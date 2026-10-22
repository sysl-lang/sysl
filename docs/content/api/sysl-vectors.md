---
title: sysl.vectors
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.vectors
summary: "Lane lists for a vector's `shuffle`: the rearrangements SIMD code reaches for, at every width."
requires: "no alloc"
---

`reference/vectors.md § Rearranging lanes` describes `shuffle` itself.

**Each is a `const` function answering an array of lane numbers**, which is the form `shuffle`
folds while compiling: `a.shuffle(b, zip_lo[W]())` is one `shufflevector` whose list is the
instruction's own operand, so a kernel generic in its width interleaves at four lanes and at
sixteen from one body, and the list costs nothing at run time. They are lists rather than methods
because a list is what may be named, combined and handed on; the method that applies one is the
compiler's.

**`W` is always the width of the vectors being shuffled**, never the width of the answer, and the
second vector's lanes are numbered `W` to `2W - 1` as `shuffle` numbers them. The lists that take a
pair say so; the rest pick from one vector.

## Index

[`reverse`](#reverse) [`rotate`](#rotate) [`unzip_even`](#unzip_even) [`unzip_odd`](#unzip_odd) [`zip_hi`](#zip_hi) [`zip_lo`](#zip_lo)

## Functions

### `reverse`

```sysl
const reverse[const W: usize]() -> [W]usize
```

One vector's lanes back to front. `rev64` and an `ext` on AArch64.

### `rotate`

```sysl
const rotate[const W: usize, const K: usize]() -> [W]usize
```

One vector's lanes moved `K` toward lane zero, the ones that fall off coming round at the top:
lane `i` of the answer is lane `(i + K) % W`. `ext` on AArch64.

### `unzip_even`

```sysl
const unzip_even[const W: usize]() -> [W]usize
```

Every even lane of a pair, in order: `[a0, a2, …, b0, b2, …]` -- the inverse of the two zips.
`uzp1` on AArch64.

### `unzip_odd`

```sysl
const unzip_odd[const W: usize]() -> [W]usize
```

Every odd lane of a pair, in order: `[a1, a3, …, b1, b3, …]`. `uzp2` on AArch64.

### `zip_hi`

```sysl
const zip_hi[const W: usize]() -> [W]usize
```

The high halves of two vectors, interleaved: `[a(W/2), b(W/2), …]`, `W` lanes from a pair of `W`.
`zip2` on AArch64.

### `zip_lo`

```sysl
const zip_lo[const W: usize]() -> [W]usize
```

The low halves of two vectors, interleaved: `[a0, b0, a1, b1, …]`, `W` lanes from a pair of `W`.
`zip1` on AArch64, `unpcklps` on x86.
