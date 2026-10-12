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

**So is a table a `const` function computes.** A conversion of any constant expression is folded, and
a call to a `const` function over constants is run while compiling wherever it sits — the whole
initializer, one element, one field — so a table generated rather than written out is laid down the
same way, rounded and saturated exactly as the run-time conversion would:

```sysl build=c target=thumbv6m-freestanding
module ramp

const step(i: int) -> real = real(i) / 4.0 - 0.5

val RAMP: [4]q15 = [q15(step(0)), q15(step(1)), q15(step(2)), q15(step(3))]

@export("ramp_at")
ramp_at(i: i32) -> i32 = i32(RAMP[i].bits)
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
0.75 0.99997 -1 0.083343505859375
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
| fixed → fixed | `q15(x)`, `q15(x, Floor)` | exact when finer; otherwise round to nearest (a half up), or with `Floor` toward −∞; saturate |
| float → fixed | `q31(f)`, `q31(f, Floor)` | round to nearest, or with `Floor` toward −∞; saturate, NaN → 0 |
| fixed → float | `real(x)`, `f32(x)` | round to nearest (exact into `real` up to 53 bits) |
| integer → fixed | `i32q16(n)` | the value `n`, saturating |
| fixed → integer | `int(x)`, `int(x, Floor)` | toward zero, or with `Floor` toward −∞; saturating — as a float does |

Over a literal a conversion is worked out while compiling with the same rounding, so `q31(0.6)` is
constant data too — and it saturates where the literal `1.0` at `q31` is refused: `q31(1.0)` is
`q31::Max`.

### Choosing the rounding

The second argument is a [`Rounding`](/api/sysl/#rounding), written bare. **`Floor` is the bit-exact
narrowing C and CMSIS code does** — an arithmetic shift right, dropping the low bits toward −∞ —
where the default adds a half first. A port that has to match its reference sample for sample wants
`Floor`; it still saturates:

```sysl
val x = q31::Bits(-98304)          // -1.5 steps of a q15, held finer
print(q15(x).bits, q15(x, Floor).bits)
print(q15(q31::Bits(98303)).bits, q15(q31::Bits(98303), Floor).bits)
print(int(-1.5i32q16), int(-1.5i32q16, Floor))
print(q15(-0.00001).bits, q15(-0.00001, Floor).bits, q15(i32q20::Max, Floor))
```

```output
-1 -2
1 1
-1 -2
0 -1 0.99997
```

Into a fixed-point type a conversion takes `HalfUp` (its default, the same as writing nothing) or
`Floor`; out of one into an integer, `Down` (its default) or `Floor`. Where nothing is dropped — a
fraction that grows, an integer into a fixed format — the mode changes nothing, so a generic body
may write it for every width it is handed. Into a float there is nothing to choose, and the mode is
written at the call rather than carried in a variable, since it is laid into the instruction:

```sysl
print(real(0.5i16q15, Floor))
```

```error
a conversion from q15 into a float rounds to nearest, so it takes no rounding mode
```

On a Cortex-M4, `q15(x, Floor)` from a `q31` is one `ASR` — sixteen bits shifted out of a 32-bit
value always fit, so the saturation folds away — and from an `i32q20`, whose integer bits a `q15`
cannot hold, the shift and an `SSAT`.

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

**Printing one allocates nothing**: a fixed value is a scalar, and `print` writes its digits from a
buffer in the caller's own frame, as it does an integer's — so it prints under `@no_alloc`, and on a
board with no heap. `str(x)` and `s"$x"` make a `string` of the same characters, and so allocate.

```sysl
@no_alloc

val levels: [2]q15 = [q15::Min, 0.75]

for x in levels
    print("level", x)
```

```output
level -1
level 0.75
```

### Under a format specifier

An f-string hole takes a float conversion — `%f`, `%e`, `%g` and their upper-case forms, with every
flag, width and precision ([Format specifiers](/reference/strings/#format-specifiers)) — over a
fixed-point value exactly as over a float. **The digits are the exact value's**: a fixed-point value
is its raw integer times 2^-F, whose decimal expansion is finite, so it is rounded half to even at the
position the conversion names and never passes through a `double`. A format wider than a `double`'s 53
bits keeps every one of them:

```sysl
val gain: q31 = 0.6
val t: i64q60 = 0.1

print(f"[${gain}%.4f] [${gain}%+10.3e] [${q15::Max}%g] [${i32q16::Min}%08.1f]")
print(f"${t}%.25f")
print(f"${real(t)}%.25f")
```

```output
[0.6000] [+6.000e-01] [0.999969] [-32768.0]
0.1000000000000000003469447
0.1000000000000000055511151
```

The second line is `t`'s own value; the third is the nearest `double` to it. An integer or a string
conversion is still refused — the bits are `.bits`, under `%d` or `%x`:

```sysl
val x: q15 = 0.5

print(f"${x}%d")
```

```error
format '%d' expects an integer, but the value has type q15
```

A format up to 128 bits wide renders this way; a wider one is refused under a float conversion. The
hole lowers to `sysl.fmt.format_fixed`, and a program that may not allocate writes the same text with
[`write_fixed`](/library/fmt/#writing-one-without-allocating).

### A view of the bits

A codec or a DMA engine hands samples over as integers. The same two words read a whole view:
`T::Bits(v)` over a view of the integer `T` is stored as — a `[]i16` for `q15` — is a `[]q15` over
**the same storage**, and `.bits` over a `[]q15` is the way back. Nothing is copied: a write through
either view is seen through the other, and the view keeps its owner alive as any view does.

```sysl
var pcm: [4]i16 = [0, 16384, -32768, 32767]
val samples = q15::Bits(pcm)

print(samples[1], samples[2], samples[3])

samples[0] = 0.25
print(pcm[0], samples.bits[0])
```

```output
0.5 -1 0.99997
8192 8192
```

Whether the view may be written is the argument's: a `[]const i16` gives a `[]const q15`, and an
array is read through the view `a[..]` would take of it — read-only where a `val` holds it. The
elements have to be exactly the integer the format is stored as, width and signedness both:

```sysl
var frame: [64]i32 = [0; 64]
val samples = q15::Bits(frame)
```

```error
'q15::Bits' over a view takes a '[]short' — the short a q15 is stored as — not [64]int
```

A view read this way is a view like any other, so it allocates nothing — it is legal under
`@no_alloc` and on a board — and it no more crosses into a `&sync` than the view it was made from.
The bits of a writable view of a checking subtype (`[]Gain` below) are refused, since a write
through them would skip the check; a `[]const Gain` reads them.

## Generic code: `Zero` and `Signed`

**Every fixed-point type is a `Zero`**, its zero being the raw bits `0`, so a body written once over
`[T: Zero]` — a mixer's accumulator, a filter's reset, a buffer of silence — takes a `q15` beside an
`int` and an `f32`. It is not a `One`: a `q15` stops one unit short of `1.0`.

```sysl
reset[T: Zero](state: []T)
    for i in 0..<state.len
        state[i] = T.zero()

sum[T: Add + Zero](xs: []const T) -> T
    var acc = T.zero()
    for x in xs
        acc = acc + x
    acc

var history: [3]q15 = [0.25, 0.5, 0.125]
print(sum(history[..]))
reset(history[..])
print(history[0], history[1], history[2], sum(history[..]))
```

```output
0.875
0 0 0 0
```

**Every signed fixed-point type is a `sysl.math.Signed`**, on the integers' terms and with its own
arithmetic: `abs` is `x < 0 ? -x : x` with the type's own saturating `-`, so the magnitude of the most
negative value is the largest one, as `-q15::Min` is. `signum` answers `-1`, `0` or `1` in the format,
each the nearest value it holds — a `q15` holds `-1.0` exactly and its `1` is `q15::Max`:

```sysl
import sysl.math.Signed

magnitude[T: Signed](x: T) -> T = x.abs()

val a: q15 = -0.25
val z: q15 = 0.0
print(magnitude(a), q15::Min.abs(), z.abs())
print(a.signum(), (-a).signum(), z.signum())
val b: i32q16 = -3.5
print(magnitude(b), b.signum(), i32q16::Min.abs() == i32q16::Max)
```

```output
0.25 0.99997 0
-1 0.99997 0
3.5 -1 true
```

A `wrapping` type's `abs` wraps instead, as its `-` does: the most negative value answers itself. An
unsigned format has no sign to take off, so it is not a member:

```sysl
import sysl.math.Signed

val g: u16q16 = 0.5
print(g.abs())
```

```error
type 'u16q16' has no method 'abs' — 'abs' is 'sysl.math.Signed''s, and an unsigned value has no sign to take off
```

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

## Lanes

A fixed-point type is a vector lane like any other scalar (see [Vectors](/reference/vectors/)):
`<2>q15` is two `q15`s in one register, and `+`, `-`, `*` and unary `-` compute every lane exactly as
the scalar operator does — saturating, and rounding a product to nearest with a half going up. A
scalar beside a register fills every lane, a comparison yields a mask, and `min()` and `max()` reduce:

```sysl
val a: <2>q15 = [0.75, -0.25]
val b: <2>q15 = 0.5

val s = a + b
val p = a * b

print(s[0], s[1], p[0], p[1], (a < b)[1], a.max())
```

```output
0.99997 0.25 0.375 -0.125 true 0.75
```

**A product takes its lanes' format from its position**, as a scalar product does — so two `<2>q15`
registers multiplied where a `<2>i32q30` is wanted are the two exact products, nothing rounded.

**A `wrapping` lane has `sum()`**, because wrapping addition is associative: the lanes' sum has one
answer whatever order it is added in. And `sum()` hands its position's type to the product it is read
off, so `(x * y).sum()` written where an `Acc` is wanted multiplies at `<2>Acc` — the two exact
products, added. With a `wrapping` accumulator that is a dot product of pairs:

```sysl
type Acc = new i32q30 wrapping

dot(xs: [4]q15, ys: [4]q15) -> Acc
    var acc = Acc(0.0)

    for i in 0..<2
        val x: <2>q15 = [xs[2 * i], xs[2 * i + 1]]
        val y: <2>q15 = [ys[2 * i], ys[2 * i + 1]]

        acc += (x * y).sum()

    acc
end dot

val xs: [4]q15 = [0.5, 0.25, -1.0, 0.75]
val ys: [4]q15 = [0.5, -0.5, -1.0, 0.5]

print(dot(xs, ys), real(Acc(1.5) + Acc(1.0)))
```

```output
1.5 -1.5
```

The accumulator wraps rather than saturating, as `wrapping` says: `Acc(1.5) + Acc(1.0)` is 2.5, past
an `i32q30`'s largest value, and comes back as -1.5. So does a single pair — `-1 × -1` twice is 2,
which an `i32q30` holds as -2:

```sysl
type Acc = new i32q30 wrapping

val m: <2>q15 = [-1.0, -1.0]
val s: Acc = (m * m).sum()

print(s)
```

```output
-2
```

**On a core with Arm's DSP extension a register that fills one 32-bit word is one packed
instruction.** Every v7E-M core has the extension (Cortex-M4, M7, the `thumbv7em-freestanding`
target); on v8-M Mainline (Cortex-M33, the `thumb-freestanding` targets) it is optional, and a
manifest's `codegen { extensions = ["dsp"] }` says the core has it. There:

| written | on a Cortex-M4 |
|---|---|
| `<2>q15`, `<2>i16q8` `+` / `-` / unary `-` | `QADD16` / `QSUB16` |
| `<4>q7` `+` / `-` / unary `-` | `QADD8` / `QSUB8` |
| `<2>u16q16`, `<4>u8q8` `+` / `-` | `UQADD16` / `UQSUB16`, `UQADD8` / `UQSUB8` |
| a `wrapping` lane's `+` / `-` | `SADD16` / `SSUB16`, `SADD8` / `SSUB8` |
| `(x * y).sum()` of two `<2>q15` at a `wrapping` `i32q30` | `SMUAD` |
| `acc + (x * y).sum()`, `acc += (x * y).sum()` | `SMLAD` |
| `acc + p[0] + p[1]` over `val p: <2>Acc = x * y` | one `SMLABB` per lane |
| a register of more than one word (`<4>q15`) | the generic form, a halfword at a time |

and the operands are loaded and stored a word at a time — `LDR`, `LDR`, `QADD16`, `STR` for a sum
through three pointers, and `LDR`, `LDR`, `SMLAD` for a step of a dot product:

```sysl build=c target=thumbv7em-freestanding
type Acc = new i32q30 wrapping

@export("mix")
mix(a: *<2>q15, b: *<2>q15, out: *<2>q15)
    *out = *a + *b

@export("mac")
mac(x: *<2>q15, y: *<2>q15, acc: Acc) -> Acc = acc + (*x * *y).sum()
```

The dual multiply-add is chosen where it is the same arithmetic: two signed 16-bit lanes whose product
is exact at a `wrapping` 32-bit signed format — the product's fraction being the two lanes' fractions
added, as `q15 × q15` at `i32q30` is. A product into any other format rescales or rounds each lane
first, and keeps the generic form.

Each instruction does to each lane exactly what the generic form does, so a program's answers are the
same bits on every machine; only the instructions differ.

Three things a scalar has and a register of lanes does not. **`/`** traps on a zero divisor, and a
register traps as a whole rather than per lane, so it is refused as integer division is. **A shift**
of a register is not defined. **`sum()` of saturating lanes** is refused because a fixed-point `+`
saturates, and saturation is not associative — `(max + max) + min` is not `max + (max + min)` — so a
sum of lanes has no answer that does not depend on the order it is added in:

```sysl
f(v: <2>q15) -> q15 = v.sum()
```

```error
'sum' is not defined for <2>q15 — a fixed-point '+' saturates, so the lanes' sum depends on the order they are added in; add 'v[0] + v[1]' in the order you mean, or hold the lanes in a 'wrapping' type, whose sum has one
```

---

Next: [strings](/reference/strings/).
