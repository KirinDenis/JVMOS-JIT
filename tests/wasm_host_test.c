/*
 * Host-side harness for the WASM interpreter.
 *
 * The interpreter itself is freestanding, so it compiles unchanged both into
 * the kernel and into this ordinary program. That lets the whole thing be
 * tested against a reference implementation (Node's WebAssembly) before it
 * goes anywhere near the OS, where there is no debugger and a wrong branch
 * just shows up as a black screen.
 *
 * Usage: wasm_host_test <module.wasm> <export> [args...]
 * Prints "OK <value>" or "ERR <reason>".
 *
 * Not part of the kernel build; the Makefile filters tests/ out.
 */
#include <stdio.h>
#include <stdlib.h>
#include "../wasm/wasm.h"

static unsigned char g_mem[65536 * 4];
static unsigned char g_bytes[1 << 20];
static wasm_module g_mod;          /* ~14KB, kept out of the stack */

static int host_add3(int *a, int n, void *user)
{
    (void)user;
    return n >= 3 ? a[0] + a[1] + a[2] : 0;
}

static int host_mul(int *a, int n, void *user)
{
    (void)user;
    return n >= 2 ? a[0] * a[1] : 0;
}

/* Stand-ins for the kernel drawing calls, so the module that ships inside the
   OS can be exercised here first. fill_rect only counts, which gives the test
   something concrete to compare against the reference engine. */
static int g_rects = 0;

static int host_set_color(int *a, int n, void *u) { (void)a; (void)n; (void)u; return 0; }
static int host_fill_rect(int *a, int n, void *u) { (void)a; (void)n; (void)u; g_rects++; return 0; }
static int host_width(int *a, int n, void *u)  { (void)a; (void)n; (void)u; return 360; }
static int host_height(int *a, int n, void *u) { (void)a; (void)n; (void)u; return 240; }
static int host_ticks(int *a, int n, void *u)  { (void)a; (void)n; (void)u; return 1000; }

static const wasm_host_entry g_hosts[] = {
    { "env", "add3",      host_add3      },
    { "env", "mul",       host_mul       },
    { "env", "set_color", host_set_color },
    { "env", "fill_rect", host_fill_rect },
    { "env", "width",     host_width     },
    { "env", "height",    host_height    },
    { "env", "ticks",     host_ticks     }
};

int main(int argc, char **argv)
{
    FILE *f;
    unsigned len;
    int args[8];
    int nargs = 0, i, result = 0;
    wasm_err e;

    if (argc < 3) {
        printf("ERR usage\n");
        return 2;
    }
    f = fopen(argv[1], "rb");
    if (!f) {
        printf("ERR cannot open %s\n", argv[1]);
        return 2;
    }
    len = (unsigned)fread(g_bytes, 1, sizeof(g_bytes), f);
    fclose(f);

    e = wasm_load(&g_mod, g_bytes, len, g_mem, sizeof(g_mem),
                  g_hosts, sizeof(g_hosts) / sizeof(g_hosts[0]), 0);
    if (e != WASM_OK) {
        printf("ERR %s\n", wasm_strerror(e));
        return 1;
    }

    for (i = 3; i < argc && nargs < 8; i++) args[nargs++] = atoi(argv[i]);

    e = wasm_call(&g_mod, argv[2], args, nargs, &result);
    if (e != WASM_OK) {
        printf("ERR %s\n", wasm_strerror(e));
        return 1;
    }
    /* draw() returns nothing, so report the work it did instead: the count is
       what gets compared against the reference engine. */
    if (argv[2][0] == 'd' && argv[2][1] == 'r' && argv[2][2] == 'a' &&
        argv[2][3] == 'w' && argv[2][4] == 0) {
        printf("OK %d\n", g_rects);
    } else {
        printf("OK %d\n", result);
    }
    return 0;
}
