---
title: Vectors
summary: `<N>T` is an array that computes lane-wise — one instruction for N additions, one kernel for every register width, and it still compiles where there is no vector unit at all.
weight: 77
---

`<N>T` holds N lanes of `T` — the same values an `[N]T` holds, in the same order — with one
difference: its operators work on every lane at once.

```sysl
val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
val b: <4>f32 = [10.0, 20.0, 30.0, 40.0]

val c = a + b

print(c[0], c[3])
```

```output
11 44
```

That `+` is one machine instruction computing four additions. The two type constructors differ by one
bracket pair because a vector **is** an array that computes lane-wise, and the spelling says so:
`[4]f32` is storage, `<4>f32` is a register.

**You never have to ask whether a machine can run this.** A vector wider than the hardware becomes
several registers, and one on a machine with no vector unit at all becomes ordinary scalar
operations — so `<4>f32` compiles for a Cortex-M0 exactly as it compiles for an Apple M-series, and
the only thing that changes is how fast it goes. The question a program asks is how *wide* to go
where it cares about speed, never whether it may write one.

## Writing one down

**A literal** fills the lanes, and it has to fill all of them: the lane count is part of the type, so
there is no equivalent of a slice dropping its length.

```sysl
val v: <4>int = [1, 2, 3, 4]

print(v[0], v[3], v.len)
```

```output
1 4 4
```

**A scalar broadcasts** — the *splat*, and the commonest thing any vector code does. Write the scalar
and it goes into every lane, at a binding, at an operator, at a return, anywhere a vector is wanted:

```sysl
val zero: <4>f32 = 0.0
val ones: <4>f32 = [1.0; 4]

half(v: <4>f32) -> <4>f32 = v * 0.5

print(zero[0], ones[3], half(ones)[0])
```

```output
0 1 0.5
```

**A declaration with no initializer** starts every lane at the lane type's zero.

**A lane is read by a constant index.** This is the one subscript in sysl that is not checked while
the program runs, and the reason is that there is nothing to check against: a vector has no address,
and an out-of-range lane read has no defined answer at the machine level rather than trapping. So the
index has to be a literal or a `const`, and it is checked where it is written:

```sysl
val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]

print(v[4])
```

```error
<4>f32 has lanes 0 to 3, and this is 4
```

If you need a computed index, you want the values in an array — whose checked subscript already
answers.

## What the operators do

A vector has exactly the operators its **lane** has, applied to every lane. Both sides must be the
same vector, or one side a scalar that broadcasts:

| lanes | operators |
|---|---|
| an integer | `+` `-` `*` `&` <code>&#124;</code> `^` `<<` `>>`, and unary `-` `~` |
| a float | `+` `-` `*` `/`, and unary `-` |
| `bool` (a mask) | `&` <code>&#124;</code> `^` |

**Integer `/` and `%` are refused**, and it is worth knowing why rather than reading it as an
omission. Scalar integer division traps on a zero divisor and on the one signed overflow; a register
traps as a whole or not at all, so a vector would either drop the check or trap for lanes that were
perfectly fine. No processor sysl targets has an integer vector divide anyway, so the loop you write
instead is what the hardware was going to do:

```sysl
val a: <4>int = [10, 20, 30, 40]

f() -> unit
    val b = a / a
```

```error
'/' is not defined on <4>int
```

There is no promotion, exactly as there is none between scalars. Two different widths, or two
different lane types, are two different types:

```sysl
val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
val b: <8>f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]

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
val a: <4>f32 = [1.0, 5.0, 3.0, 7.0]

val small = a < 4.0

print(small.any(), small.all())
```

```output
true false
```

**`select` is how a mask chooses**, and it is a method rather than a keyword because it cannot be
`if`. An `if` branches — it evaluates one side or the other — and a register has no way to take one
branch in two lanes and the other in the remaining two. So both sides are computed and the mask picks
between the results, lane by lane:

```sysl
val a: <4>f32 = [1.0, 5.0, 3.0, 7.0]
val b: <4>f32 = [4.0, 2.0, 6.0, 0.0]

val lo = (a < b).select(a, b)
val clamped = (a > 4.0).select(4.0, a)

print(lo[0], lo[1], clamped[1], clamped[3])
```

```output
1 2 4 4
```

Either side of a `select` may be a scalar, which broadcasts — `(v > hi).select(hi, v)` is what
clamping looks like and needs no construction written around the bound.

Two masks combine with the bitwise operators, and **not** with `&&`. That is not an oversight: `&&`
short-circuits, and there is no such thing as short-circuiting per lane.

```sysl
val a: <4>int = [1, 2, 3, 4]

val inside = (a > 1) & (a < 4)

print(inside.any(), inside.all())
```

```output
true false
```

For the same reason **a comparison chain has no lane-wise form**. `1 < a < 4` joins its two links with
`&&`, so it is refused rather than quietly turned into an `&` that would look like the scalar spelling
and mean something different:

```sysl
val a: <4>int = [1, 2, 3, 4]

f() -> unit
    val m = 1 < a < 4
```

```error
compare two vectors at a time and combine the masks with '&'
```

## Reductions

A reduction collapses a vector to one scalar. `sum`, `min` and `max` are a numeric vector's; `any` and
`all` are a mask's.

```sysl
dot(a: <4>f32, b: <4>f32) -> f32 = (a * b).sum()

val v: <4>f32 = [3.0, 1.0, 4.0, 1.5]
val w: <4>f32 = [1.0, 2.0, 3.0, 4.0]

print(v.sum(), v.min(), v.max(), dot(v, w))
```

```output
9.5 1 4 23
```

A float sum is computed as a **tree** rather than left to right, which is what makes it worth an
instruction instead of a loop — so it may differ in the last bit from adding the lanes yourself in
order. An integer sum wraps at the lane width, exactly as scalar integer arithmetic does.

## Reaching memory

A vector holds the lanes a kernel computes with; an [array or a slice](/reference/arrays/) holds the
data a program has. Two methods move a run between them, and they belong to the array or the slice
rather than to the vector — what the move needs is an address and a length, and a vector has neither.

```sysl
var xs: [8]f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
var out: [8]f32

val v: <4>f32 = xs.load(2)

out.store(0, v * 10.0)

print(v[0], v[3], out[0], out[3])
```

```output
3 6 30 60
```

`xs.load(i)` reads the run of lanes starting at element `i`; `out.store(i, v)` writes one back. The
store needs a slice or an array it may write, so a `[]const T` is refused exactly as `xs[i] = v`
would be.

These are two ordinary words, so **a `load` or `store` you have declared yourself wins**: an `impl`
block for a slice answers before the compiler's does, and only a receiver with no member of that
name reaches these. `sysl.sync.Atomic` has had a `load` and a `store` of its own since long before
vectors existed, and they still mean what they meant.

### Where the width comes from

**A load takes its width from whatever receives the value.** A slice has whatever length it has, so
it cannot say — and guessing would be the one mistake that silently takes the wrong run. A binding's
annotation says it, and so do a parameter and a declared result:

```sysl
first(v: <4>f32) -> f32 = v[0]

grab[const W: usize](xs: []const f32) -> <W>f32 = xs.load(0)

var xs: [8]f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]

val annotated: <4>f32 = xs.load(4)
val declared: <2>f32 = grab(xs[..])

print(annotated[0], first(xs.load(1)), declared[1])
```

```output
5 2 2
```

**An operand of an operator is such a place too**, so a load standing beside a vector reads its lane
count off it. There are three tiers, which is `01`'s literal rule with one more step: an operand
carrying a type of its own is read first, a load is read at what that one said, and a bare literal is
read last at whatever the two of them settled.

```sysl
var xs: [4]f32 = [1.0, 2.0, 3.0, 4.0]
val by: <4>f32 = 10.0
var out: [4]f32

out.store(0, xs.load(0) * by + 1.0)

print(out[0], out[3])
```

```output
11 41
```

**What is not such a place is one where any width would do.** A store takes whatever it is handed, so
every width type-checks and there is genuinely nothing to infer:

```sysl
f(xs: []const f32, out: []f32)
    out.store(0, xs.load(0))
```

```error
how many lanes it takes is the vector type's to say
```

Say it on a binding there, which is one line and reads better in a kernel anyway.

### What the run promises

**It is bounds-checked, and checked as a whole run.** `xs.load(3)` on a five-element array traps
even though element 3 exists, because elements 3 through 6 do not. This is the only vector operation
with a run-time test: everything else is a register operation that cannot fail, and a vector is not
a hole through which a program reaches past the end of an array.

**A partial run at the end traps too, and the scalar tail is yours to write.** An array whose length
is not a multiple of the width ends with fewer elements left than there are lanes; the kernel below
is what that looks like.

**The alignment claimed is the element's.** A `[]f32` promises four bytes and says nothing about
where a run begins, so a vector's own alignment would be a claim the type does not support. Every
machine sysl targets has an unaligned vector load costing what the aligned one costs on aligned
data, so the honest number is also the free one.

**`volatile` elements are refused rather than quietly widened.** One access per element is not one
access, and a single instruction cannot promise per-lane ordering. `volatile <4>u32` is the spelling
that means something, and it qualifies the whole register.

```sysl
f(regs: []volatile u32) -> u32
    val v: <4>u32 = regs.load(0)

    v[0]
```

```error
the qualifier cannot be kept
```

A `*T` is refused for a different reason: it carries no length, so there is nothing to check the run
against. Take a slice of the elements first.

## One kernel, every width

This is what the feature is for, and it needs nothing of its own: a lane count is a
[value parameter](/reference/generics/), so it binds from the argument the way an array's length does.

```sysl
scale[const W: usize](v: <W>f32, by: f32) -> <W>f32 = v * by

val four: <4>f32 = [1.0, 2.0, 3.0, 4.0]
val eight: <8>f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]

val a = scale(four, 2.0)
val b = scale(eight, 3.0)

print(a[3], b[7], a.len, b.len)
```

```output
8 24 4 8
```

Neither call writes a width. One body is compiled twice, and the two hold different instructions —
four lanes wide and eight. The mask inside a generic kernel follows the same width, so a comparison
in there is a `<W>bool` and the `select` chooses at whatever W turned out to be:

```sysl
clamp_low[const W: usize](v: <W>f32, lo: f32) -> <W>f32 = (v < lo).select(lo, v)

val four: <4>f32 = [1.0, -2.0, 3.0, -4.0]
val two: <2>f32 = [-1.0, 5.0]

print(clamp_low(four, 0.0)[1], clamp_low(two, 0.0)[0])
```

```output
0 0
```

For comparison, the C answer to the same problem is to write the kernel once per instruction set
behind `#ifdef` — Box2D's contact solver carries four copies of itself, and more than half of that
file is those copies.

### A kernel over an array

Put the two halves together and the loop a real kernel runs is one body too — the loads, the
arithmetic and the store all take their width from `W`, and the scalar tail picks up whatever the
last run could not:

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

val src: [10]f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]

var four: [10]f32
var eight: [10]f32

val by4: <4>f32 = 3.0
val by8: <8>f32 = 3.0

scale(src[..], four[..], by4)
scale(src[..], eight[..], by8)

print(four[0], four[9], eight[0], eight[9])
```

```output
3 30 3 30
```

Ten elements is not a multiple of either width, so both instantiations take the tail — and the two
answers agree, which is what says one body serves both registers.

**`by` is a vector rather than an `f32`, and that is where `W` enters.** A written type argument at a
call is refused, so a kernel whose every parameter is a slice has no way to be told its width. This
is rarely felt, because a SIMD kernel's constants are broadcast across the lanes anyway — but it is
worth knowing before a signature is designed around it.

## What a vector does not have yet

**Shuffles and swizzles.** There is no way to rearrange lanes in one step. A gather is a loop that
writes the elements into a scratch array and one `load` to pick the whole run up — correct
everywhere, and still not the single instruction a shuffle would be. This is the reason the section
above is a stronger claim about arithmetic than about loading: a gather is what changes between
hand-written SIMD variants, and it is the half that does not generalise over a width.

**A masked load or store.** A run that would reach past the end traps rather than reading the lanes
that exist and leaving the rest alone. Write the scalar tail, which is what the kernel above does
and what C's SIMD code does.

**A place in a C signature.** A vector may not cross to a C function in either direction. Which
register it would arrive in differs by target and by which instruction-set extensions the other side
was compiled with, and guessing would produce a call that links and corrupts its arguments rather than
one that fails to link. Pass the lanes through memory — a `*f32` and a count, which is what C's own
SIMD-taking functions take:

```sysl
extern "process_lanes" process(v: <4>f32) -> unit
```

```error
how a vector reaches a C function differs by target
```

## Lanes

A lane is a scalar: an integer, a float, `bool` or `char`. An aggregate cannot be one — there is no
such thing as a vector of structs — and neither can a `volatile` type, since per-lane access ordering
is not something a single load can give. `volatile <4>u32` is the spelling that means something, and
it qualifies the whole register.

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

A **constrained** lane is fine, and computes at its base exactly as a scalar of that subtype does:

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
