---
title: The command line
summary: The subcommands, the flags they share, and what each one leaves as an exit status.
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

A subcommand is required; sysl with none says so on stderr, with its usage, and exits 2.

A word that is not one of these is refused on stderr, naming the commands there are, and exits 2.
Every command is sysl's own — nothing is looked for on the `PATH`:

```text
sysl: error: 'wave' is not a sysl command — the commands are run, build, build-lib, build-c, emit-header, weave, tangle, test, emit-llvm, emit-ast, emit-typed, prove, deps, add, vendor, tidy, doc, targets
```

**What a command was run for goes to stdout, and everything it says about the run goes to stderr** —
a diagnostic, a refusal, a mistake in the command line with the usage under it, `wrote <exe>`, and
what `--verbose` reports. So `sysl emit-llvm hello.sysl > hello.ll` holds a module or nothing at all.
`sysl --help` and `sysl --version` were asked for, so they answer on stdout with status 0.

### `run`

```bash
sysl run hello.sysl
```

Four things in a row: the source is parsed and checked, textual LLVM IR is emitted, `clang`
assembles and links it, and the binary runs.

**The second run of an unchanged program builds nothing.** The executable is kept, keyed over
everything that can reach its bytes — this compiler's version, the target, the allocator, the
optimization level, every source file the program is made of and the C beside it, every `--lib`
artifact, the standard module, and the search and link paths. Run it again with nothing changed and
it is the same binary; change a byte of any of those and it is a different key, so there is nothing
to go stale. **What is deliberately not in the key is the program's own arguments**, which is the
point: `sysl run p -- a` and `sysl run p -- b` are one binary run twice.

What that saves is most of what `run` costs. In a 9,600-line module with three git dependencies, a
program whose body is `print("hi")` took **9.2 seconds** every time — all of it compilation, none of
it the program, and invisible as such: four timings taken while benchmarking that program's real work
read 9.6 to 10.1 seconds and appeared to say the workloads were indistinguishable, when the ratio
between the fastest and slowest was about 45x.

**`test` gets the same treatment**, which is where it is felt most often — a suite that recompiles on
every run is the loop somebody is actually sitting in. `--filter` picks from the list of tests rather
than deciding what is compiled, so it is not part of the key.

**`SYSL_NO_CACHE`** set to anything non-empty compiles every time. It is for working *on the
compiler*, where the version in the key stands still while the bytes it produces do not — the one
case a cache cannot see, and the same one the standard module's cache has. No other subcommand
consults it: `build` writes a binary somebody named, and `build-c` and `build-lib` write artifacts
for somebody else's toolchain.

**Everything after a bare `--` belongs to the program**, not to sysl:

```bash
sysl run report.sysl -- --verbose report.txt
```

The split is made *before* sysl's own options are parsed, which is the point: `--verbose` really is
one of sysl's own options, so without the `--` sysl would have taken it — after it, it belongs to the
program, and neither side has to know what the other's flags are called. What arrives at
[`main(args: []string)`](/library/args/) is the executable's own path followed by those two words,
so `args.len` is 3 — `args[0]` is the program, exactly as C's `argv[0]` is.

**`run` exits with the status the program exited with.** It is running your program, so its status
is your program's — a compilation that failed is what exits 1 on sysl's own behalf.

**The program's input is sysl's input**, so a program that reads works under `run` exactly as the
built binary does:

```bash
printf 'one\ntwo\n' | sysl run count.sysl
```

What the program writes comes out as it writes it rather than all at once when it finishes, which is
what makes a program that prompts usable here at all.

### `build`

```bash
sysl build hello.sysl -o hello
```

The same compilation, stopping at the executable instead of running it. `wrote hello` goes to
stderr so that stdout stays whatever the build was for.

**`-o` is optional, and where it writes depends on what you named.** Given a **file**, the name is
that file's, with its extension dropped, in the current directory: `src/tools/fmt.sysl` becomes
`fmt` beside you.

Given a **directory**, the executable goes *inside* it, named after it. `sysl build .`,
`sysl build fmt` and `sysl build ../fmt` are three ways of naming one project, and all three write
`fmt/fmt` — so the answer does not depend on where you were standing when you asked.

**A project whose `package.hocon` names dependencies gets them fetched here**, if this machine has
not got them already — see [packages](/reference/packages/). `run` and `test` do the same; there is
no separate step to remember, and a project with no dependencies does none of it.

### `build-lib`

```bash
sysl build-lib mylib --target thumbv7em-freestanding
checked mylib
```

A library run through the whole analysis, against the standard module, for one machine — and nothing
written. It is the only check a board package gets on a host, which is what it is for. A library is
handed to a program as the directory it is written in, never as an artifact somebody names
([modules](/reference/modules/#separate-compilation) has why), so `build-lib` takes no `-o`:

```text
sysl: error: build-lib writes nothing anybody names — it checks a library, and what it compiles is this compiler's cache, found by the compiler alone — so it takes no -o
```

**A library may itself be built on another one**, and `--lib` naming the other's directory is what
says so, exactly as it does for a compilation.

**Unlike `build`, `run` and `test`, this does not fetch.** A package with a `dependencies` block is
refused rather than resolved over the network, and the message names the dependency and points at
`--lib`. A command whose whole job is to check one tree for one machine should not be the thing that
goes looking; the cost is that such a package writes its dependency down twice.

**`--cc` names the clang, where a search would not find the right one.**

```
sysl build hello.sysl --cc /opt/homebrew/opt/llvm/bin/clang
```

They are the answer to a machine with more than one toolchain on it: a vendor's clang trimmed to that
vendor's processors, an LLVM installed beside the system one, a cross toolchain that has to be
reached by path. A named compiler is **not searched past and not fallen back from** — somebody who
wrote down which compiler to use is owed an error rather than a different compiler — so naming one
that cannot run is refused rather than quietly replaced.

`--cc` reaches every place sysl runs clang: the link, a package's carried C, the `c const` probe, and
the standard module's own rebuild. That last one is the reason it is worth saying: the standard
module is rebuilt automatically when nothing usable is at the default path, and a flag that stopped
applying at that moment would change compiler halfway through a build and blame the library.

**It compiles the package's C, so it asks what that C needs.** A package that declares its
[header requirements](/reference/packages/#headers-a-package-needs-and-does-not-carry)
is refused here without `--include-path <name>=<dir>`, exactly as it is for a `build` — this being
the command a package is *published* by rather than merely built by. It is asked for the package's
own manifest and nothing else: the C of a `--lib` source root is not compiled here, so that root's
declaration is not charged to a library built against it.

### `build-c`

```bash
sysl build-c mylib -o libmylib.a
sysl build-c mylib -o libmylib.a --header include/mylib.h
```

`build-lib`'s shape with a different destination: a **static archive** an existing C project links,
and a **C header** declaring whatever the module marked `@export`. The compilation is the ordinary
one rather than a library build — what is wanted is a module lowered for this target with its calls
resolved — and what differs from `build` is that no entry point is emitted, since the C side supplies
its own `main`. The archive holds **native objects** by default: a manifest's `lto` key does not
apply to `build-c`, since LLVM bitcode links only under a linker with LLVM's plugin and optimizing
across the archive is the C project's own link's business. A C project that links with clang and
lld can ask for that on the command line: `--lto thin` (or `full`) makes the archive LLVM bitcode,
compiled as `sysl build` compiles its own objects under that mode, so the host's link optimizes
across the boundary — and `build-c` says on stderr that the archive must be linked with
`-fuse-ld=lld`.

The header goes beside the archive with `.h` appended unless `--header` names somewhere else. Both
paths are announced on stderr, along with the libraries the C project's own link line will still need
— what `@link` named, and the `pkg_config` modules the packages in the build require, given as the
`pkg-config --libs …` to ask for their flags. An unresolved sysl symbol over there reads as a missing
definition rather than as a missing archive, so it is worth being told before you meet it.
`--no-std-lib` folds the standard library into
the object and the archive then stands alone.

Like `build-lib`, this needs an `llvm-ar` as well as a `clang`. [FFI](/reference/ffi/) has `@export`
itself — what may be exported, what a symbol is named, and how module storage a computed initializer
fills is filled here, where there is no entry point to fill it in.

### `emit-header`

```bash
sysl emit-header mylib
```

The same header `build-c` writes, on stdout and with nothing built, for a project that generates its
headers as a build step.

### `test`

```bash
sysl test <path>
sysl test <path> --filter <text>
sysl test <path> --fail-fast
sysl test <path> --std
```

The tree is compiled once, into a binary that runs one named test per process, and the runner starts
it once per test. [Attributes](/reference/attributes/) has `@test` itself — what a test may be, what
every other build does with one, and why the process per test is the mechanism rather than a cost.

**It runs the tests of the tree you name, and no other.** A [dependency](/reference/packages/) is
compiled into the binary because the tree's code calls it, but its own `@test` functions are left
out: they are its author's to run, in its own repository, and a consumer's run reporting them would
blame the consumer for code it cannot change.

**It takes the search-path flags too** — `--link-path`, `--include-path` and `-D`, exactly as `build`
does, and it needs them for the same reasons. A tree whose C includes a header the toolchain does not
already know about, or whose constants come from a [`c const`](/reference/ffi/) block over one, is a
tree whose *tests* have to compile that C as much as its programs do. A package binding a system
library is the ordinary case rather than a corner of one, so a `test` that could not be given those
directories would be a `test` most packages could not run.

**It takes the [feature flags](#the-feature-flags) too**, and given none of them it enables every
feature the manifest declares rather than only `default`.

`--std` says the tree **is** the standard module, which is how sysl's own library is tested. The
compiler supplies `sysl` to every compilation, so without it the library arrives twice — once as the
tree being compiled and once as the copy handed over — and every declaration is already declared.
Nothing infers it: a program with a `sysl` directory of its own is nearly always a mistake, and a
build that guessed would turn that refusal into a collision at the link.

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
`@test(should_trap: "…")` whose run trapped without saying it. Everything the run printed follows
underneath, prefixed `>`.

**[The hooks a module may write](/reference/attributes/#the-hooks-a-module-may-write) change what a
`FAIL` line can be about, without adding a fourth verdict.** A `@setup` or
`@setup_all` fault never runs the test it was guarding, so there is no assertion inside the test to
report — the test's own `FAIL` line names the setup function instead, in place of the usual sentence.
A `@setup_all` fault guards the whole module, so every test in it fails that way and the run reports
none of them as having started. A `@teardown` or `@teardown_all` fault does not touch the test's own
verdict, since the test has already returned or trapped by the time its teardown runs — it is reported
as a failing entry of its own, named after the hook, alongside whatever the test it followed reported.

`--filter` keeps the tests whose name **or module** holds the text, and the header says how many of
how many are running. `--fail-fast` stops the loop rather than the report: what ran is still
reported and what never ran is simply absent, because "skipped" would be a third verdict for
something that is not a verdict.

**Exit status is 0 if and only if every test that ran passed.** A tree with no tests at all, and a
filter that matched none of the tests there are, both exit 0 — neither is a failure, and each says
which of the two happened rather than leaving one empty report to mean both.

**None of that mechanism exists on a microcontroller.** A process per test, a name in `argv`, a
verdict in an exit status: a board has none of the three, so `sysl test` cannot follow the code onto
one. [`sysl.harness`](/library/harness/) is the other half — a framework linked *into* the image,
which names its tests, locates a failure and prints a tally through a writer you hand it. Use this
command for everything that runs on the machine you are typing on, and that for the checks that only
exist on the target.

### `emit-llvm`

```bash
sysl emit-llvm hello.sysl
```

The IR to stdout, the same text `run` and `build` hand to clang but for one line: the printed
module's header is `target triple` alone, while the text clang compiles also carries the target's
`target datalayout`, asked of that clang. Without it LLVM would read every 64-bit access the text
leaves unannotated as four-byte aligned, which on a machine with no unaligned access (riscv64) splits
each one in two. Nothing is assembled and no toolchain is needed for it.

### `emit-ast`

```bash
sysl emit-ast hello.sysl
sysl emit-ast hello.sysl --no-spans
```

One file's **untyped** parse tree, as deterministic text — one node per line, indented for its
children, with every field the tree carries. It is parse only: no analysis runs and no standard
module is read, so it works on a file that would otherwise fail to compile, and it needs no target's
toolchain. `--no-spans` leaves out each node's source position, for a diff that does not move when a
line does. A parse error exits non-zero and prints the ordinary diagnostic instead.

### `emit-typed`

```bash
sysl emit-typed hello.sysl
sysl emit-typed hello.sysl --no-spans
sysl emit-typed hello.sysl --tables
```

One module's **typed** tree, in the same text `emit-ast` writes — `emit-ast`'s analysed counterpart.
It runs the whole front end: parsing, then analysis against the standard module exactly as
`emit-llvm` reads it, with every name resolved and every type solved. Nothing is lowered and nothing
is emitted — no pruning, no codegen, no clang — so it needs no target's toolchain, the same way
`prove` does not. Every expression's resolved type is printed as the compiler's own diagnostic text
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

`weave [-o <path>] <path>`. A **literate** source rendered as an HTML document. A `.lsysl` file is a
Markdown document whose four-column-indented part is the program, which is what makes one readable
with nothing rendering it — and an indented code block carries no *language*, so nothing can
highlight it. `weave` sets exactly the lines the compiler reads as program text as sysl code, one
block through any blank lines between them, and renders every other line as Markdown: prose, tables,
illustrations and heading levels are the document's own business.

The Markdown is [`sysl-lang/markdown`](https://github.com/sysl-lang/markdown)'s: CommonMark 0.31.2
with what GitHub renders — tables, strikethrough, bare links, task lists, footnotes, emoji
shortcodes, alerts (`> [!NOTE]`), mathematics as `$…$` and `$$…$$`, and mermaid diagrams — and every
heading given GitHub's id, so `# The greeting` is `<h1 id="the-greeting">`. The whole file is one
document, so a link reference defined anywhere in the prose reaches a link anywhere else, and a
program block between them closes whatever paragraph, list or quote was open. Raw HTML passes
through, under GitHub's tag filter.

What comes out is one file that opens by itself. It carries its own styling, in a light and a dark
palette; its code — every block whose language is sysl, so an illustration fenced as `sysl` too — is
coloured by keyword, type, function, string, number, comment and punctuation, the keywords being the
lexer's own reserved words; and its mathematics is set by KaTeX, which the page links. That last is
the one thing a woven document needs the network for — the prose and the code are markup in the
file, so a document read offline loses its equations to TeX source and nothing else.

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

The output goes to standard output, or to what `-o` names. **A path holding several literate sources
writes one document each**, and `-o` then names a directory: a woven document is something somebody
opens, so the unit is the file that was written rather than the tree. The ordinary `.sysl` files
alongside are passed over, and a tree with no literate source at all is refused rather than producing
an empty page.

It is a **source-level** command: no target, no standard module, no libraries. A package's prose is
worth reading on a machine that could not build it. What it does share with a compilation is the
reading, so a file the compiler would refuse — a tab in an indent, a fence that is never closed — is
refused here too, with the same message.

This is not an API reference generated from declarations. That is [`doc`](#doc), further down: sysl
has [documentation comments](/reference/lexical/#documentation-comments) and `sysl doc` reads them.
The two answer different questions — `weave` renders a document somebody *wrote*, `doc` renders what
the declarations *say* — so a tree usually wants both, and neither is a substitute for the other.

### `tangle`

```bash
sysl tangle library/sysl/regex/vm.lsysl
sysl tangle library/sysl/regex/vm.lsysl -o vm.sysl
```

`tangle [-o <path>] <path>`. The other half of a literate system: the program, with the prose
stripped, written to standard output or to what `-o` names. A path holding several literate sources
gives their programs one after another; the ordinary `.sysl` files beside them are passed over, and a
tree with none is refused. A build tangles anyway —
that is how a `.lsysl` file compiles at all — so what this adds is a way to **see** it.

That is worth having when a literate file misbehaves. A block indented that should not have been, a
fence that swallowed a function: the question is always what the compiler actually read, and this is
how to ask. It also hands the program to anything that does not know the format — a tool, a paste, a
bug report.

**The prose is replaced by blank lines rather than removed**, so line 100 of the output is line 100
of the source. That is what lets a diagnostic about the program point into the document it was
written in, and it is why the output is not as short as the program looks.

### `deps`

The dependency graph the project resolves to: every package it will be compiled against, the version
selection settled on, and who asked for that package and at what floor.

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

**That project's manifest names one dependency**, which is what makes the command worth having:
[imports are transitive](/reference/packages/#imports-are-transitive), so three of those four
packages are ones nobody typed, and nothing in the files in front of you says they are there.

**A coordinate marked `(raised)` is one somebody asked for a lower version of.**

```
github.com/e/buf  1.4.0  (raised)
    github.com/e/a asks for 1.2.0
    github.com/e/b asks for 1.4.0
```

That is the answer to *why is this version the one I got*, and it is the half a build cannot tell
you: [selection](/reference/packages/#which-version-you-get) keeps the highest minimum and
nothing else, so the claim that lost is gone by the time anything could go looking for it. The note a
build prints covers only the case where *your own* manifest was overtaken; this covers a dependency
overtaken by its sibling, which is the case where the version you are running is one no file you own
mentions.

**A coordinate marked `(dev)` is one only this project's tests reach.**

```
github.com/sysl-lang/quickjs  0.1.0  (dev)
```

It is declared in [`dev_dependencies`](/reference/packages/#dependencies-a-test-alone-needs), so
`sysl test` resolves it and nothing that depends on this project ever fetches it. The listing shows
it because what a project *takes* is a property of its manifests rather than of any one build — and
the mark is what says a consumer will not be paying for it.

It is a mark rather than a section of its own, because a package reached *through* a dev dependency
is in exactly the same position without being named in either block, and a second section would have
nowhere to put it.

**A path dependency prints its directory** where a coordinate prints a version, since it has none and
the directory is the only thing that says which tree it is.

The command resolves exactly as a build does, so it fetches what this machine has not got and records
what it got in `sysl.sum`. It asks nothing of a machine beyond that — no target, no standard module,
no source is read — so a project that cannot be built here can still be inspected.

### `add`

A dependency written into `package.hocon`, at the newest version the repository has tagged or at one
you name.

```
sysl add github.com/sysl-lang/sdl3
sysl add github.com/sysl-lang/sdl3@0.3.1
```

```
added sdl3 github.com/sysl-lang/sdl3 0.3.1
```

The version comes from `git ls-remote --tags`, not from a forge's API — a coordinate here is a git
identity and the build already clones it with plain git, so this works for a self-hosted server or a
mirror as well as for GitHub. A tag that is not a version is passed over rather than refused, since
repositories carry `latest` and `nightly` and neither is something a manifest can pin.

**Your manifest is rewritten one run of bytes at a time**, and nothing else in it moves. Printing a
parsed value back would emit the value and nothing else — every blank line, every comment explaining
why a dependency is there, and your own layout would be gone the first time a tool touched the file.
A new entry takes the indent its siblings have and lines up with their column where they have one.

Nothing is fetched: this records what the project takes, and the next build goes and gets it. The
result is read back through the manifest reader before it is written, so a rewrite that produced
something unreadable leaves the file exactly as it was.

### `vendor`

Every package the project depends on, put in a `vendor/` directory beside the manifest.

```text
sysl vendor .
```

```text
fetching github.com/sysl-lang/parsing v0.12.0
vendored 1 package into vendor/
```

**It is the machine's package cache moved into the project, rather than a second mechanism beside
it** — the same layout, the same resolution, the same `sysl.sum`. A project that has a `vendor/`
builds with the network off, and somebody who clones it needs to know none of this: the directory
being there is the whole of what turns it on.

The graph is resolved against the machine's cache first, so anything the machine has not got is
fetched (the `fetching` line, on standard error) and anything `sysl.sum` has no line for gets one,
exactly as a build would. Each package is then **copied** out of the cache, and the copy's hash is
checked against its `sysl.sum` line before it is put in place: a cached package that somebody edited
after it was fetched is refused rather than frozen into your project. A repository's `.git` is left
behind. Running it again copies only what is not there yet.

What goes in is the graph `sysl test` resolves — every feature the project declares, unless the
[feature flags](#the-feature-flags) say otherwise, and its `dev_dependencies` as well as its
`dependencies` — so a vendored project's own tests fetch nothing either. Inside a
[workspace](/reference/packages/#workspaces) there is one `vendor/`, at the workspace root, holding
every member's packages.

`vendor/` is not part of the project's own source, exactly as
[`examples/`](/reference/packages/#a-package-may-carry-examples) is not, so nothing in it is compiled
as one of your modules. Commit it where you want a build that cannot be broken by an upstream
disappearing; leave it out of the repository where you would rather fetch.

**A path dependency is not vendored and cannot be.** It is a directory you are editing beside this
one — which is why nothing keeps a sum for it — so freezing a copy is the one thing it is there not
to do. The command says how many there were.

### `tidy`

`sysl.sum`, cut down to the packages the project resolves to now. A build only ever adds a line to
it, so a version the project has moved off stays recorded until this runs.

```text
sysl tidy
```

```text
removed github.com/sysl-lang/skitter v0.1.0
removed github.com/sysl-lang/box2d v0.1.0
```

It takes the working directory where no path is given. The graph it resolves is the whole one —
every feature the manifest declares and the `dev_dependencies` too — because a build that pruned
would drop the lines of whichever features it had not been asked for. A line that stays is left
byte for byte where it was, a file that is already tidy is left alone with nothing printed, and a
project depending on nothing but paths ends with no `sysl.sum` at all.

`--check` writes nothing and fails where the file is not tidy, naming what it would change — the
form to put in CI. Outside a project, with no `package.hocon`, the command is refused.
[`reference/packages.md`](/reference/packages/#sysl-sum) has the rule.

### `doc`

An API reference for a tree, generated from its declarations and their
[doc comments](/reference/lexical/#documentation-comments) — one Markdown page per module.

```
sysl doc library --out docs/api
```

```
sysl doc: 27 modules -> docs/api
```

**It writes Markdown rather than a website**, against what scaladoc, javadoc and rustdoc all do, and
the reason is what a package repository is for. A `docs/` folder of generated Markdown is readable in
the GitHub UI with no tooling, no hosting and nothing installed; Rust needs docs.rs — an entire
hosted service — to solve exactly that for crates. It is also diffable, where generated HTML cannot
be reviewed in a commit.

**The unit is a module**, not a type, because a module is what you import.

| Flag | Means |
|---|---|
| `-o`, `--out <dir>` | where the Markdown goes (default `docs/api`) |
| `-t`, `--title <text>` | the index page's title |
| `-n`, `--note <text>` | a note under the index page's title |
| `-V`, `--docversion <v>` | the version being documented, shown on the pages |
| `--private` | include file- and module-private declarations |
| `--site <dir>` | after writing, build the juicer site rooted at `<dir>` |
| `--check` | write nothing; exit non-zero if what is committed is stale |

**`--check` is what keeps committed documentation honest.** Generated files that live in a repository
go stale, so the answer is a job that regenerates and compares:

```
sysl doc library --out docs/api --check
```

```
sysl doc: docs/api is stale — 2 file(s) differ:
  sysl-text.md
  sysl-slices.md

Regenerate with 'sysl doc' and commit the result.
```

It exits `1` there and `0` when everything matches, so it drops straight into CI and fails a build
where somebody edited a doc comment and did not regenerate.

**It reads only the syntax.** Nothing is analyzed, so a package whose dependencies are missing — or
which does not currently compile — still documents, which is exactly when somebody is trying to read
their way out of trouble.

**A literate source is tangled first**, so a `.lsysl` file's narrative never reaches the generator: that
prose is an essay about the program and belongs to [`weave`](#weave). A doc comment written *inside*
its code blocks is read like any other. A literate module whose author explained everything in the
narrative therefore produces bare signatures here, and that is the honest answer rather than a defect.

#### The site is somebody else's

`sysl doc` is built into the compiler, and what it writes is Markdown and nothing else. `--site`
hands the pages to the **`juicer`** command, found on your `PATH`, to build the HTML: generating a
site means a templating engine, an asset pipeline and a web server, and a systems compiler has no
business carrying any of that. Without a `juicer` it says so and where one comes from.

### `targets`

The registry, one line per machine — the name to write after `--target`, the LLVM triple it stands
for, and, for a target sysl knows and cannot build for, why not:

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
riscv32-freestanding         riscv32-unknown-elf
wasm32-freestanding          wasm32-unknown-unknown
wasm32-wasi                  wasm32-wasip1
craft-freestanding           craft
x86-linux                    i386-unknown-linux-gnu  (no C calling convention has been measured for x86)
```

The line for the machine you are on is marked `(this machine)`, and a last line says which processor
and system sysl reads this machine as — `this machine reports: aarch64 / macos` on an Apple Silicon
Mac. That last line is there for the case the rest of the list cannot help with: on a machine sysl
has no entry for, it is the only place to read what sysl took it for.

**`aarch64-android` is the one row whose triple carries a version number, and the one that needs
something set in your environment.** The `24` is an Android API level — which of Bionic's declarations
exist — and it is in the triple because clang requires it there: without a level, no `__ANDROID_API__`
is defined and the first system header that guards a declaration on it refuses to compile. Its C
calling convention is `aarch64-linux`'s, because AAPCS64 is AAPCS64; what differs is everything above
the ABI, which is why it is a system of its own and `#if android` is a symbol distinct from
`#if linux`.

What it needs is *which clang*. Every other target here is served by a compiler that has the right
back end, and having the back end is not the same as having the toolchain: Android's headers and
libraries are the NDK's, and no clang outside it carries them. So sysl asks the environment.
`ANDROID_NDK_ROOT` or `ANDROID_NDK_HOME` names an NDK outright; otherwise `ANDROID_HOME` names the
SDK and the newest `ndk/<version>` under it is used.

```
export ANDROID_HOME=~/Library/Android/sdk
sysl build --target aarch64-android hello.sysl
```

`ANDROID_SDK_ROOT` is read too and means the same thing, but Android's own documentation marks it
deprecated in favour of `ANDROID_HOME` — so set that one, and if you already have both, keep them
pointing at the same directory, which is what Android Studio and the Gradle plugin check.

Nothing is guessed at. An NDK sits wherever you installed it, so a compiler that went looking through
your home directory would find one on the machine it was written on and the wrong one — or none —
anywhere else, and building against the wrong platform headers is a failure you would not see. With
nothing set, the build stops and says what to set:

```
sysl: error: building for Android needs the NDK's own clang, and nothing here says where it is — no clang outside the NDK carries Bionic's headers, so one picked for having the back end fails at the first '#include'. Set ANDROID_HOME to the Android SDK (the directory holding 'ndk/'), or ANDROID_NDK_ROOT to one NDK directly
```

That message exists because the alternative was worse: pick the host's clang for having `aarch64`, and
the build dies on a missing `dirent.h` inside the standard library's own C — which reads as a broken
library rather than as the wrong compiler.

Naming the NDK is the whole of what is required. Its clang resolves a sysroot from its own location,
so there is no `--sysroot` to pass and no include or library path to set up: the result is an ordinary
position-independent Android executable, which is what an APK loads.

**Eight of the freestanding rows are 32-bit microcontrollers.** The RP2350 — the Pico
2 — boots either a pair of Cortex-M33s or a pair of RV32IMAC cores; the RP2040, the original Pico, has
a pair of Cortex-M0+; the Armv7E-M rows are ST's parts; and Armv7-M is the Cortex-M3. All are here
because a microcontroller is what *freestanding* is mostly for: the three 64-bit freestanding rows
reach kernels and hypervisors, which is a different audience. `thumb` rather than `arm` names the Arm
ones because a Cortex-M executes Thumb only, so an arm written for A32 would assemble for a machine
that cannot run it.

**The Cortex-M33 has three rows, because neither the float ABI nor the FPU's presence is sysl's to
pick.** `thumb-freestanding` passes floating-point arguments in VFP registers, which is what `eabihf`
selects; `thumb-freestanding-softfp` passes them in core registers, which is what
`-mfloat-abi=softfp` means and what pico-sdk builds by default. The two
cannot be mixed — GNU ld refuses the link outright, saying one object *"uses VFP register arguments"*
and the other *"does not"* — so a sysl archive joining a C build has to agree with that build, and
offering only the first row meant the C had to be rebuilt to follow sysl. Pick the one your project
already uses; if you do not know, `softfp` is the pico-sdk default.

**Both of those rows use the M33's own unit, which is single precision.** `f32` arithmetic is
instructions and `f64` arithmetic is a call into the board's runtime — `__aeabi_dmul` and its family
— because an `fpv5-sp-d16` has no double-precision instructions to select. That is the part rather
than a setting, and it is worth knowing before a `f64` goes into an inner loop.

**`thumb-freestanding-soft` is the third, and it is for a board with no unit at all.** `softfp` is not
`soft`: `-mfloat-abi=soft` means no FPU instructions whatever, while `softfp` uses the `fpv5-sp-d16`
this core has and changes only the calling convention. That distinction is not something a triple can
carry — both rows are `thumbv8m.main-none-eabi` — so sysl says which it is on every clang command
line, with the convention beside it: `-mfloat-abi=soft -mfpu=none` for the `soft` row,
`-mfloat-abi=softfp -mfpu=fpv5-sp-d16` for `softfp`, and `-mfloat-abi=hard -mfpu=fpv5-sp-d16` for the
first. None of that is left to the compiler's default, because the default is not the same one twice:
the same triple reports a floating-point unit under some clangs and none under others, so a row that
said nothing would mean a different machine depending on which was installed. **Reach for it when your board's headers say the FPU is off**, which is
where the difference announces itself: CMSIS refuses the build with *"Compiler generates FPU
instructions for a device without an FPU (check `__FPU_PRESENT`)"*, and every Zephyr MPS2 board is
configured that way. Getting it wrong the other way is worse than a refusal — the image links, boots,
and takes a fault at whatever arithmetic reached a VFP instruction first.

`thumbv7em-freestanding` and `thumbv7em-freestanding-soft` are that same pair for Armv7E-M — an
STM32 F4 or H7 with the FPU on, and one with it off.

**`thumbv6m-freestanding` and `thumbv7m-freestanding` are different architectures, not further
conventions.** The first is the RP2040's Cortex-M0+ — Armv6-M, which came before Armv8-M rather than
being a subset of its options — and the second is the Cortex-M3. Build an original Pico's program for
`thumbv6m`; building it for a `thumb-` row produces instructions the core cannot execute, and the
failure is a fault at whatever ran first rather than a refusal at the link. Neither core has a
floating-point unit in the architecture, so neither needs a `-soft` sibling: there was never a second
answer to give.

Build a Cortex-M3's program for `thumbv7m` rather than for `thumbv6m`, even though Armv6-M code runs
on an M3. What does not survive the substitution is the *headers*: a real project reads its own
configuration, so it takes `CONFIG_CPU_CORTEX_M3` to mean Armv7-M and reaches for `BASEPRI`, while
CMSIS reading an Armv6-M triple hands it an intrinsic set that has none.

**One thing needs the board's help on that target, and only one.** Armv6-M has no atomic
instructions, so a program using `&sync` — the shared counted reference — compiles to calls the
toolchain does not supply, and the link fails naming `__atomic_fetch_add_4`. Every other program is
unaffected, because those calls are emitted only for a program that holds a `&sync`. The
[`pico`](https://github.com/sysl-lang/pico) package supplies them over one of the chip's hardware
spinlocks, which is what a dual-core part needs — masking interrupts is per-core and would leave the
other core free to lose the update.

**`wasm32-freestanding` is the odd row: 32-bit, freestanding, and not a board at all.** It is
WebAssembly — a browser, `wasmtime`, or whatever else embeds a module — and `unknown-unknown` in the
triple is the literal truth, so it comes with no libc, no loader and nothing that runs before an
exported function is called. Sysl links it with `-nostdlib` and names `main` as the module's entry,
which is what makes `main` reachable and exports it under that name; a program that does not print
comes out as a couple of hundred bytes of `.wasm` that `wasmtime` will run, and a program that does
print fails at the link naming `putchar`, exactly as on any other bare target.

It needs a clang with the WebAssembly back end. Apple's has eleven back ends and this is not among
them, so on a Mac sysl reaches for Homebrew's LLVM by itself — the same fallback it already makes for
the RISC-V rows — and says so if it cannot find one.

**`wasm32-wasi` is the same machine with a libc under it, and it is the row to reach for unless you
know you want the bare one.** WASI is a standardised set of *imports* — file descriptors, clocks,
randomness, arguments, exit — that a module asks its host for by name in place of syscalls, and
**wasi-libc** is a real libc built on them. So a program that prints links and runs, `sysl.fs` and
`sysl.env` have something underneath them, and nothing has to be written by hand before the first
build:

```
export WASI_SDK_PATH=~/wasi-sdk-34.0-arm64-macos
sysl build --target wasm32-wasi hello.sysl -o hello.wasm
wasmtime hello.wasm
```

It needs **wasi-sdk**, which is clang, wasi-libc and a sysroot in one download from
[the project's releases](https://github.com/WebAssembly/wasi-sdk/releases). That is the same
arrangement Android's NDK is under and for the same reason: having the `wasm32` back end is not
having the toolchain, so a clang picked for the back end alone succeeds at the search and fails at
the first `#include`. Nothing is guessed at, and with nothing set the build stops and says what to
set.

The version is **preview1**, which is what every runtime supports today and what the browser shims
(`@bjorn3/browser_wasi_shim`, Node's `node:wasi`) implement. preview2 — the Component Model, with WIT
interfaces and resource handles — produces something that is not a core wasm module at all and that
browsers do not run natively; the standard preview1-to-preview2 adapter lifts a module into a
component, so nothing here is a dead end.

**`craft-freestanding` is 16-bit, and it is the one row sysl will not drive a build for.** It is
CRAFT — a teaching ISA with eight registers, a 64 KiB virtual address space and a
software-managed TLB — whose LLVM back end lives out of tree, so what exists is an `llc` rather than
a compiler driver. The machine has no libc, no object format and **no linker**: its assembler reads
one file and resolves every label inside it. So sysl writes the LLVM and stops, and the rest is two
commands of your own:

```
sysl emit-llvm hello.sysl --target craft-freestanding > hello.ll
llc -march=craft hello.ll -o hello.s
craft as hello.s
```

Every other subcommand refuses this target and says that. It is not a target sysl cannot *lower*
for — `emit-llvm` produces an ordinary module — it is one with nothing for a driver to call.

**Sixteen bits is the part that shows up in your code.** `usize` is pointer-width by definition, so
a slice's length is a `u16` and the address space is the bound on everything. An `int` is still 32
bits and a `long` still 64, because a width is the language's answer rather than the machine's — so
ordinary arithmetic here is multi-word, and the back end expands it, exactly as every 32-bit target
already expands a `long`. Indexing with an `int` needs no conversion for it: an index wider than an
address is checked against what an address can name and then narrowed, so `for i in 0..<4 do b[i] …`
means what it means everywhere else.

`x86-linux` is listed *because* it cannot be built for. The limit is the compiler's rather than the
machine's, and **it is no longer the width** — this page said so until the 32-bit rows above arrived.
What is missing is a C calling convention measured against clang, which is the only way a target's
answers are allowed to be arrived at. A reader who names it is better told that than told the name is
unknown.

**Freestanding does not mean self-contained.** A program built for a bare board still names C symbols
its runtime has to define — `putchar` wherever anything prints, `free` wherever a reference count can
reach zero, `memcpy` and `memset` for a structure assignment you never wrote — and on a 32-bit
machine it names a 64-bit division helper as well, because a `long` is sixty-four bits everywhere and
neither RP2350 core has the instruction. None of that is a sysl dependency the compiler could warn
about: it is what any C compiler emits for the same code, and a real project never meets it because
its SDK has linked `libgcc` or compiler-rt already. Meeting it looks like `undefined symbol:
__aeabi_ldivmod` at the link, which is the one place anybody will come looking for this paragraph.

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
| `--general-regs-only` | keep the code out of the floating-point and SIMD registers — the [`codegen` block](/reference/packages/#what-the-generated-code-may-use-of-the-machine) for one build |
| `--strict-align` | make every access aligned — the [`codegen` block](/reference/packages/#what-the-generated-code-may-use-of-the-machine) for one build |
| `-p <member>`, `--package <member>` | in a [workspace](/reference/packages/#workspaces), the member to build, test or run |
| `-v`, `--verbose` | report what the build decided: the files read, the standard module and how it was reached, the search paths, the allocator, and the clang and linker command lines |
| `--explain-escapes` | report every local array promoted to the heap |

**An option no command takes is refused, never passed over.** Each mistake is named on a line of its
own, the command's usage under them, on standard error, and the exit status is 2 — so a misspelt
`--relase` is never quietly built as a debug binary, and one taken for the path is never compiled:

```text
Error: Unknown option --relase
usage: sysl build [--target <name>] [--cc <path>] [-O<level>] [-o <exe>] …
```

An option that wants a value and was given none says `Error: Missing value after --target` the same
way. An option one command takes and another does not — `-o` on `test` — is unknown to the second.
A lone `-` is a path, and what follows `--` in `run` is the program's, options included.

The standard-module flags and `-O` are covered in
[installation](/getting-started/installation/), including why the default is `-O1` and not off.
`--lto` is the flag form of the [`lto` manifest key](/reference/packages/#link-time-optimization),
`--link` of the [`link` key](/reference/packages/#linking-a-library-statically), and the two profile flags are the ends of the workflow below.

### Profile-guided builds

**A profile-guided build is three steps and two of them are sysl's.** The optimizer is told what the
program actually did — which branch is taken, which call is hot, which function is never reached —
and lays it out around that, rather than around what a static reading of the code suggests.

```
sysl build . --lto thin --profile-generate prof -o myprog-instr
./myprog-instr <each job in the training set>
"$(clang -print-prog-name=llvm-profdata)" merge -output my.profdata prof/*.profraw
sysl build . --lto thin --profile-use my.profdata -o myprog
```

1. **Instrument.** `--profile-generate` reaches every clang the build drives — the module, each C
   file a package carries, and the link, which is where the counting runtime comes from. The
   resulting program is larger and slower, which is what instrumentation is.
2. **Train.** Run it over work that resembles the work it is for. Each run writes into the directory
   named, under a name derived from the binary, and **the runtime merges into that one file rather
   than overwriting it** — so twenty-three training runs leave one `.profraw` holding all of them.
3. **Merge, then build against the result.** `--profile-use` wants the *indexed* profile
   `llvm-profdata merge` writes, not the raw counters the program wrote.

Asking for both flags at once is refused, since clang takes both and quietly instruments the build
you asked it to optimize. Neither is a manifest key: a profile describes one measurement on one
machine, and a path to it in a file a consumer reads is a path that is wrong for everybody but its
author.

#### Use the `llvm-profdata` the compiler names, not the one on the PATH

A `.profraw` carries a format version, and `llvm-profdata` reads only the version its own LLVM
writes — so the tool has to match the **compiler**, not the machine. On a Mac those are routinely
different, and the failure reads as a corrupt profile rather than as the wrong tool:

```
warning: default_15853205779378358405_0.profraw: raw profile version mismatch: Profile uses raw
profile format version = 10; expected version = 11
error: no profile can be merged
```

`clang -print-prog-name=llvm-profdata` is the question that cannot get this wrong: it asks the driver
where its own tools are. Apple's clang answers with the one inside Xcode's toolchain, which is
nowhere on the `PATH`, while the `llvm-profdata` Homebrew's LLVM puts *on* the `PATH` is from a
different LLVM entirely.

#### Nothing leaks into an ordinary build

A build that did not ask for instrumentation carries none of it: `nm` over a plain build finds no
`__llvm_profile` symbol at all, and the binary built with `--profile-use` finds none either — a
profile is read at compile time and leaves nothing behind.

#### What it was worth

Measured on the slate interpreter, trained over its own benchmark suite and measured on the same
suite by alternating best-of-9, **on top of** `-O2` with thin LTO:

| | geomean | binary |
|---|---|---|
| `-O2`, `--lto thin` | — | 6,013,000 bytes |
| the same, instrumented | (not for use) | 10,162,936 bytes |
| the same, `--profile-use` | **−12.5 %** | 5,880,888 bytes |

Against the plain `-O2` build the two levers together are about **−31 %**, and the profile-guided
binary is *smaller* than the one that never saw a profile — cold code is laid out where it does not
crowd the hot path. Every one of the 64 programs checked answered byte-identically.

**Training on the work you then measure flatters the result**, and the table above does exactly that.
A profile is worth what it is worth for jobs the training set resembles; for a workload nothing in
training looked like, expect less.

### The feature flags

`run`, `build`, `build-c`, `test`, `deps` and `vendor` take three more, which choose the root
project's [features](/reference/packages/#features) — and so which optional dependencies are fetched
at all:

| | |
|---|---|
| `--features <a,b>` | a feature of the root project to turn on, beside `default`; comma-separated, and may be given more than once |
| `--no-default-features` | leave the root's `default` feature off |
| `--all-features` | turn on every feature the root's manifest declares |

**`--all-features` may not be combined with either of the others**, and that is a third refusal of the
same kind as the two below: it says everything the manifest declares is wanted where they each say
something narrower about the same set, so no precedence could keep both answers.

```
sysl: error: --all-features says every feature the manifest declares is wanted, and
--features/--no-default-features each say something narrower about the same set — a compilation
cannot ask for both
```

`sysl test` given none of the three enables **every** feature the manifest declares, because gated
code no build compiles is gated code nobody is testing. Naming one turns that off, so a suite may be
run at exactly the configuration a consumer would get.

### `--link-path`, `--include-path` and `-D` are three steps of one thing

A module that binds a C library carries C of its own, and that C has to be found, compiled and
linked. The three flags answer the three ways it fails, in the order it fails them:

- **`--include-path`** — the shim `#include`s the library's header and cannot find it. This is the
  first failure and the one that surprises, because it happens before anything reaches a linker.
- **`-D`** — the header is found and refuses, because a C project of any size configures its own
  headers with macros and has not been asked. pico-sdk's `pico/cyw43_arch.h` `#error`s rather than
  guessing which architecture variant is meant, which is the shape to expect.
- **`--link-path`** — everything compiled and the archive is not where the linker looks.

Nothing is guessed at and nothing is defaulted. sysl does not add `/opt/homebrew/lib`, and it does
not invent a macro: a compiler that ruled on where a platform keeps its libraries, or on how a
project configures its headers, would be wrong on a machine nobody here has — and being wrong there
costs a build that fails somewhere its author cannot reach. What a project defines is the project's.

A build system that already knows these will have them: reading them out of CMake is a matter of
asking the target for its `INCLUDE_DIRECTORIES` and `COMPILE_DEFINITIONS` and handing each along.

A **package** whose C includes headers it does not carry can say so, and then the first failure above
stops being a surprise: written `--include-path <name>=<dir>`, the flag answers a requirement the
package declared, and a build that is missing one is refused by name before clang runs. See
[packages](/reference/packages/#headers-a-package-needs-and-does-not-carry).

### `--lib` takes a source tree

A library is handed over as the directory it is written in — a
[source root](/reference/modules/#source-roots) — and what the compiler makes from it is its own
cache, which nobody names. A path to a `.syslib` is refused:

```text
sysl: error: --lib takes the directory a library's source is in, and 'mylib.syslib' is a '.syslib' — a library is handed over as the tree it is written in, and the compiler keeps its own cache of what it compiled from one, which nobody names
```

**A source tree that is a dependency's package overrides that dependency.** Building against a
working copy of something the manifest names is one flag, with the manifest left alone:

```
sysl build . --lib ../geom
```

Where `../geom/package.hocon` names the package `geom` and the project depends on
`github.com/e/geom`, the checkout is what is compiled — for that dependency and for any other package
in the graph that depends on it — and the coordinate is never fetched, so a version nobody has
published yet is no obstacle. A tree that only *holds* a module a dependency also offers, without
being that package, is refused as a collision instead.
[`reference/packages.md` § A source root stands in for the package it is](/reference/packages/#a-source-root-stands-in-for-the-package-it-is)
has the rule.

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
sysl: error: unknown target 'arm-linux' — sysl knows aarch64-macos, x86_64-macos, aarch64-linux, x86_64-linux, riscv64-linux, x86_64-windows, aarch64-android, aarch64-freestanding, x86_64-freestanding, riscv64-freestanding, thumb-freestanding, thumb-freestanding-softfp, thumb-freestanding-soft, thumbv6m-freestanding, thumbv7m-freestanding, thumbv7em-freestanding, thumbv7em-freestanding-soft, riscv32-freestanding, wasm32-freestanding, wasm32-wasi, craft-freestanding, x86-linux
```

### `-v`, `--verbose`

What the build decided, on stderr, each line led `sysl:` — the files it read, which standard module
it got and by which route, where it looks and which heap it allocates through, then every command
line it handed to clang:

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
`--link-path`, `--include-path` and `-D` is said too (`library:`, `link path:`, `include path:`,
`define:`), ahead of the allocator — which names who chose it where a package or the project did. A
test suite links like any other build and says the same. There are no phase timings: a build that
is slow is diagnosed by asking what it *did*.

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

## The standard module is never named

The compiled standard module is the compiler's cache, so no flag points a build at one. `--std-lib`
is refused by every command in the same words, before any of them reads its own options:

```
sysl: error: --std-lib names a '.syslib', and a '.syslib' is this compiler's cache, which nobody names — to compile against another standard module, set SYSL_LIB to the root its source is in
```

## Exit statuses

| | |
|---|---|
| **0** | it worked — and for `test`, every test that ran passed |
| **1** | a compiler diagnostic, a driver error, or a failing test |
| **2** | the command line did not parse — an unknown option, a missing value, no subcommand |
| *the program's* | `run` only, once the program has started |

A compiler diagnostic is printed on standard error, by every command, exactly as the compiler wrote
it: the message, the location, the
line it happened on, and what went wrong underlined beneath it. The underline is as wide as the thing
being complained about — a name, a literal, or a whole expression where the expression is what is
wrong — and stops at the end of the line, since only the one line is quoted.

```
error: 'b' of 'add' is int, but string was given
 --> hello.sysl:7:14
  |
7 | print(add(x, "two"))
  |              ^^^^^
```

A driver error — something that went wrong *around* the compilation rather than inside it — is
prefixed `sysl: error:` and written to standard error, which is why every message quoted on this
page carries it and none of the ones on the language pages do. A command line that did not parse is
the one exception: it is answered `Error: …` with the command's usage under it, as above.

---

Next: the [tour](/tour/), which uses `run` throughout.
