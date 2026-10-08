---
title: sysl.container
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.container
summary: "Five containers, each with a shape a slice and a `Buf` cannot give you."
---

`Map` and `Set` over one flat probe table, `Deque` for a queue cheap at both ends, `Heap` for a
priority queue, and `List` — an immutable singly linked list that shares its tail. Every one of
them owns its elements and grows, so every one of them needs an allocator; the fixed-capacity
complement is `sysl.container.ring`, which needs none.

**A module rather than five**, because what a program wants from here is a data structure and the
choice between them is the interesting part. Each file's own header says what its structure costs
and when to reach for it instead of its neighbour.

## Index

[`deque`](#deque) [`deque_with_capacity`](#deque_with_capacity) [`difference`](#difference) [`heap`](#heap) [`heap_of`](#heap_of) [`heap_with_capacity`](#heap_with_capacity) [`intersection`](#intersection) [`is_subset`](#is_subset) [`list`](#list) [`list_of`](#list_of) [`map`](#map) [`map_with_capacity`](#map_with_capacity) [`set`](#set) [`set_of`](#set_of) [`set_with_capacity`](#set_with_capacity) [`union`](#union) [`Cursor`](#cursor) [`Deque`](#deque-1) [`DequeCursor`](#dequecursor) [`Heap`](#heap-1) [`List`](#list-1) [`ListCursor`](#listcursor) [`Map`](#map-1) [`Set`](#set-1) [`SetCursor`](#setcursor) [`Slot`](#slot) [Index for Deque[T]](#index-for-dequet) [IndexSet for Deque[T]](#indexset-for-dequet) [Iterate for Cursor[K, V]](#iterate-for-cursork-v) [Iterate for DequeCursor[T]](#iterate-for-dequecursort) [Iterate for ListCursor[T]](#iterate-for-listcursort) [Iterate for SetCursor[K]](#iterate-for-setcursork) [Walk for Deque[T]](#walk-for-dequet) [Walk for List[T]](#walk-for-listt) [Walk for Map[K, V]](#walk-for-mapk-v) [Walk for Set[K]](#walk-for-setk)

## Functions

### `deque`

```sysl
deque[T]() -> Deque[T]
```

### `deque_with_capacity`

```sysl
deque_with_capacity[T](n: usize, fill: T) -> Deque[T]
```

A deque with room for `n` elements already under it. Every slot starts as `None`, so `fill` is
not stored anywhere; the parameter stays because callers write it, and because it is what lets a
call say the element type without brackets.

The storage is rounded **up** to a power of two, because the mask every index goes through is only
correct for those lengths.

### `difference`

```sysl
difference[K: Hash + Eq](a: Set[K], b: Set[K]) -> Set[K]
```

The keys in the first set and not the second. Not symmetric, so it walks `a` whatever the sizes.

### `heap`

```sysl
heap[T: Ord]() -> Heap[T]
```

### `heap_of`

```sysl
heap_of[T: Ord](xs: []const T) -> Heap[T]
```

Every element of a slice heaped at once.

**This is linear where pushing them one at a time is not**, and the difference is the reason it is
worth having rather than a convenience: sifting down from the last parent to the root touches each
element once at a depth that halves as it rises, which comes to O(n), while n pushes come to
O(n log n). The elements go in unordered and the heap property is established afterwards.

### `heap_with_capacity`

```sysl
heap_with_capacity[T: Ord](n: usize, fill: T) -> Heap[T]
```

A heap whose storage is already big enough for `n` elements. The fill is written for the reason
`buf_with_capacity`'s is: a generic `T` has no zero, and none of these slots is read.

### `intersection`

```sysl
intersection[K: Hash + Eq](a: Set[K], b: Set[K]) -> Set[K]
```

The keys in both sets. **The smaller set is the one walked**, because every key walked costs a
lookup in the other and the result cannot be bigger than the smaller input -- so walking the
larger would ask more questions to reach the same answer.

### `is_subset`

```sysl
is_subset[K: Hash + Eq](a: Set[K], b: Set[K]) -> bool
```

Whether every key of the first set is in the second. A set cannot be a subset of a smaller one, so
the count settles most calls before a single lookup is made.

### `list`

```sysl
list[T]() -> List[T]
```

The empty list.

### `list_of`

```sysl
list_of[T](xs: []const T) -> List[T]
```

A list of a slice's elements, in the same order.

The slice is walked **backwards**, because a list is built by putting things on the front: walking
forwards would produce the reverse and need a second pass to correct.

### `map`

```sysl
map[K: Hash + Eq, V]() -> Map[K, V]
```

An empty map, which holds **no table at all** until something is put in it.

**An empty map allocates one small cell and no table**, which matters far more than it looks: a
map built inside a loop, held in a struct that is usually empty, or made and dropped on a branch
that turns out not to be taken would otherwise pay for a table of slots every time, and no caller
can see that it is paying. The cell is the `MapCore` every name for the map shares -- three words,
whatever the key and value. The first `put` finds the table over its load -- nothing is over
nothing -- and rehashes to `initial_slots` before it places anything, which is the path `put`
already took for a table that had filled up. So there is no separate "grow from empty" case to get
wrong.

Reach for `map_with_capacity` where entries are known to be coming: a caller who names a size is
saying so, and gets its table immediately.

**The annotation on `core` is required and is not noise.** `MapCore`'s parameters would have to
come from the constructor's arguments while the empty table waits to hear what it is a table of,
and neither can go first.

### `map_with_capacity`

```sysl
map_with_capacity[K: Hash + Eq, V](n: usize) -> Map[K, V]
```

A map whose table is already big enough for `n` entries without rehashing, for a caller that knows
roughly how many are coming. The table is sized to twice `n` because the map rehashes at three
quarters full, so a table of exactly `n` slots would grow before the `n`th entry arrived.

### `set`

```sysl
set[K: Hash + Eq]() -> Set[K]
```

### `set_of`

```sysl
set_of[K: Hash + Eq](xs: []const K) -> Set[K]
```

Every key of a slice, with duplicates collapsed. The shape that makes a set worth reaching for in
one line rather than three.

### `set_with_capacity`

```sysl
set_with_capacity[K: Hash + Eq](n: usize) -> Set[K]
```

### `union`

```sysl
union[K: Hash + Eq](a: Set[K], b: Set[K]) -> Set[K]
```

The keys in either set. Sized for the larger of the two, since that is the least it can come to.

## Types

### `Cursor`

```sysl
struct Cursor[K, V]
    cell: []Slot[K, V]
    at: usize
```

A position in a table: which slot to look at next.

It takes **no bounds**, which is the point of writing them on the declarations that need them:
walking a table hashes nothing and compares nothing, so a cursor asks nothing of a key.

### `Deque`

```sysl
struct Deque[T]
    private core: &DequeCore[T]
```

A sequence that grows and that is cheap to take from at **either** end.

**The reason this exists beside `Buf` is one line of cost.** A queue written over a growable
sequence takes from the front with `remove(0)`, which shifts every remaining element down one --
so a loop that pushes n things and pops them all is quadratic, and reads as though it were linear.
That is the shape of every breadth-first walk, every work list and every scheduler run queue, and
it is the one place the library was quietly teaching an accident.

**The elements live in a ring**: `head` says where the first one is, `count` says how many follow,
and both ends wrap around the end of the storage rather than moving anything. Taking from the
front advances `head` and touches nothing else; adding to the front steps it backwards into the
space that leaves. Neither end shifts, which is the whole point.

**A slot the ring is not using holds `None`**, which holds nothing. That is what lets the storage
be made before there is anything to put in it, and what makes taking an element out let go of it
there and then: the slot it leaves is written `None`, so a destructor behind the element runs when
the deque gives it up rather than when the storage goes. `Buf` keeps its spare slots empty a
different way -- a buffer's live slots are a *prefix*, which `slot_storage` and `slot_vacate`
describe -- and a ring's are not, since `push_front` writes the far end of the storage first.

**The storage length is always a power of two**, because it starts at eight and only ever doubles,
so wrapping is a mask rather than a division. That invariant is worth stating because it is what
every index calculation here assumes -- there is no `%` anywhere below, and a growth path that
produced a non-power-of-two length would break all of them at once rather than visibly.

**A `Deque` is a reference to its ring, not the ring.** Assigning one, passing it, capturing it in a
closure or reading it out of another container gives a second name for the same deque: a
`push_front` through either is seen by both, a growth included. A destructor behind an element runs
when the element is taken out, or when the last name for the deque goes.

The bounds-checked members panic rather than answering an `Option`, which is the bargain `Buf`
makes for the same reason: an index past the end is a mistake in the program. `pop_front` and
`pop_back` answer an `Option`, because taking from an empty sequence is a question a caller asks
on purpose.

| Member | Signature | Description |
|---|---|---|
| `head` | `head -> usize` | Where the first element sits in the storage. |
| `count` | `count -> usize` | How many elements there are -- `len()` under the name the field had. |
| `len` | `len(self) -> usize` |  |
| `cap` | `cap(self) -> usize` |  |
| `is_empty` | `is_empty(self) -> bool` |  |
| `at` | `at(self, i: usize) -> T` |  |
| `set` | `set(self, i: usize, v: T)` |  |
| `first` | `first(self) -> Option[T]` |  |
| `last` | `last(self) -> Option[T]` |  |
| `push_back` | `push_back(self, v: T)` |  |
| `push_front` | `push_front(self, v: T)` | The end `Buf` has no answer for at all. |
| `pop_front` | `pop_front(self) -> Option[T]` |  |
| `pop_back` | `pop_back(self) -> Option[T]` |  |
| `clear` | `clear(self)` | The elements dropped and the head put back at the start. |
| `copy` | `copy(self) -> Deque[T]` | A second deque holding the same elements in the same order, the front put back at the start of its storage -- the one way to get a deque that is not this one, every other way of handing a `Deque` on handing on this one. |

### `DequeCursor`

```sysl
struct DequeCursor[T]
    private elems: []Option[T]
    head: usize
    count: usize
    at: usize
```

A walk from the front to the back. It holds the storage rather than the deque, so pushing onto the
deque while a cursor is live leaves the cursor reading the ring it started on; an element the deque
gives up meanwhile is a slot that reads `None`, and the walk ends there.

### `Heap`

```sysl
struct Heap[T: Ord]
    elems: Buf[T]
```

A priority queue: the smallest element is always the next one out.

**It is a MIN-heap, and that is a decision rather than a coin toss.** The three things a priority
queue is actually reached for -- a scheduler ordered by deadline, a shortest-path search ordered
by distance, an event loop ordered by time -- all want the *smallest* first, and all three would
otherwise have to negate their keys to get it. A max-heap is a min-heap over a reversed
comparison, and reversing `Ord` needs a wrapper type the caller would have to declare, so
whichever way round this goes one set of callers pays. It goes the way the common cases want.

**The shape is a binary heap laid out in a `Buf`**, with no tree and no pointers: the children of
the element at `i` are at `2i + 1` and `2i + 2`, and its parent is at `(i - 1) / 2`. So the whole
structure is arithmetic over a growable sequence the library already has, which is why this module
is short -- `Buf` does the growing and this does the ordering.

The heap property is that no element is smaller than its parent. That is weaker than sorted, which
is exactly why `push` and `pop` are logarithmic rather than linear: only one path from a leaf to
the root is ever touched.

**Walking a heap is deliberately not offered.** The storage order is not the priority order --
only the *first* element is guaranteed to be anything in particular -- so a cursor over it would
hand back elements in an order that looks meaningful and is not. Draining with `pop` is how a heap
is read in order, and it is the only way that answers truthfully.

| Member | Signature | Description |
|---|---|---|
| `len` | `len(self) -> usize` |  |
| `is_empty` | `is_empty(self) -> bool` |  |
| `peek` | `peek(self) -> Option[T]` | The smallest element, left where it is. |
| `push` | `push(self, v: T)` | An element added, then carried up past every parent it is smaller than. |
| `pop` | `pop(self) -> Option[T]` | The smallest element removed and returned. |
| `clear` | `clear(self)` |  |

### `List`

```sysl
struct List[T]
    private cell: Option[&Cell[T]]
    private count: usize
```

A list, which is a first cell or nothing, and the number of cells after it.

**The length is carried rather than counted, and immutability is what makes that free.** A cell's
tail never changes, so a length worked out when the list was built stays true forever -- there is
no operation that could invalidate it. A mutable list would have to walk to answer `len`, or
maintain the count against every mutation; this pays one addition at `prepend` and answers in
constant time for the rest of the list's life.

| Member | Signature | Description |
|---|---|---|
| `len` | `len(self) -> usize` |  |
| `is_empty` | `is_empty(self) -> bool` |  |
| `first` | `first(self) -> Option[T]` | The first element, or `None`. |
| `rest` | `rest(self) -> Option[List[T]]` | Everything after the first element, or `None` where there is no first element. |
| `uncons` | `uncons(self) -> Option[(T, List[T])]` | The first element and the rest, in one question. |
| `prepend` | `prepend(self, v: T) -> List[T]` | A new list with `v` on the front, sharing the whole of this one. |
| `reverse` | `reverse(self) -> List[T]` | The same elements in the opposite order, which costs a cell each because nothing of the original can be shared: a reversed list's tails are not tails of the original. |

### `ListCursor`

```sysl
struct ListCursor[T]
    private at: Option[&Cell[T]]
```

A position in a list, which is just the cell to look at next -- there is nothing else to remember,
since a cell knows the whole of the rest of the list.

It holds the cell rather than the list, and holding it is what keeps it alive: a cursor part-way
through a list that nothing else refers to still owns the cells it has not reached.

### `Map`

```sysl
struct Map[K: Hash + Eq, V]
    private core: &MapCore[K, V]
```

A mapping from keys to values, with a key found in constant time on average.

**A `Map` is a reference to one table, like Scala's `mutable.HashMap`.** Assigning it, passing it,
capturing it in a closure or reading it out of another container gives a second name for the same
map, so a `put` through either name is seen through both:

```sysl
var m: Map[int, int] = map()
var n = m

n.put(1, 10)
print(m.len(), m.get(1).unwrap_or(0))      // 1 10
```

**It has no zero value**, the table it names having to exist, so a declaration says where its map
comes from: `var m: Map[string, int] = map()`.

What a `Map` holds is one counted reference to a `MapCore`, which carries the table and its two
counts. Holding the counts beside the table is what makes the sharing whole: a map whose copies
each carried their own counts over one shared table would let a `put` through one copy write an
entry the other's count never learned of.

| Member | Signature | Description |
|---|---|---|
| `cell` | `cell -> []Slot[K, V]` | The table itself, every slot of it, for a reader that wants to look rather than ask. |
| `count` | `count -> usize` | How many entries are live. |
| `dead` | `dead -> usize` | How many tombstones the table is carrying. |
| `len` | `len(self) -> usize` |  |
| `is_empty` | `is_empty(self) -> bool` |  |
| `capacity` | `capacity(self) -> usize` | How many slots the table has, which is what the load factor is measured against. |
| `get` | `get(self, k: K) -> Option[V]` |  |
| `get_or` | `get_or(self, k: K, default: V) -> V` | The value a key names, or the one the caller supplies where it names none. |
| `has` | `has(self, k: K) -> bool` |  |
| `put` | `put(self, k: K, v: V)` | A key given a value: the entry it already had takes the new one, or a fresh entry is placed. |
| `remove` | `remove(self, k: K) -> bool` | A key taken out, answering whether it was there. |
| `clear` | `clear(self)` | Every entry dropped and the table let go entirely; see `MapCore.clear`. |
| `copy` | `copy(self) -> Map[K, V]` | A second map holding the same entries, its table sized to them -- the one way to get a map that is not this one, every other way of handing a `Map` on handing on this one. |

### `Set`

```sysl
struct Set[K: Hash + Eq]
    entries: Map[K, bool]
```

A set of keys, with membership answered in constant time on average.

**It is a `Map` with nothing in the value column**, and that is a deliberate choice about where the
duplication goes rather than laziness. A bespoke table would save the value slot -- for a
`Set[int]` that is eight bytes of padding per slot against twenty-four -- and would cost a second
copy of the probing, the tombstone rule and the rehash sizing, which is the part of a hash table
that is actually hard to get right. Two copies of that is two places for it to be wrong, and only
one of them would have the tests.

**The representation is behind the API**, which is what makes the choice reversible: `add`, `has`
and `remove` say nothing about how the table is laid out, so a bespoke table can replace this one
later without moving anything a program can name.

The value stored is `true` throughout and is never read. What carries the information is whether
the key is in the map at all.

| Member | Signature | Description |
|---|---|---|
| `len` | `len(self) -> usize` |  |
| `is_empty` | `is_empty(self) -> bool` |  |
| `capacity` | `capacity(self) -> usize` |  |
| `has` | `has(self, k: K) -> bool` |  |
| `add` | `add(self, k: K) -> bool` | A key put in, answering whether it was **new**. |
| `remove` | `remove(self, k: K) -> bool` | A key taken out, answering whether it was there. |
| `clear` | `clear(self)` |  |

### `SetCursor`

```sysl
struct SetCursor[K]
    inner: Cursor[K, bool]
```

A position in a set, which is a position in the map underneath it with the unread value dropped
on the way out.

### `Slot`

```sysl
enum Slot[K, V]
    Empty
    Dead
    Live(hash: u64, key: K, value: V)
```

One slot of the table, which is one of three things: never used, used and vacated, or holding an
entry.

**`Empty` and `Dead` are different and the difference is the whole probing scheme.** A probe stops
at `Empty`, because a key that hashed here would have been placed at the first free slot it found
and so cannot be further along. It does **not** stop at `Dead`: something was here when the key it
is looking for was inserted, so the chain it belongs to may continue past it.

`Live` carries the hash beside the key so that a probe can reject a slot on an integer comparison
and `rehash` can re-place an entry without asking the key to mix itself down again.

| Member | Signature | Description |
|---|---|---|
| `value` | `value(self) -> Option[V]` | What a slot holds, for a caller that has an index and wants the value at it. |
| `is_live` | `is_live(self) -> bool` |  |

## Implementations

### Index for Deque[T]

```sysl
impl[T] Index[usize, T] for Deque[T]
```

Subscripting reaches the bounds-checked members rather than the storage, so `d[i]` counts from the
**front** of the deque and cannot read a slot the ring is not currently using.

### IndexSet for Deque[T]

```sysl
impl[T] IndexSet[usize, T] for Deque[T]
```

### Iterate for Cursor[K, V]

```sysl
impl[K, V] Iterate for Cursor[K, V]
```

A pair per entry rather than a reference to one, because there is no entry to refer to -- an entry
lives inside the table, and a reference into storage that `rehash` replaces is the one thing this
design exists to make impossible.

### Iterate for DequeCursor[T]

```sysl
impl[T] Iterate for DequeCursor[T]
```

### Iterate for ListCursor[T]

```sysl
impl[T] Iterate for ListCursor[T]
```

### Iterate for SetCursor[K]

```sysl
impl[K] Iterate for SetCursor[K]
```

### Walk for Deque[T]

```sysl
impl[T] Walk for Deque[T]
```

`for x in d` walks a deque from the front to the back, through the cursor `walk` answers.

### Walk for List[T]

```sysl
impl[T] Walk for List[T]
```

`for x in l` walks a list from its head, through the cursor `walk` answers.

### Walk for Map[K, V]

```sysl
impl[K: Hash + Eq, V] Walk for Map[K, V]
```

`for (k, v) in m` walks the entries, a pair apiece, through the cursor `walk` answers.

### Walk for Set[K]

```sysl
impl[K: Hash + Eq] Walk for Set[K]
```

`for k in s` walks the keys, in the map's order, through the cursor `walk` answers.
