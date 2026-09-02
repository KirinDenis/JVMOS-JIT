/*MIT License

Copyright (c) 2026 Allan (Slam)

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.*/

package kernel;

public class Native {

    // TABLA DE SYSCALLS
    public static final int SYS_KALLOC           = 0;  // Asignación de memoria en Heap
    public static final int SYS_SET_COLOR        = 1;  // Color activo VRAM
    public static final int SYS_FILL_RECT        = 2;  // Rellenar rectángulo
    public static final int SYS_DRAW_RECT        = 3;  // Dibujar borde rectángulo
    public static final int SYS_DRAW_LINE        = 4;  // Dibujar línea
    public static final int SYS_DRAW_STRING      = 5;  // Imprimir cadena texto
    public static final int SYS_READ_KEYBOARD    = 6;  // Leer teclado PS/2 (FIFO)
    public static final int SYS_READ_MOUSE       = 7;  // Leer ratón PS/2 (X, Y, Botones)
    public static final int SYS_DISK_READ        = 8;  // Leer sector ATA IDE LBA28
    public static final int SYS_DISK_WRITE       = 9;  // Escribir sector ATA IDE LBA28
    public static final int SYS_INB              = 10; // Puerto E/S (inb)
    public static final int SYS_OUTB             = 11; // Puerto E/S (outb)
    public static final int SYS_SLEEP            = 12; // Retardo ms (PIT IRQ0)
    public static final int SYS_GET_TIME         = 13; // CMOS RTC (Hora/Fecha)
    public static final int SYS_GET_PIXEL        = 14; // Leer pixel de VRAM
    public static final int SYS_DRAW_CHAR        = 15; // Renderizar carácter en VRAM
    public static final int SYS_SET_KBD_LAYOUT   = 16; // Mapa Teclado (0=US, 1=ES)
    public static final int SYS_EXIT             = 17; // Apagar equipo
    public static final int SYS_GET_TICKS        = 18; // Consultar ticks del sistema
    public static final int SYS_SERIAL_PUTC      = 19; // Enviar carácter por COM1
    public static final int SYS_SERIAL_PUTS      = 20; // Enviar cadena por COM1 (Debug)
    public static final int SYS_PCI_READ         = 21; // Leer espacio de config PCI
    public static final int SYS_BEEP             = 22; // Audio PC Speaker (Frecuencia Hz)
    public static final int SYS_RTL8139_INIT     = 23; // Inicializar Tarjeta de Red
    public static final int SYS_RTL8139_SEND     = 24; // Enviar paquete de Red
    public static final int SYS_NET_RECEIVE      = 25; // Recibir paquete de Red
    public static final int SYS_PRESENT          = 26; // Copiar buffer trasero a la VRAM real
    public static final int SYS_SET_CLIP         = 27; // Limitar el dibujo a un rectángulo
    public static final int SYS_FILL_BLEND       = 28; // Rellenar mezclando al 50% (sombras)
    public static final int SYS_STR_LEN          = 29; // Longitud de un literal de cadena
    public static final int SYS_SET_BLEND        = 31; // Opacidad de fill_blend (1/2^k)
    public static final int SYS_C_SELFTEST       = 32; // Comprueba que el codigo C esta enlazado
    public static final int SYS_WASM_DRAW        = 33; // Ejecuta un fotograma del invitado WebAssembly
    public static final int SYS_WASM_KEY         = 34; // Encola una tecla para el invitado
    public static final int SYS_WASM_SOUND       = 35; // Activa o silencia el sonido del invitado
    public static final int SYS_WASM_MUSIC       = 36; // Avanza el secuenciador de musica
    public static final int SYS_SB16_STATUS      = 37; // 1 si se detecto la Sound Blaster
    public static final int SYS_SND_PLAY         = 38; // Reproduce un sonido del sistema
    public static final int SYS_STR_BYTE         = 30; // Byte i-esimo de un literal de cadena
    public static final int SYS_FS_STATUS        = 39; // 0 sin disco, 1 montado, 2 formateado, 3 ajeno
    public static final int SYS_FS_COUNT         = 40; // Entradas del directorio raiz
    public static final int SYS_FS_NAME          = 41; // Byte b del nombre de la entrada a
    public static final int SYS_FS_SIZE          = 42; // Tamano en bytes de la entrada a
    public static final int SYS_FS_ISDIR         = 43; // 1 si la entrada a es un directorio
    public static final int SYS_FS_FREE_KB       = 44; // Espacio libre en KB
    public static final int SYS_FS_TOTAL_KB      = 45; // Espacio total del volumen en KB
    public static final int SYS_FS_EDIT          = 46; // Editor: a = operacion, b y c argumentos
    public static final int SYS_FS_RUN           = 47; // Lanza el programa a: 1, o codigo negativo

    // Operaciones de SYS_FS_EDIT, iguales que en fs/fat32_disk.c
    public static final int ED_CAPACITY   = 0;
    public static final int ED_LENGTH     = 1;
    public static final int ED_GET        = 2;   // b = posicion
    public static final int ED_INSERT     = 3;   // b = posicion, c = byte
    public static final int ED_DELETE     = 4;   // b = posicion
    public static final int ED_CLEAR      = 5;
    public static final int ED_OPEN       = 6;   // b = indice del directorio
    public static final int ED_SAVE       = 7;
    public static final int ED_NAME_LEN   = 8;
    public static final int ED_NAME_GET   = 9;   // b = posicion
    public static final int ED_NAME_CLEAR = 10;
    public static final int ED_NAME_PUSH  = 11;  // b = caracter
    public static final int ED_NAME_POP   = 12;
    public static final int ED_REMOVE     = 13;  // b = indice del directorio
    public static final int ED_DIRTY      = 14;
    public static final int ED_LINE_OF    = 15;  // b = posicion -> linea
    public static final int ED_LINE_START = 16;  // b = linea    -> posicion, o -1
    public static final int ED_LINE_END   = 17;  // b = posicion -> fin de esa linea


    // FIRMAS NATIVAS
    public static native int sys(int id, int a, int b, Object c, int d);
    public static native int sys(int id, int a, int b, int c, int d);
}