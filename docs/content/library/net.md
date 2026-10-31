---
title: The net module
summary: "`sysl.net` — TCP, UDP and Unix domain sockets and the names they resolve: `socket`, `bind`, `listen`, `accept`, `connect`, `send`, `recv`, `send_to`, `recv_from`, `shutdown`, `close`; `unix_listen` and `unix_connect`; `non_blocking` and `would_block`; addresses as sysl values; and the hooks a target without a C library answers."
weight: 76
---

**Every declaration in `sysl.net`, with its signature:** [the generated API page](/api/sysl-net/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.net` is sockets: a TCP stream, a UDP socket, a Unix domain stream, an address to give each,
and the calls that move bytes. It requires `os`.

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

## Blocking, unless asked

Every call here returns when its work is done, unless the socket was put in non-blocking mode (the
section on polling, below). There is no event loop and no readiness
notification.

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

## Polling: `non_blocking` and `would_block`

A program that already has a loop of its own — a frame, a tick — cannot sit in `accept` waiting for
a client that may never come. **`non_blocking()` puts a socket in non-blocking mode**, and then an
`accept`, `recv` or `send` that would have had to wait answers an error `would_block` recognises
instead of waiting, as Rust's `set_nonblocking` does. `non_blocking(false)` takes it out again. It is
on `Socket`, `UdpSocket` and `UnixListener`.

```sysl
import sysl.net.{ipv4, socket, timed_out, would_block}

val here = ipv4(127, 0, 0, 1, 0)
var server = socket(here).unwrap()

server.bind(here).unwrap()
server.listen().unwrap()
server.non_blocking().unwrap()

// Nobody has connected yet: once a frame, this is all the loop learns.
val e = server.accept().unwrap_err()

print(would_block(e), timed_out(e))

val at = server.local().unwrap()
var client = socket(at).unwrap()

client.connect(at).unwrap()

// The connection may not be queued yet: ask again until it is.
var got = server.accept()

while got.is_err() && would_block(got.unwrap_err())
    got = server.accept()

var (accepted, _) = got.unwrap()

accepted.close().unwrap()
client.close().unwrap()
server.close().unwrap()
```

```output
true false
```

**A connection `accept` answers is blocking, whatever its listener is.** A BSD hands an accepted
socket its listener's mode and Linux does not; here the two agree, so a program that polls its
listener and then serves the request in a straight line gets the same connection on a Mac as under
Linux, and one that wants the connection non-blocking says so.

**`would_block` and `timed_out` are different answers.** Both come from the kernel's `EAGAIN`, and
the socket's mode decides which: a blocking socket whose timeout ran out is `timed_out`, and a
non-blocking one with nothing ready is `would_block` — `EAGAIN`'s own code, 35 on a BSD and 11
elsewhere.

## A send to a peer that has gone is an error, not `SIGPIPE`

A `send`, `send_all` or `send_to` to a peer that has closed answers an `IoError`, which
**`peer_closed(e)`** recognises (`EPIPE`, 32 everywhere, or `ECONNRESET`, 54 on a BSD and 104
elsewhere). It never raises `SIGPIPE`, whose default action ends the process silently with status
141. This holds for Unix and TCP sockets, connecting and accepted, blocking and non-blocking. A
stream's first send to a closed TCP peer can still succeed, because the peer's reset has not arrived;
a later one is refused. Unix sockets refuse the first.

It is done per socket on Darwin (`SO_NOSIGPIPE`, set on every socket made or accepted) and per call
elsewhere (`MSG_NOSIGNAL`), so **the process's signal disposition is untouched**: a program that
installs its own `SIGPIPE` handler with `sysl.signal` still hears it for a write to a pipe or file,
and does not hear it for a socket.

## Buffer sizes

`set_send_buffer(bytes)` and `set_recv_buffer(bytes)` ask the kernel for a socket's buffers, and
`send_buffer()` and `recv_buffer()` read what it granted, on `Socket` and `UdpSocket`. **The kernel
may round the size** — Linux doubles it and both clamp to a system limit — so compare the answer
with `>=`, not `==`. A size that is not positive is refused as `EINVAL` (22) and the buffer is left
as it was. macOS gives a Unix stream socket 8 KB, so a 3 MB response takes 384 turns of a 8 KB-at-a-time
loop; with 4 MB buffers on both ends the same transfer takes 48 turns, limited then by the 64 KB the
reader takes per turn. Set the receiver's buffer too: the sender's buffer alone does not bound the
turns.

## Unix domain sockets

A Unix domain socket is a stream between two processes on one machine, addressed by a path in the
filesystem. **`unix(path)` is the address, `unix_listen` makes the socket file and listens on it, and
`unix_connect` answers a `Socket`** — the type a TCP connection is — so the connection is read and
written exactly as a TCP one is. `UnixListener.accept` answers the connection alone: a Unix client is
ordinarily unnamed, so there is no peer address worth handing back.

```sysl
import sysl.fs.{make_temp_dir, metadata, remove_dir_all, remove_file, set_permissions}
import sysl.net.{unix, unix_connect, unix_listen}
import sysl.path.join

val dir = make_temp_dir("net-doc-").unwrap()
val at = unix(join(dir, "control.sock")).unwrap()
var server = unix_listen(at).unwrap()

// Only this user may connect: who may is the socket file's permission bits.
set_permissions(at.path(), 0o600).unwrap()
print(f"${metadata(at.path()).unwrap().permissions()}%o")

var client = unix_connect(at).unwrap()
var accepted = server.accept().unwrap()

client.send_all("ping".bytes).unwrap()

var into: []u8 = [0; 8]

print(accepted.recv(into).unwrap())

accepted.close().unwrap()
client.close().unwrap()
server.close().unwrap()

// The socket file outlives its listener; removing it is the program's.
remove_file(at.path()).unwrap()
remove_dir_all(dir).unwrap()
```

```output
600
4
```

**The socket file is not removed by anything in the module.** `unix_listen` refuses a path that
already holds something — `EADDRINUSE`'s code, 48 on a BSD and 98 under Linux — rather than replacing
it, because what is there may be another program's live socket or not a socket at all; a server that
owns its path removes it with `sysl.fs.remove_file` first, and again after closing. Connecting to a
path with nothing there is `NotFound`.

**A path the platform's address cannot carry is refused by `unix` itself**, not at the `bind`:
`sockaddr_un`'s `sun_path` is 104 bytes on Darwin and 108 under glibc, one of them the terminator, so
`max_unix_path` is 103 or 107 and a longer path is `ENAMETOOLONG`'s code:

```sysl
import sysl.net.{max_unix_path, unix}

var long = ""

for _ in 0..<max_unix_path + 1 do long += "a"

print(unix(long).is_err(), unix(long[0..<max_unix_path]).is_ok(), unix("").is_err())
```

```output
true true true
```

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

**Everything above reaches the network through `sysl.net.sys` and through nothing else**: sixteen
hooks, each an `extern` no module of the library defines.

| hook | what it answers |
|---|---|
| `sysl_net_socket(family, kind) -> int` | a descriptor; `family` 4, 6 or `unix_domain` (1), `kind` `stream` (0) or `datagram` (1) |
| `sysl_net_bind(fd, at) -> int`, `sysl_net_connect(fd, to) -> int` | zero |
| `sysl_net_bind_path(fd, path, path_len) -> int`, `sysl_net_connect_path(fd, path, path_len) -> int` | zero; a Unix domain socket's filesystem path |
| `sysl_net_listen(fd, backlog) -> int` | zero |
| `sysl_net_accept(fd, peer) -> int` | the new connection's descriptor, the peer written into `peer` |
| `sysl_net_local(fd, at) -> int` | zero, the bound address written into `at` |
| `sysl_net_send(fd, from, len) -> isize`, `sysl_net_recv(fd, into, room) -> isize` | a count of bytes |
| `sysl_net_send_to(fd, from, len, to) -> isize`, `sysl_net_recv_from(fd, into, room, from) -> isize` | a count of bytes, the sender written into `from` |
| `sysl_net_shutdown(fd, how) -> int`, `sysl_net_close(fd) -> int` | zero |
| `sysl_net_option(fd, which, value) -> int` | zero; `read_timeout`, `write_timeout` (milliseconds), `reuse_address`, `non_blocking`, `send_buffer` or `recv_buffer` (bytes, positive); `send_buffer_size` and `recv_buffer_size` ignore `value` and answer the size in bytes |
| `sysl_net_resolve(host, host_len, port, passive, out, room) -> int` | how many addresses it wrote |

**Each answers zero or more for success and the `IoError` code negated for a failure** — the contract
of every `*.sys` module (`sysl.fs.sys`, `sysl.io.sys`, `sysl.process.sys`) — and `sysl.sys.UNSUPPORTED`
(-38 on every platform, not the host's `ENOSYS`) for a call the target cannot make at all, which
reaches the caller as *not supported on this target*. A call its timeout
stopped answers `ETIMEDOUT` negated (60 on a BSD, 110 everywhere else), which is what `timed_out`
asks, and one on a non-blocking socket that would have had to wait answers `EAGAIN` negated (35 on a
BSD, 11 everywhere else), which is what `would_block` asks. **An address crosses as `sysl.net.sys.Endpoint`**: sixteen bytes in network order (an IPv4
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

Multicast, broadcast, Unix datagram sockets, Linux's abstract namespace, and every socket option
beyond the two timeouts, the two buffer sizes, `non_blocking` and `reuse_address`, which is here because a listener that has just been stopped cannot
otherwise be started again until `TIME_WAIT` runs out — a minute or two, during which the program
looks broken.

**All of them are additive**, so leaving them out costs a later release nothing, and guessing at them
now would fix a shape before anybody has used one. Each would be a hook in `sysl.net.sys` and a call
here, which is the shape everything above already has.
