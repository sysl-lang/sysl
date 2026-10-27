---
title: Enums and patterns
summary: A named set of constants, or a closed set of shapes — and the one expression that takes either apart.
weight: 80
---

One keyword covers two things many languages write separately: a **set of named constants**, and a
**sum type** whose variants carry data. Whether any variant has a payload decides which one you get,
so there is a single keyword to learn and no way for two declarations to disagree about the kind.

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

Discriminants follow C's rule: a variant without a value is the previous one plus one, starting at
zero, and an explicit `= 10` sets the value counting continues from. Unlike C, `Color` is its own
type: a `Color` and an `int` do not mix without a written conversion, so the enum is a type-safe set
of constants rather than a collection of integers.

Two variants may not have the same value, however the collision arises. A simple enum's value *is*
its identity, so two names for one number would be one value with two spellings, and the `match` arm
for the second could never run. To give a value a second name, use a `const`.

A simple enum's underlying integer is `int` by default, and any integer type can be named instead:
`enum Pin: u8`, or an arbitrary width such as `u4`, which makes an enum usable as a packed
hardware-register field and not only as a set of names. The enum's name also answers a small fixed
set of queries written with `::`: `Pin::Image(A1)` is a variant's name as a string, `Pin::Pos` its
position, `Pin::First` and `Pin::Last` the two ends. These are kept out of the member namespace so they
cannot collide with a variant; [the reference](/reference/attributes/) lists them all.

Converting *to* the underlying integer always succeeds, since every enum value is a valid integer.
Converting *from* one has two forms, depending on how far you trust the value:

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

`Color(n)` is a checked conversion that **traps** on an integer no variant declares; it is the fast
path for a value already known to be valid. `Color.try(n)` returns an `Option`, and it is the form to
use for bytes read from a wire. This closes the gap in C where every `int` is silently a valid enum
value, in the same way the `char` rules do for code points.

## Variants that carry data

Give a variant a payload and the same keyword declares a sum type:

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

A variant is constructed by calling it, as in `Circle(3)`, with no `Shape.` prefix, because each
variant name is in scope as a constructor of its enum. A variant with no payload, like `Empty`, is
allowed in either kind of enum and is written the same way in both.

The memory mode follows the ordinary rule from the [memory chapter](/tour/memory/): a data enum is a
**value**, sized for its widest variant plus a tag, and it is placed on the heap only where a
`&Shape` is expected. An enum is a struct-shaped value with a tag, and the three modes apply to it
unchanged.

The payloads share one region rather than having a field per variant. A four-variant enum carrying
one integer in each variant is one integer wide plus its tag, so a table of two hundred of them is
the size you would expect, not four times that.

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

This is the main benefit of a closed set of variants: when you add a variant, every `match` on the
enum that does not handle it is reported at compile time, instead of failing at run time.

Coverage is about which *values* are handled, not which tags appear, and the arms are considered
together. `Some(Circle(r))`, `Some(Rect(w, h))` and `None` cover an `Option[Shape]` between them,
although no single arm covers `Some` on its own. `Some(0)` alone does not cover `Some`, because a
`Some` holding any other number is not matched.

A guarded arm never counts toward covering a variant, since the compiler cannot prove the guard
holds. That keeps exhaustiveness a real guarantee.

`Option` and `Result` are not special cases. They are ordinary generic enums in the library:

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

Everything this chapter says about matching, binding and exhaustiveness therefore applies to
`Option`, and everything you learn about `Option` applies to an enum you write yourself.

## Patterns

Arms can use literals, ranges, alternatives, bindings and guards, and are tried in order:

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

Arms are tried from top to bottom and the first match wins, so a failed guard **falls through** to a
later overlapping arm. That is why the two `4..10` arms mean "even, otherwise odd". The value being
matched is evaluated exactly once, whatever the arms do.

The `else` arm has no `->`. The arrow separates a pattern from its result, and `else` is not a
pattern but the fallback, so it takes its body the way an `if`'s `else` does.

Ranges are limited to the numeric types and `char`, where a contiguous interval is meaningful.
Literals work on anything `==` can compare, including `string` and `bool`, so a `match` on a boolean
with `true ->` and `false ->` arms is exhaustive without a catch-all.

Field selection dereferences one level automatically, but `match` does not. Matching a `&Enum` is
written `match *e`, which makes it explicit whether the reference or the value is being matched:

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

This is also how a recursive enum is written. A variant holding its own enum by value would have
infinite size, so recursion goes through a `&T` or a `*T`, the same indirection the memory chapter
describes for structs.

Binding a `&T` payload out of an enum retains it, so the extracted reference can outlive the enum it
came from and the count stays exact. This is the ordinary retain on aliasing, applied to the
variable a pattern binding introduces.

## Testing one pattern with `is`

`match` chooses among several shapes. Often a program cares about only **one**, and exhaustiveness
then requires arms it does not need: a one-arm `match` on an `Option` must still add an empty
catch-all.

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

The right side can be any pattern a match arm accepts: a literal, a range, a variant, a struct,
nested to any depth, and `|` alternatives. Tests chain with `&&`, so further conditions can follow:

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

An `is` that binds nothing is an ordinary `bool` anywhere: `val empty = o is None`, `!(o is
Some(_))`, an argument, a `return`. An `is` that **binds** may appear only as a term of an `if` or
`while` condition: not under `||`, not under `!`, and not on the right of an `=`. The restriction
concerns the binding, not the boolean. Elsewhere in sysl a name is introduced by a declaration whose
scope the indentation shows, and confining a binding `is` to conditions keeps the scope of its
bindings to a single rule:

> A binding is live from its own `is` rightward through the rest of the condition, and through the
> branch that condition guards.

That rule is what excludes `||`: there is no path through `a is P(x) || b` on which `x` is always
bound. A `while` makes its bindings per iteration, in the test, and releases them at the end of the
body, so a loop that drains a queue is written naturally and holds only one iteration's references
at a time, however many elements it processes.

Two further restrictions apply. A pattern under `is not` may not bind, since the name would exist
only on the path where nothing matched; write `x is not Some(_)`. And a pattern that cannot fail is
rejected rather than treated as `true`: `x is n` would be a declaration, not a test.

---

Next: [error handling](/tour/errors/): `Result`, the `?` operator, and the difference between an
error and a trap.
