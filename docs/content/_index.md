---
title: sysl
heroTitle: A systems language you can
heroHighlight: actually learn
summary: Ref-counted rather than borrow-checked. Three memory modes, no garbage collector, and an operating system meant to be readable end to end.
---

## Why sysl

A systems language is used for the control it gives you: where a value lives, when it dies, what
the machine does. sysl keeps that control and drops the thing that makes the current answer hard to
learn — instead of a borrow checker, it counts references.

Memory is one of three modes, and which one you are in is written on the type:

```sysl
var here: Point         // T   — a value. It lives in this frame.
var shared: &Point      // &T  — a reference. Counted, freed when the last one goes.
var raw: *Point         // *T  — a raw pointer. C's pointer, spelled so you can grep for it.
```

There is no garbage collector and no allocation keyword. Writing an ordinary construction where a
`&T` is expected is what puts the object on the heap, and the compiler counts the references for
you.

## Where to go

The [tour](/tour/) is the way in: it starts at `print("Hello, sysl!")` and ends with a program that
reads its input, parses it, and reports what it found. It teaches the standard library alongside the
language, because the two were designed together.

The [specification](https://github.com/edadma/sysl/tree/dev/docs/design) is the other kind of
document — numbered chapters that say what the language *is* and why, written for someone deciding
the design rather than someone learning it.
