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
    /// Returns a pending key, or 0. Codes are assigned by the host below.
    fn key() -> i32;
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

const STRIDE: usize = 32;
const ROWS: usize = 24;
const CELLS: usize = STRIDE * ROWS;
const LEVEL_COUNT: i32 = 61;

const LEVELS: &str = include_str!("levels.txt");

// Colours match the desktop palette so the guest does not look pasted in.
const C_FLOOR: i32 = 0x0020_2830;
const C_WALL: i32 = 0x0080_6040;
const C_WALL_LIT: i32 = 0x00A8_8860;
const C_WALL_DARK: i32 = 0x0050_3820;
const C_GOAL: i32 = 0x00C0_5050;
const C_CRATE: i32 = 0x00B0_8838;
const C_CRATE_OK: i32 = 0x0058_B058;
const C_CRATE_EDGE: i32 = 0x0040_2808;
const C_HERO_HEAD: i32 = 0x00FF_D070;
const C_HERO_BODY: i32 = 0x002C_6CC0;
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
        return;
    }

    if CRATE[at] == 1 {
        let bx = nx + dx;
        let by = ny + dy;
        if bx < 0 || by < 0 || bx >= STRIDE as i32 || by >= ROWS as i32 {
            return;
        }
        let behind = by as usize * STRIDE + bx as usize;
        if WALL[behind] == 1 || CRATE[behind] == 1 {
            return;
        }
        CRATE[at] = 0;
        CRATE[behind] = 1;
        PUSHES += 1;
    }

    HERO_X = nx;
    HERO_Y = ny;
    MOVES += 1;
    count_goals();
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

unsafe fn handle(k: i32) {
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

unsafe fn draw_crate(px: i32, py: i32, ts: i32, on_goal: bool) {
    set_color(if on_goal { C_CRATE_OK } else { C_CRATE });
    fill_rect(px + 1, py + 1, ts - 2, ts - 2);
    set_color(C_CRATE_EDGE);
    fill_rect(px + 1, py + 1, ts - 2, 1);
    fill_rect(px + 1, py + ts - 2, ts - 2, 1);
    fill_rect(px + 1, py + 1, 1, ts - 2);
    fill_rect(px + ts - 2, py + 1, 1, ts - 2);
    if ts >= 12 {
        fill_rect(px + 3, py + ts / 2 - 1, ts - 6, 2);
        fill_rect(px + ts / 2 - 1, py + 3, 2, ts - 6);
    }
}

unsafe fn draw_hero(px: i32, py: i32, ts: i32) {
    let cx = px + ts / 2;
    set_color(C_HERO_HEAD);
    fill_rect(cx - 3, py + 2, 6, 5);
    if ts >= 10 {
        set_color(C_HERO_BODY);
        fill_rect(cx - 4, py + 7, 8, ts - 10);
    }
}

unsafe fn draw_board(ox: i32, oy: i32, ts: i32) {
    // The floor goes down as one rectangle rather than one per cell. Every
    // rectangle is a call across the sandbox boundary into the interpreter, so
    // the cheapest drawing is the drawing that never happens: this alone cuts
    // the calls per frame roughly in half.
    set_color(C_FLOOR);
    fill_rect(ox, oy, COLS * ts, ROWS_USED * ts);

    let mut r = 0i32;
    while r < ROWS_USED {
        let mut c = 0i32;
        while c < COLS {
            let at = r as usize * STRIDE + c as usize;
            let px = ox + c * ts;
            let py = oy + r * ts;
            if WALL[at] == 1 {
                set_color(C_WALL);
                fill_rect(px, py, ts, ts);
                set_color(C_WALL_LIT);
                fill_rect(px, py, ts - 1, 1);
                fill_rect(px, py, 1, ts - 1);
                set_color(C_WALL_DARK);
                fill_rect(px, py + ts - 1, ts, 1);
                fill_rect(px + ts - 1, py, 1, ts);
            } else if GOAL[at] == 1 && CRATE[at] == 0 {
                set_color(C_GOAL);
                fill_rect(px + ts / 2 - 2, py + ts / 2 - 2, 5, 5);
            }
            if CRATE[at] == 1 {
                draw_crate(px, py, ts, GOAL[at] == 1);
            }
            c += 1;
        }
        r += 1;
    }
    draw_hero(ox + HERO_X * ts, oy + HERO_Y * ts, ts);
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
            handle(k);
            k = key();
        }

        let w = width();
        let h = height();
        if w <= 0 || h <= 0 || COLS <= 0 || ROWS_USED <= 0 {
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

        let top = 22;
        let mut ts = (w - 4) / COLS;
        let vertical = (h - top - 4) / ROWS_USED;
        if vertical < ts {
            ts = vertical;
        }
        if ts > 26 {
            ts = 26;
        }
        if ts < 4 {
            ts = 4;
        }
        draw_board((w - COLS * ts) / 2, top, ts);
    }
}

/// Exposed for the host so it can label the status line correctly.
#[no_mangle]
pub extern "C" fn level_count() -> i32 {
    LEVEL_COUNT
}
