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

**Layout is part of a module's public interface** — unless the type opts out (§9). A caller needs
size, alignment, and field offsets to stack-allocate a `T`, embed one, index one, or pass one by
value. This is not an artifact of any caching design — it is what by-value semantics mean, and C has
the same property. An `opaque` struct is the one type that publishes no layout, and it pays for that
in the only currency available: none of those operations is open to a caller either.

**Private fields still participate in layout, and in the ABI.** Platform ABI classification
recurses into field *types* to choose register versus memory passing — under SysV x86-64 an
8-byte struct of two `f32` is passed in an SSE register, two `i32` in an integer register — so a
by-value call needs the complete ordered field type list, private fields included. Field
visibility and layout visibility are independent axes: a private field is not *nameable*
downstream, and it is still *there*. §9 is that second axis given a name of its own.

**The cost, stated plainly:** a careless field order silently wastes memory, and nothing packs it
back. The mitigation is a lint that reports a struct's padding and suggests an ordering — the
programmer reorders once, and the layout stays predictable afterward.

**Two attributes move a struct off that default, and they are separate axes.**

`@packed` places the fields at their declared offsets with **no interior padding**, and drops the
aggregate's own alignment to one. It lowers to LLVM's `<{ }>`, so the offsets the back end computes
are the ones this file's rules computed rather than a second opinion that agrees right up until a
field needs padding in front of it. A register block, and a C struct that has to match one, are what
it is for.

`@align(n)` raises where the aggregate must **begin**, and rounds the size up to a multiple of `n` so
an array of them keeps every element on the boundary. It may only raise: asking for less than the
fields already require changes nothing, since lowering is what `@packed` is for and a type that
under-promised would be unsound to pass. The bound is folded rather than lexed, so `@align(CACHE_LINE)`
is available wherever a program has a name for the number, and it must be a power of two — an address
is aligned by having low bits clear, so a boundary of six is unsatisfiable rather than merely weak.

They compose, because the gaps *between* fields and the boundary the whole thing *starts* on are
different questions: a wire header that has to live in a DMA-capable buffer is `@packed @align(64)`.

**A packed field has no address.** `&s.f` is refused where `s` is packed, and so is any `*T` into
one. The field sits at its declared offset, which is very often not a multiple of its own alignment —
that is the point of the attribute — while a `*u32` is a `*u32` wherever it came from, and every use
of one is entitled to assume the address is aligned. Reading and writing the field are untouched:
those go through the struct, where the offset is known. Only the escaped address loses that, and it
loses it arbitrarily far from the `&` that made it.

**Sub-byte fields are not part of this.** Whether a `@packed` struct lays an `i5` field out in
exactly five bits — the bitfield and hardware-register payoff — remains open in `00` §Open. Today an
`iN` field occupies its allocated width wherever it sits, packed or not.

## 2. Symbol names carry the module path

A sysl definition is mangled with **its module path**, so there is no global symbol namespace and
two modules may each declare an `init` without colliding. Two declarations are the exception and are
not mangled at all: an `extern`, which emits the raw C symbol or its explicit link name (`12` §1),
and a definition marked `@export`, which is that same rule read the other way (§12).

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
than to retrofit when a linker chokes (§Open b).

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

1. **Discover.** Walk from the project root — the path the driver is given, which `packages.md § 1`
   confirms a `package.hocon` only ever *names* rather than replaces. Every directory containing
   sources is a module, and `readdir` gives its file set with no parsing at all.
2. **Parse** every file, **for the target** — a literate file's prose is blanked first (§11) and
   then the lines of a branch this build is not for are (`targets.md § Conditional compilation`), so
   what is parsed is already the program this machine sees. `13` §3 establishes that a qualified reference can create a dependency no
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
   libraries those are is §8's answer: each module names the ones its own `extern`s need, and the
   target decides what a name becomes on the command line.

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

## 7. A source tree may carry C

**A `.c` file dropped anywhere in a tree is compiled with it.** Nothing declares it and nothing lists
it: the walk of §5 step 1 already visits every directory, and a C file found there is compiled for
the same target as the sysl beside it. The sysl side reaches it through the `extern` that was already
the way to name a symbol the linker has (`12` §1) — so the language gains nothing, and the whole of
the feature is in the build.

**Which tree it is decides nothing about whether the C is compiled, and only where the object goes.**
Four trees reach a build and all four carry their C:

| the tree | what its objects become |
|---|---|
| a `build-lib`'s library | members of the `.syslib`, named as below |
| the project being built | objects on the link line |
| a source root named with `--lib` | objects on the link line |
| a package a `dependencies` block brought in | objects on the link line |

The first is the only one that has to name anything, because an artifact is one file. A compilation
has no such constraint, so its C goes on the command line as objects — which is also more correct
than archiving would be: an archive member is pulled in only to resolve a symbol already undefined,
and a shim reached through a table nothing has mentioned yet would not be pulled in at all.

**A tree brought in as source and the same tree built into an artifact must behave alike**, which is
what makes `--lib` able to take either without a second flag. It is also why the packages chapter can
refuse build scripts (`packages.md § 7`): its argument is that sysl compiles a package's C
declaratively, and that is a claim about a package consumed *as source*, since that is how a
`dependencies` block consumes one.

A run that names a single **file** rather than a directory gets no C, and that follows from `13 §1`
rather than being a rule of its own: naming a file compiles that file alone, so there is no tree for
C to have travelled with.

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

### A shim answers for a function, and `c const` answers for a value

**Wrapping it in a function is the whole answer only where a call will do**, and for a *value* it is
not an answer at all. A constant reached through a call is not a constant: it has no value until the
program runs, so it cannot size an array, cannot stand in a `match` arm, cannot be folded into a
bound and cannot be checked by `@assert`. The macro bullet above still holds for `REG_EXTENDED` as an
*argument*; it stops holding the moment the number has to be known while compiling.

FreeRTOS is where that stopped being theoretical. A statically allocated task is a
`[sizeof(StaticTask_t)]u8` the caller supplies, and there is no such thing as an array whose length is
decided by a function — so the shim route does not reach it, and the only route left was to write the
number down, which is the practice the paragraph above exists to condemn.

**A `c const` block is a constant whose value the C compiler works out**, for the target being built
for:

```
@include("FreeRTOS.h")

c const
    STATIC_TASK_SIZE: usize = "sizeof(StaticTask_t)"
    MAX_DELAY: u32          = "portMAX_DELAY"

var tcb: [STATIC_TASK_SIZE]u8 = [0; STATIC_TASK_SIZE]
```

`@include` is a header clause like `@link`, written the way C writes it and looked for where a shim's
headers are looked for. What it buys is that the expressions compile: **no name from the header
becomes visible in sysl**, and a type still arrives by `opaque struct` and a function by `extern`
(§9). Where the C compiler is not told about the header, this fails at the `#include` exactly as a
shim does — see §8's paragraph on search paths.

**The module's own directory is searched first, so a vendored header needs no flag.** That is not a
convenience: C resolves `#include "foo.h"` relative to the file doing the including, and the probe is
a temporary file somewhere else — so a package carrying `qcbor.h` beside `qcbor.sysl` would have been
unable to use the feature at all, which is the case it was built for. The shim in that same directory
reaches the header with no flag, and a `c const` that needed one would have been the odd member of
the pair. It is searched before the paths given on the command line, which is C's own precedence for
a quoted include.

**The value is measured from a probe translation unit, which is compiled and never linked or run**:
the file's headers, one global per constant, lowered to IR for the target, and the number read out of
the IR. That is what makes the answer *the target's* — a pointer is four bytes for a Cortex-M and
eight for this machine, and neither requires anything to execute. It is the same method
`targets.md § Adding one` already binds an ABI answer to, applied to the numbers a compiler has no
way to know it should have asked about.

**The C is quoted with a plain string.** Inside such a block a string can mean nothing else, so the
`c` on the header marks the language once and no literal prefix is introduced. That `c` is
contextual, which the `const` after it is what makes safe: nothing else in the language follows a
name with a keyword, so a program is free to call a variable `c`.

**Any C constant expression, and the C compiler is the judge of which those are.** That is what makes
the claim honest rather than a subset somebody maintains — an expression C will not settle is refused
in clang's own words, quoted rather than paraphrased. Four more refusals go with it: a header that is
not there, a value the declared type cannot hold (naming the value and both ends of the range), a
type that is not an integer, and a block written inside a body, which has no file's headers to be
compiled against.

**A `string` from C is not written this way, deliberately.** An integer is a number in the IR and
reads straight off; a string constant is a block of storage and a different job, and it would have to
be written `"\"foo\""` — two quotings for one value, which is a form nobody would guess. The refusal
says so rather than leaving it to be discovered.

**It is lowered to an ordinary `const` before anything else looks at the tree**, which is why nothing
else in this chapter has to change: by the time a name is resolved or a bound is folded, what is there
is `13 §7`'s constant holding a literal. A **library** is lowered before it is encoded, so an
artifact ships the measured number rather than the expression — a program linking a package needs
neither the package's headers nor a C compiler, and could not honestly be handed the expression
anyway, since an artifact is built for one target and re-measuring it elsewhere would be answering a
different question under the same name.

**A file that writes no block costs nothing**, and never causes a C compiler to be looked for. Every
file in this repository is that case.

**An object is named after the path it was found at**, directories included — `demo/util.c` becomes
`demo.util.o`. A basename alone would not do: `ar r` replaces by name, so two modules each holding a
`util.c` would have the second evict the first, and the library would ship missing whatever only the
first defined. The one collision that survives this is a directory called `sysl` holding a `code.c`,
which would take the name the library's own compiled half uses; it is refused rather than archived.

**Across trees even that is not unique**, since two packages may each hold a `net/util.c`, and the
consumer never chose either name. So a compilation stages each tree's objects into a directory of its
own and the names inside stay the readable ones. There is nothing to check, because the shape makes a
collision impossible — which is the right answer for a clash between two files neither of whose
authors could have known about the other.

**The C files are fingerprinted with the sysl ones.** A library's shims are as much its source as its
modules are, and an artifact that did not change when one was edited is a stale artifact nothing
would notice was stale.

**What the fingerprint does not cover is the headers a tree includes, or the macros it is configured
with**, and that gap is older than `c const` — a shim compiled against a system header has always had
it. Editing a header outside the tree, or building with a different `-D`, changes what the C means
and leaves the fingerprint where it was. A `c const` makes it easier to *notice*, because the stale
answer is now a number a program reads rather than a shim's behaviour, but it is the same hole and
closing it belongs to whatever closes it for both.

**Cross-compiling a library that includes headers needs that target's headers.** This is not a cost
the design imposes — it is the requirement being honest. A binding to POSIX regex cannot be built for
a platform whose `regex_t` nobody can see. C that includes nothing cross-compiles like any other
object. The standard module under `library/sysl` includes no headers and reaches libc by symbol alone, so
it goes on building for any target the toolchain can lower for; that is worth keeping, and it is a
property of what that library happens to need rather than a rule about where C may live.

## 8. A module says which library resolves its externs

**`@link("z")` in a file's header names a library the linker must be given**, and it sits beside the
`extern`s it supports because that is the only place that knows. It is an attribute for the reason
the capability clauses are (`13 §4`): it says something *about* the file rather than being a
construct the language executes, and written as grammar it would spend the word `link` — which
`guide/slab` uses for the pointer that threads its free list. An `extern` states the symbol it
wants and never where the symbol lives; a binding to `libpng` is written by whoever writes the
module, and the driver cannot carry a list of libraries it has never heard of.

```
module image.png
@link("png")
@link("z")

extern "png_create_read_struct" create(ver: *u8, err: *u8, fn: *u8) -> *u8
```

**A directive names a library, and never a flag.** That is the whole of the design, and everything
else follows from it. Where a library *lives* is a property of the machine being built for: the
mathematics is a file of its own on ELF, part of `libSystem` on Darwin, inside the CRT on Windows,
and absent from a freestanding target that has no libc for it to be in. A directive that spelled
`-lm` would be right on one of those and wrong on the other three — and the author could not be told
so by any compiler running on the machine that wrote it, because the link that fails is somewhere
else. So the file names `m`, and the driver decides what that becomes:

| the target | what a name becomes | why |
|---|---|---|
| has the library separately | `-lname` | the ordinary case, and what every unrecognized name gets |
| already links what holds it | nothing | Darwin's `libSystem`, Windows' CRT — the driver passes those unasked |
| does not have it at all | nothing | a freestanding build has no libc, so nothing can be passed for one |

The last two both put nothing on the command line and are still written apart, because they are
different facts and a target added to the registry has to answer them separately. A freestanding
program that then calls `sqrt` fails at the link naming `sqrt`, which is the honest report: what is
missing is the function, and no `-l` would have supplied it.

**The set the compiler knows is deliberately small** — the C runtime and the mathematics, which are
the two whose placement actually differs across the four. An unrecognized name is passed straight
through rather than guessed about. Being wrong in that direction produces a link error naming the
library; being wrong in the other produces a link error naming a *function*, on a platform the
author does not have.

### Where the library sits is the host's question, and belongs to the driver

Everything above decides what a *name* becomes. It says nothing about **where that name is looked
for**, and the two are different questions with different owners: the target decides the name-to-flag
mapping, the machine being built **on** decides the search path. This section answered the first and
left the second unasked, and while it was unasked `@link` could reach only libraries already on the
toolchain's own path — every OS-shipped library and nothing else. `-lpng` failed with `library 'png'
not found` on a machine where `/opt/homebrew/lib/libpng.dylib` was sitting the whole time.

**`--link-path <dir>` and `--include-path <dir>` are the answer, and they are the driver's.** Both are
repeatable and searched in the order given. They ship as a pair because half of the capability is
none of it: a binding to a library outside the default prefix has to compile its shim against that
library's header before there is anything to link, so `--link-path` alone gets a build one step
further and no closer.

**It must not be an attribute, for this section's own reason.** `@link("png")` is portable because it
names a library rather than a flag; `@link_path("/opt/homebrew/lib")` would be one machine's
directory layout compiled into source meant to build anywhere — the same mistake as writing `-lm`,
one level up. For the same reason it is not a field in `package.hocon`: that file is committed and
describes the *package*, and where a prefix lives on somebody's laptop is not a property of the
package.

**The environment already worked, and that is why the flags exist rather than why they don't.**
`LIBRARY_PATH` and `CPATH` are read by clang, which sysl execs, so a developer who exports them has
always had a build that links. But a build that works only because of one person's shell is one
nobody else can reproduce, and it fails for the next person with a message naming a library rather
than the setting they are missing. The environment stays the right tool for *this machine, always*;
the flag is the right one for *this build, wherever it runs*.

**Nothing is guessed at.** Adding `/opt/homebrew/lib` by default is the obvious convenience and is
refused for the reason the table above refuses to guess a library's placement: a compiler that ruled
on where a platform keeps its libraries would be wrong about a machine nobody here has, and the cost
of being wrong is a link that fails somewhere the author cannot reach.

**The requirement travels in the artifact.** The clauses are part of the tree a `.syslib` carries, so
a program depending on a prebuilt library learns to pass `-lz` without reading that library's source.
Leaving them out would mean a binding that works from source and stops working the moment it ships —
the worst available shape, since the build that breaks is one its author never ran.

**A module's requirement is the union of its files', and its files are not held to agreeing.** This
is the one place the directive differs from the capability clause of `13 §4`, which it is otherwise
shaped like, and the difference is in what each describes. A capability describes what the whole
module may do, so files that disagreed would be describing different modules. A link requirement
describes what one file's `extern`s need — and a module whose foreign declarations all sit in one
file has nothing for its other four to repeat.

**Order is kept rather than sorted.** A static archive is scanned once, left to right, and a member
is pulled in only to resolve a symbol already undefined, so a library that calls into another has to
come first: `-lpng -lz`, never the reverse. That ordering is the author's to state and the compiler's
to preserve. Sorting would decide it by spelling, which is right by accident for those two and wrong
for the next pair. Two libraries that call into *each other* are not expressible today; `--start-group`
is what would express them, and nothing has needed it yet.

**`link` is a soft keyword** — special only in a header, an ordinary identifier everywhere else.
`guide/slab` declares a function called `link`, the pointer threading a free block, and a systems
language cannot afford to spend that name. Nothing is lost by it: a directive is `link` followed by a
*string*, and no statement has that shape.

**It cannot be tested by linking**, which decides how it is tested. A program that calls `sqrt` links
on a Mac whether or not `-lm` was passed, so the machine that finds a missing directive is never the
machine the compiler was developed on. The assertion is therefore about the *command line*, and
`LinkCommandTests` is that — one test aside, which hands a real linker a library that does not exist
and requires the error to name it back.

**This replaced a list the driver carried.** Before it, `-lm` went onto every ELF link whether or not
the program computed anything, because `sysl.math` had no way to say so and the compiler had no way
to be told. That stopgap was bounded to the standard module because the standard module is the one
the compiler ships; every other module needed this built. It says so itself now, in
`library/sysl/sys/math.sysl`.

## 9. `opaque` withholds a layout

**`opaque struct Name` is known by shape only inside the module that declares it.** Everywhere else
the type is **incomplete** — exactly what C's `struct foo;` is — and the only thing that may be said
about it is `*Name`.

```
module net

opaque struct Conn
    fd: int
    live: bool
end Conn

open() -> *Conn
close(c: *Conn)
```

**One rule, because two different wants meet in it.** A library stabilizing its surface wants to add
and reorder fields with nothing downstream recompiled. A binding wants `*sqlite3` to be a type that a
`*u8` cannot be mistaken for, where nobody in sysl knows the layout at all. Both are "the shape is
not yours to know", and both are served by making the type incomplete outside its module.

So an opaque struct may declare **no body at all**:

```
opaque struct Dir

private[sysl] extern "opendir" c_opendir(path: *u8) -> *Dir
private[sysl] extern "closedir" c_closedir(d: *Dir) -> int
```

That is the C-handle case, and `library/sysl/fs` is its first user — it previously bound `DIR *` as
`*u8`, which the linker accepts and which is interchangeable with the `*u8` *paths* declared on the
lines above it. Nothing in sysl lays a `Dir` out; the storage is libc's. An ordinary struct with no
body stays an error, and says which word to add.

**What is refused outside, and why it is one list.** A binding, a field of another type, an element,
an array, a slice, a `&`, a type argument, a by-value parameter or result, construction, reading or
writing a field, a pattern naming the fields, a dereference, `sizeof`, `alignof`, and a by-value
`self` method. Every one of them needs a size or an offset, which is the single fact being withheld,
so they are one diagnostic rather than fourteen.

**The by-value `self` method is the case worth stating outright**, because it looks like a call and
is not. The *function* was compiled by the library; what crosses the boundary is the **caller's
copy**, laid out to the fields as they stood when that caller was built. Adding a field would then
break it silently — precisely the failure the modifier exists to prevent. `*self` and `&self` need no
shape and stay reachable, which is what makes them the forms an opaque type's methods take.

**The reach is the declaring module exactly**, not a subtree the way `private[M]` widens (`13 §2`).
What `opaque` buys is that a field may move with nothing downstream recompiled, and the set of files
that must recompile together is the module — its files share one scope (`13 §1`), so they are already
one unit for this, and a submodule is already not.

**It is not a visibility, and the two are independent.** `vis` decides who may say the *name*;
`opaque` decides who may know the *shape*. A public type may be opaque, which is the whole point of
one; a `private` type may be opaque too, and simply has nobody left to be opaque to.

**Rejected: Swift-style resilient value types**, which compute layout at runtime from metadata. Every
field access becomes an indirect load, which is why Swift itself added `@frozen` to opt back out. The
cost here is an indirection at the *interface* — a pointer the caller already holds — rather than at
every access forever.

Codegen needs nothing for it. Pointers lower to `ptr` (`codegen.md`), so a `*Opaque` downstream never
asks for the aggregate, and the check is entirely a front-end rule.

## 10. A definition may name the convention it is entered under

**`interrupt` before a definition says the processor enters it, not a caller.** It is written where a
visibility modifier is, on the declaration rather than folded into `extern`, because it is about a
*definition* — the handler is code this program supplies.

```
interrupt timer()               // RISC-V: takes nothing
interrupt(supervisor) trap()    // ...at a named privilege level

interrupt fault(f: *Frame)      // x86-64: the ABI requires the frame
```

**One concept, three answers, and every one of them was read off clang rather than out of a
document.** This is the fact the whole design turns on:

| processor | what `interrupt` is | the signature it demands |
|---|---|---|
| **x86-64** | an LLVM calling convention, `x86_intrcc` | a pointer to the frame the hardware pushed, optionally then an integer error code |
| **RISC-V** | a function *attribute*, `"interrupt"="machine"` | nothing at all |
| **AArch64** | it does not exist | — |

So the annotation names the **concept** and the back end decides what that becomes. A directive that
spelled `x86_intrcc` would put one machine's answer in a source file and be wrong on the other two —
and the author could not be told, because the build that breaks is elsewhere. This is the same shape
as §8's rule that a link directive names a library rather than a flag, and it is the same reason.

**On a processor without it, the annotation is refused rather than ignored.** Clang answers
`__attribute__((interrupt))` on AArch64 with "unknown attribute ignored" and compiles an ordinary
function. That is defensible for C, where an attribute is advisory by tradition. It is not defensible
here: the handler then returns with `ret` where the machine needs `eret`, having saved none of the
registers an asynchronous entry clobbers, so the failure is silent and arrives as corruption in
whatever was interrupted. §1 already refuses the annotated/unannotated split everywhere else, and
this is the same refusal.

**Nothing about this is portable, and the design does not pretend otherwise.** An interrupt handler
is the least portable code there is — it is entered by a mechanism the processor defines, and even
the number of arguments differs. What the compiler owes is that the source says which machine it is
for and that building it for another fails loudly, which is exactly what the table above buys.

**AArch64's absence is not an oversight to fill in later.** Its exception entry goes through a vector
table the processor indexes by cause, where each entry is a fixed-size slot of instructions — so the
entry point is assembly by construction, and there is nothing for a convention on a sysl function to
describe.

**A handler is an entry point, so it is a root of the reachability walk** (§5 step 7's pruning). No
program calls one, and calling one is refused outright: it leaves through a return-from-interrupt
that would unwind a frame the call never pushed. A walk starting from what the program *runs*
therefore cannot reach it, and dropping it would leave the vector table pointing at nothing — a fault
at the worst available moment. Its address is still worth taking, which is what fills that table.

**The rules are about the signature, so they are checked on the declaration** rather than while a
body is walked. A generic handler nothing instantiates has no body analyzed at all, and it is exactly
as wrong as one that does.

`interrupt` is a **soft keyword**, and what keeps it one is that a name must follow it. Three things
start with that word and only the first is a convention: `interrupt timer()` declares a handler,
`interrupt(n: int) -> int` declares a function *called* `interrupt`, and `interrupt(4)` calls one.

**The annotation carries a name and an optional argument** — RISC-V's privilege mode is the argument
today — so a second convention is a change to what the analyzer accepts rather than to every tree
that holds a function. `extern` still implies the C ABI with nothing written, which is what the
overwhelming majority of foreign declarations want.

## 11. A source file may be a document with the program inside it

**A file named `.lsysl` is Markdown, and the part of it indented four columns is the program.**
Everything else is prose and is not compiled. There is no other marker: no name on a block, no
directive opening one, no way to say that a block ends — a `.lsysl` file with no prose in it is a
`.sysl` file with four spaces down the left, and a `.sysl` file is never read this way whatever its
indentation happens to look like. Which of the two a file is, its **name** decides and nothing else.

This is Knuth's *WEB* with its most famous half deliberately left out. WEB let an author write the
program in the order that explains it and had the tangler put it back into the order the compiler
needs, which is the feature that made a `.web` file unreadable without its tools. Here the code
appears in the order it runs and the tangler only removes prose, so the file is legible as it sits —
by a reader, by a Markdown renderer, and by `grep`. What is kept is the part that pays: room for an
argument between two functions, in a place a comment cannot hold it.

**Consecutive indented blocks are one block.** A paragraph between two of them does not end anything,
so a function body can be explained a step at a time — the prose dedents to column zero, and the code
resumes at the indentation it left off at. Without this the format would only be good for examples,
which is the length at which it is not needed.

**A fenced block is an illustration.** ` ``` ` and `~~~` mark code that is to be *looked at* rather
than run — the wrong version beside the right one, a shell transcript, output, a fragment of C — and
none of it is compiled however it is indented. Code that runs is indented; code that is shown is
fenced. A chapter needs both, and confusing them is the one thing this format could get wrong that
would not be visible on the page.

### Positions survive, and that decides the implementation

**Prose is blanked, not removed.** The text handed to the lexer has exactly as many lines as the
file, and each line of program text is on the line it was written on — so every position the lexer
records is already a position in the `.lsysl` file. There is no mapping table and no pass below the
parser knows that any of this happened. It is the same device conditional compilation already uses
on a branch this build is not for (`targets.md`), and the two compose in that order: **tangle, then
gate**, so a `#if` written inside the program is an ordinary directive by the time the gate sees it.

The alternative — concatenating the code blocks and compiling the result — is a third of the code and
loses the line numbers, which are the difference between a diagnostic and a puzzle. That it also
happens to be what a Markdown library would hand you is worth stating plainly: the AST such a library
produces carries the block's *text* and not where the text was, so the shortest path to this feature
is the one that cannot be made to work.

The one coordinate that does move is the **column**, by the four that made the line code. The source
carries how far, and it is added back where a position is *reported* rather than where it is
recorded, so a location names the column of the file the reader has open.

### Two things are refused that Markdown would accept

Both are the same failure — a program silently missing a piece of itself — and neither can be
diagnosed later, because what reaches the compiler afterwards is a program that is merely smaller
than the author's.

**A tab in the indentation.** A tab is as wide as whatever is displaying it, so a tab-indented line
is program text in one editor and prose in another. It is refused *before* the four-column test
rather than failing it, since failing it is the silent outcome: the line becomes prose, and a
function quietly loses a statement.

**A fence that is never closed.** Markdown runs an unclosed fence to the end of the document, which
for a document is harmless. Here it turns every declaration below it into an illustration, and the
diagnostic the author would get is about something incomplete further up — their missing half never
enters the story. Refused at the line that opened it, which is the line to go and look at.

### `sysl doc` renders one, and what it puts back is smaller than it sounds

A `.lsysl` file is already Markdown, so the obvious objection to a renderer is that it has nothing to
do. What it has to do is restore the one thing the format gives up: **the program is marked by an
indent**, which is Markdown's own spelling of a code block and is exactly what makes the file
readable unrendered — and an indented block carries no *language*. Nothing can highlight it, and
nothing scanning for the code can find it. `sysl doc` re-fences each block as ` ```sysl ` and passes
prose, illustrations and heading levels through as written. It is not a formatter and has no opinion
about anybody's Markdown.

It is a **source-level** command and asks for no target, no standard module and no library, which is
the whole of why it is usable: a package's prose is worth reading on a machine that could not build
it. What it does share with a compilation is the *reading* — the same pass decides which lines are
program — because a document that rendered happily out of a file the compiler refuses would be
documentation of a program that does not exist.

**The other product a `doc` command usually means — a page per module, generated from declarations
and the comments above them — is not this, and is not built.** It cannot be yet, for a reason that
has nothing to do with rendering: a comment here is lexical trivia, dropped by the lexer, reaching no
tree. What that product needs first is a decision about whether a documentation comment is a
*construct*, which is a question about the language.

## 12. `@export` makes a definition C-callable

**`@export` is `12` §1's `extern` read the other way.** An `extern` names a symbol the linker has and
states the signature the other side published; an `@export` publishes a symbol and states the
signature C may call it at. Neither invents a shape the other end could not spell, and they are
spelled alike:

```
extern exit(code: int) -> never          -- resolves the symbol 'exit'
extern "opendir" c_opendir(p: *u8) -> *Dir   -- resolves 'opendir', under another sysl name

@export                                  -- publishes the symbol 'add'
add(a: i32, b: i32) -> i32 = a + b

@export("mylib_parse")                   -- publishes 'mylib_parse'
parse(text: *u8, n: usize) -> i32
```

**The rename is the form that matters rather than a convenience.** A C library's symbols share a
prefix so that linking two of them is not a coin toss, and the sysl side has a module path doing that
job already. `parse` in module `mylib` is the name to write inside sysl and `mylib_parse` is the name
to publish; requiring the function to be *called* `mylib_parse` everywhere would be spelling the
module path twice. **A sysl caller is unaffected** — it still names `mylib.parse` and simply arrives
at a different label.

**`@export` implies the C convention and says nothing about any other**, which is `12` §5's rule for
`extern` in the other direction. Whether §10's convention annotation composes with it is left until
something needs a convention other than C; §10 was built so that adding one is a change to what the
analyzer accepts rather than to every tree that holds a function.

### Why this direction exists at all

A C replacement is adopted **incrementally**, and until now only one of the two directions was
possible. Putting sysl on top and keeping the C underneath — adding libraries one `@link` at a time —
works and is what every package in the org does. But a C shop with an existing program and an
existing build had no way in: `build-lib` makes a `.syslib` for sysl consumers, and every definition
carried its module path, so there was nothing to hand a C linker and no symbol for it to resolve.

### A boundary layer, not an arbitrary function

**What gets written is a facade** — one file whose job is the export surface, where the author
restricts themselves on purpose to signatures C can spell. That is the same thing a Scala program
exposing itself to Java or JS grows, and it is why the refusals below cost so little: they fire only
inside a file somebody wrote to be the boundary, where the restriction is the point rather than a
surprise.

**The marking is per-definition even so**, because the symbol name has to be. A file-level mark could
not name `mylib_parse` and `mylib_free` separately, so it would have needed a second per-definition
attribute beside it, and one mechanism beats two. `@export` is an ordinary declaration attribute,
carrying an optional parenthesised argument exactly as `@align(n)` does.

### What cannot be exported

None of these is a decision — each is a consequence, and the work is only in saying so where somebody
tries:

| refused | because |
|---|---|
| a **generic** | an exported symbol is one function at one signature, and there is no way to say which instantiation the linker holds |
| a **member** | C has no receiver to hand it. The grammar refuses this before any rule does: an attribute is read at statement position only, so `@test` and `@pure` are as unavailable on a method |
| a **`private`** definition | `private` emits the symbol `internal` (§3), which promises every caller is inside the module; an export promises the opposite, and a definition cannot make both claims |
| a **`@ghost`** | it is erased before codegen (`17` §8), so there is no symbol at all |
| a **`@test`** | only `sysl test` builds one, and an exported symbol has to be in the artifact a C project links |
| a **variadic** | what a C caller promotes into the tail is decided by the prototype it compiled against, not by this declaration. A `va_list` parameter states the same thing and is what C's own `v` variants do |
| a parameter or result that is **not a scalar, a pointer or a function pointer** | see below |
| a symbol that is **not a C identifier** | there would be nothing a C declaration could spell |

**The type rule is `CAbi`'s existence stated as a restriction.** A scalar and a pointer are one
register on every machine sysl lowers for and nothing has to be decided about them. An aggregate is
the opposite: each ABI says which registers a struct arrives in, LLVM applies no rule of its own, and
`CAbi` exists precisely because sysl's own lowering and C's published one differ. Passing one by
value would be a **corrupt call rather than a link error**, which is the worst available outcome, so
it is refused rather than lowered hopefully. Every refusal names the shape to write instead — a slice
becomes the pointer and length C's own buffer functions already take, an aggregate becomes a pointer
to itself — because there always is one, and that is what makes the boundary writable rather than
merely restricted.

### Module storage: a computed `val` cannot be reached

Module storage is filled by the entry point (`13` §7), and **a C project supplies its own `main`** —
so nothing sysl emitted runs before the C side calls in. An exported function that reached storage a
computed initializer would have written would read whatever the loader left, which is a silent wrong
answer rather than a link error. It is refused, and the walk is transitive: the `val` may be three
calls down, and the diagnostic names the export that reached it.

**A `val` whose initializer is constant data is fine and is not looked at**, because nothing runs to
fill it — a constant tree is written straight into the object file. That is the rule C already has
for a static-storage initializer, so it needs no explaining to the audience it is for, and it is why
the restriction bites so rarely. A `const` has no storage at all and never arises.

There is no generated `sysl_init()` the host must call, and no `llvm.global_ctors` registration.
The first is silent zeroed storage when somebody forgets it; the second trades the diagnostic for a
link-order dependency that does not exist on every target. Either can be added later without
invalidating anything here.

### What the driver produces

`sysl build-c` is `build-lib`'s shape with a different destination — a **static archive** holding the
module's object and the objects of any C the compilation's trees carry (§7), plus a **C header**
beside it:

```
sysl build-c mylib -o libmylib.a       # writes libmylib.a and libmylib.a.h
sysl emit-header mylib                 # the same declarations, on stdout
```

The compilation is the ordinary one rather than a library build, because what is wanted is a module
lowered for *this* target with its calls resolved. What differs from `build` is the **entry point**,
which is suppressed — the same switch a library build has always used, reached from a second command
— and the ending, an archive rather than a link.

**An exported function is a reachability root**, exactly as an interrupt handler is (§10) and for the
same reason: nothing inside the program calls it, and the whole point is that something outside will.
A build with no entry point is what makes this load bearing, since every other root is absent there.

**The header is a translation and holds no decisions**, which is why it is the cheapest of the three
pieces: the marking is what does the work. It uses `<stdint.h>`'s fixed-width names because sysl's
integers say what they *are* where C's say what they are *at least* — an `i32` is `int32_t`, and
writing it `int` would be right on every machine anyone is likely to use and wrong as a claim. A
`char` is a Unicode scalar value and becomes `uint32_t`, never C's `char`, which would be wrong by a
factor of four.

**The one fact C cannot take from a type name is divergence**, since `never` and `unit` both spell as
`void`. A function returning `never` is therefore annotated — `SYSL_NORETURN void spin(void)` — where
the macro resolves to C11's `_Noreturn`, C++11's `[[noreturn]]`, or nothing on a compiler older than
either. It is written as a macro because the header serves both languages and `_Noreturn` is not valid
C++; the empty definition makes a weaker declaration rather than an invalid one. What the annotation
buys the caller is real: code after the call is dead, and a path ending in it needs no return value.

**The header assumes C99 or any C++**, which the three includes already did before anything said so.

**The standard module is compiled into the archive**, which is the one thing `build-c` decides
differently from every other command. A `.syslib` is not something a C link line can carry, so an
archive referring to one fails at that link naming `sysl$prints` — a symbol whose author has no way
to place it. An archive stands alone or it is not an artifact, and there is nothing here for a flag
to choose between: `--std-lib` is refused rather than discarded, and `--no-std-lib` asks for what
already happens.

The cost, which is accepted: two `build-c` archives linked into one program each carry the reachable
part of the library, and duplicate whatever they share.

**Every tree of §7's table is walked here, and "objects on the link line" means archive members**,
since this is the command whose link line the archive *is*. So a `--lib` source root's C and a
dependency's C land beside the project's own, and a package can carry a shim a C consumer will
resolve — which is most of what §7 is for. The archive being one flat namespace is what decides the
one refusal: `LibraryArtifact.nativeMember` names a member after the path within its own tree, so two
trees that hold a C file at the same internal path map to one name, and that is reported rather than
letting `ar r` replace by name and ship an archive quietly missing half of what one of them defined.

**What the archive does not hold is what this build's own libraries supply** — `libm`, and whatever
`@link` named — and the driver says so rather than leaving it to be found at the C project's link,
where an unresolved symbol reads as a missing definition rather than as a missing archive. Those are
libraries the author chose and can hand to a linker, which is exactly the distinction the standard
module fails.

## Open (not yet decided)

- **b. The symbol-length threshold** at which §2 truncates and appends a hash.
- **c. Recording file discovery.** Whether the `readdir` of step 1 is written into a build log, so
  that adding a file to a module is a *visible* change for reproducible or sandboxed builds rather
  than an invisible one.
