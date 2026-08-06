# Methods and Associated Functions

**Status:** decided. This closes design gap #2. `02-traits.md` settled how a type conforms to a
trait but deliberately left one hole open: how a plain method is declared, and how the receiver
is spelled. That hole blocks everything — `.len` and `.bytes` shipped as builtin fields only to
defer it, and every string operation that makes bytes (`s.copy()`, `string.from_utf8`,
`str.builder`) needs the *spelling* to exist before it can be written at all. This document
fills it. It covers **inherent** members — the ones a type declares for itself. Trait members
(`impl Trait for Type`) and dynamic dispatch stay in `02`.

## The decision in one paragraph

A type's methods, properties, and associated functions are declared **in its body**, alongside
its fields — Swift's and Kotlin's placement, not Rust's separate `impl` block. An instance
member names its receiver as its first parameter, spelled with a **memory-mode sigil**: `self`,
`&self`, `*self`, or `&sync self`, the same three modes every other value has. A member with a
parameter list but no `self` is an **associated function**, called `Type.name(...)`. A member
with **no parameter list at all** is a **computed property**, read without parentheses — which
is exactly what lets `s.len` and `s.bytes` become real members without a single call site
changing.

## Where members live

A `struct` or `enum` body already holds its fields or variants. It may now also hold member
declarations, and the two kinds may appear in any order — though the convention this document
follows is data first, behaviour after.

```
struct Point
    x: int
    y: int

    dist(self) -> real = sqrt(f64(self.x * self.x + self.y * self.y))

    translate(*self, dx: int, dy: int)
        self.x += dx
        self.y += dy

    origin() -> Point = Point(0, 0)
end Point
```

A member is told apart from a field by what follows its name, with no new keyword:

| Form | What it is | Called as |
|---|---|---|
| `name: type` | a **field** | `p.name` |
| `name -> type …` (no parens) | a **property** | `p.name` |
| `name(self…) …` | an **instance method** | `p.name(…)` |
| `name(params…) …` (no `self`) | an **associated function** | `Point.name(…)` |

The bodies are the ones functions already have (`03`, `07`): an `= expr` short form or an
indented block whose trailing expression is the value. Nothing about a member's body is new — that
holds for a property as much as for a method, since a property is a method with the parameter list
left off — only where it is written and what its first parameter may be.

### Why the type body, not an `impl` block

Rust puts methods in a separate `impl Type` block; Swift, Kotlin, and Scala put them in the
type body. Principle 2 makes Swift the DNA match, and the three-to-one split among the
inspirations points the same way, so the body it is. A reader sees a type's data and its
behaviour in one place, which is the object-oriented default and what most programmers reach
for without being taught.

The one thing an `impl` block buys that the body does not is **splitting a type's methods across
several blocks or files**, including adding methods to a type you do not own. sysl gives that up
for inherent methods on purpose — a type's own behaviour lives in one place — and keeps the
capability where it actually matters through **trait conformance**: `impl Trait for Type` (`02`)
is declared outside the body and may target a foreign type, so retrofitting behaviour onto a
type you did not write is still expressible, just as a trait `impl` rather than a loose method.
This is Swift's shape exactly: methods in the body, plus conformances declared elsewhere.

## The receiver

An instance member's first parameter is the receiver, and it is spelled `self` with the mode
sigil written in front, never a `name: type` binding. The sigil is not decoration — it is the
same choice every parameter makes, and it says how the method takes the instance:

| Receiver | Desugars to | The method gets | Use it for |
|---|---|---|---|
| `self` | `self: T` | a **copy** of the value | reads that do not need the original (`dist`) |
| `*self` | `self: *T` | a **raw pointer** to the instance | mutating a value you hold on the stack |
| `&self` | `self: &T` | the **ARC reference** | operating on a shared heap object |
| `&sync self` | `self: &sync T` | the atomic ARC reference | the same, across threads (`06`) |

A `&sync self` is a `&sync T` like any other, so it is held to what `06` asks of a shared object:
a member written with that receiver on a type nothing may share is a member no call could reach,
and it is refused at the receiver rather than at the first call site.

There is no `&mut self` and no `mut` anywhere, because sysl has no borrow checker to make one
mean something. Mutation of a receiver is mutation through a place or a pointer, exactly as it
is everywhere else in the language: `*self` hands the method a raw pointer whose pointee it may
write, which is the method form of the `bump(n: *int)` idiom already in the language; `&self`
hands it the shared heap object, whose fields it may write so that every holder sees the change.
A `self`-by-value method that assigns to `self.x` changes only its copy — the same rule a
by-value parameter has always had.

`self` is a reserved word. It names the receiver inside an instance member and is legal nowhere
else, so it cannot be shadowed or used as an ordinary variable.

## Calling a method

`p.dist()` looks `dist` up as a member of `p`'s type and calls it with `p` as the receiver.
Passing the receiver follows the **same conversions a matching argument would**, and the
method-call site inserts them so the three modes stay as easy to reach as C's (principle 3):

- A **value** receiver (`self`) takes a copy. Call it on a `Point`, on a `&Point` (the object is
  loaded), or on a `*Point` (the pointee is loaded) — anything a `Point` value can be produced
  from.
- A **`*self`** receiver takes the instance's address. Call it on any place — a `var`, a field, an
  array element — and its address is taken automatically, the way `bump(&counter)` takes one by
  hand; call it on an existing `*Point` and the pointer passes straight through. Calling a `*self`
  method on a temporary that has no address is an error, because there is nothing to point at.
- A **`&self`** receiver needs the ARC reference itself. Call it on a `&Point` and it passes
  through. A bare stack `Point` has no reference — there is no box behind it — so this is an
  error that points you at `*self` for a stack value or at putting the object behind a `&`.

This extends the one-level auto-deref that field selection already does (`03`): `p.field` reads
through a `&Point` or `*Point` without a written `*`, and `p.method(…)` does the matching thing
for the receiver mode the method asked for. It stays one level: a `**Point` is not walked for
you.

## Properties

A property is a member with **no parameter list** — not even `()` — and a body that computes a
value. It is read with no parentheses, so it reads exactly like a field:

```
impl string          // built-in; shown for the shape, see "Built-in members" below
    len   -> usize = …
    bytes -> []u8  = …
```

```
var n = greeting.len          // a property read, no parens
var b = greeting.bytes
```

A property's receiver is an implicit **borrow** — it reads the instance and does not consume or
mutate it, so no sigil is written and none is needed. Inside the body, `self` is in scope and
refers to the instance. A property is read-only for now; a settable property (a paired getter
and setter, so `p.name = v` runs code) is a later addition and is noted under *Not yet*.

**A property's body is the body a method has**, so all three spellings are available: `= expr`, an
`=` opening an indented block, or a block with no `=` at all, with the trailing expression as the
value either way and an optional `end <name>` closing it.

```
struct P
    x: int
    y: int

    biggest -> int
        var m = self.x
        if self.y > m then m = self.y
        m
end P
```

There is nothing in a property that wants a narrower body than a method's — a property *is* a
function with the parameter list left off — and having only the one-expression form made it the one
member whose body could not be written out. That bit a **default** property in a trait as hard as an
inherent one, which is where `02` recorded it.

Dropping the body leaves the **signature** form, `name -> type`, which is what a trait writes to ask
an implementation for a property (`02`). That the receiver is unwritten changes nothing about the
member being an instance member: it dispatches through a bound and through a trait object's table
exactly as a method does, and the parentheses are the only thing missing at either end.

The point of the no-parens form is honesty about cost that a systems reader depends on: `s.len`
is O(1) and reads like the field it nearly is, while `s.copy()` allocates and is written with
the parentheses that mark a call. A property should therefore be cheap — a field-like projection
— and anything that computes or allocates should be a method. This is the one convention the
property form asks of the programmer; the compiler does not enforce it, exactly as Swift's does
not.

This is what the plan called for: `.len` and `.bytes` were builtin fields precisely because the
member syntax to hold them did not exist yet. They are now properties, and the call sites that
were already written `s.len` and `s.bytes` do not change.

### Why properties at all, rather than one call syntax

The alternative is Go's: every member is a method, and a length is `s.len()`. It is a smaller
language — one call syntax, no property concept. It was rejected for two reasons. First, it
would have forced every existing `s.len` to become `s.len()`, which the plan explicitly set out
to avoid. Second, and the reason that outlasts the migration: Swift and Kotlin both keep the
property/method distinction because it *carries information* — parentheses mean "this does
something," their absence means "this is a projection of what is already there." In a language
whose memory model rests on costs being visible, throwing that signal away to save a concept is
the wrong trade.

## Associated functions

A member whose parameter list does **not** begin with `self` belongs to the type rather than to
an instance, and is called through the type name:

```
struct Point
    x: int
    y: int

    origin() -> Point = Point(0, 0)
end Point

var o = Point.origin()
```

This is the home for the named constructors the earlier docs already spell this way —
`string.from_utf8(b)` and `string.from_utf8_unchecked(b)` (`04`), `char.try(u)` (`00`) — and for
any type-level helper that does not act on an existing value.

**`Type(…)` stays the positional constructor** and is not an associated function: `Point(1, 2)`
builds a value from its fields, and `Point.origin()` calls a function named `origin` in `Point`'s
namespace. The two never collide because one is the bare type name applied to arguments and the
other is a member selected from it. A named constructor that wants validation or a default is an
associated function returning the type; the positional form remains the zero-ceremony default.

On a **generic** type the same form works, with the type's arguments inferred from the call rather
than read off a receiver there is none of — see *Generics* below.

## Built-in members

`string`, `char`, arrays, slices, and the numeric types have no source body to write members
in, so their members are **provided by the compiler** — they are part of the language the way
`print` and the `int(x)` conversions are. They are surfaced through the very syntax this
document defines, so a built-in property reads like any other and a built-in method calls like
any other; a program cannot tell, and should not have to, whether `s.len` was written in a body
or built in.

What exists today, as compiler-provided members:

| Type | Member | Kind |
|---|---|---|
| `[N]T`, `[]T`, `string` | `len -> usize` | property |
| `string` | `bytes -> []u8` | property |
| `string` | `chars -> Chars` | property |
| `string` | `copy() -> string` | method |
| `weak T` | `get() -> Option[&T]` | method |

`chars` is the one of the properties that is not a projection of the words already there: it hands
back a cursor over the bytes, which `for` walks through `Iterate` (`14 §7`). It is compiler-provided
rather than an `impl` on `string` for the same reason the other two are — a built-in has no body to
write a member in — and the type it gives back is an ordinary library struct, so nothing about the
member form is special.

**The last two rows are where the property/method line falls for a built-in, and they fall on
opposite sides of it for the reasons §'s rule gives.** `copy()` allocates and walks the bytes, so it
is written with the parentheses that say a call is happening; `get()` is a question about the world
rather than a fact about the value, since two calls a moment apart may disagree (`03`). Reading
either without the parentheses is told which it is and what to write, the way a user type's method
is.

A user `struct` or `enum` is the case that declares its members in its own body; the built-ins are
the case where the body is the language. A built-in has no body to write members in, so what it has
comes from an `impl` — and since such a block may now carry a member with no receiver, `u32.bits()`
is a call and the obstacle that sent the fallible `u32` → `char` constructor to a free function
(`char_from_u32`) is gone as an obstacle. It is not *only* that: every `impl` is for a trait, so
`char.try(n)` would need a trait declaring a `try`, which is a stranger thing than the free function
that exists. Recorded so that the reason is the current one; nothing here is specified and unbuilt.

A compiler-provided member is reached **ahead of** the member table rather than through it, which is
what puts these names out of reach for an `impl` (`02`): a member declared as `len` on a slice or
`bytes` on a string would be registered and never found, so it is refused at the declaration. This
is the same rule that stops an `impl` method from colliding with a struct's field, applied to a type
whose "fields" are the language's.

The rule has to be asked of a **shape** as well as of a type, and asking it only of the type left a
hole exactly where the sentence above points. `impl Sized for []int` is refused because `Self` is a
type by the time its members are hoisted and the type has a `len`; `impl[T] Sized for []T` has no
`Self` yet — the element is still a parameter — so the same question found nothing to ask, and the
block was accepted and then never reached, which is the outcome the refusal exists to prevent. The
shape is asked directly now, and the shapes that have a provided member are the two written with
brackets, `len` being the whole of what a sequence gets. A tuple has no provided member either: its
shape is its arity, and the parts a `for const` walks are reached by position rather than by a name
the compiler supplies (`10 §10`).

### A type with no name still has members

The forms above are written in a type's own body, which only a `struct` or an `enum` has. Everything
else gains members through an `impl` (`02`) — and that includes the types with no name to write a
body for: `impl Total for []int` gives every `[]int` a `total`, read as `xs.total()` exactly as a
struct's is. The receiver is the composed type itself, so `self` in such a member is a `[]int`,
`*self` a `*[]int`, and the conversions at the call site are the ones the table above already
describes.

A block may also match the **shape** rather than the type — `impl[T] Total for []T` gives every
slice a `total` — and such a member is found by the same read, one step later: the receiver's own
type is asked first, and the shape answers when it has nothing. A name reaches one member either
way, because a shape and a slice written out in full may not both declare it (`02`).

## Generics

A member of a generic type is monomorphized with the type, so `Box[T]`'s methods are emitted per
instantiation exactly as its construction and field access already are (`03`). Inside a member,
the type's parameters are in scope, and the receiver's type is the type applied to them —
`self: Box[T]`, `&self: &Box[T]`, and so on. **`Self` means that same type**, which is not a type
until an instantiation says what the parameters are, so it is resolved alongside them rather than
ahead of them: `same(self) -> Self` is `same(self) -> Box[int]` at a `Box[int]` receiver.

What such a member may *assume* of those parameters is what the type asks of them, and a bound on
the type's own parameters is where it asks (`struct SortedList[T: Ord]`, `10 §5`). So a member's
body is checked once, at its definition, against that bound alone — whether or not anything ever
instantiates the type.

A trait may also be implemented for a generic type as a whole, or for a composed **shape**, with type
parameters and bounds on the block itself (`02`). Such a member is a member of the type exactly as
one written in its body is, and is instantiated by the same rule — a shape's from the element type
the receiver turned out to have.

An **associated function** has no receiver, so there is nothing to read the type's arguments off.
They are inferred from the call instead, exactly as a generic free function's are (`10 §4`): from
what the arguments turn out to be, and from the type the context expects where the arguments do not
settle them. So `Box.of(41)` is a `Box[int]` because `41` is an `int`, and a `none()` whose only
mention of the parameter is in its result needs the expecting side to say — `var c: Cursor[int] =
Cursor.none()`. A parameter that neither route reaches is an error at the call, naming the parameter
it could not infer; the bound the type wrote on that parameter is checked against what was inferred,
in the type's name, because the type is where it is written.

An associated function needs a name a call can appear in front of, and every type that *has* a name
may declare one — a struct, an enum, a constrained subtype, and a built-in: `u32.bits()` and
`real.epsilon()` are calls the same shape as `Box.of(41)`. Through a bound the name is the type
**parameter**, which is what `02 § Reaching a trait's members without a value` is about, and it is
the reason a built-in may carry one at all.

A **composed** type is what is left over. `[]int` is a type an `impl` may be written for and not
something that can stand in call position, and a block matching a shape (`impl[T] Make for []T`) is
for a whole family at once. A member with no receiver written in either is refused at the
declaration rather than left as one nothing could use.

A member may introduce **type parameters of its own**, beyond the type's, written in the same
bracketed list every other generic declaration uses and in the same place — directly after the
member name, bounds included:

    struct Box[T]
        v: T
        with[U](self, x: U) -> Pair[T, U] = Pair(self.v, x)

The two lists are fixed from two different places, and that is the whole of the rule. The **type's**
parameters are already settled by the time the call is made, because the receiver is a type and a
type carries its arguments; they are read off it, and what the type asked of them was answered where
the receiver's type was made. The member's **own** are settled by the call, from what it passes and
from the type the context expects — the associated-function rule above, applied to the parameters a
receiver says nothing about. So `b.with("hi")` on a `Box[int]` is `T = int` from the receiver and
`U = string` from the argument, and a member whose own parameter appears only in its result
(`zero[U](self) -> U`) needs the expecting side to say which. A parameter neither route reaches is
an error at the call, naming that parameter, and a bound the *member* wrote is checked against what
was inferred and reported in the member's name — the member is where it is written.

A type that declares no parameters at all may still have a member that declares some: nothing about
that inference goes through the receiver, so `Counter.make[T](x: T)` and `c.with[U](…)` need the
type to be generic no more than a free function does.

The two lists must stay apart, so a member may not spell one of its own the way its type spells one
of its — a `T` in the body has to mean one thing. And a **property** declares none: a property is
read rather than called, so there would be nothing at the read to fix them with.

A trait declares no generic method yet (`02`), which is what an `impl` block's members must match,
so a generic member is an inherent one for now.

## Interaction with traits

Inherent members (this document) and trait members (`02`) are two ways a type gains behaviour,
and they coexist the way Swift's body methods and protocol conformances do. Name resolution on a
`p.name` or `p.name(…)`:

- An **inherent** member of the receiver's type is found first.
- Failing that, a member from a **trait the type conforms to** (`impl Trait for Type`) is found.

**An inherent member and a trait member of the same name are a collision, and the type is told so.**
The implementation of a trait is written in the `impl` block that keeps the promise, and only there:

```
trait Fallible
    failed(*self) -> bool = false

struct File
    err: int
    failed(self) -> bool = self.err != 0     // refused
```

Which refusal it takes says which half of the rule caught it, and both are worth reading. With a
**default body**, as above, the block inherits `failed` for `File` and the inherent one is a second
member of that name: *type 'File' already has a member named 'failed'*. With a bare **requirement**
the block supplies nothing, so what is reported is the promise it fails to keep: *'File' does not
implement 'Fallible': method 'failed' is missing*. Either way the fix is the same line moved one
place — into the block, and marked `override` where it replaces a default (`02 § override`).

An earlier draft of this section said the opposite: that an inherent member of a trait member's name
was the type choosing to satisfy the trait with its own method, and that the analyzer took it as the
implementation rather than reporting a clash. **That was never true of the compiler**, in either
case, and it is worth recording as a claim rather than quietly deleting, because it is the reading
somebody arriving from Swift will bring. What sysl has instead is the stricter rule, and the reason
to keep it is the silence the Swift reading buys: a member that quietly becomes some trait's
implementation is a coupling nothing in the source shows — add a method whose name a trait you
implement happens to use, and its default stops running, at a distance, with no diagnostic.

The operator traits of `00` §9 (`+`, `[]`, the comparisons) are trait members by this rule —
overloading an operator is implementing its trait, declared as `impl Trait for Type`, never an
inherent method with an operator name.

### One name, one member — and what a second implementation does to that

A type's members are one namespace: a member may not take a struct field's name, and a type's own
body may not declare a name one of its `impl` blocks brings. That is what makes `p.show()` a lookup,
and it is the rule the whole of `02`'s coherence rests on.

Two **traits** declaring a `show` may both be implemented for one type, and that is not an exception
to the namespace so much as a statement of how wide it is. A trait's members are reached where the
trait is (`13 §2`), so the two are told apart by what the using file asked for — which is what lets a
library implement a wide trait for a built-in without claiming those names from every program that
will ever compile. A use that reaches both is reported where it is written.

There is one further qualification, and a **parameterized** trait is the whole of it. A type may
implement such a trait at more than one argument list (`02 § One implementation per argument list`),
and each implementation brings the trait's members under the name the trait declared — so a
`Complex` that is both `Mul[Complex]` and `Mul[real]` has two members called `mul`. What resolves
the name is the **argument list**, which every use already carries: an operator has its pair of
operands, a bound names the arguments, a trait object was formed at written ones, and a call passes
values whose types are the arguments.

Resolution is by exact parameter types and is **determined, not preferred**, and it is the last of
three steps rather than the only one: a **bound** narrows first where the use is in a generic body,
then **scope**, and the arguments settle whatever is left. Exactly one candidate accepting the
arguments is the answer; none and more than one are both reported. In particular a
literal has no type of its own to be matched by, so `c.mul(2)` against candidates taking a `Complex`
and a `real` is refused rather than rounded to the nearest — choosing there would be a ranking over
**argument lists**, which `02`'s ordering deliberately does not do: it orders implementations by
their subject, and `override` says nothing about which arguments a call meant. A **property** takes no arguments and so cannot be
resolved this way at all: two implementations both supplying one make it unreadable, which is
reported where it is read.

## Visibility

`13 §2` gives a top-level declaration four reaches: public, `private[ancestor]`,
`private[own_module]`, and a bare `private` meaning the file that declares it. A type's **fields**
and its **inherent members** take the same modifiers, in the same spellings, written before the
declaration exactly as they are at the top level.

```
struct Counter
    private n: int

    value(self) -> int = self.n

    private[stats] bump(*self) = self.n += 1

    make() -> Counter = Counter(0)
end Counter
```

### A member starts at its type's reach, and a modifier only narrows

An unmarked member is **as visible as the type it belongs to** — not public. That is what `13 §2`
already says about a member, and adding the modifier changes only what a member may say about
itself, never what silence means. A `private struct Node`'s members are file-private whether or not
any of them says so, and a public type's are public until one says otherwise.

A modifier may therefore only **narrow**. A `private[oskit]` member of a `private[oskit.arch]`
struct names a region larger than anything that can reach the struct at all, so it is refused rather
than quietly clamped: the reader of that line would otherwise be told something about the member
that is not true. This is the mirror of `13 §2`'s "a declaration may not be more visible than the
types it names", asked about the type a member *belongs to* rather than about the types it mentions.

### Every way of reading a field is restricted, and the constructor is one of them

A modifier decides who may **read** a field, and a field is read three ways: by selecting it, by
naming it in a pattern, and — the one that is easy to miss — by writing the **positional
constructor**, since `Point(1, 2)` names every field of `Point` in order.

| Written | Reads | Needs visible |
|---|---|---|
| `p.x`, `p.x = 5` | one field | that field |
| `Point{x: a}` | the fields it names | those fields |
| `Point(a, b)` (pattern) | every field, in order | every field |
| `Point(1, 2)` (constructor) | every field, in order | every field |

The last is the consequential one, and it is deliberate. A private field a caller could still set by
position would restrict nothing worth restricting: an invariant that a constructor can be made to
break is not an invariant. A struct with a restricted field is therefore built from outside through
an **associated function**, which is what that form is for and what the diagnostic names.

Rust answers this the same way and for the same reason. The alternative — exempting the constructor
— leaves `private` on a field meaning only "you may not read it back", and nothing useful rests on
that.

**A visibility modifier hides a field; it does not hide the layout.** Every row of that table is
about *naming* something, and a private field still occupies its place in the type — it is counted in
the size, it shifts the fields after it, and it takes part in the ABI (`15 §1`). A type that wants
its shape withheld as well says so with `opaque` (`15 §9`), which is the other axis and takes every
row of the table with it: outside the declaring module none of those four forms is open, because
there are no offsets out there to read one with.

### A member a trait declares carries no modifier

A `trait`'s members and an `impl` block's have no visibility of their own, and a modifier written on
either is refused. A trait's member is as visible as the trait: it is part of what the trait asks
for, and a requirement a caller cannot name is one they cannot ask about. An `impl`'s member
supplies what the trait declared, so its reach was settled where the trait was written. This is the
same sentence `13 §2` writes about an `impl` block itself, which takes no modifier because it
declares no name of its own.

An enum's variants and their payload fields carry none either, for the reason `13 §2` gives: a
variant belongs to a type nobody outside may name, and a variant a caller may construct is one they
may take apart.

### A restricted member states its own types

`13 §2`'s "a declaration may not be more visible than the types it names" is asked of each member
and each field at **its own** reach rather than at its type's. So a `private` field of a public
struct may name a `private` type — nothing outside the file can reach the field, so nothing leaks —
while a public method of that same struct may not, exactly as a public function may not.

## Not yet

- **Settable properties.** A property is read-only. A getter/setter pair, so that `p.name = v`
  runs code, is a later addition; until then a value that must be written is a field.
- **Static (type-level) properties and stored associated constants.** An associated *function*
  reaches everything one would — `real.max_value()`, `Point.origin()` — and a trait may declare one,
  so what is deferred is only the spelling without the parentheses (`int.max`, `Point.origin`). It
  waits on settable properties, since both turn on the same accessor machinery, and it is cosmetic
  rather than a gap in what can be expressed.
- **Default trait method bodies and trait-level invariants** — those are `02`'s open items, not
  this document's.
