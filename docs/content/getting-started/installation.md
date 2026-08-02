---
title: Installation
summary: Build the compiler from source, and install the toolchain it emits into.
weight: 10
---

There are no binary releases yet. The compiler is a Scala 3 cross-project, so the path in is a
clone and an sbt build.

## What you need

| | why |
|---|---|
| **JDK 17+** | the compiler is written in Scala and runs on the JVM |
| **sbt 1.12+** | builds it |
| **clang** | sysl emits textual LLVM IR; clang assembles and links it |
| **llvm-ar** | only for building a library — a `.syslib` is an `ar` archive of objects |

`clang` is the only one most systems already have. On macOS the Xcode command-line tools supply
one; on Debian and Ubuntu it is the `clang` package.

`llvm-ar` matters only when you build a library of your own, and it has to be the LLVM one: a
`.syslib` holds objects for the machine it was built *for*, and a platform archiver indexes only
its own format and silently drops the rest. On a Mac, Homebrew keeps its LLVM deliberately off the
`PATH`, so sysl looks in `/opt/homebrew/opt/llvm/bin` as well. `--ar` names one anywhere else.

## Build the compiler

```bash
git clone https://github.com/edadma/sysl.git
cd sysl
sbt syslJVM/compile
```

The JVM target is the one to develop against. JS and Native cross-targets exist in the build, but
the JVM one is what gets used.

## Check it

```bash
./run-example.sh
```

That compiles `examples/hello.sysl` all the way to a native binary and runs it. If you see
`Hello, sysl!` followed by a page of output, everything is in place.

To run a different file, name it — and anything after a `--` goes to the program rather than to
sysl:

```bash
./run-example.sh examples/args.sysl -- -n one two
```

Under the script is the CLI, which you can call directly:

```bash
sbt "syslJVM/run run examples/hello.sysl"
```

## The standard library

Every program is compiled against the standard module. You do not have to build it: when nothing
usable is at the default path, the compiler builds the artifact itself out of the library source it
carries, says so on stderr, and gets on with the compilation. A fresh clone just works.

Two flags matter when you want something other than that. `--core-lib <path>` names an artifact
explicitly, and an artifact you named is never rebuilt behind your back — if it will not read, the
compilation stops and says so. `--no-core-lib` compiles against the copy built into the compiler
with no toolchain involved at all, which is the path the compiler's own test suite takes.

## If something goes wrong

**`clang: command not found`** — sysl got as far as emitting IR and had nothing to hand it to.
Install clang and try again.

**`llvm-ar` complaints when building a library** — you have the platform archiver, not LLVM's.
Point at LLVM's with `--ar /path/to/llvm-ar`.

**sbt is slow on the first run** — it is downloading Scala and the dependency tree. This happens
once.
