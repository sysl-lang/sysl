# `sysl import-c` — design

`sysl import-c <header.h>` writes the **raw layer** of a binding: the `opaque struct`s, the records
handed over by value, the `extern`s, and the numbers, exactly the shape every org binding's
`c.sysl` has been typed in by hand. The pleasant layer above it (who owns what, `&T` with a `Drop`,
`Result`s, callbacks through `@export` trampolines) stays a person's.

```
sysl import-c [-I <dir>] [-D <macro>] [--prefix <p>] [--module <name>] [--link-lib <lib>]
              [--include-as <path>] [--follow <header>] [--shim <file.c>] [--target <name>]
              [-o <path>] <header.h>
```

## 1. The front end: clang's JSON AST

Three options were on the table.

| option | verdict | evidence |
|---|---|---|
| (a) `clang -Xclang -ast-dump=json -fsyntax-only` | **chosen** | no new dependency: clang is already what `c const`/`c type` (`c_probe.sysl`) and every link use. Probed: `tree_sitter/api.h` dumps 3.1 MB of JSON, `uv.h` 23 MB, in 0.09 s / 0.15 s of clang; `sysl-lang/json` 0.2.1 parses them in 0.03 s / 0.19 s (`-O2`) |
| (b) a libclang binding | rejected | a new link-time dependency of the *compiler binary* (`libclang.dylib` from CLT or brew llvm, a different path and soname per platform and per release tarball), for structured types the JSON route recovers with a 300-line declarator reader |
| (c) sysl's own C parser | does not exist | the compiler parses no C: `c_probe.sysl` compiles a probe with clang and reads numbers out of the IR, `c_header.sysl` only *writes* C. Writing one means a preprocessor, which is clang's job |

**Cost of (a)**: the compiler takes `sysl-lang/json` as a dependency (decision Q1), and
clang prints a declaration's type as text (`"fx_ctx *(const char *, size_t)"`), so the declarator
grammar is read back in `import_c_type.sysl`.

### The two clang runs

1. `clang -E -dD <header>` — the header's own `#define`s, in order, using the line markers to keep
   only the ones the named header writes (not its includes'). Function-like macros are kept with
   their parameters and bodies for `--shim` (§3); empty ones (guards, flags) are skipped silently.
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
  `sizeof`/`__builtin_offsetof`, and an `@assert` per field and per size; a union, an `@align(n)`
  record and one with C bitfields carry `ALIGNOF_<R>` (`_Alignof`) and its `@assert` too. A
  bitfield has no offset, so its record's size and boundary are what check it.

So a generated module is not a snapshot of one machine: a field laid out differently on the target
being built fails to compile at that record's `@assert` (`imc_layout_check_can_fail`), instead of
linking and reading the wrong bytes.

## 3. Every C construct

| C | sysl | notes |
|---|---|---|
| function | `extern "c_name" name(p: T, ...) -> R` | prefix stripped; the quoted link name is omitted when the names agree; no result for `void` |
| variadic function | `extern … (fmt: *u8, ...)` | sysl externs take `...` |
| `static` / `static inline` function, with `--shim` | `extern "sysl_<name>" name(…)` + a wrapper in the shim: `int sysl_fx_twice(int x) { return fx_twice(x); }` | the wrapper's parameters are declared with clang's spelling of their types (`__typeof__(T)` where the name would sit inside a declarator) |
| the same, without `--shim` | **refused**: "a 'static inline' function has no symbol to link against -- '--shim <file.c>' writes the wrapper that reaches it" | |
| `static inline` taking `...` | **refused**: "…that takes '...', whose arguments a wrapper cannot pass on" | |
| `extern` variable | `extern "c_name" name: T` | |
| `struct` with a complete definition | `struct Name` + fields + size/offset `@assert`s | named for its typedef if one names it, else its tag |
| `struct` never defined (handle) | `opaque struct Name` | |
| record from another header, behind a pointer | `opaque struct Name`, under "Declared by another header" | e.g. `FILE` |
| record from another header, by value, its header named by `--follow` | laid out with the same `@assert`s, under "Declared by a header '--follow' named" | only the records used are written, and what they use in turn |
| the same, without `--follow` | **refused**: "'struct fx_far' by value, and it is declared by another header -- '--follow fx_other.h' lays it out" | |
| `__attribute__((packed))` | `@packed struct` | layout checked like any other |
| `__attribute__((aligned(n)))`, `n` a literal | `@align(n)`, with `ALIGNOF_` and its `@assert` | a macro that is a literal counts |
| `aligned` with no literal, an `aligned` member, `#pragma pack` | opaque, sized: "carries an alignment attribute whose value is not a literal" / "is laid out under '#pragma pack'" | clang's JSON gives a pack no value, so `pack(1)` and `pack(2)` cannot be told apart |
| `union` | `union Name` + members, `SIZEOF_`/`ALIGNOF_`/`OFFSETOF_` and their `@assert`s | `types.md § Unions` |
| a union member sysl cannot express | `_name: [SIZEOF_<U>_<NAME>]u8`, the union `@align(ALIGNOF_<U>)`; said in a comment and in the refusals | the user's rule from `union.md` Q4: one member does not withhold the union |
| anonymous `union`/`struct` member | an anonymous block in place; its fields' offsets asked of the outer record | `types.md § Anonymous members` |
| a named member of an unnamed record type (`union { … } v;`) | `v: HolderV`, and `union HolderV` written after the outer record, measured through it (`sizeof(((struct fx_holder *)0)->v)`) | |
| bitfield `T name : W` | `name: uW in T` (`iW` for a signed `T`), `_: uW in T` unnamed, `_: in T` zero-width, `bool in bool` for `_Bool b : 1` | `attributes.md § C bitfields`: C's layout for the target |
| a bitfield whose unit is not an 8-to-64-bit integer or `_Bool` (`long`, an enum) | record opaque, sized: "has a bitfield ('l') declared 'long', and a bitfield's unit is an integer of 8, 16, 32 or 64 bits" | `long`'s width is the target's |
| a record with C bitfields passed or returned by value | **refused**: "'struct fx_bits' has C bitfields, which cross a C boundary only by address -- as a '*Bits'" | the language passes one by address only |
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
| `#define NAME(x) f(x, 0)`, with `--shim` | `extern "sysl_NAME" name(x: T) -> R` + a wrapper calling the macro, typed by `f` | the body is one call of a function the header declares, each macro parameter one whole argument and no other argument naming one |
| the same, without `--shim` | **refused**: "a function-like macro has no symbol to link against -- '--shim <file.c>' writes the wrapper that reaches it" | |
| any other `#define NAME(x) …` | **refused**: "a function-like macro whose parameters ('x') no function it calls gives a type" | |
| `#define NAME(c, ...) …` | **refused**: "a function-like macro that takes '...'" | |
| `#define NAME <not a constant>` | **refused**: "not a constant expression C can evaluate" | |
| `#define NAME <a type or other non-number>` | **refused**: "a '<type>', not a number" | |
| `long double` | **refused**: "'long double', whose format is the machine's -- see `c type`" | |
| `_Complex`, vectors, `_BitInt` | refused by name, through the type that cannot be mapped | not probed yet |

### The type mapping

`char`/`unsigned char`/`void` behind a pointer → `*u8`, `signed char *` → `*i8`; a `char` value
(a parameter, a result, a field, an array element) → `i8`; `short`/`int`/
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
  exactly; the output built and run against a `.c` implementing three functions (a struct by value
  both ways, a `char` both ways, an enum and three macros read back; a union, an anonymous member
  and a C bitfield written and read); a widened field, a widened union member, a widened bitfield
  and a lowered `@align` each failing the build at its record's `@assert`; `--follow` laying out
  another header's record and the result running; `--shim`'s exact C, compiled, linked and called
  through; the command's stdout/stderr/`-o`/`--module`/`--link`/`--shim`/`--follow`; the usage, a
  flag with no value, a missing header, and a header clang cannot compile; the declarator reader and
  the naming rules.
- **tree-sitter**: `api.h` with `--prefix ts_` generates 150 externs, 21 records and 6 handles with
  **no refusal**; the module compiles with every layout `@assert` holding and calls into
  `libtree-sitter`. All **90** externs of the hand-written `sh.sysl.treesitter.c` are generated;
  80 match textually, the other 10 differ only in naming the type the binding wrote as an integer
  (`Symbol`/`FieldId`/`StateId` aliases of `u16`; the `SymbolType`/`Quantifier`/`QueryError` enums'
  `c type`s, `u32` here) or one record name (`QueryPredicateStep` vs `PredicateStep`) — asserted
  by `imc_tree_sitter_agrees`.

## 5. Decisions

- **Q1 — the compiler depends on `sysl-lang/json`** (0.2.1), the org's JSON reader, rather than
  carrying a second one.
- **Q2 — the output is one module.** One `c.sysl` is the house shape; `--prefix` plus a pleasant
  module above it is the split.
- **Q3 — a complete record from another header, used by value, is laid out when that header is
  named with `--follow <header>`**, with the same `@assert`s as the header's own. Only the records
  something generated uses are written, so following `time.h` does not pull in the rest of libc.
  Without `--follow` the declaration is refused, and the refusal names the `--follow` that would
  lay it out.
- **Q4 — unions and anonymous members map directly** to sysl's `union` and anonymous blocks; a
  union member sysl cannot express is its bytes, `[N]u8`, with the union's boundary given by
  `@align`. **C bitfields map** to `name: uW in T`, which the language lays out as C does; a
  bitfield whose unit the language cannot hold is refused by name, with its record.
- **Q5 — `--shim <file.c>` ships**: a three-line C wrapper and its `extern` for each `static inline`
  function and each function-like macro whose arguments and result a declared function types;
  what cannot be typed is refused by name. The `.c` goes in the module's directory, where the build
  compiles it.
- **Q6 — `__attribute__((aligned(n)))` with a literal `n` is `@align(n)`**, checked by `ALIGNOF_`
  and its `@assert`. `#pragma pack` stays opaque-and-sized: clang's JSON records that a pack is in
  force but not its value.
- **Q7 — a `char` value is `i8`; behind a pointer it is `*u8`.** C's `char` is signed on the
  machines most C is built for, and a value is where the sign is extended. (It is unsigned on
  Linux's Arm ABIs, where a `char` value outside 0..127 reads back with the other sign.)
- **Q8 — `ffi.md § What is deliberately absent`** says a header parser is absent from the compiler
  and points at `sysl import-c`; `ffi.md § sysl import-c` and `cli.md § import-c` document it.
