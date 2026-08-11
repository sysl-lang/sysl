# Design Decisions: Modules

**Status:** §1, §2's visibility modifiers, the whole of §3, the whole of §6, and §7's entry-point
rule are **built** — a project is a tree of directories, each one a module named by its path from
the root and holding its files to that name, their declarations visible across all of them with no
ordering and no forward declaration, a member of one module reached from another by naming it in
full, the `import` statement in all five of its forms shortening that path for the file or the block
that writes it, `private` / `private[M]` deciding which of those spellings a given file is allowed
to write at all, no declaration allowed to name in its signature a type that does not reach as far
as it does, and the graph those references make held to being acyclic. **§4 is built too** — the
header attributes `@no_alloc`, `@requires(...)` and `@link("...")` parse — as does `@tests`, which
belongs to `testing.md` rather than to this chapter and shares only the position — a module whose files
disagree about a narrowing is rejected, the requirement propagates over the import graph as the
single reverse-topological sweep §6 makes available, and the finer-than-declaration `alloc` check
this section ends on reports the *call* rather than the import. Two claims this paragraph used to
make are gone with it: the clause did not parse, and the target was the blocker. Both were stale, and
`Target` had been a real value with a registry of ten targets, a `--target` flag and a `targets`
command for some time before either sentence was removed. What is genuinely left of open item (a) is
the platform-file suffix grammar and resolution (§5) — the `package.hocon` schema is written and
built, as `packages.md` says. Two written docs already lean on modules: `capabilities.md` attaches
capability narrowing (`no alloc`, `requires`) and its transitive propagation to *modules*, and
`cross-platform.md` fixes that "module names follow the directory tree relative to the project
root." This chapter defines what a module **is** so those have something to name, and consolidates
the module-side of the capability machinery `capabilities.md` specifies. Where it commits a
spelling it says so; the *open* list records what waits for the project-config doc, which is now
written as `packages.md`.

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
**never crosses a file boundary**. That is the level at which a declaration can be fully inferred
and LLVM `internal` linkage applies; everything wider needs an external symbol, because §1's shared
module scope means a sibling file can call it. §3 of the module-system notes develops what rests on
that property.

**What that level does *not* buy is a shorter symbol, and an earlier draft said it did.** The claim
was that mangling could be skipped for a bare `private`, on the reasoning that a name nothing outside
the file can write needs nothing outside the file to agree on it. That confuses two boundaries. Every
file of a compilation is emitted into **one** LLVM module, so a symbol still has to be unique across
the whole program rather than within its file — and the paragraph above is exactly what makes the
collision reachable: a file-private name is spent in its module, so two *modules* may each hold a
private `scale`, and dropping the module segment would leave one `@scale` where there are two
definitions. The mangling is what keeps them apart, and it stays. `internal` is the part that was
real, and it is applied.

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
through the file's imports, rather than stopping there — a file that wrote
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

**"Signature" is the shorter word for it, and the rule is not about signatures.** It is about
everything a declaration says about itself, so it reaches the forms that have no signature at all: a
field, an enum variant's payload, a type parameter's bound and its default, and the declarations that
are a **name and one type** — a module-level `val`, and an `extern` variable. Those two are the
easiest to overlook and are no smaller a hole than a function's result: a module that may write the
name holds a value of a type it cannot write, which is the whole of what this section refuses. A
`const` is spared only because it cannot reach the question — §7 holds a constant to being a scalar,
and every scalar is a builtin nobody may restrict.

**It reaches everything a caller has to be able to write**, which is more than a parameter and a
result: a struct's fields and an enum variant's payload, since neither has a visibility of its own; a
type *argument*, since `Box[Point]` names `Point` as much as a bare `Point` does; a trait behind a
memory mode, which is an object over it; a member of a type or a trait, which is as visible as the
thing it belongs to unless it narrows itself (`08 § Visibility`); and a **bound**, since a trait a caller cannot
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

**A trait's member is reached where the trait is.** A type's members are one namespace whatever
brought them (`08 § One name, one member`), and the members an `impl` block brings are in it — but
which of them a use can *see* is a question about the **trait**, not about the type. A trait
declared in this module, imported by name, offered by a wildcard, or carried by an auto-imported
module is in scope; a library submodule's is not until a file asks for it, exactly as its values are
not (`§3`).

**This holds whether or not anything else declares the name.** A member is not reachable because
there is nothing it might be confused with — a file that never named `sysl.math.Float` may not call
`x.sqrt()`, and the diagnostic is the import, because an import is the whole of what it was missing.
That makes a submodule cost a program exactly what it asked for in both halves: `pi` and `sqrt` are
one rule, not a name rule and a member exception to it. The alternative was tried and is worse than
it looks — if a lone member arrived unasked, then *adding* a trait of your own with that name would
silently change which implementation an existing call meant, since your trait is in scope and the
library's is not.

This is what keeps an `impl` for a built-in from spending names. `sysl.math`'s `Float` declares some
forty-one members on `real` and `f32`, and a program that never imports the module may still declare
a trait of its own with any of those names and implement it for either width. Where the program does
import the module, both members are in scope at once and a **bound** is what says which one a body
means.

**The escape stops exactly where `08` does, and `guide/fft` is where that showed up.** A trait's
members become the implementing type's, so a name is free only if the type does not already declare
one — the rule two paragraphs down, seen from the other side. That program wanted a `trait Zero` with
a `zero() -> Self` and implements it for `real` and for `sysl.math.complex`'s `Complex`; the first is
fine, and the second is refused, because `Complex` declares a `zero` in its own body. It names the
member `identity` instead. A trait a program writes for types it did not write has to find a name
still free on **all** of them, and the more ordinary the concept the likelier that it is not.

**Two traits may therefore give one type a member of one name, and three things tell them apart, in
this order.** A **bound** answers first, because inside a generic body the signature already said
which trait was being asked for — and at an instantiation the parameter has become an ordinary type
whose table holds both. **Scope** answers next, and is the only thing that *can* answer for two
members that take no arguments: `zero()` and `zero()` differ in nothing a call writes. The
**arguments** answer last, which is `08`'s existing rule for two implementations of one trait, now
reached only once the first two have narrowed the set. A use that still reaches two is reported
where it is written, naming both traits.

What does **not** get a scope to be told apart by is a member of the type's **own** body: it is
reachable wherever the type is, so a trait may not give a type a name its own declaration already
used, and that is refused at the `impl`.

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

**So the rule binds in exactly two places, and both are §7's module storage.** A module-level `val`
and a `static var` are the declarations whose types could have been inferred from their initializers
and are not: each is refused without one, by name. This paragraph used to say the rule bound
*nowhere* — that a top-level `var` was only ever a local of the entry point, so nothing was left for
the rule to hold — and it was written before `static` existed. The moment a binding could be a
member of its module, the rule it was stated in advance for started binding, which is what stating it
in advance was for.

**It bites harder on the `var`**, and that is the case worth having written the rule down for: a
`static var`'s initializer **may be absent** (§7), so there is not merely a value the rule declines
to read, there is often no value at all. `static var slot: int` is a complete declaration of storage
and the type is the only thing that says what storage.

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
import std.fs.read as rd           // rd(p)            — the same, unbraced
import std.fs                      // fs.read(p)       — the module itself, member access by name
import std.fs as f                 // f.read(p)        — the module under a shorter name
```

The selective `{a, b}`, the wildcard `*`, and both spellings of the rename are Scala 3's import
syntax unchanged. The **unbraced** `as` belongs to the bare-path form alone, where exactly one thing
is being named: after a wildcard there is nothing for one word to rename, and a selector list
carries its own `as` per name, so both are refused rather than quietly ignored. It renames whatever
the path turned out to name — a member or a module — because which of the two it is is settled by
the same longest-prefix rule everything else here uses, and a reader wanting a shorter word should
not have to know the answer first. Renaming a module is the case the braced form cannot express at
all: there is no selector list to hang it on. `import std.fs` (the last form) brings the module *name* `fs` into scope, so its
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

**The standard module counts as one of those wildcards.** `sysl` is auto-imported into every file
(§8), so a written `import a.*` where `a` declares an `Option` of its own is the two-wildcard case
and an unqualified `Option` in that file is a compile error naming both — it does not shadow the
library's. This is deliberate rather than a consequence nobody wanted: the alternative is a
precedence tier that makes the library quietly lose to whatever a program imported, which is the
same silent-capture problem an explicit conflict is being reported to avoid. The two fixes are the
ordinary ones, `a.Option` or `import a.Option`.

An unqualified name is looked for **in the module it is written in, then among the file's imports,
then in the library**, and nowhere else — a sibling module's names are not in scope unqualified, and
neither are the root module's, which have no path to be reached by at all (§1). The standard module
is reachable two ways and they agree: it is auto-imported into every file, so it answers at the
import step and takes part in the wildcard rules above, and it is also the last step on its own, so
a name it declares resolves even where a file's imports say nothing.

**The three steps rank a name by where it was written, not by what kind of thing it is.** A
function, a `const`, a module-level `val`, an `extern` variable and an enum variant are five tables,
and a bare name may be any of them — but a program's own declaration answers before an import's and
an import's before the library's *whichever* table each is in. So a program that declares `extern
"__stdoutp" stdout: *u8` reaches C's stream, though the library declares a `stdout()` of its own, and
the library's is still there under `sysl.stdout`. The rule is worth stating because the tables can
only be asked one at a time, and asking them in a fixed order is precisely how a nearer declaration
loses to a further one — the reader is looking at a declaration on their own screen, and nothing
about a name says which table will claim it.

**The library's own files take the same three steps.** They are files of modules, and the module each
is in is one of the library's, so "this module first" can only ever hand a library file the library's
own declaration — there is nothing for a special order to protect it from. A file of the library may
therefore also *import*, which is what a library of more than one module needs: `sysl.io` opens with
`import sysl.sys.{sysl_memchr, sysl_read}` and says what it needs from the platform exactly as any
other file would. Only the standard module is auto-imported (§8); a submodule of it is an offer like
any other library's.

**Every step is filtered by §2, the library's included.** A member the library keeps to itself is not
an answer to a program's bare name, exactly as a sibling file's private helper is not: the search
passes over it and goes on, and only where nothing else answers at all is the restriction reported —
at which point it is the whole story, and better than an undefined name. This is what keeps the two
spellings of one declaration from disagreeing. The library is the step where it would be easiest to
lose, because it is the one a program reaches without writing anything, so a name that resolved there
is a name nothing was asked about.

A **dotted**
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

- **A capability attribute narrows the module**, and it is written in the **file header** below
  `module`. Because the module is the directory (§1) and the capability is a property of the module,
  a narrowing **must appear consistently in every file of the module** — the compiler
  rejects a module whose files disagree. The redundancy buys local legibility: you can never open
  a file in a `@no_alloc` module and fail to see that it is one.
- **They are attributes rather than grammar, and that is what keeps the words.** `@no_alloc` and
  `@requires(...)` are written in the notation `@test` and `@tailrec` already used, and an
  attribute's name is an ordinary identifier — so `no`, `alloc`, `requires` and `link` all remain
  available to a program. Spelled as keywords they were reserved, and `guide/slab` had to call its
  allocator's central function `take` because `alloc` was taken.
- **Each takes a line of its own**, below the header rather than beside it, which is what
  keeps `module oskit.arch @no_alloc @requires(os)` from being a line anyone has to read. A file that
  declares **no** module may still carry one, since the anonymous root module of §1 is a module like
  any other and a one-file program is exactly that case.

```
// oskit/arch/cpu.sysl          // oskit/arch/mmu.sysl
module oskit.arch               module oskit.arch
@no_alloc                       @no_alloc           // same attribute, enforced identical
```

- **`@requires(alloc)`** (and the other direction) is likewise a module-header attribute, documenting
  and early-diagnosing a hard dependency, per `capabilities.md`. It takes a **list**, because a
  module often needs more than one at once — `sysl.thread` is `@requires(threads, posix)` — where a
  narrowing gives up one at a time.
- **The header has one other inhabitant, and it is deliberately not held to agreeing.** `@link("z")`
  (`15 §8`) names a library the file's `extern`s need, and the files of a module may each name their
  own. The rule differs because what is being described does: a capability is a property of the whole
  module, so files that disagreed would describe different modules, while a link requirement is a
  property of the `extern`s in *one* file — and a module whose foreign declarations all sit together
  has nothing for its other files to repeat.
- **Propagation is over the module graph.** A module's effective requirement is its own uses plus
  the requirements of every module it imports, transitively, and the whole graph must fit the
  target (`capabilities.md`). A `no alloc` module importing a `requires alloc` module is an error
  at the import, not deep in codegen. Because the module import graph is acyclic (§6), the
  propagation is a **single sweep in reverse topological order** — each module's requirement set is
  final before any importer of it is visited — not an iterated fixpoint. The fixpoint that escape
  analysis uses for mutual recursion (`05`) is still needed *within* a module, where sibling files
  share one scope and may call each other freely; it is the cross-module direction that the DAG
  removes.
- **For `alloc`, the check is finer than the declaration, and the standard library is why.** The
  paragraph above describes what a *declared* requirement does, and `alloc` is mostly not declared —
  it is inferred from use. Inferring it per module would put the whole of `sysl` on one side of a
  line that runs through the middle of it, since `print` allocates nothing and `from_utf8` does, so
  the inferred half is asked of **what a module calls** rather than of which modules it depends on.
  `capabilities.md § Propagation` carries the argument; what belongs here is that this does not
  weaken §6, which is still what makes a *declared* requirement answerable in one sweep.
- **For `os` and `posix`, the check is exactly the declaration, and it is asked of the graph this
  chapter is about.** These gate which modules *exist* rather than what the language allows, so
  there is nothing finer than a module to ask about: every declaration of `sysl.fs` is equally out
  of reach of a module that wrote `no os`. The edge the rule is stated over is the **reference**
  graph of §6 rather than the import graph, and that is load-bearing — a qualified path reaches
  another module with no import at all (§3), so a rule about imports would have missed the shorter
  of the two ways to write the mistake. The diagnostic lands at the reference for the same reason
  `alloc`'s lands at the call: that is the line a reader has to change.

## 5. Platform selection rides the same name

A module's members may be **split across platform-specific files** — `cpu.aarch64.sysl` and
`cpu.x86_64.sysl` contributing to the same `oskit.arch` — with the active target selecting which
file contributes. The **module name is unchanged by the platform suffix**: importers write
`import oskit.arch` and name `oskit.arch` members regardless of which platform's file is compiled
in, which is what lets the kernel import one arch module and get whichever platform it was built
for (`cross-platform.md`, and the pattern the old compiler reached for with its path-prefix
relaxation).

The **exact suffix grammar and the resolution rule** — which filename shapes mark a platform, how
the active target is chosen, and the `package.hocon` schema that ties it together — belong to
**`packages.md`**, which now carries the schema; the suffix grammar itself is the one part of that
promise still unwritten. This chapter fixes
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

The **anonymous root module** (§1) sits outside the graph: it can be depended on by nothing, having
no name for another module to write — a program's root files depend on the modules beneath them, and
the dependency never runs back.

**The standard module does not.** Writing `print` records an edge on `sysl` like any other reference,
and that is deliberate rather than an oversight in the rule. For a program the edge is inert — nothing
in the library can point back at a program's module, so it can never close a cycle — but *within the
library* it is the whole of what keeps the split honest: a submodule of `sysl` that uses the free
names depends on `sysl`, so `sysl` may not turn round and depend on it. That is a real constraint and
it decides the library's own layout. `sysl` reaches `sysl.sys` for the C functions its printing is
built on, so `sysl.sys` may name nothing of `sysl`'s — which is why it holds the externs and nothing
else, and why the argument conversion, which calls `buf`, `print` and `exit`, is `sysl.args` and not
part of `sys`. A module that depends on nothing is what a platform module should be anyway; the rule
is what says so out loud.

**The rule reads forward as well as backward, and that is what ordered the rest of the library.** A
submodule may use the free names as freely as it likes so long as `sysl` does not name it back, so
the question to ask of a candidate is not what it needs but who needs *it*. `sysl.io` calls `print`,
`exit` and `buf` and is none the worse for it, because nothing the language desugars onto reads. But
it also calls `from_utf8`, and while `line_text` was still a declaration of `sysl`'s the validator
could not leave either — `sysl.text` had a caller in `sysl`, and moving it would have closed a cycle.
Moving the reading surface first is what freed it. Which surface moves first is therefore decided by
the graph and not by which one is tidiest to move.

The library's tree as it stands, read as edges: `sysl.sys` needs nothing; `sysl` reaches `sysl.sys`;
`sysl.buf` reaches `sysl`; `sysl.text` reaches `sysl` and `sysl.buf`; `sysl.io` reaches all four;
`sysl.args` reaches `sysl`, `sysl.buf` and `sysl.text`; `sysl.fs` reaches `sysl`, `sysl.buf`,
`sysl.text` and `sysl.io`. Every edge runs away from the standard module and none runs back, which is
what makes any of them removable from a program that never asks.

**`sysl.fs` is where the forward rule paid for itself a second time.** It holds the externs it needs
rather than putting them in `sysl.sys`, which the "every declaration that is not sysl" convention
would have suggested — because they are the only ones in the library that need an *operating system*
under them rather than merely a C library, and `sysl.sys` carrying them would have meant either
`sysl.sys requires os`, dragging printing and reading in behind it, or a module whose header says
less than its contents do. A capability clause is a property of the module (§4), so what a module may
truthfully claim is part of what decides where a declaration lives.

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

That file is the program's **entry file**, and the rest of this section is about what its top level
means, because the answer is not the same as it is anywhere else.

### The entry file is a body

**What the entry file declares is local to it.** Its top level is a body: a `val` or `var` there is a
stack local, initialized where it is written and in the order the statements around it run, and a
function there is a nested function (`12 §5a`), which reads and writes the bindings above it because
its environment holds their addresses.

That is one rule where there were two, and the seam it closes had been visible for a long time: a
top-level `var` was a local of the entry point that no function could see, while a top-level `val`
beside it was a module member that every function could — a distinction with a reason nobody could
give, because there was none. **Which file the program starts in is decided by statements; what that
file's declarations mean is decided by which file it is.** Those two questions had one answer and
needed two.

It is also what makes a sequence a sequence. A script that binds a value, sets it up with a
statement, and binds something derived from it used to run the second binding *first* — the `val`s
were module members, filled before any statement ran, with nothing in the source suggesting an order
was involved. Written as locals they run where they stand, which is what the program looks like it
does.

**A helper pays nothing for this unless it uses it.** Whether a function at the top of the entry file
belongs to the body is settled by whether it reads one of the body's bindings, or calls something
that does — capture is transitive, since the nested functions of a block share one environment. One
that reads none is an ordinary module function, with everything that goes with it: it may be generic,
its address may be taken, it may be passed as a value, and another file may call it. `12 §5a` says
why that has to be so — "one that captures nothing and does not escape is an ordinary static function
with a private name" — so the three things a nested function cannot be are what holding a frame
costs, not what being written in the entry file costs. A comparison handed to C's `qsort` is the case
that makes it concrete: it reads nothing, and refusing its address for the frame it reads would name
a frame that does not exist.

**`main` is never one of the body's, however it is written.** It is not a name the program calls but
the one the *platform* calls, so there is no caller inside the body to have formed an environment for
it.

### `static` — asking for the module instead

A `val` in the entry file that should belong to the **module** says so:

```
static val table: [3]int = [1, 2, 3]
```

It is then hoisted, laid into the object file, visible under §2's ordinary rules, and initialized
before any statement runs — which is also why its initializer may not call a helper that reads the
body: at that moment there is no body yet.

**It is meaningful in exactly one file per program**, since only the entry file has a body for a
declaration to *not* belong to. In a file with a `module` header, or a headerless file carrying no
statements, everything is the module's already, and the modifier is refused rather than accepted as a
no-op. A function never takes it either: settled by what it reads, the modifier would be redundant on
one that reads nothing and impossible on one that reads a binding, since a frame is the one thing a
module member cannot have.

**`static var` is the same storage, written.** It takes from the `val` everything about being a
module member — visibility, the shared value namespace, the initializer dependency graph and its
cycle diagnostic, hoisting — and adds the two things the word `var` already means: assignment at
every depth, and `&`. `&k[0]` on a `val` is refused because a `val` promises its storage is written
once and that is where the promise is kept; a module `var` promises nothing.

**That list was a claim rather than a description in one place, and the probe pass caught it.** The
cycle diagnostic looked a name up among the `val`s alone, so a cycle running through a `var` threw
where it should have explained — the compiler dying on the program it was meant to be reporting on.
It had stood since `static var` landed, because every cycle anyone had written was between two
`val`s. Fixed and pinned at both spellings and at one of each.

**Everywhere else it is spelled `var`, with no modifier, and it is the same declaration.** The two
spellings follow from the paragraph above rather than adding anything to it: `static` asks for the
module *instead of the body*, so it is needed exactly where there is a body to be asking about, and
that is one file per program. In any other file — a `module` header, or a headerless file that is
not the one carrying the statements — a top-level `var` is the module's storage because there is
nothing else it could be.

```
// counter/c.sysl
module counter

var count: int = 0

bump() = count += 1
```

**A `var` therefore does not decide which file the program starts in.** That question is answered by
what a file *runs*, and a declaration of storage runs nothing — a file holding a counter is no more
the program's beginning than one holding a table (`§7`, *the entry file is a body*). The entry file's
own top-level `var` is untouched by this and is still a local of its body, which is the distinction
the modifier exists to cross.

This was unwritable until 2026-08-05, and the gap was found by `17 §5` — its module-invariant example
was written against exactly this spelling and did not compile, because a `var` outside the entry file
was read as a statement and refused as a second beginning. The rule stated here is what the chapter
had assumed all along.

It is stricter in exactly one place, and it is the subtle one. **It may not hold a value that owes a
release, and the question is asked of the TYPE where a `val`'s is asked of the VALUE.** A `val` is
forever the value it was given, so `static val greeting: string = "hello"` is admissible — a
literal's owner word is null and nothing was ever built. A `static var` could be given that literal
and `str(n)` on the next line, and whatever it holds when the program ends has nowhere to write its
release, so the question is about what the storage may *ever* hold.

Its **initializer may be absent**, which a `val`'s may not: a variable with no value is still a
complete declaration of storage, and the type's zero is what it starts at. That is the cheapest form
— `zeroinitializer` and no store at all — and the one an arena wants. Its **type is mandatory** for
the same reason a `val`'s is (`§2`), and it bites harder here, since there may be no initializer for
one to be inferred from.

It takes a **visibility** like any other module member (`§2`), in both spellings: `private var count:
int = 0` keeps a module's state inside the file that carries the functions maintaining it, which is
what a module with state usually wants and what the `val` beside it could always say. Only the
`static var` form parsed one until 2026-08-06, and the plain form's refusal was the grammar's
complaint about the word after the modifier rather than a rule — `private static var` had worked all
along, and the two are one declaration.

`guide/slab` was the customer and is now the demonstration: two regions, one address each for the
life of the program, declared where they are carved rather than threaded as a `*u8` through six
sections. What it still cannot say is what address a region should *start* at — module storage has no
way to state an alignment, so the allocator rounds up and pays for it out of the region.

A program in which no file carries a statement is a complete program that does nothing: the entry
point exists, runs nothing, and succeeds. That is what a tree of pure declarations compiles to,
which is what it should compile to — a library is not an error.

**And a file that names a module is not the beginning of it, which is the case that has to be said
out loud rather than left to follow.** Where nothing runs anywhere, a lone file of bindings is a body
after all — that is what keeps a one-file `var n = 1` meaning what it has always meant — but the
sentence at the top of this section is what bounds that: only a file *with no header* has a body for
a binding to belong to instead. A library is exactly the shape that tests it, since a library is
files and no beginning; and the failure did not appear at the `var`. The file became a body, so every
function **reading** the `var` became a nested function of it, and was refused for the two things a
nested function may not be — `private`, and generic. Which meant a module holding state could compile
when a program imported it, because the program supplied a beginning, and be refused by `build-lib`,
where there is none. Found on 2026-08-06 by the first library that kept state.

### `main` — the named form of the entry point

A program may instead declare a function called `main`:

```
main(args: []string)
    for a in args[1..]
        print(a)
```

**A program starts in one place, and these are two ways of writing it, so a program writes one or the
other.** Writing both is an error naming the file that already carries the statements: it would be
two entry points with an order between them to remember, which is what it reads as to anybody who
opens it, and whichever of the two the program means, the other belongs inside it.

What `main` has that statements do not is a **parameter list**, which is the whole reason the form
exists — so a program that wants its arguments writes `main` and puts inside it what it would
otherwise have written above.

**What `main` gets at that a statement cannot is the arguments.** A statement at the top of a file
has nowhere to receive them: it is not a call, so it has no parameter list, and a program's arguments
are not a module-level anything — they are what this run of this program was started with. A named
function does have a parameter list, which is the whole reason for the form.

The signatures are these, and there are no others:

```
main()
main(args: []string)
main() -> Result[unit, E]
main(args: []string) -> Result[unit, E]
```

A `main` that asks for nothing is for the program that has work to do and no arguments to read; it
costs nothing, since the conversion below is reached only by the other one.

**A `main` may answer with a `Result[unit, E]`, and that is what lets `?` reach the top of a
program.** Without it every fallible call in `main` ends in `.unwrap()`, which reports a failure as a
panic naming the line that gave up rather than the thing that went wrong; with it the error travels
as a value to the end of the program and is reported once, on stderr, with a non-zero exit status.

Three things about that form are decided rather than incidental:

- **The `unit` is not decoration.** A value `main` answered with would have nowhere to go, since what
  the platform takes is a status and not a value. `Result[int, E]` is refused for that reason rather
  than quietly using the integer.
- **`E` must be `Display`.** The report is the whole point, and an error nobody can render would exit
  non-zero having said nothing. The bound is checked where `main` is written.
- **The status is `1`, not something read off the error.** A status is one byte and an error is a
  value; mapping one onto the other is the program's business, and a program that wants to choose its
  own has `exit` and always did.

The reporting is `sysl.main_result`, an ordinary library function instantiated at whatever error type
`main` named — the same arrangement as `args_of` below, and for the same reason: the entry point
carries as little hand-written code as the platform allows.

**`args` is a slice of `string`.** What the platform hands a program is C's pair — a count, and a
vector of NUL-terminated byte runs — and neither of those appears in a sysl signature anywhere. The
pair is converted by the library's `sysl.args.args_of`, which finds each run's end, validates its
bytes, and **copies** them into strings the program owns: an argument therefore outlives the vector it
came from and holds no memory the platform is still responsible for. The zeroth element is the
program's own path, because that is what the platform passes and withholding it would be inventing a
different convention than every other language's.

It sits in a submodule and not in `sysl`, so `args_of` is not a word every file has: a `main(args:
[]string)` reaches it without naming anything, because the entry point names it by key rather than by
resolving the word, and a program handed an `argv` by something *other* than the platform writes the
path. Which is what a submodule is for — the free names are the ones a program cannot avoid needing.

**An argument that is not UTF-8 stops the program**, with the offset of the byte that made it
ill-formed. `04` puts the validation of bytes computed at run time at the boundary they arrive
through, and this is one of those boundaries; a `string` that might not be text would push the
question into every program instead. A program that genuinely needs the raw bytes wants an
`args_bytes() -> [][]u8` beside this, which nothing has asked for yet.

**`main` names one function in a program**, wherever it is written. Two of them — even in two
different modules, where nothing else would collide — is an error naming both, for the reason C
reserves the name: it is not a name the program calls, it is the name the *platform* calls, so two
would leave which one the program **is** to whichever was emitted last. Otherwise it is an ordinary
function: it may be called by the program too, and then it runs both times.

**A result is not yet spelled.** `main() -> int` is refused rather than read as an exit status,
because a program's exit status is a whole question — which values mean what, what a trap leaves
behind, whether a `val` initializer can set one — and reading a result type as one now would answer
it by accident. Until it is decided, a program that must choose its status calls `exit`.

### Constants

A **`const` is a module member**:

```
const capacity: usize = 512
const max_slots: usize = 32
private const window: int = 1 << 15
```

It is a declaration wherever it is written, including the entry file, since it is folded into its
uses before anything runs and so has nothing a body could make it local to. The three are told apart
by what they are rather than by immutability: a `const` never runs at all, a `val` is storage, and a
`var` is storage the program writes. Which of the last two belong to the module and which to the
entry file's body is what the section above settles.

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
resolution rather than adding a second one. A **`val`** is the one module-level name that could
have brought the trap back, since it is read while running and so has no value for a pattern to
compare against; naming one in a pattern is therefore an error rather than a quiet binding.

**A fourth place it stands: the bounds of a `within` range.** `type Slot = u8 within 0..<max_tasks` is
ordinary, so a program that indexes a fixed table by a constrained integer writes the table's size
**once** — the array bound and the range are the same constant, and there is no pair of numbers left to
disagree. That was the last magic number a table-driven program carried, and `guide/kernel` was the
customer.

The three positions a constant can size — an array bound, an enum discriminant, and a range bound — go
through the **same fold**, which is the property worth keeping: they accept the same expressions because
they ask the same question, rather than three grammars agreeing by coincidence. What a range bound still
refuses is a name that is not a constant, and a **`val`** is the instructive case — it is read while
running, so it cannot size a type any more than it can stand in a pattern. Recorded from the other side
in `16`, where the rest of the constrained-subtype design lives.

### `@assert` — a condition settled while compiling

```
@assert(sizeof(FRect) == 16, "FRect must match SDL_FRect")
@assert(max_tasks <= 64)
```

**The condition is a constant expression**, exactly the set above, folded by the same machinery a
`const` initializer goes through — so it may name constants, `sizeof`, `alignof`, and the arithmetic
and comparisons over them, and it may name a constant declared below it. A false one is a compile
error quoting the message; a true one emits nothing at all. The message is optional and is the
*reader's* sentence, because they know what the number means and the expression alone says only that
two of them differ.

**It exists because `require` is a runtime check and there was no compile-time one.** `17` is
explicit that a `require` is still compiled, still branches and still traps, so nothing in the
language could fail a build on a fact already known while compiling. That is a gap on its own terms
— a table whose size a protocol fixes had no way to say so — but the case that forced it is a
binding to C.

**What it buys: a C struct layout that is CHECKED rather than transcribed.** `15 §1` lays every
struct out in declaration order and claims C compatibility by construction, and `15 §7` refuses
transcription on the grounds that nothing verifies the claim — *"sysl's own `sizeof` would report
what sysl laid out, not what C did, so even that comparison is a tautology."* It stops being a
tautology once both sides are pinned to the same number. The C half was always writable, since a
`.c` in the tree is compiled with it and for the same target:

```c
_Static_assert(sizeof(SDL_FRect) == 16, "SDL_FRect size moved");
_Static_assert(offsetof(SDL_FRect, w) == 8, "SDL_FRect.w moved");
```

That pins what the *header* says. `@assert(sizeof(FRect) == 16)` pins what *sysl* laid out. Neither
can drift without the build stopping, and the number is in the source where a reader can see it.

**An attribute rather than a word**, for the reason the capability clauses are (`§4`): it says
something *about* the module rather than being a construct the language executes. A reserved word
would also have cost the lexer, the reference's reserved-word table and its stated count, and the
highlighting grammar, to buy nothing the sigil does not.

**It declares no name**, which is why it is not in the visibility table and why two saying the same
thing are two checks rather than a duplicate. Nothing can refer to one.

**A constant has no address.** It is folded into each use and occupies no storage, which is why it
needs no initialization order, why a `no alloc` module may hold one, and why `&capacity` is not a
thing to write. That is also exactly what rules it out for the *other* half of what the guide
programs asked for — a **table**, which is indexed at a value only known while running and
therefore has to be somewhere. The answer to that is not "make `const` bigger"; it is a second
declaration, below.

Two things a constant is **not**, so the boundaries stay where the rest of the design put them:

- **Not an enumeration.** A set of related named values is a simple enum, which `09` argues for at
  length as the type-safe replacement for a pile of `const`s. A constant is one dimension, not a
  family of them; if a second constant would be the obvious neighbour of the first, the declaration
  wanted was an `enum`.
- **Not value generics.** Parameterizing over a length is `10 §9` — but it is worth recording that
  this is its prerequisite, since a value cannot be passed as an argument before it can be named.
  That chapter takes the point further than it was left here: a
  value **parameter** is spelled `[const N: usize]`, which is this declaration with the initializer
  left to the caller, so the two are one idea in two positions rather than two features.

A cycle between constants (`const a: int = b`, `const b: int = a`) is reported at the declaration,
naming the loop, in the same way §6 reports one between modules.

### `val` — a binding written once

A **`val` is a thing**, where a `const` is a value:

```
private val order: [19]usize = [16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15]
```

Written at the top of a file it is a module member — read-only storage that exists for the whole
run — and written inside a block it is a local, the immutable counterpart of `var`, in the same
frame with the same lifetime. One keyword at both levels, because it is one idea at both: a name
bound once and never assigned to again.

**The whole difference from a `const` is an address.** A constant is folded into every use, which
is what lets it size an array and what stops it from being one. A `val` sits somewhere, so it may
be indexed at a value only known while running, iterated, and reached into. The rule for a reader
is short: *if it has to be indexed or pointed at, it is a `val`.*

**Read-only means read-only at every depth.** `k = …`, `k[0] = …`, `k[0] += 1`, and `k[0]++` are
all refused, and so is `&k[0]` — a `*T` is a licence to write, and handing one out would move the
mistake one step away from where it could still be reported.

**Slicing is allowed, and yields a `[]const T`** (`07 § A view that may not be written`). It was
refused for exactly as long as there was no type that could carry the property: a plain `[]T`
permits writes and records nothing about whose elements it views, so the view would have been a
route around the paragraph above, and one the view outlives the expression to take. A `[]const T`
records it, so the property travels with the view — through a name, through a call, and through a
second subscript — and every *write* listed above is refused through it too. This is what lets a
table be **read and passed** rather than only read, which is the one thing a `val` used to cost.

`&` is the one refusal above that does **not** carry over to the view. `&k[0]` on the `val` stays
refused, because that is where the promise was made and where it can still be kept; `&v[0]` on a
view of it is a `*T`, the tier `03` excludes on purpose, and is how the view reaches C. Slicing is
the step between the two, and it is written down.

**A module-level `val` may not hold a value that owes a release.** The reason is the one thing that
is true of module storage and of no other storage: it exists for the whole run and is therefore never
let go of, so a count taken in one is a count with no line to write the release on. A `&T`, a
`weak T`, a slice and a built `string` are each refused for it.

**The question is asked of the value, not of the type**, and the difference is the whole of what the
rule is worth. A value the object file carries as it stands was never built and takes no count, so a
counted *type* is admissible exactly when its initializer is a constant tree by the rule below. A
string literal is the case this exists for: `04` decided that a literal's owner word is null and that
both retain and release test for it, so `val greeting: string = "hello"` is three words in read-only
data with no count anywhere in them, while `val greeting: string = str(n)` is a count with nowhere to
write the release. The two are told apart by the same test that decides whether any `val` is laid
into the object file, which is why this is one rule and not two.

```
val messages: [3]string = ["out of range", "not permitted", "no such device"]
```

That shape is what asked for the relaxation. A module with `no alloc` (§4) can hold, index, slice and
compare literals but cannot make bytes, so a table of messages was the one thing it could name only
by reaching for a `const` — which is folded into its uses and has no address, so it cannot be indexed
at a position computed while running. That is the whole difference between the two declarations, and
a message table needs the half a `const` does not have.

**A struct is admitted by the same recursion**, so a table of `{name, code}` pairs is one declaration
rather than two parallel arrays. It follows that a struct carrying an `invariant` may **not** hold a
literal string: a value that has to be checked is code by the rule below, and code takes a count. That
is the specification reading consistently rather than a hole in it, but it is the corner worth knowing
about before writing a device table with a constraint on it.

**A joined string is refused, however constant its parts look.** `"a" + "b"` allocates, and folding it
into one literal is separate work; admitting it on the strength of how it reads would be admitting a
leak. The same holds for `str(x)` and, for now, for a slice of a literal — `"hello"[1..]` genuinely is
immortal, but seeing that requires folding the slice arithmetic.

**A raw pointer may be held, and so may the address of a function.**

```
const UART: usize = 0x1000_0000
val regs: *Uart = ptr_cast(UART)
```

A `*T` counts nothing, so the reason above does not reach it: there is no release to write. What a
`val` promises is that **its own storage** is written once and never again — the ordinary meaning the
word carries — and holding an address keeps that promise exactly as holding a number does.

It was refused once, on the read-only-at-every-depth rule above, and that argument does not survive
being looked at. Read-only at every depth is a promise about the storage this declaration lays down, and it
is kept where it is made: `k[0] = …` and `&k[0]` are both refused *there*. It was never a promise
about what a value inside the storage addresses, and it could not be — slicing a `val` and writing
`&v[0]` yields a `*T` today, on purpose, and is how a table reaches C (`07 § A view that may not be
written`). Refusing `val p: *T` declined one route to what another route already grants, which buys a
program nothing and costs it the one shape that has no substitute: **a device register block named at
file scope**, reached by every function in the driver rather than re-materialised in each.

The raw tier is where a `*T` lands, here as everywhere: what it addresses, whether that is still
there, and whether anyone else is writing it are the programmer's to know (`03 § Reinterpreting
storage`).

**An address that is a constant is laid into the object file**, not stored by a prologue. `ptr_cast`
over a constant is a constant tree by the rule below, so a `val` at pointer type needs nothing ordered
and is readable before the first initializer runs — which is what a freestanding program starting at a
reset vector requires, and it would be no use if the register block were filled in by code that had to
run first.

**The type is written at module level and inferred inside a block.** Not two rules but one: §2 says
anything visible outside its file states its types, and a module member always could be. A local
states nothing to anyone, so it infers exactly as a `var` does.

**The one module-level storage these rules do not reach is an `extern` variable** (`12 §1`), and the
reason is what the rules are about. Every paragraph above is a promise this program makes about
storage it laid down: that it is written once, that it is read-only at every depth, that nothing
counted sits in it unreleased. An `extern` variable is storage the *linker* supplies — `stdout`,
`environ`, `optind` — and this program lays none of it down, so there is no such promise to keep and
none of these refusals to carry over. It is a place, it may be written, and it holds whatever the
other side put there. That is the foreign seam behaving like the foreign seam, and it is written
down here so a reader who finds `optind = 1` compiling does not read it as a hole in this section.

#### What order the initializers run in

**An initializer may be computed**, and `guide/png`'s CRC table is what asked for it: 256 entries
derived from a polynomial, which no constant tree could ever have carried.

```
private val crc_table: [256]u32 = build_crc_table()
```

The two forms are told apart by what the initializer *is*, not by how it is written. A constant tree
— a number, a string literal, a struct or tuple built from constant trees, an array of them, a repeat
`[v; n]` (`07`), `null`, and a `ptr_cast` of any of those — is laid straight into the object file,
runs nothing, and needs nothing ordered. Anything else is code, and code runs somewhere.

A **text block** is a string literal by the time this rule sees it — the lexer joins one into a single
constant (`04 § Text blocks`) — so it needs no clause of its own. This is also the test the paragraph
above asks: a counted type is admissible in a module `val` exactly when its initializer is one of
these, since a value that was never built owes no release.

**A value that has to be *checked* is code, whatever it looks like.** A `val` at a constrained type
(`16`) is written as a plain number and would otherwise be laid down by the rule above — which would
make it the one produce site in the language that skipped its check, since a global has nowhere to
run one. So the constraint decides: `val a: Age = 200` is storage the program fills, the range check
runs there, and an out-of-range one stops the program before any statement of its own. The same holds
for a `where` predicate, for a struct `invariant`, and element by element for a table of a constrained
type. This is not a second rule so much as the first one read correctly — a check is code, and code
runs somewhere.

**Where it runs is the program's own entry point**, ahead of the statements §7 pins to one file.
That is the one moment that certainly comes first and is already written down; a platform's
constructor section would run before the runtime was up, be spelled differently on every target, and
hide the order somewhere a reader could not look.

**What order they run in is the order their own dependencies describe.** A `val` is filled after
every `val` it needs, and it needs the ones its initializer reads — directly, or through anything
that initializer calls. That is a graph over the `val`s themselves, which is finer than the module
graph in exactly the direction that matters: a module's own files have no order at all (§6), so
ordering by module could never have settled two tables in one directory.

§6 is what makes the rule usable, and in a way worth saying out loud. A reference to another
module's `val` follows a module edge, and module edges may not cycle — so **a cycle among `val`s can
only ever be inside a single module**. A diagnostic about one names declarations that are all in one
directory and usually in one file, which is why the rule needs no cross-module story beyond the one
§6 already tells:

```
'a' cannot be initialized: its value needs 'b', whose value needs 'a' — a computed 'val'
runs once before anything else does, so what it needs has to be settled first
```

**A call through a method table is followed too**, and that is not a special case so much as the
absence of one. Which function a `&Trait` lands in is not known while the order is being computed,
but the whole program is compiled together, so the *set* it could land in is: every table for that
trait supplies one function per slot. Taking their union cannot miss a read. Refusing dynamic
dispatch inside an initializer would have been the alternative, and it would have been a restriction
with no reason behind it.

**The rule outlives whole-program compilation, which is why it can be stated now.** Today the driver
has every module in front of it, so the order is one sort over one list. `15 §6` defers an
incremental build, and the shape of the answer there is already fixed by this one: since a
cross-module edge follows a module edge and those may not cycle, each module's initializers can be
sorted *within* the module at the time that module is compiled, emitted as one init function, and
called by the driver in the same dependency order §6 already compiles in. Nothing has to be
recomputed across the seam, and no module needs another's bodies to settle its own order — which is
this chapter's through-line rather than a concession to it.

Two things follow that are worth having in mind rather than being rules of their own. An initializer
runs code, so it may allocate on its way to a value even though the value itself may not carry a
reference — which is a question for the `no alloc` capability (§4) rather than for this. That
capability is built, and it already answers this case: an initializer is code like any other, so a
module that declared `no alloc` is refused one that allocates on its way to a value, at the smallest
part of the initializer that still reaches an allocator.
And an initializer that traps takes the program down before any statement of its own has run, which
is what "before anything else" has to mean if it means anything at all.

A `const`, a `val`, and an enum variant share one namespace, since all three are values a bare name
reaches; a clash is reported at whichever was written second.

## 8. Separate compilation — a library is compiled once and linked

A library is built into one file and linked against, rather than recompiled by everything that uses
it:

```
sysl build-lib mylib -o mylib.syslib     # compile the library once
sysl run prog.sysl --lib mylib.syslib    # link a program against it
```

**`--lib` takes either an artifact or a source tree**, and which one is read off the name. How a
library shipped is the shipper's business; a program that depends on one should not have to write
down which form it got. Given a source tree the library is simply *more modules* — its files carry
the directory segments they were found under exactly as a program's do, and §1's rules do the rest.

**An artifact has two halves, and the split is the whole design.** A declaration with no type
parameters is compiled ahead of time into object code, by whoever built the library; a program that
calls it *declares* the symbol and links the body. A **generic** has nothing to compile until a
caller fixes its type arguments, so it crosses as the tree it was parsed into and is monomorphized
in the consuming program. Rust's `.rlib` makes the same split, carrying MIR in `lib.rmeta` for
exactly this reason.

The metadata carries **every** declaration, not only the generic ones: a call into the precompiled
half still has to be type-checked, and the tree is where the signature is. What the symbol list adds
is which of those the consumer must declare rather than emit a second time.

Five consequences worth stating, because each is a thing a reader would otherwise have to discover:

- **An artifact is for one machine**, exactly as an `.rlib` is, and **both** halves pin it. The
  object half obviously does. The tree half does since a library may gate on the machine it is built
  for (`targets.md § Conditional compilation`), which makes two artifacts built from one source two
  different sets of declarations. So an artifact records the target it was built for and is refused
  by a build for another — refused rather than left to the linker, which would eventually complain
  about object formats in a message saying nothing about which library or why, and which would not
  fire at all for a mismatch that only reached the trees.
- **A library carries no entry point.** A `main` of its own would collide with the one belonging to
  whatever links it.
- **Nothing is pruned when a library is built.** A program is lowered from `main` outwards because
  what it cannot reach is dead; a library has no `main` and every public declaration is a potential
  entry, so all of them are emitted and the *linker* discards what a given program never calls.

  The one thing removed is its **tests**, and they are removed before the analysis rather than by a
  pruner afterwards — a `@tests` file whole, and a `@test` declaration out of an ordinary one
  (`testing.md`). That is not an exception to the rule so much as the reason the rule needs stating:
  because nothing is pruned, a test left in would be emitted and advertised, and so would every
  generic instantiation its body had demanded.
- **A library defines its own declarations and nobody else's.** The compilation is handed the
  standard library too, and a library that prints reaches `printi` and `putbytes` exactly as a
  program does — but emitting *those* would put a copy of the printing surface in every artifact, so
  two libraries that both printed could not be linked into one program. They are declared in the
  artifact and defined in the consuming program, which compiles the standard library anyway and
  reaches them through the very body that called for them.
- **A library may not sit in the anonymous root module.** A library is reached by naming its module
  (§3) and the root module has no name, so nothing depending on it could write a path to what it
  declares — and its keys would be a headerless program's own. Its files go in directories under the
  root it is built from. This is also what makes the rule above exact: everything the compiler
  supplied is keyed outside the library's own modules.

**The standard module is built the same way.** `sysl`'s own source is ordinary sysl files under
`library/sysl`, and `sysl build-lib library --std` compiles them — `--std` being the one thing that lets a
compilation declare a module the compiler otherwise supplies. It is written down rather than inferred
from the module names in the tree, because a build that guessed would turn a clear refusal — *you
cannot add to the module every program is compiled against* — into an artifact that builds and then
collides with the library at whatever link tried to use it.

**Those files are what a compiler is installed with, and it reads them off disk.** An installed sysl
finds them at `<prefix>/share/sysl/library`, reached from its own resolved path — the ordinary Unix
prefix layout, and exactly what Homebrew's `pkgshare` is. A sysl run out of a checkout finds `library/`
in the tree. `SYSL_LIB` names a root outright and is an escape hatch rather than the mechanism: a
toolchain that had to be told where its own library was is one nobody could install.

The source was **generated into the binary** for the whole of the language's first year, as a
`StdSource` object written by the build. That guaranteed something real — a compilation could not
fail to find its library — and it was bootstrap scaffolding rather than the design, for two reasons
that both got heavier as the library grew. A library nobody can open is not one anybody can learn
from, and the library is meant to be the worked example of what sysl is for. And a compiler that
cannot be pointed at an edited copy is one whose library cannot be worked on at all except by
rebuilding the compiler.

`rustc` computes a sysroot from its own location, `clang` finds its resource directory the same way,
`zig` its `library/`. None of them carries a standard library inside the executable and none of them asks
for a variable to be set. What replaces the guarantee is the **diagnostic**: a compiler that cannot
find its library names every path it tried, in the order it tried them, and says how to name one.
That is the whole of the trade, and it is why the message is specified rather than left to whatever
the failure happened to be.

A library is **analyzed before anything is written**. A library that does not check is broken once,
by whoever built it; without that check the artifact ships anyway and every program that links
against it is handed a diagnostic pointing into somebody else's source.

**The container is an `ar` archive**, which is what an `.rlib` is and for the same reason: the linker
already reads one, so the compiled half needs no unwrapping and a member is pulled in only to resolve
something a program actually left undefined.

The metadata rides inside it the way Rust's does — **wrapped in a real object file**, as one
`private` constant in a section of its own. That is not decoration, and the shape of the alternative
is worth recording. A *raw* archive member is silently dropped by macOS's `ranlib`; suppressing the
index only moves the failure to the linker, which reads every member and refuses with
`archive member 'lib.smeta' not a mach-o file`. Wrapped, the member is an object like any other, and
being `private` it exports no symbol — so nothing ever gives the linker a reason to pull it in, and it
costs the linked program nothing.

Reading it back needs **no object-file parser**: the member is found by scanning for a marker and the
payload carries its own length. That matters more than it sounds, because the objects in an artifact
are for the machine it was built *for*. A compiler that had to parse them could not read the metadata
of a library it had cross-built, which is the ordinary case rather than the exotic one.

Producing one needs an **`llvm-ar`** alongside `clang`, and for the same reason. A platform archiver
indexes only its own object format: asked on macOS to archive an ELF object, the system `ar` exits 0,
prints a `ranlib` warning, and writes an archive with the member **missing** — a cross-built library
that is silently empty. `llvm-ar` indexes every format, so one archiver covers every target. `--ar`
names it where a search would not find it, which on a Mac is the usual case: Homebrew deliberately
keeps its LLVM off the `PATH`, so the tool is installed, works, and is invisible to `which`.

Member selection and dead-striping are **two mechanisms, and the second is not made redundant by the
first**. The linker takes only the members it needs, but it takes each one *entire*, and a member
holds many definitions; `-dead_strip` (Mach-O) and `--gc-sections` (ELF, paired with
`-ffunction-sections` when the library was compiled) remove the rest of a member pulled in for one
function. Without the second, a program whose whole text is `print(1)` carried all 61 of the standard
module's symbols.

**What this does not yet reach:** a library function that reads a module-level `val` is left out of
the precompiled half, because a `val`'s storage is written by the entry point and a library has none.
Such a function is compiled in the consuming program instead, where the initialization it depends on
happens. Lifting that needs a library initializer the program calls before `main`.

**And the standard module is linked by default, once it has been built.** Which library a compilation
is compiled against is a **parameter** of it rather than an ambient fact — which is what lets two
standard modules be handed to two compilations and compared — but the parameter has a default, and the default
is found rather than named:

```
sysl run prog.sysl                 # builds the artifact if it is not there, then finds it
sysl build-lib library --std          # the same artifact, written on demand
```

One path at both ends. `build-lib --std` with no `-o` writes to it and a compilation with no
`--std-lib` looks there; naming the path is for the cases where it is somewhere else. Handed one, a
compilation does the whole of the above: it declares what the artifact compiled, monomorphizes the
generics here, and links — rather than re-deriving every signature in the standard module before
checking its own first line.

**That path is in the user's cache, keyed by the library's fingerprint** — on this author's macOS,
`~/Library/Caches/sysl/<fingerprint>/std.syslib`. It was once `./.sysl/std.syslib`, and the change is
what an *installed* compiler forces: a clone has its own `library/sysl`, so a per-tree artifact was right,
but a compiler carrying the library inside itself would otherwise rebuild the same 900KB once per
directory anyone ran it in, and leave a `.sysl/` wherever `sysl run` was typed. Keying on the
fingerprint rather than a release number is what makes an upgrade need no invalidation: an edited
`library/sysl` hashes differently and therefore *is* a different path, so a stale hit cannot occur rather
than being caught. Where a machine has no cache directory at all — a container with no home — the
project-local path is still the answer.

**The artifact is not committed.** It is object code for one machine, and building it takes under a
second, so a clone or a fresh worktree builds its own. That makes drift the thing to guard against
rather than staleness in the repository: the artifact carries a **fingerprint of the standard module sources it
was built from** — a 64-bit FNV-1a over each file's basename and contents in sorted order, run
through `fmix64` — and a compilation refuses one whose fingerprint is not the one the compiler
carries. Sorting by basename rather than by path is what lets the artifact built from `library/sysl` on
disk match the copy the compiler generated from the same files, which are named by where each was
read.

**A compilation that finds no standard module at the default path builds one.** The artifact is a
*derived* file — not committed, object code for one machine, and computed entirely from library
source the compiler already carries — so the two states it is ever found in, absent after a clone or
a fresh worktree and stale after a change to the tree encoding or the container, have one answer
each, the same answer every time, and it takes well under a second to produce. Putting that to
whoever ran the compiler buys nothing: there is no second option for them to choose.

**A rebuild publishes by rename**, which is what keying the path on the library rather than on the
directory costs. One key means one file for every compilation of that library on the machine, and
nothing holds two of them apart: separate worktrees at one commit carry identical sources and so
hash to identical paths, and so do two runs of the same suite. `ar` truncates its output before it
writes, so a build that assembled in place would leave every concurrent reader a file that is
briefly absent and then briefly half an archive — a failure with no diagnostic, since half an
archive is simply an artifact that will not read. Assembled beside its destination and renamed onto
it, a reader sees the whole of the previous artifact or the whole of the new one, and a rebuild that
fails leaves the working artifact it was replacing rather than taking it down on the way to not
producing one.

**This is not the silent substitution the rule above forbids, and the difference is exact.** What a
compiler must never do is answer *I could not find the library you meant* by compiling against a
different one — which is why no C compiler that cannot find libc carries a spare. A rebuild compiles
against **this** library: the sources are the ones the compiler carries, the result is held to the
same fingerprint on the way back in, and the program is compiled against precisely what it would have
been had the artifact been there. Nothing is substituted, so there is nothing to be misled about. It
is announced on stderr rather than done invisibly, because a first build that pauses to do work
should say what the work was.

**An artifact named with `--std-lib` is not rebuilt, and one that cannot be read stops the
compilation** — corrupt, truncated, built by another sysl, or built from other sources than these.
Someone who wrote down which standard module to compile against is owed the truth about that one
rather than a different one built underneath them; it is the rule a named `--ar` already takes.

**`--no-std-lib` is the one route to the copy the compiler carries.** That copy is there for the
compiler's own unit tests, which have to run in a tree where nothing has been built — and, once, for
the bootstrap, since there was no released sysl to build the first artifact with. It is reached by
asking for it, never by a lookup coming up empty. The distinction is what keeps it honest. A fallback taken silently would be a
fallback taken *always* — nobody would have any reason to build an artifact — and the path meant for
the rarest of circumstances would quietly become the only one anyone ever ran. It has a second use
beyond the bootstrap, and the suite now takes it: compiling one program both ways is how the two
paths are held to meaning the same thing. What that comparison is over is the **program's** own code,
since the two modules deliberately do not hold the same symbols — the standard module's own, and the
ARC runtime beside them, are defined in place when the copy is carried and come from the artifact's
object when it is linked, which is the difference the flag exists to make.

**`build-lib --std` is exempt**, and must be: it is the command that produces the artifact, so
requiring one would be a deadlock with nothing to break it.

**The copy the compiler carries is a choice, not a necessity.** No other toolchain embeds its
standard library: clang ships no libc, and rustc ships precompiled `libstd.rlib` beside the binary in
a sysroot whose absence is a hard error rather than a fallback. Both put the library *alongside* the
compiler in a known place. sysl carries one instead, deliberately: it has no released compiler to
bootstrap from, and its own tests have to run in a tree where nothing has been built yet. What that
costs is the standing obligation to keep the carried copy in step with `library/sysl` — met by
generating it from those files on every build and asserting file-for-file that the two agree, so the
files are the fact and the carrier cannot disagree with them.

Two properties make the switch safe to make one step at a time, and both are pinned rather than
assumed. The artifact **means what the source means**: one program compiled both ways emits the same
module, byte for byte. And what linking costs it is accountable — every definition the module loses
is either a symbol the artifact defines, or the module-private ARC runtime, which is emitted on
demand by the bodies that needed it and is `private` in every module precisely so that a program and
the library it links may each carry one.

The reason for the care is that the compiler builds this artifact and every compilation consumes it.
A bad one is not a failing test but a compiler that cannot compile anything, with the tests that
would diagnose it unable to run — so the source path stays, and stays reachable.

## 9. What is deliberately absent

- **No file-as-module.** The file is a contribution, not a unit; there is no per-file namespace
  and no import of a file. A module is always a directory (§1).
- **No `pub` keyword.** Public is the unmarked default; `private` / `private[X]` are the only
  visibility modifiers (§2).
- **No relative or wildcard-path imports.** An import names a module by its full dotted path from
  the project root; there is no `import ..sibling` or path-relative form. Absolute names keep a
  reference's meaning independent of where the importing file sits.
- **No implicit auto-import beyond the standard module.** What every file gets for free is `sysl`
  (§8) — `Option`, `Result`, `print` and the rest of the library — and the scalar types, which are
  the language's own (`09`, `11`). Nothing else is: a module earns visibility by being imported or
  fully qualified, and a compilation does not gain unqualified names by having a library on hand.
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

- **a. The project-config doc — mostly CLOSED, by `packages.md`.** The `package.hocon` schema, the
  active target, the capability declarations and the dependency model are written there, along with
  the namespacing rule this chapter's §1 forces (a module name is local and relative, so a fetched
  `json` and your own `json` are one name — resolved by an optional per-consumer *mount*, with a
  collision an error rather than a silent winner, and canonical identity taken from the coordinate so
  `15 § 2`'s mangling never sees a mount). **The project root was already out of it**: the driver
  takes one, as the path it is given — `sysl run <dir>` makes that directory the root and compiles
  the tree beneath it, and a config file only ever *names* a root the driver would otherwise be told.
  **What is left of this item is the platform-file suffix grammar and resolution (§5)**, which
  `packages.md` does not attempt. Of `packages.md` itself, the file, its capability sets **and the
  dependency model** are built — a `dependencies` block is fetched, resolved by MVS and checked
  against `sysl.sum`. What is unbuilt there is the *commands* (`sysl add`, `vendor`), which is a
  question about the driver rather than about modules.
- **b. Re-export / facade modules.** Whether a module can re-export another's names (a `pub
  import`-style forwarding, so `std` can surface `std.fs.read`) is a real ergonomic want for
  building a curated public surface over sub-modules, and is left until the standard library's
  shape calls for it.
- **c. Module-level visibility.** §2 governs a *declaration's* visibility. Whether a whole
  *module* can be marked private to its parent (an internal sub-module invisible to outside
  importers, as Rust's `mod` privacy and Scala's `private[parent] package` allow) is open, and
  interacts with (b).
- **d. Separate compilation — settled for libraries, open for the module graph.** §8 settles the
  boundary a *library* crosses: the on-disk form is a `.syslib`, the split is object code for what is
  determined and trees for what is generic, and a program links the first and monomorphizes the
  second. What is left of this item is the **whole-graph** question — whether the modules *within*
  one project compile separately and cache, rather than being compiled together every time. The
  driver's discovery order is part of that: §3 establishes that the module graph cannot be recovered
  from file headers alone, so a build has to parse before it can order, and how that interleaves with
  cycle reporting (§6) and caching is unsettled. Escape and capability propagation across a library
  boundary is also still open — §8 carries bodies, so the information is *there*, but nothing reads
  it for those two passes yet.
- **e. `private[M]` and re-export.** §2 settles what `M` may name (the declaring module or an
  ancestor, as a simple name, resolved innermost-outward) and therefore that a visibility scope is
  always a contiguous subtree. What is left open is how scoped-private interacts with re-export
  (b) — whether a facade module may forward a name it can see but its own importers cannot — which
  cannot be pinned before (b) is.
- **f. Visibility below the top level — settled for members, open for modules.** §2 governs a
  *top-level* declaration, and a type's **fields and inherent members** now take the same four
  reaches: `08 § Visibility` has the rules, of which the load-bearing one is that an unmarked member
  sits at its type's reach and a modifier may only narrow. What is left of this item is (c) — a
  whole *module* private to its parent — which is the same question one level up.
- **~~g. What the file level buys the backend.~~ Done, and half of it was never there to do.** A
  declaration whose reach is the file it was written in now emits `define internal`, which is what
  §8 made worth doing: a library is a real object file, so a file-private helper in one was an
  exported symbol that nothing might call and nothing would discard. It reaches a member as well as
  a top-level declaration, with no rule of its own — an unmarked member sits at its type's reach
  (`08 § Visibility`), so a member of a file-private type is file-private, and both answer through
  the same `declAccess` entry. A `private[M]` declaration is deliberately **not** included: its reach
  is a whole subtree, so the file is not what bounds its callers. **The other half of the item —
  skipping the mangling — turned out to be wrong rather than unbuilt, and §2 now says why.**

- **h. What is in the standard library — the *where* is settled, the *what* is not.** A library now
  has somewhere to live and a way to be reached: §8 is the mechanism, `sysl` is the module name every
  program is compiled against, and its source is real sysl files under `library/sysl` that the compiler's
  own `build-lib --std` compiles. What that module should *contain* is the open half, and it is the
  question the whole exercise was for.

  **The prelude is gone.** What a program starts with is a module, not a set of declarations threaded
  in beside it. Getting there was one declaration at a time rather than a switch: every unqualified
  name in every program resolves through the library, so a change that moved all of it at once would
  have put the whole surface onto a path nothing had exercised, and a single hole in it would have
  failed everything with nothing to bisect. Thirteen surfaces crossed in that order, and the
  mechanism was deleted once the last one had.

  The pressure was real and predated the mechanism: the first program to want mathematics found none
  — `guide/fft` declared `sin`, `cos` and `sqrt` as C externs of its own and wrote its own absolute
  value, and every float program after it would have done the same. **`sysl.math` answers that one,
  and what it settles is bigger than the functions in it.** A module of free functions could not have
  worked: there is no overloading (`12` §1), so `sqrt` over binary64 and `sqrt` over binary32 would
  have needed two names and every caller would have had to track which width it was holding. What the
  module ships instead is a **trait implemented for the built-in float types**, which `02` allows for
  exactly this reason — so `x.sqrt()` is the same three words at either width, and the widths are told
  apart by the receiver rather than by the caller.

  That shape draws a line the rest of the standard library can be built along. A trait member whose
  result C computes is *required* of each width and binds to that width's libm entry point; a member
  that is arithmetic over the others is a **default**, written once and inherited by both. So the
  logarithm in an arbitrary base exists in one place, and a third floating-point width would be
  bindings and no new mathematics. The C declarations themselves are in `sysl.sys` with the rest of
  what the library asks of its host, so the surface a freestanding target would have to supply is one
  file.

  **It also shows what an import gates, which turned out to be one answer.** A *name* in a submodule
  has to be asked for — `pi`, `min` and `nan` all need the import, which is §1's rule and the reason
  `sysl.math` is a submodule. A **member** is asked for the same way, and §2 is where that is
  written: it is reachable where its **trait** is, so implementing `Float` for `real` reaches the
  files that named `Float` and no others. This was the other answer once, and the asymmetry is what
  made the question worth asking — a submodule of methods on a built-in would otherwise widen what
  every program can call without any program importing anything, and claim those member names from
  every program that would ever compile. Both halves of that went away with the one rule.

  What was wrong with growing a *prelude* instead is worth keeping, because it is the constraint on
  the answer: a prelude declaration is one every program carries a **layout** for whether or not it is
  reached (`14 §2`), which is why a prelude could never have become a standard library by getting
  bigger. A library a program links against pays only for what it calls — §8's pruning and the
  linker's discard between them — so the two are different things and the difference is measurable.
  It is the same question as `14 §8 a`'s — which module the trait catalog lives in once there is more
  than one — asked about a second body of code.

  **The *where* now admits a shape, which narrows the *what*.** `library/sysl` is a tree: a directory
  under it is a submodule of `sysl` by §1's ordinary rule, and only `sysl` itself is auto-imported, so
  a name put in a submodule is a name a program has to ask for. That turns "what belongs in the
  standard library" into two questions that can be answered separately — what a program cannot avoid
  needing, which is what the language desugars onto and belongs in `sysl`, and what a program should
  have to name, which is everything else. Whether a submodule can be marked private to its parent, so
  that the library can have workings that are not a public surface at all, is (c); `private[sysl]` on
  each declaration reaches the same place today, one declaration at a time.

  **Seven modules in, one boundary case decided the shape of the rule.** `sysl.sys`, `sysl.buf`,
  `sysl.text`, `sysl.io`, `sysl.args`, `sysl.math` and `sysl.fs` all fell out of "does the language
  reach it". The
  `display_*` renderers do not: no desugaring names one, so the letter of the rule puts them in a
  `sysl.fmt` — and the split was *tried*, works, and needs nothing else to move once the tuple rows
  go with it, `13 §6` notwithstanding. They stay in `sysl` anyway, and the reason sharpens the rule
  rather than excusing an exception. **A program writing `impl Display` is not reaching for a
  library; it is implementing a language feature.** `print` is a keyword, `Display` is how a type
  joins in, and the renderers are the vocabulary that contract is written in — a program that cannot
  write its `display` body without an import has been asked to name part of the language. So the
  test is not "does a desugaring name it" but **"can a program take part in the language without
  it"**, which is the same question everywhere else and a different answer only here.
