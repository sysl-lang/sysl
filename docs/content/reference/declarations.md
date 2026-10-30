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
| `const Name = v` | no, and known while compiling | required | optional |

**A `const` takes its type exactly as a `val` does**: left off, the type is the value's own —
`const Step = 2` is an `int`, `const Greeting = "hello"` a `string`, `const Area = sq(3)` whatever
`sq` answers. Written, `const Name: T = v`, the type governs: the value is read at `T`, and refused
where `T` does not hold it. A `val` or a `const` with no value is not a declaration of anything.

```sysl
const Step = 2
const Greeting = "hello"
const Half: real = 1

sq(x: int) -> int = x * x

const Area = sq(Step + 1)

var cells: [Area]u8

print(Step * 10, Greeting, Half / 4, Area, cells.len)
```

```output
20 hello 0.25 9 9
```

```sysl
const Limit: u8 = 300
```

```error
'Limit' does not fit byte: 300
```

### A `const` inside a body

A `const` may be written inside a body too. Its value is computed **while compiling**, where it is
written, and every use of the name is that value — so it stands wherever a constant is wanted: an
array length, a value argument, a pattern, an `@assert`. It belongs to its block, as a `val` does,
and may read a value parameter, another constant, and any function whose body can run:

```sysl
sq(x: int) -> int = x * x

twice[const N: usize]() -> usize
    const D: usize = N * 2
    D + 1

main()
    const L = sq(6)
    var a: [L]int

    print(L, a.len, twice[4](), twice[L]())
```

```output
36 36 9 73
```

That last call is the point of it: `twice[L]()` hands `L` to a value parameter, which a `val` could
not be. A `val` over the same call is computed when the program runs, unless the optimiser folds it.

**It reads nothing the body binds**, since a binding of the body holds a value only once the program
runs:

```sysl
main()
    var n = 3
    const A: int = n + 1
    print(A)
```

```error
'A' is a constant, so its value is known while compiling, and it reads 'n', which the body binds and which holds a value only once the program runs. A name bound to such a value is a 'val'
```

Its type follows the rule for a module's `const` — [a scalar](/reference/modules/#const-a-value) —
and a value its type does not admit is refused while compiling. It has no storage of its own, so
nothing assigns to it and `&` does not reach it.

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

### A literal binding takes its type from its first use

A local `var` or `val` with no type written whose value is a bare number — `0`, `-1`, `2.5` — takes
its type from its **first use that fixes one**: a comparison or arithmetic with an operand that has
a type of its own, an assignment of such a value or into a typed place, an argument, a `return` or
the body's result. A counter compared with a length is a `usize`, and an index read with it needs no
conversion:

```sysl
val xs = [10, 20, 30]
val first = 0
var i = 0

while i < xs.len
    print(xs[i])
    i += 1

print(first < xs.len, i)
```

```output
10
20
30
true 3
```

**The undecided type flows through arithmetic.** `i + 1`, `i * 2`, `-i`, `(i + 1) * 2` and `i + j`
over two such bindings have the bindings' type, whatever it turns out to be, so a use that fixes the
arithmetic fixes them: `i + 1 < xs.len` makes `i` a `usize` exactly as `i < xs.len` does, and
`wide(i + 1)`, `return i + 1` and `val x: u8 = i + 1` make it the parameter's, the result's or the
annotation's type. **An operand of a type of its own beside them in arithmetic fixes them too**, as
Rust's integer variables are fixed: in `sum + b` with `b` a `u8`, `sum` is a `u8`, and so is the
sum. The operators are `+ - * / % & | ^` and their wrapping and saturating forms, in either order and
through nesting — `b + sum`, `(sum + 1) * b` and `sum + b + c` each fix `sum`. A shift has the type
of the value shifted; its amount is fixed by nothing the shift is used at, and the shifted value
only by where the shift is used.

```sysl
val xs = [10, 20, 30, 40]
var i = 0

while i + 1 < xs.len
    print(xs[i] + xs[i + 1])
    i += 2
```

```output
30
70
```

**The type fixed is the one the arithmetic or the comparison computes in.** Arithmetic on a subtype
that checks a range or a predicate happens at its base ([errors](/reference/errors/)), and so does a
comparison with one, so such an operand fixes the binding at the base, and a total over a narrow
range is free to pass it:

```sysl
type Digit = int within 0..9

var sum = 0

for d in Digit::Range do sum += d

print(sum)
```

```output
45
```

A comparison fixes the binding the same way, whichever use comes first, so `x` here is an `int`
and passing 9 is no trap; written `var x: Digit = 0`, it would stop the program at the `+=`:

```sysl
type Digit = int within 0..9

val limit: Digit = 9
var x = 0

while x <= limit do x += 4

print(x)
```

```output
12
```

A derived subtype is a type of its own, so it fixes nothing at its base: `sum + d` or `sum < d`
over a `new int within 0..9` is refused for mixing an `int` with it, as it is with `int` written.

**A conversion fixes nothing**: `u8(n)` converts `n` from what it is rather than deciding it.
**Nor does an index**, nor a slice's bounds: an array, a
slice or a pointer is indexed by any integer, so `xs[i]` asks no type of `i` and the next use that
does ask one decides; the same goes for a vector run's start and a slot's index. A subscript that a
method answers with a `usize` parameter — a `Buf`'s, or a type's own `Index[usize, E]` — takes any
integer the same way, so it fixes nothing either; one whose parameter is some other type — a table
keyed by `string`, an `Index[int, E]` — hands the index to it as an argument like any other. A binding no use fixes has the literal's default: `int`, or `real` for a
float literal, so a literal `int` cannot hold, used only as an index, is refused as it would be with
`int` written, and a negative one stops the program at the subscript. A use whose type cannot hold
the literal — `300` handed to a `u8` — fixes nothing either, and is refused as it would be with
`int` written. An integer literal is fixed at a float type where it holds the value exactly. A use
inside a closure or a nested function is a use like any other.

**A generic call reads such a binding as it reads the literal itself**, as Rust's integer variables
unify: it decides no type parameter while an argument with a type of its own is there, and is fixed
at the parameter the call arrives at. In `pick(k, b)` with `b` a `u8`, the call is a `u8` call, in
either order, and `k` a `u8`; with no typed argument beside it, it is an `int`.

```sysl
pick[T](a: T, b: T) -> T = b

val b: u8 = 10
val k = 250

print(pick(b, k) +% 10)
```

```output
4
```

**A compound assignment is the same arithmetic written back**, so `s += b` fixes `s` at `b`'s type
just as `s + b` does, and `total += k` fixes an undecided `k` at `total`'s:

```sysl
half(x: f32) -> f32 = x / 2

val b: u8 = 10
var sum = 250
var s = 3
var h = 3

s += b
print(sum +% b, s, half(h), h / 2)
```

```output
4 13 1.5 1.5
```

The sum wraps at the byte's width, as it would with `u8` written on `sum`. Arithmetic beside a typed
integer operand **holds the literal to the type even where it does not fit**, as it holds a literal
written there: `300` beside a `u8` is a mistake in what was written, not an `int` sum, and is refused
at the literal. A
float literal beside an integer operand fixes nothing, and the integer widens to `real`:

```sysl
val b: u8 = 10
var s = 300

print(s + b)
```

```error
the literal 300 does not fit byte
```

Here `n` indexes `firsts` first, but it is the result that decides, so `n` is the `int` the function
returns:

```sysl
val firsts = [0, 1, 9, 22, 35, 48, 59, 64, 69]

count_of(a: int) -> int
    var n = 8
    while firsts[n] > a do n -= 1
    n

print(count_of(30), count_of(70))
```

```output
3 8
```

**The first such use decides**, and a later use that disagrees is told exactly as it would be had the
type been written on the declaration:

```sysl
wide(n: usize) -> usize = n
narrow(n: u8) -> u8 = n

var i = 0

print(wide(i))
print(narrow(i))
```

```error
'n' of 'narrow' is u8, but usize was given
```

**A `for` over a range whose bounds are both bare literals follows the same rule.** In `for s in
1..3` the variable takes its type from its first use that fixes one, and the bounds are read at that
type. Only an integer type fixes it. A loop with no such use, or with only an index or a float
argument, counts in `int`. `for all` and `for some` read their ranges the same way. A range held in a
binding (`val r = 1..3`) already has its type, as does one with a bound of a type of its own (`0..<n`,
`0u16..<3`).

```sysl
seed(n: u64) -> u64 = n * 1000000007

for s in 1..3
    print(seed(s))
```

```output
1000000007
2000000014
3000000021
```

**A `for` variable is fixed by arithmetic with an operand of a type of its own, as a literal binding
is**: beside an integer operand of `+ - * / % & | ^` in either order, through nesting, and under a
compound assignment — `n += k`, `n = n - k`, `m * k`, `k + m` and `(m + k) * 2` each make `k` the
type of `n` or `m`. A shift is not arithmetic between its operands: `m << k` and `m <<= k` fix
nothing of `k`, and `(k << 1) + m` fixes it through the `+`. A literal operand fixes nothing
(`k + 1`), and nor does a float, since the loop counts in integers.

```sysl
var n: usize = 0
var m: u64 = 1

for k in 1..20 do n += k
for k in 1..5 do m *= k

print(n, m)
```

```output
210 120
```

A bound that does not fit the type a use fixed is refused at the bound. The top bound of an exclusive
range is refused as well, because it is still a value of the range's type:

```sysl
narrow(n: u8) -> u8 = n

for i in 0..<256
    print(narrow(i))
```

```error
the literal 256 does not fit byte
```

A comparison with a narrow value is a use that fixes the variable, so arithmetic on it is at that
width, as it would be with the type written on a bound — here `+%`, which wraps:

```sysl
val limit: u8 = 252

for i in 250..<255
    if i == limit then print("at the limit")
    print(i +% 10)
```

```output
4
5
at the limit
6
7
8
```

### A module member takes its type as a local does

A **member of a module** may leave its type off as a local may: `val limit = 10` is an `int`
wherever it is declared, `val name = "sysl"` a `string`, and `val area = square(6)` whatever `square`
returns. A module member's literal is its default, `int` or `real`: its uses are spread across the
module, so no one of them is first. Where the type is written it governs, and the value is read against it — `val ratio: real =
3` is a `real` three. A `const` follows the same rule, and so does module storage written with `var`
— `static var ticks = 0` is an `int` the program may write. Only a `var` with **no initializer**
states its type, since there is no value to read one off and it starts at its type's zero.

The type is the initializer's wherever the name is read — in a body, in another `val`'s initializer
above or below it, in another file of the module, or in a program built against a library that exports
it — and `sysl doc` shows the declaration as it was written. What the value is decides, as it always
did, whether the storage is laid into the object file or filled before the first statement.

```sysl
const Limit = 3
static val Scale = 2
static val Ratio: real = 3
static var calls = 0

show()
    var count = 0
    val doubled = Limit * Scale
    val name = "sysl"

    count += 1
    calls += 1

    print(count, doubled, name, Ratio / 2, calls)

show()
show()
```

```output
1 6 sysl 1.5 1
1 6 sysl 1.5 2
```

The `static` is what asks for the member — this is the file the program starts in, so a plain `val`
there would be a local of its body.

**Two values give a module `val` or `var` no type to take.** One whose initializer reaches the
storage itself has nothing to read the type from first, and one whose value is a bare closure has a
type with no name for storage to be declared at — a function is the declaration for that:

```sysl
static val a = b + 1
static val b = a + 1

print(a)
```

```error
'a' takes its type from its value, and the value reaches 'a' again — write its type, 'val a: T = …'
```

A loop is one mistake and is reported once, at the declaration it is named by: storage refused a
type for it is not refused again for the order it would run in, and a binding that names several
things is reported once for the binding, however many of its names the value reaches.

**A module-level `val` may hold a counted value, and never releases it.** Storage that exists for the
whole run is never let go of, so the one release with nowhere to go is the one at exit — and not
taking it is what a static *is*. A `&T`, a `weak T`, a slice and a `string` the program **builds**
are all admissible:

```sysl
static val name: string = 2026.to_string()

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
import sysl.raw.ptr_cast

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

**A module takes it too**, since module storage takes its type as a local does: each name is a module
`val` — or, written with `var`, module storage — of its part's type, read from the module's
functions, its other files and the modules that import it like any other. A list of values is shared
out one each, so a part over a constant is constant data; one value taken apart is computed once,
however many names it gives. Each name a `var` binds is storage of its own, written without touching
the others.

```sysl
static val width, height = 640, 480
static val q, r = halves(17)

halves(n: int) -> (int, int) = (n / 2, n % 2)

area() -> int = width * height

print(area(), q, r)
```

```output
307200 8 1
```

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

**A pattern binding may state the type of the whole value**, written after the pattern as a plain
binding writes it after the name. It is the position the value is read in, so a literal inside the
value takes its width from it, and the parts are taken apart at the stated type:

```sysl
show()
    val (a, b): (u64, u8) = (4000000000, 250)
    var (lo, hi): (i16, i16) = (-1, 1)

    hi = hi + 1

    print(a + u64(b), lo, hi)

show()
```

```output
4000000250 -1 2
```

A value the stated type does not fit is refused in the words a plain binding's is:

```sysl
show()
    val (c, d): (u64, u64) = (5, "x")

    print(c, d)

show()
```

```error
cannot initialize the parts of this binding: declared (ulong, ulong) but the value is (ulong, string)
```

Like the comma form, it **is a module `val` per name** at the top of a file — module storage per name,
written with `var` — and a type written for the whole value governs there as it does here:

```sysl
static val (lo, hi): (u8, u16) = (1, 300)
static var (count, limit): (u8, u16) = (0, 2)

span() -> u16 = hi - u16(lo)

count += 1
limit *= 100
print(lo, hi, span(), count, limit)
```

```output
1 300 299 1 200
```

The same pattern stands in a
[`for` header](/reference/statements/#the-loop-variable-may-be-a-pattern), where it takes the element
apart as the loop hands it over.

## Functions

A name, a parameter list, an optional `-> result`, and a body. There is no keyword: the shape is what
identifies it.

The body is either `= expr` — whose value is the result — or an indented block, whose **trailing
expression** is the result.

```sysl
double(n: int) = n * 2

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

### The result type

**A body written `= value` with no `-> T` takes the value's type**, exactly as a `val` written with
no type takes its initializer's. A body that is an indented block with no `=` and no `-> T` hands back
nothing: its result is `unit`, and the value its last line comes to is discarded.

```sysl
twice(x: int) = x * 2
name() = "sysl"
pair[T](x: T) = (x, x)
area(w: int, h: int) =
    val inner = w * h

    inner + 1

shout(word: string) = print(word + "!")

tally()
    42

print(twice(21) + 1, name(), pair(2.5).1, area(3, 4))
shout("hey")
print(tally() == ())
```

```output
43 sysl 2.5 13
hey!
true
```

A generic function's result is the body's type in terms of its parameters, so `pair` above is
`(T, T)` and each call fixes `T`. A literal body takes the literal's own type, as a `val`'s
initializer does — `byte() = 255` is an `int`; a function that wants a `u8` writes `-> u8`.

**A body that is an assignment written `=` is `unit`**, plain or compound, to a variable, a field or
an element, because [that assignment is](/reference/expressions/#assignment): the store is what the
function is for, so `bump() = count += 1` is `bump()`, not an `int` function. A body written `:=`
hands back what it stored, and takes its type.

```sysl
var count = 0

struct Light
    on: bool

    switch_on(*self) = self.on = true

bump() = count += 1
reset_to(v: int) = count := v

var lamp = Light(false)

bump()
lamp.switch_on()
print(count, lamp.on, bump() == (), reset_to(10) + 1, count)
```

```output
1 true true 11 10
```

Using such a call as a value is refused, since there is none:

```sysl
var count = 0

bump() = count += 1

val next: int = bump()
```

```error
cannot initialize 'next': declared int but the value is unit
```

**The result is known wherever a written one would be.** A call above the declaration, in another
file of the module, in another module, or through a library reads it, in any order. `sysl doc` and an
editor's hover show the declaration at the type it was read as, exactly as though `-> T` were written
— `twice(x: int) -> int`, and `shout(word: string)` with no arrow for a `unit` one — and never show
the body. A signature whose result could not be read, in a tree that does not compile, is shown as
`twice(x: int) = …`.

**A body that reaches its own function before the result is known has no type to take**, directly or
through another function that does the same, and is refused at the declaration. Writing the result on
any one function of the loop settles it:

```sysl
fact(n: int) = if n <= 1 then 1 else n * fact(n - 1)

print(fact(5))
```

```error
'fact' takes its result type from its body, and the body reaches 'fact' again — write its result type, 'fact(…) -> T'
```

```sysl
fact(n: int) -> int = if n <= 1 then 1 else n * fact(n - 1)

print(fact(5))
```

```output
120
```

The rest of what a result read off the body is held to:

- **It is the signature's**, so it obeys the
  [visibility rule](/reference/modules/#a-declaration-may-not-be-more-visible-than-the-types-it-names)
  exactly as a written one does: a public function whose body yields a type private to its file is
  refused at the declaration.
- **A member of an `impl` block for a trait takes the trait's result.** The block is held to the
  trait's signature, so `size(self) = 255` supplying a trait's `size(self) -> u8` is a `u8`, and its
  body is read against it. A trait's own member states its result, since every implementation is held
  to it: a default written `= value` with no `-> T` is `unit`.
- **A `@test` function and a hook return nothing** whatever their `= value` comes to.
- **C reads what an `@export` or `@convention` function hands back**, so one whose body yields a value
  writes its result type; one whose body is `unit` need not.
- **A body that hands back a bare closure is refused**: its type has no name for a signature to state,
  so the function writes `-> &Fn(…) -> R`.
- An `async` function's task is of its body's type, and a `const` function's value folds as a written
  one's does.

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

Three things end a tail position instead of being optimized around:

- **a `defer` in scope**, which runs on the way out of a scope — after the callee returns for an
  ordinary call, and before it is entered for a jump;
- **an `ensures` on the function**, which is checked when a call *returns*, and a tail call never
  returns;
- **an argument that may point into the frame**. A jump reuses the frame: the locals are released on
  the way to it, and the next round writes every slot again, so `&sub` handed down would be read
  after `sub` was freed or written over. Where the body takes the address of its own storage
  anywhere — `&` of a local, a parameter or a temporary, or a slice of a local array — a self-call
  whose arguments can hold an address (a pointer, a slice, or a value made of one) stays a call. A
  parameter passed on as it arrived — never written, its address never taken — is the caller's, and
  does not count.

Any of them leaves the function compiled exactly as written:

```sysl
import sysl.buf.{Buf, buf}

struct P
    toks: Buf[string]
    pos: usize

decl(p: *P, base: string) -> string
    if p.pos < p.toks.len() && p.toks[p.pos] == "*" then
        p.pos += 1
        return decl(p, base + "*")

    if p.pos < p.toks.len() && p.toks[p.pos] == "("
        var inner: Buf[string] = buf()
        inner.push("*")
        var sub = P(inner, 0)
        return decl(&sub, base + "()")

    base

var toks: Buf[string] = buf()
toks.push("(")
var top = P(toks, 0)
print(decl(&top, "void"))
```

```output
void()*
```

The call that passes `&sub` keeps its frame, so `sub`'s buffer is alive while the callee reads it.

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

A self-call in tail position that may hand down an address in the frame is refused with its own
reason:

```sysl
struct S
    n: int

@tailrec
walk(s: *S, acc: int) -> int
    if s.n == 0 then return acc

    var next = S(s.n - 1)

    walk(&next, acc + s.n)

var s = S(4)
print(walk(&s, 0))
```

```error
its tail call may pass an address in its own frame
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

**No argument may point into the frame being replaced.** The callee runs in the replaced frame's
place, so `&` of a local, a parameter or a temporary — or a slice of a local array — would hand it
storage that no longer exists. The test is the one a self tail call is held to: where the body takes
the address of its own storage anywhere, an argument that can hold an address (a pointer, a slice,
or a value made of one) is refused, unless it is one of the function's own parameters passed on as
it arrived, which points at its caller's storage:

```sysl
struct S
    n: int

f(s: *S, acc: int) -> int
    if s.n == 0 then return acc

    var next = S(s.n - 1)

    become g(&next, acc + s.n)

g(s: *S, acc: int) -> int = f(s, acc)

var s = S(4)

print(f(&s, 0))
```

```error
an argument may point into this frame
```

```sysl
struct S
    n: int

f(s: *S, acc: int) -> int
    var probe = S(0)
    val seen = &probe

    if s.n == 0 then return acc + seen.n

    s.n -= 1

    become g(s, acc + 1)

g(s: *S, acc: int) -> int = f(s, acc)

var s = S(4)

print(f(&s, 0))
```

```output
4
```

The rest are the tail call's own, for the reasons `Tail calls` gives: an `ensures` is checked when a
call returns and a `become` never returns; a `defer` runs on the way out of a scope and the jump *is*
the way out; a `decreases` measure is checked against the frame being replaced; a variadic function's
tail lives in that frame. An `extern` is refused too — its frame is C's — and so is a result too
large to come back in a register, which travels through a pointer the replaced frame's caller
supplied.

### `@abandons` — a call that leaves its caller for good

A call to a function answering `never` does not come back, and two kinds of departure look alike at
the call. A trap, a `panic`, C's `exit`: the program is over, and what its frames hold is left exactly
as it is — the state a debugger or a core file wants to see, which is why a trap runs nothing. A
thread that ends, or a jump back to a context saved earlier: the program **goes on**, and the stack
the call was made on is never resumed, so every count a frame on it holds is storage nothing will
ever free.

`@abandons` says a function is the second kind. **A call to one ends the caller's frame first,
exactly as a `return` would** — its deferred statements run and its counts are given back, in the
one reverse order a block is undone in — and then the call is made:

```sysl
struct Node
    v: int

impl Drop for Node
    drop(self)
        print("dropped", self.v)

@abandons
finish() -> never
    exit(0)

work()
    val n: &Node = Node(7)

    defer print("deferred", n.v)

    print("working")
    finish()

work()
```

```output
working
deferred 7
dropped 7
```

Without the annotation the same program prints `working` and nothing else: `exit` ends the process
with the node still counted, which is what a trap should do. That is why `panic`, `assert` and the
library's `exit` are not `@abandons`, and why nothing changes for a program that never writes it.

It marks an `extern` as well, which is where a context switch or a `longjmp` written in assembly is
declared:

```sysl
@abandons
extern "leave_user" leave_user(kernel: *u64, status: u64) -> never
```

#### What an `@abandons` function requires

It answers `never`, since a call that comes back has left nothing:

```sysl
@abandons
finish() -> int = 0

print(finish())
```

```error
'@abandons' says a call to 'finish' never comes back, so 'finish' answers 'never' — this one answers 'int'
```

**No parameter may carry a count.** The caller's frame is given back before the call, and the
callee never returns to give back what its own parameters hold, so nothing would. Pass what it needs
as a value that carries no count, or through a `*T` — `become`'s rule, for `become`'s reason. A type
parameter is refused for the same one, since it may be such a type:

```sysl
@abandons
finish(why: string) -> never
    exit(1)

finish("done")
```

```error
nothing is left to give back the count its parameter 'why' holds
```

#### A frame further down holds nothing across the call

The caller is given back; the frames below it are abandoned too, and cannot be, because a function
that merely *reaches* an `@abandons` call may also come back. So the compiler follows the calls — as
it does for [`@no_alloc`](/reference/modules/) — and refuses a frame that holds something across a
call that can arrive at one: a counted local, a binding a `match` took a share of, a counted
temporary the enclosing expression made, or a `defer` still to run.

```sysl
@abandons
leave(code: int) -> never
    exit(code)

handle(n: int)
    if n == 0 then leave(1)

run()
    val s = 1.to_string() + "x"

    handle(2)
    print(s)

run()
```

```error
'handle' can reach a call that abandons every frame below it and never comes back, and this frame holds 's' across the call
```

The repair is the one the message names: hold the value in a function that returns before the call
is made, which leaves the frame that makes it holding nothing.

```sysl
@abandons
leave(code: int) -> never
    exit(code)

handle(n: int)
    if n == 0 then leave(1)

show()
    val s = 1.to_string() + "x"

    print(s)

run()
    show()
    handle(2)
    print("done")

run()
```

```output
1x
done
```

**A string literal and a module `val` are not temporaries.** A literal is static data with no count,
and a module `val` — laid down as data, or computed once at startup — is storage nothing ever
releases, so a share of it stranded by the abandoning call strands nothing; the same holds for a
field or element laid inside one by value. A module `var` is different: assigning it gives its share
back, so a frame passing one is refused as holding *a share of the module 'var'*.

```sysl
@abandons
leave(code: int) -> never
    exit(code)

val OUT_OF_BOUNDS = "index out of bounds"

trap_with(message: string)
    print(message)
    if message.bytes.len == 0 then leave(1)

main()
    trap_with(OUT_OF_BOUNDS)
```

```output
index out of bounds
```

**A call through a value or a trait object cannot be followed**, so nothing that can arrive at an
`@abandons` call may become one — not the function's address, not a closure or function that reaches
it, and not a type whose table for a trait object holds one:

```sysl
@abandons
leave(code: int) -> never
    exit(code)

val p = &leave

print(1)
```

```error
'leave' is '@abandons', and a call through a value cannot be followed to it
```

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

An assignment written `=` yields nothing, so it is never an argument, and `name = value` there is
always the named argument. A store whose value is passed on is written
[`:=`](/reference/expressions/#assignment), which reads as no parameter's name: `f(n := 1)` stores to
`n` and passes what it stored.

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

A **trait member's** defaults and parameter names are part of the trait's signature, so they reach
every call made through it: through a bound on a type parameter (a method, an associated function, a
`where` clause, an associated type's bound) and through a trait object, exactly as on the type that
implements it. The default is the trait's on every path, which is why an `impl` block's member
declares none — a call through an object or a bound has no implementation to read one from — and an
implementation that replaces the member keeps the trait's.

```sysl
trait Greet
    greet(self, n: int = 3, m: int = 10) -> int = n + m

struct A
    x: int

impl Greet for A

via[G: Greet](g: G) -> int = g.greet()
named[G: Greet](g: G) -> int = g.greet(m = 100)
obj(g: &Greet) -> int = g.greet(n = 0)

print(A(1).greet(), via(A(1)), named(A(1)), obj(A(1)))
```

```output
13 13 103 10
```

A trait's default may name **the trait's own type parameters**, and it is read at what the `impl`
fixed them to — `f64` below, on every one of those paths. Where the `impl` is generic itself, at what
each instantiation gives its parameter. The default is still held to the trait's bounds where it is
written: `T.zero()` in a `trait Pad[T]` with no `Zero` among `T`'s bounds is refused, though every
type that implements it might have a `zero`.

```sysl
trait Pad[T: Zero + Add]
    pad(self, x: T, by: T = T.zero()) -> T = x + by

struct A
    x: int

struct W[U]
    u: U

impl Pad[f64] for A
impl[U: Zero + Add] Pad[U] for W[U]

via[P: Pad[f64]](p: P) -> f64 = p.pad(2.5)
obj(p: &Pad[f64]) -> f64 = p.pad(4.5)

val n: int = W(3).pad(4)

print(A(1).pad(1.5), via(A(1)), obj(A(1)), n)
```

```output
1.5 2.5 4.5 4
```

```sysl
trait Pad[T]
    pad(self, x: T, by: T = T.zero()) -> T = x
```

```error
'zero' needs 'T: Zero'
```

```sysl
trait Greet
    greet(self, n: int = 3) -> int = n

struct A
    x: int

impl Greet for A
    override greet(self, n: int = 5) -> int = n * 10
```

```error
a member of an 'impl' block declares no default
```

A parameter that has no default is still owed through a bound, and the refusal names the trait's
member, since that is the signature the call was read against:

```sysl
trait Greet
    greet(self, n: int, m: int = 1) -> int = n + m

via[G: Greet](g: G) -> int = g.greet(m = 2)
```

```error
method 'Greet.greet' was given no value for 'n', and that parameter declares no default
```

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
        out = out + " " + x.to_string()

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

**The one exception is a bare integer literal, which prefers the signed widening.** Where the
candidates differ only at positions holding an integer literal with no suffix, negated or not, and
none takes it at `int`, a candidate whose parameter there is a signed integer at least as wide as `int` beats an
unsigned, a narrower signed or a float one, and among several the narrowest wins. Two unsigned
candidates offer nothing signed and stay ambiguous. The rule is the same for a member several
implementations of one trait answer, so `BigInt.from(3)` is its `From[long]`:

```sysl
pick(x: long) -> string = "long"
pick(x: u64) -> string = "u64"

print(pick(3))
print(pick(3u64))
```

```output
long
u64
```

**Candidates tied only by bare literals take each literal at its default type.** Where every
position the candidates differ at holds a numeric literal with no suffix, and no rule above has
chosen, the candidate whose parameter at each of those positions is the literal's default wins. A
float literal's default is `real`. An integer literal's default is `int` at an integer parameter and
`real` at a float one, since an integer literal is read as the float its position expects. So
`nextafter(1.0, 2)`, which both the `real` and the `f32` declarations fit, means the `real` one:

```sysl
import sysl.math.nextafter

scale(x: f32, k: f32) -> string = "f32"
scale(x: real, k: real) -> string = "real"

print(scale(1.5, 2), scale(1, 2), scale(1.5f32, 2f32))
print(nextafter(1.0, 2) > 1)
```

```output
real real f32
true
```

The call stays ambiguous where an argument with a type of its own is part of the tie, where no
candidate takes the literals at their defaults (`f32` beside `f128` at `2`), and where two do
(`(int, real)` beside `(real, int)` at `(2, 2)`):

```sysl
scale(x: f32, k: f32) -> string = "f32"
scale(x: real, k: real) -> string = "real"

val s: i16 = 3
print(scale(s, 2))
```

```error
'scale' is ambiguous here — 2 of its declarations take these arguments:
```

An operator choosing between implementations of its trait settles a literal operand the same way,
so `x * 2` means what `x.mul(2)` means.

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

### Overloading a member

**A type's own members overload by exactly the rules above.** A method or an associated function
may be declared more than once in a type's body, and a call — `s.f(…)` or `S.make(…)` — means the
one its arguments fit, with the same tie-breaks. The receiver is not part of what tells them apart:
it is the same type in every one of them.

```sysl
struct S
    n: int

    f(self, x: int) -> int = x + self.n
    f(self, x: string) -> int = int(x.len)
    f(self, x: int, y: int) -> int = x * y
    make(n: int) -> S = S(n)
    make(s: string) -> S = S(int(s.len))

val s = S.make("abcdefghij")
print(s.f(1), s.f("abc"), s.f(3, 4), S.make(2).n)
```

```output
11 3 12 2
```

**On a generic type the choice is made at the receiver's instantiation**, so a member taking `T`
beside one taking `string` is two members on a `Box[int]` and the call picks by its arguments, in a
generic body as well as outside one:

```sysl
struct Box[T]
    v: T

    put(self, x: T) -> string = "same"
    put(self, x: string, y: int) -> string = "pair"

g[T](b: Box[T], x: T) -> string = b.put(x)

val b = Box(1)
print(b.put(2), b.put("x", 3), g(b, 7))
```

```output
same pair same
```

**A pair a call could not tell apart is refused at the second**, in the words a function pair gets,
and its body is not read against the first's parameters:

```sysl
struct D
    n: int

    h(self, x: int) -> string = "s"
    h(self, x: int, y: int = 0) -> int = 1
```

```error
type 'D' already has a member named 'h' with parameters this one could not be told from — a call passing 1 argument would fit both
```

**A call that several fit equally, or that none fits, is refused at the call with the roster:**

```sysl
struct W
    n: int

    width(self, x: u8) -> string = "u8"
    width(self, x: u16) -> string = "u16"

print(W(0).width(1))
```

```error
'W.width' is ambiguous here — 2 of its declarations take these arguments:
    W.width(x: u8)
    W.width(x: u16)
```

Three things do not overload, each because it is one name with one meaning rather than a set of
calls:

- **A [property](#a-property)** is read without parentheses, so there is no argument list to choose
  by. A property and a method of one name are refused as before.
- **A trait's member.** An implementation supplies each member of its trait by name, and a bound
  reaches each by name, so a trait declares a name once:

  ```sysl
  trait Tr
      f(self, x: int) -> string
      f(self, x: string) -> string
  ```

  ```error
  trait 'Tr' already declares a member named 'f' — an implementation supplies a trait's members by name, so a trait declares each name once
  ```

- **A type's own member and a trait's member of one name** still collide, whatever their
  parameters: the trait's member is reachable only where the trait is in scope, and a name meaning
  a different set of declarations in different files is exactly what that rule keeps out.

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

### A function declared inside another takes most attributes

A function written in a body carries the attributes written above it, as a top-level function does.
`@pure`, `@ghost`, `@tailrec`, `@reads` and `@writes`, `@needs` and `@crossing` all take effect on it,
and so do the marks (`@noinline`, `@cold`, `@blocks` and the rest). A diagnostic names it by where it
was written, `'outer.inner'`, and a closure written in one as `the closure in 'outer.inner'`.

**What `@export`, `@section`, `@test`, a test hook and `interrupt` say is where the code lives or who
calls it**, and a nested function has neither: it is called with the frame of the function around it,
under a name no file wrote. Each is refused, each is told, and a function carrying two hears about both.
`@no_alloc`, `@no_block` and the other `@no_<capability>` promises are refused too, for the reason
[`@no_alloc`](/reference/attributes/#no-alloc-on-a-function-one-entry-point-that-makes-no-heap-storage)
gives.

```sysl
outer(n: int) -> int
    @export("hook")
    inner(k: int) -> int = k + n

    inner(1)

print(outer(3))
```

```error
'@export' holds a top-level function, and 'inner' is declared inside another function
```

**A captured local is storage the nested function did not create.** Under `@pure` it may be read, as a
top-level function reads through a pointer it was handed, and it may not be written:

```sysl
scaled(base: int) -> int
    var factor = 2
    factor += 1

    @pure
    apply(x: int) -> int = x * factor + base

    apply(10)

print(scaled(4))
```

```output
34
```

```sysl
counted() -> int
    var n = 1

    @pure
    bump(x: int) -> int
        n = x
        x

    bump(2)

print(counted())
```

```error
a '@pure' function writes through a reference to storage it did not create
```

**A `@reads` or `@writes` frame covers module storage only**, so it cannot name a captured local:

```sysl
peeked() -> int
    var mine = 5

    @reads(mine)
    peek() -> int = mine

    peek()

print(peeked())
```

```error
'@reads' names 'mine', which is not module-level storage
```

### Type parameters

A bracketed list directly after the name declares type parameters, with optional bounds. See
[generics and traits](/reference/generics/).

### A `const` function runs while compiling

`const` in front of a function's name **promises** that the compiler can run it while it compiles,
so that a call to it can stand where a [constant](/reference/modules/) is required. The promise is
checked at the declaration, against the body and every function it calls. Called anywhere else it is
an ordinary function, and an ordinary call:

```sysl
const sq(x: int) -> int = x * x

var n = 6
n += 1
print(sq(n))
```

```output
49
```

**The mark is a promise, not a permission.** Any function whose body can run while compiling is run
where a constant wants it, marked or not — [modules § const](/reference/modules/) shows one. What
the mark adds is that the promise is held at the declaration: a later change to the body, or to
something it calls, that would stop it running is refused there, rather than at a constant in some
other package that relied on it.

**The standard library marks what it promises.** That covers:
- `sysl.math`'s integer helpers and comparisons: `min`, `max`, `clamp`, `pow`, `gcd`, `lcm`, the
  flooring division, the checked and overflowing arithmetic, the powers of two and `magnitude`;
- `Buf`'s constructors and members, `Frozen`'s readers, and `Map` and `Set` with their operations;
- `sysl.slices`' searches, comparisons and sorts;
- `StrBuilder`.

What it leaves unmarked still runs where its body can. Two things are left out on purpose: the C
library's floating-point mathematics, and `slices.copy`, which compares addresses to handle
overlapping slices.

```sysl
import sysl.buf.{Buf, buf}
import sysl.math.gcd
import sysl.slices.sort

const sorted_gcds(xs: []const int) -> Buf[int]
    var out = buf[int]()

    for i in 1..<xs.len
        out.push(gcd(xs[i - 1], xs[i]))

    sort(out.view())
    out

const LEAST: int = sorted_gcds([12, 18, 27, 36][..]).at(0)

print(LEAST)
```

```output
6
```

The word is the one sysl already uses for "settled while compiling" — a `const` declaration,
`for const`, a `[const N: usize]` parameter — and a `const` function is a constant with parameters.
`const`, then a name, then `(` or `[` is a function; `const`, a name and `:` is a constant. It sits
after a visibility and after the annotations, and it marks a member of a type, of an enum and of an
`impl` block the same way, an associated function included:

```sysl
struct Money
    cents: int

    const zero() -> Money = Money(0)
    const doubled(self) -> int = self.cents * 2

trait Weigh
    weight(self) -> int

impl Weigh for Money
    const weight(self) -> int = self.cents

private const heavy(m: Money) -> int = m.weight() + m.doubled()

print(Money.zero().cents, heavy(Money(5)))
```

```output
0 15
```

**A trait's member is not marked.** Whether a call can run while compiling is a fact about the body
that answers it, and a trait's member is a signature every implementing type answers with a body of
its own:

```sysl
trait Shape
    const area(self) -> int
```

```error
a trait's member is not marked 'const' — whether a call can run while compiling is a fact about the body that answers it, so each 'impl' marks the member it supplies
```

**What its body may hold.** Every integer width, `f32` and `real`, `bool`, `char` and `unit`, and
structs, tuples, fixed arrays and enums made of them — payload-carrying ones included — all by value;
strings, built by `+`, interpolation, `str` and an `f"…"` specifier, indexed, sliced, compared and
matched; slices of arrays and of strings; constrained and `new` types; locals, assignment into a
local's fields and elements and through a slice; `if`, `match`, `loop`, `while`, `do … while`, the C
`for`, `for i in a..<b` and `for x in` a fixed array or a slice; `break`,
`continue`, an early `return`, recursion; `?`; `require`, `ensure`, a struct's `invariant` and a loop's
`invariant` and `variant`. It may call another `const` function, read a `const` and a `val` laid into
the object file, and reach the LLVM operations whose result is exactly specified (`llvm.sqrt`,
`floor`, `ceil`, `trunc`, `round`, `roundeven`, `fabs`, `copysign`, `fma`, `ctpop`, `ctlz`, `cttz`,
`bswap`, `bitreverse`, `fshl`, `fshr`, and the `.sat` integer operations):

```sysl
extern "llvm.sqrt" root(x: f64) -> f64

static val base: int = 3

enum Shape
    Dot
    Square(side: int)

const area(s: Shape) -> int = s match
    Dot -> 0
    Square(n) -> n * n

const fact(n: int) -> int
    require n >= 0
    if n <= 1 then return 1
    n * fact(n - 1)

const plus(n: int) -> int = fact(n) + base

const hyp(a: f64, b: f64) -> f64 = root(a * a + b * b)

print(area(Square(3)), plus(3), int(hyp(3.0, 4.0)))
```

```output
9 9 5
```

What the compiler *evaluates* while compiling — scalars, fixed-point values, strings, and fixed
arrays, tuples, structs, enums and slices of them — is set out in [modules §
const](/reference/modules/), with how a table a `const` function fills in a loop becomes constant
data.

**A string is a value the compiler holds**, so a `const` function may build one, take one apart and
answer one, and a `val` over the call is laid down as the literal it came to:

```sysl
const label(i: int) -> string = s"ch$i:" + (if i % 2 == 0 then "even" else "odd")

const vowels(s: string) -> int
    var n = 0

    for b in s.bytes
        if b == u8('a') || b == u8('e') || b == u8('i') || b == u8('o') || b == u8('u') then n += 1

    n

const hex(n: int) -> string = f"0x${n}%04x"

static val NAMES: [3](string, int) = [(label(0), vowels("table")), (label(1), 0), (label(2), 0)]
static val H: string = hex(255)

print(NAMES[0].0, NAMES[0].1, NAMES[1].0, H, label(5)[0..<3])
```

```output
ch0:even 2 ch1:odd 0x00ff ch5
```

**A slice is a view** of an array or a string the evaluation holds, so a write through a `[]T` lands
in the array its caller passed. A slice may be answered only where it views what the caller handed
in, or module storage — a slice of the call's own local would outlive it:

```sysl
const bump(xs: []int)
    for i in 0..<xs.len
        xs[i] += 1

const local() -> []const int
    var a: [3]int = [1, 2, 3]

    bump(a[1..<3])
    a[..]

const head() -> int = local()[0]

const K: int = head()
```

```error
'local' answers a slice of, or a pointer to, storage local to its call, which ends when the call returns
```

**A `*self` method, and a `*T` parameter, are admitted** — sysl mutates through `*self` everywhere:

```sysl
struct Counter
    n: int

    const bump(*self)
        self.n += 1

const counted(k: int) -> int
    var c = Counter(0)

    while c.n < k
        c.bump()

    c.n

print(counted(4))
```

```output
4
```

**An address may come in, be stored, and go out, where the linker fills it in**: the address of
module storage, of a field or an element inside it, and of a function — and `null`, which needs no
linker at all. While compiling, such an
address is held as the relocation it is, so a `const` constructor that stores one makes constant
data, laid into the object file as C's `static Display oled = { &bus, setup };` is
([ffi § What constant data is](/reference/ffi/#what-constant-data-is)):

```sysl
const f(p: *int) -> *int = p

var x = 1
val q = f(&x)
print(q == &x)
```

```output
true
```

What such an address points at exists only when the program runs, so reading through one, or
converting one to a number, is not evaluated — and neither is the address of thread-local or
per-core storage. A function pointer may be stored and answered, and calling through
it runs the function the address was taken of. On a bare machine, where storage must be constant
data, the refusal says which:

```sysl build=c target=thumbv7em-freestanding
module mylib

var x: i32 = 1

const peek(p: *i32) -> i32 = *p

val seen: i32 = peek(&x)

@export
begin() -> i32 = seen
```

```error
reading through an address is not evaluated while compiling — the linker fills an address in, and what it points at exists only when the program runs
```

**A reference, and a pointer to storage the run owns, are values while compiling.** A box made with
`&T`, a local, an element or a field reached through `&` or `*` is held by the run itself, and a
write through one is seen wherever else the storage is held, as it is when the program runs. A
pointer may not outlive its call, nor be converted to a number or read as another type:

```sysl
struct Node
    v: int
    next: Option[&Node]

bump(p: *int)
    *p += 1

const total(k: int) -> int
    var head: Option[&Node] = None

    for i in 0..<k
        head = Some(Node(i, head))

    var sum = 0
    var cur = head

    while cur is Some(n)
        sum += n.v
        cur = n.next

    bump(&sum)
    sum

const T: int = total(5)

print(T)
```

```output
11
```

**The heap is the run's own**, so a `Buf`, a `Map`, a `Set` and a frozen buffer run while
compiling by their own library bodies, as they do when the program runs. A library's panic — an
index past the end — is a trap there, as it is here:

```sysl
import sysl.buf.{Buf, buf}
import sysl.container.{Map, map}

const counts() -> int
    var m: Map[string, int] = map()
    var seen = buf[string]()

    for w in ["to", "be", "or", "not", "to", "be"]
        if !m.has(w) then seen.push(w)
        m.put(w, m.get(w).unwrap_or(0) + 1)

    int(seen.len()) * 10 + m.get("be").unwrap_or(0)

const C: int = counts()

print(C)
```

```output
42
```

**A closure and a trait object are called while compiling** as the program calls them — a closure
where it is written, handed on as a type argument or a `&Fn`, kept in a buffer, returned with state of
its own; a call through a trait object goes to the function its table names. **An atomic operation** on
storage the run owns is the plain one, since a run while compiling is one thread:

```sysl
import sysl.sync.{Atomic, SeqCst}

counter() -> &Fn() -> int
    var n = 0

    () ->
        n += 1
        n

const ticks() -> int
    val c = counter()
    var a = Atomic[int](0)

    for _ in 0..<3
        a.add(c(), SeqCst)

    a.load(SeqCst)

const T: int = ticks()

print(T)
```

```output
6
```

A box of a type with a `Drop` is refused, since its `drop` runs when the box's last share goes, and
when that is, is decided by counts kept only when the program runs:

```sysl
struct Lamp
    on: bool

impl Drop for Lamp
    drop(self)
        print("off")

lit() -> bool
    val l: &Lamp = Lamp(true)
    l.on

const ON: bool = lit()

print(ON)
```

```error
a box of a 'Lamp', which has a 'Drop', is not made while compiling — its 'drop' runs when the last share of the box goes, and when that is, is decided by counts kept only when the program runs
```

**What it may not hold** is everything that exists only once the program runs, and the few kinds of
value the compiler does not evaluate yet. Each is refused where it was written:

| construct | refusal |
|---|---|
| a call to a function whose body cannot run | `'f' is 'const', so it runs while compiling, and it calls 'noisy', which prints, which is outside the program — nothing outside the program exists while compiling` — the calls are followed down to what stops them |
| `print` | `… it prints, which is outside the program — nothing outside the program exists while compiling` |
| the C library's mathematics (`sin`, `exp`, `pow`, `cbrt`…) | `… it calls 'c_sin', which is the C library's 'sin' — its last bit differs between C libraries, and the board's would not agree with this compiler's` |
| any other `extern` | `… it calls 'abs', which is outside the program — nothing outside the program exists while compiling` |
| an `asm` block | `… it holds an 'asm' block, which is outside the program — nothing outside the program exists while compiling` |
| a weak reference | `… it uses a 'weak P' — a weak reference answers whether its object is still alive, which is decided by counts kept only when the program runs` |
| a value rendered through a `display` whose body cannot run | `… it calls 'R.display', which prints, which is outside the program — nothing outside the program exists while compiling` |
| a pointer converted, a C string | `… it converts a pointer — an address exists only when the program runs` (and `takes the address of a C string`) |
| module storage filled at run time — a `var`, a `val` computed at startup | `… it reaches 'counter' — 'counter' is filled when the program runs` |
| C variadic arguments | `… it walks C variadic arguments — C variadic arguments are not evaluated while compiling yet` |
| a float vector's `sum` | `… it sums a float vector, whose lanes are added in the order the target's instructions take — so the value is known only where the program runs` |

One worked refusal for each of the commonest. A function that is not marked is no reason to refuse;
what its body does is:

```sysl
plain(x: int) -> int = x + 1

noisy(x: int) -> int
    print(x)
    plain(x)

const f(x: int) -> int = plain(x)

const g(x: int) -> int = noisy(x)

print(f(1), g(1))
```

```error
'g' is 'const', so it runs while compiling, and it calls 'noisy', which prints, which is outside the program — nothing outside the program exists while compiling
```

```sysl
const f(x: int) -> int
    print(x)
    x

print(f(1))
```

```error
'f' is 'const', so it runs while compiling, and it prints, which is outside the program — nothing outside the program exists while compiling
```

```sysl
extern "sin" c_sin(x: f64) -> f64

const f(x: f64) -> f64 = c_sin(x)

print(f(1.0))
```

```error
'f' is 'const', so it runs while compiling, and it calls 'c_sin', which is the C library's 'sin' — its last bit differs between C libraries, and the board's would not agree with this compiler's
```

```sysl
static var counter = 0

const f() -> int = counter

print(f())
```

```error
'f' is 'const', so it runs while compiling, and it reaches 'counter' — 'counter' is filled when the program runs
```

```sysl
struct R
    n: int

impl Display for R
    display(self, out: *Writer, fmt: FormatSpec)
        print("rendering")
        display_int(long(self.n), out, fmt)

const f(r: R) -> string = r.to_string()

print(f(R(1)))
```

```error
'f' is 'const', so it runs while compiling, and it calls 'R.display', which prints, which is outside the program — nothing outside the program exists while compiling
```

**Text is rendered while compiling as the program renders it.** A float goes through the library's
own renderers — the shortest round trip for `to_string`, the exact digits under a specifier — so `-0`, a
subnormal, `inf` and `NaN` come out spelled as at run time. A value whose type's `display` can run
while compiling runs it with a writer the evaluation keeps: `write` appends to it, the library's `display_*` routines
and the `display` of every type the library renders write into it, and a nested `display` is handed
the same writer:

```sysl
struct Pt
    x: int
    y: real

impl Display for Pt
    const display(self, out: *Writer, fmt: FormatSpec)
        out.write("(".bytes)
        self.x.display(out, fmt)
        out.write(", ".bytes)
        self.y.display(out, fmt)
        out.write(")".bytes)

const label(p: Pt) -> string = f"$p ${p.y}%.3f ${p.y}%e ${p}%4s"

static val L: string = label(Pt(-2, 0.1))

print(L)
```

```output
(-2, 0.1) 0.100 1.000000e-01 (  -2,  0.1)
```

**A generic `const` function is checked at its definition** for everything that does not go through a
type parameter; a call through a bound is answered by whichever type fills the parameter:

```sysl
const twice[T: Add](x: T) -> T = x + x

print(twice(21), twice(u8(2)))
```

```output
42 4
```

```sysl
noisy() -> int
    print("asked")
    1

const g[T](x: T) -> int = noisy()

print(g(5))
```

```error
'g' is 'const', so it runs while compiling, and it calls 'noisy', which prints, which is outside the program — nothing outside the program exists while compiling
```

### Calls where a type is read

**A call may stand wherever a type needs a value**: an array length in a field, a parameter or an
alias, a value argument, and an `@assert` beside the declarations. It is run while the declarations
are read. A body of arithmetic and plain calls runs where it stands. One that calls a type's members
— `push` on a `Buf`, a method of the program's own — waits until every declaration has been read,
and the declarations are then read again with its value in hand:

```sysl
import sysl.buf.{Buf, buf}

sq(n: int) -> int = n * n

primes_below(n: int) -> int
    var out = buf[int]()

    for i in 2..<n
        var p = true
        for d in 2..<i
            if i % d == 0 then p = false
        if p then out.push(i)

    int(out.len())

struct Table
    primes: [primes_below(30)]int
    cells: [sq(2)]u8

type Quad = [sq(2)]u8

@assert(primes_below(10) == 4, "four primes below ten")

val t = Table([0; 10], [0; 4])
val q: Quad = [1, 2, 3, 4]

print(t.primes.len, t.cells.len, q.len)
```

```output
10 4 4
```

**A value argument is an expression too**, in a call or in a type — a call, arithmetic over a
`const`, a comparison for a `bool` parameter:

```sysl
cap(n: int) -> int = n * 2

const L: int = 3

struct Ring[T, const N: int]
    items: [N]T

f[const N: int]() -> int = N + 1

val a: Ring[int, cap(4)] = Ring([0; 8])
val b = Ring[int, L + 1]([0; 4])

print(f[cap(3)](), a.items.len, b.items.len)
```

```output
7 8 4
```

**A call may name its type arguments outright**, and stands in all of these positions just as the
same call with them inferred does. A function with no value parameter to infer from is called this
way — here a fingerprint of a struct's layout, settled while compiling and checked in an `@assert`:

```sysl
struct Header
    tag: u8
    length: u32
    flags: u16

const fingerprint[T]() -> u32
    var h: u32 = 2166136261

    for const f in T::Fields
        h = (h ^ u32(f.offset)) * 16777619

    (h ^ u32(sizeof(T))) * 16777619

const width[T]() -> int = int(sizeof(T))

const Layout = fingerprint[Header]()

struct Frame
    header: Header
    pad: [width[u64]()]u8

@assert(fingerprint[Header]() == 2081354981, "the header's layout moved")

print(Layout, sizeof(Frame))
```

```output
2081354981 20
```

**A declaration whose value needs that declaration itself is refused, naming both** — reading it
waits for the value, and the value is worked out by reading it:

```sysl
struct S
    items: [f()]int

f() -> int = int(sizeof(S))

print(f())
```

```error
'S' cannot be read while compiling: it needs the value of a call to 'f', and running 'f' reads 'S' itself — a declaration cannot wait on a value worked out from it
```

Two declarations that each wait on the other are refused the same way, each naming the other. A
value argument that is not a constant is refused where it is written, and the generic is not read
with it:

```sysl
f[const N: int]() -> int = N + 1

var n = 3

print(f[n]())
```

```error
a value argument must be a constant — a literal, or a 'const' naming one
```

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

**A property written `= value` with no `->` takes the value's type**, exactly as a
[function's result](#the-result-type) does: `count = self.n` is `count -> int`. It is read the same
way wherever the property is used — above its declaration, through a generic type's parameters, in an
`impl` block — and a body whose value is `unit` makes a unit property. A setter beside it takes that
type for its value. `sysl doc` and an editor's hover show the type, never the body:

```sysl
struct Counter
    n: int

    count = self.n
    doubled = self.count * 2
    label = s"#${self.n}"

    set count(v)
        self.n = v

struct Box[T]
    v: T

    get = self.v

var c = Counter(4)

c.count = 20
print(c.count + 1, c.doubled, c.label, Box("lid").get, Box(2.5).get * 2)
```

```output
21 40 #20 lid 5
```

The function's rules come with it. A body that reaches the property again before its type is known
has no type to take, and is told to write one:

```sysl
struct P
    x: int

    a = self.b + 1
    b = self.a * 2

print(P(1).a)
```

```error
'P.a' takes its result type from its body, and the body reaches 'P.a' again through 'P.b' — write its result type, 'P.a -> T'
```

A public property may not hand back a type its readers cannot name, as a written `->` may not. A
trait's property is a signature every implementation is held to, so it writes its type; an `impl`
member supplying one may leave it off and takes the trait's. In an enum's body `name = value` is a
variant and its discriminant, so an enum's inferred property is written in an `impl` block.

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
storage amounts to. Two things follow, and they are the same two that follow for an element
reached through [`Index`](/reference/expressions/): the write cannot be one place of a multiple
assignment, and `&t.f` is refused because there is no address. Written
[`:=`](/reference/expressions/#assignment), the write yields what the getter reads back once the
setter has run.

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

**The same holds for every by-value parameter, and "large" starts past two machine words** — sixteen
bytes on a 64-bit target, eight on a 32-bit one, the line past which C's own conventions stop passing
a struct in registers. A struct, an array or an enum with a payload wider than that, which the body
only reads, is lent by address: the caller hands over its own storage, or a snapshot where the
original could change, instead of loading the whole value into registers. A body that writes its
parameter, takes its address, or keeps it takes it by value as before, and so does everything
narrower, which costs nothing to read where it is. The choice is the function's, made from its body
alone, so a call through a trait object, a function value or a closure reaches it all the same:

```sysl
struct P
    a: i64
    b: i64
    c: i64

var g = P(1, 2, 3)

bump()
    g.a = 10

look(p: P) -> i64
    val before = p.a
    bump()
    before * 100 + p.a

total(p: P) -> i64 = p.a + p.b + p.c

apply(f: &Fn(P) -> i64, p: P) -> i64 = f(p)

print(look(g), g.a)
print(apply(total, g))
```

```output
101 10
15
```

**What "could change" means is decided by type, and conservatively.** A receiver of type `T` is
handed over in place, with no copy and no count taken on what it holds, when nothing the method
does before it returns — itself or anything it calls — writes storage that could be part of a `T`:

- a write to the method's own locals never counts, nor one through a pointer, a reference or a
  view to a type that neither holds a `T` nor is a part of one — an output buffer of `[4]u32`
  beside a receiver that holds no `[4]u32`, a lock word of another struct;
- a write through a pointer to a part of `T` counts (a `*u32` may point at a `u32` field), as does
  one through a pointer type the program anywhere makes with `ptr_cast`, or reads at another type
  with a reinterpreting view or `bytes`, since such a pointer may point anywhere;
- so does a call into C, through a closure or a trait object, a write that replaces a counted
  value, and making a value whose type has a destructor, since any of those can run code the
  compiler cannot see;
- and so does any argument worked out after the receiver that calls or writes something, since
  the receiver is lent from the moment its address is taken.

Everything else gets the snapshot, so the program sees exactly what a copy would. An argument
after the receiver that writes it is the case a reader may not expect:

```sysl
struct Big
    n: int
    table: [1024]u32

    look(self, k: int) -> int = self.n * 10 + k

    later(*self) -> int = self.look(poke(self))

poke(b: *Big) -> int
    b.n = 5
    0

var b = Big(1, [0; 1024])
print(b.later(), b.n)
```

```output
10 5
```

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

### A method may answer a place: `-> ref T`

A member whose result is written `-> ref T` answers **a place** rather than a value: its call can be
assigned to, compound-assigned, called a `*self` method on, or named with `ref`, and each reaches the
storage the body came to.

```sysl
struct Grid
    cells: [6]int
    w: usize

    at(*self, x: usize, y: usize) -> ref int = self.cells[y * self.w + x]

var g = Grid([0; 6], 3)

g.at(2, 1) = 7
g.at(2, 1) += 1

ref corner = g.at(0, 0)

corner = 4

print(g.cells[5], g.cells[0], g.at(2, 1))
```

```output
8 4 8
```

The body is one place reached through a parameter — a `*self` receiver, a reference, a container's
element, a `*T`, or module storage — and the compiler keeps it, so a `ref` bound to the call is held
to every rule a `ref` bound to the place itself is. `-> ref` is written only on a type's own member,
never on a free function or in a trait; [memory](/reference/memory/#a-member-that-answers-a-place)
has the whole of it.

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
module rather than to that file's body ([bindings](#a-module-member-takes-its-type-as-a-local-does)). The two are
told apart by what follows it.

### `invariant`

A condition every value of the struct must satisfy, re-checked whenever the struct is built or one of
its fields is written. Bare field names are in scope inside it. A multiple assignment re-checks it
**once**, after every write has landed, which is what lets two fields that relate to each other be
changed together.

`invariant` is a contextual word — an ordinary identifier everywhere else. See
[errors and contracts](/reference/errors/) for what happens when one is broken.

### A struct with a destructor is built as a reference

**A struct (or an enum) with an `impl Drop` is a reference type**: its constructor makes a `&T` with
nothing written, and a declaration that would hold one by value — a parameter, a result, a field, a
payload, a binding — is refused with the `&T` to write
([memory](/reference/memory/#a-type-with-a-destructor-is-a-reference-type)):

```sysl
struct Conn
    fd: int

impl Drop for Conn
    drop(self) = print("closed", self.fd)

struct Pool
    first: &Conn

connect(fd: int) -> &Conn = Conn(fd)

use_pool()
    val p = Pool(connect(3))
    val c = Conn(4)

    print("using", p.first.fd, c.fd)

use_pool()
```

```output
using 3 4
closed 4
closed 3
```

```sysl
struct Conn
    fd: int

impl Drop for Conn
    drop(self) = print("closed", self.fd)

connect(fd: int) -> Conn = Conn(fd)

print("ready")
```

```error
'connect' hands back 'Conn' by value and 'Conn' has a 'Drop' — a type with a destructor is a reference type, and building one makes a '&Conn'. Write '&Conn' in its place
```

A method still takes `self` — that is the copy a call through the reference hands it, and the
reference keeps owning the resource.

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

### Members in an `impl` block of their own

**An `impl` naming no trait adds members to a struct or an enum**, written in any file of the module
that declares the type. A type whose methods outgrow its declaration — a `Quaternion` with fifty
operations, an enum with a parser and a printer — keeps its fields together and its members where
they read best:

```sysl
// geom/quat.sysl
module geom

struct Quat[F]
    w: F
    x: F
```

```sysl
// geom/ops.sysl
module geom

import sysl.math.Float

impl[F: Float] Quat[F]
    norm2(self) -> F = self.w * self.w + self.x * self.x

    scaled(self, k: F) -> Quat[F] = Quat(self.w * k, self.x * k)
```

The block's members are the type's own exactly as the body's are: no trait to import, reached
wherever the type is, and one namespace with the body — so a name the body already uses is a
duplicate whichever was written first:

```sysl
struct P
    n: int

    show(self) -> int = self.n

impl P
    show(self) -> int = 2

print(P(1).show())
```

```error
type 'P' already has a member named 'show'
```

**A member of the block may be `private` or `private[m]`**, with the same reach as one written in the
body — the file that wrote it, or the module it names. A trait's `impl` has no such modifier, since
what it supplies is as visible as the trait:

```sysl
struct P
    n: int

impl P
    private twice(self) -> int = self.n * 2

    shown(self) -> int = self.twice()

print(P(4).shown())
```

```output
8
```

**The block's header binds the type's parameters**, under names of its own, as a trait's `impl`
does. **A bound it adds is a condition on the members it brings**: they exist only where the
receiver's arguments meet it, while the type and its body's members stay as they were. It is the
inherent counterpart of [a conditional conformance](/reference/traits/#conditional-conformance):

```sysl
struct Pair[T]
    lo: T
    hi: T

    swapped(self) -> Pair[T] = Pair(self.hi, self.lo)

impl[T: Ord] Pair[T]
    larger(self) -> T = if self.lo < self.hi then self.hi else self.lo

struct Tag
    n: int

print(Pair(3, 7).larger())
print(Pair(Tag(1), Tag(2)).swapped().lo.n)
```

```output
7
2
```

A `Pair[Tag]` is built and swapped; asking it for `larger` is refused at the call, naming the bound
the block wrote:

```sysl
struct Pair[T]
    lo: T
    hi: T

impl[T: Ord] Pair[T]
    larger(self) -> T = if self.lo < self.hi then self.hi else self.lo

struct Tag
    n: int

print(Pair(Tag(1), Tag(2)).larger().n)
```

```error
'Pair.larger' requires its type parameter 'T' to implement 'Ord', but Tag does not
```

**Another module may not add members**: a type's own members are part of what its declaration
promises, and what another module wants to add is a trait, which it may implement for the type:

```sysl
impl[T] Option[T]
    or_zero(self) -> int = 0

print(1)
```

```error
'Option' is the library's, and an 'impl' naming no trait adds members to a type only in a file of the module that declares it
```

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

**A function in an enum body is named in lowercase.** `Circle(r: real) = 0` reads as a variant given
a value, and it is a function: it would leave the enum without the variant it was written to declare.
A capitalised name with a body after its brackets is refused rather than read that way:

```sysl
enum Shape
    Circle(r: int) = 0
    Square(side: int)

print(1)
```

```error
'Circle' is read as a function, but a function in an enum body is named in lowercase — only a payload-free variant may be given a value
```

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

**A variant written `= 0` is the enum's zero value.** A `var` with no initializer, a struct field and
an array element all start at the type's zero, and an enum has one exactly when the program wrote a
variant's value as 0. A first variant numbered 0 by position does not count
([module storage](/reference/modules/) has the rule in full):

```sysl
enum Phase: u8
    Running = 1
    Stopped = 0

struct Motor
    phase: Phase
    rpm: int

var m: Motor

print(m.phase == Stopped, m.rpm)
```

```output
true 0
```

A data enum may write `= 0` on ONE variant that carries nothing. That variant takes tag 0 and is the
enum's zero value; the others are numbered in the order they are declared. Its zero is that tag
beside a payload area nobody reads, so the payload's own types need no zero — `Option` declares
`None = 0`, which makes `Option[&T]` zeroable although `&T` is not:

```sysl
enum Link
    Down(reason: string)
    Idle = 0
    Up(peer: int)

struct Port
    link: Link
    last: Option[int]

var x: Option[int]
var p: Port

print(x, x.is_none())
print(p.last.is_none(), p.link match
    Idle -> "idle"
    _ -> "busy")
```

```output
None true
true idle
```

No other variant of a data enum may have a value. A variant that carries data cannot even be
written with one: `Up(peer: int) = 0` is a capitalised header with a body, and it is refused at its
name, as a function in an enum body is above.

```sysl
enum Link
    Idle = 0
    Busy = 1
    Up(peer: int)
```

```error
'Link' carries data, so its variant 'Busy' cannot have an explicit value — a data enum numbers its variants in order, and only a variant that carries nothing may be written '= 0', as its zero value
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

An alias may take type parameters, written in brackets after its name as a struct's are. A generic
alias is just as transparent: `Pair[int, string]` **is** `(int, string)`, the same type with the same
instantiation, so a value of one goes wherever the other is asked for, and a type argument is inferred
through it as through what it names. A bound on a parameter is held at every application, and a
default may be given as on a struct:

```sysl
type Pair[A, B] = (A, B)

swap[A, B](p: Pair[A, B]) -> Pair[B, A] = (p.1, p.0)

val p: Pair[int, string] = (1, "one")
val q: (string, int) = swap(p)

print(q.0, q.1)
```

```output
one 1
```

An application gives exactly as many arguments as the alias takes, less any it has defaults for:

```sysl
type Pair[A, B] = (A, B)

first(p: Pair[int]) -> int = p.0
```

```error
alias 'Pair' takes 2 type arguments, but 1 type argument was given
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

A `new` type's name converts from whatever its base's name converts from: `Phase(3)` over
`type Phase = new real` is `Phase(real(3))`, accepted exactly where `real(3)` is. Only the written
conversion does it — a `3` or an `int` is still not a `Phase` where one is wanted:

```sysl
type Phase = new real

val n = 3

print(f64(Phase(3)) + f64(Phase(n)))
```

```output
6
```

`new`, `within` and `where` are contextual words, so a function or field may still be named `where`.
See [errors and contracts](/reference/errors/) for what a bound costs and when it is checked, and for
[how a `new` type converts](/reference/errors/#a-new-type-s-name-converts-from-whatever-its-base-s-name-converts-from).

### A `new` type may take parameters

A `new` type may take type parameters too, and they are **phantom**: the base is still a scalar, so
`UserPtr[Frame]` and `UserPtr[u8]` are both laid out as a `u64` — and are two types, neither of which
passes for the other or for the `u64`. What the parameter buys is that a function can read it back. A
conversion names the arguments, or takes them from where the value goes, and `u64(p)` reads the base
back as for any `new` type:

```sysl
struct Frame
    sp: u64
    pc: u64

type UserPtr[T] = new u64

size_at[T](p: UserPtr[T]) -> usize = sizeof(T)

next[T](p: UserPtr[T]) -> UserPtr[T] = UserPtr[T](u64(p) + u64(sizeof(T)))

val f = UserPtr[Frame](4096)
val b: UserPtr[u8] = UserPtr(4096)

print(size_at(f), size_at(b), u64(next(f)), u64(next(b)))
```

```output
16 1 4112 4097
```

The argument is part of the type, so one written where another is wanted is refused:

```sysl
struct Frame
    sp: u64

type UserPtr[T] = new u64

take(p: UserPtr[Frame]) -> u64 = u64(p)

print(take(UserPtr[u8](1)))
```

```error
'p' of 'take' is UserPtr[Frame], but UserPtr[byte] was given
```

An argument that is only a second name for a type — a measured `c type`, an exported alias — is that
type here as everywhere, so `UserPtr[Word]` over `@export type Word = u64` is `UserPtr[u64]`.

**Only a plain `new` type takes parameters.** A `within`, `where` or `wrapping` type is one check, and
an `@export` type one name in a header, so there is nothing for an argument to fill:

```sysl
type Slot[T] = new u8 within 0..<8
```

```error
'Slot' takes type parameters, and only a transparent alias or a plain 'new' type may
```

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

A **binding whose value is an indented block** takes one too, naming the binding — `end x` after a
`val x =` or a `var x =` — and so does one whose value ends in a block, such as a `match`:

```sysl
classify(n: int) -> string
    val kind = n match
        0 -> "zero"
        _ -> "some"
    end kind

    kind

print(classify(0))
```

```output
zero
```

**A marker that names something else is refused, naming both** — a declaration's `end Name` under
another declaration, a statement's marker under a declaration, or another name under a binding:

```sysl
total(xs: []int) -> int
    var sum = 0

    for x in xs
        sum += x

    sum
end while

print(total([1, 2]))
```

```error
'end while' does not match 'total'
```

```sysl
var limit =
    val base = 10

    base * 2
end bound

print(limit)
```

```error
'end bound' cannot close the 'var limit' above it — its marker is 'end limit'
```

**A marker closes a block, so one after a value written on one line is refused** — there is nothing
for it to close, and it would only add clutter:

```sysl
val x = 1
end x

print(x)
```

```error
'end x' has no block to close — the 'val x' above it ends on a one-line form, which takes no marker
```

The statements' own markers — `end if`, `end while`, `end for`, `end loop` and `end match` — are on
[statements](/reference/statements/), under the same rules.

`end` is a **soft** word: it is an ordinary identifier everywhere except immediately before a name or
a construct keyword, so `end` stays usable as a variable — `end match` with arms indented under it is a
`match` over a binding called `end`.

---

Next: [patterns and matching](/reference/patterns/).
