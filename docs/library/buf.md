---
title: The buf module
summary: "`sysl.buf` — `Buf[T]`, the growable sequence written in ordinary sysl, and `ByteSink`, the `Writer` that keeps what is written into it."
weight: 30
---

**Every declaration in `sysl.buf`, with its signature:** [the generated API page](/api/sysl-buf/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.buf` holds two types and three functions, and the interesting thing about the larger of them is
what it is *not*: **`Buf[T]` is not a type the compiler knows.** It is a `[]T` field for the storage,
a `usize` for how much of it is live, and a dozen members — ordinary sysl, in a file a program could
have written.

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
`Iterate`, and neither of those is a growable sequence — so a program that wants one asks, and the
`import` is where it asked. That is the [core module's](/library/core/) rule applied from the other
side: what a program cannot avoid needing arrives free, and what it has to ask for it asks for.

## Why it can be written at all

A container that sizes its own storage needs three things, and sysl's design notes once recorded all
three as reasons this could not be a library type: `sizeof` over a type parameter, a cast to reach
the elements through it, and above all a **destructor**, since the language had exactly one and no
way to write another.

All three were the wrong answer, and none of the three is an absence now — a
[destructor](/reference/memory/) is writable. **A container does not need one if its storage is a
value that already has one** — the `[]T` field is an ARC-owned buffer, so when a `Buf` goes, its storage
goes with it, and nothing in the container has to say so. The one thing genuinely missing was the
ability to ask for storage at a length worked out while running, and once a `[]T` could be sized that
way, `Buf[T]` was a hundred lines with no unsafe primitive in them.

A second apparent blocker dissolved the same way. A generic container cannot make its own storage,
because an array is built by *repeating a value* and no bound promises that `T` has one. But **a
`push` arrives holding one** — the value being pushed seeds the new storage, and the question never
comes up.

```sysl
struct Buf[T]
    elems: []T
    count: usize

    len(self) -> usize
    cap(self) -> usize
    is_empty(self) -> bool

    at(self, i: usize) -> T
    set(*self, i: usize, v: T)

    push(*self, v: T)
    extend(*self, xs: []const T)
    pop(*self) -> Option[T]

    insert(*self, i: usize, v: T)
    remove(*self, i: usize) -> T
    truncate(*self, n: usize)
    clear(*self)

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

## Bounds: a panic here, an `Option` there

`at` and `set` **panic** on an index past the end. `pop` returns an `Option`. That looks like two
minds about the same question and is not:

> An index past the end is a **mistake in the program**, not a value it meant to handle. Taking from
> an empty sequence is a question a caller asks **on purpose**.

It is the same bargain [`unwrap`](/reference/errors/) makes. A `pop` that returned `T` and panicked
would make every drain loop write a length test it could have got from the answer.

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
back, and ends at `truncate`; an `i` that names no element is `at`'s panic, for `at`'s reason.

**`insert(i, v)` is `remove`'s other half**, putting an element at `i` and moving everything from
there on up one. Inserting at `len` is a `push` and is allowed for that reason — a loop inserting at
a cursor reaches the end on its last step, and refusing there would make every caller write the case
this one already handles. Anything past `len` is a gap, which a sequence cannot represent, and panics
the way any other index past the end does.

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
above. The elements above the count are still values in a `[]T` that ARC owns — which is also why a
**copy** of a `Buf` taken before a removal reads the shifted elements at the length it was copied at.

## Subscripting goes through the checked members

```sysl
impl[T] Index[usize, T] for Buf[T]
    index(self, i: usize) -> T = self.at(i)

impl[T] IndexSet[usize, T] for Buf[T]
    index_set(*self, i: usize, v: T) = self.set(i, v)
```

`b[i]` on a `Buf` means `b.at(i)`, and that is worth more than the syntax. The backing slice is
longer than the count — subscripting the *storage* would happily read a slot the buffer does not
consider live, and every bounds check in the language would pass. Routing `[]` through `at` is what
makes `b[i]` unable to see spare capacity.

The cost is that reading through [`Index`](/library/core/) is a **call**, so it yields a value rather
than a place:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)
b[0] += 1
```

```error
'+=' on an element read through 'sysl.Index' would evaluate the receiver and the index twice
```

`b[0] = b[0] + 1` is the spelling, and writing it out is the point: the expansion the compiler
declines to make would evaluate `b` twice and the index twice, which for a receiver that is a call,
or an index that advances a cursor, is wrong rather than merely wasteful.

Two other shapes a first program tries. `len` is a **method**, unlike `StrBuilder`'s property:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

print(b.len)
```

```error
'len' is a method of 'sysl.buf.Buf[int]' — call it with 'len(…)'
```

And a `Buf` is not itself iterable — it implements `Index`, not `Iterate`:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)

for x in b
    print(x)
```

```error
'for' iterates an integer range, an array, a slice, or a type that implements 'sysl.Iterate', and sysl.buf.Buf[int] is none of those — what it does have is 'view()', which answers with something a 'for' walks, so 'for x in b.view()' is the loop
```

`for x in b.view()` is how it is walked, and that is not a workaround — it names the thing being
iterated, which is *the live prefix at the moment the loop started*:

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)
b.push(2)

for x in b.view()
    print(x)
```

```output
1
2
```

The refusal says so itself: it looks for a member of the receiver whose answer a `for` **does** walk,
so a container that hands out a cursor from [`walk()`](/library/container/) is told to use that
instead. It offers only a member you could have called — no arguments, and visible from where you
are.

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

**A `&Buf` is a different question and answers a different one.** References
[compare by address](/reference/memory/#t-counted-references) and a `&T` implements nothing its referent implements, so
`==` between two of them asks whether they are the same buffer, and `print` on one is refused. The
deref is the way to the value:

```sysl
import sysl.buf.{Buf, buf}

var b: &Buf[int] = buf()
var c: &Buf[int] = buf()

for i in 1..3
    b.push(i)
    c.push(i)

print(b == c, *b == *c)
print(*b)
```

```output
false true
[1, 2, 3]
```

That is not a rule about buffers — it is true of every `&T` whose referent is `Eq` — but a `Buf` is
usually held by reference, so it is where the question comes up.

## How a push is seen follows from how the `Buf` is held

sysl does not have to choose here, and that is the point:

```sysl
import sysl.buf.{Buf, buf}

var p: &Buf[int] = buf()
var q = p
var c = *p

p.push(1)

print(q.len(), c.len())
```

```output
1 0
```

`q` is a second name for one buffer, so it sees the push. `c` is a copy, because copying a struct is
what `*p` means. Neither is a rule about growable sequences — both are the [memory
modes](/reference/memory/) doing exactly what they do for any struct, and the author wrote which one
they wanted.

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

None of those slots is ever read — `count` starts at zero, so every one of them is written before
anything can see it. But they are **real values**, and that is the honest cost of a growable sequence
in this language: there is no way to have storage that is merely reserved. A `Buf[&T]` grown to a
capacity of 1024 while holding one element is holding 1024 references to whatever seeded the growth,
and that object stays alive until the slots are overwritten. Capacity that is not yet values is a
known gap, not a solved problem.

## It needs an allocator, and says so

```sysl
@no_alloc

import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)

print(b.len())
```

```error
this reaches 'sysl.buf.Buf.grow.int', which makes heap storage, and this module declared '@no_alloc'
```

**`grow` is named and `buf()` is not, and the difference is worth reading.** `push`'s own growth path
— the allocation, the copy, and the release of the storage it replaced — is out of line in a `grow`
it shares with `extend`, kept there so a hot `push` stays a compare, a store and a bump. [`alloc` is
checked on what a module *calls*](/reference/modules/), at the smallest expression that still reaches
an allocator, so the refusal names `grow` rather than `push` — and **an empty `Buf` reaches neither**.
`buf()` is `Buf([], 0)`, and an empty view is `{null, null, 0}`: the zero value of what a view is made
of, with no elements, no length and nobody owning them. So building one costs nothing and the refusal
falls where the storage is really made.

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
'v' of 'Buf.push.int' is int, but string was given
```

`Buf.push.int` is the `push` of a `Buf[int]` — one function, emitted for that instantiation.
[Generics](/reference/generics/) has the rest of that story.

---

Next: [`sysl.io`](/library/io/) — reading, and the `Lines` cursor.
