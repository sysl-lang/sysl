# Design Decisions: Modules

**Status:** the module system is **not yet implemented** — the parser has no `module`, `import`,
or visibility production, and a program today is a single flat statement list. But two written
docs already lean on modules: `capabilities.md` attaches capability narrowing (`no alloc`,
`requires`) and its transitive propagation to *modules*, and `cross-platform.md` fixes that
"module names follow the directory tree relative to the project root." This chapter defines what
a module **is** so those have something to name, and consolidates the module-side of the
capability machinery `capabilities.md` specifies. Where it commits a spelling it says so; the
*open* list records what waits for the project-config doc.

This chapter rests on `capabilities.md` (capabilities are a per-module property that propagates
through imports — this chapter is the module half of that contract), `cross-platform.md` (the
directory-tree naming and the platform-file axis), and `12-functions-and-closures.md` (top-level
definitions are an unordered, hoisted set — modules extend that across files and directories).

The through-line: a module is a **directory**, its name is its path, and its boundary is the
unit of both visibility and capability. A file is not a module; it is one contribution to the
module its directory names.

---

## 1. A module is a directory

A **module is a directory** of source files, and its **name is that directory's path relative to
the project root**, with the separators read as dots: the files under `oskit/arch/` make up the
module `oskit.arch`, those under `std/fs/` the module `std.fs`. Every declaration in every file
of the directory is a member of the one module; splitting a growing module into more files adds
no new module and changes no import.

This is the Scala/Go/Java package model, and it is the precedent principle 2 points at: of the
languages sysl defers to, Scala's `package a.b.c` and Go's directory-package both make the
directory the unit. (Rust, which makes a file or an inline block a module, is the "harder than we
want" reference, not the model.) The payoff is concrete for the target: an OS subsystem is a
directory — `oskit.arch`, a server, a driver — so the module is the subsystem, exactly the unit
`capabilities.md` narrows and the unit an importer depends on.

**Each file states the module it contributes to**, in a header, and the compiler checks the
declared name against the file's location:

```
// oskit/arch/cpu.sysl
module oskit.arch

halt() -> unit = …
```

The `module oskit.arch` line is the Scala `package` declaration: it names the directory-module,
and it must agree with where the file sits under the project root. Requiring the header (rather
than inferring the name purely from the path) keeps a file self-describing and gives the
capability clause a home (§4). It also **supersedes cleanly** the old compiler's path-prefix
relaxation — the "Step 3b" hack that let a file in `oskit/arch/x86_64/` declare the shorter
`module oskit.arch` — by making the name/location agreement a first-class, checked rule rather
than a loosened validation.

## 2. Visibility — public by default

A top-level declaration is **public by default**: visible to any module that imports it. Two
modifiers restrict it, following Scala exactly:

- **`private`** — visible only **within its own module** (the directory), across all that
  module's files, and invisible to importers. This is the everyday internal-helper marker: a
  function, type, or field that the module uses to build its public surface but does not export.
- **`private[ancestor]`** — Scala's **scoped** private: visible up to and including a named
  enclosing module. `private[oskit]` on a member of `oskit.arch` makes it visible throughout
  `oskit.*` — every sibling and descendant module under `oskit` — but not outside it. This is how
  a subsystem shares internals across its sub-modules without making them part of the world's
  API.

```
pub_by_default() -> int = 42            // exported

private lookup(fd: int) -> &File = …    // this module only

private[oskit] struct FrameHeader …     // anywhere under oskit.*
```

**Why public-default and not export-on-`pub`.** Rust makes a declaration private until `pub`;
Scala and Kotlin make it public until restricted, and that is the precedent chosen here. The
low-ceremony common case — a small module whose declarations are meant to be used — writes no
modifier, and encapsulation is the deliberate act (`private`) rather than exposure being one.
There is no `pub` keyword; its absence *is* public.

## 3. Imports — the Scala forms

A module reaches another module's members two ways, and the first needs no import at all:

- **A member is always reachable fully-qualified** by its module path: `std.fs.read(p)` names
  `read` in module `std.fs` with no import statement. Nothing is required to *see* a public
  member; import exists only to *shorten* the reference.
- **`import` brings names into scope unqualified or under a shorter alias**, with the Scala
  spelling:

```
import std.fs.read                 // read(p)          — one member, unqualified
import std.fs.{read, write}        // read(p), write(…) — several
import std.fs.*                    // every public member of std.fs, unqualified
import std.fs.{read as rd}         // rd(p)            — renamed
import std.fs                      // fs.read(p)       — the module itself, member access by name
```

The selective `{a, b}`, the wildcard `*`, and the `{a as b}` rename are Scala 3's import
syntax unchanged. `import std.fs` (the last form) brings the module *name* `fs` into scope, so its
members are reached qualified by the final segment — the self-documenting `fs.read` that says at
the call site where the name came from, without listing members. The wildcard is the terse
opposite; the selective form is the middle. One syntax serves all three of the styles the earlier
draft weighed, because Scala's import already spans them.

**Imports normally sit just below the `module` header**, but — following Scala — an import may
also appear **inside a block**, scoped to that block, for the local case where a name is wanted in
one function only.

**Name resolution** is innermost-first: a local binding shadows an imported name, an imported
name shadows nothing it does not name, and the fully-qualified path is always available to break a
tie or reach a name deliberately not imported. A wildcard import that would bring in a name
already defined locally or explicitly imported loses to the more specific one, as in Scala; two
wildcard imports that both offer the same name make an *unqualified* use of it ambiguous (a
compile error naming both), and the fix is to qualify it or import it selectively.

## 4. Capabilities are a module property

`capabilities.md` is the authority on what the capabilities *are* and how the effective set is
computed; this section states only how they attach to the module model of §1, which is the piece
that doc left to here.

- **A capability clause narrows the module**, and it is written in the **file header** beside
  `module`. Because the module is the directory (§1) and the clause is a property of the module,
  a narrowing clause **must appear consistently in every file of the module** — the compiler
  rejects a module whose files disagree. The redundancy buys local legibility: you can never open
  a file in a `no alloc` module and fail to see that it is `no alloc`.

```
// oskit/arch/cpu.sysl          // oskit/arch/mmu.sysl
module oskit.arch               module oskit.arch
no alloc                        no alloc            // same clause, enforced identical
```

- **`requires alloc`** (and the other direction) is likewise a module-header clause, documenting
  and early-diagnosing a hard dependency, per `capabilities.md`.
- **Propagation is over the module graph.** A module's effective requirement is its own uses plus
  the requirements of every module it imports, transitively, and the whole graph must fit the
  target (`capabilities.md`). A `no alloc` module importing a `requires alloc` module is an error
  at the import, not deep in codegen. Because the module import graph is acyclic (§6), the
  propagation is a **single sweep in reverse topological order** — each module's requirement set is
  final before any importer of it is visited — not an iterated fixpoint. The fixpoint that escape
  analysis uses for mutual recursion (`05`) is still needed *within* a module, where sibling files
  share one scope and may call each other freely; it is the cross-module direction that the DAG
  removes.

## 5. Platform selection rides the same name

A module's members may be **split across platform-specific files** — `cpu.aarch64.sysl` and
`cpu.x86_64.sysl` contributing to the same `oskit.arch` — with the active target selecting which
file contributes. The **module name is unchanged by the platform suffix**: importers write
`import oskit.arch` and name `oskit.arch` members regardless of which platform's file is compiled
in, which is what lets the kernel import one arch module and get whichever platform it was built
for (`cross-platform.md`, and the pattern the old compiler reached for with its path-prefix
relaxation).

The **exact suffix grammar and the resolution rule** — which filename shapes mark a platform, how
the active target is chosen, and the `sysl.conf` schema that ties it together — belong to the
**project-config doc**, which `capabilities.md` already flags as unwritten. This chapter fixes
only that the *module identity* is invariant under platform selection; the file axis lives below
the module name, not beside it.

## 6. The module graph is acyclic

**Two modules may not import each other**, directly or through a chain. The import graph is a
**DAG**, and a cycle in it is a compile error naming the modules on the cycle. This is Go's rule,
and it is a deliberate divergence from Scala, where a package's compilation units may depend on
each other freely.

Three things follow from it:

- **Modules can be compiled in dependency order, and independent modules in parallel.** A
  topological sort exists, so a module's imports are all fully known before it is checked. A cyclic
  graph would force the whole strongly-connected component to be collected and checked as one unit,
  which is the same as saying it was never really more than one module.
- **Capability propagation is a sweep, not a fixpoint** (§4).
- **A cycle is a design error, and the fix is cheap.** Two directories that need each other are
  either one module drawn along the wrong line — merge them, which changes no import, since a
  module is a directory and its file count is not part of its name (§1) — or they share something
  that belongs in a third module both import. Neither fix costs an importer anything.

**Within** a module, cycles are free and carry no ceremony. All files of a directory share one
scope (§1), so mutually recursive functions and types across sibling files need no forward
declaration and no ordering: the analyzer collects **every signature in the module before it checks
any body**, the cross-file extension of the top-level hoisting of `12` §4. The restriction is on
the directory graph, never on how a module's own files refer to one another.

## 7. What is deliberately absent

- **No file-as-module.** The file is a contribution, not a unit; there is no per-file namespace
  and no import of a file. A module is always a directory (§1).
- **No `pub` keyword.** Public is the unmarked default; `private` / `private[X]` are the only
  visibility modifiers (§2).
- **No relative or wildcard-path imports.** An import names a module by its full dotted path from
  the project root; there is no `import ..sibling` or path-relative form. Absolute names keep a
  reference's meaning independent of where the importing file sits.
- **No implicit prelude-style auto-import beyond the language prelude.** The prelude (`Option`,
  `Result`, `print`, the scalar types — `09`, `11`) is in scope everywhere without an import;
  nothing else is. A module earns visibility by being imported or fully qualified.

---

## Open (not yet decided)

- **a. The project-config doc.** The `sysl.conf` (HOCON) schema, the target registry, the
  platform-file suffix grammar and resolution (§5), and how the project root is located are a
  separate doc `capabilities.md` already defers. Design the minimum that unblocks multi-file
  builds — root, active target, capabilities, platform-file selection — and let real needs drive
  dependency resolution, workspaces, and publishing rather than guessing them now.
- **b. Re-export / facade modules.** Whether a module can re-export another's names (a `pub
  import`-style forwarding, so `std` can surface `std.fs.read`) is a real ergonomic want for
  building a curated public surface over sub-modules, and is left until the standard library's
  shape calls for it.
- **c. Module-level visibility.** §2 governs a *declaration's* visibility. Whether a whole
  *module* can be marked private to its parent (an internal sub-module invisible to outside
  importers, as Rust's `mod` privacy and Scala's `private[parent] package` allow) is open, and
  interacts with (b).
- **d. Separate compilation and module metadata.** Monomorphization and escape/capability
  propagation need a module's bodies or a summary of them across the boundary (`05`,
  `capabilities.md`); the on-disk form of that metadata, and whether modules compile separately or
  the whole graph compiles together, is an implementation decision not settled here.
- **e. `private[X]` scoping corners.** The exact set of names `X` may take (only ancestor
  modules, or also sibling paths), and how scoped-private interacts with re-export (b), follows
  Scala but has corners to pin when the feature is built.
