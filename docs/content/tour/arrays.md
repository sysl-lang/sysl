---
title: Arrays and slices
summary: One type for storage, one for a view of it — and every index checked.
weight: 60
---

sysl has two sequence types where a lot of languages have one, and the split is Go's:

| type | what it is |
|---|---|
| `[N]T` | a **fixed array**: `N` elements, a value, no header, `N` known while compiling |
| `[]T` | a **slice**: a view of elements someone owns — `{ owner, pointer, length }` |

An array *is* its elements, so copying one copies all of them. A slice *names* elements that live
somewhere else. A function that only reads takes the view and stops caring where the bytes are.

Both carry a length, so **every index is checked**.

## Writing one down

A literal fixes the length from how many elements it has:

```sysl
var primes = [2, 3, 5, 7]

print("count:", primes.len, "third:", primes[2])
```

```output
count: 4 third: 5
```

`primes` is a `[4]int` — the count is part of the type, not a field of the value. `.len` is a
property, so no parentheses, and on a fixed array it is a constant that costs nothing to read.

A declaration with no initializer starts at the type's zero value, which is what a scratch buffer
wants. A repeat `[value; count]` fills every slot with one value:

```sysl
var counters: [4]int
var ones = [1; 5]

counters[0] = 9

print("zeroed:", counters[1], "set:", counters[0])
print("repeat:", ones.len, ones[4])
```

```output
zeroed: 0 set: 9
repeat: 5 1
```

The zero-value form is not special to arrays — it is a `var` with a type and no `=`, and it means
"the zero of this type" for any type that has one. A type holding a `&T` has no zero, since a
reference always points at something live, so that one asks you for an initializer.

## An array is a value

Which means the thing the [structs chapter](/tour/structs/) said about `b = a` is true here too:

```sysl
var a = [1, 2, 3]
var b = a

b[0] = 99

print("a:", a[0], "b:", b[0])
```

```output
a: 1 b: 99
```

Three elements were copied. That is fine for four integers and less fine for four thousand, which is
the reason the other type exists.

## A slice is a view

Subscript with a range instead of an index and you get a slice. It does not copy — it names the same
elements:

```sysl
var data = [10, 20, 30, 40, 50]
var middle = data[1..<4]

middle[0] = 99

print("view:", middle.len, middle[0])
print("original:", data[1])
```

```output
view: 3 99
original: 99
```

Writing through the view wrote through to the array, because there is only one set of elements.

Both ends of the range are optional, and the two range operators keep the meanings they have
everywhere else in the language — `..` includes its high end, `..<` excludes it:

```sysl
sum(xs: []int) -> int
    var total = 0

    for x in xs do total += x

    total

var data = [1, 2, 3, 4, 5]

print(sum(data[..]), sum(data[1..3]), sum(data[..<2]), sum(data[3..]))
```

```output
15 9 3 9
```

`data[..]` is the whole thing as a slice, `data[1..3]` is elements 1 through 3, `data[..<2]` is the
first two, and `data[3..]` runs to the end. "The first `n`" is `xs[..<n]`, which matches the
`for i in 0..<n` that walks it — the two must agree, and that is why the inclusive `..` is the one
that looks unusual rather than the one that is wrong.

There is no `xs[lo..<]`: "through the last" is not a question of including or excluding anything, so
the open form is `xs[lo..]` and the other spelling is refused rather than quietly meaning the same.

## A view that may not be written

`[]const T` is a slice whose elements may not be written **through it**. It is the signature a
function that only reads should have:

```sysl
scale(xs: []const int)
    xs[0] = 99

var data = [1, 2, 3]

scale(data[..])
```

```error
views elements it may not write
```

The `const` is a property of the *view*, not of the element type — which is why it sits after the
brackets, and why a `[]T` is accepted anywhere a `[]const T` is wanted but never the other way
round. Giving up the ability to write is a promise the caller can always make; inventing one is what
the type exists to stop.

## Iterating

`for x in xs` binds a **copy** of each element, which is what value semantics mean. To change
elements, walk the indices:

```sysl
var xs = [1, 2, 3]

for x in xs do print("saw:", x)

for i in 0..<xs.len
    xs[i] *= 10

print("scaled:", xs[0], xs[1], xs[2])
```

```output
saw: 1
saw: 2
saw: 3
scaled: 10 20 30
```

The loop evaluates its sequence once, so a slice written directly in the header lives for the whole
loop rather than being rebuilt each step.

## A length worked out while running

Every form so far fixes its length in the type, and that is exactly what a program reading a file
cannot do — the size is in the header, and the header is read by the code that needs the buffer.

A length that is not in the type is what `[]T` already is, so this needs no new spelling. The
declared type decides which reading you get:

```sysl
squares(n: usize) -> []int
    var xs: []int = [0; n]

    for i in 0..<n do xs[i] = int(i) * int(i)

    xs

var s = squares(5)

print("len:", s.len, "last:", s[4])
```

```output
len: 5 last: 16
```

Under a `[N]T` the count must be constant; under a `[]T` it can be any expression, and the storage
that gets made belongs to the view. That is the whole mechanism — a slice's owner word is an
ordinary counted reference, so storage a function makes this way can be **returned**, and a decoder
that learns its size from what it is decoding does not have to ask its caller to size a buffer whose
size is in a header the caller has not read.

## Growing one

`Buf[T]` is the growable sequence, and the interesting thing about it is that it is **ordinary sysl
in the library** — a `[]T` for the storage, a count of how much is live, and methods. The section
above is what makes that possible.

Nothing in the language reaches it, so a program that wants one asks:

```sysl
import sysl.buf.{Buf, buf}

var b: &Buf[int] = buf()

for i in 1..4 do b.push(i * i)

print("len:", b.len(), "first:", b[0], "cap:", b.cap() >= b.len())

b.pop() match
    Some(v) -> print("popped:", v)
    None -> print("empty")
```

```output
len: 4 first: 1 cap: true
popped: 16
```

Two details worth catching. `b.len()` has parentheses where `xs.len` did not: the built-in length is
a property of a type the compiler knows, while `Buf`'s is an ordinary method. And `b[0]` works
because `Buf` implements the `Index` trait — the same syntax, reaching a call rather than an address.

`pop` returns an `Option` because taking from an empty buffer is a question a caller asks on purpose,
while `b[i]` past the end panics, because that is a mistake in the program rather than a value it
meant to handle.

Handing the elements to something that takes a slice is `view()`:

```sysl
import sysl.buf.{Buf, buf}

total(xs: []const int) -> int
    var t = 0

    for x in xs do t += x

    t

var b: &Buf[int] = buf()

for i in 1..5 do b.push(i)

print("total:", total(b.view()))
```

```output
total: 15
```

## What a view keeps alive

Taking a slice **retains whatever owns the elements**, and dropping the slice releases it. So a view
cannot dangle: the storage it names is alive for exactly as long as the view is.

That holds even across a growth. A `push` that runs out of room allocates fresh storage and copies —
and a view taken before that keeps the *old* storage alive and goes on showing what it was made
from. It does not become invalid, which is the half of Go's behaviour sysl could not reproduce even
if it wanted to. What it does not do is grow with the buffer; take the view again to see more.

Where there is nothing to keep alive — a view of a string literal, or of a region behind a `*T` —
the owner word is simply null and the counting compiles away.

---

Next: [strings](/tour/strings/) — the same three words, with a guarantee added and an operation
taken away.
