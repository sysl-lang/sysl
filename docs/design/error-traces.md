# Error return traces — design

An **error return trace** is the list of places a failure travelled through on its way to the code
that reported it: the site that made the `Err`, and every `?` that handed it one frame further up.
Zig prints one when `main` returns an error, and it is the thing a `Result`-based language most
lacks next to exceptions — the report says *what* went wrong but not *where*. sysl takes it for 0.3.0.

**Decided** (user, 2026-10-19: "as written", with traces on at every optimization level and an
opt-out, § 2 row 6) and **built** for 0.4.0. Two findings decided the same day are part of it: **a
`@test` may answer with a `Result[unit, E]`**, whose `Err` fails the test with the error and its
trace, and **`unwrap`/`expect` of an `Err` show the error** (its `Display`, else its `Debug`) as well
as the trace. The reference is `reference/errors.md` § "Where a failure came from"; § 1 below is the
state before it, and § 5 records how it was built.

## 1. What exists, and the gaps (0.2.5)

| exists | where |
|---|---|
| `main() -> Result[unit, E]`, `E: Display`, reported by `sysl.main_result`: `error: <display>` on stderr, exit 1 | modules.md, `library/sysl/result.sysl` |
| `?` lowered as a tag test and an early `ret` in the failure arm, with `From` called inside that arm | `gen_try`, `emit_expr.sysl` |
| a bounds check names its `file:line:col`, a string per check in rodata; `bounds_locations = false` / `--no-bounds-locations` drops them | errors.md § What stopping looks like, packages.md |
| a module `var` per thread (`@thread_local`), refused on a freestanding target, where the ownership runtime keeps a plain module `var` instead | attributes.md § `@thread_local` |
| `unwrap` of an `Err`: `panic: unwrap of an Err value`, exit 1 — the error itself is not shown | probed |
| a `@test` may not return a `Result`: *"a '@test' function returns nothing … there is nothing to read a value with"* | probed |

Nothing named a trace exists in the reference, the compiler or the library. The probe (abridged:
`enum Fault` with `Missing(what: string)` and its `Display` sit above it; the blank lines are
dropped, the line numbers are the file's):

```sysl
lookup(k: string) -> Result[int, Fault] = Err(Fault.Missing(k))   // line 8

load(k: string) -> Result[int, Fault]
    defer print("load: defer")
    val n = lookup(k)?                                            // line 13
    Ok(n + 1)

main() -> Result[unit, Fault]
    defer print("main: defer")
    val v = load("port")?                                         // line 20
    print(v)
    Ok(())
```

prints `load: defer` and `main: defer` on stdout, `error: missing port` on stderr, and exits 1. Two
`?` and one `Err(...)` carried the failure; none of the three appears in the report.

## 2. Decisions

| # | question | recommendation | why, in one line |
|---|---|---|---|
| 1 | what is recorded | **A `u32` site id**, indexing a table of `file:line:col in <function>` strings laid in rodata, exactly as the bounds locations are. Not return addresses | prints on a board with no symbolizer, no DWARF and no `atos`; identical across optimization levels and LTO, which inlining makes untrue of addresses |
| 2 | which sites record | **The origin and every hop.** A *hop* is a `?` taking its failure arm (recorded in that arm, after `From`). An *origin* is an exit whose result is a failure constructor the compiler can see — `Err(...)` (or a `return Err(...)`) as a tail, an `if` arm or a `match` arm. A `return r` of a computed value records nothing | every record sits on a failure path the compiler already branches to, so the success path pays nothing |
| 3 | where it lives | **One fixed ring per thread: 32 ids and a count**, 132 bytes. `@thread_local` on a hosted target; a plain module `var` on a freestanding one, the ownership runtime's precedent | no allocation, so it holds under `no alloc`; 32 frames covers any real propagation chain |
| 4 | overflow | **Keep the first 32, keep counting**, and print `… and N more` | the origin end is where the bug is; the `main` end is already known |
| 5 | when a handled error leaves the ring | **Pruning, as Zig does**: before a call whose `Result` is not consumed by `?` or returned, the count is saved; when the innermost statement holding the call completes, it is restored, and the statements around it do nothing. A `return` from inside the statement is not completing it, so `Err(e) -> return Err(Wrap(e))` keeps the inner chain | a `match` that handles an error must not leave its frames in front of the next failure's |
| 6 | when it is on | **On by default at every optimization level; `error_traces = false` in the manifest or `--no-error-traces` removes it entirely** | bounds checks set the precedent: safety-adjacent reporting stays on in optimized builds, and a board short of flash opts out by name |
| 7 | cost when off | **Zero**: no ring, no table, no stores, no save/restore; the `main_result` report is the 0.2.5 one | required |
| 8 | cost when on | **Failure path**: one store and an increment per record. **Success path**: a load and a store per *handled* call (5), nothing per `?` | the only hot-path cost is pruning, and it is one word in cache |
| 9 | `Option` `?` | **Does not record** | `None` is absence, not failure; it is the normal flow of every parser, and no report prints an `Option` |
| 10 | `From` | **Records the `?` site, never the conversion** | the conversion is part of the hop, not a frame |
| 11 | closures, nested functions | **Ordinary functions**: a `?` in a closure body records, named `<enclosing>.<closure>` in the table | no special case |
| 12 | `async` | **The ring of the thread running the task.** `await t?` is a hop at the await site; entries from another task's handled error may interleave and are pruned by 5 | a per-task ring is 132 bytes a `Task` for a rare case (decision 4 below) |
| 13 | printing at the top | **`sysl.main_result` prints the trace under its `error:` line**, origin first | the one place that knows the program is over |
| 14 | printing on a forcing member | **`unwrap`/`expect` of an `Err` print the trace after the panic line** | a test that fails on an `Err` shows where it came from; a `@test` answering with a `Result` reports its `Err` the same way |
| 15 | explicit access | **`sysl.debug.error_trace() -> ErrorTrace`** (a copy: `len`, `dropped`, an iterator of `(file, line, col, function)`), **`print_error_trace(w: *Writer)`** and **`clear_error_trace()`** | a server loop logs and clears per request; a board writes it to a UART |
| 16 | freestanding output | **`sysl_error_trace_report`, a weak function**, as `sysl_bounds_fail` is; the default on a freestanding target writes nothing | the board decides where text goes |

## 3. How it reads

The program in § 1, built with traces on (stderr; the line numbers are the abridged listing's):

```text
error: missing port
error return trace (origin first):
  main.sysl:8:43 in lookup
  main.sysl:13:22 in load
  main.sysl:20:25 in main
```

A loop that handles each failure and logs the trace:

```sysl
import sysl.debug.{print_error_trace, clear_error_trace}

for req in queue
    serve(req) match
        Ok(_) -> ()
        Err(e) ->
            eprints(s"request failed: $e\n")
            print_error_trace(stderr())

    clear_error_trace()
```

## 4. Against real toolchains

| | what is recorded | when on | printed |
|---|---|---|---|
| **Zig** | return addresses at each `try` failure and `return error.X`, into a `StackTrace` reached by a hidden pointer; pruned when an error is handled | Debug and ReleaseSafe | when `main` returns an error; `@errorReturnTrace()` |
| **Rust** | nothing at `?`; `std::backtrace::Backtrace` captured where a library (`anyhow`, `eyre`) builds the error | `RUST_BACKTRACE` at run time | by the error's `Debug` |
| **Swift** | nothing for `throws` | — | — |
| **Go** | nothing; `fmt.Errorf("…: %w", err)` builds the chain by hand | — | the wrapped message |
| **C++** | `std::stacktrace::current()` (C++23) where the program asks | always available | by hand |
| **sysl** | a static site id at the origin and each `?` hop; pruned when handled | every level, unless switched off | `main_result`, `unwrap`/`expect`, `sysl.debug` |

sysl takes Zig's *what* (every hop, not one capture point) and the bounds locations' *how* (static
strings, not addresses), because the second must work on a Pico with nothing to symbolize against.

## 5. Implementation plan

**As built**, three things differ from the plan below, none of them in what a program sees:

- **A record is the address of the site's constant**, not a `u32` index into a per-module table:
  an index would have to be numbered across the standard module's artifact and every codegen unit,
  and the address is already unique. The constant is the one the bounds locations lay down
  (`c_text_of`). The ring is therefore 32 pointers and a count — 132 bytes on a 32-bit board, 264 on
  a 64-bit host.
- **The ring is C** (`library/sysl/sys/trace.c`): `_Thread_local` on a hosted target, a plain object
  on a freestanding one (`__STDC_HOSTED__`), with `sysl_error_trace_push`/`_mark`/`_reset` called by
  the emitted code and `_len`/`_site`/`_clear`/`_report` read by the library. With traces off the
  compiler answers each library read where it is called (`trace_seam_off`), so the C is never linked.
- **Pruning is two calls per handling statement** (mark before, reset after), not an inline load and
  store: a call reaches the right thread's ring inside a coroutine resumed on another thread, which a
  thread-local address cached across an `await` would not. `break` and `continue` reset to the first
  mark opened inside the loop they leave.

The plan as it was written:

1. **Table and ring.** A `site_id` per recorded edge, interned per module into one rodata array of
   strings; the ring and count in `library/sysl/debug/trace.sysl`, `@thread_local` where the target
   has it. `--no-error-traces` / `error_traces` read beside `bounds_locations` (`manifest`, `driver`).
2. **Records.** In `gen_try`'s `try.fail` block, before `release_all`: `trace_push(site)`. Origins:
   the analyzer marks a tail / `return` whose value is a failure constructor (`expr_flow`), and the
   emitter pushes on that edge.
3. **Pruning.** The analyzer marks each call of a `Result`-typed callee not under `?` and not a
   returned value; `emit_stmt` saves the count before the statement and restores it on the
   statement's normal completion.
4. **Reports.** `main_result`, `unwrap`, `expect` and the `sysl.debug` functions; the weak
   `sysl_error_trace_report`.
5. **Docs.** errors.md § "Where a failure came from"; packages.md § the key; the CLI page.

**Tests: prefix `ert_`.** Each builds a program as a child and compares its **stderr** exactly, the
way `tests_bfm_bounds_message.sysl` pins the bounds report: the three-frame trace above; a `From` hop;
a pruned handled error followed by a fresh one (only the second shows); overflow at 33 hops
(`… and 1 more`); a closure frame; `unwrap` printing the trace; `--no-error-traces` giving the 0.2.5
report and a module with no `error_trace` symbol (`nm`); a `thumb-freestanding` build linking no TLS.

## 6. Decisions for the user

All seven were decided as recommended (2026-10-19); 6 also lets a `@test` answer with a `Result`.

1. **Static site ids, not return addresses.** Recommend yes — they print on a board and survive
   inlining; the cost is one string per `?` site in the image, which `error_traces = false` removes.
2. **On by default at every optimization level**, switched off by `error_traces = false` /
   `--no-error-traces`. Recommend yes, by the bounds-check precedent. The alternative is Zig's "off
   at `-O3`/`-Os`", which sysl has no release mode to hang on.
3. **Pruning at statement end (decision 5).** Recommend yes. Its known gap: `val r = f()` on one line
   and `return r` on a later one loses `f`'s frames. The alternative, no pruning and a reset at each
   origin, costs nothing on the success path but drops the inner chain whenever a `match` re-wraps.
4. **Async: the thread's ring, not a ring per `Task`.** Recommend the thread's ring for 0.3.0.
5. **`Option` `?` does not record.** Recommend yes.
6. **`unwrap`/`expect` of an `Err` print the trace** (and, while there, the error itself when `E:
   Display`, which they do not today). Recommend yes — it is the only report a failing test gets.
7. **Ring size 32, first-kept.** Recommend yes; a manifest key for the size is not proposed.
