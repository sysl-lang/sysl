# The 0.6.0 ownership release — design

**Status: decided (2026-10-23); every question below is answered.** Being built in the order of §7. Every code block marked
*proposed* is behaviour the compiler does not have; every unmarked block is sysl as it is in 0.5.0.
The numbers come from an inventory of dev at ab824ef2 (sysl 0.5.0): lines matching in `*.sysl`,
over `library/`, `compiler/` (tests counted apart), and the 131 org repos.

## What 0.6.0 is for

sysl's memory model is three modes — `T` a value, `&T` a counted reference, `*T` a raw pointer — and
it is pleasant where it is simple: values copy, references count, slices keep their buffer alive, and
a local array whose view escapes is quietly moved to the heap. Around that core, eight releases have
added a ring of attributes and rules that each close one hole: `@lends`, `@borrows`, `@scoped`,
`@crossing`, `@crossing(copy …)`, `@consumes`, `@relocates`, and the warning that a type with a `Drop`
must be made behind a `&T`.

0.6.0 replaces the ring with four rules the compiler applies by itself:

1. **Moves are inferred.** A value's last use hands it over instead of copying it.
2. **A borrowed pointer cannot escape.** A `*T` taken from a place is good for the call it is passed
   to, and the compiler — not an attribute — checks who keeps one.
3. **`Drop` runs on values.** A type with a destructor is a resource, and a resource has one owner.
4. **`&T` stays** the way to share, counted as today; `&sync T` stays the way to share across domains.

**The test every rule must pass: user code gets shorter or stays the same, and never gains an
annotation.** No lifetime syntax, no borrow checker. Where a rule here would need one, the rule is
wrong. Library authors may keep a small number of attributes for promises the compiler cannot see
(a scheduler's join); those are counted below.

## What the inventory changed

The direction written on 2026-10-22 said "views are second-class by default". Reading the reference
against the counts, that is the wrong target, and this note narrows it:

- **Slices are already safe and already first-class.** A slice carries an `owner` that keeps its
  buffer alive, and a view of a local array that escapes is promoted to the heap (`memory.md § Escape
  analysis`, `§ Promotion is silent, not hidden`); under `@no_alloc` that promotion is an error.
  Making slices second-class would turn working, pleasant code into errors — the opposite of the test.
  **Slices keep their current rules.**
- **The hole is `*T`.** `&place` yields a raw pointer that "can dangle and nothing promotes it"
  (`memory.md § Places`). Every `*self` method call on a local hands one out implicitly. `@lends`,
  `@borrows` and `@scoped` exist to patch exactly that hole, one site at a time.
- **The attributes are almost unused outside the compiler's own tests.** Org uses: `@scoped` 0,
  `@lends` 0, `@borrows` 2, `@crossing(copy …)` 0, `@consumes` 0, `@relocates` 0, `ref` 0. They are a
  cost to the language's surface and to its reference pages, not to its users' code. Removing them is
  cheap; making their protection automatic is the gain.
- **The real everyday costs are elsewhere:** `(*self)` receivers (1057 lines in the org, 60 in skiff),
  `&sync` (328), and the Drop-behind-`&T` rule (122 Drop types; 232 functions returning `&T` for one,
  0 returning the bare type, so the warning is obeyed everywhere and every one of those signatures is
  the trap's workaround).

## 1. Inferred moves

**Today** a value is copied on assignment and argument passing, a copy retains each `&T` field, and
"there is no move semantics and no use-after-move" (`memory.md § T — values`). That sentence is a
promise worth keeping, and this rule keeps it for every ordinary type.

**Proposed:** at a value's last use, the compiler hands it over instead of copying it. For a type
with no `Drop` this is invisible — the program means exactly what it meant — and it removes work:

```sysl
wrap(n: int) -> Option[&Node] = Some(Node(n))
```

emits today, after filling the box, `call @arc.copy.sysl$Option.ref.Node` and then
`call @arc.release` of the same box: a retain through the enum's copy glue and a release that cancel.
`arc_elide.sysl:elide_arc_pairs` misses it because the retain is glue, not `arc.retain`. With moves
inferred, a box handed to `Some` at its last use is simply handed over. The same holds for every
struct with `&T` fields passed at its last use, every `return local`, and every `Buf` header pushed
into a container.

- **No syntax.** There is no `move` keyword and no `consume` operator.
- **No new error for ordinary types.** Use after the "last use" is, by definition, not the last use.
- **`@pinned` vetoes it**: a pinned value is never relocated by an inferred move (15 org lines).
- `slot_take`/`slot_put` stay as the raw tier's explicit move through a pointer.

## 2. A borrowed pointer cannot escape — checked by the compiler, not declared

**Today** `&place` yields a `*T`, a `*T` is an ordinary type that can be stored anywhere, and the
only checks are local: returning the address of the frame's own storage (`escape.sysl`), and the body
of a `@lends` call letting its pointer out.

**Proposed:** the compiler works out, for every function, which of its `*T` parameters it **keeps**
— stores somewhere that outlives the call (a field, a global, a box, a closure, another keeping
call), or returns other than as a pointer derived from that parameter. That fact becomes part of the
function's signature as the compiler sees it, never as text the user writes. Then one rule at the
call site:

> **An address of storage that is not the program's to give away — a local, a parameter's place, a
> field of a value in this frame, a pointer lent to this function — may not be passed to a parameter
> that keeps it.**

What that buys, with no annotation anywhere:

- Every `*self` method is a checked borrow. `acct.deposit(5)` on a local `acct` is safe by
  construction, because `deposit` either keeps `self` (refused at that call) or does not.
- `@lends(body)` disappears: a body passed a `*T` that it then keeps is the same call-site rule.
  `Mutex.with` keeps working as written, minus the attribute.
- `@borrows(p)` disappears: a trait method's `*T` parameter is not kept, and an implementation that
  keeps it is refused at the `impl` — the promise becomes the default.
- A function returning a pointer into its argument stays legal and stays useful
  (`field(s: *S) -> *int = &s.x`); the result is lent for as long as the argument was.

*Proposed* — what a refused call reads like:

```sysl
struct Registry
    last: *int

    remember(*self, p: *int)
        self.last = p

run(r: *Registry)
    var n = 5
    r.remember(&n)
```

```error
'remember' keeps its parameter 'p' (it is stored in 'self.last'), and '&n' is the address of a local of 'run'
```

**Where the compiler cannot see a body**, it needs a default:

| boundary | default | why |
|---|---|---|
| a sysl function | inferred | it has a body |
| a trait method | not kept; an `impl` that keeps is refused | the `@borrows` promise, made the rule |
| a function pointer / closure type | not kept | a callback that keeps its argument is rare and visible |
| an `extern` C function | not kept | C's convention for a pointer argument; it was unchecked before, so nothing gets worse |
| an `extern` that does keep (a C library registering a context pointer) | `@keeps(p)` on the binding | the one place an annotation remains, in binding code, where today there is none and nothing is checked |

**`@scoped(f)` stays, for library authors.** It is the promise that a spawned closure is joined
before the call returns, and no compiler can see a scheduler's join. Org uses: 0; it will appear in
`sysl.threads.scope` and nowhere else.

**`@crossing(p)` becomes inferred** where the compiler can see the value go into another domain
(`spawn`, a channel `send`, a `@scoped` parameter), and stays as a library-author attribute at the
boundaries it cannot see (an extern queue, a C thread entry). Org uses: 41, most in freertos and zephyr
bindings — exactly those boundaries. `@crossing(copy value)` becomes the channel's own behaviour; its
4 library lines go.

**`ref`** is a named borrow of a place and already obeys "what may move underneath it"; under this
rule it is just a `*T` that cannot be kept, and its own section of the reference shrinks. Org uses: 0.

## 3. `Drop` on values: a resource has one owner

**Today** a destructor runs only when a `&T`'s count reaches zero. "A value type is copied, and a copy
is not a second resource … a program that puts one on a type it then passes by value gets no
destructor rather than a wrong one" (`memory.md § The four limits`). Since 0.4 a warning catches a
constructor that returns one by value. The result: 232 functions across the library and org return
`&T` only to make the destructor run, and a `Drop` type cannot be used at all without an allocator —
no lock guard, chip-select guard or DMA-buffer handle on a board.

Two ways to end the trap:

- **(a) A type with a `Drop` is a reference type.** Writing `Encoder(…)` makes a `&Encoder`; a bare
  `Encoder` cannot be held. Nothing new to learn, every one of the 232 signatures can drop its `&`,
  and "no use-after-move" survives untouched. It still needs an allocator, so a bare-metal guard stays
  impossible.
- **(b) A type with a `Drop` is a single-owner value.** It lives on the stack or inline like any value;
  assigning or passing it **moves** it (rule 1, now visible); its destructor runs when its owner's
  scope ends, on every path; it may still be put behind a `&T` to share it, which counts as today. A
  use after it was moved is the one new error — and it appears only for `Drop` types, which are
  exactly the types where "this was given away" is the true state of the world. This is Swift's
  non-copyable struct with a `deinit`, and Rust's `Drop`, without lifetimes.

**Decided: (a)** (user, 2026-10-23: needing an allocator is fine for a type with a destructor).

- **Constructing a `Drop` type makes a `&T`.** `Encoder(…)` and a constructor declared `-> Encoder`
  both yield a `&Encoder`; spelling a by-value `Encoder` (a binding, a field, a parameter, a `Buf`
  element) is refused with a fix-it to write `&Encoder`. The 0.4 warning becomes that error.
- **The 232 `-> &T` signatures keep compiling.** Dropping the `&` from them is an optional sweep.
- **No new error and no new rule:** a `&T` is shared and counted exactly as today, so there is no
  use-after-move. `@consumes` stays as it is.
- **A guard on a board without an allocator stays unavailable**; `defer` is the tool there.

## 4. `&T` and `&sync T` stay

Counting is the sharing model, and nothing here changes it: `&T` for one domain, `&sync T` (atomic) for
several, `weak T` to break cycles, a static `&T` laid down as data. Rule 1 removes counting traffic;
rule 2 removes the need to annotate around raw pointers; neither touches what a count means.

## 5. The traps, each mapped

| trap today | after 0.6.0 |
|---|---|
| a `Drop` type made by value never drops (warning since 0.4) | gone: rule 3 |
| `(self)` copies, so the address of `self` is not an answer | stays as a rule about addresses; rule 2 reports it at the call that keeps it |
| a `Buf` assigned is a second name for one buffer; a captured `Buf` is a copy | **open — Q7** |
| a local named `str`/`print`/`format` takes the special form away, silently | ordinary shadowing: a user name wins, the form is the fallback, and interpolation always reaches the form |
| a module `val` that does not fold shuts a package out of a bare target, and the host cannot see it | `sysl build`/`test` on the host checks folding for every target the manifest names (0.5.0 gives the manifest its targets) |
| `Some(rvalue)` retain/release pair | gone: rule 1 |

## 6. What changes for existing code

- **Behaviour changes:**
  - a `*T` passed to a function that keeps it, from a place the caller cannot give away, is refused
    (today it compiles and may dangle);
  - a `Drop` type spelled by value is refused; constructing one yields a `&T`;
  - a local named like a special form is refused.
- **Removed:** `@lends`, `@borrows`, `@crossing(copy …)` — removed outright; writing one
  is an unknown attribute.
- **Kept:** `@scoped`, `@crossing` at boundaries the compiler cannot see, `@disjoint` (an aliasing
  promise, not ownership), `@consumes`, `@no_alloc`, `@pinned`, `@thread_local`, `&sync`, `weak`, `slot_*`.
- **New:** `@keeps(p)` on an `extern` only.
- **Not in this release:** `package.hocon` refused (decided for 0.6.0 in the manifest note), so the
  org's ~115 manifests are renamed in a sweep before 0.6.0 ships.

## 7. How it gets built

One agent per item, in this order, each landing on dev with its tests and reference pages:

1. Inferred moves for copyable types (codegen only; no semantic change; the `Some` pair is its
   regression test).
2. The keeps-analysis: per-function summaries for `*T` parameters, the call-site rule, the boundary
   defaults, `@keeps`. `@lends`/`@borrows` removed.
3. `@crossing` inferred where visible; `@crossing(copy …)` folded into the channel.
4. `Drop` types are reference types (Q3): construction yields `&T`, a by-value spelling is refused.
5. The two small traps (special-form shadowing; host-side fold check).
6. Fewer global built-in names (user, 2026-10-23):
   - `va_*`, `ptr_cast`, `str_cast` and `str_alias` move behind an import.
   - `print`, `str`, `debug` and `format` stay built in, as fallbacks a user name overrides (item 5).
     They can't become library functions without specialisation: every value prints, through
     `Display`, else `Debug`, else its type name, and some types are shown by rule because no `impl`
     may be written for them.
7. The org sweep: `package.hocon` renamed, removed attributes deleted, optional `-> &T` cleanup.

## Questions for the user

- **Q1. Narrow rule 2 to `*T`, leaving slices first-class with promotion?** **Decided: yes** (see
  "What the inventory changed").
- **Q2. The defaults at an invisible boundary** (the table in §2): trait methods, function pointers and
  C externs default to "not kept", with `@keeps(p)` on a C binding that keeps. **Decided: as tabled.**
- **Q3. `Drop` types: (a) reference types, or (b) single-owner values?** **Decided: (a).**
- **Q4.** Withdrawn: it asked about (b)'s use-after-move error.
- **Q5. Removed attributes: refuse with a fix-it for 0.6.x, then unknown in 0.7.0?** **Decided: no —
  removed outright in 0.6.0** (an ordinary unknown attribute); the org sweep deletes the few uses.
- **Q6. `@scoped` and boundary `@crossing` stay as library-author attributes.** **Decided: yes**; no
  user code in the org writes either.
- **Q7. `Buf`'s aliasing copy.** Today `val b2 = b` makes a second name for one buffer, and pushing
  through one leaves the other's length behind. Options: (a) leave it, documented; (b) make `Buf`
  single-owner like a `Drop` type, so `val b2 = b` moves and a closure capture moves it in — which
  would break code that copies `Buf` headers today, by an amount not yet measured; (c) make a `Buf`
  copy a real copy (allocates). **Decided: measure (b)'s breakage across the org first**, and
  decide then; (a) until.
- **Q8. `Option[T]`'s zero value** (skiff's question (b)): a `var x: Option[T]` with no initializer is
  `None`. It touches value initialization rather than ownership, but it belongs to the same pass over
  what a value is. **Decided: yes, in 0.6.0.**
- **Q9. Fold the special-form shadowing error and the host-side fold check into 0.6.0?** **Decided:
  yes**; both are small and both are silent traps.
