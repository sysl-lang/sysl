---
title: sysl.container.ring
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.container.ring
summary: "A queue of a **fixed** capacity, laid over storage the caller supplies, cheap to take from at either end and needing no allocator at all."
requires: "no alloc"
---

**The reason this exists beside `Deque` is the storage, not the shape.** A `Deque[T]` is the same
ring with a growth path under it, so it owns its elements and needs a heap to widen them. This one
cannot grow: its capacity is the length of the slice it was handed, a full ring answers rather than
doubles, and `@no_alloc` on this file is the whole of the claim. That is what makes it reachable
from a program that has given the heap up -- a board, an interrupt handler, a signal handler --
which is where a bounded queue is wanted most and where `Deque` cannot go.

```
var slots: [8]int = [0; 8]
var r = ring(slots[..])

r.push_back(1)
r.push_back(2)
r.pop_front()                      // Some(1)
```

**The storage is a `*T` and a count rather than a `[]T`, and that is the crossing rule rather than
a preference.** A view owns its elements through a count that is not atomic, so a struct holding
one may not reach another concurrency domain at all -- which would refuse every ring embedded in
something a thread is handed, one line before the first `push_back`. A raw pointer carries no
count, so it crosses; what it costs is the same trust `*p` already asks for, and `sysl.posix.
threads.Channel` is a ring of exactly this shape for exactly this reason. The caller writes a
slice at `ring(...)` and never sees the pointer.

**The storage has to outlive the ring**, which is the one thing this type asks of a caller that
`Deque` does not: a module-level `static var`, a local of a scope that outlives every holder, or a
region something else owns. It is the same contract `channel(slots[..])` already has.

**The length is not rounded to a power of two**, unlike `Deque`'s, because the capacity is the
caller's number rather than one this file chose -- a program that asked for six slots gets six.
So wrapping is a compare and a subtract instead of a mask, which is what `seat` below is.

## Index

[`ring`](#ring) [`Ring`](#ring-1) [`RingCursor`](#ringcursor) [Index for Ring[T]](#index-for-ringt) [IndexSet for Ring[T]](#indexset-for-ringt) [Iterate for RingCursor[T]](#iterate-for-ringcursort)

## Functions

### `ring`

```sysl
ring[T](slots: []T) -> Ring[T]
```

Builds an empty ring over storage the caller supplies.

A function rather than an associated `new`, because the element type is inferred from the slots and
there is no receiver for it to be read off: `ring(slots[..])` says everything, where
`Ring[int].new(...)` would say the element type twice.

**The slice is read here and not kept**, for the reason the `slots` field gives: what is stored is
its address and its length.

## Types

### `Ring`

```sysl
struct Ring[T]
    private slots: *T
    private room: usize
    private head: usize
    private live: usize
```

The ring itself: where the caller's storage is, how much of it there is, and the live run inside
it.

| Member | Signature | Description |
|---|---|---|
| `capacity` | `capacity(self) -> usize` | How many values the ring can hold at once, which is the storage it was given. |
| `len` | `len(self) -> usize` | How many values are in it. |
| `is_empty` | `is_empty(self) -> bool` |  |
| `is_full` | `is_full(self) -> bool` | Whether the next `push_back` will be refused, which is the question a bounded queue is asked that a growing one never is. |
| `at` | `at(self, i: usize) -> T` | The `i`th value from the front. |
| `get` | `get(self, i: usize) -> Option[T]` | The `i`th value from the front, or nothing where there is no such value. |
| `set` | `set(*self, i: usize, v: T)` |  |
| `first` | `first(self) -> Option[T]` |  |
| `last` | `last(self) -> Option[T]` |  |
| `push_back` | `push_back(*self, v: T) -> bool` | Adds to the back, and answers whether there was room. |
| `push_front` | `push_front(*self, v: T) -> bool` | Adds to the front, stepping `head` backwards into the space behind it. |
| `pop_front` | `pop_front(*self) -> Option[T]` | Takes from the front. |
| `pop_back` | `pop_back(*self) -> Option[T]` | Takes from the back. |
| `overwrite` | `overwrite(*self, v: T) -> Option[T]` | Adds to the back, **dropping the oldest value to make room** where the ring is full, and answers whatever was dropped. |
| `clear` | `clear(*self)` | Forgets every value. |
| `walk` | `walk(self) -> RingCursor[T]` | A walk from the front to the back. |

### `RingCursor`

```sysl
struct RingCursor[T]
    slots: *T
    room: usize
    head: usize
    count: usize
    at: usize
```

A walk from the front to the back. It holds the storage rather than the ring, so pushing onto the
ring while a cursor is live leaves the cursor reading the run it started on.

## Implementations

### Index for Ring[T]

```sysl
impl[T] Index[usize, T] for Ring[T]
```

Subscripting reaches the bounds-checked members rather than the storage, so `r[i]` counts from the
**front** of the ring and cannot read a slot the ring is not currently using.

### IndexSet for Ring[T]

```sysl
impl[T] IndexSet[usize, T] for Ring[T]
```

### Iterate for RingCursor[T]

```sysl
impl[T] Iterate for RingCursor[T]
```
