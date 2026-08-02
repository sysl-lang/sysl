---
title: sysl.sys
summary: The platform seam — every declaration in the standard library that is not sysl, in one file you cannot call.
weight: 100
---

`sysl.sys` is the one module in the standard library with **no surface at all**. Every name in it is
`private[sysl]`, so the whole of it is closed to a program:

```sysl
import sysl.sys.sysl_putchar

print(sysl_putchar(104))
```

```error
'sysl.sys.sysl_putchar' is private to module 'sysl'
```

That is not a page with nothing to say, though — it is the page about a **seam**. Every declaration
in the standard library that is not sysl lives here, which means the surface a host has to supply
can be read off two files, and the question "what would a freestanding target have to provide?" has
a place to be answered.

## Nothing in it is reachable, by any route

A fully-qualified path is refused for the same reason a bare one is. Being able to *see* a name is
not being able to use it:

```sysl
import sysl.sys.*

print(sysl.sys.sysl_sqrt(2.0))
```

```error
'sysl.sys.sysl_sqrt' is private to module 'sysl'
```

And the glob import above is worth looking at twice, because it is **not itself refused** — it
succeeds, and brings in nothing:

```sysl
import sysl.sys.*

print(sysl_putchar(104))
```

```error
undefined function 'sysl_putchar'
```

`private[sysl]` means *the module `sysl` and its whole subtree*, so every other part of the standard
library may reach these and no program may. Putting a name here is therefore a decision about the
library's **own workings** rather than an addition to its surface — which is exactly what a reader
wants a module like this to mean.

## The `sysl_` prefix, and what it buys you

Every extern here is bound to a `sysl_`-prefixed sysl name while going on resolving to the ordinary
C symbol:

```sysl
module sysl.sys
link "m"

private[sysl] extern "putchar"   sysl_putchar(c: int) -> int
private[sysl] extern "llvm.sqrt" sysl_sqrt(x: f64) -> f64
private[sysl] extern "cbrt"      sysl_cbrt(x: f64) -> f64
```

**An extern's *symbol* is not qualified and cannot be.** It names something the linker already has,
and the linker knows nothing about sysl's modules. So the sysl-side name is what had to move, and
moving it is what keeps `putchar`, `sqrt`, `pow`, `floor`, `read`, `memchr` and `strtod` free for a
program to declare itself:

```sysl
import sysl.text.cstring

extern "putchar" putchar(c: int) -> int
extern "sqrt" sqrt(x: f64) -> f64
extern "strtod" strtod(p: *u8, end: **u8) -> real

putchar(104)
putchar(105)
putchar(10)

print(sqrt(2.0))

var s = cstring("3.5 and the rest")
var endp: *u8 = null
var v = strtod(s.ptr, &endp)

print(v)
```

```output
hi
1.41421
3.5
```

Those three names are the program's, bound to the same three symbols the library is bound to, and
nothing collides. Spending seven ordinary words out of every program's namespace would have bought
nothing, and `guide/fft` had already declared its own `sqrt` before there was a module to ask.

## The two halves

**`platform.sysl`** — what the library asks of the C library it is hosted on, and the whole of it:

| symbol | what the library uses it for |
|---|---|
| `putchar` | every byte `print` and `prints` emit |
| `snprintf` | formatting a number into text |
| `read` | `sysl.io`'s `FdReader`, and `stdin()` under it |
| `memchr` | `find_byte`, and the line splitting built on it |
| `strtod` | `parse_real` |

**`math.sysl`** — what `sysl.math` asks of the machine and of the C mathematics library. Roots,
exponentials and logarithms; powers and `hypot`; the circular and hyperbolic trigonometry; the four
roundings; `fmod`; and the two sign-bit operations.

## Two entry points per operation

C names the float widths apart and sysl has no overloading to hide it behind, so `sqrt` takes a
`double` and `sqrtf` a `float`, and both are declared. **`sysl.math` is where that stops being
visible**, and it stops there by dispatching on the receiver's type rather than by a caller
choosing which one they meant.

The intrinsics are spelled the same twice for a different reason: one base name, two widths, and the
compiler derives `.f64` or `.f32` from the signature.

## `llvm.` or libm — which is which, and why

Two kinds of declaration, told apart by the **namespace the link name is in**. A name beginning
`llvm.` is an intrinsic: the back end recognises it and emits the machine's own instruction, and
there is no symbol for a linker to find. Everything else is libm's, resolved at the link.

**The split is not stylistic — it is which operations the hardware has.** A square root, an absolute
value, a sign transfer and the four roundings are instructions on every machine sysl targets. A sine
is not, on any of them, so asking LLVM for `llvm.sin` would produce a call to the same libm function
this file already names, one indirection later.

| on the machine | in libm |
|---|---|
| `sqrt`, `fabs`, `copysign` | `cbrt`, `exp`, `exp2`, `log`, `log2`, `log10` |
| `floor`, `ceil`, `round`, `trunc` | `pow`, `hypot`, `fmod` |
| | `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `atan2` |
| | `sinh`, `cosh`, `tanh` |

**What that buys beyond speed is a program that needs no libc.** A freestanding target has no libm
to link against, so the whole module used to be hosted-only. The operations in the left column now
work on a bare machine, and only the transcendentals in the right one do not.

### `round` goes away from zero

`llvm.round` is C's `round`, and it is **not** `llvm.roundeven`'s rule. The two differ at exactly
the inputs a rounding is chosen for, so the one adopted here is pinned by a test rather than assumed
from the name:

```sysl
import sysl.math.Float

print(2.5.round(), 3.5.round(), (-2.5).round())
print(2.5.trunc(), (-2.5).trunc(), 2.5.floor(), (-2.5).floor())
```

```output
3 4 -3
2 -2 2 -3
```

Banker's rounding would have answered `2 4 -2` on the first line. All four roundings answer in the
float's own type, because a `floor` that returned an integer would be undefined for the operands
that do not fit one — and the caller who wants an integer is the one who knows the range.

## Where the line is drawn

The transcendentals are here and most of the comparisons are not, and **the line is what the machine
can do that sysl's operators cannot**. A range-reduced sine is an algorithm. So, less obviously, are
the two sign operations — because they read and write the **sign bit** directly, and a sign bit is
something no comparison can see.

An absolute value written the obvious way is wrong, and here is the proof:

```sysl
import sysl.math.Float

var z = -0.0
var hand = if z < 0.0 then -z else z

print(1.0 / z, 1.0 / z.abs(), 1.0 / hand)
```

```output
-inf inf -inf
```

`-0.0 < 0.0` is **false** — no comparison distinguishes a negative zero from a positive one — so the
negation never runs and the magnitude comes back negative. `abs` clears the sign bit and gets it
right. The only way to see the difference is to divide into it, which is what the program does.

`copysign` is on the same side of the line for the same reason:

```sysl
import sysl.math.Float

print(3.0.copysign(-1.0), (-3.0).copysign(1.0), 0.0.copysign(-1.0) == 0.0)
print(1.0 / 0.0.copysign(-1.0))
```

```output
-3 3 true
-inf
```

Read those two lines together. The third value on the first line says the result **compares equal**
to positive zero; the second line says it is nevertheless a negative zero. That gap is the whole
argument: a sysl body written out of comparisons cannot produce this value or detect it, so the
operation has to be the machine's.

Everything that *is* a comparison and nothing more — `signum`, `is_finite`, `is_nan`, the
interpolation — [`sysl.math`](/library/math/) writes in sysl and calls nothing.

## `link "m"`

The module carries a `link "m"` directive, and the reason it names the **library** rather than the
flag is that where libm lives is the target's answer: a file of its own on ELF, part of `libSystem`
on Darwin, absent from a freestanding machine.

The driver used to carry this instead, and every ELF link was handed `-lm` whether or not the
program computed anything — because the compiler had no way to be told and this file had no way to
say. Now the requirement travels with the declarations that create it.

## Why it is a leaf

`sysl.sys` needs nothing. It imports no module, calls no sysl function, and reports no error — and
that is a property worth protecting rather than an accident of how small it is.

The clearest illustration is a module that is **not** here. `args_of` converts C's `argc`/`argv`
into a `[]string`, which sounds exactly like platform business — but it calls `print` and `exit`,
which are `sysl`'s, and `sysl` reaches `sysl.sys` for its printing. Putting both in one module would
make the two depend on each other, which the [acyclic module graph](/reference/modules/) refuses. So
it lives in [`sysl.args`](/library/args/) instead, and what is left here is a leaf.

**A declaration that reports its own failure in words is not a leaf**, because reporting is itself a
dependency. That is the shape to look for when deciding whether something belongs at the seam.
