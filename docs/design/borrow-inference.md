# Borrow inference — +0 locals, rewritten parameters, held arguments — design

**Status: proposed (2026-11-18).** Nothing here is built. It unifies three items from the AWFY
studies (queue D-LST): **R1** "+0 locals", **R2** "a parameter a loop rewrites stays borrowed", and
**H5** "+0 borrowed arguments for callees that do not store them". No surface syntax changes, no
diagnostic is added, and nothing a program prints or the moment a destructor runs changes
(§7) — this is the `memory.md § What a count costs` section growing, under that section's existing
promise.

## Why

Idiomatic sysl must be as fast as C++. Two studies found the dominant remaining cost is count traffic
on code that only **reads**:

- **AWFY List, 5.9x C++.** `is_shorter_than` walks two lists:

  ```sysl
  private is_shorter_than(x: Option[&Element], y: Option[&Element]) -> bool
      var x_tail = x
      var y_tail = y

      while y_tail is Some(yt)
          x_tail match
              None -> return true
              Some(xt) -> x_tail = xt.next

          y_tail = yt.next

      false
  ```

  Each iteration does 4 retains + 4 releases: the bindings `xt`/`yt` are retained (an arm's binding
  is "a retain and an own-slot at the bind"), `x_tail = xt.next` retains the new value and releases
  the old, and the copies of `x`/`y` into the `var`s are retained at entry. LLVM cancels none: every
  release can reach `arc.reap`. Removed by hand: **663 → 248 ms**. Then `tail(x, y, z)`, whose outer
  self-call `tail_calls` turns into a jump, takes a count of every parameter at entry and gives it
  back at exit (`borrowing.sysl`'s `owning`: `jumps` ⇒ every parameter), and each
  `x.unwrap().next` argument gets a retain/release around its call (`held` refuses a field read
  through a reference). Removed too: **131 ms = 1.14x C++** (the rest is `Option[&T]`'s layout, the
  null-niche branch).
- **AWFY Havlak, 2.5x.** `do_dfs` and its neighbours retain `self.nodes[current]` around
  `init_node`, the `val outer_blocks = current_node.out_edges` across the recursive call, and so on —
  every argument or `val` read through a reference is counted because the call "may write that
  memory".

## 1. Decisions

| # | question | decided | why, in one line |
|---|---|---|---|
| 1 | one rule or three | **one**: a counted value is *backed* when its referents are reachable from a live **root** through **edges** nothing has overwritten since it was read; a backed value needs no count of its own | R1, R2 and H5 are the same fact asked of a local, a rewritten parameter and an argument |
| 2 | what invalidates a backing | **an overwrite of an edge on its chain** (judged by *(owner type, field)*, through any alias), the **death of its root**, **code nobody can see** (unknown call, closure call, foreign call, a destructor's body), a **suspension**, an operation that **reads a count** of a chain type | a referent held by an intact edge has a count, so it cannot be freed; these are the only things that change an edge or a root |
| 3 | is a plain release a kill | **no** — releasing a count the frame or a callee holds cannot free an object an intact edge holds; only a release that **may run a destructor** is a kill (it runs code) | what makes `tail` borrowable: it releases its own temporaries, and writes no edge |
| 4 | granularity of "edge" | **(owner type, field name)** after instantiation; a whole-value store to a place of type `T` writes every edge of every struct/enum laid out by value in `T` (`by_value_parts`); a buffer's slot elements are the edge *(slot of `E`)*, written by an index store and `slot_vacate`, **not** by relocation (it moves counts, it releases none) | type-only reachability (`gives_back_clear`) kills Havlak's `out_edges` borrow because `init_node` *could* free a `BasicBlock`; field keys see that it never writes `BasicBlock.out_edges` |
| 5 | a local is +0 for part of its life? | **no — for the whole of it, or not at all** (static). Every definition that reaches a use must be backed and stay backed to every use; a local that ever holds a fresh value, or whose backing is killed while it is live, is counted exactly as today | no run-time "owned" flag, no merge of differently-counted values at a loop head |
| 6 | keeping a +0 value | **materialise**: a store, `return`, `break` value, capture, owned argument, `defer` naming it, or a move into a `@unique` slot takes the count *there* (`retain_moving` with no slot to take over — the path a lent `for` variable and a borrowed parameter already use) | the count is paid only on the path that keeps |
| 7 | roots | a **borrowed parameter's entry value** (the caller's guarantee lasts the whole activation, jumps included); an **owned local or owned parameter** whose address is never taken nor `ref`-bound, until it is reassigned, moved, or its block ends; a `@unique` local, until it is moved, assigned or dropped (D-AFF §8). **Not** module storage, not anything reached through `&sync`, `weak` or a pointer that did not come out of a counted object's field | these are the slots only this frame can change, and whose changes it can see |
| 8 | a rewritten parameter (R2) | a parameter assigned in its body — by `=` or by a self-jump — whose every assigned value is backed by roots valid for the activation **stays borrowed**: no entry retain, no exit release | the original argument is the caller's for the whole activation; the walk only reads behind it |
| 9 | a self-jump whose arguments are fresh (`tail`) | **peel**: the borrowing entry `S` becomes a full copy of the body whose jump lands in `S.owned`, the body that owns its parameters. Today `S` would retain and call `S.owned`; it already exists as a symbol | the first activation reads its arguments through the caller's counts; only the jumped-into ones own what they were handed |
| 10 | an argument (H5) | an argument expression that is **backed**, passed to a **borrowed** parameter, is `held` when the call — and the later arguments' evaluation — kills none of its chain | the guarantee retain exists only because "the call may write that memory"; the callee's edge-write summary says whether it can |
| 11 | ABI | **unchanged.** Every decision is in the caller's body or the callee's own body; `S` keeps its signature and stays the entry vtables, closures and C reach; R2/peel only change which of `S`/`S.owned` is a thunk | separate compilation and codegen units see nothing new (§5) |
| 12 | summaries | **one new interprocedural answer**: each function's *edge writes* (set of edge keys, plus `opaque`, plus count-reading types), a least fixed point over the call graph, computed in the same walk as `untouched_summary` | the call graph already has every other question asked of it once per build |
| 13 | where it is decided | on the typed tree, before emission, generalising `lent_element` (`e.lent_decls`) from a `for` variable to every local and binding | the emitter already knows "a name with no count"; `--explain-borrows` can say why |

## 2. What exists today, and why each count survives it

The building blocks are already there; none covers these shapes.

- **Parameters are borrowed by default** (Swift's *guaranteed*): `held` lists the argument nodes the
  caller needs no retain for. **H5 as worded is therefore already the default** — a callee that does
  not keep its argument takes it +0. What Havlak pays is the *caller's guarantee retain* for an
  argument `held` refuses: a field or element read through a reference (`TDeref` falls to `_ ->
  false`). That retain is what decision 10 removes. A callee that *keeps* (`init_node(bb)` stores it,
  `number.at_put(key, …)` keeps the key) owns its parameter and the count is genuine; C++ stores a raw
  pointer there and pays nothing, sysl must. **Those retains stay**, and the report says so.
- **`owning`** makes every parameter of a jumping or `async` body owned, and every rebound parameter
  owned (`rebound`). R2/decisions 8–9 narrow both.
- **`inert_callees`** (releases nothing it did not take, writes only its own `var`s) and
  **`untouched_by(type)`** (cannot reach storage a value of that type could be in) are the call
  judgments `lent_element`, `arc_cover` and the zero/place entries use. Both are coarser than needed
  here: `tail` is not inert (it owns, jumps, releases temporaries), and `untouched_by(Element)`
  refuses a body that releases an `Element` it holds even though that release frees nothing anyone
  else holds.
- **`lent_element`** lends a `for` variable and the bindings that copy a part of it, where the body
  is inert. Its emitter path (`e.lent_decls`, `own_slot` skipped, keeps materialised by
  `retain_moving`) is the one R1 reuses.
- **`arc_elide` / `arc_cover`** cancel pairs inside a window with no release, no count read and no
  suspension, on the IR. A loop-carried `var` (`x_tail`) has no window: its retain is in one trip,
  its release in the next.

## 3. The rule

**Backing.** Every counted value a body computes gets, on the typed tree, a provenance:

- **Fresh** — a call's answer (other than a place/+0 entry's), a box, a literal that allocates, a
  value read from module storage or through `weak`/`&sync`: it arrives with a count.
- **Backed(R, E)** — read out of storage reachable from root(s) `R` along edge keys `E`: a field
  read through a backed or rooted reference adds `(owner, field)`; an element read adds
  `(slot of E)`; a `match`/`is` payload of a backed value is backed the same; a place/+0 entry's
  answer (`borrow_summary`'s `locs`) is backed by its argument plus the path the summary names; a
  value built on the spot from backed parts is backed by the union; a copy of a +0 local is backed
  as the local is.

**Valid.** `Backed(R, E)` is valid at a point when, on every path from its definition to that point:
every root in `R` is alive (decision 7) and nothing **kills** it:

| kill | judged by |
|---|---|
| a store to a place whose edge keys meet `E` | its owner type and field, any alias (`*p`, a `ref`, another parameter, a global); a whole-value store writes every by-value part's keys |
| a call whose edge-write summary meets `E`, or is `opaque` | §4.2 |
| a release that may run a destructor (`may_run_drop`) | opaque (BI1); BI5 makes it a call to the destructor bodies it reaches (`Reacher.lets_go`) |
| an operation that reads a count of a type on the chain (`slot_vacate`, a uniqueness test) | the summary's count-reading set |
| `await`, a suspension, a fiber switch, a lock or unlock | always — other code runs in the frame's time |
| a root dying | reassignment, move, hand-over or end of block of a local root; a `@unique` root moved, assigned or dropped |

**+0 local.** A local (a `var`, a `val`, a pattern binding in a `match` arm, an `is` condition, a
`for` header, a rewritten parameter) holds no count when every value assigned to it is backed and
valid at every use of the local, and it is never the root of another +0 value whose life outlasts
an assignment to it. Otherwise it is counted exactly as today. Keeps materialise (decision 6).

**Why `is_shorter_than` qualifies.** `x_tail = x` is `Backed({x}, ∅)`; `xt` is `x_tail`'s payload,
backed the same; `xt.next` is `Backed({x}, {(Element, next)})`; the loop's only operations are
loads, tests and local assignments, so nothing kills; the fixed point at the loop head is
`Backed({x}, {(Element, next)})` for `x_tail`, likewise for `y_tail` from `y`. Zero count operations.

**Why `tail` qualifies.** It writes no edge and calls only itself and `is_shorter_than`, whose
summaries are empty, so `x.unwrap().next` is held for each inner call (decision 10). Its jump
arguments are fresh (inner calls' answers), so decision 9 peels: the entry `S` reads `x, y, z`
through the caller's counts and jumps into `S.owned`.

## 4. The analysis

### 4.1 Intraprocedural — a forward dataflow on the typed tree

Per function, before emission (`emit_func.sysl`, where `owning` is asked today), one pass over the
body: state = map from counted local to `Fresh | Backed(R, E) | Killed`, joined by union of `R` and
`E` (and `Fresh` absorbing), loops iterated to a fixed point (the lattice is finite: the body's
roots × the edge keys it can name). Kills are applied at each node; uses are checked against the
state at the use. The answer is the set of +0 locals, handed to the emitter as `lent_decls` is
today, and the reason for each counted one, for `--explain-borrows`. Liveness matters in one place —
`x_tail = xt.next` kills nothing even though `xt`'s chain passes through `x_tail`'s value, because
`x_tail` is +0 (holds no edge) — so no per-statement liveness is needed beyond "is the local used
after this kill" (a dead local cannot be harmed by a kill).

### 4.2 Interprocedural — the edge-write summary

`EdgeWrites { keys, within: Buf[Type], counts: Buf[Type], opaque: string }` per function (per
instance), the **least** fixed point over the call graph (start empty, grow — a write needs a finite
path to it, so recursion settles on what the bodies actually write). `opaque` is a reason (`a call
through a closure`, `a destructor of 'm.Res'`), empty when every write is named, with the function whose
own body holds it (`opaque_in`) — the words `--explain-borrows` will quote. A body contributes:

- **each store's edge keys** (decision 4), by these rules:
  - a store to a local or parameter of the body's own, reached without a dereference (fields of it,
    elements of a local array), contributes nothing — the frame's slot is no edge;
  - a store of a value that holds **no count** changes no edge and contributes no key;
  - otherwise it writes the edge its place sits on **and every edge that place sits inside by value**
    — `a.b.c = v` writes `(B, c)` and `(A, b)`, since a reader may hold a copy of the `B` — climbing
    until the object the place is in: module storage (no edge), a reference's referent (a counted
    object, which nothing holds by value), or storage reached through a **pointer, a view or a `ref`**,
    whose container cannot be named: that adds the written type to `within`;
  - it also writes every counted edge of the value it overwrites (`by_value_parts`);
- **`within`**: a type written through a pointer, view or `ref`. It meets an edge whose held value
  lays that type out by value (`*p = None` with `p: *Option[&Node]` meets `(Node, next)` and
  `(Holder, inner)` where `inner: Inner` holds one). Every pointer write is noted counted or not, and
  judged against the program's **writable** casts (a cast to a non-`const` pointer from another
  pointee type, an integer or anything not a named object; a non-read-only `view_as`): a write
  through a type such a cast made from anything is `opaque`; one made from a named object's address
  writes that object's counted edges; read-only casts and views (`x.bytes`) are not counted, since
  nothing can store through them;
- **the slot forms**: `slot_place`, `slot_place_run`, `slot_take` and `slot_vacate` write
  `(slot of E)` and the counted edges inside an `E`; `slot_put` (fills an empty slot) and
  `slot_storage` write nothing a reader could have read, and neither does relocation;
- each count read (`slot_vacate`; there is no other uniqueness test today): the element type;
- **`opaque`** for: a call through a closure (an erased `FnN` table — not followed into the closures
  it could be, even in a closed program; a closure called directly by its own type is an ordinary
  call), a call through a function value, a table call not resolved by `slot_callees` in a closed
  program, a store through a laundered cast, a suspension (`await`, `block_on`, a step) and a fiber
  switch, inline `asm` that clobbers memory, a store to a place the analysis cannot name, and **any
  release that may run a destructor** (`may_run_drop` on what the body holds; BI5 refines this to the
  destructor bodies' summaries). A counted trait object or closure says nothing of what it holds, so
  until BI5 a body that holds one is opaque by this rule (`a destructor of '&m.Step'`) whatever it
  calls through it; a table call through a `*Step` is resolved by `slot_callees`;
- **a foreign call**: trusted (contributes only a `within` write of what it is handed a writable
  pointer or view to) when the program is a closed program's build, the function is not variadic,
  `@abandons` or returns-twice, every parameter is a plain value or a pointer/view to one, and every
  sysl function C can call (`c_entered`) has an empty, non-opaque summary — otherwise the summary is
  solved again with every foreign call opaque, as `quiet_callees` does. A thread spawn is opaque by
  this rule (`pthread_create` is handed a function pointer);
- every callee's summary, for the calls on paths that come back; **a call on a path that ends the
  program is not followed** (no frame that lent anything runs again), except an `@abandons` callee,
  after which the program goes on in an earlier frame.

It is computed in the walk `untouched_summary` already makes (`ReachSink` gains `writes` and
`wcasts`; `Untouched` keeps each body's own writes), and the fixpoint runs the first time a summary is
asked for (`edge_writes(u, name)`), so a build that asks none pays only the walk's extra notes. Where
no body is in sight — `extern`, a function pointer, an archive's function, a name no body of the
build has — the answer is `opaque`, the line every inferred fact draws.

**The API the later items use** (`borrowing.sysl`): `edge_writes(u, name) -> EdgeWrites`;
`field_key(owner, i)` and `slot_key(elem)` build the keys a read adds; `writes_edge(w, key)` and
`first_written(w, keys)` answer whether a summary meets a chain (and which edge, for the report);
`reads_count(w, t)` whether it reads a count of a chain type; `edge_text(key)` shows one, each type
as `type_show` names it (`m.Node.next`, `sysl.Option[&m.Node].Some.value`, `the slots of &m.Node`).

### 4.3 Parameter conventions

- **Rewritten, not jumping** (`rebound` names it): borrowed when §3 makes it a +0 local whose entry
  value is the root `param@entry`. Otherwise owned, as today.
- **Self-jumping** (`tail_calls_of` non-empty): per parameter, from the jump sites' staged
  arguments — (a) every staged value backed by entry roots and valid across the jump: **borrowed
  throughout**, no counts at all; (b) every staged value fresh: **peel** (decision 9), and only for
  types that cannot run a destructor (a `Drop`-capable parameter keeps the entry retain, since the
  peel would not move a drop but the item does not need to prove that); (c) mixed: **owned**, as
  today. The staging order of `memory.md`'s tail-call bullet is untouched: a (a) argument is staged
  with no count because none is needed — it is backed by the entry root, which the frame does not
  give up.
- **`async`, `@export`, `@section`, `conv`, variadic, address-taken, contracts, `@scoped`, closure
  `.call` bodies**: unchanged (owned or borrowed as `owning` says today).

### 4.4 Arguments

`held_unless` gains one arm: an argument that is `Backed(R, E)` at the call (every root alive,
nothing killed so far), passed to a borrowed parameter of a direct call whose summary and the later
arguments' (`written_by` already collects their local writes; their calls' summaries join) meet no
key of `E` and are not opaque, is held. Never to an owned parameter, an `async` or `@abandons`
callee, a vtable/closure/function-value call, or a C function (the same list `lent_view` refuses).

## 5. ABI, separate compilation, codegen units

- **Nothing about a function's signature or its entry symbols changes.** `S` stays the borrowing
  entry; R2(a) makes a jumping function unsplit (no `S.owned`), R2(b) makes `S` a body instead of a
  thunk. A caller naming `S.owned` (owned-argument hand-over) decides from the same body and callee
  answers it always has, so a `.syslib` and its user still agree, and a disagreement is still a link
  error rather than a silent miscount.
- **The edge-write summary is a caller-side fact about a callee's body.** It is used only where the
  body is part of the build (the program, its from-source packages, the standard module whose
  bodies are checked in every build). A body reached only through a precompiled artifact or a
  library build (`!entry_point`) answers `opaque`, as `untouched_by`'s table resolution already
  does in an archive.
- **Codegen units**: every answer is computed on the whole program before the module is split; a
  unit receives decisions about its own bodies only.

## 6. Hazards, each mapped

| hazard | where a naive rule frees under a reader | handled by |
|---|---|---|
| the list mutated mid-walk through another reference | `w.next = None` via an alias of an element `x_tail` will reach | a store to `(Element, next)` through any reference kills every backing whose `E` holds it |
| a callee dropping the last owner via a global | `G = None` inside a callee while a caller reads `G`'s list +0 | module storage is never a root (an argument read from `G` is counted, as today); the callee's store of `G`'s old value is an edge write when the list's elements are on any other chain |
| a callee overwriting the field an argument was read from | `f(a.b)` where `f` sets `a.b = …` through a global | the call's summary holds `(A, b)`; the argument is not held |
| a `Drop` impl with side effects | a temporary's destructor clears the walked list | a release that may run a destructor is a call to its body; item 1: opaque |
| a closure capturing the walked value | `val k = () -> head.next = None` called mid-walk | a call through a closure value is opaque; building the closure captures = keeps (materialised) |
| a closure capturing a +0 local | the closure outlives the backing | capture is a keep: the count is taken at the capture |
| threads and `&sync` | another domain writes the chain | chains through `&sync`, `weak`, module storage reachable from another domain are never backed; a plain `&T` cannot cross a domain (the capture rules); a spawn is opaque; raw-pointer writes from another thread to counted storage are a data race, outside the model as for `arc_cover` and `lent_element` |
| `@unique` moves (D-AFF) | a root moved into a callee that drops it | a moved/assigned/dropped root dies (decision 7); a +0 value handed to a `@consumes`/owned slot materialises |
| async frames | another task runs at an `await` | every suspension kills; `async` parameters keep today's convention |
| count readers | a +0 local hides a share and `slot_vacate`/a uniqueness test acts as if unique | count-reading operations on a chain type kill; their callers' summaries carry them |
| a root's slot written through its address | `ref r = l; r = other` | a local whose address is taken or `ref`-bound is never a root |
| `defer`/`errdefer` | the deferred code runs at the exit, after the last visible use | a local named in a `defer` is counted (as for hand-over today); the deferred code is part of every exit's stretch |
| the self-jump frees the argument | `go(next, list)` freeing `list` as the parameter is reassigned | (a) needs no count because the root is the activation's entry value, not the slot; (b)/(c) keep today's staging |
| relocation | `push` moves the storage an element was read from | moves counts, releases none: a +0 *reference* survives; +0 *views* are not in scope (their rule is `lent_view`/`counted_pointers`) |
| generics | `T = &U` vs `T = int` | per instance, as every summary |

## 7. Observable behaviour, and what `memory.md` must say

**Nothing observable changes, by construction.** A +0 value is never the last owner of anything: its
referents are held by an intact edge or a live root at every use. So the count it would have taken
could never have been the one to reach zero — no object is freed earlier or later, no destructor
runs at a different moment, `SYSL_COUNT_ALLOCS` nets the same, a `weak` upgrade answers the same.
The one way a missing count *could* show is an operation that reads a count; those kill (§3).
R2(b)'s peel moves no release either: the caller still holds its temporaries to the end of its
statement, and `S.owned` owns exactly what the jump staged today. **No question for the user** — the
Drop timing `memory.md § When a value is dropped` promises ("assigning to a variable drops what it
held, at the assignment"; "a temporary is dropped at the end of its statement") holds, since the
assignment of a +0 local never held anything that could drop.

`memory.md` after the change (each in the stage that makes it true):

- **§ Passing a reference to a call usually costs nothing**: the third bullet becomes "anything else
  costs a retain before and a release after **unless the call can write no field on the way to it**:
  a field of a parameter's referent passed to a function that writes no such field takes no count".
  The sentence "A callee still takes a count for a parameter it assigns to" becomes: "…for a
  parameter it assigns a value it made — a walk that only follows fields of what it was handed
  (`p = p.next`) takes none — and for every parameter of a function something outside the program may
  call". This was always a cost statement under the section's "none of this changes what a program
  prints" promise; it stays one.
- **New § A local that walks storage takes no count** (after § A loop lends each element), with the
  `is_shorter_than` shape as its runnable block and the cases that keep the count (a write to the
  field walked, a call that may write it, a closure call, an `await`, a destructor).
- **§ Edge cases / Hazard summary**: one row each for "a walk whose list is rewritten mid-walk keeps
  its counts".
- **`getting-started/cli.md § --explain-borrows`**: the new lines (§8).
- **`compiler-notes.md § Ownership`**: the backing rule, the edge-write summary, the peel.

## 8. `--explain-borrows`

Today it reports each function's answer. It gains, for each function of the program's own files,
one line per counted local or rewritten parameter **that is a candidate** (holds a reference and is
read through, not merely passed along), and one per argument refused a `held` it would get under
§4.4 — never per counted local in general, so the report stays readable:

```
list.sysl:27:9: 'x_tail' holds no count: borrowed from 'x' through .Some.next
list.sysl:41:1: 'tail' parameter 'x' borrowed on entry; owned after the self-call at 43:9 (fresh arguments)
havlak.sysl:265:13: 'outer_blocks' holds no count: borrowed from 'current_node' through .out_edges
walk.sysl:12:9: 'cur' is counted: 'clear(l)' at 14:9 may write Node.next
walk.sysl:20:9: 'cur' is counted: it is assigned a fresh value at 22:13
havlak.sysl:261:9: argument 'self.nodes[_]' of 'init_node' needs no count: 'init_node' writes no field on its way
```

The reasons are the first kill found, named by its edge key or its kind (`a call through a closure`,
`an await`, `a destructor of 'Res'`, `the root 'l' is reassigned at 30:5`).

## 9. Soundness

**Invariant.** At every use of a +0 value `v = Backed(R, E)`: each root `r ∈ R` holds a count
(a local's own slot, or the caller's for a borrowed parameter — `held` guarantees it, which is
today's convention), and every edge in `E` holds the value it held when the read along it was made.

**Then every referent of `v` is alive**: it is reached from a counted root by a sequence of edges
each of which holds a count of its target (a counted field holds a count, by the memory model), so
each object on the way has count ≥ 1.

**The invariant is preserved** because the only operations that change an edge's contents are stores
to that edge (any alias of the owner, judged by owner type and field, so aliasing does not matter),
and stores by code the frame cannot see (opaque calls, destructors, other tasks at a suspension);
the only operations that drop a root's count are the root events of decision 7. All are kills. A
release that is not a kill gives back a count its holder owned; it reaches zero only on an object no
intact edge or live root holds, so none on `v`'s chain. Another thread cannot write a non-`&sync`
chain (the crossing rules), and a `&sync` chain is never backed.

**Nothing observable changes** (§7): `v` holds no count, the object it reads has count ≥ 1 from
others, so the count `v` would have held was never the last — every free and every destructor happens
at the same point as before. Materialising at a keep takes the count the keep needs.

**The callee side composes**: a caller drops a guarantee retain only when the callee's summary (all it
and everything it calls may write) misses the argument's chain; the callee then treats its parameter
as a root and its own backings are judged against its own chains, which the same summary bounds.

## 10. The test matrix

Each implementation item owns its rows. Two kinds of proof, both deterministic: **IR counts** (the
`dvp_count_ops` idiom — `@arc.retain(`, `@arc.release(`, `@arc.copy.`, `@arc.dispose.` in the named
function's lowered body; ask for `S.owned` where split) and **runs on the counted heap**
(`SYSL_COUNT_ALLOCS=1`, net zero) **and under ASan** for every adversarial program (a wrongly +0 value
is a use-after-free ASan reports). Every adversarial row asserts the count IS still taken and the run
is clean.

**Positive (count operations drop to the stated number):**

| # | program | expect |
|---|---|---|
| P1 | `is_shorter_than` verbatim | 0 in the loop; 0 in the body |
| P2 | `var cur = l.head; while cur is Some(c) do cur = c.next` with `l` a parameter | 0 |
| P3 | same with `l` an owned local not reassigned in the loop | 0 in the loop |
| P4 | a walk calling an inert reader (`length()`) per node | 0 |
| P5 | a walk calling a function that writes an unrelated field (`count.n += 1` on another type) | 0 |
| P6 | `go(p: Option[&Node], acc: int) = p match Some(n) -> go(n.next, acc + n.v); None -> acc` (self-jump, backed args) | 0, no `S.owned` |
| P7 | `p = p.next` in a non-jumping loop on a parameter | 0, no entry retain |
| P8 | `tail` verbatim | entry `S`: no retain of `x,y,z`; inner `x.unwrap().next` arguments: no retain |
| P9 | Havlak's `do_dfs` shape: `self.nodes[i].init(…)` receiver | no guarantee retain of the element; the kept `current_node` still retained (pinned, it is genuine) |
| P10 | `val e = p.edges` across a recursive call that writes other fields | 0 for `e` |
| P11 | a +0 local kept: `return cur`, `buf.push(cur)`, `break cur`, captured by a closure | exactly one retain, at the keep, on that path only |
| P12 | generic `walk[T]` instantiated at `&U` and `int` | per instance |
| P13 | a `match` arm binding and an `is` binding of a backed value | no retain at the bind, no release at the arm's end |

**Adversarial (count kept, counted heap and ASan clean):**

| # | program | the free a naive rule makes |
|---|---|---|
| A1 | walk with `other.next = None` mid-walk, `other` an alias of a node ahead | frees the rest of the list under `cur` |
| A2 | walk calling `cut()` that sets `G.unwrap().next = None` through a global | same, through a callee |
| A3 | callee `f(a.b)` that does `A_GLOBAL.b = fresh()` where `A_GLOBAL` is `a` | the argument freed inside `f` |
| A4 | `G = None` in a callee while the caller walks `G`'s list after reading it into a local | the local's list freed |
| A5 | a walk releasing a `Res` temporary whose `Drop` clears the list | destructor frees under `cur` |
| A6 | a closure capturing the list head, called mid-walk, cutting it | opaque call |
| A7 | a walk with an `await` between reads, another task cutting the list | suspension |
| A8 | root reassigned mid-walk: `l = None` (owned local root) | the root's death |
| A9 | root's address taken: `ref r = l; r = None` | write through the root's address |
| A10 | `@unique` root moved into a consuming call mid-walk | D-AFF move |
| A11 | self-jump whose argument is fresh on one site and backed on another | mixed: owned, today's staging |
| A12 | a walk over a `Buf` element whose loop `remove`s from that buffer | `(slot of E)` written, `slot_vacate` reads a count |
| A13 | a walk over a `&sync` list | never backed |
| A14 | a foreign call (`extern`) and a function-value call mid-walk | opaque |
| A15 | `defer print(cur.v)` with `cur` walked | named in a `defer`: counted |
| A16 | a table call (`dyn` trait) mid-walk, closed program, one impl writing `next` | `slot_callees` union meets the key |
| A17 | a +0 local live across an assignment of its own root's field: `x_tail = xt.next; xt.next = None; read x_tail` | `x_tail`'s chain edge written |

**Design-claim pass**: every row of §6 has a row above; every bullet `memory.md` gains (§7) is a
runnable block or an `output` pinned in the page; `--explain-borrows` lines of §8 are pinned for
P1, P8, P9 and one each of A1, A5, A8.

## 11. Implementation items, in order

Each is one agent, on its own branch off dev, gated targeted (its own test file + the files naming
the functions it changes; `tests_fle_for_lend.sysl`, `tests_dvp_diverging_path.sysl` and the
owned-params tests are the blast radius of every item). Expected gains are the studies' numbers,
not new measurements; each item's proof is its IR-count rows.

1. **BI1 — the edge-write summary** (`borrowing.sysl`: `ReachSink` gains `writes`/`wcasts`,
   `Untouched` each body's own writes; the least fixpoint, solved lazily; edge keys incl. by-value
   parts and slot elements; the API at the end of §4.2). No codegen change, no `--explain-borrows`
   line (its lines are BI2's and BI3's). Tests (`tests_bls_borrow_edge_summary.sysl`): a store, a
   plain store, own slots, a whole-value store, a nested store, stores through a pointer, a `ref`,
   another parameter and module storage, a laundered cast, a callee's inherited write, recursion
   settling, a closure/function-value/foreign/variadic/table call → opaque (and a trusted foreign
   call, and the call-back and library distrust), a spawn, an `await`, an unseen callee, a release
   that may run a destructor → opaque, `slot_vacate` → slots + counts, push (relocation) → no key, a
   generic per instance. Gain: none by itself; it is the judgment the rest share.
2. **BI2 — +0 locals (R1)**: the dataflow of §4.1 on the typed tree; `lent_decls` generalised to
   every `var`, `val`, arm and `is` binding; materialisation at keeps; `--explain-borrows` local
   lines; `memory.md`'s new section; `lent_element` left as is (it becomes a special case; folding it
   in is a later refactor). Tests (`bli_`): P1–P5, P11, P13, A1, A2, A4–A9, A12–A15, A17.
   Gain: List's `is_shorter_than` — **663 → ~248 ms** (the hand-removal number).
3. **BI3 — held arguments (H5)**: §4.4's arm in `held_unless`, using BI1 and BI2's backing.
   Tests (`bla_`): P8's argument half, P9, P10, A3, A16, and an argument to an owned parameter
   (still retained). `memory.md § Passing a reference…` bullet. Gain: Havlak's guarantee retains
   (no separate number in the study); with BI4, List's 248 → **~131 ms**.
4. **BI4 — rewritten parameters (R2)**: `owning`'s `rebound` and `jumps` branches consult the
   backing; cases (a)/(b)/(c) of §4.3; the peel in `owned_params.sysl`/`emit_func.sysl` (`S` a body
   whose jump lands in `S.owned`). Tests (`blj_`): P6, P7, P8's entry half, A11, a `Drop`-capable
   jumping parameter (keeps the entry retain), a jumping function reached through a vtable and a
   closure (enters `S`), a `.syslib` built with the change and linked by a program (agreement),
   `tail_calls`' `go(next, list)` regression. `memory.md` sentence on assigned parameters. Gain:
   together with BI3, List **→ 131 ms (1.14x C++)** once the null-niche item lands.
5. **BI5 — destructor precision** (optional, after BI2): a release that may run a destructor
   contributes the destructor bodies' summaries (`lets_go`) instead of `opaque`. Tests: A5 kept,
   a harmless destructor (prints) no longer refusing a walk. Gain: none on AWFY (no destructors on
   the hot paths); it widens what qualifies.

Items 1 → 2 are sequential; 3 and 4 may run in parallel after 2. Every item adds its
`fix_tests.tsv` row if the flow asks, updates `compiler-notes.md § Ownership`, and runs its page's
docs sweep.

## 12. Open questions

None needs the user: nothing observable changes (§7). Two calls made here, logged for review:
the **static** +0 (decision 5) rather than a run-time ownership flag, and the **peel** (decision 9)
doubling the code of a self-jumping function with fresh arguments — bounded to those functions, and
the alternative (an entry retain per activation) is exactly the cost being removed.
