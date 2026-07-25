# Design Decisions: Generics

**Status:** ratifies the generic surface the implementation already carries — type-parameter
lists on functions, structs, and enums; monomorphization; and bidirectional inference — and
settles the one decision the code left implicit and got backwards: **bounds**. The
implementation today is *unbounded* (a type parameter may be used for anything, and a misuse is
caught only when it is instantiated); this chapter commits sysl to **bounded, definition-checked**
generics, matching `02-traits.md`'s already-written promise that by-value polymorphism is "a
generic bounded by the trait, monomorphized," and marks the current unbounded checking as
bring-up scaffolding to tighten.

This chapter rests on `02-traits.md` (a bound *is* a trait), `03-memory-model.md` (why every
value is copyable, which decides what an unbounded parameter may do), and `09-enums-and-
patterns.md` (`Option`/`Result` are generic enums). The `?` operator and the error types are
`11-error-handling.md`.

---

## 1. What is generic, and how a parameter is written

A **type parameter list** in square brackets makes a function, struct, or enum generic:

```
id[T](x: T) -> T = x                     // generic function
struct Pair[A, B]                        // generic struct, two parameters
    first: A
    second: B
enum Option[T]                           // generic enum (09)
    Some(value: T)
    None
```

A parameter name stands for a type not yet known; it may appear anywhere a type may — a
parameter type, a return type, a field type, a variant payload, a local annotation. The three
declaration forms are the whole of what can be parameterized; a generic type's *own body* may hold
members, while a generic `impl` block is still open (`§ Open c`).

## 2. `[]` means type application in a type, indexing in an expression

Square brackets are reused for two things, disambiguated by **position**, with no new token:

- In a **type**, `Box[int]` and `Result[Box[int], string]` *apply* type arguments to a generic
  type. Nesting is ordinary.
- In an **expression**, `a[0]` *indexes*. `a[0]` parses as an index, never as a type
  application.

This reuse is deliberate and unambiguous because a type and an expression never occupy the same
grammatical slot. The cost is that **explicit type arguments at a call site collide with
indexing** — `id[int](7)` in expression position reads as "index `id` by `int`, then call" — so
call-site type arguments are not offered; inference (§4) supplies them instead, and the one
case inference cannot reach is answered by annotating the result, not by a turbofish (`§ Open a`).

## 3. Type arguments and construction

Applying a generic type names a concrete instance: `Box[int]` is the type of a box of `int`,
`Pair[int, string]` a pair. Constructing one is the ordinary construction of `09`/`03` —
`Box(41)`, `Pair(1, "one")` — with the type arguments *inferred from the arguments* rather than
written. The memory mode is the usual per-declaration choice: `Box(41)` is a value unless a
`&Box[int]` is expected.

## 4. Inference is bidirectional

Type arguments are inferred, and from two directions — this is settled and tested:

- **From the arguments.** `id(7)` infers `T = int`; `Pair(1, "one")` infers `A = int, B =
  string`; inference reaches *through* a generic construction, so `id(Box(id(5)))` solves every
  nested parameter.
- **From the expected type**, when the arguments cannot determine a parameter. `empty[T]() ->
  Option[T] = None` has nothing in its (empty) argument list to fix `T`; the declaration `var e:
  Option[real] = empty()` supplies it, and `T` resolves to `real`. The return/expected type
  flows *inward* to pin a parameter the call site left open.

The model is unification: each parameter is solved by matching the declared parameter and
return types against the actual argument types and the expected type. When a parameter is left
undetermined by **both** directions, that is a compile error asking for an annotation on the
binding (`var e: Option[real] = …`) — never a silent default and never a stuck inference
variable.

## 5. Bounds — the core decision

A type parameter is **bounded by a trait**, and the bound is what the body of a generic is
allowed to assume about the parameter. This is the decision the implementation had backwards,
and it is settled here.

### An unbounded parameter permits only what *every* type supports

With no bound, `T` may be used only for the operations every sysl value has, which — because of
the memory model — is a genuinely useful set:

- **copied, assigned, passed, returned, and stored** in a struct field, enum payload, array, or
  slice.

That set is exactly `id[T]`, `Box[T]`, `Pair[A, B]`, and every other container: they *move data
around* without inspecting it, and they need no bound. Crucially, **every sysl value is
copyable** — assignment copies, and copying a value holding a `&T` retains it (`03`) — so there
is **no `Copy` bound to write, ever**. This is a real simplification over Rust, where `T` is
move-by-default and `T: Copy` / `T: Clone` litter generic signatures. In sysl, "hold and hand
along any `T`" is the free, unmarked baseline.

What an unbounded `T` may **not** do is anything that assumes structure: no `+`, `<`, `==`, or
other operator; no method call; no field access; no index; no cast. Each of those is a
capability some types have and others do not, so each requires a bound that guarantees it.

### A bound is a trait, written `[T: Trait]`

```
sum[T: Add](a: T, b: T) -> T = a + b               // + is available because T: Add
min[T: Ord](a: T, b: T) -> T = if a < b then a else b
```

`a + b` inside `sum` type-checks **because** `T: Add` promises the operator; drop the bound and
`sum` fails *at its own definition* with "`+` needs `T: Add`," pointing at the line that made the
unsupported assumption. This is the whole payoff over the unbounded/template model: the error
lands on the **definition that is wrong**, not on some caller three files away that instantiated
it with the wrong type. It is the Swift/Kotlin/Scala consensus (principle #2 — all three check
bounds at the definition; only C++ defers to instantiation), and it is what `02-traits.md`
already committed to.

Operators are available through bounds because operators *are* trait methods (`00 §9`,
Rust-style overloading): `+` is `Add`, `<` is `Ord`, `==` is `Eq`. The exact operator-trait set
is the standard library's to define; generics only fix that **an operator on a `T` requires the
bound that supplies it**.

**Multiple bounds** join with `+`: `[T: Ord + Hash]` requires both. The `+` reads unambiguously
in a bound position (it is between trait names, not values) and matches the Rust spelling that
sysl's Rust-flavored trait system already implies. A `where`-clause form for long or complex
bound lists is a possible ergonomic extension (`§ Open d`); the inline `[T: A + B]` form is the
settled baseline.

## 6. Static dispatch (generic) vs dynamic dispatch (trait object)

A trait is used two ways, and this is the pivot between them — worth stating plainly because it
is the question a programmer faces every time polymorphism comes up:

- **`[T: Trait]` — static, monomorphized.** The compiler generates a specialized copy per
  concrete `T` (§7). Calls are direct, inlinable, allocation-free; the cost is code size and
  that the set of types is fixed at compile time. This is the default and the right choice for
  the overwhelming majority of generic code.
- **`&Trait` / `*Trait` — dynamic, one copy.** A trait object (`02`) dispatches through the
  object's method table at runtime. One copy of the code serves all types; the cost is an
  indirect call and no inlining. Reach for it when the set of types is open or heterogeneous —
  a list of mixed shapes, a plugin boundary — where monomorphizing is impossible or wasteful.

Same trait, two strategies, chosen by how you *spell the parameter* — a bound for static, a
sigil-carried trait object for dynamic — with no `dyn` keyword either way (`02`). By-value
generic bounded code is the norm; the trait object is the escape hatch for genuine runtime
heterogeneity.

## 7. Monomorphization

Each distinct set of type arguments produces its **own** specialized function or aggregate —
`id` called at `int` and `real` emits two functions (`id.int`, `id.real`), a `Box[int]` and a
`Box[string]` are two distinct layouts. This is settled and tested (the IR carries exactly one
`define` per instantiation, not one per call), and it is why bounds can be checked once at the
definition yet lowered to direct, monomorphic code with no dictionary passed at runtime.

- **The cost is code size**, the standard monomorphization tradeoff (C++ templates, Rust
  generics). It is the right default for a systems language, where the direct, inlinable call
  matters and the dynamic path (`§6`) is available when one copy is preferable.
- **Recursion is fine.** A recursive generic function recurses at a *fixed* instantiation, so it
  monomorphizes like any other; `countdown[T]` calling itself is one specialization per `T`.

## 8. Variance does not arise

There is no variance question in sysl, by construction. Variance is about when `G[A]` may stand
in for `G[B]`, and that needs a subtyping relation `A <: B` to be interesting — which sysl does
not have among concrete types (no inheritance; a trait bound is a constraint, not a supertype
relation between values). `Box[Cat]` and `Box[Animal]` are simply unrelated types. This deletes
an entire category of design difficulty that afflicts languages with nominal subtyping, and it
should stay deleted: polymorphism over a set of types is expressed by a bound or a trait object,
never by a covariant container.

---

## Open (not yet decided)

- **a. Explicit call-site type arguments.** Inference (§4) plus result annotation covers every
  case the implementation exercises, and the naive `id[int](7)` spelling collides with indexing
  (§2). If explicit arguments prove necessary, they need a disambiguating syntax (a Rust-style
  turbofish marker, or a rule that a type-argument list is only read in a call head). Deferred
  until a real case cannot be served by inference.
- **b. Bounds on struct/enum parameters.** §5 settles bounds on *function* parameters, which is
  where the implementation exercises them. Whether a *type's* parameter may carry a bound
  (`struct SortedList[T: Ord]`) — and whether such a bound is required at the type or re-stated
  at each method — is open, and ties into (c).
- **c. Members on generic types.** Methods and properties on a generic struct *or enum* are
  settled and implemented: the member is instantiated from the receiver's own type arguments, so
  `Box[int].get` and `Box[real].get` are two monomorphized functions exactly as two instantiations
  of a free generic function are. What remains open is the part with nothing to infer from — an
  **associated function** on a generic type (no receiver to read the arguments off) and a member
  carrying **its own** type parameters — both deferred with a diagnostic. A generic `impl` block is
  the other half, and it is what decides where a type parameter's bounds are declared.
- **d. `where` clauses.** An out-of-line bound syntax for readability when the inline `[T: A +
  B]` list grows long or involves relations between parameters. All of Rust/Swift/Kotlin have
  one; a candidate ergonomic addition, not a day-one need.
- **e. Const generics.** Parameterizing over a *value* — most importantly an array length,
  `[N: usize]` — so a function can be generic over `[N]T`. Not implemented (array sizes are
  literals today); a clear eventual want for fixed-size numeric and buffer code, deferred until
  the array story calls for it.
- **f. Higher-kinded parameters.** Parameterizing over a *type constructor* (`F[_]`) is
  **excluded**, not merely deferred: it pushes inference toward undecidable, and no target use
  (an OS, drivers, embedded) needs it. Abstraction over containers is served by traits and
  bounds, not by HKT.
