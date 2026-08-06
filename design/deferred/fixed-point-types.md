# Deferred: Fixed-Point Types

**Status:** deferred — a sketch, not a decision. Not part of the language basics. Captured so
the idea and its prerequisites aren't lost. Motivating use case: an audio/DSP project.

Fixed-point is the canonical numeric model for audio, so this draws on the well-worn designs
in that space — ARM CMSIS-DSP, Embedded-C's `_Fract`/`_Accum` (ISO TR 18037), and C++
libraries like `fpm` / CNL.

## What audio actually needs

Three requirements drive the whole design:

1. **Samples live in `[-1, 1)`** — a signed value where full-scale is ±1.0. The classic
   format is **Q15**: a signed 16-bit integer read as `raw / 2^15`. (Q7 in a byte, Q31 for
   high resolution.)
2. **Multiply changes the scale.** `Q15 × Q15` has *30* fractional bits and needs 32 bits to
   hold — a Q15 product is not a Q15. Getting this wrong is the most common fixed-point bug,
   so the type system should track the scale rather than let it be silently dropped.
3. **Overflow must saturate, not wrap.** A wrapped audio sample is a full-scale glitch; a
   saturated one is a soft click. Saturation must be easy to ask for, and is arguably the
   default for audio.

## The type constructor

A backing integer `B` (fixing width *and* signedness) plus a compile-time fractional-bit
count `F`:

```
type fixed[B, F]         // B ∈ {iN, uN}, F a compile-time int, 0 ≤ F ≤ bits(B)

// CMSIS-DSP names as aliases — familiar to audio/DSP programmers:
type q7    = fixed[i8,  7]    // [-1, 1) in a byte
type q15   = fixed[i16, 15]   // [-1, 1) — the classic 16-bit PCM sample
type q31   = fixed[i32, 31]   // [-1, 1) — high-precision sample
type q1_14 = fixed[i16, 14]   // [-2, 2) — one integer bit of headroom
```

The aliases are honest names (the alias test in the basics doc): the name *is* the exact
format.

## Literals and conversion

The compiler knows the scale, so decimal literals are scaled at compile time:

```
var gain: q15 = 0.5      // → raw 16384, exact
var att:  q15 = 0.1      // not exactly representable → rounds (compiler may warn)
let raw:  i16  = gain.bits     // the underlying integer, unscaled
let f:    real = real(gain)    // 0.5 — back to f64 floating point
```

## Scale-tracking arithmetic

Add/sub stay in-scale; multiply widens and records the new scale in the result type, so the
rescale cannot be silently skipped:

```
let mix: q15 = a + b            // same scale; wraps on overflow (glitch)
let mix: q15 = a.sat_add(b)     // clamps to ±full-scale (click) — lowers to llvm.sadd.sat

let p: fixed[i32, 30] = a * b   // q15 × q15 — no precision lost, scale = 15 + 15
let y: q15 = p.round[q15]()     // explicit narrow: round-to-nearest + saturate
```

The `fixed[i32, 30]` result type is the point: the compiler forces acknowledgement of the
rescale instead of pretending `q15 × q15 = q15`.

## The FIR-filter idiom this is designed to make clean

```
fir(x: []q15, h: []q15) -> q15 =
    var acc: fixed[i64, 30] = 0        // 64-bit accumulator, 30 frac bits = guard bits
    for i in 0 ..< x.len do
        acc += x[i] *+ h[i]            // *+ = widening multiply into the accumulator's scale

    acc.round[q15]()                   // one round + saturate at the very end
```

The `i64` accumulator gives roughly 33 bits of headroom above the Q30 product, so hundreds
of taps can be summed before a single round at the end — how real filters avoid accumulating
rounding error.

## How it rides on the backend

No new backend primitive is required — this sits on the existing integer family plus LLVM's
fixed-point intrinsics:

- **add/sub** → integer add/sub, or the saturating intrinsics `llvm.sadd.sat` / `llvm.ssub.sat`.
- **multiply** → widening integer multiply (scale tracked in the type), or `llvm.smul.fix`
  with a scale immediate when narrowing to the same format.
- **narrow / round** → shift-with-rounding + saturate to the target width.

## The language prerequisite (the real cost)

The *general* `fixed[B, F]` form needs two things the basics language may not yet have:

- **Value-generic parameters** (`10 §9`) — a type parameterized by a compile-time *integer*
  (`F`), not just by a type. Array sizes (`[16]byte`) already parameterize on a compile-time int, so
  the machinery is partly present, but user-type value generics are a larger step.
- **A little type-level arithmetic** — the multiply rule computing `F₁ + F₂` and choosing a
  wide-enough backing type at compile time.

### The fork

- **General, compile-time-checked fixed-point** — the `fixed[B, F]` design above. Requires
  value generics + type-level scale arithmetic. Safest; the type tracks scale for you.
- **Curated Q-format types** — ship `q7` / `q15` / `q31` as built-ins with explicit rescale
  operators (the CMSIS model). No value generics needed; scale is managed by hand. Less
  general, but completely usable for audio and shippable without new type-system machinery.

Whichever path, this is post-basics work. The breadcrumb is here for when the audio project
comes back around.
