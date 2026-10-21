---
title: The command line
summary: The subcommands, the flags they share, and what each one leaves as an exit status.
weight: 30
---

Every subcommand takes a **path**, which is either a project root or a single file. Both follow from
one rule: a module is a directory, named by its path relative to the root. Naming a directory
compiles the whole tree under it, one module per directory, and naming a file compiles that file
alone. The [modules reference](/reference/modules/) describes the rule in full.

## The subcommands

| | what it does |
|---|---|
| `sysl run <path>` | compile and execute |
| `sysl build <path> -o <exe>` | compile to a native executable |
| `sysl build-lib <path>` | check a library for one machine, writing nothing |
| `sysl build-c <path> -o <archive>` | compile to a static archive and a C header, for a C project |
| `sysl test <path>` | run the `@test` functions |
| `sysl emit-llvm <path>` | print the generated LLVM IR |
| `sysl emit-ast <path>` | print one file's untyped parse tree, as deterministic text |
| `sysl emit-typed <path>` | print one module's typed tree, as deterministic text |
| `sysl emit-header <path>` | print the C header for what a module exports |
| `sysl weave <path>` | render a literate source as an HTML document |
| `sysl tangle <path>` | print the program a literate source holds |
| `sysl deps <path>` | print the dependency graph the project resolves to |
| `sysl add <coordinate>` | add a dependency to the project's manifest |
| `sysl vendor <path>` | put what the project depends on into `vendor/` |
| `sysl tidy [<path>]` | drop the `sysl.sum` lines for versions the project no longer resolves |
| `sysl doc <path>` | generate an API reference from declarations and their doc comments |
| `sysl targets` | list the machines sysl can build for |
| `sysl prove <path>` | discharge the contracts with Why3 — see [verification](/reference/verification/#sysl-prove) |
| `sysl lsp` | serve the Language Server Protocol to an editor, on standard input and output |

A subcommand is required. Run with none, sysl says so on stderr, prints its usage, and exits 2.

An unknown subcommand is refused on stderr with the list of valid ones, and sysl exits 2. Every
command is built in; sysl does not look for external commands on the `PATH`:

```text
sysl: error: 'wave' is not a sysl command — the commands are run, build, build-lib, build-c, emit-header, weave, tangle, test, emit-llvm, emit-ast, emit-typed, prove, deps, add, vendor, tidy, doc, targets, lsp
```

**A command's product goes to stdout, and everything it reports about the run goes to stderr**: a
diagnostic, a refusal, a command-line error with the usage under it, `wrote <exe>`, and what
`--verbose` reports. `sysl emit-llvm hello.sysl > hello.ll` therefore holds a complete module or
nothing. `sysl --help` and `sysl --version` produce what was asked for, so they write to stdout and
exit 0.

### `run`

```bash
sysl run hello.sysl
```

`run` parses and checks the source, emits textual LLVM IR, has `clang` assemble and link it, and
runs the binary.

**Running an unchanged program a second time builds nothing.** The executable is kept, keyed on
everything that can affect its bytes: the compiler's version, the target, the allocator, the
optimization level, every source file of the program and the C files beside it, every `--lib`
artifact, the standard module, and the search and link paths. With nothing changed, the same binary
runs again; change a byte of any input and the key changes, so a kept binary cannot be stale. **The
program's own arguments are not part of the key**: `sysl run p -- a` and `sysl run p -- b` run one
binary twice.

This saves most of what `run` costs, because compilation usually dominates. In a 9,600-line module
with three git dependencies, a program whose body is `print("hi")` takes 9.2 seconds to compile and
almost no time to run; with the binary kept, only the run remains.

**`test` keeps its binary the same way**, which matters most for a suite you run repeatedly while
working. `--filter` selects from the list of tests rather than changing what is compiled, so it is
not part of the key.

**`SYSL_NO_CACHE`**, set to any non-empty value, compiles every time. It is meant for working *on
the compiler*, where the version in the key stays the same while the code it produces changes — the
one case the key cannot detect, and the same one the standard module's cache has. No other
subcommand consults it: `build` writes a binary to the path you named, and `build-c` and `build-lib`
write artifacts for another toolchain.

**`SYSL_TIMINGS=1`** reports how long each phase of a compilation took, one line on standard error
as each phase ends:

```
sysl: phase parse std 130ms
sysl: phase parse program 0ms
sysl: phase declarations 52ms
sysl: phase bodies 23ms
sysl: phase checks 4ms
sysl: phase prune 0ms
sysl: phase std 33ms
sysl: phase codegen 2ms
sysl: phase link 90ms
```

The phases sum to the whole compilation, with nothing counted twice. `std` is the time to find the
standard module's compiled artifact, or to build it on first use. Use these timings to find out
why a build is slow.

**When a phase is slow, a sampling profiler shows which functions are responsible.** On a hosted
target sysl keeps the frame pointer wherever the platform's C compiler does (Apple platforms, and
AArch64 Linux and Android), so `sample` on macOS or `perf` on Linux can walk the stacks of a sysl
program, including the compiler itself, and report callers as well as the functions where the time
went.

**The kept binaries, and the C objects every build keeps beside them, are bounded.** Each directory
— `~/Library/Caches/sysl/run/` and `~/Library/Caches/sysl/cobj/` on macOS, under `~/.cache/sysl/`
on Linux — holds at most 4 GB. Beyond that the oldest entries are removed, except any written in the
last hour and any the current build used. Using an entry refreshes its age, so "the last hour" means
used in the last hour, and the oldest entries are the least recently used. Beyond 256 entries,
anything more than a week older than the newest entry is removed too. The sweep runs once, after a
build that compiled something, and is skipped if another build swept the same directory in the last
ten minutes. Deleting either directory costs one rebuild and nothing else. The same bound, sweep and
exemptions apply to the compiled roots under `roots/` and to the standard module's entries (one
directory per compiler build, target and optimization level, directly under `sysl/`), which are
removed whole, oldest first, and never the one the running compiler uses.

**Everything after a bare `--` belongs to the program**, not to sysl:

```bash
sysl run report.sysl -- --verbose report.txt
```

The split is made *before* sysl parses its own options. `--verbose` is also one of sysl's options,
so without the `--` sysl would take it; after the `--` it belongs to the program, and neither side
needs to know the other's flags. [`main(args: []string)`](/library/args/) receives the executable's
own path followed by those two words, so `args.len` is 3 and `args[0]` is the program, as C's
`argv[0]` is.

**`run` exits with the program's exit status.** sysl exits 1 on its own behalf only when the
compilation fails.

**The program reads sysl's standard input**, so a program that reads input behaves under `run`
exactly as the built binary does:

```bash
printf 'one\ntwo\n' | sysl run count.sysl
```

Output appears as the program writes it, not all at once when it finishes, so an interactive
program that prompts for input works under `run`.

### `build`

```bash
sysl build hello.sysl -o hello
```

The same compilation as `run`, stopping at the executable instead of running it. `wrote hello`
goes to stderr, so that stdout carries only the build's product.

**`-o` is optional, and the default location depends on what you named.** For a **file**, the
executable takes the file's name without its extension, in the current directory:
`src/tools/fmt.sysl` becomes `./fmt`.

For a **directory**, the executable goes *inside* it, named after it. `sysl build .`,
`sysl build fmt` and `sysl build ../fmt` name the same project, and all three write `fmt/fmt`, so
the result does not depend on the directory you run the command from.

**If the project's `package.hocon` names dependencies, `build` fetches any this machine does not
have yet** — see [packages](/reference/packages/). `run` and `test` do the same, so there is no
separate fetch step, and a project with no dependencies fetches nothing.

### `build-lib`

```bash
sysl build-lib mylib --target thumbv7em-freestanding
checked mylib
```

`build-lib` runs a library through the full analysis, against the standard module, for one
machine, and writes nothing. Its purpose is to check a board package on a host, where nothing else
can. A library is passed to a program as the directory its source is in, never as a named artifact
([modules](/reference/modules/#separate-compilation) explains why), so `build-lib` takes no `-o`:

```text
sysl: error: build-lib writes nothing anybody names — it checks a library, and what it compiles is this compiler's cache, found by the compiler alone — so it takes no -o
```

**A library may depend on another**, and `--lib` with the other library's directory declares it,
as it does for any compilation.

**Unlike `build`, `run` and `test`, `build-lib` does not fetch.** It resolves a `dependencies` block
the same way they do — optional dependencies that no feature enables are pruned first, and the
[feature flags](#the-feature-flags) decide which those are — but only over directories. A `path`
dependency is read in place, and a coordinate must be supplied by a `--lib` root or by a path
dependency that [stands in](/reference/packages/#a-source-root-stands-in-for-the-package-it-is) for
it. A coordinate nothing supplies is refused, with a message naming it and the directory that would
supply it. A command whose job is to check one tree for one machine does not go to the network.

**`--cc` names the clang to use when a search would not find the right one.**

```
sysl build hello.sysl --cc /opt/homebrew/opt/llvm/bin/clang
```

It is for a machine with more than one toolchain: a vendor's clang limited to that vendor's
processors, an LLVM installed beside the system one, or a cross toolchain reached by path. A named
compiler is **used exactly as given, with no search and no fallback**: if you named a compiler, you
get an error rather than a different compiler, so naming one that cannot run is refused.

`--cc` applies everywhere sysl runs clang: the link, a package's own C, the `c const` probe, and the
rebuild of the standard module. The last matters because the standard module is rebuilt
automatically when no usable copy is cached; if the flag did not apply there, the build would
switch compilers partway through.

**`build-lib` compiles the package's C, so it requires what that C needs.** A package that
declares [header requirements](/reference/packages/#headers-a-package-needs-and-does-not-carry) is
refused without `--include-path <name>=<dir>`, exactly as it is for `build`, because `build-lib` is
the command a package is checked with before it is published. Only the package's own manifest is
consulted: the C of a `--lib` source root is not compiled here, so that root's header requirements
do not apply to a library built against it.

### `build-c`

```bash
sysl build-c mylib -o libmylib.a
sysl build-c mylib -o libmylib.a --header include/mylib.h
```

`build-c` produces two outputs for an existing C project: a **static archive** to link, and a **C
header** declaring everything the module marked `@export`. The compilation is the ordinary one, not
a library check — the module is lowered for this target with its calls resolved — and the
difference from `build` is that no entry point is emitted, since the C side supplies its own
`main`. The archive holds **native objects** by default. A manifest's `lto` key does not apply to
`build-c`, because LLVM bitcode links only under a linker with LLVM's plugin, and optimizing across
the archive is the C project's link step's responsibility. A C project that links with clang and lld
can request it on the command line: `--lto thin` (or `full`) makes the archive LLVM bitcode,
compiled as `sysl build` compiles its own objects in that mode, so the host's link optimizes across
the boundary. `build-c` then notes on stderr that the archive must be linked with `-fuse-ld=lld`.

The header is written beside the archive, with `.h` appended, unless `--header` names another path.
Both paths are reported on stderr, together with the libraries the C project's link line still
needs: what `@link` named, and the `pkg_config` modules the packages in the build require, given as
the `pkg-config --libs …` command that produces their flags. A missing library shows up on the C
side as an unresolved sysl symbol, which looks like a missing definition rather than a missing
archive, so `build-c` lists them up front. With `--no-std-lib` the standard library is folded into
the object, and the archive is self-contained.

Like `build-lib`, `build-c` needs `llvm-ar` as well as `clang`. [FFI](/reference/ffi/) covers
`@export` itself: what may be exported, how a symbol is named, and how module storage with a computed
initializer is initialized when there is no entry point to do it.

### `emit-header`

```bash
sysl emit-header mylib
```

Prints the header `build-c` writes to stdout, without building anything, for a project that
generates its headers as a build step.

### `test`

```bash
sysl test <path>
sysl test <path> --filter <text>
sysl test <path> --file <name>
sysl test <path> --fail-fast
sysl test <path> --jobs <n>
sysl test <path> --timeout <seconds>
sysl test <path> --std
```

The tree is compiled once, into a binary that runs one named test per process, and the runner starts
it once for each test. [Attributes](/reference/attributes/) covers `@test` itself: what a test may
be, what other builds do with one, and why each test runs in its own process.

**`test` runs the tests of the tree you name, and no others.** A
[dependency](/reference/packages/) is compiled into the binary because the tree's code calls it, but
its `@test` functions are left out. They belong to its author, to run in its own repository; a
consumer cannot change that code, so its tests are not reported in the consumer's run.

**`test` takes the search-path flags as well** — `--link-path`, `--include-path` and `-D`, exactly
as `build` does, and for the same reasons. If a tree's C includes a header the toolchain does not
already know, or its constants come from a [`c const`](/reference/ffi/) block over one, its *tests*
must compile that C just as its programs do. A package binding a system library is the common case,
so most packages need these flags to run their tests.

**`test` takes the [feature flags](#the-feature-flags) too**, and with none given it enables every
feature the manifest declares, not only `default`.

**`test` also compiles each program in the root's `tests/refused/`**, one at a time with the
package's modules in scope, and passes it only if the compiler refuses it with a diagnostic
containing every `// error:` line at its head. These entries are named by path, counted with the
other tests, and selected by `--filter`
([packages](/reference/packages/#a-package-may-test-what-it-refuses)).

`--std` declares that the tree **is** the standard module; this is how sysl's own library is
tested. The compiler supplies `sysl` to every compilation, so without the flag the library would
appear twice — once as the tree being compiled and once as the supplied copy — and every declaration
would be a duplicate. The flag is never inferred: a program with its own `sysl` directory is almost
always a mistake, and inferring `--std` would turn that clear refusal into a collision at link time.

The report groups tests by the file they are written in, keeps source order within a file, and
shows a test's output **only** when it failed:

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

A failure's own line is one of three messages: **`did not return — exit status n`**, which is how
a failed `assert` appears, because `assert` prints and exits; **`returned, and was expected to
trap`**; and **`trapped, but printed nothing holding "…"`**, for a `@test(should_trap: "…")` whose
run trapped without printing the expected text. Everything the run printed follows underneath,
prefixed `>`.

**[The hooks a module may write](/reference/attributes/#the-hooks-a-module-may-write) change what a
`FAIL` line can report, without adding a fourth verdict.** A fault in `@setup` or `@setup_all`
means the test it guards never runs, so there is no assertion to report; the test's `FAIL` line
names the setup function instead of the usual message. A `@setup_all` fault guards the whole
module, so every test in it fails that way and none is reported as started. A fault in `@teardown`
or `@teardown_all` does not affect the test's verdict, since the test has already returned or
trapped when its teardown runs. It is reported as a separate failing entry, named after the hook,
alongside the result of the test it followed.

`--filter` keeps the tests whose name **or module** contains the text, and the header reports how
many of the total are running.

`--file <name>` keeps the tests written in one file, the file a report heads their rows with. The
name is the file's base name, with or without `.sysl` (`--file clamp` and `--file clamp.sysl` are the
same), or as much of its path as is needed to distinguish two files with the same name
(`--file geom/clamp`). **It matches whole path components, never part of one**, so `--file clamp`
does not also run `clamps.sysl`. `--filter` and `--file` are independent: given both, a test must
match each. A refused program under `tests/refused/` is a file of its own, so `--file` selects one
the same way.

**Tests run in parallel — one per processor, or `--jobs <n>`** — and the report is identical to a
sequential run's: each row waits for every row above it, so rows appear in source order, and
`--jobs 1` runs them sequentially. Each test already has its own process, so running them in
parallel shares nothing in memory. What tests can still share is anything that outlives a process,
such as a file at a fixed path or a port. A test that needs exclusive use of one should create its
own, with a temporary directory or a port the system picks, or be listed in its manifest's
[`test.alone`](/reference/packages/#how-a-package-s-suite-is-run), which runs it with nothing else
alongside. The manifest's `test` block can also set the run's `jobs` and `timeout`, and the flags
override it. A module's `@setup_all` finishes before any of its tests starts, and its
`@teardown_all` starts after all of them have finished.

`--fail-fast` stops new tests from starting after the first failure. Tests already running finish
and are reported, everything that ran is reported, and tests that never started are omitted rather
than reported as skipped, since "skipped" is not a verdict.

**Every test has a time limit, so a test that never ends fails instead of hanging the run.**
`--timeout <seconds>` sets the limit for each test — 300 seconds by default, and `--timeout 0` for
no limit — and a test's own
[`@test(timeout: n)`](/reference/attributes/#a-test-may-be-given-a-limit) overrides it for that test.
A test that exceeds its limit is stopped, together with every process it started, and its `FAIL`
line reads **`timed out after Ns`**, followed by whatever it had printed; the run continues with
the next test. A module's hooks are held to the run's limit.

**A test process does not outlive its run.** Each one watches the runner that started it, and if
the runner stops — interrupted, killed, or its terminal closed — the test ends itself and everything
it started, so a stopped run leaves nothing running.

**The exit status is 0 if and only if every test that ran passed.** A tree with no tests, and a
filter that matched none of the tests present, both exit 0. Neither is a failure, and the report
says which of the two occurred.

**None of this mechanism exists on a microcontroller.** A board has no processes, no `argv` and no
exit status, so `sysl test` cannot run code there. [`sysl.harness`](/library/harness/) covers that
case: a framework linked *into* the image, which names its tests, locates a failure and prints a
tally through a writer you provide. Use `sysl test` for code that runs on your development machine,
and `sysl.harness` for checks that can only run on the target.

### `emit-llvm`

```bash
sysl emit-llvm hello.sysl
```

Prints the IR to stdout. It is the same text `run` and `build` pass to clang except for one line:
the printed module's header has only `target triple`, while the text clang compiles also carries
the target's `target datalayout`, obtained from that clang. Without it, LLVM would treat every
64-bit access the text leaves unannotated as four-byte aligned, which on a machine without
unaligned access (riscv64) splits each one in two. Nothing is assembled, and no toolchain is needed.

### `emit-ast`

```bash
sysl emit-ast hello.sysl
sysl emit-ast hello.sysl --no-spans
```

Prints one file's **untyped** parse tree as deterministic text: one node per line, indented under its
parent, with every field the tree carries. It only parses: no analysis runs and the standard module
is not read, so it works on a file that would fail to compile, and it needs no toolchain.
`--no-spans` omits each node's source position, so a diff does not change when lines move. A parse
error exits non-zero and prints the ordinary diagnostic instead.

### `emit-typed`

```bash
sysl emit-typed hello.sysl
sysl emit-typed hello.sysl --no-spans
sysl emit-typed hello.sysl --tables
```

Prints one module's **typed** tree, in the same format as `emit-ast`; it is the analysed
counterpart of `emit-ast`. It runs the whole front end: parsing, then analysis against the standard
module exactly as `emit-llvm` does, with every name resolved and every type inferred. Nothing is
lowered or emitted — no pruning, no code generation, no clang — so, like `prove`, it needs no
toolchain. Every expression's resolved type is printed as the compiler's own diagnostic text
(`int`, `a slice of u8`, `Point`) rather than as the type's internal structure. `--no-spans` leaves
out each node's source position, exactly as it does for `emit-ast`. `--tables` prints the module's
declaration tables instead of its tree — every struct, enum, trait implementation, extern, module
`val` and function, sorted by name, with every signature resolved. An analysis error, or a parse
error, exits non-zero and prints the ordinary diagnostic instead, with nothing on stdout.

### `weave`

```bash
sysl weave library/sysl/regex/vm.lsysl -o vm.html
sysl weave library/sysl/regex -o documents/
```

`weave [-o <path>] <path>` renders a **literate** source as an HTML document. A `.lsysl` file is a
Markdown document whose four-column-indented lines are the program, so it reads well as plain text.
An indented code block carries no *language*, though, so a Markdown renderer cannot highlight it.
`weave` renders exactly the lines the compiler reads as program text as sysl code, as one block
across any blank lines between them, and renders every other line as Markdown, so prose, tables,
illustrations and heading levels are entirely up to the author.

The Markdown renderer is [`sysl-lang/markdown`](https://github.com/sysl-lang/markdown): CommonMark
0.31.2 with the GitHub extensions — tables, strikethrough, bare links, task lists, footnotes, emoji
shortcodes, alerts (`> [!NOTE]`), mathematics as `$…$` and `$$…$$`, and mermaid diagrams — and every
heading gets GitHub's id, so `# The greeting` becomes `<h1 id="the-greeting">`. The whole file is
one document, so a link reference defined anywhere in the prose applies to a link anywhere else, and
a program block closes whatever paragraph, list or quote was open before it. Raw HTML passes
through, under GitHub's tag filter.

The output is a single self-contained file. It carries its own styling, with light and dark
palettes. Its code — every block whose language is sysl, including an illustration fenced as `sysl`
— is coloured by keyword, type, function, string, number, comment and punctuation, using the
lexer's own reserved words. Its mathematics is typeset by KaTeX, which the page links to; that is
the only part of a woven document that needs the network. Read offline, the document shows its
equations as TeX source and is otherwise complete.

```text
$ cat greeting.lsysl
# The greeting

A program of one statement.

    print("hi")
$ sysl weave greeting.lsysl
<!doctype html>
…
<h1 id="the-greeting">The greeting</h1>
<p>A program of one statement.</p>
<pre><code class="language-sysl"><span class="hl-function">print</span><span class="hl-punctuation">(</span><span class="hl-string">&quot;hi&quot;</span><span class="hl-punctuation">)</span>
</code></pre>
…
```

The output goes to standard output, or to the path `-o` names. **A path containing several literate
sources produces one document for each**, and `-o` then names a directory, because each source file
is a document in its own right. Ordinary `.sysl` files alongside are skipped, and a tree with no
literate source is refused rather than producing an empty page.

`weave` is a **source-level** command: it needs no target, no standard module and no libraries, so
a package's documentation can be rendered on a machine that cannot build the package. It reads the
source exactly as the compiler does, so a file the compiler would refuse — a tab in an indent, a
fence that is never closed — is refused here too, with the same message.

`weave` does not generate an API reference from declarations; that is [`doc`](#doc), below, which
reads sysl's [documentation comments](/reference/lexical/#documentation-comments). The two serve
different purposes — `weave` renders a document someone *wrote*, `doc` renders what the declarations
*say* — so a tree often uses both.

### `tangle`

```bash
sysl tangle library/sysl/regex/vm.lsysl
sysl tangle library/sysl/regex/vm.lsysl -o vm.sysl
```

`tangle [-o <path>] <path>` is the counterpart of `weave`: it writes the program with the prose
removed, to standard output or to the path `-o` names. A path containing several literate sources
produces their programs one after another; ordinary `.sysl` files beside them are skipped, and a tree
with none is refused. Every build tangles a `.lsysl` file internally in order to compile it;
`tangle` lets you **see** the result.

That is useful when a literate file behaves unexpectedly — a block indented by mistake, a fence that
swallowed a function — because it shows exactly what the compiler read. It also provides the
program to anything that does not understand the format, such as another tool or a bug report.

**The prose is replaced by blank lines rather than removed**, so line 100 of the output is line 100
of the source. This keeps a diagnostic's line number valid in the original document, and is why the
output is longer than the program itself.

### `deps`

Prints the dependency graph the project resolves to: every package it will be compiled against, the
version selected, and which package asked for it at what minimum version.

```
sysl deps .
```

```
syslui-demo 0.0.1

github.com/sysl-lang/plutovg     0.2.1
    github.com/sysl-lang/syslui-sdl asks for 0.2.1
    github.com/sysl-lang/syslui asks for 0.2.1
github.com/sysl-lang/sdl3        0.3.1
    github.com/sysl-lang/syslui-sdl asks for 0.3.1
github.com/sysl-lang/syslui      0.1.0
    github.com/sysl-lang/syslui-sdl asks for 0.1.0
github.com/sysl-lang/syslui-sdl  0.1.0
    syslui-demo asks for 0.1.0
```

**That project's manifest names only one dependency.** Because
[imports are transitive](/reference/packages/#imports-are-transitive), the other three packages are
pulled in indirectly, and nothing in the project's own files mentions them; `deps` shows them.

**A coordinate marked `(raised)` is one for which some package asked for a lower version.**

```
github.com/e/buf  1.4.0  (raised)
    github.com/e/a asks for 1.2.0
    github.com/e/b asks for 1.4.0
```

This answers *why did I get this version*, which a build cannot tell you:
[selection](/reference/packages/#which-version-you-get) keeps only the highest minimum, so the
lower request is no longer visible afterwards. The note a build prints covers only the case where
*your own* manifest's version was raised; `deps` also covers a dependency raised by one of its
siblings, where the version you are running is one that none of your files mentions.

**A coordinate marked `(dev)` is reached only by this project's tests.**

```
github.com/sysl-lang/quickjs  0.1.0  (dev)
```

It is declared in [`dev_dependencies`](/reference/packages/#dependencies-a-test-alone-needs), so
`sysl test` resolves it, and no project that depends on this one ever fetches it. The listing
includes it because a project's dependencies are a property of its manifests rather than of one
build, and the mark tells you that a consumer does not pay for it.

It is a mark rather than a separate section because a package reached *through* a dev dependency is
in the same position without being named in either block, and a separate section would have no
place for it.

**A path dependency shows its directory** where a coordinate shows a version, since it has no version
and the directory identifies it.

`deps` resolves exactly as a build does, so it fetches what this machine does not have and records
it in `sysl.sum`. It needs nothing else — no target, no standard module, and no source is read — so
you can inspect a project that cannot be built on this machine.

### `add`

Adds a dependency to `package.hocon`, at the newest version the repository has tagged or at the
version you name.

```
sysl add github.com/sysl-lang/sdl3
sysl add github.com/sysl-lang/sdl3@0.3.1
```

```
added sdl3 github.com/sysl-lang/sdl3 0.3.1
```

The version comes from `git ls-remote --tags`, not from a hosting service's API. A coordinate is a
git identity, and the build clones it with plain git, so this works with a self-hosted server or a
mirror as well as with GitHub. A tag that is not a version, such as `latest` or `nightly`, is
skipped rather than refused, since a manifest cannot pin one.

**`add` edits your manifest in place and changes nothing else in it.** Re-printing the parsed file
would discard every blank line, every comment explaining why a dependency is there, and your
layout, so `add` inserts only the new text. A new entry takes the indentation of its siblings and
aligns with their column where they have one.

Nothing is fetched: `add` records the dependency, and the next build fetches it. The result is read
back through the manifest reader before it is written, so if the edit would produce an unreadable
file, the file is left unchanged.

### `vendor`

Copies every package the project depends on into a `vendor/` directory beside the manifest.

```text
sysl vendor .
```

```text
fetching github.com/sysl-lang/parsing v0.12.0
vendored 1 package into vendor/
```

**`vendor/` is the machine's package cache placed inside the project, not a separate mechanism**:
the same layout, the same resolution, the same `sysl.sum`. A project with a `vendor/` directory
builds without network access, and someone who clones it needs no extra steps: the directory's
presence is all that enables it.

The graph is resolved against the machine's cache first, so anything missing is fetched (the
`fetching` line, on standard error) and any package `sysl.sum` has no line for gets one, exactly as
in a build. Each package is then **copied** out of the cache, and the copy's hash is checked against
its `sysl.sum` line before it is put in place, so a cached package edited after it was fetched is
refused rather than copied into your project. A repository's `.git` is not copied. Running the
command again copies only what is missing.

`vendor` copies the graph `sysl test` resolves — every feature the project declares, unless the
[feature flags](#the-feature-flags) say otherwise, and its `dev_dependencies` as well as its
`dependencies` — so a vendored project's own tests fetch nothing either. Inside a
[workspace](/reference/packages/#workspaces) there is one `vendor/`, at the workspace root, holding
every member's packages.

`vendor/` is not part of the project's own source, just as
[`examples/`](/reference/packages/#a-package-may-carry-examples) is not, so nothing in it is compiled
as one of your modules. Commit it if you want builds that keep working even if an upstream
repository disappears; leave it out of the repository if you prefer to fetch.

**A path dependency is not vendored.** It is a directory you are editing alongside this project,
which is why `sysl.sum` keeps no hash for it, and a frozen copy would defeat its purpose. The
command reports how many path dependencies it skipped.

### `tidy`

Removes from `sysl.sum` every line for a package version the project no longer resolves to. A build
only adds lines, so a version the project has moved away from stays recorded until `tidy` runs.

```text
sysl tidy
```

```text
removed github.com/sysl-lang/skitter v0.1.0
removed github.com/sysl-lang/box2d v0.1.0
```

With no path, it uses the working directory. It resolves the complete graph — every feature the
manifest declares, and the `dev_dependencies` too — because a pruned graph would drop the lines for
features not currently selected. A line that stays is left exactly as it was, a file that is already
tidy is left untouched with nothing printed, and a project whose dependencies are all paths ends up
with no `sysl.sum` at all.

`--check` writes nothing and fails if the file is not tidy, naming what it would change; this is
the form to use in CI. Outside a project, with no `package.hocon`, the command is refused.
[Packages](/reference/packages/#sysl-sum) has the rule.

### `doc`

Generates an API reference for a tree from its declarations and their
[doc comments](/reference/lexical/#documentation-comments), one Markdown page per module.

```
sysl doc library --out docs/api
```

```
sysl doc: 27 modules -> docs/api
```

**`doc` writes Markdown rather than a website.** A `docs/` folder of generated Markdown is readable
directly in a repository host such as GitHub, with no tooling, hosting or installation, and it can
be reviewed as a diff in a commit, which generated HTML cannot.

**The unit is a module**, not a type, because a module is what you import.

**A page lists every public declaration of its module**, each group under its own heading:
constants, values (`val`), variables (`var` and `static var`), functions, externs, types, aliases,
traits and implementations. A declaration's signature is shown as it was written, and its doc
comment beneath it; a function whose result is [read off its body](/reference/declarations/#the-result-type)
is shown at that result, `-> T` and all, and its body is never shown. A variable is listed because storage a caller may write is as much a part of a
module's API as storage it may only read.

| Flag | Means |
|---|---|
| `-o`, `--out <dir>` | where the Markdown goes (default `docs/api`) |
| `-t`, `--title <text>` | the index page's title |
| `-n`, `--note <text>` | a note under the index page's title |
| `-V`, `--docversion <v>` | the version being documented, shown on the pages |
| `--private` | include file- and module-private declarations |
| `--site <dir>` | after writing, build the juicer site rooted at `<dir>` |
| `--check` | write nothing; exit non-zero if what is committed is stale |

**`--check` keeps committed documentation current.** Generated files kept in a repository go
stale, so a CI job regenerates them and compares:

```
sysl doc library --out docs/api --check
```

```
sysl doc: docs/api is stale — 2 file(s) differ:
  sysl-text.md
  sysl-slices.md

Regenerate with 'sysl doc' and commit the result.
```

It exits `1` in that case and `0` when everything matches, so a CI build fails when a doc comment
was edited without regenerating the pages.

**`doc` reads only the syntax.** Nothing is analysed, so a package whose dependencies are missing,
or which does not currently compile, can still be documented.

**A literate source is tangled first**, so a `.lsysl` file's narrative never reaches the generator;
that prose documents the program as a whole and is rendered by [`weave`](#weave). A doc comment
written *inside* its code blocks is read like any other. A literate module whose author explained
everything in the narrative therefore produces bare signatures here, by design.

#### Building an HTML site

`sysl doc` is built into the compiler, and it writes only Markdown. `--site` passes the pages to the
**`juicer`** command, found on your `PATH`, to build the HTML. A site generator needs a templating
engine, an asset pipeline and a web server, none of which belong in a compiler. If `juicer` is not
installed, `sysl doc` says so and where to get it.

### `targets`

Lists the target registry, one line per machine: the name to write after `--target`, the LLVM
triple it stands for, and, for a target sysl knows but cannot build for, the reason:

```
aarch64-macos                arm64-apple-macosx
x86_64-macos                 x86_64-apple-macosx
aarch64-linux                aarch64-unknown-linux-gnu
x86_64-linux                 x86_64-unknown-linux-gnu
riscv64-linux                riscv64-unknown-linux-gnu
x86_64-windows               x86_64-pc-windows-msvc
aarch64-android              aarch64-linux-android24
aarch64-freestanding         aarch64-none-elf
x86_64-freestanding          x86_64-unknown-none-elf
riscv64-freestanding         riscv64-unknown-elf
thumb-freestanding           thumbv8m.main-none-eabihf
thumb-freestanding-softfp    thumbv8m.main-none-eabi
thumb-freestanding-soft      thumbv8m.main-none-eabi
thumbv6m-freestanding        thumbv6m-none-eabi
thumbv7m-freestanding        thumbv7m-none-eabi
thumbv7em-freestanding       thumbv7em-none-eabihf
thumbv7em-freestanding-soft  thumbv7em-none-eabi
thumbv7em-freestanding-dp    thumbv7em-none-eabihf
riscv32-freestanding         riscv32-unknown-elf
wasm32-freestanding          wasm32-unknown-unknown
wasm32-wasi                  wasm32-wasip1
craft-freestanding           craft
x86-linux                    i386-unknown-linux-gnu  (no C calling convention has been measured for x86)
```

The line for the machine you are on is marked `(this machine)`, and a final line reports which
processor and operating system sysl detects — `this machine reports: aarch64 / macos` on an Apple
silicon Mac. On a machine with no entry in the registry, that line is the only place to see what sysl
detected.

**`aarch64-android` is the only row whose triple carries a version number, and the only one that
needs an environment variable.** The `24` is an Android API level, which determines which of
Bionic's declarations exist. It is in the triple because clang requires it there: without a level,
`__ANDROID_API__` is not defined, and the first system header that guards a declaration on it fails
to compile. Its C calling convention is the same as `aarch64-linux` (both are AAPCS64); everything
above the ABI differs, which is why Android is a separate system and `#if android` is a symbol
distinct from `#if linux`.

The variable says *which clang* to use. Every other target here works with any clang that has the
right back end, but a back end is not a toolchain: Android's headers and libraries come from the
NDK, and no clang outside it carries them. sysl therefore reads the environment.
`ANDROID_NDK_ROOT` or `ANDROID_NDK_HOME` names an NDK directly; otherwise `ANDROID_HOME` names the
SDK, and the newest `ndk/<version>` under it is used.

```
export ANDROID_HOME=~/Library/Android/sdk
sysl build --target aarch64-android hello.sysl
```

`ANDROID_SDK_ROOT` is also read and means the same thing, but Android's documentation marks it
deprecated in favour of `ANDROID_HOME`, so set `ANDROID_HOME`. If you have both, keep them pointing
at the same directory, which Android Studio and the Gradle plugin check.

sysl does not search for an NDK. An NDK can be installed anywhere, so a search through your home
directory could find the wrong one, or none, and building against the wrong platform headers fails
in ways that are hard to see. With nothing set, the build stops and says what to set:

```
sysl: error: building for Android needs the NDK's own clang, and nothing here says where it is — no clang outside the NDK carries Bionic's headers, so one picked for having the back end fails at the first '#include'. Set ANDROID_HOME to the Android SDK (the directory holding 'ndk/'), or ANDROID_NDK_ROOT to one NDK directly
```

Without this check, the host's clang would be chosen because it has an `aarch64` back end, and the
build would fail on a missing `dirent.h` inside the standard library's C, which looks like a broken
library rather than the wrong compiler.

Naming the NDK is all that is required. Its clang finds its sysroot from its own location, so there
is no `--sysroot` to pass and no include or library path to configure. The result is an ordinary
position-independent Android executable, which is what an APK loads.

**Eight of the freestanding rows are 32-bit microcontrollers.** The RP2350 (Raspberry Pi Pico 2)
boots either a pair of Cortex-M33 cores or a pair of RV32IMAC cores; the RP2040 (the original Pico)
has two Cortex-M0+ cores; the Armv7E-M rows cover ST's parts; and Armv7-M is the Cortex-M3.
Microcontrollers are the main use of *freestanding* targets; the three 64-bit freestanding rows serve
kernels and hypervisors. The Arm rows are named `thumb` rather than `arm` because a Cortex-M executes
only Thumb code, so a target named `arm` and built for A32 would produce code the core cannot run.

**The Cortex-M33 has three rows, because both the float ABI and the presence of an FPU depend on
your project and board.** `thumb-freestanding` passes floating-point arguments in VFP registers,
which is what `eabihf` selects; `thumb-freestanding-softfp` passes them in core registers, which is
what `-mfloat-abi=softfp` means and what pico-sdk builds by default. The two cannot be mixed — GNU ld
refuses the link, saying one object *"uses VFP register arguments"* and the other *"does not"* — so
a sysl archive linked into a C build must match that build. Pick the one your project already uses;
if you are not sure, `softfp` is the pico-sdk default.

**Both of those rows use the M33's own FPU, which is single precision.** `f32` arithmetic compiles to
instructions, and `f64` arithmetic compiles to calls — `__aeabi_dmul` and its family, which the
program carries in `sysl.softfloat.binary64` — because an `fpv5-sp-d16` has no double-precision
instructions. This is a property of the hardware, not a setting, and worth knowing before putting
`f64` in an inner loop.

**The third row, `thumb-freestanding-soft`, is for a board with no FPU.** `softfp` is not `soft`:
`-mfloat-abi=soft` means no FPU instructions at all, while `softfp` uses the core's `fpv5-sp-d16` and
changes only the calling convention. A triple cannot express that distinction — both rows are
`thumbv8m.main-none-eabi` — so sysl states it on every clang command line, together with the
convention: `-mfloat-abi=soft -mfpu=none` for the `soft` row, `-mfloat-abi=softfp -mfpu=fpv5-sp-d16`
for `softfp`, and `-mfloat-abi=hard -mfpu=fpv5-sp-d16` for the first. None of this is left to the
compiler's default, because the default varies: the same triple reports a floating-point unit under
some clang builds and none under others, so a row that relied on the default would describe a
different machine depending on which clang was installed. **Use the `soft` row when your board's
headers say the FPU is off.** That is where the difference becomes visible: CMSIS refuses the build
with *"Compiler generates FPU instructions for a device without an FPU (check `__FPU_PRESENT`)"*, and
every Zephyr MPS2 board is configured that way. The opposite mistake is harder to find: the image
links and boots, then faults at the first arithmetic that reaches a VFP instruction.

`thumbv7em-freestanding` and `thumbv7em-freestanding-soft` are the same pair for Armv7E-M: an STM32
F4 with the FPU enabled, and one without.

**`thumbv7em-freestanding-dp` targets the Cortex-M7's double-precision FPU.** The
`thumbv7em-freestanding` row names an `fpv4-sp-d16`, which does single precision in hardware and
calls a library routine (`__aeabi_dmul` and the rest) for every `f64` operation. A Cortex-M7 with a
double-precision FPU (an STM32 F7 or H7) has an `fpv5-d16`, so this row — the same triple,
`-mfloat-abi=hard`, and `-mfpu=fpv5-d16` — lets an `f64` multiply compile to `vmul.f64`. Use it only
on a part that has the double-precision unit: on a single-precision M7 the first `f64` instruction
takes a usage fault.

**`thumbv6m-freestanding` and `thumbv7m-freestanding` are different architectures, not further
conventions.** The first is the RP2040's Cortex-M0+ — Armv6-M, an earlier architecture rather than a
subset of Armv8-M — and the second is the Cortex-M3. Build programs for the original Pico with
`thumbv6m`; a `thumb-` row produces instructions the core cannot execute, and the failure is a fault
at run time rather than an error at link time. Neither architecture includes a floating-point unit,
so neither needs a `-soft` variant.

Build a Cortex-M3 program for `thumbv7m` rather than `thumbv6m`, even though Armv6-M code runs on an
M3. The difference is in the *headers*: a real project reads its own configuration, takes
`CONFIG_CPU_CORTEX_M3` to mean Armv7-M and uses `BASEPRI`, while CMSIS, seeing an Armv6-M triple,
provides an intrinsic set without it.

**On Armv6-M, one feature needs support from the board.** Armv6-M has no atomic instructions, so a
program using `&sync` — the shared counted reference — compiles to calls the toolchain does not
supply, and the link fails naming `__atomic_fetch_add_4`. Other programs are unaffected, because
those calls are emitted only for a program that uses `&sync`. The
[`pico`](https://github.com/sysl-lang/pico) package implements them with one of the chip's hardware
spinlocks, which a dual-core part requires: masking interrupts affects only one core, and the other
core could still lose the update.

**`wasm32-freestanding` is 32-bit and freestanding, but not a board.** It is WebAssembly, run by a
browser, `wasmtime`, or any other host that embeds a module. As `unknown-unknown` in the triple
indicates, it has no libc, no loader and nothing that runs before an exported function is called.
sysl links it with `-nostdlib` and names `main` as the module's entry, which keeps `main` reachable
and exports it under that name. A program that does not print produces a few hundred bytes of
`.wasm` that `wasmtime` can run; a program that prints fails at the link naming `putchar`, as on any
other bare target.

It needs a clang with the WebAssembly back end. Apple's clang does not include it, so on a Mac sysl
uses Homebrew's LLVM automatically, as it does for the RISC-V rows, and says so if it cannot find
one.

**`wasm32-wasi` is the same machine with a libc, and it is the one to use unless you specifically
need the bare target.** WASI is a standard set of *imports* — file descriptors, clocks, randomness,
arguments, exit — that a module requests from its host by name instead of making system calls, and
**wasi-libc** is a full libc built on them. A program that prints therefore links and runs,
`sysl.fs` and `sysl.env` work, and nothing needs to be written by hand before the first build:

```
export WASI_SDK_PATH=~/wasi-sdk-34.0-arm64-macos
sysl build --target wasm32-wasi hello.sysl -o hello.wasm
wasmtime hello.wasm
```

It needs **wasi-sdk**: clang, wasi-libc and a sysroot in one download from
[the project's releases](https://github.com/WebAssembly/wasi-sdk/releases). This is the same
arrangement as Android's NDK, for the same reason: a clang with the `wasm32` back end is not a
complete toolchain, and one chosen for the back end alone fails at the first `#include`. sysl does
not search for it; with `WASI_SDK_PATH` unset, the build stops and says what to set.

The WASI version is **preview1**, which every runtime supports and which the browser shims
(`@bjorn3/browser_wasi_shim`, Node's `node:wasi`) implement. preview2 — the Component Model, with WIT
interfaces and resource handles — produces something that is not a core wasm module and that browsers
do not run natively; the standard preview1-to-preview2 adapter converts a module into a component,
so a preview1 module can still be used there.

**`craft-freestanding` is 16-bit, and it is the one row for which sysl does not drive the whole
build.** CRAFT is a teaching ISA with eight registers, a 64 KiB virtual address space and a
software-managed TLB. Its LLVM back end is maintained out of tree, so it provides an `llc` rather
than a compiler driver. The machine has no libc, no object format and **no linker**: its assembler
reads one file and resolves every label inside it. sysl therefore emits the LLVM IR and stops, and
two more commands finish the build:

```
sysl emit-llvm hello.sysl --target craft-freestanding > hello.ll
llc -march=craft hello.ll -o hello.s
craft as hello.s
```

Every other subcommand refuses this target and says why. sysl can *lower* for it — `emit-llvm`
produces an ordinary module — but there is no compiler driver for it to call.

**The 16-bit width is visible in your code.** `usize` is pointer-width by definition, so a slice's
length is a `u16` and the address space bounds everything. An `int` is still 32 bits and a `long`
still 64, because the language fixes those widths rather than the machine, so ordinary arithmetic
here is multi-word and the back end expands it, as every 32-bit target expands a `long`. Indexing
with an `int` needs no conversion: an index wider than an address is checked against the address
range and then narrowed, so `for i in 0..<4 do b[i] …` means what it means everywhere else.

`x86-linux` is listed *because* it cannot be built for, so that naming it produces an explanation
rather than "unknown target". The limitation is in the compiler, not the machine, and it is not the
32-bit width: what is missing is a C calling convention verified against clang, which is required
before sysl supports a target.

**Freestanding does not mean self-contained.** A program built for a bare board still refers to C
symbols that its runtime must define: `putchar` wherever anything prints, `free` wherever a reference
count can reach zero, and `memcpy` and `memset` for structure assignments. These are not sysl-specific
dependencies; any C compiler emits the same calls for the same code, and a real project already has
them because its SDK links a libc.

**What the program does carry is the arithmetic the machine has no instruction for.** A `long` is 64
bits everywhere, and no Cortex-M or 32-bit RISC-V core has a 64-bit divide instruction, so dividing
one — and formatting one, which divides by ten, and therefore any reachable `panic` — is a call to
compiler-rt's `__aeabi_ldivmod` or `__divdi3`. An `f64` on a machine with no double-precision unit
is a call per operation, `__aeabi_dmul` or `__muldf3`. The standard module defines these routines
(`sysl.softint`, `sysl.softfloat.binary64`) as weak symbols, and a build for a 32-bit machine keeps
them exactly where reachable code divides a 64-bit integer or computes with an `f64`. An image
therefore links with `-nostdlib`, and if a `libgcc` also defines them, its definitions take
precedence. An `f32` on a core with no FPU at all — a Cortex-M0+ or M3, or RISC-V without `F` —
works the same way: `__aeabi_fmul` or `__mulsf3`, kept where reachable code computes with one.
**Armv6-M lacks more**: 32-bit division, 64-bit multiply, variable shifts and leading-zero count are
all calls there, which LLVM generates from code that does not mention them, so `sysl.softint` is kept
in every Armv6-M build. **Every Thumb core** calls the run-time ABI's `__aeabi_memcpy4` and its
relatives for a large struct copy, so `sysl.softint.aeabi` provides them in every Thumb build, built
on C's `memcpy` and `memset`, which the board supplies as on any freestanding target.

### `lsp`

`sysl lsp` is a [Language Server Protocol](https://microsoft.github.io/language-server-protocol/)
server on standard input and output, for an editor to start and communicate with. It takes
`--stdio`, which is what an editor's client library passes, and no other option. Standard output
carries the protocol; anything the server reports about itself goes to standard error.

Every answer comes from the same analysis `sysl build` performs, rerun in full on each change to an
open file, using the text the editor holds, saved or not:

| request | answer |
|---|---|
| `textDocument/didOpen`, `didChange` (full text) | `textDocument/publishDiagnostics`: the file's syntax error, or every diagnostic the analysis reports, ranged in UTF-16 columns as the protocol counts them — for the file itself and for every other file the analysis found a mistake in, open or not; a file whose mistakes are gone gets an empty list |
| `textDocument/hover` | the type of a local or a module `val`/`var`, or a function's signature |
| `textDocument/definition` | where that name is declared — in the file, another file of the package, or the standard library |

A file is analysed with the package whose `package.hocon` is nearest above it, or alone if there is
none. The standard library is parsed once and kept for the life of the server, except while the
editor holds unsaved text for one of the library's own files. A message that is not JSON is answered
with the JSON-RPC parse error (`-32700`); a request the server does not answer, with `-32601`. The
server ends at `exit`, with status 0 after `shutdown` and 1 without; input that ends or cannot be
framed ends it with status 1.

**Visual Studio Code**: the [sysl extension](https://github.com/sysl-lang/vscode-extension) starts
`sysl lsp --stdio` and colours sysl with the grammar the documentation uses. Install the `.vsix`
from its [releases](https://github.com/sysl-lang/vscode-extension/releases) (**Extensions: Install
from VSIX…**). Its settings are `sysl.server.path` (the compiler, `sysl` on the `PATH` by default)
and `sysl.lib` (passed as `SYSL_LIB`; an installed sysl needs none). The grammar itself ships in
the release at `share/sysl/editors/sysl.tmLanguage.json`, for any editor that reads TextMate
grammars.

## Flags every subcommand takes

| | |
|---|---|
| `--target <name>` | the machine to build for; defaults to this one |
| `--lib <dir>` | a library's source directory to compile against; may be given more than once |
| `--no-std-lib` | compile the standard module from source rather than linking the cached one |
| `--link-path <dir>` | where to look for a library a `link` directive named; may be given more than once |
| `--include-path <dir>` | where to look for a header the C beside a module includes; may be given more than once |
| `--include-path <name>=<dir>` | the same, and it answers the header requirement a package declared under that name |
| `-D NAME` or `-D NAME=value` | a macro the C beside a module is compiled with; may be given more than once |
| `-O <level>` | the optimization level handed to clang |
| `--lto <mode>` | optimize across every object at the link: `thin` or `full` |
| `--link <how>` | link `pkg_config` libraries from their static archives: `static`, `dynamic`, or a comma-separated list of their names |
| `--profile-generate <dir>` | build an instrumented program that writes its counters into this directory |
| `--profile-use <file>` | build against a merged profile |
| `--no-bounds-locations` | leave the `file:line:column` out of a failed bounds check's report — the [`bounds_locations` key](/reference/packages/#leaving-the-bounds-locations-out) for one build |
| `--no-error-traces` | record no [error return trace](/reference/errors/#where-a-failure-came-from) — the [`error_traces` key](/reference/packages/#leaving-the-error-return-trace-out) for one build |
| `--general-regs-only` | keep the code out of the floating-point and SIMD registers — the [`codegen` block](/reference/packages/#what-the-generated-code-may-use-of-the-machine) for one build |
| `--strict-align` | make every access aligned — the [`codegen` block](/reference/packages/#what-the-generated-code-may-use-of-the-machine) for one build |
| `-p <member>`, `--package <member>` | in a [workspace](/reference/packages/#workspaces), the member to build, test or run |
| `-v`, `--verbose` | report what the build decided: the files read, the standard module and how it was reached, the search paths, the allocator, and the clang and linker command lines |
| `--explain-escapes` | report every local array promoted to the heap |

**An unknown option is refused, never ignored.** Each mistake is reported on a line of its own, with
the command's usage below, on standard error, and the exit status is 2. A misspelt `--relase` is
therefore never silently built as a debug binary, nor mistaken for the path and compiled:

```text
Error: Unknown option --relase
usage: sysl build [--target <name>] [--cc <path>] [-O<level>] [-o <exe>] …
```

An option that needs a value and was given none is reported the same way, as
`Error: Missing value after --target`. An option that one command takes and another does not, such
as `-o` on `test`, is unknown to the second. A lone `-` is a path, and everything after `--` in
`run` belongs to the program, options included.

The standard-module flags and `-O` are covered in
[installation](/getting-started/installation/), including why the default is `-O1` rather than
`-O0`. `--lto` is the flag form of the
[`lto` manifest key](/reference/packages/#link-time-optimization), `--link` of the
[`link` key](/reference/packages/#linking-a-library-statically), and the two profile flags are the
first and last steps of the workflow below.

### Profile-guided builds

**A profile-guided build has three steps, two of which are sysl builds.** The optimizer is told what
the program actually did — which branches are taken, which calls are hot, which functions are never
reached — and lays out the code accordingly, rather than relying on static analysis alone.

```
sysl build . --lto thin --profile-generate prof -o myprog-instr
./myprog-instr <each job in the training set>
"$(clang -print-prog-name=llvm-profdata)" merge -output my.profdata prof/*.profraw
sysl build . --lto thin --profile-use my.profdata -o myprog
```

1. **Instrument.** `--profile-generate` applies to every clang invocation in the build — the
   module, each C file a package carries, and the link, which supplies the counting runtime. The
   instrumented program is larger and slower than a normal build.
2. **Train.** Run it on work that resembles its real workload. Each run writes into the named
   directory, under a name derived from the binary, and **the runtime merges into that one file
   rather than overwriting it**, so any number of training runs leave a single `.profraw` holding
   all of them.
3. **Merge, then build against the result.** `--profile-use` takes the *indexed* profile that
   `llvm-profdata merge` writes, not the raw counters the program wrote.

Giving both flags at once is refused, because clang would accept both and silently instrument the
build you asked it to optimize. Neither is a manifest key: a profile describes one measurement on
one machine, and a path to it in a manifest that consumers read would be wrong for everyone but its
author.

#### Use the `llvm-profdata` the compiler names, not the one on the PATH

A `.profraw` file carries a format version, and `llvm-profdata` reads only the version its own LLVM
writes, so the tool must match the **compiler**, not whatever is installed on the machine. On a Mac
the two often differ, and the failure looks like a corrupt profile rather than the wrong tool:

```
warning: default_15853205779378358405_0.profraw: raw profile version mismatch: Profile uses raw
profile format version = 10; expected version = 11
error: no profile can be merged
```

`clang -print-prog-name=llvm-profdata` always gives the right answer, because it asks the driver
where its own tools are. Apple's clang reports the one inside Xcode's toolchain, which is not on the
`PATH`, while the `llvm-profdata` that Homebrew's LLVM puts *on* the `PATH` belongs to a different
LLVM.

#### Ordinary builds carry no instrumentation

A build that did not request instrumentation contains none: `nm` finds no `__llvm_profile` symbol in
a plain build, nor in a binary built with `--profile-use`, because a profile is read at compile time
and leaves nothing in the output.

#### Measured effect

Measured on the slate interpreter, trained on its own benchmark suite and measured on the same suite
by alternating best-of-9 runs, **in addition to** `-O2` with thin LTO:

| | geomean | binary |
|---|---|---|
| `-O2`, `--lto thin` | — | 6,013,000 bytes |
| the same, instrumented | (not for use) | 10,162,936 bytes |
| the same, `--profile-use` | **−12.5 %** | 5,880,888 bytes |

Compared with the plain `-O2` build, LTO and the profile together give about **−31 %**, and the
profile-guided binary is *smaller* than the one built without a profile, because cold code is moved
away from the hot path. All 64 programs checked produced byte-identical output.

**Training on the same work you then measure overstates the gain**, and the table above does exactly
that. A profile helps most on jobs that resemble the training set; for a workload unlike anything in
training, expect less.

### The feature flags

`run`, `build`, `build-c`, `build-lib`, `test`, `deps` and `vendor` take three more flags, which
select the root project's [features](/reference/packages/#features), and therefore which optional
dependencies are fetched:

| | |
|---|---|
| `--features <a,b>` | a feature of the root project to turn on, beside `default`; comma-separated, and may be given more than once |
| `--no-default-features` | leave the root's `default` feature off |
| `--all-features` | turn on every feature the root's manifest declares |

**`--all-features` cannot be combined with either of the others.** It requests every feature the
manifest declares, while each of the others requests something narrower about the same set, so no
precedence rule could honour both.

```
sysl: error: --all-features says every feature the manifest declares is wanted, and
--features/--no-default-features each say something narrower about the same set — a compilation
cannot ask for both
```

With none of the three, `sysl test` enables **every** feature the manifest declares, so that no
feature-gated code goes untested. Giving any of them turns that off, so a suite can be run in exactly
the configuration a consumer would get.

### `--include-path`, `-D` and `--link-path`

A module that binds a C library carries C of its own, and that C has to be found, compiled and
linked. The three flags address the three ways this fails, in the order the failures occur:

- **`--include-path`** — the shim `#include`s the library's header and cannot find it. This is the
  first failure, and often unexpected, because it happens before anything reaches the linker.
- **`-D`** — the header is found but refuses to compile, because a sizeable C project configures
  its headers with macros that have not been set. pico-sdk's `pico/cyw43_arch.h`, for example,
  stops with `#error` rather than guess which architecture variant is meant.
- **`--link-path`** — everything compiles, but the library archive is not where the linker looks.

sysl supplies no defaults for any of these. It does not add `/opt/homebrew/lib`, and it does not
define macros: a compiler that assumed where a platform keeps its libraries, or how a project
configures its headers, would be wrong on some machine, and the resulting build failure would occur
where the project's author cannot reproduce it. These settings belong to the project.

A build system that already knows these values can pass them through: from CMake, read the target's
`INCLUDE_DIRECTORIES` and `COMPILE_DEFINITIONS` and pass each one along.

A **package** whose C includes headers it does not carry can declare them, which turns the first
failure above into a clear message. Written as `--include-path <name>=<dir>`, the flag satisfies a
requirement the package declared, and a build missing one is refused by name before clang runs. See
[packages](/reference/packages/#headers-a-package-needs-and-does-not-carry).

### `--lib` takes a source tree

A library is passed as the directory its source is in — a
[source root](/reference/modules/#source-roots) — and the compiler keeps what it builds from it in
its own cache, which is never named directly. A path to a `.syslib` file is refused:

```text
sysl: error: --lib takes the directory a library's source is in, and 'mylib.syslib' is a '.syslib' — a library is handed over as the tree it is written in, and the compiler keeps its own cache of what it compiled from one, which nobody names
```

**A source tree that is a dependency's package overrides that dependency.** To build against a
working copy of a package the manifest names, pass one flag and leave the manifest unchanged:

```
sysl build . --lib ../geom
```

If `../geom/package.hocon` names the package `geom` and the project depends on
`github.com/e/geom`, the checkout is compiled in its place — for that dependency and for any other
package in the graph that depends on it — and the coordinate is never fetched, so the working copy
can contain changes that are not yet published. A tree that merely *contains* a module a dependency
also provides, without being that package, is refused as a collision.
[Packages § A source root stands in for the package it is](/reference/packages/#a-source-root-stands-in-for-the-package-it-is)
has the rule.

### `--target`, and cross targets under `run`

With no `--target`, a build targets the machine it runs on. If sysl has no registry entry for that
machine, it says so and stops rather than guessing, because a wrong guess produces a module that
looks correct and is not.

`run` executes what it builds, which only the current machine can do, so a cross target is refused
before the build starts:

```
sysl: error: 'run' executes what it builds, and 'x86_64-linux' is not this machine — use 'sysl build --target x86_64-linux'
```

An unknown target name is answered with the list of known ones:

```
sysl: error: unknown target 'arm-linux' — sysl knows aarch64-macos, x86_64-macos, aarch64-linux, x86_64-linux, riscv64-linux, x86_64-windows, aarch64-android, aarch64-freestanding, x86_64-freestanding, riscv64-freestanding, thumb-freestanding, thumb-freestanding-softfp, thumb-freestanding-soft, thumbv6m-freestanding, thumbv7m-freestanding, thumbv7em-freestanding, thumbv7em-freestanding-soft, thumbv7em-freestanding-dp, riscv32-freestanding, wasm32-freestanding, wasm32-wasi, craft-freestanding, x86-linux
```

### `-v`, `--verbose`

Reports what the build decided, on stderr, each line prefixed `sysl:`: the files it read, which
standard module it used and how it obtained it, its search paths and allocator, and then every
command line it passed to clang:

```
sysl: 1 source file(s) under hello.sysl
sysl:   read hello.sysl
sysl: standard module linked from ~/Library/Caches/sysl/<version>+…/std.syslib
sysl: allocator: malloc / free (the C default)
sysl: link: clang --target=arm64-apple-macosx -Wno-override-module -O1 -Wl,-dead_strip hello.ll ~/Library/Caches/sysl/<version>+…/std.syslib -o hello
wrote hello
```

Under `--no-std-lib` the third line is `standard module compiled from source, not linked from an
artifact`, and a `compile:` line follows for each of the library's C files. Each `--lib`,
`--link-path`, `--include-path` and `-D` is reported too (`library:`, `link path:`,
`include path:`, `define:`), before the allocator line, which names the package or project that
chose the allocator where one did. A test suite links like any other build and reports the same
lines. `--verbose` reports what the build did, not how long it took; for phase timings, use
[`SYSL_TIMINGS=1`](#run).

### `--explain-escapes`

Reports on stderr one line for each local array the compiler moved to the heap, and the view that
required the move:

```
$ sysl build --explain-escapes tty.sysl
tty.sysl:31:12: 'buf' is promoted to the heap, because this view of it is returned
```

The position given is the **view**, not the declaration, because the view is the part that is hard
to find by reading. When nothing was promoted, the flag says so explicitly, so you know it took
effect. [Memory](/reference/memory/) explains what promotion is and when it happens.

### `-O2` is written the way clang writes it

A short option normally takes its value as the next argument, but C compilers write the
optimization flag joined to its level. sysl therefore rewrites `-O2` into `-O 2` before parsing
options — only for that letter, and only when something follows it — so a bare `-O` still takes the
next argument and `--optimize` is unaffected. The rewrite cannot reach the program's own arguments,
which have already been split off at the `--`.

## The standard module is never named

The compiled standard module lives in the compiler's cache, so no flag points a build at one.
Every command refuses `--std-lib` with the same message, before reading its own options:

```
sysl: error: --std-lib names a '.syslib', and a '.syslib' is this compiler's cache, which nobody names — to compile against another standard module, set SYSL_LIB to the root its source is in
```

## Exit statuses

| | |
|---|---|
| **0** | success — and for `test`, every test that ran passed |
| **1** | a compiler diagnostic, a driver error, or a failing test |
| **2** | the command line did not parse — an unknown option, a missing value, no subcommand |
| *the program's* | `run` only, once the program has started |

Every command prints a compiler diagnostic on standard error in the same form: the message, the
location, the source line, and the offending part underlined beneath it. The underline is as wide
as what the diagnostic is about — a name, a literal, or a whole expression where the expression is
at fault — and stops at the end of the line, since only that one line is quoted.

```
error: 'b' of 'add' is int, but string was given
 --> hello.sysl:7:14
  |
7 | print(add(x, "two"))
  |              ^^^^^
```

A driver error — a problem *around* the compilation rather than inside it — is prefixed
`sysl: error:` and written to standard error, which is why the messages quoted on this page carry
that prefix and those on the language pages do not. The one exception is a command line that did
not parse: it is reported as `Error: …` with the command's usage below, as shown above.

---

Next: the [tour](/tour/), which uses `run` throughout.
