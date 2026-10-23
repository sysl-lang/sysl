---
title: Language Reference
summary: The language in full, organized for looking things up rather than for reading through.
weight: 30
---

The [tour](/tour/) teaches sysl in the order the ideas are easiest to learn. This section covers the
same language for lookup: every construct is described once, in its own place, with its rules
complete.

It answers specific questions. What may follow `for`? Which conversions are implicit? What does `?`
do to a `&T` payload? When is a closure boxed?

**Every example is checked.** Every program on these pages is compiled and run by the test suite,
and every refusal shown is quoted from the compiler's own diagnostic, so a page that disagrees with
the compiler fails the build. Where a rule has an edge case, the edge case is shown as a program.

**Rules are given with their reasons where the reason helps you predict behaviour.** sysl's rules
depend on one another — the memory model determines what a closure costs, which determines what a
trait object can be, which determines what a generic can promise — so those connections are stated
where they matter.

## How this section is organized

The pages run from the smallest units upward: what the compiler reads, then types, then
expressions, then the declarations that bind them, and then the systems built on top — memory,
traits, generics, reflection, modules, errors, async and tasks, the foreign interface, and the forms
that run during compilation.

**[Compact reference](/reference/compact/)** is the whole language on a single page: tables, one-line
rules and the common idioms, for a reader who already programs and wants the rules without the
explanation, or for a tool reading them on a budget. Everything on it is stated in full elsewhere in
this section.

**[Packages](/reference/packages/)** describes `sysl.hocon` and dependencies on other people's
code. It is a build-time concern rather than a rule about expressions, but it determines which
modules a program has.

**The [standard library](/library/)** is documented separately. It is ordinary sysl — no type in it
is a language feature, and a program could have written any of it.

**This section is the specification.** It describes what the language is, and gives the reason for
a rule wherever the rule would otherwise be hard to predict.
