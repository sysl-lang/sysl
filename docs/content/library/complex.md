---
title: The complex module
summary: "`sysl.math.complex` — `Complex[F: Float]`, generic over both float widths: the operators at two argument lists each, the transcendental set, and the branch cuts written down."
weight: 62
---

`sysl.math.complex` is one type. It is a **submodule** rather than part of
[`sysl.math`](/library/math/) because `Complex` is not a name every numeric program wants in scope,
and because `sysl.math` is mathematics *on* the floating-point types — which is not a sentence a type
belongs in.

It requires no capability: every name here is reachable under `no alloc` and on a target with no
operating system.

```sysl
import sysl.math.complex.Complex

var a = Complex(3.0, 4.0)
var b = Complex(1.0, -2.0)

print(a, a.conj(), -a)
print(a.abs(), a.norm_sqr(), a.arg())
print(a + b, a - b, a * b, a / b)
print(a * 2.0, a / 2.0, a + 1.0)
```

```output
3+4i 3-4i -3-4i
5 25 0.927295
4+2i 2+6i 11-2i -1+2i
6+8i 1.5+2i 4+4i
```

**Importing the type is the whole of what a program does.** Every operator above is a conditional
conformance whose condition names `Float` — the library's import, not yours.

## Generic over the width, and why that is not a flourish

```sysl
import sysl.math.complex.Complex
import sysl.math.Float

// A rotation, written once for both widths.
rotate[F: Float](z: Complex[F], theta: F) -> Complex[F] = z * Complex.expi(theta)

var wide = Complex(1.0, 0.0)
var narrow = Complex(1.0f32, 0.0f32)

print(rotate(wide, real.pi() / 2.0))
print(rotate(narrow, f32.pi() / 2.0f32))
```

```output
6.12323e-17+1i
-4.37114e-08+1i
```

`Complex[F: Float]` is generic over the [`Float`](/library/math/) trait, so it is a `Complex[real]`
or a `Complex[f32]` and never a widening of one into the other. The two are ordinary structs of two
fields laid out at their own widths, with no dispatch between them — the parameter costs nothing at
run time.

The library had already paid for this: `sysl.math` made the one-trait-two-widths investment so that
`x.sqrt()` is the same three words whichever width `x` is. A complex type fixed at `real` would have
been the first thing in the library to ignore it, and anyone working in binary32 would have had to
write their own.

The two answers above differ in their real part because a quarter turn is not exactly representable
in binary and `f32` has seven digits where `real` has sixteen. That is the ordinary floating-point
fact, and the page shows it rather than choosing an example that hides it.

## The surface

```sysl
struct Complex[F: Float]
    re: F
    im: F

    // Values that need no receiver.
    zero() -> Complex[F]
    one() -> Complex[F]
    i() -> Complex[F]
    from_real(x: F) -> Complex[F]
    from_polar(r: F, theta: F) -> Complex[F]
    expi(theta: F) -> Complex[F]

    // What a value is.
    conj(self) -> Complex[F]
    abs(self) -> F
    norm_sqr(self) -> F
    arg(self) -> F
    to_polar(self) -> (F, F)
    unit(self) -> Complex[F]
    recip(self) -> Complex[F]

    // Questions with a yes or a no.
    is_zero(self) -> bool
    is_real(self) -> bool
    is_imaginary(self) -> bool
    is_nan(self) -> bool
    is_infinite(self) -> bool
    is_finite(self) -> bool
    near(self, rhs: Complex[F], eps: F) -> bool

    // Exponentials, logarithms and roots.
    exp(self) -> Complex[F]
    ln(self) -> Complex[F]
    log(self, base: F) -> Complex[F]
    log2(self) -> Complex[F]
    log10(self) -> Complex[F]
    sqrt(self) -> Complex[F]
    powc(self, w: Complex[F]) -> Complex[F]
    powf(self, x: F) -> Complex[F]
    powi(self, n: int) -> Complex[F]

    // Trigonometry, circular and hyperbolic, each with its inverse.
    sin(self) -> Complex[F]
    cos(self) -> Complex[F]
    tan(self) -> Complex[F]
    asin(self) -> Complex[F]
    acos(self) -> Complex[F]
    atan(self) -> Complex[F]
    sinh(self) -> Complex[F]
    cosh(self) -> Complex[F]
    tanh(self) -> Complex[F]
    asinh(self) -> Complex[F]
    acosh(self) -> Complex[F]
    atanh(self) -> Complex[F]
end Complex
```

Plus `Add`, `Sub`, `Mul`, `Div`, `Neg`, `Eq` and `Display` — with `Add`, `Sub`, `Mul` and `Div`
written **twice each**, once for another complex number and once for a real on the right.

**Scaling is `Mul` at a second argument list rather than a second trait.** The complex numbers are a
vector space over the reals, and scaling is the operation a transform performs most often: every
sample of an inverse, every window function, every spectrum read in decibels. `z * k` is the same
answer as `z * Complex(k, 0)` and costs a quarter as much, and
[which of the two a `*` means](/reference/expressions/) is settled by the type of its right operand.

**There is no `Ord`**, because the complex numbers are not ordered — no order on them is compatible
with the arithmetic:

```sysl
import sysl.math.complex.Complex

print(Complex(1.0, 0.0) < Complex(2.0, 0.0))
```

```error
'<' is not defined for sysl.math.complex.Complex[real]
```

sysl keeps `Eq` and `Ord` independent traits precisely so a type can have the one without the other.
There is nothing to opt out of.

**There is no scalar on the left.** `2.0 * z` would need an `impl Mul[Complex[F]] for F`, which is an
implementation of a library trait for a built-in written by a module that owns neither — and it could
not be one generic block, since `F` is a parameter rather than a type an `impl` can be written for.
`z * 2.0` is the same product.

## Constructing one

```sysl
import sysl.math.complex.Complex

var i: Complex[real] = Complex.i()

print(i, i * i)
print(Complex.from_real(2.0), Complex.from_polar(2.0, 0.0), Complex.expi(0.0))

var a = Complex(3.0, 4.0)
var p = a.to_polar()

print(p.0, p.1, a.unit().abs(), a.recip() * a)
```

```output
0+1i -1+0i
2+0i 2+0i 1+0i
5 0.927295 1 1+0i
```

**`zero()`, `one()` and `i()` take an annotation, and the ones beside them do not.** They are
[associated functions](/reference/generics/) of a generic type, so their width comes from what the
context expects — and there is nothing to read it off in the middle of an expression. Everything else
here is inferred from an argument: `Complex.from_real(2.0)` is a `Complex[real]` because `2.0` is a
`real`.

`expi(theta)` is `from_polar` at radius one, and it has a name because that is how it is reached — a
Fourier transform's twiddle factor, a rotation, a phasor.

## Exponentials, logarithms and trigonometry

```sysl
import sysl.math.complex.Complex
import sysl.math.Float

var a = Complex(3.0, 4.0)

print(a.exp(), a.ln(), a.sqrt())
print(a.log2(), a.log10(), a.log(2.0))
print(a.powi(2), a.powf(0.5), a.powc(Complex(0.0, 1.0)))
print(a.sin(), a.cos(), a.tan())
print(a.sinh(), a.cosh(), a.tanh())
print(a.asin(), a.acos(), a.atan())
print(a.asinh(), a.acosh(), a.atanh())
```

```output
-13.1288-15.2008i 1.60944+0.927295i 2+1i
2.32193+1.3378i 0.69897+0.402719i 2.32193+1.3378i
-7+24i 2+1i -0.0152837+0.395327i
3.85374-27.0168i -27.0349-3.85115i -0.000187346+0.999356i
-6.54812-7.61923i -6.58066-7.58155i 1.00071+0.00490826i
0.633984+2.30551i 0.936812-2.30551i 1.44831+0.158997i
2.29991+0.917617i 2.30551+0.936812i 0.117501+1.40992i
```

**Three powers, because they are not the same function.** `powi` is repeated squaring and is the only
one that is *exact* — `powi(2)` of `3+4i` is `-7+24i` and not something an ulp away from it. `powf`
raises the magnitude and multiplies the angle. `powc` goes through the logarithm, and so carries its
branch cut.

**`ln` is the natural logarithm and is spelled for what it is**, rather than as C's bare `log` — which
reads as though it were the general one and is the single most common way to get a base wrong.
`log(base)` is the general one, and it takes a *real* base.

## The operands a textbook formula does not survive

```sysl
import sysl.math.complex.Complex

var huge = Complex(1.0e200, 1.0e200)
var tiny = Complex(1.0e-200, 1.0e-200)

print(huge.abs(), huge.norm_sqr())
print(huge / huge, tiny / tiny)
```

```output
1.41421e+200 inf
1+0i 1+0i
```

This is why the module exists rather than the four-line struct a program would write for itself. Both
lines are ordinary operands whose obvious formula answers nonsense.

**`abs` goes through [`hypot`](/library/math/), never `norm_sqr().sqrt()`.** The magnitude of
`1e200 + 1e200i` is about `1.4e200` and is perfectly representable; its *squared* magnitude is not,
and squaring first would have thrown the answer away before taking the root. `norm_sqr` overflowing is
not a defect — that number really is out of range, and it is printed above so the contrast is on the
page.

**Division uses Smith's algorithm**, not `(ac + bd) / (c² + d²)`. The textbook quotient forms the
divisor's squared magnitude, so both divisions above would be a NaN: an infinity over an infinity at
the top of the range and a zero over a zero at the bottom. Dividing the smaller part of the divisor by
the larger first keeps every intermediate within a factor of two of the answer's own scale, which is
the whole of the trick and costs one comparison.

**A zero divisor is a NaN and not an infinity.** It is the one place the complex quotient does not
follow the real one: among the reals `1/0` runs off in the only direction there is, while a complex
quotient has an argument as well as a magnitude and a zero divisor fixes neither. `is_zero` is the
test a caller with a direction in mind writes.

**`norm_sqr` is never spelled `norm`.** C++'s `std::norm(z)` is the squared magnitude and Rust's
`num-complex` `.norm()` is the magnitude — the two precedents disagree, so the bare word would read as
the wrong one to half of its readers and neither meaning is given it.

## The branch cuts

```sysl
import sysl.math.complex.Complex
import sysl.math.Float

var above = Complex(-1.0, 0.0)
var below = Complex(-1.0, -0.0)

print(above.ln(), below.ln())
print(Complex(-4.0, 0.0).sqrt(), Complex(-4.0, -0.0).sqrt())
print(Complex(2.0, 0.0).asin(), Complex(0.5, 0.0).asin())
print(Complex(1.0, 0.0).atanh().re, Complex(-1.0, 0.0).atanh().re)
```

```output
0+3.14159i 0-3.14159i
0+2i 0-2i
1.5708-1.31696i 0.523599+1.11022e-16i
inf -inf
```

A function that is many-valued over the complex numbers is made single-valued by choosing where to
cut, and **which cut a library chose is the one thing a caller cannot work out from a signature**. So
they are written down here, and each is pinned by a test at the boundary itself rather than near it.

| function | cut | principal value |
|---|---|---|
| `ln`, `log`, `log2`, `log10` | the negative real axis | `arg` in `(-pi, pi]` |
| `sqrt` | the negative real axis | non-negative real part |
| `powc` | inherits `ln`'s | — |
| `asin`, `acos` | the real axis outside `[-1, 1]` | — |
| `atan` | the imaginary axis outside `[-i, i]` | — |
| `asinh` | the imaginary axis outside `[-i, i]` | — |
| `acosh` | the real axis to the left of `1` | non-negative real part |
| `atanh` | the real axis outside `[-1, 1]` | — |

**A negative zero says which side of a cut a value is on.** `arg` puts `pi` itself on the cut, so
`-1 + 0i` reads `pi` and `-1 - 0i` reads `-pi` — which is how a value that arrived at the axis from
below keeps its side of it. The first two lines above are that, twice.

`atanh` has a pole at each end of its cut and runs off to an infinity of the right sign at both. It is
written as a difference of two logarithms rather than one logarithm of a quotient for exactly that
reason: the quotient form divides by zero at one pole and reaches `ln(0)` at the other, and would
report the same singularity two different ways.
