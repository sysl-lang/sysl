---
title: sysl.math.complex
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.math.complex
summary: "Complex numbers, at whichever floating-point width the program is already using."
requires: "no alloc"
---

**Generic over the width rather than fixed at `real`, because `Float` already paid for that.**
`sysl.math` made the one-trait-two-widths investment so that `x.sqrt()` is the same three words at
`real` and at `f32`; a `Complex` that worked only at the wider one would be the first thing in the
library to ignore it, and a program doing signal processing in `f32` would have to write its own.
The parameter costs nothing at run time -- `Complex[real]` and `Complex[f32]` are two structs of
two fields, laid out at their own widths, with no dispatch between them.

**A submodule rather than part of `sysl.math`, because these are names a program should have to
ask for.** `sysl.math`'s own header says it is mathematics *on the floating-point types*, which is
not a sentence a type belongs in, and `Complex` is not a name every numeric program wants taking
up space in its scope. Its siblings, if they ever come, are `sysl.math.rational` and
`sysl.math.fixed`.

**Two files, and the split is by what a member is written inside.** A type's own members live in
its body and are here; the operators are `impl` blocks and live beside it in `operators.sysl`.

The arithmetic below is written for the operands a program actually has rather than for the ones a
textbook formula assumes. Three places differ from the obvious expression, and each is a case
where the obvious one overflows or loses digits at inputs that are perfectly ordinary:
`abs` goes through `hypot`, division scales before it divides, and `sqrt` never squares what it is
about to take the root of.

## Index

[`Complex`](#complex) [Add for Complex[F]](#add-for-complexf) [Add for Complex[F]](#add-for-complexf-1) [Add for f32](#add-for-f32) [Add for real](#add-for-real) [Display for Complex[F]](#display-for-complexf) [Div for Complex[F]](#div-for-complexf) [Div for Complex[F]](#div-for-complexf-1) [Div for f32](#div-for-f32) [Div for real](#div-for-real) [Eq for Complex[F]](#eq-for-complexf) [Magnitude for Complex[F]](#magnitude-for-complexf) [Mul for Complex[F]](#mul-for-complexf) [Mul for Complex[F]](#mul-for-complexf-1) [Mul for f32](#mul-for-f32) [Mul for real](#mul-for-real) [Neg for Complex[F]](#neg-for-complexf) [One for Complex[F]](#one-for-complexf) [Sub for Complex[F]](#sub-for-complexf) [Sub for Complex[F]](#sub-for-complexf-1) [Sub for f32](#sub-for-f32) [Sub for real](#sub-for-real) [Zero for Complex[F]](#zero-for-complexf)

## Types

### `Complex`

```sysl
struct Complex[F: Float]
    re: F
    im: F
```

A complex number at the width `F`: a real part and an imaginary one, laid out as two fields of
that width and nothing else.

| Member | Signature | Description |
|---|---|---|
| `i` | `i() -> Complex[F]` | The imaginary unit, which is the one constant here that has no counterpart among the reals. |
| `from_real` | `from_real(x: F) -> Complex[F]` | A real number as a complex one. |
| `from_polar` | `from_polar(r: F, theta: F) -> Complex[F]` | The point at distance `r` and angle `theta`, which is the constructor a rotation writes. |
| `expi` | `expi(theta: F) -> Complex[F]` | `e^(i*theta)`, the point on the unit circle at that angle. |
| `conj` | `conj(self) -> Complex[F]` | The reflection in the real axis, which is what turns a division into a multiplication and a squared magnitude into a product. |
| `abs` | `abs(self) -> F` | The distance from the origin. |
| `norm_sqr` | `norm_sqr(self) -> F` | The squared magnitude, which is what a comparison of two magnitudes wants: the root is monotone, so taking it to compare is arithmetic thrown away. |
| `arg` | `arg(self) -> F` | The angle from the positive real axis, in radians and in `(-pi, pi]`. |
| `to_polar` | `to_polar(self) -> (F, F)` | The magnitude and angle together, which is the pair `from_polar` takes back. |
| `unit` | `unit(self) -> Complex[F]` | The point on the unit circle in the same direction. |
| `recip` | `recip(self) -> Complex[F]` | One over the value, which is the division the operator would do and is worth a name for the reason `Float.recip` is: a loop dividing repeatedly by the same value should multiply instead. |
| `is_zero` | `is_zero(self) -> bool` |  |
| `is_real` | `is_real(self) -> bool` | On the real axis, and on the imaginary one. |
| `is_imaginary` | `is_imaginary(self) -> bool` |  |
| `is_nan` | `is_nan(self) -> bool` | A NaN in either part poisons every arithmetic result the value reaches, so one is enough. |
| `is_infinite` | `is_infinite(self) -> bool` | Infinite where either part is, and finite only where both are -- so the two are not each other's negation, and a value with a NaN in it is neither. |
| `is_finite` | `is_finite(self) -> bool` |  |
| `near` | `near(self, rhs: Complex[F], eps: F) -> bool` | Within `eps` of another value, which is the comparison a float program means almost every time it writes `==`. |
| `exp` | `exp(self) -> Complex[F]` | `e^z`, which is the magnitude `e^re` turned through the angle `im`. |
| `ln` | `ln(self) -> Complex[F]` | The principal logarithm: the real part is the logarithm of the magnitude, and the imaginary part is the angle. |
| `log` | `log(self, base: F) -> Complex[F]` | The logarithm in a real base, which is the general one and the reason `ln` is spelled for what it is rather than as C's bare `log`. |
| `log2` | `log2(self) -> Complex[F]` | The two bases worth their own names. |
| `log10` | `log10(self) -> Complex[F]` |  |
| `sqrt` | `sqrt(self) -> Complex[F]` | The principal square root, the one with a non-negative real part. |
| `powc` | `powc(self, w: Complex[F]) -> Complex[F]` | `z^w`, by way of the logarithm -- so it carries `ln`'s branch cut, and `(-1)^(1/2)` is `i` rather than `-i`. |
| `powf` | `powf(self, x: F) -> Complex[F]` | `z^x` for a real exponent, which is polar rather than logarithmic: the magnitude is raised by the width's own `pow` and the angle is multiplied, so nothing is turned into a logarithm and back. |
| `powi` | `powi(self, n: int) -> Complex[F]` | `z^n` for an integer exponent, by repeated squaring. |
| `sin` | `sin(self) -> Complex[F]` |  |
| `cos` | `cos(self) -> Complex[F]` |  |
| `tan` | `tan(self) -> Complex[F]` |  |
| `asin` | `asin(self) -> Complex[F]` | The inverses, each written over `ln` and `sqrt` and so carrying their cuts. |
| `acos` | `acos(self) -> Complex[F]` |  |
| `atan` | `atan(self) -> Complex[F]` | *The difference of two logarithms and not the logarithm of a quotient**, for the reason `atanh` below is written the same way: at a pole the quotient form divides by zero, and a complex division by zero is a NaN. |
| `sinh` | `sinh(self) -> Complex[F]` |  |
| `cosh` | `cosh(self) -> Complex[F]` |  |
| `tanh` | `tanh(self) -> Complex[F]` |  |
| `asinh` | `asinh(self) -> Complex[F]` |  |
| `acosh` | `acosh(self) -> Complex[F]` | *Two roots multiplied rather than one root of a product**, which is not the same function: `sqrt(z*z - 1)` picks the wrong branch for `z` on the negative real axis, where this form keeps the cut where `acosh`'s is supposed to be -- everything to the left of one. |
| `atanh` | `atanh(self) -> Complex[F]` | Cut outside `[-1, 1]` on the real axis, with a pole at each end of it. |

## Implementations

### Add for Complex[F]

```sysl
impl[F: Float] Add for Complex[F]
```

### Add for Complex[F]

```sysl
impl[F: Float] Add[F] for Complex[F]
```

### Add for f32

```sysl
impl Add[Complex[f32], Complex[f32]] for f32
```

### Add for real

```sysl
impl Add[Complex[real], Complex[real]] for real
```

### Display for Complex[F]

```sysl
impl[F: Float + Display] Display for Complex[F]
```

`a+bi`, with the sign of the imaginary part written out and never a `+-`.

**The field applies to the whole value and not to each part** (`library/core.md § A specifier is
the whole value's field`), so the four pieces are padded once between them rather than each in
turn. Padding each piece would put spaces inside the number.

**It is measured rather than gathered, and that is the whole of why it allocates nothing.** The
obvious way to pad a compound rendering once is to build the text and hand the finished bytes to
`display_pad`, and that is three intermediate strings a `Complex` printed in a loop pays for every
value. Running the same writes into a `Counting` sink answers the same question -- how wide did it
come out -- and stores nothing. A rendering with no width asked for never builds the sink at all,
so the ordinary print costs one pass.

**It does not follow that this module can declare `@no_alloc`, and it cannot.** A call through
`*Writer` is judged against every `impl Writer` linked into the compilation, and `sysl.buf`'s
`ByteSink` allocates -- so writing into a sink at all is refused under the clause wherever a
growable buffer is in the program. That is a question about the clause rather than about
rendering, and it is open.

The bound is `Float + Display` rather than `Float`, because rendering a part means asking `F` for
its own rendering and `Float` does not require one. Both widths have it, so nothing a program can
reach is excluded -- the compound bound is what says why.

### Div for Complex[F]

```sysl
impl[F: Float] Div for Complex[F]
```

*Smith's algorithm, and not `(ac + bd) / (c*c + d*d)`.**

The textbook quotient computes the divisor's squared magnitude, which overflows to infinity for a
divisor whose parts are above the square root of the largest float and underflows to zero for one
below the square root of the smallest -- in both cases at operands whose quotient is perfectly
representable, and in both cases silently. Dividing the smaller part by the larger first keeps
every intermediate within a factor of two of the answer's own scale, which is the whole of the
trick and costs one comparison.

**A zero divisor is a NaN and not an infinity**, which is the one place the complex quotient does
not follow the real one. Among the reals `1/0` runs off in the only direction there is; a complex
quotient has an argument as well as a magnitude, and a zero divisor fixes neither -- so there is
no complex number to answer with, and the value meaning exactly that is what comes back. A caller
that has a direction in mind knows it, and `is_zero` is the test.

### Div for Complex[F]

```sysl
impl[F: Float] Div[F] for Complex[F]
```

### Div for f32

```sysl
impl Div[Complex[f32], Complex[f32]] for f32
```

### Div for real

```sysl
impl Div[Complex[real], Complex[real]] for real
```

### Eq for Complex[F]

```sysl
impl[F: Float] Eq for Complex[F]
```

### Magnitude for Complex[F]

```sysl
impl[F: Float] Magnitude for Complex[F]
```

The modulus, as the trait that asks every type for its size. `Complex[F]` measures in `F` — the
modulus of a complex number is a *real* number however the parts are stored — which is the case
`Magnitude` carries an associated type for: no implementation could have answered here if the
trait had fixed its result to the subject's own type, and fixing it to `real` would have made a
`Complex[f32]` widen every comparison.

It is the type's own `abs` and nothing more. What the trait adds is that a generic body can *ask*:
a routine bounded by `[T: Magnitude]` compares sizes without naming what they come out at, which
is what lets one elimination run over the reals and over the plane.

### Mul for Complex[F]

```sysl
impl[F: Float] Mul for Complex[F]
```

### Mul for Complex[F]

```sysl
impl[F: Float] Mul[F] for Complex[F]
```

### Mul for f32

```sysl
impl Mul[Complex[f32], Complex[f32]] for f32
```

### Mul for real

```sysl
impl Mul[Complex[real], Complex[real]] for real
```

### Neg for Complex[F]

```sysl
impl[F: Float] Neg for Complex[F]
```

### One for Complex[F]

```sysl
impl[F: Float] One for Complex[F]
```

### Sub for Complex[F]

```sysl
impl[F: Float] Sub for Complex[F]
```

### Sub for Complex[F]

```sysl
impl[F: Float] Sub[F] for Complex[F]
```

### Sub for f32

```sysl
impl Sub[Complex[f32], Complex[f32]] for f32
```

### Sub for real

```sysl
impl Sub[Complex[real], Complex[real]] for real
```

### Zero for Complex[F]

```sysl
impl[F: Float] Zero for Complex[F]
```

The two identities, which `Float` itself requires and which a generic body reaches through a bound
rather than through the type's own members. `zero` is where an accumulator starts and `one` is
where a product does, and neither can be written as a literal in a body that does not know the
width it is holding.
