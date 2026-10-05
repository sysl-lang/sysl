---
title: sysl.posix.threads
summary: Starting a thread, waiting for one, and the mutex above the spinlock — the half of concurrency that needs a scheduler.
weight: 80
---

**Every declaration in `sysl.posix.threads`, with its signature:** [the generated API page](/api/sysl-posix-threads/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.posix.threads` is where the capability lands. Everything on the [`sysl.sync`](/library/sync/) page is
reachable from a module that has given up its allocator and its operating system; nothing here is,
because creating a thread needs a scheduler underneath it.

```sysl
@no_posix

import sysl.sync.*
import sysl.posix.threads.spawn

var a = Atomic(0)

print(a.load())
```

```error
this reaches 'sysl.posix.threads', which requires 'posix', and this module declared 'no posix' — an environment capability gates which modules exist, so a module that gave one up may not reach one that needs it
```

Note where that lands: **at the import**, not at the call. A capability a module has given up decides
which modules exist for it, so a program that cannot spawn is a program whose author never sees the
name — and the `sysl.sync` import on the line above is untouched, which is the split working exactly
as it is meant to.

The module declares **one** requirement, and the namespace it sits in says the same thing twice over:

```
module sysl.posix.threads
@requires(posix)
```

**What is here is pthreads**, which is the whole claim and the reason the module lives under
`sysl.posix` beside [`sysl.posix.tty`](/library/term/#taking-the-terminal-over-sysl-posix-tty-raw) and
[`sysl.posix.rand`](/library/rand/). A module in that namespace is one a freestanding target does not
get, and the path is enough to know it without opening the file.

There was a fourth capability, `threads`, and it was **removed rather than renamed**. It gated this
one module, and it read as a claim that the compiler tracks whether a scheduler exists — which it
does not, and which nothing in the library was gated on. **A board running FreeRTOS or Zephyr has
threads and no POSIX**: it does not reach this module, and it binds its own kernel as a package,
because no capability could have made `pthread_create` appear on it. So `@no_threads` is now an
unknown capability, and the three that remain are `heap`, `os` and `posix`.

| name | what it is |
|---|---|
| `spawn(body, arg)` | starts a thread, answering `Option[Thread]` |
| `Thread.join` | waits for one, answering whether it waited |
| `current()` | the calling thread's own handle |
| `yield_now()` | offers the processor away — a hint, not a wait |
| `Mutex[T]` | mutual exclusion that owns what it protects |

## `spawn` takes an address, not a callable

```sysl
import sysl.posix.threads.*

struct Job
    input: i32
    output: i32

square(j: *Job)
    j.output = j.input * j.input

var job = Job(12, 0)
var t = spawn(&square, &job).unwrap()

t.join()

print(job.input, job.output)
```

```output
12 144
```

`&square` is the address of a named function, and the parameter's type is `*extern(*T) -> unit` —
C's own shape, because `pthread_create` is underneath. **A closure will not do**, and the refusal is
worth seeing in both of the ways a reader will hit it. Written bare, the argument has nothing to
infer its parameter type from, because `T` is what is being inferred:

```sysl
import sysl.sync.*
import sysl.posix.threads.*

var counter = Atomic(0)
var c = spawn(a -> a.add(1), &counter)

print(c.is_some())
```

```error
'a' has no type here — nothing says what this closure takes, so write it
```

Write the type in and the real mismatch surfaces:

```sysl
import sysl.sync.*
import sysl.posix.threads.*

var counter = Atomic(0)
var c = spawn((a: *Atomic[i32]) -> a.add(1), &counter)

print(c.is_some())
```

```error
'body' of 'sysl.posix.threads.spawn' is *extern(*sysl.sync.Atomic[int]) -> unit, but a closure was given
```

Two reasons, and neither is a limitation waiting to be lifted. A closure would have to be **boxed**
for the new thread to reach it, which needs an allocator this module could otherwise do without; and
its **captures** would be values crossing a domain boundary through a box whose shape says nothing
about what is in it. The address of a named function has neither problem, and what travels beside it
is an ordinary parameter — which is what lets the crossing rule be asked at all.

### A body with nothing to read is passed `null`

`spawn` is generic in what the body reads, so `T` is inferred from the body and the `ptr_cast` to
C's shape happens once, inside. A body with nothing of its own writes `null`, and the type it would
otherwise have had to invent is one the *body's* own signature already gave:

```sysl
import sysl.posix.threads.*

quiet(state: *int)
    print("nothing to read")

spawn(&quiet, null).unwrap().join()
```

```output
nothing to read
```

`null` has no type of its own, so at a parameter still being solved it is set aside until the
arguments that have one are read — here the `&quiet`, whose `*extern(*int) -> unit` says `T` is
`int`. It is refused only where *nothing* says: a call with no other argument to read is an error
asking for the type rather than a guess.

### `join` does not carry the result back

It answers a `bool` — whether it waited — and nothing else. That is the crossing rule rather than an
oversight: **a result coming out of another domain is a value crossing a boundary**, which is what a
channel is for, and the channel is not written. So a body that has something to say writes it
through the address it was given, which is what the `Job` above does.

The `Thread` value is a **handle rather than the thread**. Copying one copies the handle, and
joining either copy joins the one thread; joining **twice** is undefined in POSIX and is not checked
here, for the same reason `SpinLock.unlock` checks nothing — the word it would take to notice is
paid by every correct program. The `id` field is public so that a program can hand it to a POSIX
call this module does not wrap, such as `pthread_detach` or a scheduling parameter, since the
alternative is a wrapper per call.

## Sharing the thing at the address

The pointer *is* the sharing. Two threads reading and writing what is at that address is a data race
unless something orders them, and the two things that order them are on the
[`sysl.sync`](/library/sync/) page.

### What may be at that address

`spawn` is declared `@crossing(arg)`, which is how a facility says a parameter hands a value to
another concurrency domain ([memory](/reference/memory/)). It is why the state a thread is given is
**checked** rather than taken on trust: a raw pointer carries no refcount of its own, and the
annotation is what asks the compiler to look through it at the object that actually crossed.

```sysl
import sysl.posix.threads.*

struct Cell
    n: int

struct State
    cell: &Cell

look(s: *State)
    print(s.cell.n)

var c: &Cell = Cell(1)
var st = State(c)

print(spawn(&look, &st).is_some())
```

```error
what 'arg' of 'sysl.posix.threads.spawn' points at reaches another concurrency domain, so every count inside it has to be atomic
```

A plain `&Cell` has a **non-atomic** count, so two threads retaining it is exactly the race the model
exists to prevent — and until the annotation existed, this program compiled and ran. Writing
`&sync Cell` in the field and at the allocation is the whole fix: the count becomes atomic, and the
same program is accepted.

Nothing here is special to this module. A package binding FreeRTOS or Zephyr writes the same line
above the wrapper it already has, and gets the same refusal at its own callers' calls.

```sysl
import sysl.sync.*
import sysl.posix.threads.*

bump(a: *Atomic[i32])
    for i in 0..<10000
        a.add(1)

var counter = Atomic(0)
var t1 = spawn(&bump, &counter).unwrap()
var t2 = spawn(&bump, &counter).unwrap()
var j1 = t1.join()
var j2 = t2.join()

print(j1, j2)
print(counter.load())
```

```output
true true
20000
```

A `SpinLock` works across real threads exactly as it does in the single-threaded program on the
other page, and this is where its "guards nothing by construction" becomes concrete — the lock is a
field beside the data, and remembering that `total` is what `guard` guards is the programmer's job:

```sysl
import sysl.sync.*
import sysl.posix.threads.*

struct Shared
    guard: SpinLock
    total: i32

add_up(s: *Shared)
    for i in 0..<10000
        s.guard.lock()
        s.total = s.total + 1
        s.guard.unlock()

var sh = Shared(SpinLock(0), 0)
var s1 = spawn(&add_up, &sh).unwrap()
var s2 = spawn(&add_up, &sh).unwrap()

s1.join()
s2.join()

print(sh.total)
```

```output
20000
```

### Take the atomic away and it is a race

```sysl
import sysl.posix.threads.*

racy(p: *i32)
    for i in 0..<100000
        *p = *p + 1

var n = 0
var r1 = spawn(&racy, &n).unwrap()
var r2 = spawn(&racy, &n).unwrap()

r1.join()
r2.join()

print(n)
```

That program is a data race by definition: two threads, one word, no synchronization. **On the run
that produced this page it printed `200000` — the right answer.** It is not on the site as a checked
program for exactly that reason, and the reason is the lesson: a race that gives the right answer on
the machine you tested it on is why the model is built out of things you can *grep for* rather than
things you can test for. `*T` is one of the two spellings that share on purpose, and it is the
unchecked one.

## `Mutex[T]`

`Mutex[T]` **owns what it protects**, which is the whole difference against `SpinLock`. Both of its
fields are private, so there is no way to reach the value that does not go through `lock` or
`try_lock`:

```sysl
import sysl.posix.threads.*

var m = Mutex.new(7)

print(m.value)
```

```error
field 'value' of 'sysl.posix.threads.Mutex' is private to 'library/sysl/posix/threads/mutex.sysl', the file that declares it
```

Private is the entirety of what "owns" means here. With `value` public, reading it would be an
unsynchronized read of the very thing the type exists to synchronize, and the type would be a
spinlock with a suggestion attached.

The private field does a second job: it puts the **positional constructor** out of reach, so
`Mutex.new` is the only way in and there is no way to build one that starts out held.

```sysl
import sysl.posix.threads.*

var built = Mutex(0, 5)
var p = built.lock()

print(*p)
```

```error
the constructor names every field of 'sysl.posix.threads.Mutex' in order, and 'held' is private to 'library/sysl/posix/threads/mutex.sysl', the file that declares it — build it through an associated function of its own
```

### `lock` answers an address, and releasing is written

Rust returns a guard whose destruction releases the lock. sysl has a
[destructor](/reference/memory/) now and deliberately does not use it here: a destructor runs for a
value held behind a `&T`, so a guard would mean a heap allocation per `lock` — on the one path where
the whole point is to hold a lock for as few instructions as possible. `defer m.unlock()` is the
idiom, the same one [`sysl.fs`](/library/fs/) uses for `close`, and for the same reason.

```sysl
import sysl.posix.threads.*

inc(m: *Mutex[i32])
    for i in 0..<10000
        var p = m.lock()

        defer m.unlock()
        *p = *p + 1

var mx = Mutex.new(0)
var a1 = spawn(&inc, &mx).unwrap()
var a2 = spawn(&inc, &mx).unwrap()

a1.join()
a2.join()

var mp = mx.lock()

print(*mp)

mx.unlock()
```

```output
20000
```

`defer` is [block-scoped](/reference/statements/), not function-scoped, so the `defer` inside that
loop body runs at the end of **each iteration** — which is what makes it usable for a lock taken in
a loop at all, and is the point at which sysl's `defer` and Go's stop agreeing.

**The mistake this shape cannot prevent** is holding on to the address past the `unlock`. Nothing
takes it away from you, and nothing will tell you.

`try_lock` never waits and answers an `Option[*T]`, which is the ordinary shape for "it might not
have worked":

```sysl
import sysl.posix.threads.*

var q = Mutex.new(5)
var g = q.try_lock()

print(g.is_some(), *g.unwrap())

var again = q.try_lock()

print(again.is_some())

q.unlock()

var third = q.try_lock()

print(third.is_some())

q.unlock()
```

```output
true 5
false
true
```

Neither `lock` nor `try_lock` takes an `Ordering`, and neither does `unlock`, because **a lock's
orderings are fixed by what a lock means**. The exchange that takes it is an acquire and the store
that frees it is a release, and that pairing is what publishes everything the holder wrote to
whichever thread takes the lock next. It is the whole of what makes the data safe to touch.

### It is not built on `pthread_mutex_t`

The reason is a **build property rather than a preference**, and it is worth spelling out because it
is the same argument that keeps the standard library buildable for targets nobody has tried yet.

A caller-allocated opaque C type is one of the three things the
[compilation model](/reference/ffi/) names as reachable from C and from nothing else. Its size lives
in a header, and it differs both between platforms and between two libcs on the *same* platform —
64 bytes on Darwin, 40 under glibc on x86-64, 48 on aarch64, 40 again under musl. `#if` can ask
which operating system this is, but **not which libc**. So a transcribed byte count would compile
everywhere and be checked nowhere, which is precisely the failure that section is about.

The way to read a header is C, and the standard library deliberately includes none: it reaches libc
by symbol alone, which is what lets it go on building for any target the toolchain can lower for.

```sysl
private[sysl] extern "pthread_create" c_pthread_create(
    t: *usize,
    attr: *u8,
    body: *extern(*u8) -> *u8,
    arg: *u8,
) -> int
private[sysl] extern "pthread_join" c_pthread_join(t: usize, result: **u8) -> int
```

Every one of those is a **scalar** handle or an address. A `pthread_t` is one word on both platforms
this builds for — a pointer on Darwin, an `unsigned long` under glibc — and `usize` is the spelling
that is both. Transcribing that is safe in a way transcribing a *layout* is not, and it is the only
transcription in the module.

So `Mutex[T]` is three atomic operations and a yield: an acquiring exchange to take it, a relaxed
load between attempts, and a releasing store to free it. **What that costs is a context switch per
contended attempt**, where a futex would cost none. A futex-backed mutex is what a binding library
carrying its own C shim would add; it is not something the standard library can reach.

The spin is not `SpinLock`'s, either. A failed exchange **gives the processor up** rather than
turning round again, so a waiter cannot starve the holder the way a pure spin can on one core, and
the hold may be as long as it likes. What it does not do is *sleep*: there is no wait queue.

## `Channel[T]`

**A bounded queue two threads hand values across**, and the one place the rule about leaving a
concurrency domain is asked of a **value** rather than of an address. Everything else on this page
shares by address — `spawn` hands a `*T`, `Mutex[T]` hands a `*T` — and what crosses is a pointer
whose far end both threads are looking at. A channel is the other shape: a value goes in on one
thread and comes out on another, and the two are never looking at one object.

```sysl
import sysl.posix.threads.*

var slots: [4]int = [0; 4]
var ch = channel(slots[..])

print(ch.capacity(), ch.len(), ch.is_closed())
print(ch.try_send(1), ch.try_send(2))
print(ch.try_receive(), ch.try_receive(), ch.try_receive())
```

```output
4 0 false
true true
Some(1) Some(2) None
```

| | |
|---|---|
| `channel(slots)` | an empty channel over storage the caller supplies |
| `ch.send(v)` | puts a value in, waiting while full; `false` only if closed |
| `ch.try_send(v)` | the same without waiting |
| `ch.receive()` | takes one out, waiting while empty; `None` once closed **and drained** |
| `ch.try_receive()` | the same without waiting |
| `ch.close()` | no more will arrive; what already has is still taken |
| `ch.capacity()`, `ch.len()`, `ch.is_closed()` | what it holds and whether it is shut |

**The storage is the caller's**, which is this module's house style rather than a limitation —
`Mutex[T]` wraps a value the caller built and `spawn` takes an address the caller has. What it buys
is that a channel needs no allocator at all, so it is available to a program that gave the heap up,
and its capacity is a number the program chose. The storage has to outlive every thread holding the
channel, which is the contract `spawn(&body, &state)` already has with whatever `state` points at.

```sysl
import sysl.posix.threads.*

struct Feed
    ch: Channel[int]

feeder(f: *Feed) -> unit
    for i in 1..5
        f.ch.send(i)

    f.ch.close()

var slots: [2]int = [0; 2]
var f = Feed(channel(slots[..]))
var running = spawn(&feeder, &f) match
    Some(t) -> t
    None -> panic("the thread did not start")

var total = 0

loop
    f.ch.receive() match
        Some(v) -> total += v
        None -> break

print(running.join(), total)
```

```output
true 15
```

Five values go through a ring of two, so the producer really does fill it and really is woken.

### `@crossing(value)` is written on `send`, and marking the way in is the whole check

`send` and `try_send` are the two operations that put a value in, so they carry
[`@crossing(value)`](/reference/memory/) — which is what holds a caller to the rule about what may
reach another domain. `receive` carries nothing and needs nothing: **nothing can be taken out that
was not put in**, so a channel whose sends are held to the rule can hold nothing that breaks it.

They were **free functions** taking the channel by address until sysl let a member carry an
annotation about a parameter, because the word had to go on a wrapper a caller already went through.
That left a channel's queries as methods and its transfers as functions, which is an asymmetry a
reader had to learn and no longer has to.

```sysl
import sysl.posix.threads.*

struct Node
    n: int

var slots: [2]&Node = [Node(0); 2]
var ch = channel(slots[..])

print(ch.send(Node(1)))
```

```error
reaches another concurrency domain, so every count inside it has to be atomic
```

### A waiter yields rather than sleeps

There is no wait queue in this module, so a full `send` and an empty `receive` give the processor up
and try again — the same trade `Mutex` makes, and for the same reason. That costs a context switch
per attempt where a futex would cost none; it is a scheduler call rather than a spin, so a waiter
cannot starve the thread it is waiting for.

### The ring is a pointer, and that is the crossing rule

The slots are held as a `*T` and a count rather than as a `[]T`, and it is not a preference: **a
struct holding a view may not reach another domain at all**, because a view owns its elements through
a count that is not atomic. A channel that kept the slice would have been refused at the `spawn` that
shares it — one line before the first `send`, and for the only thing the type is for. A raw pointer
carries no count, so it crosses; the caller writes a slice at `channel(…)` and never sees the pointer.

**The ring is [`Ring[T]`](/library/ring/#the-storage-is-a-pointer-and-that-is-the-crossing-rule)
rather than four fields of this struct's own**, which it was until that module existed. A bounded
queue over caller-supplied storage is what a channel is made of and is also a thing on its own, so
what is left in this file is the lock and the closed flag; the wrapping arithmetic is over there and
tested there. The pointer-and-count shape travelled with it for exactly the reason this section
gives, which is why a channel can embed one.

**The copying half of the crossing rule is still not written.** [The memory
model](/reference/memory/) allows a channel to take a heap-backed view *because it copies the bytes*,
and this one assigns a slot — the same share the sender held. So a view is refused here exactly as it
is at `spawn`.

## `yield_now` and `current`

```sysl
import sysl.posix.threads.*

print(yield_now())

var me = current()
var mine = current()

print(me.id == mine.id)
```

```output
true
true
```

`yield_now` is a **hint, not a wait**. A thread that yields is still runnable and may be handed the
processor straight back; what it is for is the spin in `Mutex.lock`, where the thread holding the
lock may not be running at all and nothing else in the loop would make it so. It answers whether the
system took the offer.

`current()` is what a body compares against to learn that it is not the thread that spawned it.

## There is no `async`

No `async`, no `await`, and no task runtime — in the language or in this module. Threads are what
sysl offers for doing two things at once, and this page is all of it.

**It is deferred rather than refused**, and the difference is worth knowing if you are deciding
whether to build on threads something you would rather have written as tasks. The shape it would take
here is settled: futures compiled to state machines, sized at compile time, with no allocation
inherent in a future and the executor an ordinary library. What stands between that and being built
is a schedule. Nothing on this page changes when it arrives — a thread will still be a thread.

What you get in the meantime is worth having, and both halves of it are things an `await` costs a
language that has one. sysl has no **actor reentrancy** hazard — the trap where an actor's state
changes across an `await` — because there is no await-based interleaving. And blocking is honest: a
thread that waits is a thread that waits, with no cooperative-scheduling model to reason about on top
of it, and no way for one stalled task to stall nine others you never looked at.

## How strong this is

Honestly weaker than Rust or Swift 6, and worth saying plainly rather than implying a guarantee the
language cannot keep.

| | checked | not checked |
|---|---|---|
| crossing a domain | what a `@crossing` parameter is handed, structurally | a boundary **nobody marked** — no annotation, no question |
| refcount races | what a `&sync T` may hold, structurally | — |
| mutating shared state | — | **use a `Mutex`; nothing enforces it** |
| the kernel tier | — | `*T`, spinlocks, orderings — as in C |

**This module is where the first row stops being theoretical.** `spawn` is declared
`@crossing(arg)`, so the pointer it takes is looked *through* and a state whose counts are not all
atomic is refused where the call is written. That is also why this API takes an address rather than a
value: **a `spawn` taking a `T` would be claiming the *copying* half of the crossing rule**, which
belongs to a channel and is still not written — `Channel[T]` above assigns a slot rather than copying,
so it refuses a view exactly as `spawn` does.

The unchecked half of that row is where the annotation is **absent**. A facility with no
`@crossing` on it is a boundary the compiler was never told about, and there is no way to guess one:
a scheduler in a package looks like any other function until somebody writes the line.

So a data race requires you to have shared something on purpose. Sharing takes `&sync` or `*T`, both
of which are greppable and neither of which is what an ordinary value is. What remains permanent is
the race you can write by putting a mutable field in a `&sync T`, because it is the cost of not
having a borrow checker.

---

Next: [`sysl.args`](/library/args/) — how `argc` and `argv` become a `[]string`, and the two layers
that read options out of them.
