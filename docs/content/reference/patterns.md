---
title: Patterns and matching
summary: Every pattern form, how arms are chosen, what guards do to exhaustiveness, and why alternatives may not bind.
weight: 60
---

`match` is an expression: it yields a value in value position and acts as ordinary control flow in
statement position. It consists of a scrutinee and a sequence of arms, each an ordered list of
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

**The keyword goes after the value.** A match transforms the value to its left, so matches can be
chained: `x match … match …` reads in the order the values flow. It binds **more loosely than every
operator**, so the scrutinee is the whole expression before it — `a < b match` matches on the
comparison, and parentheses narrow it.

**The `else` arm has no `->`.** The arrow separates a *pattern* from its body, and `else` is not a
pattern but the fallback, which takes its body the way an `if`'s `else` does.

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
  holds — wins, so a specific arm above a general one takes precedence.

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
| unit | `()` | `unit`'s one value — so it always matches one |
| named | `c @ Circle(r)` | what the sub-pattern matches, binding the **whole** value too |

**Literal patterns match any type with equality; range patterns need a contiguous order.** A literal
pattern works on the integers, `char`, `string` and `bool`. A match on a `bool` is exhaustive with
both arms present and no catch-all:

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

A range pattern is restricted to the numeric types and `char`, over which a contiguous interval is
meaningful. `string` is excluded, since `"a".."z"` has no useful meaning; a `string` is matched by
literal or by binding.

**`()` is `unit`'s pattern, the tuple with no parts.** `unit` has one value, so `()` always matches
and covers the type alone. `Ok(())` is therefore an arm, and `Ok(())` with `Err(…)` is an exhaustive
match:

```sysl
check(n: int) -> Result[unit, string] = if n > 0 then Ok(()) else Err("too low")

for n in [1, 0]
    check(n) match
        Ok(()) -> print("ok")
        Err(why) -> print(why)
```

```output
ok
too low
```

Against any other type it is refused:

```sysl
var p = 1
p match
    () -> print(p)
    _ -> print(0)
```

```error
'()' matches unit, but the value is int
```

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
        Circle(r) -> "circle " + r.to_string()

print(name(Empty), name(Circle(3)))
```

```output
empty circle 3
```

A bare name that is a **data** variant is a diagnostic rather than a binding, so a payload-carrying
variant written without its payload cannot silently become a catch-all:

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

Rust and Swift resolve bare names the same way; sysl makes the data-variant case an error rather than
a lint.

**A qualified name is a pattern wherever a bare one is.** `Shape.Empty` matches exactly as `Empty`
does, since the scrutinee's type already determines the enum. A qualified name can never be a
*binding*, because no declared name contains a dot, so one that resolves to neither a variant nor a
constant is a diagnostic rather than a new local.

### A backticked name references rather than binds

The rule above resolves a bare name against a narrow set — the scrutinee's nullary variants, then the
constants — and binds otherwise. A `val`, a local or a parameter therefore cannot be referenced by a
bare name: each is storage read at run time, so there is no compile-time value for the pattern to
compare against.

**A [backtick-quoted name](/reference/lexical/#quoted-identifiers) makes the arm a test**: an ordinary
equality against whatever the name holds when the match runs:

```sysl
describe(n: int, limit: int) -> string
    n match
        `limit` -> "at the limit"
        else "elsewhere"

print(describe(10, 10), describe(3, 10))
```

```output
at the limit elsewhere
```

Written bare, `limit` would bind, matching everything and leaving the second arm unreachable. The
spelling alone shows which is meant, so a reader does not need to know what is in scope.

Three consequences follow. A quoted name that resolves to nothing is a diagnostic, not a new local. A
`const` still folds to its literal, so quoting one changes only how the arm reads. And a run-time
equality contributes nothing to exhaustiveness, so an arm written this way never covers a case and a
catch-all is still required.

It cannot appear in a binding, where there is no other arm to take when the value differs:

```sysl
val limit = 10
val (`limit`, b) = (3, 4)

print(b)
```

```error
a binding cannot test a value
```

### Alternatives may not bind

`1 | 2 | 3` is one arm matching any of three literals. An arm whose alternatives **bind** is
rejected, because the body cannot know which alternative matched, and so which part of the value a
binding refers to:

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
at the same types. In sysl, an arm with `|` binds nothing.

### A name may be bound twice in one match, but not in one pattern

Each arm is its own scope, so two arms may reuse a name freely. **Inside one pattern a repeat is
refused**, because each name is bound once however deeply the pattern nests, and a second binding
would silently refer to a different part of the value:

```sysl
struct Point
    x: int
    y: int

var p = Point(3, 3)

p match
    Point(v, v) -> print(v)
```

```error
'v' is bound twice in one pattern, and the second would quietly stand for a different part of the value — rename it, or compare the two in a guard
```

`Point(v, v)` looks like a test that the two fields are *equal*, but no pattern compares two parts of
the value; that is what a [guard](#guards) is for. Scala, Rust, OCaml and Haskell all refuse it for
the same reason.

### `n @ pat` — matching and naming at once

A pattern that takes a value apart gives the arm only the parts. Where the arm also needs the whole
value — to pass it on, store it or return it — `@` binds it beside them:

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
        c @ Circle(r) -> s"circle r=$r area=${area(c)}"
        other -> s"other, area=${area(other)}"

print(describe(Circle(2)))
```

```output
circle r=2 area=12
```

Without it the arm would have to either destructure and lose the whole value, or bind it and test
the shape again inside the body.

**A binding is not a test**, so a named arm covers exactly what the sub-pattern covers: it neither
requires nor makes unreachable any other arm. It nests, since the part after the `@` is an ordinary
pattern: `whole @ One(part @ Val(n))` names three things at three depths.
It is also read at an [`is` test](/reference/expressions/#is-testing-a-value-against-a-pattern) and at a [binding](#a-pattern-at-a-binding), where
`var whole @ Point{x, y} = p` gives the value a name alongside its fields.

The name must be one a program could declare, so a qualified name is refused: a binding introduces a
local, and a local's name has no dot.

**This `@` and an [annotation's](/reference/attributes/) are the same character but never conflict.**
An annotation's `@` is a prefix, on its own line above a declaration; this one is infix, between a
name and a pattern. No declaration can appear where a pattern is read, so the two positions never
overlap. Scala uses the same arrangement.

### Struct patterns

A struct can be destructured in two ways, which serve different purposes.

```sysl
struct Point
    x: int
    y: int

describe(p: Point) -> string
    p match
        Point(0, 0) -> "origin"
        Point{x: a} -> "x is " + a.to_string()

print(describe(Point(0, 0)), describe(Point(5, 1)))
```

```output
origin x is 5
```

**Positional (`Point(a, b)`) is total.** It mirrors positional construction and must name **every**
field, with `_` to skip one. Adding a field to the struct therefore makes each positional match an
arity error, as a new enum variant makes a match non-exhaustive. Use it where every field must be
handled.

**Named-field (`Point{x, y}`) is partial.** It binds by field name, so it is order-independent,
supports renaming (`{x: a}` binds field `x` to `a`), and may list a subset — any field left out is
unconstrained. Adding a field never breaks a named pattern. Use it to take only the fields you need.
There is no `..` token, because the positional form already covers the total case.

`Point(a, b)` has the same syntax as a variant pattern but is not ambiguous: the compiler resolves it
by what `Point` denotes. A struct type gives a struct pattern and an enum variant a variant pattern,
by the same name resolution the bare-name rule uses.

**A tuple pattern is a struct pattern without the name.** `(a, b)` binds both components, `(a, _)`
binds one, and nesting works. A tuple has one shape, so a tuple pattern is irrefutable and covers
its position for exhaustiveness exactly as a struct pattern does.

Both forms compose with everything else: a struct pattern nests inside a variant pattern and vice
versa, and each bound sub-pattern is itself any pattern in this table.

## A pattern at a binding

Patterns are not limited to `match`. A `val` or `var`
[binding](/reference/declarations/#by-pattern-when-the-shape-matters) takes one too, and so does the
variable of a [`for` loop](/reference/statements/#the-loop-variable-may-be-a-pattern), which binds
as a binding does and follows every rule in this section:

```sysl
struct Counter
    n: int

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

**The value is evaluated exactly once**, as a scrutinee is: it is stored in an unnamed temporary, and
every part is a field read of that temporary. `pair()` runs once however many names are bound from
it; the `1` at the end of the first line is the count.

A `var` pattern makes every name it binds assignable and a `val` pattern makes each write-once,
exactly as the single-name forms do. A `_` binds nothing and skips its part. Where there is no
keyword — a `match` arm, an `is`, an `@` — the binding
[is written once](#a-binding-a-match-makes-is-written-once), as a name with no `var` before it is.

Because the value is evaluated once, before any name is bound, a swap works as expected:

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

A binding has **no other arm to take**, so the patterns allowed in one are exactly those that cannot
fail — a tuple pattern, **either struct form**, a name, a wildcard, and any nesting of these — and
every pattern in the table above that is a *test* is refused, with a diagnostic naming it.

A `for` header is a binding under this rule just as a `val` is: a loop has nowhere to send an element
of an unexpected shape, so it accepts the same subset and refuses the same patterns with the same
message.

A struct qualifies because it has exactly one shape, as a tuple does, and both struct forms are
allowed:

```sysl
struct Point
    x: int
    y: int

struct Pair
    both: (int, int)

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

**The two forms differ here as they do in a `match`.** The named form may leave fields out, and an
unlisted field binds nothing. The positional form names every field, so adding a field to the struct
makes each positional binding an error to revisit rather than one that silently binds the same
names:

```sysl
struct Point
    x: int
    y: int
    z: int

show()
    val Point(x, y) = Point(1, 2, 3)

    print(x)

show()
```

```error
struct 'Point' has 3 fields, but 2 sub-patterns were given
```

`Name(…)` is resolved as a struct pattern here by the ordinary rule: the value's type decides between
a struct pattern and a variant pattern, exactly as in an arm.

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

So is a range, with the same diagnostic.

A variant is a **choice among shapes**, and has its own diagnostic:

```sysl
enum Shape
    Circle(r: int)
    Square(s: int)

show()
    val (Circle(r), b) = (Circle(1), 2)

    print(r, b)

show()
```

```error
a binding cannot choose among variants — this pattern matches one of several shapes, and a binding has no other arm to take when the value has another
```

Each of these belongs in a `match`, where a value that does not match can go to another arm.

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

Taking apart a value that is not a tuple at all has its own diagnostic:

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

## A binding a match makes is written once

At a `val` or a `var` the keyword decides. In a `match` arm, in an `is`, and under `@` there is no
keyword, so a pattern binding means what a name without `var` means everywhere else in sysl: **it is
written once.** Assignment to one is refused, and so is `&`, as for a `val`.

The reason is that the binding is a copy. A pattern gives the arm the part it matched rather than a
view into the original value — [a payload binding is a copy](#a-payload-binding-is-a-copy) — so a
write to the name would change only that copy, which is discarded when the arm ends, while appearing
to change the matched value.

```sysl
enum Cell
    Empty
    Full(v: int)

var d = Full(1)

d match
    Full(n) -> n = 99
    Empty -> print("empty")
```

```error
a pattern binding is written once, because it holds a copy of what it matched — so assignment would reach that copy rather than the value it came from. Where you mean to change something, take a 'var' from the binding first
```

To change a copy, declare it with `var`; the matched value is unchanged:

```sysl
enum Cell
    Empty
    Full(v: int)

var d = Full(1)

d match
    Full(n) ->
        var m = n
        m = 99
        print(m)
    Empty -> print("empty")

d match
    Full(n) -> print(n)
    Empty -> print("empty")
```

```output
99
1
```

Rust and Swift refuse the same write and each have a syntax for a mutable copy — `Some(mut n)`,
`case .full(var n)`. sysl has none: a `var` declared from the binding is short, and it makes the copy
explicit.

### The rule is about the binding, not about what it can reach

Where the payload is a `&T`, the copy is the **reference**, so a store through it reaches the object
the enum holds — see [refcounts survive destructuring](#refcounts-survive-destructuring). Field
selection dereferences, so `e.value = 99` is a write through the reference and does not require the
binding to be writable:

```sysl
struct Entry
    value: int

enum Slot
    Empty
    Filled(e: &Entry)

var s = Filled(Entry(1))

s match
    Filled(e) -> e.value = 99
    Empty -> print("empty")

s match
    Filled(e) -> print(e.value)
    Empty -> print("empty")
```

```output
99
```

The name is still written once, so it cannot be rebound to another object: `e = other` in the arm
above is refused as at the top of this section, even though the payload is a reference.

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
  a side-effecting guard runs only for arms whose pattern fits.
- **A failed guard falls through to a later overlapping arm.** The two `1..10` arms above rely on
  first-match order plus fallthrough; they are not disjoint cases.
- **A guarded arm does not count toward exhaustiveness.** The compiler cannot prove a guard holds, so
  a guarded arm never covers a case: a `match` covered *only* by guarded arms still needs a
  catch-all. This is Rust's rule.

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

This is the main benefit of a closed sum type: adding a variant makes every match on it without a
catch-all report what it now misses.

Four rules decide what counts as covered.

**Coverage is about which values are guaranteed to be handled, not which tags appear**, and the arms
are considered together. `Some(Halt)`, `Some(Push)` and `None` together cover an `Option[Op]` even
though none of them covers a variant alone, and `Some(0)` alone does *not* cover `Some`, because a
`Some` holding a non-zero value is not handled.

**What is missing is reported at the depth where it is missing.** A gap inside a payload is reported
as `missing Some(Push)` rather than `missing Some`, and a position no arm narrowed is reported as a
`_` standing for all its values, rather than as one line per combination.

**A type is covered by listing its values only when it has a finite, known set of them** — an enum's
variants, a struct's single shape, `bool`'s two values. Every other type is covered only by a
wildcard or a binding, so `1 -> …` and `2 -> …` on an `int` still need an `else`.

**A scalar match must be exhaustive only when it is used for a value.** In statement position a
non-exhaustive scalar match is allowed, and an unmatched value does nothing. An **enum** match is
checked for exhaustiveness in *both* positions, because falling off the end of one has no defined
result even for effect. For a single-variant test without a catch-all, use `is`; see
[expressions](/reference/expressions/).

A catch-all is a wildcard or a binding in tail position, in an unguarded arm. `else` and `_` are
equivalent to the analyzer; `else` is the conventional form in tail position, and `_` the form used
inside a `|` or a nested pattern.

## The type of a match

**A match used for a value takes the common type of its arms.** When every arm yields the same
non-unit type, that is the match's type; a match whose arms only perform actions is `unit`.

**An arm that does not finish imposes no type.** An arm that aborts or returns has type `never`, so
it is set aside before the others are compared:

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

Exhaustiveness is unaffected: a diverging arm still covers only what its pattern covers.

**Disagreeing arm types are a diagnostic, not a silent `unit`.** When the arms yield different
non-unit types that cannot be unified, the match is a type error reported at the match, rather than
a `unit` that causes a confusing error later at the use site.

**A `&T` context applies to each arm, not to the whole match.** Under a `&Point` expectation, an arm
yielding a bound `&Point` payload and an arm building a new `Point` both become `&Point`: the value
arm is boxed individually and the reference arm passes through unchanged. Boxing the whole match
instead would fail, because an arm that is already a `&Point` cannot be turned back into a value.

## Refcounts survive destructuring

Pattern matching follows the memory model with no special rule. The two cases below are where a
hand-written tagged union in C typically leaks or double-frees.

**Binding a `&T` payload out of an enum retains it.** `Full(p) -> p` passes the bound reference
beyond the enum it came from, so the payload is retained on binding and released once when the
binding dies: the extracted reference may outlive the enum, and the count stays exact.

**A binding under a failed guard is released exactly once.** When `Full(p) if p.x > 100` fails and
control falls through, the `p` bound for the guard is released before the next arm is tried, with no
double free and no leak.

## Matching through a reference

Selection auto-dereferences one level, but `match` does not: matching a `&Enum` or a `*Enum` against
its variants is written **`*e match`**, an explicit one-level dereference, as Go requires on a type
switch.

This keeps it visible whether the reference or the referenced value is being matched; it is the one
place a reference to an enum needs the `*`.

### A payload binding is a copy

The dereference determines *which value* is matched. It does not make the names bound in the arms
into views of the payload: **a payload binding is initialized by copy, whether or not the match went
through a reference.** Changing the copy leaves the enum unchanged.

The binding itself is [written once](#a-binding-a-match-makes-is-written-once), so the change is made
to a `var` declared from it, which makes the copy explicit.

```sysl
struct Reading
    id: int
    value: int

enum Slot
    Empty
    Full(r: Reading)

var s: &Slot = Full(Reading(1, 10))

*s match
    Full(r) ->
        var mine = r
        mine.value = 99
    Empty -> print("empty")

*s match
    Full(r) -> print("through a reference:", r.value)
    Empty -> print("empty")

var v = Full(Reading(1, 10))

v match
    Full(r) ->
        var mine = r
        mine.value = 99
    Empty -> print("empty")

v match
    Full(r) -> print("by value:", r.value)
    Empty -> print("empty")
```

```output
through a reference: 10
by value: 10
```

**Both reads give the value the enum was built with**: neither `mine.value = 99` reached the enum. A
binding is a local holding a copy of the payload; to change what a `&Slot` holds, assign a variant
to it.

The copy is the whole payload, so matching a reference to an enum whose variant carries a large
struct copies that struct into the arm. A binding does **not** copy the object behind a `&T`
payload: that payload is a reference, and copying it retains the object rather than duplicating it,
as described under *Refcounts survive destructuring* above.

---

Next: [memory](/reference/memory/).
