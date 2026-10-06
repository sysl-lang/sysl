---
title: Traits
summary: The one polymorphism mechanism — declaring, implementing, coherence, associated types, required traits, and trait objects.
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
| a property's setter | `set size(n)` | `x.size = n` |
| associated function | `zero() -> Self` | `T.zero()`, through the type |
| [static property](/reference/declarations/#a-static-property) | `static lowest -> int` | `T.lowest`, through the type and with no parentheses |

**A property is asked for by dropping the body from its declaration form**, and supplied by an
implementation writing that body:

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

Nothing about a property's dispatch differs from a method's: it has a receiver, it simply never
spells one, so it takes a table slot beside the methods and a bound licenses reading it exactly as
one licenses calling them.

**A trait may ask for the write half too**, by declaring the setter beside the property
([declarations](/reference/declarations/)). A setter is a `*self` method that mentions `Self` nowhere
but its receiver, so it takes a slot of its own and changes nothing about object safety — a bound
writes the property, and so does an erased object:

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
trait**, so that is where a setter for it is declared — writing one the trait does not ask for names
the trait rather than the concrete type, because an inherent setter there would not license the
write.

**Which kind a member is has to match between the trait and the implementation**, and that is a real
check rather than a formality — a property and an associated function both have no receiver to
compare, so `size(self) -> int` would otherwise quietly stand in for `size -> int`. A **static**
property is a third member with no receiver, and the word is what tells it from the other two.

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

### Replacing a default says `override`

An implementation may write the member itself instead of taking the trait's body, and when it does it
says so — replacing a default is the same act as [replacing an implementation](#override-when-the-overlap-is-deliberate),
and takes the same keyword:

```sysl
trait Loud
    volume(self) -> int = 11

struct Amp
    n: int

impl Loud for Amp
    override volume(self) -> int = self.n

var a = Amp(4)

print(a.volume())
```

```output
4
```

Leave it off and the member is refused, because the reader of an `impl` block wants to know which of
its members are replacing something and which are supplying what the trait asked for — a question
that otherwise means opening the trait to find out:

```sysl
trait Loud
    volume(self) -> int = 11

struct Amp
    n: int

impl Loud for Amp
    volume(self) -> int = self.n

print(1)
```

```error
trait 'Loud' supplies a body for method 'volume', so writing one here replaces it — say 'override volume', or leave the member out to keep the trait's
```

**And it is refused where nothing is replaced.** A member answering a bare requirement — the ordinary
case, and every member of most `impl` blocks — supplies what the trait asked for rather than
replacing a body, so the keyword would be saying something untrue:

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

The rule costs almost nothing, because an implementation content with a default writes no member at
all — across the whole of sysl's own library, guides and examples, exactly two members replace one.

## Conformance is explicit, always

A type that happens to have a member of the right name and shape does **not** satisfy a trait. There
is no structural conformance, and the `impl` is what documents the intent:

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

Nominal is the consensus among the languages sysl takes from — Swift protocols, Kotlin interfaces,
and Scala traits are all nominal — and it kills *accidental conformance*, where a type satisfies a
promise purely because two names collided.

**Retrofitting is preserved.** You may `impl` your trait for a type you do not own; you just do it
explicitly rather than by implicit structural match.

## Reaching a member through its trait

A member declared by a trait is reached **where the trait is**, and the member's own name is not
enough. That is the ordinary scope rule — a trait is a module member like any other — but it
surprises, because what is written at the call is the *member's* name while what has to be in scope
is the *trait's*:

```sysl
import sysl.seq.Sequence

val a = [1, 2, 3]
val xs: []const int = a[0..]

print(xs.map(x -> x * 2))
```

```output
[2, 4, 6]
```

Take the import away and the member is not there. The compiler says which trait it came from rather
than leaving a reader to guess why a slice has a `map` in one file and not the one beside it:

```sysl
val a = [1, 2, 3]
val xs: []const int = a[0..]

print(xs.map(x -> x * 2))
```

```error
[]const int has 'map' from sysl.seq.Sequence, and that trait is not in scope here — import it to reach the member
```

An `impl` does not change this. It settles which types are members; it does not put the member's
name anywhere a file can see without naming the trait that declares it. So **moving a member out of
a type and into a trait moves an import onto every caller** — including callers that never mention
the trait — which is a cost worth weighing before making that move.

### Two traits may declare a member of one name

They do not collide by existing, and a type may implement both. What separates them is the type the
call goes through, so erasing to one trait or the other says which is meant:

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

**Called on the value itself, with both traits in scope, there is nothing to read an intention off:**

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

Note what the refusal turns on: *each is in scope here*. A file importing one of the two has no
ambiguity to resolve, which is the ordinary case and the reason this is rarely met.

**Where it cannot be arranged away is a member taking no arguments on a type that is genuinely
both**, and that is why a question two traits both want is a **required** trait rather than a member
each declares. `Writer` and `Reader` both need to answer whether a stream has gone wrong, and an open
file is both — so a `failed` declared by each would be exactly the refusal above, on the one type
that most needs to be asked. Requiring [`Fallible`](/reference/errors/) from both makes the question
go away instead of moving it, and the library does that rather than declaring the member twice.

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

struct Cat
    n: string

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

**An `impl Trait[A…] for Type` may appear only in the module that declares `Trait`, or in one that
declares a type the block names — in `Type` or among `A…`.** Resolving a bound therefore inspects
only the modules a use site already depends on in order to write the trait and the type down. No
global search, and no dependency edge the source does not show.

This is Rust's orphan rule, and it costs nothing:

- **retrofitting still works** — `impl MyTrait for TheirType` lives with `MyTrait`, and the trait's
  module licenses it;
- **`impl Show for int` still works** — a built-in has no module of its own, so its owner key belongs
  to the library, and every `impl` on one is licensed by its trait's module instead;
- **a composed type is the module's when anything named in it is** — `override impl Display for
  []Point` is licensed by `Point`, while a block for `[]int` names nothing outside the library and
  has no home. (It says `override` because the library implements `Display` for every slice; the two
  rules are separate, and a slice of your own struct needs both — coherence to have a home, and
  [`override`](#override-when-the-overlap-is-deliberate) to outrank the block already covering it);
- **a type parameter is not a local type**, so `impl[T: Display] Display for []T` is refused however
  its bound is written. Making every printable slice printable is the library's job;
- **a local type in the trait's arguments licenses the block too**, which is what puts a scalar on
  the left of an operator. `impl Mul[Point, Point] for real` is written by the module that declares
  `Point` — `real` is the library's and `Mul` is the library's, and the argument list is what makes
  the block that module's business:

```sysl
struct Point
    x: real
    y: real

impl Mul[real] for Point
    mul(self, k: real) -> Point = Point(self.x * k, self.y * k)

impl Mul[Point, Point] for real
    mul(self, p: Point) -> Point = Point(p.x * self, p.y * self)

val p = Point(1.0, 2.0)
val a = p * 2.0
val b = 2.0 * p

print(a.x, a.y, b.x, b.y)
```

```output
2 4 2 4
```

What the rule forbids is the case with no home: **a foreign trait implemented for a foreign type**,
where two unrelated modules could each supply a different implementation and no rule picks one. So
`impl Mul[real, real] for real` is refused in any module but the library's, naming nothing of its
own in either place:

```sysl
impl Mul[real, real] for real
    mul(self, k: real) -> real = self

print(1)
```

```error
an 'impl' may be written only in the module that declares the trait or in one that declares a type the block names, and 'sysl.Mul' is the library's while nothing this block names is declared outside the library — so this one has no home
```

The built-in keeps everything it had. `real` is a member of `Mul` whatever anybody writes, and only
the argument list tells the two apart — so `2.0 * 3.0` is still the machine's multiply, and the
block above reaches `real` only at the pair it was written for.

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

**Its subject must be the type applied to the block's parameters and nothing else** — each argument
one parameter, each parameter used once, all of them spoken for:

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
about it. `"hi".bytes` is a `[]const u8` and is covered, which is the next rule.

#### Both views of a slice are one shape

`[]T` and `[]const T` are one type with a bit rather than two types
([`arrays.md`](/reference/arrays/#const-t-a-view-that-may-not-be-written)), and they share the one
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

The block is written against what a slice *is* — a pointer and a count of `T` — and whether this one
may be written through is no part of that.

**What the covering does not do is lend a member a licence its receiver never had.** The block is
made real **once per view**, so the instance a read-only receiver reaches has a `self` it may not
write through. A member that writes is refused, in its own body:

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

The refusal lands on the line that writes rather than on the call, which is where a reader can act on
it. It has to say more than the write, though: the block's own file wrote `[]T` and never typed a
`const`, so the message names the receiver that chose this instance. `bump` is callable as it always
was — on a `[]int`:

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

**Write `[]const T` as the subject where the members only read.** Nothing forces it and both forms
reach both views, but the subject is where a reader looks to find out what a block does with what it
is given, and a promise made there is one nobody has to discover from a refusal.

**A shape and a written-out type overlap, and an unmarked overlap is refused.** Both blocks would say
how a `[]int` renders, so whichever is written second is refused and the diagnostic names the one
already there:

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

That is the default and it is worth keeping: two blocks that overlap are usually a mistake — a
duplicate written by accident, or one put in the wrong module — and refusing them is how that gets
found.

### `override` — when the overlap is deliberate

An implementation may say **`override`**, and then it wins:

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

**The keyword goes on the overriding side, not the overridden one.** That is the whole of the design,
and it is the opposite of C#'s `virtual`/`override` pair and of Rust's unstable `default`: both of
those make the general implementation grant permission in advance, and a library author cannot know
which of their implementations somebody will need to replace. Intent is something the writer of the
override has and the writer of the original does not.

It grants no permission, so what it buys is the diagnostic. An unmarked second implementation is
still refused exactly as above.

**The overriding side is always a type written out in full.** A shape is one key and so is a generic
type's name, and the blocks that would sit *under* those are already refused — `impl[T] Show2 for
[][]T` matches a shape's argument by its shape, and `impl Show2 for Box[int]` fixes one instantiation
of a block covering every instantiation. So there is nothing below either of them to be more specific
than, and marking one says so:

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

**An `override` that overrides nothing is refused**, which is the check in the other direction and
the one that earns its keep later: a library drops or narrows the implementation a program was
overriding, and without this the override silently becomes the only one while still claiming to
replace something.

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

**What keeps this sound is coherence rather than the keyword.** The hazard a rule like this usually
brings is two method tables for one type — a `[]Point` erased to a `*Show2` picking one
implementation at one site and the other elsewhere. An `impl` may live only in the module declaring
the trait or in one declaring a type named in the subject, so a program cannot write
`impl[T] Display for []T` at all — `[]T` names no type of its own. **The only override anybody can
write across a module boundary is one that names their own type**, so there is exactly one per type
and it lives with that type; and any site that can write `[]Point` down already depends on the module
declaring `Point`. One type, one table.

The cost that remains, stated plainly: a library can no longer rely on its own implementations. What
`override` buys is that every such site is greppable rather than invisible.

## The compiler writes four of them

Four traits are pure structure. `Eq` on a product is its fields compared one by one; `Ord` is those
comparisons in declaration order; `Hash` is the fields mixed; `Display` is the name and the fields.
Writing them out is the most mechanical code a program contains, and it is code that goes wrong
quietly — a field added to a struct and forgotten in its `eq` is a comparison that silently stops
looking at it.

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

The clause goes after the name and its type parameters, before the body. What it produces is an
ordinary `impl` block — the one a person would have typed — so a derived implementation is found,
checked, dispatched and erased exactly as a written one is, and a field that cannot do the work says
so in the ordinary words.

**`Display` writes one more declaration than the block**, and it is the only one of the four that
does: a file-private function beside the type, which writes the name and each part straight through
to wherever the rendering is going. `display` points it at a
[`Counting`](/library/core/#counting-the-width-without-the-bytes) sink first where a width was asked
for, learns how wide the value came out, pads once and renders for real — so a derived rendering
[builds no string](/library/core/#a-specifier-is-the-whole-value-s-field) and an ordinary `print` of
one costs a single pass. A shape with no parts — a fieldless struct, an enum whose variants carry
nothing — renders as one name and gets no renderer, there being nothing to measure.

Its name is a [quoted identifier](/reference/lexical/#quoted-identifiers) with a space in it,
`` `render Size` ``, so no ordinary name can collide with it: an unquoted identifier is letters,
digits and `_`. It is not a name to call, and nothing but a stack trace or an object file will show
it to you.

**The four are the whole list**, and it is closed: `Eq`, `Ord`, `Hash`, `Display`. They are the four
the library already provides structurally for [every tuple](/reference/types/#what-the-library-gives-every-tuple),
and a derived block walks a named product by the same rules a tuple's is walked by — so a `Size` and
a `(int, int)` compare and order alike, and render alike but for the name in front. A trait of your own is implemented with an
`impl` block; there is no way to teach the clause a fifth name.

```sysl
trait Show
    show(self) -> string

struct P derives Show
    x: int
```

```error
is not a trait the compiler knows how to write
```

### Order is declaration order

`Ord` is lexicographic, first field first — the only ordering a named product has a claim to, and the
one a tuple already has. That makes the order fields are written in part of what the type promises,
which it already was for layout: a struct's fields are laid out in the order they are written.

```sysl
struct Version derives Ord, Display
    major: int
    minor: int

print(Version(1, 9) < Version(2, 0), Version(2, 0) < Version(1, 9))
```

```output
true false
```

**So a type that orders by one of its fields should say so rather than derive.** A block written out
is not a failure to use the clause; it is the case the clause does not cover.

### A generic type derives conditionally

Every type parameter gains the derived trait as a bound, which is exactly
[conditional conformance](#conditional-conformance) written for you: a `Box[int]` is `Eq` and a `Box`
of something unequatable is not, and neither needs saying.

```sysl
struct Box[T] derives Eq, Display
    v: T

print(Box(1) == Box(1), Box(1) == Box(2), Box("x"))
```

```output
true false Box(x)
```

A `const` value parameter gains nothing — it is not a type and has no membership to ask for — and the
type's own bounds are kept, since `struct Sorted[T: Ord]` is only a type at all where `T` is `Ord`.

### An enum takes the clause too

A variant renders under **its own** name, which is how it is written. Comparison is by variant first,
in declaration order, and then field by field within the variant.

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

The hash mixes the variant before the payload, so two variants carrying equal payloads are not one
key — which a table holding both would otherwise collide on at every insert.

A **simple** enum — one where no variant carries anything — is already `Eq` by rule, because its
value *is* its discriminant. The clause says so rather than letting the block be refused further in:

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
general block for a derived one to outrank; where one is ever needed, an
[`override impl`](#override-when-the-overlap-is-deliberate) is written by hand and the two forms do
not have to compose.

And derivation is **all or nothing per trait**. There is no writing the block and then replacing one
method of it, and a derived block beside a hand-written one for the same trait is the duplicate
implementation it looks like.

Nor does it reach a type whose fields it cannot see. An `opaque struct` with no body is C's
incomplete type — the storage belongs to whoever allocated it and nothing here knows its shape — so a
derived `Eq` over no fields at all would answer `true` for every pair with nothing to say it had:

```sysl
opaque struct Handle derives Eq
```

```error
opaque and declares no fields
```

That is the only place visibility comes into it, and it is about a layout that is absent rather than
one that is hidden. The clause is part of the declaration, so the block it writes is in the module
that declares the type: a **private field is walked**, because the block is written where the type
is.

## A trait may take type parameters

A trait declares parameters in the same bracketed list every other generic declaration writes, and an
implementation says which arguments it supplies:

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
container carry the type of what it holds in the trait it implements, without reaching for the
[associated type](#a-trait-may-declare-an-associated-type) below — and it is the right tool when the
type is one the *container's* own parameters already name. The block's parameters are exactly the arguments of the type it is written for, so an
argument built out of them says one thing per instantiation and the subject settles which — including
where the argument is the subject itself, which is how [an operator carries a result that is not its
operands' type](/reference/expressions/#at-a-generic-subject).

**What a generic block may not write is an argument fixed to one instantiation of its own subject.**
Where the trait's default names the type being asked about, such an argument coincides with the
defaulted block at that one instantiation and differs from it at every other — which is a choice
between implementations rather than a lookup:

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
**implementation**, and everything else reads it back off the type:

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

`type Item: Render` declares the parameter and what the type filling it must implement. `type Item =
string` fills it. `Self::Item` is the **projection** — the same `::` that reads
[`int::Max`](/reference/attributes/) in expression position, reaching type position — and `show`'s
body may call `render` on what it gets because that is what the trait asked of `Item`, and nothing
more.

That is the whole difference from `trait Sink[T]`, and it is one difference with three consequences:

| | a trait parameter | an associated type |
|---|---|---|
| who writes the argument | whoever names the trait | the implementation |
| how many per type | one implementation **per argument list** | **one**, and the subject settles it |
| how a use names it | `Sink[int]`, everywhere the trait is named | `T::Item`, off the type |

A trait may declare both. The parameters go in the bracketed list and the associated types among the
members, and neither is in the other's way.

### The projection normalizes as soon as its subject is concrete

`Self::Item` inside a generic body is **abstract** — it stands for a type nobody has chosen yet, and
is licensed to do exactly what the trait's bound on it promises. Off a type that is actually known it
is the type the implementation chose, and may be written wherever a type may:

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

**So there is no hiding, and that is deliberate.** Swift's `some View` conceals the type because
Swift gives you no way to write it down; `Nums::Item` is a way to write it down, so concealment would
buy a restriction rather than an abstraction. What a generic caller sees is still only the bound —
that is where the abstraction pays, and it is delivered by the projection being abstract there.

### A generic block answers with one type per instantiation

The block's own parameters may stand in the associated type, exactly as they may stand in a trait
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
concrete type all the way down, so none of this costs anything at run time — there is no box per
value and no indirect call.

### A default's callable may name the projection

A [default body](#a-default-may-assume-exactly-what-its-own-trait-declares) is checked once at the
trait, where `Self` is a parameter, and copied per implementing type, where `Self` is that type. A
[bare arrow](/reference/generics/#bounds) is sugar
for a bound rather than a parameter type, so a default taking one over `Self::Item` puts the
projection somewhere neither half is written out — and each copy binds it to what its own subject
chose:

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

`n` is an `int` and `s` is a `string`, from one body that names neither. The arrow costs nothing at
the call — it monomorphizes, so there is no closure on the heap — and it is the member's own type
parameter `N` that keeps `over` [out of the table](#a-member-with-its-own-type-parameters-leaves-the-table-not-the-trait)
rather than anything about the projection.

### `some Trait` — the result read off the body

The type an implementation chooses is often one nobody wants to write. A tree of nested containers is
the case the feature exists for: the type is enormous, it is the implementation's business, and it
changes whenever the body does. `some Trait` in an `impl` member's result says *"the associated type
is whatever this body produced, and it implements this"*:

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

No `type Body = …` line is written, and none is wanted: the body is the answer. `Counter::Body` is
`Text` from that point on, so the two spellings are the same feature — one writes the type and the
other reads it off the body.

**`some` is a contextual word**, special only in front of a bound in a result position. A program may
still name a variable, a parameter or a function `some`.

**Every path out of the member must produce one type.** Two branches yielding different concrete types
are an error, not a silent widening to the bound — the bound is what a *caller* may rely on, and it is
not what the member returns. No rule of its own is needed for this: an `if` used as a value already
has to agree with itself, and that is the rule that speaks.

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

**And the type may be one nobody else can name.** A `private` struct supplied through a `some` result
works and is genuinely opaque outside the file that declared it — a caller reaching it through a bound
does what the bound allows and has no spelling for the type at all, which is what Swift's `some View`
buys by concealment and this gets from ordinary visibility.

**It stands in that one position and nowhere else.** A free function has no trait to settle anything
for, so the reason to write one is missing and the refusal says where it belongs:

```sysl
trait Render
    render(self) -> string

f(x: int) -> some Render = x

print(1)
```

```error
'some Render' says the type is read off the body of a member that supplies a trait's associated type, so it stands only in an 'impl' block
```

The promise is checked against what the body actually produced:

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

A written binding is held to the trait's bound in the same way:

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

And an implementation that supplies none has not implemented the trait, exactly as a missing method
has not:

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
asks something of the thing it is written on, and what `type W: Add` is written on is `W` — so
[`Add`](/reference/expressions/)'s defaults, which are `Self`, are filled from `W`. `type W: Add` on
an implementation supplying `type W = u32` therefore asks whether `u32` is `Add[u32, u32]`, which it
is, and never whether it can be added to the implementing type:

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

It is the same reading `Self` has in a bound anywhere else, and it is stated here because the
associated type is the one place where two types are in play and either would be a plausible answer.

**And the bound is answered once every `impl` in the program is registered**, so the block making
the supplied type a member of it may be written below the block that chose it — the same rule a
[required trait](#a-trait-may-require-another-trait) follows, and for the same reason.

### One name per type, because a projection does not name its trait

`Box::Item` says which *type* and which *name*, and never which trait. So a type may implement at most
one trait declaring an associated type of any one name, and the second block is where that is said:

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

Rust spells the qualified form `<T as A>::Item`; there is no such spelling here, and the collision is
refused rather than left to be disambiguated at every use.

### Declaring one spends the trait's erasability, unless the object says which type it is

An erased value has forgotten which type it is, and an associated type is a function of exactly that —
so the slot would have a different signature for every implementing type. A bare `&Seq` is therefore
not something that can be formed, and the refusal names the associated type rather than leaving it to
be discovered as a `Self`:

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

**A bound keeps the type, and so keeps the answer.** `[S: Seq]` is what such a trait is for, which is
the same place [the operator catalog](#object-safety) ends up and for a related reason. A trait that
*requires* one is unerasable too, and the diagnostic names the trait the associated type came from.

### An object may fix the associated type

The other answer is to write the type down. `&Seq[Item = int]` is a value of some forgotten type
**whose `Item` is known to be `int`** — so every slot has one signature again, and there is a table to
point at:

```sysl
trait Seq
    type Item
    head(self) -> Self::Item

struct Box
    v: int

impl Seq for Box
    type Item = int
    head(self) -> Self::Item = self.v

show(s: &Seq[Item = int]) -> unit
    print(s.head())

show(Box(7))
```

```output
7
```

**Where a trait has no parameters of its own and exactly one associated type, the name may be left
off.** `&Seq[int]` and `&Seq[Item = int]` are then the same type — not two types with a conversion
between them — so a function declared with either takes what the other made:

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

The short form stops exactly where it could be read two ways. A trait with parameters **and** an
associated type takes its arguments in order, at the front, and names the associated type beside
them — `&Keyed[int, Item = string]` — because a bare argument there means one of the trait's own:

```sysl
trait Keyed[K]
    type Item
    at(self, k: K) -> Self::Item

struct Row
    v: int

impl Keyed[int] for Row
    type Item = int
    at(self, k: int) -> Self::Item = self.v + k

show(s: &Keyed[int, Item = int]) -> unit
    print(s.at(2))

show(Row(5))
```

```output
7
```

**The value put into the object has to have chosen the same type.** That is what makes the binding
sound rather than a promise: every slot's signature was read under the object's answer, so a value
whose own implementation chose otherwise would be called through a table promising the wrong types.
It is refused at the erasure, which is the one place both are known:

```sysl
trait Seq
    type Item
    head(self) -> Self::Item

struct Box
    v: int

impl Seq for Box
    type Item = int
    head(self) -> Self::Item = self.v

show(s: &Seq[Item = string]) -> unit
    print(s.head())

show(Box(7))
```

```error
a &Seq[Item = string] says 'Item' is string, and Box supplies int for it — an object fixes the associated type, so only a type that chose the same one goes into it
```

A binding is held to whatever the trait asked of the associated type, exactly as an implementation
is; and a required trait's associated type is bound in the same brackets, under the name it was
declared with, since its members are slots in the same table.

**A bound has no such spelling.** `[S: Seq[Item = int]]` is not written, and nothing bounds a
projection either — so a generic body may call what a bound licenses and may not assume anything
about what comes back beyond what the trait itself promised of the type. Where a body needs more, the
promise belongs on the trait's own declaration: `type Item: Display`.

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
made into an object when it declares no
[associated type](#a-trait-may-declare-an-associated-type) — the meaning of one *is* the forgotten
type — and when every member:

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

**What that bites is the operator catalog at written arguments**, and only that. `Add[int, int]`
declares `add(self, rhs: int) -> int` — no `Self` anywhere — so it is a formable object type, and an
`int` belongs to it by the compiler's rule; `&Add[int, int] = 3` is therefore refused, and the
diagnostic says why rather than reporting a plain mismatch.

**`Display` and `Hash` are not among them**: every built-in reaches both through an `impl`, so a
`*Display` carries an `int`, a `u256`, a `string` or a float alike, a `&Hash` carries anything that
hashes, and a heterogeneous array of either is ordinary code. The rest of the catalog — `Eq`, `Ord`,
`Bits`, `Signed` — names `Self` away from the receiver, so object safety refuses the *type* before a
value gets that far.

#### A member with its own type parameters leaves the table, not the trait

The four rules above decide whether a trait has an object **at all**. A member that declares
[type parameters of its own](/reference/generics/#a-member-declares-its-own) is a different case and
costs less: it is not a function until a call names its types, so no slot can point at it — and
nothing about the other members changes. The object forms, and dispatches them:

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

Three routes to that member and only one of them is closed. On the value it is an ordinary call; a
bound solves the member's own parameters at the instantiation, which is what `reach` is; and the
object is the one that cannot, because a table is what it dispatches through:

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

**What follows for bounds is the one consequence worth carrying**: an object does not satisfy a bound
on such a trait, because a bound promises every member and the object reaches only its table
([generics](/reference/generics/#a-trait-object-satisfies-a-bound-on-the-trait-it-dispatches-through)).
That is the exception to a rule which is otherwise total, and it is stated there.

**This is what lets a trait carry a `map`.** A member choosing its own result type is the whole reason
[`sysl.seq`](/library/seq/) can exist — `map[U]` names `U` at the call and nowhere else — and the
price is a member no object dispatches, paid by traits that declare one and by nothing else.

**A bare-arrow callable is the same case wearing different syntax**, and it is the one a trait meets
most often. `f: T -> U` is sugar for a type parameter bounded by `Fn(T) -> U`
([types](/reference/types/#function-types)), so a member written that way declares one whether or not
its author was thinking about type parameters — and loses its slot for it. That is the trade
`sysl.seq` takes on purpose: nothing is boxed at any call into it, and `&Sequence[int]` dispatches
none of its members. The boxed spelling keeps the slot, which is what a trait meant to be erased
writes.

### Forming and using one

Erasure is a **coercion**, applied wherever a trait-object type is expected: at an argument, a declared
variable, an assignment, a returned value, an array element, a struct field. `&r` erases to `*Shape`;
a `&Rect` erases to `&Shape`; and a plain `Rect(3, 4)` where a `&Shape` is expected is boxed and then
erased, which is the ordinary "write the construction and it is allocated" rule with one more step.

**A `*Trait` will not take a bare value.** A raw pointer needs an address, and taking one *silently*
is how a program acquires a dangling pointer without a line to point at:

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

**And writing it is all it asks**: `&Rect(2)` gives the value a hidden local of that scope and hands
back its address, so the fix the diagnostic names is one character rather than a second declaration.
What stays refused is the silent case, which is the one above — see
[Addressing a value](/reference/memory/#addressing-a-value).

Because the coercion applies **per branch**, an `if` or a `match` whose arms are different concrete
types meets at one trait object, which is the point of having them:

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

### The identity is readable, and it is not a way back

**`o::Id` answers which type is inside an object**, as a `usize`. It is the one fact about the
forgotten type that an object still carries, and it carries it because the method table it points at
begins with it:

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

**This is not the downcast the section above refuses, and the difference is the whole of why it is
here.** The id **compares** — two values with the same one hold the same type — and there is nothing
else to do with it: no map from an id back to a type, no test that changes what an object offers, and
no `Any`. What it makes possible is the two things a catalogue of erased values actually wants, and
neither is a cast: **a key**, for asking whether the node here is the same *kind* as the node that was
here before, and the first half of a **memo table's** key.

So the cost the decision used to carry is paid. A program counting the circles in a catalogue
declared a `kind` property that every implementation answered with a constant — a hand-maintained copy
of exactly the fact the object's first word already is — and it can ask the word instead:

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

**`T::Id` is how a type that is known asks**, and `::Id` on a value is admitted **only** where the
value is erased — which is the case that says something the static type does not:

```sysl
struct Rect
    w: int

var r = Rect(2)

print(r::Id)
```

```error
'::Id' on a value reads the identity an erased value carries
```

**What it guarantees is that equal ids mean the same type, and nothing else.** It is not stable
across releases — it is derived from the type's name, and the naming is free to change — it is not a
number to write down anywhere, and two compilations of *one* program agree only because they compute
the same thing from the same name. A generic body asks through its parameter, `T::Id`, which is
answered once per instantiation.

## A method may promise to borrow

A call through a trait object is opaque: which body it reaches is settled at run time, so
[escape analysis](/reference/memory/#what-happens-when-a-slice-escapes) has to assume the worst of
every argument. A view of an array in your own frame passed through one could be kept by whatever is
behind it, so the array goes on the heap.

`@borrows` is how a trait says otherwise — and the compiler holds every implementation to it, so the
promise is checked rather than trusted:

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

buf[0] = 7u8
s.put(buf[0..<4])

print(c.n)
```

```output
4
```

Without the annotation that program is identical and `buf` is allocated. With it, the frame keeps its
storage — which is what makes rendering allocation-free, since a renderer writes into a buffer on its
own stack and passes a slice of it. `sysl.Writer` declares exactly this, and is an ordinary user of
the feature rather than a case the compiler knows about.

**An implementation that keeps what it was lent is refused**, by the name the trait gave the
parameter:

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

Reading out of the view is not keeping it, which is the distinction the check draws and the reason it
is a question about what the body *does* rather than about how it is written.

**It names parameters rather than being a flag**, because a method may take a buffer it borrows and
something it legitimately retains. A name that is not a parameter is refused: a misspelling would
promise nothing and say nothing, which reads exactly like a rule being enforced.

**It says nothing above a free function and is refused there.** That body is right in front of the
compiler and the analysis reads the answer out of it — a written promise would restate what is
already known and go stale the moment the body changed.

```sysl
@borrows(xs)
take(xs: []const u8) -> usize = xs.len

print(take([1u8][..]))
```

```error
'@borrows' is about a call whose body the compiler cannot see
```

## Reaching a trait's members without a value

A trait may declare a member with **no receiver** — an associated function — reached through the
*type* rather than through a value of one:

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

`T.bits()` inside a generic body, `Self.bits()` inside a member's, and `u32.bits()` from anywhere are
the same member reached through three spellings of its type.

**A built-in may carry one.** Through a bound the name is the *parameter*, which every type has
whether or not it has one of its own — so `impl Word for u32` may declare `bits()`, and `impl Float
for real` declares the epsilon and the two values no literal spells. What stays refused is the case
the rule was really about: a **composed** type has no name at all, so an `impl` for `[]int` still
refuses a member with no receiver.

**The library's own pair is [`Zero` and `One`](/library/math/)**, which are this feature at its
smallest: one member each, no receiver, and nothing else a generic body could use to write down the
value it has to start an accumulation from. They are in the standard module, so `T.zero()` under a
`[T: Add + Zero]` needs no import — and a `T` that is itself generic brings its own arguments, so the
answer at `T = Complex[real]` is a `Complex[real]` with nothing written at the call.

**They are also where a receiverless member is compiler-provided rather than written**, which is the
half a built-in could not have before. Every integer type is a member of both, and there is no block
for it anywhere: the `iN`/`uN` families are open, so a membership over them has to be a rule, and a
rule about a member with no receiver has to say what it *lowers to* rather than reading it off a
value. `T.zero()` at an integer is the literal, so a bounded accumulator costs no call.
[Expressions](/reference/expressions/) has the rule and the two types it stops at.

**It may carry a default**, exactly as a member with a receiver may, and it is inherited and replaced
by the same rules: an implementation that leaves the member out gets the trait's body, and one that
wants its own says `override`. That is what lets a trait made *entirely* of receiverless members
grow — `sysl.crypto`'s internal `Compression` gained a member saying which end of a word goes first,
and the three compressions for which the answer was already the common one needed no edit.

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
and its reason is its own rather than anything about receivers: it has no parameter list and no
receiver, so there is nothing available to vary what it would hand every implementation.

```sysl
trait Maker
    static size -> int = 1
```

```error
'Maker.size' is a property of the type, so a default body would give every implementation the same value — drop the body and let each one supply it
```

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
