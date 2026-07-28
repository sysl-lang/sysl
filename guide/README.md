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

| directory | axis it owns |
|---|---|
| `json` | recursive ownership — a value that contains itself through `&T` |
| `hashmap` | the trait system under load — bounds, the behaviour they promise, and ownership at once |
| `bytecode` | the module system, and the set's one end-to-end assertion — source in, bytecode out, run it |
| `png` | the byte level — endianness, bit streams, checksums, a format someone else defined |
| `fft` | arithmetic on a type the program defined, and floating point |
| `sha2` | generic arithmetic — one algorithm at two widths — and static tables |
| `shapes` | dynamic dispatch — a heterogeneous collection whose element types are forgotten |
| `scheduler` | OS shapes — a run queue, blocking and waking, and `&T` graphs mutated through references |

The rest of the set, and the coverage map that justifies each entry, is recorded outside the repo
with the rest of the project's decisions. A candidate that does not own an axis already unclaimed is
a variation, and belongs in the test suite instead.

## What each program found

Findings go in the program's own header comment, where the code that provoked them is. They are the
output of the exercise; the program passing is only the evidence that the finding is real.

**A finding must be discharged before the next program is written.** Every language or compiler
issue one of these raises is either fixed, or investigated and decided — and "deferred, for this
reason, waiting on that" is a decision. Writing it down is not. The rule exists because findings
compound: two of these programs independently paid the same cost for the same missing feature, and
the second one taught us nothing the first had not, because nothing had been settled in between. A
program written on top of an undecided finding spends its budget rediscovering it.
