# Testing Architecture

**Status:** decided. A compiler-architecture doc (like `front-end.md`), not a language-spec
chapter. This is the "design the test architecture first" the restart called for — one
backend means one expected result per test, no `--backend` matrix, no "passes on interp /
fails elsewhere."

Tests fall into **three tiers**, by *what they exercise* and *how they run*.

## Tier 1 — pure Scala, no execution (the workhorse)

Everything checkable *statically*, run as plain ScalaTest with no external toolchain:

- **lexing** — source → tokens;
- **parsing** — tokens → AST;
- **analysis** — AST → typed AST, plus the full set of **error cases** (a bad program must be
  rejected with the right diagnostic);
- **IR-shape checks** — codegen emits **textual LLVM IR**, and the test asserts on that text
  *without running it* (the IR contains / matches the expected pattern). These catch codegen
  regressions cheaply and are pure-Scala because the IR is just a string.

Tier 1 is the **fast feedback loop** — millisecond runs, no LLVM toolchain. Since the restart
has **no interpreter**, this static tier carries the iteration speed the interpreter used to
provide. Most lexer / parser / analyzer / codegen correctness is nailed here.

## Tier 2 — compile to LLVM, execute, check output (Scala-authored)

End-to-end tests that run the full pipeline on a sysl snippet and check runtime behavior
(stdout, exit code). **These test the *compiler*** — minimal sysl programs targeting a
specific codegen feature, authored in Scala (as fixtures or inline source), compiled, run,
asserted.

## Tier 3 — sysl `@test` functions, compiled + executed (sysl-authored)

sysl's **own test framework**: `@test`-annotated functions written *in sysl*, discovered and
run by a `sysl test` CLI command. **These test *sysl code*** — the standard library and
language behavior — and are the executable-spec / conformance corpus.

**Built.** What follows is what it is, rather than what it should be.

### The attribute

```
@test
adds_two() =
    assert(add(1, 1) == 2, "one and one")

@test("an empty slice has no first element")
first_of_empty() = …

@test(should_trap)
a_broken_promise() = …

@test(should_trap: "past the end")
an_index_past_the_end() = …
```

`@test` was the language's first attribute and is no longer its only one — `@tailrec`, `@pure` and
`@ghost` are the other three a declaration takes, and `@no_<capability>`, `@requires(...)` and
`@link("...")` are the file-header three (`13 §4`). A word after `@` that is none of them is answered
by name rather than as grammar, and the message says which of the two lists it was looking in. The
attribute goes on its own line above an ordinary function declaration, which may still be `private`.

What stays true is that this is *not* a general extension mechanism: the set is closed and each
member was added deliberately. The two that would make it general are `packed` (`15 §1`) and the
alignment attribute `00 § Open` wants, and neither is designed.

**A conditional-compilation directive starts with `#` instead** (`targets.md § Conditional
compilation`), and the two never meet: a directive sits at the margin and is gone before the lexer
runs, while an attribute is indented with the declaration it is on and reaches the grammar as one.
**`#test` is refused by name**, because `#` is the sigil a reader arriving from Rust or C reaches for
first, and being told the annotation is written `@test` is more use than being told a directive word
is unknown. A `@test` inside a gated-out branch is simply not there, so a test build has the tests its
target has.

**A test is an ordinary function with a caller nothing else has.** No parameters, no result,
not generic — all three are the same requirement from different sides, since the runner calls
it with nothing and reads the answer off whether it returned. They are checked at the
attribute, because the function is a perfectly good function and it is `@test` that made a
promise about it.

**A test passes by returning.** That is the whole protocol, and it is what lets a test assert
in the language it is testing rather than in a framework: a broken `require`, a bounds
violation, `unwrap` of a `None` — each ends the process, and none of them had to know it was
running under a test. `should_trap` inverts the reading, for a test whose subject *is* the
check; with a string it additionally requires that the run printed it, which is what tells a
trap from the *right* trap. A silent trap satisfies `should_trap` and can satisfy no string —
`llvm.trap` raises a signal and says nothing.

**A test has one caller and the program is not it.** Calling one is refused where the call is
written. Every build but `sysl test` drops the definition, so the call would compile and fail
at the link; work two tests share belongs in an ordinary function they both call.

### What is dropped, and when

`sysl run`, `sysl build`, `sysl emit-llvm` and `sysl build-lib` all drop the tests, and drop
them **after** analysis — so a `@test` that does not compile is an error in a build that would
never have run it, and a module's capability clause (`13 §4`) reaches its tests like any other
member. That ordering is what lets a test sit beside what it tests: a library's tests do not
travel in the library, a program's do not run when it runs, and neither stops being checked.

A helper only a test calls leaves with it, because it becomes unreachable and pruning notices;
a helper the program also calls stays, because the program still calls it.

### The runner

```
sysl test <path>
sysl test <path> --filter <text>
sysl test <path> --fail-fast
```

**One build, one process per test.** The tree is compiled once, into a binary whose entry point
takes a test's name and runs that test alone — the program's own statements and its `main` are
not run, though its module-level `val`s are still filled, since a test reads a module's storage
like any other function. The runner then starts that binary once per test.

The process per test is not a cost being tolerated; it is the mechanism. A test that fails does
so by ending its process, so a run that shared one would report the first failure and nothing
after it. The compile is the slow half and there is only ever one of it.

Exit status is 0 iff every test that ran passed. A tree with no tests, and a filter that matched
none of the tests there are, both exit 0 and say which happened.

### `assert` and `panic`

Both are in the standard module (`lib/sysl/check.sysl`), and the tests needed them: `require`
is a promise about a *call*, checked on entry, and a test's fifth statement has no contract to
hang a claim on. They stop the program the way `unwrap` does — a line naming what happened,
then the hosted exit — rather than with `llvm.trap`, because a check a *program* makes is one
the compiler cannot see, and saying which one is the whole point of it.

**`assert`'s message is optional**, and used to be required. The reason it was required was that a
failure could not say *where* it happened, so a message was the only thing distinguishing one
assertion from another — which made the message a workaround, and `check.sysl` said as much in the
words of a principle. Both functions now take `file: string = __FILE__, line: long = __LINE__` and
report the caller's line (`reserved-identifiers.md`), so `assert(x == 2)` is a complete assertion. A
message is still worth writing where it says something the condition does not; it is no longer
carrying the whole burden of identifying the check.

The location is composed with `prints` and `printi` rather than an interpolated string, because
building a string makes heap storage and an assertion is exactly what a module under `@no_alloc`
wants most.

**Summary:** Tier 1 = static (fast, most tests, including IR-shape); Tier 2 = the compiler's
own run-it tests (Scala-authored); Tier 3 = the language's test framework (sysl-authored).

## Build order

Tests accrete stage by stage down the pipeline:

```
lexer → parser (AST) → analyzer (typed AST) → LLVM codegen → sysl @test runner
  T1        T1               T1                  T1 + T2            T3
```

## Execution infrastructure (Tier 2/3)

Two decisions the run-it tiers force, settled now:

- **Codegen emits textual LLVM IR** (`.ll`), not via an in-memory LLVM-C binding. Simplest and
  most portable (no native bindings), and it makes the Tier-1 IR-shape checks trivial — you
  assert on the emitted string.
- **Tests execute through `clang → native`**, and that is worth stating plainly because this
  document used to record the opposite as settled. The plan was `lli` / ORC-JIT for speed, with
  `clang → native` as the "real" build; what exists is `Toolchain.build`, which writes a `.ll`,
  invokes `clang`, and runs the executable, and every Tier-2 helper goes through it. `lli` appears
  nowhere in the compiler or the suite.

  The motivation for the JIT path stands and is unspent: a run-it test currently pays a full
  `clang` invocation, which is most of what a suite of a few thousand costs. It runs the *same*
  IR, so it is one semantics either way, and a program has been checked to run under `lli`
  correctly — including a 128-bit divide, and a trap, which kills `lli` noisily while still
  yielding status 133. Adopting it is an available speed-up rather than a design question; the
  only environment dependency is the LLVM toolchain that is already required. On the aarch64 dev
  machine, `lli` and `clang` both target aarch64 natively.
