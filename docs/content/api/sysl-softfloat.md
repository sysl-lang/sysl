---
title: sysl.softfloat
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.softfloat
---

## Index

[`addtf3`](#addtf3) [`divtf3`](#divtf3) [`eqtf2`](#eqtf2) [`extenddftf2`](#extenddftf2) [`extendhftf2`](#extendhftf2) [`extendsftf2`](#extendsftf2) [`fixtfdi`](#fixtfdi) [`fixtfsi`](#fixtfsi) [`fixtfti`](#fixtfti) [`fixunstfdi`](#fixunstfdi) [`fixunstfsi`](#fixunstfsi) [`fixunstfti`](#fixunstfti) [`floatditf`](#floatditf) [`floatsitf`](#floatsitf) [`floattitf`](#floattitf) [`floatunditf`](#floatunditf) [`floatunsitf`](#floatunsitf) [`floatuntitf`](#floatuntitf) [`getf2`](#getf2) [`gttf2`](#gttf2) [`letf2`](#letf2) [`lttf2`](#lttf2) [`multf3`](#multf3) [`netf2`](#netf2) [`subtf3`](#subtf3) [`trunctfbf2`](#trunctfbf2) [`trunctfdf2`](#trunctfdf2) [`trunctfhf2`](#trunctfhf2) [`trunctfsf2`](#trunctfsf2) [`unordtf2`](#unordtf2)

## Functions

### `addtf3`

```sysl
addtf3(a: f128, b: f128) -> f128
```

`a + b`.

### `divtf3`

```sysl
divtf3(a: f128, b: f128) -> f128
```

`a / b`.

### `eqtf2`

```sysl
eqtf2(a: f128, b: f128) -> int
```

Zero where `a == b`, and not zero otherwise or where either is a NaN.

### `extenddftf2`

```sysl
extenddftf2(x: real) -> f128
```

A `double` widened, exactly.

### `extendhftf2`

```sysl
extendhftf2(x: f16) -> f128
```

An `f16` widened, exactly.

### `extendsftf2`

```sysl
extendsftf2(x: f32) -> f128
```

An `f32` widened, exactly.

### `fixtfdi`

```sysl
fixtfdi(x: f128) -> long
```

Toward zero, as a `long`.

### `fixtfsi`

```sysl
fixtfsi(x: f128) -> int
```

Toward zero, as an `int`.

### `fixtfti`

```sysl
fixtfti(x: f128) -> i128
```

Toward zero, as an `i128`.

### `fixunstfdi`

```sysl
fixunstfdi(x: f128) -> ulong
```

Toward zero, as a `ulong`.

### `fixunstfsi`

```sysl
fixunstfsi(x: f128) -> uint
```

Toward zero, as a `uint`.

### `fixunstfti`

```sysl
fixunstfti(x: f128) -> u128
```

Toward zero, as a `u128`.

### `floatditf`

```sysl
floatditf(x: long) -> f128
```

A `long` as binary128, exactly.

### `floatsitf`

```sysl
floatsitf(x: int) -> f128
```

An `int` as binary128, exactly.

### `floattitf`

```sysl
floattitf(x: i128) -> f128
```

An `i128` as binary128, rounded.

### `floatunditf`

```sysl
floatunditf(x: ulong) -> f128
```

A `ulong` as binary128, exactly.

### `floatunsitf`

```sysl
floatunsitf(x: uint) -> f128
```

A `uint` as binary128, exactly.

### `floatuntitf`

```sysl
floatuntitf(x: u128) -> f128
```

A `u128` as binary128, rounded.

### `getf2`

```sysl
getf2(a: f128, b: f128) -> int
```

Zero or above where `a >= b`; below zero otherwise, a NaN included.

### `gttf2`

```sysl
gttf2(a: f128, b: f128) -> int
```

Above zero where `a > b`; zero or below otherwise, a NaN included.

### `letf2`

```sysl
letf2(a: f128, b: f128) -> int
```

Zero or below where `a <= b`; above zero otherwise, a NaN included.

### `lttf2`

```sysl
lttf2(a: f128, b: f128) -> int
```

Below zero where `a < b`; zero or above otherwise, a NaN included.

### `multf3`

```sysl
multf3(a: f128, b: f128) -> f128
```

`a * b`.

### `netf2`

```sysl
netf2(a: f128, b: f128) -> int
```

Not zero where `a != b` or either is a NaN, and zero otherwise.

### `subtf3`

```sysl
subtf3(a: f128, b: f128) -> f128
```

`a - b`, which is `a` plus `b` with its sign turned over.

### `trunctfbf2`

```sysl
trunctfbf2(x: f128) -> bf16
```

Rounded to a `bf16`.

### `trunctfdf2`

```sysl
trunctfdf2(x: f128) -> real
```

Rounded to a `double`.

### `trunctfhf2`

```sysl
trunctfhf2(x: f128) -> f16
```

Rounded to an `f16`.

### `trunctfsf2`

```sysl
trunctfsf2(x: f128) -> f32
```

Rounded to an `f32`.

### `unordtf2`

```sysl
unordtf2(a: f128, b: f128) -> int
```

Not zero where either is a NaN.
