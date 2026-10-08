---
title: sysl.fmt
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.fmt
summary: "What an `f\"…\"` hole's computed width or precision comes to — `${x}%.${n}f`, `${s}%${w}s`."
---

A program does not call these; the compiler does, at a specifier whose width or precision is a
hole of its own. They read a count the way C's `*` does, which is the one rule both halves of a
specifier follow: a value C formats is handed the count, and a type rendering itself through
`Display` is handed the `FormatSpec` the same count makes.

## Index

[`format_count`](#format_count) [`format_spec_of`](#format_spec_of)

## Functions

### `format_count`

```sysl
format_count(n: i128) -> int
```

A count as the `int` C's `*` reads, saturated at `int`'s range rather than wrapped into it.

The compiler hands every integer over at `i128`, which holds any of them up to 64 bits exactly,
so a `usize` past `int` is a very wide field rather than a negative one.

### `format_spec_of`

```sysl
format_spec_of(width: int, prec: int, left: bool) -> FormatSpec
```

The `FormatSpec` a run-time width and precision make, read as C reads a `*`: a negative width
left-justifies the field at its magnitude, and a negative precision is as though none was written.
