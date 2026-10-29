---
title: sysl.fmt
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.fmt
summary: "What an `f\"…\"` hole's computed width or precision comes to — `${x}%.${n}f`, `${s}%${w}s` — and what an integer under a specifier renders as — `${n}%08x`."
---

A program does not call these; the compiler does, at a specifier whose width or precision is a
hole of its own, and at every integer conversion. They read a count the way C's `*` does, which is
the one rule both halves of a specifier follow: a value C formats is handed the count, and a type
rendering itself through `Display` is handed the `FormatSpec` the same count makes.

## Index

[`f128_scratch_len`](#f128_scratch_len) [`float_scratch_len`](#float_scratch_len) [`binary`](#binary) [`fixed_scratch_len`](#fixed_scratch_len) [`format_bf16_plain`](#format_bf16_plain) [`format_count`](#format_count) [`format_f128`](#format_f128) [`format_f128_plain`](#format_f128_plain) [`format_f128_shortest`](#format_f128_shortest) [`format_f16_plain`](#format_f16_plain) [`format_f32`](#format_f32) [`format_f32_plain`](#format_f32_plain) [`format_fixed`](#format_fixed) [`format_fixed_wide`](#format_fixed_wide) [`format_int`](#format_int) [`format_real`](#format_real) [`format_real_plain`](#format_real_plain) [`format_spec_of`](#format_spec_of) [`format_str`](#format_str) [`hex`](#hex) [`octal`](#octal) [`real_digits`](#real_digits) [`real_digits`](#real_digits-1) [`shortest`](#shortest) [`shortest`](#shortest-1) [`shortest`](#shortest-2) [`write_f128`](#write_f128) [`write_f32`](#write_f32) [`write_fixed`](#write_fixed) [`write_fixed_wide`](#write_fixed_wide) [`write_real`](#write_real) [`FormatError`](#formaterror) [`RealDigits`](#realdigits) [`RealKind`](#realkind) [Display for FormatError](#display-for-formaterror)

## Constants

### `f128_scratch_len`

```sysl
const f128_scratch_len: usize = 11600
```

A scratch this long is enough for `write_f128` under any specifier: an `f128`'s subnormals run to
11,563 significant digits.

### `float_scratch_len`

```sysl
const float_scratch_len: usize = 800
```

A scratch this long is enough for `write_real` and `write_f32` under any specifier: the longest
exact `real` runs to 767 significant digits.

## Functions

### `binary`

```sysl
binary[T: Integer](v: T, width: int = 0) -> string
```

An integer in base two, as `f"$v%b"` makes it: `width` zero-pads, and a negative value reads at
its own width, so `binary(i8(-6))` is `"11111010"`.

### `fixed_scratch_len`

```sysl
fixed_scratch_len(bits: usize) -> usize
```

A scratch this long is enough for `write_fixed_wide` of a fixed-point value stored in `bits` bits,
under any specifier: a value never has more significant digits than it has bits.

### `format_bf16_plain`

```sysl
format_bf16_plain(x: bf16) -> string
```

A `bf16` as `str` writes it: `format_real_plain`'s rule in the digits a `bf16` needs.

### `format_count`

```sysl
format_count(n: i128) -> int
```

A count as the `int` C's `*` reads, saturated at `int`'s range rather than wrapped into it.

The compiler hands every integer over at `i128`, which holds any of them up to 64 bits exactly,
so a `usize` past `int` is a very wide field rather than a negative one.

### `format_f128`

```sysl
format_f128(x: f128, spec: string, width: int, precision: int) -> string
```

An `f128` under a printf specifier, rendered as `format_real` renders a `real` -- every digit the
exact value's, rounded half to even where the conversion says -- and taken apart at its own width,
so `${x}%.30e` prints thirty digits binary128 really holds rather than a `double`'s seventeen and
noise. A value that is not a `double`'s works in wider integers, and the digits buffer holds the
11,563 significant digits the longest one has.

### `format_f128_plain`

```sysl
format_f128_plain(x: f128) -> string
```

An `f128` as `str` writes it: `format_real_plain`'s rule in the digits an `f128` needs.

### `format_f128_shortest`

```sysl
format_f128_shortest(x: f128) -> string
```

An `f128` in the fewest digits that read back as the same `f128`, spelled as `shortest` spells a
`real` -- what `shortest(x)` answers for one.

### `format_f16_plain`

```sysl
format_f16_plain(x: f16) -> string
```

An `f16` as `str` writes it: `format_real_plain`'s rule in the digits an `f16` needs.

### `format_f32`

```sysl
format_f32(x: f32, spec: string, width: int, precision: int) -> string
```

An `f32` under a printf specifier, as `format_real` renders the same value: an `f32` holds nothing
a `real` cannot, so the text is the same, and it is taken apart at its own width so that no double
arithmetic is asked of a machine that has only single precision.

### `format_f32_plain`

```sysl
format_f32_plain(x: f32) -> string
```

An `f32` as `str` writes it: `format_real_plain`'s rule in the digits an `f32` needs.

### `format_fixed`

```sysl
format_fixed(raw: i128, bits: int, signed: bool, frac: int, spec: string, width: int, precision: int) -> string
```

A fixed-point value under a printf float conversion -- what `${x}%.4f` lowers to when `x` is a `q31`,
an `i32q16` or any other `iWqF`/`uWqF`. A program does not call it; it writes the hole.

`raw` is the integer the value is stored as (`x.bits`) at `i128` -- a `u128` above `i128`'s maximum
arriving as its two's complement, which `bits` and `signed` read back -- and the value is
`raw * 2^-frac`. **That is a dyadic rational, so its decimal expansion is finite and is printed
exactly**, by the machinery `format_real` uses: the digits are the true value's, rounded half to
even at the position the conversion names, never a `double`'s approximation of it. A `q31` or an
`i64q60` keeps every one of its bits -- `${q31::Max}%.12f` is `0.999999999534`, and `%.20f` of an
`i64q60` prints digits no `double` holds. `spec`, `width` and `precision` are read as `format_real`
reads them, flag for flag.

### `format_fixed_wide`

```sysl
format_fixed_wide[T: Integer](raw: T, frac: int, spec: string, width: int, precision: int) -> string
```

A fixed-point value of any width under a printf float conversion -- what `${x}%.4f` lowers to when
`x` is wider than 128 bits, an `i256q100` say. `raw` is the integer the value is stored as
(`x.bits`) at its own type, `frac` is the format's fraction, and the value is `raw * 2^-frac`. The
digits are exact and rounded as `format_fixed`'s are, half to even at the position the conversion
names; the arithmetic is the same, over working integers sized by the type, on the stack. `%a` is
not offered, as a hole does not offer it, and answers an empty string.

### `format_int`

```sysl
format_int(n: i128, bits: int, spec: string, width: int, precision: int) -> string
```

An integer rendered through a printf specifier, written here rather than handed to C's
`snprintf`, so a target with no C library formats `${n}%d` exactly as a hosted one does.

`n` is the value at `i128`, and `bits` is the width of the type it came from, up to 128: a `u128`
above `i128`'s maximum arrives as its two's complement, which an unsigned conversion reads at `bits`. `spec` is the whole specifier, `%` to conversion letter; a `*` in it
reads `width` or `precision`, as C's `*` does — a negative width left-justifies at its magnitude
and a negative precision is as though none was written.

The rendering is C's, flag for flag: a precision is a minimum count of digits, and a precision of
zero renders a zero as nothing; `0` pads with zeros after the sign and the base prefix, and gives
way to `-` or to a precision; `+` and a space mark a non-negative signed value; `#` puts a leading
zero on an octal and `0x`, `0X` or `0b` on a non-zero hexadecimal or binary. A signed conversion
(`d`, `i`) keeps the value's sign, and an unsigned one (`u`, `o`, `x`, `X`, `b`) reads the value at
its own width, so `%x` of an `i32 -1` is `ffffffff` and of a `u8 255` is `ff`.

### `format_real`

```sysl
format_real(x: real, spec: string, width: int, precision: int) -> string
```

A float rendered through a printf specifier, written here rather than handed to C's `snprintf`,
so a target with no C library formats `${x}%.3f` exactly as a hosted one does -- and a hosted one
prints what it printed before.

`spec` is the whole specifier, `%` to conversion letter, which is one of `f`, `e`, `g` or an upper
case `F`, `E`, `G`; a `*` in it reads `width` or `precision` as `format_int`'s does. The digits are
the exact value's, rounded half to even at the position the conversion names, whatever the
precision -- `${0.125}%.2f` is `0.12` and `${2.5}%.0f` is `2` -- and the rest is C's, flag for
flag: `%e`'s exponent has at least two digits, `%g` takes the precision as significant digits
and drops trailing zeros unless `#` keeps them, `0` pads after the sign, and an infinity or a NaN
is `inf` or `nan` (upper case under an upper-case letter), never padded with zeros, and signed as
C signs one: `-nan` where the sign bit is set, `+nan` or ` nan` under `+` or a space.

### `format_real_plain`

```sysl
format_real_plain(x: real) -> string
```

A `real` as `str` and a plain hole write it -- what Rust's `{}` writes: the fewest digits that read
back as the same `real`, set out positionally, so `0.1`, `1`, `100000000000000000000` and
`0.0000001`, never an exponent and never a trailing `.0`; `-0`, `inf`, `-inf` and `NaN` for the rest.
The compiler calls it; a program writes `x.to_string()` or `s"$x"`.

### `format_spec_of`

```sysl
format_spec_of(width: int, prec: int, left: bool) -> FormatSpec
```

The `FormatSpec` a run-time width and precision make, read as C reads a `*`: a negative width
left-justifies the field at its magnitude, and a negative precision is as though none was written.

### `format_str`

```sysl
format_str(s: string, spec: string, width: int, precision: int) -> string
```

A string rendered through a `%s` specifier, written here rather than handed to C's `snprintf`, so
a target with no C library pads and cuts `${s}%-6s` exactly as a hosted one does.

`spec` is the whole specifier, `%` to `s`; a `*` in it reads `width` or `precision`, as C's `*`
does — a negative width left-justifies at its magnitude and a negative precision is as though none
was written.

The rendering is C's, byte for byte. **A width and a precision count bytes, not characters**: a
width is the field's minimum length in bytes, and a precision the most bytes of the text the field
takes, cut where it falls even inside a character. The text ends at its first NUL byte, as a C
string does. `-` pads on the right; `0` pads a right-justified field with zeros, which is what
Darwin's and musl's C libraries do with a flag the C standard leaves undefined for `%s`; `+`, a
space and `#` change nothing.

### `hex`

```sysl
hex[T: Integer](v: T, width: int = 0, upper: bool = false) -> string
```

An integer in base sixteen, as the string `f"$v%x"` makes, for code that wants the text without
writing an `f"…"`. `width` is a minimum count of digits, padded with zeros on the left, and `upper`
picks `A`-`F`. A negative value reads at its own width, two's complement, so `hex(i8(-1))` is
`"ff"` and `hex(-1)` (an `int`) is `"ffffffff"`. There is no `0x` prefix. Every integer
width is exact, `u128` and `i128` included, with no C library and no 128-bit division.

### `octal`

```sysl
octal[T: Integer](v: T, width: int = 0) -> string
```

An integer in base eight, as `f"$v%o"` makes it: `width` zero-pads, and a negative value reads at
its own width, so `octal(i8(-6))` is `"372"`.

### `real_digits`

```sysl
real_digits(x: real) -> RealDigits
```

The fewest digits that read back as `x`, and the decimal exponent of the first -- the same digits
`shortest(x)` spells in `%g` style, for a program that writes reals its own way (Java's `1.0E20`,
say). See `RealDigits` for how to read the answer.

### `real_digits`

```sysl
real_digits(x: f32) -> RealDigits
```

The fewest digits that read back as the same `f32`: `real_digits(f32(0.1))` is `"1"`, exponent -1.

### `shortest`

```sysl
shortest(x: f128) -> string
```

The fewest digits that read back as the same `f128` -- `0.1` for `0.1f128`, and up to thirty-six
for a value that needs them.

### `shortest`

```sysl
shortest(x: real) -> string
```

The fewest digits that read back as `x` -- among those, the closest to it, and a tie to the even
digit -- spelled as `%g` spells a number: `0.1`, `1e+23`, `5e-324`, `-0`, `inf`, `nan`. It is the
text a serializer wants, where `%g`'s six digits lose the value and seventeen print noise. The
algorithm is Ryu.

### `shortest`

```sysl
shortest(x: f32) -> string
```

The fewest digits that read back as the same `f32`, which is shorter than its widened `real`
needs: `shortest(f32(0.1))` is `0.1`, and `shortest(real(f32(0.1)))` is `0.10000000149011612`.

### `write_f128`

```sysl
write_f128(x: f128, out: *Writer, spec: string, width: int, precision: int, scratch: []u8) -> Result[unit, FormatError]
```

An `f128` under a printf float conversion, written as `format_f128` renders it: every digit the
exact value's, so `%.30Le` prints thirty digits binary128 really holds. `f128_scratch_len` bytes
are always enough.

### `write_f32`

```sysl
write_f32(x: f32, out: *Writer, spec: string, width: int, precision: int, scratch: []u8) -> Result[unit, FormatError]
```

An `f32` under a printf float conversion, written as `write_real` writes the same value -- which is
what C prints for a `float`, promoted to `double` -- and taken apart at its own width.

### `write_fixed`

```sysl
write_fixed(raw: i128, bits: int, signed: bool, frac: int, out: *Writer, spec: string, width: int, precision: int, scratch: []u8) -> Result[unit, FormatError]
```

A fixed-point value under a printf float conversion, written as `format_fixed` renders it and as
`write_real` writes a float -- into `out`, with nothing allocated, so a `@no_alloc` program prints
a `q31` exactly. `raw`, `bits`, `signed` and `frac` say what the value is (`i128(x.bits)`, the
format's width, whether it is an `iWqF`, and its `F`); the rest is `write_real`'s.
`float_scratch_len` bytes of scratch are always enough: the longest exact value of a 128-bit format
runs to under two hundred digits.

### `write_fixed_wide`

```sysl
write_fixed_wide[T: Integer](raw: T, frac: int, out: *Writer, spec: string, width: int, precision: int, scratch: []u8) -> Result[unit, FormatError]
```

`format_fixed_wide` written to `out`, allocating nothing, as `write_fixed` is for the narrow
formats. `fixed_scratch_len(bits)` bytes of scratch are always enough, and a shorter one is refused
with `Short` naming a length that would do, before anything is written.

### `write_real`

```sysl
write_real(x: real, out: *Writer, spec: string, width: int, precision: int, scratch: []u8) -> Result[unit, FormatError]
```

`x` under a printf float conversion, written to `out` -- what `format_real` answers, without the
string, so a program that may not allocate, a C library's `printf` among them, prints a float
exactly. It allocates nothing: the digits are worked out in `scratch`, which the caller lends.

`spec` is the whole specifier, `%` to conversion letter: C's flags (`-`, `+`, space, `#`, `0`), a
width, a precision, an `l` or `L` that is read and ignored (so a C format's own specifier can be
handed over as it stands), and one of `f F e E g G a A`. **The `width` and `precision` arguments
are read only where `spec` carries a `*` for them** -- `%*f` reads `width`, `%.*f` reads
`precision` -- and are ignored otherwise, a width or precision written into `spec` itself being the
one that counts; a negative `*` width is C's `-` flag and that width, and a negative `*` precision is none. No precision at all is
six digits for `%e`, `%f` and `%g` and every digit the value holds for `%a`. The digits are the exact
value's, rounded half to even. `%a` writes a first digit of 1 for every value but zero, a subnormal
included (`0x1p-1074`), as Apple's and musl's C libraries do. An infinity or a NaN is a word, signed
as C signs it (`-nan` for a NaN whose sign bit is set, `+nan` and ` nan` under `+` and a space) and
padded with spaces even under `0`.

`float_scratch_len` bytes of scratch are always enough, and most requests need far fewer: `%.17g`
needs 17, and `%a`, an infinity, a NaN and a zero need none. A scratch that is too short is refused
with `Short` before anything is written, naming a length that would do; a specifier that is not a
float conversion is `BadSpec`.

## Types

### `FormatError`

```sysl
enum FormatError
    BadSpec
    Short(needed: usize)
```

Why `write_real`, `write_f32` or `write_f128` wrote nothing.

### `RealDigits`

```sysl
struct RealDigits
    kind: RealKind
    negative: bool
    digits: string
    exponent: int
```

A float's shortest round-trip decimal, taken apart so a program can lay it out in its own style.

For a `Finite` value the number is `-`(if `negative`) then `d.ddd` times ten to the `exponent`,
where `digits` is `"dddd"` -- ASCII digits, the first non-zero, no trailing zero: `1e20` is digits
`"1"`, exponent 20; `123.456` is `"123456"`, exponent 2; `0.001` is `"1"`, exponent -3. A `Zero`
has digits `"0"` and exponent 0, and `Infinite` and `NaN` have no digits. `negative` is the sign
bit, so it is set for `-0` and `-inf`.

### `RealKind`

```sysl
enum RealKind
    Finite
    Zero
    Infinite
    NaN
```

What kind of number a `RealDigits` describes.

## Implementations

### Display for FormatError

```sysl
impl Display for FormatError
```

The rendering, so that a refusal can be printed without matching on it.
