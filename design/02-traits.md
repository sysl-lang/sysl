# Traits (Polymorphism)

**Status:** decided (core model), and **built** — static dispatch through bounds, dynamic dispatch
through `*Trait` / `&Trait`, **default bodies**, **properties** alongside methods, an `impl` for any
concrete type (**composed types included**), and an `impl` for a **generic** type or a composed
**shape**, with **conditional conformance**. Some surface-syntax details are flagged open at the end. How a
plain method and its receiver are spelled — the hole this doc used to leave open — is settled in
`08-methods.md`; a trait's methods are declared and called the same way, with the receiver an
`impl`'s type instead of a concrete one. A signature that has to name the implementing type writes
**`Self`**, specified and built in `14 §1`.

sysl has **one** polymorphism mechanism: the **`trait`**. It is nominal, and it supports both
static dispatch (generic bounds, monomorphized) and dynamic dispatch (a boxed trait object).
This replaces the old design's split into compile-time `trait`s and structural runtime
`interface`s.

## The decision

- **One nominal mechanism, named `trait`.** A type participates in a trait only through an
  explicit `impl Trait for Type` — there is **no structural / implicit conformance**. A type
  does not satisfy a trait by coincidence of method names.
- **Both dispatch modes come from the one trait:**
  - **Static dispatch** — a generic bounded by a trait is monomorphized; zero-cost, no
    indirection (`f[T: Trait](x: T)`).
  - **Dynamic dispatch** — a **boxed trait object** is a fat pointer `{ vtable, data }`; calls
    go through the vtable. For heterogeneous collections, drivers, plugin-style designs.
- **Trait-object ownership is expressed through the three memory modes — no `dyn` keyword.**
  The old structural interface had an invisible `owns` flag (escape analysis silently chose
  share-vs-heap-copy); that violated the "costs are visible" rule and is **removed**. A trait
  object is inherently a fat pointer `{ vtable, data }` (an erased value has unknown size), so
  it is spelled with a memory-mode sigil — the sigil already says everything a `dyn` keyword
  would:
  - `*Trait` → a **raw** fat pointer: a non-owning / unmanaged trait object, used as easily as
    any C pointer (borrowing; kernel / no-allocator contexts);
  - `&Trait` → an **ARC-owned** trait object (refcounted heap box, like any `&T`).

  `*T` / `&T` on a *concrete* type is a thin pointer; on a *trait* it is a fat
  (vtable-carrying) pointer — the trait-ness makes it fat. There is no `dyn`.
- **By-value polymorphism is generics, not a trait object.** When the concrete type is known,
  bound a generic by the trait (`f[T: Trait](x: T)`): the struct moves by value, monomorphized,
  zero indirection — as easy as C. A trait object is only for *type-erased* polymorphism, and
  because an erased value has unknown size it always lives behind `*Trait` / `&Trait`, never by
  value.
- **Retrofitting is preserved** — you can `impl` your trait for a type you don't own; you just
  do it explicitly rather than by implicit structural match.
- **Any type may carry an `impl`, the built-ins and the composed types included.** `impl Show for
  int` is as ordinary as `impl Show for Point`, `impl Show for []int` is as ordinary as either, and
  `5.show()` resolves by the same rule `p.show()` does. This is not a convenience: a `Show` that
  cannot cover `int` is a `Show` no library can be written against, and the library's own `Show` —
  the one that lets `str` stop being a compiler builtin, and lets `print`'s desugaring aim at a
  method instead of six names (`04`, *Printing*) — is the first thing that needs it. Every type has
  one **owner key** its members are filed under: a struct or an enum by the name it was declared
  with, everything else by its one canonical name, so `impl Show for int` and `impl Show for i32`
  are the single implementation they are rather than two. Two types have no key because they have no
  behaviour to give: `never`, which has no values, and `unit`, which has one.

## Why (summary; the design audit holds the long form)

- **One mechanism, not two.** Rust (traits) and Swift (protocols) each get static *and*
  dynamic dispatch from a single nominal mechanism. The old dual system made sysl *harder than
  Rust* on polymorphism — against the "easier than Rust" thesis. Unifying cuts a whole concept.
- **Nominal follows the inspirations' consensus** (`principles.md` §2): Swift protocols,
  Kotlin interfaces, and Scala traits are all nominal single-mechanism; only Go — the source
  of the old structural `interface` — is structural. A 3-way consensus over the lone outlier.
- **Nominal is more explicit** — an `impl` documents intent and kills *accidental conformance*
  (a type matching a trait purely by method-name coincidence), which structural typing invites.
- **Kills a hidden cost** — the auto-`owns` flag was invisible allocation/copy behavior,
  exactly what the three-mode model forbids.

## Coherence — where an `impl` may live

An `impl` is **unnamed**: nothing at a use site says which one to apply, so resolving `T: Show` or
`5.show()` means *searching* for an implementation. Once a program is more than one module (`13`),
that search needs a bound, or it would have to range over every module in the program — which is
exactly the property that makes separate compilation impossible.

**An `impl Trait for Type` may appear only in the module that declares `Trait`, or in one that
declares a type named in `Type`.** Resolving a bound therefore inspects the modules a use site
already depends on to write the trait and the type down. No global search, and no dependency edge
that the source does not show.

This is Rust's orphan rule, and it costs nothing the chapter already promised:

- **Retrofitting still works.** `impl MyTrait for TheirType` lives with `MyTrait` — the trait's
  module licenses it. That is the retrofitting kept above, unchanged.
- **`impl Show for int` still works**, and it is the library's `Show` that makes it legal: a
  built-in has no module of its own, so a built-in type's owner key belongs to the library, and
  every `impl` on one is licensed by its trait's module instead. A trait that could not cover
  `int` is a trait no library can be written against.
- What it forbids is the case with no home: **a foreign trait implemented for a foreign type**,
  where two unrelated modules could each supply a different `impl` and no rule picks one.

An `impl` is part of its module's public surface. Adding, removing, or changing one is an
interface change to that module, visible to everything downstream — the same reasoning that put
`given`/`using`-style implicit resolution out of scope (`13` §7): unrestricted search and separate
compilation are not compatible.

**A type with no declaration is the library's.** A tuple (`00 §13`) has no module to be local to,
which looks like a type the rule has nothing to say about. It is the question `int` already answers:
a built-in belongs to the library, so the library is where its catalog rows live and a user module
writing `impl Eq for (int, string)` is the ordinary orphan case — both halves foreign — while
`impl MyTrait for (int, string)` is permitted, because the trait is local. No exception is needed;
the rule needed a sentence saying where a nameless type lives.

**A composed type is the module's when anything named in it is.** `impl Display for []Point` is
licensed by `Point` — the block is written where `Point` is, and nowhere else could have written it
— while `impl Display for []int` names nothing outside the library and has no home. This is the half
the paragraphs above left to be settled, and it is settled this way because the strict reading takes
something the language cannot do without: with it, a module could not so much as print a slice of
its own struct, and the compiler's own advice — *write an `impl Display for []Point`* — would name a
block the rule then refused. Rust reaches the same answer by its covered-type rule.

The reach of that is smaller than it looks, because a **generic named type has one implementation
covering every instantiation** (below): there is no `impl Eq for Option[Point]` to be licensed, only
`impl[T] Eq for Option[T]`, which names nothing local. So the sentence buys the composed types —
slices, arrays, tuples — and leaves the library's generic enums to the library, which is the right
split: a slice of your struct is your business, and `Option` is not.

**A type parameter is not a local type**, so `impl[T: Display] Display for []T` is refused however
its bound is written. That is the case with two unrelated modules each supplying a different row for
one type, which is what the rule exists to stop; making every printable slice printable is the
library's to do, and the library's own rows for tuples are written exactly that way.

**Open: whether a trait *argument* licenses a block.** Rust counts one — `impl ForeignTrait<Local>
for i32` is allowed there — and sysl does not, so `impl Mul[Complex] for int` has no home. Nothing
has wanted it yet, and allowing it later breaks no program that compiles today.

## Defaults — a trait may answer as well as ask

A trait method is written either as a bare signature, which an implementation must supply, or with a
body, which supplies a **default** every `impl` inherits unless it writes its own:

```
trait Greet
    name(self) -> string
    greet(self) -> string = "hello, " + self.name()
```

`impl Greet for Cat` then writes `name` and gets `greet` free, or writes both and overrides it. A
trait whose every method has a default leaves nothing to write at all, so the block is optional —
`impl Zero for E` on its own line is a complete implementation, and the opt-in is the point of
writing it.

This is the Swift / Kotlin / Scala / Rust consensus, and it settles the question the chapter left
open. What it buys beyond convenience is that **a trait can grow**: adding a method with a default
does not break the implementations that already exist, which is the difference between a trait a
library can evolve and one frozen at its first release. `14 §8 d` had to design around not having
this — `FormatSpec` went into `Display`'s signature early precisely because adding a parameter later
would have broken every `impl` — and the library now uses a default itself, for `Fallible.failed`,
which `Writer` and `Reader` both require (most sinks and most sources cannot fail, and one that cannot
should not have to say so).

**That the latch is one trait rather than two defaults is the first thing a two-way stream asked
for.** It was written twice, once on each of the two traits, until an open file had to be both a
source and a sink at once. Two traits may each declare a member of one name for one type — that is
settled below, and a call says which by naming the trait — so what the library ran into was not a
refusal but something worse to live with: `failed()` takes no arguments, and a program that reads and
writes a file has *both* traits in scope by definition, so neither the arguments nor the scope can
say which was meant and the call is refused at the use rather than at the declaration. **Permitted is
not the same as answerable.** One required trait makes the question disappear instead of moving it,
and it is the diamond the rule below says needs no rule of its own. What it costs is one line per
implementation, `impl Fallible for MySink` with no block, which is the opt-in this section is about.

**A default may assume of its receiver exactly what its own trait declares.** That is not a
restriction bolted on; it is what a default *is*, since the body must serve every implementing type
and the trait is all they have in common. So a default's body is checked once, at the trait, as the
generic function it is — one type parameter, `Self`, bounded by the trait — through the same
definition-time pass `14 §4` runs over every bounded generic. A default calling a method the trait
does not declare is reported at the trait, on its own line, **even when nothing implements the trait
at all**. It may not read a *field* of its receiver either: a bound promises methods, and a field is
layout, so no bound could ever license one (`10 §5`).

The body a program runs is a **copy per implementing type**, materialized under that type's own
`Type.method` name. That is monomorphization with `Self` for the parameter, and it means everything
downstream — an ordinary call, a vtable slot, the escape summary — finds a function that exists and
needs to know nothing about where it came from. One source body, one diagnostic if it is wrong: a
default the definition-time pass already reported is not reported again by each copy.

What a default cannot do is stand in for the checks that need the *implementing* type. A body whose
result disagrees with its declared type for reasons only a concrete type settles is caught where
every other concrete mistake in a generic body is — at each type it is materialized for. A trait
with no implementations therefore gets its bounds checked and nothing about any concrete type, which
is the same reach the definition-time pass has over a generic function nothing instantiates.

**A signature is checked at the trait too, for the same reason and by a weaker rule.** Every other
kind of member lowers to a function, whose signature is resolved when it is hoisted; a trait's
members lower to nothing, so without a pass of their own the only thing that ever reads them is the
conformance check an `impl` runs — and a trait nobody implements could promise a type that does not
exist. What is checked is that every **name** written in one stands for something: `Self` and the
trait's own parameters stand in for themselves, since that is precisely what the signature means. It
holds for every implementing type, so nothing here can ask which one, and everything that needs to
know stays with the conformance check.

## A trait may ask for a property

A trait's members are not only methods. A **property** — a member with no parameter list, read as
`value.name` with no parentheses (`08`) — is asked for by dropping the body from its declaration
form, and supplied by an `impl` writing that body:

```
trait Sized
    size -> int

impl Sized for Box
    size -> int = self.w * self.h
```

`b.size` then reads it, `f[T: Sized](x: T) = x.size` reads it through a bound, and `s.size` on a
`*Sized` reads it through the object's table. Nothing about either dispatch path is different: a
property has a receiver, it simply never spells one, so it takes a table slot beside the methods and
a bound licenses reading it exactly as one licenses calling them. A property with a body in a trait is
a **default** on the same terms as a method's, which needed nothing added at all — a property's
declaration form already carries the body a default is.

Which kind a member is has to match between the trait and the implementation, and that is a real
check rather than a formality: a property and an associated function both have no receiver to
compare, so `size(self) -> int` would otherwise quietly stand in for `size -> int` and every reader
of `x.size` would meet a mistake somewhere else. The diagnostics name the kind for the same reason —
a missing *property* reported as a missing method sends the reader off to write the one thing that
would keep it from conforming.

What a bound licenses is behaviour, and this is where that shows: a property *is* behaviour that
happens to be spelled like a field, so a trait can promise it. A **field** stays out of reach
whatever the bounds say, because a field is layout and no promise about behaviour reaches one
(`10 §5`) — which is what the diagnostic on `x.v` says when nothing declares a property of the name.

## An `impl` is for a type, and a type is not always a name

`impl Trait for Type` names its subject with a **type reference**, not an identifier, so the types
that have no name of their own carry an implementation exactly as the named ones do:

```
impl Display for []int
    display(self, out: *Writer, fmt: FormatSpec)
        …

impl Total for [3]int
    total(self) -> int = self[0] + self[1] + self[2]
```

Nothing downstream is special about them. The members are filed under the type's owner key
(`[]int`, `[3]int`), a call finds them the way it finds a struct's, a bound is satisfied the way a
struct satisfies one, and an erasure gets a table with the same slots. The one thing that had to
differ is invisible: an owner key is what a *diagnostic* calls the type, and `[]int` is no name a
linker would take, so the members are **emitted** under the type mangled — `slice.int.total`,
`arr3.int.total`. The two spellings coincide for every type that is a name, which is why nothing
about a struct's members moved.

Two spellings of one type are still one implementation, since the key is the resolved type:
`impl Show for []int` and `impl Show for []i32` collide exactly as `int` and `i32` do. An array's
**length is part of its type**, so `[2]int` and `[3]int` are two types and may implement the same
trait differently.

Two shapes are refused, each because an implementation for it would be about nothing:

- **A memory mode** — `*Point`, `&Point`. A mode is a way of *holding* a `Point`, not a type
  beside it, and a member call already sees through one level of `*` / `&` to find the receiver's
  members (`08`). An `impl` for the mode would register members nothing could ever reach.
- **A trait object** — `*Show`. An `impl` says how one particular type behaves, and which type it
  holds is precisely what an erased value has forgotten.

Members the compiler provides are out of reach for the same reason a field is: `len` on a slice or
an array, and `bytes` on a string, are reached ahead of the member table rather than through it, so
an `impl` that declared one would register a member no reader could find. That is the built-in
counterpart of an `impl` method colliding with a struct's field, and it is reported at the
declaration for the same reason.

## An `impl` covers a generic type as a whole

A block may declare **type parameters of its own**, written where a generic function writes them —
directly after the keyword that opens the declaration:

```
impl[T] Show for Box[T]
    show(self) -> string = "a box"
```

That is one implementation for **every** `Box`, and its members are monomorphized per receiver
exactly as a generic type's own members are: `Box[int].show` and `Box[string].show` are two
functions, from one source body, instantiated the moment something calls one.

Its subject must be the type applied to the block's parameters and nothing else — each argument one
parameter, each parameter used once, all of them spoken for. `impl Show for Box[int]` is refused,
and so is `impl[T] Show for Pair[T, int]`, because **a generic type has one key for all of its
instantiations**: a type's members are filed under the name it was declared with, so an
implementation for *some* instantiations would be a second implementation for a key that holds one.
This is a **keying** rule and not an overlap rule, which is why `override` does not reach it: there
is no second key for an implementation of *some* instantiations to be filed under, so the refusal is
about what can be represented rather than about which of two candidates wins. The parameters are
matched to the arguments **by position in the subject**, not by the order they were declared in, so
`impl[X, Y] Show for Pair[Y, X]` reads as it looks.

### Conditional conformance

A bound on the block is what makes the conformance conditional:

```
impl[T: Display] Show for Box[T]
    show(self) -> string = str(self.v)
```

A `Box[int]` implements `Show` precisely when `int` implements `Display`. That question is asked one
step in and composes: under `impl[T: Show] Show for Box[T]`, a `Box[Box[int]]` conforms exactly when
`Box[int]` does, which is what makes a conditional implementation usable on nested types at all.
Everything that asks whether a type conforms asks it the same way — a generic function's bound, an
erasure to a trait object, `print` reaching for a `Display` — so an instantiation that fails the
condition is refused at each of them, while its siblings are not.

What the bounds buy beyond deciding conformance is that **the members become checkable at their
definition**. A block states what it assumes of its parameters, so its bodies are walked once,
against those bounds alone, by the same definition-time pass `14 §4` runs over every bounded generic
— and a method calling something no bound licenses is reported on its own line, with nothing
instantiated:

```
impl[T] Show for Box[T]
    show(self) -> string = self.v.show()    // error: 'show' needs 'T: Show'
```

A generic *type's* own members are checked the same way and against the same kind of bound, written
on the type's own parameters instead of on a block's (`10 §5`). The two forms of "what this
declaration assumes" are one rule reached from two places.

`Self` inside such a block is the subject applied to its parameters, which is not a type until an
instantiation says what they are — so it is resolved alongside them rather than ahead of them, and
`-> Self` and `-> Box[T]` are the one signature conformance compares. The same now holds inside a
generic type's *own* body (`08`), which had been the one place `Self` named nothing.

## An `impl` covers a shape the same way

A composed type has no name to be generic over, but it has a **shape**, and a block with type
parameters may match that instead:

```
impl[T: Display] Show for []T
    show(self) -> string = str(self.len)

impl[T] Total for [3]T
    total(self) -> int = 3
```

Everything above holds unchanged. The subject is the shape applied to the block's parameters and
nothing else, so `impl[T] Show for []int` is refused (the element is fixed) and so is
`impl[T] Show for [][]T` (the element is a shape rather than a parameter). The members are
monomorphized per receiver, a bound makes the conformance conditional and composes one step in, and
the bodies are checked once at their definition against the bounds alone.

Two things are the shape's own. **A composed type is filed under the whole of itself** — `[]int`,
not `[]` — so a shape needs a key that the types it covers do not have, and dropping the arguments
is what makes one; a lookup that finds nothing under the type's own key falls back to it. And
because an **array's length is not something a parameter can stand for** (`10 § Open d` — const
generics are not in the language), the length stays part of the shape: `[2]T` and `[3]T` are two
shapes, each covering every element type at its own length.

A `string` is **not** covered by `[]T`. It is a view of bytes that are valid UTF-8, and that
invariant is the whole difference between it and a `[]u8` — a block written for every slice has said
nothing about it. `"hi".bytes` is a `[]u8` and is covered.

### One implementation per type, unless the second one says `override`

**Designed, not yet built.** What follows is the decided rule; the compiler still refuses every
overlap, which is the first half of it.

`impl Display for []int` and `impl[T] Display for []T` would both say how a `[]int` renders. By
default **whichever is written second is refused**, and the diagnostic names the one already there.
That is the rule this section used to end at, and it stays the default for a reason worth keeping:
two impls that overlap are usually a mistake — a duplicate written by accident, or one put in the
wrong module — and refusing them is how that gets found.

What the rule now admits is the case where the overlap is deliberate. An implementation may be
marked **`override`**, and then it wins:

```
impl[T: Display] Display for []T          // the library, saying how every slice renders
override impl Display for []Point         // a program, saying how its own renders
```

**The keyword goes on the overriding side, not the overridden one.** That is the whole of the
design and it is the opposite of C#'s `virtual`/`override` pair and of Rust's unstable `default`
(#31844), both of which make the general implementation grant permission in advance. Requiring
permission means a program can override only where a library author thought to allow it, and a
library author cannot know which of their implementations somebody will need to replace. Intent is
something the writer of the override has and the writer of the original does not.

What the keyword buys, given it grants no permission, is the diagnostic: an unmarked second
implementation is still refused exactly as before, so the accidental duplicate is still caught. The
only thing `override` changes is that somebody can say they meant it.

**An `override` that overrides nothing is refused**, which is the check in the other direction and
the one that earns its keep later: a library drops or narrows the implementation a program was
overriding, and without this the override silently becomes the only one while still claiming to
replace something.

**`override` is required wherever something really is an override**, which includes a member that
replaces a trait's default body:

```
trait Fallible
    failed(*self) -> bool = false

impl Fallible for File
    override failed(*self) -> bool = self.err != 0
```

A default body is arguably an invitation — the trait author wrote it knowing implementations would
replace it — and the earlier draft of this section used that to exempt it. The exemption is not
worth its inconsistency: the reader of an `impl` wants to know which members are replacing something
and which are supplying what the trait required, and that is the same question in both places. It
costs almost nothing to say so, because an implementation that is happy with a default writes no
member at all; across `lib/`, `guide/` and `examples/` exactly two members replace one.

**And it reaches the same act written somewhere else.** A type's own **inherent** member may carry a
trait member's name, and `08 §` gives it precedence — silently, which is the whole problem. Where
the trait member is a bare requirement the inherent one merely satisfies it and the keyword is
refused; where the trait member has a **default body**, the inherent member replaces it and must say
`override`, exactly as it would inside the block. The test is what the member *does*, never where it
was written.

#### Which of two implementations is more specific

`override` says a replacement was meant. It does not say *what* is being replaced, so the language
still needs an ordering, and it is deliberately a small one. For two implementations of one trait
whose heads both match some type:

1. **Structure first.** A written-out type beats a parameter at the position they differ:
   `[]Point` over `[]T`, `[][]T` over `[]U`.
2. **Then bound strength.** For two shapes of the same structure, the larger set of bounds under
   supertrait closure wins — `impl[T: Ord]` over `impl[T: Display]` where `Ord` requires `Display`.
3. **Otherwise they are incomparable**, and the overlap is refused even with `override`, because
   there is nothing for the keyword to choose between. `impl[T: Ord] Display for []T` against
   `impl[T: Hash] Display for []T` is the case: neither is a stronger statement than the other, and
   a rule that picked one would be picking by declaration order, which is not a reason.

The third case is why `override` is a statement of intent rather than a tiebreak. It cannot paper
over an ambiguity; it can only resolve one the ordering already resolved.

#### What keeps this sound, and it is not the keyword

The hazard a specialization rule usually brings is two method tables for one type — a `[]Point`
erased to a `*Display` at one site picking a different implementation than at another, which is
exactly the failure the member-name rule below is about. Here the **coherence rule closes it before
`override` is reached**. An `impl Trait for Type` may live only in the module declaring `Trait` or
one declaring a type named in `Type`, so:

- A program cannot write `impl[T: MyTrait] Display for []T` at all — `[]T` names no type of its own,
  making it an ordinary orphan. **The only override anybody can write across a module boundary is
  one that names their own type.**
- Therefore exactly one override can exist for a given type: it must live with that type.
- And any site that can write `[]Point` down already depends on the module declaring `Point`, so no
  site can erase a `[]Point` without seeing the override. One type, one table.

It also means an override can never apply to a type the library might already have instantiated
generically — `[]int` and `[]u8` belong to the library, and a program may not touch them — so a
pre-compiled library artifact (`15`) cannot disagree with an override that arrives later.

The two things that have kept this unstable in Rust for a decade are lifetime erasure, where an
implementation selected on a lifetime bound may not be the one codegen sees, and associated types,
where a more specific implementation can change a projection out from under code checked against the
general one. sysl has neither: ownership is escape analysis and reference counting (`03`, `05`), and
a trait takes parameters rather than declaring associated types.

**The cost that remains, stated plainly:** a library can no longer rely on its own implementations.
If `sysl.args` rendered through `Display for []T`, a program's override would change what its help
text does, from a module the library has never heard of. That is the price of the flexibility and it
is not bought off by anything above — what `override` buys is that every such site is greppable
rather than invisible.

The same rule reaches member *names*, and the reason is the boundary rather than the namespace. A
shape may not give a member a name that some slice written out in full already has, and vice versa —
otherwise one name on one type would mean two different members depending on which table was asked,
and a trait object's slot would be filled from the wrong one. **Two traits declaring one name are a
different case and are allowed**, because a use of one of them says which by naming the trait
(`13 §2`); what has no such answer is two *tables* that a lookup picks between without being told.

An `override` is not this case and does not relax it. An override supplies the **same trait's**
members and replaces the implementation rather than adding a second one beside it, so the type still
has one table and one meaning per name — which is why the ordering above has to settle the question
before any table is built, rather than a lookup choosing as it goes.

This is also the boundary a **second implementation of one trait** does not cross. A type may
implement a parameterized trait at more than one argument list (below), and what makes that work is
that the implementations share a namespace to be told apart in. A shape and a written-out type have
two, and a lookup takes one or the other — so a second implementation split across the boundary
would be one no call could reach, whatever arguments the two blocks wrote.

This section used to close by saying that refusing the overlap was the conservative choice, that a
rule picking between two implementations could not be walked back, and that the language would take
that step only with its eyes open when a case turned up. The case turned up: `guide/table` wanted a
slice of anything printable to render, which is one blanket implementation in the library, and the
blanket implementation shuts the door on every program's `impl Display for []TheirType` — a
documented capability with no workaround, since `[]int` names nothing of a program's and the advice
the diagnostic gave could not be taken. The step above is that step, taken deliberately.

## A trait may take type parameters of its own

A trait declares parameters in the same bracketed list every other generic declaration writes, and
an implementation says which arguments it supplies them:

```
trait Sink[T]
    put(self, x: T) -> int

impl Sink[int] for Buffer
    put(self, x: int) -> int = …
```

`Sink` is not one promise but a family of them: `Sink[int]` and `Sink[string]` say different things,
and which one a `Buffer` makes is the implementation's to state. That is the whole feature, and it
is what a trait needs before it can describe a relation between two types rather than a property of
one — what a sink accepts, what a conversion converts from, what an iterator yields.

The arguments are written in the same place in all three positions a trait is named, and they mean
the same thing in each:

- **a bound** — `f[X: Sink[int]](x: X)`, and the body's `x.put(…)` then takes an `int`;
- **an implementation** — `impl Sink[int] for Buffer`;
- **a trait object** — `&Sink[int]`, whose table is `@vt.ref.Sink.int.Buffer`.

A bound's arguments are **types**, so one may name a parameter of the declaration that wrote it:
`conv[X: Into[Y], Y](x: X) -> Y` is held to a different promise at every call, and the `Y` the call
solves is what says which. What that parameter can then do is what *its* own bounds promise, so
`[X: Into[Y], Y: Display]` is a body that may print what the conversion yields.

A trait's own parameters carry bounds too — `trait Get[T: Show]` — and everything applying the trait
supplies them, exactly as everything applying a bounded struct does (`10 §5`). It is one rule with
one diagnostic, reached from three places.

Nothing else about a trait changes. Defaults, properties, associated functions, object safety, and
conformance all read the trait's parameters as ordinary types once the implementation has fixed
them; a method written in the trait's `T` and one written in the type that `T` is are the same
signature, which is the same rule that makes `Self` and the concrete name interchangeable (`14 §1`).

### One implementation per **argument list**

A type may implement a trait once at each argument list, and the argument list is what tells two
implementations apart:

```
impl From[int]  for Celsius        // fine
impl From[real] for Celsius        // fine, and a different implementation
impl From[int]  for Celsius        // refused — that one is already there
```

This is the one place the "a trait's members become the type's, and a type's members are one
namespace" rule (`08`) is qualified, and the qualification is narrow. A `Celsius` with both blocks
above has two members called `from`; what says which a use means is the **argument list**, which
every way of reaching one already carries:

- an **operator** carries the pair of operands, which is what `14 §3` dispatches on;
- a **bound** names the arguments — `[T: Mul[f64]]` asks for that one and no other;
- a **trait object** is formed at written arguments, and its table's slots are filled from the
  implementation those arguments name;
- a **named call** passes values, whose types are the arguments.

The resolution is **determined, not preferred**. Nothing ranks two candidates: a call is answered by
the one implementation whose parameters are the types the arguments have, and a call that answers to
none of them or to more than one is reported rather than resolved. So `c.mul(2)` where the
candidates take a `Complex` and a `real` is refused — an integer literal is neither, and picking the
nearest would be a ranking over **argument lists**, which the ordering above deliberately does not
do: it orders implementations by their *subject*, and says nothing about a type implementing one
trait at several argument lists. This is why it is not general member overloading: what is being
chosen among is the implementations of **one** trait, told apart by the very thing that declares them
to be different.

Two limits fall out of "several implementations are told apart inside one namespace":

- A **property** has no arguments, so two implementations both supplying one leave nothing to select
  with, and reading it is refused.
- A **shape** and a type of that shape are filed under two different owner keys, and a member lookup
  takes one or the other and never both — so the overlap rule above stays exactly as it was, whatever
  arguments the two blocks write.

And one on the declaring side: on a **generic** subject a defaulted argument list is not one promise
but one per instantiation, since the trait's own default names the type being asked about. So
`impl[T] Mul[Box[int]] for Box[T]` is refused — at a `Box[int]` it would promise what a defaulted
block promises there, and at every other `Box` it would not. An argument built out of anything else
is fine, which is the case the feature exists for.

**A generic block may write its own parameter as a trait argument**, and this is that same
"one promise per instantiation" reading rather than an exception to it:

```
impl[T] Index[usize, T] for Buf[T]      // a Buf[int] implements Index[usize, int], and nothing else
```

Nothing about it is open, because a generic block's parameters are already exactly the arguments of
the type it is written for — each appearing in the subject, and each appearing once. A parameter an
argument can name is therefore one the subject settles, and what settles it is the same thing that
settles a defaulted `Self`. This is what lets a container carry the type of what it holds in the
trait it implements without an associated type to derive it from (`14 §7`), and it is safe for the
reason the rest of this section is: nothing ranks argument lists, so `impl Index[usize, int] for
Buf[int]` beside it is refused outright and the two never have to be chosen between. The subject
ordering that `override` introduces does not apply — these two have the same subject and differ only
in what they supply the trait.

What has not changed is what a bound means. `Mul` is `Mul[Self]` by `10 §3`'s default, so a bare
bound still names the homogeneous implementation rather than "whichever there is", and every bound
written before parameterized traits existed means what it meant. A bound asking for `From[real]` is
still not met by a type that implements only `From[int]` — the difference is that now the advice is
to write that second implementation, and the diagnostic names every one the type does have.

## Trait objects, as built

A trait object is a **fat pointer** — two words, the method table for the type it forgot and the
value itself:

```
{ ptr vtable, ptr data }
```

The sigil says who owns the second word, and nothing else changes between the two:

- **`*Trait`** — the data word is the value's own address. Raw and unmanaged, like every `*T`, and
  the reason `14 §2` reached for a `*Writer`: a sink a kernel can pass around needs no allocator.
- **`&Trait`** — the data word is the reference-counted **box** the value sits in. It counts exactly
  as the `&T` it was erased from does, because the box carries its own destructor (`03`) — so
  letting go of a trait object needs no more knowledge of the payload than letting go of a `&T`
  does, which is the same erasure ARC already relied on for a slice's owner.

**The table is per (trait, type, sigil).** Two flavours rather than one because the data word means
different things: an entry has to reach a receiver, and from a box that is one step further in than
from a bare value. Where the data word already *is* the receiver an implementation declared — a
`*self` method under `*Trait`, a `&self` method under `&Trait` — the entry names the implementation
itself; otherwise it names a small adapter that steps over the box header, loads the value, or both.
So the common case costs one indirect call and nothing else.

### Object safety

Erasure forgets the type, so a method may promise nothing that depends on knowing it. A trait may be
made into an object when every method:

- **has a receiver.** An associated function has nothing to dispatch on. A property does have one —
  by value, and unwritten — so a trait that asks for a property is as safe to erase as one that asks
  for a method.
- **mentions `Self` nowhere but that receiver.** A second `Self` would have to be the *same*
  forgotten type as the first, which is exactly the fact an object no longer carries, and a `Self`
  result has no size to hand back.
- **does not take `&self`, for a `*Trait` only.** `&self` asks for its receiver inside a box, and a
  raw object points straight at a value. A `&Trait` carries one, so it accepts such a method; the
  diagnostic says which sigil to write.
- **takes no `...`.** A call to a variadic names the callee's *whole* function type, because that is
  how it says where the declared parameters stop and the tail begins (`12 §9`); a slot in a table is
  one word and names none. A bound still reaches such a method, since that call knows which function
  it is reaching — so a variadic trait method is for bounds, exactly as a `Self`-mentioning one is.

The middle rule excludes **every trait in the operator catalog** — `add(self, rhs: Self) -> Self`
first among them — and that is the right answer rather than a limitation: `14`'s traits describe an
operator over two values of one type, which is a question about types known at compile time. They
are for bounds.

Every type reaching a table also got there through a **source `impl`**, so every slot is a function
that exists — but that is a rule of its own rather than a consequence of the one above, and
`Display` is why it has to be. `Display` is compiler-provided (`14 §5`) *and* object-safe, so
nothing in the list excludes it: an `int` satisfies a `Display` bound and `print` finds its
rendering, and only the erasure refuses it. So the refusal is made where the erasure is, and the
diagnostic names the rule rather than reporting a type mismatch — the membership is real, and a
reader told otherwise would go looking for a conformance they already have.

### Forming and using one

Erasure is a **coercion**, applied wherever a trait-object type is expected: at an argument, a
declared variable, an assignment, a returned value, an array element, a struct field. `&r` erases to
`*Shape`; a `&Rect` erases to `&Shape`; and a plain `Rect(3, 4)` where a `&Shape` is expected is
boxed and then erased, which is the ordinary "write the construction and it is allocated" rule of
`03` with one more step. A `*Trait` will not take a bare value — a raw pointer needs an address, and
taking one of a temporary silently is how a program acquires a dangling pointer.

Because the coercion applies per branch, an `if` or a `match` whose arms are *different concrete
types* meets at one trait object, which is the point of having them.

The library's **`Writer`** is the first trait the language itself forms objects of: `Display` renders
into a `*Writer` so that writing text costs no allocation (`14 §2`), and a program supplies its own
sink with an ordinary `impl`. It is also the one trait the compiler knows a *contract* about beyond
its signatures — a writer borrows the bytes it is handed rather than keeping them — and that is
checked against every implementation rather than assumed (`05`). Nothing about the dispatch changes;
what the identity buys is the escape analysis being able to let a stack-backed slice through.

What an object still offers is the trait's methods, and nothing else: no dereference, no fields, no
comparison (two objects over one value through different traits are the same value and different
tables, so what equality means is the trait's question). A call is checked against the **trait's**
signature, which stands in for every implementation because conformance is exact.

### An object keeps one trait and what that trait requires

A bound may name several traits because a bound is a list; a trait-object type names one because it
is a type. So a value that implements `Shape` *and* `Display` keeps only the first when it becomes a
`&Shape` — unless `Shape` **requires** `Display`, which is what a supertrait is for and what makes
the difference between the object being printable and not.

That the two are the same question is worth seeing. A bound may say what it wants of the type it is
given; an object type may not, because it is a type and a type is one name. So the only place the
second promise can be written is on the **trait**, once, rather than at every use — which is exactly
what a required trait is. A multi-trait object type (`&(Shape + Display)`) would be a second way to
say the same thing and a worse one, since it puts at every use a fact that belongs to the trait.

Where a trait requires nothing, the old cost stands and is worth stating plainly: **erasure is paid
for in capabilities and not only in dispatch cost**, and it is charged at the moment a program stops
knowing the type — which is exactly the moment it most wants to describe what it is holding. The
diagnostic names the fix rather than the symptom, since a word on the declaration is what is wanted:

```
cannot make a string of a &Shape value — an object offers what its trait declares and what
that trait requires, so write 'trait Shape: Display' to keep the rendering the value had
before it was erased
```

`guide/shapes` is the record of both states. It used to declare a `render` member the trait should
not have needed *and* a `describe(s: &Shape)` gathering it back into a string, because an erased
shape could not be handed to `str`. With `trait Shape: Display` the `describe` is gone and `str(s)`
is the whole of it; `render` stays, because rendering the parts and placing them in the field a
specifier asked for are two operations and the padding is worth writing once.

### The two sigils do not convert

`*Trait` and `&Trait` are two types and neither is accepted for the other. In the direction that
would matter — lending a counted object to something that only wants to ask it questions — this is
sharper than it is for plain references, which have a spelling for it. `&*r` is the address of the
place `*r`, so a `&T` reaches a function written against `*T` with the crossing into the unsafe tier
written down at the call, which is the discipline `03` is built on. An object has no dereference, so
`&*o` says nothing, and a function that only reads a shape has to exist once per sigil.

This is recorded as a gap rather than settled either way. The lend is meaningful — a fat pointer's
two words are the same two words whichever sigil owns the second — and refusing it is not protecting
anything, since the raw object is the weaker capability. What is missing is only a spelling, and any
spelling must keep the property that makes `&*` acceptable: the crossing is greppable. Not chosen
here, because the shape of it should be decided together with whatever `03` grows for the same
question on plain references, if anything.

The other direction stays refused for a stronger reason and needs no further thought: a raw object
points at a value with no count to take a share of, so accepting one where a counted object is
wanted would be inventing ownership.

### There is no way back to the type

An object cannot be asked what it forgot. There is no cast and no test that would express one. What
there also is not is a *diagnostic* about downcasting, and that is worth stating exactly, because the
shape of the refusal is not the one this section originally claimed. A type is not a pattern — so a
type's name in pattern position is an ordinary **binding**, and `s is Circle` parses, binds every
`&Shape` to a new name called `Circle`, and is refused as a test that is always true. The outcome is
the one wanted and the diagnostic is better than a bespoke one would have been, since it also catches
the reader who meant a binding; but nothing here rests on the form failing to parse. **This is a
decision, not an omission.** A downcast is the one operation that makes erasure a lie: every other
rule here says an object offers the trait's members and nothing else, and a type test would say that
it also secretly offers its identity, which is what the vtable pointer is and what the type deliberately
stops promising. Languages that offer it need a whole parallel mechanism to do so (Rust's `Any`, and
the `'static` bound that comes with it), and that mechanism is the honest price — not a small
addition to this one.

The cost is real and should be written down beside the decision. A program that wants to count the
circles in a catalogue has to be told, so `guide/shapes` declares a `kind` property that every
implementation answers with a constant — a hand-maintained copy of exactly the fact the object's
first word already is, which has to be extended by hand every time a shape is added. If that burden
ever justifies the machinery, the thing to add is the parallel mechanism, not a hole in this one.

## A trait may require another trait

`trait Word: Add + BitXor` — written after the name, with the same `:` and the same `+` a bound on a
type parameter uses, because it asks the same thing of the implementing type. A generic trait writes
both, the parameters first: `trait Convert[U]: Into[U]`, whose requirement is read under whatever
arguments the trait was applied to.

A required trait is a promise the **trait** makes rather than one each declaration repeats, and that
is what separates it from the `where` clause `10 § Open c` defers and from the bound alias the hash
map asked for and this document refused. `[T: Word]` then licenses the arithmetic; a **default body**
in `Word` may use it, since what a default may assume is exactly what its trait promises; and — the
case a bound alias could never have reached — a `&Word` object carries the required traits' members
in its table.

**The requirement is checked at the `impl`, not at the bound.** Two reasons, and the second decides
it. The diagnostic belongs on the declaration that cannot keep its word:

```
'Greet' requires 'Named', so 'P' has to implement that too — write 'impl Named for P'
```

And a `&Sub` object's table needs a slot for every required trait's method, so a requirement checked
only where the trait is *used* could leave a table with nothing to point at. Checking at the `impl`
is also what keeps `conforms` a plain lookup: by the time anything asks whether a type implements a
required trait, an implementation of it is already registered. A **built-in** membership counts —
`impl Word for i32` is asking `i32` for arithmetic it has always had — and the question is held until
every `impl` is registered, since the block that supplies a required trait may be written below the
one that needs it.

### The table carries the required trait's slots

A trait's members are the required traits' members, depth-first with each trait taken once, followed
by its own. Both the table and the call sites that index into it are laid out from that one list, so
a required trait's method is **one indirect call**, exactly like the trait's own. The alternative —
a word in the table pointing at the required trait's own table — costs a second load on every such
call and buys one thing sysl does not have: an upcast.

So **a `&Sub` cannot become a `&Super`**, and that is the price of the choice rather than an
oversight. The slots are there and no word names them as a table of their own. Nothing is unwritable
for want of it: what a program does with a required trait is call its members, which works. If an
upcast is ever wanted the extension is additive — a table for the required trait, and a pointer to
it appended *after* the slots, so no index moves.

The price is smaller than it first reads, because the case that would have wanted an upcast mostly
wants a **bound**, and a bound an object satisfies (`10 §5`). A function written `[T: Super]` takes a
`&Sub` — the slots it needs are in the table, and finding them is what the closure the table was laid
out from already did. What stays refused is the *conversion*, and the two are worth keeping apart: a
bound asks what may be called through the value, which the table answers; forming a `&Super` asks
what may be assembled from the value's type, and an object has no implementations to assemble from.

The diamond needs no rule of its own. `D: A + C` with both `A: B` and `C: B` carries `B`'s members
once, because the walk takes each trait the first time it reaches it. What *is* refused is two traits
in one closure declaring a member of the same name — and the reason is the **table**, not the
namespace. Two unrelated traits may each name a member of one type, because a call says which by
naming the trait (`13 §2`); two traits inside one requirement closure are laid out as one table,
and a call through a `&Sub` has already forgotten everything that could have said which slot it
meant.

```
'R' and 'L' both declare 'len', and a trait's members become the implementing type's — so
'Both' cannot require both
```

A trait may not require itself, directly or around a cycle; and a trait may not require one that
reaches less far than it does (`13 §2`) — implementing the trait
means implementing the required one, so a requirement the implementer cannot name leaves the trait
unimplementable from outside.

**`Self` in a requirement's arguments is the type implementing the requiring trait**, exactly as it
is in a method's signature — `trait Vector: Scale[Self]` asks that whatever implements `Vector` can
be scaled by its own type, and it is the same requirement `trait Vector: Scale` writes when `Scale`
defaults its parameter to `Self` (`10 §1`). It was once refused as meaningless, on the reading that a
trait requires another of *whatever* type implements it; that is true and it is exactly why `Self`
names something there. Being one requirement however it is spelled is what keeps the flattened table
from laying out two slots for one member: two spellings of one requirement are compared as the types
they resolve to, so `Scale[Self]` and a defaulted `Scale` are the same row, while `Scale[Self]` and
`Scale[int]` are the two the coherence rule refuses in advance.

One thing follows from the table and is worth stating: a trait that requires an **unerasable** one is
unerasable itself, so `trait Word: Add` has no object, and the diagnostic names `Add` as the trait
the offending member came from. And a **built-in** that satisfies a requirement by the compiler's
rule cannot be erased at all — a table holds function pointers and a scalar's `add` is an
instruction, which is the sentence *Object safety* already carried, now reachable through a required
trait rather than only through the operator catalog.

## Kept / dropped

- **Kept:** static dispatch (monomorphized bounds), dynamic dispatch (boxed trait object),
  retrofitting foreign types (explicit `impl`), default bodies, properties as members, an `impl` for
  any concrete type — named or composed — and an `impl` for a generic type or a composed shape,
  conditionally.
- **Dropped:** the separate structural `interface`; implicit/structural conformance; the
  invisible `owns` flag (replaced by explicit three-mode ownership).

## Reaching a trait's members without a value

A trait may declare a member with **no receiver** — an associated function — and it is reached
through the *type* rather than through a value of one:

```
trait Word: Add + Shl + Shr
    bits() -> Self
    zero() -> Self

    shift_in(self, b: u8) -> Self

rotr[T: Word](x: T, n: T) -> T = (x >> n) | (x << (T.bits() - n))
```

`T.bits()` inside a generic body, `Self.bits()` inside a member's, and `u32.bits()` from anywhere
are the same member reached through three spellings of its type. Nothing about the declaration is
new — an associated function is what `08` already calls a member with no `self` — and what changed
is where the name in call position may come from.

**A built-in may carry one.** The rule that refused it said only a struct or an enum has a name a
call reaches through, and that was about the call site rather than about the `impl`: through a bound
the name is the *parameter*, which every type has whether or not it has one of its own. So
`impl Word for u32` may declare `bits()`, and `impl Float for real` declares the zero, the one, the
epsilon and the two values no literal spells. What is left of the rule is the case it was really
about — a **composed** type has no name at all, so an `impl` for `[]int` still refuses a member with
no receiver, and says which half of the requirement it fails.

**It is static dispatch only, and nothing was added to keep it that way.** Object safety
(§ Object safety) already excludes a member with no receiver, because a table slot is selected *by*
the receiver and there is nothing here to select with. A trait that declares one is usable as a
bound and not as an object, exactly as one that mentions `Self` twice already is.

**What it does not answer** is the other half of `14 §7`'s "a bound that promises a value": nothing
in the operator catalog declares a zero, so `total[T: Add](xs: []T) -> T` still cannot start its
accumulation. That is now a question about what the catalog declares rather than about whether the
language can express it — a `Zero` trait with `zero()` is an ordinary trait today, and what is not
decided is whether the compiler should supply the membership for the open `iN`/`uN` families the way
`14 §8 a` does for the operators.

A related gap the mechanism makes visible: a routine can now be entirely *about* a type and mention
it nowhere in its signature, and such a function cannot be called, because inference reads the
binding and a call cannot write its type arguments (`10 § Open a`). `describe[T: Word]() -> string`
is well-formed and unreachable. Taking a value of `T` is the workaround, and it is the same one the
type-argument entry already records.

## Details still to settle

- ~~**Whether a built-in should be erasable into a `&Display`.**~~ **Settled: yes, and it needed no
  synthesis.** The heading asked whether the compiler should manufacture table slots for a
  membership it provides by rule. The answer was to stop providing it by rule. `bool`, `char`,
  `string` and the two floats are a finite list, so the library writes them ordinary blocks; the
  `iN`/`uN` families are open, so one **blanket** block covers them — `impl[T: Integer] Display for
  T`, sized from `sizeof(T)`. A heterogeneous `[3]&Display` of `1`, `"hi"`, `true` is ordinary code,
  and *Object safety*'s rule is untouched: a slot still holds a function some block wrote.

  What made this the harder question it looked was that the integers have no finite list of types
  to write blocks for, so the choice appeared to be between a rule and nothing. A block written over
  a **bound** is the third option, and what it costs is that the bound must be one nothing outside
  the compiler can join — see `14 §5`.

  `Hash` is the one membership still provided by rule *and* object-safe, so it is the case the
  refusal now speaks about, and the last one.
- **Laws / invariants on traits.** The old `trait` could assert invariants ("`Ord` is a total
  order"). Whether the unified trait carries such contracts (via `require` / `ensure`-style
  annotations) is deferred to the contracts spec.
- **Associated types.** A trait's own parameters (above) cover much of what an associated type is
  for — `Sink[T]`, `Into[T]` — with the difference that an argument is written by everything that
  names the trait rather than chosen once by the implementation. Which of the two a language wants,
  and whether it wants both, is a real question and is not answered here.
- **Choosing between implementations of one trait for one type.** ~~Refused above, because a type's
  members are one namespace.~~ **Answered.** `From[int]` and `From[real]` may both be implemented for
  one type: the members are filed under names that differ, and a use says which it means by the
  arguments it wrote — which is the last of the three steps `13 §2` lays out, the other two being
  the bound and the trait's scope. What `11 § Open a`'s `?`-conversion was waiting on is therefore
  no longer this.
- **A trait method with type parameters of its own.** An inherent member may declare them (`08`,
  `10 §4`); a trait's may not, and so neither may an `impl`'s, which must match what the trait
  declares. The refusal is a **decision**, and the diagnostic says so — it names the table slot that
  could not hold such a member rather than a feature the compiler has yet to write, and it points at
  the inherent member that may declare them. It is also what closes `12 § Open b`'s second half: a
  closure's type is a call trait, so a closure cannot be generic either, for this reason and not for
  a reason of its own. Three questions come with allowing it and none is answered here: how a conformance
  comparison treats two signatures whose parameters are spelled differently, what a default body may
  assume of them, and — the one that decides the shape of the rest — that a generic method makes a
  trait unusable as a trait object, since no vtable slot can hold a function that does not exist
  until a call names its types. Refusing at the declaration is what keeps that from being discovered
  at the `*Trait` instead.
- ~~**Specialization.**~~ **Decided, and designed above.** This entry said that letting a written-out
  type beat a shape was the one relaxation here that would be genuinely useful, and that it was not
  done because such a rule is easy to add and impossible to remove. It is now done, on the terms the
  section states: the overlap is still refused by default, the second implementation must say
  `override`, and the ordering is small enough to be written in three lines. What made it affordable
  was not a change of mind about reversibility — it was noticing that coherence already confines a
  cross-module override to a type of the overriding module's own, which is what rules out the two
  tables and the stale instantiation that make specialization hard elsewhere.
- ~~**A property's body must be an expression.**~~ **Built** — a property takes the `funcBody` a
  method takes, so `= expr`, an `=` opening a block, and a bare block are all spellings of it, in a
  trait's default and in a type's own body alike. It was additive exactly as this item predicted, and
  it was never a decision about traits or about properties: it was one parser alternative narrower
  than the member beside it. See `08 § Properties`.
- ~~**`&Trait` is not yet gated on `alloc`.**~~ **Built, and it needed no rule of its own.**
  `capabilities.md` puts a counted trait object behind the allocator capability alongside `&T`
  itself, and this item said neither was gated. Both are: an erasure sits on top of the box the `&T`
  already is, so the one gate on `&T` answers for the trait object one step earlier and there is
  nothing about `&Trait` for the capability pass to know. Pinned by `CapabilityClauseTests`' *"a
  boxed trait object, which is a reference underneath"*, which is written as a trait object
  precisely so that the shared gate cannot be removed without a trait-object test failing.

  **The item's own reasoning is worth keeping, because it was right about the shape and wrong about
  the state.** It said the gate waited on an implementation rather than a decision, and the
  implementation it waited on turned out to be the `&T` one — which is what "the same gap `&T`
  already has rather than a new one" had already said, one step short of the conclusion that closing
  the one closes the other.
