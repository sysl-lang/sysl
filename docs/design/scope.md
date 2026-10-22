# Scoped spawns — design

Structured fork-join whose tasks may borrow the caller's storage, the compiler checking that no
borrow outlives the join. It is what Rust's `std::thread::scope` and rayon's `scope` give, done with
what sysl already has (`@lends`, escape analysis, the crossing rule) rather than with lifetimes.
Status: being built: item (a) landed. The origin is finding 5 of the `sysl-lang/pool` README; the
probes below were run with the installed 0.1.3.

## Decisions

| question | recommendation | why, in one line |
|---|---|---|
| language form or library | **a library function, with one new annotation**: `@lends(body)` on the scope (as today) and **`@scoped(f)`** on its `spawn` | no scheduler is privileged — `sysl.threads`, `pool`, a board's second core and keel each own their spawn, and `@crossing` already set that precedent |
| what `s.spawn(f)` answers | a **`Joined[R]`** handle, lent like the scope itself; for `R = unit` it is one pointer and may be dropped | a child that computes something should hand it back without a shared cell; the existing lends check already stops a handle getting out |
| a child that panics | the program ends, as a panic anywhere does; nothing is caught and `join` answers `R`, not a `Result` | a sysl panic is a trap, not an unwinding |
| freestanding targets | **yes**, wherever a package marks its scope — the check is compile-time only and needs nothing from the target | the join is the library's; the borrow rule is the compiler's |
| what a child may capture | everything a `&sync Fn` may today, **plus views** (`[]T`, `[]const T`) of storage that outlives the scope call, element type shareable. Not `&T`, `string`, `Buf` (open question Q3) | a view is the borrow sysl already has; its owner word is the only count it carries, and the capture drops it |
| how a view crosses | captured with its **owner word set to null**: no count traffic, so no race on a non-atomic count | a null-owner view is exactly the form of a slice of a frame's own array, which every view operation already handles |
| shared and written | any number of children may **read** one origin; a **written** origin is reached by exactly one spawn site, not in a loop, and by nothing else in the scope. Fan-out writes go through disjoint pieces | sysl has no unique borrows, so uniqueness is checked syntactically over the scope, per origin |
| what keeps borrowed storage still | the calling frame is blocked inside the scope call; inside it, the owner of a borrowed view may not be named at all | a `Buf` pushed by the body would move storage under a child |
| why the join makes it sound | every child finishes before the lending call returns, and every borrowed origin lives at least that long | the same argument `@lends` makes for one call, extended to the children the call waits for |
| the pool case | `pool.scope` keeps its signature; `Scope.spawn` gains `@scoped(f)`; the raw `*(&out[i])` becomes a piece handed to each task | the loop the optimizer emits is the same instruction for instruction; the store gains the bounds check the raw form skipped, hoisted away |

## What is wrong today — the refusals, exactly

Each probe is in this branch's history as a quoted diagnostic; all are from `sysl 0.1.3`.

**`@lends(body)` refuses the spawn**, because `spawn` keeps the lent `*Scope` inside the job it
queues:

```sysl
struct Scope
    jobs: Buf[&sync Fn() -> unit]

    spawn(*self, f: &sync Fn(*Scope) -> unit)
        val s = self
        self.jobs.push(() -> f(s))

struct Runner
    n: int

    @lends(body)
    scope[R](self, body: *Scope -> R) -> R
        var s = Scope(buf())
        val r = body(&s)

        for j in s.jobs do j()
        r

print(Runner(0).scope((s) ->
    s.spawn((s) -> ())
    17))
```

```error
'Runner.scope' lends this body a pointer that is valid only until the call returns, and this one is passed to 'Scope.spawn', which holds on to it — after the call it points at storage the lender has taken back. Copy the value out instead, or do the work inside the body
```

So `pool.sysl` has no `@lends` on `Pool.scope`, and hands the body a `*Scope` nothing checks.

**A `&sync Fn` may not capture a view of a local**, writable or not:

```sysl
var v: [8]u64 = [0; 8]
val s = v[..]
val job: &sync Fn() -> unit = () -> s[0] = 1
```

```error
a closure shared between two domains may be called from either, so every count it captures has to be atomic — but the 's' it captures reaches a '[]ulong', which owns its elements through a count that is not atomic. A literal's elements are immortal and would be safe to share, but nothing in the type says whether these are, so a shared object holds no view at all ('06')
```

The same text for `[]const u64`. A plain `&Cell` is refused the same way (`… reaches a '&Cell',
whose count is not. Hold it as a '&sync Cell'`).

**A raw pointer is accepted**, and is what pool fell back to:

```sysl
var v: [8]u64 = [0; 8]
val base = &v[0]
val job: &sync Fn() -> unit = () -> *(&base[3]) = 7

job()
print(v[3])
```

```output
7
```

Nothing bounds the index, nothing stops two tasks writing one cell, and nothing stops `base`
outliving `v`.

**A closure captured into a kept box is refused as "put on the heap"** — the refusal that will stop
a lent scope being smuggled into a non-scoped spawn (`p.detach(() -> s.poke())` inside a lent body):

```error
'Runner.scope' lends this body a pointer that is valid only until the call returns, and this one is put on the heap — after the call it points at storage the lender has taken back. Copy the value out instead, or do the work inside the body
```

**A closure captures by value** (`reference/expressions.md` § A closure that only reads…), so a
captured *array* is a copy and a write to it is lost:

```sysl
twice(f: () -> unit)
    f()
    f()

var a: [4]u64 = [0; 4]

twice(() -> a[0] += 1)
print(a[0])

val v = a[..]

twice(() -> v[1] += 1)
print(a[1])
```

```output
0
2
```

This settles a point of the design: **what a child borrows is a view**, captured by value like
every capture. Capture mode does not change; nothing about a scoped closure is a new kind of closure.

## 1. The surface

### A library function, with `@scoped` beside `@lends`

```sysl
struct Scope
    private pool: *Shared
    private pending: &sync Latch

    @scoped(f)
    spawn[R](*self, f: &sync Fn(*Scope) -> R) -> Joined[R]

struct Pool
    @lends(body)
    scope[R](self, body: *Scope -> R) -> R
```

and at the use:

```sysl
val v = cells[..]

p.scope((s) ->
    s.spawn((s) -> fill(v[0..<128]))
    sum_of(v[128..<256]))
```

**`@scoped(f)` says: `f` runs in another concurrency domain, and finishes before the `@lends`
call that lent this receiver returns.** It implies `@crossing(f)`. It is a member annotation about a
parameter, so it sits with `@crossing`, `@reads`, `@writes` and `@lends` among those a member may
carry. The compiler requires the receiver at every call to be a pointer some `@lends` call lent —
calling a `@scoped` member on a `Scope` a program built itself is refused, since then there is no
call for the borrows to end at:

```error
'Scope.spawn' is '@scoped', so the 'Scope' it is called on must be one a '@lends' function lent this body
```

**Against a language form (`scope s` … `end scope`).** The form would have to name a scheduler:
OS threads, pool's workers, kairos, a board's second core. Each is a package's, and `@crossing`
already decided that a domain begins where a library says it does ("a boundary nobody marks is not
examined"). `@lends` already gives the lexical extent a block would — a pointer valid exactly while
one call runs — and a multi-line closure already reads as a block. The cost of the library route is
one trusted promise: the compiler cannot see that `Pool.scope` really waits for every child. That is
the same trust `@crossing` and `extern` ask for, and it lives in a few dozen lines of one library
rather than in every program; Rust's `thread::scope` is likewise an `unsafe` core behind a safe
signature.

### What `spawn` answers

`Joined[R]` with `join(self) -> R` (that child only) and `is_done`. It is made of the scope pointer
and a slot inside the job, so by the rule `@lends` already applies ("what a call answers is followed
the same way") a handle returned from the body or stored outside it is refused as the lent pointer
itself would be. A handle never joined is joined by the scope's end. For `R = unit` the slot is
empty, and a spawn whose answer is dropped costs what pool's does today. Rayon's `scope` answers
unit; Rust's `std::thread::scope` answers a `ScopedJoinHandle` — the handle is the more useful of
the two and costs nothing extra when unused.

### A child that panics

A panic in sysl is a trap: the process ends with the message, from whichever thread raised it. There
is nothing to catch, so `join` answers `R` and the scope answers what the body answered. Rust needs
`join() -> Result` and a scope that re-panics because it unwinds; sysl does not.

### Freestanding

The check is compile-time and the join is the library's, so any target with a scheduler gets it the
moment that scheduler marks its scope. `sysl.threads.scope` (one OS thread per spawn, joined in
order) is the standard library's, under `@requires(os)`. `pool` is `@requires(os)` today because its
workers are `sysl.threads`; a keel or board scheduler (an RP2350's second core, skiff's M4) writes the
same two annotations over its own join. A single-threaded loop such as kairos has no domain to cross
and does not need it.

## 2. The borrow rule

### What a scoped closure may capture

At a `@scoped` parameter, a closure may capture everything a `&sync Fn` may today — scalars, arrays
(copied), `&sync T`, raw pointers, the lent scope pointer — and, in addition:

- **a view `[]T` or `[]const T` whose element type is shareable** (`unshareable(T)` answers
  nothing: no non-atomic count inside), **whose origin outlives the scope call**: a local array of a
  frame enclosing that call, a view parameter of such a frame, or module storage. A view made
  inside the body or inside a child of a local of that body or child is refused — the body's locals
  are gone before the scope joins (pool's `scope` releases its latch *after* `body` returns), and a
  child's are gone when it returns while its own children run on;
- **the lent scope pointer as a capture**, not only as the parameter `spawn` hands each child.

**The capture stores the view with a null owner word.** A view's owner is the one count it carries
(`{owner, ptr, len}`); with a null owner every `retain_maybe`/`release_maybe` is the `cbz` that
skips it. That is precisely the representation of a slice of a frame's own array, so no view
operation needs a new case, and the non-atomic count the crossing rule protects is never touched
from a child. Nulling it is sound because the borrow's origin is held still for longer than the
child runs (below).

Not in the first cut: `&T`, `string`, `Buf`, anything else with a non-atomic count. Reading a field
of a borrowed `&T` costs nothing, but copying the reference into a local or an argument takes a
share, non-atomically; holding a child to "read through, never copy" is a second rule of its own
(open question Q3).

### Shared and written

Two children writing one array is a data race; one writing while another reads is too. sysl has no
unique borrow to make that impossible by type, so the check is made **per origin over the whole
scope** — the body, every child, and every function the lent scope is passed to:

- an origin **only read** inside the scope may be captured by any number of children and read by the
  body;
- an origin **written** inside the scope (an element assigned, a writable view of it passed to a
  function that writes through it) is reached by **exactly one spawn site**, **not inside a loop**
  and not on a path that reaches that site twice, and **by nothing else in the scope** — not the
  body, not another child, not a read.

**Origins are what escape analysis already tracks.** Each local array is its own origin. Two view
*parameters* of one function are **one** origin — the caller may have passed the same storage
twice — unless the function is `@disjoint` over them (item 5, branch `no-overlap`), which is exactly
the promise that tells them apart. Views read out of the heap are one unknown origin.

**Fan-out writes go through pieces.** `v[0..<128]` and `v[128..<256]` are distinct origins when the
bounds are constants that share no element — the test `@disjoint`'s call check already makes. For
pieces with run-time bounds, two forms:

- **`split_at`** answering two views marked disjoint (`@disjoint` extended to a result, open question
  Q2), so recursive halving is checked: `val (l, r) = v.split_at(mid); s.spawn((s) -> go(s, r));
  go(s, l)` — `r` and `l` are distinct origins, each reached once, and every recursion's are its
  own locals;
- **`s.spawn_pieces(v, grain, (lo, piece) -> …)`**, the library cutting `v` and handing each child its
  piece as a *parameter*. Its few lines of splitting are trusted library code, as Rust's
  `split_at_mut` is `unsafe` inside.

A loop of `s.spawn(() -> … v[i] …)` writing one origin is refused; so is the shape pool's test
writes today (sixteen spawns each writing a row of `cells`).

### What keeps a borrowed origin still

- **The calling frame cannot move.** It is inside the scope call until every child has finished, so
  it can neither assign, move nor free anything; that is the whole content of a lexical scope.
- **The body and the children hold views, never the owner.** Captures are by value, so the body's
  `cells` would be a copy and is harmless; but a `Buf`, or a `&T` holding one, whose view is borrowed
  may not be **named** inside the scope at all — a `push` from the body would move the storage out
  from under a child. Refused at the name, with the borrowed view's span as the note.
- **A view cannot reassign its origin**, and a child cannot let its view out: the capture is held
  to the rule `@lends` holds a lent pointer to — not returned, not stored outside the child's own
  locals, not boxed, not passed to anything that keeps it (`lends.sysl`'s `lent_escapes`, seeded with
  the borrowed captures instead of the body's parameters).

### Why the join makes it sound

A borrow is valid while its origin lives and nobody else writes it. The origin lives at least as
long as the frame that called the scope, and that frame is inside the scope call until every child
has finished — the `@scoped` promise. Within that window the per-origin rule leaves at most one
writer and no concurrent reader beside it. When the call returns every child is gone, so no view
captured with a null owner exists any more, and every count the frame holds is exactly what it was.
The argument is the one `@lends` makes for one call, extended to the calls the lender waits for.

### The escapes refused

| what the program does | where it is refused | the sentence (new ones in *italics*) |
|---|---|---|
| returns the handle or the scope from the body | the return | existing: `… lends this body a pointer that is valid only until the call returns, and this one is returned` |
| stores the handle, the scope, or a child closure in a field, a global or a `Buf` outside | the store | existing: `… and this one is stored somewhere the call does not own` |
| passes the scope to a non-scoped spawn (`p.spawn(() -> s.spawn(…))`, `threads.spawn(&f, &st)` with `st` holding `s`) | the capture into the box, or the argument | existing: `… and this one is put on the heap` / `… passed to 'spawn', which holds on to it` |
| a child stores its borrowed view anywhere but its own locals | the store | *`the view '<v>' is borrowed from '<origin>' for this scope, and this one is stored somewhere that outlives the child`* |
| a child returns a borrowed view as its `R` | the spawn | *`'<v>' is borrowed for this scope, and a child's answer outlives the child`* |
| a child borrows a local of the body or of another child | the capture | *`'<a>' is declared inside the scope, so it is gone before the scope waits for this child`* |
| two spawn sites, or one in a loop, write one origin | the second site | *`'<origin>' is written by the child spawned at <span>, so no other part of the scope may reach it — split it into pieces`* |
| the body mentions the owner of a borrowed view | the name | *`'<buf>' owns the storage '<v>' lends to a child, so the scope may not touch it until every child has finished`* |
| a `@scoped` member called on a scope no `@lends` call lent | the call | *`'Scope.spawn' is '@scoped', so …`* (above) |

## 3. The pool case

**`Pool.scope` and `Scope.spawn`** change by one line each: `@lends(body)` above `scope`, `@scoped(f)`
above `spawn`. Their bodies stay as they are.

**The test that writes `cells` through a raw pointer** (`tests.sysl` § scope) spawns sixteen
children, each spawning sixteen grandchildren that write one cell apiece. Written with a captured
view and a loop of `s.spawn`, it is refused — 256 sites in a loop write one origin, and nothing says
the cells differ. Written with pieces, each task is handed the cells it writes, and a row's
grandchildren are pieces of the row:

```sysl
var cells: [256]u64 = [0; 256]
val all = cells[..]

val r = p.scope((s) ->
    s.spawn_pieces(all, 16, (lo, row) ->
        s.spawn_pieces(row, 1, (j, cell) -> cell[0] = u64(lo + j + 1)))
    17)

assert(r == 17, "scope answers what its body answered")
for i in 0..<256 do assert(cells[i] == u64(i + 1), "every cell written before scope returned")
```

(`spawn_pieces` is called with a scope pointer the child closure captured — the second row of
"what a scoped closure may capture".)

**The benchmark's `parallel_for`** (`bench.sysl`):

```sysl
// today
p.parallel_for(0..<N, 1024, (i) -> *(&out[i]) = collatz(u64(i) + 1))

// proposed
p.parallel_pieces(out[..], 1024, (lo, piece) ->
    for j in 0..<piece.len do piece[j] = collatz(u64(lo + j) + 1))
```

`parallel_pieces` is `scope` + `spawn_pieces`, as `parallel_for` is `scope` + `split` today.

**It compiles to the same loop.** The two task bodies, compiled by 0.1.3 and `clang -O2` for arm64:

```sysl
@noinline
piece_raw(out: *u32, lo: usize, hi: usize)
    for i in lo..<hi do *(&out[i]) = collatz(u64(i) + 1)

@noinline
piece_view(piece: []u32, lo: usize)
    for j in 0..<piece.len do piece[j] = collatz(u64(lo + j) + 1)
```

The inner loops are instruction for instruction the same — `lsr`, `add …lsl #1`, `tst`, `csinc`,
`add w, #1`, `cmp`, `b.ne` — and the store is `str w9, [x0, x1, lsl #2]` against `str w11, [x1, x8,
lsl #2]`; the bounds check is gone, the loop bound being the length. `piece_view` touches the owner
word not at all: a view parameter, writable or `[]const`, is read through the count its caller holds
for the length of the call (`reference/memory.md` § Passing a reference to a call), and the callee
takes a count only where it keeps the view — stores it, returns it, captures it, or reassigns the
parameter. The count a child *would* take where it keeps a captured view is a non-atomic increment,
which is the race the crossing rule refuses a view for; nulling the owner word at the capture makes
that path a not-taken branch.

## 4. Evidence: Rust, Zig, and what sysl has instead

| | Rust `std::thread::scope` | Zig | sysl, proposed |
|---|---|---|---|
| how a child may borrow | `F: FnOnce() -> T + Send + 'scope`, with `'env: 'scope` — lifetimes | it does not: `std.Thread.spawn(.{}, f, .{&local})` passes a pointer, unchecked; `Thread.Pool.spawnWg` + `WaitGroup.wait` for the join | a view captured at a `@scoped` parameter, its origin checked to outlive the `@lends` call |
| what stops two writers | `&mut` is unique by type; disjoint pieces from `split_at_mut` / `chunks_mut` | nothing | per-origin, over the scope: one writing site, not in a loop; pieces by constant bounds, `split_at` (Q2), or `spawn_pieces` |
| what may cross at all | `Send` / `Sync`, auto traits | anything | the crossing rule (`unshareable`), as today |
| the join | the scope function, `unsafe` inside | the program's | the library's, promised by `@scoped` |
| a panicking child | `join()` is `Err`; the scope re-panics after joining all | `@panic` ends the program | the program ends |

**What sysl lacks is lifetimes and a unique reference.** It has two things that do their work for
the one shape a scope needs:

- **`@lends`** is a borrow with a lexical extent, inferred from the body passed at the call
  (`lends.sysl`: a fixpoint over the body's locals, every store, box, return and call followed into
  the callee by summary). A scope's borrow is the same extent; what is new is that the children the
  lender waits for are inside it.
- **Escape analysis** (`escape.sysl`) already knows each view's origin and asks of every callee
  whether it lets an argument outlive the call. A capture at a `@scoped` parameter gets a third
  answer — kept until the lender returns — which is neither an escape (no promotion) nor nothing.

The uniqueness Rust gets from `&mut` is replaced by a syntactic count over a scope that the compiler
can see whole, because the body and every child are bodies it can read — the same reason `@lends`
refuses a closure behind a `&Fn`. What this cannot express is a writer handed on through an opaque
call; that is refused, and `spawn_pieces` is the way round it.

## 5. Implementation plan

| area | change | size |
|---|---|---|
| `parse_attr`, `analyze`, `lends.sysl` (`check_lends_names`) | `@scoped(f)` on a member: names a callable parameter once; receiver is `*self`; implies `@crossing(f)`; refused on a free function and above a field | small: ~120 lines, 6 tests |
| `lends.sysl` | a `@scoped` member called on the lent pointer does not keep it; a closure boxed at a `@scoped` parameter is not "put on the heap"; `Joined[R]` followed as made of the lent pointer (exists) | medium: ~200 lines |
| `sharing.sysl` (`check_crossing`, `note_sync_erasure`) | at a `@scoped` parameter a captured view with shareable elements is admitted | small: ~80 lines |
| `closure.sysl`, `emit_arc`, `emit_box` | a capture marked borrowed: the owner word stored null, no `arc.copy`/`arc.dispose` for that field | medium: ~150 lines, IR-text tests |
| `escape.sysl` | the "kept until the lender returns" answer: no promotion, and the origin must enclose the lender's call | medium: ~200 lines |
| new `scoped.sysl` (a `walk_typed` visitor, after the analyzer beside `escape`/`lends`) | per `@lends` call: the scope's bodies, transitively through functions the scope pointer is passed to (a summary per function: which parameters its spawns read and write); per-origin read/write classification; one-site, loop and owner-naming rules; the escape refusals of the table | **large**: ~700 lines; the interprocedural summary is the risk |
| `no-overlap` follow-on | `@disjoint` over a result, for `split_at` (Q2) | medium, after item 5 lands |
| `library/sysl/threads` | `scope`, `Scope.spawn`, `Joined[R]`, `spawn_pieces` over OS threads | medium: ~250 lines with tests |
| docs | `reference/memory.md` § A scope (beside § A pointer lent for one call), `attributes.md` (`@scoped` in the member-annotation list), `library/threads.md` | medium |
| `sysl-lang/pool` | the two annotations, `spawn_pieces`, `parallel_pieces`; the raw-pointer test and bench rewritten; README finding 5 closed | small, its own release |

**Test families** (fresh prefixes, checked unused): `scn_` the annotation's name checks; `scb_` the
borrow rule — every row of the escape table refused with its exact sentence, and each accepted shape
(read-shared fan-out, one writer, constant-bound pieces, recursive `split_at`, nested spawns
borrowing the outer origin) run; `sce_` emission — the captured view's owner is `null` in the IR and
no `retain_maybe` of it appears in the child; a library `@test` with eight threads writing disjoint
pieces, run under TSan to show no report; the docs blocks.

**Size, honestly:** about 2,000 lines with tests, in five or six bounded items — annotation and
lends relaxation; crossing and emission; the scope checker; escape; library and docs; pool. The
scope checker is the one that could grow: if the interprocedural summary proves too much, the first
release can require every `spawn` to be written in the body or a child closure (no spawning through
a helper that takes `*Scope`), which pool's `split` would then have to inline.

## Open questions, each with a recommendation

- **Q1. `@scoped` as its own word, or `@crossing(f)` plus a second word?** Recommend `@scoped(f)`
  alone, implying `@crossing(f)`: it is one promise, and two annotations that only mean something
  together invite one without the other.
- **Q2. `@disjoint` over a result, for `split_at` (and later a `chunks` iterator)?** Recommend yes,
  after item 5 lands: it makes recursive halving checked rather than trusted. Without it, run-time
  fan-out writes go only through `spawn_pieces`.
- **Q3. `&T` (and `string`, `Frozen`) as borrowed captures?** Recommend not in the first cut. A
  child may read through a borrowed `&T` but must never take a share; that is a "read through, never
  copy" rule of its own, worth doing once a program needs to share a tree read-only across a scope.
- **Q4. Does `sysl.threads` grow a `scope` (one OS thread per spawn), or is the pool the only
  provider?** Recommend yes: the standard library should have the safe form beside `spawn(&f, &st)`,
  and it is the reference implementation the docs run.
- **Q5. Settled: a view parameter takes no share in the callee**, writable or not — `zero(v: []u32)`
  and `total(v: []const u32)` both read through the caller's count, as `reference/memory.md` §
  Passing a reference to a call says (`tests_vps_view_param_share.sysl`).
