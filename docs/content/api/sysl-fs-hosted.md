---
title: sysl.fs.hosted
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.fs.hosted
requires: "requires { libc }, requires { os }"
---

## Index

[`supply_access`](#supply_access) [`supply_chdir`](#supply_chdir) [`supply_chmod`](#supply_chmod) [`supply_chown`](#supply_chown) [`supply_close`](#supply_close) [`supply_closedir`](#supply_closedir) [`supply_fstat`](#supply_fstat) [`supply_ftruncate`](#supply_ftruncate) [`supply_getcwd`](#supply_getcwd) [`supply_link`](#supply_link) [`supply_mkdir`](#supply_mkdir) [`supply_open`](#supply_open) [`supply_opendir`](#supply_opendir) [`supply_read`](#supply_read) [`supply_readdir`](#supply_readdir) [`supply_readlink`](#supply_readlink) [`supply_realpath`](#supply_realpath) [`supply_rename`](#supply_rename) [`supply_rmdir`](#supply_rmdir) [`supply_seek`](#supply_seek) [`supply_stat`](#supply_stat) [`supply_symlink`](#supply_symlink) [`supply_temp_dir`](#supply_temp_dir) [`supply_truncate`](#supply_truncate) [`supply_unlink`](#supply_unlink) [`supply_write`](#supply_write)

## Functions

### `supply_access`

```sysl
supply_access(path: *u8, len: usize, how: int) -> int
```

### `supply_chdir`

```sysl
supply_chdir(path: *u8, len: usize) -> int
```

### `supply_chmod`

```sysl
supply_chmod(path: *u8, len: usize, mode: u32) -> int
```

### `supply_chown`

```sysl
supply_chown(path: *u8, len: usize, owner: u32, group: u32) -> int
```

### `supply_close`

```sysl
supply_close(fd: int) -> int
```

### `supply_closedir`

```sysl
supply_closedir(dir: usize) -> int
```

### `supply_fstat`

```sysl
supply_fstat(fd: int, out: *Stat) -> int
```

### `supply_ftruncate`

```sysl
supply_ftruncate(fd: int, length: long) -> int
```

### `supply_getcwd`

```sysl
supply_getcwd(into: *u8, room: usize, got: *usize) -> int
```

### `supply_link`

```sysl
supply_link(existing: *u8, existing_len: usize, fresh: *u8, fresh_len: usize) -> int
```

### `supply_mkdir`

```sysl
supply_mkdir(path: *u8, len: usize, mode: u32) -> int
```

### `supply_open`

```sysl
supply_open(path: *u8, len: usize, flags: u32, mode: u32, fd: *int) -> int
```

### `supply_opendir`

```sysl
supply_opendir(path: *u8, len: usize, dir: *usize) -> int
```

### `supply_read`

```sysl
supply_read(fd: int, into: *u8, room: usize, got: *usize) -> int
```

### `supply_readdir`

```sysl
supply_readdir(dir: usize, into: *u8, room: usize, got: *usize) -> int
```

### `supply_readlink`

```sysl
supply_readlink(path: *u8, len: usize, into: *u8, room: usize, got: *usize) -> int
```

### `supply_realpath`

```sysl
supply_realpath(path: *u8, len: usize, into: *u8, room: usize, got: *usize) -> int
```

### `supply_rename`

```sysl
supply_rename(from: *u8, from_len: usize, to: *u8, to_len: usize) -> int
```

### `supply_rmdir`

```sysl
supply_rmdir(path: *u8, len: usize) -> int
```

### `supply_seek`

```sysl
supply_seek(fd: int, offset: long, whence: int, at: *long) -> int
```

### `supply_stat`

```sysl
supply_stat(path: *u8, len: usize, follow: int, out: *Stat) -> int
```

### `supply_symlink`

```sysl
supply_symlink(target: *u8, target_len: usize, link: *u8, link_len: usize) -> int
```

### `supply_temp_dir`

```sysl
supply_temp_dir(prefix: *u8, len: usize, into: *u8, room: usize, got: *usize) -> int
```

### `supply_truncate`

```sysl
supply_truncate(path: *u8, len: usize, length: long) -> int
```

### `supply_unlink`

```sysl
supply_unlink(path: *u8, len: usize) -> int
```

### `supply_write`

```sysl
supply_write(fd: int, from: *u8, len: usize, put: *usize) -> int
```
