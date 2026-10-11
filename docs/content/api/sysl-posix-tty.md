---
title: sysl.posix.tty
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.posix.tty
summary: "Whether escapes should be written at all."
requires: "requires { libc }, requires { posix }"
---

`sysl.term` names the sequences and deliberately answers nothing about them, because the answer
needs the process and its environment and a capability requirement is module-wide -- one function
asking for `posix` beside those constants would have taken every one of them away from the
`@no_alloc` program that only wanted to name a colour, which is the case that module is arranged
for. So the question lives here instead, and the split is visible in the import: a hosted program
writes both, and a freestanding one writes neither this line nor this dependency.

**It is under `sysl.posix` rather than beside `sysl.term` because `isatty(3)` and `termios` are
what it is.** Everything in that namespace requires the same one capability, which is what makes
the path worth reading: a module there is a module a freestanding target does not get.

## What the answer is made of

Three questions, and a program that skips any of them gets it wrong in a way somebody eventually
files a bug about:

- **Is it a terminal at all?** Output redirected to a file or a pipe wants no escapes, and this is
  the one that matters most: a build log full of `ESC [ 3 1 m` is what not asking looks like.
- **Has the user said no?** `NO_COLOR` set to anything non-empty means no, whatever the terminal
  is. The convention is deliberately about the variable being *there* rather than about its value,
  so a program reading it as a boolean and looking for `"1"` has misread it.
- **Can the terminal do it?** `TERM=dumb` is a terminal that cannot, and Emacs' shell buffer is the
  one everybody meets.

`color()` asks all three about standard output, which is the ordinary case and the one worth
making a single word. The other three names exist because the ordinary case is not the only one: a
program colouring its diagnostics asks about standard error, and one drawing a progress bar wants
`is_tty` on its own, since a bar is worth suppressing on a pipe whether or not colour is.

**Ask once and keep the answer.** Every one of these is a system call or an environment scan, and
the answer cannot change under a running program in any way that matters:

    val paint = color()

    print(f"${if paint then red else ""}error${if paint then reset else ""}: ${msg}")

## Index

[`tty_sink`](#tty_sink) [`color`](#color) [`color_err`](#color_err) [`color_on`](#color_on) [`color_wanted`](#color_wanted) [`cooked`](#cooked) [`flush`](#flush) [`hidden`](#hidden) [`is_tty`](#is_tty) [`raw`](#raw) [`tty_writer`](#tty_writer) [`TtyWriter`](#ttywriter) [Fallible for TtyWriter](#fallible-for-ttywriter) [Writer for TtyWriter](#writer-for-ttywriter)

## Values

### `tty_sink`

```sysl
val tty_sink: *TtyWriter = ptr_cast(0usize)
```

## Functions

### `color`

```sysl
color() -> bool
```

Standard output, which is where a program that prints its results writes them.

### `color_err`

```sysl
color_err() -> bool
```

Standard error, which is where a program that prints diagnostics writes those -- and the two
genuinely differ, since `prog > file` leaves one a file and the other a terminal.

### `color_on`

```sysl
color_on(fd: int) -> bool
```

All three questions, about whichever descriptor is asked about.

### `color_wanted`

```sysl
color_wanted() -> bool
```

The environment's half of the answer: whether colour is wanted here at all, said without reference
to any descriptor.

It is public rather than folded into `color_on` because the two halves are genuinely separate
questions and a program can want this one alone -- a `--color=always` flag overrides the
descriptor and not the user's `NO_COLOR`, which is exactly this function. It is also the half that
can be asked without a terminal, which is what makes it answerable in a test.

### `cooked`

```sysl
cooked()
```

Put back what `raw` or `hidden` changed, and only what they changed -- `sysl.tty.cooked`.

### `flush`

```sysl
flush()
```

Push out everything the C library is holding, on every stream.

**A caller needs this for its own prompt.** The editor's sink below flushes what the *editor*
writes; a prompt printed by the program before handing over is the program's own output, and
nothing has written a newline after it. `prints("ogol> ")` then `flush()` is the whole idiom.

### `hidden`

```sysl
hidden() -> bool
```

Put the terminal into password mode: whole lines, nothing echoed -- `sysl.tty.hidden`.

### `is_tty`

```sysl
is_tty(fd: int) -> bool
```

Whether a descriptor is attached to a terminal.

This is the whole of what C answers, and it is separate from the colour question because a program
can want it on its own: a progress bar, a spinner, a prompt, and a full-screen redraw are all worth
suppressing when output is a pipe, and none of them is about colour.

### `raw`

```sysl
raw() -> bool
```

Put the terminal into cbreak mode: keystrokes arrive as they are typed and nothing is echoed --
`sysl.tty.raw`. **Answers whether it worked**; it fails where there is no terminal to change.

    if raw()
        var ed = editor(&input, &output)
        for line in ed do ...
        cooked()

### `tty_writer`

```sysl
tty_writer() -> *Writer
```

## Types

### `TtyWriter`

```sysl
struct TtyWriter
end TtyWriter
```

Standard output as a sink that **flushes what it is given**, which is what an editor must write
through on a hosted terminal.

It carries no state for the reason `Stdout` does not: a destination fixed at compile time keeps
none, so the pointer the sink is built on addresses nothing and `write` never looks at `self`.

A program on a board passes its own port's writer and needs none of this. That asymmetry is real
and is the only one in the whole facility: the board has no buffer to defeat.

## Implementations

### Fallible for TtyWriter

```sysl
impl Fallible for TtyWriter
```

### Writer for TtyWriter

```sysl
impl Writer for TtyWriter
```
