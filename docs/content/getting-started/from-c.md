---
title: Coming from C
summary: What translates straight across, what changes shape, and the refusals a C program runs into first.
weight: 40
---

Most of C carries over unchanged. You still choose where a value lives, you still have a pointer,
you still have `sizeof`, and there is no runtime underneath making decisions for you. What changes
is a short list, and this page is that list. Once you have read it, most of the [tour](/tour/) will
confirm what you already expect.

## The map

| C | sysl | |
|---|---|---|
| `int`, `char`, `unsigned` | `int`, `byte`, `uint` | a width is a **type**; only lossless conversions are implicit |
| `T *` | `*T` | the same pointer, with a distinct, searchable spelling |
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

There is no `malloc` or `free`, and no allocation keyword. Writing an ordinary construction where a
`&T` is expected places the object on the heap:

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

`Node(1, None)` is the same expression that would build a plain value; the annotation `&Node` makes
it a reference-counted heap object. When the last reference goes, the object is freed. There is no
call to write, and none to forget.

`weak Node` is the counterpart, and it is the kind of field a C programmer uses constantly. A parent
pointer, a back-link, an entry in an index: in C these are ordinary pointers that work because they
do not own what they point at. `weak` states that explicitly. It does not keep the object alive, and
reading it asks whether the object still exists rather than assuming it does.

## An array converts, and the length comes with it

In C, an array in an expression becomes a pointer to its first element and **the length is lost**.
That is why every C function taking a buffer also takes a count, and why the two can disagree. sysl
keeps the convenience without the loss: an array converts where a view is expected, and the view
carries the length.

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

`[4]int` is the storage; `[]int` is a view of it, three words rather than one: owner, pointer and
length. A function that takes a buffer therefore takes one parameter, and the call reads as it does
in C:

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

`a` is still a `[3]int`, and `zero` cannot be given a wrong length because it is not given one at
all. The explicit `a[..]` writes the same conversion; you need it when you want *part* of the array
rather than all of it, as in `a[1..]` or `a[..<2]`.

Every index through a view is bounds-checked against the length it carries. That is the one cost
sysl adds here, and it catches the out-of-bounds accesses that C's array-to-pointer decay hides.

## Widths are types, and only lossless conversions are implicit

C's integer promotions do not exist: arithmetic on two `byte` values is `byte` arithmetic. A value
converts implicitly only to a type that can hold every value of its own, such as a `byte` assigned
to an `int`:

```sysl
var small: byte = 200

var wide: int = small

print(small + 100, wide + 100)
```

```output
44 300
```

The first sum wraps at 256 because both operands are `byte`. The second is `int` arithmetic because
`wide` is an `int`; assigning `small` to it widened the value implicitly, since every `byte` fits in
an `int`. The opposite direction can lose information, so it is refused:

```sysl
var count: int = 300

var low: byte = count

print(low)
```

```error
cannot initialize 'low': declared byte but the value is int
```

There are no rank or signedness rules to recall: a conversion happens implicitly only when it is
exact, and every other conversion is written. [Values](/tour/values/) covers the whole family of
widths, which is open-ended — `u12` and `i5` are valid types.

## A string is not a `char *`

```sysl
var s = "héllo"

print(s.len, s.chars.count())
```

```output
6 5
```

Six **bytes** and five **characters**. A `string` is validated UTF-8, and it carries its length
rather than ending at a NUL. `s.len` counts bytes, because indexing and slicing work in bytes;
`s.chars` walks Unicode scalar values. A byte and a character are different types, so the two are
never confused.

When a C function needs a C string, `cstring(s)` builds one: a NUL-terminated copy and a pointer
to it. [Strings](/reference/strings/) covers both directions.

## A tagged union is one construct

```sysl
enum Shape
    Circle(r: real)
    Square(side: real)

area(s: Shape) -> real = s match
    Circle(r)  -> r * r
    Square(a)  -> a * a

print(area(Circle(2)), area(Square(3)))
```

```output
4 9
```

In C this is a struct holding a tag and a union, and nothing checks that the tag you branched on
matches the member you read. Here the tag and the payload are one value, the payload is reachable
only through the arm that matched it, and a `match` missing an arm is a compile error rather than a
silent fall-through. `Circle(2.0)` constructs a `Shape` directly; no `Shape.` prefix is needed,
because a variant name belongs to its enum.

## The pointer is still there

`*T` is C's pointer with C's rules, and it is deliberately the most conspicuous of the four to
write:

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

`null` applies to `*T` alone. Field access through a pointer uses the ordinary dot — `p.field` is
C's `p->field` — and it is unchecked, exactly as in C. That is what a raw pointer is for: talking to
hardware, to a foreign library, and to memory you manage yourself, without the compiler
intervening.

`sizeof` and `alignof` take any type, not only a name, and report the layout the target actually
uses.

## Two diagnostics worth knowing early

A reference is never null, so there is no null case to test for:

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

C's `T *` answers two questions at once — *where is it* and *is there one* — and sysl separates
them. `&T` answers the first, `Option[&T]` answers both, and `*T` remains for when you want C's
behaviour.

A reference also does not support arithmetic:

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

Walking memory is the job of a `*T`, and most of the time a `[]T` is the better choice: it is the
pointer-and-length pair C makes you carry by hand, with the bounds check built in.

## What C has and sysl does not

- **A `static` inside a function.** There is no per-function persistent storage, and no
  module-level `var` either; [modules](/reference/modules/) explains why. State that outlives a call
  goes in a struct that the caller owns and passes in, which is what a C program does anyway as
  soon as it needs two instances of that state.
- **An untagged union.** A union whose discriminant is stored elsewhere cannot be written. Where
  there is a discriminant, use an `enum`; to reinterpret bytes, use `ptr_cast`, described in
  [memory](/reference/memory/).
- **A C bitfield declarator.** sysl writes it as `x: u5 in u32`, which is C's `unsigned x : 5`: the
  field's width and the type C declares it with. The struct is laid out by the target's C rule, so
  it matches the C header it came from. `_: u4 in u32` is the unnamed `unsigned : 4`, and `_: in u32`
  the zero-width `unsigned : 0`. Where no C header is involved, a `u5` field inside a `@packed`
  struct occupies exactly five bits, and the two things C leaves to the implementation — which end
  the bits fill from, and whether a field may cross a byte — are fixed by the language. `volatile`
  applies to both forms and means what it means in C: a volatile access of the containing unit, so a
  write is a read-modify-write of it. [Attributes](/reference/attributes/) has the rules.
- **`goto`.** It is not a reserved word. A labelled `break` or `continue` covers the case that
  actually arises, which is leaving a nested loop.
- **The preprocessor.** There are no macros, no textual inclusion and no include guards. `const`
  replaces a `#define` of a value, a module replaces a header, and `#if` handles platform gating;
  [attributes](/reference/attributes/) lists the closed set of symbols it may test.

## What sysl checks that C does not

- Every index, against the length.
- Every `match`, for a missing arm.
- Every **numeric** conversion that could lose information. Such a conversion happens only where
  it is written.
- Every contract you write with `require` and `ensure`, in every build. No release mode drops them,
  and no flag removes a bounds check.
- Every reference's lifetime: a `&T` is counted and freed automatically rather than by hand, so
  when to free it is not a question your code has to answer.

Every implicit conversion sysl performs preserves information. A numeric value widens to a type that
holds all of its values. An ordinary construction becomes a `&T` where one is expected, and a `&x`
becomes a trait object where one is expected. An array literal written where a `[]T` is expected
gets storage of its own. A `[]T` is accepted where a `[]const T` is expected, the direction that
removes a permission, never the one that grants it. None of these alters the value being
converted, which C's implicit conversions can.

## You do not have to start over

You can adopt sysl one file at a time, and calls work in both directions across the boundary.

**sysl on top, your C underneath.** An `extern` declares a symbol the linker already has, and
`@link("z")` names the library that provides it. No bindings are generated and no header is parsed:
you write declarations for what you use, and a declaration nothing calls adds nothing to the output.
A `.c` file placed in any module of the tree is compiled with it, which is how you reach what only a
header can express — a macro, a `sizeof` only the header knows, an untagged union.

**Your C on top, sysl underneath.** `@export` publishes a plain, unmangled symbol using your
platform's C calling convention, so a struct passes by value exactly as it does to one of your own C
functions. `sysl build-c` then writes a static archive and a C header for your existing build to
use:

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

Exported signatures use C-compatible types — scalars, pointers, function pointers — because those
are what a C prototype can express. The usual structure is a **boundary file**: one module that
holds the handful of functions the C side calls, with everything behind it written in idiomatic
sysl. A C++ or Rust library exposes a C interface the same way, and
[the FFI reference](/reference/ffi/) lists everything that may cross the boundary.

---

Next: the [tour](/tour/), which starts from the beginning and does not assume you have read this
page.
