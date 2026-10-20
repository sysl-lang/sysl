---
title: sysl
heroTitle: A systems language you can
heroHighlight: actually learn
summary: Ref-counted rather than borrow-checked. Four ways to name storage, no garbage collector, and every check the compiler makes explainable in a sentence.
---

## Why sysl

A systems language gives you control over where a value lives, when it is freed, and what the
machine does with it. sysl keeps that control and manages shared memory by reference counting
rather than with a borrow checker, so ownership rules stay short enough to learn quickly.

**Where a value lives is part of its type**, and there are four ways to name storage:

```sysl
var here: Point         // T   — a value. It lives in this frame.
var raw: *Point         // *T  — a raw pointer, as in C, with its own visible syntax.
var shared: &Point      // &T  — a reference. Counted, and freed when the last one goes.
ref slot = grid[i]      // ref — a second name for storage that already exists.
```

The first three are types. The fourth is a **binding**: `ref` names a place that other storage
already owns. Reaching into a nested structure through a `ref` costs one walk, and every write
through the name still runs the same checks the full path would. A `ref` cannot be stored, returned
or captured, which is what makes it safe without a borrow checker.

There is no garbage collector and no allocation keyword. Constructing a value where a `&T` is
expected places it on the heap, and the compiler maintains the reference count.

## Where to go

The [tour](/tour/) is the place to start. It begins with `print("Hello, sysl!")` and ends with a
program that reads its input, parses it, and reports what it found. It covers the standard library
alongside the language, because the two are designed together.

If you write C, [coming from C](/getting-started/from-c/) is shorter: what translates directly,
the six things that change shape, and the diagnostics a C programmer meets first.

The [reference](/reference/) is the specification. Every construct is described once, in its own
place, with the complete rules rather than the ones a beginner needs first. Use it when the question
is what the language *is* rather than how to start using it.

The [standard library](/library/) is documented separately, because nothing that ships beside the
compiler is a language feature. It covers text, collections such as the bounded ring buffer in
[`sysl.container.ring`](/library/ring/), files, networking, threads and more. Further
packages, including C library bindings, are published in the
[sysl-lang organization](https://github.com/sysl-lang).

## Getting in touch

**Bugs and questions belong on [GitHub](https://github.com/sysl-lang/sysl/issues)**, where the answer
is visible to the next person who asks.

For anything else, write to [hello@sysl.sh](mailto:hello@sysl.sh).

**Report a security problem to [security@sysl.sh](mailto:security@sysl.sh), not in an issue**,
because an issue is public from the moment it is filed. The
[security policy](https://github.com/sysl-lang/.github/blob/main/SECURITY.md) says what to include
and what to expect.
