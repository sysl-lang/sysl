---
title: Memory
summary: Three modes, chosen per declaration. No garbage collector, no borrow checker, and no allocation keyword.
weight: 50
---

This chapter covers what most distinguishes sysl from other languages. The earlier pages describe
features found in many languages; the pages after this one assume you know the three memory modes.

A systems language is chosen for the control it gives over where data lives and when it is freed.
The two well-known ways to keep memory safe are a garbage collector, which takes control of *when*,
and a borrow checker, which keeps both but requires you to prove the program's use of memory to the
checker as you write it. sysl uses **reference counting**, with the memory mode written on each
declaration.

## The three modes

| mode | what it is | who frees it |
|---|---|---|
| `T` | a value — on the stack, in a register, or inline in something bigger | nobody; it goes when the frame does |
| `&T` | a counted reference to a heap object | the compiler, when the last reference goes |
| `*T` | a raw pointer — C's pointer | you |

The mode is chosen **per declaration**, not per type. The same `struct Point` can be a value in one
place and a heap object in another, and the declaration decides which:

```sysl
struct Point
    x: int
    y: int

var here = Point(1, 2)              // a value, in this frame
var shared: &Point = Point(3, 4)    // on the heap, counted

print("value:", here.x, "reference:", shared.x)
```

```output
value: 1 reference: 3
```

**The two constructions are identical.** `Point(3, 4)` does not request the heap. The annotation
`&Point` is an *expected type*, and writing an ordinary construction where a `&T` is expected places
the object on the heap.

There is no `new`, no `malloc`, no `Rc::new`, no `.clone()` and no wrapper type. The positions that
create a reference are the ones that already state a type: a declared local, a parameter, a return
type, a struct field, an enum variant's payload. A function declared `-> &Process` allocates in its
body and one declared `-> Process` does not, with the same construction written in each. Where there
is no expected type, a construction is a value.

## References are shared, and never null

There is no borrow checker, so there is no exclusivity rule. Many references may point to one object,
and any of them may write to it:

```sysl
struct Account
    owner: string
    balance: int

deposit(a: &Account, amount: int)
    a.balance += amount

var shared: &Account = Account("ada", 100)
var alias = shared

deposit(alias, 40)

print("balance:", shared.balance, "same object:", shared == alias)
```

```output
balance: 140 same object: true
```

`alias = shared` did not copy the account. It created a second reference to it and incremented the
count. In the struct chapter, `b = a` on a value copied it: the syntax is the same, and the declared
mode decides the behaviour.

If you know Rust: these are two live mutable aliases, and sysl allows them. That is a deliberate
choice. Reference counting has a run-time cost, a retain and a release where a reference is shared,
and in return the language does not ask you to satisfy a borrow checker.

**A `&T` always points to a live object.** There is no null reference and no way to create one, so
null-reference errors cannot occur.

A value that may be absent is an `Option`, which is how a linked structure is written:

```sysl
struct Item
    label: string
    rest: Option[&Item]

count(list: Option[&Item]) -> int
    list match
        Some(item) -> 1 + count(item.rest)
        None -> 0

var last: &Item = Item("c", None)
var middle: &Item = Item("b", Some(last))
var items: &Item = Item("a", Some(middle))

print("length:", count(Some(items)), "head:", items.label)
```

```output
length: 3 head: a
```

The `match` is the only way to reach the item, so the absent case is always handled.

## Cycles, and `weak`

Reference counting has one known limitation: a cycle of strong references keeps itself alive. A
parent that holds its children while each child holds its parent is the usual case, and sysl has a
dedicated construct for it.

A `weak T` is a non-owning reference. It does not keep its target alive, and when the last strong
reference goes away it becomes empty:

```sysl
struct Node
    label: string
    parent: weak Node

parent_of(n: &Node) -> string
    n.parent.get() match
        Some(p) -> p.label
        None -> "(none)"

var root: &Node = Node("root", None)
var child: &Node = Node("child", root)

print("root's parent:", parent_of(root))
print("child's parent:", parent_of(child))
```

```output
root's parent: (none)
child's parent: root
```

A weak reference is read with `get()`, which returns `Option[&T]`: a live strong reference, or
`None`. A weak reference therefore cannot dangle; by the time you hold something to use, it is known
to be alive.

No operator creates a weak reference. A `&T` becomes one wherever a `weak T` is expected, which above
is the struct field. Use `weak` where there is a real cycle, and not otherwise.

## When the last reference goes

When the last reference goes, the storage is returned and everything the value held is released.
Reference counting cannot close a file descriptor, unmap a region, or return a handle to the C
library that created it, because those are held as a raw pointer or an integer, and neither carries
ownership. `impl Drop for T` declares that cleanup once, beside the type:

```sysl
struct Handle
    id: int

impl Drop for Handle
    drop(self) = print("closing", self.id)

hold()
    var h: &Handle = Handle(7)
    print("working")

hold()
print("done")
```

```output
working
closing 7
done
```

`defer` (in [statements](/reference/statements/)) is the other mechanism, and the two complement
each other. `defer` covers exits a program can *name* in its source; a destructor also covers those
it cannot, such as an element of a container that is freed when the container goes out of scope. The
[reference](/reference/memory/) lists the four limits; the first to know is that a destructor runs
for a value held behind a `&T`.

## `*T`, the raw pointer

The third mode is C's pointer, with C's safety. It is written with a sigil you can search for, so the
unsafe code in a program is visible in the source.

```sysl
bump(n: *int)
    *n += 1

var counter = 41

bump(&counter)

print("counter:", counter)
```

```output
counter: 42
```

`&x` takes the address of something and `*p` reads or writes through a pointer, as in C. Nothing is
counted or checked, and a pointer can dangle. In exchange it has no overhead, which is what a device
driver, an allocator, or the internals of a data structure sometimes need.

A pointer also lets a type refer to *itself* without an `Option`. Selecting a field through a pointer
needs no `->`; `.` works because it has no other meaning there:

```sysl
struct Node
    value: int
    next: *Node

var third = Node(3, null)
var second = Node(2, &third)
var first = Node(1, &second)

var walk = &first
var total = 0

while walk != null
    total += walk.value
    walk = walk.next

print("chain:", total)
```

```output
chain: 6
```

`null` exists for `*T` and only for `*T`, so a null can appear only where you have chosen C's rules.

A location deep inside a structure can be given a shorter name with `ref`, which binds the storage
itself rather than a copy of its contents and keeps the checks a `*T` would bypass. It is a local
declaration, never a type, which is why it works without a borrow checker;
[the reference](/reference/memory/#ref-a-name-for-a-place) describes it, along with `volatile` for
storage that something other than the program also writes.

## What this costs

The compiler avoids counting where it is unnecessary. A local that never escapes its frame is not
heap-allocated; a closure that does not outlive its frame is inlined rather than boxed; a slice of a
local array stays on the stack when no view of it escapes. None of this needs an annotation, and the
compiler rejects the unsafe cases: returning a view of a local buffer is an error.

What remains is a retain and a release where a reference is actually shared. That is the cost of not
proving ownership to a borrow checker, and the language is designed around that choice.

---

Next: [arrays and slices](/tour/arrays/), two types that build on this chapter.
