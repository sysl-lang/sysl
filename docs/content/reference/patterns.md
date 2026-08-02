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
is not exhaustive; missing Rect
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
