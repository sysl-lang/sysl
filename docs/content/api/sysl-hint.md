---
title: sysl.hint
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.hint
summary: "Hints to the optimizer that change what it may assume, never what a program computes."
requires: "no alloc"
---

## Index

[`black_box`](#black_box) [`prefetch`](#prefetch) [`Access`](#access) [`Locality`](#locality)

## Functions

### `black_box`

```sysl
black_box[T](x: T) -> T
```

Answers `x` unchanged, through a step the optimizer must treat as opaque.

The compiler may not assume anything about the value that comes back -- not that it is the
constant that went in, not that nobody looked at it -- so what is computed from it is computed
by the machine at run time, and what is computed into it is not discarded as unused. A test
holds a constant through it to make the machine do the arithmetic rather than the compiler, and
a benchmark holds its input and its result through it so the work being timed is neither folded
away nor dropped.

It works for a value of any type: a scalar, a struct, a reference, a slice, an `Option`. The
value is placed in memory whose address is handed to an empty block of assembly that the
compiler must assume reads and writes all of memory. That costs a store and a load, and no
instruction of its own, on every processor sysl builds for, with or without a C library.

It is a hint about optimization, not a guarantee of timing: it does not make code constant-time,
and it orders nothing between threads.

### `prefetch`

```sysl
prefetch[T](p: *T, access: Access = Read, locality: Locality = High)
```

Asks the machine to start bringing the memory at `p` into its cache, so that a read or write of
it a little later does not wait for memory.

It is a hint: it reads nothing, changes nothing and never faults, whatever `p` holds -- an address
past the end of a buffer, or one nothing is mapped at, is prefetched as uselessly and as harmlessly
as any other. On a processor with no prefetch instruction it is nothing at all.

`access` and `locality` say which instruction to use, so they are constants: an enum's variant
written at the call, a module `val` holding one, or a generic's `const` parameter. The compiler
answers each call itself, so a
prefetch costs the one instruction and no call.

## Types

### `Access`

```sysl
enum Access
    Read
    Write
```

What a prefetch readies a line of the cache for: reading it, or writing it.

### `Locality`

```sysl
enum Locality
    None
    Low
    Medium
    High
```

How long the prefetched line is wanted, from `None` -- read once and not again, so it should not
push anything out of the cache -- to `High`, kept in every level the machine has.
