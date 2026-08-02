---
title: Values and bindings
summary: Three ways to name a value, a scalar family with no surprises in it, and why nothing widens on its own.
weight: 10
---

## Naming a value

Three keywords, differing in what may happen to the thing afterwards:

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

`var` and `val` are the pair most languages have. `const` is the different one: it is not a variable
that happens to be fixed, it is a value the compiler substitutes wherever the name appears, so there
is nothing at run time to read. That is why it insists on its type being written and on having a
value — a `const` with either missing is refused rather than inferred, because a name with no
storage and no value is nothing at all.

`limit` writes its type for a reason worth knowing early, because it is the first place the file's
top level behaves differently from the inside of a function. These statements are at **module
level**, and a module-level `val` is part of the module's surface — something another file can see —
so its type is stated rather than inferred from whatever happens to be on the right today. The same
`val limit = 10` inside a function infers happily, because a local is nobody else's business.

There is a second thing a module-level `val` will not do, and it is worth knowing before you go
looking for it: it may not hold a value the program had to **build** — a reference, a slice, or a
string put together while running. Storage that exists for the whole run is never let go of, so a
count in one would be a count with nowhere to write the release. Numbers, characters, booleans,
string *literals*, and tables of any of them are what a module-level `val` is for; text a program
builds or reads is a `var` at the top of the file, which is a local of the entry point.

`print` takes any number of arguments, renders each one, and puts a space between them. It comes
from the prelude, which is the part of the standard library a program gets without asking.

## The scalar types

Integers are an open family: `i8`, `i16`, `i32`, `i64` and the unsigned `u8` … `u64`, with `isize`
and `usize` for the pointer-width pair. The common ones also have friendly names, and those are what
ordinary code uses:

| friendly | is |
|---|---|
| `int` | `i32` |
| `byte` | `u8` |
| `short`, `long` | `i16`, `i64` |
| `uint`, `ushort`, `ulong` | `u32`, `u16`, `u64` |
| `real` | `f64` |

Floats are a closed set — `f32` and `f64` — because IEEE 754 defines those and not an open family.
`real` is `f64`, and it is the width arithmetic reaches for unless a program says otherwise.

Then `bool`, `char`, and `string`.

## Every width is its own type

This is the part that catches people arriving from C. A width is a *type*, not a hint, and no value
changes width on its own:

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

`small + 100` is `byte` arithmetic, so it wraps at 256 and gives 44. It does not quietly become an
`int` because the answer would not fit — the type said `byte`, and `byte` is what the arithmetic
is. An unsuffixed literal like `100` takes the type of what is around it, which is what lets that
line be written without a suffix on every number.

Ask for a wider type and the compiler will not do it silently:

```sysl
var small: byte = 200
var wide: int = small
print(wide)
```

```error
declared int but the value is byte
```

The fix is to say so, and every conversion is written with call syntax:

```sysl
var small: byte = 200

print("widened:", int(small))
print("truncated:", int(3.9))
print("divided:", f32(7) / f32(2))
print("code point:", u32('A'))
```

```output
widened: 200
truncated: 3
divided: 3.5
code point: 65
```

`int(3.9)` truncates rather than rounds, and `f32(7) / f32(2)` is float division because both
operands are floats — writing `7 / 2` would have been integer division giving 3. Which one you get
follows from the types, and the types are written down.

## Characters are not small integers

A `char` is one Unicode scalar value. It compares and prints, and it does not do arithmetic:

```sysl
var letter = 'é'

print("char:", letter, 'a' <= letter, char(9731))
```

```output
char: é true ☃
```

Ordering is defined, so `'a' <= letter` answers. Adding `1` to it is not, because "the next scalar
value" is rarely what a program that wrote `letter + 1` actually meant. When the code point *is*
what you want, `u32(letter)` says so and `char(9731)` goes back the other way.

## Assignment is an expression

It yields the value assigned, which is what lets a chain work and a condition read normally:

```sysl
var a = 0
var b = 0

a = b = 7
print("both:", a, b)
```

```output
both: 7 7
```

A binding takes a comma list too, so two names can be introduced, or swapped, in one line:

```sysl
demo()
    val lo, hi = 1, 10
    var x, y = 3, 4

    x, y = y, x
    print("range:", lo, hi, "swapped:", x, y)

demo()
```

```output
range: 1 10 swapped: 4 3
```

The right-hand side is evaluated before anything is stored, so `x, y = y, x` is a swap and needs no
temporary.

This one is inside a function rather than at the top level, and it has to be. A binding that names
several things has nowhere to write a type, and a module-level binding is required to have one — so
the comma form is a local's convenience, and the compiler says exactly that if you try it at module
level.

---

Next: [control flow](/tour/control-flow/), where the same "it yields a value" idea turns out to
cover `if`, `match` and the loops as well.
