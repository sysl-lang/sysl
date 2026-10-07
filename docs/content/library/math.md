---
title: The math module
summary: "`sysl.math` — the `Float` trait over every floating width, `Signed` and `Bits` over the open integer family, `Magnitude` and the size a type measures in, the constants, `min`/`max`/`clamp` over anything ordered, the float comparisons, and the integer arithmetic above the operators."
weight: 60
---

**Every declaration in `sysl.math`, with its signature:** [the generated API page](/api/sysl-math/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.math` is five files and four traits, and the interesting thing about it is that **no two of
them are written the same way** — because the types they cover are different shapes, and the shape of
the family is what decides how a trait can reach it.
Above them sit the free functions, which are not members of anything and say in a bound what they
need. It requires no capability at all: every name here is reachable under `no alloc` and on a target
with no operating system.

```sysl
@no_alloc
@no_os

import sysl.math.{Float, Bits, min, pi}

var two = 2.0
var u = 0b1011u8

print(two.sqrt(), pi, min(3, 7))
print(u.count_ones(), u.rotate_left(1u32))
```

```output
1.41421 3.14159 3
3 22
```

## The constants

```sysl
import sysl.math.{pi, tau, e, sqrt2, ln2, ln10}

print(pi, tau, e)
print(sqrt2, ln2, ln10)
```

```output
3.14159 6.28319 2.71828
1.41421 0.693147 2.30259
```

Those are the full-precision values printed by `%g`'s six significant digits, which is what
[`print`](/library/core/) does with a float. The constants themselves carry every digit a `real`
holds.

**All six are `real`**, which is the width they are correct to and the width arithmetic reaches for
unless a program says otherwise. An `f32` program writes `f32(pi)`: the conversion is a constant the
compiler folds, so it costs nothing at run time, and one declaration per constant is better than two
that could drift apart.

**They are digits rather than expressions.** `tau` is written out rather than as `2.0 * pi`, because a
constant is a value and not a computation — and the last bit of a doubled binary64 is not always the
last bit of the correctly rounded product.

`tau` earns its place beside `pi` because it is the one that appears in the arguments to `sin` and
`cos`: a whole turn is `tau`, a quarter turn is `tau / 4.0`, and no factor of two has to be carried
around to remember it.

## `Float`

```sysl
trait Float: Zero + One + Eq + Ord + Neg + Add + Sub + Mul + Div

    // The type's own values, asked without a receiver. The two identities are *required* rather
    // than declared: `Zero` and `One` are core traits, and a required trait's members are reached
    // through the bound that requires it.
    max_value() -> Self
    epsilon() -> Self
    infinity() -> Self
    nan() -> Self
    pi() -> Self

    // Required — each width binds these to its own libm entry point.
    sqrt(self) -> Self
    cbrt(self) -> Self
    exp(self) -> Self
    exp2(self) -> Self
    ln(self) -> Self
    log2(self) -> Self
    log10(self) -> Self
    pow(self, exponent: Self) -> Self
    hypot(self, other: Self) -> Self
    sin(self) -> Self
    cos(self) -> Self
    tan(self) -> Self
    asin(self) -> Self
    acos(self) -> Self
    atan(self) -> Self
    atan2(self, x: Self) -> Self
    sinh(self) -> Self
    cosh(self) -> Self
    tanh(self) -> Self
    asinh(self) -> Self
    acosh(self) -> Self
    atanh(self) -> Self
    floor(self) -> Self
    ceil(self) -> Self
    round(self) -> Self
    trunc(self) -> Self
    fmod(self, divisor: Self) -> Self
    abs(self) -> Self
    copysign(self, sign: Self) -> Self
    to_radians(self) -> Self
    to_degrees(self) -> Self

    // Answered by the trait, once, for every width.
    signum(self) -> Self
    recip(self) -> Self
    square(self) -> Self
    log(self, base: Self) -> Self
    lerp(self, to: Self, t: Self) -> Self
    is_nan(self) -> bool
    is_infinite(self) -> bool
    is_finite(self) -> bool
```

**A trait rather than two sets of functions.** The shape was forced by a language decision that has
since been reversed, and is kept because it is the better one anyway. sysl had no
[overloading](/reference/declarations/) when this was written, so free functions could not call the
square root of a `real` and the square root of an `f32` by one name — it would have needed `sqrt`
and `sqrtf` the way C does. Two free `sqrt`s would resolve correctly today.

What the trait still buys is the half overloading does not: a member that is *arithmetic over the
others* — the logarithm in an arbitrary base, the hypotenuse — is written **once** as a default and
inherited by every width, where free functions would need it four times over and could disagree.
Dispatch on the receiver is worth having for its own sake too: `x.sqrt()` is the same three words
whichever width `x` is, and changing a declaration from `f32` to `real` sends nobody editing call
sites.

**The split between what is required and what is answered is where the mathematics is.** A method
whose result C computes — a range-reduced sine, a correctly rounded root — is required, and each width
binds it to its own entry point. A method that is *arithmetic over the others* is a default, written
once and inherited by all of them. So `log` in an arbitrary base exists in exactly one place, and
that is what made adding the narrow widths 38 bindings each and no new mathematics.

### The narrow widths compute at `f32`

There are four blocks — `real`, `f32`, [`f16` and `bf16`](/reference/types/) — and the last two are
not written the way the first two are. **libm stops at `float`**: there is no half-width `sqrt` to
bind, at either sixteen-bit format, and nothing about the widths suggests one is coming. So every
body at `f16` and `bf16` widens to `f32`, calls the entry point `f32` already binds, and narrows the
answer back.

**That is the correct answer rather than a compromise.** An `f32` holds every `f16` and every `bf16`
exactly — more exponent range and more significand than either — so the widening loses nothing at
all. The `f32` result is then within half an `f32` ulp of the true one, and narrowing rounds it to
the nearest value of the destination. Two roundings rather than one, and the first is so much finer
than the second that it can only change the answer where that answer already sits within an `f32` ulp
of the midpoint between two neighbouring values — which is the accuracy libm itself promises, not
something the widening introduced.

```sysl
import sysl.math.Float

var h: f16 = 9.0
var b: bf16 = 9.0

print(h.sqrt(), b.sqrt())
print(f16.max_value(), bf16.epsilon())
```

```output
3 3
65504 0.0078125
```

The constants are each width's own, and they are where the two sixteen-bit formats differ most: the
largest finite `f16` is 65504, while `bf16` reaches as far as an `f32` and gives up significand to do
it.

```sysl
import sysl.math.{Float, tau, e}

var two = 2.0
var three = 3.0
var eight = 8.0
var hundred = 100.0
var eightyone = 81.0
var quarter = tau / 4.0

print(two.sqrt(), eight.cbrt())
print(e.ln(), two.exp2())
print(hundred.log10(), eight.log2(), eightyone.log(three))
print(two.pow(10.0), three.hypot(4.0))
print(quarter.sin(), quarter.cos())
```

```output
1.41421 2
1 4
2 3 4
1024 5
1 6.12323e-17
```

Four of those lines are decisions rather than arithmetic.

**`ln` is spelled for what it is**, rather than as C's bare `log` — which reads as though it were the
general one and is the single most common way to get a base wrong. `log(base)` is the general one, and
it is the default written over `ln`.

**`log2` and `log10` are required separately rather than left to that default**, because reading them
back through a ratio loses digits that libm keeps.

**`hypot` is not `(x*x + y*y).sqrt()`.** The squares of operands near the top of the range overflow to
infinity when the answer itself is perfectly representable; libm's does the scaling that avoids it.

**`cos(tau/4)` is `6.12e-17` and not zero**, which is not a bug in anything — a quarter turn is not
exactly representable in binary, so the argument handed to `cos` is not exactly π/2. This is the
ordinary floating-point fact, and the page shows it rather than choosing an example that hides it.

### Rounding, sign, and the rest

```sysl
import sysl.math.Float

var half = 2.5
var neg = -2.5
var three = 3.0
var seven = 7.5
var four = 4.0
var zero = 0.0
var ten = 10.0
var one = 1.0

print(half.floor(), half.ceil(), half.round(), neg.trunc())
print(neg.abs(), three.copysign(-1.0))
print(seven.fmod(2.0))
print(four.recip(), three.square(), zero.lerp(ten, 0.25))
print(one.atan2(one).to_degrees())
print(neg.signum(), zero.signum())
```

```output
2 3 3 -2
2.5 -3
1.5
0.25 9 2.5
45
-1 0
```

**All four rounding functions answer in the float's own type.** A `floor` that returned an integer
would have no answer for the operands that do not fit one — the caller who wants an integer is the
caller who knows the range, and casts.

`round` goes **away from zero** at a half, which is C's rule and not the banker's rounding a printed
value gets. `trunc` goes towards zero, which is what a cast already does.

**`fmod` is not `%`.** The integer types have `Rem` and the floats do not, because a float remainder is
a library operation rather than an instruction. It keeps the sign of the receiver.

**`atan2` takes the two coordinates rather than their ratio**, which is what lets it tell the four
quadrants apart, and the receiver is the *vertical* coordinate — matching the argument order the name
has had since Fortran.

**`lerp` is written `a + (b - a) * t` rather than `a * (1 - t) + b * t`.** The second form is exact at
`t = 1` and this one is exact at `t = 0`, and starting where you said you would start is what a caller
notices.

**`signum` answers a zero with that zero** rather than with a one it cannot justify: it is a pair of
comparisons, so it does not see a negative zero, and a NaN satisfies neither comparison and leaves by
the same arm holding itself. `abs` and `copysign` are the other two readings of a sign, and those
*do* see a negative zero, because they work on the bit.

### Hyperbolics, and where they have no answer

```sysl
import sysl.math.Float

var one = 1.0
var two = 2.0
var half = 0.5
var below = 0.5
var outside = 2.0
var neg = -3.0

print(one.sinh(), one.cosh(), one.tanh())
print(one.sinh().asinh(), two.cosh().acosh(), half.tanh().atanh())
print(neg.asinh(), below.acosh().is_nan(), outside.atanh().is_nan())
```

```output
1.1752 1.54308 0.761594
1 2 0.5
-1.81845 true true
```

**The three inverses are the ones with domains.** `acosh` wants an argument of at least one, `atanh`
one strictly between −1 and 1, and `asinh` is defined everywhere — which is why the third line asks
two of them for an answer they do not have.

**Outside a domain the answer is a NaN, not a trap.** That is the same thing `asin` and `acos` do
outside theirs: a float has a value meaning *no answer*, and the library hands it back rather than
stopping the program. It is quiet, so a program that can reach outside a domain should say what it
does about it — `is_nan` is the test, and a NaN that flows on will keep failing every comparison it
meets.

### The type's own values

```sysl
import sysl.math.Float

var f: f32 = 2.0f32

print(real.epsilon(), real.max_value())
print(f.sqrt(), f32.pi(), f32.epsilon())
```

```output
2.22045e-16 1.79769e+308
1.41421 3.14159 1.19209e-07
```

**These are members with no receiver, reached through the type**, and they are what makes the defaults
possible at all: a `signum` needs a one to answer with and a `recip` needs a one to divide, and
neither can be written in a body shared by two widths unless there is a way to ask a type for its own
one. `Self.one()` is that way, so a routine bounded by `[T: Float]` can build a value of a width it
has never met.

**The two identities are not `Float`'s own, and that reasoning is why.** It never stopped at floats —
a generic sum wants a zero and a generic product wants a one, whatever the elements are — so `zero`
and `one` are the core traits [`Zero` and `One`](/reference/traits/), which `Float` *requires*. They
live in the standard module beside the operators whose identities they are, so nothing imports them,
and a body bounded by `[T: Add + Zero]` reaches `T.zero()` for a `real`, an `f32` and a
[`Complex`](/library/complex/) alike without asking for the rest of `Float`.

**The integers are among them too, and no block below says so.** An `impl` can only name types that
exist and the `iN`/`uN` families are open — a program may write `u256` — so no list of blocks covers
them; their membership is the compiler's, exactly as `Signed`'s and `Bits`' are. What made this the
last of the three to arrive is that a provided membership used to have to be a method with a
**receiver**, and a `zero()` has no value to be called on. One without a receiver is reached through
the type instead, and `T.zero()` at an integer lowers to the literal. `[T: Add + Zero]` therefore
takes an `int` and a `u256` beside the `real`, the `f32` and the [`Complex`](/library/complex/).

A **constrained subtype** is the one thing left out, and it is the difference between a value and an
operation: a subtype has every operation its base has, and a range written to exclude zero has not
got a zero. [Expressions](/reference/expressions/) states the rule with its edges.

`epsilon` is what a convergence test should be written against — a loop that stops when two iterations
agree to within a few epsilons stops at the right point at *both* widths, where a literal tolerance
does not.

The two that no literal spells get bodies that say what they are: `infinity()` is `1.0 / 0.0` and
`nan()` is `0.0 / 0.0`. Dividing a float by zero is not the error dividing an integer by zero is — IEEE
754 says what the answer is, and this is where the library says it too.

The only thing that stays per width beyond the libm bindings is the pair of angle conversions, whose
factor is π over 180 — and 180 is not something a zero and a one can be built up into.

### NaN, and what compares to it

```sysl
import sysl.math.{Float, infinity, nan, min}

var one = 1.0

print(nan().is_nan(), infinity().is_infinite(), one.is_finite())
print(min(nan(), one).is_nan(), min(one, nan()).is_nan())
```

```output
true true true
true false
```

**`is_nan` is `self != self`** — the only value not equal to itself, which is both the definition and
the test, and the reason an equality check cannot be used to look for one. `is_infinite` asks whether
the magnitude exceeds the largest finite value, a condition only the two infinities meet and which a
NaN fails the way it fails every comparison.

**That second line is the one to read carefully.** `min(nan(), 1.0)` is a NaN and `min(1.0, nan())` is
`1.0`, and neither is a bug. A NaN is less than nothing and greater than nothing, so the single
comparison each of these makes is false whichever way round the operands go, and both fall through to
the arm holding the **first** argument.

That is said here rather than worked around. Propagating a NaN from one argument position while
dropping it from the other is what C's `fmin` was criticised for — and the alternative is a comparison
per argument on every call, to spare a case a caller can see coming. A program that must reject a NaN
tests for one.

## `min`, `max` and `clamp` are not `Float`'s

```sysl
import sysl.math.{min, max, clamp}

var half = 2.5

print(min(3, 7), max(3, 7), clamp(12, 0, 10))
print(min("b", "a"), max(half, 1.5))
print(clamp(-5, 0, 10), clamp(5, 0, 10))
print(min((1, 2), (1, 3)))
```

```output
3 7 10
a 2.5
0 5
(1, 2)
```

**They are generic over `Ord`, and that is why they are in a file of their own.** Nothing about
picking the smaller of two things is arithmetic: `min` over the integers is the same three words as
`min` over the floats, over a string, over a tuple, and over any type a program has written an
`lt` for. A version living on `Float` would have been the narrowest useful one and would have left
every other type asking why.

**A tie answers with the first argument.** `min` is written `if b < a then b else a` rather than the
other way round, and for types whose equality does not mean identity — a record ordered on one field,
a pair ordered on its first — which of two indistinguishable values comes back is something a caller
can observe. Taking the first is what makes a fold over a sequence stable.

`clamp` tests the low end first, so an inverted range answers `low`. There is no check that the two
bounds are the right way round: a bound is nearly always a constant or a length at the call site, and
a [contract](/reference/errors/) is the tool for saying so where it is not.

## Comparing floats that were computed

Binary floating point does not hold `0.1 + 0.2 == 0.3`, so a program that checks a computation
against a written-down number needs a tolerance rather than an equality:

```sysl
import sysl.math.{approx_eq, approx_eq_rel, nan, infinity, Float}

print(approx_eq(0.1 + 0.2, 0.3, 1e-12), approx_eq(1.0, 1.5, 0.1))
print(approx_eq(infinity(), infinity(), 0.0), approx_eq(nan(), nan(), 1.0))
print(approx_eq_rel(1e18, 1e18 + 1000.0, 1e-6), approx_eq_rel(1.0, 1.5, 1e-6))
```

```output
true false
true false
true false
```

`approx_eq` takes an **absolute** tolerance, which is what a delta comparison is. `approx_eq_rel`
scales it to the larger of the two operands, so the tolerance reads as a fraction — `0.001` means
"within a tenth of a percent" whatever the magnitude — and is the one to reach for when the values
could be any size. Near zero the relative form becomes strict, since the scale goes to zero with the
operands; that is the case the absolute form is for.

Two behaviours are worth knowing rather than discovering. **Identical infinities are close**, because
both functions test equality before subtracting — `inf - inf` is a NaN, and the subtraction alone
would call a value unequal to itself. And **a NaN is close to nothing, including another NaN**,
whatever the tolerance, which falls out of the comparisons and agrees with `==`.

Each has an assertion beside it, which stops the program and names both values and the tolerance
rather than answering `bool`:

```sysl
import sysl.math.assert_approx_eq

assert_approx_eq(0.1 + 0.2, 0.3, 1e-12)
assert_approx_eq(0.1 + 0.2, 0.3, 1e-12, "the sum")
print("both held")
```

```output
both held
```

`assert_approx_eq_rel` is the same against the relative test. **They live here rather than beside
[`assert_eq`](/library/core/) in the core**, and that is forced rather than chosen: `Float` is
declared in this module and reaches `Eq`, `Ord` and the arithmetic traits in the core, so `sysl.math`
depends on `sysl` — and a float assertion written in the core would point an edge back the other way,
which [the module graph](/reference/modules/) refuses. Anyone writing float assertions imports this
module already, since it is where the float functions are.

Mixing types is refused, as everywhere else in the language:

```sysl
import sysl.math.min

print(min(1, 2.0))
```

```error
'b' of 'sysl.math.min' is int, but real was given
```

## `Signed` and `Bits` — a different mechanism

```sysl
trait Signed
    abs(self) -> Self
    signum(self) -> Self

trait Bits
    width() -> u32
    count_ones(self) -> u32
    count_zeros(self) -> u32
    leading_zeros(self) -> u32
    trailing_zeros(self) -> u32
    leading_ones(self) -> u32
    trailing_ones(self) -> u32
    reverse_bits(self) -> Self
    rotate_left(self, n: u32) -> Self
    rotate_right(self, n: u32) -> Self
```

**Neither of these has an `impl` block anywhere, and neither could.** `Float` is a trait with an `impl`
per width because the floating formats are a [closed set](/reference/types/) of four. The integers
are an [open family](/reference/types/):
`i5` and `u12` are types a program may name, so there is no finite list of scalars to write an `impl`
for, and five blocks covering `i8` through `isize` would leave `i128` and every narrow width without
one — a worse surface than none at all.

So membership is the **compiler's**, by the same rule that makes an `int` an `Add` without anything
having written `impl Add for int`. What is in the source file is the part a declaration can say: the
names, the signatures, and what each one means.

**The trait still has to be in scope to be reached.** That is what a compiler-provided membership does
*not* change — it settles which types have the member, not which files may name it:

```sysl
import sysl.math.pi

var x = 2.0

print(pi, x.sqrt())
```

```error
real has 'sqrt' from sysl.math.Float, and that trait is not in scope here — import it to reach the member
```

Importing the module is not the same as importing the trait: `pi` is in scope on that line and
`sqrt` is not.

`Signed` covers the signed widths only; `Bits` covers both signednesses, because a bit pattern is a
bit pattern:

```sysl
import sysl.math.Signed

var u = 5u8

print(u.abs())
```

```error
type 'byte' has no method 'abs'
```

### `Signed`

```sysl
import sysl.math.Signed

var n = -42
var z = 0
var m: i32 = -2147483647 - 1
var big: i128 = -170141183460469231731687303715884105727

print(n.abs(), n.signum(), z.signum())
print(m.abs())
print(big.abs(), big.signum())
```

```output
42 -1 0
-2147483648
170141183460469231731687303715884105727 -1
```

**At the most negative value, `abs` answers that value again.** The magnitude is one larger than the
width can hold, and [plain integer arithmetic in sysl wraps](/reference/types/) — so this is what the
two's-complement negation beside it already does, and the alternative would be a member that traps
where the `-` next to it does not.

`signum` answers in `Self` rather than a fixed width, so it can be multiplied back into a value of the
same type — which is what a signum is usually for.

### `Bits`

```sysl
import sysl.math.Bits

var u = 0b1011u8
var zero8 = 0u8
var all8 = 255u8
var wide: u32 = 1u32

print(u.count_ones(), u.count_zeros())
print(u.leading_zeros(), u.trailing_zeros())
print(u.leading_ones(), u.trailing_ones())
print(u.reverse_bits(), u.rotate_left(1u32), u.rotate_right(1u32))
print(zero8.leading_zeros(), zero8.trailing_zeros())
print(all8.leading_ones(), all8.count_zeros())
print(wide.leading_zeros(), wide.rotate_right(1u32))
print(u8.width(), u32.width(), i64.width())
```

```output
3 5
4 0
0 2
208 22 133
8 8
8 0
31 2147483648
8 32 64
```

**`width()` is asked of the TYPE and not of a value**, which is what the empty parentheses on a bare
type name are saying: every `u32` has the same answer, and a body that wants the width usually has no
value of the type in hand yet. It is the same shape `zero()` and `one()` have.

**It is what makes a bit expression writable in a body that does not know its own width.** Reversing
the low `n` bits is one line with it and a loop without:

```sysl
import sysl.math.Bits

low[T: Bits + Shr](v: T, n: u32) -> T = v.reverse_bits() >> T(T.width() - n)

print(low(0b001u8, 3), low(0b001u32, 3))
```

```output
4 4
```

**The shift count is converted, and that is the one thing to know before writing one of these.** The
width arithmetic happens at `u32` because that is what `width()` answers, and a `u8` shifts by a `u8`
— so `T(...)` is what carries the count back to the receiver's own type. Neither a bare `Shr` bound
nor `Shr[u32]` reaches a `u8` without it.

**The answer is a `u32` and not `Self`**, for the reason the rotation amount is: a width is a count of
bit positions rather than a value of the type it measures. At `Self` a `u8` could not state its own
width plus one, and `u8.width() + 248` would wrap to zero instead of answering `256`.

**Every one of these is a shift-and-mask loop a program would otherwise write, and every one is a
single instruction on the machines sysl targets** — `count_ones` is `popcnt`, `leading_zeros` is
`lzcnt` or `clz`, the rotations are `rol` and `ror`. That is the case for a member rather than a
comment recommending a loop.

**Zero answers the width, at both ends**, rather than being undefined the way the bare machine
instruction is on some targets — `0u8.leading_zeros()` is 8 and so is its `trailing_zeros`. That is
what makes `leading_zeros` usable as "how far left is the top bit" with no special case in front of
it, and what makes the same program print the same number on every machine.

**`count_zeros` is worth having rather than left to a subtraction**, because it says what it means at
the point of use — `width() - count_ones()` is the same number arrived at by arithmetic a reader has
to follow. `leading_ones` and
`trailing_ones` are the same pair counted over set bits, so `-1` answers the width and `0` answers
nothing.

**The rotation amount is taken modulo the width**, so every amount is meaningful and none of it is
undefined — which is the whole reason to call this rather than write `(x << n) | (x >> (w - n))`, an
expression that shifts by the width when `n` is zero and is undefined when it does. The amount is a
`u32` rather than `Self`, because how far to rotate is a count of bit positions and not a value of the
type being rotated: a narrow receiver would otherwise be unable to state an amount its own width
cannot hold.

**`reverse_bits` is not a byte order.** The width is the receiver's, so it is a different function at
every type.

### There is deliberately no `swap_bytes`

```sysl
import sysl.math.Bits

var w: u32 = 7u32

print(w.swap_bytes())
```

```error
type 'uint' has no method 'swap_bytes'
```

Reversing the byte order needs a whole number of bytes and at least two, so a `u24` has no answer to
it and a `u4` has none either. **Every member of `Bits` is total over every integer type**, because a
`[T: Bits]` body is written once and instantiated later — a member that worked at `u32` and not at
`u24` would turn a bound that was supposed to have *proven* an operation into a failure at somebody
else's instantiation.

A program that means to reorder bytes has the shifts, and knows its own width while writing them.

## `Magnitude` — how big, when that is not which is greater

```sysl
trait Magnitude
    type Size: Ord
    magnitude(self) -> Self::Size
```

**The associated type is the whole of why this is a trait rather than a function.** A magnitude has to
say what it answers *with*, and there is no one answer: the modulus of a complex number is a real, the
magnitude of an integer is an integer of that same width, and a rational's is a rational. Fixing the
result to `real` would be a floating-point commitment made on behalf of element types with no floating
point in them; fixing it to `Self` would leave `Complex` out, which is the one type the trait exists
for. [`type Size`](/reference/traits/#a-trait-may-declare-an-associated-type) lets each implementation
answer in its own terms, and a generic body name the answer without knowing it.

```sysl
import sysl.math.Magnitude
import sysl.math.complex.Complex

var narrow: f32 = (-7.5f32).magnitude()
var wide: real = Complex(3.0, 4.0).magnitude()

print((-9).magnitude(), 200u8.magnitude())
print(narrow, wide)
```

```output
9 200
7.5 5
```

The two annotations are the claim: an `f32` measures in `f32` and never widens, and a
`Complex[real]` measures in `real` because a modulus is a real number however the parts are stored.

**It is not `Ord` on the values, and that distinction is the reason it exists.** `Complex` has no
`Ord` on purpose — no order on the plane respects the arithmetic — and yet `|z|` orders complex
numbers by size perfectly well:

```sysl
import sysl.math.Magnitude

largest[T: Magnitude](xs: []const T) -> T::Size
    var best = xs[0].magnitude()

    for x in xs do if best < x.magnitude() then best = x.magnitude()

    best

print(largest([3.0, -40.0, 7.0]), largest([3, -40, 7]))
```

```output
40 40
```

`largest` names `T::Size` once, in its result, and compares values of it without knowing what one is.
That is the shape the `sh.sysl.linalg` package pivots on: Gaussian elimination chooses the largest remaining
cell in a column, which is a comparison every element type can answer and `<` is not.

**A bound is not the only way to reach `Size`, and the other way is what lets one function serve types
with nothing else in common.** `largest` is generic, so each call is compiled for one element type and
its slice holds one kind of thing. Writing the trait as an object instead forgets the type and keeps
the answer — `&Magnitude[real]` is *some* type that measures in a real, decided where the call is
rather than where the function is:

```sysl
import sysl.math.Magnitude
import sysl.math.complex.Complex

size(x: &Magnitude[real]) -> real = x.magnitude()

var scalar: real = -7.5

print(size(scalar), size(Complex(3.0, 4.0)))
```

```output
7.5 5
```

A `real` and a `Complex` share no supertype, no layout and no arithmetic; `&Magnitude[real]` is the
whole of what that parameter promises, and it is enough. The bracket is the sugar — `Magnitude` takes
no arguments of its own and declares exactly one associated type, so `&Magnitude[real]` and
`&Magnitude[Size = real]` are the same type, spelled two ways
([an object may fix the associated type](/reference/traits/#an-object-may-fix-the-associated-type)).

**An `f32` does not fit that parameter, and that is the trait working rather than a limitation.** It
measures in `f32`, so it belongs to `&Magnitude[f32]` — a different type, and the object has to say
which it is for the same reason the bound had to name `T::Size`.

### The three memberships, and the one written over a family

`real` and `f32` each measure in themselves. Every integer type — at every width and either
signedness — arrives through **one blanket block** over `Integer`, the same shape `Display` is written
in, because the `iN`/`uN` families are open and no list of blocks covers them. `Complex[F]` measures
in `F`, and lives in [`sysl.math.complex`](/library/complex/).

**At the most negative value a signed integer answers that value again**, exactly as
[`Signed.abs`](#signed) does — the magnitude is one larger than the width can hold, and
two's-complement negation wraps:

```sysl
import sysl.math.Magnitude
import sysl.math.Signed

var edge: i8 = -128

print(edge.magnitude(), edge.abs())
```

```output
-128 -128
```

Answering anything else would need a wider type to answer in, which is a promise a size cannot make.

### One name per type, so `Size` is spent

An associated type is written without its trait — `T::Size` and never `T::Magnitude.Size` — so **a
type has at most one associated type of any one name**, and the refusal lands on the block that
creates the collision:

```sysl
trait Sized
    type Size
    extent(self) -> Self::Size

impl[T: Integer + Zero] Sized for T
    type Size = T
    extent(self) -> Self::Size = self

print(1)
```

```error
already implements 'sysl.math.Magnitude', which declares an associated type 'Size'
```

That is the price of the name and it is worth knowing before picking one: a library trait covering a
family spends its associated type's name for every member of that family. A program wanting its own
size-like trait over the integers picks a different word for the type it answers with — and one word
is the whole of the difference between the block above and this one:

```sysl
trait Sized
    type Extent
    extent(self) -> Self::Extent

impl[T: Integer + Zero] Sized for T
    type Extent = T
    extent(self) -> Self::Extent = self

print(7.extent(), 200u8.extent())
```

```output
7 200
```

## The arithmetic above the operators

Six free functions sit above `Signed` and `Bits`, and they are what the operators do not give you:

```
pow(base, exponent)          gcd(a, b)                lcm(a, b)
divmod(a, b) -> T, T         is_power_of_two(x)       next_power_of_two(x) -> Option[T]
```

```sysl
@no_alloc
@no_os

import sysl.math.{pow, gcd, lcm, is_power_of_two, next_power_of_two}

print(pow(2, 10), pow(3, 0u32))
print(gcd(12, 18), lcm(4, 6), lcm(21, 6))
print(is_power_of_two(8), is_power_of_two(0))
print(next_power_of_two(17).unwrap_or(-1))
```

```output
1024 1
6 12 42
true false
32
```

### Free functions, and the bound is the specification

**None of these is a trait member**, and the reason is the one this page already gave for `min`. A
member has to belong to a trait, and the trait is what decides which types have it — but nothing here
is a question about a *bit pattern*, which is what `Bits` collects, nor about having a sign, which is
what `Signed` collects.

What each one actually needs is written in its own bound instead:

| function | bound |
|---|---|
| `pow` | `Mul + Ord` |
| `gcd` | `Rem + Eq + Ord + Sub` |
| `lcm` | `Rem + Eq + Ord + Sub + Div + Mul` |
| `divmod` | `Div + Rem` |
| `is_power_of_two` | `Bits + Ord` |
| `next_power_of_two` | `Bits + Ord + Shl + Sub` |

Read `gcd`'s: `Rem` because Euclid's algorithm is a remainder loop, `Sub` and `Ord` because it has to
answer a magnitude — and **deliberately not `Signed`**, which would have shut the unsigned widths out
of a function that serves them perfectly well.

That is also the thing a trait member could not have offered. The memberships of `Bits` and `Signed`
are the compiler's, and **no program can join them** — so a member would have been closed to a
program's own numeric type forever, where a bound is satisfied by whoever satisfies it.

When one is not, the bound is what says so, by name:

```sysl
import sysl.math.is_power_of_two

print(is_power_of_two(8.0))
```

```error
'sysl.math.is_power_of_two' requires its type parameter 'T' to implement 'sysl.math.Bits', but real does not
```

And these are ordinary names in the module, so they are reached the way `pi` is — by importing them,
not by importing the module:

```sysl
import sysl.math.pi

print(gcd(12, 18), pi)
```

```error
undefined function 'gcd'
```

### `pow`

```sysl
@no_alloc
@no_os

import sysl.math.pow

print(pow(2, 10), pow(-3, 3u32))
print(pow(2, 64u32), pow(2u8, 9u32))
```

```output
1024 -27
0 0
```

**The exponent is a `u32` and not a `T`**, because how many times to multiply is a *count* rather
than a value of the type being multiplied — the same reason `rotate_left` takes one. A negative
exponent has no answer among the integers, and saying so in the type is better than a trap:

```sysl
import sysl.math.pow

print(pow(2, -1))
```

```error
the literal -1 does not fit uint
```

**An overflowing power wraps**, as every other integer operation in the language does. `pow(2, 64)`
at `int` is `0` and `pow(2u8, 9)` is `0`, both arrived at honestly — the doubling that overflows is a
`*` like any other. A program that needs to know uses
[the checked operations](#checked-and-overflowing-arithmetic) below, one step at a time.

The implementation is repeated squaring, so the exponent costs a logarithmic number of multiplies
rather than a linear one.

**This one is the integers'.** Its bound admits a `real`, and the body does not:

```sysl
import sysl.math.pow

print(pow(2.0, 10u32))
```

```error
cannot initialize 'acc': declared real but the value is int
```

A float raises through [`Float`](#float)'s own member — `2.0.pow(10.0)` — which takes its exponent as
a `Self` rather than a count, because a float exponent is a meaningful thing to have and an integer
one is not the same operation.

### Checked and overflowing arithmetic

**Plain integer arithmetic wraps**, which is the right default: it is what the hardware does, it is
one instruction, and a program that has proved its values fit pays nothing for the proof. What it is
not is a default for a program that *cannot* prove it — a parser reading a number out of a document,
a running total over untrusted input, an interpreter whose own integers promote to arbitrary
precision when they stop fitting. Each of those needs the question *did that fit?* answered, and the
answer is not recoverable afterwards: the wrapped result of `a + b` is indistinguishable from the
honest sum of two other operands.

**There are two surfaces, because two questions get asked.** `checked_add`, `checked_sub` and
`checked_mul` answer with an `Option`, for the caller that abandons the operation. `overflowing_add`,
`overflowing_sub` and `overflowing_mul` answer with the wrapped value *and* the flag, for the caller
that has somewhere else to go — an interpreter reaching for a wider representation wants to know it
overflowed and does not want the narrow answer at all, while a checksum wants both.

```sysl
import sysl.math.{checked_add, checked_mul, overflowing_add, overflowing_mul}

val most: long = 9223372036854775807
val least: long = -9223372036854775807 - 1
val root: long = 3037000499

print(checked_add(most, -1).expect("it fits"))
print(checked_add(most, 1).is_none())
print(checked_mul(root, root).expect("it fits"), checked_mul(root + 1, root + 1).is_none())

val (wrapped, overflowed) = overflowing_add(most, 1)

print(wrapped, overflowed)
print(overflowing_mul(least, -1).1)
```

```output
9223372036854775806
true
9223372030926249001 true
-9223372036854775808 true
true
```

**These are the signed integers', and the bound says so.** Every test in them is a fact about a
two's-complement sign bit: an addition overflows exactly when the operands agree in sign and the
result does not, a subtraction exactly when they disagree and the result disagrees with the minuend.
The unsigned widths overflow by a different rule — a sum below either operand, a difference above the
minuend — so they would need their own bodies, and nothing has needed them yet.

**Multiplication takes whichever of three routes settles it.** Where the leading-zero counts of the
two magnitudes already prove the product fits, nothing is divided at all. Where one operand is `-1`
the answer is a negation, which overflows at exactly one value — and that case is answered *before*
anything divides, because the most negative value over `-1` is the one pair a machine divide traps
on. Otherwise the product is divided back by an operand and compared with the other. The last two
lines above are the second route and the third: `3037000499` squared is the largest square a `long`
holds, and the most negative value times `-1` wraps to itself and says so.

The width is the type's, so the same body serves every signed width — `checked_add` at `i8` refuses
`127 + 1` exactly as `checked_add` at `long` refuses `9223372036854775807 + 1`.

### `gcd` and `lcm`

```sysl
@no_alloc
@no_os

import sysl.math.{gcd, lcm}

print(gcd(12, 18), gcd(-12, 18), gcd(12, -18), gcd(-12, -18))
print(gcd(7, 0), gcd(0, 7), gcd(12u8, 18u8))
print(lcm(4, 6), lcm(0, 5), lcm(21, 6))
```

```output
6 6 6 6
7 7 6
12 0 42
```

**Neither ever answers a negative**, and how `gcd` gets there is worth reading. The magnitude is
taken at the **end** rather than at the start: `%` truncates, so Euclid's loop over negative operands
arrives at the right divisor already, carrying the wrong sign, and one comparison at the end fixes
it. Doing it at the start would have meant negating both operands first — two operations instead of
one, and a signed-only one at that.

Because the negation is written `zero - x` rather than `-x`. Unary minus requires `Neg`, which the
language gives to the **signed** integers alone, while `Sub` is every integer's — so that one
spelling is what keeps `gcd(12u8, 18u8)` on the third value of the second line.

`gcd(x, 0)` is `x`, which is both the identity the loop already produces and the answer number theory
gives: everything divides zero, so the largest divisor the pair has in common is `x`'s own.

**`lcm` divides before multiplying** — `a / gcd(a, b) * b` and not `a * b / gcd(a, b)` — because the
product of the operands overflows at half the width where the answer itself would fit, and the
quotient is exact by construction, `gcd` being a divisor of `a`. That is the same reason `hypot` is
not `sqrt(x*x + y*y)`. A zero operand answers zero rather than dividing by one, since `gcd(0, 0)` is
zero and reaching the division would be a division by it.

### `divmod`

```sysl
@no_alloc
@no_os

import sysl.math.divmod

show()
    val q, r = divmod(17, 5)
    val nq, nr = divmod(-17, 5)

    print(q, r)
    print(nq, nr)

show()
```

```output
3 2
-3 -2
```

Both are the operators' own, so both truncate toward zero and the remainder takes the sign of the
**dividend**. The reason to call this rather than write the two operators is that it says once what a
reader would otherwise have to check twice: that the same two operands feed both.

It answers a [result list](/reference/declarations/) and not a tuple, because the pair travels from
callee to caller and nothing afterwards needs to hold the two together. A caller that *does* need to
hold them writes the tuple itself.

A binding naming several things is a **local** form — its parts have nowhere to write a type — so the
top of a program, which is a body, takes one exactly as a function does:

```sysl
import sysl.math.divmod

val q, r = divmod(17, 5)

print(q, r)
```

```output
3 2
```

Asking for the module's storage instead is what has nowhere to put the types, and is refused:

```sysl
import sysl.math.divmod

static val q, r = divmod(17, 5)

print(q, r)
```

```error
a module-level 'val' states its type, and a binding that names several things has nowhere to write one — declare 'q' and 'r' separately
```

### `is_power_of_two` and `next_power_of_two`

```sysl
@no_alloc
@no_os

import sysl.math.{is_power_of_two, next_power_of_two}

print(is_power_of_two(8), is_power_of_two(0), is_power_of_two(-8), is_power_of_two(1))
print(is_power_of_two(-128i8), is_power_of_two(128u8))
print(next_power_of_two(17).unwrap_or(-1), next_power_of_two(16).unwrap_or(-1))
print(next_power_of_two(0).unwrap_or(-1), next_power_of_two(-5).unwrap_or(-1))
print(next_power_of_two(200u8).is_some(), next_power_of_two(128u8).unwrap_or(0u8))
print(next_power_of_two(100i8).is_some(), next_power_of_two(60i8).unwrap_or(0i8))
```

```output
true false false true
false true
32 16
1 1
false 128
false 64
```

**`is_power_of_two` is one set bit *and* a comparison against zero**, and the second line is why the
comparison is there. `-128i8` has exactly one bit set — `count_ones` answers `1` — and it is
emphatically not a power of two. A comparison costs nothing and is the whole of the difference.

**`next_power_of_two` answers an `Option`, and that is what totality costs here.** `200u8`'s next
power is `256`, which no `u8` holds. Wrapping to zero would be silently wrong, and a trap would make
a library function rule that a caller's arithmetic is a bug — so the absence goes in the **type**,
where a caller has to look at it. Values at or below one answer `1`, negatives included: one is the
smallest power of two and every negative is below it.

**The last two lines are the same width answering differently**, and they are the reason the
implementation works at all. `128` fits a `u8` and does not fit an `i8`, because the top bit of a
signed byte is the sign — so `next_power_of_two(100i8)` is none while `next_power_of_two(128u8)` is
`128`. The body does not ask which `T` it has, which is a question a generic body has no way to put:
it performs the shift and compares the result against zero, since **a signed shift that has reached
the sign bit comes back negative** and an unsigned one never does.

---

Next: [`sysl.time`](/library/time/) — instants, durations, and the calendar between them.
