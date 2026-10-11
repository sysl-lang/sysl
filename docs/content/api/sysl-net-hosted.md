---
title: sysl.net.hosted
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.net.hosted
requires: "requires { libc }, requires { posix }"
---

## Index

[`net_accept`](#net_accept) [`net_bind`](#net_bind) [`net_close`](#net_close) [`net_connect`](#net_connect) [`net_listen`](#net_listen) [`net_local`](#net_local) [`net_option`](#net_option) [`net_recv`](#net_recv) [`net_recv_from`](#net_recv_from) [`net_resolve`](#net_resolve) [`net_send`](#net_send) [`net_send_to`](#net_send_to) [`net_shutdown`](#net_shutdown) [`net_socket`](#net_socket)

## Functions

### `net_accept`

```sysl
net_accept(fd: int, peer: *Endpoint) -> int
```

### `net_bind`

```sysl
net_bind(fd: int, at: *Endpoint) -> int
```

### `net_close`

```sysl
net_close(fd: int) -> int
```

### `net_connect`

```sysl
net_connect(fd: int, to: *Endpoint) -> int
```

### `net_listen`

```sysl
net_listen(fd: int, backlog: int) -> int
```

### `net_local`

```sysl
net_local(fd: int, at: *Endpoint) -> int
```

### `net_option`

```sysl
net_option(fd: int, which: int, value: i64) -> int
```

### `net_recv`

```sysl
net_recv(fd: int, into: *u8, room: usize) -> isize
```

### `net_recv_from`

```sysl
net_recv_from(fd: int, into: *u8, room: usize, from: *Endpoint) -> isize
```

### `net_resolve`

```sysl
net_resolve(host: *u8, host_len: usize, port: int, passive: int, out: *Endpoint, room: usize) -> int
```

### `net_send`

```sysl
net_send(fd: int, from: *u8, len: usize) -> isize
```

### `net_send_to`

```sysl
net_send_to(fd: int, from: *u8, len: usize, to: *Endpoint) -> isize
```

### `net_shutdown`

```sysl
net_shutdown(fd: int, how: int) -> int
```

### `net_socket`

```sysl
net_socket(family: int, kind: int) -> int
```
