---
title: The env module
summary: "`sysl.env` — reading the environment a program was started with: `get`, `get_or`, `is_set`, and why nothing here writes one."
weight: 72
---

**Every declaration in `sysl.env`, with its signature:** [the generated API page](/api/sysl-env/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.env` reads the environment a program was started with. It is three functions and no state.

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

It requires `os` — not `posix`. `getenv` is ISO C rather than a POSIX addition, so any target with a
C library and a process environment can answer it, and filing it under the stronger capability would
have made the module unreachable on a machine that has an operating system and is not POSIX.

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

## Who asks

`TZDIR` is the worked example. It is how every other reader of the time zone database is redirected —
a distribution relocating it, a container carrying its own copy, a test pointing at one it wrote — and
[`sysl.posix.time`](/library/time/#a-zone-by-name) consults it through `get_or`. A reader that ignored
it would disagree with `date` and `zdump` on the same machine.
