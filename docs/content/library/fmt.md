---
title: The fmt module
summary: "`sysl.fmt` — integers in hexadecimal, binary and octal as plain functions, built on the same renderer an `f\"$v%x\"` hole uses, so they work on a target with no C library."
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

## They are the f-string's renderer, so a bare target has them too

An integer specifier is rendered by `sysl.fmt.format_int`, written in sysl, and these functions only
compose the specifier and call it. Nothing here asks for `snprintf`, so a freestanding image that
reaches `hex` links with no C library symbol, as one that writes `f"$v%016x"` does. A float specifier
is different: it still needs the C library and is refused on a target without one.
