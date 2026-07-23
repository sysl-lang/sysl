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

- **Growable arrays** — `append`, capacity, a `[]T` that owns rather than views. Needs an
  allocator and a decision about whether growth is a library type or a language one.
- **Promotion of an escaping local array** (`05`). The analysis that *finds* the escape is
  implemented; what happens next is not. A view that would outlive its array is a diagnostic
  rather than a silent heap promotion, so a program that means to return one writes `&[64]u8`
  itself. That is `05`'s `no alloc` behaviour applied everywhere, which is the safe direction
  to be wrong in, and `--explain-escapes` only becomes meaningful once promotion exists.
- **Slicing a `&sync` buffer.** A `[]T` does not record whether its owner's count is atomic, so
  it cannot carry an owner that needs the atomic path. Rejected with a diagnostic until slices
  distinguish the two.
- **An unchecked-index escape hatch** for hot loops, listed as likely-yes and deferred in `03`.
- **Multi-dimensional shorthand.** `[3][3]f64` already works as an array of arrays; a distinct
  rectangular type is not planned.
