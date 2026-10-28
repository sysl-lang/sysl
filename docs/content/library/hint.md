---
title: The hint module
summary: "`sysl.hint` — `black_box`, an identity the optimizer must treat as opaque, for a value of any type and on every machine."
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
