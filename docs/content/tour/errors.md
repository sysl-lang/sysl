---
title: Error handling
summary: Two channels — a failure you handle is a value, and a bug stops the program.
weight: 90
---

sysl reports failure through two separate channels, and every API chooses one deliberately:

- **A recoverable failure is a value.** It is a `Result[T, E]` or `Option[T]` in the return type,
  propagated with `?`. The caller must deal with it, because it is part of the signature.
- **A bug is a trap.** An index past the end, an invalid conversion, a broken contract. A trap is not
  a value, cannot be caught, and stops the program.

Keeping them apart makes a signature accurate. A function returning `Result[T, E]` tells you it can
fail in a way you are expected to handle; one returning plain `T` tells you it fails only if the
program already has a bug.

One question decides between them: **could correct calling code ever reach this failure?** If so,
because the input came from a file, a socket or a person, or because the operation may legitimately
not succeed, it is a `Result`. If only a bug can reach it, it traps.

## `Result` and `Option`

Both are ordinary generic enums from the library, as the [previous chapter](/tour/enums/) described.
`Option[T]` is **absence with no reason attached**; `Result[T, E]` is **failure with a reason**:

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

`E` is whatever type describes the reason: a string here, but usually an enum, so a caller can match
on what went wrong instead of parsing a message.

Type arguments are inferred, including where the call itself gives nothing to infer from: a bare
`None` takes its `T` from context, and `Ok(n)` inside a function returning `Result[int, string]`
takes its `E` from the return type.

Matching every call quickly becomes repetitive, and `?` is shorthand for that match: **unwrap the
success, or return the failure early.**

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

On a `Result`, `Ok(v)` becomes `v` and `Err(e)` immediately returns `Err(e)` from the enclosing
function. On an `Option`, `Some(v)` becomes `v` and `None` returns `None`:

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

`?` is an expression, not a statement, so the unwrapped value can be used directly in the
surrounding expression.

**The two kinds do not mix.** An `Option`'s `?` cannot return early from a function that returns a
`Result`, and the enclosing function's return type must be able to carry the failure, since the early
return needs somewhere to go:

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

Where the callee's error type differs from the caller's, `?` converts it through `From`. A function
with its own error type writes `impl From[Theirs] for Mine` once, and every `?` that crosses between
the two types uses it:

```sysl
enum ReadError
    Missing

enum ConfigError
    Unreadable(cause: ReadError)
    Invalid(key: string)

impl From[ReadError] for ConfigError
    from(value: ReadError) -> ConfigError = Unreadable(value)

read_setting(present: bool) -> Result[int, ReadError]
    if present then Ok(8080) else Err(Missing)

load_port(present: bool) -> Result[int, ConfigError]
    val port = read_setting(present)?

    if port > 65535 then Err(Invalid("port")) else Ok(port)

print(load_port(true).unwrap_or(-1))

load_port(false) match
    Ok(p) -> print("port", p)
    Err(Unreadable(_)) -> print("config file unreadable")
    Err(Invalid(k)) -> print("invalid", k)
```

```output
8080
config file unreadable
```

Without such a block the `?` is rejected, and the diagnostic names the `impl` to write;
[the reference](/reference/errors/#a-converts-through-from) has the details.

The conveniences on both types are **ordinary members in the library**, not built into the compiler:
`is_some`, `is_none` and `unwrap_or` on an `Option`; `is_ok`, `is_err` and `unwrap_err` on a
`Result`; and `unwrap()` and `expect(msg)`, which return the payload or stop the program when there
is none. [`library/core`](/library/core/) lists them.

Because they are written in sysl, the compiler does not need to know every way a program can stop:

```sysl
unwrap(self) -> T = self match
    Some(v) -> v
    None ->
        print("panic: unwrap of a None value")
        exit(1)
```

Two features make this ordinary code. A diverging arm has the type `never`, so `exit(1)` can sit
beside `Some(v) -> v` and the `match` still has the payload's type. And stopping is a plain call: the
library declares `exit(code: int) -> never` as an `extern`.

## `defer`: releasing what reference counting does not

`?` makes an early return the **normal** way out of a function. That is fine for everything
reference counting manages, since a `&T`, a string and a slice's storage are all released
automatically. It is a problem for everything else. A file descriptor from `open`, a block from
`malloc` and a held mutex lock must be released by hand, and a function with four exits has four
places to do it.

`defer <statement>` runs that statement when control leaves the enclosing block, so the release is
written once, **next to the call that acquired the resource**:

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

Both exits ran the `defer`. `run(4)` reached the end of the function and `run(3)` left through the
`?` in the middle, and neither needed its own release code. The second kind of exit is the one most
easily forgotten.

Two rules make `defer` predictable. **Several in one block run in reverse order of registration**,
so they undo the setup in reverse. And **a `defer` runs only if control reached it**: it is a
statement, not a declaration, so one after an early return was never registered. Above, a failure
from `acquire` returns before the `defer` line, and nothing is released, which is correct because
nothing was acquired.

The scope is the **block**, not the function, which matters in a loop:

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

Go runs `defer` at function exit, so the same loop would hold both resources until the end and
release them together; a loop over ten thousand files can run out of descriptors that way. In sysl
each iteration is a block and releases its own resources.

`defer` is not a `finally`: **a trap runs nothing.** A broken invariant means the program's state is
already wrong, and cleanup code run against that state can write the corruption out. `defer`
releases resources; it does not recover from bugs, which are the subject of the rest of this page.

## Traps

A trap is the run-time response to a broken invariant, and its behaviour is fixed: **it aborts.**
There is no unwinding, no stack cleanup, no `catch`, and no exceptions.

```sysl
var xs = [1, 2, 3]

print(xs[5])
```

That program compiles, then stops when it runs, because the bounds check fails. No handler can catch
it and no `defer` runs: `defer` handles scope exits, and a trap is not an exit.

Traps come from the run-time safety checks the safe subset relies on: an out-of-bounds index, an
inverted or out-of-range slice, a failed checked conversion, integer division by zero, and a violated
`require` or `ensure` contract.

**Integer overflow does not trap**, unlike a Rust debug build. Arithmetic wraps at the declared
width, as [values](/tour/values/) showed; that is defined behaviour, not a broken invariant.

Aborting is the only option for two reasons. A kernel or embedded target has **no unwinding
runtime**: landing pads, a personality routine and per-frame cleanup tables are exactly what a
freestanding target lacks. And an abort is a single code path, whereas unwinding is a second,
invisible control-flow graph that every function would have to handle correctly.

What happens on a trap depends on the environment, not the language. A hosted program prints a
diagnostic and exits with a non-zero status; a kernel installs its own panic handler. The language
decides to stop; the environment decides what stopping does.

No panic can be recovered from, so a program that must survive bad input has to **avoid the
operation that traps**: check the bound, validate before dividing, and use the fallible conversion
rather than the checked one. Do this wherever untrusted input enters the program:

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

`Color(9)` would trap. `Color.try(9)` returns `None`, which this function turns into an error with a
reason, so an invalid byte from the wire becomes a value the program handles rather than a bug.

In summary:

- **No exceptions.** A recoverable failure is a returned value and a bug is an abort. There is no
  third, invisible control-flow channel.
- **No error codes by convention.** The failure is in the type and is checked, not an `int` a caller
  might forget to inspect.
- **No unwinding panic.** A trap is terminal.

`?` follows the ordinary reference-counting rules, which matters because it is the operator most
likely to carry a heap payload across a function boundary.

Unwrapping a `&T` success payload retains it, so the reference outlives the `Result` it came from
and is released exactly once. Propagating a `&T` error payload moves it through the early return
with its count intact.

Neither needs a special rule for `?`; both follow from retaining on aliasing and releasing at the end
of a scope. They are worth stating because hand-written C error returns tend to leak or double-free
at exactly these two points.

---

Next: [traits and generics](/tour/traits/): one implementation over many types, and what a bound
guarantees.
