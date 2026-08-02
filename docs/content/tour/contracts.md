---
title: Contracts and constrained types
summary: A type that carries a rule, a struct that keeps one, and a function that states what it requires.
weight: 120
---

Two features that are really one idea: **write the rule down where the thing is declared, and let the
compiler put the check in.** A constrained type narrows what values a scalar may hold; a struct
invariant says what has to stay true of a value's fields; a contract says what a function requires and
what it promises.

## One declaration form, three independent parts

```
type Name = [new] Base [within lo..hi] [where predicate]
```

`new`, `within` and `where` are contextual keywords — ordinary identifiers everywhere else, so a
field may still be called `where`. Each of the three parts may be left out:

```sysl
type Meters   = new f64
type Age      = int within 0..150
type Even     = int where value % 2 == 0
type Slot     = new u8 within 0..<8

var a: Age = 42
var e: Even = 8
var m = Meters(5.5)

print(a, e, f64(m))
```

```output
42 8 5.5
```

The value the predicate is about is named `value`, and it is bound only inside the predicate.

The base is a **scalar** — an integer, a float or a `char`. A constraint here is a check on a value,
and the two ways to narrow an aggregate are the struct invariant below and, for an enum, having fewer
variants.

One combination is rejected: `type Alias = int`, with no `new` and no constraint. It neither narrows
the values nor makes a new type, so it declares nothing at all — sysl has no `typedef`, and the
diagnostic says so at the declaration.

## `new` is what makes it a type

Without `new`, a constrained type **is** its base with a checked range. `Age` and `int` are the same
type, values flow between them freely, and what the declaration buys is the check:

```sysl
type Age = int within 0..150

birthday(a: Age) -> Age = a + 1

var n: int = birthday(41)

print(n)
```

```output
42
```

With `new` it is a **distinct nominal type**, and that is a large difference. Two derived types over
one base do not mix:

```sysl
type Meters = new f64
type Feet = new f64

var m = Meters(3.0)
var f = Feet(3.0)

print(f64(m + f))
```

```error
'+' needs matching types, got Meters and Feet
```

A derived type does not mix with its **base** either, and going in either direction is a written
conversion: `Meters(x)` wraps and `f64(m)` unwraps, and the wrap is where the constraint is checked.

The payoff is a specific bug. A table-driven program has several small integers that index different
things — a task number, a lock number, a priority level — and the mistake such a program actually
makes is passing one where another was wanted. Three `new u8`s with three ranges make that a compile
error instead of a plausible-looking wrong answer. It is an argument about **types**, not about
checking.

### A derivation inherits its base's behaviour and may replace none of it

A `new` type over a scalar arrives with everything the scalar could do — `==`, `<`, `+`, `str` and the
rest — working at itself and producing itself. And no `impl` may replace or extend any of it.

Inheriting is right because a derivation does not change what the values *are*: a `Slot` is some of
the `u8`s, not a different set of things stored in a byte. Refusing to replace is the harder call. If
`Stamp` could redefine `<`, then `Stamp` would be a set of `i64`s that do not order the way `i64`s
order, and every fact the base guarantees would hold only until somebody looked.

So the answer to "I want my own `+`" is that you do not want a derivation, you want a **struct**:

| | a `new` derivation | a one-field struct |
|---|---|---|
| distinct type | yes | yes |
| the base's catalogue | free, and unchangeable | nothing, write it all |
| an operation the base does not have | impossible | ordinary |
| an operation the base has that is now nonsense | present anyway | absent |

Use a derivation for an **identity** — a slot number, a handle, a unit-tagged measurement. Use a
struct for a **quantity with an algebra of its own**: an instant plus a duration is an instant, an
instant plus an instant is nonsense, and no derived scalar can be told the difference.

## Where a constraint is checked

At every point a value of the type is **produced**, and nowhere else. That is not a list of syntactic
forms to memorize — a value comes to have the type wherever it flows into a slot the type is written
on: an initializer, an assignment, an argument, a returned value, a cast, a struct field, an array
element, an enum payload, an item entering a generic container instantiated at the type.

A value that already has the type is not re-checked when it is merely read, passed along or copied.
It could not have got there unchecked.

One site is closed by not existing. A constrained subtype has **no zero value**, whether or not its
range contains zero:

```sysl
type Age = int within 0..150

var a: Age

print(a)
```

```error
Age has no zero value, so 'a' needs an initial value
```

Making that the *type's* rule rather than the range's means widening a range never silently changes
whether a declaration compiles somewhere else.

A violated check **traps**. It is not an error value and it is not catchable, and there is
deliberately no `try` form returning an `Option`: a constrained type states something its values are,
so a value that is not one of them is a bug in the code that made it rather than a condition to
handle.

## Asking instead of trapping

Which is what the type's own name is for. A constrained type answers a small closed set of
**attributes**, written with `::` so they stay out of the member namespace and no `impl` can shadow
one:

```sysl
type Slot = new u8 within 0..<8

print(u8(Slot::First), u8(Slot::Last), Slot::Valid(3u8), Slot::Valid(200u8))

var total = 0

for s in Slot::Range do total += int(u8(s))

print("sum of every slot:", total)
```

```output
0 7 true false
sum of every slot: 28
```

`First` and `Last` are the bounds — note `Last` is 7, one below the written exclusive bound. `Succ`
and `Pred` step, and **trap** at the ends rather than saturating or wrapping, on the same argument as
the produce sites. `Range` is the whole range as a `for` loop's iterable.

Every attribute but one speaks the subtype: a bound of `T` is a value of `T`, and the values `Range`
walks are `T`s. That is invisible on a transparent subtype and it is the point on a derived one, which
would otherwise be the only kind of type whose own attributes had to be cast back into it.

`Valid` is the exception, and the asymmetry is its job: it takes the **base**, because asking whether
a value is a `T` is only a question about something that is not one yet. It is total — it never traps
— which is what makes it the question form, and a cast after a `Valid` that answered true is the
ordinary way in.

## Struct invariants

A struct body may carry `invariant` clauses among its fields, each an expression over the struct's own
fields, in scope by name:

```sysl
struct Window
    lo: int
    hi: int
    invariant lo <= hi
    invariant hi - lo < 4096

var w = Window(2, 10)

w.hi += 5

print(w.lo, w.hi, w.hi - w.lo)
```

```output
2 15 13
```

Several clauses all have to hold, and each is checked independently so the diagnostic names the one
that failed.

**Checked at every write, not only at construction** — that is the part worth stating, because the
cheap implementation checks the constructor and calls it done. Assigning a single field counts,
including a compound assignment and an increment. So does a field written through a pointer, a field
written into an array element, and a field written *inside* one of those.

The consequence is the intended one: a sequence of writes that ends in a valid state but passes
through an invalid one **traps at the step that broke it**. There is no "I am mid-update" mode.

When a struct cannot be updated one field at a time, there are three answers and the order matters:

1. **Look for an order in which no intermediate state is illegal**, and often there is one. A
   `count <= high` watermark is updated by raising the ceiling before the floor. That the same clause
   accepts one order and refuses the other is the whole of the answer here.
2. **Ask whether the clause is pointing at a redundant field.** An invariant relating two fields is a
   claim about the *representation*, and the trap is often the compiler observing that the struct
   carries one fact twice. A ring buffer keeping `head`, `tail` and `count` cannot move any two of
   them one at a time; one keeping `head` and `count` and computing the end has no clause to break.
3. **Otherwise assign the whole struct**, and know the price — restating the whole value to move two
   bytes makes a container's own update cost the size of the container.

## Contracts on a function

```sysl
half(x: int) -> int
    require x >= 0, "a half of a negative is not what this means"
    ensure result >= 0

    x / 2

print(half(10), half(7))
```

```output
5 3
```

Both kinds form **one block at the top of the body**. A clause after an ordinary statement is
rejected: a precondition that runs after some of the work is not a precondition, and a postcondition
is written up there because that is where a reader looks for what the function promises — not because
that is when it runs.

`require` is checked on entry. `ensure` is checked before **every** return, including early ones. Both
take an optional message.

Two names exist only inside a contract. `result` is the value being returned, available in an `ensure`
only. `old(expr)` is `expr` evaluated on entry — which is what lets a postcondition talk about what a
mutating function *changed* rather than only about what it left behind:

```sysl
struct Counter
    n: int

    bump(*self)
        ensure self.n > old(self.n)

        self.n += 1

var c = Counter(0)

c.bump()
c.bump()

print("n:", c.n)
```

```output
n: 2
```

That is contracts at their most useful — on a mutating method, saying the thing the method is *for*.

Contracts are checked in **every** build. There is no release mode that drops them, and adding one
would make a program's meaning depend on how it was compiled.

## What this is not

It is not verification. Nothing here is proved while compiling: a `require` is a branch and a trap, an
invariant is a call to a synthesized predicate, a `within` is two comparisons. The compiler will not
tell you that a call site cannot satisfy a precondition, and it will not eliminate a check it could
have proved redundant.

That is a scope decision rather than a limitation waiting to be fixed. What the feature buys is that
the rule is written **once, where the thing is declared**, instead of being re-checked by hand at
every call — and that when it is broken, the program stops at the write that broke it rather than
somewhere downstream where the wrong value finally mattered.

## Tests live beside the code

The third checking tool, and the one that runs on examples rather than on rules. A `#test` attribute
on an ordinary function marks it as a test, and `assert` is what a test uses to state what it
expects:

```sysl
clamp(n: int, lo: int, hi: int) -> int
    if n < lo then lo
    elif n > hi then hi
    else n

#test
clamps_both_ends()
    assert(clamp(5, 0, 3) == 3, "above the ceiling")
    assert(clamp(-2, 0, 3) == 0, "below the floor")

#test("a value already inside is left alone")
leaves_the_middle()
    assert(clamp(2, 0, 3) == 2, "untouched")

print(clamp(9, 0, 3), clamp(1, 0, 3))
```

```output
3 1
```

That program prints `3 1` and runs **neither** test, which is the whole arrangement: `sysl run`
builds the program and the tests are not part of it. They have a caller nothing else has:

```
$ sysl test clamp.sysl
running 2 tests

clamp.sysl
  ok    clamps_both_ends                      2561ms
  ok    a value already inside is left alone  3ms

2 passed, 0 failed — 2564ms
```

A test is an ordinary function with **no parameters, no result, and no type parameters** — all three
being the same requirement seen from different sides, since the runner calls it with nothing and
reads the answer off whether it returned. Each is checked at the attribute rather than at the
function, because the function is a perfectly good function and it is `#test` that made a promise
about it.

The attribute has four forms. Bare `#test` names the test after the function; `#test("a sentence")`
names it by the sentence, which is what the second one above does and why the runner prints prose
for it. The other two are for the channel this chapter has been about: `#test(should_trap)` **passes
by stopping the program**, and `#test(should_trap: "past the end")` additionally requires that text
in what the run printed. That is how a trap gets tested at all — there is no catching it, so the
runner is the thing that survives it.

`assert(cond, msg)` traps when the condition is false, and its message is **required** rather than
optional. There is no stringizer in the language, so nothing can reconstruct the expression that
failed; a message you had to write is better than a source line you did not.

---

Next: [a program that reads its input](/tour/capstone/) — putting the whole tour to work.
