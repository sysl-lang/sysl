---
title: sysl.net.sys
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.net.sys
requires: "requires { os }"
---

## Index

[`datagram`](#datagram) [`non_blocking`](#non_blocking) [`read_timeout`](#read_timeout) [`reuse_address`](#reuse_address) [`stream`](#stream) [`unix_domain`](#unix_domain) [`write_timeout`](#write_timeout) [`Endpoint`](#endpoint)

## Constants

### `datagram`

```sysl
const datagram: int = 1
```

`socket`'s `kind` for datagrams -- UDP.

### `non_blocking`

```sysl
const non_blocking: int = 3
```

`option`'s `which` for non-blocking mode, `value` 1 or 0. A call on a non-blocking socket that would
have had to wait answers the code of `EAGAIN` instead -- 35 on a BSD, 11 everywhere else -- and that
is what `sysl.net.would_block` recognises.

### `read_timeout`

```sysl
const read_timeout: int = 0
```

`option`'s `which` for how long a receive may wait, `value` milliseconds and zero for no limit.

### `reuse_address`

```sysl
const reuse_address: int = 2
```

`option`'s `which` for letting a listener take a port a recently stopped one held, `value` 1 or 0.

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
