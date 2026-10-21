---
title: Arrays and slices
summary: One type for storage, one for a view of it — and every index checked.
weight: 60
---

sysl has two sequence types where many languages have one, divided the same way as in Go:

| type | what it is |
|---|---|
| `[N]T` | a **fixed array**: `N` elements, a value, no header, `N` known while compiling |
| `[]T` | a **slice**: a view of elements someone owns — `{ owner, pointer, length }` |

An array *is* its elements, so copying one copies all of them. A slice *refers to* elements stored
elsewhere. A function that only reads takes a slice and does not need to know where the elements
live.

Both carry a length, so **every index is checked**.

## Array literals

A literal's length is the number of elements it has:

```sysl
var primes = [2, 3, 5, 7]

print("count:", primes.len, "third:", primes[2])
```

```output
count: 4 third: 5
```

`primes` is a `[4]int`: the count is part of the type, not a field of the value. `.len` is a
property, so it takes no parentheses, and on a fixed array it is a compile-time constant.

A declaration with no initializer starts at the type's zero value, which suits a scratch buffer. A
repeat literal `[value; count]` fills every slot with one value:

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

The zero-value form is not specific to arrays. A `var` with a type and no `=` holds the zero of that
type, for any type that has one. A type holding a `&T` has no zero, since a reference always points
to a live object, so such a declaration needs an initializer.

What the [structs chapter](/tour/structs/) said about `b = a` applies to arrays too:

```sysl
var a = [1, 2, 3]
var b = a

b[0] = 99

print("a:", a[0], "b:", b[0])
```

```output
a: 1 b: 99
```

All three elements were copied. That is inexpensive for three integers and expensive for four
thousand, which is why the second type exists.

## A slice is a view

Subscripting with a range instead of an index produces a slice. It does not copy; it refers to the
same elements:

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

Writing through the view changed the array, because there is only one set of elements.

Both ends of the range are optional, and the two operators mean what they mean elsewhere:
`data[1..3]`, `data[..<2]`, `data[3..]`. An array used where a slice is expected is a view of itself,
so `data` on its own also works. There is no `xs[lo..<]`: "up to the end" excludes nothing, so the
open form is `xs[lo..]`.

`[]const T` is a slice whose elements may not be written **through it**. It is the right parameter
type for a function that only reads:

```sysl
scale(xs: []const int)
    xs[0] = 99

var data = [1, 2, 3]

scale(data)
```

```error
this element belongs to a '[]const int', which views elements it may not write, so there is nothing to assign through. Elements you may write are elements of your own: copy them into a '[]int' first
```

`const` belongs to the *view*, not to the element type, which is why it follows the brackets. A
`[]T` is accepted wherever a `[]const T` is expected, but not the reverse: giving up write access is
always safe, and gaining it is what the type prevents.

`for x in xs` binds a **copy** of each element, as value semantics require. To change elements,
iterate over the indices:

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

The loop evaluates its sequence once, so a slice written directly in the loop header lives for the
whole loop rather than being rebuilt at each step.

## Sizes known only at run time

Every form so far fixes its length in the type, which a program reading a file cannot do: the size
is in the file's header, and only the code that reads the header knows it.

A length that is not part of the type is exactly what `[]T` is, so no new syntax is needed. The
declared type decides which kind of storage is made:

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

Under a `[N]T` the count must be a constant; under a `[]T` it can be any expression, and the storage
created belongs to the slice. A slice's owner word is an ordinary counted reference, so storage made
this way can be **returned**. A decoder that learns a size from the data it decodes can allocate the
buffer itself, instead of asking its caller to size a buffer from a header the caller has not read.

## Growable buffers

`Buf[T]` is the growable sequence. It is **ordinary sysl in the standard library**: a `[]T` for the
storage, a count of the elements in use, and methods. The run-time sizing in the previous section is
what makes that possible.

`Buf` is not built into the language, so a program imports it:

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

Two details. `b.len()` takes parentheses where `xs.len` did not: the built-in length is a property
of a type the compiler knows, while `Buf`'s is an ordinary method. And `b[0]` works because `Buf`
implements the `Index` trait; the subscript syntax calls a method instead of computing an address.

`pop` returns an `Option` because removing from an empty buffer is a case a caller checks
deliberately. `b[i]` past the end panics, because that is a bug in the program rather than a value
to handle.

`b.view()` passes the elements to a function that takes a slice.

Taking a slice **retains whatever owns the elements**, and dropping the slice releases it. A view
therefore cannot dangle: the storage it refers to lives exactly as long as the view.

That holds across growth too. A `push` that runs out of room allocates new storage and copies the
elements, and a view taken before the `push` keeps the *old* storage alive and continues to show
what it was created from. It stays valid, but it does not grow with the buffer; take a new view to
see the added elements.

Where there is nothing to keep alive, as with a view of a string literal or of a region behind a
`*T`, the owner word is null and the counting is compiled away.

---

Next: [strings](/tour/strings/), which have the same three words as a slice, with a guarantee added
and an operation removed.
