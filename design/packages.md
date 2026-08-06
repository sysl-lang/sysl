# Packages

**Status:** §1–6 and §9 are **built**; §7 is a property of the design rather than code; §10–11 are
decided in outline and unbuilt. This is the project-config half of the doc `capabilities.md` promised
and `13 § Open a` tracks — what `package.hocon` says, and what it means to depend on somebody else's
code. `targets.md` already settled the other half, what a machine is.

**What exists today:** the file is found beside the sources, parsed (`PackageConfig`), and read for
identity, the active target, per-target capability sets and **dependencies**. A `sysl build` over a
project with a `dependencies` block fetches what it names, selects versions by MVS, checks what
arrived against `sysl.sum`, and compiles the result as more modules — which is what a `--lib` source
tree already was, so nothing downstream of resolution is new.

The capability half was the first thing the file was needed for: `capabilities.md`'s *target
provides* level had no way to be stated before, so every target offered everything. A target that
provides no allocator now makes every module of the program allocator-free with no clause written
anywhere, and a module's `requires` is answered against the machine.

**What is not built:** `sysl vendor` (§10) and the commands of §11 — there is no `sysl add`, `fetch`,
`vendor` or `deps` subcommand, and a dependency is added by editing `package.hocon`. §8's *report it
at add time* half waits on `sysl add` existing; the compiler's own enforcement of a package's
`requires` is there.

**Written ahead of need, deliberately, and that is worth knowing when reading it.**
`capabilities.md` advises designing only the minimum that unblocks the work at hand and letting real
needs drive versioning, resolution and publishing. This chapter goes further than that advice, on
the view that the decisions below were already taken and were living outside the repository. The
parts that are genuinely settled — the file, its format, one-version-per-module, the namespacing
rule — are marked as such; the schema details are a first cut and should be expected to move once
something real is built against them. **Where this chapter and a working implementation disagree,
the implementation is the thing to trust and this chapter is the thing to fix.**

## 1. One file, and a program may have none

A project's configuration and its package manifest are **one file, `package.hocon`, at the project
root**. It carries who this package is, what machines it is built for, what capabilities those
machines provide, and what it depends on.

One file rather than two is the precedent principle 2 points at, and it is nearly unanimous:
`package.json`, `Cargo.toml`, `pyproject.toml` and `build.sbt` each hold identity, build settings
and dependencies together. Splitting them is the arrangement that has to be justified, and the
justification would have to survive the fact that the two halves talk to each other constantly — a
dependency declares capabilities, and capabilities are per-target.

**The format is HOCON, and the reason is `cross-platform.md` rather than taste.** The compiler runs
on the JVM, on Scala Native and on Node, so anything the driver reads must parse identically on all
three. That is a real constraint and it disqualifies most of the field: the obvious alternatives
have one good parser on the JVM and nothing on the other two. sysl uses a pure-Scala HOCON
implementation with no `java.*` surface and no regular-expression engine, which is exactly the shape
`cross-platform.md` asks of anything in the edge layer. HOCON also has the property the file wants
most — it is JSON where you need to be exact and terse where you do not — and comments, which JSON
still refuses.

**The file is optional.** A single-file program has no `package.hocon`, and gets the defaults: the
project root is the directory the driver was given (`13 § Open a` settles that the driver takes a
root as the path it is handed), the target is the machine the compiler is running on, and the
capabilities are that target's. This is the same principle §1 of the modules chapter applies to the
anonymous root module — **the one-file case is not a special form, it is the general case with
nothing filled in** — and it is what keeps `sysl run hello.sysl` free of ceremony.

## 2. What the file says

```hocon
package {
  name    = "geom"
  version = "1.4.2"
}

targets {
  default = "aarch64-macos"

  aarch64-kernel {
    triple       = "aarch64-none-elf"
    capabilities { alloc = true, os = false, posix = false, threads = false }
  }
}

requires { os = true }

dependencies {
  json  { git = "github.com/edadma/sysl-json", version = "1.4.0" }
  regex { git = "github.com/edadma/sysl-regex", version = "0.4.0", mount = "re" }
  local { path = "../experiment" }
}
```

Four blocks, and each answers a question somebody already has:

| block | answers | who reads it |
|---|---|---|
| `package` | who this is, under what version | a consumer's resolver; nothing in a leaf program |
| `targets` | which machines, and what each provides | the driver, then `capabilities.md`'s checks |
| `requires` | what this package needs of its environment | a consumer, at `sysl add` and again at compile time |
| `dependencies` | what to fetch and what to call it | the resolver and the module-name resolution in §9 |

**`targets` extends the fixed registry rather than replacing it.** `targets.md` deliberately does
not carry capabilities, because a target's capabilities are exactly the part a project has an
opinion about. A named block whose name matches a registry entry adds capabilities to it; a block
with a `triple` of its own declares a machine the registry does not have. Everything else about a
target — ABI facts, `va_list` handling, whether floating registers carry arguments — stays in the
registry, where it is measured rather than configured. **A project may not overrule a measured
fact**, and that is the line: capabilities are policy, ABI is not.

## 3. Coordinates — git-addressed, no registry

A dependency is **a git repository and a version**. There is no registry, no account to create, and
no name to reserve.

```hocon
json { git = "github.com/edadma/sysl-json", version = "1.4.0" }
```

The coordinate resolves the way Go's does: `github.com/edadma/sysl-json` is cloned over HTTPS and
the tag `v1.4.0` is what gets read. A `path` dependency names a directory instead, for a package
being developed alongside its consumer.

**A coordinate is identity, not a URL, and the difference is checked.** `https://` on the front is
refused rather than stripped: the coordinate is what `§ 9` mangles module names out of, so two
spellings of one package would link as two incompatible copies of it, and the error would arrive at
the linker rather than at the line that caused it.

**Why no registry: a registry is a service, and a service is a thing that must not go down.** It
needs hosting, uptime, moderation, a name-squatting policy, an abuse contact and a story for what
happens when its maintainer loses interest. For a one-person language that is the largest single
piece of ongoing work in the whole proposal, and it buys convenience rather than capability. Go
demonstrated that decentralized fetching is sufficient; npm and crates.io demonstrate what the
alternative costs to run.

**The two costs, stated rather than glossed:** there is no central place to search for a package, so
discovery is somebody else's problem — a list in a README, for now. And a repository that
disappears takes its package with it, which is the failure a proxy exists to prevent. sysl's answer
to the second is vendoring (§7), which is a thing the project needs anyway.

**The module proxy and the checksum database are deliberately not here.** Both are services, and
both are optimizations for a language with more users than this one has. `sysl.sum` (§6) covers the
integrity half without anything to operate.

## 4. Versions — semver, and the major version rides in the path

Versions are semver, and **a breaking change makes a new coordinate**:

```hocon
json { git = "github.com/edadma/sysl-json/v2", version = "2.1.0" }
```

Go's `/v2` convention is widely disliked and sysl copies it anyway, because **the constraint that
forced it on Go binds sysl harder than it binds Go.** `15 § 2` fixes that symbol names carry the
module path: a `Money` in module `demo` links as `demo$Money.lt`. So two versions of a module named
`json` do not merely confuse a reader — they emit *the same symbol names* for different code, and
they present two incompatible notions of one type to anything holding both. One-version-per-module
is not a policy sysl adopts; it is where the linker puts sysl whether or not anyone plans for it.

`/v2` is what planning for it looks like: a major version becomes a different package, so two of
them coexist the way any two packages do, and MVS never has to choose between them.

**The criticism is fair and the alternative is worse.** Putting a version in a path is redundant
with the `version` field beside it, it makes URLs ugly, and it is viral through every consumer's
manifest. What it replaces is a resolver that must decide which of two incompatible majors wins, in
a language where losing that decision produces a link error rather than a diagnostic.

## 5. Resolution — Minimal Version Selection

Given the graph of manifests, the version chosen for each package is **the highest minimum anybody
asked for** — not the newest that exists.

```
your project     depends on json 2.1.0
       json 2.1.0 depends on buf  1.2.0
       text 3.0.0 depends on buf  1.4.0
                                  ------
                       buf resolves to 1.4.0     (the highest minimum, not the latest)
```

**MVS is chosen over a solver for the same reason the registry is skipped: it is a few hundred lines
and it terminates.** Cargo's resolver is a genuinely hard piece of software, and the language it
serves has people whose job is to maintain it. MVS is a walk of the graph taking a maximum, and its
properties fall out of that rather than out of clever search:

- **Adding a dependency cannot silently upgrade an unrelated one.** The only versions in play are
  ones some manifest names, so nothing moves that nobody asked to move.
- **Builds are reproducible without a lockfile**, because the selection is a pure function of the
  manifests reachable from this one. A lockfile records what a search happened to find; there is no
  search here to record.
- **Upgrading is an edit.** There is no `update` that quietly walks everything forward — you raise a
  minimum in `package.hocon` and the graph is recomputed.

The cost is the honest one: you do not automatically get the newest patch release. That is the same
trade Go made, and the same complaint Go gets.

## 6. Integrity — `sysl.sum`

`sysl.sum` sits beside `package.hocon`, is committed, and records a content hash per resolved
package and version. A fetch whose content does not match is refused.

**The hash is SHA-256 over a canonical listing** — one `<digest>  <relative path>` line per file,
sorted by path — which is the shape Go's `h1:` takes and is chosen for the same reason: it is a total
order over content that no filesystem detail can perturb. `.git` is excluded anywhere in the tree,
since two clones of one commit differ there and a hash including it would depend on how a package was
fetched rather than on what was fetched. The digest itself is computed by the platform's own utility
(`shasum`, `sha256sum`), which is the arrangement the compiler already has with `clang` and
`llvm-ar` — but the *definition* is independent of it, so replacing the shell-out with an
implementation in Scala changes nothing that has been committed.

**The cache is shared by every project on the machine**, and that is what makes the check more than
a formality: the hash computed when a package was written is recorded in a **sibling** of its
directory, so a project whose `sysl.sum` covers a package that some *other* project fetched first is
still checked, and is checked without walking the tree again. A sibling rather than a file inside,
because anything inside would be part of what the tree hash covers and a hash cannot cover itself.
A cached directory with nothing recording what it hashed to is refused rather than trusted — it is
an interrupted fetch or something a person put there, and it is not evidence about anything.

**A `path` dependency has no entry and wants none.** A directory beside the consumer is expected to
change; that is what it is for.

**It is not a lockfile and it is worth being clear about the difference.** MVS has already made the
*version* selection deterministic, so nothing needs to record which versions were chosen. What
`sysl.sum` pins is *content* — the case where a tag is moved, a repository is rewritten, or a mirror
serves something other than what the author published. Those are the attacks a version number cannot
describe.

## 7. No build scripts, ever

**A package cannot run code at build time. Not a hook, not a script, not a plugin.**

This is the single most valuable property in the chapter and it is available almost for free,
because the reason other ecosystems need build scripts mostly does not apply. Cargo's `build.rs`
exists in large part to compile vendored C, and **sysl already compiles a package's C
declaratively**: `15 § 7` establishes that any source tree may carry C inside its module tree, and
the regex and SQLite packages both do exactly that. The linker inputs a package needs are
`link` directives in the source (`13 §`), not a program that computes them.

**A dependency is consumed as source, so this is a claim about the source path and not only about
`build-lib`.** A fetched package's `.c` is compiled for the build's target and its object goes on
that build's link line, exactly as its `.sysl` becomes more of that build's modules; a `@link`
directive in its header reaches the same command line, which is what lets a consumer link against
SQLite without ever writing `-lsqlite3`. Neither is optional to the argument above — a package
system that dropped a package's C would need build scripts again the first time somebody bound a
real library.

What that buys is most of the supply-chain story: **a package that cannot execute during
installation cannot exfiltrate anything during installation.** `sysl add` reads and writes files and
runs nothing. The npm and PyPI incident histories are largely a history of install-time execution,
and the whole class is absent rather than mitigated.

**This is a property that can only be kept by never breaking it once.** The pressure will arrive as
a specific, reasonable request — generate a binding table, embed a build stamp, detect a system
library. The answer is to extend the schema so the compiler does it declaratively, or to say no; it
is never to open a shell, because the first exception makes every later audit conditional.

## 8. Capabilities travel with the package

A package declares what it needs of its environment, in the vocabulary `capabilities.md` already
defines:

```hocon
requires { os = true, posix = true }
```

Two things read it, and the second is what makes it more than documentation:

- **`sysl add` reports it before anything is installed**, so *this package wants `os`* is something
  you learn while choosing it rather than while debugging a build.
- **The compiler enforces it**, exactly as it enforces a module's own clause. A `no alloc` project
  that reaches a package requiring `alloc` is refused at compile time, at the reference, with the
  diagnostic landing on the line a reader has to change.

This is sysl's one clear addition over Go's model, and it costs almost nothing because the gating
machinery exists — `Capabilities.scala`, `GatedModules.scala`, and the propagation rules in
`capabilities.md § Two levels`. What is new is only that a *package* is now a thing that can carry a
requirement, alongside a module.

## 9. Namespacing — local names, an optional mount, and no silent winners

**This is the one part of Go's model that does not port, and the part most worth getting right.**

In Go the import path *is* the module name: `github.com/foo/json` is globally unique, so two
packages cannot collide. sysl's rule is the opposite — `13 § 1` fixes that a module's name is its
directory path relative to the project root, which makes every module name **local and relative**.
A fetched `json` and your own `json` are the same name with no domain in the path to separate them.

Two ways out, and sysl takes the second:

1. **Make module names globally qualified.** This breaks the name/location agreement `13 § 1` makes
   load-bearing, and puts URLs in every import line.
2. **Keep names local, and let the manifest mount each dependency under a root name.** The
   dependency's own tree still follows `13 § 1` relative to *its* root; the manifest says where that
   root hangs in yours.

**The mount is optional.** A dependency states a preferred root name; a consumer writes `mount` only
when it collides. Mandatory mounting was rejected because it would make every project's import lines
differ from the library's own documentation — a permanent tax paid for an occasional problem.

**What a package offers is its own top-level module names, and that is settled by the paragraph
above.** A package's top-level directories are its modules (`13 §1`), so those are the names a
consumer writes: a package holding `sqlite/` is reached as `sqlite.open`, which is what its
documentation shows. Prefixing them by something — the package's name, the coordinate — would be
mandatory mounting under another name, and would fail the test this section already set. The
collision example below says the same thing from the other side, since the `json` a project's own
`json/` directory collides with is a *directory* name.

**`package.name` is deliberately not what a consumer writes.** It names the package, which is a unit
of distribution; an import line names a module, which is a unit of code. sqlite3 is the case that
makes the difference concrete — the package is `sqlite3` and the module is `sqlite` — and a consumer
reaching for the second should not have to say the first.

**A mount hangs the whole package under one segment.** Mounted as `ejson`, a package's `json` is
reached as `ejson.json`, which leaves the consumer's own `json` alone. That is the escape hatch, and
it is per-consumer: two projects mounting one package differently still link one copy of it.

**What makes "optional" safe is the rule that a collision is an error, never a silent winner.** Two
dependencies resolving to one root name refuse to compile, and the diagnostic says which one to
mount. This is the thing the JVM classpath gets wrong — the same package in two jars is resolved by
jar order, quietly, and the program that results is one somebody has to bisect to understand.
**Collisions include the consumer's own modules**, which is in fact the common case: a project with a
`json/` directory at its root and a dependency preferring `json` is not an exotic scenario.

### A package may namespace itself, and the published ones do

Everything above is about what the **tool** may impose, and the answer there is nothing: a mandatory
prefix would make every import line differ from the library's own documentation, which is the tax
this section refuses to levy. It says nothing about what a package may choose for **itself**.

A package's top-level directories are its modules, so a package that wants a namespaced name simply
has namespaced directories. The packages published under `sysl-lang` do exactly that, by convention
rather than by rule:

```
sh/sysl/monocypher/         →  module sh.sysl.monocypher
    monocypher.sysl
```

`sh.sysl.` is the reverse-DNS of `sysl.sh`, the same device Java and Scala use and for the same
reason. A dotted module name is a directory path (`13 §1`), so the prefix costs three directories and
nothing else — no new mechanism, and no change to anything in this chapter.

**What it changes is how often `mount` is needed, which is close to never.** The escape hatch stays,
because a convention only binds those who follow it and a dependency may still be a package that
picked a bare name — but a consumer whose dependencies are all namespaced will not meet a collision,
and will not meet the consumer's-own-module case either, since a project is unlikely to have an `sh/`
directory of its own. The optional mount was the right call precisely because the common case can be
arranged not to need it; this is a package author arranging that.

**A convention, deliberately, and not a rule.** Enforcing it would be mandatory mounting wearing a
different hat, and would refuse a perfectly good single-purpose package that wants a short name in a
project that will never collide. What the chapter guarantees is that a collision is loud; what the
convention does is make one unlikely.

### Identity is two-layered, and the mount is not the identity

**The mount name cannot be what the symbol mangler uses.** Mounts are per-consumer, so if one
project mounts a package as `json` and another mounts it as `ejson`, a mangler keyed off the local
name would emit `json$Parser.next` in one build and `ejson$Parser.next` in the other — the same
source producing different symbols, which makes a shared artifact cache worthless and makes two
consumers of one package unlinkable into one program.

So identity splits:

| layer | is | drives |
|---|---|---|
| **canonical** | the coordinate, plus the module's path inside that package | symbol mangling (`15 § 2`), artifact caching, MVS |
| **local** | the mount, plus that same path | how import lines are written, and nothing else |

`13 § 1` survives untouched: inside any package, a module's name is still its checked directory path
from *that* package's root. The mount is an import-resolution layer above it — **a rename for
reading, not a fork.** Two consumers naming a package differently still link one copy of it.

## 10. Vendoring

`sysl vendor` copies the resolved sources into `vendor/` in the project, after which the build reads
nothing from the network.

**This is not primarily a convenience feature.** A freestanding target has no network by definition,
and a build for one that needed to fetch would be a build that cannot run where it is needed.
Vendoring is also the answer to §3's honest cost — a repository that disappears is a repository you
already have a copy of.

**Sources are what is vendored, not artifacts**, which is the same rule the whole chapter runs on:
distribution is source, and compiled artifacts are a purely local cache keyed on content, target and
compiler version. Distributing binaries would mean a package × version × target × compiler matrix,
and `13 § 8` already establishes that an artifact is for one machine and pins both the target and the
format version. This is the `std.syslib` rule one level up — **derived artifacts are a cache and get
rebuilt; an artifact somebody named is a promise.**

## 11. The commands live in the `sysl` binary

```
sysl add github.com/edadma/sysl-json    # record a dependency, fetch it, report its capabilities
sysl fetch                              # resolve and download what package.hocon names
sysl vendor                             # copy resolved sources into vendor/
sysl deps                               # the dependency graph this project actually reaches
```

**One binary, the way Go merged them rather than the way Cargo and rustc are split.** A language
whose compiler and package manager are one program is a language with one thing to install and one
version number to be confused by.

**`sysl deps` is not a convenience command, and it exists because of a specific constraint.** `13 §
3` establishes that **a file's imports are not its dependency list** — a member is always reachable
fully-qualified with no import at all, so a file can depend on a module that no header in it names.
That has a direct consequence for manifests: **dependencies must be declared explicitly, because
they cannot be inferred from import headers.** Anything that must be declared by hand will drift
from reality, and a manifest nobody can check is the mechanism by which lockfiles become untrusted.
So the real dependency walk is exposed as a command, and a manifest can be checked against what the
code reaches.

## 12. What is deliberately absent

- **A registry, a module proxy, and a checksum database** — services, all three (§3).
- **Build scripts, install hooks, and plugins** — §7, and this one is load-bearing.
- **A lockfile** — MVS makes version selection a function of the manifests (§5); `sysl.sum` covers
  content (§6).
- **Feature flags / conditional compilation per dependency.** `targets.md` already has `#if` on
  target facts, which is the case that has come up. Cargo-style features interact with MVS badly
  enough that Go declined them too.
- **Workspaces / multi-package repositories.** A `path` dependency covers developing two packages
  together, which is the part that has actually been wanted.
- **Publishing.** There is nothing to publish *to* — a tagged git repository is the published form.

## Open (not yet decided)

- **~~a. Where a preferred root name comes from when there is no manifest.~~ CLOSED by §9's own
  rule.** The worry was that a package with no manifest has nothing to state a preference with, and
  that defaulting to a directory name would be magic — but the names a package offers are its
  **top-level module** directories rather than the directory it was checked out into, and those are
  decisions its author made and `13 §1` already makes load-bearing. So a manifest is not what names
  a package's modules, and a `path` dependency into a plain tree needs no `mount`. What *is* refused
  is a package with no top-level directories at all: it offers nothing, and a dependency on it can
  only be a mistake.
- **b. Whether module names ever become globally qualified.** §9 chose local names, but the
  alternative is a real design and not a strawman.
- **c. Whether a package's `link` directives are reported at add time.** The information is already
  in the source (`13 §`), and a user who learns at link time that they need `libsqlite3` installed
  learns it later than they could have.
- **d. Schema versioning and forward compatibility.** What an older compiler does with a
  `package.hocon` carrying a key it does not know — ignore, warn, or refuse. Refusing is safest and
  most annoying; nothing has forced the question yet.
- **e. Where the artifact cache lives and how it is keyed.** Canonical coordinate, target and
  compiler version are the obvious three, and `13 § Open d`'s whole-graph separate compilation is
  the same question one level down. These should be settled together.
- **f. Authenticated and private repositories.** Deliberately unexamined; git already has answers
  and the question is only which of them the fetcher inherits.
- **g. `requires` granularity**, carried over from `capabilities.md`: whether a package or module
  is the right unit, or whether an individual declaration should be able to state a requirement.
- **h. A package has no way to carry an example program.** Found by moving the SQLite binding out to
  [sysl-lang/sqlite3](https://github.com/sysl-lang/sqlite3), and **verified rather than reasoned**:
  `build-lib` on a tree holding `examples/demo.sysl` refuses it, because `13 §1` puts a file with no
  `module` header in the anonymous root module wherever it sits, and `13 §8` says a library may not
  be there. Everything under a package root is compiled *into* the library, so there is no "outside"
  — which is exactly what the in-tree bindings had, since a `bindings/<name>/lib` root left the demo
  somewhere to sit beside it. The demo went into that repository's README, which works and is not the
  same as being compiled. The options look like a directory the build ignores by convention, a
  manifest key naming what is not part of the library, or deciding a package simply does not carry
  programs — and the third is the one that needs an argument, since an example that is never compiled
  is an example that rots.

  **Confirmed a second time, and the cost is now the whole tree's.** Moving the regex binding out to
  [sysl-lang/regex](https://github.com/sysl-lang/regex) emptied `bindings/` and retired it, so there
  is no longer anywhere in the project where a binding's example program *is* compiled. Every one of
  them is now a fenced block in a README that nothing runs. That does not change the options above;
  it changes which way the third one has to argue, since "an example that rots" has stopped being
  hypothetical.

## The first package

[sysl-lang/sqlite3](https://github.com/sysl-lang/sqlite3) is the first sysl package outside this
repository, and it exists to keep this chapter honest: it has a real `package.hocon`, a module under
the package root, C compiled as part of the library, and a `link` directive that travels in the
artifact so nothing on the consuming side names `-lsqlite3`. **It is fetched today** — naming it in a
`dependencies` block is enough, and §3–§6 do the rest — which is what this paragraph used to deny,
back when it said those sections were unbuilt and nothing could fetch anything. The header at the top
of this chapter had already been corrected; this sentence had not, which is the ordinary way a status
claim written twice goes stale in one of its two places.

What is still done by hand is **naming** it: there is no `sysl add`, so the coordinate and the version
are typed into `package.hocon`. That is the command this package should be pointed at first.
