# Guide problems

Programs written to **force a language decision**, not to demonstrate a finished language.

The value is in the friction. The point at which a program cannot be written cleanly is the point
at which the language is wrong, and that is the signal — a program that goes in smoothly told us
nothing. Each one is therefore chosen for an *axis* nothing else in the set covers, and each ends
with output a test can compare exactly, because every one of these has to keep passing across the
next compiler change.

Run one directly:

```
sbt "syslJVM/run run guide/json"
```

Each directory is a **project root**, so the files in it are the anonymous root module and any
sub-directory is a module named by its path (`13 §1`).

**Each program checks itself.** Every line it prints is either a `--` section header or `ok`
followed by what was checked, so a failure is a line that says otherwise. `GuideTests` runs each
one and asserts that nothing failed, that the number of checks is the one expected, and that the
sections ran in order — the count being what makes the first assertion mean anything, since a check
that quietly stopped running would otherwise look like a check that passed.

**What a program's own run cannot check is what it refuses.** A violated `require`, a broken
`invariant` and a failed range check all *trap*, so a program that tried to demonstrate one would
die rather than report it, and the run would look truncated instead of failing. The run therefore
asserts a refusal only through a total operation that answers instead of trapping.

**The traps are asserted in `@test(should_trap)` functions** (`testing.md`), which live in the
program's own directory and are run by `sysl test <directory>`. Each runs in a process of its own
and passes by not coming back, so a trap is an observation there rather than the end of the run —
which is what lets a refusal be stated in sysl, beside the code it is about. `guide/ring` was the
first to need this and `guide/ring/tests.sysl` is where that half of its evidence lives.

**Such a file opens with `@tests`**, which says the whole of it is scaffolding: every build but
`sysl test` drops what it declares, and nothing outside a test may name any of it. A program did not
strictly need the header — a helper only a test calls is pruned anyway — but the file is reading
material, and a fixture like `guide/ring`'s `filled_ring` is an ordinary function that says nothing
about existing only for the tests under it. The header is where it says so.

**A check that compares two values is `assert_eq`, not `assert(a == b)`.** The difference is what a
failure prints: `assert` names the line, and `assert_eq` names the line and both values, so nobody
has to run the program a second time to find out what it actually returned. The `check(name, got,
want)` helper several programs define is a different thing and stays — it *prints* every line rather
than stopping at the first, because in those programs the whole of stdout is the assertion.

**Write every refusal beside the call that is not refused.** A `should_trap` test passes for any
failure at all, its own setup included, so alone it cannot tell "the contract fired" from "nothing
worked". One call over the line and one call up to it, and the difference between them is the
contract.

| directory | axis it owns |
|---|---|
| `json` | recursive ownership — a value that contains itself through `&T` |
| `hashmap` | the trait system under load — bounds, the behaviour they promise, and ownership at once |
| `bytecode` | the module system, and the set's one end-to-end assertion — source in, bytecode out, run it |
| `png` | the byte level — endianness, bit streams, checksums, a format someone else defined |
| `fft` | an algorithm checked against its own definition |
| `sha2` | generic arithmetic — one algorithm at two widths — and static tables |
| `shapes` | dynamic dispatch — a heterogeneous collection whose element types are forgotten |
| `scheduler` | OS shapes — a run queue, blocking and waking, and `&T` graphs mutated through references |
| `kernel` | the same scheduler with no heap — a fixed table, indices for identity, intrusive lists |
| `datetime` | a conversion that can succeed twice — wall clocks, timelines, and daylight saving |
| `matrix` | an operator whose result is neither operand's type — a vector space, then Gaussian elimination |
| `ring` | the constrained-subtype surface — range types, their `::` attributes, contracts, struct invariants, and a loop's termination measure |
| `slab` | raw storage — reinterpreting bytes as a typed pointer, `sizeof`/`alignof`, and a free list threaded through the free blocks themselves |
| `lisp` | the reference cycle — the one shape a count cannot reclaim, `weak T` as both the cure and the instrument that measures it |
| `table` | text measured for **display** — a column is as wide on screen as its widest cell, and both a byte count and a character count are the wrong unit |

The rest of the set, and the coverage map that justifies each entry, is recorded outside the repo
with the rest of the project's decisions. A candidate that does not own an axis already unclaimed is
a variation, and belongs in the test suite instead.

**`slab` and `lisp` are written as literate files** (`15 §11`). Both crossed the same line: their
findings ran past sixty lines of header comment before anything executable appeared, which is the
length at which a comment stops being one — so each is a document with the program indented inside
it, and the essay that was fighting the `//` is prose. Nothing about either program changed; a
directory holds a `.lsysl` beside `.sysl` files and `Project.collect` reads both.

**The threshold is worth stating, because it is the whole of when to reach for the format.** It is
not comment *volume* — it is an argument that has to sit between two declarations rather than above
one. `lisp` has four paragraphs inside the body of `eval`, each explaining the arm under it; as
`//` they were a wall above the function that nobody would read at the point it mattered. Consecutive
indented blocks are one block, so prose costs the program nothing to split.

## What each program found

Findings go in the program's own header comment, where the code that provoked them is — or in its
prose, where the program is literate. They are the output of the exercise; the program passing is
only the evidence that the finding is real.

**A finding must be discharged before the next program is written.** Every language or compiler
issue one of these raises is either fixed, or investigated and decided — and "deferred, for this
reason, waiting on that" is a decision. Writing it down is not. The rule exists because findings
compound: two of these programs independently paid the same cost for the same missing feature, and
the second one taught us nothing the first had not, because nothing had been settled in between. A
program written on top of an undecided finding spends its budget rediscovering it.
