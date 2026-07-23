# sysl

A modern, ref-counted, OS-level systems language — easier than Rust.

> **Status: fresh restart (design-first).** This repository is a clean reimplementation of the sysl
> language. It is not a port of the earlier prototype — the old tree survives only as a source of
> lessons. Expect the compiler to be built up deliberately behind a written design, one feature at a
> time. There is no usable compiler here yet.

## What sysl is

sysl is a systems language designed to keep the control that makes a systems language worth using
while being meaningfully easier to learn and work with than Rust. Its guiding ideas:

- **Costs are visible.** Three explicit memory modes — `T` (value/stack), `&T` (ARC reference-counted
  heap), `*T` (raw pointer). No garbage collector, no hidden allocation, no borrow checker to fight.
- **Correctness is first-class.** Design-by-contract (`require` / `ensure`), exhaustive `match`, and
  integer-overflow safety are part of the language, not bolted-on lint.
- **It stays readable.** Small enough that its intended showcase — an operating system written in
  sysl — can be read end-to-end.

Where Rust reaches for lifetimes, sysl reaches for ARC and clear ownership trees: less to prove, less
to fight, the same predictability.

## Design decisions for this restart

- **One backend: aarch64 via LLVM.** No interpreter, no multi-backend matrix. Compiled behavior is the
  single source of truth for the language's semantics.
- **Design precedes code.** The language surface is decided in a written spec before it is
  implemented, and each feature ships with tests written in sysl itself.
- **Disciplined testing.** One expected result per test, organized one directory per language feature.

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
