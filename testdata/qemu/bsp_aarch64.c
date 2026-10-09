/* What the board owes the library, for the AArch64 `virt` machine -- `bsp_rv32.c` says what each of
 * these is for. The UART is a PL011, which takes a byte at offset zero and needs no setting up under
 * QEMU.
 */

#include <stddef.h>

static volatile unsigned int *const UART = (volatile unsigned int *)0x09000000;

int putchar(int c) {
    *UART = (unsigned int)(unsigned char)c;
    return c;
}

static unsigned char arena[65536] __attribute__((aligned(16)));
static size_t used = 0;

void *malloc(size_t n) {
    n = (n + 15u) & ~(size_t)15u;

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

void *memmove(void *dst, const void *src, size_t n) {
    unsigned char *d = dst;
    const unsigned char *s = src;

    if (d < s) {
        while (n--) *d++ = *s++;
    } else {
        while (n--) d[n] = s[n];
    }

    return dst;
}

void *memset(void *dst, int c, size_t n) {
    unsigned char *d = dst;

    while (n--) *d++ = (unsigned char)c;

    return dst;
}

int memcmp(const void *a, const void *b, size_t n) {
    const unsigned char *x = a;
    const unsigned char *y = b;

    for (; n; n--, x++, y++)
        if (*x != *y) return *x - *y;

    return 0;
}
