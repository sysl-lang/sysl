---
title: sysl.tty
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.tty
summary: "A terminal's mode: line by line or a keystroke at a time, echoing or not, and putting back what was there."
requires: "requires { os }"
---

**This is the half of terminal handling a kernel can answer**, which is why it is not in
`sysl.posix.tty`: every call goes through the two hooks of `sysl.tty.sys`, answered over `termios`
on a hosted POSIX target and by the program -- a kernel, a board's console -- with an `@export` on a
freestanding one. `sysl.posix.tty`'s `raw` and `cooked` are these same two functions.

## Three states, and the one the terminal was in

- **`raw`** -- keystrokes arrive as they are typed and nothing is echoed: what a line editor wants.
- **`hidden`** -- whole lines with the terminal's own editing, and nothing echoed: what a password
  prompt wants, and what raw mode is wrong for, since raw also ends the line editing.
- **`cooked`** -- whatever the terminal was in before the first of the other two.

The first `raw` or `hidden` reads the mode it found, and every state after it is that mode with a
few settings changed -- so moving from one to the other and back is never a drift, and `cooked`
restores exactly what was found rather than a set of settings somebody chose.

    if hidden()
        prints("password: ")
        val typed = read_line()
        cooked()
        print()

**Signals go in both, and that is not the obvious choice.** A program interrupted with the terminal
changed leaves a shell that shows nothing as it is typed, which the user has to fix by typing
`stty sane` blind. With `signals` off Ctrl-C arrives as byte 3 instead, so every way out of the
program is an ordinary exit -- and on a host the first change registers an exit hook that puts the
terminal back, so even a program that never calls `cooked` leaves it as it found it. `getpass(3)`
turns signals off for the same reason. What it costs is real: a wedged program can no longer be
interrupted from its own terminal.

**On a freestanding target nothing is put back at exit by the library**, there being no C library
to register a hook with: a program calls `cooked` on its way out, and a kernel that keeps a
terminal's mode past a process -- as a POSIX one does -- keeps whatever it was left in.

## Index

[`cooked`](#cooked) [`hidden`](#hidden) [`hidden_of`](#hidden_of) [`mode`](#mode) [`raw`](#raw) [`raw_of`](#raw_of) [`set_mode`](#set_mode) [`Mode`](#mode-1)

## Functions

### `cooked`

```sysl
cooked()
```

Put back the mode the terminal was in before the first `raw` or `hidden` -- exactly what was
found, and only what those changed. Doing nothing when neither was called, or when it has already
been put back, is the same rule.

### `hidden`

```sysl
hidden() -> bool
```

Put the terminal into password mode: whole lines, edited as the terminal edits them, and nothing
echoed. **Answers whether it worked**, as `raw` does; a prompt for a secret should refuse to read
one when it did not, rather than show what is typed.

The newline that ends the line is not echoed either, so a program prints one of its own after
reading, as `getpass(3)` does.

### `hidden_of`

```sysl
hidden_of(m: Mode) -> Mode
```

Password mode over `m`: line editing kept, echo and signals off, everything else as it was.

### `mode`

```sysl
mode() -> Option[Mode]
```

The mode the terminal on standard input is in, or `None` where there is none -- input from a file
or a pipe, or a target that cannot say.

### `raw`

```sysl
raw() -> bool
```

Put the terminal into raw mode: keystrokes arrive as they are typed and nothing is echoed.

**Answers whether it worked**, and a caller should look. It fails where there is no terminal to
change -- input redirected from a file or a pipe -- which is not an error so much as a different
situation, in which `sysl.io.console_lines` is the facility that fits.

    if raw()
        var ed = editor(&input, &output)
        for line in ed do ...
        cooked()

Calling it again while in force does nothing more.

### `raw_of`

```sysl
raw_of(m: Mode) -> Mode
```

Raw mode over `m`: no line editing, no echo, no signals, a read waiting for one byte and no
longer -- and output still processed, `\n` going out as `\r\n`, so every `print` in the program
goes on working.

**The output settings are asserted rather than kept**, because a terminal does not always still
have them -- a program killed before it could restore, an earlier `stty` -- and a mode this sets
should not depend on the mode it found. Without them a hosted program's output walks diagonally
down the screen.

### `set_mode`

```sysl
set_mode(m: Mode) -> bool
```

Puts the terminal on standard input into `m`, and answers whether it did. Only the settings
`Mode` names change; everything else the terminal has stays as it was.

## Aliases

### `Mode`

```sysl
type Mode = tsys.Mode
```

The settings of a terminal this module reads and changes -- `sysl.tty.sys.Mode`, named here.
