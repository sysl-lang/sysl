---
title: The bigint module
summary: "`sysl.math.bigint` — integers with no width: sign and magnitude over 32-bit limbs, schoolbook multiply, Knuth's algorithm D, bit operations on an infinite two's complement, and text in any base."
weight: 63
---

**Every declaration in `sysl.math.bigint`, with its signature:** [the generated API page](/api/sysl-math-bigint/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

A `BigInt` is an integer with no width. It is here because a general-purpose language without one
makes a whole class of program wrong in the same way — a hash reduced modulo something, a factorial,
a cryptographic exponent, an identifier that outgrew 64 bits — and because the alternative is a
package every serious program ends up depending on anyway. Go ships `math/big`; this takes Go's side.

```sysl
import sysl.math.bigint.{from_int, mul, one, pow, to_string}

var f = one()

for i in 1..<21
    f = mul(f, from_int(long(i)))

print(to_string(f))
print(to_string(pow(from_int(2), 128)))
```

```output
2432902008176640000
340282366920938463463374607431768211456
```

## Sign and magnitude, and the magnitude is limbs

A `BigInt` is a **sign** and a **magnitude**, and the magnitude is a view of `u32` limbs written
least significant first with no leading zeros. Zero is the empty magnitude and a clear sign, so there
is exactly one representation of it and nothing has to remember whether it is negative — which is
what makes `Eq`, `Ord` and `Hash` straightforward rather than careful.

**Limbs are 32 bits and the arithmetic is done in 64.** That is what makes every carry, borrow and
partial product exact without asking the language for a wider type or a carry flag: a product of two
limbs is at most `(2^32 - 1)^2`, which fits a `u64` with room for two more limbs of carry.

**The magnitude is a view, so copying a `BigInt` costs a retain rather than a copy of its limbs.**
That is what makes passing one around cheap, and it is why the limbs are never written through: every
operation builds a new magnitude, so two values may share one and neither can see the other change.

## The operators are there, and they allocate

`+`, `-`, `*`, `/` and `%` are all implemented, and so are `&`, `|`, `^`, `~`, `<<`, `>>`, `==`, `<`
and the rest. Every one of them that grows allocates — there is no in-place arithmetic and no reuse
of a caller's buffer, which is what makes `a + b * c` mean what it looks like:

```sysl
import sysl.math.bigint.{parse, to_string}

val a = parse("12345678901234567890").expect("a number")
val b = parse("98765432109876543210").expect("a number")

print(to_string(a * b))
print(to_string(a + b))
print(a < b, a == a)
```

```output
1219326311370217952237463801111263526900
111111111011111111100
true true
```

A program doing this in a loop is doing something a big-integer library is not the fastest way to do.

## There are two divisions, and a program picks one

**`div_rem` truncates toward zero and its remainder takes the dividend's sign**, which is what C, Go,
Rust and sysl's own `/` and `%` on machine integers all do. **`div_rem_floor` rounds toward negative
infinity and its remainder takes the divisor's sign**, which is what Python's `//` and `%` do. The
identity `a == q * b + r` holds for both, so they are two roundings of one division rather than two
operations; they differ only where the remainder is non-zero and the operands' signs disagree.

```sysl
import sysl.math.bigint.{div_rem, from_int, to_string}

val (q, r) = div_rem(from_int(-7), from_int(2))

print(to_string(q), to_string(r))
```

```output
-3 -1
```

```sysl
import sysl.math.bigint.{div_floor, div_rem_floor, from_int, mod_floor, to_string}

val (q, r) = div_rem_floor(from_int(-7), from_int(2))

print(to_string(q), to_string(r))
print(to_string(div_floor(from_int(7), from_int(-2))), to_string(mod_floor(from_int(7), from_int(-2))))
print(to_string(mod_floor(from_int(-1), from_int(12))))
```

```output
-4 1
-4 -1
11
```

**Which one to reach for is decided by what the remainder is for.** A quotient whose magnitude should
not depend on the signs wants the truncating pair. A remainder that has to land in a fixed range —
an index into a ring buffer, a value reduced modulo a modulus, a signed coordinate put in a bucket —
wants the floored one, and gets a negative index from the other. That is what the last line above is:
`mod_floor(-1, 12)` is `11`, where `rem(-1, 12)` is `-1`.

`div_rem` and `div_rem_floor` each answer both halves at once, which is one division rather than two.
**Division by zero traps**, as it does on a machine integer, rather than answering a `Result`.

## The algorithms are named so they can be checked

**Multiplication is schoolbook** and **division is Knuth's algorithm D** (*The Art of Computer
Programming*, volume 2, §4.3.1). Both are quadratic. Karatsuba, Toom-Cook and a Barrett or Montgomery
reduction are all improvements that fit behind this same surface and change no answer, so they are
work somebody does when a program is slow rather than decisions this module had to make.

The one thing worth knowing about algorithm D is that its quotient digit is a *guess* that has to be
corrected twice — a loop that tightens it, and an add-back for the one case the loop still gets wrong.
The add-back fires about twice in every `2^32` digits, which is rare enough that a suite of generated
inputs never enters it: the library's own tests use inputs constructed to force it, because two
hundred random pairs left the branch green with it deleted.

## The bit operations read a value as infinitely wide two's complement

A `BigInt` is stored as a sign and a magnitude, so `&`, `|`, `^`, `~` and the shifts have to be told
what they mean before they can be implemented. **The reading is Python's**: a value is an endless run
of bits, a non-negative one padded upward with zeros and a negative one padded upward with ones.

That reading is not a preference, it is the only one that is **total**. Any other answer needs a
width to complement within, and there is nothing a caller could pass as the width that would not be a
guess — so `and(-6, 3)` would have to be refused, or answered differently depending on a number
nobody has. On this reading it is `2`, for the same reason it is `2` at every machine width.

```sysl
import sysl.math.bigint.{and, from_int, not, or, shl, shr, to_string, xor}

val a = from_int(-6)
val b = from_int(3)

print(to_string(a & b), to_string(a | b), to_string(a ^ b))
print(to_string(~from_int(5)), to_string(not(from_int(-1))))
print(to_string(shl(from_int(1), 100)))
print(to_string(shr(from_int(-1), 100)), to_string(shr(from_int(-7), 1)))
```

```output
2 -5 -7
-6 0
1267650600228229401496703205376
-1 -4
```

Three consequences are worth reading off that output, because each is a place a width-bound intuition
gives the wrong answer:

- **`~x` is `-x - 1`, for every `x`.** There is no width for the complement to fill, so inverting an
  endless run of bits is that identity and nothing else. `~5` is `-6`, and `~(-1)` is `0`.
- **`<<` grows rather than discarding.** `1 << 100` is `2^100`, which is the whole point of the type;
  there is no top for a bit to leave by.
- **`>>` floors toward negative infinity**, so `(-1) >> 100` is `-1` and `(-7) >> 1` is `-4`. The sign
  is dragged down from above, which for a negative number rounds *away* from zero in magnitude. It
  agrees with `>>` on every machine integer in the language, and with `div_floor` beside it.

Only `&`, `|` and `^` pay anything for the reading: a negative operand is complemented limb-wise into
one limb more than the longer magnitude needs, which is what makes the top limb of each side a pure
sign and lets the result's sign be read off it. The queries are cheaper than that.

`bit_length` is Python's: how many bits the **magnitude** occupies, so a value and its negation answer
alike and `bit_length(0)` is `0`. `trailing_zeros` counts how many times two divides the value,
`test_bit` asks about a bit on the same two's-complement reading — so every bit above a negative
value's magnitude is set — and `is_even` and `is_odd` are the lowest bit.

## The number theory, written on purpose

```sysl
import sysl.math.bigint.{from_int, gcd, isqrt, lcm, mod_pow, parse, pow, to_string}

print(to_string(gcd(from_int(-12), from_int(18))), to_string(lcm(from_int(4), from_int(6))))
print(to_string(mod_pow(from_int(2), from_int(1000000006), from_int(1000000007))))
print(to_string(isqrt(pow(from_int(10), 30))))
print(to_string(isqrt(parse("2").expect("a number"))))
```

```output
6 12
1
1000000000000000
1
```

**`gcd` is Euclid over the magnitudes and is never negative**, the divisors of a number and of its
negation being the same set. `gcd(x, 0)` is the magnitude of `x`. **`lcm` divides before it
multiplies** — `a / gcd(a, b) * b`, never `a * b / gcd(a, b)` — because the second builds an
intermediate with as many limbs as both operands together, and the quotient is exact by construction.

**`mod_pow` is square-and-multiply with a reduction after every step**, which is what makes the second
line above possible at all: `pow(2, 1000000006)` is a number with three hundred million digits, and
the answer to it modulo a prime is `1`, by Fermat's little theorem. The answer takes the modulus's
sign, as `mod_floor` does. **It is not constant-time, and must not be used where the exponent is a
secret** — the loop multiplies on a set bit and does not on a clear one, so its timing reports how
many bits are set. A constant-time exponentiation is a different function and would say so.

**`isqrt` is Newton's method on the integers and lands exactly**, with no tolerance to choose: each
step from a starting over-estimate is still an over-estimate, and the first step that fails to
decrease is the one that has arrived. It is the exact floor, so `isqrt(2)` is `1`. A negative traps.

## Text in any base from 2 to 36

Both directions work a chunk at a time rather than a digit at a time, the chunk being the largest
power of the base that fits in a limb — `10^9` for base ten, `2^31` for binary. So rendering divides
the whole magnitude by a billion and gets nine digits out of the remainder with ordinary machine
arithmetic.

```sysl
import sysl.math.bigint.{parse, parse_radix, to_string_radix}

val x = parse("340282366920938463463374607431768211455").expect("a number")

print(to_string_radix(x, 16))
print(to_string_radix(parse("255").expect("a number"), 2))
print(parse_radix("ff", 16).expect("a number").to_long().expect("it fits"))
```

```output
ffffffffffffffffffffffffffffffff
11111111
255
```

Parsing takes an optional sign and then at least one digit, in either case. Nothing else: no
underscores, no `0x` prefix, no leading or trailing space. What a document permits is the document's
business, and stripping is one call before this one — which is also the right place for a language's
own literal syntax to handle its digit separators.

**`"-0"` parses to zero and renders as `"0"`**, which is the one input that does not round trip and
could not — there is no negative zero in this representation, which is exactly what makes equality and
hashing straightforward everywhere else.

## Getting a machine number back out

```sysl
import sysl.math.bigint.{bit_length, fits_long, from_int, from_u64, pow, to_u64}

val big = pow(from_int(10), 30)

print(bit_length(big), fits_long(big), bit_length(from_int(-255)))
print(to_u64(from_u64(18446744073709551615u64)).expect("it fits"))
print(to_u64(from_int(-1)).is_none())
```

```output
100 false 8
18446744073709551615
true
```

`to_long` and `to_u64` answer `None` rather than truncating, because a big integer that has outgrown
64 bits is exactly the case this type exists for and silently wrapping it would defeat the purpose.
`fits_long` asks the same question on its own, for a caller whose answer decides a route rather than a
value. **A negative value has no unsigned reading here**: `to_u64` refuses it rather than picking one
of the truncations of an endless run of ones.

The floating-point pair is exact about where it is not exact:

```sysl
import sysl.math.Float
import sysl.math.bigint.{from_int, from_real, pow, to_real, to_string}

print(to_real(pow(from_int(10), 30)))
print(to_string(from_real(1.0e30).expect("finite")))
print(to_real(pow(from_int(10), 400)).is_infinite())
print(from_real(real.nan()).is_none(), to_string(from_real(-2.7).expect("finite")))
```

```output
1e+30
1000000000000000019884624838656
true
true -2
```

**`to_real` is correctly rounded**, to nearest with ties to even — the same rule the hardware applies
to every arithmetic result, so a value converted here and a value computed there round alike. That
needs three things out of the magnitude rather than one: the top 53 bits, the bit below them, and
whether anything at all is set under that. Taking the top 53 and truncating would be off by one unit
in the last place half the time, and biased toward zero always.

**`from_real` is exact for every finite `real`, and truncates toward zero.** The second line above is
the interesting one: `1.0e30` is *not* the number `10^30`, and this says which number it actually is.

## One big number and one small one

A program built over this type rarely holds two large numbers at once. A language whose integers are
a 64-bit fast path promoting to a `BigInt` on overflow spends its life adding a literal to a value
that has already grown — and written as `add(a, from_int(n))`, every one of those builds a one-limb
`BigInt` to throw away a line later.

```sysl
import sysl.math.bigint.{add_long, cmp_long, div_rem_long, from_int, mul_long, pow, to_string}

var x = pow(from_int(10), 25)

print(to_string(add_long(x, 7)), to_string(mul_long(x, -3)))
print(cmp_long(x, 9223372036854775807))

val (q, r) = div_rem_long(x, 7)

print(to_string(q), r)
```

```output
10000000000000000000000007 -30000000000000000000000000
1
1428571428571428571428571 3
```

**What is actually saved varies by operation, and the module says which is which** rather than
implying the whole family is a fast path. `cmp_long` **allocates nothing at all**, which is the one
that matters most — a comparison against a small constant is what a loop condition is made of.
`mul_long` and `div_rem_long` take a single-limb route wherever the operand fits a limb, which is one
pass over the limbs rather than a pass plus a general multiply or a normalized long division.
`add_long` and `sub_long` are the general functions with the conversion written once, and are there
so that a caller's code reads the same for all five. `div_rem_long`'s remainder comes back as a
`long`, since it is smaller in magnitude than a divisor that was one.

## What is deliberately not here

**Primality.** A useful test is Miller-Rabin, and which witness set or how many rounds it should use
is a decision about what the caller is doing rather than one this module can make — a deterministic
set below `2^64`, a probabilistic round count above it. Nothing in the library needs one, and a wrong
answer from a primality test is the expensive kind.
