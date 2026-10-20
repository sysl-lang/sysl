---
title: Functions
summary: An expression after `=`, or an indented block. Defaults, names at the call, and closures.
weight: 30
---

## Two bodies

A function is a name, a parameter list, a return type, and a body. When the body is a single
expression, it follows `=`:

```sysl
add(a: int, b: int) -> int = a + b

print("3 + 4 =", add(3, 4))
```

```output
3 + 4 = 7
```

A longer body is indented under the header, without the `=`. The last expression is the function's
result, so no `return` is needed; `return` exists for leaving early:

```sysl
frame_kind(payload: int) -> string
    var total = payload + 18

    if total > 1518 then "jumbo"
    else "standard"

print(frame_kind(9000), frame_kind(46))
```

```output
jumbo standard
```

An optional `end` marker closes a long declaration and names it, which helps when the start of the
block is off the screen. A function that returns nothing omits the return type.

A parameter may have a default. Defaults are filled from the right, so the number of arguments a call
passes decides how many defaults it uses:

```sysl
tag(text: string, open: string = "[", close: string = "]") -> string = open + text + close

print(tag("info"), tag("warn", "<"), tag("error", "{", "}"))
```

```output
[info] <warn] {error}
```

A default is an expression evaluated at each call, not a single value computed once and shared. This
matters when a default allocates: every call gets its own object.

An argument may also be passed by name, which keeps a call with several literal arguments readable:

```sysl
window(x: int = 0, y: int = 0, width: int = 80, height: int = 24) -> string
    str(width) + "x" + str(height) + " at " + str(x) + "," + str(y)

print(window(width = 132, y = 10))
```

```output
132x24 at 0,10
```

A positional argument may not follow a named one: once a call starts naming arguments, the rest must
be named too.

A name may be declared more than once. The arguments of a call, their number and their types,
decide which declaration it uses:

```sysl
show(x: int) -> string = s"int $x"
show(x: string) -> string = s"str $x"

print(show(1))
print(show("a"))
```

```output
int 1
str a
```

The return type does not take part. Two declarations that differ only in their result could not be
told apart by any call, so the second is rejected where it is declared. The
[reference](/reference/declarations/#overloading) has the full rules, including what happens when a
call matches two declarations.

## Closures

A parameter that takes a function has an arrow type. A single parameter type needs no parentheses;
zero or several need them:

```sysl
apply(f: int -> int, x: int) -> int = f(x)
combine(f: (int, int) -> int, a: int, b: int) -> int = f(a, b)

print(apply(x -> x + 1, 5), combine((a, b) -> a * b, 6, 7))
```

```output
6 42
```

A closure body can use the names around it, which is what distinguishes it from a plain function
pointer. Using a name captures it; there is no capture list to write:

```sysl
apply(f: int -> int, x: int) -> int = f(x)

var factor = 10

print(apply(x -> x * factor, 5))
```

```output
50
```

If you come from Kotlin, Swift or JavaScript, one rule differs from what you expect. **Capturing a
value copies it.** The closure has its own copy, and writing to it does not change the original:

```sysl
each3(f: int -> unit)
    for i in 0..<3 do f(i)

var total = 0

each3(i -> total += i * i)
print("the outer total is still", total)
```

```output
the outer total is still 0
```

`total` is an `int`, which is a value, so the closure received a copy and incremented that. This is
not a rule specific to closures. It is the same copy behaviour a by-value parameter or a struct field
has, applied when the closure is created.

To accumulate across calls, capture something that *is* shared. A `&T` is a counted reference, so
capturing one retains the reference instead of copying the object, and every holder sees the same
object:

```sysl
import sysl.buf.{Buf, buf}

each3(f: int -> unit)
    for i in 0..<3 do f(i)

var seen: &Buf[int] = buf()

each3(i -> seen.push(i * i))
print("collected:", seen.len())
```

```output
collected: 3
```

Which behaviour you get depends on the *type* of the captured variable, not on anything written where
it is captured. It is the same question the [memory chapter](/tour/memory/) answers, and that chapter
explains this rule in full.

The compiler also determines how long a closure must live. A closure that does not outlive its frame
is inlined and needs no allocation. One that escapes, by being stored in a field or returned, is
placed in a counted heap box. The compiler makes this choice for you.

---

Next: [structs and methods](/tour/structs/), which give a type data and behaviour.
