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

The reuse is unambiguous because a type and an expression never occupy the same grammatical slot.
What it looks like it costs is **explicit type arguments at a call head** — `id[int](7)` reads as
"index `id` by `int`, then call" — but only to the parser. The compiler resolves the head first, and
a function is not a thing that can be indexed, so there is no second reading to protect:

```sysl
id[T](x: T) -> T = x

print(id[int](7), id[string]("s"))
```

```output
7 s
```

**The nearest binding wins**, which is the rule every call form follows. A local standing over a
function's name makes the brackets an index again, and its author is never told about a feature they
did not reach for.

## Writing the type arguments

Five heads take the list. A **value** argument is written exactly as a type one is, since the two
share a list and a position:

| written | what it names |
|---|---|
| `id[int](7)` | a function, qualified or not |
| `chunk[8]()` | a value parameter — `[const N: usize]` |
| `Pair[int, real](1, 2.5)` | a constructor, qualified or not |
| `x.pick[int](3)` | a method — its **own** parameters, not the receiver's |
| `Maybe[int].Just(1)` | a variant, which is a construction of its type — qualified or not |
| `va_arg[int](ap)` | a special form: `va_arg` and `ptr_cast` |

**A head reached through its module takes the list exactly where the bare name would**, so a type
from another module is instantiated without importing its name:

```sysl
import sysl.math.complex

val z = complex.Complex[f32](1.5, 2.0)

print(z.re, z.im)
```

```output
1.5 2
```

**Inference is still what supplies them nearly everywhere**, and a list inference would have found is
noise. What earns the syntax is a signature neither direction of inference reaches — a kernel whose
width is a value parameter it names in no argument and answers `unit` with:

```sysl
add[const W: usize](a: []const f32, b: []const f32, out: []f32)
    var i: usize = 0

    while i + W <= a.len
        val l: <W>f32 = a.load(i)
        val r: <W>f32 = b.load(i)

        out.store(i, l + r)
        i += W
end add

val xs: [8]f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
val ys: [8]f32 = [10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0]
var out: [8]f32

add[4](xs[..], ys[..], out[..])

print(out[0], out[7])
```

```output
11 88
```

Written without the brackets there is nothing anywhere to say what `W` is, and the message says so
rather than naming an annotation that cannot exist:

```sysl
add[const W: usize](a: []const f32, out: []f32)
    val v: <W>f32 = a.load(0)

    out.store(0, v)

var xs: [8]f32
var out: [8]f32

add(xs[..], out[..])
```

```error
'W' is in neither the parameters of 'add' nor its result
```

**Three things stay inferred.** A **type pack** — `..A` — stands for a list of types rather than one
and has no expression spelling, so its instantiation is always solved. An **associated function
selected from an applied type**, `Box[int].of(1)`, reads its type's parameters and its own from a
single solve; the annotation on the binding reaches it, and unlike the kernel above there is always
one, since an associated function has a result. And a **bare arrow's own parameter**, which is the
case below.

### A bare arrow is not in the list

`f: T -> U` is sugar for a bounded type parameter — the callable's own type, which the compiler adds
and no program writes. So a declaration carries more parameters than were written on it, and **the
list is the ones that were**:

```sysl
chain[A, B](x: int, f: int -> A, g: A -> B) -> B = g(f(x))

print(chain[int, int](3, n -> n * 2, n -> n + 1))
```

```output
7
```

Two arrows, two added parameters, and `[int, int]` is still `A` and `B`. There is nothing to write for
the other two: their argument is the closure's own anonymous type, and the name the compiler gave
them holds a character no identifier may contain.

**Where this is felt is a parameter only a bound mentions**, which inference does not reach and the
list is the only way in — so a declaration taking a callback needs both halves to work at once:

```sysl
trait Has[T]
    get(self) -> T

struct Box
    v: int

impl Has[int] for Box
    get(self) -> int = self.v

pick[B: Has[T], T, N](b: B, f: T -> N) -> N = f(b.get())

print(pick[Box, int, int](Box(4), n -> n + 1))
```

```output
5
```

**An address is the one position with no answer.** `&f[…]` has no arguments to read the callable's
type off, and no way to write it, so a function taking a bare arrow has no address form and is
refused by name rather than by a count:

```sysl
chain[A, B](x: int, f: int -> A, g: A -> B) -> B = g(f(x))

val p = &chain[int, int]

print(1)
```

```error
'chain' takes a bare arrow, so it is generic in the callable's own type — and an address has no arguments to read that from, nor any way to write it. Declare the parameter as a counted callable, '&Fn(…) -> …', to give it a type that can be named
```

The [boxed spelling](/reference/types/) is what an address needs: `&Fn(T) -> U` is a type with a name,
so a function declared with one is an ordinary generic and its address is written as any other.


There is also a **hole shared with the address form**: the brackets read the *expression* grammar, so
a type the two grammars do not spell alike has no form there. `[]int`, `weak T`, `volatile T`,
`<4>f32` and a callable are refused by the parser. The annotation reaches every one of them.

**The address form came first**: `&f[T]` names one instantiation of a generic function, and the case
that earned it is a C callback whose signature mentions the type parameter nowhere. See
[the FFI reference](/reference/ffi/).

## Construction

Applying a generic type names a concrete instance: `Box[int]` is the type of a box of `int`,
`Pair[int, string]` a pair. **Constructing one is the ordinary construction**, with the type arguments
inferred from the arguments — `Box(41)`, `Pair(1, "one")` — or written on the name, which means what
the annotation means: `Pair[int, real](1, 2.5)` fixes the instantiation and checks the fields against
it. The memory mode is the usual per-declaration choice: `Box(41)` is a value unless a `&Box[int]` is
expected.

## Inference is bidirectional

Type arguments are inferred, and from two directions.

**From the arguments.** `id(7)` infers `T = int`, and inference reaches *through* a generic
construction, so every nested parameter is solved at once:

```sysl
struct Box[T]
    v: T

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

**A construction built out of nothing but literals is consulted last for the same reason.** `Some(3)`
is a call rather than a literal, but the only thing that decided its `Option[int]` was an unsuffixed
`3` — so the conclusion is worth exactly what the `3` was worth, and an argument carrying a type of
its own outranks it whichever end of the call it is written at:

```sysl
same[T: Eq](a: T, b: T) -> bool = a == b

var s: Option[usize] = Some(3)

print(same(s, Some(3)), same(Some(3), s), s == Some(3), Some(3) == s)
```

```output
true true true true
```

An array literal and a generic struct reach it on the same rule, and it nests — `Some(Some(3))` is
built out of literals however far down you look.

**Two things stop it, and each is the reader having said what they meant.** A **suffix** anywhere
inside fixes the width, so `Some(3u8)` has a type of its own and disagreeing with it is a diagnostic.
And an ordinary **call** is not a construction: `f(3)` is spelled exactly as `Some(3)` is, and what
comes back has nothing to do with the literal handed over, so it keeps the type it answered with.

```sysl
f(n: int) -> Option[int] = Some(n)

var s: Option[usize] = Some(3)

print(f(3) == s)
```

```error
'==' needs matching types, got sysl.Option[int] and sysl.Option[usize]
```

**A parameter is solved to the type that was *written*.** Where that is a
[transparent subtype](/reference/declarations/#type-declarations), the parameter carries the subtype
rather than the base it is stored as — and it does so however the call said which type it is at:

```sysl
type Age = int within 0..150

widest[T]() -> T = T::Max

val written = widest[Age]()
val expected: Age = widest()

print(int(written), int(expected))
```

```output
150 150
```

The routes have to agree, because from the reader's side they are three ways of saying one thing.
Writing `Age` at the call, handing an `Age` as an argument, and annotating the binding `Age` are each
a way of naming the type this call is at; an answer that depended on which one was available would be
an answer about the *call* rather than about the type.

That a transparent subtype **is** its base — an `Age` stands where an `int` is asked for, and the
reverse — is unchanged, and holds for every value that flows. A type parameter is not a value: it is
the name a body is written about, and the body may ask it something its base would answer
differently. `T::Max` is the only such question there is.

**`null` is not consulted at all — it waits**, and what separates it from a literal is that it has no
default to be consulted *for*. The argument that cannot contribute is set aside, the rest solve the
parameter, and it is then read against what they said:

```sysl
two[T](a: *T, b: *T) -> bool = a == b

var x: int = 3

print(two(&x, null), two(null, &x))
```

```output
false false
```

Either order answers alike, since waiting is not queueing — and setting it aside cannot lose the
solution, because an argument with no type of its own has nothing to unify. Where nothing else
reached the parameter it is refused rather than defaulted: inference does not invent a pointee.

```sysl
one[T](a: *T) -> bool = true

print(one(null))
```

```error
'null' takes its type from its context, and there is none here
```

**A callable waits for the same reason and contributes on the way back.** A closure carries no type of
its own — its parameters come from the position it is written in — so it is set aside with the rest;
what it turns out to be then joins the solution, and the callables still waiting are read against it.
So one arrow's result can be what another arrow takes, and *which of them was declared first is not
part of the answer*:

```sysl
forward[T](f: int -> T, g: T -> int) -> int = g(f(0))

backward[T](g: T -> int, f: int -> T) -> int = g(f(0))

print(forward(n -> n + 1, n -> n * 2), backward(n -> n * 2, n -> n + 1))
```

```output
2 2
```

The two declarations differ in nothing but the order of their parameters, and each round reads the
arguments it *can* — so an argument is waiting on what it needs rather than on its turn.

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

var b = Box.of(41)

print(b.v)
```

```output
41
```

`Self` in the signature is the type applied to its own parameters, so writing `-> Self` and writing
`-> Box[T]` infer alike.

**A type parameter that stands for a generic type brings that type's arguments with it**, and this is
the one place there is nothing to infer: `T.origin()` where `T` is already `Box[int]` is asking
`Box[int]`, not asking `Box` and hoping the call says which. So a generic body reaches an associated
function of a generic type with no annotation, which is what makes
[`T.zero()`](/library/math/) usable in a body that has met neither a width nor a `Complex`:

```sysl
trait Origin
    origin() -> Self

impl Origin for int
    origin() -> int = 0

struct Box[T]
    v: T

impl[T: Origin] Origin for Box[T]
    origin() -> Box[T] = Box(T.origin())

seed[T: Origin](xs: []const T) -> T
    var s = T.origin()

    s

var bs: []Box[int] = [Box(7)]

print(seed(bs).v)
```

```output
0
```

`Self` inside the type's own body is the same case reached from inside, and needs no annotation
either. What still has to be told is a **bare** type name — `Box.origin()` written out applies `Box`
to nothing, so there the expected type is the whole of what settles it.

**A member's own type parameters are inferred the same way.** The receiver says what the *type's*
arguments are and nothing about the member's, which leaves those exactly where the rule already
reaches:

```sysl
struct Pair[A, B]
    first: A
    second: B

struct Box[T]
    v: T

    with[U](self, x: U) -> Pair[T, U] = Pair(self.v, x)

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

**A bound also brings the trait's
[associated types](/reference/traits/#a-trait-may-declare-an-associated-type) into reach**, written
`T::Name`. They are the case the table above cannot serve: an argument is written by whoever names the
trait, so a type the *implementation* chooses has nothing to be written as. `[S: Seq]` and then
`S::Item` is how a body names one without the caller having to spell it, and `S::Item` is abstract
there — licensed to do exactly what the trait declared for it, exactly as `S` itself is.

**It stands wherever a type stands, and the position worth showing is a callable's.** A bare arrow is
sugar for a bound of its own, so `f: S::Item -> N` is a parameter whose type nobody in the program
writes out: the implementation chose it, the bound reached it, and the closure at the call site is
monomorphized against it without being boxed.

```sysl
trait Walk
    type Item

    step(*self) -> Option[Self::Item]

struct Down
    n: int

impl Walk for Down
    type Item = int

    step(*self) -> Option[int]
        if self.n <= 0 then return None

        self.n -= 1
        Some(self.n)

first_of[S: Walk, N](s: *S, f: S::Item -> N) -> Option[N] =
    s.step() match
        Some(t) -> Some(f(t))
        None -> None

var d = Down(3)

print(first_of(&d, n -> n * 10).expect("a step"))
```

```output
20
```

`n` is an `int` because `Down` said `type Item = int`, and `first_of` never mentions `int` at all.

### A trait object satisfies a bound on the trait it dispatches through

A bound asks whether the members it names can be called on the value. A `*Trait` or a `&Trait` has
forgotten which type it holds, but it carries a **table** of exactly those members — so it answers
yes, and one generic function takes both a concrete type and the object erased from it:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
    h: int

impl Shape for Rect
    area(self) -> int = self.w * self.h

total[T: Shape](x: T) -> int = x.area()

var o: &Shape = Rect(3, 4)

print(total(Rect(3, 4)))
print(total(o))
```

```output
12
12
```

**Nothing about [monomorphization](#monomorphization) is bent to allow it.** An object type is a
concrete type — a pair of words — so the body is instantiated at `&Shape` exactly as it is at `Rect`,
once each. What differs between the two instantiations is what `x.area()` compiles to: a direct call
in one, an indirect call through the table in the other. That is the same difference the two
instantiations would have had anyway, and it is decided the same way — by looking at the type the
parameter was bound to.

**It holds because of object safety rather than because of anything promised here.** A trait with a
member that cannot be dispatched — one mentioning `Self` away from its receiver, say — has no object
*at all* in sysl, rather than an object missing that member. So a `&Shape` existing is already the
proof that every member of `Shape` is reachable through it.

**The one exception is a member that declares type parameters of its own**, which is left out of the
*table* rather than out of the object ([traits](/reference/traits/#object-safety)). A bound promises
every member and an object reaches only what its table holds, so an object does not stand at a bound
on such a trait — and the refusal says which member and why, because without that it reads as a
`&Applies` failing to implement `Applies`:

```sysl
trait Applies
    tag(self) -> int
    apply[U](self, f: &Fn(int) -> U) -> U

struct N
    v: int

impl Applies for N
    tag(self) -> int = self.v
    apply[U](self, f: &Fn(int) -> U) -> U = f(self.v)

total[T: Applies](x: T) -> int = x.tag()

val o: &Applies = N(3)

print(total(o))
```

```error
a bound promises every member and an erased value reaches only what its table holds
```

The value itself is unaffected — `N(3).apply(…)` is an ordinary call, and a bound taken on a type
parameter reaches it at every instantiation. What has no answer is the erased form, and only for that
member.

It follows for **required** traits with no rule of its own. `trait Shape: Display` puts `Display`'s
slots in the object's table, and a bound on a trait is already satisfied wherever a bound on one that
requires it is — so a `show[T: Display](x: T)` takes the same `o`.

What an object still does not satisfy is a bound on any *other* trait: the table is what answers, and
a trait with no slots in it has nothing to answer with.

```sysl
trait Shape
    area(self) -> int

trait Weighed
    weight(self) -> int

struct Rect
    w: int
    h: int

impl Shape for Rect
    area(self) -> int = self.w * self.h

heft[T: Weighed](x: T) -> int = x.weight()

var o: &Shape = Rect(3, 4)

print(heft(o))
```

```error
'heft' requires its type parameter 'T' to implement 'Weighed', but &Shape does not
```

**Satisfying a bound is not the same as being erasable again.** A bound asks what may be *called*
through a value; forming an object asks what may be *assembled* from its type, and a table is laid
out from a type's implementations. An object has none, so a `&Shape` cannot be turned into a
`&Display` even though `Display`'s slots are sitting inside its table — a run of slots in one table
is not a table of its own ([traits]({{< ref "traits" >}})).

### A type's own parameters carry bounds too

The same bracketed list, in the same place, means the same thing on a struct or an enum — and that is
where the type says what it assumes:

```sysl
struct SortedPair[T: Ord]
    lo: T
    hi: T

    ordered(self) -> bool = self.lo < self.hi

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

struct P
    v: int

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

### A bound may be written out of line, with `where`

The bracket list is one place to put a bound and it is not always the readable one. A declaration
with several parameters and several bounds pushes its parameter list, its result and its body off to
the right, and what the reader came for — what the function *takes* — is the part that moves. `where`
puts the bounds after the signature instead:

```sysl
show[T](x: T) -> string where T: Display = str(x)

print(show(42))
```

```output
42
```

**It is the same bound in a different place, and nothing downstream can tell the two apart.** A
clause is folded into the list the brackets fill, so `show[T: Display](x: T)` and the form above are
one declaration written two ways — same inference, same refusals, same monomorphization. Which to
write is a question about the line, not about the meaning.

**The two may be mixed**, which is what makes the choice per-bound rather than per-declaration: the
short obvious constraint stays in the brackets, and the long one that would crowd the signature goes
below it.

```sysl
same[T: Eq](a: T, b: T) -> string where T: Display =
    if a == b then str(a) else "different"

print(same(3, 3))
print(same(3, 4))
```

```output
3
different
```

The clause is a comma-separated list, and each entry joins its traits with `+` exactly as a bracketed
bound does. It goes at the end of the header line — after the result type, before the `=` or the
indented block — and it is written the same way on a function, a member, a struct, an enum, a trait
and an `impl`:

```sysl
struct SortedPair[T] where T: Ord
    lo: T
    hi: T

    ordered(self) -> bool = self.lo < self.hi

var s = SortedPair(1, 2)

print(s.ordered())
```

```output
true
```

**A clause may only bound a parameter the declaration actually declares**, and saying otherwise is
refused where it is written rather than at some later use:

```sysl
show[T](x: T) -> string where U: Display = str(x)

print(show(1))
```

```error
this declaration has no type parameter 'U', so a 'where' clause has nothing to bound — it takes 'T'
```

A **value** parameter is the one case that reads as though it ought to work and cannot. `[const N:
usize]` already states a type, and that type is what the argument must *be* rather than a promise
about what it implements — a value implements no trait, so there is nothing for a clause to add:

```sysl
sum[const N: usize](xs: [N]int) -> int where N: Display = 0

print(sum([1, 2]))
```

```error
'N' stands for a value rather than a type, and a value implements no trait — its 'const' declaration is where the type its argument must have is written
```

**`where` is not a reserved word**, and the two jobs it already had are untouched. It introduces a
[constrained type's predicate](/reference/errors/#constrained-types) in a `type` declaration,
where it follows a type rather than a signature, and it remains an ordinary name everywhere else —
so a program may still call something `where`:

```sysl
type Even = new int where value % 2 == 0

where(n: int) -> int = n + 1

var e = Even(4)

print(int(e), where(1))
```

```output
4 2
```

## A parameter may carry a default

A parameter of a **trait**, a **struct**, or an **enum** may name the type to use where a use leaves it
out:

```sysl
struct Pair[A, B = A]
    x: A
    y: B

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

A **value parameter** may carry one too, and it is the argument a use would have written in its
place — so the same four rules govern it. It fills the same gap, counts toward the same arity and
sits in the same suffix, a type default and a value default may share one list, and a value default
may be a constant expression over the value parameters written before it:

```sysl
struct Ring[T, const N: usize = 4]
    items: [N]T

struct Pad[const M: usize, const N: usize = M * 2]
    a: [M]int
    b: [N]int

val r: Ring[int] = Ring([1, 2, 3, 4])
val p: Pad[2] = Pad([1, 2], [3, 4, 5, 6])

print(r.items.len, p.b.len)
```

```output
4 4
```

`Ring[int]` is `Ring[int, 4]` — one instantiation, filled before anything is keyed. The default has
to fit the parameter's declared type, exactly as a written argument does: `[const N: u8 = 300]` is
refused with *"the default for 'N' does not fit byte: 300"*.

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

A value parameter of a function is solved from an array's length exactly as a type parameter is
from an argument's type, so its default is refused in the same words:

```sysl
total[const N: usize = 3](xs: [N]int) -> usize = N

print(total([1, 2]))
```

```error
'N' is a value parameter of the function 'total', whose type parameters are solved from what it is given rather than written where it is used — so '= 3' has nothing to stand in for
```

## Converting through a parameter

A conversion is the one capability with no bound to promise it, because a conversion is between the
concrete scalar kinds rather than something a trait declares. **Both directions are written, and both
are checked at each instantiation** rather than at the definition:

```sysl
low[T](x: T) -> u8 = u8(x)

make[T](b: u8) -> T = T(b)

var n: int = make(7)

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

make[T](b: u8) -> T = T(b)

var p: P = make(7)

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

### A nested function may be generic

A function declared inside a body takes type parameters, bounds and the bare-arrow sugar exactly as a
top-level one does, and each call instantiates it the same way. What it adds is the frame: every
instantiation is one more function over the environment its block shares, so each reads and writes
the body's variables as any nested function does.

```sysl
tally() -> int
    var total = 0

    noted[T: Display](x: T, n: int) -> string
        total += n
        s"$x"

    print(noted("a", 2), noted(true, 3), noted(1.5, 4))
    total

print(tally())
```

```output
a true 1.5
9
```

Two bodies may each declare a nested generic of one name: an instantiation is named for the frame it
belongs to as well as for its type arguments, so the two are never one function. A type argument the
call cannot settle is written out, `sized[u32]()`, and a bound is held at the call:

```sysl
struct Point
    x: int

describe() -> string
    shown[T: Display](x: T) -> string = s"<$x>"

    shown(Point(1))
```

```error
'shown' requires its type parameter 'T' to implement 'sysl.Display', but Point does not
```

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

impl Animal for Cat
    noise(self) -> string = "meow"

struct Box[T]
    v: T

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

## A parameter may stand for a value

A type parameter stands for a **type**. A parameter written `const` stands for a **value** — which is
what lets one declaration cover every array length:

```sysl
total[const N: usize](xs: [N]int) -> int
    var t = 0
    for i in 0..<N do t = t + xs[i]
    t

var a: [3]int = [1, 2, 3]
var b: [5]int = [10, 20, 30, 40, 50]

print(total(a))
print(total(b))
```

```output
6
150
```

`N` is inferred from the argument exactly as a type parameter is: matching `[3]int` against `[N]int`
binds `N` to 3, the way matching `Box[int]` against `Box[T]` binds `T`. Inside the body `N` is an
ordinary `usize` — it can be looped to, computed with, and passed on.

`total` at `N = 3` and at `N = 5` are two functions, the same way `id` at `int` and at `real` are
two: the value joins the type arguments the instantiation is keyed on, so the length is a constant
inside each copy.

### It is the same `const` as everywhere else

A constant is declared `const NAME: Type = expr`. A value parameter is that declaration with the
initializer left for the caller, so `[const N: usize]` is existing grammar in a new position rather
than a new idea.

The marker is not decoration. `[N: usize]` on its own is indistinguishable from a bounded type
parameter — `[T: Ord]` has the same shape — and only name resolution could tell them apart, by asking
whether the thing after the colon is a trait or a type. A trait name misspelled into a type name
would then silently change what kind of parameter it is.

### Which values

**Integers, `bool`, `char`, and a simple enum's variants.** A value parameter puts a value into a
type's *identity*, so the compiler has to decide when two of them are the same value and has to write
one into a mangled name — and each of these compares and mangles.

A **type** declares its value parameters the same way, and its arguments are written out rather than
inferred, because a type has no call to infer them from:

```sysl
struct Buf[const N: usize]
    data: [N]byte

struct Flag[const B: bool]
    v: int

var buf: Buf[4] = Buf([1, 2, 3, 4])
var on: Flag[true] = Flag(1)

print(buf.data.len, on.v)
```

```output
4 1
```

`Buf[2]` and `Buf[4]` are two types with two layouts, and neither stands where the other is wanted.

A **member** reads the parameter as an ordinary value of its declared type, and the two lengths are
two bodies rather than one compiled at whichever came first:

```sysl
struct Buf[const N: usize]
    used: usize

    room(self) -> usize = N - self.used

var a: Buf[8] = Buf(3)
var b: Buf[2] = Buf(1)

print(a.room())
print(b.room())
```

```output
5
1
```

A **simple enum** — one whose variants carry nothing — gives a parameter that takes one of its
variants. The parameter's declared type says which enum, so the variant is written bare, exactly as
it is where an expected type names the enum; `Level.High` names the same value:

```sysl
enum Level
    Low
    High

struct Gauge[const L: Level = Low]
    reading: int

    alarm(self) -> bool = L == High

var quiet: Gauge = Gauge(3)
var loud: Gauge[High] = Gauge(9)
var same: Gauge[Level.High] = loud

print(quiet.alarm(), loud.alarm(), same.reading)
```

```output
false true 9
```

`Gauge[Low]` and `Gauge[High]` are two types, and the default makes a bare `Gauge` the first.
A variant of any other enum is not a value of this one, bare or qualified:

```sysl
enum Level
    Low
    High

enum Tide
    Low
    Slack

struct Gauge[const L: Level]
    reading: int

var g: Gauge[Slack] = Gauge(1)
print(g.reading)
```

```error
'Slack' is not a variant of Level
```

An enum whose variants carry values cannot be a value parameter's type — its value is more than the
tag a symbol can spell — and neither can a float or a string:

```sysl
enum Shape
    Dot
    Circle(r: int)

struct Holder[const S: Shape]
    n: int

print(1)
```

```error
'S' is a value parameter, and Shape cannot be the type of one
```

Floats are excluded: `NaN != NaN` under the ordinary comparison, which would make a type unequal to
itself. Strings are excluded until two spellings of one text are one value.

### What may be written with `N`

`N` may stand as an array's length, and a body may compute with it freely. What neither may do is
carry the result of a computation into a **type**:

```sysl
f[const N: usize](xs: [N]int) -> [N + 1]int = xs

print(1)
```

```error
this length does arithmetic on 'N'
```

Deciding that `N + 1` and `1 + N` are one type — and that `2 * N` and `N + N` are — is type-level
arithmetic, which is a feature of its own.

Writing a **type** parameter where a length belongs is refused for the mirror reason, since a length
is a value:

```sysl
f[T](xs: [T]int) -> usize = 0

print(1)
```

```error
'T' is a type parameter, and an array's length is a value rather than a type
```

### What it is for: a fixed array renders

`impl[const N: usize, T: Display] Display for [N]T` is one block covering every array there is, which
is why `print` takes one:

```sysl
var a: [3]int = [1, 2, 3]
var b: [2]string = ["x", "y"]

print(a)
print(b)
```

```output
[1, 2, 3]
[x, y]
```

Before this, a length was part of a type's *shape*: `[2]T` and `[3]T` were two shapes with no way to
be generic over the difference, so no library could implement a trait for arrays in general.

An array still has two shapes an `impl` may match — the length written out, and the length as a
parameter — and the written-out one is more specific, so it is found first. A block for `[2]T` still
wins over a block for `[N]T` on arrays of two, which is the same "written-out beats a parameter"
ordering `override` uses.

### A member declares its own

A method's parameter list is its own, exactly as a function's is — so it may take a value parameter
that the *type's* parameters know nothing about. The type's are fixed by the receiver; the member's
are solved at the call:

```sysl
struct Sum
    seen: usize

    take[const N: usize](*self, xs: [N]int) -> usize
        self.seen = self.seen + N
        N
end Sum

var s = Sum(0)

print(s.take([1, 2, 3]))
print(s.take([4, 5]))
print(s.seen)
```

```output
3
2
5
```

Both calls reach one written method and neither passes a length, because `N` is read off the argument
the same way it is for a free function.
### A trait declares one too

A trait's parameter list is a parameter list like a struct's, so it may take a value. The members
read it in their signatures and in their default bodies, and an `impl` fixes it — the way
`impl Mul[int]` fixes a type:

```sysl
trait Bytes[const N: usize]
    bytes(self) -> [N]u8
    size(self) -> usize = N

struct W
    x: u8

impl Bytes[4] for W
    bytes(self) -> [4]u8 = [self.x, 0, 0, 0]

val w = W(7)

print(w.size())
print(w.bytes()[0])
print(w.bytes().len)
```

```output
4
7
4
```

A bound names it at a value, `f[T: Bytes[4]]`, and so does a trait object, `&Bytes[4]`. Two values
are two implementations, exactly as two type arguments are, so one type may implement `Bytes[2]` and
`Bytes[4]` side by side. A generic block may abstract over the value too:
`impl[const M: usize] Bytes[M] for Bits[M]`.

A default follows the struct rules (`§ A parameter may carry a default`): a block or an object that leaves the
argument out takes it.

```sysl
trait Hash[const N: usize = 32]
    digest_len(self) -> usize = N

struct Sha
    x: int

impl Hash for Sha

val o: &Hash = Sha(0)

print(o.digest_len())
```

```output
32
```

A value is checked against the parameter's type wherever it is written, the default included:

```sysl
trait Small[const N: u8]
    n(self) -> u8 = N

struct S
    x: int

impl Small[300] for S
```

```error
this argument does not fit byte: 300
```

## A parameter may stand for a list of types

A type parameter stands for one type and a `const` parameter for one value. A parameter written `..`
stands for a **list of types** — a *pack* — which is what lets one declaration cover every tuple:

```sysl
joined[..A: Display](t: (..A)) -> string
    var s = ""

    for const i in 0..<A.len
        if i > 0 then s = s + "-"

        s = s + str(t.i)

    s

print(joined((1, 2)))
print(joined((1, "two", true, 4.5)))
```

```output
1-2
1-two-true-4.5
```

Three things are new there and each does one job.

**`..A` declares the pack**, and `(..A)` is the tuple of it — the only *spelling* a pack has, wherever
it is declared.
It matches a tuple of any arity, and the arity is inferred from the argument exactly as an array's
length is: a `(int, string, bool)` matched against `(..A)` binds `A` to those three.

**The bound distributes over the members.** `[..A: Display]` says every type in `A` implements
`Display`, and that is the whole of the bound syntax a pack needs — the ordinary `[T: Display]` read
over a list. It is what makes the membership answerable before any body is compiled, so a tuple
holding something unprintable is refused where it is written:

```sysl
struct Point
    x: int
    y: int

joined[..A: Display](t: (..A)) -> string = str(t.0)

print(joined((1, Point(2, 3))))
```

```error
Display
```

**`for const` is unrolled.** Its range must be known when the program is compiled — `A.len` is how
many types the pack stands for — and the body is repeated once per value, with `i` folded in as a
compile-time `usize`. Each copy is type-checked **on its own**, which is the point: the parts of a
tuple have different types, so `t.i` is a different selection in each copy and one written line
covers all of them.

`t.i` at a compile-time `i` is the selection `t.0` already is, with the position arriving as a
constant rather than as a literal. It reaches the parts of a tuple and nothing else — a struct's
fields have names, and a number does not address one.

### What a `for const` will not do

A range computed at run time cannot be unrolled, and the ordinary `for` is what walks one:

```sysl
f(n: usize)
    for const i in 0..<n
        print(i)

f(3)
```

```error
must be known at compile time
```

`break` and `continue` are refused as well. There is no loop at run time for either to act on: the
copies are straight-line code inside whatever the `for const` was written in, so a `break` would
leave *that* loop — one copy at a time, and silently. A loop written inside the body is an ordinary
loop and breaks out of itself as usual.

`return` does work, and it is what `Eq` and `Ord` on a tuple are written with — an unrolled copy is
straight-line code in the enclosing function, so a `return` in one is an ordinary return.

### What it is for: the catalog covers every tuple

`impl[..A: Display] Display for (..A)` is one block covering every tuple there is, and `Eq`, `Ord`
and `Hash` are each one more. Before this, a tuple's arity was part of its *shape*: a pair and a
triple were two shapes with no way to be generic over the difference, so the library wrote a row per
arity and stopped at three — and a tuple of four parts implemented nothing.

A tuple now has three shapes an `impl` may match, and they are found most specific first: the tuple
written out in full, then one arity, then every arity.

```sysl
trait Tag
    tag(self) -> string

impl[..A: Display] Tag for (..A)
    tag(self) -> string = "any"

impl[A: Display, B: Display] Tag for (A, B)
    tag(self) -> string = "pair"

override impl Tag for (int, int)
    tag(self) -> string = "two ints"

print((1, 2).tag(), (1, "x").tag(), (1, 2, 3).tag())
```

```output
two ints pair any
```

That is the same "written-out beats a parameter" ordering an array's two shapes have, one rung
longer — and the `override` is the separate rule it has always been. Coherence says where a block
may be written; `override` says which of two blocks that both have a home answers. A block for a
tuple **written out in full** is the specific one, so it is the one that says so.

### A member declares one too, and a trait's member as well

A pack stands in a method's own parameter list on the same terms a value parameter does, and for the
same reason — the list belongs to the member:

```sysl
struct Row
    n: usize

    take[..A: Display](*self, t: (..A))
        for const i in 0..<A.len
            self.n = self.n + str(t.i).len
end Row

var r = Row(0)

r.take((1, "abc", true))
print(r.n)
```

```output
8
```

A **trait's** member takes one too, and not for a reason about packs: a member may declare parameters
of its own whatever their kind, and a pack is one more way of writing that list. What it costs is the
trait **object** rather than the declaration — a member with parameters of its own is not a function
until a call names them, so no slot of a `&Trait`'s table can point at it.

The `impl` spells the same list — by position, so the letters need not agree — and the types are named
at the call, whether that call is on the type or through a bound:

```sysl
trait Take
    take[..A: Display](self, t: (..A)) -> usize

struct Row
    n: usize

impl Take for Row
    take[..A: Display](self, t: (..A)) -> usize
        var total = self.n

        for const i in 0..<A.len
            total = total + str(t.i).len

        total

through[S: Take](s: S) -> usize = s.take((1, "abc", true))

print(Row(0).take((1, "abc", true)))
print(through(Row(2)))
```

```output
8
10
```

**What such a member gives up is its table slot**, which is a rule about erasure rather than one about
packs: no slot can hold a function that does not exist until a call names its types. The trait still
has an object and it still dispatches every other member; what is refused is reaching *that* member
through one ([traits](/reference/traits/#object-safety)).

```sysl
trait Take
    tag(self) -> usize
    take[..A: Display](self, t: (..A)) -> usize

struct P
    n: usize

impl Take for P
    tag(self) -> usize = self.n
    take[..A: Display](self, t: (..A)) -> usize = self.n

val o: &Take = P(5)

print(o.tag())
print(o.take((1, "ab")))
```

```error
'take' of 'Take' declares type parameters of its own, so it is not a function until a call names them
```

A `struct`, an `enum` and a `trait` are refused a pack for a different reason — their parameters
**are** their shape, and there is nothing to spread a list over. A member has no shape of its own, so
that reason never reached one.

### This is not variadic functions

A pack is a compile-time list of *types*. C's ellipsis is a run-time walk over untyped storage
([ffi](/reference/ffi/)), and the two share nothing. There is also no pack **expansion**: a pack
cannot be spread into a call's arguments, and `(..A, int)` cannot append to one. The unrolled loop
stands in for expansion, in the one direction the catalog needs.

## What is deliberately not here

| absent | why, and what to write instead |
|---|---|
| explicit type arguments **at a call** | `id[int](7)` collides with indexing; annotate what receives the result. At an **address** they are written — `&f[T]` |
| `where` clauses | the inline `[T: A + B]` list is the settled baseline; an out-of-line form is a possible ergonomic addition |
| type-level arithmetic (`[N + 1]T`) | a value parameter may stand as a length but not be computed with in a type; deciding that `N + 1` and `1 + N` are one type is a feature of its own |
| pack expansion (`f(..a)`, `(..A, int)`) | a pack may be matched and walked, not spread into an argument list or appended to; `for const` is what stands in for it |
| higher-kinded parameters (`F[_]`) | **excluded**, not deferred — it pushes inference toward undecidable, and abstraction over containers is served by traits and bounds |

The first of those has one position where the annotation costs more than a word. A **nullary** generic
has no argument to be inferred from, so `buf()` and `map()` are solved by what receives the result and
nothing else — and `buf[u8]()` is the first thing a reader tries. That form is refused **by name**,
naming the annotation that stands in for it, rather than by a general complaint about a callee that is
not a name.

---

Next: [modules](/reference/modules/).
