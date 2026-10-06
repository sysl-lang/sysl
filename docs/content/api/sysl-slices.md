---
title: sysl.slices
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.slices
summary: "Operations over a built-in slice, and the pointer a C binding needs to hand one across."
requires: "no alloc"
---

**These are free functions over `[]T` rather than members of anything**, which is the call
`math/compare.sysl` already made for `min` and `integer.sysl` made for `gcd`. A slice is indexed by
the compiler walking to an address, so none of this goes through `Index` or `Iterate` -- those say
what indexing and walking mean for a type *someone wrote*, and a built-in has neither to offer. A
bound written on each function is also the specification: `contains` asks for `Eq` because it
compares, `max_index` asks for `Ord` because it orders, and a program with a type of its own
satisfying the bound gets these for nothing.

**Nothing here allocates and nothing here needs a capability.** The module is reachable from a
freestanding target, which is deliberate and is what keeps it usable by a C binding: `as_ptr` is
the first thing a binding reaches for, and acquiring an allocator by asking for it would be a poor
trade. Sorting is in `sort.sysl` beside this file and holds to the same rule.

**The byte `copy` reaches libc, and it is gated on the target rather than reaching it everywhere.**
A bare board is linked `-nostdlib` and has no `memmove` to call, so a hosted target hands the move
to libc and a freestanding one walks the elements. Nothing about what `copy` promises differs
between the two; only what it costs does.

## Index

[`INSERTION_LIMIT`](#insertion_limit) [`align_up`](#align_up) [`as_mut_ptr`](#as_mut_ptr) [`as_ptr`](#as_ptr) [`binary_search`](#binary_search) [`binary_search_by`](#binary_search_by) [`contains`](#contains) [`copy`](#copy) [`copy`](#copy-1) [`copy_exact`](#copy_exact) [`copy_exact`](#copy_exact-1) [`ends_with`](#ends_with) [`equal`](#equal) [`fill`](#fill) [`index_of`](#index_of) [`index_of_from`](#index_of_from) [`is_aligned`](#is_aligned) [`is_sorted`](#is_sorted) [`is_sorted_by`](#is_sorted_by) [`last_index_of`](#last_index_of) [`last_index_of_from`](#last_index_of_from) [`max_index`](#max_index) [`min_index`](#min_index) [`reverse`](#reverse) [`sort`](#sort) [`sort_by`](#sort_by) [`sort_stable`](#sort_stable) [`sort_stable_by`](#sort_stable_by) [`starts_with`](#starts_with) [`swap`](#swap)

## Constants

### `INSERTION_LIMIT`

```sysl
const INSERTION_LIMIT: usize = 16
```

Below this many elements, insertion sort. It is where the recursion bottoms out and where a simple
quadratic sort genuinely wins -- the constant factor is small and the data is in cache.

## Functions

### `align_up`

```sysl
align_up[T](p: *T, alignment: usize) -> *T
```

The first address at or after `p` that `alignment` divides.

**This is what a binding needs to place a C struct in storage the caller supplied.** A `[]u8`
promises nothing about its own address, while the struct C is about to read wants its natural
alignment -- so a caller over-sizes its storage by `alignment - 1` and puts the state at the first
aligned address inside it. `miniz` is the package that does this, and it wrote the arithmetic in C
because a comment there says sysl has no pointer-to-integer cast. It has one: an address read as a
`usize` is an ordinary conversion (`reference/memory.md`), and reading it back is `ptr_cast`, so
this needs no C at all.

The alignment a type wants is `alignof(T)`, which is a compile-time constant, so the usual call
spells it rather than repeating a number the C header already knows.

**A pointer that is already aligned is answered unchanged**, which is what makes it safe to call
on storage that happens to be aligned already -- the common case on a 64-bit machine, and the one
that hides a missing call until the day it does not.

**`alignment` must be a power of two and nothing checks it.** The mask this is computed with means
something only for a power of two; any other value silently answers an address that is not aligned
to anything. It is the caller's to get right, exactly as it is in C.

### `as_mut_ptr`

```sysl
as_mut_ptr[T](xs: []T) -> *T
```

The same, where the caller may write through it.

Separate from `as_ptr` because `[]const T` and `[]T` are different types and the const one must not
yield a pointer something can write through. The empty case answers `null` for the same reason.

### `as_ptr`

```sysl
as_ptr[T](xs: []const T) -> *T
```

A pointer to the first element, for handing a slice to C as the pointer-and-length pair every C
interface takes.

**This exists because every binding was writing it.** `qcbor` and `monocypher` each carried a
private copy and the QOI survey needed the same one, and each copy had to get the empty case right
on its own -- which is the case that is not obvious, and the reason this is a library function.
Both copies are gone: the two packages import this one instead, and their two hand-rolled versions
had different bugs, which is the argument for a library function in one line.

**An empty slice answers `null`.** An empty slice is a real input -- C's convention is a pointer
beside a length, and a length of zero is a loop that does not run -- but `&xs[0]` on an empty slice
is an out-of-bounds index, so it traps rather than yielding the pointer C would have accepted.

The two answers this was chosen over are both worse. A pointer to a **function-local** scratch,
which is what the hand-rolled copies return, points into a dead stack frame: safe exactly as long
as nobody reads it, and silently wrong if anything ever does. A module-level scratch fixes the
lifetime but cannot be generic, so there would have to be one per element type and nowhere to put
them.

**The cost, said here so a caller can see it:** ISO C leaves passing a null pointer to `memcpy`
undefined even at a length of zero, and a small number of libraries assert non-null on entry. Every
real interface takes the pair `(null, 0)`, and the length is what says not to look -- but a binding
whose C asserts otherwise must pass storage it owns rather than an empty slice.

### `binary_search`

```sysl
binary_search[T: Ord](xs: []const T, x: T) -> (bool, usize)
```

Where a value is in a sorted slice, and where it would go if it is not there.

**Answers a pair rather than an `Option`, because the index is wanted either way.** A caller that
found the value wants its position; a caller that did not wants the place to insert it, which is
the expensive half of the answer and is already computed. `Option[usize]` throws that away and
`Result[usize, usize]` -- Rust's spelling -- calls a miss a failure, which it is not.

**The index of the FIRST equal element**, where the slice holds several. That makes the answer
depend on the values rather than on how the search happened to land, which is what lets two
searches of the same slice be compared.

The slice must be sorted by the same order being searched with. Nothing checks it -- that would
cost a linear scan on every search, defeating the point -- and the answer for an unsorted slice is
unspecified rather than wrong in some particular way.

### `binary_search_by`

```sysl
binary_search_by[T](xs: []const T, x: T, lt: (T, T) -> bool) -> (bool, usize)
```

### `contains`

```sysl
contains[T: Eq](xs: []const T, x: T) -> bool
```

Whether the value appears at all -- `index_of` where the position is not wanted.

### `copy`

```sysl
copy[T](dst: []T, src: []const T) -> usize
```

As much of `src` as fits in `dst`, copied element by element, answering how much moved.

**This exists because every caller was writing the loop**, `Buf.extend` included -- and a loop at
a call site is both noise and slower than the platform's own move. C has `memcpy`, Rust has
`copy_from_slice` and Go has `copy` for the same reason.

**The two slices may be views of one array, and the direction is chosen from their addresses** so
that an overlap does not overwrite what has not been read yet. `usize(p)` reads an address as a
number, which is an ordinary conversion (`reference/memory.md`), so this needs nothing raw.
Overlapping is `memmove`'s guarantee rather than `memcpy`'s, and it is the right one to make: two
views into one array is an ordinary thing to have, and a silently wrong answer is not worth the
nanoseconds.

**The short answer is the point rather than a failure.** A streaming caller writes what fits and
asks again; `copy_exact` is for a caller who has already sized the destination and for whom a
mismatch is a mistake.

The declaration beside this one takes byte slices and is what a call over `[]u8` reaches, since
`reference/declarations.md § Overloading` prefers a declaration that named its parameters. That
one hands the move to `memmove`; this one cannot, because an element carrying a reference count
needs the retain and the release an assignment does, and a bitwise move would leak what the
destination held and hand the source's boxes out twice.

### `copy`

```sysl
copy(dst: []u8, src: []const u8) -> usize
```

The same over bytes, which libc moves a word at a time where a sysl loop moves one byte.

**Measured rather than assumed**: the loop above compiles to a `ldrb`/`strb` pair per element at
the default optimization, because the destination's bounds check survives inside the body and
stops the optimizer recognising the idiom. `sysl.io.find_byte` reaches `memchr` for exactly this
reason and says so.

A byte carries no count, so the bitwise move the generic form must not make is the right one
here. `memmove` rather than `memcpy`, for the overlap the other declaration promises.

**A FREESTANDING TARGET WALKS THE ELEMENTS INSTEAD, because there is nothing to call.** A bare
board is linked `-nostdlib`, so a `memmove` reached from here is an undefined symbol at the link
-- and this module is one a board reaches, `sysl.harness` being written for exactly that. The
declaration stays either way, so a caller writes one thing and a program's own targets decide what
it costs.

The gate is `freestanding` rather than anything finer, and that is deliberately conservative: a
board with an SDK usually *does* have a libc -- the Pico's newlib defines `__aeabi_memmove` -- but
whether one is on the link line is the application's to decide and not something the library can
read. Being wrong in this direction costs a loop; being wrong in the other costs a build.

### `copy_exact`

```sysl
copy_exact[T](dst: []T, src: []const T) -> bool
```

The whole of `src` copied, or nothing at all -- `true` when it fitted.

**The question `copy` does not answer.** A caller who sized the destination once and is filling it
has a bug rather than a state when the source is too long, and reading a returned count back
against `src.len` at every call is the check this is instead.

**A destination LARGER than the source is a fit**, where Rust's `copy_from_slice` asks for equal
lengths. A buffer sized once and written into in runs is the ordinary caller, and refusing that
would send them back to `copy` for a question they were not asking.

### `copy_exact`

```sysl
copy_exact(dst: []u8, src: []const u8) -> bool
```

The same over bytes, reaching `memmove` through the byte `copy` beside it.

### `ends_with`

```sysl
ends_with[T: Eq](xs: []const T, suffix: []const T) -> bool
```

Whether the slice ends with the given elements.

### `equal`

```sysl
equal[T: Eq](a: []const T, b: []const T) -> bool
```

Whether two slices have the same length and the same elements in the same order.

The length test comes first and is what makes the common refusal cost nothing.

### `fill`

```sysl
fill[T](xs: []T, v: T)
```

Every element set to one value.

### `index_of`

```sysl
index_of[T: Eq](xs: []const T, x: T) -> Option[usize]
```

Where a value first appears, or `None`.

**`Option[usize]` rather than a sentinel index.** A library answering `-1` for absence hands back a
number that indexes when it should not, and `usize` has no negative to spare in any case. The
absence is in the type, where a caller has to look at it.

### `index_of_from`

```sysl
index_of_from[T: Eq](xs: []const T, x: T, from: usize) -> Option[usize]
```

The same search begun part-way along, which is what walks every occurrence without cutting the
slice up: `index_of_from(xs, x, k + 1)` is the next one after `k`.

**`from` names a place to START, and the answer is an index into the WHOLE slice** -- so it is
`index_of(xs[from..], x)` with `from` added back on. `sysl.text`'s `Search` reads its own `_from`
members the same way, and this is the pair that made the rule worth stating in both places.

**A `from` past the end answers `None` rather than trapping**, which is what makes the `k + 1`
above safe to write at the last element without a bound test of its own.

### `is_aligned`

```sysl
is_aligned[T](p: *T, alignment: usize) -> bool
```

Whether an address is already a multiple of `alignment`.

The question `align_up` answers by construction, asked on its own -- for an assertion in a binding's
raw layer, or to find out whether over-sizing the storage was needed at all. **A power of two is
assumed here too**, though this one is a remainder rather than a mask, so a value that is not one
answers a real question about divisibility rather than nonsense.

### `is_sorted`

```sysl
is_sorted[T: Ord](xs: []const T) -> bool
```

Whether the slice is already in order. Worth having on its own, and it is also the sorts' own
postcondition, which is what the tests assert against.

### `is_sorted_by`

```sysl
is_sorted_by[T](xs: []const T, lt: (T, T) -> bool) -> bool
```

### `last_index_of`

```sysl
last_index_of[T: Eq](xs: []const T, x: T) -> Option[usize]
```

Where a value last appears, or `None`. Walks down from the end, so it stops at the first match
rather than scanning the whole slice to keep the latest one.

### `last_index_of_from`

```sysl
last_index_of_from[T: Eq](xs: []const T, x: T, from: usize) -> Option[usize]
```

The last occurrence at or after `from` -- the backward search over the same suffix
`index_of_from` takes, rather than a search running downwards from `from`. So the two agree on
which elements are being looked at and disagree only on which end they answer from, and a `from`
past the end answers `None` from either.

### `max_index`

```sysl
max_index[T: Ord](xs: []const T) -> Option[usize]
```

Where the largest element is, or `None`. Ties go to the first, as above -- written `xs[best] <
xs[i]` rather than `xs[i] > xs[best]` so that an equal element does not displace the one already
held.

### `min_index`

```sysl
min_index[T: Ord](xs: []const T) -> Option[usize]
```

Where the smallest element is, or `None` for an empty slice.

**The index rather than the value**, because the index answers both questions: an element is one
subscript away, and a caller that wanted to *modify* the extreme element could not have got there
from a copy. Ties go to the **first**, which is the same choice `min` makes and for the same
reason: for a type whose equality does not mean identity, which of two indistinguishable values
comes back is something a caller can observe.

### `reverse`

```sysl
reverse[T](xs: []T)
```

The elements in the opposite order, in place.

Walks from both ends toward the middle, so each element is written once and an odd middle element
is left alone rather than swapped with itself.

### `sort`

```sysl
sort[T: Ord](xs: []T)
```

The slice in order, in place, using no extra storage.

**Introsort**: quicksort for the general case, insertion sort for the small one, and heapsort once
the recursion has gone deeper than a well-behaved input ever would. The last of those is what holds
the worst case at O(n log n) -- a plain quicksort degrades to O(n²) on input chosen to defeat its
pivot, and a library sort must not have an input that makes it quadratic.

**Equal elements may come back in any order.** That is what "unstable" means and it is only
observable for a type whose equality does not mean identity -- a record ordered on one field. A
caller who cares wants `sort_stable`, which says so in its name.

### `sort_by`

```sysl
sort_by[T](xs: []T, lt: (T, T) -> bool)
```

### `sort_stable`

```sysl
sort_stable[T: Ord](xs: []T, scratch: []T) -> bool
```

The slice in order, with equal elements left in the order they were given, merged through storage
the caller supplies.

Answers `false` and changes nothing when the scratch is shorter than the slice, which is the one
way it can refuse. `scratch` needs `xs.len` elements; anything beyond that is ignored.

**Bottom-up rather than recursive.** The passes double in width -- 1, 2, 4, 8 -- merging adjacent
runs, so there is no recursion and the stack does not grow at all. A top-down merge sort would add
O(log n) frames for the same result.

### `sort_stable_by`

```sysl
sort_stable_by[T](xs: []T, scratch: []T, lt: (T, T) -> bool) -> bool
```

### `starts_with`

```sysl
starts_with[T: Eq](xs: []const T, prefix: []const T) -> bool
```

Whether the slice begins with the given elements. An empty prefix is a prefix of everything, which
falls out of the loop rather than being decided here.

### `swap`

```sysl
swap[T](xs: []T, i: usize, j: usize)
```

Two elements exchanged, in place.

Out-of-range indices trap, as any other subscript does: this is an operation on storage the caller
named, and inventing an answer for an index that is not there would hide the caller's own mistake.
