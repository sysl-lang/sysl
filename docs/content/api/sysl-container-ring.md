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

**A value is moved in and out, never copied.** A push puts the value into an empty slot with
`slot_put` and a pop takes it back out with `slot_take`, so the share a counted value carries goes
in with it and comes out with it, and the slot it leaves is empty again. A value popped is the
caller's alone: its destructor runs when the caller lets it go, not when the slot is next reused,
and the next push into that slot gives nothing back -- which matters most in a channel, where that
push is on another thread. `ring(...)` empties every slot first, giving back whatever the storage
was filled with, so a slot outside the live run is always an empty one.

## Index

[`inline_ring`](#inline_ring) [`ring`](#ring) [`InlineRing`](#inlinering) [`InlineRingCursor`](#inlineringcursor) [`Ring`](#ring-1) [`RingCursor`](#ringcursor) [Index for InlineRing[T, N]](#index-for-inlineringt-n) [Index for Ring[T]](#index-for-ringt) [IndexSet for InlineRing[T, N]](#indexset-for-inlineringt-n) [IndexSet for Ring[T]](#indexset-for-ringt) [Iterate for InlineRingCursor[T, N]](#iterate-for-inlineringcursort-n) [Iterate for RingCursor[T]](#iterate-for-ringcursort) [Walk for InlineRing[T, N]](#walk-for-inlineringt-n) [Walk for Ring[T]](#walk-for-ringt)

## Functions

### `inline_ring`

```sysl
inline_ring[T, const N: usize]() -> InlineRing[T, N]
```

An empty ring of capacity `N`: `inline_ring[int, 8]()`.

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

**Every slot is emptied here**, giving back whatever the storage was filled with, so that every push
after it moves a value into a slot that holds nothing. Over storage of plain values that is no work
at all.

## Types

### `InlineRing`

```sysl
struct InlineRing[T, const N: usize]
    private slots: [N]T
    private head: usize
    private live: usize
```

A ring of a **fixed capacity that carries its own storage**: the `N` slots are an `[N]T` inside
the value, so a struct can simply hold one, and a copy of the struct holds a copy of the ring.

```
struct Uart
    rx: InlineRing[u8, 16]

var u: Uart
u.rx.push_back(1)
```

**It is `Ring` with the storage moved inside**, and its members have `Ring`'s names and answers:
`push_back` and `push_front` answer whether there was room, `pop_front` and `pop_back` answer an
`Option`, `overwrite` drops the oldest value of a full ring, `r[i]` counts from the front and traps
past the end as an array's index does, and `for x in r` walks front to back. Code can move between the two by changing the
type and the line that makes it.

**A fresh one is `inline_ring[T, N]()`, or a plain `var r: InlineRing[T, N]`**: a zeroed slot is an
empty slot, so no slot needs a value to be made, and a type with no default value can still be held.
`N` need not be a power of two; wrapping is a compare and a subtract, as in `Ring`.

A value is moved in and out of its slot with `slot_put` and `slot_take`, exactly as `Ring` does it.

| Member | Signature | Description |
|---|---|---|
| `capacity` | `capacity(self) -> usize` | How many values it can hold at once, which is `N`. |
| `len` | `len(self) -> usize` | How many values are in it. |
| `is_empty` | `is_empty(self) -> bool` |  |
| `is_full` | `is_full(self) -> bool` | Whether the next `push_back` will be refused. |
| `get` | `get[I: Integer](self, i: I) -> Option[T]` | The `i`th value from the front, or nothing where there is no such value -- `i` at or past the length, or negative. |
| `first` | `first(self) -> Option[T]` |  |
| `last` | `last(self) -> Option[T]` |  |
| `push_back` | `push_back(*self, v: T) -> bool` | Adds to the back and answers whether there was room; a full ring is left unchanged. |
| `push_front` | `push_front(*self, v: T) -> bool` | Adds to the front and answers whether there was room. |
| `pop_front` | `pop_front(*self) -> Option[T]` | Takes from the front, moving the value out and leaving its slot empty. |
| `pop_back` | `pop_back(*self) -> Option[T]` | Takes from the back, moving the value out and leaving its slot empty. |
| `overwrite` | `overwrite(*self, v: T) -> Option[T]` | Adds to the back, **dropping the oldest value to make room** where the ring is full, and answers whatever was dropped. |
| `clear` | `clear(*self)` | Lets go of every value, there and then, leaving every slot empty. |

### `InlineRingCursor`

```sysl
struct InlineRingCursor[T, const N: usize]
    ring: InlineRing[T, N]
    at: usize
```

A walk from the front to the back over a copy of the ring it started from.

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
| `get` | `get[I: Integer](self, i: I) -> Option[T]` | The `i`th value from the front, or nothing where there is no such value -- `i` at or past the length, or negative. |
| `first` | `first(self) -> Option[T]` |  |
| `last` | `last(self) -> Option[T]` |  |
| `push_back` | `push_back(*self, v: T) -> bool` | Adds to the back, and answers whether there was room. |
| `push_front` | `push_front(*self, v: T) -> bool` | Adds to the front, stepping `head` backwards into the space behind it. |
| `pop_front` | `pop_front(*self) -> Option[T]` | Takes from the front, moving the value out and leaving its slot empty. |
| `pop_back` | `pop_back(*self) -> Option[T]` | Takes from the back, moving the value out and leaving its slot empty. |
| `overwrite` | `overwrite(*self, v: T) -> Option[T]` | Adds to the back, **dropping the oldest value to make room** where the ring is full, and answers whatever was dropped. |
| `clear` | `clear(*self)` | Lets go of every value, there and then, leaving every slot empty. |

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

### Index for InlineRing[T, N]

```sysl
impl[T, const N: usize] Index[usize, T] for InlineRing[T, N]
```

### Index for Ring[T]

```sysl
impl[T] Index[usize, T] for Ring[T]
```

Subscripting reaches the bounds-checked members rather than the storage, so `r[i]` counts from the
**front** of the ring and cannot read a slot the ring is not currently using.

### IndexSet for InlineRing[T, N]

```sysl
impl[T, const N: usize] IndexSet[usize, T] for InlineRing[T, N]
```

### IndexSet for Ring[T]

```sysl
impl[T] IndexSet[usize, T] for Ring[T]
```

### Iterate for InlineRingCursor[T, N]

```sysl
impl[T, const N: usize] Iterate for InlineRingCursor[T, N]
```

### Iterate for RingCursor[T]

```sysl
impl[T] Iterate for RingCursor[T]
```

### Walk for InlineRing[T, N]

```sysl
impl[T, const N: usize] Walk for InlineRing[T, N]
```

`for x in r` walks the ring from the front to the back. The cursor holds a copy of the run it
started on, so pushing onto the ring while a cursor is live does not change what it reads.

### Walk for Ring[T]

```sysl
impl[T] Walk for Ring[T]
```

`for x in r` walks a ring from the front to the back, through the cursor `walk` answers.
