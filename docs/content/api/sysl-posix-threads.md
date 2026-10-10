---
title: sysl.posix.threads
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.posix.threads
summary: "Threads of execution, and the two things a program does with one: start it, and wait for it."
requires: "no alloc, requires { posix }"
---

**This module requires a capability and `sysl.sync` does not**, which is the whole reason the two
are apart. `Atomic[T]` and `SpinLock` are reachable from a module that has given up its allocator
and its operating system, because a word the processor touches indivisibly is something a bare
machine has. A *thread* is not: creating one needs a scheduler underneath, and a module's
requirement is module-wide -- one type in here needing an operating system would have taken
`Atomic[T]` out of the kernel's reach.

**What is here is pthreads, which is why it sits under `sysl.posix`** (`reference/modules.md §
Capabilities are a module property`). The capability is `posix` and nothing narrower: whether a
scheduler exists is not something the compiler tracks, because nothing in the library is gated on
one. A bare-metal target running FreeRTOS has threads of its own and no POSIX, so it does not
reach this module and wants a package binding its own kernel instead -- the way `termbox2` sits
beside `sysl.term`.

A **domain** is a thread (`reference/memory.md § Crossing a concurrency domain`), so everything
this module starts is a new one, and what may cross the boundary between two is the subject of
that chapter. Nothing here copies anything: `spawn` hands the new thread an **address**, and what
is at that address is then shared by two threads and needs a `Mutex[T]` beside this file, or an
`Atomic[T]` below it.

**`spawn` is marked `@crossing(arg)`**, so every count the state reaches has to be atomic and the
compiler says so at the call (`reference/memory.md § @crossing`). A raw pointer is on the
crossable list because it carries no refcount to make atomic -- which is a fact about the
pointer, not about what it points at, and the annotation is what asks for the second question to
be put.

## Index

[`channel`](#channel) [`current`](#current) [`spawn`](#spawn) [`yield_now`](#yield_now) [`Channel`](#channel-1) [`Mutex`](#mutex) [`Thread`](#thread)

## Functions

### `channel`

```sysl
channel[T](slots: []T) -> Channel[T]
```

Builds an empty channel over storage the caller supplies.

A function rather than an associated `new`, because the element type is inferred from the slots
and there is no receiver for it to be read off: `channel(slots[..])` says everything, where
`Channel[int].new(…)` would say the element type twice.

**The slice is read here and not kept**, for the reason the `slots` field gives: what is stored is
its address and its length, so that the channel may cross a domain. The storage has to outlive
every thread that holds the channel, which is the same contract `spawn(&body, &state)` already
has with whatever `state` points at — a module-level `static var`, or a local of a `main` that
joins before it returns.

### `current`

```sysl
current() -> Thread
```

The calling thread's own handle, which is what a body compares against to learn it is not the
thread that spawned it.

### `spawn`

```sysl
spawn[T](body: *extern(*T) -> unit, arg: *T) -> Option[Thread]
```

Starts `body` on a new thread, with `arg` as the address it is handed.

**The body is a `*extern`, not a callable** (`reference/ffi.md § A function's address`), and the
reason is this module's `@no_alloc`: a closure would have to be boxed for the new thread to reach
it, and a box needs an allocator that everything else here does without. The address of a named
function is what C's own interface takes, so it is what this takes.

**That is a limit of this module and not of the language, which this paragraph once said the
other way round.** It read that a closure's captures would be "values crossing a domain boundary
with nothing yet checking that they may", and no such moment existed: the capture check was
already shipping when the sentence was written. A `&sync Fn` is checked capture by capture, and
the refusal names the offending one and what to hold it as. So a **package** that has an
allocator is free to take a closure here -- `sh.sysl.libuv`'s thread pool does -- and only
`@no_alloc` stands in this module's way.

It is corrected rather than deleted because a reader who believed it once designed around it, and
the shape they would have reached for -- a `*extern` and a state struct at every call site, with
no captures anywhere -- is materially worse than the closure that works.

**`T` is inferred from the body**, so `spawn(&work, &state)` is the whole of the call. An address
is always written, `null` included -- and `null` is the one thing that cannot be, since it takes
its type from its context and the context here is the `T` being inferred. A body with nothing of
its own to read is handed the address of whatever it reads instead, which every body has.

The pointer is the sharing: two threads reading and writing what is at that address is a race
unless something orders them, which is what `Mutex[T]` and `Atomic[T]` are for.

**`@crossing(arg)` is what holds a caller to `06`'s rule about what may reach another domain.**
Without it the pointer would end the question: a `*T` is on the crossable list because *it* carries
no count, which says nothing about the object at the far end -- and the object at the far end is
what the new thread gets. The annotation is what asks the compiler to look through it, so a state
holding a plain `&T` is refused here rather than racing later.

A body declared `-> unit` is called by pthreads as though it returned a `void *`, and the value it
leaves in the return register is whatever was there. Nothing reads it -- `join` above passes no
place to put it -- so the mismatch costs the thread's exit value, which this module does not offer.

### `yield_now`

```sysl
yield_now() -> bool
```

Offers the processor to whatever else is ready to run, and answers whether the system took it.

This is a **hint**, not a wait: a thread that yields is still runnable and may be given the
processor straight back. What it is for is the spin in `Mutex.lock_raw`, where the thread holding the
lock may not be running at all and nothing else will make it so.

## Types

### `Channel`

```sysl
struct Channel[T]
    private held: i32
    private ring: Ring[T]
    private closed: i32
```

A bounded queue two threads hand values across, and the one place the language's rule about what
may leave a concurrency domain is enforced on a *value* rather than on an address
(`library/sync.md`, `reference/memory.md § Crossing a concurrency domain`).

**It is what `Thread.join` says it does not have.** Everything else in this module shares by
*address*: `spawn` hands the new thread a `*T`, `Mutex[T]` hands a holder a `*T`, and what crosses
is a pointer whose far end both threads are looking at. A channel is the other shape — a value
goes in on one thread and comes out on another, and the two are never looking at one object.

**The storage is the caller's, which is this module's house style rather than a limitation.**
`Mutex[T]` wraps a value the caller built and `spawn` takes an address the caller has; a channel
takes the slots. What that buys is that the type needs no allocator — `@no_alloc` on the file is
the whole of the claim — so a channel is available to a program that has given the heap up, and
its capacity is a number the program chose rather than one this file did.

```
var slots: [8]int = [0; 8]
var ch = channel(slots[..])

spawn(&producer, &ch)

loop
    ch.receive() match
        Some(v) -> print(v)
        None -> break
```

**A waiter yields rather than sleeps**, exactly as `Mutex` does and for the same reason: there is
no wait queue in this module, so a full send and an empty receive give the processor up and try
again. That costs a context switch per attempt where a futex would cost none, and it is a
scheduler call rather than a spin, so a waiter cannot starve the thread it is waiting for.

**The copying half of the crossing rule is NOT built.** `reference/memory.md § Crossing a
concurrency domain` allows a channel to take a heap-backed view *because it copies the bytes*, and
this one does not copy: a slot is assigned, which is the same share the sender held. So a view is
refused here exactly as it is at `spawn`, and the relaxation waits on something that copies.

| Member | Signature | Description |
|---|---|---|
| `capacity` | `capacity(*self) -> usize` | How many values the channel can hold at once, which is the storage it was given. |
| `len` | `len(*self) -> usize` | How many values are waiting to be taken. |
| `is_closed` | `is_closed(*self) -> bool` | Whether `close` has been called. |
| `close` | `close(*self)` | Stops the channel taking anything further, and lets every waiter go. |
| `send` | `send(*self, value: T) -> bool` | Puts a value in, waiting while the channel is full, and answers whether it went in. |
| `try_send` | `try_send(*self, value: T) -> bool` | Puts a value in if there is room, and answers whether it went. |
| `receive` | `receive(*self) -> Option[T]` | Takes a value out, waiting while the channel is empty. |
| `try_receive` | `try_receive(*self) -> Option[T]` | Takes a value out if there is one, and answers nothing where there is not. |

### `Mutex`

```sysl
struct Mutex[T]
    private held: i32
    private value: T
```

Mutual exclusion that **owns what it protects**, which is the difference `library/threads.md §
Mutex[T]` draws against `SpinLock`.

A spinlock is a flag beside the data and what the data is stays the programmer's to remember. This
holds the `T`, and both of its fields are private -- so there is no way to reach the value that
does not go through the lock, and no way to build one that skips the free state.

**`with` is the way in**: it takes the lock, hands a closure the address of the value, and releases
the lock when the closure returns. The address is lent (`@lends`), so the compiler holds the closure
to letting it go -- returning it, storing it, or passing it to something that keeps it is refused,
which is the one mistake a returned address could not prevent: using it after the release.

```
m.with((n) -> *n += 1)
```

`lock_raw` and `unlock_raw` are the hold a block cannot express -- taken in one function and
released in another, or handed from one thread to the next. Nothing ties their address to the hold,
which is what the name says.

**`@guards(value)` is what lets one cross into a `&sync`** whatever it holds (`reference/memory.md §
A guarded value crosses with its lock`): sharing the lock does not share the value, and what leaves
through `with` or `try_with` is held to the `&sync` rule instead -- an `int` or a `.copy()` of a
string, not the `Map` or a string still sharing the guarded one's count.

**It is not built on `pthread_mutex_t`, and the reason is a build property rather than a
preference.** A caller-allocated opaque C type is one of the three things `reference/ffi.md § A
library may carry C` names as reachable from C and from nothing else: its size is in a header, it
differs between the platforms and between two libcs on the *same* platform -- 64 bytes on Darwin,
40 under glibc on x86-64, 48 on aarch64, 40 again under musl -- and `#if` can ask which operating
system this is but not which libc. Transcribing a bound would compile everywhere and be checked
nowhere, which is precisely the failure that section is about. The way to read a header is C, and
the standard library deliberately includes none: it reaches libc by symbol alone, which is what
lets it go on building for any target the toolchain can lower for. So the lock is three atomic
operations and a yield, and a binding library that carries its own C shim is where a futex-backed
one belongs.

**What that costs is a waiter's processor for one scheduling quantum at a time.** The spin is not
`SpinLock`'s -- a failed exchange gives the processor up rather than turning round again -- so a
waiter cannot starve the holder the way a pure spin can on one core, and the hold may be as long as
it likes. What it does not do is *sleep*: there is no wait queue, so a contended lock costs a
context switch per attempt where a futex would cost none.

| Member | Signature | Description |
|---|---|---|
| `new` | `new(value: T) -> Mutex[T]` | Builds a free lock around a value. |
| `with` | `with[R](*self, body: *T -> R) -> R` | Takes the lock, waiting until it is free, runs `body` with the address of what it protects, releases the lock, and answers what `body` answered. |
| `try_with` | `try_with[R](*self, body: *T -> R) -> Option[R]` | The same, if the lock is free: `Some` of what `body` answered, or `None` without waiting. |
| `lock_raw` | `lock_raw(*self) -> *T` | Takes the lock, waiting until it is free, and answers the address of what it protects. |
| `try_lock_raw` | `try_lock_raw(*self) -> Option[*T]` | Takes the lock if it is free, and answers what it protects where it did. |
| `unlock_raw` | `unlock_raw(*self)` | Releases the lock a `lock_raw` or `try_lock_raw` took. |

### `Thread`

```sysl
struct Thread
    id: usize
```

A thread that has been started, and may be waited for.

It is a handle rather than the thread: copying one copies the handle, and joining either copy
joins the one thread. Joining **twice** is undefined in POSIX and is not checked here, for the
reason `SpinLock.unlock` gives about the releasing thread -- the word it would take to notice is
paid by every correct program.

| Member | Signature | Description |
|---|---|---|
| `join` | `join(self) -> bool` | Waits for the thread to finish, and answers whether it was waited for. |
