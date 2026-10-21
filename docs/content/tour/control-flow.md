---
title: Control flow
summary: Every construct yields a value, including the loops.
weight: 20
---

## `if` yields a value

sysl has no ternary operator, because `if` is already an expression:

```sysl
var n = 55

var label = if n % 2 == 0 then "even" else "odd"
print(n, "is", label)
```

```output
55 is odd
```

Written across several lines with `elif`, it reads as ordinary control flow. It is the same
expression either way; when nothing uses the value, it is discarded:

```sysl
battery(percent: int) -> string
    if percent >= 80 then "full"
    elif percent >= 40 then "normal"
    elif percent >= 15 then "low"
    else "critical"

print(battery(91), battery(72), battery(15), battery(4))
```

```output
full normal low critical
```

An `if` whose value is used needs an `else`. Without one, the missing branch has no value, and the
compiler reports it rather than supplying a zero.

`match` arms test a literal, a `|`-separated set of alternatives, or a range, and `else` catches the
rest:

```sysl
size(n: int) -> string
    n match
        0 -> "empty"
        1 | 2 | 3 -> "small"
        4..99 -> "medium"
        else "large"

print(size(0), size(2), size(55), size(1000))
```

```output
empty small medium large
```

A `match` used for its value must be **exhaustive**. That is the role of the `else` above: `int` has
more values than the arms name, and without it some inputs would have no result. Where the arms
already cover every case, as with the variants of an enum, no `else` is needed, and adding one is a
mistake: it would stop the compiler from reporting a variant added later and not handled.

## Loops

`while` and `for` work as they do in most languages. A range is written `a..b` when inclusive and
`a..<b` when exclusive, the same two operators used throughout the language:

```sysl
var sum = 0
for i in 1..10 do sum = sum + i

var doubled = 0
for i in 0..<5 do doubled += i * 2

print("sum:", sum, "doubled:", doubled)
```

```output
sum: 55 doubled: 20
```

`do` puts the body on the same line. An indented body needs no `do`:

```sysl
var attempts = 3

while attempts > 0
    print("connecting, attempts left:", attempts)
    attempts -= 1

print("giving up")
```

```output
connecting, attempts left: 3
connecting, attempts left: 2
connecting, attempts left: 1
giving up
```

A `do … while` loop tests its condition at the end, for a body that must run at least once, and a
`continue` inside one jumps to that test; [the reference](/reference/statements/) covers both.

A loop with no condition is written `loop`:

```sysl
var n = 0

loop
    n += 1
    if n >= 4 then break

print("stopped at", n)
```

```output
stopped at 4
```

Loops can also produce a value, which replaces the common pattern of setting a flag variable inside
the loop and testing it afterwards. `break` carries a value out, and an `else` block after the body,
as in Python, supplies the value when the loop finishes *without* breaking:

```sysl
first_multiple(of: int, upto: int) -> int
    for i in 1..upto
        if i % of == 0 then break i
    else -1

print(first_multiple(7, 20), first_multiple(23, 20))
```

```output
7 -1
```

Read the `else` as "if the loop ran to completion". Every `break` value and the `else` value share
one type, which becomes the type of the loop. Without an `else`, a loop that completes normally
yields nothing, so a `break` that carries a value requires one, and the compiler reports it if it is
missing.

A `loop` takes no `else`, because it has no normal completion. As a result, a `loop` that nothing
breaks out of never finishes, so it can be the last statement of a function that returns a value,
with nothing after it. `while true` does not have this property: the compiler does not evaluate the
condition, so it treats such a loop as one that might finish and the code after it as reachable.

A `for` iterates over a collection directly:

```sysl
var primes = [2, 3, 5, 7, 11]
var total = 0

for p in primes do total += p

print("count:", primes.len, "total:", total)
```

```output
count: 5 total: 28
```

When the stepping is something a range cannot describe, there is a three-clause `for`, whose `;` is
the only place that token appears in the language; [the reference](/reference/statements/) covers it.

---

Next: [functions](/tour/functions/), which have appeared in the examples from the start.
