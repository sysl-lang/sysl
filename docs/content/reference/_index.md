---
title: Reference
summary: The language in full, organized for looking things up rather than for reading through.
weight: 30
---

The [tour](/tour/) teaches sysl in the order the ideas make sense to learn. This section is the other
shape: every construct written down once, in its own place, with the rules complete rather than the
ones a beginner needs first.

It is meant to answer the question you actually have. What may follow `for`? Which conversions are
implicit? What exactly does `?` do to a `&T` payload? When is a closure boxed? Those are lookups, and
a tutorial answers them only by accident.

**The same guarantee holds here.** Every program on these pages is compiled and run by the test
suite, and every refusal is a refusal the compiler really makes, quoted from its own diagnostic. A
page that drifts from the compiler fails the build. Where a rule has an edge, the edge is shown as a
program rather than described — a sentence about what would happen is a guess, and a fenced block
that runs is not.

**Where a rule exists for a reason, the reason is here too.** A reference that only lists behaviour
tells you what to type and leaves you unable to predict anything you have not looked up yet. sysl's
rules are unusually load-bearing on one another — the memory model decides what a closure costs,
which decides what a trait object can be, which decides what a generic can promise — so the
connections are written down where they matter. Thorough is the point; brevity is not.

## How this section is organized

The order runs from the smallest units upward: what the compiler reads, then what a type is, then
what an expression is, then the declarations that bind them, then the systems built on top —
memory, generics, modules, errors, and the foreign interface.

Cross-references point at the [design chapters](https://github.com/edadma/sysl/tree/dev/docs/design)
where a rule was argued out. Those are the specification and carry the alternatives that were
rejected; this section is what the language *is*, without the deliberation.
