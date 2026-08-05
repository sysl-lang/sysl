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

requires { os = true }

dependencies {
  json  { git = "github.com/edadma/sysl-json", version = "1.4.0" }
  regex { git = "github.com/edadma/sysl-regex", version = "0.4.0", mount = "re" }
  local { path = "../experiment" }
}
```

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

## What a dependency's modules are called

A package is a tree of modules, and **its top-level directories come in under their own names**. A
package holding `sqlite/` is reached exactly as its own documentation shows it:

```text
sqlite.open("db.sqlite")
```

The name is the *module's*, not the package's — sqlite3's package is called `sqlite3` and its module
is `sqlite`, and reaching the second does not mean saying the first.

**Two packages cannot quietly share a name.** If two dependencies both offer a `json`, or one offers
a `json` and your own project has a `json/` directory, the build stops and says so rather than
picking one. Write a `mount` to say what one of them is called here:

```hocon
dependencies {
  theirs { git = "github.com/edadma/sysl-json", version = "1.4.0", mount = "ejson" }
}
```

which hangs that whole package under one segment, so its `json` is `ejson.json` and your own `json`
is untouched. A mount is yours alone: another project may mount the same package differently, and
both still link one copy of it.

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

## No build scripts, ever

**A package cannot run code at build time.** Not a hook, not a script, not a plugin. `sysl add` and
`sysl build` read and write files and run nothing.

Most of what other ecosystems need build scripts for is compiling vendored C, and sysl already
compiles a library's C declaratively — the linker inputs a package needs are `@link` attributes in
its source, not a program that computes them. What that buys is most of the supply-chain story: a
package that cannot execute during installation cannot exfiltrate anything during installation.
