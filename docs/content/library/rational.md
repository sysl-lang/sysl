---
title: The rational module
summary: "`sysl.math.rational` — exact fractions over `BigInt`: lowest terms with a positive denominator, the four operations, floor and ceiling, an exact comparison against a `real`, and a correctly rounded conversion to one."
weight: 64
---

**Every declaration in `sysl.math.rational`, with its signature:** [the generated API page](/api/sysl-math-rational/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

A `Rational` is a fraction with no error: a numerator and a denominator, both
[`BigInt`](/library/bigint/)s. `BigInt` makes `+`, `-` and `*` exact over the integers; the rationals
are the smallest set that keeps `/` exact as well, and every numeric tower that has one — Scheme's,
Haskell's `Rational`, Python's `fractions` — builds it out of exactly these two integers.

```sysl
import sysl.math.rational.{ratio, to_string}

val third = ratio(1, 3)
val sixth = ratio(1, 6)

print(to_string(third + sixth))
print(to_string(ratio(6, -4)), to_string(ratio(10, 5)))
print(third < sixth, third * ratio(3, 1) == ratio(1, 1))
```

```output
1/2
-3/2 2/1
false true
```

## Lowest terms, and the denominator is positive

Every value is kept **reduced by its `gcd` with the sign on the numerator**, so every number has
exactly one representation: `6/-4` is built as `-3/2`, and zero is `0/1` whatever it was computed
from. That is what lets `==` compare the two parts field by field, `Hash` hash them, and the text
print without anybody normalizing first. The text is always `n/d` — a whole number is `2/1`, so it
says which type it came from.

`of(num, den)` builds one from two `BigInt`s and `ratio(n, d)` from two machine integers;
`from_int` and `from_big` take a whole number, and `From[long]` and `From[BigInt]` are implemented.
**A zero denominator traps**, and so does division by zero, as both do for a `BigInt` and for a
machine integer: the mistake is the same one and the language's answer for it is the same answer. A
caller that cannot rule out a zero divisor checks for one.

**Every operation reduces its result**, which is Euclid's algorithm over `BigInt`s and most of the
cost of a sum. Without it a running sum of fractions grows its denominator without bound even when
the value does not.

## Back to an integer

`floor`, `ceil` and `trunc` round down, up and toward zero, and answer a `BigInt`.
`numerator()` and `denominator()` hand back the two parts:

```sysl
import sysl.math.bigint.to_string
import sysl.math.rational.{ceil, floor, ratio, trunc}

val x = ratio(-7, 2)

print(to_string(floor(x)), to_string(ceil(x)), to_string(trunc(x)))
print(to_string(x.numerator()), to_string(x.denominator()))
```

```output
-4 -3 -3
-7 2
```

## A `real` is read at its exact value

**Every finite `real` is a rational** — an integer times a power of two — so `from_real` and the
comparisons take a double at its exact binary value, never at the shortest decimal that reads back
as it. `0.1` is not a tenth, and this module says so:

```sysl
import sysl.math.Float
import sysl.math.rational.{cmp_real, eq_real, from_real, lt_real, ratio, to_string}

print(to_string(from_real(0.1).unwrap()))
print(cmp_real(ratio(1, 10), 0.1), lt_real(ratio(1, 10), 0.1))
print(eq_real(ratio(1, 2), 0.5), cmp_real(ratio(1, 2), real.nan()))
```

```output
3602879701896397/36028797018963968
Some(-1) true
true None
```

`cmp_real` answers `None` for a NaN, which is unordered against everything, and `eq_real`,
`lt_real` and `gt_real` answer `false` for one. An infinity is above or below every rational.
**None of them converts the rational to a `real`**, which rounds and so can call two different
numbers equal.

## And a `real` comes back correctly rounded

`to_real` gives the nearest `real`, a tie to the even one — the answer an infinitely precise
division followed by one rounding would give. The numerator is scaled so one integer division
yields a few bits more than the 53 a `real` keeps; the bits below those and the division's remainder
decide the rounding, on the integer side where it is exact. Below the normal range fewer bits are
kept, so a subnormal is rounded once at its own last place rather than twice, and past the largest
finite `real` the answer is an infinity.

```sysl
import sysl.math.bigint.{add, from_int, shl}
import sysl.math.rational.{from_big, ratio, to_real}

print(f"${to_real(ratio(1, 3))}%.17g")

val odd = add(shl(from_int(1), 53), from_int(1))

print(f"${to_real(from_big(odd))}%.17g")
```

```output
0.33333333333333331
9007199254740992
```

`2^53 + 1` sits exactly halfway between two doubles and goes to the even one.
