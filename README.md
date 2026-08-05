<p align="center">
  <img src="https://sysl.sh/sysl-wordmark.svg" alt="sysl" width="360">
</p>

<p align="center">
  <a href="https://sysl.sh/"><img alt="Site" src="https://img.shields.io/badge/docs-sysl.sh-6f1f9e"></a>
  <a href="https://github.com/edadma/sysl/commits"><img alt="Last Commit" src="https://img.shields.io/github/last-commit/edadma/sysl"></a>
  <img alt="License" src="https://img.shields.io/github/license/edadma/sysl">
  <img alt="Scala Version" src="https://img.shields.io/badge/Scala-3.8.4-blue.svg">
  <img alt="Scala.js Version" src="https://img.shields.io/badge/Scala.js-1.21.0-blue.svg">
  <img alt="Scala Native Version" src="https://img.shields.io/badge/Scala_Native-0.5.12-blue.svg">
</p>

A modern, ref-counted, general-purpose systems language — easier than Rust.

> **Status: design-first, and it runs.** This repository is a clean reimplementation of the sysl
> language — not a port of the earlier prototype, which survives only as a source of lessons. The
> compiler is built up deliberately behind a written design, one feature at a time, and it compiles
> programs to native binaries through LLVM today: see [`guide/`](guide/) for complete ones, and the
> [tour](https://sysl.sh/tour/) to learn the language.

## What sysl is

sysl is a general-purpose systems language that aims to keep the control a systems language is used
for while being meaningfully easier to learn and work with than Rust. It is ref-counted rather than
borrow-checked: memory is managed through explicit modes written on the type — `T` (value/stack),
`&T` (ARC reference-counted heap), and `*T` (raw pointer) — with no garbage collector, plus `ref`,
a local binding that gives a second name to storage something else already owns.

## Documentation

- **[The tour](https://sysl.sh/tour/)** — the language and its standard library in one
  pass, from `print` to a program that reads its input. Every program on those pages is compiled and
  run by a test suite, so a page that has drifted from the compiler fails.
- **[`design/`](design/)** — the numbered specification. The language is designed in
  writing before it is implemented, and each chapter carries the argument for its rules along with
  the alternatives that were rejected.
- **[`guide/`](guide/)** — complete working programs at the size where the choices start to matter.

The site lives in **[sysl-lang/sysl.sh](https://github.com/sysl-lang/sysl.sh)**, which drives this
compiler as a published dependency. That is deliberate rather than incidental: a website documents
the *released* language, so its pages are checked against whatever version is on Central, and a page
demonstrating something only `dev` can do would be wrong for the person reading it.

What stays here is the specification, which belongs in the same commit as the code implementing it,
and `guide/` + `examples/`, which are checked against `dev` on every run.

## Building

This is a Scala 3 cross-project (the compiler is written in Scala). During bring-up:

```bash
sbt syslJVM/compile     # compile
sbt syslJVM/test        # run the test suite
sbt syslJVM/run         # run the CLI
```

To see the language run end to end — source, through the compiler, to a native binary:

```bash
./run-example.sh                     # examples/hello.sysl
./run-example.sh examples/hello.sysl # or name one
./run-example.sh examples/args.sysl -- -n one two
```

Anything after a `--` goes to the program rather than to sysl, which is what a `main(args: []string)`
reads — the same convention `sysl run <path> -- <args>` follows.

That needs a `clang` on the PATH: sysl emits textual LLVM IR and links it with clang.

`bindings/` holds bindings to real C libraries. Each is a library rather than a single file — its
sysl and the `.c` shims that read whatever only a header knows — and `--lib` takes the tree itself:

```bash
sbt "syslJVM/run run bindings/regex/match.sysl --lib bindings/regex/lib"
```

Building it into an artifact first is the other road, and gives a program that compiles without the
library's source anywhere near it:

```bash
sbt "syslJVM/run build-lib bindings/regex/lib -o /tmp/rx.syslib"
sbt "syslJVM/run run bindings/regex/match.sysl --lib /tmp/rx.syslib"
```

Both carry the shims: a `.c` anywhere in a tree is compiled with it (`design/15 §7`), whether the
tree is a library, a package a `dependencies` block brought in, or the project itself.

`bindings/regex` binds POSIX regular expressions and needs nothing installed, since they are part of
libc. A program built against it says nothing about linking, because `link "regex"` is written once
in the binding and travels with it.

**The SQLite binding used to live here and now has a repository of its own** —
[sysl-lang/sqlite3](https://github.com/sysl-lang/sqlite3), the first sysl package outside this tree
(`design/packages.md`). It moved because a binding to a library nobody is obliged to have
installed is a *package*, not an example, and keeping it here made the compiler's own suite depend on
SQLite being present.

A program's own unit tests are `@test` functions written beside what they test, and `sysl test` is
what runs them (`design/testing.md`):

```bash
sbt "syslJVM/run test guide/ring"                  # every @test under a directory
sbt "syslJVM/run test guide/ring --filter empty"   # the ones whose name holds this
```

A test passes by returning; `@test(should_trap)` is for the ones whose subject is a check that
should fire, and passes only if the run does not come back. Every other build drops the tests, so
they cost a program nothing.

Every program is compiled against the standard module, and **there is no step to run for it**. The
library's own source ships with the compiler and is read off disk — `share/sysl/lib` under the
install prefix, found from the binary's own location, or `lib/` in a checkout. The artifact built
from it is a derived file — object code for one machine — so a clone, a fresh worktree, or a stale
container after the encoding moved all have the same answer, and the compiler gives it in well under
a second, announced on stderr. It goes in your cache directory, keyed by a fingerprint of the library
it was built from, so every project on the machine shares one and an upgrade needs no invalidating.
`sysl build-lib lib --std` writes one explicitly if you want to.

That rebuild is not a silent fallback. What the design refuses is compiling against a *different*
standard module than the one asked for, so an artifact named with `--std-lib` is never rebuilt and a
compilation stops when it will not read: somebody who wrote down which artifact to use is owed the
truth about that one. `--no-std-lib` is the third way — it compiles the library's source into the
program with no toolchain at all, which is what the test suite takes and what makes the first build
possible.

Building a library also needs an **`llvm-ar`**, because a `.syslib` is an `ar` archive whose members
are objects for the machine it was built *for*: a platform archiver indexes only its own format and
silently drops the rest. On a Mac, Homebrew's LLVM is deliberately off the `PATH`, so sysl looks in
`/opt/homebrew/opt/llvm/bin` as well; `--ar` names one anywhere else.

The JS and Native cross-targets exist in the build but the JVM target is the working one during
development.

## Using the compiler as a library

The command line takes paths, because a program on disk is what it is for. A tool that *generated*
the source it wants compiled — a documentation harness holding a page's code block, an editor with an
unsaved buffer, a test with an inline program — has no file and no reason to make one. The same
compiler is on Maven Central and takes a string:

```scala
libraryDependencies += "io.github.edadma" %% "sysl" % "0.0.4"   // %%% in a cross-project
```

```scala
import io.github.edadma.sysl.api.Sysl

Sysl.run("main()\n    print(1 + 2)")     // Right(Sysl.Run(0, "3\n"))
Sysl.runBody("print(1 + 2)")             // the same, with the `main` supplied
Sysl.compile("main()\n    print(x)")     // Left("… undefined name 'x' …")
```

`compile` and `run` take a whole program. `compileBody` and `runBody` take a **body** — the statements
a program runs, with no `main` written around them, which is what a documentation page shows and what
a test writes inline. Supplying that wrapper is structural rather than textual, which is why it lives
in the compiler rather than in each caller: at a file's top level `f()` is a call and `f() -> int = x`
is a declaration, and nothing about the characters separates them.

Each has a `…Files` form taking several `Sysl.File(name, text, dir)`, where `dir` names the module a
file belongs to when there is no filesystem to read it from.

**No signature here mentions a syntax tree.** Everything is `String`, `Int`, `List` and `Either`, so a
node added to the AST is not a breaking change to anything compiled against this. `Sysl.canRun` says
whether this machine has the clang that linking needs; compiling to LLVM IR needs nothing installed.

## License

ISC — see [LICENSE](LICENSE).
