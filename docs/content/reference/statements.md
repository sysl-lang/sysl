---
title: Statements and control flow
summary: Blocks, bindings, every loop and branch form, and what each one yields — because in sysl most of them yield something.
weight: 40
---

`if`, `match`, `while`, `loop` and `for` are **expressions**. Each yields a value, so each can
initialize a binding, be a function's body, or be a branch of another. In statement position the
value is unused and the same forms act as ordinary control flow.

This removes the need for C's declare-then-assign pattern (`int label; if (…) label = …; else label
= …;`), and one rule — *the last expression is the value* — applies uniformly to functions,
branches, arms and loops.

## Blocks

A block is an indented run of statements, and its value is its **trailing expression**. There are no
braces and no statement terminator: a newline ends a statement, and indentation opens and closes a
block. See [lexical structure](/reference/lexical/) for the layout rules and how a long expression is
continued across lines.

Where a block's own value is unused, the block has **none** — its type is `unit` whatever the last
line yields — and this propagates inward, into the branches of an `if`, the arms of a `match`, and
the `else` of a loop. Branches that only perform actions therefore do not have to agree on a type:

```sysl
var full = false
var len = 0
var wrapped = true

if wrapped
    full = true
else
    len += 1

print(full, len)
```

```output
true 0
```

Without this rule, `full = true` (a `bool`) and `len += 1` (an `int`) would be branches of
incompatible types. Statement position begins at a statement, at a loop body, and at the body of a
function that returns nothing.

**A block may appear wherever a construct accepts one, including after a binding's `=`** — see
[the value may be an indented block](/reference/declarations/#the-value-may-be-an-indented-block).
The same rules apply: the trailing expression is the value, and what the block binds goes out of
scope with it.

### The statement forms

Everything that can stand as a statement:

| form | |
|---|---|
| an expression | including every control-flow form, since they are expressions |
| `var` / `val` / `const` | a binding — see [declarations](/reference/declarations/) |
| `ref name = place` | a second name for one place — see [memory](/reference/memory/) |
| `a, b = x, y` | a multiple assignment, which is **not** an expression |
| `return`, `break`, `continue` | transfers of control |
| `become f(…)` | `return` with the frame **replaced** rather than added to — see [declarations](/reference/declarations/) |
| `defer stmt` | what to run on the way out of this block |
| `require` / `ensure` | a contract clause — see [errors and contracts](/reference/errors/) |
| `import`, `impl`, and the declarations | a declaration is a statement, so a function may be nested in one |

## `if`

`if cond` with an indented block, `elif` for each further test, and an optional `else`. The inline
form puts `then` between the condition and a one-line body.

```sysl
label(n: int) -> string = if n % 2 == 0 then "even" else "odd"

grade(n: int) -> string
    if n >= 90
        "A"
    elif n >= 80
        "B"
    else
        "C"

print(label(4), label(7), grade(95), grade(85), grade(70))
```

```output
even odd A B C
```

`elif` is shorthand: each one nests into the `else` branch of the one before, so it follows the
same rules as `if`.

**An `if` used for a value needs an `else`.** Without one the missing branch is `unit`, which is a
diagnostic wherever a value is expected. In statement position a missing `else` is ordinary.

An `if` may be closed with `end if`. The marker is always optional and is useful when a block is long
enough that its extent is no longer obvious:

```sysl
var n = 3

if n > 0
    print("positive")
end if
```

```output
positive
```

`end` is a **contextual** word — an ordinary identifier everywhere except immediately before a
construct keyword — so `end` remains usable as a name. The same marker closes `while`, `for`, `loop`
and `match` here, and a struct, an enum and a block-bodied `val` or `var` on
[declarations](/reference/declarations/).

**A marker is checked, so one naming a different construct is refused, and the diagnostic names
both:**

```sysl
var n = 3

while n > 0
    n -= 1
end for
```

```error
'end for' cannot close the 'while' above it — its marker is 'end while'
```

**A marker closes an indented block, so one after a one-line form is refused**, since there is no
block to close. A construct whose *last* part is written on one line also ends on a one-line form,
whatever came before it:

```sysl
var n = 3

if n > 0 then print("positive")
end if
```

```error
'end if' has no block to close — the 'if' above it ends on a one-line form, which takes no marker
```

**Where two constructs open on one line, their blocks close together, and the marker names the outer
one.** The `match` below ends the `if`'s body as well as its own arms, so `end if` closes both:

```sysl
report(wide: bool, n: int)
    if wide then n match
        0 -> print("none")
        _ -> print("some")
    end if

report(true, 0)
report(true, 5)
```

```output
none
some
```

A `match` takes `end match` under its arms in the same way:

```sysl
tier(n: int) -> string
    val name = n match
        0 -> "bronze"
        1 -> "silver"
        _ -> "gold"
    end match

    name

print(tier(1))
```

```output
silver
```

## `match`

`value match`, then an indented list of `pattern -> body` arms. The keyword goes **after** the value,
as in Scala: a match transforms the value to its left, so matches can be chained.

```sysl
enum Tier
    Bronze
    Silver
    Gold

fee(t: Tier) -> int
    t match
        Bronze -> 0
        Silver -> 10
        Gold   -> 25

print(fee(Bronze), fee(Silver), fee(Gold))
```

```output
0 10 25
```

A match used **for a value must be exhaustive**; one written for effect need not be, following the
same rule as an unused block value. The left side of an arm — the pattern grammar, guards,
alternatives, bindings — is covered on [patterns and matching](/reference/patterns/).

## A branch with no type of its own takes its sibling's

The branches of an `if` and the arms of a `match` must agree on one type; there is no subtyping among
concrete types for them to widen to. A **bare literal** is the exception, because it has no type of
its own. It takes the type of the branch beside it, just as it takes the type of the other operand
in `n + 1` and of the other bound in `0..<xs.len`.

```sysl
width(n: usize) -> usize = n

at_least_one(n: usize) -> usize = width(if n == 0 then 1 else n)
gap(a: usize, b: usize) -> usize = width(if a > b then a - b else 0)

print(at_least_one(0), gap(9, 2), gap(2, 9))
```

```output
1 7 0
```

`width` takes a `usize`, so an `if` whose type came out as `int` would not compile. Without the rule
the `1` and the `0` would default to `int`, each pair would be refused, and the fix would be an
annotation restating the width the other branch already gives.

A `match` applies the same rule across all its arms. The arms with a type of their own determine the
type, and the bare literals take it, wherever they are written; the arm that determines it may be
the last one.

```sysl
width(n: usize) -> usize = n

step(n: usize) -> usize
    val w = n match
        0 -> 1
        1 -> 2
        else n

    width(w)

print(step(0), step(1), step(8))
```

```output
1 2 8
```

An arm or a branch that does not finish has type `never` and does not affect the type of the whole
form. Nor does it stop a sibling literal from taking a type: `if c then 0 else continue` is a `0` of
whatever type the position expects, exactly as it would be with a `1` in place of the `continue`. A
branch ending in `return`, `break`, `continue` or a call of a function declared `-> never` (`panic`,
or one of the program's own) is treated this way.

```sysl
first_even(xs: []usize) -> usize
    for x in xs
        val w = (x % 2) match
            0 -> x
            _ -> if x > 100 then 0 else continue

        return w

    1

print(first_even([3, 5, 8]), first_even([7, 101]), first_even([1]))
```

```output
8 0 1
```

**Two branches that each have a type must still agree.** Neither is converted to match the other:

```sysl
f(a: int, b: usize)
    val w = if a == 0 then a else b

    print(w)

f(0, 1)
```

```error
if branches have different types: int and usize
```

**The rule applies inside a construction, part by part.** A tuple or array literal made only of
literals has no type of its own either, so `(0, 0)` beside a `(u64, u64)` is a pair of `u64`s,
however deeply the tuples nest. A construction with a type for only some of its parts — `(n, 0)`,
`Some(0)` — takes the rest from its sibling, and two such branches that each determine a different
part combine into the type that has both:

```sysl
pair(ok: bool) -> Result[(u64, u64), int] = if ok then Ok((3, 4)) else Err(1)

ends(ok: bool) -> u64
    val (r, w) = pair(ok) match
        Ok(both) -> both
        Err(_) -> (0, 0)

    r + w

joined(n: u8, m: u16) -> u16
    val t = if n < 3 then (n, 1) else (0, m)

    t.0 + t.1

print(ends(true), ends(false), joined(1, 5), joined(7, 5))
```

```output
7 0 2 5
```

The branches of an `elif` chain are all siblings, so they are resolved to one type together rather
than two at a time; the four branches below are four pairs of `usize`:

```sysl
split(n: usize) -> usize
    val (a, b) = if n % 3 == 0 then (n, 0) elif n % 2 == 0 then (0, n) elif n % 3 == 2 then (n, 1) else (n, 2)

    a * 10 + b

print(split(3), split(4), split(5), split(7))
```

```output
30 4 51 72
```

A part that cannot take the sibling's type keeps its own, and the branches are refused as
disagreeing:

```sysl
f(n: u64)
    val t = n match
        1 -> (n, n)
        _ -> (1, "a")

    print(t.0)

f(1)
```

```error
match arms have different types: (ulong, ulong) and (ulong, string)
```

**A branch that cannot be typed on its own is deferred, not refused.** A nullary generic call has no
argument from which to infer its type parameter, so a branch like `empty()` cannot be typed alone;
it is deferred until a sibling has determined the type, in either order, through an `elif` chain or
a `match`'s arms:

```sysl
empty[T]() -> Option[T] = None

pick(c: bool) -> Option[real]
    val v = if c then Some(1.0) else empty()
    v

print(pick(true))
print(pick(false))
```

```output
Some(1)
None
```

Where no branch can be typed alone, the expression is refused as above.

**Branches that each determine a different part of one generic enum's type combine into the whole
type.** `Err(E.Bad)` gives a `Result`'s error type and nothing about its success type; `Ok(n)` gives
the opposite. Neither is complete alone, and together they are one `Result[int, E]`, in either order,
across a `match`'s arms or an `elif` chain:

```sysl
enum E
    Bad

pick(n: int) -> Result[int, E]
    val r = n match
        0 -> Err(E.Bad)
        _ -> Ok(n)

    r

check(n: int) -> Result[int, E]
    val r = if n < 0 then Ok(-n) elif n == 0 then Err(E.Bad) else Ok(n)
    r

print(pick(0).is_err(), pick(4).unwrap(), check(-3).unwrap(), check(0).is_err())
```

```output
true 4 3 true
```

Each type parameter is taken from the first branch that determines it, and a later branch that
determines it differently is refused at its own value. A type parameter that no branch determines is
an error:

```sysl
enum E
    Bad
    Worse

f(c: bool)
    val r = if c then Err(E.Bad) else Err(E.Worse)
    print(r.is_err())

f(true)
```

```error
cannot infer the type argument 'T' of 'Err' here — annotate the expected type
```

## Loops

There are five loop forms, and each is an expression.

| form | when |
|---|---|
| `while cond` | test before each iteration |
| `do … while cond` | test after each iteration, so the body runs at least once |
| `loop` | no test at all; something inside leaves it |
| `for x in seq` | walk a range, an array, a slice, an `Iterate`, or a container through its `Walk` |
| `for init; cond; step` | a stride, a descent, a compound test, several variables |

**`for const` is not a loop.** `for const i in 0..<A.len` is *unrolled* at compile time: the
compiler emits one copy of the body per value and no loop remains at run time, so it takes no label,
no `break` and no `else`. It is for walking a
[type pack](/reference/generics/#a-parameter-may-stand-for-a-list-of-types), where each iteration
has a different type and so must be a separate copy. This page covers the five forms above;
`for const` is documented with generics, together with its range rule and restrictions.

### `for x in`

The ordinary iteration, and the preferred form wherever it fits: it names one thing instead of the
three a counted loop needs.

```sysl
var xs = [4, 5, 6]

for x in xs
    print(x)

for i in 0..<2
    print(i)
```

```output
4
5
6
0
1
```

**A container is walked through `Walk`.** Where `seq`'s type implements the library's
[`Walk`](/library/core/) and is not itself a cursor, the loop is `for x in seq.walk()`: `walk` is
called once, before the first iteration, and its result is walked by the rules above. A `Buf`, whose
`walk` returns its view, is therefore walked by index as a slice is, and a `Map` through its cursor,
one `(key, value)` pair per iteration. A type implementing `Iterate` is walked by `next` even if it
implements `Walk` too.

```sysl
import sysl.buf.{Buf, buf}

var b: Buf[int] = buf()

b.push(4)
b.push(5)

for x in b
    print(x)
```

```output
4
5
```

#### The loop variable may be a pattern

A `for` binds what a [binding](/reference/declarations/#by-pattern-when-the-shape-matters) binds, so
an element that is a tuple or a struct may be taken apart in the header instead of inside the body.
The full pattern grammar applies: nesting to any depth, a part skipped with `_`, a struct named by
its fields, and the whole element kept beside its parts with `n @`.

```sysl
struct Point
    x: int
    y: int

for (name, n) in [("a", 1), ("b", 2)]
    print(name, n)

for ((a, b), c) in [((1, 2), 3)]
    print(a, b, c)

for Point{x, y} in [Point(3, 4)]
    print(x * y)

for whole @ (first, _) in [(5, 6)]
    print(first, whole.1)
```

```output
a 1
b 2
1 2 3
12
5 6
```

The parts are bound the way the loop variable itself is, so a part may be assigned to. The pattern
may **not** choose among an enum's variants, because a loop has no other arm to take when the
element has a different shape; a binding follows the same rule.

The parts go in parentheses. The comma form is not accepted, because `for a, b` already begins a
three-clause header whose first clause assigns to two names, and the two forms could not be told
apart until the `in`:

```sysl
for k, v in [(1, 2)]
    print(k, v)
```

```error
a 'for' names one thing, and a pattern is what takes it apart — write 'for (k, v) in …'
```

### `break` carries a value, and `else` supplies the other one

`break expr` leaves the nearest loop and makes `expr` the loop's value. An optional **`else` block**,
written after the body as in Python, runs when the loop finishes *normally* — the condition turned
false or the sequence ran out, with no `break` — and its trailing expression is the loop's value on
that path.

```sysl
var mixed = [1, 3, 4, 5]
var odds = [1, 3, 5]

var found = for x in mixed
    if x % 2 == 0 then break x
else -1

var none = for x in odds
    if x % 2 == 0 then break x
else -1

print(found, none)
```

```output
4 -1
```

This makes the most common early exit — *find the first element satisfying a predicate* — a single
expression, and the required `else` states what happens when nothing is found. The loop cannot fall
through to an undefined value.

With no `else`, normal completion yields `unit` — so a value-carrying `break` without one is refused:

```sysl
var xs = [1, 2, 3]

var found = for x in xs
    if x == 2 then break x

print(found)
```

```error
this loop breaks with a int but has no 'else' to give a value when it finishes normally
```

Every `break` value and the `else` value share one type, which becomes the loop's. A bare `break`
with no `else` is the ordinary statement loop, of type `unit`.

A literal among them has no type of its own, so it takes the one the others settle, exactly as an
`if`'s literal branch takes its sibling's. Here the `else`'s `255` is a `u8` because `i` is, and the
loop with it; a literal that does not fit that type is refused at the literal.

```sysl
val codes: [4]u8 = [7, 9, 12, 40]

val at = for i in 0u8..<4
    if codes[i] > 10 then break i
else 255

val code: u8 = codes[at] + at
print(at, code)
```

```output
2 14
```

### `do … while`

The test comes at the end, so the body runs at least once. The body is an indented block with the
`while` on the line that closes it, or a single statement on one line.

```sysl
digits(n: int) -> string
    var rest = n
    var s = ""

    do
        s = (rest % 10).to_string() + s
        rest /= 10
    while rest > 0

    s

var i = 0

do i += 1 while i < 4

print(digits(0), digits(4071), i)
```

```output
0 4071 4
```

Written as a `while`, `digits` would print nothing for `0`. The value rules are `while`'s: a `break`
carries the loop's value, and an `else` runs when the test at the end fails.

**`continue` runs the test.** This distinguishes the form from the common substitute, a `loop` with
the inverted test at the bottom:

```
loop
    body
    if !cond then break        // NOT the same loop
```

That loop has no test for a `continue` to reach, so a `continue` skips the exit and the loop never
ends — the same problem the three-clause `for` avoids, where a `continue` would otherwise skip the
step.

The test takes no `is` binding, and cannot read names the body declared; since the body has already
run, a binding in the test would guard nothing.

```sysl
do
    var k = 1
while k < 2
```

```error
undefined name 'k'
```

This `do` is never ambiguous with the `do` that introduces a one-line body. That one always follows
a loop header on the same line, so it is never a statement's first token; a `do` that starts a line
always opens this form.

### `loop`

A loop with no test. It runs until something leaves it, and it replaces `while true`.

```sysl
var n = 0

var stop = loop
    n += 1

    if n == 4 then break n * 10

print(stop, n)
```

```output
40 4
```

Because a `loop` has no condition at all, rather than a constant one, two things follow. It takes
**no `else`** — an `else` runs on normal completion, and this loop never completes normally — so a
value-carrying `break` needs nothing beside it; that is why the program above compiles where the
earlier `for` did not.

And a `loop` **that nothing breaks out of has type `never`**, so it may be the last expression of a
function that returns a value, with nothing after it:

```sysl
serve(handle: () -> unit) -> int
    loop
        handle()
```

`while true` cannot express this: the analyzer does not evaluate a condition, so such a loop appears
able to finish, and the code after it appears reachable.

### The three-clause `for`

C's counted loop without the parentheses, as in Go; no header in the language is parenthesized. Each
of the three clauses may be omitted.

```sysl
var total = 0

for var i = 0; i < 10; i += 1
    if i % 2 == 0 then continue

    total += i

var down = ""

for var i = 3; i > 0; i -= 1
    down += i.to_string()

print(total, down)
```

```output
25 321
```

**The form is not shorthand for a `while`.** `continue` runs the step before testing again, so the
skipped iterations above still advance and the loop terminates. Written as a `while`, the increment
sits at the end of the body, where a `continue` added later skips it.

The second loop shows the other use: a descent, which a range cannot express.

An omitted condition never becomes false, so such a loop ends only through a `break` and takes no
`else`, exactly as `loop` does; write `loop` instead.

### Labels

A `'label` written before the loop keyword names that loop, and `break 'label` / `continue 'label`
then act on it rather than on the nearest enclosing one. This is the only way to leave or restart an
outer loop from inside a nested one.

```sysl
var grid = [[1, 2], [3, 4]]

var hit = 'outer for row in grid
    for x in row
        if x == 3 then break 'outer x
else -1

print(hit)
```

```output
3
```

A labeled `break` carries a value exactly as a bare one does, sharing one type with that loop's other
breaks and its `else`. `continue` takes a label but never a value.

The sigil is a leading apostrophe, as in Rust, because a plain `break outer` would be ambiguous with
`break expr` carrying a value named `outer`. It does not collide with a character literal: `'a'`
closes its quote and is a character; `'a` does not and is a label.

A label is in scope only inside its own loop's body. Naming a loop that does not enclose the
`break`, or reusing a label already in scope, is an error.

## `return`

A function's body yields its trailing expression, so `return` is for leaving **early**. It takes the
same comma list a multi-result signature declares:

```sysl
divmod(a: int, b: int) -> int, int
    if b == 0 then return 0, 0

    a / b, a % b

var q, r = divmod(17, 5)
var z, w = divmod(17, 0)

print(q, r, z, w)
```

```output
3 2 0 0
```

**`become f(…)` is `return f(…)` with the tail call guaranteed.** It differs in meaning, not only in
speed: a chain of tail calls between functions runs in constant stack only if every call is
eliminated, so an ordinary `return` of a call may use a frame and `become` never does. It is
described under [declarations](/reference/declarations/), beside the tail call it generalizes.

## `defer`

Reference counting releases a `&T`, a `string` and a slice's backing storage automatically. It does
not manage other resources: a descriptor from `open`, a `FILE*` from `fopen`, a block from `malloc`,
a lock taken from a mutex. Releasing those by hand at every exit is error-prone, especially since
`?` makes an early exit the normal way to leave a function on failure.

**`defer <statement>` runs that statement on the way out of the block containing it.** The release
is written once, beside the call that acquired the resource, rather than at every exit.

```sysl
work()
    defer print("second")
    defer print("first")

    print("body")

work()
```

```output
body
first
second
```

### What runs, and when

**A deferred statement belongs to its block** — a loop body, a branch arm, or the function body
itself — and runs when control leaves that block by any ordinary route: falling off the end,
`return`, `break`, `continue`, or a `?` taking its failure arm.

**A deferred statement may not contain a jump that leaves it** — a `return`, or a `break` or
`continue` of a loop outside it — at any depth, since it runs while its block is already being left
and there is no second exit to take. A loop written inside the statement is separate, so a jump
within that loop is allowed, as is a jump inside a closure or a nested function written there:

```sysl
first(c: bool) -> int
    defer if c then return 1

    2

print(first(true))
```

```error
a deferred statement runs while its block is being left, so it cannot 'return' — there is no exit left to take
```

```sysl
work()
    defer for i in 0..<5
        if i == 2 then break
        print(i)

    print("body")

work()
```

```output
body
0
1
```

**Several in one block run in reverse order of registration**, as above, so resources are released
in the reverse of the order they were acquired, and a later one may depend on an earlier one's
resource.

**A `defer` runs only if control reached it.** It is a statement, not a declaration: one after an
early `return` is never registered, and neither is one in a branch not taken.

**The whole statement runs at the exit, so every name in it is read there**, not where the `defer`
is written:

```sysl
show()
    var n = 1

    defer print(n)

    n = 2

show()
```

```output
2
```

This differs from Go, which evaluates a deferred call's *arguments* where the `defer` is written and
runs the call later, keeping each argument in a slot until then. sysl follows Zig: reading
everything at the exit needs no storage. `defer fclose(f)` releases the handle bound above it; if a
program rebinds `f` in between, it has changed which file is open, and closing that file is the
intended behaviour.

**A trap runs nothing.** A trap aborts without stack cleanup, including deferred statements: a broken
invariant means the program's state is already wrong, and running cleanup against that state risks
writing corrupt data on the way down. `defer` releases a resource; it does not restore an invariant.
A call that leaves its frame permanently while the program continues — a thread ending — is the
opposite case: a function declared [`@abandons`](/reference/declarations/) runs its caller's
deferred statements before the call.

### Why the block and not the function

Go's `defer` runs at **function** exit. The two are the same in the common case — a resource taken
at the top of a body and released when the body ends, where the function *is* the block — and differ
in a loop:

```sysl
for i in 0..<2
    defer print("close", i)

    print("open", i)
```

```output
open 0
close 0
open 1
close 1
```

Block scope releases each iteration's resource at the end of that iteration, so the program holds
one at a time. Function scope would hold all of them until the function returned, and would need a
per-frame list of pending statements whose length is known only at run time — storage a
freestanding target under `no alloc` has nowhere to put. Block scope needs no run-time state: the
statement is emitted at each edge that leaves the block, as reference-count releases are.

Go's choice follows from features sysl does not have: `recover` works only inside a deferred call
and a panic unwinds one frame at a time, and Go wraps errors by mutating named results on the way
out. sysl has no unwinding and no named results. Zig and Swift, which also have no `recover`, both
scope `defer` to the block.

### Where it sits against the rest of the model

**Leaving a block undoes it in reverse.** Each deferred statement the block reached and each local it
declared is undone in the reverse order of where it was written: a `defer` runs after every local
declared below it is released, and before every local declared above it. A deferred statement can
name only the locals above it, so every one it names is still alive when it runs, including the one
holding the resource being closed. Leaving from the middle unwinds outward: the innermost block is
undone whole, then the enclosing block, up to the function's own.

```sysl
struct Res
    name: string

impl Drop for Res
    drop(self) = print(s"release ${self.name}")

work(fail: bool) -> Result[int, string]
    val a: &Res = Res("a")
    defer print("defer 1")
    val b: &Res = Res("b")
    errdefer print("undo b")
    val c: &Res = Res("c")

    if fail then return Err(a.name + b.name + c.name)

    Ok(1)

print(work(false))
print(work(true))
```

```output
release c
release b
defer 1
release a
Ok(1)
release c
undo b
release b
defer 1
release a
Err(abc)
```

This is the order of Rust's and C++'s destructors and scope guards, and of D's `scope(exit)`. An
`errdefer` takes its written place on a failing way out and is skipped on any other. A temporary is
released at the end of its statement, as everywhere.

**It owns nothing and allocates nothing.** `defer` takes no reference count, creates no box, and adds
no word to any value; a program that does not use it emits nothing for it. It is therefore available
under `no alloc`, where the resources it releases are the only kind there are.

**It is not a [destructor](/reference/memory/).** A destructor belongs to a *type* and runs wherever
a value of that type dies; `defer` belongs to one place in one body and runs for the resource that
body acquired. Each covers what the other cannot: `defer` covers every site a program can name, and
a destructor covers the deaths it cannot — a resource inside a container, or inside a struct inside
a container, dies at a point with no expression in the source.

## `errdefer`

A function that builds something in steps needs the other half of `defer`: when a later step fails,
the steps already taken are undone, and when every step succeeds they are kept. **`errdefer
<statement>` runs that statement on the way out of its block only if the function is failing.**

```sysl
step(ok: bool) -> Result[int, string] = if ok then Ok(1) else Err("step")

build(ok: bool) -> Result[int, string]
    val f = step(true)?
    errdefer print("undo", f)

    step(ok)?
    Ok(f)

print(build(true))
print(build(false))
```

```output
Ok(1)
undo 1
Err(step)
```

### What counts as failing

**A function fails when it returns its failure variant**: `Err` from a function returning a
`Result`, `None` from one returning an `Option`. Every way of returning it counts — a `?` taking its
failure arm, a `return`, or the body's last value — and so does a computed result that holds the
failure, which is told apart by its tag where it leaves:

```sysl
lookup(key: int) -> Option[int]
    errdefer print("no entry for", key)

    if key < 0 then return None

    val found: Option[int] = if key % 2 == 0 then Some(key * 10) else None
    found

print(lookup(4))
print(lookup(3))
```

```output
Some(40)
no entry for 3
None
```

**It belongs to its block, as a `defer` does.** It runs when the function fails while control is
inside that block; leaving the block any other way — its end, a `break`, a `continue` — discards it.
A loop body's `errdefer` therefore undoes only the iteration that failed:

```sysl
check(n: int) -> Result[int, string] = if n < 3 then Ok(n) else Err(s"item $n")

fill(count: int) -> Result[int, string]
    errdefer print("fill failed")

    for i in 0..<count
        print("take", i)
        errdefer print("give back", i)

        check(i)?

    Ok(count)

print(fill(2))
print(fill(5))
```

```output
take 0
take 1
Ok(2)
take 0
take 1
take 2
take 3
give back 3
fill failed
Err(item 3)
```

**The innermost function decides.** An `errdefer` in a closure or a nested function runs when that
body fails, not when the function around it does; an `async` body's runs when its task ends in
failure. In a `main` that returns a `Result`, it runs before the error is reported.

**A trap runs nothing**, as it runs no `defer`, and neither does a call to an
[`@abandons`](/reference/declarations/) function: the frame it leaves did not fail.

### Beside `defer`

**`defer` and `errdefer` share one stack**: in a block holding both, the last written runs first,
whichever kind it is. The undo of a later step therefore runs before the release of an earlier one:

```sysl
step(ok: bool) -> Result[unit, string] = if ok then Ok(()) else Err("declined")

transfer(debit_ok: bool, credit_ok: bool) -> Result[unit, string]
    print("lock")
    defer print("unlock")

    step(debit_ok)?
    errdefer e ->
        print("credit back")
        print(s"transfer rolled back: $e")

    step(credit_ok)?
    Ok(())

print(transfer(true, false))
print(transfer(false, true))
```

```output
lock
credit back
transfer rolled back: declined
unlock
Err(declined)
lock
unlock
Err(declined)
```

When the debit fails, the `errdefer` below it was never reached, so only the `unlock` runs.

**Locals take their place in the same order.** An `errdefer` runs after every local declared below
it is released and before every local declared above it, so what it undoes is still held when it
runs:

```sysl
struct Slot
    name: string

impl Drop for Slot
    drop(self) = print(s"release ${self.name}")

reserve(ok: bool) -> Result[unit, string]
    val first: &Slot = Slot("first")
    errdefer print("undo", first.name)
    val second: &Slot = Slot("second")

    if ok then Ok(()) else Err("full")

print(reserve(true))
print(reserve(false))
```

```output
release second
release first
Ok(())
release second
undo first
release first
Err(full)
```

Every
rule of a deferred statement applies to it unchanged: it may not `return`, `break` or `continue` out
of itself or use `?`, and it reads every name at the exit rather than where it is written.

### Seeing the error

**`errdefer e -> <statement>` binds the error being returned** to `e`, read-only, for the statement
after the arrow — one line, or a block under it, as a `match` arm takes. The error is in the
function's own error type, after any conversion a `?` made through `From`:

```sysl
struct Failure
    reason: string

impl From[string] for Failure
    from(s: string) -> Failure = Failure(s"parse: $s")

parse(text: string) -> Result[usize, string] =
    if text == "" then Err("empty") else Ok(text.len)

load(text: string) -> Result[usize, Failure]
    errdefer e -> print("load failed:", e.reason)

    val n = parse(text)?
    Ok(n)

print(load("abc").is_ok())
print(load("").is_ok())
```

```output
true
load failed: parse: empty
false
```

The binder reads the error the returned value already holds: it takes no reference count and
allocates nothing, and like `defer`, `errdefer` is available under `no alloc`.

### Where it is refused

**A function that cannot fail is refused an `errdefer`**, which would never run:

```sysl
count(n: int) -> int
    errdefer print("never")
    n

print(count(1))
```

```error
'errdefer' runs when 'count' fails, and 'count' returns int, which cannot fail — use 'defer'
```

**An `Option` has no error to bind**, so the binder is refused there:

```sysl
pick(n: int) -> Option[int]
    errdefer e -> print(e)
    Some(n)

print(pick(1))
```

```error
'errdefer' binds the error 'pick' fails with, and 'pick' returns Option[int], whose 'None' carries none — leave the binder out
```

A closure whose result its context does not fix has no result to ask yet, so an `errdefer` in one is
refused until the result type is stated — as a `?` in one is.

---

Next: [declarations](/reference/declarations/).
