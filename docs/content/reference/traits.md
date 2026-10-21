---
title: Traits
summary: The one polymorphism mechanism — declaring, implementing, coherence, associated types, required traits, and trait objects.
weight: 80
---

sysl has **one** polymorphism mechanism: the `trait`. It is **nominal**: a type participates in a
trait only through an explicit `impl Trait for Type`, never because its method names happen to
match. Both dispatch strategies come from this one mechanism: static dispatch through a
[generic bound](/reference/generics/), and dynamic dispatch through a trait object.

```sysl
trait Device
    name(self) -> string
    describe(self) -> string = "device " + self.name()

struct Pump
    tag: string

impl Device for Pump
    name(self) -> string = self.tag

var p = Pump("P-101")

print(p.describe())
```

```output
device P-101
```

The example shows two things. A trait's member is either a **bare signature**, which an
implementation must supply, or a signature **with a body**, a default every implementation inherits
unless it writes its own. And an implementation's members become the type's own, so `p.describe()`
is called exactly as an inherent method is.

## Declaring a trait

A trait's members are the same kinds a type's members are, declared and distinguished the same way:

| member | form | reached as |
|---|---|---|
| method | `area(self) -> int` | `x.area()` |
| property | `size -> int` | `x.size`, with no parentheses |
| a property's setter | `set size(n)` | `x.size = n` |
| associated function | `zero() -> Self` | `T.zero()`, through the type |
| [static property](/reference/declarations/#a-static-property) | `static lowest -> int` | `T.lowest`, through the type and with no parentheses |

**A trait requires a property by declaring it without a body**, and an implementation supplies it by
writing the body:

```sysl
trait Sized
    size -> int

struct Box
    w: int
    h: int

impl Sized for Box
    size -> int = self.w * self.h

var b = Box(3, 4)

print(b.size)
```

```output
12
```

A property dispatches exactly as a method does: it has a receiver that is never written, so it takes
a table slot beside the methods, and a bound allows reading it just as it allows calling them.

**A trait may also require the setter**, declared beside the property
([declarations](/reference/declarations/)). A setter is a `*self` method that mentions `Self` only in
its receiver, so it takes a slot of its own and does not affect object safety. Both a bound and an
erased object can write the property:

```sysl
trait Counter
    count -> int
    set count(n)

struct Cell
    v: int

impl Counter for Cell
    count -> int = self.v
    set count(n)
        self.v = n

bump[C: Counter](c: *C)
    c.count += 1

var cell = Cell(41)

bump(&cell)

var erased: &Counter = Cell(0)

erased.count = 7

print(cell.count, erased.count)
```

```output
42 7
```

An implementation supplies both halves, or inherits either as a default; a block supplying only the
setter need not restate the property. **A property reached through a bound or a table belongs to the
trait**, so its setter is declared there. Writing a setter the trait does not declare is reported
against the trait rather than the concrete type, because an inherent setter would not allow the
write.

**The kind of a member must match between the trait and the implementation.** This check matters: a
property and an associated function both lack a receiver to compare, so without it
`size(self) -> int` could stand in for `size -> int`. A **static** property is a third kind with no
receiver, and the `static` keyword distinguishes it from the other two.

**An implementation may take by value what the trait takes by address.** A trait's `*self` states
what every caller must be able to pass: an address, so a generic body or a table can let the member
write through it. An implementation that needs less may ask for less. Written `self`, the call passes
it a copy of the receiver instead, and a `val` is accepted. This suits a **handle**, a type whose
fields share their storage, since writing through a copy changes the same storage the original
refers to:

```sysl
import sysl.buf.{Buf, buf}

trait Bump
    bump(*self)

struct Tally
    b: Buf[int]

impl Bump for Tally
    bump(self) = self.b.push(1)

val t = Tally(buf())

t.bump()
t.bump()

print(t.b.len())
```

```output
2
```

That is how `b[i] = x` works on a `val` holding a [`Buf`](/library/buf/): `IndexSet` declares
`index_set(*self, …)` and the `Buf`'s implementation takes `self`. A generic body is checked again
at each instantiation, so a call there uses the receiver of the implementation it reaches; a table
slot for such a member points at an adapter that loads the value. **Every other receiver must
match**, including the converse: an implementation that wants an address where the trait declares
only a value would require something callers never agreed to provide:

```sysl
trait Show
    show(self)

struct P
    v: int

impl Show for P
    show(*self) = print(self.v)
```

```error
method 'show' of 'impl Show for P' takes a different receiver than the trait declares
```

**`Self` is the implementing type**, written wherever a signature needs to name it. Inside a generic
`impl` it is the subject applied to that block's parameters, so conformance treats `-> Self` and
`-> Box[T]` as the same signature.

**A trait that declares nothing is closed by its `end` marker**, as a struct with no fields is. The
emptiness is stated rather than inferred from a missing body, so a misindented member list cannot
silently produce an empty trait. Such a trait is a name a bound can require, and an `impl` of it has
nothing to supply:

```sysl
trait Marked
end Marked

tagged[T: Marked](x: T) -> T = x

impl Marked for int

print(tagged(4))
```

```output
4
```

Without the marker a body is still expected (`indent expected`). The library's
[`AnyBits`](/library/core/#anybits-storage-whose-every-bit-pattern-is-a-value) is such a trait, and
the compiler decides its membership.

### A default may assume exactly what its own trait declares

A default's body must serve every implementing type, and the trait is all they have in common. So
**a default's body is checked once, at the trait**, as the generic function it is: one type
parameter, `Self`, bounded by the trait. A default calling a member the trait does not declare is
reported at the trait, on its own line, **even when nothing implements the trait.**

It may not read a **field** of its receiver either. A bound describes behaviour and a field is
layout, so no bound can allow one; see [generics](/reference/generics/).

**Every name in a default is resolved in the trait's module**, in its signature as well as its body.
A default returning `Buf[int]`, or taking a struct the trait's module declares, is inherited by an
`impl` in a module that imports neither, including one in another package. A type of the same name
in the implementing module never substitutes for one the trait's module cannot see; that is
reported once, at the trait. `Self` is the one name a copy takes from the `impl`.

Beyond convenience, defaults let **a trait grow**: adding a member with a default does not break
existing implementations, so a library can extend a trait after its first release.

**A trait whose every member has a default leaves nothing to write**, so the block body is empty, and
writing the `impl` is the opt-in:

```sysl
trait Retry
    retries(self) -> int = 3

struct Link
    n: int

impl Retry for Link

var l = Link(1)

print(l.retries())
```

```output
3
```

The body a program runs is a **copy per implementing type**, generated under that type's own name.
This is monomorphization with `Self` as the parameter, so everything downstream (an ordinary call, a
table slot, the escape summary) finds an ordinary function and needs no knowledge of its origin.

### Replacing a default says `override`

An implementation may write the member itself instead of taking the trait's body, and when it does
it says so. Replacing a default is the same act as
[replacing an implementation](#override-when-the-overlap-is-deliberate), and takes the same keyword:

```sysl
trait Retry
    retries(self) -> int = 3

struct Link
    n: int

impl Retry for Link
    override retries(self) -> int = self.n

var l = Link(4)

print(l.retries())
```

```output
4
```

Without the keyword the member is refused. A reader of an `impl` block needs to know which members
replace a default and which supply what the trait requires, and would otherwise have to open the
trait to find out:

```sysl
trait Retry
    retries(self) -> int = 3

struct Link
    n: int

impl Retry for Link
    retries(self) -> int = self.n

print(1)
```

```error
trait 'Retry' supplies a body for method 'retries', so writing one here replaces it — say 'override retries', or leave the member out to keep the trait's
```

**The keyword is also refused where nothing is replaced.** A member that answers a bare requirement,
as most members of most `impl` blocks do, supplies what the trait requires rather than replacing a
body, so the keyword would be false:

```sysl
trait Sized
    size -> int

struct Box
    w: int
    h: int

impl Sized for Box
    override size -> int = self.w * self.h

print(1)
```

```error
trait 'Sized' declares property 'size' without a body, so this member supplies what the trait asked for rather than replacing anything — 'override' says a body was replaced
```

The rule rarely needs writing, because an implementation that keeps a default writes no member for
it, and replacing a default is uncommon.

## Conformance is explicit, always

A type that happens to have a member of the right name and shape does **not** satisfy a trait. There
is no structural conformance; the `impl` documents the intent:

```sysl
trait Named
    label(self) -> string

struct P
    n: string

    label(self) -> string = self.n

announce[T: Named](x: T) -> string = x.label()

print(announce(P("ada")))
```

```error
'announce' requires its type parameter 'T' to implement 'Named', but P does not
```

Swift protocols, Kotlin interfaces and Scala traits are nominal too. Nominal conformance rules out
*accidental conformance*, where a type satisfies a trait only because two names happen to match.

**Retrofitting is still possible.** You may `impl` your trait for a type you do not own; you do it
explicitly rather than by structural match.

## Reaching a member through its trait

**A member that an `impl` gives a type is reached on a value of that type without importing the
trait.** The impl travels with the type: wherever the program can see it (and a program sees every
impl of the modules it is built from), `x.member()` finds it, as Scala 3 finds a given through its
*implicit scope*. So a float has its `sqrt` and a slice its `map` in a file that imports nothing:

```sysl
val a = [1, 2, 3]
val xs = a[0..]
val r = 2.25

print(xs.map(x -> x * 2), r.sqrt(), (-r).abs(), real.epsilon() < 1.0e-15)
```

```output
[2, 4, 6] 1.5 2.25 true
```

**The traits the file names are searched first.** The lookup has two tiers. The first is the traits
the file can name: its own module's, the ones it imported, and the ones a bound in force provides.
Only where none of those gives the type a member of that name does the second tier apply: every
trait whose impl the program can see. So a program's own trait takes precedence over a library trait
with the same member name that the file never mentions, and an import still settles which of two
traits is meant:

```sysl
trait Level
    magnitude(self) -> Self

impl Level for f32
    magnitude(self) -> f32 = self + 1

print(f32(-2).magnitude())
```

```output
-1
```

`sysl.math.Magnitude` also gives `f32` a `magnitude`; this file names `Level` and not `Magnitude`, so
`Level`'s member is the one called.

**Where the file names none of several traits that each give the type the member, the call is
ambiguous.** With a `Weigh` in one module and a `Measure` in another, each implemented for a `Box`
from a third, a file importing only `Box` is refused at `Box(n).size()` with

```text
'size' on boxes.Box comes from measure.Measure and weigh.Weigh, and this file names none of them — nothing in the call says which was meant; import the one that was meant
```

and importing either trait resolves it. A trait private to its module is not reached from outside
that module by either tier.

**Generic code still needs the bound.** Inside `f[T](x: T)` the type is not known, so no impl can be
found for it: a `T` has the members its bounds provide and no others, and `x.sqrt()` there requires
`T: Float`.

### Two traits may declare a member of one name

The two members do not conflict, and a type may implement both traits. The type the call goes
through decides which is meant, so erasing to one trait or the other selects it:

```sysl
trait Weigh
    size(self) -> int

trait Measure
    size(self) -> int

struct Box
end Box

impl Weigh for Box
    size(self) -> int = 1

impl Measure for Box
    size(self) -> int = 2

val w: &Weigh = Box()
val m: &Measure = Box()

print(w.size(), m.size())
```

```output
1 2
```

**Called on the value itself, with both traits in scope, the call is ambiguous:**

```sysl
trait Weigh
    size(self) -> int

trait Measure
    size(self) -> int

struct Box
end Box

impl Weigh for Box
    size(self) -> int = 1

impl Measure for Box
    size(self) -> int = 2

print(Box().size())
```

```error
'size' on Box comes from Weigh and Measure, and each is in scope here — nothing in the call says which was meant
```

The refusal depends on *each is in scope here*. A file importing only one of the two has no
ambiguity to resolve, which is the ordinary case, so this is rarely encountered.

**Sharing one name has no other cost.** The trait's other members remain the type's, and a bound
reaches every one of them, including an associated function called through the type's name. Here
`Level` shares `magnitude` with the library's `sysl.math.Magnitude`, which `f32` already implements:

```sysl
trait Level
    magnitude(self) -> Self
    half() -> Self

impl Level for f32
    magnitude(self) -> f32 = self * 2
    half() -> f32 = 0.5

h[S: Level]() -> S = S.half()
twice[S: Level](x: S) -> S = x.magnitude()

print(h[f32](), f32.half(), twice(f32(-3)))
```

```output
0.5 0.5 -6
```

The same rule holds through the type's name as on a value: an associated function both traits
declare, called as `f32.half()` with both in scope, is refused with the message above, and a bound
naming one of the two settles it.

**The ambiguity cannot be avoided for a member with no arguments on a type that implements both
traits**, so a member that two traits both need belongs in a **required** trait rather than in each.
`Writer` and `Reader` both need to report whether a stream has failed, and an open file is both; a
`failed` declared by each would produce the refusal above on exactly the type that most needs it.
The library instead has both require [`Fallible`](/reference/errors/), which removes the ambiguity.

## The two dispatch strategies

A trait can be used in two ways, and choosing between them is the main decision whenever
polymorphism is needed.

| | `[T: Trait]` — static | `&Trait` / `*Trait` — dynamic |
|---|---|---|
| what happens | one specialized copy per concrete `T` | one copy of the code, dispatched through a table |
| the call | direct, inlinable | indirect, not inlined |
| the value | by value, no indirection | behind a fat pointer, always |
| the set of types | fixed at compile time | open |
| cost | code size | one indirect call |
| use it when | the type is known, which is most of the time | the collection is heterogeneous, or the boundary is a plugin |

**Static** is the default:

```sysl
trait Named
    label(self) -> string

struct Pump
    tag: string

struct Valve
    tag: string

impl Named for Pump
    label(self) -> string = "pump " + self.tag

impl Named for Valve
    label(self) -> string = "valve " + self.tag

announce[T: Named](x: T) -> string = x.label()

print(announce(Pump("P-1")), announce(Valve("V-7")))
```

```output
pump P-1 valve V-7
```

**Dynamic** dispatch is for values whose types vary at run time:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
    h: int

struct Square
    s: int

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

The same trait supports both strategies, chosen by **how the parameter is written**: a bound for
static dispatch, a trait object behind a sigil for dynamic. There is no `dyn` keyword, because the
sigil already says it.

**The two work together.** A trait object satisfies a bound on the trait it dispatches through, so
`announce` above accepts a `&Named` as readily as a `Pump`; the table the object carries holds
exactly the members the bound names:

```sysl
trait Named
    label(self) -> string

struct Pump
    tag: string

impl Named for Pump
    label(self) -> string = "pump " + self.tag

announce[T: Named](x: T) -> string = x.label()

var d: &Named = Pump("P-1")

print(announce(Pump("P-1")), announce(d))
```

```output
pump P-1 pump P-1
```

The table above therefore describes what the **caller** holds, not which functions it can reach. A
library written against bounds also serves a program that uses trait objects, with no second
signature. See [generics](/reference/generics/) for why: an object type is a concrete type, so
monomorphization applies to it unchanged.

## Any type may carry an `impl`

`impl Show for int` is as ordinary as `impl Show for Point`, and so is `impl Show for []int`. This is
essential rather than convenient: a library cannot be written against a trait that cannot cover
`int`, and the library's own `Display` depends on it.

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

**Every type has one owner key** under which its members are recorded: a struct or an enum by its
declared name, every other type by its canonical name. So `impl Show for int` and
`impl Show for i32` are one implementation, not two, and writing both is reported as a duplicate.

**An `impl`'s subject is a type reference, not an identifier**, so types with no name of their own
carry an implementation exactly as named ones do:

```sysl
trait Total
    total(self) -> int

impl Total for [3]int
    total(self) -> int = self[0] + self[1] + self[2]

var a = [1, 2, 3]

print(a.total())
```

```output
6
```

**An array's length is part of its type**, so `[2]int` and `[3]int` are two types and may implement
the same trait differently.

Two subjects are refused, because an implementation for either would have no effect:

- **a memory mode**, such as `*Point` or `&Point`. A mode is a way of *holding* a `Point` rather than
  a separate type, and a member call already sees through one level of `*` or `&` to find the
  receiver's members, so an `impl` for the mode would register members nothing could reach;
- **a trait object**, such as `*Show`. An `impl` says how one particular type behaves, and an erased
  value does not know which type it holds.

```sysl
struct P
    v: int

trait Show2
    show2(self) -> int

impl Show2 for *P
    show2(self) -> int = 1

print(1)
```

```error
'*P' is a way of holding a P rather than a type of its own — write the 'impl' for P
```

**A compiler-provided member cannot be implemented, for the same reason a field cannot.** `len` on a
slice or an array, and `bytes` on a string, are found before the member table is consulted, so an
`impl` declaring one would register a member no call could reach.

### Where an `impl` may live

An `impl` is **unnamed** (nothing at a use site says which one to apply), so resolving `T: Show` or
`5.show()` means *searching* for an implementation. In a program of several modules, that search
must be bounded; otherwise it would range over every module in the program, which would make
separate compilation impossible.

**An `impl Trait[A…] for Type` may appear only in the module that declares `Trait`, or in one that
declares a type the block names, in `Type` or among `A…`.** Resolving a bound therefore inspects
only modules the use site already depends on in order to name the trait and the type. There is no
global search, and no dependency the source does not show.

This is Rust's orphan rule, and it leaves the common cases open:

- **retrofitting works**: `impl MyTrait for TheirType` lives with `MyTrait`, and the trait's module
  makes it valid;
- **`impl Show for int` works**: a built-in type has no module of its own, so its owner key belongs
  to the library, and every `impl` on one is made valid by its trait's module instead;
- **a composed type belongs to a module when anything named in it does**: `override impl Display
  for []Point` is valid in the module that declares `Point`, while a block for `[]int` names nothing
  outside the library and has no home. (It says `override` because the library implements `Display`
  for every slice. The two rules are separate, and a slice of your own struct needs both: coherence
  to give the block a home, and [`override`](#override-when-the-overlap-is-deliberate) to take
  precedence over the block already covering it);
- **a type parameter is not a local type**, so `impl[T: Display] Display for []T` is refused however
  its bound is written. Making every printable slice printable is the library's job;
- **a local type in the trait's arguments also gives the block a home**, which is how a scalar can
  appear on the left of an operator. `impl Mul[Point, Point] for real` is written in the module that
  declares `Point`: `real` and `Mul` both belong to the library, and the argument list is what
  places the block in that module:

```sysl
struct Point
    x: real
    y: real

impl Mul[real] for Point
    mul(self, k: real) -> Point = Point(self.x * k, self.y * k)

impl Mul[Point, Point] for real
    mul(self, p: Point) -> Point = Point(p.x * self, p.y * self)

val p = Point(1, 2)
val a = p * 2
val b = 2 * p

print(a.x, a.y, b.x, b.y)
```

```output
2 4 2 4
```

The rule forbids the case with no home: **a foreign trait implemented for a foreign type**, where two
unrelated modules could each supply a different implementation and no rule could pick one. So
`impl Mul[real, real] for real` is refused in any module but the library's, since it names nothing
of its own in either place:

```sysl
impl Mul[real, real] for real
    mul(self, k: real) -> real = self

print(1)
```

```error
an 'impl' may be written only in the module that declares the trait or in one that declares a type the block names, and 'sysl.Mul' is the library's while nothing this block names is declared outside the library — so this one has no home
```

The built-in implementation is unaffected. `real` implements `Mul` regardless, and only the argument
list tells the two apart, so `2.0 * 3.0` remains the machine's multiply, and the block above applies
to `real` only for the argument pair it was written for.

An `impl` is part of its module's public surface. Adding, removing, or changing one is an interface
change visible to everything downstream — the same reasoning that puts implicit-resolution schemes
out of scope. See [modules](/reference/modules/).

## An `impl` covers a generic type as a whole

A block may declare **type parameters of its own**, written directly after the keyword:

```sysl
struct Box[T]
    v: T

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

**Its subject must be the type applied to the block's parameters and nothing else**: each argument a
single parameter, each parameter used once, and every parameter used:

```sysl
struct Box[T]
    v: T

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
would be a second implementation under a key that already holds one. Overlapping implementations,
and the specialization rule needed to choose between them, are deliberately left out of the
language.

The parameters are matched to the arguments **by position in the subject**, not by declaration
order, so `impl[X, Y] Show for Pair[Y, X]` means what it appears to.

### Conditional conformance

A bound on the block makes the conformance conditional. In the `Box` example above, a `Box[T]`
implements `Show` **exactly when** `T` implements `Display`, so `Box[int]` does and a `Box` of
something that cannot be displayed does not.

The condition is checked one level in, and it **composes**: under `impl[T: Show] Show for Box[T]`, a
`Box[Box[int]]` conforms exactly when `Box[int]` does, which makes a conditional implementation
usable on nested types.

```sysl
struct Box[T]
    v: T

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

Every check of conformance works the same way: a generic function's bound, an erasure to a trait
object, `print` looking for a `Display`, a call of the member itself. An instantiation that fails
the condition is refused at each of them, while other instantiations are not:

```sysl
struct Box[T]
    v: T

trait Show
    show(self) -> string

trait Mark
    m(self) -> int

impl[T: Mark] Show for Box[T]
    show(self) -> string = "b"

struct N
    n: int

print(Box(N(1)).show())
```

```error
'show' is a member the 'impl' of 'Show' gives 'Box[N]' only where its condition holds, and it asks 'Mark' of N, which does not implement it
```

Beyond deciding conformance, the bounds make **the members checkable at their definition**: a block
states what it assumes, so its bodies are checked once against those bounds alone, and a method
calling something no bound allows is reported on its own line before anything is instantiated.

### A shape is covered the same way

A composed type has no name to be generic over, but it has a **shape**, and a block with type
parameters may match the shape instead:

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

The rules above apply unchanged: the subject is the shape applied to the block's parameters and
nothing else, so `impl[T] Count for [][]T` is refused because the element is a shape rather than a
parameter, and so is a fixed element type:

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

Two rules are specific to shapes. **A composed type is recorded under its whole self** (`[]int`, not
`[]`), so a shape needs a key of its own, formed by dropping the arguments; a lookup that finds
nothing under the type's own key falls back to the shape's. And a shape with a type parameter keeps
the array's length written out: `[2]T` and `[3]T` are two shapes, each covering every element type
at its own length. (A block covering every length takes a value parameter, `[N]T`, as described on
[generics](/reference/generics/#what-it-is-for-a-fixed-array-renders).)

**A `string` is not covered by `[]T`.** It is a view of bytes that are valid UTF-8, and that
invariant is what distinguishes it from a `[]u8`; a block written for every slice says nothing about
it. `"hi".bytes` is a `[]const u8` and is covered, as the next rule explains.

#### Both views of a slice are one shape

`[]T` and `[]const T` are one type with a flag rather than two types
([`arrays.md`](/reference/arrays/#const-t-a-view-that-may-not-be-written)), and they share one
shape. A block written for either subject therefore reaches a receiver of either view:

```sysl
trait Count
    count(self) -> usize

impl[T] Count for []T
    count(self) -> usize = self.len

var xs = [1, 2, 3]
val view: []const int = xs[..]

print(view.count(), "hi".bytes.count())
```

```output
3 2
```

The block is written against what a slice *is*, a pointer and a count of `T`, and whether a given
slice may be written through is not part of that.

**The covering does not give a member a permission its receiver lacks.** The block is instantiated
**once per view**, so the instance a read-only receiver reaches has a `self` it may not write
through. A member that writes is refused, in its own body:

```sysl
trait Bump
    bump(self)

impl[T] Bump for []T
    bump(self)
        self[0] = self[0]

var xs = [1, 2, 3]
val view: []const int = xs[..]

view.bump()
```

```error
made real at the read-only view because that is what the receiver was
```

The refusal is reported on the line that writes rather than on the call, where a reader can act on
it. Because the block's own file wrote `[]T` and never wrote `const`, the message also names the
receiver that selected this instance. `bump` remains callable on a `[]int`:

```sysl
trait Bump
    bump(self)

impl[T] Bump for []T
    bump(self)
        self[0] = 9

var xs = [1, 2, 3]

xs[..].bump()

print(xs[0])
```

```output
9
```

**Write `[]const T` as the subject where the members only read.** Nothing requires it, and both forms
reach both views, but the subject is where a reader looks to see what a block does with its
receiver, and stating it there saves discovering it from a refusal.

**A shape and a written-out type overlap, and an unmarked overlap is refused.** Both blocks would
define the member for a `[]int`, so whichever is written second is refused, and the diagnostic names
the one already there:

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

Refusing is the right default: two overlapping blocks are usually a mistake, such as an accidental
duplicate or a block placed in the wrong module, and the refusal finds it.

### `override` — when the overlap is deliberate

An implementation may be marked **`override`**, and then it takes precedence:

```sysl
trait Show2
    show2(self) -> int

impl[T] Show2 for []T
    show2(self) -> int = 2

override impl Show2 for []int
    show2(self) -> int = 1

var xs: []int = [7, 8]
var ss: []string = ["a"]

print(xs.show2(), ss.show2())
```

```output
1 2
```

**The keyword goes on the overriding side, not the overridden one.** This is the opposite of C#'s
`virtual`/`override` pair and of Rust's unstable `default`, both of which make the general
implementation grant permission in advance. A library author cannot know which implementations
someone will need to replace; the intent belongs to the writer of the override, not the writer of
the original.

The keyword grants no permission; what it provides is the diagnostic. An unmarked second
implementation is still refused exactly as above.

**The overriding side is always a type written out in full.** A shape is one key and so is a generic
type's name, and the blocks that would sit *under* those are already refused: `impl[T] Show2 for
[][]T` matches a shape's argument by its shape, and `impl Show2 for Box[int]` fixes one instantiation
of a type whose blocks cover every instantiation. There is nothing more general for either of them
to replace, and marking one `override` is refused with that explanation:

```sysl
trait Show2
    show2(self) -> int

override impl[T] Show2 for []T
    show2(self) -> int = 2

print(1)
```

```error
'override' says this block replaces a more general one, and '[]T' is the general kind — an implementation for a shape or for a generic type covers every type it matches at once, so there is nothing below it. The override is written on the block for one type spelled out in full
```

**An `override` that overrides nothing is refused.** This is the check in the other direction, and it
matters when a library removes or narrows an implementation a program overrides: without it, the
override would silently become the only implementation while still claiming to replace one.

```sysl
trait Show2
    show2(self) -> int

override impl Show2 for []int
    show2(self) -> int = 1

print(1)
```

```error
'[]int' says 'override', but nothing else implements 'Show2' for it — an override replaces an implementation that covers the type more generally, and there is none to replace
```

**Coherence, not the keyword, keeps this sound.** The usual hazard of overriding is two method tables
for one type, such as a `[]Point` erased to a `*Show2` using one implementation at one site and the
other elsewhere. An `impl` may live only in the module declaring the trait or in one declaring a type
named in the subject, so a program cannot write `impl[T] Display for []T` at all, because `[]T` names
no type of its own. **The only override that can be written across a module boundary is one that
names the writer's own type**, so there is at most one per type and it lives with that type, and any
site that can write `[]Point` already depends on the module declaring `Point`. One type has one
table.

The remaining cost is that a library cannot rely on its own implementations being the ones in use.
`override` makes every such site searchable rather than invisible.

## The compiler writes four of them

Four traits follow directly from structure. `Eq` on a product compares its fields one by one; `Ord`
makes those comparisons in declaration order; `Hash` mixes the fields; `Display` writes the name and
the fields. Writing them by hand is mechanical and fails quietly: a field added to a struct and
forgotten in its `eq` is silently left out of the comparison.

A **`derives` clause** on a `struct` or an `enum` asks for them:

```sysl
struct Size derives Eq, Ord, Hash, Display
    w: int
    h: int

val a = Size(3, 4)

print(a == Size(3, 4), a < Size(3, 5), a)
```

```output
true true Size(3, 4)
```

The clause goes after the name and its type parameters, before the body. It produces an ordinary
`impl` block, the one a person would have written, so a derived implementation is found, checked,
dispatched and erased exactly as a written one is, and a field that does not support the trait is
reported in the ordinary way.

**Each of the four is written by the library**, as the trait's own
[`derive` member](#a-trait-says-how-it-is-derived): the same mechanism a program's own trait uses, so
the compiler treats the four specially only in what the clause refuses up front. The derived
`Display` writes the name and each part directly to the destination; where a width is requested, it
first renders into a [`Counting`](/library/core/#counting-the-width-without-the-bytes) sink to learn
the width, pads once, and then renders for real. A derived rendering therefore
[builds no string](/library/core/#a-specifier-is-the-whole-value-s-field), and an ordinary `print` of
one takes a single pass. A shape with no parts, such as a fieldless struct or an enum whose variants
carry nothing, renders as its name.

The library provides the same four for
[every tuple](/reference/types/#what-the-library-gives-every-tuple), and a derived block walks a
named product by the same rules as a tuple, so a `Size` and an `(int, int)` compare and order alike,
and render alike except for the name in front. Any other trait is derived by **its own `derive`
members**, described below.

### A trait says how it is derived

A trait may carry a **`derive` member**: `derive[T] name(self: T, …)`, with a body written over the
structure of `T` using [reflection](/reference/reflection/): `T::Kind`, `for const f in T::Fields`,
`self.(f)`. A `derives` clause naming the trait instantiates each `derive` member with `T` standing
for the type, producing the `impl` block a person would have written:

```sysl
@annotation
struct rename
    name: string

trait Json
    encode(self, w: *Writer)

    derive[T] encode(self: T, w: *Writer)
        if const T::Kind == Kind.Struct
            w.write("{".bytes)

            for const f in T::Fields
                if f.index > 0 then w.write(",".bytes)

                val key = f::Annotation[rename].map(r -> r.name).unwrap_or(f.name)

                w.write(s"\"$key\":".bytes)
                self.(f).encode(w)

            w.write("}".bytes)
        else
            @assert(false, "Json derives for a struct")

impl Json for int
    encode(self, w: *Writer) = w.write(s"$self".bytes)

impl Json for string
    encode(self, w: *Writer) = w.write(s"\"$self\"".bytes)

struct User derives Json
    @rename("id") user_id: int
    name: string

User(7, "ann").encode(stdout())
print("")
```

```output
{"id":7,"name":"ann"}
```

`User` gets an `impl Json for User` whose body is two unrolled field writes. The body is resolved in
the trait's file, under the trait's imports, and it is a **template rather than a default**: a block
written by hand inherits nothing from it. A generic type derives at each instance (`struct Box[T]
derives Json` requires `T: Json` of each), and a data enum is walked with
`for const c in T::Variants`.

**The copy is checked once per field, and an error is reported at the `derives`**: a field whose
type does not support what the body asks is reported against the deriving type, where it requested
the derivation, naming the field. A call in the body to a member the trait declares means **that
trait's** member, as a bound `T: Json` would: a field whose type has a member of the same name from
another trait is still refused as not implementing `Json`, and is never passed to the other trait's
member. A member of a trait the derived one requires is reached the same way, through the trait
that declares it.

```sysl
trait Json
    to_json(self) -> string

    derive[T] to_json(self: T) -> string
        var out = ""

        for const f in T::Fields
            out = out + self.(f).to_json()

        out

impl Json for int
    to_json(self) -> string = s"$self"

struct Pin
    on: bool

struct Lamp derives Json
    level: int
    pin: Pin

print(Lamp(1, Pin(true)).to_json())
```

```error
'Pin' does not implement 'Json': method 'to_json' is missing (in the copy for field 'Lamp.pin')
```

**A derivation reaches the type's private fields.** `self.(f)` on a private field is otherwise
refused outside the type's own file; naming the trait in `derives` is the type author's opt-in.

A trait with no `derive` member cannot be derived, and is implemented with an `impl` block:

```sysl
trait Show
    show(self) -> string

struct P derives Show
    x: int
```

```error
'Show' is not a trait the compiler knows how to write, and it carries no 'derive' member
```

### Order is declaration order

`Ord` is lexicographic, first field first: the only ordering a named product can claim, and the one
a tuple has. That makes the order of the fields part of the type's contract, as it already is for
layout, since a struct's fields are laid out in the order they are written.

```sysl
struct Version derives Ord, Display
    major: int
    minor: int

print(Version(1, 9) < Version(2, 0), Version(2, 0) < Version(1, 9))
```

```output
true false
```

**A type that orders by a particular field should implement `Ord` by hand rather than derive it.**
That is the case the clause does not cover.

### A generic type derives conditionally

Every type parameter gains the derived trait as a bound, which is
[conditional conformance](#conditional-conformance) written for you: a `Box[int]` is `Eq` and a `Box`
of something without `Eq` is not, with nothing further to write.

```sysl
struct Box[T] derives Eq, Display
    v: T

print(Box(1) == Box(1), Box(1) == Box(2), Box("x"))
```

```output
true false Box(x)
```

A `const` value parameter gains no bound, since it is not a type, and the type's own bounds are kept,
since `struct Sorted[T: Ord]` is only a valid type where `T` is `Ord`.

### An enum takes the clause too

A variant renders under **its own** name, as it is written. Comparison is by variant first, in
declaration order, and then field by field within the variant.

```sysl
enum Shape derives Eq, Ord, Display
    Circle(r: int)
    Rect(w: int, h: int)
    Empty

print(Circle(2), Rect(3, 4), Empty)
print(Circle(9) < Rect(0, 0), Rect(1, 5) < Rect(1, 6), Circle(1) == Circle(1))
```

```output
Circle(2) Rect(3, 4) Empty
true true true
```

The hash mixes the variant before the payload, so two variants carrying equal payloads hash
differently instead of colliding in a table that holds both.

A **simple** enum, one where no variant carries a payload, is already `Eq`, because its value *is*
its discriminant. The clause reports that directly rather than producing a block that is refused
later:

```sysl
enum Colour derives Eq
    Red
    Green
```

```error
a simple enum is already 'Eq'
```

Its `Ord` is its discriminants' order, so a simple enum still has three worth deriving:

```sysl
enum Colour derives Ord, Hash, Display
    Red
    Green
    Blue

print(Red < Green, Blue < Green, Green)
```

```output
true false Green
```

### What the clause deliberately cannot say

It takes no `override`. Nothing in the library covers a type you declared, so there is no more
general block for a derived one to take precedence over; where one is needed, an
[`override impl`](#override-when-the-overlap-is-deliberate) is written by hand, and the two forms do
not need to combine.

Derivation is **all or nothing per trait**. A derived block cannot have one of its methods replaced,
and a derived block beside a hand-written one for the same trait is a duplicate implementation.

It also does not apply to a type whose fields it cannot see. An `opaque struct` with no body is C's
incomplete type: the storage belongs to whoever allocated it, and its shape is unknown here. A
derived `Eq` over no fields would answer `true` for every pair without any basis:

```sysl
opaque struct Handle derives Eq
```

```error
opaque and declares no fields
```

This is the only case where visibility matters, and it concerns a layout that is absent rather than
one that is hidden. The clause is part of the declaration, so the block it writes is in the module
that declares the type: a **private field is walked**, because the block is written where the type
is.

## Every type has a `Debug`

`Debug` is the library's other rendering trait, with the same shape as `Display` (a sink and the
field the whole value occupies), and it is the one exception to
[conformance being explicit](#conformance-is-explicit-always): **every type has one, and none has to
be written.** The compiler supplies a `Debug` for every struct and enum that does not write its own,
as a `derives` clause would but without being asked: the name, then each field by name, each shown
through **its own** `Debug`, so a string inside a structure is quoted and escaped. An enum's variant
is shown under the enum's name.

```sysl
struct Point
    x: int
    y: int

enum Color
    Red
    Green

struct Label
    text: string
    at: Point
    color: Color

print(Label("a \"b\"", Point(1, 2), Red))
```

```output
Label(text: "a \"b\"", at: Point(x: 1, y: 2), color: Color.Red)
```

The library writes the rest: the scalars (a `char` in single quotes, a `string` in double quotes),
tuples, arrays, slices, `Buf`, `Option` and `Result`, each shown as its `Display` shows it but with
the parts through their `Debug`. So `print` of a value with neither block written still prints:
**`print`, `str` and an interpolation hole use `Display` where a value has one and `Debug` where it
does not**, and `f"${x}%?"` and `debug(x)` always use `Debug`
([strings](/reference/strings/#format-specifiers)). `assert_eq` reports both sides through it, so
`got "1", want 1` cannot be mistaken for equal values.

**An `impl Debug` of your own replaces the supplied one**, for example to hide a field or abbreviate
a large buffer, and a structure holding the type shows it through that block too:

```sysl
struct Secret
    key: string

impl Debug for Secret
    debug(self, out: *Writer, fmt: FormatSpec) = display_pad("Secret(***)".bytes, out, fmt)

struct Login
    user: string
    secret: Secret

print(Login("ed", Secret("9f2a71c4")))
```

```output
Login(user: "ed", secret: Secret(***))
```

A generic type's supplied block is conditional, as a derived one is, and every type meets the
condition, so a `T: Debug` bound places no burden on a caller:

```sysl
show[T: Debug](x: T) = print(f"${x}%?")

struct Box[T]
    v: T

show(Box("in"))
show(3)
```

```output
Box(v: "in")
3
```

**A type's own `debug` is unaffected by the supplied one.** A field, a variant or a member named
`debug` keeps the name on the type, and `%?`, `debug(x)` and a `T: Debug` bound still use the
supplied rendering:

```sysl
struct Options
    title: string
    debug: bool

impl Options
    describe(self) -> string = if self.debug then "verbose" else "quiet"

var o = Options("run", false)
o.debug = true
print(o.debug, o.describe())
print(f"${o}%?")
```

```output
true verbose
Options(title: "run", debug: true)
```

An `impl Debug` *written* for such a type is refused, as is any trait member that shares a field's
name:

```sysl
struct Flags
    debug: bool

impl Debug for Flags
    debug(self, out: *Writer, fmt: FormatSpec) = display_pad("Flags".bytes, out, fmt)
```

```error
type 'Flags' has both a field and a member named 'debug'
```

### What is shown by rule

A few values have no block that could be written for them, and are shown by a fixed rule:

- **a raw pointer `*T` shows its address**, in lower-case hex with no padding, and the null pointer
  as `0x0`. It is not followed, since what it points at may be gone. *This difference is
  deliberate*: a member call sees through one `*` (above), so in generic code a `T: Debug`
  instantiated at `*P` uses `P`'s `Debug` (the bound is met by what the pointer points at), while
  `print(p)`, `f"${p}%?"` and a field of type `*P` show the address;
- **a reference `&T` is followed**, since it is a value, and shows as its referent;
- **a callable shows as `<fn>`**, a `weak T` as `weak(…)` without following it, `unit` as `()` and a
  task as `<task>`;
- **device memory is shown by its type and never read**: a `volatile` field, or an array or a view
  of them, inside a structure shows as `<volatile u32>` and the like, since reading a register can
  have side effects;
- **an `opaque struct` gets no supplied `Debug`**, since its layout belongs to its own module. It is
  held through a pointer, which shows its address; one shown by value where its layout is private
  shows as its type in angle brackets, as does any value whose type has no `Debug` at all;
- **a trait object shows its concrete value**: every object's table carries a `Debug` slot after its
  own members, whether or not the trait requires `Debug`, so `print` of a `*Shape` uses the `Debug`
  of the `Rect` behind it. Two kinds of table carry none: a callable's, shown as `<fn>`, and a
  `Writer`'s, shown as `<writer>`. A sink is where a rendering goes rather than a value to show, and
  a slot in every writer's table would pull a `Debug` for every sink into every program that writes
  to one. The slot costs one word per table, and keeps the concrete type's `Debug` in the program
  wherever the table is.

**A sink that should show itself says so in its trait**: a trait requiring `Debug` beside `Writer`
restores the slot, so its objects print the concrete sink while a bare `&Writer` stays `<writer>`.
A concrete writer that is never erased prints through its `Debug` like any other value.

```sysl
struct Tally
    n: usize

impl Fallible for Tally

impl Writer for Tally
    write(*self, bytes: []const u8)
        self.n = self.n + bytes.len

trait LoggedWriter: Writer + Debug

impl LoggedWriter for Tally

val w: &Writer = Tally(1)
val l: &LoggedWriter = Tally(2)

print(w, l, Tally(3))
```

```output
<writer> Tally(n: 2) Tally(n: 3)
```

**Printing a placeholder directly produces a warning**, and still builds. A value whose whole printed
form is `<writer>`, `<fn>`, `<task>` or a `<Type>` with no `Debug`, passed directly to `print`,
`str`, an interpolation or `format` hole, `debug(x)`, or an argument meeting a `T: Debug` bound such
as `assert_eq`'s, was almost certainly meant to show something. The `print(w)` above reports:

```
warning: printing 'w' shows only '<writer>': a Writer object carries no Debug; print the concrete
sink, or ask for 'Writer + Debug'
```

A placeholder inside a structure (a field, an element, an option's payload) is part of a larger
rendering and never produces the warning, and an object whose trait requires `Debug` has nothing to
warn about. There is no switch to silence the warning; applying the fix it names removes it.

**A rendering that nests deeper than thirty-two levels writes `…` and stops.** Following references
lets a list or a tree show in full, but would also make a structure that refers back to itself
render forever; the limit makes every value safe to show.

```sysl
struct Node
    v: int
    next: Option[&Node]

val list: &Node = Node(1, Some(Node(2, None)))

print(list)
```

```output
Node(v: 1, next: Some(Node(v: 2, next: None)))
```

## A trait may take type parameters

A trait declares parameters in the same bracketed list as every other generic declaration, and an
implementation states which arguments it supplies:

```sysl
trait Sink[T]
    put(*self, x: T) -> int

struct Buffer
    n: int

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

`Sink` is not one trait but a family of them: `Sink[int]` and `Sink[string]` say different things,
and the implementation states which one a `Buffer` satisfies. This lets a trait describe a
**relation between two types** rather than a property of one: what a sink accepts, what a conversion
converts from, what an iterator yields.

The arguments are written the same way in all three positions where a trait is named, and mean the
same thing in each:

| position | written |
|---|---|
| a bound | `f[X: Sink[int]](x: X)`, and the body's `x.put(…)` then takes an `int` |
| an implementation | `impl Sink[int] for Buffer` |
| a trait object | `&Sink[int]` |

A trait's own parameters carry bounds too (`trait Get[T: Show]`), and every application of the trait
must satisfy them, exactly as with a bounded struct.

**A parameter may carry a default**, and `Self` is its main use: in `trait Scale[R = Self]`, `R` is
the operand type, usually the implementing type, so `impl Scale for P` means `impl Scale[P] for P`
and `[T: Scale]` asks for `Scale[T]`. See [generics](/reference/generics/) for how defaults are
filled.

### One implementation per argument list

A type may implement a trait **once for each argument list**, and the argument list distinguishes
two implementations:

```sysl
trait Sink[T]
    put(self, x: T) -> string

struct Buf
    tag: string

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

A second `impl Sink[int] for Buf` beside those is refused as a duplicate.

This is the one qualification of the rule that a trait's members become the type's and that a
type's members share one namespace, and it is narrow. A type implementing both `From[int]` and
`From[real]` has two members called `from`, and the **argument list** decides which one a use means.
Every way of reaching a member already carries one: an operator has its pair of operands, a bound
names the arguments, a trait object is formed at written arguments, and a named call passes values
whose types are the arguments.

**The resolution is exact, not ranked.** A call is answered by the one implementation whose
parameters are the types of the arguments, and a call matching none of them, or more than one, is
reported rather than resolved. So `c.mul(2)`, where the candidates take a `Complex` and a `real`,
calls the `real` one, because an integer literal can be read as a `real` and cannot be a `Complex`.
Where the candidates take an `f32` and a `real`, a bare literal fits both and takes its default float
type, `real`. Beyond that nothing ranks candidates: picking the nearest one would be a specialization
rule, which the language does not have.

Two limits follow from distinguishing several implementations inside one namespace:

- **a property has no arguments**, so when two implementations both supply one there is nothing to
  select with, and reading it is refused;
- **a shape and a type of that shape** are recorded under two different owner keys, and a member
  lookup uses one or the other, never both, so a second implementation split across that boundary
  could not be reached by any call.

**A generic block may write its own parameter as a trait argument**: `impl[T] Index[usize, T] for
Buf[T]` says a `Buf[int]` implements `Index[usize, int]` and nothing else. This lets a container
carry the type it holds in the trait it implements, without an
[associated type](#a-trait-may-declare-an-associated-type), and it is the right tool when the
*container's* own parameters already name that type. The block's parameters are exactly the
arguments of the type it is written for, so an argument built from them means one thing per
instantiation, and the subject settles which. That includes an argument that is the subject itself,
which is how [an operator carries a result that is not its operands'
type](/reference/expressions/#at-a-generic-subject).

**A generic block may not write an argument fixed to one instantiation of its own subject.** Where
the trait's default names the type being asked about, such an argument coincides with the defaulted
block at that one instantiation and differs from it at every other, which would be a choice between
implementations rather than a lookup:

```sysl
struct Box[T]
    v: T

impl[T] Mul[Box[int]] for Box[T]
    mul(self, rhs: Box[int]) -> Box[T] = self

print(1)
```

```error
whose arguments default names the type it is written for
```

## A trait may declare an associated type

A trait parameter is written where the trait is **applied**. An associated type is written by the
**implementation**, and everything else reads it from the type:

```sysl
trait Render
    render(self) -> string

trait Seq
    type Item: Render
    head(self) -> Self::Item

struct Words
    first: string

impl Render for string
    render(self) -> string = self

impl Seq for Words
    type Item = string
    head(self) -> Self::Item = self.first

show[S: Seq](s: S) -> string = s.head().render()

print(show(Words("hi")))
```

```output
hi
```

`type Item: Render` declares the associated type and what the type filling it must implement.
`type Item = string` fills it. `Self::Item` is the **projection**: the same `::` that reads
[`int::Max`](/reference/attributes/) in expression position, used in type position. `show`'s body
may call `render` on what it gets because the trait requires that of `Item`, and nothing more.

That is the difference from `trait Sink[T]`, and it has three consequences:

| | a trait parameter | an associated type |
|---|---|---|
| who writes the argument | whoever names the trait | the implementation |
| how many per type | one implementation **per argument list** | **one**, and the subject settles it |
| how a use names it | `Sink[int]`, everywhere the trait is named | `T::Item`, off the type |

A trait may declare both. The parameters go in the bracketed list and the associated types among the
members, and the two do not interfere.

### The projection normalizes as soon as its subject is concrete

`Self::Item` inside a generic body is **abstract**: it stands for a type not yet chosen, and is
allowed exactly what the trait's bound on it provides. On a known type it is the type the
implementation chose, and may be written wherever a type may:

```sysl
trait Render
    render(self) -> string

trait Seq
    type Item: Render
    head(self) -> Self::Item

struct Nums
    first: int

impl Render for int
    render(self) -> string = "an int"

impl Seq for Nums
    type Item = int
    head(self) -> Self::Item = self.first

val n: Nums::Item = 41

print(n, n.render())
```

```output
41 an int
```

**The type is deliberately not hidden.** Swift's `some View` conceals the type because Swift offers
no way to write it; `Nums::Item` is a way to write it, so concealing it would add a restriction
rather than an abstraction. A generic caller still sees only the bound, which is where the
abstraction matters, and the projection being abstract there provides it.

### A generic block answers with one type per instantiation

The block's own parameters may appear in the associated type, exactly as they may in a trait
argument. Each instantiation of the subject then settles it:

```sysl
trait Render
    render(self) -> string

trait Seq
    type Item: Render
    head(self) -> Self::Item

struct Cell[T]
    v: T

impl[T: Render] Render for Cell[T]
    render(self) -> string = "[" + self.v.render() + "]"

impl Render for int
    render(self) -> string = "i"

impl Render for string
    render(self) -> string = self

struct Box[T]
    v: T

impl[T: Render] Seq for Box[T]
    type Item = Cell[T]
    head(self) -> Self::Item = Cell(self.v)

show[S: Seq](s: S) -> string = s.head().render()

print(show(Box(3)), show(Box("x")))
```

```output
[i] [x]
```

`Box[int]::Item` is `Cell[int]` and `Box[string]::Item` is `Cell[string]`. Monomorphization keeps the
concrete type throughout, so this has no run-time cost: there is no box per value and no indirect
call.

### A default's callable may name the projection

A [default body](#a-default-may-assume-exactly-what-its-own-trait-declares) is checked once at the
trait, where `Self` is a parameter, and copied per implementing type, where `Self` is that type. A
[bare arrow](/reference/generics/#bounds) is shorthand for a bound rather than a parameter type, so a
default taking one over `Self::Item` uses the projection where neither type is written out, and each
copy binds it to what its own subject chose:

```sysl
trait Mapper
    type Item

    first(self) -> Self::Item
    over[N](self, f: Self::Item -> N) -> N = f(self.first())

struct One
    v: int

impl Mapper for One
    type Item = int
    first(self) -> int = self.v

struct Word
    w: string

impl Mapper for Word
    type Item = string
    first(self) -> string = self.w

print(One(20).over(n -> n + 1))
print(Word("hello").over(s -> s.len))
```

```output
21
5
```

`n` is an `int` and `s` is a `string`, from one body that names neither. The arrow has no cost at the
call, since it monomorphizes and puts no closure on the heap. It is the member's own type parameter
`N`, not the projection, that keeps `over`
[out of the table](#a-member-with-its-own-type-parameters-leaves-the-table-not-the-trait).

### `some Trait` — the result read off the body

The type an implementation chooses is often one nobody wants to write. A tree of nested containers is
the typical case: the type is large, it is an implementation detail, and it changes whenever the
body does. `some Trait` in an `impl` member's result says *"the associated type is whatever this
body produces, and it implements this trait"*:

```sysl
trait Render
    render(self) -> string

trait View
    type Body: Render
    body -> Self::Body

struct Text
    s: string

impl Render for Text
    render(self) -> string = self.s

struct Counter
    n: int

impl View for Counter
    body -> some Render = Text("count")

draw[V: View](v: V) -> string = v.body.render()

print(draw(Counter(7)))
```

```output
count
```

No `type Body = …` line is written; the body supplies the type. `Counter::Body` is `Text`, so the two
forms are the same feature: one writes the type, the other reads it from the body.

**`some` is a contextual keyword**, special only in front of a bound in a result position. A program
may still name a variable, a parameter or a function `some`.

**Every path out of the member must produce one type.** Two branches yielding different concrete
types are an error, not a silent widening to the bound: the bound is what a *caller* may rely on, not
what the member returns. No separate rule is needed, since an `if` used as a value must already have
matching branches, and that rule reports it.

```sysl
trait Render
    render(self) -> string

trait Seq
    type Item: Render
    head(self) -> Self::Item

struct A
    n: int

struct B
    n: int

impl Render for A
    render(self) -> string = "a"

impl Render for B
    render(self) -> string = "b"

struct Box
    flag: bool

impl Seq for Box
    head(self) -> some Render = if self.flag then A(1) else B(2)

print(1)
```

```error
if branches have different types: A and B
```

**The type may be one no other file can name.** A `private` struct supplied through a `some` result
works and is opaque outside the file that declares it: a caller reaching it through a bound can do
what the bound allows and has no way to write the type. Swift's `some View` achieves this by
concealment; sysl achieves it with ordinary visibility.

**`some` is allowed only in that position.** A free function has no trait whose associated type it
could supply, so the refusal says where `some` belongs:

```sysl
trait Render
    render(self) -> string

f(x: int) -> some Render = x

print(1)
```

```error
'some Render' says the type is read off the body of a member that supplies a trait's associated type, so it stands only in an 'impl' block
```

The bound is checked against what the body actually produces:

```sysl
trait Render
    render(self) -> string

trait Seq
    type Item
    head(self) -> Self::Item

struct Box
    v: int

impl Seq for Box
    head(self) -> some Render = self.v

print(1)
```

```error
'head' promises 'some Render' and its body yields int, which does not implement 'Render'
```

### What the implementation is held to

A written binding is checked against the trait's bound in the same way:

```sysl
trait Render
    render(self) -> string

trait Seq
    type Item: Render
    head(self) -> Self::Item

struct Box
    v: int

impl Seq for Box
    type Item = int
    head(self) -> Self::Item = self.v

print(1)
```

```error
trait 'Seq' asks that its associated type 'Item' implement 'Render', and int does not
```

An implementation that supplies no associated type has not implemented the trait, exactly as with a
missing method:

```sysl
trait Seq
    type Item
    head(self) -> Self::Item

struct Box
    v: int

impl Seq for Box
    head(self) -> Self::Item = self.v

print(1)
```

```error
'Box' does not implement 'Seq': the associated type 'Item' is missing
```

**`Self` inside such a bound is the associated type, not the type implementing the trait.** A bound
constrains what it is written on, and `type W: Add` is written on `W`, so the defaults of
[`Add`](/reference/expressions/), which are `Self`, are filled from `W`. `type W: Add` on an
implementation supplying `type W = u32` therefore asks whether `u32` is `Add[u32, u32]`, which it
is, and not whether it can be added to the implementing type:

```sysl
trait Holder
    type W: Add
    one(self) -> Self::W

struct N
    v: u32

impl Holder for N
    type W = u32
    one(self) -> u32 = self.v

sum[H: Holder](h: H) -> H::W = h.one() + h.one()

print(sum(N(21)))
```

```output
42
```

This is how `Self` reads in a bound everywhere; it is stated here because an associated type is the
one place where two types are in play and either would be a plausible reading.

**The bound is checked once every `impl` in the program is registered**, so the block that makes the
supplied type implement the bound may be written below the block that chose it. A
[required trait](#a-trait-may-require-another-trait) follows the same rule, for the same reason.

### One name per type, because a projection does not name its trait

`Box::Item` names a *type* and a *name*, never a trait. So a type may implement at most one trait
declaring an associated type of a given name, and the second block is refused:

```sysl
trait A
    type Item
    a(self) -> Self::Item

trait B
    type Item
    b(self) -> Self::Item

struct Box
    v: int

impl A for Box
    type Item = int
    a(self) -> Self::Item = self.v

impl B for Box
    type Item = bool
    b(self) -> Self::Item = true

print(1)
```

```error
one type cannot have two of one name
```

Rust writes the qualified form `<T as A>::Item`; sysl has no such form, so the collision is refused
rather than left to be disambiguated at every use.

### Declaring one spends the trait's erasability, unless the object says which type it is

An erased value does not know which type it is, and an associated type depends on exactly that, so
the slot would have a different signature for every implementing type. A bare `&Seq` therefore
cannot be formed, and the refusal names the associated type rather than leaving it to surface as a
`Self`:

```sysl
trait Seq
    type Item
    head(self) -> Self::Item

look(s: &Seq) -> int = 0

print(1)
```

```error
'Seq' declares the associated type 'Item', whose meaning is the implementing type's — an erased value has forgotten which type that is, so there is no '&Seq' to form
```

**A bound keeps the type, and with it the associated type.** `[S: Seq]` is the intended use of such a
trait, as it is for [the operator catalog](#object-safety), for a related reason. A trait that
*requires* such a trait cannot be erased either, and the diagnostic names the trait the associated
type comes from.

### An object may fix the associated type

The alternative is to write the type out. `&Seq[Item = int]` is a value of some unknown type **whose
`Item` is known to be `int`**, so every slot has one signature again, and there is a table to point
at:

```sysl
trait Seq
    type Item
    head(self) -> Self::Item

struct Box
    v: int

impl Seq for Box
    type Item = int
    head(self) -> Self::Item = self.v

show(s: &Seq[Item = int])
    print(s.head())

show(Box(7))
```

```output
7
```

**Where a trait has no parameters of its own and exactly one associated type, the name may be left
off.** `&Seq[int]` and `&Seq[Item = int]` are then the same type, not two types with a conversion
between them, so a function declared with either accepts a value of the other:

```sysl
trait Seq
    type Item
    head(self) -> Self::Item

struct Box
    v: int

impl Seq for Box
    type Item = int
    head(self) -> Self::Item = self.v

named(s: &Seq[Item = int]) -> int = s.head()
bare(s: &Seq[int]) -> int = named(s)

print(bare(Box(4)))
```

```output
4
```

The short form is not available where it would be ambiguous. A trait with parameters **and** an
associated type takes its arguments in order, first, and names the associated type after them
(`&Keyed[int, Item = string]`), because a bare argument there means one of the trait's own:

```sysl
trait Keyed[K]
    type Item
    at(self, k: K) -> Self::Item

struct Row
    v: int

impl Keyed[int] for Row
    type Item = int
    at(self, k: int) -> Self::Item = self.v + k

show(s: &Keyed[int, Item = int])
    print(s.at(2))

show(Row(5))
```

```output
7
```

**The value put into the object must have chosen the same type.** This makes the binding sound:
every slot's signature was read under the object's binding, so a value whose implementation chose
otherwise would be called through a table with the wrong types. It is refused at the erasure, the
one place where both are known:

```sysl
trait Seq
    type Item
    head(self) -> Self::Item

struct Box
    v: int

impl Seq for Box
    type Item = int
    head(self) -> Self::Item = self.v

show(s: &Seq[Item = string])
    print(s.head())

show(Box(7))
```

```error
a &Seq[Item = string] says 'Item' is string, and Box supplies int for it — an object fixes the associated type, so only a type that chose the same one goes into it
```

A binding is checked against whatever the trait requires of the associated type, exactly as an
implementation is. A required trait's associated type is bound in the same brackets, under its
declared name, since its members are slots in the same table.

**A bound has no such form.** `[S: Seq[Item = int]]` cannot be written, and nothing bounds a
projection either, so a generic body may call what a bound allows and may assume nothing about the
result beyond what the trait itself requires of the type. Where a body needs more, the requirement
belongs on the trait's own declaration: `type Item: Display`.

## A trait may require another trait

`trait Word: Add + BitXor` is written after the name, with the same `:` and `+` a bound uses,
because it asks the same thing of the implementing type.

```sysl
trait Named
    label(self) -> string

trait Describe: Named
    describe(self) -> string = "device " + self.label()

struct P
    n: string

impl Named for P
    label(self) -> string = self.n

impl Describe for P

var p = P("P-101")

print(p.describe())
```

```output
device P-101
```

A required trait is stated once by the **trait** rather than repeated by each declaration that uses
it. `[T: Describe]` then allows `label`; a **default body** in `Describe` may use it, since a default
may assume exactly what its trait provides; and a `&Describe` object carries the required trait's
members in its table.

**The requirement is checked at the `impl`, not at the bound**, so the diagnostic lands on the
declaration that fails to meet it:

```sysl
trait Named
    label(self) -> string

trait Describe: Named
    describe(self) -> string = "device"

struct P
    n: string

impl Describe for P

print(1)
```

```error
'Describe' requires 'Named', so 'P' has to implement that too — write 'impl Named for P'
```

Checking at the `impl` also keeps conformance a plain lookup: by the time anything asks whether a
type implements a required trait, an implementation of it is already registered. The check waits
until every `impl` has been seen, since the block supplying a required trait may be written below
the one that needs it.

### The table carries the required trait's slots

A trait's members are the required traits' members, depth-first with each trait taken once, followed
by its own. Both the table and the call sites that index into it are laid out from that one list, so
**a required trait's method is one indirect call**, exactly like the trait's own. The alternative, a
word in the table pointing at the required trait's own table, would cost a second load on every such
call and would only enable an upcast, which sysl does not have.

So **a `&Sub` cannot become a `&Super`**; that is the deliberate cost of this layout. No program is
prevented by it: what a program does with a required trait is call its members, which works.

**The diamond needs no rule of its own.** `D: A + C` with both `A: B` and `C: B` carries `B`'s members
once, because the walk takes each trait the first time it reaches it. What *is* refused is two traits
in one closure declaring a member of the same name, because of the **table**, not the namespace. Two
unrelated traits may each give one type a member of the same name, because a call selects one by
naming the trait; two traits inside one requirement closure are laid out as one table, and a call
through a `&Sub` has nothing left that could say which slot it meant.

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

Two more rules apply. A trait may not require one that is **less visible** than itself, since
implementing the trait means implementing the required one, and a requirement the implementer cannot
name would make the trait impossible to implement from outside. And `Self` in a requirement's
arguments is the type implementing the requiring trait, so `trait Vector: Scale[Self]` asks that
whatever implements `Vector` can be scaled by its own type, which is the same requirement
`trait Vector: Scale` states when `Scale` defaults its parameter to `Self`.

## Trait objects

A trait object is a **fat pointer**: two words, the method table for the erased type and the value
itself:

```
{ ptr vtable, ptr data }
```

The sigil says who owns the second word; nothing else differs between the two:

| written | the data word is | who frees it |
|---|---|---|
| `*Trait` | the value's own address | nobody — raw and unmanaged, like every `*T` |
| `&Trait` | the reference-counted **box** the value sits in | ARC, exactly as for the `&T` it was erased from |

`*T` and `&T` on a *concrete* type are thin pointers; on a *trait* they are fat. Naming a trait is
what makes them fat, which is why there is no `dyn`.

**There is one table per (trait, type, sigil)**, two variants rather than one because the data word
means different things: an entry has to reach a receiver, and from a box that is one step further
in than from a bare value. Where the data word already *is* the receiver an implementation declares,
the entry names that implementation directly; otherwise it names a small adapter that steps over the
box header, loads the value, or both. The common case costs one indirect call and nothing more.

**A table is emitted only where a function the program reaches erases a value into it**, together
with the functions it points at. The standard library's own tables follow the same rule: a library
body that creates an object but is never reached keeps neither the table nor the functions it names,
so a board image that never creates a `Buf` carries no `malloc` because of `Buf`'s `Writer` table.
Erasing a library type in the program's own code keeps that type's table:

```sysl
main()
    var c = Counting(0)
    val w: *Writer = &c

    w.write("abc".bytes)
    print(c.n)
```

```output
3
```

### Object safety

Erasure discards the type, so no member may depend on knowing it. A trait can be made into an object
when it declares no [associated type](#a-trait-may-declare-an-associated-type) (an associated type
depends on exactly the erased type) and when every member:

- **has a receiver.** An associated function has nothing to dispatch on. A property does have one, by
  value and unwritten, so a trait requiring a property is as safe to erase as one requiring a
  method.
- **mentions `Self` only in that receiver.** A second `Self` would have to be the *same* erased type
  as the first, which is exactly what an object does not record, and a `Self` result has no known
  size to return.
- **does not take `&self`, for a `*Trait` only.** `&self` requires its receiver inside a box, and a
  raw object points directly at a value. A `&Trait` carries a box, so it accepts such a member.
- **takes no `...`.** A call to a variadic names the callee's *whole* function type, because that is
  how it marks where the declared parameters stop and the variable tail begins, and a table slot is
  one word and names no type.

```sysl
trait Scale
    scale(self, k: int) -> Self

struct P
    v: int

impl Scale for P
    scale(self, k: int) -> P = P(self.v * k)

grow(s: &Scale) -> int = 0

print(1)
```

```error
'scale' of 'Scale' mentions 'Self' away from its receiver, and an erased value has forgotten which type that is — so there is no '&Scale' to form
```

The second rule excludes **every trait in the operator catalog**, starting with
`add(self, rhs: Self) -> Self`. This is correct rather than a limitation: those traits describe an
operator over two values of one type, which concerns types known at compile time. They are meant for
bounds.

**A trait that requires an unerasable trait is itself unerasable**, and the diagnostic names the
trait the offending member came from. A **built-in** type that satisfies a requirement by the
compiler's rule cannot be erased through it either, since a table holds function pointers and a
scalar's `add` is an instruction.

**This affects only the operator catalog at written arguments.** `Add[int, int]` declares
`add(self, rhs: int) -> int`, with no `Self`, so it is a valid object type, and an `int` implements
it by the compiler's rule. `&Add[int, int] = 3` is therefore refused, and the diagnostic explains
why rather than reporting a plain mismatch.

**`Display` and `Hash` are not affected**: every built-in type implements both through an `impl`, so
a `*Display` holds an `int`, a `u256`, a `string` or a float alike, a `&Hash` holds anything that
hashes, and a heterogeneous array of either is ordinary code. The rest of the catalog (`Eq`, `Ord`,
`Bits`, `Signed`) mentions `Self` outside the receiver, so object safety refuses the *type* before a
value is involved.

#### A member with its own type parameters leaves the table, not the trait

The four rules above decide whether a trait has an object type **at all**. A member that declares
[type parameters of its own](/reference/generics/#a-member-declares-its-own) is a different, milder
case: it is not a function until a call names its types, so no slot can point at it, and the other
members are unaffected. The object can still be formed, and it dispatches them:

```sysl
trait Applies
    tag(self) -> int
    apply[U](self, f: &Fn(int) -> U) -> U

struct N
    v: int

impl Applies for N
    tag(self) -> int = self.v
    apply[U](self, f: &Fn(int) -> U) -> U = f(self.v)

reach[T: Applies](x: T) -> string = x.apply(n -> s"<${n}>")

val o: &Applies = N(3)

print(o.tag())
print(N(3).apply(n -> s"<${n}>"))
print(reach(N(4)))
```

```output
3
<3>
<4>
```

Of the three ways to reach that member, only one is closed. On the value it is an ordinary call; a
bound solves the member's own parameters at the instantiation, as `reach` does; the object cannot
reach it, because it dispatches through a table:

```sysl
trait Applies
    tag(self) -> int
    apply[U](self, f: &Fn(int) -> U) -> U

struct N
    v: int

impl Applies for N
    tag(self) -> int = self.v
    apply[U](self, f: &Fn(int) -> U) -> U = f(self.v)

val o: &Applies = N(3)

print(o.apply(n -> s"<${n}>"))
```

```error
'apply' of 'Applies' declares type parameters of its own, so it is not a function until a call names them
```

**The consequence for bounds**: an object does not satisfy a bound on such a trait, because a bound
requires every member and the object reaches only its table
([generics](/reference/generics/#a-trait-object-satisfies-a-bound-on-the-trait-it-dispatches-through)).
That is the one exception to an otherwise universal rule, and it is described there.

**This is what lets a trait carry a `map`.** A member choosing its own result type is what makes
[`sysl.seq`](/library/seq/) possible (`map[U]` names `U` at the call and nowhere else), and the cost
is a member no object dispatches, borne only by traits that declare one.

**A bare-arrow callable is the same case in different syntax**, and the most common one in traits.
`f: T -> U` is shorthand for a type parameter bounded by `Fn(T) -> U`
([types](/reference/types/#function-types)), so a member written that way declares a type parameter
and loses its slot. `sysl.seq` makes that trade deliberately: nothing is boxed at any call into it,
and `&Sequence[int]` dispatches none of its members. The boxed form keeps the slot, so a trait meant
to be erased uses it.

### Forming and using one

Erasure is a **coercion**, applied wherever a trait-object type is expected: an argument, a declared
variable, an assignment, a returned value, an array element, a struct field. `&r` erases to `*Shape`;
a `&Rect` erases to `&Shape`; and a plain `Rect(3, 4)` where a `&Shape` is expected is boxed and then
erased, which is the ordinary rule that a construction where a `&T` is expected is allocated, with
one more step.

**A `*Trait` does not accept a bare value.** A raw pointer needs an address, and taking one
*silently* would let a program acquire a dangling pointer with no line to show for it:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int

impl Shape for Rect
    area(self) -> int = self.w

var s: *Shape = Rect(2)

print(s.area())
```

```error
a *Shape points at a value, so it needs an address — write '&' in front of the Rect to take one
```

**Writing the `&` is all it takes**: `&Rect(2)` stores the value in a hidden local of that scope and
yields its address, so the fix the diagnostic names is one character rather than a second
declaration. Only the silent case above is refused; see
[Addressing a value](/reference/memory/#addressing-a-value).

Because the coercion applies **per branch**, an `if` or a `match` whose arms have different concrete
types can produce one trait object:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
    h: int

struct Square
    s: int

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

impl Shape for Rect
    area(self) -> int = self.w * self.h

var s: &Shape = Rect(2, 3)

print(s.w)
```

```error
a &Shape has no fields, and trait 'Shape' declares no 'w'
```

A call is checked against the **trait's** signature, which represents every implementation because
conformance is exact.

**An object keeps one trait and what that trait requires.** A bound may name several traits because
a bound is a list; a trait-object type names one because it is a type. So a value implementing
`Shape` *and* `Display` keeps only `Shape` when it becomes a `&Shape`, unless `Shape` **requires**
`Display`, which decides whether the object is printable. A multi-trait object type
(`&(Shape + Display)`) would be a second, worse way to say the same thing, since it repeats at every
use a fact that belongs on the trait.

### The two sigils do not convert

`*Trait` and `&Trait` are two types, and neither is accepted for the other.

In the useful direction, lending a counted object to code that only reads it, this is stricter than
for plain references, which have a form for it: `&*r` is the address of the place `*r`, so a `&T`
reaches a function written against `*T` with the step into the unsafe tier written at the call. An
object has no dereference, so `&*o` has no meaning, and a function that only reads a shape must be
written once per sigil. This is an open gap in the language: what is missing is a form, and any
form must keep the crossing searchable.

The other direction is refused for a stronger reason: a raw object points at a value with no count
to take a share of, so accepting one where a counted object is expected would be **inventing
ownership**.

### An object cannot be erased a second time

`trait Shape: Display` puts the slots of `Display` in a `&Shape`'s table, and the object satisfies a
`[T: Display]` bound, but it cannot become a `&Display`. A bound asks what may be **called** through
the value, and the table answers it. Forming an object asks what may be **assembled** from the
value's type, and a table is built from a type's implementations, which an object does not have.

```sysl
trait Shape: Display
    area(self) -> int

struct Rect
    w: int
    h: int

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

The members of `Display` are in that table, laid out inline so that a required trait's method costs
the same one indirect call as the trait's own methods. What is missing is a *name* for that run of
slots. Flattening gives up the upcast in exchange for one indirection instead of two on every call
through a `&Shape`.

### There is no way back to the type

An object cannot recover its erased type. There is no cast and no type test, and the form a reader
might try first is not one either: a type is not a pattern, so `s is Rect` reads `Rect` as an
ordinary binding, which matches anything:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
    h: int

impl Shape for Rect
    area(self) -> int = self.w * self.h

var s: &Shape = Rect(2, 3)

if s is Rect then print("yes") else print("no")
```

```error
this pattern matches every &Shape, so the test is always true — take the value apart with 'match', or bind it with 'var'
```

**This is deliberate.** A downcast would undo erasure: every other rule here says an object offers
the trait's members and nothing else, and a type test would also expose its concrete type, which the
object type deliberately does not provide. Languages that offer downcasts need a separate mechanism
for them, which is a substantial addition rather than a small extension of this one.

### The identity is readable, and it is not a way back

**`o::Id` gives the identity of the type inside an object**, as a `usize`. It is the one fact about
the erased type that an object still carries, because the method table it points at begins with
it:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int

struct Sq
    s: int

impl Shape for Rect
    area(self) -> int = self.w

impl Shape for Sq
    area(self) -> int = self.s

var a: *Shape = &Rect(2)

print(a::Id == Rect::Id, a::Id == Sq::Id)
```

```output
true false
```

**This is not the downcast the section above refuses.** The id can only be **compared**: two values
with the same id hold the same type. There is no map from an id back to a type, no test that changes
what an object offers, and no `Any`. It supports the two things a collection of erased values
typically needs, neither of which is a cast: **a key**, for asking whether the node here is the same
*kind* as the node that was here before, and the first half of a **memo table's** key.

A program that counts one kind of shape in a collection therefore needs no hand-maintained `kind`
property returning a constant in every implementation; it compares the ids directly:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int

struct Sq
    s: int

impl Shape for Rect
    area(self) -> int = self.w

impl Shape for Sq
    area(self) -> int = self.s

var xs: [3]*Shape = [&Rect(1), &Sq(2), &Rect(3)]
var rects = 0

for x in xs
    if x::Id == Rect::Id then rects += 1

print(rects)
```

```output
2
```

**A known type is asked with `T::Id`**, and `::Id` on a value is allowed **only** where the value is
erased, the one case where it tells you something the static type does not:

```sysl
struct Rect
    w: int

var r = Rect(2)

print(r::Id)
```

```error
'::Id' on a value reads the identity an erased value carries
```

**It guarantees only that equal ids mean the same type.** It is not stable across compiler versions
(it is derived from the type's name, and the naming scheme may change), it is not a number to store
anywhere, and two compilations of *one* program agree only because they compute the same value from
the same name. A generic body asks through its parameter, `T::Id`, which is computed once per
instantiation.

## A method may promise to borrow

A call through a trait object is opaque: which body it reaches is decided at run time, so
[escape analysis](/reference/memory/#what-happens-when-a-slice-escapes) has to assume the worst of
every argument. A view of an array in your own frame passed through one could be kept by whatever is
behind it, so the array goes on the heap.

`@borrows` lets a trait state otherwise, and the compiler checks every implementation against it, so
the promise is verified rather than trusted:

```sysl
trait Sink
    @borrows(bytes)
    put(*self, bytes: []const u8)

struct Counter
    n: usize

impl Sink for Counter
    put(*self, bytes: []const u8)
        self.n += bytes.len

var c: Counter
var s: *Sink = &c
var buf: [8]u8

buf[0] = 7
s.put(buf[0..<4])

print(c.n)
```

```output
4
```

Without the annotation the program behaves the same, but `buf` is allocated. With it, the frame keeps
its storage, which is what makes rendering allocation-free: a renderer writes into a buffer on its
own stack and passes a slice of it. `sysl.Writer` declares exactly this, as an ordinary user of the
feature rather than a special case in the compiler.

**An implementation that keeps what it was lent is refused**, naming the parameter as the trait
declares it:

```sysl
trait Sink
    @borrows(bytes)
    put(*self, bytes: []const u8)

struct Bad
    held: []const u8

impl Sink for Bad
    put(*self, bytes: []const u8)
        self.held = bytes

var b: Bad
var s: *Sink = &b
var buf: [8]u8

s.put(buf[0..<4])
```

```error
keeps what it is passed as 'bytes', but 'Sink' declares that parameter borrowed
```

Reading from the view is not keeping it. The check distinguishes the two by what the body *does*,
not by how it is written.

**It names parameters rather than being a flag**, because a method may take both a buffer it borrows
and something it legitimately retains. A name that is not a parameter is refused: a misspelling would
otherwise silently check nothing while appearing to enforce a rule.

**It is refused on a free function.** That body is visible to the compiler, and the analysis reads
the answer from it; a written promise would restate what is already known and become wrong as soon as
the body changed.

```sysl
@borrows(xs)
take(xs: []const u8) -> usize = xs.len

print(take([1][..]))
```

```error
'@borrows' is about a call whose body the compiler cannot see
```

## Reaching a trait's members without a value

A trait may declare a member with **no receiver**, an associated function, reached through the
*type* rather than through a value of it:

```sysl
trait Origin
    origin() -> Self

impl Origin for int
    origin() -> int = 0

start[T: Origin]() -> T = T.origin()

var n: int = start()

print(n)
```

```output
0
```

`T.bits()` inside a generic body, `Self.bits()` inside a member's, and `u32.bits()` anywhere reach the
same member through three ways of naming its type.

**A built-in type may carry one.** Through a bound the name used is the *parameter*, which every type
has whether or not it has a name of its own, so `impl Word for u32` may declare `bits()`, and
`impl Float for real` declares the epsilon and the two values no literal can express. A
**composed** type has no name at all, so an `impl` for `[]int` refuses a member with no receiver.

**The library's own pair is [`Zero` and `One`](/library/math/)**, the simplest use of this feature:
one member each, no receiver, and the only way a generic body can write the value an accumulation
starts from. They are in the standard module, so `T.zero()` under a `[T: Add + Zero]` needs no
import, and a `T` that is itself generic brings its own arguments, so the result at
`T = Complex[real]` is a `Complex[real]` with nothing written at the call.

**They are also where a receiverless member is provided by the compiler rather than written.** Every
integer type implements both, with no block anywhere: the `iN`/`uN` families are open, so membership
over them has to be a rule, and a rule about a member with no receiver has to say what it *compiles
to* rather than reading it from a value. `T.zero()` at an integer is the literal, so a bounded
accumulator costs no call. [Expressions](/reference/expressions/) has the rule and the two types it
does not cover.

**It may carry a default**, exactly as a member with a receiver may, and it is inherited and replaced
by the same rules: an implementation that leaves the member out gets the trait's body, and one that
wants its own says `override`. This lets a trait made *entirely* of receiverless members grow: a
member added with a default needs no edit in the implementations for which the default is right.

```sysl
trait Sized
    width() -> usize
    doubled() -> usize = Self.width() * 2

struct Word
end Word

impl Sized for Word
    width() -> usize = 4

show[T: Sized]() = print(T.width(), T.doubled())

show[Word]()
```

```output
4 8
```

**The one member that may not carry a default is a [static property](/reference/declarations/#a-static-property)**,
for its own reason: it has no parameter list and no receiver, so nothing could vary the value a
default would give every implementation.

```sysl
trait Maker
    static size -> int = 1
```

```error
'Maker.size' is a property of the type, so a default body would give every implementation the same value — drop the body and let each one supply it
```

**It is static dispatch only.** Object safety already excludes a member with no receiver, because a
table slot is selected *by* the receiver and there is nothing to select with. A trait declaring one
is usable as a bound but not as an object, as is one that mentions `Self` twice.

A function can be entirely *about* a type without mentioning it in its signature, such as
`describe[T: Word]() -> string`. It is called with an explicit type argument, as `show[Word]()` is
above ([generics](/reference/generics/#writing-the-type-arguments)).

---

Next: [generics](/reference/generics/).
