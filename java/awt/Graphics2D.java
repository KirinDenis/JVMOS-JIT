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

package java.awt;

import kernel.Native;

public class Graphics2D {
    private Color currentColor = Color.BLACK;

    // Fija el color directamente desde un entero 0x00RRGGBB.
    // Evita crear objetos Color en los bucles de dibujo: el heap es un bump
    // allocator sin free y cada 'new' consume 4KB irrecuperables.
    public void setRGB(int rgb) {
        Native.sys(Native.SYS_SET_COLOR, rgb, 0, 0, 0);
    }

    public void setColor(Color c) {
        if (c != null) {
            this.currentColor = c;
			// Syscall 1: Color activo VRAM
            Native.sys(Native.SYS_SET_COLOR, c.getRGB(), 0, 0, 0);
        }
    }

    public Color getColor() {
        return currentColor;
    }

    public void fillRect(int x, int y, int width, int height) {
		//Syscall 2: Rellenar rectángulo
        Native.sys(Native.SYS_FILL_RECT, x, y, width, height);
    }

    public void drawRect(int x, int y, int width, int height) {
		// Syscall 3: Dibujar borde rectángulo
        Native.sys(Native.SYS_DRAW_RECT, x, y, width, height);
    }

    public void drawLine(int x1, int y1, int x2, int y2) {
		// Syscall 4: Dibujar línea
        Native.sys(Native.SYS_DRAW_LINE, x1, y1, x2, y2);
    }

    public void drawString(String text, int x, int y) {
        if (text != null) {
			// Syscall 5: Imprimir cadena texto en modo gráfico
            Native.sys(Native.SYS_DRAW_STRING, x, y, text, 0);
        }
    }    
	
	public int drawChar(char c, int x, int y) {
        // Syscall 15: Renderizar carácter en VRAM
        Native.sys(Native.SYS_DRAW_CHAR, x, y, (int) c, 0);
        return x + 8; // Siguiente posición X (celda de 8px, igual que drawString)
    }
	
	public int drawInt(int value, int x, int y) {
        if (value == 0) {
            drawChar('0', x, y);
        }
        int temp = value;
        int len = 0;
        boolean isNegative = false;
        
        if (temp < 0) {
            isNegative = true;
            temp = -temp;
            value = temp;
            len++;
        }
        
        int t2 = temp;
        while (t2 > 0) {
            len++;
            t2 /= 10;
        }
        
        int currX = x + (len - 1) * 8;
        int endX = x + len * 8;

        temp = value;
        while (temp > 0) {
			// Syscall 15: Renderizar carácter en VRAM
            Native.sys(Native.SYS_DRAW_CHAR, currX, y, '0' + (temp % 10), 0);
            currX -= 8;
            temp /= 10;
        }
        
        if (isNegative) {
			// Syscall 15: Renderizar carácter en VRAM
            Native.sys(Native.SYS_DRAW_CHAR, currX, y, '-', 0);
        }
        
        return endX;
    }
    
    public int getPixel(int x, int y) {
		// Syscall 14: Leer pixel de VRAM
        return Native.sys(Native.SYS_GET_PIXEL, x, y, 0, 0);
    }

    // Limita todo el dibujo posterior al rectángulo dado. Sin esto el
    // contenido de una ventana se sale de su marco al reducirla.
    public void setClip(int x, int y, int w, int h) {
        Native.sys(Native.SYS_SET_CLIP, x, y, w, h);
    }

    // Opacidad de fillBlend: 1/2^k (k=1 -> 50%, k=2 -> 25%, k=3 -> 12.5%).
    public void setBlend(int k) {
        Native.sys(Native.SYS_SET_BLEND, k, 0, 0, 0);
    }

    // Rellena mezclando el color actual con el fondo, con la opacidad activa.
    public void fillBlend(int x, int y, int width, int height) {
        Native.sys(Native.SYS_FILL_BLEND, x, y, width, height);
    }

    public void present() {
        // Syscall 26: copiar el buffer trasero completo a la VRAM real (doble buffer, evita el parpadeo)
        Native.sys(Native.SYS_PRESENT, 0, 0, 0, 0);
    }

	// Faltan por añadir drawCircle, fillCircle, drawTriangle, fillTriangle, clipText, etc.
	
}
