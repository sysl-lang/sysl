---
title: sysl.net
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.net
summary: "Blocking sockets -- a TCP stream, a UDP socket -- and the names a host resolves to."
requires: "requires { os }"
---

**Everything here goes through `sysl.net.sys` and through nothing else.** On a hosted POSIX target
the library answers those hooks over the platform's sockets; on a target with no C library -- a
kernel, a board with a network stack of its own -- the program answers them, and one it reaches and
leaves unanswered is refused when it is compiled. So the module names no C function and no
`sockaddr`: an `Address` is a sysl value, four or sixteen bytes and a port, built with `ipv4` or
`ipv6` or handed back by `resolve`.

**The vocabulary is still the sockets one** -- `socket`, `bind`, `listen`, `accept`, `connect`,
`send`, `recv`, `shutdown`, `close` -- because forty years of programs and of documentation are
written in it, and a reader who knows it should not have to translate.

## Blocking, and only blocking

Every call here returns when its work is done. There is no event loop, no non-blocking mode and no
readiness notification, and that is the line Rust draws too -- `std::net` is blocking and every
async runtime is a package outside it. `sysl-lang/libuv` is where the event-loop story lives.

**A timeout is the one thing a blocking call cannot do without**, because otherwise "returns when
the work is done" can mean never. `read_timeout` and `write_timeout` are what bound it, and a call
that hits one answers an error `timed_out` recognises.

## What is deliberately out

Multicast, broadcast, unix domain sockets, non-blocking mode, and every socket option beyond the
timeouts and the one a listener needs. All of them are **additive**, so leaving them out costs a
later release nothing.

## Index

[`ipv4`](#ipv4) [`ipv6`](#ipv6) [`resolve`](#resolve) [`resolve_passive`](#resolve_passive) [`socket`](#socket) [`timed_out`](#timed_out) [`udp_socket`](#udp_socket) [`Address`](#address) [`Family`](#family) [`Shutdown`](#shutdown) [`Socket`](#socket-1) [`UdpSocket`](#udpsocket) [Display for Address](#display-for-address)

## Functions

### `ipv4`

```sysl
ipv4(a: u8, b: u8, c: u8, d: u8, port: u16) -> Address
```

The IPv4 address `a.b.c.d` at `port`. `ipv4(127, 0, 0, 1, 8080)` is the loopback, and
`ipv4(0, 0, 0, 0, port)` the wildcard a listener binds to.

### `ipv6`

```sysl
ipv6(groups: [8]u16, port: u16, scope: u32 = 0) -> Address
```

The IPv6 address of eight 16-bit `groups`, as written left to right, at `port` -- `::1` is
`[0, 0, 0, 0, 0, 0, 0, 1]`. `scope` is the interface a link-local address belongs to, and zero
for none.

### `resolve`

```sysl
resolve(host: string, port: int) -> Result[Buf[Address], IoError]
```

Every address a host and a port resolve to, in the order the machine ranked them.

**Both protocol versions are asked for and both come back.** That is what makes a program work on
a v6-only network without knowing it is on one, and the *order* is the system's answer to which
to prefer -- RFC 6724 says how a machine sorts them. So the ordinary client is a loop:

```
for a in resolve("example.com", 80)?
    var s = socket(a)?
    if s.connect(a).is_ok() then break
    s.close()
```

A failure to resolve is reported as `NotFound`, whatever the resolver's own reason was. A numeric
host -- `127.0.0.1`, `::1` -- resolves to itself, and where no resolver is wanted `ipv4` and `ipv6`
build an address directly.

### `resolve_passive`

```sysl
resolve_passive(port: int) -> Result[Buf[Address], IoError]
```

Where a **listener** binds: the wildcard, meaning every address this machine answers on.

A separate call rather than an empty host, because the same question has two answers and nothing
about the arguments alone says which is wanted -- a caller that got the wrong one binds to the
loopback and wonders why nobody can reach it.

### `socket`

```sysl
socket(for_address: Address) -> Result[Socket, IoError]
```

A stream socket of the family an address belongs to, ready to be connected or bound.

The family comes from the address rather than being said separately: a socket of the wrong one
cannot be connected to it, so letting the two be given apart would only make it possible to
disagree.

### `timed_out`

```sysl
timed_out(e: IoError) -> bool
```

Whether an error is a call that ran out of its timeout rather than one that failed.

It is a function rather than a case of `IoError` because the alternative is adding a variant to an
error type every module in the library shares, for a condition only this one can produce.

### `udp_socket`

```sysl
udp_socket(for_address: Address) -> Result[UdpSocket, IoError]
```

A datagram socket of the family an address belongs to, ready to be bound or connected.

## Types

### `Address`

```sysl
struct Address
    private[net] at: Endpoint
```

Somewhere a socket can be connected to, bound to, or sent a datagram: an IP address and a port.

**It is a sysl value, not the platform's bytes.** Four bytes and a port, or sixteen and a port,
compared with `==` and hashed like any other value; what a `sockaddr_in` looks like on this
machine is the hosted answer's business and nobody else's. Built by `ipv4` and `ipv6`, or handed
back by `resolve`, `accept` and `recv_from`.

| Member | Signature | Description |
|---|---|---|
| `family` | `family(self) -> Family` | Which protocol version this is for. |
| `port` | `port(self) -> int` | The port. |
| `octets` | `octets(self) -> [16]u8` | The address's bytes, most significant first: all sixteen of an IPv6 one, and the first four of an IPv4 one with the rest zero. |
| `with_port` | `with_port(self, port: u16) -> Address` | The same address at another port -- where a reply goes, or a second service on one host. |
| `text` | `text(self) -> string` | The numeric form, as a person writes it -- `127.0.0.1`, or `::1`, or `fe80::1%4` with the interface an IPv6 address is scoped to. |

### `Family`

```sysl
enum Family
    Ipv4
    Ipv6
```

Which version of the protocol an address is for.

### `Shutdown`

```sysl
enum Shutdown
    Read
    Write
    Both
```

How much of a connection is being given up.

### `Socket`

```sysl
struct Socket
    private fd: i32
```

A connection, or something listening for one. One type, because the sockets API has one.

**A socket is not closed by going out of scope.** `close` is a call, exactly as it is in C, and a
program that drops one without closing it leaks a descriptor until it exits. `defer s.close()` is
the idiom.

| Member | Signature | Description |
|---|---|---|
| `connect` | `connect(*self, to: Address) -> Result[unit, IoError]` | Connect to somewhere. |
| `bind` | `bind(*self, to: Address) -> Result[unit, IoError]` | Take a local address, which is what a listener does before it listens. |
| `listen` | `listen(*self, backlog: int = 128) -> Result[unit, IoError]` | Start accepting connections, with `backlog` of them allowed to queue. |
| `accept` | `accept(*self) -> Result[(Socket, Address), IoError]` | Wait for a connection, and answer it together with where it came from. |
| `local` | `local(self) -> Result[Address, IoError]` | Where this socket is, which is how a listener that asked for port 0 learns which port it got. |
| `send` | `send(*self, bytes: []const u8) -> Result[usize, IoError]` | Write some of `bytes`, and say how many. |
| `send_all` | `send_all(*self, bytes: []const u8) -> Result[unit, IoError]` | Every byte of `bytes`, however many calls that takes. |
| `recv` | `recv(*self, into: []u8) -> Result[usize, IoError]` | Read into `into`, and say how many bytes arrived. |
| `shutdown` | `shutdown(*self, how: Shutdown = Both) -> Result[unit, IoError]` | Stop reading, stop writing, or stop both -- while the descriptor stays open. |
| `close` | `close(*self) -> Result[unit, IoError]` | Give the descriptor back. |
| `read_timeout` | `read_timeout(*self, ms: int) -> Result[unit, IoError]` | How long a `recv` may wait before giving up, in milliseconds. |
| `write_timeout` | `write_timeout(*self, ms: int) -> Result[unit, IoError]` | How long a `send` may wait before giving up, in milliseconds. |
| `reuse_address` | `reuse_address(*self, on: bool = true) -> Result[unit, IoError]` | Let a listener take a port that a recently stopped one was using, so that a server just stopped can be started again without waiting out `TIME_WAIT`. |

### `UdpSocket`

```sysl
struct UdpSocket
    private fd: i32
```

A datagram socket: UDP. Each `send_to` is one datagram and each `recv_from` takes one whole,
with who sent it.

**A datagram is not a stream.** One that is longer than the buffer it is received into loses the
rest, one sent may never arrive, and two may arrive in either order. What UDP is for is exactly
the exchanges that can live with that -- a query and its answer, a heartbeat, a sample -- and the
calls here say nothing more than that.

Not closed by going out of scope, for the reason `Socket` is not.

| Member | Signature | Description |
|---|---|---|
| `bind` | `bind(*self, to: Address) -> Result[unit, IoError]` | Take a local address: the port this socket receives on. |
| `connect` | `connect(*self, to: Address) -> Result[unit, IoError]` | Fix where this socket talks to: `send` then goes there without naming it, and `recv` takes datagrams from there and from nowhere else. |
| `send_to` | `send_to(*self, bytes: []const u8, to: Address) -> Result[usize, IoError]` | Send `bytes` as one datagram to `to`, and say how many went -- all of them, or an error. |
| `recv_from` | `recv_from(*self, into: []u8) -> Result[(usize, Address), IoError]` | Wait for one datagram, read as much of it as `into` holds, and answer how many bytes that was together with who sent it. |
| `send` | `send(*self, bytes: []const u8) -> Result[usize, IoError]` | Send `bytes` as one datagram to where `connect` pointed this socket. |
| `recv` | `recv(*self, into: []u8) -> Result[usize, IoError]` | Wait for one datagram from where `connect` pointed this socket, and answer how many of its bytes `into` held. |
| `local` | `local(self) -> Result[Address, IoError]` | Where this socket is bound. |
| `read_timeout` | `read_timeout(*self, ms: int) -> Result[unit, IoError]` | How long a receive may wait before giving up, in milliseconds. |
| `write_timeout` | `write_timeout(*self, ms: int) -> Result[unit, IoError]` | How long a send may wait before giving up, in milliseconds. |
| `close` | `close(*self) -> Result[unit, IoError]` | Give the descriptor back. |

## Implementations

### Display for Address

```sysl
impl Display for Address
```

So that printing one says where it is rather than how it is stored: `127.0.0.1:8080`, and
`[::1]:8080` -- the brackets are not decoration, a v6 address having colons in it, so `::1:8080`
cannot be read apart. It is the form the URL syntax settled on.
