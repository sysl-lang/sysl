---
title: Vectors
summary: `<N>T` is an array that computes lane-wise — one instruction for N additions, one kernel for every register width, and it still compiles where there is no vector unit at all.
weight: 77
---

`<N>T` holds N lanes of `T` — the same values an `[N]T` holds, in the same order — with one
difference: its operators work on every lane at once.

```sysl
val a: <4>f32 = [1, 2, 3, 4]
val b: <4>f32 = [10, 20, 30, 40]

val c = a + b

print(c[0], c[3])
```

```output
11 44
```

That `+` is one machine instruction computing four additions. The two type constructors differ only
in their brackets because a vector **is** an array whose operators work lane-wise: `[4]f32` is
storage, and `<4>f32` is a register.

**Vector code runs on every target.** A vector wider than the hardware becomes several registers, and
on a machine with no vector unit it becomes ordinary scalar operations, so `<4>f32` compiles for a
Cortex-M0 exactly as for an Apple M-series; only the speed differs. Where speed matters, the choice
a program makes is how *wide* its vectors are.

## Writing a vector

**A literal** fills the lanes, and it must fill all of them: the lane count is part of the type.

```sysl
val v: <4>int = [1, 2, 3, 4]

print(v[0], v[3], v.len)
```

```output
1 4 4
```

**A scalar broadcasts** (a *splat*, the most common vector operation). A scalar written where a vector
is expected — at a binding, an operator, a return — is copied into every lane:

```sysl
val zero: <4>f32 = 0
val ones: <4>f32 = [1; 4]

half(v: <4>f32) -> <4>f32 = v * 0.5

print(zero[0], ones[3], half(ones)[0])
```

```output
0 1 0.5
```

**A declaration with no initializer** starts every lane at the lane type's zero.

**A lane is read by a constant index.** This is the one subscript in sysl that is not checked at run
time: a vector has no address, and an out-of-range lane read has no defined result at the machine
level. The index must therefore be a literal or a `const`, and it is checked at compile time:

```sysl
val v: <4>f32 = [1, 2, 3, 4]

print(v[4])
```

```error
<4>f32 has lanes 0 to 3, and this is 4
```

For a computed index, store the values in an array, whose subscript is checked at run time.

## What the operators do

A vector has exactly the operators its **lane** has, applied to every lane. Both sides must be the
same vector, or one side a scalar that broadcasts:

| lanes | operators |
|---|---|
| an integer | `+` `-` `*` `&` <code>&#124;</code> `^` `<<` `>>`, and unary `-` `~` |
| a float | `+` `-` `*` `/`, and unary `-` |
| a fixed-point type | `+` `-` `*`, and unary `-` — saturating and rounding as the scalar does |
| `bool` (a mask) | `&` <code>&#124;</code> `^` |

**Integer `/` and `%` are refused.** Scalar integer division traps on a zero divisor and on signed
overflow, but a register traps as a whole or not at all, so a vector divide would either drop the
check or trap for lanes that were valid. No processor sysl targets has an integer vector divide, so
a scalar loop compiles to the same instructions the hardware would use:

```sysl
val a: <4>int = [10, 20, 30, 40]

f() -> unit
    val b = a / a
```

```error
'/' is not defined on <4>int
```

Operators do not convert between vector types. Two different widths, or two different lane types,
are two different types:

```sysl
val a: <4>f32 = [1, 2, 3, 4]
val b: <8>f32 = [1, 2, 3, 4, 5, 6, 7, 8]

f() -> unit
    val c = a + b
```

```error
'+' needs matching types, got <4>f32 and <8>f32
```

## Masks, and the lane-wise `if`

A comparison between vectors yields a **mask** — a `<N>bool`. It is an ordinary value: bind it, combine
it, reduce it.

```sysl
val a: <4>f32 = [1, 5, 3, 7]

val small = a < 4

print(small.any(), small.all())
```

```output
true false
```

**`select` chooses by mask.** It is a method rather than an `if`, because an `if` evaluates one side
or the other, and a register cannot take one branch in some lanes and the other in the rest. Both
sides are computed, and the mask picks between the results lane by lane:

```sysl
val a: <4>f32 = [1, 5, 3, 7]
val b: <4>f32 = [4, 2, 6, 0]

val lo = (a < b).select(a, b)
val clamped = (a > 4).select(4, a)

print(lo[0], lo[1], clamped[1], clamped[3])
```

```output
1 2 4 4
```

Either side of a `select` may be a scalar, which broadcasts: `(v > hi).select(hi, v)` clamps `v` to
`hi` with no vector constructed around the bound.

Two masks combine with the bitwise operators, **not** with `&&`, because `&&` short-circuits and a
vector cannot short-circuit per lane.

```sysl
val a: <4>int = [1, 2, 3, 4]

val inside = (a > 1) & (a < 4)

print(inside.any(), inside.all())
```

```output
true false
```

For the same reason **a comparison chain has no lane-wise form**. `1 < a < 4` joins its two
comparisons with `&&`, so it is refused rather than silently rewritten with `&`:

```sysl
val a: <4>int = [1, 2, 3, 4]

f() -> unit
    val m = 1 < a < 4
```

```error
compare two vectors at a time and combine the masks with '&'
```

## Reductions

A reduction collapses a vector to one scalar. `sum`, `min` and `max` apply to a numeric vector; `any`
and `all` to a mask.

```sysl
dot(a: <4>f32, b: <4>f32) -> f32 = (a * b).sum()

val v: <4>f32 = [3, 1, 4, 1.5]
val w: <4>f32 = [1, 2, 3, 4]

print(v.sum(), v.min(), v.max(), dot(v, w))
```

```output
9.5 1 4 23
```

A float sum is computed as a **tree** rather than left to right, so that it maps to vector
instructions; it may therefore differ in the last bit from adding the lanes in order. An integer sum
wraps at the lane width, exactly as scalar integer arithmetic does.

## Loading and storing

A vector holds the lanes a kernel computes with; an [array or a slice](/reference/arrays/) holds the
program's data. Two methods move a run of elements between them. They belong to the array or slice
rather than to the vector, because the move needs an address and a length, and a vector has neither.

```sysl
var xs: [8]f32 = [1, 2, 3, 4, 5, 6, 7, 8]
var out: [8]f32

val v: <4>f32 = xs.load(2)

out.store(0, v * 10)

print(v[0], v[3], out[0], out[3])
```

```output
3 6 30 60
```

`xs.load(i)` reads the run of lanes starting at element `i`; `out.store(i, v)` writes one back. The
store needs a slice or an array it may write, so a `[]const T` is refused exactly as `xs[i] = v`
would be.

These are ordinary names, so **a `load` or `store` you declare yourself takes precedence**: an `impl`
block for a slice is found before the built-in methods, which apply only to a receiver with no member
of that name. `sysl.sync.Atomic`, for example, has its own `load` and `store`, unaffected by these.

### Where the width comes from

**A load takes its width from whatever receives the value.** A slice's length does not determine it,
and guessing could silently read the wrong run. A binding's annotation gives the width, as do a
parameter and a declared result:

```sysl
first(v: <4>f32) -> f32 = v[0]

grab[const W: usize](xs: []const f32) -> <W>f32 = xs.load(0)

var xs: [8]f32 = [1, 2, 3, 4, 5, 6, 7, 8]

val annotated: <4>f32 = xs.load(4)
val declared: <2>f32 = grab(xs[..])

print(annotated[0], first(xs.load(1)), declared[1])
```

```output
5 2 2
```

**An operand of an operator gives the width too**, so a load beside a vector takes its lane count
from it. Types are resolved in three steps, extending the rule for literals: an operand with a type
of its own is resolved first, a load takes the width that operand gives, and a bare literal takes
the type the other two determined.

```sysl
var xs: [4]f32 = [1, 2, 3, 4]
val by: <4>f32 = 10
var out: [4]f32

out.store(0, xs.load(0) * by + 1)

print(out[0], out[3])
```

```output
11 41
```

**A position that accepts any width gives none.** A store accepts any width, so there is nothing to
infer from:

```sysl
f(xs: []const f32, out: []f32)
    out.store(0, xs.load(0))
```

```error
how many lanes it takes is the vector type's to say
```

Annotate a binding there instead.

### What a load or store guarantees

**It is bounds-checked as a whole run.** `xs.load(3)` on a five-element array traps even though
element 3 exists, because elements 3 through 6 do not. This is the only vector operation with a
run-time check; every other operation is a register operation that cannot fail, so a vector cannot
read or write past the end of an array.

**A partial run at the end traps too, and the scalar tail is written by hand.** An array whose length
is not a multiple of the width ends with fewer elements than lanes; the kernel below shows how to
handle it.

**The alignment assumed is the element's.** A `[]f32` guarantees four-byte alignment and nothing
about where a run begins, so the vector's own alignment cannot be assumed. On every machine sysl
targets, an unaligned vector load of aligned data costs the same as an aligned one.

**`volatile` elements are refused rather than widened.** A single instruction cannot preserve one
ordered access per element. `volatile <4>u32` qualifies the whole register instead.

```sysl
f(regs: []volatile u32) -> u32
    val v: <4>u32 = regs.load(0)

    v[0]
```

```error
the qualifier cannot be kept
```

A `*T` is refused for a different reason: it carries no length to check the run against. Take a
slice of the elements first.

## One kernel, every width

A lane count is a [value parameter](/reference/generics/), so it is inferred from the argument as an
array's length is, and one generic kernel serves every width.

```sysl
scale[const W: usize](v: <W>f32, by: f32) -> <W>f32 = v * by

val four: <4>f32 = [1, 2, 3, 4]
val eight: <8>f32 = [1, 2, 3, 4, 5, 6, 7, 8]

val a = scale(four, 2)
val b = scale(eight, 3)

print(a[3], b[7], a.len, b.len)
```

```output
8 24 4 8
```

Neither call states a width. The body is compiled twice, into different instructions — four lanes
wide and eight. A mask inside a generic kernel has the same width, so a comparison there is a
`<W>bool` and the `select` works at whatever `W` is:

```sysl
clamp_low[const W: usize](v: <W>f32, lo: f32) -> <W>f32 = (v < lo).select(lo, v)

val four: <4>f32 = [1, -2, 3, -4]
val two: <2>f32 = [-1, 5]

print(clamp_low(four, 0)[1], clamp_low(two, 0)[0])
```

```output
0 0
```

In C, the same problem is usually solved by writing the kernel once per instruction set behind
`#ifdef`; Box2D's contact solver, for example, contains four copies of itself.

### A kernel over an array

Combining loads, stores and a width parameter gives a complete kernel in one body: the loads, the
arithmetic and the store all take their width from `W`, and a scalar loop handles the remaining
elements:

```sysl
scale[const W: usize](xs: []const f32, out: []f32, by: <W>f32)
    var i: usize = 0

    while i + W <= xs.len
        val v: <W>f32 = xs.load(i)

        out.store(i, v * by)
        i += W

    while i < xs.len
        out[i] = xs[i] * by[0]
        i += 1

val src: [10]f32 = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]

var four: [10]f32
var eight: [10]f32

val by4: <4>f32 = 3
val by8: <8>f32 = 3

scale(src[..], four[..], by4)
scale(src[..], eight[..], by8)

print(four[0], four[9], eight[0], eight[9])
```

```output
3 30 3 30
```

Ten elements is not a multiple of either width, so both instantiations run the tail loop, and the two
results agree.

**`by` is a vector rather than an `f32`, because that is where `W` comes from.** A written type
argument at a call is refused, so a kernel whose parameters are all slices cannot be given its
width. In practice a SIMD kernel's constants are broadcast across the lanes anyway, but design the
signature with this in mind.

## What a vector does not have

**Shuffles and swizzles.** There is no way to rearrange lanes in one step. A gather is a loop that
writes the elements into a scratch array followed by one `load` of the whole run — correct on every
target, but not the single instruction a shuffle would be. Gathers are where hand-written SIMD
variants for different instruction sets differ most, and they do not generalize over a width.

**A masked load or store.** A run that would reach past the end traps rather than reading only the
lanes that exist. Write a scalar tail, as the kernel above does.

**A place in a C signature.** A vector may not be passed to or returned from a C function. The
register it would occupy differs by target and by the instruction-set extensions the C side was
compiled with, and a mismatch would produce a call that links but corrupts its arguments. Pass the
lanes through memory instead — a `*f32` and a count, as C's own SIMD functions usually take:

```sysl
extern "process_lanes" process(v: <4>f32) -> unit
```

```error
how a vector reaches a C function differs by target
```

## Lanes

A lane is a scalar: an integer, a fixed-point type, a float, `bool` or `char`. A fixed-point lane
computes as its scalar does, a `<2>q15` sum being `QADD16` on a Cortex-M4; [Fixed-point
numbers](/reference/fixed-point/) describes what it supports. An aggregate cannot be a lane — there is
no vector of structs — and neither can a `volatile` type, since a single load cannot give per-lane
access ordering. `volatile <4>u32` qualifies the whole register instead.

```sysl
struct Point
    x: int
    y: int

f() -> unit
    var v: <4>Point
```

```error
a vector's lanes are scalars
```

A **constrained** lane is allowed, and computes at its base type exactly as a scalar of that subtype
does:

```sysl
type Small = int within 0..100

f() -> unit
    val v: <4>Small = [1, 2, 3, 4]
    val w = v + v

    print(w[0], w[3])

f()
```

```output
2 8
```
