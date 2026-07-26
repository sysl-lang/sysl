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

## 2. Visibility — public by default, and `private` means *this file*

A top-level declaration is **public by default**: visible to any module that imports it. Two
modifiers restrict it:

- **`private`** — visible only **within the file that declares it**. Not to sibling files of its
  own module, not to importers. This is the file-local helper: a declaration that exists to build
  something in this one file and is not part of even the module's internal surface.
- **`private[M]`** — **scoped** private, where `M` names the declaring module or one of its
  ancestors. Visibility extends to that module and everything beneath it. `private[arch]` on a
  member of `oskit.arch` makes it visible across all of `oskit.arch`'s files — this is the
  everyday module-internal helper — and `private[oskit]` widens it to every sibling and descendant
  module under `oskit`, without making it part of the world's API.

So the four levels are one keyword and its scope argument:

| form | visible to |
|---|---|
| `private` | this file |
| `private[own_module]` | every file of this module |
| `private[ancestor]` | the named ancestor module and its whole subtree |
| *(unmarked)* | any module that imports it |

```
pub_by_default() -> int = 42            // exported

private lookup(fd: int) -> &File = …    // this file only

private[arch] reset(c: *Cpu) -> unit = …  // every file of oskit.arch

private[oskit] struct FrameHeader …     // anywhere under oskit.*
```

**`M` resolves innermost-outward.** The argument is a **simple name**, not a path, and it is
matched against the enclosing module names from the declaring module outward, first hit winning.
That disambiguates a repeated segment — `private[geom]` inside `geom/mesh/geom/tri` binds to the
nearer `geom`. A name matching no enclosing module is an error; there is no way to name an
unrelated module, so a visibility scope is always a contiguous subtree containing the declaration.
Because the name is resolved where it is declared, moving a subtree elsewhere does not change what
its internal `private[M]` annotations mean.

**Why file-scoped and not module-scoped.** This is a deliberate divergence from Scala, where
`private` means the enclosing class or package. Making the bare form file-scoped costs nothing in
expressiveness — module-private is exactly `private[own_module]`, the degenerate case of the
scoped form, so no separate keyword is needed for it — and it buys the one level that provably
**never crosses a file boundary**. That is the level at which a declaration can be fully inferred,
mangling can be skipped, and LLVM `internal` linkage applies; everything wider needs an external
symbol, because §1's shared module scope means a sibling file can call it. §3 of the module-system
notes develops what rests on that property.

The cost is honest and worth naming: the everyday module-internal helper is now `private[arch]`
rather than a bare `private`, so the common case is the wordier one. The alternative — a second
keyword whose only job is the module level — spends a keyword to save a bracket, and leaves the
file level either unspellable or spelled by something even less obvious.

### Anything visible outside its file states its types

**A declaration visible beyond the file that declares it carries explicit types.** Inference is
available only at the bare-`private` level — the one level that provably never crosses a file
boundary (above), which is why the two rules are really one.

Most of this the existing syntax already enforces, and nothing changes:

- **Parameter and field types are mandatory** — a function parameter and a struct field are the
  same `name: type` binding, and neither may omit the type (`12` §1, `08`).
- **A return type is written, or its absence *means* `unit`** (`12` §1). That is a default, not an
  inference: the signature is complete as written either way, and a reader never has to consult a
  body to learn what a function returns.

So the rule binds in exactly one place today — a **top-level `var`**, whose annotation is optional
and whose type otherwise comes from its initializer. Such a declaration must be annotated unless it
is file-`private`:

```
var counter: int = 0             // visible past this file: annotated
private var scratch = 0          // file-private: inferred, as a local is
```

**Why: it makes interface extraction parse-only.** A file's exported surface can then be read off
its syntax tree — without resolving a name, checking a body, or having compiled anything the file
imports. That is what lets the collect pass of §1 depend on nothing but parsing, and it is the
property that a fast, parallel, and eventually incremental build rests on. Scala infers types for
public members and pays for it with a far heavier extraction step; this is a deliberate divergence,
and it is cheap here precisely because sysl's signatures were already explicit for other reasons.

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

**A file's imports are not its dependency list.** Because a qualified reference needs no import,
a file can depend on a module without naming it in any header — the dependency appears only in a
body. Two consequences follow, and they are the price of the Scala-style convenience above:

- **Building the module graph requires parsing, not a header scan.** Lexing each file's `module`
  line and imports would be enough to find the edges only in a language where import is the sole
  path to a foreign name. Here the edges live in bodies, so discovery reads the whole file. This
  is bounded work — a parse, no name resolution and no typechecking — but it is not the few
  hundred bytes per file a header-only scan would cost.
- **The §6 cycle check needs real resolution, not a textual scan.** Since a cycle is an *error*,
  a spuriously-recorded edge can reject a valid program. Matching every dotted chain against the
  known module directories (which §1 makes available from `readdir` alone, with zero parsing) is
  the cheap approximation, but it over-approximates exactly where a local binding shadows a module
  name — a local named `std` makes `std.fs` a field access, not a module reference. So the edge
  set has to come from resolution.

Neither is a reason to give up qualified access, and neither affects the parse-only *interface*
extraction of §2 — a module's exported surface is its signatures, which do not change with what its
bodies happen to reference. Interface extraction and dependency discovery are separate questions
with separate costs. What the build driver does with this belongs to open item (d).

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
- **No implicits — no Scala-style `given`/`using`.** A term-level value selected by *searching* the
  scope for something of the right type is out of scope, and deliberately. Introducing a given
  anywhere in scope can change what resolves in a file that did not change and whose dependencies'
  signatures did not change, so a module's interface would no longer bound its blast radius —
  making the whole graph, rather than a module's declared surface, the unit of invalidation.
  (Scala pays for this with per-file used-name tables and API diffing; that machinery is the cost
  of the feature, not an implementation detail of it.) The one search sysl does perform, for a
  trait `impl`, is bounded to two modules by the orphan rule (`02`) for the same reason.

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
  the whole graph compiles together, is an implementation decision not settled here. The driver's
  discovery order is part of this: §3 establishes that the module graph cannot be recovered from
  file headers alone, so the build has to parse before it can order, and how that interleaves with
  cycle reporting (§6) and caching is unsettled.
- **e. `private[M]` and re-export.** §2 settles what `M` may name (the declaring module or an
  ancestor, as a simple name, resolved innermost-outward) and therefore that a visibility scope is
  always a contiguous subtree. What is left open is how scoped-private interacts with re-export
  (b) — whether a facade module may forward a name it can see but its own importers cannot — which
  cannot be pinned before (b) is.
