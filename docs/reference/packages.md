---
title: Packages
summary: package.hocon, dependencies on other people's code, version selection, and what a fetched package's modules are called here.
weight: 105
---

A project's configuration and its list of dependencies are **one file, `package.hocon`, at the
project root**. It says who this package is, what machines it is built for, what those machines
provide, and what it depends on.

**The file is optional.** A single-file program has none, and gets the defaults: the project root is
the directory the compiler was given, the target is the machine it is running on, and that target
provides everything. `sysl run hello.sysl` needs no ceremony.

```hocon
package {
  name    = "geom"
  version = "1.4.2"
}

targets {
  default = "aarch64-macos"
}

capabilities { heap = false }

requires { os = true }

dependencies {
  json  { git = "github.com/edadma/sysl-json", version = "1.4.0" }
  regex { git = "github.com/edadma/sysl-regex", version = "0.4.0", mount = "re" }
  local { path = "../experiment" }
}
```

## What a project is called

**`package.name` is what a directory project's executable is called.** A directory is a project
because it holds `.sysl` files, not because anybody said so, so a project has no identity of its own
unless this block gives it one. Without a name the output takes the directory's:

```
myproj/main.sysl        ->  myproj/myproj
myproj/package.hocon    ->  myproj/tool
  package { name = "tool" }
```

Requiring the file was the other way to answer this, and it is deliberately not what happens: it
would give every project an identity and charge every project the ceremony, when a scratch directory
holding one `.sysl` file is the cheapest thing in the toolchain and worth keeping cheap.

A **file** project is outside this. `sysl build foo.sysl` writes `foo` beside the caller, whatever a
`package.hocon` sitting in the same directory says — the name came from the path you typed, and a
config quietly moving the executable would be a worse surprise than anything it fixed.

The name reaches the filesystem, so it has to be a single path segment. `.`, `..`, anything holding a
separator, and the empty string are refused when the file is read, rather than being sanitized into
something that would build a differently-named executable without saying so.

**And it may not be the name of a module directory beside it**, since the binary would be written
over one:

```
error: this project builds an executable named 'slate', and 'slate' is a directory — the two
       cannot both have that name. Rename the package or the directory, or say where the binary
       goes with '-o <path>'
```

Naming the package for its own module is the obvious layout and reaches this without trying. `sysl
run` never does, writing to a temporary file of its own, so a project can carry the collision until
the first time somebody asks for a binary.

## A package may carry examples

**`examples/` at a project root is not part of the tree.** Everything else under a root compiles
*into* it, which is what makes the exclusion necessary rather than convenient: a `.sysl` file with no
`module` header is the anonymous root module wherever it sits, and a library may not have one — so
without this a package could not carry a runnable demo at all, and every one of them would be a
fenced block in a README that nothing compiles.

```
mypkg/package.hocon
mypkg/sh/sysl/mypkg/mypkg.sysl     the library
mypkg/examples/demo.sysl           a program, and not part of it
mypkg/examples/bigger/main.sysl    a program of several files
```

The directory is left out of the library build, out of the C the package carries, out of the modules
the package offers, and out of the walk `sysl test .` makes — that walk takes every `.sysl` under the
root and refuses one that declares no module, so the exclusion has to reach it too or the feature
does not work.

**A directory holding a `package.hocon` of its own is left out the same way, wherever it sits** — it
is another project ([workspaces](#a-directory-holding-its-own-manifest-is-another-project)). A demo
that wants dependencies of its own, or is more than a few files, is better as a project in
`examples/<name>/` with its own manifest and the package as a workspace member it depends on.

**A program in it compiles against the package with nothing on the command line.** `sysl run
mypkg/examples/demo.sysl` and `sysl run mypkg/examples/bigger` both find the package they sit under
and use it as a library, exactly as a `--lib` naming it would — so the manifest's own dependencies,
its C and its search paths all reach the demo.

That is a rule about the shape of a path rather than a search: exactly two levels up, only where the
intervening directory is literally `examples`, and only where a `package.hocon` is there. A manifest
is looked for **beside** the sources everywhere else in this system, deliberately, because a search
that walked upward would make a build depend on directories above the one it was given.

**The exclusion is at the root and nowhere else.** `sh/sysl/mypkg/examples/` is an ordinary module
named `sh.sysl.mypkg.examples`, so a package that wants the word still has it.

## A key this compiler does not know is reported and ignored

A manifest is read by whatever compiler is in hand, which may be older than the manifest. So an
unrecognized key is **warned about and skipped**, and the rest of the file still takes effect:

```
package.hocon: 'profiles' is not something this sysl understands, and is ignored — either it is a
               misspelling, or it was added by a newer compiler
```

That is the middle of three, and the other two are worth naming because the choice is permanent —
whatever the first stable release does with an unknown key is the compatibility floor every later
manifest has to clear. **Refusing** would mean no key could ever be added without breaking every
released compiler, which for a format whose consumers pin coordinates is a promise not to grow.
**Ignoring silently** is the failure that reads as success: a mistyped `dependencis` block does
nothing and says nothing.

A **dependency's** unknown keys are reported with the package root in front of them, because that is
the case a manifest cannot report for itself — a package written against a newer sysl, being built by
an older one:

```
/…/cache/github.com/sysl-lang/thing@0.2.0: package.hocon: 'profiles' is not something this sysl
understands, and is ignored — …
```

**Inside `dependencies` and `allocator` an unknown key is still refused**, and the asymmetry is the
point: those are a closed vocabulary rather than a growing one. Somebody who wrote
`versoin = "1.0.0"` believes they have pinned a version, and resolving whatever the default branch
happens to be while they believe it is worse than stopping. The same judgement applies to a
misspelled capability.

## The oldest compiler a package builds with

**`package.sysl` states a floor**, and a build stops there rather than somewhere inside the package:

```
package {
  name    = "sdl3"
  version = "0.2.6"
  sysl    = "0.0.62"
}
```

```
package sdl3 v0.2.6 cannot be built because it requires sysl 0.0.62 or newer,
while the compiler in hand is 0.0.61
```

**The whole of what it buys is that sentence.** A package using something the language grew builds
or does not depending on what the consumer happens to have installed, and when it does not, the
diagnostic points at a line **inside somebody else's package** with nothing to say the compiler is
what is wrong. `sdl3` is the live example: v0.2.6 writes a bare `None` as a method default, which
needs 0.0.62, and a consumer on 0.0.61 gets a type-inference error inside `video.sysl`.

It is a **version** like every other version here — three numbers, no range and no pre-release, which
is [the version rule](#the-major-version-rides-in-the-coordinate) and not a second one. A range would
be a claim about compilers that do not exist yet, and the field is a floor precisely because nobody
can make that claim.

**Both kinds of manifest are held to it**: the project being built, and every package it depends on.
Saying nothing is the ordinary case and is never an error.

**A compiler's own version is ordered as semver orders it**, pre-release included. A release
numbered `0.1.0-alpha.1` sits above every `0.0.x` and below `0.1.0` itself, and alphas order by
their number — `alpha.2` before `alpha.10` — so every `0.0.x` floor in the org is met by the alpha,
and a floor of `0.1.0` is not:

```
this project cannot be built because it requires sysl 0.1.0 or newer, while the compiler in hand is 0.1.0-alpha.1
```

**A development build checks no floor at all.** A compiler built from a checkout rather than cut as
a release says `0.0.1`, which makes no claim about what it has, so no floor is too high for it.

**An older compiler cannot report this**, and nothing can change that: it does not know the key, so
it reads the manifest, ignores the field, and fails wherever it was going to fail. The field starts
paying from the release that understands it — which is also why adding one to a published package is
safe, since every compiler that came before simply passes over it.

**What it deliberately does not do is feed the resolver.** A dependency whose newest version is too
new is refused rather than resolved to an older one. Cargo added `rust-version` in 1.56 and only
taught the resolver about it thirteen years later, behind an opt-in; and here there is no registry to
ask — a dependency is a git coordinate plus a tag, and "the newest tag whose floor I satisfy" means
fetching and reading several tags' manifests, which is a different algorithm and a different fetch.

## The optimization level a project is built at

**`optimization` names the level handed to clang**, spelled as clang spells one after the `-O`, and
it applies to every command that builds the project — `build`, `run`, `test`, `build-lib` and
`build-c` alike:

```hocon
package {
  name    = "mimic"
  version = "0.3.0"
}

optimization = "2"
```

It is a top-level key rather than something inside `package`, because it is a decision about how this
project is built rather than a statement of who it is — the same tier as `targets`, `capabilities`
and `allocator`.

**The default is `1`, and a project that says nothing gets it.** That is the interesting half of the
default and it is written up in [the CLI's optimization
section](/getting-started/installation/#optimization): `-O0` is a different instruction selector
rather than merely a slower one, and a miscompile was found living there.

### The flag beats the key, and the key beats the default

`-O` on the command line is for **this invocation** and wins over a manifest that speaks for all of
them:

```
sysl build .            the project's level, or 1 where it names none
sysl build . -O0        0 — what was typed wins
```

Which is why naming the default and naming nothing are two different things: `sysl build . -O1`
against a project that says `optimization = "2"` builds at `1`, because somebody typed it.

**Nothing repeats itself as a consequence.** A project measured at `2` is built at `2` by every
consumer, every CI job and every `sysl test` without a flag anywhere, and dropping to `0` for an
afternoon's debugging is still one word on one command line.

### Only the root project's key applies

**A dependency's `optimization` is read and not used.** The level reaches every object a build
produces, so a package that could set it would be deciding how its consumer's whole program is
compiled — including the half it has nothing to do with, and against a judgement the consumer is the
only one able to make.

It is not an error and draws no warning, exactly as a dependency's `targets { default }` is not — a
package says which machine *it* is built for, and the build is for the machine the project asked for.
A package that states the level it is developed at has said nothing wrong either. It is simply not the
project being built, and the key is the root's or nobody's.

### A level clang does not have is refused when the file is read

The levels a manifest may name are `0`, `1`, `2`, `3`, `s` and `z` — the six every clang has:

```
package.hocon: 'optimization = "9"' names no level clang has — it is one of 0, 1, 2, 3, s, z
```

**`--optimize` is checked the same way, before anything is built, against a list two longer**: the
six, plus `fast` and `g`, which a build somebody is standing in front of may reasonably ask for and a
manifest every consumer inherits may not. Anything else is refused with the whole list:

```
sysl build . -O2        built at 2
sysl build . -Og        built at g — a command-line level a manifest may not name
sysl build . -O7        '-O7' names no level clang has — it is one of 0, 1, 2, 3, s, z, fast, g
sysl build . -O4        '-O4' names no level clang has — it is one of 0, 1, 2, 3, s, z, fast, g
```

**It is refused before the compiler touches its cache**, because the level names the directory the
standard module is compiled into. Clang answers `-O7` with a warning that it is using `-O3` instead,
not with a refusal, so a flag passed straight through would build a whole standard module under a
key naming a level that does not exist — and report success.

The two lists differ because of who is watching: a flag is typed at one build, while a manifest is
written once and read by every build afterwards — including a consumer's, who did not write the file
and should not inherit a level chosen for one afternoon's debugging.

**`sysl run` notices a change to the key.** What it keeps is keyed on the level the build resolved
among everything else that reaches the bytes, so raising `optimization` rebuilds rather than replaying
a binary compiled at the old level from a tree that did not move.

## Link-time optimization

**`lto` asks the linker to optimize across every object**, which is the second of the two things a
project states about how it is compiled:

```hocon
package {
  name    = "slate"
  version = "0.1.2"
}

optimization = "2"
lto          = "thin"
```

`thin` keeps a summary per module and links at close to an ordinary link's cost; `full` merges every
module into one and is the thorough, slower one. `lto = true` means `thin`, which is the answer for a
project that has not thought about it, and `lto = false` says the same as saying nothing. The
command-line spelling is `--lto thin`, and it beats the key exactly as `-O` beats `optimization`.

### Why a whole-program language has anything to gain from it

A sysl program is lowered into **one** LLVM module, so the cross-translation-unit inlining that LTO
exists to give a C project is already available to sysl's own code at `-O2`. What is not in that
module is everything reached over the FFI: the C a package vendors, the shims beside the standard
library, and whatever a `@link` names. Those are separate objects compiled by a separate clang, and
LTO is the only thing that lets a call into one of them be inlined.

So `lto` is not a second way of asking for what `-O` already does. It decides whether the seam
between sysl and its C is an optimization barrier — and it decides it for the whole link, which is
also why it is the root project's key and never a dependency's.

**It is worth measuring rather than assuming, and on one real program it was worth a great deal.**
The slate interpreter — a sysl program of some size, linking seven packages' vendored C — was built
at `-O2` and then at `-O2` with `lto = "thin"`, and measured over its own benchmark suite by
alternating best-of-9:

| | geomean | binary |
|---|---|---|
| `-O2` | — | 5,978,856 bytes |
| `-O2`, `lto = "thin"` | **−21.5 %** | 6,013,000 bytes |
| `-O2`, `lto = "full"` | −11.0 % | 5,767,024 bytes |
| `-O3` | −2.2 % | 6,177,768 bytes |

Every one of the 64 programs checked answered byte-identically under all of them, and the build took
the same time to within the noise (110–114 s). **`thin` beat `full`**, which is the result to take
from the table rather than the exact percentages: `full` is not a stronger `thin`, it is a different
set of inlining decisions, so a project that wants the most out of this measures both.

**The `-O3` row is there to be argued with.** It is the lever reached for first, it is a tenth of
what the row above it is worth on this program, three of the twenty-three benchmarks came out
*slower* under it, and it costs 3 % of binary size. Raising the level is worth measuring; it is not
worth assuming.

### A mode clang does not have is refused when the file is read

```
package.hocon: 'lto = "thick"' names no kind of link-time optimization clang has — it is thin or
full, or 'true' for thin
```

The reasoning is `optimization`'s: a manifest is read by builds nobody is watching, so a typo that
clang would report arrives at a consumer with nothing naming the file or the key. `sysl run`'s cache
carries the answer too, so adding `lto` to a tree that did not otherwise move rebuilds rather than
replaying the ordinary link's output.

## Linking a library statically

**`link` says which of the libraries a build found through `pkg_config` are linked from their static
archives** rather than as shared libraries — the third thing a project states about how it is built:

```hocon
package {
  name    = "slate"
  version = "0.1.6"
}

optimization = "2"
link         = "static"
```

`"static"` means every library any `requires { pkg_config { … } }` names anywhere in the build — the
project's own and every package's, however deep — **taken from its archive wherever it has one**. A
list names some of them, each of which *must* come from its archive, and leaves the rest as they
were:

```hocon
link = ["openssl", "libuv"]
```

The names are the ones the manifests write under `pkg_config` — `libuv`, not the `uv` its `@link`
says — because that is the name a reader can see. `"dynamic"`, and an empty list, say the same as
saying nothing. The command-line spelling is `--link static`, `--link openssl,libuv` or
`--link dynamic`, on `build`, `run` and `test`, and it beats the key exactly as `-O` beats
`optimization`. **Only the root project's key applies**, for `lto`'s reason: how a program is linked
is decided once for the whole of it, and a package that could decide it would be deciding for code it
never sees.

### What it buys

A program linking a dozen shared libraries pays for loading them every time it starts: the dynamic
loader maps each one, binds its symbols and runs its initializers before `main` runs a line. For a
program that runs for milliseconds, that is most of its run — the slate interpreter spends about
2.7 ms of each short benchmark there. An archive is copied into the binary at the link instead, once.

### What reaches the link line

For each library marked static, sysl asks `pkg-config --static --libs`, which adds what the library
links *privately* — a shared library carries those inside itself and an archive does not — and
`--variable=libdir`. Every `-l<name>` of that answer with a `lib<name>.a` in its `-L` directories,
its `libdir` or a `--link-path` directory is replaced by **the archive's path**:

```
-L/opt/homebrew/Cellar/libuv/1.52.1/lib … /opt/homebrew/Cellar/libuv/1.52.1/lib/libuv.a -lpthread -lm
```

**The path, because `-l` cannot be made to mean the archive on macOS.** Apple's `ld` takes a `.dylib`
over a `.a` it finds in the same directory, and Homebrew installs both side by side, so the file
itself is the only spelling that reaches the archive. The same spelling works unchanged on Linux.

`otool -L` is the check. The program above, built with `link = ["libuv"]`:

```
out:
	/usr/lib/libSystem.B.dylib (compatibility version 1.0.0, current version 1359.0.0)
```

### What stays dynamic

**The platform's own libraries are never taken from an archive**, whatever the key says: `libSystem`
on macOS, and `libc`, `libm`, `libpthread`, `libdl` and the C++ runtime everywhere; a `-framework`
is never touched either. Linking one of those statically is not a faster program but a different one: on macOS
`libSystem` is the only supported way into the kernel, and on Linux a static `libc` beside the dynamic
loader is two copies of `malloc`.

A library only `--static` added — a private dependency of the one that was asked for — stays an `-l`
where it has no archive, since nobody named it and it may well be a system library installed without
one.

**Under `"static"`, so does a library the program links directly and nobody named.** The key is
written once over a dozen libraries, and one of them is often the system's own — macOS's `sqlite3`
lives in `/usr/lib` with no archive beside it — so "every library" can only mean *every library that
can be*. Such a library is linked exactly as a dynamic build links it, and `--verbose` says so:

```
static: 'sqlite3' has no 'libsqlite3.a', so -lsqlite3 is linked dynamically — looked in /usr/lib
```

### A library a list names with no archive is refused

A library the program links directly, **named in the list**, with no archive anywhere it was looked
for stops the build, naming the file and the directories:

```
'libuv' is to be linked statically, and there is no 'libuv.a' to link it from — looked in
/opt/homebrew/Cellar/libuv/1.52.1/lib. Install its static archive there, or leave 'libuv' out of
'link' to link it dynamically
```

A build that named a library and quietly got its `.dylib` would be the very program the key exists to
prevent, with nothing to say so. For the same reason a name no `pkg_config` requirement answers to is
refused rather than ignored, since a misspelling would otherwise link dynamically and say nothing:

```
'link' names 'libvu', and no pkg_config requirement in this build is called that — the names are the
ones the manifests write under 'requires.pkg_config', which here are libuv
```

**A name that only a [feature](#features) this build leaves off requires is not a misspelling**, and is
left out rather than refused. The list is written once for every build of the project, and a build
with `--no-default-features` that does not link `lmdb` at all has nothing of it to link statically:

```
link: 'lmdb' is required only under a feature this build leaves off, so there is nothing of it to link
```

Telling the two apart takes the packages behind the features that are off, which this build never
read — so where a name matches nothing, the build is resolved a second time as `--all-features`
would resolve it, and the name is checked against every requirement found there. That is also the
list the refusal offers, so a misspelt `lmbd` is shown `lmdb` among the names whether or not its
feature is on.

And a single name written as a string, which is the likeliest slip:

```
package.hocon: 'link = "libuv"' is neither "static" nor "dynamic" — to link one library statically,
name it in a list: 'link = ["libuv"]'
```

`sysl run`'s cache carries the choice, so changing `link` over a tree that did not otherwise move
relinks rather than replaying the other kind of binary.

## Leaving the bounds locations out

A failed bounds check [says what failed and where](/reference/errors/#what-stopping-looks-like),
and the *where* is a `file:line:column` string per check carried in the image. A board short of
flash can drop them:

```hocon
bounds_locations = false
```

The report keeps its values — `index 5 out of bounds for length 3` — and loses only the location;
no location string reaches the module at all. `--no-bounds-locations` on the command line does the
same for one build. The key is `true` or `false` and nothing else, and anything else is refused when
the file is read.

## Capabilities

**Whether the machine has a heap, an operating system or POSIX is a project engineering
decision, and this is where it is stated.** The compiler's registry of targets deliberately carries no
capabilities: a target's ABI is measured and its capabilities are policy, so the ABI is the registry's
and the policy is yours.

```hocon
capabilities { heap = false }
```

That is the project's own statement and it applies to **every** target the project builds for. A
capability the file does not mention is provided — the prior is that a machine can do everything,
which is what every build had before there was a file to say otherwise, so what a config records is
what a machine *cannot* do.

**A target block layers over it, per capability, for the one machine that differs:**

```hocon
capabilities { heap = false }

targets {
  default = "thumbv7em-freestanding"
  aarch64-macos { capabilities { heap = true } }
}
```

Only the capabilities a target block names are overridden; everything else still comes from the
project's own block. Writing the statement only inside target blocks — which was once the only place
it could go — keys it to a machine's name, so a project building for three targets says it three
times and cannot say it at all for a target the registry already has without a block that reads as
redefining the machine.

### `capabilities` against `requires`

The two blocks point in opposite directions and neither substitutes for the other:

| block | says | about |
|---|---|---|
| `capabilities` | this machine **has** these | the target being built for |
| `requires` | this package **cannot be built without** these | the host it needs |

`requires { heap = true }` buys one clean error when the package is built for a machine without one,
instead of an error at every `&T`. A `false` there says nothing — a package does not need a facility
*not* to exist — and is refused rather than quietly dropped, naming the two places that do mean it.

### `heap`, and the module's own `@no_alloc`

The capability is `heap`, a **facility** the machine either has or has not. A module's promise that
its own code does not use one is spelled [`@no_alloc`](/reference/modules/#capabilities-are-a-module-property),
a **conduct**. They are two words because they are two statements, and each is refused where the other
belongs.

For compatibility with packages already published, `alloc` is still accepted in this file and read as
`heap`. Write `heap`.

## One heap, and the package that names it

Having a heap is one question; **which** heap is another. A program allocates through one pair of C
functions, and a package that brings its own says which:

```hocon
allocator {
  alloc = "pvPortMalloc"
  free  = "vPortFree"
}
```

Saying it settles the pair for the whole program, not for the package that said it. Every allocation
the compilation emits calls that pair — a string concatenation, a `Buf` growing, a box the reference
counter builds — and every release gives the storage back to it. Declare nothing anywhere and the pair
is libc's `malloc` and `free`, which is what a program depending on nothing that says otherwise gets.

**It is the program's pair rather than the package's, because there is one heap.** Ownership is
settled by reference count, so which code frees a thing is not knowable when a package is written: a
`Buf` filled inside an RTOS package and handed back is freed by the application, and one built by the
application and passed in is freed by the package. Two allocators would make every one of those
crossings a heap boundary that no signature marks.

A package declares it rather than a target, and that is deliberate. The obvious alternative is a
target fact — the machine knows it is a Cortex-M — and it does not survive contact: `thumbv7em` does
not imply FreeRTOS, two RTOSes on one chip want different pairs, and a bare-metal program on that chip
wants libc's. What knows the answer is the package carrying the heap.

### Two that disagree, and two that agree

Two packages naming different pairs is refused when the dependency graph is resolved:

```
two packages name different allocators, and a program has one heap — 'freertos' names
pvPortMalloc / vPortFree; 'arena' names arena_alloc / arena_free. Drop one of the
declarations, or depend on only one of them
```

Refused there rather than at the link, because the link will not refuse it: both symbols resolve, the
program builds, and it hands one allocator's storage to the other's `free` at run time.

Two packages naming the **same** pair unify to it. That is the ordinary case rather than a coincidence
— a kernel package and a driver built on it both name the kernel's allocator, and neither has to know
whether the other did.

Both halves are said or neither is; half a pair is refused, since storage taken from one heap and
given back to another is the one outcome worse than not building. The project's own manifest may
declare a pair too, which covers an application with its own arena and no dependency that has one.

**Whichever road the package arrived by.** A package named in `dependencies` and the same package
handed over as a `--lib` source root declare the same thing and settle the same question — this is a
property of the package, not of the flag that reached it.

### The compiled standard module is built for one allocator

The [standard module's compiled half](/reference/modules/#separate-compilation) is object code, and
it calls the pair by name. So the compiler's cache keeps one for each pair exactly as it keeps one for
each machine — the allocator's two symbols are in the cache key beside the target and the
optimization level — and a program that allocates another way is linked against a standard module
compiled for its own heap rather than handed one that frees into somebody else's. A library reaches a
build as source, so it is compiled against the program's pair along with everything else.

The standard module is under the same rule and needs nothing done about it — its cache is keyed by the
pair among the other things it is keyed by, so a program that names an allocator gets a standard
module built for that allocator, built on demand and announced on stderr.

### A freestanding target has nowhere to take the default from

Declaring nothing gets libc's `malloc` and `free`, which is the right default everywhere there is a
libc. A freestanding target has none — so a package that `requires { heap = true }`, built for one by
a command that links, is asking for two symbols nothing will define:

```
this package requires a heap and 'wasm32-freestanding' is freestanding, so nothing supplies
'malloc' and 'free' — the link would answer with an undefined symbol for each. Name a heap of
your own in package.hocon's 'allocator' block, or build for a target that has a libc
```

The `allocator` block above is the way out, and it is the whole of the way out: naming libc's own pair
explicitly is refused by the same rule, because writing `malloc` down does not make a machine that has
one.

**This is not the capability check, and cannot be.** `requires` is answered against what the *project*
says a target provides, and a capability the manifest does not mention is provided — the prior is that
a machine can do everything, and what a manifest records is what a machine cannot do. Whether an
allocator exists is exactly the kind of engineering decision that prior is right about: a Cortex-M
program linked against newlib has `malloc`, one linked against nothing has not, and the target cannot
tell you which. So what is asked here is not what the machine has. It is **who is doing the link**.

That is why `build-c` and `build-lib` are untouched. They write an archive for somebody else's build
system to link, and the allocator arrives from a `CMakeLists.txt` the compiler never sees — which is
how every board project works. Only where sysl invokes the linker itself is there nothing left to
supply the pair.

### What it does not do

Naming the pair says which functions the program uses. It says nothing about **where** the program may
use them, and that distinction matters on a real RTOS: `pvPortMalloc` suspends the scheduler and is
not usable from an interrupt handler, while sysl allocates implicitly — a string operation, a growing
buffer, a box. Code reachable from a handler is the caller's to bound, and `@no_alloc` is how it is
bounded.

## Headers a package needs and does not carry

A capability is answered by the target, and there is nothing for anybody to go and do. **A header is
answered by a path on a machine the package has never seen**, and that is the other thing a package
may need of its environment.

Most bindings carry the C they include — sqlite3, qcbor, monocypher and termbox2 all vendor their
library's source, so a relative include resolves and no flag is involved. A package binding something
the *consumer's* build system owns cannot do that. `pico2` is the case: lwIP's headers live in an
81 MB pico-sdk clone that only the consuming project has, and vendoring a copy would be vendoring the
thing the package exists not to reimplement.

```hocon
requires {
  headers { lwip = "lwIP's headers — the pico-sdk carries them at lib/lwip/src/include" }
}
```

The consumer says where they are:

```
sysl build . --include-path lwip=$PICO_SDK_PATH/lib/lwip/src/include
```

**The package names the requirement and the driver supplies the path.** That is the same split as
[`@link("png")` and `--link-path`](/reference/ffi/#where-the-library-is-where-its-headers-are-and-what-they-are-configured-with):
a path in a committed file would be one machine's directory layout published as though it were a
property of the package.

### A requirement may name the variable its path conventionally lives in

Some libraries have an agreed name for where they are installed. The pico-sdk's is `PICO_SDK_PATH` —
its own CMake reads it, and every project that uses it sets it. Where such a name exists the package
may say so, and then a consumer who has it set needs no flag at all:

```hocon
requires {
  headers {
    pico_sdk = { note = "the pico-sdk's headers", env = "PICO_SDK_PATH" }
  }
}
```

**A variable's *name* is not a path.** `PICO_SDK_PATH` is the same string on every machine in the
world, so it is a property of the library's ecosystem rather than of whoever is building — which is
exactly the test the paragraph above applies, and it passes it where a directory does not.

**A flag still wins, and an unset variable still refuses.** The variable is consulted only where
nothing on the command line named that requirement, so it can turn a failure into a success and can
never turn one success into a different one — and `--include-path` remains the way to build against
something other than what the variable points at. An empty variable counts as unset.

The `note` stays required, because it is what a consumer *without* the variable is shown, and the
refusal names both:

```
this project needs the 'pico_sdk' headers and nothing supplied them — the pico-sdk's headers.
Set PICO_SDK_PATH, which is where this package's own ecosystem looks for it, or say where they
are with '--include-path pico_sdk=<dir>'
```

**Most requirements have no such variable and take the bare string**, which means exactly what it
always meant. `FreeRTOSConfig.h` is the application's own file and no convention names it; there is
no agreed variable for lwIP either.

**The value is the reason, not a path.** It is prose for a person — what the headers are and where
they come from — quoted back at whoever has to find them. A name on its own would report that
something called `lwip` is missing and leave the reader to work out what that is.

### What it buys is the refusal

`--include-path` always worked, and a consumer who passed it always built. What did not exist was any
way for the *package* to say it needed one, so a build without the flag failed inside a C compiler
that names the header and knows nothing about sysl, the package, or the flag:

```
fatal error: 'lwip/tcp.h' file not found
```

Now the build stops before clang runs, naming all three:

```
github.com/sysl-lang/pico2 needs the 'lwip' headers and nothing supplied them — lwIP's headers,
the pico-sdk carries them at lib/lwip/src/include. Say where they are with
'--include-path lwip=<dir>'
```

A **bare** `--include-path` is not an answer, deliberately. The check is about what a build says it
has rather than what it might happen to find; reading a bare path as an answer would let a consumer
satisfy the requirement by accident and never learn they had.

It is asked only where C is actually compiled, so `emit-llvm` and `prove` are not held up by a path
they would never open.

**`build-lib` compiles C, so it is asked too — and it is asked for its own manifest and nothing
else.** That is the narrowest scope of any command here, and it follows from the same rule rather
than being an exception to it: `build-lib` compiles the C of the tree it was handed and no other, so
a `--lib` source root's declaration is not charged to a library built against it. That build never
opens the root's header, and the root is asked for it when the root is built itself.

This is the road a package is *packaged* by, so it is the one that matters most to whoever is
publishing one — and it was the last to be asked. Until it was, building a declaring package into an
artifact answered with `fatal error: 'lwip/tcp.h' file not found` out of the package's own shim, and,
worse, a **bare** `--include-path` satisfied the requirement in effect, because nothing was asking.
A requirement that can be met by accident on the machine that built the artifact and nowhere else is
exactly what the paragraph above exists to prevent.

### It is asked whichever way the package arrived

A package reaches a build by two roads, and the declaration is worth the same on each:

| how the package arrived | what happens |
|---|---|
| named in `dependencies` | its manifest comes with the graph, and the requirement is asked |
| given as a `--lib` source root | its manifest is read for this, and the requirement is asked |

A library is always handed over as source, so there is no third road on which its C would arrive
already compiled and its headers could be skipped.

**The header requirements, the allocator and the `dependencies` are read from a `--lib` root.** That
flag names a *source root*, which need not be a package at all, and one that is not has nothing to
declare — so a root with no `package.hocon` goes on building exactly as it always did.

All three are read for one reason: each is a property of the *package* rather than of the road the
package arrived by. A directory handed over with `--lib` is the same package as one named by a
coordinate, so it brings its heap, its header requirements and what it is written against either way.
Where a manifest names that coordinate as well, the directory is the copy that is built and the
coordinate is never fetched ([§ A source root stands in for the package it is](#a-source-root-stands-in-for-the-package-it-is)).

The allocator used to be read only from a coordinate, and the two roads then disagreed **in silence**:
the package's own objects came out of its heap and every string, `Buf` and box in the same program out
of libc's, with nothing said at any point. A silent mixed heap is worse than a rule somebody has to
know, which is what decided it.

The `dependencies` were the last of the three. Read only by coordinate, the same directory gave a
build the package's sysl and nothing it was written against, and what came back was a page of
unresolved names pointing into a package you did not write — naming neither the missing dependency nor
a flag. They are fetched now, into the **same** graph the project's own go through, so version
selection sees every claim at once and a package two roots share is one copy at one version. What a
root's manifest binds is reachable from your own files as well, which is no more true of a
dependency's name than it always was of the root's own modules.

`build-lib` is the one command that refuses them instead, and that is not an inconsistency: it
compiles one tree into an artifact for one machine and does not reach the network, so it has nothing
to fetch **with**. It says so, and names `--lib` as what to write instead.

What is still not read is the rest of the manifest. A root's capabilities are the program's to state,
and they have none of the one-answer-per-program character that makes the allocator settle for
everybody.

## A library the machine already has, found by asking it

A `headers` requirement puts the path in your hands, which is right when the headers belong to your
build — lwIP's live in your pico-sdk clone and nothing else could know where that is. **It is more
than is needed for an ordinary installed library**, because most of them answer the question
themselves. `pkg-config` is how: a `.pc` file installed beside the library says where its headers are
and what its link line is, on this machine.

```hocon
requires {
  pkg_config { sdl3 = "SDL3 — brew install sdl3, or Debian's libsdl3-dev" }
}
```

```
sysl run .
```

That is the whole command. Before this, the same program wanted the layout of your machine typed out —
and typed out correctly, which for the box2d demo meant knowing that cairo's headers are in
`include/cairo` while SDL3's want the directory *above* `SDL3`:

```
sysl run . --link-path /opt/homebrew/lib \
           --include-path cairo=/opt/homebrew/include/cairo \
           --include-path sdl3=/opt/homebrew/include
```

**The split is the same one as before: the package names the requirement and something else supplies
the path.** What changed is who that something else is — the machine, rather than a person copying its
layout onto a command line. Nothing the package wrote is a path, and no code the package supplied is
run; the compiler asks a well-known tool a question, exactly as it already asks clang what a
[`c const`](/reference/ffi/) measures to.

**One declaration answers both halves.** A package binding an installed library needs its headers to
compile *and* its library to link, and having one of those is not a build. `--cflags` feeds every C
compilation in the tree and `--libs` feeds the link line — including the `-Wl,-rpath` that decides
whether a dynamically-linked program finds its library at **run** time.

**A hand-written `--link-path` used to leave that last part out, and no longer does.** Until 0.0.104
it emitted `-L` and nothing else, so a library whose install name is `@rpath/…` — what CMake writes
for any library given a `SOVERSION` — linked cleanly and then failed to start, naming a library
sitting exactly where the flag had said it was. It now emits the run-time search path as well. What
`pkg-config` still supplies that a typed path cannot is the rest of the answer: which library, what
its headers need, and the exact rpath *that library* asked for rather than a guess made on its
behalf.

### The name is the one pkg-config files it under

It cannot be derived, and the two things it might have been derived from are both wrong. The `@link`
directive is one: the sdl3 package writes `@link("SDL3")` and the module is `sdl3`, because `-lSDL3`
and `sdl3.pc` are two naming conventions that happen to share a word. A `headers` requirement's name is
the other, and worse: a name that happened to match some `.pc` file on your machine would satisfy a
requirement nobody answered — met by accident on the machine that built it and nowhere else.

**So it is not yours to choose, and what it may hold says so.** A `headers` name is one you invent, and
it is held to a plain word: letters, digits, `_` and `-`, starting with a letter. A `.pc` name may also
hold a `.` and a `+`, because a great many libraries file under one with a version in it — libyaml is
`yaml-0.1` on macOS, Debian and Arch alike, GLib is `glib-2.0`, GTK 3 is `gtk+-3.0`. A rule that
refused those would not be asking for a better name; it would be refusing to bind the library.

**A name with a `.` in it has to be quoted**, because an unquoted dot is HOCON's path separator and
`yaml-0.1 = "…"` would declare a `1` inside a `yaml-0`:

```hocon
requires {
  pkg_config { "yaml-0.1" = "libyaml — brew install libyaml, or Debian's libyaml-dev" }
}
```

What the name may **not** hold is a separator or an `=`, and that bound is the flag rather than
taste: the name is written back to you as `--include-path <name>=<dir>`, and one holding either
could not be told from the directory beside it.

### What happens when it cannot be answered

**Your own flags win and stop the probe.** `--include-path <name>=<dir>` answers this exactly as it
answers a header requirement, so a hermetic build, a hand-built prefix or a machine with a broken `.pc`
is never at the mercy of what happens to be installed.

**A build for another machine is not asked at all.** `pkg-config` answers for the machine it runs on,
and a cross build's headers and library are the target's. A freestanding program compiled against your
laptop's `/opt/homebrew` would link and be wrong somewhere you cannot see it, so a target that is not
this machine is refused rather than answered:

```
this project needs the 'sdl3' library and this is a build for 'thumbv7m-freestanding' rather than
for this machine, so there is nothing to ask where it is — SDL3 — brew install sdl3. Say where it
is with '--include-path sdl3=<dir>' and '--link-path <dir>'
```

**A machine without `pkg-config` is exactly where it was before**, with the refusal the previous
section describes plus a sentence naming what was looked for. The two failures are told apart, because
they send you to different places: `pkg-config` missing is one install away and says nothing about the
library, where a `pkg-config` that does not know the module means the library itself is not there.

macOS ships no `pkg-config` and the libraries do not bring one — `brew deps cairo` lists fifteen
packages and it is not among them — so `brew install sysl` installs it as a dependency of the compiler.

## Dependencies

A dependency is **a git repository and a version**. There is no registry, no account to create, and
no name to reserve.

```hocon
dependencies {
  json { git = "github.com/edadma/sysl-json", version = "1.4.0" }
}
```

The coordinate is cloned over HTTPS and the tag `v1.4.0` is what gets read. A `path` dependency names
a directory instead, for a package being developed alongside its consumer:

```hocon
dependencies {
  helper { path = "../helper" }
}
```

A third kind names **a member of the workspace the project is in**, by its `package.name`, and is
resolved to that member's directory ([workspaces](#workspaces)):

```hocon
dependencies {
  kairos { workspace = true }
}
```

`sysl build` fetches whatever the machine has not got, so adding a dependency is an edit to this file
and nothing else. Fetched packages are cached under the machine's cache directory and shared by every
project on it.

**A coordinate is identity, not a URL.** `https://` on the front is refused rather than stripped:
the coordinate is what a package's module names are derived from, so two spellings of one package
would link as two incompatible copies of it.

### The major version rides in the coordinate

From the second major version on, a breaking change makes a **new coordinate**:

```hocon
dependencies {
  json { git = "github.com/edadma/sysl-json/v2", version = "2.1.0" }
}
```

A module's name is part of every symbol it emits, so two versions of a module named `json` would emit
the same symbol names for different code. One version per module is where the linker puts things
whether or not anyone plans for it, and `/v2` is what planning for it looks like. `0.x` and `1.x`
ride in the bare path.

### Which version you get

The version chosen for a package is **the highest minimum anybody asked for** — not the newest that
exists:

```text
your project     depends on json 2.1.0
       json 2.1.0 depends on buf  1.2.0
       text 3.0.0 depends on buf  1.4.0
                                  ------
                       buf resolves to 1.4.0
```

Three things follow from that, and they are the reason for it:

- **Adding a dependency cannot silently upgrade an unrelated one.** The only versions in play are
  ones some manifest names.
- **Builds are reproducible without a lockfile**, because the selection is a pure function of the
  manifests.
- **Upgrading is an edit.** Nothing quietly walks everything forward; you raise a minimum here and
  the graph is recomputed.

The cost is the honest one: you do not automatically get the newest patch release.

**When your own version is the one that got raised, the build says so:**

```text
sysl: note: 'plutovg' is named at 0.2.0 and the build selected 0.2.1, which syslui asks for
```

A note and not a refusal, because the higher version is the right answer and the build is correct.
Selection is otherwise silent by design — it raises floors constantly, and a line for each would be a
wall of them about packages nobody typed. What is different here is that the version came from *your*
manifest: you wrote one number, the build used another, and nothing in the file you are reading says
so.

**Where the note does not fire and you want to know anyway, ask**:
[`sysl deps`](/getting-started/cli/#deps) prints the whole graph — every package, the version settled
on, and every claim that was made on it, including the ones that lost. That is the only place a
losing claim survives: selection keeps the maximum and forgets the rest, so once a build is over
there is nothing left to reconstruct it from.

### Dependencies a test alone needs

A package's own suite often reaches for something no consumer of it should have to install — a second
implementation to check answers against, a driver for a database, a fixture generator.
`dev_dependencies` is where those go, and it reads exactly as `dependencies` does:

```hocon
dependencies {
  parsing { git = "github.com/sysl-lang/parsing", version = "0.5.0" }
}

dev_dependencies {
  quickjs { git = "github.com/sysl-lang/quickjs", version = "0.1.0" }
}
```

The difference is entirely in **who resolves them**:

- `sysl test` on this project resolves them, exactly like an ordinary dependency;
- `sysl build`, `sysl build-lib` and `sysl build-c` do not — every other build drops the source that
  could name one before it analyzes anything, so fetching the package would compile something the
  compilation cannot refer to;
- **anything that depends on this package does not resolve them at all**, at any command. They are
  not fetched, not built, and not on the link line.

That last one is the point of the block. A test-only dependency declared in `dependencies` is a
package every consumer clones, builds, and — for a binding to a system library — has to have
installed before your package will build for them at all.

**A dev dependency may only be imported from a `@tests` file or a `@test` function**, which is the
same line the resolution is drawn along: those are what an ordinary build strips, so they are exactly
the source a consumer never compiles. An ordinary module importing one is refused:

```text
error: 'sh.sysl.quickjs' comes from 'sh.sysl.quickjs', which this project declares in
'dev_dependencies' — that is not fetched for anything depending on this project, so only a
'@tests' file or a '@test' function may import it. Move the import into the tests, or declare
the package in 'dependencies' if the library itself needs it
```

Without that check the mistake is invisible to the person making it: `sysl test` resolves the package,
the ordinary module importing it compiles, the suite passes, and every consumer is refused at a module
that was never fetched for them.

**A package may not be named by both blocks.** They say opposite things about one package — that a
consumer gets it, and that a consumer does not — and there is no reading under which both are meant,
so it is refused the way a `git` beside a `path` is.

[`sysl deps`](/getting-started/cli/#deps) lists them with the rest, marked `(dev)`, since what a
project takes is a property of its manifests rather than of any one build.

## Features

A package often has a part not every consumer wants — a server, a desktop window, a second codec —
and the cost of that part is a dependency somebody has to install. **A feature is a name for one of
those parts**, and what the name turns on is a list of optional dependencies and other features:

```hocon
features {
  default = [server]
  server  = [llhttp, nghttp2]
  desktop = [webview]
}

dependencies {
  llhttp  { git = "github.com/sysl-lang/llhttp",  version = "0.2.0", optional = true }
  nghttp2 { git = "github.com/sysl-lang/nghttp2", version = "0.3.0", optional = true }
  webview { git = "github.com/sysl-lang/webview", version = "0.1.0", optional = true }
}
```

`default` is the feature a build gets when nobody asks for anything else. It is an ordinary entry
rather than a key of its own — it names dependencies and other features exactly as `desktop` does —
and a manifest that declares no `default` simply starts with nothing on.

A feature may share its name with the dependency it turns on — `lmdb = [lmdb]` is the ordinary way
to spell "the `lmdb` feature takes the `lmdb` package". **Inside a feature's list, a bare member
names the feature it spells, whenever a feature of that name is declared** — Cargo's rule — so
`default = [lmdb]` turns on the `lmdb` *feature*, not the dependency directly. The one exception is a
self-reference: inside `lmdb`'s own list, the `lmdb` in `lmdb = [lmdb]` is the one spelling that
means the dependency, since a feature cannot name itself. To name a dependency unambiguously anywhere
else, quote it as `"dep:lmdb"` — HOCON reads a bare colon as a separator, so the quotes are required.

### An optional dependency nobody turns on is not in the build

`optional = true` says an entry is taken only where some enabled feature names its label, and the
pruning happens before anything is fetched. So a dependency nothing turned on is **not cloned, not
asked for the libraries and headers its own manifest requires, and not on the link line**.
[`sysl deps`](/getting-started/cli/#deps) prints the graph the build resolved, which is where that is
visible:

```text
sysl deps .
```

```
app 0.1.0

helper  ../helper
```

Turning off the feature that named it leaves nothing behind:

```text
sysl deps . --no-default-features
```

```
app 0.1.0

this project depends on nothing
```

That is what the whole block is for. A binding whose `pkg_config` requirement would stop a build on a
machine that has not installed the library costs nothing at all to a consumer who never asked for it,
and the check that would have refused is never reached rather than being suppressed.

### Gating source on a feature

An enabled feature is a symbol the [`#if`](/reference/attributes/#if-gating-lines-before-the-lexer)
directive reads, named by putting `feature_` in front of it:

```text
#if feature_server
serve(port: int) -> Result[unit, Error] = listen(port)
#endif
```

The prefix is what keeps the two vocabularies apart. What a target says about itself is the
compiler's vocabulary and the set is closed; a feature's name is whatever a manifest chose, so a
package declaring a feature called `linux` would otherwise gate on a word that already means
something else. Writing the bare name says exactly that:

```
'server' is not something a target says about itself — sysl knows aarch64, android, bsd, craft,
freestanding, hosted, linux, macos, posix, riscv32, riscv64, thumb, wasi, wasm32, windows, x86,
x86_64. A feature this package's manifest declares is named 'feature_server'
```

**A file is gated against its own package's features and nobody else's.** The root project's
`feature_server` is invisible in a dependency's source and a dependency's is invisible in the root's,
because a feature exists only because a manifest said so, and the manifest that says so is the one
beside the file.

**And `feature_x` where no `x` is declared is false rather than refused**, which is the same answer a
declared feature nobody turned on gives. This is the one place the closed-set rule the `#if` chapter
states does not reach: a condition naming a feature is asking a question about a manifest, and *no* is
an answer to it. A program stays readable while a feature is being added, and a file copied between
packages gates rather than stops.

### What a consumer asks for

A dependency entry says which of that package's features this project wants:

```hocon
dependencies {
  webview { git = "github.com/sysl-lang/webview", version = "0.1.0", features = [desktop] }
  json    { git = "github.com/edadma/sysl-json",  version = "1.4.0", default_features = false }
}
```

`features` names the ones it wants; `default_features = false` leaves that package's `default` off,
which is how a consumer takes a package's core and none of what it ships enabled. An entry that says
neither gets the package its author meant to ship, and that is why `default_features` is true where
nothing writes it.

Those two keys are claims about the package being *depended on*, where `optional` is a claim about the
package being described. A feature the named package does not declare is refused, and the refusal
names who asked for it:

```
this project asks 'helper' for the feature 'fancy', which 'helper' does not declare — a consumer
may only ask for a feature that package's own manifest names, and it declares none
```

### Every request is added together, and a feature only ever adds

A package reached by two consumers gets the features **both** of them asked for. Each is compiled
against a surface it believes is there and neither can be told it was wrong, so unification is a
**union** — and `default` survives unless *every* consumer turned it off, since one consumer saying
what it does not need is not a decision about a sibling that took the package the ordinary way.

**So a feature is additive by contract: enabling one may add declarations, and may never remove or
change one.** Two projects in one build share a package, and the copy they share is the union of what
they asked for — so a feature that removed a declaration, or gave one a different meaning, would break
whichever of them did not ask for it. A feature that has to take something away is a second package.

Enabling a feature can pull an optional dependency into the graph, that dependency is a consumer of
its own with requests of its own, and those can enable a feature somewhere the first pass had already
settled. The resolution therefore runs again against the answer the round before it produced, until a
round changes nothing. Every round can only add, so it climbs to an answer rather than circling; a
graph that somehow did not settle is refused rather than built against whichever round it stopped at.

### Choosing them on the command line

Three flags, and [`run`, `build`, `build-c`, `test`, `deps` and
`vendor`](/getting-started/cli/#the-feature-flags) take them:

| | |
|---|---|
| `--features <a,b>` | turn these on, beside `default`; comma-separated, and may be given more than once |
| `--no-default-features` | leave `default` off |
| `--all-features` | turn on every feature this manifest declares |

They are about the **root project**. Which features a dependency gets is decided by the manifest that
depends on it, because a package's author is the one who knows which parts of it their own code needs,
so there is no spelling here that reaches past the project being built.

A name the manifest does not declare is refused rather than quietly selecting nothing, and the refusal
lists what is on offer:

```
this project has no feature 'quick' — a feature has to be declared in package.hocon's 'features'
block before it can be asked for. It declares 'default', 'extra'
```

**`sysl test` on the project itself enables every feature the manifest declares**, because gated code
that no build compiles is gated code nobody is testing:

```text
sysl test .
```

```
running 2 tests

/…/app/main.sysl
  ok    the_helper_greets       4ms
  ok    arithmetic_still_works  3ms

2 passed, 0 failed — 7ms
```

**Naming anything explicitly turns that off**, since a command line that asks for a configuration is
asking for that one rather than asking for the default to be widened:

```text
sysl test . --no-default-features
```

```
running 1 test

/…/app/main.sysl
  ok    arithmetic_still_works  3ms

1 passed, 0 failed — 3ms
```

### What the manifest refuses

Four things, and each names what to write instead. **A feature naming a dependency that is not
optional**, which reads as a feature that turns something on and is not one, the entry being taken
whatever anybody asks for:

```
package.hocon: 'features.greeting' names the dependency 'helper', which is not optional — a
feature turns an optional dependency on, and this one is taken whatever is asked for. Write
'optional = true' in that dependency's entry, or drop it from the feature
```

**An optional dependency no feature names**, which nothing could ever reach:

```
package.hocon: 'helper' is an optional dependency no feature names — an optional dependency is
reached only by the feature that turns it on, so nothing would ever build it. Name it in a
'features' entry, or drop its 'optional = true'
```

**A member that is neither a dependency of this package nor a feature of it**, which is what a
misspelling looks like from here:

```
package.hocon: 'features.server' names 'llhttp', which is neither a dependency of this package nor
a feature of it — a feature turns on things this manifest declares, so 'llhttp' would select
nothing
```

**Features that turn each other on**, since following the implications has to reach an end:

```
package.hocon: the features here turn each other on — 'server' turns on 'tls', which turns on
'server'. A feature may imply another, but following the implications has to reach an end
```

All four are read when the manifest is, so they stop a build before anything is fetched — which is
the same point as the pruning: what a feature decides is decided before the network is touched.

## What a dependency's modules are called

A package is a tree of modules, and **its modules come in under their own names**. A module is a
directory of source files, so a package holding `sqlite/` is reached exactly as its own documentation
shows it:

```text
sqlite.open("db.sqlite")
```

The name is the *module's*, not the package's — sqlite3's package is called `sqlite3` and its module
is `sqlite`, and reaching the second does not mean saying the first.

**A name is a module path, not a first segment.** Every package published under `sysl-lang` puts its
source under a reverse-DNS prefix, so what it offers is a dotted path:

```text
sh/sysl/table/table.sysl       →  sh.sysl.table
```

`sh/` and `sh/sysl/` hold no source, so neither is a module and neither is a name that package
offers. Two packages laid out this way therefore do not collide, which is the point of the
convention: a project may depend on `sqlite3`, `linenoise` and `table` at once and import all three
under the names their own documentation shows.

A binding covers the module it names and everything below it, so `sh.sysl.table.Style` reaches the
same package and keeps its tail.

### Local names are a decision, and the alternative is a real one

**Nothing in an import line names a coordinate**, and that is chosen rather than incidental. The
alternative — modules qualified by where the package came from, as Go's import paths and Java's
reverse-DNS packages both do — is a serious design and not a strawman: it makes a collision
impossible by construction rather than by refusal.

What it costs is that the coordinate then appears in **every import line in every file**, so
moving a package, forking it, or vendoring it edits every consumer's source instead of one line of
its manifest. sysl takes the half of that design which pays and leaves the half that does not: the
reverse-DNS *layout* convention above is what keeps `sh.sysl.table` and `sh.sysl.sdl3` apart, and it
costs nothing at the import, because the prefix is the module's own name rather than an address.

**What makes a local name safe is the rest of this section.** A collision is refused rather than
resolved silently; a nearer name wins over a name nobody asked for; and `mount` renames a package
for the one consumer that needs it. Those are what a global scheme would have bought, obtained a
different way.

**The cost of the choice is the transitive import**, stated where it arises above: a program may
import through a package that never promised to keep depending on what it depends on. That is the
price of not writing addresses down, and it is the one being paid deliberately.

### Imports are transitive

**A package reached through another is importable too.** Naming one dependency brings its own
dependencies with it, and theirs, however far down the graph they are — so a manifest names what a
project *takes* rather than everything it can see:

```hocon
dependencies {
  syslui-sdl { git = "github.com/sysl-lang/syslui-sdl", version = "0.1.0" }
}
```

is enough to `import sh.sysl.ui`, `import sh.sysl.plutovg` and `import sh.sysl.sdl3`, because the
driver depends on all three.

The reason is that **a package's public surface is made of its dependencies' types**. `syslui-sdl`
hands out a `&Fn() -> &View` and `View` belongs to the toolkit it is built on, so a program that
could not name the toolkit could not call the one function that package exists for. Declaring it
anyway is a line that says nothing the build could not work out.

**Three levels of precedence:**

1. your own modules, and every `--lib` source root's;
2. what your manifest declared;
3. what arrived through something else.

A nearer name wins over one that **arrived through something else**, quietly — a project with its own
`json/`, or a dependency it mounted as `json`, keeps that name however many packages further down
offer one. A name nobody asked for never takes one somebody wrote, and refusing there would mean a
package you have never heard of could break your own module names.

**What the levels do not do is let a nearer name beat one your manifest declared.** A name of your
own and a declared dependency's are both names somebody wrote, so the two meeting is the collision
below rather than a win for the first level. The one way a source root replaces a declared
dependency is by **being** it, which is the override further down.

**Two packages at the *same* level wanting one name is the collision below**, and it is refused
whether they were declared or inherited. Naming one of them yourself is what settles an inherited
pair, since a declared name beats an inherited one.

**A `mount` does not travel.** It is a name its writer chose for their own import lines, so what an
inherited package offers is what its own documentation shows.

The cost is stated rather than hidden: a program may import through a package that never promised to
keep depending on what it depends on, so a library dropping one of its own dependencies can break a
consumer that never named it. Every language with a class path has this, and the ceremony of the
alternative is what people actually complain about.

**Two packages cannot quietly share a name.** If two dependencies both offer a `json`, or one offers
a `json` and your own project has a `json/` directory of source, the build stops and says so rather
than picking one. So does one offering a path *inside* another's — a package offering `sh.sysl` and
one offering `sh.sysl.table` share no name, but an import of `sh.sysl.table` could be read as either,
and resolving it to the longer would be a rule nobody wrote down.

**"Your own modules" includes every `--lib` source root**, since a root's modules are filed under your
project's names rather than under a prefix of their own. So a root holding a module that a dependency
also offers — yours or the root's own — is refused in the same words, and the message names the root
as you gave it. Without that, the root's module answered, the dependency's was unreachable, and the
build was green — the silent winner the whole rule exists to refuse.

**Unless the root is that dependency's package** ([below](#a-source-root-stands-in-for-the-package-it-is)): then it is not a second
claim on the name but the package itself, handed over from a directory instead of a coordinate.

Write a `mount` to say what one of them is called here:

```hocon
dependencies {
  theirs { git = "github.com/edadma/sysl-json", version = "1.4.0", mount = "ejson" }
}
```

which hangs that whole package under one segment, so its `json` is `ejson.json` and your own `json`
is untouched. A mount is yours alone: another project may mount the same package differently, and
both still link one copy of it.

**Two major versions of one library are named as such**, because that collision reads very
differently from an ordinary one:

```text
'json' and 'json' cannot both be imported — github.com.e.json and github.com.e.json.v2 are two major
versions of one library and both are in this graph, and their modules have the same names
```

Selection cannot fold those together — a major above the first is a different coordinate, which is
the whole point of the suffix — while their module names are identical, because a module's name is
its directory. A `mount` is still the answer where you genuinely want both.

### A source root stands in for the package it is

**A `--lib` root that is a coordinate's package replaces that coordinate in the build.** It is the
loop for working on a dependency and the program that uses it at once — Cargo's `[patch]`, Go's
`replace` — and it needs no edit to the manifest:

```text
sysl build . --lib ../geom
```

over a project declaring `g { git = "github.com/e/geom", version = "1.0.0" }` compiles the checkout
in `../geom`, not the published 1.0.0.

**The coordinate is dropped before anything is selected, so nothing is fetched for it.** A package
that has not been published yet, a tag that does not exist, or no network at all does not stop the
build — the coordinate is never reached. Nothing is recorded in `sysl.sum` for it either, since
nothing was fetched to record.

**It wins wherever in the graph the coordinate is named.** A dependency that itself depends on
`github.com/e/geom` is built against the root too, so the program holds one `geom` — the one you
named — rather than your import reading the working copy and the dependency's reading the release.
What the root's own manifest depends on comes with it, as the coordinate's would have.

**A root is that package when its `package.name` is the coordinate's repository name** — the last
segment of the path, with a major-version suffix set aside, so a root naming itself `json` stands in
for `github.com/e/json` and for `github.com/e/json/v2` alike. That is the one identity both sides
state without anything being fetched: a coordinate names a repository, and a checkout of it — a
clone, a worktree, a fork — carries that repository's manifest. Comparing modules would need the
coordinate's tree, which is the fetch an override exists to avoid; and a dependency's label is a name
each consumer chooses for itself, so two manifests may spell one package two ways.

**Overriding is not the silent winner the collision rule refuses**, because nothing about it is
silent: `--lib` is something you typed, naming the directory that wins. A root that is **not** the
package — one that merely holds a module the coordinate also offers — is still refused, and the
refusal says what would have made it an override:

```text
'geom' is both a module of the source root '../shapes' and one github.com.e.geom offers — give the
dependency a 'mount' to say what it is called here. A source root stands in for a coordinate only when
it is that package — when its package.hocon names it 'geom'
```

A `mount` on the stood-in dependency still renames it for the manifest that wrote one.

## `sysl.sum`

`sysl.sum` sits beside `package.hocon` and **should be committed**. It records a content hash for
each package and version the project resolved, and a fetch whose content does not match is refused:

```text
github.com/edadma/sysl-json v1.4.0 sha256:6f1b…
```

What it protects against is the class of change a version number cannot describe — a tag moved to
point at different commits, a repository rewritten, a mirror serving something other than what the
author published. In all three the version number is exactly what it was.

It is **not a lockfile**: version selection is already a function of the manifests, so there is
nothing to record about which versions were chosen. The first time a package is seen it is trusted
and recorded; reviewing the line that appears is the part a person does. A `path` dependency gets no
entry, because a directory beside you is expected to change.

**A workspace has one `sysl.sum`, at its root**, covering every member: a member's build reads and
writes that file, and a [workspace member](#workspaces) depended on by name gets no line, being a
directory of the same checkout.

**A build only ever adds a line.** Move a dependency from 0.1.0 to 0.3.0 and the 0.1.0 line stays,
alongside every version any earlier build resolved. A build does not prune, because it resolves only
the part of the graph its `--features` turn on: it would drop the lines of every feature it was not
asked for, and the next build with that feature on would write them back.

`sysl tidy` is what removes them. It resolves the project's whole graph — every feature the manifest
declares, and `dev_dependencies` as well as `dependencies` — and rewrites `sysl.sum` to exactly the
packages that resolution reads, saying what it dropped:

```text
sysl tidy
removed github.com/sysl-lang/skitter v0.1.0
removed github.com/sysl-lang/sdl3 v0.3.0
```

A line that stays is the line that was there, hash and position unchanged, so the diff it makes is
removals and nothing else. It fetches only what a build of the same graph would fetch, a package
with no line yet is recorded exactly as a build would record it, and a file that is already tidy is
left alone with nothing printed. A version that resolution reads on its way to a higher one keeps
its line, since every build reads it again. A project that depends on nothing, or only on paths, is
tidy with no `sysl.sum` at all, which is what a build leaves for it.

`sysl tidy --check` asks the same question and writes nothing. It fails, naming the lines it would
remove or add, when the file is not tidy, which makes it the check a CI job runs:

```text
sysl tidy --check
would remove github.com/sysl-lang/skitter v0.1.0
sysl: error: sysl.sum is not tidy — 'sysl tidy' rewrites it to what this project resolves to
```

It is refused where there is no `package.hocon`, since only a project with a manifest resolves
anything to record.

## Adding one, and vendoring the lot

A dependency is a line in `package.hocon` and you may write it yourself, but `sysl add` is what
settles the two things you would otherwise go and look up — how the coordinate is spelled and what
the newest tag is:

```text
sysl add github.com/sysl-lang/sdl3
sysl add github.com/sysl-lang/sdl3@0.3.1
```

The version comes from `git ls-remote --tags`, so it works for any host a build can clone from. The
manifest is rewritten one run of bytes at a time, which is why your comments and layout survive it,
and the result is read back before it is written — a rewrite that produced something unreadable
leaves the file exactly as it was. Nothing is fetched; the next build does that.

`sysl vendor` puts everything the project resolves to into a `vendor/` directory beside the manifest:

```text
sysl vendor .
```

**It is the machine's package cache moved into the project** — the same layout, the same resolution,
the same `sysl.sum`. A project that has a `vendor/` builds with the network off, and the directory
being there is the whole of what turns that on: nobody has to be told, and no flag has to be passed.
It is not part of the project's own source, exactly as [`examples/`](#a-package-may-carry-examples)
is not, so nothing in it is compiled as one of your modules.

Commit it where you want a build that an upstream disappearing cannot break; leave it out where you
would rather fetch. A `path` dependency is not vendored and cannot be — it is a directory you are
editing beside this one, which is why nothing keeps a sum for it either.

## Workspaces

**A workspace is several projects in one repository, resolved as one.** The root manifest lists
the members, and the list is the whole declaration:

```hocon
workspace = ["compiler", "tools/*", "examples/board-probe"]
```

Each entry is a directory relative to the file, and **each member is an ordinary project** with a
`package.hocon` of its own — built, tested and depended on exactly as it would be on its own. `*`
stands for one directory level and matches inside a name (`tools/*`, `tools/sysl-*`); a glob takes
the directories it reaches that hold a manifest and passes over the rest, so `tools/*` beside a
directory of images is the ordinary case. Nothing fancier is read: no `**`, no `..`, no absolute
path.

What the workspace adds is three things, and nothing else:

- **one version selection over every member's dependencies**, so no two members can be built against
  two versions of one package;
- **one `sysl.sum` and one `vendor/`, at the workspace root**;
- **members depending on each other by name**, with no fetch, no version and no `sysl.sum` line.

### The root may be a package too

A root manifest with a `package` block is a member itself — the first one — which is the shape of a
package that keeps a probe program or a starter beside it:

```hocon
package {
  name    = "kairos"
  version = "0.1.0"
}

workspace = ["examples/board-probe"]
```

A root with **no** `package` block compiles nothing of its own: it is a list and the files beside
it. It therefore takes no dependencies, since a dependency there would reach no code while a reader
believed every member had it:

```text
package.hocon: 'dependencies' in a workspace with no 'package' block reaches nothing — the root compiles nothing of its own, so name each dependency in the member that imports it
```

The list is a list and nothing else. A string, a block or a list of anything but directory names is
refused:

```text
package.hocon: 'workspace' is not a list of directories — a workspace is written 'workspace = ["compiler", "tools/*"]', each entry a member's directory relative to this file
```

### A directory holding its own manifest is another project

**A tree stops at any directory below its root that holds a `package.hocon`** — the rule Cargo keeps
for a nested `Cargo.toml`. It holds whether or not anything declares a workspace, because a nested
project is a different program either way:

```
kairos/package.hocon
kairos/sh/sysl/kairos/kairos.sysl            the package
kairos/probe/package.hocon                   another project: not part of the package's tree
kairos/probe/main.sysl
```

That one rule is what lets a repository hold more than one project, and it is also why a workspace
needs no second rule for its members: every member has a manifest, so the root's walk never swallows
one. The C walk stops at the same place. A member's own `examples/` and `vendor/` are left out of
its tree exactly as a root's are.

A project below a workspace root that the list does **not** name belongs to no workspace and builds
as it would anywhere else.

### Depending on another member

A member names another by its `package.name`, with `workspace = true`:

```hocon
dependencies {
  kairos { workspace = true }
}
```

That is resolved to the member's directory — nothing fetched, no version, no `sysl.sum` line — and
the member's own dependencies join the graph as a fetched package's would. `mount`, `optional` and
`features` mean what they mean on any other entry. A coordinate, a path or a version beside
`workspace = true` is refused, the workspace having already said where the member is:

```text
'dependencies.kairos' names 'workspace = true' and a 'git', 'path' or 'version' as well — a workspace member is the directory the workspace lists, at no version, so it is named by the workspace alone
```

**A member's modules are named by its `package.name`**, as a path dependency's are named by its
label: `kairos`'s `sh.sysl.kairos` is `kairos.sh.sysl.kairos` in every member that depends on it.
Two members may not share a name, so two members' modules never collide, and a member never meets
a fetched package of its name, whose modules carry the coordinate instead.

A member that is also published may say where, with `package.repository`, and is then named exactly
as a consumer fetching it names it ([what a dependency's modules are
called](#what-a-dependency-s-modules-are-called)): with `repository = "github.com/sysl-lang/kairos"`
its module is `github.com.sysl-lang.kairos.sh.sysl.kairos`, so moving it into or out of the workspace
renames none of its symbols. Nothing requires it; a workspace builds the same either way.

`package.repository` is written as a consumer writes `git`; a URL there is refused:

```text
workspace member 'geom' gives 'package.repository' as 'https://github.com/e/geom', which is not a coordinate — it is written as a consumer writes 'git', 'github.com/you/thing', since it is what the member's modules are named by
```

### One resolution

**Minimal Version Selection runs once, over every member's `dependencies` and `dev_dependencies`
together**, with every optional dependency read — the answer has to hold for any build of any member
with any features. Each member's own build then takes the versions the workspace settled on, so a
member asking for `buf` 1.2.0 beside one asking for 1.4.0 is built against 1.4.0 — and `sysl deps`
of the first lists the second member's claim beside its own, since that is the line explaining a
version it never asked for.

A member's build still compiles **only what it reaches**: the workspace's selection sets floors, it
does not hand a member another member's packages, so a library one member links is never on
another's link line.

**`sysl.sum` and `vendor/` live at the workspace root**, read and written from whichever member is
being built. `sysl tidy`, run anywhere in the workspace, rewrites that one file to what the whole
workspace resolves to.

### Commands

| command | at the workspace root | inside a member | `-p <member>` |
|---|---|---|---|
| `sysl build` | every member, root package first; `-o` refused beside more than one | that member | that member |
| `sysl test` | every member, each suite under `testing <name> (<dir>)`, then one `workspace:` line | that member | that member |
| `sysl run` | the root package; refused for a root with no `package` | that member | that member |
| `sysl deps` | every member's graph, one after another | that member | — |
| `sysl tidy` | the workspace's `sysl.sum` | the workspace's `sysl.sum` | — |
| `sysl add` | the root package; refused for a root with no `package` | that member's manifest | — |
| `emit-llvm`, `emit-typed`, `build-c`, `build-lib`, `emit-header` | the root package; refused for a root with no `package` | that member | — |

`-p` is also spelled `--package`, and without a path it means the workspace the working directory is
in. A member's build always resolves with the workspace — its versions, its `sysl.sum` — whichever
way it was reached.

```text
sysl test .
sysl test . -p kairos
sysl build -p board-probe
```

A failing member's suite fails the run and the others still run, unless `--fail-fast` was given:

```text
workspace: 3 members tested, 1 failed: probe
```

### What is refused

Every one of these is refused by any command that builds any part of the workspace, a member's
included — a list naming a directory that is not there is wrong whichever member is being built.

```text
workspace member 'nope' does not exist — '/…/repo/nope' is not a directory
workspace member 'bare' has no package.hocon — a member is a project, and a project is a directory holding one
'workspace' names '../x', which is not a directory below the workspace — a member is written relative to the file declaring the workspace, inside it, with '*' standing for one directory level
workspace member 'a/inner' is inside member 'a' — each member's tree is its own, so one member cannot hold another
workspace members 'a' and 'b' are both named 'twin' — a member is picked and depended on by its 'package.name', so each needs its own
workspace member 'anon' has no 'package.name' — a member is picked with '-p' and depended on by that name
workspace member 'a' declares a workspace of its own — workspaces do not nest, so its members belong in the root's list
'app' names 'ghost' as a workspace dependency, and no member of the workspace is called 'ghost' — the members are 'app', 'geom'
'geom' is a workspace dependency, and '/…/app' is in no workspace — 'workspace = true' names a member of the workspace this project is listed in
```

**Members depending on each other in a cycle** are refused, `dev_dependencies` counting, since a test
build compiles them in exactly as it does `dependencies`:

```text
workspace members depend on each other in a cycle: a -> b -> a — a member is compiled into the one that depends on it, so one of these edges has to go
```

From the command line:

```text
sysl: error: the workspace at '/…/repo' has no member called 'nope' — its members are 'a', 'b'
sysl: error: '-p a' picks a member of a workspace, and '/…/app' is in none
sysl: error: '-o' names one file, and this workspace builds 2 members — pick one with '-p <member>'
sysl: error: '/…/repo' is a workspace with no 'package' block, so it is no project of its own — point at a member's directory, or pick one with '-p <member>'
sysl: error: '/…/repo' is a workspace with no 'package' block, so it takes no dependencies — add it to the member that imports it, 'sysl add <coordinate> <member directory>'
```

## No build scripts, ever

**A package cannot run code at build time.** Not a hook, not a script, not a plugin. `sysl add` and
`sysl build` read and write files and run nothing.

Most of what other ecosystems need build scripts for is compiling vendored C, and sysl already
compiles a library's C declaratively — the linker inputs a package needs are `@link` attributes in
its source, not a program that computes them. What that buys is most of the supply-chain story: a
package that cannot execute during installation cannot exfiltrate anything during installation.

### What the vendored C is compiled with

A C library almost always has compile-time options, and they are the **package author's** decision
rather than the consumer's. Which of miniz's four `MINIZ_NO_*` switches are set is what makes that
package a caller-owned codec with no allocator in it; a program that depends on it has no more
business choosing them than choosing its warning flags.

A `defines` block says so. Each sub-block is a `.c` file **the package carries**, named relative to
the package root, and what follows is what that file is compiled with:

```hocon
defines {
  "sh/sysl/miniz/c/miniz.c" {
    MINIZ_NO_MALLOC       = true
    MINIZ_NO_STDIO        = true
    TDEFL_LESS_MEMORY     = 1
  }
}
```

`true` is a bare `-DNAME`, which is what an option tested with `#ifdef` wants. Any other scalar is
`-DNAME=value`, for one tested with `#if`. **`false` is refused**, because a reader would have to
guess between *do not define this* and `-DNAME=0` — and those differ under `#ifdef`. Whichever was
meant can be said exactly: leave the line out, or write `0`.

This is not a general flags channel. It reaches the C a package carries and nothing else: no include
paths, no warning flags, nothing for an installed library's headers. `--define` remains what a
*build* says and applies to every C compilation in it; a `defines` block is what a *package* says
and applies to the file it names. Where both name the same macro the package wins, since the whole
point is that its configuration is not the consumer's to change by accident.

### One key, several files

A key may name its files with braces, as a shell writes them:

```hocon
defines {
  "sh/sysl/miniz/c/{miniz,shim}.c" {
    MINIZ_NO_MALLOC   = true
    TDEFL_LESS_MEMORY = 1
  }
}
```

Several groups multiply out — `"{a,b}/{x,y}.c"` is four files — and expansion happens when the
manifest is read, so nothing further on sees anything but one path and its macros.

**The point is not brevity.** A package whose C shares a configuration would otherwise carry one copy
of the list per file, and every macro in such a list changes a struct's size or deletes a
declaration. Two copies that drift apart are precisely the silent skew this block exists to prevent,
and duplication is how drift starts.

**There is no `*`, and that is deliberate.** A wildcard picks up a `.c` added later without anybody
deciding — the same failure by another road, since the new file joins a set that changes struct
layouts. A brace still names every file it configures; it only says the shared part once.

Nesting (`{a,{b,c}}`) and empty alternatives (`{a,}`) are refused, both saying nothing a flat list
does not. So is a file configured from two blocks, which has no sensible merge — the later would
silently win.

### The path has to name C the package actually carries

A key that matches no carried file stops the build:

```
package.hocon: 'defines."sh/sysl/miniz/c/typo.c"' names a file this package does not carry —
the block configures the C the package itself holds, and there is no such C file in this tree
```

This is the one mistake a `defines` block can make that reading the manifest cannot catch. Every
other way of getting it wrong — a key that is not a `.c` file, a macro name the preprocessor would
not take, a `false` — is refused when the file is read. A path that is merely *wrong* would compile
perfectly, under the library's defaults, and only a `c const` measuring a configured struct would
ever notice.

It also catches a subtler case: a directory holding no sysl is not a module, so
[the C walk](/reference/ffi/) never collects its `.c` files and nothing compiles them. A block
configuring one is configuring nothing, and now says so.

The path is matched against the files the walk found rather than joined to the package root, which
is what makes the check possible at all — and is also what makes the block work when a package is
built from its own tree with `sysl test .`, where the root as typed and the path as walked are two
spellings of one directory.

### A `c const` block inherits from the C beside it

A `c const` block is measured by compiling a small program the compiler writes itself, so there is
no file in the package for a `defines` key to have named. It reads the headers under **the union of
what the carried C in its own directory is compiled with**.

That is not a convenience. Every option worth setting is one that changes a struct's size or deletes
a declaration, so a probe reading the defaults while the object beside it was built with the options
does not fail — it answers a *different number*:

```
sizeof(tdefl_compressor)     167800   what the object file holds
sizeof(tdefl_compressor)     319352   what a probe under the defaults measures
```

A package exporting the second while linking the first is wrong by 151,552 bytes, and nothing in the
build says a word. Inheriting from the directory is what makes the three translation units of an
ordinary binding — the implementation, the shim and the `c.sysl` — agree by construction.

Two C files in one directory compiled with different macros give their union, which is a shape to
avoid rather than to rely on: a probe cannot be measured under two disagreeing configurations at
once, and nothing can tell which of them the block meant.

### What it does not fix

An upstream header that defines its own option **unguarded** cannot be configured from outside at
all. miniz's does:

```c
#define TDEFL_LESS_MEMORY 0
```

Any definition made before including that header — from a `defines` block, from `--define`, from
anything — is overridden there, with a `macro redefined` warning and no other effect. A vendored
copy has to guard the line. That is one line of difference from upstream rather than a wrapper
header, but it is not nothing, and no mechanism here can remove it.
