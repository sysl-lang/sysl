---
title: Traits and generics
summary: One mechanism for polymorphism, two ways to spend it — a bound for static dispatch, a sigil for dynamic.
weight: 100
---

sysl has **one** polymorphism mechanism, the `trait`. A type implements a trait only through an
explicit `impl Trait for Type`. There is no structural conformance, so a type never satisfies a trait
just because its method names happen to match.

The mechanism is the same throughout; what varies is how the parameter is written:

- `[T: Trait]` is a **bound** on a generic: monomorphized, with direct calls and no indirection.
- `&Trait` / `*Trait` is a **trait object**: one copy of the code, dispatched through a table.

One trait supports both strategies, and there is no `dyn` keyword: the memory-mode sigil already
says what one would.

## A trait, and an implementation

```sysl
trait Device
    name(self) -> string
    label(self) -> string = "device " + self.name()

struct Uart
    port: string

impl Device for Uart
    name(self) -> string = self.port

var u = Uart("ttyS0")

print(u.label())
```

```output
device ttyS0
```

A member written as a bare signature must be supplied by each implementation. A member written
**with a body** is a default, which every `impl` inherits unless it provides its own.

Defaults let a trait *grow*: adding a member with a default does not break existing
implementations, so a library can extend a trait after its first release.

A default's body may rely only on what its own trait declares about the receiver. The body must work
for every implementing type, and the trait is all those types are known to share. This is checked
once, at the trait, even when nothing implements it yet.

Built-in types can implement traits too. This is necessary: a `Show` trait that could not cover `int`
would be of little use to a library.

```sysl
trait Show
    show(self) -> string

impl Show for int
    show(self) -> string = "int " + self.to_string()

impl Show for bool
    show(self) -> string = if self then "yes" else "no"

var n = 5

print(n.show(), true.show())
```

```output
int 5 yes
```

There is one restriction on where an `impl` may be written: in the module that declares the trait,
or in one that declares a type named in the subject. This is Rust's orphan rule. Extending another
library's type still works, because `impl MyTrait for TheirType` lives with `MyTrait`. In return,
resolving a bound only inspects modules the use site already depends on, with no global search and
no hidden dependency.

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

`Pair` requires nothing of `A` and `B`. A parameter with no bound may be **copied, assigned, passed,
returned and stored**, which is everything a container needs.

That baseline is broader than in Rust, for the reason given in the memory chapter: every sysl value
can be copied, because assignment copies and copying a value that holds a `&T` retains it. There is
therefore **no `Copy` bound** to write.

Type arguments are inferred from two directions: from the arguments (`Pair(1, "one")` gives
`A = int, B = string`), and from the expected type where the arguments do not settle it. A parameter
that neither determines is an error asking for an annotation, never a silent default.

An unbounded `T` may **not** be used in any way that assumes structure: no operator, method call,
field access or subscript. Each of these is a capability some types have and others lack, so each
needs a bound that guarantees it:

```sysl
trait Show
    show(self) -> string

impl Show for int
    show(self) -> string = "#" + self.to_string()

describe[T: Show](x: T) -> string = "<" + x.show() + ">"

var n = 7

print(describe(n))
```

```output
<#7>
```

Remove the bound and the error is reported at **the definition that is wrong**, not at a caller in
another file that instantiated it:

```sysl
trait Show
    show(self) -> string

describe[T](x: T) -> string = x.show()
```

```error
'show' needs 'T: Show'
```

The diagnostic names the bound to add. These are definition-checked generics, as in Swift, Kotlin and
Scala, unlike C++ templates, whose errors appear at instantiation and refer to the callee's internals.

Multiple bounds are joined with `+`: `[T: Ord + Hash]` requires both.

**A field is the one exception.** Every other disallowed use names the bound that would allow it. A
field access names none, because a trait describes *behaviour* and a field is *layout*, so no bound
can provide one. Generic code reaches a value's data through a member the bound declares, which also
lets two types satisfy one bound while storing their data differently.

Since operators are trait members, a bound is all that arithmetic over a parameter needs:

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

`+` is `Add`, `<` is `Ord`, `==` is `Eq`. Operators are not special-cased in generics; an operator on
a `T` requires the bound that supplies it, just as a method call does.

A type's own parameters take bounds in the same brackets, with the same meaning, and that is where a
type states its assumptions. `struct SortedList[T: Ord]` requires every use of the type to satisfy
the bound, and lets its members be checked at their definitions rather than at each use.

A bound is declared **once**, on the type, and applies everywhere its parameters appear: a member's
signature and body, a field's type, a variant's payload. It is not repeated per member.

Each distinct set of type arguments produces its own specialized function or type. `sum` called with
`int` and with `real` emits two functions; `Pair[int, string]` and `Pair[int, int]` have two layouts.

This is what lets bounds be checked once at the definition and still compile to direct, inlinable
code with no dictionary passed at run time. The cost is code size, the usual trade-off and the right
default for a systems language; trait objects, below, provide a single shared copy when that is what
you need.

**There is no variance.** Variance concerns when `G[A]` may stand in for `G[B]`, which only matters
with subtyping, and sysl has no subtyping between concrete types. `Box[Cat]` and `Box[Animal]` are
unrelated types. Polymorphism over a set of types is expressed with a bound or a trait object, never
a covariant container.

## Trait objects

When the set of types is open or the values are of mixed types, monomorphizing is impossible or
wasteful. A trait object is a **fat pointer**: two words, the method table for the erased type and
the value itself:

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

print("total:", total(shapes))
```

```output
total: 37
```

The sigil says who owns the data word; nothing else differs between the two forms. `&Shape` is a
reference-counted object: the data word is the counted box holding the value, and releasing it needs
no more knowledge of the payload than releasing any `&T`. `*Shape` is a raw fat pointer, unmanaged
like every `*T`, which is what a kernel uses when there is no allocator.

Erasure is a **coercion**, applied wherever an object type is expected: an argument, a declared
variable, an array element, a struct field. Above, each `Rect(3, 4)` is constructed, placed in a box
because a `&Shape` was expected, and then erased. This is the ordinary rule that a construction where
a `&T` is expected is allocated, with one more step. Because the coercion applies to each branch, an
`if` or a `match` whose arms produce different concrete types can yield one trait object.

A trait object offers the trait's methods and nothing else: no dereference, no fields, no comparison,
and no way back to the concrete type.

**Bounds and trait objects work together.** An object carries a table of the trait's members, which
is exactly what a bound requires, so a `[T: Shape]` function accepts a `&Shape` as readily as a
`Rect`, making an indirect call where the other makes a direct one. The choice between static and
dynamic dispatch depends on what the *caller* holds, not on which functions it can call, and a
library written against bounds is usable from a program that uses trait objects.

Erasure forgets the type, so no member may depend on knowing it. A trait can be used as an object
when every member has a receiver and mentions `Self` nowhere else.

That second rule excludes **every operator trait**, starting with `add(self, rhs: Self) -> Self`. An
operator over two values of one type is a question about types known at compile time, so those
traits are used as bounds.

`trait Reader: Fallible` means that implementing `Reader` requires implementing `Fallible` too. The
requirement is checked **at the `impl`**, not at the bound, so `[T: Reader]` gets the members of both
traits without naming both, and a type that is missing one is told at its own declaration rather than
at a caller.

Where every member of the required trait has a default, satisfying it takes one line with no body:

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

The standard library's byte I/O uses this shape: `Reader` and `Writer` each require `Fallible`, so a
type that is **both** has a single `failed` rather than two that a call site could not distinguish.

A trait may also take **type arguments**, as in `impl From[int] for Temp` beside
`impl From[real] for Temp`, so one type can implement it once for each argument list. Conversion
APIs are built this way; [the reference](/reference/traits/) covers it.

## Implementing `Display`

`Display` is usually the first trait to implement, because `print` and `to_string` use it:

```sysl
struct Point
    x: int
    y: int

impl Display for Point
    display(self, out: *Writer, fmt: FormatSpec) =
        display_pad(s"(${self.x}, ${self.y})".bytes, out, fmt)

var p = Point(3, 4)

print(p)
print("as text:", p.to_string(), "padded:", f"${p}%10s")
```

```output
(3, 4)
as text: (3, 4) padded:     (3, 4)
```

A value renders itself **into a `*Writer`** rather than returning a string. Rendering therefore needs
no allocation, since a program in a kernel can supply its own output sink with an ordinary `impl`,
and `x.to_string()` — a member every value has — is the same rendering directed into a buffer.

`Writer` is the first trait the language itself forms objects of. `display_pad` applies the
`FormatSpec` **once, to the whole value**: a specifier describes the field the entire value occupies,
so the `%10s` above padded the whole point, not its first number.

Building the parts into a string first, as this implementation does, is the shortest way to follow
that rule, but the library does it differently. `Counting` is a `Writer` that records the *length* of
what it receives and discards the bytes, so an implementation can measure its output, pad once, and
then write the parts directly: two passes and no allocation. `[]T`, `Option`, `Result` and
`Complex[F]` all work this way, and [the library's own page](/library/core/) shows both approaches
side by side.

---

Next: [modules and the standard library](/tour/modules/): how a program is divided into files, and
what the standard library provides.
