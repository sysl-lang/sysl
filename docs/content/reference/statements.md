---
title: Statements and control flow
summary: Blocks, bindings, every loop and branch form, and what each one yields — because in sysl most of them yield something.
weight: 40
---

`if`, `match`, `while`, `loop` and `for` are **expressions**. Each yields a value, so each can
initialize a binding, be a function's body, or feed a branch of another. In statement position the
value is simply unused and the same forms read as ordinary control flow.

That removes the statement/expression split that forces C's temporary-and-reassign dance
(`int label; if (…) label = …; else label = …;`), and it makes one rule — *the last expression is
the value* — uniform across functions, branches, arms and loops.

## Blocks

A block is an indented run of statements, and its value is its **trailing expression**. There are no
braces and no statement terminator: a newline ends a statement, and indentation opens and closes a
block. See [lexical structure](/reference/lexical/) for the layout rules and how a long expression is
continued across lines.

Where a block's own value is unused, the block has **none** — its type is `unit` whatever the last
line yields — and that propagates inward, into the branches of an `if`, the arms of a `match`, and
the `else` of a loop. This is what lets branches merely do things without being made to agree on a
value nobody asked for:

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

Without that rule, `full = true` (a `bool`) and `len += 1` (an `int`) would be two branches with
nothing to meet at. Statement position starts at a statement, at a loop body, and at the body of a
function that returns nothing.

### The statement forms

Everything that can stand as a statement:

| form | |
|---|---|
| an expression | including every control-flow form, since they are expressions |
| `var` / `val` / `const` | a binding — see [declarations](/reference/declarations/) |
| `ref name = place` | a second name for one place — see [memory](/reference/memory/) |
| `a, b = x, y` | a multiple assignment, which is **not** an expression |
| `return`, `break`, `continue` | transfers of control |
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

`elif` is sugar: each one nests into the `else` branch of the one before, so no separate node exists
and no rule about it has to be learned.

**An `if` used for a value needs an `else`.** A missing one leaves the open branch at `unit`, which
is a diagnostic wherever a value was wanted. In statement position a missing `else` is ordinary.

An `if` may be closed with `end if`, which is optional everywhere and reads well when the block is
long enough that its extent has stopped being obvious:

```sysl
var n = 3

if n > 0
    print("positive")
end if
```

```output
positive
```

`end` is a **soft** word — an ordinary identifier everywhere except immediately before a construct
keyword — so `end` remains usable as a name. The same marker closes `while`, `for`, `loop`, a struct
and an enum.

## `match`

`value match`, then an indented list of `pattern -> body` arms. The keyword goes **after** the value,
as in Scala and for Scala's reason: a match is a transformation of the thing to its left, so writing
it there is what lets one feed another.

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

A match used **for a value must be exhaustive**; one written for effect need not be, which follows
the same rule the block-value discard does. Everything about the left side of an arm — the pattern
grammar, guards, alternatives, bindings — is on [patterns and matching](/reference/patterns/).

## Loops

Four forms, and each is an expression.

| form | when |
|---|---|
| `while cond` | test before each iteration |
| `loop` | no test at all; something inside leaves it |
| `for x in seq` | walk a range, an array, a slice, or an `Iterate` |
| `for init; cond; step` | a stride, a descent, a compound test, several variables |

### `for x in`

The ordinary walk, and preferred wherever it fits: it names one thing instead of three and cannot
get any of them wrong.

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

That turns the most common reason to leave a loop early — *find the first element satisfying a
predicate* — into one expression whose type states, through the mandatory `else`, what happens when
nothing is found. It is the same discipline that gives references no null and errors a `Result`: the
loop cannot fall through to an undefined value.

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

### `loop`

A loop with nothing to test. It runs until something leaves it, which is what `while true` was always
being used to say.

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

Two things follow from the condition being *gone* rather than merely constant. It takes **no
`else`** — an `else` runs on normal completion, and this loop has none — so a value-carrying `break`
needs nothing beside it, which is why the program above compiles where the `for` above it did not.

And a `loop` **nothing breaks out of has type `never`**, so it may stand as the last thing a function
owing a value does, with nothing after it to supply one:

```sysl
serve(handle: () -> unit) -> int
    loop
        handle()
```

That is the fact `while true` cannot state. A condition is an expression the analyzer does not
evaluate, so a loop written that way looks like one that might finish, and the code after it looks
reachable.

### The three-clause `for`

C's counted loop without the parentheses, as Go writes it — every other header in the language is
parenthesis-free, and parentheses here would be structure rather than grouping. Each of the three
clauses may be left out.

```sysl
var total = 0

for var i = 0; i < 10; i += 1
    if i % 2 == 0 then continue

    total += i

var down = ""

for var i = 3; i > 0; i -= 1
    down += str(i)

print(total, down)
```

```output
25 321
```

**The step is why the form exists, and it is not sugar for a `while`.** `continue` runs the step
before testing again, so the skipped iterations above still advance — which is the whole reason that
loop terminates. Written as a `while` the increment sits at the foot of the body, where the first
`continue` anybody adds later walks straight past it: a bug the shape of the code invites and nothing
about it warns of.

The second loop is the other reason: a descent is not something a range says.

An absent condition never turns false, so such a loop ends only through a `break` and takes no
`else` — exactly as `loop` does, and `loop` says it better.

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

A labeled `break` carries a value exactly as a bare one does, meeting that loop's other breaks and
its `else` at one type. `continue` takes a label but never a value.

The sigil is a leading apostrophe, as in Rust, and deliberately not the `:`-suffix some languages
use: a bare `break outer` would be ambiguous with `break expr` carrying a value that happens to be
named `outer`, and the apostrophe keeps a label and a value textually distinct. It does not collide
with a character literal — `'a'` closes its quote and is a character, `'a` does not and is a label.

A label is in scope only inside its own loop's body. Naming a loop that does not enclose the
`break`, or reusing a label already in scope, is an error rather than a silent miss.

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

## `defer`

ARC gives back a `&T`, a `string` and a slice's backing without being asked. It knows nothing about
the rest: a descriptor from `open`, a `FILE*` from `fopen`, a block from `malloc`, a lock taken from
a mutex. Until those are released by hand, a correct program is one that never takes an early exit —
which `?` makes the *normal* way to leave a function.

**`defer <statement>` runs that statement on the way out of the block containing it.** The resource
is released beside the call that took it, once, rather than at every exit.

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

**Several in one block run last-registered-first**, as above, so they undo in the reverse of the
order they were set up — the order that lets a later one depend on an earlier one's resource.

**A `defer` runs only if control reached it.** It is a statement, not a declaration: one after an
early `return` never registered, and one in a branch never taken did not either.

**The whole statement runs at the exit, so everything in it is read there** — not where the `defer`
stands:

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

Go differs here: it evaluates a deferred call's *arguments* where the `defer` stands and runs the
call later, which needs somewhere to keep each captured argument until then — a slot per deferred
statement, sized and laid out per call. Reading everything at the exit needs nothing kept. This is
Zig's rule, and it changes nothing for the form's purpose: `defer fclose(f)` releases the handle
bound above it, and a program that rebinds `f` in between has changed which file is open, so closing
the one that is actually open is the behaviour wanted.

**A trap runs nothing.** A trap aborts without stack cleanup, and `defer` does not qualify that: a
broken invariant means the program's model of itself is already wrong, and running cleanup against
that state is how a corrupt program writes its corruption to disk on the way down. `defer` releases a
resource; it does not restore an invariant.

### Why the block and not the function

Go's `defer` runs at **function** exit. The difference is invisible for the common case — a resource
taken at the top of a body and released when the body ends, where the function *is* the block — and
shows up in a loop:

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

Block scope closes each iteration's resource at the end of that iteration, so the program holds one
at a time. Function scope would hold all of them until the function returned — and, the part that
decides it, would need somewhere to record a number of pending statements that no compiler can bound.
That is a per-frame list whose length is discovered while running, which is precisely the machinery
that a freestanding target under `no alloc` has nowhere to put. Block scope needs no runtime state at
all: the statement is emitted at each edge that leaves the block, exactly as ARC's own releases
already are.

Go has two reasons for its choice and sysl has neither. `recover` only works inside a deferred call
and a panic unwinds one frame at a time, so the frame has to be the unit; and mutating a named result
is how Go wraps an error on the way out. sysl has no unwinding and no named results. The languages
that came later without `recover` — Zig, Swift — both put `defer` at the block.

### Where it sits against the rest of the model

**A deferred statement runs before the block's ARC releases**, so every local it names is still alive
when it runs, including the one holding the resource being closed. Leaving from the middle unwinds
outward: the innermost block runs its deferred statements and then gives up its counts, then the
block outside it does the same, up to the function's own.

**It owns nothing and allocates nothing.** `defer` takes no count, makes no box, and adds no word to
any value; a program that does not use it emits nothing for it. That is what keeps it available under
`no alloc`, where the resources it releases are the only ones there are.

**What it is not is a destructor.** A destructor belongs to a *type* and runs wherever a value of that
type dies; `defer` belongs to one place in one body and runs for the resource that body took.

---

Next: [declarations](/reference/declarations/).
