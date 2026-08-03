---
title: Traits and generics
summary: One mechanism for polymorphism, two ways to spend it — a bound for static dispatch, a sigil for dynamic.
weight: 100
---

sysl has **one** polymorphism mechanism, and it is called `trait`. A type takes part in a trait only
through an explicit `impl Trait for Type`; there is no structural conformance, so a type never
satisfies a trait by coincidence of method names.

What varies is not the mechanism but how you spell the parameter:

- `[T: Trait]` — a **bound** on a generic. Monomorphized, direct calls, no indirection.
- `&Trait` / `*Trait` — a **trait object**. One copy of the code, dispatched through a table.

Same trait, two strategies, and no `dyn` keyword: the memory-mode sigil already says everything one
would.

## A trait, and an implementation

```sysl
trait Greet
    name(self) -> string
    greet(self) -> string = "hello, " + self.name()

struct Cat
    tag: string

impl Greet for Cat
    name(self) -> string = self.tag

var c = Cat("mimi")

print(c.greet())
```

```output
hello, mimi
```

A member written as a bare signature is one an implementation must supply. One written **with a
body** is a default that every `impl` inherits unless it writes its own.

Defaults are what let a trait *grow*: adding a member with a default does not break the
implementations that already exist, which is the difference between a trait a library can evolve and
one frozen at its first release.

A default's body may assume of its receiver exactly what its own trait declares, and nothing more.
That is not a restriction bolted on — it is what a default is, since the body has to serve every
implementing type and the trait is all they have in common. It is checked once, at the trait, even
when nothing implements the trait at all.

## Any type may carry an `impl`

Including the built-ins, which is not a convenience but a requirement — a `Show` that cannot cover
`int` is a `Show` no library can be written against:

```sysl
trait Show
    show(self) -> string

impl Show for int
    show(self) -> string = "int " + str(self)

impl Show for bool
    show(self) -> string = if self then "yes" else "no"

var n: int = 5

print(n.show(), true.show())
```

```output
int 5 yes
```

Where an `impl` may *live* is the one restriction: in the module that declares the trait, or in one
that declares a type named in the subject. That is Rust's orphan rule, and it costs nothing —
retrofitting still works, because `impl MyTrait for TheirType` lives with `MyTrait`. What it buys is
that resolving a bound inspects only the modules a use site already depends on, with no global search
and no dependency edge the source does not show.

## Generics, and what an unbounded parameter may do

A type parameter list in square brackets makes a function, struct or enum generic:

```sysl
struct Pair[A, B]
    first: A
    second: B

    swapped(self) -> Pair[B, A] = Pair(self.second, self.first)

var p = Pair(1, "one")
var q = p.swapped()

print(p.first, p.second, "/", q.first, q.second)
```

```output
1 one / one 1
```

`Pair` asks nothing of `A` and `B`, and it does not need to. With no bound, a parameter may be
**copied, assigned, passed, returned, and stored** — which is exactly what a container does.

That baseline is bigger here than in Rust, and the memory chapter is why: every sysl value is
copyable, since assignment copies and copying a value holding a `&T` retains it. So there is **no
`Copy` bound to write, ever**. "Hold and hand along any `T`" is free and unmarked.

Type arguments are inferred, and from two directions — from the arguments (`Pair(1, "one")` gives
`A = int, B = string`) and from the expected type, for the cases the arguments cannot settle. A
parameter that neither direction determines is an error asking for an annotation, never a silent
default.

## A bound is a trait

What an unbounded `T` may **not** do is anything that assumes structure: no operator, no method call,
no field access, no subscript. Each of those is a capability some types have and others do not, so
each needs a bound that guarantees it:

```sysl
trait Show
    show(self) -> string

impl Show for int
    show(self) -> string = "#" + str(self)

describe[T: Show](x: T) -> string = "<" + x.show() + ">"

var n: int = 7

print(describe(n))
```

```output
<#7>
```

And here is the whole payoff. Drop the bound and the error lands on **the definition that is wrong**,
not on some caller three files away that instantiated it with the wrong type:

```sysl
trait Show
    show(self) -> string

describe[T](x: T) -> string = x.show()
```

```error
'show' needs 'T: Show'
```

The diagnostic names the bound to write, which is its whole job. That is definition-checked generics — the Swift/Kotlin/Scala consensus, and the opposite of a C++
template, whose errors arrive at instantiation wearing the callee's insides.

Multiple bounds join with `+`: `[T: Ord + Hash]` asks for both.

**A field is the one exception, and it proves the rule.** Every other unlicensed use names the bound
that would allow it, because that is the diagnostic's whole job. A field names none — a trait promises
*behaviour* and a field is *layout*, so no bound could ever supply one. Reaching a value's data
through a generic means going through a member the bound declares, which is also what lets two types
satisfy one bound while storing the value differently.

## Operators are trait methods

Which is why a bound is all it takes to write arithmetic over a parameter:

```sysl
sum[T: Add](a: T, b: T) -> T = a + b
biggest[T: Ord](a: T, b: T) -> T = if a < b then b else a

print(sum(3, 4), sum(1.5, 2.25))
print(biggest(3, 9), biggest("apple", "pear"))
```

```output
7 3.75
9 pear
```

`+` is `Add`, `<` is `Ord`, `==` is `Eq`. Nothing about generics is special-cased for operators; an
operator on a `T` simply requires the bound that supplies it, the same as a method call does.

A type's own parameters carry bounds too, in the same brackets and meaning the same thing — and that
is where a type says what it assumes. `struct SortedList[T: Ord]` holds every application of the type
to the promise, and lets its members be checked at their definitions rather than at each use.

A bound is declared **once**, at the type, and is in force everywhere its parameters appear: a
member's signature and body, a field's type, a variant's payload. It is not restated per member.

## Monomorphization

Each distinct set of type arguments produces its own specialized function or aggregate. `sum` called
at `int` and at `real` emits two functions; `Pair[int, string]` and `Pair[int, int]` are two layouts.

That is what lets bounds be checked once at the definition and still lower to direct, inlinable code
with no dictionary passed at run time. The cost is code size, which is the standard trade and the
right default for a systems language — with the dynamic path below available whenever one copy is
what you actually want.

**Variance does not arise.** Variance is about when `G[A]` may stand in for `G[B]`, which needs a
subtyping relation to be interesting, and sysl has none among concrete types. `Box[Cat]` and
`Box[Animal]` are simply unrelated. That deletes a whole category of design difficulty, and it should
stay deleted: polymorphism over a set of types is a bound or a trait object, never a covariant
container.

## Trait objects

When the set of types is open or genuinely heterogeneous, monomorphizing is impossible or wasteful.
A trait object is a **fat pointer** — two words, the method table for the type it forgot, and the
value itself:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
    h: int

impl Shape for Rect
    area(self) -> int = self.w * self.h

struct Square
    side: int

impl Shape for Square
    area(self) -> int = self.side * self.side

total(shapes: []const &Shape) -> int
    var t = 0

    for s in shapes do t += s.area()

    t

var shapes: [2]&Shape = [Rect(3, 4), Square(5)]

print("total:", total(shapes[..]))
```

```output
total: 37
```

The sigil says who owns the second word, and nothing else changes between the two. `&Shape` is an
ARC-owned object — the data word is the reference-counted box the value sits in, and letting go of it
needs no more knowledge of the payload than letting go of any `&T` does. `*Shape` is a raw fat
pointer, unmanaged like every `*T`, and it is what a kernel passes around when there is no allocator.

Erasure is a **coercion**, applied wherever an object type is expected — an argument, a declared
variable, an array element, a struct field. Above, each `Rect(3, 4)` is constructed, boxed because a
`&Shape` was expected, and then erased, which is the ordinary "write the construction and it is
allocated" rule with one more step. Because the coercion applies per branch, an `if` or a `match`
whose arms are different concrete types meets at one trait object — which is the point of having
them.

What an object offers is the trait's methods and nothing else. No dereference, no fields, no
comparison, and no way back to the concrete type.

**But the two halves of this page's opening meet at a bound.** An object carries a table of the
trait's members, which is exactly what a bound asks for — so a `[T: Shape]` function takes a `&Shape`
as readily as it takes a `Rect`, with an indirect call where the other gets a direct one. The choice
between static and dynamic is about what the *caller* holds, not about which functions it can reach,
and a library written against bounds is not closed to a program that erased.

### Object safety

Erasure forgets the type, so a member may promise nothing that depends on knowing it. A trait can be
made into an object when every member has a receiver and mentions `Self` nowhere but there.

That second rule excludes **every trait in the operator catalogue** — `add(self, rhs: Self) -> Self`
first among them — and that is the right answer rather than a limitation. An operator over two values
of one type is a question about types known while compiling, so those traits are for bounds.

## A trait may require another

`trait Reader: Fallible` says that implementing `Reader` obliges implementing `Fallible` too. The
requirement is checked **at the `impl`**, not at the bound — so `[T: Reader]` gets both traits'
members without having to name both, and a type that forgot one is told at its own declaration rather
than at somebody's call.

Where the required trait's members all have defaults, satisfying it is one line with no block under
it:

```sysl
trait Fallible
    failed(*self) -> bool = false

trait Source: Fallible
    take(*self) -> int

struct Counter
    n: int

impl Fallible for Counter

impl Source for Counter
    take(*self) -> int
        self.n += 1
        self.n

var c = Counter(0)

print(c.take(), c.take(), c.failed())
```

```output
1 2 false
```

That is the shape the standard library's byte surface uses: `Reader` and `Writer` each require
`Fallible`, so a type that is **both** carries one `failed` rather than two that nothing at a call
site could tell apart.

## A trait may take type arguments

Every trait so far has been a property of one type — a `Cat` greets, a `Rect` has an area. A trait
with **parameters** says something about a *relation* between types instead: what a sink accepts,
what a conversion converts from, what an iterator yields.

The arguments are written in the same place wherever the trait is named — `impl Sink[int] for Buffer`,
a bound `[X: Sink[int]]`, an object `&Sink[int]` — and they mean the same thing in each. What that
buys is the rule underneath: **a type implements a parameterized trait once at each argument list**,
so two implementations on one type are ordinary rather than a conflict:

```sysl
trait From[T]
    from(x: T) -> Self

struct Temp
    tenths: int
end Temp

impl From[int] for Temp
    from(x: int) -> Temp = Temp(x * 10)

impl From[real] for Temp
    from(x: real) -> Temp = Temp(int(x * 10.0))

var a = Temp.from(21)
var b = Temp.from(21.5)

print(a.tenths, b.tenths)
```

```output
210 215
```

`Temp` has two members called `from`, and what says which a call means is the **argument list**. The
resolution is *determined*, not preferred: a call is answered by the one implementation whose
parameters match the types the arguments have, and nothing ranks two candidates — so a call matching
none of them is reported rather than resolved to the nearest.

Which is exactly why the arguments have to appear where they can be *seen* at a call. A trait
parameter that shows up only in a **return** type leaves the call with nothing to select on:

```sysl
trait Into[T]
    into(self) -> T

struct Reading
    raw: int
end Reading

impl Into[real] for Reading
    into(self) -> real = f64(self.raw) / 10.0

impl Into[string] for Reading
    into(self) -> string = "raw " + str(self.raw)

var r = Reading(215)

var celsius: real = r.into()

print(celsius)
```

```error
'into' comes from 2 implementations of one trait on Reading, and the arguments do not say which was meant
```

Rust answers that one with turbofish and inference from the expected type; sysl does not, and writing
the conversion as `From[T]` — where the thing being converted is an argument — is the shape that
needs no such machinery.

This is also the mechanism the [error handling](/tour/errors/) chapter points at. A `?` that converts
a callee's error into the caller's needs an `AppError` that is `From[IoError]` **and**
`From[ParseError]`, and those are two argument lists rather than two implementations of one thing.

## Implementing `Display`

The trait worth writing first, because it is what `print` and `str` reach:

```sysl
struct Point
    x: int
    y: int

impl Display for Point
    display(self, out: *Writer, fmt: FormatSpec) =
        display_pad(("(" + str(self.x) + ", " + str(self.y) + ")").bytes, out, fmt)

var p = Point(3, 4)

print(p)
print("as text:", str(p), "padded:", f"${p}%10s")
```

```output
(3, 4)
as text: (3, 4) padded:     (3, 4)
```

Note the shape of the signature: a value renders itself **into a `*Writer`** rather than returning a
string. That is what makes rendering allocation-free — a program in a kernel supplies its own sink
with an ordinary `impl` — and `str(x)` is then the same rendering aimed at a buffer.

`Writer` is the first trait the language itself forms objects of, and `display_pad` is where every
implementation ends up: it applies the `FormatSpec` to the finished bytes, which is why the parts are
gathered before anything is padded. A specifier describes the field the *whole* value occupies, so
`%10s` on a point pads the point and not its first number.

---

Next: [modules and the standard library](/tour/modules/) — how a program is split up, and what comes
in the box.
