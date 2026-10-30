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

As in Go, one type is storage and one is a view, so a function that only reads takes the view and
need not care where the elements live. Both carry a length, so **every index is checked**, which
makes them the safe choice in place of a `*T`.

An array **is** its elements. A slice **names** elements that live somewhere else. The rest of this
page follows from that distinction, and a function call shows it most plainly:

```sysl
scribble(a: [4]int) -> int
    a[0] = 99
    a[0]

set_first(xs: []int)
    xs[0] = 99

var a = [1, 2, 3, 4]

print(scribble(a), a[0])

set_first(a)

print(a[0])
```

```output
99 1
99
```

`scribble` receives four integers and writes to its own copy of them. `set_first` receives three
words naming the caller's four integers, and writes to those. Neither function says anything about
copying; the parameter types decide it.

A `string` is the second row of that table with one thing added and one taken away — its bytes are
guaranteed well-formed UTF-8, and nothing may write through it. Everything here about indexing,
slicing, length and ownership is therefore true of a string as well, and
[strings](/reference/strings/) is where the two differences are.

A third shape is deliberately not in that table: `<N>T`, a [vector](/reference/vectors/), holds the
same values an `[N]T` holds but is not storage at all. It lives in a register and its operators work
on every lane at once, so `a + b` on two `<4>f32` is one instruction doing four additions. Everything
on *this* page — run-time index checks, slicing, ownership — concerns values with an address, and a
vector has none.

Arrays and slices are how a vector reaches memory, though, and the two methods that move a run of
elements between them belong to the array and the slice: `xs.load(i)` reads the run starting at element `i` into a
vector, and `xs.store(i, v)` writes one back. Both are bounds-checked like a subscript, and checked
as a whole run — see [vectors](/reference/vectors/).


## Writing one down

**A literal** lists its elements, and the count becomes part of the type:

```sysl
var primes = [2, 3, 5, 7]
var row: [3]f64 = [1, 0, 0]

print(primes.len, primes[2], row[0])
```

```output
4 5 1
```

**The elements share one type, and a bare literal takes it from an element that has one** — the rule
the two sides of `n + 1` follow. One suffix, or one typed name, anywhere in the list is enough, and a
nested literal settles row by row:

```sysl
const k: usize = 5

var sizes = [1, 2usize, 7]
var mixed = [1, k, 3]
var table = [[1usize, 2], [3, 4]]

var total = sizes[0] + mixed[1] + table[1][1]

print(total)
```

```output
10
```

The bare literals are read at that type, not as an `int` first. An integer literal beside an
integer element is read at its type whether it fits or not, so `[1u8, 300]` is refused at the `300`,
as a literal beside a typed operand is (§
[Which conversions are implicit](/reference/expressions/#which-conversions-are-implicit)); a float
literal that does not fit takes its default, `real`, and the typed elements widen to that where every
value of theirs is one of its. Two elements that each have a type meet at the wider where one holds
every value of the other, as two operands do — `[b, n]` over a `byte` and an `int` is a `[2]int` —
and otherwise have to agree, so `[u, n]` over a `u32` and an `int` is refused. An integer literal
beside a real is not made a real.

```sysl
var bytes = [1u8, 300]
```

```error
the literal 300 does not fit byte
```

**Tuple elements meet part by part**, each part by the same rule, however deeply they nest: a bare
literal part takes the type of the part at its place in another element, so the `0`s below are
`long`s and the list is a `[3](long, int)`. A tuple written out of bare literals alone takes the
type of a tuple beside it whole, in an array literal, as an operand, and as an argument for a generic
parameter.

```sysl
val spots = [(0, 0), (1i64 << 40, 2), (-1, 7)]
val wide: [3](long, int) = spots

print(wide[1].0, wide[2].0 + wide[2].1)
```

```output
1099511627776 6
```

Two typed parts that do not meet are refused as two typed elements are, and the message names both
tuples — `[(u, 0), (n, 0)]` over a `u32` and an `int` is told it got `(uint, int)` and `(int, int)`.

**A declaration with no initializer** starts at the type's zero value. **A repeat** `[value; count]`
fills every element with one value, and is the form for an element type whose zero is not the wanted
starting point — or which has no zero at all:

```sysl
const n = 6

var counters: [4]int
var ones = [1; 5]
var grid = [[0; 3]; 3]
var window = [0; n]

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
var k = 4
var buf: [4]u8 = [0; k]
```

```error
an array's repeat count must be a constant, since it is the array's bound
```

The diagnostic goes on to name the alternative, described in a section below: a count computed while
running makes *storage* rather than an array, and storage is written where a `[]T` is expected.

**The element type comes from the value**, or from the context where the value is a bare literal.
And **the value is evaluated exactly once**, then copied into every element:

```sysl
struct Counter
    n: int

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

One call fills three elements, so the form is a construction rather than shorthand for writing the
value out `count` times. Where the element type holds a reference, each copy is a share of its own.

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
this type" for **any** type that has one. What matters here is which types have none: a type
containing a `&T` has no zero value, because a reference always points at something live.

```sysl
struct Node
    next: &Node

var n: Node
```

```error
Node has no zero value, so 'n' needs an initial value
```

That keeps the non-null guarantee of [memory](/reference/memory/) true with no special case for
arrays, and it is why an array of enum values is written as a repeat such as `[Empty; 16]`.

### A table written at the top of a file

The same three forms written outside a function make a `val`, the natural form for a fixed table such
as a lookup order defined by a file format:

```sysl
val order = [16, 17, 18, 0, 8]

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
known while running, sliced, and iterated. It may not be written, so slicing one yields a
`[]const T`.

**A `val` may hold a value the program has to build.** Storage that exists for the whole run is never
released, so the share it holds is never given back — which is what a static is, and why this is
allowed:

```sysl
squares(k: usize) -> []int
    var xs: []int = [0; k]

    for i in 0..<k do xs[i] = int(i) * int(i)

    xs

static val table = squares(4)

print(table[0], table[3], table.len)
```

```output
0 9 4
```

What the value **is** still decides where the storage is filled. A string literal's bytes are a
constant in the object file and its owner word is null, so a table of them is constant data
throughout and needs no code to run first. That lets a module with no allocator hold one, as it can
hold `order` above:

```sysl
val names = ["alpha", "beta", "gamma"]

var i = 2

print(names[i], names[0].len)
```

```output
gamma 5
```

A `const` would not serve here: a constant is folded into its uses and has no address, so there is
nothing to index at `i`. A raw pointer may be held for the same reason a
literal may — it counts nothing, so there is no release to write.

### A byte array from a string literal

On-disk formats are full of fixed-width text: an OEM name in eight bytes, a volume label in eleven, a
magic number in four. **A string literal is accepted wherever a `[N]u8` is expected, when its UTF-8
length is exactly `N`** — a binding's annotation, a field, an argument, an element of an outer array
and a function's result:

```sysl
struct Boot
    oem: [8]u8
    label: [11]u8

magic() -> [4]u8 = "FAT1"

sum(b: [3]u8) -> int = int(b[0]) + int(b[1]) + int(b[2])

val boot = Boot("MSWIN4.1", "NO NAME    ")
val kinds: [2][3]u8 = ["FAT", "EXT"]

print(boot.oem[0], boot.label[10], magic()[3], kinds[1][0], sum("abc"))
```

```output
77 32 49 69 294
```

The literal is its bytes and nothing else: no NUL is added, and an escape counts as the bytes it
encodes to, so a non-ASCII character takes its full UTF-8 width:

```sysl
val e: [2]u8 = "é"
val nl: [2]u8 = "\n\0"

print(e[0], e[1], nl[0], nl[1])
```

```output
195 169 10 0
```

**It is an array literal of those bytes**, so a module `val` or `var` written this way is constant
data: on a bare-metal target it is laid into `.rodata` or `.data` with nothing run before the first
statement, exactly as `[77, 83, …]` would be.

**The length has to match exactly, either way.** A shorter literal is not padded, because what the
rest should hold — spaces, NULs — is the format's to say, not the language's:

```sysl
val oem: [8]u8 = "MSWIN"
```

```error
this string literal is 5 bytes and [8]byte holds 8 — a string literal fills a byte array only when its UTF-8 length is exactly the array's
```

**Only `u8` (and a name for it) takes one.** A `[N]i8` or a `[N]char` is not a byte array the literal
spells — a `char` is a scalar value, not a byte — so there the literal stays a `string`, and the
position refuses it as it refuses any other mismatch:

```sysl
val a: [2]char = "ab"
```

```error
cannot initialize 'a': declared [2]char but the value is string
```

A byte above `0x7F` that is not part of a UTF-8 character — a boot sector's `0xEB` jump — cannot be
written in a string literal at all, since a string is valid UTF-8; that is an array literal's job.

### A file's bytes, embedded

Fixed data often comes as a file — an image, a font, a program a kernel carries before it has a
disk. **`embed("path")` is that file's bytes, read while compiling, as a `[]const u8`** over a
constant the object file carries, as `include_bytes!` is in Rust and `#embed` in C. This program is
`main.sysl`, and embeds itself:

```sysl
// hello, from the file itself
import sysl.raw.str_cast

val me = embed("main.sysl")

print(str_cast(me[3..<8]), me[0] == u8('/'))
```

```output
hello true
```

- **A relative path is read from the directory of the file that writes it**, never from wherever the
  build was started, so a source file and the data beside it move together and the tree builds from
  any directory. An absolute path is taken as written.
- **The bytes start on a 16-byte boundary**, as aligned as any scalar a target has, so a header read
  in place from the start of an image is aligned even on a machine that faults on an unaligned load.
  `embed("disk.img", align = 4096)` asks for more; the boundary is a constant power of two.
- **It is constant data**, so a `val` holding one is laid down rather than filled by code — a module
  with no allocator and no entry point, a kernel's, may hold one — and a file embedded in several
  places is laid down once.
- **The build depends on the file**: its bytes are in the key of every cache that replays a build,
  so an edit to it is a different program, exactly as an edit to the source is.
- `embed` is a form only where nothing else claims the name: a function or a local called `embed` is
  what a call reaches.

A file that is not there is refused while compiling, naming the path that was tried:

```sysl
print(embed("logo.bin").len)
```

```error
cannot embed 'logo.bin': there is no file at '
```

**The path is a string literal and nothing else.** A build that is replayed has to know which files
it read without being compiled again, and a literal is what can be found in the source text; a
`const` or a computed string is refused:

```sysl
val name = "logo.bin"

print(embed(name).len)
```

```error
'embed' reads its file while compiling, and a build that is replayed has to know which files it read without compiling again — so the path is written as a string literal, not computed
```

## Storage sized while running

Every form so far fixes its length in the type, which a program reading a file cannot do: the size is
in the header, and the header is read by the code that needs the buffer.

A sequence whose length is not in its type is exactly what a `[]T` is, so no new spelling is needed.
**The expected type decides** which reading a literal or a repeat gets:

```sysl
decode(header: []const u8) -> []u8
    var out: []u8 = [0; header[0]]

    for i in 0..<out.len do out[i] = header[1] + u8(i)

    out

var frame: [2]u8 = [3, 10]
var body = decode(frame)

print(body.len, body[0], body[2])
```

```output
3 10 12
```

Under a `[N]T` a non-constant count is the error above; under a `[]T` a constant count is also
accepted. The three spellings side by side:

```
var buf: [64]u8 = [0; 64]     // an array — the count is in the type, so it is constant
var raw: []u8   = [0; n]      // a view of fresh storage — n is any expression
var xs:  []int  = [1, 2, 3]   // likewise, with the elements written out
```

**The storage is the view's own.** The `owner` word is an ordinary counted reference to the elements,
so everything under [ownership](#what-a-view-keeps-alive) is already true of it: a sub-slice retains
it, the last view to go releases it, and the elements are destroyed before the bytes go back. Nothing
about indexing, slicing, `.len` or iterating tells such a view apart from one over another owner's
elements, so a separate type would add nothing.

What it adds is **leaving the frame**, as the program above does: a decoder that learns
its size from what it is decoding returns a result, instead of asking its caller to size a buffer
whose size is in a header the caller has not read.

**Three things are checked**, because a computed length is where arithmetic goes wrong. The count is
widened to the address width and read unsigned, so a negative one arrives as a very large one; the byte size is
computed with an overflow-checked multiply and add, so a count that would wrap cannot allocate a
small buffer that is then written past; and a failed allocation traps rather than handing back a null
the elements are then stored through.

## Indexing

`a[i]` reads the element, and it is a **place** — so `a[i] = v`, `a[i] += 1`, `a[i]++` and `&a[i]`
all follow from the general rules for assignment and address-of, with nothing specific to arrays:

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

The index is widened to **the address width** and compared **unsigned** against the length. That is
one comparison rather than two, and it rejects a negative index as a very large one, the standard
technique for a bounds check. (It is the address width rather than a fixed 64 bits because it is
compared against a length, and a length is a `usize`.)

An index already *wider* than an address — a `u128` anywhere, or an ordinary `int` on a 16-bit
machine — is asked whether it fits and then narrowed, in that order. Nothing holds more than `usize`
elements, so a value that does not fit names no element and traps like any other index out of range;
testing before narrowing is what stops `2^64 + 5` arriving as `5` and passing on a six-element
array. So `for i in 0..<n do a[i] …` needs no conversion on any machine.

### A failed check traps

```sysl
var a = [1, 2, 3]
var i = -1

print("before")
print(a[i])
```

The process stops at the index. This is the same runtime-safety category as the partial `char(u)`
conversion and a mid-character string slice, and it gets the same treatment: a trap instruction, no
message, and no unwinding. Output already written but not yet flushed is lost with it, so `before`
does not appear; a truncated log is therefore not reliable evidence of how far a program got.

### A check a guard already paid for is folded

Plain `+` on an unsigned integer [wraps](/reference/types/), so a guard written as
`n + len > out.len` proves nothing about `out[n + k]`: a huge `n` wraps the guard's sum to a small
value, the guard passes, and only the subscript's own check stops the program. Written so that
nothing in it can wrap, the guard does settle the subscripts after it:

```sysl
fill(out: []u8, n: usize, len: usize, v: u8) -> bool
    if n > out.len || len > out.len - n then return false

    for k in 0..<len
        out[n + k] = v

    true

var buf = [u8(0); 8]

print(fill(buf, 2, 3, 7), fill(buf, 6, 3, 7), fill(buf, 18446744073709551615, 3, 7))
print(buf)
```

```output
true false false
[0, 0, 7, 7, 7, 0, 0, 0]
```

Past that guard `n <= out.len` and `len <= out.len - n`, so the compiler lays `n + len` and `n + k`
(for `k` running below `len`, in a loop entered after the guard) down as additions that cannot wrap
— LLVM's `add nuw` — and repeats the guard's conclusion as an `llvm.assume` where the fall-through
begins. The optimizer can then see that every `out[n + k]` is inside, and the check goes. It costs
nothing at run time: the flag and the assumption emit no code.

The fact lasts until the first statement that writes `n`, `len` or `out` — a loop whose body
writes one of them is outside it from the start — and a local whose address is taken anywhere in the
function never takes part. The same flag goes on any unsigned add both of whose operands are bounded
by half the word: a view's `.len` (no object reaches past half the address space), a literal no
larger than `isize::MAX`, a narrower unsigned value widened, a `&` with one of these, a `%` by one, a
right shift or a division by a literal, or a local never written after its declaration, or a loop
variable, bounded by one of them.

### A raw pointer is indexed anyway

A `*T` is the one receiver with nothing to check against, and it can be subscripted anyway: that is
C's subscript, unchecked address arithmetic. Slicing one needs the end written, because nothing
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
an array has. A slice keeps a program safe; a pointer is how it talks to hardware and to C, and the
language supplies both.

**A pointer to something with a subscript of its own is indexed as that thing.** The `*[N]T` above
is one; a pointer to a container indexed through a trait (the next section) is the other. `p[i]` on a
`*Buf[int]` is the `i`th element, checked by the `Buf` — exactly what `p.at(i)`, `p.len()` and
`(*p)[i]` reach — and a write through it lands in the element, never the `i`th `Buf` along:

```sysl
import sysl.buf.{Buf, buf}

first(b: *Buf[int]) -> int = b[0]

bump(b: *Buf[int])
    b[0] += 1

var b: Buf[int] = buf()

b.push(41)
bump(&b)

print(first(&b), b.len())
```

```output
42 1
```

The shorthand reaches one level, as selection does, so a `**Buf[int]` is subscripted as the pointer
it is. **A generic decides by the bound, not by the instantiation**: in `nth[T](p: *T, i: int) -> T
= p[i]` the subscript is the `i`th `T` whatever `T` turns out to be, an array or a `Buf` included,
while a `[C: Index[usize, int]](p: *C)` reads `p[i]` as the container's element at every
instantiation.

### Unchecked access

Where a program has proved an index is inside and the optimizer cannot see the proof, the check can
be left out by name. `xs.get_unchecked(i)` reads the `i`th element and `xs.set_unchecked(i, v)`
writes it, on an array or a slice, with no test of `i` at all; everything else about the call —
the element type, a `val` that may not be written, a [read-only view](#const-t-a-view-that-may-not-be-written) — is checked as a subscript is.

```sysl
val xs = [10, 20, 30]
var ys = [0; 3]

ys.set_unchecked(2, xs.get_unchecked(1))
print(ys)
```

```output
[0, 0, 20]
```

For a stretch of code rather than one subscript, `@unchecked` on a line of its own marks the block
indented under it: every subscript written inside it is read with no bounds check.

```sysl
dot(a: []int, b: []int) -> int
    var sum = 0
    val n = if a.len < b.len then a.len else b.len

    @unchecked
        for i in 0..<n
            sum += a[i] * b[i]

    sum

print(dot([1, 2, 3], [4, 5, 6, 7]))
```

```output
32
```

**A vector load or store is a subscript of several elements, and the block covers it.** Inside
`@unchecked`, `xs.load(i)` and `xs.store(i, v)` on an array or a slice (see
[vectors](/reference/vectors/#what-a-load-or-store-guarantees)) leave out the test that the whole run
fits, exactly as `xs[i]` leaves out its test; outside the block the run is still checked. Like the
scalar subscript, a run that does not fit is not a trap: it reads or writes whatever the memory
holds.

```sysl
var xs: [8]f32 = [1, 2, 3, 4, 5, 6, 7, 8]
var ys: [8]f32 = [0; 8]

@unchecked
    val v: <4>f32 = xs.load(4)
    ys.store(0, v * 2)

print(ys[0], ys[3])
```

```output
10 16
```

**The block is lexical.** A closure or a nested function written inside it is inside it; one written
outside and called from inside keeps its checks, and so does every subscript after the block ends. A
[`Buf`](#growing-one), and any container whose subscript is its own, takes part through its
`IndexPlace` implementation: inside the block `b[i]` is `b.index_place_unchecked(i)`, whose default
is the checked `index_place`, so a container that has not said how to skip its test keeps it. `Buf`
says how, and has `get_unchecked` and `set_unchecked` of its own.

An index out of range in either form is not a trap: the read or write lands wherever the address
falls, as it does through a [raw pointer](#a-raw-pointer-is-indexed-anyway). What the compiler
evaluates while compiling still checks, so a constant that reads past the end is refused, block or
not. `@unchecked` marks a block and nothing else:

```sysl
val xs = [1, 2, 3]

@unchecked print(xs[0])
```

```error
'@unchecked' marks a block: write it on a line of its own and indent the statements it covers under it
```

There is no `unsafe` block to stand them in: like `from_utf8_unchecked` and a raw pointer's `*`, the
spelling is the marker a reader searches for.

### A type with no elements of its own is indexed through a trait

Everything above is about the built-in subscript rather than about `[]` as a token. A user type — the
library's [`Buf[T]`](/library/buf/), a lookup table, anything a program writes — implements
`IndexPlace` or `Index` and is read with the same syntax:

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

**A type that implements `IndexPlace` has elements that are places**, as an array's are. Its one
method answers the element's address, checked against the bounds, and `b[i]` means
`*b.index_place(i)` — so `b[i].f` reads one field where it lives, `b[i] += 5` writes the element,
and `ref e = b[i]` names it. `Buf` is one:

```sysl
import sysl.buf.{Buf, buf}

var b: &Buf[int] = buf()

b.push(1)

b[0] += 5

print(b[0])
```

```output
6
```

A program's own container takes the same trait. It is declared `*self`, so a container whose elements
are its own bytes answers an address in the container and not in a copy of it:

```sysl
struct Ring
    cells: [4]int
    head: usize

impl IndexPlace[usize, int] for Ring
    index_place(*self, i: usize) -> *int = &self.cells[(self.head + i) % 4]

var r = Ring([1, 2, 3, 4], 2)

r[0] *= 10

ref last = r[1]

last = 0

print(r.cells)
```

```output
[1, 2, 30, 0]
```

**Where the method takes a `usize`, the subscript takes any integer**, exactly as an array's does: the
compiler brings the index to a `usize` before the call, so `b[i]` with `i` an `int`, an `i8` or a
`u64` needs no conversion written, and the index fixes no literal binding's type. This holds for
`Index`, `IndexPlace` and `IndexSet` alike, and for a type parameter bounded by one of them:

```sysl
import sysl.buf.{Buf, buf}

var b: &Buf[int] = buf()

b.push(10)
b.push(20)

val i = 1
val narrow: i8 = 0

b[narrow] += 5

print(b[i], b[narrow])
```

```output
20 15
```

**A negative index stops the program at the subscript**, as an array's does, since no `usize` holds
it; the method is never called. A negative literal is refused while compiling:

```sysl
import sysl.buf.{Buf, buf}

at(b: &Buf[int], i: int) -> int = b[i]

var b: &Buf[int] = buf()

b.push(1)
print("reading")
print(at(b, 0 - 1))
```

```output trap
reading
```

```sysl
import sysl.buf.{Buf, buf}

var b: &Buf[int] = buf()

print(b[-1])
```

```error
the literal -1 does not fit usize
```

What happens to an index past the end is the method's to decide: an array's check traps, and the
standard containers' — `Buf`, `Frozen`, `Deque`, `Ring` — trap the same way, reporting the index and
the count through `sysl_bounds_fail` as an array's check does, without the caller's location
([`Buf`](/library/buf/#bounds-a-trap-here-an-option-there)). A method that
takes some other type — an `int`, a `string` key — is handed the index as it was written, and an
argument of the wrong type is refused as it would be in a call.

**A type that implements only `Index` is read by a call**, which yields a value rather than a place.
The index is whatever the implementation takes, and need not be an integer; `b[i] = v` reaches a
second trait's method, `IndexSet`, and the compound forms are refused, since expanding them would
evaluate the receiver and the index twice ([core](/library/core/)).

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

The inclusive `..` differs from C-family habit, but the range operators mean the same thing
everywhere: `for i in 0..<n` and `a[0..<n]` cover the same indexes. So "the first `n`" is `a[..<n]`,
matching the loop that walks it.

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

print(total(boxed), boxed[2], boxed.len)
```

```output
10 3 4
```

The call passes `boxed` itself rather than `boxed[..]`; the next section explains why that works.

## An array where a view is asked for

**A `[N]T` standing where a `[]T` is wanted is `a[..]`, written by the position instead of by hand.**
An array already knows both halves a view is made of — where the elements are, and how many — so
nothing is taken on trust and no bound is guessed.

This matters most for an API built on caller-supplied storage, the usual style of freestanding code:
the caller lends, the callee fills, and nothing allocates.

```sysl
fill(s: []int, v: int)
    for i in 0..<s.len do s[i] = v

var a = [0; 4]

fill(a, 7)

print(a[0], a[3], a.len)
```

```output
7 7 4
```

It happens at every position that asks for a view rather than merely requiring one — an argument, a
`val` or `var` with the view's type written on it, a `return`, a parameter's default — and a `&[N]T`
converts on the same terms, with the reference as what the view is taken over.

**The conversion does not change the storage's own terms.** A view of a `val` array is a
`[]const T`, exactly as `a[..]` would give, so a `val` array passed where a `[]T` is wanted is
refused, and the diagnostic says why:

```sysl
fill(s: []int, v: int)
    for i in 0..<s.len do s[i] = v

val a = [1, 2, 3]

fill(a, 7)
```

```error
is a licence to write them — so the one does not become the other
```

A `&sync [N]T` does not convert either, for the same reason it cannot be sliced. A `*[N]T` is
excluded deliberately: the raw-pointer tier is written out where it is used, and a view of
pointed-at storage taken silently is the one case where nothing in the type says the elements are
really there.

### An empty literal is a view of nothing, and needs no allocator

**`[]` where a view is wanted is the empty view**, which is the zero value of what a view is made of —
no elements, no length, and no owner. So it makes no storage and reaches no allocator, and a module
without a heap may write one.

```sysl
@no_alloc

val none: []const int = []
var also: []int = []

print(none.len, also.len)
```

```output
0 0
```

It takes its element type from the position it stands at, having none of its own — an `[]` with
nothing asking for a view is refused for exactly that reason.

**A literal with elements in it is different**, and the difference is where the storage comes from: at
a `[]T` such a literal builds a buffer of its own, which needs an allocator. Where the elements are
laid out in the caller's frame instead, what is written is an array and a view of it — `[1, 2, 3][..]`
— and that is the form a variadic call packs its arguments into; see
[declarations](/reference/declarations/#a-parameter-may-collect-the-rest-of-the-call).

**A sliced array literal takes its element type from the view that was asked for**, which is the one
receiver with no type of its own:

```sysl
show(xs: []const &Display) -> unit
    for x in xs
        print(x)

show([1, "hi", true][..])
```

```output
1
hi
true
```

## `[]const T` — a view that may not be written

The `const` sits after the brackets, where `sync` sits after the `&`, and for the same reason: it is
a property of the **view**, not of the element type. It is the signature a function that only reads
should have.

```sysl
scale(xs: []const int)
    xs[0] = 99

var data = [1, 2, 3]

scale(data)
```

```error
which views elements it may not write, so there is nothing to assign through
```

The same diagnostic answers `xs[i] += 1` and `xs[i]++`, and ends by naming the alternative: to write
the elements, copy them into a `[]T` of your own first.

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

A caller can always give up the ability to write; gaining it is what the type exists to prevent.

**Where two of them have to agree, they meet at the read-only view.** A pair of operands, the branches
of an `if`, the arms of a `match`, a loop's `break` values — each is a place where two types have to
become one. Apart from the lossless numeric conversions
(§ [Which conversions are implicit](/reference/expressions/#which-conversions-are-implicit)), the two
views are the only pair of concrete types with a common type to meet at; any other two must agree or
the form is refused, since there is no subtyping to widen towards.

```sysl
var a = [1, 2, 3]
var b = [1, 2, 3]

val c: []const int = b[..]
val k = if a.len > 0 then a[..] else c

print(a[..] == c, c == a[..], k.len)
```

```output
true true 3
```

Without this rule the **order** would decide: the first of the two would settle the type and the
second would be read at it, so a form and its mirror image could disagree about the same two values.

**What they meet at is the read-only view**, so this is not a way of inventing a licence to write:

```sysl
var a = [1, 2, 3]
val c: []const int = a[..]

var k = if a.len > 0 then a[..] else c

k[0] = 9
```

```error
views elements it may not write, so there is nothing to assign through
```

Two writable views are untouched — nothing is read-only, so there is nothing to meet — and a form with
only one branch keeps that branch's view whichever it is.

**What produces one.** Slicing a `val` array, since read-only storage gives a read-only view — and so
is a `val` array standing where a view is asked for, which is the same rule reached without the
brackets. An array inside a `val` struct value is the binding's storage too. `s.bytes`, whose
elements are a string's own and may be a literal's. Re-slicing one, because a bit a second subscript
dropped would make `xs[..]` the way around `xs`. And a buffer
literal written where one is wanted, since storage an expression makes has no other holder to
disagree with it.

```sysl
readonly(xs: []const int) -> usize
    var again = xs[1..]
    again.len

first(xs: []const int) -> int
    val p = &xs[0]
    *p

var a = [1, 2, 3, 4]

print(readonly(a), first(a))
```

```output
3 1
```

**A `val` holding an address is not read-only storage of elements.** `val` is a promise about the
name's own bytes, and a `*T`, a `&[N]T` or a `[]T` is an address: the elements it leads to belong to
whoever laid them down, so a view taken through one is exactly as writable as that owner allows.

```sysl
fill(s: []int, v: int)
    for i in 0..<s.len do s[i] = v

var store = [1, 2, 3, 4]

val p: *int = &store[0]
val whole: []int = store[..]
val heap: &[2]int = [5, 6]

fill(p[0..<1], 7)
fill(whole[2..], 8)
fill(heap, 9)

print(store[0], store[3], heap[1])
```

```output
7 8 9
```

**A read-only view still allows `&`.** `&xs[0]` is a `*T` the moment it is written, the tier where
the guarantees stop, and it is how a view reaches a C function taking a pointer and a length. The
library's own `find_byte` is `memchr` over exactly this, and a read-only view that could not yield
an address could not be passed to C at all. This is *not* the rule for a `val` itself, where
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
whether their owner's count is atomic. Those are properties of the *owner*, which a view cannot
describe; the [refusal below](#what-is-still-refused) follows from that.

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

`for x in seq` over an array or a slice binds a **copy** of each element, as value semantics require
— the first loop above multiplies three copies and discards them. Changing the
sequence goes through the index form. The loop evaluates its sequence once, so a slice written
directly in the header lives for the whole loop rather than being rebuilt each step.

**Storage is walked by index, so a container is not an iterator.** A `for` also accepts a cursor — a
value implementing `Iterate` — but no built-in sequence implements it, and `Buf` deliberately does
not: its elements are already in memory, and reading them costs an index where a cursor would cost
a call per element. The protocol is for sequences whose elements have to be
*computed*, which a container's never are. What `Buf` implements instead is `Walk`, whose `walk()`
answers the view, so `for x in b` asks for it once and then walks the slice by index.

### Asking one a question

An array is a [`Sequence`](/library/seq/) at every length, as a slice and a `Buf` are, so `any`,
`all`, `find`, `position`, `count_where`, `fold`, `each`, `map`, `filter` and `flat_map` are called
on it directly:

```sysl
val RUNS = ["warmup", "sprint", "cooldown"]

print(RUNS.any(r -> r == "sprint"))
print(RUNS.count_where(r -> r.len > 6))
print(RUNS.map(r -> r.len)[2])
print(RUNS.find(r -> r.starts_with("c")).unwrap())
```

```output
true
1
8
cooldown
```

**Asking costs no copy.** An array too large to travel in registers crosses as the address of the
storage it is in, and these members only read it, so `RUNS.any(p)` walks the module's own `RUNS`.
The answers that are sequences — `map`, `filter`, `flat_map` — are new buffers, never views of the
array. A generic `S: Sequence[T]` takes an array as it takes a slice.

## What a view keeps alive

Taking a slice **retains the owner**; dropping one releases it. That is what guarantees a slice never
dangles, and it holds across a reallocation:

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
storage alive and goes on showing what it was made from; it does not become invalid. It does not grow
with the buffer either: it is a view of some elements and keeps the length it was made with. Take it
again to see more.

Two consequences for how this is implemented:

- **The owner word is null** whenever there is nothing to keep alive — a view of a string literal, of
  static storage, or of a `*T` region — so retain and release on a slice must tolerate null. The
  `&T` path does not pay for that check: a reference is non-null by construction, so only slices
  need to handle null, and they have their own pair of runtime helpers.
- **Release cannot be per payload type.** A `[]u8` may view a 64-byte buffer at one moment and a
  4096-byte one at the next, so the static type of the slice does not name the type of the object its
  owner points at. Giving back a count is therefore type-erased, through the deallocation hook every
  ARC object carries, so releasing a slice's owner is the same instruction sequence as releasing any
  other reference.

A view that would outlive the array it was made from does not fail: the array is **promoted** to a
buffer instead, so a program that means to return one writes the ordinary `var buf: [64]u8` and says
nothing. [Memory](/reference/memory/#escape-analysis) is where that is settled, along with what is
still refused — storage the body did not declare.

**The one view with nothing to keep alive and something that can die is a view of memory a C
library owns**, made over a `*T` by an object that frees it in its destructor. Its owner word is
null, so the view cannot hold the object; instead the member that hands it out is marked
`@views(self)`, and the compiler refuses every use of the view that could come after the object it
was called on is freed:

```sysl
extern "free" c_free(p: *u8)

struct Rows
    p: *u8
    n: usize

    @views(self)
    view(&self) -> []const u8 = self.p[0..<self.n]

impl Drop for Rows
    drop(self) = c_free(self.p)

newest() -> []const u8
    val r: &Rows = Rows(null, 0)

    r.view()
```

```error
this returns a view borrowed from 'r', and nothing keeps 'r' alive once this function returns
```

The view is still a plain `[]const u8` at no run-time cost. [Memory](/reference/memory/#a-view-borrowed-from-its-receiver)
has the four places it is refused.

## Growing one

`Buf[T]` is the growable array, and it is **ordinary sysl in the library** rather than a type the
compiler knows: a `[]T` field for the storage, a count of how much of it is live, and the methods
over them — with the one fact below, which only the compiler can pass on to the optimizer. It can be
written in ordinary sysl because of [storage sized while running](#storage-sized-while-running): a
container needs no [destructor](/reference/memory/) of its own when its storage is a value that
already has one, as the `[]T` field does.

It lives in [`sysl.buf`](/library/buf/), which is where the surface is documented. Nothing in the
language reaches it: an array literal makes an array or a slice, and a `for` walks whatever
implements `Iterate`.

Whether a push is seen through another name for the same buffer needs no special rule; it follows
from how the buffer is held:

```
var p: &Buf[int] = buf()
var q = p                   // one buffer, two names: q sees every push through p
var c = *p                  // a copy, because copying a struct is what that means
```

In Go two slices can agree until one of them grows, because one representation has to serve both
roles. Held by reference, a `Buf` behaves as any `&T` does; held by value it is a value. Neither is a
rule specific to growable arrays.

**A buffer that is done growing can be frozen.** `b.freeze()` takes its elements and answers a `&sync
Frozen[T]` — one atomic count on the whole buffer, indexed, walked and viewed as a `[]const T`, and
never written — which every thread may read at once without a lock; `b` is left empty. An index
past its length traps, as a `Buf`'s does. What it may
hold, and why, is in [A frozen buffer crosses](/reference/memory/#a-frozen-buffer-crosses):

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(3)
b.push(4)

val f = b.freeze()
val v = f.view()

print(f[1], f.len(), v, b.len())
```

```output
4 2 [3, 4] 0
```

### Its elements are never its header

A `Buf`'s **header** — the handle in its box, and the record behind it holding the storage view and the
count — and its **elements** are two different allocations: the storage is made by the buffer for
itself, and a box is never made out of a buffer's storage. So a write to an element cannot change the
count or move the storage, and a write to the header cannot change an element. **The compiler tells
the optimizer exactly that, and nothing more**: every access the buffer's own members make to its
header is marked as one side, every access they make to an element as the other, and the optimizer
may keep the header in registers across a loop that writes elements. A row operation over a matrix
held in a `&Buf[real]` reads the length and the storage pointer once, not once per cell.

It is a fact about where things are, not a rule a program has to keep. Every other access is
unmarked: a view handed out by `view()` or `elems`, a reinterpreting view over the elements, a `ptr_cast`
of an element's address, and C given the data pointer all read and write the elements with no mark
at all, which the optimizer must assume can touch anything. A member that grows the buffer writes
the header, so an element read after it reads through the new storage:

```sysl
import sysl.buf.{Buf, buf}

var b: &Buf[int] = buf()

b.push(1)

for i in 0..<20 do b.push(b.at(usize(i)) + 1)

print(b.len(), b.at(0), b.at(20), b.cap())
```

```output
21 1 21 32
```

## What is still refused

**A `&sync` array cannot be sliced.**

```sysl
var boxed: &sync [4]int = [1, 2, 3, 4]
var v = boxed[..]
```

```error
a slice does not record whether its owner's count is atomic
```

**Nor can an array that lives inside a `&sync` box**, whether it is a field, a field of a field, or
handed to a parameter that takes a view. A view of it would hold its share of the box, and take and
give back that share the ordinary way while another domain changes the same count atomically:

```sysl
struct Ring
    samples: [8]i16

val r: &sync Ring = Ring([1, 2, 3, 4, 5, 6, 7, 8])
val recent = r.samples[2..<5]
print(recent.len)
```

```error
a slice does not record whether its owner's count is atomic, so storage inside a '&sync Ring' cannot be sliced — a view of it would take its share of the box with a count update that is not atomic. Read it by index, or walk it with 'for'
```

Neither of those takes a share, so both work through the same box:

```sysl
struct Ring
    samples: [8]i16

val r: &sync Ring = Ring([1, 2, 3, 4, 5, 6, 7, 8])
r.samples[3] = 40

var sum = 0
for x in r.samples
    sum += x

print(r.samples[3], sum)
```

```output
40 72
```

This is the one place where a bit like the read-only one cannot help, because the two properties are
not symmetric. A `[]const T` can be *made* out of a writable view by giving
something up, which is why widening is safe and why the bit costs nothing at run time. An atomic
count cannot be made out of a non-atomic one at all: it is fixed when the object is allocated, and a
view claiming it would be reporting on another object's storage rather than describing itself. Rust
divides the same properties the same way — `&[T]` carries writability in the reference, while
`Rc<[T]>` against `Arc<[T]>` carries count discipline in the *owner's* type.

**There is no rectangular multi-dimensional type.** `[3][3]f64` is an array of arrays and works;
there is no distinct type with one bounds check for two indexes.

---

Next: [strings](/reference/strings/) — the same three words, with a guarantee added and an operation
taken away.
