/*
 * Freestanding C support for JVMOS.
 *
 * This is the foundation for the planned WebAssembly sandbox. A WASM
 * interpreter is one large dispatch over ~170 opcodes with unsigned
 * arithmetic and pointer work, which is exactly what C is good at and what
 * the Java side of this system cannot express: no switch, no unsigned types,
 * at most ~27 local slots per method, and every field must live in one class.
 *
 * Nothing here is WASM yet. The first job is to prove that the C toolchain
 * links into the kernel at all and that the ASM dispatcher can call into it,
 * because the build declares gcc but has never actually compiled a C file.
 *
 * Constraints, since this runs in ring 0 with no runtime underneath:
 *   - no libc, no floating point helpers, no stack protector
 *   - no SSE: the kernel never sets CR4.OSFXSR, so an SSE instruction faults
 *     (the Makefile passes -mno-sse/-mno-sse2 to keep gcc away from them)
 *   - gcc may still emit calls to memset/memcpy for struct or array work even
 *     with -fno-builtin, so freestanding code has to provide them itself
 */

typedef unsigned char u8;
typedef unsigned int u32;

void *memset(void *dst, int value, unsigned long n)
{
    u8 *p = (u8 *)dst;
    while (n--) {
        *p++ = (u8)value;
    }
    return dst;
}

void *memcpy(void *dst, const void *src, unsigned long n)
{
    u8 *d = (u8 *)dst;
    const u8 *s = (const u8 *)src;
    while (n--) {
        *d++ = *s++;
    }
    return dst;
}

int memcmp(const void *a, const void *b, unsigned long n)
{
    const u8 *x = (const u8 *)a;
    const u8 *y = (const u8 *)b;
    while (n--) {
        if (*x != *y) {
            return (int)*x - (int)*y;
        }
        x++;
        y++;
    }
    return 0;
}

/*
 * Returns 'WASM' as a 32-bit value, built at runtime rather than returned as a
 * constant so the check actually exercises the stack, byte stores and the
 * cdecl return path instead of being folded away by the optimiser.
 */
int c_selftest(void)
{
    volatile u8 probe[4];
    probe[0] = 'W';
    probe[1] = 'A';
    probe[2] = 'S';
    probe[3] = 'M';
    return ((int)probe[0] << 24) | ((int)probe[1] << 16) |
           ((int)probe[2] << 8) | (int)probe[3];
}
