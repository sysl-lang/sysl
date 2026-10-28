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
sink is `x.to_string()`, and that goes through storage the compiler lays out rather than through
`ByteSink` -- which is why the sink can sit here beside the buffer it wraps.

## Index

[`buf`](#buf) [`buf_filled`](#buf_filled) [`buf_with_capacity`](#buf_with_capacity) [`byte_sink`](#byte_sink) [`freeze_buf`](#freeze_buf) [`Buf`](#buf-1) [`ByteSink`](#bytesink) [`Frozen`](#frozen) [`FrozenWalk`](#frozenwalk) [Buf[T]](#buft) [Debug for Buf[T]](#debug-for-buft) [Debug for Frozen[T]](#debug-for-frozent) [Display for Buf[T]](#display-for-buft) [Display for Frozen[T]](#display-for-frozent) [Drop for Frozen[T]](#drop-for-frozent) [Eq for Buf[T]](#eq-for-buft) [Fallible for ByteSink](#fallible-for-bytesink) [Index for Buf[T]](#index-for-buft) [Index for Frozen[T]](#index-for-frozent) [IndexPlace for Buf[T]](#indexplace-for-buft) [IndexSet for Buf[T]](#indexset-for-buft) [Iterate for FrozenWalk[T]](#iterate-for-frozenwalkt) [Walk for Buf[T]](#walk-for-buft) [Walk for Frozen[T]](#walk-for-frozent) [Writer for ByteSink](#writer-for-bytesink)

## Functions

### `buf`

```sysl
const buf[T]() -> Buf[T]
```

### `buf_filled`

```sysl
const buf_filled[T](n: usize, value: T) -> Buf[T]
```

A buffer of `n` elements, every one of them `value` -- what a RAM disk, a frame buffer or a table
of counters starts as. The storage is sized to `n` once and each slot is filled in place, so it
costs one allocation and `n` stores rather than the doublings of a `push` loop.

**Every element is a copy of `value`**, the way any assignment copies: a plain value is repeated,
and a counted one is shared -- `n` elements naming one object, holding `n` counts of it.

### `buf_with_capacity`

```sysl
const buf_with_capacity[T](n: usize, fill: T) -> Buf[T]
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

### `freeze_buf`

```sysl
freeze_buf[T](b: Buf[T]) -> &sync Frozen[T]
```

The buffer's elements, taken: copied once into storage of their own, sized to them, and the
buffer left empty -- for this name and every other name of the same buffer, which is what makes the
result immutable. A view taken of the buffer earlier still reads the old storage, which the frozen
one shares nothing with.

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

**A `Buf` in storage the program zeroed itself is EMPTY until it is assigned.** A `calloc`ed block,
a collector's arena or any other memory the language did not initialise holds a handle naming no
buffer at all. Every reader answers as an empty buffer does -- `len()` is 0, `view()` is empty,
`at` reports the index past the end, `for` visits nothing, `copy()` makes a fresh empty buffer --
and assigning `buf()` into it gives it storage. What cannot work is growing it in place: a member
that adds an element (`push`, `extend`, `insert`) is handed the handle by value and has nowhere to
put a buffer it made, so it panics, saying so, rather than adding to nothing.

**`release()` is the way back to that state without allocating**: it lets go of this name's share
of the buffer -- the elements go when the last name does -- and leaves the handle naming nothing,
as zeroed storage does. It is what a finalizer for raw storage calls, where assigning `buf()` would
make a fresh buffer that is lost when the storage is freed.

The bounds-checked members trap rather than returning an `Option`, as an array's subscript does --
an index past the end is a mistake in the program, not a value it meant to handle -- while `pop`
returns one, because taking from an empty sequence is a question a caller asks on purpose. The
report is an array's (`index 5 out of bounds for length 3`, through `sysl_bounds_fail`), without
the caller's line.

| Member | Signature | Description |
|---|---|---|
| `elems` | `elems -> []T` | The storage, spare capacity included -- for a reader that wants the address of an element, as `&b.elems[i]` is. |
| `count` | `count -> usize` | How many elements there are -- `len()` under the name the field had. |
| `len` | `const len(self) -> usize` |  |
| `cap` | `const cap(self) -> usize` |  |
| `is_empty` | `const is_empty(self) -> bool` |  |
| `at` | `const at(self, i: usize) -> T` |  |
| `set` | `const set(self, i: usize, v: T)` |  |
| `push` | `const push(self, v: T)` |  |
| `extend` | `const extend(self, xs: []const T)` |  |
| `pop` | `const pop(self) -> Option[T]` |  |
| `truncate` | `const truncate(self, n: usize)` |  |
| `clear` | `const clear(self)` |  |
| `insert` | `const insert(self, i: usize, v: T)` |  |
| `reserve` | `const reserve(self, more: usize)` | Room for at least `more` elements past `len()`, so that many pushes -- or that many slots written through `spare()` -- need no growth. |
| `remove` | `const remove(self, i: usize) -> T` |  |
| `copy` | `const copy(self) -> Buf[T]` | A second buffer holding the same elements, sized to them -- the one way to get a buffer that is not this one, every other way of handing a `Buf` on handing on this one. |
| `freeze` | `const freeze(self) -> &sync Frozen[T]` | The elements, frozen: a `&sync Frozen[T]` every thread may read at once, and this buffer -- through this name and every other -- left empty (`Frozen`). |
| `view` | `const view(self) -> []T` | The elements as a slice, which is what everything reading a `Buf` in bulk goes through. |
| `release` | `release(*self)` | This name's share of the buffer given back, and the handle left naming nothing -- the state zeroed storage starts in, where every reader answers as an empty buffer does. |

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

### `Frozen`

```sysl
struct Frozen[T]
    private owner: usize
    private data: *T
    private n: usize
```

A buffer that will never change again, shared by every thread that holds it.

`Buf.freeze()` makes one: it takes the buffer's elements and answers a `&sync Frozen[T]`, which
is one atomic count on the whole buffer. A `&sync` object may hold it, and a `&sync Fn` may
capture it, so read-only heap data -- decoded pixels, a list of shapes -- is read by every thread
at once with no lock and no count per element (`reference/memory.md § A frozen buffer crosses`).

**Nothing writes it.** There is no `push`, no `set`, no subscript assignment and no writable view;
what it has is `len`, `f[i]`, `for x in f` and `view()`, a `[]const T`.

**A frozen buffer shares exactly what its elements share**, so a `&sync Frozen[T]` is formed only
where a `&sync T` could hold a `T`: scalars, `&sync U`, raw pointers, other frozen buffers, and the
structs and arrays built of them. The compiler asks that of the type argument where the reference
is formed, as it asks a struct's fields.

**Its fields carry no count**, and that is what makes it safe to read from every thread: the
storage is owned through a count that is not atomic, held here as the bare words of the slice that
owns it, so copying the struct -- as a `(self)` member does -- takes no share of anything. Only the
destructor touches that count, once, run by whichever thread lets go of the box's last share.

| Member | Signature | Description |
|---|---|---|
| `len` | `const len(self) -> usize` |  |
| `is_empty` | `const is_empty(self) -> bool` |  |
| `at` | `const at(self, i: usize) -> T` | The element at `i`, checked against the length: past it is a trap, as an array's index is. |
| `view` | `const view(&sync self) -> []const T` | The elements as a read-only slice. |

### `FrozenWalk`

```sysl
struct FrozenWalk[T]
    private data: *T
    private n: usize
    private i: usize
```

The cursor a `for` over a frozen buffer walks: the storage's address and length, read while the
frozen buffer the `for` was handed keeps it alive.

## Implementations

### Buf[T]

```sysl
impl[T: AnyBits] Buf[T]
```

The storage past a buffer's elements, filled by something other than `push` -- a C function
handed its address, or a writer handed it as a `[]T` -- and then taken into the count without a
second copy:

```
b.reserve(n)
val got = fill(b.spare())       // writes into the first `got` slots
b.set_len(b.len() + got)
```

**Only a buffer whose element type is `AnyBits` has these.** A slot `set_len` takes in holds
whatever was in its storage: the bytes written there, or, for a slot nobody wrote, whatever the
allocator left -- an unspecified value, which for an `AnyBits` type is still a value of it. A
`bool`, an enum, a `string` or a `&T` read out of bytes nobody wrote would not be one, so those
buffers are refused the call at compile time.

| Member | Signature | Description |
|---|---|---|
| `spare` | `spare(self) -> []T` | The slots past `len()`, as a slice of `cap() - len()` -- the room a writer may fill. |
| `set_len` | `set_len(self, n: usize)` | The length set to `n`, taking the slots between the old length and `n` as the elements they already hold. |

### Debug for Buf[T]

```sysl
impl[T: Debug] Debug for Buf[T]
```

A `Buf` shows as the sequence it holds, `[1, 2, 3]`, rather than as the storage it is built from.

### Debug for Frozen[T]

```sysl
impl[T: Debug] Debug for Frozen[T]
```

A frozen buffer shows as the sequence it holds, as a `Buf` does.

### Display for Buf[T]

```sysl
impl[T: Display] Display for Buf[T]
```

A buffer renders as the sequence it holds, which is `view()` -- the same delegation its equality
goes through, and for the same reason.

What the delegation buys here beyond agreement is the padding: a specifier describes the field the
whole value occupies, and `[]T`'s own block already measures a rendering before it pads one, so a
width on a `Buf` pads the whole sequence rather than being worked out a second time.

### Display for Frozen[T]

```sysl
impl[T: Display] Display for Frozen[T]
```

A frozen buffer renders as the sequence it holds, as a `Buf` does.

### Drop for Frozen[T]

```sysl
impl[T] Drop for Frozen[T]
```

The storage given back: the slice the words describe is rebuilt in a local, without a share being
taken, and the local going out of scope is the one release the storage gets -- which lets go of every
element it holds.

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

### Index for Frozen[T]

```sysl
impl[T] Index[usize, T] for Frozen[T]
```

### IndexPlace for Buf[T]

```sysl
impl[T] IndexPlace[usize, T] for Buf[T]
```

`b[i]` is the element where it lives: the address, checked against the count, so `b[i].f` reads
one field without copying the element and `ref e = b[i]` names it. Good until a `@relocates`
member moves the storage.

### IndexSet for Buf[T]

```sysl
impl[T] IndexSet[usize, T] for Buf[T]
```

### Iterate for FrozenWalk[T]

```sysl
impl[T] Iterate for FrozenWalk[T]
```

### Walk for Buf[T]

```sysl
impl[T] Walk for Buf[T]
```

`for x in b` walks `view()`: the elements the buffer held when the loop began, read by index as a
slice's are, rather than through a cursor that would cost a call apiece. An element pushed during
the walk is not visited, the view having been taken before the first turn.

### Walk for Frozen[T]

```sysl
impl[T] Walk for Frozen[T]
```

`for x in f` walks the elements by index through a cursor holding one share of the buffer, so the
walk allocates nothing.

### Writer for ByteSink

```sysl
impl Writer for ByteSink
```
