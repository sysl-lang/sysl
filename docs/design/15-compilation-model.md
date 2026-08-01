# Design Decisions: Compilation Model

**Status:** `13-modules.md` fixes what a module *is*; this chapter fixes what the compiler does
with a graph of them. It settles the decisions that multi-module compilation cannot be built
without — struct layout as a language guarantee, how symbols are named, how visibility becomes
linkage, where a generic is instantiated, and the order the driver works in — and it **defers the
incremental build** to a named later phase (§6) rather than designing it now.

This chapter rests on `13-modules.md` (module identity, the visibility levels, the acyclic module
graph), `02-traits.md` (the orphan rule, which is what bounds `impl` search), `10-generics.md`
(bounds checked once at the definition, monomorphization), and `codegen.md`, whose IR dialect it
extends rather than replaces.

The through-line: **a module compiles against its imports' signatures, never their bodies** — with
exactly one seam, the generic or `inline` body that must cross to be emitted (§4). Keeping that
seam narrow and explicit is what the rest of the chapter is for.

---

## 1. Struct layout is declaration order

**Fields are laid out in declaration order, and the compiler never reorders them.** This is a
language guarantee, not a default: sysl targets devices whose register and structure layouts are
fixed by a datasheet, where physical order is part of the contract rather than an implementation
detail.

Two consequences pay for it immediately:

- **Every type is C-compatible by construction**, so no `repr(C)`-style annotation exists or is
  needed. There is no annotated/unannotated split, and therefore no silent-corruption failure mode
  from having forgotten the annotation. The `extern` surface of `12` §1 already assumes this.
- **Layout is deterministic from the declaration alone**, which is what lets an importing module
  compute a generic instantiation's layout for itself (§4) instead of asking the defining module.

**Layout is part of a module's public interface.** A caller needs size, alignment, and field
offsets to stack-allocate a `T`, embed one, index one, or pass one by value. This is not an
artifact of any caching design — it is what by-value semantics mean, and C has the same property.

**Private fields still participate in layout, and in the ABI.** Platform ABI classification
recurses into field *types* to choose register versus memory passing — under SysV x86-64 an
8-byte struct of two `f32` is passed in an SSE register, two `i32` in an integer register — so a
by-value call needs the complete ordered field type list, private fields included. Field
visibility and layout visibility are independent axes: a private field is not *nameable*
downstream, and it is still *there*.

**The cost, stated plainly:** a careless field order silently wastes memory, and nothing packs it
back. The mitigation is a lint that reports a struct's padding and suggests an ordering — the
programmer reorders once, and the layout stays predictable afterward. Padding *suppression* (a
`packed` attribute placing fields at declared offsets with no interior padding, for register
blocks and wire formats) is a separate axis and remains open in `00` §Open, along with the
bitfield question it drags in.

## 2. Symbol names carry the module path

A sysl definition is mangled with **its module path**, so there is no global symbol namespace and
two modules may each declare an `init` without colliding. An `extern` is the exception and is not
mangled at all — it emits the raw C symbol, or its explicit link name (`12` §1).

Mangling extends the convention `codegen.md` already fixes (a memory mode spelled as a word,
`ptr.` / `ref.` / `sync.`, since a sigil is not an LLVM name character).

**Canonicality is a correctness requirement, not a nicety.** Two modules that instantiate the same
generic must produce **byte-identical** symbols, or the comdat dedup of §4 silently fails and
duplicate code ships — a bug that costs size rather than correctness, and so is easy not to notice.
Three rules follow:

- **Always fully-qualified paths** in a mangled name — never a locally imported short name, never
  an alias introduced by `import x as y`.
- **Fixed argument order** for type arguments.
- **One normalized spelling per value**, so a value parameter written `16` and one written `0x10`
  cannot produce two symbols for one instantiation.

Deeply nested instantiations produce very long symbols — a standing C++ complaint. Truncating past
a threshold and appending a hash of the full name, as Rust and Swift do, is cheaper to build in now
than to retrofit when a linker chokes (§Open e).

## 3. Visibility chooses linkage

`13` §2's levels map onto object-file linkage:

| visibility | LLVM linkage |
|---|---|
| `private` (this file) | `internal` |
| `private[M]` (module or subtree) | external, hidden |
| *(unmarked)* | external, default |

**Only file-`private` can be `internal`**, and that is forced rather than chosen: the files of a
module share one scope (`13` §1), so a module-private function called from a sibling file is a
cross-object reference and needs a real symbol. Visibility is a frontend concept — it decides what
a program may *name* — and hidden-versus-default is what survives into the object file to decide
what a linker may bind.

### What is emitted at all

Linkage says how a symbol may be bound; **reachability says whether there is a symbol.** A
declaration nothing can arrive at is not written out — neither the function nor the `extern` it
would have called.

Two rules keep that from being a way to hide mistakes or to lose one.

**Only emission is filtered, never analysis.** Every body is checked, every contract typechecked,
every escape found, whether or not anything calls it. A mistake is a mistake because of what the
line says, not because of whether the program would have run it — Rust's rule, and the one that
makes an unread `const` and an unused subtype errors today. So reachability is the *last* thing that
happens to a typed program, after everything that reads one.

**Reaching is over-approximated, never under.** Where the target of a call is settled at run time,
every function it could land in is taken: a slot of a method table stands for whatever each table
for that trait put there. Missing one would mean emitting a call to a function that was never
written; keeping an extra costs a function nobody calls.

The roots are what the program can start from — the statements it runs, the `main` it runs after
those, the initializers that fill its `val`s before either (`13` §7), and the method tables a trait
object dispatches through, a table being a constant a program reads a function pointer out of. A
declared `main` is a root because nothing in the program calls it: what calls it is the entry point
the compiler lays down, which is not a tree this walk reads.

**The rule survives separate compilation**, and it is the roots that change rather than the rule.
Today the whole program is compiled at once and nothing outside it can name anything in it, so every
declaration is a candidate. Under §5's per-file emission, a module's exported surface — everything
not `private` per §3's table — joins the roots, because a module it has never heard of may call any
of it. What stays prunable is exactly what stays unnameable, which is the same line visibility
already draws.

## 4. A generic is instantiated at the use site

The defining module **cannot know its own instantiation set** — any downstream module may apply
`Box` to a type the defining module never heard of — so it cannot emit the instances. The module
that *uses* an instantiation emits it.

- Instances are emitted **`linkonce_odr` in a comdat** and deduped by the linker, so two modules
  that both instantiate `Box[int]` each emit it and exactly one survives. §2's canonical mangling
  is what makes that dedup correct.
- **This is the one place a body crosses a module boundary.** Everything else in this chapter needs
  only signatures. `10`'s definition-site bound checking is what guarantees a *caller* never needs
  the body to typecheck — only to emit — which keeps the seam a codegen dependency rather than a
  typechecking one. The same mechanism serves a cross-module `inline`.
- **A generic struct has no layout, only a layout function** of its arguments. The importing module
  computes the concrete layout itself, running the same deterministic algorithm §1 guarantees, so
  an instantiation never round-trips through the defining module. The full ordered field list —
  private fields included, per §1 — has to be available for it to do so.

**Rejected: uniform representation / dictionary passing.** Boxing `Vec[i32]` so one copy of the
code serves every element type is the standard alternative, and it is not acceptable in a language
meant to replace C. The cost of monomorphization is code size (`10` §7), and that is the right
trade here.

## 5. What the driver does

Nothing in this section is an independent decision; it is what §1–§4 and `13` require.

1. **Discover.** Walk from the project root — whose location is the project-config doc's to settle
   (`13` §Open a). Every directory containing sources is a module, and `readdir` gives its file
   set with no parsing at all.
2. **Parse** every file, **for the target** — the lines of a branch this build is not for are
   blanked first (`targets.md § Conditional compilation`), so what is parsed is already the file
   this machine sees. `13` §3 establishes that a qualified reference can create a dependency no
   header mentions, so the module graph is *not* recoverable from a header scan; discovery reads
   whole files. This is a parse — no name resolution, no typechecking.
3. **Order.** Build the module graph, reject a cycle naming the modules on it (`13` §6), and
   topologically sort what remains.
4. **Collect.** Per module, merge every file's cross-file-visible signatures into the one shared
   scope (`13` §6). Sequential over a module's own files; modules in dependency order.
5. **Check** bodies against the merged scope — parallel across a module's files, and across
   independent modules.
6. **Emit.** One object file per source file, plus the instantiations of §4.
7. **Link.** Objects, then the archives that resolve them, then the system libraries the target needs
   — left to right, because that is the order the scan pulls archive members in. Which system
   libraries those are is the driver's answer today and §Open c's in general.

Step 4 never needs a dependency to have been **compiled** — only parsed. That is `13` §2's
parse-only interface extraction doing the work it was chosen for, and it is what makes steps 4–6
parallelizable in the first place.

## 6. Deferred: the incremental build

**Phase 0 compiles everything, every time**, and it exercises every structural decision above —
discovery, the graph, cycle detection, scope merging, two-pass checking, visibility enforcement,
mangling, instantiation, link. Caching is a layer over a correct compiler, not a foundation under
it, so it is deliberately not built first.

When it does arrive it wants per-file `.iface` files (parse-only, holding just the cross-file
surface), a merged `module.iface` per module, a `module.bodies` for the generic and `inline` bodies
of §4, and four hashes:

| hash change | rebuild |
|---|---|
| a file's source | that file's object |
| a file's `.iface` | re-check the other files of its module |
| `module.iface` | re-check downstream modules |
| `module.bodies` | re-codegen downstream files that instantiated or inlined |

Two decisions already made are what let this bolt on without repainting anything: interface
extraction is **parse-only** (`13` §2), so a `.iface` can be produced before anything is compiled;
and generic bodies are checked **at their definition** (`10`), so a body edit is a codegen
dependency and never a typechecking one — which is exactly what separates the last row from the
third.

Refinements available later, none of which change the model: bucketing the module hash **by
visibility scope**, so editing a `private[geom]` signature invalidates only modules under `geom/`
(the annotation is a declared blast radius; the build should use it as one); splitting a type's
entry into **`api`** and **`layout`** hashes and recording per file which it consumed, so a file
that only passes pointers is provably unaffected by a new private field; and splitting `layout`
into **`size+align+abi`** versus **`offsets`**, which is possible *because* §1 forbids reordering —
appending a field cannot move an existing field's offset.

---

## 7. A library may carry C

**A `.c` file dropped anywhere in a library's tree is compiled with it and archived beside it.**
Nothing declares it and nothing lists it: the walk of §5 step 1 already visits every directory, and a
C file found there is compiled for the same target and becomes one more member of the `.syslib`.
The sysl side reaches it through the `extern` that was already the way to name a symbol the linker
has (`12` §1) — so the language gains nothing, and the whole of the feature is in the build.

**It exists because a binding to a real C library cannot be written without it.** Three things are
reachable from C and from nothing else, and each of them blocks an ordinary POSIX interface:

- **A caller-allocated opaque type.** `regcomp` wants a `regex_t` the caller supplies, and its size
  is 32 bytes on Darwin and 64 under glibc. A program can only allocate storage whose size it knows.
- **A macro.** `REG_EXTENDED`, `O_RDONLY`, `SIGKILL` are `#define`s. They have no symbol, so there is
  nothing for a linker to resolve and nothing for `extern` to name.
- **A shape with no sysl spelling** — an untagged union, a bitfield, an inline function.

Each becomes an ordinary function in three lines of C. `size_t f(void) { return sizeof(regex_t); }`
is a symbol; so is `int f(void) { return REG_EXTENDED; }`. Better still, a shim that *allocates* the
opaque type hands back a pointer and the sysl side never learns the size at all.

**The alternative is transcription, and transcription is silently wrong.** A hand-written `struct
regex_t` with the fields of one platform's header compiles everywhere and is correct on one machine.
Nothing checks it — sysl's own `sizeof` would report what sysl laid out, not what C did, so even that
comparison is a tautology. Getting it wrong writes past the end of the caller's storage. The number
has to come from the headers, and C is what reads headers.

**A member is named after the path it was found at**, directories included — `demo/util.c` becomes
`demo.util.o`. A basename alone would not do: `ar r` replaces by name, so two modules each holding a
`util.c` would have the second evict the first, and the library would ship missing whatever only the
first defined. The one collision that survives this is a directory called `sysl` holding a `code.c`,
which would take the name the library's own compiled half uses; it is refused rather than archived.

**The C files are fingerprinted with the sysl ones.** A library's shims are as much its source as its
modules are, and an artifact that did not change when one was edited is a stale artifact nothing
would notice was stale.

**Cross-compiling a library that includes headers needs that target's headers.** This is not a cost
the design imposes — it is the requirement being honest. A binding to POSIX regex cannot be built for
a platform whose `regex_t` nobody can see. C that includes nothing cross-compiles like any other
object. The standard module under `lib/sysl` includes no headers and reaches libc by symbol alone, so
it goes on building for any target the toolchain can lower for; that is worth keeping, and it is a
property of what that library happens to need rather than a rule about where C may live.

## Open (not yet decided)

- **a. `opaque` structs.** A struct whose layout is withheld from its interface entirely, usable
  downstream only behind a pointer — no stack allocation, no embedding, no `sizeof`, construction
  through a function in the defining module. It buys unlimited private-field churn with zero
  downstream impact, and costs an indirection and an allocation. Wanted once there is a real
  library surface to stabilize; nothing is blocked without it. (Swift-style *resilient* value
  types, which compute layout at runtime from metadata, are the alternative and are rejected —
  every field access becomes an indirect load, which is why Swift itself added `@frozen` to opt
  back out.)
- **b. Calling-convention annotation.** `extern` implies the C ABI, but device targets need others
  — interrupt handlers at minimum — so this wants to be a general annotation on a declaration or
  definition rather than a flag that only `extern` understands.
- **c. Link directives.** How a module of externs tells the driver to pass `-lc` and friends. Most
  naturally a per-module directive sitting beside the externs it supports.

  **The one library the standard module itself needs is carried by the driver in the meantime**, and
  the shape of that stopgap is an argument for the general mechanism rather than against it.
  `sysl.math` binds libm, and where libm *lives* is a property of the host: Darwin keeps it in
  `libSystem` and Windows in the CRT, both of which the driver links unasked, while a program hosted
  on ELF has to say `-lm` and a freestanding one must not, having no libc for it to be in. So the
  driver answers that off the target's `Os` — a decision per object format, written out per case so
  that a target added to the registry is a decision rather than whichever arm a default reached.

  What that does not scale to is a *program's* externs. A binding to `libpng` or `libz` is written by
  whoever writes the module, and the driver cannot carry a list of libraries it has never heard of.
  The stopgap is bounded to the standard module because the standard module is the one the compiler
  ships; every other module needs this item built.

  It also cannot be tested by linking. A program that calls `sqrt` links on a Mac whether or not
  `-lm` was passed, so the machine that finds a missing directive is never the machine the compiler
  was developed on — which makes the assertion one about the *command line*, and `LinkCommandTests`
  is that.
- **d. Export to C.** The reverse of `12` §1's `extern`: mangling suppression plus the ABI
  annotation of (b), applied to a *definition* so existing C can call into sysl. A C replacement is
  adopted incrementally, so this direction matters as much as the importing one.
- **e. The symbol-length threshold** at which §2 truncates and appends a hash.
- **f. Recording file discovery.** Whether the `readdir` of step 1 is written into a build log, so
  that adding a file to a module is a *visible* change for reproducible or sandboxed builds rather
  than an invisible one.
