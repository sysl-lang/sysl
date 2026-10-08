---
title: sysl.posix.net
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.posix.net
summary: "Blocking TCP, and the names a host and a service resolve to."
requires: "requires { posix }"
---

**It is `sysl.posix.net` rather than `sysl.net`, and the namespace is a promise about the shape.**
The library has two conventions and the rule that separates them is visible in one pair:
`sysl.term` is the portable surface sysl invented over a terminal, and `sysl.posix.tty` is
`termios` presented as `termios`. Everything under `sysl.posix` keeps the underlying API's own
vocabulary. So this is `socket`, `bind`, `listen`, `accept`, `connect`, `send`, `recv`,
`shutdown` and `close`, in that order and with those meanings, and it is deliberately **not** a
`TcpStream`.

**A portable `sysl.net` comes later, over this, and doing it in that order is the point.** POSIX
made every decision this module needs forty years ago, so mirroring it cannot guess wrong -- and a
module that cannot guess wrong is one that can be frozen. What a *portable* address is, what a
portable error is, how a timeout is spelled, whether a listener is a type or a function: those are
guesses, and they want real consumers before anything is fixed. Adding a module later is free.

## Blocking, and only blocking

Every call here returns when its work is done. There is no event loop, no non-blocking mode and
no readiness notification, and that is the same line Rust draws -- `std::net` is blocking and
every async runtime is a package outside it. `sysl-lang/libuv` is where the event-loop story
lives, and the two compose: a program that wants one uses the package, and a program that wants a
straight line uses this.

**A timeout is the one thing a blocking call cannot do without**, because otherwise "returns when
the work is done" can mean never. `read_timeout` and `write_timeout` are what bound it, and a call
that hits one answers an error `timed_out` recognises.

## What is deliberately out

UDP, multicast, unix domain sockets, non-blocking mode, and every socket option beyond the timeout
and the one a listener needs. All of them are **additive**, so leaving them out costs a later
release nothing; guessing at them now would fix a shape before anybody has used one.

## Index

[`resolve`](#resolve) [`resolve_passive`](#resolve_passive) [`socket`](#socket) [`timed_out`](#timed_out) [`Address`](#address) [`Family`](#family) [`Shutdown`](#shutdown) [`Socket`](#socket-1) [Display for Address](#display-for-address)

## Functions

### `resolve`

```sysl
resolve(host: string, port: int) -> Result[Buf[Address], IoError]
```

Every address a host and a port resolve to, in the order the machine ranked them.

**Both protocol versions are asked for and both come back.** That is what makes a program work on
a v6-only network without knowing it is on one, and the *order* is the system's answer to which
to prefer — RFC 6724 says how a machine sorts them, and second-guessing it here would be this
module inventing policy that is not its to have. So the ordinary client is a loop:

```
for a in resolve("example.com", 80)?
    var s = socket(a)?
    if s.connect(a).is_ok() then break
    s.close()
```

A failure to resolve is reported as `NotFound`, whatever the resolver's own reason was. The
`EAI_*` numbers are a numbering of their own and overlap `errno`'s — `EAI_AGAIN` is 2 on Darwin,
which is `ENOENT` — so passing them through would produce an `IoError` that means something else
entirely.

### `resolve_passive`

```sysl
resolve_passive(port: int) -> Result[Buf[Address], IoError]
```

Where a **listener** binds: the wildcard, meaning every address this machine answers on.

A separate call rather than an empty host, because the same question has two answers and nothing
about the arguments alone says which is wanted — `AI_PASSIVE` is the flag that decides it, and a
caller that got the wrong one binds to the loopback and wonders why nobody can reach it.

### `socket`

```sysl
socket(for_address: Address) -> Result[Socket, IoError]
```

A socket of the family an address belongs to, ready to be connected or bound.

The family comes from the address rather than being said separately: a socket of the wrong one
cannot be connected to it, so letting the two be given apart would only make it possible to
disagree.

### `timed_out`

```sysl
timed_out(e: IoError) -> bool
```

Whether an error is a call that ran out of its timeout rather than one that failed.

A timed-out blocking call reports `EAGAIN`, which is a number rather than a case of `IoError` —
so this is the question to ask instead of comparing against one. It is a function rather than an
enum case because the alternative is adding a variant to an error type every module in the
library shares, for a condition only this one can produce.

## Types

### `Address`

```sysl
struct Address
    private bytes: [addr_bytes]u8
    private used: i32
```

Somewhere a socket can be connected to, or bound to.

**It carries the platform's own bytes and sysl never looks inside them.** A `sockaddr_in` and a
`sockaddr_in6` are different layouts, and Darwin's carry a length byte glibc's do not, so a
struct written here would be a transcription of a layout — which `reference/ffi.md § A library
may carry C` says is the thing that compiles everywhere and is wrong somewhere. What crosses is
the bytes and their length; everything a program wants to *know* about an address it asks for.

| Member | Signature | Description |
|---|---|---|
| `family` | `family(self) -> Family` | Which protocol version this is for. |
| `text` | `text(self) -> string` | The numeric form, as a person writes it — `127.0.0.1`, or `::1`. |
| `port` | `port(self) -> int` | The port. |

### `Family`

```sysl
enum Family
    Ipv4
    Ipv6
```

Which version of the protocol an address is for.

Named by the version rather than by `AF_INET`'s number, which is the thing a program must not be
handed: `AF_INET6` is 30 on Darwin and 10 under glibc, so a program that compared against a
literal would be right on one machine and silently wrong on the other.

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

A connection, or something listening for one. One type, because POSIX has one.

**A socket is not closed by going out of scope.** `close` is a call, exactly as it is in C, and a
program that drops one without closing it leaks a descriptor until it exits — which is C's own
rule rather than something this binding introduced, and is the same shape `sysl-lang/libuv` states
about a handle. `defer s.close()` is the idiom.

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
| `shutdown` | `shutdown(*self, how: Shutdown = Both) -> Result[unit, IoError]` | Stop reading, stop writing, or stop both — while the descriptor stays open. |
| `close` | `close(*self) -> Result[unit, IoError]` | Give the descriptor back. |
| `read_timeout` | `read_timeout(*self, ms: int) -> Result[unit, IoError]` | How long a `recv` may wait before giving up, in milliseconds. |
| `write_timeout` | `write_timeout(*self, ms: int) -> Result[unit, IoError]` | How long a `send` may wait before giving up, in milliseconds. |
| `reuse_address` | `reuse_address(*self, on: bool = true) -> Result[unit, IoError]` | Let a listener take a port that a recently stopped one was using. |

## Implementations

### Display for Address

```sysl
impl Display for Address
```

So that printing one says where it is rather than how it is stored.
