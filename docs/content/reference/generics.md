---
title: Generics
summary: Type parameters, bidirectional inference, bounds checked at the definition, and monomorphization.
weight: 90
---

A **type parameter list** in square brackets makes a function, struct, or enum generic. A parameter
name stands for a type not yet known, and may appear anywhere a type may — a parameter type, a return
type, a field type, a variant payload, a local annotation.

```sysl
id[T](x: T) -> T = x

struct Pair[A, B]
    first: A
    second: B
end Pair

var p = Pair(1, "one")

print(id(7), id("hi"), p.first, p.second)
```

```output
7 hi 1 one
```

Three further declarations take parameters without declaring a type of their own: an **`impl` block**,
whose subject is a generic type or a composed shape applied to them; a **member**, which may be generic
over types of its own beyond its type's; and a **trait**, which is then a family of promises rather
than one. All three are on [traits](/reference/traits/).

## `[]` means type application in a type, indexing in an expression

Square brackets are reused for two things, disambiguated by **position**, with no new token:

- in a **type**, `Box[int]` and `Result[Box[int], string]` *apply* type arguments to a generic type,
  and nesting is ordinary;
- in an **expression**, `a[0]` *indexes*, and never reads as a type application.

The reuse is unambiguous because a type and an expression never occupy the same grammatical slot. The
cost is that **explicit type arguments at a call site collide with indexing** — `id[int](7)` in
expression position reads as "index `id` by `int`, then call" — so they are not offered at all:

```sysl
id[T](x: T) -> T = x

print(id[int](7))
```

```error
'id' cannot be given type arguments at a call; write the type on what receives the result
```

Inference supplies them instead, and the one case inference cannot reach is answered by annotating
what receives the result.

## Construction

Applying a generic type names a concrete instance: `Box[int]` is the type of a box of `int`,
`Pair[int, string]` a pair. **Constructing one is the ordinary construction**, with the type arguments
inferred from the arguments rather than written — `Box(41)`, `Pair(1, "one")`. The memory mode is the
usual per-declaration choice: `Box(41)` is a value unless a `&Box[int]` is expected.

## Inference is bidirectional

Type arguments are inferred, and from two directions.

**From the arguments.** `id(7)` infers `T = int`, and inference reaches *through* a generic
construction, so every nested parameter is solved at once:

```sysl
struct Box[T]
    v: T
end Box

id[T](x: T) -> T = x

var b = id(Box(id(5)))

print(b.v)
```

```output
5
```

**From the expected type**, when the arguments cannot determine a parameter. A nullary generic has
nothing in its argument list to fix `T`, so the declaration supplies it and the return type flows
*inward*:

```sysl
empty[T]() -> Option[T] = None

var e: Option[real] = empty()

e match
    Some(v) -> print("some", v)
    None    -> print("none")
```

```output
none
```

The model is **unification**: each parameter is solved by matching the declared parameter and return
types against the actual argument types and the expected type. When a parameter is left undetermined by
**both** directions, that is a compile error asking for an annotation on the binding — never a silent
default and never a stuck inference variable.

**A literal is consulted last**, because a literal has no type of its own to offer. It takes one from
where it appears, and a parameter still being solved is not yet a place that can give it one. So what
is already a type settles the parameter first, then the expected type, and a literal's default only
where nothing else reached it:

```sysl
pick[T: Add](a: T, b: T, c: T) -> T = a + b + c

print(pick(1, 2, 250u8))
```

```output
253
```

That is a `u8` because one argument knew and two did not, while `id(7)` is still an `int` because none
did. Once the parameter is a type the literals are read against it — the same order the operand rule
uses inside an expression.

**A parameter that names no type parameter is not part of the question**, and its argument is checked
against it exactly as a plain callee's is:

```sysl
at[T](x: T, n: usize) -> string = str(n)

print(at("v", 7))
```

```output
7
```

That is worth saying because inference has to look at the arguments before it knows what anything is,
which would otherwise cost a generic callee the rules that need an expected type — a parameter's type
fixing an unsuffixed literal, and the coercions to `&T` and to a trait object. A declaration having a
`T` somewhere does not make its `usize` any less a `usize`.

### Members and associated functions

**A method never asks the question.** Its receiver already *is* a `Box[int]`, so the type's arguments
are read rather than solved.

**An associated function has no receiver**, which puts it in the position a generic free function is
always in — so it is inferred by exactly the rule above and needs no machinery of its own:

```sysl
struct Box[T]
    v: T

    of(x: T) -> Box[T] = Box(x)
end Box

var b = Box.of(41)

print(b.v)
```

```output
41
```

`Self` in the signature is the type applied to its own parameters, so writing `-> Self` and writing
`-> Box[T]` infer alike.

**A member's own type parameters are inferred the same way.** The receiver says what the *type's*
arguments are and nothing about the member's, which leaves those exactly where the rule already
reaches:

```sysl
struct Pair[A, B]
    first: A
    second: B
end Pair

struct Box[T]
    v: T

    with[U](self, x: U) -> Pair[T, U] = Pair(self.v, x)
end Box

var b = Box(1)
var p = b.with("x")

print(p.first, p.second)
```

```output
1 x
```

The two lists are held to their bounds separately and under the name each was written in. That they
must not collide is the one thing the two-list form adds, and it is settled by refusing a member that
spells one of its own the way its type spells one of its.

## Bounds

**A type parameter is bounded by a trait, and the bound is what the body of a generic is allowed to
assume about the parameter.**

### An unbounded parameter permits only what every type supports

With no bound, `T` may be used only for the operations every sysl value has — which, because of the
memory model, is a genuinely useful set: **copied, assigned, passed, returned, and stored** in a struct
field, an enum payload, an array, or a slice.

That set is exactly `id[T]`, `Box[T]`, `Pair[A, B]`, and every other container: they move data around
without inspecting it, and they need no bound.

```sysl
struct Box[T]
    v: T
end Box

keep[T](x: T) -> Box[T] = Box(x)

var a = keep(5)
var b = keep("hi")

print(a.v, b.v)
```

```output
5 hi
```

**Every sysl value is copyable** — assignment copies, and copying a value holding a `&T` retains it —
so there is **no `Copy` bound to write, ever.** That is a real simplification over Rust, where `T` is
move-by-default and `T: Copy` / `T: Clone` litter generic signatures. Here, "hold and hand along any
`T`" is the free, unmarked baseline.

What an unbounded `T` may **not** do is anything that assumes structure: no operator, no method call,
no field access, no index. Each is a capability some types have and others do not, so each requires a
bound that guarantees it — and each is refused **at the definition**, naming the bound to write:

```sysl
sum[T](a: T, b: T) -> T = a + b

print(sum(2, 3))
```

```error
'+' needs 'T: sysl.Add'
```

A subscript is among them, because a subscript *is* `Index`'s one method, so it is asked of the bounds
exactly as a dot call is.

**A field is the exception that proves the rule.** Every other unlicensed use names the bound that
would allow it — the diagnostic's whole job is to say what to write. A field names none, because a
trait promises *behaviour* and a field is *layout*, so no bound could ever supply one:

```sysl
first[T](x: T) -> int = x.v

print(1)
```

```error
'T' is a type parameter, so it has no fields to read — a field is layout, and no trait declares a property 'v' that a bound could promise instead
```

It is therefore settled outright at the definition rather than deferred to the types that turn up:
`first[T](x: T) = x.v` is wrong even if every call happens to pass a type with a `v`. Reaching a
value's data through a generic means going through a member the bound declares, which is also what lets
two types satisfy one bound while storing the value differently.

`x.v` is spelled like a field and need not be one, so the diagnostic is reached only after looking for
a **property** of that name: a property is behaviour, a trait may declare one, and reading it through a
bound is as ordinary as calling a method.

### A bound is a trait, written `[T: Trait]`

```sysl
sum[T: Add](a: T, b: T) -> T = a + b

smaller[T: Ord](a: T, b: T) -> T = if a < b then a else b

print(sum(2, 3), smaller(9, 4))
```

```output
5 4
```

`a + b` inside `sum` type-checks **because** `T: Add` promises the operator; drop the bound and `sum`
fails *at its own definition*, pointing at the line that made the unsupported assumption. **That is the
whole payoff over the template model: the error lands on the definition that is wrong, not on some
caller three files away that instantiated it with the wrong type.** It is the Swift/Kotlin/Scala
consensus — all three check bounds at the definition, and only C++ defers to instantiation.

Operators are available through bounds because [operators *are* trait
methods](/reference/expressions/#operator-dispatch): `+` is `Add`, `<` is `Ord`, `==` is `Eq`.

**Multiple bounds join with `+`:** `[T: Ord + Hash]` requires both. The `+` reads unambiguously in a
bound position, since it stands between trait names rather than values.

**A bound is the trait *applied***, so it carries the trait's own type arguments where it has any:

| written | means |
|---|---|
| `[X: Sink[int]]` | the body's `x.put(…)` takes an `int` |
| `[X: Into[Y], Y]` | and `Y` is solved at the call |
| `[X: Into[Y], Y: Display]` | a body that may also print what the conversion yields |

The arguments are ordinary types, which is what lets one name another parameter of the same
declaration — and what a body may then do with that parameter is what *its* bounds promise. Inference
does not run backwards through a bound: a parameter appearing only there is solved from the result type
or annotated.

### A type's own parameters carry bounds too

The same bracketed list, in the same place, means the same thing on a struct or an enum — and that is
where the type says what it assumes:

```sysl
struct SortedPair[T: Ord]
    lo: T
    hi: T

    ordered(self) -> bool = self.lo < self.hi
end SortedPair

var s = SortedPair(1, 2)

print(s.ordered())
```

```output
true
```

It buys two things, worth stating separately.

**Everything applying the type must supply it**, wherever the application is written — a declared
parameter, a result, a field of another type, a variant's payload, a construction:

```sysl
struct SortedPair[T: Ord]
    lo: T
    hi: T
end SortedPair

struct P
    v: int
end P

var s = SortedPair(P(1), P(2))

print(1)
```

```error
'SortedPair' requires its type parameter 'T' to implement 'sysl.Ord', but P does not
```

Where the argument is itself a type parameter, the answer is what *its* own bounds promise, so a
function taking a `SortedPair[U]` must bound `U` by at least what `SortedPair` asks. A bound is
satisfied by a bound, one step out.

**And the type's members may assume it, so they are checked at their definition.** That is what having
somewhere to write the bound is *for*: a member of a generic type is walked once, with the parameters
standing in for themselves, by the same pass that walks a bounded generic function — so a method
calling something no bound licenses is reported on its own line whether or not anything instantiates the
type. A generic type's fields are laid out once the same way, which is what catches a field applying
another bounded type to this one's parameter.

**A bound is declared once, at the type, and is in force everywhere its parameters appear** — a
member's signature and body, a field's type, a variant's payload. It is not restated per member.
Restating it is Rust's rule and its own users regret it; declaring once is the Swift/Kotlin behaviour
and the one that matches how the bound reads.

## A parameter may carry a default

A parameter of a **trait**, a **struct**, or an **enum** may name the type to use where a use leaves it
out:

```sysl
struct Pair[A, B = A]
    x: A
    y: B
end Pair

total(p: Pair[int]) -> int = p.x + p.y

print(total(Pair(1, 2)))
```

```output
3
```

The **bound comes first and the default last** — `[R: Show = Self]` — and either may be written without
the other. Four rules govern them:

- **Defaults are filled left to right**, each resolved under the arguments already fixed, so a default
  may name a parameter written **before** it, and naming one written after it is the forward reference
  it looks like.
- **They are a suffix.** A parameter with no default may not come after one that has, because arguments
  are written in order and nothing could leave out the earlier one and still supply the later.
- **The filling happens before anything is keyed on the arguments**, so `Pair[int]` and `Pair[int, int]`
  are one instantiation rather than two that happen to have the same fields.
- **A default is exposed like a field.** A public declaration may not default to a type that reaches
  less far than it does, or a caller who leaves the argument out ends up holding something they could
  not have written and cannot name. And a default may not lead back to the declaration it belongs to,
  directly or through another's.

**`Self` is the case the feature exists for.** In a trait's default it means the implementing type,
exactly as in a method's signature — so `impl Scale for P` is the `impl Scale[P] for P` it reads as,
and `[T: Scale]` asks for `Scale[T]`. A struct and an enum have no implementing type, so `Self` in one
of their defaults is refused. Neither has a **trait object**: an object has forgotten which type it
holds, so a default of `Self` has nothing to name and the argument is written out.

**Only those three declarations may carry one.** A function's, a method's, and an `impl` block's type
parameters are *solved* from what they are given rather than written where they are used, so there is
no argument list with a gap for a default to fill:

```sysl
f[T = int](x: T) -> T = x

print(f(1))
```

```error
'T' is a type parameter of the function 'f', whose type parameters are solved from what it is given rather than written where it is used — so '= int' has nothing to stand in for
```

What would be useful there is a fallback for an inference that found nothing, which is a different
feature; `f[T = int](x: T)` is refused rather than quietly meaning that.

## Converting through a parameter

A conversion is the one capability with no bound to promise it, because a conversion is between the
concrete scalar kinds rather than something a trait declares. **Both directions are written, and both
are checked at each instantiation** rather than at the definition:

```sysl
low[T](x: T) -> u8 = u8(x)

make[T](b: u8) -> T = T(b)

var n: int = make(7u8)

print(low(321), n)
```

```output
65 7
```

`T(b)` is a conversion written where the parameter's name stands, resolved at each instantiation, so
the two directions of one conversion are one rule. An instantiation at a **constrained subtype** or a
**simple enum** takes that type's own checked cast, since the scalar conversion has no meaning for
either and the form written under the type's name does — so `T(x)` at an `Age` is the `Age(x)` a
reader would have written, trap included.

**Construction is deliberately not among the forms it reaches.** A struct's positional constructor
takes a field list rather than a value, and a generic body filling in an unknown struct's fields by
position is not something to arrive at by accident:

```sysl
struct P
    v: int
end P

make[T](b: u8) -> T = T(b)

var p: P = make(7u8)

print(p.v)
```

```error
cannot convert byte to P
```

`T(b)` stays a **conversion** at every instantiation, so at a struct it is refused exactly as `u8(x)`
at one is — naming the struct, and not quietly becoming a constructor. What a container that wants to
build a `T` reaches for is a bound that says so.

**The parameter wins over a declaration of the same name**, which closes an inconsistency rather than
opening one: `var y: T` inside a `[T]` body has always meant the parameter, so `T(x)` one line later
means it too.

## Monomorphization

Each distinct set of type arguments produces its **own** specialized function or aggregate. `id`
called at `int` and at `real` emits two functions; a `Box[int]` and a `Box[string]` are two distinct
layouts. The IR carries exactly one definition per instantiation, not one per call.

That is why bounds can be checked once at the definition yet lower to direct, monomorphic code with no
dictionary passed at runtime.

- **The cost is code size**, the standard monomorphization tradeoff — C++ templates and Rust generics
  make the same one. It is the right default for a systems language, where the direct, inlinable call
  matters and [the dynamic path](/reference/traits/#the-two-dispatch-strategies) is available when one
  copy is preferable.
- **Recursion is fine.** A recursive generic function recurses at a *fixed* instantiation, so it
  monomorphizes like any other.

## Variance does not arise

There is no variance question in sysl, by construction. Variance is about when `G[A]` may stand in for
`G[B]`, and that needs a subtyping relation to be interesting — which sysl does not have among concrete
types. There is no inheritance, and a trait bound is a constraint rather than a supertype relation
between values.

```sysl
trait Animal
    noise(self) -> string

struct Cat
    n: string
end Cat

impl Animal for Cat
    noise(self) -> string = "meow"

struct Box[T]
    v: T
end Box

hear(b: Box[&Animal]) -> string = b.v.noise()

var c = Box(Cat("ada"))

print(hear(c))
```

```error
'b' of 'hear' is Box[&Animal], but Box[Cat] was given
```

`Box[Cat]` and `Box[&Animal]` are simply unrelated types. That deletes an entire category of design
difficulty that afflicts languages with nominal subtyping, and it should stay deleted: polymorphism
over a set of types is expressed by a bound or a trait object, never by a covariant container.

## What is deliberately not here

| absent | why, and what to write instead |
|---|---|
| explicit call-site type arguments | `id[int](7)` collides with indexing; annotate what receives the result |
| `where` clauses | the inline `[T: A + B]` list is the settled baseline; an out-of-line form is a possible ergonomic addition |
| const generics (`[N: usize]`) | array lengths are literals today; deferred until the array story calls for it |
| higher-kinded parameters (`F[_]`) | **excluded**, not deferred — it pushes inference toward undecidable, and abstraction over containers is served by traits and bounds |

The first of those has one position where the annotation costs more than a word. A **nullary** generic
has no argument to be inferred from, so `buf()` and `map()` are solved by what receives the result and
nothing else — and `buf[u8]()` is the first thing a reader tries. That form is refused **by name**,
naming the annotation that stands in for it, rather than by a general complaint about a callee that is
not a name.

---

Next: [modules](/reference/modules/).
