---
title: Declarations
summary: Bindings, functions, structs, enums and type declarations — what each form states, and what it leaves to be inferred.
weight: 50
---

A declaration is a **statement**, so anything that can be declared at the top of a file can also be
declared inside a function body. A helper function used by one function belongs inside it, and needs
no separate rule to allow it.

## Bindings

Three forms, and the differences between them are about *when* the value is fixed rather than about
where it lives.

| form | mutable | value | type |
|---|---|---|---|
| `var name = v` | yes | optional — a `var` may be declared and assigned later | optional |
| `val name = v` | no, written once | **required** | optional |
| `const Name: T = v` | no, and known while compiling | required | **required** |

The type is mandatory on a `const` and optional on a `val`, and that arrangement is deliberate in
both directions. A `const` states an interface — its value is substituted where it is used, so what
that value *is* matters less than what it is a value **of**. A `val` has its type readable off the
value it was given, and a `val` with no value is not a declaration of anything.

### A module member states its type

Where a binding is a **member of a module** rather than a local, the annotation stops being optional
even on a `val`. That is a rule about *where* the binding was written, so the grammar accepts either
form and the analyzer applies the rule.

It is worth reading as one rule rather than two, because it is one: **anything visible outside its
file states its types**, and a module member always could be, while a local states nothing to anyone
and so infers exactly as a `var` does. Most of it the syntax already enforced — a parameter type and
a field type are mandatory, and an absent return type *means* `unit` rather than being inferred — so
a module-level binding is simply the last declaration the rule had left to reach.

**The payoff is that interface extraction is parse-only.** A file's exported surface can be read off
its syntax tree without resolving a name, checking a body, or having compiled anything the file
imports, which is what lets the collect pass depend on nothing but parsing — and that is what a fast,
parallel, eventually incremental build rests on. Scala infers types for public members and pays for
it with a far heavier extraction step; this is a deliberate divergence, and a cheap one here because
sysl's signatures were already explicit for other reasons.

```sysl
const Limit: int = 3
val Scale: int = 2

show()
    var count = 0
    val doubled = Limit * Scale
    val name = "sysl"

    count += 1

    print(count, doubled, name, Limit)

show()
```

```output
1 6 sysl 3
```

The two `val`s in that program are one keyword doing one thing: the one at file scope is visible
outside it and says `: int`, and the one inside `show` is visible to nobody and does not have to.

**A module-level `val` holds nothing that owes a release.** There is one thing true of module storage
and of no other storage: it exists for the whole run and is therefore never let go of, so a count
taken in one is a count with no line to write the release on. A `&T`, a `weak T` and a slice are each
refused for that reason, and so is a `string` the program **builds**:

```sysl
val name: string = str(2026)

print(name)
```

```error
'name' cannot be a 'val': storage that exists for the whole run is never let go of, so a count taken in one is a count with nowhere to write the release — and this string is built while the program runs. One the object file can carry as it stands may be held: a string literal owns nothing, and neither does a table of them
```

The question is asked of the **value**, not of the type, and a string *literal* answers it: its bytes
are a constant in the object file and its owner word is null, so nothing was allocated and nothing is
owed. A `val` may hold one, and a table of them:

```sysl
val greeting: string = "hello"
val names: [3]string = ["alpha", "beta", "gamma"]

var i = 1

print(greeting, names[i])
```

```output
hello beta
```

This is what a module with no allocator uses for its messages. A `const` could not serve it: a
constant is folded into its uses and has no address, so it cannot be indexed at a position computed
while running. Anything the program had to build is a **local** instead, which is ordinary — that is
what the first program above does with its `val name`.

**A raw pointer may be held**, because it counts nothing and so there is no release to write. What a
`val` promises is that its *own* storage is written once and never again, and holding an address
keeps that promise exactly as holding a number does. This is the shape that has no substitute — a
device register block named at file scope, reached by every function in the driver rather than
re-materialised in each:

```
const UART: usize = 0x1000_0000
val regs: *Uart = ptr_cast(UART)
```

An address that is a constant is laid into the object file rather than stored by a prologue, so a
`val` at pointer type needs nothing ordered and is readable before the first initializer runs — which
is what a freestanding program starting at a reset vector requires.

Writing to a `val` twice is refused:

```sysl
show()
    val name = "sysl"

    name = "other"

    print(name)

show()
```

```error
a 'val' is written once, so assignment has nothing to write through
```

**Read-only means read-only at every depth.** `k = …`, `k[0] = …`, `k[0] += 1` and `k[0]++` are all
refused on a module-level `val`, and so is `&k[0]` — a `*T` is a licence to write, and handing one
out would move the mistake one step away from where it could still be reported.

**Slicing is allowed, and yields a `[]const T`.** That is what lets a table be *read and passed*
rather than only read: the read-only property travels with the view, through a name, through a call,
and through a second subscript, so every write above is refused through it too. `&v[0]` on such a
view is a `*T`, the tier the memory model excludes on purpose, and is how the view reaches C.

**The one module-level storage none of this reaches is an `extern` variable.** Every rule above is a
promise this program makes about storage it laid down; an `extern` variable is storage the *linker*
supplies — `stdout`, `environ`, `optind` — so there is no such promise to keep. It is a place, it may
be written, and it holds whatever the other side put there.

### Several at once

`var` and `val` both take a comma list, binding several names to several values. Each part's type is
inferred from its own value.

```sysl
show()
    val a, b = 1, 2
    var lo, hi = 0, 10

    print(a, b, lo, hi)

show()
```

```output
1 2 0 10
```

Two or more names and an initializer are both required: one name is the ordinary form, and a multiple
binding with nothing to take apart names nothing. The right side is produced before any name is
bound, so a value there still means whatever the enclosing scope calls it — the binding does not
shadow itself half way through its own right-hand side.

**It is a local form.** The parts carry no type annotation and there is nowhere to write one, so a
multiple `val` at the top of a file collides with the rule above and is refused rather than becoming
a quiet local of the entry point. A module member that wants the form declares its names separately.

The same spelling also takes a **result list** and a **tuple** apart, which is why a function with
several results needs no special form at the call.

### By pattern, when the shape matters

A comma list says how *many* things to bind. A **pattern** says the shape, and so reaches inside a
tuple that holds another one — which is the whole of the difference between the two forms.

```sysl
show()
    val (a, b) = (1, 2)
    val ((x, y), z) = ((3, 4), 5)
    val (first, _) = (6, 7)

    var (lo, hi) = (0, 10)

    hi = hi + 1

    print(a, b, x, y, z, first, lo, hi)

show()
```

```output
1 2 3 4 5 6 0 11
```

A `_` binds nothing and skips its part. A `var` pattern makes every name it binds assignable, and a
`val` pattern makes each of them write-once, exactly as the single-name forms do.

A **struct pattern** stands here on the same terms, naming fields rather than positions. It may name
them in any order, may leave fields out, and may rename one to a sub-pattern:

```sysl
struct Point
    x: int
    y: int

struct Line
    a: Point
    b: Point

show()
    val Point{x, y} = Point(3, 4)
    val Line{a: Point{x: ax}, b} = Line(Point(1, 2), Point(5, 6))

    print(x, y, ax, b.x, b.y)

show()
```

```output
3 4 1 5 6
```

A field the pattern does not name simply binds nothing — unlike a `match` arm, a binding has no
exhaustiveness to discharge.

The **positional** spelling `Point(a, b)` stands here too, and takes the fields in declaration order.
It differs in the one way it differs in a `match`: it names every field, so a struct that grows one
turns each positional binding into a checked to-do rather than one that goes on binding the same
names. See [Patterns](../patterns/#only-an-irrefutable-pattern-may-stand-there).

**Only a pattern that cannot fail may stand at a binding** — a tuple pattern, a struct pattern, a
name, a wildcard, and those nested inside one another. A struct qualifies because it has exactly one
shape, which is the same property that makes a tuple pattern irrefutable.

A literal, a range, or a **variant** is a *test*, and a binding has no other arm to take when the
test does not match, so each is refused with that as the reason. Those belong in a `match` (see
[Patterns](../patterns/)).

Like the comma form, this is **a local form**: the parts have nowhere to carry a type, so one at the
top of a file is refused rather than becoming a quiet local of the entry point.

## Functions

A name, a parameter list, an optional `-> result`, and a body. There is no keyword: the shape is what
identifies it. An absent result type means `unit`.

The body is either `= expr` — whose value is the result — or an indented block, whose **trailing
expression** is the result.

```sysl
double(n: int) -> int = n * 2

sum(a: int, b: int) -> int
    var t = a + b

    t

greet(name: string)
    print("hi", name)

print(double(21), sum(40, 2))
greet("you")
```

```output
42 42
hi you
```

`= ` may also open an indented block, so a body does not have to change shape when it outgrows a
line.

### Several results

A signature may declare more than one result, and the trailing expression or `return` supplies them
as a comma list.

```sysl
minmax(a: int, b: int) -> int, int
    if a < b
        a, b
    else
        b, a

show()
    var lo, hi = minmax(9, 4)

    print(lo, hi)

show()
```

```output
4 9
```

This is not a tuple. A tuple is a value with a type of its own; a result list is several values
handed back at once, and the multiple binding above is what receives them.

A result list is **a whole line by construction**, so it cannot be the body of an inline branch:
`if a < b then a, b else b, a` does not parse, because the comma there would have to belong to the
branch rather than to whatever expression the branch is part of. The block form above is how a
conditional supplies several results.

### Default parameters and named arguments

A parameter may say what a call that leaves it out gets instead. The default is a full expression, so
a call or a conditional may stand there — it is evaluated at the call site that omitted it, and it
may not name anything local to the declaration.

An argument may be written `name = value`, which stands at the parameter it names rather than at the
one its position would have given it.

```sysl
box(w: int, h: int = 1, fill: string = "*") -> string
    var s = ""

    for i in 0..<w * h
        s += fill

    s

print(box(3), box(2, 2), box(2, fill = "#"))
```

```output
*** **** ##
```

`name = value` is also a legal expression — assignment yields the value stored — so the two readings
collide, and the named argument is the one taken. It applies only where the name is a bare
identifier: `p.x = 1` and `b[i] = v` are stores as they always were, since neither is a name a
parameter list could have written. A store to a plain variable is still reachable in an argument by
parenthesizing it.

A **closure's** parameter declares no default. A call reaches a closure through the `Fn` traits,
which carry the types and not the names, so there would be nothing at the call to fill one from.

### Type parameters

A bracketed list directly after the name declares type parameters, with optional bounds. See
[generics and traits](/reference/generics/).

## Structs

A named product type. Fields are declared one per line; methods, properties and an `invariant` may
follow among them, in any order.

```sysl
struct Rect
    w: int
    h: int

    invariant w > 0 && h > 0

    area(self) -> int = self.w * self.h

    perimeter -> int = 2 * (self.w + self.h)

    scale(*self, k: int)
        self.w *= k
        self.h *= k
end Rect

var r = Rect(3, 4)

print(r.area(), r.perimeter)

r.scale(2)

print(r.area(), r.w, r.h)
```

```output
12 14
48 6 8
```

Four things are on display there.

**A constructor is the struct's name applied to its fields**, in declaration order. There is no
separate constructor declaration to write or to keep in step.

**A field declares no default.** What an unwritten field gets is decided by the constructor that
builds the value, not by the field — and the compiler says so rather than leaving a `= v` after a
field to fail as "newline expected".

**A property is a method with the parameter list left off** — `perimeter -> int`, called as
`r.perimeter` with no parentheses. It takes the same body forms a method does.

**The receiver says how the method reaches its value**, and is written as the first thing in the
parameter list:

| receiver | meaning |
|---|---|
| `self` | by value — the method gets a copy |
| `*self` | by pointer — the method may write through it |
| `&self` | by reference, counted |
| `&sync self` | by reference, and safe to share across threads |
| *(none)* | an **associated function** — no receiver, called on the type |

```sysl
struct Point
    x: int
    y: int

    origin() -> Point = Point(0, 0)

var p = Point.origin()

print(p.x, p.y)
```

```output
0 0
```

### `invariant`

A condition every value of the struct must satisfy, re-checked whenever the struct is built or one of
its fields is written. Bare field names are in scope inside it. A multiple assignment re-checks it
**once**, after every write has landed, which is what lets two fields that relate to each other be
changed together.

`invariant` is a contextual word — an ordinary identifier everywhere else. See
[errors and contracts](/reference/errors/) for what happens when one is broken.

### `opaque`

`opaque struct Name` withholds the layout from every module but the one declaring it. Outside, the
type is *incomplete*: only `*Name` may be said, so a value cannot be built, copied, or have a field
read. This is a **different axis from visibility** — `private` decides who may say the name, `opaque`
decides who may know the shape. See [modules](/reference/modules/).

## Enums

Two shapes under one keyword. A **simple** enum is a set of named discriminants over an underlying
integer type; a **data** enum gives its variants payloads, which makes it a sum type. Both may carry
members.

```sysl
enum Status: u8
    Ok = 0
    Warn = 10
    Fail = 20

    severe(self) -> bool = u8(self) >= 10

print(u8(Warn), Ok.severe(), Fail.severe())
```

```output
10 false true
```

The `: u8` pins the storage; without it the compiler picks. A variant is a bare name, a name with an
explicit integer value, or a name with a payload:

```sysl
enum Shape
    Circle(r: real)
    Rect(w: real, h: real)

    area(self) -> real = self match
        Circle(r)  -> 3.14159 * r * r
        Rect(w, h) -> w * h

print(Circle(1.0).area(), Rect(2.0, 3.0).area())
```

```output
3.14159 6
```

A member is told from a variant by what follows it: a member needs a body after its header, so
`Circle(r: real)` — a header with nothing after it — is a variant.

`Option[T]` and `Result[T, E]` are ordinary data enums declared in the standard library, with no
compiler privileges.

## Type declarations

`type Name = Existing` introduces a second spelling, interchangeable with the first. It creates **no**
new type and no checking — an alias is for shortening a name that has grown long.

Adding `new` makes it a genuinely distinct type, and `within` and `where` add checked bounds:

```sysl
type Meters = new f64
type Slot = new u8 within 0..<8

var d = Meters(1.5)
var s = Slot(3)

print(f64(d) * 2.0, u8(s))
```

```output
3 3
```

`new`, `within` and `where` are contextual words, so a function or field may still be named `where`.
See [errors and contracts](/reference/errors/) for what a bound costs and when it is checked.

## Traits, impls, and externs

`trait Name` declares a set of requirements; `impl Trait for Type` supplies them. Both are covered on
[traits](/reference/traits/).

`extern` declares a function or a variable the other side of the link owns, and is covered on
[the foreign interface](/reference/ffi/).

## Visibility

A declaration is **public unless it says otherwise** — the unmarked case is the one that writes
nothing, because it is the common one.

| written | reach |
|---|---|
| *(nothing)* | public — anything that can see the module can see it |
| `private` | this file only |
| `private[mod]` | the named enclosing module and everything under it |

A struct's and an enum's **members and fields** each take their own modifier, so a type may be public
while part of its shape is not. A trait's members and an `impl` block's take none: a trait's member is
as visible as the trait, and an implementation supplies what the trait asked for.

The details — what a module is, how the reach is computed, and how visibility interacts with `opaque`
— are on [modules](/reference/modules/).

## `end` markers

Every block-shaped declaration may be closed with `end Name`, naming what it closes. It is optional
everywhere and checked when written, so it cannot drift from the thing it claims to close.

`end` is a **soft** word: it is an ordinary identifier everywhere except immediately before a name or
a construct keyword, so `end` stays usable as a variable.

---

Next: [patterns and matching](/reference/patterns/).
