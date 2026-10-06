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
the values nor makes a new type, so it declares nothing at all — there is no `typedef` to write by
hand, and the diagnostic says so at the declaration. The one declaration of that shape is the one the
compiler writes for you, when a [`c type`](/reference/ffi/) asks the C compiler what a typedef in a
header actually is.

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

Being its base means its **name converts what the base's name converts**. `Age(n)` on a `usize` is
the `int(n)` you would otherwise write, and the range is checked on the value that arrives — so the
conversion is a conversion rather than a way past the constraint:

```sysl
type Age = int within 0..150

var n: usize = 42
var f: f64 = 7.9

print(Age(n), Age(f))
```

```output
42 7
```

That matters most where the base cannot be *named*. A [`c type`](/reference/ffi/) is a transparent
subtype of a width the C compiler measured for the target being built for, so `u32(n)` would be one
machine's answer written into the source, and the type's own name is the only portable way in.

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
The wrap takes a value that is already at the base — `Meters(3.0)` and not `Meters(3)` — which is the
one place the two kinds differ in call position, and it follows from `new` making the type distinct.

The payoff is a specific bug. A table-driven program has several small integers that index different
things — a task number, a lock number, a priority level — and the mistake such a program actually
makes is passing one where another was wanted. Three `new u8`s with three ranges make that a compile
error instead of a plausible-looking wrong answer. It is an argument about **types**, not about
checking.

A constraint is checked at every point a value of the type is **produced** — an initializer, an
assignment, an argument, a return, a cast, a field, an element — and nowhere else, because a value
that already has the type could not have got there unchecked. A violation **traps**: a constrained
type states something its values *are*, so a value that is not one of them is a bug in the code that
made it rather than a condition to handle. The type's own name is what asks instead of trapping —
`Slot::Valid(n)` is total and answers `bool`. [The reference](/reference/types/) has the full set.

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

A function says the same kind of thing about itself. `require` states what it needs of its arguments
and `ensure` what it promises of its result:

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

Nothing on this page is proved while compiling: a `require` is a branch and a trap, an invariant is a
call to a synthesized predicate, a `within` is two comparisons. What the feature buys is that the
rule is written **once, where the thing is declared**, instead of being re-checked by hand at every
call — and that when it is broken, the program stops at the write that broke it rather than somewhere
downstream where the wrong value finally mattered.

**The proving is a page of its own.** [Verification](/reference/verification/) adds the vocabulary a
specification needs — quantifiers, loop invariants, termination measures, `@pure` and `@ghost` — and
`sysl prove`, which discharges the obligations with Why3. The two fit together the way they do
because **a clause means one thing**: the prover and the running program read the same sentence, and
a check the prover proves redundant is still compiled. So nothing above changes when you start
proving, which is the point.

## Tests live beside the code

The third checking tool, and the one that runs on examples rather than on rules. A `@test` annotation
on an ordinary function marks it as a test, and `assert` is what a test uses to state what it
expects:

```sysl
clamp(n: int, lo: int, hi: int) -> int
    if n < lo then lo
    elif n > hi then hi
    else n

@test
clamps_both_ends()
    assert(clamp(5, 0, 3) == 3, "above the ceiling")
    assert(clamp(-2, 0, 3) == 0, "below the floor")

@test("a value already inside is left alone")
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
reads the answer off whether it returned. Each is checked at the annotation rather than at the
function, because the function is a perfectly good function and it is `@test` that made a promise
about it.

The annotation has four forms. Bare `@test` names the test after the function; `@test("a sentence")`
names it by the sentence, which is what the second one above does and why the runner prints prose
for it. The other two are for the channel this chapter has been about: `@test(should_trap)` **passes
by stopping the program**, and `@test(should_trap: "past the end")` additionally requires that text
in what the run printed. That is how a trap gets tested at all — there is no catching it, so the
runner is the thing that survives it.

`assert(cond)` traps when the condition is false, and names the file and line it failed on: its
`file` and `line` parameters default to the [reserved identifiers](/reference/lexical/) `__FILE__`
and `__LINE__`, and a default is evaluated at the call, so they report your line rather than the
library's.

The message is **optional**, and worth writing where it says something the condition does not — as
`"above the ceiling"` does above, naming the case rather than restating the comparison. There is
still no stringizer, so nothing reconstructs the expression that failed; what changed is that a
failure carrying no message is now a location instead of the bare words "assertion failed".

---

Next: [a program that reads its input](/tour/capstone/) — putting the whole tour to work.
