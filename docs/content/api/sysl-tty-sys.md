---
title: sysl.tty.sys
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.tty.sys
---

## Index

[`Mode`](#mode)

## Types

### `Mode`

```sysl
struct Mode
    echo: bool
    canonical: bool
    signals: bool
    output: bool
    crlf: bool
    min: u8
    time: u8
```

The settings of a terminal a program changes, each named for what it does rather than for the
POSIX flag that does it.

- `echo` -- typed characters are shown as they arrive (`ECHO`).
- `canonical` -- input is released a whole line at a time, with the line editing the terminal
  does itself (`ICANON`); off, every byte is released as it arrives.
- `signals` -- Ctrl-C and its kin become signals rather than bytes (`ISIG`).
- `output` -- what is written is processed on its way out at all (`OPOST`).
- `crlf` -- a `\n` written goes out as `\r\n` (`ONLCR`), which only means something with `output`.
- `min` and `time` -- with `canonical` off, how many bytes a read waits for and how many tenths of
  a second it waits (`VMIN`, `VTIME`).
