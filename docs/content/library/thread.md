---
title: sysl.thread
summary: Starting a thread, waiting for one, and the mutex above the spinlock — the half of concurrency that needs a scheduler.
weight: 80
---

`sysl.thread` is where the capability lands. Everything on the [`sysl.sync`](/library/sync/) page is
reachable from a module that has given up its allocator and its operating system; nothing here is,
because creating a thread needs a scheduler underneath it.

```sysl
no threads

import sysl.sync.*
import sysl.thread.spawn

var a = Atomic(0i32)

print(a.load())
```

```error
this reaches 'sysl.thread', which requires 'threads', and this module declared 'no threads' — an environment capability gates which modules exist, so a module that gave one up may not reach one that needs it
```

Note where that lands: **at the import**, not at the call. A capability a module has given up decides
which modules exist for it, so a program that cannot spawn is a program whose author never sees the
name — and the `sysl.sync` import on the line above is untouched, which is the split working exactly
as it is meant to.

The module declares **two** requirements, and both are written because neither implies the other:

```
module sysl.thread
requires threads
requires posix
```

`threads` because a scheduler is an environment capability. `posix` because **pthreads is what this
is built on** — and a bare-metal target with a scheduler of its own has threads and no POSIX, so the
implication list deliberately does not connect them.

| name | what it is |
|---|---|
| `spawn(body, arg)` | starts a thread, answering `Option[Thread]` |
| `Thread.join` | waits for one, answering whether it waited |
| `current()` | the calling thread's own handle |
| `yield_now()` | offers the processor away — a hint, not a wait |
| `Mutex[T]` | mutual exclusion that owns what it protects |

## `spawn` takes an address, not a callable

```sysl
import sysl.thread.*

struct Job
    input: i32
    output: i32
end Job

square(j: *Job)
    j.output = j.input * j.input

var job = Job(12i32, 0i32)
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
import sysl.thread.*

var counter = Atomic(0i32)
var c = spawn(a -> a.add(1i32), &counter)

print(c.is_some())
```

```error
'a' has no type here — nothing says what this closure takes, so write it
```

Write the type in and the real mismatch surfaces:

```sysl
import sysl.sync.*
import sysl.thread.*

var counter = Atomic(0i32)
var c = spawn((a: *Atomic[i32]) -> a.add(1i32), &counter)

print(c.is_some())
```

```error
'body' of 'sysl.thread.spawn' is *extern(*sysl.sync.Atomic[int]) -> unit, but .closure0 was given
```

Two reasons, and neither is a limitation waiting to be lifted. A closure would have to be **boxed**
for the new thread to reach it, which needs an allocator this module could otherwise do without; and
its **captures** would be values crossing a domain boundary with nothing yet checking that they may.
The address of a named function has neither problem.

### `null` is the one argument that cannot be passed

`spawn` is generic in what the body reads, so `T` is inferred from the body and the `ptr_cast` to
C's shape happens once, inside. That inference is what rules `null` out — it takes its type from its
context, and the context here is the type being inferred:

```sysl
import sysl.sync.*
import sysl.thread.*

bump(a: *Atomic[i32])
    a.add(1i32)

var t = spawn(&bump, null)

print(t.is_some())
```

```error
'null' takes its type from its context, and there is none here
```

A body with nothing of its own to read is handed the address of whatever it reads instead, which
every body has.

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

```sysl
import sysl.sync.*
import sysl.thread.*

bump(a: *Atomic[i32])
    for i in 0..<10000
        a.add(1i32)

var counter = Atomic(0i32)
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
import sysl.thread.*

struct Shared
    guard: SpinLock
    total: i32
end Shared

add_up(s: *Shared)
    for i in 0..<10000
        s.guard.lock()
        s.total = s.total + 1
        s.guard.unlock()

var sh = Shared(SpinLock(0i32), 0i32)
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
import sysl.thread.*

racy(p: *i32)
    for i in 0..<100000
        *p = *p + 1

var n = 0i32
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
import sysl.thread.*

var m = Mutex.new(7i32)

print(m.value)
```

```error
field 'value' of 'sysl.thread.Mutex' is private to 'lib/sysl/thread/mutex.sysl', the file that declares it
```

Private is the entirety of what "owns" means here. With `value` public, reading it would be an
unsynchronized read of the very thing the type exists to synchronize, and the type would be a
spinlock with a suggestion attached.

The private field does a second job: it puts the **positional constructor** out of reach, so
`Mutex.new` is the only way in and there is no way to build one that starts out held.

```sysl
import sysl.thread.*

var built = Mutex(0i32, 5i32)
var p = built.lock()

print(*p)
```

```error
the constructor names every field of 'sysl.thread.Mutex' in order, and 'held' is private to 'lib/sysl/thread/mutex.sysl', the file that declares it — build it through an associated function of its own
```

### `lock` answers an address, and releasing is written

That is as far as a language with **no destructor** can take the idea. Rust returns a guard whose
destruction releases the lock; sysl has nothing to hang that on, so `defer m.unlock()` is the idiom
— the same one [`sysl.fs`](/library/fs/) uses for `close`, and for the same reason.

```sysl
import sysl.thread.*

inc(m: *Mutex[i32])
    for i in 0..<10000
        var p = m.lock()

        defer m.unlock()
        *p = *p + 1

var mx = Mutex.new(0i32)
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
import sysl.thread.*

var q = Mutex.new(5i32)
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

## `yield_now` and `current`

```sysl
import sysl.thread.*

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

No `async`, no `await`, and no task runtime — in the language or in this module.

Swift's version needs executors, continuations, and heap-allocated task state; Go's goroutines need
a scheduler and growable stacks. **Neither can exist under `no alloc`**, and the kernel is exactly
where threads are most real. Concurrency machinery of that kind belongs in a library that requires
`alloc` and `threads`, not in a language that has to compile a page-fault handler.

Two things fall out of that, and both are good. sysl has no **actor reentrancy** hazard — the trap
where an actor's state changes across an `await` — because there is no await-based interleaving. And
blocking is honest: a thread that waits is a thread that waits, with no cooperative-scheduling model
to reason about on top of it.

## How strong this is

Honestly weaker than Rust or Swift 6, and worth saying plainly rather than implying a guarantee the
language cannot keep.

| | checked | not checked |
|---|---|---|
| crossing a domain | — | **which values may cross** — specified structurally; the check arrives with the channel |
| refcount races | what a `&sync T` may hold, structurally | — |
| mutating shared state | — | **use a `Mutex`; nothing enforces it** |
| the kernel tier | — | `*T`, spinlocks, orderings — as in C |

**This module is where the first row stops being theoretical.** `spawn` hands the new thread a
`*T`, and a raw pointer is on the crossable list on purpose — it carries no refcount to make atomic.
What it points *at* is not examined, so a plain `&T` reaches another thread through one with nothing
said. That is the rule as written rather than an escape from it, and it is why this API takes an
address rather than a value: **a `spawn` taking a `T` would be claiming a check that does not
exist.**

So a data race requires you to have shared something on purpose. Sharing takes `&sync` or `*T`, both
of which are greppable and neither of which is what an ordinary value is. What you cannot yet be
*stopped* from doing is pointing one of them at something whose count is not atomic — and the race
you can write by putting a mutable field in a `&sync T` is permanent, because it is the cost of not
having a borrow checker.
