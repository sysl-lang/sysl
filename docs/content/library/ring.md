---
title: The ring module
summary: "`sysl.container.ring` — `Ring[T]`, a queue of a fixed capacity over storage the caller supplies: no allocator, a full ring that answers rather than grows, and `overwrite` for a window over an unbounded stream."
weight: 33
---

**Every declaration in `sysl.container.ring`, with its signature:** [the generated API page](/api/sysl-container-ring/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`Ring[T]` is a queue cheap to take from at either end, laid over a slice the caller hands it. It is a
module of its own beside [`sysl.container`](/library/container/#the-queue-at-both-ends) rather than a
sixth type inside it, because it is the one container in the library that declares `@no_alloc`, and a
capability is [a property of the whole module](/reference/modules/#capabilities-are-a-module-property).

```sysl
import sysl.container.ring.ring

var slots: [4]int = [0; 4]
var r = ring(slots[..])

print(r.capacity(), r.len(), r.is_empty())

r.push_back(1)
r.push_back(2)
r.push_front(0)

print(r.len(), r.first(), r.last())
print(r.pop_front(), r.pop_front())
print(r.pop_front(), r.pop_front())
```

```output
4 0 true
3 Some(0) Some(2)
Some(0) Some(1)
Some(2) None
```

| | |
|---|---|
| `ring(slots)` | an empty ring over storage the caller supplies |
| `r.capacity()` | how many values it can hold, which is the length of that slice |
| `r.len()`, `r.is_empty()`, `r.is_full()` | how many are in it, and the two questions about the ends of that |
| `r.push_back(v)`, `r.push_front(v)` | add at either end; `false` where the ring is full |
| `r.pop_front()`, `r.pop_back()` | take from either end, `None` where it is empty |
| `r.first()`, `r.last()`, `r.get(i)` | look without taking, as an `Option` |
| `r[i]`, `r.at(i)`, `r.set(i, v)` | the `i`th value **from the front**, panicking past the end |
| `r.overwrite(v)` | add at the back, dropping the oldest to make room, and answer what was dropped |
| `r.clear()`, `r.walk()` | forget every value; walk from the front to the back |

## The reason it exists beside `Deque` is the storage, not the shape

A [`Deque[T]`](/library/container/#the-queue-at-both-ends) is this same ring with a growth path under
it: a head index, both ends wrapping, and a `Buf` underneath that doubles when the elements no longer
fit. So it **owns** its elements, and owning them means being able to widen them, and widening them
means a heap.

This one cannot grow. Its capacity is the length of the slice it was handed, a full ring answers
rather than doubles, and `@no_alloc` on the file is the whole of the claim — which is what makes it
reachable from a program that has given the allocator up:

```sysl
@no_alloc
@no_os

import sysl.container.ring.ring

var slots: [3]int = [0; 3]
var r = ring(slots[..])

r.push_back(1)
r.push_back(2)

print(r.len(), r.first(), r.last())
```

```output
2 Some(1) Some(2)
```

The `Deque` written the same way is refused, and it is refused at the `push_back` rather than at the
import:

```sysl
@no_alloc

import sysl.container.{Deque, deque}

var work: Deque[int] = deque()

work.push_back(1)

print(work.len())
```

```error
which makes heap storage, and this module declared '@no_alloc'
```

**That is not a defect in `Deque` and no version of it could avoid it** — growing is the whole of
what a `Deque` is for, and the same refusal falls on `Buf` for the same reason. What it means is that
a board, an interrupt handler and a signal handler — the three places a bounded queue is wanted most
— had no queue in this library at all until this module, and now have one whose bound is a number the
caller chose.

## A full ring is a state, not a failure

`push_back` and `push_front` answer a `bool`, where `Deque`'s `push_back` answers nothing at all. The
asymmetry is the point: a growing queue cannot be full, so there is nothing for it to report; a
bounded one being full is the state it exists to have.

```sysl
import sysl.container.ring.ring

var slots: [2]int = [0; 2]
var r = ring(slots[..])

print(r.push_back(1), r.push_back(2))
print(r.is_full(), r.push_back(3))
print(r.len(), r.last())
```

```output
true true
true false
2 Some(2)
```

So a refused push is an ordinary answer a caller reads, and `is_full()` is what it asks beforehand
where it would rather not build the value at all.

## `overwrite` is what makes it a ring rather than a bounded queue

Everything above is a queue with a ceiling. `overwrite` is the operation that turns the ceiling into
a **window**: it adds at the back, drops the oldest value where there is no room, and answers
whatever it dropped.

```sysl
import sysl.container.ring.ring

var slots: [3]int = [0; 3]
var r = ring(slots[..])

print(r.overwrite(1), r.overwrite(2), r.overwrite(3))
print(r.overwrite(4), r.overwrite(5))
print(r[0], r[1], r[2])
```

```output
None None None
Some(1) Some(2)
3 4 5
```

**A caller cannot compose this out of the other members**, which is why it is a member. The obvious
spelling — pop the front, then push the back — is wrong exactly when the ring is *not* full, where it
would throw away a value nothing was competing with. What it is for is the last `n` of something
unbounded: log lines, samples, a scrollback, a moving average. There the newest value is the one that
matters and the oldest is what a bounded queue would have refused the newest for.

A ring with **no storage at all** drops what it was given rather than treating it as an edge case:
there is no slot for the value, so `overwrite` evicts it at once and answers `Some(v)`.

## The subscript counts from the front, whatever the ring is doing underneath

`r[i]` is the `i`th value from the front, not the `i`th slot, and the wrapping is invisible to a
caller — which is what `walk()` iterates in as well.

```sysl
import sysl.container.ring.ring

var slots: [3]int = [0; 3]
var r = ring(slots[..])

r.push_back(1)
r.push_back(2)
r.push_back(3)
r.pop_front()
r.pop_front()
r.push_back(4)
r.push_back(5)

for v in r.walk()
    print(v)
```

```output
3
4
5
```

That run has wrapped: `3` is in the last slot of the storage and `4` and `5` are in the first two.
`r[0]` is still `3`. Subscripting reaches the bounds-checked members rather than the storage, so it
cannot read a slot the ring is not currently using — the slot exists, the element does not.

**The length is not rounded up to a power of two**, unlike `Deque`'s, and that is a consequence of
the capacity being the caller's number rather than one this module chose: a program that asked for
six slots gets six. So wrapping is a compare and a subtract instead of a mask.

**A cursor holds the storage rather than the ring**, so pushing while one is live leaves it reading
the run it started on.

## The storage is a pointer, and that is the crossing rule

The slots are held as a `*T` and a count rather than as a `[]T`, and it is not a preference. **A
struct holding a view may not reach another concurrency domain at all**, because a view owns its
elements through a count that is not atomic — so a ring that kept the slice would be refused at the
`spawn` that shares it. A raw pointer carries no count, so it crosses; what it costs is the same
trust `*p` already asks for, and the caller writes a slice at `ring(…)` and never sees the pointer.

**That is why [`Channel[T]`](/library/threads/#channel-t) can hold one.** A channel is a bounded
queue two threads hand values across, so it is a ring by construction and was four fields and its own
wrapping arithmetic before this module existed; what is left in that file now is the lock and the
closed flag. A `Ring[T]` written over a view would have made the channel unshareable one line before
the first `send`, which is the only thing a channel is for.

## The storage has to outlive the ring

This is the one thing the type asks of a caller that `Deque` does not, and it follows from the same
sentence: what a `Ring[T]` stores is an address and a length, so the slice behind that address has to
still be there. A module-level `static var`, a local of a scope that outlives every holder, or a
region something else owns — all fine; a slice over a local of a function that has returned is not.

It is the same contract [`channel(slots[..])`](/library/threads/#channel-t) already has, and the same
one every C binding in the organisation makes when it hands a library a buffer to work in.
