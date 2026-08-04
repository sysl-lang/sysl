---
title: fft
summary: A transform kept beside the definition it rearranges, and checked against it.
weight: 50
---

The discrete Fourier transform, twice: the O(n log n) one everybody uses and the O(n²) one that is
the definition.

**Having both is the point.** The fast transform is a *rearrangement* of the slow one, and a
rearrangement is exactly the kind of thing that can be subtly wrong and still produce plausible
numbers — so the slow one is kept as the thing to compare against. Published values then anchor the
pair, since two implementations by the same author agreeing proves only that they agree.

**The axis: an algorithm checked against its own definition.** Every other program in the set is
checked against values somebody else wrote down, and this one is too — but it also carries the thing
it is supposed to be equal to, and runs it. A spot check accepts a plausible number; the definition
does not.

**The complex arithmetic used to be this program's own, and is now
[`sysl.math.complex`](/library/complex/).** That is the ordinary end of a guide program's life: it
was written here because there was nothing to import, the friction it turned up is what moved the
language, and once the type existed there was no reason for a second copy of it. Nothing in the
language knows what a complex number is — the [operator traits](/reference/expressions/) are still
the whole mechanism, and the program still leans on them hardest.

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

**A trait written for somebody else's type has to find a name that is still free.** What survived the
move to the library is `sum` — the generic total that starts at a value the type promised rather than
at `xs[0]`, which is what keeps an empty sequence from needing an `Option` in the signature. The
trait behind it wanted a `zero() -> Self`, and could not have one: a trait's members become the
implementing type's, and `Complex` already declares a `zero`. So the member is called `identity`, for
what it is rather than for the value it answers. The more ordinary the concept, the likelier that
collision is — and there is no way to name the member the trait *means* apart from the name it goes
under.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/fft) ·
Next: [sha2](/guides/sha2/) — one algorithm at two widths.
