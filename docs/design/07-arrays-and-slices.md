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

- **Growth** — `append`, and a length that changes after the storage exists. `§Storage sized while
  running` settles the half that reached signatures: a view may own its elements, so a function
  sizes its own buffers and returns one. What is left is the question that decides the rest, which
  is **what an append does to the other views of the same storage**. Go's answer — write in place
  while the capacity lasts, reallocate silently when it does not — is why a Go program can hold two
  slices that agree until one of them grows. sysl cannot reproduce the memory-unsafe half of that
  (an earlier view retains its own storage, so it stays valid), but it would reproduce the
  confusion, and a container whose aliases quietly stop agreeing is not worth an `append`. The other
  answer is that a growable array is a **reference** — one object every alias reaches through, so a
  push is visible to all of them, which is what `&T` already means and needs no new rule. That is
  the likely shape; it is not yet decided, and neither is whether the thing is written in the
  language or in a library once the language can express a destructor.
- **A library container at all.** Growth is above rather than here because a library cannot yet
  reach it: a `Vec[T]` written in sysl would need storage sized from `sizeof(T)` over a type
  parameter, a cast to reach the elements through it, and — the one that decides it — **a
  destructor**, so the storage is returned when the last holder goes. The language has exactly one
  destructor, the deallocation hook every ARC box carries (`03`), and no way to write another. So a
  container in a library is not a smaller feature than a container in the language: it is that
  feature plus `Drop`, plus `sizeof` over a parameter, plus a pointer cast — which is opening the
  unsafe tier to generic code to get to something ARC already does.
- **A generic container making its own storage.** The repeat form `[v; n]` (`§Writing one down`)
  settles the concrete half of this: an `enum` has no zero value, but `[Empty; 16]` needs none. What
  is left is the generic half — a `[16]K` still cannot be declared *whatever* `K` is, because a
  bound promises behaviour and no trait in the catalog promises a value, so there is nothing to
  write in the repeat's value position. A generic container can therefore make its own storage only
  once it already holds something to fill it with. A `Default`-style bound is the obvious answer and
  is not in the catalog (`14`); whether it should be is that document's decision.
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
  written today keeps its meaning when one arrives.
- **An unchecked-index escape hatch** for hot loops, listed as likely-yes and deferred in `03`.
- **Multi-dimensional shorthand.** `[3][3]f64` already works as an array of arrays; a distinct
  rectangular type is not planned.
