---
title: The net module
summary: "`sysl.posix.net` — blocking TCP and the names it resolves: `socket`, `bind`, `listen`, `accept`, `connect`, `send`, `recv`, `shutdown`, `close`, and why it mirrors POSIX rather than inventing a stream type."
weight: 76
---

**Every declaration in `sysl.posix.net`, with its signature:** [the generated API page](/api/sysl-posix-net/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.posix.net` is blocking TCP: a socket, an address to give it, and the four calls that move
bytes. It requires `posix`.

```sysl
import sysl.posix.net.{resolve, socket}

val addrs = resolve("127.0.0.1", 8080).unwrap()

print(addrs.len() > 0)
print(addrs.at(0).text(), addrs.at(0).port(), addrs.at(0).family())
print(addrs.at(0))
```

```output
true
127.0.0.1 8080 Ipv4
127.0.0.1:8080
```

## Why it is `sysl.posix.net` and not `sysl.net`

The library has two conventions, and the rule that separates them is visible in one pair:
[`sysl.term`](/library/term/) is the portable surface sysl invented over a terminal, and
`sysl.posix.tty` is `termios` presented as `termios`. **Everything under `sysl.posix` keeps the
underlying API's own vocabulary.**

So this is `socket`, `bind`, `listen`, `accept`, `connect`, `send`, `recv`, `shutdown` and `close`,
with those meanings and in that order, and it is deliberately **not** a `TcpStream`.

**A portable `sysl.net` comes later, over this, and that order is the point rather than a
compromise.** POSIX made every decision this module needs forty years ago, so mirroring it cannot
guess wrong — and a module that cannot guess wrong is one that can be frozen. What a *portable*
address is, what a portable error is, how a timeout is spelled, whether a listener is a type or a
function: those are guesses, and they want real consumers before anything is fixed. Adding a module
after 0.1.0 is free; only removing one is forbidden. So the safe half goes before the freeze and the
risky half after it.

## Blocking, and only blocking

Every call here returns when its work is done. There is no event loop, no non-blocking mode and no
readiness notification.

**That is the line Rust draws too**, and for the same reason: `std::net` is blocking and every async
runtime, tokio included, is a package outside the standard library. `sysl-lang/libuv` is where the
event-loop story lives, and the two compose — a program that wants a loop uses the package, and one
that wants a straight line uses this. So sysl's standard library having no asynchronous tier stops
being an omission and becomes a decision with a precedent behind it.

## A client

Resolve, make a socket of the address's family, connect.

```sysl
import sysl.posix.net.{resolve, socket, Socket}

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
import sysl.posix.net.{resolve, socket, Shutdown}

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
import sysl.posix.net.{resolve, socket, timed_out}

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

**`timed_out` is a function rather than a case of `IoError`.** A timed-out blocking call reports
`EAGAIN`, which is a number and not one of that enum's named cases — and adding a variant to an error
type every module in the library shares, for a condition only this one can produce, is a larger
change than the question deserves.

The rest of the error half is [`sysl.fs`](/library/fs/)'s `IoError`, from the same `errno` numbers as
everywhere else. A name that will not resolve is `NotFound`, whatever the resolver's own reason was:
the `EAI_*` codes are a numbering of their own and overlap `errno`'s — `EAI_AGAIN` is 2 on Darwin,
which is `ENOENT` — so passing them through would produce an error that means something else
entirely.

## An address carries the platform's bytes

`Address` holds a `sockaddr` and its length and sysl never looks inside it. A `sockaddr_in` and a
`sockaddr_in6` are different layouts, and Darwin's carry a length byte glibc's do not, so a struct
written in sysl would be a transcription of a layout — [the thing that compiles everywhere and is
wrong somewhere](/reference/ffi/#a-library-may-carry-c). What crosses is the bytes; everything a
program wants to *know* about an address it asks for.

**`Family` is `Ipv4` and `Ipv6`, named by the version rather than by `AF_INET`'s number** — which is
what a program must not be handed, since `AF_INET6` is 30 on Darwin and 10 under glibc. `text()` is
the numeric form and never a name: turning an address back into a hostname is a second lookup over
the network, which is not what a program printing what it just connected to is asking for, and is a
thing an attacker controls the answer to.

Printing one brackets a v6 address — `[::1]:8080` — because a v6 address has colons in it and
`::1:8080` cannot be read apart. It is the form the URL syntax settled on.

## What is deliberately absent

UDP, multicast, unix domain sockets, non-blocking mode, and every socket option beyond the two
timeouts and `reuse_address`, which is here because a listener that has just been stopped cannot
otherwise be started again until `TIME_WAIT` runs out — a minute or two, during which the program
looks broken.

**All of them are additive**, so leaving them out costs a later release nothing, and guessing at them
now would fix a shape before anybody has used one. That is the same reasoning that puts a portable
`sysl.net` after the freeze rather than before it.
