# Traits (Polymorphism)

**Status:** decided (core model). Some surface-syntax details are flagged open at the end. How a
plain method and its receiver are spelled — the hole this doc used to leave open — is settled in
`08-methods.md`; a trait's methods are declared and called the same way, with the receiver an
`impl`'s type instead of a concrete one.

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
- **Trait-object ownership is expressed through the three memory modes — no `dyn` keyword.**
  The old structural interface had an invisible `owns` flag (escape analysis silently chose
  share-vs-heap-copy); that violated the "costs are visible" rule and is **removed**. A trait
  object is inherently a fat pointer `{ vtable, data }` (an erased value has unknown size), so
  it is spelled with a memory-mode sigil — the sigil already says everything a `dyn` keyword
  would:
  - `*Trait` → a **raw** fat pointer: a non-owning / unmanaged trait object, used as easily as
    any C pointer (borrowing; kernel / no-allocator contexts);
  - `&Trait` → an **ARC-owned** trait object (refcounted heap box, like any `&T`).

  `*T` / `&T` on a *concrete* type is a thin pointer; on a *trait* it is a fat
  (vtable-carrying) pointer — the trait-ness makes it fat. There is no `dyn`.
- **By-value polymorphism is generics, not a trait object.** When the concrete type is known,
  bound a generic by the trait (`f[T: Trait](x: T)`): the struct moves by value, monomorphized,
  zero indirection — as easy as C. A trait object is only for *type-erased* polymorphism, and
  because an erased value has unknown size it always lives behind `*Trait` / `&Trait`, never by
  value.
- **Retrofitting is preserved** — you can `impl` your trait for a type you don't own; you just
  do it explicitly rather than by implicit structural match.
- **Any type may carry an `impl`, the built-ins included.** `impl Show for int` is as ordinary as
  `impl Show for Point`, and `5.show()` resolves by the same rule `p.show()` does. This is not a
  convenience: a `Show` that cannot cover `int` is a `Show` no library can be written against, and
  the prelude's own `Show` — the one that lets `str` stop being a compiler builtin, and lets
  `print`'s desugaring aim at a method instead of six names (`04`, *Printing*) — is the first thing
  that needs it. Every type has one **owner key** its members are filed under: a
  struct or an enum by the name it was declared with, everything else by its one canonical name, so
  `impl Show for int` and `impl Show for i32` are the single implementation they are rather than two.
  Two types have no key because they have no behaviour to give: `never`, which has no values, and
  `unit`, which has one.

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

- **Default methods.** Whether a trait may supply default method bodies (as Swift / Kotlin /
  Scala do). Likely yes; specify with the trait-declaration grammar.
- **Laws / invariants on traits.** The old `trait` could assert invariants ("`Ord` is a total
  order"). Whether the unified trait carries such contracts (via `require` / `ensure`-style
  annotations) is deferred to the contracts spec.
- **Trait bounds, associated types, generic interaction** — deferred to the generics spec.
- **An `impl` for a *composed* type.** `impl Trait for Type` names its type with an identifier, so a
  built-in reachable by name (`int`, `string`, `char`) may carry one but a composed type — `*u8`,
  `[]int`, `[4]byte` — cannot yet be spelled there. Additive: it wants the implementing type to be a
  full type reference rather than a name, and a key for each shape.
- **An `impl` for a generic type.** `impl Show for Box[T]` is rejected for now; the implementing type
  must be concrete. Wanted, and it interacts with monomorphizing the members.
