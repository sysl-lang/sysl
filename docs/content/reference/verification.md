---
title: Verification
summary: Quantifiers, loop invariants, termination measures, `@pure`, `@ghost`, and `sysl prove` — the clauses that say what a program means, and the prover that reads them.
weight: 135
---

[Contracts](/tour/contracts/) give sysl `require`, `ensure` and `invariant`, and every one of them is
a branch and a trap. This page is the other half: the vocabulary a *specification* needs that an
executable condition does not supply on its own, and the backend that discharges the result.

**One clause means one thing.** The prover and the running program read the same sentence. There is
no proof-only build, no specification subset the compiler declines to execute, and a check the prover
proves redundant is still compiled — a program whose emitted code depended on whether a prover was
available, and on how long it was given, is one nobody could reason about. The single exception is
[ghost code](#ghost--what-costs-nothing-to-say), and it is legible in the source rather than in a
flag.

## `for all` and `for some`

A quantifier over an integer range, universal and existential. It is an ordinary `bool`, usable
wherever one is:

```sysl
var a = [2, 4, 6]

print(for all i in 0..<3 do a[i] % 2 == 0)
print(for some k in 0..<3 do a[k] > 5)
print(for some k in 0..<3 do a[k] > 9)
```

```output
true
true
false
```

`all` and `some` stay ordinary identifiers — they are read as keywords only directly after `for`, and
only when a name follows. So a loop over a variable called `all` is still a loop:

```sysl
for all in 0..<3 do print(all)
```

```output
0
1
2
```

The separator is `do`, the word every loop header already uses. The predicate extends as far to the
right as an expression can, exactly as a closure's body does, so `for all i in r do P(i) && Q(i)`
quantifies over the conjunction. Written as the second arm of a chain, a quantifier is parenthesized:

```sysl
var a = [2, 4, 6]

print(a[0] == 2 && (for all i in 0..<3 do a[i] > 0))
```

```output
true
```

**An empty range takes each quantifier's identity.** A conjunction over nothing is true and a
disjunction over nothing is false — these are not conventions, and getting them the other way round
breaks every proof that reasons about the first iteration of anything. A range whose bounds are the
wrong way round is empty too, which is what `for all i in 0..<n - 1` needs when `n` is zero:

```sysl
print(for all i in 0..<0 do false)
print(for some i in 0..<0 do true)

var n = 0

print(for all i in 0..<n - 1 do false)
```

```output
true
false
true
```

Both forms short-circuit: `for all` stops at the first counterexample, `for some` at the first
witness. That is observable, because a predicate may trap, so it is specified rather than left to the
emitter.

## `invariant` and `variant` on a loop

Both are written as the leading statements of a loop's body, in the position `require` and `ensure`
take at the top of a function. Written as statements rather than as slots in each loop's header, one
rule serves all five loop forms:

```sysl
sum_to(n: int) -> int
    var i = 0
    var s = 0

    while i < n
        invariant i >= 0 && i <= n
        variant n - i
        s += i
        i += 1

    s

print(sum_to(5))
```

```output
10
```

**`invariant` is checked on every entry to the body** — on arrival at the loop, and again before each
subsequent iteration. It is not checked on the way out: that is where the clause is written, and a
clause that also ran on exit would be checking something the loop is no longer doing.

**`variant` is a live termination check.** The measure is taken at the top of each iteration and
compared against the previous one; a run that fails to decrease traps. So a loop that stops making
progress stops, rather than running forever:

```sysl
@test("a loop that stops decreasing stops", should_trap)
spins()
    var i = 0

    while i < 10
        variant 10 - i
        if i == 3 then i -= 1
        else i += 1

    print(i)
```

The measure is not required to be non-negative. A strictly decreasing integer unbounded below still
fails to prove termination, and that failure belongs to the prover — making it a runtime trap would
refuse programs that terminate for a reason the clause does not capture.

Both words are contextual, so a value may still be called either:

```sysl
f() -> int
    var invariant = 7
    var variant = 35

    invariant + variant

print(f())
```

```output
42
```

A clause written after ordinary work is not an invariant, and is refused:

```sysl
f() -> int
    var i = 0

    while i < 2
        i += 1
        invariant i > 0

    i
```

```error
an 'invariant' belongs at the head of a loop's body
```

## `variant` on a function

The same word in a function's contract block declares what decreases at a recursive call:

```sysl
gcd(a: int, b: int) -> int
    require b >= 0
    variant b
    if b == 0 then a
    else gcd(b, a % b)

print(gcd(48, 18))
```

```output
6
```

**A function's `variant` may read only its parameters**, and that restriction is what makes the check
local. At a direct self-call the compiler has both the current parameter values and the arguments
about to replace them, so it evaluates the measure twice — once as it stands, once with the arguments
in the parameters' own slots — and traps when the second is not less than the first. No hidden
argument travels with the call, and nothing is kept between calls.

Scoping is what enforces the restriction: the clause is analyzed before the body, in a scope holding
the parameters alone, so a name from the body is simply undefined there.

The check happens *before* the call, so unlike an `ensure` it survives the tail-call transform and a
`@tailrec` function may carry one:

```sysl
@tailrec
walk(n: int, acc: int) -> int
    variant n
    if n == 0 then acc
    else walk(n - 1, acc + n)

print(walk(4, 0))
```

```output
10
```

The check reaches direct self-calls only. Mutual recursion between two functions with measures is a
proof obligation for `sysl prove` and is not checked at runtime — there is no call site at which both
halves of the measure are in hand.

## `@pure`

An annotation, checked by the compiler, refused on violation. A caller of a pure function can observe
nothing about the call but its result:

```sysl
@pure
square(x: int) -> int = x * x

@pure
fact(n: int) -> int
    require n >= 0
    if n <= 1 then 1
    else n * fact(n - 1)

print(square(7), fact(5))
```

```output
49 120
```

**What it may do:** read its parameters and any `const` or `val`; declare and mutate its own locals;
call other pure functions; recurse; use every control-flow form; **allocate**; and **trap**.

**What it may not do:** call a function that is not pure, including any `extern`; write through a
`*T`, into a `&T`'s field, or into any storage it did not create; perform I/O; contain an `asm` block;
call through a closure, or dispatch through a trait object.

A write is examined through its *path*, not its root — so storage the call made is the call's,
however deep the indexing goes:

```sysl
@pure
sum(a: [4]int) -> int
    var b = a
    var t = 0

    b[0] = 99

    for i in 0..<4
        t += b[i]

    t

var xs = [1, 2, 3, 4]

print(sum(xs))
```

```output
108
```

**A pure function may allocate**, which is a deliberate departure from other languages' purity
checks. A caller cannot observe an object that did not exist when the call began, and banning
allocation would put every string operation out of reach. The question allocation raises is a real
one and sysl answers it elsewhere — `no alloc` answers it for a whole module, at the point of
allocation. Two annotations for two questions:

```sysl
@pure
shout(s: string) -> string = s + "!"

print(shout("hi"))
```

```output
hi!
```

Writing through a reference the function was handed is refused, since that is what a caller sees:

```sysl
@pure
f(p: *int) -> int
    *p = 5
    1

print(f(null))
```

```error
a '@pure' function writes through a reference to storage it did not create
```

So is I/O, which is the same rule reaching a call rather than a store:

```sysl
@pure
f(x: int) -> int
    print(x)
    x

print(f(1))
```

```error
which is not marked '@pure'
```

**Purity is not inferred.** A function is pure because it says so — inference would let an unrelated
edit to a leaf break a caller three levels up with no annotation anywhere naming the promise. Nothing
in the standard library is annotated yet, so a pure function today reaches the language and other
pure functions the program wrote.

## `@ghost` — what costs nothing to say

Everything above executes, and that has a cost in exactly one place: a specification is often more
expensive than the code it specifies. `invariant is_sorted(a, i)` is the right invariant for an
insertion sort's outer loop, it is O(n) where the body is O(n), and checking it every iteration turns
an O(n²) sort into O(n³).

`@ghost` marks a declaration that exists for the specification alone. It is erased before codegen and
costs nothing at run time:

```sysl
@ghost
is_sorted(a: []int, n: int) -> bool = for all i in 0..<n - 1 do a[i] <= a[i + 1]

insertion(a: []int, n: int) -> int
    ensure is_sorted(a, n)
    var i = 1

    while i < n
        invariant is_sorted(a, i)
        variant n - i
        var j = i

        while j > 0 && a[j - 1] > a[j]
            variant j
            var t = a[j - 1]

            a[j - 1] = a[j]
            a[j] = t
            j -= 1

        i += 1

    n

var xs = [5, 2, 9, 1, 7]

print(insertion(xs[..], 5))
print(xs[0], xs[1], xs[2], xs[3], xs[4])
```

```output
5
1 2 5 7 9
```

Two rules make erasing it sound:

1. **Nothing executable may call a ghost function.** If it could, erasing the declaration would
   change what the program computes.
2. **A clause that calls one is a clause that does not run.** Given the first it *cannot* run — the
   callee is not there — so the only question was whether to allow such a clause at all.

The second is the one exception to "a clause means one thing", and it is acceptable because it is
**visible in the source**: a reader asking whether a clause executes reads the names in it. What sysl
refuses is a *switch*, where one program has two meanings depending on how it was built.

An ordinary clause beside a ghost one is untouched, and still traps:

```sysl
@ghost
positive(n: int) -> bool = n > 0

f(n: int) -> int
    require n >= 0
    require positive(n)
    n

print(f(3))
```

```output
3
```

Calling a ghost function from code that runs is refused:

```sysl
@ghost
positive(n: int) -> bool = n > 0

print(positive(1))
```

```error
is '@ghost', so it exists for the specification and is not there when the program runs
```

A ghost function's own body is ordinary code and may read real state freely — that is the whole point
of an `is_sorted`.

## `sysl prove`

`sysl prove <file>` translates a module to [WhyML](https://www.why3.org/), the input language of the
Why3 platform, and discharges the resulting goals with whichever provers Why3 is configured with.

```bash
$ sysl prove gcd.sysl
Goal gcd'vc.
Prover result is: Valid (0.01s, 165 steps).

every goal was discharged
```

`--emit-whyml` prints the translation instead of proving it, which is what to reach for when a goal
will not go through.

**A proof is not a build.** Nothing is emitted and nothing about `sysl build` changes. A module that
fails to prove still compiles and still runs, with every check this page describes. What the prover
buys is finding out before the program runs rather than at the trap.

### Integer overflow is a proof obligation

sysl's plain integer arithmetic wraps; WhyML's `int` is the mathematical integers, which do not.
Translating `a + b` to `a + b` would prove theorems about a language sysl is not — silently, which is
the worst kind of wrong. So in code every arithmetic operation goes through a checked wrapper whose
precondition is that the true result is representable:

```
let add_i32 (a b: int) : int
  requires { -2147483648 <= a + b <= 2147483647 }
  ensures  { result = a + b }
= a + b
```

A program that stays in range gets the mathematical model, which is exact for it. One that might not
gets a failed goal naming the operation — so `n * 2` for an `n` known only to be non-negative does
not discharge, and the same function with `require n <= 1000` does. `--overflow ignore` drops the
obligations for somebody reasoning about the rest of a function first.

Every integer parameter carries its own range as a precondition, which is not an extra demand on the
caller: it is the fact that the argument had the type it was declared with.

### Terms and programs

WhyML separates *terms*, which are mathematics and may appear in a `requires`, from *programs*, which
have state and may not. **`@ghost` is what decides which world a function lands in** — a ghost
function becomes a `predicate`, and every other function becomes a program. So a contract that calls
an ordinary function is refused, with the sentence that says to mark it.

That also means a ghost function's body must be one expression: a specification is mathematics, so it
may not declare a variable or run a loop.

### What is translated

The scalar fragment: functions whose parameters, locals and result are integers and booleans;
arithmetic and comparison; `if`; `while`; local variables and assignment; `require`, `ensure`,
`result`, `old`, both quantifiers, loop `invariant` and `variant`, function `variant`, and `@ghost`
declarations.

Anything else is refused **by name** — *"the proof backend does not translate an array or a slice"* —
so that a gap in the translator reads as a gap in the translator, and not as a program the prover
disliked. Arrays are the largest absence.

### Installing Why3

Why3 installs through opam and has no Homebrew formula:

```bash
$ opam install why3 alt-ergo
$ why3 config detect
```

A shell that has not run `eval $(opam env)` will not see one that is installed. Z3 works as the
prover too, and Why3 finds it once `why3 config detect` has run.
