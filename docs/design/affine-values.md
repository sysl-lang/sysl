# Unique values — `@unique` — design

**Status: proposed (2026-11-17). 0.11.13 ships U-1 only (§ 13): the attribute and the move check.
`impl Drop` on a `@unique` type is still refused, so no `@unique` value has a destructor yet (U-2).** Every `sysl` block below with an
`output` or `error` block under it is **proposed behaviour**: the compiler does not have it yet, and
this page sits in `docs/design/`, which the docs sweep does not run. Each block moves into a reference
page (`memory.md`, `attributes.md`, `declarations.md`) in the stage that makes it true (§ 13), and is
swept from then on. Blocks under **Today** are 0.11.11's real output; the probes that produced them
are `affine-values-probes/t*.sysl` (`zsh affine-values-probes/run.zsh`).

## Why

sysl adopts Rust's locking model (user, 2026-11-17): `Lock[T, B]` owns its data, `lock()` answers a
guard, dropping the guard unlocks, `CondVar.wait` takes the guard, and `LockedBy[T, Owner]` covers a
lock in another object. keel is the consumer, so a guard must work **with no allocator**. Two gaps
stop it:

1. **No value type can have a destructor.** A `Drop` type exists only as `&T` (`memory.md § A type
   with a destructor is a reference type`), so a guard costs a box and a count, and under `@no_alloc`
   it cannot be built at all.
2. **Nothing is move-only, and nothing ends a borrow taken through a value that is given up.** A guard
   copies, and a `ref` answered through the guard outlives the guard's release.

This reverses one half of `ownership-0.6.md` Q3, which chose "(a) a `Drop` type is a reference type"
over "(b) a single-owner value" because needing an allocator was then acceptable. It is not acceptable
for a kernel's lock guard. **(a) stays exactly as it is**; (b) is added beside it, opt-in, so nothing
expressible is lost and nothing that compiles today changes meaning.

## Decisions

| # | axis | decided | Rust | where sysl differs, and why |
|---|---|---|---|---|
| U1 | spelling | **`@unique` above a `struct` or `enum`** | every type is move-only unless `Copy` | sysl's default is copy, and a bare `Drop` type already means `&T` today, so the opt-in has to be written; an attribute, like `@pinned`/`@packed`, because it is a fact about how values of the type are held |
| U2 | what it means | a by-value use **moves**; a use after a move is refused; an `impl Drop` on it runs **on the value**, at the end of its owner's scope or when it is overwritten; no box, no count | same | — |
| U3 | `Drop` without `@unique` | **unchanged**: a reference type, built as `&T` | — | patch rule: existing code keeps its meaning |
| U4 | `@unique` without `Drop` | allowed: a move-only token | non-`Copy` struct | — |
| U5 | containment | a struct or enum the program declares holding a `@unique` value by value **must be marked `@unique` too**; a tuple, an array, and an instance of a generic type (`Option[Guard]`, `Buf[Guard]`) is unique **by what it holds**, with nothing to write | inferred everywhere | the `@pinned` rule: a declaration's reader sees that `val b = a` moves rather than copies |
| U6 | generics | a type parameter accepts a `@unique` type **by default**; the body is checked **per instance**, and a copy inside it is refused naming the instance and its caller | `T` is move-only; `T: Copy` to copy | no bound syntax: sysl already checks `Drop` and `@pinned` per instance (`drop_values.sysl`, `pinned.sysl`) |
| U7 | locals | `val b = a`, `f(a)`, `return a`, `Some(a)`, a field initialiser, an assignment's right side — each **moves** `a`; reading a field of a copyable type (`a.n`) **copies the field** and moves nothing | same | — |
| U8 | non-locals | a field reached through a `&T` or `*T`, an element, module storage and `*p` **cannot be moved out of**; `replace`, `swap`, `Option.take` and `slot_take` are the ways | same (`mem::replace`, `Option::take`) | — |
| U9 | partial moves | a field may be moved out of a **local** whose type has **no `Drop`**; the rest drop at its scope's end | same | — |
| U10 | conditional moves | a value moved on some paths is **maybe moved**: reading it is refused; at scope end it is dropped **only if still held**, by a one-byte flag in the frame where the paths disagree | drop flags | — |
| U11 | loops | a move of a value declared outside the loop is refused unless every path from it reassigns the value before the next turn or leaves the loop | same | — |
| U12 | assignment | `a = b` drops what `a` held (if held), then moves `b` in | same | — |
| U13 | parameters | a by-value parameter of a `@unique` type is **owned** by the callee, which drops it at its end unless it moves it on; `*T` and `ref` borrow | same | a `@unique` parameter is never lent: no borrowing entry is made for it (`owned_params.sysl`) |
| U14 | receivers | `(self)` on a `@unique` type is a **non-owning copy**, as a `Drop` type's `self` is today: readable, never stored, boxed, returned or passed on by value. **`@consumes` makes a member take its receiver**: the caller's local moves into the call, and the callee owns `self` | `self` takes, `&self` borrows | `(self)` already means "a copy for reading" in sysl, and `@consumes` already means "this call takes what its receiver holds" — both keep their meaning |
| U15 | `drop(self)` | handed the value; runs before its fields drop; may read them, may not move one out | `drop(&mut self)` | the existing `Drop` trait's shape is kept |
| U16 | drop order | locals in reverse declaration order, each `defer` in its place among them; fields and elements in declaration and index order; temporaries at the end of their statement; `val _ = x` holds `x` to the end of the block | same, except `let _ = x` drops at once | sysl already holds a `_` binding to block end (probe t09); the Rust footgun is not imported |
| U17 | `forget` | `sysl.mem.forget(x)` moves `x` and runs nothing | `mem::forget`, safe | — |
| U18 | traps | **a trap runs nothing**: no destructor, no `defer`. An `@abandons` call drops the caller's values first; a frame further down may not hold a `@unique` value with a `Drop` across a call that can reach one. Dropping a suspended task drops its frame's values | unwinding runs drops (`panic=abort` runs none) | sysl has no unwinding; a trap leaves the state a debugger wants |
| U19 | borrows | a `ref`, a view and a `&`-pointer **taken from a `@unique` value**, or **answered by a member called on one** (`-> ref T`, a view result), may not be used after that value is moved, assigned or dropped; checked from the **last use**, as `*T` already is | NLL borrow check | no lifetimes: the check is local to one body, as every ref check in sysl is |
| U20 | how far | **local flow, one body.** Only a local or a parameter is tracked; everything else cannot be moved out of (U8) | whole-program lifetimes | the reach of `consumes.sysl` and `counted_pointers.sysl` |
| U21 | closures | a **borrowed** closure (one that does not outlive the local) borrows a `@unique` capture; a **kept** closure (returned, stored, `async`, `&sync Fn`) **moves** it in, and the closure's box drops it; a closure body may not move a capture out | `move` closures, `FnOnce` | sysl's capture already follows keeping; there is no `FnOnce` (open question Q4) |
| U22 | `&T` of one | allowed: building `&Guard` moves the value into a box, and the destructor runs when the count reaches zero; `*r` cannot be moved out of | `Rc<T>` | — |
| U23 | C | a `@unique` type may not cross an `extern` or `@export` signature by value; `*T` crosses | allowed by value | C cannot honour "exactly once"; an `@export` entry may be called with the same bytes twice |
| U24 | raw tier | `slot_take`/`slot_put` move one; a reinterpreting cast may not produce one by value; `*p` read whole is refused | `ptr::read` | — |
| U25 | `@pinned` | `@pinned @unique` is a value that never moves and has a value destructor; `@pinned` alone with a `Drop` keeps today's refusal | `Pin` | — |
| U26 | `@no_alloc` | building, moving and dropping a `@unique` value allocates nothing; its destructor's body is reached and held to the rule | — | — |
| U27 | crossing | a `@unique` value crosses a domain **by move** wherever its fields may cross; `@unique` adds no crossing rule of its own | `Send` | whether a guard may cross is the lock's own design (it holds what makes it not `Send`) |

## Today, exactly

**A guard by value is refused** (t01):

```
error: 'take' hands back 'Guard' by value and 'Guard' has a 'Drop' — a type with a destructor is a reference type, and building one makes a '&Guard'. Write '&Guard' in its place
```

**A `Drop` type spelled bare is already a `&T`** (t04): `val g = Guard(1); val h = g` prints, and
`unlock 1` runs once, at the end, when the count reaches zero. This is why "a `Drop` struct that is not
boxed" cannot be the opt-in: that program would change meaning.

**Under `@no_alloc` it cannot be built at all** (t03):

```
error: a reference needs an allocator, and 'work' declared '@no_alloc' — it may hold and release storage made elsewhere, and may make none of its own
```

**A guard with no `Drop` copies, and a `ref` through it outlives its `@consumes` release** (t02):

```sysl
struct Guard
    data: *int
    held: bool

    value(*self) -> ref int = *self.data

    @consumes
    release(*self)
        self.held = false
        print("unlock")

main()
    var x = 1
    var g = Guard(&x, true)
    ref d = g.value()
    val g2 = g

    g.release()
    d = 5
    print(x, g2.held)
```

prints `unlock` then `5 true`: the write lands after the unlock, and `g2` is a second live guard.
The `ref` stands on the raw `*self.data`, which `memory.md § The one rule` leaves to the raw tier.

**What already exists and is reused:**

- `consumes.sysl` walks a body's paths, joining branches and turning loops to a fixpoint, and refuses
  a read of a local some path consumed (t05). The move check is this walk with more ways in.
- `pinned.sysl` lists every form that reads a value whole (the table in `attributes.md § @pinned`).
  Each is a move of a `@unique` local, or a refusal elsewhere.
- `drop_values.sysl` checks generic bodies once per instance and names the instance in a refusal.
- `counted_pointers.sysl` finds a pointer's last use on every path, `defer` and later loop turns
  included.
- `view_borrow.sysl` ties a view a member answers to the name it was called through (`@views(self)`).
- `emit_arc.sysl` keeps an owned-slot list per scope and releases it on every way out, in order
  with the `defer`s; `@abandons` and task cancellation already undo it.
- `val _ = x` already holds `x` to the end of its block (t09); a discarded statement value goes at the
  end of the statement (t10).
- `@unique` is an unknown annotation today (t07), and no struct in the org declared `@annotation` is
  named `unique`.

## 1. The spelling

```sysl
import sysl.sync.SpinLock

@unique
struct Held
    lock: *SpinLock

impl Drop for Held
    drop(self)
        self.lock.unlock()
        print("unlocked")

hold(l: *SpinLock) -> Held
    l.lock()
    Held(l)

var l = SpinLock(0)

main()
    val h = hold(&l)
    print("holding")
```

```output
holding
unlocked
```

**`@unique` says a value of the type has one owner.** It marks a `struct` or an `enum` beside
`@packed`, `@align(n)` and `@pinned`; above anything else it is refused. Weighed:

- **A keyword** (`unique struct`) — a new word in the grammar for one fact, where every other fact of
  this kind is an attribute.
- **"A `Drop` struct not written behind `&`"** — that is today's spelling of a `&T` (t04), so it
  would change what a compiling program means, and it leaves a move-only type with no destructor
  unspellable.
- **`@move`, `@affine`, `@nocopy`, `@owned`** — `@move` reads as if moves were new (every value
  already moves at its last use); `@affine` is jargon; `@nocopy` says what is missing, not what it is;
  `@owned` collides in sense with owned parameters. **`@unique` names the guarantee: one owner.** The
  word is the user's to settle (Q1).

## 2. Moves

**Every by-value use of a `@unique` value hands it over**, and the name it came from holds nothing
until it is assigned again:

```sysl
@unique
struct Token
    n: int

spend(t: Token) = print("spent", t.n)

main()
    val t = Token(1)
    val u = t

    spend(u)
    print(u.n)
```

```error
'u' is read here after it was moved into 'spend' on line 11 — 'Token' is '@unique', so a by-value use hands it over, and 'u' holds nothing until it is assigned again
```

The by-value uses are the ones `@pinned` lists: a binding, an assignment, an argument to a by-value
parameter, a result, a field or element of something built, a payload, a tuple, a capture by a kept
closure, a `match` or pattern that binds a part by value. **Reading a field of a copyable type copies
the field and moves nothing** (`t.n`, `t.name`). Passing to `*T` (`&t`, a `*self` method) and naming
with `ref` borrow.

**A value that is not a local cannot be moved out of**, since whatever holds it would still hold it:

```sysl
@unique
struct Token
    n: int

@unique
struct Wallet
    t: Token

take(w: *Wallet) -> Token = w.t
```

```error
this moves 'w.t' out of what 'w' points at, and 'Token' is '@unique' — only a local or a parameter can be moved out of, since whatever holds this would still hold it. Take it with 'replace(&w.t, …)', or name it with a 'ref'
```

`sysl.mem` gives the three ways, each a move through a pointer: `replace(p: *T, v: T) -> T`,
`swap(a: *T, b: *T)`, `forget(x: T)`; and `Option.take(*self) -> Option[T]` leaves `None` behind.

**Assignment drops what was there**, after the right side is evaluated and before it lands:

```sysl
@unique
struct Res
    n: int

impl Drop for Res
    drop(self) = print("drop", self.n)

main()
    var r = Res(1)

    r = Res(2)
    print("swapped")
```

```output
drop 1
swapped
drop 2
```

**A move on some paths leaves the value maybe-moved**: reading it is refused, and at the scope's end
it is dropped only if it is still held. Where paths disagree the frame carries a one-byte flag; where
they agree no flag is made:

```sysl
@unique
struct Res
    n: int

impl Drop for Res
    drop(self) = print("drop", self.n)

keep(r: Res) = print("kept", r.n)

run(c: bool)
    val r = Res(1)

    if c then keep(r)
    print("after")

main()
    run(true)
    run(false)
```

```output
kept 1
drop 1
after
after
drop 1
```

**A loop is followed round**, so a move of an outer value on one turn and a read on the next is
refused — unless the path reassigns it or leaves the loop:

```sysl
@unique
struct Res
    n: int

keep(r: Res) = print("kept", r.n)

main()
    var r = Res(0)

    for i in 0..<3
        keep(r)
```

```error
'r' is moved here on one turn of the loop and would be moved again on the next — 'Res' is '@unique'. Assign 'r' a new value before the turn ends, or leave the loop after the move
```

**A field may be moved out of a local whose type has no `Drop`**; the fields left behind drop at the
local's scope end, and the local itself may not be read whole again. Out of a type with a `Drop` it
is refused, since its destructor is handed the whole value. **`match` and patterns** move each part
they bind by value out of a local scrutinee; an arm binding nothing `@unique` moves nothing, and the
parts a moving arm does not bind drop at that arm's end.

## 3. Receivers and `@consumes`

**`(self)` on a `@unique` type is a copy that owns nothing**, exactly as a `Drop` type's `self` is
today: the method reads it, calls other `(self)` methods on it, and may not store, box, return or pass
it on by value. Calling one moves nothing. **`@consumes` makes a member take its receiver**: the
caller's local is moved into the call, and the callee owns `self` and drops it at its end unless it
moves it on. On a `@unique` type `@consumes` may also take arguments (today it may not, on any type,
and that stays so for a type that is not `@unique`).

```sysl
@unique
struct Res
    n: int

    peek(self) -> int = self.n

    @consumes
    close(self, why: string) = print("closing", self.n, why)

impl Drop for Res
    drop(self) = print("drop", self.n)

main()
    val r = Res(1)

    print(r.peek())
    r.close("done")
    print(r.peek())
```

```error
'r' is read here after 'r.close()' took it
```

Rust spells these `&self` and `self`. sysl keeps `(self)` as the reading receiver it already is, and
`@consumes` as the "takes everything its receiver holds" it already is; a guard type writes no
receiver it would not write today.

**`drop(self)`** is handed the value, runs before the value's fields are dropped, may read every
field, and may not move one out (Rust's `drop(&mut self)` cannot either). Its own locals drop at the
end of its body, still before the fields — today's order.

## 4. Containment and generics

**A declaration holding a `@unique` value by value says so:**

```sysl
@unique
struct Guard
    n: int

struct Holder
    g: Guard
```

```error
field 'g' of 'Holder' holds 'Guard' by value, and 'Guard' is '@unique', so 'Holder' would be moved rather than copied too. Mark 'Holder' '@unique', or hold a '&Guard' or a '*Guard'
```

**A tuple, a fixed array and an instance of a generic type are unique by what they hold**, with
nothing to write: `(Guard, int)`, `[4]Guard`, `Option[Guard]`, `Result[Guard, string]`,
`Buf[Guard]`. Building `[Guard(1); 4]` copies one value four times and is refused; an array literal of
four built values is not.

**A type parameter accepts a `@unique` type**, and each instance's body is checked at the types it
was made at — the way `drop_values.sysl` checks a `Drop` type and `pinned.sysl` a pinned one:

```sysl
@unique
struct Guard
    n: int

twice[T](x: T) -> (T, T) = (x, x)

main()
    val p = twice(Guard(1))
```

```error
'x' is read here after it was moved into the tuple — 'Guard' is '@unique' (in 'twice[Guard]', called from
```

Rust asks for `T: Copy` at the declaration. sysl asks at the instance, as it already does, so no
bound is written and no library signature changes; only the members a program calls are checked.
`Buf[Guard]` works through `push`, `pop`, `ref e = b[i]`, `len` and its teardown (which drops the live
elements in index order); a member that copies an element (`b[i]` read whole, `fill`, iterating
by value) is refused at the instance.

**An `&T` of a `@unique` type is a counted box**: building `&Guard` moves the value in, the
destructor runs when the count reaches zero, and `*r` cannot be moved out of. So a guard that must be
shared is shared exactly as today's `Drop` types are.

## 5. Closures

**A closure follows keeping, as today** (`memory.md § Capture follows keeping`). One that does not
outlive the local refers to a `@unique` capture and moves nothing; one that is kept — returned, stored,
handed to a keeping parameter, built in an `async` body, erased into a `&sync Fn` — **moves** the
capture in, and the local is moved from there on. The closure's box drops it. **A closure body may not
move a capture out**, since a closure may be called more than once:

```sysl
@unique
struct Res
    n: int

keep(r: Res) = print("kept", r.n)

main()
    val r = Res(1)
    val f = () -> keep(r)

    f()
```

```error
this closure moves 'r' out of what it captured, and a closure may be called more than once
```

A `&sync Fn` capture is held to the crossing rules by its fields (U27).

## 6. Drop order, `forget`

Rust's, and today's: **locals in reverse declaration order** at the end of their block, each `defer`
in its place among them, on every way out; **a moved-out local is skipped**; **fields in declaration
order, elements in index order**, depth first; **temporaries at the end of their statement**; **`val _ =
x` holds `x` to the end of the block** (Rust drops it at once — the footgun is not imported).

```sysl
@unique
struct Res
    name: string

impl Drop for Res
    drop(self) = print("drop", self.name)

main()
    val a = Res("a")
    defer print("deferred")
    val b = Res("b")
    val c = Res("c")
    val keep = b

    print("end")
```

```output
end
drop b
drop c
deferred
drop a
```

(`keep` was declared last, so it goes first; `b` was moved into it and is skipped.)

**`forget(x)` moves `x` and runs nothing** — no destructor, no field drops. Like Rust's, it is safe:
the worst it does is leak.

## 7. Traps, `@abandons` and cancellation

- **A trap runs nothing** — no destructor and no `defer`, for a `@unique` value as for everything
  else. There is no unwinding; a lock a trapping core held stays held, and nothing runs after it that
  could wait on it. A kernel that recovers from a fault in one task does so outside the language.
- **An `@abandons` call ends its caller's frame first**, so the caller's `@unique` values are dropped
  before it; a frame further down may not hold one with a `Drop` across a call that can reach one —
  the existing rule's list of what a frame may not hold gains it.
- **Dropping a suspended task drops the `@unique` values its frame still holds**, flags read at the
  suspension point, in the order a `return` there would. A frame never moves, so a value held across
  an `await` stays where it is.
- **A fiber that is never resumed** drops nothing on its stack, as for every other value today.

## 8. Borrows a move or a drop ends

**A borrow taken from a `@unique` value may not be used after the value is moved, assigned or
dropped.** A borrow is a `ref`, a view, or a pointer made by `&` — taken into the value's own storage,
**or answered by a member called on it**: `-> ref T` and a view result (`[]T`, `[]const T`, `string`)
are tied to the receiver whatever path the body took, raw pointer or not, as `@views(self)` ties a
view today. A member answering a `*T` is the raw tier's, and is not tied.

```sysl
import sysl.sync.SpinLock

@unique
struct Guard
    lock: *SpinLock
    data: *int

    value(self) -> ref int = *self.data

    @consumes
    release(self) = print("release")

impl Drop for Guard
    drop(self) = self.lock.unlock()

var l = SpinLock(0)
var x = 1

main()
    l.lock()

    val g = Guard(&l, &x)
    ref d = g.value()

    d += 1
    g.release()
    d = 5
```

```error
'd' is a 'ref' borrowed from 'g', and this call moves 'g' — 'd' is used after it, on line 27
```

**It is asked from the last use**, as a `*T`'s liveness already is (`counted_pointers.sysl`): `d += 1`
before the release is fine, and so is the release when nothing reads `d` after it. A use on a later
loop turn or in a `defer` counts. Rust's NLL asks the same question; sysl asks it of one body and needs
no lifetime in any signature, because a `ref` never leaves its body and a tied view is held to the four
`@views(self)` rules. The existing `ref` hazards are unchanged: a `ref` into an inner field of a
`@unique` local is also a borrow of it, since the local becomes an **owning step** (the one-rule table
gains a row: a `@unique` value — overwriting it runs its destructor, moving it gives the storage away).

## 9. The C boundary and the raw tier

- **A `@unique` type does not cross an `extern` or `@export` signature by value**, in a parameter or
  a result, at any depth. C cannot give back a value exactly once, and an `@export` entry can be
  called with the same bytes twice. A `*Guard` crosses.

```sysl
@unique
struct Guard
    n: int

extern "hold" c_hold(g: Guard)
```

```error
'Guard' is '@unique', and 'c_hold' is an 'extern' — C cannot be handed a value it must give back exactly once. Pass a '*Guard'
```

- **`slot_take(p)` and `slot_put(p, v)` move one** through a raw pointer, the caller owing what they
  owe today. `*p` read whole is a move out of what a pointer reaches, and is refused (U8).
- **A reinterpreting cast may not produce a `@unique` value by value**, since it would mint a second
  owner; a pointer cast is the raw tier's bargain, as today.

## 10. Interactions

- **`@consumes` on a type that is not `@unique`** is exactly today's (receiver only, no arguments).
- **`ref`**: a declaration, block-bound, never a type — all unchanged; § 8 adds the move hazard.
- **`Frozen[T]`**: `freeze` of a `Buf[Guard]` moves the elements into the frozen buffer; they drop
  with its last share; an element read whole is refused (U8).
- **`&sync T`**: a `&sync Guard` box is allowed where `Guard` may be shared; it drops on whichever
  thread lets go of the last share.
- **`@scoped`**: a scoped closure borrows a `@unique` capture, and moving it while the scope is open is
  refused, as moving any borrowed origin is.
- **`@pinned`**: `@pinned @unique` is a value with a value destructor that never moves (it lands once
  and is dropped where it lives); `@pinned` with a `Drop` and no `@unique` keeps today's refusal (t06).
- **`@no_alloc`**: a `@unique` value allocates nothing to build, move or drop. Its destructor's body
  is reached from every point that drops one, and held to the rule there.
- **Module storage**: a module `var` of a `@unique` type is allowed, is never dropped (there is no exit
  pass, as today), is assigned (dropping what it held) and is `replace`d; it is never moved out of.
- **async**: an `async` function's `@unique` parameter is moved into the task, which owns it.

## 11. The lock, written with it

This is the shape the lock item builds; the names are the lock design's.

```sysl
import sysl.sync.SpinLock

struct Lock[T]
    raw: SpinLock
    value: T

    lock(*self) -> Guard[T]
        self.raw.lock()
        Guard(&self.raw, &self.value)

@unique
struct Guard[T]
    raw: *SpinLock
    data: *T

    get(self) -> ref T = *self.data

impl[T] Drop for Guard[T]
    drop(self) = self.raw.unlock()

var counter = Lock(SpinLock(0), 0)

@no_alloc
bump() -> int
    val g = counter.lock()
    ref n = g.get()

    n += 1
    n

main()
    print(bump(), bump())
```

```output
1 2
```

Everything in it but `@unique` and the `Drop` compiles on 0.11.11 (probe t12, which unlocks by hand
and prints `1 2`). `get` takes `(self)` so that a `val` guard can answer the place; a `*self` member
would ask for `var g`.

`CondVar.wait(g: Guard[T]) -> Guard[T]` takes the guard by value, unlocks through `g.raw`, waits,
relocks, and hands the same guard back; `g = cv.wait(g)` is a move out and a move in, and any `ref`
taken from the old `g` is refused after it.

## 12. What changes for a program that compiles today

**Nothing that compiles today changes meaning.** Each change below only accepts what was refused, or
adds a name behind an import:

| change | could a compiling program be affected? | notes line |
|---|---|---|
| `@unique` becomes a built-in attribute word | **yes, one case**: a program declaring its own `@annotation struct unique` is now refused (a struct declared `@annotation` may not take a built-in word). None in the org | BEHAVIOUR CHANGE: "`unique` is now a built-in attribute; an annotation struct of that name must be renamed" |
| `impl Drop` on a `@unique` type runs on the value | no — `@unique` did not compile | feature line under the fix |
| `@consumes` with arguments on a `@unique` member | no — widening, only on `@unique` types | — |
| the move, borrow, containment and C checks | no — they fire only where a `@unique` type is reached | — |
| `sysl.mem` (`replace`, `swap`, `forget`) | no — a new module, reached only by import | new module line |
| `Option.take(*self)` | **one case**: a program whose own trait adds a member `take` to `Option` is now refused ("a trait member may not collide with an inherent one"). None found in the org; the stage greps again | BEHAVIOUR CHANGE if kept; or ship it as `sysl.mem.take(o: *Option[T])` and change nothing (Q5) |
| drop glue for value types in `Buf`/`Option`/arrays | no — generated only for `@unique` element types | — |
| a `Drop` type without `@unique` | no — unchanged, same diagnostic text | — |

The release notes owe one more sentence, since it is filed as a bug fix: **"A type with a destructor
can now be a value: `@unique` (memory.md § Unique values)."**

## 13. Implementation — release-0.11 patch items

Each item is one agent, branches from `origin/release-0.11`, lands on it alone, and leaves the
compiler sound at its landing: a stage that cannot yet honour a form **refuses it** with a diagnostic
naming the form, so nothing half-built is accepted. `compiler/` never uses `@unique` (it is built by
the installed 0.11.x); only `library/` and user code do, and `library/` uses it only from the item
that needs it. Test prefixes are fresh (`unq_`, `udr_`, `ucn_`, `ubr_`, `ucl_`, `upm_`).

1. **U-1 — the attribute and the move check, no destructor yet.** `parse_attr.sysl`
   (`builtin_attr_word`, the struct/enum attribute list), `ast_stmts.sysl` (`Unique` beside `Pinned`),
   `type_attr.sysl`, `type_queries.sysl` (`is_unique(t)`: marked, or a tuple/array/generic instance
   holding one), the containment refusal (modelled on `pinned.sysl`'s "says so"), and a new
   `unique_moves.sysl` — `consumes.sysl`'s path walk with every by-value form from `pinned.sysl`'s list
   as a move, maybe-moved joins, loops to a fixpoint, the U8 refusal for non-locals, `(self)` as a
   non-owning copy, `@consumes` receivers (widening `consumes.sysl`'s argument rule for `@unique`).
   `impl Drop` on a `@unique` type is still refused here ("not yet" is not written: today's message
   stands). Pages: none yet. Tests `tests_unq_unique_moves.sysl`.
2. **U-2 — destructors on values.** `drop_values.sysl` exempts `@unique` types; `emit_arc.sysl`
   (`destructor_of`, `walk_at`/`release_at`: drop glue for a value — call `drop`, then the fields),
   `own_slot`/`release_slots` for `@unique` locals and parameters, drop flags (a frame byte per
   maybe-moved local, from U-1's walk; `emit_stmt.sysl`, `emit_flow.sysl`), assignment drops first
   (`expr_assign.sysl`/`emit_place.sysl`), temporaries (`own_temp`), `owned_params.sysl` (a `@unique`
   parameter is always owned, no borrowing entry), `return_slot.sysl`'s `runs_code`,
   `emit_moves.sysl` (a `@unique` local's moves are not optional), `@abandons` and task cancellation,
   `no_alloc.sysl` (no allocation; the destructor reached). Tests `tests_udr_unique_drop.sysl`, with
   ASan and `SYSL_COUNT_ALLOCS`.
3. **U-3 — containers, generics, patterns.** Per-instance body checks (`drop_values.sysl`'s instance
   machinery, `expr_generic.sysl`), `&T` boxing of a `@unique` value (`held_by_reference`'s cousin;
   box dispose runs the drop), `Buf` element teardown and `slot_vacate` running value drops,
   `Option`/`Result`/tuple/array glue, `match`/pattern moves (`pattern.sysl`, `emit_pattern.sysl`),
   `[v; n]` refused. Library: `library/sysl/mem/` (`replace`, `swap`, `forget`), `Option.take` per
   Q5, and an audit of `Buf`/`Option`/`Result` members a guard program calls. Tests
   `tests_ucn_unique_containers.sysl` and library tests.
4. **U-4 — borrows ended by a move.** `place.sysl`/`ref_result.sysl` (a `@unique` local is an owning
   step; a place answered by a member of a `@unique` receiver is borrowed from it), `checks.sysl`'s
   ref hazards, `counted_pointers.sysl`'s last-use walk extended to refs and tied views,
   `view_borrow.sysl` (a view result of a `@unique` receiver is tied without `@views(self)`). Tests
   `tests_ubr_unique_borrows.sysl`.
5. **U-5 — closures, C, the raw tier, crossing.** `closure_mode.sysl`/`closure.sysl` (borrowed vs
   kept capture moves; no move out of a capture), `cabi.sysl`/`emit_foreign.sysl`/`emit_export.sysl`
   (by-value refusal), the reinterpreting-cast refusal, `sharing.sysl` (crossing by move), `@scoped`
   origins. Tests `tests_ucl_unique_closures.sysl`.
6. **U-6 — partial moves.** Per-field move state in `unique_moves.sysl`, per-field drop at scope end,
   the refusal on a `Drop` type. Tests `tests_upm_unique_partial.sysl`. (The lock does not need it; it
   may ship last or in a later patch.)
7. **U-7 — the pages.** `memory.md § Unique values` (this page's §§ 1–10 as claims, every block live),
   `attributes.md § @unique`, `declarations.md` (a pointer from § A struct with a destructor),
   `library/` pages for `sysl.mem`; this design page marked done. Docs sweep of those pages. Each of
   U-1…U-6 moves its own blocks over as it lands if the director prefers; U-7 then only links.

The lock item (`Lock`, `Guard`, `CondVar`, `LockedBy`) follows U-4 (it needs U-1, U-2, U-4; U-3 for
`Option[Guard]` from `try_lock`).

## Open questions for the user

- **Q1. The word.** `@unique` (recommended: it names the guarantee), or `@move` / `@affine` /
  `@nocopy`.
- **Q2. Containment: marked or inferred?** Recommended **marked** for declared structs and enums
  (the `@pinned` precedent: `val b = a` changing from copy to move is visible at the declaration),
  inferred for tuples, arrays and generic instances.
- **Q3. Borrow liveness: last use or block?** Recommended **last use** (§ 8), as `*T` already is;
  block-scoped would refuse `cv.wait(g)` while any `ref` from `g` is still in scope, which is the
  kernel's everyday shape.
- **Q4. A call-once closure?** Recommended **not now**: a closure may not move a capture out. A
  `once` closure (Rust's `FnOnce`) would be a design of its own.
- **Q5. `Option.take`**: as a member (pleasant, one theoretical refusal: a user trait adding `take` to
  `Option`) — recommended, after a grep of the org — or as `sysl.mem.take(*Option[T])` (no change at
  all).
- **Q6. Partial moves (U-6) in 0.11.13, or later?** Recommended later: the lock needs none.
