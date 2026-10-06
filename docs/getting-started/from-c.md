---
title: Coming from C
summary: What translates straight across, what changes shape, and the refusals a C program runs into first.
weight: 40
---

Most of C comes across unchanged. You still choose where a value lives, you still have a pointer,
you still have `sizeof`, and there is still no runtime underneath deciding things for you. What
changes is a short list, and this page is that list — read it and the [tour](/tour/) will mostly be
confirming what you already expected.

## The map

| C | sysl | |
|---|---|---|
| `int`, `char`, `unsigned` | `int`, `byte`, `uint` | a width is a **type**; nothing promotes |
| `T *` | `*T` | the same pointer, spelled so you can grep for it |
| `malloc` / `free` | `&T` | construct where a `&T` is expected; the count frees it |
| `T a[N]` | `[N]T` | a fixed array — it converts to a view, and the length comes with it |
| a `T *` plus a length | `[]T` | one value that carries the length |
| `const T *` | `[]const T` | the read-only-ness is in the type and travels with it |
| `char *` | `string` | validated UTF-8, with a length, and no terminator |
| `struct` | `struct` | the same thing |
| a `union` and a tag beside it | `enum` | one construct, and the arms are checked |
| `typedef` | `type`, or [`c type`](/reference/ffi/) | `type` narrows or renames a scalar; a bare alias is refused. `c type` is for a typedef in a *header*, whose width the C compiler answers for |
| `#define MAX 512` | `const MAX: usize = 512` | folded into every use, no storage, usable as an array bound |
| `static` at file scope | `private` | |
| a header | a module | no `#include`, no include guard, no forward declaration |
| `NULL` | `null` | on a `*T` and nowhere else |
| `errno`, a returned `-1` | `Result[T, E]` and `?` | |
| `assert.h` | `assert`, and `require` / `ensure` | |
| `printf` | `print`, and `f"…"` | |
| a function pointer | `*extern(A) -> R` | |
| `sizeof`, `_Alignof`, `offsetof` | `sizeof`, `alignof`, `offsetof` | over any type |
| `volatile T *` | `volatile` on the **field** | the storage is qualified, not the pointer to it |
| `goto` | a labelled `break` / `continue` | |

## The allocation is a construction

There is no `malloc` and no `free`, and no keyword took their place. Writing an ordinary
construction where a `&T` is expected is what puts the object on the heap:

```sysl
struct Node
    value: int
    next: weak Node

var head: &Node = Node(1, None)

print(head.value)
```

```output
1
```

`Node(1, None)` is the same expression that would have built a value; the annotation `&Node` is what
makes it a heap object with a count. When the last reference goes, so does the object — there is no
line to write and no line you can forget to write.

`weak Node` is the other half of the same story, and it is the field a C programmer will reach for
without thinking. A parent pointer, a back-link, an entry in an index: in C those are just pointers,
and the reason they work is that they do not own. `weak` is that, said out loud — it does not keep
the object alive, and reading it is a question rather than an assumption.

## An array converts, and the length comes with it

In C, an array in an expression becomes a pointer to its first element and **the length is gone** —
which is why every C function taking a buffer takes a count beside it, and why the two can disagree.
sysl has the same convenience and none of the loss: an array converts where a view is asked for, and
what it converts to carries the length.

```sysl
var buf: [4]int

buf[0] = 10
buf[1] = 20

var view: []int = buf

print(view.len, view[1])
```

```output
4 20
```

`[4]int` is the storage; `[]int` is a view of it — three words rather than one: owner, pointer,
length. So a function that takes a buffer takes one parameter, and the call reads as it does in C:

```sysl
zero(xs: []int)
    for i in 0..<xs.len do xs[i] = 0

var a = [1, 2, 3]

zero(a)

print(a[0], a[2], a.len)
```

```output
0 0 3
```

`a` is still a `[3]int`, and `zero` cannot be told a wrong length because it was not told one. The
explicit `a[..]` writes the same conversion; what you reach for it for is *part* of the array rather
than all of it — `a[1..]`, `a[..<2]`.

Every index through a view is bounds-checked against the length it carries. That is the one cost
sysl adds here, and it buys the class of bug C's decay makes unfindable.

## Widths are types, and nothing promotes

C's usual arithmetic conversions are absent. `byte` arithmetic is `byte` arithmetic:

```sysl
var small: byte = 200

print(small + 100, int(small) + 100)
```

```output
44 300
```

The first wraps at 256 because both operands are `byte`; the second is `int` arithmetic because the
conversion was written. Neither happened by accident, and no rule about ranks or signedness has to be
recalled to predict which one you got. [Values](/tour/values/) has the family, which is open — `u12`
and `i5` are types you may write.

## A string is not a `char *`

```sysl
var s = "héllo"

print(s.len, s.chars.count())
```

```output
6 5
```

Six **bytes** and five **characters**, and the difference is the point: a `string` is UTF-8 that has
been validated, and it carries its length rather than ending at a NUL. `s.len` is bytes because that
is what indexing and slicing are in; `s.chars` walks scalar values. There is no cheap byte that is
also a character, so the two are never confused.

When you need C's shape at a boundary, `cstring(s)` builds one — a terminator and a pointer, for
passing to a C function. [Strings](/reference/strings/) has both directions.

## A tagged union is one construct

```sysl
enum Shape
    Circle(r: real)
    Square(side: real)

area(s: Shape) -> real = s match
    Circle(r)  -> r * r
    Square(a)  -> a * a

print(area(Circle(2.0)), area(Square(3.0)))
```

```output
4 9
```

In C this is a struct holding a tag and a union, and nothing checks that the tag you branched on is
the member you read. Here the tag and the payload are one value, the payload is reachable only
through the arm that established it, and a `match` missing an arm is a compile error rather than a
silent fall-through. `Circle(2.0)` constructs — there is no `Shape.` to write, because a variant name
belongs to its enum.

## The pointer is still there

`*T` is C's pointer with C's rules, and it is deliberately the ugly one to write:

```sysl
struct Node
    value: int
    next: weak Node

var p: *int = null

print(p == null, sizeof(int), sizeof(Node), alignof(u64))
```

```output
true 4 16 8
```

`null` belongs to `*T` alone. Selection through one is ordinary — `p.field` is C's `p->field`, with
no operator of its own to remember — and it is unchecked, exactly as it is in C. That is what a raw
pointer is *for*: it is how you talk to hardware, to a foreign library, and to memory you are
managing yourself, and the compiler stays out of it.

`sizeof` and `alignof` take any type, not just a name, and answer with what the target actually
lays out.

## Two refusals worth meeting early

A reference is never null, so there is no failure to test for:

```sysl
struct Point
    x: int
    y: int

var r: &Point = null
print(r.x)
```

```error
a &Point always points at a live object — an absent one is Option[&Point]
```

That is the whole trade: C's `T *` answers two questions at once — *where is it* and *is there one*
— and sysl splits them. `&T` is the first, `Option[&T]` is both, and `*T` is still there for when
you want C's answer.

Nor does a reference do arithmetic:

```sysl
struct Point
    x: int
    y: int

var p: &Point = Point(1, 2)
var q = p + 1
print(q.x)
```

```error
'+' needs matching types, got &Point and int
```

Walking memory is a `*T`'s job, and a `[]T` is what you want nine times out of ten — it is the
pointer-and-length pair C makes you carry by hand, with the bounds check that pair was always for.

## What C has and sysl does not

- **A `static` inside a function.** There is no per-function persistent storage, and there is no
  module-level `var` either — [modules](/reference/modules/) has why the keyword is taken. State
  that outlives a call goes in a struct the caller owns and passes in, which is what a C program
  ends up doing anyway the first time it needs two of anything.
- **An untagged union.** A `union` whose discriminant lives somewhere else has no spelling. Where
  the discriminant is real, an `enum` is it; where the point is reinterpreting bytes, that is
  `ptr_cast` and it is in [memory](/reference/memory/).
- **A bitfield *declarator*.** There is no `unsigned x : 5`, and nothing is missing by its absence:
  inside a `@packed` struct a `u5` field already occupies exactly five bits, so a width is written
  where every other width is. What sysl adds is that the two things C leaves to the implementation —
  which end the bits fill from, and whether one may cross a byte — are fixed by the language.
  `volatile unsigned x : 3` carries over as well, and means what it means in C: a volatile access of
  the container, so a write is a read-modify-write of it.
  [attributes](/reference/attributes/) has the rules.
- **`goto`.** Not even a reserved word. A labelled `break` or `continue` reaches the case that
  actually comes up, which is leaving a nested loop.
- **The preprocessor.** No macros, no textual inclusion, no include guards. `const` covers a
  `#define` of a value, a module covers a header, and `#if` covers platform gating —
  [attributes](/reference/attributes/) has the closed set of symbols it may test.

## What sysl checks that C does not

Every index against a length. Every `match` for a missing arm. Every **numeric** conversion, because
none of those is implicit — a value never changes width on its own, whatever the surrounding
expression wants. Every contract you write with `require` and `ensure`, in every build: there is no
release mode that drops them, and no flag that strips a bounds check either. And a reference that is
counted rather than freed by hand, which is the one that turns a class of bug into a class of
question you no longer have to ask.

What *is* implicit is a different kind of thing, and none of it loses information. An ordinary
construction becomes a `&T` where one is expected, a `&x` becomes a trait object where one is
expected, an array form written where a `[]T` is wanted makes storage of its own, and a `[]T` is
accepted where a `[]const T` is wanted — the direction that takes a permission away, never the one
that grants it. Each of those adds an owner or removes a licence. None of them changes a value's
width, which is the implicit conversion C actually has and the one this page is about.

## You do not have to start over

A language is adopted a file at a time or not at all, and both directions across the boundary are
open.

**sysl on top, your C underneath.** An `extern` declares a symbol the linker already has, and
`@link("z")` names the library that resolves it. Nothing is generated and no header is parsed — you
write the declarations you use and no more, and one nothing calls costs the output nothing. A `.c`
file dropped in any module of the tree is compiled with it, which is how the parts a header hides — a
macro, a `sizeof` only the header knows, an untagged union — get reached at all.

**Your C on top, sysl underneath.** `@export` publishes a plain, unmangled symbol, entered under your
machine's C convention — so a struct crosses it by value exactly as it does one of your own functions
— and `sysl build-c` writes a static archive and a C header for your existing build to consume:

```sysl
module mylib

@export("mylib_add")
add(a: i32, b: i32) -> i32 = a + b
```

```text
$ sysl build-c mylib -o libmylib.a
wrote libmylib.a
wrote libmylib.a.h
```

```c
#include "libmylib.a.h"

int main(void) { return mylib_add(2, 3); }
```

```text
$ clang main.c libmylib.a -o app
```

The exported signatures are the C-shaped ones — scalars, pointers, function pointers — because that
is what a C prototype can say. What you write is a **boundary file**: one module whose job is the
surface, holding the handful of functions the C side calls, with everything behind it written in
whatever shapes sysl prefers. That is the same facade a C++ or Rust library grows for the same
reason, and [the FFI reference](/reference/ffi/) has the whole of what may cross.

---

Next: the [tour](/tour/), which starts from the beginning and does not assume you read this.
