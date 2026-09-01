/*
 * Minimal WebAssembly interpreter, i32 core. See wasm.h for the contract.
 *
 * Branch handling deserves a word, because it is the part that makes WASM
 * different from a plain bytecode: control flow is structured, so "br 2" means
 * "leave two enclosing blocks" rather than "jump to address N". Instead of
 * building a side table of jump targets, a branch scans forward from the
 * current position counting nesting until it has left the requested number of
 * blocks. Loops are the cheap case: their label already carries the address to
 * jump back to. Scanning costs more per branch than a precomputed table, but it
 * removes a whole pass and its storage, which matters more here.
 */
#include "wasm.h"

/* Local helpers instead of libc: this object is linked into a freestanding
   kernel where <string.h> does not exist. */
static void wzero(void *p, unsigned n)
{
    unsigned char *d = (unsigned char *)p;
    while (n--) *d++ = 0;
}

static int wcmp(const unsigned char *a, const char *b, unsigned n)
{
    unsigned i;
    for (i = 0; i < n; i++) {
        if (a[i] != (unsigned char)b[i]) return 1;
    }
    return b[n] == 0 ? 0 : 1;
}

/* ------------------------------------------------------------------ reader */

typedef struct {
    const unsigned char *p;
    const unsigned char *end;
    int err;
} rd;

static unsigned char rd_u8(rd *r)
{
    if (r->p >= r->end) { r->err = 1; return 0; }
    return *r->p++;
}

static unsigned rd_uleb(rd *r)
{
    unsigned result = 0, shift = 0;
    for (;;) {
        unsigned char b = rd_u8(r);
        if (r->err) return 0;
        result |= (unsigned)(b & 0x7F) << shift;
        if ((b & 0x80) == 0) break;
        shift += 7;
        if (shift > 31) { r->err = 1; return 0; }
    }
    return result;
}

static int rd_sleb(rd *r)
{
    int result = 0;
    unsigned shift = 0;
    unsigned char b = 0;
    for (;;) {
        b = rd_u8(r);
        if (r->err) return 0;
        result |= (int)((unsigned)(b & 0x7F) << shift);
        shift += 7;
        if ((b & 0x80) == 0) break;
        if (shift > 31) { r->err = 1; return 0; }
    }
    if (shift < 32 && (b & 0x40)) result |= -(int)(1u << shift);
    return result;
}

static void rd_skip(rd *r, unsigned n)
{
    if ((unsigned)(r->end - r->p) < n) { r->err = 1; r->p = r->end; return; }
    r->p += n;
}

/* -------------------------------------------------- instruction navigation */

/* Advances past a blocktype: 0x40 (none), a value type, or a type index. */
static void skip_blocktype(rd *r)
{
    if (r->p >= r->end) { r->err = 1; return; }
    if (*r->p == 0x40 || (*r->p >= 0x7B && *r->p <= 0x7F)) { r->p++; return; }
    rd_sleb(r);
}

/*
 * Advances past the immediates of one opcode. Every forward scan depends on
 * this being exact: miscounting an immediate would resynchronise on a byte
 * that is not an opcode and corrupt the scan.
 */
static void skip_immediates(rd *r, unsigned char op)
{
    if (op == 0x02 || op == 0x03 || op == 0x04) { skip_blocktype(r); return; }
    if (op == 0x0C || op == 0x0D) { rd_uleb(r); return; }          /* br, br_if */
    if (op == 0x0E) {                                              /* br_table */
        unsigned n = rd_uleb(r), i;
        for (i = 0; i <= n && !r->err; i++) rd_uleb(r);
        return;
    }
    if (op == 0x10) { rd_uleb(r); return; }                        /* call */
    if (op == 0x11) { rd_uleb(r); rd_uleb(r); return; }            /* call_indirect */
    if (op >= 0x20 && op <= 0x24) { rd_uleb(r); return; }          /* local/global */
    if (op >= 0x28 && op <= 0x3E) { rd_uleb(r); rd_uleb(r); return; } /* load/store */
    if (op == 0x3F || op == 0x40) { rd_u8(r); return; }            /* memory.size/grow */
    if (op == 0x41) { rd_sleb(r); return; }                        /* i32.const */
    if (op == 0x42) { rd_sleb(r); return; }                        /* i64.const */
    if (op == 0x43) { rd_skip(r, 4); return; }                     /* f32.const */
    if (op == 0x44) { rd_skip(r, 8); return; }                     /* f64.const */
    /* everything else has no immediates */
}

/*
 * From a position inside a block, walks forward until it has left `levels`
 * enclosing blocks, and returns the position just past the final `end`.
 * Returns null if the body is malformed.
 */
static const unsigned char *branch_target(const unsigned char *pc,
                                          const unsigned char *end,
                                          unsigned levels)
{
    rd r;
    int depth = 0;
    r.p = pc; r.end = end; r.err = 0;
    while (r.p < r.end && !r.err) {
        unsigned char op = rd_u8(&r);
        if (op == 0x02 || op == 0x03 || op == 0x04) { skip_blocktype(&r); depth++; continue; }
        if (op == 0x0B) {                                          /* end */
            if (depth > 0) { depth--; continue; }
            if (levels == 0) return r.p;
            levels--;
            continue;
        }
        /* An `else` at depth 0 belongs to a block we are leaving: keep going,
           its `end` is what terminates that block. */
        if (op == 0x05) continue;
        skip_immediates(&r, op);
    }
    return 0;
}

/* Finds where a false `if` continues: just past its `else`, or past its `end`. */
static const unsigned char *else_or_end(const unsigned char *pc,
                                        const unsigned char *end)
{
    rd r;
    int depth = 0;
    r.p = pc; r.end = end; r.err = 0;
    while (r.p < r.end && !r.err) {
        unsigned char op = rd_u8(&r);
        if (op == 0x02 || op == 0x03 || op == 0x04) { skip_blocktype(&r); depth++; continue; }
        if (op == 0x0B) {
            if (depth == 0) return r.p;
            depth--;
            continue;
        }
        if (op == 0x05 && depth == 0) return r.p;
        skip_immediates(&r, op);
    }
    return 0;
}

/* ------------------------------------------------------------------ loading */

static unsigned blocktype_arity(wasm_module *m, rd *r)
{
    if (r->p >= r->end) { r->err = 1; return 0; }
    if (*r->p == 0x40) { r->p++; return 0; }
    if (*r->p >= 0x7B && *r->p <= 0x7F) { r->p++; return 1; }
    {
        int idx = rd_sleb(r);
        if (idx < 0 || (unsigned)idx >= m->ntypes) { r->err = 1; return 0; }
        return m->types[idx].nresults;
    }
}

static wasm_err read_limits(rd *r, unsigned *initial)
{
    unsigned char flags = rd_u8(r);
    *initial = rd_uleb(r);
    if (flags & 1) rd_uleb(r);          /* maximum, not enforced here */
    return r->err ? WASM_ERR_TRUNCATED : WASM_OK;
}

static wasm_err section_type(wasm_module *m, rd *r)
{
    unsigned n = rd_uleb(r), i, j;
    if (n > WASM_MAX_TYPES) return WASM_ERR_LIMIT;
    for (i = 0; i < n; i++) {
        unsigned np, nres;
        if (rd_u8(r) != 0x60) return WASM_ERR_TYPE;
        np = rd_uleb(r);
        if (np > 255) return WASM_ERR_LIMIT;
        for (j = 0; j < np; j++) {
            if (rd_u8(r) != 0x7F) return WASM_ERR_UNSUPPORTED;   /* i32 only */
        }
        nres = rd_uleb(r);
        if (nres > 1) return WASM_ERR_UNSUPPORTED;
        for (j = 0; j < nres; j++) {
            if (rd_u8(r) != 0x7F) return WASM_ERR_UNSUPPORTED;
        }
        m->types[i].nparams = (unsigned char)np;
        m->types[i].nresults = (unsigned char)nres;
    }
    m->ntypes = n;
    return r->err ? WASM_ERR_TRUNCATED : WASM_OK;
}

static wasm_err section_import(wasm_module *m, rd *r)
{
    unsigned n = rd_uleb(r), i;
    for (i = 0; i < n; i++) {
        unsigned mlen, nlen;
        const unsigned char *mname, *fname;
        unsigned char kind;

        mlen = rd_uleb(r);
        mname = r->p; rd_skip(r, mlen);
        nlen = rd_uleb(r);
        fname = r->p; rd_skip(r, nlen);
        kind = rd_u8(r);
        if (r->err) return WASM_ERR_TRUNCATED;

        if (kind == 0x00) {                       /* function */
            unsigned t = rd_uleb(r);
            unsigned h;
            int found = -1;
            if (m->nfuncs >= WASM_MAX_FUNCS) return WASM_ERR_LIMIT;
            if (t >= m->ntypes) return WASM_ERR_TYPE;
            for (h = 0; h < m->nhosts; h++) {
                if (wcmp(mname, m->hosts[h].module, mlen) == 0 &&
                    wcmp(fname, m->hosts[h].name, nlen) == 0) { found = (int)h; break; }
            }
            if (found < 0) return WASM_ERR_IMPORT;
            m->funcs[m->nfuncs].type = t;
            m->funcs[m->nfuncs].imported = 1;
            m->funcs[m->nfuncs].host = (unsigned char)found;
            m->funcs[m->nfuncs].code = 0;
            m->funcs[m->nfuncs].code_end = 0;
            m->funcs[m->nfuncs].nlocals = 0;
            m->nfuncs++;
            m->nimports++;
        } else if (kind == 0x01) {                /* table */
            rd_u8(r); { unsigned ini; read_limits(r, &ini); }
        } else if (kind == 0x02) {                /* memory */
            unsigned ini; read_limits(r, &ini);
        } else if (kind == 0x03) {                /* global */
            rd_u8(r); rd_u8(r);
        } else {
            return WASM_ERR_UNSUPPORTED;
        }
    }
    return r->err ? WASM_ERR_TRUNCATED : WASM_OK;
}

static wasm_err section_function(wasm_module *m, rd *r, unsigned *first_local)
{
    unsigned n = rd_uleb(r), i;
    *first_local = m->nfuncs;
    if (m->nfuncs + n > WASM_MAX_FUNCS) return WASM_ERR_LIMIT;
    for (i = 0; i < n; i++) {
        unsigned t = rd_uleb(r);
        if (t >= m->ntypes) return WASM_ERR_TYPE;
        m->funcs[m->nfuncs].type = t;
        m->funcs[m->nfuncs].imported = 0;
        m->funcs[m->nfuncs].host = 0;
        m->funcs[m->nfuncs].code = 0;
        m->funcs[m->nfuncs].code_end = 0;
        m->funcs[m->nfuncs].nlocals = 0;
        m->nfuncs++;
    }
    return r->err ? WASM_ERR_TRUNCATED : WASM_OK;
}

/* Constant expressions: only i32.const and global.get are needed here. */
static int const_expr(wasm_module *m, rd *r)
{
    int v = 0;
    unsigned char op = rd_u8(r);
    if (op == 0x41) {
        v = rd_sleb(r);
    } else if (op == 0x23) {
        unsigned g = rd_uleb(r);
        v = (g < m->nglobals) ? m->globals[g] : 0;
    } else {
        r->err = 1;
    }
    if (rd_u8(r) != 0x0B) r->err = 1;      /* end */
    return v;
}

static wasm_err section_global(wasm_module *m, rd *r)
{
    unsigned n = rd_uleb(r), i;
    if (n > WASM_MAX_GLOBALS) return WASM_ERR_LIMIT;
    for (i = 0; i < n; i++) {
        if (rd_u8(r) != 0x7F) return WASM_ERR_UNSUPPORTED;
        rd_u8(r);                          /* mutability */
        m->globals[i] = const_expr(m, r);
        m->nglobals = i + 1;
    }
    return r->err ? WASM_ERR_TRUNCATED : WASM_OK;
}

static wasm_err section_export(wasm_module *m, rd *r)
{
    unsigned n = rd_uleb(r), i;
    if (n > WASM_MAX_EXPORTS) return WASM_ERR_LIMIT;
    for (i = 0; i < n; i++) {
        unsigned len = rd_uleb(r);
        const unsigned char *name = r->p;
        rd_skip(r, len);
        m->exports[i].name = (const char *)name;
        m->exports[i].namelen = len;
        m->exports[i].kind = rd_u8(r);
        m->exports[i].index = rd_uleb(r);
        m->nexports = i + 1;
    }
    return r->err ? WASM_ERR_TRUNCATED : WASM_OK;
}

static wasm_err section_code(wasm_module *m, rd *r, unsigned first_local)
{
    unsigned n = rd_uleb(r), i, j;
    for (i = 0; i < n; i++) {
        unsigned size = rd_uleb(r);
        const unsigned char *body_end = r->p + size;
        unsigned groups, total = 0;
        unsigned fi = first_local + i;

        if (r->err || body_end > r->end) return WASM_ERR_TRUNCATED;
        if (fi >= m->nfuncs) return WASM_ERR_LIMIT;

        groups = rd_uleb(r);
        for (j = 0; j < groups; j++) {
            unsigned count = rd_uleb(r);
            if (rd_u8(r) != 0x7F) return WASM_ERR_UNSUPPORTED;
            total += count;
        }
        if (total > 255) return WASM_ERR_LIMIT;

        m->funcs[fi].nlocals = (unsigned short)total;
        m->funcs[fi].code = r->p;
        m->funcs[fi].code_end = body_end;
        r->p = body_end;
    }
    return r->err ? WASM_ERR_TRUNCATED : WASM_OK;
}

static wasm_err section_data(wasm_module *m, rd *r)
{
    unsigned n = rd_uleb(r), i;
    for (i = 0; i < n; i++) {
        unsigned mode = rd_uleb(r);
        unsigned offset = 0, len, k;
        if (mode != 0) return WASM_ERR_UNSUPPORTED;
        offset = (unsigned)const_expr(m, r);
        len = rd_uleb(r);
        if (r->err) return WASM_ERR_TRUNCATED;
        if (!m->mem || offset > m->mem_limit || len > m->mem_limit - offset) {
            return WASM_ERR_MEMORY;
        }
        for (k = 0; k < len; k++) m->mem[offset + k] = rd_u8(r);
    }
    return r->err ? WASM_ERR_TRUNCATED : WASM_OK;
}

wasm_err wasm_load(wasm_module *m,
                   const unsigned char *bytes, unsigned len,
                   unsigned char *memory, unsigned memory_bytes,
                   const wasm_host_entry *hosts, unsigned nhosts,
                   void *user)
{
    rd r;
    unsigned first_local = 0;

    wzero(m, sizeof(*m));
    m->bytes = bytes;
    m->len = len;
    m->mem = memory;
    m->mem_bytes = memory_bytes;
    m->hosts = hosts;
    m->nhosts = nhosts;
    m->user = user;

    r.p = bytes; r.end = bytes + len; r.err = 0;
    if (len < 8) return WASM_ERR_TRUNCATED;
    if (bytes[0] != 0x00 || bytes[1] != 0x61 || bytes[2] != 0x73 || bytes[3] != 0x6D) {
        return WASM_ERR_MAGIC;
    }
    if (bytes[4] != 1 || bytes[5] || bytes[6] || bytes[7]) return WASM_ERR_VERSION;
    r.p += 8;

    while (r.p < r.end) {
        unsigned char id = rd_u8(&r);
        unsigned size = rd_uleb(&r);
        const unsigned char *next;
        rd sub;
        wasm_err e = WASM_OK;

        if (r.err || size > (unsigned)(r.end - r.p)) return WASM_ERR_TRUNCATED;
        next = r.p + size;
        sub.p = r.p; sub.end = next; sub.err = 0;

        if (id == 1) e = section_type(m, &sub);
        else if (id == 2) e = section_import(m, &sub);
        else if (id == 3) e = section_function(m, &sub, &first_local);
        else if (id == 5) {
            unsigned count = rd_uleb(&sub);
            unsigned pages = 0;
            if (count > 1) return WASM_ERR_UNSUPPORTED;
            if (count == 1) e = read_limits(&sub, &pages);
            m->mem_pages = pages;
            m->mem_limit = pages * 65536u;
            if (memory_bytes < m->mem_limit) return WASM_ERR_MEMORY;
        }
        else if (id == 6) e = section_global(m, &sub);
        else if (id == 7) e = section_export(m, &sub);
        else if (id == 10) e = section_code(m, &sub, first_local);
        else if (id == 11) e = section_data(m, &sub);
        /* 0 custom, 4 table, 8 start, 9 element: skipped */

        if (e != WASM_OK) return e;
        if (sub.err) return WASM_ERR_TRUNCATED;
        r.p = next;
    }
    return WASM_OK;
}

/* --------------------------------------------------------------- execution */

#define PUSH(v) do { if (m->sp >= WASM_STACK) return WASM_ERR_STACK; m->stack[m->sp++] = (v); } while (0)
#define POP(out) do { if (m->sp <= 0) return WASM_ERR_STACK; (out) = m->stack[--m->sp]; } while (0)

static wasm_err mem_check(wasm_module *m, unsigned addr, unsigned size)
{
    /* Checked against the memory the module declared, not against the buffer
       the host happened to provide: a guest asking for one page must not be
       able to reach the rest of that buffer. Written as a subtraction so a
       wrapping address cannot slip through. */
    if (!m->mem) return WASM_ERR_MEMORY;
    if (addr > m->mem_limit) return WASM_ERR_MEMORY;
    if (size > m->mem_limit - addr) return WASM_ERR_MEMORY;
    return WASM_OK;
}

static int load32(const unsigned char *p)
{
    return (int)((unsigned)p[0] | ((unsigned)p[1] << 8) |
                 ((unsigned)p[2] << 16) | ((unsigned)p[3] << 24));
}

static void store32(unsigned char *p, int v)
{
    unsigned u = (unsigned)v;
    p[0] = (unsigned char)u;
    p[1] = (unsigned char)(u >> 8);
    p[2] = (unsigned char)(u >> 16);
    p[3] = (unsigned char)(u >> 24);
}

static int clz32(unsigned v)
{
    int n = 0;
    if (!v) return 32;
    while (!(v & 0x80000000u)) { v <<= 1; n++; }
    return n;
}

static int ctz32(unsigned v)
{
    int n = 0;
    if (!v) return 32;
    while (!(v & 1u)) { v >>= 1; n++; }
    return n;
}

static int popcnt32(unsigned v)
{
    int n = 0;
    while (v) { n += (int)(v & 1u); v >>= 1; }
    return n;
}

static wasm_err run(wasm_module *m, unsigned fidx, int depth, int *result);

static wasm_err do_call(wasm_module *m, unsigned fidx, int depth, int *result)
{
    wasm_func *f;
    if (fidx >= m->nfuncs) return WASM_ERR_TYPE;
    f = &m->funcs[fidx];

    if (f->imported) {
        unsigned np = m->types[f->type].nparams;
        int r;
        if ((unsigned)m->sp < np) return WASM_ERR_STACK;
        m->sp -= (int)np;
        r = m->hosts[f->host].fn(&m->stack[m->sp], (int)np, m->user);
        if (m->types[f->type].nresults) {
            if (m->sp >= WASM_STACK) return WASM_ERR_STACK;
            m->stack[m->sp++] = r;
        }
        if (result) *result = r;
        return WASM_OK;
    }
    return run(m, fidx, depth, result);
}

static wasm_err run(wasm_module *m, unsigned fidx, int depth, int *result)
{
    wasm_func *f = &m->funcs[fidx];
    wasm_type *t = &m->types[f->type];
    unsigned nparams = t->nparams;
    unsigned nlocals = f->nlocals;
    int locals_base = m->lp;
    int ctrl_base = m->cp;
    int sp_base;
    rd r;
    unsigned i;

    if (depth >= WASM_DEPTH) return WASM_ERR_DEPTH;
    if (m->lp + (int)(nparams + nlocals) > WASM_LOCALS) return WASM_ERR_STACK;
    if ((unsigned)m->sp < nparams) return WASM_ERR_STACK;

    /* Arguments were pushed in call order, so they come off in reverse. */
    m->sp -= (int)nparams;
    for (i = 0; i < nparams; i++) m->locals[locals_base + i] = m->stack[m->sp + i];
    for (i = 0; i < nlocals; i++) m->locals[locals_base + nparams + i] = 0;
    m->lp = locals_base + (int)(nparams + nlocals);
    sp_base = m->sp;

    r.p = f->code; r.end = f->code_end; r.err = 0;

    for (;;) {
        unsigned char op;
        if (r.p >= r.end) break;
        op = rd_u8(&r);
        if (r.err) return WASM_ERR_TRUNCATED;

        /* ---- control ---- */
        if (op == 0x00) return WASM_ERR_TRAP;                 /* unreachable */
        else if (op == 0x01) { /* nop */ }
        else if (op == 0x02 || op == 0x03) {                  /* block, loop */
            unsigned arity = blocktype_arity(m, &r);
            if (r.err) return WASM_ERR_TRUNCATED;
            if (m->cp >= WASM_CTRL) return WASM_ERR_STACK;
            m->ctrl[m->cp].is_loop = (op == 0x03);
            /* Branching to a loop re-enters its body, so the label has to point
               past the blocktype: pointing at it would re-read that byte as an
               opcode (0x40 happens to decode as memory.grow). */
            m->ctrl[m->cp].cont = r.p;
            m->ctrl[m->cp].arity = (unsigned char)(op == 0x03 ? 0 : arity);
            m->ctrl[m->cp].sp = m->sp;
            m->cp++;
        }
        else if (op == 0x04) {                                /* if */
            unsigned arity;
            int cond;
            arity = blocktype_arity(m, &r);
            if (r.err) return WASM_ERR_TRUNCATED;
            POP(cond);
            if (m->cp >= WASM_CTRL) return WASM_ERR_STACK;
            m->ctrl[m->cp].is_loop = 0;
            m->ctrl[m->cp].cont = 0;
            m->ctrl[m->cp].arity = (unsigned char)arity;
            m->ctrl[m->cp].sp = m->sp;
            m->cp++;
            if (!cond) {
                const unsigned char *dst = else_or_end(r.p, r.end);
                if (!dst) return WASM_ERR_TRUNCATED;
                /* Landing past `end` means the block is already finished. */
                if (dst[-1] == 0x0B) m->cp--;
                r.p = dst;
            }
        }
        else if (op == 0x05) {                                /* else */
            /* Reached by falling out of the then-arm: skip the else-arm. */
            const unsigned char *dst = branch_target(r.p, r.end, 0);
            if (!dst) return WASM_ERR_TRUNCATED;
            if (m->cp > ctrl_base) m->cp--;
            r.p = dst;
        }
        else if (op == 0x0B) {                                /* end */
            if (m->cp > ctrl_base) m->cp--;
            else break;                                       /* end of function */
        }
        else if (op == 0x0C || op == 0x0D) {                  /* br, br_if */
            unsigned levels = rd_uleb(&r);
            int cond = 1;
            if (op == 0x0D) POP(cond);
            if (cond) {
                wasm_label *lab;
                int keep, k;
                if ((int)levels >= m->cp - ctrl_base) {
                    /* Branching past the outermost label returns. */
                    m->cp = ctrl_base;
                    break;
                }
                lab = &m->ctrl[m->cp - 1 - (int)levels];
                keep = lab->arity;
                if (m->sp < keep) return WASM_ERR_STACK;
                for (k = 0; k < keep; k++) m->stack[lab->sp + k] = m->stack[m->sp - keep + k];
                m->sp = lab->sp + keep;
                if (lab->is_loop) {
                    m->cp = m->cp - (int)levels;      /* the loop label stays */
                    r.p = lab->cont;
                } else {
                    const unsigned char *dst = branch_target(r.p, r.end, levels);
                    if (!dst) return WASM_ERR_TRUNCATED;
                    m->cp = m->cp - (int)levels - 1;
                    r.p = dst;
                }
            }
        }
        else if (op == 0x0E) {                                /* br_table */
            unsigned n = rd_uleb(&r), k;
            unsigned chosen, target = 0;
            int idx;
            POP(idx);
            chosen = ((unsigned)idx < n) ? (unsigned)idx : n;
            for (k = 0; k <= n; k++) {
                unsigned v = rd_uleb(&r);
                if (k == chosen) target = v;
            }
            if (r.err) return WASM_ERR_TRUNCATED;
            {
                wasm_label *lab;
                int keep, j;
                if ((int)target >= m->cp - ctrl_base) { m->cp = ctrl_base; break; }
                lab = &m->ctrl[m->cp - 1 - (int)target];
                keep = lab->arity;
                if (m->sp < keep) return WASM_ERR_STACK;
                for (j = 0; j < keep; j++) m->stack[lab->sp + j] = m->stack[m->sp - keep + j];
                m->sp = lab->sp + keep;
                if (lab->is_loop) {
                    m->cp = m->cp - (int)target;
                    r.p = lab->cont;
                } else {
                    const unsigned char *dst = branch_target(r.p, r.end, target);
                    if (!dst) return WASM_ERR_TRUNCATED;
                    m->cp = m->cp - (int)target - 1;
                    r.p = dst;
                }
            }
        }
        else if (op == 0x0F) { m->cp = ctrl_base; break; }    /* return */
        else if (op == 0x10) {                                /* call */
            unsigned callee = rd_uleb(&r);
            wasm_err e;
            if (r.err) return WASM_ERR_TRUNCATED;
            e = do_call(m, callee, depth + 1, 0);
            if (e != WASM_OK) return e;
        }
        else if (op == 0x11) return WASM_ERR_UNSUPPORTED;     /* call_indirect */

        /* ---- parametric ---- */
        else if (op == 0x1A) { int v; POP(v); }               /* drop */
        else if (op == 0x1B) {                                /* select */
            int c, a, b;
            POP(c); POP(b); POP(a);
            PUSH(c ? a : b);
        }

        /* ---- variables ---- */
        else if (op == 0x20) {                                /* local.get */
            unsigned k = rd_uleb(&r);
            if (k >= nparams + nlocals) return WASM_ERR_TYPE;
            PUSH(m->locals[locals_base + k]);
        }
        else if (op == 0x21) {                                /* local.set */
            unsigned k = rd_uleb(&r);
            int v;
            if (k >= nparams + nlocals) return WASM_ERR_TYPE;
            POP(v);
            m->locals[locals_base + k] = v;
        }
        else if (op == 0x22) {                                /* local.tee */
            unsigned k = rd_uleb(&r);
            if (k >= nparams + nlocals) return WASM_ERR_TYPE;
            if (m->sp <= 0) return WASM_ERR_STACK;
            m->locals[locals_base + k] = m->stack[m->sp - 1];
        }
        else if (op == 0x23) {                                /* global.get */
            unsigned k = rd_uleb(&r);
            if (k >= m->nglobals) return WASM_ERR_TYPE;
            PUSH(m->globals[k]);
        }
        else if (op == 0x24) {                                /* global.set */
            unsigned k = rd_uleb(&r);
            int v;
            if (k >= m->nglobals) return WASM_ERR_TYPE;
            POP(v);
            m->globals[k] = v;
        }

        /* ---- linear memory: every access is bounds checked ---- */
        else if (op >= 0x28 && op <= 0x3E) {
            unsigned offset, addr;
            int base;
            wasm_err e;
            rd_uleb(&r);                                      /* alignment hint */
            offset = rd_uleb(&r);
            if (r.err) return WASM_ERR_TRUNCATED;

            if (op <= 0x35) {                                 /* loads */
                POP(base);
                addr = (unsigned)base + offset;
                if (op == 0x28) {                             /* i32.load */
                    e = mem_check(m, addr, 4); if (e) return e;
                    PUSH(load32(m->mem + addr));
                } else if (op == 0x2C) {                      /* i32.load8_s */
                    e = mem_check(m, addr, 1); if (e) return e;
                    PUSH((int)(signed char)m->mem[addr]);
                } else if (op == 0x2D) {                      /* i32.load8_u */
                    e = mem_check(m, addr, 1); if (e) return e;
                    PUSH((int)m->mem[addr]);
                } else if (op == 0x2E) {                      /* i32.load16_s */
                    e = mem_check(m, addr, 2); if (e) return e;
                    PUSH((int)(short)((unsigned)m->mem[addr] | ((unsigned)m->mem[addr + 1] << 8)));
                } else if (op == 0x2F) {                      /* i32.load16_u */
                    e = mem_check(m, addr, 2); if (e) return e;
                    PUSH((int)((unsigned)m->mem[addr] | ((unsigned)m->mem[addr + 1] << 8)));
                } else {
                    return WASM_ERR_UNSUPPORTED;              /* i64/f32/f64 */
                }
            } else {                                          /* stores */
                int value;
                POP(value);
                POP(base);
                addr = (unsigned)base + offset;
                if (op == 0x36) {                             /* i32.store */
                    e = mem_check(m, addr, 4); if (e) return e;
                    store32(m->mem + addr, value);
                } else if (op == 0x3A) {                      /* i32.store8 */
                    e = mem_check(m, addr, 1); if (e) return e;
                    m->mem[addr] = (unsigned char)value;
                } else if (op == 0x3B) {                      /* i32.store16 */
                    e = mem_check(m, addr, 2); if (e) return e;
                    m->mem[addr] = (unsigned char)value;
                    m->mem[addr + 1] = (unsigned char)((unsigned)value >> 8);
                } else {
                    return WASM_ERR_UNSUPPORTED;
                }
            }
        }
        else if (op == 0x3F) { rd_u8(&r); PUSH((int)m->mem_pages); }
        else if (op == 0x40) {                                /* memory.grow */
            int pages;
            unsigned want;
            rd_u8(&r);
            POP(pages);
            want = m->mem_pages + (unsigned)pages;
            if (pages < 0 || want * 65536u > m->mem_bytes) {
                PUSH(-1);
            } else {
                PUSH((int)m->mem_pages);
                m->mem_pages = want;
                m->mem_limit = want * 65536u;
            }
        }

        /* ---- constants and i32 arithmetic ---- */
        else if (op == 0x41) { int v = rd_sleb(&r); PUSH(v); }
        else if (op == 0x45) { int a; POP(a); PUSH(a == 0); }
        else if (op >= 0x46 && op <= 0x4F) {
            int a, b;
            POP(b); POP(a);
            if (op == 0x46) PUSH(a == b);
            else if (op == 0x47) PUSH(a != b);
            else if (op == 0x48) PUSH(a < b);
            else if (op == 0x49) PUSH((unsigned)a < (unsigned)b);
            else if (op == 0x4A) PUSH(a > b);
            else if (op == 0x4B) PUSH((unsigned)a > (unsigned)b);
            else if (op == 0x4C) PUSH(a <= b);
            else if (op == 0x4D) PUSH((unsigned)a <= (unsigned)b);
            else if (op == 0x4E) PUSH(a >= b);
            else PUSH((unsigned)a >= (unsigned)b);
        }
        else if (op == 0x67) { int a; POP(a); PUSH(clz32((unsigned)a)); }
        else if (op == 0x68) { int a; POP(a); PUSH(ctz32((unsigned)a)); }
        else if (op == 0x69) { int a; POP(a); PUSH(popcnt32((unsigned)a)); }
        else if (op >= 0x6A && op <= 0x78) {
            int a, b;
            POP(b); POP(a);
            if (op == 0x6A) PUSH((int)((unsigned)a + (unsigned)b));
            else if (op == 0x6B) PUSH((int)((unsigned)a - (unsigned)b));
            else if (op == 0x6C) PUSH((int)((unsigned)a * (unsigned)b));
            else if (op == 0x6D) {                            /* div_s */
                if (b == 0) return WASM_ERR_TRAP;
                if (a == (int)0x80000000 && b == -1) return WASM_ERR_TRAP;
                PUSH(a / b);
            }
            else if (op == 0x6E) {                            /* div_u */
                if (b == 0) return WASM_ERR_TRAP;
                PUSH((int)((unsigned)a / (unsigned)b));
            }
            else if (op == 0x6F) {                            /* rem_s */
                if (b == 0) return WASM_ERR_TRAP;
                if (a == (int)0x80000000 && b == -1) PUSH(0);
                else PUSH(a % b);
            }
            else if (op == 0x70) {                            /* rem_u */
                if (b == 0) return WASM_ERR_TRAP;
                PUSH((int)((unsigned)a % (unsigned)b));
            }
            else if (op == 0x71) PUSH(a & b);
            else if (op == 0x72) PUSH(a | b);
            else if (op == 0x73) PUSH(a ^ b);
            else if (op == 0x74) PUSH((int)((unsigned)a << ((unsigned)b & 31)));
            else if (op == 0x75) PUSH(a >> ((unsigned)b & 31));
            else if (op == 0x76) PUSH((int)((unsigned)a >> ((unsigned)b & 31)));
            else if (op == 0x77) {                            /* rotl */
                unsigned s = (unsigned)b & 31;
                PUSH((int)(s ? (((unsigned)a << s) | ((unsigned)a >> (32 - s))) : (unsigned)a));
            }
            else {                                            /* rotr */
                unsigned s = (unsigned)b & 31;
                PUSH((int)(s ? (((unsigned)a >> s) | ((unsigned)a << (32 - s))) : (unsigned)a));
            }
        }
        else {
            return WASM_ERR_UNSUPPORTED;
        }
    }

    /* Unwind: keep the result, restore locals and control for the caller. */
    {
        int nres = t->nresults;
        int value = 0;
        if (nres) {
            if (m->sp <= sp_base) return WASM_ERR_STACK;
            value = m->stack[m->sp - 1];
        }
        m->sp = sp_base;
        m->lp = locals_base;
        m->cp = ctrl_base;
        if (nres) {
            if (m->sp >= WASM_STACK) return WASM_ERR_STACK;
            m->stack[m->sp++] = value;
        }
        if (result) *result = value;
    }
    return WASM_OK;
}

wasm_err wasm_call(wasm_module *m, const char *name,
                   const int *args, int nargs, int *result)
{
    unsigned i;
    int found = -1;
    for (i = 0; i < m->nexports; i++) {
        if (m->exports[i].kind != 0) continue;
        if (wcmp((const unsigned char *)m->exports[i].name, name,
                 m->exports[i].namelen) == 0) {
            found = (int)m->exports[i].index;
            break;
        }
    }
    if (found < 0) return WASM_ERR_NO_EXPORT;
    if ((unsigned)found >= m->nfuncs) return WASM_ERR_TYPE;
    if (m->types[m->funcs[found].type].nparams != (unsigned)nargs) return WASM_ERR_TYPE;

    m->sp = 0;
    m->lp = 0;
    m->cp = 0;
    for (i = 0; i < (unsigned)nargs; i++) {
        if (m->sp >= WASM_STACK) return WASM_ERR_STACK;
        m->stack[m->sp++] = args[i];
    }
    return do_call(m, (unsigned)found, 0, result);
}

const char *wasm_strerror(wasm_err e)
{
    if (e == WASM_OK) return "ok";
    if (e == WASM_ERR_MAGIC) return "not a wasm module";
    if (e == WASM_ERR_VERSION) return "unsupported version";
    if (e == WASM_ERR_TRUNCATED) return "truncated or malformed";
    if (e == WASM_ERR_UNSUPPORTED) return "unsupported feature";
    if (e == WASM_ERR_LIMIT) return "module exceeds a fixed limit";
    if (e == WASM_ERR_NO_EXPORT) return "no such export";
    if (e == WASM_ERR_IMPORT) return "unresolved import";
    if (e == WASM_ERR_STACK) return "stack error";
    if (e == WASM_ERR_DEPTH) return "call depth exceeded";
    if (e == WASM_ERR_MEMORY) return "memory access out of bounds";
    if (e == WASM_ERR_TRAP) return "trap";
    return "type error";
}
