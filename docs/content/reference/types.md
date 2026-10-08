---
title: Types
summary: Every type the language has — the open integer families, the closed float set, and the aggregates built on them.
weight: 20
---

sysl's types fall into four groups: **scalars** (numbers, `bool`, `char`), the built-in **`string`**,
the **aggregates** you build (arrays, slices, structs, enums, tuples), and the **modes** that decide
where a value lives (`T`, `&T`, `*T`). This page covers the first three. The modes have a
[page of their own](/reference/memory/), because they are about storage rather than shape.

There is no implicit conversion anywhere in the language. Every width change, signedness change, and
float/integer crossing is written as a cast, and a cast that could lose information is written and
seen.

## Integers are an open family

`iN` and `uN` are not a fixed set of four sizes. They are an **open family parameterized by an
arbitrary bit width**: `i5`, `u3`, `u12`, `i128` are all types you may write, and none of them needed
the compiler to have heard of them. LLVM supports integers of any width natively, so this is a
capability of the target rather than something sysl emulates.

```sysl
var small: u3 = 5
var odd: i5 = -7
var wide: u12 = 4000

print(small, odd, wide)
```

```output
5 -7 4000
```

That openness is the reason the `iN` spelling exists at all. If the integers were only four sizes,
carrying both `iN` names *and* C-style names would be two spellings for every type and no benefit.
Instead there are two layers, each earning its place: `iN`/`uN` is the general mechanism, and a short
list of **aliases** covers the common widths.

| alias | is | | alias | is |
|---|---|---|---|---|
| `byte` | `u8` | | `short` | `i16` |
| `ushort` | `u16` | | `int` | `i32` |
| `uint` | `u32` | | `long` | `i64` |
| `ulong` | `u64` | | `real` | `f64` |

C's "how wide is `long`?" problem — the usual reason to distrust names like these — **does not apply**,
because each width is pinned *by definition*. `long` is exactly `i64`, on every target, always.

**But that is an anti-ambiguity guarantee, not an ABI promise**, and the difference matters at a
foreign boundary. On a 64-bit host every alias happens to match its C namesake, which is what makes
them safe in `extern` code there. On a 32-bit target C's `long` is 32 bits while sysl's is still
exactly 64 — so **precise FFI should use the explicit-width names**, which match C's `int32_t` and
`int64_t` on every target. Note also that `i8` has no alias: there is no settled C-style name for a
signed byte worth adopting.

**An integer literal has no maximum of its own.** Its value is exact at any size and has to fit the
type it takes, so a literal is as wide as the widest type you can write:

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
than widened — give it a type, with a suffix or on the declaration:

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

### Storage is not `N / 8`

An integer's alignment rounds up to the smallest width the target names, and its stride rounds up to
that alignment. A `u12` occupies **two** bytes aligned to two; a `u96` occupies sixteen aligned to
sixteen. This is LLVM's rule, and it matters anywhere a width is stated rather than derived.

### Where the width stops

Up to 64 bits everything is a machine instruction. Past that the arithmetic is still native — the
back end expands a wide multiply or divide inline, with no runtime routine behind it — and decimal
rendering becomes the language's own job, since C's `printf` has no length modifier that wide. So a
value past 64 bits renders through a digit loop and is refused a `%d`.

The ceiling is LLVM's own **2²³ − 1**, and a wider width is a diagnostic naming it. That is a
statement about the toolchain, not about the design.

```sysl
var wide: u256 = 1
var i = 0

while i < 200 do
    wide = wide * 2u256
    i = i + 1

print(wide)
```

```output
1606938044258990275541962092341162602522202993782792835301376
```

Two costs are worth knowing before reaching for an extreme width, because neither is guarded against:
the digit buffer is stack space proportional to the width, so a width near the ceiling overflows the
frame, and sizing it evaluates 2^N at compile time. Nothing reaches either without asking for it by
name.

### The narrowest widths

`N ≥ 1` has no exception at its low end. `u1` is a single binary digit. `i1` holds `{-1, 0}` — one bit
of two's complement, where the only bit *is* the sign bit. It is degenerate but entirely consistent,
and nothing special-cases it: `abs` at `i1` answers `-1` because `-1` is that width's most negative
value, exactly as `abs` at any width answers its own minimum, and `signum` never returns `+1` because
no value of the type is positive. If you want a bit, write `u1` or `bool`.

### Fixed point is the integers read in units

`iWqF` and `uWqF` (`q7`, `q15`, `q31`) are a W-bit integer counting units of 2^-F — see
[fixed-point numbers](/reference/fixed-point/). They are the one scalar family whose arithmetic
saturates rather than wraps, unless a type is declared `wrapping`: a fixed-point value models a real number in a range, as a float does.

## Floating point is a closed set

`fN` is **not** open — only real formats exist, because there is no meaningful `f37`. The set is
`f16`, `bf16`, `f32` and `f64`, and `f128` is reserved but not lowered: writing it is a diagnostic
saying so rather than a type you get.

Only the default width gets an alias, and it is deliberately named `real` rather than `float`, because
`float` means 32-bit to every C, C++, Rust and Java programmer and `real` promises nothing it does not
keep.

```sysl
var a: f32 = 1.5
var b: real = 2.25

print(a + 1.0, b / 2.0)
```

```output
2.5 1.125
```

A float renders in the shortest form that round-trips, so a value with no fractional part prints
without one.

### The two values no literal spells

An infinity and a NaN have no decimal to write down, so each float type names them: **`real.infinity`
and `real.nan`**, and the same on every width — `f32.infinity`, `f16.nan`, `bf16.infinity`. They are
read **without parentheses**, because they are constants rather than functions: a `const` initializer
takes one, and so does a `within` bound.

`infinity` and `nan` written on their own are the `real` ones. Negative infinity is a minus in front,
as for any other value:

```sysl
const top: real = infinity
val low: f32 = -f32.infinity

print(top > 1.0e308, low < f32(-3.0e38), nan != nan, -infinity < 0.0)
```

```output
true true true true
```

A NaN compares false with everything, itself included, which is what the third answer shows — and why
`is_nan()` is how one is looked for.

**The bare names are the outermost there are.** Anything the program declares or imports under the
spelling answers first — a local, a parameter, a `const`, a function — so a program that already has
an `infinity` keeps it:

```sysl
f(nan: int) -> int = nan + 1

print(f(2))
```

```output
3
```

Where a declared **function** has taken the name, reading it bare is the usual refusal, and the
message says which spelling no declaration can hide:

```sysl
infinity() -> real = 2.0

val x: real = infinity

print(x)
```

```error
The built-in constant this declaration hides is spelled 'real.infinity'
```

`real.infinity()` and `real.nan()` — the `Float` trait's associated functions, which a routine generic
over the width calls as `T.infinity()` — are unchanged, as are `sysl.math`'s `infinity()` and `nan()`.

### The two sixteen-bit formats

`bf16` is the one name in the set that is not a width, and it has to be, because **width alone does
not identify a format at sixteen bits**. Both spend one bit on the sign and divide the other fifteen
differently:

| | exponent | significand | largest finite | decimal digits |
|---|---|---|---|---|
| `f16` | 5 bits | 10 bits | 65504 | ~3 |
| `bf16` | 8 bits | 7 bits | 3.4 × 10³⁸ | ~2 |

So `bf16` is not a smaller `f16`, it is a **shorter `f32`** — its bits are a binary32's top sixteen,
which is where the name comes from and why it has binary32's exact range. The trade is the whole
reason both exist: `f16` is the more precise of the two and runs out of range early, while `bf16`
reaches every exponent an `f32` does and pays for it three bits of significand at a time.

Both are ordinary arithmetic types. A tenth shows the precision difference, and doubling the largest
`f16` shows the range one:

```sysl
var h: f16 = 0.1
var b: bf16 = 0.1

print(h, b)

var big: f16 = 65504.0
var wide: bf16 = 65504.0

print(big * 2.0, wide * 2.0)
```

```output
0.0999756 0.100098
inf 131072
```

Neither prints a tenth, because neither holds one — what prints is the value the format really has,
since rendering widens to `real` and that is exact at every width.

`f16` and `bf16` are also the one pair where **neither conversion is a widening or a narrowing**. It
is still written as an ordinary conversion:

```sysl
var b: bf16 = 0.1

print(f16(b))
```

```output
0.100098
```

That answer is `bf16`'s tenth rather than `f16`'s, which is the point: a conversion carries the value
the source actually holds, and here `f16` has room for it exactly — its ten bits of significand hold
`bf16`'s seven with nothing left to round. The other direction rounds, since seven bits do not hold
what ten need: `bf16(h)`, with `h` still the `f16` tenth from above, prints `0.100098` too rather than
`h`'s own `0.0999756`, because that value has no exact seven-bit reading and the nearest one is
`bf16`'s own tenth.

`sysl.math`'s [`Float`](/library/math/) trait covers both, so `sqrt`, `floor`, `pi` and the rest reach
them by the same spelling they reach `real` by. libm has no entry point at sixteen bits, so those
bodies widen to `f32`, compute, and narrow back — exact on the way in, leaving the only rounding to
the destination.

Neither format has an alias, and a literal reaches one through a suffix or through the type it is
written into: `1.5f16`, `1.5bf16`, or `var x: bf16 = 1.5`.

## `usize` and `isize`

Pointer-width integers, and they are **distinct types** from every `uN` — not aliases for `u64`. That
is required by the target scope: aliasing `usize` to `u64` would be correct only if every target were
64-bit, and a 32-bit embedded target is squarely in scope.

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

`true` and `false`, and nothing coerces to it. `if x = 0` is a **type error** rather than a subtle
bug, because `if` requires a `bool` and an assignment yields what was assigned. There is no
integer-as-condition rule to memorize.

## `char`

One **Unicode scalar value** — a codepoint up to `0x10FFFF`, excluding the surrogate range. It is its
own type, aliased to neither `u8` nor `u32`.

Keeping it distinct is what lets "a character" and "a byte" stay different ideas, which a language
with a UTF-8 `string` has to do. Conversion to `u32` is total and written `u32(c)`; conversion *from*
`u32` is partial, and has both a checked form that traps and a fallible form that returns an `Option`.

## `unit` and `never`

`unit` is the type with exactly one value — what a function that returns nothing returns. It is a real
type, so it composes: a `Fn() -> unit` needs no special case in the callable machinery.

`never` is the type with **no** values, and it is what an expression that does not finish has. A call
to `exit` has type `never`, as does a `return` or a `break` considered as an expression. Because there
are no values of it, `never` is a subtype of everything — which is what lets a `match` arm that aborts
sit beside arms that produce an `int` without the arms disagreeing.

## `string`

A validated UTF-8 string, three words wide. It is immutable, indexing it by byte position gives
bytes, iterating it gives `char`s, and `+` concatenates but will not accept a non-string operand.
[Strings](/reference/strings/) is the full account — the representation, the validity guarantee, and
every form that makes new bytes — and the operations that live in the library are under
[`sysl.text`](/library/text/).

## Fixed arrays and slices

Two sequence types where many languages have one.

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

var v: []int = a[..]
v[1] = 88

print(a[0], a[1], b[0])
```

```output
1 88 99
```

The disagreement in that program is the whole distinction: writing through `b` did not touch `a`
because `b` is a copy, and writing through `v` did because `v` is a view.

Both carry a length, so **every index is checked**. [Arrays and slices](/reference/arrays/) is the
full account — writing one down, storage sized while running, the indexing and slicing rules, and
what a view keeps alive.

## Vectors

A fourth sequence shape, and the one that is not storage at all.

| type | what it is |
|---|---|
| `<N>T` | a **vector**: N lanes of `T` in a register, whose operators work on every lane at once |

It holds the same values an `[N]T` holds, in the same order — the two type constructors differ by one
bracket pair because a vector *is* an array that computes lane-wise:

```sysl
val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
val b: <4>f32 = [10.0, 20.0, 30.0, 40.0]

print((a + b)[3], (a * 2.0)[0], (a * b).sum())
```

```output
44 2 300
```

That `+` is one instruction doing four additions, and the `2.0` broadcasts into every lane. A lane is
read by a **constant** index, which is the one subscript in the language not checked while the program
runs — a register has no address to check against. A run of an array or a slice moves into a vector
and back with `xs.load(i)` and `xs.store(i, v)`, which *are* checked, because those have addresses.

A machine with no vector unit is not a special case: the back end turns a vector into as many
registers as it needs, or into ordinary scalar operations, so `<4>f32` compiles everywhere sysl
compiles. [Vectors](/reference/vectors/) is the full account — masks and `select`, the reductions, and
writing one kernel that is compiled for more than one register width.

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

Structs may carry methods, an `invariant`, and a visibility modifier per field. A struct may also be
declared `opaque`, which withholds its layout from everyone outside its own module — a different axis
from visibility, covered under [modules](/tour/modules/).

## Tuples

A product type with no name: a fixed-length, positional group of values, written with parentheses.
Where a struct names its parts, a tuple numbers them.

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

Four rules are on display there, and they are the whole of the type.

**A part is reached by position, `t.0` and `t.1`.** It is written like a field because it *is* one —
a tuple's fields are named for their positions — so a part is a **place** like any other field, which
is what lets `q.0 = 30` and `q.1 += 1` write through one.

**The type is inferred from the parts.** `(3, 4)` is a `(int, int)`, and an annotation is needed only
where the parts alone would not settle it, exactly as anywhere else.

**A tuple takes apart with the ordinary comma binding**, `val a, b = q`, and with a
[tuple pattern](/reference/patterns/) in a `match`.

**Arity starts at two.** There is no one-tuple: `(x)` is a parenthesized expression and always will
be, since a one-element product is the element. There is no zero-tuple either — that job belongs to
`unit`.

### A nested part needs parentheses

`t.0.1` does not select part 1 of part 0, because `0.1` lexes as a floating-point number before
anything gets to decide what it meant. The tokenizer is not made to guess, so the second step is
parenthesized:

```sysl
val t = ((1, 2), 3)

print((t.0).1)
```

```output
2
```

Writing it the other way is not a puzzle to solve — the compiler recognizes the float that landed
after a `.` and names the form to write:

```sysl
val t = ((1, 2), 3)

print(t.0.1)
```

```error
'.0.1' reads as a number rather than as two tuple indices — write '(x.0).1' to select part 1 of part 0
```

### A tuple is a type, and that is what it is for

This is the whole difference between a tuple and a
[result list](/reference/declarations/#several-results). A result list is a property of a
*signature*: several values travel from callee to caller and there is nothing afterwards. A tuple is
a **type** — it can be stored in a variable, held in a field, put inside an `Option`, made the
element of a container, and named as a generic argument.

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

`-> int, int` and `-> (int, int)` look alike and mean different things, and that cost is worth
stating plainly. What blunts it is the second and third lines of that program: **the caller usually
cannot tell and does not need to**, because `val a, b = f(x)` takes either apart. The choice is the
callee's, and changing it breaks no call site that was not storing the pair. Only the tuple can be
held, which is what `held` is there to show.

So the question to ask is *does anything need to hold these together?* If no, the result list is
lighter and says so. If yes, only the tuple will do.

### What the library gives every tuple

`Eq`, `Ord`, `Hash` and `Display` are provided structurally, at **every** arity, whenever every
component has the row. Ordering is lexicographic, first component first, which is the only ordering a
positional product has a claim to.

```sysl
val pair = (1, "one")
val trip = (1, 2.5, 'x')

val o: Option[(string, int)] = Some(("k", 7))

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

None of those rows is compiler-generated. They are ordinary `impl` blocks in the standard library,
written once over a [type pack](/reference/generics/#a-parameter-may-stand-for-a-list-of-types) — a
`(..A)` matches a tuple of any arity, so four blocks cover every tuple there is rather than eight
covering pairs and triples only.

That last line is the layout rule: a tuple is laid out **as a struct with those fields in that
order**, so `(u8, u8, u8)` is three bytes and a tuple holding a `&T` retains and releases exactly as
a struct field does.

### Who may implement what

Each arity is its own type, so an `impl` for a pair says nothing about a triple. A trait of your own
may be implemented for a tuple, because the trait is local and that is the half of the
[coherence rule](/reference/traits/#any-type-may-carry-an-impl) that permits it:

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

`impl Eq for (int, string)` is the case that is refused, and no new rule is doing it: a tuple type
has no declaration and so no module of its own, and a built-in type is the library's. Both the trait
and the type are therefore somebody else's, which is exactly what coherence forbids:

```sysl
impl Eq for (int, string)
    eq(self, rhs: (int, string)) -> bool = true

print(1)
```

```error
an 'impl' may be written only in the module that declares the trait or in one that declares a type the block names, and 'sysl.Eq' is the library's while nothing in '(int, string)' is declared outside the library — so this one has no home. A trait of your own, or a type of your own in what it is written for or in the trait's arguments, gives it one
```

## Enums

Two shapes under one keyword. A **simple** enum is a set of named discriminants with an underlying
integer type; a **data** enum gives variants payloads, making it a sum type.

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

print(int(Green), area(Circle(1.0)), area(Rect(2.0, 3.0)))
```

```output
1 3.14159 6
```

`Option[T]` and `Result[T, E]` are ordinary data enums declared in the standard library, with no
compiler privileges — which is why you can write your own and have it work identically.

### A variant belongs to its enum

Two enums in one module may each name a variant `Failed`, and neither has to be renamed. **What a
bare name means is settled where it is used, by the type expected there** — an argument, an annotated
binding, a `return` and a field all supply one, so the short form is what you normally write:

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

Where two enums answer and nothing says which, that is a diagnostic rather than a quiet choice — a
construction that picked the first-declared enum would be a line whose meaning changed when somebody
added an unrelated enum above it:

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

`Shape.Circle(1)` is what that line wants. The qualified form works at a construction exactly as it
[works in a pattern](/reference/patterns/#the-bare-name-rule).

**The expected type reaches across modules too, which is what lets a program name a variant the
library also names.** `Result` and `Option` are the prelude's, so their `Ok`, `Err`, `Some` and `None`
are the four names most likely to collide with an enum you were going to write anyway — and a module
declaring its own may still return a `Result` on the next line:

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

Each `Ok` there means what the line it is on asks for. Where the expected type is the library's, the
library's variant is what a bare name reaches; where it is the module's own, the module's own is. The
qualified `Status.Ok` says so outright for a site whose expected type is a `Result` and whose meaning
is not.

This is Rust's arrangement — a variant is namespaced under its enum — without Rust's use site, where
`Link::Failed` is required everywhere unless a scope opts into `use Link::*`. A variant still may not
share a name with a constant, a `val`, a module `var` or an `extern` variable: two variants of a name
are told apart by the enum they belong to, and a variant and a constant have nothing to be told apart
*by*.

## Type aliases

`type Name = Existing` introduces a second spelling for a type, interchangeable with the first. It
creates **no** new type and no checking: an alias is for shortening a name that has grown long, not
for distinguishing two uses of the same representation.

**The base may be any type at all**, which is what makes it worth having — the names that grow long
are the compound ones:

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

Because the alias is a spelling and not a type, everything the base has is reached through it: a
value written at one name is accepted where the other is wanted, a struct is constructed and read
through the alias, and a method or a trait implementation declared on the base needs nothing said
about the alias at all.

When you want a genuinely distinct type — one the compiler will not let you confuse with its base —
that is a **constrained type**, written with `new`, and it is covered under
[contracts](/tour/contracts/).

## Function types

A callable's type is written with `Fn`:

```sysl
apply(f: &Fn(int) -> int, x: int) -> int = f(x)

print(apply(n -> n * 3, 7))
```

```output
21
```

A named function used where a callable is expected is the capture-free case of the same thing — there
is no separate "function pointer" concept to learn. A raw C function pointer, for a foreign boundary,
is spelled `*extern(A) -> R` and is covered under the [foreign interface](/reference/ffi/).

**A parameter written with a bare arrow is a type parameter, and that is the difference between the
two spellings.** `f: A -> B` is sugar for `[$F: Fn(A) -> B](f: $F)`: the closure is a *type argument*,
so the function is monomorphized and calls it directly with nothing on the heap. `f: &Fn(A) -> B` is a
counted reference to a trait object, so the closure is boxed at the call and dispatched through a
table. Both accept the same closures; what differs is what the call costs and what the declaration
becomes.

**A trait's member takes either**, and the choice has a consequence there that it does not have on a
free function: an arrow makes the member declare a type parameter, so it gets no table slot and no
object can dispatch it ([traits](/reference/traits/#object-safety)). A trait meant to be erased asks
for `&Fn(…)`; one meant to be fast asks for the arrow.

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

`message()` ran once, not twice: the first call never evaluated its argument. That is the form's
whole purpose — an argument a callee may not want should cost nothing to offer.

**Each use is an evaluation**, because each use is a call — so a body that names the parameter twice
runs the argument twice. A body wanting one evaluation binds it to a `val` first.

That holds inside a closure or a nested function in the body too: the parameter is still read by
name, and the argument is evaluated each time the closure is called rather than once when it is
made.

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

It costs nothing at runtime. `x: -> T` has the type `Fn() -> T`, so it lowers to a bounded type
parameter exactly as the ordinary bare arrow does — one specialized copy per call site, called
directly, with no allocation.

**`x: () -> T` is the neighbouring form and keeps its meaning.** Same type, different call site:
there the caller constructs the callable and the body calls it.

```sysl
twice(f: () -> int) -> int = f() + f()

print(twice(() -> 21))
```

```output
42
```
