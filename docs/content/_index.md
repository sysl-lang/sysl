---
title: sysl
heroTitle: A systems language you can
heroHighlight: actually learn
summary: Ref-counted rather than borrow-checked. Four ways to name storage, no garbage collector, and every check the compiler makes explainable in a sentence.
---

## Why sysl

A systems language is used for the control it gives you: where a value lives, when it dies, what
the machine does. sysl keeps that control and drops the thing that makes the current answer hard to
learn — instead of a borrow checker, it counts references.

**Which memory you are in is written on the type**, and there are four ways to name it:

```sysl
var here: Point         // T   — a value. It lives in this frame.
var raw: *Point         // *T  — a raw pointer. C's pointer, spelled so you can grep for it.
var shared: &Point      // &T  — a reference. Counted, freed when the last one goes.
ref slot = grid[i]      // ref — a second name for storage that already exists.
```

The first three are types, and a value's own says which it is. The fourth is a **binding** rather
than a type: `ref` names a place that some other storage already owns, so a walk down into a nested
structure is paid once and every write through the name still runs the checks the full path would
have. It cannot be stored, returned or captured, which is what lets it exist in a language with no
borrow checker.

There is no garbage collector and no allocation keyword. Writing an ordinary construction where a
`&T` is expected is what puts the object on the heap, and the compiler counts the references for
you.

## Where to go

The [tour](/tour/) is the way in: it starts at `print("Hello, sysl!")` and ends with a program that
reads its input, parses it, and reports what it found. It teaches the standard library alongside the
language, because the two were designed together.

If you write C, [coming from C](/getting-started/from-c/) is shorter: a map of what translates
straight across, the six things that change shape, and the refusals a C program meets first.

The [reference](/reference/) is for looking things up once you are writing sysl rather than reading
about it: every construct in its own place, with the rules complete. The
[standard library](/library/) is documented apart from it, because none of what ships beside the
compiler is a language feature.

Most of the features documented above exist because a program could not be written without them.
That was the job of a set of *guide programs* — real code at the size where the choices start to
matter, each written to force a language decision rather than to demonstrate a finished one. Every
one of them has now been retired into a [package](https://github.com/sysl-lang) or into the standard
library, which is what such a program earns by discharging what it found: a PNG reader, a fast
Fourier transform, a JSON reader, a slab allocator, and the bounded ring in
[`sysl.container.ring`](/library/container/).

The [reference](/reference/) is the other kind of document — every construct written down once, in
its own place, with the rules complete rather than the ones a beginner needs first. It is the
specification, and it is where to go when the question is what the language *is* rather than how to
start using it.

## Getting in touch

**Bugs and questions belong on [GitHub](https://github.com/sysl-lang/sysl/issues)**, where the answer
is visible to whoever asks next.

For anything else — [hello@sysl.sh](mailto:hello@sysl.sh).

**A security problem goes to [security@sysl.sh](mailto:security@sysl.sh) instead of to an issue**,
because an issue is public from the moment it is filed. The
[policy](https://github.com/sysl-lang/.github/blob/main/SECURITY.md) says what to include and what to
expect.
