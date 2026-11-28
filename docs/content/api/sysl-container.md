---
title: sysl.container
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.container
summary: "Six containers, each with a shape a slice and a `Buf` cannot give you."
---

`Map` and `Set` over one flat probe table, `Deque` for a queue cheap at both ends, `Heap` for a
priority queue, `List` — an immutable singly linked list that shares its tail — and `Inline`, a
sequence of a fixed capacity held inside its owner.

**Five of them own their elements and grow, so those five need an allocator**: `Map`, `Set`,
`Deque`, `Heap` and `List`. **`Inline` needs none.** Its slots are part of the value that holds it
and no member of it allocates, so a module that has given the allocator up may import it from here
and build for a machine with no heap; what a program does not call, it does not link. The module
itself cannot say `@no_alloc`, that being a promise about all of it. The other fixed-capacity
containers are `sysl.container.ring`'s, a module that does say it.

**A module rather than six**, because what a program wants from here is a data structure and the
choice between them is the interesting part. Each file's own header says what its structure costs
and when to reach for it instead of its neighbour.

## Index

[`deque`](#deque) [`deque_with_capacity`](#deque_with_capacity) [`difference`](#difference) [`heap`](#heap) [`heap_of`](#heap_of) [`heap_with_capacity`](#heap_with_capacity) [`inline`](#inline) [`intersection`](#intersection) [`is_subset`](#is_subset) [`list`](#list) [`list_of`](#list_of) [`map`](#map) [`map_with_capacity`](#map_with_capacity) [`set`](#set) [`set_of`](#set_of) [`set_with_capacity`](#set_with_capacity) [`union`](#union) [`Cursor`](#cursor) [`Deque`](#deque-1) [`DequeCursor`](#dequecursor) [`Heap`](#heap-1) [`Inline`](#inline-1) [`InlineCursor`](#inlinecursor) [`List`](#list-1) [`ListCursor`](#listcursor) [`Map`](#map-1) [`Set`](#set-1) [`SetCursor`](#setcursor) [`Slot`](#slot) [Index for Deque[T]](#index-for-dequet) [Index for Inline[T, N]](#index-for-inlinet-n) [IndexSet for Deque[T]](#indexset-for-dequet) [IndexSet for Inline[T, N]](#indexset-for-inlinet-n) [Iterate for Cursor[K, V]](#iterate-for-cursork-v) [Iterate for DequeCursor[T]](#iterate-for-dequecursort) [Iterate for InlineCursor[T, N]](#iterate-for-inlinecursort-n) [Iterate for ListCursor[T]](#iterate-for-listcursort) [Iterate for SetCursor[K]](#iterate-for-setcursork) [Walk for Deque[T]](#walk-for-dequet) [Walk for Inline[T, N]](#walk-for-inlinet-n) [Walk for List[T]](#walk-for-listt) [Walk for Map[K, V]](#walk-for-mapk-v) [Walk for Set[K]](#walk-for-setk)

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
const difference[K: Hash + Eq](a: Set[K], b: Set[K]) -> Set[K]
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

### `inline`

```sysl
inline[T, const N: usize]() -> Inline[T, N]
```

An empty buffer of capacity `N`: `inline[int, 8]()`, or `inline()` where the type is known. It
is what makes one for a `T` with no zero value, which a plain declaration cannot.

### `intersection`

```sysl
const intersection[K: Hash + Eq](a: Set[K], b: Set[K]) -> Set[K]
```

The keys in both sets. **The smaller set is the one walked**, because every key walked costs a
lookup in the other and the result cannot be bigger than the smaller input -- so walking the
larger would ask more questions to reach the same answer.

### `is_subset`

```sysl
const is_subset[K: Hash + Eq](a: Set[K], b: Set[K]) -> bool
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
const map[K: Hash + Eq, V]() -> Map[K, V]
```

An empty map, which holds **no table at all** until something is put in it.

**An empty map allocates one small cell and no table**, which matters far more than it looks: a
map built inside a loop, held in a struct that is usually empty, or made and dropped on a branch
that turns out not to be taken would otherwise pay for a table of slots every time, and no caller
can see that it is paying. The cell is the `MapCore` every name for the map shares -- three words,
whatever the key and value. The first `put` makes the `list_room` slots a small map lives in, and
the ninth distinct key turns them into a table.

Reach for `map_with_capacity` where entries are known to be coming: a caller who names a size is
saying so, and gets its storage immediately.

**The annotation on `core` is required and is not noise.** `MapCore`'s parameters would have to
come from the constructor's arguments while the empty table waits to hear what it is a table of,
and neither can go first.

### `map_with_capacity`

```sysl
const map_with_capacity[K: Hash + Eq, V](n: usize) -> Map[K, V]
```

A map whose storage is already big enough for `n` entries without rehashing, for a caller that
knows roughly how many are coming. Up to `list_room` entries that is the list's slots, since so few
would be a list anyway. Past it the map starts as a table, sized to twice `n` because it rehashes
at three quarters full, so a table of exactly `n` slots would grow before the `n`th entry arrived.

### `set`

```sysl
const set[K: Hash + Eq]() -> Set[K]
```

### `set_of`

```sysl
const set_of[K: Hash + Eq](xs: []const K) -> Set[K]
```

Every key of a slice, with duplicates collapsed. The shape that makes a set worth reaching for in
one line rather than three.

### `set_with_capacity`

```sysl
const set_with_capacity[K: Hash + Eq](n: usize) -> Set[K]
```

### `union`

```sysl
const union[K: Hash + Eq](a: Set[K], b: Set[K]) -> Set[K]
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

The bounds-checked members trap rather than answering an `Option`, as `Buf`'s and an array's do,
for the same reason: an index past the end is a mistake in the program. `pop_front` and
`pop_back` answer an `Option`, because taking from an empty sequence is a question a caller asks
on purpose.

| Member | Signature | Description |
|---|---|---|
| `head` | `head -> usize` | Where the first element sits in the storage. |
| `count` | `count -> usize` | How many elements there are -- `len()` under the name the field had. |
| `len` | `len(self) -> usize` |  |
| `cap` | `cap(self) -> usize` |  |
| `is_empty` | `is_empty(self) -> bool` |  |
| `get` | `get[I: Integer](self, i: I) -> Option[T]` | The element `i` places from the front, or `None` where there is none -- `i` at or past the length, or negative. |
| `first` | `first(self) -> Option[T]` |  |
| `last` | `last(self) -> Option[T]` |  |
| `push_back` | `push_back(self, v: T)` |  |
| `push_front` | `push_front(self, v: T)` | The end `Buf` has no answer for at all. |
| `pop_front` | `pop_front(self) -> Option[T]` |  |
| `pop_back` | `pop_back(self) -> Option[T]` |  |
| `clear` | `clear(self)` | The elements dropped and the head put back at the start. |
| `copy` | `copy(self) -> Deque[T]` | A second deque holding the same elements in the same order, the front put back at the start of its storage -- the one way to get a deque that is not this one, every other way of handing a `Deque` on handing on this one. |
| `release` | `release(*self)` | This name's share of the ring given back, and the handle left naming nothing -- the state zeroed storage starts in, where every reader answers as an empty deque does. |

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
| `release` | `release(*self)` | The buffer underneath given back without allocating, as `Buf.release` does -- for a heap in storage the program zeroed itself, which answers as empty until it is assigned. |

### `Inline`

```sysl
struct Inline[T, const N: usize]
    private slots: [N]T
    private live: usize
```

A sequence of **up to `N` values held inside its owner**: the `N` slots are an `[N]T` in the
value itself, the first `len()` of them hold a value and the rest hold nothing. There is no heap
behind it, so a struct that holds one is one piece of storage, wherever that struct lives.

```
struct Leaf[K, const B: usize]
    keys: Inline[K, B]
    kids: Inline[usize, B + 1]

val leaf: Leaf[string, 4] = Leaf(inline(), inline())
```

**It is `Buf` with the storage moved inside and the growth taken away**, and its members have
`Buf`'s names: `push`, `pop`, `insert`, `remove`, `swap_remove`, `truncate`, `clear`, `get`,
`view`, `view_mut`, `b[i]`, and `for x in b`.

**A slot past the length holds no value.** A value is moved into its slot and moved out again, so
a removed element is let go of at the removal, an `Inline[&Handle, 8]` holding three handles keeps
three alive, and when the buffer goes exactly the elements it still holds are let go of, in order.

**A push onto a full buffer stops the program**, as an index past the length does, with a report
naming the capacity; `try_push` and `try_insert` answer instead, handing the value back in `Err`.
This is where it differs from `InlineRing`, whose `push_back` answers a `bool`: a ring is made to
be full, and a full ring is a state its caller meets on every lap, while a buffer that is full
when something pushes is a bound the program got wrong. Ask `is_full()` first, or use the `try_`
form, where being full is expected.

**A fresh one is `inline[T, N]()`.** A plain `var b: Inline[T, N]` is an empty buffer too wherever
`T` has a zero value (an integer, a `string`, a struct of them). Where it has none -- a `&T`, an
enum with no variant at zero -- that declaration is refused (*"has no zero value, so 'b' needs an
initial value"*), and `inline()` is the initial value it needs.

**A copy is its own buffer**, since the storage is part of the value: passing one, returning one
and assigning one copy the `N` slots. `view()` and `view_mut()` are views of the buffer they were
taken from, not copies, and may not outlive it.

| Member | Signature | Description |
|---|---|---|
| `capacity` | `capacity(self) -> usize` | How many values it can hold, which is `N`. |
| `len` | `len(self) -> usize` | How many values are in it. |
| `is_empty` | `is_empty(self) -> bool` |  |
| `is_full` | `is_full(self) -> bool` | Whether the next `push` would stop the program. |
| `get` | `get[I: Integer](self, i: I) -> Option[T]` | The `i`th value, or nothing where there is no such value -- `i` at or past the length, or negative. |
| `first` | `first(self) -> Option[T]` |  |
| `last` | `last(self) -> Option[T]` |  |
| `push` | `push(*self, v: T)` | Adds at the end. |
| `try_push` | `try_push(*self, v: T) -> Result[unit, T]` | Adds at the end, or hands the value back in `Err` where the buffer is full, which is then left as it was. |
| `pop` | `pop(*self) -> Option[T]` | The last value, moved out, its slot left holding nothing. |
| `insert` | `insert(*self, i: usize, v: T)` | Puts `v` at `i`, moving every value from `i` on up one; `i` may be the length, which is a push. |
| `try_insert` | `try_insert(*self, i: usize, v: T) -> Result[unit, T]` | `insert`, except that a full buffer hands the value back in `Err` and is left as it was. |
| `remove` | `remove(*self, i: usize) -> T` | The value at `i`, moved out, every value after it moved down one. |
| `swap_remove` | `swap_remove(*self, i: usize) -> T` | The value at `i`, moved out in constant time by moving the last value into its place. |
| `truncate` | `truncate(*self, n: usize)` | The values from `n` on, let go of now, lowest index first. |
| `clear` | `clear(*self)` | Every value let go of, there and then, lowest index first. |
| `view` | `view(*self) -> []const T` | The values as a read-only slice of the buffer's own storage: the filled part and no more. |
| `view_mut` | `view_mut(*self) -> []T` | The same slice, writable: a write through it is a write to the buffer. |

### `InlineCursor`

```sysl
struct InlineCursor[T, const N: usize]
    buffer: Inline[T, N]
    at: usize
```

A walk in order over a copy of the buffer it started from.

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
| `len` | `const len(self) -> usize` |  |
| `is_empty` | `const is_empty(self) -> bool` |  |
| `capacity` | `const capacity(self) -> usize` | How many slots the map's storage has. |
| `get` | `const get(self, k: K) -> Option[V]` |  |
| `get_or` | `const get_or(self, k: K, default: V) -> V` | The value a key names, or the one the caller supplies where it names none. |
| `has` | `const has(self, k: K) -> bool` |  |
| `put` | `const put(self, k: K, v: V)` | A key given a value: the entry it already had takes the new one, or a fresh entry is placed. |
| `upsert` | `const upsert(self, k: K, init: V, f: V -> V)` | A key given `f` of the value it had, or `f(init)` where it had none -- a counter's `m.upsert(k, 0, _ + 1)`, an accumulator's `m.upsert(k, 0, _ + x)`. |
| `remove` | `const remove(self, k: K) -> bool` | A key taken out, answering whether it was there. |
| `clear` | `const clear(self)` | Every entry dropped and the table let go entirely; see `MapCore.clear`. |
| `copy` | `const copy(self) -> Map[K, V]` | A second map holding the same entries, its table sized to them -- the one way to get a map that is not this one, every other way of handing a `Map` on handing on this one. |
| `release` | `release(*self)` | This name's share of the table given back, and the handle left naming nothing -- the state zeroed storage starts in, where every reader answers as an empty map does. |

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
| `len` | `const len(self) -> usize` |  |
| `is_empty` | `const is_empty(self) -> bool` |  |
| `capacity` | `const capacity(self) -> usize` |  |
| `has` | `const has(self, k: K) -> bool` |  |
| `add` | `const add(self, k: K) -> bool` | A key put in, answering whether it was **new**. |
| `remove` | `const remove(self, k: K) -> bool` | A key taken out, answering whether it was there. |
| `clear` | `const clear(self)` |  |
| `release` | `release(*self)` | The map underneath given back without allocating, as `Map.release` does -- for a set in storage the program zeroed itself, which answers as empty until it is assigned. |

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
and `rehash` can re-place an entry without asking the key to mix itself down again. While the map
is still a list nothing has been hashed, and the field holds zero.

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

### Index for Inline[T, N]

```sysl
impl[T, const N: usize] Index[usize, T] for Inline[T, N]
```

`b[i]` is the `i`th value, and traps at or past the length as an array's index does -- a slot
past the length holds nothing to read.

### IndexSet for Deque[T]

```sysl
impl[T] IndexSet[usize, T] for Deque[T]
```

### IndexSet for Inline[T, N]

```sysl
impl[T, const N: usize] IndexSet[usize, T] for Inline[T, N]
```

`b[i] = v` replaces the `i`th value, letting go of the one it held; it does not grow the buffer,
and traps at or past the length.

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

### Iterate for InlineCursor[T, N]

```sysl
impl[T, const N: usize] Iterate for InlineCursor[T, N]
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

### Walk for Inline[T, N]

```sysl
impl[T, const N: usize] Walk for Inline[T, N]
```

`for x in b` walks the values in order. The cursor holds a copy of the buffer as it was when the
walk began, so changing the buffer inside the loop does not change what the loop reads.

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
