---
title: sysl.signal.hosted
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.signal.hosted
requires: "requires { libc }, requires { posix }"
---

## Index

[`sig_arrivals`](#sig_arrivals) [`sig_disposition`](#sig_disposition) [`sig_raise`](#sig_raise) [`sig_send`](#sig_send) [`sig_watch`](#sig_watch)

## Functions

### `sig_arrivals`

```sysl
sig_arrivals(sig: int) -> i64
```

### `sig_disposition`

```sysl
sig_disposition(sig: int, how: int) -> int
```

### `sig_raise`

```sysl
sig_raise(sig: int) -> int
```

### `sig_send`

```sysl
sig_send(pid: i64, sig: int) -> int
```

### `sig_watch`

```sysl
sig_watch(sig: int) -> int
```
