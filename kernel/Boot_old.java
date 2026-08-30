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

public class Boot_old {   

	public static void main(String[] args) {
		// Inicializar el teclado
		initKeyboard();
		
		// Imprime un POST/BIOS no real
		dramaticBIOS();

		// Imprime un shell estático
		shell();
		
		// Variables globales
		int cursorX = 85;
		int cursorY = 80;
		int lastKey = 0;
		int cmdLen = 0;        
		int[] cmdBuffer = new int[16];
		
		showCursor(cursorY);

		// Ciclo principal del sistema
		while (true) {
			int asciiChar = readKeyboardKey(0);

			if (asciiChar != 0 && asciiChar != lastKey) {

				if (asciiChar == 13) { // ENTER
					cursorY += 25; 		

				// --- INYECCIÓN DE DEPURACIÓN ---
				debugBuffer(cmdBuffer, cmdLen, cursorY);
				cursorY += 25;
				// -------------------------------					
					
					// Manejo de comandos
					if (cmdLen == 6 && cmdBuffer[0] == 's' && cmdBuffer[1] == 't' && cmdBuffer[2] == 'a' && cmdBuffer[3] == 'r' && cmdBuffer[4] == 't' && cmdBuffer[5] == 'x') {
						runStartX(cursorY);
						cursorY = 40;
					}					
					else if(cmdLen == 3 && cmdBuffer[0] == 'v' && cmdBuffer[1] == 'e' && cmdBuffer[2] == 'r'){	
						setColor(0x0000FF00); // verde
						drawString(20, cursorY, "JVMOS Kernel v2.5 (Baremetal Java x86)");
						cursorY += 25;
						drawString(20, cursorY, "Hecho por Slam 2026");
						cursorY += 25;
					}
					else if (cmdLen == 4 && cmdBuffer[0] == 't' && cmdBuffer[1] == 'i' && cmdBuffer[2] == 'm' && cmdBuffer[3] == 'e') {
						showTime(cursorY);
						cursorY += 25;
					}
					else if (cmdLen == 4 && cmdBuffer[0] == 'd' && cmdBuffer[1] == 'a' && cmdBuffer[2] == 't' && cmdBuffer[3] == 'e') {
						showDate(cursorY);
						cursorY += 25;
					}
					else if ((cmdLen == 5 && cmdBuffer[0] == 'c' && cmdBuffer[1] == 'l' && cmdBuffer[2] == 'e' && cmdBuffer[3] == 'a' && cmdBuffer[4] == 'r') || (cmdLen == 3 && cmdBuffer[0] == 'c' && cmdBuffer[1] == 'l' && cmdBuffer[2] == 's')) {
						clearScreen();
						cursorY = 40;
					}
					else if (cmdLen == 4 && cmdBuffer[0] == 'h' && cmdBuffer[1] == 'e' && cmdBuffer[2] == 'l' && cmdBuffer[3] == 'p') {
						setColor(0x0000FF00); // verde
						drawString(20, cursorY, "COMANDOS: help | startx | clear | ver | time | date | exit");
						cursorY += 25;
					}
					else if (cmdLen == 4 && cmdBuffer[0] == 'e' && cmdBuffer[1] == 'x' && cmdBuffer[2] == 'i' && cmdBuffer[3] == 't') {
						clearScreen();
						shutdown();
					}
					else if (cmdLen > 0) {
						setColor(0x00FF5555); // rosa
						drawString(20, cursorY, "Error: Comando no reconocido.");
						cursorY += 25;
					}

					// Reset de memoria y longitud real de caracteres usados
					for (int i = 0; i < cmdLen; i++) {
						cmdBuffer[i] = 0;
					}                    
					cmdLen = 0;
					
					if (cursorY > 700) {
						clearScreen();
						cursorY = 40;
					}
					showCursor(cursorY);
					cursorX = 85;
				}
				else if (asciiChar == 8) { // BACKSPACE
					if (cmdLen > 0 && cursorX > 85) {                        
						cmdLen--;
						cmdBuffer[cmdLen] = 0; // Limpiar último byte
						cursorX -= 10;                        
						setColor(0x00000000);
						fillRect(cursorX, cursorY, 12, 20);						
					}
				}
				else if (asciiChar >= 32 && asciiChar <= 165) { // TECLAS IMPRIMIBLES
					if (cmdLen < 15) {
						cmdBuffer[cmdLen] = asciiChar;
						cmdLen++;
						
						setColor(0x00FFFFFF);
						drawChar(cursorX, cursorY, asciiChar);
						
						cursorX += 10;
						if (cursorX > 980) {
							cursorX = 85;
							cursorY += 25;
						}
					}					
				}
				lastKey = asciiChar;
			} else if (asciiChar == 0) {
				lastKey = 0;
			}
			sleep(1);
		}
	}

    // Métodos auxiliares 
	public static void initKeyboard(){
		Native.sys(Native.SYS_SET_KBD_LAYOUT, 1, 0, 0, 0);
	}	
		
	public static int readMouseEvent(int parametro){
		return Native.sys(Native.SYS_READ_MOUSE, parametro, 0, 0, 0);
	}
	
	public static int readKeyboardKey(int parametro){
		return Native.sys(Native.SYS_READ_KEYBOARD, parametro, 0, 0, 0);		
	}
	
	public static int readTime(int parametro){
		return Native.sys(Native.SYS_GET_TIME, parametro, 0, 0, 0);
	}
	
	public static void setColor(int color){
		Native.sys(Native.SYS_SET_COLOR, color, 0, 0, 0);
	}	
	
	public static void drawString(int x, int y, String texto){
		Native.sys(Native.SYS_DRAW_STRING, x, y, texto, 0);		
	}
	
	public static void drawChar(int x, int y, int c){
		Native.sys(Native.SYS_DRAW_CHAR, x, y, c, 0);
	}
	
	public static void putc(int x, int y, char c){
		Native.sys(Native.SYS_SERIAL_PUTC,c,0,0,0);	
	}
	
	public static void drawRect(int x, int y, int w, int h){
		Native.sys(Native.SYS_DRAW_RECT, x,y,w,h);
	}
	
	public static void sleep(int delay){
		Native.sys(Native.SYS_SLEEP, delay, 0, 0, 0);
	}
	
	public static void showCursor(int y){
		setColor(0x0000FF00); // verde
		drawString(20, y, "JVMOS>");
	}
	
	public static void fillRect(int x, int y, int w, int h){
		Native.sys(Native.SYS_FILL_RECT, x, y, w, h);
	}
    
	public static void showTime(int y) {
        int hour = readTime(2);
		int min  = readTime(1);
		int sec  = readTime(0);
		
		setColor(0x0000FF00);
		drawString(20, y, " HORA: ");
		drawChar( 90, y, (hour / 10) + '0');
		drawChar( 100, y, (hour % 10) + '0');
		drawChar( 110, y, ':');
		drawChar( 120, y, (min / 10) + '0');
		drawChar( 130, y, (min % 10) + '0');
		drawChar( 140, y, ':');
		drawChar( 150, y, (sec / 10) + '0');
		drawChar( 160, y, (sec % 10) + '0');

    }

    public static void showDate(int y) {
        int day   = readTime(3);
        int month = readTime(4);
        int year  = readTime(5);
        setColor(0x0000FF00);
        drawString(20, y, "FECHA: ");
        drawChar( 90, y, (day / 10) + '0');
        drawChar( 100, y, (day % 10) + '0');
        drawChar( 110, y, '/');
        drawChar( 120, y, (month / 10) + '0');
        drawChar( 130, y, (month % 10) + '0');
        drawString(140, y, "/20");
        drawChar( 170, y, (year / 10) + '0');
        drawChar( 180, y, (year % 10) + '0');
    }

    public static void runStartX(int cursorY) {
        boolean ventanaVisible = true;
        boolean menuAbierto = false;
        boolean menuClicAbierto = false;
        int fondoActual = 0; // 0 gradiente, 1 mandelbrot
        int cMenuX = 0;
        int cMenuY = 0;

        // Renderizado inicial completo
        redrawDesktop(fondoActual, ventanaVisible);

        int oldMx = 512;
        int oldMy = 384;
        int lastBtn = 0;

        drawMouse(oldMx, oldMy);

        // Bucle interactivo para Mouse, Teclado y Reloj
        while (true) {
            // Reloj en vivo (extrema derecha)
            int day   = readTime(3);
            int month = readTime(4);
            int year  = readTime(5);
            int hour  = readTime(2);
            int min   = readTime(1);
            int sec   = readTime(0);

            setColor(0x00222222);
            fillRect(770, 733, 245, 30);
            setColor(0x0000FF00);

            drawChar( 780, 742, (day / 10) + '0');
            drawChar( 790, 742, (day % 10) + '0');
            drawChar( 800, 742, '/');
            drawChar( 810, 742, (month / 10) + '0');
            drawChar( 820, 742, (month % 10) + '0');
            drawString(830, 742, "/20");
            drawChar( 860, 742, (year / 10) + '0');
            drawChar( 870, 742, (year % 10) + '0');

            drawChar( 890, 742, (hour / 10) + '0');
            drawChar( 900, 742, (hour % 10) + '0');
            drawChar( 910, 742, ':');
            drawChar( 920, 742, (min / 10) + '0');
            drawChar( 930, 742, (min % 10) + '0');
            drawChar( 940, 742, ':');
            drawChar( 950, 742, (sec / 10) + '0');
            drawChar( 960, 742, (sec % 10) + '0');

            // Eventos de Mouse PS/2
            int mx = readMouseEvent(0);
            int my = readMouseEvent(1);
            int btn = readMouseEvent(2);

            if (mx < 0) mx = 0;
            if (mx > 1010) mx = 1010;
            if (my < 0) my = 0;
            if (my > 750) my = 750;

            if (mx != oldMx || my != oldMy) {
                // Borrar rastro viejo restaurando el fondo bajo el puntero (14x18 px)
                restoreBg(oldMx, oldMy, 14, 18, ventanaVisible, menuAbierto, menuClicAbierto, cMenuX, cMenuY, fondoActual);
                drawMouse(mx, my);
                oldMx = mx;
                oldMy = my;
            }

            // Procesando los eventos de clic
            // Clic Izquierdo (Seleccionar, Cerrar, Abrir Menú Inicio)
            if (btn == 1 && lastBtn != 1) {
                
                // Si el menú contextual está abierto, evaluar si se hizo clic en "Cambiar fondo"
                if (menuClicAbierto) {
                    menuClicAbierto = false;
                    if (mx >= cMenuX && mx <= cMenuX + 140 && my >= cMenuY && my <= cMenuY + 30) {
                        fondoActual = (fondoActual == 0) ? 1 : 0;
                        redrawDesktop(fondoActual, ventanaVisible);
                        drawMouse(mx, my); // Repintar el cursor tras regenerar la pantalla completa
                    } else {
                        // Clic fuera del menú: solo borramos el menú
                        restoreBg(cMenuX, cMenuY, 140, 30, ventanaVisible, menuAbierto, false, 0, 0, fondoActual);
                    }
                }

                // Clic en Botón Cerrar [X] de la ventana
                if (ventanaVisible && mx >= 760 && mx <= 790 && my >= 155 && my <= 175) {
                    ventanaVisible = false;
                    redrawDesktop(fondoActual, ventanaVisible);
                    drawMouse(mx, my);
                } 
                // Clic en Botón JVMOS (Menú de Inicio)
                else if (mx >= 5 && mx <= 85 && my >= 733 && my <= 763) {
                    menuAbierto = !menuAbierto;
                    if (menuAbierto) {
                        setColor(0x00222222);
                        fillRect(5, 665, 140, 60);
                        setColor(0x0000AA00);
                        drawRect(5, 665, 140, 60);
                        setColor(0x00FF5555);
                        drawString(15, 685, "> Apagar");
                    } else {
                        restoreBg(5, 665, 140, 60, ventanaVisible, false, menuClicAbierto, cMenuX, cMenuY, fondoActual);
                    }
                } 
                // Clic en Apagar dentro del Menú Inicio
                else if (menuAbierto && mx >= 5 && mx <= 145 && my >= 665 && my <= 725) {
                    clearScreen();
                    shutdown();
                }

                lastBtn = 1;
            
            // Clic Derecho (Abrir Menú Contextual)
            } else if (btn == 2 && lastBtn != 2) {
                // Si ya había un menú abierto en otra posición, lo borramos primero
                if (menuClicAbierto) {
                    restoreBg(cMenuX, cMenuY, 140, 30, ventanaVisible, menuAbierto, false, 0, 0, fondoActual);
                }
                
                menuClicAbierto = true;
                cMenuX = mx;
                cMenuY = my;
                
                // Dibujar la caja del menú
                setColor(0x00222222);
                fillRect(cMenuX, cMenuY, 140, 30);
                setColor(0x0000AA00);
                drawRect(cMenuX, cMenuY, 140, 30);
                setColor(0x00FFFFFF);
                drawString(cMenuX + 10, cMenuY + 10, "Cambiar fondo");
                
                lastBtn = 2;
                
            // Soltar clics
            } else if (btn == 0) {
                lastBtn = 0;
            }

            // ESC para regresar al Shell
            int gKey = readKeyboardKey(0);
            if (gKey == 27) {
                break;
            }

            sleep(1);
        }

        // Limpiar y restaurar la terminal
        clearScreen();
        cursorY = 40;
    }

    public static void redrawDesktop(int fondoActual, boolean ventanaVisible) {
        cambiarFondo(fondoActual);
        
        setColor(0x00333333);
        fillRect(0, 728, 1024, 40);
        setColor(0x0000AA00);
        fillRect(5, 733, 80, 30);
        setColor(0x00FFFFFF);
        drawString(15, 742, "JVMOS");
        
        if (ventanaVisible) {
            setColor(0x001F4E5B);
            fillRect(200, 150, 600, 30);
            setColor(0x00FFFFFF);
            drawString(210, 160, "ENTORNO GRAFICO INTERACTIVO - JVMOS");
            setColor(0x00AA0000);
            fillRect(765, 155, 25, 20);
            setColor(0x00FFFFFF);
            drawString(773, 160, "X");
            setColor(0x00CCCCCC);
            fillRect(200, 180, 600, 350);
            setColor(0x00000000);
            drawString(230, 220, "Sistema Operativo Baremetal Java Operativo!");
        }
    }

    public static void drawMouse(int x, int y) {
        // Sombra y Borde (Negro)
        setColor(0x00000000);
        for (int i = 0; i < 12; i++) fillRect(x, y + i, i + 2, 1);
        fillRect(x + 2, y + 12, 4, 5);
        // Centro del puntero (Blanco)
        setColor(0x00FFFFFF);
        for (int i = 1; i < 10; i++) fillRect(x + 1, y + i, i, 1);
        fillRect(x + 3, y + 10, 2, 6);
    }

    public static void cambiarFondo(int fondoActual) {
		// 0 = Color sólido, 1 = Gradiente, 2 = Fractal Mandelbrot 
		if(fondoActual == 0){
			setColor(0x000000FF);
			fillRect(0, 0, 1024, 768);
			
		} else if(fondoActual == 1){
			// Gradiente
            for (int y = 0; y < 728; y += 16) {
                int b = 60 + (y / 3);
                if (b > 255) b = 255;
                int col = ((y / 8) << 16) | ((y / 4) << 8) | b;
                setColor(col);
                fillRect(0, y, 1024, 16);
            }
		}
        if (fondoActual == 2) {
            // Conjunto Mandelbrot usando punto fijo
            for (int py = 0; py < 728; py += 4) {
                for (int px = 0; px < 1024; px += 4) {
                    int x0 = ((px - 600) * 4096) / 300;
                    int y0 = ((py - 364) * 4096) / 300;
                    int cx = 0, cy = 0, iter = 0;
                    while (iter < 24) {
                        int nx2 = (cx * cx) >> 12;
                        int ny2 = (cy * cy) >> 12;
                        if (nx2 + ny2 > 16384) break; // 4.0 << 12
                        int xtemp = nx2 - ny2 + x0;
                        cy = ((2 * cx * cy) >> 12) + y0;
                        cx = xtemp;
                        iter++;
                    }
                    int color = (iter == 24) ? 0x00000000 : (0x000000FF | (iter * 10 << 8) | (iter * 5));
                    setColor(color);
                    fillRect(px, py, 4, 4);
                }
            }
        }
    }
	
    public static void restoreBg(int x, int y, int w, int h, boolean winVis, boolean menuOpen, boolean ctxOpen, int cx, int cy, int fondoActual) {
        // Restaurar Fondo Dinámico
        for (int iy = y; iy < y + h; iy++) {
            if (iy >= 728) {
                setColor(0x00333333);
                fillRect(x, iy, w, 1);
            } else {
                if (fondoActual == 0) {
                    int b = 60 + (iy / 3);
                    if (b > 255) b = 255;
                    int col = ((iy / 8) << 16) | ((iy / 4) << 8) | b;
                    setColor(col);
                    fillRect(x, iy, w, 1);
                } else {
                    // Mandelbrot
                    for (int ix = x; ix < x + w; ix++) {
                        int bx = (ix / 4) * 4;
                        int by = (iy / 4) * 4;
                        int x0 = ((bx - 600) * 4096) / 300;
                        int y0 = ((by - 364) * 4096) / 300;
                        int cx2 = 0, cy2 = 0, iter = 0;
                        while (iter < 24) {
                            int nx2 = (cx2 * cx2) >> 12;
                            int ny2 = (cy2 * cy2) >> 12;
                            if (nx2 + ny2 > 16384) break;
                            int xtemp = nx2 - ny2 + x0;
                            cy2 = ((2 * cx2 * cy2) >> 12) + y0;
                            cx2 = xtemp;
                            iter++;
                        }
                        // Fórmula para azul
                        int color = (iter == 24) ? 0x00000000 : (0x000000FF | (iter * 10 << 8) | (iter * 5));
                        setColor(color);
                        fillRect(ix, iy, 1, 1);
                    }
                }
            }
        }

        // Restaurar UI
        if (y + h >= 733 && x <= 85) {
            setColor(0x0000AA00);
            fillRect(5, 733, 80, 30);
            setColor(0x00FFFFFF);
            drawString(15, 742, "JVMOS");
        }
        
        if (winVis) {
            if (x + w > 200 && x < 800 && y + h > 150 && y < 530) {
                int dx = x < 200 ? 200 : x;
                int dw = (x + w > 800 ? 800 : x + w) - dx;
                for (int iy = y; iy < y + h; iy++) {
                    if (iy >= 150 && iy < 180) {
                        setColor(0x001F4E5B);
                        fillRect(dx, iy, dw, 1);
                        if (dx + dw > 765 && dx < 790 && iy >= 155 && iy < 175) {
                            int kx = dx < 765 ? 765 : dx;
                            int kw = (dx + dw > 790 ? 790 : dx + dw) - kx;
                            setColor(0x00AA0000);
                            fillRect(kx, iy, kw, 1);
                        }
                    } else if (iy >= 180 && iy < 530) {
                        setColor(0x00CCCCCC);
                        fillRect(dx, iy, dw, 1);
                    }
                }
                setColor(0x00FFFFFF);
                drawString(210, 160, "ENTORNO GRAFICO INTERACTIVO - JVMOS");
                drawString(773, 160, "X");
                setColor(0x00000000);
                drawString(230, 220, "Sistema Operativo Baremetal Java Operativo!");
            }
        }
        
        if (menuOpen) {
            if (x + w > 5 && x < 145 && y + h > 665 && y < 725) {
                setColor(0x00222222);
                fillRect(5, 665, 140, 60);
                setColor(0x0000AA00);
                drawRect(5, 665, 140, 60);
                setColor(0x00FF5555);
                drawString(15, 685, "> Apagar");
            }
        }

        if (ctxOpen) {
            if (x + w > cx && x < cx + 140 && y + h > cy && y < cy + 30) {
                setColor(0x00222222);
                fillRect(cx, cy, 140, 30);
                setColor(0x0000AA00);
                drawRect(cx, cy, 140, 30);
                setColor(0x00FFFFFF);
                drawString(cx + 10, cy + 10, "Cambiar fondo");
            }
        }
    }
	
	// Helper para el POST
    public static void printfk(int y, String msg) {        
        setColor(0x0000FF00);
        drawString(20, y, "[ OK ]");
        setColor(0x00FFFFFF);
        drawString(90, y, msg);
    }
	
	// Función para apagado
	public static void shutdown(){
		setColor(0x00FF5555);
		drawString(380, 360, "SISTEMA APAGADO. CERRANDO EN 2s...");
		sleep(2000);
		Native.sys(Native.SYS_EXIT, 0, 0, 0, 0);
	}
	
	// Función limpiar pantalla
	public static void clearScreen(){
		setColor(0x00000000);
        fillRect(0, 0, 1024, 768);
	}
	
	// Helper de depuración para ver el contenido exacto del buffer
	public static void debugBuffer(int[] buf, int len, int y) {
		//clearScreen();
		setColor(0x00FFFF00); // Amarillo
		drawString(20, y, "[DEBUG] Len: ");
		//y+=20;
		drawChar(150, y, (len / 10) + '0');
		drawChar(160, y, (len % 10) + '0');
		
		drawString(190, y, "| [");
		int posX = 230;
		
		for (int i = 0; i < len; i++) {
			int val = buf[i];
			// Imprimir el valor numérico ASCII
			drawChar(posX, y, (val / 100) + '0');
			drawChar(posX + 10, y, ((val / 10) % 10) + '0' );
			drawChar(posX + 20, y, (val % 10) + '0' );
			
			// Imprimir el carácter entre paréntesis
			drawChar(posX + 30, y, '(');
			drawChar(posX + 40, y, val);
			drawChar(posX + 50, y, ')');
			drawChar(posX + 60, y, ' ');
			
			posX += 70;
		}
		drawString(posX, y, "]");
	}
	
	// Simulación de un BIOS/POST
    public static void dramaticBIOS() {
        clearScreen();

        sleep(250);
        setColor(0x0000FF00);
        drawString(20, 25, "JVMOS BIOS [v2.5]");
        drawString(20, 45, "=============================================");
				
        printfk(75, "Verificando CPU x86 [Protected Mode 32-Bit]...");
        printfk(95, "Memoria RAM Detectada: [128MB]");
        printfk(115, "Cargando Driver PS/2 Keyboard [LATAM ISO Map]");
        printfk(135, "Cargando Driver Mouse i8042 [240 DPI]");
        printfk(155, "Montando Sistema de Archivos JVMFS [Virtual Ramdisk]");
        printfk(175, "Modo de Video VBE VESA [1024x768 @ 32bpp]");

        setColor(0x0000FF00); // verde
        drawString(20, 205, "SISTEMA LISTO. Iniciando Shell interactivo...");
        sleep(1000);

        clearScreen();
    }
		
	// Mostrar texto del shell	
	public static void shell(){
		setColor(0x0000FF00);
		drawString(20, 30, "JVMOS TERMINAL INTERACTIVA - Escriba 'help' o 'startx'");
		drawString(20, 50, "-----------------------------------------------------");
	}	
	
}