---
title: sysl.process.hosted
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.process.hosted
requires: "requires { posix }"
---

## Index

[`proc_exec`](#proc_exec) [`proc_get_id`](#proc_get_id) [`proc_getpgrp`](#proc_getpgrp) [`proc_kill`](#proc_kill) [`proc_pipe`](#proc_pipe) [`proc_poll`](#proc_poll) [`proc_set_id`](#proc_set_id) [`proc_setpgid`](#proc_setpgid) [`proc_setsid`](#proc_setsid) [`proc_spawn`](#proc_spawn) [`proc_tcgetpgrp`](#proc_tcgetpgrp) [`proc_tcsetpgrp`](#proc_tcsetpgrp) [`proc_temp_path`](#proc_temp_path) [`proc_wait`](#proc_wait)

## Functions

### `proc_exec`

```sysl
proc_exec(path: *u8, path_len: usize, argv: *Text, argc: usize, envp: *Text, envc: usize, inherit_env: int) -> int
```

### `proc_get_id`

```sysl
proc_get_id(which: int) -> i64
```

### `proc_getpgrp`

```sysl
proc_getpgrp() -> i64
```

### `proc_kill`

```sysl
proc_kill(pid: i64, signal: int) -> int
```

### `proc_pipe`

```sysl
proc_pipe(read_end: *int, write_end: *int) -> int
```

### `proc_poll`

```sysl
proc_poll(pid: i64, how: *int, value: *int) -> int
```

### `proc_set_id`

```sysl
proc_set_id(which: int, id: u32) -> int
```

### `proc_setpgid`

```sysl
proc_setpgid(pgid: i64) -> int
```

### `proc_setsid`

```sysl
proc_setsid() -> i64
```

### `proc_spawn`

```sysl
proc_spawn(path: *u8, path_len: usize, argv: *Text, argc: usize, envp: *Text, envc: usize, inherit_env: int, dir: *u8, dir_len: usize, stdin_fd: int, stdout_fd: int, stderr_fd: int, started: *i64, pgid: i64, foreground: int) -> i64
```

### `proc_tcgetpgrp`

```sysl
proc_tcgetpgrp() -> i64
```

### `proc_tcsetpgrp`

```sysl
proc_tcsetpgrp(pgid: i64) -> int
```

### `proc_temp_path`

```sysl
proc_temp_path(into: *u8, room: usize) -> isize
```

### `proc_wait`

```sysl
proc_wait(pid: i64, started: i64, timeout_ms: i64, how: *int, value: *int) -> int
```
