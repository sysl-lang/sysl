---
title: sysl.math.bigint
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.math.bigint
summary: "Integers with no width: sign and magnitude, the magnitude a run of 32-bit limbs."
---

**A general-purpose language without arbitrary precision makes a whole class of program wrong in
the same way** -- a hash reduced modulo something, a factorial, a cryptographic exponent, an
identifier that outgrew 64 bits. Go ships `math/big` and Rust does not, and this takes Go's side:
the alternative is a package that every serious program ends up depending on anyway, and a
standard library is where a type everybody has to agree on belongs.

## The representation

A `BigInt` is a **sign** and a **magnitude**, and the magnitude is a view of `u32` limbs written
**least significant first** with no leading zeros. Zero is the empty magnitude and a clear sign,
so there is exactly one representation of it and nothing has to remember whether it is negative.

**Limbs are 32 bits and the arithmetic is done in 64.** That is what makes every carry, borrow and
partial product exact without asking the language for a wider type or a carry flag: a product of
two limbs is at most `(2^32 - 1)^2`, which fits a `u64` with room for two more limbs of carry.
Sixty-four-bit limbs would halve the work and would need a 128-bit product, which is a different
conversation.

## What it costs

**Every operation that grows allocates**, and the docstrings say so where it is not obvious.
There is no in-place arithmetic and no reuse of a caller's buffer: a `BigInt` is a value that
copies, which is what makes `a + b * c` mean what it looks like, and the cost of that is one
allocation per operation. A program doing this in a loop is doing something a big-integer library
is not the fastest way to do.

**Multiplication is schoolbook and division is Knuth's algorithm D.** Both are quadratic.
Karatsuba, Toom-Cook and a Barrett or Montgomery reduction are all improvements that fit behind
this same surface, and none of them changes an answer -- so they are work somebody does when a
program is slow rather than decisions this module has to make now. What this is for is money,
identifiers, checksums and the occasional exact computation, none of which is GMP's territory.

## The bit operations, and the reading they settled on

`and`, `or`, `xor`, `~` and the two shifts read a value as a **two's-complement number of
infinite width**: a non-negative one padded upward with zeros and a negative one padded upward
with ones. That is Python's reading, and it is the only one that is total over both signs without
a width the caller would have had to invent. So `~x` is `-x - 1` for every `x`, `-1` has every bit
set, and `>>` floors toward negative infinity rather than toward zero. `bits.sysl` builds it and
writes the argument out.

**There are two divisions and both are here.** `div_rem` truncates, which is what the hardware and
the language's own `/` do; `div_rem_floor` rounds toward negative infinity and leaves the
remainder with the divisor's sign, which is what puts a modulus in a fixed range. A language
implemented over this type picks one and says which.

## What is deliberately not here

**Primality.** A useful test is Miller-Rabin, and which witness set or how many rounds it should
use is a decision about what the caller is doing rather than one this module can make. Nothing
here needs one, and a wrong answer from a primality test is the expensive kind.

## Index

[`abs`](#abs) [`add`](#add) [`add_long`](#add_long) [`and`](#and) [`bit_length`](#bit_length) [`cmp`](#cmp) [`cmp_long`](#cmp_long) [`div`](#div) [`div_floor`](#div_floor) [`div_rem`](#div_rem) [`div_rem_floor`](#div_rem_floor) [`div_rem_long`](#div_rem_long) [`fits_long`](#fits_long) [`from_int`](#from_int) [`from_real`](#from_real) [`from_u64`](#from_u64) [`gcd`](#gcd) [`is_even`](#is_even) [`is_odd`](#is_odd) [`isqrt`](#isqrt) [`lcm`](#lcm) [`mod_floor`](#mod_floor) [`mod_pow`](#mod_pow) [`mul`](#mul) [`mul_long`](#mul_long) [`negate`](#negate) [`not`](#not) [`one`](#one) [`or`](#or) [`parse`](#parse) [`parse_radix`](#parse_radix) [`pow`](#pow) [`rem`](#rem) [`shl`](#shl) [`shr`](#shr) [`sub`](#sub) [`sub_long`](#sub_long) [`test_bit`](#test_bit) [`to_real`](#to_real) [`to_string`](#to_string) [`to_string_radix`](#to_string_radix) [`to_u64`](#to_u64) [`trailing_zeros`](#trailing_zeros) [`xor`](#xor) [`zero`](#zero) [`BigInt`](#bigint) [Add for BigInt](#add-for-bigint) [BitAnd for BigInt](#bitand-for-bigint) [BitOr for BigInt](#bitor-for-bigint) [BitXor for BigInt](#bitxor-for-bigint) [Display for BigInt](#display-for-bigint) [Div for BigInt](#div-for-bigint) [Eq for BigInt](#eq-for-bigint) [From for BigInt](#from-for-bigint) [From for BigInt](#from-for-bigint-1) [Hash for BigInt](#hash-for-bigint) [Mul for BigInt](#mul-for-bigint) [Neg for BigInt](#neg-for-bigint) [Not for BigInt](#not-for-bigint) [One for BigInt](#one-for-bigint) [Ord for BigInt](#ord-for-bigint) [Rem for BigInt](#rem-for-bigint) [Shl for BigInt](#shl-for-bigint) [Shr for BigInt](#shr-for-bigint) [Sub for BigInt](#sub-for-bigint) [Zero for BigInt](#zero-for-bigint)

## Functions

### `abs`

```sysl
abs(x: BigInt) -> BigInt
```

The magnitude, as a value of this type.

### `add`

```sysl
add(a: BigInt, b: BigInt) -> BigInt
```

`a + b`. Allocates the result's limbs.

Two values of the same sign add their magnitudes and keep it; two of different signs subtract the
smaller magnitude from the larger and take the larger one's sign, which is where a zero result
comes from and is why the sign is dropped when the magnitudes cancel.

### `add_long`

```sysl
add_long(a: BigInt, n: long) -> BigInt
```

`a + n`.

### `and`

```sysl
and(a: BigInt, b: BigInt) -> BigInt
```

`a & b`, on the two's-complement reading -- so `(-6) & 3` is `2`. Allocates.

The sign follows the operation: a bit of the result is set only where both operands have one, and
the sign bit is a bit like any other, so the answer is negative exactly when both operands are.

### `bit_length`

```sysl
bit_length(x: BigInt) -> usize
```

How many bits the **magnitude** occupies, which is zero for zero and is Python's `bit_length`.

It answers the same number for `x` and for `-x`, the sign not being one of the bits it counts.
That is the question a caller actually has -- how big is this -- and it is the one that makes
`bit_length(x) <= 64` the test for whether a value still fits a machine word.

### `cmp`

```sysl
cmp(a: BigInt, b: BigInt) -> int
```

`-1`, `0` or `1` as `a` is below, equal to or above `b`.

A negative is below every positive, and between two negatives the *larger magnitude* is the
smaller number -- which is the one place sign-and-magnitude needs a thought that two's complement
does not.

### `cmp_long`

```sysl
cmp_long(a: BigInt, n: long) -> int
```

`-1`, `0` or `1` as `a` is below, equal to or above `n`. **Allocates nothing.**

That is the whole reason it is here rather than left to `cmp(a, from_int(n))`. A comparison is
what a loop tests and what a sort calls, so it is the operation most often on a hot path and the
one where building a value to throw away is least defensible. The signs decide it where they
differ, a magnitude of more than two limbs is past anything a `long` holds, and what is left is
two `u64`s.

### `div`

```sysl
div(a: BigInt, b: BigInt) -> BigInt
```

`a / b`, truncating toward zero.

### `div_floor`

```sysl
div_floor(a: BigInt, b: BigInt) -> BigInt
```

`a` divided by `b`, rounding toward negative infinity: `div_floor(-7, 2)` is `-4`.

### `div_rem`

```sysl
div_rem(a: BigInt, b: BigInt) -> (BigInt, BigInt)
```

`(quotient, remainder)` with the signs applied.

**The quotient truncates toward zero and the remainder takes the DIVIDEND's sign**, which is what
C, Go, Rust and sysl's own `/` and `%` on machine integers all do, and is what makes
`a == q * b + r` hold. It is not the floored division a mathematician would write, and the
difference shows on exactly the cases with a negative operand: `-7 / 2` is `-3` with a remainder
of `-1`, rather than `-4` with a remainder of `1`.

**Division by zero traps**, as it does on a machine integer, rather than answering a `Result`.
That is the language's own answer for the same mistake and there is no reason for this type to
disagree with it -- a caller that cannot rule out a zero divisor checks for one.

### `div_rem_floor`

```sysl
div_rem_floor(a: BigInt, b: BigInt) -> (BigInt, BigInt)
```

`(quotient, remainder)` **floored**: the quotient rounds toward negative infinity and the
remainder takes the **divisor's** sign. `div_rem_floor(-7, 2)` is `(-4, 1)`.

**This is the other division, and both are here because neither is the right one.** `div_rem`
truncates, which is what the hardware does and what makes `abs(a / b)` independent of the signs;
this floors, which is what makes the remainder land in a fixed range -- `0..<b` for a positive
divisor, whatever the dividend was. A program indexing a ring buffer, reducing modulo a modulus,
or bucketing a signed coordinate wants the second and gets a negative index from the first.

Python's `//` and `%` are this pair, and a language implemented over this type reaches for it.

**The identity `a == q * b + r` holds for both**, which is what makes them two roundings of one
division rather than two different operations. They differ only where the remainder is non-zero
and the operands' signs disagree, and there the quotient is one lower and the remainder one
divisor higher.

### `div_rem_long`

```sysl
div_rem_long(a: BigInt, n: long) -> (BigInt, long)
```

`(quotient, remainder)` for a machine-sized divisor, the remainder as a `long`.

**The remainder always fits**, which is what makes the second result a `long` rather than another
`BigInt`: it is smaller in magnitude than the divisor, and the divisor was a `long`. It truncates
and takes the dividend's sign, as `div_rem` does -- `div_rem_floor` is the other rounding and has
no small-operand form, a caller wanting it having `div_rem_floor(a, from_int(n))`.

**A zero divisor traps**, as it does everywhere else in this module.

### `fits_long`

```sysl
fits_long(x: BigInt) -> bool
```

Whether the value still fits a `long`, which is the question in front of every `to_long` and is
worth asking on its own when the answer decides a route rather than a value.

### `from_int`

```sysl
from_int(n: long) -> BigInt
```

The value `n` names.

The most negative `long` is the case worth knowing about: its magnitude cannot be reached by
negating it, since that value has no positive counterpart, so the conversion goes through the
unsigned reading of the bits.

### `from_real`

```sysl
from_real(f: real) -> Option[BigInt]
```

The value `f` names with its fraction discarded, or `None` for a NaN or an infinity.

**It truncates toward zero**, as `long(f)` does: `from_real(-2.7)` is `-2`. A magnitude below one
truncates to zero, including a negative one, there being no negative zero here.

**Exact for every finite `real`**, however large -- which is the reason to convert through this
type rather than through a machine integer, where `1e30` has no answer at all.

### `from_u64`

```sysl
from_u64(n: u64) -> BigInt
```

The value `n` names, `n` being unsigned -- so the whole 64-bit range, where `from_int` stops at
the largest positive `long`.

### `gcd`

```sysl
gcd(a: BigInt, b: BigInt) -> BigInt
```

The largest value dividing both, and never a negative one: `gcd(-12, 18)` is `6`.

**Euclid's algorithm over the magnitudes**, which is what makes one loop serve every combination
of signs: the divisors of a number and of its negation are the same set, so the signs can be
dropped at the door rather than reasoned about. `gcd(x, 0)` is the magnitude of `x`, everything
dividing zero, and `gcd(0, 0)` is zero.

The binary algorithm -- shifts and subtractions, no division -- is the faster one on a machine
whose divide is slow, and it is a fair amount more code for a constant factor. This is the one
that is obviously right.

### `is_even`

```sysl
is_even(x: BigInt) -> bool
```

Whether two divides the value. Zero is even, as it is everywhere else.

### `is_odd`

```sysl
is_odd(x: BigInt) -> bool
```

Whether two does not divide the value.

### `isqrt`

```sysl
isqrt(x: BigInt) -> BigInt
```

The largest value whose square is at most `x`: `isqrt(17)` is `4`, and `isqrt(16)` is `4`.

**Newton's method on the integers, and it lands exactly** -- there is no tolerance to choose and
no epsilon anywhere in it. Starting from a guess at or above the answer, each step `(g + x/g) / 2`
is still at or above it, and the sequence decreases until it cannot; the first step that fails to
decrease is the one that has arrived, and the value before it is the floor. That is the standard
argument and it needs the starting guess to be an over-estimate, which is what the shift gives:
two raised to half the bit length, rounded up, is at least the root.

**A negative value traps**, there being no integer answer and no reason for this to be the one
function in the module that returns an `Option` for a mistake the caller can see coming.

### `lcm`

```sysl
lcm(a: BigInt, b: BigInt) -> BigInt
```

The smallest non-negative value both divide: `lcm(4, 6)` is `12`.

**Divided before multiplied** -- `a / gcd(a, b) * b` rather than `a * b / gcd(a, b)`. The two
agree on every input, `gcd` being a divisor of `a` so the quotient is exact, and the second builds
an intermediate with as many limbs as both operands together. That costs nothing at a machine
width and costs a real allocation here.

**A zero operand answers zero**, which is both the right answer -- zero is a multiple of
everything -- and the case that would otherwise divide by `gcd(0, 0)`.

### `mod_floor`

```sysl
mod_floor(a: BigInt, b: BigInt) -> BigInt
```

`a` modulo `b`, taking the divisor's sign: `mod_floor(-7, 2)` is `1`.

### `mod_pow`

```sysl
mod_pow(base: BigInt, exp: BigInt, m: BigInt) -> BigInt
```

`base` raised to `exp`, modulo `m`, by square-and-multiply: `mod_pow(2, 10, 1000)` is `24`.

**The reduction is what makes it possible at all.** `pow(base, exp)` on its own grows to roughly
`exp * bit_length(base)` bits, so an exponent of a few thousand is a number nobody can hold;
reducing after every squaring keeps every intermediate under twice the modulus.

**The answer takes the modulus's sign**, which is `mod_floor`'s rule and Python's: it lands in
`0..<m` for a positive `m`. A negative exponent traps -- `base^-1` modulo `m` is the modular
inverse, which exists only where `base` and `m` are coprime and is a different function with a
different failure. A zero modulus traps, as a division by it does.

**This is NOT constant-time and must not be used where the exponent is a secret.** The loop
multiplies on a set bit and does not on a clear one, so the time it takes reveals how many bits of
the exponent are set, and a cache-timing attacker learns more than that. A constant-time modular
exponentiation is a Montgomery ladder over a Montgomery-form modulus, and it is a separate
function rather than a flag on this one -- saying which one a caller got is the point.

### `mul`

```sysl
mul(a: BigInt, b: BigInt) -> BigInt
```

`a * b`, schoolbook. Allocates.

The sign is the sign of the product and the magnitudes multiply, with the one special case every
sign-and-magnitude implementation has: a zero result must not be negative, and here it cannot be,
because a zero operand returns early.

### `mul_long`

```sysl
mul_long(a: BigInt, n: long) -> BigInt
```

`a * n`, one pass over the limbs where `n` fits a limb.

The magnitude of the operand is taken as a `u64` before the width is tested, which is what lets
the most negative `long` take the general route rather than wrapping on the way to it.

### `negate`

```sysl
negate(x: BigInt) -> BigInt
```

The same magnitude with the other sign, and zero unchanged -- there being no negative zero here
to produce.

### `not`

```sysl
not(x: BigInt) -> BigInt
```

`~x`, which on an infinitely wide two's-complement number is `-x - 1`: `~5` is `-6` and `~(-1)`
is `0`.

**It is written as the subtraction rather than as a complement loop**, because that is what it is:
inverting every bit of an endless run of them is the identity `-x - 1` and nothing else, and a
loop over the limbs would have to invent a width to stop at.

### `one`

```sysl
one() -> BigInt
```

One.

### `or`

```sysl
or(a: BigInt, b: BigInt) -> BigInt
```

`a | b`, on the two's-complement reading -- so `(-6) | 3` is `-5`. Allocates.

Negative wins, by the same reading `and` takes from the other side: the sign bit is set where
either operand has it, so one negative operand makes the answer negative.

### `parse`

```sysl
parse(s: string) -> Option[BigInt]
```

The value `s` names in base ten, or `None`.

### `parse_radix`

```sysl
parse_radix(s: string, base: int) -> Option[BigInt]
```

The value `s` names in `base`, or `None` where it names none.

An optional `+` or `-` and then at least one digit, in either case. Nothing else: no underscores,
no `0x` prefix, no leading or trailing space. A caller reading a number out of a document trims
and strips before it gets here, because what a document allows is the document's business.

**`"-0"` parses to zero and renders as `"0"`**, which is the one input that does not round trip
and could not: there is no negative zero in this representation, which is what makes equality and
hashing straightforward everywhere else.

### `pow`

```sysl
pow(a: BigInt, n: usize) -> BigInt
```

`a` raised to `n`, by repeated squaring. Allocates once per squaring and once per set bit.

**A negative exponent has no integer answer and gives one**, since `x^-1` is not an integer for
any `x` but 1 and -1; a caller wanting a rational wants a rational. `pow(x, 0)` is one, including
for `x` of zero, which is the convention every language and every algebra text takes.

### `rem`

```sysl
rem(a: BigInt, b: BigInt) -> BigInt
```

`a % b`, taking the dividend's sign.

### `shl`

```sysl
shl(x: BigInt, n: usize) -> BigInt
```

`x << n`, which is `x * 2^n` and keeps the sign. Allocates.

There is no overflow to think about, which is the whole difference from a machine integer: a shift
left grows the number rather than discarding what leaves the top.

### `shr`

```sysl
shr(x: BigInt, n: usize) -> BigInt
```

`x >> n`, **arithmetic**: the result floors toward negative infinity, so `(-1) >> 100` is `-1` and
`(-7) >> 1` is `-4`. Allocates.

**Flooring is what the two's-complement reading forces**, and it is worth seeing why rather than
taking it as a convention. Shifting an endless run of bits right discards the low ones and drags
the sign down from above, which for a negative number is exactly division rounding away from zero
in magnitude. A truncating shift would disagree with `>>` on every machine integer in the language
and with `div_floor` beside it, and there would then be two different meanings of the same symbol.

So the magnitude is shifted and rounded **up** where anything was lost, and the sign put back.

### `sub`

```sysl
sub(a: BigInt, b: BigInt) -> BigInt
```

`a - b`, which is `a + (-b)` and is written as that rather than as a second carry loop.

### `sub_long`

```sysl
sub_long(a: BigInt, n: long) -> BigInt
```

`a - n`.

**Written as a subtraction rather than as `add_long(a, -n)`**, which would be wrong at the most
negative `long`: that value has no positive counterpart, so negating it in a `long` wraps back to
itself and the answer would come out added instead of taken away.

### `test_bit`

```sysl
test_bit(x: BigInt, i: usize) -> bool
```

Whether bit `i` is set, on the two's-complement reading -- so every bit of `-1` is set, and every
bit above the magnitude of a negative number is too.

**A negative value is read through `|x| - 1` rather than complemented whole**: `-m` is `~(m - 1)`,
so a bit of the negative is the complement of the same bit of `m - 1`, and one subtraction answers
any index including the ones past the end. Allocates for a negative `x` and not for a positive.

### `to_real`

```sysl
to_real(x: BigInt) -> real
```

The nearest `real` to the value, ties to even, and an infinity where the value is past what a
`real` holds.

**Every step of the rounding is done on the integer side**, where it is exact, and the result is
assembled by a multiplication that cannot itself round: a mantissa of at most 53 bits converts
exactly, and scaling it by a power of two is exact until it overflows -- at which point it becomes
an infinity, which is the right answer.

### `to_string`

```sysl
to_string(x: BigInt) -> string
```

`x` in base ten.

### `to_string_radix`

```sysl
to_string_radix(x: BigInt, base: int) -> string
```

`x` written in `base`, with a leading `-` where it is negative and no `+` where it is not.

**A base outside 2 to 36 traps**, since there is no digit to write past `z` and a caller asking
for base 37 has a mistake rather than an unusual requirement. Zero is `"0"` in every base.

Allocates the answer, and a working buffer the size of the answer.

### `to_u64`

```sysl
to_u64(x: BigInt) -> Option[u64]
```

The value as a `u64`, or `None` where it is negative or too large.

**A negative value answers `None` rather than its two's-complement pattern**, which is the one
decision this makes: the bit operations in this module read a negative number as an endless run of
ones, and no truncation of that to 64 bits is more obviously right than any other. A caller that
wants the low word of a two's-complement reading writes `and(x, from_u64(0xffffffffffffffff))`
and gets a value that says what it is.

### `trailing_zeros`

```sysl
trailing_zeros(x: BigInt) -> usize
```

How many low bits are clear, which is how many times two divides the value.

**Zero answers zero**, there being no highest power of two dividing it and no honest number to
give; a caller that cares tests `is_zero` first. The sign does not matter: complementing a value
leaves its trailing zeros exactly where they were, since `-m` is `~(m - 1)` and the borrow runs
out at the lowest set bit.

### `xor`

```sysl
xor(a: BigInt, b: BigInt) -> BigInt
```

`a ^ b`, on the two's-complement reading -- so `(-6) ^ 3` is `-7`. Allocates.

The answer is negative when exactly one operand is, which is the sign bit obeying the same rule
every other bit does.

### `zero`

```sysl
zero() -> BigInt
```

Zero, which is the empty magnitude.

## Types

### `BigInt`

```sysl
struct BigInt
    private[bigint] minus: bool
    private[bigint] mag: []const u32
```

An integer of no fixed width.

**The magnitude is a view, so a `BigInt` costs a retain to copy rather than a copy of its limbs.**
That is what makes passing one around cheap and is the reason the limbs are never written through:
every operation builds a new magnitude, so two values may share one and neither can see the other
change.

| Member | Signature | Description |
|---|---|---|
| `is_zero` | `is_zero(self) -> bool` | Whether this is zero, which is the one value with an empty magnitude. |
| `sign` | `sign(self) -> int` | `-1`, `0` or `1`, which is the comparison against zero written as a number -- what a caller switching on the three cases wants, and what `cmp` answers for a pair. |
| `limbs` | `limbs(self) -> usize` | How many limbs the magnitude takes, which is the closest thing to a size this type has. |
| `to_long` | `to_long(self) -> Option[long]` | The value as a `long`, or `None` where it does not fit. |

## Implementations

### Add for BigInt

```sysl
impl Add for BigInt
```

### BitAnd for BigInt

```sysl
impl BitAnd for BigInt
```

### BitOr for BigInt

```sysl
impl BitOr for BigInt
```

### BitXor for BigInt

```sysl
impl BitXor for BigInt
```

### Display for BigInt

```sysl
impl Display for BigInt
```

### Div for BigInt

```sysl
impl Div for BigInt
```

### Eq for BigInt

```sysl
impl Eq for BigInt
```

### From for BigInt

```sysl
impl From[u64] for BigInt
```

### From for BigInt

```sysl
impl From[long] for BigInt
```

### Hash for BigInt

```sysl
impl Hash for BigInt
```

### Mul for BigInt

```sysl
impl Mul for BigInt
```

### Neg for BigInt

```sysl
impl Neg for BigInt
```

### Not for BigInt

```sysl
impl Not for BigInt
```

### One for BigInt

```sysl
impl One for BigInt
```

### Ord for BigInt

```sysl
impl Ord for BigInt
```

*Written out, and it stays written out now that an array has `Ord` -- a `BigInt` is not
structurally ordered.** Its fields are a sign and a little-endian limb view, so a derived
comparison would take the sign first, putting every positive before every negative, and then
compare limbs from the *least* significant end, making `2^32` less than `1`. The numeric order
needs the magnitudes' lengths first and their limbs from the top, which is `cmp_mag`.

It could not be derived in any case: `bool` has no `Ord`, so `derives Ord` on this shape is
refused with `'<' is not defined for bool`.

### Rem for BigInt

```sysl
impl Rem for BigInt
```

### Shl for BigInt

```sysl
impl Shl[usize] for BigInt
```

### Shr for BigInt

```sysl
impl Shr[usize] for BigInt
```

### Sub for BigInt

```sysl
impl Sub for BigInt
```

### Zero for BigInt

```sysl
impl Zero for BigInt
```
