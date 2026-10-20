---
title: Contracts and constrained types
summary: A type that carries a rule, a struct that keeps one, and a function that states what it requires.
weight: 120
---

This chapter covers several features built on one idea: **write the rule where the thing is
declared, and let the compiler insert the check.** A constrained type narrows the values a scalar may
hold; a struct invariant states what must remain true of a value's fields; a contract states what a
function requires and what it guarantees.

## One declaration form, three independent parts

```
type Name = [new] Base [within lo..hi] [where predicate]
```

`new`, `within` and `where` are contextual keywords and ordinary identifiers everywhere else, so a
field may still be called `where`. Each of the three parts is optional:

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

Inside the predicate, the value being checked is named `value`; the name exists only there.

The base is a **scalar**: an integer, a float or a `char`. A constraint is a check on a single value.
To narrow an aggregate, use a struct invariant (below), or for an enum, declare fewer variants.

One combination is rejected: `type Alias = int`, with no `new` and no constraint. It neither narrows
the values nor creates a new type, so it declares nothing, and the diagnostic says so at the
declaration. sysl has no hand-written `typedef`. The only declaration of that shape is one the
compiler writes itself, when a [`c type`](/reference/ffi/) asks the C compiler what a typedef in a
header resolves to.

## `new` makes a distinct type

Without `new`, a constrained type **is** its base with a checked range. `Age` and `int` are the same
type and values pass between them freely; the declaration adds the check:

```sysl
type Age = int within 0..150

birthday(a: Age) -> Age = a + 1

var n: int = birthday(41)

print(n)
```

```output
42
```

Because it is its base, its **name performs the same conversions as the base's name**. `Age(n)` on a
`usize` is the `int(n)` you would otherwise write, and the range is checked on the converted value,
so the conversion cannot bypass the constraint:

```sysl
type Age = int within 0..150

var n: usize = 42
var f: f64 = 7.9

print(Age(n), Age(f))
```

```output
42 7
```

This matters most where the base cannot be *named*. A [`c type`](/reference/ffi/) is a transparent
subtype of a width the C compiler measured for the build target, so writing `u32(n)` would hard-code
one machine's answer, and the type's own name is the only portable conversion.

With `new`, the result is a **distinct nominal type**. Two such types over the same base do not mix:

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

A `new` type does not mix with its **base** either, and each direction is a written conversion:
`Meters(x)` wraps and `f64(m)` unwraps, and the constraint is checked when wrapping. The wrap takes a
value already of the base type, `Meters(3.0)` rather than `Meters(3)`. This is the one place the two
kinds differ at a call, and it follows from `new` making the type distinct.

This prevents a specific kind of bug. A table-driven program has several small integers that index
different things, such as a task number, a lock number and a priority level, and a common mistake is
passing one where another was meant. Three `new u8` types with three ranges turn that mistake into a
compile error instead of a plausible wrong answer. The benefit comes from the **types**, not from the
checks.

A constraint is checked wherever a value of the type is **produced**: an initializer, an assignment,
an argument, a return, a conversion, a field, an element. It is checked nowhere else, because a value
that already has the type cannot have arrived unchecked. A violation **traps**: a constrained type
states what its values *are*, so a value outside it is a bug in the code that produced it rather than
a condition to handle. To test a value without trapping, ask the type: `Slot::Valid(n)` always
succeeds and returns a `bool`. [The reference](/reference/types/) has the full set.

## Struct invariants

A struct body may contain `invariant` clauses among its fields. Each is an expression over the
struct's fields, which are in scope by name:

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

All clauses must hold, and each is checked separately so the diagnostic names the one that failed.

**Invariants are checked at every write, not only at construction.** Assigning a single field
counts, including a compound assignment and an increment, and so do a field written through a
pointer, a field of an array element, and a field nested inside either.

As a result, a sequence of writes that ends in a valid state but passes through an invalid one
**traps at the write that broke the invariant**. There is no exemption for an update in progress.

When a struct cannot be updated one field at a time, there are three options, in this order:

1. **Find an order in which no intermediate state is invalid**; there often is one. A
   `count <= high` watermark is updated by raising the ceiling before the floor. The same clause
   accepts one order and rejects the other.
2. **Check whether the clause points to a redundant field.** An invariant relating two fields is a
   claim about the *representation*, and the trap often shows that the struct stores one fact twice.
   A ring buffer that keeps `head`, `tail` and `count` cannot update any two of them one at a time;
   one that keeps `head` and `count` and computes the end has no clause to break.
3. **Otherwise assign the whole struct**, knowing the cost: rewriting the whole value to change two
   bytes makes each update cost the size of the struct.

## Function contracts

A function can state similar rules about itself. `require` states what it needs of its arguments,
and `ensure` what it guarantees about its result:

```sysl
half(x: int) -> int
    require x >= 0, "x must be non-negative"
    ensure result >= 0

    x / 2

print(half(10), half(7))
```

```output
5 3
```

Both kinds form **one block at the top of the body**. A clause after an ordinary statement is
rejected: a precondition checked after part of the work is not a precondition, and a postcondition
belongs at the top because that is where a reader looks for what the function guarantees, not because
that is when it runs.

`require` is checked on entry. `ensure` is checked before **every** return, including early ones.
Both accept an optional message.

Two names exist only inside a contract. `result` is the value being returned, available only in an
`ensure`. `old(expr)` is `expr` evaluated on entry, which lets a postcondition describe what a
mutating function *changed* and not only the state it left behind:

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

Contracts are most useful on mutating methods like this one, where they state what the method is
for.

Contracts are checked in **every** build. No release mode removes them, since that would make a
program's meaning depend on how it was compiled.

Nothing on this page is proved at compile time: a `require` is a branch and a trap, an invariant is a
call to a generated predicate, and a `within` is two comparisons. What the features provide is that
the rule is written **once, where the thing is declared**, instead of being re-checked by hand at
every call, and that when the rule is broken the program stops at the write that broke it, not later
where the wrong value happens to matter.

**Proof is covered separately.** [Verification](/reference/verification/) adds what a specification
needs, namely quantifiers, loop invariants, termination measures, `@pure` and `@ghost`, together with
`sysl prove`, which discharges the proof obligations with Why3. The two fit together because **a
clause has one meaning**: the prover and the running program read the same clause, and a check the
prover shows to be redundant is still compiled. Nothing on this page changes when you start proving.

## Tests live beside the code

Tests are the third checking tool, and they check examples rather than rules. A `@test` annotation on
an ordinary function marks it as a test, and `assert` states what the test expects:

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

That program prints `3 1` and runs **neither** test: `sysl run` builds the program, and tests are not
part of it. Tests are run by `sysl test`:

```
$ sysl test clamp.sysl
running 2 tests

clamp.sysl
  ok    clamps_both_ends                      2561ms
  ok    a value already inside is left alone  3ms

2 passed, 0 failed — 2564ms
```

A test is an ordinary function with **no parameters, no result and no type parameters**. These are
one requirement: the runner calls the test with nothing and judges it by whether it returns. The
requirement is checked at the annotation rather than at the function, because the function is valid
on its own and it is `@test` that makes the promise about it.

The annotation has four forms. Bare `@test` names the test after the function; `@test("a sentence")`
names it with the sentence, as the second test above does, which is why the runner prints prose for
it. The other two test traps: `@test(should_trap)` **passes if the program stops on a trap**, and
`@test(should_trap: "past the end")` also requires that text in the output. This is the only way to
test a trap, since a trap cannot be caught; the test runner observes it from outside the program.

`assert(cond)` traps when the condition is false and reports the file and line where it failed. Its
`file` and `line` parameters default to the [reserved identifiers](/reference/lexical/) `__FILE__`
and `__LINE__`, and since a default is evaluated at the call, they report your line rather than the
library's.

The message is **optional**. Write one where it adds something the condition does not, as
`"above the ceiling"` does above by naming the case rather than restating the comparison. The failed
expression is not reconstructed as text, so a failure without a message reports its location only.

---

Next: [a program that reads its input](/tour/capstone/), which puts the whole tour to work.
