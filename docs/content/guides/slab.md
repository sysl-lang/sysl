---
title: slab
summary: Raw storage — reinterpreting bytes as a typed pointer, `sizeof`/`alignof`, and a free list threaded through the free blocks themselves.
weight: 130
---

One region of bytes carved into fixed blocks, with the free list threaded through the free blocks'
own storage. **The first literate program in the set** — its findings ran to sixty lines of header
comment before anything executable appeared, which is the length at which a comment stops being one,
so `slab.lsysl` is a document with the program indented inside it.

**The axis: raw storage** — reinterpreting bytes as a typed pointer, asking what a type's storage
costs, and the address arithmetic between the two. Nothing else in the set touches any of it.
[kernel](/guides/kernel/) is allocator-free but never *makes* storage; it is handed three fixed tables
and indexes them. This is the other side of that: the thing a program with no allocator would have to
write before it could have one, which is why it is written with no allocator itself.

It is **generic in what it holds**, and that is the point rather than a flourish. A slab over one
hardcoded struct needs no `sizeof` at all — a literal block size would do — so the generic form is
what makes the measurement load-bearing, and it is the shape a real allocator has.

The free list is **intrusive**: a free block holds the address of the next free block in its own first
bytes, so the list costs no storage beside the region. That is what a slab allocator *is*, and it is
only writable because a `*u8` can be read as a `**u8` and back:

```sysl
link(b: *u8) -> **u8
    ptr_cast(b)
end link

next_free(b: *u8) -> *u8
    *link(b)
end next_free
```

## What it found

**The language cannot demand that storage be aligned.** A `[N]u8` is aligned to one, because that is
what its element needs, and there is no `alignas`. So a region declared the obvious way is not aligned
for anything carved out of it, and every load through the resulting pointer is unaligned — which x86
tolerates and which faults on some of the targets this language is for.

The allocator therefore rounds its own base up, exactly as a real one does, paying up to
`alignof(T) - 1` bytes of the region. **That is the right behaviour for an allocator and the wrong
reason to have written it**: an allocator rounds up because its caller's region is wherever it is, not
because the language could not say what it wanted.

**`sizeof` is what makes a container generic, and `alignof` is what makes it correct.** With only the
first, a slab over any `T` still lays its blocks at whatever offset the region began at. The two
arrived together for this reason rather than by tidiness.

**A block has to be wide enough to hold the list that threads it.** `sizeof(T) >= sizeof(*u8)` is a
real precondition — a slab of `u16` cannot store an address inside a free block — and it is a
`require` rather than a comment because both sides are constants the compiler already knows. **A
language without `sizeof` could not have stated it at all.**

**Nothing checks a reinterpreted pointer, and nothing should.** `ptr_cast` hands back a `*T` aimed at
bytes that hold no `T` yet; the caller writes one before reading one, and no rule here says so. That
is the [raw tier](/reference/memory/) behaving as specified — the same assertion an unchecked index
already is — and it is why the whole file is greppable for the three operations that take on the risk.

**An allocator may call its function `alloc`, and this one does.** It could not always: the
capability clause used to be written as the two ordinary words `no alloc`, which reserved both, so
the most natural name in the whole program was the one name it could not have and this file called it
`take`. **Capabilities are attributes now** — `@no_alloc`, `@requires(...)` — and an attribute's name
is an ordinary identifier, so the words came back. The general lesson is the one the old note was
groping at: **a capability written as grammar spends a word out of every program's namespace**, and
`alloc` is the word the code that *provides* the capability wants most.

**The one thing the compiler still refuses is a `&T`.** An early draft tried to hand back a counted
reference, on the grounds that a slab block outlives its user. It cannot: ARC would have no count to
own, and the safe subset relies on a `&T` being a live object. So an allocator's result is a `*T` and
stays in the raw tier, which is where a caller reaching for one already is.

**It is allocator-free and still cannot say so** — but not for the reason first recorded. `no alloc`
has shipped; what keeps it out of this directory is the clause's own shape, since a capability is a
property of the module and the checks next door render a `str` on nearly every line.
[bytecode](/guides/bytecode/) is the program that got to carry it, and the difference is only that its
machine already lived in a module of its own.

## Deliberately not modelled

More than one block size, coalescing, and returning the region. The first is a different allocator,
and the other two need a heap this program is written without.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/slab) ·
Back to [the guide programs](/guides/), or on to the [reference](/reference/).
