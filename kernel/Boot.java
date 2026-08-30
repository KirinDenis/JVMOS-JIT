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

public class Boot {
    
    // ESTRUCTURA DEL SISTEMA DE ARCHIVOS (VFS)    
    public static class Node {
        public String name;
        public boolean isDir;
        public Node[] children;
        public int childCount;
        public Node parent;
    }

    private static Node root;
    private static Node currentDir;
    private static Node selectedNode; // NUEVO: Para saber qué está marcado

    // ESTADO GLOBAL DEL ESCRITORIO (Sin inicializar aquí por falta de <clinit>)    
    private static int winX, winY, winW, winH;
    private static boolean windowOpen;
    private static boolean windowMinimized;
    private static boolean isDragging;
    private static int dragOffsetX, dragOffsetY;

    private static boolean showStartMenu;
    private static boolean showContextMenu;
    private static boolean showAbout;
    private static int contextX, contextY;

    private static int backgroundMode;

    public static void main(String[] args) {
        initKeyboard();
        dramaticBIOS();
        shell();

        int cursorX = 85, cursorY = 80, lastKey = 0, cmdLen = 0;
        int[] cmdBuffer = new int[16];

        showCursor(cursorY);

        while (true) {
            int asciiChar = readKeyboardKey(0);

            if (asciiChar != 0 && asciiChar != lastKey) {
                if (asciiChar == 13) {
                    cursorY += 25;

                    if (cmdLen == 6 && cmdBuffer[0] == 's' && cmdBuffer[1] == 't' && cmdBuffer[2] == 'a' && cmdBuffer[3] == 'r' && cmdBuffer[4] == 't' && cmdBuffer[5] == 'x') {
                        runStartX();
                        cursorY = 40;
                    } else if (cmdLen == 3 && cmdBuffer[0] == 'v' && cmdBuffer[1] == 'e' && cmdBuffer[2] == 'r') {
                        setColor(0x0000FF00);
                        drawString(20, cursorY, "JVMOS Kernel v2.5 (Baremetal Java x86)");
                        cursorY += 25;
                    } else if (cmdLen == 4 && cmdBuffer[0] == 'c' && cmdBuffer[1] == 'l' && cmdBuffer[2] == 's') {
                        clearScreen();
                        cursorY = 40;
                    } else if (cmdLen > 0) {
                        setColor(0x00FF5555);
                        drawString(20, cursorY, "Error: Comando no reconocido.");
                        cursorY += 25;
                    }

                    for (int i = 0; i < cmdLen; i++) cmdBuffer[i] = 0;
                    cmdLen = 0;

                    if (cursorY > 700) { clearScreen(); cursorY = 40; }
                    showCursor(cursorY);
                    cursorX = 85;
                } else if (asciiChar == 8) {
                    if (cmdLen > 0 && cursorX > 85) {
                        cmdLen--; cmdBuffer[cmdLen] = 0; cursorX -= 10;
                        setColor(0x00000000); fillRect(cursorX, cursorY, 12, 20);
                    }
                } else if (asciiChar >= 32 && asciiChar <= 165) {
                    if (cmdLen < 15) {
                        cmdBuffer[cmdLen] = asciiChar; cmdLen++;
                        setColor(0x00FFFFFF); drawChar(cursorX, cursorY, asciiChar);
                        cursorX += 10;
                    }
                }
                lastKey = asciiChar;
            } else if (asciiChar == 0) {
                lastKey = 0;
            }
            sleep(1);
        }
    }

    // MOTOR GRÁFICO (ESCRITORIO, EXPLORADOR Y GESTOR DE EVENTOS)    
    public static void runStartX() {
        // Inicialización Explicita (Vital en Baremetal)
        winX = 150; winY = 60; winW = 720; winH = 460;
        windowOpen = true; windowMinimized = false;
        isDragging = false; dragOffsetX = 0; dragOffsetY = 0;
        showStartMenu = false; showContextMenu = false; showAbout = false;
        contextX = 0; contextY = 0; backgroundMode = 0;
        selectedNode = null; // Inicializar selección

        initFS();
        redrawScreen();

        int oldMx = 512, oldMy = 384, lastBtn = 0;
        drawMouse(oldMx, oldMy);

        while (true) {
            // Actualizar reloj dinámico y fecha en la Barra de Tareas
            int hour = readTime(2), min = readTime(1), sec = readTime(0);
            int day = readTime(3), month = readTime(4), year = readTime(5);
            setColor(0x00C0C0C0); fillRect(880, 728, 144, 38);
            setColor(0x00000000);
            
            // Hora Superior
            drawChar(910, 733, (hour / 10) + '0'); drawChar(920, 733, (hour % 10) + '0'); drawChar(930, 733, ':');
            drawChar(940, 733, (min / 10) + '0');  drawChar(950, 733, (min % 10) + '0');  drawChar(960, 733, ':');
            drawChar(970, 733, (sec / 10) + '0');  drawChar(980, 733, (sec % 10) + '0');
            // Fecha Inferior
            drawChar(890, 750, (day / 10) + '0'); drawChar(900, 750, (day % 10) + '0'); drawChar(910, 750, '/');
            drawChar(920, 750, (month / 10) + '0'); drawChar(930, 750, (month % 10) + '0'); drawString(940, 750, "/20");
            drawChar(970, 750, (year / 10) + '0'); drawChar(980, 750, (year % 10) + '0');

            int mx = readMouseEvent(0), my = readMouseEvent(1), btn = readMouseEvent(2);

            if (mx < 0) mx = 0; if (mx > 1010) mx = 1010;
            if (my < 0) my = 0; if (my > 750) my = 750;

            if (mx != oldMx || my != oldMy) {
                if (isDragging && windowOpen && !windowMinimized && !showAbout) {
                    winX = mx - dragOffsetX; winY = my - dragOffsetY;
                    if (winX < 0) winX = 0; if (winY < 0) winY = 0;
                    if (winX + winW > 1024) winX = 1024 - winW;
                    if (winY + winH > 726) winY = 726 - winH;
                    redrawScreen();
                } else {
                    clearMouse(oldMx, oldMy);
                }
                drawMouse(mx, my);
                oldMx = mx; oldMy = my;
            }

            if (btn == 2 && lastBtn != 2) { // Clic Derecho
                showContextMenu = true; showStartMenu = false;
                contextX = mx; contextY = my;
                if (contextX > 820) contextX = 820;
                if (contextY > 600) contextY = 600;
                redrawScreen(); drawMouse(mx, my);
                lastBtn = btn;
            } else if (btn == 1 && lastBtn != 1) { // Clic Izquierdo
                if (showContextMenu) {
                    if (mx >= contextX && mx <= contextX + 190) {
                        // Corrección de hitboxes del menú (Saltos de 20px alineados con drawString)
                        if (my >= contextY + 15 && my <= contextY + 35) backgroundMode = 0;
                        else if (my >= contextY + 35 && my <= contextY + 55) backgroundMode = 1;
                        else if (my >= contextY + 55 && my <= contextY + 75) backgroundMode = 2;
                        else if (my >= contextY + 75 && my <= contextY + 95) { windowOpen = true; windowMinimized = false; }
                        else if (my >= contextY + 95 && my <= contextY + 115) showAbout = true;
                    }
                    showContextMenu = false; redrawScreen(); drawMouse(mx, my);
                } else if (showStartMenu) {
                    int menuY = 726 - 105;
                    if (mx >= 5 && mx <= 185 && my >= menuY && my <= 726) {
                        // Corrección hitboxes Menú Inicio (Saltos de 30px)
                        if (my >= menuY + 20 && my < menuY + 50) { windowOpen = true; windowMinimized = false; }
                        else if (my >= menuY + 50 && my < menuY + 80) { windowOpen = false; showAbout = false; }
                        else if (my >= menuY + 80 && my <= 726) { clearScreen(); shutdown(); }
                    }
                    showStartMenu = false; redrawScreen(); drawMouse(mx, my);
                } else if (showAbout) {
                    int ax = 262, ay = 250, aw = 500;
                    int btnX = ax + aw - 23, btnY = ay + 5;
                    if (mx >= btnX && mx <= btnX + 18 && my >= btnY && my <= btnY + 18) {
                        showAbout = false; redrawScreen(); drawMouse(mx, my);
                    }
                } else if (my >= 726) { // Taskbar
                    if (mx >= 5 && mx <= 85) { showStartMenu = !showStartMenu; redrawScreen(); drawMouse(mx, my); }
                    else if (windowOpen && mx >= 95 && mx <= 235) { windowMinimized = !windowMinimized; redrawScreen(); drawMouse(mx, my); }
                } else if (windowOpen && !windowMinimized) {
                    int btnX = winX + winW - 23, btnY = winY + 5;
                    if (mx >= btnX && mx <= btnX + 18 && my >= btnY && my <= btnY + 18) {
                        windowOpen = false; redrawScreen(); drawMouse(mx, my);
                    } else if (mx >= winX && mx <= winX + winW - 30 && my >= winY && my <= winY + 27) {
                        isDragging = true; dragOffsetX = mx - winX; dragOffsetY = my - winY;
                    } else if (mx >= winX + 10 && mx <= winX + 190 && my >= winY + 35 && my <= winY + winH - 45) {
                        // HITBOX ÁRBOL IZQUIERDO CORREGIDO
                        int nodeY = winY + 55;
                        if (my >= nodeY - 5 && my <= nodeY + 15) { currentDir = root; selectedNode = null; redrawScreen(); drawMouse(mx, my); }
                        nodeY += 25;
                        for (int i = 0; i < root.childCount; i++) {
                            Node child = root.children[i];
                            if (child.isDir) {
                                if (my >= nodeY - 5 && my <= nodeY + 15) { currentDir = child; selectedNode = null; redrawScreen(); drawMouse(mx, my); }
                                nodeY += 20;
                            }
                        }
                    } else if (mx >= winX + 195 && mx <= winX + winW - 10 && my >= winY + 35 && my <= winY + winH - 45) {
                        // HITBOX VISTA PREVIA CORREGIDO
                        int iconX = winX + 215, iconY = winY + 55;
                        boolean clickedSomething = false;
                        
                        // Opción de Retroceso ".."
                        if (currentDir.parent != null) {
                            if (mx >= iconX && mx <= iconX + 60 && my >= iconY && my <= iconY + 50) { 
                                currentDir = currentDir.parent; selectedNode = null; redrawScreen(); drawMouse(mx, my); clickedSomething = true;
                            }
                            iconX += 90;
                        }
                        
                        // Iconos de la carpeta
                        if (!clickedSomething) {
                            for (int i = 0; i < currentDir.childCount; i++) {
                                Node child = currentDir.children[i];
                                if (child != null) {
                                    if (mx >= iconX - 5 && mx <= iconX + 65 && my >= iconY - 5 && my <= iconY + 55) { 
                                        if (child == selectedNode && child.isDir) {
                                            // Doble Clic = Abrir
                                            currentDir = child; selectedNode = null;
                                        } else {
                                            // Un Clic = Seleccionar
                                            selectedNode = child;
                                        }
                                        redrawScreen(); drawMouse(mx, my);
                                        clickedSomething = true;
                                        break;
                                    }
                                    iconX += 90;
                                    if (iconX > winX + winW - 80) { iconX = winX + 215; iconY += 60; }
                                }
                            }
                        }
                        
                        // Si clicó en el blanco, desmarcar selección
                        if (!clickedSomething && selectedNode != null) {
                            selectedNode = null; redrawScreen(); drawMouse(mx, my);
                        }
                    }
                }
                lastBtn = 1;
            } else if (btn == 0) {
                isDragging = false; lastBtn = 0;
            }

            if (readKeyboardKey(0) == 27) break;
            sleep(1);
        }
        clearScreen();
    }

    // MÉTODOS DE DIBUJADO DE UI    
    public static void redrawScreen() {
        drawBackground();
        drawWindow();
        drawTaskbar();
        drawStartMenu();
        drawContextMenu();
        drawAboutWindow();
    }

    public static void drawBackground() {
        if (backgroundMode == 0) {
            setColor(0x00000055); fillRect(0, 0, 1024, 726);
        } else if (backgroundMode == 1) {
            for (int y = 0; y < 726; y += 8) {
                int red = (y * 255) / 726;
                setColor(((red / 2) << 16) | ((255 - red) / 2));
                fillRect(0, y, 1024, 8);
            }
        } else if (backgroundMode == 2) {
            for (int px = 0; px < 1024; px += 4) {
                for (int py = 0; py < 726; py += 4) {
                    int x0 = ((px - 600) * 4096) / 300; 
                    int y0 = ((py - 364) * 4096) / 300;
                    int cx = 0, cy = 0, iter = 0;
                    while (iter < 24) {
                        int nx2 = (cx * cx) >> 12;
                        int ny2 = (cy * cy) >> 12;
                        if (nx2 + ny2 > 16384) break;
                        int xtemp = nx2 - ny2 + x0;
                        cy = ((2 * cx * cy) >> 12) + y0;
                        cx = xtemp;
                        iter++;
                    }
                    if (iter < 24) setColor(0x000000FF | (iter * 10 << 8) | (iter * 5));
                    else setColor(0x00000000);
                    fillRect(px, py, 4, 4);
                }
            }
        }
    }

    public static void drawWindow() {
        if (!windowOpen || windowMinimized) return;
        
        setColor(0x00C0C0C0); fillRect(winX, winY, winW, winH); // Fondo base ventana
        setColor(0x00000080); fillRect(winX + 3, winY + 3, winW - 6, 24); // Barra Título
        setColor(0x00FFFFFF); drawString(winX + 10, winY + 10, "JExplorer - "); drawString(winX + 130, winY + 10, currentDir.name);

        int btnX = winX + winW - 23, btnY = winY + 5;
        setColor(0x00FF0000); fillRect(btnX, btnY, 18, 18);
        setColor(0x00FFFFFF); drawString(btnX + 5, btnY + 5, "X");

        int treeX = winX + 10, treeY = winY + 35, treeW = 180, treeH = winH - 45;
        int viewX = winX + 195, viewY = winY + 35, viewW = winW - 205, viewH = winH - 45;

        // Limpieza de paneles a su color base exacto
        setColor(0x00E0E0E0); fillRect(treeX, treeY, treeW, treeH); // Gris claro árbol
        setColor(0x00FFFFFF); fillRect(viewX, viewY, viewW, viewH); // Blanco contenido

        // DIBUJADO DE ÁRBOL IZQUIERDO CORREGIDO
        int nodeY = treeY + 20;
        
        // Root Selección
        if (currentDir == root) { setColor(0x00000080); fillRect(treeX + 5, nodeY - 3, 170, 18); setColor(0x00FFFFFF); }
        else { setColor(0x00000000); }
        drawString(treeX + 10, nodeY, "[-] / (Root)"); 
        nodeY += 25;

        for (int i = 0; i < root.childCount; i++) {
            Node child = root.children[i];
            if (child.isDir) {
                if (child == currentDir) {
                    setColor(0x00000080); fillRect(treeX + 15, nodeY - 3, 160, 18); setColor(0x00FFFFFF);
                } else { 
                    setColor(0x00000000); 
                }
                drawString(treeX + 25, nodeY, "+-- "); drawString(treeX + 55, nodeY, child.name);
                nodeY += 20;
            }
        }

        // DIBUJADO VISTA PREVIA CORREGIDA
        int iconX = viewX + 20, iconY = viewY + 20;
        if (currentDir.parent != null) {
            setColor(0x00808080); fillRect(iconX, iconY, 32, 22);
            setColor(0x00000000); drawString(iconX - 5, iconY + 38, ".. (Atras)"); // Texto centrado con icono
            iconX += 90;
        }

        for (int i = 0; i < currentDir.childCount; i++) {
            Node child = currentDir.children[i];
            if (child != null) {
                // Marco de selección alineado
                if (child == selectedNode) { setColor(0x00D0D0FF); fillRect(iconX - 5, iconY - 5, 80, 60); }

                // Dibujar Icono
                if (child.isDir) {
                    setColor(0x00F0C000); fillRect(iconX, iconY, 32, 22); fillRect(iconX, iconY - 4, 12, 4);
                } else {
                    setColor(0x00A0A0A0); fillRect(iconX, iconY, 20, 26);
                }
                
                // Texto de archivo
                setColor(0x00000000); drawString(iconX - 5, iconY + 38, child.name); // Centrado con icono

                iconX += 90;
                if (iconX > viewX + viewW - 80) { iconX = viewX + 20; iconY += 60; }
            }
        }
    }

    public static void drawTaskbar() {
        int taskbarY = 726;
        setColor(0x00C0C0C0); fillRect(0, taskbarY, 1024, 42);
        setColor(0x00FFFFFF); fillRect(0, taskbarY, 1024, 2);

        setColor(showStartMenu ? 0x00808080 : 0x00008000);
        fillRect(5, taskbarY + 4, 80, 32);
        setColor(0x00FFFFFF); drawString(22, taskbarY + 14, "INICIO");

        if (windowOpen) {
            setColor(windowMinimized ? 0x00A0A0A0 : 0x00E0E0E0);
            fillRect(95, taskbarY + 4, 140, 32);
            setColor(0x00000000); drawString(110, taskbarY + 14, "JExplorer");
        }
    }

    public static void drawStartMenu() {
        if (!showStartMenu) return;
        int menuH = 105, menuY = 726 - menuH;
        setColor(0x00C0C0C0); fillRect(5, menuY, 180, menuH);
        setColor(0x00000080); fillRect(5, menuY, 25, menuH); // Barra azul izquierda
        setColor(0x00000000);
        
        // Textos alineados con los nuevos hitboxes
        drawString(35, menuY + 25, "Abrir JExplorer");
        drawString(35, menuY + 55, "Cerrar Ventanas");
        drawString(35, menuY + 85, "Apagar Equipo");
    }

    public static void drawContextMenu() {
        if (!showContextMenu) return;
        setColor(0x00F0F0F0); fillRect(contextX, contextY, 190, 125);
        setColor(0x00000000); drawRect(contextX, contextY, 190, 125);
        
        // Textos alineados para clic derecho
        drawString(contextX + 15, contextY + 20, "Fondo Solido");
        drawString(contextX + 15, contextY + 40, "Fondo Gradiente");
        drawString(contextX + 15, contextY + 60, "Fondo Fractal");
        drawString(contextX + 15, contextY + 80, "Abrir Explorador");
        drawString(contextX + 15, contextY + 100, "Acerca de JVMOS");
    }

    public static void drawAboutWindow() {
        if (!showAbout) return;
        int ax = 262, ay = 250, aw = 500, ah = 220;
        
        setColor(0x00E0E0E0); fillRect(ax, ay, aw, ah);
        setColor(0x001F4E5B); fillRect(ax + 3, ay + 3, aw - 6, 24);
        setColor(0x00FFFFFF); drawString(ax + 10, ay + 10, "Acerca de JVMOS");
        
        int btnX = ax + aw - 23, btnY = ay + 5;
        setColor(0x00FF0000); fillRect(btnX, btnY, 18, 18);
        setColor(0x00FFFFFF); drawString(btnX + 5, btnY + 5, "X");

        setColor(0x00000000);
        drawString(ax + 170, ay + 60, "JVMOS - Version 1.0");
        drawString(ax + 20, ay + 80, "Sistema operativo escrito en ASM y Java");
        drawString(ax + 20, ay + 110, "Hecho por: Allan Ayes Ramirez (30/08/2026)");
        drawString(ax + 20, ay + 130, "GitHub: aayes89");
        drawString(ax + 20, ay + 160, "Memoria RAM: 128 MB (Estatica BIOS)");
        drawString(ax + 20, ay + 180, "Video: VBE VESA 1024x768 @ 32bpp");
    }
    
    // RESTAURACIÓN LIGERA DEL RATÓN (Algoritmo del Pintor Rápido)
    public static void clearMouse(int x, int y) {
        if (backgroundMode == 0) {
            setColor(0x00000055); fillRect(x, y, 14, 18);
        } else if (backgroundMode == 1) {
            for (int iy = y; iy < y + 18; iy += 2) {
                if (iy >= 726) break;
                int red = (iy * 255) / 726;
                setColor(((red / 2) << 16) | ((255 - red) / 2));
                fillRect(x, iy, 14, 2);
            }
        } else if (backgroundMode == 2) {
            for (int px = (x / 4) * 4; px < x + 14; px += 4) {
                for (int py = (y / 4) * 4; py < y + 18; py += 4) {
                    if (py >= 726) continue;
                    int x0 = ((px - 600) * 4096) / 300; 
                    int y0 = ((py - 364) * 4096) / 300;
                    int cx = 0, cy = 0, iter = 0;
                    while (iter < 24) {
                        int nx2 = (cx * cx) >> 12;
                        int ny2 = (cy * cy) >> 12;
                        if (nx2 + ny2 > 16384) break;
                        int xtemp = nx2 - ny2 + x0;
                        cy = ((2 * cx * cy) >> 12) + y0;
                        cx = xtemp; iter++;
                    }
                    if (iter < 24) setColor(0x000000FF | (iter * 10 << 8) | (iter * 5));
                    else setColor(0x00000000);
                    fillRect(px, py, 4, 4);
                }
            }
        }

        // Re-dibujado condicional por capa (z-index simulado)
        if (windowOpen && !windowMinimized && x < winX + winW && x + 14 > winX && y < winY + winH && y + 18 > winY) drawWindow();
        if (showAbout && x < 262 + 500 && x + 14 > 262 && y < 250 + 220 && y + 18 > 250) drawAboutWindow();
        if (y + 18 >= 726) drawTaskbar();
        if (showStartMenu && x < 185 && y + 18 > 621) drawStartMenu();
        if (showContextMenu && x < contextX + 190 && x + 14 > contextX && y < contextY + 125 && y + 18 > contextY) drawContextMenu();
    }

    public static void drawMouse(int x, int y) {
        setColor(0x00000000);
        for (int i = 0; i < 12; i++) fillRect(x, y + i, i + 2, 1);
        fillRect(x + 2, y + 12, 4, 5);
        setColor(0x00FFFFFF);
        for (int i = 1; i < 10; i++) fillRect(x + 1, y + i, i, 1);
        fillRect(x + 3, y + 10, 2, 6);
    }

    // INICIALIZADOR VIRTUAL (VFS)
    public static void initFS() {
        root = new Node(); root.name = "Root"; root.isDir = true; root.children = new Node[8]; root.childCount = 0; root.parent = null;
        Node appsDir = new Node(); appsDir.name = "APPS"; appsDir.isDir = true; appsDir.children = new Node[8]; appsDir.childCount = 0; appsDir.parent = root;
        Node paintApp = new Node(); paintApp.name = "Paint.class"; paintApp.isDir = false; paintApp.parent = appsDir;
        appsDir.children[0] = paintApp; appsDir.childCount = 1;
        Node docsDir = new Node(); docsDir.name = "DOCS"; docsDir.isDir = true; docsDir.children = new Node[8]; docsDir.childCount = 0; docsDir.parent = root;
        root.children[0] = appsDir; root.children[1] = docsDir; root.childCount = 2;
        currentDir = root;
    }

    // =========================================================================
    // NATIVAS HAL BAREMETAL
    // =========================================================================
    public static void initKeyboard() { Native.sys(Native.SYS_SET_KBD_LAYOUT, 1, 0, 0, 0); }
    public static int readMouseEvent(int p) { return Native.sys(Native.SYS_READ_MOUSE, p, 0, 0, 0); }
    public static int readKeyboardKey(int p) { return Native.sys(Native.SYS_READ_KEYBOARD, p, 0, 0, 0); }
    public static int readTime(int p) { return Native.sys(Native.SYS_GET_TIME, p, 0, 0, 0); }
    public static int getSystemTicks() { return Native.sys(Native.SYS_GET_TICKS, 0, 0, 0, 0); }
    public static void setColor(int c) { Native.sys(Native.SYS_SET_COLOR, c, 0, 0, 0); }
    public static void drawString(int x, int y, String t) { Native.sys(Native.SYS_DRAW_STRING, x, y, t, 0); }
    public static void drawChar(int x, int y, int c) { Native.sys(Native.SYS_DRAW_CHAR, x, y, c, 0); }
    public static void drawRect(int x, int y, int w, int h) { Native.sys(Native.SYS_DRAW_RECT, x, y, w, h); }
    public static void fillRect(int x, int y, int w, int h) { Native.sys(Native.SYS_FILL_RECT, x, y, w, h); }
    public static void sleep(int delay) { Native.sys(Native.SYS_SLEEP, delay, 0, 0, 0); }
    public static void clearScreen() { setColor(0x00000000); fillRect(0, 0, 1024, 768); }
    public static void showCursor(int y) { setColor(0x0000FF00); drawString(20, y, "JVMOS>"); }

    public static void dramaticBIOS() {
        clearScreen(); sleep(250);
        setColor(0x0000FF00); drawString(20, 25, "JVMOS BIOS [v2.5]"); drawString(20, 45, "=============================================");
        setColor(0x0000FF00); drawString(20, 75, "[ OK ]"); setColor(0x00FFFFFF); drawString(90, 75, "Verificando CPU x86 [Protected Mode 32-Bit]...");
        setColor(0x0000FF00); drawString(20, 95, "[ OK ]"); setColor(0x00FFFFFF); drawString(90, 95, "Memoria RAM Detectada: [128MB]");
        setColor(0x0000FF00); drawString(20, 115, "[ OK ]"); setColor(0x00FFFFFF); drawString(90, 115, "Cargando Driver PS/2 Keyboard [LATAM ISO Map]");
        setColor(0x0000FF00); drawString(20, 135, "[ OK ]"); setColor(0x00FFFFFF); drawString(90, 135, "Cargando Driver Mouse i8042 [240 DPI]");
        setColor(0x0000FF00); drawString(20, 155, "[ OK ]"); setColor(0x00FFFFFF); drawString(90, 155, "Montando Sistema de Archivos JVMFS [Virtual Ramdisk]");
        setColor(0x0000FF00); drawString(20, 175, "[ OK ]"); setColor(0x00FFFFFF); drawString(90, 175, "Modo de Video VBE VESA [1024x768 @ 32bpp]");
        setColor(0x0000FF00); drawString(20, 205, "SISTEMA LISTO. Iniciando Shell interactivo...");
        sleep(1000); clearScreen();
    }

    public static void shell() {
        setColor(0x0000FF00);
        drawString(20, 30, "JVMOS TERMINAL INTERACTIVA - Escriba 'startx'");
        drawString(20, 50, "-----------------------------------------------------");
    }

    public static void shutdown() {
        setColor(0x00FF5555); drawString(380, 360, "SISTEMA APAGADO. CERRANDO EN 2s...");
        sleep(2000); Native.sys(Native.SYS_EXIT, 0, 0, 0, 0);
    }
}
