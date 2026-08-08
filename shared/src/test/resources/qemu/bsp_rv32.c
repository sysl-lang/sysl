/* What the board owes the library, for `virt`.
 *
 * A freestanding sysl program is not a program that touches nothing outside itself. Two things
 * reach C by name and neither is optional:
 *
 *   - `putchar`, which is what `sysl.putbytes` is written in terms of, so every `print` and every
 *     `Display` rendering that goes to standard output ends here;
 *   - `free`, which ARC calls when the last reference to heap storage goes -- and it is *named*
 *     whether or not it is reached, because a call to a function nothing defines is a link error
 *     even on a path never taken (ticket 0037).
 *
 * So this file is the board support package, and it is what a real bare-metal project has too:
 * pico-sdk and newlib-nano supply exactly these. It is not a way around anything -- a program that
 * genuinely allocates needs an allocator, and one that prints needs somewhere to print.
 *
 * The arena is a bump allocator and `free` returns storage to nobody. That is the right shape for a
 * self-test image, which runs once and stops: reuse would cost a free list, and a free list is a
 * thing to get wrong in the code that is meant to be measuring something else.
 */

#include <stddef.h>

/* `virt`'s 16550, which takes a byte at offset zero and needs no setting up. `-nographic` pipes it
 * to the emulator's own standard output. */
static volatile unsigned char *const UART = (volatile unsigned char *)0x10000000;

int putchar(int c) {
    *UART = (unsigned char)c;
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
