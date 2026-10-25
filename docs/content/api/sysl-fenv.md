---
title: sysl.fenv
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.fenv
summary: "The floating-point environment: the rounding mode, the five exception flags, and on Arm the flush-to-zero and default-NaN controls."
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

[`clear_flags`](#clear_flags) [`default_nan`](#default_nan) [`flags`](#flags) [`flush_to_zero`](#flush_to_zero) [`rounding`](#rounding) [`set_default_nan`](#set_default_nan) [`set_flush_to_zero`](#set_flush_to_zero) [`set_rounding`](#set_rounding) [`with_rounding`](#with_rounding) [`FpFlags`](#fpflags) [`RoundingMode`](#roundingmode)

## Functions

### `clear_flags`

```sysl
clear_flags()
```

Clears the five flags on this thread, leaving the controls as they are.

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

### `rounding`

```sysl
rounding() -> RoundingMode
```

The rounding mode in force on this thread.

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
