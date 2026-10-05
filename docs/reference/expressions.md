---
title: Expressions and operators
summary: The precedence ladder, every operator's meaning, evaluation order, and the traits an operator dispatches through.
weight: 30
---

Almost everything in sysl is an expression. Assignment yields the value assigned, `++` yields a
value, and `if`, `match` and the loops all yield the branch they took. What is *not* an expression is
a short list — a declaration, a multiple assignment, and `defer` — and each is named where it comes
up rather than left to be discovered.

This page is about how expressions are built and what each operator means. Where an expression is a
control-flow form, [statements and control flow](/reference/statements/) has the details of the form
itself.

## Precedence

Loosest at the top, tightest at the bottom. The set is **closed**: there are no user-defined
operator symbols, and no facility to add one.

| Prec | Operators | Role | Associativity |
|---|---|---|---|
| 1 | `=` `+=` `-=` `*=` `/=` `%=` `&=` `\|=` `^=` `<<=` `>>=` | assignment | right |
| 2 | `\|\|` | logical or | left |
| 3 | `&&` | logical and | left |
| 4 | `is`, `is not` | pattern test | non-associative |
| 5 | `==` `!=` `<` `>` `<=` `>=` | comparison | **chained** |
| 6 | `..` `..<` | range | non-associative |
| 7 | `\|` | bitwise or | left |
| 8 | `^` | bitwise xor | left |
| 9 | `&` | bitwise and | left |
| 10 | `+` `-` | add, subtract | left |
| 11 | `*` `/` `%` `<<` `>>` | multiply, divide, remainder, shift | left |
| 12 | `-` `!` `~` `*` `&` `++` `--` | prefix unary | right |
| 13 | `[]` `.` `()` `::` `with` `?` `++` `--` | postfix | left |

`*` and `&` appear at two levels each — prefix at 12 (dereference, address-of) and binary at 11 and 9
(multiply, bitwise and). Position tells them apart, and nothing else has to.

### Two deliberate corrections to C

Every level above matches C except two, and both are cases C is now widely held to have gotten wrong.

**Bitwise binds tighter than comparison.** `x & mask == 0` means `(x & mask) == 0`, which is what it
looks like. In C it means `x & (mask == 0)`, the single most-cited precedence bug in the language.

**Shift binds like multiplication**, at level 11 alongside `* / %`, rather than looser than addition.
A shift *is* a multiply or divide by a power of two, so it belongs with them. This is Go's fix, and a
deliberate divergence from Rust and Zig toward it.

```sysl
var x = 0b1100
var mask = 0b0100

print(x & mask == 4)
print(1 + 2 << 3)
```

```output
true
17
```

Both of those need parentheses in C, Rust and Zig. The payoff in systems code is that
`base + index << shift` and `a << 8 + b` group the way they read.

## Operands

**Both operands of a binary operator have the same type.** There is no implicit promotion anywhere,
so a mixed-width expression is a diagnostic asking for the conversion rather than a silent widening.

```sysl
var a: int = 1
var b: long = 2

print(a + b)
```

```error
'+' needs matching types, got int and long
```

### A shift takes a count

**A shift is the one exception, and its right operand may be any integer type.** `a + b` combines two
values that have to agree on what they are; `x << n` asks for `x` shifted `n` places, and `n`'s width
has nothing to do with `x`'s. That is C's reading, Rust's, Java's, Go's and Scala's, and the cast a
program used to write for the compiler's benefit was noise in exactly the code — a hash, a bitset, a
decoder — where widths are already being juggled.

**The result is the shifted value's own type**, never the count's, so a byte shifted by a 64-bit
count is still a byte and wraps as one.

```sysl
var x: u32 = 1
var n: u8 = 5
var wide: u64 = 3
var b: u8 = 0x81

print(x << n, (x << n) >> n)
print(b << wide)
```

```output
32 1
8
```

The count must still be an **integer**, and the refusal names it as a count rather than as a
mismatched operand.

```sysl
var x: u8 = 1

print(x << 1.5)
```

```error
'<<' shifts by a count, and real is not an integer
```

**A count wider than the value is clamped before it is narrowed**, which is what keeps the
over-shift rule below meaning what it says: 256 places on a byte is a full shift, not — as
truncating first would have it — a shift by nothing.

```sysl
var x: u8 = 1
var n: u32 = 256

print(x << n)
```

```output
0
```

A **vector** is untouched: its count is lane-wise and is already the same register type, so the two
sides of `v << w` still have to agree.

### What has arithmetic

Arithmetic is defined on the numeric types and nowhere else. `char` has equality and ordering and no
arithmetic at all; `bool` has equality, no ordering, and no arithmetic.

Unary `-` needs a type with a sign, so it is the signed integers and the floats — negating an
unsigned value is written as the subtraction it actually is. Unary `~` is defined on every integer
type, signed or not.

Integer arithmetic **wraps** at the declared width; see [types](/reference/types/). Integer division
by zero traps.

### Shifting by the width or more

**A shift amount at or past the operand's width is defined**, and answers what shifting all the way
answers: zero for `<<` and for an unsigned `>>`, and the **sign** for a signed `>>`, since an
arithmetic right shift fills from the top. That is Go's rule and Swift's.

The width is the **operand's own** rather than the machine's, so a `u8` reaches this case at 8 —
where the bare instruction masks the amount to five or six bits and would shift by something else
entirely.

```sysl
var u: usize = 11
var neg: int = -8
var pos: int = 8
var b: u8 = 0b1011

print(u >> 64, u << 64, u >> 65)
print(neg >> 64, pos >> 64)
print(b >> 8, b << 8, b >> 200)
```

```output
0 0 0
-1 0
0 0 0
```

It follows from the decision that makes integer arithmetic wrap rather than trap: plain arithmetic is
total, and `within` is where checking is opted into, so a plain over-width shift has to be *defined*
— trapping would put a trap on a type whose whole contract is that it has none. **A `within`-typed
left shift is the exception and does trap**, on its own terms and not the shift operator's; see
[errors](/reference/errors/#the-operations-are-the-base-s-their-overflow-is-not).

**Masking** the amount — C's rule, Java's, and what the instruction does unaided — is what this is
not. It makes `x >> 64` be `x`, which is never what anybody means.

### Equality reaches further than ordering

`==` and `!=` are defined wherever `<` is, and additionally on `bool` and on the two pointer-shaped
modes `*T` and `&T`, which compare by address. **Ordering on an address is not defined** — a bare
address has no meaningful one.

### The one arithmetic two pointers have is `-`

`p - q`, between two `*T`s of the same pointee, is an `isize` counting the **elements** between them
— C's `ptrdiff_t`, and the inverse of `&p[n]`. It is the only operator whose result type is neither
operand's.

Nothing else is defined: `p + q` names no address, `p - n` is what `&p[n]` is for, and a counted
`&T` has no arithmetic at all.

## Comparison chains

`a < b < c` is **one comparison node**, not two comparisons and a `bool`. It means `a < b && b < c`,
short-circuiting — and a middle operand is **evaluated once** and compared twice.

```sysl
bump(n: int) -> int
    print("evaluated", n)
    n

print(0 < bump(5) < 10)
print(9 < bump(5) < bump(6))
```

```output
evaluated 5
true
evaluated 5
false
```

The first line calls `bump` **once** although its value is compared twice. The second stops at the
first comparison that fails, so the `bump(6)` on its right never runs at all.

### A vector comparison is not a chain

Comparing two [vectors](/reference/vectors/) yields a mask rather than a `bool`, and a chain of them
is **refused**. The reason is the short-circuiting above: `a < b < c` joins its links with `&&`, and
there is no such thing as short-circuiting one lane of a register and not another. Reading the chain
as a lane-wise `&` would give the same spelling a different meaning, so the reader is asked to write
the `&` and see it.

```sysl
val a: <4>int = [1, 2, 3, 4]

f() -> unit
    val m = 1 < a < 4
```

```error
compare two vectors at a time and combine the masks with '&'
```

### Floats compare by IEEE 754, `NaN` and all

A `NaN` is equal to nothing, itself included. So `==`, `<`, `>`, `<=` and `>=` are **all false** at
one — and `!=` is **true**, because IEEE makes it the negation of `==` rather than a sixth ordered
comparison. Exactly one of the six answers true.

```sysl
var zero = 0.0
var nan = zero / zero

print(nan == nan, nan != nan)
print(nan < 1.0, nan >= 1.0)
```

```output
false true
false false
```

This is also why a float is not hashable: a table assumes a reflexive equality, and one `NaN` breaks
it.

## Logical operators

`&&` and `||` take `bool` and short-circuit; `!` is prefix on a `bool`. Nothing coerces to `bool`, so
there is no integer-as-condition rule and no `if (p)` idiom — write `p != null`.

## Ranges

`a..b` is inclusive, `a..<b` is half-open. Either end may be omitted: `a..`, `..b`, `..<b`, and a
bare `..` meaning the whole thing.

Ranges are **non-associative** and sit below arithmetic and above comparison, which is Swift's
placement — so `0..<n + 1` is `0..<(n + 1)` and needs no parentheses.

```sysl
var lo = 1

for i in lo..lo + 2
    print(i)
```

```output
1
2
3
```

The spelling deviates from Swift's `...` toward Kotlin's `..` / `..<`, because the two forms are
visually parallel and read as "through" and "up to, less than". It lexes unambiguously: a float
literal needs digits after the `.`, so `1..2` is three tokens and not two.

**`..=` is not a spelling sysl has**, and is refused by name wherever a range may be written — it is
Rust's inclusive range, and inclusive here is the bare `..`:

```sysl
val a = 1

for i in a..=3
    print(i)
```

```error
'..=' is not a range — inclusive is 'a..b' and exclusive is 'a..<b'
```

**A range with both ends written is a form in four places and a value everywhere else.** In a `for`
header, a slice index, a `match` pattern and a quantifier the compiler reads the two bounds directly
and no range object exists in what it emits — that is what keeps the most ordinary loop in the
language a counter and a comparison, with no struct, no `Option` a step and no call. Anywhere else it
builds a `sysl.Range[T]`: three fields, the two bounds and whether the upper one is included.

```sysl
val r = 0..<4

total(r: Range[int]) -> int
    var acc = 0

    for i in r
        acc += i

    acc

print(total(r), total(r), total(0..3))
```

```output
6 6 6
```

A `Range` is walked by [`Iterate`](/library/core/), so a `for` over one is the ordinary `for` over
anything with a cursor rather than a second kind of loop. The cursor moves, but a `for` walks a
**copy** — which is why `r` above answers the same both times rather than coming back spent, and why
`0..3` reaching the same total says the inclusive form counts one more.

**Its bounds are read by the same two rules a `for` header's are** — they must agree, and they must
be integers — so the value and the loop cannot come to disagree about what a range is. Note that a
bound written as a literal takes its type from the other end, so `0..<3u8` is a `u8` range rather
than a disagreement; it takes two names to write one.

```sysl
f(a: int, b: u8) -> int
    val r = a..<b
    0

print(f(0, 3))
```

```error
a range needs matching bounds
```

**An open end has no value reading.** `..`, `lo..` and `..hi` mean something only in a slice index,
because what an absent bound *is* depends on what is being indexed. They stay index-only and are
refused elsewhere by name.

```sysl
val r = 1..

print(r)
```

```error
a range with an open end is only allowed in a slice index
```

Because a range is a value, it implements [`Sequence`](/library/seq/), which is what makes
`(0..<n).map(f)` the spelling it looks like it should be. Nothing is materialized to answer a
question about one, so `(0..<1_000_000).any(p)` costs the predicate and no storage.

## `is` — a pattern where a condition is wanted

`x is Pat` tests a value against a pattern and yields a `bool`; `x is not Pat` negates it. The right
side is a full pattern, alternatives (`|`) included — the same grammar a `match` arm's left side
uses, so it is not a second thing to learn.

```sysl
enum Shape
    Circle(r: real)
    Rect(w: real, h: real)

var s = Rect(2.0, 3.0)

if s is Rect(_, _) && s is not Circle(_)
    print("rectangular")
```

```output
rectangular
```

Its level — between `&&` and the comparisons — is what makes `a is P && b > 0` a chain of two terms
rather than an `is` against a conjunction. Both `is` and `not` are **soft** words: neither is
reserved, so both remain usable as ordinary names, and this is the only place either reads as a
keyword.

## Assignment

Assignment is an **expression** yielding the value assigned, lowest precedence and
right-associative.

```sysl
var a = 0
var b = 0
var c = 0

a = b = c = 7

print(a, b, c)
```

```output
7 7 7
```

That is what makes the capture idioms available — `while (c = next()) != 0`,
`if (p = find(k)) != null`. C's classic `if (x = 0)` bug is not caused by this; it is caused by C
additionally letting any integer serve as a truth value. sysl has a distinct `bool` with no such
coercion, so the mistake is already a type error and needs no grammar ban:

```sysl
var x = 1

if x = 0
    print("never")
```

```error
condition must be bool, got int
```

The compound forms `+=`, `-=`, `&=` and the rest are **not separate operators with their own
traits**: `a += b` is defined as `a = a + b` and requires exactly what `+` requires. There is no
`AddAssign`.

### Statement position discards a block's value

A block's value is its trailing expression — but where the block's own value is unused, the block has
**none**, and its type is `unit` whatever the last line yields. Without that rule an `if` whose
branches merely each did something would be forced to make those somethings agree:

```
if c
    full = true          // bool
else
    len += 1             // usize
```

The rule is about the **position**, not the operator: it propagates into the branches of an `if`, the
arms of a `match`, and a loop's `else`. Statement position starts at a statement, at a loop body, and
at the body of a function that returns nothing.

A block that does not *arrive* keeps `never` rather than collapsing to `unit` — that is
reachability, not a value, and the code around it is entitled to know.

### Several places at once

A comma-separated list of places takes a comma-separated list of values. **The right side is
evaluated in full, into temporaries, before any assignment happens** — which is the entire content of
the feature, and what makes the first line below a swap rather than two statements that leave both
variables holding `b`.

```sysl
var a = 1
var b = 2

a, b = b, a

var xs = [10, 20, 30]

xs[0], xs[2] = xs[2], xs[0]

print(a, b, xs[0], xs[2])
```

```output
2 1 30 10
```

Compound forms multi-assign too, and need no rule of their own — only the one above, read carefully.
Every place is located, then every place a compound form touches is **read**, then the whole right
side is produced, and only then does anything land:

```sysl
var a = 1
var b = 2

a, b += b, a

print(a, b)
```

```output
3 3
```

Both arms saw the values the statement started with. Written as two statements it would not work:
`a += b` followed by `b += a` folds the new `a` into `b`, and nothing on the page says so.

A place's own subexpressions are evaluated **exactly once**, before the assignments — so in
`xs[f()], xs[g()] = xs[g()], xs[f()]` each of `f` and `g` runs once.

**A multiple assignment is a statement, not an expression**, and that is what keeps it small. A
single assignment yields the value assigned; a multiple one would have to yield several, and the only
thing that could be is a tuple — allocating a product type for a form whose whole point is that
several places change at once. So it does not nest, does not appear in a condition, and has no value
to discard. A binding takes the same list: `val a, b = 1, 2`.

A place that is really a **call** is the one thing the accepted set is *smaller* than a single `=`'s,
and there are two of them: an element reached through a user type's `Index`, where `b[i] = v` calls
`index_set`, and a [settable property](/reference/declarations/), where `p.count = v` calls the
setter. Neither has an address for the locating phase to find, and the call both reads and writes, so
there is nothing to split into the two halves the ordering rule is about.

Those two are also the exception to *"a single assignment yields the value assigned"* above: a call
yields what the call yields, which for both of these is `unit`.

## `++` and `--`

Expressions, both prefix and postfix. Prefix yields the new value; postfix yields the old one. This
is what the pointer-walking idioms need — `*p++` only works if `++` is an expression.

```sysl
var i = 5

print(i++, i, ++i, i)
```

```output
5 6 7 7
```

C's `i = i++ + ++i` is undefined because C leaves evaluation order unspecified while allowing
unsequenced mutation of one object. sysl fixes the root cause rather than banning the operator:
**evaluation order is strictly left to right**, everywhere — operands of a binary operator, function
arguments, index expressions. With an order defined, the expression above has one meaning. It is
merely hard to read, which makes it a lint candidate and not a footgun.

Postfix binds tighter than prefix, so `*p++` is `*(p++)` and `-a.b` is `-(a.b)`, exactly as in C.

## The postfix tail

Eight things attach to an expression on the right, and they compose left to right.

| tail | what it does |
|---|---|
| `e[i]` | index — an element of an array, a slice, or a type implementing `Index` |
| `e.name` | select a field, or call a method |
| `e.0` | select a tuple's part by position |
| `e(…)` | call |
| `e:` and an indented block | call, with the block as an argument — [below](#a-trailing-block) |
| `T::Attr` | a type's attribute rather than a value's — a constrained type's bounds live here |
| `e with { f = v }` | this struct again, with those fields changed — [below](#with-this-value-with-one-field-different) |
| `e?` | try — unwrap or propagate |
| `e++`, `e--` | post-increment, post-decrement |

A tuple index is a `Field` selection because it *is* one: a tuple's fields are named for their
positions. Note that `t.0.1` does not work, because the lexer reads `0.1` as a float before it is two
indices — write `(t.0).1`, which is what the diagnostic says.

A dot with **nothing to its left** is a different form — the member of whatever type the context
expects, [below](#a-leading-dot-the-qualifier-the-context-already-knows) — and it is told from a tail
by there being no expression in front of it to select from.

### `?`

Postfix on an `Option` or a `Result`, and sugar for the most common `match`: **unwrap the success,
or early-return the failure.**

- On a `Result`: `Ok(v)` evaluates to `v`; `Err(e)` returns `Err(e)` from the enclosing function
  immediately.
- On an `Option`: `Some(v)` evaluates to `v`; `None` returns `None` immediately.

```sysl
half(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n / 2) else Err("odd")

quarter(n: int) -> Result[int, string]
    var h = half(n)?

    half(h)

show(r: Result[int, string])
    r match
        Ok(v)  -> print("ok", v)
        Err(e) -> print("err", e)

show(quarter(8))
show(quarter(6))
```

```output
ok 2
err odd
```

Two rules make it well defined. **The enclosing function's return type must carry the failure** — a
`?` on a `Result` is legal only inside a function returning `Result`, and on an `Option` only inside
one returning `Option`. The early return has to have somewhere to go, and the two channels do not
cross. And **the error types must match exactly**; there is no implicit widening, so a function with
its own error type converts a callee's explicitly.

`?` is an expression and composes as one, so its unwrapped value flows into whatever surrounds it.

## `with` — this value, with one field different

`base with { bg = 9 }` is `base` again, with the fields named in the braces changed. Everything else
comes along, and the value written on the left is untouched.

```sysl
struct Style
    fg: int
    bg: int
    pad: int

val theme = Style(1, 2, 3)
val hot = theme with { bg = 9 }

print(theme.fg, theme.bg, theme.pad)
print(hot.fg, hot.bg, hot.pad)
```

```output
1 2 3
1 9 3
```

Several fields at once are separated by commas. A brace suspends the off-side rule until it closes,
so they may be written one per line, and the last one may carry a comma like every other bracketed
list in the language:

```sysl
struct Style
    fg: int
    bg: int
    pad: int

val theme = Style(1, 2, 3)

val hot = theme with {
    bg = 9,
    pad = 8,
}

print(hot.fg, hot.bg, hot.pad)
```

```output
1 9 8
```

### It is the two statements you would otherwise write

The whole of the rule is one desugaring. `base with { bg = 9 }` means:

```
var tmp = base
tmp.bg = 9
tmp
```

so the base is copied first, the fields are assigned to **in the order written**, and the copy is the
value. Nothing else is defined anywhere: a struct's `invariant` is re-checked because a field
assignment re-checks it, a field another module keeps `private` is refused because an assignment
refuses it, a literal needs no width suffix because it is read against the field's type, and a
[settable property](/reference/declarations/) runs its setter because `p.count = v` is a call.

```sysl
struct Cell
    v: int

    count -> int = self.v

    set count(x)
        self.v = x * 10

val c = Cell(0)

print((c with { count = 4 }).v)
```

```output
40
```

### What it buys is that it composes

The two statements are perfectly good and are still there. What they cannot do is stand inside a
larger expression — so a value could not be layered at the point it is used, which is what a theme
overridden for one state and overridden again for the next actually is:

```sysl
struct Style
    fg: int
    bg: int

paint(s: Style)
    print(s.fg, s.bg)

val theme = Style(1, 2)
val pressed = theme with { bg = 9 }

paint(theme)
paint(pressed)
paint(pressed with { fg = 7 })
```

```output
1 2
1 9
7 9
```

The base is evaluated **once**, however many fields change, so a base that is a call is called once.

### The base has to be a struct

A counted reference or a pointer is refused, and it is the one case where refusing matters rather
than merely being tidy: `var tmp = p` on a `&Style` binds a second reference to the *same* object, so
the writes would reach every other holder and the form would quietly mean the opposite of a copy.

```sysl
struct Style
    bg: int

val base: &Style = Style(2)
val hot = base with { bg = 9 }

print(hot.bg)
```

```error
'with' copies a struct, and this is a counted reference to one
```

The refusal names the spelling that does copy, and the parentheses in it are load-bearing: the clause
is a postfix tail, so `*p with { … }` is `*(p with { … })`.

```sysl
struct Style
    bg: int
    pad: int

var base = Style(2, 3)
val p = &base
val hot = (*p) with { bg = 9 }

print(base.bg, hot.bg, hot.pad)
```

```output
2 9 3
```

A field named twice in one clause is refused too, since the first change is one the value cannot
carry:

```sysl
struct Style
    bg: int

val base = Style(2)
val hot = base with { bg = 9, bg = 8 }

print(hot.bg)
```

```error
'bg' is changed twice in one 'with'
```

### `with` is not a reserved word

It is read as a keyword only after a value and before a brace, and is an ordinary identifier
everywhere else — including as a field's own name, on both sides of the clause:

```sysl
struct Flags
    with: int

val f = Flags(1)

print((f with { with = 9 }).with)
```

```output
9
```

## A leading dot — the qualifier the context already knows

`.Green` is `Colour.Green` with the qualifier left off, and the type the position expects is what
supplies it. It is a **lookup rather than a second kind of inference**: the expectation names the
type, and everything after that is the resolution the written-out spelling already gets.

```sysl
enum Colour
    Red
    Green
    Blue

code(c: Colour) -> int
    c match
        Red -> 1
        Green -> 2
        Blue -> 3

struct Pen
    tip: Colour

pick() -> Colour = .Blue

var c: Colour = .Red

c = .Green

val pens: [2]Pen = [Pen(.Red), Pen(.Blue)]

print(code(.Green), code(c), code(pick()), code(pens[1].tip))
print(c == .Green, .Green == c)
```

```output
2 2 3 3
true true
```

**Every position that already pushes an expected type down supplies one**, which is why the list is
long and needs no rule of its own: an argument, an argument written by name, a parameter's default, an
annotated binding and the assignment after it, a return and an expression body, a struct's field, an
element of an array or a slice, a part of a tuple, a variant's payload, and each branch of an `if` or
a `match` used as a value.

**An operand takes it from the operand beside it, and neither side is privileged** — `c == .Green`
and `.Green == c` are the same question. At a *generic* parameter it is held back to the second pass
exactly as `null` is, so the argument that settles the type parameter settles this one too.

**It reaches whatever the qualified form reaches**, and fails where that fails in the same words: a
variant, a variant carrying data, an associated function of an enum, of a struct, or of a
[constrained subtype](/reference/errors/). Visibility is untouched — what the dot leaves off is the
*spelling*, not the check, so a private associated function is as private as it ever was.

**The type it resolves against need not be nameable where the dot is written**, which is most of the
point. A variant of another module's enum needs no import and no path: the parameter already says
which type it is.

**The expected type is read as it was written**, and this is the one place a transparent
constrained subtype is *not* interchangeable with its base: the base is not where the subtype's
members are filed. Reducing `Age` to `int` first would look for the member on `int`, which does not
have it.

```sysl
type Age = int within 0..150

trait Fresh
    fresh() -> Self

impl Fresh for Age
    fresh() -> Age = Age(1)

val a: Age = .fresh()

print(int(a))
```

```output
1
```

Two things it does not do. A **type attribute** is written `::`, because it belongs to the type
rather than to a value of it, and the dot does not reach it. A **pattern** is left alone: a bare name
in one already resolves against the scrutinee's enum, so a dot written there is refused by name
rather than left to a message about patterns.

What it refuses is a position that expects nothing:

```sysl
enum Colour
    Red
    Green

print(.Red)
```

```error
'.Red' is a member of whatever type the context expects, and nothing here expects one
```

and a name the expected type does not have, which is the qualified form's own diagnostic:

```sysl
enum Colour
    Red
    Green

val c: Colour = .Rd

print(1)
```

```error
enum 'Colour' has no variant 'Rd'
```

## Conversions are calls

Every conversion is written, with call syntax, and none is inferred — the visible-cost rule the
memory model rests on applies to representation changes too.

| from → to | written | behaviour |
|---|---|---|
| integer → integer | `u16(n)`, `byte(n)` | truncates or extends; sign-extends only when the *source* is signed |
| integer → float | `real(n)`, `f32(n)` | rounds to nearest; signed and unsigned sources differ |
| float → integer | `int(x)` | truncates toward zero |
| float → float | `f32(x)`, `real(x)` | rounds to nearest |
| `char` → integer | `u32(c)` | total — every `char` is an integer |
| integer → `char` | `char(u)` | **partial** — traps on a value that is not a Unicode scalar value |
| `char` → `string` | `string(c)` | total — the one character, UTF-8 encoded into a fresh string |
| `*T` → integer | `usize(p)`, `isize(p)` | total — an address is a number |

```sysl
var n = 300
var c = 'A'

print(byte(n), real(n), u32(c), int(3.9))
```

```output
44 300 65 3
```

Everything else is rejected. There is **no conversion to or from `bool`** — `int(true)` is an error
and so is `bool(0)` — and **no number converts to or from a `string`**: `str(x)` renders one and the
`strconv` surface parses one, neither of them spelled as a conversion. The `char` → `string` row is
the one exception, and a narrow one: a `char` is a single scalar value, so encoding it is total and
has nothing to say about failure.

The pointer row goes only one way. An address *is* a number of `usize`'s width, so reading it as one
loses nothing and produces a value that cannot be dereferenced. The inverse is not a conversion at
all — making a pointer out of an integer is `ptr_cast`, in the raw tier, spelled apart from this
table because it is where the language's guarantees stop.

Because these are calls, they parse as postfix at level 13 rather than as an operator of their own.
The name in front may be a **type parameter**, and then the row is chosen at the instantiation.

## `sizeof`, `alignof` and `offsetof`

The three forms whose first operand is a **type** rather than a value. All take parentheses, and all
yield a `usize`.

```sysl
struct Pair
    a: int
    b: byte

print(sizeof(int), alignof(int), sizeof(Pair), alignof(Pair))
```

```output
4 4 8 4
```

They are read as their own grammar rather than left to look like calls, because a call's arguments
are expressions and `sizeof(*Node)` would otherwise parse as a dereference. There is no form that
takes a value — a value's type is what would be measured anyway.

`offsetof` takes a type and then a **field name**, and answers where that field starts in bytes. The
name is a name and not an expression, for the same reason the type is not one: there is no value here
for a `p.x` to select from.

```sysl
struct Header
    tag: u8
    length: u32
    flags: u16

print(offsetof(Header, tag), offsetof(Header, length), offsetof(Header, flags), sizeof(Header))
```

```output
0 4 8 12
```

The padding after `tag` is what puts `length` at 4 — `@packed` lays the same fields end to end and
makes them 0, 1 and 5. Its use is
[checking a mirrored C struct](/reference/attributes/#checking-a-c-struct-s-layout), where a size
alone cannot see two same-width fields transposed. A field the struct does not have is refused by
name, and so is a [bitfield](/reference/attributes/#bitfields-an-in-field-in-exactly-n-bits) — the
answer is in bytes, and a field starting at bit twelve is not at byte one.

## Closures

`x -> x + 1` is a closure literal. It sits at the top of the expression grammar, so its body extends
as far to the right as an expression can: that is a closure over the sum, not a closure over `x`
added to `1`.

Parameters are one bare name, or a parenthesized list — including the empty list, which is the one
arity with nowhere else to be written. A type annotation goes inside the parentheses and nowhere
else, so there is no second spelling to disagree with the first.

```sysl
apply(f: () -> int) -> int = f() + f()

var n = 10
var twice = apply(() -> n)
var inc = (x: int) -> x + 1

print(twice, inc(41))
```

```output
20 42
```

A closure's parameter declares **no default**. A call reaches a closure through the `Fn` traits,
which carry types and not names, so there would be nothing at the call to fill one from.

### A closure that only reads what it captured may be a `val`

A closure captures **by value**, so it carries its own copy of what it closed over and calling one
that writes a capture writes through the closure itself. That is why `Fn::call` takes `*self` — and a
`val` is written once, so binding such a closure to one and calling it is refused.

**A closure that only reads is not that closure**, and it is the common one. It writes nothing, so
nothing about the call needs the name to be mutable, and `val` is what a program that is not going to
reassign it should be able to write.

```sysl
val inc = (x: int) -> x + 1
val double = (x: int) -> x * 2

print(inc(41), double(21))
```

```output
42 42
```

The refusal stands exactly where it was, which is the point of the distinction rather than an
exception to it — a closure that writes a capture is a value the call mutates:

```sysl
var n = 0
val bump = () ->
    n += 1
    n

print(bump(), bump())
```

```error
'call' takes '*self', so it writes through what it is called on, and a 'val' is written once — write 'var bump' if it is meant to change
```

`&Fn(int) -> int` is still the way to hold one behind a reference, and is what a program without an
allocator cannot use — a `&T` is a box. That was the whole of the argument for admitting the reading
case: on a freestanding target there had been no way to bind a closure to an immutable name at all.

### `_` — a parameter with the name left out

A bare `_` in operand position is a closure parameter, and the closure it builds closes at the
nearest of three boundaries: a parenthesized group, an argument, or a statement.

```sysl
apply(f: int -> int, x: int) -> int = f(x)

print(apply(_ + 1, 41), apply(_ * 2, 21))
```

```output
42 42
```

The group is what a program reaches for when the other two boundaries fall in the wrong place. But
it cuts both ways, and this is the one thing to know about the form: **the closure closes at the
group, so anything outside the group applies to the closure itself**, not to what it computes.

```sysl
apply(f: int -> int, x: int) -> int = f(x)

print(apply((_ + 1) * 2, 20))
```

```error
this '_' has no type here
```

`(_ + 1) * 2` multiplies a *closure* by two rather than closing over the doubled sum — so nothing is
left to say what the placeholder's parameter is, and the diagnostic points at the `_`. When the
boundary is not where you want it, write the arrow form: `x -> (x + 1) * 2`.

An interpolation hole is a boundary too, so a placeholder cannot reach out of a string to close over
the whole of one.

## A trailing block

A call may write its **last argument as an indented block**, after a colon.

```sysl
total(xs: []int) -> int
    var t = 0

    for i in 0..<xs.len
        t += xs[i]

    t

val n = total:
    1
    2
    3

print(n)
```

```output
6
```

The block is a sequence of expressions, one per line, and **what it becomes is decided by the
parameter it fills**:

| the parameter is | the block is |
|---|---|
| a collection — `[]T`, `[N]T` | an **array** whose elements are its lines |
| a callable — `A -> T`, `&Fn(A) -> T`, or a by-name `-> T` | a **closure** whose body is the block |

Nothing else takes one. A block at a parameter that is neither is refused, and the message names
both readings:

```sysl
f(n: int) = n

val x = f:
    1

print(x)
```

```error
a trailing block stands at 'n', which is a 'int'
```

The callable reading is a closure's body rather than a list, so a binding in it is an ordinary
statement and the last line is its value:

```sysl
apply(f: () -> int) -> int = f()

val n = apply:
    val a = 6
    val b = 7
    a * b

print(n)
```

```output
42
```

### The one value it is passed is `it`

A block writes no parameter list, so the callable it stands at is what names one. Where that callable
takes a **single** value, the block binds it as **`it`**.

```sysl
each(xs: []int, f: int -> unit)
    for i in 0..<xs.len
        f(xs[i])

each([1, 2, 3]):
    print(it * 10)
```

```output
10
20
30
```

`it` is an ordinary parameter of the closure the block became, and not a keyword. It is not reserved,
a binding inside the block shadows it, it shadows a name from outside, and a block nested in another
block hides the outer one's:

```sysl
outer(f: &Fn(int) -> int) -> int = f(1)
inner(f: &Fn(int) -> int) -> int = f(2)

val n = outer:
    inner:
        it * 10

print(n)
```

```output
20
```

A block with no use for the value simply never writes it. A callable taking **none** binds nothing at
all, so `it` inside such a block is whatever the surrounding scope already had.

**A block cannot name two.** There is one implicit name and no positional spelling behind it, so a
callable of two parameters or more is written as a closure literal, which names its own:

```sysl
on_drag(f: &Fn(int, int) -> unit) = f(1, 2)

on_drag:
    print(it)
```

```error
binds the one value it is passed as 'it'
```

```sysl
on_drag(f: &Fn(int, int) -> unit) = f(1, 2)

on_drag((dx, dy) -> print(dx + dy))
```

```output
3
```

None of this reaches the **collection** reading. A block filling `[]T` is a list of its lines and
binds nothing, so `it` there is an undefined name like any other:

```sysl
total(xs: []int) -> int = xs[0]

val n = total:
    it

print(n)
```

```error
undefined name 'it'
```

### Where the block stands

**It fills the first parameter no written argument filled.** Where the call writes no names that is
the same rule as "the block is the last argument"; where it does, the block is still the argument
that was not written in the parentheses.

```sysl
total(xs: []int, bonus: int) -> int
    var t = bonus

    for i in 0..<xs.len
        t += xs[i]

    t

val n = total(bonus = 100):
    1
    2

print(n)
```

```output
103
```

A parameter's default does not count as having filled it, so a block still reaches a parameter that
has one — the block was written and the default was not.

### Blocks nest, which is what the form is for

A call is an expression, so a call carrying a block is a line of the block above it. That is how a
tree gets written as indentation rather than as a nest of brackets.

```sysl
node(name: string, kids: []string) -> string
    var s = name + "("

    for i in 0..<kids.len
        if i > 0 then s += " "

        s += kids[i]

    s + ")"

val tree = node("a"):
    node("b"):
        "c"
        "d"
    "e"

print(tree)
```

```output
a(b(c d) e)
```

### There is no result builder

Swift has one because a Swift block holds *statements*, so something has to assemble arms of
differing shape into a single value. A sysl block holds **expressions**, and `if` is already an
expression whose arms must agree in type — so a branch is an ordinary line and needs no machinery:

```sysl
pick(xs: []int) -> int = xs[0] + xs[1]

val big = true

val n = pick:
    1
    if big then 100 else 2

print(n)
```

```output
101
```

**What that costs is loops, and it is the one limit worth knowing before you reach it.** A loop in
sysl *is* an expression, so one written as a line of a collection's block would contribute the
single `unit` it evaluates to rather than an element per iteration. It is refused by name rather
than read that way:

```sysl
total(xs: []int) -> int = 0

val n = total:
    for k in 0..<3
        k

print(n)
```

```error
would contribute one element and not one per iteration
```

Build the elements first and pass what you built. For the same reason a **binding** is not a line of
a collection's block either: it declares a name rather than producing an element.

```sysl
total(xs: []int) -> int = 0

val n = total:
    var k = 1
    k

print(n)
```

```error
this one declares a name instead
```

Neither restriction applies to the callable reading, where the block is a body and every statement
form is at home in it.

### A trailing block cannot be written inside brackets

A bracket suspends the off-side rule until it closes, so there is no indentation inside one for a
trailing block to be made of. `print(total:` followed by an indented block is a parse error rather
than a call with a block in it — bind the value first and print the name.

The two forms that *do* open a block inside brackets are `match` and `->`, and
[the layout rules](/reference/lexical/) say why they are the two: each is a token that can only ever
open a block, so a reader is never left wondering whether the line ended. `:` is not, and a trailing
block written as an argument has a name-it-first form that reads better anyway.

## String interpolation

`s"…"` renders each `${…}` hole through `str` and concatenates: `s"a${e}b"` **is** `"a" + str(e) + "b"`,
with no runtime formatting machinery involved. `f"…"` allows a printf specifier after a hole, and
routes that hole through `format` instead, where the analyzer checks the specifier against the
value's type.

```sysl
var name = "world"
var n = 7

print(s"hello ${name}, ${n * 6}")
print(f"[${n}%4d]")
```

```output
hello world, 42
[   7]
```

The embedded source is parsed as an ordinary expression — so a hole may itself interpolate — and it
is parsed as its own little source, so a diagnostic inside a hole points into the hole rather than at
an unrelated column of the line the string sits on.

## Operator dispatch

An operator expression is a **trait-method call**, resolved by one rule in every context. For an
operator `⊕` mapped to trait `Op` with method `m`, `a ⊕ b` means `Op::m(a, b)`, and it type-checks
exactly when `a`'s type satisfies `Op` **at `b`'s type**.

What differs between a scalar, a user type and a bounded type parameter is only *where the impl comes
from* — never the rule:

- **A built-in scalar** satisfies the operator traits by a compiler-provided impl, and codegen keeps
  emitting the native machine instruction. No call, no vtable. The membership exists so the type
  system agrees a scalar satisfies `Add`, which is what lets one be passed where `[T: Add]` is wanted.
  That membership is at **one** argument list — `real` is `Mul[real, real]` — so a block written for
  a scalar at another one is reached without disturbing it, which is what puts a scalar on the
  [left of an operator](/reference/traits/#where-an-impl-may-live).
- **A user type with `impl Op for S`** lowers to the member the impl produced. Overloading an
  operator *is* implementing its trait; there is no separate operator-method syntax.
- **A bounded parameter `[T: Op]`** resolves abstractly at the definition, and monomorphization binds
  it per instantiation.

| trait | method | operator |
|---|---|---|
| `Add[Rhs = Self, Out = Self]` | `add(self, rhs: Rhs) -> Out` | `+` |
| `Sub[Rhs = Self, Out = Self]` | `sub(self, rhs: Rhs) -> Out` | `-` (binary) |
| `Mul[Rhs = Self, Out = Self]` | `mul(self, rhs: Rhs) -> Out` | `*` |
| `Div[Rhs = Self, Out = Self]` | `div(self, rhs: Rhs) -> Out` | `/` |
| `Rem[Rhs = Self, Out = Self]` | `rem(self, rhs: Rhs) -> Out` | `%` |
| `BitAnd[Rhs = Self, Out = Self]` | `bitand(self, rhs: Rhs) -> Out` | `&` (binary) |
| `BitOr[Rhs = Self, Out = Self]` | `bitor(self, rhs: Rhs) -> Out` | `\|` |
| `BitXor[Rhs = Self, Out = Self]` | `bitxor(self, rhs: Rhs) -> Out` | `^` |
| `Shl[Rhs = Self, Out = Self]` | `shl(self, rhs: Rhs) -> Out` | `<<` |
| `Shr[Rhs = Self, Out = Self]` | `shr(self, rhs: Rhs) -> Out` | `>>` |
| `Neg` | `neg(self) -> Self` | `-` (unary) |
| `Not` | `not(self) -> Self` | `~` |
| `Eq` | `eq` | `==`, `!=` |
| `Ord` | `lt` | `<`, `>`, `<=`, `>=` |
| `Index` | `index` | `e[i]` read |
| `IndexSet` | `index_set` | `e[i] = v` |

The two prefix rows take no result parameter: negation and complement are closed operations on the
type they are written for. `Eq` and `Ord` stay homogeneous for a different reason — what a comparison
across two types would promise about reflexivity and transitivity is a question nothing has asked.

**Two more traits sit beside these and have no operator**: [`Zero`](/library/math/) and `One`, each
declaring a single member with no receiver — `zero() -> Self` and `one() -> Self`. They are here
rather than in `sysl.math` because they are the identities *of these operators*, and because a trait
outside the standard module is reachable only where a file names it: a generic body writing
`T.zero()` should owe no import, and a `real` that answers `real.zero()` should not stop doing so
because the caller did not think to ask for a trait. What a bound gains by them is the one thing an
operator cannot supply — a **value** — so `[T: Add + Zero]` can start an accumulation where
`[T: Add]` has to be handed one.

**Every integer type is a member of both, and no block anywhere says so.** `sysl.ops` writes an
`impl` for `real` and one for `f32`, and it cannot write one for the integers: `iN` and `uN` are an
[open family](/reference/types/), so a program may name a `u256` no library file ever listed. That
membership is the compiler's, exactly as `Signed`'s and `Bits`' are — and unlike those, the member
it supplies has **no receiver**, so what it lowers to is stated rather than read off a value.
`T.zero()` at an integer is the literal `0`, and the accumulator below costs no call at all.

```sysl
sum[T: Add + Zero](xs: []const T) -> T
    var total = T.zero()

    for i in 0..<xs.len do total += xs[i]

    total

var none: []int = []

print(sum([1, 2, 3]), sum(none), sum([1.5, 2.5]) == 4.0)
```

```output
6 0 true
```

**A constrained subtype is deliberately not a member, and it is the one place a value parts company
with an operation.** [A derivation](/reference/errors/) gives a subtype every operation its base has,
because narrowing which values a type holds does not narrow what can be done with them. An identity
is not an operation: a range written to exclude zero has not got one, so the membership stops at the
integer types themselves. A **vector** is left out for a related reason — `<4>i32` has four lanes and
`zero()` names none of them, where every operator above is the lane's operation happening four times
at once.

```sysl
type Age = int within 0..150

first[T: Zero](x: T) -> T = T.zero()

var a: Age = 30

print(first(a))
```

```error
'first' requires its type parameter 'T' to implement 'sysl.Zero', but Age does not
```

```sysl
struct Vec2
    x: int
    y: int

impl Add for Vec2
    add(self, rhs: Vec2) -> Vec2 = Vec2(self.x + rhs.x, self.y + rhs.y)

var v = Vec2(1, 2) + Vec2(10, 20)

print(v.x, v.y)
```

```output
11 22
```

### The operand and the result are both trait arguments

Both parameters default to `Self`, so the homogeneous reading is what every arithmetic trait means
where nothing says otherwise: `impl Mul for Point` is `impl Mul[Point, Point] for Point`, and
`[T: Mul]` asks for `Mul[T, T]`. **Writing an argument is what asks for something else**, and four
readings fall out of one trait:

| written | means | what it says |
|---|---|---|
| `impl Mul for V` | `Mul[V, V]` | homogeneous — `V * V -> V` |
| `impl Mul[real] for V` | `Mul[real, V]` | scaling — `V * real -> V` |
| `impl Mul[V, real] for V` | as written | a dot product — `V * V -> real` |
| `impl Mul[V, V] for M` | as written | a transform — `M * V -> V` |

**No associated type is involved, and that is a choice rather than an absence.** sysl has
[associated types](/reference/traits/#a-trait-may-declare-an-associated-type), and the catalog
deliberately does not use one: an associated type is **one per type**, so `Out` as an associated type
would let a `Vec2` have exactly one multiplication. Rust's `Add` carries an associated `Output` and
pays for it with a separate trait per operand type; here the result is an ordinary trait argument
defaulting to `Self`, so one trait covers all four readings below — which is the mechanism
[`Index`](#the-postfix-tail) already uses to carry the element type of what it reads. A vector space needs three of the four readings at once, and they are three ordinary
blocks:

```sysl
struct Vec2
    x: real
    y: real

struct Mat2
    a: real
    b: real
    c: real
    d: real

impl Mul[Vec2, real] for Vec2
    mul(self, o: Vec2) -> real = self.x * o.x + self.y * o.y

impl Mul[real] for Vec2
    mul(self, k: real) -> Vec2 = Vec2(self.x * k, self.y * k)

impl Mul[Vec2, Vec2] for Mat2
    mul(self, v: Vec2) -> Vec2 = Vec2(self.a * v.x + self.b * v.y, self.c * v.x + self.d * v.y)

var v = Vec2(1.0, 2.0)
var scaled = v * 3.0
var turned = Mat2(0.0, -1.0, 1.0, 0.0) * v

print(v * v, scaled.x, turned.x, turned.y)
```

```output
5 3 -2 1
```

**A result is not a selector.** A use writes the operands and never the result — `a * b` asks to be
*told* what comes back — so the operands choose the implementation and the implementation supplies
the result. Two implementations agreeing on the operands and differing only in what they give back
are therefore refused where they are written, rather than ranked at the use:

```sysl
struct V
    x: real

impl Mul[V, real] for V
    mul(self, o: V) -> real = self.x * o.x

impl Mul[V, V] for V
    mul(self, o: V) -> V = V(self.x * o.x)

print(1)
```

```error
differs only in what it gives back
```

**A compound assignment stays homogeneous**, and this is where a result that is not the left
operand's type is felt: `a op= b` is `a = a op b`, so there is nothing to assign back.

```sysl
struct V
    x: real

impl Mul[V, real] for V
    mul(self, o: V) -> real = self.x * o.x

var v = V(3.0)

v *= V(4.0)

print(v.x)
```

```error
'*=' updates V in place, but '*' between V and V gives real
```

One thing the result being an argument opens: an operator trait at **written** arguments has no
`Self` left in its signature — `Mul[real, real]` declares `mul(self, rhs: real) -> real` — so it is
object-safe, and `&Mul[real, real]` is a formable [trait object](/reference/traits/#trait-objects)
over types with quite different multiplications. Written bare it is `Mul[Self, Self]` and still is
not.

#### At a generic subject

A generic block writes its **own parameters** as trait arguments, and that is the same reading rather
than an exception to it: the block's parameters are exactly the arguments of the type it is written
for, so an argument built out of them says one thing per instantiation and the subject settles it.
The vector space above is therefore written once over an element type:

```sysl
struct Vec2[T]
    a: T
    b: T

impl[T: Mul + Add] Mul[Vec2[T], T] for Vec2[T]
    mul(self, rhs: Vec2[T]) -> T = self.a * rhs.a + self.b * rhs.b

impl[T: Mul] Mul[T] for Vec2[T]
    mul(self, rhs: T) -> Vec2[T] = Vec2(self.a * rhs, self.b * rhs)

var f = Vec2(1.0, 2.0) * Vec2(3.0, 4.0)
var n = Vec2(1, 2) * Vec2(3, 4)
var s = Vec2(1, 2) * 3

print(f, n, s.a, s.b)
```

```output
11 11 3 6
```

The dot product is the reading with **no other spelling**: trait arguments are positional, so
reaching `Out` means writing `Rhs`, and `Rhs` on a dot product is the subject itself. There is no
`Mul[Out = T]`.

What is refused is an argument naming **one instantiation** of the subject. That one would promise at
that instantiation what a defaulted block promises there, and promise something different everywhere
else — a choice between implementations rather than a lookup:

```sysl
struct Box[T]
    v: T

impl[T] Mul[Box[int]] for Box[T]
    mul(self, rhs: Box[int]) -> Box[T] = self

print(1)
```

```error
whose arguments default names the type it is written for
```

A genuine duplicate on a generic subject is caught by the rules above, unchanged: a second block at
the same operands by [one implementation per argument
list](/reference/traits/#one-implementation-per-argument-list), and one differing only in its result
by the rule that a result is not a selector.

A type becomes fully comparable by implementing **one** method, `lt`, and fully equatable by
implementing **one**, `eq` — the compiler derives the rest: `a != b` is `!eq(a, b)`, `a > b` is
`lt(b, a)`, `a <= b` is `!lt(b, a)`, `a >= b` is `!lt(a, b)`.

Two of those **swap their operands**, and the swap is of the two *values*, applied at the call — so
`a > b` still evaluates `a` before `b`, and the derivation is invisible in evaluation order as well
as in the answer.

`Eq` and `Ord` are **independent** traits, not a hierarchy. That is the scalar law lifted intact:
`bool` and the pointer modes have `==` and no `<`. There is no four-way `PartialEq`/`Eq`/`PartialOrd`
/`Ord` tower.

The scalars do not go through those derivations — the compiler-provided impls supply all six
comparisons directly at IEEE semantics, which is what keeps `NaN <= 1.0` and `NaN >= 1.0` both false
where negating `lt` would have made one true.

### A simple enum is `Eq`, and nothing else

An enum whose variants all carry nothing is a **simple** enum, and its value *is* its discriminant.
There is exactly one thing equality on it could mean, so the compiler supplies it — the same rule
that makes every width of integer `Eq` without a block being written per width:

```sysl
enum Colorspace
    Srgb
    Linear

same[T: Eq](a: T, b: T) -> bool = a == b

print(Srgb == Srgb, Srgb == Linear)
print(same(Linear, Linear))
```

```output
true false
true
```

The membership satisfies an `Eq` **bound**, as the second line shows, and not merely the `==` token —
so a simple enum goes into anything written over `[T: Eq]`.

It is **not** `Ord`. Declaration order is an order and it is not a *meaning*: `Srgb < Linear` says
nothing anybody wants a language to assert on their behalf, so an enum whose order means something
writes the `impl` that says so. It is not `Hash` either, for the same reason — that is a promise
about a distribution, and a program makes it deliberately.

An enum that **carries data** is not a member. Comparing two of those means comparing their payloads,
which needs every payload type to be `Eq` itself; that is an `impl` a program writes, and
[the core module](/library/core/) shows the shape.

Writing the block by hand for a simple one is refused rather than ignored, because the comparison is
emitted whatever the block says:

```sysl
enum Colorspace
    Srgb
    Linear

impl Eq for Colorspace
    eq(self, rhs: Colorspace) -> bool = int(self) == int(rhs)

print(Srgb == Srgb)
```

```error
'Colorspace' already implements 'sysl.Eq' — no variant of it carries anything, so its value is its discriminant and '==' is that comparison. Delete the block; a variant that needs an equality of its own has to carry something for it to be about
```

### Why the token set is closed

Overloading the fixed set is the bare-metal consensus: Rust, C++, D and Ada all allow it, Zig and C
allow no overloading at all, and **none** permits a new operator token. Low-level code is read while
reasoning about hardware, and a mystery operator that is secretly a user function fights that — the
same visible-cost value the memory model rests on. A closed set also lexes by longest match against a
fixed list, with no operator "muncher" and no parser-vocabulary registration to keep in step.

---

Next: [statements and control flow](/reference/statements/).
