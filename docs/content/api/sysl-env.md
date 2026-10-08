---
title: sysl.env
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.env
summary: "Reading the environment a program was started with."
requires: "requires { os }"
---

**A module of its own, following `sysl.fs`.** `sysl.sys` is the printing-and-reading core and
carries no capability, so a symbol that needs an operating system filed there would be a
requirement written where nothing declares one -- which is the argument `sysl.posix.tty` already
makes about its own `isatty`.

It asks for `os` rather than `posix`, which is the weaker of the two and the true one: `getenv` is
ISO C rather than a POSIX addition, so any target with a C library and a process environment can
answer it.

**`vars` is the exception, and carries `@needs(posix)` on its own.** Listing every variable needs
`environ`, which POSIX declares and ISO C does not, so the module as a whole stays at `os` and only
a caller of `vars` is asked for `posix` -- `get` keeps working on a target with a C library and no
POSIX.

## Reading only

**Nothing here sets a variable, and that is a decision rather than an omission.** `setenv` mutates
state the whole process shares, is not thread-safe against a concurrent `getenv`, and is the
mechanism this library went out of its way to avoid in `sysl.posix.time` -- naming a time zone by
setting `TZ` is exactly the trap that made a zone reader worth writing. A program that genuinely
needs to hand a child a different environment wants that at the spawn, which is `process`'s
business and not this module's.

## Unset, empty, and not text are three different answers

`get` folds two of them together and `is_set` is what tells them apart, because the two questions
have different callers. Most callers want a value and treat "unset" and "empty" alike -- a search
path, a directory override, a home. The convention-driven ones do not: `NO_COLOR` disables colour
by being **present**, whatever it contains, so `NO_COLOR=0` means no colour and a caller asking
`get` would get that backwards.

## Index

[`get`](#get) [`get_or`](#get_or) [`is_set`](#is_set) [`vars`](#vars)

## Functions

### `get`

```sysl
get(name: string) -> Option[string]
```

What a name is set to, or `None` where it is unset.

**A value that is not UTF-8 answers `None` as well**, which folds a third case into the second and
is the honest thing for a function whose result is a `string`: the bytes are somebody's
environment rather than the program's input, so there is no encoding to negotiate and nothing
useful to report. A caller that must tell "unset" from "not text" asks `is_set` too.

### `get_or`

```sysl
get_or(name: string, fallback: string) -> string
```

What a name is set to, or a value of the caller's where it is not.

This is the shape most callers actually want -- a default that is a real value rather than a case
to match -- and it is the one `sysl.posix.time` uses to let `TZDIR` redirect a lookup.

### `is_set`

```sysl
is_set(name: string) -> bool
```

Whether a name is present at all, whatever it holds.

**Set-to-empty answers `true` here and `Some("")` from `get`**, which is what makes the two
functions worth having separately: `VAR=` is a name that is set, and a convention reading presence
rather than content is entitled to say so.

### `vars`

```sysl
vars() -> Buf[(string, string)]
```

Every variable in the environment, as `(name, value)` pairs, in the order the process holds them.

**It is the one function here that needs `posix`**, the rest of the module being ISO C: C can
answer what one name is set to, and only POSIX's `environ` can say which names there are. A module
that has given up `posix` is refused at the call rather than at the link.

**The value is everything after the FIRST `=`**, so `A=b=c` is `("A", "b=c")` -- a name cannot hold
an `=` and a value can. `VAR=` is kept as `("VAR", "")`, set-to-empty being a variable that is set,
as `is_set` says. An entry with no `=` at all names nothing and is passed over, and so is one whose
name or value is not UTF-8, which is `get`'s answer to the same bytes.

**Both halves are copied out**: what `environ` points at is the process's own storage, and a later
`setenv` from C may move or free it, so nothing returned here is a view of it.
