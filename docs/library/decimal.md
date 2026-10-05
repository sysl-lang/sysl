---
title: The decimal module
summary: "`sysl.math.decimal` — exact decimal arithmetic: a big-integer coefficient and a scale, so a tenth and a fifth make three tenths."
weight: 64
---

**Every declaration in `sysl.math.decimal`, with its signature:** [the generated API page](/api/sysl-math-decimal/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

A general-purpose language without a decimal type makes every financial program wrong in the same
way. `real` is binary floating point, and a tenth is no more representable in binary than a third is
in decimal — so a total of a million transactions is out by cents, and no amount of care at the call
site fixes it.

```sysl
import sysl.math.decimal.{add, from_int, parse, to_string}

val a = parse("0.1").expect("a number")
val b = parse("0.2").expect("a number")

print(to_string(add(a, b)), add(a, b) == parse("0.3").expect("a number"))
print(0.1 + 0.2, 0.1 + 0.2 == 0.3)
```

```output
0.3 true
0.3 false
```

**Look at the second line rather than the first.** The float sum *renders* as `0.3` — sysl's real
rendering rounds for display — and is not equal to `0.3`, because it is
`0.30000000000000004` and always was. So the error is invisible in the output and real in every
comparison, which is the worst way round: a program can print a plausible total for a year before
anybody sums a column and finds it short.

It accumulates, too:

```sysl
import sysl.math.decimal.{add, from_int, parse, to_string}

var real_total = 0.0
var exact = parse("0.00").expect("a number")
val dime = parse("0.10").expect("a number")

for _ in 0..<100
    real_total += 0.1
    exact = add(exact, dime)

print(real_total, real_total == 10.0)
print(to_string(exact), exact == from_int(10))
```

```output
10 false
10.00 true
```

PostgreSQL's `numeric`, Java's `BigDecimal` and Python's `decimal` are all this type. It is a
[`BigInt`](/library/bigint/) **coefficient** and a **scale**, which is how many of its digits are
after the point: `12.340` is a coefficient of `12340` at a scale of 3.

## The scale is part of the value

`1.50` and `1.5` compare **equal** and render **differently**, and that is what a program handling
money needs: a price quoted to the cent is quoted to the cent whether or not the cent happens to be
zero, and a total that rendered as `1.5` where every other row said `1.50` would be a bug report.

```sysl
import sysl.math.decimal.{normalized, parse, to_string}

val a = parse("1.50").expect("a number")
val b = parse("1.5").expect("a number")

print(a == b, a.hash() == b.hash())
print(to_string(a), to_string(b), a.scale(), b.scale())
print(to_string(normalized(a)))
```

```output
true true
1.50 1.5 2 1
1.5
```

`Hash` is over the **normalized** form, which is what `Eq` obliges it to be — without that, a map
keyed on a price would miss whenever two rows quoted it to different places. `normalized` is
available to a caller who wants it, and a caller **rendering** a value should not reach for it: the
scale is the number of places the value was quoted to, and dropping it throws information away.

**The scale is never negative.** Every operation here produces one that is zero or more, and the two
that take a scale from the caller refuse a negative.

## Addition, subtraction and multiplication are exact and never round

A sum's scale is the larger of its operands', and a product's is the **sum** of theirs. That is what
exactness costs, and it is worth expecting: two prices at two places multiply to a value at four.

```sysl
import sysl.math.decimal.{add, mul, parse, sub, to_string}

val price = parse("19.99").expect("a number")
val rate = parse("0.0825").expect("a number")

print(to_string(mul(price, rate)))
print(to_string(add(price, parse("0.01").expect("a number"))))
print(to_string(sub(price, parse("20").expect("a number"))))
```

```output
1.649175
20.00
-0.01
```

A program that wants two places back asks for them with `rescale`. Anything else would be rounding a
product without being told how.

## Division takes a scale and a rounding mode, because it cannot be exact

A third has no finite decimal expansion, so there is no scale a quotient "naturally" has and any
choice this module made for a caller would be wrong for somebody. `divide` is the only place in the
module where a digit is ever discarded, and it says how:

```sysl
import sysl.math.decimal.{Rounding, divide, from_int, to_string}

val one = from_int(1)
val eight = from_int(8)

// An eighth is 0.125, so two places puts it exactly on the tie.
print(to_string(divide(one, eight, 2, Rounding.HalfEven)))
print(to_string(divide(one, eight, 2, Rounding.HalfUp)))
print(to_string(divide(one, from_int(3), 5, Rounding.HalfEven)))
```

```output
0.12
0.13
0.33333
```

**`HalfEven` is the default and is what money code should use.** Rounding halves consistently up
biases a long run of sums upward; rounding them to the nearest even value does not. It is what IEEE
754 does with binary floating point and what accountants call banker's rounding — and it is the one
answer here that differs from what a schoolbook teaches.

The six modes, and the two rows that separate them:

| | `1 / 8` = 0.125 | `-1 / 8` = -0.125 |
|---|---|---|
| `HalfEven` | `0.12` | `-0.12` |
| `HalfUp` | `0.13` | `-0.13` |
| `Down` | `0.12` | `-0.12` |
| `Up` | `0.13` | `-0.13` |
| `Floor` | `0.12` | `-0.13` |
| `Ceiling` | `0.13` | `-0.12` |

**The negative column is what makes the positive one mean anything.** `Floor` and `Down` agree on
every positive value and disagree on every negative one, and so do `Ceiling` and `Up` — a table with
only the first column in it would say nothing about four of the six.

```sysl
import sysl.math.decimal.{Rounding, divide, from_int, to_string}

val a = from_int(-1)
val b = from_int(8)

print(to_string(divide(a, b, 2, Rounding.Down)), to_string(divide(a, b, 2, Rounding.Floor)))
print(to_string(divide(a, b, 2, Rounding.Up)), to_string(divide(a, b, 2, Rounding.Ceiling)))
```

```output
-0.12 -0.13
-0.13 -0.12
```

`rescale` takes the same modes, and growing a scale needs none of them — that direction is exact.

**Division by zero traps**, as it does for a [`BigInt`](/library/bigint/) and for a machine integer.

## Reading and writing keeps the scale

`parse` reads an optional sign and then digits with at most one point among them, and the scale is
however many digits came after it. So `to_string(parse(s))` is `s`, which is the whole promise:

```sysl
import sysl.math.decimal.{parse, to_string}

for s in ["12.340", "0.005", "-0.5", "1.50", "0"]
    print(to_string(parse(s).expect("a number")))
```

```output
12.340
0.005
-0.5
1.50
0
```

`".5"` is read as a half. `"1."` is not read at all — a point with no digit after it is a mistake
rather than a spelling. `"-0"` parses to zero and renders as `"0"`, which is the one input that does
not round trip, there being no negative zero in the representation underneath.

**There is no exponent form**, deliberately: `1e-3` names a value and says nothing about the scale it
should be written to, so accepting it would mean this module choosing one — and the scale is the thing
a caller is trusting the round trip with. A program reading exponent notation splits it and calls
`rescale`.

## Taking one apart

`coefficient` and `scale` are how a program storing a value in a database column or sending it over a
wire takes it apart, since that is exactly how such a column is stored:

```sysl
import sysl.math.bigint.to_string as big_text
import sysl.math.decimal.{of, parse, to_string}

val x = parse("12.340").expect("a number")

print(big_text(x.coefficient()), x.scale())
print(to_string(of(x.coefficient(), x.scale())))
```

```output
12340 3
12.340
```

## What it costs

Everything is `BigInt` arithmetic, so everything allocates — and adding two values at **different**
scales additionally multiplies one coefficient by a power of ten. That is the price of being exact and
it is not small: a program adding a million rows should keep them at one scale, where the alignment is
free.
