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

The [guide programs](/guides/) are the evidence: fourteen real programs — a JSON parser, a scheduler,
a slab allocator, SHA-2, a Lisp — each written to force a language decision, with what it found
written down.
Most of the features documented above exist because one of them could not be written without.

The [specification](https://github.com/edadma/sysl/tree/dev/design) is the other kind of
document — numbered chapters that say what the language *is* and why, written for someone deciding
the design rather than someone learning it.
