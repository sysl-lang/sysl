---
title: Stability
summary: What sysl says is settled, what it deliberately leaves free, and which platforms it ships for.
weight: 50
---

sysl is **pre-1.0**, and the version number says so. "Pre-1.0" alone does not tell you whether what
you build against today will still be there next month, so this page states which parts of sysl
are settled, which are deliberately left open, and which platforms a release ships for.

## What is settled

Three surfaces are settled, and they are the ones a program touches:

- **The language** — the syntax and semantics the [reference](/reference/) describes.
- **The standard library's API** — the modules under [`sysl`](/library/) and the names they export.
- **The package format** — `sysl.hocon`, the dependency model, and how a coordinate is resolved.

A program is written in the first, against the second, and distributed by the third.

**Settled means the shape is decided, not that the text is frozen.** Under semantic versioning a
`0.x` release may still break compatibility, and sysl does not claim otherwise. A break in one of
these three surfaces is a **deliberate, recorded decision**, listed in the release notes, rather
than an incidental result of ongoing design. The full compatibility guarantee comes with 1.0.

## Tightening rules

A change that makes the compiler **refuse** something it previously accepted is the one kind of
change that call-site care cannot absorb: every program relying on the old freedom stops building.
Such a change is cheap before a stability statement exists and expensive afterwards.

sysl therefore treats a tightening — a binding made write-once, a loophole in a capability closed,
an accepted but meaningless form refused — as a decision to make early, and measures its cost on
existing code before making it. **In practice, a program that compiles under a 0.x release is
expected to keep compiling**, and where a rule does tighten, the release notes say so.

## What is deliberately not promised

Each of these is excluded on purpose, and the reason is given.

### The in-memory layout of a type

`sizeof` is part of every type's interface, so a program can observe layout, which is why it is
named here. **Layout is not part of the promise until 1.0.**

The compiler is free to make types smaller: to narrow a data enum's tag, or to use a niche in a
payload's representation so that a discriminant takes no storage at all. Both are standard
optimizations, both change what `sizeof` returns, and both are worth having.

**What this means for you:** a program that depends on a particular layout — a struct accessed
through a raw pointer, a value written to a device register, a buffer whose size was computed by
hand — depends on something that is not promised. Where layout must hold, state it in the source:
`@packed` and `@align(n)` make the layout a guarantee rather than an observation, and they are
promised.

### Verification and inline assembly

[Verification](/reference/verification/) and [inline assembly](/reference/inline-assembly/) are
**experimental**, and outside the promise.

Both are shipped, documented and usable: `@pure`, `@reads`/`@writes`, `@ghost`, `for all`,
`sysl prove` and `asm` all work, and the pages describing them are checked against the compiler
like every other page on this site. What they lack is a settled surface. Together they carry more
open design questions than the rest of the language, and some of the answers would change code
already written against them. Building on either is reasonable; expecting that code to keep
compiling unchanged across releases is not.

## Which platforms are shipped

The release platforms are:

| platform | |
|---|---|
| `darwin-arm64` | Apple silicon |
| `linux-arm64` | |
| `linux-x86_64` | |

**sysl 0.8.5 ships a binary for `darwin-arm64` only.** On Linux, build the compiler from source as
described in [installation](/getting-started/installation/#build-from-source).

There is **no darwin-x86_64 build and no Windows build**, by design. Building from source reaches
more platforms than this list; the table describes what is downloadable, not what the compiler
supports.

Which *targets* the compiler can compile **for** is a separate question with a much longer answer —
see [the command line](/getting-started/cli/) for the target registry and `--target`.
