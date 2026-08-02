---
title: Errors, traps and contracts
summary: Two channels for failure — a value you handle and a stop you cannot — plus the constrained types, invariants and contracts that decide which one you get.
weight: 110
---

Failure travels on **two separate channels**, and which one a given failure uses is a design decision
at every API rather than a coin toss:

- **A recoverable failure is a value.** `Result[T, E]` or `Option[T]` in the return type, propagated
  with [`?`](/reference/expressions/). It is part of the signature, so the caller has to engage
  with it.
- **A bug is a trap.** An index past the end, an invalid cast, a broken contract. Not a value, not
  catchable, and the program stops.

Keeping them apart is what makes a signature honest. A function returning `Result[T, E]` is telling
you it can fail in a way you handle; one returning plain `T` is telling you the only way it "fails"
is if the program is already wrong.

This page covers both channels and then the three features that *create* the second one —
constrained types, struct invariants, and function contracts — because a `within` range and a
`require` clause are only interesting once you know what happens when they are false.

## Which channel — the policy

One question decides it: **could correct calling code ever hit this?**

| | answer | why |
|---|---|---|
| **Yes** — an expected outcome of valid use | `Result` / `Option` | The input came from outside the program — a file, a socket, a person — or the operation legitimately may not succeed. A correct caller still meets this case, so the type must force it to be handled. |
| **No** — only a bug reaches it | a trap | Indexing past the end, converting an out-of-range integer to a `char`, dividing by zero, violating a `require`. A correct program never does these; reaching one means the program is already wrong. |

The payoff of the split is that signatures stay clean — no `Result` smeared across functions that
cannot meaningfully fail — while the failures that *are* real are impossible to ignore, because they
are in the type.

## `Option[T]` and `Result[T, E]`

Both are **ordinary generic enums declared in the standard library**, with no compiler privileges:

```
enum Option[T]                 enum Result[T, E]
    Some(value: T)                 Ok(value: T)
    None                           Err(error: E)
```

- **`Option[T]` is absence** — a value that may or may not be there, with no reason attached: a
  missing key, the `next` of a list tail, the result of a search. It is also what a
  [`weak` reference](/reference/memory/) degrades to, and what a fallible constructor like
  `char.try` answers with.
- **`Result[T, E]` is failure with a reason** — parsing, I/O, validation. `E` is whatever carries the
  reason: a string, an error enum, a `&Fail`.

They are constructed and taken apart like any enum, and their type arguments come from inference —
including the two cases with nothing to infer from at the call. A bare `None` takes its `T` from
context, and an `Ok(n)` inside a `Result[int, string]`-returning function takes its `E` from the
return type:

```sysl
half(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n / 2)
    else Err("odd")

first_even(xs: []const int) -> Option[int]
    for x in xs
        if x % 2 == 0 then return Some(x)

    None

var data = [3, 5, 8, 9]

half(10) match
    Ok(v)  -> print("half:", v)
    Err(e) -> print("refused:", e)

half(7) match
    Ok(v)  -> print("half:", v)
    Err(e) -> print("refused:", e)

print(first_even(data[..]).unwrap_or(-1))
```

```output
half: 5
refused: odd
8
```

`E` is usually an enum rather than a string, so a caller can match on *what* went wrong instead of
reading a sentence about it.

### The members

The **total** ones ask a question or supply a fallback. None of them can stop the program:

| on `Option[T]` | on `Result[T, E]` | is |
|---|---|---|
| `is_some()` | `is_ok()` | `true` when the value is there |
| `is_none()` | `is_err()` | `true` when it is not |
| `unwrap_or(default)` | `unwrap_or(default)` | the payload, or `default` |

The **forcing** ones hand over the payload and stop the program when there is none:

| on `Option[T]` | on `Result[T, E]` | stops when |
|---|---|---|
| `unwrap()` | `unwrap()` | `None` / `Err` |
| `expect(msg)` | `expect(msg)` | `None` / `Err`, printing `msg` |
| — | `unwrap_err()` | `Ok` |
| — | `expect_err(msg)` | `Ok` |

```sysl
var got: Option[int] = Some(7)
var bad: Result[int, string] = Err("no")

print(got.is_some(), got.unwrap_or(0), got.expect("put here two lines ago"))
print(bad.is_err(), bad.unwrap_or(-1), bad.unwrap_err())
```

```output
true 7 7
true -1 no
```

`unwrap_err` is `Result`'s alone because `Option`'s empty case carries nothing to hand back.

### They are written in sysl, and that is the point

The forcing members stop the program without any compiler support of their own:

```sysl
unwrap(self) -> T = self match
    Some(v) -> v
    None ->
        print("panic: unwrap of a None value")
        exit(1)
```

That is what keeps "a bug stops the program" from meaning "the compiler must know the name of every
way to stop". Two ordinary features make it work. A diverging arm has a type — [`never`](/reference/types/)
— so `exit(1)` sits beside `Some(v) -> v` and the `match` still has the payload's type rather than a
conflict between `T` and nothing. And the departure itself is an `extern`: the library declares
`exit(code: int) -> never`, so stopping is a call.

Nothing here costs a program that does not use it. The enums are generic, so a member exists only
where a call asks for one, and an `extern` nothing reaches is never declared in the output.

## `?`

The [expressions page](/reference/expressions/) gives `?` its place in the grammar — postfix, at
the tightest level, alongside `.` and `()`. Its rules in full:

**On a `Result`,** `Ok(v)` evaluates to `v` and `Err(e)` returns `Err(e)` from the enclosing function
immediately. **On an `Option`,** `Some(v)` evaluates to `v` and `None` returns `None` immediately.

**The enclosing function's return type must be able to carry the failure.** The early return has to
have somewhere to go:

```sysl
half(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n / 2) else Err("odd")

count(n: int) -> int
    half(n)?
```

```error
'?' may only be used in a function returning sysl.Result, not int
```

**The two channels do not cross.** An `Option`'s `?` cannot early-return from a `Result`-returning
function, and the diagnostic names the type it wanted:

```sysl
pick(n: int) -> Option[int]
    if n > 0 then Some(n) else None

cross(n: int) -> Result[int, string]
    var v = pick(n)?

    Ok(v)
```

```error
'?' may only be used in a function returning sysl.Option, not sysl.Result[int, string]
```

**The error types must match exactly.** There is no implicit widening, so a function with an error
type of its own converts a callee's explicitly:

```sysl
enum Fail
    Bad

half(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n / 2) else Err("odd")

other(n: int) -> Result[int, Fail]
    var v = half(n)?

    Ok(v)
```

```error
'?' propagates a string error, but this function returns Fail
```

That last rule is the shipping behaviour rather than the end state, and its ergonomic cost is real: a
program with its own `AppError` cannot `?`-propagate a library's `IoError` without a manual step at
each call. The eventual answer is a `From`-style conversion inserted by `?`, which is designed and
waiting on something else — a type implements a trait **once**, so an `AppError` cannot be
`From[IoError]` and `From[ParseError]` both, and lifting that needs a way for a use to say which
implementation it means. The conversion is additive: turning it on later invalidates no exact-match
`?` written before it.

**`?` is an expression**, so its unwrapped value flows straight into whatever surrounds it — `Ok(mk()?)`
is ordinary, and a chain of hops is written as a sequence of `?`-bound locals.

### `?` and the memory model

`?` obeys [ARC](/reference/memory/) with no special rule, and this is where that
discipline is load-bearing, because `?` is the operator most likely to carry a heap payload across a
function boundary.

**Unwrapping a `&T` success payload retains it past the wrapper**, so the reference outlives the
`Result` it came out of and is released exactly once:

```sysl
struct Point
    x: int
    y: int

mk(ok: bool) -> Result[&Point, string]
    if ok then Ok(Point(1, 2)) else Err("no")

use(ok: bool) -> Result[int, string]
    var p = mk(ok)?

    Ok(p.x + p.y)

print(use(true).unwrap_or(-1), use(false).unwrap_or(-1))
```

```output
3 -1
```

**Propagating a `&T` error payload moves it through the early return** with its count intact, freed
exactly once on whichever path consumes it.

Neither is a rule about `?`. Both fall out of retain-on-alias and release-at-scope-end, and they are
named here because a hand-rolled C error return leaks or double-frees at exactly these two
boundaries.

## Traps

The other channel. A trap is the runtime response to a **broken invariant**, and its semantics are
settled:

**A trap aborts.** There is no unwinding, no stack cleanup, no `catch`, no exceptions. When a check
fails the program stops; it does not run destructors up the stack, and it cannot be intercepted and
resumed.

Two reasons this is the only defensible choice for the language's targets. **A kernel and an embedded
target have no unwinding runtime** — landing pads, a personality routine and per-frame cleanup tables
are exactly the machinery a freestanding `no alloc` target does not have and does not want, and an
abort needs none of it. And **determinism**: an abort is one code path, where unwinding is a second,
invisible control-flow graph that every function in the program would have to be correct under.
Removing it removes the whole "is this exception-safe?" class of reasoning, which is the same
simplification the memory model makes by having no move semantics.

**Nor does a trap run a deferred statement.** [`defer`](/reference/statements/#defer) is the
language's scope-exit form, and a trap is not an exit — it is the program stopping because its model
of itself is already wrong. Cleanup code run against that state is how a corrupt program writes its
corruption out on the way down. So `defer` is for releasing a resource, not for restoring an
invariant.

### What traps

| source | example |
|---|---|
| an out-of-bounds array or slice index | `xs[5]` on a `[3]int` |
| an inverted or out-of-range slice range | `xs[3..1]`, `xs[0..9]` |
| a checked cast that fails | `char(u)` on an invalid scalar, `Color(n)` on an undeclared discriminant |
| an integer divide by zero | `n / 0`, `n % 0` |
| a violated `require` or `ensure` | below |
| a violated struct `invariant` | below |
| a violated `within` range or `where` predicate, at a produce site | below |
| `T::Succ` past `T::Last`, `T::Pred` below `T::First` | below |
| overflow of `+`, `-`, `*` where an operand came through a ranged type | below |

**Integer overflow is otherwise not a trap source**, which is worth saying because Rust traps on it
in a debug build. Plain arithmetic [wraps at the declared width](/reference/types/), so
overflow is defined behaviour rather than a broken invariant and there is nothing for a trap to
report. The last row of the table is the narrow exception, and it belongs to constrained types rather
than to arithmetic.

**No build option removes any of these.** There is no `--no-contracts`, no bounds-check stripping, no
`NDEBUG`. A check that is in the language is in every build, because a switch that removed one would
make a program's meaning depend on how it was compiled.

### What stopping looks like

The *decision* to stop is the language's; the *action* on stopping is the environment's. Under the
`os` capability a hosted program stops the process and exits non-zero; a kernel installs its own
panic handler and enters that.

On a hosted target there are two observably different shapes, and the difference is worth knowing
before you debug one:

| stopped by | what you see | exit status |
|---|---|---|
| a **compiler-inserted check** — a bound, a cast, a range, a contract | nothing at all, and **buffered output already written by the program is lost** | the platform's signal status for a trap instruction |
| a **library forcing member** — `unwrap`, `expect` | `panic: <message>` on stdout, after everything the program printed before it | 1 |

The library's route goes through `exit`, which flushes on its way out; a compiler check goes straight
to the target's trap instruction, which does not. So a program that printed diagnostics right up to
the failing line will appear to have printed **none** of them if a bounds check is what stopped it.
Reconciling the two so that every stop says why it stopped is an open question in the design.

## Turning a trap back into a value

There is no `panic` you can recover from, so a program that must survive bad input has to **not do
the trapping thing**: check the bound, validate before dividing, and use the fallible constructor
rather than the checked cast. That is the move to make wherever untrusted input enters, and it is
where a bug turns back into a value:

```sysl
enum Color: u8
    Red
    Green
    Blue

parse(b: u8) -> Result[Color, string]
    Color.try(b) match
        Some(c) -> Ok(c)
        None    -> Err("byte " + str(b) + " is not a Color")

parse(1) match
    Ok(c)  -> print("read:", Color::Image(c))
    Err(e) -> print(e)

parse(9) match
    Ok(c)  -> print("read:", Color::Image(c))
    Err(e) -> print(e)
```

```output
read: Green
byte 9 is not a Color
```

`Color(9)` would have trapped. `Color.try(9)` hands back a `None` this function turns into a reason,
and the byte off the wire stops being a bug in the program and starts being a value it handles.

For a constrained type the equivalent is `T::Valid`, below.

## Constrained types

One declaration form with three independent parts:

```
type Name = [new] Base [within lo..hi] [where predicate]
```

`new`, `within` and `where` are **contextual keywords** — ordinary identifiers everywhere else, so a
field, a function or a variable may still be called `where`:

```sysl
struct Cfg
    where: int
    invariant: int

where(n: int) -> int = n * 2

var c = Cfg(3, 4)

print(c.where, c.invariant, where(5))
```

```output
3 4 10
```

Each of the three parts may be left out, with one exception:

| written | means |
|---|---|
| `type Meters = new f64` | a distinct type over `f64`, no constraint |
| `type Age = int within 0..150` | `int` with a range, but the *same type* as `int` |
| `type Even = int where value % 2 == 0` | `int` with a predicate |
| `type Slot = new u8 within 0..<200` | distinct **and** constrained |
| `type Alias = int` | **rejected** |

The last row is the exception. A transparent alias with no constraint declares nothing — it neither
narrows the values nor makes a new type — so writing it can only be a mistake or an attempt at a
`typedef`, which sysl does not have:

```sysl
type Alias = int

var n: Alias = 1

print(n)
```

```error
'Alias' has no constraint — add a 'within' range or a 'where' predicate, or 'new' to make it a distinct type
```

Note that `type Name = Existing` **is** legal as a [type alias](/reference/declarations/#type-declarations)
for a name that has grown long; what is refused is calling that a constrained type by adding nothing
to it. The diagnostic is what tells the two apart.

**The base must be a scalar** — an integer, a float, or a `char`:

```sysl
struct Point
    x: int
    y: int

type Bad = new Point

var b = Bad(Point(1, 2))

print(b.x)
```

```error
a constrained subtype's base must be an integer, a float, or 'char', not Point
```

A constraint here is a check on a *value*. The two ways to narrow an aggregate are the struct
invariant below and, for an enum, having fewer variants.

### Ranges

`..` includes the upper bound and `..<` excludes it, matching the
[range expressions](/reference/expressions/#ranges). A bound is a **constant expression** — a
literal, a `const`, or arithmetic over them, folded through the same path an array length and an enum
discriminant go through, so the three positions accept the same expressions and cannot drift apart:

```sysl
const max_tasks: int = 4

type Task = int within 0..<max_tasks
type Trimmed = int within 0..max_tasks - 2

var t: Task = 3
var u: Trimmed = 2

print(t, u)
```

```output
3 2
```

That is one fact written once: `within 0..<max_tasks` beside `[max_tasks]Task`. What a bound may not
be is **non-constant** — a module-level `val` is read-only storage with an address rather than a
constant, and is refused here.

Two shapes are rejected at the declaration. A bound outside the base's own range:

```sysl
type Big = byte within 0..300

var b: Big = 1

print(b)
```

```error
the bound 300 does not fit byte
```

And an inverted range:

```sysl
type Inverted = int within 10..0

var i: Inverted = 5

print(i)
```

```error
the lower bound of 'Inverted' is above its upper bound
```

### Predicates

The predicate is an ordinary boolean expression, and the value it is about is named **`value`**,
bound only inside the predicate:

```sysl
type Even = int within 0..100 where value % 2 == 0
type HexDigit = char where value >= '0' && value <= '9'

var e: Even = 8
var h: HexDigit = '7'

print(e, h)
```

```output
8 7
```

A predicate may read module constants, which is what lets a range and a table's size be stated once.
Where both a range and a predicate are written, **the range is checked first**, so a value the range
rejects never reaches the predicate.

A non-boolean predicate is reported against the type, without leaking the name of the function the
compiler synthesised to hold it:

```sysl
type Odd = int where value + 1

var o: Odd = 3

print(o)
```

```error
a 'where' predicate must be a 'bool', but this one is int
```

### `new` is what makes it a type

Without `new`, a constrained type **is** its base with a checked range. `Age` and `int` are the same
type, values flow between them in both directions with no cast, and what the declaration buys is the
check:

```sysl
type Age = int within 0..150

birthday(a: Age) -> Age = a + 1

var n: int = birthday(41)

print(n)
```

```output
42
```

With `new` it is a **distinct nominal type**, and that is the whole of the difference. Two derived
types over one base do not mix:

```sysl
type Meters = new f64
type Feet = new f64

print(f64(Meters(1.0) + Feet(1.0)))
```

```error
'+' needs matching types, got Meters and Feet
```

A derived type does not mix with its **base** either, and no position excuses it — an initializer, an
argument and a returned value each refuse it:

```sysl
type Meters = new f64

var q: Meters = 3.0

print(f64(q))
```

```error
cannot initialize 'q': declared Meters but the value is real
```

Going in either direction is a **written conversion**: `Meters(x)` wraps, `f64(m)` unwraps, and the
wrap is where the constraint is checked.

The payoff is a specific bug. A table-driven program has several small integers that index different
things — a task number, a lock number, a priority level — and the mistake such a program actually
makes is passing one where another was wanted. Three `new u8`s with three ranges make that a compile
error rather than a plausible-looking wrong answer. **It is an argument about types, not about
checking.**

### A derivation inherits its base's behaviour and may replace none of it

A `new` type over a scalar arrives with **everything the scalar could do** — the arithmetic
operators, the remainder, the bitwise operators and the shifts, unary `-` and `~`, the comparisons,
the compound assignments, `++` and `--`, and `str` — working at itself and producing itself.

And no `impl` may replace or extend any of it:

```sysl
type Stamp = new i64
type Span = new i64

impl Add[Span] for Stamp
    add(self, other: Span) -> Stamp = Stamp(i64(self) + i64(other))
```

```error
'add' is how 'Add' is implemented for Stamp, and the compiler provides that — a member of this name would hide it
```

```sysl
type Span = new i64

impl Display for Span
    str(self) -> string = "span"
```

```error
'Span' already implements 'sysl.Display' — the compiler provides it
```

Both halves are deliberate. **Inheriting is right** because a derivation does not change what the
values *are*: a `Slot` is some of the `u8`s, not a different set of things that happens to be stored
in a byte. A derivation that started with nothing would make every `new u8` cost a dozen `impl`
blocks, and nobody would use it.

**Refusing to replace is the harder call, and it is a ruling.** If `Stamp` could redefine `<`, then
`Stamp` would be a set of `i64`s that do not order the way `i64`s order, and every fact the base
guarantees would hold only until somebody looked. A derivation is a *narrowing*, and a narrowing that
alters behaviour is not a narrowing.

So the answer to "I want my own `+`" is that you do not want a derivation, you want a **struct**:

| | a `new` derivation | a one-field struct |
|---|---|---|
| distinct type | yes | yes |
| the base's catalogue | free, and unchangeable | nothing, write it all |
| an operation the base does not have | impossible | ordinary |
| an operation the base has that is now nonsense | present anyway | absent |

**Use a derivation for an identity** — a slot number, a handle, a unit-tagged measurement, anything
whose operations are its representation's operations. **Use a struct for a quantity with an algebra
of its own**: an instant plus a duration is an instant, an instant plus an instant is nonsense, and
no derived scalar can be told the difference. The cost is real and was measured — a date-and-time
library written this way needs one-field structs and five `impl` blocks per type.

What has no answer today is the case in the middle: a type that wants most of its base's catalogue
and one row of its own. `impl Add[Duration] for Instant` takes something the base could never have
taken and touches no guarantee of `i64`'s, but it is refused because the check is on the method
*name*, and `add` is taken.

**A subtype narrows which values a type has, never which operations it has** — so what the base does
not have, the subtype does not either, and the diagnostic names the subtype rather than the base:

```sysl
type Slot = new u8 within 0..<8

neg(s: Slot) -> Slot = -s
```

```error
unary '-' is not defined for the unsigned type Slot
```

```sysl
type Ratio = new f64

flip(r: Ratio) -> Ratio = ~r
```

```error
unary '~' is not defined for Ratio
```

### The operations are the base's; their overflow is not

Raw integer arithmetic wraps, and a wrapped result reaching a produce site is a wrong answer the
range check can pass without noticing. On a `Slot = u8 within 0..<200`, `Slot(150) + Slot(150)` is
300, which wraps to 44 — which is in range.

So **where an operand came through a `within` type and the operands' declared ranges permit a result
the base width cannot hold, `+`, `-` and `*` are overflow-detecting and trap.** A range narrow enough
that its results always fit stays on the plain instruction, so a counter, an index and an `x + 1`
cost exactly what they did before. A left shift is checked on its own terms — it has no overflow
intrinsic, so a bit pushed out of the top is caught by shifting back, and a shift amount at or past
the width traps rather than being undefined. Arithmetic on a type with **no** range still wraps.

For a **transparent** subtype the arithmetic happens at the base and yields a base value, so a
literal beside one is an ordinary base value and what has to be in range is only what is stored:

```sysl
type Small = int within 0..10

var s: Small = 2

print(s * 100)
```

```output
200
```

The multiplier is not a `Small` and does not have to be. A **derived** subtype is its own
representation, so it mixes with the base only through the cast above, and its results are its own
and are checked where they are produced.

### Where a constraint is checked

**At every point a value of the constrained type is produced, and nowhere else.** That is not a list
of syntactic forms to memorize: a value comes to have the type wherever it **flows into a slot the
type is written on**, plus wherever an operation of the type's own **yields one**. The slots are
enumerable:

- a variable's initializer, and every later assignment to it — including one arm of a
  multi-assignment, and **a write through a pointer**
- an argument at a call, and a function's returned value — a plain function's, a method's, a nested
  function's, or a closure's
- an explicit cast, `T(x)`
- a field written into a struct, at construction and at every later write, however the struct is
  reached
- an element of an array, at a literal and at every later write, through the array or through a view
  of it
- a part of a tuple, and the payload of an enum variant
- an item entering a **generic** container instantiated at the type — the slot is written `T` there,
  so the check follows the type *argument* and not the spelling

```sysl
type Age = int within 0..150

var a: Age = 8
var slot: Option[Age] = Some(a)
var p = &a

*p = 41

print(a, slot.unwrap_or(0))
```

```output
41 8
```

And the two sites that are the type's own doing rather than a slot's:

- **an operation on a derived subtype**, which gets the base's catalogue *producing itself* — so
  `Slot(199) + Slot(1)` is a produce site and traps. A **transparent** subtype has no such site: its
  arithmetic happens at its base and yields a base value, checked by the store that gives it the
  subtype again.
- **a compound assignment and an increment**, which compute and store in one step and so are checked
  between the two. `a += e` produces exactly what `a = a + e` produces: what the operator is applied
  to is a base value either way, so `t += 120` on a `Temp = int within -100..100` holding `-50` is
  `70`, and not a complaint that 120 is no temperature.

A value that **already has** the type is not re-checked when it is merely read, passed along, or
copied — it could not have got there unchecked. Passing one to a *different* subtype over the same
base **is** a produce site and is checked again, which is what keeps a wider type from leaking into a
narrower one.

### A constrained type has no zero value

The one site that is closed by not existing:

```sysl
type Age = int within 0..150

var a: Age

print(a)
```

```error
Age has no zero value, so 'a' needs an initial value
```

That holds whether or not the range contains zero. Making it the **type's** rule rather than the
range's means widening a range never silently changes whether a declaration compiles somewhere else.

### A violated check traps, and there is no `try`

It is not an error value and it is not catchable. A constrained type states something its values
*are*, so a value that is not one of them is a bug in the code that made it rather than a condition
to handle — which means the fallible constructor an enum has is deliberately absent here, and the
diagnostic says so by name:

```sysl
type Age = int within 0..150

print(Age.try(5))
```

```error
'Age' is a constrained type and has no 'try': a value outside its range is a mistake in the code that produced it rather than a condition to handle, so 'Age(x)' checks it and traps, and 'Age::Valid(x)' asks the question without trapping
```

## What the type's own name offers: `::` attributes

A constrained type's name is a type and not a value, so nothing is *read* from it. What it answers
are **attributes**, written with `::` rather than `.` so they stay out of the member namespace:
`Age::First` cannot be confused with a field, a property or an associated function, and no `impl` can
shadow one by declaring a member of that name.

The set is small and closed, and every member of it is a question about **integer bounds**:

| written | is | notes |
|---|---|---|
| `T::First` | the lower bound | a `T`, a constant, no argument |
| `T::Last` | the upper bound | a `T`; one *below* the written bound where the range is exclusive |
| `T::Valid(x)` | whether `x` is in range | takes the **base**; a `bool`, and **total** — it never traps |
| `T::Succ(x)` | the next value | a `T` from a `T`; traps at `T::Last` |
| `T::Pred(x)` | the previous value | a `T` from a `T`; traps at `T::First` |
| `T::Range` | the range itself | only as a `for` loop's iterable, `First..Last` inclusive, the variable a `T` |

```sysl
type Slot = new u8 within 0..<8

print(u8(Slot::First), u8(Slot::Last), Slot::Valid(3u8), Slot::Valid(9u8))
print(u8(Slot::Succ(Slot(3))), u8(Slot::Pred(Slot(3))))

var total = 0

for k in Slot::Range do total += int(u8(k))

print("sum of every slot:", total)
```

```output
0 7 true false
4 2
sum of every slot: 28
```

**Every attribute but `Valid` speaks the subtype.** A bound of `T` is a value of `T`, the step from
one `T` is another, and the values `Range` walks are `T`s. That is invisible on a transparent
subtype, where `T` and its base agree anyway — it is what makes the set usable on a **derived** one,
which would otherwise be the only kind of type whose own attributes had to be cast back into it. A
`new` subtype exists to stay out of its base's traffic, and an attribute surface that handed back the
base would make its own declaration the thing you had to undo to use it.

**`Valid` is the exception, and the asymmetry is its job.** It takes the base, because asking whether
a value is a `T` is only a question about something that is not one yet. Handed a `T` it is refused,
since the answer could only be yes:

```sysl
type Slot = new u8 within 0..<8

print(Slot::Valid(Slot(3)))
```

```error
'Slot::Valid' takes a byte, not Slot
```

The numbers it can be asked about are therefore the ones the **base** can hold, so a subtype over a
`u8` cannot be asked about `-1`. That is not a narrowing of the question; it is the base's range being
what a base is.

`Valid` is the answer to "how do I ask instead of trapping". A produce site traps because a value
outside the range is a mistake and not a condition; `Valid` is how a program holding a number it has
not vetted puts the question, and a cast after a `Valid` that answered true is the ordinary way in.

`Succ` and `Pred` **trap** rather than saturating or wrapping, on the same argument: a step past the
end is not a value of the type, so making one is the mistake, and no single answer would be right for
every caller.

Two shapes are refused. `Range` is meaningful only where an iterable is — it names nothing a program
can hold, because a range is not yet a type a program can name:

```sysl
type Slot = new u8 within 0..<8

var r = Slot::Range

print(r)
```

```error
'Slot::Range' is only meaningful as the iterable of a 'for' loop
```

And a subtype over a **float** or a `char`, or one written with **no range**, has no attributes at
all — each of these is a question about integer bounds, and there are none to ask about. The
diagnostic names the part that would have to change:

```sysl
type Ratio = new f64 within 0.0..1.0

print(f64(Ratio::First))
```

```error
'Ratio::First' needs an integer subtype, not real
```

```sysl
type Plain = new int

print(Plain::Last)
```

```error
'Plain::Last' needs a 'within' range
```

## Struct invariants

A struct body may carry `invariant <bool>` clauses among its fields. The clause is an expression over
the struct's own fields, in scope by name, and several clauses all have to hold — each checked
independently, so the diagnostic names the one that failed:

```sysl
struct Window
    lo: int
    hi: int
    invariant lo <= hi
    invariant hi - lo < 4096

var w = Window(2, 10)

w.hi += 5

print(w.lo, w.hi, w.hi - w.lo)
```

```output
2 15 13
```

An invariant may read a module constant. `invariant` is contextual here too, and the grammar
disambiguates by shape: a **member** declaration has a parameter list or a `->`, an **invariant
clause** is the word followed by an expression, and anything else is a **field** — so a field may
still be named `invariant`, as the `Cfg` example above showed, since `invariant: int` matches neither
of the first two.

### Checked at every write, not only at construction

That is the part worth stating, because the cheap implementation checks the constructor and calls it
done. The obligation covers:

- constructing the struct, including one built directly as an argument or as a returned value
- assigning a whole struct over an existing one
- **assigning a single field**, including a compound assignment (`w.hi += 1`) and an increment
  (`w.hi++`)
- a field written through a pointer
- a field written into an array element
- a field written **inside** one of these — `o.a.n = 9`, or `g.items[0].n = 9`

That last one is owed for a reason a narrow reading misses. A clause may read *through* a field:
`invariant a.n <= b` is legal, and `a.n` is then not a field of the struct at all, so the write that
breaks it is a write the struct never sees. The obligation is therefore on **every struct a place is
written inside**, not only on the one whose field is named last, and the checks nest
innermost-first — so the smallest struct the write broke is the one that stops it. An index locates a
place rather than owning one, so it contributes no check and is walked through.

The consequence is the intended one: **a sequence of writes that ends in a valid state but passes
through an invalid one traps at the step that broke it.** There is no "I am mid-update" mode.

The [multiple assignment](/reference/expressions/#assignment) is the exception that proves it — it
lands both writes before either invariant is consulted, so a pair that ends legal is legal even where
each half alone would not be:

```sysl
struct Window
    lo: int
    hi: int
    invariant lo <= hi

var w = Window(2, 10)

w.lo, w.hi = 6, 8

print(w.lo, w.hi)
```

```output
6 8
```

The pair that needs it is the one that crosses: `w.lo = 12` followed by `w.hi = 14` traps at the
first write, because 12 is briefly above the 10 still in `hi`. `w.lo, w.hi = 12, 14` does not, and
the two writes land on a struct that was legal before and is legal after.

### When a struct cannot be updated one field at a time

Three answers, and the order matters, because the last one is the expensive one and it is the one
reached for first:

1. **Look for an order in which no intermediate state is illegal**, and often there is one. A
   `count <= high` watermark is updated by raising the ceiling before the floor, and needs nothing
   else; the same two writes in the other order are refused. That the same clause accepts one order
   and refuses the other is the whole of the answer here.
2. **Ask whether the clause is pointing at a redundant field.** An invariant relating two fields is a
   claim about the *representation*, and the trap is often the compiler observing that the struct
   carries one fact twice. A ring buffer keeping `head`, `tail` and `count` cannot move any two of
   them one at a time; one keeping `head` and `count` and computing the end has no clause to break,
   because nothing is left to disagree. Two of the three fields is the honest design either way.
3. **Otherwise assign the whole struct**, which is what whole-struct assignment is for — and know the
   price. `*self = Ring(self.buf, …)` restates the *whole* value to move two bytes, so an invariant
   across two fields makes a container's own update cost the size of the container. For a buffer,
   that is its entire storage, per element.

### What may be aliased

Everything above is discharged by walking outward through the **place** being written, so the whole
obligation rests on the place still naming the struct. A pointer is where that runs out — inside a
function taking a `*Inner`, there is no `Outer` to re-read and no way to learn there ever was one.

It is therefore a rule about **what may be aliased**, and it restricts alias *creation*, which is
local and a question about types, rather than alias *use*, which would need the borrow checker sysl
does not have. Four parts, and **none of them applies to a program that declares no invariants** —
each is a question asked about a clause, and there is no clause to ask about.

**A pointer that would be typed below a clause is refused where it is made:**

```sysl
struct Inner
    n: int

struct Outer
    a: Inner
    invariant a.n <= 10

var o = Outer(Inner(1))
var bad = &o.a

print(bad.n)
```

```error
'&' here makes a '*Inner' pointing inside Outer, whose invariant reads 'a.n' — and a '*Inner' names no Outer, so a write through it would break the clause with nothing left to re-check it against. Take the address of the Outer itself, which keeps the invariant in its type, or make the change through a method
```

**A writable view is the same licence to write by another spelling**, and is refused the same way:

```sysl
struct Inner
    n: int

struct Group
    items: [4]Inner
    invariant items[0].n <= 10

var g = Group([Inner(0), Inner(0), Inner(0), Inner(0)])
var view = g.items[0..<2]

print(view.len)
```

```error
this view may be written, and it views storage inside Group, whose invariant reads 'items.n' — a '[]Inner' names no Group, so a write through it would break the clause with nothing left to re-check it against. Take it as a '[]const Inner', which may not write, or make the change through a method
```

A `[]const T` is ordinary, since giving up the write is exactly what makes a view carry no promise it
could break. So is `&o` itself, whose `*Outer` names the struct and is checked by the ordinary walk.
And so is a pointer to a field **no clause mentions** — the refusal is about the clause, not about
the struct:

```sysl
struct Inner
    n: int

struct Outer
    a: Inner
    c: int
    invariant a.n <= 10

var o = Outer(Inner(1), 0)
var cp = &o.c

*cp = 9

print(o.a.n, o.c)
```

```output
1 9
```

**A mutating method call on such a field is allowed, and re-checks the clause when it returns.**
`o.a.set(4)` hands `set` the same severed `*Inner` — and it is allowed because the *call site* still
knows the whole place: `o` is right there, so the clause is re-run the moment the call returns:

```sysl
struct Inner
    n: int

    set(*self, v: int)
        self.n = v

struct Outer
    a: Inner
    invariant a.n <= 10

var o = Outer(Inner(1))

o.a.set(4)

print(o.a.n)
```

```output
4
```

Since there are no parameter modes, a receiver is the only way a callee is handed somewhere to write
without an `&` in the caller's own source — which is why this one channel is worth keeping open, and
why keeping it open costs a mutating method nothing.

**A `*self` method may not let a pointer into its receiver outlive the call.** That is what makes the
paragraph above sound: a `set` that returned `&self.n` would hand out the severed alias by a route no
`&` in the caller spells:

```sysl
struct Cell
    n: int

    leak(*self) -> *int = &self.n

struct Holder
    c: Cell
    invariant c.n <= 10
```

```error
a '*self' method may not let a pointer into the receiver's own storage outlive the call, and this one is returned — the receiver may be a field of a struct whose invariant reads it, and a pointer that gets out is somewhere to write that names no such struct. Hand back a copy of the value, or an index into it, and let the caller reach the storage through the receiver it already has
```

The check is local to the body, and it is asked only of methods whose struct can *be* a field of one
carrying clauses — a struct that lies inside nothing can never have a severed receiver, so its
methods are left alone. Storage on the far side of a reference or a view is not the receiver's to
lose either, so `&self.bytes[0]` where `bytes` is a `[]u8` field is ordinary.

**A clause may only read storage the struct owns.** All of the above is a rule about aliases of the
struct's own bytes, so a clause reading through a pointer, a reference, or a view's *elements* is
refused where it is written:

```sysl
struct Inner
    n: int

struct Ptr
    p: *Inner
    invariant p.n <= 10
```

```error
an invariant may only read storage the struct owns, and 'n' is read through *Inner — what is on the far side has an identity of its own, so another alias of it could break the clause with nothing left to re-check it against. Hold the value in a field of the struct, and state the invariant over that
```

A view's `len` is on the near side — the three words are stored in the struct — and may be read.

What none of this does is make a `*T` safe, and it does not try to. A pointer handed to a *third*
function that stores it is out of reach of a local check, exactly as the
[memory model](/reference/memory/) says every guarantee about a raw pointer is. What the
rule buys is that the **silent** severing — the one that looks like ordinary code, reads like
ordinary code, and leaves a clause quietly false — is refused.

### Generic structs

Invariants on a generic struct are not supported, and say so:

```sysl
struct Gen[T]
    v: T
    invariant true
```

```error
invariants on generic structs are not supported yet — 'Gen'
```

The open question is what a clause over a field of type `T` could even mean when nothing about `T` is
known. Most useful invariants compare, which needs a bound — and a bound the *struct* carries is
inherited by every member, so the machinery is there.

## Contracts on a function

```sysl
half(x: int) -> int
    require x >= 0, "a half of a negative is not what this means"
    ensure result >= 0

    x / 2

print(half(10), half(7))
```

```output
5 3
```

`require` is checked **on entry**. `ensure` is checked **before every return**, including early
ones — an early `return` that violates the postcondition traps exactly as the fall-through path
would. Both take an optional message after a comma.

**Both kinds form one block at the top of the body.** A clause of either kind after an ordinary
statement is rejected — a precondition that runs after some of the work is not a precondition, and a
postcondition is *written* with them because that is where a reader looks for what the function
promises, not because that is when it runs. Clauses may not be nested inside an inner block either:

```sysl
f(x: int) -> int
    var y = x + 1

    require y >= 0

    y
```

```error
'require'/'ensure' clauses must come before any other statement in a function body
```

### `result` and `old`

Two names exist only inside a contract.

**`result`** is the value being returned, available in an `ensure` only. It is rejected in a
`require` — there is no value yet — in an ordinary statement, and in the `ensure` of a function that
returns nothing:

```sysl
h(x: int) -> int
    require result >= 0

    x
```

```error
'result' is only meaningful inside an 'ensure' of a value-returning function
```

A local actually named `result` shadows it, which is the ordinary scoping rule rather than a special
case:

```sysl
triple(n: int) -> int
    ensure result >= 0

    var result = n * 3

    result

print(triple(4))
```

```output
12
```

**`old(expr)`** is `expr` evaluated **on entry**, available in an `ensure` only. It takes exactly one
argument, and several `old` snapshots in one function are independent of each other. This is what
lets a postcondition talk about what a mutating function *changed* rather than only about what it
left behind:

```sysl
struct Counter
    n: int
    m: int

    both(*self)
        ensure self.n > old(self.n) && self.m > old(self.m)

        self.n += 1
        self.m += 2

var c = Counter(0, 0)

c.both()
c.both()

print(c.n, c.m)
```

```output
2 4
```

Contracts work the same on a **method**, including one with a `*self` receiver, where they are at
their most useful: `ensure result > old(self.n)` on a mutating method says the thing the method is
*for*.

Like a constraint, a violated contract **traps**, and contracts are checked in **every** build. There
is no release mode that drops them, and adding one would make a program's meaning depend on how it
was compiled.

## The third shape: a latch

`Result` and a trap are the two channels for an *operation* that failed. A **stream** is the case
neither fits well, and the library answers it with a third shape — a trait whose one member reports
whether the stream has gone wrong:

```sysl
struct Meter
    written: int
    bad: bool

    put(*self, n: int)
        if n < 0 then self.bad = true
        else self.written += n

impl Fallible for Meter
    failed(*self) -> bool = self.bad

var m = Meter(0, false)

m.put(3)

print(m.failed(), m.written)

m.put(-1)

print(m.failed(), m.written)
```

```output
false 3
true 3
```

`Fallible` is required by both `Writer` and `Reader`, which is what lets one open file be both — two
traits each declaring a `failed` for one type could not be told apart at a call, because `failed`
takes no arguments and so nothing about the call could say which was meant.

**It latches rather than returning**, and that is the shape everything above it is built on: an
implementation stays straight-line, `print(x)` stays a statement, and a caller asks once after a run
of operations rather than after each. Whether a stream *ended* is a separate question from whether it
ended badly, which is why reading answers the first with an empty result and leaves this to answer
the second.

The default is `false` — most streams cannot fail, and one that cannot should not have to write down
that it cannot, so an `impl` with an empty body is a complete one.

## What is deliberately absent

| absent | instead |
|---|---|
| exceptions, `throw`, `try`/`catch` | a returned value, or an abort. There is no third, invisible control-flow channel |
| error return codes by convention | `Result`, in the type, checked — not an `int` a caller might forget to inspect |
| a `panic` that unwinds | a trap is terminal |
| a `try` on a constrained type | `T::Valid(x)`, then the ordinary cast |
| a build flag that strips checks | every check is in every build |
| static proof of a contract | it is a branch and a trap, checked where the value is produced |

That last row is the scope decision worth stating plainly. **This is not verification.** Nothing here
is proved while compiling: a `require` is a branch and a trap, an invariant is a call to a synthesised
predicate, a `within` is two comparisons. The compiler will not tell you that a call site cannot
satisfy a precondition, and it will not eliminate a check it could have proved redundant.

Static checking of these conditions is a different project with a different shape — it needs a
specification language, a notion of framing, and an answer for what happens when the prover times
out. The runtime-checked version is useful on its own terms, and what it buys is that **the failure
lands where the mistake is** — plus, for `new` types, a compile-time guarantee that has nothing to do
with the checking at all.

---

Next: [the foreign interface](/reference/ffi/).
