---
title: sysl.sync
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.sync
summary: "What two threads may touch at once: `Atomic[T]`, `SpinLock`, and the five memory orderings."
requires: "no alloc"
---

An atomic is a word an operation reads, writes or exchanges indivisibly; an `Ordering` says how
strongly that operation is ordered against the ordinary accesses around it; and a `SpinLock` is
mutual exclusion for code that cannot block — an interrupt handler, a signal handler, a board with
no scheduler.

**This module requires nothing, which is what puts it below `sysl.posix.threads` rather than
inside it.** The threads a program spawns are POSIX's and are behind a capability; the ordering
rules are the machine's, and a freestanding program that shares a word with an interrupt needs
them exactly as much as a hosted one that shares it with a thread.

## Index

[`Atomic`](#atomic) [`Ordering`](#ordering) [`SpinLock`](#spinlock)

## Types

### `Atomic`

```sysl
struct Atomic[T]
    v: T
```

A word two threads may touch at once, and the operations that touch it indivisibly.

The operations underneath are the language's -- nine forms in the raw tier, each one machine
instruction (`library/sync.md`) -- and every one of them takes an **address**. So this is an
ordinary struct with a field and methods that take the address of it, which is the whole of what
"built on `*T`" means, and it is why the type a program reaches for lives here in the library
where a reader can see what it is made of rather than inside the compiler where they cannot.

**`T` is an integer of 8, 16, 32 or 64 bits, or a pointer** -- what the machine has an instruction
for. Nothing here declares a bound saying so, because there is no trait that means "a width a
processor can touch indivisibly": sysl's integers are an open family, so `u12` is a type a program
may name and no `impl` list could have covered. What refuses `Atomic[u12]` is the form inside,
where the instruction would have had to be chosen, and it names the widths in its message. The
arithmetic members are refused on a pointer for the same kind of reason and in the same place.

**Reading `a.v` directly is an ordinary non-atomic read.** The field is not hidden, because a
thread that knows it is alone with the value -- the one that built it, the one left after every
other has been joined -- is entitled to the cheap read, and hiding it would only mean a method
that does the same thing less visibly. It is a race everywhere else, and it is greppable, which is
the bargain the whole `*T` tier makes.

| Member | Signature | Description |
|---|---|---|
| `load` | `load(*self, ord: Ordering = SeqCst) -> T` | Reads the word. |
| `store` | `store(*self, v: T, ord: Ordering = SeqCst)` | Writes the word. |
| `swap` | `swap(*self, v: T, ord: Ordering = SeqCst) -> T` | Writes the word and answers what was there before. |
| `cas` | `cas(*self, expected: T, desired: T, ord: Ordering = SeqCst) -> T` | Writes `desired` if the word is `expected`, and answers the value it **found** either way. |
| `add` | `add(*self, n: T, ord: Ordering = SeqCst) -> T` | Adds to the word and answers what was there before, which is what makes an atomic increment usable as a ticket: every caller gets a different number and none of them is skipped. |
| `sub` | `sub(*self, n: T, ord: Ordering = SeqCst) -> T` | Subtracts from the word and answers what was there before. |
| `and` | `and(*self, n: T, ord: Ordering = SeqCst) -> T` | Bitwise-ands into the word and answers what was there before. |
| `or` | `or(*self, n: T, ord: Ordering = SeqCst) -> T` | Bitwise-ors into the word and answers what was there before, which is how a flag is set in a word other threads are setting their own flags in. |
| `xor` | `xor(*self, n: T, ord: Ordering = SeqCst) -> T` | Bitwise-xors into the word and answers what was there before. |

### `Ordering`

```sysl
enum Ordering
    Relaxed
    Acquire
    Release
    AcqRel
    SeqCst
```

How strongly an atomic operation is ordered against the accesses around it (`library/sync.md`).

An ordering is not a property of the value being read or written -- every ordering reads the same
bits -- it is a promise about what *else* is guaranteed to have happened. That is why it is an
argument rather than a property of the variable: the same word is read with `Relaxed` in a
statistics counter and with `Acquire` in the handoff that publishes a structure, and only the
operation knows which it is.

**These are C11's and LLVM's, named the same way**, so the machine documentation and the standards
text a reader already has apply to them unchanged. Nothing weaker than the machine's own model is
described here, and nothing here is sysl's invention.

The compiler requires the ordering to be written as one of these names at the call, because it
becomes a keyword in the instruction rather than a value the instruction reads. See `Atomics`.

| Member | Signature | Description |
|---|---|---|
| `orders_a_load` | `orders_a_load(self) -> bool` | Whether a **load** can be ordered this way. |
| `orders_a_store` | `orders_a_store(self) -> bool` | Whether a **store** can be ordered this way. |

### `SpinLock`

```sysl
struct SpinLock
    held: i32
```

Mutual exclusion for code that cannot block, held by spinning rather than by sleeping.

**This is the lock a kernel has before it has a scheduler.** Blocking means handing the processor
to something else, which means there is something else to hand it to; the allocator, the
scheduler's own run queue, and an interrupt handler are all code that has to take a lock before
any of that exists, and this is what they take. `sysl.sync` requires no capability, so a module
under `no alloc` and `no os` can reach it -- which is the whole reason the module is split this
way (`library/sync.md`).

**A thread that spins burns its processor for as long as it waits**, so this is right only where
the hold is short and bounded -- a few instructions under the lock, no allocation, no system call,
and above all no second lock. Where a wait may be long, or where the holder might be descheduled
mid-hold, the answer is a `Mutex[T]` and a real blocking primitive underneath it. On one processor
with no preemption a spin is a deadlock outright: nothing can release what nothing else is running
to release.

**It guards nothing by construction**, unlike `Mutex[T]`, which owns what it protects. A spinlock
is a flag beside the data, and what the data is stays the programmer's to remember -- again as in
C, and for the same reason: the code that needs this is code that is also reaching through raw
pointers, where a type that owned its contents would have nothing coherent to own.

| Member | Signature | Description |
|---|---|---|
| `lock` | `lock(*self)` | Takes the lock, spinning until it is free. |
| `try_lock` | `try_lock(*self) -> bool` | Takes the lock if it is free, and answers whether it did. |
| `unlock` | `unlock(*self)` | Releases the lock. |
