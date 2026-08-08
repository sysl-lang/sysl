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

