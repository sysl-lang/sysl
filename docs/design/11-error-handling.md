# Design Decisions: Error Handling

**Status:** ratifies the error model the implementation already carries — `Option`, `Result`,
the postfix `?`, the prelude's combinators up to `unwrap`/`expect` (§8), and the trapping runtime
checks — and settles the two decisions it left implicit: **traps abort** (no unwinding, no
`catch`), and **`?` requires an exact error-type match** for now, with type-converting `?` designed
but deferred. The recoverable half rests on
`09-enums-and-patterns.md` (both types are generic enums) and `10-generics.md` (`?` unwraps a
generic enum); the trapping half rests on `03-memory-model.md` (the safety checks that trap) and
`capabilities.md` (what a trap does depends on the environment).

The organizing decision is that failure travels on **two separate channels**, and which one a
given failure uses is a real design choice at each API — never a coin toss:

- **A recoverable failure is a value** — `Result[T, E]` or `Option[T]` in the return type,
  propagated with `?`. The caller *must* engage with it; it is part of the signature.
- **A bug is a trap** — an out-of-bounds index, an invalid cast, a broken contract. It is not a
  value, not catchable, and aborts the program (or, in a kernel, enters the panic handler).

Keeping these apart is what makes the type signature honest: a function that returns
`Result[T, E]` tells you it can fail in a way you handle, and a function that returns plain `T`
tells you the only way it "fails" is if the program is already wrong.

---

## 1. `Option[T]` and `Result[T, E]`

The two recoverable-failure types are **ordinary generic enums** from the prelude, not compiler
built-ins (`09 §4`):

```
enum Option[T]                 enum Result[T, E]
    Some(value: T)                 Ok(value: T)
    None                           Err(error: E)
```

- **`Option[T]` is for absence** — a value that may or may not be there, with no reason attached:
  a missing map key, the `next` of a list tail, the result of a search. It is also what a `weak`
  reference degrades to (`03`) and what a fallible constructor like `char.try` returns (`00 §1`).
- **`Result[T, E]` is for failure with a reason** — an operation that can go wrong in a way the
  caller wants to inspect or report: parsing, I/O, validation. `E` is whatever type carries the
  reason (a string, an error enum, a `&Fail`).

Both are constructed and destructured like any enum — `Some(5)`, `Ok(n / 2)`, `Err("odd")`,
taken apart by `match` — and both take their type arguments by inference, including the
bare-`None`/`Ok`-from-return-type cases (`10 §4`): `None` alone gets its `T` from context, and
`Ok(n)` in a `Result[int, string]`-returning function gets its `E = string` from the return
type.

## 2. Which channel — the policy

The line between "return a `Result`" and "trap" is **"could correct calling code ever hit
this?"**

- **Yes — it's an expected outcome of valid use → `Result`/`Option`.** The input came from
  outside the program (a file, a socket, a user), or the operation legitimately may not succeed
  (a key that might be absent). A correct caller still meets this case, so the type must force
  the caller to handle it.
- **No — only a bug reaches it → trap.** Indexing past the end of an array, converting an
  out-of-range integer to a `char`, dividing by zero, violating a `require` precondition. A
  correct program never does these; reaching one means the program is already wrong, and the
  honest response is to stop, not to thread an error value through code that had no reason to
  expect one.

This is the Rust `Result`-vs-`panic` split, and the payoff is the same: signatures stay clean
(no `Result` smeared across functions that cannot meaningfully fail), while the failures that
*are* real are impossible to ignore because they are in the type.

## 3. The `?` operator

`?` is postfix on an `Option` or `Result`, and it is sugar for the most common `match`: **unwrap
the success, or early-return the failure.**

```
quarter(n: int) -> Result[int, string]
    var h = half(n)?               // Ok(v) → v ;  Err(e) → return Err(e) from quarter
    half(h)
```

- **On `Result`:** `Ok(v)` evaluates to `v`; `Err(e)` returns `Err(e)` from the enclosing
  function immediately.
- **On `Option`:** `Some(v)` evaluates to `v`; `None` returns `None` from the enclosing
  function immediately.

Two rules make this well-defined:

- **The enclosing function's return type must match the channel.** `?` on a `Result` is only
  legal inside a function returning `Result` (with a matching `E`, §4); `?` on an `Option` only
  inside a function returning `Option`. Using `?` where the return type cannot carry the failure
  is a compile error — the early return has to have somewhere to go. The two channels do not
  cross: an `Option`'s `?` cannot early-return from a `Result`-returning function, and vice
  versa.
- **`?` composes as an expression.** Its unwrapped value flows straight into the surrounding
  expression, so `Ok(mk()?)` and chained hops `head?.next?` (written `var h = head?; var nx =
  h.next?`) work by the ordinary expression rules — `?` is not a statement.

## 4. `?` requires a matching error type (conversion deferred)

Today `?` propagates an `Err(e)` **only when the callee's error type is exactly the caller's**:
a `Result[_, string]` `?`-ed inside a `Result[_, string]`-returning function, a `Result[_,
&Fail]` inside a `Result[_, &Fail]`-returning one. There is **no error conversion** — a function
whose own error type differs from a callee's must convert the error explicitly (there is no
implicit widening).

This is the shipping behavior and the baseline the spec commits to. The known ergonomic cost —
a program with its own `AppError` cannot `?`-propagate a library's `IoError` without a manual
conversion at each call — is real, and the eventual answer is **`From`-style conversion**: `?`
inserts a conversion from the callee's `E` to the caller's `E` when a conversion trait connects
them, exactly as Rust's `?` calls `From::from`. That is **designed but deferred** (`§ Open a`): it
needs the conversion trait and the stdlib trait layer, and it is additive — turning it on later
does not invalidate any exact-match `?` written before it. Until then, exact match keeps the
feature simple and its failures legible.

## 5. `?` and the memory model

`?` obeys ARC with no special rule, and — because it is the operator most likely to move a
heap payload across a function boundary — this is where the discipline is load-bearing (all
tested):

- **Unwrapping a `&T` success payload retains it past the wrapper.** `var p = mk()?` on a
  `Result[&Point, string]` yields a `&Point` that outlives the `Result` it came out of, retained
  on bind and released once when it dies — no leak across a long loop, no double-free.
- **Propagating a `&T` error payload moves it through the early return.** `Err(Fail(404))`
  `?`-ed from a `Result[int, &Fail]` carries the `&Fail` into the caller's own return with its
  count intact, freed exactly once on whichever path consumes it.

These fall directly out of `03`'s retain-on-alias / release-at-scope-end; they are called out
because a hand-rolled C error-return leaks or double-frees at exactly these boundaries, and not
doing so is the safety claim in action.

## 6. Traps — abort, not unwind

A trap is the runtime response to a **broken invariant**, and its semantics are now settled:

**A trap aborts. There is no unwinding, no stack cleanup, no `catch`, no exceptions.** When a
check fails the program stops; it does not run destructors up the stack and it cannot be
intercepted and resumed. This is deliberate and it is the only defensible choice for the
language's targets:

- **A kernel and an embedded target have no unwinding runtime.** Landing pads, a personality
  routine, and per-frame cleanup tables are exactly the machinery a freestanding `no alloc`
  target does not have and does not want. An abort needs none of it.
- **Determinism.** An abort is one code path; unwinding-through-arbitrary-frames is a second,
  invisible control-flow graph that every function would have to be correct under. Removing it
  removes a whole class of "is this exception-safe?" reasoning — the same simplification the
  memory model makes by removing move semantics.

**What a trap *does* is an environment fact (`capabilities.md`).** Under the `os` capability a
hosted program prints a diagnostic (the check that failed and where) and exits non-zero; a kernel
installs its own panic handler and enters it. The *decision to stop* is the language's; the
*action on stopping* is the environment's.

**Trap sources** are the runtime safety checks the safe subset relies on: an out-of-bounds array
or slice index, an inverted or out-of-range slice range, a checked cast that fails (`char(u)` on
an invalid scalar, `Color(n)` on an undeclared discriminant, `09 §2`), an integer divide-by-zero,
and a violated design-by-contract `require`/`ensure` where contracts are in force. These are the
checks that make the safe subset segfault-proof (`03`), and like the bounds checks they are
**strippable** for a release build that accepts the risk (a `--no-contracts`-style removal), on
the same footing as C's `assert` compiled out by `NDEBUG`.

> **Cross-doc note.** Whether *integer overflow* is a trap source or defined wrapping is a
> scalar-types decision (`00 §5` currently specifies wrapping at the declared width), not an
> error-handling one. This chapter lists the checks that trap today; the overflow question is
> settled in `01`, and the README's "integer-overflow safety" wording should be reconciled with
> it there.

## 7. What is deliberately absent

- **No exceptions / `throw` / `try`-`catch`.** Recoverable failure is a returned value; a bug is
  an abort. There is no third, invisible control-flow channel.
- **No error return codes by convention.** The failure is `Result`, in the type, checked — not
  an `int` the caller might forget to inspect.
- **No `panic`-that-unwinds.** A trap is terminal. A program that wants to "recover" from what
  would trap must instead not do the trapping thing — check the bound, use the fallible cast
  (`char.try`, `Color.try`), validate before dividing — turning the bug back into a `Result` at
  the point where untrusted input enters.

## 8. The prelude's combinators, and how `unwrap` stops the program

The conveniences on `Option` and `Result` are **ordinary members in the prelude** (`09 §4`,
`10 §Open c`), not compiler knowledge. The *total* ones ask a question or supply a fallback:

- `Option`: `is_some()`, `is_none()`, `unwrap_or(default)`
- `Result`: `is_ok()`, `is_err()`, `unwrap_or(default)`

The *forcing* ones hand over the payload and stop the program when there is none:

- `Option`: `unwrap()`, `expect(msg)` — on `None`
- `Result`: `unwrap()`, `expect(msg)` — on `Err`

**They are written in sysl, with no compiler support of their own**, and that is the point worth
recording: it is what keeps "a bug is a trap" from meaning "the compiler must know the name of
every way to trap". Two pieces make it possible. The diverging arm has a type — `never` (`00 §11`)
— so `None -> exit(1)` sits beside `Some(v) -> v` and the `match` still has the payload's type. And
the departure itself is an `extern` (`12 §1`): the prelude declares `exit(code: int) -> never`, so
stopping is a call, not an intrinsic.

```
unwrap(self) -> T = match self
    Some(v) -> v
    None ->
        print("panic: unwrap of a None value")
        exit(1)
```

This is §6's split in action: **the decision to stop is the language's, the action on stopping is
the environment's.** On a hosted target the action is what §6 specifies — print a diagnostic, exit
non-zero — and it is reached through the C library's `exit`, which flushes what was printed on its
way out (`abort` would not have to). A freestanding target supplies its own departure and the
prelude's `exit` is simply never called. The compiler's *own* checks — a bounds test, a failed cast
— still trap through `llvm.trap` and print nothing; reconciling those two so that every stop says
why is `§ Open c`.

Nothing here costs a program that does not use it: the enums are generic, so a member exists only
where a call asks for one, and an `extern` nothing reaches is not declared in the output.

---

## Open (not yet decided)

- **a. `From`-style error conversion in `?`.** Let `?` convert the callee's `E` to the caller's
  `E` through a conversion trait, so cross-error-type propagation needs no manual step (§4).
  Additive over the exact-match baseline; waits on the stdlib trait layer.
- **b. `Result`/`Option` combinator library.** `map`, `and_then`, `or_else`, `ok_or`, and the
  rest are stdlib surface, designed when the standard library is (they are not language
  features). The *forcing* ones are settled and shipped (§8); these are the transforming ones.
- **c. The panic-handler interface.** The exact signature a kernel installs to receive a trap
  (message, source location, register state) and whether a hosted program can customize the
  abort message are `capabilities.md`/runtime concerns, not settled here.
- **d. Error context / chaining.** Attaching context as an error propagates (an
  `anyhow`/`?`-with-context style), and whether the language or only the library offers it, is
  left open pending real multi-layer error code.
