# Capabilities

**Status:** core mechanism decided and **both halves built**. `@no_alloc` and `@requires(...)` are
read from a file's header, the files of a module are held to agreeing, and the narrowing is enforced
against the module's own constructions and against what its calls arrive at. The **target half** is
built too: `package.hocon` declares what each target provides (`packages.md § 2`), a module's
effective set is that intersected with its own narrowing, and `@requires` is finally answered against
a machine rather than being documentation.

**The clauses are written as attributes**, in the notation `@test` and `@tailrec` already used. They
were once grammar — `no alloc`, `requires alloc` — which reserved `no`, `alloc` and `requires` and
took the most natural name in an allocator away from the code that provides one. Nothing about the
model changed with the spelling; what changed is that a capability is now said *about* a module
rather than being a construct the language executes, which is what it always was.

**The two environment capabilities are enforced too, against the module graph.** Each became
checkable the day the library grew a module declaring it — `sysl.fs` for `os`, `sysl.posix.tty` for
`posix` — because what an environment capability gates is a whole module, so the rule is that a module
which may not have one may not *reach* one that needs it, directly or through anything in between.
**Both ways of not having it are asked at that same edge**: the module gave it up, or the target never
provided it. The second is the half that reads as the surprise, so it is worth stating plainly —
a program is refused for reaching `sysl.fs` on a machine its own `package.hocon` says has no operating
system, with no clause written anywhere. **All three are narrowings now.** The order is the one the
next capability will arrive in: declared, then gated by something, then narrowable — a clause
enforcing nothing would read in a source file as a guarantee the compiler never made, so it is
refused for as long as that is what it would be.

Capabilities govern the allocator / OS / POSIX boundary
that lets one language span safe application code and allocator-free kernel/driver code. The
mechanism spans three layers — project config (targets declare capabilities), the module
system (propagation, per-module restriction), and the type system (`heap` gates the memory
modes). **The target registry is now its own doc and is built — `targets.md`** — so what a
target *is* and how one is named are settled, and the project-config half around it is now
`packages.md`: the `package.hocon` schema, per-target capability sets, and the dependency model
that lets a *package* carry a capability requirement the way a module does. `packages.md` is
**built for the capability half and for the dependency model both** — the file is parsed, its
per-target sets are what the two-level rule below is now measured against, and a `dependencies`
block is fetched, resolved and checked against `sysl.sum`. What is unbuilt there is the commands
that would edit the file for you (`sysl add`, `vendor`), which changes nothing about this doc.
This one is the capability model.

## Two kinds of capability

Capabilities are named environment facts, and they come in two kinds, enforced by different
parts of the compiler:

- **`heap` — a *language* capability.** It changes what the type system allows. With `heap`,
  the heap-backed features are legal: `&T`, `weak T`, growable arrays, boxed trait objects
  (`&Trait`), escaping closures, and allocating string operations. Without it, only the
  **no-alloc subset** compiles: value types `T`, raw pointers `*T`, fixed arrays `[N]T`,
  slices `[]T`, static data, string literals, and bounds-checked indexing. What is gated is
  *creating* heap storage, not *holding* it — allocator-free code may keep and release a `&T`
  or heap-backed slice it was handed, because every ARC object carries its own deallocation
  hook (`03`). The **type-checker** enforces this — it is what makes "the kernel allocates
  nothing" a checked guarantee rather than a convention.
- **`os` / `posix` — *environment* capabilities.** They do not change the language; they
  gate which **standard-library modules exist**. `sysl.fs` requires `os`; everything under
  `sysl.posix` requires `posix`. Enforced against the **module graph**, not by the type-checker: the
  unit is the reference from one module to another, and the diagnostic lands at the reference rather
  than at the clause, because that is the line a reader has to change.

`heap` is the load-bearing one for the memory model. The other two layer on the same two-level
machinery but act at the import boundary.

## The core set

| Capability | Kind | Gates | Implies |
|---|---|---|---|
| `heap` | language | `&T`, `weak T`, growable arrays, `&Trait`, escaping closures, allocating string ops | — |
| `os` | environment | OS / syscall standard-library surface | — |
| `posix` | environment | everything under `sysl.posix` — threads, `termios`, `getentropy` | `os` |

`posix` implies `os` (POSIX needs an OS); config validation enforces the implication. The set
is extensible, but these three are the core.

**A fourth, `threads`, was here and has been removed** — the one capability ever taken *out* of this
set, and the reason is worth keeping because it is the test the next addition has to pass. It gated
one module, `sysl.thread`, and it read as a claim that the compiler tracks whether a scheduler
exists. It does not, and nothing in the library was gated on that: what `sysl.thread` is built on is
pthreads. So the module became `sysl.posix.threads`, requires `posix`, and the capability had nothing
left to say. **A target with a scheduler of its own — FreeRTOS, Zephyr, a kernel written in sysl —
has threads and no POSIX**, and binds its own kernel as a package; no capability could have made
`pthread_create` appear on it. **Nothing gates a package**, and nothing needs to: what a package
requires is stated in its `package.hocon` and checked against the target's set exactly as a module's
clause is.

**The capability is `heap` and the clause that gives it up is `@no_alloc`, and the two names differ
on purpose.** The config states whether a facility **exists** — a noun, beside `os` and `posix` —
because whether a heap exists is a project engineering decision about the machine being
built for. A narrowing clause is a module's promise about its own **conduct**, and a promise is about
an action: *I do not allocate, so I do not need a heap to exist.* The other two need no second word,
since for them giving the facility up and not using it are the same act.

`@no_heap` is refused, naming `@no_alloc`: it says a machine has no heap, which is the project's to
say and not a module's.

**The config accepts `alloc` and maps it, transitionally**, and that is compatibility rather than a
second spelling. A **tag is immutable**: every package is fetched at a pinned version whose
`package.hocon` says `requires { alloc = true }` and always will, and a fetched dependency's file is
validated exactly as the project's own is — so refusing the old word would stop every pinned
dependency resolving on the day it shipped, and re-tagging cannot help a consumer that has not also
bumped its pin. `heap` is the name to write; the allowance goes once the packages have been swept.

**What gates spawning is not soundness**: what may cross a domain boundary is a structural rule
(`06`), checked at a `@crossing` parameter — which any facility may write, and no capability decides.
`posix` gates the module that can start a *pthread*, and a module may narrow it away to declare
itself single-threaded; a package binding an RTOS is outside that gate entirely and inside the
crossing rule all the same, which is the division to keep in mind. Note that
a **fixed-capacity** channel needs neither `heap` nor `posix` to exist — allocator-free code can
still receive on one, which is the same reason `sysl.sync` requires nothing at all while
`sysl.posix.threads` requires an operating system.

## Two levels: target provides, module narrows

1. **The project declares what is available**, in the project config — for every target it builds
   for, with a target block layering over it per capability where one machine differs:
   ```hocon
   capabilities { heap = false }        // this project's own policy, everywhere

   targets {
     aarch64-macos { capabilities { heap = true } }   // except on the workstation
     aarch64-kernel { triple = "aarch64-none-elf", capabilities { os = false, posix = false } }
   }
   ```
   The top-level block is the one that says what the project *is*. Keyed only by target the
   statement could not be made at all for a machine the registry already has, since a block would
   then read as a target being redefined rather than as a policy being declared.
2. **A module inherits the target's capabilities by default.** Ordinary application code writes
   *no* capability clause and simply uses whatever the target provides — zero ceremony for the
   common case.
3. **A module may narrow below the target**, in source — the enforcement lever:
   ```
   module oskit.arch
   @no_alloc           // allocator-free, enforced: &T in this module is a compile error
   ```
   This lets you write provably allocator-free kernel/driver code **even on a hosted target
   that has an allocator**. The boundary is compiler-checked, not a naming convention.
4. **Using a gated feature requires the capability in the module's *effective* set**
   (`target ∩ narrowing`). Otherwise it is a compile error at the use site — e.g. `&T` in a
   `@no_alloc` module: *"references need an allocator; not available here."*

The optional other direction — a module that fundamentally needs a capability — is `requires`:
```
module std.heap
@requires(heap)     // one clean error if built for a target with no heap,
                    // instead of one error at every &T
```
`requires` is documentation plus an early, precise diagnostic; it is not needed for
correctness (using `&T` already implies the requirement).

## Propagation through imports

A capability requirement flows through the module graph. A module's real requirement is its own
uses **plus the requirements of everything it imports**, and the whole transitive graph must
fit within the target's capabilities.

- A `@no_alloc` module can only import and call things that are themselves allocator-free.
  Importing `std.heap` (which `requires heap`) from a `@no_alloc` module is an error — cleanly,
  at the import, not deep in codegen.
- A `no os` module may not reach any module that requires `os`. **This is built**, and the graph it
  is asked of is the **reference** graph rather than the import graph: a qualified path reaches
  another module's declarations with no import at all (`13 §3`), so a rule stated over imports would
  have missed the case a program is most likely to write. The requirement is transitive — reaching
  `sysl.fs` through a module that says nothing itself is still reaching it.
- **A module on a target that provides no `os` may not reach one either, and that needs no clause** —
  it is the ceiling half of the two-level rule, so a module which inherits the target's capabilities
  by default inherits their absence with them. It is asked at the same edge, in the same walk, and
  differs only in what the message names: a clause the reader wrote where there is one, and the
  config that understated the machine where there is not.

**Whose modules are asked, and why a library's are not.** The edge that gets refused starts in a
module the compilation is **producing** — the program's own, or the library's own where a library is
what is being built. A module handed *to* it is exempt: the standard module's, a `--lib` source root's,
a fetched package's. The reason is what the check is for. A library holding one POSIX module is not a
library that a POSIX-less target cannot use; it is a library one module of which that program cannot
reach — and refusing at the library's own clause would refuse a build over a module the program never
names, in a file its author did not write and cannot change. So the target half is asked at the
reference, where the answer is a line somebody chose to write.

**Where the `heap` diagnostic actually lands, and why it is the call rather than the import.** The
rule above is stated over modules, and the standard library is why the check cannot be: `sysl` is
one module and is **half allocator-free**. `print` and `from_utf8` are declarations of the same
module and only one of them allocates, so a module-grained rule would refuse every `@no_alloc` module
that named anything at all — including the printing that allocator-free code does constantly. So the
question asked is the one this section's first bullet also asks: what does this module *call*. A
`@no_alloc` module is refused where it reaches a function that makes heap storage, and the message
points at the smallest part of the body that still reaches it.

The reachable set **over-approximates** where a call's target is decided at run time — a method-table
slot is answered with what every table for that trait put there — which is the direction to be wrong
in, since a refusal then names a function the program might really arrive at. An `extern` is not
followed at all: what a C function does is not this compiler's to know, and `capabilities.md` already
allows an allocator-free module the `malloc` and `free` it provides itself through `*T`.

### A generic answers for what it wrote, not for what its caller chose

A generic has no execution until a type is chosen, and the module that chose it is not the module
that declared it. So the promise is asked of **the body as written** — the one the definition-time
pass of `14 §4` checks, with each type parameter standing for itself — and a monomorphized instance
answers for nothing at all.

That is not a redirection of blame, because the two kinds of call inside a generic body are
different things:

- a call to a **concrete** function is the declaring module's conduct at every instantiation, and is
  charged to it;
- a call **through a bound** is the caller's choice of type, and is charged to nobody here — the
  module that made the choice is the one holding whatever `impl` answers it, and its own body is
  walked exactly as any other.

The distinction needs nothing recorded at a call site. In the body as written, a bound's call names
the **trait's** member — a name no program links, since every implementation is somewhere else —
while a concrete call keeps the name it always had. Substitution is what makes the two identical, so
the answer is read before it happens.

Three consequences follow, and each is the rule rather than an exception to it:

- **A construction in the generic's own body is still the declaring module's.** `boxed[T](x: T) -> &T`
  makes a reference at every instantiation, whatever `T` is, so an allocator-free module may not
  write one.
- **A trait's default body is the trait's module's**, for the same reason: it is written once, in the
  trait's own file, and is the same at every implementing type.
- **A generic calling a generic leads to the body that was written**, so a module cannot promise
  `no alloc` and then reach an allocator through a one-line generic of its own.

**None of this weakens the promise where it is load-bearing.** On a target that provides no heap
every module is allocator-free with no clause written anywhere, so the module that chose the type is
itself checked, and the walk from its body goes straight through the instance to whatever the type
argument dragged in. What the rule gives up is a refusal aimed at the wrong file; it gives up no
refusal.

## What `heap` gates, precisely

**Requires `alloc`** (heap-backed):

- **creating** a `&T` or a `weak T` (ARC boxes, weak-tracking) — which is an ordinary
  construction in a position expecting one (`03`), so this is the construction the type checker
  rejects, not the type;
- growable arrays (append / realloc);
- boxed trait objects `&Trait`;
- **escaping** closures (a closure that outlives its scope is heap-boxed, as in Swift — but
  which closures escape is **inferred**, not annotated, see `05`);
- allocating string operations (building / concatenating a new `string`).

**Available without `alloc`** (the no-alloc subset):

- value types `T`, fixed arrays `[N]T`, slices `[]T`, `*T` raw pointers, static data;
- bounds-checked indexing on arrays and slices;
- string **literals** (static) and **non-escaping** closures (inlined, no box);
- **holding, passing, and releasing** references and heap-backed slices created elsewhere —
  retain and release need no allocator, and the free path goes through the object's own hook. That
  hook is the header's single function word, asked which of its two jobs is wanted: run over the
  contents when the strong count reaches zero, and give the storage back when the weak count does.
  It is installed by whoever built the box, so the bytes go back to the allocator that made them —
  and a module that builds no box emits no hook, and therefore names no `free` at all;
- manual `malloc` / `free` you provide yourself via `*T` — the allocator's own building blocks.

What remains a compile error is *making* heap storage: `&T`, `weak T`, growable arrays, boxed
trait objects, escaping closures, and the allocating string operations. A slice of a local
array that escapes its frame is also an error here: with an allocator the compiler would
promote the array to the heap, and there is nothing to promote into (`05`).

## The payoff

| | Hosted app | Kernel (heap, no os) | Cortex-M (no heap) |
|---|---|---|---|
| ordinary module | everything | `&T` ok; no `sysl.fs` | no-alloc subset only |
| `@no_alloc` module | no-alloc subset, enforced | no-alloc subset, enforced | compiles unchanged |

A `@no_alloc` module is portable across every target — write the arch layer once, guaranteed
allocator-free everywhere.

## Open sub-questions

- ~~**A generic has no execution until a type is chosen, and the chapter never said whose conduct it
  is**~~ — **done**, and the rule is § *A generic answers for what it wrote* above. A generic is
  charged from the body as written and an instance from nobody, which is what lets an allocator-free
  library be instantiated at a type whose `impl` allocates.

  **Two attempts at it failed first, and both failed by asking the wrong tree.** Excusing the
  instance alone lets

  ```
  thru[T](x: T, s: string) -> usize = cstring(s).len
  ```

  compile with no heap anywhere, because that call appears nowhere but inside an instance body;
  marking which calls came from a bound was then priced as machinery on every call site. What was
  missing from both is that the body **as written** already draws the line — a bound's call names the
  trait's member and a concrete call does not — and that the definition-time pass had analyzed it and
  thrown it away.


- ~~**Per-module `os` / `posix` restriction**~~ — **done.** A module may assert `no os` or
  `no posix`, and the assertion is enforced against the module graph. What settled it was not a
  decision but an arrival: the question was academic while nothing required `os`, and `sysl.fs` is
  what gave the clause something to refuse.
- **Config / module-resolution details** — **now written, in `packages.md`**: the `package.hocon`
  schema, per-target capability sets, and a package-level `requires` block. Filename-axis platform
  selection is the one piece still unwritten. **The target registry itself is done (`targets.md`)**:
  a target is a value with a name, a triple, and the ABI facts codegen reads, and `--target` selects
  one. What it deliberately does *not* carry is capabilities — that is exactly the part a project
  has an opinion about, so it belongs to the config rather than to the fixed table, and
  `packages.md § 2` fixes the line: **capabilities are policy and ABI is not**, so a config may add
  capabilities to a registry target but may not overrule a measured fact.
  **Expect the project config to be revisited repeatedly** as the language grows. That advice stands
  and `packages.md` deliberately went past it — it designs versioning, resolution and vendoring
  ahead of anything needing them, at the user's direction and with the risk recorded in its own
  status header. Nothing there is built, so it is a design to be checked against a first
  implementation rather than a constraint on one.
- **`requires` granularity** — module-level only, or also finer? Module-level is the unit a
  *declaration* is written at, and that has not changed. What did change is that the **question**
  turned out to be finer than the declaration: `alloc` is checked against what a module calls, for
  the reason § *Propagation* gives, so a `requires alloc` written on `sysl` would say something
  false about most of it. Whether `requires` should be writable on a declaration — so that a library
  can mark the handful of its functions that need an allocator, rather than the module that holds
  them — is the open half, and the standard library is the case that asks for it.
- **A module is the unit, and rendering is what does not fit in it.** Both guide programs written to
  be allocator-free hit the same thing when the clause arrived, one function apart: a machine that
  makes no heap storage sits beside the function that turns its error into a sentence, and that
  function builds a string. `guide/bytecode`'s `vm` carries the clause and its `describe` moved out
  to the caller — which is the shape freestanding code has anyway, so the clause found the seam
  rather than creating one. `guide/kernel` cannot carry it at all, because its machine and its checks
  are one module. Nothing here is wrong; what it says is that **a module is a coarse unit for this
  question**, and it is the same observation the granularity bullet above makes from the other end.
- **Narrowing is now enforced for all three.** Each became a narrowing the day there was something
  for it to gate, exactly as this bullet predicted when it named only `alloc`: `os` and `posix` when
  `sysl.fs` was written. The refusal that carried them until then refuses nothing today and stays for
  the next capability, which will arrive declared before it is gated.
- ~~**The clause spends two ordinary words, and one of them is wanted.**~~ — **CLOSED. The clauses
  are attributes**, `@no_alloc` and `@requires(...)`, and an attribute's name arrives as an ordinary
  identifier rather than through the lexer's reserved list. `no`, `alloc`, `requires` and `link` are
  all names a program may use again.

  The answer went further than this bullet proposed. It suggested keeping the clause and reading an
  ordinary identifier in the capability's *position*, which would have freed `alloc` and left `no`
  and `requires` spent. Moving the whole family into the notation sysl already had for saying
  something *about* a declaration frees all of them, and it makes the header one notation rather than
  two: `@test` and `@tailrec` were already written that way.

  `guide/slab` is the file that reports it, because it is the one that paid — an allocator's central
  function is called `alloc` in every language that has one, and that guide had to call it `take`.
  It is called `alloc` now.
