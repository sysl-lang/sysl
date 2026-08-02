---
title: The buf module
summary: "`sysl.buf` — `Buf[T]`, the growable sequence written in ordinary sysl, and `ByteSink`, the one `Writer` the library supplies."
weight: 30
---

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
the elements through it, and above all a **destructor**, since the language has exactly one and no
way to write another.

All three were the wrong answer. **A container does not need a destructor if its storage is a value
that already has one** — the `[]T` field is an ARC-owned buffer, so when a `Buf` goes, its storage
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

b.truncate(0usize)

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

**What none of the three does is give storage back.** The `cap()` of `8` survives the `truncate`
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
'for' iterates an integer range, an array, a slice, or a type that implements 'sysl.Iterate', and sysl.buf.Buf[int] is none of those
```

`for x in b.view()` is how it is walked, and that is not a workaround — it names the thing being
iterated, which is *the live prefix at the moment the loop started*.

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

var w: Buf[int] = buf_with_capacity(100usize, 0)

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

var c: Buf[int] = buf_with_capacity(8usize)
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
no alloc

import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(1)

print(b.len())
```

```error
this reaches 'sysl.buf.buf.int', which makes heap storage, and this module declared 'no alloc'
```

Both `buf()` and the `push` are named, because [`alloc` is checked on what a module
*calls*](/reference/modules/). There is no allocator-free `Buf` and there cannot be one: growing is
the whole of what it does.

## `ByteSink`

```sysl
struct ByteSink
    bytes: &Buf[u8]

    text(self) -> []u8

impl Fallible for ByteSink

impl Writer for ByteSink
    write(*self, bytes: []const u8) = self.bytes.extend(bytes)
```

That is the entire type. It is **the one `Writer` the library supplies** — the two that `print` and
`str` use are the compiler's, and the standard-output one has to be, since it holds no state and
there is no struct with no fields to give it.

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

Because **an implementation that renders more than one part cannot honour its specifier without
one.** A format specifier describes the field the *whole value* occupies, so a rendering of `1`, `+`,
`2`, `i` has to pad what those four came to rather than each of them; padding needs the finished
bytes; and the finished bytes need somewhere to land.

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

Every such implementation would write the same dozen lines, which is the definition of something that
belongs in the library. What a program still writes for itself is an ordinary `impl Writer for
MyThing` — a counter, a device, a bounded buffer that latches — and that remains the case the trait
exists for.

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
'v' of 'sysl.buf$Buf.push.int' is int, but string was given
```

`sysl.buf$Buf.push.int` is the `push` of a `Buf[int]` — one function, emitted for that instantiation.
[Generics](/reference/generics/) has the rest of that story.

---

Next: [`sysl.io`](/library/io/) — reading, and the `Lines` cursor.
