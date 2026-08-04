---
title: Installation
summary: Install the compiler from the tap, or build it from source.
weight: 10
---

## Install

```bash
brew install edadma/tap/sysl
```

That is a native binary — there is no JVM under it and nothing to start up. It brings LLVM with it,
which sysl needs at runtime: the compiler emits textual LLVM IR and hands it to `clang` to assemble
and link, and `llvm-ar` is what builds a library into a `.syslib`.

Check it, and see what it offers:

```bash
sysl --version
sysl --help
```

**macOS on Apple silicon only, for now.** Other platforms build from source, below; a Linux binary
needs a build machine that is not the author's laptop and is not here yet.

## Your first compile

```bash
echo 'main()
    print("Hello, sysl!")' > hello.sysl
sysl run hello.sysl
```

The first run prints a line on stderr about building the standard module. That is expected and it
happens once — see below.

`sysl build` compiles without running, leaving an executable you can ship:

```bash
sysl build hello.sysl -o hello
./hello
```

## Build from source

The compiler is a Scala 3 cross-project, so the path in is a clone and an sbt build. You want this if
you are working *on* sysl, or if you are on a platform the tap has no binary for.

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

```bash
git clone https://github.com/edadma/sysl.git
cd sysl
sbt syslJVM/compile
```

The JVM target is the one to develop against. JS and Native cross-targets exist in the build, and the
Native one is what the released binary is built from:

```bash
SYSL_RELEASE=1 sbt syslNative/nativeLink
```

Without `SYSL_RELEASE` that links in debug mode, which is much faster and is what you want while
working on the compiler.

### Check it

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
carries, says so on stderr, and gets on with the compilation. A fresh install just works.

It goes in your cache directory — `~/Library/Caches/sysl/` on macOS, `~/.cache/sysl/` on Linux, or
wherever `XDG_CACHE_HOME` points — under a fingerprint of the library it was built from. So it is
built once per machine rather than once per project, nothing is written into your source tree, and
installing a compiler that carries a different library gets its own entry instead of a stale hit.
Nothing there is ever evicted, and everything in it is derived: deleting the directory costs one
rebuild.

Two flags matter when you want something other than that. `--std-lib <path>` names an artifact
explicitly, and an artifact you named is never rebuilt behind your back — if it will not read, the
compilation stops and says so. `--no-std-lib` compiles against the copy built into the compiler
with no toolchain involved at all, which is the path the compiler's own test suite takes.

## Optimization

`-O` names the level handed to clang, spelled the way clang spells one — `-O2`, `-Os`, `-O0` — and
it reaches every object a build produces rather than only the final link. It can also be written
`--optimize 2`.

The default is **`-O1`**, not off. That is worth knowing because it is unusual: `-O0` is a different
instruction selector rather than merely a slower one, it is the mode a back end's own test suite
covers least, and a real miscompile was found living there. If you drop to `-O0` to make something
easier to debug and the behaviour changes, suspect that before your program.

## If something goes wrong

**`clang: command not found`** — sysl got as far as emitting IR and had nothing to hand it to.
Install clang and try again. Installing from the tap brings LLVM with it, so this is a
built-from-source problem.

**`llvm-ar` complaints when building a library** — you have the platform archiver, not LLVM's.
Point at LLVM's with `--ar /path/to/llvm-ar`.

**sbt is slow on the first run** — it is downloading Scala and the dependency tree. This happens
once.
