---
title: The fmt module
summary: "`sysl.fmt` — the renderers behind an f-string's specifiers: integers in hexadecimal, binary and octal as plain functions, floats correctly rounded under `%f`, `%e` and `%g`, and the shortest reading of a float — all in sysl, so they work on a target with no C library."
weight: 21
---

**Every declaration in `sysl.fmt`, with its signature:** [the generated API page](/api/sysl-fmt/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`print` writes an integer in decimal. A register dump, a memory address or a bit mask is read in
another base, and there are two ways to get one.

**In an f-string, a specifier does it.** `%x`, `%X`, `%o` and `%b` convert, and `0`, a width and `#`
shape the field, as in C:

```sysl
val v: u32 = 0xcafe

print(f"$v%x")
print(f"$v%08X")
print(f"$v%#x")
print(f"$v%b")
```

```output
cafe
0000CAFE
0xcafe
1100101011111110
```

**As a function, `sysl.fmt` does it** — for code that wants the string without an `f"…"`, or that
chooses the base or the width at run time. `hex`, `binary` and `octal` take any integer, and `width` is
a minimum count of digits, padded with zeros on the left:

```sysl
import sysl.fmt.hex
import sysl.fmt.binary
import sysl.fmt.octal

val w: u32 = 0xdeadbeef

print(hex(w))
print(hex(u64(0xab), 16))
print(hex(u16(0xbeef), 6, true))
print(binary(u8(5), 8))
print(octal(u16(511)))
```

```output
deadbeef
00000000000000ab
00BEEF
00000101
777
```

| | |
|---|---|
| `hex(v, width = 0, upper = false)` | base sixteen, lower case unless `upper`; no `0x` prefix |
| `binary(v, width = 0)` | base two |
| `octal(v, width = 0)` | base eight |

**A negative value is its two's complement at its own width**, exactly as `%x` renders it: the width
is the type's, not the value's, so the same bits give the same digits whatever the sign.

```sysl
import sysl.fmt.hex

print(hex(i8(-1)))
print(hex(i16(-2)))
print(hex(-1))
print(hex(i64(-1)))
```

```output
ff
fffe
ffffffff
ffffffffffffffff
```

**Every width is exact, `u128` and `i128` included**, in an f-string hole and in these functions
alike, because both go through `format_int`. The powers of two are done by shift and mask, and decimal
divides in 32-bit limbs, so no 128-bit division is called:

```sysl
import sysl.fmt.hex

val m: u128 = 340282366920938463463374607431768211455
val lo: i128 = -170141183460469231731687303715884105727 - 1

print(f"$m%x")
print(f"$m%d")
print(f"$lo%d")
print(hex(lo))
```

```output
ffffffffffffffffffffffffffffffff
340282366920938463463374607431768211455
-170141183460469231731687303715884105728
80000000000000000000000000000000
```

**A width never truncates** — `hex(u32(0x12345), 2)` is `12345` — and a width of zero or less asks for
no padding.

## Floats

A float under `%f`, `%e` or `%g` — and in a plain hole, and through `str`, which are `%g` — is rendered
by `format_real`, or `format_f32` for an `f32`, flag for flag as C's `printf` renders a `double`:

```sysl
val x = 3.14159265358979

print(f"[${x}%.3f] [${x}%10.2e] [${x}%-8.3g] [${x}%+08.2f] [${1e-5}%g] [${2.5e6}%G]")
print(f"[${100.0}%#g] [${2.0}%.0e] [${0.0}%e] [${-0.0}%g]")
```

```output
[3.142] [  3.14e+00] [3.14    ] [+0003.14] [1e-05] [2.5E+06]
[100.000] [2e+00] [0.000000e+00] [-0]
```

**The digits are the exact value's, correctly rounded at any precision.** A finite float is a
fraction with a power of two below it, so its decimal expansion is finite, and every digit of it is
there for the asking. A digit is rounded half to even, which is what decides a value that lies
exactly half way — `0.125` to two places and `2.5` to none:

```sysl
print(f"${0.125}%.2f ${0.375}%.2f ${2.5}%.0f ${3.5}%.0f")
print(f"${0.1}%.25f")
print(f"${5e-324}%.3e")
```

```output
0.12 0.38 2 4
0.1000000000000000055511151
4.941e-324
```

An infinity is `inf` and a NaN is `nan`, upper case under `%E`, `%F` or `%G`; neither is padded with
zeros, and a NaN carries no sign. The width and the precision may be holes of their own, as an
integer's may (`${x}%.${n}f`).

**One host differs, and it is the host that is wrong.** Where `%g` rounds a tie *down* to the even
digit, macOS's C library leaves the trailing zeros C requires dropped — its `printf("%g", 1000005.0)`
is `1.00000e+06` — and `format_real` writes `1e+06`, as glibc does.

### The shortest reading

`%g`'s six digits lose a value, and seventeen print noise after one that needed fewer. What a
serializer wants is **the fewest digits that read back as the same float**, and `shortest` gives
it — among those, the one nearest the value, and a tie to the even digit, which is the reading
JavaScript and Python print. The algorithm is Ryu. An `f32` is read at its own width, so it is
usually shorter than the same value widened:

```sysl
import sysl.fmt.shortest

print(shortest(0.1), shortest(1.0 / 3.0), shortest(1e23), shortest(5e-324))
print(shortest(f32(0.1)), shortest(real(f32(0.1))))
```

```output
0.1 0.3333333333333333 1e+23 5e-324
0.1 0.10000000149011612
```

The spelling is `%g`'s: an exponent below `1e-4`, and from `1e15` up where the digits do not already
reach that far, at least two
exponent digits, and never a trailing zero or point. Reading any of them back is
[`sysl.text.parse_real`](/library/text/#reading-a-value-back-the-parsers), which is exact too.

| | |
|---|---|
| `format_real(x, spec, width, precision)` | a `real` under a printf specifier; `width` and `precision` are what a `*` reads |
| `format_f32(x, spec, width, precision)` | the same for an `f32`, taken apart at its own width |
| `shortest(x)` | the fewest digits that read back as `x`, a `real` or an `f32` |

## They are the f-string's renderer, so a bare target has them too

An integer specifier is rendered by `sysl.fmt.format_int`, a string one by `format_str` and a float
one by `format_real`, all written in sysl, and `hex`, `binary` and `octal` only compose a specifier
and call one. Nothing here asks for `snprintf`, so a freestanding image that reaches `hex` links with
no C library symbol, as one that writes `f"$v%016x"`, `f"${s}%-8s"` or `f"${x}%.3f"` does — the float
renderer is integer arithmetic throughout, so it asks a machine with no double-precision unit for
nothing it cannot do either. No specifier is refused for want of a C library.
