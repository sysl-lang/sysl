---
title: sysl.alloc
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.alloc
summary: "Allocation that may fail, answered rather than trapped."
---

**Every allocation the language makes traps on a null from the heap** -- a box, a slice, a string, a
buffer's growth -- before anything is stored (`reference/memory.md § An allocation that may fail`).
That is the right default for a program that has nowhere to go when memory runs out, and the wrong one
for a kernel, whose system calls answer `ENOMEM` and carry on. So each allocating member of the
library's containers has **exactly one fallible twin**, named `try_` and the member's own name, which
answers `Result[_, AllocError]` and never traps on the heap's refusal: `Buf.try_push`,
`Buf.try_reserve`, `try_buf_with_capacity`, `StrBuilder.try_push`, and `try_box` here for a box.

**A refused twin leaves nothing half-built**: the buffer it was asked to grow keeps its elements, its
count and its storage; the value it was handed is not kept; nothing it allocated before the refusal is
left allocated. Rust's `try_reserve` family and Rust for Linux's `try_push` and `Box::try_new` are the
same bargain.

The twins are built on two raw-tier forms the compiler answers, `try_slot_storage(n)` and
`try_heap(v)` (`reference/memory.md § Slot storage`); a program writes the twins, not the forms.

## Index

[`try_box`](#try_box) [`AllocError`](#allocerror) [Display for AllocError](#display-for-allocerror)

## Functions

### `try_box`

```sysl
try_box[T](v: T) -> Result[&T, AllocError]
```

`v` on the heap, behind a `&T` -- the box a position expecting `&T` makes of a `T` -- or
`OutOfMemory` where the heap refused it, in which case nothing is kept of `v`.

It is the fallible twin of that construction: `val p: &Point = Point(1, 2)` traps on a refused heap,
and `val p = try_box(Point(1, 2))?` answers the refusal.

## Types

### `AllocError`

```sysl
enum AllocError
    CapacityOverflow
    OutOfMemory
```

Why an allocation was not made.

- `CapacityOverflow` -- the size asked for does not fit in a `usize`: a length plus what is being
  added, or a count of elements times their size. Nothing was asked of the heap.
- `OutOfMemory` -- the heap answered null.

| Member | Signature | Description |
|---|---|---|
| `message` | `message(self) -> string` | The words a report uses. |

## Implementations

### Display for AllocError

```sysl
impl Display for AllocError
```
