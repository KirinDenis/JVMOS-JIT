; MIT License
;
; Copyright (c) 2026 Allan (Slam)
;
; Permission is hereby granted, free of charge, to any person obtaining a copy
; of this software and associated documentation files (the "Software"), to deal
; in the Software without restriction, including without limitation the rights
; to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
; copies of the Software, and to permit persons to whom the Software is
; furnished to do so, subject to the following conditions:
;
; The above copyright notice and this permission notice shall be included in all
; copies or substantial portions of the Software.
;
; THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
; IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
; FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
; AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
; LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
; OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
; SOFTWARE.

[bits 32]

global bootjvm_start

; Estado y Variables globales de la VM
global cp_offsets
global current_class_ptr
global pc_ptr
global cp_end_ptr
global methods_ptr

global sys_arg_id
global sys_arg_a
global sys_arg_b
global sys_arg_c
global sys_arg_d
global cp_base_ptr
global find_method_bytecode
global current_param_count

; Invocaciones externas
extern sys_hardware_init
extern sys_kalloc
extern sys_hlt
extern sys_serial_puts
extern sys_native_dispatch      ; Enlace directo al despachador de Syscalls HAL

extern jit_init
extern jit_compile_method
extern jit_emit_byte 
extern jit_emit_dword
extern jit_buffer_ptr

section .text

global resolve_and_compile_java_method

bootjvm_start:
    push ebp 
    mov ebp, esp
    call sys_hardware_init
	
	mov ebx, [ebp+8]
    
    mov eax, [ebx]              ; Leer flags de Multiboot
    test eax, 8                 ; ¿El bit 3 (Mods) está activo?
    jz .no_modules

    mov ecx, [ebx + 20]         ; Cantidad de módulos
    mov esi, [ebx + 24]         ; Dirección de la lista de módulos
    mov edi, 0                  ; Índice de la tabla

.mod_loop:
    cmp ecx, 0
    jle .done_modules

    mov eax, [esi]              ; mod_start (Dirección física en RAM)
    mov edx, [esi + 8]          ; string (Nombre asignado en GRUB)

    mov [class_addr_ptr + edi * 4], eax
    mov [class_name_ptr + edi * 4], edx

    inc edi
    add esi, 16                 ; Avanzar al siguiente módulo (16 bytes por entrada)
    dec ecx
    jmp .mod_loop

.no_modules:
    mov edi, 0
.done_modules:
    mov [class_count], edi      ; Guardar total de clases cargadas

    push msg_dbg_start
    call sys_serial_puts
    add esp, 4

    ; Obtener Boot.class de GRUB
    cmp dword [class_count], 0
    je fatal_no_boot_class      ; Pánico si GRUB no cargó nada

    mov esi, [class_addr_ptr]   ; Leer Módulo 0 (Boot.class)
    mov [current_class_ptr], esi

    ; Validar Magic 'CAFEBABE'
    mov eax, [esi]
    bswap eax
    cmp eax, 0xCAFEBABE
    jne fatal_magic_error

    push msg_dbg_magic_ok
    call sys_serial_puts
    add esp, 4

    ; Parsear Constant Pool
    call parse_constant_pool
    jc fatal_class_format_error

    push msg_dbg_cp_ok
    call sys_serial_puts
    add esp, 4

    ; Cachear puntero a método e inicializar JIT global
    call parse_class_structure

    ; Asignar 1MB para la memoria persistente del JIT
    mov eax, 0x00200000
    mov ebx, 1048576
    call jit_init

    ; Buscar MAIN en bytecode
    push dword main_name_str
    push dword 4
    call find_method_bytecode
    add esp, 8

    test eax, eax
    jz fatal_main_not_found

    mov [pc_ptr], eax
    mov esi, eax                 

    mov ecx, [esi - 4]          
    bswap ecx                   ; Convierte de BE a LE

    push msg_dbg_main_ok
    call sys_serial_puts
    add esp, 4
    
    ; Ejecutando main de Boot.java
    call jit_compile_method
    
    push dword 0            ; Argumento del main (args = null)
    call eax                ; Ejecuta el código nativo compilado de Boot.main
    add esp, 4              ; Limpiar el argumento de la pila nativa si retorna

    push eax
    push msg_dbg_jit_done
    call sys_serial_puts
    add esp, 4
    pop eax

    jmp fatal_halt

; Extraer estructura de la clase (1 vez)
parse_class_structure:
    mov esi, [cp_end_ptr]
    add esi, 6                  ; Saltar access_flags, this_class, super_class

    ; Consumir Interfaces
    mov ax, [esi]
    xchg al, ah
    movzx eax, ax               ; interfaces_count
    add esi, 2
    shl eax, 1                  ; count * 2 bytes
    add esi, eax

    ; Consumir Fields
    mov ax, [esi]
    xchg al, ah
    movzx ecx, ax               ; fields_count
    add esi, 2

.skip_fields_loop:
    cmp ecx, 0
    jle .fields_done
    add esi, 6                  ; access_flags, name_index, descriptor_index
    mov ax, [esi]
    xchg al, ah
    movzx eax, ax               ; attributes_count
    add esi, 2

.skip_f_attrs:
    cmp eax, 0
    je .next_field
    add esi, 2                  ; attribute_name_index
    mov ebx, [esi]
    bswap ebx                   ; attribute_length
    add esi, 4
    add esi, ebx
    dec eax
    jmp .skip_f_attrs

.next_field:
    dec ecx
    jmp .skip_fields_loop

.fields_done:
    mov [methods_ptr], esi      ; Guardar el inicio exacto de los métodos
    ret

; Buscador de bytecode
find_method_bytecode:
    push ebp
    mov ebp, esp
    sub esp, 8                  ; Variables locales: [ebp-4]=saved_esi, [ebp-8]=saved_edi
    push ebx
    push ecx
    push edx
    push esi
    push edi

    ; Iniciar directamente desde la cache de métodos
    mov esi, [methods_ptr]

    ; Leer Methods Count
    mov ax, [esi]
    xchg al, ah
    movzx ecx, ax               ; methods_count
    add esi, 2

.search_methods_loop:
    cmp ecx, 0
    jle .method_not_found

    mov edi, esi                ; Guardar puntero base del método en EDI

    mov ax, [esi + 2]           ; name_index (offset +2)
    xchg al, ah
    movzx eax, ax

    mov ebx, [cp_offsets + eax * 4]
    test ebx, ebx
    jz .skip_this_method

    inc ebx                     ; Tag Utf8
    mov ax, [ebx]
    xchg al, ah
    movzx edx, ax               ; Longitud del nombre en CP
    add ebx, 2                  ; Texto ASCII en CP

    ; Comparar longitud esperada ([ebp + 8])
    mov eax, [ebp + 8]
    cmp eax, edx
    jne .skip_this_method

    ; Preservar ESI/EDI actuales antes del cmpsb
    mov [ebp - 4], esi
    mov [ebp - 8], edi

    mov esi, [ebp + 12]         ; Puntero al nombre buscado
    mov edi, ebx                ; Puntero al nombre en CP
    push ecx
    mov ecx, edx
    repe cmpsb
    pop ecx

    mov esi, [ebp - 4]          ; Restaurar ESI intacto
    mov edi, [ebp - 8]          ; Restaurar EDI intacto
    jne .skip_this_method

    ; Si encuentra el método, ir a attributes_count
    add esi, 6
    mov ax, [esi]
    xchg al, ah
    movzx edx, ax               ; attributes_count
    add esi, 2

.search_code:
    cmp edx, 0
    je .skip_this_method

    mov ax, [esi]               ; attribute_name_index
    xchg al, ah
    movzx eax, ax

    mov ebx, [cp_offsets + eax * 4]
    add ebx, 3                  ; Saltar Tag(1B) y Length(2B)
    mov eax, [ebx]
    cmp eax, 0x65646F43          ; Magic "Code"
    je .found_code

    add esi, 2
    mov eax, [esi]
    bswap eax                   ; attribute_length
    add esi, 4
    add esi, eax
    dec edx
    jmp .search_code

.found_code:
    add esi, 14                 ; Saltar cabecera Code_attribute
    mov eax, esi
    jmp .find_done

.skip_this_method:
    mov esi, edi                ; Restaurar ESI desde EDI
    add esi, 6
    mov ax, [esi]
    xchg al, ah
    movzx edx, ax               ; attributes_count
    add esi, 2
.skip_attrs:
    cmp edx, 0
    je .next_method
    add esi, 2
    mov eax, [esi]
    bswap eax                   ; attribute_length
    add esi, 4
    add esi, eax
    dec edx
    jmp .skip_attrs

.next_method:
    dec ecx
    jmp .search_methods_loop

.method_not_found:
    xor eax, eax

.find_done:
    ; Extraer banderas para saber si es static o virtual
    push eax
    push esi
    
    mov ax, [edi]
    xchg al, ah
    movzx ecx, ax               ; ECX = access_flags
    
    ; Extraer la cadena del Descriptor
    mov ax, [edi + 4]
    xchg al, ah
    movzx ebx, ax               ; EBX = descriptor_index
    mov ebx, [cp_offsets + ebx * 4]
    add ebx, 3                  ; EBX = Puntero al texto UTF-8
    
    xor edx, edx                ; EDX = Contador de parámetros
    test ecx, 0x0008            ; ACC_STATIC
    jnz .parse_desc
    inc edx                     ; 'this' implícito

.parse_desc:
    inc ebx                     ; Saltar '('
.desc_loop:
    mov al, [ebx]
    inc ebx
    cmp al, ')'
    je .desc_done
    cmp al, '['
    je .desc_loop
    cmp al, 'L'
    jne .check_double
.skip_class:
    mov al, [ebx]
    inc ebx
    cmp al, ';'
    jne .skip_class
    inc edx
    jmp .desc_loop
.check_double:
    cmp al, 'D'
    je .is_double
    cmp al, 'J'
    je .is_double
    inc edx
    jmp .desc_loop
.is_double:
    add edx, 2
    jmp .desc_loop

.desc_done:
    mov [current_param_count], edx

    pop esi
    pop eax
    pop edi
    pop esi
    pop edx
    pop ecx
    pop ebx
    mov esp, ebp
    pop ebp
    ret

; Resolución y compilación en tiempo de ejecución (JIT Trampoline)
resolve_and_compile_java_method:
    push ebp
    mov ebp, esp
    sub esp, 20                 
    push ebx
    push ecx
    push edx
    push esi
    push edi	

    ; Guardar clase llamadora
    mov [ebp - 20], edx
    mov [current_class_ptr], edx
	
	push eax
    call parse_constant_pool
	pop eax

    ; 1. Obtener la entrada CONSTANT_Methodref_info
    mov ebx, [cp_offsets + eax * 4]

    ; 2. Extraer Clase Destino
    movzx ecx, word [ebx + 1]
    xchg cl, ch
    mov ecx, [cp_offsets + ecx * 4]     
    
    movzx ecx, word [ecx + 1]           
    xchg cl, ch
    mov ecx, [cp_offsets + ecx * 4]     
    
    mov ax, word [ecx + 1]
    xchg al, ah
    movzx edi, ax                       
    add ecx, 3                          

    mov [ebp - 4], ecx                  
    mov [ebp - 8], edi                  

    ; 3. Extraer Método Destino
    movzx ecx, word [ebx + 3]
    xchg cl, ch
    mov ecx, [cp_offsets + ecx * 4]     
    
    movzx ecx, word [ecx + 1]
    xchg cl, ch
    mov ecx, [cp_offsets + ecx * 4]     
    
    mov ax, word [ecx + 1]
    xchg al, ah
    movzx edi, ax                       
    add ecx, 3                          

    mov [ebp - 12], ecx                 
    mov [ebp - 16], edi                 

    ; EVALUACIÓN Y DESPACHO EN EL ORQUESTADOR 
    mov esi, [ebp - 4]                  
    mov ecx, [ebp - 8]                  

	; Hack que impide que se lean otros archivos .class desde el grub
    ;cmp dword [esi], 0x6176616A         ; "java"
    ;je .bypass_method

    cmp ecx, 6
    jl .check_user_class
    mov edi, esi
    add edi, ecx
    sub edi, 6
    cmp dword [edi], 0x6974614E         ; "Nati"
    jne .check_user_class
    cmp word [edi + 4], 0x6576          ; "ve"
    je .emit_native_syscall_wrapper

.check_user_class:
    push dword [ebp - 4]
    push dword [ebp - 8]
    call find_class_in_grub
    add esp, 8

    mov [current_class_ptr], eax
    call parse_constant_pool
    call parse_class_structure

    push dword [ebp - 12]
    push dword [ebp - 16]
    call find_method_bytecode
    add esp, 8

    test eax, eax
    jz .panic

    mov esi, eax
    mov ecx, [esi - 4]
    bswap ecx
    call jit_compile_method
    jmp .done

.emit_native_syscall_wrapper:
    mov eax, [jit_buffer_ptr]
    push eax

    ; 1. Crear marco de pila x86 estándar
    mov al, 0x55                ; push ebp
    call jit_emit_byte
    mov al, 0x89                ; mov ebp, esp
    call jit_emit_byte
    mov al, 0xE5
    call jit_emit_byte

    mov al, 0x53                ; push ebx
    call jit_emit_byte
    mov al, 0x56                ; push esi
    call jit_emit_byte
    mov al, 0x57                ; push edi
    call jit_emit_byte

    ; 2. Extraer parámetros (ORDEN NORMAL: ID está al fondo de la pila en [ebp+24])
    mov al, 0x8B                ; mov eax, [ebp + 24]
    call jit_emit_byte
    mov al, 0x45
    call jit_emit_byte
    mov al, 0x18
    call jit_emit_byte
    mov al, 0xA3                ; mov [sys_arg_id], eax
    call jit_emit_byte
    mov eax, sys_arg_id
    call jit_emit_dword

    mov al, 0x8B                ; mov eax, [ebp + 20]
    call jit_emit_byte
    mov al, 0x45
    call jit_emit_byte
    mov al, 0x14
    call jit_emit_byte
    mov al, 0xA3                ; mov [sys_arg_a], eax
    call jit_emit_byte
    mov eax, sys_arg_a
    call jit_emit_dword

    mov al, 0x8B                ; mov eax, [ebp + 16]
    call jit_emit_byte
    mov al, 0x45
    call jit_emit_byte
    mov al, 0x10
    call jit_emit_byte
    mov al, 0xA3                ; mov [sys_arg_b], eax
    call jit_emit_byte
    mov eax, sys_arg_b
    call jit_emit_dword

    mov al, 0x8B                ; mov eax, [ebp + 12]
    call jit_emit_byte
    mov al, 0x45
    call jit_emit_byte
    mov al, 0x0C
    call jit_emit_byte
    mov al, 0xA3                ; mov [sys_arg_c], eax
    call jit_emit_byte
    mov eax, sys_arg_c
    call jit_emit_dword

    mov al, 0x8B                ; mov eax, [ebp + 8]
    call jit_emit_byte
    mov al, 0x45
    call jit_emit_byte
    mov al, 0x08
    call jit_emit_byte
    mov al, 0xA3                ; mov [sys_arg_d], eax
    call jit_emit_byte
    mov eax, sys_arg_d
    call jit_emit_dword

    ; 3. Ejecutar Syscall en HAL
    mov al, 0xE8                ; call sys_native_dispatch
    call jit_emit_byte
    mov eax, sys_native_dispatch
    mov ebx, [jit_buffer_ptr]
    add ebx, 4
    sub eax, ebx
    call jit_emit_dword

    ; 4. Restaurar registros y stack frame
    mov al, 0x5F                ; pop edi
    call jit_emit_byte
    mov al, 0x5E                ; pop esi
    call jit_emit_byte
    mov al, 0x5B                ; pop ebx
    call jit_emit_byte
    mov al, 0x5D                ; pop ebp
    call jit_emit_byte

    ; 5. Retornar limpiando los 20 bytes (stdcall)
    mov al, 0xC2                ; ret 20
    call jit_emit_byte
    mov al, 20
    call jit_emit_byte
    mov al, 0
    call jit_emit_byte

    ; Limpiar variable global pos-envoltorio
    mov dword [current_param_count], 0

    pop eax
    jmp .done

.bypass_method:
    mov al, 0xC3
    call jit_emit_byte
    mov eax, [jit_buffer_ptr]
    dec eax                             
    jmp .done

.panic:
    push msg_err_resolve
    call sys_serial_puts
    mov eax, [ebp - 12]
    push eax
    call sys_serial_puts
    add esp, 8
    cli
    hlt

.done:
    push eax                    
    
    mov edx, [ebp - 20]
    mov [current_class_ptr], edx
    call parse_constant_pool
    call parse_class_structure  
    
    pop eax                     

    pop edi
    pop esi
    pop edx
    pop ecx
    pop ebx
    add esp, 20
    mov esp, ebp
    pop ebp
    ret

; Buscador en Módulos GRUB
find_class_in_grub:
    push ebp
    mov ebp, esp
    push ebx
    push ecx
    push edx
    push esi
    push edi

    mov edx, [class_count]
    test edx, edx
    jz .not_found

    mov ebx, 0
.loop_mods:
    cmp ebx, edx
    jge .not_found

    mov esi, [class_name_ptr + ebx * 4]
    test esi, esi
    jz .next_mod

.scan_char:
    cmp byte [esi], 0
    je .next_mod

    push esi
    mov edi, [ebp + 12]         ; Target String de Java
    mov ecx, [ebp + 8]          ; Target String Length
    repe cmpsb
    pop esi
    je .found

    inc esi
    jmp .scan_char

.next_mod:
    inc ebx
    jmp .loop_mods

.found:
    mov eax, [class_addr_ptr + ebx * 4]
    jmp .done

.not_found:
    mov eax, [class_addr_ptr]   ; Fallback a Boot.class (Módulo 0)
.done:
    pop edi
    pop esi
    pop edx
    pop ecx
    pop ebx
    mov esp, ebp
    pop ebp
    ret

; Parser al CONSTANT POOL
parse_constant_pool:
    mov esi, [current_class_ptr]
    add esi, 8                      ; Saltar Magic y Version

    mov dword [cp_base_ptr], cp_offsets

    mov ax, [esi]
    xchg al, ah
    movzx ecx, ax
    dec ecx
    add esi, 2

    mov ebx, 1
.cp_loop:
    cmp ecx, 0
    jle .cp_done

    mov [cp_offsets + ebx * 4], esi

    mov al, [esi]
    inc esi
    cmp al, 1
    je .tag_utf8
    cmp al, 5
    je .tag_8b
    cmp al, 6
    je .tag_8b
    cmp al, 15
    je .tag_3b
    cmp al, 7
    je .tag_2b
    cmp al, 8
    je .tag_2b
    cmp al, 16
    je .tag_2b
    
    add esi, 4
    jmp .next_entry

.tag_utf8:
    mov ax, [esi]
    xchg al, ah
    movzx eax, ax
    add esi, 2
    add esi, eax
    jmp .next_entry

.tag_2b:
    add esi, 2
    jmp .next_entry

.tag_3b:
    add esi, 3
    jmp .next_entry

.tag_8b:
    add esi, 8
    inc ebx
    dec ecx

.next_entry:
    inc ebx
    dec ecx
    jmp .cp_loop

.cp_done:
    mov [cp_end_ptr], esi
    clc
    ret

fatal_magic_error:
    push msg_err_magic
    call sys_serial_puts
    add esp, 4
    jmp fatal_halt

fatal_class_format_error:
    push msg_err_format
    call sys_serial_puts
    add esp, 4
    jmp fatal_halt

fatal_no_boot_class:
    push msg_err_noboot
    call sys_serial_puts
    add esp, 4
    jmp fatal_halt

fatal_main_not_found:
    push msg_err_nomain
    call sys_serial_puts
    add esp, 4
    jmp fatal_halt

fatal_halt:
    cli
.loop:
    call sys_hlt    
    jmp .loop

section .rodata
main_name_str:         db "main"
msg_dbg_start:         db 13, 10, "[BootJVM] Iniciando JVM Kernel [Modo JIT]...", 13, 10, 0
msg_dbg_magic_ok:      db "[BootJVM] Número mágico 'CAFEBABE' verificado. Archivo Java-bytecode válido", 13, 10, 0
msg_dbg_cp_ok:         db "[BootJVM] Constant Pool parseado correctamente", 13, 10, 0
msg_dbg_main_ok:       db "[BootJVM] Método 'main' encontrado. Compilando via JIT...", 13, 10, 0

msg_dbg_jit_done:      db 13, 10, "[SUCCESS] Ejecución Boot.main terminada", 13, 10, 0

msg_err_magic:         db 13, 10, "[BootJVM Panico] Número mágico incorrecto", 13, 10, 0
msg_err_format:        db 13, 10, "[BootJVM Panico] Constant Pool corrupta", 13, 10, 0
msg_err_noboot:        db 13, 10, "[BootJVM Panico] Modulo Boot.class no encontrado en GRUB!", 13, 10, 0
msg_err_nomain:        db 13, 10, "[BootJVM Panico] Método 'main' no encontrado", 13, 10, 0
msg_err_resolve:       db 13, 10, "[BootJVM Panico] Error: Metodo no encontrado en la clase", 13, 10, 0

section .data
sys_arg_id:        dd 0
sys_arg_a:         dd 0
sys_arg_b:         dd 0
sys_arg_c:         dd 0
sys_arg_d:         dd 0

current_class_ptr: dd 0
cp_end_ptr:        dd 0
pc_ptr:            dd 0
cp_base_ptr:       dd 0
methods_ptr:       dd 0 
current_param_count: dd 0

section .bss
cp_offsets:        resd 1024

align 4
class_name_ptr: resd 32
class_addr_ptr: resd 32
class_count:    resd 1

section .note.GNU-stack noalloc noexec nowrite progbits
