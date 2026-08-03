---
title: The command line
summary: Six subcommands, the flags they share, and what each one leaves as an exit status.
weight: 30
---

Every subcommand takes a **path**, and a path is either a project root or a single file. That is not
two modes bolted together — a module is a directory and its name is that directory's path relative
to the root, so naming a directory compiles the whole tree under it, one module per directory, and
naming a file compiles that file alone. The [modules reference](/reference/modules/) has the rule
the path is standing on.

## The subcommands

| | what it does |
|---|---|
| `sysl run <path>` | compile and execute |
| `sysl build <path> -o <exe>` | compile to a native executable |
| `sysl build-lib <path> -o <artifact>` | compile a library to a linkable artifact |
| `sysl test <path>` | run the `#test` functions |
| `sysl emit-llvm <path>` | print the generated LLVM IR |
| `sysl targets` | list the machines sysl can build for |

A subcommand is required; sysl with none exits 2 and prints its usage.

### `run`

```bash
sysl run hello.sysl
```

Four things in a row: the source is parsed and checked, textual LLVM IR is emitted, `clang`
assembles and links it, and the binary runs. The executable goes to a temporary file and is removed
afterwards — `run` leaves nothing behind.

**Everything after a bare `--` belongs to the program**, not to sysl:

```bash
sysl run report.sysl -- --verbose report.txt
```

The split is made *before* sysl's own options are parsed, which is the point: `--verbose` is one of
the program's arguments even though it looks like it could have been one of sysl's, and neither side
has to know what the other's flags are called. What arrives at
[`main(args: []string)`](/library/args/) is the executable's own path followed by those two words,
so `args.len` is 3 — `args[0]` is the program, exactly as C's `argv[0]` is.

**`run` exits with the status the program exited with.** It is running your program, so its status
is your program's — a compilation that failed is what exits 1 on sysl's own behalf.

### `build`

```bash
sysl build hello.sysl -o hello
```

The same compilation, stopping at the executable instead of running it. `wrote hello` goes to
stderr so that stdout stays whatever the build was for.

**`-o` is optional.** Given none, the name is the source path's last segment with its extension
dropped — `src/tools/fmt.sysl` becomes `fmt` in the current directory — and `a.out` if that leaves
nothing.

### `build-lib`

```bash
sysl build-lib mylib -o mylib.syslib
sysl run prog.sysl --lib mylib.syslib
```

A library compiled once into the two halves a program links against. See
[modules](/reference/modules/) for what an artifact holds and why the generic half of it travels as
trees rather than as object code.

Building one needs an `llvm-ar` as well as a `clang`, because a `.syslib` **is** an `ar` archive —
[installation](/getting-started/installation/) has the note about which `ar` and why the platform
one will not do.

### `test`

```bash
sysl test <path>
sysl test <path> --filter <text>
sysl test <path> --fail-fast
```

The tree is compiled once, into a binary that runs one named test per process, and the runner starts
it once per test. [Attributes](/reference/attributes/) has `#test` itself — what a test may be, what
every other build does with one, and why the process per test is the mechanism rather than a cost.

The report groups by the file each test was written in, keeps source order inside a file, and shows
a test's output **only** where it failed:

```
running 4 tests

clamp.sysl
  ok    clamps below the low bound   6ms
  ok    clamps above the high bound  5ms
  FAIL  leaves a value in range      5ms
        did not return — exit status 1
        at clamp.sysl:31
        > panic: clamp(4, 1, 3) should be 3
  ok    is idempotent                5ms

3 passed, 1 failed — 21ms
```

A failure's own line is one of three sentences and never more: **`did not return — exit status n`**,
which is what a failed `assert` looks like since `assert` prints and exits; **`returned, and was
expected to trap`**; and **`trapped, but printed nothing holding "…"`**, for a
`#test(should_trap: "…")` whose run trapped without saying it. Everything the run printed follows
underneath, prefixed `>`.

`--filter` keeps the tests whose name **or module** holds the text, and the header says how many of
how many are running. `--fail-fast` stops the loop rather than the report: what ran is still
reported and what never ran is simply absent, because "skipped" would be a third verdict for
something that is not a verdict.

**Exit status is 0 if and only if every test that ran passed.** A tree with no tests at all, and a
filter that matched none of the tests there are, both exit 0 — neither is a failure, and each says
which of the two happened rather than leaving one empty report to mean both.

### `emit-llvm`

```bash
sysl emit-llvm hello.sysl
```

The IR to stdout, the same text `run` and `build` hand to clang. Nothing is assembled and no
toolchain is needed for it.

### `targets`

The registry, one line per machine — the name to write after `--target`, the LLVM triple it stands
for, and, for a target sysl knows and cannot build for, why not:

```
aarch64-macos         arm64-apple-macosx
x86_64-macos          x86_64-apple-macosx
aarch64-linux         aarch64-unknown-linux-gnu
x86_64-linux          x86_64-unknown-linux-gnu
riscv64-linux         riscv64-unknown-linux-gnu
x86_64-windows        x86_64-pc-windows-msvc
aarch64-freestanding  aarch64-none-elf
x86_64-freestanding   x86_64-unknown-none-elf
riscv64-freestanding  riscv64-unknown-elf
x86-linux             i386-unknown-linux-gnu  (32-bit — not yet supported)
```

The line for the machine you are on is marked `(this machine)`, and a last line repeats what that
machine's own runtime called itself. That last line is there for the case the rest of the list
cannot help with: on a machine sysl has no entry for, it is the only place to read what the machine
actually said.

`x86-linux` is listed *because* it cannot be built for. The limit is the compiler's rather than the
machine's — the emitted code assumes a 64-bit address in places nothing has been asked to
parameterize yet — and a reader who names it is better told that than told the name is unknown.

## Flags every subcommand takes

| | |
|---|---|
| `--target <name>` | the machine to build for; defaults to this one |
| `--lib <path>` | a library to compile against; may be given more than once |
| `--core-lib <path>` | a prebuilt standard module |
| `--no-core-lib` | the copy of the standard module built into the compiler |
| `--ar <path>` | the `llvm-ar` to build a library with |
| `-O <level>` | the optimization level handed to clang |
| `--explain-escapes` | report every local array promoted to the heap |

The standard-module flags and `-O` are covered in
[installation](/getting-started/installation/), including why the default is `-O1` and not off.

### `--lib` takes either a source tree or an artifact

Which one a path names is read off the name: a `.syslib` is decoded, anything else is walked as
source. That is deliberate — how a library was shipped is the shipper's business, and a program
depending on one should not have to write down which it got. `build-lib` is what turns the first
into the second, and the only difference downstream is what the compilation *cost*: an artifact is a
linear decode where source is a parse.

### `--target`, and the one thing `run` will not do

Given no `--target`, a build is for the machine it is running on. If that is a machine sysl has no
entry for, it says so and stops rather than guessing, because a wrong guess produces a module that
looks right and is not.

`run` executes what it builds, and only this machine can do that, so a cross target is refused
before the build rather than after it:

```
sysl: error: 'run' executes what it builds, and 'x86_64-linux' is not this machine — use 'sysl build --target x86_64-linux'
```

A name the registry does not have is answered with the names it does:

```
sysl: error: unknown target 'arm-linux' — sysl knows aarch64-macos, x86_64-macos, aarch64-linux, x86_64-linux, riscv64-linux, x86_64-windows, aarch64-freestanding, x86_64-freestanding, riscv64-freestanding, x86-linux
```

### `--explain-escapes`

On stderr, one line per local array the compiler moved to the heap, and the view that forced the
move:

```
$ sysl build --explain-escapes tty.sysl
tty.sysl:31:12: 'buf' is promoted to the heap, because this view of it is returned
```

The position is the **view**, not the declaration, because that is the half a reader cannot work out
for themselves. Where nothing was promoted it says so, in as many words, rather than printing
nothing and leaving you to wonder whether the flag took. [Memory](/reference/memory/) has what
promotion is and when it happens instead.

### `-O2` is written the way clang writes it

A short option normally takes its value as the next argument, and clang's optimization flag has been
written joined since cc. So `-O2` is rewritten into `-O 2` before the options are parsed — only that
letter, and only where something follows it, so a bare `-O` still takes the next argument and
`--optimize` is untouched. Nothing in that rewrite can reach the program's own arguments, which were
already split off at the `--`.

## Two combinations that are refused

Neither is resolved by precedence, because whichever precedence were chosen would silently discard
half of what was asked for:

```
sysl: error: --core-lib compiles against the standard module, and 'build-lib --core' is what builds it
sysl: error: --no-core-lib and --core-lib ask for different standard modules
```

The first is `build-lib --core --core-lib x`, which cannot mean anything: the declarations being
compiled are the ones the artifact holds. The second is the pair of near-identical spellings that a
typo produces.

## Exit statuses

| | |
|---|---|
| **0** | it worked — and for `test`, every test that ran passed |
| **1** | a compiler diagnostic, a driver error, or a failing test |
| **2** | the command line did not parse |
| *the program's* | `run` only, once the program has started |

A compiler diagnostic is printed exactly as the compiler wrote it, with its location and a caret
under the offending column. A driver error — something that went wrong *around* the compilation
rather than inside it — is prefixed `sysl: error:`, which is why every message quoted on this page
carries it and none of the ones on the language pages do.

---

Next: the [tour](/tour/), which uses `run` throughout.
