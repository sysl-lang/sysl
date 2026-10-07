---
title: sysl.seq
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.seq
summary: "What a sequence of values can be asked, in one trait, so that a slice and a `Buf` answer the same names."
---

**This is the surface `sysl.slices` deliberately does not have.** That module asks *where is this
value* -- `index_of`, `contains`, `min_index` -- and every one of its questions is answered by
comparing elements. This one asks *which of these satisfies this predicate*, and every question is
answered by calling something the caller wrote. The two never overlap.

**It is a trait rather than a family of free functions because a built-in slice has no members of
its own**, and a method on one can only arrive through an `impl`. That is also why it could not be
written until a trait's member was allowed to declare type parameters of its own: `map` chooses its
result type at the call, so `U` belongs to the member and to nothing else.

**The operations are EAGER.** `map` returns a new slice rather than a description of one, and a
chain allocates once per stage. The lazy alternative is an adapter over `Iterate`, evaluated by
whatever finally walks it.

**What blocked it no longer does.** This paragraph reported that laziness needed a type a trait
could name without spelling and that sysl had none; it has one, and `Iterate` uses it -- the
element is `type Item`, and a cursor's own type is what an adapter would name the same way. What
is still true is the second half: a slice is not an `Iterate` to begin with, since a `for` walks
one by address, so a lazy chain over a slice starts by making a cursor for it. Swift makes the
same trade and opts into laziness explicitly, and that is the shape this leaves room for.

**The callable is taken by BARE ARROW, so nothing is boxed to make one of these calls.** A
parameter written `f: T -> U` is a bounded type parameter (`reference/types.md § Function
types`): the closure is a type argument, monomorphized into a copy of the member and called
directly. The other spelling, `&Fn(T) -> U`, is a counted reference, and the closure goes on the
heap at every call -- which is what these members took until a trait's member was allowed to
write an arrow at all.

**What the arrow costs is the table slot**, since a member with type parameters of its own is not
a function until a call names them. So `Sequence` has no object: a `&Sequence[int]` cannot
dispatch `map` or `fold`. That is the right trade for this module -- what a program holds is a
slice or a `Buf`, both of which have types -- and it is the reason a trait meant to be *erased*
writes the boxed form deliberately.

So the only allocation left is the one that is inherent: **three of the ten build a new sequence
to hand back**, and the seven that do not now allocate nothing at all.

**One thing in this module is not part of the trait**: `generate`, at the foot of the file, which
makes a sequence out of a count. It cannot be a member of anything -- a creator has no receiver --
and it is here rather than in `sysl.buf` because the shape it has is `map`'s.

**A range implements this trait too**, in `range.sysl` beside it, which is what makes
`(0..<n).map(f)` the ordinary spelling and leaves `generate` the shorter of two ways to say one
thing. What it still has that the range does not is the **length**: a count is a `usize` in hand,
so `generate` gives its buffer the size the answer will be and grows once, where a range's `map`
would have to work its length out generically from bounds the trait says only are integers.

## Index

[`generate`](#generate) [`Sequence`](#sequence) [Sequence for []const E](#sequence-for-const-e) [Sequence for Buf[E]](#sequence-for-bufe) [Sequence for Range[E]](#sequence-for-rangee)

## Functions

### `generate`

```sysl
generate[U](n: usize, f: usize -> U) -> []U
```

A sequence of `n` elements, each produced from its index.

**The one thing here that makes a sequence rather than taking one**, and a free function because a
creator has no receiver: there is nothing for `self` to be until the call has already done the
work.

It is in this module because the shape a caller wants is `map`'s -- an index goes in and an element
comes out. **`(0..<n).map(f)` says the same thing now** and is what a reader reaches for first;
what this keeps is the allocation, since a count is a length and a range is two bounds.

`f` is called exactly once for each index, in order, and the buffer is given the length the answer
is known to have, so it grows once.

## Traits

### `Sequence`

```sysl
trait Sequence[T]
    fold[A](self, init: A, f: (A, T) -> A) -> A
    any(self, p: T -> bool) -> bool
    all(self, p: T -> bool) -> bool
    find(self, p: T -> bool) -> Option[T]
    position(self, p: T -> bool) -> Option[usize]
    count_where(self, p: T -> bool) -> usize
    each(self, f: T -> unit)
    map[U](self, f: T -> U) -> []U
    filter(self, p: T -> bool) -> []T
    flat_map[U](self, f: T -> []U) -> []U
```

The ten operations a sequence of `T` answers: seven that walk it and answer something, and three
that build a new sequence.

| Member | Signature | Description |
|---|---|---|
| `fold` | `fold[A](self, init: A, f: (A, T) -> A) -> A` | Every element, in order, passed to something that returns a value carried to the next -- which is the operation the other six here are special cases of. |
| `any` | `any(self, p: T -> bool) -> bool` | Whether any element satisfies the predicate. |
| `all` | `all(self, p: T -> bool) -> bool` | Whether every element satisfies it. |
| `find` | `find(self, p: T -> bool) -> Option[T]` | The first element satisfying the predicate, or `None`. |
| `position` | `position(self, p: T -> bool) -> Option[usize]` | Where the first element satisfying the predicate is, or `None`. |
| `count_where` | `count_where(self, p: T -> bool) -> usize` | How many elements satisfy it. |
| `each` | `each(self, f: T -> unit)` | Every element, in order, handed to something that returns nothing. |
| `map` | `map[U](self, f: T -> U) -> []U` | Every element, transformed, in a slice of its own. |
| `filter` | `filter(self, p: T -> bool) -> []T` | The elements satisfying the predicate, in a slice of their own. |
| `flat_map` | `flat_map[U](self, f: T -> []U) -> []U` | Every element turned into a sequence, and all of those laid end to end. |

## Implementations

### Sequence for []const E

```sysl
impl[E] Sequence[E] for []const E
```

A slice is what everything else in the library hands back, so it is the implementation that
matters: `Buf`'s below is this one reached through `view`.

**Written for `[]const E` rather than `[]E`**, which is what makes it reachable on both:
`reference/arrays.md § []const T — a view that may not be written` says a `[]T` is accepted wherever a
`[]const T` is wanted, so a mutable slice finds these members and a read-only one is not given a
member that could write through it. Nothing here writes.

### Sequence for Buf[E]

```sysl
impl[E] Sequence[E] for Buf[E]
```

A buffer answers every one of these by handing its elements over as a slice, which is what `view`
is for and costs nothing -- the slice is the storage rather than a copy of it.

**It is written out rather than left to the caller** so that a program holding a `Buf` reads the
same as one holding a slice. Without it every call site would carry a `.view()` that says nothing
about what it is doing.

### Sequence for Range[E]

```sysl
impl[E: Integer + Add + One] Sequence[E] for Range[E]
```

A range is a sequence, which is the reason `Range` is a value at all.

`(0..<n).map(f)` is what a caller reaches for first, and until a range could be named there was no
receiver for it to be a method on -- `generate` in the file beside this one stands in for that one
spelling, and the other nine members had no stand-in at all.

**Every member walks the range and none of them subscripts it**, which is the difference from the
slice implementation: there is no storage here, so a range answers a question about a million
values without a million values existing. `any`, `all`, `find`, `position` and `count_where` stop
as early as they would over a slice, so `(0..<1_000_000).any(p)` costs what the predicate costs
and nothing else.

**The three that build a sequence grow it as they go.** A range's length is arithmetic rather than
a field, but working it out generically means a subtraction into a `usize` at a type the bound
says only that it is an integer -- so the buffer doubles the way `filter`'s does everywhere else
in this module, and the count a caller already knows is what `generate` is for.
