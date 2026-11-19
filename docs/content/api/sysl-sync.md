---
title: sysl.sync
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.sync
summary: "What two threads may touch at once: `Atomic[T]`, `SpinLock`, `SeqLock[T]`, and the five memory orderings."
requires: "no alloc"
---

An atomic is a word an operation reads, writes or exchanges indivisibly; an `Ordering` says how
strongly that operation is ordered against the ordinary accesses around it; and a `SpinLock` is
mutual exclusion for code that cannot block — an interrupt handler, a signal handler, a board with
no scheduler.

**This module requires nothing, which is what puts it below `sysl.threads` rather than
inside it.** The threads a program spawns need an operating system's scheduler; the ordering
rules are the machine's, and a freestanding program that shares a word with an interrupt needs
them exactly as much as a hosted one that shares it with a thread.

## Index

[`load_relaxed`](#load_relaxed) [`spin_hint`](#spin_hint) [`store_relaxed`](#store_relaxed) [`Atomic`](#atomic) [`Guarded`](#guarded) [`IrqLock`](#irqlock) [`Ordering`](#ordering) [`SeqLock`](#seqlock) [`SpinLock`](#spinlock) [`Lock`](#lock) [Lock for IrqLock](#lock-for-irqlock) [Lock for SpinLock](#lock-for-spinlock)

## Functions

### `load_relaxed`

```sysl
load_relaxed[T: Plain](src: *T) -> T
```

Reads the value at `src` a word at a time, each word one relaxed atomic load, and answers it.

**This is the read another thread may be writing over while it runs**, and it is not a data race:
every word is an atomic access, so the memory model gives each one some value a write stored
there, and a plain copy of the same bytes would be undefined behaviour instead. What it does not
give is a value **all of one write** -- two words may come from two different writes, which is a
torn value. Something has to say whether that happened, and that is what a sequence number is for:
`SeqLock[T]` is this read with the check around it, and the reason the read exists.

**`T` is plain data** (`sysl.encoding.Plain`): integers, floats, fixed-point numbers, `bool`s,
simple enums, and arrays and structs of them. Every byte of a torn value then still came from some
value of its field, so holding one is harmless until it is thrown away. A counted reference, a
string or a view is refused at the line that names the type: a copy of its bytes would be a second
owner nobody counted. The answer is built in a local that starts at `T`'s zero value, so a `T`
with none -- a simple enum none of whose variants is `= 0` -- is refused as well.

**A word is as wide as the type's alignment allows**, up to the machine's pointer: a struct of
`u64`s is copied in 64-bit loads on a 64-bit machine and in 32-bit ones on a 32-bit one, and a
struct of bytes a byte at a time. The width is chosen while compiling, so the copy is a straight
run of loads.

No ordering is offered, because none would be right: an acquire on every word orders the words
against each other, which the caller cannot use, and costs a barrier per word on a weak machine.
The ordering a copy needs is around it -- an `atomic_fence(Acquire)` after it, or a release fence
before `store_relaxed` -- and `SeqLock` places both.

### `spin_hint`

```sysl
spin_hint()
```

Tells the processor that the loop around this call is waiting for another processor to change
something, and does nothing else.

Each machine has an instruction for exactly this: `yield` on AArch64 and Thumb, `pause` on x86 and
on RISC-V (Zihintpause, encoded so that a core without the extension runs it as a fence that
orders nothing). A core that shares its pipeline with another hardware thread gives that thread
the cycles, a hypervisor may run another virtual processor, and an x86 core stops its speculative
loads from filling the pipeline it then has to flush when the line changes. It costs a handful of
cycles where it means nothing, so it belongs in the body of every busy-wait — `SpinLock.lock`
calls it on each round of its read-only spin.

**It is not a wait.** Nothing sleeps, no event is awaited, and the loop still has to load the
word it is waiting on; AArch64's `wfe`, which does sleep until the exclusive monitor sees the line
change, needs that load to be a load-exclusive, which is a kernel's own loop to write. A wasm
module has no such instruction, and there it is nothing at all.

### `store_relaxed`

```sysl
store_relaxed[T: Plain](dst: *T, v: T)
```

Writes `v` to `dst` a word at a time, each word one relaxed atomic store.

The other half of `load_relaxed`, with the same words, the same bound on `T`, and the same reason
for being: a reader on another thread may be copying `dst` while this runs, and with every word an
atomic store neither side races. A reader may see some words of `v` and some of what was there,
which is the torn value a sequence number exists to catch.

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

**`T` is an integer of 8, 16, 32 or 64 bits, a `bool`, or a pointer** -- what the machine has an instruction
for. Nothing here declares a bound saying so, because there is no trait that means "a width a
processor can touch indivisibly": sysl's integers are an open family, so `u12` is a type a program
may name and no `impl` list could have covered. What refuses `Atomic[u12]` is the instantiation, at
the line that names it, and it names the widths in its message. A `bool` is held as the byte it is
stored in and takes the bitwise members but not `add` or `sub`. The
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

### `Guarded`

```sysl
struct Guarded[L: Lock, T]
    private lock: L
    private value: T
```

A record and its own lock, stored together, with **the record reachable only while the lock is
held**.

**This is the shape of a table whose every entry has a lock of its own** -- a buffer cache, an
inode table, a page cache -- and it is a value, not a box: the lock is laid out inline beside the
record, so `[64]Guarded[SpinLock, Inode]` is sixty-four locks and sixty-four inodes in one array,
with no allocation and no count anywhere but inside the records themselves.

**`@guards(value)` is what lets such a table cross into another domain** whatever its records
hold (`reference/memory.md § A guarded value crosses with its lock`): both fields are private, so
sharing the table shares the locks and not the records, and an array is shared element by
element. What leaves through `with` or `try_with` is held to the `&sync` rule at the call -- an
`int` or a `.copy()` of a string, not a `Buf` still sharing a count with the one inside.

```
cache[i].with((b) -> b.dirty = true)
```

**`sysl.threads.Mutex[T]` is this with a `SleepLock`**: the lock is any `Lock`, so a board with no
scheduler uses the same type a hosted program's mutex is built on. `new` is `const`, so a module
table of them filled by `Guarded.new` calls is constant data even on a freestanding target.

`lock_raw` and `unlock_raw` are the hold a block cannot express -- taken in one function and
released in another, or let go and taken again around a `Condvar.wait`. Nothing ties the address
they hand out to the hold, which is what the name says; where the hold fits a block, `with` is the
one to write.

| Member | Signature | Description |
|---|---|---|
| `new` | `const new(lock: L, value: T) -> Guarded[L, T]` | A record behind a lock, which must be free. |
| `with` | `with[R](*self, body: *T -> R) -> R` | Takes the lock, waiting however `L` waits, runs `body` with the address of the record in place, releases the lock on every way out, and answers what `body` answered. |
| `try_with` | `try_with[R](*self, body: *T -> R) -> Option[R]` | The same, if the lock can be taken without waiting: `Some` of what `body` answered, or `None`. |
| `lock_raw` | `lock_raw(*self) -> *T` | Takes the lock, waiting however `L` waits, and answers the address of the record. |
| `try_lock_raw` | `try_lock_raw(*self) -> Option[*T]` | Takes the lock if it can without waiting, and answers the record's address where it did. |
| `unlock_raw` | `unlock_raw(*self)` | Releases the lock a `lock_raw` or `try_lock_raw` took, publishing everything written through the address to whoever takes it next. |

### `IrqLock`

```sysl
struct IrqLock
    saved: u32
```

Mutual exclusion on one Cortex-M core, held by masking interrupts rather than by spinning.

**This is the lock an Armv6-M core has.** It has no exclusive pair, so it cannot take a
`SpinLock`'s flag; what it can do is stop being interrupted. `lock` saves PRIMASK and runs
`cpsid i`, `unlock` puts PRIMASK back as it was, so holds nest: an inner hold taken with interrupts
already masked leaves them masked when it lets go. Nothing waits, so `try_lock` always succeeds.

**It excludes interrupt handlers, not other cores.** Code on a second core runs straight through
it, so it is right only where everything that touches the data runs on the core taking the lock --
the main loop and that core's handlers. A dual-core part (an RP2040) needs its hardware lock,
through `Guarded[L, T]`.

It exists on every Cortex-M target sysl builds for (Armv6-M, Armv7-M, Armv8-M); a program reaching
it for any other machine is refused.

| Member | Signature | Description |
|---|---|---|
| `lock` | `lock(*self)` | Masks interrupts, remembering whether they already were. |
| `try_lock` | `try_lock(*self) -> bool` | Takes the lock, which never has to wait, and answers `true`. |
| `with` | `with[R](*self, body: () -> R) -> R` | Takes the lock, runs `body`, releases it, and answers what `body` answered. |
| `unlock` | `unlock(*self)` | Puts PRIMASK back as `lock` found it. |

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

### `SeqLock`

```sysl
struct SeqLock[T: Plain]
    private seq: usize
    private value: T
```

A value many threads read and a few write, where a reader never waits for another reader and
never makes a writer wait.

**The value sits beside a sequence number.** A writer makes the number odd, writes the value, and
makes it even again; a reader notes the number, copies the value, and looks at the number again. If
it was odd, or has moved, a writer was in the middle and the copy may be torn, so the reader copies
again. A copy the number did not move across is all of one write. Readers write nothing, so a
hundred of them on a hundred cores never contend for the line the way a lock's readers do; what
they pay is a retry when a write lands under them. That makes it the shape for a value read far
more often than it is written -- a clock's calibration, a sensor's latest sample, a game's frame
state -- and the wrong one for a value written constantly, where readers starve.

**The copies are word-wise relaxed atomics** (`load_relaxed`, `store_relaxed`), so a reader copying
while a writer writes is not a data race, only a torn copy the recheck throws away. The fences are
the C++ model's:

- **reading**: an acquire load of the number, the relaxed copy, an acquire fence, a relaxed load of
  the number. A copy that saw any word of a write synchronises, through the writer's release fence
  and this acquire fence, with that write's first step, so the recheck sees the number it moved to.
- **writing**: the number incremented to odd, a release fence, the relaxed copy, a release store of
  the number incremented to even. A reader whose first load sees that even number sees every word
  before it.

**Writers exclude each other**: making the number odd is a compare-and-swap from an even value, and
a writer that finds it odd spins (`spin_hint`) until the writer before it has finished. So it is
safe with any number of writers, and fast with one.

**`T` is plain data** (`sysl.encoding.Plain`), for `load_relaxed`'s reason: a reader holds a torn
copy before it knows to throw it away, and a copy of a counted reference's bytes would be an owner
nobody counted. A struct holding a `&T`, a `string` or a slice is refused where it names the type.

| Member | Signature | Description |
|---|---|---|
| `new` | `const new(value: T) -> SeqLock[T]` | A lock holding `value`, with no write under way. |
| `read` | `read(*self) -> T` | A copy of the value that is all of one write, trying again for as long as writes land under it. |
| `try_read` | `try_read(*self) -> Option[T]` | A copy of the value that is all of one write, or `None` where a write was under way. |
| `write` | `write(*self, v: T)` | Replaces the value, waiting for a write already under way to finish first. |
| `writes` | `writes(*self) -> usize` | How many writes have finished, which is half the sequence number. |

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
| `with` | `with[R](*self, body: () -> R) -> R` | Takes the lock, runs `body`, releases the lock, and answers what `body` answered. |
| `try_with` | `try_with[R](*self, body: () -> R) -> Option[R]` | The same, if the lock is free: `Some` of what `body` answered, or `None` without spinning. |
| `unlock` | `unlock(*self)` | Releases the lock. |

## Traits

### `Lock`

```sysl
trait Lock
    acquire(*self)
    try_acquire(*self) -> bool
    release(*self)
```

What every lock does, whatever it waits by: take the lock, try to take it, let it go.

**A lock is told apart by how it waits, and nothing that holds one needs to know which.** A
`SpinLock` spins, a `sysl.threads.SleepLock` sleeps on a futex, and a kernel's own lock may mask
interrupts on the processor taking it -- and `Guarded[L, T]` is written once over all of them. The
trait asks for nothing a board lacks, so a package can implement it for a lock it defines itself.

**`acquire` returns with the lock held and everything the last holder wrote visible**, which is
an `Acquire` ordering on whatever word the lock takes, and **`release` publishes everything the
holder wrote** to whoever acquires next, which is a `Release` on it. That pairing is the whole of
what makes data behind the lock safe to touch, and it is the implementation's to keep.

`try_acquire` takes the lock only where it can without waiting, and answers whether it did. A lock
that never waits -- one that masks interrupts on a single processor -- answers `true` every time.

| Member | Signature | Description |
|---|---|---|
| `acquire` | `acquire(*self)` |  |
| `try_acquire` | `try_acquire(*self) -> bool` |  |
| `release` | `release(*self)` |  |

## Implementations

### Lock for IrqLock

```sysl
impl Lock for IrqLock
```

### Lock for SpinLock

```sysl
impl Lock for SpinLock
```
