# The Memory Model — Three Modes

**Status:** core model decided, including slice ownership and what the allocator-free boundary
gates. This is the heart of the language; the type, trait, and standard-library docs all rest
on it. A few sub-mechanisms (escape analysis, concurrency/atomic refcounts) are flagged open at
the end.

## The guarantee

**If you do not use `*T`, you cannot segfault.** The safe subset — value types, ARC
references, `weak` references, arrays, and slices — is memory-safe: no use-after-free, no null
dereference, no out-of-bounds access, no dangling pointers. The single unsafe primitive is the
raw pointer `*T`, and it is **greppable**: you can audit exactly where a program takes on
C-like risk. In a Minix-style OS that is the kernel's low-level core and the drivers, and
nowhere else — the servers, utilities, and applications never write `*T` and are segfault-proof
by construction.

## Why runtime safety, not Rust's compile-time safety

sysl gets memory safety from **ARC (automatic reference counting)** at runtime — a small,
predictable cost — deliberately in exchange for deleting the cognitive burden Rust charges:
**no lifetimes, no borrow checker, no move semantics.** A `&T` behaves like a Scala object
reference (alias it, pass it, mutate through it), except it is refcounted instead of
garbage-collected. That trade — small runtime cost for large simplicity — is the language's
reason to exist. It is *easier than Rust* by construction, because the hard part of Rust is
exactly the part sysl does not have.

## The three modes

### `T` — value

A value lives inline: on the stack, in a register, or embedded in an enclosing struct/array.
Assignment and argument passing **copy** the value, exactly like a C struct — there is **no
move semantics and no use-after-move**. If a value contains a `&T` field, copying it increments
that field's refcount, so the copy is independently safe. Value types need no allocator and are
always safe.

### `&T` — reference-counted (ARC)

`&T` is a reference to a heap object managed by ARC. It is:

- **shared and freely aliased** — many `&T` may point at the same object;
- **mutable through any alias** — no borrow checker, no exclusivity rule (Scala/Swift reference
  semantics);
- **non-null** — a `&T` always points at a live object (nullable is `Option[&T]`);
- **automatically managed** — the compiler emits retain/release; the object is freed the moment
  the last strong reference goes away.

**Creating** a `&T` needs a managed allocator (see Capabilities); **holding** one does not.
This is the pleasant default for application, server, and utility code, and — because of the
rule below — it is also what lets a driver hold a reference the bus manager created for it.

### Who frees it — the deallocation hook

Every ARC heap object carries, alongside its refcount, a **pointer to the function that frees
it**, installed by whoever allocated it. Release decrements; at zero it runs the destructor
(which the compiler emits inline, since every release site knows the static type) and then
calls through the hook.

One word per heap object buys three things:

- **ARC works the same everywhere.** A module that never allocates can still retain and
  release, because the free path calls back into the heap the object came from. Refcount
  operations are a few instructions and depend on no runtime.
- **Several heaps coexist.** A microkernel wants exactly this: the kernel heap, each server's
  heap, and an arena are different allocators, and an object frees itself into the one that
  made it, wherever it is dropped.
- **No boundary rule to learn.** Ownership crosses a `no alloc` edge like any other value.

The cost lands only where the feature is used. Value types, fixed arrays, and `*T` buffers
have no header at all, so kernel code written in the allocator-free subset pays nothing for a
mechanism it never touches.

### `*T` — raw pointer

`*T` is a bare machine pointer, exactly like C: no length, no refcount, no checks, manual
lifetime. Pointer arithmetic, `malloc` / `free`, MMIO addresses, and structure-walking live
here. **It is the only unsafe primitive** — the only way to produce a dangling or wild pointer
— and it needs no runtime. It is how a kernel, a driver, or the allocator's own internals are
written.

## Per-declaration, and why value is the default

Which mode a value uses is chosen **per declaration**, as in C — at each site you write the
value, a pointer, or (new in sysl) a reference. This is deliberately *not* the Swift / Scala /
Kotlin per-*type* split (`struct` value type vs `class` reference type): the per-declaration
form keeps C's per-site flexibility — the same struct type can be held by value in one place,
by reference in another, and by raw pointer in a third — which systems code relies on.

Within that model, **value (`T`) is the unmarked default, and the kernel is why.** A no-alloc
module cannot *make* a reference, so if the *reference* were the bare default, the unmarked
form would be the one spelling that is illegal exactly where value semantics are needed most —
every kernel struct would need a mark and hit "references need an allocator" on its most
natural spelling. Value-as-default keeps the kernel's common case unmarked (`T`), `*T` covers
pointers, and `&T` appears in kernel code only where something else handed it one. It is the
only assignment of the three where the unmarked default is usable in *both* worlds.

**Ergonomics of `&` in application code.** With type inference (Scala-style), `&` appears only
in explicit type positions — function signatures and struct fields — not on locals:

```
val p = spawn(parent)                  // inferred: no annotation, no &
spawn(parent: &Process) -> &Process    // & only at the boundary
```

So references are not scattered through the code; `&` lives at API boundaries, where
`&Process` usefully documents "shared reference, not a copy" — the same place Scala puts its
type annotations. Clean bodies, honest boundaries.

## `weak T` — breaking cycles

ARC cannot reclaim a reference cycle (A holds B, B holds A). The tool for back-references is
**`weak T`**: a non-owning reference that does *not* keep its referent alive. When the last
strong `&T` is gone, the object is freed and every `weak T` to it becomes empty. Because a weak
reference may already be gone, **accessing it yields `Option[&T]`** — a live strong reference
or `None` — and the compiler makes you handle the `None`, so a weak reference can never dangle.

Cycles are uncommon, but back-references do occur in systems code — a child's pointer to its
parent, the back-link of an intrusive doubly-linked list, a process's pointer to its parent
process. `weak T` expresses those safely; reach for it only when you have a genuine
back-reference. Needs an allocator.

## Null

References are **non-null**. There is no null pointer in the safe subset, so there is no null
dereference. A maybe-absent reference is `Option[&T]`, matched or unwrapped explicitly. (`*T`
may be null like any C pointer — part of its unsafety.)

## Bounds safety follows length, not pointers

The out-of-bounds hazard is governed by whether a value **carries its length**, independent of
allocation or "pointer-ness":

- **Arrays (`[N]T`) and slices** carry their length, so indexing is **bounds-checked in every
  context** — hosted or allocator-free kernel, over static, stack, or heap memory.
- **`*T` carries no length**, so it is the one unchecked primitive.

The practical consequence: even low-level, allocator-free code stays bounds-safe by using
slices; `*T` is reserved for genuine address work, not merely for having an indexable buffer.
Growable (appendable) arrays need an allocator; fixed arrays and slices do not.

## Slices keep their backing alive

A slice is **three words**, not two:

```
[]T = { owner: *Buf, ptr: *T, len: usize }        // 24 bytes on a 64-bit target
```

A bare `{ptr, len}` view can outlive the buffer it views — Go gets away with it because its
collector finds the object from an interior pointer, and sysl has no collector. So a slice
carries the owning reference itself: `ptr` and `len` name the range, `owner` keeps the bytes
alive. Taking a slice retains; dropping one releases. Slicing stays O(1) and allocation-free,
and "a slice never dangles" becomes true rather than aspirational.

`owner` is **null when there is nothing to keep alive** — a slice of static data, of a `*T`
buffer, or of a fixed array whose storage outlives every view of it. Retain and release on
such a slice are no-ops, so allocator-free code slicing a static or stack buffer pays nothing
at all. This is the same immortality rule string literals use (`04`), generalized.

A `string` is then exactly an **immutable, validated `[]u8`**, and the two share one
representation and one implementation.

**Slicing something that would not outlive the slice.** If a local fixed array is sliced and
the slice escapes the frame, the array is promoted to an ARC buffer so the slice's `owner` has
something to hold — the same escape analysis Go performs, done by the compiler with no
annotation in the source. That is the ordinary case. Under `no alloc` there is nothing to
promote it into, so the escape is a compile error naming the array and the slice that outlives
it; the fix there is to make the storage static or to pass the slice down rather than out.
This analysis is a compiler-internal one, not a lifetime system the programmer writes.

## Shared mutability and concurrency

`&T` permits aliasing and mutation through any reference — the deliberate simplification over
Rust's exclusive-mutability rule. This is safe under a single-threaded assumption. Cross-thread
sharing (atomic refcounts, data-race prevention) is a **later topic**: shared `&T` across
threads will need atomic retain/release, and the concurrency model is not yet designed.

## The two worlds, one language

- **App / server / utility:** `T` + `&T` (+ `weak`, slices, arrays). Safe, pleasant,
  ARC-managed. The bulk of an OS.
- **Kernel / driver / allocator-free:** `T` + `*T` + fixed arrays + slices + manual `malloc` /
  `free`. A module that never *creates* a `&T` emits no allocation and no allocator
  dependency, exactly like C, and stays bounds-checked wherever it uses arrays and slices.

The boundary is not a convention — it is compiler-enforced through the allocator capability.

**What the boundary gates is allocation, not ownership.** Allocator-free code may hold, pass,
copy, and drop a `&T` or a heap-backed slice that something else created: retain and release
are a few instructions, and the free path goes through the object's own deallocation hook.
What it may not do is *make* one. This is what allows a driver to keep the `&Device` its bus
manager handed it, and a `no alloc` parser to be handed a heap-backed slice and read it —
neither of which is expressible if ownership stops at the boundary.

## Capabilities (the allocator, and orthogonal environment facts)

The presence of a **managed allocator** is a first-class capability that gates the operations
that *allocate*: creating a `&T`, growing an array, and anything in the library that returns
newly-made storage. A module declared **`no alloc`** makes those a compile error, leaving `T`,
`*T`, fixed arrays, slices, and whatever references it was given — the allocator-free subset.
Holding and releasing an already-allocated object stays legal there (see "The two worlds").

This is **orthogonal** to whether an OS or POSIX layer is present (which gate the
standard-library / syscall surface). Unlike Rust's `no_std` — which bundles "no allocator," "no
OS," and "no conveniences" together and forces embedded-with-a-heap to claw `alloc` back — sysl
keeps them independent switches: bare-metal-with-a-heap is "alloc yes, os no."

The full mechanism — `alloc` as a type-checker-enforced *language* capability vs `os`/`posix`
as import-gated *environment* capabilities, the target-provides / module-narrows two-level
model, and propagation through imports — is specified in **`capabilities.md`**.

## Hazard summary

| Segfault source | Prevented in the safe subset by |
|---|---|
| use-after-free / double-free | ARC on `&T`; `weak` degrades to `Option`, never dangles |
| null dereference | non-null references; nullable is `Option` |
| out-of-bounds | length-carrying arrays/slices, checked everywhere |
| slice outliving its buffer | the slice's `owner` word retains it; escaping locals are promoted |
| dangling / wild pointer | impossible without `*T` |

Only `*T` opts out — visibly.

## Open sub-questions

- ~~Capability mechanism~~ — **done**, see `capabilities.md` (`alloc` type-checker-enforced,
  `os`/`posix` import-gated; target provides + module `no alloc` narrows; propagated through
  imports).
- ~~Slice ownership and the allocator-free boundary~~ — **done**, above: slices carry an
  `owner` word, ARC objects carry a deallocation hook, and `no alloc` gates allocation rather
  than ownership.
- **Escape analysis** — the promotion rule above is stated but not specified: exactly which
  escapes are detected, what the `no alloc` diagnostic says, and whether promotion is ever
  silent enough to be surprising. Needs its own pass.
- **Concurrency** — atomic refcounts for cross-thread `&T`, and the data-race story. Not yet
  designed.
- **Unchecked-index escape hatch** — an opt-out of bounds checking for hot loops (default
  checked). Likely yes, deferred.
- **`weak` runtime** — the exact weak-tracking representation (side table vs in-box header).
