---
title: Traits
summary: The one polymorphism mechanism — declaring, implementing, coherence, required traits, and trait objects.
weight: 80
---

sysl has **one** polymorphism mechanism, and it is the `trait`. It is **nominal**: a type
participates in a trait only through an explicit `impl Trait for Type`, and never by coincidence of
method names. From that one mechanism come both dispatch strategies — static, through a
[generic bound](/reference/generics/), and dynamic, through a trait object.

```sysl
trait Greet
    name(self) -> string
    greet(self) -> string = "hello, " + self.name()

struct Cat
    tag: string
end Cat

impl Greet for Cat
    name(self) -> string = self.tag

var c = Cat("ada")

print(c.greet())
```

```output
hello, ada
```

Two things are on display. A trait's member is either a **bare signature**, which an implementation
must supply, or a signature **with a body**, which is a default every implementation inherits unless
it writes its own. And an implementation's members become the type's own, so `c.greet()` is called
exactly as an inherent method is.

## Declaring a trait

A trait's members are the three kinds a type's members are, declared the same way and distinguished
the same way:

| member | form | reached as |
|---|---|---|
| method | `area(self) -> int` | `x.area()` |
| property | `size -> int` | `x.size`, with no parentheses |
| associated function | `zero() -> Self` | `T.zero()`, through the type |

**A property is asked for by dropping the body from its declaration form**, and supplied by an
implementation writing that body:

```sysl
trait Sized
    size -> int

struct Box
    w: int
    h: int
end Box

impl Sized for Box
    size -> int = self.w * self.h

var b = Box(3, 4)

print(b.size)
```

```output
12
```

Nothing about a property's dispatch differs from a method's: it has a receiver, it simply never
spells one, so it takes a table slot beside the methods and a bound licenses reading it exactly as
one licenses calling them.

**Which kind a member is has to match between the trait and the implementation**, and that is a real
check rather than a formality — a property and an associated function both have no receiver to
compare, so `size(self) -> int` would otherwise quietly stand in for `size -> int`.

**`Self` is the implementing type**, written wherever a signature has to name it. Inside a generic
`impl` it is the subject applied to that block's parameters, so `-> Self` and `-> Box[T]` are the one
signature conformance compares.

### A default may assume exactly what its own trait declares

That is not a restriction bolted on; it is what a default *is*, since the body must serve every
implementing type and the trait is all they have in common. So **a default's body is checked once, at
the trait**, as the generic function it is — one type parameter, `Self`, bounded by the trait. A
default calling a member the trait does not declare is reported at the trait, on its own line, **even
when nothing implements the trait at all.**

It may not read a **field** of its receiver either. A bound promises behaviour and a field is layout,
so no bound could ever license one — see [generics](/reference/generics/).

What a default buys beyond convenience is that **a trait can grow**: adding a member with a default
does not break the implementations that already exist, which is the difference between a trait a
library can evolve and one frozen at its first release.

**A trait whose every member has a default leaves nothing to write**, so the block is optional and
the opt-in is the point of writing it:

```sysl
trait Loud
    volume(self) -> int = 11

struct Amp
    n: int
end Amp

impl Loud for Amp

var a = Amp(1)

print(a.volume())
```

```output
11
```

The body a program runs is a **copy per implementing type**, materialized under that type's own name.
That is monomorphization with `Self` for the parameter, so everything downstream — an ordinary call, a
table slot, the escape summary — finds a function that exists and needs to know nothing about where it
came from.

## Conformance is explicit, always

A type that happens to have a member of the right name and shape does **not** satisfy a trait. There
is no structural conformance, and the `impl` is what documents the intent:

```sysl
trait Named
    label(self) -> string

struct P
    n: string

    label(self) -> string = self.n
end P

announce[T: Named](x: T) -> string = x.label()

print(announce(P("ada")))
```

```error
'announce' requires its type parameter 'T' to implement 'Named', but P does not
```

Nominal is the consensus among the languages sysl takes from — Swift protocols, Kotlin interfaces,
and Scala traits are all nominal — and it kills *accidental conformance*, where a type satisfies a
promise purely because two names collided.

**Retrofitting is preserved.** You may `impl` your trait for a type you do not own; you just do it
explicitly rather than by implicit structural match.

## The two dispatch strategies

A trait is used two ways, and this is the pivot a programmer faces every time polymorphism comes up.

| | `[T: Trait]` — static | `&Trait` / `*Trait` — dynamic |
|---|---|---|
| what happens | one specialized copy per concrete `T` | one copy of the code, dispatched through a table |
| the call | direct, inlinable | indirect, not inlined |
| the value | by value, no indirection | behind a fat pointer, always |
| the set of types | fixed at compile time | open |
| cost | code size | one indirect call |
| reach for it when | the type is known — the overwhelming majority | the collection is heterogeneous, or the boundary is a plugin |

**Static** is the default:

```sysl
trait Named
    label(self) -> string

struct Dog
    n: string
end Dog

struct Cat
    n: string
end Cat

impl Named for Dog
    label(self) -> string = "dog " + self.n

impl Named for Cat
    label(self) -> string = "cat " + self.n

announce[T: Named](x: T) -> string = x.label()

print(announce(Dog("rex")), announce(Cat("ada")))
```

```output
dog rex cat ada
```

**Dynamic** is the escape hatch for genuine runtime heterogeneity:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
    h: int
end Rect

struct Square
    s: int
end Square

impl Shape for Rect
    area(self) -> int = self.w * self.h

impl Shape for Square
    area(self) -> int = self.s * self.s

var shapes: [2]&Shape = [Rect(2, 3), Square(4)]

print(shapes[0].area(), shapes[1].area())
```

```output
6 16
```

Same trait, two strategies, chosen by **how you spell the parameter** — a bound for static, a
sigil-carried trait object for dynamic. There is no `dyn` keyword either way, because the sigil
already says everything one would.

**The two are not separate worlds.** A trait object satisfies a bound on the trait it dispatches
through, so `announce` above takes a `&Named` as readily as it takes a `Dog` — the table it carries
holds exactly the members the bound names:

```sysl
trait Named
    label(self) -> string

struct Dog
    n: string
end Dog

impl Named for Dog
    label(self) -> string = "dog " + self.n

announce[T: Named](x: T) -> string = x.label()

var d: &Named = Dog("rex")

print(announce(Dog("rex")), announce(d))
```

```output
dog rex dog rex
```

So the table above is about what the **caller** holds, not about which functions it can reach. A
library written against bounds is open to a program that erased, and needs no second signature
written the other way. See [generics](/reference/generics/) for what the rule rests on — an object
type is a concrete type, so nothing about monomorphization changes to allow it.

## Any type may carry an `impl`

`impl Show for int` is as ordinary as `impl Show for Point`, and `impl Show for []int` is as ordinary
as either. This is not a convenience: a trait that cannot cover `int` is a trait no library can be
written against, and the library's own `Display` is the first thing that needs it.

```sysl
trait Double
    twice(self) -> int

impl Double for int
    twice(self) -> int = self * 2

print(5.twice(), (2 + 3).twice())
```

```output
10 10
```

**Every type has one owner key** its members are filed under: a struct or an enum by the name it was
declared with, everything else by its one canonical name. So `impl Show for int` and
`impl Show for i32` are the single implementation they are rather than two, and two spellings of one
type collide as the duplicate they are.

**An `impl`'s subject is a type reference, not an identifier**, so the types with no name of their own
carry an implementation exactly as the named ones do:

```sysl
trait Total
    total(self) -> int

impl Total for [3]int
    total(self) -> int = self[0] + self[1] + self[2]

var a: [3]int = [1, 2, 3]

print(a.total())
```

```output
6
```

**An array's length is part of its type**, so `[2]int` and `[3]int` are two types and may implement
the same trait differently.

Two subjects are refused, each because an implementation for it would be about nothing:

- **a memory mode** — `*Point`, `&Point`. A mode is a way of *holding* a `Point` rather than a type
  beside it, and a member call already sees through one level of `*` or `&` to find the receiver's
  members, so an `impl` for the mode would register members nothing could reach;
- **a trait object** — `*Show`. An `impl` says how one particular type behaves, and which type it
  holds is precisely what an erased value has forgotten.

```sysl
struct P
    v: int
end P

trait Show2
    show2(self) -> int

impl Show2 for *P
    show2(self) -> int = 1

print(1)
```

```error
'*P' is a way of holding a P rather than a type of its own — write the 'impl' for P
```

**A compiler-provided member is out of reach for the same reason a field is.** `len` on a slice or an
array, and `bytes` on a string, are reached ahead of the member table rather than through it, so an
`impl` declaring one would register a member no reader could find.

### Where an `impl` may live

An `impl` is **unnamed** — nothing at a use site says which one to apply — so resolving `T: Show` or
`5.show()` means *searching* for an implementation. Once a program is more than one module, that
search needs a bound, or it would range over every module in the program, which is exactly the
property that makes separate compilation impossible.

**An `impl Trait for Type` may appear only in the module that declares `Trait`, or in one that
declares a type named in `Type`.** Resolving a bound therefore inspects only the modules a use site
already depends on in order to write the trait and the type down. No global search, and no dependency
edge the source does not show.

This is Rust's orphan rule, and it costs nothing:

- **retrofitting still works** — `impl MyTrait for TheirType` lives with `MyTrait`, and the trait's
  module licenses it;
- **`impl Show for int` still works** — a built-in has no module of its own, so its owner key belongs
  to the library, and every `impl` on one is licensed by its trait's module instead;
- **a composed type is the module's when anything named in it is** — `impl Display for []Point` is
  licensed by `Point`, while `impl Display for []int` names nothing outside the library and has no
  home;
- **a type parameter is not a local type**, so `impl[T: Display] Display for []T` is refused however
  its bound is written. Making every printable slice printable is the library's job.

What the rule forbids is the case with no home: **a foreign trait implemented for a foreign type**,
where two unrelated modules could each supply a different implementation and no rule picks one.

An `impl` is part of its module's public surface. Adding, removing, or changing one is an interface
change visible to everything downstream — the same reasoning that puts implicit-resolution schemes
out of scope. See [modules](/reference/modules/).

## An `impl` covers a generic type as a whole

A block may declare **type parameters of its own**, written directly after the keyword:

```sysl
struct Box[T]
    v: T
end Box

trait Show
    show(self) -> string

impl[T: Display] Show for Box[T]
    show(self) -> string = "box of " + str(self.v)

var b = Box(41)

print(b.show())
```

```output
box of 41
```

That is one implementation for **every** `Box`, and its members are monomorphized per receiver
exactly as a generic type's own members are.

**Its subject must be the type applied to the block's parameters and nothing else** — each argument
one parameter, each parameter used once, all of them spoken for:

```sysl
struct Box[T]
    v: T
end Box

trait Show2
    show2(self) -> int

impl Show2 for Box[int]
    show2(self) -> int = 1

print(1)
```

```error
'Box' is generic, so an 'impl' for it covers every instantiation at once — write 'impl[T] Show2 for Box[T]'
```

A generic type has **one key for all of its instantiations**, so an implementation for *some* of them
would be a second implementation for a key that holds one. Overlapping implementations, and the
specialization rule that would be needed to pick between them, are deliberately not in the language.

The parameters are matched to the arguments **by position in the subject**, not by the order they were
declared in, so `impl[X, Y] Show for Pair[Y, X]` reads as it looks.

### Conditional conformance

A bound on the block is what makes the conformance conditional. In the `Box` example above, a
`Box[T]` implements `Show` **precisely when** `T` implements `Display` — so `Box[int]` does and a
`Box` of something unprintable does not.

That question is asked one step in and **composes**: under `impl[T: Show] Show for Box[T]`, a
`Box[Box[int]]` conforms exactly when `Box[int]` does, which is what makes a conditional
implementation usable on nested types at all.

```sysl
struct Box[T]
    v: T
end Box

trait Show
    show(self) -> string

impl Show for int
    show(self) -> string = "i"

impl[T: Show] Show for Box[T]
    show(self) -> string = "b" + self.v.show()

var b = Box(Box(1))

print(b.show())
```

```output
bbi
```

Everything that asks whether a type conforms asks it the same way — a generic function's bound, an
erasure to a trait object, `print` reaching for a `Display` — so an instantiation that fails the
condition is refused at each of them while its siblings are not.

What the bounds buy beyond deciding conformance is that **the members become checkable at their
definition**: a block states what it assumes, so its bodies are walked once against those bounds
alone, and a method calling something no bound licenses is reported on its own line with nothing
instantiated.

### A shape is covered the same way

A composed type has no name to be generic over, but it has a **shape**, and a block with type
parameters may match that instead:

```sysl
trait Count
    count(self) -> usize

impl[T] Count for []T
    count(self) -> usize = self.len

var xs: []int = [1, 2, 3, 4]

print(xs.count())
```

```output
4
```

Everything above holds unchanged: the subject is the shape applied to the block's parameters and
nothing else, so `impl[T] Count for [][]T` is refused because the element is a shape rather than a
parameter, and so is a fixed element:

```sysl
trait Count
    count(self) -> int

impl[T] Count for []int
    count(self) -> int = 1

print(1)
```

```error
'int' fixes the element type, and an 'impl' with type parameters covers every slice — write one of the block's own parameters here
```

Two things are the shape's own. **A composed type is filed under the whole of itself** — `[]int`,
not `[]` — so a shape needs a key the types it covers do not have, and dropping the arguments is what
makes one; a lookup finding nothing under the type's own key falls back to it. And because an
**array's length is not something a parameter can stand for**, the length stays part of the shape:
`[2]T` and `[3]T` are two shapes, each covering every element type at its own length.

**A `string` is not covered by `[]T`.** It is a view of bytes that are valid UTF-8, and that invariant
is the whole difference between it and a `[]u8` — a block written for every slice has said nothing
about it. `"hi".bytes` is a `[]u8` and is covered.

**A shape and a written-out type may not overlap.** Both would say how a `[]int` renders, and neither
is more specific than the other, because being more specific is not something sysl knows how to be:

```sysl
trait Show2
    show2(self) -> int

impl Show2 for []int
    show2(self) -> int = 1

impl[T] Show2 for []T
    show2(self) -> int = 2

print(1)
```

```error
'[]int' already implements 'Show2', and this 'impl' would implement it for every slice — including that one
```

Whichever is written second is refused, and the diagnostic names the one already there. The choice is
between saying how every slice behaves and saying it slice type by slice type; not both. Refusing the
overlap is the conservative choice and can be relaxed; shipping a rule that picks between two
implementations cannot be walked back.

## A trait may take type parameters

A trait declares parameters in the same bracketed list every other generic declaration writes, and an
implementation says which arguments it supplies:

```sysl
trait Sink[T]
    put(*self, x: T) -> int

struct Buffer
    n: int
end Buffer

impl Sink[int] for Buffer
    put(*self, x: int) -> int
        self.n += x

        self.n

var b = Buffer(0)

print(b.put(3), b.put(4))
```

```output
3 7
```

`Sink` is not one promise but a family of them: `Sink[int]` and `Sink[string]` say different things,
and which one a `Buffer` makes is the implementation's to state. That is what a trait needs before it
can describe a **relation between two types** rather than a property of one — what a sink accepts,
what a conversion converts from, what an iterator yields.

The arguments are written in the same place in all three positions a trait is named, and mean the same
thing in each:

| position | written |
|---|---|
| a bound | `f[X: Sink[int]](x: X)`, and the body's `x.put(…)` then takes an `int` |
| an implementation | `impl Sink[int] for Buffer` |
| a trait object | `&Sink[int]` |

A trait's own parameters carry bounds too — `trait Get[T: Show]` — and everything applying the trait
supplies them, exactly as everything applying a bounded struct does.

**A parameter may carry a default**, and `Self` is the case the feature exists for:
`trait Scale[R = Self]` is the operand type, usually the implementing type, so `impl Scale for P` is
the `impl Scale[P] for P` it reads as and `[T: Scale]` asks for `Scale[T]`. See
[generics](/reference/generics/) for how defaults are filled.

### One implementation per argument list

A type may implement a trait **once at each argument list**, and the argument list is what tells two
implementations apart:

```sysl
trait Sink[T]
    put(self, x: T) -> string

struct Buf
    tag: string
end Buf

impl Sink[int] for Buf
    put(self, x: int) -> string = self.tag + " int"

impl Sink[string] for Buf
    put(self, x: string) -> string = self.tag + " string"

var b = Buf("b")

print(b.put(1), b.put("x"))
```

```output
b int b string
```

A second `impl Sink[int] for Buf` beside those is refused — that one is already there.

This is the one place the "a trait's members become the type's, and a type's members are one
namespace" rule is qualified, and the qualification is narrow. A `Celsius` with both blocks has two
members called `from`, and what says which a use means is the **argument list**, which every way of
reaching one already carries: an operator carries its pair of operands, a bound names the arguments, a
trait object is formed at written arguments, and a named call passes values whose types are the
arguments.

**The resolution is determined, not preferred.** Nothing ranks two candidates: a call is answered by
the one implementation whose parameters are the types the arguments have, and a call answering to none
of them or to more than one is reported rather than resolved. So `c.mul(2)` where the candidates take
a `Complex` and a `real` is refused — an integer literal is neither, and picking the nearest would be
the specialization rule this language does not have.

Two limits fall out of "several implementations are told apart inside one namespace":

- **a property has no arguments**, so two implementations both supplying one leave nothing to select
  with, and reading it is refused;
- **a shape and a type of that shape** are filed under two different owner keys, and a member lookup
  takes one or the other and never both — so a second implementation split across that boundary would
  be one no call could reach.

**A generic block may write its own parameter as a trait argument** — `impl[T] Index[usize, T] for
Buf[T]` says a `Buf[int]` implements `Index[usize, int]` and nothing else. That is what lets a
container carry the type of what it holds in the trait it implements, without an associated type to
derive it from.

## A trait may require another trait

`trait Word: Add + BitXor` — written after the name, with the same `:` and the same `+` a bound uses,
because it asks the same thing of the implementing type.

```sysl
trait Named
    label(self) -> string

trait Greet: Named
    greet(self) -> string = "hello, " + self.label()

struct P
    n: string
end P

impl Named for P
    label(self) -> string = self.n

impl Greet for P

var p = P("ada")

print(p.greet())
```

```output
hello, ada
```

A required trait is a promise the **trait** makes rather than one each declaration repeats. `[T: Greet]`
then licenses `label`; a **default body** in `Greet` may use it, since what a default may assume is
exactly what its trait promises; and a `&Greet` object carries the required trait's members in its
table.

**The requirement is checked at the `impl`, not at the bound**, and the diagnostic belongs on the
declaration that cannot keep its word:

```sysl
trait Named
    label(self) -> string

trait Greet: Named
    greet(self) -> string = "hi"

struct P
    n: string
end P

impl Greet for P

print(1)
```

```error
'Greet' requires 'Named', so 'P' has to implement that too — write 'impl Named for P'
```

Checking at the `impl` is also what keeps conformance a plain lookup: by the time anything asks
whether a type implements a required trait, an implementation of it is already registered. The
question is held until every `impl` has been seen, since the block supplying a required trait may be
written below the one that needs it.

### The table carries the required trait's slots

A trait's members are the required traits' members, depth-first with each trait taken once, followed
by its own. Both the table and the call sites indexing into it are laid out from that one list, so **a
required trait's method is one indirect call**, exactly like the trait's own. The alternative — a word
in the table pointing at the required trait's own table — costs a second load on every such call and
buys one thing sysl does not have: an upcast.

So **a `&Sub` cannot become a `&Super`**, and that is the price of the choice rather than an oversight.
Nothing is unwritable for want of it: what a program does with a required trait is call its members,
which works.

**The diamond needs no rule of its own.** `D: A + C` with both `A: B` and `C: B` carries `B`'s members
once, because the walk takes each trait the first time it reaches it. What *is* refused is two traits
in one closure declaring a member of the same name — and the reason is the **table**, not the
namespace. Two unrelated traits may each name a member of one type, because a call says which by
naming the trait; two traits inside one requirement closure are laid out as one table, and a call
through a `&Sub` has already forgotten everything that could have said which slot it meant.

```sysl
trait L
    len2(self) -> int

trait R
    len2(self) -> int

trait Both: L + R
    both(self) -> int

print(1)
```

```error
'R' and 'L' both declare 'len2', and a trait's members become the implementing type's — so 'Both' cannot require both
```

**A trait may not require itself**, directly or around a cycle:

```sysl
trait Loop: Loop
    step(self) -> int

print(1)
```

```error
trait 'Loop' requires itself, through Loop -> Loop
```

Two rules close the section. A trait may not require one that **reaches less far** than it does, since
implementing the trait means implementing the required one — a requirement the implementer cannot name
leaves the trait unimplementable from outside. And `Self` in a requirement's arguments is the type
implementing the requiring trait, so `trait Vector: Scale[Self]` asks that whatever implements `Vector`
can be scaled by its own type — which is the same requirement `trait Vector: Scale` writes when
`Scale` defaults its parameter to `Self`.

## Trait objects

A trait object is a **fat pointer** — two words, the method table for the type it forgot and the value
itself:

```
{ ptr vtable, ptr data }
```

The sigil says who owns the second word, and nothing else changes between the two:

| written | the data word is | who frees it |
|---|---|---|
| `*Trait` | the value's own address | nobody — raw and unmanaged, like every `*T` |
| `&Trait` | the reference-counted **box** the value sits in | ARC, exactly as for the `&T` it was erased from |

`*T` and `&T` on a *concrete* type are thin pointers; on a *trait* they are fat. The trait-ness makes
them fat, and that is why there is no `dyn`.

**The table is per (trait, type, sigil)**, two flavours rather than one because the data word means
different things: an entry has to reach a receiver, and from a box that is one step further in than
from a bare value. Where the data word already *is* the receiver an implementation declared, the entry
names that implementation itself; otherwise it names a small adapter that steps over the box header,
loads the value, or both. So the common case costs one indirect call and nothing else.

### Object safety

Erasure forgets the type, so a member may promise nothing that depends on knowing it. A trait may be
made into an object when every member:

- **has a receiver.** An associated function has nothing to dispatch on. A property does have one — by
  value, and unwritten — so a trait asking for a property is as safe to erase as one asking for a
  method.
- **mentions `Self` nowhere but that receiver.** A second `Self` would have to be the *same* forgotten
  type as the first, which is exactly the fact an object no longer carries, and a `Self` result has no
  size to hand back.
- **does not take `&self`, for a `*Trait` only.** `&self` asks for its receiver inside a box, and a raw
  object points straight at a value. A `&Trait` carries one, so it accepts such a member.
- **takes no `...`.** A call to a variadic names the callee's *whole* function type, because that is
  how it says where the declared parameters stop and the tail begins, and a slot in a table is one word
  and names none.

```sysl
trait Scale
    scale(self, k: int) -> Self

struct P
    v: int
end P

impl Scale for P
    scale(self, k: int) -> P = P(self.v * k)

grow(s: &Scale) -> int = 0

print(1)
```

```error
'scale' of 'Scale' mentions 'Self' away from its receiver, and an erased value has forgotten which type that is — so there is no '&Scale' to form
```

The middle rule excludes **every trait in the operator catalog** — `add(self, rhs: Self) -> Self`
first among them — and that is the right answer rather than a limitation: those traits describe an
operator over two values of one type, which is a question about types known at compile time. They are
for bounds.

**A trait that requires an unerasable one is unerasable itself**, and the diagnostic names the trait
the offending member came from. A **built-in** that satisfies a requirement by the compiler's rule
cannot be erased through it either — a table holds function pointers, and a scalar's `add` is an
instruction.

`Hash` is the only trait where that bites, since every other compiler-provided membership is an
operator and already excluded above. **`Display` is not one of them**: every built-in reaches it
through an `impl`, so a `*Display` carries an `int`, a `u256`, a `string` or a float alike, and a
heterogeneous array of them is ordinary code.

### Forming and using one

Erasure is a **coercion**, applied wherever a trait-object type is expected: at an argument, a declared
variable, an assignment, a returned value, an array element, a struct field. `&r` erases to `*Shape`;
a `&Rect` erases to `&Shape`; and a plain `Rect(3, 4)` where a `&Shape` is expected is boxed and then
erased, which is the ordinary "write the construction and it is allocated" rule with one more step.

**A `*Trait` will not take a bare value.** A raw pointer needs an address, and taking one of a
temporary silently is how a program acquires a dangling pointer:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
end Rect

impl Shape for Rect
    area(self) -> int = self.w

var s: *Shape = Rect(2)

print(s.area())
```

```error
a *Shape points at a value, so it needs an address — write '&' in front of the Rect to take one
```

Because the coercion applies **per branch**, an `if` or a `match` whose arms are different concrete
types meets at one trait object, which is the point of having them:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
    h: int
end Rect

struct Square
    s: int
end Square

impl Shape for Rect
    area(self) -> int = self.w * self.h

impl Shape for Square
    area(self) -> int = self.s * self.s

var wide = true
var s: &Shape = if wide then Rect(2, 3) else Square(4)

print(s.area())
```

```output
6
```

**What an object offers is the trait's members and nothing else**: no dereference, no fields, no
comparison.

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
    h: int
end Rect

impl Shape for Rect
    area(self) -> int = self.w * self.h

var s: &Shape = Rect(2, 3)

print(s.w)
```

```error
a &Shape has no fields, and trait 'Shape' declares no 'w'
```

A call is checked against the **trait's** signature, which stands in for every implementation because
conformance is exact.

**An object keeps one trait and what that trait requires.** A bound may name several traits because a
bound is a list; a trait-object type names one because it is a type. So a value implementing `Shape`
*and* `Display` keeps only the first when it becomes a `&Shape` — unless `Shape` **requires**
`Display`, which is what makes the difference between the object being printable and not. A
multi-trait object type (`&(Shape + Display)`) would be a second way to say the same thing and a worse
one, since it puts at every use a fact that belongs on the trait.

### The two sigils do not convert

`*Trait` and `&Trait` are two types and neither is accepted for the other.

In the direction that would matter — lending a counted object to something that only wants to ask it
questions — this is sharper than it is for plain references, which have a spelling for it: `&*r` is the
address of the place `*r`, so a `&T` reaches a function written against `*T` with the crossing into the
unsafe tier written down at the call. An object has no dereference, so `&*o` says nothing, and a
function that only reads a shape has to exist once per sigil. That is recorded as a gap rather than
settled; what is missing is a spelling, and any spelling must keep the crossing greppable.

The other direction stays refused for a stronger reason: a raw object points at a value with no count
to take a share of, so accepting one where a counted object is wanted would be **inventing ownership**.

### An object cannot be erased a second time

`trait Shape: Display` puts `Display`'s slots in a `&Shape`'s table, and a `[T: Display]` bound is
satisfied by the object — but a `&Display` is not, and the difference is worth being exact about. A
bound asks what may be **called** through the value, and the table answers it. Forming an object asks
what may be **assembled** from the value's type, and a table is laid out from a type's
implementations, which an object has none of.

```sysl
trait Shape: Display
    area(self) -> int

struct Rect
    w: int
    h: int
end Rect

impl Shape for Rect
    area(self) -> int = self.w * self.h

impl Display for Rect
    display(self, out: *Writer, fmt: FormatSpec) = display_pad("a rect".bytes, out, fmt)

var o: &Shape = Rect(3, 4)
var d: &Display = o
```

```error
a &Shape has forgotten which type it holds, so there is nothing for a &sysl.Display to be built from
```

`Display`'s members really are in that table — they are laid out inline, so that a required trait's
method stays the one indirect call the trait's own methods are. What is missing is a *name* for that
run of slots. This is the upcast flattening gives up, and it is the reason it is worth giving up:
every call through a `&Shape` costs one indirection instead of two.

### There is no way back to the type

An object cannot be asked what it forgot. There is no cast and no test, and the spelling a reader
reaches for first is not one either — a type is not a pattern, so `s is Rect` reads `Rect` as an
ordinary binding, which matches anything:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
    h: int
end Rect

impl Shape for Rect
    area(self) -> int = self.w * self.h

var s: &Shape = Rect(2, 3)

if s is Rect then print("yes") else print("no")
```

```error
this pattern matches every &Shape, so the test is always true — take the value apart with 'match', or bind it with 'var'
```

**This is a decision, not an omission.** A downcast is the one operation that makes erasure a lie:
every other rule here says an object offers the trait's members and nothing else, and a type test
would say that it also secretly offers its identity, which is what the table pointer is and what the
type deliberately stops promising. Languages that offer it need a whole parallel mechanism to do so,
and that mechanism is the honest price rather than a small addition to this one.

The cost is real and worth writing down beside the decision. A program that wants to count the circles
in a catalogue has to be told, so it declares a `kind` property every implementation answers with a
constant — a hand-maintained copy of exactly the fact the object's first word already is.

## Reaching a trait's members without a value

A trait may declare a member with **no receiver** — an associated function — reached through the
*type* rather than through a value of one:

```sysl
trait Zero
    zero() -> Self

impl Zero for int
    zero() -> int = 0

start[T: Zero]() -> T = T.zero()

var n: int = start()

print(n)
```

```output
0
```

`T.bits()` inside a generic body, `Self.bits()` inside a member's, and `u32.bits()` from anywhere are
the same member reached through three spellings of its type.

**A built-in may carry one.** Through a bound the name is the *parameter*, which every type has
whether or not it has one of its own — so `impl Word for u32` may declare `bits()`, and `impl Float
for real` declares the zero, the one, the epsilon, and the two values no literal spells. What stays
refused is the case the rule was really about: a **composed** type has no name at all, so an `impl` for
`[]int` still refuses a member with no receiver.

**It is static dispatch only, and nothing was added to keep it that way.** Object safety already
excludes a member with no receiver, because a table slot is selected *by* the receiver and there is
nothing here to select with. A trait declaring one is usable as a bound and not as an object, exactly
as one that mentions `Self` twice already is.

One gap the mechanism makes visible: a routine can be entirely *about* a type and mention it nowhere in
its signature, and such a function cannot be called, because inference reads the binding and a call
cannot write its type arguments. `describe[T: Word]() -> string` is well-formed and unreachable.
Taking a value of `T` is the workaround.

---

Next: [generics](/reference/generics/).
