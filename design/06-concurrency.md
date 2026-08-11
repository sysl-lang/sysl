# Concurrency

**Status:** model decided, and the half the language has to enforce is built — `&sync T` and what
it may point at, and the nine atomic operations below it. Of the library surface this document
defers, `sysl.sync` and `sysl.thread` are built and the channel is not, which leaves the crossing
rule as specification with nothing checking it. It rests on the memory model — the whole design
follows from one fact about it, stated first.

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
- a `&sync T` (below);
- a `*T`, which carries no count to make atomic. It is the unsafe tier, and "How strong this is"
  below already names it as one of the two ways a program shares on purpose — leaving it off this
  list would have made that sentence untrue.

What may not cross is a plain `&T`, a `weak T`, or any type containing one: those carry a
non-atomic refcount, and two domains touching it is exactly the race the model exists to
prevent. This is Swift's `Sendable`, derived the way Swift derives it for value types —
structurally, from the fields — but without the protocol, because sysl needs no user-written
conformances for it.

**Where this rule is enforced is a channel**, and channels are library surface this document
defers — so the crossing rule is specification and has no check behind it yet. What *is* built is
the stricter rule the next section states, because that one has a place to be asked: the point a
`&sync T` is written.

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

Mixing them is refused by the rule that two unrelated types do not convert, which is true and says
nothing a reader wants to know — so it has a complaint of its own, naming the allocation as the
place the choice was made and the spelling to make it at.

For a `&sync T` to be sound, **`T` must itself be shareable**: every field crossable, and no
plain `&T`, `weak T`, slice, or string among them. A heap-backed string inside a shared object
would put a non-atomic buffer refcount under two threads — the same race one level down.

**An immortal string would be safe and is refused anyway, because nothing in the type says which
kind it is.** An earlier draft of this paragraph said immortal strings were fine, "which covers the
common case of a name or a fixed message" — and that sentence presupposed a distinction the
language does not draw. A `string` is one type whether its bytes are a literal's or a buffer's;
which it holds is a property of the value, decided at run time by whether the owner word is null
(`03`). So the rule as drafted could not be applied to a *type*, and the strict half is what is
enforced: a shared object holds no view at all. The same is true of `[]T`.

What would lift it is not a rule about strings but a view whose type records something about the
storage it views. `07`'s **read-only view type** is now built — `[]const T` — and it does **not**
lift this, which is worth stating plainly because an earlier draft of this paragraph assumed one
type would answer both.

**The two facts sit on different sides of the view.** Whether a view may be written through is a
property of the *view*: it can be given up, so a `[]T` widens into a `[]const T` and the bit costs
nothing. Whether the elements are immortal, and whether their owner's count is atomic, are
properties of the *owner* — and the paragraph above has already said what that means here, that
which kind a `string` holds is decided at run time by whether the owner word is null. A view cannot
describe that about itself; it could only report it, and a report is what a type is not. So a shared
object still holds no view at all, and what this waits on is a question about owners rather than one
more bit on a view.

**The walk stops at two places, and both are the same argument ARC already makes.** A `&sync U`
inside a shared object is a leaf, because whatever *that* type reaches was settled where it was
written; a `*U` is a leaf because it has no count. Everything else — fields, array elements, tuple
parts, every variant's payload — is walked, and the complaint names the whole path to what is in
the way, since naming only the type would leave a reader searching a struct they may not have
written.

**A trait object is asked where the type it forgot is known.** `&sync Show` says nothing about
what it points at, so the question cannot be asked at the type; it is asked at each point a value
is erased into one, and what it names is the concrete type. **A closure literal is asked about its
captures** — it is a struct whose fields are exactly what it captured (`12 §8`), so it needs no
rule of its own, only a complaint that says "closure" and "captures" rather than the name the
compiler filed it under.

**A generic pointee is a question about the argument, not the parameter.** `&sync Box[int]` shares
and `&sync Box[&Inner]` does not, and each instantiation resolves the type again, so each is
asked separately.

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

**Letting go of the last one is the place where an atomic count is not the whole answer.** A zero
hands the object to the iterative reaper, and the worklist that reaper drains is **per thread**
(`03 § Teardown is iterative`) — otherwise two threads dropping two unrelated shared objects at the
same moment would each overwrite the other's list. The counts cannot help with that: they are what
got both threads to the reaper correctly, each having genuinely released the last reference to a
different object.

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

**The operations themselves are the language's, and the types over them are the library's.** Nine
forms sit beside `sizeof` and `ptr_cast` in the raw tier, each one machine instruction that no sysl
body could write:

```
atomic_load(p, ord)                  atomic_swap(p, v, ord)
atomic_store(p, v, ord)              atomic_cas(p, expected, desired, ord)
atomic_add / _sub / _and / _or / _xor(p, v, ord)
atomic_fence(ord)
```

Each takes an address, so `Atomic[T]` and `SpinLock` are ordinary structs with a field and methods
that take the address of it — which is what "built on `*T`" means, and what keeps every type in the
library where a reader can see it. Every read-modify-write answers the value that was **there
before**, which is the property that makes an atomic increment usable as a ticket; `atomic_cas`
answers the value it **found**, so a caller comparing it against what they expected learns whether
it swapped.

**What may be reached is what the machine has an instruction for**: an integer of 8, 16, 32 or 64
bits, or a pointer. sysl's integers are an open family, so `u12` is a type a program may name and no
processor can touch indivisibly — it is refused where it is written rather than at the assembler. An
aggregate is what a lock is for.

**An ordering is written at the call, always.** `sysl.sync` declares the five C11 names — `Relaxed`,
`Acquire`, `Release`, `AcqRel`, `SeqCst` — and the form requires one of them *spelled there*, because
it becomes a keyword in the instruction rather than a value the instruction reads. An ordering held
in a variable is well-typed sysl that cannot be lowered, so it is refused with that reason. The
defaults belong on the library surface above, where a reader can see which one they are getting.
This is also the whole of the gate: the names live in a module, so a program that never imported it
has no ordering to write, and none of the nine takes a name a program has declared for itself.

**A load and a store take three of the five.** A release publishes the writes that came before it and
a load makes none; an acquire sees what a release published and a store reads nothing. So `Release`
and `AcqRel` name loads that do not exist, `Acquire` and `AcqRel` name stores that do not exist, and
each is refused at the call. Every read-modify-write does both halves and takes all five. This is a
fact about what the operations *are* rather than about any machine, and the narrowing is not
something a stronger processor would lift.

**`volatile` is not one of these, and the mistake is worth naming.** C's own reference material used
to recommend the qualifier for "shared-memory variables", and it is wrong: `volatile` constrains the
*compiler* — it stops accesses being elided, merged or reordered relative to one another — and says
nothing about other cores, about ordering, or about tearing. It is for device memory (`03 § Device
memory`) and for nothing else. Two threads sharing a counter want `Atomic[T]` from this list, or a
`&sync Mutex[T]` above it; a `volatile` counter is a race with a keyword in front of it.

**Refcount ordering.** Atomic retain is a relaxed increment — no ordering is needed to add a
reference you already hold. Atomic release is a decrement with release ordering, followed on
the zero transition by an acquire fence before the destructor runs, so every prior write from
every other domain is visible to the thread that frees. This is the standard sequence, and it
is written here because getting it wrong produces a bug nobody finds.

## The types above them

`sysl.sync` is the library module the forms are reached through, and it **requires no capability** —
which is the point of it being its own module rather than part of the standard one. A module under
`no alloc` and `no os` can import it and get `Atomic[T]` and `SpinLock`, because neither type
allocates, calls into an operating system, or panics: a violated precondition in there traps.

**`Atomic[T]`** is a struct holding one `T`, with the nine operations as methods that take the
address of the field. Its field is **not hidden**: a thread that knows it is alone with the value —
the one that built it, the one left after every other has been joined — is entitled to the ordinary
read, and hiding it would only mean a method doing the same thing less visibly. Every method takes a
`*self` receiver, including `load`, because a `self` receiver is handed a *copy* of the struct and
the address of a copy is not the address the other threads are writing to.

**An ordering on the surface is a parameter with a default.** `a.add(1)` is sequentially consistent
and `a.add(1, Relaxed)` names another, which is the only place in the design where the ordering is
not written at the call — and it is written at *this* call, one level up. Since the form below needs
a name and the method has a value, each method is a five-arm match from the one to the other, in the
shape `core::sync::atomic` uses for the same reason. **It costs nothing.** Measured on AArch64 at
`-O1`: the scrutinee is a constant at every ordinary call, so the whole dispatch folds and each
method call becomes the single instruction it names — `a.add(1)` is `ldaddal`, `a.add(1, Relaxed)`
is `ldadd`, `a.load(Acquire)` is `ldapr`, `a.store(v, Release)` is `stlr`.

**The narrowing on `load` and `store` lands at run time**, and it is the one check in the module that
does. The form refuses a releasing load where the name is *written* and cannot see a name that
arrived in a variable, which is exactly what a method taking an `Ordering` hands it — so `load` and
`store` carry a `require`, over the `orders_a_load` and `orders_a_store` predicates `Ordering`
declares for the purpose. It folds away with the dispatch. What it buys is that an ordering that
cannot be honoured **traps** rather than being quietly promoted to `SeqCst`: promotion is sound, and
it is not what the author asked for.

**`SpinLock`** is a flag and three methods — `lock`, `try_lock`, `unlock` — and it guards nothing by
construction, unlike the `Mutex[T]` above it. It takes with an acquire and releases with a release,
so it declares no `Ordering` parameter at all: a lock's orderings are fixed by what a lock means.
`lock` spins on a **relaxed load** once the exchange has failed, retrying the exchange only when the
word looks free, because a read-modify-write takes the cache line exclusively every time round and
waiters spinning on the exchange itself fight both each other and the holder trying to write the
release.

**A fence has no wrapper**, and the omission is deliberate: `atomic_fence(ord)` is the fence. A free
function beside it could add only the default, and the default is what it could not survive —
`Relaxed` is refused, so the wrapper would need a `Relaxed` arm whose only options are to call a form
that refuses it or to quietly do nothing.

## `sysl.thread` — spawning, joining, and the lock above the spinlock

The second module is where the capability lands. `sysl.thread` is `requires threads, posix`, and both
are written because neither implies the other: pthreads is what this is built on, and a bare-metal
target with a scheduler of its own has threads and no POSIX.

**`spawn(&work, &state)` takes the address of a function, not a callable.** A closure would have to be
boxed for the new thread to reach it, which needs the allocator, and its captures would be values
crossing a domain boundary with nothing yet checking that they may — so what it takes is `12 §6a`'s
`*extern`, which is what `pthread_create` has always taken. The argument is typed: `spawn` is generic
in what the body reads, so `T` is inferred from the body and the `ptr_cast` to C's shape happens once,
inside. `null` is the one thing that cannot be passed, since it takes its type from its context and
the context is the `T` being inferred; a body with nothing of its own is handed the address of
whatever it reads instead.

`Thread.join` waits and answers whether it waited. **It does not carry the body's result back**, and
that is the crossing rule rather than an oversight: a result coming out of another domain is a value
crossing a boundary, which is what a channel is for.

**`Mutex[T]` owns what it protects**, which is the difference against `SpinLock`. Both of its fields
are private, so there is no way to reach the value that does not go through `lock` or `try_lock` and
no way to build one already held — `Mutex.new(v)` is the only way in, because a private field puts
the positional constructor out of reach (`08 §`). **Releasing is still written**, and the reason has
changed without the conclusion changing: it used to be that the language had no destructor, and now
it is that a guard would have to be a `&T` to get one (`03 § A destructor`) — which means allocating
a box per `lock`, on the path where the whole point is to hold a lock for as few instructions as
possible. `defer m.unlock()` is the idiom, the same one `sysl.fs` uses for `close`.

**It is not built on `pthread_mutex_t`, and the reason is a build property rather than a preference.**
A caller-allocated opaque C type is one of the three things `15 §7` names as reachable from C and from
nothing else: its size is in a header, and it differs both between the platforms and between two
libcs on the same one — 64 bytes on Darwin, 40 under glibc on x86-64, 48 on aarch64, 40 again under
musl. `#if` can ask which operating system this is but not which libc, so a transcribed bound would
compile everywhere and be checked nowhere, which is the failure that section is about. The standard
module deliberately includes no headers, which is what lets it go on building for any target the
toolchain can lower for. So the lock is the atomic tier plus `sched_yield`: acquire on the exchange
that takes it, release on the store that frees it, and a relaxed load between attempts. What that
costs is a context switch per contended attempt where a futex would cost none, and a futex-backed
mutex is what a binding library carrying its own C shim would add.

**What crossing a domain is checked to be is still nothing**, and the thread API is where that becomes
visible rather than theoretical. `spawn` hands the new thread a `*T`, and a raw pointer is on the
crossable list on purpose — it carries no count to make atomic, and "How strong this is" names it as
one of the two ways a program shares deliberately. What a pointer points *at* is not examined, so a
plain `&T` reaches another thread through one with nothing said. That is the rule as written rather
than an escape from it, and it is why this API takes an address rather than a value: a `spawn` taking
a `T` would be claiming a check that does not exist yet.

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
| crossing a domain | — | **which values may cross** — specified structurally, and the check lands with the channel |
| refcount races | what a `&sync T` may hold, structurally | — |
| **mutating shared state** | — | **use a `Mutex`; nothing enforces it** |
| the kernel tier | — | `*T`, spinlocks, orderings — as in C |

The first row is the one to read twice, because it is the row that will change. What may cross is
decided structurally and there is nothing to write, but there is also nothing yet *asking* the
question: the thread API hands the new thread an address, a raw pointer is crossable on purpose, and
what it points at is not examined. So a plain `&T` reaches another thread today with nothing said.

So: **a data race requires you to have shared something on purpose**, and until the channel is
written that is a property of what the spellings make visible rather than one the compiler enforces.
Sharing takes `&sync` or `*T`, both of which are greppable and neither of which is what an ordinary
value is; what you cannot yet be *stopped* from doing is pointing one of them at something whose
count is not atomic. You can also write a race by putting a mutable field in a `&sync T` and racing
on it, and that one is permanent — it is the cost of not having a borrow checker. The trade is the
same one the whole language makes, and the same discipline applies.

## Deferred

- **`Channel`** is library surface and is not specified here; this document fixes only what the
  language must know. The atomic *operations* it will be built from are the language's and are above,
  in "The kernel tier". `Atomic[T]`, `SpinLock`, `Mutex[T]` and the thread API are built, and what is
  said of them in the two sections above is there because each answers a question this document
  asked — where the defaults live, what a module requiring no capability can hold, and what the
  crossing rule amounts to while nothing checks it.
- **Whether `&sync` should be inferable.** An object allocated, never crossed, and provably
  domain-local could use non-atomic refcounts even when its type says `sync` — the same shape
  of analysis as `05`. Worth revisiting once there is something to measure.
- **Cancellation and shutdown.** Structured lifetimes for threads and channels — who wakes a
  blocked receiver, and how a server drains — is a library design question that should be
  settled before the first server is written on top of this.
- **`weak sync T`.** `weak T` is built (`03`) and its atomic counterpart is not, because upgrading
  one is a compare-and-swap loop against a strong count another thread may be driving to zero, and
  nothing can race with it until this chapter is. It is refused where it is written, naming this
  document. Whatever lands here has to say what an upgrade racing a release means.

- **A view that records something about its storage.** Shared above, with `07 § Not yet` — the same
  missing type is what an immortal string and a `&sync` buffer's slice both want.
