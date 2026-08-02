---
title: Standard Library
summary: What ships with the compiler, module by module — the core every program has, and the layers a target may not.
weight: 40
---

The [reference](/reference/) is the *language*: what the compiler reads, what a type is, what a
declaration binds. This section is the other half of what a program has — **the modules that ship
with the compiler**, and what each of them offers.

They are kept apart on purpose. Nothing in this section is a language feature: every type here is an
ordinary struct or enum, every function is ordinary sysl, and a program could have written any of it.
`Option` is a generic enum, `unwrap` is a member that calls `exit`, `print` is a library function
reached by a desugaring. The line matters because it is the language's own rule about itself —
**there are no functions built into the compiler that a program could not have written**, and the one
exception is the seam out to C.

So a page in the reference tells you what the compiler will accept. A page here tells you what
somebody already wrote for you, and where it will not be there.

## The library is a tree, and the tree is the point

`sysl` is one module with submodules under it, and each is a directory. A program reaches the core
without asking; everything below it is [imported](/reference/modules/) by name.

| module | holds |
|---|---|
| `sysl` | the core — `Option`, `Result`, `Display`, the operator traits, `print`, `assert` |
| `sysl.buf` | `Buf[T]`, the growable sequence, and `ByteSink` |
| `sysl.text` | the whole text surface — validation, the character cursors, `Ascii` and `Search`, splitting and joining, `StrBuilder`, the parsers, `CString` |
| `sysl.io` | `Reader`, `stdin()`, `lines()` |
| `sysl.fs` | files and paths — `read_text`, `write_bytes`, `exists`, `rename`, and `IoError` |
| `sysl.math` | `max`, `min`, `pi`, the float functions, the integer traits `Signed` and `Bits`, and the integer arithmetic above them — `pow`, `gcd`, `lcm`, `divmod`, `is_power_of_two`, `next_power_of_two` |
| `sysl.sync` | `Atomic[T]`, `SpinLock`, and the five memory orderings |
| `sysl.thread` | `spawn`, `Thread.join`, `yield_now`, and `Mutex[T]` |
| `sysl.args` | `args_of`, for reading a raw `argv` |
| `sysl.sys` | the platform seam — what a freestanding target replaces |

**The split is by capability, not by taste.** `sysl.fs` is `requires os`, because a filesystem is
something the environment either has or does not; `sysl.thread` is `requires threads`. That is why
the atomics live apart from the threads: `sysl.sync` requires **nothing**, so a kernel can have a
spinlock without acquiring a scheduler along with it.

A module a target cannot support is therefore not a module that fails to link — it is one a
[capability clause](/reference/modules/) will not let that program import in the first place.

## Where some of this already is

Three pieces of the library are documented in the reference instead, because the language has
machinery that only makes sense beside them:

- **`Option`, `Result` and the `Fallible` latch** are on [errors and
  contracts](/reference/errors/), because `?` is a language form and it is what those types are for.
- **`assert` and `panic`** are on [attributes and compile time](/reference/attributes/), beside the
  `#test` protocol they exist to serve.
- **The operator traits** — which trait a `+` or a `<` reaches — are on
  [expressions](/reference/expressions/), because dispatch is a rule about the operator rather than
  about the trait.

This section links to them rather than repeating them.
