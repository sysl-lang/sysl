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

**The `brew trust` line is required.** Homebrew loads a formula only from a tap you have trusted,
and it checks *after* fetching and verifying the download. Without the trust step the install
fails at the very end, with a message that reads like a defect in the formula:

```
Error: sysl-lang/tap/sysl: Refusing to load formula sysl-lang/tap/sysl from untrusted tap
sysl-lang/tap. Run `brew trust --formula sysl-lang/tap/sysl` or `brew trust sysl-lang/tap`
```

Trusting the tap once covers every formula in it and every later upgrade.

The formula installs a native binary. It also installs **LLVM**, which sysl needs at run time: the
compiler emits textual LLVM IR and hands it to `clang` to assemble and link, and `llvm-ar` archives
the compiled part of the standard module into the compiler's cache.

It installs **pkgconf** as well. A package that binds an installed C library can name it —
`requires { pkg_config { sdl3 = "…" } }` — and sysl asks `pkg-config` for that library's headers and
link line, so building against SDL3 or cairo needs no extra flags. macOS does not ship `pkg-config`,
so the formula provides it.

Check the installation, and see what the tool offers:

```bash
sysl --version
sysl --help
```

**The formula supports macOS on Apple silicon only.** sysl 0.7.9 ships a single binary,
`darwin-arm64`. On any other platform, build from source as described below.

## Your first compile

```bash
echo 'main()
    print("Hello, sysl!")' > hello.sysl
sysl run hello.sysl
```

The first run prints a line on stderr saying that it is building the standard module. This is
expected and happens only once; [the standard library](#the-standard-library) below explains why.

`sysl build` compiles without running, and leaves an executable you can ship:

```bash
sysl build hello.sysl -o hello
./hello
```

## Build from source

**The compiler is written in sysl**, so building it takes a clone of the repository and an existing
`sysl` compiler. Build from source if you are working on sysl itself, or if you are on a platform
the tap has no binary for.

| | why |
|---|---|
| **an installed `sysl`** | the compiler is a sysl program, so building it needs a sysl compiler — the seed |
| **clang** | sysl emits textual LLVM IR; clang assembles and links it |
| **llvm-ar** | the compiled part of the standard module is an `ar` archive of objects |
| **pkg-config** | only for a package that names an installed C library; without it, give the library's location with `--include-path` and `--link-path` |

Most systems already have `clang`. On macOS the Xcode command-line tools supply it; on Debian and
Ubuntu it is the `clang` package. `pkg-config` is common on Linux and absent from a stock macOS.

`llvm-ar` must be LLVM's archiver. The standard module's archive holds objects for the machine it
was built *for*, and a platform archiver indexes only its own object format and silently drops the
rest. Homebrew deliberately keeps its LLVM off the `PATH` on a Mac, so sysl also looks in
`/opt/homebrew/opt/llvm/bin`.

The repository holds two projects side by side: **`compiler/`**, the compiler, and **`library/`**,
the standard library it ships. Always build the compiler from `compiler/`. The repository root is
not a project, and a build there reads the library's files as though they belonged to the program.

The build runs in two stages, the same way a release does. The seed first builds the tree against
**its own** library, because `library/` may use forms the seed's library lacks. The result then
builds the tree again against `library/`, and **that second build is the compiler**:

```bash
git clone https://github.com/sysl-lang/sysl.git
cd sysl/compiler
sysl build . -o stage1
SYSL_LIB=$(cd ../library; pwd) ./stage1 build . -o sysl
```

Each stage takes several minutes. Debug and release builds differ only in optimization level: the
compiler's default is `-O1`, and a release passes the same `-O` to both stages.

### Check it

```bash
./sysl --version
./sysl run hello.sysl
```

A compiler built from a checkout reports `sysl 0.0.1`. It is a development build, not a release,
and it [checks no version floor](/reference/packages/#the-oldest-compiler-a-package-builds-with).
Run from `compiler/`, it finds the standard library at `../library` with nothing set; from anywhere
else, set `SYSL_LIB` to the checkout's `library/`. An installation has the same two parts laid out
under a prefix — `bin/sysl` with `share/sysl/library` beside it — and that is where the compiler
looks first.

## The standard library

Every program is compiled against the standard module, and **its source ships with the compiler**.
An installation puts it at `share/sysl/library` under the install prefix — on a Homebrew Mac,
`$(brew --prefix)/share/sysl/library` — and the compiler finds it relative to its own location, the
way `rustc` finds its sysroot. In a checkout it is the `library/` directory of the tree. There is
nothing to configure and no variable to set. A directory named `lib` in the same place is also
accepted; `library` is looked for first.

The library is meant to be read. It is ordinary sysl, laid out as an ordinary sysl library, and
every function in it is one a program could have written. `sysl build-lib <root> --std` is the same
command that builds any other library.

You do not need to build the compiled artifact yourself. When there is no usable one in the cache,
the compiler builds it from that source, says so on stderr, and continues with the compilation, so a
fresh installation works immediately.

The artifact is stored in your cache directory — `~/Library/Caches/sysl/` on macOS, `~/.cache/sysl/`
on Linux, or wherever `XDG_CACHE_HOME` points — under a fingerprint of the library it was built
from. It is therefore built once per machine rather than once per project, nothing is written into
your source tree, and a compiler installed with a different library gets its own entry instead of a
stale one. Entries are limited to 4 GB in total. Beyond that the oldest entries are removed whole,
except one written in the last hour or the one the running compiler is using. Using an entry
refreshes its age, so the entries removed first are the least recently used.

**The entry also records the compiler that built it — its version and a digest of its own
executable — so a different build of the same version never reads another build's archive.** The
same key names the binaries that `sysl run` and `sysl test` keep, so neither reuses what a different
compiler produced for an unchanged program.

**A kept binary is also keyed on where the program's files are, not only on their contents**,
because the binary refers to its sources by full path: a test report heads each file with it, and a
trap reports it. Moving a project to another directory therefore costs one rebuild, and two
identical trees in two directories are two separate programs.

**The fingerprint also includes the optimization level and, when requested, LTO and a profile.**
The archive is compiled the same way as the rest of the build, so `-O3`, `--lto thin` and a
`--profile-use` file each get their own entry rather than sharing one built at the default level.
The key lists these settings in order, for example `-O3-lto-thin`, so a build that changes only one
of them gets a matching archive instead of linking a standard module compiled differently from
everything around it. A `--profile-use` archive is keyed by the profile's contents, not its path,
because the usual way to train is to merge a new profile into the old file in place.

Everything in the cache is derived, so deleting the directory costs only a rebuild.

**No option names the cached artifact.** To compile against a different standard module, set
`SYSL_LIB` to the root of its source; `--std-lib` is refused with a message saying so.
`--no-std-lib` compiles the standard module from source together with the program instead of
linking the cached one.

## Optimization

`-O` sets the level passed to clang, spelled as clang spells it — `-O2`, `-Os`, `-O0` — and it
applies to every object a build produces, not only the final link. It can also be written
`--optimize 2`.

The default is **`-O1`**, not `-O0`. That is unusual, and the reason matters: `-O0` uses a
different instruction selector rather than simply generating slower code, and it is the mode a back
end's own test suite covers least. If you switch to `-O0` to make something easier to debug and the
behaviour changes, suspect the code generator before your program.

A project that needs its own level states it once in its manifest rather than on every command
line — see [`optimization`](/reference/packages/#the-optimization-level-a-project-is-built-at). The
flag still takes precedence where it is given, so a project built at `2` can be profiled at `0`
from the command line.

## If something goes wrong

**`clang: command not found`** — sysl emitted the IR and found no `clang` to hand it to. Install
clang and try again. The tap installs LLVM with the compiler, so this only affects a build from
source.

**`cannot find llvm-ar`** — the platform's own `ar` is not a substitute, so sysl looks for LLVM's:
`llvm-ar` on the `PATH`, then `/opt/homebrew/opt/llvm/bin` and `/usr/local/opt/llvm/bin`. Install
LLVM, or put its `bin` directory on the `PATH`.

**`cannot find the standard module's source`** — the compiler could not find the library it ships
with, and the message lists every path it tried. For a package installation this means the
installation is incomplete: reinstall it. From a checkout it usually means the working directory is
outside the tree. In either case `SYSL_LIB=/path/to/library` names the library root directly. The
root is the directory that contains `sysl` — the `library` above `library/sysl`, not `library/sysl`
itself.
