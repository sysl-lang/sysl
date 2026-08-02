---
title: Error handling
summary: Two channels — a failure you handle is a value, and a bug stops the program.
weight: 90
---

Failure travels on two separate channels in sysl, and choosing between them is a real decision at
every API rather than a coin toss:

- **A recoverable failure is a value.** `Result[T, E]` or `Option[T]` in the return type, propagated
  with `?`. The caller has to engage with it, because it is part of the signature.
- **A bug is a trap.** An index past the end, an invalid cast, a broken contract. Not a value, not
  catchable, and it stops the program.

Keeping them apart is what makes a signature honest. A function returning `Result[T, E]` is telling
you it can fail in a way you handle; one returning plain `T` is telling you the only way it "fails"
is if the program is already wrong.

The line between them is one question: **could correct calling code ever hit this?** If yes — the
input came from a file, a socket, or a person, or the operation legitimately may not succeed — it is
a `Result`. If only a bug reaches it, it traps.

## `Result` and `Option`

Both are ordinary generic enums from the library, exactly as the [previous
chapter](/tour/enums/) described. `Option[T]` is **absence with no reason attached**; `Result[T, E]`
is **failure with a reason**:

```sysl
half(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n / 2)
    else Err("odd")

half(10) match
    Ok(v)  -> print("half:", v)
    Err(e) -> print("refused:", e)

half(7) match
    Ok(v)  -> print("half:", v)
    Err(e) -> print("refused:", e)
```

```output
half: 5
refused: odd
```

`E` is whatever carries the reason — a string here, but usually an enum, so a caller can match on
what went wrong instead of reading it.

Type arguments come from inference, including the cases with nothing to infer from at the call: a
bare `None` takes its `T` from context, and `Ok(n)` inside a `Result[int, string]`-returning function
takes its `E` from the return type.

## `?`

Matching every call gets old fast, and `?` is sugar for the match you would have written: **unwrap
the success, or early-return the failure.**

```sysl
half(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n / 2)
    else Err("odd")

quarter(n: int) -> Result[int, string]
    var h = half(n)?

    half(h)

print(quarter(20).unwrap_or(-1), quarter(10).unwrap_or(-1), quarter(7).unwrap_or(-1))
```

```output
5 -1 -1
```

On a `Result`, `Ok(v)` becomes `v` and `Err(e)` returns `Err(e)` from the enclosing function
immediately. On an `Option`, `Some(v)` becomes `v` and `None` returns `None`:

```sysl
struct Node
    label: string
    next: Option[&Node]

second_label(head: &Node) -> Option[string]
    var nx = head.next?

    Some(nx.label)

var tail: &Node = Node("b", None)
var head: &Node = Node("a", Some(tail))

print(second_label(head).unwrap_or("(none)"))
print(second_label(tail).unwrap_or("(none)"))
```

```output
b
(none)
```

`?` is an expression, not a statement, so its unwrapped value flows straight into whatever surrounds
it.

**The two channels do not cross.** An `Option`'s `?` cannot early-return from a `Result`-returning
function, and the enclosing function's return type has to be able to carry the failure at all — the
early return needs somewhere to go:

```sysl
half(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n / 2)
    else Err("odd")

count(n: int) -> int
    half(n)?
```

```error
'?' may only be used in a function returning sysl.Result, not int
```

The error types must match **exactly**, too. There is no implicit widening, so a function whose own
error type differs from a callee's converts at the call site:

```sysl
struct IoError
    code: int
end IoError

struct AppError
    why: string
end AppError

read_it(n: int) -> Result[int, IoError]
    if n > 0 then Ok(n)
    else Err(IoError(5))

run(n: int) -> Result[int, AppError]
    var v = read_it(n)?

    Ok(v)

print(run(1).is_ok())
```

```error
'?' propagates a IoError error, but this function returns AppError
```

That is a real ergonomic cost, and it is the shipping behaviour rather than the end state: the
intended answer is a `From`-style conversion, where `?` converts the callee's error to the caller's
whenever a conversion trait connects the two, as Rust's `?` calls `From::from`. Writing that trait is
possible today — `impl From[IoError] for AppError` and `impl From[ParseError] for AppError` are two
different argument lists, and a type may implement a parameterized trait once at each of them (see
the [reference](/reference/traits/)). What is left is teaching `?` to look for one.

## The combinators

The conveniences on both types are **ordinary members in the library**, not compiler knowledge. The
total ones ask a question or supply a fallback:

```sysl
find(xs: []const int, target: int) -> Option[usize]
    for i in 0..<xs.len
        if xs[i] == target then return Some(i)

    None

var data = [4, 5, 6]

print(find(data[..], 5).is_some(), find(data[..], 9).is_none())
print("fallback:", find(data[..], 9).unwrap_or(99))
```

```output
true true
fallback: 99
```

`Result` has the matching pair `is_ok()` and `is_err()`, plus `unwrap_err()` for reaching the reason.

The **forcing** ones hand over the payload and stop the program when there is none — `unwrap()`, and
`expect(msg)` which says why you thought there would be one:

```sysl
var got: Option[int] = Some(7)

print(got.unwrap(), got.expect("a value was put here two lines ago"))
```

```output
7 7
```

That these are written in sysl rather than built in is the part worth pausing on, because it is what
keeps "a bug stops the program" from meaning "the compiler has to know the name of every way to
stop":

```sysl
unwrap(self) -> T = self match
    Some(v) -> v
    None ->
        print("panic: unwrap of a None value")
        exit(1)
```

Two things make that ordinary code. A diverging arm has a type — `never` — so `exit(1)` sits beside
`Some(v) -> v` and the `match` still has the payload's type rather than a conflict. And the departure
itself is an `extern`: the library declares `exit(code: int) -> never`, so stopping is a call.

## `defer` — releasing what ARC does not

`?` has a consequence worth facing directly: it makes leaving early the **normal** way out of a
function. That is fine for everything ARC owns — a `&T`, a string, a slice's backing all go back on
their own — and it is a problem for everything else. A file descriptor from `open`, a block from
`malloc`, a lock taken from a mutex: those are released by hand, and a function with four exits has
four places to remember.

`defer <statement>` runs that statement on the way out of the block containing it, so the release is
written **beside the call that took the resource**, once:

```sysl
acquire(n: int) -> Result[int, string]
    if n > 0 then Ok(n)
    else Err("cannot acquire")

step(n: int) -> Result[int, string]
    if n % 2 == 0 then Ok(n * 10)
    else Err("odd step")

run(n: int) -> Result[int, string]
    var h = acquire(n)?

    defer print("released", h)

    var v = step(h)?

    print("finished with", v)

    Ok(v)

print(run(4).is_ok())
print(run(3).is_ok())
```

```output
finished with 40
released 4
true
released 3
false
```

Both exits ran it. `run(4)` fell off the end and `run(3)` left through the `?` in the middle, and
neither one had to say so — which is the entire point, because the second exit is the one a program
forgets.

Two rules make it predictable. **Several in one block run last-registered-first**, so they undo in
the reverse of the order they were set up. And **a `defer` runs only if control reached it**: it is a
statement, not a declaration, so one below an early return never registered at all. In the program
above, a failure from `acquire` returns before the `defer` line, and nothing is released — correctly,
since nothing was acquired.

The scope is the **block**, not the function, and a loop is where that shows:

```sysl
for i in 0..<2
    defer print("close", i)

    print("open", i)

print("done")
```

```output
open 0
close 0
open 1
close 1
done
```

Go runs its `defer` at function exit, so the same loop would hold two resources to the end and
release both at once — which is how a loop over ten thousand files runs out of descriptors. Here each
iteration is a block and each closes its own.

One thing `defer` is not: a `finally`. **A trap runs nothing.** A broken invariant means the
program's model of itself is already wrong, and cleanup run against that state is how a corrupt
program writes its corruption out. `defer` releases a resource; it does not rescue a bug — which is
the subject of the rest of this page.

## Traps

The other channel. A trap is the runtime response to a broken invariant, and what it does is settled:
**it aborts.** No unwinding, no stack cleanup, no `catch`, no exceptions.

```sysl
var xs = [1, 2, 3]

print(xs[5])
```

That program compiles and then stops when it runs, because the bounds check fails. There is no
handler that could have caught it and no `defer` that runs on the way down — `defer` is a scope-exit
form, and a trap is not an exit. It is the program stopping because its model of itself is already
wrong, and cleanup code run against that state is how a corrupt program writes its corruption out.

The trap sources are the runtime safety checks the safe subset rests on: an out-of-bounds index, an
inverted or out-of-range slice range, a checked cast that fails, an integer divide by zero, and a
violated `require`/`ensure` contract.

**Integer overflow is not one of them**, which is worth saying because Rust traps on it in a debug
build. Arithmetic wraps at the declared width, as [values](/tour/values/) showed — that is defined
behaviour rather than a broken invariant, so there is nothing for a trap to report.

Two reasons abort is the only defensible choice here. A kernel and an embedded target have **no
unwinding runtime** — landing pads, a personality routine and per-frame cleanup tables are exactly
what a freestanding target does not have. And an abort is one code path, where unwinding is a second,
invisible control-flow graph that every function in the program would have to be correct under.

What a trap *does* is an environment fact rather than a language one. A hosted program prints a
diagnostic and exits non-zero; a kernel installs its own panic handler and enters that. The decision
to stop is the language's; the action on stopping is the environment's.

## Turning a trap back into a value

There is no `panic` you can recover from, so a program that wants to survive bad input must **not do
the trapping thing** — check the bound, validate before dividing, and use the fallible constructor
rather than the checked cast. That is the move to make wherever untrusted input enters:

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

`Color(9)` would have trapped. `Color.try(9)` hands back a `None` that this function turns into a
reason, and the byte off the wire stops being a bug in the program and starts being a value it
handles — which is the whole of the policy in one function.

## What is deliberately absent

- **No exceptions.** Recoverable failure is a returned value and a bug is an abort. There is no
  third, invisible control-flow channel.
- **No error return codes by convention.** The failure is in the type and it is checked, not an
  `int` a caller might forget to inspect.
- **No panic that unwinds.** A trap is terminal.

## `?` and the memory model

`?` obeys ARC with no special rule, which matters most here because it is the operator most likely to
carry a heap payload across a function boundary.

Unwrapping a `&T` success payload retains it past the wrapper, so the reference outlives the `Result`
it came out of and is released exactly once. Propagating a `&T` error payload moves it through the
early return with its count intact.

Neither is a rule about `?`. Both fall out of retain-on-alias and release-at-scope-end — and they are
worth naming because a hand-rolled C error return leaks or double-frees at exactly these two
boundaries.

---

Next: [traits and generics](/tour/traits/) — one implementation over many types, and what a bound is
really promising.
