---
title: sysl.process.sys
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.process.sys
---

## Index

[`ended_exited`](#ended_exited) [`ended_signalled`](#ended_signalled) [`ended_timed_out`](#ended_timed_out) [`group_inherit`](#group_inherit) [`group_new`](#group_new) [`id_group`](#id_group) [`id_real_group`](#id_real_group) [`id_real_user`](#id_real_user) [`id_user`](#id_user) [`Text`](#text)

## Constants

### `ended_exited`

```sysl
const ended_exited: int = 0
```

What `wait` writes into `how` for a child that exited: `value` is its exit code.

### `ended_signalled`

```sysl
const ended_signalled: int = 1
```

What `wait` writes into `how` for a child that a signal ended: `value` is the signal's number.

**A child a kernel ended for a fault is reported this way too**, with the number POSIX raises for
that fault -- 11 (`SIGSEGV`) for an address it could not touch, 4 (`SIGILL`) for an instruction it
could not run, 7 (`SIGBUS`), 8 (`SIGFPE`) -- so that `Status.Signalled(11)` means what it means on
every POSIX host.

### `ended_timed_out`

```sysl
const ended_timed_out: int = 2
```

What `wait` writes into `how` for a child it stopped because its deadline passed.

### `group_inherit`

```sysl
const group_inherit: i64 = -1
```

What `spawn` is handed as `pgid` to leave the child in this program's process group.

### `group_new`

```sysl
const group_new: i64 = 0
```

What `spawn` is handed as `pgid` to make the child the leader of a new group, numbered by its pid.

### `id_group`

```sysl
const id_group: int = 1
```

The group the program acts as: the effective group.

### `id_real_group`

```sysl
const id_real_group: int = 3
```

The group of the user who started the program: the real group.

### `id_real_user`

```sysl
const id_real_user: int = 2
```

The user who started the program -- the **real** user. It differs from `id_user` only in a
program that changed who it acts as, a set-user-ID one or one that called `set_id`; on a target
that keeps one user per process the two are the same number.

### `id_user`

```sysl
const id_user: int = 0
```

What `get_id` and `set_id` are asked about: the user the program acts as -- the **effective** user,
the one a filesystem checks a permission against and makes a new file's owner.

## Types

### `Text`

```sysl
struct Text
    ptr: *u8
    len: usize
```

A run of bytes, lent: where it starts and how many there are. Laid out as C lays out a pointer
followed by a `size_t`, which is what a supplier written in C reads it as.
