---
title: sysl.math.rational
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.math.rational
summary: "Exact fractions: a numerator and a denominator, both `BigInt`s, so a third is a third."
---

**A language that has an integer with no width has, one division later, a fraction with no
error.** `BigInt` makes `+`, `-` and `*` exact over the integers; the rationals are the smallest set
that keeps `/` exact as well, and every numeric tower that has one -- Scheme's, Haskell's
`Rational`, Python's `fractions` -- builds it out of exactly these two integers. A program that
needs it and does not find it here writes its own, and the hard half of that is not the arithmetic
but the conversion to and from `real`, which is where this module spends its care.

## The representation

A **numerator** and a **denominator**, kept in lowest terms with the denominator positive. So every
number has exactly one representation: `2/4` is built as `1/2`, `1/-2` as `-1/2`, and zero is
`0/1` whatever it was computed from. That is what lets `==` compare the two fields, `Hash` hash
them, and `n/d` print without anybody having to normalize first.

**A zero denominator traps**, as division by zero does on `BigInt` and on a machine integer -- the
mistake is the same one and the language's answer for it is the same answer. A caller that cannot
rule out a zero divisor checks for one.

## What it costs

**Every operation reduces its result by a `gcd`**, which is Euclid's algorithm over `BigInt`s and
is most of the cost of a sum. That is the price of the canonical form; without it a running sum of
fractions grows its denominator without bound even when the value does not.

## The two conversions to and from `real`

**Both are exact where exactness is possible and correctly rounded where it is not.** Every finite
`real` is a rational -- an integer times a power of two -- so `from_real` and `cmp_real` take the
double at its exact binary value, and `0.1` is `3602879701896397/36028797018963968` rather than
`1/10`. `to_real` goes the other way and has to round; it gives the nearest `real`, ties to even,
subnormals included, which is what a division of the two integers in infinite precision followed by
one rounding would give. **Neither direction ever passes through a `real` computed from the
fraction**, which is the shortcut that rounds twice.

## Index

[`abs`](#abs) [`add`](#add) [`ceil`](#ceil) [`checked_of`](#checked_of) [`cmp`](#cmp) [`cmp_real`](#cmp_real) [`div`](#div) [`eq_real`](#eq_real) [`floor`](#floor) [`from_big`](#from_big) [`from_int`](#from_int) [`from_real`](#from_real) [`gt_real`](#gt_real) [`lt_real`](#lt_real) [`mul`](#mul) [`negate`](#negate) [`of`](#of) [`one`](#one) [`ratio`](#ratio) [`sub`](#sub) [`to_real`](#to_real) [`to_string`](#to_string) [`trunc`](#trunc) [`zero`](#zero) [`Rational`](#rational) [Add for Rational](#add-for-rational) [Display for Rational](#display-for-rational) [Div for Rational](#div-for-rational) [Eq for Rational](#eq-for-rational) [From for Rational](#from-for-rational) [From for Rational](#from-for-rational-1) [Hash for Rational](#hash-for-rational) [Mul for Rational](#mul-for-rational) [Neg for Rational](#neg-for-rational) [Ord for Rational](#ord-for-rational) [Sub for Rational](#sub-for-rational)

## Functions

### `abs`

```sysl
abs(x: Rational) -> Rational
```

The magnitude.

### `add`

```sysl
add(a: Rational, b: Rational) -> Rational
```

`a + b`, exactly. Allocates, and reduces the sum by a `gcd`.

### `ceil`

```sysl
ceil(x: Rational) -> BigInt
```

The least integer not below `x`: `ceil(-7/2)` is `-3`, `ceil(7/2)` is `4`.

### `checked_of`

```sysl
checked_of(num: BigInt, den: BigInt) -> Option[Rational]
```

`num / den` in lowest terms as `of` builds it, or `None` where `den` is zero -- the constructor
for a caller that cannot rule out a zero denominator and would rather branch than trap.

### `cmp`

```sysl
cmp(a: Rational, b: Rational) -> int
```

`-1`, `0` or `1` as `a` is below, equal to or above `b`.

Cross-multiplied, which is exact and is the comparison the positive denominators make sound: `a/b <
c/d` is `a*d < c*b` exactly when `b` and `d` are both above zero.

### `cmp_real`

```sysl
cmp_real(a: Rational, f: real) -> Option[int]
```

How `a` stands against `f` at `f`'s exact binary value: `Some(-1)`, `Some(0)` or `Some(1)` as `a`
is below, equal to or above it, and `None` where `f` is a NaN, which is unordered against
everything.

**Never by converting `a` to a `real`**, which rounds and so can call two different numbers equal:
`1/3` is not `0.3333333333333333`, and this says so. An infinity is above or below every rational.

### `div`

```sysl
div(a: Rational, b: Rational) -> Rational
```

`a / b`, exactly -- the operation the type exists to keep exact.

**Division by zero traps**, as it does on `BigInt`.

### `eq_real`

```sysl
eq_real(a: Rational, f: real) -> bool
```

Whether `a` is exactly the value `f` holds; `false` for a NaN.

### `floor`

```sysl
floor(x: Rational) -> BigInt
```

The greatest integer not above `x`: `floor(-7/2)` is `-4`.

### `from_big`

```sysl
from_big(n: BigInt) -> Rational
```

The whole number `n`, over one.

### `from_int`

```sysl
from_int(n: long) -> Rational
```

The whole number `n`, over one.

### `from_real`

```sysl
from_real(f: real) -> Option[Rational]
```

The exact value `f` holds, or `None` for a NaN or an infinity.

**Exact, never the shortest decimal that reads back as `f`**: `from_real(0.1)` is
`3602879701896397/36028797018963968`, which is the number the double actually is. Every finite
`real` is an integer times a power of two, and that is the fraction this answers -- the denominator
is always a power of two, up to `2^1074` for the smallest subnormal.

Doubling is exact for every finite `real` that is not a whole number, those all lying below
`2^52`, so `f` is doubled until it is whole and the count of doublings is the power of two.

### `gt_real`

```sysl
gt_real(a: Rational, f: real) -> bool
```

Whether `a` is above `f`'s exact value; `false` for a NaN.

### `lt_real`

```sysl
lt_real(a: Rational, f: real) -> bool
```

Whether `a` is below `f`'s exact value; `false` for a NaN.

### `mul`

```sysl
mul(a: Rational, b: Rational) -> Rational
```

`a * b`, exactly.

### `negate`

```sysl
negate(x: Rational) -> Rational
```

The same value with the other sign.

### `of`

```sysl
of(num: BigInt, den: BigInt) -> Rational
```

`num / den` in lowest terms, the sign moved to the numerator.

**A zero denominator traps** -- the one representation rule enforced at the one place a caller
could break it.

### `one`

```sysl
one() -> Rational
```

One, `1/1`.

### `ratio`

```sysl
ratio(n: long, d: long) -> Rational
```

`n / d` over machine integers, in lowest terms -- `ratio(6, -4)` is `-3/2`. A zero `d` traps.

### `sub`

```sysl
sub(a: Rational, b: Rational) -> Rational
```

`a - b`, exactly.

### `to_real`

```sysl
to_real(x: Rational) -> real
```

The nearest `real` to `x`, a tie to the even one -- correctly rounded, subnormals included, and an
infinity past the largest finite `real`.

**One integer division, one rounding.** The numerator is scaled by a power of two so the quotient
carries two or three bits more than the 53 a `real` keeps; the bits below those 53 and the
remainder of the division are the guard and the sticky bit, and the rounding is decided on the
integer side where it is exact. Below the normal range fewer bits are kept, so the rounding happens
once, at the subnormal's own last place, rather than once to 53 bits and again on the way down.
The result is assembled from a mantissa of at most 53 bits and a power of two, which cannot round.

### `to_string`

```sysl
to_string(x: Rational) -> string
```

`n/d` in base ten, the sign on the numerator -- and a whole number is still `n/1`, so the text
says which type it came from.

### `trunc`

```sysl
trunc(x: Rational) -> BigInt
```

`x` with its fraction discarded, toward zero: `trunc(-7/2)` is `-3`.

### `zero`

```sysl
zero() -> Rational
```

Zero, `0/1`.

## Types

### `Rational`

```sysl
struct Rational
    private[rational] num: BigInt
    private[rational] den: BigInt
```

A fraction in lowest terms, the denominator positive.

Both parts are `BigInt`s, so copying a `Rational` costs two retains rather than a copy of either.

| Member | Signature | Description |
|---|---|---|
| `numerator` | `numerator(self) -> BigInt` | The numerator, which carries the sign. |
| `denominator` | `denominator(self) -> BigInt` | The denominator, which is always positive and is `1` for a whole number. |
| `is_zero` | `is_zero(self) -> bool` | Whether this is zero, which is `0/1` and nothing else. |
| `is_integer` | `is_integer(self) -> bool` | Whether the denominator is one -- the value is a whole number. |
| `sign` | `sign(self) -> int` | `-1`, `0` or `1`, which is the numerator's, the denominator being positive. |

## Implementations

### Add for Rational

```sysl
impl Add for Rational
```

### Display for Rational

```sysl
impl Display for Rational
```

### Div for Rational

```sysl
impl Div for Rational
```

### Eq for Rational

```sysl
impl Eq for Rational
```

*Field by field, because the form is canonical**: two equal values are reduced to the same
numerator and the same positive denominator, so nothing has to be multiplied out.

### From for Rational

```sysl
impl From[long] for Rational
```

### From for Rational

```sysl
impl From[BigInt] for Rational
```

### Hash for Rational

```sysl
impl Hash for Rational
```

### Mul for Rational

```sysl
impl Mul for Rational
```

### Neg for Rational

```sysl
impl Neg for Rational
```

### Ord for Rational

```sysl
impl Ord for Rational
```

### Sub for Rational

```sysl
impl Sub for Rational
```
