---
title: sysl.io.hosted
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.io.hosted
requires: "requires { libc }"
---

## Index

[`io_flush`](#io_flush) [`io_poll_close`](#io_poll_close) [`io_poll_control`](#io_poll_control) [`io_poll_open`](#io_poll_open) [`io_poll_wait`](#io_poll_wait) [`io_poll_wake`](#io_poll_wake) [`io_read`](#io_read) [`io_wait`](#io_wait) [`io_write`](#io_write)

## Functions

### `io_flush`

```sysl
io_flush(fd: int) -> int
```

### `io_poll_close`

```sysl
io_poll_close(poller: int, wake: int) -> int
```

### `io_poll_control`

```sysl
io_poll_control(poller: int, fd: int, op: int, interest: int, mode: int, token: u64) -> int
```

### `io_poll_open`

```sysl
io_poll_open(wake: *i32) -> int
```

### `io_poll_wait`

```sysl
io_poll_wait(poller: int, wake: int, into: *u8, room: usize, micros: long) -> isize
```

### `io_poll_wake`

```sysl
io_poll_wake(poller: int, wake: int) -> int
```

### `io_read`

```sysl
io_read(fd: int, into: *u8, room: usize) -> isize
```

### `io_wait`

```sysl
io_wait(fd: int, writable: int, micros: long) -> int
```

### `io_write`

```sysl
io_write(fd: int, from: *u8, len: usize) -> isize
```
