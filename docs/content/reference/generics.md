---
title: Generics
summary: Type parameters, bidirectional inference, bounds checked at the definition, and monomorphization.
weight: 90
---

A **type parameter list** in square brackets makes a function, struct, or enum generic. A parameter
name stands for a type not yet known, and may appear anywhere a type may — a parameter type, a return
type, a field type, a variant payload, a local annotation.

```sysl
id[T](x: T) -> T = x

struct Pair[A, B]
    first: A
    second: B

var p = Pair(1, "one")

print(id(7), id("hi"), p.first, p.second)
```

```output
7 hi 1 one
```

Three further declarations take parameters without declaring a type of their own: an **`impl` block**,
whose subject is a generic type or a composed shape applied to them; a **member**, which may be generic
over types of its own beyond its type's; and a **trait**, which is then a family of promises rather
than one. All three are on [traits](/reference/traits/).

## `[]` means type application in a type, indexing in an expression

Square brackets are reused for two things, disambiguated by **position**, with no new token:

- in a **type**, `Box[int]` and `Result[Box[int], string]` *apply* type arguments to a generic type,
  and nesting is ordinary;
- in an **expression**, `a[0]` *indexes*, and never reads as a type application.

The reuse is unambiguous because a type and an expression never occupy the same grammatical slot.
**Explicit type arguments at a call head** are the one place the two meet: the parser reads
`id[int](7)` as "index `id` by `int`, then call". The compiler resolves the head first, and a
function cannot be indexed, so the brackets are read as type arguments:

```sysl
id[T](x: T) -> T = x

print(id[int](7), id[string]("s"))
```

```output
7 s
```

**The nearest binding wins**, as it does for every call form. A local that shadows a function's
name makes the brackets an index again, with no diagnostic about type arguments.

## Writing the type arguments

The heads below take an explicit list. A **value** argument is written exactly as a type argument
is, since the two share a list and a position:

| written | what it names |
|---|---|
| `id[int](7)` | a function, qualified or not |
| `chunk[8]()` | a value parameter — `[const N: usize]` |
| `Pair[int, real](1, 2.5)` | a constructor, qualified or not |
| `x.pick[int](3)` | a method — its **own** parameters, not the receiver's — on any receiver, a bounded type parameter's included |
| `Maybe[int].Just(1)` | a variant, which is a construction of its type — qualified or not |
| `Box[int].of(1)` | an associated function, called at the instantiation the list names — qualified or not |
| `va_arg[int](ap)` | a special form: `va_arg` and `ptr_cast` |

**A method reached through a bound takes its list the same way a method on a concrete receiver
does.** The list names the trait member's own parameters, which is the only way to supply one that
no argument mentions. A trait's default body writes it on `self` in the same form:

```sysl
trait Out
    one[A](self) -> int

    two(self) -> int = self.one[string]() + 1

struct Sink
    n: int

impl Out for Sink
    one[A](self) -> int = self.n

via[O: Out](o: *O) -> int = o.one[int]()

var s = Sink(41)
print(s.one[int](), via(&s), s.two())
```

```output
41 41 42
```

**An associated function called on an applied name runs at that instantiation**, exactly as it
does through an alias of it. For a function with no receiver, the applied name may be the only
thing that says what the type's parameters are:

```sysl
struct Of[T]
    x: T

    twice() -> int = 2

    of(x: T) -> Of[T] = Of(x)

type OfInt = Of[int]

val o = Of[u8].of(200)
print(OfInt.twice(), Of[int].twice(), Of[Of[string]].twice(), o.x + 55)
```

```output
2 2 2 255
```

The list belongs to the type, so an instance method is refused there, since a type in front of the
dot is not a value to call it on. A member read without a call is likewise told where the type is
written instead:

```sysl
struct Of[T]
    x: T

    get(self) -> T = self.x

val o = Of(3)
print(Of[int].get(o))
```

```error
'get' is an instance method of 'Of' — call it on a value, not the type
```

**A head reached through its module takes the list exactly where the bare name would**, so a type
from another module is instantiated without importing its name:

```sysl
import sysl.math.complex

val z = complex.Complex[f32](1.5, 2.0)

print(z.re, z.im)
```

```output
1.5 2
```

**Inference supplies the arguments nearly everywhere**, and a list that inference would have found
only adds noise. The explicit form is for a signature neither direction of inference reaches, such
as a kernel whose width is a value parameter that no argument mentions and whose result is `unit`:

```sysl
add[const W: usize](a: []const f32, b: []const f32, out: []f32)
    var i: usize = 0

    while i + W <= a.len
        val l: <W>f32 = a.load(i)
        val r: <W>f32 = b.load(i)

        out.store(i, l + r)
        i += W
end add

val xs: [8]f32 = [1, 2, 3, 4, 5, 6, 7, 8]
val ys: [8]f32 = [10, 20, 30, 40, 50, 60, 70, 80]
var out: [8]f32

add[4](xs[..], ys[..], out[..])

print(out[0], out[7])
```

```output
11 88
```

Without the brackets nothing says what `W` is, and the diagnostic states that directly:

```sysl
add[const W: usize](a: []const f32, out: []f32)
    val v: <W>f32 = a.load(0)

    out.store(0, v)

var xs: [8]f32
var out: [8]f32

add(xs[..], out[..])
```

```error
'W' is in neither the parameters of 'add' nor its result
```

**Two kinds of parameter are always inferred.** A **type pack** (`..A`) stands for a list of types
rather than one and has no expression spelling, so its instantiation is always solved. A **bare
arrow's own parameter** is the other, described below.

An associated function selected from the **plain** name of a generic type takes its type's
parameters from the annotation on the binding. The annotation also reaches through an `unwrap()` or
an `expect(…)` when the function returns an `Option` or a `Result` of the type it builds: the binding
says what the wrapper carries, and that settles the type. The call's own arguments are read first,
so where they already settle the type, the binding is checked against it as usual:

```sysl
struct Gain[S]
    k: S

    of(x: real) -> Result[Self, string]
        if x > 1 then return Err("too loud")
        Ok(Gain(S(x)))
end Gain

val g: Gain[q15] = Gain.of(0.5).unwrap()
val h: Gain[q31] = Gain.of(0.25).expect("in range")

print(g.k, h.k)
```

```output
0.5 0.25
```

### A bare arrow is not in the list

`f: T -> U` is shorthand for a bounded type parameter: the callable's own type, which the compiler
adds and no program writes. A declaration therefore carries more parameters than were written on
it, and **the explicit list covers only the written ones**:

```sysl
chain[A, B](x: int, f: int -> A, g: A -> B) -> B = g(f(x))

print(chain[int, int](3, n -> n * 2, n -> n + 1))
```

```output
7
```

The two arrows add two parameters, and `[int, int]` still names `A` and `B`. The added parameters
cannot be written: their argument is the closure's own anonymous type, and the compiler names them
with a character no identifier may contain.

**This matters for a parameter that only a bound mentions.** Inference does not reach it, so the
list is the only way to supply it, and a declaration that also takes a callback relies on both
rules at once:

```sysl
trait Has[T]
    get(self) -> T

struct Box
    v: int

impl Has[int] for Box
    get(self) -> int = self.v

pick[B: Has[T], T, N](b: B, f: T -> N) -> N = f(b.get())

print(pick[Box, int, int](Box(4), n -> n + 1))
```

```output
5
```

**Taking an address is the one case that cannot be resolved.** `&f[…]` has no arguments to read the
callable's type from and no way to write it, so a function taking a bare arrow has no address, and
the diagnostic says why:

```sysl
chain[A, B](x: int, f: int -> A, g: A -> B) -> B = g(f(x))

val p = &chain[int, int]

print(1)
```

```error
'chain' takes a bare arrow, so it is generic in the callable's own type — and an address has no arguments to read that from, nor any way to write it. Declare the parameter as a counted callable, '&Fn(…) -> …', to give it a type that can be named
```

The [boxed spelling](/reference/types/) is what an address needs: `&Fn(T) -> U` is a type with a name,
so a function declared with one is an ordinary generic and its address is written as any other.


**Every type an annotation can spell can be written in the list.** The parser reads each item as an
expression first, so a subscript, `T`, `*T`, `&T`, `Box[T]` and `(A, B)` read as usual; where that
reading does not end the item at the `,` or `]`, it reads the item again as a type from the same
place. That admits `&sync T`, `[]T`, `[]const T`, `[N]T`, a nested application and a boxed callable:

```sysl
import sysl.buf.{Buf, buf}

struct Shared
    n: int

val shared = buf[&sync Shared]()
val views = buf[[]const u8]()
val rows = buf[[3]int]()
val nested = buf[Buf[&sync Shared]]()

print(shared.len(), views.len(), rows.len(), nested.len())
```

```output
0 0 0 0
```

A type that is refused in an annotation is refused here with the same message, so a bare callable
`int -> int` gets the advice to write `&Fn(int) -> int`. In a subscript, such a type is reported as
not being a value:

```sysl
first(xs: []const int) -> int = xs[&sync Missing]
```

```error
this is a type, and a value is wanted here
```

**The address form** `&f[T]` names one instantiation of a generic function. Its main use is a C
callback whose signature does not mention the type parameter. See
[the FFI reference](/reference/ffi/).

## Construction

Applying a generic type names a concrete instance: `Box[int]` is the type of a box of `int`,
`Pair[int, string]` a pair. **Constructing one is an ordinary construction**, with the type arguments
inferred from the arguments (`Box(41)`, `Pair(1, "one")`) or written on the name, where they mean
what an annotation means: `Pair[int, real](1, 2.5)` fixes the instantiation and checks the fields
against it. The memory mode is chosen as for any construction: `Box(41)` is a value unless a
`&Box[int]` is expected.

## Inference is bidirectional

Type arguments are inferred from two directions.

**From the arguments.** `id(7)` infers `T = int`, and inference reaches *through* a generic
construction, so every nested parameter is solved at once:

```sysl
struct Box[T]
    v: T

id[T](x: T) -> T = x

var b = id(Box(id(5)))

print(b.v)
```

```output
5
```

**From the expected type**, when the arguments cannot determine a parameter. A generic function with
no parameters has nothing in its argument list to fix `T`, so the binding's declared type supplies it
and the return type flows *inward*:

```sysl
empty[T]() -> Option[T] = None

var e: Option[real] = empty()

e match
    Some(v) -> print("some", v)
    None    -> print("none")
```

```output
none
```

The model is **unification**: each parameter is solved by matching the declared parameter and return
types against the actual argument types and the expected type. A parameter left undetermined by
**both** directions is a compile error asking for an annotation on the binding. There is no silent
default.

**A literal is consulted last**, because it has no type of its own to offer. A literal takes its type
from where it appears, and a parameter still being solved cannot yet provide one. So an argument that
already has a type settles the parameter first, then the expected type, and a literal's default
applies only where nothing else reached it:

```sysl
pick[T: Add](a: T, b: T, c: T) -> T = a + b + c

print(pick(1, 2, 250u8))
```

```output
253
```

The result is a `u8` because one argument had a type and two did not, while `id(7)` is an `int`
because no argument had one. Once the parameter is solved, the literals are read against it, in the
same order the operand rule uses inside an expression.

**An `if` or a `match` every arm of which is a literal counts as one**, as does a block ending in one,
however they nest; an arm that leaves (`continue`, `return`, a `panic`) is no evidence either way. So
the expected type reaches the arms exactly as it reaches a single literal, and no suffix is needed:

```sysl
import sysl.buf.{Buf, buf_filled}

val c = true
val live: Buf[u8] = buf_filled(3, if c then 255 else 0)

print(live.len(), live[0])
```

```output
3 255
```

An arm that does not fit is refused at that arm, as a lone literal is: under `Buf[u8]`,
`if c then 256 else 0` says `the literal 256 does not fit byte` at the `256`, and `if c then 1 else 2.5`
points at the `2.5`, the one arm the `u8` could not reach:

```sysl
import sysl.buf.{Buf, buf_filled}

val c = true
val b: Buf[u8] = buf_filled(3, if c then 1 else 2.5)
```

```error
'value' of 'sysl.buf.buf_filled' is byte, but real was given
```

**A construction built only from literals is consulted last for the same reason.** `Some(3)` is a
call rather than a literal, but the only thing that gives it the type `Option[int]` is the unsuffixed
`3`. An argument carrying a type of its own therefore outranks it, whichever end of the call it is
written at:

```sysl
same[T: Eq](a: T, b: T) -> bool = a == b

var s: Option[usize] = Some(3)

print(same(s, Some(3)), same(Some(3), s), s == Some(3), Some(3) == s)
```

```output
true true true true
```

An array literal and a generic struct follow the same rule, and it nests: `Some(Some(3))` is built
only from literals at every level.

**Two things stop it, and each is an explicit statement of the type.** A **suffix** anywhere inside
fixes the width, so `Some(3u8)` has a type of its own, and disagreeing with it is a diagnostic. An
ordinary **call** is not a construction: `f(3)` is written exactly as `Some(3)` is, but its result
has nothing to do with the literal passed in, so it keeps its declared return type.

```sysl
f(n: int) -> Option[int] = Some(n)

var s: Option[usize] = Some(3)

print(f(3) == s)
```

```error
'==' needs matching types, got sysl.Option[int] and sysl.Option[usize]
```

**A parameter is solved to the type that was *written*.** Where that is a
[transparent subtype](/reference/declarations/#type-declarations), the parameter carries the subtype
rather than the base it is stored as, however the call states the type:

```sysl
type Age = int within 0..150

widest[T]() -> T = T::Max

val written = widest[Age]()
val expected: Age = widest()

print(written, expected)
```

```output
150 150
```

The three routes agree because they are three ways of saying the same thing. Writing `Age` at the
call, passing an `Age` as an argument, and annotating the binding `Age` each name the type the call
is instantiated at; a result that depended on which one was used would describe the *call* rather
than the type.

A transparent subtype still **is** its base for every value: an `Age` is accepted where an `int` is
expected, and the reverse. A type parameter is not a value, though. It is the name a body is written
against, and the body may ask it something its base would answer differently. `T::Max` is the only
such question.

**`null` is not consulted at all; it waits.** Unlike a literal, it has no default to fall back on.
The argument that cannot contribute is set aside, the others solve the parameter, and `null` is then
read against the result:

```sysl
two[T](a: *T, b: *T) -> bool = a == b

var x = 3

print(two(&x, null), two(null, &x))
```

```output
false false
```

The order of the arguments does not matter, and setting `null` aside cannot lose the solution,
because an argument with no type of its own has nothing to unify. Where nothing else reaches the
parameter, the call is refused rather than defaulted: inference does not invent a pointee type.

```sysl
one[T](a: *T) -> bool = true

print(one(null))
```

```error
'null' takes its type from its context, and there is none here
```

**A closure waits for the same reason, and contributes once it is typed.** A closure carries no type
of its own, since its parameters come from the position it is written in, so it is set aside at
first. Once typed, its result joins the solution, and the closures still waiting are read against
it. One closure's result can therefore be another's parameter, regardless of *which one is declared
first*:

```sysl
forward[T](f: int -> T, g: T -> int) -> int = g(f(0))

backward[T](g: T -> int, f: int -> T) -> int = g(f(0))

print(forward(n -> n + 1, n -> n * 2), backward(n -> n * 2, n -> n + 1))
```

```output
2 2
```

The two declarations differ only in the order of their parameters. Each round of inference reads the
arguments it *can*, so an argument waits on what it needs rather than on its position.

**A parameter that mentions no type parameter takes no part in inference**, and its argument is
checked against it exactly as for a non-generic function:

```sysl
at[T](x: T, n: usize) -> string = n.to_string()

print(at("v", 7))
```

```output
7
```

This matters because inference examines the arguments before it knows any types. Without this rule a
generic function would lose the rules that need an expected type: a parameter's type fixing an
unsuffixed literal, and the coercions to `&T` and to a trait object. A `usize` parameter stays a
`usize` even in a generic declaration.

### Members and associated functions

**A method needs no inference for its type's parameters.** Its receiver already *is* a `Box[int]`,
so the type's arguments are read rather than solved.

**An associated function has no receiver**, which puts it in the same position as a generic free
function, so it is inferred by exactly the rule above:

```sysl
struct Box[T]
    v: T

    of(x: T) -> Box[T] = Box(x)

var b = Box.of(41)

print(b.v)
```

```output
41
```

`Self` in the signature is the type applied to its own parameters, so writing `-> Self` and writing
`-> Box[T]` infer alike.

**A type reached through its module infers exactly as its imported name does**, a dotted path being
one name, so what receives `complex.Complex.i()` settles its arguments as it would `Complex.i()`'s:

```sysl
import sysl.math.complex

val z: complex.Complex[f32] = complex.Complex.i()

print(z.re, z.im)
```

```output
0 1
```

**A type parameter that stands for a generic type brings that type's arguments with it**, so there
is nothing to infer: when `T` is `Box[int]`, `T.origin()` calls `Box[int].origin`, not `Box.origin`
with its argument still to be found. A generic body therefore reaches an associated function of a
generic type with no annotation, which is what makes [`T.zero()`](/library/math/) usable in a body
that knows neither the width nor whether the type is a `Complex`:

```sysl
trait Origin
    origin() -> Self

impl Origin for int
    origin() -> int = 0

struct Box[T]
    v: T

impl[T: Origin] Origin for Box[T]
    origin() -> Box[T] = Box(T.origin())

seed[T: Origin](xs: []const T) -> T
    var s = T.origin()

    s

var bs: []Box[int] = [Box(7)]

print(seed(bs).v)
```

```output
0
```

`Self` inside the type's own body is the same case seen from inside, and needs no annotation either.
A **bare** type name is different: `Box.origin()` applies `Box` to no arguments, so there the
expected type alone settles them.

**A member's own type parameters are inferred the same way.** The receiver supplies the *type's*
arguments and says nothing about the member's, so those are solved by the rule above:

```sysl
struct Pair[A, B]
    first: A
    second: B

struct Box[T]
    v: T

    with[U](self, x: U) -> Pair[T, U] = Pair(self.v, x)

var b = Box(1)
var p = b.with("x")

print(p.first, p.second)
```

```output
1 x
```

The two lists are checked against their bounds separately, each under its own names. The names must
not collide: a member may not name one of its own parameters the same as one of its type's.

## Bounds

**A type parameter is bounded by a trait, and the bound states what the body of a generic may assume
about the parameter.**

### An unbounded parameter permits only what every type supports

With no bound, `T` may be used only for the operations every sysl value supports. Because of the
memory model, that is a useful set: a value can be **copied, assigned, passed, returned, and stored**
in a struct field, an enum payload, an array, or a slice.

That set covers `id[T]`, `Box[T]`, `Pair[A, B]`, and every other container: they move data around
without inspecting it, and they need no bound.

```sysl
struct Box[T]
    v: T

keep[T](x: T) -> Box[T] = Box(x)

var a = keep(5)
var b = keep("hi")

print(a.v, b.v)
```

```output
5 hi
```

**Every sysl value is copyable**: assignment copies, and copying a value that holds a `&T` retains
it. There is therefore **no `Copy` bound to write.** This is simpler than Rust, where `T` is
move-by-default and `T: Copy` or `T: Clone` appear throughout generic signatures. In sysl, holding
and passing along any `T` is the unmarked baseline.

What an unbounded `T` may **not** do is anything that assumes structure: no operator, no method call,
no field access, no index. Each is a capability some types have and others lack, so each requires a
bound that guarantees it, and each is refused **at the definition** with the bound to write:

```sysl
sum[T](a: T, b: T) -> T = a + b

print(sum(2, 3))
```

```error
'+' needs 'T: sysl.Add'
```

A subscript is among them, because a subscript *is* the single method of `Index`, so it is checked
against the bounds exactly as a method call is.

**A field is the exception.** Every other unsupported use names the bound that would allow it. A
field names none, because a trait describes *behaviour* and a field is *layout*, so no bound can
supply one:

```sysl
first[T](x: T) -> int = x.v

print(1)
```

```error
'T' is a type parameter, so it has no fields to read — a field is layout, and no trait declares a property 'v' that a bound could promise instead
```

This is decided at the definition, not by the types that are later passed in: `first[T](x: T) = x.v`
is an error even if every call passes a type with a `v`. A generic reaches a value's data through a
member the bound declares, which also lets two types satisfy one bound while storing the value
differently.

`x.v` looks like a field read but need not be one, so the diagnostic is issued only after looking for
a **property** of that name. A property is behaviour, a trait may declare one, and reading it through
a bound is as ordinary as calling a method.

### A bound is a trait, written `[T: Trait]`

```sysl
sum[T: Add](a: T, b: T) -> T = a + b

smaller[T: Ord](a: T, b: T) -> T = if a < b then a else b

print(sum(2, 3), smaller(9, 4))
```

```output
5 4
```

`a + b` inside `sum` type-checks **because** `T: Add` provides the operator. Without the bound, `sum`
fails *at its own definition*, at the line that made the unsupported assumption. **This is the main
advantage over the template model: the error is reported on the definition that is wrong, not on a
distant caller that instantiated it with the wrong type.** Swift, Kotlin and Scala also check bounds
at the definition; C++ templates defer the check to instantiation.

Operators are available through bounds because [operators *are* trait
methods](/reference/expressions/#operator-dispatch): `+` is `Add`, `<` is `Ord`, `==` is `Eq`.

**Multiple bounds are joined with `+`:** `[T: Ord + Hash]` requires both. The `+` is unambiguous in a
bound position, since it stands between trait names rather than values.

**A bound is the trait *applied***, so it carries the trait's own type arguments where it has any:

| written | means |
|---|---|
| `[X: Sink[int]]` | the body's `x.put(…)` takes an `int` |
| `[X: Into[Y], Y]` | and `Y` is solved at the call |
| `[X: Into[Y], Y: Display]` | a body that may also print what the conversion yields |

The arguments are ordinary types, so one may name another parameter of the same declaration, and
what a body may do with that parameter is what *its* bounds allow. Inference does not run backwards
through a bound: a parameter that appears only there is solved from the result type or annotated.

**A bound also makes the trait's
[associated types](/reference/traits/#a-trait-may-declare-an-associated-type) available**, written
`T::Name`. They cover what the table above cannot: a trait argument is written by whoever names the
trait, but an associated type is chosen by the *implementation*. With `[S: Seq]`, a body names
`S::Item` without the caller having to write it, and `S::Item` is abstract there, allowed exactly
what the trait declares for it, as `S` itself is.

**`S::Item` may appear wherever a type may, including in a callable's signature.** A bare arrow is
shorthand for a bound of its own, so `f: S::Item -> N` is a parameter whose type is never written
out: the implementation chose it, the bound reaches it, and the closure at the call site is
monomorphized against it without being boxed.

```sysl
trait Walk
    type Item

    step(*self) -> Option[Self::Item]

struct Down
    n: int

impl Walk for Down
    type Item = int

    step(*self) -> Option[int]
        if self.n <= 0 then return None

        self.n -= 1
        Some(self.n)

first_of[S: Walk, N](s: *S, f: S::Item -> N) -> Option[N] =
    s.step() match
        Some(t) -> Some(f(t))
        None -> None

var d = Down(3)

print(first_of(&d, n -> n * 10).expect("a step"))
```

```output
20
```

`n` is an `int` because `Down` declares `type Item = int`; `first_of` never mentions `int`.

### A reference meets its referent's bounds

A member call sees through one level of `&` or `*` ([traits]({{< ref "traits" >}})), so `r[i]` and
`r.len()` on a `&Buf[int]` reach the buffer. A bound agrees with it: a `&T`, a `&sync T` or a `*T`
meets `B` wherever `T` does, and the body, instantiated at the reference, reaches the referent's
members exactly as the same call written on the reference would. A write through one lands in the
object the caller holds, and an associated type is the referent's:

```sysl
import sysl.buf.{Buf, buf}

read_at[C: Index[usize, int]](c: C, i: usize) -> int = c[i]

write_at[C: IndexSet[usize, int]](c: C, i: usize, v: int)
    var w = c
    w.index_set(i, v)

struct Up
    i: int
    hi: int

impl Iterate for Up
    type Item = int

    next(*self) -> Option[int]
        if self.i >= self.hi then return None

        self.i += 1
        Some(self.i)

first[I: Iterate](it: I) -> Option[I::Item]
    var c = it
    c.next()

main()
    var b: Buf[int] = buf()
    b.push(1)
    b.push(2)

    val r: &Buf[int] = b

    write_at(r, 0, 10)
    write_at(&b, 1, 20)
    print(read_at(b, 0), read_at(r, 1), read_at(&b, 0))

    var up = Up(0, 3)

    print(first(&up).unwrap(), up.i)
```

```output
10 20 10
1 1
```

**It is the traits a call on a reference reaches its referent through, and no others.** A reference
is refused a bound its referent meets where that would make the body do something the same call on
the reference does not, and the refusal says which:

- **through a `*T`, `Eq` and `Hash`**, which a pointer meets by its address
  ([memory]({{< ref "memory" >}})), and a trait that requires either. A `&T` meets both through its
  referent where the referent has them and by its identity where it does not, so a trait requiring
  either is met through a `&T` like any other;
- **an operator's trait** — `Ord`, `Add` and the rest — since `a < b` and `a + b` do not see through a
  reference;
- **a member naming `Self` away from its receiver**, such as `merge(self, other: Self)`, since `Self`
  would be the reference in the body and the referent in the implementation;
- **an associated function or a static property**, which is reached through the type, and a
  reference's type has none of its referent's;
- **through a `*T`, a member taking `&self`**, which needs the counted box a pointer does not carry;
- **a second level**: a `& &Buf[int]` meets nothing through the buffer, as `rr.len()` does not reach it.

```sysl
struct N
    v: int

impl Ord for N
    lt(self, rhs: N) -> bool = self.v < rhs.v

least[T: Ord](a: T, b: T) -> bool = a < b

main()
    val n: &N = N(1)
    print(least(n, n))
```

```error
'least' requires its type parameter 'T' to implement 'sysl.Ord', but &N does not — N implements it, but 'sysl.Ord' is an operator's, and an operator does not see through a reference
```

`Display` and `Debug` are reached through, so `v.display(…)` on a `T` at `*P` is `P`'s. What
`str(v)`, `v.to_string()` and `s"${v}"` render is decided by the rule for showing a value, and a raw
pointer is shown as its address there ([traits]({{< ref "traits" >}})), at `T = *P` as on a `*P`
local.

### A trait object satisfies a bound on the trait it dispatches through

A bound asks whether the members it names can be called on the value. A `*Trait` or a `&Trait` does
not know which type it holds, but it carries a **table** of exactly those members, so it satisfies
the bound, and one generic function accepts both a concrete type and an object erased from it:

```sysl
trait Shape
    area(self) -> int

struct Rect
    w: int
    h: int

impl Shape for Rect
    area(self) -> int = self.w * self.h

total[T: Shape](x: T) -> int = x.area()

var o: &Shape = Rect(3, 4)

print(total(Rect(3, 4)))
print(total(o))
```

```output
12
12
```

**[Monomorphization](#monomorphization) works unchanged.** An object type is a concrete type, a pair
of words, so the body is instantiated at `&Shape` exactly as it is at `Rect`, once each. The two
instantiations differ in what `x.area()` compiles to: a direct call in one, an indirect call through
the table in the other. That difference is decided as for any two instantiations, by the type the
parameter is bound to.

**This works because of object safety.** A trait with a member that cannot be dispatched, such as
one that mentions `Self` outside its receiver, has no object type *at all*, rather than an object
missing that member. So the existence of a `&Shape` guarantees that every member of `Shape` is
reachable through it.

**The one exception is a member that declares type parameters of its own**, which is left out of the
*table* rather than out of the object ([traits](/reference/traits/#object-safety)). A bound requires
every member, and an object reaches only what its table holds, so an object does not satisfy a bound
on such a trait. The diagnostic names the reason, since otherwise it would read as a `&Applies`
failing to implement `Applies`:

```sysl
trait Applies
    tag(self) -> int
    apply[U](self, f: &Fn(int) -> U) -> U

struct N
    v: int

impl Applies for N
    tag(self) -> int = self.v
    apply[U](self, f: &Fn(int) -> U) -> U = f(self.v)

total[T: Applies](x: T) -> int = x.tag()

val o: &Applies = N(3)

print(total(o))
```

```error
a bound promises every member and an erased value reaches only what its table holds
```

The concrete value is unaffected: `N(3).apply(…)` is an ordinary call, and a bound on a type
parameter reaches it at every instantiation. Only the erased form lacks the member.

**Required** traits follow without a rule of their own. `trait Shape: Display` puts the slots of
`Display` in the object's table, and a bound on a trait is satisfied wherever a bound on a trait that
requires it is, so a `show[T: Display](x: T)` accepts the same `o`.

An object does not satisfy a bound on any *other* trait: the table answers the bound, and a trait
with no slots in it cannot.

```sysl
trait Shape
    area(self) -> int

trait Weighed
    weight(self) -> int

struct Rect
    w: int
    h: int

impl Shape for Rect
    area(self) -> int = self.w * self.h

heft[T: Weighed](x: T) -> int = x.weight()

var o: &Shape = Rect(3, 4)

print(heft(o))
```

```error
'heft' requires its type parameter 'T' to implement 'Weighed', but &Shape does not
```

**Satisfying a bound is not the same as being erasable again.** A bound asks what may be *called*
through a value; forming an object asks what may be *assembled* from its type, and a table is built
from a type's implementations. An object has none, so a `&Shape` cannot be converted to a `&Display`
even though the slots of `Display` are inside its table: a run of slots in one table is not a table
of its own ([traits]({{< ref "traits" >}})).

### A trait object stands in for a parameter behind a mode

A parameter written `p: *P` or `p: &P` asks for a pointer to *some* type implementing the bound. An
object is exactly that, a pointer to a type nobody remembers, so handed a `*Port`, `P` binds to the
trait `Port` itself and `*P` is the object. Every call through `p` dispatches through its table:

```sysl
trait Port
    mask(self) -> u32

struct Board
    level: u32

impl Port for Board
    mask(self) -> u32 = self.level

send[P: Port](p: *P, v: u32) -> u32 = p.mask() + v

var b = Board(40)
val port: *Port = &b

print(send(port, 1))
print(send[Port](port, 2))
print(send(&b, 3))
```

```output
41
42
43
```

The first two calls are one instantiation, at `Port`, whatever type the object was made from; the
third is the ordinary one, at `Board`. An object of a trait that requires `Port` stands in the same
way, and `P` can be handed on to another generic, inferred or written as `inner[P](p)`.

**A trait has no layout, so the body may only ever reach `P` behind `*` or `&`.** A `P` by value, as a
parameter, a local or a result, `sizeof(P)`, `[4]P`, `Buf[P]`, `P(…)`, a static member and an
associated type `P::Item` all need the type rather than an object of it, and the call says which line
of the body asked for one:

```sysl
trait Port
    mask(self) -> u32

struct Board
    level: u32

impl Port for Board
    mask(self) -> u32 = self.level

copy[P: Port](p: *P) -> u32
    val held: P = *p
    held.mask()

var b = Board(40)
val port: *Port = &b

print(copy(port))
```

```error
'copy' uses 'P' by value at line 11, so a trait object cannot stand in for it
```

**An associated type is refused rather than read off the object.** The object's type fixes it, but a
body written against `P::Item` reads it as a type to lay out, which is the same need. **The parameter
must be bounded**: the calls go through the table of the trait a bound names, so a `[P](p: *P)`
handed an object is refused rather than bound to a trait it asks nothing of. A generic *type* inferred
at an object, such as `Wrap(port)` for a `struct Wrap[P: Port]` holding `p: *P`, follows the same rule,
and a field holding `P` by value is refused where it is declared.

**A generic type applied to the parameter is a use behind a mode exactly when the type reaches it only
behind one.** `Wrap[P]` lays out a pointer to the object, so it may be a parameter's type, a result, a
local or another generic type's field:

```sysl
trait Port
    mask(self) -> u32

struct Board
    level: u32

impl Port for Board
    mask(self) -> u32 = self.level

struct Wrap[P: Port]
    p: *P

wrap[P: Port](p: *P) -> Wrap[P] = Wrap(p)

send[P: Port](p: *P, v: u32) -> u32
    val w = wrap(p)
    w.p.mask() + v

var b = Board(40)
val port: *Port = &b

print(send(port, 2))
```

```output
42
```

A type that holds the parameter by value somewhere along its fields — directly, in an array, or in
another generic type that does — needs its layout even behind a pointer, and the refusal names the
fields that hold it:

```sysl
trait Port
    mask(self) -> u32

struct Board
    level: u32

impl Port for Board
    mask(self) -> u32 = self.level

struct Cell[T]
    v: T

struct Shelf[P: Port]
    cell: Cell[P]

peek[P: Port](p: *P) -> u32
    val s: *Shelf[P] = null
    p.mask()

var b = Board(40)
val port: *Port = &b

print(peek(port))
```

```error
'peek' uses 'P' in 'Shelf[P]' (its field 'cell.v' holds 'P') at line 17
```

### A type's own parameters carry bounds too

The same bracketed list means the same thing on a struct or an enum, and it is where the type states
what it assumes:

```sysl
struct SortedPair[T: Ord]
    lo: T
    hi: T

    ordered(self) -> bool = self.lo < self.hi

var s = SortedPair(1, 2)

print(s.ordered())
```

```output
true
```

A bound on a type has two effects.

**Every application of the type must satisfy it**, wherever the application is written: a declared
parameter, a result, a field of another type, a variant's payload, a construction:

```sysl
struct SortedPair[T: Ord]
    lo: T
    hi: T

struct P
    v: int

var s = SortedPair(P(1), P(2))

print(1)
```

```error
'SortedPair' requires its type parameter 'T' to implement 'sysl.Ord', but P does not
```

Where the argument is itself a type parameter, its own bounds decide, so a function taking a
`SortedPair[U]` must bound `U` by at least what `SortedPair` requires. A bound is satisfied by a
bound one level out.

**The type's members may assume the bound, so they are checked at their definition.** A member of a
generic type is checked once, with the parameters standing for themselves, by the same pass that
checks a bounded generic function. A method calling something no bound allows is reported on its
own line whether or not anything instantiates the type. A generic type's fields are laid out once
the same way, which catches a field that applies another bounded type to this one's parameter.

**A bound is declared once, at the type, and is in force everywhere its parameters appear**: a
member's signature and body, a field's type, a variant's payload. It is not restated per member, as
in Swift and Kotlin; Rust requires it to be repeated on each `impl`.

### A bound may be written out of line, with `where`

A declaration with several parameters and several bounds pushes its parameter list, its result and
its body to the right, and the most important part, what the function *takes*, becomes harder to
read. `where` puts the bounds after the signature instead:

```sysl
show[T](x: T) -> string where T: Display = x.to_string()

print(show(42))
```

```output
42
```

**It is the same bound in a different place.** A clause is folded into the bracketed list, so
`show[T: Display](x: T)` and the form above are one declaration written two ways, with the same
inference, diagnostics and monomorphization. The choice is about layout, not meaning.

**The two may be mixed**, so the choice is made per bound: a short constraint stays in the
brackets, and a long one that would crowd the signature goes after it.

```sysl
same[T: Eq](a: T, b: T) -> string where T: Display =
    if a == b then a.to_string() else "different"

print(same(3, 3))
print(same(3, 4))
```

```output
3
different
```

The clause is a comma-separated list, and each entry joins its traits with `+` exactly as a bracketed
bound does. It goes at the end of the header line, after the result type and before the `=` or the
indented block, and it is written the same way on a function, a member, a struct, an enum, a trait
and an `impl`:

```sysl
struct SortedPair[T] where T: Ord
    lo: T
    hi: T

    ordered(self) -> bool = self.lo < self.hi

var s = SortedPair(1, 2)

print(s.ordered())
```

```output
true
```

**A clause may only bound a parameter the declaration actually declares**, and any other name is
refused where it is written:

```sysl
show[T](x: T) -> string where U: Display = x.to_string()

print(show(1))
```

```error
this declaration has no type parameter 'U', so a 'where' clause has nothing to bound — it takes 'T'
```

A **value** parameter cannot be bounded. `[const N: usize]` already states a type, and that type is
what the argument must *be*, not a statement about what it implements. A value implements no trait,
so there is nothing for a clause to add:

```sysl
sum[const N: usize](xs: [N]int) -> int where N: Display = 0

print(sum([1, 2]))
```

```error
'N' stands for a value rather than a type, and a value implements no trait — its 'const' declaration is where the type its argument must have is written
```

**`where` is not a reserved word.** It also introduces a
[constrained type's predicate](/reference/errors/#constrained-types) in a `type` declaration,
where it follows a type rather than a signature, and it is an ordinary name everywhere else, so a
program may still name something `where`:

```sysl
type Even = new int where value % 2 == 0

where(n: int) -> int = n + 1

var e = Even(4)

print(int(e), where(1))
```

```output
4 2
```

## A parameter may carry a default

A parameter of a **trait**, a **struct**, or an **enum** may name the type to use when a use leaves it
out:

```sysl
struct Pair[A, B = A]
    x: A
    y: B

total(p: Pair[int]) -> int = p.x + p.y

print(total(Pair(1, 2)))
```

```output
3
```

The **bound comes first and the default last** (`[R: Show = Self]`), and either may be written
without the other. Four rules govern defaults:

- **Defaults are filled left to right**, each resolved under the arguments already fixed, so a default
  may name a parameter written **before** it; naming one written after it is a forward reference.
- **They form a suffix.** A parameter with no default may not follow one that has a default, because
  arguments are written in order and a use cannot omit an earlier one while supplying a later one.
- **Defaults are filled before instantiations are identified**, so `Pair[int]` and `Pair[int, int]`
  are one instantiation, not two with the same fields.
- **A default is exposed like a field.** A public declaration may not default to a type less visible
  than itself, or a caller who leaves the argument out would hold a value whose type they cannot
  name. A default also may not lead back to the declaration it belongs to, directly or through
  another declaration's default.

A **value parameter** may carry a default too. It stands for the argument a use would have written,
so the same four rules govern it: it fills the same gap, counts toward the same arity and sits in
the same suffix. A type default and a value default may share one list, and a value default may be a
constant expression over the value parameters written before it:

```sysl
struct Ring[T, const N: usize = 4]
    items: [N]T

struct Pad[const M: usize, const N: usize = M * 2]
    a: [M]int
    b: [N]int

val r: Ring[int] = Ring([1, 2, 3, 4])
val p: Pad[2] = Pad([1, 2], [3, 4, 5, 6])

print(r.items.len, p.b.len)
```

```output
4 4
```

`Ring[int]` is `Ring[int, 4]`, a single instantiation. The default has to fit the parameter's
declared type, exactly as a written argument does: `[const N: u8 = 300]` is refused with *"the
default for 'N' does not fit byte: 300"*.

**`Self` is the main use of a default.** In a trait's default it means the implementing type, exactly
as in a method's signature, so `impl Scale for P` means `impl Scale[P] for P`, and `[T: Scale]` asks
for `Scale[T]`. A struct and an enum have no implementing type, so `Self` in one of their defaults is
refused. A **trait object** has none either: an object does not know which type it holds, so a
default of `Self` has nothing to name, and the argument must be written out.

**Only those three declarations may carry defaults.** The type parameters of a function, a method and
an `impl` block are *solved* from what they are given rather than written where they are used, so
there is no argument list with a gap for a default to fill:

```sysl
f[T = int](x: T) -> T = x

print(f(1))
```

```error
'T' is a type parameter of the function 'f', whose type parameters are solved from what it is given rather than written where it is used — so '= int' has nothing to stand in for
```

A fallback for an inference that found nothing would be a different feature, so `f[T = int](x: T)`
is refused rather than given that meaning.

A value parameter of a function is solved from an array's length exactly as a type parameter is
from an argument's type, so its default is refused in the same words:

```sysl
total[const N: usize = 3](xs: [N]int) -> usize = N

print(total([1, 2]))
```

```error
'N' is a value parameter of the function 'total', whose type parameters are solved from what it is given rather than written where it is used — so '= 3' has nothing to stand in for
```

## Converting through a parameter

A conversion is the one capability no bound provides, because a conversion is between concrete
scalar kinds rather than something a trait declares. **Both directions can be written, and both are
checked at each instantiation** rather than at the definition:

```sysl
low[T](x: T) -> u8 = u8(x)

make[T](b: u8) -> T = T(b)

var n: int = make(7)

print(low(321), n)
```

```output
65 7
```

`T(b)` is a conversion written with the parameter's name, resolved at each instantiation, so both
directions follow one rule. An instantiation at a **constrained subtype** or a **simple enum** uses
that type's own checked cast, since the scalar conversion has no meaning for either: `T(x)` at an
`Age` is `Age(x)`, including its trap.

**Construction is deliberately excluded.** A struct's positional constructor takes a field list
rather than a value, and a generic body should not fill in an unknown struct's fields by position
by accident:

```sysl
struct P
    v: int

make[T](b: u8) -> T = T(b)

var p: P = make(7)

print(p.v)
```

```error
cannot convert byte to P
```

`T(b)` stays a **conversion** at every instantiation, so at a struct it is refused exactly as `u8(x)`
is, naming the struct, rather than becoming a constructor. A container that needs to build a `T`
uses a bound that provides one.

**The parameter takes precedence over a declaration of the same name**, consistently: `var y: T`
inside a `[T]` body means the parameter, so `T(x)` on the next line means it too.

## Monomorphization

Each distinct set of type arguments produces its **own** specialized function or aggregate. `id`
called at `int` and at `real` emits two functions; a `Box[int]` and a `Box[string]` are two distinct
layouts. The IR carries exactly one definition per instantiation, not one per call.

This is why bounds can be checked once at the definition yet compile to direct, monomorphic code with
no dictionary passed at run time.

- **The cost is code size**, the standard monomorphization tradeoff, which C++ templates and Rust
  generics also make. It suits a systems language, where a direct, inlinable call matters, and
  [dynamic dispatch](/reference/traits/#the-two-dispatch-strategies) is available when a single copy
  is preferable.
- **Recursion is fine.** A recursive generic function recurses at a *fixed* instantiation, so it
  monomorphizes like any other.

### A nested function may be generic

A function declared inside a body takes type parameters, bounds and bare-arrow parameters exactly as
a top-level one does, and each call instantiates it the same way. Each instantiation is another
function over the environment its block shares, so each reads and writes the body's variables as any
nested function does.

```sysl
tally() -> int
    var total = 0

    noted[T: Display](x: T, n: int) -> string
        total += n
        s"$x"

    print(noted("a", 2), noted(true, 3), noted(1.5, 4))
    total

print(tally())
```

```output
a true 1.5
9
```

Two bodies may each declare a nested generic with the same name: an instantiation is identified by
the frame it belongs to as well as by its type arguments, so the two are never one function. A type
argument the call cannot settle is written out, `sized[u32]()`, and a bound is checked at the call:

```sysl
struct Point
    x: int

describe() -> string
    shown[T: Display](x: T) -> string = s"<$x>"

    shown(Point(1))
```

```error
'shown' requires its type parameter 'T' to implement 'sysl.Display', but Point does not
```

## Variance does not arise

sysl has no variance rules. Variance decides when `G[A]` may stand in for `G[B]`, which only matters
with a subtyping relation among concrete types, and sysl has none. There is no inheritance, and a
trait bound is a constraint rather than a supertype relation between values.

```sysl
trait Sensor
    unit(self) -> string

struct Thermometer
    channel: int

impl Sensor for Thermometer
    unit(self) -> string = "degC"

struct Box[T]
    v: T

label(b: Box[&Sensor]) -> string = b.v.unit()

var t = Box(Thermometer(2))

print(label(t))
```

```error
'b' of 'label' is Box[&Sensor], but Box[Thermometer] was given
```

`Box[Thermometer]` and `Box[&Sensor]` are unrelated types. This removes a whole category of design
difficulty found in languages with nominal subtyping: polymorphism over a set of types is expressed
by a bound or a trait object, never by a covariant container.

## A parameter may stand for a value

A type parameter stands for a **type**. A parameter written `const` stands for a **value**, which
lets one declaration cover every array length:

```sysl
total[const N: usize](xs: [N]int) -> int
    var t = 0
    for i in 0..<N do t = t + xs[i]
    t

var a = [1, 2, 3]
var b = [10, 20, 30, 40, 50]

print(total(a))
print(total(b))
```

```output
6
150
```

`N` is inferred from the argument exactly as a type parameter is: matching `[3]int` against `[N]int`
binds `N` to 3, the way matching `Box[int]` against `Box[T]` binds `T`. Inside the body `N` is an
ordinary `usize`: it can be used as a loop bound, computed with, and passed on.

`total` at `N = 3` and at `N = 5` are two functions, just as `id` at `int` and at `real` are two: the
value is part of what identifies the instantiation, so the length is a constant inside each copy.

### It is the same `const` as everywhere else

A constant is declared `const NAME: Type = expr`. A value parameter is that declaration with the
initializer left to the caller, so `[const N: usize]` is existing grammar in a new position.

The `const` marker is required. `[N: usize]` on its own has the same shape as a bounded type
parameter such as `[T: Ord]`, and only name resolution could tell them apart, by asking whether the
name after the colon is a trait or a type. A misspelled trait name that happened to name a type
would then silently change the kind of parameter.

### Which values

**Integers, `bool`, `char`, and a simple enum's variants.** A value parameter puts a value into a
type's *identity*, so the compiler must decide when two values are the same and must encode each
into a mangled name. Each of these kinds supports both.

A **type** declares its value parameters the same way, and its arguments are written out rather than
inferred, because a type has no call to infer them from:

```sysl
struct Buf[const N: usize]
    data: [N]byte

struct Flag[const B: bool]
    v: int

var buf: Buf[4] = Buf([1, 2, 3, 4])
var on: Flag[true] = Flag(1)

print(buf.data.len, on.v)
```

```output
4 1
```

`Buf[2]` and `Buf[4]` are two types with two layouts, and neither is accepted where the other is
expected.

**An argument may be any constant expression** (a `const`, arithmetic over one, an `if` or `match`
over one, or a call evaluated at compile time), in a type and in a call alike
([declarations.md § Calls where a type is read](/reference/declarations/#calls-where-a-type-is-read)):

```sysl
struct Ring[const N: usize]
    data: [N]byte

double(n: usize) -> usize = n * 2

const Base: usize = 2

var r: Ring[double(Base) + 1] = Ring([0; 5])

print(r.data.len)
```

```output
5
```

An associated function's own parameters are written the same way, through the type:

```sysl
struct Sq
    n: u32

impl Sq
    scaled[U: Integer, const K: u32](x: U) -> U = x * U(K)

const Two: u32 = 2

print(Sq.scaled[i64, Two](5))
```

```output
10
```

An integer constant is read at the parameter's type wherever it is handed to the parameter itself,
and refused only where its value does not fit, so `const Base = 2`, an `int`, still makes `Ring[Base]`
and `Ring[Base + 1]`. Inside the expression the ordinary rules hold: a function's parameter takes
what any call to it takes, and an `int` is not converted to a `usize` implicitly. That argument is
refused at the part that does not read, as the same call would be anywhere else:

```sysl
struct Ring[const N: usize]
    data: [N]byte

double(n: usize) -> usize = n * 2

const Base = 2

var r: Ring[double(Base) + 1] = Ring([0; 5])

print(r.data.len)
```

```error
'n' of 'double' is usize, but int was given
```

**An argument is of its parameter's type.** An integer of any width is read at an integer parameter,
and nothing else stands in for another kind: `true` is not `1`, `65` is not `'A'`, and `1` is not an
enum's second variant. An argument of another type is refused as a call's argument is, naming the
parameter:

```sysl
struct Flag[const B: bool]
    v: int

var on: Flag[1] = Flag(1)

print(on.v)
```

```error
'B' of 'Flag' is bool, but int was given
```

A **member** reads the parameter as an ordinary value of its declared type, and each length gets its
own body:

```sysl
struct Buf[const N: usize]
    used: usize

    room(self) -> usize = N - self.used

var a: Buf[8] = Buf(3)
var b: Buf[2] = Buf(1)

print(a.room())
print(b.room())
```

```output
5
1
```

A **simple enum**, one whose variants carry no payload, gives a parameter that takes one of its
variants. The parameter's declared type names the enum, so the variant is written bare, as it is
wherever an expected type names the enum; `Level.High` names the same value:

```sysl
enum Level
    Low
    High

struct Gauge[const L: Level = Low]
    reading: int

    alarm(self) -> bool = L == High

var quiet: Gauge = Gauge(3)
var loud: Gauge[High] = Gauge(9)
var same: Gauge[Level.High] = loud

print(quiet.alarm(), loud.alarm(), same.reading)
```

```output
false true 9
```

`Gauge[Low]` and `Gauge[High]` are two types, and the default makes a bare `Gauge` the first. A
variant of any other enum is not a value of this one, bare or qualified:

```sysl
enum Level
    Low
    High

enum Tide
    Low
    Slack

struct Gauge[const L: Level]
    reading: int

var g: Gauge[Slack] = Gauge(1)
print(g.reading)
```

```error
'Slack' is not a variant of Level
```

An enum whose variants carry values cannot be the type of a value parameter, since its value is more
than a tag, and neither can a float or a string:

```sysl
enum Shape
    Dot
    Circle(r: int)

struct Holder[const S: Shape]
    n: int

print(1)
```

```error
'S' is a value parameter, and Shape cannot be the type of one
```

Floats are excluded because `NaN != NaN` under the ordinary comparison, which would make a type
unequal to itself. Strings are excluded because two spellings of the same text are not guaranteed
to be one value.

### What may be written with `N`

`N` may be used as an array's length, and a body may compute with it freely. A computed result may
not be carried into a **type**:

```sysl
f[const N: usize](xs: [N]int) -> [N + 1]int = xs

print(1)
```

```error
this length does arithmetic on 'N'
```

Deciding that `N + 1` and `1 + N` are one type, or that `2 * N` and `N + N` are, requires type-level
arithmetic, which sysl does not provide.

Writing a **type** parameter where a length belongs is refused for the opposite reason, since a
length is a value:

```sysl
f[T](xs: [T]int) -> usize = 0

print(1)
```

```error
'T' is a type parameter, and an array's length is a value rather than a type
```

### What it is for: a fixed array renders

`impl[const N: usize, T: Display] Display for [N]T` is one block covering every array, which is why
`print` accepts one:

```sysl
var a = [1, 2, 3]
var b = ["x", "y"]

print(a)
print(b)
```

```output
[1, 2, 3]
[x, y]
```

Value parameters are what let a library implement a trait for arrays of every length: `[2]T` and
`[3]T` are different types, and `[N]T` is generic over the difference.

An array has two shapes an `impl` may match, the length written out and the length as a parameter,
and the written-out one is more specific, so it is found first. A block for `[2]T` takes precedence
over a block for `[N]T` on arrays of two, the same "written out before a parameter" ordering that
`override` uses.

### A member declares its own

A method's parameter list is its own, exactly as a function's is, so it may take a value parameter
the *type's* parameters know nothing about. The type's parameters are fixed by the receiver; the
member's are solved at the call:

```sysl
struct Sum
    seen: usize

    take[const N: usize](*self, xs: [N]int) -> usize
        self.seen = self.seen + N
        N
end Sum

var s = Sum(0)

print(s.take([1, 2, 3]))
print(s.take([4, 5]))
print(s.seen)
```

```output
3
2
5
```

Both calls reach one written method and neither passes a length, because `N` is read from the
argument as it is for a free function.

### A trait declares one too

A trait's parameter list is like a struct's, so it may take a value. The members read it in their
signatures and default bodies, and an `impl` fixes it, the way `impl Mul[int]` fixes a type:

```sysl
trait Bytes[const N: usize]
    bytes(self) -> [N]u8
    size(self) -> usize = N

struct W
    x: u8

impl Bytes[4] for W
    bytes(self) -> [4]u8 = [self.x, 0, 0, 0]

val w = W(7)

print(w.size())
print(w.bytes()[0])
print(w.bytes().len)
```

```output
4
7
4
```

A bound names it at a value, `f[T: Bytes[4]]`, and so does a trait object, `&Bytes[4]`. Two values
give two implementations, exactly as two type arguments do, so one type may implement `Bytes[2]` and
`Bytes[4]` side by side. A generic block may abstract over the value too:
`impl[const M: usize] Bytes[M] for Bits[M]`.

A default follows the struct rules ([§ A parameter may carry a default](#a-parameter-may-carry-a-default)):
a block or an object that leaves the argument out takes it.

```sysl
trait Hash[const N: usize = 32]
    digest_len(self) -> usize = N

struct Sha
    x: int

impl Hash for Sha

val o: &Hash = Sha(0)

print(o.digest_len())
```

```output
32
```

A value is checked against the parameter's type wherever it is written, the default included:

```sysl
trait Small[const N: u8]
    n(self) -> u8 = N

struct S
    x: int

impl Small[300] for S
```

```error
this argument does not fit byte: 300
```

## A parameter may stand for a list of types

A type parameter stands for one type and a `const` parameter for one value. A parameter written `..`
stands for a **list of types**, a *pack*, which lets one declaration cover every tuple:

```sysl
joined[..A: Display](t: (..A)) -> string
    var s = ""

    for const i in 0..<A.len
        if i > 0 then s = s + "-"

        s = s + t.i.to_string()

    s

print(joined((1, 2)))
print(joined((1, "two", true, 4.5)))
```

```output
1-2
1-two-true-4.5
```

The example uses three constructs.

**`..A` declares the pack**, and `(..A)` is its tuple, the only way to *write* a pack wherever it is
declared. It matches a tuple of any arity, and the arity is inferred from the argument exactly as an
array's length is: a `(int, string, bool)` matched against `(..A)` binds `A` to those three types.

**The bound applies to every member.** `[..A: Display]` says every type in `A` implements `Display`;
it is the ordinary `[T: Display]` read over a list, and a pack needs no other bound syntax. Because
the bound is known before any body is compiled, a tuple holding something that cannot be displayed
is refused where it is written:

```sysl
struct Point
    x: int
    y: int

joined[..A: Display](t: (..A)) -> string = t.0.to_string()

print(joined((1, Point(2, 3))))
```

```error
Display
```

**`for const` is unrolled.** Its range must be known at compile time (`A.len` is the number of types
in the pack), and the body is repeated once per value, with `i` substituted as a compile-time
`usize`. Each copy is type-checked **separately**. The parts of a tuple have different types, so
`t.i` is a different selection in each copy, and one written line covers all of them.

`t.i` at a compile-time `i` is the same selection as `t.0`, with the position given as a constant
rather than a literal. It reaches the parts of a tuple and nothing else: a struct's fields have
names, and a number does not address one.

### A `for const` over a type's own make-up

**The subject may also be a descriptor list**: `for const f in T::Fields` unrolls once per field of a
struct, tuple or union, and `for const c in T::Variants` once per variant of an enum. `f` and `c` are
compile-time descriptors, `v.(f)` is that field of a value in each copy, `f::Type` its type, and
`c::Fields` the payload's own list, which a nested `for const` walks. Each copy is checked separately,
as a tuple's `t.i` is, so one body serves fields of every type. The descriptors, and a derivation
written with them, are on [reflection](/reference/reflection/).

**A walk of a type's own parts unrolls once per part**, with no limit: the type's declaration bounds
it, visibly, and a derivation over a hundred-variant enum emits what a hundred hand-written arms
would. The limit described below applies only to a range.

### What a `for const` will not do

A range computed at run time cannot be unrolled; the ordinary `for` walks one:

```sysl
f(n: usize)
    for const i in 0..<n
        print(i)

f(3)
```

```error
must be known at compile time
```

A range is unrolled to at most 65 copies (its two ends at most 64 apart). `for const i in
0..<100000` would be one line producing a hundred thousand copies of its body, with nothing in the
source to show it. A loop that long belongs in a run-time `for`, which costs one copy however far it
counts:

```sysl
f()
    for const i in 0..<100
        print(i)

f()
```

```error
a 'for const' over 100 values would emit that many copies of its body, and 64 is the most one is unrolled to
```

`break` and `continue` are refused as well. There is no loop at run time for either to act on: the
copies are straight-line code inside whatever encloses the `for const`, so a `break` would silently
leave *that* enclosing loop. A loop written inside the body is an ordinary loop and breaks out of
itself as usual.

`return` works, and `Eq` and `Ord` on a tuple are written with it: an unrolled copy is straight-line
code in the enclosing function, so a `return` in one is an ordinary return.

### `if const` — the arm each instance takes

**`if const cond` picks an arm at compile time, and only that arm is compiled.** The condition is a
constant: a value parameter, a `const`, a call that can be evaluated at compile time, or folded
arithmetic over them. Each instance of a generic and each copy of a `for const` decides it again, so
one body can hold an arm that makes sense only for some of them:

```sysl
describe[const N: usize]() -> string
    if const N > 64
        "big"
    elif const N == 0
        "empty"
    else
        "small"

pick[T](x: T) -> int
    if const sizeof(T) == 1
        x.nothing_here()
    else
        7

print(describe[100](), describe[0](), describe[3](), pick(5))
```

```output
big empty small 7
```

`pick(5)` compiles: `x.nothing_here()` is never type-checked for an `int`, because its arm is not the
one `int` takes. An ordinary `if` would check both arms for every instance.

**A chain is decided entirely at compile time.** Every `elif` after an `if const` is an `elif const`,
and a plain `else` covers the rest; a run-time test goes in a plain `if` inside that `else`. A
condition not known at compile time is refused:

```sysl
main()
    var n = 3
    if const n > 2
        print("x")
```

```error
an 'if const' is decided while compiling, so its condition has to be known then
```

A generic body is also checked once before any instance, for what does not depend on its parameters.
That check reads neither arm of an `if const`; each instance reads the arm it takes.

### What it is for: the catalog covers every tuple

`impl[..A: Display] Display for (..A)` is one block covering every tuple, and `Eq`, `Ord` and `Hash`
are one block each. Packs are what let the library implement a trait for tuples of every arity: a
pair and a triple are different types, and `(..A)` is generic over the difference.

A tuple has three shapes an `impl` may match, and they are found most specific first: the tuple
written out in full, then one arity, then every arity.

```sysl
trait Tag
    tag(self) -> string

impl[..A: Display] Tag for (..A)
    tag(self) -> string = "any"

impl[A: Display, B: Display] Tag for (A, B)
    tag(self) -> string = "pair"

override impl Tag for (int, int)
    tag(self) -> string = "two ints"

print((1, 2).tag(), (1, "x").tag(), (1, 2, 3).tag())
```

```output
two ints pair any
```

This is the same "written out before a parameter" ordering an array's two shapes have, with one more
level. The `override` is a separate rule: coherence says where a block may be written, and
`override` says which of two valid blocks answers. A block for a tuple **written out in full** is the
specific one, so it carries the `override`.

### A member declares one too, and a trait's member as well

A pack may appear in a method's own parameter list on the same terms as a value parameter, for the
same reason: the list belongs to the member:

```sysl
struct Row
    n: usize

    take[..A: Display](*self, t: (..A))
        for const i in 0..<A.len
            self.n = self.n + t.i.to_string().len
end Row

var r = Row(0)

r.take((1, "abc", true))
print(r.n)
```

```output
8
```

A **trait's** member takes one too: a member may declare parameters of its own of any kind, and a
pack is one more kind. The cost falls on the trait **object**, not the declaration: a member with
parameters of its own is not a function until a call names them, so no slot of a `&Trait`'s table
can point at it.

The `impl` writes the same list, matched by position so the names need not agree, and the types are
named at the call, whether that call is on the type or through a bound:

```sysl
trait Take
    take[..A: Display](self, t: (..A)) -> usize

struct Row
    n: usize

impl Take for Row
    take[..A: Display](self, t: (..A)) -> usize
        var total = self.n

        for const i in 0..<A.len
            total = total + t.i.to_string().len

        total

through[S: Take](s: S) -> usize = s.take((1, "abc", true))

print(Row(0).take((1, "abc", true)))
print(through(Row(2)))
```

```output
8
10
```

**Such a member has no table slot.** This is a rule about erasure, not about packs: no slot can hold
a function that does not exist until a call names its types. The trait still has an object type, and
the object still dispatches every other member; only reaching *that* member through it is refused
([traits](/reference/traits/#object-safety)).

```sysl
trait Take
    tag(self) -> usize
    take[..A: Display](self, t: (..A)) -> usize

struct P
    n: usize

impl Take for P
    tag(self) -> usize = self.n
    take[..A: Display](self, t: (..A)) -> usize = self.n

val o: &Take = P(5)

print(o.tag())
print(o.take((1, "ab")))
```

```error
'take' of 'Take' declares type parameters of its own, so it is not a function until a call names them
```

A `struct`, an `enum` and a `trait` may not take a pack, for a different reason: their parameters
**are** their shape, and there is nothing to spread a list over. A member has no shape of its own, so
that reason does not apply to it.

### This is not variadic functions

A pack is a compile-time list of *types*. C's ellipsis is a run-time walk over untyped storage
([ffi](/reference/ffi/)), and the two are unrelated. There is also no pack **expansion**: a pack
cannot be spread into a call's arguments, and `(..A, int)` cannot append to one. The unrolled loop
takes the place of expansion, in the one direction the catalog needs.

## What is deliberately not here

| absent | why, and what to write instead |
|---|---|
| type-level arithmetic (`[N + 1]T`) | a value parameter may be used as a length but not computed with in a type; deciding that `N + 1` and `1 + N` are one type would need type-level arithmetic |
| pack expansion (`f(..a)`, `(..A, int)`) | a pack may be matched and walked, not spread into an argument list or appended to; use `for const` instead |
| higher-kinded parameters (`F[_]`) | **excluded** by design: they push inference toward undecidability, and traits and bounds already serve abstraction over containers |

Explicit type arguments at a call are available, at the heads listed
[above](#writing-the-type-arguments). They matter most for a generic function with **no
parameters**, which has no argument to infer from: `buf()` and `map()` are otherwise solved only by
what receives the result, and `buf[u8]()` states it at the call:

```sysl
import sysl.buf.buf

var b = buf[u8]()

b.push(3)
print(b.len())
```

```output
1
```

---

Next: [modules](/reference/modules/).
