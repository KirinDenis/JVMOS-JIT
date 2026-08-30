; HEADER MULTIBOOT Y ARRANQUE INICIAL CON GDT
MBALIGN  equ  1 << 0
MEMINFO  equ  1 << 1
VIDINFO  equ  1 << 2
FLAGS    equ  MBALIGN | MEMINFO | VIDINFO
MAGIC    equ  0x1BADB002
CHECKSUM equ -(MAGIC + FLAGS)

section .multiboot
align 4
    dd MAGIC
    dd FLAGS
    dd CHECKSUM
    dd 0, 0, 0, 0, 0
    ; Petición de Video a GRUB (1024x768 x 32bpp)
    dd 0
    dd 1024
    dd 768
    dd 32

section .bootstrap_stack nobits
align 16
stack_bottom:
    resb 32768 ; Pila x86 reservada de 32 KB
stack_top:

; Variables globales expuestas para la JVM
global g_framebuffer
global g_width
global g_height
global g_pitch

section .data
g_framebuffer: dd 0
g_width:       dd 1024
g_height:      dd 768
g_pitch:       dd 4096

; GDT BÁSICA DE 32 BITS
align 16
gdt_start:
    ; Descriptor 0x00: Nulo
    dd 0x00000000, 0x00000000

    ; Descriptor 0x08: Código (Base 0, Límite 4GB, R0, Exec/Read)
    dd 0x0000FFFF, 0x00CF9A00

    ; Descriptor 0x10: Datos (Base 0, Límite 4GB, R0, Read/Write)
    dd 0x0000FFFF, 0x00CF9200
gdt_end:

gdtr:
    dw gdt_end - gdt_start - 1
    dd gdt_start

section .text
global _start
extern bootjvm_start

_start:
    cli
    mov esp, stack_top

    ; 1. CARGAR NUESTRA PROPIA GDT (Evita el Triple Fault de GRUB)
    lgdt [gdtr]
    jmp 0x08:.reload_segments

.reload_segments:
    mov ax, 0x10
    mov ds, ax
    mov es, ax
    mov fs, ax
    mov gs, ax
    mov ss, ax

    ; 2. Capturar datos de Video Multiboot
    cmp ebx, 0
    je .fallback_vram

    mov eax, [ebx]
    test eax, (1 << 11)
    jz .fallback_vram

    mov edi, [ebx + 76]
    cmp edi, 0
    je .fallback_vram

    mov eax, [edi + 40]
    cmp eax, 0
    je .fallback_vram

    mov [g_framebuffer], eax

    mov ax, [edi + 18]        ; XResolution
    movzx eax, ax
    mov [g_width], eax

    mov ax, [edi + 20]        ; YResolution
    movzx eax, ax
    mov [g_height], eax

    mov ax, [edi + 16]        ; Pitch
    movzx eax, ax
    mov [g_pitch], eax
    jmp .start_jvm

.fallback_vram:
    mov dword [g_framebuffer], 0xFD000000 
    mov dword [g_pitch], 4096

.start_jvm:
	push ebx	; pasa puntero como argumento
    call bootjvm_start
	add esp, 4	; limpiar pila

.hang:
    hlt
    jmp .hang

section .note.GNU-stack noalloc noexec nowrite progbits
