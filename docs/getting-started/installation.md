---
title: Installation
summary: Install the compiler from the tap, or build it from source.
weight: 10
---

## Install

```bash
brew tap sysl-lang/tap
brew trust sysl-lang/tap
brew install sysl-lang/tap/sysl
```

**The `brew trust` line is not optional and its absence does not look like a missing step.** Homebrew
requires per-tap trust before it will load a formula, and it checks *after* fetching and verifying
the download — so an install without it fails at the end of a successful download, in words that
read like a defect in the formula:

```
Error: sysl-lang/tap/sysl: Refusing to load formula sysl-lang/tap/sysl from untrusted tap
sysl-lang/tap. Run `brew trust --formula sysl-lang/tap/sysl` or `brew trust sysl-lang/tap`
```

It can also name a formula you did not ask for. `sysl-alpha` conflicts with `sysl`, and a
`conflicts_with` makes Homebrew *load* the other formula to check the conflict — so installing one
is refused over the other's trust, which reads as the wrong formula being installed. Trusting the
tap once covers both, and covers every upgrade after it.

That is a native binary — there is no JVM under it and nothing to start up. It brings **LLVM** with
it, which sysl needs at runtime: the compiler emits textual LLVM IR and hands it to `clang` to
assemble and link, and `llvm-ar` is what archives the standard module's compiled half into the
compiler's cache.

It also brings **pkgconf**. A package that binds an installed C library can name it — `requires {
pkg_config { sdl3 = "…" } }` — and sysl asks `pkg-config` where that library's headers and link line
are, so building against SDL3 or cairo needs no flags. macOS ships no `pkg-config` and the libraries
do not bring one, so the formula does.

Check it, and see what it offers:

```bash
sysl --version
sysl --help
```

**The alpha formula is macOS on Apple silicon only.** The `0.1.0` alphas are cut by the compiler
itself on a Mac and ship one binary, `darwin-arm64`; Linux binaries return when a Linux build is part
of the release. Anything else builds from source, below.

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
| **llvm-ar** | the standard module's compiled half is an `ar` archive of objects |
| **pkg-config** | only for a package that names an installed C library; without it, say where the library is with `--include-path` and `--link-path` |

`clang` is the only one most systems already have — `pkg-config` is common on Linux and absent from a
stock macOS. On macOS the Xcode command-line tools supply
one; on Debian and Ubuntu it is the `clang` package.

`llvm-ar` has to be the LLVM one: the standard module's archive holds objects for the machine it was
built *for*, and a platform archiver indexes only its own format and silently drops the rest. On a Mac, Homebrew keeps its LLVM deliberately off the
`PATH`, so sysl looks in `/opt/homebrew/opt/llvm/bin` as well.

```bash
git clone https://github.com/sysl-lang/sysl-bootstrap.git
cd sysl-bootstrap
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
sbt "syslJVM/run run guide/ring"
```

That compiles one of the guide programs all the way to a native binary and runs it. Each guide
program checks itself, so what you should see is a run of `-- section` headers and `ok` lines and
nothing saying `FAIL` — at which point everything is in place.

Any of the directories in `guide/` works the same way. Where a program declares
`main(args: []string)`, anything after a `--` goes to it rather than to sysl:

```bash
sbt "syslJVM/run run <program> -- one two"
```

## The standard library

Every program is compiled against the standard module, and **its source ships with the compiler**.
An install puts it at `share/sysl/library` under the install prefix — on a Homebrew Mac that is
`$(brew --prefix)/share/sysl/library` — and the compiler finds it from its own location, the way
`rustc` finds its sysroot. Running out of a checkout, it is the `library/` directory in the tree.
Nothing has to be configured, and there is no variable to set.

The directory was called `lib/` until it was renamed, and both spellings are still looked for with
the new one first — so a compiler installed before the change, and a checkout that has not been
updated, both go on working.

It is meant to be read. The library is ordinary sysl, laid out as an ordinary sysl library, and
every function in it is one a program could have written; `sysl build-lib <root> --std` is the same
command that builds anybody else's.

You do not have to build the artifact, either: when nothing usable is at the default path, the
compiler builds it out of that source, says so on stderr, and gets on with the compilation. A fresh
install just works.

It goes in your cache directory — `~/Library/Caches/sysl/` on macOS, `~/.cache/sysl/` on Linux, or
wherever `XDG_CACHE_HOME` points — under a fingerprint of the library it was built from. So it is
built once per machine rather than once per project, nothing is written into your source tree, and
installing a compiler with a different library gets its own entry instead of a stale hit.

**The entry also names the compiler that built it — its version and a digest of its own executable — so
a different build of the same version never reads back another build's archive.** The same key
names the binaries `sysl run` and `sysl test` keep, so neither replays what an earlier compiler made
for an unchanged program. A compiler before 0.0.151 keyed both on the version alone.

**A kept binary is also keyed on where the program's files are, not only on what they say**, because
the binary names its sources by their full path — a test report heads each file with it, and a trap
reports it. So moving a project to another directory costs one rebuild of it, and two identical
trees in two directories are two programs. Before 0.0.152 the second was handed the first one's
binary and reported the other directory's paths as its own.

**The fingerprint also carries the optimization level and, where they are asked for, LTO and a
profile** — the archive is compiled the way the rest of the build is, so `-O3`, `--lto thin` and a
`--profile-use` file each earn their own entry rather than sharing one built at the default level.
The key reads the levers in order, for example `-O3-lto-thin`, so a build that changes only one of
them gets an archive that matches it instead of linking a standard module compiled differently from
everything beside it. A `--profile-use` archive is keyed by what the profile *says*, not by its
path, since the ordinary way of training is to merge a new profile over the old file in place.

Nothing there is ever evicted, and everything in it is derived: deleting the directory costs one
rebuild.

**Nothing names the cached artifact.** To compile against another standard module, set `SYSL_LIB`
to the root its source is in; `--std-lib` is refused, saying exactly that. `--no-std-lib` compiles
the standard module from its source together with the program instead of linking the cached one.

## Optimization

`-O` names the level handed to clang, spelled the way clang spells one — `-O2`, `-Os`, `-O0` — and
it reaches every object a build produces rather than only the final link. It can also be written
`--optimize 2`.

The default is **`-O1`**, not off. That is worth knowing because it is unusual: `-O0` is a different
instruction selector rather than merely a slower one, it is the mode a back end's own test suite
covers least, and a real miscompile was found living there. If you drop to `-O0` to make something
easier to debug and the behaviour changes, suspect that before your program.

A project that wants a level of its own states it once, in its manifest, rather than on every command
line — [`optimization`](/reference/packages/#the-optimization-level-a-project-is-built-at). The flag
still wins where it is given, so a project built at `2` is profiled at `0` by typing it.

## If something goes wrong

**`clang: command not found`** — sysl got as far as emitting IR and had nothing to hand it to.
Install clang and try again. Installing from the tap brings LLVM with it, so this is a
built-from-source problem.

**`cannot find llvm-ar`** — the platform's own `ar` is not a substitute, so sysl looks for LLVM's:
`llvm-ar` on the `PATH`, then `/opt/homebrew/opt/llvm/bin` and `/usr/local/opt/llvm/bin`. Install
LLVM, or put its `bin` on the `PATH`.

**`cannot find the standard module's source`** — the compiler could not find the library it ships
with, and the message lists every path it tried. From a package install that means the install is
incomplete: reinstall it. From a checkout it usually means the working directory is not in the tree.
Either way `SYSL_LIB=/path/to/library` names the library root outright, where the root is the
directory holding `sysl` — that is, the `library` above `library/sysl`, not `library/sysl` itself.
