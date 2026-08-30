[bits 32]
global idt_load
global isr_common_stub
extern isr_handler
global isr128

idt_load:
    mov eax, [esp + 4]
    lidt [eax]
    ret

%macro ISR_NOERRCODE 1
global isr%1
isr%1:
    push 0
    push %1
    jmp isr_common_stub
%endmacro

%macro ISR_ERRCODE 1
global isr%1
isr%1:
    push %1
    jmp isr_common_stub
%endmacro

; --- GENERACIÓN AUTOMÁTICA DE EXCEPCIONES (0-31) ---
%assign i 0
%rep 32
    %if i == 8 || (i >= 10 && i <= 14) || i == 17
        ISR_ERRCODE i
    %else
        ISR_NOERRCODE i
    %endif
    %assign i i+1
%endrep

; --- GENERACIÓN AUTOMÁTICA DE IRQs (32-47) ---
%assign i 32
%rep 16
    ISR_NOERRCODE i
    %assign i i+1
%endrep

; --- SYSCALL INT 0x80 ---
isr128:
    push 0
    push 128
    jmp isr_common_stub

isr_common_stub:
    pusha

    xor eax, eax
    mov ax, gs
    push eax

    xor eax, eax
    mov ax, fs
    push eax

    xor eax, eax
    mov ax, es
    push eax

    xor eax, eax
    mov ax, ds
    push eax

    mov ax, 0x10    ; Selector de datos del kernel
    mov ds, ax
    mov es, ax
    mov fs, ax
    mov gs, ax

	push esp        ; Pasamos el puntero a la estructura registers_t
    call isr_handler
    add esp, 4

    pop eax
    mov ds, ax

    pop eax
    mov es, ax

    pop eax
    mov fs, ax

    pop eax
    mov gs, ax

    popa

    add esp, 8      ; Limpia el error code y el número de ISR
    iret

isr_handler:
    ; Manejador básico temporal en Assembly pura (sin C)
    pusha
    mov al, 0x20
    out 0x20, al ; Enviar End of Interrupt (EOI) al Master PIC
    popa
    iret
	
section .note.GNU-stack noalloc noexec nowrite progbits