---
title: The buf module
summary: "`sysl.buf` — `Buf[T]`, the growable sequence written in ordinary sysl, and `ByteSink`, the `Writer` that keeps what is written into it."
weight: 30
---

**Every declaration in `sysl.buf`, with its signature:** [the generated API page](/api/sysl-buf/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.buf` holds two types and three functions, and the interesting thing about the larger of them is
what it is *not*: **`Buf[T]` is not a type the compiler knows.** It is a `[]T` for the storage and
a `usize` for how much of it is live, kept in one box that every name for the buffer shares, and a
dozen members — ordinary sysl, in a file a program could have written.

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)
b.push(2)
b.push(3)

print(b.len(), b.cap(), b.is_empty())
print(b[0], b.at(2))

b[1] = 20

print(b.view().len, b.view()[1])
```

```output
3 8 false
1 3
3 20
```

Nothing in the language reaches it. An array literal makes a `[]T`, a `for` walks whatever implements
`Iterate` or `Walk`, and neither of those is a growable sequence — so a program that wants one asks, and the
`import` is where it asked. That is the [core module's](/library/core/) rule applied from the other
side: what a program cannot avoid needing arrives free, and what it has to ask for it asks for.

## Why it can be written at all

A container that sizes its own storage needs three things, and sysl's design notes once recorded all
three as reasons this could not be a library type: `sizeof` over a type parameter, a cast to reach
the elements through it, and above all a **destructor**, since the language had exactly one and no
way to write another.

All three were the wrong answer, and none of the three is an absence now — a
[destructor](/reference/memory/) is writable. **A container does not need one if its storage is a
value that already has one** — the `[]T` field is an ARC-owned buffer, so when the last name for a
`Buf` goes, its storage goes with it, and nothing in the container has to say so. The one thing genuinely missing was the
ability to ask for storage at a length worked out while running, and once a `[]T` could be sized that
way, `Buf[T]` was a hundred lines with no unsafe primitive in them.

A second apparent blocker dissolved the same way. A generic container cannot make its own storage,
because an array is built by *repeating a value* and no bound promises that `T` has one. But **a
`push` arrives holding one** — the value being pushed seeds the new storage, and the question never
comes up.

```sysl
struct Buf[T]
    elems -> []T

    len(self) -> usize
    cap(self) -> usize
    is_empty(self) -> bool

    at(self, i: usize) -> T
    set(self, i: usize, v: T)

    push(self, v: T)
    extend(self, xs: []const T)
    pop(self) -> Option[T]

    insert(self, i: usize, v: T)
    remove(self, i: usize) -> T
    truncate(self, n: usize)
    clear(self)

    copy(self) -> Buf[T]
    view(self) -> []T
```

It is generic over anything, including counted types — a `Buf[string]` retains and releases its
elements like any other slice of them:

```sysl
import sysl.buf.{Buf, buf}

var names: Buf[string] = buf()

names.push("ada")
names.push("grace")

print(names.len(), names[1])
```

```output
2 grace
```

## Bounds: a trap here, an `Option` there

`at` and `set` **trap** on an index past the end, exactly as an array's subscript does. `pop` returns
an `Option`. That looks like two minds about the same question and is not:

> An index past the end is a **mistake in the program**, not a value it meant to handle. Taking from
> an empty sequence is a question a caller asks **on purpose**.

A `pop` that returned `T` and trapped would make every drain loop write a length test it could have
got from the answer.

**The trap is an array's trap**: the program stops with the trap instruction's status (133 under a
shell), having written the array's report to standard error through
[`sysl_bounds_fail`](/reference/errors/#what-stopping-looks-like) — so a program's own override of
that function hears about a `Buf` too, and a [`should_trap`](/reference/attributes/#a-test-passes-by-returning)
test recognises it. The one difference is the location: an array's check is laid down at the
subscript, so it names that line; a `Buf`'s runs inside the member, which cannot see who called it,
and the report ends at the values:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)
print(b[0])
print(b[3])
```

```output trap
1
```

and standard error says `index 3 out of bounds for length 1`.

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)
b.push(2)
b.push(3)

print(b.pop().unwrap())
print(b.remove(0))
print(b.view()[0], b.len())

b.truncate(0)

print(b.len(), b.cap(), b.is_empty())

var e: Buf[int] = buf()

print(e.pop().is_none())
```

```output
3
1
2 1
0 8 true
true
```

**`truncate`, `clear` and `remove` are one operation seen three ways.** `truncate(n)` lowers the
count, and does nothing where `n` is a length the buffer does not have — a length past the end names
no element, so unlike an index there is nothing for it to read and nothing to stop the program about.
`clear()` is `truncate(0)`. `remove(i)` shifts the survivors down over element `i`, hands that element
back, and ends at `truncate`; an `i` that names no element is `at`'s trap, for `at`'s reason.

**`insert(i, v)` is `remove`'s other half**, putting an element at `i` and moving everything from
there on up one. Inserting at `len` is a `push` and is allowed for that reason — a loop inserting at
a cursor reaches the end on its last step, and refusing there would make every caller write the case
this one already handles. Anything past `len` is a gap, which a sequence cannot represent, and traps
the way any other index past the end does, in a form of its own: `insertion index 5 is past length 3`.

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.extend([1, 3, 4])
b.insert(1, 2)
b.insert(4, 5)

print(b.view()[1], b.len())
```

```output
2 5
```

Hand-rolling it is a shift written *backwards* — a loop walking up overwrites the element it is about
to read — and that is where the off-by-one lives.

**What none of `truncate`, `clear` and `remove` does is give storage back.** The `cap()` of `8`
survives the `truncate`
above. The slots above the count hold nothing; what a removal gives up is let go of there and then.

## Subscripting goes through the checked members

```sysl
impl[T] Index[usize, T] for Buf[T]
    index(self, i: usize) -> T = self.at(i)

impl[T] IndexSet[usize, T] for Buf[T]
    index_set(self, i: usize, v: T) = self.set(i, v)
```

```sysl
impl[T] IndexPlace[usize, T] for Buf[T]
    index_place(self, i: usize) -> *T
```

**`b[i]` on a `Buf` is `*b.index_place(i)` — the element where it lives**, checked against the
count. The backing slice is longer than the count — subscripting the *storage* would happily read a
slot the buffer does not consider live, and every bounds check in the language would pass. Checking
against the count is what makes `b[i]` unable to see spare capacity, and answering an address is
what makes it a [place](/reference/memory/#places): `b[i].f` reads one field without copying the
element, so a counted field of it is neither retained nor released, and `b[i].f = v`, `b[i] += 1`
and `ref e = b[i]` write the element itself. `Index` and `IndexSet` stay, for code generic over them.

```sysl
import sysl.buf.{Buf, buf}

struct Item
    block: u64
    data: Buf[u8]

var items: Buf[Item] = buf()

items.push(Item(7, buf()))
items[0].block += 1
items[0].data.push(9)

ref it = items[0]

it.block *= 10

print(items[0].block, items[0].data.len())
```

```output
80 1
```

**The subscript takes any integer**, as an array's does: `items[i]` with `i` an `int` or an `i8`
needs no `usize(i)`, and a negative index stops the program at the subscript, as an array's does
([arrays](/reference/arrays/#a-type-with-no-elements-of-its-own-is-indexed-through-a-trait)). The
methods themselves — `at`, `set`, `insert`, `remove` — take a `usize`, as written. An index past the
count traps, as `at` does — `index 3 out of bounds for length 1`.

**A member that can move the storage is `@relocates`** — `push`, `extend`, `insert`, `pop`,
`truncate`, `clear`, `remove`, `reserve`, `set_len` and `release` — and is refused on a buffer a live
`ref` has an element of, since growing reallocates and shrinking gives the element up. A `*T` made by
`&b[i]` is held to the same rule for as long as it is still to be used:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)

ref first = b[0]

b.push(2)
first = 5
```

```error
'first' is a 'ref' standing on an element whose storage 'push' could move or give up, since it is '@relocates'
```

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)

val p = &b[0]

b.push(2)
print(*p)
```

```error
'p' is a pointer to an element whose storage 'push' could move or give up, since it is '@relocates', and it is used after this call
```

The check is local, as every `ref` check is ([memory](/reference/memory/#the-one-rule-what-may-move-underneath-it)):
a second name for the same buffer, or a function handed it, can still push while the `ref` or the
pointer is live.

**`index_place` takes `self`**: a `Buf` is a handle, so a copy of one writes the buffer the
original names, and `b[i] = x` works on a `val` as `b(i) = x` does on a Scala `val ArrayBuffer`:

```sysl
import sysl.buf.{Buf, buf}

val b: Buf[int] = buf()
val c = b

b.push(1)
b[0] = 5

print(c[0])
```

```output
5
```

Two other shapes a first program tries. `len` is a **method**, unlike `StrBuilder`'s property:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

print(b.len)
```

```error
'len' is a method of 'Buf[int]' — call it with 'len(…)'
```

A `for` walks a `Buf` directly. A `Buf` is not a cursor — it implements `Index`, not `Iterate` — but
it implements [`Walk`](/library/core/), whose `walk()` answers `view()`, and a `for` over a type
implementing `Walk` walks what `walk()` answers. So the loop reads *the live prefix at the moment it
started*, by index as a slice's elements are read, and an element pushed during the walk is not
visited:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)
b.push(2)

for x in b
    b.push(x * 10)

print(b)
```

```output
[1, 2, 10, 20]
```

A type that implements neither is refused, and the refusal looks for a member of the receiver whose
answer a `for` **does** walk, so a type of your own that hands out a view or a cursor is told to use
it. It offers only a member you could have called — no arguments, and visible from where you are.

**What a buffer answers without being walked at all is [`sysl.seq`](/library/seq/)** — `map`,
`filter`, `fold` and the seven questions beside them, implemented for a `Buf` as well as for a slice,
so `b.map(f)` needs no `.view()` at the call site.

## `view` is the bulk read, and it is a view

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)
b.push(2)

var v = b.view()

print(v.len, b.cap())

b.push(3)
b.push(4)
b.push(5)
b.push(6)
b.push(7)
b.push(8)
b.push(9)

print(v.len, v[0], v[1])
print(b.len(), b.cap())
```

```output
2 8
2 1 2
9 16
```

The seventh push overflowed a capacity of 8, so the elements moved to a new buffer of 16. **`v` is
unchanged and still valid** — that is the whole of what this shows.

It does not dangle, because the storage it was made from is an ARC buffer like any other and the view
keeps it alive. Go's version of this is the famous confusion: two slices that agree until one of them
grows, and afterwards agree about nothing. Here the guarantee is stronger and simpler — a program
with no `*T` in it cannot fault, so the old storage stays until the last view of it goes.

What `v` does *not* do is grow with the buffer. It is a view of some elements and it has the length it
was made with. Take it again to see more.

## It compares and renders as the sequence it holds

A `Buf[T]` is the growable form of a `[]T`, so it answers `==` and `print` the way one does — both
by delegating to `view()`, which is the elements it actually has:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()
var c: Buf[int] = buf()

for i in 1..3
    b.push(i)
    c.push(i)

for i in 0..<9 do c.push(i)

c.truncate(3)

print(b)
print(b == c, b.cap() == c.cap())
print(f"[${b}%13s]")
```

```output
[1, 2, 3]
true false
[    [1, 2, 3]]
```

**The spare capacity is not part of the value**, which is what the second line is there to show: `c`
grew to sixteen slots and was shortened back to three, so it holds the same three elements as `b` and
a different amount of storage. `==` sees the first and not the second.

`Eq` asks the element type for its own `==` and `Display` asks it for its own rendering, so a buffer
of a type you wrote works the moment that type does — the two blocks are
[the slice's](/library/core/#a-slice-or-an-array-of-anything-equatable), reached one delegation away,
which is also why a width pads the whole rendering rather than each element.

**A `&Buf` compares as the buffer does.** A reference
[compares through its referent](/reference/memory/#references-compare-through-their-referent)
wherever the referent has an `Eq`, so `==` between two of them compares the elements; `===` asks
whether they are the same buffer. `print` on one is refused, and the deref is the way to the value:

```sysl
import sysl.buf.{Buf, buf}

var b: &Buf[int] = buf()
var c: &Buf[int] = buf()

for i in 1..3
    b.push(i)
    c.push(i)

print(b == c, b === c, *b == *c)
print(*b)
```

```output
true false true
[1, 2, 3]
```

That is not a rule about buffers — it is true of every `&T` whose referent is `Eq`. A `&Buf` is
seldom worth writing now, a `Buf` being shared without one (below), but it still means what it says.

## How a push is seen follows from how the `Buf` is held

It does not, any more — **a `Buf` is a reference, so every way of holding one holds the same buffer:**

```sysl
import sysl.buf.{Buf, buf}

var p: &Buf[int] = buf()
var q = p
var c = *p

p.push(1)

print(q.len(), c.len())
```

```output
1 1
```

`q` is a second name for the box, and `c` — the struct `*p` reads out of it — is a second name for
the buffer, because what a `Buf` holds is a counted reference to its storage and count rather than
the two themselves. The same is true without a `&` anywhere: assigning a `Buf`, passing it by value,
capturing it in a closure, keeping it in a struct or reading it back out of another container hands
on the buffer, never a copy of it.

```sysl
import sysl.buf.{Buf, buf}

var outer: Buf[Buf[int]] = buf()

outer.push(buf())
outer.at(0).push(1)

print(outer.at(0).len())
```

```output
1
```

That is what a growable collection is in most languages a program has met — Scala's `ArrayBuffer`,
Java's `ArrayList`, a Python list — and it is what a program reaching for one expects: a function that
fills the buffer it was handed fills the caller's.

**So every member that changes a buffer takes it by value**, a copy of the handle being the same
buffer, and **a `val` buffer is pushed into like any other.** What `val` promises is about the name: it
always refers to the same buffer, which is exactly what a parameter or a field holding one wants to
say. A program that wants a *second* buffer asks for one with `copy()`:

```sysl
import sysl.buf.{Buf, buf}

fill(b: Buf[int]) = b.push(9)

val a: Buf[int] = buf()

fill(a)

val b = a.copy()

b.push(10)

print(a.len(), b.len())
```

```output
1 2
```

**A `Buf` has no zero value, because a buffer is a box and a box is made by somebody.** A declaration
has to say which buffer it starts as:

```sysl
import sysl.buf.Buf

var b: Buf[int]
```

```error
Buf[int] has no zero value, so 'b' needs an initial value
```

### In storage the program zeroed itself, a `Buf` is empty until it is assigned

The refusal is about a *declaration*. A program can still lay a `Buf` in memory the language did not
initialise — a `calloc`ed block, a garbage collector's arena, a struct of an interpreter's objects
reached through a `*T` — and there the handle names no buffer at all. **That handle is an empty
buffer**: `len()` is 0, `view()` is empty, `for` visits nothing, `at` reports the index past the end,
and assigning into it gives back nothing, so `p.items = buf()` is how it gets storage. What it cannot
do is grow where it stands: `push`, `extend` and `insert` take the handle by value and have nowhere to
put a buffer they made, so they panic, naming the fix, rather than adding to nothing.

**`release()` is the way back to that state without allocating**, and it is what a finalizer for
such storage calls: it gives back this name's share — the elements go when the last name does — and
leaves the handle naming nothing. Assigning `buf()` there instead would give the old buffer back too,
but make a fresh one that is lost the moment the storage is freed.

```sysl
import sysl.buf.{Buf, buf}
import sysl.raw.ptr_cast

extern "calloc" c_calloc(n: usize, size: usize) -> *u8
extern "free" c_free(p: *u8)

struct Obj
    items: Buf[int]

val o: *Obj = ptr_cast(c_calloc(1, sizeof(Obj)))

print(o.items.len(), o.items.view())

o.items = buf()
o.items.push(7)
print(o.items.count, o.items)

o.items.release()
print(o.items.len())

c_free(ptr_cast(o))
```

```output
0 []
1 [7]
0
```

`Map`, `Deque`, `Set` and `Heap` behave the same way and each has its own `release()`
([containers](/library/container/)). A plain `&T` field in such storage is emptied the same way by
the built-in `release(o.field)`
([memory](/reference/memory/#storage-the-compiler-did-not-lay-out-may-not-hold-a-counted-value)).

## Capacity, and what it costs

```sysl
import sysl.buf.{Buf, buf_with_capacity}

var w: Buf[int] = buf_with_capacity(100, 0)

print(w.len(), w.cap())

w.push(7)

print(w.len(), w.cap(), w[0])
```

```output
0 100
1 100 7
```

**Growth is geometric — a full buffer doubles — and `buf_with_capacity` skips the
reallocate-and-copy at each doubling on the way up to `n`.** It is a guess and nothing depends on it:
too small and the buffer grows the way it always does, too large and the slack goes with the rest.
`extend` sizes the same way rather than fitting exactly, so a loop of `extend` calls stays amortized
constant per element instead of turning quadratic the way `+=` on a string does.

**The fill is a parameter, and that is `T` having no zero.** An array is made by repeating a value,
and nothing about a type parameter says what an unused slot should hold. Leaving it out is an
ordinary arity error:

```sysl
import sysl.buf.{Buf, buf_with_capacity}

var c: Buf[int] = buf_with_capacity(8)
```

```error
function 'sysl.buf.buf_with_capacity' takes 2 arguments, but 1 argument was given
```

**The fill is not stored anywhere.** The spare slots come from
[`slot_storage`](/reference/memory/#slot-storage-room-that-holds-no-value-yet) and hold no value at
all, so a `Buf[&T]` given a capacity of 1024 keeps nothing alive but the elements it was pushed.

### A buffer filled to a length

`buf_filled(n, value)` is a buffer that already holds `n` elements, every one of them `value` — what
a RAM disk or a frame buffer starts as, and what `vec![0; n]` is in Rust:

```sysl
import sysl.buf.{Buf, buf_filled}

val disk: Buf[u8] = buf_filled(4096, 0)

print(disk.len(), disk[0], disk[4095])

disk[512] = 0xEB

print(disk[512], disk[513])
```

```output
4096 0 0
235 0
```

It is one allocation sized to `n` and `n` stores, not a `push` loop's doublings. **Every element is a
copy of `value`, the way any assignment copies**, so there is no bound to write: a plain value is
repeated, and a counted one is *shared* — the elements name one object and hold one count each:

```sysl
import sysl.buf.{Buf, buf_filled}

struct Cell
    n: int

val cells: Buf[&Cell] = buf_filled(3, Cell(0))

cells[0].n = 7

print(cells[0].n, cells[1].n, cells[2].n)
```

```output
7 7 7
```

A buffer of separate objects is a loop that makes one per element.

### Filling the spare room

A buffer can be filled by something other than `push` — a C function handed an address, a
`read(fd, p, n)`, a decoder writing into a `[]u8` — and then told how much of its room now holds
elements, with no second copy and no fill pass first:

- **`reserve(more)`** grows the storage once, the way a push grows it, until `cap()` is at least
  `len() + more`. It is on every `Buf`.
- **`spare()`** is the room past the length, a `[]T` of `cap() - len()` slots — a view of the
  storage, so valid until the buffer next grows.
- **`set_len(n)`** moves the length to `n`, taking the slots in between as the elements they already
  hold. Below `len()` it is `truncate(n)`; past `cap()` it panics
  (`panic: length 17 past the 16 slots a Buf has room for`).

```sysl
import sysl.buf.{Buf, buf}

extern memcpy(dst: *u8, src: *u8, n: usize) -> *u8

// Stands in for read(fd, p, room): C writes up to `room` bytes at `p` and says how many it wrote.
c_read(p: *u8, room: usize) -> usize
    var src: [5]u8 = [104, 101, 108, 108, 111]
    val n = if room < 5 then room else 5

    memcpy(p, &src[0], n)
    n

var b: Buf[u8] = buf()

b.push(62)
b.reserve(64)

val got = c_read(&b.spare()[0], b.spare().len)

b.set_len(b.len() + got)

print(b.len(), b)
```

```output
6 [62, 104, 101, 108, 108, 111]
```

**`set_len` writes nothing.** A slot it takes in holds what was last stored there: the bytes the
writer put in, the value a `truncate` gave up, or — for a slot nobody ever wrote — whatever the
allocator left, since the spare room comes from
[`slot_storage`](/reference/memory/#slot-storage-room-that-holds-no-value-yet) and is never cleared.
Taking in a slot nobody wrote is not a crash, but its value is unspecified; write every slot you
claim.

**That is why only a buffer of an [`AnyBits`](/library/core/#anybits-storage-whose-every-bit-pattern-is-a-value)
type has `spare` and `set_len`.** For such a type every bit pattern is *some* value, so the worst a
stale slot holds is a wrong number. A `bool`, an enum, a `char`, a string or a `&T` has bit patterns
that are no value at all, and a buffer of one is refused the call where it is written:

```sysl
import sysl.buf.{Buf, buf}

var flags: Buf[bool] = buf()

flags.set_len(0)
```

```error
'sysl.buf.Buf.set_len' requires its type parameter 'T' to implement 'AnyBits', but bool does not — storage nobody wrote would not hold a valid value of it: a 'bool' is 0 or 1, and its byte can hold 254 numbers that are neither
```

A buffer of such a type is filled with `push` or `extend`, or with `buf_filled` and then written in
place.

## It needs an allocator, and says so

```sysl
@no_alloc

import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)

print(b.len())
```

```error
this reaches 'sysl.buf.buf.int', which makes heap storage, and this module declared '@no_alloc'
```

**The refusal names `buf()`, and that is the box.** A `Buf` is a reference, so making one makes the
counted box every name for it will share — an allocation before a single element is pushed. [`alloc` is
checked on what a module *calls*](/reference/modules/), at the smallest expression that still reaches
an allocator, so it falls on the first thing that really makes storage: here the constructor, and in
a module that was handed its buffer, the `grow` that `push` and `extend` share, kept out of line so a
hot `push` stays a compare, a store and a bump.

There is still no allocator-free `Buf` and there cannot be one: growing is the whole of what it does.
What an allocator-free module may now do is *hold* an empty one — which is what a function answering
"no results" wants, and what it could not do before.

## `ByteSink`

```sysl
struct ByteSink
    bytes: &Buf[u8]

    text(self) -> []u8

impl Fallible for ByteSink

impl Writer for ByteSink
    write(*self, bytes: []const u8) = self.bytes.extend(bytes)
```

That is the entire type. It is **one of the three `Writer`s the library supplies** — the others are
[`Stdout`](/reference/declarations/), which stands for standard output, holds no state at all, and
is therefore a struct with no fields, and `Counting`, which keeps the *length* of what it is handed
and throws the bytes away. The buffer `str` and an `f"…"` hole render into is still the
compiler's, since a growable byte array is not something it can name at the layer it needs one.

`impl Fallible for ByteSink` has no block, and does not need one: every member of `Fallible` has a
default, so implementing the latch on a buffer that has nothing to fail at is entirely a matter of
opting in.

```sysl
import sysl.buf.byte_sink

var sink = byte_sink()
var out: *Writer = &sink

display_int(42, out, FormatSpec(6, -1, false))
out.write("|".bytes)
display_str("ok", out, FormatSpec(0, -1, false))

putbytes(sink.text())
prints("\n")
print(sink.failed(), sink.text().len)
```

```output
    42|ok
false 9
```

### Why it is in the library rather than in each program

Because a rendering sometimes has to end up somewhere that is not a stream — handed back as a
`string`, compared against another, kept. Writing a growable byte array per program to do that is
writing `Buf[u8]` again.

**It is not because a multi-part `Display` needs one.** A format specifier describes the field the
*whole value* occupies, so a rendering of `1`, `+`, `2`, `i` has to pad what those four came to
rather than each of them — but padding needs their *width*, which `Counting` answers without keeping
a byte, and that is what [`sysl`'s own multi-part renderings](/library/core/) do. Gathering is the
shorter way to write it and costs an allocation the other does not:

```sysl
import sysl.buf.byte_sink

struct Complex
    re: int
    im: int

impl Display for Complex
    display(self, out: *Writer, fmt: FormatSpec)
        var sink = byte_sink()
        var gather: *Writer = &sink

        display_int(long(self.re), gather, FormatSpec(0, -1, false))
        gather.write("+".bytes)
        display_int(long(self.im), gather, FormatSpec(0, -1, false))
        gather.write("i".bytes)

        display_pad(sink.text(), out, fmt)
    end display

print(Complex(1, 2))
print(f"[${Complex(1, 2)}%8s]")
print(f"[${Complex(1, 2)}%-8s]")
```

```output
1+2i
[    1+2i]
[1+2i    ]
```

**Note the two specs.** The inner `FormatSpec(0, -1, false)` is neutral — the parts are rendered
plainly — and only `display_pad` at the end sees `fmt`. An implementation that forwarded `fmt` down
to each part would pad the `1` to eight columns and then the `2`, which is not what `%8s` on a
complex number meant.

**The `Complex` above is this page's own, and the library's is not written this way** —
[`sysl.math.complex`](/library/complex/) measures with `Counting` and allocates nothing, which is why
an allocator-free program can print one. The struct here is the gathering shape, kept because it is
the shorter one and because it is what a first `Display` looks like.

What a program still writes for itself is an ordinary `impl Writer for MyThing` — a device, a bounded
buffer that latches, a checksum — and that remains the case the trait exists for.

### A `Writer` may not keep what it is written

`write` takes a `[]const u8` that may be a view of the caller's **stack** — that is exactly what the
`display_*` renderers hand it, and it is why they cost no allocation. Nothing in the type says the
bytes are borrowed, so it is *checked*: escape analysis rejects an implementation whose `write` lets
its parameter outlive the call. `ByteSink` copies them into its `Buf`, which is what `extend` is.

That check is what licenses a renderer to pass a stack-backed slice through a trait object at all.

## Type errors read as they should

The generic is monomorphized, so a mismatch names the instantiation rather than the parameter:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push("x")
```

```error
'v' of 'sysl.buf.Buf.push.int' is int, but string was given
```

`sysl.buf.Buf.push.int` is the `push` of a `Buf[int]` — one function, emitted for that instantiation.
[Generics](/reference/generics/) has the rest of that story.

---

Next: [`sysl.io`](/library/io/) — reading, and the `Lines` cursor.
