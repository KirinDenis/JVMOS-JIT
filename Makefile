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

# Captura de fuentes
C_SOURCES   := $(wildcard *.c) $(wildcard */*.c)
ASM_SOURCES := $(wildcard *.asm) $(wildcard */*.asm) $(wildcard */*/*.asm)
ASM_SOURCES := $(filter-out boot/boot_class.asm, $(ASM_SOURCES))

C_OBJS   := $(patsubst %.c,%.o,$(C_SOURCES))
ASM_OBJS := $(patsubst %.asm,%.o,$(ASM_SOURCES))

BOOT_OBJ  := boot/multiboot.o
REST_OBJS := $(filter-out $(BOOT_OBJ), $(ASM_OBJS) $(C_OBJS))
ALL_OBJS  := $(BOOT_OBJ) $(REST_OBJS)

# LISTA ESTRICTA DE CLASES JAVA A COMPILAR
JAVA_SOURCES := kernel/Boot.java kernel/Native.java kernel/vfs/Node.java \
                java/lang/Object.java java/lang/String.java java/lang/StringBuilder.java \
                java/lang/System.java java/lang/Thread.java java/lang/Runtime.java \
                java/awt/Color.java java/awt/Graphics2D.java java/awt/Toolkit.java \
                java/io/PrintStream.java java/io/RandomAccessFile.java \
                java/net/DatagramPacket.java java/net/RawSocket.java \
                java/util/Calendar.java

# =========================================================================
# COMANDOS SEGUROS DE COPIA DE CLASES
# =========================================================================
ifeq ($(OS),Windows_NT)
    CLEAN_CMD = if exist isodir rmdir /s /q isodir & del /s /q *.bin *.iso *.class *.o
    MKDIR     = if not exist isodir\boot\grub mkdir isodir\boot\grub & if not exist isodir\classes mkdir isodir\classes
    COPY_KERN = copy kernel.bin isodir\boot\kernel.bin >nul
    # Windows: Copia carpeta por carpeta garantizando que no se omita ninguna
    COPY_CLS  = copy kernel\*.class isodir\classes\ >nul & copy kernel\vfs\*.class isodir\classes\ >nul & copy java\lang\*.class isodir\classes\ >nul & copy java\awt\*.class isodir\classes\ >nul & copy java\io\*.class isodir\classes\ >nul & copy java\net\*.class isodir\classes\ >nul & copy java\util\*.class isodir\classes\ >nul
else
    CLEAN_CMD = rm -rf isodir *.bin *.iso $(ALL_OBJS) $$(find . -name "*.class")
    MKDIR     = mkdir -p isodir/boot/grub isodir/classes
    COPY_KERN = cp $(KERNEL_BIN) isodir/boot/kernel.bin
    # POSIX: Encuentra y copia cualquier .class
    COPY_CLS  = find kernel java -name "*.class" -exec cp {} isodir/classes/ \;
endif

# =========================================================================
# REGLAS DE COMPILACIÓN
# =========================================================================
all: $(OS_ISO)

kernel/Boot.class: $(JAVA_SOURCES)
	$(JAVAC) -g:none -Xbootclasspath/p:. -source 8 -target 8 $(JAVA_SOURCES)

%.o: %.asm
	$(AS) $(ASFLAGS) $< -o $@

$(KERNEL_BIN): $(ALL_OBJS)
	$(LD) $(LDFLAGS) -o $@ $(ALL_OBJS)

$(OS_ISO): $(KERNEL_BIN) kernel/Boot.class
	@$(MKDIR)
	@$(COPY_KERN)
	@$(COPY_CLS)
	@echo set timeout=0 > isodir/boot/grub/grub.cfg
	@echo set default=0 >> isodir/boot/grub/grub.cfg
	@echo menuentry "JVM-OS Self-Hosting" { >> isodir/boot/grub/grub.cfg
	@echo    set gfxpayload=1024x768x32 >> isodir/boot/grub/grub.cfg
	@echo    multiboot /boot/kernel.bin >> isodir/boot/grub/grub.cfg
	@echo    module /classes/Boot.class kernel/Boot >> isodir/boot/grub/grub.cfg
	@echo    module /classes/Native.class kernel/Native >> isodir/boot/grub/grub.cfg
	@echo    module /classes/Node.class kernel/vfs/Node >> isodir/boot/grub/grub.cfg
	@echo    module /classes/Object.class java/lang/Object >> isodir/boot/grub/grub.cfg
	@echo    module /classes/String.class java/lang/String >> isodir/boot/grub/grub.cfg
	@echo    module /classes/StringBuilder.class java/lang/StringBuilder >> isodir/boot/grub/grub.cfg
	@echo    module /classes/System.class java/lang/System >> isodir/boot/grub/grub.cfg
	@echo    module /classes/Thread.class java/lang/Thread >> isodir/boot/grub/grub.cfg
	@echo    module /classes/Runtime.class java/lang/Runtime >> isodir/boot/grub/grub.cfg
	@echo    module /classes/Color.class java/awt/Color >> isodir/boot/grub/grub.cfg
	@echo    module /classes/Graphics2D.class java/awt/Graphics2D >> isodir/boot/grub/grub.cfg
	@echo    module /classes/Toolkit.class java/awt/Toolkit >> isodir/boot/grub/grub.cfg
	@echo    module /classes/PrintStream.class java/io/PrintStream >> isodir/boot/grub/grub.cfg
	@echo    module /classes/RandomAccessFile.class java/io/RandomAccessFile >> isodir/boot/grub/grub.cfg
	@echo    module /classes/DatagramPacket.class java/net/DatagramPacket >> isodir/boot/grub/grub.cfg
	@echo    module /classes/RawSocket.class java/net/RawSocket >> isodir/boot/grub/grub.cfg
	@echo    module /classes/Calendar.class java/util/Calendar >> isodir/boot/grub/grub.cfg
	@echo    boot >> isodir/boot/grub/grub.cfg
	@echo } >> isodir/boot/grub/grub.cfg
	grub-mkrescue -o $(OS_ISO) isodir

run: $(OS_ISO)
	qemu-system-i386 -cdrom $(OS_ISO) -drive file=disk.img,format=raw -m 128M -serial stdio -rtc base=localtime

clean:
	@$(CLEAN_CMD)

.PHONY: all run clean
