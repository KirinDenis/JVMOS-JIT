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

public class Graphics2D {
	
    // Primitivas de la clase Graphics adaptadas a mi ABI
    public static void setColor(int rgb) {
        Native.sys(Native.SYS_SET_COLOR, rgb, 0, 0, 0);
    }
	
	/*public static void setColor(String colorRgb){
		int rgb = 0x00000000; // negro por defecto
		
		switch(colorRgb){
			case "rojo":
			case "ROJO":
			case "red":
			case "RED":
				rgb = 0x00FF0000;
			break;
			
			case "verde":
			case "VERDE":
			case "green":
			case "GREEN":
				rgb = 0x0000FF00;
			break;
			
			case "azul":
			case "AZUL":
			case "blue":
			case "BLUE":
				rgb = 0x000000FF;
			break;
			
			case "negro":
			case "NEGRO":
			case "black":
			case "BLACK":
				//rgb = 0x00000000;
			break;
			
			case "amarillo":
			case "AMARILLO":
			case "yellow":
			case "YELLOW":
				rgb = 0x00FFFF00;
			break;
		
			case "blanco":
			case "BLANCO":
			case "white":
			case "WHITE":
				rgb = 0x00FFFFFF;
			break;			
		}
		Native.sys(Native.SYS_SET_COLOR, rgb, 0, 0, 0);
	}*/

    public static void fillRect(int x, int y, int w, int h) {
        Native.sys(Native.SYS_FILL_RECT, x, y, w, h);
    }

    public static void drawRect(int x, int y, int w, int h) {
        Native.sys(Native.SYS_DRAW_RECT, x, y, w, h);
    }

    public static void drawLine(int x1, int y1, int x2, int y2) {
        Native.sys(Native.SYS_DRAW_LINE, x1, y1, x2, y2);
    }

    public static void drawString(int x, int y, String text) {
        Native.sys(Native.SYS_DRAW_STRING, x, y, text, 0);
    }

    // Faltan por añadir drawCircle, fillCircle, drawTriangle, fillTriangle, clipText, etc.
	// Voy a usar referencia de la clase original
}
