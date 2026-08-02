# sysl

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
  run by the test suite, so a page that has drifted from the compiler fails the build.
- **[`docs/design/`](docs/design/)** — the numbered specification. The language is designed in
  writing before it is implemented, and each chapter carries the argument for its rules along with
  the alternatives that were rejected.
- **[`guide/`](guide/)** — complete working programs at the size where the choices start to matter.

The site source is `docs/`, built with [juicer](https://juicer.build/) and deployed from `dev`.

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
sysl and the `.c` shims that read whatever only a header knows — so it takes two commands, because a
program compiles against the built artifact rather than against the tree:

```bash
sbt "syslJVM/run build-lib bindings/regex/lib -o /tmp/rx.syslib"
sbt "syslJVM/run run bindings/regex/match.sysl --lib /tmp/rx.syslib"
```

`bindings/regex` binds POSIX regular expressions and needs nothing installed, since they are part of
libc. `bindings/sqlite3` binds SQLite and needs its header and library; the program says nothing
about linking, because `link "sqlite3"` is written once in the binding and travels in the artifact.

A program's own unit tests are `#test` functions written beside what they test, and `sysl test` is
what runs them (`docs/design/testing.md`):

```bash
sbt "syslJVM/run test guide/ring"                  # every #test under a directory
sbt "syslJVM/run test guide/ring --filter empty"   # the ones whose name holds this
```

A test passes by returning; `#test(should_trap)` is for the ones whose subject is a check that
should fire, and passes only if the run does not come back. Every other build drops the tests, so
they cost a program nothing.

Every program is compiled against the standard module, which is built once after a clone:

```bash
sbt "syslJVM/run build-lib lib --core"   # writes .sysl/core.syslib
```

A compilation with no `--core-lib` looks there and stops if it finds nothing, the same as a C
compiler that cannot find its libc. `--no-core-lib` compiles against the copy built into the
compiler, which is what the test suite uses and what makes the first build possible.

Building a library also needs an **`llvm-ar`**, because a `.syslib` is an `ar` archive whose members
are objects for the machine it was built *for*: a platform archiver indexes only its own format and
silently drops the rest. On a Mac, Homebrew's LLVM is deliberately off the `PATH`, so sysl looks in
`/opt/homebrew/opt/llvm/bin` as well; `--ar` names one anywhere else.

The JS and Native cross-targets exist in the build but the JVM target is the working one during
development.

## License

ISC — see [LICENSE](LICENSE).
