---
title: sysl.env.hosted
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.env.hosted
requires: "requires { libc }, requires { os }"
---

## Index

[`supply_count`](#supply_count) [`supply_entry`](#supply_entry) [`supply_get`](#supply_get)

## Functions

### `supply_count`

```sysl
supply_count() -> isize
```

### `supply_entry`

```sysl
supply_entry(i: usize, into: *u8, room: usize, got: *usize) -> int
```

### `supply_get`

```sysl
supply_get(name: *u8, name_len: usize, into: *u8, room: usize, got: *usize) -> int
```
