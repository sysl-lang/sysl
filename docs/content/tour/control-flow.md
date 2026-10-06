---
title: Control flow
summary: Everything yields a value — including the loops, which is less strange than it sounds.
weight: 20
---

## `if` yields a value

There is no ternary operator, because there is no need for one:

```sysl
var n = 55

var label = if n % 2 == 0 then "even" else "odd"
print(n, "is", label)
```

```output
55 is odd
```

Written across several lines it uses `elif` and reads as ordinary control flow. It is the same
expression either way — the value is simply unused when nothing wants it:

```sysl
grade(score: int) -> string
    if score >= 90 then "excellent"
    elif score >= 70 then "fine"
    elif score >= 50 then "scraped it"
    else "no"

print(grade(91), grade(72), grade(50), grade(12))
```

```output
excellent fine scraped it no
```

An `if` being *used* for its value needs an `else`. Without one the open branch has no value to
give, and the compiler says so rather than inventing a zero.

Arms match a literal, a `|`-separated set of alternatives, a range, or fall through to `else`:

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

A `match` used for a value has to be **exhaustive**. That is what the `else` is doing above: `int`
has more values than the arms name, so without it there would be inputs with no answer. Where the
arms genuinely cover everything — the variants of an enum, say — no `else` is needed and adding one
is a mistake, because it is the thing that would stop the compiler telling you about a variant you
forgot when you add it later.

## Loops

`while` and `for` are the ordinary ones. A range is written `a..b` for inclusive and `a..<b` for
exclusive, which are the same two operators used everywhere else in the language:

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

`do` puts the body on the same line. Indent it instead and the `do` goes away:

```sysl
var countdown = 3

while countdown > 0
    print("t minus", countdown)
    countdown -= 1

print("go")
```

```output
t minus 3
t minus 2
t minus 1
go
```

A `do … while` puts the test at the foot, for a body that has to run before anything is asked, and
a `continue` in one reaches that test — [the reference](/reference/statements/) has both.

A loop with nothing to test is written `loop`, which is what `while true` was always being used to
say:

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

This is the part worth slowing down for, because it replaces a pattern that is otherwise written
with a flag variable. `break` carries a value out, and an `else` block — after the body, as in
Python — supplies the value when the loop finishes *without* breaking:

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

Read the `else` as "and if it ran out". Every `break` value and the `else` value share one type,
which becomes the loop's type. Without an `else`, finishing normally yields nothing — so a
value-carrying `break` needs one, and the compiler will tell you if it is missing.

A `loop` takes no `else`, because it has no normal completion to have one for. That has a
consequence worth knowing: a `loop` that nothing breaks out of never finishes, so it can stand as
the last thing a function owing a value does, with nothing after it to supply that value. `while
true` cannot say this — a condition is an expression the compiler does not evaluate, so a loop
written that way looks like one that might finish and the code after it looks reachable.

A `for` walks a collection directly:

```sysl
var primes = [2, 3, 5, 7, 11]
var total = 0

for p in primes do total += p

print("count:", primes.len, "total:", total)
```

```output
count: 5 total: 28
```

Where the stepping is something a range cannot describe there is a three-clause `for`, whose `;` is
the only place in the language that token appears; [the reference](/reference/statements/) has it.

---

Next: [functions](/tour/functions/), which have been quietly appearing in every example so far.
