# Capabilities

**Status:** core mechanism decided. Capabilities govern the allocator / OS / POSIX boundary
that lets one language span safe application code and allocator-free kernel/driver code. The
mechanism spans three layers — project config (targets declare capabilities), the module
system (propagation, per-module restriction), and the type system (`alloc` gates the memory
modes). The fuller project-config and module-resolution design (the HOCON `sysl.conf` schema,
target registry, and filename-axis platform selection) is a separate doc, still to be written;
this one is the capability model.

## Two kinds of capability

Capabilities are named environment facts, and they come in two kinds, enforced by different
parts of the compiler:

- **`alloc` — a *language* capability.** It changes what the type system allows. With `alloc`,
  the heap-backed features are legal: `&T`, `weak T`, growable arrays, boxed trait objects
  (`&Trait`), escaping closures, and allocating string operations. Without it, only the
  **no-alloc subset** compiles: value types `T`, raw pointers `*T`, fixed arrays `[N]T`,
  slice-views `[]T`, static data, string literals, and bounds-checked indexing. The
  **type-checker** enforces this — it is what makes "no `&T` in the kernel" a checked
  guarantee rather than a convention.
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

`posix` implies `os` (POSIX needs an OS); config validation enforces the implication. The set
is extensible (e.g. a future `threads` capability), but these three are the core.

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

- `&T` and `weak T` (ARC boxes, weak-tracking);
- growable arrays (append / realloc);
- boxed trait objects `&Trait`;
- **escaping** closures (a closure that outlives its scope is heap-boxed — the Swift
  escaping / non-escaping distinction);
- allocating string operations (building / concatenating a new `string`).

**Available without `alloc`** (the no-alloc subset):

- value types `T`, fixed arrays `[N]T`, slice-views `[]T`, `*T` raw pointers, static data;
- bounds-checked indexing on arrays and slices;
- string **literals** (static) and **non-escaping** closures (inlined, no box);
- manual `malloc` / `free` you provide yourself via `*T` — the allocator's own building blocks.

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
- **Config / module-resolution details** — the HOCON `sysl.conf` schema, the target registry,
  and filename-axis platform selection are a separate design doc, still to be written.
- **`requires` granularity** — module-level only, or also finer? Module-level is the unit for
  now.
