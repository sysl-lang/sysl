---
title: sysl.rand
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.rand
summary: "Pseudo-random numbers: a named, seedable, reproducible generator, and the distributions that are easy to get wrong."
requires: "no alloc"
---

**This is NOT a source of unpredictability, and nothing here should be used as one.** The generator
is fast, small and completely determined by its seed -- which is exactly what a test that must
reproduce, a simulation that must replay and a shuffle all want, and exactly what a key, a token, a
nonce or a password reset must not have. Anyone who can see a handful of outputs can compute the
state and every output that follows. A cryptographic generator is a different thing with a
different implementation, and it is not in this module under any name.

**The algorithm is named so it can be checked.** A generator nobody can identify is one nobody can
verify against published output, so this is PCG32 -- specifically PCG-XSH-RR with a 64-bit state
and 32-bit output, M. E. O'Neill, *PCG: A Family of Simple Fast Space-Efficient Statistically Good
Algorithms for Random Number Generation*, 2014 -- and `tests.sysl` pins it against the reference
implementation's own first outputs. It was chosen over xoshiro because its state is two words, its
multiplier and increment are published constants a reader can check against the paper, and it has a
canonical reference implementation to take a vector from.

**Seeding is the caller's, and that is what keeps this module portable.** Nothing here reads a
clock or asks the operating system for anything, so it compiles and runs on a freestanding target.
Taking a seed from the host is `sysl.posix.rand`, a module of its own, so that importing the
generator cannot drag an operating system in behind it.

## Index

[`rng`](#rng) [`Rng`](#rng-1)

## Functions

### `rng`

```sysl
rng(seed: u64, seq: u64) -> Rng
```

A generator from a seed and a stream selector.

**Two arguments, because the stream is not the seed.** Two generators with the same seed and
different `seq` produce different, equally good sequences -- which is what a program wanting
several independent streams from one seed needs, and what seeding several generators from
consecutive seeds does *not* reliably give.

The initialization is the reference implementation's: the increment is made odd, the state is
advanced once, the seed is added, and the state is advanced again. The two steps are what stop a
seed of zero from being a special case.

## Types

### `Rng`

```sysl
struct Rng
    state: u64
    inc: u64
```

A generator: sixty-four bits of state, and the odd increment that selects one of 2^63 distinct
streams.

A copy of an `Rng` is an independent generator continuing from the same point, which is a useful
thing rather than an accident -- it is how a simulation replays a branch from a saved position.

| Member | Signature | Description |
|---|---|---|
| `next_u32` | `next_u32(*self) -> u32` | The next 32 bits. |
| `next_u64` | `next_u64(*self) -> u64` | Sixty-four bits, as two draws with the first taken as the high half. |
| `boolean` | `boolean(*self) -> bool` | One bit, taken from the **top** of a draw rather than the bottom. |
| `below` | `below(*self, n: u64) -> u64` | A uniform value in `[0, n)`, with **no modulo bias**. |
| `range` | `range(*self, lo: i64, hi: i64) -> i64` | A uniform value in `[lo, hi)`, empty when `hi` is not above `lo`. |
| `unit` | `unit(*self) -> real` | A uniform value in `[0, 1)`, from **53** bits. |
| `shuffle` | `shuffle[T](*self, xs: []T)` | The elements rearranged, every permutation equally likely. |
