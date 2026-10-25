---
title: The fenv module
summary: "`sysl.fenv` — the floating-point environment: the rounding mode, the five exception flags, flush-to-zero and default-NaN, Arm's input-denormal flag and alternative half-precision, `with_rounding`, and the `@strict_fp` bodies that follow them."
weight: 76
---

`sysl.fenv` reads and sets the **floating-point environment** of the thread it runs on: the
rounding mode, the five IEEE 754 exception flags, the flush-to-zero and default-NaN controls, and on
Arm the input-denormal flag and the alternative half-precision control.
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
| `RoundingMode` | `NearestEven`, `Upward`, `Downward`, `TowardZero` — IEEE 754's four directions |
| `rounding()`, `set_rounding(r)` | the mode in force on this thread, and setting it |
| `with_rounding(r, body)` | runs `body` with `r` in force and puts the mode it found back on every way out |
| `FpFlags` | `invalid`, `divide_by_zero`, `overflow`, `underflow`, `inexact`, and `any()` |
| `flags()`, `clear_flags()` | the flags raised since they were last cleared, and clearing them |
| `flush_to_zero()`, `set_flush_to_zero(on)` | a subnormal read as a zero of its sign |
| `default_nan()`, `set_default_nan(on)` | a NaN result as the default NaN rather than an operand's |
| `input_denormal()` | Arm's flag for a subnormal operand flush-to-zero replaced; `clear_flags` clears it |
| `alternative_half_precision()`, `set_alternative_half_precision(on)` | Arm's alternative `f16` format in conversions |

`RoundingMode` is the direction the unit rounds every operation in, and is not core's
`Rounding`, which says how [a decimal](/library/decimal/) is rounded to a number of places when it
is asked to be — `HalfUp` and `Up` among them, which no unit offers. The two answer different
questions, so they have different names, and a file importing `sysl.fenv.*` names both without
ambiguity.

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

## The input-denormal flag

Under flush-to-zero an Arm unit reads a subnormal **operand** as a zero of its sign, and says so by
raising its input-denormal flag, IDC (bit 7 of AArch64's FPSR and of an Arm core's FPSCR).
`input_denormal()` reads it. Like the five it is sticky, and `clear_flags` clears it with them:

```sysl
import sysl.fenv.*

@strict_fp
summed(tiny: f32, small: f32) -> string
    set_flush_to_zero(true)
    clear_flags()
    val flushed = (tiny + small).bits()
    val raised = input_denormal()
    set_flush_to_zero(false)
    clear_flags()
    val exact = (tiny + small).bits()
    f"$flushed%08x $raised, $exact%08x ${input_denormal()}"

print(summed(f32.from_bits(1), f32.from_bits(0x00800000)))
```

```output
00800000 true, 00800001 false
```

The smallest subnormal added to the smallest normal is exact, and raises nothing, until
flush-to-zero reads the subnormal as zero. A subnormal *result* that is flushed raises underflow,
not this. It is not one of IEEE 754's exceptions, so it is no field of `FpFlags`, which every machine
answers. x86-64's MXCSR has a denormal-operand flag, DE, but it is raised by every subnormal operand
and by none that DAZ reads as zero, which is close to the opposite; so off Arm the call is refused
rather than answered with something else:

```sysl target=x86_64-freestanding
import sysl.fenv.*

@export("flushed")
flushed() -> bool = input_denormal()
```

```error
'input_denormal' is the input-denormal flag of an Arm unit, raised when flush-to-zero replaces a subnormal operand, and 'x86_64-freestanding' has none — MXCSR's denormal-operand flag is raised by every subnormal operand, and by none that DAZ reads as zero, which is not what IDC says
```

## Alternative half-precision

An Arm unit can read `f16` in a format of its own (AHP, bit 26 of FPCR and of FPSCR) that spends the
top exponent on numbers up to 131008 instead of on infinities and NaNs. It changes what a conversion
to or from `f16` produces: `0x7c00` widens to 65536 rather than an infinity, a narrowing past 131008
saturates to `0x7fff`, and a NaN narrows to a zero, both raising invalid. Arithmetic at `f16` is
IEEE's whatever it says.

```sysl
import sysl.fenv.*

@strict_fp
widened(h: f16) -> string
    val ieee = f32(h).bits()
    set_alternative_half_precision(true)
    val alternative = f32(h).bits()
    val on = alternative_half_precision()
    set_alternative_half_precision(false)
    f"$ieee%08x $alternative%08x $on"

print(widened(f16.from_bits(0x7c00)))
```

```output
7f800000 47800000 true
```

**The compiler knows `f16` only as IEEE's binary16.** A literal, a `const` and whatever the optimizer
works out while compiling are IEEE's bits, and LLVM's strict conversions model the rounding mode and
the exceptions, not this control. So with it on, a conversion follows it where the unit's instruction
runs — in a [`@strict_fp`](/reference/attributes/#strict-fp-floating-point-that-follows-the-environment-it-runs-in)
body, of a value read at run time — and what one the compiler can see answers is not something a
program can count on. Off Arm, `f16` is IEEE's always, and the control is refused:

```sysl target=x86_64-freestanding
import sysl.fenv.*

@export("alternative")
alternative() = set_alternative_half_precision(true)
```

```error
'set_alternative_half_precision' is the alternative half-precision control of an Arm unit, and 'x86_64-freestanding' has none — its f16 is IEEE 754's binary16 always
```

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
