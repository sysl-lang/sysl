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
| `name -> type = …` (no parens) | a **property** | `p.name` |
| `name(self…) …` | an **instance method** | `p.name(…)` |
| `name(params…) …` (no `self`) | an **associated function** | `Point.name(…)` |

The bodies are the ones functions already have (`03`, `07`): an `= expr` short form or an
indented block whose trailing expression is the value. Nothing about a method body is new; only
where it is written and what its first parameter may be.

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

What the earlier docs specify but which waits on an allocator surface — every operation that
makes new bytes — is compiler-provided too, and lands as these same member forms when it lands:
`string.from_utf8` and `char.try` as associated functions, `s.copy()` and `s.chars` as methods
(`04`, `00`). A user `struct` or `enum` is the case that declares its members in its own body;
the built-ins are the case where the body is the language.

## Generics

A member of a generic type is monomorphized with the type, so `Box[T]`'s methods are emitted per
instantiation exactly as its construction and field access already are (`03`). Inside a member,
the type's parameters are in scope, and the receiver's type is the type applied to them —
`self: Box[T]`, `&self: &Box[T]`, and so on.

A **generic method** — one that introduces type parameters of its own, beyond the type's — is
allowed by the same machinery that makes generic free functions work, and reads with the type
parameters after the member name (`map[U](self, f: …) -> Box[U]`). Where a member's type
parameters and the type's own interact in a bound is a question the generics spec owns, not this
one.

## Interaction with traits

Inherent members (this document) and trait members (`02`) are two ways a type gains behaviour,
and they coexist the way Swift's body methods and protocol conformances do. Name resolution on a
`p.name` or `p.name(…)`:

- An **inherent** member of the receiver's type is found first.
- Failing that, a member from a **trait the type conforms to** (`impl Trait for Type`) is found.

An inherent member and a trait requirement of the same name is the case where the type is
choosing to satisfy the trait with its own method; the analyzer treats the inherent member as
the implementation rather than reporting a clash. The operator traits of `00` §9 (`+`, `[]`, the
comparisons) are trait members by this rule — overloading an operator is implementing its trait,
declared as `impl Trait for Type`, never an inherent method with an operator name.

## Not yet

- **Settable properties.** A property is read-only. A getter/setter pair, so that `p.name = v`
  runs code, is a later addition; until then a value that must be written is a field.
- **Static (type-level) properties and stored associated constants.** Associated *functions*
  exist; a type-level constant or computed property (`int.max`, `Point.origin` without the call)
  is deferred with settable properties, since both turn on the same accessor machinery.
- **Method visibility.** Everything is visible for now; applying `13`'s levels — public by
  default, `private` for the declaring file, `private[M]` for a module or an ancestor subtree — to
  methods and to individual fields waits on the module system being built.
- **Default trait method bodies and trait-level invariants** — those are `02`'s open items, not
  this document's.
