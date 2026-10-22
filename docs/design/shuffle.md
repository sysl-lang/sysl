# Vector shuffles and swizzles — design

sysl 0.4.0 adds a way to **rearrange a vector's lanes in one step**: interleave two vectors, reverse
one, rotate across a pair, broadcast a lane, take a half, permute bytes. Today
[`reference/vectors.md` § "What a vector does not have"](/reference/vectors/#what-a-vector-does-not-have)
says there is no such operation and that a gather is a loop.

Nothing here is built. This note records what exists, what other languages do (checked by running them
where a toolchain is installed), the options for sysl in real sysl, and the questions the user decides.
Every claim cites a probe under [`shuffle-probes/`](shuffle-probes/); `zsh
docs/design/shuffle-probes/run.sh <out dir>` re-runs all of them.

## 1. Decisions

| # | question | decision | why, in one line |
|---|---|---|---|
| 1 | the general form | a **vector method**, `a.shuffle([3, 2, 1, 0])` / `a.shuffle(b, [0, 4, 1, 5])` | it sits beside `select`, `sum` and `load`, which are all methods the compiler supplies |
| 2 | numbering of the second input | lanes of `b` are **`N` to `2N − 1`** | LLVM, clang, GCC and Rust all number them this way; Zig's `~i` is the outlier |
| 3 | an index list that is not constant | **refused**, in the words the lane index already uses | a run-time permutation is a different instruction (`tbl`, `pshufb`) with different semantics; a silent gather loop would hide the cost |
| 4 | where a constant list can come from | a literal, or a **`const` function's result** (works today, generic in `W`) | it is what lets one kernel interleave at every width |
| 5 | result width | **the list's length**, so `<8>` from two `<4>` and `<4>` from one `<8>` | it is `shufflevector`'s own rule, and both cases are free or one instruction |
| 6 | swizzles | **yes, as sugar for `shuffle`**: `.x .y .z .w` name lanes 0–3, 1–4 letters, one letter is a scalar | graphics-shaped code reads as it does in every shading language; nothing in sysl means `v.xy` today |
| 7 | swizzle as a target | **yes**, `v.xy = w.zw`, no repeated letter — **and `v[i] = x` with it** | a write of lanes is the same read-modify-shuffle-write, and refusing `v[1] = x` while allowing `v.y = x` would be arbitrary |
| 8 | `<N>bool` masks | shuffle like any other vector | `(a < b).wzyx` is legitimate; cost noted in §5.4 |
| 9 | inputs of different widths | **refused** — `a` and `b` are one type | `shufflevector` requires it; widen first with a shuffle |
| 10 | don't-care lanes | **not in 0.4.0** | LLVM rarely gains from one, and it adds a spelling (`_`) to a list of integers |

The user accepted every row (2026-10-22, §8); the recommendation is §7.

## 2. What exists today (sysl 0.3.13)

**The type.** `<N>T` holds N lanes of a scalar `T` (integer, fixed-point, float other than `f128`,
`bool`, `char`), lowered to LLVM's `<N x T>`; `<N>bool` is `<N x i1>`, the mask a comparison yields.

**Lanes are read by a constant index**, checked at compile time (`expr_coll.sysl`, `lane_expr`):

```
<4>f32 has lanes 0 to 3, and this is 4
a lane index is part of the instruction that reads it, so it has to be a constant — a literal, or a
'const' naming one. To index <4>f32 by a computed number, keep the values in a '[4]f32'
```

A value parameter is a constant there: `pick[const K: usize](v: <4>f32) -> f32 = v[K]` and
`last_lane[const W: usize](v: <W>f32) -> f32 = v[W - 1]` both work
([`today.sysl`](shuffle-probes/sysl/today.sysl), [`const_masks.sysl`](shuffle-probes/sysl/const_masks.sysl)).

**A lane cannot be written.** `v[1] = 9` is refused: *"assignment needs a variable, a field, an
element, or a dereference — something with an address"* ([`lane_store.sysl`](shuffle-probes/refusals/lane_store.sysl)).

**What lowers to what** (`emit_vector.sysl`):

| sysl | LLVM |
|---|---|
| `[a, b, c, d]` at `<4>T` | an `insertelement` chain |
| a scalar where a vector is wanted (splat) | `insertelement` into lane 0 + **`shufflevector` with a zero mask** |
| `v[k]` | `extractelement` |
| `a < b` | `fcmp`/`icmp` at the vector width → `<N x i1>` |
| `m.select(a, b)` | `select <N x i1>` |
| `v.sum()` etc. | `llvm.vector.reduce.*` |
| `xs.load(i)` / `xs.store(i, v)` | a bounds check of the whole run + one vector load/store |

**The IR instruction a shuffle needs already exists.** `ir_inst.sysl` has `InShuffle(dest, ty, a, b,
mask: IrArg)`, rendered as `shufflevector`, and `ir_val.sysl` has `VVector(lanes)` for a constant mask.
The splat is its only user. `soft_bf16.sysl` and `contract_assume.sysl` already handle it.

**Member names on a vector.** A field read other than `len` is refused (*"cannot read field 'x' of
<4>f32"*); a trait **may** give a vector methods (`impl Lanes for <4>f32` with `xy(self)` is accepted
and `v.xy()` calls it); an inherent `impl <4>f32` does not parse
([`names.sysl`](shuffle-probes/refusals/names.sysl)). The built-in methods are a closed table in
`member_call.sysl` (`Vector(_, _) -> if is_vector_method(name) …`), consulted before declared ones.

**`const` is scalar-only.** `const ZIP: [4]int = …` is refused (*"a constant is a scalar, and [4]int is
storage — write 'ZIP' as a 'val'"*), and a module `val` array is not a constant: `v[ZIP[3]]` is refused
as a lane index. **A `const` function, though, can build and return an array, generic in the width,
and an element of its result folds wherever a constant is required** — `v[zip_lo_mask[W]()[2]]` works
([`const_masks.sysl`](shuffle-probes/sysl/const_masks.sysl)). `Const.ConstArray` exists for exactly
this.

### 2.1 What today's spellings already compile to — the honest baseline

LLVM folds a chain of `extractelement`/`insertelement` into one `shufflevector`, so **at a fixed width a
literal of lane reads already becomes the single instruction** at sysl's default `-O1`
([`today.sysl`](shuffle-probes/sysl/today.sysl), via `sysl build`, and `run.sh` at `-O1`):

| sysl today | arm64 | x86-64 SSE4.1 |
|---|---|---|
| `[a[0], b[0], a[1], b[1]]` | `zip1.4s` | `unpcklps` |
| `[a[3], a[2], a[1], a[0]]` | `rev64.4s` + `ext.16b #8` | `shufps $0x1b` |
| `[a[1], a[2], a[3], b[0]]` | `ext.16b #4` | `blendps` + `shufps` |
| `<8>i32` from `[a[0] … b[3]]` | nothing (`ret`) | nothing |
| the scratch-array gather the reference page describes | `zip1.4s` | `unpcklps` |
| 16 lane reads of a `<16>u8`, irregular | `tbl.16b` (mask from the constant pool) | `pshufb` |

So the case for a shuffle is **not** "today is a loop" at a fixed width. It is the four things the
literal cannot do ([`today_limits.sysl`](shuffle-probes/sysl/today_limits.sysl)):

1. **It is not a guarantee.** At `-O0` the literal is four `mov.s v0[i], vK[j]` lane moves; a
   `shufflevector` is `zip1` at `-O0` because *instruction selection* picks it, not a pass
   ([`proposed.ll`](shuffle-probes/ll/proposed.ll) at `-O0`). A larger body can defeat the fold.
2. **It does not generalise over a width.** A lane index must be constant and a loop counter is not, so
   a `zip_lo[const W]` has to go through memory. At `-O1` that stays a store/load **loop** for `W = 4`
   and `W = 8`; only at `-O2` does LLVM unroll it back into `zip1` (`W = 4`) and `zip1` + `zip2`
   (`W = 8`). Gathers are where hand-written SIMD differs per width, and this is the gap.
3. **A sixteen-term literal is not pleasant**, and a byte permutation is sixteen terms.
4. **There is no run-time permutation at all** — a table lookup by a vector of indices goes through
   memory (a scalar loop at `-O1`), where arm64 has one `tbl`. (Out of scope for 0.4.0: §8 Q3.)

## 3. Prior art

| language | spelling | constant? | inputs | result width | out of range | lowers to |
|---|---|---|---|---|---|---|
| **clang** | `__builtin_shufflevector(a, b, 0, 4, 1, 5)`; `-1` = don't care | **must be** (*"index for __builtin_shufflevector must be a constant integer"*) | 2 of one type (*"first two arguments … must have the same type"*); pass `a, a` for one | the number of indices | error (*"must be less than the total number of vector elements"*) | one `shufflevector` |
| **clang `ext_vector_type`** | `v.xyzw`, `v.s0123` (hex, to 16 lanes), `.hi .lo .even .odd`; assignable | yes (it is syntax) | 1 | the letter count | error (*"vector component access exceeds type"*) | `shufflevector` / `extractelement` |
| **GCC** | `__builtin_shuffle(a, mask)` / `(a, b, mask)`, the mask a **vector** of integers | **no** — indices are taken modulo `N` (or `2N`) | 1 or 2 | the input width | never an error: `9` of 8 lanes is lane 1 | constant: a permute (`zip1`, but `tbl` for a reverse); run time: a spill and lane loads, not `tbl` |
| **Zig** | `@shuffle(T, a, b, mask)`; `i ≥ 0` from `a`, `~i` (negative) from `b`; `undefined` = don't care | **comptime** | 2; **different lengths allowed** | the mask length | compile error | `shufflevector` (shorter input padded) |
| **Rust** `std::simd` | `simd_swizzle!(a, [3, 2, 1, 0])` / `simd_swizzle!(a, b, [0, 4, 1, 5])`; `Swizzle` trait's `const INDEX`; `interleave`, `reverse`, `rotate_elements_left::<K>`, `resize`; run-time `swizzle_dyn` (bytes) | **const** | 1 or 2, `b` numbered `N..2N` | the index array's length | compile error after monomorphisation (*"source element index exceeds input vector length"*) | `shufflevector`; `swizzle_dyn` → `tbl` |
| **GLSL / HLSL / Metal** | `v.xyzw`, `v.rgba` (GLSL also `.stpq`); sets not mixed; HLSL `._m00` on matrices | syntax | 1 | 1–4 letters; 1 is a scalar | error | a register swizzle (free on GPUs) |
| **Odin** | `swizzle(v, 3, 2, 1, 0)` builtin; `.xyzw`/`.rgba` on fixed arrays of ≤ 4; `simd.shuffle(a, b, …)` | constant | 1 (`swizzle`), 2 (`simd.shuffle`) | the index count | error | `shufflevector` |

**Run** here: clang 21 ([`clang_shuffle.c`](shuffle-probes/c/clang_shuffle.c),
[`clang_refusals.c`](shuffle-probes/refusals/clang_refusals.c)), gcc-16 ([`gcc_shuffle.c`](shuffle-probes/gcc/gcc_shuffle.c),
arm64 only), rustc 1.94 with `RUSTC_BOOTSTRAP=1` ([`swizzle.rs`](shuffle-probes/rust/swizzle.rs),
[`rust_out_of_range.rs`](shuffle-probes/refusals/rust_out_of_range.rs)). **Not run** (no toolchain
installed; Metal's needs a separate download): Zig, Odin, GLSL, HLSL, Metal — those rows are from
their documentation.

Three things the table says:

- **Every systems language that lowers to LLVM requires a constant index list.** GCC alone takes a
  run-time mask, and pays for it with modulo semantics (no out-of-range error is possible) and, on
  arm64, a spill-and-reload rather than a `tbl`.
- **The two-input numbering `N..2N − 1` is the consensus**; Zig's `~i` exists only because Zig lets the
  inputs differ in length.
- **Swizzle letters are a shading-language convention capped at four lanes.** Above four, clang
  switches to hex digits and Rust and Zig have no swizzle syntax at all.

## 4. The bar: one instruction ([`clang_shuffle.c`](shuffle-probes/c/clang_shuffle.c), `-O2`)

| pattern | indices | arm64 | x86-64 SSE4.1 | x86-64 AVX2 |
|---|---|---|---|---|
| interleave low | `0, 4, 1, 5` | `zip1.4s` | `unpcklps` | `vunpcklps` |
| interleave high | `2, 6, 3, 7` | `zip2.4s` | `unpckhps` | |
| even lanes of a pair | `0, 2, 4, 6` | `uzp1.4s` | `shufps $0x88` | |
| rotate across a pair | `1, 2, 3, 4` | `ext.16b #4` | `blendps` + `shufps` | |
| reverse | `3, 2, 1, 0` | `rev64.4s` + `ext #8` | `shufps $0x1b` | |
| broadcast lane 2 | `2, 2, 2, 2` | `dup.4s v0[2]` | `shufps $0xaa` | |
| `<8>` from two `<4>` | `0 … 7` | none (C passes it in memory) | none | `vinsertf128` |
| upper `<4>` of an `<8>` | `4, 5, 6, 7` | none | none | `vextractf128` |
| irregular 16 bytes | — | `tbl.16b` | `pshufb` | |
| `v.xy = w.zw` | `6, 7, 2, 3` of `(v, w)` | `zip2.2d` | `movhlps` | |
| `(a < b) ? a.wzyx : b` | | `fcmgt` + `rev64` + `ext` + `bif` | `cmpltps` + `shufps` + `blendvps` | |

sysl's own calling convention keeps an `<8>` in two registers where C's spills it, so in sysl the
`<8>`-from-two-`<4>` case is **no instruction at all** on arm64 too (`concat_literal` is a bare `ret`).
On a core with no vector unit (`thumbv6m`) the same IR becomes register moves.

## 5. Options for sysl

### 5.1 (a) `shuffle` — a vector method with a constant lane list

```sysl
val a: <4>f32 = [1, 2, 3, 4]
val b: <4>f32 = [10, 20, 30, 40]

val zipped = a.shuffle(b, [0, 4, 1, 5])      // [1, 10, 2, 20]   -- zip1 / unpcklps
val turned = a.shuffle([3, 2, 1, 0])         // [4, 3, 2, 1]     -- rev64 + ext
val rolled = a.shuffle(b, [1, 2, 3, 4])      // [2, 3, 4, 10]    -- ext #4
val wide = a.shuffle(b, [0, 1, 2, 3, 4, 5, 6, 7])   // <8>f32  -- free
val upper = wide.shuffle([4, 5, 6, 7])       // <4>f32           -- free

print(zipped[1], turned[0], rolled[3], wide.len, upper[0])
```

**Why a method and not a function.** sysl cannot write it as a library function: a value parameter is
an integer, `bool`, `char` or simple-enum value, never an array, and there are no `const` value
parameters on an ordinary function. So it is compiler-supplied whichever way it is spelled, and every
other compiler-supplied vector operation is a method (`select`, `sum`, `any`; `load`/`store` on the
slice). A written value-argument list, `a.shuffle[0, 4, 1, 5](b)`, reads like Zig but would need a
variable-length list of *values* in a type-argument position, and a computed list (`zip_lo_mask[W]()`)
could not be written there; rejected.

**The list is called the *lanes*, never the *mask*.** In sysl a mask is the `<N>bool` a comparison
yields, and `select` takes one; LLVM's word would make "a shuffle mask" and "a select mask" two
different things with one name.

**What may be the list.** Any expression the compiler can fold to an array of integers: an array
literal whose elements are constants (literals, `const`s, value parameters, arithmetic on them), or a
`const` function's result. The second is what makes it generic, and it works today
([`const_masks.sysl`](shuffle-probes/sysl/const_masks.sysl) prints `0 8 3 11` for `zip_lo_mask[8]()`):

```sysl
const zip_lo_mask[const W: usize]() -> [W]usize
    var m: [W]usize

    for i in 0..<W / 2
        m[2 * i] = i
        m[2 * i + 1] = W + i

    m

zip_lo[const W: usize](a: <W>f32, b: <W>f32) -> <W>f32 = a.shuffle(b, zip_lo_mask[W]())
```

One body; `zip1` at `W = 4`, `zip1` + `zip2` at `W = 8` — at every optimisation level, where today's
loop needs `-O2`.

**A list that is not constant is refused**, in the words the lane index already uses:

```sysl
f(a: <4>f32, k: usize) -> <4>f32 = a.shuffle([k, 0, 1, 2])
```

```error
a shuffle's lanes are part of the instruction, so the list has to be a constant — a literal, or a
'const' function's result. To pick lanes by a computed number, keep the values in a '[4]f32'
```

The alternative — lowering a run-time list to a gather loop — is rejected: it is the silent cost the
lane index already refuses, and the real run-time operation (`tbl`, `pshufb`, `vpermilps`) has
different semantics (what an out-of-range index yields) and only covers bytes on some machines. It is
a separate, later method if wanted (Rust calls it `swizzle_dyn`; §8 Q3).

**An out-of-range lane is a compile-time error**, naming which input each range belongs to:

```sysl
f(a: <4>f32, b: <4>f32) -> <4>f32 = a.shuffle(b, [0, 8, 1, 5])
```

```error
'shuffle' picks from lanes 0 to 7 — 0 to 3 of the receiver and 4 to 7 of the second vector — and
this is 8
```

with the one-input form saying *"<4>f32 has lanes 0 to 3, and this is 4"*, exactly as `v[4]` does. A
negative element is the same error. Inside a generic kernel the check runs per instantiation, like
`v[W - 1]`.

**A result of a different width.** The result is `<M>T` where `M` is the list's length and `T` the
receiver's lane: `<8>` from two `<4>`, `<2>` or `<4>` from one `<8>`, `<16>` from one `<4>` by
repetition. An empty list is refused (a vector has at least one lane). The two inputs must be **one
type**:

```error
'shuffle' takes two vectors of one type, got <4>f32 and <8>f32
```

and a scalar second argument is refused rather than splatted — `a.shuffle(0, [0, 4, 1, 5])` is almost
certainly a missing vector, and a splat is written `[0; 4]` or as a typed binding.

**`<N>bool` masks shuffle like any other vector** (`(a < b).shuffle([3, 2, 1, 0])`), lowering to a
`shufflevector` on `<N x i1>`.

**Lowering.** One `InShuffle` with a `VVector` of `i32` constants for the list; the one-input form
passes `poison` as the second operand, as the splat already does.

### 5.2 (b) swizzle members — `v.xyzw`

```sysl
val p: <4>f32 = [1, 2, 3, 4]

val back = p.wzyx        // <4>f32 [4, 3, 2, 1]    == p.shuffle([3, 2, 1, 0])
val flat = p.xy          // <2>f32 [1, 2]          == p.shuffle([0, 1])
val wide = flat.xyxy     // <4>f32 [1, 2, 1, 2]    == flat.shuffle([0, 1, 0, 1])
val zed = p.z            // f32 3                  == p[2]

var q: <4>f32 = [0, 0, 0, 0]

q.xy = p.zw              // q is [3, 4, 0, 0]      == q = p.shuffle(q, [2, 3, 6, 7])
q.w = 9                  // q is [3, 4, 0, 9]
```

**The rule.** On a vector, a *field read* whose name is one to four of the letters `x y z w` is a
swizzle: `x` is lane 0, `y` 1, `z` 2, `w` 3. One letter is the lane itself (a scalar, as in every
shading language and as `v[0]` is); two to four letters are a `<k>T`. Letters may repeat in a read.
Each letter must name a lane the vector has: `<2>f32` has only `x` and `y`.

```error
<2>f32 has lanes x and y, and 'z' is lane 2
```

**Name lookup.** Nothing changes for existing code: a field read on a vector is refused today except
`len`, and a trait can contribute only methods, so `v.xy` (a read) and `v.xy()` (a call of a trait's
`xy`) stay distinguishable — the call is the trait's, as a declared `load` is the slice's. No name
containing a letter outside `xyzw` is a swizzle, so `len` is untouched.

**Beyond four lanes.** `xyzw` reach lanes 0–3 of any width, so `<8>f32` has `.x` through `.w` and
nothing for lanes 4–7, and a result is at most four lanes. Every rearrangement wider than that is a
`shuffle`. Not proposed: clang's hex `.s0`–`.sF` (sixteen more spellings for what `shuffle` already
says), GLSL's `.rgba` (claims `.r`, `.g`, `.b`, `.a` — names programs use — on every vector) and
`.stpq`. The cap is the convention's: swizzles are for vectors that *are* points and colours.

**As an assignment target.** `v.xy = e` requires `v` to be a place (a `var`, a field, an element), the
letters to be **distinct** (clang: *"vector is not assignable (contains duplicate components)"*), and
`e` to be a `<k>T` of the letter count or a scalar, which splats (`q.xy = 0`). It lowers to a load of
`v`, one two-input `shufflevector` of `e` (widened) and `v`, and a store — `zip2.2d` on arm64 and
`movhlps` on x86 for `q.xy = p.zw` ([`proposed.ll`](shuffle-probes/ll/proposed.ll) `store_xy`).
Compound forms (`q.xy += d`) follow from the place. **A one-letter target is a lane write**, so
`v[i] = x` for a constant `i` comes with it (`insertelement`); allowing `v.y = x` and refusing
`v[1] = x` would be arbitrary.

### 5.3 (c) both

`shuffle` is the operation; a swizzle is defined as the `shuffle` (or lane read) it abbreviates, so
the two share one typed node and one lowering. This is what clang does (`.wzyx` and
`__builtin_shufflevector(a, a, 3, 2, 1, 0)` are the same IR), and what Odin does (`.xyzw` and
`swizzle`).

### 5.4 Masks, and where the cost is

A shuffled `<N>bool` is correct but not free on arm64: `(a < b).wzyx.select(a, b)` at `-O1` is
`fcmgt` + `xtn` + `rev64.4h` + `sshll` + `bif` (five), because LLVM narrows the `i1` lanes, shuffles
and widens; at `-O0` it is around fifty instructions. x86 shuffles the full-width compare result
(`cmpltps` + `shufps` + `blendvps`). Shuffling the *operands* before comparing is the cheaper
spelling and the same result. The reference page should say so; the compiler should not rewrite
it.

### 5.5 Comparison

| | (a) `shuffle` | (b) swizzle only | (c) both |
|---|---|---|---|
| two inputs (zip, rotate, unzip) | yes | no | yes |
| wider than 4 lanes / byte permutes | yes | no | yes |
| generic over `W` | yes (const function list) | no (letters are fixed) | yes |
| reads like the maths for points/colours | no | yes | yes |
| writes lanes | no (reassign the whole vector) | yes | yes |
| analysis surface | one method | a field-read rule + a new place kind | both, one lowering |

## 6. How each lowers to one `shufflevector`

| sysl | IR |
|---|---|
| `a.shuffle(b, [0, 4, 1, 5])` | `shufflevector <4 x float> %a, <4 x float> %b, <4 x i32> <i32 0, i32 4, i32 1, i32 5>` |
| `a.shuffle([3, 2, 1, 0])`, `a.wzyx` | `shufflevector <4 x float> %a, <4 x float> poison, <4 x i32> <i32 3, i32 2, i32 1, i32 0>` |
| `a.shuffle(b, [0 … 7])` | `… <8 x i32> <i32 0, …, i32 7>` → `<8 x float>` |
| `p.xy` | `… <2 x i32> <i32 0, i32 1>` → `<2 x float>` |
| `p.z` | `extractelement <4 x float> %p, i32 2` |
| `q.xy = p.zw` | load `%q`; `shufflevector <4 x float> %p, <4 x float> %q, <4 x i32> <i32 2, i32 3, i32 6, i32 7>`; store |
| `q.w = 9` / `q[3] = 9` | load; `insertelement … i32 3`; store |

Every row was compiled from hand-written IR of exactly this shape at `-O0` and `-O1` for arm64, x86-64
and thumbv6m ([`proposed.ll`](shuffle-probes/ll/proposed.ll)); the arm64 and x86 instructions are §4's.

## 7. Recommendation

**(c): `shuffle` as the operation, `.xyzw` swizzles as its sugar, with reads and writes.**

- `shuffle` covers everything a SIMD kernel needs, including the width-generic case today's sysl
  cannot express below `-O2`, and it is one instruction by construction rather than by optimisation.
- Swizzles cost one field-read rule and make vector-as-point code read the way every shading language
  does — pleasantness is the tiebreaker.
- The lane write (`v[i] = x`, `v.y = x`) closes a gap the swizzle store exposes anyway.
- A run-time permutation, don't-care lanes and hex/colour swizzle names stay out until asked for.

## 8. Decided (user, 2026-10-22)

**The user accepted every recommendation below**: Q1 a method; Q2 the second input's lanes are
`N..2N − 1`; Q3 a non-constant list is refused (a run-time permutation is a separate, later item);
Q4 a named list is a `const` function; Q5 yes to the width-generic lists in `sysl.vectors`; Q6 `xyzw`
only, one to four letters, one letter a scalar; Q7 `v.xy = …` and `v[i] = x` are both allowed; Q8
different widths are refused; Q9 no don't-care lanes. The questions as they were put:

1. **Spelling of the general form** — `a.shuffle(b, [0, 4, 1, 5])` (a method, like `select`), or a free
   `shuffle(a, b, [0, 4, 1, 5])` (like clang and Zig)? *Recommend the method.*
2. **Numbering of the second input** — `N..2N − 1` (LLVM, clang, GCC, Rust) or Zig's negative `~i`?
   *Recommend `N..2N − 1`.*
3. **A non-constant lane list** — refuse it, or lower it to a gather loop? And is a run-time
   permutation (`tbl`/`pshufb`, Rust's `swizzle_dyn`) wanted as a separate method later? *Recommend
   refuse; the run-time method is a separate item, not in 0.4.0.*
4. **Named lane lists** — keep `const` scalar-only and name a list with a `const` function (works
   today), or let `const` hold an array of integers? *Recommend the `const` function; no language
   change.*
5. **A library of width-generic lists** — ship `zip_lo`, `zip_hi`, `unzip_even`, `unzip_odd`,
   `reverse`, `rotate[K]`, `concat`, `low_half`, `high_half` as `const` functions (or as methods built
   on `shuffle`)? *Recommend yes, as `const` list functions in `sysl.vectors`, in the same release —
   generality across widths is the point.*
6. **Swizzle letters** — `xyzw` only, one to four letters, one letter a scalar? Or also `rgba` /
   `.s0`–`.sF`? *Recommend `xyzw` only.*
7. **Swizzle and lane assignment** — allow `v.xy = …` (distinct letters) and, with it, `v[i] = x`?
   *Recommend yes to both, in the same item.*
8. **Inputs of different widths** (Zig allows `<2>` with `<4>`) — *Recommend refuse; one type, as
   LLVM requires.*
9. **Don't-care lanes** (`_` in the list → `poison`) — *Recommend not in 0.4.0.*

## 9. Implementation plan

Two items, in order, each one agent.

**Item 1 — `shuffle` (option a).** About 250 lines of compiler, 300 of tests, 120 of docs.

| file | change |
|---|---|
| `tast.sysl` | `TShuffle(a: TExpr, b: Option[TExpr], lanes: Buf[int], ty: Type)` and its `texpr_type` arm |
| `vectors.sysl` | `shuffle` in `is_vector_method`; `vector_shuffle` (arity, one type, the list folded, range check, result type); `const_lanes(cx, e) -> Option[Buf[BigInt]]`: an array literal by `const_int` per element, else `const_call` → `ConstArray` |
| `emit_vector.sysl` | `gen_shuffle`: one `InShuffle` with a `VVector` of `i32`, `VPoison` for the one-input form |
| `emit_expr.sysl` | dispatch |
| the walkers that list `TLane` | `abandons`, `borrowing`, `const_eval` (fold a shuffle of constant vectors), `consumes`, `emit_async`, `place`, `return_slot`, `show_typed`, `walk_typed` — one arm each |
| `library/sysl/vectors.sysl`, new (Q5) | the `const` list functions, with `@tests` |

Tests: **`shf_`** run tests (`tests_shf_shuffle_run.sysl` — each pattern of §4, the generic `W = 2, 4,
8, 16` kernel, `<8>` from two `<4>`, `<N>bool`, fixed-point lanes, a constant shuffle folded into a
module `val`); **`shx_`** errors (non-constant, out of range one- and two-input, negative, empty,
mismatched types, scalar second argument, an out-of-range lane only in one instantiation);
**`shg_`** codegen (the `shufflevector` text and its mask; arm64 `zip1`/`ext`/`tbl` read from the
assembly at `-O0`, as `fx2_` reads disassembly). Each fix row goes in `scripts/fix_tests.tsv`.

**Item 2 — swizzles and lane writes (option b).** About 200 lines of compiler, 200 of tests, 60 of docs.

| file | change |
|---|---|
| `expr_member.sysl` | `field_expr`'s `Vector` arm: a name of 1–4 `xyzw` letters → `TLane` or `TShuffle`; the lane-range diagnostic |
| `place.sysl`, `expr_assign.sysl` | a vector-lanes place: `v[k]` and `v.xy` as targets (constant index, distinct letters, width or splat), compound assignment |
| `emit_place.sysl` / `emit_stmt.sysl` | load, `insertelement` or two-input `shufflevector`, store |

Tests: **`swz_`** run and error tests (reads, repeats, one letter, lane past the width, `.len` still
`len`, a trait method `xy()` still called, store with a repeat refused, store to a `val` refused,
compound, `v[i] = x`), and `shg_` rows for the store's IR.

**Docs, with the code** (the site is executable, so every block is a test):

- `reference/vectors.md`: a new section **"Rearranging lanes"** after "Masks, and the lane-wise `if`" —
  `shuffle`, the width-generic list, the error blocks above, swizzles, lane writes, the mask cost note;
  remove "Shuffles and swizzles" from "What a vector does not have"; change "A lane is read by a
  constant index" to cover writes.
- **Correct a stale paragraph found by this note**: § "A kernel over an array" says *"A written type
  argument at a call is refused, so a kernel whose parameters are all slices cannot be given its
  width."* On 0.3.13 `scale[4](src[..], out[..], 3)` compiles and runs
  ([`written_width.sysl`](shuffle-probes/sysl/written_width.sysl) prints `3 12 0`).
- The `library/` page for `sysl.vectors` if Q5 is yes.
