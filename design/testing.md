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

`sysl run`, `sysl build` and `sysl emit-llvm` drop the tests, and drop them **after** analysis —
so a `@test` that does not compile is an error in a build that would never have run it, and a
module's capability clause (`13 §4`) reaches its tests like any other member. That ordering is
what lets a test sit beside what it tests: a program's tests do not run when it runs, and they do
not stop being checked.

A helper only a test calls leaves with it, because it becomes unreachable and pruning notices;
a helper the program also calls stays, because the program still calls it.

**`sysl build-lib` is the exception and drops them *before* analysis.** An artifact is the one
output that outlives the compilation that made it, and analysis is not a passive reading: a test
naming `Buf[int]` **creates** the whole of `Buf` at `int`, and a monomorphization is an ordinary
library function afterwards — nothing in one records which declaration demanded it. Dropping the
test from the typed tree therefore removes the test and keeps everything it caused, and the
artifact ships instantiations no caller of the library ever asked for. Its contents become a fact
about its tests.

**The line falls between parsing and analysis, and is worth stating exactly** — "`build-lib` no
longer checks a library's tests" is wrong in both directions. Every source is parsed before the
strip is reached, so a **syntax** error in a `@tests` file still stops the build. What such a file
no longer gets is everything after the parse: name resolution, types, visibility, capabilities, the
`@test` well-formedness rules above, generic instantiation, escape analysis, the tail-call check.

So what is given up is narrower than it sounds and sharper: a library test that is well-formed text
and wrong in every other way builds clean. The net moved rather than went — `sysl test --std`
compiles the library's tests properly and runs them, and the compiler's own suite runs *that*, which
is a better place for the check than a command whose subject is the artifact.

### `@tests` — a file of scaffolding

Pruning answers for a **program** and does not answer for a **library**. A library has no `main`
to lower outwards from, so every public declaration is a potential entry and all of them are
emitted; a helper only a test called would ride into the artifact and be advertised out of it,
nameable by everything that links it. Nothing about the declaration says it is scaffolding, and
nothing could — it is an ordinary function, which is the point of it.

So the **file** says it, in its header, beside the capability clauses:

```
module sysl.text
@tests

fixture() -> Layout = ...

@test("a column is measured with its header")
header_counts() =
    assert_eq(fixture().width(), 12)
```

It is `@tests` and not `@test` because the two say different things: `@test` names something the
runner calls, and this names something no build but the runner's keeps. One word for both would
read as though the file were itself a test.

**Every build but `sysl test` drops everything such a file declares**, and — the other half of
the same rule — **nothing outside a test may name any of it**. Either alone is unsound. Dropping
without restricting leaves a program that called a helper compiling here and failing at the
link, with a message about a missing symbol rather than about the line that named it.

The restriction is stated over the **referring declaration** and not over the file it sits in,
and the case that forces this is the one above: a test may sit beside what it tests. So a
`@test` function may name scaffolding wherever it was written, and a declaration in another
`@tests` file may, and nothing else may. That the two agree is what makes the drop safe rather
than lucky — every reference into such a file comes from something dropped in exactly the builds
the file is, so a stripped tree can hold no reference to what went with it.

It stops at the **package boundary**, and that is not a taste: a package is compiled from source
and a library arrives as an artifact whose test files were never encoded, so a rule that let the
reference cross would compile against one and fail against the other. A test-support library
meant to be imported — sysl's `harness`, old sysl's `std.testing` — is therefore ordinary code
that ships, and is a different thing from a file of scaffolding inside a package.

An **`impl` block may not sit in one**. It declares no name; it puts an entry in a method table,
which the rest of the program reads without naming anything. Kept in a test build and dropped
everywhere else, it would mean a trait answering one way while the tests ran and another way in
the program that shipped. The impl belongs beside the type. What that buys, beyond the rule
itself, is that no declaration a test file *writes* is a trait method — so a dispatch through a
table can never reach one, and the reference check has three node kinds to look at rather than
five.

A **closure** written in such a file is judged by the body it was written in rather than by the
name it ends up under, and that needs saying because the two would otherwise disagree. Lowering
one produces a function of its own, under a name the compiler made up, in no table that
remembers which file it came from — so a lambda inside a test naming that test's own helper
would be held to the rule about everything else while sitting inside one of the two things the
rule exempts. It is the test's on both counts: it may name what the file declared, and it goes
when the file does.

The going matters as much as the naming. A closure is a struct and an `impl` of `Fn`, so
lowering one inside a test writes a method table — and a table is something a program can read a
function out of, which makes it a *root* for pruning rather than something pruning decides
about. Left behind, it would hold the lowered body alive to call helpers the build had just
removed. So a table whose slot names something the drop took goes with it, and a closure's own
is the only slot such a name can fill.

What must **not** be reported is a reference *to* one of those lowered names, and the standard
library is where that shows: `is_sorted_by(xs, (a, b) -> a < b)` instantiates a library generic
at the closure's own type, so the comparison inside that instantiation is a direct call on the
test's closure — an ordinary library function naming it. A diagnostic there would name something
the program does not contain, at a line in a file the reader did not write. Nothing is given up
by staying quiet: an instantiation keyed on a test's closure type can only have been asked for
by that test, so it is unreachable once the test goes and pruning removes it.

The **types** such a file declares are left in the tree when its functions go, exactly as
pruning leaves them: a type is emitted for its layout rather than for anything that runs. A
closure's struct is one of those, so a stripped program still defines it and holds no body for
it.

### The runner

```
sysl test <path>
sysl test <path> --filter <text>
sysl test <path> --fail-fast
sysl test <path> --std
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

### `--std` — testing the standard library itself

The compiler supplies `sysl` to every compilation, so pointing the runner at `library/` means the
library arriving twice: once as the tree in front of the compiler and once as the copy it hands
over. Every declaration is already declared, and the whole library is refused.

`--std` says the tree **is** the standard module. It is the same word `build-lib` has used for the
same reason since libraries existed, and it carries the same set of module names through to the
analyzer, which is what suppresses the collision.

**Nothing infers it.** A build says so, because a build that guessed would turn a crisp refusal —
a program with a `sysl` directory of its own, which is nearly always a mistake — into a link-time
collision.

```
sysl test library --std
sysl test library --std --filter sysl.time
```

This is what makes the first half of Tier 3's claim true. Testing "the standard library and
language behavior" had until then reached only the second half, because there was no way to point
the runner at the library at all.

**What belongs in a library `@test` and what does not.** A `@test` runs code the compiler under
test produced and asserts with `assert_eq`, which that same compiler produced — so a miscompiled
`==` makes the assertion pass. A sysl test is therefore the right place for what is true of the
**library** (that `fields` collapses a run of blanks, that 1900 was not a leap year) and the wrong
place for what is true of the **language**. A claim like "an `f32` computes at `f32`'s width"
stays in Tier 1 or 2, where the expectation is written in another language and compared by another
runtime: a compiler that had quietly widened everything to binary64 would compile the sysl
assertion into the same widening and pass it.

### `assert` and `panic`

Both are in the standard module (`library/sysl/check.sysl`), and the tests needed them: `require`
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

### `assert_eq`, and why it is a function rather than advice

What it adds over `assert(a == b)` is the two **values**. A failed `assert` names the line, which
says which check broke; its reader then runs the thing again to find out what the values were, and
running it again is exactly what the report could have saved them.

```
assert_eq[T: Eq + Display](got: T, want: T, msg = "", …)
assert_slice_eq[T: Eq + Display](got: []const T, want: []const T, msg = "", …)
```

The message could be written by hand — `assert(a == b, s"got $a, want $b")` — and that spelling is
what these replace. It evaluates each side twice, and it builds a string, which makes heap storage
and so is unavailable to the modules that want an assertion most. Rendering through `Display` into
`stdout()` costs neither, which is why this is a function in the library rather than a sentence in a
guide telling people to interpolate.

**One function and not one per type.** `Eq` says the comparison means something and `Display` says
the value can be shown, which together are the whole of what a report needs. Old sysl's `std.testing`
had twelve, and said why in its own prose: it had no ad-hoc polymorphism on parameter types.

The slice form earns its place differently. `assert_eq` on two slices would say they differ and send
its reader to find out where; this reports the length when the lengths are what differ, and otherwise
the first index they disagree at with both elements at it.

**The float pair lives in `sysl.math`**, as `assert_approx_eq` and `assert_approx_eq_rel`, because
`==` is the wrong question to ask about a float and `approx_eq` is where the right one already is.
That placement is forced rather than chosen: `Float` is declared in `sysl.math` and reaches `Eq`,
`Ord` and the arithmetic traits in the root, so `sysl.math` depends on `sysl` — and a float assertion
in `check.sysl` would put an edge back the other way, which `13 §6` refuses.

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
