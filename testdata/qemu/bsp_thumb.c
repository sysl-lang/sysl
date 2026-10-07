/* What the board owes the library, for `mps2-an505`. `bsp_rv32.c` explains why this file exists at
 * all; what differs here is the UART, and it differs in more than an address.
 *
 * The CMSDK's registers are **words** rather than bytes, and it **transmits nothing until it is
 * enabled** -- a program that writes to it cold produces no output and no error, and QEMU mentions
 * it only under `-d guest_errors`. There is nowhere earlier to do the enabling than the first write,
 * since the library calls `putchar` and nothing else, so it is done once behind a flag.
 */

#include <stddef.h>

struct uart {
    volatile unsigned int data;
    volatile unsigned int state;
    volatile unsigned int ctrl;
    volatile unsigned int intstatus;
    volatile unsigned int bauddiv;
};

static struct uart *const UART = (struct uart *)0x40200000;

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

/* Sixty-four bit division, which a Cortex-M33 has no instruction for. `divmod64.h` has the
 * arithmetic and says why the board owes it; what is peculiar here is the **interface**.
 *
 * The ARM EABI asks for one function that answers the quotient *and* the remainder --
 * `__aeabi_ldivmod` returns the quotient in r0:r1 and the remainder in r2:r3 -- and no C signature
 * can say that. So each is a naked wrapper around an ordinary C helper that returns the quotient and
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

