---
title: sysl.fenv
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.fenv
summary: "The floating-point environment: the rounding mode, the five exception flags, and on Arm the flush-to-zero, default-NaN and alternative half-precision controls and the input-denormal flag."
requires: "no alloc"
---

**The environment belongs to the thread.** Each thread has its own control and status registers --
AArch64's FPCR and FPSR, x86-64's MXCSR, an Armv7-M or Armv8-M core's FPSCR, RISC-V's `fcsr` -- so
what one thread sets is never seen by another, and a thread starts with round-to-nearest and no
flags raised.

**Only a `@strict_fp` body promises to follow it** (`reference/attributes.md § @strict_fp`). An
ordinary body's operations may be computed while compiling, merged with one another, or moved past
a call of `set_rounding`, so what they round to and which flags they raise is not something a
program can count on; inside a strict body every operation runs where it was written, in the
environment that is in force there.

A machine with no floating-point unit has no environment, and a program reaching this module there
is refused at the call; so is one asking for a control its unit does not have.

## Index

[`alternative_half_precision`](#alternative_half_precision) [`clear_flags`](#clear_flags) [`default_nan`](#default_nan) [`flags`](#flags) [`flush_to_zero`](#flush_to_zero) [`input_denormal`](#input_denormal) [`rounding`](#rounding) [`set_alternative_half_precision`](#set_alternative_half_precision) [`set_default_nan`](#set_default_nan) [`set_flush_to_zero`](#set_flush_to_zero) [`set_rounding`](#set_rounding) [`with_rounding`](#with_rounding) [`FpFlags`](#fpflags) [`RoundingMode`](#roundingmode)

## Functions

### `alternative_half_precision`

```sysl
alternative_half_precision() -> bool
```

Whether a conversion to or from `f16` reads binary16 as Arm's alternative half-precision format
rather than IEEE 754's: FPCR.AHP on AArch64, FPSCR.AHP on an Arm core.

The alternative format spends the top exponent on numbers, up to 131008, instead of on infinities
and NaNs. So with it on a conversion to `f16` turns a NaN into a zero and an infinity -- or a
number past 131008 -- into the largest magnitude, raising invalid for each, and a conversion from
`f16` reads `0x7c00` as 65536 rather than an infinity.

The compiler knows `f16` only as IEEE's binary16: a literal, a `const` and whatever the optimizer
works out while compiling are IEEE's bits, and LLVM's strict conversions model the rounding mode
and the exceptions but not this control. So with it on, a conversion is the unit's -- and follows
it -- only where the instruction runs: in a `@strict_fp` body of a value read at run time. Of a value
the compiler can see, what it answers is not something a program can count on.

### `clear_flags`

```sysl
clear_flags()
```

Clears the five flags on this thread, and on Arm the input-denormal flag with them, leaving the
controls as they are.

### `default_nan`

```sysl
default_nan() -> bool
```

Whether an operation that answers a NaN answers the default one -- positive, quiet, no payload --
rather than propagating the payload of a NaN operand.

### `flags`

```sysl
flags() -> FpFlags
```

The flags raised on this thread since they were last cleared.

### `flush_to_zero`

```sysl
flush_to_zero() -> bool
```

Whether a subnormal result -- and, on x86-64, a subnormal operand -- is read as a zero of its sign.

### `input_denormal`

```sysl
input_denormal() -> bool
```

Whether, since the flags were last cleared, flush-to-zero has replaced a subnormal OPERAND with a
zero: Arm's input-denormal flag, FPSR.IDC on AArch64 and FPSCR.IDC on an Arm core. A subnormal
result that is flushed raises underflow instead, and with flush-to-zero off nothing raises this.
It is sticky like the five, and `clear_flags` clears it with them.

It is not one of IEEE 754's exceptions, so it is not a field of `FpFlags`, which every machine
answers; this is an Arm flag, and a program asking for it elsewhere is refused at the call.

### `rounding`

```sysl
rounding() -> RoundingMode
```

The rounding mode in force on this thread.

### `set_alternative_half_precision`

```sysl
set_alternative_half_precision(on: bool)
```

Turns Arm's alternative half-precision format on or off on this thread.

### `set_default_nan`

```sysl
set_default_nan(on: bool)
```

Turns default-NaN on or off on this thread: FPCR.DN on AArch64 and Arm.

### `set_flush_to_zero`

```sysl
set_flush_to_zero(on: bool)
```

Turns flush-to-zero on or off on this thread: AArch64's and Arm's FPCR.FZ, x86-64's MXCSR.FTZ with
MXCSR.DAZ beside it, so that both machines flush what goes in as well as what comes out.

### `set_rounding`

```sysl
set_rounding(r: RoundingMode)
```

Makes `r` the rounding mode on this thread.

### `with_rounding`

```sysl
with_rounding[R](r: RoundingMode, body: () -> R) -> R
```

Runs `body` with `r` the rounding mode, and puts the mode back on every way out of it.

What `body` computes follows `r` where `body` is written in a `@strict_fp` function -- a closure
written there is strict too. Elsewhere its operations are not held to the mode at all.

## Types

### `FpFlags`

```sysl
struct FpFlags
    invalid: bool
    divide_by_zero: bool
    overflow: bool
    underflow: bool
    inexact: bool
```

The five exceptions IEEE 754 defines, each one raised -- set, and left set -- by an operation that
meets it. Nothing clears a flag but `clear_flags`, so a flag read after a run of operations says
whether any of them raised it.

| Member | Signature | Description |
|---|---|---|
| `any` | `any(self) -> bool` | Whether any of the five is raised. |

### `RoundingMode`

```sysl
enum RoundingMode
    NearestEven
    Upward
    Downward
    TowardZero
```

How a result that falls between two representable values is rounded: IEEE 754's four directions.
