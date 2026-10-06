---
title: Fixed-point numbers
summary: "`q31`, `i32q16`, `u32q32` — an integer counted in units of 2^-F, with the arithmetic a DSP wants: saturating, rounding to nearest, mixed formats read from where a result goes, and literals that are constant data on a board with no FPU."
weight: 76
---

`iWqF` is a **W-bit two's-complement integer counting units of 2^-F**: an `i32q31` is an `i32` read in
2^-31ths, so it holds -1 up to just under 1. `uWqF` is the unsigned form. Three formats have the short
names CMSIS gives them — `q31` is `i32q31`, `q15` is `i16q15`, `q7` is `i8q7` — and every other format
is written as its width and its fraction.

```sysl
val gain: q31 = 0.6
val ratio: i32q16 = 3.25
val phase: u32q32 = 0.5

print(gain, ratio, phase)
```

```output
0.6 3.25 0.5
```

The storage width is in the name because on a microcontroller it **is** the cost: an `i32q31` add is
one `QADD` on a Cortex-M4 and a 64-bit multiply is a library call on a Cortex-M0+. A fixed-point value
is laid out, passed and stored exactly as the `iW`/`uW` of its width, and a C header spells it as
that integer (`int32_t` for a `q31`, which is CMSIS's `q31_t`).

The fraction is 1 to the width. No fraction is the integer itself, and more fraction than storage has
no bits to put it in:

```sysl
var x: i32q0 = 0
```

```error
'i32q0' has no fractional bits — that is 'i32'
```

```sysl
var x: i16q17 = 0
```

```error
'i16q17' has more fractional bits than its 16 bits of storage — a fixed-point type's fraction is 1 to its width, so the finest is 'i16q16'
```

## A literal is converted exactly, while compiling

A decimal literal — with or without a `.` — written where a fixed-point type is expected becomes the
representable value **nearest the exact decimal**, a value exactly between two of them going to the
even one. It is read from the digits, never through a `double`: an `i64q62` holds more precision than
a double has, and `0.1` read through one would already be the wrong number.

```sysl
val a: q31 = 0.6
val b: i64q62 = 0.1
val c: i32q16 = 3

print(a.bits, b.bits, c.bits)
```

```output
1288490189 461168601842738790 196608
```

A literal outside the range is a compile error, exactly as `var x: u8 = 300` is — and the message
names the range and the attribute for the end, since the commonest way to write one out of range is
to reach for the end itself:

```sysl
val x: q31 = 1.0
```

```error
the literal 1.0 does not fit q31, whose range is -1 to 0.9999999995; the largest value is 'q31::Max'
```

A suffix names the format where nothing else does: `0.6i32q31`, `1i32q16`.

**The converted value is constant data.** A module `val`, an array or a struct built from fixed-point
literals is laid into the object file with no initializer at all, which is what lets it live on a
board with no loader:

```sysl build=c target=thumbv6m-freestanding
module tone

val X: q31 = 0.6
val TABLE: [3]q31 = [0.5, -0.25, q31::Max]

@export("tone_at")
tone_at(i: i32) -> i32 = (TABLE[i] * X).bits
```

## The operators

`+`, `-`, `*`, `/` and unary `-` over two values of **one** format answer the representable value
nearest the true result:

- `+` and `-` **saturate** at both ends of the range rather than wrapping (`QADD` on a core with the
  DSP extension);
- `*` and `/` compute the exact result at twice the width, **round to nearest** with a half going up,
  and saturate once into the format;
- dividing by zero traps, as integer division does;
- negating the most negative value gives the largest one.

```sysl
var a: q31 = 0.75
var b: q31 = 0.5

print(a + b, -a - b, a * b, b / a)
```

```output
0.9999999995 -1 0.375 0.6666666665
```

Comparisons are on the value. `%` and the bit operators are not defined — the bits are `.bits`. A
fixed-point value and a float do not mix without a conversion:

```sysl
var a: q31 = 0.5
var f = 0.5
print(a + f)
```

```error
'+' needs matching types, got q31 and real
```

### Two formats: the result's format comes from where it goes

**A product or a quotient of two different formats takes the format of the place it is written in** —
a declared binding, a parameter, a returned value, a compound assignment's target, or the other operand
of a `+`, `-` or comparison beside it. There it is worked out exactly and rounded once, to nearest with
a half going toward +∞, saturating at both ends. Where the place is wide enough to hold the exact
result, nothing is rounded at all: `q31` times `q31` at an `i64q62` is the exact product, which is the
accumulator a filter wants.

```sysl
val b: i32q30 = 1.5
val x: q31 = -0.5
val acc: i64q61 = b * x
val p: i64q62 = q31::Min * q31::Min
val s: q31 = q31::Min * q31::Min

print(real(acc), p.bits, s.bits)
```

```output
-0.75 4611686018427387904 2147483647
```

`q31::Min` squared is 1, which `q31` cannot hold and `i64q62` can: the same product saturates in one
place and is exact in the other.

Where nothing names a format, the compiler will not invent one — it says what the exact one would be:

```sysl
val a: i32q30 = 0.5
val x: q31 = 0.25
val y = a * x
```

```error
the product of i32q30 and q31 has no format of its own — it is exactly an i64q61; give it a type
```

A [`new` type](/reference/types/) over a format joins a product only at that same type — a place's
format is not a way around the rule that a derived type does not mix with its base.

### An integer is a scale

An integer on either side of `*` scales the value, and under `/` divides it, rounding to nearest with
a half going up and saturating; a literal there is an integer, not a fixed-point value. An integer
divisor of zero traps.

```sysl
val x: q15 = 0.25
val n: int = -4
val a: q15 = x * 3
val b: q15 = x * 5
val c: q15 = x * n
val d: q15 = x / 3

print(a, b, c, real(d))
```

```output
0.75 0.99997 -1 0.0833435
```

The other way round has no meaning a scale could give it, so an integer divided by a fixed-point value
is two types:

```sysl
val x: q15 = 0.5
val n: int = 2
val y = n / x
```

```error
'/' needs matching types, got int and q15
```

### Shifts

`x << k` scales by 2^k and saturates; `x >> k` floors. A shift past the width saturates or empties
rather than being undefined:

```sysl
val x: q15 = 0.25

print((x << 1).bits, (x << 3).bits, (x >> 1).bits, (-q15::Small >> 1).bits, (x << 40).bits)
```

```output
16384 32767 4096 -1 32767
```

### An accumulator that wraps

**`wrapping` makes a format whose `+`, `-`, unary `-`, `<<` and rounded products wrap instead of
saturating** — what an accumulator wants, because an overflow partway through a sum cancels when the
sum comes back into range, where a saturating sum clips at the first step and never recovers. It
changes what the operators do, so it makes a type of its own and is written with `new`:

```sysl
type Acc = new i8q4 wrapping

var w: Acc = Acc(7.0)
w += Acc(2.0)
w -= Acc(3.0)

var s: i8q4 = 7.0
s += 2.0
s -= 3.0

print(real(w), real(s))
```

```output
6 4.9375
```

```sysl
type Acc = i64q61 wrapping
```

```error
'wrapping' changes what the operators do, so it makes a type of its own — write 'type Acc = new i64q61 wrapping'
```

It is for a fixed-point base only — an integer already wraps. Converting **out** of a wrapping type
still rounds and saturates, so the step from accumulator to sample is the safe one. A direct-form-I
biquad in this shape, with `q31` samples, `i32q30` coefficients and the accumulator above, is five
exact products and one rounding per sample:

```sysl
type Acc = new i64q61 wrapping

struct Biquad
    b0: i32q30
    b1: i32q30
    b2: i32q30
    a1: i32q30
    a2: i32q30
    x1: q31
    x2: q31
    y1: q31
    y2: q31

    process(*self, x: q31) -> q31
        var acc: Acc = self.b0 * x
        acc += self.b1 * self.x1
        acc += self.b2 * self.x2
        acc -= self.a1 * self.y1
        acc -= self.a2 * self.y2

        val y = q31(acc)

        self.x2 = self.x1
        self.x1 = x
        self.y2 = self.y1
        self.y1 = y
        y
    end process
end Biquad
```

On a Cortex-M4 each of those products added into the accumulator is one `SMLAL`, and on a Cortex-M0+
a `q15` product is a 32-bit multiply rather than a 64-bit library call.

## Conversions

Every conversion is a call, as for every other number:

| from → to | written | what it does |
|---|---|---|
| fixed → fixed | `q15(x)`, `q31(y)` | exact when finer; otherwise round to nearest (a half up), saturate |
| float → fixed | `q31(f)` | round to nearest, saturate, NaN → 0 |
| fixed → float | `real(x)`, `f32(x)` | round to nearest (exact into `real` up to 53 bits) |
| integer → fixed | `i32q16(n)` | the value `n`, saturating |
| fixed → integer | `int(x)` | toward zero, saturating — as a float does |

Over a literal a conversion is worked out while compiling with the same rounding, so `q31(0.6)` is
constant data too — and it saturates where the literal `1.0` at `q31` is refused: `q31(1.0)` is
`q31::Max`.

## Attributes, and the bits

`T::Min`, `T::Max` and `T::Small` (one unit, 2^-F) are values of `T`; `T::Bits(n)` makes a value from
the integer it is stored as, and `x.bits` reads that integer back. Neither converts anything.

```sysl
print(q31::Min, q31::Max, q31::Small)
print(q15::Bits(16384), (0.5i32q31).bits)
```

```output
-1 0.9999999995 0.0000000005
0.5 1073741824
```

`print` and `str` write the **shortest decimal that reads back as the same value**, never in
exponent form: `0.0000000005` says which place the one bit is in.

## A range

A fixed-point type is a scalar, so `within` narrows it like any other — the ends are decimals, held
exactly, and a value is checked against them at the raw value:

```sysl
type Gain = i32q27 within 0.0..8.0

val g: Gain = 4.0
print(g)
```

```output
4
```

---

Next: [strings](/reference/strings/).
