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
 * below is still owed, because no Cortex-M divides sixty-four bits.
 *
 * **And no floating-point routine is here**, because nothing on this tier asks this board for one:
 * the FPU test in `QemuRunTests` runs only where `hardFloat`. A real Armv7-M project that computes
 * with floats owes `__aeabi_fmul` and its family exactly as it owes these, and that requirement is
 * what `Target.noFpu` selecting the EABI routine instead of `vmul.f32` actually amounts to.
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

/* Sixty-four bit division, which no Cortex-M has an instruction for -- an M3 has a 32-bit divider
 * and stops there. `divmod64.h` has the arithmetic and says why the board owes it; the **interface**
 * is the peculiar part, and it is the ARM EABI's rather than this board's.
 *
 * `__aeabi_ldivmod` answers the quotient in r0:r1 *and* the remainder in r2:r3, which no C signature
 * can say. So each is a naked wrapper around an ordinary C helper that returns the quotient and
 * writes the remainder through a pointer, and the four lines of assembly are the marshalling: the
 * numerator and divisor are already in r0-r3 where the helper wants them, the fifth argument goes on
 * the stack because that is where the fifth argument goes, and the remainder is loaded back into
 * r2:r3 on the way out.
 *
 * Both wrappers push an even number of registers and subtract a multiple of eight, so the stack is
 * eight-byte aligned at the call, which AAPCS requires and which a fault would report as something
 * else entirely.
 */
#include "divmod64.h"

unsigned long long __sysl_uldivmod(unsigned long long n, unsigned long long d,
                                   unsigned long long *rem) {
    return sysl_udivmod64(n, d, rem);
}

long long __sysl_ldivmod(long long n, long long d, long long *rem) {
    return sysl_divmod64(n, d, rem);
}

__attribute__((naked)) unsigned long long __aeabi_uldivmod(unsigned long long n,
                                                           unsigned long long d) {
    __asm__ volatile("push {r4, lr}\n"
                     "sub  sp, sp, #16\n"
                     "add  r4, sp, #8\n"
                     "str  r4, [sp, #0]\n"
                     "bl   __sysl_uldivmod\n"
                     "ldr  r2, [sp, #8]\n"
                     "ldr  r3, [sp, #12]\n"
                     "add  sp, sp, #16\n"
                     "pop  {r4, pc}\n");
}

__attribute__((naked)) long long __aeabi_ldivmod(long long n, long long d) {
    __asm__ volatile("push {r4, lr}\n"
                     "sub  sp, sp, #16\n"
                     "add  r4, sp, #8\n"
                     "str  r4, [sp, #0]\n"
                     "bl   __sysl_ldivmod\n"
                     "ldr  r2, [sp, #8]\n"
                     "ldr  r3, [sp, #12]\n"
                     "add  sp, sp, #16\n"
                     "pop  {r4, pc}\n");
}
