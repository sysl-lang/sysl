---
title: sysl.threads.hosted
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.threads.hosted
requires: "requires { libc }, requires { os }"
---

## Index

[`supply_futex_wait`](#supply_futex_wait) [`supply_futex_wake`](#supply_futex_wake) [`supply_join`](#supply_join) [`supply_self`](#supply_self) [`supply_spawn`](#supply_spawn) [`supply_yield`](#supply_yield)

## Functions

### `supply_futex_wait`

```sysl
supply_futex_wait(addr: *u32, expected: u32, timeout_ns: i64) -> int
```

### `supply_futex_wake`

```sysl
supply_futex_wake(addr: *u32, n: int) -> int
```

### `supply_join`

```sysl
supply_join(tid: *u64) -> int
```

### `supply_self`

```sysl
supply_self() -> u64
```

### `supply_spawn`

```sysl
supply_spawn(entry: *extern(*u8) -> unit, arg: *u8, stack: *u8, stack_len: usize, tls: *u8, tid: *u64) -> int
```

### `supply_yield`

```sysl
supply_yield() -> int
```
