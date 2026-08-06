# Design Decisions: Generics

**Status:** ratifies the generic surface the implementation already carries — type-parameter
lists on functions, structs, and enums; monomorphization; and bidirectional inference — and
settles the one decision the code left implicit and got backwards: **bounds**. This chapter commits
sysl to **bounded, definition-checked** generics, matching `02-traits.md`'s already-written promise
that by-value polymorphism is "a generic bounded by the trait, monomorphized."

The mechanism is specified in `14 §4`, and it is **built**: a body's method calls, *property reads*,
*operators*, *subscripts*, and *renderings* of a type parameter are checked once, at the definition,
against the parameter's bounds alone. `sum[T: Add](a, b) = a + b` type-checks because `T: Add` promises `+`, and
dropping the bound fails on that line rather than at some caller; `print(x)` on a parameter asks for
`T: Display` the same way (`14 §6`). A **field** read off a parameter is refused there too, and with
no bound suggested, because none could license it — see §5.

The same pass checks a trait's **default bodies** (`02`), each as the generic function it is: one
parameter, `Self`, bounded by its own trait.

**A type's own parameters carry bounds too**, and that is built as well: `struct SortedList[T: Ord]`
holds every application of the type to what it asks, and lets its members be checked at their
definition the way a bounded function's body is — see §5.

**§9 is the exception to "already carries": value generics are designed here and not implemented.**
It is marked as such where it starts, and it is written out rather than left in the open list because
the decisions it takes — the spelling, which values may parameterize a type, and what a length may
*not* be used for — are what an implementation would otherwise take one at a time and inconsistently.

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

### A parameter may carry a default

A parameter of a **trait**, a **struct** or an **enum** may name the type to use where a use leaves
it out:

```
trait Scale[R = Self]                    // the operand type, usually the implementing type
    scale(self, k: R) -> Self
struct Pair[A, B = A]                    // Pair[int] is Pair[int, int]
    x: A
    y: B
```

The bound comes first and the default last — `[R: Show = Self]` — and either may be written without
the other. Defaults are filled left to right, each resolved under the arguments already fixed, so a
default may name a parameter written **before** it and naming one written after it is the forward
reference it looks like. They are a suffix: a parameter with no default may not come after one that
has, because arguments are written in order and nothing could leave out the earlier one and still
supply the later. And the filling happens before anything is keyed on the arguments, so `Pair[int]`
and `Pair[int, int]` are one instantiation rather than two that happen to have the same fields.

**`Self` is the case the feature exists for.** In a trait's default it means the type implementing
the trait, exactly as it does in a method's signature — so `impl Scale for P` is the
`impl Scale[P] for P` it reads as, and `[T: Scale]` asks for `Scale[T]`. A struct and an enum have no
implementing type, so `Self` in one of their defaults is refused. Neither has a **trait object**: an
object has forgotten which type it holds, so a default of `Self` has nothing to name and the argument
is written out — `&Sink[int]` rather than `&Sink`, for a `trait Sink[T = Self]`. `Scale` is not the
example to reach for here, and the reason is worth the line: its `scale` *returns* `Self`, so no
object of it exists at any argument at all (`02`), and `&Scale[int]` is refused for that rather than
for the default.

Two rules follow from a default being the one part of a signature a use does not write. It is
**exposed** like a field, so `13 §2` applies: a public declaration may not default to a type that
reaches less far than it does, or a caller who leaves the argument out ends up holding something they
could not have written and cannot name. And a default may not **lead back** to the declaration it
belongs to, directly or through another's, since each arrival applies the declaration to fewer
arguments than it declares and so asks for the defaults again. A type fully applied inside a default
is not that: nothing is filled, and naming a type inside itself through an indirection is as ordinary
there as it is in a field.

**Only those three declarations may carry one**, and the reason is §2's: a function's, a method's and
an `impl` block's type parameters are *solved* from what they are given rather than written where
they are used, so there is no argument list with a gap for a default to fill. What would be useful
there is a fallback for an inference that found nothing, which is a different feature; writing
`f[T = int](x: T)` is refused rather than quietly meaning that.

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
other operator; no method call; no field access; no index. Each of those is a capability some types
have and others do not, so each requires a bound that guarantees it, and each is refused **at the
definition**, naming the bound to write. A subscript is among them because a subscript *is* `Index`'s
one method (`14 §3`), so it is asked of the bounds exactly as a dot call is.

**A conversion is the exception, and it belongs here as an open question rather than as a rule.**
`u8(x)` on a parameter is *not* refused at the definition: `§ Open f` calls it ordinary code, and
`guide/sha2` is written on it — `top_byte[T: Word](x: T) -> u8` converts out of its parameter, once
per instantiation. It is the one capability with nothing to promise it, because a conversion is
between the concrete scalar kinds rather than something a trait declares, so there is no bound for
the rule above to name. It is therefore checked where the type is known — which is exactly what
every other capability's bound exists to avoid. Whether that stands is undecided: a scalar-shaped
bound would settle it, and so would the trait-level `T.of(b)` that `§ Open f` wants for the
other direction.

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

### A trait object satisfies a bound on the trait it dispatches through

A bound asks whether the members it names can be called on the value, and a `*Trait` / `&Trait`
carries a **table** of exactly those members — so it answers yes, and one generic function serves
both a concrete type and the object erased from it:

```
total[T: Shape](x: T) -> int = x.area()

var o: &Shape = Rect(3, 4)

total(Rect(3, 4))                                  // direct call, one instantiation
total(o)                                           // indirect call, another
```

**Monomorphization is not bent to allow it,** which is why this is a small rule rather than a second
compilation strategy. An object type is a concrete type — a pair of words (`02`) — so §7 instantiates
the body at `&Shape` exactly as it does at `Rect`, once each; what differs is that `x.area()` lowers
to an indirect call in the one and a direct call in the other. That difference was already there
between any two instantiations, and it is decided the same way, by the type the parameter is bound
to. No dictionary is passed, and no body is compiled that was not going to be.

**It is total, and that is object safety's doing rather than a promise made here.** A trait with a
member that cannot be dispatched has no object *at all* in sysl (`02`), rather than an object missing
that member — so a `&Shape` existing is already the proof that every member of `Shape` is reachable
through it. Languages that decide object safety per member owe a rule about what happens when a bound
reaches an absent one; sysl owes none, because the case does not exist.

**A requirement follows with no rule of its own.** `trait Shape: Display` flattens `Display`'s slots
into the object's table (`02`), and a bound on a trait is satisfied wherever a bound on one that
requires it is — so the object meets `[T: Display]` by the same step that a `[T: Shape]` parameter
meets it.

**What this does not license is erasing an object again.** A bound asks what may be *called* through
a value; forming an object asks what may be *assembled* from its type, and a table is laid out from a
type's implementations, which an object has none of. So a `&Shape` satisfies `[T: Display]` and
cannot be converted to a `&Display` — the slots are in its table but a run of slots inside one table
is not a table anything can point at, which is the upcast `02` gives up by flattening. The two
questions look alike and only the first of them is about behaviour.

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

**They are not two worlds, though: a bound takes an object** (§5), so a function written the static
way is not closed to callers who erased. `total[T: Shape]` serves a `Rect` with a direct call and a
`&Shape` with an indirect one, from one signature. The choice above is therefore about what the
*caller* holds rather than about which functions it can reach, and the common case — a library
written against bounds, used by a program keeping a heterogeneous collection — needs no second
signature written the other way.

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

## 9. Value generics

A parameter may stand for a **value** rather than a type, which is what
lets one declaration cover every array length:

```
impl[const N: usize, T: Display] Display for [N]T
    display(self, out: *Writer, fmt: FormatSpec) = self[..].display(out, fmt)

sum[const N: usize](xs: [N]int) -> int
    var t = 0
    for i in 0..<N do t = t + xs[i]
    t

struct Buf[const N: usize]
    data: [N]byte
    used: usize
```

Ada has had this since **1983** (a *generic formal object*), C++ since templates (a *non-type
template parameter*), Rust since 1.51 (*const generics*), and Zig spells it `comptime`. The
languages without it — Go, Java, Kotlin, C# — are the ones where every array is a heap object
carrying its length at runtime, so there is nothing for a value parameter to be *for*. sysl is not
one of those.

### `const` marks it, and it is the same `const` as everywhere else

**A value parameter is a `const` whose value the instantiation supplies**, and it is written that
way. `13 §7 Constants` declares one as `const NAME: Type = expr`; a parameter is that with the
initializer left for the caller, so `[const N: usize]` is the existing grammar in a new position
rather than a new idea. That chapter already says so from the other side, recording constants as
**the prerequisite** for this feature: *"a value cannot be passed as an argument before it can be
named."*

**The marker is not decoration.** `[N: usize]` on its own is indistinguishable from a bounded type
parameter — `[T: Ord]` has the same shape, an identifier and a colon and a name. Only name resolution
could tell them apart, by asking whether the thing after the colon is a trait or a type, and that is
the wrong place for the question: a trait name misspelled into a type name would silently stop
declaring a type parameter and start declaring a value one, with the complaint arriving somewhere
else. Reading a signature would also mean going to look up what the bound is before knowing what
kind of parameter it is.

It settles two more things the unmarked spelling would have to guess at. A bound is a `+`-separated
**list** while a value parameter's type is one type, so `[N: usize + Ord]` would otherwise parse and
mean nothing. And a default is a **type** after a type parameter (`[T = int]`) and an **expression**
after a value one (`[const N = 3]`) — one slot, two grammars, which the parser can only take apart if
it already knows which parameter it is reading.

**`val` is the alternative and `13 §7` rules it out in its own words: "a `val` is a thing, where a
`const` is a value", and "the whole difference from a `const` is an address".** A type argument is a
value and never a thing — there is nothing for a type to hold the address *of* — so `[val N: usize]`
would name the one of the two that cannot be what is meant. It would also promise less than the
parameter delivers, since a `val` is bound at run time: a module's runs before the script body, but
it still runs.

The one place `const` means something else is `[]const u8`, where it marks a view whose elements may
not be written. That is a modifier inside a type rather than a declaration, so the two never occupy
one slot, and Rust carries the identical pair — `*const T` beside `const N: usize` — without anyone
confusing them.

### Which values, and why not all of them

A value parameter puts a value into a **type's identity**: `[3]T` and `[4]T` are different types, so
the compiler must decide when two parameter values are the same value, and must be able to write
that value into a mangled name. Admissible on day one:

**integers, `bool`, `char`, and enum values** — each already compares structurally and already
mangles (`Type.mangleOne`).

The rest are excluded for reasons rather than for now:

- **Floating point** may not be admitted before it is written down that the comparison is on the
  **bit pattern**. `NaN != NaN` under the ordinary comparison, which would make a type not equal to
  itself; C++20 admits floats only inside its "structural type" rule for exactly this.
- **Strings** need interning first, or two spellings of one text are two types.
- **Structs** are the step Rust has left unstable for years (`adt_const_params`), and nothing here
  wants them yet.

### A parameter's kind is the declaration's to state

Whichever kind a parameter is, writing the other one is **refused rather than guessed at**:

```
f[T](xs: [T]int)                   // REFUSED: 'T' is a type; a length is a value
impl[N, T] Tag for [N]T            // REFUSED: the same, one declaration form over
var b: Buf[int]                    // REFUSED: 'int' is a type, and this slot wrote 'const'
var b: Box[4]                      // REFUSED: a value stands here, and this argument is a type
```

The first two are the ones worth stating, because they used to be accepted. A bare name in a length
position could not mean anything before this feature, so it stood at **zero** — harmless while
nothing could put a name there, and a silent wrong answer the day something could. `impl[N, T] Tag
for [N]T` quietly became an implementation for `[0]T`.

The function case has to be caught at the **declaration** rather than where the type is built,
because by then the two kinds are no longer distinguishable: inference reads a length off the
argument's type and binds `T` to what it found, so `T` arrives at resolution holding a value and
looking exactly like a parameter that was declared `const`. The declaration is the last place the
distinction still exists.

### What may be written with `N`, and what may not

`N` is a `usize` (or whatever its declared type is) and may be used as one: as an array length in a
type, and as an ordinary value in a body. It is **not** a term the type system does arithmetic on.

```
f[const N: usize](xs: [N]int) -> [N]int         // fine
g[const N: usize](xs: [N]int) -> [N + 1]int     // REFUSED
```

`[N + 1]T` needs the compiler to decide when two *expressions* denote the same length — that
`N + 1` and `1 + N` are one type, and that `2 * N` and `N + N` are — which is type-level arithmetic
and a different feature entirely. Rust separates them the same way and has kept
`generic_const_exprs` unstable long after const generics shipped. A body may compute `N + 1` freely;
what it may not do is put the result in a type.

### Inference, monomorphization, and what this retires

**A value argument is inferred from the argument's type**, by §4's existing bidirectional rule with
one more thing to unify: matching `[3]int` against `[N]int` binds `N` to 3, exactly as matching
`Box[int]` against `Box[T]` binds `T`. `sum(a)` where `a: [3]int` needs nothing written. Where
nothing can be inferred from, `§ Open a` applies unchanged — the argument is supplied by annotating
what receives the result, since there is still no call-site argument syntax.

**Monomorphization keys on the value arguments beside the type arguments** (§7): `sum` at `N = 3`
and `N = 4` are two functions, exactly as `id` at `int` and `real` are two. Nothing about the cost
model changes, and the length is a constant inside each copy — which is what already makes every
index checkable against one.

**It retired the array-shape workaround, which is what it was for.** An `impl` matching an array
used to be filed under a key that included the length — `[3]`, `[4]` — because there was no way to
be generic over one, so a block covered every element type at *one* length and each other length
needed its own. That is why the library implemented `Display` for every slice and for no array, and
why printing a fixed array was answered by the whole-array view instead.

An array now keeps that per-length key **and** gains a second one under which the length is an
argument beside the element type. Both are consulted, most specific first, so a block written for
`[2]T` still beats one written for `[N]T` — which is `02 § override`'s "written-out beats a
parameter" one level up from where it used to apply. The library's one block is
`impl[const N: usize, T: Display] Display for [N]T`, and it delegates to the block for slices rather
than repeating it.

Which key a block is filed under is decided by the subject **as written**, not by the type it
resolves to. A value parameter stands at zero for the walk that checks a generic body, so `[N]T` and
`[0]T` resolve to the same array; only the syntax says which was meant.

**It does not retire the tuple arity rule** — §10 does. A tuple's shape is how many parts it has
*and what each one is*, which is not one value of one type, so nothing here reaches it: a length is
one `usize` and an arity is a list of types. The two are different features and this was the first
of them.

---

## 10. Type packs, and the loop that walks one

A parameter may stand for **several types at once**, which is what lets one declaration cover every
tuple:

```
impl[..A: Display] Display for (..A)
    display(self, out: *Writer, fmt: FormatSpec)
        var s = "("

        for const i in 0..<A.len
            if i > 0usize then s = s + ", "
            s = s + str(self.i)

        display_pad((s + ")").bytes, out, fmt)
```

`..A` is a **pack**: one name standing for a list of types, of a length the instantiation decides.
`(..A)` is the tuple of them, and it matches a tuple of any arity. `for const` is the loop that
walks the parts — unrolled at monomorphization, so `i` is a compile-time integer and `self.i` is an
ordinary field selection at whatever type that part has.

The two halves are separable in principle and are taken together because neither is useful alone.
A pack with no loop can match a tuple and not say anything about its parts; a loop with no pack has
nothing to walk. §9's value parameters are the near neighbour and the contrast is the whole
distinction: **a value parameter is one value of one type, and a pack is a list of types.** An
array's length is the first, a tuple's arity is the second, and that is why one feature did not
reach the other.

C++11 has parameter packs, Swift took them in 5.9, D pairs its tuple templates with `static
foreach`, and Zig writes exactly this loop as `inline for` over `@typeInfo`. **Rust is the
instructive one for having neither**: its standard library implements each trait for tuples up to
arity twelve by macro, twelve near-copies per trait, and stops there — which is the position sysl
was in with two hand-written arities, differing only in where the ceiling sits.

### A bound on a pack distributes over its members

`[..A: Display]` says every type in `A` implements `Display`, and that is the whole of the bound
syntax: no new spelling, no quantifier, no `where` clause. It is the ordinary `[T: Display]` read
over a list instead of over one name.

This is what makes the membership answerable *before* instantiation, which is the property a bounded
generic exists to have. A tuple satisfies `Display` when every part does — so `print(p)` on a
`(int, string)` resolves, `print(p)` on a tuple holding something unprintable is refused where it is
written, and neither answer waits for a body to be compiled. Getting that wrong is what a language
with templates and no bounds has instead of diagnostics.

**A pack may carry no default and no value bound.** A default would have to be a list of types with
no way to write one, and §9's `const` marks a different kind of parameter entirely; a parameter is
one of the three kinds and says which.

### `for const` — the loop the compiler unrolls

```
for const i in 0..<A.len
```

The range's ends must be compile-time constants, which `A.len` is: a pack knows its length once the
instantiation binds it, exactly as §9's `N` knows its value. A range whose end is not constant is
refused by name — the loop cannot be unrolled against a count nobody has yet, and the ordinary `for`
is what that program wants.

Each iteration is a **separate copy of the body**, type-checked on its own, which is the whole point:
`self.0` and `self.1` have different types and the same source line covers both. `i` is a compile-time
`usize` inside each copy and folds into its uses the way a value parameter does (§9), so `if i >
0usize` is decided at compile time and costs nothing at run time.

`return` out of the loop works and is what `Eq` and `Ord` are written with — an unrolled loop is
straight-line code in the enclosing function, so a `return` in it is an ordinary one. **`break` and
`continue` are refused**, with a sentence saying why: there is no loop at run time for either to act
on, and the shape that wants them is a `for` over a range of values rather than over a list of types.

That refusal is not tidiness. The copies are straight-line code *inside whatever the `for const` was
written in*, so an unlabelled `break` would leave the enclosing loop — silently, and one copy at a
time. The enclosing loops are therefore **hidden** while the copies are analyzed, which turns the
wrong answer into no answer; a real loop written inside the body pushes onto the emptied stack and
breaks out of itself exactly as it always did.

### What `self.i` means

A tuple's parts are fields named for their positions (`00 §13`), so `self.i` at a compile-time `i` is
field selection and not a new form — the same selection `self.0` already is, with the position
arriving as a constant rather than as a literal. It is refused where the index is not compile-time
constant, and refused where the receiver is not a tuple, because a struct's fields have names that a
number does not address.

The bound is what makes the part usable. Inside `impl[..A: Display]`, every `self.i` has a type known
to implement `Display`, so `self.i.display(…)` resolves through the bound exactly as `x.display(…)`
does under `[T: Display]`.

### The body is checked at a representative arity of two

A generic body is walked once, abstractly, before any instantiation exists — and an unrolled loop has
no fixed length to be walked at. **The pack stands at two types for that walk**, which is §9's "a
value parameter stands at zero" one kind up.

Two is not arbitrary and the choice is what makes the walk worth doing. The body of an unrolled loop
is one piece of source repeated, and every copy sees a part whose type carries the pack's bound and
nothing else — so a copy that checks at any position checks at every position. What two buys over one
is the *between*: at arity two both `i > 0usize` branches occur, a separator is emitted once, and the
bound is demanded of a part that is not the first. Arity two is also the smallest tuple there is
(`00 §13`), so the walk is checking a shape that really exists.

**What it reports is what the bounds do not license, and only that.** That is the pass's existing
rule and not a limitation of this one: every other complaint is dropped there, because a mistake in
the concrete part of a generic body is found at each instantiation and reporting it here as well
would report it against a body no call site may ever ask for. So `for const i in 0..<A.len` with an
unbounded `A` is told at the declaration that it wants `A: Display`, and a loop that runs past the
arity is told at the call, by the instantiation that has a real arity to be past.

The walk is a check and its tree is discarded; every instantiation is analyzed at its own arity, so
nothing about a real tuple is inferred from the representative one.

### Where a pack may be written

- **An `impl` subject** — `impl[..A: Eq] Eq for (..A)`, which is what the feature is for.
- **A function's parameter or result** — `widest[..A: Display](t: (..A)) -> usize`, since a free
  function over any tuple is the same need one step out of the catalog.

And nowhere else, day one. In particular there is **no pack expansion in an expression**: no
`f(..a)` spreading a tuple into an argument list, and no `(..A, int)` appending to a pack in a type.
Both are real features in the languages that have packs and neither is needed by anything here — the
unrolled loop is what replaces expansion, and it replaces it in the one direction the catalog wants.

**This is not variadic *functions*, and does not touch `va_arg`.** A variadic body reads a C-ABI
argument tail (`12`), which is a runtime walk over untyped storage; a pack is a compile-time list of
types. Making the first out of the second is a plausible future and is not this.

### Inference, monomorphization, and what this retires

**A pack is inferred by unifying `(..A)` against the argument's type**, by §4's existing rule: a
`(int, string, bool)` matched against `(..A)` binds `A` to those three, exactly as `[3]int` against
`[N]int` binds `N` to 3. There is nothing to write at a call.

**Monomorphization keys on the pack's members** (§7), so a `(int, string)` and a `(int, bool)` are
two instantiations of one block and two functions in the object file — the same trade every generic
makes, and the reason the loop costs nothing at run time.

**A written-out block still beats a pack, and a fixed arity beats both.** A tuple now has three keys
consulted most specific first: its own type, its arity's shape, and the pack's. `impl Display for
(int, string)` beats `impl[A: Display, B: Display] Display for (A, B)` beats `impl[..A: Display]
Display for (..A)`, which is `02 § override`'s "written-out beats a parameter" applied twice down one
ladder. §9 introduced the second rung for arrays and this adds the third for tuples.

**It retires the arity ceiling, which is what it was for.** The library implemented `Eq`, `Ord`,
`Hash` and `Display` at arity two and three — eight blocks, four of them near-copies of the other
four — and a tuple of four parts implemented none of them. Those eight become four, no arity is
special, and the diagnostic that existed to explain where the ceiling sat (*"the library provides
'sysl.Display' for tuples of up to 3 parts"*) goes with it.

Two of the four also come out **better** rather than merely shorter. `Ord`'s lexicographic ladder no
longer special-cases its last position — it ran the two-test ladder at every position but the last,
which ended with a bare `<` because there was no next position to fall through to, and the loop ends
with `false` instead: all positions tied means not less. `Eq` gains an early exit it wrote as a `&&`
chain. **`Hash` changes the values it produces**, deliberately: the hand-written rows seeded the fold
from `self.0.hash()`, and a loop needs a constant to start from, so the FNV offset basis is now the
seed. Nothing persists a hash, so this is a note rather than a migration.

**It does not reach structs.** `for const` over a struct's fields would give the derived `Display`
that `14 §8` records as wanted, and the loop form is the half of that which is now built — but a
struct's fields have names rather than positions, so `self.i` does not address them and what is
missing is a way to name a field list. That is the deriving question, and `14 §8` still holds it.

---

## Open (not yet decided)

- **a. Explicit call-site type arguments.** Inference (§4) plus result annotation covers every
  case the implementation exercises, and the naive `id[int](7)` spelling collides with indexing
  (§2). If explicit arguments prove necessary, they need a disambiguating syntax (a Rust-style
  turbofish marker, or a rule that a type-argument list is only read in a call head). Deferred
  until a real case cannot be served by inference.

  The reach for it is common enough to be worth a sentence of its own, because a **nullary**
  generic has no argument to be inferred from: `buf()` and `map()` are solved by what receives the
  result and nothing else, so `buf[u8]()` is the first thing a reader tries. That form is refused
  by name — `'buf' cannot be given type arguments at a call; write the type on what receives the
  result` — for a generic free function and a generic method alike, rather than by the general
  complaint about a callee that is not a name. The message is the whole of the mitigation: the
  syntax stays deferred, and the case that would otherwise look like a compiler limitation now
  names the annotation that stands in for it. The **special forms** are refused the same way and
  for the same reason, `va_arg[int](ap)` above all.

  **`va_arg` is the strongest case against the deferral, and worth recording as such.** Everywhere
  else the annotation is a word — the type on the binding that was going to be written anyway. A
  variadic body reads its tail into whatever the surrounding expression is, so `total += va_arg(ap)`
  and `take(va_arg(ap))` are answered by the place and the parameter; but a bare `print(va_arg(ap))`
  has nothing to read from and costs a whole statement to write. That is one position rather than a
  class of them, which is why the deferral stands — but it is the first place the missing syntax
  costs more than a word, and a second one would be the case this item is waiting for.
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
- ~~**d. Value generics**~~ — **built: see §9**, which settles the spelling (`[const N: usize]`), the
  admissible value types and why they are staged, what may be written with the parameter, and how
  inference and monomorphization reach it. A function, an `impl`, a struct and an enum may each
  declare one, and the library's `impl[const N: usize, T: Display] Display for [N]T` is what it was
  for. Type-level arithmetic over a value parameter stays excluded, and §9 says why.

  **The name is the one part worth keeping here, because it was nearly wrong.** Rust calls this
  *const generics*, and that is what this entry said until the feature was taken up. The word is
  fine as sysl's **keyword**, since `const` already declares a compile-time constant (`13 §`) and
  `[const N: usize]` is that declaration with the initializer left to the caller. It is wrong as the
  feature's **name**, because naming it after one admissible spelling hides that the parameter may be
  a `bool`, a `char` or an enum value too. C++ calls it a non-type template parameter, which defines
  the thing by what it is not; Ada 83 — the oldest of these by a decade — calls it a **generic formal
  object**, and *value* is that word said shorter.
- **e. Higher-kinded parameters.** Parameterizing over a *type constructor* (`F[_]`) is
  **excluded**, not merely deferred: it pushes inference toward undecidable, and no target use
  (an OS, drivers, embedded) needs it. Abstraction over containers is served by traits and
  bounds, not by HKT.
- ~~**f. A conversion into a type parameter**~~ — **built.** `T(b)` is a conversion, written where
  the parameter's name stands and resolved at each instantiation, so the two directions of one
  conversion are one rule: `guide/sha2`'s `top_byte` and `shift_in` sit beside each other, both
  generic, and `Word` is three members shorter for it.

  The objection recorded here — that a conversion into a parameter cannot mean anything for a `T`
  that turns out to be a struct — is true and turns out not to be an objection, because it was
  already the situation in the direction that worked. `u8(x)` where `x: T` says nothing at the
  definition either; it is checked when the instantiation says what `T` is, and a `T` that is a
  struct is refused there, naming the struct. `T(b)` is checked at the same moment and says the same
  thing. Making the inbound direction stricter than the outbound one would have been the odd choice,
  and a bound that promises the conversion is still writable on top — `02 § Reaching a trait's
  members without a value` is what a library reaches for when it wants the promise checked at the
  definition rather than at each use.

  An instantiation at a **constrained subtype** or a **simple enum** takes that type's own checked
  cast, since the scalar conversion has no meaning for either and the form written under the type's
  name does. So `T(x)` at an `Age` is the `Age(x)` a reader would have written and `T(n)` at a
  `Colour` is `Colour(n)`, trap included in both.

  **Construction is deliberately not among the forms it reaches.** A struct's positional constructor
  takes a field list rather than a value, and a generic body filling in an unknown struct's fields
  by position is not something to arrive at by accident — so `T(x)` at a struct is refused, naming
  the struct, exactly as `u8(x)` at one is. What a container that wants to build a `T` reaches for
  is a bound that says so.

  The **parameter wins** over a declaration of the same name, which closes an inconsistency rather
  than opening one: `var y: T` inside a `[T]` body has always meant the parameter, and until this
  was written `T(x)` one line later meant a `struct T` declared elsewhere. A name means one thing in
  both positions.
