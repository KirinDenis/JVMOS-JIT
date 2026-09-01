/*
 * Host-side harness for the WASM interpreter.
 *
 * The interpreter is freestanding, so it compiles unchanged both into the
 * kernel and into this ordinary program. That lets it be tested against a
 * reference implementation (Node's WebAssembly) before it goes anywhere near
 * the OS, where there is no debugger and a wrong branch is just a black screen.
 *
 *   wasm_host_test <module.wasm> <export> [args...]
 *       calls one export and prints "OK <value>"
 *   wasm_host_test --game <module.wasm> [keycodes...]
 *       drives the Sokoban guest one key per frame and prints the state it
 *       drew, so the whole game can be diffed against the reference engine
 *
 * Not part of the kernel build; the Makefile filters tests/ out.
 */
#include <stdio.h>
#include <stdlib.h>
#include "../wasm/wasm.h"

static unsigned char g_mem[65536 * 4];
static unsigned char g_bytes[1 << 20];
static wasm_module g_mod;          /* ~14KB, kept off the stack */

static int g_rects;
static int g_imgs;
static int g_ints[32];
static int g_nints;
static int g_pending_key;

static int host_add3(int *a, int n, void *u) { (void)u; return n >= 3 ? a[0] + a[1] + a[2] : 0; }
static int host_mul(int *a, int n, void *u)  { (void)u; return n >= 2 ? a[0] * a[1] : 0; }

static int host_set_color(int *a, int n, void *u) { (void)a; (void)n; (void)u; return 0; }
static int host_fill_rect(int *a, int n, void *u) { (void)a; (void)n; (void)u; g_rects++; return 0; }
static int host_width(int *a, int n, void *u)  { (void)a; (void)n; (void)u; return 360; }
static int host_height(int *a, int n, void *u) { (void)a; (void)n; (void)u; return 240; }
static int host_ticks(int *a, int n, void *u)  { (void)a; (void)n; (void)u; return 1000; }
static int host_draw_image(int *a, int n, void *u) { (void)a; (void)n; (void)u; g_imgs++; return 0; }

/* The guest reports its state through draw_int, so capturing those calls is
   enough to observe the game without any other instrumentation. */
static int host_draw_int(int *a, int n, void *u)
{
    (void)u;
    if (n >= 1 && g_nints < 32) g_ints[g_nints++] = a[0];
    return 0;
}

/* One key per frame, matching how the desktop feeds input. */
static int host_key(int *a, int n, void *u)
{
    int k = g_pending_key;
    (void)a; (void)n; (void)u;
    g_pending_key = 0;
    return k;
}

static const wasm_host_entry g_hosts[] = {
    { "env", "add3",      host_add3      },
    { "env", "mul",       host_mul       },
    { "env", "set_color", host_set_color },
    { "env", "fill_rect", host_fill_rect },
    { "env", "width",     host_width     },
    { "env", "height",    host_height    },
    { "env", "ticks",     host_ticks     },
    { "env", "draw_int",  host_draw_int  },
    { "env", "draw_image", host_draw_image },
    { "env", "key",       host_key       }
};

static unsigned read_module(const char *path)
{
    FILE *f = fopen(path, "rb");
    unsigned len;
    if (!f) return 0;
    len = (unsigned)fread(g_bytes, 1, sizeof(g_bytes), f);
    fclose(f);
    return len;
}

static int run_game(int argc, char **argv)
{
    unsigned len = read_module(argv[2]);
    wasm_err e;
    int i;

    if (!len) { printf("ERR cannot open %s\n", argv[2]); return 2; }

    e = wasm_load(&g_mod, g_bytes, len, g_mem, sizeof(g_mem),
                  g_hosts, sizeof(g_hosts) / sizeof(g_hosts[0]), 0);
    if (e != WASM_OK) { printf("ERR %s\n", wasm_strerror(e)); return 1; }

    /* One frame per key, plus a first frame that just boots the level. */
    for (i = 2; i < argc; i++) {
        g_pending_key = (i == 2) ? 0 : atoi(argv[i]);
        g_rects = 0;
        g_imgs = 0;
        g_nints = 0;
        e = wasm_call(&g_mod, "frame", 0, 0, 0);
        if (e != WASM_OK) { printf("ERR %s\n", wasm_strerror(e)); return 1; }
    }

    printf("RECTS %d IMGS %d INTS", g_rects, g_imgs);
    for (i = 0; i < g_nints; i++) printf(" %d", g_ints[i]);
    printf("\n");
    return 0;
}

int main(int argc, char **argv)
{
    unsigned len;
    int args[8];
    int nargs = 0, i, result = 0;
    wasm_err e;

    if (argc >= 3 && argv[1][0] == '-' && argv[1][1] == '-') return run_game(argc, argv);
    if (argc < 3) { printf("ERR usage\n"); return 2; }

    len = read_module(argv[1]);
    if (!len) { printf("ERR cannot open %s\n", argv[1]); return 2; }

    e = wasm_load(&g_mod, g_bytes, len, g_mem, sizeof(g_mem),
                  g_hosts, sizeof(g_hosts) / sizeof(g_hosts[0]), 0);
    if (e != WASM_OK) { printf("ERR %s\n", wasm_strerror(e)); return 1; }

    for (i = 3; i < argc && nargs < 8; i++) args[nargs++] = atoi(argv[i]);

    e = wasm_call(&g_mod, argv[2], args, nargs, &result);
    if (e != WASM_OK) { printf("ERR %s\n", wasm_strerror(e)); return 1; }

    /* draw() returns nothing, so report the work it did instead. */
    if (argv[2][0] == 'd' && argv[2][1] == 'r' && argv[2][2] == 'a' &&
        argv[2][3] == 'w' && argv[2][4] == 0) {
        printf("OK %d\n", g_rects);
    } else {
        printf("OK %d\n", result);
    }
    return 0;
}
