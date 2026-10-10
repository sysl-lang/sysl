---
title: sysl.fs.sys
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.fs.sys
requires: "requires { os }"
---

## Index

[`access_exists`](#access_exists) [`access_read`](#access_read) [`access_write`](#access_write) [`open_append`](#open_append) [`open_create`](#open_create) [`open_read`](#open_read) [`open_truncate`](#open_truncate) [`open_write`](#open_write) [`seek_current`](#seek_current) [`seek_end`](#seek_end) [`seek_start`](#seek_start) [`Stat`](#stat)

## Constants

### `access_exists`

```sysl
const access_exists: int = 0
```

What `sysl_fs_access` is asked: whether anything is at the path at all, or whether the program may
read it or write it. They are POSIX's numbers for the same questions.

### `access_read`

```sysl
const access_read: int = 4
```

### `access_write`

```sysl
const access_write: int = 2
```

### `open_append`

```sysl
const open_append: u32 = 16
```

### `open_create`

```sysl
const open_create: u32 = 4
```

### `open_read`

```sysl
const open_read: u32 = 1
```

The bits of `sysl_fs_open`'s `flags`. They are this interface's own numbers rather than any
platform's `O_*`, which differ between C libraries; a supplier translates them once.

`open_read` and `open_write` may both be set, for a file opened for update. `open_create` makes the
file where it is missing, `open_truncate` empties it where it is not, and `open_append` puts every
write at the end of whatever the file holds at that moment.

### `open_truncate`

```sysl
const open_truncate: u32 = 8
```

### `open_write`

```sysl
const open_write: u32 = 2
```

### `seek_current`

```sysl
const seek_current: int = 1
```

### `seek_end`

```sysl
const seek_end: int = 2
```

### `seek_start`

```sysl
const seek_start: int = 0
```

Where `sysl_fs_seek` counts from: the start of the file, the current position, or the end.

## Types

### `Stat`

```sysl
struct Stat
    size: long
    mode: long
    links: long
    uid: long
    gid: long
    inode: long
    device: long
    modified_s: long
    modified_ns: long
    accessed_s: long
    accessed_ns: long
    changed_s: long
    changed_ns: long
```

What `sysl_fs_stat` and `sysl_fs_fstat` fill in about one filesystem entry: thirteen 64-bit
numbers, in this order.

`mode` carries POSIX's encoding -- the file type in the top bits (`0o170000` masks it: `0o100000`
a regular file, `0o040000` a directory, `0o120000` a symbolic link) and the twelve permission bits
below -- since that is what `Meta` reads. A supplier with nothing to say about a field leaves it
zero. Times are seconds and nanoseconds since the Unix epoch, held apart.
