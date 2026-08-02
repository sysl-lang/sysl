---
title: Arrays and slices
summary: Two sequence types — storage and a view of it — and the rules for writing one down, indexing it, slicing it, and knowing what it keeps alive.
weight: 75
---

sysl has two sequence types where many languages have one:

| type | what it is | shape |
|---|---|---|
| `[N]T` | a **fixed array**: `N` elements of `T`, a value, no header | the elements, and nothing else |
| `[]T` | a **slice**: a view of elements someone owns | `{ owner, ptr, len }` — three words |
| `[]const T` | a slice that may be read and not written | the same three words, with a bit |

The split is Go's, and for Go's reason: one type for storage, one for a view, so a function that only
reads takes the view and never has to care where the bytes live. Both carry a length, so **every
index is checked** — which is what makes them the thing to reach for in place of a `*T`.

An array **is** its elements. A slice **names** elements that live somewhere else. Everything on
this page follows from that one sentence, and a call is where it shows most plainly:

```sysl
scribble(a: [4]int) -> int
    a[0] = 99
    a[0]

set_first(xs: []int)
    xs[0] = 99

var a = [1, 2, 3, 4]

print(scribble(a), a[0])

set_first(a[..])

print(a[0])
```

```output
99 1
99
```

`scribble` was handed four integers and wrote on its own copy of them. `set_first` was handed three
words naming the caller's four integers, and wrote on those. Neither function said anything about
copying; the types did.

A `string` is the second row of that table with one thing added and one taken away — its bytes are
guaranteed well-formed UTF-8, and nothing may write through it. Everything here about indexing,
slicing, length and ownership is therefore true of a string as well, and
[strings](/reference/strings/) is where the two differences are.

## Writing one down

**A literal** lists its elements, and the count becomes part of the type:

```sysl
var primes = [2, 3, 5, 7]
var row: [3]f64 = [1.0, 0.0, 0.0]

print(primes.len, primes[2], row[0])
```

```output
4 5 1
```

**A declaration with no initializer** starts at the type's zero value. **A repeat** `[value; count]`
fills every element with one value, and is the form for an element type whose zero is not the wanted
starting point — or which has no zero at all:

```sysl
const n: usize = 6

var counters: [4]int
var ones = [1; 5]
var grid = [[0; 3]; 3]
var window = [0u8; n]

grid[1][2] = 5

print(counters[0], ones[4], grid[1][2], grid[0][0], window.len)
```

```output
0 1 5 0 6
```

Three rules govern the repeat.

**The count is a compile-time constant** — a literal, a `const` naming one, or an expression over
those — for the same reason an array bound is one: it *is* the bound, and the type is not known
without it.

```sysl
var k: usize = 4
var buf: [4]u8 = [0; k]
```

```error
an array's repeat count must be a constant, since it is the array's bound
```

The diagnostic goes on to name the way out, and it is the section below: a count computed while
running makes *storage* rather than an array, and storage is written where a `[]T` is expected.

**The element type comes from the value**, or from the context where the value is a bare literal.
And **the value is evaluated exactly once**, then copied into every element:

```sysl
struct Counter
    n: int
end Counter

bump(c: *Counter) -> int
    c.n = c.n + 1
    7

var c = Counter(0)
var three = [bump(&c); 3]

print(three[0], three[1], three[2], c.n)
```

```output
7 7 7 1
```

One call, three elements. That is what makes the form a construction rather than shorthand for
writing the value out `count` times — and where the element type holds a reference, each of those
copies is a share of its own.

**An empty literal** has no element type of its own and takes one from the context, exactly as a
bare `None` does:

```sysl
var empty: [0]int = []

print(empty.len)
```

```output
0
```

### The zero-value form is not about arrays

`var counters: [4]int` is a `var` with a declared type and no `=`, and it means "the zero value of
this type" for **any** type that has one. What matters here is which types do not: a type containing
a `&T` has no zero value, because a reference always points at something live.

```sysl
struct Node
    next: &Node
end Node

var n: Node
```

```error
Node has no zero value, so 'n' needs an initial value
```

That keeps the non-null guarantee of [memory](/reference/memory/) true with no special case for
arrays, and it is why `[Empty; 16]` is the form an array of enum values takes.

### A table written at the top of a file

The same three forms written outside a function are a `val`, which is what a table somebody else
fixed wants:

```sysl
val order: [5]int = [16, 17, 18, 0, 8]

total(xs: []const int) -> int
    var t = 0

    for x in xs do t += x

    t

print(order[2], total(order[1..<3]), order.len)
```

```output
18 35 5
```

The difference from a `const` is an **address**. A constant is folded into every use and has none, so
it can size an array but cannot *be* one; a `val` is storage, so it may be indexed at a position only
known while running, sliced, and iterated. What it may not be is written, and slicing one therefore
yields a `[]const T` — which is the whole of why a `val` is sliceable at all.

**A `val` may not hold a slice, and the reason is the count.** Storage that exists for the whole run
is never let go of, so anything in it that owes a release has nowhere to write one:

```sysl
squares(k: usize) -> []int
    var xs: []int = [0; k]

    for i in 0..<k do xs[i] = int(i) * int(i)

    xs

val table: []int = squares(4)
```

```error
'table' cannot be a 'val': its type is []int, and storage that exists for the whole run is never let go of — a reference, a weak reference, a slice, or a string in one would be a count with nowhere to write the release. A raw pointer may be held: it counts nothing
```

The diagnostic names the whole family — a reference, a weak reference, a slice, or a string — and
the one exception: a raw pointer may be held, because it counts nothing. An array of plain values,
like `order` above, is storage all the way down and is exactly what the form is for.

## Storage sized while running

Every form so far fixes its length in the type, and that is the one thing a program reading a file
cannot do: the size is in the header, and the header is read by the code that needs the buffer.

A length that is not in the type is precisely what a `[]T` is, so nothing here needs a new spelling.
**The expected type decides** which reading a literal or a repeat gets:

```sysl
decode(header: []const u8) -> []u8
    var out: []u8 = [0u8; usize(header[0])]

    for i in 0..<out.len do out[i] = header[1] + u8(i)

    out

var frame = [3u8, 10u8]
var body = decode(frame[..])

print(body.len, body[0], body[2])
```

```output
3 10 12
```

Under a `[N]T` a non-constant count is the error above; under a `[]T` a constant one is simply the
easy case. The three spellings side by side:

```
var buf: [64]u8 = [0; 64]     // an array — the count is in the type, so it is constant
var raw: []u8   = [0; n]      // a view of fresh storage — n is any expression
var xs:  []int  = [1, 2, 3]   // likewise, with the elements written out
```

**The storage is the view's own.** The `owner` word is an ordinary counted reference to the elements,
so everything under [ownership](#what-a-view-keeps-alive) is already true of it: a sub-slice retains
it, the last view to go releases it, and the elements are destroyed before the bytes go back. Nothing
about indexing, slicing, `.len` or iterating tells such a view apart from one over somebody else's
elements — which is the point, and why a second type would have been a second type for nothing.

What it adds is **leaving the frame**, which is what the program above does: a decoder that learns
its size from what it is decoding returns a result, instead of asking its caller to size a buffer
whose size is in a header the caller has not read.

**Three things are checked**, because a computed length is where arithmetic goes wrong. The count is
widened to 64 bits and read unsigned, so a negative one arrives as a very large one; the byte size is
computed with an overflow-checked multiply and add, so a count that would wrap cannot allocate a
small buffer that is then written past; and a failed allocation traps rather than handing back a null
the elements are then stored through.

## Indexing

`a[i]` reads the element, and it is a **place** — so `a[i] = v`, `a[i] += 1`, `a[i]++` and `&a[i]`
all follow from the same machinery assignment and address-of use everywhere else, with nothing said
about arrays in particular:

```sysl
var a = [10, 20, 30, 40]

a[0] += 5
a[1]++

var p = &a[2]

*p = 99

print(a[0], a[1], a[2])
```

```output
15 21 99
```

**The index may be any integer type.** Requiring `usize` would make `for i in 0..<10 do a[i] …` need
a conversion for no benefit, since the check has to happen anyway:

```sysl
var a = [10, 20, 30, 40]
val narrow: i8 = 1
val unsigned: u8 = 2
val sized: usize = 3

print(a[narrow], a[unsigned], a[sized])
```

```output
20 30 40
```

The index is widened to 64 bits and compared **unsigned** against the length. That is one comparison
rather than two, and it rejects a negative index as a very large one — the trick a bounds check has
always used.

### A failed check traps

```sysl
var a = [1, 2, 3]
var i: i32 = -1

print("before")
print(a[i])
```

The process stops at the index. This is the same runtime-safety category as the partial `char(u)`
conversion and a mid-character string slice, and it gets the same treatment: a trap instruction, no
message, and no unwinding. Output already written but not yet flushed goes with it — so `before`
does not appear, which is worth knowing before reading a truncated log as evidence of where a program
got to.

### A raw pointer is indexed anyway

A `*T` is the one receiver with nothing to check against, and it is subscripted regardless — that is
C's subscript, the address arithmetic, unchecked. Slicing one needs the end written, because nothing
in the type can supply it:

```sysl
total(xs: []const int) -> int
    var t = 0

    for x in xs do t += x

    t

var back = [7, 8, 9]
var p = &back[0]

print(p[1], total(p[0..<2]))
```

```output
8 15
```

The resulting view owns nothing, since a `*T` region has nothing to keep alive. **The check is a
property of the type, not of the syntax**: a `*[N]T`, whose length is in its type, keeps every check
an array has. Reaching for a slice is how a program stays safe; reaching for a pointer is how it
talks to hardware and to C, and the language supplies both rather than withholding the second.

### A type with no elements of its own is indexed through a trait

Everything above is about the built-in subscript rather than about `[]` as a token. A user type — the
library's [`Buf[T]`](/library/buf/), a lookup table, anything a program writes — implements `Index`
and is read with the same syntax:

```sysl
import sysl.buf.{Buf, buf}

var b: &Buf[int] = buf()

b.push(1)
b.push(2)

b[0] = 9

print(b[0], b.len())
```

```output
9 2
```

Two differences follow from its being a call rather than a walk to an address. The index is whatever
the implementation takes, and need not be an integer. And the element is **not a place**, so `b[i] =
v` reaches a second trait's method and the compound forms are refused outright:

```sysl
import sysl.buf.{Buf, buf}

var b: &Buf[int] = buf()

b.push(1)

b[0] += 5
```

```error
would evaluate the receiver and the index twice — write it out as 'b[i] = b[i] + …'
```

Note also that `b.len()` has parentheses where `a.len` does not: the built-in length is a property of
a type the compiler knows, and `Buf`'s is an ordinary method. Nothing a program writes competes with
the built-in subscript — an array, a slice and a string are indexed by the compiler.

## Slicing

A slice expression is an index whose subscript is a range, and the two range operators keep the
meanings they have [everywhere else](/reference/expressions/): `..` includes its high end, `..<`
excludes it.

```sysl
var d = [1, 2, 3, 4, 5]

print(d[2..4].len, d[2..<4].len, d[2..].len, d[..<2].len, d[..].len)
```

```output
3 2 3 2 5
```

The inclusive `..` is the odd one against C-family habit, and the alternative is worse:
`for i in 0..<n` and `a[0..<n]` **must** mean the same thing, and a language that has already chosen
two range operators does not get to give them different meanings in a subscript. So "the first `n`"
is `a[..<n]`, matching the loop that walks it.

Both ends are optional: an omitted low end is 0, an omitted high end is the last element. Because
"through the last" is not a question of including or excluding anything, only one spelling of the
open-ended form exists:

```sysl
var a = [1, 2, 3, 4]
var v = a[1..<]
```

```error
an open-ended slice is written 'a[lo..]'
```

**The check is on the half-open interval the slice ends up naming.** With `s` the first index and `e`
one past the last, `s <= e` and `e <= len` must both hold, and the inclusive form additionally
requires that its named high element exist. An empty slice is legal, including at the very end:

```sysl
var d = [1, 2, 3, 4, 5]

print(d[d.len..].len, d[2..2].len)
```

```output
0 1
```

**What can be sliced:** a fixed array, a slice, or an array reached through a `&[N]T` or a `*[N]T` —
one-level auto-deref applies to a subscript as it does to field selection, so the expression reads
the same whether the name is the array or a reference to it:

```sysl
total(xs: []const int) -> int
    var t = 0

    for x in xs do t += x

    t

var boxed: &[4]int = [1, 2, 3, 4]

print(total(boxed[..]), boxed[2], boxed.len)
```

```output
10 3 4
```

## `[]const T` — a view that may not be written

The `const` sits after the brackets, where `sync` sits after the `&`, and for the same reason: it is
a property of the **view**, not of the element type. It is the signature a function that only reads
should have.

```sysl
scale(xs: []const int)
    xs[0] = 99

var data = [1, 2, 3]

scale(data[..])
```

```error
which views elements it may not write, so there is nothing to assign through
```

The same diagnostic answers `xs[i] += 1` and `xs[i]++`, and finishes by naming the way out: elements
you may write are elements of your own, so copy them into a `[]T` first.

**It is one type with a bit, not two types.** Both forms are the same three words, reach through the
same instructions, and keep the same thing alive; what the bit changes is only what may be *done*
with the view. So a `[]T` is accepted wherever a `[]const T` is wanted — and never the other way
round:

```sysl
writes(xs: []int) -> usize
    xs[0] = 9
    xs.len

hand_over(xs: []const int) -> usize
    writes(xs)
```

```error
is a licence to write them — so the one does not become the other
```

Giving up the ability to write is a promise the caller can always make; inventing one is the whole of
what the type exists to stop.

**What produces one.** Slicing a `val`, since read-only storage gives a read-only view. `s.bytes`,
whose elements are a string's own and may be a literal's. Re-slicing one, because a bit a second
subscript dropped would make `xs[..]` the way around `xs`. And a buffer literal written where one is
wanted, since storage an expression makes has no other holder to disagree with it.

```sysl
readonly(xs: []const int) -> usize
    var again = xs[1..]
    again.len

first(xs: []const int) -> int
    val p = &xs[0]
    *p

var a = [1, 2, 3, 4]

print(readonly(a[..]), first(a[..]))
```

```output
3 1
```

**What it does not refuse is `&`.** `&xs[0]` is a `*T` the moment it is written, which is the tier
where the guarantees stop, and it is how a view reaches a C function taking a pointer and a length.
The library's own `find_byte` is `memchr` over exactly this. A read-only view that could not yield an
address could not do the job it was added for. This is *not* the rule for a `val` itself, where
`&k[0]` is refused: a `val` is storage whose promise is kept where it was made, and a view is a value
whose promise is about writing through it.

**`[N]const T` is not a type.** An array is storage rather than a view of one, and storage written
once is what `val` declares:

```sysl
frozen() -> int
    var table: [4]const int
    table[0]
```

```error
'const' says a view refuses writes, and an array is storage rather than a view of one
```

**What a view does not record** is whose elements these are, whether they outlive the program, and
whether their owner's count is atomic. Those are properties of the *owner*, and a view can only
report on them — which is what the [refusal below](#what-is-still-refused) is about.

## Length

`a.len` is the number of elements, as a `usize`. On a `[N]T` it is the constant `N` and costs
nothing; on a `[]T` it is the third word.

It is a **property** — a member read without parentheses — because a length is a projection of what
is already there rather than a computation over it. On the built-in array and slice types it is
compiler-provided, so `a.len` reads the same whether `a` is a fixed array whose length is a constant
or a slice whose length is a word it carries.

## Iterating

```sysl
var xs = [1, 2, 3]

for x in xs
    x = x * 10

for i in 0..<xs.len
    xs[i] *= 10

print(xs[0], xs[1], xs[2])
```

```output
10 20 30
```

`for x in seq` over an array or a slice binds a **copy** of each element, which is what value
semantics mean — the first loop above multiplies three copies and throws them away. Changing the
sequence goes through the index form. The loop evaluates its sequence once, so a slice written
directly in the header lives for the whole loop rather than being rebuilt each step.

**Storage is walked by index, and that is why a container is not an iterator.** A `for` also accepts
a cursor — a value implementing `Iterate` — but no built-in sequence implements it, and `Buf`
deliberately does not: `for x in b.view()` reads elements already sitting in memory, which costs an
index where a cursor would cost a call apiece. The protocol is for sequences whose elements have to
be *computed*, which a container's never are.

## What a view keeps alive

Taking a slice **retains the owner**; dropping one releases it. That is what makes "a slice never
dangles" a fact rather than a hope, and it holds across a reallocation:

```sysl
import sysl.buf.{Buf, buf}

var b: &Buf[int] = buf()

b.push(1)
b.push(2)

var v = b.view()

for i in 0..<100 do b.push(i)

print(v.len, v[0], b.len())
```

```output
2 1 102
```

The pushes moved the elements to fresh storage. The view made before the move keeps the **old**
storage alive and goes on showing what it was made from — it does not become invalid, which is the
half of Go's behaviour sysl could not have reproduced even if it had wanted to. What it does not do
is grow with the buffer: it is a view of some elements and it has the length it was made with. Take
it again to see more.

Two consequences for how this is implemented:

- **The owner word is null** whenever there is nothing to keep alive — a view of a string literal, of
  static storage, or of a `*T` region — so retain and release on a slice must tolerate null. The
  `&T` path does not pay for that check: a reference is non-null by construction, so nullability is
  the slice's problem alone and gets its own pair of runtime helpers.
- **Release cannot be per payload type.** A `[]u8` may view a 64-byte buffer today and a 4096-byte
  one tomorrow, so the static type of the slice does not name the type of the object its owner points
  at. Giving back a count is therefore type-erased, through the deallocation hook every ARC object
  carries — which is why releasing a slice's owner is the same instruction sequence as releasing any
  other reference, with no static type in sight.

A view that would outlive the array it was made from does not fail: the array is **promoted** to a
buffer instead, so a program that means to return one writes the ordinary `var buf: [64]u8` and says
nothing. [Memory](/reference/memory/#escape-analysis) is where that is settled, along with what is
still refused — storage the body did not declare.

## Growing one

`Buf[T]` is the growable array, and it is **ordinary sysl in the library** rather than a type the
compiler knows: a `[]T` field for the storage, a count of how much of it is live, and the methods
over them. That it can be written at all is what the section on storage sized while running bought —
a container does not need a destructor if its storage is a value that already has one.

It lives in [`sysl.buf`](/library/buf/), which is where the surface is documented. Nothing in the
language reaches it: an array literal makes an array or a slice, and a `for` walks whatever
implements `Iterate`.

How a push is seen by another name for the same buffer is not a question this language has to answer,
because it is already answered by how the buffer is held:

```
var p: &Buf[int] = buf()
var q = p                   // one buffer, two names: q sees every push through p
var c = *p                  // a copy, because copying a struct is what that means
```

Go's confusion — two slices that agree until one of them grows — comes from having one representation
and therefore one behaviour. Held by reference, a `Buf` behaves as a `&T` behaves; held by value it
is a value. Neither is a rule about growable arrays.

## What is still refused

**A `&sync` array cannot be sliced.**

```sysl
var boxed: &sync [4]int = [1, 2, 3, 4]
var v = boxed[..]
```

```error
a slice does not record whether its owner's count is atomic
```

This is the one place where the read-only bit's existence does not help, and the reason is the
asymmetry between the two properties. A `[]const T` can be *made* out of a writable view by giving
something up, which is why widening is safe and why the bit costs nothing at run time. An atomic
count cannot be made out of a non-atomic one at all: it is fixed when the object is allocated, and a
view claiming it would be reporting on somebody else's storage rather than describing itself. Rust
reaches the same three answers with three mechanisms and splits them the same way — `&[T]` carries
writability in the reference, `Rc<[T]>` against `Arc<[T]>` carries count discipline in the *owner's*
type.

**There is no unchecked-index escape hatch** for a hot loop. A `*T` is the way to write one today.

**There is no rectangular multi-dimensional type.** `[3][3]f64` is an array of arrays and works;
a distinct type with one bounds check for two indices is not planned.

---

Next: [strings](/reference/strings/) — the same three words, with a guarantee added and an operation
taken away.
