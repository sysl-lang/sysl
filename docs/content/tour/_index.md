---
title: The Tour
summary: The language and its standard library, in the order they make sense to learn.
weight: 20
---

The tour teaches sysl in one pass, from `print` to a program that reads its input and reports what
it found. It assumes you already program in some language and have used one with static types. It
does not assume you have used a systems language.

**Every program on these pages runs.** The test suite compiles each one and checks it against the
output printed beneath it, so the pages always match the compiler. Paste any of them into a file and
run it with `sysl run <file>.sysl`.

A `sysl` block with an `output` block under it is a complete program, and the `output` block is what
it prints. A `sysl` block with an `error` block under it is a program the compiler rejects, and the
`error` block is the diagnostic it reports; the tour shows these because knowing what is refused is as
useful as knowing what is accepted. An `output trap` block is a program that prints that output and
then stops on a [trap](/reference/errors/). A `sysl` block on its own is a fragment, quoted to show a
shape rather than to be run.

The order is deliberate. The three memory modes come about a third of the way in, before generics and
traits, because they are what most distinguishes sysl from other languages, and the pages after them
are easier to follow once you know which mode a type uses.
