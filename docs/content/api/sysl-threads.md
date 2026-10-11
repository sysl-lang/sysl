---
title: sysl.threads
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.threads
summary: "Threads of execution, and the two things a program does with one: start it, and wait for it -- with the lock, the condition variable and the channel threads share things through."
requires: "requires { os }"
---

**This module asks for `os` and nothing more.** A thread is something an operating system's
scheduler gives a program, and what starts one is whatever answers the hooks of
`sysl.threads.sys` -- pthreads on a hosted target, a kernel's own `@export`s over `clone` and
`futex` on a bare one, which needs neither POSIX nor a C library. `sysl.sync` stays below it,
requiring nothing: an atomic word is something a bare machine has whether or not anything
schedules threads on it.

`sysl.posix.threads` is the same API under its older name, kept for the programs that import it.

## Answered by whatever is underneath

**Every thread is started, joined and named through the hooks** -- `sysl_thread_spawn`,
`sysl_thread_join`, `sysl_thread_self` and `sysl_thread_yield` -- and **every wait sleeps on the
futex pair**, `sysl_futex_wait` and `sysl_futex_wake`. `Lock`, `Mutex[T]`, `Condvar` and
`Channel[T]` are written once, here, over `sysl.sync.Atomic[u32]` and those two hooks; nothing in
them is a C type.

## The library owns the stack, and the compiler owns the thread-local storage

**`spawn` makes one allocation per thread** holding the thread's id word, its block of
`@thread_local` storage and its stack, and `join` gives it back. A program with no heap starts a
thread on storage of its own with `spawn_on`. **The thread-local block is laid out from the
program's own template** -- the `.tdata` image and the `.tbss` size the linker made, which the
compiler's link names -- so a kernel answering `sysl_thread_spawn` only writes the thread pointer.
On a hosted target the C library lays each thread's storage itself and `spawn` passes the system no
stack, pthreads taking its own.

A **domain** is a thread (`reference/memory.md § Crossing a concurrency domain`), so everything
this module starts is a new one. `spawn` hands the new thread an **address**, and what is at that
address is then shared by two threads and needs a `Mutex[T]` beside it, or an `Atomic[T]` below.
**`spawn` is marked `@crossing(arg)`**, so every count the state reaches has to be atomic and the
compiler says so at the call (`reference/memory.md § @crossing`).

## Index

[`DEFAULT_STACK`](#default_stack) [`channel`](#channel) [`current`](#current) [`spawn`](#spawn) [`spawn_on`](#spawn_on) [`tls_place`](#tls_place) [`tls_room`](#tls_room) [`yield_now`](#yield_now) [`Channel`](#channel-1) [`Condvar`](#condvar) [`Lock`](#lock) [`Mutex`](#mutex) [`Thread`](#thread)

## Constants

### `DEFAULT_STACK`

```sysl
const DEFAULT_STACK: usize = 65536
```

The stack `spawn` gives a thread where nothing else is asked for: 64 KiB.

## Functions

### `channel`

```sysl
channel[T](slots: []T) -> Channel[T]
```

Builds an empty channel over storage the caller supplies.

A function rather than an associated `new`, because the element type is inferred from the slots:
`channel(slots[..])` says everything, where `Channel[int].new(…)` would say the element type twice.

**The slice is read here and not kept**: what is stored is its address and its length, so that the
channel may cross a domain. The storage has to outlive every thread that holds the channel, which is
the same contract `spawn(&body, &state)` already has with whatever `state` points at.

### `current`

```sysl
current() -> Thread
```

The calling thread's own handle, which is what a body compares against to learn it is not the
thread that spawned it. It cannot be joined: a thread waiting for itself would wait for ever.

### `spawn`

```sysl
spawn[T](body: *extern(*T) -> unit, arg: *T, stack: usize = DEFAULT_STACK) -> Option[Thread]
```

Starts `body` on a new thread, with `arg` as the address it is handed.

**On a bare target the library makes the thread's storage**: one allocation of `stack` bytes plus
the thread's id word and its block of `@thread_local` storage, given back by `join`. On a hosted
target nothing is allocated and `stack` is not used: pthreads makes the thread's stack, and the C
library its thread-local storage.

**The body is a `*extern`, not a callable** (`reference/ffi.md § A function's address`): it is
what every system's thread start takes, and a closure would have to be boxed for the new thread to
reach it. A package with an allocator is free to take a `&sync Fn` and call it from a body of its
own -- `sh.sysl.libuv`'s thread pool does, and the capture check holds such a closure to the
crossing rule capture by capture.

**`T` is inferred from the body**, so `spawn(&work, &state)` is the whole of the call. An address
is always written, `null` included -- and `null` is the one thing that cannot be, since it takes
its type from its context and the context here is the `T` being inferred.

**`@crossing(arg)` is what holds a caller to the rule about what may reach another domain.** A
`*T` is on the crossable list because *it* carries no count, which says nothing about the object at
the far end -- and the object at the far end is what the new thread gets. The annotation is what
asks the compiler to look through it, so a state holding a plain `&T` is refused here rather than
racing later.

### `spawn_on`

```sysl
spawn_on[T](stack: []u8, body: *extern(*T) -> unit, arg: *T) -> Option[Thread]
```

Starts `body` on a new thread whose storage is `stack`, which the program owns.

What a program with no heap writes: the slice holds the thread's id word, its block of
`@thread_local` storage and, in what is left, its stack -- so it has to outlive the thread, which is
the contract `spawn(&body, &state)` already has with whatever `state` points at. A module-level
`static var` of bytes, or a local of a `main` that joins before it returns, is the usual owner.

It answers `None` where the slice leaves no stack once the word and the thread-local block are
laid in it, and where the system would not start the thread. On a hosted target the stack is handed
to pthreads, which asks for at least its `PTHREAD_STACK_MIN` -- 16 KiB on macOS, 128 KiB under glibc
on aarch64 -- and is trimmed to whole 16 KiB pages first.

### `tls_place`

```sysl
tls_place(room: []u8) -> Option[*u8]
```

Lays one thread's block of `@thread_local` storage out in `room` -- the template's initialized
bytes copied, the rest zeroed -- and answers the value the thread pointer holds while that thread
runs.

`Some(null)` where there is nothing to lay: the program has no thread-local storage, or the target
is hosted and the C library lays it. `None` where `room` is smaller than `tls_room()`.

**The layout is the processor's ABI for local-exec access, at the offsets the linker computed**: on
AArch64 the pointer is the start of a 16-byte control block the storage follows at its alignment; on
x86-64 the storage ends at the pointer, whose first word holds its own address; elsewhere the
storage starts at the pointer. The pointer is aligned to the storage's alignment in each.

### `tls_room`

```sysl
tls_room() -> usize
```

How many bytes one thread's block of `@thread_local` storage needs, slack for its alignment
included -- zero where the program has none, and **always zero on a hosted target**, where the C
library lays each thread's block down itself.

It is what a program running a thread `spawn` did not start -- its first one, on a bare target --
sets aside before laying the block with `tls_place`.

### `yield_now`

```sysl
yield_now() -> bool
```

Offers the processor to whatever else is ready to run, and answers whether the system took it.

This is a **hint**, not a wait: a thread that yields is still runnable and may be given the
processor straight back.

## Types

### `Channel`

```sysl
struct Channel[T]
    private lock: Lock
    private has_room: Condvar
    private has_value: Condvar
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
takes the slots. What that buys is that the type needs no allocator, so a channel is available to a
program that has given the heap up, and its capacity is a number the program chose.

```
var slots: [8]int = [0; 8]
var ch = channel(slots[..])

spawn(&producer, &ch)

loop
    ch.receive() match
        Some(v) -> print(v)
        None -> break
```

**A waiter sleeps**: a full `send` waits on one `Condvar` and an empty `receive` on the other, each
woken by the transfer that makes room or brings a value, and both woken by `close`.

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

### `Condvar`

```sysl
struct Condvar
    private seq: Atomic[u32]
```

A place for threads to sleep until another says something has changed: a condition variable.

**It is a sequence number**, bumped by every `notify_one` and `notify_all`. A waiter reads the number
while it still holds the lock, lets the lock go, and sleeps in `sysl_futex_wait` only while the
number is still the one it read -- so a notification sent between the release and the sleep changes
the number, the futex finds it changed, and the waiter does not sleep through it. That is the whole
of what makes a condition variable correct, and it needs no queue of its own.

**A wait can end with nothing having changed** -- a spurious wake, a notification meant for another
waiter -- so the condition is always re-checked in a loop, as with every condition variable:

```
val state = m.lock_raw()

while !state.ready
    cv.wait(&m)

m.unlock_raw()
```

| Member | Signature | Description |
|---|---|---|
| `new` | `new() -> Condvar` | A condition variable nobody is waiting on. |
| `wait` | `wait[T](*self, m: *Mutex[T])` | Releases `m`, which the caller holds, sleeps until notified, and takes `m` again before answering. |
| `wait_for` | `wait_for[T](*self, m: *Mutex[T], ns: i64) -> bool` | `wait`, for at most `ns` nanoseconds: answers `false` where the time ran out first. |
| `wait_raw` | `wait_raw(*self, l: *Lock)` | `wait` over a bare `Lock`, which the caller holds. |
| `notify_one` | `notify_one(*self)` | Wakes one waiter, if there is one. |
| `notify_all` | `notify_all(*self)` | Wakes every waiter. |

### `Lock`

```sysl
struct Lock
    private state: Atomic[u32]
```

A lock that sleeps, and guards nothing of its own: the classic three-state futex lock.

**The word is zero when free, one when held, and two when held with a thread asleep on it**, so an
uncontended `lock` and `unlock` are one atomic operation each and never reach the system, and only
an `unlock` that may have a sleeper to wake calls `sysl_futex_wake`. A waiter sleeps in
`sysl_futex_wait` rather than spinning or yielding, so a contended lock costs nothing while it waits.

It is what `Mutex[T]` and `Channel[T]` are built on, and it is public for the hold neither of them
expresses: a lock beside data the program names itself, as `SpinLock` is, for code that may sleep.

| Member | Signature | Description |
|---|---|---|
| `new` | `new() -> Lock` | A free lock. |
| `lock` | `lock(*self)` | Takes the lock, sleeping until it is free. |
| `try_lock` | `try_lock(*self) -> bool` | Takes the lock if it is free, and answers whether it did. |
| `unlock` | `unlock(*self)` | Releases the lock, waking one sleeper where there may be one. |

### `Mutex`

```sysl
struct Mutex[T]
    private lock: Lock
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
released in another, or held across a `Condvar.wait`. Nothing ties their address to the hold,
which is what the name says.

**`@guards(value)` is what lets one cross into a `&sync`** whatever it holds (`reference/memory.md §
A guarded value crosses with its lock`): sharing the lock does not share the value, and what leaves
through `with` or `try_with` is held to the `&sync` rule instead -- an `int` or a `.copy()` of a
string, not the `Map` or a string still sharing the guarded one's count.

**The lock is a `Lock`, written in sysl over an `Atomic[u32]` and the futex hooks**, not a
`pthread_mutex_t`: a caller-allocated C struct's size is in a header and differs between libcs on
one platform, and the futex is what every system that has threads has underneath its own mutex. A
waiter sleeps; it neither spins nor yields.

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
    id: u64
    private word: *u64
    private room: *Room
```

A thread that has been started, and may be waited for.

It is a handle rather than the thread: copying one copies the handle, and joining either copy
joins the one thread. Joining **twice** is not checked here -- the second join would give the
thread's storage back a second time -- for the reason `SpinLock.unlock` gives about the releasing
thread: the word it would take to notice is paid by every correct program. A thread never joined
keeps its storage for the rest of the program.

| Member | Signature | Description |
|---|---|---|
| `join` | `join(self) -> bool` | Waits for the thread to finish, and answers whether it was waited for. |
