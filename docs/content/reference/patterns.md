---
title: Patterns and matching
summary: Every pattern form, how arms are chosen, what guards do to exhaustiveness, and why alternatives may not bind.
weight: 60
---

`match` is an expression: it yields a value in value position and reads as ordinary control flow in
statement position. Its shape is a scrutinee and a sequence of arms, each an ordered list of
patterns, an optional guard, and a body.

```sysl
classify(n: int) -> string
    n match
        0         -> "zero"
        1 | 2 | 3 -> "small"
        4..10     -> "medium"
        else         "large"

print(classify(0), classify(2), classify(7), classify(99))
```

```output
zero small medium large
```

**The keyword goes after the value.** A match is a transformation of the thing to its left, and
writing it there is what lets one feed another: `x match … match …` reads in the order the values
flow. It binds **looser than every operator**, so the scrutinee is the whole expression written
before it — `a < b match` chooses on the comparison, and parentheses are what narrow it.

**The `else` arm carries no `->`.** The arrow separates a *pattern* from what to do when it matches,
and `else` is not a pattern — it is the fallback, and it takes its body the way an `if`'s `else`
does.

```sysl
classify(n: int) -> string
    n match
        0    -> "zero"
        else -> "other"

print(classify(0))
```

```error
the 'else' arm takes its body directly, with no '->' — 'else' names no pattern to separate one from
```

Two evaluation guarantees:

- **The scrutinee is evaluated exactly once.** A side-effecting scrutinee runs one time, and the arms
  test against the resulting value.
- **Arms are tried top to bottom**, and the first whose pattern matches — and whose guard, if any,
  holds — wins. That first-match rule is what makes a specific arm above a general one behave as
  written.

## The pattern forms

| pattern | example | matches |
|---|---|---|
| wildcard | `_` | anything, binds nothing |
| `else` arm | `else …` | anything; the catch-all spelling in tail position |
| literal | `0`, `'a'`, `"hi"`, `true` | a value equal to the literal |
| range | `3..7`, `0..<10`, `'a'..'z'` | a value in the range |
| bind | `r`, `other` | anything, and binds it to the name |
| variant | `Circle(r)`, `Empty`, `Shape.Empty` | that variant, binding each sub-pattern to a field |
| nested | `Wrap(Val(v))` | a variant whose payload itself matches a sub-pattern |
| struct, positional | `Point(a, b)` | a struct, binding every field by position |
| struct, named | `Point{x, y}`, `Point{x: a}` | a struct, binding fields by name; unlisted fields unconstrained |
| tuple | `(a, b)`, `(a, _)` | a tuple, by position |
| named | `c @ Circle(r)` | what the sub-pattern matches, binding the **whole** value too |

**Literal patterns match any type with equality; range patterns need a contiguous order.** The two
gates are deliberately different. A literal pattern works on the integers, `char`, `string`, and
`bool` — matching a boolean by pattern is the natural spelling, and it is exhaustive with both arms
present and no catch-all:

```sysl
word(b: bool) -> string
    b match
        true  -> "yes"
        false -> "no"

print(word(true), word(false))
```

```output
yes no
```

A range pattern is restricted to the numeric types and `char`, the types over which a contiguous
interval is meaningful. `string` is deliberately excluded: `"a".."z"` has no useful meaning. A
`string` is matched by literal or by binding.

### The bare-name rule

A bare identifier in pattern position is a **nullary-variant pattern** when it names a nullary
variant of the scrutinee's enum, and a **binding** otherwise.

```sysl
enum Shape
    Empty
    Circle(r: int)

name(s: Shape) -> string
    s match
        Empty     -> "empty"
        Circle(r) -> "circle " + str(r)

print(name(Empty), name(Circle(3)))
```

```output
empty circle 3
```

A bare name that happens to be a **data** variant is a diagnostic rather than a silent binding, which
closes the classic trap where a misspelled or payload-carrying variant quietly becomes a catch-all:

```sysl
enum Shape
    Empty
    Circle(r: int)

name(s: Shape) -> string
    s match
        Empty  -> "empty"
        Circle -> "circle"

print(name(Empty))
```

```error
variant 'Circle' carries data — match it as 'Circle(…)'
```

Rust and Swift reach the same resolution; sysl makes the data-variant case a hard error rather than a
lint.

**A qualified name is a pattern wherever a bare one is.** `Shape.Empty` matches exactly as `Empty`
does — the scrutinee's type already settled which enum is meant. What a qualified name cannot be is a
*binding*: no program declares a name with a dot in it, so one that resolves to neither a variant nor
a constant is a diagnostic rather than a new local.

### Alternatives may not bind

`1 | 2 | 3` is one arm matching any of three literals. An arm whose alternatives **bind** is
rejected, because the body cannot know which alternative matched and therefore cannot know a
binding's origin:

```sysl
enum Box
    One(v: int)
    Two(v: int)

get(b: Box) -> int
    b match
        One(v) | Two(v) -> v

print(get(One(1)))
```

```error
alternative patterns joined by '|' cannot bind a name
```

This is stricter than Rust, which permits `A(x) | B(x)` when every alternative binds the same names
at the same types. The rule here is simple and unambiguous: an arm with `|` binds nothing.

### A name may be bound twice in one match, but not in one pattern

Each arm is its own scope, so two arms may reuse a name freely. **Inside one pattern a repeat is
refused**, because a pattern binds once however deeply it nests, and a second binding of the name
would quietly stand for a different part of the value:

```sysl
struct Point
    x: int
    y: int
end Point

var p = Point(3, 3)

p match
    Point(v, v) -> print(v)
```

```error
'v' is bound twice in one pattern, and the second would quietly stand for a different part of the value — rename it, or compare the two in a guard
```

The reading it stops is the tempting one: `Point(v, v)` looks like a test that the two fields are
*equal*, and it is not one — no pattern here compares two parts of the value. That is what a
[guard](#guards) is for. Scala, Rust, OCaml and Haskell all refuse it for the same reason.

### `n @ pat` — matching and naming at once

A pattern that takes a value apart leaves the arm holding only the parts. Where the arm wants the
whole as well — to hand it on, to store it, to return it — `@` binds it beside them:

```sysl
enum Shape
    Circle(r: int)
    Rect(w: int, h: int)

area(s: Shape) -> int
    s match
        Circle(r) -> r * r * 3
        Rect(w, h) -> w * h

describe(s: Shape) -> string
    s match
        c @ Circle(r) -> "circle r=" + str(r) + " area=" + str(area(c))
        other -> "other, area=" + str(area(other))

print(describe(Circle(2)))
```

```output
circle r=2 area=12
```

Without it the arm has to choose: destructure and lose the value, or bind it and test the shape a
second time inside the body.

**A binding is not a test**, so a named arm covers exactly what the sub-pattern covers — no `else` is
owed that would not have been owed anyway, and none becomes unreachable. It nests, the part after
the `@` being an ordinary pattern: `whole @ One(part @ Val(n))` names three things at three depths.
It is also read at an [`is` test](#) and at a [binding](#a-pattern-at-a-binding), where
`var whole @ Point{x, y} = p` gives the value a name alongside its fields.

The name must be one a program could declare, so a qualified name is refused — what a binding
introduces is a local, and a name with a dot in it is not one.

**This `@` and an [annotation's](/reference/attributes/) are the same character and never compete.**
An annotation's is a prefix, on its own line above a declaration; this one is infix, between a name
and a pattern. No declaration may stand where a pattern is read, so neither position is reachable by
the other form — the arrangement Scala has carried for twenty years.

### Struct patterns

A struct is destructured two ways, and they are a **division of labour** rather than two spellings of
one thing.

```sysl
struct Point
    x: int
    y: int

describe(p: Point) -> string
    p match
        Point(0, 0) -> "origin"
        Point{x: a} -> "x is " + str(a)

print(describe(Point(0, 0)), describe(Point(5, 1)))
```

```output
origin x is 5
```

**Positional (`Point(a, b)`) is total.** It mirrors construction — sysl builds a struct positionally,
so tearing it apart positionally is symmetric — and it must name **every** field, with `_` to skip
one. Adding a field to the struct therefore turns each positional match into a checked arity error,
the way a new enum variant does. This is the *handle-everything* tool.

**Named-field (`Point{x, y}`) is partial by default.** It binds by field name, so it is
order-independent, supports renaming (`{x: a}` binds field `x` to `a`), and matches a subset — any
field left unlisted is simply unconstrained. Adding a field never breaks a named pattern. This is the
*grab-what-I-need* tool. There is no `..` token, because positional already covers the total case.

`Point(a, b)` is textually identical to a variant pattern but not ambiguous: the compiler resolves it
by what `Point` denotes. A struct type is a struct pattern; an enum variant is a variant pattern —
the same name resolution the bare-name rule uses.

**A tuple pattern is a struct pattern with the name left off.** `(a, b)` binds both components,
`(a, _)` binds one, and nesting works. A tuple has one shape, so a tuple pattern is irrefutable and
discharges its column for exhaustiveness exactly as a struct pattern does.

Both forms compose with everything else: a struct pattern nests inside a variant pattern and vice
versa, and each bound sub-pattern is itself any pattern in this table.

## A pattern at a binding

`match` is not the only place a pattern stands. A `val` or `var`
[binding](/reference/declarations/#by-pattern-when-the-shape-matters) takes one too:

```sysl
struct Counter
    n: int
end Counter

pair(c: *Counter) -> (int, int)
    c.n = c.n + 1
    (1, 2)

once()
    var c = Counter(0)

    val ((p, q), r) = (pair(&c), 3)

    print(p, q, r, c.n)

shape()
    val ((x, y), _) = ((3, 4), 5)
    var (lo, hi) = (0, 10)

    hi += 1

    print(x, y, lo, hi)

once()
shape()
```

```output
1 2 3 1
3 4 0 11
```

**The value is evaluated exactly once**, which is the same guarantee a scrutinee gets and for the
same reason: it is analyzed into a temporary no program can name, and every part is a field read of
that. `pair()` runs one time above however many names come out of it — the `1` on the end of the
first line is the count.

A `var` pattern makes every name it binds assignable and a `val` pattern makes each write-once,
exactly as the single-name forms do. A `_` binds nothing and skips its part.

That once-only rule is also what makes the obvious swap correct, since both parts of the right-hand
side are read before either name is bound:

```sysl
show()
    var (a, b) = (1, 2)
    val (c, d) = (b, a)

    print(a, b, c, d)

show()
```

```output
1 2 2 1
```

### Only an irrefutable pattern may stand there

A binding has **no other arm to take**. So the patterns allowed at one are exactly those that cannot
fail — a tuple pattern, **either struct form**, a name, a wildcard, and those nested inside one
another — and everything in the table above that is a *test* is refused by name.

A struct qualifies because it has exactly one shape, which is the same property that makes a tuple
pattern irrefutable, and both spellings of it stand here:

```sysl
struct Point
    x: int
    y: int
end Point

struct Pair
    both: (int, int)
end Pair

show()
    val Point{y, x} = Point(3, 4)
    val Point(a, b) = Point(5, 6)
    val (Point{x: px}, k) = (Point(7, 8), 9)
    val Pair((lo, hi)) = Pair((10, 11))

    print(x, y, a, b, px, k, lo, hi)

show()
```

```output
3 4 5 6 7 9 10 11
```

**The two forms differ in what they must account for, and it is the difference they have in a
`match`.** The named form may leave fields out, and an unlisted one simply binds nothing — there is
no exhaustiveness to discharge at a binding, so nothing has to stand in for it. The positional form
names every field, so a struct that grows one turns each positional binding into a checked to-do
rather than a binding that quietly goes on binding the same names:

```sysl
struct Point
    x: int
    y: int
    z: int
end Point

show()
    val Point(x, y) = Point(1, 2, 3)

    print(x)

show()
```

```error
struct 'Point' has 3 fields, but 2 sub-patterns were given
```

That `Name(…)` is a struct pattern here at all is the ordinary resolution rule: the spelling reads as
a variant pattern until the value's type settles it, exactly as it does in an arm.

A literal is a test:

```sysl
show()
    val (1, b) = (1, 2)

    print(b)

show()
```

```error
a binding cannot test a value — this pattern matches only some values, and a binding has no other arm to take when it does not match
```

So is a range, and the diagnostic is the same one, because it is the same objection.

A variant is a **choice among shapes**, which is a different objection and gets its own words:

```sysl
enum Shape
    Circle(r: int)
    Square(s: int)
end Shape

show()
    val (Circle(r), b) = (Circle(1), 2)

    print(r, b)

show()
```

```error
a binding cannot choose among variants — this pattern matches one of several shapes, and a binding has no other arm to take when the value has another
```

Each of those belongs in a `match`, where the arm that does not match has somewhere to fall through
to.

### The shape has to line up

A tuple pattern is irrefutable *for the tuple it describes*, so the arity is checked where it is
written rather than at run time:

```sysl
show()
    val (a, b, c) = (1, 2)

    print(a)

show()
```

```error
this pattern takes 3 parts, and a (int, int) has 2 parts to give it
```

Taking apart something that is not a tuple at all is refused in its own words, because the mistake is
a different one — there is no shape to disagree about:

```sysl
show()
    val (a, b) = 5

    print(a)

show()
```

```error
one int is not something to take apart — only a tuple is
```

And a name may appear once:

```sysl
show()
    val (a, a) = (1, 2)

    print(a)

show()
```

```error
'a' is named twice in one binding
```

## Guards

An arm may carry an `if` guard, evaluated **after** its pattern matches.

```sysl
band(n: int) -> string
    n match
        1..10 if n > 5 -> "high"
        1..10          -> "low"
        else              "out"

print(band(7), band(2), band(50))
```

```output
high low out
```

Three rules:

- **A guard runs only when its pattern has already matched**, never for an arm that was ruled out, so
  a side-effecting guard fires exactly on the arms whose shape fits.
- **A failed guard falls through to a later overlapping arm.** The two `1..10` arms above are the
  first-match rule plus fallthrough, not a partition into disjoint cases.
- **A guarded arm does not count toward exhaustiveness.** The compiler cannot prove a guard holds, so
  a guarded arm never discharges a variant's obligation — a `match` covered *only* by guarded arms
  still needs a catch-all. This is Rust's rule, and it is what keeps exhaustiveness a real guarantee
  rather than a formality.

## Exhaustiveness

**A `match` on a data enum must cover every value or carry an unguarded catch-all**, and a gap names
what is missing:

```sysl
enum Shape
    Circle(r: int)
    Rect(w: int, h: int)

area(s: Shape) -> int
    s match
        Circle(r) -> r * r

print(area(Circle(2)))
```

```error
match on 'Shape' is not exhaustive; missing Rect (add an 'else' arm)
```

That is the central payoff of a closed sum type: adding a variant turns every non-catch-all match on
it into a checked to-do list.

Four rules decide what counts as covered.

**Coverage is about which values are guaranteed handled, not which tags appear**, and the arms answer
that together. `Some(Halt)`, `Some(Push)` and `None` cover an `Option[Op]` between them even though
none of them covers a variant on its own — and `Some(0)` alone does *not* cover `Some`, because a
`Some` holding a non-zero value slips through.

**What is missing is named at the depth it is missing at.** A gap inside a payload reports as
`missing Some(Push)` rather than as `missing Some`, and a column no arm narrowed stays a `_` standing
for all its values, rather than expanding into one line per combination behind it.

**A type is covered by listing its values only when it has a finite, known set of them** — an enum's
variants, a struct's single shape, `bool`'s two. Everything else is covered by a wildcard or a
binding and by nothing shorter, which is why `1 -> …` and `2 -> …` on an `int` still need an `else`.

**A scalar match must be exhaustive only when it is used for a value.** In statement position a
non-exhaustive scalar match is fine — the unmatched case is a no-op. An **enum** match is
exhaustive-checked in *both* positions, because falling off the end of one has no defined result even
for effect. That asymmetry is what `is` exists to relieve; see [expressions](/reference/expressions/).

A catch-all is a wildcard or a bind in tail position, carried by an unguarded arm. `else` and `_` are
the same thing to the analyzer; `else` is the conventional spelling in tail position, `_` the one
that reads inside a `|` or a nested pattern.

## What a match is worth

**A match used for a value takes the common type of its arms.** When every arm yields the same
non-unit type, that is the match's type; a match whose arms only do things is `unit`.

**An arm that does not finish constrains nothing.** An arm that aborts or returns has type `never`,
so it is set aside before the others are compared:

```sysl
first(o: Option[int]) -> int
    o match
        Some(v) -> v
        None    -> return 0

print(first(Some(41)) + 1, first(None))
```

```output
42 0
```

Exhaustiveness is unaffected — a diverging arm still has to be *reachable* by a pattern that covers
something.

**Disagreeing arm types are a diagnostic, not a silent `unit`.** When the arms yield different
non-unit types and nothing unifies them, the match is a type error reported at the match rather than
a quiet fallback that surfaces later as a confusing error at the use site.

**A `&T` context reaches each arm, not the whole match.** Under a `&Point` expectation, an arm
yielding a bound `&Point` payload and an arm building a fresh `Point` meet at `&Point` — the value
arm is boxed on its own and the reference arm passes through untouched. Boxing the whole match
instead would fail, because an arm that is already `&Point` cannot un-become a value.

## Refcounts survive destructuring

Pattern matching obeys the memory model with no special rule, and both obligations are exactly where
a hand-written tagged union in C leaks or double-frees.

**Binding a `&T` payload out of an enum retains it.** `Full(p) -> p` hands the bound reference past
the frame of the enum it came from, so the payload is retained on bind and released once when the
binding dies — the extracted reference outlives the enum, and the count is exact.

**A binding under a failed guard is released exactly once.** When `Full(p) if p.x > 100` fails and
control falls through, the `p` bound for the guard is released before the next arm is tried, with no
double free and no leak.

## Matching through a reference

Selection auto-dereferences one level, but `match` does not: matching a `&Enum` or a `*Enum` against
its variants is written **`match *e`** — the same explicit one-level dereference Go asks for on a
type switch.

That keeps "am I matching the reference or the thing" a visible question, and it is the one place a
reference to an enum needs the `*`.

---

Next: [memory](/reference/memory/).
