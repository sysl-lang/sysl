---
title: The Tour
summary: The language and its standard library, in the order they make sense to learn.
weight: 20
---

The tour teaches sysl in one pass, from `print` to a program that reads its input and reports what
it found. It assumes you can already program in something, and that you have used a language with
types before — it does not assume you have used a systems language.

**Every program on these pages runs.** They are not sketches: the test suite compiles each one and
checks it against the output printed beneath it, so a page that has drifted from the compiler fails
the build rather than misleading you. Paste any of them into a file and run it with
`sbt "syslJVM/run run <file>.sysl"`.

A `sysl` block with an `output` block under it is a whole program and that is what it prints. A
`sysl` block with an `error` block under it is a program the compiler *refuses*, and that is what it
says — the refusals are worth as much as the acceptances, so the tour shows them. A `sysl` block
standing on its own is a fragment, quoted to make a point about shape rather than to be run.

The order is deliberate. The three memory modes come early — around a third of the way in, well
before generics or traits — because they are what makes sysl a different language rather than a
different syntax, and because everything after them reads better once you know which of the three
a type is in.
