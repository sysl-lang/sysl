# Design Decisions: Generics

**Status:** ratifies the generic surface the implementation already carries — type-parameter
lists on functions, structs, and enums; monomorphization; and bidirectional inference — and
settles the one decision the code left implicit and got backwards: **bounds**. This chapter commits
sysl to **bounded, definition-checked** generics, matching `02-traits.md`'s already-written promise
that by-value polymorphism is "a generic bounded by the trait, monomorphized."

The mechanism is specified in `14 §4`, and it is **built**: a body's method calls, *property reads*,
*operators*, and *renderings* of a type parameter are checked once, at the definition, against the
parameter's bounds alone. `sum[T: Add](a, b) = a + b` type-checks because `T: Add` promises `+`, and
dropping the bound fails on that line rather than at some caller; `print(x)` on a parameter asks for
`T: Display` the same way (`14 §6`). A **field** read off a parameter is refused there too, and with
no bound suggested, because none could license it — see §5.

The same pass checks a trait's **default bodies** (`02`), each as the generic function it is: one
parameter, `Self`, bounded by its own trait.

**A type's own parameters carry bounds too**, and that is built as well: `struct SortedList[T: Ord]`
holds every application of the type to what it asks, and lets its members be checked at their
definition the way a bounded function's body is — see §5.

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
parameter type, a return type, a field type, a variant payload, a local annotation. A fourth form
takes parameters without declaring a type: an **`impl` block**, whose subject is a generic type or a
composed shape applied to them (`02`). A generic type's *own body* may hold members (`§ Open b`).

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

**An associated function on a generic type is inferred by exactly this rule**, and that is the whole
reason it needs no machinery of its own (`08`). A *method* never asks the question — its receiver
already is a `Box[int]`, so the arguments are read rather than solved. An associated function has no
receiver, which puts it in the position a generic free function is always in: `Box.of(41)` solves
`T = int` from the argument, and a `none() -> Cursor[T]` solves from `var c: Cursor[int] = …`.
`Self` in the signature is the type applied to its own parameters, so writing `-> Self` and writing
`-> Box[T]` infer alike.

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

**A field is the exception that proves the rule.** Every other unlicensed use names the bound that
would allow it — the diagnostic's whole job is to say what to write. A field names none, because a
trait promises *behaviour* and a field is *layout*, so no bound could ever supply one. It is
therefore settled outright at the definition rather than deferred to the types that turn up:
`first[T](x: T) = x.v` is wrong even if every call happens to pass a type with a `v`. Reaching a
value's data through a generic means going through a member the bound declares, which is also what
lets two types satisfy one bound while storing the value differently.

`x.v` is spelled like a field and need not be one, so the diagnostic is reached only after looking
for a **property** of that name (`02`): a property is behaviour, a trait may declare one, and reading
it through a bound is as ordinary as calling a method. Where a trait does declare it, the answer is
the bound to write; where none does, there is nothing a bound could be and the field rule above is
the whole of it.

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
bound lists is a possible ergonomic extension (`§ Open c`); the inline `[T: A + B]` form is the
settled baseline.

### A type's own parameters carry bounds too, and that is where the type says what it assumes

The same bracketed list, in the same place, means the same thing on a struct or an enum:

```
struct SortedList[T: Ord]                 enum Tagged[T: Display]
    head: T                                   One(value: T)
    contains(self, x: T) -> bool = …          None
```

It buys exactly what it buys a function, and the two halves are worth stating separately.

**Everything applying the type must supply it.** `SortedList[Point]` is an error unless `Point`
implements `Ord`, and it is an error *wherever* the application is written — a declared parameter, a
result, a field of another type, a variant's payload, a construction. The diagnostic names the
parameter, the trait, and the argument that fails. Where the argument is itself a type parameter,
the answer is what its own bounds promise, so a function taking a `SortedList[U]` must bound `U` by
at least what `SortedList` asks; a bound is satisfied by a bound, one step out.

**And the type's members may assume it, so they are checked at their definition.** This is what
having somewhere to write the bound is *for*: a member of a generic type is walked once, with the
parameters standing in for themselves, by the same pass that walks a bounded generic function — so a
method calling something no bound licenses is reported on its own line whether or not anything
instantiates the type. A generic type's fields are laid out once the same way, which is what catches
a field applying another bounded type to this one's parameter (`struct Wrap[T: Show]` holding an
`Inner[T]` where `Inner` asks `Ord`).

That closes the one asymmetry the implementation used to carry, where a generic `impl`'s members
were definition-checked and a generic *type's* were not. It also means a member that assumed
something the type never asked for is a **new error in code that used to compile**, which is the
migration this rule was always going to require: `struct Box[T]` with an `inc` that adds to its
element is now `struct Box[T: Add]`, and the line naming the missing bound is where to write it.

An unbounded parameter is unchanged in every respect: `Box[T]` holds and hands along any type at
all, and asks nothing of it (§5's baseline). A bound is written where a body needs one, and nowhere
else.

**A bound is declared once, at the type, and is in force everywhere its parameters appear** — a
member's signature and body, a field's type, a variant's payload. It is not restated per member.
Restating it is Rust's rule and its own users regret it; declaring once is the Swift/Kotlin
behaviour and the one that matches how the bound reads.

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
- **b. Members on generic types.** Methods, properties, and associated functions on a generic
  struct *or enum* are settled and implemented: a method or property is instantiated from the
  receiver's own type arguments, so `Box[int].get` and `Box[real].get` are two monomorphized
  functions exactly as two instantiations of a free generic function are; an associated function,
  having no receiver, infers those arguments from the call by §4's ordinary rule. The same holds for
  the members a generic `impl` adds (`02`). What remains open is a member carrying **its own** type
  parameters — `map[U](self, f: …) -> Box[U]` — which is deferred with a diagnostic, and with it the
  question of how a member's parameters and the type's interact in a bound.
- **c. `where` clauses.** An out-of-line bound syntax for readability when the inline `[T: A +
  B]` list grows long or involves relations between parameters. All of Rust/Swift/Kotlin have
  one; a candidate ergonomic addition, not a day-one need.
- **d. Const generics.** Parameterizing over a *value* — most importantly an array length,
  `[N: usize]` — so a function can be generic over `[N]T`. Not implemented (array sizes are
  literals today); a clear eventual want for fixed-size numeric and buffer code, deferred until
  the array story calls for it. It is what an `impl` matching an array's **shape** is missing:
  `impl[T] Total for [3]T` covers every element type at length 3, and each other length needs its
  own block (`02`).
- **e. Higher-kinded parameters.** Parameterizing over a *type constructor* (`F[_]`) is
  **excluded**, not merely deferred: it pushes inference toward undecidable, and no target use
  (an OS, drivers, embedded) needs it. Abstraction over containers is served by traits and
  bounds, not by HKT.
