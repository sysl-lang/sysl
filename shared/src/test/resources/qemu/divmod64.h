/* Sixty-four bit division for a machine whose registers are thirty-two.
 *
 * A `long` is sixty-four bits on every sysl target, so dividing one on the RP2350 is a **call**
 * rather than an instruction: neither of its cores has the divider, and the back end emits a
 * reference to a compiler-rt builtin instead. Rendering a `long` divides by ten, so
 * `lib/sysl/display.sysl` reaches this the moment a program prints one.
 *
 * The names differ by architecture and nothing else. RISC-V asks for `__divdi3` and its three
 * relatives; the ARM EABI asks for `__aeabi_ldivmod`, which answers the quotient *and* the remainder
 * from one call, in registers no C signature can name. Each board's support file spells its own; what
 * is here is the arithmetic they share, which is the part worth writing once.
 *
 * A real project gets these from `libgcc` or from compiler-rt, and pico-sdk links one of them for
 * you. This machine has no cross builtins at all -- Homebrew's clang ships a `lib` directory holding
 * `darwin` and nothing else -- so the test board supplies them exactly as it supplies `malloc`.
 *
 * Shift-and-subtract, sixty-four iterations, no lookup and no early exit. It is the slowest correct
 * long division there is, and the right one here: this file exists so that something *else* can be
 * measured, and every clever version is a thing to get wrong. Nothing in it divides, so it cannot
 * recurse into itself -- shifts, comparisons and subtraction are all instructions on both cores.
 */

#ifndef SYSL_DIVMOD64_H
#define SYSL_DIVMOD64_H

/* The quotient, with the remainder through the pointer when one is given. A zero divisor leaves
 * every bit of the quotient set and the numerator as the remainder, which is as meaningful as
 * anything else: dividing by zero is undefined, and this is what falls out of the loop.
 */
static unsigned long long sysl_udivmod64(unsigned long long n, unsigned long long d,
                                         unsigned long long *rem) {
    unsigned long long q = 0;
    unsigned long long r = 0;

    for (int i = 63; i >= 0; i--) {
        r = (r << 1) | ((n >> i) & 1u);

        if (r >= d && d != 0) {
            r -= d;
            q |= 1ull << i;
        }
    }

    if (d == 0) {
        q = ~0ull;
        r = n;
    }

    if (rem) *rem = r;

    return q;
}

/* The signed division both architectures round toward zero, so the remainder takes the numerator's
 * sign. The magnitudes are taken as *unsigned* negations, which is what makes the most negative
 * value work: `-(long long)LLONG_MIN` overflows and `-(unsigned long long)LLONG_MIN` is exactly the
 * magnitude wanted.
 */
static long long sysl_divmod64(long long n, long long d, long long *rem) {
    int negn = n < 0;
    int negd = d < 0;

    unsigned long long un = negn ? -(unsigned long long)n : (unsigned long long)n;
    unsigned long long ud = negd ? -(unsigned long long)d : (unsigned long long)d;
    unsigned long long ur;
    unsigned long long uq = sysl_udivmod64(un, ud, &ur);

    if (rem) *rem = negn ? -(long long)ur : (long long)ur;

    return (negn ^ negd) ? -(long long)uq : (long long)uq;
}

#endif
