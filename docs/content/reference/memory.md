---
title: Memory
summary: The three modes, ARC, weak references, places and `ref`, escape analysis, the raw tier, and device memory.
weight: 70
---

Every value in a sysl program lives in one of three ways, and the declaration that names it is what
says which. This page is the whole of that model: what each mode costs, what it guarantees, how a
reference is made, when storage moves without being asked, and where the guarantees stop.

**If you do not write `*T`, you cannot segfault.** The safe subset — values, references, weak
references, arrays, and slices — has no use-after-free, no null dereference, no out-of-bounds access,
and no dangling pointer. The single unsafe primitive is the raw pointer, and it is **greppable**: a
program's exposure to C's hazards is exactly the lines with a `*` in the type. A driver has them and
an application does not, and you can tell which by looking.

## The three modes

| mode | what it is | who frees it | needs an allocator |
|---|---|---|---|
| `T` | a value — on the stack, in a register, or inline in something bigger | nobody; it goes when its frame or its container does | no |
| `&T` | a counted reference to a heap object | the compiler, when the last reference goes | to **make** one |
| `*T` | a raw machine pointer | you | no |

```sysl
struct Point
    x: int
    y: int
end Point

var here = Point(1, 2)
var shared: &Point = Point(3, 4)
var raw: *Point = &here

print(here.x, shared.x, raw.x)
```

```output
1 3 1
```

**The choice is per declaration, not per type.** The same `struct Point` is a value in the first
line, a heap object in the second, and a pointer's target in the third. This is C's arrangement
rather than Swift's or Scala's, where a type is `struct` or `class` once and for all, and systems
code depends on it: a page-table entry, a temporary in a loop, and a shared configuration object may
all be the same type held three ways.

**Value is the unmarked default, and the kernel is why.** A module that has given up the allocator
cannot *make* a reference. If the reference were the bare default, the unmarked spelling would be
the one that is illegal exactly where value semantics matter most — every kernel struct would need a
mark, and would hit "references need an allocator" on its most natural form. With value as the
default, `T` is usable in both worlds, `*T` covers pointers, and `&T` appears in allocator-free code
only where something else handed one over.

## `T` — values

A value lives inline: in a stack slot, in a register, or embedded in the struct or array that
contains it. **Assignment and argument passing copy it**, exactly as in C.

```sysl
struct Counter
    n: int
end Counter

var a = Counter(1)
var b = a

b.n = 99

print(a.n, b.n)
```

```output
1 99
```

There is **no move semantics and no use-after-move**. `a` is still a whole `Counter` after `b = a`,
and it always will be — the concept of a value that has been given away does not exist here, so
neither does the class of error built on it.

A value containing a `&T` field is still a value: copying it **retains** that field, so the copy is
independently safe and the original is untouched. That is the one thing a copy does beyond moving
bytes, and it is what makes "values are simple" true rather than approximately true.

Values need no allocator and are always safe.

## `&T` — counted references

A `&T` is a reference to a heap object managed by **ARC**: the compiler emits the retains and
releases, and the object is destroyed the moment the last strong reference to it goes away. It is:

- **shared and freely aliased** — many references may point at one object;
- **mutable through any alias** — there is no borrow checker and no exclusivity rule;
- **non-null** — a `&T` always points at a live object;
- **automatically managed** — no `free`, and no place to forget one.

```sysl
struct Counter
    n: int
end Counter

var a: &Counter = Counter(1)
var b = a

b.n = 99

print(a.n, b.n, a == b)
```

```output
99 99 true
```

Compare that with the value example above: **the same two lines, and the declared mode is the only
difference.** `b = a` copied a `Counter` there and made a second reference here.

Two live mutable aliases is what Rust exists to prevent, and permitting them is the deliberate
trade. Reference counting pays for it at run time — a retain here, a release there — and buys a
language with no lifetimes, no borrow checker, and nothing to prove to a checker as you write.

**References compare by address.** `==` and `!=` on a reference or a pointer ask whether the two
name the same object, which is the only question a bare address can answer, and there is no ordering
on either.

### There is no allocation keyword

A reference is made by writing an ordinary construction **where a `&T` is expected**. The expectation
is what puts the object on the heap:

```sysl
struct Process
    id: int
    priority: int
end Process

spawn(id: int) -> &Process = Process(id, 0)

var p = spawn(7)
var q: &Process = Process(8, 1)
var v = Process(9, 2)

print(p.id, q.id, v.id)
```

```output
7 8 9
```

`Process(id, 0)` in the body allocates because the return type says `&Process`. `Process(8, 1)`
allocates because the annotation says so. `Process(9, 2)` does not, because nothing asked — with no
expectation at all, a construction is a value.

No `new`, no `malloc`, no `Rc::new`, no `.clone()`, and no wrapper type. The positions that fix the
expectation are the ones that already state a type for generic inference:

| position | example |
|---|---|
| a declared local | `var p: &Point = Point(1, 2)` |
| a parameter | `f(p: &Point)`, called as `f(Point(1, 2))` |
| a return type | `-> &Point`, with a construction as the result |
| a struct field | `Item("a", Node(1))` where the field is `&Node` |
| an enum variant's payload | `Some(Node(1))` where the payload is `&Node` |

An element of an array or slice, a part of a tuple, a generic argument, and a parameter of a callable
type all ask the same way. **The rule is about the types and not about the syntax**: a `T` written
where a `&T` is expected goes on the heap, whatever produced it.

A prefix `&` on a construction was considered as an explicit mark and not taken — it would collide
with address-of, which is a different operation producing a different type.

### The expectation reaches each branch

An `if`, a `match`, and a loop yield their value through their branches, so a `&T` expectation
reaches **each branch on its own** rather than the expression as a whole:

```sysl
struct Point
    x: int
    y: int
end Point

var origin: &Point = Point(0, 0)
var far = true
var p: &Point = if far then Point(9, 9) else origin

print(p.x, p.y)
```

```output
9 9
```

That is what lets a value branch and an already-reference branch meet at `&Point`. Boxing the whole
expression instead would fail, because a branch that is already a `&Point` cannot un-become a value —
something that is already a reference passes through untouched.

It is also what lets a scalar be referenced without `int` needing a constructor of its own:

```sysl
var n: &int = 41

print(*n + 1)
```

```output
42
```

### A reference is never null

There is no null in the safe subset and no way to make one, so there is no null dereference to
diagnose. Something that may be absent is an `Option[&T]`, and getting at it is a `match`:

```sysl
struct Item
    label: string
end Item

show(o: Option[&Item]) -> string
    o match
        Some(i) -> i.label
        None    -> "(none)"

var it: &Item = Item("a")

print(show(Some(it)), show(None))
```

```output
a (none)
```

The `match` is not ceremony around a null check. It is the only route to the item, so the absent case
cannot be forgotten rather than merely being unwise to forget.

### What a heap object costs

Every ARC object carries **three header words**: the strong count, a pointer to the function that
destroys it, and a weak count. Release decrements the strong count; at zero it calls through the
hook, which releases whatever the payload holds and returns the storage to the heap the object came
from.

Putting the destructor behind a hook rather than inline at each release site is what makes letting go
of a reference **type-erased** — one instruction sequence, no static type. Slices need exactly that,
since a `[]T` gives no clue what type of object its owner word points at.

One word per object buys three things:

- **ARC works the same everywhere.** A module that never allocates can still retain and release,
  because the free path calls back into the heap the object came from. The operations are a few
  instructions and depend on no runtime.
- **Several heaps coexist.** A kernel heap, a server's heap, and an arena are different allocators,
  and an object frees itself into the one that made it, wherever it is dropped.
- **There is no boundary rule to learn.** Ownership crosses an allocator-free edge like any other
  value.

**Teardown is iterative, so depth is bounded.** Destroying the head of a long chain of references
would recurse one frame per node if a destructor called the next destructor. It does not: an object
whose count reaches zero is pushed onto a worklist, reusing its now-dead refcount slot as the link,
and the first release to hit zero drains the list in a loop. A structure of any depth comes apart in
O(1) stack. The worklist is **per thread**, because it is scratch space a drain uses rather than
state anything shares.

The header is the same three words for every object whether or not anything weakly references it,
which is what keeps the type-erased release path expressible at all. The cost is eight bytes on an
allocation that already cost a `malloc`, and it lands only where the feature is used: values, fixed
arrays, and `*T` buffers have no header, so allocator-free code pays nothing for a mechanism it never
touches.

## `weak T` — breaking cycles

Reference counting has one honest weakness: a cycle of strong references keeps itself alive. A parent
holding its children while each child holds its parent is the shape that does it, and it is common
enough in systems code — a back-link in an intrusive list, a process's pointer to its parent — to
have a purpose-built answer.

A **`weak T` does not keep its referent alive.** When the last strong reference goes, the object is
destroyed and every weak reference to it becomes empty.

```sysl
struct Node
    label: string
    parent: weak Node
end Node

parent_of(n: &Node) -> string
    n.parent.get() match
        Some(p) -> p.label
        None    -> "(none)"

var root: &Node = Node("root", None)
var kid: &Node = Node("kid", root)

print(parent_of(root), parent_of(kid))
```

```output
(none) root
```

**`weak T` is a type, not an operator.** There is nothing that makes one; a `&T` becomes a `weak T`
wherever a `weak T` is what the context asked for — the struct field above, an argument, a declared
local, a returned value. That is the same rule that makes a `&T` out of a `T`, so both `&` and `weak`
live at the boundaries a program annotates and neither appears in a body.

The conversion goes one way. A `weak T` is not a `&T` and never silently becomes one, because
becoming one is the operation that can fail.

**Reading one is a question, and it is `get()`.** The answer is `Option[&T]`: a live strong reference
with a count taken for the caller, or `None`. Nothing else may be done to a weak reference — no field
selection, no method call, no `==`:

```sysl
struct Node
    label: string
    parent: weak Node
end Node

var root: &Node = Node("root", None)
var kid: &Node = Node("kid", root)

print(kid.parent.label)
```

```error
may be gone, so nothing is read off one directly
```

Every road to the object goes through the `Option`, which is what makes "a weak reference never
dangles" a fact about the language rather than a promise about the programmer.

The parentheses are not decoration. `.len` is a **property** — a fact about the value, the same answer
every time it is asked — while `get()` is a **question about the world**: two calls a moment apart may
disagree, and the answer costs a count. sysl puts the parentheses on the second kind.

**An empty weak reference is written `None`**, and it is the same state whether the object is gone or
there never was one. This `None` is not an `Option` — it is the empty value of the weak reference
itself, chosen because it is exactly what `get()` will hand back for it.

It is also the **zero value** of `weak T`, which is the one place `weak T` parts company with `&T`:
there is no such thing as a reference to nothing, so `&T` has no zero. A struct with a weak field
therefore still has one, and an uninitialized declaration of it is still a declaration:

```sysl
struct Node
    label: string
    parent: weak Node
end Node

var n: Node

n.parent.get() match
    Some(p) -> print("held")
    None    -> print("empty")
```

```output
empty
```

**A weak reference may not be made from a value with nowhere else to live.** Constructing into a weak
position would box the value and then weaken it, leaving the weak edge as the object's only holder —
and the object dead before the statement ended:

```sysl
struct Node
    label: string
    parent: weak Node
end Node

var kid: &Node = Node("kid", Node("root", None))

print(kid.label)
```

```error
a weak reference does not keep Node alive, and nothing else here holds this one
```

**The only default a `weak T` parameter can have is `None`**, and that falls out rather than being
decided. A default is produced afresh at each call that omits it, in a scope holding none of the
caller's locals, so what it names would have to outlive every frame — and every candidate that names
an *object* is closed off. A construction is refused by the rule above; a top-level `var` is a local
of the entry point; and a module-level `val` counts nothing, which a reference does. What is left is
the one value that holds nothing:

```sysl
struct Node
    label: string
    parent: weak Node
end Node

adopt(child: string, parent: weak Node = None) -> string
    parent.get() match
        Some(p) -> child + " of " + p.label
        None    -> child + " of nobody"

var root: &Node = Node("root", None)

print(adopt("a"), adopt("b", root))
```

```output
a of nobody b of root
```

**`weak sync T` is refused**, naming the concurrency chapter. Upgrading an atomic weak reference is a
compare-and-swap loop against a count another thread may be driving to zero underneath it, and that
is written when there is something to race with.

Everything a `&T` may point at, a `weak T` may be taken of: a struct, a scalar, an array, a generic
instantiation, a trait object, and a type parameter.

## `*T` — the raw pointer

`*T` is a bare machine pointer, exactly like C's: no length, no count, no checks, manual lifetime. It
is the only unsafe primitive over data, it needs no runtime, and it is how a kernel, a driver, or an
allocator's own internals are written.

```sysl
bump(n: *int)
    *n += 1

var counter = 41

bump(&counter)

print(counter)
```

```output
42
```

**Anything C can do through a pointer, sysl can.** The pointer is where the language's guarantees
stop, so it carries C's whole surface rather than a safer subset of it — a subset would only mean a
kernel that could not be written here.

### `null` exists, and only here

`null` is the absent raw pointer. It has no type of its own and takes the `*T` its context expects,
the way a bare `None` takes its type argument:

```sysl
struct Node
    value: int
    next: *Node
end Node

var third = Node(3, null)
var second = Node(2, &third)
var first = Node(1, &second)

var walk = &first
var total = 0

while walk != null
    total += walk.value
    walk = walk.next

print(total)
```

```output
6
```

That program is also the answer to "how does a type reach itself" — see [recursive
types](#recursive-types) below.

### Arithmetic: two directions, one spelling each

```sysl
var buf: [8]int = [10, 20, 30, 40, 50, 60, 70, 80]
var base = &buf[0]
var third = &buf[2]

print(*third, third - base, base - third, *(&base[3]))
```

```output
30 2 -2 40
```

**`p - q` between two pointers of the same pointee is an `isize`, and it counts elements**, not bytes
— C's `ptrdiff_t`. It is signed because the order of the operands is the programmer's, and it is the
exact inverse of `&p[n]`: indexing takes an address and a count to an address, and the difference
takes two addresses back to a count, both striding by the pointee.

It is here because the interior-pointer half of libc needs it. `memchr`, `strchr`, `strrchr`,
`strstr` and `memmem` all hand back a pointer *into* a buffer the caller owns, and without a
difference every one of them is callable and useless, because nothing could turn the answer into an
index.

**Offsetting stays `&p[n]`.** `p + n` is not spelled, deliberately: indexing already exists and
already strides by the pointee, and a second spelling for one address would be a second thing to keep
in step. `p + q` names no address and is refused too.

**Two pointers of different pointee types have no shared element to count**, and are refused by the
ordinary matching-types rule rather than by one of this operator's own:

```sysl
var n = 1
var c = 'x'
var pn = &n
var pc = &c

print(pn - pc)
```

```error
'-' needs matching types, got *int and *char
```

**A counted `&T` has no arithmetic at all**, keeping the equality it always had and nothing more:

```sysl
struct Point
    x: int
end Point

var a: &Point = Point(1)
var b: &Point = Point(2)

print(a - b)
```

```error
operator '-' is not defined for &Point
```

Arithmetic is a property of the unsafe mode, not of holding an address.

Whether two pointers into unrelated objects may be subtracted is the programmer's business, as `p[i]`
past the end already is.

### Bounds safety follows length, not pointers

The out-of-bounds hazard is governed by whether a value **carries its length**, independently of
where it was allocated:

- **arrays and slices carry their length**, so indexing is bounds-checked in every context — hosted
  or allocator-free, over static, stack, or heap memory;
- **`*T` carries no length**, so it is the one unchecked primitive. `p[i]` reads the `i`th element
  and `p[0..<n]` views `n` of them, both exactly as C does and both unchecked, because there is no
  length to check against and supplying one is the programmer's assertion.

So even low-level, allocator-free code stays bounds-safe by *choosing* slices, and reaches for `*T`
where it must — an MMIO window, a page table, a buffer a C function filled. That choice is the point.

## Places

A **place** is something with an address: a local or parameter, a dereference, an **element**, and a
field of any of them. Everything else — a call result, an arithmetic result, a freshly built struct —
is a value with no address to take.

| operation | what it does |
|---|---|
| `&place` | yields a **`*T`** — C's address-of, with C's result |
| `*p` | reads through a `*T` or a `&T`, and is itself a place |
| `p.f` | selects, dereferencing **one** level automatically on both `*T` and `&T` |

**`&` on a place yields a raw pointer, never a reference.** A place lives in a frame or inside
another object, so there is no count to take a share of. Reaching a `&T` means being handed one or
constructing one, and taking the address of a local is therefore inherently in the unsafe tier —
which is right, because it can dangle and nothing promotes it.

**Selection is the only implicit dereference.** `p.x` is `(*p).x` and `p.x = 9` writes through the
pointer — Go's rule, with no `->`. Matching a reference to an enum against its variants is `match *e`.

**The shorthand stops at one level**, so reaching through a `**T` is written:

```sysl
struct Point
    x: int
    y: int
end Point

var p = Point(1, 2)
var pp = &p
var ppp = &pp

print(pp.x, (*ppp).x)
```

```output
1 1
```

Leaving the step out is a diagnostic rather than a second implicit dereference, and it says how many
levels are left:

```sysl
struct Point
    x: int
    y: int
end Point

var p = Point(1, 2)
var pp = &p
var ppp = &pp

print(ppp.x)
```

```error
selection reaches through one level of indirection and **Point has more
```

Assignment, compound assignment, and `++`/`--` all take a place, so the same three forms work on a
variable, on a field, and through a pointer with nothing special said about any of them.

**An element carries one wrinkle the other three do not.** A slice's elements and a pointer's live
wherever the storage is, which is somewhere the expression naming them is not, so they have an
address whether or not the expression naming them does. That is what makes `rows(g)[i] = v` write
through to the grid rather than into the view the call handed back. An **array's** elements *are* the
array, so they are places exactly when the array is. A string's bytes are never one: writing a byte
of UTF-8 is how a string stops being UTF-8, and that is refused as immutability rather than as the
absence of an address.

The hazard on the other side of that rule is the one dangle a **single statement** can produce. If a
temporary view is the only holder of its buffer, the buffer is released at the end of the statement,
and `&f()[i]` is a pointer to freed storage before the next line runs. That is the unsafe tier
behaving as advertised — `&` yields a raw pointer, a raw pointer can dangle, nothing promotes it —
and it is called out because every other dangle needs the pointer to be carried somewhere first.

## `ref` — a name for a place

A place can be deep, and until `ref` the two ways to shorten one both cost something.
`var t = self.tasks[i]` binds a **copy**, so every read and every write walks the path from the table
again; `&self.tasks[i]` gives the name back and gives up bounds checking, `within` checking,
invariant re-checking, and the guarantee at the top of this page, all in one step. That is a cliff
rather than a gradient.

**`ref` binds a name to a place.**

```sysl
struct Task
    state: int
    prio: int
end Task

struct Kernel
    tasks: [4]Task

    advance(*self, i: usize)
        ref t = self.tasks[i]

        t.state = 2
        t.prio = t.prio + 1
end Kernel

var k = Kernel([Task(0, 7); 4])

k.advance(2usize)

print(k.tasks[2].state, k.tasks[2].prio, k.tasks[0].prio)
```

```output
2 8 7
```

**The place is evaluated once**, where the binding is written — the index is computed once, the bounds
are checked once — and what the name means afterwards is the storage that was found, not the
expression that found it. A later `i += 1` leaves `t` naming the element it always named. Scala's
by-name parameter is the thing this is *not*: `x: => T` re-evaluates at every use, which would save
no check and would silently make `t` follow a later change to `i`.

### It is a declaration, never a type

This is the whole of why it can exist in a language with no borrow checker. `ref` may be written
**only as a local declaration**. There is no `ref` type, so a ref cannot be a field, a parameter, a
return type, an element, or a type argument; it cannot be re-pointed at a second place once bound; it
cannot be captured by a closure; and it does not outlive the block that declares it.

A ref *of* a ref is not a re-pointing and is ordinary — one more name for the place the first one
stands for, with the walks following through it:

```sysl
var xs: [3]int = [1, 2, 3]

ref a = xs[1]
ref b = a

b = 42

print(xs[0], xs[1], xs[2])
```

```output
1 42 3
```

That restriction is what keeps the compiler's knowledge complete. A `*T` is a type, so the moment one
exists it can be carried somewhere the compiler has lost the path it came from. A ref never travels,
so the analyzer still holds the place expression, at the point it was written, in the same body.

**At run time a ref stores an address. At compile time it remembers a place.** Both halves are
load-bearing. The address is what makes sixty-five path walks one. The remembered place is what keeps
every check a `*T` would have severed:

- a write through the ref re-runs the `invariant` clauses of every struct the place lies inside,
  found by the same outward walk, because the walk still has the whole place to walk;
- a ref into a `val`, or into an element of one, is read-only, since reaching into read-only storage
  keeps the property;
- a ref to a `within`-constrained slot is checked on assignment exactly as the slot is;
- and the bounds check a subscript owes is paid once, at the binding, rather than not at all.

A ref is therefore **not a fourth memory mode**. It introduces no representation, no new type, and
nothing that can be stored: it is a second way to *say* a place the three modes already describe.

### What may be written

**A ref's initializer must be a place**, and a call result has no address for the name to mean:

```sysl
make() -> int = 7

demo()
    ref x = make()

    print(x)

demo()
```

```error
'ref' names a place — a local, a field, an element, or a dereference — and this expression has no address for 'x' to name
```

**A ref inherits the place's writability and gets no modifier of its own.** A ref into a `val` may be
read and not written, exactly as the `val` may. Stating it twice would only create the chance to
state it wrong.

**A user type's subscript is not a place.** `b[i]` on a container goes through the `Index` trait,
which is a call rather than a walk to an address, so there is nothing there for a name to mean. A ref
reaches the built-in subscript, which is the one that indexes storage.

### The one rule: what may move underneath it

A stored address is only as good as the storage staying where it is. C# has ref locals and needs no
rule like this, because a tracing collector keeps the old array alive when the variable is pointed at
a new one. sysl has no collector in this tier, so the same program would dangle:

```sysl
struct Table
    cell: []int

    swap(*self)
        ref e = self.cell[0]

        self.cell = [0; 8]

        print(e)
end Table

var t = Table([1, 2, 3])

t.swap()
```

```error
'e' is a 'ref' standing on storage this assignment would release, so the name would be left pointing at freed memory
```

**While a ref is live, no step of its place that could come to name different storage may be
assigned, and no mutating method may be called on a prefix of one.** The check is local, decided from
types alone, and never asks where the ref is *used* — which is the question that would need lifetimes.

Which steps are hazards falls out of the model rather than being a list, and **the discriminator is
ownership, not indirection.** Only a step that *releases* something when it is overwritten can strand
a ref:

| step | hazard? | why |
|---|---|---|
| a `&T` | **yes** | overwriting it drops what it held, and that release may be the last |
| a view (`[]T`) | **yes** | the same — a view owns its buffer |
| a `*T` | no | a raw pointer owns nothing, so `p = q` frees nothing |
| a field, a fixed array, or an element of one | no | that storage *is* the enclosing object's bytes |

Two words carry the rule, and between them they keep the set small. A step is held still when it is
an **owning** step that the path goes **through** — so the place itself is never in the set, because
an assignment to it is what writing through the ref *is*. `ref r = h.node` names the slot, so
`r = other` and `h.node = other` are one statement written two ways and neither is refused, while
`h = other` on a `&Holder` is.

The `*T` exclusion is not a concession. A program that reaches its tables through a `*Self` receiver
has no owning step anywhere in its chains, so it is asked for nothing at all — which is exactly the
`advance` above, where the rule costs the program nothing.

The mutating-call half is the conservative one, deliberately. A `*self` method may write any part of
its receiver, so a live ref into that receiver is refused the call whether or not that particular
method reassigns anything:

```sysl
struct Table
    cell: []int

    grow(*self)
        self.cell = [0; 8]
end Table

var t = Table([1, 2, 3])

ref e = t.cell[0]

t.grow()

e = 5
```

```error
'e' is a 'ref' standing on storage this call could release, since a '*self' method may write its receiver
```

Deciding otherwise would mean reading the callee's body, and a module compiles against its imports'
signatures and never their bodies. The cost is one refusal in a program that could have written the
ref one line later.

**What this does not do is make a `*T` safe**, and it does not try. A ref's place may be rooted at a
pointer, and whether *that* points anywhere is the raw tier's ordinary bargain. What the rule buys is
that the storage a ref names cannot be released by the block that named it.

### A ref to a slot that holds a reference

`ref r = self.node`, where `node` is a `&Node`, names the **slot**, not the object in it. So the
binding takes no count — nothing new holds the object — and `r = other` is the assignment
`self.node = other` by another name, releasing what was there and retaining what arrives, in that
order. Reading `r` produces the reference and takes a count for the reader exactly as reading the
field would.

The distinction matters because the other reading is available and wrong: if binding retained, a ref
would be a `&T` with extra steps, and the count it took would keep an object alive past the write
that replaced it.

### Where it comes from

The form is old and lives outside this language's usual references, none of which have it. **Ada's
`renames`** is the general case and evaluates the name once at the declaration; **Fortran's
`ASSOCIATE`** is the closest in shape, being block-scoped and deliberately not a type; **C#'s `ref`
locals** are the closest in spelling. C# also shows what the restriction is worth: having added ref
returns and ref fields, it spent several releases building the escape analysis that keeping the form
local avoids entirely.

## Recursive types

A type may reach itself **through an indirection**, and only through one. `next: *Node` is
pointer-sized and legal, as is `next: Option[&Node]`; a struct holding itself by value has no finite
size:

```sysl
struct Node
    value: int
    next: Node
end Node

print(1)
```

```error
type 'Node' contains itself, so it has no finite size
```

The rule is **per cycle rather than per field**: a cycle is legal as soon as one edge on it is a `*T`
or a `&T`, so mutually recursive types work as long as the loop passes through a pointer or a
reference somewhere.

## Slices keep their backing alive

A slice is **three words**, not two:

```
[]T = { owner: *Buf, ptr: *T, len: usize }        // 24 bytes on a 64-bit target
```

`ptr` and `len` name the range; **`owner` keeps the bytes alive.** A bare `{ptr, len}` view can
outlive the buffer it views — Go gets away with it because its collector finds the object from an
interior pointer, and sysl has no collector — so the slice carries the owning reference itself.
Taking a slice retains, dropping one releases, slicing stays O(1) and allocation-free, and "a slice
never dangles" becomes true rather than aspirational.

```sysl
tail() -> []int
    var xs: []int = [1, 2, 3, 4]

    xs[2..]

var t = tail()

print(t.len, t[0], t[1])
```

```output
2 3 4
```

The buffer `xs` named is gone as a name before `print` runs, and the slice is still valid, because
the slice is one of its owners.

**`owner` is null when there is nothing to keep alive** — a slice of static data, of a `*T` buffer, or
of a fixed array whose storage outlives every view of it. Retain and release on such a slice are
no-ops, so allocator-free code slicing a static or stack buffer pays nothing at all. This is the same
immortality rule string literals use, generalized.

A `string` is then exactly an **immutable, validated `[]u8`**, and the two share one representation
and one implementation.

## Escape analysis

The `owner` word answers "who keeps this alive" for every slice except one: a slice of a **local
fixed array**. That storage is a stack slot, valid until the frame returns and no longer.

| slice of | `owner` | safe to keep? |
|---|---|---|
| a heap buffer | the buffer | yes — retained |
| static data, a string literal | null (immortal) | yes — never freed |
| a `*T` region | null | the programmer's problem, like every `*T` |
| **a local fixed array** | **null** | **only while the frame lives** |

The compiler finds the slices for which that matters, and **the programmer writes nothing**. There is
no `@escaping`, no lifetime parameter, and no "this result views that argument" marker anywhere in a
signature. Go is the precedent; Rust's lifetimes and Swift's `@escaping` are the alternative, and both
charge the programmer for what a compiler can work out.

### What escapes

A slice whose origin is a local array escapes if it can be reached after the frame returns — that is,
if it is:

1. **returned**, directly or nested inside whatever carries it back: a struct, an enum, an `Option`,
   a tuple, or one slot of a multi-result list;
2. **stored into anything that outlives the frame** — a global, a field reached through a `&T`, or an
   aggregate that is itself stored somewhere that outlives the frame;
3. **passed as an argument the callee keeps**;
4. **captured by a closure that itself escapes**;
5. **assigned into a local that escapes** — the rule is transitive, resolved as a fixpoint over the
   function's own locals.

Everything else needs no allocation: indexing, iterating, sub-slicing into a local that stays local,
comparing, and passing to a callee that only reads.

### Crossing a call

Two facts about a function are enough to keep the caller's analysis local and exact, and both are
**inferred from the body** rather than written:

- **per parameter** — does the callee let this argument outlive the call?
- **for the result** — which parameters may the returned value be a view of?

They are computed bottom-up over the call graph and recorded in module metadata. Recursion starts
optimistic and iterates to a fixpoint, so a self- or mutually-recursive function converges on the
truth rather than on the conservative answer.

Two cases get the pessimistic answer instead — every parameter kept, the result viewing everything:

- **a call through a trait object**, because which body it reaches is a word read at run time, so
  there is no one summary to consult. Reading through a *concrete* type or through a bounded type
  parameter is unaffected, since monomorphization turns the second into a direct call too;
- **a function whose body is not available** — an `extern`, which is the declaration form for exactly
  that. The foreign side may retain what it was given, and nothing here can tell.

### What happens when a slice escapes

**With an allocator, the array is promoted.** It is allocated as an ARC buffer instead of a stack
slot, the slice's `owner` points at it, and nothing else about the program changes:

```sysl
render() -> []u8
    var line: [8]u8

    line[0] = 104u8
    line[1] = 105u8

    line[0..<2]

var l = render()

print(l.len, l[0], l[1])
```

```output
2 104 105
```

**The array keeps its type, and only its storage moves.** A promoted `[8]u8` is still a `[8]u8`: its
length is still a compile-time constant, it is still a value that copies on assignment, and every
index and store is emitted exactly as an unpromoted one's is. What changes is where the name's
address points, and that a view of it carries that buffer as its owner. Rewriting the declaration
into a `[]u8` would have been the other way to do it and is wrong — it changes the type at every use,
and an array is a value type where a view is not.

**Which array moves** is decided by the *root* of the escaping view. An index step is walked through,
because an element of a local array of arrays is part of that array's storage; a **field** step is
not, because that storage belongs to a struct. Only arrays that are *both* sliced *and* escaped are
promoted.

Two roots have nowhere to be promoted to, and are diagnostics rather than promotions — an array a
caller passed **by value**, which is the caller's layout, and an array that is a **field** of a struct
on the frame:

```sysl
first_two(a: [4]int) -> []int = a[0..<2]

print(first_two([1, 2, 3, 4]).len)
```

```error
a slice of an array this frame owns is returned, so it would outlive the array, and the storage is not this body's to move — it is a field of a value, or an array a caller passed by value. Declare it as a '[]T', which makes a buffer of its own and owns it, or as a '&[N]T' where the length is fixed
```

**Without an allocator it is always a compile error.** Under `no alloc` there is nothing to promote
into, so every promotion becomes a refusal reported at the view that leaves the frame. That is how
every other allocation-gated feature behaves in the allocator-free subset — growable arrays, escaping
closures, and `&T` creation are all compile errors there — so it introduces no new rule.

### Promotion is silent, not hidden

Silent promotion earns the obvious objection: an allocation appears that nothing in the source asked
for. The answer is discoverability rather than ceremony — **`--explain-escapes`** reports every
promotion the compiler made and the route that forced it:

```
$ sysl build --explain-escapes tty.sysl
tty.sysl:31:12: 'buf' is promoted to the heap, because this view of it is returned
```

One line per array, in source order, on stderr, accepted by every subcommand. The position is the
**view that forced the move** rather than the declaration, because that is the half a reader cannot
work out for themselves. This is Go's `-m`, and it is the right shape: the common case costs no
reading, and "why did this allocate?" always has an answer. A program that must not allocate says so
with `no alloc`, and then the compiler enforces it rather than reporting it.

The idiom worth reaching for first, even where an allocator exists, is the last one: **return a count
and let the caller slice its own buffer.** That is what `snprintf` does, what Rust's buffer writers
do, and what most kernel code wants.

## Reinterpreting storage

An allocator carves bytes and hands back a typed pointer — that is the whole of what an allocator
does. A driver takes an address the datasheet gives as a number and reaches the register block at it.
Both need a way to say which type some bytes are, and both are cases the raw tier already committed
to.

**Three directions, two spellings**, because they are not equally dangerous:

| written | direction | tier |
|---|---|---|
| `usize(p)` | a pointer as a number | an ordinary conversion |
| `ptr_cast(n)` | a number as a pointer | unsafe |
| `ptr_cast(p)` | one pointee type as another | unsafe |

**A pointer becomes an integer through the ordinary conversion syntax**, because it is an ordinary
conversion: `usize` is wide enough to hold any address by definition, so it is total and loses
nothing, and the result is a number that cannot be dereferenced. `isize` takes one too, which is what
a program comparing addresses against a signed offset wants.

```sysl
struct Node
    value: int
end Node

var arena: [64]u8 = [0u8; 64]
var n: *Node = ptr_cast(&arena[0])

n.value = 42

print(n.value, sizeof(Node), usize(n) == usize(&arena[0]))
```

```output
42 4 true
```

**The target type is not written in the call.** It comes from whatever receives the result — the same
way `va_arg`, a bare `None`, and a bare `null` all take theirs. That is not a shortcut: square
brackets in an expression are indexing, and call-site type arguments are refused language-wide, so a
written target would need a syntax nothing else in the language has. Where nothing says which pointer
is wanted, the program is told to annotate what receives it:

```sysl
var arena: [64]u8 = [0u8; 64]
var p = ptr_cast(&arena[0])

print(1)
```

```error
'ptr_cast' reads an address as a pointer to some type, and nothing here says which
```

**`ptr_cast` never produces a `&T`.** A reference is a safe-tier value — non-null, refcounted, and
relied on by everything the safe subset promises — and an address invented from bytes has no count for
ARC to own and no object to be non-null about:

```sysl
struct Node
    value: int
end Node

var arena: [64]u8 = [0u8; 64]
var n: &Node = ptr_cast(&arena[0])

print(n.value)
```

```error
'ptr_cast' never produces a reference: a '&T' is counted and non-null, and an address read out of bytes carries no count for anything to own — read it as a '*T'
```

A `weak T` is refused for the same reason, and the fat types — a slice, a `string` — for that reason
and one more: they are wider than an address, so there is nothing to reinterpret. What comes out is a
`*T`, and reaching anything else from it is the ordinary route through `*p`.

`sizeof` and `alignof`, which measure what is being carved, are on the
[expressions](/reference/expressions/) page.

## Device memory

`ptr_cast` gets a driver to the register block. It does not get it a *correct* driver, and the missing
half is this: an optimizer is entitled to assume that reading the same storage twice yields the same
value, that a store nobody reads is a store nobody needs, and that two accesses in a row may be one
wider access. Every one of those assumptions is false at a device. `while regs.status == 0u32 do ()`
is a loop that reads a register until the hardware changes it, and a compiler that hoisted the read
out would spin forever on the first value it saw.

So sysl has C's qualifier, spelled the way C spells it — in the type:

```sysl
struct Uart
    status: volatile u32
    data:   volatile u32
    baud:   u32
end Uart

var block = Uart(1u32, 0u32, 115200u32)
var regs: *Uart = &block

regs.data = 65u32

print(regs.status, regs.data, regs.baud)
```

```output
1 65 115200
```

> A **`volatile`** place is one whose reads and writes are **effects, not value computations**. It
> may change without the program changing it, and reading it may itself do something. So the compiler
> emits exactly the accesses the source wrote, exactly once each, in the order written — never adding,
> dropping, merging, or moving them relative to one another.

**It constrains the compiler, not the machine.** No atomicity, no ordering against another core, no
protection from a torn read. C spent two decades learning this; for talking to another thread the
tools are `&sync T`, `Mutex[T]`, `Atomic[T]` and explicit orderings, and none of them is spelled
`volatile`. A program that reaches for this word to share a counter has written a race with a keyword
in front of it.

### It qualifies storage, and a value read out of storage is an ordinary value

This is the rule everything else follows from. `regs.status` above has type `u32` — not
`volatile u32` — because what a load hands back is a number, and a number is not somewhere a device
can write. What is qualified is the **place**, and the qualifier lives in the three types that name a
place somebody else owns:

| written | what is qualified |
|---|---|
| `status: volatile u32` | a struct field |
| `bank: [4]volatile u32` | an element — a GPIO bank |
| `p: *volatile u32` | a pointee — the lone register |

Everywhere else the type being written is the type of a **value**: what a `var` holds, what a
parameter receives, what a function hands back, what a type argument stands for.

```sysl
var x: volatile u32 = 0u32

print(x)
```

```error
is the type of *storage*, and this is a value
```

The diagnostic says which spelling was wanted, because a program that writes `var x: volatile u32`
almost always meant `*volatile u32`.

**Per field, not per aggregate.** C also allows `volatile struct Uart`; sysl does not, and the block
above shows why. `baud` is a shadow value the driver keeps in ordinary memory beside the registers,
and every real device header has one — a reserved word, a cached configuration, a software flag. A
qualifier on the whole struct would sweep it in:

```sysl
struct Uart
    status: u32
end Uart

var p: *volatile Uart = null

print(1)
```

```error
a register block is qualified one field at a time
```

Qualifying per field is the same power with the opt-out, it is what CMSIS and every other vendor
header already does, and it makes the restriction below a check on one scalar instead of a walk of a
type.

**Only a scalar or a raw pointer may be qualified.** The promise is about *the* load and *the* store
the source wrote, so it is only meaningful where an access is one instruction:

- **a counted value is refused outright** — a `&T`, a `weak T`, a slice and a `string` come with
  retains and releases the compiler places, and a retain that may not be elided is not a request
  anybody could act on;
- **a trait object is refused** for a plainer reason: it is two words, so touching one is two accesses
  whatever the source says, and the table beside the value is this program's rather than a device's;
- **a constrained subtype is refused**, and this one is about trust rather than instructions. A
  `Level = int within 0..7` is the claim that a value *has been checked*; a register holds whatever
  the device put there. So the register is declared at the base type and what comes back is converted
  — one written conversion, checked, at the point the value arrives.

**A struct that holds a register carries no `invariant`.** A check is a call taking every field, so it
reads the whole block however few fields the clause names — an invariant written over the shadow value
beside the registers would make writing that shadow an access to the device. There is nothing to hold
the clause true either: a device changes a register between the check and the instruction after it. A
register is checked where it is read.

### What the compiler does with it

A qualified access lowers to LLVM's `load volatile` / `store volatile`, which is exactly the barrier
this needs: it stops the reordering, elision and merging above, and stops nothing else. There is no
runtime cost and no runtime component.

- **A qualified field is reached at its own address**, not lifted out of the block. Reading a `Uart`
  to find out what is in `status` would also read `data`, and reading a data register is how a FIFO is
  popped — so a qualified field gets a place walk, one `getelementptr` and one `load volatile`, with
  nothing else touched.
- **A whole block copied is a copy of every register in it.** `var u = *regs` is one access to each,
  which is as much an effect as one access to one of them, so the aggregate access is marked too.
  Whether a driver wants that is the driver's business; what it does not get is a silent unqualified
  read of hardware.
- **An address taken of a register is the address of a register.** `&regs.status` has type
  `*volatile u32`, so a driver may hand one register to a helper and every access the helper makes is
  still an access to a device. Without that, a driver would have to be one function.
- **A type parameter never binds to a qualified type.** The loads and stores a generic body emits are
  *its* accesses, written once and shared by every instantiation, so it cannot promise to have written
  the ones a particular caller had in mind.

**`volatile` is not reserved.** It is special only in front of another type, so a program with a
variable, a field, a function, or a type of its own by that name still compiles — the same arrangement
`sync` has after `&`:

```sysl
var volatile = 7

print(volatile + 1)
```

```output
8
```

**`[]const T` composes with it and means a different thing.** `const` is a property of the *view* —
these elements may not be written through this handle — while `volatile` is a property of the
*element*. A read-only device register is `[]const volatile u32`, and both words are doing work.

## Where `defer` sits

[`defer`](/reference/statements/#defer) is the model's answer for what the language does *not* own: a
descriptor from `open`, a `FILE*` from `fopen`, a block from `malloc`, a lock taken from a mutex. ARC
gives back a reference, a string, and a slice's backing without being asked, and knows nothing about
those.

Three facts place it against everything above:

- **A deferred statement runs before the block's ARC releases**, so every local it names is still
  alive when it runs — including the one holding the resource it is closing. Leaving from the middle
  unwinds outward: the innermost block runs its deferred statements and then gives up its counts, then
  the block outside it does the same.
- **It owns nothing and allocates nothing.** `defer` takes no count, makes no box, and adds no word to
  any value; a program that does not use it emits nothing for it. That is what keeps it available
  under `no alloc`, where the resources it releases are the only ones there are.
- **A trap runs nothing.** A trap aborts without stack cleanup, and `defer` does not qualify that: a
  broken invariant means the program's model of itself is already wrong, and running cleanup against
  that state is how a corrupt program writes its corruption to disk on the way down.

**What it is not is a destructor.** A destructor belongs to a *type* and runs wherever a value of that
type dies; `defer` belongs to one place in one body and runs for the resource that body took.

## Crossing a concurrency domain

A `&T` permits aliasing and mutation through any reference, which is safe **within one concurrency
domain** and nowhere else: its refcount is non-atomic, and two threads touching it would race. So a
`&T` may not leave its domain, and crossing one **copies** by default — which is what process IPC does
anyway, and what keeps the ordinary path free of atomics.

The exception is **`&sync T`**, whose refcount is atomic. It is a distinct type from `&T` with no
conversion either way, and which one an object is is chosen where it is allocated:

```sysl
struct Cell
    n: int
end Cell

send(c: &sync Cell) -> int = c.n

var c: &Cell = Cell(1)

print(send(c))
```

```error
'&Cell' and '&sync Cell' are distinct types, and neither converts to the other: a count is atomic or it is not from the moment the object is allocated, and a conversion would put an ordinary retain beside an atomic one. Allocate Cell as a '&sync Cell' where it is constructed
```

A conversion would put an ordinary retain beside an atomic one, so the count is atomic or it is not
from the moment the object exists. **`&sync T` makes the *reference* safe to share, not the object
safe to mutate** — that still wants a `Mutex`.

## Hazard summary

| segfault source | prevented in the safe subset by |
|---|---|
| use-after-free / double-free | ARC on `&T`; `weak` degrades to `Option` and never dangles |
| null dereference | non-null references; nullable is `Option` |
| out-of-bounds | length-carrying arrays and slices, checked everywhere |
| slice outliving its buffer | the slice's `owner` word retains it; escaping locals are promoted |
| refcount race across threads | `&sync T` is atomic; a `&T` may not cross a domain |
| dangling or wild pointer | impossible without `*T` |

Only `*T` opts out, and it opts out visibly.

One hazard is **not** on that list: racing on the *fields* of an object two threads deliberately
share. Preventing that needs proof of exclusive access, which is the thing this language trades away,
so it is answered by a `Mutex` and by convention rather than by the type checker. It takes `&sync` or
`*T` to reach the situation at all, so it is at least as greppable as everything else here.

## The two worlds, one language

- **Application, server, utility:** `T` and `&T`, plus `weak`, slices, and arrays. Safe, pleasant,
  ARC-managed.
- **Kernel, driver, allocator-free:** `T`, `*T`, fixed arrays, slices, and manual `malloc`/`free`. A
  module that never *creates* a `&T` emits no allocation and no allocator dependency, exactly like C,
  and stays bounds-checked wherever it uses arrays and slices.

The boundary is not a convention — it is compiler-enforced through the allocator capability. **What it
gates is allocation, not ownership.** Allocator-free code may hold, pass, copy, and drop a `&T` or a
heap-backed slice that something else created: retain and release are a few instructions, and the free
path goes through the object's own deallocation hook. What it may not do is *make* one. That is what
lets a driver keep the `&Device` its bus manager handed it, and a `no alloc` parser read a heap-backed
slice it was given — neither of which is expressible if ownership stops at the boundary.

---

Next: [arrays and slices](/reference/arrays/) — the two sequence types, and what a view keeps alive.
