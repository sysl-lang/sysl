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

A lane is written by the same constant index, `v[1] = 9`, and by [letter](#swizzles): `v.y = 9`. For
a computed index, [convert the vector to an array](#a-vector-and-an-array), whose subscript is
checked at run time.

### Every lane, one at a time

**A loop over the lanes is a [`for const`](/reference/generics/#a-parameter-may-stand-for-a-list-of-types).**
It is unrolled while compiling into one copy of its body per value, and in each copy the variable is
a constant, so `v[j]` is a lane index like `v[2]`. The range may be bounded by literals, `const`s or a
value parameter, so one kernel walks every width it is instantiated at:

```sysl
dot[const N: usize](a: <N>f64, b: <N>f64) -> f64
    var s = 0.0

    for const j in 0..<N
        s += a[j] * b[j]

    s

val a: <4>f64 = [1, 2, 3, 4]
var scaled: <4>f64 = 0

for const j in 0..<4
    scaled[j] = a[j] * real(j)

print(dot(a, a), scaled[3])
```

```output
30 12
```

Each lane read in the copies is one `extractelement` at a constant lane, exactly what `a[0] * b[0] +
a[1] * b[1] + …` written out would give, and the optimizer is free to keep a lane in its register:
AArch64's `fmla v0.2d, v1.2d, v2.d[1]` multiplies by one lane of another register in place. Copies
nest, so `acc[i][j]` over an array of vectors is two `for const` loops.

The rules are the `for const`'s own: the range is known at compile time, it is unrolled to at most
65 copies, a `return` in a copy leaves the function, and `break` and `continue` are refused, since
no loop is left at run time for them to act on. **An ordinary `for` is a loop at run time**, its
variable different on each turn, so it cannot pick a lane:

```sysl
const NR = 4

total(v: <NR>f64) -> f64
    var s = 0.0

    for j in 0..<NR
        s += v[j]

    s

print(total(1))
```

```error
a lane index is part of the instruction that reads it, so it has to be a constant — a literal, a 'const' naming one, or the variable of a 'for const', which is unrolled
```

### A vector and an array

**A vector converts to an array of the same element and length, and an array to such a vector.**
`<N>T` and `[N]T` hold the same values in the same order, so nothing is rounded or reinterpreted,
and the conversion is made by the position as every [lossless
conversion](/reference/expressions/#which-conversions-are-implicit) is: a declared `val` or `var`, an
assignment, an argument, a returned value, a field, an element.

```sysl
largest[const N: usize](v: <N>f64) -> f64
    val xs: [N]f64 = v
    var best = xs[0]

    for x in xs
        if x > best then best = x

    best

val v: <4>f64 = [3, 9, 2, 6]
val doubled: [4]f64 = v * 2
var i = 1

val back: <4>f64 = doubled

print(largest(v), doubled[i], (back + 1)[3])
```

```output
9 18 13
```

The array is storage, so it is subscripted by a computed index, walked by an ordinary `for`, and
checked at run time; the vector is a register, computed with lane-wise. The conversion moves the
lanes one by one (an `extractelement` and an `insertvalue` each), which the optimizer merges into
the machine's own moves; it is never a store of the whole register, because a `<4>bool` is packed
into bits in memory and a `[4]bool` is not. For moving a run of a *larger* array, use
[`load` and `store`](#loading-and-storing).

The two must agree in element and in length. **Nothing converts through anything else**, so a
`<4>f32` is not a `[4]f64`; and a different length is refused naming both:

```sysl
val v: <4>f32 = [1, 2, 3, 4]
val a: [3]f32 = v
```

```error
a vector converts to an array, and an array to a vector, only at one length: <4>f32 has 4 lanes and [3]f32 has 3 elements
```

An operator does not mix the two, in either order: two operands meeting is not a position that names
the type it wants, so `v + a` with `a` an array is refused as two different vector types are.
Convert one side first, with a declared `val`.

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

## Rearranging lanes

**`shuffle` picks lanes by number.** Given a list, it builds a vector whose lane `i` is the lane the
list names at `i`. Given a second vector as well, it picks from both, numbering the second vector's
lanes after the first's: lanes `0` to `N - 1` are the receiver's and `N` to `2N - 1` the second's.

```sysl
val a: <4>f32 = [1, 2, 3, 4]
val b: <4>f32 = [10, 20, 30, 40]

val zipped = a.shuffle(b, [0, 4, 1, 5])
val turned = a.shuffle([3, 2, 1, 0])
val rolled = a.shuffle(b, [1, 2, 3, 4])

print(zipped[0], zipped[1], zipped[2], zipped[3])
print(turned[0], turned[3], rolled[0], rolled[3])
```

```output
1 10 2 20
4 1 2 10
```

Each `shuffle` is **one `shufflevector` instruction**, with the list as its constant operand, and
the back end turns it into the machine's own permutes at every optimisation level: on AArch64 the
three above are `zip1`, `rev64` with an `ext`, and `ext`; on x86 they are `unpcklps`, `shufps`, and
`blendps` with a `shufps`. A literal of lane reads, such as `[a[0], b[0], a[1], b[1]]`, usually
reaches the same instructions once LLVM's optimiser has folded it, but `shuffle` reaches them
without the optimiser.

**The result is as wide as the list is long**, so a shuffle also widens, narrows and repeats. A list
may name a lane more than once:

```sysl
val a: <4>f32 = [1, 2, 3, 4]
val b: <4>f32 = [10, 20, 30, 40]

val wide = a.shuffle(b, [0, 1, 2, 3, 4, 5, 6, 7])
val upper = wide.shuffle([4, 5, 6, 7])
val third = a.shuffle([2, 2, 2, 2])

print(wide.len, upper.len, upper[0], third[3])
```

```output
8 4 10 3
```

Joining two `<4>` vectors into an `<8>`, or taking either half of one, costs nothing at run time: the
eight lanes already sit in the pair of registers the two halves occupy.

### The list is a constant

A lane number is part of the instruction, just as a [lane index](#writing-a-vector) is, so the list must be
known while compiling. It may be an array literal of constants — literals, `const`s, value
parameters, and arithmetic on them — or the result of a `const` function. Anything else is refused:

```sysl
f(a: <4>f32, k: usize) -> <4>f32 = a.shuffle([k, 0, 1, 2])
```

```error
a shuffle's lanes are part of the instruction, so the list has to be a constant
```

Picking lanes by numbers computed at run time is a different instruction (`tbl` on AArch64,
`pshufb` on x86), with its own rules for an index out of range. Where the indices are only known at
run time, keep the values in an array and index it.

A lane outside the vectors is refused while compiling, with both ranges named:

```sysl
f(a: <4>f32, b: <4>f32) -> <4>f32 = a.shuffle(b, [0, 8, 1, 5])
```

```error
'shuffle' picks from lanes 0 to 7 — 0 to 3 of the receiver and 4 to 7 of the second vector — and this is 8
```

The two vectors must be **one type**, at the same width with the same lanes. A scalar is not
broadcast here: a `shuffle` with a scalar where the second vector belongs is almost always a
mistake, and a broadcast is spelled `[0; 4]` or as a typed binding.

```sysl
f(a: <4>f32, b: <8>f32) -> <4>f32 = a.shuffle(b, [0, 1, 2, 3])
```

```error
'shuffle' takes two vectors of one type, got <4>f32 and <8>f32
```

### Lists for every width

A `const` function computes a list once per width, so one kernel shuffles at every width.
[`sysl.vectors`](/api/sysl-vectors/) provides the common lists, each taking the width `W` of the
vectors being shuffled:

| list | lanes at `W = 4` | AArch64 |
|---|---|---|
| `zip_lo[W]()` | `[0, 4, 1, 5]` | `zip1` |
| `zip_hi[W]()` | `[2, 6, 3, 7]` | `zip2` |
| `unzip_even[W]()` | `[0, 2, 4, 6]` | `uzp1` |
| `unzip_odd[W]()` | `[1, 3, 5, 7]` | `uzp2` |
| `reverse[W]()` | `[3, 2, 1, 0]` | `rev64` + `ext` |
| `rotate[W, K]()` | `[1, 2, 3, 0]` at `K = 1` | `ext` |

```sysl
import sysl.vectors.{zip_lo, zip_hi}

interleave[const W: usize](a: <W>int, b: <W>int) -> (<W>int, <W>int) =
    (a.shuffle(b, zip_lo[W]()), a.shuffle(b, zip_hi[W]()))

val a4: <4>int = [0, 1, 2, 3]
val b4: <4>int = [10, 11, 12, 13]
val a8: <8>int = [0, 1, 2, 3, 4, 5, 6, 7]
val b8: <8>int = [10, 11, 12, 13, 14, 15, 16, 17]

val (lo, hi) = interleave(a4, b4)
val (lo8, hi8) = interleave(a8, b8)

print(lo[1], hi[0], lo8[7], hi8[1])
```

```output
10 2 13 14
```

At `W = 4` the kernel is `zip1` and `zip2`, and at `W = 8` it is two of each. A list of your own is
written the same way — a `const` function with a value parameter, returning `[W]usize`. A width that
makes a lane fall outside the vectors is refused for that instantiation only, in the words above.

### Shuffling a mask

A mask is a vector, and shuffles like one:

```sysl
val a: <4>f32 = [1, 5, 3, 7]
val b: <4>f32 = [4, 2, 6, 0]

val m = (a < b).shuffle([3, 2, 1, 0])

print(m[0], m[3])
```

```output
false true
```

It is correct, but not free. On AArch64 a comparison's lanes are narrowed to be shuffled and widened
again afterwards, so `(a < b).shuffle(lanes)` costs several instructions more than
`a.shuffle(lanes) < b.shuffle(lanes)`. The two are the same mask; prefer shuffling the operands.

### Swizzles

**A field read on a vector whose name is one to four of the letters `x y z w` is a swizzle.** The
letters are lanes 0 to 3. One letter is the lane itself, a scalar, as `v[2]` is; two to four are a
vector as wide as the name is long, and a letter may repeat. A swizzle is `shuffle` under another
spelling, so it is the same single instruction:

```sysl
val p: <4>f32 = [1, 2, 3, 4]

val back = p.wzyx
val flat = p.xy
val wide = flat.xyxy
val third = p.z

print(back[0], back[3])
print(flat[0], flat[1])
print(wide[2], wide[3])
print(third)
```

```output
4 1
1 2
1 2
3
```

The letters reach lanes 0 to 3 of a vector of any width, so a swizzle is at most four lanes; anything
wider is a `shuffle`. A name with a letter outside `xyzw` is an ordinary member, which is why `len`
is still the lane count. Each letter has to be a lane the vector has:

```sysl
val v: <2>f32 = [1, 2]

print(v.z)
```

```error
<2>f32 has lanes x and y, and 'z' is lane 2
```

### Writing lanes

**A swizzle or a constant index is also a target.** `v.y = x` and `v[1] = x` replace one lane, which is
one `insertelement`; `v.xy = w` replaces the named lanes, which is one `shufflevector` that merges `w`
into `v`. The vector itself has to be something that can be assigned, and a scalar on the right
fills every named lane:

```sysl
val p: <4>f32 = [1, 2, 3, 4]
var q: <4>f32 = [0, 0, 0, 0]

q.xy = p.zw
q.w = 9
q[1] = 7
print(q[0], q[1], q[2], q[3])

q.zx = [5, 6]
q.yw += 1
print(q[0], q[1], q[2], q[3])

q.yz = 0
print(q[0], q[1], q[2], q[3])
```

```output
3 7 0 9
6 8 5 10
6 0 0 10
```

A compound assignment is the longhand, `q.yw += 1` being `q.yw = q.yw + 1`. A lane has no address of
its own, so a write is the vector read, changed and stored back, and four things follow:

- the letters of a swizzle written to must be distinct, since one value cannot go to two lanes;
- the vector must be a place that can be named twice, so a vector reached through a call is refused;
- a write is a statement, and `:=` is refused;
- `&v.y` is refused, as `&v[1]` is.

```sysl
var q: <4>f32 = [0, 0, 0, 0]

q.xx = [1, 2]
```

```error
a swizzle written to cannot repeat a letter — 'x' names lane 0 twice, and one value cannot go to both
```

```sysl
val p: <4>f32 = [1, 2, 3, 4]

p.x = 1
```

```error
a 'val' is written once, so assignment has nothing to write through
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

**A float `min()` or `max()` steps over a NaN**, as the scalar
[`min_num` and `max_num`](/library/math/#rounding-sign-and-the-rest) do: a NaN lane loses to any
number, and only a vector of nothing but NaNs reduces to one. Where a NaN must not be lost,
[`maximum()` and `minimum()`](#nan-propagating-maximum-and-minimum) are the reductions that keep it.

## Magnitude, and a fused multiply-add

Two methods apply a single machine operation to every lane at once and answer a vector. Each is one
instruction over the whole register where the target has one, and never a loop over the lanes.

**`v.abs()`** is the magnitude of each lane. A float lane has its sign bit cleared, so `-0.0` answers
`0.0`, a NaN stays a NaN and an infinity stays an infinity, at every width including `f16` and
`bf16`. A signed integer lane answers as the scalar `abs` does: **the most negative value answers
itself**, in every build, because its magnitude does not fit the width. An unsigned, `bool` or
fixed-point vector has no `abs` and is refused.

**`a.mul_add(b, c)`** is `a * b + c` in every lane with the product never rounded on its own, as the
scalar [`mul_add`](/library/math/#mul-add-a-multiply-and-an-add-rounded-once) is. Either `b` or `c`
may be a scalar, which broadcasts. It is defined for `f32` and `f64` lanes only: a half-width lane
cannot keep the single rounding, so `f16` and `bf16` are refused, as the scalar methods are absent.
Inside a `@strict_fp` body it is the constrained multiply-add, as the scalar one is, rounded in the
mode in force. Neither method is evaluated while compiling.

```sysl
val e = 1.0 / 1073741824.0
val a: <2>f64 = [1.0 + e, 2.0]
val b: <2>f64 = [1.0 - e, 3.0]
val c: <2>f64 = [-1.0, 1.0]

val product = a * b
val twice = product + c
val once = a.mul_add(b, c)

val i: <4>i32 = [-5, 5, -2147483648, 0]
val m = i.abs()
val f: <4>f32 = [-0.0, -1.5, 2.0, -3.0]
val g = f.abs()

print(twice[0] == 0.0, once[0] < 0.0, once[1])
print(m[0], m[1], m[2], m[3])
print(1.0 / g[0], g[1], g[3])
```

```output
true true 7
5 5 -2147483648 0
inf 1.5 3
```

```sysl
val a: <4>f16 = [1.0, 2.0, 3.0, 4.0]

f() -> unit
    val r = a.mul_add(a, a)
```

```error
'mul_add' rounds the product and the sum once, which only a float of 32 or 64 bits can promise
```

## NaN-propagating maximum and minimum

**`a.maximum(b)` and `a.minimum(b)`** are IEEE 754-2019's maximum and minimum in every lane: **a NaN
in either lane wins**, and `-0.0` orders below `0.0`, so the maximum of two zeros is the positive one
and the minimum the negative one, whichever way round they are. `b` may be a scalar, which
broadcasts. **`a.maximum()` and `a.minimum()`**, with no argument, reduce the lanes the same way: one
NaN lane makes the answer a NaN, where [`max()` and `min()`](#reductions) would step over it. The
names and the meaning are the scalar [`Float` methods'](/library/math/#rounding-sign-and-the-rest).

Each is one instruction over the register where the target has one. On AArch64 that is `fmax` and
`fmin`, which *are* these operations, and the reduction of two `f64` lanes is one `fmaxp`.

```sysl
val a: <4>f64 = [1.0, nan, -0.0, 5.0]
val b: <4>f64 = [2.0, 3.0, 0.0, -1.0]

val hi = a.maximum(b)
val lo = a.minimum(b)

print(hi[0], hi[1] != hi[1], 1.0 / hi[2], hi[3])
print(lo[0], lo[1] != lo[1], 1.0 / lo[2], lo[3])
print(a.max(), a.maximum() != a.maximum(), b.maximum())
```

```output
2 true inf 5
1 true -inf -1
5 true 3
```

That is what a search that must not step over a NaN is built from. BLAS's `iamax` answers the index of
the largest magnitude, and of the **first NaN** where there is one; a running lane-wise `maximum`
carries a NaN to the end, so clean data costs nothing beyond the vector pass:

```sysl
iamax(x: []f64) -> usize
    var best: <4>f64 = 0.0
    var i = 0

    while i + 4 <= x.len
        val v: <4>f64 = x.load(i)

        best = best.maximum(v.abs())
        i += 4

    var top = best.maximum()

    for k in i..<x.len
        top = top.maximum(x[k].abs())

    for k in 0..<x.len
        if top != top
            if x[k] != x[k] then return k
        elif x[k].abs() == top then return k

    0
end iamax

var xs: [6]f64 = [1.0, -9.0, 3.0, 2.0, 9.0, 0.5]

print(iamax(xs[..]))
xs[3] = nan
print(iamax(xs[..]))
```

```output
1
3
```

They are defined for `f32` and `f64` lanes. **An integer vector has no `maximum`**: it has no NaN to
propagate and no signed zero, so the lane-wise maximum of integers is a comparison and a
[`select`](#masks-and-the-lane-wise-if). A half-width lane is refused too; widen it first. Inside a
`@strict_fp` body the lane-wise form is the constrained maximum or minimum, which raises invalid only
for a signalling NaN.

```sysl
val a: <4>int = [1, 2, 3, 4]

f() -> unit
    val m = a.maximum(a)
```

```error
'maximum' is IEEE 754's NaN-propagating maximum, defined for f32 and f64 lanes, and <4>int is not one
```

An `extern` naming `llvm.maximum` at a vector type is refused, as any vector at that boundary is
([below](#what-a-vector-does-not-have)); the methods are the way to it.

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

**So does the receiver of a vector method.** The operands of `mul_add`, `select` and `shuffle` are
the receiver's vector type (or, for `select`, the other side's), so a load in one takes its width as
an operator's operand would:

```sysl
var xs: [4]f32 = [1, 2, 3, 4]
var ys: [4]f32 = [1, 1, 2, 2]
var acc: <2>f32 = 0
val a: <2>f32 = xs.load(0)

acc = a.mul_add(ys.load(2), acc)

print(acc[0], acc[1])
```

```output
2 4
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

**Inside an [`@unchecked`](/reference/arrays/#unchecked-access) block that one test is left out**, as
a scalar subscript's is: the load or store is the address and nothing else.

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

**`by` is a vector, and that is where `W` comes from.** A kernel whose parameters are all slices
has nothing to infer the width from, so the call writes it, and a scalar `by` broadcasts across the
lanes where it meets a vector:

```sysl
scale_by[const W: usize](xs: []const f32, out: []f32, by: f32)
    var i: usize = 0

    while i + W <= xs.len
        val v: <W>f32 = xs.load(i)

        out.store(i, v * by)
        i += W

    while i < xs.len
        out[i] = xs[i] * by
        i += 1

val src: [10]f32 = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
var out: [10]f32

scale_by[4](src[..], out[..], 3)

print(out[0], out[4], out[9])
```

```output
3 15 30
```

## What a vector does not have

**A lane with an address.** A lane is written, but only by writing its vector back, so `&v.y` is
refused as `&v[1]` is: a lane has no address of its own. See [Writing lanes](#writing-lanes).

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
