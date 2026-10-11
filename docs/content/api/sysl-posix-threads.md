---
title: sysl.posix.threads
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.posix.threads
summary: "`sysl.threads` under the name it had when its threads were pthreads, kept for the programs that import it."
requires: "requires { libc }, requires { posix }"
---

**Write `sysl.threads`**: it is the same API, it asks only for `os`, and it reaches a
kernel's threads through the hooks of `sysl.threads.sys` where this name asks for POSIX and a C
library the threads no longer need.

Every name here is the one in `sysl.threads` -- the types as aliases, the functions forwarding --
so a value made through either module is the other's.

## Index

[`channel`](#channel) [`current`](#current) [`spawn`](#spawn) [`spawn_on`](#spawn_on) [`yield_now`](#yield_now) [`Channel`](#channel-1) [`Condvar`](#condvar) [`Lock`](#lock) [`Mutex`](#mutex) [`Thread`](#thread)

## Functions

### `channel`

```sysl
channel[T](slots: []T) -> Channel[T]
```

`sysl.threads.channel`.

### `current`

```sysl
current() -> Thread
```

`sysl.threads.current`.

### `spawn`

```sysl
spawn[T](body: *extern(*T) -> unit, arg: *T) -> Option[Thread]
```

`sysl.threads.spawn`, with the stack it gives a thread where nothing else is asked for.

### `spawn_on`

```sysl
spawn_on[T](stack: []u8, body: *extern(*T) -> unit, arg: *T) -> Option[Thread]
```

`sysl.threads.spawn_on`.

### `yield_now`

```sysl
yield_now() -> bool
```

`sysl.threads.yield_now`.

## Aliases

### `Channel`

```sysl
type Channel[T] = th.Channel[T]
```

### `Condvar`

```sysl
type Condvar = th.Condvar
```

### `Lock`

```sysl
type Lock = th.Lock
```

### `Mutex`

```sysl
type Mutex[T] = th.Mutex[T]
```

### `Thread`

```sysl
type Thread = th.Thread
```
