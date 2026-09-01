/*
 * Bridge between the WebAssembly sandbox and the kernel.
 *
 * The guest is Sokoban, written in Rust and compiled to wasm32. It never sees
 * a kernel address: it asks how big its window is, draws in its own
 * coordinates from 0,0, and this layer translates and clamps. Three
 * independent things must fail before it can touch anything it should not:
 *
 *   1. the interpreter bounds checks every linear memory access against the
 *      single page the module declared,
 *   2. these host functions clamp drawing to the window rectangle,
 *   3. the graphics clip rectangle, set by the desktop before the guest runs,
 *      is enforced down in the framebuffer routines.
 *
 * Input goes the other way through a small ring buffer: the desktop pushes
 * keys in, the guest drains them at the start of its frame.
 */
#include "wasm.h"
#include "guest_module.h"

/* Kernel entry points, cdecl, from boot/sys_api.asm and boot/font.asm. */
extern void sys_set_color(int rgb);
extern void sys_fill_rect(int x, int y, int w, int h);
extern void draw_char_vram(int ch, int x, int y, int color);

#define KEY_SLOTS 16

static wasm_module g_mod;
static unsigned char g_mem[65536];      /* one page: all the guest may address */
static int g_loaded;
static int g_failed;                    /* remembers a load failure, do not retry every frame */

static int g_ox, g_oy, g_ow, g_oh;      /* window origin and size */
static int g_color = 0x00FFFFFF;

static int g_keys[KEY_SLOTS];
static int g_key_head, g_key_tail;

static int hf_set_color(int *a, int n, void *user)
{
    (void)user;
    if (n >= 1) {
        g_color = a[0] & 0x00FFFFFF;
        sys_set_color(g_color);
    }
    return 0;
}

static int hf_fill_rect(int *a, int n, void *user)
{
    int x, y, w, h;
    (void)user;
    if (n < 4) return 0;
    x = a[0]; y = a[1]; w = a[2]; h = a[3];
    if (w <= 0 || h <= 0) return 0;

    /* Clamp into the window before translating, so a guest asking for a huge
       or negative rectangle simply draws less. */
    if (x < 0) { w += x; x = 0; }
    if (y < 0) { h += y; y = 0; }
    if (x >= g_ow || y >= g_oh) return 0;
    if (w > g_ow - x) w = g_ow - x;
    if (h > g_oh - y) h = g_oh - y;
    if (w <= 0 || h <= 0) return 0;

    sys_fill_rect(g_ox + x, g_oy + y, w, h);
    return 0;
}

/* The guest has no font, so numbers come back here to be rendered. */
static int hf_draw_int(int *a, int n, void *user)
{
    int value, x, y, i, len;
    unsigned u;
    char digits[12];
    (void)user;
    if (n < 3) return 0;
    value = a[0]; x = a[1]; y = a[2];

    if (x < 0 || y < 0 || x >= g_ow || y + 16 > g_oh) return 0;

    len = 0;
    if (value < 0) {
        u = (unsigned)(-value);
    } else {
        u = (unsigned)value;
    }
    do {
        digits[len++] = (char)('0' + (u % 10u));
        u /= 10u;
    } while (u && len < 11);
    if (value < 0 && len < 12) digits[len++] = '-';

    for (i = 0; i < len; i++) {
        int px = x + (len - 1 - i) * 8;
        if (px + 8 > g_ow) continue;
        draw_char_vram((int)(unsigned char)digits[i], g_ox + px, g_oy + y, g_color);
    }
    return 0;
}

static int hf_width(int *a, int n, void *user)  { (void)a; (void)n; (void)user; return g_ow; }
static int hf_height(int *a, int n, void *user) { (void)a; (void)n; (void)user; return g_oh; }

static int hf_key(int *a, int n, void *user)
{
    int k;
    (void)a; (void)n; (void)user;
    if (g_key_head == g_key_tail) return 0;
    k = g_keys[g_key_head];
    g_key_head = (g_key_head + 1) % KEY_SLOTS;
    return k;
}

static const wasm_host_entry g_hosts[] = {
    { "env", "set_color", hf_set_color },
    { "env", "fill_rect", hf_fill_rect },
    { "env", "draw_int",  hf_draw_int  },
    { "env", "width",     hf_width     },
    { "env", "height",    hf_height    },
    { "env", "key",       hf_key       }
};

/* Called from the desktop's key handler; codes are the guest's own small set. */
void wasm_push_key(int code)
{
    int next = (g_key_tail + 1) % KEY_SLOTS;
    if (code <= 0 || next == g_key_head) return;   /* full: drop rather than block */
    g_keys[g_key_tail] = code;
    g_key_tail = next;
}

/*
 * Runs the guest for one frame inside the given window rectangle.
 * Returns 1 on success, or the negated wasm_err so the desktop can say what
 * went wrong instead of silently drawing nothing.
 */
int wasm_guest_frame(int x, int y, int w, int h)
{
    wasm_err e;

    g_ox = x; g_oy = y; g_ow = w; g_oh = h;
    if (w <= 0 || h <= 0) return 1;
    if (g_failed) return g_failed;

    if (!g_loaded) {
        e = wasm_load(&g_mod, wasm_guest_module, (unsigned)sizeof(wasm_guest_module),
                      g_mem, (unsigned)sizeof(g_mem),
                      g_hosts, (unsigned)(sizeof(g_hosts) / sizeof(g_hosts[0])), 0);
        if (e != WASM_OK) { g_failed = -(int)e; return g_failed; }
        g_loaded = 1;
    }

    e = wasm_call(&g_mod, "frame", 0, 0, 0);
    return (e == WASM_OK) ? 1 : -(int)e;
}
