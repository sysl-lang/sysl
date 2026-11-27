# The trait and algorithm layer over collections — design (0.15)

**Status: PHASE 1, a proposal for approval. Nothing here is implemented.** Probes are in
`collection-traits-probes/`, one directory each (`zsh run.zsh [<dev sysl> <its library>] [-- p03 …]`;
`gen34.zsh` writes and times the long chains). Every result quoted below was produced by the installed
`sysl` 0.13.7 **and** dev's compiler at `b8a9f22`; the two agreed on every probe.

This layer is three things: **lazy iterator adapters** over `Iterate`, an **ordering story** (`Ord`,
a three-way `Order`, comparators, reversing and key-based orders, floats), and **generic algorithms**
written once (sorting, searching, partitioning, set operations over sorted input). It is also the
stressor the brief asked for, and it found three language gaps and two bugs. They come first.

---

## 0. What stops the design today — read this first

The design in §3–§5 is the one the language **should** allow: adapters as provided members of
`Iterate`, as Rust's `Iterator` has them. **Today it cannot be written.** Every adapter that takes a
closure runs into G1, every bounded consumer (`max`, `sum`, `rev`) into G2, and adding any adapter to
`Iterate` breaks every existing `*Iterate[T]` object through G3. B1 and B2 are compiler bugs, to be
fixed in this work (Rule 9a). Per Rule 9 the design does **not** route around these. §0.6 shows the
free-function form that compiles today, as evidence and not as the proposal.

### 0.1 B1 (bug): a trait default's own type parameter is captured by the implementing type's

**Shortest input** (`p18`):

```sysl
trait Pick
    pick[U](self, u: U) -> U = u

struct Box[U]
    v: U

impl[U] Pick for Box[U]

main()
    print(Box(1).pick("x"))
```

```
error: 'Box' already declares a type parameter 'U', so member 'pick' cannot declare one of that name
 --> main.sysl:3:5
error: 'u' of 'Box.pick' is int, but string was given
```

**What the reference says** (`traits.md § A default may assume exactly what its own trait declares`):
*"Every name in a default is resolved in the trait's module … `Self` is the one name a copy takes from
the `impl`."* The trait author cannot know what the implementing types will call their parameters,
and the second diagnostic shows the capture is real: the default's `U` is bound to `Box`'s `U = int`.
The check that fires (`hoist_members.sysl:490`, *"A member's parameters and its type's are two lists
that end up in one signature"*) is right for a member written **in** the type and wrong for a
default **copied into** it. For this layer it makes every adapter struct a landmine: `struct
MapIter[I, F, U]` cannot implement `Iterate` while `Iterate.map` declares `[U, F]` (p01, and the
library copy in the scratch probe: `'MapIter' already declares a type parameter 'F', so member 'map'
cannot declare one of that name`, reported at `library/sysl/iter.sysl:92`).
**Workaround it would take:** give every adapter struct parameter names no default uses. Not taken.
**Fix:** rename a copied default's own parameters hygienically, or treat the two lists as separate scopes.

### 0.2 B2 (bug): a closure inside a nested generic call takes on the outer call's closure type

**Shortest input** (`p29`):

```sysl
struct W[F]
    f: F

wrap[G: Fn1[int, int]](g: G) -> W[G] = W(g)

use2[T](w: T, h: (int, int) -> int) -> int = h(1, 2)

main()
    print(use2(wrap(x -> x * 10), (a, b) -> a + b))
```

```
error: 'g' of 'wrap' is a closure, but a closure was given — the closure asked for is '(int, int) -> int', and the one given is '(int) -> int'
 --> main.sysl:11:21
```

Binding the inner call to a `val` first compiles and runs (`p27` vs `p25`: `60`). Two bare arrows
nested the same way do too (`p30`: `33`). So the trigger is a closure whose type flows into an
inferred type argument (`W[G]`) of a call that is itself an argument of a call taking a closure. The
IR names closures by a per-function counter (`$closure0`, `$closure1`, … in the symbols of `p31`),
and `CLAUDE.md` lists the closure counter among *the four rewinds*. A tentative reading of the outer
argument list that does not rewind the counter would produce exactly this, and that is the first
place to look. **This is the shape every adapter chain written as calls takes:**
`fold(map(xs, f), 0, g)` fails the same way (`p25`, and `p22`'s five-stage chain). Method-call
chains (`xs.map(f).fold(0, g)`) nest the same way, so they need the fix too. **Workaround:** a `val`
per stage. Not taken.

### 0.3 G1 (gap or bug, see Q1): `Self` inside a member's own type-parameter bound is the parameter

A provided adapter must say *"`F` is a callable from my `Item`"*, and must name `F` in its result
(`-> MapIter[Self, F, U]`), so `F` has to be a written parameter rather than a bare arrow (a bare
arrow's type cannot be written, `generics.md § A bare arrow is not in the list`). Written either way
(`p19`, `p20`):

```sysl
trait It
    type Item
    next(*self) -> Option[Self::Item]

    apply[U, F: Fn1[Self::Item, U]](self, f: F) -> Option[U]        // or: … where F: Fn1[Self::Item, U]
        …
```

```
error: a closure implements no trait declaring the associated type 'Item' — 2 traits declare one (Iterate, It), and this type implements none of them
 --> main.sysl:6:21
```

`Self` in `F`'s bound was read as `F`, the closure. **What the reference says** (`traits.md § What
the implementation is held to`): *"`Self` inside such a bound is the associated type, not the type
implementing the trait. A bound constrains what it is written on, and `type W: Add` is written on `W`,
so the defaults of `Add`, which are `Self`, are filled from `W` … This is how `Self` reads in a bound
everywhere."* The paragraph is about the **bounded trait's own defaults** (`Add[Rhs = Self]`), and
its last sentence can be read to cover a `Self` the programmer **writes** inside a member's bound as
well. The compiler takes the wide reading. Under it **no trait member can relate its own type
parameters to the trait's `Self`**. The adapters (`map`, `filter`, `filter_map`, `take_while`,
`skip_while`, `flat_map`, `inspect`, `scan`), `chain[J: Iterate[Item = Self::Item]]`, `collect[C:
FromIter[Self::Item]]` (p03: `Buf[int] implements no trait declaring the associated type 'Item'`) and
`zip_with` are all unwritable as members. The bare arrow binds to `Self::Item` correctly
(`traits.md § A default's callable may name the projection`), but then the result cannot name the
closure's type, and `some Trait` is refused outside an `impl` (`traits.md § some Trait`).
**Workaround:** free functions (`p24`, `p31`). Not taken.

### 0.4 G2 (gap): a member's `where` cannot bound `Self` or `Self::Item`

`max`, `min`, `sum`, `product`, `is_sorted`, `rev`, `sorted` need a bound the trait does not carry:
the element is `Ord`, the element is `Add + Zero`, the cursor is double-ended. Rust writes `fn
max(self) where Self::Item: Ord` and `fn rev(self) where Self: DoubleEndedIterator`. In sysl (`p06a`,
`p06b`, `p06d`):

```
max(self) -> Option[Self::Item] where Self::Item: Ord
error: a 'where' clause names a type parameter and what it must implement, as 'where T: Display' — the ':' is what separates the two

max[E: Ord](self) -> Option[E] where Self: It[Item = E]
error: this declaration has no type parameter 'Self', so a 'where' clause has nothing to bound — it takes 'E'

rev(self) -> RevIt[Self] where Self: Back = RevIt(self)
error: this declaration has no type parameter 'Self', so a 'where' clause has nothing to bound — it takes none
```

**What the reference says** (`generics.md § A bound may be written out of line`): *"A clause may only
bound a parameter the declaration actually declares."* So this is the rule working as written, and
the gap is that the rule leaves no way to write a **conditional member**. The blanket-impl route
(`impl[I: Iterate[Item: Ord]] OrdIterate for I`) is closed by design (`p09`: *"'I' is a type
parameter of this 'impl', so it stands for every type at once"*; `compact.md`: *"A blanket
`impl[T: Bound]` is allowed only over a family the compiler closes"*). A refining trait would need an
explicit `impl` per cursor per element type. **Workaround:** free functions bounded `[I: Iterate[Item:
Ord]]` (`p06c` prints `3`). Not taken.

### 0.5 G3 (gap): one provided adapter makes `Iterate` unerasable

`p07`/`p08`, a trait whose provided `map` returns `MapIt[Self, F, U]`:

```
error: 'map' of 'It' mentions 'Self' away from its receiver, and an erased value has forgotten which type that is — so there is no '&It' to form
 --> main.sysl:43:13   val o: &It[int] = Count(0, 3)
```

**What the reference says** (`traits.md § Object safety`): the four rules decide whether a trait has
an object *at all*, and a member with its own type parameters *"leaves the table, not the trait"*. An
adapter is both kinds of member at once, so the strict rule wins. In addition (`generics.md § A trait
object satisfies a bound`), *"an object does not satisfy a bound on such a trait"*, so even a
consumer like `fold[A]` (no `Self` in its result) would stop `*Iterate[int]` from meeting `[I:
Iterate]`. **Adding any adapter to `Iterate` therefore breaks every erased cursor in the program:**
`io/read.sysl`'s `*Iterate[string]` sources, `library/sysl/tests.sysl:349,360`, and
`compiler/…/tests_itt_iterate_trait.sysl:469–497`. Today (`p33`) an erased cursor chosen at run time
is formed, returned and handed to `count_all[I: It]` (`3 3`), and that has to keep working. Rust
solves it with `where Self: Sized` on each adapter plus `impl Iterator for &mut I`.
**Workaround:** a second trait the cursors opt into one by one (§0.6 explains why that is not one).

### 0.6 What does compile today, and why it is not the proposal

Adapters as **structs generic over source and closure**, the bound on the struct's `impl`, built by
**free functions**, chain bound stage by stage to `val`s (because of B2). All of these work on both
compilers:

| shape | probe | result |
|---|---|---|
| adapter struct `MapIt[I, F, U]` with `impl[I: It, F: Fn1[I::Item, U], U] It for …` | p24 | `60` |
| five-stage chain, each stage a `val` | p31 | `7392 7392` (chain = hand loop) |
| `collect` generic over its target, return-type driven (`val b: Buf[int] = collect(c)`; `collect[Buf[int], Count](…)`) | p23 | `4 3 3 5` / `3` |
| closure **writing** an outer `var` through an adapter; closure **reading** a `var` changed after the adapter was built | p32 | `5 10` / `303` (both by reference: the adapter never leaves the frame) |
| peekable (state names `Option[I::Item]`), zip, chain (`B: It[Item = A::Item]`), flat_map (inner cursor made by the closure) | p33 | `0 0 1` / `0 10 1` / `4` / `6` |
| a slice as its own cursor (`impl[T] It for []const T`, `next` shortens the view) | p33 | `3 3` |
| an erased cursor chosen at run time, returned as `&It[int]`, handed to `count_all[I: It]` | p33 | `3 3` |
| one function over every container, `total[C: Walk](c)` with `for x in c` | p10 | `3 7` (Buf and List) |
| `Rev[T]` reversing `Ord`, `Heap[Rev[int]]` as a max-heap, `sort_by_key`, derived `Ord` on a struct and a data enum, floats through `total_lt` | p12 | `9`, `fig banana`, `z a b true`, `true true`, `-1 true` |

It is not the proposal because (a) `filter(take(map(xs, f), 3), p)` reads inside out, and B2 forces a
`val` per stage; (b) the user's rule is that a gap stops the work; and (c) the only route that keeps
method syntax, a separate `trait Adapt: Iterate` that each cursor opts into with an empty `impl`, is
the per-type boilerplate the missing blanket `impl` would have removed. It leaves a user's own cursor
without adapters until its author writes the line, and it still meets G1 for every closure-taking
member.

### 0.7 Friction (not gaps; each needs a decision, see §9)

- **F1, field and member share one namespace** (`p15`): `error: type 'Upto' has both a field and a
  member named 'count'`, reported at the trait's default. In the library today `DequeCursor` and
  `RingCursor` hold a field `count`, and `Chars` has a member `count(self) -> usize`. Every provided
  name (`count`, `last`, `step_by`, `take`, …) is reserved in every cursor anyone writes.
- **F2, an `impl` may not reuse its trait's parameter name**: `impl[E] FromIter[E] for Buf[E]` →
  `trait 'FromIt' already declares a type parameter 'E', so this 'impl' cannot declare one of that
  name` (p03). This one is deliberate and pinned (`tests_generic_trait_error.sysl:386`). It explains
  why `seq.sysl` writes `impl[E] Sequence[E]` against `trait Sequence[T]`.
- **F3, `Sequence.map` and a lazy `Iterate.map` on one type are ambiguous** wherever both traits
  are in scope (`traits.md § Two traits may declare a member of one name`: *"'size' on Box comes
  from Weigh and Measure, and each is in scope here"*). `Range` implements both today, and so would
  a slice if it became an `Iterate`.

---

## 1. What exists (the survey)

| what | where | shape |
|---|---|---|
| `Eq`, `PartialOrd` (`lt` + `le`), `Ord: PartialOrd` (empty; an `impl Ord` writing `lt` alone is the whole order) | `ops.sysl` | derivable for structs and enums (declaration order, variant then fields); tuples, `[]T`, `[N]T` lexicographic; `unit`, `never` |
| `Hash` | `hash.sysl` | `impl[T: Integer] Hash for T`, strings, `ByAddress[T]` |
| `Iterate` (`type Item`; `next(*self) -> Option[Item]`) | `iter.sysl` | object `*Iterate[T]` / `&Iterate[T]` |
| `Walk` (`type Walker`; `walk(self)`) | `iter.sysl` | **`Walker` unbounded**: `Buf` answers `[]T` (not an `Iterate`), `Frozen`, `List`, `Deque`, `Map`, `Set`, `Ring`, `InlineRing`, `ErrorTrace` answer cursors |
| cursors implementing `Iterate` | `Range`, `ListCursor`, `DequeCursor`, `SetCursor`, map `Cursor`, `RingCursor`, `InlineRingCursor`, `FrozenWalk`, `Chars`, `CharIndices`, `Graphemes`, `io.Lines`, `fs.Walk`/`Matching`, `term.Editor`, `ErrorTraceWalk` | one method each, no adapters |
| `Sequence[T]`, eager: `fold any all find position count_where each map filter flat_map` (+ free `generate`) | `seq/seq.sysl`, `seq/range.sysl` | `[]const E`, `Buf[E]`, `[N]E`, `Range[E]`, and **nothing else** |
| `sort`/`sort_by` (introsort), `sort_stable`/`_by` (**caller-supplied scratch**, `@no_alloc`), `is_sorted`/`_by`, `binary_search`/`_by` → `(bool, usize)` | `slices/sort.sysl` | slices only, `lt` comparators |
| `index_of`/`_from`, `last_index_of`/`_from`, `contains`, `min_index`, `max_index` → `Option[usize]`, `equal`, `starts_with`, `ends_with`, `reverse`, `swap`, `fill`, `split_at`, `copy`, `sum`, `dot`, `zeroize` | `slices/slices.sysl` | slices only |
| `min`, `max`, `clamp` (`Ord`); `approx_eq`; `Float.total_lt` (IEEE totalOrder), float `min`/`max` | `math/compare.sysl`, `math/float.sysl` | free functions / members |
| `Heap[T: Ord]` | `container/heap.sysl` | min-heap; no comparator; no walk, by design |
| private three-way `cmp -> int` | `BigInt`, `Decimal`, `Rational`, `float_text.Big` | each writes `lt`/`eq` from it |

**Where it is inconsistent:**

1. **Two iteration surfaces that do not meet.** A slice, a `Buf`, an array and a `Range` answer
   `fold`/`any`/`find` eagerly; a `List`, `Deque`, `Map`, `Set`, `Ring` or `Frozen` answers none of
   them, and supports only `for`. A cursor (`"ab".chars`) has nothing but `next`.
2. **`Walk.Walker` is unbounded**, so `c.walk().next()` is valid for a `List` and not for a `Buf`.
   `for` hides it (`p10` works), and anything generic that wants a cursor cannot.
3. **Ordering operations live in three places** (`sysl.slices`, `sysl.math`, `Heap`) with three
   conventions: `binary_search` answers `(bool, usize)`, `min_index` answers `Option[usize]`, `math.min`
   answers a value. There is no `min`/`max` of a sequence, `sum` exists for slices only, and nothing
   takes a key.
4. **Length is three spellings**: a slice's `.len` (property), `Buf`/`List`/`Heap`'s `.len()`,
   `Map`/`Deque`'s `count` property beside `len()`. `Sequence.count_where` is named after the field
   collision (F1).
5. **No three-way comparison**: four types each re-invent a private `cmp -> int`, and the name
   `Ordering` is taken by `sysl.sync.Ordering` (memory orders).
6. **No reversing order**: `heap.sysl`'s header says a max-heap *"needs a wrapper type the caller
   would have to declare"*, and none is provided (`p12` shows the wrapper is six lines).
7. **Stale prose**: `docs/content/library/seq.md § Eager` says *"`Iterate` still carries its element as
   a parameter"*, but it is `type Item`. Fix in phase 2.

---

## 2. The ordering story

**`Ord` stays the trait, `lt` stays its one method, and a comparator stays a value.** Both forms
are needed and each has one job.

- **The trait** (`T: Ord`) is the default order: the one `sort`, `binary_search`, `Heap`, a B-tree and
  `max` use when nothing is said. Keep it exactly as it is: one method, derivable, lexicographic for
  tuples and slices, and the operators `<` `<=` `>` `>=` read through it. This is Swift's `Comparable`
  shape (`<` only), not Rust's (`cmp` + `Eq`), and it is the right one here: a derived or hand-written
  order is one line, and `PartialOrd`'s explicit `le` already carries the NaN story.
- **The comparator** is a bare-arrow `lt: (T, T) -> bool` (a strict weak order, as `sort_by` already
  takes it), so it is monomorphized and inlined (`sort.sysl` header; p31's IR). Every `_by` algorithm
  takes one, and every `_by_key` takes `key: T -> K` with `K: Ord`.

**Added:**

```sysl
/** The answer of a three-way comparison. Not `Ordering`: that name is sysl.sync's memory order. */
enum Order derives Eq, Ord, Hash, Display
    Less
    Equal
    Greater

    reverse(self) -> Order
    then(self, next: Order) -> Order                  // lexicographic chaining
    is_lt / is_le / is_gt / is_ge / is_eq / is_ne (self) -> bool

trait Ord: PartialOrd
    /** Two `lt`s by default; a type with a cheaper three-way answer (string, slice, BigInt, Decimal,
     * Rational) writes `override compare`. */
    compare(self, rhs: Self) -> Order = if self < rhs then Order.Less elif rhs < self then Order.Greater else Order.Equal
```

`Ord` is already unerasable (operator catalog), so a provided member costs nothing there. A B-tree's
node search is the reason: one `compare` per key instead of `lt` twice. The four private
`cmp -> int` become `override compare` (breaking for nobody, since they are private).

- **Equality under an order is equivalence**, `compare == Equal`. No algorithm in this layer asks
  for `Eq` in addition to `Ord` (`binary_search` already doesn't). `Ord` does not require `Eq` (Q7).
- **Reverse:** `struct Rev[T]` with `impl[T: Ord] Ord for Rev[T]` (`rhs.v < self.v`) and
  `Eq`/`Hash`/`Display` through `v`. `Heap[Rev[int]]` is the max-heap (`p12`: pops `9`).
- **Key-based:** `sort_by_key`, `sort_stable_by_key`, `binary_search_by_key`, `min_by_key`,
  `max_by_key`, `is_sorted_by_key`, each `key: T -> K` with `K: Ord`. (`p12`'s `sort_by_key` is
  one line over `sort_by`.) There is no `ByKey` wrapper type: an ordered container keyed by a
  projection stores the projection as its key.
- **Floats are `PartialOrd`, never `Ord`** (unchanged; `derives Ord` already refuses a float field).
  A float sorts by `sort_by(xs, (a, b) -> a.total_lt(b))` (works, `p12`: `-1 true`, NaN last). Added:
  `struct Total[F: Float]` with `Ord` through `total_lt` and `Eq`/`Hash` through the bits, so a float
  can key a B-tree or a heap and `sort` can take `[]Total[real]`.
- **Derived orders** work for structs (field order) and data enums (variant order, then fields)
  (`p12`: `z a b true`, `true true`). Nothing changes; the reference already says it.

---

## 3. Iteration: the traits

```sysl
trait Iterate                         // unchanged core: one required member, object-safe core
    type Item
    next(*self) -> Option[Self::Item]

    size_hint(self) -> (usize, Option[usize]) = (0, None)      // a collect reserves from it
    … every adapter and consumer of §4, provided ...

trait Back: Iterate                   // double-ended: slices, Range, Deque/Ring/Buf cursors, B-tree ranges, PVec
    next_back(*self) -> Option[Self::Item]
    rev(self) -> Rev[Self] = …        // and rfind, rfold, rposition

trait FromIter[E]                     // what `collect` builds
    from_iter[I: Iterate[Item = E]](it: I) -> Self

trait Walk
    type Walker                       // Q4(a) adds `: Iterate`; Q4(b) leaves it unbounded
    walk(self) -> Self::Walker
```

- **How a slice starts a chain depends on Q4.** Under **Q4(a)** a slice **is** an `Iterate`
  (`impl[T] Iterate for []const T`, whose `next` shortens the view; `p33` works) and a `Back`. `for x
  in xs` is untouched, because the compiler walks a slice by address before it asks for `Iterate`.
  `Walk.Walker` can then be bounded `: Iterate`, since `Buf`'s walker, a view, now is one. Under
  **Q4(b)** (recommended for 0.15) a slice is not an `Iterate`, so F3 cannot arise. `Sequence` gains
  `iter(self) -> SliceIter[T]`, a cursor struct holding the view (the same `next`), which a `Buf`
  and an array reach through `Sequence` too, and `Walk.Walker` stays unbounded.
- **The chain head**: a container's `walk()` for every cursor-walked container (`List`, `Map`, …),
  `iter()` for slices, `Buf`s and arrays under Q4(b). `xs.iter().map(f).filter(p).collect()` reads
  the same over all of them.
- **Ownership**: an adapter **owns its source by value**, as `self` (`map(self, …)`), and the `for`
  that drives it iterates a copy, as `Iterate`'s header already says. A container's cursor holds `&`
  handles (a `List` cell, a `Map` core), so copying a cursor shares storage under the count rather
  than copying elements. A slice cursor is the view, and the view keeps its storage alive. `by_ref`
  (adapt without consuming) is `impl[I: Iterate] Iterate for *I` on the pointer shape (to be probed
  in phase 2).
- **Closures** are taken as written type parameters (`F: Fn1[Self::Item, U]`), so they are
  monomorphized and never boxed. Capture follows `expressions.md § A closure that does not outlive
  its variables captures them by reference`: a chain consumed in the frame captures by reference.
  That is what lets `inspect`/`map` count into an outer `var` (`p32`: `5 10`), and it means a
  closure reads a captured `var` **when the chain is drained**, not when it was built (`p32`: `303`).
  A chain returned from a function captures by value. The compiler decides; `--explain-escapes`
  says which. Rust refuses the `303` program with a borrow error. sysl runs it, and the library page
  will say so with that example.
- **Lazy, and free of cost**: nothing runs until a consumer calls `next`. p31's five-stage chain at
  `-O2` is one function with **no call, no allocation and no load or store of an adapter struct**.
  Every `next` is inlined and the state lives in registers (SROA). It is **not** instruction-identical to the
  hand loop (91 IR lines against 36): the `Option[int]` between stages survives as
  `insertvalue`/`extractvalue` pairs and the `take` counter as a second loop level. The test bar is
  therefore *no call, no allocation, no adapter in memory*, not *same instructions* (§7).

## 4. The adapters and consumers

Every row is a provided member of `Iterate` (or `Back`) **once G1–G3 are resolved**. The struct each
adapter answers is public and named, so a function can return a chain, and `p33` shows each shape's
`impl` compiles today.

| member | answers | notes |
|---|---|---|
| `map[U, F: Fn1[Self::Item, U]](self, f: F)` | `Map[Self, F, U]` | |
| `filter[P: Fn1[Self::Item, bool]](self, p: P)` | `Filter[Self, P]` | |
| `filter_map[U, F: Fn1[Self::Item, Option[U]]](self, f: F)` | `FilterMap[Self, F, U]` | |
| `take(self, n: usize)` / `skip(self, n: usize)` | `Take[Self]` / `Skip[Self]` | |
| `take_while[P]` / `skip_while[P]` | `TakeWhile[Self, P]` / `SkipWhile[Self, P]` | |
| `step_by(self, n: usize)` | `StepBy[Self]` | `n == 0` traps, as a zero divisor does |
| `enumerate(self)` | `Enumerate[Self]`, Item `(usize, Self::Item)` | |
| `zip[J: Iterate](self, other: J)` | `Zip[Self, J]`, Item `(Self::Item, J::Item)` | p33 |
| `chain[J: Iterate[Item = Self::Item]](self, other: J)` | `Chain[Self, J]` | p33 |
| `flat_map[J: Iterate, F: Fn1[Self::Item, J]](self, f: F)` | `FlatMap[Self, F, J]` | p33 |
| `flatten(self) where Self::Item: Iterate` | `Flatten[Self]` | needs G2 |
| `peekable(self)` | `Peekable[Self]` with `peek(*self) -> Option[Self::Item]` | p33 |
| `inspect[F: Fn1[Self::Item, unit]]` | `Inspect[Self, F]` | |
| `rev(self)` (on `Back`) | `Rev[Self]` | the cursor `Rev`, distinct from the order wrapper (the latter is `sysl.cmp.Rev`, Q6) |
| `windows(n)` / `chunks(n)` / `chunks_exact(n)` | `Windows[T]` / `Chunks[T]`, Item `[]const T` | **slice members** (in `Sequence`, Q4), not `Iterate`'s: a window is a view, which only contiguous storage has |
| **consumers** | | |
| `fold[A](self, init: A, f: (A, Self::Item) -> A) -> A` | | bare arrow: nothing to name in the result |
| `each(self, f: Self::Item -> unit)` | | `Sequence`'s name, kept |
| `count(self) -> usize`, `count_where(self, p)` | | F1, Q5 |
| `any`, `all`, `find`, `position`, `last`, `nth(n)` | | as `Sequence`'s, same names |
| `min`, `max` `where Self::Item: Ord` | `Option[Self::Item]` | G2 |
| `min_by(lt)`, `max_by(lt)`, `min_by_key(key)`, `max_by_key(key)` | `Option[Self::Item]` | `lt` comparators; ties: first for `min`, last for `max` (Rust's rule, so `min`/`max` of a stable order are stable) |
| `sum`, `product` `where Self::Item: Add + Zero` / `Mul + One` | `Self::Item` | G2 |
| `is_sorted` `where Self::Item: Ord`, `is_sorted_by(lt)` | `bool` | |
| `eq_iter[J: Iterate[Item = Self::Item]](other) where Self::Item: Eq` | `bool` | G1+G2 |
| `collect[C: FromIter[Self::Item]](self) -> C` | | G1; return-type driven, `p23` proves the free form |

**`FromIter` implementations**: `Buf`, `[]T` (through a `Buf`), `List` (built reversed, then
reversed), `Deque`, `Heap` (`heap_of`'s linear heapify), `Set` and `Map` (Item `(K, V)`), `string`
(Item `char`), and the new collections (§6).

## 5. Generic algorithms

In-place algorithms take **a slice**, and every contiguous container reaches them through its view
(`buf.view_mut()`, `arr[..]`). They stay in `sysl.slices`, which keeps its `@no_alloc` and
no-capability promise. Read-only and merging algorithms take **any `Iterate`**, so they serve every
container, the B-tree's cursors and the persistent ones included.

| algorithm | signature (slice forms in `sysl.slices`) | container kinds |
|---|---|---|
| sort, unstable | `sort[T: Ord](xs: []T)`, `sort_by(xs, lt)`, `sort_by_key(xs, key)` | contiguous (exists, plus `_by_key`) |
| sort, stable | `sort_stable(xs, scratch) -> bool`, `_by`, `_by_key` | contiguous; caller scratch kept. Allocating forms `sorted(xs) -> []T`, `Buf.sort_stable()` live in `sysl.buf` (Q9) |
| binary search | `binary_search(xs, x) -> (bool, usize)`, `_by`, `_by_key`, `partition_point(xs, p) -> usize` | sorted contiguous (exists, plus two) |
| partition | `partition(xs, p) -> usize` (in place, unstable, answers the split), `is_partitioned` | contiguous |
| dedup | `dedup(xs) -> usize` (in place, answers the new length; `Buf.dedup()` truncates), `dedup_by(eq)`, `dedup_by_key` | contiguous; and `Iterate.dedup()` lazily for any sorted cursor |
| reverse | `reverse(xs)` (exists) | contiguous; `rev()` lazily for `Back` |
| rotate | `rotate_left(xs, k)`, `rotate_right(xs, k)` (three reversals; no allocation) | contiguous |
| merge | `merge(a, b, out: []T) -> bool` (two sorted slices into storage the caller gives); `merge(a, b)` lazily over two `Iterate`s with `Item: Ord` | slice / any sorted cursor |
| is_sorted | exists; plus `_by_key`; `Iterate.is_sorted` | contiguous / any |
| min / max | `min(xs) -> Option[T]`, `max`, `_by`, `_by_key` beside the existing `min_index`/`max_index` | contiguous / any (`Iterate.min`) |
| set operations | `union`, `intersection`, `difference`, `symmetric_difference` over two **sorted** `Iterate`s with `Item: Ord` (lazy merge-walks, `Item` from the left on ties) | any sorted cursor: sorted slices, B-tree sets |
| includes | `is_subset(a, b)` over sorted cursors | any sorted cursor |

Not proposed: `nth_element`/`select`, `next_permutation`, `shuffle` (that belongs in `sysl.rand`),
heap operations on a bare slice (`Heap` is the API).

## 6. What the other two designs need from this one

**Ordered collections (B-tree)**:
- `Ord` with the new **`compare`/`Order`** for one-comparison node search;
- **`Rev[T]`** and **`Total[F]`** so max-first maps and float keys need no user type;
- **`Back`** for their cursors and ranges (`m.range(a..b).rev()`);
- **`FromIter[(K, V)]`** / **`FromIter[K]`** impls (`collect` into a `BTreeMap`);
- their `walk()` answering an `Iterate` cursor (every non-contiguous container's already does), so it
  is an adapter head;
- the **set operations** of §5, which need nothing from them beyond a cursor of `Ord` items;
- a decision on comparators (Q8): this design proposes `K: Ord` plus wrapper types, not a comparator
  type parameter.

**Persistent collections**: *nothing ordered* (their doc says so), plus
- `Iterate` adapters over their cursors (their doc assumes `v.walk().map(f)` works, which is G1–G3);
- **`FromIter`** impls through `pvec_of`/`pmap_of`;
- `Back` for the `PVec` cursor (optional).

Their doc lists `Sequence[T]` for `PVec`, so Q4's answer reaches them too.

## 7. Comparison

| | sysl (proposed) | Rust | C++20 ranges | Swift | Scala 2.13 |
|---|---|---|---|---|---|
| default order | `Ord`, one method `lt`, derivable | `Ord: Eq + PartialOrd`, `cmp → Ordering` | `<` / `std::strict_weak_order`, `<=>` | `Comparable`, `<` | `Ordering[T]` typeclass (implicit) |
| three-way | `compare → Order`, provided from `lt` | primary | `<=>` | none in the protocol | `compare → Int` |
| comparator value | bare arrow `lt`, inlined | closure `FnMut → Ordering` | comparator + projection | `by: (T, T) -> Bool` | `Ordering` instance |
| floats | `PartialOrd` only; `Total[F]`; `total_lt` | same (`total_cmp`) | `partial_ordering` | `<` with NaN anomalies | `Ordering.Double.TotalOrdering` |
| adapters | lazy, provided members of `Iterate` | same | lazy views, `|` pipe | **eager** by default, `.lazy` | **eager** (strict), `.view` lazy |
| closure cost | type parameter, inlined | same | same | generic specialization, may box | megamorphic `Function1` |
| collect | `FromIter`, return-type driven | `FromIterator` + turbofish | `std::ranges::to<C>` (C++23) | `Array(seq)` / initializers | `to(Factory)` |
| erased iterator | `*Iterate[T]` + adapters on it (G3 fix) | `&mut dyn Iterator` | `any_view` (not standard) | `AnySequence` | `Iterator[T]` is already erased |
| stable sort | caller scratch, never allocates (+ allocating convenience) | allocates | allocates (may fall back) | allocates | allocates |
| binary search | `(bool, usize)` | `Result<usize, usize>` | `lower_bound` iterator | none in stdlib | `Searching.Found/InsertionPoint` |

**Better:** a total order is one method and derives; a stable sort that can run with no allocator;
`binary_search` that does not call a miss an error; capture by reference that lets a chain update
local state without a borrow fight (p32); objects that keep their adapters (with the G3 fix, as
Rust's `&mut dyn Iterator` does, with no `Sized` bound to write).
**Deliberately different:** comparators are `lt` and not three-way, as in Swift and C++, because
`Ord` is `lt`; `Order` is not named `Ordering`; there is no pipe syntax and no higher-kinded
`collect` (`generics.md` excludes `F[_]`); a slice's eager `Sequence` survives beside the lazy
layer if Q4 says so, where Rust has only the lazy one and Scala only the eager one.

## 8. Test plan

- **Property tests against slice oracles** (a fresh 3-letter prefix in `library/sysl/iter/`): for
  each adapter and consumer, random `Buf[int]` inputs from `sysl.rand` with a fixed seed, lengths
  0…64 including 0 and 1. The chain's `collect[Buf[int]]()` is compared with the same operation
  written as a plain loop over the slice. `Sequence`'s eager `map`/`filter`/`flat_map`/`fold`/`find`
  are the oracles where they exist. **The same chain runs over every cursor kind**: slice, `Buf`
  walk, `List`, `Deque`, `Ring`, `Range`, `Chars`, an erased `*Iterate[int]`, and (in their own
  branches) the B-tree and persistent cursors. A mutation pass breaks each adapter's edge
  (`take(0)`, `skip(n > len)`, `step_by(1)`, an empty side of `zip`/`chain`) and checks a test goes
  red.
- **Sorting and the algorithms**: permutation preserved (sort a `Buf` of `(key, index)` pairs and
  check the multiset), result `is_sorted`, stability by index among equal keys, every short length
  0…20 exhaustively, adversarial inputs (sorted, reversed, all equal, organ pipe). `binary_search`,
  `partition_point` and the set operations are checked against a linear scan and against `Set`.
  `rotate` is checked against a copy-based rotation for every `k` in `0..=len`. `Order`/`compare`
  are checked against `lt` over every pair of a small domain, plus `Rev` and `Total` (NaN, ±0,
  ±inf).
- **Zero cost (IR)**: a compiler test emits a five-stage chain and the hand loop, optimizes at the
  release level, and asserts that the chain's function has **no `call`, no allocator symbol and no
  `alloca` surviving**. The same check runs over an erased cursor to pin that the object form makes
  exactly one indirect call per element. It is the `p31` probe made an assertion.
- **Build time**: a generated chain of 40 alternating stages (`gen34.zsh` today: the bodies phase
  takes 66 / 84 / 134 / 364 ms at 5 / 10 / 20 / 40 stages, and the longest symbol is 414 / 781 /
  1555 / 3105 chars, which is linear). The test pins a ceiling (bodies < 2 s at 40 stages) and a
  linear symbol length, so a quadratic regression in instantiation or mangling is caught.
- **The bugs' own tests**: B1 (default and implementing type share a parameter name; the trait in a
  library and the `impl` in a program; a property and a member default), B2 (`p29`; the nest at
  three levels; the inner closure capturing; the outer taking `&Fn`), each with the edge cells
  `compiler-notes.md § Writing tests` asks for.
- **Docs**: `library/iter.md` (new), `library/seq.md`, `library/slices.md` and `reference/traits.md`
  examples run under `SYSL_DOCS`. The `303` capture example is one of them.

## 9. Open questions for the user

**Q1. G1: what does `Self` mean inside a member's own bound?** (a) **The implementing type**, as in
Rust; `Self` reads as the bounded type only in a trait's `type X: Bound` declarations and in the
bounded trait's own defaults. (b) A new spelling for the outer `Self`. (c) Allow `some Iterate[Item =
U]` as a provided member's result, so the closure can stay a bare arrow. **Recommend (a)**: it is
what a reader assumes, it needs no syntax, and only the wide reading of one reference sentence
stands against it. It is perhaps a bug rather than a gap.

**Q2. G2: conditional members.** (a) **Allow `where Self: Trait` / `where Self::Item: Trait` on a
member**; the member exists only where the condition holds and is checked at the call, as an `impl`'s
condition already is (`traits.md § Conditional conformance`). (b) Refining traits per bound with
explicit `impl`s per cursor. (c) Free functions for every bounded consumer. **Recommend (a)**: the
compiler already has exactly this check for conditional `impl` members ("*a member the 'impl' … gives
'Box[N]' only where its condition holds*").

**Q3. G3: objects and provided generic members.** (a) **A provided member that has type parameters
or mentions `Self` leaves the table and the object's bound check, and is called on an object by
instantiating its default with `Self` = the object type** (`*Iterate[int]`, whose `next` dispatches).
Then `o.map(f)` works on an erased cursor and `*Iterate[T]` keeps meeting `[I: Iterate]`. (b) An
explicit attribute on such members. (c) Split the trait (`Iterate` core, `Iter` adapters opted into
per cursor). **Recommend (a)**: it is Rust's `where Self: Sized` + `impl Iterator for &mut dyn
Iterator` with nothing to write, and `generics.md` already says an object type *"is a concrete type …
the body is instantiated at `&Shape` exactly as it is at `Rect`"*.

**Q4. `Sequence` beside lazy adapters (F3).** (a) **Slices become `Iterate`; `Sequence` keeps only
what is eager by nature** (`windows`, `chunks`, plus the slice-only queries), and `map`/`filter`/
`flat_map` are the lazy ones everywhere (`xs.map(f).collect()`). That is a **behaviour change** for
every eager `xs.map(f)` caller, and the org sweep pays it. (b) Keep `Sequence` whole and do **not**
make slices `Iterate`; the lazy head is `xs.walk()`, and `Range` loses `Sequence` (its `map` becomes
lazy). (c) Keep both, and let a program importing `sysl.seq` meet the ambiguity. **Recommend (b) for
0.15**: nothing eager breaks, `Range` is the one change, and (a) can follow once the lazy layer has
shipped a release.

**Q5. `count` and the field namespace (F1).** (a) **Rust's names, `count` included**: rename the
`count` fields of `DequeCursor`/`RingCursor`, make `Chars.count` an `override`, and sweep the org's
cursors. (b) Avoid colliding names (`count_all`). **Recommend (a)**, with the sweep filed when this
lands.

**Q6. Names**: `Order` for the three-way enum (`Ordering` is `sysl.sync`'s), the order wrapper as
`sysl.cmp.Rev`, and the iterator adapter as `sysl.iter.Rev`? **Recommend yes**, with `Order`, `Rev`
(order) and `Total` in a new `sysl.cmp` module, and the adapter structs in `sysl.iter`.

**Q7. Should `Ord` require `Eq`?** **Recommend no**: equality under an order is `compare == Equal`,
and requiring `Eq` breaks every `impl Ord` that has none.

**Q8. Custom orders in ordered containers**: wrapper types only (`Rev`, `Total`, a user newtype) vs a
comparator type parameter with a default (`BTreeMap[K, V, C = Natural]`). **Recommend wrappers for
0.15**, and leave the type parameter for when a user needs it.

**Q9. Allocating sort conveniences** (`sorted(xs) -> []T`, `Buf.sort_stable()` that brings its own
scratch): **recommend yes, in `sysl.buf`/`sysl.seq`**, never in `sysl.slices`.

**Q10. F2**: relax *"trait 'X' already declares a type parameter 'E', so this 'impl' cannot declare one
of that name"*? The two are different scopes. **Recommend relaxing it**, at low priority: it is
pinned deliberately, so it is the user's call.

## 10. Phase 2, in order (once approved)

1. Fix **B1** and **B2** in the compiler, each with its tests and `fix_tests.tsv` row.
2. The language changes the user picks for **Q1–Q3**, each with its reference page and `error`/
   `output` blocks.
3. `sysl.cmp`: `Order`, `Ord.compare` (+ the four `override compare`s), `Rev`, `Total`.
4. `Iterate` adapters and consumers, `Back`, `FromIter`, the slice's chain head (`SliceIter` or
   the slice itself, and the `Walk.Walker` bound, per Q4), the F1 renames (per Q5).
5. The §5 algorithms in `sysl.slices` and over `Iterate`.
6. Docs (`library/iter.md`, `cmp.md`, `seq.md` and its stale paragraph, `slices.md`, `container.md`)
   and the org sweep for the names Q4/Q5 move.
