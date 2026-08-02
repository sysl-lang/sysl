---
title: sysl.sync
summary: Atomics, the five memory orderings, and a spinlock — the concurrency a target has before it has a scheduler.
weight: 70
---

`sysl.sync` is two types and five names, and the most important thing about it is what it does
**not** require. There is no `requires` clause on the module at all, so a program that has given up
its allocator and its operating system can still reach every name in it:

```sysl
no alloc
no os

import sysl.sync.*

var hits = Atomic(0i32)
var guard = SpinLock(0i32)

guard.lock()
hits.add(1i32)
guard.unlock()

print(hits.load(), guard.held)
```

```output
1 0
```

That is the whole reason this module exists apart from [`sysl.thread`](/library/thread/). A word the
processor can touch indivisibly is something a bare machine has; a *thread* is not, because creating
one needs a scheduler underneath. A module's capability requirement is module-wide, so putting one
type that needed `threads` in here would have taken `Atomic[T]` out of reach of the allocator, the
scheduler, and the interrupt handler — the three pieces of code that need a lock before there is
anything to schedule.

| name | what it is |
|---|---|
| `Ordering` | the five C11 orderings — `Relaxed`, `Acquire`, `Release`, `AcqRel`, `SeqCst` |
| `Atomic[T]` | one word, and the nine operations that touch it indivisibly |
| `SpinLock` | mutual exclusion held by spinning rather than by sleeping |

This is the `*T` tier of concurrency in the same sense `*T` is the unsafe tier of memory: nothing
here is checked, everything is greppable, and it is how a kernel is written. What sits above it —
`Mutex[T]`, `spawn`, and the crossing rule — is on the [`sysl.thread`](/library/thread/) page.

## `Ordering`

An ordering is **not a property of the value**. Every ordering reads the same bits; what differs is
the promise about what *else* is guaranteed to have happened around the read. That is why it is an
argument rather than something fixed on the variable: the same word is read with `Relaxed` in a
statistics counter and with `Acquire` in the handoff that publishes a structure, and only the
operation knows which it is.

**These are C11's and LLVM's, named the same way.** Nothing here is sysl's invention, so the
standards text and the machine documentation a reader already has apply unchanged.

| ordering | what it promises |
|---|---|
| `Relaxed` | nothing but indivisibility — correct for a counter read once at the end |
| `Acquire` | everything the releasing thread wrote before its release is visible here afterwards |
| `Release` | everything written before this becomes visible to whoever acquires it |
| `AcqRel` | both, for a read-modify-write that is a handoff in each direction at once |
| `SeqCst` | every thread agrees on one order for all `SeqCst` operations in the program |

`SeqCst` is the strongest and the slowest, and it is what every method here defaults to, because it
is the one that makes an ordinary program behave the way its author read it.

### A load and a store take three of the five

A release publishes the writes that came before it, and **a load makes none**; an acquire sees what
a release published, and **a store reads nothing**. So `Release` and `AcqRel` name loads that do not
exist, `Acquire` and `AcqRel` name stores that do not exist, and no machine has an instruction for
any of them. `Ordering` answers the question directly, which is what lets a wrapper taking an
ordering as a *value* check it:

```sysl
no alloc
no os

import sysl.sync.*

print(SeqCst.orders_a_load(), Release.orders_a_load(), AcqRel.orders_a_load())
print(SeqCst.orders_a_store(), Acquire.orders_a_store(), AcqRel.orders_a_store())
```

```output
true false false
true false false
```

Read the two rows against each other: `SeqCst` is in both, and it is worth being clear that this is
not a special case. It is stronger than an acquire rather than a release in disguise, and stronger
than a release rather than an acquire in disguise, so it orders either operation. `AcqRel` is in
neither, because it is exactly the ordering that asks for both halves at once.

This is a fact about what the operations *are*, not about any particular machine, and **a stronger
processor would not lift it**.

## The ordering is written at the call

Below `Atomic[T]` sit nine forms in the language's raw tier, beside `sizeof` and `ptr_cast`. Each is
one machine instruction that no sysl body could have written, and each takes an **address**:

```
atomic_load(p, ord)                  atomic_swap(p, v, ord)
atomic_store(p, v, ord)              atomic_cas(p, expected, desired, ord)
atomic_add / _sub / _and / _or / _xor(p, v, ord)
atomic_fence(ord)
```

The ordering on one of these has to be **one of `Ordering`'s names, spelled there**, because it
becomes a keyword in the emitted instruction rather than a value the instruction reads. An ordering
held in a variable is well-typed sysl that cannot be lowered:

```sysl
import sysl.sync.*

var q = Atomic(0i32)
var ord = Acquire

print(atomic_load(&q.v, ord))
```

```error
'atomic_load' spells its ordering into the instruction, so it has to be one of Ordering's names written here — not a value carrying one. Where a caller chooses, branch on their choice and write a call per ordering
```

The last clause is the whole implementation strategy of the module above: each method on `Atomic[T]`
is a match from the ordering it was *given* to the ordering it *writes*, one arm per name. That is
also what `core::sync::atomic` does in Rust, for the same reason.

**And it costs nothing.** At every ordinary call the scrutinee is a constant, so the match folds
away and the method call becomes the single instruction it names. Measured on AArch64 at `-O1`:
`a.add(1)` is `ldaddal`, `a.add(1, Relaxed)` is `ldadd`, `a.load(Acquire)` is `ldapr`, and
`a.store(v, Release)` is `stlr`.

### The module is the gate

The five names live in `sysl.sync` and nowhere else, and none of the nine forms accepts a name a
program declared for itself. So a program that never imported the module has **no ordering it can
write**, and the raw tier is closed to it by the ordinary rules about names rather than by a rule of
its own. Importing the type is not importing the names:

```sysl
import sysl.sync.Atomic

var a = Atomic(0i32)

print(a.load(Relaxed))
```

```error
undefined name 'Relaxed'
```

`import sysl.sync.*` is therefore the ordinary way to reach this module — there are seven names in
it, five of them are orderings, and a program using the sixth almost always wants some of the five.

### A fence has no wrapper, and the omission is deliberate

`atomic_fence(ord)` **is** the fence. It is a barrier the whole thread passes through rather than an
operation on any one location, so there is no value for it to be a member of and nothing for a
struct to hold. What a free function beside it could add is the default — and the default is what it
could not survive, because `Relaxed` is refused:

```sysl
import sysl.sync.*

atomic_fence(Relaxed)
```

```error
a fence is nothing but its ordering, so 'Relaxed' would ask for a barrier that orders nothing — write 'Acquire', 'Release', 'AcqRel' or 'SeqCst', or drop the fence
```

A wrapper taking an `Ordering` would need a `Relaxed` arm, and the only two things that arm could do
are call a form that refuses it or quietly do nothing. Softening that diagnostic is worse than
writing the form out, so the form is what a program writes.

## `Atomic[T]`

`Atomic[T]` is an **ordinary struct with one field**, and its methods take the address of that field
and hand it to the forms above. There is nothing else to it, and that is the point: the type a
program reaches for lives in the library where a reader can open it, rather than inside the compiler
where they cannot.

```sysl
no alloc
no os

import sysl.sync.*

var a = Atomic(0i32)

a.store(7i32)

var was = a.swap(9i32)

print(was, a.load())
```

```output
7 9
```

**Every method takes a `*self` receiver, including `load`** — and that is worth stating, because a
read looks like it should not need one. A `self` receiver is handed a **copy** of the struct, and
the address of a copy is not the address the other threads are writing to. It would compile, it
would be atomic, and it would read the wrong word.

### Every read-modify-write answers what was there before

That is the property, and it is what makes an atomic increment usable as a **ticket**: every caller
gets a different number and none of them is skipped.

```sysl
no alloc
no os

import sysl.sync.*

var next = Atomic(0i32)
var t1 = next.add(1i32)
var t2 = next.add(1i32)
var t3 = next.add(1i32)

print(t1, t2, t3, next.load())
```

```output
0 1 2 3
```

The same shape covers the bitwise members. `or` is how a flag is set in a word other threads are
setting their own flags in, and the answer tells the caller whether it was the one that set it:

```sysl
no alloc
no os

import sysl.sync.*

var flags = Atomic(0b1100u8)
var o = flags.or(0b0011u8)
var n = flags.and(0b0110u8)
var x = flags.xor(0b1111u8)

print(o, n, x, flags.load())
```

```output
12 15 6 9
```

### `cas` answers the value it found

Not a `bool`, and not an `Option`. A caller learns whether it swapped by comparing the answer
against what it expected — one comparison it was going to make anyway — and **on failure the answer
is the value to retry against**, so the whole retry loop is one line:

```sysl
no alloc
no os

import sysl.sync.*

var slot = Atomic(1i32)
var seen = slot.load()

while slot.cas(seen, seen * 10i32) != seen
    seen = slot.load()

print(seen, slot.load())
```

```output
1 10
```

Nothing else is running here, so the exchange succeeded the first time round and the body never
ran — which is the ordinary case, and the reason the loop is worth writing this way rather than as a
`bool` plus a second load.

### Naming another ordering

An ordering on the surface is a parameter with a default, and this is the one place in the design
where the ordering is not written at the raw call — it is written at *this* call, one level up:

```sysl
no alloc
no os

import sysl.sync.*

var stats = Atomic(0i32)

for i in 0..<5
    stats.add(1i32, Relaxed)

print(stats.load(Acquire))
```

```output
5
```

### What `T` may be

**An integer of 8, 16, 32 or 64 bits, or a pointer** — what the machine has an instruction for.
Nothing in the declaration says so, and there is no bound that could: sysl's integers are an
[open family](/reference/generics/), so `u12` is a type a program may name and no `impl` list could
have covered it. What refuses the type is the **form inside**, where the instruction would have had
to be chosen:

```sysl
import sysl.sync.*

var wide = Atomic(0u12)

print(wide.load())
```

```error
'atomic_load' is one machine instruction, and a machine has one for 8, 16, 32 and 64 bits — u12 is 12, so there is nothing to emit
```

An aggregate is refused for a different reason and with a different message, because it is a
different mistake — there is no width to round to, and what the author wanted was a lock:

```sysl
import sysl.sync.*

struct Point
    x: i32
    y: i32
end Point

var here = Atomic(Point(1i32, 2i32))

print(here.load().x)
```

```error
'atomic_load' reaches a word the machine can touch indivisibly — an integer of 8, 16, 32 or 64 bits, or a pointer — and Point is neither
```

A float is in the same position, and it is the one people are most surprised by — a `real` is 64
bits wide and there is still no instruction that loads one atomically as a float:

```sysl
import sysl.sync.*

var f = Atomic(1.5)

print(f.load())
```

```error
'atomic_load' reaches a word the machine can touch indivisibly — an integer of 8, 16, 32 or 64 bits, or a pointer — and real is neither. An aggregate is what a 'SpinLock' or a '&sync Mutex[T]' is for (`06`)
```

**The arithmetic members are refused on a pointer**, and for a third reason again: an address plus a
number is a question the raw tier does not answer.

```sysl
import sysl.sync.*

var raw: *u8 = null
var ap = Atomic(raw)
var sum = ap.add(raw)

print(sum == null)
```

```error
'atomic_add' is arithmetic, and what an address plus a number means is the question the raw tier does not answer — use 'atomic_swap' or 'atomic_cas' to change a pointer, or do the arithmetic on a 'usize' beside it
```

**Read where those four diagnostics point.** The caret is on a line in `lib/sysl/sync/atomic.sysl`,
not on the line the program wrote — because the form that refuses is inside the method, and the
method is ordinary library sysl like everything else here. It is the honest place for it to land,
and it is also the clearest demonstration on this page that `Atomic[T]` really is a struct somebody
wrote rather than a type the compiler knows about.

### The narrowing on `load` and `store` lands at run time

This is the one check in the module that does, and the reason is exactly the boundary the module
sits on. The **form** refuses a releasing load where the name is written, and it cannot see a name
that arrived in a variable — which is precisely what a method taking an `Ordering` hands it. So
`load` and `store` carry a `require` over the two predicates above.

```sysl
import sysl.sync.*

var a = Atomic(0i32)

print(a.load(Release))
```

That compiles, and the process **traps** when it runs: the contract clause lowers to a trap
instruction, so there is no message and no unwinding, the shell reports signal 5, and anything still
sitting in the output buffer never reaches the terminal. It folds away entirely wherever the
ordering was written at the call, which is every ordinary use.

What the check buys is that an ordering that cannot be honoured **stops the program** rather than
being quietly promoted to `SeqCst`. Promotion would be sound — it is strictly stronger — and it is
not what the author asked for, which is the more useful thing to find out.

### Reading the field directly

`Atomic[T].v` is **not hidden**, and that is deliberate rather than an oversight:

```sysl
no alloc
no os

import sysl.sync.*

var done = Atomic(0i32)

done.store(1i32, Release)

print(done.v)
```

```output
1
```

A thread that knows it is alone with the value — the one that built it, or the one left after every
other has been joined — is entitled to the cheap read, and hiding it would only have meant a method
doing the same thing less visibly. Everywhere else it is a data race, and it is **greppable**, which
is the bargain the whole `*T` tier makes.

## `SpinLock`

The lock a kernel has before it has a scheduler. Blocking means handing the processor to something
else, which means there is something else to hand it to — and the allocator, the scheduler's own run
queue, and an interrupt handler are all code that has to take a lock before any of that exists.

```sysl
no alloc
no os

import sysl.sync.*

var lk = SpinLock(0i32)
var first = lk.try_lock()
var second = lk.try_lock()

print(first, second, lk.held)

lk.unlock()

var third = lk.try_lock()

print(lk.held, third)

lk.unlock()
lk.lock()

print(lk.held)

lk.unlock()
```

```output
true false 1
1 true
1
```

`try_lock` never spins and answers whether it took the lock; `lock` spins until it is free. Neither
takes an `Ordering` and there is no overload that does, because **a lock's orderings are fixed by
what a lock means**: the exchange that takes it is an acquire, the store that frees it is a release,
and that pairing is the whole of what makes the guarded data safe to touch.

### Three things it will not do for you

**It guards nothing by construction.** A spinlock is a flag beside the data, and what the data is
stays the programmer's to remember. That is the difference against
[`Mutex[T]`](/library/thread/#mutext), which owns what it protects — and it is deliberate, because
the code that needs a spinlock is code that is also reaching through raw pointers, where a type that
owned its contents would have nothing coherent to own.

**Nothing checks that the releasing thread is the one that took it**, or that it was held at all.
That would be a second word to maintain on every take, paid by every correct program, to diagnose a
bug the discipline below already asks the reader to hold to.

**A thread that spins burns its processor for as long as it waits.** So this is right only where the
hold is short and bounded: a few instructions under the lock, no allocation, no system call, and
above all no second lock. Where a wait may be long, or where the holder might be descheduled
mid-hold, the answer is a `Mutex[T]` and a real blocking primitive underneath it. **On one processor
with no preemption a spin is a deadlock outright** — nothing can release what nothing else is
running to release.

### Why the flag is not an `Atomic[i32]`

It is used as exactly that, and it is written as three raw calls anyway. `SpinLock` is declared in
the same file as `Atomic[T]`, and a lock whose entire implementation is three atomic operations
reads better as those three than as a wrapper around a wrapper.

The implementation is worth reading for one detail, which is that `lock` **does not spin on the
exchange**:

```sysl
while atomic_swap(&self.held, 1i32, Acquire) != 0i32
    var busy = atomic_load(&self.held, Relaxed)

    while busy != 0i32
        busy = atomic_load(&self.held, Relaxed)
```

A read-modify-write has to take the cache line exclusively every time round, so waiters spinning on
the exchange itself fight each other for the line — and worse, they fight the holder trying to write
the release, which is the one thread whose progress everybody is waiting on. A relaxed load spins in
a shared line and costs nobody anything.

## What is not here

**`volatile` is not a synchronization tool**, and the mistake is worth naming because C's own
reference material used to recommend the qualifier for shared variables. It constrains the
*compiler* — it stops accesses being elided, merged, or reordered relative to one another — and says
nothing about other cores, about ordering, or about tearing. It is for
[device memory](/reference/memory/) and for nothing else. Two threads sharing a counter want
`Atomic[T]`; a `volatile` counter is a race with a keyword in front of it.

**`&sync T` is the language's, not this module's.** The sigil that makes a reference's refcount
atomic is a spelling the compiler checks, and it lives on [memory](/reference/memory/). It makes the
*reference* safe to share and not the object safe to mutate — the fields are still mutable through
any alias — so `&sync Mutex[T]` and `&sync Atomic[i32]` are how shared mutable state is actually
reached.

**There is no channel yet.** The message-passing half of the model — where the rule about which
values may cross a domain boundary is meant to be enforced — is not written. Until it is, that rule
is specification with nothing asking the question, which the [`sysl.thread`](/library/thread/) page
says more about, since that is where it becomes visible rather than theoretical.

---

Next: [`sysl.thread`](/library/thread/) — spawning, joining, and the mutex above the spinlock.
