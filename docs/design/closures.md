# Closures that capture by reference — design (0.8.0)

**Decided by the user:** a closure that does not escape captures by **reference**, so a write to a
captured variable reaches the variable — what Rust, Swift, Kotlin and JavaScript do. The cost is a
pointer to the variable's slot: no allocation, nothing `@no_alloc` refuses. A closure that escapes
keeps today's by-value capture. 0.7.5's capture-write refusal (`expressions.md § A write to a capture
that nothing will see is refused`) refuses exactly the programs this makes legal:

```sysl
call(() -> hit = true)
each(xs, n -> if n > best then best = n)
print(best)
```

Nothing here is built. This note settles what the language says, so that the implementation is
mechanical, and lists what the user still has to decide (§12). Every claim about today's behaviour
is from a probe in [`closures-probes/`](closures-probes/), run on the installed **sysl 0.7.5**
(`zsh closures-probes/run.zsh`).

## 1. Decisions

| # | question | recommendation | why, in one line |
|---|---|---|---|
| 1 | how escape is decided | **inferred**, never declared: a closure escapes where the compiler would refuse it today *if it held the address of the variables it captures* — the existing `keeps` analysis, asked about the closure literal | the analysis already exists, already covers closure parameters (p16, p23, `memory.md` 4776), and `memory.md § Escape analysis` already rejects `@escaping` by name |
| 2 | how the fact crosses a compilation boundary | **it does not need to.** Every sysl body is read from source in every compilation (a `.syslib` carries a symbol list and no trees); the mode belongs to the closure literal, which is in the caller, and the callee's object code is the same either way | §3.2 |
| 3 | when the callee is unknown | the defaults `keeps` already has: a trait object keeps a callback wherever any implementation does (p11); a closure called through a closure borrows (p13); a function pointer and an `extern` cannot be handed a closure at all (p12); `&sync Fn`, `@crossing`, `@scoped`, a thread and a spawned task always escape | §3.3 |
| 4 | the unit of the decision | **per closure literal**, not per capture: all of a literal's captures are by reference, or all by value | one sentence for the reader; a mixed closure is a puzzle |
| 5 | what a read-only capture does | **by reference too** — a non-escaping closure sees the variable, not a snapshot (p05 changes from `1` to `5`) | the user's rule says "captures by reference", and JS/Swift/Kotlin agree; a `val` capture may still be copied, since nothing can tell |
| 6 | the lifetime escape is judged against | **the innermost block declaring a captured variable**, not the frame: a closure that may be reached after that block ends escapes | a loop body's variable is a new variable each iteration (p26, p27); judged at the frame, by-reference would hand every closure the last iteration's |
| 7 | `var g = () -> …`, `val g = …`, a stored `&Fn` local | an ordinary value of the closure's type; by reference while it stays inside the captured variables' block, by value once it may leave it. **`val g` holding a writing closure becomes legal** when it is by reference — calling it no longer writes the closure | §4 |
| 8 | the `&Fn` box | a non-escaping closure erased to `&Fn` is **built in the caller's frame** with an immortal count — today it is a heap box, and `@no_alloc` refuses it (p09) | without it, "no allocation" holds only for the bare-arrow parameter |
| 9 | threads | `&sync Fn`, a `@crossing` or `@scoped` parameter: **always by value** | a write from another domain through the caller's slot is a race; the raw tier (`*T`) is still there for the program that means it |
| 10 | generics (`f: A -> B`) | the same rule; the mode is fixed per literal *after* the `keeps` pass, so the closure struct's layout is settled late | §6 |
| 11 | ownership (0.6.0) | a by-reference capture takes **no count**; the captured variable is **live until the closure's last call**, so no inferred move takes it before then; nothing moves out of a by-reference capture inside the closure; `@pinned` captures become legal by reference; a closure argument's writes are **writes by the call** to the `ref` hazard check | §7 |
| 12 | `defer`, and drop order | independent questions. By-reference capture is safe under either order, because a closure cannot outlive its captured variables' block and a release empties a slot without ending it | §8 |
| 13 | async | **a Task holds its arguments until it finishes**: an async call keeps every parameter, and the Task's own escape is followed as a stored closure's is. Today this is missing — **a bug** (p21, p25, ASan-confirmed) | §9 |
| 14 | aliasing | **no exclusivity rule.** Two closures over one variable share it (p04/p18 become legal); a write made during the call through `&n` is seen (p05). A whole variable's slot never moves, so none of it is unsafe | §10 |
| 15 | migration | three accepted shapes change meaning (M1 loop-made writer, M2 aliased read-only capture, M3 callee stops keeping); **the org sweep found none of M1/M3** — its one hit is a today-bug (interpolation hides a read-after, p29), not a change | §11 |

## 2. What 0.7.5 does — the probes

| probe | program | 0.7.5 | 0.8.0 under this design |
|---|---|---|---|
| p01 | `call(() -> hit = true); print(hit)` through `&Fn` | refused: *a closure captures by value, so it holds a copy of 'hit' … nothing in the closure reads it back* | `true` |
| p02 | `each(xs[..], n -> if n > best then best = n)` through a bare arrow | refused (the `val` array's view is `[]const int`; with that fixed, the same lost-write refusal) | `9` |
| p03 | `for … print(twice(() -> { n += 1; n }))` | **accepted, `3 3 3`** — each closure starts from its own copy | **`3 7 11`: meaning changes** |
| p04 | two `var` closures both writing `n` | refused: *the variable is used afterwards* (the second closure's capture is a read after the first) | `1 2 3` |
| p05 | `poke(&n, () -> n)` where `poke` writes `*p = 5` first | **accepted, `1`** | **`5`: meaning changes** |
| p06 | a counter stored in a `static` through `install` | `11 12` | `11 12` — it escapes, so by value |
| p07 | `install(() -> hit = true)`, stored | refused (lost write) | refused, and the message says *why* it is by value: `install` keeps it |
| p08 | `var bump = () -> n += 1; bump(); bump(); print(n)` | refused (lost write) | `2` |
| p09 | `@no_alloc` with a bare-arrow closure / a `&Fn` closure | bare arrow accepted; `&Fn` refused: *a reference needs an allocator* | both accepted (decision 8) |
| p10 | `call(() -> c.bump())`, `bump(*self)` | refused (lost write through `*self`) | `1` |
| p11 | a closure holding `&n` handed to a trait object's `visit(*self, f: &Fn…)` | `6` — the object's implementations are asked | unchanged; the same question decides the mode |
| p12 | a `&Fn` parameter on a `*extern` function pointer | refused: *an aggregate crosses to C …* | unchanged: no closure reaches a function pointer |
| p13 | a closure handed to a `&Fn` that takes a callback | `9` — a call through a closure borrows | unchanged; by reference |
| p14 | a closure holding `&n` erased into `&sync Fn`, only called | `1` | unchanged; by value (decision 9) |
| p15 | a closure holding `&n` handed to an `async` function, `block_on` in the frame | `2` | `2`, by reference: the Task does not leave the frame |
| p16 | `() -> *p` returned | refused: *holds the address of a local of 'make'* | unchanged; the implicit form is by value instead |
| p17 | `defer print(n); n = 2` | `2` — a deferred statement reads the variable when it runs | unchanged |
| p18 | two generator closures over one `cur`, `*self` call | refused (*used afterwards*) | `12` then `34` |
| p19 | `var bump = () -> { n += 1; n }; print(bump(), bump())` | `1 2` | `1 2` — the same, and `n` outside is now `2` |
| p20 | a `string` captured read-only, through a non-keeping call | `5` (a count taken and given back) | `5`, no count |
| p21 | `make() -> Task[int]` answers `work(() -> *p += 1)` over a local | **accepted — a use of a dead frame** | refused (bug fix, decision 13) |
| p22 | `var g: &Fn() -> unit = () -> *p += 1` in the frame | `2` | unchanged |
| p23 | a bare-arrow `hold(into: *Buf[&Fn…], f)` that pushes `f` | refused: *'hold' keeps its parameter 'f' (it is passed to 'push', which keeps it)* | by value; a writing closure there is refused naming `hold` |
| p24 | `val a; defer …; val b`, both `Drop` | `body ab / defer / drop b / drop a` | unchanged (§8) |
| p25 | `async work(p: *int)`, its Task answered out of the frame | **accepted; ASan: `stack-use-after-scope`** | refused (bug fix) |
| p26 | a loop body's `&n` kept in an outer `var g` | `20` (a stale slot) | the implicit form is by value: `20` from the copy |
| p27 | the same with a `string`, read after the block | **accepted; ASan: `heap-use-after-free`** | the implicit form is by value; the explicit `&s` is §12 Q4 |
| p28 | `q = &s` from an inner block, read after it | **accepted; ASan: `heap-use-after-free`** | §12 Q4 |
| p29 | a stored writing counter, the variable read after it inside `f"${clicks}%d"` | **accepted, prints `clicked 0 times`** (plain `print(clicks)` is refused) | refused (bug fix C0b); by value, since it is stored |

Three of these are bugs in today's compiler and one is a hole the reference does not speak to:

- **p29 — a read inside string interpolation is not a read for the lost-write rule.**
  `read_after_copy_write` is asked from `name_expr`, and an interpolated name reaches the `$format`
  desugaring without passing it. Found by the org sweep in `webview-demo` (§11). A 0.7.x patch fix.

- **p21/p25 — an async call keeps its arguments, and `keeps` does not know.** `memory.md § A
  borrowed pointer cannot escape` says a function keeps a parameter it stores somewhere the call does
  not own; an `async` function stores every parameter in the Task it answers. A `*T` to a local, or a
  closure holding one, handed to an async call whose Task leaves the frame, is a use of a dead frame
  (ASan, `detect_stack_use_after_return=1`: `stack-use-after-scope`). This is a 0.7.x patch fix of its
  own, and by-reference capture depends on it.
- **p27/p28 — the address of an inner block's local, kept in an outer block.** The rule is judged
  against the frame, so `q = &s` from inside an `if` passes, and reading `*q` after the block is a
  use-after-free. `*T` is the raw tier, and the reference only promises the frame boundary, so this is
  a question (§12 Q4), not a bug. **For the implicit, by-reference form it is a requirement**: the
  program wrote no `&`, so the compiler must judge against the block (decision 6).

## 3. How escape is decided

### 3.1 Inferred, through the analysis that already exists

`keeps.sysl` works out, for every function and every parameter, whether the function lets that
parameter outlive the call, to a fixed point over recursion, and holds every call to one rule: the
address of storage that is not the program's to give away may not be passed to a parameter that keeps
it. **It already treats a closure as a carrier of addresses**: a closure holding `*p` to a local may be
passed to `call` (borrowed) and is refused at `install` (kept), at a `return` (p16) and at a generic
`hold` that pushes it (p23).

So by-reference capture needs no second analysis. **A closure literal escapes exactly where today's
compiler would refuse it if each of its captures were the address of the captured variable.** Every
place the closure's value flows is asked:

1. an argument → does the callee keep that parameter (`keeps`, including through a trait object, a
   closure-typed callee and an async call)?
2. a local, or a field of one → does that local escape, or may it be reached after the captured
   variables' block ends (decision 6)? — the fixpoint over locals `memory.md § What escapes` item 5
   already describes;
3. a return, a `static`, a field reached through a pointer or a reference, a buffer → escapes;
4. a conversion to `&sync Fn`, a `@crossing` or `@scoped` argument → escapes (decision 9).

If none of them escapes, the closure captures by reference; otherwise by value.

**Why not `@escaping`.** Swift declares it because its closures are separately compiled through
module interfaces with no bodies. sysl has the bodies (§3.2), `memory.md § Escape analysis` already
says "There is no `@escaping`" for the same question about views, and declaring it would spell twice
what `keeps` already knows. The user's preference (inferred, pleasant) and the existing design agree.

### 3.2 Separate compilation: the fact needs no carrier

- **Every sysl body is source in every compilation.** `syslib.sysl`'s header: *"The metadata carries
  no trees … the library is always read from source beside the compiler, so the trees are already in
  hand."* A package is fetched as source. So `keeps` already runs over the standard library's bodies
  and every package's in each program — which is how p23 names `push` today. A `.syslib` is keyed by
  the fingerprint of the very source the analysis reads, so the two cannot disagree.
- **The mode is a property of the closure literal, and the literal is in the caller.** The callee
  receives a value it calls through `Fn::call(*self, …)`; whether that value's fields are copies or
  addresses is invisible to it. A precompiled `each` or `sort_by` in the `.syslib` is the same object
  code either way. Nothing is added to a signature, a `.syslib` or a generated header.
- **A library that changes its body changes its callers' closures' mode.** That is the price of
  inference, and it is bounded: a closure that writes a capture and stops being by reference is
  refused (the lost-write rule, now naming the callee: *'install' keeps its parameter 'f' (it is stored
  in 'later'), so the closure holds a copy of 'hit' …*), so the change cannot be silent for the
  motivating shapes. The residue is §11's shape M1 under a callee that started keeping — a closure that
  then has state of its own, which is what by-value means.

### 3.3 Where no body is in sight

`memory.md § Where no body is in sight, the call borrows` already answers every case; by-reference
capture reads the same table:

| what is called | does a closure argument escape? |
|---|---|
| a sysl function | what its body does (`keeps`) |
| a method through a trait object | wherever any implementation keeps it (p11) |
| a closure (`&Fn` / bare arrow) | no — a closure that keeps what it is handed is refused where it is written (p13) |
| a function pointer | cannot be handed a closure: a `&Fn` is an aggregate C cannot be passed (p12) |
| an `extern` | cannot be handed a closure, for the same reason; a C callback is an `@export` trampoline and a `*T` context, under `@keeps(p)` — the raw tier, unchanged |
| an `async` function | its Task holds it; the Task's escape decides (§9) |
| `&sync Fn`, `@crossing`, `@scoped`, `spawn` | always escapes (decision 9) |

**There is no "unknown"** left over: every call shape either has a body or a default the reference
already promises, and a default is held to its promise where the body is written.

## 4. What a closure value is

A closure literal is still a struct and an `impl` (`compiler-notes.md § Closures`). By reference, each
field holds the **address of the captured variable's slot** — exactly what a nested function's
environment holds today (*"The environment holds the ADDRESS of every capture — a closure may outlive
its frame, a nested function may not"*); by value, a copy. A capture is then read as
`*self.field`, a place, as a nested function's is.

- **`var g = () -> n += 1`** in the block that declares `n`, called there: by reference (p08 prints
  `2`). Handed to `call(g)`, assigned to another local of that block, put in a local `&Fn`: still by
  reference.
- **`val g = () -> n += 1`** becomes legal when `g` is by reference: a call writes through the
  closure's pointer, not into the closure, so `Fn::call` no longer needs the binding to be mutable. The
  refusal *'call' takes '\*self' … write 'var bump'* stays for a writing closure that is by value.
  (Implementation: the `val` check asks the mode — the refusal is owed at the call and said once the
  mode is known, naming what keeps the closure.)
- **Stored where it outlives the captured variables' block** — returned, put in a `static`, a field
  through a pointer, a buffer, a local of an outer block (p26): by value, and today's lost-write rule
  applies to it, with the reason named.
- **A `&Fn` of a non-escaping closure** is built in the caller's frame (decision 8): the heap box's
  header and payload in a slot of the frame, counted as a heap box is, with a hook that lets go of
  what the payload holds and frees no storage (none at all for a payload holding nothing). A retain
  and a release in a borrowing callee touch only the slot's count, and `@no_alloc` accepts it. A count
  rather than an immortal mark, because a by-value capture (a `val` string) still needs its release
  at the last use. "Non-escaping" is the probe's answer for the literal **plus** one more root: the
  literal's own site, judged against the innermost loop body around it, since a literal built again
  next turn builds into the same slot (`gs[i] = () -> k` in a loop is on the heap). An escaping one
  is boxed as today, and `--explain-escapes` names why.

## 5. Threads

**A closure crossing a concurrency domain captures by value, always.** `&sync Fn` already checks what
it captures (scalars, arrays, `&sync T`, `*T` — `sysl-lang/CLAUDE.md`), and a write through the
caller's slot from another thread is a data race no type stops. `@scoped(f)`'s closure written at the
argument already *borrows* views from the caller, read-only, joined before the scope returns; it stays
by value for its scalars (a scoped child mutating the parent's `int` would need the parent not to read
it until the join, which is a lifetime rule this design does not introduce). A program that means a
shared counter writes `&sync Cell` or an atomic, as today.

## 6. Generics

`f: A -> B` is `[$F: Fn(A) -> B](f: $F)`: monomorphized, the closure passed by value, called
directly. Nothing about that changes: `keeps` asks the instantiation's body, so `each[$closure3]`
borrows `f` and the closure is by reference.

**The order of work changes.** Today the closure's struct is laid out when the literal is read, and
`check_keeps` runs after analysis. The mode depends on `keeps`, so: the analyzer types every capture as
it does today and records the literal as *mode pending*; after `check_keeps`, a small pass decides each
literal's mode; lowering lays out the struct (pointer or value fields) and reads a capture as
`*self.f` or `self.f`. The instantiation's name is the closure's base name, not its layout, so no
re-instantiation is needed. What the analyzer decides today from the mode — the lost-write refusal and
the `val` refusal — moves into that pass.

(An explicit `[F: Fn() -> unit]` bound does not parse — `']' expected` — and only the bare arrow
spells it. `types.md` shows the bracket form as an explanation, not as syntax, so this is not a claim
broken; noted for whoever writes the trait-bound pages.)

## 7. Ownership (0.6.0)

- **No count.** A by-value capture of a `&T`, a `string` or a slice takes a share where the closure is
  made and gives it back when the closure dies (p20); by reference there is nothing to take. That is
  the common closure handed to `each`, `sort_by`, `with` — it gets cheaper.
- **Liveness.** A variable captured by reference is used by every call of the closure, so its last use
  is the closure's last use. The inferred-move pass must not move out of it while a closure holding it
  is live; a use that would move inside the closure body takes a count instead (the closure may run
  again — there is no `FnOnce`).
- **Drop.** The variable is released at the end of its block, as today. A closure cannot be reached
  after that block (decision 6), so it is dead first; nothing is released twice or early.
- **Views.** A captured view is a variable like any other: by reference the closure reads the slot
  that holds the view, which owns its backing.
- **`ref`.** `ref` names may not be captured (`memory.md` 1515), and stay so. But a `ref` live in the
  caller across a call whose closure argument **writes** the variable the `ref` stands in is exactly
  the hazard of `memory.md § The one rule: what may move underneath it`: the call must be counted as
  writing that variable. Today the check asks the callee's body; it must also ask the by-reference
  closures among the arguments.
- **`@pinned`.** A pinned value cannot be captured today (`refuse_pinned_captures`), because capture
  copies it. By reference nothing is relocated, so a non-escaping closure may capture one — a small
  pleasantness win that falls out.

## 8. `defer`, and the order of releases

**Today** (`memory.md § Where defer sits`, p24): a block's deferred statements run first, then its
references are released in reverse order of declaration. Rust instead runs everything in one reverse
order, so a guard made before a later local is dropped after it. Benchtop met a race from the
difference.

**By-reference capture does not depend on which order is chosen**, so the two need not be settled
together:

- a captured variable's **slot** is the frame's for the whole call — a release empties it, it does not
  end it — and a by-reference closure holds the slot;
- a closure cannot be reached after its captured variables' block ends (decision 6), and anything
  holding it (a guard whose destructor calls it, say) is declared after the variables it captured, so
  under either order it dies before they are released;
- a `defer` is not a closure: it reads its variables when it runs (p17), which is already what a
  by-reference closure does.

The one visible interaction: a value whose destructor calls a by-reference closure sees whatever the
block's deferred statements did, because they ran first. That is true today of a destructor reading
through a `*T`, and it is the drop-order question itself. **Recommendation: decide drop order on its
own item** — and the evidence of benchtop's race argues for one interleaved reverse order (Rust, C++,
Swift's `defer` within scope), but that is outside this note.

## 9. async / await

- **A Task holds its arguments until it finishes.** An async call therefore keeps every parameter —
  for as long as the Task lives. Today `keeps` does not know this (p21, p25: accepted, a use of a dead
  frame). **Fix first, as a 0.7.x patch**: a `*T` or a closure carrying one, handed to an async call,
  makes the Task a carrier of that address, and the Task is then followed as a stored closure is
  (`memory.md` 4741): awaited or `block_on`'d in the same frame it is fine (p15); answered, stored, or
  handed to an executor's queue it is refused.
- **By reference inside an async body.** A closure in an async function that captures a local by
  reference and is live across an `await` needs that local in the heap frame, not in the step
  function's stack. The coroutine lowering already decides which locals cross a suspension; a
  by-reference capture whose closure crosses one is such a local.
- **`await work(() -> hit = true)`** in an async body is the motivating case in async form: the Task
  does not escape, so the closure is by reference and `hit` is written.

## 10. Aliasing

- **Two closures over one variable share it.** p04 and p18, refused today, become legal and print
  `1 2 3` / `12 34`. This is the JS/Swift behaviour the user chose.
- **A write the caller makes during the call is seen.** `poke(&n, () -> n)` prints `5` (p05). sysl has
  no exclusivity rule anywhere else — a `*T` aliases freely — and none is needed for safety: a whole
  variable's slot never moves, only its value is replaced, and replacing a value through one name while
  a closure reads it through another is the same as two `*T` to one local.
- **The only unsafe aliasing is a `ref` or an iteration standing inside the variable's value** while a
  closure replaces that value. The `ref` case is §7's rule. The iteration case — `for x in xs do
  f(() -> xs = [])` — is safe only if the loop holds its own count of what it walks; the matrix (§14)
  has a row for every loop form, since `escape.sysl`'s `loop_views` has missed loop forms before.

## 11. Migration — what 0.7.x code changes meaning

Today's refusal rejects a closure that writes a capture **unless** the closure reads it back **and**
the enclosing body never names the variable after the closure. Everything rejected becomes legal, so
it changes nothing that compiled. What compiled and changes:

- **M1 — a writing, reading-back closure made more than once over one variable** (p03): a loop, or a
  helper called twice. By value each starts from the variable's value; by reference each continues.
- **M2 — a read-only capture of a variable written during the closure's life** through `&n` (p05) or
  by a nested function (which already captures by address).
- **M3 — a writing closure that stops escaping** because a callee stopped keeping it: the reverse of
  §3.2's residue, also M1-shaped.

**The sweep.** A stage-1 compiler with one extra fault at the accepted read-back write
(`closure.sysl`'s `refuse_lost_writes`, the `copy_writes.push` arm, under `SYSL_CLOSURE_CENSUS=1`;
built, not committed) was run as `emit-typed` over every project in every repository under
`~/dev/sysl-lang` ([`closures-probes/sweep.zsh`](closures-probes/sweep.zsh), which quotes the
two-line instrumentation). It lists every M1/M3 candidate — every closure that
writes a capture and compiles today — which is a superset of what changes.

**Result: 140 projects analysed, one hit, and it is not a migration case — it is a bug.**

- `webview-demo/main.sysl:21` — `w.bind("count", (args) -> { clicks += 1; Ok(f"${clicks}%d") })`.
  `bind` stores the callback, so the closure escapes and stays by value under this design: **its
  meaning does not change.** But line 35 reads `clicks` after it (`print(f"… ${clicks}%d clicks")`),
  which should have been refused today and was not: **the read-after refusal does not see a read inside
  string interpolation** (p29 prints `1 2` then `clicked 0 times`; with `print(clicks)` the same program
  is refused). webview-demo has been printing "closed after 0 clicks". That is a 0.7.x patch fix
  (item C0b), after which webview-demo needs a `&int` (or a `static`) — under 0.8.0 too, since `bind`
  keeps its callback.
- **No M1 or M3 case exists in the org.** Every other closure that writes a capture is already refused.
- **Limits of the sweep, stated:** `emit-typed` analyses a project's sources, not its `@tests` files;
  17 of the 140 projects stopped at an unrelated error against dev's library (stale imports such as
  `ptr_cast`/`str_cast` moved to `sysl.raw`, `str(x)` removed in 0.7.0, a C header absent on this
  machine), so their remaining bodies may not have been read — the 0.8.0 org sweep after the switch
  covers them. The compiler's own tree and `library/` are among the 140.

M2 is not something a single fault can list (it needs the address-taken set of the enclosing body);
implementation item C9 adds it as a one-release diagnostic, run over the census before the switch.

## 12. Open questions — the user decides

1. **Read-only captures by reference (decision 5).** The alternative — by reference only for the
   captures a closure writes — keeps p05 printing `1` and makes a migration of M2 impossible, at the
   cost of a two-mode rule ("it sees the variable if it writes it, a snapshot otherwise"). Recommended:
   all by reference.
2. **Per literal, not per capture (decision 4).** Per capture would let a closure that writes an outer
   counter and reads a loop variable, stored in an outer local, have both; per literal refuses the write
   with a message naming the loop variable. Recommended: per literal; revisit only if it bites.
3. **`@scoped` children (§5).** By value recommended; by reference would need a "not read until joined"
   rule.
4. **The explicit `&s` across a block (p27, p28).** Not a bug by the reference's own words (the raw
   tier; the promise is the frame), but the block-level judgement decision 6 builds for the implicit
   form could refuse it too at no extra cost. Recommended: yes, in the same item, as a behaviour change.
5. **Drop order (§8)** — its own item.

## 13. The rule, as the reference will say it

> **A closure that does not outlive the variables it captures captures them by reference**: it holds
> where each variable lives, so a write in the closure reaches the variable, and a read sees its value
> at the time of the call — the variable itself, shared with the code around it and with every other
> such closure. That is the closure handed to `each`, `sort_by`, `with` or a test helper, the closure in
> a local `var` called in its own block, and any closure every use of which the compiler can follow.
> **A closure that may outlive them** — returned, stored in a `static` or a structure that outlives the
> block, handed to a parameter its function keeps, to an async call whose task leaves the frame, or
> erased into a `&sync Fn` for another domain — **captures by value**: it carries its own copy, a write
> reaches that copy, and a write that nothing would read is refused, naming what keeps the closure.
> Nothing is written for either: the compiler works it out from the bodies, as it does for every
> pointer a function is handed. A by-reference capture costs a pointer and takes no count; a
> non-escaping closure handed as a `&Fn` is built in the caller's frame, so neither allocates.

## 14. Implementation plan — bounded items

Each is one agent, one branch off `dev`, in dependency order. C0 is a patch.

| item | what | depends on | tests |
|---|---|---|---|
| **C0** (patch, `release-0.7`) | an async call keeps every parameter while its Task lives; the Task is a carrier of what its arguments carry, followed as a stored closure is | — | p21, p25 refused; p15 accepted; a Task answered/stored/queued × `*T` / closure-with-`*T` / `&T`; an awaited Task in an async body; ASan on the accepted ones |
| **C0b** (patch, `release-0.7`) | a read of a variable inside string interpolation (`f"${x}"`, `s"$x"`, a format spec) counts as a read for `read_after_copy_write`; webview-demo then holds `clicks` as `&int` | — | p29 in `f`/`s` strings, with and without a spec, in a nested interpolation, inside the closure's own span (not a read after) |
| **C1** | judge a frame address against the innermost **block** that declares it (decision 6) — at first only for the closure decision; Q4 decides whether `&s` gets it too | — | p26/p27/p28 shapes in every block form: `if`, `match` arm, `for`/`while`/`loop` body, nested block, `defer` |
| **C2** | the mode decision: after `check_keeps`, mark each closure literal by-reference or by-value per §3.1; a capture of a by-reference literal becomes `*self.f` (the nested-function environment's form); laid out at lowering | C0, C1 | p01, p02, p08, p10, p19, p22; the flows of §3.1 one row each |
| **C3** | move `refuse_lost_writes` and the `val` refusal into that pass, by-value literals only; the message names the reason (callee + its own `keeps` why, return, `static`, outer block, `&sync`) | C2 | p07, p23 messages; `val` writer by reference accepted, by value refused |
| **C4** | a non-escaping closure erased to `&Fn` is built in the frame with an immortal count; `@no_alloc` accepts it | C2 | p09; retain/release in a borrowing callee; `--explain-escapes`; leak sweep |
| **C5** | ownership: no count for a by-reference capture; liveness through the closure's last call for inferred moves; no move out of a capture inside; `ref` hazard counts by-reference closure arguments' writes; `@pinned` captures by reference allowed | C2 | the §14 matrix ownership rows; `SYSL_COUNT_ALLOCS=1` on each |
| **C6** | async bodies: a local captured by reference by a closure live across `await` goes in the heap frame | C0, C2 | closure made before / called after an `await`; nested async |
| **C7** | threads: `&sync Fn` / `@crossing` / `@scoped` / `spawn` force by value; the existing crossing checks unchanged | C2 | p14; a write capture into each is refused naming the domain |
| **C8** | docs: `expressions.md § Closures` (the two subsections rewritten around §13's paragraph, p01/p08/p10 as `output` blocks), `memory.md § Escape analysis` and `§ Where no body is in sight` (a closure row), `async.md` (a Task holds its arguments), `compiler-notes.md § Closures`; 0.8.0 notes: **Behaviour change** above the features | C2–C7 | the docs sweep for those pages |
| **C9** | migration: before the switch lands, a census-only diagnostic for M1/M2/M3 run over the org; every hit fixed in its repo or recorded | C2 | the census itself |

## 15. Edge-case matrix

Each row is a test (the error path included); a prefix is picked by C2's agent.

| axis | cells |
|---|---|
| **where the closure goes** | bare-arrow argument · `&Fn` argument · trait-object method argument · closure-typed callee · local `var` · local `val` · local `&Fn` · field of a local struct · element of a local array · returned · `static` · field through `*T` / `&T` · `Buf.push` · outer-block local · an `Option`/enum payload · a tuple slot |
| **callee keeps** | not at all · directly · through another parameter (`hold(into, f)`) · through a callee that keeps · recursively (fixpoint) · in one implementation of a trait among several · through a generic instantiation only for some type arguments |
| **what is captured** | `int` · struct by value · array · `&T` · `string` · view `[]T` · `Buf` · `*T` · `weak T` · `@pinned` · a `val` · a parameter (`n`, `self`) · a loop variable · a `match`-bound name · an outer closure's capture (closure in a closure) · a nested function's capture |
| **what the closure does with it** | read · `=` · compound `+=` · `*self` method · field write · element write · `ref` inside · passes it to a keeping callee · moves it (last use) |
| **block of the captured variable** | the function body · `if` arm · `match` arm · `for` body (each iteration a new variable) · `while` · `loop` · nested block · `defer`'d statement · async body across an `await` |
| **aliasing** | two closures, one variable · `&n` passed with the closure · a nested function writing it · a `ref` in the caller over it · a `for` over it while the closure reassigns it · the closure reassigning a variable whose view the caller holds |
| **ownership** | count taken (by value) / not taken (by reference) · inferred move after the closure's last call / before it (refused or counted) · `Drop` value captured, released after the closure · closure in a `Drop` guard's field · leak sweep clean |
| **concurrency** | `&sync Fn` · `@crossing` · `@scoped` written at the argument · `spawn` · executor queue · `block_on` in frame · awaited Task · Task answered |
| **build modes** | `@no_alloc` · `thumb-freestanding` (`sysl build-c … --target thumb-freestanding`) · `-O0` / `-O2` · a std function taking a closure (precompiled `.syslib` callee) · a package callee · ASan |
| **migration** | p03 (loop) · p05 (alias) · a callee switching from keeping to borrowing · the `val` refusal now accepting |

Cells skipped, with the reason: a function pointer or an `extern` handed a closure (refused at the
type, p12 — no mode to decide); a closure in a `const` function (closures are not compile-time values
today).
