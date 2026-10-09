---
title: Attributes, annotations, and compile time
summary: `::` attributes a type answers, the fifteen annotations a function takes, the three that lay out or place what they mark, the five a file's header takes, `@assert` which stands on its own, and the `#if` directive that gates lines before the lexer sees them.
weight: 130
---

Three kinds of form in sysl reach into the *compilation* rather than into the running program. Each
has a name and a spelling of its own:

| written | is | read by |
|---|---|---|
| `T::Attr` | an **attribute** — a question a type's own name answers | the analyzer, at the use |
| `@test`, `@tailrec`, `@pure`, `@ghost`, `@export`, `@reads`, `@writes`, `@crossing`, `@noinline`, `@inline`, `@cold` | an **annotation** — a fact about the free function under it | the grammar |
| `@setup`, `@teardown`, `@setup_all`, `@teardown_all` | an **annotation** — a hook `sysl test` runs around a module's tests | the grammar |
| `@borrows` | an **annotation** on a trait's method — see [`@borrows`](/reference/traits/#a-method-may-promise-to-borrow) | the grammar |
| `@needs(...)` | an **annotation** — the capabilities reaching the declaration under it requires; the one an `extern` takes | the grammar |
| `@packed`, `@align(n)`, `@section("...")` | an **annotation** — where the declaration under it is laid out, or where it lands | the grammar |
| `@export("...")` on a `struct` | an **annotation** — the name the type carries in a generated C header | the grammar |
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

**On a function** there are fifteen, each written on its own line above the declaration. More than
one may be stacked, and writing the same one twice is refused. `@test` and `@tailrec` are below, and
so are the four hooks a module's tests may declare — `@setup`, `@teardown`, `@setup_all` and
`@teardown_all` — and the three that tell the optimizer what a definition is for, `@noinline`,
`@inline` and `@cold`; `@pure`,
`@ghost`, `@reads` and `@writes` belong to the specification vocabulary and are on the
[verification](/reference/verification/) page; `@export` makes the definition C-callable and is on
the [FFI](/reference/ffi/) page, beside the `extern` it is the mirror image of; `@crossing(...)` says
a parameter hands a value to another concurrency domain and is on the
[memory](/reference/memory/) page, beside the crossing rule it asks.

**On a type, or on storage** there are four, and three of them are about *where* rather than about
what the declaration does. `@packed` and `@align(n)` lay out a struct — no interior padding, and the
boundary the aggregate begins on — and `@align(n)` marks one binding's storage as well.
`@section("...")` marks a binding **or** a function, and says which linker section it lands in. All
three are below. The fourth is `@export`, which on a struct names the type in a generated C header
rather than placing it, and is on the [FFI](/reference/ffi/) page with the other half of itself.

**On the file** there are five, in its header directly below `module` and before everything else:
`@no_alloc` and its siblings, `@requires(...)`, `@link("...")`, `@include("...")`, and `@tests`. The
first two say what the whole module may do; `@link` says what its `extern`s need at the linker and
`@include` what its `c const` and `c type` blocks need at the C compiler; the last says it is scaffolding
for the module's tests. All five attach to the file rather than to any declaration in it — and
writing one further down is refused with a message saying where it belongs. The first four are
covered under [modules](/reference/modules/) and [FFI](/reference/ffi/), where what they *mean* is;
`@tests` is below.

**On nothing at all** there is one: `@assert`, which stands where a declaration stands and describes
only itself. It attaches to nothing, declares no name, and nothing can refer to one — two saying the
same thing are two checks rather than a duplicate. It is below.

**On a member, four: the ones that are about a PARAMETER.** `@crossing`, `@borrows`, `@reads` and
`@writes` each name parameters, and a method, a property or an associated function has parameters
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

Everything else is refused, and the line is what the annotation is **about** rather than where it is
written. "A function" in the list above means a *free* function: what `sysl test` calls, what
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

## `@test` — a function with a caller nothing else has

A word after `@` that is neither `test` nor `tailrec` is answered by name rather than as grammar. The
set is closed, which is what makes a misspelling an error instead of a marker that quietly does
nothing — annotations are deliberately *not* a general mechanism yet.

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

Five forms of the annotation:

| written | means |
|---|---|
| `@test` | an ordinary test, named after the function |
| `@test("a sentence")` | named by the sentence instead |
| `@test(should_trap)` | the test **passes** by stopping the program |
| `@test(should_trap: "past the end")` | …and the run must have printed that text |
| `@test(ignore: "why")` | the test is compiled and checked, and **not run** |

They compose, in the order the table gives them — `@test("a sentence", should_trap: "past the end",
ignore: "why")` is one annotation.

**Five, and `@test()` is not a sixth.** An empty argument list would mean what bare `@test` means, and
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

**A test is an ordinary function with a caller nothing else has**: no parameters, no result, not
generic. All three are the same requirement from different sides, since the runner calls it with
nothing and reads the answer off whether it returned. They are checked **at the annotation**, because
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
a '@test' function returns nothing, and 'gives_back' returns 'int' — a test's result is whether it came back, so there is nothing to read a value with
```

### A test passes by returning

That is the whole protocol, and it is what lets a test assert **in the language it is testing**
rather than in a framework. A broken `require`, a bounds violation, an `unwrap` of a `None` — each
ends the process, and none of them had to know it was running under a test.

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

`sysl run`, `sysl build` and `sysl emit-llvm` drop the tests — and drop them **after** analysis. So a
`@test` that does not compile is an error in a build that would never have run it, and the module's
capability rules reach its tests like they reach any other member — with the one difference that a
[`@tests` file states its own](/reference/modules/#a-tests-file-states-its-own-capabilities), since
that file is what these builds are dropping.

That ordering is what lets a test sit beside what it tests: a program's tests do not run when it
runs, and they do not stop being checked.

A helper only a test calls leaves with it, because it becomes unreachable and pruning notices; a
helper the program also calls stays, because the program still calls it.

What a test build keeps regardless is the four definitions **nothing in the program names**: an
interrupt handler, an [`@export`](/reference/ffi/), a `@section` definition (below), and a
[destructor](/reference/memory/). Each is entered from somewhere no walk over the
program can see — the processor, a caller outside this compilation, a linker script gathering a named
section, and the release hook generated from a payload type — so each is a root wherever the walk
begins. A test build swaps the roots, putting the tests where the entry point was, and these four
are roots there too. They have to be: leaving them out would not make them reachable from the tests
instead, it would make them reachable from nothing.

That matters most to a package, whose only build is its own suite. A test build dropping a destructor
could not link at all, the release hook being left to call a symbol nothing defined; one dropping an
export would link, pass, and quietly go without the surface the package exists to provide.

**`sysl build-lib` is the exception, and drops them *before* analysis.** An artifact is the one
output that outlives the compilation that made it, and analysis is not a passive reading: a test
naming `Buf[int]` **creates** the whole of `Buf` at `int`, and that instantiation is an ordinary
library function afterwards, with nothing in it recording which declaration asked for it. Dropping
the test from the analyzed program would therefore drop the test and keep everything it caused —
shipping instantiations no caller of the library ever asked for, and making the artifact's contents
a fact about its tests.

**The line falls between parsing and analysis.** Every source is parsed before the drop, so a
**syntax** error in a `@tests` file still stops a `build-lib`. What such a file no longer gets is
everything after the parse — name resolution, types, visibility, capabilities, the `@test`
well-formedness rules above, generic instantiation, and the duplicate-`@export` check. So a library
test that is well-formed text and wrong in every other way builds clean, and its real errors are
reported by [`sysl test --std`](/getting-started/cli/), which is where a library's tests are run.

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

**It stops at the package boundary.** A package is compiled from source and a library arrives as an
artifact whose test files were never encoded, so a rule that let the reference cross would compile
against one and fail against the other. A test-support library *meant* to be imported is therefore
ordinary code that ships, and is a different thing from a file of scaffolding inside a package.

### The runner

```bash
sysl test <path>
sysl test <path> --filter <text>
sysl test <path> --fail-fast
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
it. The compile is the slow half, and there is only ever one of it.

Exit status is 0 if and only if every test that ran passed. A tree with no tests, and a filter that
matched none of the tests there are, both exit 0 and say which happened.

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
`offsetof`, and the arithmetic and comparisons over them, including a constant declared *below* it. A true one emits
nothing at all. A false one stops the compilation, quoting the message:

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

**A failed comparison also says what each side came out as.** The compiler folded both in order to
decide the condition, so it has the numbers at the moment it reports that one of them is wrong, and
withholding them would leave you editing the literal and rebuilding to find out. A side you wrote as
a literal is not repeated back — it is on the line above the message — so `sizeof(FRect) == 16`
reports only the left; where both sides are computed, both are named. A condition that is not a
comparison has nothing to add, since the thing that came out `false` is its only operand.

**It is not `require`.** A `require` is a runtime precondition: it is compiled, it branches, and it
traps when the program reaches it. This is settled while compiling and reaches the binary as nothing.
A condition it cannot settle is refused rather than deferred — and a call is the line, since a call
in a constant expression would be a request for compile-time evaluation of arbitrary code:

```sysl
f() -> int = 3

@assert(f() == 3)

print(1)
```

```error
has to be a constant expression
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

## `@tailrec` — an assertion that the frame is reused

A function whose last act is a call to itself compiles to a branch back to its own entry rather than
a second frame, so the recursion is bounded by the arithmetic and not by the stack. That happens
whether or not anything is written — see
[tail calls](/reference/declarations/#tail-calls) for what counts as the last act, and for the two
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

**It is a hint, not an instruction.** The inliner is told to spend more and then decides as it
decides; nothing here forces a call to disappear, and a definition that is enormous is still a
definition. That is deliberate — an instruction would make a library's opinion about one member
override a caller's whole code size.

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

### None of the three takes an argument

Whether a call stays a call is the whole of what the first says, `@inline` raises a budget rather
than naming one, and how rare a rare path is is not a number a program has:

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
'@inline' takes no arguments
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

If what you meant was *keep it out of the hot path*, that is `@cold`, and it stands beside either of
them.

They stand beside everything else. `@tailrec` is about this function's call to *itself* becoming a
jump and `@noinline` about the call into it from elsewhere staying a call — two different calls, so
there is nothing for them to disagree about; and `@test` only says who calls the function.

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
protocol rather than to the CPU, and stays with
[`sysl.encoding.binary`](/library/encoding/)'s `get_u16_le` and the rest.

**Every field has to be an integer.** A `bool` is not one here — its storage is a byte and its
representation a bit — and neither is a pointer, a float or an array:

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

**Nesting is the composition path**, and it costs nothing: a bitfield struct is a leaf, and an
ordinary `@packed` struct lays one out as a field of its size.

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

**A bitfield may be `volatile`, and it means a volatile access of the container.** Reading a field is
one volatile load of the whole container; writing one is a volatile load and a volatile store of it —
what C does with `volatile unsigned x : 3`, and what lets a `@packed` struct describe a hardware
register rather than only a data layout:

```sysl
@packed
struct Reg
    enable: volatile u1
    mode: volatile u3
    prescale: volatile u4

var block: [4]u8 = [0u8; 4]
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
@packed
struct Ctrl
    a: u3
    b: u5

var p: *volatile Ctrl = ptr_cast(4096usize)

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

var raw: u64 = u64(0x2a) << 26
val e: *Esr = ptr_cast(&raw)

print(describe(Ec.try(e.ec)))

raw = u64(0x3c) << 26
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
integer on every target.

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
**A value on the heap is the exception.** A box (`&T`) or a buffer takes its storage from the
allocating pair, which promises only what `malloc` does — sixteen bytes — so a boxed value of a type
aligned beyond that is not guaranteed its boundary, and the compiler claims no more than sixteen
for any access through an address that might be one.

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

The initializer has to be a value the compiler can write down — a literal, `null`, a `const`, or an
array or struct built from those — or left off, which starts every thread at the type's zero. That is
the one rule the attribute adds, and it follows from what a per-thread copy is: the copies are made
from a single image in the object file, because a thread started halfway through a run never passes
through the prologue an ordinary module `var` is filled by. A call as an initializer would fill exactly
one copy and leave every other thread reading zeroes, so it is refused where it is written.

It marks a `var` and nothing else. A `val` and a `const` never change, so the one copy each already has
is every thread's.

```sysl
import sysl.posix.threads.*

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
next() -> int = 42

@thread_local static var n: int = next()

print(n)
```

```error
'n' is '@thread_local', so its value has to be one the compiler can write down: every thread gets a copy of the initial value, and the copies are made from a single image in the object file rather than by code that runs per thread. A literal, 'null', a 'const' and the arrays and structs built from them are all values; a call is not. Leave the value off to start every thread at the type's zero, and do the work in each thread instead
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

That is the whole vocabulary. There is **no `#define`**, nothing a project can add, and no dependence
on a project config. A condition is a symbol, `!`, `&&`, `||`, and parentheses; `&&` binds tighter
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

That distinction is worth making before it is needed rather than after. macOS is the only BSD sysl
targets today, so `#if macos` and `#if bsd` select identically — and a second BSD would route into
the glibc branch at every one of those sites, where exactly one of them says anything: the `errno`
accessor fails at the link, while a wrong `ENOTEMPTY` merely stops matching and a `mode_t` at the
wrong width appears to work. **A symbol that means what it says costs nothing today and is the whole
of the fix later.**

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
'darwin' is not something a target says about itself — sysl knows aarch64, android, bsd, craft, freestanding, hosted, linux, macos, posix, riscv32, riscv64, thumb, wasi, wasm32, windows, x86, x86_64
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

**The gate runs before anything knows what a string or a comment is**, so a line that begins at the
margin with a directive word is a directive even inside a text block or a block comment. Recognizing
those would mean a second copy of the lexer's rules about literals, in a place where the two could
drift with nothing to notice — a worse defect than this one. The margin rule is what keeps it rare: a
text block written anywhere but the top level is indented in the source, whatever its value turns out
to be.

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
| a general annotation mechanism | the set is closed: `@test`, `@tailrec`, `@pure`, `@ghost`, `@export`, `@reads(...)`, `@writes(...)`, `@crossing(...)`, `@noinline`, `@inline`, `@cold`, `@setup`, `@teardown`, `@setup_all` and `@teardown_all` on a free function, `@packed`, `@align(n)` and `@export("...")` on a struct, `@section("...")` on a binding or a function, `@section("...")` and `@align(n)` on an `asm` block at the top of a file, `@no_<capability>`, `@requires`, `@link`, `@include` and `@tests` on a file, and `@assert` on nothing at all. Each was designed and added on its own evidence; there is no way to write one the compiler does not already know |
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
