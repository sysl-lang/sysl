# Persistent collections — `PVec`, `PMap` — design

**Status: proposed (2026-11-26), phase 1: a design for the user to approve. Nothing is implemented.**
It targets 0.15 on dev, landing after 0.14.0 ships. Every claim marked **P*nn*** was run, on the installed
0.13.7 and on dev's compiler at `b8a9f22` (the two agree on every probe), from
`persistent-collections-probes/` (`zsh run.zsh <dev sysl> <its library>`). Each block quoted under a
probe is that probe's real output.

A persistent collection is immutable and structurally shared: an update answers a new collection,
the old one still reads exactly as it did, and the two share every node the update did not touch.
This page proposes a persistent vector (`PVec[T]`) and a persistent hash map (`PMap[K, V]`, with
`PSet[K]` over it). It is also a deliberate stressor of sysl's ownership model: shared immutable nodes,
counting, `&sync`, generic nodes over elements with destructors, and teardown of big trees.

## 0. What the probes found — read this first

Two **gaps** (Rule 9: the design below assumes they are closed, and does not work around them) and
one **bug**.

### G1 — a program cannot ask whether a `&T` is the only reference to its object

The fast path of every modern persistent collection (`im`, `rpds`, Swift's copy-on-write arrays) is
**update in place when the node's count is 1, copy otherwise**: Rust's `Rc::make_mut` / `get_mut`,
Swift's `isKnownUniquelyReferenced`. sysl has no way to ask.

Shortest input (P02):

```sysl
struct Node
    n: int

main()
    val r: &Node = Node(1)

    print(r.is_unique())
```

```
error: type 'Node' has no method 'is_unique'
```

- **What the reference says**: `memory.md § What a count costs` describes the strong count, the weak
  count and the boxes laid in a frame; nothing in `memory.md`, `attributes.md` or the library reads a
  count. `@unique` (`docs/design/affine-values.md`) is a different thing — a move-only *value* type —
  and does not answer this.
- **Why it is worth having — P03.** Reading the count through the raw tier (`ptr_cast` to the word
  before the payload) shows that the compiler's counts are already *exact enough to make it pay in the
  idiomatic spelling*: inside `push(self)` called as `v = v.push(2)`, the root's count is **1**; with a
  second name `held = v` alive it is **2**; inside `push_ptr(*self)` it is **1**. So
  `v = v.push(x)` on an unshared vector would never copy a node.

  ```
  two names (a, keep): (4346597984, 34359738369, 2)
  one name (a): (4346597984, 34359738369, 1)
  v = v.push(1):
    inside push(self): (4346598400, 6127888280, 2305843009213693953)
  v = v.push(2):
    inside push(self): (0, 0, 1)
  v = v.push(3) with a second name 'held':
    inside push(self): (2, 2, 2)
  v.push_ptr(4):
    inside push_ptr(*self): (0, 0, 1)
  ```

- **Why the raw read is not the answer**: the first `push` above found a box **laid in the frame**,
  whose count starts one past `frame_mark` (2⁶¹ + 1 = `2305843009213693953`), and a `&sync` box needs
  an acquire load, and the header layout differs between compact and full boxes. Only the compiler
  knows which box it is looking at. The question has to be a form the compiler answers.
- **The workaround it would take**: path-copy on every update (each `push` allocates
  ⌈log₃₂ n⌉ + 1 nodes even when nothing else holds the vector), or Clojure's *edit token* transients,
  which need a second, mutable builder type and a `@unchecked(Share)` promise on every node (P08,
  P08b) — the JVM has no counts to ask, which is why Clojure does it that way. Neither should be
  chosen *because* the language cannot ask.
- **Proposed**: a compiler form, safe tier, on `&T` and `&sync T` — see Q1.

### G2 — one node type cannot be written for both kinds of count

`&T` and `&sync T` are distinct types chosen where the object is made (`memory.md § &sync T`), and a
node names its children's kind in its field: `kids: [32]Option[&Node[T]]` or
`[32]Option[&sync Node[T]]`. So a vector usable in one domain *and* shareable across threads is
either two copies of every node type and every algorithm, or always `&sync` (which refuses every
element holding an ordinary count — P05 — so `PVec[&Thing]` would not exist). rpds solves it with a
pointer-kind parameter (`Vector<T, P: SharedPointerKind>`); `im` ships the code twice, as two crates.

Shortest input (P11) — the nearest spelling, the child's reference type as a parameter tied off by an
alias:

```sysl
struct Node[T, P]
    item: T
    kids: [2]Option[P]

type Plain = Node[int, &Plain]
type Shared = Node[int, &sync Shared]

main()
    val leaf: &Plain = Node(1, [None, None])
    val root: &Plain = Node(2, [Some(leaf), None])

    print(root.item + leaf.item)
```

```
error: 'value' of 'Some' is &Node[int, &?], but &Plain (aka &Node[int, &Node[int, &?]]) was given
```

- **What the reference says**: nothing lets a type parameter stand for a count kind;
  `generics.md § Variance does not arise` and `§ What is deliberately not here` do not mention it.
  `Frozen[T]` gets both kinds from one declaration only because its fields carry **no** count (raw
  words, released by hand in its `Drop`) — the raw tier, not a pattern for a tree.
- **The workaround it would take**: write `PVec`/`SyncPVec`, `PMap`/`SyncPMap` and their nodes twice
  (about 2 × 1,500 lines, every fix made twice), or hold children as raw words with hand-written
  retains through `into_raw`/`from_raw`, as `Frozen` does.
- **Proposed**: see Q2.

### B1 — bug: an alias that names itself is accepted and leaks `&?` into a diagnostic

`type Plain = Node[int, &Plain]` (P11) is accepted at its declaration and half-resolved: the message
above prints `&?`, an internal placeholder. `declarations.md § Type declarations` says an alias is
"a second spelling, interchangeable with the first"; a self-naming alias has no finite spelling, so
the declaration should be refused, naming the cycle (as `type 'Node' contains itself` is for a
struct). This is a compiler bug, independent of G2 however G2 is answered. It goes to a fix item
on the release line (Q8), not into this work's code.

### What the probes found working (not gaps)

| probe | risk | result |
|---|---|---|
| P01 | a generic node holding `&Node[T]` children, path-copied, elements `&Res` with a `Drop` | old version unchanged (`v1[2] = 2 v2[2] = 9`), untouched leaf shared by identity (`true`), each element dropped once, counted heap net zero |
| P04 | an immutable `&sync SNode[T]` tree, children in a fixed array in the node's own box, read by four threads with no lock and no copy | `500` |
| P05 | the `&sync` node at an element holding an ordinary count | refused, clearly: `'&sync SNode[&Cell]' may be reached from two domains at once, so every count inside it has to be atomic — but its 'items' reaches a '&Cell', whose count is not. Hold it as a '&sync Cell'` |
| P06 | a `&sync` node holding a run-time-sized `[]T` | refused (views are `Send`, not `Share`); the message names `&sync Frozen[T]` as the way |
| P07 | a `&sync` node with `&sync Frozen` children and elements, any length | `72`, two threads |
| P08 | a transient writing a `&sync` node in place | refused (`writes a field reached through a '&sync SNode', which is read-only`) |
| P08b | the same node `@unchecked(Share)` over `private` fields, written only by its own members under a token | `true false 10 2` |
| P09 | teardown of a million-link generic chain, `&Node[&Res]` with a destructor and `&sync SNode[int]` | `plain chain dropped: 1000000`, then the shared chain; constant stack, no recursion written |
| P10 | a HAMT node generic over `K: Hash + Eq, V`: `u32` bitmap, `count_ones`, a compressed child run of exactly `popcount` entries from slot storage, an enum entry of pair or sub-node | `Some(1) None Some(2) Some(11) Some(1)` — every version answers its own contents |
| P12 | a full 32-element leaf held inline in its box (one allocation), built from a generic run with no fill value through `slot_empty`, in both kinds and at `&Res` | `31 30 29 128`, 32 drops, counted heap net zero |

## 1. The API

Both are **values**: a small struct (a count and a root) that copies in a few words, as `List[T]`
already is. Assigning one, passing one, storing one never copies elements. Every update is a
`(self)` member answering the new collection.

### `PVec[T]`

```sysl
pvec[T]() -> PVec[T]                          // empty: no allocation
pvec_of[T](xs: []const T) -> PVec[T]          // bulk build, one pass, leaves filled in place

struct PVec[T]
    len(self) -> usize
    is_empty(self) -> bool
    get[I: Integer](self, i: I) -> Option[T]  // total form; v[i] traps past the end (Index)
    first(self) -> Option[T]
    last(self) -> Option[T]

    push(self, x: T) -> PVec[T]               // amortised O(1): into the tail
    pop(self) -> Option[(PVec[T], T)]          // the shorter vector and what was last
    set[I: Integer](self, i: I, x: T) -> PVec[T]       // traps past the end, as v[i] does
    update[I: Integer](self, i: I, f: T -> T) -> PVec[T]
    slice(self, from: usize, to: usize) -> PVec[T]     // see Q3 for its cost
    concat(self, other: PVec[T]) -> PVec[T]            // see Q3 for its cost
    to_buf(self) -> Buf[T]
```

**No transient type** if G1 is closed (Q4). With a uniqueness question, a builder is just a `var`:

```sysl
var w = v                        // shares everything with v
for x in xs do w = w.push(x)     // the first push copies one path (count 2); every later push
                                 // finds nodes only w holds (count 1) and writes them in place
```

That is a transient with nothing to misuse: there is no builder to read after it is frozen, no edit
token, and `v` is untouched because the nodes it holds have a count of 2 and are copied. If G1 is
refused, Q4 (b) is the transient this falls back to.

### `PMap[K: Hash + Eq, V]` and `PSet[K: Hash + Eq]`

```sysl
pmap[K: Hash + Eq, V]() -> PMap[K, V]
pmap_of[K: Hash + Eq, V](pairs: []const (K, V)) -> PMap[K, V]

struct PMap[K: Hash + Eq, V]
    len(self) -> usize
    is_empty(self) -> bool
    get(self, k: K) -> Option[V]
    contains(self, k: K) -> bool
    insert(self, k: K, v: V) -> PMap[K, V]             // replaces an existing value
    remove(self, k: K) -> PMap[K, V]                   // the same map (by identity) where k is absent
    update(self, k: K, f: Option[V] -> Option[V]) -> PMap[K, V]   // one walk: insert, change or remove
    merge(self, other: PMap[K, V], f: (K, V, V) -> V) -> PMap[K, V]  // f only where both hold k
    keys(self) -> PMapKeys[K, V]                       // cursors; `for (k, v) in m` walks pairs
    values(self) -> PMapValues[K, V]
```

`merge` walks the two tries together and **reuses every sub-trie only one side has, by reference**,
so merging a small map into a large one costs the small one's size times the depth, not the large
one's size (Clojure's `merge` is a fold of `assoc`; Scala 2.13's CHAMP `concat` does the structural
walk proposed here). `PSet[K]` is `PMap[K, unit]` behind its own names (`insert`, `remove`,
`contains`, `union`, `intersect`, `difference`), the last three structural for the same reason.

### Equality and hashing of whole collections

- **`PVec`**: `Eq` where `T: Eq` — lengths first, then a parallel walk that **skips any pair of
  sub-trees that are the same node** (`===`), so comparing a vector with an updated copy of itself
  costs one path, not n elements. `Hash` where `T: Hash`: the sequence hash a `Buf[T]` and a `[]T`
  give, so `pvec_of(xs).hash() == xs.hash()`.
- **`PMap`**: `Eq` where `V: Eq` — lengths, then a walk of the two tries, which is valid because the
  CHAMP layout (§ 2) is **canonical**: one set of keys has exactly one shape, whatever order it was
  built in. Identical sub-tries are skipped by `===`. `Hash` where `V: Hash`: **order-independent**,
  the wrapping sum of each entry's mixed `(k, v)` hash, so two equal maps hash alike regardless of the
  order collision nodes hold their entries in.
- **No cached hash in 0.15** (Q7): an immutable collection could cache its hash, but the cache is a
  write to something shared, which on a `&sync` root is an `Atomic` field in every root.

### The traits each implements

| trait | `PVec[T]` | `PMap[K, V]` | `PSet[K]` |
|---|---|---|---|
| `Walk` (`for x in v`) | cursor over the leaves, one leaf at a time as a `[]const T` | cursor over pairs | cursor over keys |
| `Index[usize, T]` | yes (traps past the end) | `Index[K, V]`: no — `m[k]` would have to trap on an absent key; `get` is the spelling | — |
| `Eq`, `Hash` | where `T` has them | where `V` has them | yes |
| `Display`, `Debug` | as a `Buf` renders (`[1, 2, 3]`) | as a `Map` renders | as a `Set` renders |
| `Default` (codec) | the empty vector | the empty map | the empty set |
| `Sequence[T]` (`sysl.seq`) | yes: `fold`, `any`, …, and `map`/`filter` answering a `[]U` as every implementer does | — | — |
| `Send`, `Share` | the compiler's answer, by what the nodes hold (§ 2.4) | same | same |

**What this assumes from the trait/algorithm layer** (designed by another agent): that `Iterate`
adapters work over any cursor, so `v.walk().map(f)` needs nothing here; that a `collect`-style
"build from an `Iterate`" trait, if it comes, is implemented by `PVec` and `PMap` through
`pvec_of`/`pmap_of`; and that an ordering trait is not needed — nothing here is ordered.
**What this assumes from the ordered-collections agent**: nothing, except that a persistent
B-tree (if they propose one) meets the same G2 question and should take the same answer.

### Crossing threads as `&sync`, with no copying

Yes — **if G2 is answered**. P04 and P07 show the language already allows an immutable `&sync` tree
to be read by any number of threads with no lock and no copy: a field reached through `&sync` is
read-only, which is exactly an immutable node's contract, and the compiler checks every element type
(P05). So a shared persistent map is the one data structure in the library that a program can hand
to every worker as configuration, a routing table or a snapshot, *and* keep updating on one thread,
publishing each new version by storing it in an `Atomic`-guarded or `Mutex` slot — readers keep the
version they took. Nothing else in `sysl.container` can do both: a `Map` crosses only under a
`Mutex`, and a `Frozen` cannot be updated.

The cost is the atomic count on each node a path copy touches, plus the string copy-in rule
(`memory.md § A string crosses into a shared object`) for strings first stored in a shared node — an
old node's strings are shared already and are not copied again.

## 2. The representation

### 2.1 `PVec`: a 32-way radix trie with a tail

- **Branching factor 32** (5 bits a level): a node of 32 children is one 256-byte box, which is four
  cache lines; depth is ⌈log₃₂ n⌉, so a million elements is four levels and every 64-bit length fits
  in thirteen. 32 is what Clojure, Scala, `im` and `rpds` all chose, and the reason holds here:
  lookups are a shift and a mask per level, and the copy a path update pays (≤ 32 words a level) is a
  memmove.
- **A tail** of up to 32 elements beside the root, as Clojure's: `push` writes the tail, and only
  every 32nd push moves a full leaf into the trie. `pop` reads the tail back the same way.
- **Plain radix, not RRB, in 0.15** (Q3). RRB (relaxed radix balanced, Bagwell–Rompf 2011, used by
  `im`'s `Vector` and Scala 2.12's `concat`) gives O(log n) `concat` and `slice` at the price of a size
  table in every relaxed node and a search instead of a shift on lookup through one. Scala 2.13 went
  back to plain radix (with prefix/suffix "fingers") and found `concat` rarer than lookup. Plain radix
  makes `slice` from the front and `concat` O(n); `slice` that keeps the front (`take`) is O(log n).
- **Interior node**: `struct Branch[T]` holding `kids: [32]Option[&Node[T]]` **inline in its own box**
  — one allocation, `None` is a null word (`memory.md § An Option of a reference is one word`), and it
  is the form P04 showed a `&sync` tree may hold.
- **Leaf**: `struct Leaf[T]` holding `items: [32]T` inline, built only when full, from the tail, through
  `slot_empty` (P12: no fill value needed for a generic `T`, one allocation, `sizeof(Leaf[int])` is
  128). The tail is the one run of fewer than 32: an exactly-sized `[]T` from slot storage in the
  one-domain kind, a `&sync Frozen[T]` in the shared kind (P06/P07: a `&sync` object holds no view).

### 2.2 `PMap`: a CHAMP trie

- **CHAMP** (Steindorfer & Vinju 2015), the HAMT variant Scala 2.13's `HashMap` uses: every node
  keeps **two** 32-bit bitmaps, one for entries held in place and one for sub-nodes, with entries
  first and sub-nodes after in one compressed run. Over Bagwell's HAMT (Clojure, `im`'s `HashMap`) it
  buys a **canonical** shape — a removal that leaves a node with one entry pulls it up — so equality is
  a structural walk, iteration touches entries before descending, and memory is smaller.
- **Hash**: the key's `u64` from `sysl.hash`, five bits per level, twelve levels plus four bits; keys
  whose whole hash collides go into a **collision node** (a short run of pairs, compared by `Eq`).
- **A node's run is exactly `popcount` long** (P10: `count_ones` on a `u32`, the run made with
  `slot_storage` and `slot_place_run`). In the one-domain kind the run is the node's own storage —
  the bitmaps ride in the parent's entry, so a node is one allocation. In the shared kind it is a
  `&sync Frozen[Entry]`, which is two allocations a node — as Clojure's node and its `Object[]` are.

### 2.3 Sharing through `&T` counting, and in-place update at count 1

Nodes are held by counted reference and never written once another holder exists. An update walks
root to leaf; at each node it asks (G1) whether the node is held once:

- **held once** — write the slot in place, keep going down;
- **held more** — copy the node (one memmove of ≤ 32 words, plus a retain of each child it shares),
  write the copy, and every node below is now reached only through the copy, so it is asked afresh.

P03 shows `v = v.push(x)` reaches count 1 for an unshared vector, and the count is 2 the moment
another name holds it, so the old version is never written. **Whether sysl can observe it**: not
today (G1). In the shared kind the write is to a `&sync` node, which the compiler refuses (P08); the
node types are `@unchecked(Share)` over `private` fields (P08b), and the promise is exactly true: a
member writes a node only after an acquire load found its count at 1, which no other thread can
raise because no other thread holds it.

### 2.4 Which kinds exist (G2)

Every node type exists in a one-domain kind (ordinary counts, any `T` including `&Thing`) and a shared
kind (atomic counts, `T: Share`). The shared kind is `Send` and `Share` by the compiler's own walk
whenever `T` is, so `PVec[int]` held in a `&sync` slot, captured by a `&sync Fn`, or sent through a
channel crosses with **no copy**: the receiver takes one atomic share of the root. How the two kinds
are spelled is Q2.

### 2.5 Drop of deep and wide structures

Nothing is written. P09 tore down a million-deep chain of each kind in constant stack, and the trie is
at most thirteen deep anyway; the risk is breadth, and the runtime's worklist (`memory.md § What a
count costs`: "teardown is iterative") takes a node's 32 children onto the same list. No node type
has a `Drop` of its own — every field is counted — so a node with no counted field or destructor
below it is freed on the spot without reaching the list. Standard-library types keep the full
three-word header (compact boxes are for a program's own types, `compact_box.sysl`), so a branch
costs 24 bytes of header on 256 of payload.

## 3. Comparison

| | Clojure `PersistentVector`/`HashMap` | Scala 2.13 `Vector`/`HashMap` | Rust `im` / `rpds` | Immutable.js | sysl (proposed) |
|---|---|---|---|---|---|
| memory | GC | GC | `Rc`/`Arc` | GC | ARC, freed the moment the last version goes |
| vector shape | 32-way + tail | 32-way radix + prefix/suffix fingers | `im`: RRB + chunked fingers; `rpds`: 32-way | 32-way + tail, origin offset | 32-way + tail |
| map shape | HAMT | CHAMP | `im`: HAMT; `rpds`: HAMT | HAMT | CHAMP |
| transients | edit-token builder | builders (copy at `result`) | `im`: in place at count 1 (`make_mut`) | `withMutations` (owner token) | in place at count 1 (G1) — no builder type |
| thread sharing | free (GC, immutable) | free | two crates (`im`/`im-rc`); `rpds` kind parameter | single-threaded | one type per kind (G2); checked by the compiler, `PVec[&Thing]` refused in the shared kind |
| element destructors | — | — | `Drop` | — | `Drop` runs once, when the last version holding it goes (P01) |
| leaf allocation | node + `Object[]` | `Array[AnyRef]` | inline chunk | node + array | inline `[32]T`, one allocation (P12) |

**Where sysl's is better**: elements are unboxed (`PVec[int]` stores 4-byte ints inline, where the JVM
and JS store boxed objects or tagged words); memory goes back deterministically, when the last version
goes, with no pause; the in-place path needs no builder and cannot be misused (Clojure's transient
used after `persistent!` throws at run time; here there is nothing to use); and sharing across
threads is checked at compile time by element type — Immutable.js cannot share, Scala and Clojure
share anything including mutable elements, `im` makes you pick a crate.

**Deliberately different**: no RRB in 0.15 (Q3); no `PMap[k]` subscript (an absent key is not a trap);
a `merge` that is structural rather than a fold of inserts; `PMap` hashes order-independently and
`PVec` hashes exactly as the `Buf` with the same elements.

## 4. How it stresses the language

| feature leaned on | probe | status |
|---|---|---|
| a generic node holding `&Node[T]` children | P01, P10 | works |
| path copy over slot storage (`slot_storage`, `slot_place_run`) | P01, P10 | works |
| a node generic over `T` with a `Drop`, every element dropped once | P01, P12 | works |
| a fixed `[32]T` built from a generic run with no fill value (`slot_empty`) | P12 | works |
| a count-1 question | P02, P03 | **G1** |
| one node type for both count kinds | P11 | **G2**, and **B1** found on the way |
| an immutable `&sync` tree read by many threads | P04, P07 | works |
| the compiler refusing a non-shareable element in the shared kind | P05, P06 | works, clear messages |
| writing a `&sync` node in place (transient or count-1 path) | P08, P08b | works under `@unchecked(Share)` over private fields |
| iterative drop of deep generic structures, both kinds | P09 | works |
| `Frozen` as variable-length shared node storage | P07 | works (two allocations a node) |
| `Hash`/`Eq` bounds through a node; `count_ones` | P10 | works |
| keeps: a cursor holding a node while the walk yields elements | — | not probed; nothing escapes a frame (every node is a `&T`) |
| closures by bare arrow for `update`/`merge` | — | used by `sysl.seq` already |

## 5. Test plan

Each test family gets a fresh three-letter prefix (`pvc_`, `pmp_`), library tests beside the files
(`library/sysl/container/pvec_tests.sysl`, `pmap_tests.sysl`), run with `--std`.

- **Model-based**: a seeded `sysl.rand` stream of operations — push, pop, set, slice, concat for
  `PVec`; insert, remove, update, merge for `PMap` — applied to the persistent collection and to a
  mutable oracle (`Buf[T]` / `Map[K, V]`); after every step, length, every element and a full walk
  must agree. Sizes chosen to cross every boundary: 0, 1, 31, 32, 33, 1023, 1024, 1025, 32·32·32 + 1;
  keys chosen to collide at level 0, at every level, and in all 64 bits (a key type whose `Hash` is a
  constant).
- **Old versions unchanged**: every version from a run is kept in a `Buf[PVec[T]]` beside a copy of
  its oracle; at the end every kept version is compared with its oracle — the test the in-place path
  (G1) can fail, and must be shown to fail by a mutation that skips the count question.
- **Count-1 path taken**: under `SYSL_COUNT_BYTES=1`, a million `v = v.push(x)` must allocate
  ~n·sizeof(T) + n/32 leaves, not a path per push (the linearity check doubles n).
- **Leaks**: every test run under `SYSL_COUNT_ALLOCS=1`, net zero; an element type with a `Drop` that
  counts, checked equal to the number made once every version is gone; and under ASan.
- **Thread sharing**: a shared `PMap[u64, u64]` and `PVec[int]` read by eight threads in a `scope`
  while the owner publishes new versions; every reader's version is internally consistent; run under
  `-fsanitize=thread` as well. The refusal of `PVec[&Thing]` in the shared kind pinned as an `error`.
- **Equality/hash laws**: `a == b ⇒ a.hash() == b.hash()`, maps built in shuffled orders equal and
  hash alike, `pvec_of(xs).hash() == xs.hash()`.
- **Benchmark** (`bench/`, not the gate): build n, read all, random set, iterate, for n = 10³…10⁷,
  against `Buf`/`Map` (the price of persistence) and against Clojure 1.12 and `im` 15 / `rpds` 1.x
  running the same operation mix on the same machine (the published numbers are not comparable across
  machines, so they are re-run, not quoted). Expected bands to check, not promises: lookup within 2–3×
  of `Buf`, `push` with G1 within 2× of `Buf.push`, map lookup within 2× of `Map.get`.

## 6. Open questions for the user

**Q1 — the uniqueness question (G1). How is it spelled?**
- (a) `r.is_unique() -> bool` on every `&T` and `&sync T`, safe tier: true where the strong count is
  one (a frame box's one past the mark) and no weak reference exists; an acquire load on `&sync`.
- (b) `sysl.raw.is_unique(r)`, imported, as the raw forms are.
- (c) Rust's shape: `make_unique(ref r)` that copies the object into `r` when shared and answers a
  place you may write.
- **Recommended: (a).** It cannot be misused to break safety — it answers a fact, and a `&T` is already
  writable through any alias — so it does not belong in the raw tier; (c) bakes "copy" into the
  language where the collection wants to copy its own way (a node copy retains its children).

**Q2 — the two kinds (G2).**
- (a) Two types, written twice: `PVec`/`SyncPVec`, `PMap`/`SyncPMap`.
- (b) A language feature: a type parameter that stands for a count kind (rpds's `SharedPointerKind`),
  e.g. `struct Node[T, K: Count]` with fields `&K Node[T, K]`, instantiated at `plain` or `sync`.
- (c) Always shared: one type with atomic counts, refusing every element that holds an ordinary count.
- (d) The kind chosen by position as `Frozen`'s is (`&PVec[T]` vs `&sync PVec[T]`), with nodes holding
  raw words and counting by hand.
- **Recommended: (b), as its own language item first**, then this work over it — it is the "write it
  the long way" signal Rule 9 names, and a persistent B-tree, a rope, or any shared tree hits it too.
  (c) loses `PVec[&Thing]`, the most common element; (d) puts a whole library in the raw tier.

**Q3 — RRB?**
- (a) Plain radix with a tail; `concat` and front `slice` O(n), `take` O(log n).
- (b) RRB now: O(log n) `concat`/`slice`, a size table per relaxed node, a slower lookup through one.
- **Recommended: (a) in 0.15**, with RRB a later item if a user needs fast `concat`; Scala 2.13 made
  the same call.

**Q4 — transients.**
- (a) None: the count-1 path (G1) is the transient (§ 1).
- (b) A builder type with an edit token and `@consumes freeze()`, as Clojure's — needed only if G1 is
  refused.
- **Recommended: (a).**

**Q5 — where and what names.** `sysl.container` beside `List` (already persistent) as `PVec`, `PMap`,
`PSet`; or a new `sysl.persistent` with `Vector`, `HashMap`, `HashSet`. **Recommended:
`sysl.container`, `PVec`/`PMap`/`PSet`** — one place to choose a structure, and short names that do
not collide with `Map`.

**Q6 — `PSet` in the same work?** **Recommended: yes**, it is a thin layer over `PMap[K, unit]` and its
set algebra is where CHAMP's structural walk pays most.

**Q7 — cache the hash?** (a) no; (b) an `Atomic[u64]` in each root, filled on first `hash()`.
**Recommended: (a)** in 0.15; measure first.

**Q8 — B1 (the self-naming alias).** **Recommended:** a separate fix item on the release line (a
refusal naming the cycle, with tests for direct, mutual and generic cycles), independent of Q2.

## 7. Stages, once approved

Order: B1 (fix item) and G1 (language item) and, under Q2 (b), G2 (language item) land first; then
`PVec` (one-domain kind, then shared), then `PMap`/`PSet`, each with its tests and its section on
`docs/content/library/container.md`, then the benchmark. Each stage is one agent and one branch.
