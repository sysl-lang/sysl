# The Memory Model — Three Modes

**Status:** core model decided. This is the heart of the language; the type, trait, and
standard-library docs all rest on it. A few sub-mechanisms (the capability system,
concurrency/atomic refcounts) are flagged open at the end.

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

`&T` needs a managed allocator (see Capabilities). This is the pleasant default for
application, server, and utility code.

### `*T` — raw pointer

`*T` is a bare machine pointer, exactly like C: no length, no refcount, no checks, manual
lifetime. Pointer arithmetic, `malloc` / `free`, MMIO addresses, and structure-walking live
here. **It is the only unsafe primitive** — the only way to produce a dangling or wild pointer
— and it needs no runtime. It is how a kernel, a driver, or the allocator's own internals are
written.

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

- **Arrays (`[N]T`) and slices (`[]T`, a `{ptr, len}` view)** carry their length, so indexing
  is **bounds-checked in every context** — hosted or allocator-free kernel, over static, stack,
  or heap memory. A slice is only a view; creating one needs no allocator.
- **`*T` carries no length**, so it is the one unchecked primitive.

The practical consequence: even low-level, allocator-free code stays bounds-safe by using
slices; `*T` is reserved for genuine address work, not merely for having an indexable buffer.
Growable (appendable) arrays need an allocator; fixed arrays and slice-views do not.

## Shared mutability and concurrency

`&T` permits aliasing and mutation through any reference — the deliberate simplification over
Rust's exclusive-mutability rule. This is safe under a single-threaded assumption. Cross-thread
sharing (atomic refcounts, data-race prevention) is a **later topic**: shared `&T` across
threads will need atomic retain/release, and the concurrency model is not yet designed.

## The two worlds, one language

- **App / server / utility:** `T` + `&T` (+ `weak`, slices, arrays). Safe, pleasant,
  ARC-managed. The bulk of an OS.
- **Kernel / driver / allocator-free:** `T` + `*T` + fixed arrays + slice-views + manual
  `malloc` / `free`. No ARC runtime — a module that never uses `&T` compiles with no refcount
  and no allocator dependency, exactly like C, and stays bounds-checked wherever it uses
  arrays/slices.

The boundary is not a convention — it is compiler-enforced through the allocator capability.

## Capabilities (the allocator, and orthogonal environment facts)

The presence of a **managed allocator** is a first-class capability that gates `&T`, `weak`,
and growable arrays. A context declared **no-alloc** makes those a compile error, leaving `T`,
`*T`, fixed arrays, and slice-views — the allocator-free subset.

This is **orthogonal** to whether an OS or POSIX layer is present (which gate the
standard-library / syscall surface). Unlike Rust's `no_std` — which bundles "no allocator," "no
OS," and "no conveniences" together and forces embedded-with-a-heap to claw `alloc` back — sysl
keeps them independent switches: bare-metal-with-a-heap is "alloc yes, os no." The exact
declaration mechanism is a separate design task (see open questions).

## Hazard summary

| Segfault source | Prevented in the safe subset by |
|---|---|
| use-after-free / double-free | ARC on `&T`; `weak` degrades to `Option`, never dangles |
| null dereference | non-null references; nullable is `Option` |
| out-of-bounds | length-carrying arrays/slices, checked everywhere |
| dangling / wild pointer | impossible without `*T` |

Only `*T` opts out — visibly.

## Open sub-questions

- **Capability mechanism** — how `alloc` / `os` / `posix` are declared and checked (per-module
  attribute, per-target default, or both) and the exact syntax. Its own design pass.
- **Concurrency** — atomic refcounts for cross-thread `&T`, and the data-race story. Not yet
  designed.
- **Unchecked-index escape hatch** — an opt-out of bounds checking for hot loops (default
  checked). Likely yes, deferred.
- **`weak` runtime** — the exact weak-tracking representation (side table vs in-box header).
