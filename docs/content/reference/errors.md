---
title: Errors, traps and contracts
summary: Two channels for failure — a value you handle and a stop you cannot — plus the constrained types, invariants and contracts that decide which one you get.
weight: 110
---

Failure travels on **two separate channels**, and choosing which one a given failure uses is a
deliberate design decision for every API:

- **A recoverable failure is a value.** It is a `Result[T, E]` or `Option[T]` in the return type,
  propagated with [`?`](/reference/expressions/). It is part of the signature, so the caller has to
  handle it.
- **A bug is a trap.** An index past the end, an invalid cast, a broken contract. A trap is not a
  value and cannot be caught; the program stops.

Keeping the two apart keeps signatures accurate. A function returning `Result[T, E]` tells you it can
fail in a way you handle; one returning plain `T` tells you it can only fail if the program is
already wrong.

This page covers both channels and then the three features that *produce* the second one:
constrained types, struct invariants, and function contracts. A `within` range or a `require` clause
is easiest to understand once you know what happens when it is false.

## Which channel — the policy

One question decides it: **could correct calling code ever reach this?**

| | answer | why |
|---|---|---|
| **Yes** — an expected outcome of valid use | `Result` / `Option` | The input came from outside the program — a file, a socket, a person — or the operation legitimately may not succeed. A correct caller still meets this case, so the type must force it to be handled. |
| **No** — only a bug reaches it | a trap | Indexing past the end, converting an out-of-range integer to a `char`, dividing by zero, violating a `require`. A correct program never does these; reaching one means the program is already wrong. |

With this split, signatures stay clean, with no `Result` on functions that cannot meaningfully fail,
while the failures that *can* happen are impossible to ignore, because they are in the type.

## `Option[T]` and `Result[T, E]`

Both are **ordinary generic enums declared in the standard library**, with no compiler privileges:

```
enum Option[T]                 enum Result[T, E]
    None = 0                       Ok(value: T)
    Some(value: T)                 Err(error: E)
```

- **`Option[T]` represents absence**: a value that may or may not be there, with no reason attached,
  such as a missing key, the `next` of a list's last node, or the result of a search. It is also what
  a [`weak` reference](/reference/memory/) yields when read, and what a fallible constructor like
  `char.try` returns.
- **`Result[T, E]` represents failure with a reason**: parsing, I/O, validation. `E` is whatever
  carries the reason: a string, an error enum, a `&Fail`.

They are constructed and taken apart like any enum, and their type arguments are inferred, including
in the two cases where the call itself gives nothing to infer from. A bare `None` takes its `T` from
context, and an `Ok(n)` inside a function returning `Result[int, string]` takes its `E` from the
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

print(first_even(data).unwrap_or(-1))
```

```output
half: 5
refused: odd
8
```

`E` is usually an enum rather than a string, so a caller can match on *what* went wrong instead of
parsing a message.

### The members

The **total** members ask a question or supply a fallback. None of them can stop the program:

| on `Option[T]` | on `Result[T, E]` | is |
|---|---|---|
| `is_some()` | `is_ok()` | `true` when the value is there |
| `is_none()` | `is_err()` | `true` when it is not |
| `unwrap_or(default)` | `unwrap_or(default)` | the payload, or `default` |

The **forcing** members return the payload and stop the program when there is none:

| on `Option[T]` | on `Result[T, E]` | stops when |
|---|---|---|
| `unwrap()` | `unwrap()` | `None` / `Err` |
| `expect(msg)` | `expect(msg)` | `None` / `Err`, printing `msg` |
| — | `unwrap_err()` | `Ok` |
| — | `expect_err(msg)` | `Ok` |

```sysl
var got = Some(7)
var bad: Result[int, string] = Err("no")

print(got.is_some(), got.unwrap_or(0), got.expect("set above"))
print(bad.is_err(), bad.unwrap_or(-1), bad.unwrap_err())
```

```output
true 7 7
true -1 no
```

Only `Result` has `unwrap_err`, because `Option`'s empty case carries nothing to return. Stopping on
an `Err` names the error after the panic line and prints [where it came
from](#where-a-failure-came-from).

The **transforming** members build a new value from the old one, and pass the other case through
unchanged:

| on `Option[T]` | on `Result[T, E]` | is |
|---|---|---|
| `map(f)` | `map(f)` | the payload transformed; absence or failure carried through |
| — | `map_err(f)` | the failure transformed; the payload carried through |
| `and_then(f)` | `and_then(f)` | `map` for an `f` that may itself answer nothing, so the two do not nest |
| `or_else(f)` | `or_else(f)` | another attempt where this one had nothing; `Result`'s hands `f` the failure |
| `filter(pred)` | — | the payload only where it passes |
| `unwrap_or_else(f)` | `unwrap_or_else(f)` | the payload, or one computed; `Result`'s computes it from the failure |
| `ok_or(e)` | — | `Ok(payload)`, or `Err(e)` for absence |
| — | `ok()` / `err()` | the success, or the failure, as an `Option` |

```sysl
double(n: int) -> int = n * 2
halve(n: int) -> Option[int] = if n % 2 == 0 then Some(n / 2) else None
width(e: string) -> int = int(e.len)

main()
    val some = Some(21)
    val none: Option[int] = None
    val four = Some(4)
    val bad: Result[int, string] = Err("abc")

    print(some.map(double), none.map(double))
    print(some.and_then(halve), four.and_then(halve))
    print(some.ok_or("missing"), none.ok_or("missing"))
    print(bad.map_err(width), bad.ok(), bad.err())
```

```output
Some(42) None
None Some(2)
Ok(21) Err(missing)
Err(3) None Some(abc)
```

`and_then` deserves a closer look. `map` with a function that itself returns an `Option` gives an
`Option[Option[U]]`; `and_then` gives an `Option[U]`, which is what a chain of fallible steps needs.
On a `Result` it is `?` written as an expression, for places `?` cannot go, such as a chain built as
an argument, or a body that is a single expression.

`ok_or` and `ok`/`err` convert between the two types: `ok_or` makes a lookup usable in a body that
propagates with `?`, and `ok` converts the other way when the reason no longer matters.

`unwrap_or_else` complements `unwrap_or` rather than replacing it: `unwrap_or` evaluates its argument
whether or not it is needed, which is right for a constant and wrong for anything that reads a file
or builds a string.

**`?` converts between error types through [`From`](#a-converts-through-from)**, so `map_err` is for
a transformation the type system does not know about, such as rewording a message or renumbering a
code, rather than for joining two layers of a program. Where the two error types are not related by
a `From` block, write `step().map_err(to_mine)?`.

### They are written in sysl

The forcing members stop the program with no compiler support beyond one annotation:

```sysl
@track_caller
unwrap(self) -> T = self match
    Some(v) -> v
    None -> unwrap_failed("unwrap of a None value")
```

`unwrap_failed` prints `panic:`, the words and `caller_location()`, and calls `exit(1)`. The
[`@track_caller`](/reference/attributes/#track-caller-a-function-that-reports-its-caller-s-line) on
both is what makes the location the line that called `unwrap`.

So "a bug stops the program" does not require the compiler to know every way of stopping. Two
ordinary features make it work. A diverging arm has a type, [`never`](/reference/types/), so
`exit(1)` can sit beside `Some(v) -> v` and the `match` still has the payload's type. And stopping is
an `extern` call: the library declares `exit(code: int) -> never`.

None of this costs a program that does not use it. The enums are generic, so a member exists only
where a call uses it, and an `extern` that nothing reaches is never declared in the output.

### They compare, and they render

Both implement `Eq` and `Display`, with the bounds on the **payloads** rather than on the whole
type:

```sysl
impl[T: Eq] Eq for Option[T]
impl[T: Eq, E: Eq] Eq for Result[T, E]

impl[T: Display] Display for Option[T]
impl[T: Display, E: Display] Display for Result[T, E]
```

Two values are equal when they hold the same variant and their payloads are equal. `Some` is never
equal to `None`, and **`Ok` is never equal to `Err`** however the two payloads compare; the pair
below holds a 3 on both sides. A value renders as its source spelling, and a width applies to the
field the whole value occupies rather than to its payload:

```sysl
val a = Some(166)
val n: Option[int] = None

print(a == Some(166), a == n, n == None)

val ok: Result[int, int] = Ok(3)
val bad: Result[int, int] = Err(3)

print(ok == bad)
print(a.to_string(), n.to_string(), ok.to_string(), bad.to_string())
print(f"${a}%12s|")
```

```output
true false true
false
Some(166) None Ok(3) Err(3)
   Some(166)|
```

**A `Result[T, E]` can be compared exactly when both of its type arguments can**, and the two bounds
are written separately because they are independent: a comparable payload says nothing about the
error type.

A missing bound is reported where the comparison is written rather than where the type was declared,
and the diagnostic names the type argument that lacks it:

```sysl
struct Opaque
    n: int

main()
    val a = Some(Opaque(1))
    val b = Some(Opaque(2))

    print(a == b)
```

```error
error: '==' between Option[Opaque] needs 'Eq' — the 'impl' that covers it asks 'Eq' of Opaque, which does not implement it
```

Both implementations are needed for [`assert_eq`](/library/core/) to accept these types: it is
bounded by `Eq + Display`, so either one alone would still be refused, and the diagnostic names the
trait that is missing.

### A dropped `Result` is a warning

**A call whose `Result` nobody reads produces a warning, and the program still builds.** A failure
that is never looked at is a failure that goes unseen, so a `Result` written as a statement — its
value used for nothing — is almost certainly a mistake:

```
save(path: string) -> Result[unit, IoError] = …

save("out.txt")
```

```
warning: this 'Result[unit, IoError]' is dropped unread, so a failure it carries goes
unseen — handle it ('?', 'match', '.unwrap()'), or write '_ = …' to drop it on purpose
```

**Reading the value in any way is what silences it**: `?`, a `match`, a binding, a method call such as
`.unwrap()` or `.is_ok()`, passing it to a function, returning it. Where dropping it really is
meant — a temporary file removed on the way out, where a removal that fails changes nothing — **`_ =`
drops it on purpose** ([`_` discards a part](/reference/expressions/#discards-a-part)), and the call
still runs:

```sysl
import sysl.fs.remove_file

count(n: int) -> Result[int, string]
    print(s"count $n")
    if n < 0 then Err("negative") else Ok(n)

_ = count(-1)

for i in 0..<2
    _ = count(i)

// Already gone or not, there is nothing left to do about it.
_ = remove_file("no-such-scratch-file.tmp")
```

```output
count -1
count 0
count 1
```

The rule follows the position, not the form, as [statement position](/reference/expressions/#statement-position-discards-a-block-s-value)
does: a branch of an `if` or an arm of a `match` in statement position, a loop body, a `defer`ed
call and the last line of a function returning nothing each drop what they compute, and each is
told at the call. A generic body is told once, where it is written, when what it drops is a
`Result` there — `Result[T, E]` included — and never because a caller instantiated its `T` as one.
There is no switch to silence the warning; `_ =` is the spelling for meaning it.

## `?`

The [expressions page](/reference/expressions/) gives `?` its place in the grammar: postfix, at the
tightest level, alongside `.` and `()`. Its rules in full:

**On a `Result`,** `Ok(v)` evaluates to `v` and `Err(e)` returns `Err(e)` from the enclosing function
immediately. **On an `Option`,** `Some(v)` evaluates to `v` and `None` returns `None` immediately.

**The enclosing function's return type must be able to carry the failure**, since the early return
needs somewhere to go:

```sysl
half(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n / 2) else Err("odd")

count(n: int) -> int
    half(n)?
```

```error
'?' may only be used in a function returning Result, not int
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
'?' may only be used in a function returning Option, not Result[int, string]
```

**An early return by `?` runs the block's deferred statements**, as any `return` does, and is a
failure: an [`errdefer`](/reference/statements/#errdefer) written above it runs too, undoing the
steps the function had already taken.

### A `?` converts through `From`

**Where the two error types differ, `?` converts through [`From`](/library/core/).** A function with
an error type of its own writes `impl From[Theirs] for Mine` once, and every `?` across that boundary
uses it, so the two layers are joined in one block rather than at every call that crosses between
them.

```sysl
enum Io
    NotFound

enum Fault
    Disk(cause: Io)
    Parse(what: string)

impl From[Io] for Fault
    from(value: Io) -> Fault = Fault.Disk(value)

open(ok: bool) -> Result[int, Io] = if ok then Ok(7) else Err(Io.NotFound)

read(ok: bool) -> Result[int, Fault]
    val n = open(ok)?

    Ok(n * 2)

describe(r: Result[int, Fault]) -> string = r match
    Ok(n) -> "ok " + n.to_string()
    Err(Fault.Disk(_)) -> "disk"
    Err(Fault.Parse(s)) -> "parse " + s

print(describe(read(true)), describe(read(false)))
```

```output
ok 14 disk
```

**The parameter is the source and `Self` is the destination**, which lets a type accept conversions
from types it does not own: the block is written beside `Fault`, and `Io` does not need to know about
it. A type may implement `From` several times (`From[int]` and `From[real]` for one type are two
blocks), so an error type may accept errors from every layer beneath it.

**A generic error type is joined by a generic block**, which converts into every instantiation at
once. A `?` finds it as `Wrap[Io].from(e)` would: made real at the destination's own arguments, so
the source may be a fixed type, a type parameter, or an associated type read off a bound
(`Wrap[S::Error]`).

```sysl
enum Io
    NotFound

enum Wrap[E]
    Cause(inner: E)
    Timeout

impl[E] From[E] for Wrap[E]
    from(e: E) -> Wrap[E] = Wrap.Cause(e)

open(ok: bool) -> Result[int, Io] = if ok then Ok(7) else Err(Io.NotFound)

lift[E](r: Result[int, E]) -> Result[int, Wrap[E]]
    val n = r?

    Ok(n + 1)

read(ok: bool) -> Result[int, Wrap[Io]]
    val n = open(ok)?

    Ok(n * 2)

print(read(true).is_ok(), read(false).is_err(), lift(read(true)).unwrap_or(0))
```

```output
true true 15
```

A block with a condition (`impl[E: Show] From[E] for Wrap[E]`) converts only where the condition
holds, and a `?` where it does not is told which bound the source lacks. Where **two** blocks would
both take the source (`impl[E] From[E]` and `impl[E] From[string]`, meeting at `Wrap[string]`), the
`?` is refused as ambiguous rather than choosing one; convert with `map_err` first. A generic type
cannot take a block for one instantiation, so a missing conversion into one is told the generic block
to write:

```sysl
enum Wrap[E]
    Cause(inner: E)
    Timeout

step() -> Result[int, string] = Err("disk")

outer() -> Result[int, Wrap[string]]
    val n = step()?

    Ok(n)
```

```error
so 'impl[E] From[E] for Wrap[E]' is what joins the two layers
```

Where there is **no** such block, the `?` is refused, and the diagnostic names the block to write:

```sysl
half(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n / 2) else Err("odd")

other(n: int) -> Result[int, bool]
    var v = half(n)?

    Ok(v)
```

```error
a '?' converts through 'From', so 'impl From[string] for bool' is what joins the two layers
```

An `Option`'s `?` has nothing to convert: `None` carries nothing, so the two enums must still be the
same, and the rule above that the channels do not cross still applies.

A `?` whose error types already agree makes no conversion call at all.

**`?` is an expression**, so its unwrapped value flows directly into the surrounding expression:
`Ok(mk()?)` is ordinary, and a chain of steps is written as a sequence of `?`-bound locals.

### `?` and the memory model

`?` follows [ARC](/reference/memory/) with no special rule. This matters here, because `?` is the
operator most likely to carry a heap payload across a function boundary.

**Unwrapping a `&T` success payload retains it beyond the wrapper**, so the reference outlives the
`Result` it came from and is released exactly once:

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

**Propagating a `&T` error payload moves it through the early return** with its reference count
intact, and it is freed exactly once on whichever path consumes it.

Neither is a special rule for `?`. Both follow from retain-on-alias and release-at-scope-end, and
they are spelled out here because a hand-written C error return typically leaks or double-frees at
exactly these two boundaries.

## Where a failure came from

An error says *what* went wrong. **The error return trace says where**: the `Err` that made the
failure, and every `?` that handed it one frame further up on its way to the code that reported it.

```sysl
import sysl.debug.print_error_trace

lookup(k: string) -> Result[int, string] = Err(s"no $k")

load(k: string) -> Result[int, string]
    val n = lookup(k)?
    Ok(n + 1)

load("port") match
    Ok(n) -> print(n)
    Err(why) ->
        print("failed:", why)
        print_error_trace(stdout())
```

```output
failed: no port
error return trace (origin first):
  main.sysl:3:44 in lookup
  main.sysl:6:22 in load
```

**`main` returning an `Err` prints the trace under its `error:` line**, on standard error, so a
program that lets `?` carry its failures to the top reports where each one began without writing
anything for it:

```sysl
lookup(k: string) -> Result[int, string] = Err(s"no $k")

load(k: string) -> Result[int, string]
    val n = lookup(k)?
    Ok(n + 1)

main() -> Result[unit, string]
    print(load("port")?)
    Ok(())
```

exits with status 1, having written

```text
error: no port
error return trace (origin first):
  main.sysl:1:44 in lookup
  main.sysl:4:22 in load
  main.sysl:8:23 in main
```

### What records a site

- **A `?` on a `Result` that takes its failure arm** records the position of the `?`, after any
  [`From` conversion](#a-converts-through-from) — the conversion is part of the hop, not a frame of
  its own.
- **An `Err(...)` a function hands back as its result** is the origin: the body's value, the value
  of a `return`, or an arm of an `if` or a `match` in either place, as deeply as they nest. A
  `Result` computed earlier and returned by name records nothing, since nothing marks it as new.
- **A `?` on an `Option` records nothing.** `None` is absence rather than failure — it is the
  ordinary flow of every parser — and no report prints an `Option`.

A site is `file:line:column in function`. A method is named under its type (`Config.load`) and a
closure after the function it was written in (`load.<closure>`). **The site is a constant laid down
beside the code, never a return address**, so a trace reads the same at every optimization level and
prints on a board with nothing to symbolize it against.

### A failure that is handled leaves the trace

The innermost statement holding a call to something answering a `Result`, where the answer is not
handed on with `?` or `return`, **puts the trace back as it found it when it completes**. The failure
it handled is gone before the next one starts:

```sysl
import sysl.debug.error_trace

parse(s: string) -> Result[int, string] = if s == "" then Err("empty") else Ok(1)

first_of(a: string, b: string) -> Result[int, string]
    parse(a) match
        Ok(n) -> return Ok(n)
        Err(_) -> print("no first; the trace holds", error_trace().len())

    print("trying the second; the trace holds", error_trace().len())
    val n = parse(b)?
    Ok(n)

print(first_of("", "x").unwrap())
```

```output
no first; the trace holds 1
trying the second; the trace holds 0
1
```

A `return` from inside such a statement is not completing it, so
`Err(e) -> return Err(Wrapped(e))` keeps the chain it is wrapping and adds its own origin to it. A
`break` or a `continue` does complete it. The one gap the rule leaves is a result put in a `val` on
one line and returned on a later one: the statement holding the call completed in between.

**A function that answers without failing leaves the trace as it found it**, however it answers: a
`return` of `Ok` -- or of anything, from a function answering no `Result` -- from inside a handling
statement, a last expression that handles a failure and answers `Ok`, and a tail call made from inside
a handling statement. A caller handing the answer on with `?` prunes nothing, so a failure the callee
handled never reaches the trace of a later, unrelated one:

```sysl
import sysl.debug.error_trace

parse(s: string) -> Result[int, string] = if s == "" then Err("empty") else Ok(1)

or_zero(s: string) -> Result[int, string] = parse(s) match
    Ok(n) -> Ok(n)
    Err(_) -> Ok(0)

total(a: string, b: string) -> Result[int, string]
    val x = parse(a) match
        Ok(n) -> n
        Err(_) -> return Ok(-1)

    Ok(x + or_zero(b)?)

main() -> Result[unit, string]
    print(or_zero("")?, error_trace().len())
    print(total("", "x")?, error_trace().len())
    print(total("x", "")?, error_trace().len())
    Ok(())
```

```output
0 0
-1 0
1 0
```

**That is the whole cost on a path that succeeds**: a read of the trace's length before such a
statement and a write after it, and a write at each way out of the function taken from inside one. A
`?` whose answer is `Ok` costs nothing, and neither do the statements around a handling one -- a loop
over instructions whose one handling arm handles in a statement of its own pays on that arm alone,
never once a turn.

### Thirty-two sites, per thread

**The trace is one fixed ring per thread**, of thirty-two sites and a count, and recording allocates
nothing. A chain longer than that keeps its first thirty-two — the origin end is where the bug is —
and counts the rest:

```text
  main.sysl:5:25 in down
  … and 9 more
```

A task records into the trace of the thread running it, and an `await t?` that fails is a hop at the
`await`. On a freestanding target there is no thread-local storage under it: the trace is the store
a hook answers, and the default hook answers a pointer, `sysl_error_trace_current`, which starts at
one static store laid down in the image, so a program with one flow of control has one trace and
nothing to set up.

### A trace per task

A kernel that switches tasks gives each one a trace of its own, so two tasks failing in turn never
read each other's sites. **`ErrorTraceStore` is a trace's storage**, with its size known while
compiling, so it sits in a task's control block like any other field; **`use_error_trace(s)` makes
`s` the trace every failure records into and every report reads**, and answers the one that was
current. A kernel calls it on each context switch, handing in the incoming task's store:

```sysl
import sysl.debug.{print_error_trace, use_error_trace, ErrorTraceStore}

struct Task
    name: string
    trace: ErrorTraceStore

open(n: int) -> Result[int, string] = Err(s"no file $n")

load(n: int) -> Result[int, string]
    val v = open(n)?
    Ok(v)

var a = Task("a", ErrorTraceStore.new())
var b = Task("b", ErrorTraceStore.new())

val own = use_error_trace(&a.trace)

load(1) match
    Ok(_) -> ()
    Err(_) ->
        use_error_trace(&b.trace)

        open(2) match
            Ok(_) -> ()
            Err(_) ->
                print(b.name)
                print_error_trace(stdout())

        use_error_trace(&a.trace)
        print(a.name)
        print_error_trace(stdout())

use_error_trace(own)
```

```output
b
error return trace (origin first):
  main.sysl:7:39 in open
a
error return trace (origin first):
  main.sysl:7:39 in open
  main.sysl:10:20 in load
```

Task `a` is switched out with its failure in hand, `b` fails and reports only its own site, and `a`
reports its whole chain when it is switched back in.

- **`null` names the program's own trace** — the thread's own on a hosted target — and is what a
  kernel hands back when it returns to code that runs on no task. What `use_error_trace` answers is
  never `null`.
- **`ErrorTraceStore.new()` is an empty store** and `init()` empties one in place, which is done
  before a store is made current, never to the current one. What a store holds is read only through
  `sysl.debug` while it is current.
- **On a freestanding target the switch is one store of a pointer**, and recording or pruning pays
  one call to the [hook](#a-trace-per-core-or-inside-the-task-s-own-map) that answers it — never a
  `?` that succeeds. A kernel's switch code written in assembly may write
  `sysl_error_trace_current` itself. On a hosted target the trace stays the thread's own object, so
  recording pays nothing for the switch: `use_error_trace` copies the live trace out to the store
  that was current and the new one in.
- **An interrupt handler records into whatever trace is current** when it fires, and a failure it
  handles leaves that trace as it found it. One that wants its failures kept apart makes a store of
  its own current on entry and puts the previous one back on the way out.
- With [traces off](#switching-it-off) none of this exists: `use_error_trace` does nothing and
  answers `null`, and no symbol of the trace is linked.

### A trace per core, or inside the task's own map

**On a freestanding target the trace is the store `sysl_error_trace_here()` answers**, a weak hook
called on every failure path that records or prunes and by every read of the trace. The default
answers `sysl_error_trace_current`, the pointer `use_error_trace` sets, which is all a kernel with one
core and no memory protection needs. One image-wide word is not enough everywhere: on two cores each
core's context switch overwrites the other's choice, and behind an MPU unprivileged code cannot read
a word in the kernel's data — nor can the word be mapped read-only to the task, since the kernel's
own write on the switch then faults. **A board or an OS supplies its own hook** under the same
symbol, exported from sysl or written in its C, and the link keeps it in place of the default:

```sysl build=c target=thumbv7em-freestanding
import sysl.debug.ErrorTraceStore

var traces: [2]ErrorTraceStore = [ErrorTraceStore.new(), ErrorTraceStore.new()]

// The board's own: which core is running (on an RP2350, a read of SIO's CPUID register).
extern "board_core_id" core_id() -> u32

@export("sysl_error_trace_here")
trace_here() -> *ErrorTraceStore = &traces[core_id() & 1]
```

An OS behind an MPU answers instead the store it keeps at the top of the running task's stack,
inside the region the task may touch.

- **The hook is called from anywhere a `?` runs** — a task, an interrupt handler, unprivileged code —
  and may be called often: once per failure recorded, and twice per statement that handles one.
- **It must not fail, allocate, or use `?`**, and it must answer a valid store every time, never
  `null`. It runs inside the recording, so a failure of its own would record through itself.
- **`use_error_trace` is the default hook's setter.** Beside a hook of the program's own it still
  sets `sysl_error_trace_current` and answers what it held, and nothing records into that store unless
  the hook reads the pointer: which store is current is the OS's to decide.
- **On a hosted target the hook is never asked**: the trace is the thread's own object, as above.
- With [traces off](#switching-it-off) the default hook is not linked, and nothing calls one.

### `unwrap` and `expect` show the error, then the trace

`unwrap` and `expect` of an `Err` say the error after the panic line, in its `Debug` form, and then
print the trace on standard error before the program exits with status 1. **`Debug` rather than
`Display`** because every type has one that writes straight into the stream, while a `Display` may
build a string — and an `unwrap` has to stay callable from an [`@no_alloc`
module](/reference/modules/):

```sysl
parse(s: string) -> Result[int, string] = if s == "" then Err("empty input") else Ok(1)

print(parse("").expect("a number"))
```

prints `panic: a number: "empty input" (main.sysl:3:22)` — the line that called `expect`, since it
is [`@track_caller`](/reference/attributes/#track-caller-a-function-that-reports-its-caller-s-line)
— and then

```text
error return trace (origin first):
  main.sysl:1:59 in parse
```

A [`@test` that answers with a `Result`](/reference/attributes/#a-test-passes-by-returning) and
returns an `Err` reports the same way.

### Reading it from a program

`sysl.debug` reads the current thread's trace:

| | |
|---|---|
| `error_trace() -> ErrorTrace` | a copy of it, taken without allocating |
| `ErrorTrace.len()`, `.dropped()`, `.is_empty()` | the sites kept, the ones counted past them, whether there are none |
| `ErrorTrace.at(i) -> ErrorSite`, `for site in trace` | a site: `file`, `line`, `col` and `function`, origin first |
| `print_error_trace(w: *Writer)` | writes it to `w` in the form `main` prints it in |
| `clear_error_trace()` | empties it — what a loop that logs each failure it handles does before the next |
| `ErrorTraceStore`, `use_error_trace(s) -> *ErrorTraceStore` | a trace's storage, and making one current — [a trace per task](#a-trace-per-task) |

On a freestanding target the reports print nothing: they call `sysl_error_trace_report`, a weak
function, which a program replaces with its own under the same symbol
(`@export("sysl_error_trace_report")`) to write the trace wherever its board writes text, reading it
through `sysl.debug`.

### Switching it off

The trace is on at every optimization level. `error_traces = false` in the
[manifest](/reference/packages/#leaving-the-error-return-trace-out), or `--no-error-traces` for one
build, removes it entirely: no site reaches the image, nothing is recorded or put back, and `main`,
`unwrap` and `expect` report the error alone. With it on, the cost on a path that does not fail is
two calls per statement that handles a failure — one to read the trace's length before it, one to
put it back after; a `?` that succeeds and an `Option`'s `?` cost nothing.

## Traps

A trap is the other channel: the runtime response to a **broken invariant**. Its semantics are
fixed:

**A trap aborts.** There is no unwinding, no stack cleanup, no `catch`, and no exceptions. When a
check fails, the program stops; it does not run destructors up the stack, and it cannot be
intercepted and resumed.

There are two reasons this is the right choice for the language's targets. **Kernels and embedded
targets have no unwinding runtime**: landing pads, a personality routine and per-frame cleanup tables
are machinery that a freestanding `no alloc` target does not have and does not want, and an abort
needs none of it. The second is **determinism**: an abort is one code path, whereas unwinding is a
second, invisible control-flow graph under which every function in the program would have to be
correct. Removing it removes the whole "is this exception-safe?" class of reasoning, the same
simplification the memory model makes by having no move semantics.

**A trap does not run deferred statements either.** [`defer`](/reference/statements/#defer) is the
language's scope-exit form, and a trap is not an exit: it is the program stopping because its own
state is already wrong. Running cleanup code against that state risks writing the corruption out
before the program stops. So `defer` is for releasing a resource, not for restoring an invariant.

### What traps

| source | example |
|---|---|
| an out-of-bounds array or slice index | `xs[5]` on a `[3]int` |
| an index past a standard container's count | `b[5]`, `b.at(5)`, `b.remove(5)` on a three-element `Buf`; likewise `Frozen`, `Deque`, `Ring` |
| an inverted or out-of-range slice range | `xs[3..1]`, `xs[0..9]` |
| a checked cast that fails | `char(u)` on an invalid scalar, `Color(n)` on an undeclared discriminant, a bitfield's enum field holding one — each enum form has a fallible twin, `Color.try(n)` and `Color.try(s.field)`, [below](#turning-a-trap-back-into-a-value) |
| an integer divide by zero | `n / 0`, `n % 0` |
| a violated `require` or `ensure` | below |
| a violated struct `invariant` | below |
| a violated `within` range or `where` predicate, at a produce site | below |
| `T::Succ` past `T::Last`, `T::Pred` below `T::First` | below |
| overflow of `+`, `-`, `*` where an operand came through a ranged type | below |
| in a **debug build**, overflow of a plain `+`, `-`, `*`, a unary `-`, or a written integer conversion | `i8(200)`, `x * 2` past the width |

**A build is debug or release, and the one difference is integer overflow.** `sysl test` and `sysl
run` build in debug; `sysl build` and every other command build in release; `--debug` and
`--release` say otherwise for any of them ([the command line](/getting-started/cli/#debug-and-release)).
In a debug build an overflow of a plain operator is a bug, and it stops the program with a report. In
a release build the same operation [wraps at the declared width](/reference/types/#arithmetic-wraps),
which is defined behaviour — never undefined, at any optimization level. The mode is not the
optimization level: a debug build may be optimized, and a release build may be built at `-O0`.

```sysl
main()
    var level: u8 = 250

    print(level +% 10)
    level += 10
    print(level)
```

```output trap
4
```

`+%` says the wrap in its spelling, so it wraps in every build; the plain `+=` is checked, and the run
above — the site runs its examples as `sysl run` does, in debug — stops with `integer overflow: u8
+= u8 (main.sysl:5:5)` on standard error — the types are named as the program declared them (`u8`
here, `byte` where the binding says `byte`, an alias by its own name). Built with `sysl build` it prints `4` twice. Where the wrap
is the point, `+%` `-%` `*%` say so; where the answer should stop at the end of the range, `+|` `-|`
`*|` ([wrapping and saturating operators](/reference/expressions/#wrapping-and-saturating-operators)).

**No build option removes any other check, and this is the one stated exception.** There is no
`--no-contracts`, no bounds-check stripping, no `NDEBUG`: bounds checks, contracts, invariants and a
ranged type's checks — including the ranged-type overflow row above — are in every build, because a
switch that removed one would make a program's meaning depend on how it was compiled. Overflow of a
plain operator is the exception because both of its meanings are defined: a release build gives the
two's-complement answer, and a debug build refuses to give one. A program whose output differs
between the two has a bug a debug run reports.

**A trap while a constant is being evaluated is a compile error.** A
[`const` function](/reference/declarations/#a-const-function-runs-while-compiling) called where a
constant is expected is run by the compiler, and every source in the table above stops that run as
it would stop the program. The constant is refused at the call, with the trap's message, where it
happened, and every call the evaluation was inside:

```sysl
const per(n: int, k: int) -> int = n / k

const buckets(n: int, k: int) -> int = per(n, k) + 1

const steps = buckets(1024, 0)

print(steps)
```

```error
'steps' traps while it is being compiled: integer division by zero
```

The program never runs, so it prints nothing. The full message names the division, then each call
the evaluation was inside (`inside 'per(1024, 0)'`, `inside 'buckets(1024, 0)'`), with the location
of each call.

### What stopping looks like

The *decision* to stop belongs to the language; the *action* taken on stopping belongs to the
environment. Under the `os` capability a hosted program stops the process and exits non-zero; a
kernel installs its own panic handler and enters that.

On a hosted target there are three observably different outcomes, and it helps to know them before
you debug one:

| stopped by | what you see | exit status |
|---|---|---|
| a **bounds check** — an index, a range, a substring cut inside a character, an index past a container's count — or an **integer overflow** check | everything the program printed before it, and one line on stderr saying what failed and where | the platform's signal status for a trap instruction |
| any other **compiler-inserted check** — a cast, a constrained range, a contract — or `trap()` | everything the program printed before it, and nothing more | the same |
| a **library forcing member** — `unwrap`, `expect` | `panic: <message> (file:line:column)` on stdout, naming the line that called it, after everything the program printed before it | 1 |

**A trap flushes standard output before it stops the program.** Standard output is buffered (fully
buffered when it goes to a file or a pipe rather than a terminal), so what a program printed may
still be in the buffer when a check fails. Every trap, from the compiler's checks and the library's
`trap()` alike, goes through one runtime function that flushes every open output stream and then
executes the trap instruction. The exit status is the trap's, and a program that printed diagnostics
up to the failing line shows all of them:

```sysl
main()
    val xs = [1, 2, 3]
    var i = 0

    loop
        print(s"element $i is ${xs[i]}")
        i += 1
```

```output trap
element 0 is 1
element 1 is 2
element 2 is 3
```

All three lines reach standard output, and the trap follows them: the program exits with status 133,
after writing `index 3 out of bounds for length 3 (main.sysl:6:35)` to standard error (see below).
The block above is marked `output trap` rather than `output` because the site's test suite runs the
program and checks both what it printed and that it ended with a trap.

The flush happens once: a trap raised during the flush goes straight to the instruction, so a stream
in a state that makes flushing fail cannot cause a loop. **On a freestanding target there is nothing
to flush**: its `putchar` is whatever the board supplies and buffers nothing a trap could lose, so a
trap there is the instruction alone, and a program that never prints links no C stream function at
all.

**A bounds check reports what failed before it traps**, writing directly to standard error so that
the message survives the trap:

```text
index 5 out of bounds for length 3 (main.sysl:2:8)
range 2..<9 out of bounds for length 3 (main.sysl:2:8)
range start 3 is past its end 2 (main.sysl:2:8)
range end 2 is not on a character boundary (main.sysl:2:8)
variant 'Shape.Circle' was asked for, and the value is 'Shape.Dot' (main.sysl:9:18)
```

The last is [`x.(c)`](/reference/reflection/) reading the payload of a variant the value does not
hold; it goes through the same function, which receives the two names as NUL-terminated strings. So
does [`T::Make(p)`](/reference/reflection/#building-a-value-a-field-at-a-time) made from a partial
value with a field still `None`: `'P' was made from a partial value whose field 'P.y' is None
(main.sysl:8:8)`. So does an integer overflow, naming the operation and its types — `integer
overflow: int * int (main.sysl:3:13)`, `integer overflow: -i8`, `integer overflow: u8(int)`; inside
a [`@track_caller`](/reference/attributes/#track-caller-a-function-that-reports-its-caller-s-line)
function it names the caller's line, as a bounds check there does. On a freestanding target the
report needs no allocator and no formatter: the text is a constant, and the trap follows.

**The standard containers report through it too.** An index past a `Buf`'s, `Frozen`'s, `Deque`'s or
`Ring`'s count — by subscript, `at`, `set` or `remove` — is reported as an array's index is and then
traps; an `insert` past the count says `insertion index 5 is past length 3`. **Their report names
the caller's line, as an array's does**: the members are
[`@track_caller`](/reference/attributes/#track-caller-a-function-that-reports-its-caller-s-line), so
`b[5]` on line 4 reports `index 5 out of bounds for length 3 (main.sysl:4:6)`, the bracket's column
as an array's report gives it, and `b.at(5)` names the call.

The location is the bracket's line and column, with the file named by its path inside the build (a
fetched package's file by its coordinate), so no build directory appears in it. The exit status is
still the trap's, so a [`should_trap`](/reference/attributes/#a-test-passes-by-returning) test
recognises it, and can also check the message. Every failure goes through one weak function,
`sysl_bounds_fail`, which a program may replace with an `@export` of its own, for example on a board
that writes the report to a UART; the trap still follows whatever it does. On a freestanding target
the default writes nothing and returns, so the trap is what stops the program. The locations cost
one string per check in the image; `bounds_locations = false` in the manifest, or
`--no-bounds-locations` on the command line, leaves them out and keeps the values:

```text
index 5 out of bounds for length 3
```

Whether every other check should also report why it stopped is an open design question.

### Running out of stack, which is the third shape

**A hosted program installs a handler for the fault an exhausted stack raises**, and reports which of
two things happened rather than stopping silently:

```
sysl: this program has overflowed its stack -- a recursion with no base case, or a walk over a
structure that contains itself
```

```
sysl: this program faulted on an address it does not own
```

The choice is made by **where the faulting address lies**: just below the stack, or anywhere else,
as Rust, Go and Java also do. When the classification errs, it errs toward the second message, so
the first can be trusted.

**Output the program had already printed is preserved.** The handler flushes standard output before
it stops, so a program that printed twenty lines before overflowing shows all twenty, rather than
losing them with the buffer. The flush is attempted **after** the message, because flushing is not
async-signal-safe: a fault raised inside the output machinery itself would deadlock, and by then the
diagnostic has already been written.

**A freestanding target installs nothing**, having no signals to catch. Neither does an archive built
with `sysl build-c`: how a C project sets up its own entry point is up to that project.

## Turning a trap back into a value

There is no recoverable `panic`, so a program that must survive bad input has to **avoid the
operation that traps**: check the bound, validate before dividing, and use the fallible constructor
rather than the checked cast. Do this wherever untrusted input enters the program; it is where a
potential bug becomes a value instead:

```sysl
enum Color: u8
    Red
    Green
    Blue

parse(b: u8) -> Result[Color, string]
    Color.try(b) match
        Some(c) -> Ok(c)
        None    -> Err("byte " + b.to_string() + " is not a Color")

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

`Color(9)` would have trapped. `Color.try(9)` returns a `None` that this function turns into a
reason, so a byte read from the wire is a value the program handles rather than a bug.

**The same form reads a bitfield's enum field fallibly.** Reading the field is a checked cast and
traps on bits that match no variant; passed directly to `try`, the field's bits are read unchecked
and validated:

```sysl
import sysl.raw.ptr_cast

enum Ec: u6
    Unknown = 0x00
    Brk = 0x3c

@packed
struct Esr
    low: u26
    ec: Ec
    high: u32

var raw: u64 = 0x2a << 26
val e: *Esr = ptr_cast(&raw)

print(Ec.try(e.ec).is_none())
```

```output
true
```

[`attributes.md`](/reference/attributes/#bitfields-an-in-field-in-exactly-n-bits) has the field
rule. A value of the enum obtained any other way is already one of its variants, so `try` accepts
only an integer or such a field.

For a constrained type the equivalent is `T::Valid`, below.

## Constrained types

One declaration form with three independent parts:

```
type Name = [new] Base [within lo..hi] [where predicate]
```

`new`, `within` and `where` are **contextual keywords** and ordinary identifiers everywhere else, so a
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

Each of the three parts is optional, and leaving out all three is a form of its own:

| written | means |
|---|---|
| `type Meters = new f64` | a distinct type over `f64`, no constraint |
| `type Age = int within 0..150` | `int` with a range, but the *same type* as `int` |
| `type Even = int where value % 2 == 0` | `int` with a predicate |
| `type Slot = new u8 within 0..<200` | distinct **and** constrained |
| `type Alias = int` | a [type alias](/reference/declarations/#type-declarations) — no new type at all |

The last row differs in kind from the four above it. Adding nothing to the base declares no new type:
it is a second name for an existing one, and a value passes between the two names with nothing
emitted and nothing checked, because there is only one type.

```sysl
type Alias = int

var n: Alias = 1

print(n + 1)
```

```output
2
```

The rest of this section is about the other four rows, starting with the clearest difference: an
alias's base may be **any** type, and a constrained subtype's may not.

**A constrained subtype's base must be a scalar**: an integer, a float, a `char`, or a
[fixed-point type](/reference/fixed-point/#a-range), whose `within` bounds are decimals:

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
[range expressions](/reference/expressions/#ranges). A bound is a **constant expression**: a
literal, a `const`, or arithmetic over them, folded by the same code that folds an array length and
an enum discriminant, so all three positions accept the same expressions:

```sysl
const max_tasks = 4

type Task = int within 0..<max_tasks
type Trimmed = int within 0..max_tasks - 2

var t: Task = 3
var u: Trimmed = 2

print(t, u)
```

```output
3 2
```

This lets one constant define both `within 0..<max_tasks` and `[max_tasks]Task`. A bound may not be
**non-constant**: a module-level `val` is read-only storage with an address rather than a constant,
and is refused here.

Two forms are rejected at the declaration. A bound outside the base's own range:

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

A bound that folds to a float is ordered like a literal one, so `f64 within 2.0..(0.5 * 2.0)` is an
inverted range. A bound whose folding itself fails, such as `1/0`, is refused with the folder's
message, at the division: *"a constant divided by zero"*.

### A float range may end at an infinity

Over a float base, a bound may be `infinity` or `-infinity`, the
[constants no literal spells](/reference/types/#infinity-and-nan), which is how a range open at one
end is written. **An infinite bound is still a check**: every comparison with a NaN is false, so even
`-infinity..infinity` refuses exactly one value, and `0..<infinity` refuses `infinity` itself.

```sysl
type Distance = real within 0..infinity
type Finite = real within -infinity..<infinity

var far: Distance = infinity
var d: Finite = -1.0e300

print(far > 1.0e308, d < 0)
```

```output
true true
```

A NaN assigned to either type is refused where it is produced, like any out-of-range value: the
program traps there. **A NaN bound is refused at the declaration**, since a range ending at a NaN
admits no value at all:

```sysl
type Broken = real within 0.0..nan

print(1)
```

```error
a 'within' bound may not be NaN — every comparison with one is false, so a range ending at a NaN admits no value
```

An integer or `char` range has no infinity to end at; `int within 0..infinity` is refused with *"an
integer subtype needs integer bounds, not a floating-point literal"*. A constant declared with such a
type is checked against it at compile time, and the diagnostic spells the bound as it was written:

```sysl
type Distance = real within 0.0..infinity

const back: Distance = -1.0

print(1)
```

```error
'back' is -1.0, which Distance does not admit — it holds 0.0 to infinity
```

### Predicates

The predicate is an ordinary boolean expression, and the value it is about is named **`value`**,
bound only inside the predicate:

```sysl
type Even = int within 0..100 where value % 2 == 0
type Digit = char where value >= '0' && value <= '9'

var e: Even = 8
var h: Digit = '7'

print(e, h)
```

```output
8 7
```

A predicate may read module constants, so a range and a table's size can share one definition.
Where both a range and a predicate are written, **the range is checked first**, so a value the range
rejects never reaches the predicate.

A non-boolean predicate is reported against the type, without exposing the name of the function the
compiler generates to hold it:

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
type, values flow between them in both directions with no cast, and what the declaration adds is the
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

With `new` it is a **distinct nominal type**, and that is the only difference. Two derived types over
one base do not mix:

```sysl
type Meters = new f64
type Feet = new f64

print(f64(Meters(1.0) + Feet(1.0)))
```

```error
'+' needs matching types, got Meters and Feet
```

A derived type does not mix with its **base** either, in any position: an initializer, an argument
and a returned value each refuse a value that already *has* the base type:

```sysl
type Meters = new f64

val r = 3.0
var q: Meters = r

print(f64(q))
```

```error
cannot initialize 'q': declared Meters but the value is real
```

Converting in either direction is **written explicitly**: `Meters(x)` wraps, `f64(m)` unwraps, and
the wrap is where the constraint is checked.

**A bare literal is the exception, because it has no type of its own.** It takes its type from its
position (`x + 1` reads the 1 at `x`'s width), and a `new` type's position is no different, so the
literal becomes a value of that type, read at the base and checked as `Meters(3)` would be:

```sysl
type Meters = new f64
type PhysAddr = new u64

var q: Meters = 3
val p = PhysAddr(0x4000_0000)

print(f64(q * 2), u64(p + 4096), p < 0x8000_0000)
```

```output
6 1073745920 true
```

A value that already has a type still needs an explicit conversion: `p + n` with an `n: u64` is
refused, since a `u64` is not an address, which is exactly the mistake the type exists to catch. A
literal that does not fit the base is refused at the base:

```sysl
type Ip = new u32

val i: Ip = 0x1_0000_0000

print(u32(i))
```

```error
the literal 4294967296 does not fit uint
```

A literal the base does not accept is not accepted by the `new` type either: `2^53 + 1` is not
exactly representable as a `real`, so it is not a valid `Phase` over one.

```sysl
type Phase = new real

val p: Phase = 9007199254740993
```

```error
the literal 9007199254740993 is not exactly a real
```

#### A `new` type's name converts from whatever its base's name converts from

**`N(x)` is `N(B(x))`**, accepted exactly where `B(x)` is: `Phase(3)` over `real` is
`Phase(real(3))`, a variable of any type that `real(…)` accepts converts the same way, and a value
already of the base type is wrapped directly. The base's conversion is the one that runs, with its
own rules (a float into an integer base is truncated; a value into a fixed-point base is rounded and
saturated as `q15(x)` is, and takes a rounding mode where that conversion takes one), and then the
`new` type's own `within` or `where` is checked on the result. A chain of `new` types converts
through to the scalar at the bottom, so with `type A = new B` and `type B = new real`, `A(3)` is
`A(B(real(3)))`:

```sysl
type Phase = new real
type Slot = new u8 within 0..<8
type Gain = new i32q16
type Turn = new Phase

val n = 3
val f = 2.9

print(f64(Phase(3)) / 2, u8(Slot(f)), i32q16(Gain(n)), i32q16(Gain(0.1, Floor)), f64(Turn(n)))
```

```output
1.5 2 3 0.09999 3
```

What the base conversion refuses, the `new` type's name refuses too, and the diagnostic names the
type written on the line:

```sysl
type Phase = new real

print(f64(Phase(true)))
```

```error
cannot make Phase from bool
```

What is **not** inherited is implicit conversion: only the written `Phase(…)` converts. A value that
already has a type is still refused wherever a `Phase` is expected, as shown above, and converting
out (`f64(p)`, `int(p)`) is the base's own conversion applied to the value it holds.

**A `new` type's own `From` takes precedence.** Where the type implements `From[X]` and the value is
an `X`, `N(x)` calls that `from`, whatever the base conversion would have produced; a value of any
other type still takes the base's conversion. Inside that `from` itself, `N(x)` is the base's
conversion, so the impl may be written in terms of it. A value made through a `from` is what that
call answers, in a `const` as anywhere else — there the `from` is run while compiling, as any
function a constant calls is:

```sysl
type Phase = new real

impl From[int] for Phase
    from(n: int) -> Phase = Phase(n) * 2

const P: Phase = Phase(3)
val i = 3

print(f64(P), f64(Phase(i)), f64(Phase(i8(3))), f64(Phase(1.5)))
```

```output
6 6 3 1.5
```

The payoff is a specific bug. A table-driven program has several small integers that index different
things (a task number, a lock number, a priority level), and the typical mistake in such a program is
passing one where another was expected. Three `new u8`s with three ranges make that a compile error
rather than a plausible-looking wrong answer. **The benefit comes from the types, not from the
checks.**

### A derivation inherits its base's behaviour and may replace none of it

A `new` type over a scalar comes with **everything the scalar supports**: the arithmetic operators,
the remainder, the bitwise operators and the shifts, unary `-` and `~`, the comparisons, the compound
assignments, `++` and `--`, and `str`, each operating on the new type and producing it.

No `impl` may **replace** any of it:

```sysl
type Stamp = new i64

impl Add for Stamp
    add(self, other: Stamp) -> Stamp = Stamp(0)
```

```error
'Stamp' already implements 'Add' — the compiler provides it
```

```sysl
type Span = new i64

impl Display for Span
    str(self) -> string = "span"
```

```error
'Span' already implements 'Display' — every 'Integer' does, through one block written over the family, and a subtype has its base's memberships
```

The two refusals come from different sources, as the messages say. `Add` is an implementation the
**compiler** provides, so a block with no type arguments asks for the one that already exists.
`Display` is an ordinary `impl` the **library** writes, one blanket block covering every integer,
and a derivation inherits its base's implementations, so that block covers `Span` too.

**Extending a trait at an argument list the base does not have is different, and it is allowed.** A
trait that takes arguments is a family of separate implementations, and the base has exactly one of
them: the compiler provides `Stamp + Stamp` and says nothing about `Stamp + Span`. So a derivation
can model a timeline just as a struct can:

```sysl
type Stamp = new i64
type Span = new i64

impl Add[Span] for Stamp
    add(self, other: Span) -> Stamp = Stamp(i64(self) + i64(other))

print(i64(Stamp(100) + Span(7)), i64(Stamp(100) + Stamp(5)))
```

```output
107 105
```

The second number shows that the base's own addition is untouched; only the argument type
distinguishes the two.

Both rules are deliberate. **Inheriting is right** because a derivation does not change what the
values *are*: a `Slot` is a subset of the `u8`s, not a different kind of value that happens to be
stored in a byte. If a derivation started with nothing, every `new u8` would need a dozen `impl`
blocks before it was usable.

**Refusing replacement is the stricter decision, and it is intentional.** If `Stamp` could redefine
`<`, it would be a set of `i64`s that do not order the way `i64`s order, and none of the guarantees
the base makes could be relied on. A derivation is a *narrowing*, and a narrowing that alters
behaviour is not a narrowing.

So if you want your own `+`, you want a **struct**, not a derivation.

### Except rendering, which a derivation may replace

The second refusal above applies only to an *unmarked* block. Write
[`override`](/reference/traits/#override-when-the-overlap-is-deliberate) and the block replaces the
inherited one:

```sysl
type Stamp = new int

override impl Display for Stamp
    display(self, out: *Writer, fmt: FormatSpec) = display_str("#" + int(self).to_string(), out, fmt)

var s = Stamp(7)

print(s)
print(7)
```

```output
#7
7
```

**The line is drawn at what the base guarantees about its values.** Ordering is such a guarantee, as
the `<` argument above explains. How a value *renders* is not: a `Stamp` printing as `#7` holds the
same integer as before, and nothing downstream reasons about it differently. So rendering is the one
behaviour a derivation may replace, and the operators are not.

| | a `new` derivation | a one-field struct |
|---|---|---|
| distinct type | yes | yes |
| the base's catalogue | free; only `Display` is replaceable | nothing, write it all |
| an operation the base does not have | a member, or an `impl` at another argument list | ordinary |
| an operation the base has that makes no sense for the new type | present anyway | absent |
| rendering as something other than the base | `override impl Display` | ordinary |

**Use a derivation for an identity**: a slot number, a handle, a unit-tagged measurement, anything
whose operations are its representation's operations. **Use a struct for a quantity with an algebra
of its own**: an instant plus a duration is an instant, an instant plus an instant is meaningless,
and a derived scalar cannot express the difference. The struct approach has a cost: a date-and-time
library written this way needs one-field structs and five `impl` blocks per type.

The case in between, a type that wants most of its base's operations plus a few of its own, is a
derivation with **members**, plus an `impl` at an argument list the base does not have, like
`Stamp + Span` above.

### A `new` type may declare members

A body indented under a `new` type's declaration holds its members, written as a struct's are, and
`self` is the type's value:

```sysl
type PhysAddr = new u64
    page(self) -> u64 = u64(self) >> 12
    offset(self) -> u64 = u64(self) & 0xfff
    plus(self, n: u64) -> PhysAddr = self + PhysAddr(n)

val p = PhysAddr(0x4020_1234)

print(p.page(), p.offset(), u64(p.plus(0x1000)))
```

```output
262657 564 1075847732
```

A generic one is written the same way, and its parameter is in scope in every member:

```sysl
struct Frame
    sp: u64
    pc: u64

type UserPtr[T] = new u64
    next(self) -> UserPtr[T] = self + UserPtr[T](sizeof(T))

val f = UserPtr[Frame](4096)

print(u64(f.next()), u64(UserPtr[u8](4096).next()))
```

```output
4112 4097
```

The body holds **only members**: the value is the base, so there is no field for it to hold:

```sysl
type PhysAddr = new u64
    raw: u64
```

```error
'PhysAddr' is its base's value and holds no fields — its body declares members, and a type with fields of its own is a 'struct'
```

Only a `new` type has members. Without `new` the declaration is another name for its base, so a member
written there would be a member of every value of the base:

```sysl
type Age = int within 0..150
    older(self) -> Age = self + 1
```

```error
'Age' is not a 'new' type, so its values are int's and a member written here would be a member of every int — 'new' after the '=' makes it a type of its own, which may have members
```

By the rule above, a member may add to the inherited operations but not replace them: a member named
after an operator's method is refused, as the `impl` would be.

```sysl
type Stamp = new i64
    add(self, o: Stamp) -> Stamp = Stamp(0)
```

```error
'add' is how 'Add' is implemented for Stamp, and the compiler provides that — a member of this name would hide it
```

**A subtype narrows which values a type has, never which operations it has**, so an operation the
base lacks, the subtype lacks too, and the diagnostic names the subtype rather than the base:

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

Raw integer arithmetic wraps, and a wrapped result that reaches a produce site is a wrong answer the
range check may not catch. On a `Slot = u8 within 0..<200`, `Slot(150) + Slot(150)` is 300, which
wraps to 44, and 44 is in range.

So **where an operand came through a `within` type and the operands' declared ranges permit a result
the base width cannot hold, `+`, `-` and `*` detect overflow and trap.** A range narrow enough that
its results always fit keeps the plain instruction, so a counter, an index and an `x + 1` cost
nothing extra. A left shift is checked separately: it has no overflow intrinsic, so a bit pushed out
of the top is detected by shifting back, and a shift amount at or beyond the width traps. **That trap
belongs to this rule, not to the shift operator**: a plain shift by the width or more is *defined*,
and yields zero or the sign; see
[expressions](/reference/expressions/#shifting-by-the-width-or-more). Arithmetic on a type with
**no** range still wraps.

For a **transparent** subtype, the arithmetic happens at the base and yields a base value, so a
literal beside one is an ordinary base value, and only what is stored has to be in range:

```sysl
type Small = int within 0..10

var s: Small = 2

print(s * 100)
```

```output
200
```

The multiplier is not a `Small` and does not need to be. A **derived** subtype is a type of its own,
so it mixes with the base only through the explicit conversion above, and its results are of its
own type and are checked where they are produced.

### Where a constraint is checked

**At every point where a value of the constrained type is produced, and nowhere else.** There is no
list of syntactic forms to memorize: a value acquires the type wherever it **flows into a slot the
type is written on**, and wherever one of the type's own operations **yields one**. The slots are:

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

The two sites that come from the type itself rather than from a slot are:

- **an operation on a derived subtype**, whose inherited operations *produce the subtype*, so
  `Slot(199) + Slot(1)` is a produce site and traps. A **transparent** subtype has no such site: its
  arithmetic happens at its base and yields a base value, which is checked by the store that gives
  it the subtype again.
- **a compound assignment and an increment**, which compute and store in one step and so are checked
  between the two. `a += e` produces exactly what `a = a + e` produces: the operator is applied to a
  base value either way, so `t += 120` on a `Temp = int within -100..100` holding `-50` gives `70`,
  rather than an error because 120 is out of range.

A value that **already has** the type is not re-checked when it is merely read, passed along, or
copied — it could not have got there unchecked. Passing one to a *different* subtype over the same
base **is** a produce site and is checked again, which is what keeps a wider type from leaking into a
narrower one.

### A constrained type has no zero value

One possible produce site, the implicit zero value, is closed by not existing:

```sysl
type Age = int within 0..150

var a: Age

print(a)
```

```error
Age has no zero value, so 'a' needs an initial value
```

This holds whether or not the range contains zero. Making it a rule of the **type** rather than of
the range means that widening a range never silently changes whether a declaration elsewhere
compiles.

**A `new` type that checks nothing is not constrained in this sense**, and has its base's zero. With
no range and no predicate, every value of the base is a valid value, zero included, so the rule would
protect nothing, and an address type that could not live in zero-filled storage could not be used
for an address field in a table:

```sysl
type Ip = new u32

struct Entry
    valid: bool
    ip: Ip

var table: [4]Entry
var none: Ip

print(u32(table[2].ip), u32(none))
```

```output
0 0
```

A `new` type with a range is a checking type, and the rule above applies:

```sysl
type Slot = new u8 within 0..<8

var s: Slot

print(u8(s))
```

```error
Slot has no zero value, so 's' needs an initial value
```

### A constant may have a constrained type

A constant's value is folded at compile time, so its range is checked once, against the folded
number. A constant of a `new` type is the base's constant written through the conversion, or a bare
literal, which takes the type as it does anywhere else:

```sysl
type Ip = new u32
type Slot = new u8 within 0..<8

const OUR_IP = Ip(0x0a00_020f)
const GATEWAY: Ip = 0x0a00_0202
const LAST: Slot = 7

print(u32(OUR_IP), u32(GATEWAY), u8(LAST), OUR_IP == GATEWAY)
```

```output
167772687 167772674 7 false
```

A value the range does not admit is refused at the declaration:

```sysl
type Slot = new u8 within 0..<8

const T: Slot = 9
```

```error
'T' is 9, which Slot does not admit — it holds 0 to under 8
```

A type with a `where` predicate is refused for a constant: the predicate is a function run where a
value is produced, and a constant is folded into its uses rather than produced at run time.

### A violated check traps, and there is no `try`

A violation is not an error value and cannot be caught. A constrained type states something its
values *are*, so a value outside it is a bug in the code that produced it rather than a condition to
handle. The fallible constructor an enum has is therefore deliberately absent, and the diagnostic
says so:

```sysl
type Age = int within 0..150

print(Age.try(5))
```

```error
'Age' is a constrained type and has no 'try': a value outside its range is a mistake in the code that produced it rather than a condition to handle, so 'Age(x)' checks it and traps, and 'Age::Valid(x)' asks the question without trapping
```

## What the type's own name offers: `::` attributes

A constrained type's name is a type, not a value, so nothing is *read* from it. What it provides are
**attributes**, written with `::` rather than `.` so they stay out of the member namespace:
`Age::First` cannot be confused with a field, a property or an associated function, and no `impl` can
shadow one by declaring a member of that name.

The set is small and closed, and every attribute in it concerns **integer bounds**:

| written | is | notes |
|---|---|---|
| `T::First` | the lower bound | a `T`, a constant, no argument |
| `T::Last` | the upper bound | a `T`; one *below* the written bound where the range is exclusive |
| `T::Valid(x)` | whether `x` is in range | takes the **base**; a `bool`, and **total** — it never traps |
| `T::Succ(x)` | the next value | a `T` from a `T`; traps at `T::Last` |
| `T::Pred(x)` | the previous value | a `T` from a `T`; traps at `T::First` |
| `T::Range` | the range itself | only as a `for` loop's iterable, `First..Last` inclusive, the variable a `T` |
| `T::Min` / `T::Max` | the same two numbers | the *magnitude* question, which agrees with the ordinal one here — see below |

**`Min`/`Max` are not aliases of `First`/`Last`; they answer different questions that can have
different answers.** `First` and `Last` name the ends of a *declared sequence*; `Min` and `Max` name
the extremes a type can hold. A `within` range is written in order, so both questions have the same
answer here, and both are offered: a type whose purpose is its bounds should support `Min`, and a
reader who knows `Min` on `u32` should find it on a subtype of `u32` too. A **simple enum answers
only `First`/`Last`**, because there the answers differ: discriminants may be explicit and
non-contiguous, so the first-declared variant need not carry the smallest one. An **integer type
answers only `Min`/`Max`**, as described on the [attributes](/reference/attributes/) page.

```sysl
type Slot = new u8 within 0..<8

print(u8(Slot::First), u8(Slot::Last), Slot::Valid(3), Slot::Valid(9))
print(u8(Slot::Succ(Slot(3))), u8(Slot::Pred(Slot(3))))

var total = 0

for k in Slot::Range do total += u8(k)

print("sum of every slot:", total)
```

```output
0 7 true false
4 2
sum of every slot: 28
```

**Every attribute except `Valid` works in terms of the subtype.** A bound of `T` is a value of `T`,
the step from one `T` is another, and the values `Range` walks are `T`s. On a transparent subtype
this is invisible, since `T` and its base are the same type; it is what makes the set usable on a
**derived** subtype, which would otherwise have to cast its own attributes back into itself. A `new`
subtype exists to keep its values separate from its base's, and attributes that returned the base
would undo that separation.

**`Valid` is the deliberate exception.** It takes the base, because asking whether a value is a `T`
only makes sense for a value that is not one yet. Given a `T` it is refused, since the answer could
only be yes:

```sysl
type Slot = new u8 within 0..<8

print(Slot::Valid(Slot(3)))
```

```error
'Slot::Valid' takes a byte, not Slot
```

The values it can be asked about are therefore the ones the **base** can hold, so a subtype over a
`u8` cannot be asked about `-1`; that is simply the base's own range.

`Valid` is how to check instead of trapping. A produce site traps because a value outside the range
is a mistake, not a condition; a program holding a number it has not validated asks `Valid`, and a
cast after `Valid` returns true is the ordinary way to obtain a value of the type.

`Succ` and `Pred` **trap** rather than saturating or wrapping, for the same reason: a step past the
end is not a value of the type, so producing one is the mistake, and no single alternative result
would suit every caller.

Two forms are refused. `Range` is meaningful only as an iterable; it names nothing a program can
hold, because a range is not a type a program can name:

```sysl
type Slot = new u8 within 0..<8

var r = Slot::Range

print(r)
```

```error
'Slot::Range' is only meaningful as the iterable of a 'for' loop
```

And a subtype over a **float** or a `char`, or one written with **no range**, has no attributes at
all, since each attribute concerns integer bounds and there are none. The diagnostic names the part
that would have to change:

```sysl
type Ratio = new f64 within 0..1

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
the struct's own fields, which are in scope by name, and when there are several clauses all must
hold. Each is checked independently, so the diagnostic names the one that failed:

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
distinguishes by shape: a **member** declaration has a parameter list or a `->`, an **invariant
clause** is the word followed by an expression, and anything else is a **field**. So a field may
still be named `invariant`, as the `Cfg` example above showed, since `invariant: int` matches neither
of the first two.

### Checked at every write, not only at construction

An invariant is checked far more often than at construction alone. The check covers:

- constructing the struct, including one built directly as an argument or as a returned value
- assigning a whole struct over an existing one
- **assigning a single field**, including a compound assignment (`w.hi += 1`) and an increment
  (`w.hi++`)
- a field written through a pointer
- a field written into an array element
- a field written **inside** one of these: `o.a.n = 9`, or `g.items[0].n = 9`
- **the zero value** a declaration with no initializer starts at: `var w: Window`, a `[4]Window`, a
  struct with a `Window` field, and module storage declared the same way

The second-to-last case is needed for a reason that is easy to miss. A clause may read *through* a
field: `invariant a.n <= b` is legal, and `a.n` is then not a field of the struct itself, so the
write that breaks it is a write the struct never sees directly. The check therefore applies to
**every struct a written place is inside**, not only to the one whose field is named last, and the
checks run innermost first, so the smallest struct the write broke is the one that traps. An index
into an array locates a place rather than owning one, so it contributes no check and is passed
through. An index into a **view** reaches storage outside the struct, which no clause may read (see
below), so writing a view's element requires no check on the struct holding the view.

The zero value is an implicit construction. `Window`'s zero has `lo` and `hi` both `0`, which
satisfies `lo <= hi` but would immediately break `lo < hi`, and a struct that started out invalid
would be a value no write had ever checked. The check is on a constant, so where the zero value
satisfies the clause it costs nothing.

As a result, **a sequence of writes that ends in a valid state but passes through an invalid one
traps at the step that broke the invariant.** There is no "mid-update" mode.

The [multiple assignment](/reference/expressions/#assignment) is the one exception: it completes both
writes before either invariant is checked, so a pair that ends valid is accepted even where each half
alone would not be:

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

This matters when the two writes cross: `w.lo = 12` followed by `w.hi = 14` traps at the first
write, because 12 is briefly above the 10 still in `hi`. `w.lo, w.hi = 12, 14` does not, and the
two writes take a struct that was valid before to one that is valid after.

### When a struct cannot be updated one field at a time

There are three approaches. Their order matters, because the last one is the most expensive and the
one most often tried first:

1. **Look for an order in which no intermediate state is invalid**; there often is one. A
   `count <= high` watermark is updated by raising the ceiling before the floor, and needs nothing
   else; the same two writes in the other order are refused.
2. **Ask whether the clause points at a redundant field.** An invariant relating two fields is a
   claim about the *representation*, and the trap often means the struct stores one fact twice. A
   ring buffer keeping `head`, `tail` and `count` cannot update any two of them one at a time; one
   keeping `head` and `count` and computing the end has no clause to break, because nothing is left
   to disagree. Storing two of the three fields is the better design either way.
3. **Otherwise assign the whole struct**, which is what whole-struct assignment is for, but be aware
   of the cost. `*self = Ring(self.buf, …)` restates the *whole* value to change two bytes, so an
   invariant across two fields makes a container's own update cost the size of the container. For a
   buffer, that is its entire storage, per element.

### What may be aliased

Every check above works by walking outward through the **place** being written, so it depends on the
place still naming the struct. A pointer is where that stops working: inside a function taking a
`*Inner`, there is no `Outer` to re-read and no way to know there ever was one.

The rule is therefore about **what may be aliased**, and it restricts alias *creation*, which is
local and decided by types, rather than alias *use*, which would need a borrow checker, which sysl
does not have. It has four parts, and **none of them applies to a program that declares no
invariants**, since each concerns a clause.

This is a separate question from whether two parameters may overlap at all, which is what a
function's [`@disjoint(...)`](/reference/attributes/#disjoint-parameters-whose-memory-nothing-else-reaches)
promises the optimizer; the two rules are independent.

**A pointer whose target is constrained by a clause it cannot see is refused where it is created:**

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

**A writable view grants the same ability to write**, and is refused the same way:

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

A `[]const T` is allowed, since a view that cannot write cannot break the invariant. It is the first
of the two alternatives the diagnostic names, and the only difference between the two programs:

```sysl
struct Inner
    n: int

struct Group
    items: [4]Inner
    invariant items[0].n <= 10

var g = Group([Inner(0), Inner(0), Inner(0), Inner(0)])
var view: []const Inner = g.items[0..<2]

print(view.len, view[0].n)
```

```output
2 0
```

So is `&o` itself, whose `*Outer` names the struct and is checked by the ordinary walk, and so is a
pointer to a field **no clause mentions**, because the refusal concerns the clause, not the struct:

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
`o.a.set(4)` passes `set` the same detached `*Inner`, and it is allowed because the *call site* still
knows the whole place: `o` is visible there, so the clause is re-run as soon as the call returns:

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

Since there are no parameter modes, a receiver is the only way a callee is given somewhere to write
without an `&` in the caller's own source, which is why this one channel is kept open, at no cost to
a mutating method.

**A `*self` method may not let a pointer into its receiver outlive the call.** This is what makes
the rule above sound: a `set` that returned `&self.n` would hand out the detached alias by a route
that no `&` in the caller shows:

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

The check is local to the body, and it applies only to methods of a struct that can *be* a field of
a struct with clauses; a struct that is never contained in one can never have a detached receiver,
so its methods are not checked. Storage on the far side of a reference or a view does not belong to
the receiver either, so `&self.bytes[0]` where `bytes` is a `[]u8` field is ordinary.

**A clause may only read storage the struct owns.** Everything above concerns aliases of the
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

A view's `len` is stored in the struct, as part of the view itself, and may be read. Its elements
are stored elsewhere, and that has a useful consequence: since no clause can read them, **an alias of
a view's element cannot break an invariant**. A pointer to one, and a writable view sliced from the
view, are ordinary, and writing through either changes nothing a clause over the length can see:

```sysl
struct Seq
    elems: []int
    count: usize
    invariant count <= elems.len

var s = Seq([1, 2, 3], 2)
var p = &s.elems[0]
var v = s.elems[..<s.count]

*p = 4
v[1] = 5
s.elems[2] = 9

print(s.elems[0], s.elems[1], s.elems[2])
```

```output
4 5 9
```

None of this makes a `*T` safe, and it is not intended to. A pointer passed to a *third* function
that stores it is out of reach of a local check, as the [memory model](/reference/memory/) says of
every guarantee about a raw pointer. What the rule provides is that the **silent** case, an alias
that looks like ordinary code and leaves a clause quietly false, is refused.

### Generic structs

A generic struct carries clauses like any other struct, and they are checked at every place the list
above names. A clause is a generic body over the struct's own parameters, so what it may do with a
`T` is what the struct's bounds allow: a comparison needs `Ord`, and is checked once, where the
struct is declared, rather than once for every type it is instantiated at:

```sysl
struct Span[T: Ord]
    lo: T
    hi: T
    invariant lo <= hi

var a = Span(1, 5)
var b = Span(0.5, 2.5)

a.hi = 9

print(a.lo, a.hi, b.hi)
```

```output
1 9 2.5
```

Each instantiation checks the clause at its own type, so `Span[real]` compares floats. Whether a
clause reads storage the struct owns is also decided per instantiation, since it depends on the
type arguments: a `T` that is `int` is the struct's own bytes, and one that is a pointer is not.

The standard library's `Buf[T]` relies on this. It declares `invariant count <= elems.len`, and every
member is then [told so on entry](/reference/verification/#what-the-optimizer-is-told), which lets
`at` compare the index with `count` once and fold the slice's own bounds test into that comparison.

## Contracts on a function

```sysl
half(x: int) -> int
    require x >= 0, "x must not be negative"
    ensure result >= 0

    x / 2

print(half(10), half(7))
```

```output
5 3
```

`require` is checked **on entry**. `ensure` is checked **before every return**, including early
ones: an early `return` that violates the postcondition traps exactly as the fall-through path
would. Both take an optional message after a comma.

**The message is any `string` the compiler can compute**, the same reading an `@assert`'s message
gets: a literal, a `const`, a `val` that folds, a concatenation of them, a name from another module.
`invariant` takes its message the same way.

```sysl
const Negative = "x must not be negative"

half(x: int) -> int
    require x >= 0, Negative
    ensure result >= 0, "half of " + "a non-negative"

    x / 2

print(half(10))
```

```output
5
```

A message that cannot be worked out while compiling is refused, naming the clause:

```sysl
check(x: int, why: string) -> int
    require x >= 0, why

    x
```

```error
the message of this 'require' cannot be computed while compiling
```

**Both kinds form one block at the top of the body.** A clause of either kind after an ordinary
statement is rejected: a precondition that runs after some of the work is not a precondition, and a
postcondition is *written* there because that is where a reader looks for what the function
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

**`result`** is the value being returned, available only in an `ensure`. It is rejected in a
`require` (there is no value yet), in an ordinary statement, and in the `ensure` of a function that
returns nothing:

```sysl
h(x: int) -> int
    require result >= 0

    x
```

```error
'result' is only meaningful inside an 'ensure' of a value-returning function
```

A local named `result` shadows it, by the ordinary scoping rule rather than a special case:

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

**`old(expr)`** is `expr` evaluated **on entry**, available only in an `ensure`. It takes exactly one
argument, and several `old` snapshots in one function are independent of each other. It lets a
postcondition describe what a mutating function *changed*, not only the state it left behind:

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

Contracts work the same way on a **method**, including one with a `*self` receiver, where they are
most useful: `ensure result > old(self.n)` on a mutating method states what the method is *for*.

Like a constraint, a violated contract **traps**, and contracts are checked in **every** build. There
is no release mode that drops them, since one would make a program's meaning depend on how it was
compiled.

## The third shape: a latch

`Result` and a trap are the two channels for an *operation* that failed. A **stream** fits neither
well, and the library handles it with a third shape: a trait whose single member reports whether the
stream has failed:

```sysl
struct Meter
    written: int
    bad: bool

    put(*self, n: int)
        if n < 0 then self.bad = true
        else self.written += n

impl Fallible for Meter
    override failed(*self) -> bool = self.bad

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

`Fallible` is required by both `Writer` and `Reader`, which lets one open file be both. Two traits
each declaring a `failed` for the same type could not be told apart at a call, because `failed` takes
no arguments, so nothing about the call could say which was meant.

**It latches rather than returning a result**, and the streams built on it rely on that: an
implementation stays straight-line, `print(x)` stays a statement, and a caller checks once after a
series of operations rather than after each one. Whether a stream *ended* is a separate question from
whether it failed, which is why reading reports the first with an empty result and leaves `failed`
to report the second.

The default is `false`: most streams cannot fail, and one that cannot should not have to say so, so
an `impl` with an empty body is complete.

## What is deliberately absent

| absent | instead |
|---|---|
| exceptions, `throw`, `try`/`catch` | a returned value, or an abort. There is no third, invisible control-flow channel |
| error return codes by convention | `Result`, in the type, checked — not an `int` a caller might forget to inspect |
| a `panic` that unwinds | a trap is terminal |
| a `try` on a constrained type | `T::Valid(x)`, then the ordinary cast |
| a build flag that strips checks | every check is in every build |
| a contract discharged instead of checked | it is a branch and a trap wherever the value is produced, whether or not a prover has been over it |

Nothing on this page is proved at compile time: a `require` is a branch and a trap, an invariant is a
call to a generated predicate, and a `within` is two comparisons. The benefit is that **the failure
is reported where the mistake is**, plus, for `new` types, a compile-time guarantee that is
independent of the checks.

**Proving is a separate tool, and it removes nothing.** [Verification](/reference/verification/) adds
the specification vocabulary (quantifiers, loop invariants, termination measures, `@pure` and
`@ghost`) and `sysl prove`, which discharges the obligations with Why3. A clause proved redundant is
still compiled, because a program whose emitted code depended on whether a prover had been available
would be impossible to reason about. That is why the row above says *instead of*: the two are
additive.

---

Next: [async and tasks](/reference/async/).
