---
title: Functions
summary: An expression after `=`, or an indented block. Defaults, names at the call, and closures.
weight: 30
---

## Two bodies

A function is a name, a parameter list, a return type, and a body. When the body is one expression
it goes after `=`:

```sysl
add(a: int, b: int) -> int = a + b

print("3 + 4 =", add(3, 4))
```

```output
3 + 4 = 7
```

When it is more than that, indent it under the header and drop the `=`. The trailing expression is
what the function yields — there is no `return` to write, though `return` exists for leaving early:

```sysl
classify(n: int) -> string
    var doubled = n * 2

    if doubled > 100 then "big"
    else "small"

print(classify(60), classify(3))
```

```output
big small
```

An optional `end` marker closes a long declaration and names it — worth writing when the closing
indentation is off the screen. A function that returns nothing simply says no return type.

A parameter may carry a default, and defaults fill from the right — so what a call writes decides
how many are taken:

```sysl
tag(text: string, open: string = "[", close: string = "]") -> string = open + text + close

print(tag("all"), tag("half", "<"), tag("none", "{", "}"))
```

```output
[all] <half] {none}
```

The default is an expression evaluated at each call, not one value computed once and shared. That
matters as soon as a default allocates: every call gets its own.

An argument may also be given by name, which is what rescues a call site that would otherwise be a
row of anonymous literals:

```sysl
window(x: int = 0, y: int = 0, width: int = 80, height: int = 24) -> string
    str(width) + "x" + str(height) + " at " + str(x) + "," + str(y)

print(window(width = 132, y = 10))
```

```output
132x24 at 0,10
```

A positional argument may not follow a named one — once a call starts naming, it names.

A name may be declared more than once. Which declaration a call means is decided by the arguments it
passes — how many, and what type each is:

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

Never by what they return, though: two declarations differing only in the result have no call that
tells them apart, and the second is refused where it is written. The
[reference](/reference/declarations/#overloading) has the rest, including what happens when a call
fits two of them.

## Closures

A function that takes a function writes the parameter's type with an arrow. One parameter needs no
parentheses; two or more, or none, take them:

```sysl
apply(f: int -> int, x: int) -> int = f(x)
combine(f: (int, int) -> int, a: int, b: int) -> int = f(a, b)

print(apply(x -> x + 1, 5), combine((a, b) -> a * b, 6, 7))
```

```output
6 42
```

A closure body sees the names around it, which is what makes it worth having rather than passing a
plain function pointer. Naming one captures it, and there is no capture list to write:

```sysl
apply(f: int -> int, x: int) -> int = f(x)

var factor = 10

print(apply(x -> x * factor, 5))
```

```output
50
```

This is the one place closures will surprise you if you arrive from Kotlin, Swift or JavaScript, so
it is worth meeting now rather than in a debugger. **Capturing a value copies it in.** The closure
gets its own, and writing to it does not touch the original:

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

Nothing went wrong there. `total` is an `int` — a value — and capturing a value copies it, so the
closure incremented a copy that went away when it did. This is not a special rule for closures; it
is the same copy discipline a by-value parameter or a struct field follows, applied at the moment
the closure is formed.

To accumulate, capture something that *is* shared. A `&T` is a counted reference, so capturing one
retains it rather than copying what it points at, and everybody sees the same object:

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

Which of the two you get is decided by the captured variable's *type*, not by anything written at
the capture site — so it is the same question the [memory chapter](/tour/memory/) is about, and
knowing that chapter is knowing this rule.

The compiler also works out how long a closure needs to live. One that does not outlive its frame
is inlined and costs nothing; one that escapes — stored in a field, returned — is heap-boxed and
counted. You do not choose between them, and you cannot get it wrong.

---

Next: [structs and methods](/tour/structs/) — giving a type some data, and then some behaviour.
