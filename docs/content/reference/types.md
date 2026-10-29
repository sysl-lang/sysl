---
title: Types
summary: Every type the language has — the open integer families, the closed float set, and the aggregates built on them.
weight: 20
---

sysl's types fall into four groups: **scalars** (numbers, `bool`, `char`), the built-in **`string`**,
the **aggregates** you build (arrays, slices, structs, enums, tuples), and the **modes** that decide
where a value lives (`T`, `&T`, `*T`). This page covers the first three. The modes have a
[page of their own](/reference/memory/), because they are about storage rather than shape.

**A conversion is implicit exactly when it cannot lose information**: where every value of one
numeric type is exactly a value of another — a `byte` into an `int`, an `int` into a `long` or a
`real`, an `f32` into an `f64` — a position that expects the wider type accepts the narrower one
directly. Every other width change, signedness change, and float/integer conversion is written as a
conversion call, so a conversion that could lose information is always visible. The table is in
[Which conversions are implicit](/reference/expressions/#which-conversions-are-implicit).

## Integers are an open family

`iN` and `uN` are not a fixed set of four sizes. They are an **open family parameterized by an
arbitrary bit width**: `i5`, `u3`, `u12` and `i128` are all valid types, with no list of widths
built into the compiler. LLVM supports integers of any width natively, so this is a capability of
the target rather than something sysl emulates.

```sysl
var small: u3 = 5
var odd: i5 = -7
var wide: u12 = 4000

print(small, odd, wide)
```

```output
5 -7 4000
```

`iN`/`uN` is the general mechanism, and a short list of **aliases** names the common widths.

| alias | is | | alias | is |
|---|---|---|---|---|
| `byte` | `u8` | | `short` | `i16` |
| `ushort` | `u16` | | `int` | `i32` |
| `uint` | `u32` | | `long` | `i64` |
| `ulong` | `u64` | | `real` | `f64` |

Unlike C's, **each alias has a fixed width**: `long` is exactly `i64` on every target.

**This fixes the width, not the C ABI match.** On a 64-bit host every alias matches its C namesake,
so they are safe in `extern` declarations there. On a 32-bit target C's `long` is 32 bits while
sysl's is still 64, so **precise FFI should use the explicit-width names**, which match C's
`int32_t` and `int64_t` on every target. `i8` has no alias, since C has no established name for a
signed byte.

**An integer literal has no maximum of its own.** Its value is exact at any size and must fit the
type it takes, so a literal can be as wide as the widest type:

```sysl
val c: u256 = 115792089237316195423570985008687907853269984665640564039457584007913129639935

print(c)
```

```output
115792089237316195423570985008687907853269984665640564039457584007913129639935
```

A value that does not fit the type is refused rather than wrapped:

```sysl
val z: u8 = 300
```

```error
the literal 300 does not fit byte
```

A literal with no type from its context is an `int`, and one too large for `int` is refused rather
than widened — give it a type, with a suffix, on the declaration, or with a
[conversion](/reference/expressions/#conversions-are-calls), whose argument is read at its target:

```sysl
val big = 4294967295
```

```error
the literal 4294967295 does not fit int
```

### Arithmetic wraps

Integer arithmetic wraps at the **declared** width, and this is defined behaviour rather than a
checked error. `i5` wraps mod 2⁵ exactly as `i32` wraps mod 2³².

```sysl
var small: byte = 250

print(small + 10)
```

```output
4
```

Overflow is therefore **not** a trap source. See [errors and traps](/reference/errors/) for what is.

Where the wrap is the point — a hash, a checksum, a counter that rolls over — `+%`, `-%` and `*%` say
so in the spelling, and where the edge of the range is the right answer instead, `+|`, `-|` and `*|`
stop there: `small +| 10` is 255. Both families are defined in every build, at every width
([wrapping and saturating operators](/reference/expressions/#wrapping-and-saturating-operators)).

### Arithmetic over literals is exact

An expression written out of integer literals alone — `65536 * 65536`, `(1 << 16) - 1`,
`200 + 100` — is a constant the compiler computes with no width at all, and **the value it comes to
must fit the type it is read at**, exactly as a single literal's must. Wrapping is what happens to a
value held in a variable; a value nobody has stored yet has nothing to wrap in, so a constant that
does not fit is a mistake in what was written.

```sysl
val b: u32 = 65536 * 65536
```

```error
the value 4294967296 does not fit uint
```

Only the **whole** expression is held to the type. The steps on the way are plain numbers, so a step
may pass the type's range provided the result comes back inside it, and a division or a right shift
works on the exact value rather than a wrapped one:

```sysl
val mask: u32 = 65536 * 65536 - 1
val top: u32 = (65536 * 65536 * 256 - 1) >> 20
val low: u8 = ~0xF0

print(mask, top, low)
```

```output
4294967295 1048575 15
```

`~` is the complement at the type's own width, so `~0` is a `u32`'s largest value rather than the
`-1` that has no place in an unsigned type. Suffixed literals are literals too: `255u8 + 1u8` is
refused as the value 256. A literal with no type from its context is an `int` here as well, so
`val big = 65536 * 65536` is refused as `int`, and is written `val big: long = 65536 * 65536`.

**A left shift is a bit operation**, as in C and Rust: the bits shifted past the top of its type are
dropped. The type is the one the shift is read at, which for an unsuffixed literal is the type of the
whole expression, so a mask shifted left stays a mask and a one shifted into the sign bit is the
signed type's minimum. What the shift comes to is then an ordinary step, and the operator around it
is held to the type as any other:

```sysl
val hi: u64 = 0xffffffffffffffff << 27
val min = 1 << 31
val max: u32 = (1 << 31) + ((1 << 31) - 1)

print(hi, min, max)
```

```output
18446744073575333888 -2147483648 4294967295
```

A shift has no value by a count below zero or at or past the width of its type, and one of a value
the type does not hold would hide that value's mistake by dropping it; all three are refused:

```sysl
val big = 1 << 40
```

```error
a constant shifted by 40 places, at or past the 32 bits of int
```

A division or a remainder by a literal zero has no value at all, and is refused where it is written:

```sysl
val z = 1 + 10 / (3 - 3)
```

```error
a constant divided by zero
```

**A name anywhere in the expression makes it ordinary arithmetic**, which wraps. A `const` is a
name, so the arithmetic built on it is the arithmetic of its type:

```sysl
const Base: u8 = 200

print(Base + 100)
```

```output
44
```

A literal read as a float is not an integer constant at all: `val r: f64 = 1 / 0` is IEEE 754's
division, and is infinity.

### Storage is not `N / 8`

An integer's alignment rounds up to the smallest width the target names, and its stride rounds up to
that alignment. A `u12` occupies **two** bytes aligned to two; a `u96` occupies sixteen aligned to
sixteen. This is LLVM's rule.

### Where the width stops

Up to 64 bits every operation is a machine instruction. Beyond that the arithmetic is still native —
the back end expands a wide multiply or divide inline, with no runtime routine — but decimal
rendering is done by sysl itself, since C's `printf` has no length modifier that wide. A value wider
than 64 bits is printed through a digit loop, and `%d` is refused for it.

The maximum width is LLVM's limit of **2²³ − 1** bits, and a wider type is a diagnostic naming it.

```sysl
var wide: u256 = 1
var i = 0

while i < 200 do
    wide = wide * 2
    i = i + 1

print(wide)
```

```output
1606938044258990275541962092341162602522202993782792835301376
```

Extreme widths have two costs, neither of which is diagnosed: the digit buffer is stack space
proportional to the width, so printing a value near the maximum width overflows the stack frame, and
sizing the buffer evaluates 2^N at compile time. Only a program that names such a width is
affected.

### The narrowest widths

Any `N ≥ 1` is valid. `u1` is a single binary digit. `i1` holds `{-1, 0}` — one bit of two's
complement, where the only bit *is* the sign bit. It is degenerate but consistent, and nothing
special-cases it: `abs` at `i1` answers `-1` because `-1` is that width's most negative value, as
`abs` at any width answers its own minimum, and `signum` never returns `+1` because no value of the
type is positive. For a single bit, write `u1` or `bool`.

### Fixed point is the integers read in units

`iWqF` and `uWqF` (`q7`, `q15`, `q31`) are a W-bit integer counting units of 2^-F — see
[fixed-point numbers](/reference/fixed-point/). They are the one scalar family whose arithmetic
saturates rather than wraps, unless a type is declared `wrapping`, because a fixed-point value models
a real number in a range, as a float does.

## Floating point is a closed set

`fN` is **not** open: only real formats exist, since there is no meaningful `f37`. The set is `f16`,
`bf16`, `f32`, `f64` and `f128`.

Only the default width has an alias, named `real` rather than `float`, because `float` means 32-bit
to C, C++, Rust and Java programmers.

```sysl
var a: f32 = 1.5
var b: real = 2.25

print(a + 1, b / 2)
```

```output
2.5 1.125
```

A float renders in the shortest form that round-trips, so a value with no fractional part prints
without one.

### Infinity and NaN

An infinity and a NaN have no decimal literal, so each float type names them: **`real.infinity` and
`real.nan`**, and the same on every width — `f32.infinity`, `f16.nan`, `bf16.infinity`. They are
written **without parentheses**, because they are constants rather than functions, so a `const`
initializer or a `within` bound may use one.

`infinity` and `nan` written on their own are the `real` ones. Negative infinity is a minus in front,
as for any other value:

```sysl
const top = infinity
val low = -f32.infinity

print(top > 1.0e308, low < -3.0e38, nan != nan, -infinity < 0)
```

```output
true true true true
```

A NaN compares unequal to everything, itself included, as the third value shows; test for one with
`is_nan()`.

**The bare names have the outermost scope.** Anything the program declares or imports under the same
name takes precedence — a local, a parameter, a `const`, a function — so a program that declares its
own `infinity` keeps it:

```sysl
f(nan: int) -> int = nan + 1

print(f(2))
```

```output
3
```

Where a declared **function** has the name, reading it bare as a value is refused as usual, and the
message gives the spelling no declaration can hide:

```sysl
infinity() -> real = 2

val x = infinity

print(x)
```

```error
The built-in constant this declaration hides is spelled 'real.infinity'
```

The `Float` trait's associated functions `real.infinity()` and `real.nan()`, which a routine generic
over the width calls as `T.infinity()`, are also available, as are `sysl.math`'s `infinity()` and
`nan()`.

### The two sixteen-bit formats

`bf16` is the one name in the set that is not a width, because **width alone does not identify a
format at sixteen bits**. Both formats use one bit for the sign and divide the other fifteen
differently:

| | exponent | significand | largest finite | decimal digits |
|---|---|---|---|---|
| `f16` | 5 bits | 10 bits | 65504 | ~3 |
| `bf16` | 8 bits | 7 bits | 3.4 × 10³⁸ | ~2 |

So `bf16` is not a smaller `f16` but a **shorter `f32`**: its bits are the top sixteen of a binary32,
so it has exactly binary32's range. `f16` is the more precise of the two but has a small range, while
`bf16` covers every exponent an `f32` does at the cost of three bits of significand.

Both are ordinary arithmetic types. A tenth shows the difference in precision, and doubling the
largest `f16` shows the difference in range:

```sysl
var h: f16 = 0.1
var b: bf16 = 0.1

print(h, b)
print(real(h), real(b))

var big: f16 = 65504
var wide: bf16 = 65504.0

print(big * 2, real(wide * 2))
```

```output
0.1 0.1
0.0999755859375 0.10009765625
inf 131072
```

Both print `0.1`, because `print` writes the fewest digits that read back as the value **at its own
width**, and `0.1` reads back as each format's nearest value to a tenth. Neither holds a tenth
exactly, and the two nearest values differ: widened to a `real`, which represents every value of
both formats exactly, each shows all of its digits. (`wide * 2` is widened for the same reason: at
`bf16`'s width the shortest reading of 131072 is `131000`.) The `bf16` initializer is written
`65504.0` because the value rounds there: an integer literal is accepted at a float type only where
the format holds it exactly, and `bf16`'s nearest value is 65536.

`f16` and `bf16` are also the one pair where **neither conversion is a widening or a narrowing**. It
is still written as an ordinary conversion:

```sysl
var b: bf16 = 0.1

print(f16(b))
```

```output
0.1001
```

The result is `bf16`'s tenth rather than `f16`'s: a conversion carries the value the source actually
holds. Here `f16` represents it exactly — its ten bits of significand hold `bf16`'s seven with nothing
to round — and distinguishes it from its own tenth, so it needs four digits to print. The other
direction rounds, since seven bits cannot hold ten: `bf16(h)`, with `h` the `f16` tenth from above,
is `bf16`'s own tenth (`0.10009765625` widened) rather than `h`'s `0.0999755859375`, because that
value has no exact seven-bit representation.

`sysl.math`'s [`Float`](/library/math/) trait covers both, so `sqrt`, `floor`, `pi` and the rest are
called the same way as for `real`. libm has no sixteen-bit entry points, so those functions widen to
`f32`, compute, and narrow back; the widening is exact, so the only rounding is to the destination.

Neither format has an alias. A literal takes one through a suffix or through the type it is written
into: `1.5f16`, `1.5bf16`, or `var x: bf16 = 1.5`.

### `f128`, binary128

`f128` is IEEE binary128: a 15-bit exponent, as wide as x87's, and 112 bits of fraction, about 34
decimal digits where `real` has 16. It is an ordinary arithmetic type — `+ - * /`, the comparisons,
unary minus, `f128.infinity` and `f128.nan`, and a conversion to and from every integer and float
width — and **its literals are read from their text**, so `0.1f128` is binary128's own tenth rather
than a double's tenth widened:

```sysl
val tenth: f128 = 0.1
val third: f128 = 1 / 3

print(third.bits() == 0x3FFD5555555555555555555555555555)
print(real(tenth) == 0.1, tenth + 0.2 > 0.3, f128(0.1) == tenth)
```

```output
true
true true false
```

The last value is `false` because `f128(0.1)` exactly widens the `double` nearest a tenth, which is
not the binary128 value nearest a tenth. `x.bits()` is the encoding as a `u128`, and
`f128.from_bits(b)` reads one back.

**No machine sysl targets has quad-precision hardware**, so every operation is a call into software,
at about a hundred times the cost of a `real` operation. The routines follow compiler-rt's names and
contracts (`__addtf3`, `__lttf2`, `__fixtfdi` and the rest), and the standard module provides its own
in `sysl.softfloat`, because a Mac's runtime has none (C's `long double` is a `double` there) and a
bare-metal image has no runtime library at all. A program, archive or kernel image that computes in
`f128` is linked with them, and one that does not contains none. They always round to nearest, ties
to even.

**A `real` on a 32-bit board works the same way one size down.** Every Cortex-M floating-point unit
sysl targets is single-precision or absent, and the 32-bit RISC-V targets have none, so an `f64`
operation there is also a call — `__aeabi_dmul` on Arm, `__muldf3` on RISC-V. The standard module
provides those in `sysl.softfloat.binary64`, with the same rounding and the same rule: an image that
computes with a `real` contains them, and one that does not contains none. Results are bit for bit
what a double-precision unit gives. The same holds for dividing a `long`, which no 32-bit core does
in one instruction (`sysl.softint`). This archive for a Cortex-M4F defines `__aeabi_ldivmod`,
`__aeabi_l2d` and `__aeabi_dmul` itself:

```sysl build=c target=thumbv7em-freestanding
@export("scale")
scale(x: real, n: long) -> real = x * real(n / 1000)
```

**An `f32` on a core with no floating-point unit at all** — a Cortex-M0+ or M3, RISC-V with no `F` —
is a call as well, `__aeabi_fmul` or `__mulsf3`, from the same module and under the same rule. Each
routine computes in binary64 between an exact widening and a narrowing. Because 53 bits is more than
twice 24 plus two, the double rounding is harmless, and the result is bit for bit what a
single-precision unit gives. This archive for a Cortex-M0+ computes with an `f32` and needs nothing
from its board but `memcpy` and `memset`:

```sysl build=c target=thumbv6m-freestanding
@export("mix")
mix(a: f32, b: f32, t: f32) -> f32 = a + (b - a) * t
```

**`print`, `str` and a plain interpolation hole write an `f128` in the fewest digits that read back as
it**, positionally, as every float prints. It is never narrowed to a `real`, which would print a
different number. A format specifier renders the exact value's digits, correctly rounded at the
requested precision, as it does for a `real`. All of this is the standard module's own arithmetic,
so it needs no C library and works on bare metal:

```sysl
val tenth: f128 = 0.1
val third: f128 = 1 / 3

print(tenth, third, -0.0f128, f128.infinity)
print(third.to_string().bytes.len, s"$tenth", f"${third}%.40f", f"${tenth}%.3e")
```

```output
0.1 0.3333333333333333333333333333333333 -0 inf
36 0.1 0.3333333333333333333333333333333333172839 1.000e-01
```

The text round-trips: `sysl.text.parse_f128` reads a decimal, rounded once to the nearest binary128
value, so every `to_string` of an `f128` parses to the same `f128`. `sysl.fmt.shortest` accepts an `f128`
as it accepts a `real`.

```sysl
import sysl.text.parse_f128

val x: f128 = 2 / 7

print(parse_f128(x.to_string()).unwrap().bits() == x.bits())
```

```output
true
```

**A `const` computes at `f128` too**, including in a `const` function: every step is the exact result
rounded once to binary128, as the run-time routines would round it, and the resulting encoding is
stored, so `third` below is binary128's third, not a widened `double`'s:

```sysl
const third(x: f128) -> f128 = x / 3

const harmonic(n: int) -> f128
    var sum: f128 = 0

    for k in 1..n
        sum = sum + 1 / f128(k)

    sum
end harmonic

const T = third(1)
const H = harmonic(20)

print(T.bits() == 0x3FFD5555555555555555555555555555, H)
```

```output
true 3.597739657143681911483769068908388
```

A `c const` may be declared `f128`; it is measured as a `long double`, with every bit kept
([ffi](/reference/ffi/#c-const-a-value-only-the-c-compiler-can-work-out)). An `f128` passes through a
variadic `...` and is read back with `va_arg`, by each machine's own convention. It cannot be a
[vector](/reference/vectors/) lane. **C's `long double` is not the same as `f128`**: it is binary128
on AArch64 Linux, RISC-V and WebAssembly, a `double` on Apple's arm64 and on Windows, and x87's
80-bit format on x86. A [`c type`](/reference/ffi/#c-type-a-width-only-the-c-compiler-can-work-out)
measured as `"long double"` is the one spelling that matches it on every machine.

## `usize` and `isize`

Pointer-width integers. They are **distinct types** from every `uN`, not aliases for `u64`, because
sysl targets 32-bit embedded machines as well as 64-bit hosts.

A length, an index, and `sizeof` are all `usize`.

```sysl
var xs = [10, 20, 30]
var n: usize = xs.len

print(n, xs[1])
```

```output
3 20
```

## `bool`

`true` and `false`. Nothing converts to `bool` implicitly, so `if x = 0` is a **type error**: `if`
requires a `bool`, and an assignment yields the assigned value. No integer is ever a condition.

## `char`

One **Unicode scalar value** — a code point up to `0x10FFFF`, excluding the surrogate range. It is its
own type, an alias of neither `u8` nor `u32`, so a character and a byte remain distinct, as they must
in a language with a UTF-8 `string`. Conversion to `u32` is total and written `u32(c)`; conversion
*from* `u32` is partial, with a checked form that traps and a fallible form that returns an
`Option`.

## `unit` and `never`

`unit` is the type with exactly one value — what a function that returns nothing returns. It is an
ordinary type, so a `Fn() -> unit` needs no special case. Its value is written `()`, and so is its
[pattern](/reference/patterns/#the-pattern-forms).

**It implements the four traits a tuple does, each in the only way a single value allows**: `Eq`
(every `()` equals every other), `Ord` (none is less than another), `Hash` (all hash alike) and
`Display` (it prints as `()`). These let a `Result[unit, E]` or an `Option[unit]` be compared,
printed and passed to `assert_eq`. A `unit` occupies no storage, and a `unit` operand is passed as
nothing. A program may implement its own traits for `unit` too.

```sysl
enum Fault
    Low

impl Display for Fault
    display(self, out: *Writer, fmt: FormatSpec) = display_str("Low", out, fmt)

check(n: int) -> Result[unit, Fault] = if n > 0 then Ok(()) else Err(Low)

print(check(1) == Ok(()), check(0) == Err(Low), () < ())
print(check(1), check(0))
assert_eq(check(0), Err(Low))
```

```output
true true false
Ok(()) Err(Low)
```

**A `unit` still has an address.** `&` of a `unit` binding is a `*unit`, and a `&unit` or a `weak
unit` may hold one, as at every zero-sized type: the address is non-null and aligned, a load through
it is `()` and a store writes nothing, so neither touches memory. This is what lets a generic body
that takes the address of a `T` compile at `unit` like at any other type. C sees a `*unit` as a
`void *`. An array or a slice of `unit` is still refused: `unit` is the type the compiler drops, so
there would be nothing to index.

```sysl
through[T](x: T) -> T
    var v = x
    val p: *T = &v
    *p

var u = ()
val p = &u

*p = ()
print(through(()), through(3), *p, usize(p) != 0)
```

```output
() 3 () true
```

`never` is the type with **no** values: the type of an expression that does not finish. A call to
`exit` has type `never`, as does a `return` or a `break` used as an expression. Because it has no
values, `never` is a subtype of every type, so a `match` arm that aborts can sit beside arms that
produce an `int`.

**`never` is also an ordinary type argument.** `Result[T, never]` is a result that cannot fail,
`Option[never]` one that is always `None`, `Buf[never]` a buffer that stays empty, and a generic
function, struct or `impl` may be instantiated at it. A variant whose payload holds a `never` — by
itself, or through a struct, tuple or enum that must hold one — can never be built, so **a `match`
may leave it out**; writing the arm anyway stays legal. `never` implements `Eq`, `Ord`, `Hash`,
`Display` and `Debug`, vacuously, so a type holding it compares, hashes and prints as its other parts
allow. And **`?` on a `Result[T, never]` needs no `From`**: its failure path is never taken, so it
crosses into a function returning any error type.

```sysl
enum Error[E] derives Eq, Display
    Io(cause: E)
    Short

enum Fault
    Low

header(n: int) -> Result[int, Error[never]] = if n > 0 then Ok(n) else Err(Short)

doubled(n: int) -> Result[int, never] = Ok(n * 2)

total(n: int) -> Result[int, Fault]
    val d = doubled(n)?
    Ok(d + 1)
end total

header(0) match
    Ok(n) -> print(n)
    Err(Short) -> print("short")

doubled(4) match
    Ok(n) -> print(n)

print(header(3), total(4), header(0) == Err(Short))
```

```output
short
8
Ok(3) Ok(9) true
```

Only the argument itself may be `never`. A part of one — `Option[&never]`, `Buf[[2]never]` — still
names a value of a type that has none, and so does a field, a parameter or a variable that writes
`never` out:

```sysl
var o: Option[&never] = None
```

```error
'never' is the type of an expression that does not finish, so it can only be a result type or a type argument
```

**An expression that does not finish is still refused wherever a value must arrive**, and a generic
parameter is never inferred from one: `id(exit(1))` is refused, as `val x = exit(1)` is. A read of
storage whose type is `never` — the payload an `Err` arm binds out of a `Result[T, never]`, or a
parameter of a body instantiated at `never` — is not refused: no run reaches it.

## `string`

A validated UTF-8 string, three words wide. It is immutable; indexing it by byte position gives
bytes, iterating it gives `char`s, and `+` concatenates but rejects a non-string operand.
[Strings](/reference/strings/) covers it in full — the representation, the validity guarantee, and
every form that creates new bytes — and the library operations are under
[`sysl.text`](/library/text/).

## Fixed arrays and slices

sysl has two sequence types.

| type | what it is |
|---|---|
| `[N]T` | a **fixed array**: `N` elements, a value, no header, length known while compiling |
| `[]T` | a **slice**: a view of elements someone else owns — `{ owner, pointer, length }` |
| `[]const T` | a slice that may be read and not written |

An array **is** its elements, so copying one copies all of them and passing one by value passes the
whole thing. A slice **names** elements that live somewhere else, and copying a slice copies the
three-word header rather than the data.

```sysl
var a = [1, 2, 3]
var b = a

b[0] = 99

var v = a[..]
v[1] = 88

print(a[0], a[1], b[0])
```

```output
1 88 99
```

Writing through `b` did not change `a`, because `b` is a copy; writing through `v` did, because `v`
is a view.

Both carry a length, so **every index is checked**. [Arrays and slices](/reference/arrays/) covers
them in full — literals, storage sized at run time, the indexing and slicing rules, and what a view
keeps alive.

## Vectors

A third sequence type, which is a register value rather than storage.

| type | what it is |
|---|---|
| `<N>T` | a **vector**: N lanes of `T` in a register, whose operators work on every lane at once |

It holds the same values an `[N]T` holds, in the same order; the two type constructors differ only in
their brackets because a vector is an array whose operators work lane-wise:

```sysl
val a: <4>f32 = [1, 2, 3, 4]
val b: <4>f32 = [10, 20, 30, 40]

print((a + b)[3], (a * 2)[0], (a * b).sum())
```

```output
44 2 300
```

That `+` is one instruction performing four additions, and the `2.0` is broadcast into every lane. A
lane is read by a **constant** index, which is checked at compile time rather than at run time. A
run of an array or a slice moves into a vector and back with `xs.load(i)` and `xs.store(i, v)`, which
*are* checked at run time.

A machine with no vector unit needs no special handling: the back end splits a vector across as many
registers as it needs, or into ordinary scalar operations, so `<4>f32` compiles on every target.
[Vectors](/reference/vectors/) covers them in full — masks and `select`, the reductions, and writing
one kernel that compiles for more than one register width.

## Structs

A named product type. Fields are declared one per line, and a struct is a **value** — assigning one
copies it.

```sysl
struct Point
    x: int
    y: int

var p = Point(1, 2)
var q = p

q.x = 99

print(p.x, q.x)
```

```output
1 99
```

**A large struct is copied as its bytes.** Every copy of a struct, enum or variant payload over 128
bytes — a literal built from calls, an assignment, an argument, a return, a payload bound by a pattern
— is a block copy (`memcpy`) from where the value was read, and reading one field reads only that
field. Copying a struct that holds a large array costs what copying the array costs, and the copy is
independent: it holds what its source held when it was made.

```sysl
struct Frame
    pixels: [32768]u8
    id: int

frame(id: int) -> Frame = Frame([u8(id); 32768], id)

struct Pair
    a: Frame
    b: Frame

var p = Pair(frame(1), frame(2))
val kept = p.a

p.a = frame(3)
print(kept.id, kept.pixels[32767], p.a.id, p.b.pixels[0])
```

```output
1 1 3 2
```

Structs may carry methods, an `invariant`, and a visibility modifier per field. A struct may also be
declared `opaque`, which hides its layout from code outside its own module — a separate property from
visibility, covered under [modules](/tour/modules/).

**A field may be a C bitfield**, written `name: uW in T` — C's `T name : W;`. It holds `W` bits
allocated in a unit of `T`, the type C declares it with, and a struct with any such field is laid out
as the target's C compiler lays its counterpart out. The value type is an integer of `W` bits, a
simple enum, or `bool`; `_: uW in T` is an unnamed field and `_: in T` a zero-width one. The field is
read, written, built and matched like any other ([attributes](/reference/attributes/) has the rules):

```sysl
struct Status
    ready: bool in u32
    mode: u3 in u32
    _: u4 in u32
    count: i12 in i32

var s = Status(true, 5, -7)

s.count -= 1
print(sizeof(Status), s.ready, s.mode, s.count)
```

```output
4 true 5 -8
```

## Unions

A `union` is storage its fields **share**: every field begins at its first byte, and nothing records
which one was written last. It is for the C structures that contain one — `in6_addr`, `epoll_data`,
`SDL_Event`, a register read as a word or as its bits. It is declared like a struct, and `union` is a
contextual word, so a function may still be named `union`.

```sysl
union In6
    b8: [16]u8
    b16: [8]u16
    b32: [4]u32

var a = In6(b32 = [0, 0, 0, 0])
a.b8[15] = 1

print(sizeof(In6), alignof(In6), a.b32[3])
```

```output
16 4 16777216
```

**The layout is C's**: the largest field rounded up to the widest boundary among them, every field at
offset 0, `@packed` giving a boundary of one and no rounding, `@align(n)` raising both. The layout is
the same on every target, and `@assert(sizeof(U) == …)` can state it. A union can be a field, an
element or a value like any other: it is copied, passed and returned whole.

**A union is built from exactly one field, by name, and the bytes past it are zero.** Only the name
says which field the value is for, so a value by position is refused even when there is one field;
adding a field therefore never changes what an existing construction means. A write to a field
stores that field's bytes and leaves the rest unchanged. Since every field has a zero value, so does
the union: all zero bytes.

```sysl
union Wide
    small: u8
    big: u32

var w = Wide(big = 0x01020304)
w.small = 0xff
print(w.big, Wide(small = 7).big)
```

```output
16909311 7
```

```sysl
union Wide
    small: u8
    big: u32

val w = Wide(7)
```

```error
union 'Wide' is built from one field, named — 'Wide(small = …)': a value by position would mean another field once one is declared in front of it
```

**Every part of a field is plain data or a raw pointer.** A union does not know which field it holds,
so nothing could release a counted value: a reference, a string, a slice, a closure, a data enum, a
`Buf` or a type with a `drop` is refused at the field — or, for a generic union, where the instance
is made. An integer narrower than its byte is refused too; a bitfield struct can hold one.

```sysl
union Name
    s: string
    n: u64
```

```error
union 'Name' cannot hold its field 's': a string is counted, and nothing in a union says whether one is there to release
```

**A field whose bytes may not be a valid value of its type is checked where it is read.** Every bit
pattern of an [`AnyBits`](/library/core/) field — integers of whole bytes, floats, raw and function
pointers, and arrays, vectors and structs of them — is a valid value, so reading one is a plain
load. A `bool`, a simple enum, a `char` and a subtype with a range or a predicate are not: reading
one that holds no valid value (a `bool` holding 2, an enum holding no variant's number) **traps**,
and an enum's trap reports the number it found and where, as a bitfield's enum field does. Reading a
nested field (`ev.key.down`) checks the part it reads, and reading a whole struct checks each part of
it that can be invalid. A `ref` may not name a checked field, because a read through the name would
skip the check while a write to a sibling field changes the bytes under it.

```sysl
union Key
    raw: u8
    down: bool

var k = Key(raw = 1)
print(k.down)
k.raw = 0
print(k.down)
```

```output
true
false
```

**A `*T` field beside an integer converts the integer to a pointer without `ptr_cast`**, as
`epoll_data` and `sigval` do. Creating that pointer is harmless, since dereferencing any `*T` is
already raw-pointer code, but `union … *` is a second searchable spelling of the conversion.

A union is `AnyBits` where every field is, and [`Plain`](/library/encoding/) where every field is both
`AnyBits` and `Plain`, because a decode cannot know which field's bytes to check.

**What a union does not have**, since nothing records which field it holds:

- **no `==`**: comparing bytes is wrong for a float (`+0.0` and `-0.0`) and for the bytes past a
  short field. An `impl Eq` of the program's own compares the field it knows; `derives` is refused.
- **no destructuring**: a pattern cannot take one apart, though binding one whole is fine.
- **a `Debug` that shows the bytes**: the name and every byte in hex, so showing a union never reads a
  field and never traps. An `impl Debug` of the program's own replaces it.

```sysl
union Word
    whole: u32
    f: f32

print(Word(f = 1))
```

```output
Word(00 00 80 3f)
```

```sysl
union Word
    whole: u32
    f: f32

val w = Word(whole = 1)
print(w == w)
```

```error
'==' is not defined for the union Word — comparing its bytes is wrong for a float and for the bytes past a short field. Write 'impl Eq' comparing the field you know is there
```

A union crosses to C by value and is declared in a generated header as C's own union; see
[a union at the boundary](/reference/ffi/#a-union-at-the-boundary).

### Anonymous members

Inside a struct or a union, a line that is exactly `union` or `struct` opens an **anonymous member**,
as in C11: its fields belong to the enclosing type, so `p.word` reads one, `offsetof(Packet, level)`
measures one, and a construction names one. It is laid out as C lays it out — where a named field of
the same shape would be — and the generated header writes it in place as `union { … };`. It may close
with `end union` or `end struct`.

```sysl
struct Packet
    tag: u8
    union
        word: u32
        level: f32
    len: u16

union Split
    all: u64
    struct
        lo: u32
        hi: u32

var p = Packet(tag = 1, word = 0x3f800000, len = 2)

print(p.level, offsetof(Packet, level), offsetof(Packet, len), sizeof(Packet))
print(Split(lo = 1, hi = 2).all, p)
```

```output
1 4 8 12
8589934593 Packet(tag: 1, union(00 00 80 3f), len: 2)
```

A type holding an anonymous member is **built by naming its fields**: every field a struct holds,
and exactly one of each anonymous union. A name may appear only once across all the levels, as in
C11. An unnamed bitfield (`_: u4 in u32`) is never named; it holds zero, as in any struct.

```sysl
struct Packet
    tag: u8
    union
        word: u32
        level: f32

val p = Packet(tag = 1, word = 7, level = 2)
```

```error
the anonymous union in 'Packet' is built from exactly one field, and 2 were named — it holds 'word' and 'level'
```

```sysl
struct Packet
    tag: u8
    union
        tag: u16
        level: f32
```

```error
'tag' is declared twice — an anonymous member's fields are its enclosing type's own, so a name appears once across the levels
```

An `invariant` also names them without qualification, at any depth. A clause reads a union's field
**only when evaluation reaches the name**, so a field it does not evaluate is never read: below,
`raw = 7` is not a valid `bool`, and the clause is satisfied at `tag == 0` without reading `ok`.

```sysl
struct Packet
    tag: int
    union
        ok: bool
        raw: u8
    invariant tag == 0 || ok

val p = Packet(tag = 0, raw = 7)
val q = Packet(tag = 1, ok = true)

print(p.raw, q.ok)
```

```output
7 true
```

### Unions in constant data and with a byte order

A module `val` built from constants is **emitted as static data**, with a union in it stored as the
bytes of the field it was built with followed by zeros. A register map or a protocol table holding
unions therefore costs nothing at start-up and works on bare metal. The same holds for a `@packed`
struct holding a union, at the unaligned offset the struct gives it. A `const` function may build a
union, write a field of it, and read it back **through the field it was built or last written
with**. Compile-time evaluation works on values, not bytes, so a read through another field is
refused there (at run time it is an ordinary reinterpreting read).

```sysl
union Reg
    bits: u32
    level: f32

const make(n: u32) -> Reg = Reg(bits = n)
const bits_of(r: Reg) -> u32 = r.bits
const K = bits_of(make(7))

const rewritten(n: u32) -> u32
    var r = Reg(level = 1)

    r.bits = n
    r.bits

const W = rewritten(5)

val table = [Reg(level = 1), make(9)]

print(K, W, table[0].bits, table[1].bits)
```

```output
7 5 1065353216 9
```

```sysl
union Reg
    bits: u32
    level: f32

const level_of(r: Reg) -> f32 = r.level
const K = level_of(Reg(bits = 7))
```

```error
evaluation while compiling reads a union only through the field it was built with — this 'Reg' was built with 'bits', and 'level' would read its bytes as another type
```

`@byte_order(big)` or `(little)` on a union orders every integer of every field, as on a struct
([`@byte_order`](/reference/attributes/#byte-order-big-and-byte-order-little-the-order-a-struct-s-integers-keep-their-bytes-in)), including the fields of its anonymous members.
`sysl prove` refuses a union by name, because WhyML has no model of bytes with which to define a
read through another field.

## Tuples

A product type with no name: a fixed-length, positional group of values, written with parentheses.
A struct names its parts; a tuple numbers them.

```sysl
var p: (int, string) = (1, "one")
var q = (3, 4)

print(p.0, p.1)
print(q.0, q.1)

q.0 = 30
q.1 += 1

print(q.0, q.1)

val a, b = q

print(a, b)
```

```output
1 one
3 4
30 5
30 5
```

The example shows the four rules that define the type.

**A part is reached by position, `t.0` and `t.1`.** It is written like a field because it *is* one —
a tuple's fields are named for their positions — so a part is a **place** like any other field, and
`q.0 = 30` and `q.1 += 1` write through it.

**The type is inferred from the parts.** `(3, 4)` is a `(int, int)`, and an annotation is needed only
where the parts alone do not determine it, as anywhere else.

**A tuple is taken apart with the ordinary comma binding**, `val a, b = q`, or with a
[tuple pattern](/reference/patterns/) in a `match`.

**Arity starts at two.** There is no one-tuple: `(x)` is a parenthesized expression, since a
one-element product is just the element. There is no zero-tuple either; `unit` fills that role.

### A nested part needs parentheses

`t.0.1` does not select part 1 of part 0, because the lexer reads `0.1` as a floating-point number.
The second step is therefore parenthesized:

```sysl
val t = ((1, 2), 3)

print((t.0).1)
```

```output
2
```

The compiler recognizes a float after a `.` and gives the form to write instead:

```sysl
val t = ((1, 2), 3)

print(t.0.1)
```

```error
'.0.1' reads as a number rather than as two tuple indices — write '(x.0).1' to select part 1 of part 0
```

### A tuple is a type; a result list is not

This is the difference between a tuple and a
[result list](/reference/declarations/#several-results). A result list is a property of a
*signature*: several values pass from callee to caller, and nothing holds them together afterwards.
A tuple is a **type** — it can be stored in a variable, held in a field, put inside an `Option`, made
the element of a container, and named as a generic argument.

```sysl
list(n: int) -> int, int = n / 2, n % 2

pair(n: int) -> (int, int) = (n / 2, n % 2)

val a, b = list(7)
val c, d = pair(7)

val held = pair(9)

print(a, b, c, d, held.0, held.1)
```

```output
3 1 3 1 4 1
```

`-> int, int` and `-> (int, int)` look alike but mean different things. In most code **the caller
does not need to know which one it is calling**, because `val a, b = f(x)` takes either apart. The
choice is the callee's, and changing it breaks only call sites that store the pair. Only the tuple
can be held, as `held` shows.

Use a result list when the values do not need to stay together, and a tuple when they do.

### What the library gives every tuple

`Eq`, `Ord`, `Hash` and `Display` are provided structurally, at **every** arity, whenever every
component implements the trait. Ordering is lexicographic, first component first. `unit`, the tuple
with no parts, has the same four ([`unit` and `never`](#unit-and-never)).

```sysl
val pair = (1, "one")
val trip = (1, 2.5, 'x')

val o = Some(("k", 7))

o match
    Some((k, v)) -> print(k, v)
    None -> print("none")

print(pair)
print(trip)
print(pair == (1, "one"), (1, 2) < (1, 3))
print(sizeof((u8, u8, u8)))
```

```output
k 7
(1, one)
(1, 2.5, x)
true true
3
```

None of these implementations is generated by the compiler. They are ordinary `impl` blocks in the
standard library, written once over a
[type pack](/reference/generics/#a-parameter-may-stand-for-a-list-of-types): a `(..A)` matches a
tuple of any arity, so four blocks cover every tuple.

The last line of the example shows the layout rule: a tuple is laid out **as a struct with those
fields in that order**, so `(u8, u8, u8)` is three bytes, and a tuple holding a `&T` retains and
releases it exactly as a struct field does.

### Who may implement what

Each arity is its own type, so an `impl` for a pair says nothing about a triple. A trait of your own
may be implemented for a tuple, because the trait is local, which the
[coherence rule](/reference/traits/#any-type-may-carry-an-impl) permits:

```sysl
trait Tag
    tag(self) -> string

impl Tag for (int, string)
    tag(self) -> string = "pair"

print((1, "a").tag())
```

```output
pair
```

`impl Eq for (int, string)` is refused by the ordinary coherence rule: a tuple type has no
declaration and therefore no module of its own, and a built-in type belongs to the library. Both the
trait and the type are therefore foreign, which coherence forbids:

```sysl
impl Eq for (int, string)
    eq(self, rhs: (int, string)) -> bool = true

print(1)
```

```error
an 'impl' may be written only in the module that declares the trait or in one that declares a type the block names, and 'Eq' is the library's while nothing in '(int, string)' is declared outside the library — so this one has no home. A trait of your own, or a type of your own in what it is written for or in the trait's arguments, gives it one
```

## Enums

One keyword declares two kinds of type. A **simple** enum is a set of named discriminants with an
underlying integer type; a **data** enum gives its variants payloads, making it a sum type.

```sysl
enum Color
    Red
    Green
    Blue

enum Shape
    Circle(r: real)
    Rect(w: real, h: real)

area(s: Shape) -> real = s match
    Circle(r)  -> 3.14159 * r * r
    Rect(w, h) -> w * h

print(int(Green), area(Circle(1)), area(Rect(2, 3)))
```

```output
1 3.14159 6
```

`Option[T]` and `Result[T, E]` are ordinary data enums declared in the standard library, with no
special treatment by the compiler, so an enum you write works the same way.

**A variant that carries a payload is constructed, never named on its own.** Written without its
values it is neither a value of the enum nor a function that makes one. A position that expects the
enum does not change this, because it determines which enum is meant, not what the variant holds:

```sysl
enum Shape
    Circle(r: real)
    Dot

val shapes: []Shape = [Shape.Dot, Shape.Circle]

print(shapes.len)
```

```error
variant 'Circle' carries data — construct it with 'Circle(…)'
```

### A variant belongs to its enum

Two enums in one module may each have a variant named `Circle`, and neither has to be renamed.
**What a bare name means is decided where it is used, by the type expected there** — an argument, an
annotated binding, a `return` and a field all supply one, so the short form is the usual one:

```sysl
enum Shape
    Circle(r: int)
    Square(side: int)

enum Hole
    Circle(r: int)
    Slot(len: int)

area(s: Shape) -> int = s match
    Circle(r)    -> 3 * r * r
    Square(side) -> side * side

depth(h: Hole) -> int = h match
    Circle(r) -> r
    Slot(len) -> len

val s: Shape = Circle(2)

print(area(s), depth(Circle(5)))
```

```output
12 5
```

Where two enums match and nothing says which is meant, the compiler reports it rather than choosing
one, so a line's meaning cannot change when an unrelated enum is added elsewhere:

```sysl
enum Shape
    Circle(r: int)
    Square(side: int)

enum Hole
    Circle(r: int)
    Slot(len: int)

var s = Circle(1)

print(1)
```

```error
'Circle' is a variant of 'Shape' and 'Hole', and nothing here says which — qualify it, as 'Shape.Circle'
```

That line should be `Shape.Circle(1)`. The qualified form works at a construction exactly as it
[works in a pattern](/reference/patterns/#the-bare-name-rule).

**The expected type works across modules too, so a program may declare a variant whose name the
library also uses.** `Result` and `Option` are in the prelude, so `Ok`, `Err`, `Some` and `None` are
the names most likely to collide with your own enums, and a module that declares its own `Ok` can
still return a `Result`:

```sysl
enum Status
    Ok
    Bad

f() -> Result[int, string] = Ok(1)
g() -> Result[int, string] = Err("no")

s() -> Status = Ok

print(f().unwrap(), g().is_err(), s() == Ok)
```

```output
1 true true
```

Each `Ok` resolves to the variant of the type expected on its line: the library's where the expected
type is the library's, and the module's own where it is the module's. At a position that expects a
`Result` but means the module's variant, write `Status.Ok`.

**An enum imported by name makes its variants available bare in the same way**, at every position
that expects it, including inside an `Err` or a `Some`. Here only the enum is imported, none of its
variants:

```sysl
import sysl.fs.IoError

open_it(there: bool) -> Result[int, IoError]
    if !there then return Err(NotFound)
    Ok(3)

val clash: IoError = AlreadyExists

print(open_it(false).unwrap_err().code(), clash.code(), open_it(true).unwrap())
```

```output
2 17 3
```

A name the file already uses for something else — its own function `NotFound()`, say — keeps that
meaning, and an enum the file has not imported contributes no bare names; `sysl.fs.IoError.NotFound`
is the spelling that needs no import.

As in Rust, a variant is namespaced under its enum; unlike Rust, a use site does not need
`Shape::Circle` or a `use Shape::*`. A variant still may not share a name with a constant, a `val`, a
module `var` or an `extern` variable: two variants with one name are distinguished by their enums,
but a variant and a constant could not be distinguished.

## Type aliases

`type Name = Existing` introduces a second name for a type, interchangeable with the first. It
creates **no** new type and no checking: an alias shortens a long name; it does not distinguish two
uses of the same representation.

**The base may be any type**, which matters most for compound types, whose names are the long ones:

```sysl
struct Point
    x: int
    y: int

type P = Point
type Comparison = *extern(*u8, *u8) -> i32
type Triple = [3]int
type MaybeInt = Option[int]

val p: P = P(3, 4)
var t: Triple = [1, 2, 3]
val m: MaybeInt = Some(t[2])

print(p.x + p.y, m.unwrap())
```

```output
7 3
```

Because an alias is a name and not a type, everything the base has is available through it: a value
of one name is accepted where the other is expected, a struct is constructed and read through the
alias, and a method or trait implementation declared on the base applies to the alias unchanged.

A distinct type — one the compiler will not let you confuse with its base — is a **constrained
type**, written with `new` and covered under [contracts](/tour/contracts/). A `new` type may take
type parameters that change which type it is but not how it is laid out: `UserPtr[Frame]` and
`UserPtr[u8]` over `type UserPtr[T] = new u64` are two types, both a `u64` underneath
([declarations](/reference/declarations/)).

## Function types

A callable's type is written with `Fn`:

```sysl
apply(f: &Fn(int) -> int, x: int) -> int = f(x)

print(apply(n -> n * 3, 7))
```

```output
21
```

A named function used where a callable is expected is the capture-free case of the same type; there
is no separate function-pointer concept. A raw C function pointer, for a foreign boundary, is
written `*extern(A) -> R` and is covered under the [foreign interface](/reference/ffi/).

**A parameter written with a bare arrow is a type parameter.** `f: A -> B` is shorthand for
`[$F: Fn(A) -> B](f: $F)`: the closure's type is a *type argument*, so the function is monomorphized
and calls the closure directly, with nothing on the heap. `f: &Fn(A) -> B` is a counted reference to
a trait object, so the closure is boxed at the call and dispatched through a table. Both accept the
same closures; they differ in what the call costs and in what the declaration compiles to.

**Parentheses before the arrow are its parameter list**, whatever its length: `(string) -> usize`
is the same type as `string -> usize`, `() -> T` takes nothing, and `((int, int)) -> int` takes one
tuple. Only the arrow makes them a list — `(string)` with none after it is a type in parentheses,
and refused.

```sysl
apply(f: (string) -> usize, s: string) -> usize = f(s)
sum(f: ((int, int)) -> int) -> int = f((3, 4))

print(apply(t -> t.len, "four"), sum(t -> t.0 + t.1))
```

```output
4 7
```

**A trait member may use either**, with one further consequence: an arrow makes the member declare a
type parameter, so it gets no table slot and cannot be dispatched through a trait object
([traits](/reference/traits/#object-safety)). A trait meant for trait objects uses `&Fn(…)`; one
meant for static dispatch can use the arrow.

```sysl
trait Applies
    tag(self) -> int
    apply(self, f: int -> int) -> int

struct N
    v: int

impl Applies for N
    tag(self) -> int = self.v
    apply(self, f: int -> int) -> int = f(self.v)

reach[T: Applies](x: T) -> int = x.apply(n -> n * 2)

print(N(20).apply(n -> n + 1), reach(N(21)))
```

```output
21 42
```

### A parameter passed by name

A parameter written with the arrow and **nothing on its left** takes an expression the call does not
evaluate, and the body evaluates at each use:

```sysl
static var built: int = 0

message() -> int
    built += 1
    42

log(on: bool, m: -> int)
    if on then print(m)

log(false, message())
log(true, message())
print(built)
```

```output
42
1
```

`message()` ran once, not twice: the first call never evaluated its argument. This makes an argument
the callee may not use free to pass.

**Each use is an evaluation**, because each use is a call, so a body that names the parameter twice
evaluates the argument twice. To evaluate it once, bind it to a `val` first.

The same holds inside a closure or a nested function in the body: the parameter is still read by
name, and the argument is evaluated each time the closure is called rather than once when the
closure is created.

```sysl
static var built: int = 0

message() -> int
    built += 1
    built * 10

show_twice(m: -> int)
    val show = () -> print(m + 1)
    show()
    show()

show_twice(message())
print(built)
```

```output
11
21
2
```

It has no run-time overhead. `x: -> T` has the type `Fn() -> T`, so it compiles to a bounded type
parameter exactly as the ordinary bare arrow does: one specialized copy per call site, called
directly, with no allocation.

**`x: () -> T` is a different form.** It has the same type, but the caller writes the callable
itself and the body calls it:

```sysl
twice(f: () -> int) -> int = f() + f()

print(twice(() -> 21))
```

```output
42
```
