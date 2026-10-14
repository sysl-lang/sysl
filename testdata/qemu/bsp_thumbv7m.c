/* What the board owes the library, for `mps2-an385` -- the Cortex-M3. `bsp_rv32.c` explains why this
 * file exists at all, and `bsp_thumbv7em.c` is the AN500's copy of it.
 *
 * **It is a copy rather than a shared file, and the duplication is the honest answer.** The three
 * MPS2 boards this tier uses have the identical map -- `mps.ssram1` at zero and five CMSDK UARTs from
 * 0x40004000, read out of `info mtree -f` rather than off a datasheet -- so what each owes really is
 * the same text. But a file named for one architecture and quietly used by another is something a
 * reader has to be told, and telling them is worth more than the hundred lines saved.
 *
 * **What is NOT here is the pair of 32-bit division helpers `bsp_thumbv6m.c` carries**, and that is
 * the M3 being the bigger machine rather than an oversight: Armv7-M has `sdiv` and `udiv`, so `/` on
 * an `int` is an instruction here where it is a call to `__aeabi_idiv` on an M0+. The 64-bit pair
 * is not owed either: no Cortex-M divides sixty-four bits, and `sysl.softint` carries the routines.
 *
 * **And no floating-point routine is here.** A `double` is the program's own -- `sysl.softfloat.
 * binary64` carries `__aeabi_dmul` and its family -- and nothing on this tier asks this board for an
 * `f32` one: the FPU test (`qmu_computes_with_the_fpu`) runs only where the target is not
 * `soft_float`. A real Armv7-M project that computes with `f32` owes `__aeabi_fmul` and its family,
 * which is what `no_fpu` selecting the EABI routine instead of `vmul.f32` actually amounts to.
 */

#include <stddef.h>

struct uart {
    volatile unsigned int data;
    volatile unsigned int state;
    volatile unsigned int ctrl;
    volatile unsigned int intstatus;
    volatile unsigned int bauddiv;
};

static struct uart *const UART = (struct uart *)0x40004000;

static int enabled = 0;

int putchar(int c) {
    if (!enabled) {
        UART->bauddiv = 16;             /* the device refuses anything below sixteen */
        UART->ctrl = 1;                 /* TX on */
        enabled = 1;
    }

    UART->data = (unsigned int)(unsigned char)c;
    return c;
}

static unsigned char arena[8192];
static size_t used = 0;

void *malloc(size_t n) {
    n = (n + 7u) & ~(size_t)7u;

    if (n > sizeof arena - used) return NULL;

    void *p = &arena[used];

    used += n;
    return p;
}

void free(void *p) { (void)p; }

/* The compiler emits calls to these for a structure assignment and for zeroing storage, whatever the
 * source said, so a freestanding image needs them as surely as it needs the allocator above. Byte at
 * a time: the image is measuring something else, and a word-at-a-time version is a thing to get
 * wrong for no gain here.
 */
void *memcpy(void *dst, const void *src, size_t n) {
    unsigned char *d = dst;
    const unsigned char *s = src;

    while (n--) *d++ = *s++;

    return dst;
}

void *memset(void *dst, int c, size_t n) {
    unsigned char *d = dst;

    while (n--) *d++ = (unsigned char)c;

    return dst;
}

/* Sixty-four bit division, which no Cortex-M has an instruction for, is not here: the program
 * carries it. `sysl.softint` exports `__aeabi_uldivmod` and `__aeabi_ldivmod` wherever a reached body
 * divides a `long`, so this board owes the program nothing for it.
 */
