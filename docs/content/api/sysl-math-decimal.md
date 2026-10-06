---
title: sysl.math.decimal
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.math.decimal
summary: "Exact decimal arithmetic: an integer coefficient and a scale, so `0.1 + 0.2` is `0.3`."
---

**A general-purpose language without one makes every financial program wrong in the same way.**
`real` is binary floating point, and a tenth is not representable in binary any more than a third
is in decimal -- so `0.1 + 0.2` is `0.30000000000000004`, a total of a million transactions is out
by cents, and no amount of care at the call site fixes it. PostgreSQL's `numeric`, Java's
`BigDecimal` and Python's `decimal` are all this type. It belongs in the library rather than in a
package for the reason `sysl.log` does: a program that does not find one here writes its own, and
two of them in one process is two rounding policies that disagree about money.

## The representation

A **coefficient**, which is a `BigInt`, and a **scale**, which is how many digits of it are after
the point. `12.340` is a coefficient of `12340` at a scale of 3, and `1234` at a scale of 2 is the
same *number* written to fewer places.

**The scale is part of the value and is not normalized away.** `1.50` and `1.5` compare equal and
render differently, which is what a program handling money needs: a price quoted to the cent is
quoted to the cent whether or not the cent happens to be zero, and a total that rendered as `1.5`
where every other row said `1.50` would be a bug report. `Hash` is over the normalized form so
that the two still hash alike, which is what `Eq` obliges it to do.

**The scale is never negative.** Every operation here produces one that is zero or more, and the
two that take a scale from the caller refuse a negative. A negative scale would mean a
coefficient standing for a multiple of ten and would make the rendering a second case for no
gain -- `1500` at a scale of zero says the same thing.

## What it costs

**Everything is `BigInt` arithmetic, so everything allocates**, and adding two values at different
scales additionally multiplies one coefficient by a power of ten. That is the price of being exact
and it is not small; a program adding a million rows should keep them at one scale, where the
alignment is free.

**Addition, subtraction and multiplication are exact and never round.** A sum's scale is the
larger of its operands' and a product's is the sum of theirs, so a product of two prices at two
decimal places has four. Division is the operation that cannot be exact -- a third has no finite
decimal expansion -- so it is the one that takes a scale and a rounding mode, and it is the only
place in the module where a digit is ever discarded.

## Index

[`add`](#add) [`cmp`](#cmp) [`divide`](#divide) [`from_int`](#from_int) [`magnitude`](#magnitude) [`mul`](#mul) [`negated`](#negated) [`normalized`](#normalized) [`of`](#of) [`one`](#one) [`parse`](#parse) [`rescale`](#rescale) [`sub`](#sub) [`to_string`](#to_string) [`zero`](#zero) [`Decimal`](#decimal) [`Rounding`](#rounding) [Add for Decimal](#add-for-decimal) [Display for Decimal](#display-for-decimal) [Div for Decimal](#div-for-decimal) [Eq for Decimal](#eq-for-decimal) [From for Decimal](#from-for-decimal) [Hash for Decimal](#hash-for-decimal) [Mul for Decimal](#mul-for-decimal) [Neg for Decimal](#neg-for-decimal) [Ord for Decimal](#ord-for-decimal) [Sub for Decimal](#sub-for-decimal)

## Functions

### `add`

```sysl
add(a: Decimal, b: Decimal) -> Decimal
```

`a + b`, exactly, at the larger of the two scales.

**The result's scale is the larger and never smaller**, which is what makes addition exact: a
value at two places and one at four have a sum that needs four, and truncating it to two would be
a rounding nobody asked for.

### `cmp`

```sysl
cmp(a: Decimal, b: Decimal) -> int
```

`-1`, `0` or `1` as `a` is below, equal to or above `b`, **ignoring the scale**: `1.50` and `1.5`
are the same number and compare equal.

Both are lifted to the larger scale and their coefficients compared, which is exact and is the
only comparison that could be.

### `divide`

```sysl
divide(a: Decimal, b: Decimal, scale: int, mode: Rounding) -> Decimal
```

`a / b` to `scale` places, rounded by `mode`.

**Division is the one operation here that takes a scale, because it is the one that cannot be
exact.** A third has no finite decimal expansion, so there is no scale a quotient "naturally" has
and any choice this module made for the caller would be wrong for somebody. `divide(a, b, 2,
Rounding.HalfEven)` says what is wanted and says how the digits that do not fit are resolved.

**Division by zero traps**, as it does for a `BigInt` and for a machine integer.

### `from_int`

```sysl
from_int(n: long) -> Decimal
```

The whole number `n`, at a scale of zero.

### `magnitude`

```sysl
magnitude(d: Decimal) -> Decimal
```

The magnitude.

### `mul`

```sysl
mul(a: Decimal, b: Decimal) -> Decimal
```

`a * b`, exactly, at the SUM of the two scales.

That is what exactness costs and it is worth expecting: two prices at two places multiply to a
value at four, and a program that wants two back asks for them with `rescale`. Anything else would
be rounding a product without being told how.

### `negated`

```sysl
negated(d: Decimal) -> Decimal
```

The same value with the other sign.

### `normalized`

```sysl
normalized(d: Decimal) -> Decimal
```

The same value with every trailing zero taken off the coefficient, and the scale reduced to
match -- so `1.500` becomes `1.5` and `150` at a scale of zero is left alone.

**It is what `Hash` is over**, since two values that compare equal have to hash alike and `1.50`
equals `1.5`. A program rendering a value should NOT normalize first: the scale is the number of
places it was quoted to, and dropping it is throwing information away.

### `of`

```sysl
of(coef: BigInt, scale: int) -> Decimal
```

`coef` divided by ten to the `scale` -- the constructor a program reading a database column
reaches for, since that is exactly how such a column is stored.

**A negative scale traps**, which is the module's one representation rule enforced at the one
place a caller could break it.

### `one`

```sysl
one() -> Decimal
```

One, at a scale of zero.

### `parse`

```sysl
parse(s: string) -> Option[Decimal]
```

The value `s` names, or `None`.

An optional sign, then digits with at most one point among them -- and the scale is however many
digits came after it, so `"12.340"` is a scale of three and `"12.34"` a scale of two. `".5"` is
read as a half; `"1."` is not read at all, a point with no digit after it being a mistake rather
than a spelling.

**There is no exponent form**, deliberately. `1e-3` names a value and says nothing about the scale
it should be written to, so accepting it would mean this module choosing one -- and the scale is
the thing a caller is trusting the round trip with. A program reading exponent notation splits it
and calls `rescale`.

Nothing else: no underscores, no spaces, no thousands separator. What a document permits is the
document's business, and stripping is one call before this one.

### `rescale`

```sysl
rescale(d: Decimal, scale: int, mode: Rounding) -> Decimal
```

The same value written to `scale` places, rounded by `mode` where digits have to go.

Growing the scale is exact and needs no mode; shrinking it discards digits and is where the mode
is read. `rescale(d, d.scale(), mode)` is `d`.

### `sub`

```sysl
sub(a: Decimal, b: Decimal) -> Decimal
```

`a - b`, exactly, at the larger of the two scales.

### `to_string`

```sysl
to_string(d: Decimal) -> string
```

The value written out, with a point where the scale asks for one.

A scale larger than the coefficient has digits pads with zeros after the point and writes a
leading `0` before it, so `of(big_of(5), 3)` is `"0.005"` rather than `".005"`. A scale of zero
writes no point at all.

### `zero`

```sysl
zero() -> Decimal
```

Zero, at a scale of zero.

## Types

### `Decimal`

```sysl
struct Decimal
    private[decimal] coef: BigInt
    private[decimal] places: i32
```

A number written in decimal, exactly.

The value is the coefficient divided by ten to the scale, and both halves are what a caller
reading the type wants to be able to see: `coefficient` and `scale` are how a program storing one
in a database column or sending one over a wire takes it apart.

| Member | Signature | Description |
|---|---|---|
| `coefficient` | `coefficient(self) -> BigInt` | The integer the value is a scaled copy of. |
| `scale` | `scale(self) -> int` | How many digits of the coefficient are after the point. |
| `is_zero` | `is_zero(self) -> bool` | Whether the value is zero, at whatever scale it is written to. |
| `sign` | `sign(self) -> int` | `-1`, `0` or `1`, which the scale cannot affect: a positive power of ten does not change a sign. |

### `Rounding`

```sysl
enum Rounding
    HalfEven
    HalfUp
    Down
    Up
    Floor
    Ceiling
```

How to resolve a division that does not come out exactly.

`HalfEven` is the default and is what money code should use: rounding halves consistently up
biases a long run of sums upward, and rounding them to the nearest even value does not. It is what
IEEE 754 does with binary floating point and what accountants call banker's rounding.

## Implementations

### Add for Decimal

```sysl
impl Add for Decimal
```

### Display for Decimal

```sysl
impl Display for Decimal
```

### Div for Decimal

```sysl
impl Div for Decimal
```

### Eq for Decimal

```sysl
impl Eq for Decimal
```

### From for Decimal

```sysl
impl From[long] for Decimal
```

### Hash for Decimal

```sysl
impl Hash for Decimal
```

### Mul for Decimal

```sysl
impl Mul for Decimal
```

### Neg for Decimal

```sysl
impl Neg for Decimal
```

### Ord for Decimal

```sysl
impl Ord for Decimal
```

### Sub for Decimal

```sysl
impl Sub for Decimal
```
