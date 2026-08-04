# Capabilities

**Status:** core mechanism decided, and the **module half is built** — `no alloc` and `requires`
are read from a file's header, the files of a module are held to agreeing, and `no alloc` is
enforced both against the module's own constructions and against what its calls arrive at. The
**target half is not**: nothing declares what a target offers, so a module's effective set is
everything it did not narrow away, and `requires` against a *target* is documentation until there is
one that could fail to satisfy it.

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
system (propagation, per-module restriction), and the type system (`alloc` gates the memory
modes). **The target registry is now its own doc and is built — `targets.md`** — so what a
target *is* and how one is named are settled; what is still to be written is the project-config
and module-resolution half around it (the HOCON `package.hocon` schema, per-target capability sets,
and filename-axis platform selection). This one is the capability model.

## Two kinds of capability

Capabilities are named environment facts, and they come in two kinds, enforced by different
parts of the compiler:

- **`alloc` — a *language* capability.** It changes what the type system allows. With `alloc`,
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

`alloc` is the load-bearing one for the memory model. The other three layer on the same two-level
machinery but act at the import boundary.

## The core set

| Capability | Kind | Gates | Implies |
|---|---|---|---|
| `alloc` | language | `&T`, `weak T`, growable arrays, `&Trait`, escaping closures, allocating string ops | — |
| `os` | environment | OS / syscall standard-library surface | — |
| `posix` | environment | POSIX compatibility layer | `os` |
| `threads` | environment | `sysl.thread` — spawning, joining, and the growable channel | — |

`posix` implies `os` (POSIX needs an OS); config validation enforces the implication. The set
is extensible, but these four are the core.

`threads` gates *spawning*, not soundness: what may cross a domain boundary is a structural
rule (`06`), and one that has no check behind it until the channel is written. A target with no
scheduler simply does not offer it, and a module may narrow it away to declare itself
single-threaded. Note that a **fixed-capacity** channel needs neither `alloc` nor `threads` to
exist — allocator-free code can still receive on one, which is the same reason `sysl.sync` requires
nothing at all while `sysl.thread` requires two.

## Two levels: target provides, module narrows

1. **The target declares what is available**, in the project config:
   ```hocon
   aarch64-kernel { triple = "aarch64-none-elf", capabilities { alloc=true, os=false, posix=false } }
   ```
2. **A module inherits the target's capabilities by default.** Ordinary application code writes
   *no* capability clause and simply uses whatever the target provides — zero ceremony for the
   common case.
3. **A module may narrow below the target**, in source — the enforcement lever:
   ```
   module oskit.arch
   no alloc            // allocator-free, enforced: &T in this module is a compile error
   ```
   This lets you write provably allocator-free kernel/driver code **even on a hosted target
   that has an allocator**. The boundary is compiler-checked, not a naming convention.
4. **Using a gated feature requires the capability in the module's *effective* set**
   (`target ∩ narrowing`). Otherwise it is a compile error at the use site — e.g. `&T` in a
   `no alloc` module: *"references need an allocator; not available here."*

The optional other direction — a module that fundamentally needs a capability — is `requires`:
```
module std.heap
requires alloc      // one clean error if built for a no-alloc target,
                    // instead of one error at every &T
```
`requires` is documentation plus an early, precise diagnostic; it is not needed for
correctness (using `&T` already implies the requirement).

## Propagation through imports

A capability requirement flows through the module graph. A module's real requirement is its own
uses **plus the requirements of everything it imports**, and the whole transitive graph must
fit within the target's capabilities.

- A `no alloc` module can only import and call things that are themselves no-alloc-compatible.
  Importing `std.heap` (which `requires alloc`) from a `no alloc` module is an error — cleanly,
  at the import, not deep in codegen.
- A `no os` module may not reach any module that requires `os`. **This is built**, and the graph it
  is asked of is the **reference** graph rather than the import graph: a qualified path reaches
  another module's declarations with no import at all (`13 §3`), so a rule stated over imports would
  have missed the case a program is most likely to write. The requirement is transitive — reaching
  `sysl.fs` through a module that says nothing itself is still reaching it.

**Where the `alloc` diagnostic actually lands, and why it is the call rather than the import.** The
rule above is stated over modules, and the standard library is why the check cannot be: `sysl` is
one module and is **half allocator-free**. `print` and `from_utf8` are declarations of the same
module and only one of them allocates, so a module-grained rule would refuse every `no alloc` module
that named anything at all — including the printing that allocator-free code does constantly. So the
question asked is the one this section's first bullet also asks: what does this module *call*. A
`no alloc` module is refused where it reaches a function that makes heap storage, and the message
points at the smallest part of the body that still reaches it.

The reachable set **over-approximates** where a call's target is decided at run time — a method-table
slot is answered with what every table for that trait put there — which is the direction to be wrong
in, since a refusal then names a function the program might really arrive at. An `extern` is not
followed at all: what a C function does is not this compiler's to know, and `capabilities.md` already
allows an allocator-free module the `malloc` and `free` it provides itself through `*T`.

## What `alloc` gates, precisely

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
  retain and release need no allocator, and the free path goes through the object's own hook;
- manual `malloc` / `free` you provide yourself via `*T` — the allocator's own building blocks.

What remains a compile error is *making* heap storage: `&T`, `weak T`, growable arrays, boxed
trait objects, escaping closures, and the allocating string operations. A slice of a local
array that escapes its frame is also an error here: with an allocator the compiler would
promote the array to the heap, and there is nothing to promote into (`05`).

## The payoff

| | Hosted app | Kernel (alloc, no os) | Cortex-M (no alloc) |
|---|---|---|---|
| ordinary module | everything | `&T` ok; no `sysl.fs` | no-alloc subset only |
| `no alloc` module | no-alloc subset, enforced | no-alloc subset, enforced | compiles unchanged |

A `no alloc` module is portable across every target — write the arch layer once, guaranteed
allocator-free everywhere.

## Open sub-questions

- ~~**Per-module `os` / `posix` restriction**~~ — **done.** A module may assert `no os` or
  `no posix`, and the assertion is enforced against the module graph. What settled it was not a
  decision but an arrival: the question was academic while nothing required `os`, and `sysl.fs` is
  what gave the clause something to refuse.
- **Config / module-resolution details** — the HOCON `package.hocon` schema, per-target capability
  sets, and filename-axis platform selection are still to be written. **The target registry
  itself is done (`targets.md`)**: a target is a value with a name, a triple, and the ABI facts
  codegen reads, and `--target` selects one. What it deliberately does *not* carry is
  capabilities — that is exactly the part a project has an opinion about, so it belongs to the
  config rather than to the fixed table.
  **Expect the project config to be revisited repeatedly** as the language grows, and
  especially once external libraries and dependency management arrive. Design only the minimum
  that unblocks the work at hand (root, active target, capabilities, build flags, platform-file
  selection); let real needs drive versioning, dependency resolution, workspaces, and
  publishing rather than guessing at them upfront.
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
- **The clause spends two ordinary words, and one of them is wanted.** `no alloc` and `requires
  alloc` are read by the lexer, so `no`, `alloc` and `requires` are all reserved and none of them may
  name anything. `guide/slab` ran into it immediately: an allocator's central function is called
  `alloc` in every language that has one, and that is the one name it cannot have — it is spelled
  `take` there. The cost is small and the shape of it is not, because it falls hardest on exactly the
  code that *provides* the capability the word gates. Neither the clause nor the word is wrong; what
  is worth deciding is whether a capability's name has to be a keyword at all, or whether the clause
  can read an ordinary identifier in that position the way `import` reads a module path — which
  would give `alloc`, `os` and `posix` back to programs and keep the clause reading the same.
