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
module paths, loading `package.hocon`, writing output, command-line arguments, stdout, exit codes.

**And one question that is not I/O but is just as platform-shaped: what machine is this?**
Each platform describes the same machine in its own words — a JVM says `aarch64` / `Mac OS X`,
Scala Native says `aarch64` / `darwin`, Node says `arm64` / `darwin` — so `hostMachine` is a
per-platform function that hands back the two words verbatim, and the shared core turns the pair
into a target (`targets.md`). Asking is at the edge; deciding is in the core, once, where the
three platforms cannot drift apart. The compiler's own machine reaches the pipeline only as the
default an invocation that names no target gets.

## The libraries at the edge

Two of the author's own cross-published libraries cover the edge, and both are on Central:

- **`path`** (`io.github.edadma:path`) — a fluent `Path` type: `/` composition, `readText`,
  `exists`, `parent`, `filename`, `extension`, `relativeTo`, `normalize`, `toAbsolutePath`,
  `listDirectory` with globs. This is what **module resolution** wants: module names follow the
  directory tree relative to the project root, and `relativeTo` / `normalize` / `startsWith` are
  exactly those operations.
- **`cross_platform`** (`io.github.edadma:cross_platform`) — process-level operations
  `path` doesn't cover: `processArgs`, `stdout`, `processExit`, `nameSeparator`, the temp-file
  operations the toolchain glue writes its `.ll` through, and `exec` — running an external command
  to completion and capturing what it printed.

Both use the best implementation per platform (JVM `java.nio`, Node `fs`/`path`, Native NIO
compat) behind one API.

## Text and Unicode

Scala's `String` is UTF-16 on all three platforms, so **surrogate-pair handling is consistent**
— which matters because `char` is a Unicode scalar value and `'\u{1F600}'` is a supplementary
codepoint (see `00-types-and-expressions.md` §1). Source files are UTF-8 on disk and are decoded
to `String` at the edge; the core only ever sees `String`. Tests should include a supplementary
codepoint to keep this honest across platforms.

## Process spawning — the gap that was closed, and the one still open

Invoking an external tool was once the gap here: linking wants `clang`, and abstracting a spawn
across JVM `ProcessBuilder`, Node `child_process` and Native is genuinely awkward. It is **no longer
a gap** — `cross_platform` grew `exec`, which is what this chapter's own policy said the fix had to
be, so the toolchain glue lives in `shared/` beside the compiler rather than in JVM-only test code,
and `build` and `run` work from every platform's CLI.

**What `exec` does not offer is a child that shares this process's standard input.** It closes the
child's stdin and captures both output streams, which is exactly right for asking `clang` a question
and wrong for `run`: a compiled program that reads standard input sees end of input immediately,
whatever the CLI itself was given. Reading standard input is not a language gap — the library supplies
`sysl.io`'s `stdin()`, `Reader` and `Lines` cursor over `read(2)` (`14 §2`), and needed nothing new from the
language to do it — it is a gap in what the runner hands the program it started, and the only part of
the reading surface no test can drive end to end. What the tests do instead is pass a path through
`argv` and let the program open it, which exercises everything above the descriptor
(`ReadingSurfaceTests`); the two facts left to standard input itself — that it is descriptor zero and
that a cursor over an empty one ends rather than hangs — are checked directly. Closing the gap means
an `exec` variant that **inherits** the
parent's three streams and reports only a status, since a program sharing the terminal has nowhere to
capture output *to*. By the policy below that belongs in `cross_platform`, not here.

## Library policy

`path`, `cross_platform`, and `indentation` are the author's own libraries. Per
`principles.md` §1 and the standing fix-at-source rule: **if one lacks something we need, change
the library and publish it — never work around it in sysl.**

Current state of that policy:

- `path`, `cross_platform` and `indentation` — all three on Central, used as-is. `build.sbt` holds
  the versions; naming them here only guarantees this chapter goes stale behind it, which is what
  had happened by the time anyone checked.
- **`indentation` is the policy's worked example.** It sat at 0.0.2 while the commits for 0.0.3
  (`blockTriggerToken`) and 0.0.4 (`isLineContinuationToken`) were written and never released, and
  trailing-operator line continuation waited on that release rather than on a workaround here. The
  release happened; `isLineContinuationToken` is what the lexer's line continuation now reads, and
  `blockTriggerToken` is still unused because `front-end.md` decided `None` for it.
- **Open against the policy: an inheriting `exec`.** See the section above — `run` cannot give a
  program the standard input it was started with until `cross_platform` offers a spawn that passes
  the streams down.
