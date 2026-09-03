# The vendored Unicode Character Database

`sysl.unicode` answers out of **utf8proc**, vendored here rather than reached through a system
library. It is the same library Julia's `Base` uses behind its own string functions, for exactly
this set of operations.

| | |
|---|---|
| upstream | <https://github.com/JuliaStrings/utf8proc> |
| version | **2.11.0** |
| tarball | `https://github.com/JuliaStrings/utf8proc/archive/refs/tags/v2.11.0.tar.gz` |
| sha256 | `c24379b5fa0a429a1f9a3fc23b44a75f2b141a34c09146a529a55d20a5808070` |
| Unicode | 17.0.0 — and `unicode_version()` answers it at run time rather than this table being the record |
| licence | MIT, `UTF8PROC-LICENSE.md` beside this file |

## Why vendored rather than a `pkg_config` dependency

The org's rule is that vendoring is a claim about where the code can run, not a way to drop an
install step — and this is the case that claim is for. A binding reached through `pkg_config` cannot
be in the standard library at all: a program that uppercases a string would fail to link on a
machine that had not installed something. The whole point of the module is that every sysl program
gets correct case mapping with nothing to install and no coordinate to name.

It also pays the rule's own test on its own terms: with the five lines below, this compiles for a
bare-metal target. `clang -c --target=thumbv6m-none-eabi` produces an object whose only undefined
symbol is `__aeabi_uidivmod`, which the toolchain supplies. Nothing here reaches a libc.

## What this copy carries on top of upstream

Five lines, all of them making the malloc-using surface optional, and one rename. Nothing is
deleted, so a refresh is re-applying them rather than re-doing a merge.

1. **`utf8proc.h`** — the one `#include <stdlib.h>` is wrapped in `#ifndef UTF8PROC_NO_MALLOC` /
   `#endif`.
2. **`utf8proc.c`** — `#define UTF8PROC_NO_MALLOC 1` above its `#include "utf8proc.h"`, and
   `#ifndef UTF8PROC_NO_MALLOC` / `#endif` around the contiguous tail from `utf8proc_map` to
   `utf8proc_NFKC_Casefold`.
3. **`utf8proc_data.c` is renamed `utf8proc_data.h`**, and the `#include` in `utf8proc.c` follows
   it. It is not a translation unit — it is a body of tables `utf8proc.c` includes — and sysl
   compiles every `.c` beside a module, so under its own name it would be compiled a second time on
   its own and fail.

The functions switched off are the ones that ask C to allocate. `sysl.unicode` binds none of them:
`map.sysl` measures with `utf8proc_decompose`, allocates the buffer in sysl, fills it, and calls
`utf8proc_reencode` over it — which is what `utf8proc_map` does internally, with the allocation on
the side that has an allocator.

## Refreshing it

```
curl -sL -o utf8proc.tar.gz https://github.com/JuliaStrings/utf8proc/archive/refs/tags/v<new>.tar.gz
shasum -a 256 utf8proc.tar.gz
tar xzf utf8proc.tar.gz
cp utf8proc-<new>/utf8proc.c utf8proc-<new>/utf8proc.h .
cp utf8proc-<new>/utf8proc_data.c utf8proc_data.h
cp utf8proc-<new>/LICENSE.md UTF8PROC-LICENSE.md
```

Then re-apply the three edits above, update the table at the top of this file, and run the tests. A
new database version changes what some of them assert — `unicode_version()` is checked for shape
rather than for a number, but a character's category or a normalization can genuinely change between
Unicode versions, and a test that goes red is the refresh reporting what moved rather than a defect.

Check both compiles before believing it, since only one of them is what the suite exercises:

```
clang -c -O2 -I. utf8proc.c -o /dev/null
clang -c -O2 --target=thumbv6m-none-eabi -I. utf8proc.c -o /dev/null
```

## What it costs

Nothing at all to a program that does not call into it. The standard library is compiled to an
archive with one object per C file, and an archive member is pulled in only to resolve a symbol
something already referenced; the link then runs `-dead_strip` over `-ffunction-sections`. A program
that calls one of these functions links about 330 KB, nearly all of it the tables. `StdArtifactTests`
pins both halves of that.
