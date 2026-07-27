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
parameter type, a return type, a field type, a variant payload, a local annotation. Three further
forms take parameters without declaring a type: an **`impl` block**, whose subject is a generic type
or a composed shape applied to them (`02`), a **member**, which may be generic over types of its
own beyond its type's (`08`, §4 below), and a **trait**, which is then a family of promises rather
than one (`02`) — `trait Sink[T]` is what an implementation applies to `int` to say what it accepts.

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

**A literal is consulted last**, because a literal has no type of its own to offer (`01`) — it takes
one from where it appears, and a parameter still being solved is not yet a place that can give it
one. So what is already a type settles the parameter first, then the expected type, and a literal's
default only where nothing else reached it: `pick(1, 2, 250u8)` is a `u8` because one argument knew
and two did not, while `id(7)` is still an `int` because none did. Once the parameter is a type the
literals are read against it, which is the same order the operand rule uses inside an expression.

**A parameter that names no type parameter is not part of the question**, and its argument is
checked against it exactly as a plain callee's is. This is worth saying because inference has to
look at the arguments before it knows what anything is, which would otherwise cost a generic callee
the rules that need an expected type: `01`'s literal rule — the parameter's type at a call fixes an
unsuffixed literal — and the coercions to `&T` and to a trait object. A declaration having a `T`
somewhere does not make its `usize` any less a `usize`, so `f[T](x: T, n: usize)` takes `f(v, 7)`
with no suffix, and only a parameter that mentions what is being solved waits for the solution.

**An associated function on a generic type is inferred by exactly this rule**, and that is the whole
reason it needs no machinery of its own (`08`). A *method* never asks the question — its receiver
already is a `Box[int]`, so the arguments are read rather than solved. An associated function has no
receiver, which puts it in the position a generic free function is always in: `Box.of(41)` solves
`T = int` from the argument, and a `none() -> Cursor[T]` solves from `var c: Cursor[int] = …`.
`Self` in the signature is the type applied to its own parameters, so writing `-> Self` and writing
`-> Box[T]` infer alike.

**A member's own type parameters are inferred by it too**, and for the same reason: the receiver
says what the *type's* arguments are and nothing about the member's, which leaves those in the
position the rule already covers. So `with[U](self, x: U) -> Pair[T, U]` on a `Box[int]` receiver has
`T` read and `U` solved — from the argument, or from the expected type where the argument does not
mention it. The two lists are held to their bounds separately and under the name each was written
in: the type's in the type's, the member's in the member's. That a member's parameters and the
type's must not collide is the one thing the two-list form adds, and it is settled by refusing a
member that spells one of its own the way its type spells one of its.

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

**A bound is the trait *applied***, so it carries the trait's own type arguments where it has any
(`02`):

```
take[X: Sink[int]](x: X) -> int = x.put(1)         // x.put takes an int
conv[X: Into[Y], Y](x: X) -> Y = x.into()          // and Y is solved at the call
```

The arguments are ordinary types, which is what lets one name another parameter of the same
declaration — and what a body may then do with that parameter is what *its* bounds promise, so
`[X: Into[Y], Y: Display]` is a body that may print what the conversion yields. Inference does not
run backwards through a bound: a parameter that appears only there is solved from the result type or
annotated, exactly as §4's rule says.

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
- **b. Members on generic types** — settled and implemented, and no longer open. A method or
  property is instantiated from the receiver's own type arguments, so `Box[int].get` and
  `Box[real].get` are two monomorphized functions exactly as two instantiations of a free generic
  function are; an associated function, having no receiver, infers those arguments from the call by
  §4's ordinary rule; a member carrying **its own** type parameters solves those by that same rule
  while the type's are read off the receiver (§4). The same holds for the members a generic `impl`
  adds (`02`). What a *trait* may declare is `02`'s question, and it declares no generic method
  today, so a generic member is an inherent one.
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
- **f. A conversion into a type parameter.** `u8(x)` where `x: T` is ordinary code, because `u8` is
  a type that exists; `T(b)` is "undefined function 'T'", because a parameter is not a name a call
  can be written through. So a generic function can take a value apart and not put one together,
  and a symmetric pair of operations ends up half generic: `guide/sha2` writes `top_byte` once and
  its mirror — building a word out of a byte — twice, as a trait member, where the width is
  concrete. What this wants is either the conversion forms reaching a parameter of known layout, or
  the trait-level member `02 § Reaching a trait's members without a value` wants, since a `T.of(b)`
  would answer it too. The second is the better bet: a conversion into a parameter cannot mean
  anything for a `T` that turns out to be a struct, and a trait member says which types offer it.
