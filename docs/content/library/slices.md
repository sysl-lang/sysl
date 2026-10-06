---
title: The slices module
summary: "`sysl.slices` — the operations over a built-in slice: searching, comparing, reversing, a copy that is right where the two slices overlap, two sorts that neither of them allocates, a binary search that answers where a missing value belongs, and the pointer a C binding hands across."
weight: 55
---

**Every declaration in `sysl.slices`, with its signature:** [the generated API page](/api/sysl-slices/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.slices` is what a program does *to* a `[]T` once it has one. Everything here is a **free
function** rather than a member of anything, which is the same call [`sysl.math`](/library/math/)
made for `min`: a built-in slice is indexed by the compiler walking to an address, so none of this
goes through a trait, and what each function needs is written in its own bound.

**Nothing here allocates and nothing here needs a capability.** That is deliberate and it is load
bearing — this is the module a C binding reaches into, and it is reachable from a target with no
allocator and no operating system. **The questions answered by calling something the caller wrote —
`map`, `filter`, `fold` — are [`sysl.seq`](/library/seq/) for exactly that reason**: every one of
them allocates, so they are a module a freestanding program can leave out.

```sysl
import sysl.slices.{sort, is_sorted, binary_search}

var xs = [5, 3, 8, 1, 9, 2]

sort(xs)

print(xs)
print(is_sorted(xs))
```

```output
[1, 2, 3, 5, 8, 9]
true
```

## Looking things up

`index_of` and `last_index_of` answer an `Option[usize]` rather than a sentinel — a library answering
`-1` hands back a number that indexes when it should not, and `usize` has no negative to spare in any
case.

```sysl
import sysl.slices.{index_of, last_index_of, contains, min_index, max_index}

var xs = [1, 2, 3, 2, 5]

print(index_of(xs, 2).unwrap(), last_index_of(xs, 2).unwrap())
print(contains(xs, 5), contains(xs, 4))
print(min_index(xs).unwrap(), max_index(xs).unwrap())
```

```output
1 3
true false
0 4
```

The extremes answer an **index** rather than a value, because the index answers both questions: an
element is one subscript away, and a caller that wanted to *modify* the extreme element could not have
got there from a copy. Ties go to the first, which is the choice `min` makes and for the same reason.

Both searches have a `_from` form, and it reads the same way `sysl.text`'s does: `from` names a place
to **start**, so the search is over `xs[from..]` and the answer is an index into the whole slice.
Walking every occurrence is therefore `index_of_from(xs, x, k + 1)` from the last one, with nothing to
slice and nothing to add back on — and the **backward** form looks at that same suffix, answering with
the last occurrence in it rather than running downwards from `from`.

```sysl
import sysl.slices.{index_of, index_of_from, last_index_of_from}

var xs = [1, 2, 3, 2, 5, 2]
var at = index_of(xs, 2)

while at.is_some()
    val k = at.unwrap()

    print(k)
    at = index_of_from(xs, 2, k + 1)

print(last_index_of_from(xs, 2, 4).unwrap())
print(index_of_from(xs, 2, 99).is_none())
```

```output
1
3
5
5
true
```

**An offset past the end answers `None` rather than trapping**, which is what makes the loop above
safe: at the last element `k + 1` is the length, and beyond it is anything at all.

## Comparing and rearranging

```sysl
import sysl.slices.{equal, starts_with, ends_with, reverse, fill, swap}

var a = [1, 2, 3, 4]

print(equal(a, [1, 2, 3, 4]))
print(starts_with(a, [1, 2]), ends_with(a, [3, 4]))

reverse(a)
print(a)

swap(a, 0, 3)
print(a)

fill(a, 7)
print(a)
```

```output
true
true true
[4, 3, 2, 1]
[1, 3, 2, 4]
[7, 7, 7, 7]
```

**`equal` is not the only way to ask that any more.** A slice is
[`Eq` when its elements are](/library/core/#a-slice-or-an-array-of-anything-equatable), so `a == b`
answers the same question, and `assert_eq` takes two slices and prints both when they disagree.

**And ordering came with it.** A slice is
[`Ord` when its elements are](/library/core/#a-slice-or-an-array-of-anything-equatable) — first
differing element deciding, and a prefix ordering before what extends it — so `a[..] < b[..]` is a
question this module never had a function for:

```sysl
var a = [1, 2, 3]
var b = [1, 2]

print(a[..] < b[..], b[..] < a[..])
```

```output
false true
```

**A slice still has no `Hash`, and that is a decision rather than a gap.** A hash is a promise about
a key and a view's storage stays writable by whoever owns it, so a slice used as a key can change
under the table. An array copies, so it hashes; [`core`](/library/core/#hashing) has the reasoning.

What `equal` still has is its **signature and its module**: it takes `[]const T` and it lives here,
under `@no_alloc`, reachable from a freestanding target — so a caller holding two slices and wanting
a `bool` loses nothing by keeping it, and a binding that must not acquire an allocator has it
whatever the core module gains. `starts_with` and `ends_with` are the same story one step along:
both are `equal` over a sub-slice, and both stay for the same reason.

## Moving elements from one slice to another

`copy` writes as much of the source as fits in the destination and answers how much that was.
`copy_exact` writes the whole of the source or nothing at all, and answers whether it fitted.

```sysl
import sysl.slices.{copy, copy_exact}

var dst = [0, 0, 0, 0]
var tight = [0, 0]
var room = [0, 0, 0, 0]
var small = [0, 0]
var src = [1, 2, 3]

print(copy(dst, src), dst)
print(copy(tight, src), tight)

print(copy_exact(room, src), room)
print(copy_exact(small, src), small)
```

```output
3 [1, 2, 3, 0]
2 [1, 2]
true [1, 2, 3, 0]
false [0, 0]
```

**The short answer is the point rather than a failure.** A caller streaming into a buffer writes what
fits and asks again; one that has already sized the destination wants `copy_exact`, where a mismatch
is a mistake in the program rather than a state to handle. A destination *larger* than the source is
a fit — a buffer sized once and written into in runs is the ordinary caller.

**The two slices may be views of one array**, and the copy is right in both directions. That is
`memmove`'s guarantee rather than `memcpy`'s, and it is the one worth making: two views into one
array is an ordinary thing to have.

```sysl
import sysl.slices.copy

var a = [1, 2, 3, 4, 5]
var b = [1, 2, 3, 4, 5]

copy(a[0..<3], a[2..<5])
copy(b[2..<5], b[0..<3])

print(a)
print(b)
```

```output
[3, 4, 5, 4, 5]
[1, 2, 1, 2, 3]
```

### Why there are two declarations of each

`copy` is [overloaded](/reference/declarations/#overloading): a generic declaration over any `[]T`,
and one taking `[]u8`. A call over bytes reaches the second, which hands the move to the platform's
`memmove`; every other element type reaches the first, which assigns element by element.

**That is not an optimization detail — it is what makes the generic one correct.** An element
carrying a reference count needs the retain and the release an assignment does. A bitwise move over a
`[]string` leaks what the destination held and hands the source's boxes out twice, and it *prints the
right answer* while doing so: the fault is a use-after-free that arrives later, if it arrives at all
where anybody is looking. So the fast path is exactly as wide as the type where it is sound.

The byte declaration earns its place because the loop is genuinely a byte at a time. The destination's
bounds check survives inside the loop body, which stops the optimizer recognising the idiom, so what
the generic form compiles to is one load and one store per element — the same reason
[`sysl.io`](/library/io/)'s line reader reaches `memchr` rather than scanning bytes itself.

**On a freestanding target it walks the elements instead, because there is nothing to call.** A bare
board is linked with no C library, so a `memmove` reached from here would be an undefined symbol —
and this is a module a board reaches, `sysl.harness` being written for exactly that. The declaration
and what it promises are the same on every target; only what it costs differs, which is what keeps
this module usable where the operating system is not.

## Two sorts, and neither of them allocates

`sort` is **unstable**, works in the slice it was given, and uses no extra storage. It is an
introsort: insertion sort below sixteen elements, a median-of-three quicksort above that, and
heapsort once the recursion has gone deeper than a well-behaved input ever would — which is what holds
the worst case at O(n log n) rather than letting a chosen input make it quadratic.

`sort_stable` keeps equal elements in the order they arrived, and merges through **scratch the caller
supplies**. That is the honest shape: a stable sort needs somewhere to merge into, and a library that
allocated on your behalf would be unusable on the targets this module exists to stay available on. It
answers `false` if the scratch is shorter than the slice.

```sysl
import sysl.slices.sort_stable

var xs = [(2, 1), (1, 1), (2, 2), (1, 2)]
var scratch = [(0, 0); 4]

print(sort_stable(xs, scratch))
print(xs)
```

```output
true
[(1, 1), (1, 2), (2, 1), (2, 2)]
```

Both come in a `_by` form taking a comparison, which is where the work actually is — the `Ord` forms
are one line handing it `<`. The comparison is a [bare arrow](/reference/types/#function-types), so it is
monomorphized and inlined rather than boxed, and the pair costs nothing over one function.

```sysl
import sysl.slices.sort_by

var xs = [1, 5, 3, 2]

sort_by(xs, (a, b) -> b < a)

print(xs)
```

```output
[5, 3, 2, 1]
```

The pair is `sort_by` and `sort_stable_by`, and `is_sorted_by` asks the same question of a slice
somebody else ordered — which is what an assertion in a test wants, and what a caller checks before
paying for a sort it may not need:

```sysl
import sysl.slices.{is_sorted, is_sorted_by}

var xs = [5, 3, 2, 1]

print(is_sorted(xs), is_sorted_by(xs, (a, b) -> b < a))
```

```output
false true
```

**Which one you want is a real question.** `sort` is faster and says nothing about equal elements;
`sort_stable` promises their order survives. That only matters for a type whose equality does not mean
identity — a record ordered on one field — and when it matters, it matters a great deal.

### Why these are written in sysl rather than calling qsort

It is the first question anybody arriving from C asks, and the answer is not that the C library is
slow.

The binding is perfectly writable, in a dozen lines, and everything it needs is in the language: the
address of a per-instantiation comparison, the address of a slice's storage, and the size of an
element. [The foreign interface](/reference/ffi/) writes exactly this one out.

What decides it is what this module **promises**. `sysl.slices` requires no capability, which is a
promise made to every machine sysl builds for — and several of those are freestanding, where there is
no C library to call at all and `qsort` is an undefined symbol at the end of somebody's link. On a
hosted machine the same promise fails more quietly: glibc's `qsort` sorts by merging into a temporary
buffer and calls `malloc` to obtain one, while Darwin's sorts in place. One source text therefore
allocates on one platform and not on the other.

**Neither fact is visible to the compiler**, because what is behind an `extern` is behind it. A
module whose whole selling point is that it needs nothing cannot offer a function whose needs are
unknowable.

Two smaller reasons stand behind that one. There is no portable **stable** sort in a C library —
`mergesort` and `heapsort` are BSD extensions, present on macOS and absent from glibc, and ISO C has
only the unstable `qsort` — so a binding could have replaced `sort` and never `sort_stable`. And a
bound comparison is an indirect call per step, where a monomorphized `[T: Ord]` body inlines the
same comparison.

## Searching a sorted slice

`binary_search` answers a **pair**: whether the value was found, and the index it is at *or would be
inserted at*.

```sysl
import sysl.slices.binary_search

var xs = [1, 3, 5, 7, 9]

val (found, at) = binary_search(xs, 5)
val (missing, where) = binary_search(xs, 4)

print(found, at)
print(missing, where)
```

```output
true 2
false 2
```

The insertion point is wanted on a miss and is the expensive half of the answer, so throwing it away
would mean computing it twice. An `Option[usize]` does exactly that; a `Result[usize, usize]` — Rust's
spelling — keeps it but calls a miss a *failure*, which it is not.

Where several elements compare equal, the answer is the index of the **first**. That makes it a
function of the values rather than of how the search happened to land, which is what lets two searches
of one slice be compared.

The slice must already be sorted by the same order you are searching with. Nothing checks it — that
would cost a linear scan on every search and defeat the point — and the answer for an unsorted slice
is unspecified rather than wrong in some particular way.

`binary_search_by` takes the comparison, exactly as the sorts' `_by` forms do, and it is the one to
reach for on a slice ordered by anything but `<` — searching with an order the slice was not sorted
by is the mistake the paragraph above describes, and it is easiest to make when the sort took a
comparison and the search did not.

## Handing a slice to C

Every binding to a C library needs a pointer to a slice's first element, because C's convention is a
pointer beside a length. `as_ptr` and `as_mut_ptr` are that, and they exist here because every binding
was otherwise writing them.

```sysl
import sysl.slices.as_ptr

var xs = [10, 20, 30]

print(*as_ptr(xs))
print(as_ptr(xs[0..<0]) == null)
```

```output
10
true
```

**An empty slice answers `null`.** An empty slice is a real input — a length of zero is a loop that
does not run — but `&xs[0]` on one is an out-of-bounds index and traps rather than yielding the
pointer C would have accepted.

The cost, said plainly: ISO C leaves passing a null pointer to `memcpy` undefined even at a length of
zero, and a small number of libraries assert non-null on entry. Every real interface takes the pair
`(null, 0)`, and the length is what says not to look — but a binding whose C asserts otherwise must
pass storage it owns rather than an empty slice.

## Placing a C struct in storage you supplied

A binding that hands C a slice to keep its state in has a second problem after the pointer: a `[]u8`
promises nothing about its own address, and the struct C is about to read wants its natural
alignment. `align_up` answers the first address at or after a pointer that a given alignment divides,
and `is_aligned` asks whether one already is. Neither needs C — an address read as a `usize` is an
ordinary conversion, and `ptr_cast` reads it back.

```sysl
import sysl.slices.align_up
import sysl.slices.is_aligned
import sysl.slices.as_mut_ptr

var storage: [sizeof(u64) + alignof(u64) - 1]u8 = [0; sizeof(u64) + alignof(u64) - 1]

val state: *u64 = ptr_cast(align_up(as_mut_ptr(storage[..]), alignof(u64)))

*state = 42

print(is_aligned(state, alignof(u64)))
print(*state)
```

```output
true
42
```

The storage is over-sized by `alignment - 1`, which is the most the rounding can ever cost, so the
value fits wherever the slice happens to start. `alignof(T)` is a compile-time constant, so the call
asks for what the type wants rather than repeating a number.

**A pointer that is already aligned is answered unchanged.** On a 64-bit machine storage usually is
aligned already, which is what makes a missing call so easy to ship: it works until the day the
storage does not start on a boundary.

**`alignment` must be a power of two and nothing checks it.** The arithmetic is a mask, and a mask
means something only for a power of two — any other value quietly answers an address that is aligned
to nothing. It is the caller's to get right, exactly as it is in C.
