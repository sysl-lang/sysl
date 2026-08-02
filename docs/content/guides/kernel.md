---
title: kernel
summary: The same scheduler with no heap — a fixed table, indices for identity, and a measurement of what references were buying.
weight: 90
---

A priority scheduler with no heap: the same machine as [scheduler](/guides/scheduler/), built out of
a fixed table and index numbers instead of `&T` and a run-time allocator.

**The axis: a program that allocates nothing.** Every task, every lock, every list and the trace of
the run live in one struct the checks declare as an ordinary local, so the whole machine is a few tens
of kilobytes of storage decided while compiling. Nothing in it makes a `&T`, a `Buf` or a `string` —
one function is the exception, and it is the seam where the machine hands its answer to the reporting.

**It is written to be compared, not just to work.** The shared scenarios produce byte-identical
schedules to the scheduler's, so the two programs check each other and the difference between them is
a *measurement* of what reference counting was buying.

## Why it cannot say `no alloc`

It is checked rather than asserted: in the emitted code, no function of the machine calls an
allocator. The [`no alloc` clause](/reference/modules/) would say that in the source instead, and this
is the program where it does not fit — a capability is a property of the **module**, both files here
are the root module, and the checks build a string on nearly every line. Even the machine's own file
would be refused, because the seam that renders the trace is in it. [bytecode](/guides/bytecode/)
carries the clause instead, and the only difference is that its machine already lived in a module of
its own. [slab](/guides/slab/) sits in exactly the same position.

## What the difference turned out to be

**An index carries nothing, so the table travels with it.** A `&Task` *was* a task; a `TaskId` is a
number that means something only beside the table. So every method of the run queue takes the table as
a parameter — and not because it wants to look at the tasks, but because the queue's own links live
inside them. **A data structure that owns no storage cannot be asked a question on its own.**

**There was no local name for the task being worked on, and this program is what asked for one.**
Binding a copy meant every read and every write repeated the path from the table: thirteen occurrences
of one subscript in a single function, where the scheduler next door wrote one name. Taking its
address would have given the name back as a `*Task` — the mode this program exists to do without, and
one that drops the bounds and `within` checking that made the indices worth having.

Answered by [`ref`](/reference/memory/): the eighty-three paths through the two tables became
forty-three, the walk is made once, the subscript is bounds-checked at the binding instead of at each
use, and the name stays inside every check a written-out path gets — which is the half a pointer could
not have. **What it costs here is nothing at all**, and that is a property of this program rather than
of the feature: the rule holding storage still while a ref stands on it asks only about steps that
*own* what they point at, and a fixed table reached through a raw receiver has none.

**A subtype bounds the slot but cannot spell "no slot".** `TaskId` is `u8 within 0..<200`, so a number
that is not a task cannot be made into one and every table index is in range by construction. What it
cannot do is carry the sentinel a kernel would have used — 255 is not a `TaskId` — so an empty link is
`Option[TaskId]`, a tag beside the byte. The tag is the honest version and the checking is worth the
byte; it is just not the representation the technique is famous for.

**Three bounded identities cannot be confused, and that is the payoff.** A task number, a lock number
and a priority level are three `u8`s with three ranges, and because `within` subtypes are `new` they
are three *types*. Handing a lock number to something expecting a task is the bug a table-driven
kernel actually makes, and here it does not compile.

**An array bound and a `within` bound name the same `const`.** The table's size and the range of what
may index it are one fact written once, so the two cannot drift apart. A bound is a constant
*expression*, folded like an array bound and an enum discriminant, so this is as ordinary as the array
beside it — and the last magic number is gone.

**A method on a table entry copies the entry.** A receiver is a value and there is no reference to
take, so calling a method on a table slot would copy a task with its whole program in it. The
predicates are therefore free functions over the state enum, and every method of the machine takes a
raw `self` including the ones that only read.

**Storage that is never allocated is never freed either.** The one thing that gives anything back in a
program like this is a frame at `return`, which is why every section of the checks is a function
rather than a run of statements.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/kernel) ·
Next: [datetime](/guides/datetime/) — a conversion that can succeed twice.
