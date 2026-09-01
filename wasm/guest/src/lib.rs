//! Sokoban as a sandboxed WebAssembly guest for JVMOS.
//!
//! Ported from the Base-Z-47 Rust game. The rules and the level maps come
//! across unchanged; the presentation does not, because the original renders
//! ANSI escapes to a terminal through crossterm, which does not exist here.
//! Instead the guest asks the host for the size of its window, draws in its own
//! coordinate space starting at 0,0, and lets the host translate and clip.
//!
//! Everything is no_std: there is no allocator, no operating system and no
//! libc underneath. State lives in statics, which land in the guest's own
//! linear memory, so the game keeps its position between frames while being
//! unable to address a single byte outside that memory.

#![no_std]

use core::panic::PanicInfo;

/// A panic must not spin: an infinite loop inside the guest would hang the
/// whole machine, since there is no scheduler to preempt it. Trapping hands
/// control back to the interpreter, which reports the failure to the desktop.
#[panic_handler]
fn panic(_: &PanicInfo) -> ! {
    core::arch::wasm32::unreachable()
}

extern "C" {
    fn set_color(rgb: i32);
    fn fill_rect(x: i32, y: i32, w: i32, h: i32);
    fn width() -> i32;
    fn height() -> i32;
    fn draw_int(value: i32, x: i32, y: i32);
    /// Draws one of the pictures the host carries, by index.
    fn draw_image(index: i32, x: i32, y: i32, scale: i32);
    /// Returns a pending key, or 0. Codes are assigned by the host below.
    fn key() -> i32;
    /// PC speaker: a tone of the given pitch for the given time, then silence.
    fn beep(hz: i32, ms: i32);
}

// Key codes agreed with the host, deliberately small and explicit rather than
// raw scancodes, so neither side depends on the other's keyboard details.
const K_NONE: i32 = 0;
const K_UP: i32 = 1;
const K_DOWN: i32 = 2;
const K_LEFT: i32 = 3;
const K_RIGHT: i32 = 4;
const K_RESTART: i32 = 5;
const K_NEXT: i32 = 6;
const K_PREV: i32 = 7;
const K_GALLERY: i32 = 8;

const IMAGE_COUNT: i32 = 5;
const IMAGE_W: i32 = 200;
const IMAGE_H: i32 = 125;

// 0 = artwork, 1 = playing. The game opens on the title picture, as the
// original does with intro_image().
const SCREEN_ART: i32 = 0;
const SCREEN_GAME: i32 = 1;

const STRIDE: usize = 32;
const ROWS: usize = 24;
const CELLS: usize = STRIDE * ROWS;
const LEVEL_COUNT: i32 = 61;

const LEVELS: &str = include_str!("levels.txt");

// The palette of the original terminal version, copied from its view.rs, so
// the board looks the way it did there rather than like the surrounding
// desktop. In the terminal each cell was five characters wide and two tall,
// filled with a background colour and decorated with box-drawing glyphs; here
// the same shapes are rectangles, keeping the 5:4 cell proportion.
const C_FLOOR: i32 = 0x00D8_FDB8; // 216,253,184 light green
const C_WALL: i32 = 0x0055_55FF; // 85,85,255 blue
const C_GOAL: i32 = 0x00C2_1460; // 194,20,96 pink outline on the floor
const C_CRATE: i32 = 0x00FF_BE00; // 255,190,0 amber
const C_CRATE_OK: i32 = 0x00FF_7D00; // 255,125,0 orange once on a goal
const C_CRATE_EDGE: i32 = 0x0034_7B98; // 52,123,152 blue frame of a crate
const C_HERO_BODY: i32 = 0x00FF_4100; // 255,65,0 orange red
const C_HERO_HEAD: i32 = 0x00D8_FDB8; // the hero is drawn in the floor colour
const C_TEXT: i32 = 0x0023_2629;
const C_ACCENT: i32 = 0x0014_606B;
const C_DONE: i32 = 0x002E_7D4F;

static mut WALL: [u8; CELLS] = [0; CELLS];
static mut GOAL: [u8; CELLS] = [0; CELLS];
static mut CRATE: [u8; CELLS] = [0; CELLS];

static mut LEVEL: i32 = 0;
static mut COLS: i32 = 0;
static mut ROWS_USED: i32 = 0;
static mut HERO_X: i32 = 0;
static mut HERO_Y: i32 = 0;
static mut MOVES: i32 = 0;
static mut PUSHES: i32 = 0;
static mut TOTAL: i32 = 0;
static mut ON_GOAL: i32 = 0;
static mut SOLVED: i32 = 0;
static mut STARTED: i32 = 0;
static mut SCREEN: i32 = SCREEN_ART;
static mut SOUND: i32 = 1;
static mut IMAGE: i32 = 0;

/// Byte range of level `n` inside LEVELS, which stores maps separated by ";".
fn level_bounds(n: i32) -> (usize, usize) {
    let bytes = LEVELS.as_bytes();
    let mut index = 0i32;
    let mut start = 0usize;
    let mut i = 0usize;
    while i < bytes.len() {
        if bytes[i] == b';' {
            if index == n {
                // drop the newline that precedes the separator
                let mut end = i;
                if end > start && bytes[end - 1] == b'\n' {
                    end -= 1;
                }
                return (start, end);
            }
            index += 1;
            start = i + 1;
            if start < bytes.len() && bytes[start] == b'\n' {
                start += 1;
            }
        }
        i += 1;
    }
    if index == n {
        (start, bytes.len())
    } else {
        (0, 0)
    }
}

fn load(n: i32) {
    unsafe {
        let mut i = 0usize;
        while i < CELLS {
            WALL[i] = 0;
            GOAL[i] = 0;
            CRATE[i] = 0;
            i += 1;
        }
        COLS = 0;
        ROWS_USED = 0;
        TOTAL = 0;
        HERO_X = 0;
        HERO_Y = 0;

        let (start, end) = level_bounds(n);
        let bytes = LEVELS.as_bytes();
        let mut col = 0i32;
        let mut row = 0i32;
        let mut p = start;
        while p < end {
            let ch = bytes[p];
            if ch == b'\n' {
                row += 1;
                col = 0;
            } else {
                if (row as usize) < ROWS && (col as usize) < STRIDE {
                    let at = row as usize * STRIDE + col as usize;
                    if ch == b'#' {
                        WALL[at] = 1;
                    } else if ch == b'.' {
                        GOAL[at] = 1;
                    } else if ch == b'$' {
                        CRATE[at] = 1;
                        TOTAL += 1;
                    } else if ch == b'@' {
                        HERO_X = col;
                        HERO_Y = row;
                    }
                }
                col += 1;
                if col > COLS {
                    COLS = col;
                }
                if row + 1 > ROWS_USED {
                    ROWS_USED = row + 1;
                }
            }
            p += 1;
        }

        MOVES = 0;
        PUSHES = 0;
        SOLVED = 0;
        count_goals();
    }
}

unsafe fn count_goals() {
    let mut on = 0i32;
    let mut i = 0usize;
    while i < CELLS {
        if CRATE[i] == 1 && GOAL[i] == 1 {
            on += 1;
        }
        i += 1;
    }
    ON_GOAL = on;
    SOLVED = if TOTAL > 0 && on == TOTAL { 1 } else { 0 };
}

unsafe fn tone(hz: i32, ms: i32) {
    if SOUND == 1 {
        beep(hz, ms);
    }
}

unsafe fn step(dx: i32, dy: i32) {
    if SOLVED == 1 {
        return;
    }
    let nx = HERO_X + dx;
    let ny = HERO_Y + dy;
    if nx < 0 || ny < 0 || nx >= STRIDE as i32 || ny >= ROWS as i32 {
        return;
    }
    let at = ny as usize * STRIDE + nx as usize;
    if WALL[at] == 1 {
        tone(150, 55);
        return;
    }

    if CRATE[at] == 1 {
        let bx = nx + dx;
        let by = ny + dy;
        if bx < 0 || by < 0 || bx >= STRIDE as i32 || by >= ROWS as i32 {
            tone(150, 55);
            return;
        }
        let behind = by as usize * STRIDE + bx as usize;
        if WALL[behind] == 1 || CRATE[behind] == 1 {
            tone(150, 55);
            return;
        }
        CRATE[at] = 0;
        CRATE[behind] = 1;
        PUSHES += 1;
        tone(520, 34);
    } else {
        tone(1500, 9);
    }

    HERO_X = nx;
    HERO_Y = ny;
    MOVES += 1;
    count_goals();
    if SOLVED == 1 {
        tone(784, 110);
        tone(988, 110);
        tone(1319, 300);
    }
}

unsafe fn go(delta: i32) {
    let mut n = LEVEL + delta;
    if n < 0 {
        n = 0;
    }
    if n >= LEVEL_COUNT {
        n = LEVEL_COUNT - 1;
    }
    LEVEL = n;
    load(n);
}

unsafe fn handle_art(k: i32) {
    if k == K_NEXT || k == K_RIGHT {
        IMAGE += 1;
        if IMAGE >= IMAGE_COUNT {
            IMAGE = 0;
        }
    } else if k == K_PREV || k == K_LEFT {
        IMAGE -= 1;
        if IMAGE < 0 {
            IMAGE = IMAGE_COUNT - 1;
        }
    } else {
        SCREEN = SCREEN_GAME;
    }
}

unsafe fn handle(k: i32) {
    if k == K_GALLERY {
        SCREEN = SCREEN_ART;
        return;
    }
    if k == K_UP {
        step(0, -1);
    } else if k == K_DOWN {
        step(0, 1);
    } else if k == K_LEFT {
        step(-1, 0);
    } else if k == K_RIGHT {
        step(1, 0);
    } else if k == K_RESTART {
        load(LEVEL);
    } else if k == K_NEXT {
        go(1);
    } else if k == K_PREV {
        go(-1);
    }
}

/// The box-drawing frame the terminal version puts inside a cell: it spans the
/// middle three of the five character columns, both rows tall.
unsafe fn draw_frame(px: i32, py: i32, cw: i32, ch: i32, colour: i32, thick: i32) {
    let inset = cw / 5;
    let x0 = px + inset;
    let w = cw - inset * 2;
    let y0 = py + thick;
    let h = ch - thick * 2;
    if w <= thick * 2 || h <= thick * 2 {
        return;
    }
    set_color(colour);
    fill_rect(x0, y0, w, thick);
    fill_rect(x0, y0 + h - thick, w, thick);
    fill_rect(x0, y0, thick, h);
    fill_rect(x0 + w - thick, y0, thick, h);
}

unsafe fn draw_crate(px: i32, py: i32, cw: i32, ch: i32, on_goal: bool) {
    set_color(if on_goal { C_CRATE_OK } else { C_CRATE });
    fill_rect(px, py, cw, ch);
    // a double line in the original, so the frame is drawn twice
    let t = if cw >= 20 { 2 } else { 1 };
    draw_frame(px, py, cw, ch, C_CRATE_EDGE, t);
}

/// The hero is a framed box with a diagonal stroke on either side.
unsafe fn draw_hero(px: i32, py: i32, cw: i32, ch: i32) {
    set_color(C_HERO_BODY);
    fill_rect(px, py, cw, ch);
    let t = if cw >= 20 { 2 } else { 1 };
    draw_frame(px, py, cw, ch, C_HERO_HEAD, t);

    let inset = cw / 5;
    if inset >= 3 {
        set_color(C_HERO_HEAD);
        let steps = ch - t * 2;
        let mut i = 0;
        while i < steps {
            let dx = i * inset / steps;
            fill_rect(px + inset - 1 - dx, py + t + i, t, 1);
            fill_rect(px + cw - inset + dx, py + t + i, t, 1);
            i += 1;
        }
    }
}

unsafe fn draw_board(ox: i32, oy: i32, cw: i32, ch: i32) {
    // The floor goes down as one rectangle rather than one per cell. Every
    // rectangle is a call across the sandbox boundary into the interpreter, so
    // the cheapest drawing is the drawing that never happens.
    set_color(C_FLOOR);
    fill_rect(ox, oy, COLS * cw, ROWS_USED * ch);

    let mut r = 0i32;
    while r < ROWS_USED {
        let mut c = 0i32;
        while c < COLS {
            let at = r as usize * STRIDE + c as usize;
            let px = ox + c * cw;
            let py = oy + r * ch;
            if WALL[at] == 1 {
                set_color(C_WALL);
                fill_rect(px, py, cw, ch);
            } else if GOAL[at] == 1 && CRATE[at] == 0 {
                draw_frame(px, py, cw, ch, C_GOAL, 1);
            }
            if CRATE[at] == 1 {
                draw_crate(px, py, cw, ch, GOAL[at] == 1);
            }
            c += 1;
        }
        r += 1;
    }
    draw_hero(ox + HERO_X * cw, oy + HERO_Y * ch, cw, ch);
}

/// The artwork screen: one picture, scaled to whole pixels so the original
/// pixel art stays crisp instead of being resampled.
unsafe fn draw_art(w: i32, h: i32) {
    let mut scale = w / IMAGE_W;
    let vertical = h / IMAGE_H;
    if vertical < scale {
        scale = vertical;
    }
    if scale < 1 {
        scale = 1;
    }
    if scale > 4 {
        scale = 4;
    }
    draw_image(
        IMAGE,
        (w - IMAGE_W * scale) / 2,
        (h - IMAGE_H * scale) / 2,
        scale,
    );
    set_color(C_ACCENT);
    draw_int(IMAGE + 1, 0, 0);
    draw_int(IMAGE_COUNT, 32, 0);
}

/// One frame: consume any pending key, then repaint. Called by the host.
#[no_mangle]
pub extern "C" fn frame() {
    unsafe {
        if STARTED == 0 {
            STARTED = 1;
            load(LEVEL);
        }

        let mut k = key();
        while k != K_NONE {
            if SCREEN == SCREEN_ART {
                handle_art(k);
            } else {
                handle(k);
            }
            k = key();
        }

        let w = width();
        let h = height();
        if w <= 0 || h <= 0 {
            return;
        }
        if SCREEN == SCREEN_ART {
            draw_art(w, h);
            return;
        }
        if COLS <= 0 || ROWS_USED <= 0 {
            return;
        }

        // status line, drawn by the host because the guest has no font
        set_color(C_ACCENT);
        draw_int(LEVEL + 1, 0, 0);
        set_color(C_TEXT);
        draw_int(MOVES, 96, 0);
        draw_int(PUSHES, 184, 0);
        set_color(if SOLVED == 1 { C_DONE } else { C_TEXT });
        draw_int(ON_GOAL, 288, 0);
        draw_int(TOTAL, 320, 0);

        // A cell was 5 characters by 2 in the terminal, and the font is 8 by 16,
        // so the proportion to keep is 5:4.
        let top = 22;
        let mut k = (w - 4) / (5 * COLS);
        let vertical = (h - top - 4) / (4 * ROWS_USED);
        if vertical < k {
            k = vertical;
        }
        if k < 1 {
            k = 1;
        }
        if k > 8 {
            k = 8;
        }
        let cw = 5 * k;
        let ch = 4 * k;
        draw_board((w - COLS * cw) / 2, top, cw, ch);
    }
}

/// The desktop owns the sound setting; it pushes the current value in.
#[no_mangle]
pub extern "C" fn set_sound(on: i32) {
    unsafe {
        SOUND = if on != 0 { 1 } else { 0 };
    }
}

/// Exposed for the host so it can label the status line correctly.
#[no_mangle]
pub extern "C" fn level_count() -> i32 {
    LEVEL_COUNT
}
