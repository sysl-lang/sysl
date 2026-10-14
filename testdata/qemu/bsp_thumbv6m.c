/* What the board owes the library, for `microbit` — an nRF51822, whose Cortex-M0 is the same
 * architecture as the RP2040's Cortex-M0+. `bsp_rv32.c` explains why this file exists at all, and
 * `bsp_thumb.c` is the nearest relative: the M33 half of the same tier.
 *
 * The UART is what differs from that file, and the arena's size.
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

/* **What this core lacks is not here**, though it lacks more than any other board's: no divider at
 * all, no long multiply, no `clz` and no two-word shift, so a 32-bit division, a 64-bit multiply or
 * variable shift, a leading-zero count and the run-time ABI's `__aeabi_memcpy` family are each a call.
 * The program carries every one: `sysl.softint` exports them, weak, in every build for this core, and
 * `sysl.softfloat.binary64` the `f32` and `f64` routines a core with no floating-point unit calls.
 * That is why `memcpy` and `memset` above are all the block operations this file owes.
 */
