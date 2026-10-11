---
title: sysl.io.hosted
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.io.hosted
requires: "requires { libc }"
---

## Index

[`io_flush`](#io_flush) [`io_read`](#io_read) [`io_write`](#io_write)

## Functions

### `io_flush`

```sysl
io_flush(fd: int) -> int
```

### `io_read`

```sysl
io_read(fd: int, into: *u8, room: usize) -> isize
```

### `io_write`

```sysl
io_write(fd: int, from: *u8, len: usize) -> isize
```
