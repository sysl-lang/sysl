---
title: The seq module
summary: "`sysl.seq` — `map`, `filter`, `fold` and the seven questions beside them, as one trait a slice and a `Buf` both answer."
weight: 56
---

**Every declaration in `sysl.seq`, with its signature:** [the generated API page](/api/sysl-seq/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.seq` is what a program asks *of* a sequence of values: transform every element, keep the ones
satisfying a predicate, carry a running value across the lot. It is a single trait, `Sequence[T]`,
and the library implements it twice — for a built-in slice and for a
[`Buf`](/library/buf/) — so the same ten names work on either. One function stands beside the trait,
`generate`, for the case where there is no sequence yet.

```sysl
import sysl.seq.Sequence

val xs = [1, 2, 3, 4, 5, 6]

print(xs[..].filter(n -> n % 2 == 0).map(n -> n * n))
print(xs[..].fold(0, (a, n) -> a + n))
```

```output
[4, 16, 36]
21
```

**This is the surface [`sysl.slices`](/library/slices/) deliberately does not have.** That module asks
*where is this value* — `index_of`, `contains`, `min_index` — and answers every question by comparing
elements. This one asks *which of these satisfies this predicate*, and answers every question by
calling something the caller wrote. The two never overlap, and they are separate modules because they
have different costs: nothing in `sysl.slices` allocates, and the three members here that build a new
sequence do.

## The trait

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

**`map` and `flat_map` declare a type parameter of their own**, which is a thing a trait's member may
do ([generics](/reference/generics/#a-member-declares-its-own)) and is what this module needed before
it could be written: a `map`'s result type is chosen at the call by what the closure returns, and by
nothing about the receiver.

**Every one of them declares another without writing it**, because a bare-arrow parameter *is* one:
`f: T -> U` is sugar for a type parameter bounded by `Fn(T) -> U`, which is what makes the closure a
type argument rather than something on the heap. So none of these members has a table slot, and
`Sequence` has no useful trait object — the trade this module makes deliberately, and the one [What
it costs](#what-it-costs) is about.

## Asking a question

`any` and `all` answer whether some element or every element satisfies a predicate, and both stop at
the first element that settles it. `find` answers the element, `position` answers where it is, and
`count_where` answers how many.

```sysl
import sysl.seq.Sequence

val xs = [3, 8, 2, 9, 4]

print(xs[..].any(n -> n > 8), xs[..].all(n -> n > 1))
print(xs[..].find(n -> n > 5).unwrap(), xs[..].position(n -> n > 5).unwrap())
print(xs[..].count_where(n -> n % 2 == 0))
```

```output
true true
8 1
3
```

**An empty sequence answers `true` to `all`**, which is the vacuous truth every language with this
operation agrees on: there is no element to be a counterexample.

**`find` and `position` are two questions rather than one.** A caller that wants to look at what it
found should not have to subscript for it, and a caller that wants to write through the sequence needs
the index and not a copy of the element.

**`count_where` and not `count`**, which is the name a reader reaches for first. A type's fields and
its members share one namespace, and `count` is the field four of the library's own containers hold
their length in — `Buf`, `Map`, `List` and `Deque` alike — so a member of that name could not be given
to any of them. The label is Swift's, and it says which of the two questions about a length this one
is.

## Folding

`fold` carries a running value across every element, in order, and is the operation the six above are
special cases of. The accumulator's type is the member's own rather than the sequence's, so summing a
slice of names into a length is as ordinary as summing numbers:

```sysl
import sysl.seq.Sequence

val xs = ["ab", "c", "def"]

print(xs[..].fold(0usize, (a, s) -> a + s.len))
```

```output
6
```

**The suffix on the seed is doing real work here and is not the noise it looks like.** The accumulator
type is read off `init` and off nothing else — an annotation on the binding does not reach it, so
`val total: usize = xs[..].fold(0, …)` is refused rather than being the place to say it once. That is
a gap in inference rather than a rule about folding, and it is filed; until it closes, the seed is
where a fold that accumulates at some other type says so.

## Visiting every element

`each` hands every element, in order, to something that returns nothing. **A `for` loop is what a
program should reach for**, and this does not replace it — what it is for is the tail of a chain,
where dropping out of expression position to write a loop is what breaks the line up.

```sysl
import sysl.seq.Sequence

val xs = [1, 2, 3, 4]

xs[..].filter(n -> n % 2 == 1).each(n -> print(n))
```

```output
1
3
```

**A closure captures by value**, so a record kept outside the call has to be storage rather than a
copy of one: a captured `Buf` is filled and thrown away, and a `&Buf` is the buffer itself.

```sysl
import sysl.seq.Sequence
import sysl.buf.{Buf, buf}

val xs = [1, 2, 3]
var seen: &Buf[int] = buf()

xs[..].each(n -> seen.push(n * 2))

print(seen.len(), seen[0], seen[2])
```

```output
3 2 6
```

## Building a new sequence

`map`, `filter` and `flat_map` each answer a **new slice**, eagerly — the elements are there when the
call returns, so each stage is complete — and paid for — before the next begins.

```sysl
import sysl.seq.Sequence
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(2)
b.push(3)

print(b.map(n -> s"<${n}>"))
print(b.count_where(n -> n > 2))
```

```output
[<2>, <3>]
1
```

That is a `Buf` answering both, and it is the same implementation the slice has: a buffer's `view` is
its storage rather than a copy of it, so `Buf`'s half of the module is ten lines of delegation. What
it buys is that a program holding a buffer reads like one holding a slice, instead of carrying a
`.view()` at every call site that says nothing about what it is doing.

**`flat_map` turns each element into a sequence and lays them end to end.** The closure hands back
storage that has to outlive the call, which an array literal does not — a literal is a value in the
frame that built it:

```sysl
import sysl.seq.Sequence
import sysl.buf.{Buf, buf}

pair(n: int) -> []int
    var b: Buf[int] = buf()

    b.push(n)
    b.push(n * 10)

    b.view()

val xs = [1, 2, 3]

print(xs[..].flat_map(n -> pair(n)))
```

```output
[1, 10, 2, 20, 3, 30]
```

Writing the literal inline is refused, and the refusal is about the type rather than about lifetimes:
an array literal is an **array**, `[2]int`, and the member asks for a slice.

```sysl
import sysl.seq.Sequence

val xs = [1, 2]

print(xs[..].flat_map(n -> [n, n * 10]))
```

```error
it implements 'sysl.Fn1[int, [2]int]'
```

## Making one from a count

Everything above takes a sequence that already exists. `generate` makes one, out of nothing but a
count and a closure from an index to an element.

```sysl
import sysl.seq.{Sequence, generate}

val squares = generate(5, i -> i * i)

print(squares)
print(generate(3, i -> s"row ${i}"))
print(generate(0, i -> i + 1))
print(squares.filter(n -> n > 2).map(n -> n + 1))
```

```output
[0, 1, 4, 9, 16]
[row 0, row 1, row 2]
[]
[5, 10, 17]
```

It is a free function rather than a member of the trait because a creator has no receiver: there is
nothing for `self` to be until the call has already done the work. What it hands back is the same
`[]U` every member here returns, so the last line above is a chain and not a special case. `f` is
called exactly once for each index, in order, and the buffer is given the length the answer is known
to have, so it grows once.

**`(0..<n).map(f)` says the same thing**, and is what a reader reaches for first. A range with both
ends written is a [value](/reference/expressions/) and implements this trait, so every member here is
on one.

```sysl
import sysl.seq.Sequence

print((0..<5).map(i -> i * i))
print((0..<10).filter(i -> i % 3 == 0))
print((0..<10).fold(0, (a, n) -> a + n))
```

```output
[0, 1, 4, 9, 16]
[0, 3, 6, 9]
45
```

**Nothing is materialized to answer a question about a range**, which is the difference from the
slice implementation rather than a detail of it: the seven members that do not build a sequence walk
and stop, so a question about the front of a very long range costs the predicate and no storage at
all.

```sysl
import sysl.seq.Sequence

print((0..<1000000).any(i -> i == 3))
```

```output
true
```

So what `generate` keeps is the **allocation**. A count is a length in hand, so it gives its buffer
the size the answer will be and grows once; a range is two bounds, and working its length out
generically means arithmetic at a type the bound says only is an integer — so a range's `map` doubles
the way `filter` does. Reach for `generate` where the count is what you have, and for the range
where the range is.

## The members are on a slice, not on an array

The `impl` covers `[]const E`, so an array reaches these members the way it reaches anything expecting
a slice — by being sliced, with `[..]`. That is one character and it is deliberate: an array is a
value, a slice is a view of one, and which of the two a `map` walks is the difference between copying
six words and not.

```sysl
import sysl.seq.Sequence

val xs = [1, 2, 3]

print(xs.map(n -> n * 2))
```

```error
type '[3]int' has no method 'map'
```

**Written for `[]const E` rather than `[]E`**, which is what makes it reachable on both: a `[]T` is
accepted wherever a `[]const T` is wanted, so a mutable slice finds these members too and a read-only
one is not handed a member that could write through it. Nothing here writes.

## What it costs

**The callable costs nothing.** Every member takes it by **bare arrow** — `f: T -> U` — which is a
bounded type parameter ([types](/reference/types/#function-types)): the closure is a *type argument*,
so the member is monomorphized into a copy that calls it directly and nothing goes on the heap. The
seven members that build no sequence therefore reach the allocator not at all, and a `fold` over a
slice costs exactly what the loop it replaces costs.

The other spelling would have cost one allocation per call. `&Fn(T) -> U` is a counted reference, so
the closure is boxed at the call before an element is touched — which is what these members took
until a trait's member was allowed to write an arrow at all.

**Three of the ten allocate, and for the other reason**: `map`, `filter` and `flat_map` build a
sequence to hand back, which is inherent. `map` is given the length its answer is known to have, so it
grows once rather than at every doubling; `filter` cannot know its own length in advance and does not
pretend to. `generate` is in `map`'s position — the whole of what it returns is built, and the count
it was handed is the length, so it grows once and allocates for nothing else.

**What the arrow costs instead is `Sequence`'s trait object.** A member that declares type parameters
of its own — which is what an arrow desugars to — has no table slot
([traits](/reference/traits/#object-safety)), so a `&Sequence[int]` cannot dispatch these. That is the
right trade here, because what a program holds is a slice or a `Buf` and both have types; a trait
meant to be *erased* writes `&Fn(…)` deliberately, and keeps its slots.

## Eager, and what that leaves room for

`map` returns a slice rather than a description of one, and each stage of a chain is complete before
the next begins. **The lazy alternative** — an adapter that describes the work and is evaluated by
whatever finally walks it — needs a type a trait can name without an implementation spelling it out,
which is what an [associated type](/reference/traits/) is. The language has those now; the library has
not been rebuilt on them, so [`Iterate`](/library/core/) still carries its element as a parameter, and
a slice is not an `Iterate` in any case — a `for` walks one by address.

Swift makes the same trade and opts into laziness explicitly, with the eager spelling as the default
one. That is the shape this leaves room for: a `.lazy` view is additive, and nothing on this page
changes when it arrives.
