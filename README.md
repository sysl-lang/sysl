# sysl

A modern, ref-counted, OS-level systems language — easier than Rust.

> **Status: fresh restart (design-first).** This repository is a clean reimplementation of the sysl
> language. It is not a port of the earlier prototype — the old tree survives only as a source of
> lessons. Expect the compiler to be built up deliberately behind a written design, one feature at a
> time. There is no usable compiler here yet.

## What sysl is

sysl is a systems language that aims to keep the control a systems language is used for while being
meaningfully easier to learn and work with than Rust. It is ref-counted rather than borrow-checked:
memory is managed through three explicit modes — `T` (value/stack), `&T` (ARC reference-counted
heap), and `*T` (raw pointer) — with no garbage collector. Its intended showcase is an operating
system written in sysl and readable end to end.

The language is designed in writing before it is implemented. The numbered specification lives in
[`docs/design/`](docs/design/) — start there for what sysl is and why it is shaped the way it is.

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
```

That needs a `clang` on the PATH: sysl emits textual LLVM IR and links it with clang.

The JS and Native cross-targets exist in the build but the JVM target is the working one during
development.

## License

ISC — see [LICENSE](LICENSE).
