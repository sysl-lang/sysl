---
title: Attributes, annotations, and compile time
summary: `::` attributes a type answers, the nineteen annotations a function takes, the three that lay out or place what they mark, the five a file's header takes, `@assert` which stands on its own, and the `#if` directive that gates lines before the lexer sees them.
weight: 130
---

Three kinds of form in sysl reach into the *compilation* rather than into the running program. Each
has a name and a spelling of its own:

| written | is | read by |
|---|---|---|
| `T::Attr` | an **attribute** — a question a type's own name answers | the analyzer, at the use |
| `@test`, `@tailrec`, `@pure`, `@ghost`, `@export`, `@reads`, `@writes`, `@crossing`, `@joined`, `@domain`, `@noinline`, `@inline`, `@cold`, `@disjoint`, `@no_alloc`, `@no_block`, `@blocks`, `@abandons`, `@track_caller` | an **annotation** — a fact about the free function under it | the grammar |
| `@setup`, `@teardown`, `@setup_all`, `@teardown_all` | an **annotation** — a hook `sysl test` runs around a module's tests | the grammar |
| `@keeps(...)` | an **annotation** on an `extern` — the parameters the C function holds on to past the call; see [`@keeps`](/reference/ffi/#keeps-a-c-function-that-holds-on-to-a-pointer) | the grammar |
| `@needs(...)` | an **annotation** — the capabilities reaching the declaration under it requires; with `@abandons` and `@keeps`, one of the three an `extern` takes | the grammar |
| `@packed`, `@align(n)`, `@section("...")` | an **annotation** — where the declaration under it is laid out, or where it lands | the grammar |
| `@export("...", weak)`, `@export(weak)` on a function | an **annotation** — the export is a default a strong definition of the symbol replaces at the link; see [a weak export](/reference/ffi/#a-weak-export) | the grammar |
| `@export("...")` on a `struct` | an **annotation** — the name the type carries in a generated C header | the grammar |
| `@export("...")` on a module `var` or `val` | an **annotation** — the C symbol the storage is published under | the grammar |
| `@no_alloc`, `@requires`, `@link`, `@include`, `@tests` | an **annotation** — a fact about the whole file, in its header | the grammar |
| `@assert` | an **annotation** that describes nothing but itself — a condition settled while compiling | the analyzer, once |
| `#if` | a **directive** — a gate on lines | a pass before the lexer |

**The last two are told apart by the sigil, and that is the whole rule.** An annotation is `@` and
belongs to what it is written above — a declaration, or the file itself; a directive is `#` and
gates lines. Nothing about
indentation is involved, which matters because a declaration at the margin — a `module` header — has
its annotation at the margin too, so a rule about columns would have had the two forms competing for
one position.

A directive is still written at column 1, and for its own reason: it is gone before anything counts a
column, so an indented one would look like it takes part in a block structure it has nothing to do
with. That is a rule about directives, not the thing that distinguishes them.

Annotations come in groups, by what they attach to — and the last of them is the empty one, which is
as much a rule as the others.

**On a function** there are seventeen, each written on its own line above the declaration. More than
one may be stacked, and writing the same one twice is refused. `@test` and `@tailrec` are below, and
so are the four hooks a module's tests may declare — `@setup`, `@teardown`, `@setup_all` and
`@teardown_all` — the three that tell the optimizer what a definition is for, `@noinline`,
`@inline` and `@cold`, and `@disjoint(...)`, which tells it which parameters never overlap; `@pure`,
`@ghost`, `@reads` and `@writes` belong to the specification vocabulary and are on the
[verification](/reference/verification/) page; `@export` makes the definition C-callable and is on
the [FFI](/reference/ffi/) page, beside the `extern` it is the mirror image of; `@crossing(...)` says
a parameter hands a value to another concurrency domain where no body shows it — a by-value one is
handed a copy of every view in it, and one a body shows is inferred — and is on the
[memory](/reference/memory/) page, beside the crossing rule it asks; `@no_alloc`, the one header word a
function may also carry, holds that one definition to making no heap storage and is below; and
`@abandons` says a call to the function never comes back while the program goes on, so the caller's
frame is given back first — it marks an `extern` too, and is on the
[declarations](/reference/declarations/) page beside `become`.

**On a type, or on storage** there are four, and three of them are about *where* rather than about
what the declaration does. `@packed` and `@align(n)` lay out a struct — no interior padding, and the
boundary the aggregate begins on — and `@align(n)` marks one binding's storage as well.
`@section("...")` marks a binding **or** a function, and says which linker section it lands in. All
three are below. The fourth is `@export`, which on a struct names the type in a generated C header
rather than placing it, and on a module `var` or `val` publishes the storage under a C symbol; both
are on the [FFI](/reference/ffi/#exported-storage) page with the other half of themselves.

**On the file** there are five, in its header directly below `module` and before everything else:
`@no_alloc` and its siblings, `@requires(...)`, `@link("...")`, `@include("...")`, and `@tests`. The
first two say what the whole module may do; `@link` says what its `extern`s need at the linker and
`@include` what its `c const` and `c type` blocks need at the C compiler; the last says it is scaffolding
for the module's tests. All five attach to the file rather than to any declaration in it — and
writing one further down is refused with a message saying where it belongs, except `@no_alloc` above
a function or a method, which holds that one definition instead (below). The first four are
covered under [modules](/reference/modules/) and [FFI](/reference/ffi/), where what they *mean* is;
`@tests` is below.

**On nothing at all** there is one: `@assert`, which stands where a declaration stands and describes
only itself. It attaches to nothing, declares no name, and nothing can refer to one — two saying the
same thing are two checks rather than a duplicate. It is below.

**On a member, five: the ones that are about a PARAMETER.** `@crossing`, `@scoped`, `@joined`,
`@reads` and `@writes` each name parameters, and a method, a property or an associated function has parameters
exactly as a free function does — so there was never anything for a blanket refusal to be about in
their case:

```sysl
struct Chan
    open: bool

    @crossing(v)
    send(*self, v: *int) -> int = v[0]

var c = Chan(true)
var n = 7

print(c.send(&n))
```

```output
7
```

Beside them a member takes the six about the function it lowers to — `@noinline`, `@inline`, `@cold`,
`@no_alloc`, `@no_block` and `@blocks` — and `@relocates`, which says a call may move the storage its
receiver's elements live in, so a live `ref` to one of them is refused the call
([memory](/reference/memory/#the-one-rule-what-may-move-underneath-it)), and `@views(self)`, which says
the view a call answers is borrowed from its receiver, so the view may not outlive it
([memory](/reference/memory/#a-view-borrowed-from-its-receiver)), and `@consumes`, which says a call
takes everything its receiver holds, so a local it was called on may not be read again until it is
assigned (below). Everything else is refused, and the line is what the annotation is **about** rather
than where it is written. "A function" in the list above means a *free* function: what `sysl test` calls, what
recurses, what a symbol names, how a type is laid out — none of which a member supplies.

```sysl
struct Counter
    n: int

    @test
    is_zero(self) -> bool = self.n == 0
```

```error
the only annotations a member may carry are the ones about a parameter
```

So what `sysl test` runs is a free function that calls the member. `@packed` and `@align(n)` are not
the exception they look like: they mark the **struct**, written above `struct` and not above a field.
`@assert` inside a type's body is refused too, and gets its own sentence, because it is not a claim
about the member under it — it goes beside the type, where `sizeof` and `offsetof` still name what it
is about.

**They are refused above a field and above a variant**, by a sentence of their own: each names
parameters, and neither a field nor a variant has any.

```sysl
struct Point
    @crossing(x)
    x: int
```

```error
are about a parameter
```

**`#test` above a member is answered by the same sentence**, with the sigil named at the end of it. A
directive is gone before the lexer counts a column, so an *indented* `#` never reaches the directive
pass at all and arrives at the member grammar instead — and being told only that an annotation is
written `@` would send a reader to write `@test`, which a member is refused just the same.

**An annotation's name is an ordinary identifier**, which is the point of writing these as
annotations at all: nothing here is reserved, so a program may still call something `test`, `link`,
`alloc`, `no` or `requires`. The [`slab`](https://github.com/sysl-lang/slab) package's allocator
calls its central function `alloc` and threads its free list through a function called `link`.

The `@` is also read **inside a pattern**, where it binds a name to what a sub-pattern matched
([patterns](/reference/patterns/)). The two never compete: an annotation's `@` is a prefix at the
start of a line above a declaration, and a pattern's is infix between a name and a pattern, in a
position no declaration may stand.

### `@scoped(f)` — a member that finishes what it starts before its scope closes

**`@scoped(f)` above a method promises that `f` runs in another concurrency domain and finishes before
the call that lent the method's `*self` returns.** It is how a scheduler's `spawn` says its children
are joined by the scope that handed out the receiver, and it implies `@crossing(f)`: what is passed at
`f` is held to the [crossing rule](/reference/memory/), exactly as if both were written. The compiler
cannot see the join — that is the library's promise to keep, as `@crossing`'s boundary is — so what it
changes is what a closure that was lent the scope may do with it: a call of the member keeps neither
its receiver nor what it runs, though the job it queues captures the scope
([memory.md § A borrowed pointer cannot escape](/reference/memory/#a-borrowed-pointer-cannot-escape)).

```sysl
import sysl.buf.{Buf, buf}

struct Scope
    jobs: Buf[&sync Fn() -> unit]

    @scoped(f)
    spawn(*self, f: &sync Fn(*Scope) -> unit)
        val s = self

        self.jobs.push(() -> f(s))

struct Runner
    n: int

    scope[R](self, body: *Scope -> R) -> R
        var s = Scope(buf())
        val r = body(&s)

        for j in s.jobs do j()
        r

print(Runner(0).scope((s) ->
    s.spawn((t) -> print(usize(s) == usize(t)))
    17))
```

```output
true
17
```

Without `@scoped` the same closure is refused: `spawn` keeps the receiver in the job it queues, and a
closure borrows the scope it is handed for the call. What `spawn` answers is still followed as made
of the scope, so a handle built around `self` may not be stored outside the body.

**A closure written straight at `f` may capture a view.** Everything a `&sync Fn` may capture it may
capture too — scalars, arrays, `&sync T`, raw pointers, the lent scope — and also a view, `[]T` or
`[]const T`, whose elements may themselves be shared. The view is lent to the child rather than shared
with it: it is stored with a null owner, the form a view of a frame's own array has, so neither
building the child nor letting it go touches the count of the storage it was taken from, and the
caller is held inside the scope call until the child is done.

```sysl
import sysl.buf.{Buf, buf}

struct Scope
    jobs: Buf[&sync Fn() -> unit]

    @scoped(f)
    spawn(*self, f: &sync Fn() -> unit) = self.jobs.push(f)

struct Runner
    n: int

    scope[R](self, body: *Scope -> R) -> R
        var s = Scope(buf())
        val r = body(&s)

        for j in s.jobs do j()
        r

total(v: []int) -> int
    Runner(0).scope((s) ->
        s.spawn(() -> print(v[0] + v[1] + v[2]))
        int(v.len))

var b: Buf[int] = buf()

b.push(3)
b.push(4)
b.push(5)
print(total(b.view()))
```

```output
12
3
```

Only a closure written at the argument borrows; the same capture anywhere else — a `&sync Fn`
parameter, a member that is only `@crossing`, a closure made earlier and handed in — is refused as it
always was. So are a `&T`, a `string`, a `Buf`, and a view whose elements cannot be shared:

```sysl
import sysl.buf.{Buf, buf}

struct Scope
    jobs: Buf[&sync Fn() -> unit]

    @scoped(f)
    spawn(*self, f: &sync Fn() -> unit) = self.jobs.push(f)

struct Runner
    n: int

    scope[R](self, body: *Scope -> R) -> R
        var s = Scope(buf())
        val r = body(&s)

        for j in s.jobs do j()
        r

first(ws: []string) -> int
    Runner(0).scope((s) ->
        s.spawn(() -> print(ws[0]))
        0)
```

```error
but the 'ws' it captures reaches a '[]string', which owns its elements through a count that is not atomic
```

**What it names is a call, once**, and the receiver is the `*self` a call lent:

```sysl
struct Chan
    open: bool

    @scoped(v)
    run(*self, v: int) -> int = v
```

```error
'@scoped' names 'v', which is neither a call nor a pointer
```

```sysl
struct Chan
    open: bool

    @scoped(g)
    run(*self, f: &sync Fn() -> int) -> int = f()
```

```error
'@scoped' names 'g', which is not a parameter of 'Chan.run'
```

```sysl
struct Chan
    open: bool

    @scoped(f, f)
    run(*self, f: &sync Fn() -> int) -> int = f()
```

```error
'@scoped' names 'f' twice
```

```sysl
struct Chan
    open: bool

    @scoped(f)
    run(self, f: &sync Fn() -> int) -> int = f()
```

```error
so the receiver is the pointer that call lent, written '*self' — this one takes 'self' by value
```

```sysl
struct Chan
    open: bool

    @scoped()
    run(*self, f: &sync Fn() -> int) -> int = f()
```

```error
There is no empty form
```

**Naming a call, it stands above a method and nowhere else.** A free function has no receiver for a
call to have lent, and a field is never called:

```sysl
@scoped(f)
run(f: &sync Fn() -> int) -> int = f()
```

```error
a free function has no receiver for a call to have lent
```

```sysl
struct Chan
    @scoped(f)
    f: int
```

```error
a field and a variant are never called, and have no parameter to run
```

### `@scoped(p)` on a pointer — held only while the caller waits

**`@scoped(p)` naming a `*T` parameter promises that the function may hold the pointer, but only
while its caller is suspended or inside the caller's scope, and lets go of it before the caller's
frame ends.** It is how a blocking primitive says so: a kernel's `block` parks the address of the
caller's wait record until `wake`, an `await`able read keeps the caller's buffer until the device
delivers, a scope's `spawn` holds the scope until the join. The release happens in a context the
compiler cannot see, so it trusts the promise, written once on the primitive — the code that calls
it stays annotation-free and may hand it the address of a local. It may stand above any function
or method, generic or not, nested or not, and above an `impl`'s method or a trait's default body.

```sysl
static var idle = 0
static var waiter: *int = &idle

@scoped(slot)
block(slot: *int)
    waiter = slot

wake(v: int)
    *waiter = v
    waiter = &idle

read_sensor() -> int
    var got = 0

    block(&got)
    wake(42)
    got

print(read_sensor())
```

```output
42
```

**The promise is the primitive's own, so it does not spread.** Its body may store the pointer, but
handing it to a callee that keeps it — or capturing it in a closure that is stored — is refused,
because that callee promised nothing. A callee whose parameter says `@scoped` too, or one that only
uses the pointer, may be handed it:

```sysl
static var idle = 0
static var waiter: *int = &idle

park(slot: *int)
    waiter = slot

@scoped(slot)
block(slot: *int)
    park(slot)
```

```error
'block' says '@scoped(slot)', a promise to let go of 'slot' before its caller's scope ends that only its own body keeps, and it is passed to 'park', which keeps it
```

It takes a `*T` or a call, and nothing else; it does not stand above an `extern`, whose C body
holds what `@keeps(...)` says, nor above a trait's requirement, which has no body to keep it — each
`impl` writes it above its own method.

### `@joined(p)` — a pointer held until the handle is joined

**`@joined(p)` naming a `*T` parameter that the function also says is `@crossing` promises that the
thread the call starts holds the pointer until the handle the call answers is joined**, and no
longer. `sysl.threads.spawn` and `spawn_on` say it of `arg`, which is what lets a function hand a
thread the address of its own local where it joins the thread on every way out
([memory § A thread holds what it is handed until it is joined](/reference/memory/#a-thread-holds-what-it-is-handed-until-it-is-joined)).
The join is the call of the handle's member named `join`. Like `@scoped`, it is a library author's
promise, written once on the primitive; nothing that calls it writes anything.

```sysl
struct Handle
    k: int

    join(self) -> bool = true

@crossing(p)
@joined(p)
begin(p: *int) -> Handle = Handle(*p)

run() -> int
    var n = 5
    val h = begin(&n)

    h.join()
    n

print(run())
```

```output
5
```

It names a parameter `@crossing` names too, since a pointer is held until a join only by the thread
it reached:

```sysl
@joined(p)
f(p: *int) = ()
```

```error
'@joined' names 'p', which '@crossing' does not
```

## `::` — what a type's own name answers

A type's name is a type and not a value, so nothing is *read* from it. What it answers are
**attributes**, written with `::` rather than `.` so they stay out of the member namespace:
`Color::First` cannot be confused with a variant, an associated function, or a member an `impl`
added, and no `impl` can shadow one by declaring a member of that name.

Four kinds of type answer them, and each set is **fixed and closed**.

### A simple enum

```sysl
enum Color: u8
    Red = 2
    Green = 5
    Blue = 9

print(Color::Image(Color::First), Color::Image(Color::Last))
print(Color::Pos(Green), Color::Image(Color::Val(2)))
print(Color::Image(Color::Succ(Red)), Color::Image(Color::Pred(Blue)))
print(Color::Image(Color::Value("Blue")), int(Color::Value("Blue")))
```

```output
Red Blue
1 Blue
Green Green
Blue 9
```

| written | is | traps |
|---|---|---|
| `T::First` / `T::Last` | the first and last variant | no |
| `T::Pos(v)` | a value's **0-based position** in the declaration | only on a value no variant has |
| `T::Val(i)` | the value at position `i` | on a position past the last, or below zero |
| `T::Succ(v)` / `T::Pred(v)` | the neighbouring value | at the last / at the first, and on a value no variant has |
| `T::Image(v)` | the variant's **name**, as a `string` | only on a value no variant has |
| `T::Value(s)` | the value a name stands for | on a name no variant has |

A value no variant has cannot be built — `E(n)` checks, and so does reading a
[bitfield's enum field](#bitfields-an-in-field-in-exactly-n-bits) — so it arrives only through storage a
`ptr_cast` or C filled. `Pos`, `Succ`, `Pred` and `Image` stop on one rather than answer for a
variant it is not.

**Position is not the discriminant, and that distinction is why `Pos` and `Val` exist.**
Discriminants may be explicit, non-contiguous and not zero-based — `Green` above is position 1 and
discriminant 5 — so an ordinal has to be looked up rather than computed. `Pos` and `Val` are the two
directions of that lookup, and `Succ`/`Pred` walk the **declaration order** rather than adding one.
Going the other way, to a value's discriminant, is the ordinary conversion `int(c)`.

**`Image` and `Value` are the printed-name pair, and they are what makes a simple enum
observable.** The type carries no `==` and no `Display` of its own, which is why every line above
turns a value into a position or a name to look at it. A comparison is written on the discriminants,
`int(a) == int(b)`.

### A constrained subtype

The other set — `First`, `Last`, `Valid`, `Succ`, `Pred`, `Range` — belongs to a `within`-ranged
integer subtype, and is on the [errors and contracts](/reference/errors/) page beside the checking it
is the question form of.

The two sets are spelled the same way for the same reason and overlap where the questions overlap,
but they are not one set: an enum has `Pos`, `Val`, `Image` and `Value` because its values are named
and unevenly spaced, and a subtype has `Valid` and `Range` because its values are a contiguous run of
integers.

### An integer type

The third set is two attributes wide — `Min` and `Max`, the extremes the type can hold. They belong
to every member of the `iN`/`uN` family, and to the named types that are members of it.

```sysl
print(u8::Min, u8::Max)
print(i8::Min, i8::Max)
print(int::Min, int::Max)
print(byte::Min, byte::Max)
```

```output
0 255
-128 127
-2147483648 2147483647
0 255
```

**The open family is why these exist rather than being written out.** A program can spell `4294967295`
for a `u32` and lose nothing; the largest `u10000` is 3,011 digits and cannot practically be written
at all, so for a wide member the attribute is the only way to name a value the type obviously has.
It is also why they cannot be a library table the way C's `UINT8_MAX` is — there is no finite set of
integer types to tabulate.

```sysl
print(u3::Min, u3::Max)
print(i5::Min, i5::Max)
print(u64::Max)
```

```output
0 7
-16 15
18446744073709551615
```

**They are constants taking no argument, so they fold.** That is what puts them where no call is
admitted — a `const` initializer, an `@assert` condition, an array bound:

```sysl
const LIMIT: u16 = u16::Max
const HALF: u32 = u32::Max / 2

print(LIMIT, HALF)
```

```output
65535 2147483647
```

**`usize` answers only a floor across targets.** Its width is the pointer's, so `usize::Min` is 0
everywhere and `usize::Max` is whatever the target makes it — a program that prints the latter is
reading a fact about the machine rather than about the language.

**`Min`/`Max` are not `First`/`Last` renamed**, and asking for the wrong pair is answered by name
rather than by a general refusal:

```sysl
print(u8::First)
```

```error
'u8' is an integer, not a declared sequence — its extremes are 'u8::Min' and 'u8::Max'. 'First' and 'Last' name the ends of an enum's variants or of a 'within' range, which is a different question
```

`First` and `Last` name the ends of a *declared sequence* — an enum's variants, a written range —
and an enum's discriminants may be explicit and non-contiguous, so its first-declared variant need
not carry the smallest value. The two questions coincide on an integer and only there. **A
`within`-ranged subtype answers both**, since there they genuinely agree, and a reader who learned
`Min` on `u32` should not find it renamed on a subtype of `u32`.

**And a subtype that narrows nothing has its base's**, because that is what it can hold. `First` and
`Last` still need a range, which is the same distinction one paragraph up — a declared sequence
against what the type can hold:

```sysl
type Handle = new u16

print(u16(Handle::Max))
```

```output
65535
```

That is the case a [`c type`](/reference/ffi/) is always in: a measured typedef carries no range, so
asking a `size_t` for its maximum is asking about the integer C said it is. A `where` predicate is
the exception and is refused — it narrows the type without saying to what, so there is no extreme to
read off the declaration.

Only integers have them: `f64::Max` and `bool::Max` are refused, the float because its extremes are
`sysl.math`'s business and the boolean because it is not that kind of type.

**A type parameter answers them too**, from whatever the instantiation bound it to — which is what
makes the bounds usable by a *library* rather than only by a program. Bounded narrowing, integer
parsing, saturating arithmetic and a min/max reduction's identity all want `T`'s extreme, and none of
them can name it any other way:

```sysl
widest[T]() -> T = T::Max

val a: u8 = widest()
val b: u16 = widest()

print(a, b)
print(widest[i8]())
```

```output
255 65535
127
```

One body serves every width it is instantiated at, and the argument may be written at the call or
inferred from what the result is used as.

**The parameter carries the type that was written**, so a `within`-ranged subtype answers its own
bound rather than its base's:

```sysl
type Age = int within 0..150

widest[T]() -> T = T::Max

val a: Age = widest()

print(int(a))
```

```output
150
```

This is the one place a transparent subtype and its base give different answers, which is why
[generics](/reference/generics/#inference-is-bidirectional) states the rule: everywhere a *value*
flows the two are interchangeable, and a bound is the one thing either can produce that the other
cannot hold.

**`Min` and `Max` are the only two a parameter answers.** The rest stay on a written type name:

```sysl
first[T]() -> T = T::First

val a: u8 = first()

print(a)
```

```error
'T' is a type parameter, and the only attributes one answers are 'T::Min' and 'T::Max'
```

The reason is the walk that checks a generic body **once**, with `T` standing for itself rather than
for any particular type. That walk has to hand back a typed value, and `Min` and `Max` both answer
*in `T`* — so one stand-in is right for both. `Valid` answers a `bool`, `Pos` a `usize` and `Image` a
`string`, so admitting those would mean restating each attribute's result type a second time, in a
second place, where the two copies could drift apart.

**A `derive` body's `T` is not such a parameter.** Its body is read once for each type that names the
trait in its `derives`, with `T` that type and never standing for itself, so it answers every attribute
the type does — `T::Image(self)` in an arm under `if const T::Kind == Kind.SimpleEnum`
([reflection](/reference/reflection/#writing-a-derivation)).

**Nothing is asked of `T` in the signature.** A parameter given a type with no extremes is reported
at the instantiation that gave it one, and the message names both:

```sysl
struct P
    x: int

widest[T]() -> T = T::Max

val a: P = widest()

print(1)
```

```error
'T::Max' needs an integer type, and 'T' is P here
```

That is the same deferral `sizeof(T)` takes, and it is what keeps a bound from being something a
generic has to declare in order to measure.

### A fixed-point type

A [fixed-point type](/reference/fixed-point/#attributes-and-the-bits) answers `Min`, `Max`, `Small`
— one unit, 2^-F — and `Bits(n)`, which makes a value from the integer it is stored as, and nothing
else:

```sysl
print(q31::Min, q31::Max, q15::Bits(16384))
```

```output
-1 0.9999999995 0.5
```

### What has no attributes

**A data enum**, because its value is a variant plus a payload, so a position, a name and a
neighbour are each questions about only half of it:

```sysl
enum Shape
    Circle(r: int)
    Rect(w: int, h: int)

print(Shape::First)
```

```error
'Shape::First' needs a simple enum, and 'Shape' carries data
```

**A generic enum**, because its name stands for no one value set until it is applied:

```sysl
enum Box[T]
    Full(v: T)
    Empty

print(Box::First)
```

```error
'Box' is generic, so 'Box::First' has no single enum to read
```

And an attribute outside the set is answered by name, since the set is closed:

```sysl
enum Color: u8
    Red
    Green

print(Color::Nonesuch)
```

```error
'Color' has no attribute 'Nonesuch'
```

### Reflection queries answer on every type

**The sets above belong to one kind of type each. A second family answers on any type**, because it asks
what the type is made of rather than what a value of it may do, and it is answered while compiling:

| written | answers |
|---|---|
| `T::Kind` | a `Kind` variant — `Struct`, `DataEnum`, `Int`, `Opaque`… |
| `T::Name`, `T::BaseName` | the type as written, with its arguments / the declared name alone |
| `T::Fields`, `T::Members` | the named fields / every stored member of a struct, tuple or union |
| `T::Variants` | the variants of an enum, simple or data |
| `T::Make(c, p)` | the variant `c` of a data enum, built from a payload |
| `T::Annotation[A]`, `f::Annotation[A]` | an `Option` of the program's own `@annotation` on a type or field |

A data enum, which has no `First` or `Pos`, is walked through `T::Variants` instead. The descriptors,
`for const f in T::Fields`, `v.(f)`, `f::Type` and the rest are on [reflection](/reference/reflection/).

## `@test` — a function with a caller nothing else has

A word after `@` that is neither `test` nor `tailrec` is answered by name rather than as grammar. The
set is closed, which is what makes a misspelling an error instead of a marker that quietly does
nothing — the built-in annotations are a closed list. A program's own are declared with `@annotation`
and read by [reflection](/reference/reflection/#annotations).

The annotation goes on its own line above an ordinary function declaration, which may still be
`private`:

```sysl
add(a: int, b: int) -> int = a + b

@test
adds_two()
    assert(add(1, 1) == 2, "one and one")

@test("an empty slice has no first element")
private first_of_empty()
    assert(true, "nothing to see")

print(add(2, 3))
```

```output
5
```

That program prints `5` and runs neither test, which is the whole of the arrangement: `sysl run`
builds the program, and the tests are for `sysl test`.

Six forms of the annotation:

| written | means |
|---|---|
| `@test` | an ordinary test, named after the function |
| `@test("a sentence")` | named by the sentence instead |
| `@test(should_trap)` | the test **passes** by stopping the program |
| `@test(should_trap: "past the end")` | …and the run must have printed that text |
| `@test(timeout: 30)` | the test may run for 30 seconds, whatever the run's own limit |
| `@test(ignore: "why")` | the test is compiled and checked, and **not run** |

They compose, in the order the table gives them — `@test("a sentence", should_trap: "past the end",
timeout: 30, ignore: "why")` is one annotation.

**Six, and `@test()` is not a seventh.** An empty argument list would mean what bare `@test` means, and
it is refused rather than accepted as a synonym: it is not a shorter way of saying nothing, it reads
as a description that was going to be there and got lost, and a reader who saw it accepted could not
tell which.

```sysl
@test()
subtracts()
    assert(3 - 1 == 2, "two")
```

```error
'@test' takes a description or nothing at all, and '()' is neither — drop the parentheses, and the function's own name becomes its description
```

Everything after the `(` is answered by the annotation, so a description that is not a string and an
argument list left open are told what `@test` wanted rather than being blamed on the function below.

### What a test may be

**A test is an ordinary function with a caller nothing else has**: no parameters, no result other
than a `Result[unit, E]`, not generic. All three are the same requirement from different sides, since
the runner calls it with nothing and reads the answer off whether it returned — or, for a test that
answers with a `Result`, whether it answered `Ok`. They are checked **at the annotation**, because
the function is a perfectly good function and it is `@test` that made a promise about it:

```sysl
@test
takes_one(n: int)
    assert(n > 0, "positive")
```

```error
a '@test' function takes no parameters, and 'takes_one' takes one — 'sysl test' calls it with nothing, so there is nowhere for an argument to come from
```

```sysl
@test
generic_one[T]()
    assert(true, "yes")
```

```error
a '@test' function has no type parameters, and 'generic_one' declares 'T' — a generic is compiled for the arguments a caller fixes, and the runner supplies none
```

```sysl
@test
gives_back() -> int = 1
```

```error
a '@test' function returns nothing or a 'Result[unit, E]', and 'gives_back' returns 'int' — a test's result is whether it came back or failed, so there is nothing to read a value with
```

### A test passes by returning

That is the whole protocol, and it is what lets a test assert **in the language it is testing**
rather than in a framework. A broken `require`, a bounds violation, an `unwrap` of a `None` — each
ends the process, and none of them had to know it was running under a test.

**A test may answer with a `Result[unit, E]`**, so the fallible code it exercises is called with `?`
just as the program calls it. An `Ok` passes. An `Err` fails the test: the run says the error —
through its `Display`, or its `Debug` where it has only that — and the
[error return trace](/reference/errors/#where-a-failure-came-from) under it, and exits with status 1:

```sysl
parse(s: string) -> Result[usize, string] = if s == "" then Err("empty input") else Ok(s.len)

check(s: string) -> Result[unit, string]
    val n = parse(s)?
    assert(n > 0)
    Ok(())

@test
reads_a_word() -> Result[unit, string]
    check("port")?
    Ok(())
```

Given `check("")` instead, the report under the failed test reads

```text
error: empty input
error return trace (origin first):
  main.sysl:1:61 in parse
  main.sysl:4:21 in check
  main.sysl:10:14 in reads_a_word
```

`should_trap` inverts the reading, for a test whose subject *is* the check. With a string it
additionally requires that the run printed it, which is what tells a trap from the **right** trap. A
bounds check says what failed before it traps — `@test(should_trap: "out of bounds for length 3")`
asks for exactly that — while every other compiler-inserted check raises a signal and says nothing,
so it satisfies `should_trap` and can satisfy no string — see
[what stopping looks like](/reference/errors/#what-stopping-looks-like).

### A test may be written and not run

A test for a defect that is not fixed yet is still worth writing — with the assertion that *should*
hold, rather than one bent to match what the code does today. `ignore` is how it lands: the function
is compiled and checked exactly like every other test, and `sysl test` reports it without starting it.

```sysl
parse_arms(n: int) -> int = n - 1

@test(ignore: "the parser drops the second arm")
keeps_both_arms()
    assert(parse_arms(2) == 2, "both arms")

print(parse_arms(2))
```

```output
1
```

**Checked, not run, is the whole point of the form.** An ignored test is held to everything a test
is — no parameters, no result, a body that compiles — so it cannot quietly stop being valid code while
it waits, which is what a test that was commented out or deleted does. It composes with the rest of
the annotation: `@test("both arms are kept", should_trap: "past the end", ignore: "…")` is a trap test
that is waiting, and it is reported as waiting rather than as a run that returned.

**The reason is required.** An ignored test that does not say what it is waiting for is the one nobody
comes back to, so bare `ignore` — or a reason that is empty — is refused:

```sysl
@test(ignore)
keeps_both_arms()
    assert(true, "both arms")
```

```error
'ignore' needs the reason the test is not run, written as a string — '@test(ignore: "the parser drops the second arm")'
```

Ignoring is something a *test* is, so there is no `@ignore` of its own; written above a function it
is answered with the spelling above.

### A test may be given a limit

Every test `sysl test` runs has a time limit — [the run's](/getting-started/cli/#test), 300 seconds
unless `--timeout` says otherwise — and a test that outlasts it **fails**, reading `timed out after
Ns`, rather than holding up every test after it. `timeout` gives one test a limit of its own, in whole
seconds, in place of the run's: longer for a test that is slow on purpose, shorter for one whose whole
point is that it finishes quickly, and `0` for none at all.

```sysl
import sysl.time.{sleep, millis}

@test(timeout: 2)
answers_promptly()
    sleep(millis(10))

@test("a soak that is meant to take a while", timeout: 0)
soaks()
    sleep(millis(10))

print("built")
```

```output
built
```

**The test's own limit beats the run's in both directions**, `--timeout 0` included: a run that asked
for no limit still stops a test that said `timeout: 5`, because the test is the one that knows. The
limit is a whole number of seconds from `0` to `2147483`, the most milliseconds an `int` counts, and
anything else is refused where it is written:

```sysl
@test(timeout: 1.5)
answers_promptly()
    assert(true, "prompt")
```

```error
'timeout' needs the seconds the test may run, written as a whole number from 0 to 2147483 — '@test(timeout: 30)'; 0 is no limit at all
```

### A test may find out that it cannot run

`ignore` is decided before the test starts. Some tests can only find out once they are running: a
test that drives a tool which is not installed, or talks to a device that is not plugged in. Such a
test calls **`skip`**, from `sysl.testing`, and the runner reports it as `skip` with the reason it
gave:

```sysl
import sysl.env.get
import sysl.testing.skip

needs_tool(name: string)
    if get(name).is_none() then skip(s"${name} is not set, so there is nothing to talk to")

@test
talks_to_the_board()
    needs_tool("BOARD_PORT")
    assert(false, "not reached on a machine without a board")
```

```
running 1 test

board.sysl
  skip  talks_to_the_board  skipped: BOARD_PORT is not set, so there is nothing to talk to

0 passed, 0 failed, 1 ignored — 2ms
```

**A test that printed a note and returned would have been reported `ok`**: a pass that checked
nothing. `skip` makes the run say so on the line a reader looks at last.

### A test may call the compiler that is running it

`sysl test` and `sysl run` start the program with **`SYSL`** in its environment: the absolute path of
the compiler doing the running. It is always set, replacing a `SYSL` the environment already held,
because one left by an outer run names the outer compiler. **`sysl.testing.compiler()`** reads it and
answers `"sysl"` where it is absent (a built program run by hand), so a test that builds a fixture or
asks for `--version` runs the compiler under test and not whichever `sysl` the search path finds:

```sysl
import sysl.process.capture
import sysl.testing.compiler

@test
asks_the_running_compiler_its_version()
    val answer = capture(compiler(), ["--version"]).unwrap()
    assert(answer.status.ok(), "the compiler answers --version")
```

- **`skip` is imported, not ambient.** It lives in `sysl.testing` rather than the root module, so a
  test file that also imports `sysl.harness.*` — which has a `skip` of its own, for the on-target
  runner — is not told the two collide.
- **`skip` ends the test at once.** It returns `never`, so nothing after it runs, and a helper the
  test calls may make the call on the test's behalf, as `needs_tool` does above.
- **It is not a pass and not a failure.** The summary counts it with the ignored tests, it does not
  change the exit status, and `--fail-fast` does not stop at it. The row's word says which kind it
  was: `ignored` is the annotation's, `skipped` is the test's own.
- **A failure before it is still a failure.** An `assert` that failed earlier ended the process
  before `skip` was reached, so the run is reported as that failure.
- **The test's `@teardown` still runs**, in a process of its own, as it does after a test that
  trapped. A teardown that fails turns the row into that failure. A `@setup` that calls `skip` skips
  the test it was setting up.
- A `should_trap` test that skips is reported as skipped, since it never reached the thing it was
  meant to trap on.

**How it works**: `skip` prints one control byte, the reason up to the end of its line, and then
ends the process with status 0. The runner looks for that byte in what the run printed. Because the
exit is clean, a `sysl test` older than `skip` reads the test as one that returned and reports `ok`,
which is what such a test was before `skip` existed, rather than reporting a failure.

### A test has one caller, and the program is not it

```sysl
run_it()
    adds_two()

@test
adds_two()
    assert(true, "yes")
```

```error
'adds_two' is a '@test' function, which 'sysl test' calls and nothing else does — every other build leaves it out, so this call would have no definition to reach. Work two tests share belongs in an ordinary function they both call
```

### What is dropped, and when

**Every build but `sysl test` drops the tests after the parse and before analysis** — `sysl run`,
`sysl build`, `sysl build-c`, `sysl emit-llvm` and `sysl build-lib` alike, for a program's own files
exactly as for a library's and a dependency's. What goes is every `@tests` file whole, and every
`@test` function and hook wherever it was written. A **syntax** error in a test still stops the
build, since every file is parsed first; everything after the parse — names, imports, types,
capabilities — is asked of a test by `sysl test` alone, which is the one command that runs it.

So a test's imports are never read by the program that ships. A `@tests` file may import a
[dev dependency](/reference/packages/#dependencies-a-test-alone-needs), which only `sysl test` fetches,
and the program still builds:

```sysl
main()
    print("built")

@test
checks_nothing_yet()
    val unused: NoSuchType = 0
```

```output
built
```

The `@test` above names a type that does not exist, and `sysl build` says nothing about it: a broken
test is reported by `sysl test`, the build that would run it. The module's capability rules reach its
tests there like they reach any other member — with the one difference that a
[`@tests` file states its own](/reference/modules/#a-tests-file-states-its-own-capabilities), since
that file is what every other build drops.

This is what lets a test sit beside what it tests: a program's tests do not run when it runs, and
nothing they need is asked of it.

An ordinary helper only a test calls leaves too, because with the test gone it is unreachable and
pruning notices; a helper the program also calls stays, because the program still calls it.

What a test build keeps regardless is the three definitions **nothing in the program names**: an
interrupt handler, an [`@export`](/reference/ffi/), and a `@section` definition (below). Each is
entered from somewhere no walk over the program can see — the processor, a caller outside this
compilation, and a linker script gathering a named section — so each is a root wherever the walk
begins. A test build swaps the roots, putting the tests where the entry point was, and these three
are roots there too. They have to be: leaving them out would not make them reachable from the tests
instead, it would make them reachable from nothing.

**A freestanding image is the one place an `@export` is not a root.** The compiler still emits it,
but the image is linked from its entry, a function at a time, and the linker drops every function
nothing the entry reaches calls — an export nobody calls included, with the thread-local storage and
the rest of what only it touched. An archive keeps every export, since what links it decides. An
export the image must keep though nothing in it calls it — a routine a debugger or a boot
ROM jumps to by name — is given a `@section` (below): a placed definition is marked for the linker
as well as the optimizer, and stays.

A [destructor](/reference/memory/) is entered from somewhere no walk sees as well — the release hook
generated from a payload type — but it is kept by its **type** rather than as a root: wherever
anything kept holds a value of the type, in a test build exactly as in a program, the destructor its
release calls is kept with it.

That matters most to a package, whose only build is its own suite. A test build dropping an export
would link, pass, and quietly go without the surface the package exists to provide.

**Why the drop comes *before* analysis.** Analysis is not a passive reading: a test naming
`Buf[int]` **creates** the whole of `Buf` at `int`, and that instantiation is an ordinary library
function afterwards, with nothing in it recording which declaration asked for it. Dropping the test
from an analyzed program would drop the test and keep everything it caused — shipping, in an
artifact, instantiations no caller of the library ever asked for. And a test's imports would be
resolved against a build that never fetched what they name.

**The line falls between parsing and analysis.** Every source is parsed before the drop, so a
**syntax** error in a test still stops a `build` or a `build-lib`. What a test no longer gets there is
everything after the parse — name resolution, types, visibility, capabilities, the `@test`
well-formedness rules above, generic instantiation, and the duplicate-`@export` check. So a test that
is well-formed text and wrong in every other way builds clean, and its real errors are reported by
[`sysl test`](/getting-started/cli/) (`sysl test --std` for the standard library's), which is where
tests are run. The refusals this page shows for tests are `sysl test`'s. That includes an ordinary
function calling a `@test` or naming what a `@tests` file declares: `sysl test` says why, and any
other build reports a name that is not there — since, in that build, it is not.

That last one is worth naming because it is the case where "everything after the parse" is easy to
apply to only half of a build. An `@export` in a `@tests` file names a symbol **this** build is about
to discard, so it can collide with nothing — and a check that read the tree before the drop would
refuse a program over a name that was never going to be emitted.

**Read the other way, `sysl test` is the build where such an export *is* a definition**, and it is
held to every rule on the [FFI page](/reference/ffi/) accordingly — private, variadic, generic, a
parameter C has no declaration for, and two exports claiming one symbol. Each of those asks about the
symbol table the build in hand emits, so which tree is read is the whole of the difference between
the two commands.

The one rule on that page a test build is **not** held to is module storage, and for a reason that is
about the build rather than about the tree: a test binary has an entry point of its own, so it fills
its own storage before the first test runs. That refusal belongs to the artifact a C project links on
a freestanding target, which is the one build with nowhere to fill it.

### `@tests` — a file of scaffolding

Pruning answers for a **program**. It does not answer for a **library**, which has no `main` to lower
outwards from, so every public declaration is a potential entry and all of them are emitted. A helper
only a test called would ride into the artifact and be advertised out of it, nameable by everything
that links the library. Nothing about the declaration says it is scaffolding, and nothing could — it
is an ordinary function, which is the point of it.

So the **file** says it, in its header, beside the capability clauses:

```sysl
@tests

fixture() -> int = 6 * 7

@test("the fixture is the answer")
the_fixture_holds() =
    assert_eq(fixture(), 42)
```

It is `@tests` and not `@test` because the two say different things: `@test` names something the
runner calls, and this names something no build but the runner's keeps. One word for both would read
as though the file were itself a test.

**Two rules, and either alone would be unsound.** Every build but `sysl test` drops everything such a
file declares — and nothing outside a test may name any of it. Without the second, a program that
called a helper would compile here and fail at the *link*, with a message about a missing symbol
rather than about the line that named it.

The restriction is stated over the **referring declaration**, not over the file it sits in. So a
`@test` function may name scaffolding wherever it was written — which is what keeps a test able to
sit beside what it tests — and another `@tests` file may, and nothing else may:

```sysl
@tests

fixture() -> int = 6 * 7

print(fixture())
```

```error
error: 'fixture' is declared in a file that said '@tests', so it is there for the module's tests and no build but 'sysl test' keeps it — only another such file, or a '@test' function, may name it
```

**A closure counts as the body it was written in.** It is lowered to a function of its own under a
name nobody wrote, so on its own terms it belongs to no file — but what decides is where it sits, not
what it ends up called. A lambda inside a test may name the test file's own scaffolding, and so may a
bare function name, which is the same thing written shorter:

```sysl
@tests

fixture() -> int = 6 * 7

apply(f: &Fn(int) -> int, n: int) -> int = f(n)

@test("the fixture is the answer, reached through a callback")
the_fixture_holds() =
    assert_eq(apply(v -> fixture() + v, 0), 42)
```

It goes when the file goes, too, which is the half that makes the naming safe: the program that ships
carries no body for it.

**An `impl` block sits in one only for a type that same file declares.** An `impl` declares no name;
it fills a slot in a method table, which the rest of the program reads without naming anything. Kept
in a test build and dropped everywhere else, an `impl` for a type the program has would mean a trait
answering one way while the tests ran and another way in the program that ships, so it belongs beside
the type. A type the `@tests` file declares itself has no such second answer — no other build has the
type at all — so its `impl` may sit beside it, and that is how a test gets a stand-in to hand to code
written against a trait:

```sysl
@tests

trait Area
    area(self) -> int

    described(self) -> string = s"area ${self.area()}"

measured(s: &Area) -> int = s.area()

struct Square
    side: int

impl Area for Square
    area(self) -> int = self.side * self.side

@test("a stand-in answers through the trait")
a_square_measures()
    val s: &Area = Square(3)

    assert_eq(measured(s), 9)
    assert_eq(s.described(), "area 9")
```

Where the trait is declared does not enter into it: a trait of the module and a trait of the test file
are equally fine. What is still refused is an `impl` for any type the file did not declare — one of
the module's, a builtin, a blanket `impl[T] … for T`, and a type another `@tests` file declares, since
which files a build keeps is decided file by file:

```sysl
@tests

trait Area
    area(self) -> int

impl Area for int
    area(self) -> int = self
```

```error
error: an 'impl' block in a file that said '@tests' has to be for a type that file declares, and 'int' is not declared in it — a test build would keep this block and every other build would drop it, so a trait would answer one way while the tests ran and another way in the program that ships. Write the block beside the type's own declaration, or declare a type for the tests in this file
```

A closure is the one exception the compiler makes for itself, and it is not a hole: the table it
writes is dropped with the closure.

**Whatever the file writes goes with it, so whatever it writes may name its scaffolding.** A method of
a type the file declares, written inline or in that type's `impl`, is as much the file's as a free
function is, and may call the file's helpers:

```sysl
@tests

helper() -> int = 41

struct Probe
    n: int

    bumped(self) -> int = helper() + self.n

impl Probe
    twice(self) -> int = helper() + helper() + self.n

@test("a stand-in's methods call the file's helper")
probe_bumps() =
    assert_eq(Probe(1).bumped(), 42)
    assert_eq(Probe(0).twice(), 82)
```

**A `@tests` file of declarations and bindings is never where a program starts**, even a root file
with no `module` header: no build but `sysl test` keeps it, so it cannot be the body a script runs.
Its `var`s are the module's storage, and a test reading one is a test like any other:

```sysl
@tests

var shared: int = 11

@test("a test reads the file's storage")
reads_it() =
    shared += 1
    assert_eq(shared, 12)
```

**Every test runs or is refused by name.** A `@test` or a hook written in the file a script starts in,
reading one of that script's bindings, would be nested into the script's body where the runner cannot
reach it — so it is refused, and marking the binding `static` makes it the module's:

```sysl
var n = 1
print(n)

@test("reads")
reads() =
    assert_eq(n, 1)
```

```error
error: 'reads' is a '@test' that reads a binding of the body of the file this program starts in, so it would be nested inside that body where the test runner cannot reach it — mark what it reads 'static', which makes it the module's
```

**It stops at the package boundary.** A package is compiled from source and a library arrives as an
artifact whose test files were never encoded, so a rule that let the reference cross would compile
against one and fail against the other. A test-support library *meant* to be imported is therefore
ordinary code that ships, and is a different thing from a file of scaffolding inside a package.

A build that depends on a package does not read that package's `@tests` files at all — not
analyzed, not even parsed, the header being enough to know one. One written for a newer compiler, or
against a dev dependency the consumer never fetches, stops nothing but that package's own `sysl test`.

### The runner

```bash
sysl test <path>
sysl test <path> --filter <text>
sysl test <path> --fail-fast
sysl test <path> --jobs <n>
sysl test <path> --timeout <seconds>
```

**One build, one process per test.** The tree is compiled once, into a binary whose entry point takes
a test's name and runs that test alone — the program's own statements and its `main` are not run,
though its module-level `val`s are still filled, since a test reads a module's storage like any other
function. The runner then starts that binary once per test.

**The tests are the named tree's own.** A dependency's modules are compiled in, since the tree calls
them, and its `@test` functions are not — a package's tests are run in the package's repository, by
`sysl test` there.

The process per test is not a cost being tolerated; **it is the mechanism.** A test that fails does
so by ending its process, so a run that shared one would report the first failure and nothing after
it. It is also what lets the tests run **several at once** — as many as the machine has processors,
or `--jobs <n>` — with nothing shared between them that a run of one at a time did not share; the
report waits for each row's turn, so it reads the same either way
([the CLI](/getting-started/cli/#test)).

Exit status is 0 if and only if every test that ran passed. A tree with no tests, and a filter that
matched none of the tests there are, both exit 0 and say which happened.

**A process that does not come back in time is stopped, and the run goes on.** Each test's process
is started in a process group of its own and given its limit ([`timeout`](#a-test-may-be-given-a-limit),
or the run's `--timeout`, 300 seconds by default); past it, the process and everything it started are
ended, the row reads `timed out after Ns` with what the test had printed underneath — a test's output
is written a line at a time, so a stopped test still has it — and the next test starts. In the other
direction, a test process watches the runner that started it and ends itself and its group once the
runner is gone, so a run stopped by hand, or killed outright, leaves nothing running behind it.

**An ignored test is reported, never left out.** It starts no process — not its own, and not its
module's `@setup` or `@teardown` — and its row says why in place of a time. The summary counts it,
so a run that ignored something says so on the line a reader looks at last:

```
running 2 tests

parser.sysl
  ok    holds            1ms
  skip  keeps_both_arms  ignored: the parser drops the second arm

1 passed, 0 failed, 1 ignored — 1ms
```

A test that [skipped itself](#a-test-may-find-out-that-it-cannot-run) gets the same `skip` row,
reading `skipped:` where an ignored test's reads `ignored:`, and the summary counts it in the same
figure.

Where nothing was ignored the summary is `N passed, M failed` exactly as it always was. A filter
selects an ignored test like any other and reports it as ignored, and an ignored test fails nothing:
it does not change the exit status, and `--fail-fast` does not stop at it.

### The hooks a module may write

Four more annotations, and none of them names a test — each names a function `sysl test` calls
*around* the ones it does. `@setup` runs before every `@test` in the module and `@teardown` after it;
`@setup_all` runs once, before the module's first test, and `@teardown_all` once, after its last. A
module may declare at most one of each. There is no way to nest them and no way for one test to opt
out of a hook the rest of the module has.

```sysl
@setup
opens_the_log()
    print("setup")

@setup
also_opens_it()
    print("setup, again")
```

```error
a module writes at most one '@setup', and this one already has one — 'opens_the_log'
```

A second `@setup` in one module is refused, and the message names both the function already declared
and the one that repeats it — the same shape as two `@test`s sharing a name, except that here there
is only ever room for one.

**The process per test, which "The runner" above already made the mechanism rather than a cost, is
what shapes everything about these four.** `sysl test` starts one process per test, so a hook that
runs *inside* that process sees what the process saw, and a hook that runs in a process of its own
sees nothing the test process held:

- `@setup` runs in the **test's own process**, immediately before the test. A module-level `val` it
  sets is filled by the time the test reads it, the same as any other statement that ran first would
  leave it.
- `@teardown` runs wherever the test left off: in the **test's own process** if the test returned, or
  in a **process of its own** if the test trapped — a trapped process is gone, so nothing after it can
  run inside it. What a `@teardown` can rely on releasing is therefore only what outlives a process —
  a file, a pid, a socket path — never a variable, because half the time there is no process left
  holding one.
- `@setup_all` and `@teardown_all` are each **their own process invocation**, run once, and share
  nothing with the tests they bracket except what outlives a process — the same kind of file, pid or
  path a `@teardown` is left with.

A `@setup` fault fails the tests it guards without running them, and names the setup function rather
than an assertion a test never reached; a `@setup_all` fault reaches every test in the module, so none
of them run. A `@teardown` or `@teardown_all` fault is a counted failure of its own, alongside
whatever verdict the test it followed already reported.

**Worked example: a module whose tests share a fixture across the process boundary.** `@setup_all`
writes the fixture once, to a file; each test reads it back; `@teardown_all` removes it. The file is
the whole of what crosses a process boundary — which is the reason a `@setup_all` exists at all,
rather than a `@setup` repeating the write once per test:

```sysl
import sysl.fs.*

writes_the_fixture()
    write_text("fixture.txt", "ready").unwrap()

removes_the_fixture()
    remove_file("fixture.txt").unwrap()

@setup_all
starts_the_fixture()
    writes_the_fixture()

@teardown_all
stops_the_fixture()
    removes_the_fixture()

@test
reads_the_fixture()
    assert(read_text("fixture.txt").unwrap() == "ready", "fixture ready")

writes_the_fixture()
print(read_text("fixture.txt").unwrap())
removes_the_fixture()
```

```output
ready
```

### `assert` and `panic`

Both are ordinary functions in the standard module:

```sysl
panic(msg: string) -> never
    print("panic:", msg)
    exit(1)

assert(cond: bool, msg: string)
    if !cond then panic(msg)
```

They exist because `require` is a promise about a **call**, checked on entry, and a test's fifth
statement has no contract to hang a claim on — the contract was about the arguments, four statements
ago.

They stop the program the way `unwrap` does — a line naming what happened, then the hosted exit —
rather than through the trap instruction, because **a check a program makes is one the compiler
cannot see, and the message is the whole point of it.** The message is required rather than
defaulted, because the condition's source is not available to print and a failure saying only
"assertion failed" sends its reader looking for which one.

### `trap` — the instruction without the message

`trap()` is the other half: it stops the program at once with the target's trap instruction and says
nothing. It answers `never`, so a function may end in it with no value to return; it needs no
capability, allocates nothing and is legal under `@no_alloc` and on every freestanding target.
Reach for it where a message cannot be written — a bare-metal `abort`, a fault handler — and for
`assert` or `panic` everywhere a reader would want to be told what broke.

```sysl
@no_alloc
halt() -> never = trap()

pick(n: int) -> int
    if n > 0 then return n
    halt()

print(pick(4))
```

```output
4
```

Like a compiler-inserted check it runs no `defer` and flushes nothing, and a
[`should_trap`](#a-test-passes-by-returning) test passes by it.

## `@assert` — a condition settled while compiling

```sysl
const capacity: usize = 512

@assert(capacity == 512, "the protocol fixes this")

print(capacity)
```

```output
512
```

The condition is a [constant expression](/reference/modules/), folded by the machinery
a `const` initializer already goes through — so it may name constants, `sizeof`, `alignof`,
`offsetof`, the arithmetic and comparisons over them, and an `if` or `match` that chooses between
them, including a constant declared *below* it, and call any function whose body can run while
compiling
([declarations.md § Calls where a type is read](/reference/declarations/#calls-where-a-type-is-read)):

```sysl
sq(n: int) -> int = n * n

@assert(sq(3) == 9, "the square of three")

print(sq(3))
```

```output
9
```

A true one emits nothing at all. A false one stops the compilation, quoting the message:

```sysl
@assert(1 == 2, "arithmetic broke")

print(1)
```

```error
assertion failed: arithmetic broke
```

The message is optional and is the reader's own, because they know what the number *means* — that a
struct matches its C counterpart, that a table is the size a protocol fixes — where the expression
alone says only that two numbers differ.

**The message is any `string` the compiler can work out while compiling**, not only a literal: an
interpolation over constants, a call whose body runs while compiling, and — inside a generic or a
[`derive`](/reference/traits/) body — what a type answers, `T::Name` or a field's `f.name`:

```sysl
const WIDTH: int = 3

@assert(WIDTH % 2 == 0, s"a width of $WIDTH is not even")

print(WIDTH)
```

```error
assertion failed: a width of 3 is not even
```

It is worked out **only once the condition has come out false**, so a message that could not be
computed costs nothing while the condition holds:

```sysl
const tenth(n: int) -> string = s"${10 / n}"

@assert(1 == 1, tenth(0))

print("the message was never worked out")
```

```output
the message was never worked out
```

Where the condition is false and the message cannot be worked out — it traps, reads storage filled at
run time, or is not a `string` — that is what is reported, in words that say the assertion failed:

```sysl
const tenth(n: int) -> string = s"${10 / n}"

@assert(1 == 2, tenth(0))

print(1)
```

```error
the message of this failed '@assert' traps while it is being compiled: integer division by zero
```

```sysl
@assert(1 == 2, 42)

print(1)
```

```error
the message of this failed '@assert' is of type 'int', and a message is a 'string'
```

**A failed comparison also says what each side came out as.** The compiler folded both in order to
decide the condition, so it has the numbers at the moment it reports that one of them is wrong, and
withholding them would leave you editing the literal and rebuilding to find out. A side you wrote as
a literal is not repeated back — it is on the line above the message — so `sizeof(FRect) == 16`
reports only the left; where both sides are computed, both are named. A condition that is not a
comparison has nothing to add, since the thing that came out `false` is its only operand.

**It is not `require`.** A `require` is a runtime precondition: it is compiled, it branches, and it
traps when the program reaches it. This is settled while compiling and reaches the binary as nothing.
A condition it cannot settle is refused rather than deferred — and a call that reaches run-time
storage, a `var` for one, is the line, since only a function holding what exists while compiling can
run then:

```sysl
var seed: int = 3

f() -> int = seed

@assert(f() == 3)

print(1)
```

```error
has to be a constant expression
```

### In a body, per instance

`@assert` may stand in a body as well, and there it is checked **for each instance** of a generic and
in each unrolled copy of a `for const` — against the types and values that instance was given, which
is the only moment `sizeof(T)` is a number. Inside an [`if const`](/reference/generics/) arm it is
checked only where that arm is taken, which makes it a compile-time error that a generic raises on
purpose:

```sysl
ring[T, const N: usize]() -> usize
    if const N > 64
        @assert(sizeof(T) * N <= 4096, "a ring this large belongs on the heap")
    N

print(ring[u8, 100](), ring[u64, 10]())
print(ring[u64, 1000]())
```

```error
assertion failed: a ring this large belongs on the heap — the left side is 8000 — where N = 1000
```

The first line compiles: `u8` times 100 is 100 bytes, and `ring[u64, 10]` never takes the arm.

The message is worked out in the instance's terms too, so it can name the type that was given:

```sysl
struct Big
    a: i64
    b: i64

fits[T]() -> int
    @assert(sizeof(T) <= 8, s"'${T::Name}' is wider than a register")
    1

print(fits[i32]() + fits[Big]())
```

```error
assertion failed: 'Big' is wider than a register — the left side is 16 — where T = Big
```

### Checking a C struct's layout

This is what it was built for. sysl lays a struct out in declaration order and is C-compatible by
construction, but from inside sysl that claim cannot be checked: `sizeof` reports what *sysl* laid
out, not what the header says, so comparing the two is a tautology.

Where the C side is `__attribute__((packed))` or was declared at a boundary, `@packed` and
`@align(n)` further down this page are how the sysl side says the same thing — and the check below is
what proves the two agree rather than merely look similar.

It stops being one when both sides name the same number. The C half goes in a `.c` beside the sysl,
which [is compiled with it](/reference/ffi/) and for the same target, so it reads the headers of the
machine being built for:

```c
#include <stddef.h>
_Static_assert(sizeof(struct pair) == 8, "struct pair size moved");
_Static_assert(offsetof(struct pair, b) == 4, "struct pair.b moved");
```

and the sysl half pins what sysl laid out:

```sysl
struct Pair
    a: i32
    b: i32

@assert(sizeof(Pair) == 8, "Pair must match struct pair")
@assert(offsetof(Pair, b) == 4, "struct pair.b moved")

print(sizeof(Pair), offsetof(Pair, b))
```

```output
8 4
```

Neither half finds the other's mistake, which is why both are written: the `.c` catches a header that
moved, and the `@assert` catches a struct that was transcribed wrong.

**Pin every field you read, not only the total.** A size catches a field that changed width and one
that was added. It says nothing about **order** — so two same-width fields transposed in the mirror
leave the total unchanged, and the size assertion on *both* sides passes while every read is off by
the distance between them:

```sysl
struct Pair
    b: i32
    a: i32

@assert(sizeof(Pair) == 8, "Pair must match struct pair")
@assert(offsetof(Pair, b) == 4, "struct pair.b moved")

print(1)
```

```error
assertion failed: struct pair.b moved — the left side is 0
```

That is the failure the whole pairing exists to prevent, arriving through the half that was not
checked. `offsetof(T, field)` closes it: it takes a type and a field **name**, and answers where the
field starts in bytes. A field the struct does not have is refused by name rather than reported as a
condition that would not fold.

### Inside a generic, where it is settled once per instantiation

A generic's interesting facts are not properties of its declaration — they are properties of each set
of arguments it is compiled for. `sizeof(T)` has no width until something chooses a `T`, so an
assertion about one is settled at every instantiation rather than once:

```sysl
slab[T](x: T) -> usize
    @assert(sizeof(T) >= sizeof(*u8), "a free block has to hold the link through it")
    sizeof(T)

print(slab(1u64))
```

```output
8
```

An argument the claim does not hold for stops the compilation, and the report names which one asked —
the mistake is at the call that chose the type, while the sentence explaining why is at the
declaration, so a message carrying only one of the two sends its reader to the wrong file:

```sysl
slab[T](x: T) -> usize
    @assert(sizeof(T) >= sizeof(*u8), "a free block has to hold the link through it")
    sizeof(T)

print(slab(1u8))
```

```error
assertion failed: a free block has to hold the link through it — the left side is 1 and the right side is 8 — where T = byte
```

**This is the check `require` cannot make.** A precondition over `sizeof(T)` written as a `require` is
a runtime branch for a fact that was settled at the call: a container instantiated at a type too
narrow compiles, ships, and traps the first time anybody uses it. The
[`slab`](https://github.com/sysl-lang/slab) package was written that way before this landed and is
the worked example of the difference.

A **value** parameter is bound the same way and so is checked the same way, which is what lets a
generic hold its caller to a bound on a length:

```sysl
scratch[const N: usize](xs: [N]int) -> usize
    @assert(N <= 4, "the scratch buffer is a stack array")
    N

var small: [2]int = [1, 2]

print(scratch(small))
```

```output
2
```

**A generic nothing instantiates is not checked**, and that is the same deferral an array bound
already lives under: `[sizeof(T)]u8` is a well-formed length nobody can name until a `T` is chosen,
and the width is not wrong there — it is not being measured yet. The claim is settled at the first
call, which is the first moment there is anything to settle. A condition that could *never* fold is
still refused inside a generic, exactly as it is outside one.

## `@no_alloc` on a function — one entry point that makes no heap storage

In a file's header `@no_alloc` gives the allocator up for the whole module
([modules](/reference/modules/)). **Above one function or method it gives it up for that definition
alone**, checked by the same two rules through every call the body makes: what the body itself
*makes* is refused at the outermost allocation, and a call that *reaches* a function that allocates
is refused at the smallest piece of the body that still arrives there. It is the form for a module
that has one path that must not allocate beside others that must — an interrupt entry beside the
system calls that share its file:

```sysl
boxed(n: int) -> &int = n

@no_alloc
on_irq(n: int) -> int = n + 1

print(on_irq(1), *boxed(2))
```

```output
2 2
```

`boxed` allocates and is not refused: the promise is `on_irq`'s, not the module's. When the
promised function reaches an allocation the refusal names the function that made the promise, what
the far end makes, and the calls between the two — the far end is somebody else's code, and the line
to change is as often in the middle of the chain as at either end:

```sysl
boxed(n: int) -> &int = n

middle(n: int) -> int = *boxed(n)

@no_alloc
on_irq(n: int) -> int = middle(n) + 1

print(on_irq(3))
```

```error
which makes a reference, by the call path 'on_irq' → 'middle' → 'boxed', and 'on_irq' declared '@no_alloc'
```

What the body makes on its own line is refused the same way the module's clause refuses it, naming
the function instead of the module:

```sysl
boxed(n: int) -> &int = n

@no_alloc
shown(n: int) -> usize = n.to_string().len

print(shown(42))
```

```error
the string a value renders as needs an allocator, and 'shown' declared '@no_alloc'
```

**A closure handed on as a `&Fn` makes nothing when nothing keeps it past the frame.** Its box is
built in the frame of the function that writes the closure — the header a heap box has, in a slot of
the frame — so a callee that only calls it, or takes a share and gives it back, allocates nothing
([closures](/reference/expressions/#a-closure-handed-on-as-a-fn-is-boxed-in-the-frame)):

```sysl
call(f: &Fn() -> unit) = f()

@no_alloc
counted() -> int
    var n = 0

    call(() -> n += 1)
    call(() -> n += 1)
    n

print(counted())
```

```output
2
```

One a callee keeps, one returned, one kept past a turn of the loop that builds it, and a `&sync Fn`
still go on the heap, and are refused here at the closure:

```sysl
var later: Option[&Fn() -> int] = None

install(f: &Fn() -> int)
    later = Some(f)

@no_alloc
setup()
    install(() -> 1)

setup()
```

```error
a reference needs an allocator, and 'setup' declared '@no_alloc'
```

**Directly under the header the word is still the module's clause.** The header is read first, and
blank lines do not end it, so a file whose first declaration is the promised function has the
module's clause instead; an `import` or another declaration above the function is what makes it the
function's. A function in a module that already declared `@no_alloc` is held to the module's clause,
which says everything the annotation would. **The module's clause binds its own files and never its
`@tests` files** ([a `@tests` file states its own
capabilities](/reference/modules/#a-tests-file-states-its-own-capabilities)): a test may use `to_string`,
f-strings and `Buf`, while the module's functions stay allocator-free when a test calls them.

**A method may carry it** — it lowers to a function like any other and is held the same way.
**A function declared inside another may not**: it is a closure by another spelling, and what it
captures is boxed by the function around it, outside the body the promise would be written on.

```sysl
outer(n: int) -> int
    @no_alloc
    inner(k: int) -> int = k + n

    inner(1)

print(outer(3))
```

```error
'@no_alloc' holds a top-level function or a method, and 'inner' is declared inside another function
```

Above anything that is not a function — a struct, a binding, an `extern` — it is the module's word
written in the wrong place, and the refusal says so before naming the other reading:

```sysl
f() -> int = 1

@no_alloc
struct P
    x: int

print(f())
```

```error
Above a function or a method, '@no_alloc' holds that one definition instead, and this declares neither
```

**A generic keeps the promise twice: as it was written, and at every instance.** The body it was
written as is held first, and what it makes whatever the type — the `n.to_string()` above — is refused
once, naming the declaration, however many instances the program makes. A method of a generic
struct and a method of a generic `impl` are held the same way:

```sysl
f() -> int = 1

@no_alloc
shown[T](x: T) -> usize = 42.to_string().len

print(shown(1), shown(true))
```

```error
the string a value renders as needs an allocator, and 'shown' declared '@no_alloc'
```

What depends on the type is only there to see once a type is chosen: a call through a bound reaches
a different implementation at each instance. Each instance is held to the promise as well, and the
refusal names the instance that breaks it; the instances that keep it are not mentioned:

```sysl
trait Size
    size(self) -> usize

impl Size for int
    size(self) -> usize = 8

impl Size for bool
    size(self) -> usize = self.to_string().len

@no_alloc
measured[T: Size](x: T) -> usize = x.size()

print(measured(1), measured(true))
```

```error
this reaches 'bool.size', which makes the string a value renders as, and 'measured' declared '@no_alloc' (instance 'measured.bool')
```

This is the function's own promise, not the module's: under a module's `@no_alloc` clause a generic
still answers only for the body it was written as ([modules](/reference/modules/)), since the type an
instance is made at is chosen by somebody else's code.

## `@no_block` and `@blocks` — a path that must never wait

An interrupt handler may take a completion and wake a thread, and must never sleep: sleeping there
switches away from an exception frame with the interrupt still unacknowledged. A real-time audio
callback is held to the same rule by a deadline rather than by a machine. **`@no_block` is that
promise, and `@blocks` marks what breaks it.** Above a function or a method, `@no_block` says the
definition never waits; in a file's header it says it of every body in the module. Either way it is
checked as `@no_alloc` is, **through every call the body makes**: a call that reaches a declaration
marked `@blocks` is refused at the smallest piece of the body that still arrives there.

```sysl
struct WaitQueue
    n: int

    @blocks
    sleep(*self) = self.n += 1

read_sector(q: *WaitQueue) -> int
    q.sleep()
    1

@no_block
on_irq(q: *WaitQueue) -> int = read_sector(q) + 1

var q = WaitQueue(0)
print(on_irq(&q))
```

```error
this reaches 'read_sector', which blocks through 'WaitQueue.sleep', and 'on_irq' declared '@no_block'
```

The refusal names the call the reader wrote and, after `through`, the declaration it arrives at —
the line to change is the first, and the reason is the second. A body that keeps the promise compiles,
and a sibling beside it may wait as much as it likes:

```sysl
struct WaitQueue
    n: int

    @blocks
    sleep(*self) = self.n += 1

@no_block
take(n: int) -> int = n + 1

drain(q: *WaitQueue) -> int
    q.sleep()
    q.n

var q = WaitQueue(0)
print(take(1), drain(&q))
```

```output
2 1
```

**Whether something waits is declared, not inferred.** A C function's waiting is invisible to the
compiler, and a sysl loop that spins until another thread moves cannot be told from a search, so
`@blocks` is written where the waiting is: above a function, a method, or an `extern` whose C call
waits.

```sysl
@blocks
extern "usleep" c_usleep(us: u32) -> int

@no_block
nap() -> int = c_usleep(10)

print(nap())
```

```error
this reaches 'c_usleep', which blocks, and 'nap' declared '@no_block'
```

**The standard library marks its own waiting operations**: `sysl.posix.time`'s `sleep` and
`nanosleep`, `Mutex.with` and `Mutex.lock_raw`, `Thread.join`, `Channel.send` and
`Channel.receive`, `sysl.net`'s `Socket.connect`, `accept`, `send`, `send_all` and `recv` and
`UdpSocket.send`, `send_to`, `recv` and `recv_from`, `Child.wait`, and the hooks a blocking read or
write of a descriptor, a file or a socket goes through — so a promised body reaches none of them,
however many calls away. `block_on`, which drives a task to its end on the calling thread, has no
declaration to mark and counts as blocking all the same. A generic is named as it was declared, since the type argument says nothing about
whether it waits:

```sysl
import sysl.threads.Mutex

f() -> int = 1

@no_block
guarded(m: *Mutex[int]) -> int = *m.lock_raw()

print(f())
```

```error
this reaches 'sysl.threads.Mutex.lock_raw', which blocks, and 'guarded' declared '@no_block'
```

A call through a trait object reaches the implementation the body erased into it, as for `@no_alloc`.
`try_with`, `try_lock_raw`, `try_send` and `try_receive` never wait and are not marked. A spin lock is not marked
either: it waits only as long as another core holds it, which is what an interrupt handler's own locks
are made of.

**A generic that promises is held as `@no_alloc` holds one**: the body it was written as, and then
each instance, so a call through a bound that one type answers by waiting is refused at that
instance:

```sysl
trait Poll
    poll(self) -> int

impl Poll for int
    poll(self) -> int = 0

impl Poll for bool
    @blocks
    poll(self) -> int = 1

@no_block
ready[T: Poll](x: T) -> int = x.poll()

print(ready(1), ready(true))
```

```error
this reaches 'bool.poll', which blocks, and 'ready' declared '@no_block' (instance 'ready.bool')
```

**The rules for where it stands are `@no_alloc`'s.** Directly under the header the word is the
module's clause, and a function there answers to it; a method may promise it; a function declared
inside another may not, being a closure. `@blocks` takes no arguments and marks a function, a method
or an `extern` function and nothing else — an `extern` variable is read rather than called:

```sysl
f() -> int = 1

@blocks
struct P
    x: int

print(f())
```

```error
'@blocks' says a call may wait, so it marks a function, a method or an 'extern' — and this declares none of them
```

The two above one definition contradict each other:

```sysl
f() -> int = 1

@blocks
@no_block
g() -> int = 2

print(g())
```

```error
'@blocks' says this definition may wait and '@no_block' that it never does
```

**Waiting is conduct, not a facility.** No machine takes it away, so `@no_block` has no
`requires` beside it the way `@no_alloc` has `requires heap`, and `@requires(block)` and
`@needs(block)` are refused:

```sysl
@needs(block)
f() -> int = 1

print(f())
```

```error
'block' is what a module does, not something a machine has, and no machine takes it away
```

## `@track_caller` — a function that reports its caller's line

A function marked `@track_caller` is handed the location of each call — `file:line:column` — and
`caller_location()` inside it answers that location rather than a line of its own:

```sysl
@track_caller
where() -> Location = caller_location()

print(where())
print(where())
```

```output
main.sysl:4:7
main.sysl:5:7
```

Outside a tracked function, `caller_location()` names its own call. A `Location` prints as
`file:line:column`, and has `file()`, `line()` and `column()` for its parts.

**A chain of tracked functions names the outermost call.** A tracked function's call of another
tracked function hands on the location it was handed, so a helper written over a helper still
reports the line in the program that started it. An untracked function in the chain is where it
stops: its calls name its own lines.

```sysl
@track_caller
inner() -> Location = caller_location()

@track_caller
outer() -> Location = inner()

plain() -> Location = inner()

print(outer())
print(plain())
```

```output
main.sysl:9:7
main.sysl:7:23
```

**What a tracked function reports names the caller too.** A check the compiler laid down in its
body — an array's index, a slice's range — reports the caller's location, and so does every tracked
function it calls. This is what the standard library is built on: `Buf`, `Frozen`, `Deque`, `Ring` and
`InlineRing`'s `at`, `set`, `insert`, `remove` and their `Index` members, `Option` and `Result`'s
`unwrap` and `expect`, and `assert`, `assert_eq`, `assert_slice_eq` and `panic` are all tracked, so
`b[5]` on a three-element `Buf` reports `index 5 out of bounds for length 3` with the subscript's
line and column, exactly as an array would, and a checking function of a program's own reports its caller's line by carrying
the mark too:

```sysl
@track_caller
positive(n: int) = assert(n > 0, "not positive")

positive(1)
print("held")
```

```output
held
```

A failed `positive(-1)` prints `panic: not positive (main.sysl:<the line of the call>)`.

### A call that cannot see the location gets the declaration's

The location is a parameter nobody wrote, after the declared ones, so a direct call — of a function,
a method, a generic function's instance, a member reached through a type parameter's bound — hands it
over. A **function pointer** and a **trait object** name no function where they are called, so what
they hold is a stand-in that passes the location of the tracked function's own declaration, as Rust
does:

```sysl
@track_caller
line() -> u32 = caller_location().line()

val f: *extern() -> u32 = &line
print(line())
print(f())
```

```output
5
2
```

A closure calling a tracked function is an ordinary call, and names the line in the closure.

### Where there is no location

A build that leaves the bounds-check locations out (`bounds_locations = false`, or
`--no-bounds-locations`) lays down none for a tracked call either: `caller_location()` is then
*unknown* — `known()` is false, it prints `<unknown>`, its line and column are zero — and the
reports that would have named it end at their values. Each distinct call site of a tracked function
otherwise costs one `file:line:column` string in the image, as a bounds check does. `__CALLER__` is
the raw pointer `caller_location()` is made of ([reserved identifiers](/reference/lexical/)).

### What it may not mark

A tracked function is called by sysl calls, which hand it the location. The callers that cannot are
refused:

```sysl
@export("count")
@track_caller
count() -> int = 1

print(count())
```

```error
'@track_caller' hands 'count' the location of each call as a parameter past its declared ones, and an '@export'ed function is called from C, which hands it none
```

`main`, a `@test` or a hook, an interrupt handler, an `async` function (whose body runs when its task
is resumed) and a variadic function are refused the same way, and so is an `extern`, whose body is
compiled elsewhere. It takes no arguments.

## `@tailrec` — an assertion that the frame is reused

A function whose last act is a call to itself compiles to a branch back to its own entry rather than
a second frame, so the recursion is bounded by the arithmetic and not by the stack. That happens
whether or not anything is written — see
[tail calls](/reference/declarations/#tail-calls) for what counts as the last act, and for the three
things that end a tail position.

`@tailrec` asserts the jump is there:

```sysl
@tailrec
count(n: int, acc: int) -> int =
    if n == 0 then acc else count(n - 1, acc + 1)

print(count(100000, 0))
```

```output
100000
```

What it buys is the refusal. The optimization is silent, so an edit that costs it is silent too —
until the day the recursion is deep enough to matter:

```sysl
@tailrec
count(n: int, acc: int) -> int =
    if n == 0 then acc else 1 + count(n - 1, acc)

print(count(5, 0))
```

```error
calls itself nowhere the jump can replace
```

It changes nothing about what is emitted. Write it where losing the jump silently would be a bug,
and leave it off everywhere else.

## `@consumes` — a call that takes everything its receiver holds

Some members hand back what their receiver held and leave the receiver empty: `Buf.freeze()` moves a
buffer's elements into the frozen buffer it answers ([memory](/reference/memory/#a-frozen-buffer-crosses)).
A read of that receiver afterwards can only find nothing, so **`@consumes` above such a member makes
the read a refusal**: once a local has been called on, it may not be read again on any path until it
is assigned a new value.

```sysl
import sysl.buf.{Buf, buf}

run()
    var b: Buf[int] = buf()

    b.push(1)
    b.push(2)

    val f = b.freeze()

    print(f.len(), b.len())

run()
```

```error
'b' is read here after 'b.freeze()' took everything it held — 'freeze' is '@consumes', so 'b' has nothing in it on this path until it is assigned again
```

**Assigning it ends it**, and so does binding the name afresh — a `var` inside a loop body is a new
local every turn:

```sysl
import sysl.buf.{Buf, buf}

run()
    var b: Buf[int] = buf()

    b.push(1)
    b.push(2)

    val f = b.freeze()

    b = buf()
    b.push(7)
    print(f.len(), b.len(), b[0])

    for i in 0..<2
        var c: Buf[int] = buf()

        c.push(i)
        print(c.freeze()[0])

run()
```

```output
2 1 7
0
1
```

**The check follows every path the body can take.** A branch that consumed on one side leaves the
local consumed after the two sides meet, and a loop is followed round, so a read on a later turn
counts — here the second turn's `push`:

```sysl
import sysl.buf.{Buf, buf}

run()
    var b: Buf[int] = buf()

    for i in 0..<3
        b.push(i)

        if i == 1 then print(b.freeze().len())

run()
```

```error
'b' is read here after 'b.freeze()' took everything it held
```

A branch that consumed and then left — a `return`, a `break` past the read — never reaches it, and is
not refused.

**Only a local is followed.** A field, an element, or what a pointer or a second name reaches may be
reached another way the body cannot see, so a call on one is accepted, and the member's own emptying
at run time is what a later read finds:

```sysl
import sysl.buf.{Buf, buf}

struct Holder
    b: Buf[int]

val h: &Holder = Holder(buf())

h.b.push(1)

val f = h.b.freeze()

print(f.len(), h.b.len())
```

```output
1 0
```

**A call through a trait object is not followed either**: which body a slot dispatches to is settled
at run time, so a trait member's `@consumes` says nothing about the object's own local. A method has
no second spelling the check could miss — `Buf.freeze(b)` is refused as every instance method named
through its type is.

It marks a member that takes a receiver, in any mode, and takes no arguments. Above a free function,
or a member that takes no `self`, it is refused:

```sysl
struct S
    v: int

    @consumes
    make() -> S = S(1)
```

```error
'@consumes' says a call to 'make' takes everything its receiver holds, and it has none — it takes no 'self'
```

## `@noinline`, `@inline` and `@cold` — what the optimizer is told about a definition

A `private` function is visible only inside the file that wrote it, so the optimizer knows every
call it has. With one call site it folds the body back into the caller and deletes it — which is
usually what you want, and is exactly wrong for a slow path. A buffer's `grow` is called from
`push`'s cold arm and from `extend`, and is almost never reached; inlined into either caller it
would make that caller large enough that it stops being inlined into *its own* callers. Splitting
the rare path out bought nothing.

`@noinline` says the call stays a call:

```sysl
@noinline
grow(n: int) -> int = n * 2

print(grow(21))
```

```output
42
```

`@inline` says the opposite, and it is the one a library reaches for more often. A member written to
be absorbed by its caller — a sequence's append, an accessor that checks one thing — sits a few
units either side of the budget the optimizer is willing to spend, and *which* side is decided by
things the member has no say in: how wide the element type is, how many counted fields it carries.
`@inline` raises that budget for this definition, so the decision stops moving when a struct one
field along grows:

```sysl
@inline
push(n: int) -> int = n + 21

print(push(21))
```

```output
42
```

**Bare, it is a hint, not an instruction.** The inliner is told to spend more and then decides as
it decides, and a definition that is enormous is still a definition. That is the right default — an
instruction would make a library's opinion about one member override a caller's whole code size.

**`@inline(always)` is the instruction.** Every call is replaced by the body, at every optimization
level — `-O0` included, where nothing else is inlined at all — so it is for the definition whose
call is known to cost more than its body: a decoder's per-symbol step, a fixed-point multiply, a
register accessor on a board built for debugging:

```sysl
@inline(always)
symbol(bits: int) -> int = bits & 0xff

print(symbol(298))
```

```output
42
```

It lowers to LLVM's `alwaysinline` in the place `@inline`'s `inlinehint` takes, and a member, a
generic — every instantiation — and an `@export`ed function may carry it like the bare word; the
exported symbol is still defined for C to call. **A function that calls itself is not inlined**,
whatever it is marked — LLVM leaves such a body out of line, as clang and rustc do with theirs, and
the program is still right. `@tailrec` turns the self-call into a jump, and a body that no longer
calls itself is absorbed like any other:

```sysl
@tailrec
@inline(always)
fact(n: int, acc: int) -> int = if n <= 1 then acc else fact(n - 1, acc * n)

print(fact(5, 1))
```

```output
120
```

`@cold` says the definition is reached rarely. The optimizer spends nothing on it and keeps its
blocks away from the hot ones — but it is a frequency, not a prohibition, and a cold function may
still be inlined:

```sysl
@cold
fail(n: int) -> int = n - 1

print(fail(43))
```

```output
42
```

**`@noinline` and `@inline` are one axis; `@cold` is another.** The first two contradict, and the
compiler refuses them together. `@cold` composes with either: a rare slow path wants `@noinline` and
`@cold` — kept out of line, and known to be rare — and a rarely reached helper written to be
absorbed wants `@inline` and `@cold`.

```sysl
@noinline
@cold
grow(n: int) -> int = n * 2

print(grow(21))
```

```output
42
```

```sysl
@inline
@cold
report(n: int) -> int = n + 21

print(report(21))
```

```output
42
```

### What `@inline` guarantees across packages and codegen units

A program is not always one module. A package — a dependency, or a `--lib` root — is compiled once
into an archive of its own that every program using it links, and a large program is cut into
codegen units compiled side by side. A call from one module into another is ordinarily a wall the
optimizer cannot see through. **At every optimization level but `-O0`, an optimized build carries
the body across that wall** for:

- every function marked `@inline` or `@inline(always)`, whatever its length, and
- every *small* function — a few statements' worth: an accessor, a comparison, a fixed-point
  multiply — whether marked or not.

The calling module is given a copy of the body to inline (LLVM's `available_externally`) and never
emits it, so the program still links the package's one definition, and a call the optimizer leaves
out of line is the same call it always was. The copy is made from the package's source on every
build, so an edit to the package is what the next build inlines; nothing is kept that could be stale.

**A package's own archive is a calling module too.** Its calls into the standard module, or into
another package, carry the same copies — including a call a generic reaches through a bound. A
package whose `is_nonzero[T: Zero + Eq](x: T) = x != T.zero()` is instantiated at `real` by the
package's own code compiles `real.zero()` to the constant `0.0` in its archive, as it would in the
program, rather than calling the standard module's `real.zero` out of line.

So the guarantee is **the same as within one file**: a call into a package, or into another unit,
inlines exactly where the same call would inline if both functions sat side by side — the hint is
still a hint, and the inliner still decides. What is never carried across:

- a function marked `@noinline`, or `@cold` without `@inline` — the call stays a call;
- an `async` function, an interrupt handler, one placed in a `@section`, or a variadic one;
- a function that reaches module storage filled at run time, or `@thread_local`, `@per_cpu` or
  `@main_thread` storage, or another package's storage, which is compiled by the program anyway (the
  package keeps no copy of it), and so needs nothing carried. Storage that is a constant image is
  not that: the package's own, and the standard module's, is laid down once in its own archive, and
  every other archive and the program name that one object — one address, one value — so a function
  reaching it, one that indexes a `Buf` or reads a lookup table say, is compiled into the package's
  archive like any other;
- a generic, which needs none of this: it is instantiated in the module that uses it, beside its
  callers.

`sysl emit-llvm` prints the program as one module and carries no copies.

### They mark a member too

A method lowers to an ordinary function, and these three are about that function — so a generic
container writes the word once and every element type it is used at gets it:

```sysl
struct Buf[T]
    cap: int

    @noinline
    @cold
    grow(self) -> int = self.cap * 2

    @inline
    room(self) -> int = self.cap + 21

var a: Buf[int] = Buf(21)
print(a.grow())
print(a.room())
```

```output
42
42
```

That is what `sysl.buf` does with its own `push`: the member is written so a caller can absorb the
whole of it, and `@inline` is what keeps the element type from deciding whether it does.

They are the only three annotations a member may carry that are not about a parameter.

### None of the three takes an argument but `always`

Whether a call stays a call is the whole of what the first says, `@inline` raises a budget rather
than naming one, and how rare a rare path is is not a number a program has. `always` is the one
argument there is:

```sysl
@noinline(2)
grow(n: int) -> int = n * 2

print(grow(21))
```

```error
'@noinline' takes no arguments
```

```sysl
@inline(200)
grow(n: int) -> int = n * 2

print(grow(21))
```

```error
'@inline' takes no argument but 'always'
```

The opposite request is a word of its own, so `never` is pointed at it:

```sysl
@inline(never)
grow(n: int) -> int = n * 2

print(grow(21))
```

```error
'@inline(never)' is written '@noinline'
```

### What they may not mark

They mark a function. A struct has no definition to keep out of line, a `const` is folded into every
use, and an `extern` names something this program does not define — a `declare` line carries no
function attributes at all.

```sysl
@noinline
struct S
    a: int

print(1)
```

```error
an annotation marks a function
```

`@inline(always)` above an `extern` is the case most often tried, and it says why — the body is in
the object the `extern` is linked from, and only that object's own compiler can put it anywhere:

```sysl
@inline(always)
extern abs(n: i32) -> i32

print(1)
```

```error
An 'extern' has no body in this program to put anywhere
```

`@ghost` is the one *other* annotation they contradict rather than merely have nothing to do with. A
ghost function is erased before anything is emitted, so there is no definition left for any of them
to be about:

```sysl
@ghost
@noinline
sorted(n: int) -> bool = n > 0

print(1)
```

```error
'@ghost' means there is none
```

### `@noinline` beside `@inline`

One forbids inlining and the other asks for it, so a definition carrying both is held to neither.
This is the one refusal in the group that is not about a missing definition:

```sysl
@noinline
@inline
grow(n: int) -> int = n * 2

print(grow(21))
```

```error
they contradict above one declaration
```

`@inline(always)` beside `@noinline` is refused the same way, and beside a bare `@inline` it says
one thing twice — the instruction already asks for everything the hint does:

```sysl
@inline
@inline(always)
grow(n: int) -> int = n * 2

print(grow(21))
```

```error
the two together say one thing twice
```

If what you meant was *keep it out of the hot path*, that is `@cold`, and it stands beside either of
them.

They stand beside everything else. `@tailrec` is about this function's call to *itself* becoming a
jump and `@noinline` about the call into it from elsewhere staying a call — two different calls, so
there is nothing for them to disagree about; and `@test` only says who calls the function.

## `@strict_fp` — floating point that follows the environment it runs in

An ordinary float operation is IEEE 754's answer for round-to-nearest, and nothing promises when it
runs or which exceptions it raises: the compiler may work it out while compiling, merge two identical
ones, or move one past a call. That is what lets `x * k` leave a loop. It is also what makes a
rounding mode impossible to use, because `set_rounding` is a call like any other and the addition
written after it may not be.

`@strict_fp` on a function says its floating point follows the **environment** in force when each
operation runs — the rounding mode, and on Arm the flush-to-zero and default-NaN controls — and raises
its exceptions there. [`sysl.fenv`](/library/fenv/) is how a program sets the environment and reads
the flags:

```sysl
import sysl.fenv.*

@strict_fp
thirds(x: f64) -> (u64, u64)
    set_rounding(Upward)
    val up = x / 3.0
    set_rounding(Downward)
    val down = x / 3.0
    set_rounding(NearestEven)
    (up.bits(), down.bits())

val (up, down) = thirds(1.0)
print(f"$up%016x")
print(f"$down%016x")
```

```output
3fd5555555555556
3fd5555555555555
```

The two divisions are the same expression with the same operands, and they are two operations, each
rounded in the mode written above it. Without the annotation the second is free to be the first one's
answer.

### What it covers

Inside a strict body, **every floating-point operation at `f32` and `f64`** follows the environment:

- `+`, `-`, `*`, `/`, and a compound assignment of one, on a float or lane by lane on a vector of
  them;
- a multiply feeding an add, which is [one fused operation](/reference/expressions/#a-multiply-feeding-an-add-is-fused-where-the-machine-fuses)
  rounded once, as it is everywhere, and [`x.mul_add(a, b)`](/library/math/#mul-add-a-multiply-and-an-add-rounded-once),
  which asks for that operation by name;
- the comparisons. `<`, `<=`, `>` and `>=` raise invalid when an operand is a NaN, as IEEE 754 says
  the relational predicates do; `==` and `!=` are quiet;
- the conversions: an integer read as a float and a float narrowed round in the mode in force, a
  float read as an integer raises inexact when it drops a fraction, and one the integer cannot hold —
  or a NaN — raises invalid while it [saturates](/reference/expressions/#conversions-are-calls) as it
  does everywhere;
- a conversion between `f16` and `f32` or `f64`, [below](#f16-conversions-and-nothing-else-at-f16);
- `sqrt`, `floor`, `ceil`, `round` and `trunc` called on a float, and the intrinsics a program
  declares with `extern "llvm.…"`.

Negation, `abs` and `copysign` touch the sign bit alone, round nothing and raise nothing, so they are
what they always are. **A negation is the sign bit flipped where it is written**: `x + -y` is never
made `x - y`, nor `(-a).mul_add(b, c)` a fused multiply-subtract, so where the answer is a NaN its sign
is the one the expression as written gives it — Arm, which hands a NaN operand back with the sign it
has, shows the difference. **A literal is a value, not an operation**: `0.1f32` is the binary32 nearest a
tenth whatever the mode, and so is a `const`, which is worked out while compiling. An expression of
literals is an operation like any other:

```sysl
import sysl.fenv.*

@strict_fp
shown() -> string
    set_rounding(Downward)
    val third = 1.0f32 / 3.0f32
    val tenth = 0.1f32
    set_rounding(NearestEven)
    f"${third.bits()}%08x ${tenth.bits()}%08x"

print(shown())
```

```output
3eaaaaaa 3dcccccd
```

The flags an operation raises are raised where it runs, so a read of them after it sees them:

```sysl
import sysl.fenv.*

@strict_fp
divided(x: f64, y: f64) -> string
    clear_flags()
    val q = x / y
    val f = flags()
    f"${q > 1.0} divide-by-zero ${f.divide_by_zero}, inexact ${f.inexact}"

print(divided(1.0, 0.0))
print(divided(1.0, 3.0))
```

```output
true divide-by-zero true, inexact false
false divide-by-zero false, inexact true
```

### Closures and nested functions are strict too

A closure or a nested function written inside a strict body is part of its text, so it is strict
too. That is what makes `with_rounding` useful — the closure it runs is the strict caller's:

```sysl
import sysl.fenv.*

@strict_fp
third_up(x: f32) -> u32 = with_rounding(Upward, () -> x / 3.0f32).bits()

print(f"${third_up(1.0f32)}%08x")
```

```output
3eaaaaab
```

**A closure takes no annotation of its own**, so the place to say it is the function it is written
in:

```sysl
val f = @strict_fp (x: f32) -> x + x
print(f(1.0f32))
```

```error
a closure takes no annotations, and '@strict_fp' reaches one through the function it is written in — every closure written inside a '@strict_fp' function is strict too, so mark that function, or move the closure into one
```

### What a strict body calls is compiled as it was written

A call from a strict body to an ordinary function runs that function's operations as ordinary ones:
they are not strict, whether the optimizer inlines the function or not, and the strict operations
around the call stay strict either way. A **generic** function is the same — a strict body that
calls `plain[T]` calls an ordinary instance — and a generic marked `@strict_fp` is strict at every
instance. A method takes the annotation as a function does.

The other way round, a strict function called from an ordinary one is strict, and is never inlined
into it: its operations would have to stay strict among ones that are not, so the call stays a call.
That is why `@inline(always)` cannot stand beside it:

```sysl
@strict_fp
@inline(always)
twice(x: f32) -> f32 = x + x

print(twice(1.0f32))
```

```error
'@inline(always)' puts the body in place of every call, and a '@strict_fp' body is only ever put inside a caller that is '@strict_fp' too — anywhere else the call stays a call, so the promise could not be kept. Write '@inline' for the hint, which a strict caller takes
```

`@strict_fp` takes no argument — the mode is whatever the environment holds — and it marks a
function or a method; above an `extern`, whose operations someone else compiled, it is refused.

### Where there is no environment to follow

A strict body computes at `f32` and `f64`. An operation at `f16`, `bf16` or `f128` is widened,
emulated or called on most machines, and is refused where it is written:

```sysl
@strict_fp
half(x: f16) -> f16 = x + x

print(half(1.0f16))
```

```error
a '@strict_fp' body computes at f32 and f64, the widths a floating-point unit performs under its environment, and this is f16
```

#### `f16` conversions, and nothing else at `f16`

The one operation at `f16` a strict body computes is a **conversion between it and `f32` or `f64`**,
because AArch64's `fcvt` and an Arm core's `vcvtb` convert binary16 in one instruction, rounding in
the mode in force and raising inexact, overflow, underflow and — for a signalling NaN — invalid. A
narrowing from `f64` is rounded once, never twice through `f32`:

```sysl
import sysl.fenv.*

@strict_fp
narrowed(x: f32, d: f64) -> string
    set_rounding(Upward)
    val up = f16(x).bits()
    set_rounding(NearestEven)
    clear_flags()
    val near = f16(x).bits()
    val once = f16(d).bits()
    f"$up%04x $near%04x inexact ${flags().inexact}, $once%04x"

print(narrowed(f32.from_bits(0x3f801000), real.from_bits(0x3ff0020000001000)))
```

```output
3c01 3c00 inexact true, 3c01
```

`1 + 2^-11` is halfway between binary16's `1.0` and the value above it: upward it goes up, to
nearest to the even `1.0`. The `f64` is a little above that halfway, which rounding it to `f32` first
would lose. An `f16` literal is a value like any other literal, IEEE's binary16 nearest it whatever
the mode; [Arm's alternative half-precision format](/library/fenv/#alternative-half-precision)
changes what a conversion produces.

Arithmetic and comparison at `f16`, and a conversion between `f16` and an integer, stay refused. So
is the conversion where it is a library call — on x86-64 and RISC-V, whose units have no binary16
instruction in the targets sysl builds for — and one between `f16` and `f64` on a single-precision
unit, a Cortex-M4F or M33, where it would be a call too, or two roundings through `f32`:

```sysl target=thumbv7em-freestanding
@strict_fp
@export("narrowed")
narrowed(d: f64) -> u16 = f16(d).bits()
```

```error
'@strict_fp' asks the floating-point unit to follow its environment, and 'thumbv7em-freestanding''s unit is single precision, so a conversion between real and f16 is a library call, which has no environment to follow and raises nothing; narrowing through f32 instead would round twice, so convert an f32, or build for a target whose unit has double precision
```

```sysl target=x86_64-freestanding
@strict_fp
@export("narrowed")
narrowed(x: f32) -> u16 = f16(x).bits()
```

```error
'@strict_fp' asks the floating-point unit to follow its environment, and on 'x86_64-freestanding' a conversion between f32 and f16 is a library call, since the unit has no instruction converting f16
```

A Cortex-M7 with a double-precision unit (`thumbv7em-freestanding-dp`) converts between `f16` and
`f64` in one `vcvtb`, and a strict body may.

A float a machine computes in software — every float on a core with no floating-point unit, an `f64`
on a Cortex-M4F, whose unit is single precision — has no environment to follow either, so it is
refused for that machine. Fixed point is integer arithmetic and follows no floating-point
environment, so a strict body neither changes it nor refuses it.

```sysl target=thumbv6m-freestanding
@strict_fp
@export("twice")
twice(x: f32) -> f32 = x + x
```

```error
'@strict_fp' asks the floating-point unit to follow its environment, and 'thumbv6m-freestanding' computes f32 in software, which has no environment to follow and raises nothing
```

So is a conversion whose integer is wider than the machine's word. A unit converts the integers its
registers hold, so a 32-bit Arm core reads an `i64` as a float with a library call, and every machine
does the same with an `i128`; the library rounds to nearest and raises nothing. An integer of the
word's width or narrower is the unit's own instruction:

```sysl target=thumbv7em-freestanding
@strict_fp
@export("widened")
widened(n: i64) -> f32 = f32(n)
```

```error
'@strict_fp' asks the floating-point unit to follow its environment, and on 'thumbv7em-freestanding' a conversion between long and f32 is a library call, since the unit converts integers of at most 32 bits
```

WebAssembly's floating point always rounds to nearest and raises nothing, so a strict body's float
operations are refused there. On a vector, lane-wise operations are strict, but a reduction —
`v.sum()`, `v.min()` — combines the lanes in an order the target chooses and has no strict form, so
inside a strict body it is refused, and the lanes are read and combined one by one instead.

## `@unchecked` — a block whose subscripts are not bounds-checked

`@unchecked` is the one annotation that marks a **block** rather than a declaration: written on a
line of its own, it covers the statements indented under it, and every subscript written there — of
an array, a slice, or a container such as `Buf` that says how — is read with no bounds check, and so
is a vector `load` or `store` of a run of elements.

```sysl
var squares = [0; 5]

@unchecked
    for i in 0..<5
        squares[i] = i * i

print(squares)
```

```output
[0, 1, 4, 9, 16]
```

The block is lexical: a closure or a nested function written inside it is inside it, and one called
from inside but written elsewhere is not. It takes no argument, and it does not mark a function, a
member or a single statement on the same line:

```sysl
@unchecked
first(xs: []int) -> int = xs[0]
```

```error
'@unchecked' marks a block
```

What it does to a subscript, `get_unchecked` and `set_unchecked` for one subscript at a time, and what
stays checked are in [arrays](/reference/arrays/#unchecked-access).

## `@disjoint` — parameters whose memory nothing else reaches

A kernel that writes through one view and reads through another is the loop a vectorizer exists
for, and the one it is least sure of. `axpy` below adds `a * x[i]` into `y[i]`; if `y` and `x` could
be two views of one array, offset by an element, then a store to `y[i]` changes what a later read of
`x` sees, and doing four elements at once would get the wrong answer. The optimizer cannot tell from
the function, so it vectorizes the loop *and* keeps a scalar copy, choosing between them at run time
by comparing the two address ranges on every call.

`@disjoint(y, x)` is the function saying the comparison is never needed:

```sysl
@disjoint(y, x)
axpy(y: []f64, x: []const f64, a: f64)
    for i in 0..<y.len
        y[i] = y[i] + a * x[i]

var ys: [4]f64 = [1.0, 1.0, 1.0, 1.0]
val xs: [4]f64 = [1.0, 2.0, 3.0, 4.0]

axpy(ys[0..<4], xs[0..<4], 2.0)
print(ys[3])
```

```output
9
```

**What it promises is C's `restrict`.** While a call runs, no memory reached through one named
parameter is reached through another named parameter, or through any other pointer the body uses.
The parameters are named on the function, as `@scoped(...)` and `@crossing(...)` name theirs — a
parameter carries no annotation of its own.

It names pointers and views: `*T`, `&T`, `[]T` and `[]const T`. A parameter of any other type
arrives as a copy, which nothing else can reach, so naming one promises nothing and is refused:

```sysl
@disjoint(n, p)
store(n: int, p: *int) = *p = n

var v = 0
store(42, &v)
print(v)
```

```error
'@disjoint' names 'n', a parameter of 'store' whose type 'int' is a value
```

A generic is held to that at each instantiation, where its parameters have types — the views and
pointers of every instance carry the promise. A name that is not a parameter, or one written twice,
is refused where it is written, as `@crossing`'s are; so is the annotation above an `async` function,
whose body runs after the call that made the task has returned, so that there is no call for the
parameters to be kept apart during:

```sysl
@disjoint(p)
async fill(p: *int) -> int = 1

print(1)
```

```error
'@disjoint' marks 'fill', which is 'async'
```

**A method names its other parameters, never `self`.** What a named parameter reaches, nothing else
the body uses reaches — the receiver included — so naming the slice a method copies from already keeps
it apart from the storage the method writes into:

```sysl
struct Ring
    bytes: [8]u8

impl Ring
    @disjoint(data)
    fill(*self, data: []const u8)
        for i in 0..<data.len
            self.bytes[i] = data[i]

var r = Ring([0; 8])
val src: [3]u8 = [7, 8, 9]

r.fill(src[..])
print(r.bytes[2])
```

```output
9
```

Writing the receiver into the list is refused, and says so:

```sysl
struct Ring
    bytes: [8]u8

impl Ring
    @disjoint(self, data)
    fill(*self, data: []const u8) = ()

print(1)
```

```error
'@disjoint' names parameters, and 'self' is the receiver — leave it out
```

**It is not `@no_overlap`**: the `@no_` words are the capabilities a module or a definition gives up — `@no_alloc`,
`@no_block` — and every `@no_<word>` is read as one, so a promise about two parameters takes a word of
its own.

### Checked where a call shows it broken

A call is refused where the arguments it hands two named parameters are provably the same storage:
the same local twice, two slices of one array or view whose constant bounds share an element, or a
view and the address of one of its own elements.

```sysl
@disjoint(y, x)
axpy(y: []f64, x: []const f64, a: f64)
    for i in 0..<y.len
        y[i] = y[i] + a * x[i]

var ys: [8]f64 = [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0]

axpy(ys[0..<4], ys[2..<6], 2.0)
```

```error
'axpy' is '@disjoint' over 'y' and 'x', and this call hands them storage that overlaps — 'ys[0..<4]' and 'ys[2..<6]' overlap
```

Slices that only meet at a boundary are separate storage, and a call handing them over compiles:

```sysl
@disjoint(y, x)
axpy(y: []f64, x: []const f64, a: f64)
    for i in 0..<y.len
        y[i] = y[i] + a * x[i]

var ys: [8]f64 = [1.0, 1.0, 1.0, 1.0, 1.0, 2.0, 3.0, 4.0]

axpy(ys[0..<4], ys[4..<8], 2.0)
print(ys[3])
```

```output
9
```

**Where the call cannot settle it, the promise is trusted, as C trusts `restrict`.** Two views a
caller was itself handed, a bound or an index computed at run time, a pointer read out of a field —
none of these is refused, and none is checked when the program runs. **A call that breaks the promise
anyway is undefined behaviour**: the optimizer has been told the loads and stores through the two
parameters never touch the same memory, and the code it makes is free to read a value before the
store that should have changed it, or after.

### Over a function's result

A function that cuts storage into pieces can promise the pieces never share an element. The parts of
its result are named by position, as a tuple's part is selected and by the name an `ensure` gives the
result — `result.0`, `result.1`:

```sysl
@disjoint(result.0, result.1)
halves(xs: []int) -> ([]int, []int) = (xs[..<xs.len / 2], xs[xs.len / 2..])

var v: [6]int = [1, 2, 3, 4, 5, 6]
val (l, r) = halves(v[..])
l[0] = 10
r[0] = 40
print(v[0], v[3])
```

```output
10 40
```

The standard library's `sysl.slices.split_at(xs, n)` is the general form, `(xs[..<n], xs[n..])`, and
makes the same promise. A result list (`-> []int, []int`) names its parts the same way.

**What the caller may rely on**: two parts of one call's result that the function names share no
element, after they are destructured, and so does every slice of either. Handed on to a function
`@disjoint` over two parameters, they are storage the call knows is apart rather than storage it
takes on trust. A part is **not** promised apart from the function's parameters — `split_at`'s pieces
are its parameter, cut in two.

That is what lets a recursive halving hand each half to a different writer. Every level cuts its own
piece, and the two it makes are apart, so every element is written exactly once:

```sysl
import sysl.slices.split_at

number(xs: []usize, from: usize)
    if xs.len == 1
        xs[0] = from
    else if xs.len > 1
        val (l, r) = split_at(xs, xs.len / 2)

        number(l, from)
        number(r, from + l.len)

@disjoint(y, x)
add_into(y: []usize, x: []const usize)
    for i in 0..<y.len
        y[i] = y[i] + x[i]

var v: [8]usize = [0; 8]

number(v[..], 10)

val (lo, hi) = split_at(v[..], 4)

add_into(lo, hi)
print(v[0], v[3], v[4], v[7])
```

```output
24 30 14 17
```

**What the function must show — it is checked, never trusted.** Every value the body answers, the
trailing one and each `return`'s, shows the named parts apart in one of two ways: slices of one
storage whose bounds meet, where one ends at the very bound the other begins at — the same literal,
or the same names and arithmetic, as `xs.len / 2` written twice — or two runs of literal bounds that share no element; or parts of one call of a
function that makes the same promise, which is how a function answers pieces of pieces. Slicing
traps past the end, so two slices that meet at a bound can never both reach one element. A name the
check traces a part through holds one value for the whole body: declared once, never assigned, its
address never taken. Anything else is refused where it is answered:

```sysl
@disjoint(result.0, result.1)
cut(xs: []int, n: usize, m: usize) -> ([]int, []int) = (xs[..<n], xs[m..])

var v: [4]int = [1, 2, 3, 4]
val (a, b) = cut(v[..], 2, 2)
print(a.len + b.len)
```

```error
'cut' is '@disjoint' over 'result.0' and 'result.1', and this answer does not show them apart — 'xs[..<n]' and 'xs[m..]' may share an element; a body shows it with slices of one view that meet at a bound, as '(xs[..<n], xs[n..])', or with parts of one call of a function '@disjoint' over its result
```

A generic's body is checked at each instantiation, as its parameters' types are. The promise
changes nothing the back end is told: it is a fact the compiler has checked, for the checks that
need to tell two pieces of one storage apart.

A part is held to what a parameter is: it reaches memory, it is a part the result has, and the
promise is about two of them, so one alone is refused:

```sysl
@disjoint(result.0)
cut(xs: []int, n: usize) -> ([]int, []int) = (xs[..<n], xs[n..])

print(1)
```

```error
'@disjoint' names 'result.0' and no other part of 'cut''s result, and a part is kept apart from the other parts named — name two, as '@disjoint(result.0, result.1)'
```

```sysl
@disjoint(result.0, result.1)
count(xs: []int) -> ([]int, usize) = (xs, xs.len)

print(1)
```

```error
'@disjoint' names 'result.1', a part of 'count''s result whose type 'usize' is a value
```

A list may name parameters and parts together, and each keeps its own promise: the parameters are
kept apart while a call runs, the parts from each other once it has returned.

### What the back end is told

A pointer `@disjoint` names carries LLVM's `noalias` on the function's definition. A view is two
pointers and a count in one value, and `noalias` attaches only to a pointer parameter, so a function
naming a view is emitted as two: the function with the signature every caller already uses, taking
each named view apart, and an `internal`, always-inlined body taking the parts, its element address
`noalias`. Its callers, function pointers and exports are unchanged.

The body is inlined into the function at every optimization level, and LLVM's inliner turns each
`noalias` parameter into the scoped alias metadata it uses for an inlined `restrict` — the form that
survives the function being inlined further, into a caller and a loop unrolled around it. `axpy`
above, built at `-O2`, is a vector loop with no run-time comparison of the two ranges; the same
function without the annotation keeps both copies of its loop and the comparison between them.

An `@export` of one is the same function behind a C entry point. An `extern` may not carry it — its
body is C, which never reads the sysl declaration, so the promise is made on the C side, as
`restrict`:

```sysl
@disjoint(d, s)
extern "memcpy" c_memcpy(d: *u8, s: *u8, n: usize) -> *u8
```

```error
an 'extern''s body is C, which never reads the sysl declaration, so the promise belongs on the C declaration as 'restrict'
```

## `@packed` and `@align(n)` — where a struct's fields sit

By default a struct pads: each field begins on its own alignment, and the aggregate takes the widest
of them. That is the layout a program wants unless something outside the program has an opinion.

```sysl
struct Head
    tag: u8
    len: u32

print(sizeof(Head), alignof(Head))
```

```output
8 4
```

Three bytes of that eight are a gap in front of `len`. **Two attributes move a struct off the
default, and they are separate axes** — one removes the padding *between* fields, the other raises
where the aggregate *begins*.

### `@packed` — no interior padding

```sysl
@packed
struct Head
    tag: u8
    len: u32

print(sizeof(Head), alignof(Head), sizeof([4]Head))
```

```output
5 1 20
```

The fields are laid end to end, the aggregate needs no alignment of its own, and an array of them has
no gap between elements either. A register block, and a C struct that has to match one, are what it
is for.

The fields still read and write their own values — packing changes where they sit, not what they are:

```sysl
@packed
struct Head
    tag: u8
    len: u32

var h = Head(7, 1000)

h.len += 1

print(h.tag, h.len)
```

```output
7 1001
```

**A packed field has no address.** `&s.f` is refused, and so is any `*T` into one:

```sysl
@packed
struct Head
    tag: u8
    len: u32

var h = Head(1, 2)
val p = &h.len

print(1)
```

```error
'&' here makes a '*uint' into Head, which is '@packed' — its fields sit at their declared offsets, so this one need not be on a 'uint' boundary, and every use of a '*uint' is entitled to assume that it is. Read or write the field through the struct, which is where the offset is known, or take the address of the Head itself
```

The field sits at its declared offset, which is very often *not* a multiple of its own alignment —
that is the point of the attribute — while a `*u32` is a `*u32` wherever it came from, and every use
of one is entitled to assume the address is aligned. Only the escaped address loses that, and it
would lose it arbitrarily far from the `&` that made it. **The struct's own address is untouched**,
being an ordinary pointer to an ordinary aggregate.

### Bitfields — an `iN` field in exactly N bits

**Inside `@packed`, an integer field occupies exactly its declared width.** A packed struct whose
fields all lower to an integer, at least one of them narrower than a byte, **is** one unsigned
integer — and its fields are ranges of it:

```sysl
@packed
struct Ctrl
    enable: u1
    mode: u3
    prescale: u4

val c = Ctrl(1, 5, 9)

print(sizeof(Ctrl), c.enable, c.mode, c.prescale)
```

```output
1 1 5 9
```

No syntax was needed for this, which is why there is none: the open integer family already does the
work C needs `int x : 3` for, so a five-bit field is written `u5` in a struct exactly as it is
anywhere else.

Two things follow that C leaves to the implementation, and pinning them is the point of the feature
rather than a detail of it — it is why portable embedded C avoids bitfields and writes the shifts out
by hand. **The bits fill from the least significant upward in declaration order**, and **a field
straddles a byte boundary** rather than moving off it. Both are visible in the storage:

```sysl
import sysl.raw.ptr_cast

@packed
struct Ctrl
    enable: u1
    mode: u3
    prescale: u4

var arena: [4]u8 = [0; 4]
var c: *Ctrl = ptr_cast(&arena[0])

c.enable = 1
c.mode = 5
c.prescale = 9

print(arena[0])
```

```output
155
```

`155` is `1 | 5 << 1 | 9 << 4`: the first field declared is the low bits.

The rule is stated over the **integer's value** and never over memory bytes. Written the other way —
"the low bits of byte 0" — it would be a claim about endianness, which nothing in sysl is allowed to
make. Written this way it costs nothing: the struct is an integer, so how it reaches memory is the
target's ordinary byte order for an integer of that width. A wire format's byte order belongs to the
protocol rather than to the CPU, and a struct of whole-byte fields states it with
[`@byte_order`](#byte-order-big-and-byte-order-little-the-order-a-struct-s-integers-keep-their-bytes-in)
below.

**Being one integer, it goes to and from that integer with nothing in between.** `T::Bits(n)` makes
the struct from the integer it is stored as and `x.bits` reads that integer back — the same pair a
[fixed-point](/reference/fixed-point/) type has, and for the same reason: neither converts anything,
both read one set of bits at another type. A system register read whole is decoded in one step:

```sysl
@packed
struct Esr
    iss: u25
    il: u1
    ec: u6
    iss2: u5
    res0: u27

val e = Esr::Bits(0xF2000000)

print(e.ec, e.il, e.iss, e.bits)
print((e with { iss = 7 }).bits)
```

```output
60 1 0 4060086272
4060086279
```

**The integer is the struct's own: unsigned, and as wide as its container** — the fields' widths
added and rounded up to whole bytes, so `Esr` is a `u64` and a struct of 3 and 21 bits a `u24`. A
literal takes that type from where it sits, and a narrower unsigned value
[widens](/reference/expressions/#which-conversions-are-implicit) to it as any argument does, so a
32-bit register read decodes into `Esr` directly:

```sysl
@packed
struct Esr
    iss: u25
    il: u1
    ec: u6
    iss2: u5
    res0: u27

val low: u32 = 0xF2000000

print(Esr::Bits(low).ec)
```

```output
60
```

A value that would have to be narrowed, or change its sign, is refused, and the conversion is
written:

```sysl
@packed
struct Esr
    iss: u25
    il: u1
    ec: u6
    iss2: u5
    res0: u27

val word: int = 0x3c

print(Esr::Bits(word).ec)
```

```error
'Esr::Bits' takes the ulong 'Esr' is stored as, not int
```

`Esr(raw)` is not the spelling because it is already the constructor, which takes one value per
field — and a bitfield struct of a single field would make it mean two things. A field or member the
struct declares named `bits` is what `.bits` reaches, ahead of the container.

**A `@packed` struct whose fields are all whole bytes converts too, though it is not stored as one
integer.** Its fields are read as ranges exactly as a bitfield struct's are — the first declared is
the low bits — each as wide as its type, so the integer is the fields' widths added: a `u8` and a
`u16` make a `u24`, a `u32` and a `u8` a `u40`. Its storage does not change: it is still its fields
end to end, with the size, alignment and C layout a `__attribute__((packed))` struct has, so a
protocol header or a C mirror is exactly what it was. On a little-endian machine — every machine
sysl targets — the integer is the struct's bytes read in order, wherever each field is a power of two
bytes wide. A register modelled from its description converts whether or not any of its fields is
narrower than a byte:

```sysl
@packed
struct Status
    code: u8
    _: u8
    count: u16

val raw: u32 = 0x00070011
val s = Status::Bits(raw)

print(s.code, s.count, s.bits == raw)
print(Status(0x22, 0, 9).bits, sizeof(Status))
```

```output
17 7 true
589858 4
```

A `@byte_order` struct's integer holds its fields' values, not the bytes of the wire: each field is
reversed on the way in and out exactly as a field read and write reverses it.

A generic struct converts at each instance, as wide as that instance's fields. The instance may be
written out in place on the left of `::`, as an alias of it would be, and a generic body reads
`.bits` at the width its instantiation has:

```sysl
@packed
struct Pair[T]
    lo: T
    hi: T

show[T](p: Pair[T]) = print(p.bits)

val a = Pair[u8]::Bits(0x0201)

print(a.lo, a.hi, Pair[u16]::Bits(0x00020001).hi)
show(a)
show(Pair[u16](1, 2))
```

```output
1 2 2
513
131073
```

An ordinary struct, a union and a struct with C bitfields have no `::Bits`, and `P::Bits(n)` on one
is refused saying which it is.

**Nothing is checked on the way in.** The bits are taken as they are, so an enum-typed field may hold
a value none of its variants has; the field is held to its variants where it is read, as one reached
through a pointer the hardware filled is.

**Every field has to be an integer** — or a simple enum, another bitfield struct, or a `@packed`
struct that converts itself. A `bool` is not
one here — its storage is a byte and its representation a bit — and neither is a pointer, a float or
an array:

```sysl
@packed
struct Mixed
    p: *u8
    a: u3

val m = Mixed(null, 1)

print(m.a)
```

```error
is one integer, and every field of it has to be one too
```

A `@packed` struct of whole bytes may hold any of those — it is laid out as fields — but then it does
not convert, and `::Bits` names the field that stops it:

```sysl
@packed
struct Sample
    id: u8
    value: f32

print(Sample::Bits(1).id)
```

```error
'Sample::Bits' has no integer to make it from: its field 'value' is f32 — only a '@packed' struct whose every field is an integer, a simple enum, a bitfield struct or a '@packed' struct that converts itself converts to one
```

**A bitfield struct may be a field of another, and there it occupies its own fields' widths** — the
bits it uses, not the whole bytes its own container rounds up to. That is how a group of flags shared
by several registers is named once: a page descriptor's ten lower attribute bits sit at bit 2 of the
block, the page and the stage 2 descriptor alike. A field of the inner struct is read and written
through both containers, and `.bits` and `::Bits` of the outer one cover all of it:

```sysl
@packed
struct Lower
    attr: u3
    ns: u1
    ap: u2
    sh: u2
    af: u1
    ng: u1

@packed
struct Desc
    valid: u1
    page: u1
    lower: Lower
    oa: u36
    rest: u16

var d = Desc(1, 1, Lower(5, 0, 1, 3, 1, 0), 0x12345, 0)

d.lower.ap = 2

print(sizeof(Desc), d.lower.attr, d.lower.ap, d.lower.sh, d.oa, d.lower.bits)
```

```output
8 5 2 3 74565 485
```

A struct that is not one integer is refused there — an ordinary struct, or a `@packed` one whose
fields are all whole bytes:

```sysl
struct Pt
    x: u8
    y: u8

@packed
struct Flags
    on: u1
    at: Pt

val f = Flags(1, Pt(2, 3))

print(f.on)
```

```error
Pt is laid out as fields rather than as one integer — only a '@packed' struct with a field narrower than a byte nests in another, in its own fields' width
```

**What decides whether a `@packed` struct is one integer is its own integer and enum fields**, so an
ordinary `@packed` struct whose fields are whole bytes lays a bitfield struct out as a field of its
size, as it always has.

```sysl
@packed
struct Ctrl
    enable: u1
    mode: u3
    prescale: u4

@packed
struct Block
    ctrl: Ctrl
    count: u32

var b = Block(Ctrl(1, 5, 9), 3)

b.ctrl.mode = 2

print(sizeof(Block), b.ctrl.enable, b.ctrl.mode, b.count)
```

```output
5 1 2 3
```

**A whole-byte `@packed` struct that converts may itself be a field of another that does**, at every
depth, and there it is as wide as its own integer. `Outer` below is a `u32`, `Header` in it a `u24`:

```sysl
@packed
struct Header
    a: u8
    b: u16

@packed
struct Outer
    h: Header
    c: u8

val o = Outer::Bits(0x04030201)

print(o.h.a, o.h.b, o.c, o.bits == 0x04030201)
```

```output
1 770 4 true
```

**A bitfield may be `volatile`, and it means a volatile access of the container.** Reading a field is
one volatile load of the whole container; writing one is a volatile load and a volatile store of it —
what C does with `volatile unsigned x : 3`, and what lets a `@packed` struct describe a hardware
register rather than only a data layout:

```sysl
import sysl.raw.ptr_cast

@packed
struct Reg
    enable: volatile u1
    mode: volatile u3
    prescale: volatile u4

var block: [4]u8 = [0; 4]
var r: *Reg = ptr_cast(&block[0])

r.enable = 1
r.prescale = 9
r.mode = 5

print(block[0], r.enable, r.mode, r.prescale)
```

```output
155 1 5 9
```

**The qualifier belongs to the container rather than to one range of it**, so writing it on any field
qualifies every access to the struct. Every field of a bitfield struct is bits of one word, so there
is no shadow field here to stay ordinary — which is the thing per-field qualification buys in an
ordinary register block. The struct itself still may not carry the qualifier, and does not need to:

```sysl
import sysl.raw.ptr_cast

@packed
struct Ctrl
    a: u3
    b: u5

var p: *volatile Ctrl = ptr_cast(4096)

print(p.a)
```

```error
'volatile Ctrl' is not a type
```

**A write is a read-modify-write, and nothing diagnoses what that costs.** A device is entitled to one
bus cycle, so writing a field of a register with **clear-on-read** or **write-1-to-clear** semantics
corrupts the ranges beside it — the read that begins the sequence has already had its effect. Nothing
in the language describes a register's read semantics, so this is stated rather than refused, exactly
as it is in C. A register of that kind keeps its `volatile u32` and its shifts, where the single
access is written out.

A field that is a set of named values is a **simple enum**, which is one integer and may be `volatile`
like any other. A **data enum** may not — a tag beside a payload is more than the one access the
qualifier promises.

**Reading an enum field is the checked conversion `E(n)`**, made where the field is read. The bits
were put there by hardware or by a cast, and a width with values no variant covers — six bits of an
exception class with two dozen defined — can hold one of them. A value no variant has stops the
program at the read, reporting it the way a failed [bounds check](/reference/errors/#what-traps) does,
rather than reaching `Image` as a wrong name or a `match` as no arm at all:

```text
value 42 is not one of the enum's variants (main.sysl:12:11)
```

An enum whose variants cover every value of its width — two variants in `u1`, four in `u2` — has
nothing to check, and its read is the shift and nothing more.

**The fallible read is `E.try(s.field)`** — the [conversion that answers instead of
trapping](/reference/errors/#turning-a-trap-back-into-a-value), handed the field itself. The field's
bits are read without the check and answered for: `Some` of the variant they are, or `None`. It is
how a kernel asks for "the exception class, if it is one I know" without declaring the field a plain
`u6`:

```sysl
import sysl.raw.ptr_cast

enum Ec: u6
    Unknown = 0x00
    Brk = 0x3c

@packed
struct Esr
    low: u26
    ec: Ec
    high: u32

describe(class: Option[Ec]) -> string = class match
    Some(c) -> Ec::Image(c)
    None -> "a class this kernel does not know"

var raw: u64 = 0x2a << 26
val e: *Esr = ptr_cast(&raw)

print(describe(Ec.try(e.ec)))

raw = 0x3c << 26
print(describe(Ec.try(e.ec)))
```

```output
a class this kernel does not know
Brk
```

A signed enum's field answers by its two's-complement bits, as its read does, and where the variants
cover the width `try` is `Some` with no test at all. Only a bitfield's enum field is taken as it is —
a value of the enum read anywhere else has been held to its variants already:

```sysl
enum Ec: u6
    Unknown = 0x00
    Brk = 0x3c

val c = Ec.Brk

print(Ec.try(c).is_some())
```

```error
'Ec' converts an integer, but the value has type Ec — a value of the enum is already one of its variants
```

**A bitfield has no byte offset**, so `offsetof` says so rather than rounding down to the byte the
field begins in:

```sysl
@packed
struct Ctrl
    enable: u1
    mode: u3
    prescale: u4

print(offsetof(Ctrl, mode))
```

```error
is a bitfield — it starts at bit 1 of the struct and is 3 bits wide, so it has no byte offset
```

It has no address either, which is the packed rule above and needs no separate ruling.

**Across a C boundary it travels as what it is** — a packed struct of one integer — and not as a C
bitfield struct. Those are different things on some machines, and it is C's own doing: MSVC allocates
`unsigned a:1` into a four-byte unit where the Itanium ABI packs it into a byte, so a C bitfield
struct of the same fields need not even be the same *size* everywhere. A sysl bitfield struct is one
integer on every target. Where the point is to match a C header's bitfields, the fields are written
as C writes them — the next section.

### C bitfields — `name: uW in T`, laid out as C lays it out

**A field written `name: uW in T` is C's `T name : W;`**: `W` bits allocated in a unit of `T`, the
type C declares the field with, by the target's own C rule. A struct with any such field is an
ordinary struct whose other fields sit where C puts them after a bitfield, so its size, its boundary
and every offset are what the C compiler for that target gives the same declaration. There are three
rules: **Itanium** (Linux, macOS, the BSDs, RISC-V, WebAssembly, i386 and CRAFT), **AAPCS** (Arm
outside Apple's platforms: an unnamed or zero-width field raises the struct's boundary too) and
**MSVC** (Windows: a run of bitfields shares a unit only while their declared types have one size, and
no field ever straddles a unit).

```sysl
struct Status
    ready: bool in u32
    mode: u3 in u32
    count: u12 in u32
    tag: u8

var s = Status(true, 5, 4000, 7)

s.mode = 6
s.count += 1
print(sizeof(Status), alignof(Status), offsetof(Status, tag), s.ready, s.mode, s.count, s.tag)
```

```output
4 4 2 true 6 4001 7
```

The field's value type is an integer of `W` bits, a simple enum whose underlying type is `W` bits
wide, or `bool` (`bool in bool` is C's `_Bool b : 1`). `T` is an integer type of 8, 16, 32 or 64 bits
or `bool`, `W` fits in it, and the value's signedness is `T`'s — `i3 in u32` is refused, because C's
read of the same field would disagree with sysl's.

**A read loads the unit, shifts the field down and cuts it to its width**, so a signed field comes
back with its sign; **a write is a read-modify-write of the unit**, so the fields beside it — in the
unit, in the next unit, and the ordinary fields around them — keep what they held. Compound
assignment reads the field, computes at its type and writes it back. An enum field is checked where it
is read, as any enum read from storage is, and `E.try(s.field)` answers instead of trapping:

```sysl
struct Range
    lo: i3 in i32
    hi: i3 in i32
    rest: u8

var r = Range(-4, 3, 255)

r.lo += 1
r.hi = -4
print(r.lo, r.hi, r.rest)
```

```output
-3 -4 255
```

**An unnamed field, `_: uW in T`, and a zero-width one, `_: in T`**, only move the fields after them —
C's `T : W;` and `T : 0;`. As in C, nothing initializes them: a construction and a positional pattern
name every field but these, and a value built field by field holds zero in their bits. A derived
`Eq`, `Ord`, `Hash` or `Display` reads every named field:

```sysl
struct Reg derives Eq, Display
    lo: i8 in i32
    _: u4 in u32
    hi: u8 in u32
    _: in u32
    next: u16 in u32

val r = Reg(-3, 200, 9)
val Reg(lo, hi, next) = r
val s = r with { hi = 7 }

print(sizeof(Reg), lo, hi, next, s, r == s)
```

```output
8 -3 200 9 Reg(-3, 7, 9) false
```

A module `val` of one is laid down as data, its bits where C puts them, as any constant struct is.

**`volatile` makes every access to the field's unit exactly the declared type's width** — one load of
the whole unit, and for a write one load and one store of it, never merged with a neighbour and never
narrowed. That is the AAPCS rule for volatile bitfields, and what a peripheral register needs: a
device may ignore, or fault on, an access narrower than its register. As with today's bitfields, the
qualifier on any field of a unit qualifies every access to that unit, and nothing diagnoses what a
read-modify-write costs a register whose read clears it. Under `@packed` a volatile field that lies
across two units of its declared type has no single access to make, and is refused.

`@packed` on such a struct is GNU's `packed` (MSVC's `pack(1)`): the fields abut bit for bit, and
`@align(n)` raises it as it raises any struct. The two kinds of bitfield do not mix in one struct — a
`@packed` struct with an `in` field may not also hold today's narrow fields — and `@byte_order` is
refused on one, there being no portable C spelling of a bitfield stored the other way round.

**A C bitfield has no address**: it is bits inside a unit and begins on no byte, so `&`, `ref` and
`offsetof` refuse it, and the struct is reached instead:

```sysl
struct Status
    ready: bool in u32
    mode: u3 in u32

var s = Status(true, 5)
val p = &s.mode

print(p[0])
```

```error
'&' here would reach 'Status.mode', a C bitfield — it is bits inside a unit of its declared type and has no address of its own
```

**What carries a count stays out**: a struct with C bitfields is laid out as C lays its counterpart
out, so a string, a slice or a reference among its fields is refused.

**It crosses a C boundary by value as C's own struct does** — as an `extern`'s argument or result,
an `@export`'s parameter or result, and through a `*extern` pointer either way. A C compiler
classifies such a struct by its fields, each named bitfield an integer of its own, and that is not
always how an ordinary struct of the same size travels: on x86-64 `{ double d; unsigned a:3; }`
comes back in a whole second register, WebAssembly passes one bitfield directly but two through
memory, and RISC-V's `lp64d` gives a float beside one bitfield a register of each but a float beside
two bitfields a single integer register. sysl follows clang on every machine it builds for:

```sysl
struct Status
    ready: bool in u32
    mode: u3 in u32

@export("next")
next(s: Status) -> Status = Status(!s.ready, s.mode + 1)

val step: *extern(Status) -> Status = &next
val s = step(Status(true, 5))

print(s.ready, s.mode)
```

```output
false 6
```

**The same holds for anything holding one by value**, at any depth: a struct whose field is one, an
array of them, a union over one, a struct holding any of those, and a struct with an anonymous
member that declares bitfields — which are the struct's own, as in C:

```sysl
struct Status
    ready: bool in u32
    mode: u3 in u32

struct Device
    id: u16
    states: [2]Status

@export("modes")
modes(d: Device) -> u32 = u32(d.id) * 100 + u32(d.states[0].mode) * 10 + u32(d.states[1].mode)

val report: *extern(Device) -> u32 = &modes

print(report(Device(1, [Status(true, 5), Status(false, 2)])))
```

```output
152
```

**Not built in this release**: its declaration in a generated header, which writes it as an
incomplete type C reaches through a pointer.

### `@align(n)` — where the aggregate begins

```sysl
@align(64)
struct Head
    tag: u8
    len: u32

print(sizeof(Head), alignof(Head))
```

```output
64 64
```

The size rounds up as well as the start, so an array keeps every element on the boundary. **It may
only raise**: asking for less than the fields already need changes nothing, since lowering is what
`@packed` is for and a type that under-promised would be unsound to pass around.

The boundary travels with every value of the type the compiler lays down: a module `var` or `val`,
a `static`, a local, an element of an array, a field of another struct — which begins on the
boundary and raises its holder's to match — and the payload of an enum variant carrying one.
**A value on the heap keeps it too.** A box (`&T`), a buffer or a task's frame takes its storage
from the allocating pair, which promises only what `malloc` does — two words, sixteen bytes on a
64-bit machine and eight on a 32-bit one — so storage of a type aligned beyond that comes from the
aligned pair, `sysl_alloc_aligned(size, align)` and `sysl_free_aligned(p)`, and the payload is laid
after the box's header on the boundary:

```sysl
@align(4096)
struct Page
    e: [512]u64

var p: Page
p.e[3] = 9

val b: &Page = p

print(usize(&*b) % 4096, b.e[3])
```

```output
0 9
```

Every access through an address of the type claims its whole boundary. With libc's `malloc` and
`free` on a POSIX system, WASI or Windows the aligned pair needs nothing from the program; anywhere
else the program answers it,
as [`packages.md` § A type aligned beyond what the pair promises](/reference/packages/#a-type-aligned-beyond-what-the-pair-promises)
says.

**The bound is folded rather than lexed**, so a program writes the name it already has for the number,
and arithmetic over one works:

```sysl
const CACHE_LINE: int = 64

@align(CACHE_LINE)
struct Head
    tag: u8
    len: u32

print(alignof(Head))
```

```output
64
```

It must be a power of two — an address is aligned by having low bits clear, so a boundary of six is
unsatisfiable rather than merely weak:

```sysl
@align(6)
struct S
    a: int
```

```error
'@align(6)' on 'S' is not an alignment — a boundary is a power of two, since an address is aligned by having low bits clear
```

### They compose

The gaps *between* fields and the boundary the whole thing *starts* on are different questions, so a
wire header that has to live in a DMA-capable buffer is both at once. The order they are written in
does not matter.

```sysl
@packed
@align(16)
struct Head
    tag: u8
    len: u32

print(sizeof(Head), alignof(Head))
```

```output
16 16
```

**Sub-byte fields are a separate axis and `@align(n)` does not touch them.** Inside `@packed` an `iN`
field occupies exactly N bits ([bitfields](#bitfields-an-in-field-in-exactly-n-bits) above); the
boundary the aggregate *begins* on is this attribute, and a bitfield struct takes one like any other.

### `@align(n)` on a binding

The boundary may also be written on the storage itself — a `var` or a `val`, at the top of a module
or inside a function, and on the `static` spelling of either.

```sysl
@align(64)
var region: [128]u8

region[0] = 7u8
print(region[0], region.len)
```

```output
7 128
```

It says nothing the struct form could not. A boundary put on a type travels with every value of it,
and a named aligned type is reusable where a repeated attribute is not — so reach for the type when
more than one thing needs the boundary. What this spelling saves is at the *use* site: a buffer
wrapped in a struct is read as `region.bytes[i]` rather than `region[i]`.

**`@packed` has no meaning here**, and says so, because it describes the arrangement of fields inside
an aggregate and a binding has none:

```sysl
@packed
var n: int = 1

print(n)
```

```error
'@packed' describes how a struct's fields are laid out, so it can only mark a struct — a 'var' or a 'val' has no fields to pack, and '@align(n)' is the one of the two that may stand above one
```

A binding that names **several** things is refused for a related reason: there is no one object for a
boundary to be about.

```sysl
@align(16)
var a, b = 1, 2

print(a, b)
```

```error
'@align(n)' is the boundary one object's storage begins on, and a binding that names several has no one object for it to be about — declare them on lines of their own
```

The bound is held to the same rule a struct's is — a power of two, folded rather than lexed, so a
`const` or arithmetic over one is what a program writes.

## `@byte_order(big)` and `@byte_order(little)` — the order a struct's integers keep their bytes in

A protocol header or an on-disk record has a byte order of its own, and it is the format's rather
than the machine's: IPv4, UDP and TCP keep the most significant byte first, on every machine that
reads them. `@byte_order` says so once, above the struct, and every integer field of it is then
**stored** in that order and **read and written** as an ordinary integer.

```sysl
import sysl.raw.ptr_cast

@packed
@byte_order(big)
struct Udp
    src: u16
    dst: u16
    len: u16
    sum: u16

var h = Udp(5353, 53, 8, 0)

h.len += 4

val raw: *u8 = ptr_cast(&h)

print(h.src, h.dst, h.len)
print(raw[0], raw[1], raw[4], raw[5])
```

```output
5353 53 12
20 233 0 12
```

`5353` is `0x14E9`, and the first byte in memory is `0x14` — the order the wire wants, though the
machine running this is little-endian. **The struct's storage is the bytes of the format**, so a
whole value is copied, cast over a frame, compared or handed to C exactly as those bytes, and the
reversal happens only where a field turns into a value and back: a read, a write, a construction, a
destructuring. On a machine whose own order is the one stated, nothing is reversed at all.

`@byte_order(little)` is the other word, for a format that keeps the least significant byte first —
most file formats, and every one written by a little-endian machine. On today's targets it reverses
nothing, since every machine sysl targets is little-endian; it is written so the struct still means
what it says on the first one that is not.

**It is a separate axis from `@packed` and `@align(n)`**, and composes with both. A byte order says
nothing about gaps between fields, so a header whose fields are not already on their own boundaries
is `@packed` as well — `Udp` above is four `u16`s and would lay out the same either way.

What a field may be, inside one:

- **an integer of a whole number of bytes** — `u16`, `i32`, `u24` — or a fixed-point number, or a
  simple enum (whose discriminant is the integer that is stored), or a name for any of these: these
  are the fields whose bytes are reversed;
- **a byte, a `bool`, or an array of either** — one byte has no order to keep;
- **a struct that states its own `@byte_order`**, or that holds only bytes, which is how a format
  whose fields disagree is written: two structs, one a field of the other.

A field is read through the struct and nowhere else, so **its address is refused** — a `*u16` is
read in the machine's order, and would hand every later read the bytes backwards:

```sysl
@byte_order(big)
struct Hdr
    len: u16

var h = Hdr(1)
val p = &h.len

print(1)
```

```error
'&' here would reach a 'ushort' inside Hdr, which is '@byte_order(big)' — the field holds its bytes most significant first whatever the machine does, and a '*ushort' is read in the machine's order. Read or write the field through the struct, which is where the order is applied, or take the address of the Hdr itself
```

A `ref` is refused in the same words, for the same reason. The address of the struct itself is
untouched — it is an ordinary pointer to the bytes of the format, which is what a frame is.

**Every other kind of field is refused, and the refusal says what to hold instead.** A float's bytes
are put in an order through its bits:

```sysl
@byte_order(big)
struct Sample
    level: f32

print(sizeof(Sample))
```

```error
'Sample.level' is f32, and 'Sample' is '@byte_order(big)' — a float is put in an order through its bits, which the language does not do behind a field read. Hold it as the unsigned integer of its width and convert with '.bits()' and 'from_bits'
```

An array of wider integers has elements, which are read by index rather than as fields:

```sysl
@byte_order(big)
struct Table
    slots: [4]u16

print(sizeof(Table))
```

```error
'Table.slots' is [4]ushort, and 'Table' is '@byte_order(big)' — an element is reached by index rather than read as a field, and the order is applied where a field is read. Hold it as bytes, '[N]u8', or make the element a '@byte_order' struct of its own
```

A struct held as a field keeps its own order, so one that states none would be read in the
machine's:

```sysl
struct Pair
    a: u16
    b: u16

@byte_order(big)
struct Outer
    p: Pair

print(sizeof(Outer))
```

```error
'Outer.p' is Pair, and 'Outer' is '@byte_order(big)' — 'Pair' keeps its integers in the machine's order, so a field of it would be read in the wrong one. Mark it '@byte_order' too
```

An address means nothing in the memory of whatever reads the bytes:

```sysl
@byte_order(big)
struct Link
    next: *u8

print(sizeof(Link))
```

```error
'Link.next' is *byte, and 'Link' is '@byte_order(big)' — an address means nothing outside the memory it points into, and a struct with a byte order is laid out to be written somewhere else. Hold an offset or an index instead
```

**A bitfield struct takes one too, and the order is its container's.** Its fields are ranges of one
integer, so the integer is what is stored in the stated order — IPv4's sixteen bits of flags and
fragment offset are exactly this:

```sysl
import sysl.raw.ptr_cast

@packed
@byte_order(big)
struct Frag
    offset: u13
    more: u1
    dont: u1
    reserved: u1

@packed
@byte_order(big)
struct Ipv4Tail
    ident: u16
    frag: Frag
    ttl: u8

var t = Ipv4Tail(0x1c46, Frag(0, 0, 1, 0), 64)
val raw: *u8 = ptr_cast(&t)

print(raw[2], raw[3], t.frag.dont, t.frag.bits)
```

```output
64 0 1 16384
```

The ranges are taken from the integer, as they are for any bitfield struct, so `dont` is bit 14 of
`0x4000` and the wire holds `0x40 0x00`. A bitfield struct with no order of its own is refused as a
field of one that has an order, its integer being in the machine's.

**A union takes one too**, and it orders every integer of every field — the bytes are shared, so a
word written through one field reads back in network order through another, and building the union
from that field stores it in the stated order. An anonymous member's fields are the enclosing type's,
so the order above the type reaches them as well. Each field is held to the rule above:

```sysl
@byte_order(big)
union Wire
    raw: [4]u8
    word: u32

var w = Wire(word = 0x01020304)

print(w.raw[0], w.raw[3])
w.raw = [0, 0, 1, 2]
print(w.word)
```

```output
1 4
258
```

The order is one of two words, and the attribute marks a struct or a union and nothing else:

```sysl
@byte_order(middle)
struct Odd
    a: u16

print(1)
```

```error
'@byte_order' names the order in parentheses — '@byte_order(big)' for a network header, which keeps the most significant byte first, or '@byte_order(little)' for a format that keeps the least significant first
```

```sysl
@byte_order(big)
var n: u16 = 1

print(n)
```

```error
'@byte_order(...)' is the order a struct's integer fields keep their bytes in, so it can only mark a struct — and this declares none. A lone value is put in an order with 'to_be' and 'from_be', the members 'sysl.math.ByteOrder' gives every whole-byte integer
```

A value that is not in a struct is put in an order with
[`sysl.math.ByteOrder`](/library/math/)'s `to_be` and `from_be`, and a byte-ordered struct is read
out of bytes, every `bool` and enum in it checked, with
[`sysl.encoding.Plain`](/library/encoding/)'s `decode`.

## `@pinned` on a struct — a value C holds pointers into

Some C state points into itself. littlefs's `lfs_t` keeps a pointer to its configuration and a list of
open files threaded through its own storage; a USB stack's device struct is handed to interrupt
handlers by address. Copy one, and C goes on writing through pointers into the copy that is gone.
**`@pinned` above a struct says a value of it is never copied or moved once it is built.**

A new value has no address yet, so it may land once — in a binding, in module storage, in a `&T`
box, in a field of another pinned value, or as what a function hands back, where it is built in the
caller's slot. After that it is reached only **through** it: a field, `&`, or a method taking `*self`.

```sysl
struct Config
    block: int

@pinned
struct Fs
    cfg: *Config
    open: int

    mount(*self, cfg: *Config)
        self.cfg = cfg
        self.open = 1

    size(*self) -> int = self.open * self.cfg.block

// A constructor hands back a value it has just built, and the caller's storage is where it lands.
filesystem(open: int) -> Fs = Fs(null, open)

var cfg: Config = Config(512)
var fs: Fs = filesystem(0)

main()
    fs.mount(&cfg)

    val boxed: &Fs = filesystem(2)

    boxed.mount(&cfg)
    print(fs.size(), boxed.open, fs.cfg.block)
```

```output
512 1 512
```

Everything that would read the value whole is refused, each with what it does to the value:

```sysl
@pinned
struct Fs
    open: int

var fs: Fs = Fs(0)

main()
    var b = fs
    print(b.open)
```

```error
'Fs' is '@pinned' — C may hold pointers into a value of it, so a value is never copied or moved once it is built, and this binds a copy of it. Take its address ('&') or name it with a 'ref' instead, or build the value in place
```

The same sentence ends differently for each form:

| written | refused because this… |
|---|---|
| `var b = fs`, `val p: &Fs = fs` | binds a copy of it / copies it into a new box |
| `c = fs`, `fs = Fs(2)` | assigns a copy of it / assigns over a value already built, which C may still hold pointers into |
| `take(f: Fs)`, `take(fs)` | parameter 'f' of 'take' takes it by value / passes it by value |
| `peek(self)` in the struct | receiver of 'Fs.peek' is taken as '(self)', which is a copy |
| `give() -> Fs = fs` | returns it by value — only a value built right there may be handed back |
| `() -> here.open` returned or stored | this closure captures 'here' by value — a closure that does not outlive the local holds it by reference, and is accepted |
| `val Fs(n) = fs`, `fs match` | takes it apart / matches on it, which reads it whole into a copy first |
| `(Fs(3), 1)`, `Option[Fs]` | puts it in a tuple / carries it by value in variant 'Some' |
| `for item in all` over `[2]Fs` | copies each element into the loop variable |
| `Buf[Fs]` | makes it an element of a buffer whose storage moves when it grows |

**Assignment is refused even of a new value**: the one already there may be what C points into. A
pinned value is built once, where it lives, and changed afterwards through its own members.

### A type holding one is pinned too, and says so

A struct holding a pinned value by value moves it whenever the struct moves, so it is pinned as well —
and it is **marked**, not inferred, so the reader of the outer declaration sees that its address
matters:

```sysl
@pinned
struct Fs
    open: int

struct Holder
    fs: Fs

var h: Holder = Holder(Fs(1))

main()
    print(h.fs.open)
```

```error
and this holds it by value in field 'fs' of 'Holder', which moves it whenever it moves. Mark 'Holder' '@pinned' too, or hold a '&Fs' or a '*Fs'
```

An array of pinned values is pinned the same way, and a `[]Fs` view, a `*Fs` and a `&Fs` are all
fine: none of them is the value.

### A generic is held to it at the instance

A generic instantiated at a pinned type is checked as the instance, the way an instance is held to its
declaration's `@no_alloc`: `addr[T](x: *T) -> *T` is fine at `Fs`, and `first[T](x: *T) -> T = *x`
is refused, the refusal naming `first.Fs`. A container is refused at the type when any of its members
is `@relocates`, since that member moves its elements — which is why `Buf[Fs]` is.

A `drop` is the one member taking `self` that a pinned struct may have: it is handed the value as the
value is destroyed. And `@pinned` marks a struct beside `@packed`, `@align(n)` and `@export(...)`;
above a function it is still the per-core promise of [`@per_cpu`](#per-cpu-one-copy-per-processor-core).

## `@section("...")` — where a symbol lands

`@align(n)` says what boundary storage begins on. `@section` says **where the storage is**, and it is
what a program reaches for when the address of a thing is part of what it is: a vector table at the
address the processor fetches from, storage in `.noinit` that survives a warm reset, a DMA buffer in
the RAM bank the engine can reach, a function copied into RAM so it can run while flash is being
erased.

It marks whatever occupies an address — a module `var`, a module `val`, a function, and an `asm`
block written at the top of a file, which is how code that runs before there is a stack is placed
(see [inline assembly](/reference/inline-assembly/); such a block also takes `@align(n)`).

```sysl
@section(".noinit")
var crash_reason: u32

@section(".ramfunc")
erase_page(n: usize) -> bool = n < 64
```

**Nothing on this page runs one of these.** A section name belongs to the target's object format —
`.noinit` is ELF's spelling, `__DATA,__mine` is Mach-O's — so a program that places something is a
program about one machine, and the machine this page's examples run on is not the interesting one.

That is also why the compiler does not check what is in the string. A set of characters chosen by
sysl would refuse a section some target requires, and the assembler that will not take one says so
better than a rule here could. It is `extern`'s link name and `@export`'s symbol read a third time:
a spelling belongs to whoever consumes it.

### It composes with `@align(n)`

The two are different axes — one is the boundary an object *begins* on, the other is where it
*lives* — and a statically placed stack is written with both. The order does not matter.

```sysl
@align(4096)
@section(".noinit")
var page_table: [512]u64
```

### A placed symbol is kept

Nothing inside the program reads a table that a linker script gathers — that is the whole point of
writing one — so a placed definition is a **root**, exactly as an interrupt handler and an `@export`ed
function are, and a placed symbol is marked so that the optimizer does not delete an object with no
reader.

Without that second half the attribute would compile, link, and place nothing. The failure would be
the *absence* of a section, which is not a thing anybody looks for.

**A placed definition a dependency supplied is kept only where your program reaches its module**,
which is the rule [the FFI page](/reference/ffi/) states for every kind of root at once. It bites
hardest here, because being marked is exactly what stops anything downstream undoing it: a placed
definition kept for want of the rule is bytes in your image that no optimizer will remove, in the
region the attribute exists to manage. A package whose placed definition you want is one you name —
an `import` is enough.

### What it may not mark

A `const` is folded into every use and has no storage; a type is not an object. Neither takes a
section:

```sysl
@section(".rodata")
const N: int = 4

print(N)
```

```error
'@section("...")' places one object in a linker section, so it marks a 'var', a 'val' or a function
```

A **local** is refused for a different reason, and the message says which — its storage is the frame
of whichever call is running, while a section is a region of the image, decided once at the link:

```sysl
f() -> int
    @section(".noinit")
    var n: int = 1
    n

print(f())
```

```error
'n' is a local, so it cannot be placed in section ".noinit"
```

A binding that names several things has no one object for a section to be about, which is the rule
`@align(n)` above already follows:

```sysl
@section(".data")
static var a, b = 1, 2

print(a, b)
```

```error
'@section("...")' places one object, and a binding that names several has no one object for it to be about
```

## `@thread_local`

`@thread_local` gives a module `var` one copy per thread rather than one for the whole program. Every
thread reading the name reads its own storage, and `&name` answers the address of the copy belonging
to the thread that took it.

The initializer has to be a value the compiler can write down — a literal, `null`, a `const`, the
address of other module storage, or an array or struct built from those — or left off, which starts
every thread at the type's zero. That is
the one rule the attribute adds, and it follows from what a per-thread copy is: the copies are made
from a single image in the object file, because a thread started halfway through a run never passes
through the prologue an ordinary module `var` is filled by. A call that has to run when the program
does — one that prints, or reads a `var` — would fill exactly one copy and leave every other thread
reading zeroes, so it is refused where it is written.

It marks a `var` and nothing else. A `val` and a `const` never change, so the one copy each already has
is every thread's.

```sysl
import sysl.threads.*

@thread_local static var n: int = 0

count_to_five(state: *int)
    for i in 0..<5
        n = n + 1
    print(n)

var a = spawn(&count_to_five, null).unwrap()
a.join()

var b = spawn(&count_to_five, null).unwrap()
b.join()

print(n)
```

```output
5
5
0
```

Each worker's `n` starts at zero and counts up in storage no other thread can see; joining one before
spawning the next is what makes the two `5`s print in a fixed order rather than racing. Main's own
copy is never touched, so its `print(n)` reads the zero it started at — the third line is the proof
that `@thread_local` gave three threads three different objects under one name.

### It composes with `@align(n)` and `@section("...")`

All three are facts about where one object's storage lives, so a thread-local can be aligned or placed
exactly as an ordinary module `var` can:

```sysl
@align(16)
@section(".tdata")
@thread_local var scratch: [4]u64
```

### A `&T` into a thread-local needs no `&sync`

A reference into a `@thread_local` belongs to one thread by construction, so there is no second thread
for its count to be torn by, and reaching it is not crossing a concurrency domain.

### What it may not take as an initializer

```sysl
next() -> int
    print("next")
    42

@thread_local static var n: int = next()

print(n)
```

```error
'n' is '@thread_local', so its value has to be one the compiler can write down: every thread gets a copy of the initial value, and the copies are made from a single image in the object file rather than by code that runs per thread. A literal, 'null', a 'const', the address of other module storage and the arrays and structs built from them are all values; a call is not. Leave the value off to start every thread at the type's zero, and do the work in each thread instead
```

### It may start at the address of other module storage

The address of an ordinary module `var` or `val` — or of a field of one, or of an element at a
constant index — is a relocation the linker fills in once, so it is a value the image can carry, as
C's `static _Thread_local Vm *vm = &main_vm;` is. Every thread starts pointing at the same storage, and
each can then point its own copy somewhere else without the others seeing it:

```sysl
import sysl.threads.*

struct Vm
    id: int

static var main_vm: Vm = Vm(1)

@thread_local static var current: *Vm = &main_vm

worker(seen: *int)
    var mine = Vm(2)

    *seen = current.id
    current = &mine
    *seen = *seen * 10 + current.id
    current = &main_vm

var seen = 0

spawn(&worker, &seen).unwrap().join()

print(seen)
print(current.id)
```

```output
12
1
```

The worker starts at main's `Vm`, repoints its own copy at a `Vm` of its own, and reads `2` through it;
main's copy was never touched, so it still reads `1`.

**The address of another `@thread_local` is not a value**, being a different address on every thread:

```sysl
@thread_local static var count: int = 0

@thread_local static var counter: *int = &count

print(*counter)
```

```error
'counter' is '@thread_local', so its value has to be one the compiler can write down, and the address of 'count' is not one: 'count' is '@thread_local' too, so its address is a different one on every thread, and no single image in the object file can hold it. The address of ordinary module storage is a value. Leave the value off to start every thread at the type's zero, and do the work in each thread instead
```

### A target with no thread-local storage refuses it by name

A freestanding target has no loader and no libc, so nothing lays a thread's storage down and nothing
writes the thread pointer an offset would be read from:

```sysl target=thumbv7em-freestanding
@thread_local static var n: int = 0

print(n)
```

```error
'n' cannot be '@thread_local' on 'thumbv7em-freestanding': there is no loader and no libc on this target, so nothing lays a thread's storage down and nothing writes the thread pointer the offset would be read from. Declare it as a plain module 'var' and let the port's scheduler answer for which task is running, which is what the ownership runtime's reaper slot does
```

What a bare target uses instead is exactly this: a plain module `var` the port's scheduler answers
for, the same shape the ownership runtime's reaper slot already takes.

### On a bare AArch64 machine the program may own the thread pointer

A kernel is the thing that writes the thread pointer, so on `aarch64-freestanding` the refusal above
becomes a question: which register? The manifest answers it, and from then on `@thread_local` compiles
to the local-exec model against that register:

```hocon
codegen {
  thread_pointer = "tpidr_el1"
}
```

The register is any of `tpidr_el0`, `tpidrro_el0`, `tpidr_el1`, `tpidr_el2` and `tpidr_el3`. Every
function is compiled to read it (clang's `-mtp`), and so is a package's carried C, so a
`_Thread_local` there and a `@thread_local` here find one block.

**What the program then owes is what a loader would have done, and the library does most of it.** The
linker gathers every thread-local into a `.tdata` image (the initial values) and a `.tbss` (the zeros),
and on AArch64 a variable lives at the thread pointer plus 16 plus its offset in that image, the 16
bytes being reserved for the thread's own use. Every bare image's link names that template —
`__sysl_tls_data`, `__sysl_tls_data_end`, `__sysl_tls_end` and `__sysl_tls_align` — so
`sysl.threads.spawn` lays each thread it starts out of it and hands the block's address to the spawn
hook (`library/threads.md § On a target with no C library`), and the program's part is the register: a
kernel writes it on every switch. The first thread is the one the library did not start, so the
program lays its block itself, `sysl.threads.tls_room()` bytes set aside and `tls_place` answering what
the register should hold:

```text
var block: [256]u8 = [0; 256]            // at least tls_room()
val tp = tls_place(block[..]).unwrap()   // copies .tdata, zeroes .tbss
write_sysreg("tpidr_el0", u64(usize(tp)))
```

A thread that never gets a block reads whatever the register points at, as it would through any
pointer the program forgot to set. A register the machine has no use for is refused before anything
is built:

```sysl target=aarch64-freestanding
@thread_local static var n: int = 0

print(n)
```

```error
cannot be '@thread_local' on 'aarch64-freestanding' until the program says which register holds the thread pointer
```

### One file for every machine: `#if thread_local`

A library that runs both where there are threads and on a board — a C library's `errno` is the usual
case — wants one variable that is per-thread wherever the build has thread-local storage and a plain
module `var` everywhere else. The `#if` symbol `thread_local` (`§ #if`) is true exactly where the
attribute is accepted, so the attribute line is gated by it and the declaration is written once:

```sysl
import sysl.threads.*

#if thread_local
@thread_local
#endif
static var errno: int = 0

fail(state: *int)
    errno = 22
    print(errno)

var t = spawn(&fail, null).unwrap()
t.join()

print(errno)
```

```output
22
0
```

On this hosted machine `errno` is one per thread, so the worker's `22` never reaches main. On a board,
and on `aarch64-freestanding` with no `codegen.thread_pointer`, the same file builds with a plain
module `var` the scheduler answers for; on `aarch64-freestanding` with the register named it is a
thread-local again. **`thread_local` is the one `#if` symbol the target alone does not settle**: it
also reads the root manifest's `codegen.thread_pointer`, which is why the register is the root's to
name and why it is read before any condition is.

## `@main_thread` — storage only the main thread reaches

A body that may run on another thread — a closure held as a `&sync Fn`, a named function read into
one — may not reach module storage whose counts are not atomic, since the rest of the program could be
touching the same counts at the same moment:

```sysl
struct Counter
    n: int

static var counter: &Counter = Counter(0)

val bump: &sync Fn() -> unit = () -> counter.n += 1

bump()
```

```error
a closure shared between two domains may be called from either, so every count it reaches has to be atomic — but the module storage 'counter' it reaches is a '&Counter'
```

Some programs know something the compiler cannot see: that a callable typed `&sync` because it *may*
cross is in fact only ever called on the main thread. An interpreter's builtins are the usual case —
the table is shared with worker threads, but the builtins that touch the main machine run on the main
thread alone. `@main_thread` above a module `var` or `val` writes that promise down, and the refusal
above stops:

```sysl
struct Counter
    n: int

@main_thread
static var counter: &Counter = Counter(0)

val bump: &sync Fn() -> unit = () -> counter.n += 1

bump()
bump()
print(counter.n)
```

```output
2
```

**The promise is checked where it can be, at run time.** Each access to `@main_thread` storage from a
body that may run in another domain first asks whether the thread making it is the one the process
began on; where it is not, the program writes `'counter' is @main_thread storage and was reached from
another thread` to standard error and traps, as a failed bounds check does. The question is answered
without anything recorded at start-up, so an archive a C program links answers it too. The program's
own code — the entry file, a function no `&sync` body reaches — is not checked, and reads the storage
exactly as it reads any module `var`. The check is kept in every build, and is there only on a target
with POSIX threads.

It marks one object every thread could otherwise reach, so it is refused above a local (already one
call's), above a `const`, an `extern` or a function (no storage), and beside `@thread_local` or
`@per_cpu` (two answers to who reaches the binding):

```sysl
@main_thread
@thread_local
static var n: int = 0
```

```error
'@main_thread' and '@thread_local' are two answers to who reaches one binding — the main thread alone, or each thread its own copy — so they cannot stand above the same one. '@thread_local' already keeps every copy to one thread; '@main_thread' is for the one object the whole program shares
```

A freestanding target has no threads for one to be the main one:

```sysl target=thumbv7em-freestanding
@main_thread
static var n: int = 0

print(n)
```

```error
'n' cannot be '@main_thread' on 'thumbv7em-freestanding': the promise is that only the program's main thread reaches it, and a freestanding target has no threads for one to be the main one
```

## `@per_cpu` — one copy per processor core

`@per_cpu` gives a module `var` one copy per **core** rather than per thread: the record a scheduler
keeps for each processor, the core's run queue, its tick count. It exists only on `aarch64-freestanding`,
where the program owns a register to find the copy through, and the manifest names that register:

```hocon
codegen {
  per_cpu_offset = "tpidr_el1"
}
```

Every `@per_cpu` binding is laid down in one section, `.percpu`, and that section is the **image** each
core's copy is made from. The register holds this core's copy's address *minus* the image's, so an
access is the binding's address plus the register — read afresh at every access — and a core whose
register is zero is using the image itself. A boot core can therefore use its per-core data before it
has made any copies, provided it zeroes the register first. For the others the program copies the
image once per core and writes each core's offset:

```text
.percpu : { __percpu_start = .; *(.percpu) __percpu_end = .; }
```

```sysl
@per_cpu
var ticks: u64 = 0

@pinned
tick() -> u64
    ticks += 1
    ticks
```

Its initializer has to be one the compiler can write down, as a `@thread_local`'s does, and it stands
beside `@align(n)` and nothing else: the section is the storage class's own.

### A per-core read is right only where the core cannot change

Which copy `ticks` names depends on the core *running*, so the read is right only while the thread
cannot move: a thread preempted between finding its core's copy and using it resumes on another core
holding the first core's. Nothing in a function's text says whether interrupts are masked, so the
function says it, in one of two words:

- **`@pinned`** — every caller already keeps this function on one core: it runs in an interrupt
  handler, or under a lock that masks interrupts. That is a precondition, so **a call of a `@pinned`
  function is held to the same rule as a per-core read**, and so is taking its address.
- **`@pins`** — the body keeps itself on one core before it gets there (it masks interrupts first,
  and unmasks them after). Its callers are asked nothing.

A `@per_cpu` binding may be named, and a `@pinned` function called, only inside a body marked one or
the other. The words are the program's claim, as an `@export`'s symbol is — the compiler cannot see a
register being written — but they put the claim at the function, where a reader can check it against
the code, and they carry it up the call tree to the place that keeps the core still.

The rule is not about storage, so it runs anywhere a `@pinned` function is written:

```sysl
@pinned
depth() -> int = 3

@pins
probe() -> int
    // The real thing masks interrupts here and restores them after.
    depth()

print(probe())
```

```output
3
```

A closure is a body of its own and carries neither word, since it can be called after the function
that wrote it has let the core go; nor do the entry file's statements.

```sysl
@pinned
depth() -> int = 3

print(depth())
```

```error
'depth' is '@pinned', so every call of it has to be made where the core cannot change, and the program's statements are neither '@pinned' nor '@pins'
```

`@pinned` says the callers hold the core still and `@pins` that the body does, so one function is one
or the other:

```sysl
@pinned
@pins
depth() -> int = 3
```

```error
'@pinned' says every caller already keeps this function on one core, and '@pins' says the function does that itself before it reaches per-core storage — one body is one or the other
```

### What it refuses

Anywhere but a bare AArch64 machine, the operating system moves a thread between cores whenever it
likes, so no answer to "which core" stays true long enough to read through:

```sysl
@per_cpu
static var ticks: u64 = 0

print(ticks)
```

```error
cannot be '@per_cpu' on
```

On `aarch64-freestanding`, until the manifest names the register:

```sysl target=aarch64-freestanding
@per_cpu
static var ticks: u64 = 0

@pins
tick() -> u64 = ticks

print(tick())
```

```error
cannot be '@per_cpu' until the program says which register holds this core's offset
```

A copy per thread and a copy per core are two answers to one question:

```sysl
@per_cpu
@thread_local
static var ticks: u64 = 0
```

```error
'@thread_local' and '@per_cpu' are two answers to how many copies one 'var' has
```

A section would move the binding out of the image the copies are taken from:

```sysl
@per_cpu
@section(".data")
static var ticks: u64 = 0
```

```error
'@per_cpu' places the binding in '.percpu', the image every core's copy is made from
```

A `val` never changes, so the one copy it has is already every core's:

```sysl
@per_cpu
static val ticks: u64 = 0
```

```error
a per-core constant is a constant
```

A local is in one call's frame, which no other core can see:

```sysl
count() -> u64
    @per_cpu
    var n: u64 = 0
    n

print(count())
```

```error
'n' is a local, and a local is already one call's
```

## `@domain(interrupt)` — a function entered on top of whatever was running

An [`interrupt`](/reference/ffi/) handler is a function the processor enters directly, in the frame
it pushed. Most kernels do not write one: a **vector table in assembly** saves the interrupted
registers itself and then calls an ordinary function, exactly as C would. That function is
`@export`ed, and an export is how C enters sysl at all — almost always on the program's one thread —
so nothing about it says a second domain exists.

**`@domain(interrupt)` says so.** It changes nothing about how the function is called; it makes the
function a root of the interrupt domain, held to what an `interrupt` handler is held to: every count
it reaches, however many calls down, has to be atomic
([memory § Module storage reached from another domain](/reference/memory/#module-storage-reached-from-another-domain)).

```sysl build=c target=aarch64-freestanding
import sysl.sync.Atomic

var done: Atomic[u32] = Atomic(0)

@domain(interrupt)
@export("disk_irq")
disk_irq()
    done.store(1)

@export("disk_wait")
disk_wait()
    while done.load() == 0 do ()
```

The handler and the program share one word, and every touch of it is one atomic instruction. A
handler that reaches a counted reference instead is refused at the handler, naming what it reached:

```sysl build=c target=aarch64-freestanding
struct Request
    sector: u64

var pending: Option[&Request] = None

@domain(interrupt)
@export("disk_irq")
disk_irq()
    pending = None
```

```error
'disk_irq' is entered in the 'interrupt' domain, on top of whatever was running, so every count it reaches has to be atomic — but the module storage 'pending' it reaches holds a '&Request' in its 'Some(value)', whose count is not. Hold it as a '&sync Request' ('06')
```

A **waker** is the case a kernel meets first, and it has no atomic spelling at all — see
[async § A task that parks stays in its domain](/reference/async/#a-task-that-parks-stays-in-its-domain).

### It names one domain, and `interrupt` is the one there is

```sysl
@domain(thread)
worker() = ()
```

```error
'thread' is not a domain a function is entered in — 'interrupt' is the one there is
```

A function the program calls itself is in the program's own domain and says so by not writing the
annotation, so there is no empty form, and a function the processor may enter in either of two is
entered in the stricter, so there is no list.

### It is not `async`

Whatever enters the function calls it directly, and calling an `async` function only makes its task:

```sysl
@domain(interrupt)
async on_irq() = ()
```

```error
a function entered in the 'interrupt' domain cannot be 'async': whatever enters it calls it directly, and calling an async function only makes its task, which nothing there can drive
```

## `#if` — gating lines before the lexer

Everything a target decides is a fact the *compiler* reads about the machine. `#if` is the one place
a **program** reads one, and it exists because machines genuinely differ in ways a library cannot
paper over: a syscall number, a struct a header lays out two ways, a symbol one libc exports and the
other does not.

```sysl
#if posix
line_ending() -> string = "\n"
#else
line_ending() -> string = "\r\n"
#endif

print("a posix machine", line_ending().len)
```

```output
a posix machine 1
```

**What differs between the branches is the implementation, not the answer.** That is the shape most
uses of `#if` have: a syscall number, a struct a header lays out two ways, a symbol one libc exports
and the other does not — chosen per machine so that everything above the choice can stop caring.

`#elif` chains, and `#else` catches what nothing named:

```sysl
#if aarch64
machine() -> string = "aarch64"
#elif x86_64
machine() -> string = "x86_64"
#elif riscv64
machine() -> string = "riscv64"
#else
machine() -> string = "something else"
#endif
```

That one is quoted rather than run, because what it prints is *supposed* to depend on the machine —
which is the whole point of the construct and exactly what a page cannot pin to one answer.

`#if` / `#elif` / `#else` / `#endif`, nesting freely, and **the branches are exclusive** — the first
whose condition holds is the one that contributes, and a group inside a branch that was not taken
contributes nothing however its own condition reads.

### It gates lines, and it gates them before anything is parsed

**A line in a branch this build is not for is replaced by an empty line, not removed**, and so is
every directive line. After the pass the file is an ordinary sysl file that happens to have some
blank lines in it, and nothing downstream knows any of this happened.

Replaced rather than removed, because **every line below a gate has to keep the number it was written
at.** Deleting them would leave the messages right and the carets somewhere else, with nothing to say
so:

```sysl
#if linux
say() -> string = "one"
extra() -> int = 1
another() -> int = 2
#endif

print(missing_name)
```

```error
:7:7
```

The caret is on line 7, which is where `print(missing_name)` is written — not line 2, where it would
be if the four gated lines had been dropped.

**A directive sits at the margin, column 1.** That is a rule and not a convention. sysl is
indentation-sensitive, and indentation is how the language reads block structure — so a gate written
*in* that channel would look like it takes part in a nesting it has nothing to do with, when in fact
the line is gone before anything counts a column. At the margin it is visibly not part of the code's
shape, which is what it is. It is also how C is written. What keeps a declaration's `@test` from ever
being mistaken for one of these is the **sigil**, not the margin — an annotation on a `module` header
sits at column 1 too.

**Why lines and not a construct wrapping declarations.** Rust spells this `#[cfg]`, an attribute on
an item, and can because Rust is brace-delimited: the attribute attaches without moving anything.
Here the equivalent would have to take an indented block, so adding or removing a platform gate would
reindent everything inside it — a one-line intent showing up as a whole-body diff. A flat marker
disturbs nothing.

### The symbols are derived from the target, and the set is closed

| kind | symbols |
|---|---|
| operating system | `macos`, `linux`, `windows`, `freestanding`, `android`, `wasi` |
| processor | `aarch64`, `x86_64`, `riscv64`, `riscv32`, `thumb`, `x86`, `wasm32`, `craft` |
| derived | `hosted` (not `freestanding`), `posix` (`macos`, `linux` or `android`), `bsd` (`macos`) |
| floating point | `fpu` (the machine has floating-point registers), `fpu_dp` (they hold a double), `hardfloat` / `softfp` (the float ABI), `fma` (a fused multiply-add is one instruction at `f32`), `fma_dp` (and at `f64`) |
| storage | `thread_local` (a `@thread_local` is accepted on this build) |
| address width | `ptr16` (an address is 16 bits), `ptr32` (it is 32 bits), `ptr64` (it is 64 bits) |
| instrumentation | `sanitize_address` (the build is instrumented for AddressSanitizer), `sanitize_thread` (for ThreadSanitizer) |

That is the whole vocabulary. There is **no `#define`** and nothing a project can add, and only one
symbol depends on a project config: `thread_local`, which also reads the root manifest's
`codegen.thread_pointer` (below). The two instrumentation symbols are facts of the build rather
than of the machine, and read `SYSL_EXTRA_CFLAGS` (below). A condition is a symbol, `!`, `&&`, `||`, and parentheses; `&&` binds tighter
than `||`.

`posix` is a name for the commonest disjunction rather than a replacement for writing it — `#if linux
|| macos` still says the same thing, and since `android` joined it is the shorter way to say all
three. **`android` is its own symbol and is not `linux`**, though there is a Linux kernel under it:
what a source file gating on a system is really asking about is the libc and the libraries, and
Bionic is neither glibc's set of `-l` names nor its headers. Code that wants both writes `#if linux
|| android`. Note that this `posix` is not the *capability* of the same name:
this one asks **is this a POSIX system**, a fact about the machine settled by the target, where the
capability asks **may this module use POSIX**, a permission a project grants and a `no posix` clause
takes away. They agree today only because nothing denies anything yet.

**`bsd` is narrower than `posix` and is about the libc rather than the machine.** A BSD and a glibc
system are both POSIX, and yet they disagree about a handful of things a source file has to get
right: `errno` is reached through `__error` on one and `__errno_location` on the other, `ENOTEMPTY`
is 66 against 39, `ENAMETOOLONG` is 63 against 36, and `mode_t` is sixteen bits against thirty-two.
Every one of those is a *libc* fact with nothing about any particular system in it, which is why the
standard library's own gates for them say `bsd` and not `macos`.

**`fma` and `fma_dp` are C's `FP_FAST_FMAF` and `FP_FAST_FMA`**: whether `llvm.fma`
(`reference/ffi.md`) is a single instruction at that width, so that a fused multiply-add is no slower
than a multiply and an add. They are facts about the unit, not the calling convention: AArch64 and a
hosted RISC-V machine have both, every Arm unit sysl targets has `fma`, and only the Cortex-M7's
double-precision one (`thumbv7em-freestanding-dp`) has `fma_dp` as well. The x86 rows ask for no
processor past the baseline, which has no FMA, and WebAssembly has none. Where a symbol is false the
operation is still exact — it is a call rather than an instruction. The same two symbols say where a
written `a * b + c` is contracted into one rounding
([expressions](/reference/expressions/#a-multiply-feeding-an-add-is-fused-where-the-machine-fuses)).

**`thread_local` is true exactly where a `@thread_local` would be accepted** (`§ @thread_local`): on
every hosted machine, and on bare AArch64 when the root manifest says which register holds the thread
pointer (`codegen { thread_pointer = "tpidr_el0" }`). It is false on a board and on bare AArch64 with
no such setting. It is the one symbol the target alone does not settle, because the register is the
program's to name; the compiler reads it before the first condition, a dependency's `if` included.

That distinction is worth making before it is needed rather than after. macOS is the only BSD sysl
targets today, so `#if macos` and `#if bsd` select identically — and a second BSD would route into
the glibc branch at every one of those sites, where exactly one of them says anything: the `errno`
accessor fails at the link, while a wrong `ENOTEMPTY` merely stops matching and a `mode_t` at the
wrong width appears to work. **A symbol that means what it says costs nothing today and is the whole
of the fix later.**

**The floating-point four are facts of the whole target, not of the processor.** Seven Arm targets
are all `thumb`, and they differ in exactly this: a Cortex-M0+ has no floating-point registers, a
Cortex-M4F has single-precision ones, a Cortex-M7 double-precision ones, and a core with a unit may
still be built to pass a float in an integer register. Code that *saves* those registers — a
`setjmp`, a context switch — has to know, because an instruction naming `s16` faults on a core with
none:

| target | `fpu` | `fpu_dp` | `hardfloat` | `softfp` |
|---|---|---|---|---|
| every hosted target, `aarch64-freestanding`, `x86_64-freestanding`, both `wasm32` | yes | yes | yes | no |
| `thumb-freestanding`, `thumbv7em-freestanding` | yes | no | yes | no |
| `thumb-freestanding-softfp` | yes | no | no | yes |
| `thumbv7em-freestanding-dp` | yes | yes | yes | no |
| `thumbv6m-`, `thumbv7m-`, `thumb-freestanding-soft`, `thumbv7em-freestanding-soft`, both RISC-V freestanding, `craft-freestanding` | no | no | no | no |

`fpu` asks about the **registers**, whichever ABI passes a value, so a soft-float-ABI build of a core
that has them still answers yes — the registers are there and a context switch must save them. A
machine with registers is exactly one of `hardfloat` and `softfp`; one without is neither, and `!fpu`
is how a condition says *soft float throughout*. Every hosted machine answers yes, which is what this
host says:

```sysl
#if fpu && fpu_dp && hardfloat
print("floating-point registers, doubles in them, and floats passed in them")
#else
print("something else")
#endif
```

```output
floating-point registers, doubles in them, and floats passed in them
```

Inside an `asm` statement the arm is still chosen by processor, and what differs between the cores
of one processor is gated by `#if` inside its arm — a `setjmp` saving the callee-saved single
registers only where there are any:

```sysl
save_fp(at: usize)
    asm
        [thumb]
#if fpu
            "vstmia {at}, {{s16-s31}}"
            in at : reg
#endif
        [x86_64, aarch64, riscv64, riscv32, craft, wasm32] unavailable "not this example"
```

**`ptr16`, `ptr32` and `ptr64` say how wide an address is** — the width of `usize` and `isize` —
and every target answers exactly one. `ptr16` is true on `craft`, `ptr32` on `thumb`, `riscv32`,
`wasm32` and `x86`, and `ptr64` on `aarch64`, `x86_64` and `riscv64`. They are for a type defined by its
relation to the machine's word rather than by one processor: a Forth double cell is twice the width
of a cell, so it is an `i64` where a cell is 32 bits and an `i128` where it is 64, and one condition
says so for every machine at once. This host's addresses are 64 bits:

```sysl
#if ptr32
type DCell = i64
#else
type DCell = i128
#endif

@assert(sizeof(DCell) == 2 * sizeof(isize), "a double cell is two cells")

print(sizeof(DCell))
```

```output
16
```

On a 32-bit board the same lines choose the narrower type, and an `@assert` the build checks says
so:

```sysl build=c target=thumb-freestanding
#if ptr32
type DCell = i64
#else
type DCell = i128
#endif

@assert(sizeof(DCell) == 8, "a double cell on a 32-bit board is eight bytes")

@export("dcell_mul")
dcell_mul(a: isize, b: isize) -> DCell = DCell(a) * DCell(b)
```

and on a 64-bit one the wider:

```sysl build=c target=riscv64-freestanding
#if ptr32
type DCell = i64
#else
type DCell = i128
#endif

@assert(sizeof(DCell) == 16, "a double cell on a 64-bit machine is sixteen bytes")

@export("dcell_mul")
dcell_mul(a: isize, b: isize) -> DCell = DCell(a) * DCell(b)
```

Like `fma`, the three are facts about the machine, so a directory cannot select on them.

**`sanitize_address` and `sanitize_thread` say the build is instrumented** for AddressSanitizer or
ThreadSanitizer. They are read from the same list the compiler marks every function with when
`SYSL_EXTRA_CFLAGS` carries `-fsanitize=address` or `-fsanitize=thread` (one flag may name several,
`-fsanitize=address,undefined`; `-fno-sanitize=` asks for nothing), so a condition and the
instrumentation cannot disagree: on any target, the symbol is true exactly when the code under it is
instrumented. They are for the code that has to behave differently under a sanitizer — a test that
measures the allocator, which ASan replaces, or a lock-free path TSan cannot see the ordering of:

```sysl
#if sanitize_address
say() -> string = "instrumented"
#else
say() -> string = "plain"
#endif

print(say())
```

```output
plain
```

`#if !sanitize_address` and `#elif sanitize_thread` read as every other symbol does. Only those two
are answered: `asan`, `tsan` and `sanitize_memory` are refused, naming them:

```sysl
#if asan
say() -> string = "instrumented"
#endif
```

```error
'asan' is not something a build says about itself — sysl answers sanitize_address (AddressSanitizer) and sanitize_thread (ThreadSanitizer)
```

A dependency's `if` cannot name either, because which packages a resolution takes may not depend on
how one build of them is compiled. Every cache the build keeps is keyed on `SYSL_EXTRA_CFLAGS`, so
an instrumented build never reuses what a plain one gated.

**A symbol nobody knows is an error, not false:**

```sysl
#if darwin
say() -> string = "one"
#else
say() -> string = "two"
#endif

print(say())
```

```error
'darwin' is not something a target says about itself — sysl knows aarch64, android, bsd, craft, fma, fma_dp, fpu, fpu_dp, freestanding, hardfloat, hosted, linux, macos, posix, ptr16, ptr32, ptr64, riscv32, riscv64, sanitize_address, sanitize_thread, softfp, thread_local, thumb, wasi, wasm32, windows, x86, x86_64
```

The set is closed, so a name outside it is a mistake rather than a fact this build happens not to
have — and a misspelling that read as *false* would gate code out of the build with nothing said.
**Silently missing code is the one failure this feature cannot be allowed to have, and it is the one
C has.**

A target's *name* is not a symbol — it has a `-` in it, which no identifier carries — and writing one
is told what to write instead, because otherwise the reader is told that `-` is not an operator,
which is true and no help:

```sysl
#if aarch64-macos
say() -> string = "one"
#else
say() -> string = "two"
#endif

print(say())
```

```error
'aarch64-macos' is a target's name rather than something a condition asks about — a condition asks about one fact of the machine at a time, so this is written 'aarch64 && macos'
```

**Every condition is checked, in the branch being taken and the ones being skipped alike.** So a
misspelling in the Linux half is caught by a macOS build, which is where it would otherwise sit until
somebody built for Linux:

```sysl
#if linux
say() -> string = "one"
#elif nosuchmachine
say() -> string = "two"
#else
say() -> string = "three"
#endif

print(say())
```

```error
'nosuchmachine' is not something a target says about itself
```

That refusal comes from a build whose `#if linux` was already false and whose `#else` is the branch
being taken. The condition on the way past was still read.

### What is given up, and what is not

**The inactive branch is never syntax-checked.** That is the price of gating text rather than trees,
it is C's price too, and a Linux branch can therefore rot while the macOS build stays green. What
finds that is a build for each target — a thing to *run*, not a thing to design around. The
conditions themselves are the part that is checked everywhere, and they are the part where a mistake
would otherwise be silent.

**Inside a text block, a condition sysl can read gates the block's lines, and every other margin
line is the block's text.** A text block carrying C — a header to compile, a template — has its
`#define`, `#if` and `#include` at column 1, and they belong to the string; a text block a program
assembles may also want some of its lines only on some machines, or only with a feature on. The two
are told apart by the condition: an `#if` or `#elif` whose condition reads as sysl's, over the
symbols a condition may name here — the target's and the package's `feature_` ones — gates exactly
as it does in live code, down to its own `#else` and `#endif`. Anything else is text: `#define`,
`#include`, `#ifdef`, `#pragma` and every other word, an `#if` whose condition is not sysl's
(`#if LIMIT > 2`, `#if defined(X)`, `#if NDEBUG`), and that `#if`'s own `#elif`, `#else` and
`#endif`, so a C conditional stays whole with a sysl group around it or inside it.

```sysl
import sysl.text.Search

val header = raw"""
    #include <stddef.h>
#if posix
    #include <unistd.h>
#else
    #include <windows.h>
#endif
    """

print(header.contains("<unistd.h>"), header.contains("<windows.h>"), header.starts_with("#include"))
```

```output
true false true
```

The directive lines go as they do everywhere, leaving blank lines, and the block's incidental
indentation is reckoned from what is left — the gated text, not the text as written. A condition
over a C macro is the block's text whatever else is around it:

```sysl
import sysl.text.Search

val header = raw"""
#define LIMIT 4
#if LIMIT > 2
#include <stdio.h>
#endif
"""

#if posix
val kind = "posix"
#else
val kind = "other"
#endif

print(kind, header.contains("#if LIMIT > 2\n#include <stdio.h>\n#endif"))
```

```output
posix true
```

**`#if posix` in a block of C gates**, since `posix` is a symbol sysl knows; a C header that means
its own macro of that name writes it as `#ifdef posix`, which is text. A `feature_` name the manifest
does not declare is false, as it is in live code, so a misspelled one gates its lines out rather than
passing into the text. The same holds for `option_<name>`, the symbol of a `bool` option of the
package that is `true` (a feature's option is `option_<feature>_<name>`): in a package's files
`#if option_trace` gates exactly as `#if feature_trace` does. **A group opened inside a text block closes inside it**: one whose `#endif`
is below the block's closing delimiter is refused at its `#if`, naming the line the block ends on.

```sysl
val s = raw"""
#if posix
    on a posix machine
    """
#endif
```

```error
this '#if' opens a group inside the text block that ends on line 4, and the group has to close inside the block — add its '#endif' before line 4
```

**A block comment is all text.** Nothing reads a comment's lines, so there is nothing for a condition
in one to gate, and a C fragment quoted in a comment stays as written. The gate asks the lexer itself
where a literal opened in live code ends, and which kind it is, with the same scans a token is read
with, so there is one set of rules about strings and comments rather than two that could drift. A
branch not taken is not read this way, since it need not lex at all — a text block that opens inside
one does not hide the `#endif` that closes it.

```sysl
/*
#if windows
The quoted condition is the comment's, and so is the '#endif' below it.
#endif
*/
print("a comment's lines are its own")
```

```output
a comment's lines are its own
```

### The library is subject to it too

`library/sysl` is sysl source, so it may gate on the machine like any other — which makes "the standard
module" a question with a target in it, and the library's source is parsed once per target
accordingly.

The one thing held fixed is that **a name the compiler spells for itself is declared on every
target**. A library that gated `Option` away for Windows would be a library nothing compiles against
there, so that is refused in a registry-wide check rather than at the first `?` somebody writes.

And **an artifact records the target it was built for** and is refused by a build for another,
because the trees a library ships are now a per-target answer.

## What is deliberately absent

| absent | why |
|---|---|
| a general annotation mechanism | the set is closed: `@test`, `@tailrec`, `@pure`, `@ghost`, `@export`, `@reads(...)`, `@writes(...)`, `@crossing(...)`, `@noinline`, `@inline`, `@cold`, `@disjoint(...)`, `@setup`, `@teardown`, `@setup_all` and `@teardown_all` on a free function, `@packed`, `@align(n)` and `@export("...")` on a struct, `@export("...")` on a module binding, `@section("...")` on a binding or a function, `@section("...")` and `@align(n)` on an `asm` block at the top of a file, `@no_<capability>`, `@requires`, `@link`, `@include` and `@tests` on a file, and `@assert` on nothing at all. Each was designed and added on its own evidence; there is no way to write one the compiler does not already know |
| `@naked`, a function with no frame | code that must run before there is a stack — a reset entry, a vector table — is an [`asm` block at the top of a file](/reference/inline-assembly/), which is not a function and so has no frame to leave out |
| bitfield syntax | there is nothing to write: inside `@packed` an `iN` field already occupies exactly N bits, so a five-bit register field is `u5` and needs no `: 5` beside it. The open integer family does the work C's declarator syntax was invented for |
| `#define`, or any project-supplied symbol | the `#if` vocabulary is derived from the target and closed, which is what makes an unknown symbol an error rather than a false |
| a `#if` that asks about a capability | a condition asks what the *target* says; what a project permits is a different question, left with the config that would define it |
| a test framework in the library | a test asserts in the language it is testing, and passes by returning |
| a release mode that drops a check | see [errors and contracts](/reference/errors/) — every check is in every build |

---

That is the language. What ships beside it is the [standard library](/library/), which is a section
of its own — nothing in it is a language feature, and every type in it is one a program could have
written.
