---
title: Reflection
summary: What a type answers about its own make-up while compiling — kind, name, fields, variants, annotations — and how a library writes a derivation from it.
weight: 95
---

**Reflection is a set of questions a type answers while the program is being compiled**, and nothing
else. A struct can be asked what its fields are, an enum what its variants are, any type what kind of
thing it is; the answers are plain values and descriptors that a `for const` walks, and a body written
over them is checked and unrolled once per type, so the code that comes out is the code a person would
have written by hand. There is no run-time half: no `any`, no type tags on a value, no table the
program carries to look a field up by name.

It is built from forms that are described elsewhere, and this page is where they meet:

| written | answers | covered in |
|---|---|---|
| `T::Kind`, `T::Name`, `T::BaseName` | what a type is, and what it is called | [below](#what-a-type-is-and-what-it-is-called) |
| `T::Fields`, `T::Members` | the stored parts of a struct, a tuple or a union | [fields](#fields-and-members) |
| `T::Variants` | the variants of an enum | [variants](#variants) |
| `v.(f)`, `f::Type` | one field of a value, and its type | [walking fields](#walking-the-fields) |
| `x is c`, `x.(c)`, `c::Payload`, `T::Make(c, p)` | testing, reading and building a variant | [variants](#variants) |
| `if const T: Trait` | what a type can do | [asking for a trait](#asking-what-a-type-can-do) |
| `@annotation`, `f::Annotation[A]` | a program's own marks on a field, variant or type | [annotations](#annotations) |

The other `::` attributes — `Color::First`, `Int::Max`, `T::Min` — are on
[attributes](/reference/attributes/#-what-a-types-own-name-answers). `for const` and `if const` are on
[generics](/reference/generics/).

## What a type is, and what it is called

**`T::Kind` is a value of the standard enum `Kind`**, one variant per make-up a type can have:
`Struct`, `Tuple`, `SimpleEnum`, `DataEnum`, `Union`, `Array`, `Vector`, `Slice`, `Int`, `Float`,
`Fixed`, `Bool`, `Char`, `String`, `Unit`, `Ref`, `Weak`, `Pointer`, `Fn`, `Trait` and `Opaque`. A
subtype is the kind of the type it narrows, a bitfield struct is a `Struct`, and a closure — which is
reached through a reference — is a `Ref`.

```sysl
struct Point
    x: int
    y: int

enum Light
    Red
    Green

enum Shape
    Dot
    Circle(r: int)

union Bits
    i: u32
    f: f32

type Pair = (int, real)
type Four = [4]int

print(Kind::Image(Point::Kind), Kind::Image(Light::Kind), Kind::Image(Shape::Kind), Kind::Image(Bits::Kind))
print(Kind::Image(Pair::Kind), Kind::Image(Four::Kind), Kind::Image(string::Kind))
print(Kind::Image(f64::Kind), Kind::Image(bool::Kind), Kind::Image(unit::Kind))
```

```output
Struct SimpleEnum DataEnum Union
Tuple Array String
Float Bool Unit
```

`Kind` is an ordinary enum, so it compares with `==` and is the usual head of an `if const`:
`if const T::Kind == Kind.Struct` keeps one arm of a body and discards the rest, per type.

**`T::Name` is the type as a program spells it**, with its arguments, and **`T::BaseName` is the
declared name alone** — what `Debug` and `Display` print in front of a value's parts:

```sysl
struct Box[T]
    v: T

type Pair = (int, real)

print(Box[int]::Name, Box[int]::BaseName)
print(Pair::Name, int::Name)
```

```output
Box[int] Box
(int, real) int
```

`T::BaseName` is refused where the type has no declared name, as a tuple has none, and `Box::Name` with
no arguments is refused because `Box` alone is not one type; both are on [what it refuses](#what-it-refuses).
An anonymous `struct` or `union` block answers the word that opened it.

## Fields and members

**`T::Fields` lists the named fields of a struct, a tuple or a union, in declaration order.** Each
element is a `sysl.reflect.Field`, a plain value that exists while compiling:

| member | is |
|---|---|
| `name: string` | the field's name; a tuple's parts are `"0"`, `"1"`, and so on |
| `index: usize` | its position in `T::Fields`, counting from `0` |
| `offset: usize` | where it begins, in bytes from the start of the value |
| `public: bool` | whether code outside the type's own file may name it |

```sysl
struct Account
    id: int
    private secret: u8
    owner: string

type Pair = (int, real)

for const f in Account::Fields
    print(f.name, f.index, f.offset, f.public)

for const f in Pair::Fields
    print(f.name, f.index, f.offset)
```

```output
id 0 0 true
secret 1 4 false
owner 2 8 true
0 0 0
1 1 8
```

A union's fields all begin at offset `0`. **`T::Fields.len` is the number of fields**, a constant, and
the list is a `for const` subject like any other.

**`T::Members` lists everything a struct, tuple or union stores**: its named fields, each anonymous
`struct` or `union` block, and each unnamed `_` bitfield. A zero-width `_` stores nothing and is not
listed. Its elements are `sysl.reflect.Member`s — a `Field`'s four, plus `anonymous: bool`, true for
a block, whose `name` is `""`; an unnamed bitfield's name is `"_"`.

```sysl
struct Packet
    tag: u8
    union
        word: u32
        real: f32
    lo: u4 in u32
    _: u4 in u32
    hi: u8 in u32

print(Packet::Members.len, Packet::Fields.len)

for const m in Packet::Members
    print(m.index, m.name, m.anonymous)

for const f in Packet::Fields
    print(f.index, f.name)
```

```output
5 3
0 tag false
1  true
2 lo false
3 _ false
4 hi false
0 tag
1 lo
2 hi
```

**`T::Fields` is the named fields alone**, so a derived `Eq` or `Hash` never reads an unnamed bitfield
and an anonymous block is not a field of its own; `T::Members` is what `Debug` walks, since it shows the
bytes a value holds. **Each list numbers its own elements**: a `Field`'s `index` is its position in
`T::Fields` and a `Member`'s its position in `T::Members`, which is why `lo` is field `1` and member
`2` above. So `f.index > 0` is "not the first field", whatever members come before it.

A member that is an anonymous block is read and assigned as its own struct or union, and its `::Type`
says which:

```sysl
struct Packet
    tag: u8
    union
        word: u32
        real: f32

for const m in Packet::Members
    if const m.anonymous
        print(Kind::Image(m::Type::Kind), m::Type::Fields.len)
```

```output
Union 2
```

## Walking the fields

**`for const f in T::Fields` unrolls once per field**, and `f` is a compile-time descriptor in each
copy. **`v.(f)` selects that field of a value** `v` of type `T`, typed as the field and readable and
assignable as `v.x` is. Each copy is checked on its own, so one written line covers fields of different
types:

```sysl
struct Point
    x: int
    y: real

twice[T](v: T) -> T
    var out = v

    for const f in T::Fields
        out.(f) = v.(f) + v.(f)

    out

val p = twice(Point(3, 1.5))

print(p.x, p.y)
```

```output
6 3
```

A copy that the field's type cannot do is refused naming the copy: `twice` over a struct holding a
`bool` says so for that field only.

**`f::Type` stands for the field's type, wherever a type is written.** No type is ever a value — it is
how a generic is called, or a local declared, once per field:

```sysl
struct Point
    x: int
    y: real

zero[T]() -> T
    var out: T

    for const f in T::Fields
        var tmp: f::Type
        out.(f) = tmp

    out

val p = zero[Point]()

print(p.x, p.y)
```

```output
0 0
```

**Building a value is `var out: T` and one assignment per field.** `out` starts at the type's zero as
any `var` does, so a field not assigned keeps it; a type with no zero is refused at the `var`.

## Variants

**`T::Variants` lists the variants of an enum**, simple or data, in declaration order. Each is a
`sysl.reflect.Variant`, with `name: string` and `index: usize`, and `for const c in T::Variants`
unrolls once per variant. A variant's payload is described by `c::Fields`, the same descriptors as a
struct's, so it walks the same way:

```sysl
enum Shape
    Dot
    Circle(r: int)
    Rect(w: int, h: int)

for const c in Shape::Variants
    print(c.name, c.index, c::Fields.len)

for const c in Shape::Variants
    for const f in c::Fields
        print(c.name, f.name, f.index)
```

```output
Dot 0 0
Circle 1 1
Rect 2 2
Circle r 0
Rect w 0
Rect h 1
```

Three operations on a value are what an encoder and a decoder need, and none writes a `match`:

- **`x is c`** is true when `x` holds that variant. It is a run-time test in each copy, so the walk
  reaches the one that matches.
- **`x.(c)` is the payload**, and it **traps** when `x` holds another variant. The payload is `unit`
  for a variant with no fields, the field itself for a variant with one, and a tuple for more.
- **`T::Make(c, p)` builds the variant** `c` of `T` from a payload `p` of that same shape.

```sysl
enum Shape derives Eq
    Dot
    Circle(r: int)
    Rect(w: int, h: int)

describe(s: Shape) -> string
    var out = ""

    for const c in Shape::Variants
        if s is c then out = c.name

    out

radius(s: Shape) -> int
    for const c in Shape::Variants
        if const c.name == "Circle"
            if s is c then return s.(c)

    -1

print(describe(Shape.Rect(2, 3)), describe(Shape.Dot))
print(radius(Shape.Circle(5)), radius(Shape.Dot))
```

```output
Rect Dot
5 -1
```

Reading a variant of a value that holds another stops the program, having written
`variant 'Shape.Circle' was asked for, and the value is 'Shape.Dot' (main.sysl:9:18)` to standard error
the way a failed [bounds check](/reference/errors/) does:

```sysl
enum Shape
    Dot
    Circle(r: int)

val s = Shape.Dot

for const c in Shape::Variants
    if const c.name == "Circle"
        print(s.(c))
```

```output trap
```

**`c::Payload` stands for a variant's payload type, where a type is written.** A decoder builds a
variant with one generic body: declare the payload, assign each field of `c::Fields` with `p.(f)`, and
hand it to `T::Make`. Over a payload of one field `p.(f)` is `p` itself, and assignable:

```sysl
enum Shape derives Eq
    Dot
    Circle(r: int)
    Rect(w: int, h: int)

build(i: usize) -> Shape
    var out = Shape.Dot

    for const c in Shape::Variants
        if c.index == i
            var p: c::Payload

            for const f in c::Fields
                p.(f) = 10 * int(f.index + 1)

            out = Shape::Make(c, p)

    out

print(build(0) == Shape.Dot, build(1) == Shape.Circle(10), build(2) == Shape.Rect(10, 20))
```

```output
true true true
```

A simple enum is walked the same way — its variants have no payload, and `x is c` and `c.name` are the
whole of what a serializer needs.

## Asking what a type can do

**`if const T: Trait` is true when `T` implements `Trait`**, asked as a bound is asked: `T: Display +
Hash`, `T: Conv[int]` and `f::Type: Display` all read. It is an operand of an `if const` or `elif const`,
so it joins `&&`, `||`, `!` and ordinary constants. A serializer uses it to choose between a type's own
implementation and walking its fields:

```sysl
trait Named
    name(self) -> string

impl Named for int
    name(self) -> string = "int"

struct Pin
    on: bool

describe[T]() -> string
    if const T: Named
        "named"
    elif const T: Display
        "displayable"
    else
        "plain"

print(describe[int](), describe[string](), describe[Pin]())
```

```output
named displayable plain
```

## Annotations

**A program may declare an annotation: a struct written under `@annotation`**, whose fields are the
arguments a use gives it. It is then legal above a field, a variant or a type, and reflection reads it:
**`f::Annotation[A]`, `c::Annotation[A]` and `T::Annotation[A]` answer an `Option[A]`**, `None` where
the mark is absent. The built-in attributes stay reserved; an annotation is only ever one the program
declared.

```sysl
@annotation
struct label
    text: string

@annotation
struct secret
    level: int

@label("a shape")
enum Shape
    @label("nothing") Dot
    Circle(r: int)

struct User
    @label("the id") id: int
    @secret(2) password: string
    name: string

for const f in User::Fields
    print(f.name, f::Annotation[label].map(l -> l.text).unwrap_or("-"), f::Annotation[secret].is_some())

for const c in Shape::Variants
    print(c.name, c::Annotation[label].map(l -> l.text).unwrap_or("-"))

print(Shape::Annotation[label].map(l -> l.text).unwrap_or("-"), User::Annotation[label].is_some())
```

```output
id the id false
password - true
name - false
Dot nothing
Circle -
a shape false
```

The arguments are checked against the annotation's fields as a call's are, a mark of a name that is
not an annotation is refused, and one written twice above the same thing is refused:

```sysl
@annotation
struct label
    text: string

struct S
    @label(5) a: int
```

```error
'text' of 'label' is string, but int was given
```

```sysl
struct S
    @label("x") a: int
```

```error
'@label' names no annotation
```

```sysl
@annotation
struct label
    text: string

struct S
    @label("a") @label("b") a: int
```

```error
'@label' is written twice above 'a', and it says nothing the once does not
```

## Privacy

**A query lists every field, and `f.public` says which are public.** `v.(f)` on a private field is
allowed only in the type's own file, or in a derivation the type asked for with `derives`: the author
of a type opts in by writing the clause, and anyone else sees only the public surface.

```sysl
// secrets/main.sysl
module secrets

struct Secret
    shown: int
    private hidden: int

dump[T](v: T) -> string
    var out = ""

    for const f in T::Fields
        out = out + s"${v.(f)}${if f.public then "" else "!"} "

    out

print(dump(Secret(shown = 1, hidden = 2)))
```

```output
1 2! 
```

Inside the file that declares the type the walk reaches `hidden`. A generic written in any other file is
refused at the copy for that field, naming it, and the same holds for `T::Members`.

## Generic types and opaque ones

**A generic type is reflected as an instance.** `Box[int]::Fields` lists the fields of that instance,
with `int` for `v`; `Box::Fields` with no arguments is refused, as `Box::First` would be, because
there is no one type to ask.

```sysl
struct Box[T]
    v: T
    n: int

for const f in Box[string]::Fields
    print(f.name, f.offset)
```

```output
v 0
n 24
```

**An `opaque struct` that has fields is a struct in its own module.** There, and in a derivation it asked
for, `T::Kind` is `Struct` and `T::Fields` and `T::Members` list its fields; anywhere else it is `Opaque`
and has none. An `opaque struct` with no body is `Opaque` everywhere.

```sysl
// lib/main.sysl
module lib

opaque struct H
    x: int
    private y: u8

opaque struct Bare

print(Kind::Image(H::Kind), Kind::Image(Bare::Kind))

for const m in H::Members
    print(m.name, m.offset)
```

```output
Struct Opaque
x 0
y 4
```

From another module the same `H` is `Opaque` and `H::Fields` is refused.

## There is no run-time reflection

**Every query is answered while compiling.** A descriptor is plain data, so a program may lay a type's
name or a table of its field names into the object file — `print(T::Name)` is a string constant — but
there is nothing to ask of a value at run time. No `any` type exists, a trait object carries no
description of the type behind it, and a program that does not reflect pays nothing for the feature.
A function's methods and a module's declarations are not reflected either; what a type's methods are
varies with what is imported.

## Writing a derivation

A trait may carry a `derive` member, a body written with the queries above, and a `derives` clause
instantiates it for a type as the `impl` block a person would have written. Everything the standard
library derives — `Eq`, `Ord`, `Hash`, `Display`, `Debug` — is written this way, and a library's own
trait is derived exactly as they are. The clause, what the body is held to, and how a field that cannot
do the work is refused are on [traits](/reference/traits/#a-trait-says-how-it-is-derived).

The program below is a complete serializer. `Json` is declared with an `encode` member and the
reflection-written derivation of it; a struct is walked field by field, a simple enum is written as
its variant's name with `T::Image(self)` (in the enum's copy `T` is the enum, and answers what it
does), `@rename` changes a key, and a field's own `encode` is reached through
`self.(f).encode(w)`:

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
        elif const T::Kind == Kind.SimpleEnum
            w.write(s"\"${T::Image(self)}\"".bytes)
        else
            @assert(false, s"Json cannot derive for ${T::Name}")

impl Json for int
    encode(self, w: *Writer) = w.write(s"$self".bytes)

impl Json for string
    encode(self, w: *Writer) = w.write(s"\"$self\"".bytes)

enum Role derives Json
    Admin
    Guest

struct Address derives Json
    city: string
    zip: int

struct User derives Json
    @rename("id") user_id: int
    name: string
    role: Role
    home: Address

User(7, "ann", Role.Admin, Address("Oslo", 150)).encode(stdout())
print("")
```

```output
{"id":7,"name":"ann","role":"Admin","home":{"city":"Oslo","zip":150}}
```

`User` gets an `impl Json for User` whose body is four unrolled field writes, and `self.(f).encode(w)`
is checked for each, so a field whose type is not `Json` is refused at the `derives` naming the field. A
type the derivation does not handle says so in a message of the library's own making: the `@assert`'s
computed message is shown where the type asked.

```sysl
trait Named
    name(self) -> string

    derive[T] name(self: T) -> string
        if const T::Kind == Kind.Struct
            T::BaseName
        else
            @assert(false, s"Named cannot derive for ${T::Name}, which is not a struct")
            ""

enum Light derives Named
    Red
    Green
```

```error
assertion failed: Named cannot derive for Light, which is not a struct
```

## What it refuses

Each refusal names the type and what it is, or the descriptor and what it answers:

```sysl
for const f in int::Fields
    print(f.name)
```

```error
'int::Fields' lists the fields of a struct, a tuple or a union, and 'int' is an integer
```

```sysl
struct Box[T]
    v: T

print(Box::Name)
```

```error
'Box' is generic, so 'Box::Name' has no single type to read — write the arguments, 'Box[…]::Name'
```

A descriptor answers what its own kind of thing has: a field its `::Type`, a variant its `::Fields` and
`::Payload`.

```sysl
struct P
    x: int

for const f in P::Fields
    var a: f::Payload
```

```error
'f' is the descriptor 'P.x', which answers '::Type', the field's type, and not '::Payload'
```

```sysl
enum E
    A(x: int)

for const c in E::Variants
    var a: c::Type
```

```error
'c' is the descriptor 'E.A', which answers '::Fields', its payload's fields, and '::Payload', its payload's type, and not '::Type'
```

A name a type does not answer is refused with the type named:

```sysl
struct P
    x: int

print(P::Bogus)
```

```error
'P' has no type attributes
```
