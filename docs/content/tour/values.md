---
title: Values and bindings
summary: Three ways to name a value, a scalar family with no surprises in it, and why nothing widens on its own.
weight: 10
---

## Naming a value

There are three keywords, and they differ in what may happen to the value afterwards:

```sysl
var count = 3            // storage that may change
val limit: int = 10      // storage that may not
const step: int = 2      // not storage at all — a value known while compiling

count = count + step
print("count:", count, "of", limit)
```

```output
count: 5 of 10
```

`var` and `val` are the pair most languages have. `const` is different: it is not a variable that
cannot change, but a value the compiler substitutes wherever the name appears, so there is nothing to
read at run time. A `const` must give a value, and its type, where it is left off, is the value's
own: `const steps = 3` is an `int`.

`limit` states its type because these statements are at **module level**, which is the first place
the top of a file behaves differently from the inside of a function. A module-level `val` is part of
the module's public surface, visible to other files, so its type is written down rather than inferred
from the expression on the right. The same `val limit = 10` inside a function infers its type, because
a local is visible only there.

A module-level `val` **may** hold a value the program builds at run time: a reference, a slice, a
string assembled while running. Its storage lasts for the whole run, as a static does. The value
decides only *when* that storage is filled. Numbers, characters, booleans, string literals and tables
of them are in place before the program starts; anything else is built by a prologue that runs before
the first statement.

`print` takes any number of arguments, renders each one, and separates them with a space. It comes
from `sysl`, the standard module, which is the one module a file may use without importing it.

Integers form an open family: `i8`, `i16`, `i32`, `i64`, the unsigned `u8` … `u64`, and `isize` and
`usize` for the pointer-width pair. The common widths also have short names, and ordinary code uses
those:

| friendly | is |
|---|---|
| `int` | `i32` |
| `byte` | `u8` |
| `short`, `long` | `i16`, `i64` |
| `uint`, `ushort`, `ulong` | `u32`, `u16`, `u64` |
| `real` | `f64` |

Floats are a closed set, `f32` and `f64`, because those are the formats IEEE 754 defines. `real` is
`f64`, and it is the width floating-point arithmetic uses unless a program says otherwise.

The remaining scalars are `bool`, `char` and `string`.

## Every width is its own type

This is the part most likely to surprise a C programmer. A width is a *type*, not a hint, and no
value changes width on its own:

```sysl
var small: byte = 200
var wide: u64 = 18446744073709551615

print("byte:", small + 100, small >> 3)
print("u64 max:", wide)
```

```output
byte: 44 25
u64 max: 18446744073709551615
```

`small + 100` is `byte` arithmetic, so it wraps at 256 and gives 44. It is not promoted to `int`
because the result would not fit; the type is `byte`, and so is the arithmetic. An unsuffixed literal
such as `100` takes its type from the surrounding expression, which is why the line needs no suffixes.

### Any bit width is a type

`i8` through `i64` are the widths with familiar names, not the only widths. `iN` and `uN` are an
**open family parameterized by bit width**, so `u12`, `i5` and `u256` are all types you may write.
Each has its own arithmetic: a `u12` wraps at 4096 because that is the range of twelve bits.

```sysl
struct Pixel
    red:   u5
    green: u6
    blue:  u5

var p = Pixel(31u5, 40u6, 17u5)

var counter: u12 = 4000

print("wraps at 4096:", counter + 100u12)
print("and divides:", counter / 7u12)
print("packed:", p.red, p.green, p.blue)
```

```output
wraps at 4096: 4
and divides: 571
packed: 31 40 17
```

A 16-bit RGB565 pixel is a 5-bit field, a 6-bit field and another 5-bit field. Declaring the fields
with those widths gives each one the correct wrapping and range checks, with no hand-written masking
of a `u16`.

Storage rounds up to whole bytes and to an alignment the machine supports, so a `u12` occupies two
bytes. A narrow width gives correct arithmetic, not tighter packing. The
[reference](/reference/types/) gives the maximum width and the costs at extreme widths.

Converting to a wider type is implicit, because no value can be lost: every `byte` is also a valid
`int`. Converting to a narrower type is never implicit:

```sysl
var small: byte = 200
var wide: int = small
var count: int = 300
var back: byte = count
```

```error
cannot initialize 'back': declared byte but the value is int
```

A conversion that can lose or change a value is written explicitly, with call syntax:

```sysl
var small: byte = 200
var wide: int = small

print("widened:", wide, "narrowed:", byte(300))
print("truncated:", int(3.9))
print("divided:", f32(7) / f32(2))
print("code point:", u32('A'))
```

```output
widened: 200 narrowed: 44
truncated: 3
divided: 3.5
code point: 65
```

`int(3.9)` truncates rather than rounds. `f32(7) / f32(2)` is floating-point division because both
operands are floats; `7 / 2` would be integer division and give 3. The types decide which operation
runs, and the types are visible in the code.

## Characters are not small integers

A `char` is one Unicode scalar value. It can be compared and printed, but it does not support
arithmetic:

```sysl
var letter = 'é'

print("char:", letter, 'a' <= letter, char(9731))
```

```output
char: é true ☃
```

Characters are ordered, so `'a' <= letter` is valid. Adding `1` is not, because "the next scalar
value" is rarely what code that writes `letter + 1` intends. When you need the code point,
`u32(letter)` converts to it and `char(9731)` converts back.

---

Next: [control flow](/tour/control-flow/), where `if`, `match` and the loops are all expressions
that produce a value.
