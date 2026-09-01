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

import java.awt.Graphics2D;
import java.util.Calendar;
import java.io.PrintStream;

/*
 * JVMOS desktop shell.
 *
 * Everything lives in this single class on purpose. The JIT this runs on has
 * some hard limits that shape the whole design:
 *
 *   1. Static initializers (<clinit>) are never executed, so no field may have
 *      an initializer. Only "static final int" compile-time constants are safe
 *      because javac inlines them at the use site. Everything else is set up
 *      by initState().
 *   2. Methods are resolved by NAME ONLY (the descriptor is ignored), so no
 *      method or constructor may be overloaded anywhere.
 *   3. Object field offsets are derived from the constant-pool index of the
 *      field reference, so a field is only addressed consistently from inside
 *      the class that declares it. Cross-class field access silently reads the
 *      wrong offset -> all shared UI state is kept in flat arrays right here.
 *   4. drawString() can only render string literals: the native side reads the
 *      length from the two bytes preceding the constant-pool UTF-8 payload.
 *      Anything dynamic has to be drawn character by character.
 *   5. 'new' hands out a fixed 4KB block from a bump allocator with no free(),
 *      so nothing may allocate inside the redraw loop.
 */
public class Boot {

    // ---- screen geometry -------------------------------------------------
    static final int SCR_W = 1024;
    static final int SCR_H = 768;
    static final int CH_W = 8;          // font cell width
    static final int CH_H = 16;         // font cell height

    static final int MENU_H = 20;
    static final int TASK_H = 28;
    static final int TASK_Y = SCR_H - TASK_H;
    static final int DESK_TOP = MENU_H;
    static final int DESK_BOT = TASK_Y;

    static final int TITLE_H = 20;
    static final int BORDER = 3;
    static final int GRIP = 14;
    static final int MIN_W = 260;
    static final int MIN_H = 140;

    // ---- palette (0x00RRGGBB) -------------------------------------------
    static final int C_DESK = 0x00103060;
    static final int C_DESK2 = 0x0017417A;
    static final int C_FACE = 0x00C0C0C0;
    static final int C_LIGHT = 0x00FFFFFF;
    static final int C_DARK = 0x00868686;
    static final int C_SHADOW = 0x00404040;
    static final int C_TITLE_A = 0x00003C82;
    static final int C_TITLE_B = 0x00787878;
    static final int C_TEXT = 0x00000000;
    static final int C_TEXTLT = 0x00FFFFFF;
    static final int C_FIELD = 0x00FFFFFF;
    static final int C_SEL = 0x00003C82;
    static final int C_GREEN = 0x0000A040;
    static final int C_RED = 0x00B02020;
    static final int C_AMBER = 0x00E0A000;

    // ---- window ids ------------------------------------------------------
    static final int WIN_COUNT = 4;
    static final int W_GALLERY = 0;
    static final int W_FILES = 1;
    static final int W_SYSTEM = 2;
    static final int W_ABOUT = 3;

    // ---- drag modes ------------------------------------------------------
    static final int DRAG_NONE = 0;
    static final int DRAG_MOVE = 1;
    static final int DRAG_SIZE = 2;

    // ---- keyboard ---------------------------------------------------------
    // SYS_READ_KEYBOARD returns: modifier bits | key code, where key codes
    // above 0xFF are 0x100 + PS/2 scancode (arrows, function keys, ...).
    static final int K_MASK = 0xFFFF;
    static final int M_ALT = 0x10000;
    static final int M_CTRL = 0x20000;
    static final int M_SHIFT = 0x40000;
    static final int K_BACK = 8;
    static final int K_TAB = 9;
    static final int K_ENTER = 13;
    static final int K_ESC = 27;
    static final int K_SPACE = 32;
    static final int K_F4 = 0x13E;
    static final int K_F10 = 0x144;
    static final int K_UP = 0x148;
    static final int K_LEFT = 0x14B;
    static final int K_RIGHT = 0x14D;
    static final int K_DOWN = 0x150;

    // focusable control ids inside the gallery
    static final int F_CHK1 = 0;
    static final int F_RADIO1 = 3;
    static final int F_FIELD = 6;
    static final int F_BTN1 = 7;
    static final int F_LIST = 10;
    static final int F_GALLERY_N = 11;

    // ---- runtime state (never field-initialized, see initState) ----------
    static Graphics2D g;

    static int[] wx, wy, ww, wh;        // window geometry
    static int[] wOpen, wMin, wMax;     // window flags (0/1)
    static int[] rx, ry, rw, rh;        // geometry saved before maximize
    static int[] zorder;                // back-to-front window order

    static int mouseX, mouseY, mouseBtn;
    static int prevX, prevY, prevBtn;
    static int dragMode, dragWin, dragDX, dragDY;
    static int menuOpen;                // -1 none, else menu index
    static int lastSecond;

    // widget state for the gallery demo
    static int chkSound, chkGrid, chkStatus;
    static int radioSel;
    static int progress;
    static int modSel;                  // selected row in the Modules list
    static int fileSel;                 // selected row in the File Manager
    static int fieldFocus, fieldLen;
    static int[] fieldBuf;
    static int pressedBtn;              // -1 none, else button id
    static int lastKey;
    static int[] focus;                 // focused control per window

    // ======================================================================
    // ENTRY POINT
    // ======================================================================
    public static void main(String[] args) {
        java.lang.System.out = new PrintStream();
        java.lang.System.out.println("[Boot] JVMOS desktop starting...");

        g = new Graphics2D();
        initState();

        Native.sys(Native.SYS_SET_KBD_LAYOUT, 0, 0, 0, 0);

        splash();
        chime();

        desktopLoop();
    }

    static void initState() {
        wx = new int[WIN_COUNT];
        wy = new int[WIN_COUNT];
        ww = new int[WIN_COUNT];
        wh = new int[WIN_COUNT];
        wOpen = new int[WIN_COUNT];
        wMin = new int[WIN_COUNT];
        wMax = new int[WIN_COUNT];
        rx = new int[WIN_COUNT];
        ry = new int[WIN_COUNT];
        rw = new int[WIN_COUNT];
        rh = new int[WIN_COUNT];
        zorder = new int[WIN_COUNT];
        focus = new int[WIN_COUNT];
        fieldBuf = new int[24];

        setWin(W_GALLERY, 60, 60, 520, 430);
        setWin(W_FILES, 600, 90, 380, 330);
        setWin(W_SYSTEM, 150, 430, 430, 250);
        setWin(W_ABOUT, 300, 220, 420, 220);

        wOpen[W_GALLERY] = 1;
        wOpen[W_FILES] = 1;
        wOpen[W_SYSTEM] = 1;
        wOpen[W_ABOUT] = 0;

        zorder[0] = W_SYSTEM;
        zorder[1] = W_FILES;
        zorder[2] = W_ABOUT;
        zorder[3] = W_GALLERY;

        mouseX = 512;
        mouseY = 384;
        mouseBtn = 0;
        prevX = -1;
        prevY = -1;
        prevBtn = 0;
        dragMode = DRAG_NONE;
        dragWin = -1;
        menuOpen = -1;
        lastSecond = -1;
        pressedBtn = -1;
        lastKey = 0;

        chkSound = 1;
        chkGrid = 1;
        chkStatus = 0;
        radioSel = 1;
        progress = 45;
        modSel = 0;
        fileSel = 0;
        fieldFocus = 0;
        fieldLen = 0;
    }

    static void setWin(int i, int x, int y, int w, int h) {
        wx[i] = x;
        wy[i] = y;
        ww[i] = w;
        wh[i] = h;
        rx[i] = x;
        ry[i] = y;
        rw[i] = w;
        rh[i] = h;
        wMin[i] = 0;
        wMax[i] = 0;
    }

    // ======================================================================
    // BOOT SPLASH + PC SPEAKER CHIME
    // ======================================================================
    static void splash() {
        g.setRGB(0x00000000);
        g.fillRect(0, 0, SCR_W, SCR_H);

        g.setRGB(C_GREEN);
        g.drawString("JVMOS / JIT  --  baremetal Java on x86", 40, 40);
        g.drawString("========================================", 40, 60);

        splashLine("CPU        x86 32-bit protected mode", 90);
        splashLine("Memory     128 MB flat, no paging", 112);
        splashLine("Video      VESA VBE 1024x768 32bpp", 134);
        splashLine("Input      PS/2 keyboard + mouse (IRQ 1/12)", 156);
        splashLine("Engine     bytecode -> native x86 JIT", 178);

        g.setRGB(C_AMBER);
        g.drawString("Starting desktop shell...", 40, 214);
        g.present();
        Native.sys(Native.SYS_SLEEP, 700, 0, 0, 0);
    }

    static void splashLine(String s, int y) {
        g.setRGB(C_GREEN);
        g.drawString("[ OK ]", 40, y);
        g.setRGB(C_TEXTLT);
        g.drawString(s, 40 + 8 * CH_W, y);
    }

    // One note. Frequencies are passed straight to the PC speaker syscall:
    // Toolkit.beep is overloaded and this JIT resolves methods by name only,
    // so every Toolkit.beep(n) call would land on the no-arg 1000 Hz version.
    static void note(int hz, int ms) {
        Native.sys(Native.SYS_BEEP, hz, 0, 0, 0);
        Native.sys(Native.SYS_SLEEP, ms, 0, 0, 0);
        Native.sys(Native.SYS_BEEP, 0, 0, 0, 0);
        Native.sys(Native.SYS_SLEEP, 26, 0, 0, 0);
    }

    // Straight-line, no arrays: a static final int[] would need a class
    // initializer, and those never run here.
    static void chime() {
        note(523, 130);
        note(659, 130);
        note(784, 130);
        note(1047, 240);
        note(880, 130);
        note(1047, 420);
    }

    static void clickTone() {
        Native.sys(Native.SYS_BEEP, 1800, 0, 0, 0);
        Native.sys(Native.SYS_SLEEP, 12, 0, 0, 0);
        Native.sys(Native.SYS_BEEP, 0, 0, 0, 0);
    }

    // ======================================================================
    // MAIN LOOP
    // ======================================================================
    static void desktopLoop() {
        paint();
        while (true) {
            mouseX = Native.sys(Native.SYS_READ_MOUSE, 0, 0, 0, 0);
            mouseY = Native.sys(Native.SYS_READ_MOUSE, 1, 0, 0, 0);
            mouseBtn = Native.sys(Native.SYS_READ_MOUSE, 2, 0, 0, 0);

            if (mouseX < 0) mouseX = 0;
            if (mouseX > SCR_W - 1) mouseX = SCR_W - 1;
            if (mouseY < 0) mouseY = 0;
            if (mouseY > SCR_H - 1) mouseY = SCR_H - 1;

            handleKeys();

            if (mouseBtn != 0 && prevBtn == 0) onPress();
            if (mouseBtn == 0 && prevBtn != 0) onRelease();
            if (dragMode != DRAG_NONE) onDrag();

            int sec = Calendar.get(Calendar.SECOND);
            if (mouseX != prevX || mouseY != prevY || mouseBtn != prevBtn || sec != lastSecond) {
                lastSecond = sec;
                paint();
            }

            prevX = mouseX;
            prevY = mouseY;
            prevBtn = mouseBtn;
            Native.sys(Native.SYS_SLEEP, 1, 0, 0, 0);
        }
    }

    static void handleKeys() {
        int raw = Native.sys(Native.SYS_READ_KEYBOARD, 0, 0, 0, 0);
        if (raw == 0) {
            lastKey = 0;
            return;
        }
        if (raw == lastKey) return;     // crude auto-repeat suppression
        lastKey = raw;

        int code = raw & K_MASK;
        int top = zorder[WIN_COUNT - 1];

        if ((raw & M_ALT) != 0) {
            if (code == K_F4) {
                wOpen[top] = 0;
                clickTone();
                paint();
                return;
            }
            if (code == K_TAB) {
                cycleWindow();
                paint();
                return;
            }
        }

        if (code == K_F10) {
            if (menuOpen < 0) menuOpen = 0; else menuOpen = -1;
            paint();
            return;
        }

        if (menuOpen >= 0) {
            menuKey(code);
            return;
        }

        if (code == K_ESC) {
            fieldFocus = 0;
            if (wOpen[W_ABOUT] == 1) wOpen[W_ABOUT] = 0;
            paint();
            return;
        }

        // A focused text field swallows printable keys and backspace.
        if (fieldFocus == 1) {
            if (code == K_BACK) {
                if (fieldLen > 0) fieldLen = fieldLen - 1;
                paint();
                return;
            }
            if (code >= 32 && code <= 126) {
                if (fieldLen < 20) {
                    fieldBuf[fieldLen] = code;
                    fieldLen = fieldLen + 1;
                }
                paint();
                return;
            }
        }

        if (code == K_TAB) {
            if ((raw & M_SHIFT) != 0) moveFocus(top, -1); else moveFocus(top, 1);
            paint();
            return;
        }
        if (code == K_DOWN || code == K_RIGHT) {
            stepDown(top);
            paint();
            return;
        }
        if (code == K_UP || code == K_LEFT) {
            stepUp(top);
            paint();
            return;
        }
        if (code == K_SPACE || code == K_ENTER) {
            activate(top);
            paint();
            return;
        }
    }

    static void menuKey(int code) {
        if (code == K_ESC) {
            menuOpen = -1;
        } else if (code == K_RIGHT) {
            menuOpen = menuOpen + 1;
            if (menuOpen > 2) menuOpen = 0;
        } else if (code == K_LEFT) {
            menuOpen = menuOpen - 1;
            if (menuOpen < 0) menuOpen = 2;
        }
        paint();
    }

    static int focusCount(int i) {
        if (i == W_GALLERY) return F_GALLERY_N;
        if (i == W_FILES) return 1;
        if (i == W_ABOUT) return 1;
        return 0;
    }

    static void setFocus(int i, int idx) {
        focus[i] = idx;
        if (i == W_GALLERY && idx == F_FIELD) fieldFocus = 1; else fieldFocus = 0;
    }

    static void moveFocus(int i, int dir) {
        int n = focusCount(i);
        if (n == 0) return;
        int f = focus[i] + dir;
        if (f < 0) f = n - 1;
        if (f >= n) f = 0;
        setFocus(i, f);
    }

    // Down/Right: inside a list move the selection, otherwise move the focus.
    static void stepDown(int i) {
        if (i == W_FILES) {
            if (fileSel < 5) fileSel = fileSel + 1;
            return;
        }
        if (i == W_GALLERY && focus[i] == F_LIST) {
            if (modSel < 4) modSel = modSel + 1;
            return;
        }
        moveFocus(i, 1);
    }

    static void stepUp(int i) {
        if (i == W_FILES) {
            if (fileSel > 0) fileSel = fileSel - 1;
            return;
        }
        if (i == W_GALLERY && focus[i] == F_LIST) {
            if (modSel > 0) modSel = modSel - 1;
            return;
        }
        moveFocus(i, -1);
    }

    static void activate(int i) {
        int f = focus[i];
        if (i == W_ABOUT) {
            wOpen[W_ABOUT] = 0;
            clickTone();
            return;
        }
        if (i != W_GALLERY) return;

        if (f == 0) chkSound = 1 - chkSound;
        else if (f == 1) chkGrid = 1 - chkGrid;
        else if (f == 2) chkStatus = 1 - chkStatus;
        else if (f == 3) radioSel = 0;
        else if (f == 4) radioSel = 1;
        else if (f == 5) radioSel = 2;
        else if (f == 7) {
            progress = progress - 10;
            if (progress < 0) progress = 0;
        } else if (f == 8) {
            progress = progress + 10;
            if (progress > 100) progress = 100;
        } else if (f == 9) {
            note(880, 90);
            return;
        }
        if (chkSound == 1) clickTone();
    }

    // Alt+Tab: send the front window to the back, skipping closed ones.
    static void cycleWindow() {
        int k = 0;
        while (k < WIN_COUNT) {
            int front = zorder[WIN_COUNT - 1];
            int j = WIN_COUNT - 1;
            while (j > 0) {
                zorder[j] = zorder[j - 1];
                j = j - 1;
            }
            zorder[0] = front;
            int cand = zorder[WIN_COUNT - 1];
            if (wOpen[cand] == 1 && wMin[cand] == 0) return;
            k = k + 1;
        }
    }

    static boolean isActive(int i) {
        return zorder[WIN_COUNT - 1] == i;
    }

    // Dotted focus rectangle, Turbo Vision style.
    static void focusRing(int x, int y, int w, int h) {
        g.setRGB(C_TEXT);
        int p = 0;
        while (p < w) {
            g.fillRect(x + p, y, 1, 1);
            g.fillRect(x + p, y + h - 1, 1, 1);
            p = p + 2;
        }
        p = 0;
        while (p < h) {
            g.fillRect(x, y + p, 1, 1);
            g.fillRect(x + w - 1, y + p, 1, 1);
            p = p + 2;
        }
    }

    // ======================================================================
    // INPUT ROUTING
    // ======================================================================
    static void onPress() {
        // menu bar has priority
        if (mouseY < MENU_H) {
            int m = menuIndexAt(mouseX);
            if (m == menuOpen) menuOpen = -1; else menuOpen = m;
            paint();
            return;
        }
        if (menuOpen >= 0) {
            if (menuHit()) return;
            menuOpen = -1;
        }
        if (mouseY >= TASK_Y) {
            taskbarClick();
            return;
        }

        int i = windowAt(mouseX, mouseY);
        if (i < 0) {
            paint();
            return;
        }
        raiseWin(i);

        int ly = mouseY - wy[i];
        if (ly < TITLE_H) {
            if (titleButtons(i)) return;
            dragMode = DRAG_MOVE;
            dragWin = i;
            dragDX = mouseX - wx[i];
            dragDY = mouseY - wy[i];
            paint();
            return;
        }
        if (wMax[i] == 0 && inGrip(i)) {
            dragMode = DRAG_SIZE;
            dragWin = i;
            dragDX = wx[i] + ww[i] - mouseX;
            dragDY = wy[i] + wh[i] - mouseY;
            paint();
            return;
        }
        contentClick(i);
    }

    static void onRelease() {
        dragMode = DRAG_NONE;
        dragWin = -1;
        if (pressedBtn >= 0) {
            pressedBtn = -1;
            paint();
        }
    }

    static void onDrag() {
        int i = dragWin;
        if (i < 0) return;
        if (dragMode == DRAG_MOVE) {
            wx[i] = mouseX - dragDX;
            wy[i] = mouseY - dragDY;
            if (wy[i] < DESK_TOP) wy[i] = DESK_TOP;
            if (wy[i] > DESK_BOT - TITLE_H) wy[i] = DESK_BOT - TITLE_H;
            if (wx[i] < 0 - ww[i] + 80) wx[i] = 0 - ww[i] + 80;
            if (wx[i] > SCR_W - 80) wx[i] = SCR_W - 80;
        } else {
            ww[i] = mouseX + dragDX - wx[i];
            wh[i] = mouseY + dragDY - wy[i];
            if (ww[i] < MIN_W) ww[i] = MIN_W;
            if (wh[i] < MIN_H) wh[i] = MIN_H;
            if (wx[i] + ww[i] > SCR_W) ww[i] = SCR_W - wx[i];
            if (wy[i] + wh[i] > DESK_BOT) wh[i] = DESK_BOT - wy[i];
        }
    }

    // Close / maximize / minimize buttons in the title bar. Returns true when
    // the click was consumed.
    static boolean titleButtons(int i) {
        int bx = wx[i] + ww[i] - 22;
        int by = wy[i] + 3;
        if (hit(mouseX, mouseY, bx, by, 18, 14)) {
            wOpen[i] = 0;
            clickTone();
            paint();
            return true;
        }
        bx = bx - 20;
        if (hit(mouseX, mouseY, bx, by, 18, 14)) {
            toggleMax(i);
            clickTone();
            paint();
            return true;
        }
        bx = bx - 20;
        if (hit(mouseX, mouseY, bx, by, 18, 14)) {
            wMin[i] = 1;
            clickTone();
            paint();
            return true;
        }
        return false;
    }

    static void toggleMax(int i) {
        if (wMax[i] == 1) {
            wMax[i] = 0;
            wx[i] = rx[i];
            wy[i] = ry[i];
            ww[i] = rw[i];
            wh[i] = rh[i];
        } else {
            rx[i] = wx[i];
            ry[i] = wy[i];
            rw[i] = ww[i];
            rh[i] = wh[i];
            wMax[i] = 1;
            wx[i] = 0;
            wy[i] = DESK_TOP;
            ww[i] = SCR_W;
            wh[i] = DESK_BOT - DESK_TOP;
        }
    }

    static boolean inGrip(int i) {
        return hit(mouseX, mouseY, wx[i] + ww[i] - GRIP, wy[i] + wh[i] - GRIP, GRIP, GRIP);
    }

    static void taskbarClick() {
        int bx = 8;
        int i = 0;
        while (i < WIN_COUNT) {
            if (wOpen[i] == 1) {
                if (hit(mouseX, mouseY, bx, TASK_Y + 4, 150, TASK_H - 8)) {
                    if (wMin[i] == 1) {
                        wMin[i] = 0;
                        raiseWin(i);
                    } else if (zorder[WIN_COUNT - 1] == i) {
                        wMin[i] = 1;
                    } else {
                        raiseWin(i);
                    }
                    clickTone();
                    paint();
                    return;
                }
                bx = bx + 156;
            }
            i = i + 1;
        }
        paint();
    }

    // ---- menus -----------------------------------------------------------
    static int menuIndexAt(int px) {
        if (px >= 8 && px < 56) return 0;      // File
        if (px >= 56 && px < 104) return 1;    // View
        if (px >= 104 && px < 152) return 2;   // Help
        return -1;
    }

    static int menuX(int m) {
        if (m == 0) return 8;
        if (m == 1) return 56;
        return 104;
    }

    static int menuItems(int m) {
        if (m == 0) return 3;
        if (m == 1) return 2;
        return 1;
    }

    static boolean menuHit() {
        int mx0 = menuX(menuOpen);
        int n = menuItems(menuOpen);
        if (!hit(mouseX, mouseY, mx0, MENU_H, 180, n * 20 + 6)) return false;

        int item = (mouseY - MENU_H - 3) / 20;
        if (item < 0) item = 0;
        if (item >= n) item = n - 1;
        menuAction(menuOpen, item);
        menuOpen = -1;
        clickTone();
        paint();
        return true;
    }

    static void menuAction(int m, int item) {
        if (m == 0) {
            if (item == 0) {
                openAll();
            } else if (item == 1) {
                int top = zorder[WIN_COUNT - 1];
                wOpen[top] = 0;
            } else {
                shutdown();
            }
        } else if (m == 1) {
            if (item == 0) cascade(); else restoreAll();
        } else {
            wOpen[W_ABOUT] = 1;
            wMin[W_ABOUT] = 0;
            raiseWin(W_ABOUT);
        }
    }

    static void openAll() {
        int i = 0;
        while (i < WIN_COUNT) {
            wOpen[i] = 1;
            wMin[i] = 0;
            i = i + 1;
        }
    }

    static void restoreAll() {
        int i = 0;
        while (i < WIN_COUNT) {
            wMin[i] = 0;
            wMax[i] = 0;
            i = i + 1;
        }
        cascade();
    }

    static void cascade() {
        int i = 0;
        int step = 0;
        while (i < WIN_COUNT) {
            int id = zorder[i];
            if (wOpen[id] == 1) {
                wMax[id] = 0;
                wx[id] = 60 + step * 34;
                wy[id] = DESK_TOP + 24 + step * 30;
                ww[id] = 520;
                wh[id] = 400;
                step = step + 1;
            }
            i = i + 1;
        }
    }

    static void raiseWin(int i) {
        int at = 0;
        int k = 0;
        while (k < WIN_COUNT) {
            if (zorder[k] == i) at = k;
            k = k + 1;
        }
        k = at;
        while (k < WIN_COUNT - 1) {
            zorder[k] = zorder[k + 1];
            k = k + 1;
        }
        zorder[WIN_COUNT - 1] = i;
    }

    // Topmost open, non-minimized window containing the point.
    static int windowAt(int px, int py) {
        int k = WIN_COUNT - 1;
        while (k >= 0) {
            int id = zorder[k];
            if (wOpen[id] == 1 && wMin[id] == 0) {
                if (hit(px, py, wx[id], wy[id], ww[id], wh[id])) return id;
            }
            k = k - 1;
        }
        return -1;
    }

    static boolean hit(int px, int py, int x, int y, int w, int h) {
        if (px < x) return false;
        if (py < y) return false;
        if (px >= x + w) return false;
        if (py >= y + h) return false;
        return true;
    }

    // ======================================================================
    // PAINT
    // ======================================================================
    static void paint() {
        drawDesktop();
        int k = 0;
        while (k < WIN_COUNT) {
            int id = zorder[k];
            if (wOpen[id] == 1 && wMin[id] == 0) drawWindow(id);
            k = k + 1;
        }
        drawMenuBar();
        drawTaskbar();
        if (menuOpen >= 0) drawDropdown();
        drawPointer(mouseX, mouseY);
        g.present();
    }

    static void drawDesktop() {
        g.setRGB(C_DESK);
        g.fillRect(0, DESK_TOP, SCR_W, DESK_BOT - DESK_TOP);
        if (chkGrid == 1) {
            g.setRGB(C_DESK2);
            int y = DESK_TOP;
            while (y < DESK_BOT) {
                g.fillRect(0, y, SCR_W, 1);
                y = y + 4;
            }
        }
    }

    // ---- window chrome ---------------------------------------------------
    static void drawWindow(int i) {
        int x = wx[i];
        int y = wy[i];
        int w = ww[i];
        int h = wh[i];
        boolean active = zorder[WIN_COUNT - 1] == i;

        panel(x, y, w, h, C_FACE, 1);

        int tc = C_TITLE_B;
        if (active) tc = C_TITLE_A;
        g.setRGB(tc);
        g.fillRect(x + BORDER, y + BORDER, w - 2 * BORDER, TITLE_H - BORDER);

        g.setRGB(C_TEXTLT);
        g.drawString(winTitle(i), x + 10, y + 3);

        drawTitleBtn(x + w - 62, y + 3, 0);
        drawTitleBtn(x + w - 42, y + 3, 1);
        drawTitleBtn(x + w - 22, y + 3, 2);

        int cx = x + BORDER + 5;
        int cy = y + TITLE_H + 4;
        int cw = w - 2 * BORDER - 10;
        int chh = h - TITLE_H - BORDER - 9;
        panel(cx, cy, cw, chh, C_FACE, 0);
        // Clip the content to the client area: shrinking a window must cut the
        // content off at the frame instead of letting it spill onto the desktop.
        g.setClip(cx + 1, cy + 1, cw - 2, chh - 2);
        drawContent(i, cx + 6, cy + 6, cw - 12, chh - 12);
        g.setClip(0, 0, SCR_W, SCR_H);

        if (wMax[i] == 0) drawGrip(x + w - GRIP - 2, y + h - GRIP - 2);
    }

    static String winTitle(int i) {
        if (i == W_GALLERY) return "Widget Gallery";
        if (i == W_FILES) return "File Manager";
        if (i == W_SYSTEM) return "System Info";
        return "About JVMOS";
    }

    // kind: 0 minimize, 1 maximize, 2 close
    static void drawTitleBtn(int x, int y, int kind) {
        panel(x, y, 18, 14, C_FACE, 1);
        g.setRGB(C_TEXT);
        if (kind == 0) {
            g.fillRect(x + 4, y + 9, 10, 2);
        } else if (kind == 1) {
            g.fillRect(x + 4, y + 3, 10, 8);
            g.setRGB(C_FACE);
            g.fillRect(x + 5, y + 5, 8, 5);
        } else {
            int k = 0;
            while (k < 7) {
                g.fillRect(x + 5 + k, y + 3 + k, 2, 2);
                g.fillRect(x + 11 - k, y + 3 + k, 2, 2);
                k = k + 1;
            }
        }
    }

    static void drawGrip(int x, int y) {
        int k = 0;
        while (k < 3) {
            int o = k * 4;
            g.setRGB(C_LIGHT);
            g.fillRect(x + 10 - o, y + 2, 2, 2);
            g.fillRect(x + 10 - o, y + 6, 2, 2);
            g.setRGB(C_SHADOW);
            g.fillRect(x + 11 - o, y + 3, 2, 2);
            g.fillRect(x + 11 - o, y + 7, 2, 2);
            k = k + 1;
        }
    }

    // ---- menu bar / dropdown / taskbar -----------------------------------
    static void drawMenuBar() {
        panel(0, 0, SCR_W, MENU_H, C_FACE, 1);
        drawMenuTitle("File", 0);
        drawMenuTitle("View", 1);
        drawMenuTitle("Help", 2);
    }

    static void drawMenuTitle(String s, int m) {
        int x = menuX(m);
        if (menuOpen == m) {
            g.setRGB(C_SEL);
            g.fillRect(x - 4, 2, 48, MENU_H - 4);
            g.setRGB(C_TEXTLT);
        } else {
            g.setRGB(C_TEXT);
        }
        g.drawString(s, x + 4, 2);
    }

    static void drawDropdown() {
        int x = menuX(menuOpen);
        int n = menuItems(menuOpen);
        int h = n * 20 + 6;
        panel(x, MENU_H, 180, h, C_FACE, 1);
        if (menuOpen == 0) {
            dropItem("Open All Windows", x, 0);
            dropItem("Close Active Window", x, 1);
            dropItem("Shut Down", x, 2);
        } else if (menuOpen == 1) {
            dropItem("Cascade Windows", x, 0);
            dropItem("Restore All", x, 1);
        } else {
            dropItem("About JVMOS", x, 0);
        }
    }

    static void dropItem(String s, int x, int item) {
        int y = MENU_H + 3 + item * 20;
        if (hit(mouseX, mouseY, x, y, 180, 20)) {
            g.setRGB(C_SEL);
            g.fillRect(x + 2, y, 176, 20);
            g.setRGB(C_TEXTLT);
        } else {
            g.setRGB(C_TEXT);
        }
        g.drawString(s, x + 10, y + 2);
    }

    static void drawTaskbar() {
        panel(0, TASK_Y, SCR_W, TASK_H, C_FACE, 1);
        int bx = 8;
        int i = 0;
        while (i < WIN_COUNT) {
            if (wOpen[i] == 1) {
                int raised = 1;
                if (wMin[i] == 0 && zorder[WIN_COUNT - 1] == i) raised = 0;
                panel(bx, TASK_Y + 4, 150, TASK_H - 8, C_FACE, raised);
                g.setRGB(C_TEXT);
                g.drawString(winTitle(i), bx + 8 + (1 - raised), TASK_Y + 8 + (1 - raised));
                bx = bx + 156;
            }
            i = i + 1;
        }
        drawClock(SCR_W - 96, TASK_Y + 6);
    }

    static void drawClock(int x, int y) {
        panel(x - 6, TASK_Y + 4, 92, TASK_H - 8, C_FACE, 0);
        g.setRGB(C_TEXT);
        int h = Calendar.get(Calendar.HOUR);
        int m = Calendar.get(Calendar.MINUTE);
        int s = Calendar.get(Calendar.SECOND);
        int cx = x;
        cx = twoDigits(h, cx, y);
        cx = g.drawChar(':', cx, y);
        cx = twoDigits(m, cx, y);
        cx = g.drawChar(':', cx, y);
        twoDigits(s, cx, y);
    }

    static int twoDigits(int v, int x, int y) {
        int nx = g.drawChar((char) ((v / 10) + 48), x, y);
        return g.drawChar((char) ((v % 10) + 48), nx, y);
    }

    // ---- mouse pointer ---------------------------------------------------
    static void drawPointer(int x, int y) {
        g.setRGB(C_TEXT);
        int i = 0;
        while (i < 12) {
            g.fillRect(x, y + i, i + 2, 1);
            i = i + 1;
        }
        g.fillRect(x + 2, y + 12, 4, 5);
        g.setRGB(C_TEXTLT);
        i = 1;
        while (i < 10) {
            g.fillRect(x + 1, y + i, i, 1);
            i = i + 1;
        }
        g.fillRect(x + 3, y + 10, 2, 6);
    }

    // ======================================================================
    // WIDGET PRIMITIVES
    // ======================================================================
    static void bevel(int x, int y, int w, int h, int raised) {
        int tl = C_LIGHT;
        int br = C_SHADOW;
        if (raised == 0) {
            tl = C_SHADOW;
            br = C_LIGHT;
        }
        g.setRGB(tl);
        g.fillRect(x, y, w, 1);
        g.fillRect(x, y, 1, h);
        g.setRGB(br);
        g.fillRect(x, y + h - 1, w, 1);
        g.fillRect(x + w - 1, y, 1, h);
    }

    static void panel(int x, int y, int w, int h, int bg, int raised) {
        g.setRGB(bg);
        g.fillRect(x, y, w, h);
        bevel(x, y, w, h, raised);
    }

    // nch = number of characters, used to centre without String.length()
    static void button(int x, int y, int w, int h, String label, int nch, int down) {
        int raised = 1;
        if (down == 1) raised = 0;
        panel(x, y, w, h, C_FACE, raised);
        g.setRGB(C_TEXT);
        g.drawString(label, x + (w - nch * CH_W) / 2 + down, y + (h - CH_H) / 2 + down);
    }

    static void checkbox(int x, int y, String label, int on) {
        panel(x, y, 14, 14, C_FIELD, 0);
        if (on == 1) {
            g.setRGB(C_TEXT);
            int k = 0;
            while (k < 5) {
                g.fillRect(x + 3 + k, y + 6 + k, 2, 2);
                k = k + 1;
            }
            k = 0;
            while (k < 5) {
                g.fillRect(x + 11 - k, y + 3 + k, 2, 2);
                k = k + 1;
            }
        }
        g.setRGB(C_TEXT);
        g.drawString(label, x + 22, y - 1);
    }

    static void radio(int x, int y, String label, int on) {
        panel(x, y, 14, 14, C_FIELD, 0);
        if (on == 1) {
            g.setRGB(C_TEXT);
            g.fillRect(x + 5, y + 4, 4, 6);
            g.fillRect(x + 4, y + 5, 6, 4);
        }
        g.setRGB(C_TEXT);
        g.drawString(label, x + 22, y - 1);
    }

    static void progressBar(int x, int y, int w, int pct) {
        panel(x, y, w, 18, C_FIELD, 0);
        int fill = (w - 4) * pct / 100;
        if (fill < 0) fill = 0;
        if (fill > w - 4) fill = w - 4;
        g.setRGB(C_GREEN);
        g.fillRect(x + 2, y + 2, fill, 14);
    }

    // Text input. The buffer is drawn character by character because
    // drawString only works on constant-pool literals.
    static void textField(int x, int y, int w) {
        panel(x, y, w, 22, C_FIELD, 0);
        g.setRGB(C_TEXT);
        int i = 0;
        int cx = x + 4;
        while (i < fieldLen) {
            cx = g.drawChar((char) fieldBuf[i], cx, y + 3);
            i = i + 1;
        }
        if (fieldFocus == 1) {
            int blink = Native.sys(Native.SYS_GET_TICKS, 0, 0, 0, 0) / 500;
            if (blink - (blink / 2) * 2 == 0) {
                g.setRGB(C_TEXT);
                g.fillRect(cx, y + 3, 8, 16);
            }
        }
    }

    static void listRow(String label, int x, int y, int w, int idx) {
        if (modSel == idx) {
            g.setRGB(C_SEL);
            g.fillRect(x, y, w, 18);
            g.setRGB(C_TEXTLT);
        } else {
            g.setRGB(C_TEXT);
        }
        g.drawString(label, x + 6, y + 1);
    }

    static void label(String s, int x, int y) {
        g.setRGB(C_TEXT);
        g.drawString(s, x, y);
    }

    static void groupBox(int x, int y, int w, int h, String title) {
        bevel(x, y + 7, w, h - 7, 0);
        g.setRGB(C_FACE);
        g.fillRect(x + 8, y, w - 16, 14);
        g.setRGB(C_TITLE_A);
        g.drawString(title, x + 12, y - 1);
    }

    // ======================================================================
    // WINDOW CONTENT
    // ======================================================================
    static void drawContent(int i, int x, int y, int w, int h) {
        if (i == W_GALLERY) {
            drawGallery(x, y, w, h);
        } else if (i == W_FILES) {
            drawFiles(x, y, w, h);
        } else if (i == W_SYSTEM) {
            drawSystem(x, y, w, h);
        } else {
            drawAbout(x, y, w, h);
        }
    }

    static void drawGallery(int x, int y, int w, int h) {
        boolean act = isActive(W_GALLERY);

        groupBox(x, y + 8, 210, 96, "Options");
        checkbox(x + 12, y + 26, "Sound enabled", chkSound);
        checkbox(x + 12, y + 50, "Desktop grid", chkGrid);
        checkbox(x + 12, y + 74, "Show status", chkStatus);

        groupBox(x + 224, y + 8, 210, 96, "Refresh rate");
        radio(x + 236, y + 26, "Low", boolInt(radioSel == 0));
        radio(x + 236, y + 50, "Normal", boolInt(radioSel == 1));
        radio(x + 236, y + 74, "High", boolInt(radioSel == 2));

        label("Name:", x + 12, y + 122);
        textField(x + 68, y + 118, 200);

        label("Progress:", x + 12, y + 158);
        progressBar(x + 90, y + 156, 180, progress);
        drawPercent(progress, x + 280, y + 157);

        button(x + 12, y + 190, 84, 26, "Minus", 5, boolInt(pressedBtn == 0));
        button(x + 104, y + 190, 84, 26, "Plus", 4, boolInt(pressedBtn == 1));
        button(x + 196, y + 190, 84, 26, "Beep", 4, boolInt(pressedBtn == 2));

        // The list only shows as many rows as the current window height allows.
        int rows = (y + h - (y + 254)) / 20;
        if (rows > 5) rows = 5;
        if (rows > 0) {
            int boxH = rows * 20 + 18;
            groupBox(x + 12, y + 236, 270, boxH, "Modules");
            if (rows > 0) listRow("kernel.Boot", x + 16, y + 254, 262, 0);
            if (rows > 1) listRow("java.awt.Graphics2D", x + 16, y + 274, 262, 1);
            if (rows > 2) listRow("java.io.PrintStream", x + 16, y + 294, 262, 2);
            if (rows > 3) listRow("java.util.Calendar", x + 16, y + 314, 262, 3);
            if (rows > 4) listRow("kernel.Native", x + 16, y + 334, 262, 4);
        }

        if (act) drawGalleryFocus(x, y, rows);
    }

    static void drawGalleryFocus(int x, int y, int rows) {
        int f = focus[W_GALLERY];
        if (f == 0) focusRing(x + 8, y + 22, 190, 22);
        else if (f == 1) focusRing(x + 8, y + 46, 190, 22);
        else if (f == 2) focusRing(x + 8, y + 70, 190, 22);
        else if (f == 3) focusRing(x + 232, y + 22, 130, 22);
        else if (f == 4) focusRing(x + 232, y + 46, 130, 22);
        else if (f == 5) focusRing(x + 232, y + 70, 130, 22);
        else if (f == 6) focusRing(x + 66, y + 116, 204, 26);
        else if (f == 7) focusRing(x + 10, y + 188, 88, 30);
        else if (f == 8) focusRing(x + 102, y + 188, 88, 30);
        else if (f == 9) focusRing(x + 194, y + 188, 88, 30);
        else if (f == 10 && rows > 0) focusRing(x + 14, y + 250, 266, rows * 20 + 6);
    }

    static void drawPercent(int v, int x, int y) {
        g.setRGB(C_TEXT);
        int nx = g.drawInt(v, x, y);
        g.drawString("%", nx, y);
    }

    static int boolInt(boolean b) {
        if (b) return 1;
        return 0;
    }

    static void drawFiles(int x, int y, int w, int h) {
        panel(x, y, w, 22, C_FACE, 1);
        g.setRGB(C_TEXT);
        g.drawString("/ (root)", x + 8, y + 3);

        // Rows follow the window height, and each row spans the full width.
        int rows = (h - 30) / 24;
        if (rows > 6) rows = 6;
        if (rows > 0) fileRow("APPS", x, y + 30, w, 0, 1);
        if (rows > 1) fileRow("DOCS", x, y + 54, w, 1, 1);
        if (rows > 2) fileRow("SYSTEM", x, y + 78, w, 2, 1);
        if (rows > 3) fileRow("README.TXT", x, y + 102, w, 3, 0);
        if (rows > 4) fileRow("BOOT.LOG", x, y + 126, w, 4, 0);
        if (rows > 5) fileRow("PAINT.CLASS", x, y + 150, w, 5, 0);

        if (isActive(W_FILES) && rows > 0) {
            int sel = fileSel;
            if (sel >= rows) sel = rows - 1;
            focusRing(x, y + 28 + sel * 24, w, 24);
        }
    }

    static void fileRow(String name, int x, int y, int w, int idx, int isDir) {
        if (fileSel == idx) {
            g.setRGB(C_SEL);
            g.fillRect(x, y, w, 20);
        }
        if (isDir == 1) {
            g.setRGB(C_AMBER);
            g.fillRect(x + 4, y + 5, 14, 10);
            g.fillRect(x + 4, y + 3, 6, 2);
        } else {
            g.setRGB(C_LIGHT);
            g.fillRect(x + 6, y + 3, 11, 14);
            g.setRGB(C_DARK);
            g.fillRect(x + 8, y + 6, 7, 1);
            g.fillRect(x + 8, y + 9, 7, 1);
            g.fillRect(x + 8, y + 12, 7, 1);
        }
        if (fileSel == idx) g.setRGB(C_TEXTLT); else g.setRGB(C_TEXT);
        g.drawString(name, x + 26, y + 2);
    }

    static void drawSystem(int x, int y, int w, int h) {
        infoRow("Kernel", "JVMOS-JIT 2.6", x, y + 6);
        if (h > 44) infoRow("Engine", "bytecode -> x86 JIT", x, y + 28);
        if (h > 66) infoRow("Video", "VESA 1024x768 32bpp", x, y + 50);
        if (h > 88) infoRow("Renderer", "double buffered", x, y + 72);
        if (h < 140) return;

        label("Uptime:", x, y + 100);
        g.setRGB(C_TEXT);
        int t = Native.sys(Native.SYS_GET_TICKS, 0, 0, 0, 0) / 1000;
        int nx = g.drawInt(t, x + 88, y + 100);
        g.drawString("s", nx, y + 100);

        // SYS_KALLOC with size 0 returns the current bump pointer without
        // allocating; subtracting the heap base gives the bytes handed out.
        // 0xA00000 must match heap_start_ptr in boot/sys_api.asm.
        label("Heap used:", x, y + 124);
        g.setRGB(C_TEXT);
        nx = g.drawInt(Native.sys(Native.SYS_KALLOC, 0, 0, 0, 0) - 0x00A00000, x + 88, y + 124);
        g.drawString(" bytes", nx, y + 124);
    }

    static void infoRow(String k, String v, int x, int y) {
        g.setRGB(C_TITLE_A);
        g.drawString(k, x, y);
        g.setRGB(C_TEXT);
        g.drawString(v, x + 88, y);
    }

    static void drawAbout(int x, int y, int w, int h) {
        g.setRGB(C_TITLE_A);
        g.drawString("JVMOS / JIT", x + 8, y + 6);
        g.setRGB(C_TEXT);
        g.drawString("A baremetal operating system whose", x + 8, y + 34);
        g.drawString("userland is Java bytecode compiled to", x + 8, y + 52);
        g.drawString("native x86 at runtime.", x + 8, y + 70);
        g.drawString("Kernel: NASM + C   UI: Java", x + 8, y + 98);
        button(x + w / 2 - 42, y + h - 40, 84, 26, "Close", 5, boolInt(pressedBtn == 3));
        if (isActive(W_ABOUT)) focusRing(x + w / 2 - 44, y + h - 42, 88, 30);
    }

    // ======================================================================
    // CONTENT INTERACTION
    // ======================================================================
    static void contentClick(int i) {
        int cx = wx[i] + BORDER + 11;
        int cy = wy[i] + TITLE_H + 10;
        if (i == W_GALLERY) {
            galleryClick(cx, cy);
        } else if (i == W_FILES) {
            filesClick(cx, cy, ww[i] - 2 * BORDER - 22);
        } else if (i == W_ABOUT) {
            aboutClick(cx, cy, i);
        }
        paint();
    }

    // Clicking a control also gives it the keyboard focus, so mouse and
    // keyboard always agree on what is currently selected.
    static void galleryClick(int x, int y) {
        if (hit(mouseX, mouseY, x + 12, y + 26, 190, 14)) {
            chkSound = 1 - chkSound;
            setFocus(W_GALLERY, 0);
        }
        if (hit(mouseX, mouseY, x + 12, y + 50, 190, 14)) {
            chkGrid = 1 - chkGrid;
            setFocus(W_GALLERY, 1);
        }
        if (hit(mouseX, mouseY, x + 12, y + 74, 190, 14)) {
            chkStatus = 1 - chkStatus;
            setFocus(W_GALLERY, 2);
        }

        if (hit(mouseX, mouseY, x + 236, y + 26, 190, 14)) {
            radioSel = 0;
            setFocus(W_GALLERY, 3);
        }
        if (hit(mouseX, mouseY, x + 236, y + 50, 190, 14)) {
            radioSel = 1;
            setFocus(W_GALLERY, 4);
        }
        if (hit(mouseX, mouseY, x + 236, y + 74, 190, 14)) {
            radioSel = 2;
            setFocus(W_GALLERY, 5);
        }

        if (hit(mouseX, mouseY, x + 68, y + 118, 200, 22)) setFocus(W_GALLERY, F_FIELD);

        if (hit(mouseX, mouseY, x + 12, y + 190, 84, 26)) {
            pressedBtn = 0;
            setFocus(W_GALLERY, 7);
            progress = progress - 10;
            if (progress < 0) progress = 0;
            if (chkSound == 1) clickTone();
        }
        if (hit(mouseX, mouseY, x + 104, y + 190, 84, 26)) {
            pressedBtn = 1;
            setFocus(W_GALLERY, 8);
            progress = progress + 10;
            if (progress > 100) progress = 100;
            if (chkSound == 1) clickTone();
        }
        if (hit(mouseX, mouseY, x + 196, y + 190, 84, 26)) {
            pressedBtn = 2;
            setFocus(W_GALLERY, 9);
            note(880, 90);
        }

        int row = 0;
        while (row < 5) {
            if (hit(mouseX, mouseY, x + 16, y + 254 + row * 20, 262, 18)) {
                modSel = row;
                setFocus(W_GALLERY, F_LIST);
            }
            row = row + 1;
        }
    }

    static void filesClick(int x, int y, int w) {
        int row = 0;
        while (row < 6) {
            if (hit(mouseX, mouseY, x, y + 30 + row * 24, w, 20)) {
                fileSel = row;
                if (chkSound == 1) clickTone();
            }
            row = row + 1;
        }
    }

    // x,y are the content origin; recompute the content box exactly as
    // drawWindow does so the hit box matches the drawn button.
    static void aboutClick(int x, int y, int i) {
        int cw = ww[i] - 2 * BORDER - 22;
        int ch = wh[i] - TITLE_H - BORDER - 21;
        if (hit(mouseX, mouseY, x + cw / 2 - 42, y + ch - 40, 84, 26)) {
            wOpen[W_ABOUT] = 0;
            clickTone();
        }
    }

    // ======================================================================
    // SHUTDOWN
    // ======================================================================
    static void shutdown() {
        g.setRGB(0x00000000);
        g.fillRect(0, 0, SCR_W, SCR_H);
        g.setRGB(C_RED);
        g.drawString("Shutting down JVMOS...", 400, 370);
        g.present();
        note(660, 160);
        note(440, 320);
        Native.sys(Native.SYS_SLEEP, 400, 0, 0, 0);
        Native.sys(Native.SYS_EXIT, 0, 0, 0, 0);
    }
}
