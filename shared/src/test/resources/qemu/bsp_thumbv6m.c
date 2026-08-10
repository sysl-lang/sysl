/* What the board owes the library, for `microbit` — an nRF51822, whose Cortex-M0 is the same
 * architecture as the RP2040's Cortex-M0+. `bsp_rv32.c` explains why this file exists at all, and
 * `bsp_thumb.c` is the nearest relative: the M33 half of the same tier.
 *
 * Two things differ from that file, and only one of them is the UART.
 */

#include <stddef.h>

/* **The nRF51's UART is a peripheral to be started, not a register to be written.** The CMSDK on the
 * MPS2 needed a baud divisor and an enable bit; this one needs a *pin*, a baud rate, an enable, and
 * then a task started — and it reports each byte through an event that has to be cleared before the
 * next one, because the flag is level-held and a second byte written without clearing it spins
 * forever on a stale ready.
 *
 * The offsets are the nRF51 manual's and are written as offsets rather than as a struct: they are
 * scattered across two pages of the register map — tasks at the bottom, events in the middle,
 * configuration at 0x500 — so a struct would be a hundred words of padding with five useful fields
 * in it.
 *
 * `PSELTXD = 24` is the **micro:bit's** wiring rather than the chip's: the nRF51 can put its
 * transmitter on any pin, and this board runs it to the USB bridge on pin 24. That single number is
 * the whole of what makes this file board-specific rather than chip-specific.
 */
#define UART(off) (*(volatile unsigned int *)(0x40002000u + (off)))

#define TASKS_STARTTX  0x008
#define EVENTS_TXDRDY  0x11C
#define ENABLE         0x500
#define PSELTXD        0x50C
#define TXD            0x51C
#define BAUDRATE       0x524

static int enabled = 0;

int putchar(int c) {
    if (!enabled) {
        UART(PSELTXD)  = 24;
        UART(BAUDRATE) = 0x01D7E000; /* 115200 */
        UART(ENABLE)   = 4;
        UART(TASKS_STARTTX) = 1;
        enabled = 1;
    }

    UART(EVENTS_TXDRDY) = 0;
    UART(TXD) = (unsigned int)(unsigned char)c;

    while (!UART(EVENTS_TXDRDY)) { }

    return c;
}

/* 16K of SRAM on this part against the MPS2's 32K, and the stack descends through the same region,
 * so the arena is a quarter of that file's. It is still far more than this tier's programs ask for,
 * and a program that outran it would get a null back and say so rather than quietly overwriting the
 * stack.
 */
static unsigned char arena[2048];
static size_t used = 0;

void *malloc(size_t n) {
    n = (n + 7u) & ~(size_t)7u;

    if (n > sizeof arena - used) return NULL;

    void *p = &arena[used];

    used += n;
    return p;
}

void free(void *p) { (void)p; }

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

/* **The second difference, and the one that is about the architecture rather than the board: this
 * core has no divider at all.**
 *
 * The M33 has `sdiv` and `udiv`, so `bsp_thumb.c` owes only the sixty-four bit helpers. Armv6-M has
 * neither, so *thirty-two* bit division is a call as well, and a program that divides an `int` — or
 * renders one, since printing divides by ten — fails to link without these four.
 *
 * The EABI's shape is the awkward part, and it is the same awkwardness as `__aeabi_ldivmod`: the
 * `divmod` forms answer the quotient **and** the remainder from one call, in two registers no C
 * signature can name. So each is a naked wrapper around an ordinary helper, and the assembly is
 * marshalling and nothing else.
 */
static unsigned int udivmod32(unsigned int n, unsigned int d, unsigned int *rem) {
    unsigned int q = 0;
    unsigned int r = 0;

    if (d == 0) { if (rem) *rem = 0; return 0; }   /* the caller's mistake, not a fault here */

    for (int i = 31; i >= 0; i--) {
        r = (r << 1) | ((n >> i) & 1u);

        if (r >= d) { r -= d; q |= 1u << i; }
    }

    if (rem) *rem = r;

    return q;
}

unsigned int __aeabi_uidiv(unsigned int n, unsigned int d) {
    return udivmod32(n, d, NULL);
}

int __aeabi_idiv(int n, int d) {
    int neg = (n < 0) ^ (d < 0);
    unsigned int q = udivmod32((unsigned int)(n < 0 ? -n : n),
                               (unsigned int)(d < 0 ? -d : d), NULL);

    return neg ? -(int)q : (int)q;
}

/* The quotient goes back in r0 and the remainder in r1, which is what the `divmod` names promise and
 * what the compiler's call site reads. `push {r4, lr}` keeps the stack eight-byte aligned at the
 * call, which AAPCS requires and which a violation would report as something else entirely.
 */
unsigned int __sysl_uidivmod(unsigned int n, unsigned int d, unsigned int *rem) {
    return udivmod32(n, d, rem);
}

int __sysl_idivmod(int n, int d, int *rem) {
    unsigned int r;
    int neg = (n < 0) ^ (d < 0);
    unsigned int q = udivmod32((unsigned int)(n < 0 ? -n : n),
                               (unsigned int)(d < 0 ? -d : d), &r);

    /* C truncates toward zero, so the remainder takes the *numerator's* sign and not the divisor's. */
    if (rem) *rem = n < 0 ? -(int)r : (int)r;

    return neg ? -(int)q : (int)q;
}

__attribute__((naked)) unsigned int __aeabi_uidivmod(unsigned int n, unsigned int d) {
    __asm__ volatile("push {r4, lr}\n"
                     "sub  sp, sp, #8\n"
                     "mov  r2, sp\n"
                     "bl   __sysl_uidivmod\n"
                     "ldr  r1, [sp, #0]\n"
                     "add  sp, sp, #8\n"
                     "pop  {r4, pc}\n");
}

__attribute__((naked)) int __aeabi_idivmod(int n, int d) {
    __asm__ volatile("push {r4, lr}\n"
                     "sub  sp, sp, #8\n"
                     "mov  r2, sp\n"
                     "bl   __sysl_idivmod\n"
                     "ldr  r1, [sp, #0]\n"
                     "add  sp, sp, #8\n"
                     "pop  {r4, pc}\n");
}

/* **Sixty-four bit shifts, which this core has no instruction for either.**
 *
 * The M33 does them inline as a pair of Thumb-2 sequences, so `bsp_thumb.c` never hears about them.
 * Armv6-M cannot, so `divmod64.h` — which shifts a `long long` on every iteration of its long
 * division — turns into calls to `__aeabi_llsr` and `__aeabi_llsl`, and the link fails naming those
 * two and nothing else.
 *
 * **They must not be written with the operator they implement.** `return v >> n` on a `long long`
 * compiles to a call to `__aeabi_llsr`, which is this function: it would assemble, link, and recurse
 * until the stack ran out. So each is written on the two halves, in thirty-two bit shifts the core
 * does have.
 *
 * The `n == 0` case is separate because shifting a thirty-two bit value by thirty-two is undefined
 * in C and is a no-op on Arm, which between them would silently drop the low half.
 *
 * The EABI wants the value in r0:r1 and the count in r2, answering in r0:r1 — which is what AAPCS
 * already does with this signature, so these need no assembly wrapper the way the `divmod` pair
 * does.
 */
unsigned long long __aeabi_llsr(unsigned long long v, int n) {
    unsigned int lo = (unsigned int)v;
    unsigned int hi = (unsigned int)(v >> 32 & 0xffffffffu);

    if (n == 0) return v;

    if (n >= 32) { lo = hi >> (n - 32); hi = 0; }
    else         { lo = (lo >> n) | (hi << (32 - n)); hi = hi >> n; }

    return ((unsigned long long)hi << 32) | lo;
}

unsigned long long __aeabi_llsl(unsigned long long v, int n) {
    unsigned int lo = (unsigned int)v;
    unsigned int hi = (unsigned int)(v >> 32 & 0xffffffffu);

    if (n == 0) return v;

    if (n >= 32) { hi = lo << (n - 32); lo = 0; }
    else         { hi = (hi << n) | (lo >> (32 - n)); lo = lo << n; }

    return ((unsigned long long)hi << 32) | lo;
}

/* The arithmetic one, for a shift of a *signed* `long long`. Nothing here reaches it today —
 * `divmod64.h` shifts unsigned — but it is four lines beside its siblings and a link error away
 * otherwise, and the sign-extension is the part somebody would get wrong in a hurry.
 */
long long __aeabi_lasr(long long v, int n) {
    unsigned int lo = (unsigned int)v;
    int          hi = (int)((unsigned long long)v >> 32 & 0xffffffffu);

    if (n == 0) return v;

    if (n >= 32) { lo = (unsigned int)(hi >> (n - 32)); hi = hi >> 31; }
    else         { lo = (lo >> n) | ((unsigned int)hi << (32 - n)); hi = hi >> n; }

    return (long long)(((unsigned long long)(unsigned int)hi << 32) | lo);
}

/* **Sixty-four bit multiply, which needs more care than the shifts because the obvious version
 * recurses twice over.**
 *
 * Armv6-M has `muls`, a thirty-two by thirty-two multiply keeping the *low* thirty-two bits, and
 * that is all. It has no `umull`, so even a 32x32 product needing sixty-four bits of answer is a
 * call — and writing that call's body as `(unsigned long long)a * b` compiles to `__aeabi_lmul`,
 * which is this function. So the partial products are taken on **sixteen** bit halves, where every
 * product fits in thirty-two bits and `muls` is enough.
 *
 * Only the low sixty-four bits of the answer are wanted, which is what makes the cross terms cheap:
 * `ahi * bhi` lands entirely above bit 63 and is dropped, so the two cross products need only their
 * low halves.
 */
static unsigned long long mul32(unsigned int a, unsigned int b) {
    unsigned int al = a & 0xffffu, ah = a >> 16;
    unsigned int bl = b & 0xffffu, bh = b >> 16;

    unsigned int ll = al * bl;
    unsigned int mid = al * bh + ah * bl;      /* may carry out of bit 31 */
    unsigned int carry = mid < al * bh ? 0x10000u : 0u;
    unsigned int lo = ll + (mid << 16);
    unsigned int hi = ah * bh + (mid >> 16) + carry + (lo < ll ? 1u : 0u);

    return ((unsigned long long)hi << 32) | lo;
}

long long __aeabi_lmul(long long x, long long y) {
    unsigned long long a = (unsigned long long)x;
    unsigned long long b = (unsigned long long)y;

    unsigned int alo = (unsigned int)a, ahi = (unsigned int)(a >> 32);
    unsigned int blo = (unsigned int)b, bhi = (unsigned int)(b >> 32);

    unsigned int cross = alo * bhi + ahi * blo;

    return (long long)(mul32(alo, blo) + ((unsigned long long)cross << 32));
}

/* **The EABI's own names for the block operations**, which clang emits in place of the C ones for
 * this triple. They are not spelling variants: `__aeabi_memset` takes its length and its byte in
 * the *opposite* order to `memset`, which is the kind of difference that produces a program filled
 * with the wrong value rather than a link error.
 */
void __aeabi_memcpy(void *dst, const void *src, size_t n)  { memcpy(dst, src, n); }
void __aeabi_memcpy4(void *dst, const void *src, size_t n) { memcpy(dst, src, n); }
void __aeabi_memcpy8(void *dst, const void *src, size_t n) { memcpy(dst, src, n); }

void __aeabi_memset(void *dst, size_t n, int c)  { memset(dst, c, n); }
void __aeabi_memset4(void *dst, size_t n, int c) { memset(dst, c, n); }
void __aeabi_memset8(void *dst, size_t n, int c) { memset(dst, c, n); }

void __aeabi_memclr(void *dst, size_t n)  { memset(dst, 0, n); }
void __aeabi_memclr4(void *dst, size_t n) { memset(dst, 0, n); }
void __aeabi_memclr8(void *dst, size_t n) { memset(dst, 0, n); }

/* And the sixty-four bit division pair, identical to the M33's because the EABI is the same — only
 * the core underneath it changed. `divmod64.h` has the arithmetic and says why the board owes it.
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
