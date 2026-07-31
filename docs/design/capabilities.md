# Capabilities

**Status:** core mechanism decided. Capabilities govern the allocator / OS / POSIX boundary
that lets one language span safe application code and allocator-free kernel/driver code. The
mechanism spans three layers — project config (targets declare capabilities), the module
system (propagation, per-module restriction), and the type system (`alloc` gates the memory
modes). **The target registry is now its own doc and is built — `targets.md`** — so what a
target *is* and how one is named are settled; what is still to be written is the project-config
and module-resolution half around it (the HOCON `sysl.conf` schema, per-target capability sets,
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
- **`os` / `posix` — *environment* capabilities.** They do not change the language; they gate
  which **standard-library modules exist**. `import std.fs` requires `os`; the POSIX
  compatibility layer requires `posix`. Enforced by **module resolution**, not the type-checker.

`alloc` is the load-bearing one for the memory model. `os` / `posix` layer on the same
two-level machinery but act at the import boundary.

## The core set

| Capability | Kind | Gates | Implies |
|---|---|---|---|
| `alloc` | language | `&T`, `weak T`, growable arrays, `&Trait`, escaping closures, allocating string ops | — |
| `os` | environment | OS / syscall standard-library surface | — |
| `posix` | environment | POSIX compatibility layer | `os` |
| `threads` | environment | thread creation and the growable channel | — |

`posix` implies `os` (POSIX needs an OS); config validation enforces the implication. The set
is extensible, but these four are the core.

`threads` gates *spawning*, not soundness: what may cross a domain boundary is a structural
rule the type checker applies regardless (`06`). A target with no scheduler simply does not
offer it, and a module may narrow it away to declare itself single-threaded. Note that a
**fixed-capacity** channel needs neither `alloc` nor `threads` to exist — allocator-free code
can still receive on one.

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
- On a no-`os` target, importing any module that requires `os` fails at resolution.

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
| ordinary module | everything | `&T` ok; no `import std.fs` | no-alloc subset only |
| `no alloc` module | no-alloc subset, enforced | no-alloc subset, enforced | compiles unchanged |

A `no alloc` module is portable across every target — write the arch layer once, guaranteed
allocator-free everywhere.

## Open sub-questions

- **Per-module `os` / `posix` restriction** — allow a module to assert `no posix` for symmetry
  with `no alloc`? Probably yes; secondary to `alloc`.
- **Config / module-resolution details** — the HOCON `sysl.conf` schema, per-target capability
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
- **`requires` granularity** — module-level only, or also finer? Module-level is the unit for
  now.
- **The clause spends two ordinary words, and one of them is wanted.** `no alloc` and `requires
  alloc` are read by the lexer, so `no`, `alloc` and `requires` are all reserved and none of them may
  name anything. `guide/slab` ran into it immediately: an allocator's central function is called
  `alloc` in every language that has one, and that is the one name it cannot have — it is spelled
  `take` there. The cost is small and the shape of it is not, because it falls hardest on exactly the
  code that *provides* the capability the word gates. Neither the clause nor the word is wrong; what
  is worth deciding is whether a capability's name has to be a keyword at all, or whether the clause
  can read an ordinary identifier in that position the way `import` reads a module path — which
  would give `alloc`, `os` and `posix` back to programs and keep the clause reading the same.
