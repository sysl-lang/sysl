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

### The value may be an indented block

A binding's `=` takes one expression, or an **indented block** whose trailing expression is the
value — the same arrangement a function body has, and the same one an `if` branch has. What the block
binds is the block's own, and goes out of scope with it:

```sysl
val limits =
    val raw = 7
    val capped = raw * 2

    capped

print(limits)
```

```output
14
```

Reach for it when the value takes more than one step to work out. The alternative is a name in the
enclosing scope that exists only to be read once on the next line, which says the value is available
to everything below when it is not.

It composes with the branching forms rather than competing with them — an `if` too long for its line
goes on the line under the `=`, which is the shape the form was built for:

```sysl
pick(c: bool) -> int
    val n =
        if c then
            val a = 3

            a + 1
        else
            0

    n * 10

print(pick(true), pick(false))
```

```output
40 0
```

**A block of one expression is that expression.** Moving a value to the next line to fit the margin
changes nothing else about the declaration: a module `val` written that way is still laid into the
object file, rather than becoming something the program computes before it starts.

**A block that ends in something other than an expression yields `unit`**, exactly as an `if` with no
`else` does, and the complaint arrives where the value is used rather than where it was bound.

**A `const` does not take one.** A constant is folded into every use of it rather than run, so there
is nowhere for the statements to happen. Its value may still sit on the next line — that is one
expression rather than a block, by the rule above — and what is refused is a block that binds
something:

```sysl
const Limit: int =
    val base = 3

    base * 2

print(Limit)
```

```error
'Limit' is a 'const', so its value is folded into every use of it
```

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
static val Scale: int = 2

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

The two `val`s in that program are one keyword doing one thing: the module member says `: int`
because it is visible outside its file, and the one inside `show` is visible to nobody and does not
have to. The `static` is what asks for the member — this is the file the program starts in, so a plain
`val` there would be a local of its body, and a local infers exactly as a `var` does.

**A module-level `val` may hold a counted value, and never releases it.** Storage that exists for the
whole run is never let go of, so the one release with nowhere to go is the one at exit — and not
taking it is what a static *is*. A `&T`, a `weak T`, a slice and a `string` the program **builds**
are all admissible:

```sysl
static val name: string = str(2026)

print(name)
```

```output
2026
```

That was refused until the reason was read again, and what it cost was every shape that needs a
value to outlive every frame. What the initializer *is* still decides where the storage gets filled —
a **literal**'s bytes are a constant in the object file and its owner word is null, so nothing is
allocated and the storage is complete before the program starts, while a built value is stored by a
prologue. That difference is why a module with `no alloc` may still hold a table of literals:

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

**A variant that carries nothing is constant data too**, which is what lets a module-level slot start
out *unset*. `None` is the one that matters: a sink nobody has installed, a handle nobody has opened,
a cache nobody has filled.

```sysl
static val held: Option[int] = None
static var chosen: Option[int] = None

pick(n: int) = chosen = Some(n)

report() -> int = chosen match
    Some(v) -> v
    None -> held.unwrap_or(0)

print(report())
pick(7)
print(report())
```

```output
0
7
```

Both slots are laid down rather than filled, so a module holding one is reachable from an `@export`
on a target with no loader to run an initializer. A variant that **carries** something is a different
matter and is stored by a prologue: a payload lives in a region every variant shares, so laying one
down means knowing which bytes of that union the variant's fields occupy, and that is a store rather
than a constant.

An enum whose variants *all* carry nothing never had this question — it lowers to its discriminant,
so `static val mode: Colour = Colour.Red` has always been a number in the object file.

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

A module `var` is one object the whole program shares, which is what makes it the thing two threads
can disagree about. `@thread_local` in front of one gives every thread a copy of its own instead, so a
name that holds *this* thread's machine, arena or scratch buffer needs no lock and no handle passed
down every call. The cost is that its initializer has to be a constant — every copy is made from one
image in the object file — and that a target with no thread-local storage refuses it rather than
emitting something silent. [`reference/attributes.md § @thread_local`](/reference/attributes/) has the
rule and the refusals.

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
names. See [Patterns](/reference/patterns/#only-an-irrefutable-pattern-may-stand-there).

**Only a pattern that cannot fail may stand at a binding** — a tuple pattern, a struct pattern, a
name, a wildcard, and those nested inside one another. A struct qualifies because it has exactly one
shape, which is the same property that makes a tuple pattern irrefutable.

A literal, a range, or a **variant** is a *test*, and a binding has no other arm to take when the
test does not match, so each is refused with that as the reason. Those belong in a `match` (see
[Patterns](../patterns/)).

Like the comma form, this is **a local form**: the parts have nowhere to carry a type, so one at the
top of a file is refused rather than becoming a quiet local of the entry point.

The same pattern stands in a
[`for` header](/reference/statements/#the-loop-variable-may-be-a-pattern), where it takes the element
apart as the loop hands it over.

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

### Tail calls

A function whose **last act** is a call to itself does not open a second frame. The call becomes a
branch back to the function's own entry, so the recursion is bounded by the arithmetic rather than by
the stack:

```sysl
count(n: int, acc: int) -> int =
    if n == 0 then acc else count(n - 1, acc + 1)

print(count(1000000, 0))
```

```output
1000000
```

A million frames is not a stack any machine has. Nothing is written to ask for this — it applies
wherever it applies.

**In tail position** is the last thing the function does: the body's trailing expression, the operand
of a `return`, and the arms of the `if` and `match` those reach through. Nothing may wait on the
result — `n + count(n - 1)` is an ordinary call, because the addition happens after it comes back.

A tail call is a call, so the jump lands where a call would: **every `require` is checked again** on
the arguments the jump wrote, and **every `old(e)` is snapshotted again**. A recursion that violates
its own precondition at depth four stops at depth four.

Two things end a tail position instead of being optimized around:

- **a `defer` in scope**, which runs on the way out of a scope — after the callee returns for an
  ordinary call, and before it is entered for a jump;
- **an `ensures` on the function**, which is checked when a call *returns*, and a tail call never
  returns.

Either one leaves the function compiled exactly as written.

It is **self-recursion only**. Mutual recursion and calls through a `Fn` or a `*extern` are ordinary
calls, however they are written.

### `@tailrec`

The jump is silent, which is what you want until an edit takes it away. `@tailrec` asserts it is
there and is refused when it is not:

```sysl
@tailrec
sum(n: int) -> int =
    if n == 0 then 0 else n + sum(n - 1)

print(sum(5))
```

```error
calls itself nowhere the jump can replace
```

It changes nothing about what is emitted — write it on the functions where losing the jump silently
would be a bug, and leave it off the rest.

### `become` — a call that replaces the frame

A tail call to the **same** function is the jump above, and it is an optimization: missing it costs
speed. A tail call to a **different** function cannot be one. A chain of them is a loop only if every
call in it is eliminated, so one that is not is an immediate stack overflow rather than a slowdown —
and a program cannot rely on something it has no way to ask for.

`become` asks for it. It is `return` with the jump guaranteed:

```sysl
even(n: int) -> bool
    if n == 0 then return true

    become odd(n - 1)

odd(n: int) -> bool
    if n == 0 then return false

    become even(n - 1)

print(even(1000000), odd(1000000))
```

```output
true false
```

A million alternating frames is not a stack any machine has, and neither is ten million. Written as
an ordinary `return odd(n - 1)` the same program is a million frames deep and whether it survives is
a question about the optimizer.

**The callee may be chosen while the program runs**, which is what the technique this exists for
needs: one function per state, each ending in a call to the next out of a table of addresses.

```sysl
step(vm: *int) -> int
    vm[0] += 1

    if vm[0] >= 5 then return vm[0]

    var table: [2]*extern(*int) -> int = [&step, &step]

    become table[vm[0] % 2](vm)

var n = 0

print(step(&n))
```

```output
5
```

**`become` is not a reserved word.** It is read as this form only where a call follows it, so a
variable or a function of that name goes on working:

```sysl
var become = 41

become = become + 1
print(become)
```

```output
42
```

#### What a `become` requires

The callee's **signature must match this function's** — the same parameter types in the same order,
and the same result. That is what makes replacing one frame with another something a machine can do
at all: the arguments land where the replaced frame's were, and the result comes back the way this
frame's caller is waiting for it.

```sysl
f(n: int) -> int
    become g(n, 1)

g(a: int, b: int) -> int = a + b

print(f(1))
```

```error
same shape
```

**No parameter may carry a reference count.** A frame lets go of what it holds along the edge it
returns on, and a `become` replaces the frame before that edge — so the releases happen *before* the
jump, and an argument read out of a slot that is about to be let go would name storage that is gone.
Pass what the callee needs as a value that carries no count, or through a `*T`.

```sysl
struct Node
    v: int

f(n: &Node) -> int
    become g(n)

g(n: &Node) -> int = n.v

var x: &Node = Node(1)

print(f(x))
```

```error
counted value
```

The rest are the tail call's own, for the reasons `Tail calls` gives: an `ensures` is checked when a
call returns and a `become` never returns; a `defer` runs on the way out of a scope and the jump *is*
the way out; a `decreases` measure is checked against the frame being replaced; a variadic function's
tail lives in that frame. An `extern` is refused too — its frame is C's — and so is a result too
large to come back in a register, which travels through a pointer the replaced frame's caller
supplied.

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
may not name anything local to the declaration. Its declaration's type parameters are not local in
that sense; see below.

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

A default is read at **the type its parameter declares**, so a value that says nothing about its own
type needs no annotation beside it: written where an `Option[int]` is wanted, `None` is that
parameter's `None`. A method reads its defaults the same way, and the receiver is not one of the
arguments a call writes.

```sysl
struct Room
    seats: int

    free(self, taken: Option[int] = None) -> int = taken match
        Some(n) -> self.seats - n
        None -> self.seats
end Room

var r = Room(40)
print(r.free(), r.free(Some(12)))
```

```output
40 28
```

A default may itself be a **closure**, and it is typed by the same thing that types one written at
the call: the parameter it stands at. Neither spelling of a callable parameter needs an annotation
inside the closure, and the placeholder form `_ * 2` needs none either.

```sysl
apply(g: int -> int = y -> y * 2) -> int = g(21)

print(apply(), apply(x -> x + 1))
```

```output
42 22
```

A default may name **its declaration's type parameters**, in either position: an associated function
reached through one, a conversion written at its name, or a type written inside the expression. A
type parameter is part of the signature rather than something local to it, and a call knows it once
the call is solved — from the arguments it wrote and the type it is expected to have — so the default
is read at each call, at the type that call settled.

```sysl
import sysl.math.Float

offset[T: Zero + Add](x: T, step: T = T.zero()) -> T = x + step
tuned[F: Float](f: F, a4: F = F(440.0)) -> F = f + a4

print(offset(2.5), offset(7), tuned(0.5))
```

```output
2.5 7 440.5
```

The default takes **no part** in settling that type: a call that leaves the argument out and gives
nothing else to settle `T` from — `start[T: Zero](x: T = T.zero())` called as `start()` with nothing
expected of it — is refused as one whose type argument cannot be inferred. What the default may *not*
name is unchanged: another parameter, and anything else local to the declaration, are as undefined
there as anywhere outside a body. And it is held to what the bounds promise where it is written, as a
body is — `T.zero()` with no `Zero` among `T`'s bounds is refused at the declaration, whether or not
anything calls it — while a conversion written at `T` is checked at each call, as one in a body is
([Converting through a parameter](/reference/generics/#converting-through-a-parameter)).

A **closure's** parameter declares no default. A call reaches a closure through the `Fn` traits,
which carry the types and not the names, so there would be nothing at the call to fill one from.

### A parameter may collect the rest of the call

**`xs: ...T` collects a call's trailing arguments into a `[]const T`**, so a function that takes a
variable number of values has one parameter rather than an arity per shape. The body sees an ordinary
slice; the call writes the values out.

```sysl
total(xs: ...int) -> int
    var s = 0

    for x in xs
        s += x

    s

print(total(1, 2, 3), total(7), total())
```

```output
6 7 0
```

The parameters in front of it are ordinary and may be named; a call that supplies nothing for the rest
parameter gets the **empty slice**, which is what "the rest" means when there is none.

```sysl
label(tag: string, xs: ...int) -> string
    var out = tag

    for x in xs
        out = out + " " + str(x)

    out

print(label("sum", 1, 2))
print(label(tag = "none"))
```

```output
sum 1 2
none
```

**The heterogeneous form is the same parameter at a trait object.** `...&Display` is what a checked
`print`-alike takes, and each argument is erased where it stands.

```sysl
show(xs: ...&Display) -> unit
    for x in xs
        print(x)

show(1, "hi", true)
```

```output
1
hi
true
```

**The array a call packs is laid out where the call is written**, not on the heap — it is the array
literal `[a, b, c][..]` the program would otherwise have written by hand — so a variadic call costs no
allocator and a module that gave one up may still make one.

`xs...` at a call hands an **existing slice** straight through, which is the case a rewrite cannot
cover: a function forwarding its own tail has a slice and not a list of values.

```sysl
total(xs: ...int) -> int
    var s = 0

    for x in xs
        s += x

    s

forward(xs: ...int) -> int = total(xs...)

var have = [7, 8]

print(forward(4, 5, 6), total(have[..]...))
```

```output
15 15
```

**Nothing may follow it**, because the arguments that would be the next parameter's are already
inside it.

```sysl
f(xs: ...int, last: int) -> int = last

print(f(1))
```

```error
collects the rest of the call, so nothing can follow it
```

Its arguments are **positional**: a name picks out one parameter and this one takes as many as are
left. It declares no default either, a call that leaves it out having an answer already.

**A trait member may declare one, and it works through a trait object.** The array a call packs is a
temporary of the caller's frame, and a trait object may hold on to what it is given — so such a call
is one where the temporary has to outlive the frame that made it, and it is given
[storage of its own](/reference/memory/) exactly as a declared array would be. That costs an
allocation at a call that escapes and nothing at one that does not.

```sysl
trait Sink
    take(self, xs: ...int) -> int

struct Adder
    base: int

impl Sink for Adder
    take(self, xs: ...int) -> int
        var s = self.base

        for x in xs
            s += x

        s

var a = Adder(10)
val d: &Sink = a

print(a.take(1, 2, 3), d.take(1, 2, 3), d.take())
```

```output
16 16 10
```

**It is not C's ellipsis**, which is the other variadic and is written `...` with no name and no type
— that tail is walked with `va_arg` and is the foreign one; see [ffi](/reference/ffi/). A declaration
may write one of them, and an `extern` may write only C's: what this form hands over is a sysl slice,
which is three words with an owner in them and not what a C function was compiled to read.

### Overloading

**A name may be declared more than once, and every use of it still means exactly one declaration.**
Which one is decided by the **arguments** a use passes: how many, and what type each is.

```sysl
show(x: int) -> string = s"int $x"
show(x: string) -> string = s"str $x"
show(x: int, y: int) -> string = s"pair $x $y"

print(show(1))
print(show("a"))
print(show(1, 2))
```

```output
int 1
str a
pair 1 2
```

**The result is never part of it.** A pair differing only in what they return is refused where the
second is written. sysl reads an expected type *inwards*, from the context to the expression, so a
call whose meaning depended on its own result would need its context typed before it could be
resolved and would need resolving before its context could be typed.

```sysl
h(x: int) -> string = "s"
h(x: int) -> int = 1
```

```error
function 'h' is already declared — which declaration a call means is decided by its arguments and never by what it returns, so two that differ only in the result have no call that tells them apart
```

**That is the plain duplicate message with its sentence finished**, and the wording is deliberate:
the two parameter lists are the same, so this *is* one declaration written twice. Somebody who wrote
that by accident is told so plainly; the clause is added only where the results differ, because that
is the pair a reader wrote on purpose and expected to work.

The rule behind it is wider than the identical case. Each declaration takes a *range* of argument
counts — from its parameters that have no default up to all of them — and two collide when their
ranges overlap at some count and their first that-many parameters agree in type. So a difference
hidden behind a default is refused too, and there the point is sharper: the default is
**unreachable**, because no call could ever supply one argument to the longer declaration.

```sysl
g(x: int) -> string = "one"
g(x: int, y: int = 0) -> string = "two"
```

```error
'g' is already declared with parameters this one could not be told from — a call passing 1 argument would fit both, and which declaration a call means is decided by its arguments and never by what it returns. Two declarations of one name have to differ in a way a call site can show
```

**Reporting that at the declaration rather than at the call is the point.** The mistake is in the
pair; reporting it where the name is *used* would report one mistake once per call site, in files
whose authors did not write it.

**A use that fits none of them, or several, is refused where the use is**, and the message carries
the roster — the reader's question at that point is which declarations exist:

```sysl
k(x: int) -> string = "a"
k(x: string) -> string = "b"

print(k(1.5))
```

```error
no 'k' takes these arguments — the declarations of that name are:
    k(x: int)
    k(x: string)
```

**Three tie-breaks decide a use that fits more than one.** A candidate that needed no default fitted
the call as written and beats one that did; and a candidate whose parameters are exactly the
arguments' own types beats one reached by a conversion — which is what lets a literal's natural type
choose between two widths:

```sysl
width(x: int) -> string = "int"
width(x: i64) -> string = "i64"

print(width(1))
print(width(1i64))
```

```output
int
i64
```

What is deliberately absent is any ranking *between* conversions. Two candidates each reached by a
different one are ambiguous, and saying so beats a ladder of precedences nobody could predict from
the source.

**Exactness is asked of the types a candidate was fitted at, which for a generic one is what the call
solved it to.** `g[T]` below takes the `[]int` at `T = []int`, as it was written; the other takes it
only by giving up the ability to write. So the generic declaration is the exact one:

```sysl
g(s: []const int) -> string = "const"
g[T](x: T) -> string = "generic"

var a = [1, 2, 3]
val v: []int = a[..]

print(g(v))
```

```output
generic
```

**The third tie-break is that a candidate that named its parameters beats one that was solved for
them, where both fitted at the same types.** `f(x: int)` and `f[T](x: T)` both fit `f(0)` at `int`,
and the ordinary declaration is the one that said what it takes:

```sysl
f(x: int) -> string = "plain"
f[T](x: T) -> string = "generic"

print(f(0))
```

```output
plain
```

That ranks a declaration against a declaration, which is a different question from ranking the routes
the arguments took — the thing the paragraph above refuses.

**It reads "at the same types" rather than "exact" because the third question is worth asking where
the second found nothing.** A type parameter is often *inside* a parameter's type rather than being
the whole of it, and then an argument may reach every candidate by a conversion — leaving no exact
candidate at all, and two declarations that nonetheless fitted the call at one signature. Both calls
below reach `g(x: []u8)`, and the array is the case where nothing is exact:

```sysl
g(x: []u8) -> string = "plain"
g[T](x: []T) -> string = "generic"

var a: [3]u8 = [1, 2, 3]
val v: []u8 = a[..]

print(g(v))
print(g(a))
```

```output
plain
plain
```

**Where the candidates fitted at *different* types it stays ambiguous**, which is the same refusal
as before: two routes out of one argument, and nothing to choose between them that is not a
preference among conversions.

**An address chooses by the type the context wants**, which is the mechanism a generic function's
address already uses. With no expected type there is nothing to read, and the address is refused
rather than guessed at.

```sysl
add(a: int) -> int = a + 1
add(a: int, b: int) -> int = a + b

val one: *extern(int) -> int = &add
val two: *extern(int, int) -> int = &add

print(one(41), two(6, 7))
```

```output
42 13
```

### Overloading an `extern`

**Two `extern`s of one name are two functions exactly when they name two symbols.** A C library's
naming is not sysl's, and a family C spells `_solid`/`_shaded`/`_blended` is one operation with an
option — which a binding may now say, without inventing a sysl name per C symbol.

```sysl
extern "strlen" size(s: *u8) -> usize
extern "strnlen" size(s: *u8, cap: usize) -> usize
```

**Two naming the *same* symbol are refused.** That is one C function claimed at two signatures, and
the symbol is what gets emitted — both calls would reach the same code with different arguments, and
nothing downstream could tell which had been meant.

```sysl
extern "strlen" size(s: *u8) -> usize
extern "strlen" size(s: *u8, cap: usize) -> usize
```

```error
'size' is already declared as an 'extern' for the symbol 'strlen' — two declarations of one name are two functions, and one C function cannot be two. Overloads of an 'extern' are told apart by the symbol each names, so give this one a symbol of its own or take its address and 'ptr_cast' it where the other signature is wanted
```

An `extern` and a sysl function do not overload each other, in either order, for the same reason:
what tells overloads of an `extern` apart is the symbol, and a sysl function declares none.

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
field to fail as whatever the grammar happened to want there.

### A property

**A property is a method with the parameter list left off** — `perimeter -> int`, called as
`r.perimeter` with no parentheses. It takes the same body forms a method does.

**The parentheses are the whole of the difference at the call, and the declaration is what decides
them.** A member declaring a parameter list is called with one; a member declaring none is read
without one. The two are not interchangeable in either direction, so the declaration settles how
every call site reads:

```sysl
struct R
    w: int

    area(self) -> int = self.w * self.w

    perimeter -> int = self.w * 4

var r = R(3)

print(r.area(), r.perimeter)
```

```output
9 12
```

```sysl
struct R
    w: int

    perimeter -> int = self.w * 4

var r = R(3)

print(r.perimeter())
```

```error
'perimeter' is a property of 'R' — read it as 'value.perimeter', without '()'
```

**That is what makes the choice a piece of documentation rather than a preference.** A property reads
as though it were a field, so it should cost what a field costs; a member that walks, allocates, or
can fail takes the list, and its parentheses are the warning. Note that `self` is what makes a member
a *method* at all — a parameter list with no receiver in it declares an **associated function**,
reached through the type rather than through a value. A property has no parameter list to write one
in, which is what [`static`](#a-static-property) is for.

### A property may be settable

**A property may be written as well as read**, by declaring a setter beside it. `set` is a contextual
word — an ordinary identifier everywhere else — and the parameter carries no type, because a setter's
value is the property's result and can be nothing else:

```sysl
struct Temp
    c: int

    f -> int = self.c * 9 / 5 + 32

    set f(v)
        self.c = (v - 32) * 5 / 9
end Temp

var t = Temp(0)

t.f = 212

print(t.c, t.f)
```

```output
100 212
```

The setter's receiver is an implicit `*self`, as the getter's is an implicit borrow, so it is held to
what any `*self` member is: the receiver needs an address, and a `val` is written once. A setter
needs a property of the same name — a set-only property would leave `t.f` meaning nothing, and would
have nowhere to take its value's type from. Visibility is the ordinary modifier, so `private set f(v)`
is a property the world reads and only the type writes.

**A write is a call rather than a store**, which is what a property computing rather than naming
storage amounts to. Three things follow, and they are the same three that follow for an element
reached through [`Index`](/reference/expressions/): the expression yields `unit` rather than the
value assigned, the write cannot be one place of a multiple assignment, and `&t.f` is refused because
there is no address.

The compound forms do work, and that is where a property differs from an element: there is no index
to evaluate twice, and the receiver's address is taken once for the pair of calls.

```sysl
struct Cell
    v: int

    count -> int = self.v

    set count(x)
        self.v = x
end Cell

var c = Cell(1)

c.count += 1
c.count *= 5

print(c.count)
```

```output
10
```

**An accessor may not reach the member it is defining.** `count -> int = self.count` calls itself,
and so does a setter writing `self.count`; there is no reading under which either is what was meant,
so both are refused rather than left to run out of stack:

```sysl
struct Cell
    v: int
    count -> int = self.count

var c = Cell(1)

print(c.count)
```

```error
'count' reads the property it is defining, so it calls itself — the value a property computes is not the property. What a body like this means to read is the field it is in front of
```

Reading `self.count` inside `set count` is left alone, and terminates: that calls the getter, which
is a different member.

**The receiver says how the method reaches its value**, and is written as the first thing in the
parameter list:

| receiver | meaning |
|---|---|
| `self` | by value — the method gets a copy |
| `*self` | by pointer — the method may write through it |
| `&self` | by reference, counted |
| `&sync self` | by reference, and safe to share across threads |
| *(none)* | an **associated function** — no receiver, called on the type |

**"A copy" is a promise about what the method can observe, not a cost it pays.** Nothing done to
the caller's value while the method runs reaches its `self`, and nothing the method does to its
`self` reaches the caller. A method that only reads a large receiver is therefore not handed a fresh
copy of it: it reads the caller's value in place, and the compiler stages a snapshot only where the
original could change during the call — through a `*T` aliasing it, say — or where the method
writes to its own `self`. So a `self` method on a struct of kilobytes costs no more stack than a
`*self` one, and the choice between them is about what the method may do, never about size.

Both halves of the promise, on a receiver of four kilobytes. `snap` is handed a pointer to the very
value it was called on and writes through it, and its `self` does not move; `bumped` writes its own
`self`, and the caller's value does not move:

```sysl
struct Big
    n: int
    table: [1024]u32

    snap(self, p: *Big) -> int
        val before = self.n
        p.n += 1
        before * 100 + self.n

    bumped(self) -> int
        self.n += 1
        self.n

var b = Big(1, [0; 1024])
print(b.snap(&b))
print(b.bumped(), b.n)
```

```output
101
3 2
```

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

### A `&self` method may keep what it was called on

**The three receivers differ in what a method may do with the value *after* it returns, and that is
the whole of the distinction.** `self` hands the method a copy, so nothing it builds can refer back
to the original; `*self` hands it an address, which is good for the length of the call and cannot be
kept, because the caller's value may be gone by the time anything read it. **`&self` hands it a share
of the box**, so a value the method constructs may hold on to the receiver and outlive the call.

**That is what makes a resource handle writable as one type.** A method taking `self` cannot name the
box it was called through — it has a copy, and constructing a fresh value around the same resource
would hand out a *second owner*, so a destructor would run twice. `&self` gives it the box itself:

```sysl
struct Env
    private n: int

    read(&self) -> Txn = Txn(self.n + 1, self)

impl Drop for Env
    drop(self)
        print("env closed")

struct Txn
    private t: int
    private env: &Env

    which(self) -> int = self.t
    depth(self) -> int = self.env.n

var e: &Env = Env(7)
var first = e.read()
var second = e.read()

print(first.which(), second.depth())
print("done")
```

```output
8 7
done
env closed
```

**Two things in that output are the point.** `env closed` prints **once** although two transactions
were made from one environment — they hold shares of one box rather than owners of two. And it prints
**last**: the environment outlives every value that kept a share of it, which is exactly the
guarantee a handle needs, since a transaction that outlived its environment would be reading freed
memory.

**A `&self` method needs a box to be called on, and a value on the stack is not one.** The receiver
has to be something a share can be taken of, so this is refused rather than silently copied:

```sysl
struct Counter
    n: int

    keeper(&self) -> Keeper = Keeper(self)

struct Keeper
    private c: &Counter

    reading(self) -> int = self.c.n

var plain = Counter(5)

print(plain.keeper().reading())
```

```error
'&self' needs a counted reference, and this receiver is a Counter on the stack — a '&self' method may keep hold of what it is called on, so what it wants is a share of a box rather than an address. Bind the value into one and call it on that: 'var r: &Counter = …'
```

Binding it into a box is the whole of the fix, and the annotation is what makes the binding one:

```sysl
struct Counter
    n: int

    keeper(&self) -> Keeper = Keeper(self)

struct Keeper
    private c: &Counter

    reading(self) -> int = self.c.n

var boxed: &Counter = Counter(5)

print(boxed.keeper().reading())
```

```output
5
```

**Declaring one `&self` member costs the type nothing else.** A type may take all three receivers,
and the other two behave on a boxed value exactly as they do on a stack one — `Counter` above could
carry a `doubled(self)` and a `bump(*self)` beside `keeper(&self)`, and every one of them would be
callable on `plain` and on `boxed` alike. The receiver is a property of the *member* rather than of
the type, which is what lets a handle expose an ordinary read by value beside a method that hands its
box on.

**The one place a `&self` method cannot be reached is through a `*Trait`**, since a raw trait object
holds no box for a share to be taken of. `reference/traits.md` says so where it lists what a `*Trait`
may carry.

### A static property

**A property has nowhere to say `self`.** A member's receiver is written in its parameter list, and
a property is a method with that list left off — so the one thing that separates an instance member
from an associated one cannot be spelled, and every property above is an instance member by
construction. `static` is what says otherwise:

```sysl
struct Temp
    n: int

    static freezing -> int = 32

print(Temp.freezing)
```

```output
32
```

It is read on the **type**, with no parentheses — `Temp.freezing`. That is an associated function
whose call the reader does not write, and it is what the form is: `static` says the member belongs to
the type rather than to a value of it, and everything else about a property is unchanged, including
the body forms and the rule about what a property should cost.

**Reading one on a value is refused**, which is the mirror of the refusal an instance property gets
when it is read on a type:

```sysl
struct Temp
    n: int

    static freezing -> int = 32

print(Temp(1).freezing)
```

```error
'freezing' is a property of the type 'Temp' rather than of a value of it — read it as 'Temp.freezing'
```

**The body has no receiver either**, so it cannot name `self`. That is why the word is required
rather than inferred: "a property that never names `self` is static" would make a member's
reachability depend on its body, so deleting a `self.` from an expression would silently move the
member from the value to the type and break every call site.

**A trait may require one, and that is what a static property is chiefly for.** A type parameter is
not a value, so a fact *about the type* — a zero, a limit, a width — has nowhere to arrive from
unless a bound can carry it:

```sysl
trait Bounded
    static lowest -> int

struct Age
    n: int

impl Bounded for Age
    static lowest -> int = 18

lowest_of[T: Bounded]() -> int = T.lowest

print(lowest_of[Age]())
```

```output
18
```

`lowest_of` never mentions `Age`, and there is no value of it anywhere in the program.

**A trait's static property is a requirement rather than a default**, so it carries no body — the
same rule every receiverless trait member has, since a default would give every implementation one
constant:

```sysl
trait Bounded
    static lowest -> int = 0

struct Age
    n: int

impl Bounded for Age

print(Age.lowest)
```

```error
'Bounded.lowest' is a property of the type, so a default body would give every implementation the same value — drop the body and let each one supply it
```

**It takes no type parameters**, for the reason an ordinary property does not: a read has no
arguments and no receiver, so there would be nothing to solve them from. And a member written with a
parameter list is an associated function already, reached the same way and called with `()` — so
`static` in front of one is refused rather than being a second spelling of what already exists.

`static` is not a new word: it is the same one that makes a binding in an entry file belong to the
module rather than to that file's body ([bindings](#a-module-member-states-its-type)). The two are
told apart by what follows it.

### `invariant`

A condition every value of the struct must satisfy, re-checked whenever the struct is built or one of
its fields is written. Bare field names are in scope inside it. A multiple assignment re-checks it
**once**, after every write has landed, which is what lets two fields that relate to each other be
changed together.

`invariant` is a contextual word — an ordinary identifier everywhere else. See
[errors and contracts](/reference/errors/) for what happens when one is broken.

### A struct with no fields

A struct may declare no fields at all. Its emptiness has to be *written* — the `end` marker, optional
everywhere else, is what says so — because a struct whose body the author forgot to indent looks
exactly like one that has no body, and that is much the likelier mistake.

```sysl
struct Stdout
end Stdout

impl Fallible for Stdout

impl Writer for Stdout
    write(*self, bytes: []const u8) = putbytes(bytes)

show[T: Display](x: T)
    var out = Stdout()

    x.display(&out, FormatSpec(0, -1, false))
    printc('\n')

show(42)
show("through a sink of one's own")

print(sizeof(Stdout))
```

```output
42
through a sink of one's own
0
```

What wants one is a **sink**: a value standing for a destination fixed at compile time — the console,
a serial port — which has nothing to keep and so has no field to keep it in. Being a value rather
than a global is what lets it be passed to a function, held in a struct, and chosen by a caller.

Such a type occupies no bytes, so embedding one costs the struct holding it nothing. The cost of that
is that two of them have nothing to tell their storage apart, and `&a == &b` on two such locals may
well be true. There is no state behind either address for the answer to be about.

### `opaque`

`opaque struct Name` withholds the layout from every module but the one declaring it. Outside, the
type is *incomplete*: only `*Name` may be said, so a value cannot be built, copied, or have a field
read. This is a **different axis from visibility** — `private` decides who may say the name, `opaque`
decides who may know the shape. See [modules](/reference/modules/).

An opaque struct with no body at all is C's incomplete type, `struct sqlite3;` — nothing in sysl lays
one out, and the declaration exists so that `*Session` is a type a `*u8` cannot be mistaken for.

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

**An underlying type belongs to a simple enum alone.** `: u8` pins the storage of a set of
discriminants; a data enum has a payload beside its tag, so there is no single integer for the
annotation to be about and the tag's width is the compiler's to choose:

```sysl
enum Shape: u8
    Circle(r: real)
    Rect(w: real, h: real)

print(Circle(1.0))
```

```error
only a simple enum has an underlying integer type — 'Shape' carries data
```

`Option[T]` and `Result[T, E]` are ordinary data enums declared in the standard library, with no
compiler privileges.

## Type declarations

`type Name = Existing` introduces a second spelling, interchangeable with the first. It creates **no**
new type and no checking — an alias is for shortening a name that has grown long. The base may be
anything a type expression can name:

```sysl
struct Reading
    at: int
    value: f64

type Sample = Reading
type Window = [4]Sample

var w: Window = [Sample(0, 1.5), Sample(1, 2.5), Sample(2, 3.5), Sample(3, 4.5)]

print(w[2].at, w[2].value)
```

```output
2 3.5
```

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

The one place it is **required** is a struct with no fields, where it is the only thing distinguishing
a body that is deliberately empty from one that was meant to be there.

`end` is a **soft** word: it is an ordinary identifier everywhere except immediately before a name or
a construct keyword, so `end` stays usable as a variable.

---

Next: [patterns and matching](/reference/patterns/).
