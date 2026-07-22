# Traits (Polymorphism)

**Status:** decided (core model). Some surface-syntax details are flagged open at the end.

sysl has **one** polymorphism mechanism: the **`trait`**. It is nominal, and it supports both
static dispatch (generic bounds, monomorphized) and dynamic dispatch (a boxed trait object).
This replaces the old design's split into compile-time `trait`s and structural runtime
`interface`s.

## The decision

- **One nominal mechanism, named `trait`.** A type participates in a trait only through an
  explicit `impl Trait for Type` — there is **no structural / implicit conformance**. A type
  does not satisfy a trait by coincidence of method names.
- **Both dispatch modes come from the one trait:**
  - **Static dispatch** — a generic bounded by a trait is monomorphized; zero-cost, no
    indirection (`f[T: Trait](x: T)`).
  - **Dynamic dispatch** — a **boxed trait object** is a fat pointer `{ vtable, data }`; calls
    go through the vtable. For heterogeneous collections, drivers, plugin-style designs.
- **Trait-object ownership is expressed through the three memory modes — not inferred.** The
  old structural interface had an invisible `owns` flag: escape analysis silently chose
  share-vs-heap-copy. That violated the "costs are visible" rule and is **removed**. Instead:
  - `dyn Trait` (by value / borrowed) → a **non-owning** view of an existing value;
  - `&Trait` → an **ARC-owned** boxed trait object (a heap box, refcounted like any `&T`);
  - `*Trait` → a raw trait object, for kernel / no-allocator contexts.

  Whether a trait object owns or borrows its data is now visible in the type, reusing the
  memory model rather than a bespoke analysis.
- **Retrofitting is preserved** — you can `impl` your trait for a type you don't own; you just
  do it explicitly rather than by implicit structural match.

## Why (summary; the design audit holds the long form)

- **One mechanism, not two.** Rust (traits) and Swift (protocols) each get static *and*
  dynamic dispatch from a single nominal mechanism. The old dual system made sysl *harder than
  Rust* on polymorphism — against the "easier than Rust" thesis. Unifying cuts a whole concept.
- **Nominal follows the inspirations' consensus** (`principles.md` §2): Swift protocols,
  Kotlin interfaces, and Scala traits are all nominal single-mechanism; only Go — the source
  of the old structural `interface` — is structural. A 3-way consensus over the lone outlier.
- **Nominal is more explicit** — an `impl` documents intent and kills *accidental conformance*
  (a type matching a trait purely by method-name coincidence), which structural typing invites.
- **Kills a hidden cost** — the auto-`owns` flag was invisible allocation/copy behavior,
  exactly what the three-mode model forbids.

## Kept / dropped

- **Kept:** static dispatch (monomorphized bounds), dynamic dispatch (boxed trait object),
  retrofitting foreign types (explicit `impl`).
- **Dropped:** the separate structural `interface`; implicit/structural conformance; the
  invisible `owns` flag (replaced by explicit three-mode ownership).

## Details still to settle

- **Trait-object type spelling.** The working forms are `dyn Trait` / `&Trait` / `*Trait`
  above; confirm whether the borrowed form needs an explicit `dyn` keyword (Rust) or is just
  the bare trait name used as a type. The memory-mode sigil already marks ownership, so a `dyn`
  marker may be redundant — to decide.
- **Default methods.** Whether a trait may supply default method bodies (as Swift / Kotlin /
  Scala do). Likely yes; specify with the trait-declaration grammar.
- **Laws / invariants on traits.** The old `trait` could assert invariants ("`Ord` is a total
  order"). Whether the unified trait carries such contracts (via `require` / `ensure`-style
  annotations) is deferred to the contracts spec.
- **Trait bounds, associated types, generic interaction** — deferred to the generics spec.
