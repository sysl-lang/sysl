---
title: Stability
summary: What 0.1.0 says is settled, what it deliberately leaves free, and which platforms it ships for.
weight: 50
---

sysl is **pre-1.0**, and the version number says so. What `0.1.0` adds to that is a statement about
*which parts have stopped being sketched* — because "pre-1.0" on its own tells you nothing about
whether the thing you are about to build against will still be there next month, and that is the only
question a version number is asked.

## What 0.1.0 says is settled

Three surfaces, and they are the ones a program actually touches:

- **The language** — the syntax and semantics the [reference](/reference/) describes.
- **The standard library's API** — the modules under [`sysl`](/library/) and the names they export.
- **The package format** — `package.hocon`, the dependency model, and how a coordinate is resolved.

These are the face of the language. A program is written in the first, against the second, and
distributed by the third, so leaving any of them visibly half-finished makes the other two hard to
judge.

**What "settled" means here is that the shape is decided, not that the text is frozen.** Under
semantic versioning a `0.x` release may still break what came before it, and sysl does not pretend
otherwise. What changes at 0.1.0 is that a break in one of these three becomes a **deliberate,
recorded decision** rather than an ordinary consequence of the language still being drafted. The
guarantee proper arrives at 1.0.

## Why refusals are arriving now

A change that makes the compiler **refuse** something it used to accept is the one kind of change a
promise cannot absorb: every program relying on the old freedom stops building, and no amount of
care at the call site avoids it. Such a change is nearly free before a stability statement exists and
expensive forever afterwards.

So the run-up to 0.1.0 is when they land. If a rule of the language is going to be tightened — a
binding made written-once, a loophole in a capability closed, an accepted-but-meaningless form
refused — it happens **before** the tag rather than after it, and the corpus is measured first to
find out what the tightening would cost.

**For a reader, the practical form of this is:** a program that compiles under 0.1.0 is meant to keep
compiling, and a program that compiles under an *earlier* 0.0.x may not. If you have been tracking
the compiler through the 0.0 series, 0.1.0 is the point at which that stops being expected.

## What 0.1.0 deliberately does not promise

Each of these is excluded on purpose, and the reason is given rather than left to be inferred.

### The in-memory layout of a type

`sizeof` is an askable part of every type's interface, so layout is observable by a program — which
is exactly why it is named here rather than passed over. **Layout is not part of the promise until
1.0.**

The compiler is left free to make types smaller: to narrow a data enum's tag, or to spend a niche in
a payload's representation so a discriminant costs no storage at all. Both are ordinary optimizations
elsewhere, both change what `sizeof` answers, and both are worth having.

**What this costs you, stated plainly:** a program that depends on a particular layout — a struct
poked at through a raw pointer, a value written to a device register, a buffer whose size was
computed by hand — is depending on something 0.1.0 does not promise. Where layout must hold, say so
in the source: `@packed` and `@align(n)` are the constructs that make it a fact rather than an
observation, and they are promised.

### Verification and inline assembly

[Verification](/reference/verification/) and [inline assembly](/reference/inline-assembly/) are
**experimental**, and outside the promise.

Both are shipped, documented and usable — `@pure`, `@reads`/`@writes`, `@ghost`, `for all`,
`sysl prove` and `asm` all work, and the pages describing them are checked against the compiler like
every other page here. What they do not have is a settled surface: between them they carry more open
design questions than the rest of the language put together, and several of the answers would change
what is already written. Building on either is reasonable; building on either *and expecting it to
hold across releases* is not.

### The compiler's own published artifacts

`sh.sysl.api` and `sh.sysl.ir` are published so that a tool or a back end can live outside this
repository. **Neither is promised before 1.0** — a consumer pins a compiler version and moves when it
chooses to.

`sh.sysl.ir` is the more volatile of the two by construction: it is a tree, and lowering is what
changes it. `sh.sysl.api` is narrower on purpose — every signature is written in `String`, `Int`,
`List` and `Either`, and it mentions no syntax tree, so it is the one to reach for where a choice
exists.

## Which platforms are shipped

Releases carry three binaries:

| platform | |
|---|---|
| `darwin-arm64` | Apple silicon |
| `linux-arm64` | |
| `linux-x86_64` | |

There is **no darwin-x86_64 build and no Windows build**, and neither is an oversight. Building from
source reaches more than this list does; what the table describes is what is downloadable, not what
the compiler supports.

Which *targets* a release can compile **for** is a different question, and a much longer list — see
[the command line](/getting-started/cli/) for the target registry and `--target`.
