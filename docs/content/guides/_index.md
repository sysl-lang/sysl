---
title: Guide Programs
summary: Thirteen real programs written to force a language decision — what each one owns, and what writing it found.
weight: 50
---

The [tour](/tour/) teaches the language, the [reference](/reference/) says what the rules are, and
the [library](/library/) says what ships beside them. This section is about something else: the
thirteen programs in [`guide/`](https://github.com/edadma/sysl/tree/dev/guide) that were written to
**force a language decision**.

They are not demonstrations of a finished language. That distinction is the whole of why they exist,
and it is worth stating in the form the set's own README does:

> The value is in the friction. The point at which a program cannot be written cleanly is the point
> at which the language is wrong, and that is the signal — a program that goes in smoothly told us
> nothing.

So a guide program is chosen for an **axis** nothing else in the set covers, written until it either
goes in cleanly or does not, and the place it snagged is the output. Most of the language features
documented elsewhere on this site exist because one of these programs could not be written without
them: `Buf[T]`, `Hash` in the core catalogue, `from_utf8`, the repeat form `[v; n]`, `unit` as a
zero-sized type, trailing-operator continuation, `ref` bindings, `sizeof` and `alignof`, and the rule
that a constrained subtype's attributes speak the subtype. Each of those has a program that paid for
it first.

## Why they are documented here rather than left in the repo

Because the findings are the interesting part and they are buried in header comments. A program's
own file records what it found in the place the code that provoked it lives, which is right for
somebody reading the program and useless for somebody wondering **why the language is shaped this
way**. These pages pull each finding out and link it to the rule it produced.

## The thirteen, and the axis each owns

In the order they were written, which matters — a finding must be discharged before the next program
starts, so each one is written on top of what the last one settled.

| program | the axis it owns |
|---|---|
| [json](/guides/json/) | recursive ownership — a value that contains itself through `&T` |
| [hashmap](/guides/hashmap/) | the trait system under load — bounds, what they promise, and ownership at once |
| [bytecode](/guides/bytecode/) | the module system, and the set's one end-to-end assertion |
| [png](/guides/png/) | the byte level — endianness, bit streams, checksums, somebody else's format |
| [fft](/guides/fft/) | arithmetic on a type the program defined, and floating point |
| [sha2](/guides/sha2/) | generic arithmetic — one algorithm at two widths — and static tables |
| [shapes](/guides/shapes/) | dynamic dispatch — a collection whose element types are forgotten |
| [scheduler](/guides/scheduler/) | OS shapes — a run queue, blocking and waking, `&T` graphs mutated through references |
| [kernel](/guides/kernel/) | the same scheduler with **no heap** — a fixed table, indices for identity |
| [datetime](/guides/datetime/) | a conversion that can succeed twice — wall clocks, timelines, daylight saving |
| [matrix](/guides/matrix/) | an operator whose result is neither operand's type |
| [ring](/guides/ring/) | the constrained-subtype surface — ranges, `::` attributes, contracts, invariants |
| [slab](/guides/slab/) | raw storage — reinterpreting bytes, `sizeof`/`alignof`, an intrusive free list |

**Two pairs are written to be compared**, and the comparison is the measurement:
[scheduler](/guides/scheduler/) against [kernel](/guides/kernel/) is what reference counting was
buying, since the two produce byte-identical schedules from opposite implementations; and inside
[ring](/guides/ring/), one buffer keeps the fact of where the ring ends once and the other keeps it
twice.

## Running one

Each directory is a **project root**, so the files in it are the anonymous root module and any
subdirectory is a module named by its path:

```
sbt "syslJVM/run run guide/json"
```

**Each program checks itself.** Every line it prints is either a `--` section header or `ok`
followed by what was checked, so a failure is a line that says otherwise. `GuideTests` runs each one
and asserts that nothing failed, that the number of checks is the expected one, and that the sections
ran in order — the count being what makes the first assertion mean anything, since a check that
quietly stopped running would otherwise look like a check that passed.

## What a self-checking run cannot check

A violated `require`, a broken `invariant` and a failed range check all
[trap](/reference/errors/), so a program demonstrating one would die rather than report it and the
run would look truncated rather than failed. A run therefore asserts a refusal only through a
**total** operation that answers instead of trapping.

The traps are asserted separately, in [`#test(should_trap)`](/reference/attributes/) functions that
live in the program's own directory. Each runs in a process of its own and passes by not coming
back, so a trap is an observation there rather than the end of the run — which is what lets a refusal
be stated in sysl, beside the code it is about. `guide/ring` was the first to need this.

The discipline that goes with it: **write every refusal beside the call that is not refused.** A
`should_trap` test passes for any failure at all, its own setup included, so alone it cannot tell
"the contract fired" from "nothing worked". One call over the line and one call up to it, and the
difference between them is the contract.

---

Next: [json](/guides/json/) — the first of the set, and the one that found the language had no way
to build a string.
