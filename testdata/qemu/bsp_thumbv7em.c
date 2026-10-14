/* What the board owes the library, for `mps2-an500`. `bsp_rv32.c` explains why this file exists at
 * all, and `bsp_thumb.c` is the near-identical sibling for the AN505.
 *
 * **The one thing that differs is the UART's address, and it differs by more than a digit.** The
 * AN505 puts its CMSDK UART at 0x40200000; this board puts five of them in the APB region starting
 * at **0x40004000**, and `-nographic` wires the first to QEMU's stdout. Everything else about the
 * device is the same, including the two things that make it silent: its registers are **words**
 * rather than bytes, and it **transmits nothing until it is enabled**. A program that writes to it
 * cold produces no output and no error -- QEMU says `transmit data write with Tx disabled`, and
 * only under `-d guest_errors`. There is nowhere earlier to do the enabling than the first write,
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

/* Sixty-four bit division, which no Cortex-M has an instruction for, and binary64 arithmetic, which
 * these single-precision units do not do, are not here: the program carries both. `sysl.softint`
 * exports `__aeabi_uldivmod` and `__aeabi_ldivmod` wherever a reached body divides a `long`, and
 * `sysl.softfloat.binary64` exports `__aeabi_dmul` and its family wherever one computes with an `f64`.
 */
