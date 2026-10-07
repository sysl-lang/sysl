---
title: Enums and patterns
summary: A named set of constants, or a closed set of shapes — and the one expression that takes either apart.
weight: 80
---

One keyword covers two things a lot of languages spell separately: a **named-constant set**, and a
**sum type** whose variants carry data. Which one you get is decided by whether any variant has a
payload, so there is no second keyword to learn and no way for two declarations to disagree about
which kind this is.

## A named set of constants

```sysl
enum Color
    Red
    Green
    Blue = 10
    Yellow

name(c: Color) -> string
    c match
        Red    -> "red"
        Green  -> "green"
        Blue   -> "blue"
        Yellow -> "yellow"

print(name(Blue), int(Blue), int(Yellow))
```

```output
blue 10 11
```

The discriminant rule is C's: a bare variant is the previous one plus one, starting at zero, and an
explicit `= 10` is a value the counting continues from. What is *not* C's is that `Color` is its own
type — a `Color` and an `int` do not mix without the conversion being written, which is what makes
this a type-safe constant set rather than a bag of integers.

Two variants may not stand for one value, however the collision arises. A simple enum's value *is*
its identity, so two names for one number would be one value with two spellings, and the second one's
`match` arm could never run. Deliberately naming a value twice is what a `const` is for.

A simple enum's underlying integer is `int` unless you say otherwise, and you can: `enum Pin: u8`
takes any integer type, including an arbitrary-width one like `u4` — which is what makes an enum
usable for a packed hardware-register field rather than only for a set of names. The enum's own name
also answers a small fixed set of questions written with `::` — `Pin::Image(A1)` is a variant's name
as a string, `Pin::Pos` its position, `Pin::First`/`Last` the ends — kept out of the member namespace
so they can never collide with a variant. [The reference](/reference/attributes/) has all of them.

Going *to* the underlying integer is total — every enum value is a valid integer. Coming *from* one
has two spellings, chosen by how much you trust the value:

```sysl
enum Color: u8
    Red
    Green
    Blue

print(Color::Image(Color(1)))

Color.try(7) match
    Some(c) -> print("got", Color::Image(c))
    None    -> print("7 is not a Color")
```

```output
Green
7 is not a Color
```

`Color(n)` is a checked cast that **traps** on an integer no variant declares — the fast path for a
value already known good. `Color.try(n)` returns an `Option`, and it is the required path for bytes
off a wire. That closes C's other enum hole, where every `int` is silently a valid enum value, the
same way the `char` rules close it for codepoints.

## Variants that carry data

Give a variant a payload and the same keyword builds a sum type:

```sysl
enum Shape
    Circle(radius: int)
    Rect(w: int, h: int)
    Empty

area(s: Shape) -> int
    s match
        Circle(r)  -> 3 * r * r
        Rect(w, h) -> w * h
        Empty      -> 0

print(area(Circle(2)), area(Rect(3, 4)), area(Empty))
```

```output
12 12 0
```

Constructing is calling the variant — `Circle(3)`, with no `Shape.` to write, because a variant name
is in scope as a constructor of its own enum. A nullary variant like `Empty` is legal in either kind
of enum and reads the same in both.

Which memory mode the result takes follows the ordinary rule from the [memory
chapter](/tour/memory/): a data enum is a **value**, sized for its widest variant plus a tag, and it
lands on the heap only where a `&Shape` is expected. Being a sum type changes nothing about the three
modes — an enum is a struct-shaped value with a tag on it.

The payload really is one region, not one field per variant. A four-variant enum carrying one integer
each is one integer wide, so a table of two hundred of them costs what you would expect rather than
four times that.

A `match` on a data enum must cover every value, and the diagnostic names what is missing:

```sysl
enum Shape
    Circle(radius: int)
    Rect(w: int, h: int)

area(s: Shape) -> int
    s match
        Circle(r) -> 3 * r * r
```

```error
match on 'Shape' is not exhaustive; missing Rect (add an 'else' arm)
```

That is the central payoff of a closed set of variants: adding one turns every match on the enum into
a checked to-do list rather than a bug that shows up at run time.

Coverage is about which *values* are guaranteed handled, not which tags appear, and the arms answer
that question together. `Some(Circle(r))`, `Some(Rect(w, h))` and `None` cover an `Option[Shape]`
between them though no single arm covers a variant on its own — while `Some(0)` alone does not cover
`Some`, because a `Some` holding anything else slips through.

A guarded arm never discharges a variant's obligation, since the compiler cannot prove a guard holds.
That is what keeps exhaustiveness a real guarantee instead of a formality.

Nothing above is special-cased for them. They are ordinary generic declarations in the library:

```sysl
first_even(xs: []const int) -> Option[int]
    for x in xs
        if x % 2 == 0 then return Some(x)

    None

var data = [1, 3, 6, 7]

first_even(data) match
    Some(n) -> print("found:", n)
    None    -> print("none")
```

```output
found: 6
```

That is worth knowing early, because it means everything this chapter says about matching, binding
and exhaustiveness is what you already know about `Option` — and everything you learn about `Option`
transfers to an enum you write yourself.

## What a pattern can be

Literals, ranges, alternatives, bindings and guards, in the order the arms are tried:

```sysl
classify(n: int) -> string
    n match
        0                    -> "zero"
        1 | 2 | 3            -> "small"
        4..10 if n % 2 == 0  -> "medium even"
        4..10                -> "medium odd"
        else                    "large"

print(classify(0), classify(2), classify(6), classify(7), classify(99))
```

```output
zero small medium even medium odd large
```

Arms are tried top to bottom and the first whose pattern matches wins, so a failed guard **falls
through** to a later overlapping arm — which is why the two `4..10` arms read as "even, otherwise
odd" rather than as a contradiction. The scrutinee is evaluated exactly once, whatever the arms do.

The `else` arm carries no `->`. The arrow separates a pattern from what to do when it matches, and
`else` is not a pattern — it is the fallback, and it takes its body the way an `if`'s `else` does.

Ranges are limited to the numeric types and `char`, where a contiguous interval means something.
Literals work on anything `==` can test, including `string` and `bool` — so matching a boolean with
`true ->` and `false ->` arms is exhaustive with no catch-all needed.

Field selection dereferences one level on its own, but `match` does not. Matching a `&Enum` is
written `match *e`, which keeps "am I matching the reference or the thing" a visible question:

```sysl
enum Tree
    Leaf(value: int)
    Node(left: &Tree, right: &Tree)

total(t: &Tree) -> int
    *t match
        Leaf(v)    -> v
        Node(l, r) -> total(l) + total(r)

var t: &Tree = Node(Leaf(1), Node(Leaf(2), Leaf(3)))

print("sum:", total(t))
```

```output
sum: 6
```

That is also the shape a recursive enum takes: a variant holding its own enum by value would be
infinitely sized, so recursion goes through a `&T` or a `*T` — the same indirection rule the memory
chapter gives for structs.

Binding a `&T` payload out of an enum retains it, so the extracted reference outlives the enum it
came from and the count stays exact. That is not a rule about patterns; it is ARC's retain-on-alias
applied to the temporary a binding introduces.

## One shape, and nothing to say about the rest

`match` asks a value to choose between several shapes. Very often a program cares about **one**, and
exhaustiveness then makes it pay for the arms it did not want — a one-arm match on an `Option` is
forced to write a do-nothing catch-all.

`expr is Pat` tests a pattern and yields a `bool`, binding whatever the pattern names:

```sysl
find(xs: []const int, target: int) -> Option[usize]
    for i in 0..<xs.len
        if xs[i] == target then return Some(i)

    None

var data = [4, 5, 6]

if find(data, 5) is Some(i) then print("at index:", i)

if find(data, 9) is not Some(_) then print("nine is not there")
```

```output
at index: 1
nine is not there
```

The right side is a match arm's left side, entire — a literal, a range, a variant, a struct, nested to
any depth, and `|` alternatives. Terms chain with `&&`, which is what keeps the form from evaporating
the moment a condition appears:

```sysl
struct Row
    active: bool
    age: int

lookup(id: int) -> Option[Row]
    if id == 1 then Some(Row(true, 30))
    else None

if lookup(1) is Some(row) && row.active && row.age is 18..65 then
    print("admitted at", row.age)
```

```output
admitted at 30
```

An `is` is a term of an `if`'s or a `while`'s condition and is legal nowhere else — not under `||`,
not under `!`, not on the right of an `=`. The restriction is about the **binding**, not the boolean:
everywhere else in sysl a name is introduced by a declaration whose scope you can see from the
indentation, and confining `is` to a condition is what keeps the answer to "where does this name hold
something?" to one sentence.

> A binding is live from its own `is` rightward through the rest of the condition, and through the
> branch that condition guards.

`||` is excluded by that sentence rather than by a separate rule: there is no path through
`a is P(x) || b` on which `x` was bound. A `while`'s bindings are per-iteration — made by the test,
released at the bottom of the body — which is what makes the drain loop the natural spelling and
keeps a loop over a million elements holding one round's refcounts rather than a million.

Two things `is` will not do. A pattern under `is not` may not bind, since it would name something on
the one path where nothing matched it — `x is not Some(_)` is the form that was wanted. And a pattern
that cannot fail is refused rather than folded away to `true`: `x is n` is a declaration wearing a
test's clothes.

---

Next: [error handling](/tour/errors/) — `Result`, the `?` operator, and the line between an error and
a trap.
