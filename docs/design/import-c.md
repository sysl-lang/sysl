# `sysl import-c` — design

`sysl import-c <header.h>` writes the **raw layer** of a binding: the `opaque struct`s, the records
handed over by value, the `extern`s, and the numbers, exactly the shape every org binding's
`c.sysl` has been typed in by hand. The pleasant layer above it (who owns what, `&T` with a `Drop`,
`Result`s, callbacks through `@export` trampolines) stays a person's.

```
sysl import-c [-I <dir>] [-D <macro>] [--prefix <p>] [--module <name>] [--link-lib <lib>]
              [--include-as <path>] [--target <name>] [-o <path>] <header.h>
```

## 1. The front end: clang's JSON AST

Three options were on the table.

| option | verdict | evidence |
|---|---|---|
| (a) `clang -Xclang -ast-dump=json -fsyntax-only` | **chosen** | no new dependency: clang is already what `c const`/`c type` (`c_probe.sysl`) and every link use. Probed: `tree_sitter/api.h` dumps 3.1 MB of JSON, `uv.h` 23 MB, in 0.09 s / 0.15 s of clang; `sysl-lang/json` 0.2.1 parses them in 0.03 s / 0.19 s (`-O2`) |
| (b) a libclang binding | rejected | a new link-time dependency of the *compiler binary* (`libclang.dylib` from CLT or brew llvm, a different path and soname per platform and per release tarball), for structured types the JSON route recovers with a 300-line declarator reader |
| (c) sysl's own C parser | does not exist | the compiler parses no C: `c_probe.sysl` compiles a probe with clang and reads numbers out of the IR, `c_header.sysl` only *writes* C. Writing one means a preprocessor, which is clang's job |

**Cost of (a)**: the compiler takes `sysl-lang/json` as a dependency (design question Q1), and
clang prints a declaration's type as text (`"fx_ctx *(const char *, size_t)"`), so the declarator
grammar is read back in `import_c_type.sysl`.

### The two clang runs

1. `clang -E -dD <header>` — the header's own `#define`s, in order, using the line markers to keep
   only the ones the named header writes (not its includes'). Function-like macros are refused here;
   empty ones (guards, flags) are skipped silently.
2. `clang -fsyntax-only -ast-dump=json probe.c`, where `probe.c` is `#include "<header>"` plus one
   line per candidate macro: `static const __auto_type __sysl_import_c_<k> = (<NAME>);`. The type
   clang deduces is the macro's type; a line clang refuses (`error:` at `probe.c:<k+2>`) is a macro
   that is not a constant. An error anywhere else is the header's, and the command fails quoting
   clang.

Both runs get `--target=<triple>`, the target's machine flags, `-fshort-enums` where the target has
it, and every `-I`/`--include-path`/`-D` — the same flags a `c const` probe is compiled with.

**Which file a declaration is in.** clang writes a location's `file` only where it differs from the
previous location it wrote, so the walk visits every `loc`/`range` in document order (skipping
`includedFrom`) and a declaration is the header's when its `loc` (the expansion location, for a
macro-made one) resolves to the header's canonical path.

## 2. What it emits, and why it checks itself

Every number comes from the C compiler **at build time**, not from the machine `import-c` ran on:

- an enum is a `c type` (`Mode = "fx_mode"`) and its enumerators `c const`s declared at it;
- a macro is a `c const` naming it (`VERSION: i32 = "FX_VERSION"`), typed by what clang deduced;
- C's `long`/`unsigned long` are `c type CLong`/`CULong` (4 bytes on ILP32, 8 on LP64);
- a laid-out record carries `SIZEOF_<R>` and `OFFSETOF_<R>_<FIELD>` `c const`s from
  `sizeof`/`__builtin_offsetof`, and an `@assert` per field and per size.

So a generated module is not a snapshot of one machine: a field laid out differently on the target
being built fails to compile at that record's `@assert` (`imc_layout_check_can_fail`), instead of
linking and reading the wrong bytes.

## 3. Every C construct

| C | sysl | notes |
|---|---|---|
| function | `extern "c_name" name(p: T, ...) -> R` | prefix stripped; the quoted link name is omitted when the names agree; no result for `void` |
| variadic function | `extern … (fmt: *u8, ...)` | sysl externs take `...` |
| `static` / `static inline` function | **refused**: "a 'static inline' function has no symbol to link against -- a '.c' shim beside the module reaches it" | a shim generator is Q5 |
| `extern` variable | `extern "c_name" name: T` | |
| `struct` with a complete definition | `struct Name` + fields + size/offset `@assert`s | named for its typedef if one names it, else its tag |
| `struct` never defined (handle) | `opaque struct Name` | |
| record from another header, behind a pointer | `opaque struct Name`, under "Declared by another header" | e.g. `FILE` |
| record from another header, by value | **refused**: "'struct timespec' by value, and it is declared by another header" | Q3 |
| `__attribute__((packed))` | `@packed struct` | layout checked like any other |
| `__attribute__((aligned(n)))`, `#pragma pack` | opaque, sized: "carries an alignment attribute" / "is laid out under '#pragma pack'" | `@align(n)` could express the first (Q6) |
| `union` | opaque, with `SIZEOF_`/`ALIGNOF_` c consts: "is a C union, which sysl has no spelling for" | **known gap**: sysl has no untagged union |
| bitfield member | record opaque, sized: "has a bitfield ('a'), whose layout C leaves to the implementation" | sysl's `@packed` `uN` fields are its own layout, not C's |
| anonymous struct/union member | record opaque, sized: "has a field ('v') that is an anonymous struct or union, which has no name to give it" | Q4 |
| flexible array member `T x[]` | record opaque, sized: "…a flexible array member, whose length is the allocation's" | |
| a field holding a withheld record by value | that record withheld too (computed to a fixed point) | |
| `enum` | `c type Name = "<C spelling>"` + `NAME: Name = "<ENUMERATOR>"` | the width is the target's (`-fshort-enums` honoured) |
| anonymous `enum` with no typedef | its enumerators as `i32` `c const`s | C's enumerator type |
| `typedef` naming a record/enum | nothing extra: the record/enum takes its name | `typedef struct {…} T` (clang spells it `struct T`) found by the typedef's `ownedTagDecl` |
| `typedef` of a scalar or pointer | `type Name = T` | an alias, interchangeable |
| `typedef` of a function pointer | `type Cb = *extern(A, B) -> R` | the callback shape; the trampoline is the pleasant layer's |
| `typedef` of a function type (`typedef int fn(int)`) | written out where used: `*extern(i32) -> i32` | sysl has no non-pointer function type |
| pointer to a variadic function | **refused**: "a pointer to a variadic function" | |
| block pointer `^` | **refused**: "a block pointer ('^'), which is Apple's closure extension to C" | |
| `#define NAME <integer or float expr>` | `c const NAME: T = "NAME"` | `T` from clang's `__auto_type` deduction |
| `#define NAME "string"` | **refused**: "a string, and a 'c const' measures numbers only" | |
| `#define NAME(x) …` | **refused**: "a function-like macro, which has no value of its own" | |
| `#define NAME <not a constant>` | **refused**: "not a constant expression C can evaluate" | |
| `#define NAME <a type or other non-number>` | **refused**: "a '<type>', not a number" | |
| `long double` | **refused**: "'long double', whose format is the machine's -- see `c type`" | |
| `_Complex`, vectors, `_BitInt` | refused by name, through the type that cannot be mapped | not probed yet |

### The type mapping

`char`/`unsigned char`/`void` behind a pointer → `*u8`, `signed char *` → `*i8`; `short`/`int`/
`long long` and their unsigned forms → `i16`/`i32`/`i64` (`u…`); `long` → `CLong`; `size_t`,
`uintptr_t` → `usize`; `ssize_t`, `ptrdiff_t`, `intptr_t` → `isize`; `intN_t`/`uintN_t` → `iN`/`uN`;
`float`/`double` → `f32`/`f64`; `_Bool`/`bool` → `bool`; `T[n]` in a field → `[n]T`, in a parameter
→ `*T`; a `typedef` from another header is followed to what it stands for.

### Names

- a type: prefix off (case-insensitive; written without its `_` it matches only before a capital or
  `_`, so `ts_` does not take `tsunami` to `unami`), then `_t` off, then UpperCamel per `_` piece:
  `uv_tcp_t` → `Tcp`, `TSNode` → `Node`;
- a constant: prefix off, SCREAMING_SNAKE: `TSQuantifierZero` → `QUANTIFIER_ZERO`;
- a function or variable: prefix off, otherwise as C wrote it;
- a reserved word is backticked (`` `in` ``); a collision gets `_2`.

## 4. Evidence

- `tests_imc_import_c.sysl`: the fixture header's output compared **exactly**; its refusal list
  exactly; the output built and run against a `.c` implementing two functions (a struct by value
  both ways, an enum and three macros read back: `8 48 5 4 -1 4294967296 0.5 4 7`); a widened field
  failing the build at the record's `@assert`; the command's stdout/stderr/`-o`/`--module`/`--link`;
  the usage, a missing header, and a header clang cannot compile; the declarator reader and the
  naming rules.
- **tree-sitter**: `api.h` with `--prefix ts_` generates 150 externs, 21 records and 6 handles with
  **no refusal**; the module compiles with every layout `@assert` holding and calls into
  `libtree-sitter`. All **90** externs of the hand-written `sh.sysl.treesitter.c` are generated;
  80 match textually, the other 10 differ only in naming the type the binding wrote as an integer
  (`Symbol`/`FieldId`/`StateId` aliases of `u16`; the `SymbolType`/`Quantifier`/`QueryError` enums'
  `c type`s, `u32` here) or one record name (`QueryPredicateStep` vs `PredicateStep`) — asserted
  by `imc_tree_sitter_agrees`.

## 5. Open questions

- **Q1 — the compiler takes `sysl-lang/json` as a dependency.** Recommendation: yes; it is the
  org's JSON, already on parsing 0.13.0 like the compiler, and the alternative is a second reader.
- **Q2 — output is one module; should it be split** (records / externs / numbers)? Recommendation:
  no — one `c.sysl` is the house shape; `--prefix` plus a pleasant module above is the split.
- **Q3 — by-value records from other headers** (`struct timespec`). Recommendation: lay them out
  too when they are complete (they get the same `@assert`s), behind a `--follow <header>` list so
  the output does not pull in half of libc.
- **Q4 — unions and anonymous members.** Recommendation: keep them opaque-and-sized until the
  language has an untagged union (a language item, not a generator one); then they map directly.
- **Q5 — `static inline` functions and function-like macros.** Recommendation: a later
  `--shim <file.c>` that writes the three-line C wrapper and its `extern`, the house's own pattern.
- **Q6 — `aligned(n)`**: emit `@align(n)` when `n` is a literal? Recommendation: yes, checked by
  the same `@assert`s.
- **Q7 — `char` scalars** map to `u8` (the house's `*u8` convention extended); C's `char` is signed
  on Apple arm64 and x86, which only matters for a `char` passed or returned by value (sign- vs
  zero-extension). Recommendation: `i8` for a bare `char` value, `*u8` behind a pointer.
- **Q8 — the reference page's "a header parser or a binding generator" row** in `ffi.md § What is
  deliberately absent` is no longer true once this ships; rewrite it then.
