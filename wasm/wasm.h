/*
 * Minimal WebAssembly interpreter for JVMOS.
 *
 * Scope of this version: the i32 core of the MVP instruction set, which is
 * enough to run real program logic. i64 and floating point are parsed but
 * rejected rather than silently mis-executed.
 *
 * Design notes, driven by running with no operating system underneath:
 *   - No allocation of any kind. The caller supplies the module struct and the
 *     linear memory buffer; everything else is fixed-size arrays inside the
 *     struct. Nothing here calls malloc, and nothing calls libc.
 *   - The module keeps pointing at the caller's byte buffer, so that buffer
 *     must outlive the module.
 *   - Every linear memory access is bounds checked against the memory the
 *     module itself declared, not against the buffer the host provided, so a
 *     guest asking for one page cannot wander into the rest of that buffer.
 *     That check is the entire point of running untrusted code this way: the
 *     guest cannot reach kernel memory even though there is no MMU and
 *     everything shares one address space.
 */
#ifndef JVMOS_WASM_H
#define JVMOS_WASM_H

#define WASM_MAX_TYPES    64
#define WASM_MAX_FUNCS   256
#define WASM_MAX_EXPORTS  64
#define WASM_MAX_GLOBALS  64
#define WASM_STACK       512   /* shared operand stack, in slots */
#define WASM_LOCALS      512   /* shared locals pool, in slots */
#define WASM_CTRL        128   /* shared control (label) stack */
#define WASM_DEPTH        32   /* max call nesting */

typedef enum {
    WASM_OK = 0,
    WASM_ERR_MAGIC,
    WASM_ERR_VERSION,
    WASM_ERR_TRUNCATED,
    WASM_ERR_UNSUPPORTED,
    WASM_ERR_LIMIT,
    WASM_ERR_NO_EXPORT,
    WASM_ERR_IMPORT,
    WASM_ERR_STACK,
    WASM_ERR_DEPTH,
    WASM_ERR_MEMORY,      /* out of bounds access: the sandbox did its job */
    WASM_ERR_TRAP,        /* unreachable, division by zero, ... */
    WASM_ERR_TYPE
} wasm_err;

/*
 * A function the guest may import. args points at the arguments in call order,
 * the return value is pushed if the import was declared as returning a value.
 */
typedef int (*wasm_host_fn)(int *args, int argc, void *user);

typedef struct {
    const char *module;
    const char *name;
    wasm_host_fn fn;
} wasm_host_entry;

typedef struct {
    unsigned char nparams;
    unsigned char nresults;
} wasm_type;

typedef struct {
    unsigned type;
    const unsigned char *code;      /* first instruction, past the locals decl */
    const unsigned char *code_end;
    unsigned short nlocals;         /* declared locals, params not included */
    unsigned char imported;
    unsigned char host;             /* index into the host table if imported */
} wasm_func;

typedef struct {
    const char *name;
    unsigned namelen;
    unsigned char kind;             /* 0 func, 1 table, 2 memory, 3 global */
    unsigned index;
} wasm_export;

typedef struct {
    const unsigned char *cont;      /* branch target for a loop label */
    unsigned char is_loop;
    unsigned char arity;
    int sp;                         /* operand stack height on entry */
} wasm_label;

typedef struct {
    const unsigned char *bytes;
    unsigned len;

    wasm_type types[WASM_MAX_TYPES];
    unsigned ntypes;

    wasm_func funcs[WASM_MAX_FUNCS];
    unsigned nfuncs;
    unsigned nimports;              /* imported functions occupy indices 0..n-1 */

    wasm_export exports[WASM_MAX_EXPORTS];
    unsigned nexports;

    int globals[WASM_MAX_GLOBALS];
    unsigned nglobals;

    unsigned char *mem;
    unsigned mem_bytes;             /* size of the caller's buffer */
    unsigned mem_pages;             /* pages currently reported to the guest */
    unsigned mem_limit;             /* mem_pages * 65536: the guest's real ceiling */

    const wasm_host_entry *hosts;
    unsigned nhosts;
    void *user;

    int stack[WASM_STACK];
    int sp;
    int locals[WASM_LOCALS];
    int lp;
    wasm_label ctrl[WASM_CTRL];
    int cp;
} wasm_module;

/*
 * Parses a module in place. memory may be null when the module declares none.
 * hosts maps (module, name) pairs to host functions and may be null.
 */
wasm_err wasm_load(wasm_module *m,
                   const unsigned char *bytes, unsigned len,
                   unsigned char *memory, unsigned memory_bytes,
                   const wasm_host_entry *hosts, unsigned nhosts,
                   void *user);

/* Calls an exported function. result may be null when nothing is returned. */
wasm_err wasm_call(wasm_module *m, const char *name,
                   const int *args, int nargs, int *result);

const char *wasm_strerror(wasm_err e);

#endif
