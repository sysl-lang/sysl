# Concurrency

**Status:** model decided. This closes the last item `03` left open. It rests on the memory
model — the whole design follows from one fact about it, stated first.

## The constraint that decides everything

`&T` is **mutable through any alias**. That is the deliberate simplification over Rust's
exclusivity rule and the reason this language is easier than Rust. It also means sysl **cannot
make shared mutable state safe**: two threads holding one `&T` is a data race, and no analysis
available here will catch it. Rust catches it by proving exclusive access; Swift 6 catches it
by proving actor isolation plus region disjointness. sysl has neither and is not going to grow
one.

So the model cannot be *checked sharing*. It has to be **isolation**: make sharing rare,
deliberate, and visible rather than safe. That is the same conclusion Swift reached, arrived at
from the opposite direction — and it is a conclusion the target architecture had already
reached on its own.

**A microkernel is already an actor system.** SLIX servers are separate processes with separate
address spaces exchanging IPC messages. Nothing is shared because nothing *can* be shared. The
language should recognize that structure rather than build a weaker one beside it.

## Domains

A **concurrency domain** is a thread of execution. Values reachable from only one thread are in
one domain, and everything in this document is about the boundary between two.

| Boundary | Shares memory? | Isolation |
|---|---|---|
| two threads in one process | yes | by rule — this document |
| two processes | no | total and free — pointers are meaningless across it |
| an interrupt handler and the code it preempts | yes | by rule, plus interrupt masking |

The interrupt case is the one neither Swift nor Go has to think about, and it is not special
here: an interrupt handler is another domain that happens to run on a borrowed stack. Sharing
with it needs the same care as sharing with a thread, and additionally needs the interrupt
masked while the shared thing is inconsistent.

## Crossing copies

**By default, a value crossing a domain boundary is copied.** Value types copy their bytes;
a `string` or a slice copies the bytes it views into the destination.

This is Erlang's rule and Minix's, and it is what process IPC does whether you ask for it or
not — two address spaces leave no alternative. Making a thread-to-thread channel behave the
same way means there is one rule instead of two, and it buys the thing that matters most:
**the default path needs no atomics at all.** Refcounts stay non-atomic, because a
non-atomically-refcounted object never has two threads touching it.

What may cross is decided structurally, with no trait system and nothing to write. A type is
**crossable** when it is:

- a scalar, `char`, `bool`, or `unit`;
- a struct, enum, or fixed array whose every field is crossable;
- an immortal slice or `string` — static data and literals, whose `owner` is null (`03`);
- a heap-backed slice or `string`, which crosses **by copying its bytes**;
- a `&sync T` (below).

What may not cross is a plain `&T`, a `weak T`, or any type containing one: those carry a
non-atomic refcount, and two domains touching it is exactly the race the model exists to
prevent. This is Swift's `Sendable`, derived the way Swift derives it for value types —
structurally, from the fields — but without the protocol, because sysl needs no user-written
conformances for it.

## `&sync T` — the deliberate exception

Copying is right for messages and wrong for a shared cache, a device registry, or a page table
that several kernel threads genuinely must see as one object. For those there is a second
reference spelling:

```
&T        // refcount is non-atomic — one domain only
&sync T   // refcount is atomic — may cross domains
```

The two are **distinct types with no conversion in either direction**. Downgrading would let a
non-atomic retain race an atomic one; upgrading would do the same in reverse. Atomicity is
fixed when the object is allocated, exactly as `Rc` and `Arc` are chosen at construction in
Rust — with the difference that here it is a sigil rather than a wrapper type, so it reads as
what it is and costs no ceremony (principle 3). `weak sync T` is the matching weak form and
pairs only with `&sync T`.

For a `&sync T` to be sound, **`T` must itself be shareable**: every field crossable, and no
plain `&T`, `weak T`, or heap-backed slice or string among them. A heap-backed string inside a
shared object would put a non-atomic buffer refcount under two threads — the same race one
level down. Immortal strings are fine, which covers the common case of a name or a fixed
message.

**`&sync T` makes the reference safe to share, not the object safe to mutate.** This is the
single most important sentence in the document. The refcount is atomic; the fields are still
mutable through any alias. Two threads writing the same field of a `&sync T` is a data race,
and nothing here will tell you. Shared *mutable* state goes through the library:

```
&sync Mutex[T]     // exclusive access, the ordinary answer
&sync Atomic[i32]  // a single word, lock-free
```

That is Rust's `Arc<Mutex<T>>` with one less layer of spelling, and it is a convention rather
than a checked rule — see "How strong this is."

## Channels

Domains talk by **channel**, one type with two implementations underneath:

- between threads in a process — a queue plus a lock;
- between processes — the kernel's IPC, which SLIX already has.

Same shape either way, so code that moves from a thread to a server does not change. A channel
carries crossable values only, which is where the structural rule above is enforced.

A growable channel needs `alloc`. A **fixed-capacity** channel — a ring buffer sized at
declaration — does not, so allocator-free kernel code can still use one. That matters: it means
the message-passing idiom is available in the place least able to afford a runtime.

## Threads are a capability

Creating a thread needs an OS or a scheduler underneath it, so `threads` joins `os` and
`posix` as an **environment** capability, the extension `capabilities.md` already anticipated.
A target that has no scheduler does not offer it; a module may narrow it away to declare
itself single-threaded. Nothing about `&T`'s soundness depends on the capability — the crossing
rule carries that on its own — but a module that cannot spawn is a module whose author can stop
thinking about any of this.

## The kernel tier

Below channels sit the primitives a kernel actually runs on, in a library, available under
`no alloc`, built on `*T`:

spinlocks, interrupt masking, memory barriers, and the `Atomic[T]` operations with explicit
orderings. This is the `*T` tier of concurrency in the same sense `*T` is the unsafe tier of
memory: the compiler guarantees nothing, the code is auditable by grep, and it is how the
scheduler and the allocator are written. Kernel code does not use `&sync T`; it uses a raw
pointer and a spinlock, as C does.

**Refcount ordering.** Atomic retain is a relaxed increment — no ordering is needed to add a
reference you already hold. Atomic release is a decrement with release ordering, followed on
the zero transition by an acquire fence before the destructor runs, so every prior write from
every other domain is visible to the thread that frees. This is the standard sequence, and it
is written here because getting it wrong produces a bug nobody finds.

## No async/await

There is no `async`, no `await`, and no task runtime in the language.

Swift's version needs executors, continuations, and heap-allocated task state; Go's goroutines
need a scheduler and growable stacks. Neither can exist under `no alloc`, and the kernel is
exactly where threads are most real. Concurrency machinery belongs in a library that requires
`alloc` and `threads`, not in a language that has to compile a page-fault handler.

Two things fall out of that, both good. sysl has no **actor reentrancy** hazard — the trap
where an actor's state changes across an `await` — because there is no await-based
interleaving. And blocking is honest: a thread that waits is a thread that waits, with no
cooperative-scheduling model to reason about on top.

If async is ever wanted, the shape to adopt is **Rust's**, not Swift's: futures compiled to
state machines with no allocation inherent in the future itself, and the executor an ordinary
library. That is the only form of it that could reach the allocator-free subset.

## How strong this is

Honestly weaker than Swift 6 or Rust, and worth saying plainly rather than implying a
guarantee the language cannot keep:

| | Checked | Not checked |
|---|---|---|
| crossing a domain | which values may cross, structurally | — |
| refcount races | `&T` cannot cross; `&sync T` is atomic | — |
| **mutating shared state** | — | **use a `Mutex`; nothing enforces it** |
| the kernel tier | — | `*T`, spinlocks, orderings — as in C |

So: **a data race requires you to have shared something on purpose.** You cannot stumble into
one by passing an ordinary object to another thread, because that does not compile. You can
still write one by putting a mutable field in a `&sync T` and racing on it, and that is the
cost of not having a borrow checker. The trade is the same one the whole language makes, and
the same discipline applies — sharing is greppable, because `&sync` and `*T` are the only ways
to do it.

## Deferred

- **`Mutex`, `Atomic`, `Channel`, and the thread API** are library surface and are not
  specified here; this document fixes only what the language must know.
- **Whether `&sync` should be inferable.** An object allocated, never crossed, and provably
  domain-local could use non-atomic refcounts even when its type says `sync` — the same shape
  of analysis as `05`. Worth revisiting once there is something to measure.
- **Cancellation and shutdown.** Structured lifetimes for threads and channels — who wakes a
  blocked receiver, and how a server drains — is a library design question that should be
  settled before the first server is written on top of this.
