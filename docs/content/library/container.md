---
title: The container module
summary: "`sysl.container` — `Map` and `Set` over one flat probe table, `Deque` for a queue at both ends, `Heap` for a priority queue, and an immutable `List` that shares its tail."
weight: 32
---

**Every declaration in `sysl.container`, with its signature:** [the generated API page](/api/sysl-container/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

One module, `sysl.container`, holding five types. A program names the ones it wants and gets nothing
else — what a program does not import, it does not link.

| type | is |
|---|---|
| [`Map[K: Hash + Eq, V]`](#the-map) | keys to values |
| [`Set[K: Hash + Eq]`](#the-set) | membership, and the algebra over it |
| [`Deque[T]`](#the-queue-at-both-ends) | a sequence cheap to take from at either end |
| [`Heap[T: Ord]`](#the-priority-queue) | smallest out first |
| [`List[T]`](#the-immutable-list) | never modified, shares its tail |

None of them is a type the compiler knows. Each is ordinary sysl over a `[]T` or a
[`Buf[T]`](/library/buf/), in files a program could have written — which is the same thing worth
saying about `Buf` itself, and it stays true as the containers get more interesting.

**There is a sixth thing in this namespace and it is a module rather than a type**:
[`sysl.container.ring`](/library/ring/), holding `Ring[T]` — the queue of a **fixed** capacity, laid
over storage the caller supplies. It sits below this module rather than inside it because it declares
`@no_alloc` and [a capability is a property of the whole
module](/reference/modules/#capabilities-are-a-module-property), which the five types here could not
claim: every one of them grows. So a program that has given the allocator up imports that module and
not this one.

## One flat table, and why that is the whole design

`Map` and `Set` are **open-addressed**: entries live in the table, and a key is found by probing
forward from where it hashes until it turns up or an empty slot proves it is not there.

The alternative is chaining — a bucket per hash, holding its entries through references. In a
reference-counted language that costs more than it looks: a heap cell per entry, an allocation on
every insert, a release on every removal, and a walk that touches every count when the table doubles.
A flat table allocates **once**.

What makes it expressible is a variant that carries no payload. A generic container cannot make
`[K; n]` storage, because a bound may promise no value to repeat — but `Slot.Empty` needs nothing of
`K` or `V`, so `[Slot.Empty; n]` fills a table of *any* key and *any* value:

```sysl
enum Slot[K, V]
    Empty
    Dead
    Live(hash: u64, key: K, value: V)
```

Three variants rather than two, and the third is what deletion needs. A removed key becomes `Dead`
rather than `Empty`, because emptying it would end every probe that had run past it on the way to
something else. Tombstones then count against the table's load exactly as live entries do, and
`rehash` sizes from the **live** count — so a map inserted into and removed from in equal measure
settles at the size its contents deserve instead of doubling forever.

The `u64` beside each key is the hash it mixed down to. It earns its eight bytes twice: a probe
rejects a wrong slot on an integer comparison without touching either key, and a rehash re-places
every entry without hashing anything.

## The map

`K: Hash + Eq` is the whole requirement, and both are [core traits](/library/core/) — so every
built-in key type already qualifies, and a program's own type joins by writing the two `impl` blocks
it would write anyway.

```sysl
import sysl.container.{Map, map}

main()
    var ages: Map[string, int] = map()

    ages.put("ada", 36)
    ages.put("alan", 41)
    ages.put("ada", 37)

    print(ages.len())
    print(ages.get("ada"))
    print(ages.get("grace"))
    print(ages.get_or("grace", 0))
    print(ages.remove("alan"), ages.remove("alan"))
```

```output
2
Some(37)
None
0
true false
```

`get` answers an `Option` because a missing key is an ordinary question rather than a mistake, and
`remove` answers whether the key was there — so the second call reports `false` without failing.

**The annotation on `var ages` is required.** `map()` is a nullary generic, so its type arguments come
from what receives it, and there is nothing else in that line to take them from.

**An empty map holds no table at all, and does not allocate.** `map()` costs nothing; the first
`put` makes the table. That matters far more than it looks, because no caller can see that they are
paying: a map built inside a loop, held in a struct that is usually empty, or made on a branch that
turns out not to be taken is a `malloc` and the `free` behind it, every time. A profile of an
interpreter that gave every block its own scope — two maps per scope, one entry each — put **27% of
its running time** in constructing and destroying maps nothing was ever read out of.

`capacity()` is how that is visible from outside, and it is the one number here that might surprise:

```sysl
import sysl.container.{Map, map}

var m: Map[string, int] = map()

print(m.capacity(), m.len())

m.put("k", 1)

print(m.capacity(), m.get("k"))

m.clear()

print(m.capacity())
```

```output
0 0
8 Some(1)
0
```

`clear` lets the table go entirely for the same reason, so a map used as scratch across a loop keeps
nothing between passes and one that is cleared and never used again costs nothing at all.

`map_with_capacity(n)` **still allocates up front**, which is the point of writing it: a caller who
names a size is saying entries are coming. It sizes the table for `n` entries, and the table is grown
at three quarters full, so the storage is twice `n`.

A `Set` is a `Map` and inherits all of this, including the empty table.

Walking hands back a pair per entry, in no particular order:

```sysl
for (name, age) in ages.walk()
    print(name, age)
```

**Nothing hands out a reference into the table**, and that is deliberate rather than an omission: a
`&V` pointing into storage that a rehash replaces would be a pointer at freed memory the moment the
map grew. The cost is that a value is updated by putting it back. The matching rule is that a map
must not be modified while a cursor over it is live — doing so is memory-safe and nothing more, since
what the cursor yields in that case is unspecified.

## The set

The same table with nothing in the value column. `add` answers whether the key was **new**, which is
what makes a set the "have I seen this before" test rather than something you ask twice:

```sysl
import sysl.container.{Set, set, set_of, intersection}

main()
    var seen: Set[int] = set()

    for n in [3, 1, 4, 1, 5, 9, 2, 6, 5, 3]
        if seen.add(n)
            print("first time:", n)

    print("distinct:", seen.len())
    print("shared:", intersection(seen, set_of([1, 2, 7])).len())
```

```output
first time: 3
first time: 1
first time: 4
first time: 5
first time: 9
first time: 2
first time: 6
distinct: 7
shared: 2
```

`set_of` collapses a slice's duplicates in one line. Beside it are `union`, `intersection`,
`difference` and `is_subset` — `intersection` walks the smaller side, since the answer cannot be
bigger than that and walking the larger would ask more questions to reach it.

## The queue at both ends

**This is the module with a performance argument rather than an expressiveness one.** A queue written
over a [`Buf`](/library/buf/) takes from the front with `remove(0)`, which shifts every remaining
element down one — so a loop that pushes n things and pops them all is quadratic while reading as
though it were linear.

A `Deque` holds its elements in a ring: a head index says where the first one is, and both ends wrap
rather than move. Taking from the front advances the head and touches nothing else.

```sysl
import sysl.container.{Deque, deque}

main()
    var work: Deque[int] = deque()

    work.push_back(1)
    work.push_back(2)
    work.push_front(0)

    loop
        work.pop_front() match
            None -> break
            Some(job) ->
                print("doing", job)

                if job < 2 then work.push_back(job + 10)
```

```output
doing 0
doing 1
doing 2
doing 10
doing 11
```

That is the shape of every work list and every breadth-first walk. `d[i]` counts from the **front**
whatever the ring is doing underneath, and `first`/`last` answer an `Option` at either end.

## The priority queue

A binary heap over a `Buf`, and a **min**-heap: the three things a priority queue is actually reached
for — a scheduler ordered by deadline, a shortest path ordered by distance, an event loop ordered by
time — all want the smallest first.

A tuple is ordered when its parts are, so a `(priority, payload)` queue needs no wrapper type:

```sysl
import sysl.container.{Heap, heap_of}

main()
    var due = heap_of([(30, "sweep"), (10, "tick"), (20, "poll")])

    loop
        due.pop() match
            None -> break
            Some(job) -> print(job.0, job.1)
```

```output
10 tick
20 poll
30 sweep
```

`heap_of` is **linear** where pushing the same elements one at a time is `O(n log n)`: it lays them
down unordered and establishes the heap property afterwards, sifting from the last parent to the
root.

**A heap cannot be walked, on purpose.** Its storage order is not its priority order — only the first
element is guaranteed to be anything in particular — so a cursor would hand back elements in an order
that looks meaningful and is not. Draining with `pop` is the only reading that answers truthfully.

## The immutable list

Every operation answers a **new** list and the old one is still there. Putting an element on the front
makes one cell holding it and a reference to the list that was there, so two lists differing by one
element share every cell but one:

```sysl
import sysl.container.{List, list_of}

main()
    val outer = list_of(["x"])
    val f = outer.prepend("y")
    val g = outer.prepend("z")

    print(outer.len(), f.len(), g.len())

    for name in f.walk()
        print("f:", name)

    for name in g.walk()
        print("g:", name)
```

```output
1 2 2
f: y
f: x
g: z
g: x
```

**This is the container that earns its place from reference counting specifically.** `prepend` is one
allocation whatever the length, and the count is what makes the sharing safe without either list
knowing the other exists — a cell goes when the last list holding it does. A language without it
would need a collector, or an ownership rule saying which of `f` and `g` owns `outer`, and there is no
good answer to that.

It also cannot form a cycle, which is what makes counting *sufficient* here rather than merely
convenient: a cell's tail always existed before the cell did.

`uncons` is the member to write loops with — it answers the head and the rest together, so a walk asks
one question instead of two:

```sysl
at.uncons() match
    None -> break
    Some(pair) ->
        val head, rest = pair
```

`len` is constant time. Immutability is what makes that free: a cell's tail never changes, so a length
worked out when the list was built stays true forever.

**Do not reach for this to hold a sequence and walk it.** There is no index, getting to the `i`th
element walks `i` cells, and those cells are wherever the allocator put them. `Buf` is what a sequence
wants; this is what a *shared history* wants — a scope chain, an undo stack, a search path that is its
parent's path plus a step. All three share almost everything and would copy almost everything in a
`Buf`.
