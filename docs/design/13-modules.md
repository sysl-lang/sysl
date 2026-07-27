# Design Decisions: Modules

**Status:** §1, §2's visibility modifiers, the whole of §3, the whole of §6, and §7's entry-point
rule are **built** — a project is a tree of directories, each one a module named by its path from
the root and holding its files to that name, their declarations visible across all of them with no
ordering and no forward declaration, a member of one module reached from another by naming it in
full, the `import` statement in all five of its forms shortening that path for the file or the block
that writes it, `private` / `private[M]` deciding which of those spellings a given file is allowed
to write at all, no declaration allowed to name in its signature a type that does not reach as far
as it does, and the graph those references make held to being acyclic. The capability clause (§4) is
**not yet implemented**: the propagation it needs is the sweep §6 now makes available, but the
*target* a propagated requirement would be checked against waits on open item (a), the
project-config doc. Two written docs already lean on modules: `capabilities.md` attaches
capability narrowing (`no alloc`, `requires`) and its transitive propagation to *modules*, and
`cross-platform.md` fixes that "module names follow the directory tree relative to the project
root." This chapter defines what a module **is** so those have something to name, and consolidates
the module-side of the capability machinery `capabilities.md` specifies. Where it commits a
spelling it says so; the *open* list records what waits for the project-config doc.

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

The **files of one module must all name it the same way**, which is the same rule §4 states for the
capability clause and for the same reason: the module is the directory, so its name is a property
of the directory rather than of any file in it, and a file that disagrees is either misplaced or
was edited without its siblings. Holding each file to the name its **location** gives it is what
enforces that, and it is the stronger rule: two files of one directory are each held to the same
derived name, so the one that strayed is reported on its own line rather than as a disagreement
with whichever sibling happened to be read first. The location is the *driver's* to know — it is
what walks the tree — so a file handed to the compiler with no project around it carries none, and
its header is then the whole of what says which module it is in.

A file with **no header at all** is in the **anonymous root module**, whose name is the empty path.
This is what lets a program be one file with no ceremony — the one-file case is not a special form,
it is a module that happens to be unnamed — and it is also why a file with nothing in it is a file
that has not said which module it is, and is told so wherever it sits below the root.

The root module's name being empty has one consequence worth stating outright: **nothing can name
it**, so its declarations are visible to its own files and to nothing else. That is the right way
round for the place a program starts — the root reaches down into the modules it is built out of,
and they do not reach back up into it — and it is also what keeps a single-file program's names
exactly where they were before modules existed.

**A module and a type of its parent may not spell one path.** A dotted reference takes the longest
prefix that names a module (§3), so a module `geom.Point` alongside a type `Point` in `geom` would
take `geom.Point.dist` outright and leave the type's member no spelling at all. The two stay
distinct *declarations* either way — what collides is the path a program writes — so the second one
is refused with a diagnostic rather than settled by a silent choice.

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

### A restriction is about naming, not about existence

**A modifier decides who may write a name; it never makes a second namespace.** A file-private
declaration still belongs to its module and still spends its name there, so a sibling file cannot
declare something else of that name — the file is a contribution rather than a unit (§8), and that
is what makes the two rules one rather than two. The five declaration forms take a modifier; an
`impl` takes none, having no name for one to restrict, and an **enum's variants carry the enum's
own**, since a type nobody outside may name is not one whose variants they may construct.

**A name a file may not reach is not a candidate for it.** Resolution passes over one and goes on
through the file's imports and the prelude, rather than stopping there — a file that wrote
`import util.width` said which `width` it meant, and a sibling file's private helper of that name is
not an answer to it. Where nothing else answers at all, the restriction is then reported, because at
that point it is the whole story and a better one than an undefined name. The two halves of that
rule are what keeps adding a private helper from changing what its module's other files mean.

**A wildcard offers only what is visible; a selector is refused where it is not.** That follows from
§3's "a wildcard offers a name, a selector binds one": a wildcard has claimed nothing, so a name it
cannot see is simply not among what it brings in — and therefore cannot make another module's name
ambiguous either. Naming something deliberately is the opposite case, and being told at the import
that it is private is more use than an undefined name at every shorter spelling it would have bound.

### A declaration may not be more visible than the types it names

**What a declaration says about itself has to be as nameable as the declaration is.** A `private
struct Point` beside a public `make() -> Point` would hand every module a value of a type none of
them may name: they could hold it, pass it on, and read its fields, and the one thing they could not
do is write the type down. That is a hole in the restriction rather than a use of it, so the
declaration is refused:

```
private struct Point
    x: int

make() -> Point = Point(1)   // refused: 'make' is public, but its result names 'Point',
                             // which is private to the file that declares it
```

The comparison is between two *reaches*, and every reach is a contiguous region because `private[M]`
may only name an enclosing module (above): a type restricted to a subtree may stand in a signature
restricted to that subtree or to anything inside it, and never the other way round. A bare-`private`
declaration is exempt in every case — it is read in one file, and a type it can name at all is
visible there.

**It reaches everything a caller has to be able to write**, which is more than a parameter and a
result: a struct's fields and an enum variant's payload, since neither has a visibility of its own; a
type *argument*, since `Box[Point]` names `Point` as much as a bare `Point` does; a trait behind a
memory mode, which is an object over it; a member of a type or a trait, which carries no modifier and
is therefore as visible as the thing it belongs to; and a **bound**, since a trait a caller cannot
name leaves it unable to say what is being asked of it.

**An `impl` block is outside the rule, in both directions.** Implementing a private trait for a
public type adds a member nobody outside can ask for by trait; implementing a public trait for a
private type makes a public promise about a type that stays unnameable. Neither leaks a name, and a
private type reaching a caller *through* a trait's signature is a leak in the trait, which is where
it is reported.

**Rust refuses this and Scala allows it**; this follows Rust. The refusal is what makes `private`
mean something a reader can rely on — a restricted type stays inside its region — and it is additive
in the safe direction: forbidding it now rules out nothing that a later rule would have had to keep
allowing, while allowing it and tightening later would break programs.

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

So the rule **binds nowhere today**, and that is worth saying plainly rather than leaving as an
apparent gap. The one declaration whose type could be inferred is a top-level `var`, and §7 settles
what one of those is: a local of the program's entry point, scoped to it and initialized in its
order, not a member of the module its file contributes to. It is therefore not visible outside its
file to begin with, carries no visibility modifier, and has nothing for this rule to hold it to. The
rule binds the moment a **module-level binding** exists — a `var` that is a member of its module,
which is a different declaration from the one written today — and it is stated here so that the
question is already settled when that arrives.

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

An unqualified name is looked for **in the module it is written in, then among the file's imports,
then in the prelude**, and nowhere else — a sibling module's names are not in scope unqualified,
and neither are the root module's, which have no path to be reached by at all (§1). A **dotted**
reference names a module by the **longest prefix of it that is one**: a program holding both `a`
and `a.b` reads `a.b.f` as `a.b`'s `f` rather than as `a`'s `b`, and §1's refusal of a module named
for a type of its parent is what keeps that from silently hiding a member. Everything left of the
module prefix is the ordinary form — `read(…)`, `Point(…)`, `Shape.Circle(…)` — which is why
qualified access needed no second resolution path beside the unqualified one.

**An import binds a name, not a kind.** The same spelling may be a type in one module and a
function in another, so what an import records is which path a name stands for; which of them a
particular use meant is settled by what that position asks for, exactly as it is for a name the
module declares itself. One import therefore serves a type, a function, a trait, and an enum
variant without saying which it expected to be.

**A module brought in by name is a prefix wherever a written path is** — `import std.fs` makes
`fs.read(p)`, `fs.File`, and `[T: fs.Seekable]` all work, because the leading segment of a dotted
reference is read through the imports where it is not already a module. Two rules keep those from
ever both applying: a module reached by the name it already has (`import geom`, where `geom` is a
top-level module) asks for what is already true and binds nothing, and an import may **not** be
given a name that a module path already begins with. The second is a refusal rather than a
precedence rule on purpose — a binding that is both would make `fs.read` mean one thing in a file
that imported `fs` and another in the file beside it, and `as` costs one word.

**A wildcard offers a name; a selector binds one.** That is the whole of the difference in the
rules above: a wildcard neither collides with a selective import of a name it also offers nor with
a second wildcard over the same module, because it has claimed nothing. Binding one name twice —
by two selectors, or by two statements — is a mistake, and is reported at the second import rather
than at whichever use first found two answers.

An **import inside a block** is added to that block's scope, so it lasts as long as the block's
local bindings do and shadows whatever the file imported under the same name. It takes effect
where it is written: the statements above it have imported nothing.

An import is **not** an executable statement, whatever it looks like — it binds a name and runs
nothing, so a file may import freely without becoming the one file of the program that carries its
statements (§7).

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

**Two modules may not depend on each other**, directly or through a chain. The dependency graph is
a **DAG**, and a cycle in it is a compile error naming the modules on the cycle. This is Go's rule,
and it is a deliberate divergence from Scala, where a package's compilation units may depend on
each other freely.

The graph is over **references**, not over imports, and §3 forces the distinction: a member of
another module is reachable by its full path with no import at all, so a file can depend on a module
its header never mentions. An edge is therefore whatever *resolution* found — a call, a type named
in a signature or a field, a trait named as a bound or behind a memory mode, a variant, a generic
instantiated from elsewhere. An import contributes an edge of its own on top of those: a file that
imports a module depends on it whether or not it goes on to write the shorter spelling it bought,
because a file's imports are meant to be readable as what it needs, and a dependency that came and
went with a use would not be.

Two things sit outside the graph. The **prelude** is the language rather than a module, so writing
`print` is not a dependency on anything. And the **anonymous root module** (§1) can be depended on
by nothing, having no name for another module to write — a program's root files depend on the
modules beneath them, and the dependency never runs back.

Three things follow from acyclicity:

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

## 7. Where a program starts

A top-level **statement** is not a declaration: a declaration is hoisted and belongs to the module
as a whole, while a statement runs, and running happens in an order. §6 gives a module's files no
order at all — they are one unordered scope, and which one the driver read first is an accident of
`readdir` — so a module whose statements were spread across two files would have no defined
behaviour to compile. Modules have no order either: §6 makes them a graph, which says what may
depend on what and nothing about what runs first.

**One file of a program carries the statements it runs**, and a second that carries any is an error
naming both. This is not a restriction on where declarations may go, which is the point of §1: a
module may be split across as many files as it likes, a program across as many modules, and only
the executable part is pinned to one file. Those statements are a body like any other in one
respect — an unqualified name in them is read in the module of the file that wrote them.

A top-level `var` counts as a statement, because it is exactly that — a local of the entry point,
scoped to it and initialized in its order, not a member of the module. (A module-level binding
*visible to other files* is a different thing, and it is what §2's "anything visible outside its
file states its types" is written about.)

A program in which no file carries a statement is a complete program that does nothing: the entry
point exists, runs nothing, and succeeds. That is what a tree of pure declarations compiles to,
which is what it should compile to — a library is not an error.

### Constants

A **`const` is a module member**, and the one kind of module-level binding there is:

```
const capacity: usize = 512
const max_slots: usize = 32
private const window: int = 1 << 15
```

It is a declaration, not a statement — hoisted, order-free, visible to the whole module and beyond
it under the ordinary rules — and it is what a top-level `var` is not. The two are told apart by
the keyword rather than by immutability: a `var` at the top of a file is a local of the entry point
(above), and a `const` never runs at all.

**Why it exists at all**, when a nullary function already serves every use in an expression. Three
of the guide programs wanted a name for a number and could only get one that a *type* cannot read.
An array bound is a compile-time constant and a call is not, so `guide/bytecode` carried `[512]u8`
next to `capacity() -> usize = 512usize` with a comment asking the reader to keep the two in step,
and `guide/png` wrote `[320]u8` where it meant `286 + 30`. All four now name what they mean. That
is the case a function cannot cover, and it is the whole reason for the declaration.

**The type is always written.** Not because it could not be inferred from the initializer — it
plainly could — but because §2's rule that anything visible outside its file states its types is
what keeps interface extraction parse-only, and a constant is the first declaration that rule has
ever had to bind. One rule for every visibility is worth more than the four characters a `private`
one would save, and writing it is what fixes the literal's type: `const capacity: usize = 512`
needs no suffix on the 512, by the ordinary rule that a literal takes its type from where it sits
(`01`).

**A constant expression** is a literal; a `const`; a conversion (`u8(x)`); a unary `-`, `!` or `~`;
or a binary arithmetic, bitwise, shift, or comparison operator applied to constant expressions.
Integers, floats, `bool` and `char` fold; a `string` constant may be declared but its initializer
must be a literal, since `+` on strings allocates and a compile-time concatenation would be a
different operation wearing the same spelling. There are no calls: a function call in a constant
expression is a request for compile-time evaluation of arbitrary code, which is a language of its
own and not one this needs.

**Where a constant may stand:** anywhere an expression may, plus the three places a literal was
previously the only thing accepted — an **array bound** (`[capacity]u8`), an **enum discriminant**
(`Halt = base`), and a **pattern** (`n match { limit -> … }`). That last one is the one worth
saying out loud, because it is where other languages have come to grief: a name in a pattern binds
unless it resolves to something, and Rust's rule that a lowercase `const` in a pattern *binds*
instead of matching is a documented trap. Here it cannot arise — a pattern name already resolves
against the enum variants in scope before it is taken as a binding, and a constant joins that same
resolution rather than adding a second one.

**A constant has no address.** It is folded into each use and occupies no storage, which is why it
needs no initialization order, why a `no alloc` module may hold one, and why `&capacity` is not a
thing to write. What that rules out is the *other* half of what the guide programs asked for: a
**table** — `guide/png`'s 256-entry CRC array — is static storage with a computed initializer, and
that is a different feature with different questions (const-evaluated or run once at startup, and
if the latter, in what order across a module graph that deliberately has none). It waits for a
second customer, and the answer is not "make `const` bigger".

Two things a constant is **not**, so the boundaries stay where the rest of the design put them:

- **Not an enumeration.** A set of related named values is a simple enum, which `09` argues for at
  length as the type-safe replacement for a pile of `const`s. A constant is one dimension, not a
  family of them; if a second constant would be the obvious neighbour of the first, the declaration
  wanted was an `enum`.
- **Not const generics.** Parameterizing over a length is `10 § Open d` and stays deferred — but it
  is worth recording that this is its prerequisite, since a value cannot be passed as an argument
  before it can be named.

A cycle between constants (`const a: int = b`, `const b: int = a`) is reported at the declaration,
naming the loop, in the same way §6 reports one between modules.

## 8. What is deliberately absent

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

- **a. The project-config doc.** The `sysl.conf` (HOCON) schema, the target registry, and the
  platform-file suffix grammar and resolution (§5) are a separate doc `capabilities.md` already
  defers. **The project root is no longer part of it**: the driver takes one, as the path it is
  given — `sysl run <dir>` makes that directory the root and compiles the tree beneath it. That is
  the minimum that unblocks multi-module builds, and it costs nothing to keep when a config file
  arrives, since a file only ever *names* a root the driver would otherwise be told. What is left
  is the active target, the capability declarations, and platform-file selection; let real needs
  drive dependency resolution, workspaces, and publishing rather than guessing them now.
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
- **f. Visibility below the top level.** §2 governs a *top-level* declaration. Whether a type's
  **member** can be restricted — a helper method that is not part of what the type offers — is
  open, and is the same question as (c) one level down: both are about a boundary that is not the
  file or the module. A member written with a modifier is a parse error today, which is the right
  refusal for something not yet designed but a poor way to say so.
- **g. What the file level buys the backend.** §2 notes that a bare `private` is the level at which
  mangling can be skipped and LLVM `internal` linkage applies. Neither is done: the compiler emits
  one module for the whole program, so `internal` would be correct but would buy nothing
  measurable. It becomes worth doing exactly when (d) does — separate compilation is what makes an
  external symbol cost something.
