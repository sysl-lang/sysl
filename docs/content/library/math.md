---
title: The math module
summary: "`sysl.math` — the `Float` trait over both widths, `Signed` and `Bits` over the open integer family, the constants, and `min`/`max`/`clamp` over anything ordered."
weight: 60
---

`sysl.math` is four files and three traits, and the interesting thing about it is that **the three
traits are written three different ways** — because the types they cover are three different shapes.
It requires no capability at all: every name here is reachable under `no alloc` and on a target with
no operating system.

```sysl
no alloc
no os

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
trait Float: Eq + Ord + Neg + Add + Sub + Mul + Div

    // The type's own values, asked without a receiver.
    zero() -> Self
    one() -> Self
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
    floor(self) -> Self
    ceil(self) -> Self
    round(self) -> Self
    trunc(self) -> Self
    fmod(self, divisor: Self) -> Self
    abs(self) -> Self
    copysign(self, sign: Self) -> Self
    to_radians(self) -> Self
    to_degrees(self) -> Self

    // Answered by the trait, once, for both widths.
    signum(self) -> Self
    recip(self) -> Self
    square(self) -> Self
    log(self, base: Self) -> Self
    lerp(self, to: Self, t: Self) -> Self
    is_nan(self) -> bool
    is_infinite(self) -> bool
    is_finite(self) -> bool
```

**A trait rather than two sets of functions, and the shape is forced by a language decision that turns
out to be the better one anyway.** sysl has [no overloading](/reference/expressions/), so free
functions could not call the square root of a `real` and the square root of an `f32` by one name —
it would need `sqrt` and `sqrtf` the way C does, and every caller would have to keep track of which
width it was holding. A trait resolves that on the receiver: `x.sqrt()` is the same three words
whichever width `x` is, and changing a declaration from `f32` to `real` sends nobody editing call
sites.

**The split between what is required and what is answered is where the mathematics is.** A method
whose result C computes — a range-reduced sine, a correctly rounded root — is required, and each width
binds it to its own libm entry point. A method that is *arithmetic over the others* is a default,
written once and inherited by both. So `log` in an arbitrary base exists in exactly one place, and
adding a third floating-point width would be 31 bindings and no new mathematics.

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
per width because there are exactly two widths. The integers are an [open family](/reference/types/):
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
```

```output
3 5
4 0
0 2
208 22 133
8 8
8 0
31 2147483648
```

**Every one of these is a shift-and-mask loop a program would otherwise write, and every one is a
single instruction on the machines sysl targets** — `count_ones` is `popcnt`, `leading_zeros` is
`lzcnt` or `clz`, the rotations are `rol` and `ror`. That is the case for a member rather than a
comment recommending a loop.

**Zero answers the width, at both ends**, rather than being undefined the way the bare machine
instruction is on some targets — `0u8.leading_zeros()` is 8 and so is its `trailing_zeros`. That is
what makes `leading_zeros` usable as "how far left is the top bit" with no special case in front of
it, and what makes the same program print the same number on every machine.

**`count_zeros` is worth having rather than left to a subtraction**, because the width is the fact the
caller would otherwise have to know and this is the member that already knows it. `leading_ones` and
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

---

Next: [`sysl.sync`](/library/sync/) — atomics and the spinlock, which require nothing at all.
