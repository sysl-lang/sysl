---
title: The fenv module
summary: "`sysl.fenv` — the floating-point environment: the rounding mode, the five exception flags, flush-to-zero and default-NaN, `with_rounding`, and the `@strict_fp` bodies that follow them."
weight: 76
---

`sysl.fenv` reads and sets the **floating-point environment** of the thread it runs on: the
rounding mode, the five IEEE 754 exception flags, and the flush-to-zero and default-NaN controls.
What follows the environment is a [`@strict_fp`](/reference/attributes/#strict-fp-floating-point-that-follows-the-environment-it-runs-in)
body; an ordinary one's operations may be worked out while compiling or moved past a call, so the
mode is no promise about them.

```sysl
import sysl.fenv.*

@strict_fp
sums(x: f32, y: f32)
    for r in [NearestEven, Upward, Downward, TowardZero]
        set_rounding(r)
        print(f"${(x + y).bits()}%08x")

    set_rounding(NearestEven)

sums(1.0f32, f32.from_bits(0x33800000))
print(rounding() == NearestEven)
```

```output
3f800000
3f800001
3f800000
3f800000
true
```

`1.0 + 2^-24` is exactly halfway between `1.0` and the float above it: to nearest the tie goes to the
even `1.0`, upward to the float above, and down and toward zero to `1.0`.

| | |
|---|---|
| `Rounding` | `NearestEven`, `Upward`, `Downward`, `TowardZero` — IEEE 754's four directions |
| `rounding()`, `set_rounding(r)` | the mode in force on this thread, and setting it |
| `with_rounding(r, body)` | runs `body` with `r` in force and puts the mode it found back on every way out |
| `FpFlags` | `invalid`, `divide_by_zero`, `overflow`, `underflow`, `inexact`, and `any()` |
| `flags()`, `clear_flags()` | the flags raised since they were last cleared, and clearing them |
| `flush_to_zero()`, `set_flush_to_zero(on)` | a subnormal read as a zero of its sign |
| `default_nan()`, `set_default_nan(on)` | a NaN result as the default NaN rather than an operand's |

## The flags are sticky

An operation that meets an exception sets its flag, and nothing but `clear_flags` clears it, so a read
after a run of operations says whether any of them raised it:

```sysl
import sysl.fenv.*

@strict_fp
sweep(one: f64) -> FpFlags
    clear_flags()
    val big = one * 1.0e300
    val a = big * big
    val b = (one - one) / (one - one)
    val f = flags()
    clear_flags()
    f

val f = sweep(1.0)
print(f.overflow, f.inexact, f.invalid, f.divide_by_zero, f.underflow)
print(flags().any())
```

```output
true true true false false
false
```

## `with_rounding` puts the mode back

```sysl
import sysl.fenv.*

@strict_fp
third(x: f64) -> u64 = with_rounding(Upward, () -> x / 3.0).bits()

print(f"${third(1.0)}%016x", rounding() == NearestEven)
```

```output
3fd5555555555556 true
```

The mode is put back by a `defer`, so it is back before `with_rounding` answers whatever `body`
answered. The closure is strict because it is written inside a strict function.

## Each thread has its own

The registers behind the environment are per thread — AArch64's FPCR and FPSR, x86-64's MXCSR, an
Arm core's FPSCR, RISC-V's `fcsr` — so a mode one thread sets is never seen by another, and a thread
starts with round-to-nearest and no flags raised.

## Flush-to-zero and default-NaN

```sysl
import sysl.fenv.*

@strict_fp
halved(x: f32) -> (u32, u32)
    val plain = (x * 0.5f32).bits()
    set_flush_to_zero(true)
    val flushed = (x * 0.5f32).bits()
    set_flush_to_zero(false)
    (plain, flushed)

val (plain, flushed) = halved(f32.from_bits(0x00800000))
print(f"$plain%08x $flushed%08x")
```

```output
00400000 00000000
```

Half the smallest normal binary32 is a subnormal, and under flush-to-zero a zero. On AArch64 and an
Arm core it is FPCR.FZ; on x86-64 it is MXCSR.FTZ with MXCSR.DAZ beside it, so that x86-64 flushes a
subnormal operand as well as a subnormal result, as Arm does.

Default-NaN (FPCR.DN) makes an operation that answers a NaN answer the default one, `0x7fc00000` at
binary32, rather than its NaN operand's payload. It is an Arm control: on x86-64 an SSE operation
always answers its NaN operand's, made quiet, and on RISC-V always the canonical NaN, so asking for it
there is refused:

```sysl target=x86_64-freestanding
import sysl.fenv.*

@export("on")
on() = set_default_nan(true)
```

```error
'set_default_nan' is the default-NaN control of an Arm unit, and 'x86_64-freestanding' has none — an SSE operation that answers a NaN always answers its NaN operand's, made quiet
```

A RISC-V unit computes every subnormal exactly, so flush-to-zero is refused there the same way.

## Where there is no environment

A core with no floating-point unit computes a float with a library routine, which has no rounding
mode and keeps no flags; WebAssembly always rounds to nearest; 32-bit x86's floating point is the x87
unit's, which this module does not reach. A program calling into `sysl.fenv` for one of them is
refused at the call:

```sysl target=thumbv6m-freestanding
import sysl.fenv.*

@export("up")
up() -> bool = rounding() == Upward
```

```error
'sysl.fenv' reads and writes the floating-point unit's control and status registers, and 'thumbv6m-freestanding' has no floating-point unit — a float there is computed by a library routine, which has no environment
```
