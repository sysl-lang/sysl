---
title: Memory
summary: Three modes, chosen per declaration. No garbage collector, no borrow checker, and no allocation keyword.
weight: 50
---

This is the chapter that makes sysl a different language rather than a different syntax. Everything
before it would have read much the same in half a dozen languages; nothing after it reads right
until you know this.

A systems language is used for the control it gives you over where things live and when they die.
The two well-known ways to keep that control and stay safe are a garbage collector, which takes away
the *when*, and a borrow checker, which keeps both but asks you to prove your program correct to a
checker as you write it. sysl takes a third road: **reference counting**, with the choice of mode
written on each declaration.

## The three modes

| mode | what it is | who frees it |
|---|---|---|
| `T` | a value — on the stack, in a register, or inline in something bigger | nobody; it goes when the frame does |
| `&T` | a counted reference to a heap object | the compiler, when the last reference goes |
| `*T` | a raw pointer — C's pointer | you |

The choice is **per declaration**, not per type. The same `struct Point` can be a value here and a
heap object there, and it is the declaration that says which:

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

Look closely at those two lines, because the difference between them is the whole idea:
**the constructions are identical.** `Point(3, 4)` did not ask to be on the heap. The annotation
`&Point` is an *expectation*, and writing an ordinary construction where a `&T` is expected is what
puts the object there.

No `new`, no `malloc`, no `Rc::new`, no `.clone()`, no wrapper type to learn. The positions that
create a reference are the ones that already state a type — a declared local, a parameter, a return
type, a struct field, an enum variant's payload. A function whose return type reads `-> &Process`
allocates in its body and one reading `-> Process` does not, with the same construction written in
each. Somewhere with no expectation at all, a construction is a value. That is the whole rule.

## References are shared, and never null

There is no borrow checker, so there is no exclusivity rule to satisfy. Many references may point at
one object, and any of them may write to it:

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

`alias = shared` did not copy the account — it made a second reference to it, and incremented the
count. Compare that with the struct chapter, where `b = a` on a value copied it. Same syntax, and
the declared mode is what decides.

If you are coming from Rust: yes, that is two live mutable aliases, and yes, it is allowed. Handing
that back is the deliberate trade. Reference counting pays for it at run time — a retain here, a
release there — and buys a language you can be productive in on the first day.

**A `&T` always points at a live object.** There is no null reference to check for and no way to make
one, which removes an entire category of bug rather than diagnosing it.

Something that may be absent is therefore an `Option`, and that is what makes a linked structure
look the way it does:

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

The `match` is not ceremony around a null check — it is the only way to get at the item, so the
absent case cannot be forgotten.

## Cycles, and `weak`

Reference counting has one honest weakness: a cycle of strong references keeps itself alive. A
parent holding its children while each child holds its parent is the shape that does it, and it
is common enough to have a purpose-built answer.

A `weak T` is a non-owning reference. It does not keep its referent alive, and when the last strong
reference goes away it simply becomes empty:

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

Reading one is a *question*, asked with `get()`, and the answer is `Option[&T]` — a live strong
reference, or `None`. So a weak reference can never dangle: by the time you have something to use,
you have proved it is still there.

There is no operator that makes a weak reference. A `&T` becomes one wherever a `weak T` is what was
asked for, which above is the struct field. Reach for `weak` when you have a genuine cycle, and not
before.

## When the last reference goes

ARC returns the storage and releases whatever the value held. What it cannot do is close a
descriptor, unmap a region, or hand a handle back to the C library that made it — those live behind a
raw pointer or an integer, and nothing about either says it is owned. `impl Drop for T` is where that
is said, once, beside the type:

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

`defer` (in [statements](/reference/statements/)) is the other way to say it, and neither replaces
the other: `defer` covers every site a program can *name*, and a destructor covers the deaths it
cannot — an element of a container that goes out of scope dies at a point with no expression in the
source. The [reference](/reference/memory/) has the four limits, of which the one to know first is
that a destructor runs for a value held behind a `&T`.

## `*T` — the way out

The third mode is C's pointer, and it is exactly as safe as C's pointer. It is spelled with a sigil
you can grep for, which is the point: the unsafe tier is visible in the source rather than hidden
behind a keyword nobody scans for.

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

`&x` takes the address of something and `*p` reads or writes through a pointer, both as in C.
Nothing is counted, nothing is checked, and a pointer can dangle. In exchange it costs nothing at
all, which is what a device driver, an allocator, or the inside of a data structure sometimes needs.

A pointer is also how a type reaches *itself* without an `Option` — and selecting through one needs
no `->`, because there is nothing else `.` could mean:

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

`null` exists for `*T` and only for `*T`. That is the trade the three modes make explicit: the one
place a null can appear is the one place you asked for C's rules.

A place deep inside a structure can be given a shorter name with `ref`, which binds the storage
rather than a copy of what is in it and keeps every check a `*T` would have severed. It is a local
declaration and never a type, which is what lets it exist in a language with no borrow checker —
[the reference](/reference/memory/#ref-a-name-for-a-place) has it, along with `volatile` for
storage the program is not the only one writing.

## What this costs

Less than you would think, because the compiler knows when counting is pointless. A local that
never escapes its frame is not heap-allocated at all; a closure that does not outlive its frame is
inlined rather than boxed; a slice of a local array stays on the stack when nothing takes a view of
it away. You do not annotate any of that, and you cannot get it wrong — try to return a view of a
local buffer and the compiler will tell you.

What remains is a retain and a release at the points where a reference is genuinely shared. That is
the price of not having to prove anything to a borrow checker, and it is the trade the whole
language is built around.

---

Next: [arrays and slices](/tour/arrays/) — two types that lean on everything in this chapter.
