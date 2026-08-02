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
end Point

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

## There is no allocation keyword

No `new`, no `malloc`, no `Rc::new`, no `.clone()`, no wrapper type to learn. The positions that
create a reference are the ones that already state a type — a declared local, a parameter, a return
type, a struct field, an enum variant's payload:

```sysl
struct Process
    id: int
    priority: int
end Process

spawn(id: int) -> &Process = Process(id, 0)

var p = spawn(7)

print("spawned:", p.id)
```

```output
spawned: 7
```

The return type says `&Process`, so the construction in the body allocates. Somewhere with no
expectation at all, a construction is a value. That is the whole rule.

## References are shared, and mutable through any alias

There is no borrow checker, so there is no exclusivity rule to satisfy. Many references may point at
one object, and any of them may write to it:

```sysl
struct Account
    owner: string
    balance: int
end Account

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

## A reference is never null

A `&T` always points at a live object. There is no null reference to check for and no way to make
one, which removes an entire category of bug rather than diagnosing it.

Something that may be absent is therefore an `Option`, and that is what makes a linked structure
look the way it does:

```sysl
struct Item
    label: string
    rest: Option[&Item]
end Item

count(list: Option[&Item]) -> int
    list match
        Some(item) -> 1 + count(item.rest)
        None -> 0
end count

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
end Node

parent_of(n: &Node) -> string
    n.parent.get() match
        Some(p) -> p.label
        None -> "(none)"
end parent_of

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
end Node

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

### `volatile` — storage the program is not the only one writing

`ptr_cast` gets a driver to a register block; it does not get it a *correct* one. An optimizer is
entitled to assume that reading the same storage twice gives the same value, that a store nobody
reads is a store nobody needs, and that two accesses in a row may be merged into one wider access.
Every one of those is false at a device, and the last of them is why a poll loop can spin forever on
the first value it read.

So sysl has C's qualifier, spelled where C spells it — in the type:

```sysl
struct Gpio
    input:  volatile u32
    output: volatile u32
    shadow: u32
end Gpio

var block = Gpio(0b1010u32, 0u32, 0u32)
var regs: *Gpio = &block

regs.output = 0b0110u32
regs.shadow = regs.output

print(regs.input, regs.output, regs.shadow)
```

```output
10 6 6
```

A `volatile` place is one whose reads and writes are **effects rather than value computations**, so
the compiler emits exactly the accesses the source wrote, exactly once each, in the order written.

Two things about it are worth carrying away. It **constrains the compiler, not the machine** — no
atomicity, no ordering against another core, no protection from a torn read, so a program reaching
for this word to share a counter between threads has written a race with a keyword in front of it.
And it is **per field**, which is what `shadow` above is there to show: a driver keeps ordinary
values beside its registers, and a qualifier on the whole struct would sweep them in and make every
touch of a cached flag an unoptimizable access.

It also qualifies **storage**, never a value — which is the rule the spelling follows from:

```sysl
var x: volatile u32 = 0u32

print(x)
```

```error
'volatile u32' is the type of *storage*, and this is a value — what a read of a volatile place hands back is an ordinary 'u32'. The qualifier goes where the storage is named: a struct field, an element, or the pointee of a '*T', as '*volatile u32'
```

What a load hands back is a number, and a number is not somewhere a device can write.

## `ref` — a name for a place

A place can be deep, and the two ways to shorten one both cost something. `var t = self.tasks[i]`
binds a **copy**, so every read and every write walks the path from the table again.
`&self.tasks[i]` gives the name back — and gives up bounds checking, `within` checking, invariant
re-checking and the guarantee at the top of this chapter, all in one step.

That is a cliff rather than a gradient, which is what `ref` is for. It binds a name to the place:

```sysl
struct Bank
    slot: [3]int

    set(*self, i: usize)
        ref e = self.slot[i]

        e = e + 42
end Bank

var b = Bank([1, 2, 3])

b.set(1usize)

print(b.slot[0], b.slot[1], b.slot[2])
```

```output
1 44 3
```

The place is evaluated **once**, where the binding is written — the index is computed once, the
bounds are checked once — and what the name means afterwards is the storage that was found, not the
expression that found it. A later `i += 1` leaves `e` naming the element it always named.

### It is a declaration, never a type

Which is the whole of why it can exist in a language with no borrow checker. `ref` may be written
only as a local declaration: there is no `ref` type, so one cannot be a field, a parameter, a return
type, an element, or a type argument. It cannot be captured by a closure, and it does not outlive
the block that declares it.

That restriction is what keeps the compiler's knowledge complete. A `*T` is a type, so the moment
one exists it can be carried somewhere the compiler has lost the path it came from. A ref never
travels, so the analyzer still holds the place expression, at the point it was written, in the same
body.

**At run time a ref stores an address. At compile time it remembers a place.** Both halves matter.
The address is what makes sixty-five path walks one. The remembered place is what keeps every check
a `*T` would have severed — the invariants of every struct the place lies inside, the `within` on a
constrained slot, the read-only-ness of storage reached through a `val`, and the bounds check the
subscript owed.

So a ref is **not a fourth memory mode**. It introduces no representation, no new type, and nothing
that can be stored: it is a second way to *say* a place the three modes already describe.

### What may be written

A ref's initializer must be a **place**. A call result has no address, so there is nothing for the
name to mean:

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

A ref inherits the place's writability and gets no modifier of its own — so a ref into a `val` may
be read and not written, exactly as the `val` may. Stating it twice would only create the chance to
state it wrong.

Note also that this is the *built-in* subscript. A user type's `b[i]` goes through the `Index`
trait, which is a call rather than a walk to an address, so there is no place there for a ref to
name.

### The one rule: what may move underneath it

A stored address is only as good as the storage staying where it is. C# has ref locals and needs no
rule like this, because a tracing collector keeps the old array alive when the variable is pointed
at a new one; sysl has no collector in this tier, so the same program would dangle:

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
assigned, and no mutating method may be called on a prefix of one.** The check is local and decided
from types alone; it never asks where the ref is *used*, which is the question that would need
lifetimes.

The second half is the one that catches a call rather than an assignment. A `*self` method may write
any part of its receiver, so a live ref into that receiver is refused the call — whether or not that
particular method reassigns anything, because deciding otherwise would mean reading the callee's
body, and a module compiles against its imports' signatures and never their bodies:

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

Which steps are hazards falls out of the model rather than being a list, and the discriminator is
**ownership**, not indirection. Only a step that *releases* something when it is overwritten can
strand a ref:

- a **`&T`** step and a **view** step are hazards, because overwriting either drops what it held and
  that release may be the last;
- a **`*T`** step is **not**. A raw pointer owns nothing, so `p = q` frees nothing and the storage a
  ref found through it stays exactly as alive as it was — no more, and no less;
- a **field, a fixed array, or an element of one** is not either: that storage *is* the enclosing
  object's bytes, so assigning to it overwrites the bytes rather than moving them.

The `*T` exclusion is not a concession, it is what makes the feature usable where it is wanted most.
A program that reaches its tables through a `*Self` receiver has no owning step anywhere in the
chain, so it is asked for nothing at all — every `self.…()` call stays legal, and the ref costs it
exactly nothing:

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

### A ref to a slot that holds a reference

`ref r = self.node`, where `node` is a `&Node`, names the **slot** and not the object in it. So the
binding takes no count — nothing new holds the object — and `r = other` is the assignment
`self.node = other` by another name, releasing what was there and retaining what arrives, in that
order.

The distinction matters because the other reading is available and wrong: if binding retained, a ref
would be a `&T` with extra steps, and the count it took would keep an object alive past the write
that replaced it.

### Where it comes from

The form is old, and none of the languages this tour has been comparing itself to have it — Swift,
Kotlin, Scala and Go are all silent. **Ada's `renames`** is the general case and evaluates the name
once at the declaration; **Fortran's `ASSOCIATE`** is the closest in shape, being block-scoped and
deliberately not a type; **C#'s `ref` locals** are the closest in spelling. C# also shows what the
restriction is worth: having added ref returns and ref fields, it spent several releases building
the escape analysis that keeping the form local avoids entirely.

Scala's by-name parameter is the thing this is *not*. `x: => T` re-evaluates at every use; a ref
evaluates once and remembers what it found.

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
