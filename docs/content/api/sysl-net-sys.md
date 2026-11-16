---
title: sysl.net.sys
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.net.sys
requires: "requires { os }"
---

## Index

[`datagram`](#datagram) [`keepalive`](#keepalive) [`keepalive_count`](#keepalive_count) [`keepalive_idle`](#keepalive_idle) [`keepalive_interval`](#keepalive_interval) [`no_delay`](#no_delay) [`non_blocking`](#non_blocking) [`read_back`](#read_back) [`read_timeout`](#read_timeout) [`recv_buffer`](#recv_buffer) [`recv_buffer_size`](#recv_buffer_size) [`reuse_address`](#reuse_address) [`reuse_port`](#reuse_port) [`send_buffer`](#send_buffer) [`send_buffer_size`](#send_buffer_size) [`stream`](#stream) [`unix_domain`](#unix_domain) [`write_timeout`](#write_timeout) [`Credentials`](#credentials) [`Endpoint`](#endpoint) [`Piece`](#piece)

## Constants

### `datagram`

```sysl
const datagram: int = 1
```

`socket`'s `kind` for datagrams -- UDP.

### `keepalive`

```sysl
const keepalive: int = 10
```

`option`'s `which` for `SO_KEEPALIVE` (probe an idle connection), `value` 1 or 0.

### `keepalive_count`

```sysl
const keepalive_count: int = 13
```

`option`'s `which` for how many unanswered keepalive probes end the connection, `value` positive.

### `keepalive_idle`

```sysl
const keepalive_idle: int = 11
```

`option`'s `which` for how long a connection idles before the first keepalive probe, `value`
whole seconds and positive. It is `TCP_KEEPALIVE` on Darwin and `TCP_KEEPIDLE` elsewhere.

### `keepalive_interval`

```sysl
const keepalive_interval: int = 12
```

`option`'s `which` for the seconds between keepalive probes, `value` positive.

### `no_delay`

```sysl
const no_delay: int = 8
```

`option`'s `which` for TCP's `TCP_NODELAY` (send small writes at once rather than coalescing
them), `value` 1 or 0.

### `non_blocking`

```sysl
const non_blocking: int = 3
```

`option`'s `which` for non-blocking mode, `value` 1 or 0. A call on a non-blocking socket that would
have had to wait answers the code of `EAGAIN` instead -- 35 on a BSD, 11 everywhere else -- and that
is what `sysl.net.would_block` recognises.

### `read_back`

```sysl
const read_back: int = 6
```

Added to `no_delay` .. `keepalive_count` to read the option back: `value` is ignored and the answer
is the setting -- 0 or 1 for the flags, the number for the three keepalive times -- or a negative
status.

### `read_timeout`

```sysl
const read_timeout: int = 0
```

`option`'s `which` for how long a receive may wait, `value` milliseconds and zero for no limit.

### `recv_buffer`

```sysl
const recv_buffer: int = 5
```

`option`'s `which` for the size of the kernel's receive buffer, as `send_buffer`.

### `recv_buffer_size`

```sysl
const recv_buffer_size: int = 7
```

`option`'s `which` for reading the receive buffer back, as `send_buffer_size`.

### `reuse_address`

```sysl
const reuse_address: int = 2
```

`option`'s `which` for letting a listener take a port a recently stopped one held, `value` 1 or 0.

### `reuse_port`

```sysl
const reuse_port: int = 9
```

`option`'s `which` for `SO_REUSEPORT` (several sockets may bind one port, the kernel sharing
connections or datagrams among them), `value` 1 or 0. A target without it answers `UNSUPPORTED`.

### `send_buffer`

```sysl
const send_buffer: int = 4
```

`option`'s `which` for the size of the kernel's send buffer, `value` bytes and positive. The
supplier may round what it is given, so `send_buffer_size` asks what it granted.

### `send_buffer_size`

```sysl
const send_buffer_size: int = 6
```

`option`'s `which` for reading the send buffer back: `value` is ignored and the answer is the
size in bytes -- positive, not zero -- or a negative status.

### `stream`

```sysl
const stream: int = 0
```

`socket`'s `kind` for a stream -- TCP.

### `unix_domain`

```sysl
const unix_domain: int = 1
```

`socket`'s `family` for a Unix domain socket: a stream between processes on one machine, addressed
by a path in the filesystem rather than by an `Endpoint`. Only `stream` is asked for with it.

### `write_timeout`

```sysl
const write_timeout: int = 1
```

`option`'s `which` for how long a send may wait, `value` milliseconds and zero for no limit.

## Types

### `Credentials`

```sysl
struct Credentials
    uid: u32
    gid: u32
    pid: i64
```

Who is at the other end of a Unix domain connection, as a hook writes it: the user and group the
peer process had when it connected, and its process id where the system says so, else -1. Laid out
as C lays out a `uint32_t`, a `uint32_t` and an `int64_t` in this order: 16 bytes, no padding.

### `Endpoint`

```sysl
struct Endpoint
    octets: [16]u8
    scope: u32
    port: u16
    family: u16
```

An address and a port, as a hook reads and writes one.

`family` is 4 or 6. An IPv4 address is the first four of `octets` and the rest are zero; an IPv6
one is all sixteen, in network order -- as written, most significant first. `port` is the number
itself, not byte-swapped, and `scope` is an IPv6 address's interface index (zero for none and for
every IPv4 address). Laid out as C lays out the same four fields in this order: 24 bytes, no
padding.

### `Piece`

```sysl
struct Piece
    base: *u8
    len: usize
```

One piece of a vectored send: `len` bytes at `base`. Laid out as C's `struct iovec`.
