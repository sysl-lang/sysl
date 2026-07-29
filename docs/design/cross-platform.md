# Cross-Platform Architecture

**Status:** decided. The compiler is a Scala cross-project (JVM / JS / Native) and must build and
behave identically on all three. This doc settles *how* — deliberately, since the previous
attempt left it to chance.

## The rule: pure core, I/O at the edge

**The compiler core is pure and platform-free.** Lexer, parser, analyzer, and codegen live in
`shared/` and touch **no I/O and no platform APIs**. Their contract is:

```
(source text: String, a logical file identity) → tokens → AST → typed AST → LLVM IR text
```

Everything in that pipeline is a pure function over strings and data. Consequences:

- the core is cross-platform **by construction** — there is no platform-specific code path that
  could make semantics diverge between JVM, JS, and Native;
- **Tier-1 tests are pure** (see `testing.md`) — no filesystem, no toolchain, so they run
  identically everywhere and stay fast;
- codegen emitting **textual** LLVM IR (already decided in `testing.md`) fits this exactly: IR
  generation is a String-producing pure function, not a native-binding call.

**All I/O is confined to a thin edge layer** — the CLI/driver: reading source files, resolving
module paths, loading `sysl.conf`, writing output, command-line arguments, stdout, exit codes.

**And one question that is not I/O but is just as platform-shaped: what machine is this?**
Each platform describes the same machine in its own words — a JVM says `aarch64` / `Mac OS X`,
Scala Native says `aarch64` / `darwin`, Node says `arm64` / `darwin` — so `hostMachine` is a
per-platform function that hands back the two words verbatim, and the shared core turns the pair
into a target (`targets.md`). Asking is at the edge; deciding is in the core, once, where the
three platforms cannot drift apart. The compiler's own machine reaches the pipeline only as the
default an invocation that names no target gets.

## The libraries at the edge

Two of the author's own cross-published libraries cover the edge, and both are on Central:

- **`path`** (`io.github.edadma:path:0.0.6`) — a fluent `Path` type: `/` composition, `readText`,
  `exists`, `parent`, `filename`, `extension`, `relativeTo`, `normalize`, `toAbsolutePath`,
  `listDirectory` with globs. This is what **module resolution** wants: module names follow the
  directory tree relative to the project root, and `relativeTo` / `normalize` / `startsWith` are
  exactly those operations.
- **`cross_platform`** (`io.github.edadma:cross_platform:0.1.7`) — process-level operations
  `path` doesn't cover: `processArgs`, `stdout`, `processExit`, plus `nameSeparator`.

Both use the best implementation per platform (JVM `java.nio`, Node `fs`/`path`, Native NIO
compat) behind one API.

## Text and Unicode

Scala's `String` is UTF-16 on all three platforms, so **surrogate-pair handling is consistent**
— which matters because `char` is a Unicode scalar value and `'\u{1F600}'` is a supplementary
codepoint (see `00-types-and-expressions.md` §1). Source files are UTF-8 on disk and are decoded
to `String` at the edge; the core only ever sees `String`. Tests should include a supplementary
codepoint to keep this honest across platforms.

## The known gap: process spawning

Tier-2/3 tests must invoke external tools (`lli`, `clang`). **Neither library provides process
spawning**, and it is genuinely hard to abstract (JVM `ProcessBuilder` vs Node `child_process`
vs Native).

This is acceptable because **tests run on the JVM**: tool invocation lives in JVM-only test
code (`jvm/src/test`), not in `shared/`. The pure core stays untouched by it.

If the *shipped CLI* ever needs to invoke tools on all platforms, that is a real gap — and the
fix is to **add process spawning to `cross_platform`**, not to work around it here.

## Library policy

`path`, `cross_platform`, and `indentation` are the author's own libraries. Per
`principles.md` §1 and the standing fix-at-source rule: **if one lacks something we need, change
the library and publish it — never work around it in sysl.**

Current state of that policy:

- `path` 0.0.6 and `cross_platform` 0.1.7 — on Central, used as-is.
- `indentation` — Central has only **0.0.2**, which is what we depend on. Commits exist for
  0.0.3 (`blockTriggerToken`) and 0.0.4 (`isLineContinuationToken`) but **were never tagged or
  published**. We do not need `blockTriggerToken` (decided `None` in `front-end.md`). We *do*
  eventually want `isLineContinuationToken` for trailing-operator line continuation — that will
  be the first real trigger to publish an `indentation` release (with a proper tag and GitHub
  release) and bump the dependency.
