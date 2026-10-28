# Borrowed return, and the order a block lets go — design (0.8.0)

**Decided by the user for 0.8.0:** (1) a method may hand back a borrow of its receiver's storage
without copying it; (2) the order in which a block's deferred statements and its references' releases
run is specified, and fixed where it caused a race. The two are one note because they meet at one
question — *when does a block stop holding what it reached* — and because the second is the cost
model the first's answer relies on.

The motivating program is automata's HashLife (branch `hashlife-075`, `hashlife.sysl:124`):

```sysl
struct Node[K: Leaves]
    ...
    kids: Option[Kids[K]]
    quad(self) -> Kids[K] = self.kids.unwrap()

struct Kids[K: Leaves]
    nw: &Node[K]
    ne: &Node[K]
    sw: &Node[K]
    se: &Node[K]
```

`n.quad()` is on the hottest path (`hashlife.sysl:419-470`: the centre and result computations call
it on a node and then on each of its four children). Written out by hand,
`n.kids match Some(q) -> …` costs nothing (p02); the method costs a whole `Node` copied into the
receiver and a `Kids` copied out with four retains and four releases (p01). The program is written the
way a reader wants it written and pays for the method.

The race is benchtop's (`d72cffd`, `dsi_write`): `lock(); defer unlock(); val h = <a reference read
out of the guarded state>` — `h`'s count is given back *after* the unlock, while another core's thread
takes the same count under the lock (d02).

Nothing here is built. Every claim about today is from a probe in
[`borrowed-return-probes/`](borrowed-return-probes/), run on the installed **sysl 0.7.8**
(`zsh borrowed-return-probes/run.zsh`: each probe's output, then the count operations and whole-struct
loads in each of the program's own functions in its unoptimised IR).

## 1. Decisions

| # | question | recommendation | why, in one line |
|---|---|---|---|
| 1 | how a borrowed return is spelled | **not at all, for a read**: inferred from the body — a function whose answer is *made of* a parameter's storage answers it without a copy or a count wherever the caller can use it before anything could change that storage | it is the rule `memory.md § A share of something read out of storage takes no count while nothing can move` already applies to `n.kids` (p02), carried across one call; no visible semantics change, so nothing to write |
| 2 | where the borrow ends | **at the end of the caller's inert stretch** — the same stretch §819 defines (no release, no write that could reach the storage, no unknown call, no `await`, no lock) — or at the last use, whichever is first. **Where the stretch ends first, the answer is materialised there**: copied and counted *then*, which is the same value it would have been at the call, since nothing could have changed it | no lifetime is ever named; the cost is paid only on the path that needs it |
| 3 | the receiver copied in | a by-value parameter (`self` included) that the callee never writes, whose address it never takes, and that nothing it calls could write, **is passed by address** | the `Node` copy in p01; Swift's default `borrowing` convention; unobservable under the same condition as decision 1 |
| 4 | `Option[&T]`, `&T`, a struct of references | **a +0 answer**: a function every path of which answers references reached from a parameter (never fresh, never one it released) answers them uncounted, and the caller takes a count only when it materialises (decision 2) | p08: `find` retains on the way out and the caller releases; the same rule as decision 1, for the counts instead of the bytes |
| 5 | how the callee hands it over | **two lowered entries** for a function that qualifies: the ordinary one (answers a value, +1), and a *place* entry (`f$place`) that answers the address of the storage (or the +0 references); a call site in an inert stretch calls the place entry | the caller chooses per call; the callee's body is compiled once per entry, so nothing about the function's type changes |
| 6 | when no body is in sight | **the ordinary entry**: a trait object, a function pointer, an `extern`, an `@export`ed hook, a closure — the defaults `keeps` already has (`memory.md § Where no body is in sight`) | the same line every inferred fact in sysl draws |
| 7 | generics | **per instantiation**: whether `get[T](self) -> T` qualifies is decided after the type argument is known (a `T` with no reference in it needs no +0 entry; a `T = &U` does) | sysl compiles an instantiation from source; `closures.md` decision 10 does the same |
| 8 | a writable borrow (`k.task(i).state = 2`) | **a separate, declared form**: `-> ref T`, a method that answers a *place*, bound only by `ref` or used at once as a place; its lifetime is a `ref` local's (`memory.md § The one rule: what may move underneath it`). **Q2** | a write must reach the receiver, so value semantics cannot infer it — the program has to say it wants a place |
| 9 | `@views(self)` | **unchanged.** It answers a different question — memory a *destructor* frees, whose lifetime is the object's, not the value's — and its four refusals stay | p04: it marks only views; widening it would turn an inferred optimisation into a declared obligation |
| 10 | drop order | **one interleaved reverse order per block**: leaving a block runs, last-written first, each `defer` and `errdefer` that was reached and each release of a counted local the block declared, in reverse order of their **position** | Rust, C++, D, Zig all agree; it is the order that fixes d02; and a `defer` can name only what is declared before it, so everything it names is still alive (§7) |
| 11 | temporaries | **unchanged**: released at the end of their statement | Rust's and C++'s rule; nothing found wrong with it |
| 12 | release at last use (Swift) | **no** — a block's releases stay lexical | deterministic destructor timing is what a `Drop` guard is for; inferred moves already end a local's ownership where it is handed off (d05) |
| 13 | migration | the order of `defer` lines against `Drop` output changes in every block holding both; a census-only diagnostic first (§8), every hit read | org: `defer` appears in ~10 repos, benchtop and kairos the most |

## 2. What 0.7.8 does — the probes

| probe | shape | today |
|---|---|---|
| b01 | `quad(self) -> Kids = self.kids.unwrap()`, `val q = n.quad()` | prints `4`; `walk` loads the whole `Node` out of `n` and passes it by value; `unwrap` copies `Kids` with `arc.copy.Kids` (**4 retains**); `walk` gives them back with `arc.dispose.Kids` (**4 releases**) |
| b02 | the same walk as `n.kids match Some(q) -> …` | prints `4`; **no count operation at all** in `walk` — §819's stretch rule at work |
| b03 | `quad(&self)` | the `Node` copy into the receiver is gone; the four retains and four releases remain — the answer is still a value built in the result slot |
| b04 | `@views(self)` on `quad(&self) -> Kids` | refused: `'@views(self)' says the view 'quad' answers is borrowed from its receiver, so it marks a member whose result is a view — '[]T', '[]const T' or 'string'` |
| b05 | `quad(*self) -> ref [4]int` | refused at the parse: `identifier expected` at `ref` — a `ref` is a declaration, never a type |
| b06 | `ref k = n.kids` in the caller, then `k match` | prints `4`, no count operations — the place exists, but only where the caller can name the field the method would hide |
| b07 | pre-0.7.5 HashLife: `node(self, n) -> Node = self.nodes[usize(n)]` on a six-field `Universe` | prints `7`; `Universe` is passed **by value** to `node` and to `population` (`define %struct.Node @Universe.node(%struct.Universe %self.param, …)`), and the `Node` is copied out |
| b08 | `find(self, key) -> Option[&Item]` answering an element of `self.items` | prints `20`; `find` makes **3 retain/release calls and one `Option` copy**, and the caller releases what it got |
| d01 | `a`, `defer 1`, `b`, `defer 2`, `c` | `defer 2`, `defer 1`, `drop c`, `drop b`, `drop a` — every defer, then every release |
| d02 | benchtop's shape: `lock`, `defer unlock`, `val h = shared.host.unwrap()` | `lock`, `use dsi under the lock`, **`unlock`, `release the host's last count`** — the release lands outside the lock |
| d03 | `break` and `return` out of a loop body with a `defer` | innermost block first, each block its defers then its releases |
| d04 | a defer reading a local declared before it and assigned later | `defer sees late` — the local is alive; under §7's order it still is |
| d05 | a local handed to a function that keeps it, with a defer between | `defer`, `drop b`, `drop a` — `b`'s release follows `kept`, released at the block's end |

The 0.7.8 `noreturn` fix already took `quad` from three count operations to one copy/dispose pair (the
`unwrap` failure path no longer keeps the stretch from being inert); what remains is the shape p01
shows, and only a change to what a call answers can remove it.

## 3. Borrowed return: survey

| language | spelling | lifetime | checked? |
|---|---|---|---|
| **Rust** | `fn quad(&self) -> &Kids`; `Option<&T>` via `as_ref()`; `&mut` for a writable one | elided: one reference in, the answer borrows from it (`&self` wins over the others); named `'a` otherwise | yes, by the borrow checker — and every caller pays in annotations when elision does not fit |
| **Swift** | `borrowing` / `consuming` parameter conventions (SE-0377; borrowing is already the default for most parameters); `_read` / `_modify` coroutine accessors, now `yielding borrow` / `yielding mutate` (SE-0474); `~Escapable` with `@lifetime(borrow self)` (SE-0446) | the accessor's coroutine yields the borrow and resumes when the caller is done; a getter answers +0 under the guaranteed convention | yes, by the ownership verifier, mostly without annotations |
| **C++** | `const Kids& quad() const`, `Kids& quad()` | none — `make_node().quad()` dangles; `[[clang::lifetimebound]]` warns about some cases | no |
| **Zig** | `fn quad(self: *const Node) *const Kids` | none — a pointer into the receiver | no |
| **Go** | `func (n *Node) Quad() *Kids { return &n.kids }` | the garbage collector; escape analysis moves `n` to the heap when needed | n/a |
| **D** | `ref Kids quad() return` (DIP1000), inferred for templates and `auto` functions | the `return` attribute ties the answer to `this` | partly |

**What sysl takes from each.** Swift is the closest model and the strongest evidence: its *default*
convention is that a parameter is borrowed and a getter's answer is +0, and the programmer writes
nothing unless they want the other thing — which is decisions 1, 3 and 4. Swift needed a coroutine
accessor to make the borrow outlive the call; sysl does not, because a sysl caller can always see when
the borrow would stop being safe (the stretch ends) and copy *then*. Rust's elision rule is the
lifetime decision 2 makes without a name — the answer borrows from the receiver — but sysl has no
need to refuse a program, since it can fall back to a copy. C++ and Zig show the cost of a declared
reference return with no check (the dangling temporary b04's sibling refusal already prevents for
views). D and Rust are the precedent for decision 8: a *writable* borrowed answer is declared.

## 4. Borrowed return: options

**A. Inferred, read-only (recommended).** The answer is made of the receiver's storage; the call site
in an inert stretch reads through an address and takes no count; outside one, or at the point its
stretch ends, the value is copied and counted exactly as today. Semantics: identical to today in every
program, because the borrowed value is read only while nothing could change it. What a reader gains:
the method and the field read cost the same.

**B. Declared, read-only:** widen `@views(self)` (or a new `@borrows(self)`) to any result type, and
hold the caller to its four refusals. Gives a visible guarantee, but refuses programs that A accepts
(`val q = n.quad()` followed by a call that might write `n` is refused instead of copied late), and
asks every author to know which methods deserve the mark. The `--explain-*` report of option A gives
the visibility without the refusals (Q1).

**C. A `ref` result type for reads too:** `quad(&self) -> ref Kids`. Makes `ref` a type in one
position, which `memory.md § It is a declaration, never a type` rules out for reasons that still hold
(a `ref` must never travel); and for a read it gains nothing over A.

**D. Do nothing in the language; write `ref k = n.kids` (b06).** Works where the caller can name the
field, which is exactly where the method was written to hide it.

**Recommendation: A for reads, plus the declared `-> ref T` of decision 8 for writes** — which is C
restricted to the case where the program's meaning needs it.

## 5. How the borrow is decided

### 5.1 "Made of a parameter"

The existing analysis (`memory.md § Handing it back is not keeping it`) already decides, for an answer
that can hold an address, which arguments it is made of: `field(s: *S) -> *int = &s.y` is lent for as
long as `s` is. Borrowed return extends the same summary from addresses to **places**:

- a function's answer is a **place of parameter `p`** when every path answers a place expression rooted
  at `p` — a field chain, an element of a fixed array, the payload a `match` (or a `-> never`-guarded
  `unwrap`) binds, an element of a `Buf` reached through `p` — or the answer of a call whose own answer
  is a place of the argument handed in that position (so `self.kids.unwrap()` qualifies once `unwrap`
  does);
- a function's answer is **+0 from `p`** when every path answers references read out of `p`'s storage,
  none of them fresh, none released in the body (`find` in b08: the element of `self.items`, or
  `None`).

Both are fixpoints over the call graph, as `keeps` is; recursion settles at "not a place".

### 5.2 The caller

At a call whose callee answers a place of argument `a`:

1. if the answer's uses until its last one all lie in an inert stretch (§819's definition, which
   already names what ends one: a release, a write that may reach `a`'s storage, a call that is not
   known to do neither, an `await`, a lock), call `f$place`, read through the address, and do nothing
   at the end;
2. if the stretch ends before the last use, call `f$place`, and at the point the stretch ends
   **materialise** — load the value, take its counts — and use the copy from there on. The value is the
   same one the ordinary entry would have answered, because nothing between the call and that point
   could change it;
3. a use that stores, returns, captures, or hands the answer to a parameter its function keeps is a
   materialisation point of its own (as it is for §819 today).

The receiver itself must stay alive for the stretch: it is a local, a parameter, or storage reached
through one, and §819's argument covers it — nothing in the stretch can lower a count.

### 5.3 What the receiver costs

Decision 3 is independent of the answer: a by-value parameter the callee only reads, under the same
"nothing could write it" condition, is passed by address. In b01 and b07 that is the whole `Node` and
the whole `Universe`. Where the callee writes its copy (`self.x += 1` on a `var self`) or something it
calls might write the original, the ordinary by-value convention stays.

### 5.4 Separate compilation

`closures.md` §3.2 holds here too: every sysl body is read from source in every compilation; the place
entry is emitted beside the ordinary one where it qualifies, and a precompiled `.syslib` caller (std's
own code) simply calls the ordinary entry. A std method like `Option.unwrap` gets its place entry from
std's own compilation, keyed in the `.syslib` symbol list.

## 6. Interactions

- **Views and `@views(self)`.** A view answered by a method is already +0 and carries its owner word
  (`memory.md § Slices keep their backing alive`); nothing changes. `@views(self)` remains the
  declared contract for C-owned memory (decision 9).
- **"A borrowed pointer cannot escape".** A place answer is never a `*T` the program can see; it is an
  address the lowering holds for the stretch. Nothing new can escape, and the escape rules are not
  touched. A `-> ref T` result (decision 8) is bound by `ref`, so it inherits `It is a declaration,
  never a type` — it cannot be stored, captured or returned (except by another `-> ref` method, which
  is how a wrapper passes it on, as a `@views(self)` member does).
- **C1's block scopes** (`closures.md` decision 6). A borrow lives inside one stretch, which never
  crosses the end of the block that declares the receiver — the end of a block releases, and a release
  ends the stretch.
- **`Option[&T]` returns** (decision 4). `find(k) match Some(it) -> it.value` takes no count; `val it
  = find(k).unwrap()` then `items.clear()` materialises `it`'s count before the `clear`.
- **Inferred moves (0.6.0).** A +0 answer is never moved out of: moving it is a materialisation.
- **Closures (C2).** A borrowed answer captured by a by-reference closure is a capture of the local
  that holds it; the capture is a use, and the closure's calls are uses, so the stretch covers the
  closure's last call or the answer is materialised before it is captured.
- **Threads.** Taking or giving back a lock ends a stretch (§819), so a borrow never spans one. The
  d02 shape under decision 4 still releases outside the lock unless decision 10 lands too — the two are
  independent fixes of the same race, and decision 10 is the one that fixes it for every local.
- **`@no_alloc` / bare metal.** Nothing allocates; the place entry is plain code.

## 7. Drop order

### 7.1 Survey

| language | cleanup forms | order |
|---|---|---|
| **Rust** | `Drop`; no `defer` (a `scopeguard::defer!` is a local guard) | locals in reverse declaration order, temporaries at the end of their statement; a guard is a local, so guards and locals interleave |
| **C++** | destructors; scope guards are objects | reverse order of construction — guards interleave with every other object |
| **D** | destructors and `scope(exit)` / `scope(failure)` | one reverse lexical order over both |
| **Zig** | `defer`, `errdefer`; no destructors | LIFO by position — every cleanup is a defer, so it is trivially interleaved |
| **Swift** | `deinit`, `defer` | defers LIFO at scope exit; an object's lifetime ends after its last use, not at the scope's end (with exceptions for deinit barriers), so the order of a release against a `defer` is unspecified; `withExtendedLifetime` pins it |
| **Go** | `defer`; finalizers | defers LIFO at **function** exit; no deterministic destruction |

Every language with both deterministic destruction and a scope-exit action (C++, D, Rust through
guards) runs them in one reverse order by position. sysl's today-order — all defers, then all releases
— is the outlier, and d02 is the program it gets wrong: a `defer` written right after taking a lock
means "release the lock last", and today it is released before the later locals.

### 7.2 The rule

> **Leaving a block undoes it in reverse.** Each deferred statement the block reached and each counted
> local it declared is undone in the reverse order of where it was written: the last one written goes
> first. A `defer` therefore runs after everything declared below it is released, and before
> everything declared above it — so a lock taken, then `defer`-unlocked, stays held while every local
> made under it is given back. An `errdefer` takes the same place when the block is left by an error.
> A temporary is released at the end of its statement, as before. Leaving from the middle undoes the
> innermost block first.

Why it is safe: a `defer` can name only locals declared above it (scope), and those are released after
it runs — the guarantee `memory.md § Where defer sits` gives today ("every local it names is still
alive") holds unchanged (d04 is the boundary case: assigned later, declared before).

### 7.3 What changes

- **Output order**: d01 becomes `drop c`, `defer 2`, `drop b`, `defer 1`, `drop a`; d02 becomes
  `release the host's last count`, `unlock`; d03's loop iterations become `drop c0`, `defer loop`,
  `drop b0`.
- **Code generation**: a block's cleanup is one list in position order instead of two; each early exit
  (`return`, `break`, `continue`, `?`, an `errdefer`-triggering error) runs the suffix of that list for
  what was reached. A local whose ownership was moved (d05's `b` into `keep`) has no entry to run, as
  today.
- **async**: a block that spans an `await` keeps its list in the task frame; nothing else changes.
- **A trap runs nothing**, as before.

### 7.4 Migration risk

A program changes behaviour only where a block has both a `defer` and, declared below it, a local whose
release is observable — a `Drop` with side effects, or a count another thread can see. The first
is what the race was; the second a reordering of output lines. A program that relied on the old order
would have to need a later local's destructor to run **after** a cleanup written above it, i.e. a
destructor that uses a resource the `defer` already closed — which is a use-after-close today. The
census (§8, D1) finds every block of the shape; each hit is read, not assumed.

## 8. Open questions — the user decides

1. **Q1: inferred, with a report (decision 1).** The alternative is a declared mark (option B). I
   recommend inferred, plus `--explain-borrows` (beside `--explain-escapes`) listing each call that
   took the place entry and each materialisation point with what ended the stretch — the visibility
   without the refusals.
2. **Q2: a writable borrowed answer, `-> ref T` (decision 8), in 0.8.0?** It is the one part that adds
   syntax and the one part HashLife does not need. Recommended: yes, as the last item (R6), since
   `memory.md`'s `ref` hazard rules already say everything about its lifetime; but it can be split out
   without touching the rest.
3. **Q3: by-value parameters passed by address (decision 3)** for every function, not only methods
   whose answer is borrowed. Recommended: yes — it is the same condition and the larger win (b07 copies
   a six-field `Universe` twice per `population()`).
4. **Q4: the drop order (decision 10)** — interleaved reverse by position. The alternative is today's
   order kept and documented, with a lint for d02's shape. Recommended: change it, as a **behaviour
   change** in the 0.8.0 notes; it is what every comparable language does and what the race needed.
5. **Q5: release at the last use (decision 12).** Recommended: no; stay lexical.

## 9. Implementation plan — bounded items

Each is one agent, one branch off `dev`, in dependency order. Drop order (D) and borrowed return (R)
are independent tracks.

| item | what | depends on | tests |
|---|---|---|---|
| **D1** | census-only diagnostic: a block with a `defer`/`errdefer` and, declared below it, a local of a counted or `Drop` type; run over the org; every hit read and recorded | — | the census; a probe per block form |
| **D2** | one cleanup list per block in position order; every exit (`return`, `break`, `continue`, `?`, error into `errdefer`, end of block) runs its suffix; async frames | D1 | d01–d05 as `output` tests in each block form (`if` arm, `match` arm, loop body, nested block, `defer` inside a `defer`'d block); d02 under TSan with a real `Mutex` and two threads; leak sweep |
| **D3** | docs: `memory.md § Where defer sits`, `statements.md § defer` and `§ errdefer`, `compiler-notes.md`; 0.8.0 notes **Behaviour change** | D2 | the docs sweep for those pages |
| **R1** | the summary: "answer is a place of `p`" / "answer is +0 from `p`", a fixpoint beside `keeps`, through `unwrap`, `match` payloads, field chains, fixed arrays, `Buf` elements; `--explain-borrows` prints it | — | one row per place form; recursion; generic instantiation where `T` is / is not a reference; trait object, `extern`, fn pointer answer "not a place" |
| **R2** | decision 3: a read-only by-value parameter passed by address | R1 (shares the "nothing could write it" query) | b01/b07 IR: no whole-struct copy; a callee that writes its copy keeps by value; a callee calling something that may write the original keeps by value; `-O0`/`-O2` |
| **R3** | the place entry `f$place` and the caller's choice (§5.2): inert stretch → read through; stretch ends → materialise there; store/return/capture/keep → materialise | R1 | b01 and b08 show zero count operations in `walk`/the caller; a write between call and last use (materialised, old value seen); a release between; an `await` between; a lock between; a closure capturing the answer; `SYSL_COUNT_ALLOCS=1` and ASan on every row |
| **R4** | +0 reference answers (`&T`, `Option[&T]`, structs and tuples of references) through the same entry | R3 | b08; a path answering a fresh reference keeps +1; a body releasing what it answers keeps +1; leak sweep |
| **R5** | docs: a `memory.md` section "A method's answer borrowed from its receiver" (b01/b02 cost side by side as `output` blocks via `SYSL_COUNT_ALLOCS`), the `@views(self)` "narrow on purpose" paragraph updated to point at it, `compiler-notes.md`; measure automata `hashlife-075` (instruction count, as the 0.7.8 re-time did) | R2–R4 | the docs sweep; the measurement in the report |
| **R6** (Q2) | `-> ref T`: parse, type, bind by `ref` only, hazards from the body's place; refused as a field/parameter/element type, captured, or stored | R1 | the `ref` hazard matrix of `memory.md § The one rule` repeated through a method; `Buf` element + `push` refused while live |

Plan size: **9 items** (3 drop order, 6 borrowed return; R6 conditional on Q2). D1–D3 can run at once
with R1.

## 10. Edge-case matrix

| axis | cells |
|---|---|
| **what is answered** | field · field chain · fixed-array element · `Buf` element · `Option` payload via `unwrap` · `match` payload · `&T` read out · `Option[&T]` · struct of references · tuple of references · a view (unchanged) · a fresh value on one path (not a place) |
| **receiver** | `self` by value · `&self` · `*self` · a parameter not `self` · a local · storage through `&T` · module storage · a temporary (`make().quad()` — ordinary entry) |
| **what ends the stretch** | nothing · a write to the receiver's field · a write to another object of its type · a release · a call into an unknown body · a closure call · `await` · lock/unlock · end of block |
| **what the caller does with the answer** | reads a field · passes it to a borrowing callee · to a keeping callee · stores it · returns it · captures it (by reference / by value) · moves it |
| **callee kind** | method · free function · generic per instantiation · trait object · fn pointer · `extern` · precompiled std (`.syslib`) · a package |
| **drop order** | defer/local interleaving in each block form · early exit by `return` / `break` / `continue` / `?` · `errdefer` on the error path and not on the ok path · a local moved before the end · async across `await` · a trap (nothing runs) |
| **build modes** | `-O0` / `-O2` · `@no_alloc` · `thumb-freestanding` · ASan · TSan (d02) · `SYSL_COUNT_ALLOCS=1` |

Cells skipped, with the reason: a `-> ref T` answer through a trait object (R6 refuses it, as
`@views(self)` requires the trait to declare it — a later item if wanted).
