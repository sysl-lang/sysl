---
title: The hint module
summary: "`sysl.hint` — `black_box`, an identity the optimizer must treat as opaque, and `prefetch`, a request to bring memory into the cache before it is read."
weight: 62
---

`sysl.hint` holds what a program tells the optimizer about itself. Nothing in it changes what a
program computes; it changes only what the compiler may assume while making the code faster.

## `black_box`

```sysl
black_box[T](x: T) -> T
```

`black_box(x)` answers `x`, unchanged. What makes it useful is that the compiler may not assume
anything about that answer: not that it is the constant that went in, and not that nobody reads it.
So arithmetic on the answer is done by the machine at run time, and work whose result goes into it
is not thrown away as unused.

A test needs the first of those to check the code the machine runs rather than the value the
compiler worked out while compiling. A benchmark needs both: its input through `black_box` so the work
is not folded, and its result through `black_box` so the work is not dropped.

```sysl
import sysl.hint.black_box

struct Pair
    a: int
    b: int

sum_squares(n: int) -> int
    var sum = 0

    for i in 0..<n do sum += i * i

    sum
end sum_squares

print(black_box(6) * 7)
print(black_box(Pair(5, 9)).b)
print(black_box("abcdef"[1..<4]))
print(black_box(Some(4)))

black_box(sum_squares(black_box(1000)))
```

```output
42
9
bcd
Some(4)
```

Without `black_box`, `6 * 7` is `42` before the program exists, and the last line is a call whose
result nobody reads, which the optimizer deletes along with the loop inside it.

**It takes a value of any type**: an integer of any width, a float, a struct, a slice, a `string`, an
`Option`, and a reference to a type with a `Drop`, which comes back as the same object and is still
released once. A generic function passes its own type parameter through it. That is what a
hand-written alternative cannot do: `volatile` qualifies a scalar, so a `volatile` field can hide a
`u32` but not a `T`.

### How it lowers, and what it costs

The value is stored in a slot of its own, and the slot's address is handed to an empty block of
assembly that the compiler must assume reads and writes memory:

```
call void asm sideeffect "", "r,~{memory},~{cc}"(ptr %slot)
```

The block contains no instruction. What it costs is a store of the value and a load of it back, which
is what keeping it in memory needs, and the call itself is inlined away. It is the same lowering
Rust's `std::hint::black_box` uses. It needs no C library and no operating system, so it builds for
every target here, Thumb, RISC-V and wasm32 among them, and a function marked
[`@no_alloc`](/reference/attributes/#no-alloc-on-a-function-one-entry-point-that-makes-no-heap-storage) may call it.

### What it is not

**It is not a guarantee about timing.** It does not make code constant-time, and code that has to be
constant-time for security needs more than a hint. **It orders nothing between threads**, which is
[`sysl.sync`](/library/sync/)'s business.

**It cannot run while compiling.** A `const` function runs at compile time, and a value computed then is
exactly what `black_box` exists to prevent, so a `const` function that calls it is refused:

```sysl
import sysl.hint.black_box

const folded() -> int = black_box(3)
```

```error
'folded' is 'const', so it runs while compiling, and it calls 'sysl.hint.black_box', which holds an 'asm' block
```

## `prefetch`

```sysl
enum Access
    Read
    Write

enum Locality
    None
    Low
    Medium
    High

prefetch[T](p: *T, access: Access = Read, locality: Locality = High)
```

`prefetch(p)` asks the machine to start bringing the memory at `p` into its cache, so that a read of
it a little later does not wait for memory. A loop over data too large for the cache prefetches a
fixed distance ahead of the element it is working on:

```sysl
import sysl.hint.{prefetch, Access, Locality}

sum_ahead(xs: []int) -> int
    var sum = 0
    val base = &xs[0]

    for i in 0..<xs.len
        prefetch(&base[i + 16])
        sum += xs[i]

    sum

scale(xs: []int, k: int)
    for i in 0..<xs.len
        prefetch(&xs[i], Write, Low)
        xs[i] *= k

var xs = [1, 2, 3, 4, 5, 6]

scale(xs, 10)
print(sum_ahead(xs))
```

```output
210
```

**It reads nothing and changes nothing**, so what the program computes is the same with or without
it. Each call is one instruction and no call: `prfm pldl1keep` on AArch64, `prefetcht0` on x86-64,
`pld` on an Armv7-M or Armv8-M core. A processor that has no prefetch instruction (a Cortex-M0, a
RISC-V core without Zicbop, wasm32) compiles it to nothing, so a program that prefetches builds for
every target, and a function marked
[`@no_alloc`](/reference/attributes/#no-alloc-on-a-function-one-entry-point-that-makes-no-heap-storage)
or `@pure` may call it.

**`access` says what the line is wanted for**: `Read`, or `Write`, which on a machine that has one
asks for the line in a state it may be written in. **`locality` says how long it is wanted**: `High`,
the default, keeps it in every level of the cache; `Medium` and `Low` keep it further out; `None` is
for data read once, which should not push anything else out. They are the four levels LLVM's
`llvm.prefetch` takes, in the same order.

### It takes a raw pointer, and never faults

The address is any `*T`: `&x` of a local or a module `var`, `&xs[i]` of an array, a slice or a `Buf`
element, `&s.field`, or a pointer a C function handed back. **A prefetch never faults**, whatever the
address holds: one past the end of a buffer, one nothing is mapped at, or `null`, is prefetched as
harmlessly as any other, because the machine's prefetch instructions are defined to ignore an address
they cannot reach.

**Reaching past the end is written through a raw pointer**, and that is the ordinary case: the loop
above prefetches sixteen elements ahead, which runs off the end for its last sixteen. `&xs[i]` keeps
its bounds check here as everywhere, since it is the same expression a store goes through; `&base[i]`
on a `*T` is C's unchecked address, as it always is
([memory.md](/reference/memory/#arithmetic-two-directions-one-spelling-each)), and an
[`@unchecked`](/reference/arrays/#unchecked-access) block leaves the check out of `&xs[i]` too.
Neither address is ever read, so neither can trap or fault. A checked subscript past the end traps
before there is an address to prefetch:

```sysl
import sysl.hint.prefetch

var xs = [1, 2, 3]

prefetch(&xs[3])
print("unreached")
```

```output trap
```

### The two choices are constants

`access` and `locality` choose the instruction, so they are settled while compiling: a variant written
at the call, a module `val` holding one, or a generic's `const` parameter. A value read while the
program runs — a parameter, a local — is refused at the argument:

```sysl
import sysl.hint.{prefetch, Access}

warm(p: *int, how: Access) = prefetch(p, how)
```

```error
'prefetch' takes its 'access' as a constant — it chooses the instruction, so it is settled while compiling: write a variant (Read, Write), a module 'val' holding one, or a generic's 'const' parameter
```

A function that passes the choice on takes it as a `const` parameter, so each instantiation is a
constant:

```sysl
import sysl.hint.{prefetch, Access, Locality}

warm[const A: Access, const L: Locality](p: *int) = prefetch(p, A, L)

var n = 7

warm[Write, None](&n)
print(n)
```

```output
7
```

**It cannot run while compiling**, for the same reason `black_box` cannot: a `const` function that
prefetches is refused, since the processor it asks exists only when the program runs.
