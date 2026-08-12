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

**The three environment capabilities are enforced too, and against the module graph rather than
against a target.** Each became checkable the day the library grew a module declaring it — `sysl.fs`
for `os` and `posix`, `sysl.thread` for `threads` — because what an environment capability gates is a
whole module, so the rule is that a module which gave one up may not *reach* one that needs it,
directly or through anything in between. **All four are narrowings now.** The order is the one the
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
- **`os` / `posix` / `threads` — *environment* capabilities.** They do not change the language; they
  gate which **standard-library modules exist**. `sysl.fs` requires `os`; `sysl.thread` requires
  `threads` and `posix` both, since pthreads is what it is built on. Enforced against the **module
  graph**, not by the type-checker: the unit is the reference from one module to another, and the
  diagnostic lands at the reference rather than at the clause, because that is the line a reader has
  to change.

`heap` is the load-bearing one for the memory model. The other three layer on the same two-level
machinery but act at the import boundary.

## The core set

| Capability | Kind | Gates | Implies |
|---|---|---|---|
| `heap` | language | `&T`, `weak T`, growable arrays, `&Trait`, escaping closures, allocating string ops | — |
| `os` | environment | OS / syscall standard-library surface | — |
| `posix` | environment | POSIX compatibility layer | `os` |
| `threads` | environment | `sysl.thread` — spawning, joining, and the growable channel | — |

`posix` implies `os` (POSIX needs an OS); config validation enforces the implication. The set
is extensible, but these four are the core.

**The capability is `heap` and the clause that gives it up is `@no_alloc`, and the two names differ
on purpose.** The config states whether a facility **exists** — a noun, beside `os`, `posix` and
`threads` — because whether a heap exists is a project engineering decision about the machine being
built for. A narrowing clause is a module's promise about its own **conduct**, and a promise is about
an action: *I do not allocate, so I do not need a heap to exist.* The other three need no second word,
since for them giving the facility up and not using it are the same act.

Each spelling is refused where the other belongs, naming it, because somebody who wrote one meant the
other: `@no_heap` says a machine has no heap, which is the project's to say and not a module's, and
`capabilities { alloc = false }` names what a module does where a facility belongs.

`threads` gates *spawning*, not soundness: what may cross a domain boundary is a structural
rule (`06`), and one that has no check behind it until the channel is written. A target with no
scheduler simply does not offer it, and a module may narrow it away to declare itself
single-threaded. Note that a **fixed-capacity** channel needs neither `heap` nor `threads` to
exist — allocator-free code can still receive on one, which is the same reason `sysl.sync` requires
nothing at all while `sysl.thread` requires two.

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

- **A GENERIC HAS NO EXECUTION UNTIL A TYPE IS CHOSEN, AND THE CHAPTER NEVER SAID WHOSE CONDUCT IT
  IS.** Nothing above mentions generics, type parameters, bounds or monomorphization, and the
  compiler's answer — a monomorphized instance is charged to the module that **declared** the generic
  — is therefore neither stated nor decided here. It bites a real case: a `@no_alloc` library whose
  generic calls through a bound is refused when a **hosted** program instantiates it at a type whose
  `impl` allocates, although the library promised nothing about a type it never saw and the program's
  own project config says a heap exists.

  **The clause is a promise a module makes about its own conduct** — *no execution that begins in this
  module's code makes heap storage* — so on that reading the module that chose the type is the one
  that answers for it. What makes the fix less than a redirection of blame is that the two kinds of
  call inside a generic body are different: a call to a **concrete** function is the declaring
  module's conduct at every instantiation, and only a call **through a bound** is the caller's choice.

  Excusing the whole instance was tried and is **too blunt**, measured rather than reasoned: it lets

  ```
  thru[T](x: T, s: string) -> usize = cstring(s).len
  ```

  compile on a target with no heap at all, because that call appears nowhere but inside the instance
  body. After substitution a bound's call is indistinguishable from a direct one, so drawing the line
  needs the instantiation to mark which calls came from a bound.


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
- **Narrowing is now enforced for all four.** Each became a narrowing the day there was something
  for it to gate, exactly as this bullet predicted when it named only `alloc`: `os` and `posix` when
  `sysl.fs` was written, `threads` when `sysl.thread` was. The refusal that carried them until then
  refuses nothing today and stays for the next capability, which will arrive declared before it is
  gated.
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
