# Traits (Polymorphism)

**Status:** decided (core model), and **built** — static dispatch through bounds, and dynamic
dispatch through `*Trait` / `&Trait`. Some surface-syntax details are flagged open at the end. How a
plain method and its receiver are spelled — the hole this doc used to leave open — is settled in
`08-methods.md`; a trait's methods are declared and called the same way, with the receiver an
`impl`'s type instead of a concrete one. A signature that has to name the implementing type writes
**`Self`**, specified and built in `14 §1`.

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

## Coherence — where an `impl` may live

An `impl` is **unnamed**: nothing at a use site says which one to apply, so resolving `T: Show` or
`5.show()` means *searching* for an implementation. Once a program is more than one module (`13`),
that search needs a bound, or it would have to range over every module in the program — which is
exactly the property that makes separate compilation impossible.

**An `impl Trait for Type` may appear only in the module that declares `Trait`, or the module that
declares `Type`.** Resolving a bound therefore inspects exactly those two modules, both of which
any module naming both the trait and the type already depends on. No global search, and no
dependency edge that the source does not show.

This is Rust's orphan rule, and it costs nothing the chapter already promised:

- **Retrofitting still works.** `impl MyTrait for TheirType` lives with `MyTrait` — the trait's
  module licenses it. That is the retrofitting kept above, unchanged.
- **`impl Show for int` still works**, and it is the prelude's `Show` that makes it legal: a
  built-in has no module of its own, so a built-in type's owner key belongs to the prelude, and
  every `impl` on one is licensed by its trait's module instead. A trait that could not cover
  `int` is a trait no library can be written against.
- What it forbids is the case with no home: **a foreign trait implemented for a foreign type**,
  where two unrelated modules could each supply a different `impl` and no rule picks one.

An `impl` is part of its module's public surface. Adding, removing, or changing one is an
interface change to that module, visible to everything downstream — the same reasoning that put
`given`/`using`-style implicit resolution out of scope (`13` §7): unrestricted search and separate
compilation are not compatible.

## Trait objects, as built

A trait object is a **fat pointer** — two words, the method table for the type it forgot and the
value itself:

```
{ ptr vtable, ptr data }
```

The sigil says who owns the second word, and nothing else changes between the two:

- **`*Trait`** — the data word is the value's own address. Raw and unmanaged, like every `*T`, and
  the reason `14 §2` reached for a `*Writer`: a sink a kernel can pass around needs no allocator.
- **`&Trait`** — the data word is the reference-counted **box** the value sits in. It counts exactly
  as the `&T` it was erased from does, because the box carries its own destructor (`03`) — so
  letting go of a trait object needs no more knowledge of the payload than letting go of a `&T`
  does, which is the same erasure ARC already relied on for a slice's owner.

**The table is per (trait, type, sigil).** Two flavours rather than one because the data word means
different things: an entry has to reach a receiver, and from a box that is one step further in than
from a bare value. Where the data word already *is* the receiver an implementation declared — a
`*self` method under `*Trait`, a `&self` method under `&Trait` — the entry names the implementation
itself; otherwise it names a small adapter that steps over the box header, loads the value, or both.
So the common case costs one indirect call and nothing else.

### Object safety

Erasure forgets the type, so a method may promise nothing that depends on knowing it. A trait may be
made into an object when every method:

- **has a receiver.** An associated function has nothing to dispatch on.
- **mentions `Self` nowhere but that receiver.** A second `Self` would have to be the *same*
  forgotten type as the first, which is exactly the fact an object no longer carries, and a `Self`
  result has no size to hand back.
- **does not take `&self`, for a `*Trait` only.** `&self` asks for its receiver inside a box, and a
  raw object points straight at a value. A `&Trait` carries one, so it accepts such a method; the
  diagnostic says which sigil to write.

The middle rule excludes **every trait in the operator catalog** — `add(self, rhs: Self) -> Self`
first among them — and that is the right answer rather than a limitation: `14`'s traits describe an
operator over two values of one type, which is a question about types known at compile time. They
are for bounds. It also means every type reaching a table got there through a source `impl`, so
every slot is a function that exists — a compiler-provided membership (`14 §5`) is an instruction,
not something a pointer can name.

### Forming and using one

Erasure is a **coercion**, applied wherever a trait-object type is expected: at an argument, a
declared variable, an assignment, a returned value, an array element, a struct field. `&r` erases to
`*Shape`; a `&Rect` erases to `&Shape`; and a plain `Rect(3, 4)` where a `&Shape` is expected is
boxed and then erased, which is the ordinary "write the construction and it is allocated" rule of
`03` with one more step. A `*Trait` will not take a bare value — a raw pointer needs an address, and
taking one of a temporary silently is how a program acquires a dangling pointer.

Because the coercion applies per branch, an `if` or a `match` whose arms are *different concrete
types* meets at one trait object, which is the point of having them.

The prelude's **`Writer`** is the first trait the language itself forms objects of: `Display` renders
into a `*Writer` so that writing text costs no allocation (`14 §2`), and a program supplies its own
sink with an ordinary `impl`. It is also the one trait the compiler knows a *contract* about beyond
its signatures — a writer borrows the bytes it is handed rather than keeping them — and that is
checked against every implementation rather than assumed (`05`). Nothing about the dispatch changes;
what the identity buys is the escape analysis being able to let a stack-backed slice through.

What an object still offers is the trait's methods, and nothing else: no dereference, no fields, no
comparison (two objects over one value through different traits are the same value and different
tables, so what equality means is the trait's question). A call is checked against the **trait's**
signature, which stands in for every implementation because conformance is exact.

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
- **A property in a trait.** A trait declares method *signatures*, and the property form is
  `name -> T = expr` — a body, which a signature has none of. So a trait cannot require a property
  today, through a bound or through an object. It wants a signature spelling of its own
  (`name -> T`, with no `=`), and it is additive: nothing about either dispatch path changes.
- **`&Trait` is not yet gated on `alloc`.** `capabilities.md` puts a counted trait object behind the
  allocator capability, alongside `&T` itself. Neither is gated, because the capability system needs
  the project config and the module system, and both are still to be written — so this is the same
  gap `&T` already has rather than a new one.
