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

[`buf`](#buf) [`buf_with_capacity`](#buf_with_capacity) [`byte_sink`](#byte_sink) [`Buf`](#buf-1) [`ByteSink`](#bytesink) [Display for Buf[T]](#display-for-buft) [Eq for Buf[T]](#eq-for-buft) [Fallible for ByteSink](#fallible-for-bytesink) [Index for Buf[T]](#index-for-buft) [IndexSet for Buf[T]](#indexset-for-buft) [Writer for ByteSink](#writer-for-bytesink)

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

**The fill is a parameter because a generic `T` has no zero**, and an array is made by repeating a
value. It is written rather than inferred for the same reason the language asks for it anywhere
else: nothing about `T` says what an unused slot should hold, and none of these slots is read --
`count` starts at zero, so every one of them is written before anything can see it.

### `byte_sink`

```sysl
byte_sink() -> ByteSink
```

## Types

### `Buf`

```sysl
struct Buf[T]
    elems: []T
    count: usize
```

A sequence that grows, over a `[]T` it replaces when it runs out.

It is ordinary sysl rather than a built-in because a `[]T` that can be sized while running is all
it needs: `push` allocates a slice twice the size and copies, so the amortized cost is the one a
growable sequence has anywhere, and none of it is underneath the language.

The bounds-checked members panic rather than returning an `Option`, which is the same bargain
`unwrap` makes -- an index past the end is a mistake in the program, not a value it meant to
handle -- while `pop` returns one, because taking from an empty sequence is a question a caller
asks on purpose.

**The count never passes the storage, and the type says so.** The clause is checked wherever
either field is written, which in this module is at a handful of places the storage has just made
room at -- and in return every member is told it on entry. That is what makes `at` one compare:
the index is tested against `count` for the panic, and the slice's own test against `elems.len`
is then implied by it and folds.

| Member | Signature | Description |
|---|---|---|
| `len` | `len(self) -> usize` |  |
| `cap` | `cap(self) -> usize` |  |
| `is_empty` | `is_empty(self) -> bool` |  |
| `at` | `at(self, i: usize) -> T` |  |
| `set` | `set(*self, i: usize, v: T)` |  |
| `push` | `push(*self, v: T)` | One element appended, after growing the storage where there is no room left for it. |
| `extend` | `extend(*self, xs: []const T)` | Every element of a slice appended at once, which is what `push` in a loop was costing more than it looked like. |
| `pop` | `pop(*self) -> Option[T]` |  |
| `truncate` | `truncate(*self, n: usize)` |  |
| `clear` | `clear(*self)` |  |
| `insert` | `insert(*self, i: usize, v: T)` | An element put at `i`, with everything from there on moved up one. |
| `remove` | `remove(*self, i: usize) -> T` |  |
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

### Writer for ByteSink

```sysl
impl Writer for ByteSink
```
