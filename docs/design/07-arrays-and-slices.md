# Arrays and Slices

**Status:** decided. `03-memory-model.md` names the two types and settles who owns the storage
a slice views; `05-escape-analysis.md` settles what happens when a view outlives it. What was
left open — design gap #3 — is the **expression surface**: how an array is written, how it is
indexed, how a slice is taken, and how a length is asked for. That is what this document
decides.

## The two types

```
[N]T        // fixed array: N elements of T, a value type, N a compile-time constant
[]T         // slice: a view of some elements of T, { owner, ptr, len }
```

An array **is** its elements — copying one copies all of them, and it has no header. A slice
is three words that *name* elements someone else owns (`03`). The pair is Go's, and for Go's
reason: one type for storage, one for a view, so a function that only reads takes the view and
never has to care where the bytes live.

Both carry a length, so **every index is checked** — that is the whole point of preferring
them to `*T` in allocator-free code (`03`).

A `string` is the second of these with one thing added and one taken away: its bytes are
guaranteed well-formed UTF-8, and nothing may write through it (`04`). Everything below about
indexing, slicing, length, and ownership is therefore true of a string as well, and the places
it is not are exactly the two the guarantee accounts for — a substring is checked for landing
between characters, and `s[i] = v` does not exist.

## Writing one down

**A literal** lists its elements and fixes the length from how many there are:

```
var primes = [2, 3, 5, 7]              // [4]int
var row: [3]f64 = [1.0, 0.0, 0.0]
```

**A declaration with no initializer** starts at the type's zero value, which is what a scratch
buffer wants:

```
var buf: [64]u8                        // 64 zero bytes
var counters: [8]int                   // eight zeros
```

This form is not array-specific — it is a `var` with a declared type and no `=`, and it means
"the zero value of this type" for any type that has one. A type that contains a `&T` does
**not** have one, since a reference always points at a live object, so declaring one without an
initializer is an error asking for the initializer. That keeps the non-null guarantee of `03`
true without a special case for arrays.

An empty literal `[]` has no element type of its own and takes it from the context, exactly as
a bare `None` does:

```
var empty: [0]int = []
```

**A repeat** `[value; count]` fills every element with one value, and is the form for an array whose
element type has no zero, or has one that is not the wanted starting value:

```
var slots: [16]Slot = [Empty; 16]      // an enum has no zero at all
var ones = [1; 8]                      // [8]int
var grid = [[0; 3]; 3]                 // [3][3]int
const n: usize = 64
var window = [0u8; n]                  // a const may give the count
```

The **count is a compile-time constant** — a literal, a `const`, or an expression over those — for
the same reason an array bound is one: it *is* the array's bound, and the type is not known without
it. The element type comes from the value, or from the context where the value is a bare literal.

The **value is evaluated once** and copied into every element. `[tick(); 3]` is one call whose
result lands in three places, not three calls, which is what makes the form a construction rather
than shorthand for writing the value out `count` times. Where the elements contain references, each
copy is a share of its own: an array of `n` copies of a `&T` holds `n` counts, released as the array
is (`§Ownership`).

**A `val`** is the same three forms written at the top of a file rather than inside one, and it is
what a table of numbers somebody else fixed wants:

```
val order: [19]usize = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]
```

The difference from a `const` is an **address** (`13 §7`): a constant is folded into every use and
has none, so it can size an array but cannot *be* one; a `val` is storage, so it may be indexed at a
value only known while running, iterated, and reached into. What it may not be is written — see
`§Ownership` below for the one thing that costs today.

## Storage sized while running

Every form above fixes its length in the type, and that is the one thing a program reading a file
cannot do: the size is in the header, and the header is read by the code that needs the buffer. The
*heap* is not what is missing — `var p: &[64]u8 = [0; 64]` already puts elements on the heap, and
`p[..]` views them with the box as owner (`§Ownership`). What is missing is a length the program
**computes**.

A length that is not in the type is exactly what `[]T` is, so nothing here needs a new spelling. The
**expected type decides**, which is `03`'s rule for `&T` applied to the forms above — a `T` written
where a `&T` is wanted goes on the heap, and an array written where a `[]T` is wanted does the same:

```
var buf: [64]u8 = [0; 64]              // an array: the count is part of the type, so constant
var raw: []u8   = [0; n]               // a view of fresh storage: n is any expression
var xs:  []int  = [1, 2, 3]            // likewise, with the elements written out
```

The declaration carries the choice, as it does everywhere else in the language, and the two readings
of `[0; n]` are told apart by what is being asked for rather than by whether `n` happens to be
constant. Under a `[N]T` a non-constant count is still the error it was; under a `[]T` a constant
one is simply the easy case.

**The storage is the view's own**, and that is the whole of the mechanism: the `owner` word is a
reference to the elements, so everything in `§Ownership` is already true of it. A sub-slice retains
it, the last view to go releases it, and the deallocation hook destroys the elements before
returning the bytes. Nothing about indexing, slicing, `.len`, or iterating distinguishes a `[]T`
that owns its elements from one that views someone else's — which is the point, and is why a second
type would have been a second type for nothing.

What it adds that the fixed forms cannot is **leaving the frame**. A function may build one and
return it, so a decoder that learns its size from what it is decoding takes one argument and returns
a result, instead of asking its caller to size buffers whose sizes are in a header the caller has
not read.

**Three things are checked**, because a computed length is where the arithmetic goes wrong. The
count is widened to 64 bits and read unsigned, so a negative one arrives as a very large one; the
byte size is computed with an overflow-checked multiply and add, so a count that would wrap cannot
allocate a small buffer that is then written past; and a failed allocation traps rather than handing
back a null the elements are stored through. All three are `§Indexing`'s trap, for `§Indexing`'s
reason.

## Growing one

`Buf[T]` is the growable array, and it is **ordinary sysl in the prelude** rather than a type the
compiler knows: a `[]T` field for the storage, a count of how much of it is live, and `push`, `pop`,
`at`, `set`, `view`, `len`, `cap`, `clear`. That it can be written at all is the interesting part,
and it is what the section above bought.

**Correcting what this document said.** The claim here was that a library could not reach a growable
array — that it would need `sizeof` over a type parameter, a cast to reach the elements through it,
and above all a **destructor**, since the language has exactly one and no way to write another. That
was wrong. A container does not need a destructor if its storage is a value that already has one,
and `§Storage sized while running` is precisely what makes a `[]T` field into storage a container
sizes for itself and ARC destroys on its behalf. The missing piece was never `Drop`; it was the
ability to ask for storage at a length worked out while running.

The other apparent blocker was `§Not yet`'s own: a generic container cannot make its own storage,
because a repeat needs a value in its value position and no bound promises one. **A `push` arrives
holding one** — so the value being pushed seeds the new storage, and the question never comes up.

### What an append does to the other views

This was the open question, and the answer is that **sysl does not have to choose**. A growable
array is a struct, so how a push is seen follows from how it is held, which is a choice the language
already makes the author write:

```
var p: &Buf[int] = buf()
var q = p                       // one buffer, two names: q sees every push through p
var c = *p                      // a copy, because copying a struct is what that means
```

Go's confusion — two slices that agree until one of them grows — comes from having one
representation and therefore one behaviour. Held by reference, a `Buf` behaves the way `03` says a
`&T` behaves: shared, mutable through any alias, visible to all of them. Held by value it is a
value. Neither is a rule about growable arrays; both are rules that were already there.

### A view taken before a growth stays valid

An append can move the elements, and the storage they move out of is an ARC buffer like any other,
so a view made before the move **keeps that storage alive** and goes on showing what it was made
from. It does not dangle, which is the half of Go's behaviour sysl could not have reproduced even
if it had wanted to: the guarantee in `03` is that a program with no `*T` in it cannot fault.

What such a view does *not* do is grow with the buffer — it is a view of some elements, and it has
the length it was made with. Take it again to see more.

### What it costs

The spare capacity holds **copies of the value that seeded the growth**, because there is no way to
have storage without values in it. They are harmless and bounded, but they are real: a `Buf[&T]`
that grew to a capacity of 1024 holding one element is holding 1024 references to it, and that
element stays alive until the slots are overwritten. What a container actually wants here is
capacity that is storage without being values yet — see `§Not yet`.

## Indexing

`a[i]` reads the element, and it is a **place** — so `a[i] = v`, `a[i] += 1`, `a[i]++`, and
`&a[i]` all follow from the place machinery that assignment and address-of already use, with
nothing said about arrays in particular.

**The index may be any integer type.** Requiring `usize` would make `for i in 0..<10 do
a[i] …` need a conversion for no benefit, since the check has to happen anyway. The index is
widened to 64 bits and compared **unsigned** against the length, which is one comparison and
which rejects a negative index as a very large one — the same trick a bounds check has always
used.

A failed check **traps**. It is the same runtime-safety category as the partial `char(u)`
conversion, and it gets the same treatment.

**A type with no elements of its own is indexed through a trait**, and everything above is about the
built-in subscript rather than about `[]` as a token. A user type — the prelude's `Buf[T]`, a
lookup table, anything a program writes — implements `Index` and is read with the same syntax
(`14 §7`). Two differences follow from its being a call rather than a walk to an address: the index
is whatever the implementation takes and not necessarily an integer, and the element is **not** a
place, so `b[i] = v` is a second trait's method and `b[i] += v` is refused rather than reading and
writing back. Nothing a program writes competes with the built-in subscript: an array, a slice and a
string are indexed by the compiler.

## Slicing

A slice expression is an index whose subscript is a range, and the two range operators keep the
meanings they have everywhere else in the language:

```
a[2..5]        // inclusive: elements 2, 3, 4, 5
a[2..<5]       // exclusive: elements 2, 3, 4
a[2..]         // from 2 through the last
a[..<5]        // the first five
a[..]          // the whole thing, as a slice
```

The inclusive `..` is the odd one against C-family habit, but the alternative is worse:
`for i in 0..<n` and `a[0..<n]` **must** mean the same thing, and a language that has already
chosen two range operators does not get to give them different meanings in a subscript. So "the
first `n`" is `a[0..<n]`, matching the loop that walks it.

Both ends are optional. An omitted low end is 0 and an omitted high end is the last element —
and because "through the last" is not a question of including or excluding anything, the
open-ended form is written `a[lo..]`; `a[lo..<]` is rejected rather than quietly meaning the
same.

The check is on the half-open interval the slice ends up naming: with `s` the first index and
`e` one past the last, `s <= e` and `e <= len` must hold, and the inclusive form additionally
requires that its named high element exist. An empty slice is legal, including at the very end
(`a[a.len..]`).

**What can be sliced:** a fixed array, a slice, or an array reached through a `&[N]T` or a
`*[N]T` — field selection's one-level auto-deref (`03`) applies to a subscript too, so
`buf[0..<n]` reads the same whether `buf` is the array or a reference to it.

## Length

`a.len` is the number of elements, as a `usize`. On a `[N]T` it is the constant `N` and costs
nothing; on a `[]T` it is the third word.

It is a **property** (`08-methods.md`) — a member read without parentheses — because a length is
a projection of what is already there rather than a computation over it. On the built-in array
and slice types it is compiler-provided, so `a.len` reads the same whether `a` is a fixed array
whose length is a constant or a slice whose length is its third word.

## Iterating

```
for b in buf do …              // each element, by value
for i in 0..<buf.len do …      // each index
```

`for x in seq` over an array or a slice binds a **copy** of each element, which is what value
semantics mean; mutating the sequence goes through the index form. The loop evaluates its
sequence once, so a slice temporary lives for the whole loop.

**Storage is walked by index, and that is why a container is not an iterator.** A `for` also
accepts a cursor — a value implementing `Iterate` (`14 §7`) — but nothing here implements it, and
`Buf` deliberately does not: `for x in b.view()` reads elements that are already sitting in memory,
which costs an index where a cursor would cost a call apiece. The protocol is for sequences whose
elements have to be *computed*, which a container's never are.

## Ownership

Taking a slice **retains the owner**, dropping one releases it — that is `03`'s rule, and it is
what makes "a slice never dangles" a fact rather than a hope. Two consequences for the
implementation:

- **The owner word is null** whenever there is nothing to keep alive, so retain and release on
  a slice must tolerate null. The `&T` path must *not* pay for that check: a reference is
  non-null by construction, so nullability is the slice's problem alone and gets its own pair
  of runtime helpers.
- **Release cannot be per payload type.** A `[]u8` may hold a view of a 64-byte buffer today
  and a 4096-byte one tomorrow, so the static type of the slice does not name the type of the
  object its owner points at. Giving back a count therefore has to be type-erased, which is
  what the **deallocation hook** in the box header is for: the hook destroys the object
  completely — releasing whatever its payload holds and then returning the storage — so
  releasing a slice's owner is the same instruction sequence as releasing any other reference,
  with no static type in sight.

## Not yet

- **Capacity that is not yet values.** `Buf`'s spare slots hold copies of whatever seeded the last
  growth (`§What it costs`), because every element of a `[]T` is a value and there is no way to have
  storage that is merely reserved. Every container wants that: it is the difference between a
  capacity and a length. What it needs is either a bound that promises a value (`14 §7`) or a way
  to hold storage whose elements are not
  live yet, which is a question about the view types here — a length and a capacity as two separate
  facts about one allocation.
- **A generic container still cannot declare storage it has no value for.** `[None; n]` works
  whatever the parameters are, because `None` needs nothing of them; a `[16]K` cannot be written
  for any `K`, because a repeat needs a value and no bound promises one. That is `14`'s decision
  rather than this chapter's — see its `§7` entry on a bound that promises a value, which the
  bullet above wants for the same reason.
- **Promotion of an escaping local array** (`05`). The analysis that *finds* the escape is
  implemented; what happens next is not. A view that would outlive its array is a diagnostic
  rather than a silent heap promotion, so a program that means to return one writes `&[64]u8`
  itself. That is `05`'s `no alloc` behaviour applied everywhere, which is the safe direction
  to be wrong in, and `--explain-escapes` only becomes meaningful once promotion exists.
- **Slicing a `&sync` buffer.** A `[]T` does not record whether its owner's count is atomic, so
  it cannot carry an owner that needs the atomic path. Rejected with a diagnostic until slices
  distinguish the two.
- **Slicing a `val`.** The same gap seen from the other side: a `[]T` permits writes and records
  nothing about whose elements it views, so a view of read-only storage would be a way of writing
  it — and the view outlives the expression that made it, so there is nowhere to catch that later.
  Rejected with a diagnostic. What this wants is a **read-only view type**, which is a decision
  about the view types here rather than about `val`, and it is additive: every program that can be
  written today keeps its meaning when one arrives. The cost has a name now: **a static table can
  be read and not passed.** `guide/sha2` has eighty round constants per width, and the generic
  compression reaches them only through a trait method whose whole body is one index, because the
  alternative — a `[80]T` parameter — copies the table at every call. A table small enough to copy
  escapes it, which is why the same program hands its eight initial values over as a `[8]T` and
  thinks nothing of it.
- **An unchecked-index escape hatch** for hot loops, listed as likely-yes and deferred in `03`.
- **Multi-dimensional shorthand.** `[3][3]f64` already works as an array of arrays; a distinct
  rectangular type is not planned.
