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

    static final int TITLE_H = 27;
    static final int BORDER = 3;
    static final int GRIP = 14;
    static final int SHADOW = 12;
    static final int TBTN = 18;      // title bar button size
    static final int MIN_W = 260;
    static final int MIN_H = 140;

    // ---- palette (0x00RRGGBB) -------------------------------------------
    // Neutral surfaces and text follow KDE Breeze, which is tuned for long
    // reading: body text is #232629 rather than pure black, and secondary text
    // is a blue-grey #707D8A that still reads against the surface (the old
    // #868686 on grey was the reason the help line went unnoticed).
    // One deep teal carries every interactive accent.
    static final int C_DESK = 0x000E3038;      // desktop base, a darker accent
    static final int C_DESK2 = 0x0014606B;     // desktop stripes, the title bar colour
    static final int C_FACE = 0x00EFF0F1;      // window surface
    static final int C_SURF2 = 0x00E3E6E9;     // buttons, recessed strips
    static final int C_LIGHT = 0x00FFFFFF;
    static final int C_DARK = 0x005A6472;      // secondary text, 5.3:1 on the surface
    static final int C_MUTED = 0x008A96A3;     // genuinely inactive, on dark only
    static final int C_SHADOW = 0x00C6CDD3;    // hairline
    static final int C_LINE2 = 0x00AEB7BF;     // stronger hairline
    static final int C_TITLE_A = 0x0014606B;   // accent: active title bar + labels
    static final int C_TITLE_B = 0x00DCE0E4;   // inactive title bar
    static final int C_TEXT = 0x00232629;
    static final int C_TEXTLT = 0x00FFFFFF;
    static final int C_FIELD = 0x00FFFFFF;
    static final int C_SEL = 0x0012808F;       // selection, brighter teal
    static final int C_GREEN = 0x002E7D4F;
    static final int C_RED = 0x00C0392B;
    static final int C_AMBER = 0x00C9860A;

    // ---- window ids ------------------------------------------------------
    static final int WIN_COUNT = 7;
    static final int W_GALLERY = 0;
    static final int W_FILES = 1;
    static final int W_SYSTEM = 2;
    static final int W_ABOUT = 3;
    static final int W_SOKOBAN = 4;
    static final int W_WASM = 5;
    static final int W_EDIT = 6;

    // ---- sokoban ---------------------------------------------------------
    static final int SOKO_LEVELS = 61;
    static final int SOKO_STRIDE = 32;   // cells per row in the flat grids
    static final int SOKO_ROWS = 24;
    static final int SOKO_CELLS = SOKO_STRIDE * SOKO_ROWS;

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

    // system sounds, ids shared with wasm/sb16.c
    static final int SND_BOOT = 0;
    static final int SND_CLICK = 1;
    static final int SND_DENY = 2;
    static final int SND_STEP = 3;
    static final int SND_PUSH = 4;
    static final int SND_BUMP = 5;
    static final int SND_WIN = 6;

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
    static int fileSel;                 // selected entry in the File Manager
    static int fileTop;                 // first entry visible, for scrolling
    static int fileMsg;                 // 0 none, 1 no association, 2 directory, 3 unreadable
    static int fileArmed;               // entry Ctrl+D is armed to delete, -1 for none

    static int edCursor;                // caret offset into the kernel's text buffer
    static int edTop;                   // first visible line
    static int edNameFocus;             // 1 while the filename field takes the keys
    static int edStatus;                // 0 none, 1 saved, 2 save failed, 3 deleted

    // Which application handles a file, decided by its extension alone.
    static final int APP_NONE = 0;
    static final int APP_EDITOR = 1;
    static final int APP_WASM = 2;

    // desktop icons
    static int iconSel;                 // selected icon, -1 for none
    static int iconClickTick;           // when the last icon click landed

    // Returned by SYS_FS_STATUS.
    static final int FS_ABSENT = 0;     // no disk answered
    static final int FS_MOUNTED = 1;    // an existing volume was found
    static final int FS_FORMATTED = 2;  // the disk was blank, so we made one
    static final int FS_FOREIGN = 3;    // occupied by something not ours to erase
    static int fieldFocus, fieldLen;
    static int[] fieldBuf;
    static int pressedBtn;              // -1 none, else button id
    static int lastKey;
    static int[] focus;                 // focused control per window

    // sokoban state (flat grids, SOKO_STRIDE cells per row)
    static int[] sokoWall, sokoGoal, sokoBox;
    static int sokoW, sokoH;            // level bounding box
    static int sokoPX, sokoPY;          // player cell
    static int sokoLevel, sokoMoves, sokoPushes;
    static int sokoTotal, sokoOn, sokoDone;

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
        sokoWall = new int[SOKO_CELLS];
        sokoGoal = new int[SOKO_CELLS];
        sokoBox = new int[SOKO_CELLS];

        setWin(W_GALLERY, 60, 60, 520, 430);
        setWin(W_FILES, 600, 90, 380, 330);
        setWin(W_SYSTEM, 150, 430, 430, 250);
        setWin(W_ABOUT, 300, 220, 420, 220);
        setWin(W_SOKOBAN, 430, 120, 540, 520);
        setWin(W_WASM, 120, 150, 470, 380);
        setWin(W_EDIT, 250, 160, 520, 400);

        // Only the File Manager opens at boot: it shows the volume, which is
        // where the programs are. Everything else waits behind its icon.
        wOpen[W_GALLERY] = 0;
        wOpen[W_FILES] = 1;
        wOpen[W_SYSTEM] = 0;
        wOpen[W_ABOUT] = 0;
        wOpen[W_SOKOBAN] = 0;
        wOpen[W_WASM] = 0;
        wOpen[W_EDIT] = 0;

        // The front of this list is the last entry, and handleKeys sends keys
        // to it, so a closed window must never sit there.
        zorder[0] = W_EDIT;
        zorder[1] = W_SYSTEM;
        zorder[2] = W_GALLERY;
        zorder[3] = W_ABOUT;
        zorder[4] = W_SOKOBAN;
        zorder[5] = W_WASM;
        zorder[6] = W_FILES;

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
        fileTop = 0;
        fileMsg = 0;
        fileArmed = -1;
        edCursor = 0;
        edTop = 0;
        edNameFocus = 0;
        edStatus = 0;
        iconSel = 0;
        iconClickTick = 0;
        fieldFocus = 0;
        fieldLen = 0;

        sokoLevel = 0;
        sokoLoad(0);
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

    // Every sound the desktop makes goes through the mixer in wasm/sb16.c,
    // which plays a mixed PCM clip on a Sound Blaster and falls back to the
    // speaker without one. On the card nothing blocks, so the boot sound no
    // longer holds the machine for a second and a half before the desktop
    // appears, and it can be a chord rather than a run of single notes.
    static void play(int id) {
        Native.sys(Native.SYS_SND_PLAY, id, 0, 0, 0);
    }

    static void chime() {
        play(SND_BOOT);
    }

    static void clickTone() {
        play(SND_CLICK);
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

            // The sequencer needs advancing far more often than the screen is
            // repainted, so it is ticked here, where a tick is one comparison
            // against the clock. It falls silent on its own when the guest's
            // window is closed or minimised.
            int audible = 0;
            if (wOpen[W_WASM] == 1 && wMin[W_WASM] == 0) audible = 1;
            Native.sys(Native.SYS_WASM_MUSIC, audible, 0, 0, 0);

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
                closeWin(top);
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

        // Nothing is open, so the keyboard belongs to the desktop icons.
        if (openWindows() == 0) {
            desktopKey(code);
            paint();
            return;
        }

        if (top == W_SOKOBAN && wMin[top] == 0 && code != K_TAB) {
            sokoKey(code);
            return;
        }

        if (top == W_WASM && wMin[top] == 0 && code != K_TAB) {
            wasmKey(code);
            return;
        }

        // The editor needs Tab and Escape of its own, so it is routed the raw
        // value and handles every key itself.
        if (top == W_EDIT && wOpen[top] == 1 && wMin[top] == 0) {
            editorKey(raw);
            paint();
            return;
        }

        if (top == W_FILES && (raw & M_CTRL) != 0 && code == 'd') {
            filesDelete();
            paint();
            return;
        }

        if (code == K_ESC) {
            fieldFocus = 0;
            if (wOpen[W_ABOUT] == 1) closeWin(W_ABOUT);
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

    // The editor takes the whole keyboard while it is in front: Alt+F4,
    // Alt+Tab and F10 are already dealt with before this is reached.
    static void editorKey(int raw) {
        int code = raw & K_MASK;
        edStatus = 0;

        if ((raw & M_CTRL) != 0) {
            if (code == 's') {
                edSave();
                return;
            }
            if (code == 'n') {
                edNew();
                return;
            }
            return;                     // no other control key types a letter
        }
        if (code == K_TAB) {
            edNameFocus = 1 - edNameFocus;
            return;
        }
        if (edNameFocus == 1) {
            edNameKey(code);
            return;
        }
        if (code == K_ESC) return;
        if (code == K_LEFT) {
            if (edCursor > 0) edCursor = edCursor - 1;
            return;
        }
        if (code == K_RIGHT) {
            if (edCursor < edLen()) edCursor = edCursor + 1;
            return;
        }
        if (code == K_UP) {
            edMoveLine(-1);
            return;
        }
        if (code == K_DOWN) {
            edMoveLine(1);
            return;
        }
        if (code == K_BACK) {
            if (edCursor > 0) {
                edCursor = edCursor - 1;
                edCall(Native.ED_DELETE, edCursor, 0);
            }
            return;
        }
        if (code == K_ENTER) {
            edInsert(10);
            return;
        }
        if (code >= 32 && code <= 126) edInsert(code);
    }

    static void edInsert(int ch) {
        if (edCall(Native.ED_INSERT, edCursor, ch) == 1) {
            edCursor = edCursor + 1;
        } else {
            play(SND_DENY);             // the buffer is full
        }
    }

    // Up and down keep the column where they can, which is what every editor
    // does and what makes a short line in the middle of a file survivable.
    static void edMoveLine(int dir) {
        int line = edLineOf(edCursor);
        int col = edCursor - edLineStart(line);
        int start = edLineStart(line + dir);
        int end;
        if (line + dir < 0 || start < 0) return;
        end = edLineEnd(start);
        if (start + col > end) edCursor = end; else edCursor = start + col;
    }

    static void edNameKey(int code) {
        if (code == K_BACK) {
            edCall(Native.ED_NAME_POP, 0, 0);
            return;
        }
        if (code == K_ENTER || code == K_ESC) {
            edNameFocus = 0;
            return;
        }
        if (code >= 32 && code <= 126) edCall(Native.ED_NAME_PUSH, edUpper(code), 0);
    }

    // 8.3 names are stored upper case. Without this a file typed as "notes"
    // would list as "NOTES" and look like somebody else's file.
    static int edUpper(int c) {
        if (c >= 'a' && c <= 'z') return c - 32;
        return c;
    }

    static void edSave() {
        if (edCall(Native.ED_SAVE, 0, 0) == 1) {
            edStatus = 1;
            play(SND_CLICK);
        } else {
            edStatus = 2;
            play(SND_DENY);
        }
    }

    static void edNew() {
        edCall(Native.ED_CLEAR, 0, 0);
        edCursor = 0;
        edTop = 0;
        edNameFocus = 1;                // an unnamed file cannot be saved
        edStatus = 0;
    }

    // ======================================================================
    // FILE ASSOCIATION
    //
    // The extension is all there is to decide with: nothing here sniffs the
    // contents. A file the editor cannot open is said to have no association
    // rather than being opened as garbage.
    // ======================================================================
    // WSM and not WASM: an 8.3 name has room for three characters of extension
    // and no more.
    static int appFor(int idx) {
        int a = Native.sys(Native.SYS_FS_NAME, idx, 8, 0, 0);
        int b = Native.sys(Native.SYS_FS_NAME, idx, 9, 0, 0);
        int c = Native.sys(Native.SYS_FS_NAME, idx, 10, 0, 0);
        if (a == 'W' && b == 'S' && c == 'M') return APP_WASM;
        if (a == 32 && b == 32 && c == 32) return APP_EDITOR;   // no extension
        if (a == 'T' && b == 'X' && c == 'T') return APP_EDITOR;
        if (a == 'L' && b == 'O' && c == 'G') return APP_EDITOR;
        if (a == 'I' && b == 'N' && c == 'I') return APP_EDITOR;
        if (a == 'C' && b == 'F' && c == 'G') return APP_EDITOR;
        if (a == 'C' && b == 'S' && c == 'V') return APP_EDITOR;
        if (a == 'M' && b == 'D' && c == 32) return APP_EDITOR;
        return APP_NONE;
    }

    static void openSelected() {
        int count = Native.sys(Native.SYS_FS_COUNT, 0, 0, 0, 0);
        int app;
        fileArmed = -1;
        if (fileSel < 0 || fileSel >= count) return;
        if (Native.sys(Native.SYS_FS_ISDIR, fileSel, 0, 0, 0) == 1) {
            fileMsg = 2;
            play(SND_DENY);
            return;
        }
        app = appFor(fileSel);
        if (app == APP_EDITOR) {
            openInEditor();
            return;
        }
        if (app == APP_WASM) {
            runProgram();
            return;
        }
        fileMsg = 1;
        play(SND_DENY);
    }

    static void openInEditor() {
        if (edCall(Native.ED_OPEN, fileSel, 0) < 0) {
            fileMsg = 3;
            play(SND_DENY);
            return;
        }
        fileMsg = 0;
        edCursor = 0;
        edTop = 0;
        edNameFocus = 0;
        edStatus = 0;
        wOpen[W_EDIT] = 1;
        wMin[W_EDIT] = 0;
        raiseWin(W_EDIT);
        play(SND_CLICK);
    }

    // The program is read off the volume and handed to the sandbox, which
    // either finds the imports it asks for or refuses to start it. Nothing is
    // relocated and nothing is linked: that is what makes the loader four
    // lines instead of a subsystem.
    static void runProgram() {
        if (Native.sys(Native.SYS_FS_RUN, fileSel, 0, 0, 0) < 0) {
            fileMsg = 6;
            play(SND_DENY);
            return;
        }
        fileMsg = 0;
        wOpen[W_WASM] = 1;
        wMin[W_WASM] = 0;
        raiseWin(W_WASM);
        play(SND_CLICK);
    }

    // Deleting asks twice. Losing a file to one stray keystroke is not a
    // consequence of anything the user decided, it is just a bad control.
    // Moving the selection disarms a pending delete: the confirmation belongs
    // to one entry, and it must not survive onto a different one.
    static void filesTouched() {
        fileArmed = -1;
        fileMsg = 0;
    }

    static void filesDelete() {
        int count = Native.sys(Native.SYS_FS_COUNT, 0, 0, 0, 0);
        if (fileSel < 0 || fileSel >= count) return;
        if (fileArmed != fileSel) {
            fileArmed = fileSel;
            fileMsg = 4;
            return;
        }
        fileArmed = -1;
        if (edCall(Native.ED_REMOVE, fileSel, 0) == 1) {
            fileMsg = 5;
            play(SND_CLICK);
        } else {
            fileMsg = 3;
            play(SND_DENY);
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
            // Bounded by what is actually on the disk, not by a fixed six.
            int count = Native.sys(Native.SYS_FS_COUNT, 0, 0, 0, 0);
            if (fileSel < count - 1) fileSel = fileSel + 1;
            filesTouched();
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
            filesTouched();
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
            closeWin(W_ABOUT);
            clickTone();
            return;
        }
        if (i == W_FILES) {
            openSelected();
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
            play(SND_WIN);
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
    // Solid accent outline: a dotted black ring is another 90s tell.
    static void focusRing(int x, int y, int w, int h) {
        g.setRGB(C_SEL);
        g.fillRect(x, y, w, 1);
        g.fillRect(x, y + h - 1, w, 1);
        g.fillRect(x, y, 1, h);
        g.fillRect(x + w - 1, y, 1, h);
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
            desktopClick();
            paint();
            return;
        }
        iconSel = -1;
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
            if (wx[i] < 0) wx[i] = 0;
            if (wx[i] + ww[i] > SCR_W) wx[i] = SCR_W - ww[i];
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
        int by = wy[i] + BORDER + (TITLE_H - BORDER - TBTN) / 2;
        int bx = wx[i] + ww[i] - 26;
        if (hit(mouseX, mouseY, bx, by, TBTN, TBTN)) {
            closeWin(i);
            clickTone();
            paint();
            return true;
        }
        bx = bx - 22;
        if (hit(mouseX, mouseY, bx, by, TBTN, TBTN)) {
            toggleMax(i);
            clickTone();
            paint();
            return true;
        }
        bx = bx - 22;
        if (hit(mouseX, mouseY, bx, by, TBTN, TBTN)) {
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
                if (hit(mouseX, mouseY, bx, TASK_Y + 4, 128, TASK_H - 8)) {
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
                bx = bx + 134;
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
        if (m == 0) return 4;
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
                edNew();
                wOpen[W_EDIT] = 1;
                wMin[W_EDIT] = 0;
                raiseWin(W_EDIT);
            } else if (item == 1) {
                openAll();
            } else if (item == 2) {
                closeWin(zorder[WIN_COUNT - 1]);
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

    // Closing a window also sinks it to the bottom of the z-order. Without
    // that it stays the front window while shut, and handleKeys keeps sending
    // every keystroke to something nobody can see.
    static void closeWin(int i) {
        int at = 0;
        int k = 0;
        while (k < WIN_COUNT) {
            if (zorder[k] == i) at = k;
            k = k + 1;
        }
        k = at;
        while (k > 0) {
            zorder[k] = zorder[k - 1];
            k = k - 1;
        }
        zorder[0] = i;
        wOpen[i] = 0;
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
        drawIcons();
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

    // Horizontal stripes in the title bar colour. The "Desktop grid" checkbox
    // still switches the pattern off for a flat surface.
    static void drawDesktop() {
        g.setRGB(C_DESK);
        g.fillRect(0, DESK_TOP, SCR_W, DESK_BOT - DESK_TOP);
        if (chkGrid == 0) return;
        g.setRGB(C_DESK2);
        int y = DESK_TOP;
        while (y < DESK_BOT) {
            g.fillRect(0, y, SCR_W, 1);
            y = y + 4;
        }
    }

    // ---- window chrome ---------------------------------------------------
    static void drawWindow(int i) {
        int x = wx[i];
        int y = wy[i];
        int w = ww[i];
        int h = wh[i];
        boolean active = zorder[WIN_COUNT - 1] == i;

        if (wMax[i] == 0) {
            if (active) dropShadow(x, y, w, h); else softShadow(x, y, w, h);
        }
        panel(x, y, w, h, C_FACE, 1);

        int tc = C_TITLE_B;
        int ti = C_DARK;   // readable on the light inactive bar
        if (active) {
            tc = C_TITLE_A;
            ti = C_TEXTLT;
        }
        int barY = y + BORDER;
        int barH = TITLE_H - BORDER;
        g.setRGB(tc);
        g.fillRect(x + BORDER, barY, w - 2 * BORDER, barH);

        // Caption centred in the bar instead of pinned to its top edge.
        g.setRGB(ti);
        g.drawString(winTitle(i), x + 12, barY + (barH - CH_H) / 2 + 2);

        int btnY = barY + (barH - TBTN) / 2;
        drawTitleBtn(x + w - 70, btnY, 0, active);
        drawTitleBtn(x + w - 48, btnY, 1, active);
        drawTitleBtn(x + w - 26, btnY, 2, active);

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

    // Only the L-shaped band that stays visible is blended: filling the whole
    // offset rectangle would darken ~200k pixels per window that the window
    // itself immediately paints over.
    //
    // The bands are nested and each pass is faint, so the overlap does the
    // fading for us: the strip hugging the frame gets three passes, the outer
    // strip only one. The bottom band stops at the window edge and the right
    // band owns the corner, so nothing is blended twice by accident - that
    // double-darkened corner square was the artefact under the old version.
    static void dropShadow(int x, int y, int w, int h) {
        g.setRGB(0x00000000);
        g.setBlend(3);
        int j = 0;
        while (j < 3) {
            int s = SHADOW - j * 4;
            g.fillBlend(x + w, y + SHADOW, s, h - SHADOW + s);
            g.fillBlend(x + SHADOW, y + h, w - SHADOW, s);
            j = j + 1;
        }
        g.setBlend(1);
    }

    // Unfocused windows sit lower: one faint, narrow band. Reserving the deep
    // shadow for the active window also keeps the cost roughly where it was.
    static void softShadow(int x, int y, int w, int h) {
        g.setRGB(0x00000000);
        g.setBlend(4);
        g.fillBlend(x + w, y + 5, 5, h);
        g.fillBlend(x + 5, y + h, w - 5, 5);
        g.setBlend(1);
    }

    static String winTitle(int i) {
        if (i == W_GALLERY) return "Widget Gallery";
        if (i == W_FILES) return "File Manager";
        if (i == W_SYSTEM) return "System Info";
        if (i == W_SOKOBAN) return "Sokoban";
        if (i == W_WASM) return "Sokoban (Rust)";
        // The filename cannot go in the title: only string literals can be
        // drawn, so it is shown inside the window instead. See JAVA-RULES.md.
        if (i == W_EDIT) return "Text Editor";
        return "About JVMOS";
    }

    // kind: 0 minimize, 1 maximize, 2 close.
    // Glyph only, no button box: the close glyph turns red so the destructive
    // action is the one thing that stands out.
    static void drawTitleBtn(int x, int y, int kind, boolean active) {
        int ink = C_DARK;
        if (active) ink = C_TEXTLT;
        if (kind == 2) {
            g.setRGB(C_RED);
            g.fillRect(x, y, TBTN, TBTN);
            ink = C_TEXTLT;
        }
        g.setRGB(ink);
        if (kind == 0) {
            g.fillRect(x + 4, y + 12, 10, 2);
        } else if (kind == 1) {
            g.fillRect(x + 4, y + 4, 10, 2);
            g.fillRect(x + 4, y + 12, 10, 2);
            g.fillRect(x + 4, y + 4, 2, 10);
            g.fillRect(x + 12, y + 4, 2, 10);
        } else {
            int k = 0;
            while (k < 8) {
                g.fillRect(x + 5 + k, y + 5 + k, 2, 2);
                g.fillRect(x + 12 - k, y + 5 + k, 2, 2);
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
        g.setRGB(C_FACE);
        g.fillRect(0, 0, SCR_W, MENU_H);
        g.setRGB(C_SHADOW);
        g.fillRect(0, MENU_H - 1, SCR_W, 1);
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
        softShadow(x, MENU_H, 180, h);
        g.setRGB(C_FACE);
        g.fillRect(x, MENU_H, 180, h);
        bevel(x, MENU_H, 180, h, 0);
        if (menuOpen == 0) {
            dropItem("New Text File", x, 0);
            dropItem("Open All Windows", x, 1);
            dropItem("Close Active Window", x, 2);
            dropItem("Shut Down", x, 3);
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

    // Light bar, flat items, a 2px accent rule over the active one instead of
    // a pushed-in bevel.
    static void drawTaskbar() {
        g.setRGB(C_FACE);
        g.fillRect(0, TASK_Y, SCR_W, TASK_H);
        g.setRGB(C_SHADOW);
        g.fillRect(0, TASK_Y, SCR_W, 1);

        int bx = 8;
        int i = 0;
        int open = 0;
        int tw = 128;
        while (i < WIN_COUNT) {
            if (wOpen[i] == 1) open = open + 1;
            i = i + 1;
        }
        // With every window open the tiles would reach the clock, so they get
        // narrower rather than running underneath it.
        if (open > 6) tw = 108;

        i = 0;
        while (i < WIN_COUNT) {
            if (wOpen[i] == 1) {
                boolean front = wMin[i] == 0 && zorder[WIN_COUNT - 1] == i;
                if (front) {
                    g.setRGB(C_SURF2);
                    g.fillRect(bx, TASK_Y + 1, tw, TASK_H - 1);
                    g.setRGB(C_SEL);
                    g.fillRect(bx, TASK_Y + 1, tw, 2);
                    g.setRGB(C_TEXT);
                } else {
                    g.setRGB(C_DARK);
                }
                g.drawString(winTitle(i), bx + 8, TASK_Y + (TASK_H - CH_H) / 2);
                bx = bx + tw + 6;
            }
            i = i + 1;
        }
        drawClock(SCR_W - 90, TASK_Y + (TASK_H - CH_H) / 2);
    }

    static void drawClock(int x, int y) {
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

    // ======================================================================
    // DESKTOP ICONS
    //
    // Windows no longer all open at boot, so the desktop needs somewhere to
    // start from. One column down the left: click selects, click again on the
    // same one opens it, and when nothing is open at all the arrow keys and
    // Enter work the same way.
    // ======================================================================
    static final int ICON_X = 20;
    static final int ICON_Y = MENU_H + 18;
    static final int ICON_W = 128;
    static final int ICON_H = 82;

    static void drawIcons() {
        int i = 0;
        while (i < WIN_COUNT) {
            drawIcon(i, ICON_X, ICON_Y + i * ICON_H);
            i = i + 1;
        }
    }

    static void drawIcon(int i, int x, int y) {
        String name = winTitle(i);
        int len = Native.sys(Native.SYS_STR_LEN, 0, 0, name, 0);
        int tx = x + (ICON_W - len * 8) / 2;

        // Tinted towards the title bar colour rather than darkened: the
        // desktop is already dark, so a shadow behind an icon reads as
        // nothing at all.
        if (iconSel == i) {
            g.setRGB(C_SEL);
            g.setBlend(2);
            g.fillBlend(x + 4, y + 2, ICON_W - 8, ICON_H - 10);
            g.setBlend(1);
        }
        iconGlyph(i, x + ICON_W / 2 - 16, y + 8);
        g.setRGB(C_TEXTLT);
        g.drawString(name, tx, y + 50);
    }

    // Each glyph is a few rectangles. There are no bitmaps in this system, and
    // at 32 pixels a shape only has to be told apart from six others.
    static void iconGlyph(int i, int x, int y) {
        if (i == W_FILES) {
            g.setRGB(C_AMBER);
            g.fillRect(x + 2, y + 4, 12, 3);        // the folder's tab
            g.fillRect(x, y + 7, 32, 22);
            g.setRGB(0x00A06B00);
            g.fillRect(x, y + 25, 32, 4);
            return;
        }
        if (i == W_EDIT) {
            g.setRGB(C_LIGHT);
            g.fillRect(x + 4, y + 2, 24, 28);       // a page with lines on it
            g.setRGB(C_DARK);
            g.fillRect(x + 8, y + 8, 16, 2);
            g.fillRect(x + 8, y + 14, 16, 2);
            g.fillRect(x + 8, y + 20, 10, 2);
            return;
        }
        if (i == W_SOKOBAN || i == W_WASM) {
            // a crate; the Rust guest gets its own colour so the two Sokobans
            // are not the same icon twice
            if (i == W_WASM) g.setRGB(0x00C06030); else g.setRGB(0x00C9860A);
            g.fillRect(x + 3, y + 4, 26, 26);
            g.setRGB(0x00000000);
            g.fillRect(x + 3, y + 4, 26, 2);
            g.fillRect(x + 3, y + 28, 26, 2);
            g.fillRect(x + 3, y + 4, 2, 26);
            g.fillRect(x + 27, y + 4, 2, 26);
            g.fillRect(x + 14, y + 6, 4, 22);
            return;
        }
        if (i == W_SYSTEM) {
            g.setRGB(C_SURF2);
            g.fillRect(x + 6, y + 6, 20, 20);       // a chip with its pins
            g.setRGB(C_TITLE_A);
            g.fillRect(x + 11, y + 11, 10, 10);
            g.setRGB(C_TEXTLT);
            g.fillRect(x + 2, y + 10, 4, 2);
            g.fillRect(x + 2, y + 18, 4, 2);
            g.fillRect(x + 26, y + 10, 4, 2);
            g.fillRect(x + 26, y + 18, 4, 2);
            return;
        }
        if (i == W_ABOUT) {
            g.setRGB(C_TITLE_A);
            g.fillRect(x + 6, y + 4, 20, 26);
            g.setRGB(C_TEXTLT);
            g.fillRect(x + 14, y + 9, 4, 4);        // an i
            g.fillRect(x + 14, y + 16, 4, 10);
            return;
        }
        // the gallery: a little window with a button in it
        g.setRGB(C_LIGHT);
        g.fillRect(x + 2, y + 4, 28, 26);
        g.setRGB(C_TITLE_A);
        g.fillRect(x + 2, y + 4, 28, 7);
        g.setRGB(C_SURF2);
        g.fillRect(x + 7, y + 16, 18, 8);
        g.setRGB(C_DARK);
        g.fillRect(x + 2, y + 4, 28, 1);
        g.fillRect(x + 2, y + 29, 28, 1);
    }

    static int iconAt(int px, int py) {
        int i = 0;
        while (i < WIN_COUNT) {
            if (hit(px, py, ICON_X, ICON_Y + i * ICON_H, ICON_W, ICON_H - 8)) return i;
            i = i + 1;
        }
        return -1;
    }

    // A click selects; a second click on the same icon soon after opens it,
    // which is the gesture a Windows user will try without being told.
    static void desktopClick() {
        int k = iconAt(mouseX, mouseY);
        int now = Native.sys(Native.SYS_GET_TICKS, 0, 0, 0, 0);
        if (k < 0) {
            iconSel = -1;
            return;
        }
        if (k == iconSel && now - iconClickTick < 400) {
            iconClickTick = 0;
            openIcon(k);
            return;
        }
        iconSel = k;
        iconClickTick = now;
        if (chkSound == 1) clickTone();
    }

    static void openIcon(int i) {
        if (i < 0 || i >= WIN_COUNT) return;
        wOpen[i] = 1;
        wMin[i] = 0;
        raiseWin(i);
        play(SND_CLICK);
    }

    static int openWindows() {
        int n = 0;
        int i = 0;
        while (i < WIN_COUNT) {
            if (wOpen[i] == 1) n = n + 1;
            i = i + 1;
        }
        return n;
    }

    // Only reached when every window is shut, so there is nothing else the
    // keyboard could belong to.
    static void desktopKey(int code) {
        if (code == K_DOWN || code == K_RIGHT) {
            if (iconSel < WIN_COUNT - 1) iconSel = iconSel + 1;
            return;
        }
        if (code == K_UP || code == K_LEFT) {
            if (iconSel > 0) iconSel = iconSel - 1;
            return;
        }
        if (code == K_ENTER || code == K_SPACE) openIcon(iconSel);
    }

    // ---- mouse pointer ---------------------------------------------------
    // The shape, exactly as it lands on screen: # is the black outline, . the
    // white interior, and the tip is the hotspot at (0,0).
    //
    //     0123456789AB C
    //  0  ##
    //  1  #.#
    //  2  #..#
    //  3  #...#
    //  4  #....#
    //  5  #.....#
    //  6  #......#
    //  7  #.......#
    //  8  #........#
    //  9  #.........#
    // 10  #..........#
    // 11  #...........#     widest point of the head
    // 12  #.....#######     the head's bottom edge, and the tail's top
    // 13  #....##..#        the tail leaves here, still touching the wing
    // 14  #...#  #..#
    // 15  #..#    #..#
    // 16  #.#      #..#
    // 17  ##       ####     the tail's cap, and the wing's tip on the spine
    //
    // The head widens by one column per row. It used to widen by one every
    // TWO rows, which is what made the arrow a sliver with no right-hand side:
    // at its widest the white interior was six pixels across where it should
    // be eleven.
    //
    // Every white pixel has shape on all four sides, which is what makes it
    // read as one closed object; tests/pointer_shape.js renders this method
    // out of the source and checks exactly that.
    static void drawPointer(int x, int y) {
        int i;

        g.setRGB(0x00000000);
        i = 0;
        while (i < 12) {                        // head, one column per row
            g.fillRect(x, y + i, i + 2, 1);
            i = i + 1;
        }
        g.fillRect(x, y + 12, 13, 1);           // bottom edge of the head
        i = 0;
        while (i < 5) {                         // wing, closing the head back
            g.fillRect(x, y + 13 + i, 6 - i, 1);        // onto the spine
            i = i + 1;
        }
        i = 0;
        while (i < 4) {                         // tail, angled down-right
            g.fillRect(x + 6 + i, y + 13 + i, 4, 1);
            i = i + 1;
        }
        g.fillRect(x + 9, y + 17, 4, 1);        // the tail's cap

        g.setRGB(C_TEXTLT);
        i = 1;
        while (i < 12) {                        // interior of the head
            g.fillRect(x + 1, y + i, i, 1);
            i = i + 1;
        }
        g.fillRect(x + 1, y + 12, 5, 1);        // interior alongside the notch
        i = 0;
        while (i < 4) {                         // interior of the wing
            g.fillRect(x + 1, y + 13 + i, 4 - i, 1);
            i = i + 1;
        }
        i = 0;
        while (i < 4) {                         // core of the tail, two pixels
            g.fillRect(x + 7 + i, y + 13 + i, 2, 1);    // wide so it reads as a
            i = i + 1;                                  // tail and not a line
        }
    }

    // ======================================================================
    // WIDGET PRIMITIVES
    // ======================================================================
    // A flat 1px outline. "raised" no longer means a 3D bevel, only how strong
    // the hairline is, so every existing call site keeps working unchanged.
    static void bevel(int x, int y, int w, int h, int raised) {
        int line = C_SHADOW;
        if (raised == 0) line = C_LINE2;
        g.setRGB(line);
        g.fillRect(x, y, w, 1);
        g.fillRect(x, y + h - 1, w, 1);
        g.fillRect(x, y, 1, h);
        g.fillRect(x + w - 1, y, 1, h);
    }

    static void panel(int x, int y, int w, int h, int bg, int raised) {
        g.setRGB(bg);
        g.fillRect(x, y, w, h);
        bevel(x, y, w, h, raised);
    }

    // nch = number of characters, used to centre without String.length()
    // Flat button: filled surface, hairline border, accent fill while pressed.
    // Nothing shifts by a pixel on press, that is a 3D-bevel idiom.
    static void button(int x, int y, int w, int h, String label, int nch, int down) {
        int fill = C_SURF2;
        int ink = C_TEXT;
        if (down == 1) {
            fill = C_SEL;
            ink = C_TEXTLT;
        }
        g.setRGB(fill);
        g.fillRect(x, y, w, h);
        bevel(x, y, w, h, 0);
        g.setRGB(ink);
        g.drawString(label, x + (w - nch * CH_W) / 2, y + (h - CH_H) / 2);
    }

    static void checkbox(int x, int y, String label, int on) {
        int box = C_FIELD;
        if (on == 1) box = C_SEL;
        g.setRGB(box);
        g.fillRect(x, y, 14, 14);
        bevel(x, y, 14, 14, 0);
        if (on == 1) {
            g.setRGB(C_TEXTLT);
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
        g.setRGB(C_FIELD);
        g.fillRect(x, y, 14, 14);
        bevel(x, y, 14, 14, 0);
        // knock the corners out so a 14px square reads as a circle
        g.setRGB(C_FACE);
        g.fillRect(x, y, 1, 1);
        g.fillRect(x + 13, y, 1, 1);
        g.fillRect(x, y + 13, 1, 1);
        g.fillRect(x + 13, y + 13, 1, 1);
        if (on == 1) {
            g.setRGB(C_SEL);
            g.fillRect(x + 5, y + 4, 4, 6);
            g.fillRect(x + 4, y + 5, 6, 4);
        }
        g.setRGB(C_TEXT);
        g.drawString(label, x + 22, y - 1);
    }

    static void progressBar(int x, int y, int w, int pct) {
        g.setRGB(C_SURF2);
        g.fillRect(x, y, w, 18);
        int fill = (w - 2) * pct / 100;
        if (fill < 0) fill = 0;
        if (fill > w - 2) fill = w - 2;
        g.setRGB(C_SEL);
        g.fillRect(x + 1, y + 1, fill, 16);
        bevel(x, y, w, 18, 0);
    }

    // Text input. The buffer is drawn character by character because
    // drawString only works on constant-pool literals.
    static void textField(int x, int y, int w) {
        g.setRGB(C_FIELD);
        g.fillRect(x, y, w, 22);
        if (fieldFocus == 1) g.setRGB(C_SEL); else g.setRGB(C_LINE2);
        g.fillRect(x, y, w, 1);
        g.fillRect(x, y + 21, w, 1);
        g.fillRect(x, y, 1, 22);
        g.fillRect(x + w - 1, y, 1, 22);
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

    // No sunken frame around the group: a caption over a short accent rule
    // separates things just as clearly and keeps the surface calm.
    static void groupBox(int x, int y, int w, int h, String title) {
        g.setRGB(C_TITLE_A);
        g.drawString(title, x + 2, y);
        g.fillRect(x + 2, y + 18, 28, 2);
        g.setRGB(C_SHADOW);
        g.fillRect(x + 32, y + 19, w - 34, 1);
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
        } else if (i == W_SOKOBAN) {
            drawSokoban(x, y, w, h);
        } else if (i == W_WASM) {
            drawWasm(x, y, w, h);
        } else if (i == W_EDIT) {
            drawEditor(x, y, w, h);
        } else {
            drawAbout(x, y, w, h);
        }
    }

    static void drawGallery(int x, int y, int w, int h) {
        boolean act = isActive(W_GALLERY);

        groupBox(x, y, 210, 96, "Options");
        checkbox(x + 12, y + 26, "Sound enabled", chkSound);
        checkbox(x + 12, y + 50, "Desktop grid", chkGrid);
        checkbox(x + 12, y + 74, "Show status", chkStatus);

        groupBox(x + 224, y, 210, 96, "Refresh rate");
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
            groupBox(x + 12, y + 228, 270, boxH, "Modules");
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

    // This is the real root directory of the FAT32 volume, not a fixed list.
    // Names arrive one byte at a time through SYS_FS_NAME because a syscall
    // returns an int and nothing else, the same way string literals already
    // cross in sys_str_byte.
    static void drawFiles(int x, int y, int w, int h) {
        int status = Native.sys(Native.SYS_FS_STATUS, 0, 0, 0, 0);

        panel(x, y, w, 22, C_FACE, 1);
        g.setRGB(C_TEXT);
        g.drawString("/ (root)", x + 8, y + 3);
        drawVolume(status, x, y + 3, w);

        if (status == FS_ABSENT) {
            g.setRGB(C_DARK);
            g.drawString("No disk answered at boot.", x + 8, y + 38);
            return;
        }
        if (status == FS_FOREIGN) {
            // Refusing to format is the whole point: a disk carrying somebody
            // else's partition is not ours to erase for the sake of a demo.
            g.setRGB(C_DARK);
            g.drawString("Unknown partition found.", x + 8, y + 38);
            g.drawString("Left untouched, not formatted.", x + 8, y + 58);
            return;
        }

        int count = Native.sys(Native.SYS_FS_COUNT, 0, 0, 0, 0);
        if (count == 0) {
            g.setRGB(C_DARK);
            g.drawString("The volume is empty.", x + 8, y + 38);
            return;
        }

        int rows = (h - 30 - 20) / 24;      // the bottom line carries the hint
        if (rows < 1) rows = 1;

        // Keep the selection inside the visible window, whatever the height.
        if (fileSel >= count) fileSel = count - 1;
        if (fileSel < 0) fileSel = 0;
        if (fileTop > fileSel) fileTop = fileSel;
        if (fileSel >= fileTop + rows) fileTop = fileSel - rows + 1;
        if (fileTop < 0) fileTop = 0;

        int r = 0;
        while (r < rows && fileTop + r < count) {
            fileRow(fileTop + r, x, y + 30 + r * 24, w);
            r = r + 1;
        }

        if (isActive(W_FILES)) {
            focusRing(x, y + 28 + (fileSel - fileTop) * 24, w, 24);
        }
        drawFilesHint(x + 8, y + h - 16);
    }

    static void drawFilesHint(int x, int y) {
        if (fileMsg == 1) {
            g.setRGB(C_DARK);
            g.drawString("No application is associated with that file.", x, y);
            return;
        }
        if (fileMsg == 2) {
            g.setRGB(C_DARK);
            g.drawString("This volume has no subdirectories yet.", x, y);
            return;
        }
        if (fileMsg == 3) {
            g.setRGB(C_RED);
            g.drawString("The file could not be read.", x, y);
            return;
        }
        if (fileMsg == 4) {
            g.setRGB(C_AMBER);
            g.drawString("Press Ctrl+D again to delete it.", x, y);
            return;
        }
        if (fileMsg == 5) {
            g.setRGB(C_GREEN);
            g.drawString("Deleted.", x, y);
            return;
        }
        if (fileMsg == 6) {
            g.setRGB(C_RED);
            g.drawString("The sandbox refused to start that program.", x, y);
            return;
        }
        g.setRGB(C_DARK);
        g.drawString("Enter opens   Ctrl+D deletes", x, y);
    }

    // Free space, from walking the FAT rather than from the cached FSInfo
    // count, which any driver is allowed to leave stale.
    static void drawVolume(int status, int x, int y, int w) {
        if (status != FS_MOUNTED && status != FS_FORMATTED) return;
        if (w < 300) return;                    // no room without colliding
        int free = Native.sys(Native.SYS_FS_FREE_KB, 0, 0, 0, 0);
        int total = Native.sys(Native.SYS_FS_TOTAL_KB, 0, 0, 0, 0);
        g.setRGB(C_DARK);
        int nx = g.drawInt(free, x + 100, y);
        g.drawString(" of ", nx, y);
        nx = g.drawInt(total, nx + 32, y);      // 4 characters at 8px
        g.drawString(" KB free", nx, y);
    }

    static void fileRow(int idx, int x, int y, int w) {
        int isDir = Native.sys(Native.SYS_FS_ISDIR, idx, 0, 0, 0);
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
        drawEntryName(idx, x + 26, y + 2);

        if (isDir != 1 && w > 260) {
            if (fileSel == idx) g.setRGB(C_TEXTLT); else g.setRGB(C_DARK);
            g.drawInt(Native.sys(Native.SYS_FS_SIZE, idx, 0, 0, 0), x + w - 96, y + 2);
        }
    }

    // A directory entry stores "README  TXT": eight characters of name, three
    // of extension, space padded, and no dot anywhere. The dot is put back
    // here, which is why this cannot just be a string copy.
    static void drawEntryName(int idx, int x, int y) {
        int i = 0;
        int nx = x;
        while (i < 8) {
            int c = Native.sys(Native.SYS_FS_NAME, idx, i, 0, 0);
            if (c == 32) break;
            nx = g.drawChar((char) c, nx, y);
            i = i + 1;
        }
        if (Native.sys(Native.SYS_FS_NAME, idx, 8, 0, 0) == 32) return;
        nx = g.drawChar('.', nx, y);
        i = 8;
        while (i < 11) {
            int c = Native.sys(Native.SYS_FS_NAME, idx, i, 0, 0);
            if (c == 32) break;
            nx = g.drawChar((char) c, nx, y);
            i = i + 1;
        }
    }

    // ======================================================================
    // TEXT EDITOR
    //
    // The text being edited lives in the kernel, not in this class. That is
    // forced, not chosen: this JIT can only draw string literals, because a
    // literal is a constant pool pointer with its length stored in front of
    // it, and a string built at runtime has no such header. So Java keeps the
    // caret and nothing else, and reads the text back a character at a time.
    // ======================================================================
    static int edCall(int op, int a, int b) {
        return Native.sys(Native.SYS_FS_EDIT, op, a, b, 0);
    }

    static int edLen() {
        return edCall(Native.ED_LENGTH, 0, 0);
    }

    static int edGet(int off) {
        return edCall(Native.ED_GET, off, 0);
    }

    static void drawEditor(int x, int y, int w, int h) {
        int rows = (h - 30 - 20) / CH_H;
        int cols = (w - 12) / 8;
        if (rows < 1) rows = 1;
        if (cols < 8) cols = 8;

        panel(x, y, w, 22, C_FACE, 1);
        g.setRGB(C_TEXT);
        g.drawString("File:", x + 6, y + 3);
        drawEditName(x + 52, y + 3, w - 58);

        edScroll(rows);
        drawEditText(x + 6, y + 28, rows, cols);
        drawEditHint(x + 6, y + h - 16);
    }

    static void drawEditName(int x, int y, int w) {
        int n = edCall(Native.ED_NAME_LEN, 0, 0);
        int i = 0;
        int nx = x;
        if (edNameFocus == 1) {
            g.setRGB(C_SURF2);
            g.fillRect(x - 3, y - 3, w - 60, 20);
            g.setRGB(C_SEL);
            g.fillRect(x - 3, y + 15, w - 60, 2);
        }
        g.setRGB(C_TEXT);
        while (i < n) {
            nx = g.drawChar((char) edCall(Native.ED_NAME_GET, i, 0), nx, y);
            i = i + 1;
        }
        if (edNameFocus == 1) {
            g.setRGB(C_SEL);
            g.fillRect(nx, y, 2, CH_H);
        } else if (n == 0) {
            g.setRGB(C_DARK);
            g.drawString("(unnamed)", x, y);
        }
        if (edCall(Native.ED_DIRTY, 0, 0) == 1) {
            g.setRGB(C_AMBER);
            g.drawString("edited", x + w - 56, y);
        }
    }

    // Jumps straight to the first visible line and stops when the window is
    // full, so drawing costs what is on screen and not what the file holds.
    static void drawEditText(int x, int y, int rows, int cols) {
        int len = edLen();
        int i = edLineStart(edTop);
        int row = 0;
        int col = 0;
        int c;

        if (i < 0) i = len;
        while (row < rows) {
            if (i == edCursor) {
                g.setRGB(C_SEL);
                g.fillRect(x + col * 8, y + row * CH_H, 2, CH_H);
            }
            if (i >= len) return;
            c = edGet(i);
            if (c == 10) {
                row = row + 1;
                col = 0;
            } else if (c == 9) {
                col = col + 4;                  // a tab is four columns here
            } else if (c >= 32 && c <= 126) {
                if (col < cols) {
                    g.setRGB(C_TEXT);
                    g.drawChar((char) c, x + col * 8, y + row * CH_H);
                }
                col = col + 1;
            }
            // A lone carriage return draws nothing: files here are written
            // CRLF, and printing it would put a box at the end of every line.
            i = i + 1;
        }
    }

    static void drawEditHint(int x, int y) {
        if (edStatus == 1) {
            g.setRGB(C_GREEN);
            g.drawString("Saved.", x, y);
            return;
        }
        if (edStatus == 2) {
            g.setRGB(C_RED);
            g.drawString("Not saved: the name is empty, or the disk is full.", x, y);
            return;
        }
        g.setRGB(C_DARK);
        g.drawString("Ctrl+S save   Ctrl+N new   Tab rename", x, y);
    }

    static void edScroll(int rows) {
        int line = edLineOf(edCursor);
        if (line < edTop) edTop = line;
        if (line >= edTop + rows) edTop = line - rows + 1;
        if (edTop < 0) edTop = 0;
    }

    // Line arithmetic is done in the kernel. Doing it here means one syscall
    // per byte of the whole file on every frame, just to find where the
    // caret's line starts; there it is one syscall and a loop over an array.
    static int edLineOf(int off) {
        return edCall(Native.ED_LINE_OF, off, 0);
    }

    // Offset where a line begins, or -1 if there is no such line.
    static int edLineStart(int line) {
        return edCall(Native.ED_LINE_START, line, 0);
    }

    static int edLineEnd(int start) {
        return edCall(Native.ED_LINE_END, start, 0);
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
        if (h > 170) {
            // Proves the freestanding C objects linked into the kernel and are
            // reachable from the syscall dispatcher: the groundwork for the
            // WASM sandbox, which will be written in C.
            g.setRGB(C_TITLE_A);
            g.drawString("C runtime", x, y + 148);
            if (Native.sys(Native.SYS_C_SELFTEST, 0, 0, 0, 0) == 0x5741534D) {
                g.setRGB(C_GREEN);
                g.drawString("linked, callable", x + 88, y + 148);
            } else {
                g.setRGB(C_RED);
                g.drawString("not available", x + 88, y + 148);
            }
        }

        if (h > 194) {
            // The card is probed the first time a sound is asked for, so this
            // reads "not probed yet" until the guest has made a noise.
            g.setRGB(C_TITLE_A);
            g.drawString("Audio", x, y + 172);
            if (Native.sys(Native.SYS_SB16_STATUS, 0, 0, 0, 0) == 1) {
                g.setRGB(C_GREEN);
                g.drawString("Sound Blaster 16", x + 88, y + 172);
            } else {
                g.setRGB(C_AMBER);
                g.drawString("PC speaker", x + 88, y + 172);
            }
        }

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
        } else if (i == W_EDIT) {
            editorClick(cx, cy, wh[i] - TITLE_H - BORDER - 21);
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
            play(SND_WIN);
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
        // A click selects the entry drawn on that row, which after scrolling
        // is fileTop + row rather than the row number itself.
        int count = Native.sys(Native.SYS_FS_COUNT, 0, 0, 0, 0);
        int row = 0;
        while (row < 16) {
            if (hit(mouseX, mouseY, x, y + 30 + row * 24, w, 20)) {
                if (fileTop + row < count) {
                    fileSel = fileTop + row;
                    filesTouched();
                    if (chkSound == 1) clickTone();
                }
            }
            row = row + 1;
        }
    }

    // Clicking in the text puts the caret where the click was, clamped to the
    // end of that line so a click past a short line lands somewhere sensible.
    static void editorClick(int x, int y, int h) {
        int rows = (h - 30 - 20) / CH_H;
        int row = (mouseY - (y + 28)) / CH_H;
        int col = (mouseX - (x + 6)) / 8;
        int start;
        int end;
        if (mouseY < y + 26) {
            edNameFocus = 1;
            return;
        }
        edNameFocus = 0;
        if (row < 0) row = 0;
        if (rows > 0 && row >= rows) row = rows - 1;
        if (col < 0) col = 0;
        start = edLineStart(edTop + row);
        if (start < 0) {
            edCursor = edLen();
            return;
        }
        end = edLineEnd(start);
        if (start + col > end) edCursor = end; else edCursor = start + col;
    }

    // x,y are the content origin; recompute the content box exactly as
    // drawWindow does so the hit box matches the drawn button.
    static void aboutClick(int x, int y, int i) {
        int cw = ww[i] - 2 * BORDER - 22;
        int ch = wh[i] - TITLE_H - BORDER - 21;
        if (hit(mouseX, mouseY, x + cw / 2 - 42, y + ch - 40, 84, 26)) {
            closeWin(W_ABOUT);
            clickTone();
        }
    }

    // The frame is produced by a WebAssembly guest, not by this class. The
    // guest is handed nothing but the size of its window: it draws in its own
    // coordinates from 0,0, the host translates, and every memory access it
    // makes is bounds checked against the one page it declared.
    // Translates desktop keys into the guest's own small code set, so neither
    // side depends on the other's keyboard details.
    static void wasmKey(int code) {
        int k = 0;
        if (code == K_UP) k = 1;
        else if (code == K_DOWN) k = 2;
        else if (code == K_LEFT) k = 3;
        else if (code == K_RIGHT) k = 4;
        else if (code == 114) k = 5;          // R
        else if (code == 110) k = 6;          // N
        else if (code == 112) k = 7;          // P
        else if (code == 105) k = 8;          // I, back to the artwork
        else if (code == 109) k = 10;         // M, music on or off
        else if (code >= 32) k = 9;           // anything else dismisses the title
        if (k != 0) Native.sys(Native.SYS_WASM_KEY, k, 0, 0, 0);
        paint();
    }

    // Everything below the caption is drawn by a Rust program compiled to
    // WebAssembly and executed by the interpreter in the kernel. The board,
    // the rules and the level maps all live inside the guest; this class only
    // lends it a rectangle and a font.
    static void drawWasm(int x, int y, int w, int h) {
        g.setRGB(C_TITLE_A);
        g.drawString("Rust guest, sandboxed WebAssembly", x, y);
        g.setRGB(C_DARK);
        g.drawString("Level", x, y + 22);
        g.drawString("Moves", x + 96, y + 22);
        g.drawString("Pushes", x + 184, y + 22);
        g.drawString("Done", x + 288, y + 22);
        g.setRGB(C_DARK);
        g.drawString("Arrows  R restart  N/P level  I artwork  M music", x, y + 40);

        // The guest keeps its own sound flag, so the desktop setting is pushed
        // in before every frame rather than being queried from inside.
        Native.sys(Native.SYS_WASM_SOUND, chkSound, 0, 0, 0);
        int r = Native.sys(Native.SYS_WASM_DRAW, x, y + 58, w, h - 58);
        if (r < 0) {
            g.setRGB(C_RED);
            g.drawString("guest refused to run, error", x, y + 80);
            g.drawInt(0 - r, x + 224, y + 80);
        }
    }

    // ======================================================================
    // SOKOBAN
    // Levels come from the Base-Z-47 Rust game, trimmed to their bounding box.
    // They are stored as string literals and read back through the string
    // syscalls, because Java here cannot index into a constant-pool literal.
    // Legend: '#' wall, '.' goal, '$' box, '@' player, ' ' floor.
    // ======================================================================
    static void sokoLoad(int n) {
        int i = 0;
        while (i < SOKO_CELLS) {
            sokoWall[i] = 0;
            sokoGoal[i] = 0;
            sokoBox[i] = 0;
            i = i + 1;
        }
        sokoW = 0;
        sokoH = 0;
        sokoTotal = 0;
        sokoPX = 0;
        sokoPY = 0;

        String s = levelData(n);
        int len = Native.sys(Native.SYS_STR_LEN, 0, 0, s, 0);
        int col = 0;
        int row = 0;
        i = 0;
        while (i < len) {
            int ch = Native.sys(Native.SYS_STR_BYTE, i, 0, s, 0);
            if (ch == 10) {
                row = row + 1;
                col = 0;
            } else {
                if (row < SOKO_ROWS && col < SOKO_STRIDE) {
                    sokoCell(row * SOKO_STRIDE + col, ch, col, row);
                }
                col = col + 1;
                if (col > sokoW) sokoW = col;
                if (row + 1 > sokoH) sokoH = row + 1;
            }
            i = i + 1;
        }

        sokoMoves = 0;
        sokoPushes = 0;
        sokoDone = 0;
        sokoCount();
    }

    static void sokoCell(int p, int ch, int col, int row) {
        if (ch == 35) {
            sokoWall[p] = 1;                    // #
        } else if (ch == 46) {
            sokoGoal[p] = 1;                    // .
        } else if (ch == 36) {
            sokoBox[p] = 1;                     // $
            sokoTotal = sokoTotal + 1;
        } else if (ch == 64) {
            sokoPX = col;                       // @
            sokoPY = row;
        }
    }

    static void sokoCount() {
        int on = 0;
        int i = 0;
        while (i < SOKO_CELLS) {
            if (sokoBox[i] == 1 && sokoGoal[i] == 1) on = on + 1;
            i = i + 1;
        }
        sokoOn = on;
        if (sokoTotal > 0 && on == sokoTotal) sokoDone = 1; else sokoDone = 0;
    }

    static void sokoMove(int dx, int dy) {
        if (sokoDone == 1) return;
        int nx = sokoPX + dx;
        int ny = sokoPY + dy;
        if (nx < 0 || ny < 0 || nx >= SOKO_STRIDE || ny >= SOKO_ROWS) return;

        int p = ny * SOKO_STRIDE + nx;
        if (sokoWall[p] == 1) {
            sokoBlip(160, 55);
            return;
        }

        if (sokoBox[p] == 1) {
            int bx = nx + dx;
            int by = ny + dy;
            if (bx < 0 || by < 0 || bx >= SOKO_STRIDE || by >= SOKO_ROWS) {
                sokoBlip(160, 55);
                return;
            }
            int q = by * SOKO_STRIDE + bx;
            if (sokoWall[q] == 1 || sokoBox[q] == 1) {
                sokoBlip(160, 55);
                return;
            }
            sokoBox[p] = 0;
            sokoBox[q] = 1;
            sokoPushes = sokoPushes + 1;
            sokoBlip(520, 34);
        } else {
            sokoBlip(1500, 9);
        }

        sokoPX = nx;
        sokoPY = ny;
        sokoMoves = sokoMoves + 1;
        sokoCount();
        if (sokoDone == 1) sokoFanfare();
    }

    // Kept taking a pitch so the call sites still read as sounds rather than
    // as ids; the mapping to a clip happens here.
    static void sokoBlip(int hz, int ms) {
        if (chkSound == 0) return;
        if (hz < 200) play(SND_BUMP);
        else if (hz < 700) play(SND_PUSH);
        else play(SND_STEP);
    }

    static void sokoFanfare() {
        if (chkSound == 0) return;
        play(SND_WIN);
    }

    static void sokoKey(int code) {
        if (code == K_UP) sokoMove(0, -1);
        else if (code == K_DOWN) sokoMove(0, 1);
        else if (code == K_LEFT) sokoMove(-1, 0);
        else if (code == K_RIGHT) sokoMove(1, 0);
        else if (code == 114) sokoLoad(sokoLevel);                  // R
        else if (code == 110) sokoGo(1);                            // N
        else if (code == 112) sokoGo(-1);                           // P
        paint();
    }

    static void sokoGo(int dir) {
        int n = sokoLevel + dir;
        if (n < 0) n = 0;
        if (n >= SOKO_LEVELS) n = SOKO_LEVELS - 1;
        sokoLevel = n;
        sokoLoad(n);
    }

    static void drawSokoban(int x, int y, int w, int h) {
        g.setRGB(C_TITLE_A);
        g.drawString("Level", x, y);
        g.setRGB(C_TEXT);
        int nx = g.drawInt(sokoLevel + 1, x + 48, y);
        g.drawString("/61", nx, y);

        g.setRGB(C_TITLE_A);
        g.drawString("Moves", x + 136, y);
        g.setRGB(C_TEXT);
        g.drawInt(sokoMoves, x + 192, y);

        g.setRGB(C_TITLE_A);
        g.drawString("Pushes", x + 256, y);
        g.setRGB(C_TEXT);
        g.drawInt(sokoPushes, x + 320, y);

        g.setRGB(C_TITLE_A);
        g.drawString("Done", x + 392, y);
        g.setRGB(C_TEXT);
        nx = g.drawInt(sokoOn, x + 440, y);
        g.drawString("/", nx, y);
        g.drawInt(sokoTotal, nx + 8, y);

        if (sokoDone == 1) {
            g.setRGB(C_GREEN);
            g.drawString("SOLVED!  press N for the next level", x, y + 18);
        } else {
            g.setRGB(C_DARK);
            g.drawString("Arrows move   R restart   N next   P previous", x, y + 18);
        }

        if (sokoW == 0 || sokoH == 0) return;
        int ts = (w - 4) / sokoW;
        int t2 = (h - 44) / sokoH;
        if (t2 < ts) ts = t2;
        if (ts > 26) ts = 26;
        if (ts < 5) ts = 5;
        drawSokoGrid(x + (w - sokoW * ts) / 2, y + 42, ts);
    }

    static void drawSokoGrid(int ox, int oy, int ts) {
        int r = 0;
        while (r < sokoH) {
            int c = 0;
            while (c < sokoW) {
                int p = r * SOKO_STRIDE + c;
                int px = ox + c * ts;
                int py = oy + r * ts;
                if (sokoWall[p] == 1) {
                    g.setRGB(0x00806040);
                    g.fillRect(px, py, ts, ts);
                    g.setRGB(0x00A88860);
                    g.fillRect(px, py, ts - 1, 1);
                    g.fillRect(px, py, 1, ts - 1);
                    g.setRGB(0x00503820);
                    g.fillRect(px, py + ts - 1, ts, 1);
                    g.fillRect(px + ts - 1, py, 1, ts);
                } else {
                    g.setRGB(0x00202830);
                    g.fillRect(px, py, ts, ts);
                    if (sokoGoal[p] == 1) {
                        g.setRGB(0x00C05050);
                        g.fillRect(px + ts / 2 - 2, py + ts / 2 - 2, 5, 5);
                    }
                }
                if (sokoBox[p] == 1) drawCrate(px, py, ts, sokoGoal[p]);
                c = c + 1;
            }
            r = r + 1;
        }
        drawHero(ox + sokoPX * ts, oy + sokoPY * ts, ts);
    }

    static void drawCrate(int px, int py, int ts, int onGoal) {
        int face = 0x00B08838;
        if (onGoal == 1) face = 0x0058B058;
        g.setRGB(face);
        g.fillRect(px + 1, py + 1, ts - 2, ts - 2);
        g.setRGB(0x00402808);
        g.fillRect(px + 1, py + 1, ts - 2, 1);
        g.fillRect(px + 1, py + ts - 2, ts - 2, 1);
        g.fillRect(px + 1, py + 1, 1, ts - 2);
        g.fillRect(px + ts - 2, py + 1, 1, ts - 2);
        if (ts >= 12) {
            g.fillRect(px + 3, py + ts / 2 - 1, ts - 6, 2);
            g.fillRect(px + ts / 2 - 1, py + 3, 2, ts - 6);
        }
    }

    static void drawHero(int px, int py, int ts) {
        int cx = px + ts / 2;
        g.setRGB(0x00FFD070);
        g.fillRect(cx - 3, py + 2, 6, 5);
        if (ts >= 10) {
            g.setRGB(0x002C6CC0);
            g.fillRect(cx - 4, py + 7, 8, ts - 10);
            g.setRGB(0x00203040);
            g.fillRect(cx - 4, py + ts - 3, 3, 2);
            g.fillRect(cx + 1, py + ts - 3, 3, 2);
        }
    }

    static String levelData(int n) {
        if (n == 0) return "    #####           \n    #   #           \n    #$  #           \n  ###  $##          \n  #  $ $ #          \n### # ## #    ######\n#   # ## ######  ..#\n# $  $           ..#\n##### ### #@###  ..#\n    #     ### ######\n    #######         ";
        if (n == 1) return "    #####             \n    #   #             \n    #$  #             \n  ###  $###           \n  #  $  $ #           \n### # ### #     ######\n#   # ### #######  ..#\n# $  $             ..#\n##### #### #@####  ..#\n    #      ###  ######\n    ########          ";
        if (n == 2) return "############  \n#..  #     ###\n#..  # $  $  #\n#..  #$####  #\n#..    @ ##  #\n#..  # #  $ ##\n###### ##$ $ #\n  # $  $ $ $ #\n  #    #     #\n  ############";
        if (n == 3) return "        ######## \n        #     @# \n        # $#$ ## \n        # $  $#  \n        ##$ $ #  \n######### $ # ###\n#....  ## $  $  #\n##...    $  $   #\n#....  ##########\n########         ";
        if (n == 4) return "              ########\n              #  ....#\n   ############  ....#\n   #    #  $ $   ....#\n   # $$$#$  $ #  ....#\n   #  $     $ #  ....#\n   # $$ #$ $ $########\n####  $ #     #       \n#   # #########       \n#    $  ##            \n# $$#$$ @#            \n#   #   ##            \n#########             ";
        if (n == 5) return "        #####    \n        #   #####\n        # #$##  #\n        #     $ #\n######### ###   #\n#....  ## $  $###\n#....    $ $$ ## \n#....  ##$  $ @# \n#########  $  ## \n        # $ $  # \n        ### ## # \n          #    # \n          ###### ";
        if (n == 6) return "######  ### \n#..  # ##@##\n#..  ###   #\n#..     $$ #\n#..  # # $ #\n#..### # $ #\n#### $ #$  #\n   #  $# $ #\n   # $  $  #\n   #  ##   #\n   #########";
        if (n == 7) return "       ##### \n #######   ##\n## # @## $$ #\n#    $      #\n#  $  ###   #\n### #####$###\n# $  ### ..# \n# $ $ $ ...# \n#    ###...# \n# $$ # #...# \n#  ### ##### \n####         ";
        if (n == 8) return "  ####          \n  #  ###########\n  #    $   $ $ #\n  # $# $ #  $  #\n  #  $ $  #    #\n### $# #  #### #\n#@#$ $ $  ##   #\n#    $ #$#   # #\n##  $    $ $ $ #\n ####  #########\n  ###  ###      \n  #      #      \n  #      #      \n  #......#      \n  #......#      \n  #......#      \n  ########      ";
        if (n == 9) return "          #######\n          #  ...#\n      #####  ...#\n      #      ...#\n      #  ##  ...#\n      ## ##  ...#\n     ### ########\n     # $$$ ##    \n #####  $ $ #####\n##   #$ $   #   #\n#@ $  $    $  $ #\n###### $$ $ #####\n     # $    #    \n     #### ###    \n        #  #     \n        #  #     \n        #  #     \n        ####     ";
        if (n == 10) return "              ####   \n         ######  #   \n         #       #   \n         #  #### ### \n ###  ##### ###    # \n##@####   $$$ #    # \n# $$   $$ $   #....##\n#  $$$#    $  #.....#\n# $   # $$ $$ #.....#\n###   #  $    #.....#\n  #   # $ $ $ #.....#\n  # ####### ###.....#\n  #   #  $ $  #.....#\n  ### # $$ $ $#######\n    # #  $      #    \n    # # $$$ $$$ #    \n    # #       # #    \n    # ######### #    \n    #           #    \n    #############    ";
        if (n == 11) return "          ####     \n     #### #  #     \n   ###  ###$ #     \n  ##   @  $  #     \n ##  $ $$## ##     \n #  #$##     #     \n # # $ $$ # ###    \n #   $ #  # $ #####\n####    #  $$ #   #\n#### ## $         #\n#.    ###  ########\n#.. ..# ####       \n#...#.#            \n#.....#            \n#######            ";
        if (n == 12) return "  #########  \n  # . # . #  \n  #. . . .#  \n  # . . . #  \n  #. . . .#  \n  # . . . #  \n  ###   ###  \n    #   #    \n###### ######\n#           #\n# $ $ $ $ $ #\n## $ $ $ $ ##\n #$ $ $ $ $# \n #   $@$   # \n #  #####  # \n ####   #### ";
        if (n == 13) return "    #########       \n  ###   ##  #####   \n###      #  #   ####\n#  $$ #$ #  #  ... #\n# #  $#@$## # #.#. #\n#  ## #$  #    ... #\n# $#    $ # # #.#. #\n#    ##  ##$ $ ... #\n# $ ##   #  #$#.#. #\n## $$  $   $  $... #\n #$  ######    ##  #\n #   #    ##########\n #####              ";
        if (n == 14) return "################ \n#              # \n# # ######     # \n# #  $ $ $ $#  # \n# #   $@$   ## ##\n# # #$ $ $###...#\n# #   $ $  ##...#\n# ###$$$ $ ##...#\n#     # ## ##...#\n#####   ## ##...#\n    #####     ###\n        #     #  \n        #######  ";
        if (n == 15) return "       ####      \n    ####  #      \n   ##  #  #      \n   #  $ $ #      \n ### #$   ####   \n #  $  ##$   #   \n #  # @ $ # $#   \n #  #      $ ####\n ## ####$##     #\n # $#.....# #   #\n #  $... . $# ###\n##  #.....#   #  \n#   ### #######  \n# $$  #  #       \n#  #     #       \n######   #       \n     #####       ";
        if (n == 16) return "#####         \n#   ##        \n#    #  ####  \n# $  ####  #  \n#  $$ $   $#  \n###@ #$    ## \n #  ##  $ $ ##\n # $  ## ## .#\n #  #$##$  #.#\n ###   $..##.#\n  #    #. ...#\n  # $$ #.....#\n  #  #########\n  #  #        \n  ####        ";
        if (n == 17) return "       #######    \n #######     #    \n #     # $@$ #    \n #$$ #   #########\n # ###......##   #\n #   $......## # #\n # ###......     #\n##   #### ### #$##\n#  #$   #  $  # # \n#  $ $$$  # $## # \n#   $ $ ###$$ # # \n#####     $   # # \n    ### ###   # # \n      #     #   # \n      ########  # \n             #### ";
        if (n == 18) return "      ############    \n      #  .  ##   #    \n      # #.     @ #    \n ###### ##...# ####   \n##  ##...####     ####\n# $ ##...    $ #  $  #\n#     .. ## # ## ##  #\n####$###$# $  #   # ##\n ###  #    ##$ $$ # # \n #   $$ # # $ # $## # \n #                  # \n #################  # \n                 #### ";
        if (n == 19) return "        ######              \n        #   @####           \n      ##### $   #           \n      #   ##    ####        \n      # $##  ##    #        \n      #   #  ##### #        \n      # #$$ $    # #        \n      #  $ $ ### # #        \n      # #   $  # # #        \n      # #  #$#   # #        \n     ## ####   # # #        \n     #  $  ##### # # ####   \n    ##    $     $  ###  ####\n#####  ### $ $# $ #   .....#\n#     ##      #  ##  #.....#\n# $$$$    ######$##   #.##.#\n##    ##              #....#\n ##  ###############   ....#\n  #  #             #####  ##\n  ####                 #### ";
        if (n == 20) return "       ############ \n       #..........# \n     ###.#.#.#.#..# \n     #   .........# \n     #@ $ $ $  . .# \n    ####### ####### \n ####   #    ##  #  \n##    $ #    # $ ## \n#  #$# ### ###$   ##\n# $  $ $   # $ $ $ #\n#  # $ ##       #$ #\n#   $####$####$##  #\n####  ##   #    #  #\n   #$ ##   # # $$  #\n   #   # $ #  $    #\n   ### # $$ #  $ ###\n     # #    # $ ##  \n     # ######## #   \n     #          #   \n     ############   ";
        if (n == 21) return "   ##########   \n   #..  #   #   \n   #..      #   \n   #..  #  #### \n  #######  #  ##\n  #            #\n  #  #  ##  #  #\n#### ##  #### ##\n#  $  ##### #  #\n# # $  $  # $  #\n# @$  $   #   ##\n#### ## ####### \n   #    #       \n   ######       ";
        if (n == 22) return "            ####      \n ############  #####  \n #    #  #  $  #   ## \n # $ $ $  $ # $ $   # \n ##$ $   # @# $   $ # \n###   ############ ## \n#  $ $#  #......# $#  \n# #   #  #......## #  \n#  ## ## # .....#  #  \n# #      $...... $ #  \n# # $ ## #......#  #  \n#  $ $#  #......# $#  \n# $   #  ##$#####  #  \n# $ $ #### $ $  $ $#  \n## #     $ $ $ $   ###\n #  ###### $    $    #\n #         # ####### #\n ####### #$          #\n       #   ###########\n       #####          ";
        if (n == 23) return "       #######           \n       #  #  ####        \n       # $#$ #  ##       \n########  #  #   ########\n#....  # $#$ #  $#  #   #\n#....# #     #$  #      #\n#..#.    $#  # $    #$  #\n#... @##  #$ #$  #  #   #\n#.... ## $#     $########\n########  #$$#$  #       \n       # $#  #  $#       \n       #  #  #   #       \n       ####  #####       \n          ####           ";
        if (n == 24) return "   ##########        \n   #........####     \n   #.#.#....#  #     \n   #........$$ #     \n   #     .###  ####  \n #########  $ #   #  \n #     $   $ $  $ #  \n #  #    #  $ $#  #  \n ## #####   #  #  #  \n # $     #   #### #  \n##  $#   # ##  #  #  \n#    ##$###    #  ## \n# $    $ #  #  #   # \n#####    # ## # ## ##\n    #$# #  $  $ $   #\n    #@#  $#$$$  #   #\n    ###  $      #####\n      ##  #  #  #    \n       ##########    ";
        if (n == 25) return "               ####    \n          ######  #####\n    #######       #   #\n    #      $ $ ## # # #\n    #  #### $  #     .#\n    #      $ # # ##.#.#\n    ##$####$ $ $ ##.#.#\n    #     #    ####.###\n    # $   ######  #.#.#\n######$$$##      @#.#.#\n#      #    #$#$###. .#\n# #### #$$$$$    # ...#\n# #    $     #   # ...#\n# #   ## ##     ###...#\n# ######$######  ######\n#        #    #  #     \n##########    ####     ";
        if (n == 26) return "#########      \n#       #      \n#       ####   \n## #### #  #   \n## #@##    #   \n# $$$ $  $$#   \n#  # ## $  #   \n#  # ##  $ ####\n####  $$$ $#  #\n #   ##   ....#\n # #   # #.. .#\n #   # # ##...#\n ##### $  #...#\n     ##   #####\n      #####    ";
        if (n == 27) return " #################     \n #...   #    #   ###   \n##.....  $## # # $ #   \n#......#  $  #  $  #   \n#......#  #  # # # ##  \n######### $  $ # #  ###\n  #     #$##$ ## ##   #\n ##   $    # $  $   # #\n #  ## ### #  #####$# #\n # $ $$     $   $     #\n # $    $##$ ######## #\n #######  @ ##      ###\n       ######          ";
        if (n == 28) return "     #######   \n     #@ #  #   \n     # $   #   \n    ### ## #   \n #### $  # ##  \n #       #  ## \n # $ $#### $ # \n # $$ #  #  $# \n #$  $   #$  # \n##  $$#   $$ ##\n# $$  #  #  $ #\n#     #### $  #\n#  #$##..##   #\n### .#....#####\n  # .......##  \n  #....   ..#  \n  ###########  ";
        if (n == 29) return "                #####   \n       ###### ###   ####\n   #####    ### $ $  $ #\n####  ## #$ $    $ #   #\n#....   $$ $ $  $   #$##\n#.. # ## #   ###$## #  #\n#....    # ###    #    #\n#....    # ##  $  ###$ #\n#..######  $  #  #### ##\n####    #   ###    @  # \n        ############### ";
        if (n == 30) return " #####        \n #   #######  \n # $ ###   #  \n # $    $$ #  \n ## ####   #  \n### #  # ###  \n#   #  #@##   \n# $$    $ #   \n#   # # $ ####\n##### #   #  #\n #   $####   #\n #  $     $  #\n ##   ##### ##\n ##########  #\n##....# $  $ #\n#.....# $$#  #\n#.. ..# $  $ #\n#.....$   #  #\n##  ##########\n ####         ";
        if (n == 31) return " #######       \n #  #  #####   \n##  #  #...### \n#  $#  #...  # \n# $ #$$ ...  # \n#  $#  #... .# \n#   # $########\n##$       $ $ #\n##  #  $$ #   #\n ######  ##$$@#\n      #      ##\n      ######## ";
        if (n == 32) return "  ####            \n  #  #########    \n ##  ## @#   #    \n #  $# $ $   #### \n #$  $  # $ $#  ##\n##  $## #$ $     #\n#  #  # #   $$$  #\n# $    $  $## ####\n# $ $ #$#  #  #   \n##  ###  ###$ #   \n #  #....     #   \n ####......####   \n   #....####      \n   #...##         \n   #...#          \n   #####          ";
        if (n == 33) return "      ####   \n  #####  #   \n ##     $#   \n## $  ## ### \n#@$ $ # $  # \n#### ##   $# \n #....#$ $ # \n #....#   $# \n #....  $$ ##\n #... # $   #\n ######$ $  #\n      #   ###\n      #$ ### \n      #  #   \n      ####   ";
        if (n == 34) return "############\n##     ##  #\n##   $   $ #\n#### ## $$ #\n#   $ #    #\n# $$$ # ####\n#   # # $ ##\n#  #  #  $ #\n# $# $#    #\n#   ..# ####\n####.. $ #@#\n#.....# $# #\n##....#  $ #\n###..##    #\n############";
        if (n == 35) return "############  ######\n#   #    #@####....#\n#   $$#       .....#\n#   # ###   ## ....#\n## ## ###  #   ....#\n # $ $     # ## ####\n #  $ $##  #       #\n#### #  #### ## ## #\n#  # #$   ## ##    #\n# $  $  # ## #######\n# # $ $    # #      \n#  $ ## ## # #      \n# $$     $$  #      \n## ## ### $  #      \n #    # #    #      \n ###### ######      ";
        if (n == 36) return "     ####         \n   ###  ##        \n####  $  #        \n#   $ $  ####     \n# $   # $   # ####\n#  #  #   $ # #..#\n##$#$ ####$####..#\n #   ##### ## ...#\n #$# ##@## ##  ..#\n # #    $     ...#\n #   #### ###  ..#\n ### ## #  ## ...#\n  ##$ ####$ ###..#\n  #   ##    # #..#\n ## $$##  $ # ####\n #     $$$$ #     \n # $ ###    #     \n #   # ######     \n #####            ";
        if (n == 37) return "###########          \n#......   #########  \n#......   #  ##   #  \n#..### $    $     #  \n#... $ $ #  ###   #  \n#...#$#####    #  #  \n###    #   #$  # $###\n  #  $$ $ $  $##  $ #\n  #  $   #$#  ##    #\n  ### ## #  $ #######\n   #  $ $ ## ##      \n   #    $  $  #      \n   ##   # #   #      \n    #####@#####      \n        ###          ";
        if (n == 38) return " #########    \n #....   ##   \n #.#.#  $ ##  \n##....# # @## \n# ....#  #  ##\n#     #$ ##$ #\n## ###  $    #\n #$  $ $ $#  #\n # #  $ $ ## #\n #  ###  ##  #\n #    ## ## ##\n #  $ #  $  # \n ###$ $   ### \n   #  #####   \n   ####       ";
        if (n == 39) return "              ###      \n             ##.###    \n             #....#    \n #############....#    \n##   ##     ##....#####\n#  $$##  $ @##....    #\n#      $$ $#  ....#   #\n#  $ ## $$ # #....#  ##\n#  $ ## $  # ## ###  # \n## ##### ###         # \n##   $  $ ##### ###  # \n# $###  # ##### # #### \n#   $   #       #      \n#  $ #$ $ $###  #      \n# $$$# $   # ####      \n#    #  $$ #           \n######   ###           \n     #####             ";
        if (n == 40) return "      #### \n####### @# \n#     $  # \n#   $## $# \n##$#...# # \n # $...  # \n # #. .# ##\n #   # #$ #\n #$  $    #\n #  #######\n ####      ";
        if (n == 41) return "           #####    \n          ##   ##   \n         ##     #   \n        ##  $$  #   \n       ## $$  $ #   \n       # $    $ #   \n####   #   $$ ##### \n#  ######## ##    # \n#..           $$$@# \n#.# ####### ##   ## \n#.# #######. #$ $###\n#........... #   $ #\n##############  $  #\n             ##  ###\n              ####  ";
        if (n == 42) return " ########    \n #@##   #### \n # $   $   # \n #  $ $ $$$# \n # $$# #   # \n##$    $   # \n#  $  $$$$$##\n# $#### #   #\n#  $....#   #\n# ##....#$$ #\n# ##....   ##\n#   ....#  # \n## #....#$$# \n # #....#  # \n #         # \n #### ##$### \n    #    #   \n    ######   ";
        if (n == 43) return "    ############ \n    #          ##\n    #  # #$$ $  #\n    #$ #$#  ## @#\n   ## ## # $ # ##\n   #   $ #$  # # \n   #   # $   # # \n   ## $ $   ## # \n   #  #  ##  $ # \n   #    ## $$# # \n######$$   #   # \n#....#  ######## \n#.#... ##        \n#....   #        \n#....   #        \n#########        ";
        if (n == 44) return "      ######             \n   #####   #             \n   #   # # #####         \n   # $ #  $    ######    \n  ##$  ### ##       #    \n###  $$ $ $ #  ##   #####\n#       $   ###### ##   #\n#  ######## #@   # #  # #\n## ###      #### #$# #  #\n # ### #### ##.. #   $ ##\n #  $  $  #$##.. #$##  ##\n #  # # #     ..## ## $ #\n ####   # ## #..#    $  #\n    #####    #..# # #  ##\n        ######..#   # ## \n             #..#####  # \n             #..       # \n             ##  ###  ## \n              #########  ";
        if (n == 45) return "        #######    \n    #####  #  #### \n    #   #   $    # \n #### #$$ ## ##  # \n##      # #  ## ###\n#  ### $#$  $  $  #\n#...    # ##  #   #\n#...#    @ # ### ##\n#...#  ###  $  $  #\n######## ##   #   #\n          #########";
        if (n == 46) return "    #########  ####   \n    #   ##  ####  #   \n    #   $   #  $  #   \n    #  # ## #     ####\n    ## $   $ $$# #   #\n    ####  #  # $ $   #\n#####  ####    ###...#\n#   #$ #  # ####.....#\n#      #  # # ##.....#\n###### #  #$   ###...#\n   #   ## # $#   #...#\n  ##       $  $# #####\n ## $$$##  # $   #    \n #   #  # ###  ###    \n #   $  #$ @####      \n #####  #   #         \n     ########         ";
        if (n == 47) return " #####             \n #   #             \n # # ######        \n #      $@######   \n # $ ##$ ###   #   \n # #### $    $ #   \n # ##### #  #$ ####\n##  #### ##$      #\n#  $#  $  # ## ## #\n#         # #...# #\n######  ###  ...  #\n     #### # #...# #\n          # ### # #\n          #       #\n          #########";
        if (n == 48) return "       ####     \n       #  ##    \n       #   ##   \n       # $$ ##  \n     ###$  $ ## \n  ####    $   # \n###  # #####  # \n#    # #....$ # \n# #   $ ....# # \n#  $ # #. ..# # \n###  #### ### # \n  #### @$  ##$##\n     ### $     #\n       #  ##   #\n       #########";
        if (n == 49) return "      ############ \n     ##..    #   # \n    ##..  $    $ # \n   ##.. .# # #$ ## \n   #.. .# # # $  # \n####...#  #    # # \n#  ## #          # \n# @$ $ ###  # # ## \n# $   $   # #   #  \n###$$   # # # # #  \n  #   $   # # #####\n  # $# #####      #\n  #$   #   #   #  #\n  #  ###   ##     #\n  #  #      #    ##\n  ####      ###### ";
        if (n == 50) return "     #############   \n     #    ###    #   \n     #     $ $  #### \n   #### #   $ $    # \n  ## $  #$#### $ $ # \n###   # #   ###  $ # \n# $  $  #  $  # #### \n# ##$#### #$#  $  ###\n# ##  ### # # #  $  #\n#    @$   $   # $ # #\n#####  #  ##  # $#  #\n  #... #####$  #  # #\n  #.......# $$ #$ # #\n  #.......#         #\n  #.......#######  ##\n  #########     #### ";
        if (n == 51) return "##### ####      \n#...# #  ####   \n#...###  $  #   \n#....## $  $### \n##....##   $  # \n###... ## $ $ # \n# ##    #  $  # \n#  ## # ### ####\n# $ # #$  $    #\n#  $ @ $    $  #\n#   # $ $$ $ ###\n#  ######  ###  \n# ##    ####    \n###             ";
        if (n == 52) return " ####                \n##  #####            \n#       # #####      \n# $###  ###   #      \n#..#  $# #  # #      \n#..#      $$# ###    \n#. # #  #$ $    #####\n#..#  ##     ##$#   #\n#. $  $ # ##  $     #\n#..##  $   #   ######\n#. ##$##   #####     \n#..  $ #####         \n#  # @ #             \n########             ";
        if (n == 53) return "   ##########\n   #  ###   #\n   # $   $  #\n   #  ####$##\n   ## #  #  #\n  ##  #.    #\n  #  ##..#  #\n  # @ #. # ##\n  # #$#..#$ #\n  # $ #..#  #\n  # # #  #  #\n  # $ #..#$##\n  #    . #  #\n ###  #  #  #\n##    ####  #\n#  #######$##\n# $      $  #\n#  ##   #   #\n#############";
        if (n == 54) return " ##################### \n #   ##  #   #   #   # \n # $     $   $   $   ##\n##### #  #   ### ##$###\n#   # ##$######   #   #\n# $   # ......#   # $ #\n## #  # ......#####   #\n## #########..#   # ###\n#          #..# $   #  \n# ## ### ###..## #  ###\n# #   #   ##..## ###  #\n#   @      $..#       #\n# #   #   ##  #   ##  #\n##### ############## ##\n#          #   #    $ #\n# $  # $ $ $   # #    #\n# #$## $#  ## ##    # #\n#  $ $$ #### $  $ # # #\n#          #   #      #\n#######################";
        if (n == 55) return " #####################\n##                   #\n#    $ #      ## #   #\n#  ###### ###  #$## ##\n##$#   ##$#....   # # \n#  #    $ #....## # # \n# $ # # # #....##   # \n# $ #$$   #....##$# # \n# # $@$##$#....##   # \n#   $$$   #....#    # \n#  $#   # ###### $### \n##  # ###$$  $   $ #  \n##     # $  $ ##   #  \n #####   #   #######  \n     #########        ";
        if (n == 56) return "##########    \n#        #### \n# ###### #  ##\n# # $ $ $  $ #\n#       #$   #\n###$  $$#  ###\n  #  ## # $## \n  ##$#   $ @# \n   #  $ $ ### \n   # #   $  # \n   # ##   # # \n  ##  ##### # \n  #         # \n  #.......### \n  #.......#   \n  #########   ";
        if (n == 57) return "         ####     \n #########  ##    \n##  $      $ #####\n#   ## ##   ##...#\n# #$$ $ $$#$##...#\n# #    @  #   ...#\n#  $# ###$$   ...#\n# $  $$  $ ##....#\n###$       #######\n  #  #######      \n  ####            ";
        if (n == 58) return "              ######       \n          #####    #       \n          #  ## #  #####   \n          #    .#..#   #   \n ##### #### $#.#...    #   \n #   ###  ## # ....## ##   \n # $      ## #..#..## #    \n###### #   # # .##### #    \n#   # $#$# # #..##### #    \n# $  $     # # .    # #    \n## ##  $ ### #  ##  # #    \n #  $  $ ### ##### ## #    \n ###$###$###  #### ## #    \n#### #         ###  # #    \n#  $ #  $####  ###$$#@#####\n#      $ # #  ####  #$#   #\n#### #  $# #              #\n   #  $  # ##  ##  ########\n   ##  ###  ########       \n    ####                   ";
        if (n == 59) return "         ####                \n         #  #                \n         #  ########         \n   #######  #      #         \n   #   # # # # #   ##        \n   # $     $  ##  $ #        \n  ### $# #  # #     #########\n  #  $  #  $# # $$ #   # #  #\n ## #   #     ###    $ # #  #\n #  #$   # ###  #  # $$# #  #\n #    $## $  #   ## $  # # ##\n####$ $ #    ##  #   $    ..#\n#  #    ### # $ $ ###  ###. #\n#     ##  $$ @  $     ##....#\n#  ##  ##   $  #$#  ##.... .#\n## #  $  # # $##  ##.... .###\n## ##  $  # $ #  #.... .###  \n#    $ ####   # .... .###    \n#   #  #  #  #  .. .###      \n########  ###########        ";
        if (n == 60) return "        #####             \n        #   ####          \n        # $    ####  #### \n        #   # $#  ####  # \n########### #   $   #   # \n#..     # $  #### #  #  # \n#..$  #   $  #  $ # $ .## \n#. # # $ $ ##  ##    #.#  \n#..#$ @ #   ##    $$ #.#  \n#..# $ $  $ $ ##   ## .#  \n#. $$ # ##   $ #$# $ #.#  \n#..#      ##   #     #.#  \n#..#######  ### ######.## \n# $$                   .##\n#  ##################  ..#\n####                ######";
        return "#####\n#@$.#\n#####";
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
        play(SND_DENY);
        Native.sys(Native.SYS_SLEEP, 500, 0, 0, 0);
        Native.sys(Native.SYS_EXIT, 0, 0, 0, 0);
    }
}
