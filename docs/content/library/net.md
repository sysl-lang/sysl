---
title: The net module
summary: "`sysl.net` — blocking TCP and UDP and the names they resolve: `socket`, `bind`, `listen`, `accept`, `connect`, `send`, `recv`, `send_to`, `recv_from`, `shutdown`, `close`; addresses as sysl values; and the hooks a target without a C library answers."
weight: 76
---

**Every declaration in `sysl.net`, with its signature:** [the generated API page](/api/sysl-net/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.net` is blocking sockets: a TCP stream, a UDP socket, an address to give either, and the calls
that move bytes. It requires `os`.

```sysl
import sysl.net.{ipv4, resolve}

val addrs = resolve("127.0.0.1", 8080).unwrap()

print(addrs.len() > 0)
print(addrs.at(0).text(), addrs.at(0).port(), addrs.at(0).family())
print(addrs.at(0))
print(addrs.at(0) == ipv4(127, 0, 0, 1, 8080))
```

```output
true
127.0.0.1 8080 Ipv4
127.0.0.1:8080
true
```

## Why it is `sysl.net`, and still speaks sockets

The library has two conventions, and the rule that separates them is visible in one pair:
[`sysl.term`](/library/term/) is the portable surface sysl invented over a terminal, and
`sysl.posix.tty` is `termios` presented as `termios`. **Everything under `sysl.posix` is a POSIX API
presented as itself, and this module is not one any more**: every call it makes goes through the
hooks of `sysl.net.sys`, which a hosted target's sockets answer and a kernel with a network stack of
its own answers just as well, and an address is a sysl value rather than a `sockaddr`. So it is
`sysl.net`. **It was `sysl.posix.net` until 0.1.0-alpha.6**; the names inside are the same, and moving
a program is its `import` line.

**The vocabulary stays the sockets one** — `socket`, `bind`, `listen`, `accept`, `connect`, `send`,
`recv`, `shutdown`, `close`, with those meanings — because forty years of programs and documentation
are written in it, and a reader who knows it should not have to translate. It is deliberately **not**
a `TcpStream` and a `TcpListener`: the sockets API has one type for both, and so does this.

## Blocking, and only blocking

Every call here returns when its work is done. There is no event loop, no non-blocking mode and no
readiness notification.

**That is the line Rust draws too**, and for the same reason: `std::net` is blocking and every async
runtime, tokio included, is a package outside the standard library. `sysl-lang/libuv` is where the
event-loop story lives, and the two compose — a program that wants a loop uses the package, and one
that wants a straight line uses this.

## A client

Resolve, make a socket of the address's family, connect.

```sysl
import sysl.net.{resolve, socket, Socket}

// A listener of our own, so the page has something to connect to. Port 0 asks the machine for one
// it has free, and `local` is what says which it gave.
val here = resolve("127.0.0.1", 0).unwrap().at(0)
var server = socket(here).unwrap()

server.reuse_address().unwrap()
server.bind(here).unwrap()
server.listen().unwrap()

val at = server.local().unwrap()

var client = socket(at).unwrap()

client.connect(at).unwrap()

var (accepted, from) = server.accept().unwrap()

client.send_all([104, 105]).unwrap()

var into: []u8 = [0; 8]
val got = accepted.recv(into).unwrap()

print(got, into[0] == u8('h'), into[1] == u8('i'))
print(from.family())

accepted.close().unwrap()
client.close().unwrap()
server.close().unwrap()
```

```output
2 true true
Ipv4
```

**`resolve` answers every address, in the order the machine ranked them, and both protocol versions
come back.** That is what makes a program work on a v6-only network without knowing it is on one,
and the order is the system's answer to which to prefer — RFC 6724 says how a machine sorts them,
and second-guessing it here would be this module inventing policy that is not its to have. So the
ordinary client is a loop over what came back, stopping at the first address that connects.

**`resolve_passive` is the other question.** A listener binds to the wildcard — every address the
machine answers on — and nothing about the arguments alone says whether that or the loopback was
meant. It is a separate call because a caller who got the wrong one binds to the loopback and
wonders why nobody outside can reach it.

## The bytes

`send` writes some of what it was given and says how many. **A short write is not an error**: a
stream moves what it can and the caller comes back for the rest. `send_all` is that loop, and is
what almost every caller wants — it also retries an interrupted call rather than reporting it, since
a signal arriving mid-write is not a failure of the write.

`recv` reads into a slice and says how many bytes arrived. **Zero means the peer has finished
writing** — end of stream, not an error and not a timeout. That is the one answer every reader has
to handle, and it is why the count is a plain `usize`: a stream that is over is an ordinary outcome,
and a loop that reads until zero is the whole protocol.

## `shutdown` is not `close`

`shutdown` gives up one direction while the descriptor stays open, and the interesting case is
`Write`: it sends the peer an end of stream, so a program that has finished *asking* can say so and
then go on reading the answer. Closing would have said nothing and thrown the answer away.

```sysl
import sysl.net.{resolve, socket, Shutdown}

val here = resolve("127.0.0.1", 0).unwrap().at(0)
var server = socket(here).unwrap()

server.bind(here).unwrap()
server.listen().unwrap()

val at = server.local().unwrap()
var client = socket(at).unwrap()

client.connect(at).unwrap()

var (accepted, _) = server.accept().unwrap()

client.shutdown(Shutdown.Write).unwrap()

var into: []u8 = [0; 4]

// The client has finished writing, so the server sees end of stream -- and can still answer.
print(accepted.recv(into).unwrap())

accepted.send_all([121]).unwrap()

print(client.recv(into).unwrap(), into[0] == u8('y'))

accepted.close().unwrap()
client.close().unwrap()
server.close().unwrap()
```

```output
0
1 true
```

**A socket is not closed by going out of scope.** `close` is a call, exactly as it is in C, and a
program that drops one without closing leaks a descriptor until it exits — which is C's own rule
rather than something this binding introduced. `defer s.close()` is the idiom.

## The timeout, which a blocking call cannot do without

Otherwise "returns when the work is done" can mean never. `read_timeout` and `write_timeout` bound
it in milliseconds, and zero — where a socket starts — means no limit.

```sysl
import sysl.net.{resolve, socket, timed_out}

val here = resolve("127.0.0.1", 0).unwrap().at(0)
var server = socket(here).unwrap()

server.bind(here).unwrap()
server.listen().unwrap()

val at = server.local().unwrap()
var client = socket(at).unwrap()

client.connect(at).unwrap()

var (accepted, _) = server.accept().unwrap()

accepted.read_timeout(50).unwrap()

var into: []u8 = [0; 8]

// Nothing was ever sent, so without the timeout this call would not return at all.
print(timed_out(accepted.recv(into).unwrap_err()))

accepted.close().unwrap()
client.close().unwrap()
server.close().unwrap()
```

```output
true
```

**`timed_out` is a function rather than a case of `IoError`.** A timed-out call reports `ETIMEDOUT`,
which is a number and not one of that enum's named cases — and adding a variant to an error type
every module in the library shares, for a condition only this one can produce, is a larger change
than the question deserves.

The rest of the error half is [`sysl.fs`](/library/fs/)'s `IoError`, from the same `errno` numbers as
everywhere else. A name that will not resolve is `NotFound`, whatever the resolver's own reason was:
the `EAI_*` codes are a numbering of their own and overlap `errno`'s — `EAI_AGAIN` is 2 on Darwin,
which is `ENOENT` — so passing them through would produce an error that means something else
entirely.

## Datagrams: `UdpSocket`

`udp_socket(address)` makes a datagram socket of that address's family; `bind` gives it a port to
receive on, and then **each `send_to` is one datagram and each `recv_from` takes one whole**, with
who sent it — which is the whole of an echo server:

```sysl
import sysl.net.{ipv4, udp_socket}

val here = ipv4(127, 0, 0, 1, 0)
var server = udp_socket(here).unwrap()

server.bind(here).unwrap()

val at = server.local().unwrap()
var client = udp_socket(at).unwrap()

client.bind(here).unwrap()
client.send_to([112, 105, 110, 103], at).unwrap()

var into: []u8 = [0; 64]
val (n, from) = server.recv_from(into).unwrap()

// Back to whoever asked.
server.send_to(into[0..<n], from).unwrap()

val (m, back) = client.recv_from(into).unwrap()

print(n, m, into[0] == u8('p'), back == at, from == client.local().unwrap())

server.close().unwrap()
client.close().unwrap()
```

```output
4 4 true true true
```

**A datagram is not a stream.** One longer than the buffer it is received into keeps what fits and
loses the rest; one sent may never arrive; two may arrive in either order. UDP is for the exchanges
that can live with that — a query and its answer, a heartbeat, a sample — and the calls say nothing
more than that. An empty datagram is a datagram, and `recv_from` answers zero for it.

`connect` on a `UdpSocket` fixes where it talks to: `send` then goes there without naming it, and
`recv` takes datagrams from there and from nowhere else. A lost datagram is waited for forever
unless `read_timeout` says otherwise, which is the reason a UDP client sets one.


## An address is a sysl value

`Address` is an IP address and a port: four bytes or sixteen, a port, and — for a link-local IPv6
address — the interface it is scoped to. It compares with `==` and hashes like any other value, so a
server can key a table on whoever sent it something. `resolve` answers addresses, and **`ipv4` and
`ipv6` build one with no resolver at all**, which is what a program on a machine without one writes:

```sysl
import sysl.net.{ipv4, ipv6}

val a = ipv4(10, 0, 2, 15, 8007)

print(a, a.family(), a.octets()[3], a.with_port(7))
print(ipv6([0x2001, 0xdb8, 0, 0, 0, 0, 0, 1], 443))
print(ipv6([0xfe80, 0, 0, 0, 0, 0, 0, 1], 0, 4).text())
print(ipv6([0, 0, 0, 0, 0, 0xffff, 0x7f00, 1], 0).text())
```

```output
10.0.2.15:8007 Ipv4 15 10.0.2.15:7
[2001:db8::1]:443
fe80::1%4
::ffff:127.0.0.1
```

`text()` is the numeric form and never a name: turning an address back into a hostname is a second
lookup over the network, which is not what a program printing what it just connected to is asking
for. An IPv6 address is written as RFC 5952 says every program should — lower-case hex, no leading
zeros, the longest run of two or more zero groups as `::` — and printing one brackets it,
`[::1]:8080`, because a v6 address has colons in it and `::1:8080` cannot be read apart.

**`Family` is `Ipv4` and `Ipv6`, named by the version rather than by `AF_INET`'s number**, which is 30
on Darwin and 10 under glibc and is the hosted answer's business. The `sockaddr` layouts — Darwin's
carry a length byte glibc's do not — stay in that answer's C, where the header decides them.

## Answering the network on a target with no C library

**Everything above reaches the network through `sysl.net.sys` and through nothing else**: fourteen
hooks, each an `extern` no module of the library defines.

| hook | what it answers |
|---|---|
| `sysl_net_socket(family, kind) -> int` | a descriptor; `family` 4 or 6, `kind` `stream` (0) or `datagram` (1) |
| `sysl_net_bind(fd, at) -> int`, `sysl_net_connect(fd, to) -> int` | zero |
| `sysl_net_listen(fd, backlog) -> int` | zero |
| `sysl_net_accept(fd, peer) -> int` | the new connection's descriptor, the peer written into `peer` |
| `sysl_net_local(fd, at) -> int` | zero, the bound address written into `at` |
| `sysl_net_send(fd, from, len) -> isize`, `sysl_net_recv(fd, into, room) -> isize` | a count of bytes |
| `sysl_net_send_to(fd, from, len, to) -> isize`, `sysl_net_recv_from(fd, into, room, from) -> isize` | a count of bytes, the sender written into `from` |
| `sysl_net_shutdown(fd, how) -> int`, `sysl_net_close(fd) -> int` | zero |
| `sysl_net_option(fd, which, value) -> int` | zero; `read_timeout`, `write_timeout` (milliseconds) or `reuse_address` |
| `sysl_net_resolve(host, host_len, port, passive, out, room) -> int` | how many addresses it wrote |

**Each answers zero or more for success and the `IoError` code negated for a failure** — the contract
of every `*.sys` module (`sysl.fs.sys`, `sysl.io.sys`, `sysl.process.sys`) — and `sysl.sys.UNSUPPORTED`
(-38 on every platform, not the host's `ENOSYS`) for a call the target cannot make at all, which
reaches the caller as *not supported on this target*. A call its timeout
stopped answers `ETIMEDOUT` negated (60 on a BSD, 110 everywhere else), which is what `timed_out`
asks. **An address crosses as `sysl.net.sys.Endpoint`**: sixteen bytes in network order (an IPv4
address in the first four), a scope, a port as the number itself, and the family — 24 bytes laid out
as C lays out the same four fields, so a supplier reads it without knowing anybody's `sockaddr`.

On a hosted target the library answers them itself, under `weak` exports
([a module may supply another module's extern](/reference/ffi/)). A freestanding program answers
each hook it reaches with an `@export` of its own — a kernel's UDP echo server needs six of them:

```sysl
import sysl.net.sys.Endpoint

@export("sysl_net_recv_from")
k_recv_from(fd: int, into: *u8, room: usize, from: *Endpoint) -> isize = -110
```

**A hook the program reaches and leaves unanswered is refused when it is compiled**, all of them in
one sentence, rather than surfacing at the link as a symbol no line of the program names:

```sysl target=aarch64-freestanding
import sysl.net.{ipv4, udp_socket}

val here = ipv4(10, 0, 2, 15, 8007)

udp_socket(here) match
    Ok(s) ->
        var u = s
        val _ = u.bind(here)
    Err(_) -> ()
```

```error
this program reaches 'sysl.net', and 'aarch64-freestanding' has no operating system under it for the standard library to answer a network with, so the program answers it: define 'sysl_net_bind', 'sysl_net_socket' with '@export', each taking what its 'extern' in 'sysl.net.sys' declares and answering a descriptor, a count or zero, or the code of an 'IoError' negated
```

The question is asked only of what the program reaches, so a freestanding program that builds an
`Address` and never makes a socket is asked nothing.

## What is deliberately absent

Multicast, broadcast, unix domain sockets, non-blocking mode, and every socket option beyond the two
timeouts and `reuse_address`, which is here because a listener that has just been stopped cannot
otherwise be started again until `TIME_WAIT` runs out — a minute or two, during which the program
looks broken.

**All of them are additive**, so leaving them out costs a later release nothing, and guessing at them
now would fix a shape before anybody has used one. Each would be a hook in `sysl.net.sys` and a call
here, which is the shape everything above already has.
