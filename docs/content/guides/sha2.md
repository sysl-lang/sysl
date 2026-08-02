---
title: sha2
summary: Generic arithmetic — one implementation serving four hash functions across two word widths.
weight: 60
---

SHA-224, SHA-256, SHA-384 and SHA-512, plus HMAC over them: one implementation, two word widths.

**The axis: generic arithmetic.** The message schedule, the round function, the padding, the
streaming buffer and the digest are written once and serve four functions. What varies by *width* is
reached through a `Word` bound — the width itself, the round count, the constant table and the four
mixing functions. What varies per **digest** rather than per width is an argument instead: SHA-224 and
SHA-256 are the same word type, and a type may implement a trait once at a given argument list.

That last sentence is the design constraint doing real work rather than a limitation being worked
around. Two things that differ per width belong in the [bound](/reference/generics/); two things that
differ within one width cannot, and become parameters. The program is the demonstration that the line
falls in a usable place.

## What it exercises

**A conversion may be written at a type parameter, in both directions.** `T(b)` builds a word out of a
byte and `u8(x)` takes one back out, each resolved once the instantiation says what the width is. The
pair being symmetric is what lets the byte-order code be written once — bytes arrive most significant
first, so a word is built by shifting each one in from the bottom, and that loop is the same code at
32 and 64 bits.

**A bound written at a type is inherited by every member.** The hash-in-progress struct declares
`Word` once, and its members do not restate it. That is the answer to the repetition the free
functions above it show, and it is the concrete reason a container is a better place to put a bound
than a pile of functions is — a point the [generics reference](/reference/generics/) states as a rule
and this program measures in lines.

**Static tables.** The round constants are large fixed arrays of words, which is the other half of
what "generic arithmetic" needs: a table per width, reachable from generic code, with no allocation
and no initialization step. That is a [module-level `val`](/reference/declarations/) doing exactly
what it is for.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/sha2) ·
Next: [shapes](/guides/shapes/) — the dynamic half of the trait system.
