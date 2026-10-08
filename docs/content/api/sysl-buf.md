---
title: sysl.buf
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.buf
summary: "The growable sequence and the sink built on it."
---

A module of its own because nothing in the language reaches either: an array literal makes a `[]T`
and a `for` walks whatever implements `Iterate`, so a program that wants a sequence that grows is
asking for one, and asks with an `import`. What renders into a buffer without a program naming a
sink is `str(x)`, and that goes through storage the compiler lays out rather than through
`ByteSink` -- which is why the sink can sit here beside the buffer it wraps.

## Index

[`buf`](#buf) [`buf_with_capacity`](#buf_with_capacity) [`byte_sink`](#byte_sink) [`Buf`](#buf-1) [`ByteSink`](#bytesink) [Display for Buf[T]](#display-for-buft) [Eq for Buf[T]](#eq-for-buft) [Fallible for ByteSink](#fallible-for-bytesink) [Index for Buf[T]](#index-for-buft) [IndexSet for Buf[T]](#indexset-for-buft) [Walk for Buf[T]](#walk-for-buft) [Writer for ByteSink](#writer-for-bytesink)

## Functions

### `buf`

```sysl
buf[T]() -> Buf[T]
```

### `buf_with_capacity`

```sysl
buf_with_capacity[T](n: usize, fill: T) -> Buf[T]
```

A buffer that has already been given room for `n` elements, for a caller that knows roughly how
many are coming. What it saves is the reallocation-and-copy at each doubling on the way up, which
is the one cost a growable sequence has that an exactly-sized array does not.

**`fill` is not stored anywhere.** The slots start out empty (`slot_storage`), so a counted `fill`
is not kept alive by the room it would once have been repeated into. The parameter stays because
callers write it, and because it is what lets a call say the element type without brackets.

### `byte_sink`

```sysl
byte_sink() -> ByteSink
```

## Types

### `Buf`

```sysl
struct Buf[T]
    private core: &BufCore[T]
```

A sequence that grows, and the name every holder of it shares.

**A `Buf` is a reference, the way a collection is in most languages a program will have met.**
Assigning one, passing it, capturing it in a closure, keeping it in a struct and reading it back
out of another container all hand over a second name for the *same* buffer, so a push through any
of them is seen through every other -- `Buf[Buf[int]]` included, where `outer.at(0).push(x)`
grows the inner buffer the outer one holds. A program that wants a second buffer asks for one with
`buf()` and fills it.

What it holds is one counted reference to a `BufCore`, which carries the storage, the count and
the clause tying them together; every member here delegates to it. So the price of sharing is one
load per access and one allocation per buffer made, and `push` keeps the shape that lets a caller
absorb it -- marked `@inline` here as on the core, so a push through the handle is still a store.
The elements are let go of when the *last* name for the buffer dies, or earlier, by `pop`,
`truncate`, `clear` and `remove`, exactly as before.

**It has no zero value.** A buffer is a box, and a box is made by somebody, so `var b: Buf[int]`
with nothing after it is refused -- write `= buf()`.

The bounds-checked members panic rather than returning an `Option`, which is the same bargain
`unwrap` makes -- an index past the end is a mistake in the program, not a value it meant to
handle -- while `pop` returns one, because taking from an empty sequence is a question a caller
asks on purpose.

| Member | Signature | Description |
|---|---|---|
| `elems` | `elems -> []T` | The storage, spare capacity included -- for a reader that wants the address of an element, as `&b.elems[i]` is. |
| `len` | `len(self) -> usize` |  |
| `cap` | `cap(self) -> usize` |  |
| `is_empty` | `is_empty(self) -> bool` |  |
| `at` | `at(self, i: usize) -> T` |  |
| `set` | `set(self, i: usize, v: T)` |  |
| `push` | `push(self, v: T)` |  |
| `extend` | `extend(self, xs: []const T)` |  |
| `pop` | `pop(self) -> Option[T]` |  |
| `truncate` | `truncate(self, n: usize)` |  |
| `clear` | `clear(self)` |  |
| `insert` | `insert(self, i: usize, v: T)` |  |
| `remove` | `remove(self, i: usize) -> T` |  |
| `copy` | `copy(self) -> Buf[T]` | A second buffer holding the same elements, sized to them -- the one way to get a buffer that is not this one, every other way of handing a `Buf` on handing on this one. |
| `view` | `view(self) -> []T` | The elements as a slice, which is what everything reading a `Buf` in bulk goes through. |

### `ByteSink`

```sysl
struct ByteSink
    bytes: &Buf[u8]
```

A writer that gathers, and the reason it is supplied rather than left to each program is the rule
that a specifier describes the field the *whole* value occupies (`library/core.md § A specifier
is the whole value's field`): an implementation rendering more than one part has to gather them
before it can pad what they came to, and gathering needs somewhere to put them.

It is one of the library's two writers; the other is `Stdout`, in the standard module, which
stands for standard output and holds no state at all.

| Member | Signature | Description |
|---|---|---|
| `text` | `text(self) -> []u8` |  |

## Implementations

### Display for Buf[T]

```sysl
impl[T: Display] Display for Buf[T]
```

A buffer renders as the sequence it holds, which is `view()` -- the same delegation its equality
goes through, and for the same reason.

What the delegation buys here beyond agreement is the padding: a specifier describes the field the
whole value occupies, and `[]T`'s own block already measures a rendering before it pads one, so a
width on a `Buf` pads the whole sequence rather than being worked out a second time.

### Eq for Buf[T]

```sysl
impl[T: Eq] Eq for Buf[T]
```

A buffer compares as the sequence it holds, which is `view()` -- the elements it actually has,
rather than the backing slice, whose slots past `count` were never written.

**The delegation is on purpose.** A `Buf[T]` is the growable form of a `[]T` and the two would be
hard to explain if either answered differently, so the loop is written once, over the slice, and
this is the growable sequence saying it is the same sequence.

### Fallible for ByteSink

```sysl
impl Fallible for ByteSink
```

A buffer that grows has nothing to fail at, so the whole of implementing the latch is opting into
it: every member of `Fallible` has a default, and a trait like that needs no block
(`reference/traits.md § Conformance is explicit, always`).

### Index for Buf[T]

```sysl
impl[T] Index[usize, T] for Buf[T]
```

Subscripting reaches the bounds-checked members rather than the storage, so `b[i]` on a `Buf`
means what `b.at(i)` means and cannot read a slot past the count that the backing slice still has.

### IndexSet for Buf[T]

```sysl
impl[T] IndexSet[usize, T] for Buf[T]
```

### Walk for Buf[T]

```sysl
impl[T] Walk for Buf[T]
```

`for x in b` walks `view()`: the elements the buffer held when the loop began, read by index as a
slice's are, rather than through a cursor that would cost a call apiece. An element pushed during
the walk is not visited, the view having been taken before the first turn.

### Writer for ByteSink

```sysl
impl Writer for ByteSink
```
