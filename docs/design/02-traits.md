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
  cannot cover `int` is a `Show` no library can be written against, and the prelude's own `Show` —
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

**An `impl Trait for Type` may appear only in the module that declares `Trait`, or the module that
declares `Type`.** Resolving a bound therefore inspects exactly those two modules, both of which
any module naming both the trait and the type already depends on. No global search, and no
dependency edge that the source does not show.

This is Rust's orphan rule, and it costs nothing the chapter already promised:

- **Retrofitting still works.** `impl MyTrait for TheirType` lives with `MyTrait` — the trait's
  module licenses it. That is the retrofitting kept above, unchanged.
- **`impl Show for int` still works**, and it is the prelude's `Show` that makes it legal: a
  built-in has no module of its own, so a built-in type's owner key belongs to the prelude, and
  every `impl` on one is licensed by its trait's module instead. A trait that could not cover
  `int` is a trait no library can be written against.
- What it forbids is the case with no home: **a foreign trait implemented for a foreign type**,
  where two unrelated modules could each supply a different `impl` and no rule picks one.

An `impl` is part of its module's public surface. Adding, removing, or changing one is an
interface change to that module, visible to everything downstream — the same reasoning that put
`given`/`using`-style implicit resolution out of scope (`13` §7): unrestricted search and separate
compilation are not compatible.

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
would have broken every `impl` — and the prelude now uses a default itself, for `Writer.failed`
(most sinks cannot fail, and one that cannot should not have to say so).

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
with no implementations therefore gets its bounds checked and nothing more, which is the same reach
the definition-time pass has over a generic function nothing instantiates.

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
Overlapping implementations, and the specialization rule that would be needed to pick between them,
are deliberately not in the language. The parameters are matched to the arguments **by position in
the subject**, not by the order they were declared in, so `impl[X, Y] Show for Pair[Y, X]` reads as
it looks.

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

### One implementation per type, so a shape and a written type may not overlap

`impl Display for []int` and `impl[T] Display for []T` would both say how a `[]int` renders. Neither
is more specific than the other — being more specific is not something sysl knows how to be, since
there is no specialization rule and deliberately no place to add one — so **whichever is written
second is refused**, and the diagnostic names the one already there. The choice is between saying
how every slice behaves and saying it slice type by slice type; not both.

The same rule reaches member *names*, because a type's members are one namespace whatever trait
brought them (`08`). A shape may not give a member a name that some slice written out in full
already has, and vice versa — otherwise one name on one type would mean two different members
depending on which table was asked, and a trait object's slot would be filled from the wrong one.
Two traits with distinct member names are unaffected, exactly as they are for a struct.

Refusing the overlap is the conservative choice and can be relaxed; shipping a rule that picks
between two implementations cannot be walked back. If a case turns up that genuinely wants
`[]byte` to render differently from every other slice, the language would be adding specialization
with its eyes open, rather than discovering it had one.

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

### One implementation per type, whatever the arguments

A type may implement a trait **once**, and the trait's arguments do not make a second one a
different implementation:

```
impl From[int]  for Celsius        // fine
impl From[real] for Celsius        // refused
```

The reason is the rule the rest of this chapter already rests on: **a trait's members become the
type's members, and a type's members are one namespace** (`08`). Two implementations of `From` would
give a `Celsius` two members called `from`, and `c.from(x)` would have no way to say which — the
same collision that stops two traits declaring `show` from both being implemented for one type, and
the same one that stops a shape and a written type from overlapping.

So the trait's arguments are what an implementation *supplies*, not part of what it is filed under.
A bound asking for `From[real]` is not met by a type that implements `From[int]`, and the diagnostic
says so in those words — there is no second `impl` to write, and telling someone to write one would
be sending them at a block that will be refused.

Relaxing this needs a way to say which implementation a use means: overload resolution on the
argument types, or a syntax that names the trait at the call. Both are additive, and neither is
worth guessing at before a program wants one. It is the same conservative choice the overlap rule
makes, for the same reason — refusing is reversible.

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

The middle rule excludes **every trait in the operator catalog** — `add(self, rhs: Self) -> Self`
first among them — and that is the right answer rather than a limitation: `14`'s traits describe an
operator over two values of one type, which is a question about types known at compile time. They
are for bounds. It also means every type reaching a table got there through a source `impl`, so
every slot is a function that exists — a compiler-provided membership (`14 §5`) is an instruction,
not something a pointer can name.

### Forming and using one

Erasure is a **coercion**, applied wherever a trait-object type is expected: at an argument, a
declared variable, an assignment, a returned value, an array element, a struct field. `&r` erases to
`*Shape`; a `&Rect` erases to `&Shape`; and a plain `Rect(3, 4)` where a `&Shape` is expected is
boxed and then erased, which is the ordinary "write the construction and it is allocated" rule of
`03` with one more step. A `*Trait` will not take a bare value — a raw pointer needs an address, and
taking one of a temporary silently is how a program acquires a dangling pointer.

Because the coercion applies per branch, an `if` or a `match` whose arms are *different concrete
types* meets at one trait object, which is the point of having them.

The prelude's **`Writer`** is the first trait the language itself forms objects of: `Display` renders
into a `*Writer` so that writing text costs no allocation (`14 §2`), and a program supplies its own
sink with an ordinary `impl`. It is also the one trait the compiler knows a *contract* about beyond
its signatures — a writer borrows the bytes it is handed rather than keeping them — and that is
checked against every implementation rather than assumed (`05`). Nothing about the dispatch changes;
what the identity buys is the escape analysis being able to let a stack-backed slice through.

What an object still offers is the trait's methods, and nothing else: no dereference, no fields, no
comparison (two objects over one value through different traits are the same value and different
tables, so what equality means is the trait's question). A call is checked against the **trait's**
signature, which stands in for every implementation because conformance is exact.

## Kept / dropped

- **Kept:** static dispatch (monomorphized bounds), dynamic dispatch (boxed trait object),
  retrofitting foreign types (explicit `impl`), default bodies, properties as members, an `impl` for
  any concrete type — named or composed — and an `impl` for a generic type or a composed shape,
  conditionally.
- **Dropped:** the separate structural `interface`; implicit/structural conformance; the
  invisible `owns` flag (replaced by explicit three-mode ownership).

## Details still to settle

- **Laws / invariants on traits.** The old `trait` could assert invariants ("`Ord` is a total
  order"). Whether the unified trait carries such contracts (via `require` / `ensure`-style
  annotations) is deferred to the contracts spec.
- **Associated types.** A trait's own parameters (above) cover much of what an associated type is
  for — `Sink[T]`, `Into[T]` — with the difference that an argument is written by everything that
  names the trait rather than chosen once by the implementation. Which of the two a language wants,
  and whether it wants both, is a real question and is not answered here.
- **Choosing between implementations of one trait for one type.** Refused above, because a type's
  members are one namespace. Allowing `From[int]` and `From[real]` on one type needs a way for a use
  to say which it means; that is what `11 § Open a`'s `?`-conversion is waiting on, not on generic
  traits themselves.
- **A trait method with type parameters of its own.** An inherent member may declare them (`08`,
  `10 §4`); a trait's may not, and so neither may an `impl`'s, which must match what the trait
  declares. Three questions come with allowing it and none is answered here: how a conformance
  comparison treats two signatures whose parameters are spelled differently, what a default body may
  assume of them, and — the one that decides the shape of the rest — that a generic method makes a
  trait unusable as a trait object, since no vtable slot can hold a function that does not exist
  until a call names its types. Refusing at the declaration is what keeps that from being discovered
  at the `*Trait` instead.
- **Specialization.** A shape and a type of that shape written out in full are refused as the two
  implementations for one type they are, as above. Allowing both and letting the written one win is
  the one thing here that would be genuinely useful (`[]byte` rendering differently from every other
  slice) and is deliberately not done: a rule for choosing between two implementations is easy to
  add later and impossible to remove.
- **A property's body must be an expression.** `name -> T = expr` is the only spelling, so a property
  cannot open an indented block the way a method's `= …`-less form can. That is `08`'s grammar rather
  than anything about traits, and it bites a default property the same way it bites an inherent one.
  Additive: the property form wants the `funcBody` a method already uses.
- **`&Trait` is not yet gated on `alloc`.** `capabilities.md` puts a counted trait object behind the
  allocator capability, alongside `&T` itself. Neither is gated, because the capability system needs
  the project config and the module system, and both are still to be written — so this is the same
  gap `&T` already has rather than a new one.
