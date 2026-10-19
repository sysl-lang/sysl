# `errdefer` — design

`defer` releases what a block took on every way out. A function that builds something in steps
needs the other half: **undo the steps already done, but only if the function fails** — close the
file it opened when the header it then writes cannot be written, give back the buffer when the parse
that would have kept it gives up. Written by hand, that is a flag set before the last `Ok` and a
`defer if !done then …`. Zig spells it `errdefer`; sysl takes it for 0.3.0.

Nothing here is built. Every block marked **proposed** is syntax or a diagnostic that does not
exist; every other block is the installed `sysl 0.2.5`'s real output.

## 1. What exists, and the gaps (0.2.5)

| exists | where |
|---|---|
| `defer <stmt>`, block-scoped, run on every ordinary exit including a `?` failure arm, LIFO, before the block's ARC releases; a trap runs nothing | statements.md § `defer` |
| a jump that leaves a deferred statement is refused, and so is a `?` in one: *"a deferred statement runs while its block is being left, so a '?' in it has nowhere to return to"* | probed |
| the emitter keeps a per-scope list of deferred statements (`defers`, `defer_marks`) and lays it down at each edge that leaves the block; `?` reaches it through `release_all` in `gen_try`'s failure block | `emit_arc.sysl` `defer_stmts`, `emit_expr.sysl` `gen_try` |
| `defer` on a `?` exit: `defer print("first: defer")` above `val x = pick(n)?` prints on both the `None` and the `Some` path | probed |

`errdefer` itself does not parse: `errdefer print("undo")` → `error: newline expected` at `print`.
Nothing in the org names an identifier `errdefer`.

The workaround it replaces, probed:

```sysl
step(ok: bool) -> Result[int, string] = if ok then Ok(1) else Err("step")

build(ok: bool) -> Result[int, string]
    val f = step(true)?
    var done = false

    defer if !done then print("undo", f)

    step(ok)?
    done = true
    Ok(f)

main()
    print(build(true))
    print(build(false))
```

```output
Ok(1)
undo 1
Err(step)
```

A flag, a negated test, and an assignment that must sit immediately before every successful exit —
forgetting it on one runs the undo on success.

## 2. Decisions

| # | question | recommendation | why, in one line |
|---|---|---|---|
| 1 | spelling | **`errdefer <stmt>`, a keyword** beside `defer` | one word, as `defer` and `loop` are; Zig's spelling, which a reader who knows the idea already knows. `defer on error` reads as a `defer` of an expression named `on` |
| 2 | what counts as a failure exit | **The function returning its failure variant**: `Err` for a `Result`, `None` for an `Option`. That is a `?` taking its failure arm, or a `return`/tail whose value is the failure variant | the statement is about the *function's* outcome, which is the one thing a caller sees |
| 3 | `None` | **Counts**, in an `Option` function | `?` treats the two channels alike, and a parser that consumed input and answers `None` needs the rollback as much |
| 4 | a computed result (`return r`, a tail `r`) | **A tag test on that exit edge**, emitted only where an `errdefer` is pending; a constructor (`Err(...)`, `Ok(...)`, `None`, `Some(...)`) or a `?` arm is decided statically and costs nothing | correct for every exit, and free on the common ones |
| 5 | traps, `@abandons` | **Run nothing** — a trap runs no `defer` either; an `@abandons` call runs its caller's `defer`s but is not a failure | a trap restores no invariant; an abandoned frame did not fail |
| 6 | seeing the error | **`errdefer e -> <stmt>`** binds the failure payload being returned, read-only, in the function's own error type (after `From`). Refused in an `Option` function, which has no payload | logging and rollback both want it; Zig's `errdefer \|e\|`, spelled with sysl's `->` |
| 7 | ordering with `defer` | **One LIFO stack**: `defer` and `errdefer` interleave in source order, last written runs first | the undo of a later step must run before the undo of an earlier one, whichever kind each is |
| 8 | jumps inside it | **`defer`'s rule, word for word**: no `return`, no `break`/`continue` leaving it, no `?` | it runs while the function is already leaving |
| 9 | a function that cannot fail | **Refused**: a function whose result is not a `Result` or an `Option` | an `errdefer` there would never run, and silence would hide that |
| 10 | block scope | **The block it is written in, as `defer`**: it runs when the function fails while control is inside that block. Leaving the block any other way — its end, `break`, `continue` — discards it | Zig's rule; a loop body's `errdefer` undoes that iteration's step only if the failure happens during that iteration |
| 11 | closures, nested functions, `async` | **The innermost function's result decides**; an `async` body's failure exit is its task ending in failure | an `errdefer` belongs to one body, as a `defer` does |
| 12 | in `main` | **Allowed when `main` returns a `Result`**; runs before `main_result` prints the report, as `defer` does (probed: `main: defer` prints, then the program exits 1) | no special case |

## 3. How it reads (**proposed**)

The probe in § 1, rewritten; it would print the same three lines:

```sysl
build(ok: bool) -> Result[int, string]
    val f = step(true)?
    errdefer print("undo", f)

    step(ok)?
    Ok(f)
```

With the error in hand, and with `defer` interleaved:

```sysl
transfer(a: *Account, b: *Account, n: int) -> Result[unit, Bank]
    lock(a)
    defer unlock(a)

    a.debit(n)?
    errdefer e ->
        a.credit(n)
        log(s"transfer rolled back: $e")

    b.credit_checked(n)?
    Ok(())
```

If `credit_checked` fails, the order is: the `errdefer` (credit back, log), then `unlock(a)`. If
`debit` fails, the `errdefer` was never reached and only `unlock(a)` runs.

The diagnostic for decision 9 (**proposed text**, in the style of `defer`'s):

```error
'errdefer' runs when 'count' fails, and 'count' returns int, which cannot fail — use 'defer'
```

## 4. Against real toolchains

| | form | sees the error | scope |
|---|---|---|---|
| **Zig** | `errdefer stmt`, `errdefer \|e\| stmt` | yes | block; runs when the function returns an error |
| **D** | `scope(failure) stmt` | no | block; runs when an exception leaves it |
| **C++** | `std::experimental::scope_fail` (Library Fundamentals TS v3); RAII guards with a `dismiss()` | no | object lifetime; exceptions |
| **Rust** | none; a `Drop` guard disarmed with `mem::forget` (`scopeguard::guard`) | no | value |
| **Go** | `defer func() { if err != nil { … } }()` over a named result | yes, by hand | function |
| **Swift** | none; `defer` only | — | block |
| **sysl (proposed)** | `errdefer stmt`, `errdefer e -> stmt` | yes | block; runs when the function returns its failure variant |

## 5. Implementation plan

1. **Parse.** `errdefer` joins the statement keywords (`lex_tables`, `parse_stmt`); `TDefer` gains a
   kind (`Always`, `OnFailure(binder: Option[string])`) rather than a sibling node, so every pass that
   walks a defer — the jump check, the `?` check, `walk_typed`, the printers — sees it unchanged.
2. **Check.** Decision 9's refusal in `stmt.sysl`; decision 6's binder typed as the function's error
   type, refused in an `Option` function.
3. **Emit.** `defers` holds `(stmt, kind)`. At an edge: a `?` failure arm lays both kinds; a
   statically-successful exit and a block's own end/`break`/`continue` lay `Always` only; a computed
   return lays each run of `OnFailure` statements under one tag test on the value being returned. The
   binder reads the payload already extracted in `gen_try` (`failed`), or extracts it at the tag test.
4. **Docs.** statements.md § `errdefer` after § `defer`, with the probed blocks; errors.md links it
   from § `?`.

**Tests: prefix `edf_`.** Runs on: a `?` failure, a `return Err`, a tail `Err`, a computed `return r`
holding an `Err`, a `None` in an `Option` function. Does not run on: success, a trap
(`should_trap`), a loop body's `break`/`continue`/end. Interleaving with `defer` (order printed). The
binder after a `From` conversion sees the converted value. Refusals: a non-failing function, a binder
in an `Option` function, `return`/`?`/`break` inside. A counted-heap run (`SYSL_COUNT_ALLOCS=1`)
showing the binder takes no count. A `thumb-freestanding` build under `no alloc`.

## 6. Decisions for the user

1. **Spell it `errdefer`.** Recommend yes; the alternatives are `defer on error` and D's
   `scope(failure)`.
2. **`None` counts as a failure exit in an `Option` function.** Recommend yes; Zig has no optional
   case to copy, and `?`'s symmetry argues for it.
3. **The binder, spelled `errdefer e -> stmt`.** Recommend yes, in 0.3.0. The alternative is to ship
   without it and add it later; the spelling is the open part.
4. **A computed return is tag-tested at the edge (decision 4).** Recommend yes — the alternative,
   counting only statically visible failures, silently skips the rollback on `return r`.
5. **Refuse `errdefer` in a function that cannot fail.** Recommend yes.
6. **Block scope, discarded on a non-failing exit from the block (decision 10).** Recommend yes —
   Zig's rule, and the one that matches `defer`.
