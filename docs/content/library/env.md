---
title: The env module
summary: "`sysl.env` — reading the environment a program was started with: `get`, `get_or`, `is_set`, `vars`, and why nothing here writes one."
weight: 72
---

**Every declaration in `sysl.env`, with its signature:** [the generated API page](/api/sysl-env/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.env` reads the environment a program was started with. It is four functions and no state.

```sysl
import sysl.env.{get, get_or, is_set}

// A name nothing sets, so this page reads the same on every machine.
print(get("SYSL_DOCS_NOT_SET"))
print(get_or("SYSL_DOCS_NOT_SET", "a default of the caller's"))
print(is_set("SYSL_DOCS_NOT_SET"))

print(is_set("PATH"))
```

```output
None
a default of the caller's
false
true
```

It requires `os` — not `posix`, and not `libc`. Every read goes through the hooks
[below](#answering-the-environment-on-a-target-with-no-c-library), so what the module needs is whatever answers them: a C
library's `getenv` on a hosted target, a kernel's own `@export`s on a bare one. Filing it under a
stronger capability would have made the module unreachable on a machine that has an operating system
and is not POSIX, or has no C library.

## Every variable at once

`vars` lists the whole environment as `(name, value)` pairs, in the order the process holds them —
what a program reporting its configuration, or a language runtime offering its own `env()`, needs
and `get` cannot give, since `get` has to be told a name first.

```sysl
import sysl.env.{get, vars}

val all = vars()

// What this prints is the same on every machine, so the page asks a question rather than listing
// somebody's environment: each pair is the one `get` answers for its name.
var agree = true

for (name, value) in all
    if get(name) != Some(value) then agree = false

print(all.len() > 0)
print(agree)
```

```output
true
true
```

**It needs nothing `get` does not**: it reads through two hooks of its own, `sysl_env_count` and
`sysl_env_entry`. On a hosted target their answer is POSIX's `environ` — ISO C can say what one name
is set to and not which names there are — so a hosted target without POSIX answers an empty list. A
kernel answering the two hooks lists its own environment with no POSIX and no C library anywhere.

**The value is everything after the first `=`**, so `A=b=c` is `("A", "b=c")`; `VAR=` is kept as
`("VAR", "")`, a set-to-empty variable being set. An entry with no `=` names nothing and is passed
over, and so is one that is not UTF-8 — `get`'s answer to the same bytes. Both halves are copied out of
what the environment lends, whose storage a later `setenv` from C may move.

## Unset, empty, and not text are three different answers

`get` folds two of them together and `is_set` is what tells them apart, because the two questions have
different callers.

Most callers want a value and treat *unset* and *empty* alike — a search path, a directory override, a
home. The convention-driven ones do not: `NO_COLOR` disables colour by being **present**, whatever it
contains, so `NO_COLOR=0` means no colour and a caller asking `get` would get that backwards.

**A value that is not UTF-8 also answers `None`**, which folds a third case into the second. That is
the honest thing for a function whose result is a `string`: the bytes are somebody's environment
rather than the program's input, so there is no encoding to negotiate and nothing useful to report. A
caller that must tell *unset* from *not text* asks `is_set` as well.

## Reading only

**Nothing here sets a variable, and that is a decision rather than an omission.**

`setenv` mutates state the whole process shares and is not thread-safe against a concurrent `getenv`.
It is also the mechanism the library went out of its way to avoid elsewhere: naming a time zone by
setting `TZ` is exactly the trap that made [a zone reader](/library/time/#a-zone-by-name) worth
writing, and offering the same gun here would undo that.

A program that genuinely needs to hand a *child* a different environment wants that at the spawn,
which belongs to a process module and not to this one.

## Answering the environment on a target with no C library

**Everything above reads the environment through `sysl.env.sys` and through nothing else**: three
hooks, each an `extern` no module of the library defines.

| hook | what it answers |
|---|---|
| `sysl_env_get(name, name_len, into, room, got) -> int` | one where the name is set, its value written into `into` and the value's length into `got`; zero where it is not |
| `sysl_env_count() -> isize` | how many variables there are |
| `sysl_env_entry(i, into, room, got) -> int` | one, the `i`-th variable's `NAME=value` bytes written into `into` and their length into `got`; zero past the last |

**Text crosses as a pointer and a length, never as a C string**: a name is lent as `name` and
`name_len`, and what a hook hands back it writes into the caller's `room` bytes at `into`, with the
**whole** length in `got` — more than `room` meaning only the first `room` bytes were written, and the
library asks again with the room it was told. Like every `*.sys` module's (`sysl.fs.sys`,
`sysl.io.sys`, `sysl.process.sys`, `sysl.net.sys`), each hook answers zero or more for success, the
`IoError` code negated for a failure, and `sysl.sys.UNSUPPORTED` (-38) for a call the target cannot
make at all.

On a hosted target the library answers them itself, under `weak` exports
([a module may supply another module's extern](/reference/ffi/)): `get` over `getenv` everywhere, and
`count` and `entry` over `environ` where there is POSIX — `UNSUPPORTED` where there is not, which
`vars` reads as an empty list. A kernel answers them from the vector it laid after a process's
arguments, with an `@export` per hook its program reaches:

```sysl
@export("sysl_env_count")
k_count() -> isize = 0
```

**A hook the program reaches and leaves unanswered is refused when it is compiled**, all of them in one
sentence, rather than surfacing at the link as C's `getenv` and `environ`, which no line of the program
names:

```sysl target=aarch64-freestanding
import sysl.env.{get, vars}

val home = get("HOME")
val all = vars()
```

```error
this program reads its environment, and 'aarch64-freestanding' has no operating system under it for the standard library to answer one with, so the program answers it: define 'sysl_env_count', 'sysl_env_entry', 'sysl_env_get' with '@export', each taking what its 'extern' in 'sysl.env.sys' declares and answering a count, one or zero, or the code of an 'IoError' negated
```

The question is asked only of what the program reaches, so one that calls `get` and never `vars` is
asked for `sysl_env_get` alone.

## Who asks

`TZDIR` is the worked example. It is how every other reader of the time zone database is redirected —
a distribution relocating it, a container carrying its own copy, a test pointing at one it wrote — and
[`sysl.posix.time`](/library/time/#a-zone-by-name) consults it through `get_or`. A reader that ignored
it would disagree with `date` and `zdump` on the same machine.
