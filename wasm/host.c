/*
 * Bridge between the WebAssembly sandbox and the kernel.
 *
 * The guest never sees a kernel address. It asks how big its window is, draws
 * in its own coordinate space starting at 0,0, and this layer translates those
 * coordinates into the window and clamps them to it. Three independent things
 * have to fail before a guest can touch something it should not:
 *
 *   1. the interpreter bounds checks every linear memory access against the
 *      memory the module declared,
 *   2. these host functions clamp drawing to the window rectangle,
 *   3. the graphics clip rectangle, set by the desktop before the guest runs,
 *      is enforced down in the framebuffer routines.
 */
#include "wasm.h"
#include "demo_module.h"

/* Kernel entry points, cdecl, defined in boot/sys_api.asm. */
extern void sys_set_color(int rgb);
extern void sys_fill_rect(int x, int y, int w, int h);
extern int sys_get_ticks(void);

static wasm_module g_mod;
static unsigned char g_mem[65536];      /* one page: all the guest may address */
static int g_loaded;
static int g_ox, g_oy, g_ow, g_oh;      /* window origin and size */

static int hf_set_color(int *a, int n, void *user)
{
    (void)user;
    if (n >= 1) sys_set_color(a[0] & 0x00FFFFFF);
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

static int hf_width(int *a, int n, void *user)  { (void)a; (void)n; (void)user; return g_ow; }
static int hf_height(int *a, int n, void *user) { (void)a; (void)n; (void)user; return g_oh; }
static int hf_ticks(int *a, int n, void *user)  { (void)a; (void)n; (void)user; return sys_get_ticks(); }

static const wasm_host_entry g_hosts[] = {
    { "env", "set_color", hf_set_color },
    { "env", "fill_rect", hf_fill_rect },
    { "env", "width",     hf_width     },
    { "env", "height",    hf_height    },
    { "env", "ticks",     hf_ticks     }
};

/*
 * Runs the embedded guest for one frame inside the given window rectangle.
 * Returns 1 on success, or the negated wasm_err so the desktop can show what
 * went wrong instead of silently drawing nothing.
 */
int wasm_demo_draw(int x, int y, int w, int h)
{
    wasm_err e;

    g_ox = x; g_oy = y; g_ow = w; g_oh = h;
    if (w <= 0 || h <= 0) return 1;

    if (!g_loaded) {
        e = wasm_load(&g_mod, wasm_demo_module, (unsigned)sizeof(wasm_demo_module),
                      g_mem, (unsigned)sizeof(g_mem),
                      g_hosts, (unsigned)(sizeof(g_hosts) / sizeof(g_hosts[0])), 0);
        if (e != WASM_OK) return -(int)e;
        g_loaded = 1;
    }

    e = wasm_call(&g_mod, "draw", 0, 0, 0);
    return (e == WASM_OK) ? 1 : -(int)e;
}
