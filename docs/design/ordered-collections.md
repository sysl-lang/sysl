# Sorted maps and sets — design (0.15)

sysl's `sysl.container` has a hashed `Map` and `Set`, a `Deque`, a `List` and a `Heap`, and **nothing
that keeps its keys in order**. This note proposes `SortedMap[K, V]` and `SortedSet[K]`: a B+ tree
with insert, lookup, removal, in-place update, first/last, neighbours, rank, ranges with every bound
kind, cursors that walk both ways, split and append, and set algebra in sorted order.

It is also a stressor, so **the language gaps it ran into come first** (§1). Every claim about the
compiler cites a probe under [`ordered-collections-probes/`](ordered-collections-probes/); `zsh
docs/design/ordered-collections-probes/run.zsh [sysl]` runs them all. The probes were run with the installed
**sysl 0.13.7**.

Nothing here is built. The user approves the design and answers §12 before anything is.

## 1. Gaps found (Rule 9) — these come before the design

Five things the design needs cannot be written today. Each has a workaround. **The design below
assumes the gap is closed rather than designing around it**, and §12 asks which to close first.

### G1. A node cannot hold its keys inline: no struct field has a live prefix

A B-tree node holds up to `B` keys, of which the first `len` are live. Rust keeps them inline in the
node (`[MaybeUninit<K>; 11]`), so a node is **one** allocation and one cache-line run. In sysl a
partly-live run exists only as heap `[]T` storage (`slot_storage`) or as a local (`slot_empty`).
Neither can be a field of a heap node.

Shortest input ([p01](ordered-collections-probes/p01_inline_prefix.sysl)):

```sysl
struct Leaf[K, const B: usize]
    len: usize
    keys: [B]K

one[K, const B: usize](k: K) -> &Leaf[K, B]
    var ks: [B]K = slot_empty()
    ks[0] = k
    Leaf(1, ks)
```

```
error: 'ks' is an empty slot, read here before anything was written to it
```

The empty slot written at the construction itself ([p02](ordered-collections-probes/p02_inline_field_slot.sysl)):

```
error: 'slot_empty' makes a local that holds nothing yet, so it is written only as a 'var''s initial value — 'var name: T = slot_empty()'
```

**What the reference says.** `reference/memory.md § An empty local: one slot in the frame`: "It stands
only as a `var`'s initial value", and an empty array "is written once every element is. Until then any
read of it is refused". `§ Slot storage`: `slot_place` on storage `slot_storage` did not make "is an
assignment and nothing". So the refusal is the documented rule, and nothing documented offers an
inline run with a live prefix.

**The workarounds, and what each costs.**

| workaround | probe | cost |
|---|---|---|
| keys and values each in `slot_storage(B)` | [p04](ordered-collections-probes/p04_slot_storage_node.sysl) works: middle insert, middle removal, a removed `Drop` key let go at once | **3 allocations per leaf** (box, keys, values) and 4 per internal node where Rust has 1; **one more dependent load per level** of every lookup (node → keys buffer) |
| fill the inline array from the first key, `[k; B]` | [p03](ordered-collections-probes/p03_repeat_fill.sysl) works | the spare copies **keep a removed key alive**: p03 prints `removed 1; live 1 2`, then `drop 1` only when the node dies. A `SortedSet[&Handle]` would not close a removed handle. B − 1 retains per new node |
| `[B]Option[K]`, as `Deque` does | (Deque's own code) | a tag per slot: an `int` key's node doubles in size; every compare unwraps |

The slot-storage layout is correct and would ship. What it gives up is the B-tree's main advantage,
which is one contiguous node per level. §11's benchmark measures it against the inline layout so the
cost is a number.

**What closing it could look like** (the user's call, §12 Q2): a fixed-capacity inline buffer the
compiler knows, `Inline[T, const N: usize]` (Rust's `arrayvec`), which keeps a live count and whose
release lets go of the live prefix only. Or `slot_empty()` admitted as a field's value in a
construction, with `slot_place`/`slot_vacate` taught inline arrays. An empty slot of a counted type is
already zero (`slot_take`'s empty), so releasing a whole array of them is already safe.

### G2. A range of keys that are not integers is refused

Shortest input ([p05](ordered-collections-probes/p05_range_of_keys.sysl)): a member taking `r: Range[K]` with
`K: Ord`, called as `m.between("b"..<"f")`:

```
error: a range runs between integer bounds, not string
```

The same member at `int` keys works ([p06](ordered-collections-probes/p06_range_generic_int.sysl), prints
`2 3`). **Reference:** `reference/expressions.md`: a range's bounds "must agree, and they must be
integers". `Range[T]` itself is generic, and only its `Iterate` impl needs `Integer`. A range *value*
over any `Ord` type would not need a counter.

### G3. An open-ended range has no value

Shortest input ([p07](ordered-collections-probes/p07_range_open_end.sysl)): `m.count(3..)`:

```
error: a range with an open end is only allowed in a slice index — as a value it would have to say what the missing bound is, and only the thing being indexed knows that. Write both ends
```

**Reference:** `reference/expressions.md`: "An open end has no value reading… refused elsewhere by name."
That is deliberate. But the diagnostic's own reason, "only the thing being indexed knows that", is
exactly the case here. A sorted map *is* the thing that knows what `"m"..` means.

G2 and G3 together mean that **`m.range(lo..<hi)`, the spelling every reader reaches for, works only
for integer keys with both ends written.** The workaround is an explicit bound enum,
`m.range(Bound.Incl("b"), Bound.Excl("f"))`, plus named members (`from`, `until`). §3.4 gives both
spellings.

### G4. A field's array length may not compute with a value parameter, but a field's struct argument may

An internal node with `B` keys has `B + 1` children.

```sysl
struct Direct[const B: usize]
    kids: [B + 1]int
```

```
error: the length 'B + 1' computes with the value parameter 'B', and a type may name a value parameter but not compute with one — deciding that two such lengths are one type is type-level arithmetic, which is a separate feature. A body may compute with 'B' freely
```

([p13](ordered-collections-probes/p13_field_computed_array.sysl)). Yet the same length through a one-field
wrapper, `kids: Arr[int, B + 1]` with `struct Arr[T, const N: usize] a: [N]T`, is accepted and prints
`4` ([p12](ordered-collections-probes/p12_field_computed_length.sysl)). **Reference:**
`reference/generics.md § What is deliberately not here`: "a body and a field may compute a struct's
value argument, compared by its polynomial". The polynomial comparison the diagnostic says is missing
already exists for struct arguments. The array length is the one position left out. Workaround: the
wrapper struct. G4 matters only once G1 lets a node hold inline arrays.

### G5. A `-> ref` member cannot answer a place inside a node that a search found

Entry-style update wants `m.entry(word, 0) += 1`, where `entry` puts `0` in if the key is absent
and answers the value's place. Over a flat array it works: `self.vals[self.index_or_put(k, init)]`
prints `2 3 1` ([p14b](ordered-collections-probes/p14b_entry_by_index.sysl)). Over a tree, the place is an
element of a node the search answers:

```sysl
entry(*self, k: string, init: int) -> ref int = self.leaf_for(k, init).vals[self.found]
```

```
error: 'Tree.entry' would answer a place in storage of its own call — a by-value parameter or a local — which is gone when it returns. A '-> ref' member answers a place its caller still holds: reached through a '*self' receiver, a reference, a view, a container's element, or module storage
```

([p15](ordered-collections-probes/p15_entry_through_node.sysl); the `*self.place_or_put(k, init)` spelling is
refused the same way, [p14](ordered-collections-probes/p14_entry_as_place.sysl)). **Reference:**
`reference/memory.md § A member that answers a place`: the body is "a field chain, an element or a
dereference from a parameter or module storage". So a call's result is outside the rule, as
documented. Two things are still wrong:

- **The same place is reachable through `IndexPlace`**, whose `index_place` answers a `*V`, and the
  `@relocates` rule guards it there. `t["b"] += 40; t["a"] *= 7` through a node the search found
  prints `7 42` ([p16](ordered-collections-probes/p16_index_place_through_node.sysl)). So the trait can say
  what the member form cannot.
- **The diagnostic misdescribes the storage.** It is neither "a by-value parameter" nor "a local". It
  is a view inside a heap node that the receiver owns. That wording is a bug whatever the user
  decides about the rule. It is filed here rather than fixed, because this is a phase-1 design and
  the fix is a compiler change.

Workaround: `upsert(k, init, f)`, the shape `Map` already has (one search, a closure).

### Not gaps, but what the B-tree needs from the ordering trait (the trait/algorithm agent's)

- **`Ord` does not give `==`** ([p09](ordered-collections-probes/p09_ord_gives_eq.sysl): `'==' needs 'K:
  Eq'`). The tree never needs `Eq`, because it decides equality as `!(a < b) && !(b < a)`. So the bound
  is `K: Ord` alone. Whether `Ord` should imply `Eq`, as Rust's does, is the ordering agent's question.
- **There is no three-way compare.** The search costs one `lt` per key visited and **one more at the
  end** to tell "found" from "insert here". For a string key that extra `lt` is a second `memcmp`
  of the same prefix. **The tree asks for a `cmp(self, rhs) -> Ordering` member on `Ord`**,
  defaulted from `lt` and overridable (strings, tuples), and it will use it when it exists. It is not
  a blocker.
- **No custom comparator.** An order other than `K`'s own is a newtype with `impl Ord` (Rust's
  answer); descending order is the ordering agent's `Reverse[T]` if it provides one.

## 2. Decisions proposed

| # | question | proposal | why, in one line |
|---|---|---|---|
| 1 | names | **`SortedMap[K, V]`, `SortedSet[K]`** | "ordered" means *insertion* order in Python, Kotlin and Java (`LinkedHashMap`); "sorted" says what is promised, and `BTree` names the representation |
| 2 | module | `sysl.container`, beside `Map` | one module for containers; the free set functions overload by type (p17) |
| 3 | sharing | **a handle over one counted core**, like `Map` and `Deque` | assigning a container gives a second name for it, everywhere in the library |
| 4 | what `K` needs | **`K: Ord` and nothing else** | equality is decided by order; no `Hash`, no `Eq` |
| 5 | structure | **B+ tree**: entries in leaves, separator keys in internal nodes, leaves linked both ways | a cursor step and a range scan never climb the tree |
| 6 | fan-out | a **value parameter with a default**, `SortedMap[K, V, const B: usize = 16]` (p10 works) | the right `B` depends on the key's size; the default comes from the benchmark sweep |
| 7 | order statistics | **subtree counts in internal nodes**: `rank`, `nth`, an O(log n) range count | none of Rust, C++, Go or Java has it; the cost is one word per child (Q4) |
| 8 | a cursor under mutation | **the cursor re-seeks**, so the walk is defined | §5; Java throws, C++ leaves it undefined, Rust refuses to compile it |
| 9 | `m[k]` | `IndexPlace[K, V]`: a place for a present key, **a trap for an absent one** | `m[k] += 1` updates in place; Buf, Deque and arrays trap at a bad index |
| 10 | a removed key | **let go of before `remove` returns**, separators included | a `SortedSet[&Handle]` closes a handle when it is removed |

## 3. The API

Signatures are written as they would be declared. `K: Ord` throughout. Ranges use the spelling
G2/G3 would allow, with the workaround spelling beside it in §3.4.

### 3.1 Making one

```sysl
sorted_map[K: Ord, V]() -> SortedMap[K, V]                    // no node until the first put, as map()
sorted_map_of[K: Ord, V](pairs: []const (K, V)) -> SortedMap[K, V]   // later pair wins; O(n) when sorted
sorted_set[K: Ord]() -> SortedSet[K]
sorted_set_of[K: Ord](xs: []const K) -> SortedSet[K]
m.copy() -> SortedMap[K, V]                                   // the one way to get a map that is not this one
```

`sorted_map_of` checks whether its input is sorted. If it is, the tree is built bottom-up in O(n),
with leaves filled and no search. If it is not, the input is sorted first (`sysl.slices.sort`, stable,
so the later duplicate wins) and then built the same way.

### 3.2 `SortedMap[K, V]`

| member | answers | cost |
|---|---|---|
| `len()`, `is_empty()` | `usize`, `bool` | O(1) |
| `get(k)`, `get_or(k, d)`, `has(k)` | `Option[V]`, `V`, `bool` | O(log n), `@no_alloc` |
| `m[k]` / `m[k] = v` / `m[k] += 1` / `ref v = m[k]` | the value's place; **traps** where `k` is absent | O(log n) |
| `put(k, v)` | — (Map's shape) | O(log n) |
| `upsert(k, init, f: V -> V)` | — `f` asked once, one search | O(log n) |
| `entry(k, init) -> ref V` | the value's place, put first if absent: `m.entry(w, 0) += 1` | **needs G5** |
| `take(k)` | `Option[V]`, the removed value | O(log n) |
| `remove(k)` | `bool`, as `Map.remove` | O(log n) |
| `first()`, `last()` | `Option[(K, V)]` | O(1): the outer leaves are held |
| `pop_first()`, `pop_last()` | `Option[(K, V)]` | O(log n) worst, O(1) amortised |
| `at_most(k)`, `at_least(k)`, `below(k)`, `above(k)` | `Option[(K, V)]`: Java's floor, ceiling, lower, higher | O(log n) |
| `rank(k)` | `usize`: how many keys are `< k` | O(log n) (Q4) |
| `nth(i)` | `Option[(K, V)]`: the `i`th in order | O(log n) (Q4) |
| `count(r)` | `usize`: how many keys `r` covers | O(log n) (Q4) |
| `retain(p: (K, V) -> bool)` | — keeps what `p` accepts | O(n), one pass, rebuilt bottom-up |
| `remove_range(r)` | `usize` removed | O(log n + removed) |
| `split_off(k)` | `SortedMap[K, V]`: every key `>= k` moved there | O(log n) |
| `append(other)` | — every entry of `other` moved here; `other` left empty, under every name | O(log n) when the key ranges are disjoint, else O(n + m) merge |
| `clear()` | — the tree let go | O(1) here; the release is O(n) |
| `walk()` | `SortedCursor[K, V]`, ascending | O(log n) to start |
| `range(r)` | a cursor confined to `r`, either way | O(log n) to start |
| `cursor_at(k)` | a cursor before the first key `>= k`, free to go either way | O(log n) |
| `keys()`, `values()` | cursors yielding one column | — |

**`put` answers nothing**, as `Map.put` does. `take` is the member that hands back the old value.

**`m[k]` on an absent key traps**, as `b[i]` past a `Buf`'s end does. A program that is not sure
asks `get`. An insert-on-write `m[k] = v` (C++'s `operator[]`) is refused on purpose: C++'s inserts
a default-constructed value on a *read*, which is a well-known trap, and sysl has no default to
insert.

### 3.3 `SortedSet[K]`

A `SortedMap[K, unit]`, the way `Set` is a `Map[K, bool]`, with zero-sized values, so nothing is
stored per key. Members: `add(k) -> bool` (new?), `has`, `remove`, `take`, `first`, `last`,
`pop_first`, `pop_last`, the four neighbours, `rank`, `nth`, `count`, `range`, `cursor_at`,
`retain`, `split_off`, `append`, `copy`, `clear`.

**Set algebra in sorted order**, as free functions that overload `Set`'s by parameter type (two
`union`s in one module, told apart by argument, work today: [p17](ordered-collections-probes/p17_free_fn_same_name.sysl)
prints `2 4`):

```sysl
union[K: Ord](a: SortedSet[K], b: SortedSet[K]) -> SortedSet[K]
intersection[K: Ord](a: SortedSet[K], b: SortedSet[K]) -> SortedSet[K]
difference[K: Ord](a: SortedSet[K], b: SortedSet[K]) -> SortedSet[K]
symmetric_difference[K: Ord](a: SortedSet[K], b: SortedSet[K]) -> SortedSet[K]
is_subset[K: Ord](a: SortedSet[K], b: SortedSet[K]) -> bool
is_disjoint[K: Ord](a: SortedSet[K], b: SortedSet[K]) -> bool
```

Each is one merge walk of the two leaf chains, O(n + m), with no lookup per key. The four that build
a set feed the merged stream into the bottom-up build, so the result never runs an insert's search.
When one set is far smaller (|a| · log |b| < |a| + |b|), `intersection` and `is_subset` search
instead of walking. `symmetric_difference` and `is_disjoint` are new, and `Set` should gain them too
(a note for whoever owns `Set`, not part of this item). Lazy merge cursors would belong with the
iterator adapters (§9).

### 3.4 Ranges, with every bound kind

| bound | with G2 + G3 closed | today's spelling (the workaround) |
|---|---|---|
| `lo <= k < hi` | `m.range(lo..<hi)` | `m.range(Incl(lo), Excl(hi))` |
| `lo <= k <= hi` | `m.range(lo..hi)` | `m.range(Incl(lo), Incl(hi))` |
| `k >= lo` | `m.range(lo..)` | `m.range(Incl(lo), Unbounded)`, `m.from(lo)` |
| `k < hi` | `m.range(..<hi)` | `m.range(Unbounded, Excl(hi))`, `m.until(hi)` |
| `k <= hi` | `m.range(..hi)` | `m.range(Unbounded, Incl(hi))` |
| everything | `m.range(..)` | `m.walk()` |
| `lo < k …` (excluded low) | no syntax in any language | `m.range(Excl(lo), …)` |

`Bound[K]` is a three-variant enum (`Incl(k)`, `Excl(k)`, `Unbounded`). It stays even once G2/G3
close, because an excluded low end has no range syntax in sysl or Rust (Rust has
`Bound::Excluded` for the same reason). **An empty or inverted range is an empty cursor**, not a
trap. Rust panics on `range(5..3)`. A sorted map is asked about a range that turns out to be empty
far more often than a slice is, so the cursor is empty.

## 4. The representation

### 4.1 Nodes

The target layout, once G1 (and G4) are closed. `B` is the leaf capacity:

```sysl
struct Leaf[K, V, const B: usize]
    len: usize
    keys: [B]K                    // live prefix of len
    vals: [B]V                    // live prefix of len
    prev: weak Leaf[K, V, B]      // leaf chain; see 4.4
    next: weak Leaf[K, V, B]

struct Inner[K, V, const B: usize]
    len: usize                    // separator keys; children = len + 1
    keys: [B]K
    kids: [B + 1]Child[K, V, B]   // G4
    counts: [B + 1]usize          // entries under each child (Q4)

enum Child[K, V, const B: usize]
    ToLeaf(l: &Leaf[K, V, B])
    ToInner(n: &Inner[K, V, B])

struct SortedCore[K, V, const B: usize]
    root: Option[Child[K, V, B]]
    len: usize
    height: usize
    version: u64                  // bumped by every change of the key set (5.2)
    head: weak Leaf[K, V, B]      // first and last leaf: O(1) first()/last()
    tail: weak Leaf[K, V, B]
```

The handle is `struct SortedMap[K: Ord, V, const B: usize = 16] private core: &SortedCore[K, V, B]`,
with `Map`'s null-core reading (an unassigned handle answers as empty).

**Keys and values are separate arrays**, as in Rust, so a search touches only keys. With `B = 16`
and `int` keys, a leaf's keys are two cache lines.

**The child enum is two words.** Every child of one `Inner` is at the same height, so a later
refinement could split `Inner` into `Inner` over leaves and `Inner` over inners, making a child one
word. That is not proposed until a benchmark shows the tag matters.

**What works today** is p11 ([p11](ordered-collections-probes/p11_recursive_enum_node.sysl), prints
`fifteen five -`): a generic enum over `&Leaf`/`&Inner`, inner nodes holding children of that enum in
slot storage, and a **`@no_alloc` lookup** that walks it.

**The search within a node** is a linear scan by `lt` up to `B = 16` (predictable branches; Rust
also scans linearly) and binary above. The benchmark settles the crossover.

### 4.2 How it allocates

- **An empty map allocates its core and nothing else.** The first `put` makes one leaf, so a map of
  up to `B` keys is one sorted array (the hashed `Map`'s "a small map is a list", for free).
- **One allocation per node** with G1 closed. Leaves hold about 3/4 of the entries and inner nodes
  1/B of them, so overhead per entry is about `(node header + spare slots) / entries per node`. With
  the workaround it is three allocations per leaf.
- **A split allocates one node.** A merge or a borrow allocates nothing. Nothing ever rehashes or
  moves the whole tree.
- **The bulk build** (from a sorted input, set algebra, `retain`, an overlapping `append`) fills leaves
  to `B − 1` rather than `B`, so the first inserts afterwards do not all split.

### 4.3 How it is dropped

**ARC does it, with no `Drop` of its own.** The core holds the root, a root holds its children, and the
last release walks down. The recursion is the tree's **height**. At `B = 16` and minimum occupancy
8, a height of 10 holds over 10⁹ entries, so the stack is no concern. The leaf chain is `weak`, so
releasing a leaf never walks its neighbours. A strong `next` link would make dropping a large map
recurse once per leaf (millions deep), which is why §4.4 does not use one.

**Removal lets go at once** (decision 10). A removed entry's slot is vacated, so a `Drop` key or
value runs its destructor before `remove` returns (p04 shows the slot-storage form doing it). A
B+ tree adds one case. A removed key may also sit in an ancestor as a **separator**. The removal
already holds the path it descended, so it replaces that separator with the leaf's new first key on
the way back up. Without that step, a removed `&Handle` would stay alive until some later
rebalancing overwrote it. A test pins this (§10).

### 4.4 Sibling links: `weak`, measured against raw

The leaf chain needs a back link for `prev()`, and a strong link both ways would be a cycle. `weak
Leaf` is safe whatever the cursor's state. Its cost is a `get()` per leaf crossed, which happens
once per `B` entries walked. The alternative is a raw `*Leaf` followed only while the core's
`version` matches the cursor's (§5), which is Rust's approach with parent pointers. It is faster by a
check, but memory safety would then rest on the version invariant. **Proposal: `weak`**, with raw
left for later if the benchmark shows the `get()` in a range scan's profile.

## 5. Cursors, and what a mutation does to a live one

### 5.1 The cursor

```sysl
struct SortedCursor[K: Ord, V, const B: usize]
    private core: &SortedCore[K, V, B]
    private leaf: Option[&Leaf[K, V, B]]   // holds its leaf: never reads freed memory
    private at: usize
    private version: u64
    private last: Option[K]                // the last key yielded, for re-seeking
    private lo: Bound[K]
    private hi: Bound[K]
```

It implements `Iterate` (`next`, ascending) and has `prev()` (descending), `peek()` and
`peek_back()`. `for (k, v) in m` is `Walk` → `walk()`. A descending loop is `for (k, v) in
m.walk().backward()`. `backward()` is a small adapter here unless the trait/algorithm agent's
double-ended trait arrives first, in which case the cursor implements that instead (§9).

A step within a leaf is an index increment. A step across leaves follows the chain, which is one
`weak.get()`, one retain and one release per `B` entries. A cursor never climbs the tree.

### 5.2 Iteration stability: the cursor re-seeks

**The rule:** every change to the **key set** (a new key, a removal, a pop, `clear`, `split_off`,
`append`, `retain`, `remove_range`) bumps `core.version`. Overwriting a present key's value (`put`
over it, `m[k] = v`, `upsert`, `entry`) does not, because it moves nothing. Each cursor step compares
its `version` with the core's. **On a mismatch it re-seeks**: one O(log n) descent to the first key
after `last` (before it, going backwards), and the walk goes on from there.

What a program may rely on:

- **The walk is always in order and never yields a key twice.**
- **A key present from the cursor's start to the end of the walk, and ahead of it, is yielded.**
- **A key inserted ahead of the cursor is yielded. One inserted behind it is not.** A removed key
  is not yielded after its removal.
- **Values are read at the step**, so an overwrite ahead of the cursor is seen.
- Memory safety never depends on any of this. The cursor holds its leaf, and the leaf holds its storage.

The cost is one integer compare per step and a copy of each yielded key. That key is being yielded
anyway, so the copy is a retain for a counted key and nothing for an `int`.

This is the strongest rule of the five designs compared (§6). It is what makes "walk the map,
removing what fails a test" a correct loop rather than a crash or a compile error. `Map`'s cursor
says "memory-safe and nothing more" and could adopt the same `version` (a note for `Map`'s owner,
not this item). The alternative, fail-fast (trap on a mismatch, as Java does), is §12 Q5.

### 5.3 A `ref` into the map

`m[k]` answers a place through `IndexPlace[K, V]`. Every member that can split, merge or vacate a
node (`put`, `upsert` (which may insert), `take`, `remove`, `pop_*`, `retain`, `split_off`, `append`,
`clear`, `remove_range`) is **`@relocates`**. So a live `ref` or pointer from `m[k]` across any of
them is refused at compile time.
[p08b](ordered-collections-probes/p08b_ref_across_insert.sysl):

```
error: 'b' is a 'ref' standing on an element whose storage 'put' could move or give up, since it is '@relocates' — so the name could be left pointing at freed memory. Make the call before the 'ref', or put the 'ref' in a block that closes first
```

`index_place` takes **`self`** (the handle), as `Buf`'s does. Written with `*self`, even a *read*
`m["b"]` while a `ref` from it is live is refused, because a `*self` call might write its receiver
An earlier form of [p08](ordered-collections-probes/p08_index_place_by_key.sysl), with `print(m["b"])`
inside the ref's block, was refused with `'b' is a 'ref' standing on storage this call could release,
since a '*self' method may write its receiver`. That is the documented rule
(`memory.md § Nothing moves under a live interior name`), and the handle shape avoids it. With the ref in its own block, p08 prints `1 42` / `142`.

## 6. Against Rust, C++, Go and Java

| | Rust `BTreeMap` | C++ `std::map` | Go | Java `TreeMap` | sysl `SortedMap` |
|---|---|---|---|---|---|
| structure | B-tree, entries in every node, `B = 6` (11 keys) | red-black tree | none in the std library; `google/btree` (B-tree, degree parameter) | red-black tree | **B+ tree**, entries in leaves, `B` a value parameter |
| allocations | one per node | **one per entry** (3 pointers + colour each) | one per node | one per entry | one per node (G1) |
| order | `Ord` | `Compare` template parameter | a `less` function | `Comparable` or `Comparator` | `Ord`; another order is a newtype |
| range bounds | `range(RangeBounds)` with every kind, `Bound::Excluded` | `lower_bound`/`upper_bound`/`equal_range` | `AscendRange`/`AscendGreaterOrEqual` callbacks | `subMap`/`headMap`/`tailMap` **live views** with inclusive flags | `range(r)` and `Bound` (G2/G3); a cursor, not a view |
| neighbours | `range(..k).next_back()` | `lower_bound` then `--` | callbacks | `floorKey`/`ceilingKey`/`lowerKey`/`higherKey` | `at_most`/`at_least`/`below`/`above` |
| rank / nth | no | no | no | no | **yes** (Q4) |
| in-place update | `get_mut`, `entry().or_insert()` | `operator[]` (inserts a default on read) | replace the item | `put`, `merge`, `compute` | `m[k] += 1` (absent traps), `upsert`, `entry` (G5) |
| mutation during a walk | **refused at compile time** (borrow) | iterators survive except the erased one; inserted ones may or may not be visited | undefined; "must not modify" | **`ConcurrentModificationException`** | **defined: re-seek** (§5.2) |
| split / join | `split_off`, `append` | `extract`/`merge` (node handles) | none | none (views) | `split_off`, `append` (O(log n) when disjoint) |
| set algebra | lazy iterators | `std::set_union` etc. over sorted ranges | none | `retainAll` etc. (per-key lookups) | eager, one merge walk, bulk-built result |
| serialised order | serde, in key order | — | — | — | codec, **in key order** (deterministic) |

**Where sysl's is better.** Mutation during a walk is defined rather than refused, thrown or
undefined. Order statistics come built in. `m[k] += 1` updates in place with the compiler guarding the
reference, which is what Rust needs `get_mut` and the borrow checker for, and it does not have C++'s
read-that-inserts. Set algebra builds its result in O(n) instead of inserting key by key.
Serialisation and `Hash` are deterministic. The hashed `Map` can promise neither.

**Where it is deliberately different.** There are no live sub-map views (Java's `headMap`): a range is
a cursor, and a program that wants a separate map says `split_off` or builds one. A view that writes
through to its parent, with its own range checks on insert, is a second API surface for a rare need.
There is no comparator parameter: a newtype keeps the type saying what order it is in, so two maps
of one type cannot be ordered differently and then merged by mistake. **Where it is behind (G1
open):** one allocation per node is Rust's and is only promised once G1 closes.

## 7. Traits, and what `K` must be

| trait | `SortedMap[K, V]` | `SortedSet[K]` |
|---|---|---|
| `Walk` | `(K, V)` pairs in key order | `K` in order |
| `IndexPlace[K, V]` | yes (absent traps) | no: a set has nothing to place |
| `Eq` | where `V: Eq`; keys compared by order | always |
| `PartialOrd` / `Ord` | lexicographic over entries, where `V: Ord` | lexicographic, so `SortedSet[SortedSet[int]]` works |
| `Hash` | where `K: Hash, V: Hash`; **order-defined**, so equal maps hash alike | where `K: Hash` |
| `Display` / `Debug` | `{1: a, 2: b}` | `{1, 2}` |
| `Encode` / `Decode` (`sysl.codec`) | as `Map`'s: `string` keys as an object, other keys as `[k, v]` pairs, **in key order**; decode refuses a duplicate | a sequence, in order |
| `Default` | `sorted_map()` | `sorted_set()` |

**`K: Ord` is the whole requirement.** Equality is decided by order, so a type whose `Eq` disagrees
with its `Ord` gets the order's answer. The docs will say so, as Rust's do. `Hash` on the map
requires `K: Hash` only for hashing, never for storing. The codec impls live in `codec.sysl`
(the module that declares the traits), beside `Map`'s.

`Sequence` (`sysl.seq`) is not implemented, matching `Map`. Whether containers should answer
`Sequence` through their cursors is the trait/algorithm agent's question.

## 8. What it leans on, and what the probes said

| feature | how the tree uses it | probe | result |
|---|---|---|---|
| generic structs, recursive through `&` and a generic enum | nodes, children | p11 | **works** |
| `@no_alloc` | lookup, neighbours, rank, cursor steps | p11 | **works** |
| value generics with a default | `B` | p10 | **works** (`6 16`) |
| a computed length in a field | `[B + 1]` children | p12, p13 | **G4**: the wrapper struct works, the array does not |
| inline storage with a live prefix | node keys and values | p01, p02, p03 | **G1** |
| slot storage | the workaround's node buffers | p04 | **works**, removal lets go at once |
| `Range[K]` over an `Ord` key | `range(lo..<hi)` | p05, p06 | **G2** (integers only) |
| open-ended range values | `range(lo..)` | p07 | **G3** (documented refusal) |
| `IndexPlace` with a generic, non-integer key | `m[k] += 1` | p08, p16 | **works**, through a node a search found |
| `@relocates` guarding a live `ref` | `m[k]` across `put` | p08b | **works** (refused, as wanted) |
| `-> ref` members | `entry(k, init)` | p14, p14b, p15 | **G5** through a node; works over a flat array |
| `Ord` bound and `==` | equality | p09 | `Ord` gives no `==`; the tree needs none |
| free functions overloaded by type | set algebra beside `Set`'s | p17 | **works** (`2 4`) |
| bare-arrow closures as type parameters | `upsert`, `retain` | (`Map.upsert` uses it) | works |
| `weak T` | leaf chain | (tour, memory.md) | works; its cost is measured in §11 |
| trait objects | not used: every call is monomorphised | — | — |
| `Drop` on a key type | removal lets go at once | p03, p04 | works with slot storage; not with the fill workaround |

## 9. What this assumes from the other two designs

- **Trait/algorithm agent (ordering, adapters):** `Ord` with `lt`, as today. A `cmp` three-way member
  is used if added (§1). Its double-ended cursor trait is implemented by `SortedCursor` if it
  exists; until then the cursor has `prev()` and `backward()` of its own. Its `Reverse[T]` (if any)
  is how a descending map is spelled. Nothing here defines a trait it would also define.
- **Persistent-collections agent:** nothing is shared in code. A persistent sorted map is naturally
  a path-copying B-tree (Go's `google/btree` has an O(1) copy-on-write `Clone`), and it could reuse
  the node search and the bulk build. If both land, `sorted_map_of` and its persistent counterpart
  should agree on names. Path copying is theirs; this map is mutable and shared by handle.

## 10. The test plan

Tests go in `library/sysl/container/sorted_map_tests.sysl` and `sorted_set_tests.sysl` (`--std`),
under one fresh prefix.

**The oracle** is a sorted `Buf[(K, V)]`, searched with `sysl.slices.binary_search_by`. A seeded
generator (`sysl.rand`) drives **random operation sequences**: put, put-over, take, remove, upsert,
`m[k] += 1`, pop_first/last, split_off then append back, retain, remove_range, clear. After every
operation the test checks the map against the oracle and checks the **structural invariants**:

- an in-order walk equals the oracle, forwards and backwards (`prev`), and `len` agrees;
- every node except the root holds between `⌈B/2⌉` and `B` keys; all leaves are at one depth;
- every separator bounds its children correctly, and no separator is a removed key;
- the leaf chain matches the in-order leaves in both directions, and `head`/`tail` are the ends;
- every subtree count is the number of entries beneath it (Q4);
- `version` changed exactly when the key set did.

**Queries against the oracle:** `get`/`has` (hit and miss), the four neighbours at every key and
between keys and past both ends, `range` with every bound kind in both directions (including empty
and inverted ranges), `rank`, `nth` and `count`.

**Key types:** `int`; `u8`, whose small domain forces duplicates and full ranges; `string` (counted);
a `Drop` type counting live objects, to show that a removed key, including one that was a separator,
is let go of before `remove` returns and that nothing leaks; a tuple; an enum; a 100-byte struct.

**Sizes at the boundaries:** 0, 1, B − 1, B, B + 1, 2B, B², B² + 1, and 10⁵ random.

**The interaction cells** (compiler-notes § hunt the interactions):

| axis | cells |
|---|---|
| mutation × cursor position | each key-set change × cursor fresh / mid-leaf / at a leaf's last key / at the end / exhausted / inside a `range`, each direction: the §5.2 guarantees |
| sharing | two names see one change; `copy()` is independent both ways; `append(self)` (the same map) is a no-op, not a corruption |
| split / append | split below the minimum, at it, mid, at the maximum, past it, at an absent key; append disjoint-before, disjoint-after, overlapping, empty either side; heights unequal both ways |
| places | `m[k]` read, write, `+=`, `ref` in a block; absent key traps (`output trap`); a `ref` across `put` refused (an `error` test, p08b's text) |
| build modes | debug (overflow traps), release, `SYSL_COUNT_ALLOCS=1` (no net allocation after every test), ASan (`SYSL_EXTRA_CFLAGS=-fsanitize=address`, instrumented confirmed) |
| `@no_alloc` | a compile test calling `get`, `at_most`, `rank` and a cursor step from an `@no_alloc` function |
| traits | `Eq`/`Hash` agree for maps built in different insertion orders; `Ord` on sets of sets; `Display`; codec round trip through JSON (string keys as an object in key order, int keys as pairs, a duplicate refused) |

**Cells skipped, with the reason:** threads, because a `SortedMap` is a handle like `Map` and
crosses no domain (`Map` has no such cells either). Bare-metal targets, because the node buffers need
a heap. A `build-c --target thumb-freestanding` probe of a program that only *reads* a map built
elsewhere is not meaningful, since there is no allocator to build one.

The site gets a section on the container page with executable blocks, and `api/sysl-container.md` is
regenerated with `sysl doc`.

## 11. The benchmark plan

The benchmark goes in `~/dev/sysl-lang/bench` (`programs/sorted_map/`). Every run uses `--release`, goes
through marshal, and is timed on a quiet machine.

- **Workloads:** random insert; lookup, hit and miss; in-order walk; 1,000 range scans of 100;
  remove a random half; a 50/50 mixed load; `pop_first` as a priority queue; bulk build from sorted
  input.
- **Sizes and keys:** n = 10³, 10⁵, 10⁷; `int` keys, and 16-byte strings.
- **Against:**
  - the hashed `Map`, which should win on point operations, while the sorted map wins the walk and
    every range;
  - C++ `std::map` (clang -O2, libc++), which the sorted map should beat on every workload at
    n ≥ 10³;
  - Rust `BTreeMap` and `absl::btree_map` where installed, as the bar (within 1.5× is the target).
- **Measured:** ns per operation; peak RSS; bytes per entry (`SYSL_COUNT_BYTES=1`); retains per lookup
  (`SYSL_COUNT_RETAINS=1`, 0.14), which should be about 0 for `int` keys.
- **Sweeps:** `B ∈ {8, 12, 16, 24, 32}` to choose the default; linear against binary in-node search;
  `weak` against raw sibling links (§4.4); and, while G1 is open, **the slot-storage node against the
  inline one**, which puts a number on G1.

## 12. Open questions for the user

**Q1. Names.** (a) `SortedMap`/`SortedSet` · (b) `OrderedMap`/`OrderedSet` · (c)
`BTreeMap`/`BTreeSet`. **Recommend (a).** "Ordered" means insertion order in Python, Kotlin and
Java, and "BTree" names the representation, which may change (it did for `Map`).

**Q2. G1, inline node storage.** (a) Close it before this ships, with a compiler-known
`Inline[T, const N]` holding a live count (Rust's `arrayvec`, useful well beyond this, e.g. on
boards) · (b) close it by admitting `slot_empty()` as a field's value, with `slot_place`/`slot_vacate`
on inline arrays · (c) ship on slot storage now and change the layout behind the API later.
**Recommend (a)**, with G4 fixed in the same piece of work. The layout is invisible to callers, so (c)
is safe if 0.15 cannot wait. The benchmark will show what it costs.

**Q3. Range spelling (G2, G3).** (a) Widen range values: `lo..<hi` over any `Ord`, open ends as
values (`RangeFrom`/`RangeTo`/`RangeFull`, or a `Range` whose ends are `Bound`s), with iteration
still integer-only · (b) keep ranges integer-only, and the map takes `Bound` pairs plus `from`/`until`
· (c) both: ship (b) now and add (a). **Recommend (c).** `Bound` is needed anyway for an excluded low
end, and (a) is a language change that deserves its own design.

**Q4. Order statistics** (subtree counts: `rank`, `nth`, O(log n) `count`, and `split_off` knowing
both lengths without a walk). (a) Yes · (b) no, as in Rust and Java. **Recommend (a).** The cost is
one word per child in inner nodes, which hold about 1/B of the entries, and one add per level on
insert and remove along a path already being walked.

**Q5. A cursor under mutation.** (a) Re-seek (§5.2) · (b) fail-fast trap, as Java throws · (c)
memory-safe and unspecified, as `Map`'s cursor is today. **Recommend (a).** It costs one compare per
step and makes "remove while walking" correct. (b) turns a working loop into a crash.

**Q6. G5 and `entry`.** (a) Widen `-> ref` to a place inside an object a `*self` call answers
(which `IndexPlace` can already reach) and ship `entry(k, init)` · (b) ship `upsert` only, as `Map`
has. **Recommend (a)** with `upsert` as well. Either way, the misleading diagnostic wording is fixed.

**Q7. `m[k]` on an absent key.** (a) Trap, like every index · (b) `IndexSet` inserts on `m[k] = v`.
**Recommend (a)**. `put` is the insert, and (b) makes `m[k] = v` and `m[k] += 1` differ on a missing key.
