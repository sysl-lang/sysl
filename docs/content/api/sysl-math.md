---
title: sysl.math
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.math
summary: "Mathematics on the numeric types: the float functions, the integer ones, and the traits that let a program write both at whichever width it is already using."
requires: "no alloc"
---

`max`, `min` and `clamp` over anything ordered; `pi` and the constants a float program stops to
look up; `Float` so that `x.sqrt()` is the same three words at `real` and at `f32`; `Signed` and
`Bits` over the integer widths, with `pow`, `gcd`, `lcm`, `divmod`, `is_power_of_two` and
`next_power_of_two` above them; and `Magnitude` for how large a value is, when that is a different
question from which of two is greater.

**What is here is arithmetic and what is a domain is a submodule.** `sysl.math.complex`,
`sysl.math.bigint` and `sysl.math.decimal` are each a type a program should have to ask for by
name, and each says in its own header why it is not part of this one.

## Index

[`e`](#e) [`ln10`](#ln10) [`ln2`](#ln2) [`pi`](#pi) [`sqrt2`](#sqrt2) [`tau`](#tau) [`approx_eq`](#approx_eq) [`approx_eq_rel`](#approx_eq_rel) [`assert_approx_eq`](#assert_approx_eq) [`assert_approx_eq_rel`](#assert_approx_eq_rel) [`checked_add`](#checked_add) [`checked_mul`](#checked_mul) [`checked_sub`](#checked_sub) [`clamp`](#clamp) [`divmod`](#divmod) [`gcd`](#gcd) [`infinity`](#infinity) [`is_power_of_two`](#is_power_of_two) [`lcm`](#lcm) [`max`](#max) [`min`](#min) [`nan`](#nan) [`next_power_of_two`](#next_power_of_two) [`overflowing_add`](#overflowing_add) [`overflowing_mul`](#overflowing_mul) [`overflowing_sub`](#overflowing_sub) [`pow`](#pow) [`Bits`](#bits) [`Float`](#float) [`Magnitude`](#magnitude) [`Signed`](#signed) [Float for bf16](#float-for-bf16) [Float for f16](#float-for-f16) [Float for f32](#float-for-f32) [Float for real](#float-for-real) [Magnitude for f32](#magnitude-for-f32) [Magnitude for real](#magnitude-for-real) [Magnitude for T](#magnitude-for-t)

## Constants

### `e`

```sysl
const e: real = 2.718281828459045
```

The base of the natural logarithm.

### `ln10`

```sysl
const ln10: real = 2.302585092994046
```

### `ln2`

```sysl
const ln2: real = 0.6931471805599453
```

The natural logarithms of the two bases the other logarithms use, which are what a change of base
multiplies by.

### `pi`

```sysl
const pi: real = 3.141592653589793
```

The ratio of a circle's circumference to its diameter.

### `sqrt2`

```sysl
const sqrt2: real = 1.4142135623730951
```

The square root of two, the diagonal of the unit square.

### `tau`

```sysl
const tau: real = 6.283185307179586
```

The ratio of a circle's circumference to its *radius*, which is the one that appears in the
arguments to `sin` and `cos`: a whole turn is `tau`, a quarter turn is `tau / 4.0`, and no factor
of two has to be carried around to remember it.

## Functions

### `approx_eq`

```sysl
approx_eq[F: Float](a: F, b: F, tol: F) -> bool
```

Whether two floats are within `tol` of each other -- an **absolute** tolerance, which is what a
delta comparison is.

**The equality test in front is not an optimisation.** It is what makes two identical infinities
answer `true`: `inf - inf` is a NaN, and every comparison against a NaN is false, so the
subtraction alone would call a value unequal to itself. It also makes the exact case exact, which
a caller checking a computation against a written-down constant is entitled to.

**A NaN is close to nothing, including another NaN**, whatever the tolerance. That falls out of
the comparisons rather than being decided here, and it agrees with `==`, which is the property
worth having: nothing in this module makes a NaN behave like a number.

This is the wrong test for values whose magnitudes differ wildly -- `1e18` and `1e18 + 1` are a
long way apart in absolute terms and identical to within one part in a million. `approx_eq_rel`
is that test.

### `approx_eq_rel`

```sysl
approx_eq_rel[F: Float](a: F, b: F, tol: F) -> bool
```

Whether two floats agree to within `tol` **relative to the larger of them**, which is the test to
use when the values could be of any size: `tol` reads as a fraction, so `0.001` means "within a
tenth of a percent" at every magnitude.

Scaled by the larger magnitude rather than by `a`, so that the answer does not depend on which
argument was written first. Near zero it becomes strict -- the scale goes to zero with the
operands, so only exact equality passes -- and that is the case `approx_eq` and an absolute
tolerance are for. A caller who needs both writes both, which is what Python's `isclose` bundles
into one call with two tolerances; here they are two functions because a caller who needed the
combined form always knew they did.

### `assert_approx_eq`

```sysl
assert_approx_eq[F: Float + Display](got: F, want: F, tol: F, msg: string = "", file: string = __FILE__, line: long = __LINE__)
```

The two above, written as assertions -- the float half of `sysl.assert_eq`, which floats cannot
use because `==` is the wrong question to ask about them.

**They live here rather than beside `assert_eq`**, and the reason is the module graph rather than
taste: `Float` is declared in this module and reaches `Eq`, `Ord` and the arithmetic traits in
the root, so `sysl.math` depends on `sysl`. A float assertion written in `check.sysl` would put
an edge back the other way, and `reference/modules.md § The module graph is acyclic` holds the
module graph to being acyclic. Anyone writing float assertions has this module in scope already,
since it is where the float functions are. They report the pair and the tolerance for the reason
`assert_eq` reports the pair: a float that missed by one part in `1e9` and one that came back NaN
are the same failed check until the report says which. `Display` joins the bound because `Float`
does not imply it -- the two floats have `impl Display` blocks of their own (`display.sysl`)
rather than a membership the compiler hands out, so asking for it costs a caller nothing and lets
the report exist.

### `assert_approx_eq_rel`

```sysl
assert_approx_eq_rel[F: Float + Display](got: F, want: F, tol: F, msg: string = "", file: string = __FILE__, line: long = __LINE__)
```

### `checked_add`

```sysl
checked_add[T: Signed + Add + BitXor + BitAnd + Ord](a: T, b: T) -> Option[T]
```

`a + b`, or `none` where the sum does not fit.

**The absent answer is the whole point**: an addition that overflowed has no value worth passing
on, so a caller that would otherwise have to remember to look at a flag is handed something it
cannot use without looking.

### `checked_mul`

```sysl
checked_mul[T: Signed + Bits + Mul + Div + Sub + Shl + Eq + Ord](a: T, b: T) -> Option[T]
```

`a * b`, or `none` where the product does not fit.

### `checked_sub`

```sysl
checked_sub[T: Signed + Sub + BitXor + BitAnd + Ord](a: T, b: T) -> Option[T]
```

`a - b`, or `none` where the difference does not fit.

### `clamp`

```sysl
clamp[T: Ord](x: T, low: T, high: T) -> T
```

The receiver held to a range, inclusive at both ends.

An inverted range -- `high` below `low` -- answers `low`, which falls out of testing the low end
first rather than being decided here. There is no check that the two are the right way round: a
bound is nearly always a constant or a length at the call site, and a contract (`16`) is the
tool for saying so where it is not.

### `divmod`

```sysl
divmod[T: Div + Rem](a: T, b: T) -> (T, T)
```

The quotient and the remainder together, as a result list (`reference/declarations.md § Several
results`): `val q, r = divmod(17, 5)` binds `3` and `2`.

This is a **result list and not a tuple** by the discriminator that page draws: the pair travels
from callee to caller and there is nothing afterwards, so nothing needs to hold the two
together. A caller that does need to hold them writes the tuple itself.

Both are the operators' own, so both truncate toward zero and the remainder takes the sign of the
dividend (`01`) -- `divmod(-17, 5)` is `-3` and `-2`. The reason to call this rather than write
the two operators is that it says once what a reader would otherwise check twice: that the same
two operands feed both.

### `gcd`

```sysl
gcd[T: Rem + Eq + Ord + Sub](a: T, b: T) -> T
```

The largest value dividing both, and never a negative one.

**The magnitude is taken at the end rather than at the start**, which is what lets one body serve
both signednesses: `%` truncates (`01`), so Euclid's loop over negative operands arrives at the
right divisor carrying the wrong sign, and one comparison at the end fixes it. Negating through
`zero - x` rather than `-x` is what keeps the unsigned widths in: unary `-` requires `Neg`, which
`01` gives to the signed integers only, while `Sub` is every integer's.

`gcd(x, 0)` is `x`, which is the identity the loop already produces and the answer number theory
gives: everything divides zero, so the largest divisor the pair has in common is `x`'s own.

### `infinity`

```sysl
infinity() -> real
```

Positive infinity, which no literal spells. It is what a running minimum starts at, so that the
first value compared always replaces it, and what an overflow produces.

These two are functions rather than `const`s because each is a division, and they name the `real`
width the way every constant above does. A routine generic over the width asks the type instead --
`T.infinity()`, `T.nan()` -- and these are that answer at one width, so the definition lives in
exactly one place either way.

### `is_power_of_two`

```sysl
is_power_of_two[T: Bits + Ord](x: T) -> bool
```

Whether the value is a power of two -- which is to say, whether exactly one bit is set.

**Zero and the negatives are not**, and the `> zero` in front is what says so. A negative value
has a bit pattern with one bit set only at the most negative value of its width, where
`count_ones` answers one and the number is emphatically not a power of two; a comparison against
zero costs nothing and is the whole of the difference.

### `lcm`

```sysl
lcm[T: Rem + Eq + Ord + Sub + Div + Mul](a: T, b: T) -> T
```

The smallest value both divide, and never a negative one.

**Divided before multiplied** -- `a / gcd(a, b) * b` and not `a * b / gcd(a, b)` -- because the
product of the operands overflows at half the width where the answer itself would fit, and the
quotient is exact by construction, `gcd` being a divisor of `a`. This is the same reason `hypot`
is not `sqrt(x*x + y*y)` in `float.sysl`.

**A zero operand answers zero** rather than dividing by one: zero has no multiple that anything
else also divides, and reaching `gcd(0, 0)`, which is zero, would otherwise be a division by it.

### `max`

```sysl
max[T: Ord](a: T, b: T) -> T
```

The larger of two values, and again the first when they tie.

### `min`

```sysl
min[T: Ord](a: T, b: T) -> T
```

The smaller of two values, and the receiver-side one when they compare equal.

Written as `if b < a` rather than `if a < b` so that a tie answers with `a`: for types whose
equality does not mean identity -- a record ordered on one field, a pair ordered on its first --
which of two indistinguishable values comes back is something a caller can observe, and taking
the first is the answer that makes a fold over a sequence stable.

### `nan`

```sysl
nan() -> real
```

Not-a-number: the answer to a question that has none, such as this one. It compares equal to
nothing, itself included, which is what `is_nan` tests and why an equality check cannot be used to
look for it.

### `next_power_of_two`

```sysl
next_power_of_two[T: Bits + Ord + Shl + Sub](x: T) -> Option[T]
```

The smallest power of two at or above the value, or `none` when that power does not fit in `T`.

**The `Option` is what totality costs here, and it is worth it.** `next_power_of_two(200)` would
be `256`, which no `u8` holds; wrapping to zero would be a silently wrong answer, and a trap would
make a library function decide that a caller's arithmetic is a bug. So the answer's absence is in
the type, where a caller has to look at it. Values at or below one answer `some(1)`, negatives
included -- one is the smallest power of two, and every negative is below it.

**Signedness is read off the result rather than the type**, which is what lets one body serve
both. At an unsigned width the answer stops fitting when it needs the whole width; at a signed one
it stops a bit earlier, because the top bit is the sign. Rather than ask which `T` is -- a
question a generic body has no way to put -- the shift is performed and the result compared
against zero: a signed shift that has reached the sign bit comes back negative, and an unsigned
one never does.

### `overflowing_add`

```sysl
overflowing_add[T: Signed + Add + BitXor + BitAnd + Ord](a: T, b: T) -> (T, bool)
```

`a + b`, and whether it overflowed. The value is the wrapped sum either way.

**The test is the sign bits**, and it is the standard one: a sum overflows exactly when both
operands have the same sign and the result has the other. `a ^ s` has its top bit set when `a` and
the sum disagree in sign, `b ^ s` likewise, and the `&` of the two is negative only when both
disagree -- which cannot happen unless `a` and `b` agreed with each other. Two operands of
opposite signs can never overflow an addition, and the expression says so by construction rather
than by a branch.

### `overflowing_mul`

```sysl
overflowing_mul[T: Signed + Bits + Mul + Div + Sub + Shl + Eq + Ord](a: T, b: T) -> (T, bool)
```

`a * b`, and whether it overflowed. The value is the wrapped product either way.

**Three cases, and the awkward one is why the first two exist.** The general test is to divide the
product back by an operand and see whether the other comes out -- which is exact and needs no
width, but divides, and traps for the one pair a machine divide cannot take: the most negative
value over `-1`. So `-1` is answered before anything is divided, and the negation it becomes
overflows at exactly that value and nowhere else.

**The first case is the cheap one and it is the common one.** A product cannot overflow when the
operands are small enough that it cannot: if the magnitudes occupy `p` and `q` bits then the
product occupies at most `p + q`, so `p + q` below the width is enough, and the leading-zero
counts say what `p` and `q` are without a division. The most negative value survives this test by
failing it -- its magnitude is itself, which is negative, so its leading-zero count is zero and
the sum can never clear the bar.

### `overflowing_sub`

```sysl
overflowing_sub[T: Signed + Sub + BitXor + BitAnd + Ord](a: T, b: T) -> (T, bool)
```

`a - b`, and whether it overflowed. The value is the wrapped difference either way.

**The mirror of the addition's test, with one operand's role changed**: a subtraction overflows
exactly when the operands differ in sign and the result disagrees with the minuend. So the pair
compared is `a ^ b` and `a ^ d` rather than `a ^ s` and `b ^ s`.

It is written out rather than as `overflowing_add(a, zero - b)`, which is wrong at the most
negative value of the width: negating that wraps to itself, so `a - min` would be computed as
`a + min` and the flag would answer about the wrong operation.

### `pow`

```sysl
pow[T: Mul + Ord](base: T, exponent: u32) -> T
```

A value raised to a whole power, by repeated squaring: `pow(2, 10)` is `1024`.

**The exponent is a `u32` and not a `T`**, because how many times to multiply is a count rather
than a value of the type being multiplied -- the same reason `rotate_left` takes one. A negative
exponent has no answer among the integers, and saying so in the type is better than a trap.

**An overflowing power wraps**, as every other integer operation in the language does (`01`).
`pow(2, 64)` at `int` is `0`, arrived at honestly: the doubling that overflows is a `*` like any
other. A program that needs to know writes the check it needs.

## Traits

### `Bits`

```sysl
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

A value's bits, asked the questions the machine can answer in one instruction.

Every one of these is a shift-and-mask loop a program would otherwise write, and every one of
them is a single instruction on the machines sysl targets -- which is the case for a member
rather than a comment recommending a loop. `count_ones` is `popcnt`, `leading_zeros` is `lzcnt`
or `clz`, and the rotations are `rol` and `ror`.

The membership is the compiler's for the reason `Signed`'s is: the integers are an open family,
so there is no finite list of widths to write an `impl` for. Unlike `Signed`, this covers the
**unsigned** widths too -- a bit pattern is a bit pattern, and `01` already gives `&`, `|`, `^`,
`~` and the shifts to every integer at either signedness.

**What is deliberately not here is `swap_bytes`.** Reversing the byte order needs a whole number
of bytes and at least two, so a `u24` has no answer to it and a `u4` has none either. Every
member of this trait is total over every integer type, because a `[T: Bits]` body is written once
and instantiated later: a member that worked at `u32` and not at `u24` would turn a bound that
was supposed to have proven an operation into a failure at somebody else's instantiation. A
program that means to reorder bytes has the shifts, and knows its own width while writing them.

| Member | Signature | Description |
|---|---|---|
| `width` | `width() -> u32` | How many bits the type has, asked of the **type** rather than of a value: `u32.width()` is `32`, and `T.width()` inside a `[T: Bits]` body is the width the body was instantiated at. |
| `count_ones` | `count_ones(self) -> u32` | How many bits are set, and how many are clear. |
| `count_zeros` | `count_zeros(self) -> u32` |  |
| `leading_zeros` | `leading_zeros(self) -> u32` | How many bits above the highest one, and below the lowest one. |
| `trailing_zeros` | `trailing_zeros(self) -> u32` |  |
| `leading_ones` | `leading_ones(self) -> u32` | The same two counted over set bits instead, so that `-1` answers the width and `0` answers nothing -- the mirror of the pair above, and what a run of ones at either end is measured by. |
| `trailing_ones` | `trailing_ones(self) -> u32` |  |
| `reverse_bits` | `reverse_bits(self) -> Self` | The bits in the opposite order: the lowest becomes the highest. |
| `rotate_left` | `rotate_left(self, n: u32) -> Self` | Rotate: the bits shifted off one end arrive at the other. |
| `rotate_right` | `rotate_right(self, n: u32) -> Self` |  |

### `Float`

```sysl
trait Float: Zero + One + Eq + Ord + Neg + Add + Sub + Mul + Div
    max_value() -> Self
    epsilon() -> Self
    infinity() -> Self
    nan() -> Self
    pi() -> Self
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
    signum(self) -> Self
    recip(self) -> Self
    is_infinite(self) -> bool
    is_nan(self) -> bool
    is_finite(self) -> bool
    square(self) -> Self
    log(self, base: Self) -> Self
    lerp(self, to: Self, t: Self) -> Self
```

Mathematics on the floating-point types, offered as a trait rather than as two sets of functions.

The shape was forced by a language decision that has since been reversed, and is kept because it
is the better one anyway. sysl had no overloading when this was written, so a module of free
functions could not call the square root of a `real` and the square root of an `f32` by one name
-- it would have needed `sqrt` and `sqrtf` the way C does, and every caller would have had to
keep track of which width it was holding. `reference/declarations.md § Overloading` now admits
two declarations of one name, so that argument no longer decides anything.

What the trait still buys is the half overloading does not. A method whose result is *arithmetic
over the others* -- the logarithm in an arbitrary base, the hypotenuse -- is written once as a
default and inherited by both widths, where two free functions would need it twice and could
disagree. Dispatch on the receiver is worth having for its own sake too: `x.sqrt()` is the same
three words whichever width `x` is, and changing a declaration from `f32` to `real` sends nobody
editing call sites. `02` allows an `impl` for a built-in precisely so a library can do this.

**What the trait requires and what it answers are two different lists, and the split is where the
mathematics is.** A method whose result C computes -- a range-reduced sine, a correctly rounded
root -- is required, and each width binds it to its own libm entry point. A method that is
arithmetic over the others is a default, written once and inherited by both. So `log` in an
arbitrary base exists in exactly one place, and adding a third floating-point width would be 38
bindings and no new mathematics.

**The constants are members too, and they are how the defaults get to be defaults.** A signum
needs a one to answer with and a reciprocal needs a one to divide, and neither can be written in a
body shared by two widths unless there is a way to ask a type for its own one. `Self.one()` is
that way: a member with no receiver, reached through the type rather than through a value of it
(`02`), so a generic routine bounded by `Float` can build a value of a width it has never met.
What stays per width is what needs a literal that is neither zero nor one -- the two angle
conversions, whose factor is pi over 180.

| Member | Signature | Description |
|---|---|---|
| `max_value` | `max_value() -> Self` | The largest finite value, and the smallest step above one. |
| `epsilon` | `epsilon() -> Self` |  |
| `infinity` | `infinity() -> Self` | The two values no literal spells. |
| `nan` | `nan() -> Self` |  |
| `pi` | `pi() -> Self` | The ratio of a circle's circumference to its diameter, at this width. |
| `sqrt` | `sqrt(self) -> Self` | Roots. |
| `cbrt` | `cbrt(self) -> Self` |  |
| `exp` | `exp(self) -> Self` | Exponentials and their inverses. |
| `exp2` | `exp2(self) -> Self` |  |
| `ln` | `ln(self) -> Self` |  |
| `log2` | `log2(self) -> Self` |  |
| `log10` | `log10(self) -> Self` |  |
| `pow` | `pow(self, exponent: Self) -> Self` | Raising to an arbitrary power, and the length of a two-dimensional vector. |
| `hypot` | `hypot(self, other: Self) -> Self` |  |
| `sin` | `sin(self) -> Self` | Circular trigonometry, in radians. |
| `cos` | `cos(self) -> Self` |  |
| `tan` | `tan(self) -> Self` |  |
| `asin` | `asin(self) -> Self` |  |
| `acos` | `acos(self) -> Self` |  |
| `atan` | `atan(self) -> Self` |  |
| `atan2` | `atan2(self, x: Self) -> Self` |  |
| `sinh` | `sinh(self) -> Self` | Hyperbolic trigonometry, and its inverses. |
| `cosh` | `cosh(self) -> Self` |  |
| `tanh` | `tanh(self) -> Self` |  |
| `asinh` | `asinh(self) -> Self` |  |
| `acosh` | `acosh(self) -> Self` |  |
| `atanh` | `atanh(self) -> Self` |  |
| `floor` | `floor(self) -> Self` | Rounding to an integral value. |
| `ceil` | `ceil(self) -> Self` |  |
| `round` | `round(self) -> Self` |  |
| `trunc` | `trunc(self) -> Self` |  |
| `fmod` | `fmod(self, divisor: Self) -> Self` | The remainder of a division that truncates, keeping the sign of the receiver. |
| `abs` | `abs(self) -> Self` | Sign, taken two ways because the two answer differently at the edges. |
| `copysign` | `copysign(self, sign: Self) -> Self` |  |
| `to_radians` | `to_radians(self) -> Self` | Degrees are what a person writes down and radians are what the functions above take, so the conversion belongs beside them rather than in each program that reads an angle from a file. |
| `to_degrees` | `to_degrees(self) -> Self` |  |
| `signum` | `signum(self) -> Self` | The third reading of a sign, and the one that is arithmetic rather than a bit. |
| `recip` | `recip(self) -> Self` | One over the receiver, which is worth a name because a loop that divides by the same value repeatedly should multiply by this instead. |
| `is_infinite` | `is_infinite(self) -> bool` | Whether the receiver is one of the two infinities. |
| `is_nan` | `is_nan(self) -> bool` | The only value that is not equal to itself, which is both the definition and the test. |
| `is_finite` | `is_finite(self) -> bool` | Neither infinite nor NaN, and so a value the arithmetic below will keep meaning something for. |
| `square` | `square(self) -> Self` | The receiver multiplied by itself, which is worth a name for the same reason `hypot` is: it says what was meant, and it evaluates the operand once where `x * x` written over a call evaluates it twice. |
| `log` | `log(self, base: Self) -> Self` | The logarithm in an arbitrary base. |
| `lerp` | `lerp(self, to: Self, t: Self) -> Self` | The point a fraction of the way from the receiver to another, which is the interpolation every program that animates or resamples writes for itself. |

### `Magnitude`

```sysl
trait Magnitude
    type Size: Ord
    magnitude(self) -> Self::Size
```

A size, in whatever terms the type measures its own.

**The associated type is the whole of why this is a trait rather than a function.** A magnitude has
to say what it answers *with*, and there is no one answer: the modulus of a complex number is a
real, the magnitude of an integer is an integer of that same width, and a rational's is a rational.
Fixing the result to `real` would be a floating-point commitment made on behalf of element types
with no floating point in them, and fixing it to `Self` would leave `Complex` out — which is the
one type the trait exists for. `type Size` lets each implementation answer in its own terms and a
generic body name the answer without knowing it.

**`Size` is `Ord` and nothing more**, because comparing two sizes is the whole of what a size is
for here. A body wanting arithmetic on one is a body that knows which type it is holding.

**It is not `Ord` on the values, and that distinction is the reason it exists.** `Complex` has no
`Ord` on purpose — no order on the plane respects the arithmetic — and yet `|z|` orders complex
numbers by size perfectly well. Gaussian elimination picking its pivot is the worked example:
`sh.sysl.linalg` chooses the largest remaining cell in a column, which is a comparison every
element type can answer and `<` is not.

**It is `sysl.math` rather than the standard module**, which `Zero` and `One` are and this is not.
Those are the identities of `+` and `*`, so they sit beside the operator catalog and cost a reader
nothing; a magnitude has no operator and its neighbours are `abs` and `Float`, which a program
already imports `sysl.math` to reach.

| Member | Signature | Description |
|---|---|---|
| `magnitude` | `magnitude(self) -> Self::Size` |  |

### `Signed`

```sysl
trait Signed
    abs(self) -> Self
    signum(self) -> Self
```

Mathematics on the integer types, which cannot be written the way the floating-point half was.

`Float` is a trait with an `impl` per width, and there are two widths. The integers are an **open
family** (`01`): `i5` and `u12` are types a program may name, so there is no finite list of
scalars to write an `impl` for, and five blocks covering `i8` through `isize` would leave `i128`
and every narrow width without one -- a worse surface than none at all.

So membership here is the compiler's, by the same rule that makes an `int` an `Add` without
anything having written `impl Add for int` (`reference/expressions.md § Operator dispatch`). What
is in this file is the part a source declaration can say: the names, the signatures, and what
each one means. Which types are members, and what each member lowers to, is in `CoreTraits`.

**The trait still has to be in scope to be reached** (`reference/traits.md § Reaching a member
through its trait`), exactly as `Float` does. That is what a compiler-provided membership does
*not* change: it settles which types have the member, not which files may name it. So a program
reaches `abs` by importing `sysl.math`, and a program that would rather define its own `abs` for
its own purposes may still do so.

**Below the two traits are the free functions**, which are here rather than in a file of their own
because they are about the same types -- and which are free functions rather than members for a
reason stated where they begin.

| Member | Signature | Description |
|---|---|---|
| `abs` | `abs(self) -> Self` | The magnitude, with the sign discarded. |
| `signum` | `signum(self) -> Self` | Which side of zero the value is on, as a value of its own type: `-1`, `0`, or `1`. |

## Implementations

### Float for bf16

```sysl
impl Float for bf16
```

Bfloat16 -- binary32's range at a quarter of its precision, and the other way sixteen bits can be
divided up.

**It is not a smaller `f16`, it is a shorter `f32`**, and the difference is the whole reason both
exist. `f16` spends five bits on the exponent and ten on the significand, so it is precise to
about three decimal digits and runs out of range above 65504. `bf16` spends eight and seven: it is
precise to about two digits and reaches 3.4e38, exactly as far as an `f32`, because its bits *are*
an `f32`'s top sixteen. So a value that overflows an `f16` is ordinary here, and a value the two
both hold is held more accurately there.

Everything the width above says about widening applies unchanged and for the same reason: `f32`
holds every `bf16` exactly, and libm has no entry point at this width either.

### Float for f16

```sysl
impl Float for f16
```

Binary16, and the first width whose mathematics is not its own.

**libm stops at `float`.** There is no half-width entry point to bind -- no `sqrth`, no `sinh` that
means the square root of a half -- because no C library offers one, and nothing about the width
suggests one is coming: sixteen bits of significand is a storage format, and the machines that
compute in it compute a lane at a time rather than a value at a time. So every body below widens to
`f32`, calls the `f`-suffixed entry point the width above already binds, and narrows the answer.

**That is not a compromise, it is the correct answer, and the reason is worth stating once here
rather than doubted at each of the thirty-odd methods that use it.** `f32` holds every `f16`
exactly -- more exponent range and more than twice the significand -- so widening loses nothing.
The `f32` result is then within half an `f32` ulp of the true answer, and narrowing rounds it to
the nearest `f16`. Two roundings rather than one, and the first is so much finer than the second
that it changes the result only for an answer sitting within an `f32` ulp of the midpoint between
two `f16` values -- which is the accuracy libm itself promises, not a loss this widening
introduced.

**The constants are written as the mathematics rather than as this width's nearest value.** A
literal is narrowed to the type it is written at, so `3.141592653589793f16` *is* the nearest `f16`
to pi and says which number was meant, where the eight digits that spelling rounds to would only
say what came out. The two that have to be exact are exact anyway: the largest finite `f16` is
65504, and its epsilon is two to the minus tenth.

### Float for f32

```sysl
impl Float for f32
```

Binary32. Every body here is its binary64 counterpart with libm's `f`-suffixed entry point and a
literal of this width, which is the duplication the trait exists to keep out of programs.

### Float for real

```sysl
impl Float for real
```

Binary64, which is what `real` names and what a float literal is unless it says otherwise.

### Magnitude for f32

```sysl
impl Magnitude for f32
```

*At the narrower width the size is an `f32`, and deliberately not the `real` it would widen to.**
Widening is what an implementation fixing the result to `real` would have forced on every caller;
a program working in `f32` compares sizes in `f32` and pays for no conversion.

### Magnitude for real

```sysl
impl Magnitude for real
```

The floats answer with themselves. `abs` clears the sign bit, so a negative zero and a NaN come
back the way `Float` documents them rather than through a comparison of this file's own.

### Magnitude for T

```sysl
impl[T: Integer + Zero] Magnitude for T
```

Every integer type, at every width and either signedness, in one block: the `iN`/`uN` families are
open (`01`), so no list of blocks covers them and a bound over `Integer` is what stands in for the
list. It is the same shape `Display` is written in.

**Negated as `T.zero() - self` rather than as `-self`, which is what keeps the unsigned widths
in**: unary `-` requires `Neg`, which the language gives to the signed integers alone, while `Sub`
is every integer's. On an unsigned type the branch is never taken and the subtraction is never
performed. `gcd` in `integer.sysl` takes the same route for the same reason.

**At the most negative value this answers that value again**, exactly as `Signed.abs` does: the
magnitude is one larger than the width can hold, and two's-complement negation wraps. Answering
anything else would need a wider type to answer in, which is a promise a size cannot make.
