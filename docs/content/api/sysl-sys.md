---
title: sysl.sys
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.sys
summary: "The platform seam: everything the library asks of what it is hosted on, and nothing else."
requires: "no alloc"
---

The C declarations behind printing, reading, searching and the mathematics library live here and
are declared nowhere else, so **what a freestanding target has to replace is this module and only
this module**. That is the whole reason it exists as a module rather than as an extern beside each
caller.

It is a leaf: it imports nothing from the rest of the library, and it gives the allocator up —
declaring a C function reaches no heap, whatever that function goes on to do.

## Index

[`UNSUPPORTED`](#unsupported)

## Constants

### `UNSUPPORTED`

```sysl
const UNSUPPORTED: int = -38
```

The status a hook answers when **the target cannot make that call at all** -- not a failure of
the call, but the absence of one. It is the one value that means this in every `*.sys` module
(`sysl.fs.sys`, `sysl.io.sys`, `sysl.process.sys`, `sysl.net.sys`, `sysl.env.sys`), on every
platform.

The contract those modules share is a system call's: zero or more is success, and a negative
answer is the code of the failure negated, so `-1` is `EPERM`. This is the one negative answer that
is not a code of the host's, which is why it is **-38** and not the host's own `ENOSYS`: that is
38 on Linux and 78 on macOS, and the contract has to mean one thing everywhere. A program
answering a hook for a target that has no way to perform the call returns it:

    @export("sysl_fs_chmod")
    no_chmod(path: *u8, len: usize, mode: u32) -> int = sysl.sys.UNSUPPORTED

The caller hears "not supported on this target".
