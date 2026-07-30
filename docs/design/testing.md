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

## Tier 3 — sysl `#test` functions, compiled + executed (sysl-authored)

sysl's **own test framework**: `#test`-annotated functions written *in sysl*, discovered and
run by a `sysl test` CLI command. **These test *sysl code*** — the standard library and
language behavior — and are the executable-spec / conformance corpus. Tier 3 is itself a
*feature to build* (the `#test` attribute + the runner), so it lands **after** codegen works
and Tier 2 is green.

**Summary:** Tier 1 = static (fast, most tests, including IR-shape); Tier 2 = the compiler's
own run-it tests (Scala-authored); Tier 3 = the language's test framework (sysl-authored).

## Build order

Tests accrete stage by stage down the pipeline:

```
lexer → parser (AST) → analyzer (typed AST) → LLVM codegen → sysl #test runner
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
