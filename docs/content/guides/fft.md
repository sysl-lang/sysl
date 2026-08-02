---
title: fft
summary: Arithmetic on a type the program defined, and the transform kept beside the definition it rearranges.
weight: 50
---

The discrete Fourier transform, twice: the O(n log n) one everybody uses and the O(n²) one that is
the definition.

**Having both is the point.** The fast transform is a *rearrangement* of the slow one, and a
rearrangement is exactly the kind of thing that can be subtly wrong and still produce plausible
numbers — so the slow one is kept as the thing to compare against. Published values then anchor the
pair, since two implementations by the same author agreeing proves only that they agree.

**The axis: arithmetic on a type the program defined.** `Complex` is an ordinary struct with
[operator implementations](/reference/expressions/), so `a * b + c` over it is written the way it is
written over a number. Nothing in the language knows what a complex number is; the operator traits are
the whole mechanism, and this is the program that leans on them hardest.

## What it found

**The width of an integer type is askable, and the first draft said it was not.** This is a finding
that got *retracted*, which is worth as much as one that stands. The header originally recorded that
the machine word width could not be obtained at a type parameter; it can —
`count_ones() + count_zeros()` is the width by construction, at whatever `Self` turned out to be. That
is what [`sysl.math`](/library/math/) means by keeping the second of that pair rather than leaving it
to a subtraction, and it means nothing has to hard-code 64.

**What actually stops the closed form is the empty shift.** `Bits.reverse_bits` reverses the whole
width, so bit-reversing an index would be `v.reverse_bits() >> (w - bits)` — and a one-sample
transform reaches that with `bits` at zero, since `bit_width(1)` is `trailing_zeros(1)`, which is
zero. A shift by the full width is the case the instruction does not define, so the loop stays.

That is the more useful shape of finding: not "the language cannot express this" but "the language
expresses it and the *machine* has an edge case", which no amount of language design removes.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/fft) ·
Next: [sha2](/guides/sha2/) — one algorithm at two widths.
