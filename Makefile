# =========================================================================
# COMPILADORES Y BANDERAS
# =========================================================================
CC      = gcc-12
LD      = ld
AS      = nasm
JAVAC   = javac

CFLAGS  = -m32 -ffreestanding -fno-pic -fno-stack-protector -fno-builtin -nostdlib -O2 -Wall -Wextra -Iinclude
ASFLAGS = -f elf32
LDFLAGS = -m elf_i386 -T linker.ld

# Archivos de salida
KERNEL_BIN = kernel.bin
OS_ISO     = os.iso

# =========================================================================
# CAPTURA AUTÓNOMA Y ORDENAMIENTO ESTRICTO DE OBJETOS
# =========================================================================
C_SOURCES   := $(wildcard *.c) $(wildcard */*.c) $(wildcard */*/*.c)
ASM_SOURCES := $(wildcard *.asm) $(wildcard */*.asm) $(wildcard */*/*.asm)

# EXCLUIR EXPLÍCITAMENTE EL VIEJO boot_class.asm (Ya no usamos incbin)
ASM_SOURCES := $(filter-out boot/boot_class.asm, $(ASM_SOURCES))

# Mapeo dinámico a .o
C_OBJS   := $(patsubst %.c,%.o,$(C_SOURCES))
ASM_OBJS := $(patsubst %.asm,%.o,$(ASM_SOURCES))

# GARANTIZAR EL ORDEN DE ARRANQUE:
# 1. boot/multiboot.o debe ser obligatoriamente el PRIMER objeto en la lista
BOOT_OBJ  := boot/multiboot.o
REST_OBJS := $(filter-out $(BOOT_OBJ), $(ASM_OBJS) $(C_OBJS))

# Lista final ordenada sin duplicados
ALL_OBJS  := $(BOOT_OBJ) $(REST_OBJS)

# Captura de fuentes Java
JAVA_SOURCES := $(wildcard kernel/*.java) $(wildcard java/lang/*.java)

# =========================================================================
# DETECCIÓN DE ENTORNO Y COMANDOS CRUZADOS (Windows vs POSIX)
# =========================================================================
ifeq ($(OS),Windows_NT)
    CLEAN_CMD = if exist isodir rmdir /s /q isodir & if exist *.bin del /q *.bin & if exist *.iso del /q *.iso & if exist kernel\*.class del /q kernel\*.class & if exist java\lang\*.class del /q java\lang\*.class
    MKDIR     = if not exist isodir\boot\grub mkdir isodir\boot\grub & if not exist isodir\classes mkdir isodir\classes
    COPY_KERN = copy kernel.bin isodir\boot\kernel.bin >nul
    COPY_CLS  = copy kernel\*.class isodir\classes\ >nul & copy java\lang\*.class isodir\classes\ >nul
else
    CLEAN_CMD = rm -rf isodir *.bin *.iso kernel/*.class java/lang/*.class $(ALL_OBJS)
    MKDIR     = mkdir -p isodir/boot/grub isodir/classes
    COPY_KERN = cp $(KERNEL_BIN) isodir/boot/kernel.bin
    COPY_CLS  = cp kernel/*.class isodir/classes/ && cp java/lang/*.class isodir/classes/
endif

# =========================================================================
# REGLAS DE COMPILACIÓN
# =========================================================================
all: $(OS_ISO)

# 1. Compilación de archivos Java
kernel/Boot.class: $(JAVA_SOURCES)
	$(JAVAC) -g:none -Xbootclasspath/p:. -source 8 -target 8 $(JAVA_SOURCES)
	
# 2. Regla genérica Ensamblador
%.o: %.asm
	$(AS) $(ASFLAGS) $< -o $@

# 3. Regla genérica C
%.o: %.c
	$(CC) $(CFLAGS) -c $< -o $@

# 4. Enlazado final del Kernel ELF (Ya no depende de Boot.class internamente)
$(KERNEL_BIN): $(ALL_OBJS)
	$(LD) $(LDFLAGS) -o $@ $(ALL_OBJS)

# 5. Generación de la ISO con GRUB (Depende de KERNEL_BIN y Boot.class)
$(OS_ISO): $(KERNEL_BIN) kernel/Boot.class
	@$(MKDIR)
	@$(COPY_KERN)
	@$(COPY_CLS)
	@echo set timeout=0 > isodir/boot/grub/grub.cfg
	@echo set default=0 >> isodir/boot/grub/grub.cfg
	@echo menuentry "JVM-OS Self-Hosting" { >> isodir/boot/grub/grub.cfg
	@echo   set gfxpayload=1024x768x32 >> isodir/boot/grub/grub.cfg
	@echo   multiboot /boot/kernel.bin >> isodir/boot/grub/grub.cfg
	@echo   module /classes/Boot.class kernel/Boot >> isodir/boot/grub/grub.cfg
	@echo   module /classes/Native.class kernel/Native >> isodir/boot/grub/grub.cfg
	@echo   module /classes/Graphics2D.class kernel/Graphics2D >> isodir/boot/grub/grub.cfg
	@echo   module /classes/Object.class java/lang/Object >> isodir/boot/grub/grub.cfg
	@echo   module /classes/String.class java/lang/String >> isodir/boot/grub/grub.cfg
	@echo   boot >> isodir/boot/grub/grub.cfg
	@echo } >> isodir/boot/grub/grub.cfg
	grub-mkrescue -o $(OS_ISO) isodir

# Ejecución en QEMU
run: $(OS_ISO)
	qemu-system-i386 -cdrom $(OS_ISO) -drive file=disk.img,format=raw -m 128M -serial stdio -rtc base=localtime

# Limpieza
clean:
	@$(CLEAN_CMD)

.PHONY: all run clean