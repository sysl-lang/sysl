---
title: sysl.term
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.term
summary: "What a terminal understands: the escape sequences that colour text, emphasise it, and move the cursor about."
requires: "no alloc"
---

**Every one of these is a `const string`, and that is the whole design.** A string literal is
immortal -- it lives in the program's own image with no owner and no reference count (`04`) -- so
naming forty of them costs nothing at run time and nothing in storage, and a module that has
declared `@no_alloc` can reach every one. That is why this module declares it too: colouring a
line is exactly what a program with no allocator most wants to do, and a facility it could not use
would be no facility at all.

A sequence is written where the text it affects is written, because that is what it is -- a mark
in the stream rather than a property of a string:

print(f"{red}{bold}error{reset}: {msg}")

## What is not here, and why

**Nothing that asks whether the terminal is a terminal.** Whether escapes should be emitted at all
-- output redirected to a file, `NO_COLOR` set, a dumb terminal -- is a question about the process
and its environment, so answering it needs `posix`. This module asks for no capability at all, and
putting the answer here would take that away from every program that only wanted to name a colour.

So the answer lives in `sysl.posix.tty`, which requires what it needs and leaves this file asking
for nothing -- and the namespace is the honest place for it, since `isatty` and `termios` are what
it is made of. A hosted program imports both and asks once:

val paint = color()

print(f"${if paint then red else ""}error${if paint then reset else ""}: ${msg}")

The split is what serves both audiences instead of choosing between them, and it shows up in the
import, which is honest about what the second one costs.

**Nothing that takes a number.** Moving the cursor to a row and column means building
`ESC [ row ; col H`, and building a string is an allocation -- which is the one thing the whole
module is arranged to avoid. The sequences below are the ones whose text is fixed. A program that
wants the others has `f"\u{1b}[{row};{col}H"` and knows what it is spending.

## Reading the numbers

These are the SGR parameters, and the arithmetic between them is a real specification rather than a
coincidence: a background is its foreground plus ten, and a bright colour is its ordinary one plus
sixty. `TermTests` asserts both relations across all eight colours, because a table of forty
constants transcribed by hand has exactly one interesting failure -- a wrong number -- and it is
invisible in review.

## Index

[`black`](#black) [`blink`](#blink) [`blue`](#blue) [`bold`](#bold) [`bright_black`](#bright_black) [`bright_blue`](#bright_blue) [`bright_cyan`](#bright_cyan) [`bright_green`](#bright_green) [`bright_magenta`](#bright_magenta) [`bright_red`](#bright_red) [`bright_white`](#bright_white) [`bright_yellow`](#bright_yellow) [`clear_below`](#clear_below) [`clear_line`](#clear_line) [`clear_screen`](#clear_screen) [`clear_to_line_end`](#clear_to_line_end) [`cyan`](#cyan) [`default_color`](#default_color) [`dim`](#dim) [`green`](#green) [`hidden`](#hidden) [`hide_cursor`](#hide_cursor) [`home`](#home) [`italic`](#italic) [`magenta`](#magenta) [`on_black`](#on_black) [`on_blue`](#on_blue) [`on_bright_black`](#on_bright_black) [`on_bright_blue`](#on_bright_blue) [`on_bright_cyan`](#on_bright_cyan) [`on_bright_green`](#on_bright_green) [`on_bright_magenta`](#on_bright_magenta) [`on_bright_red`](#on_bright_red) [`on_bright_white`](#on_bright_white) [`on_bright_yellow`](#on_bright_yellow) [`on_cyan`](#on_cyan) [`on_default`](#on_default) [`on_green`](#on_green) [`on_magenta`](#on_magenta) [`on_red`](#on_red) [`on_white`](#on_white) [`on_yellow`](#on_yellow) [`red`](#red) [`reset`](#reset) [`restore_cursor`](#restore_cursor) [`reverse`](#reverse) [`save_cursor`](#save_cursor) [`show_cursor`](#show_cursor) [`strike`](#strike) [`underline`](#underline) [`white`](#white) [`yellow`](#yellow)

## Constants

### `black`

```sysl
const black: string = "\u{1b}[30m"
```

### `blink`

```sysl
const blink: string = "\u{1b}[5m"
```

### `blue`

```sysl
const blue: string = "\u{1b}[34m"
```

### `bold`

```sysl
const bold: string = "\u{1b}[1m"
```

### `bright_black`

```sysl
const bright_black: string = "\u{1b}[90m"
```

### `bright_blue`

```sysl
const bright_blue: string = "\u{1b}[94m"
```

### `bright_cyan`

```sysl
const bright_cyan: string = "\u{1b}[96m"
```

### `bright_green`

```sysl
const bright_green: string = "\u{1b}[92m"
```

### `bright_magenta`

```sysl
const bright_magenta: string = "\u{1b}[95m"
```

### `bright_red`

```sysl
const bright_red: string = "\u{1b}[91m"
```

### `bright_white`

```sysl
const bright_white: string = "\u{1b}[97m"
```

### `bright_yellow`

```sysl
const bright_yellow: string = "\u{1b}[93m"
```

### `clear_below`

```sysl
const clear_below: string = "\u{1b}[0J"
```

From the cursor onwards, which is what a program redrawing the tail of something wants.

### `clear_line`

```sysl
const clear_line: string = "\u{1b}[2K"
```

### `clear_screen`

```sysl
const clear_screen: string = "\u{1b}[2J"
```

The whole screen, and the whole line the cursor is on. Neither moves the cursor, which is why
`clear_screen` is nearly always written with `home` after it.

### `clear_to_line_end`

```sysl
const clear_to_line_end: string = "\u{1b}[0K"
```

### `cyan`

```sysl
const cyan: string = "\u{1b}[36m"
```

### `default_color`

```sysl
const default_color: string = "\u{1b}[39m"
```

The colour a terminal was already using, which is not the same as `reset`: this ends the colour
and leaves the emphasis where it was.

### `dim`

```sysl
const dim: string = "\u{1b}[2m"
```

### `green`

```sysl
const green: string = "\u{1b}[32m"
```

### `hidden`

```sysl
const hidden: string = "\u{1b}[8m"
```

### `hide_cursor`

```sysl
const hide_cursor: string = "\u{1b}[?25l"
```

Hiding it is what stops a full-screen redraw from leaving the cursor skittering across the
picture. A program that hides it owns showing it again, including on the way out.

### `home`

```sysl
const home: string = "\u{1b}[H"
```

The top left corner.

### `italic`

```sysl
const italic: string = "\u{1b}[3m"
```

### `magenta`

```sysl
const magenta: string = "\u{1b}[35m"
```

### `on_black`

```sysl
const on_black: string = "\u{1b}[40m"
```

### `on_blue`

```sysl
const on_blue: string = "\u{1b}[44m"
```

### `on_bright_black`

```sysl
const on_bright_black: string = "\u{1b}[100m"
```

### `on_bright_blue`

```sysl
const on_bright_blue: string = "\u{1b}[104m"
```

### `on_bright_cyan`

```sysl
const on_bright_cyan: string = "\u{1b}[106m"
```

### `on_bright_green`

```sysl
const on_bright_green: string = "\u{1b}[102m"
```

### `on_bright_magenta`

```sysl
const on_bright_magenta: string = "\u{1b}[105m"
```

### `on_bright_red`

```sysl
const on_bright_red: string = "\u{1b}[101m"
```

### `on_bright_white`

```sysl
const on_bright_white: string = "\u{1b}[107m"
```

### `on_bright_yellow`

```sysl
const on_bright_yellow: string = "\u{1b}[103m"
```

### `on_cyan`

```sysl
const on_cyan: string = "\u{1b}[46m"
```

### `on_default`

```sysl
const on_default: string = "\u{1b}[49m"
```

### `on_green`

```sysl
const on_green: string = "\u{1b}[42m"
```

### `on_magenta`

```sysl
const on_magenta: string = "\u{1b}[45m"
```

### `on_red`

```sysl
const on_red: string = "\u{1b}[41m"
```

### `on_white`

```sysl
const on_white: string = "\u{1b}[47m"
```

### `on_yellow`

```sysl
const on_yellow: string = "\u{1b}[43m"
```

### `red`

```sysl
const red: string = "\u{1b}[31m"
```

### `reset`

```sysl
const reset: string = "\u{1b}[0m"
```

Ends **everything**: colour, background and every attribute at once. ANSI has no way to end one
attribute and leave the others, which is why the two below exist for the common case of wanting a
colour back without losing an emphasis.

### `restore_cursor`

```sysl
const restore_cursor: string = "\u{1b}[u"
```

### `reverse`

```sysl
const reverse: string = "\u{1b}[7m"
```

### `save_cursor`

```sysl
const save_cursor: string = "\u{1b}[s"
```

One remembered position -- the terminal's own, so nesting two of these does not work and the
second save is the one that is restored.

### `show_cursor`

```sysl
const show_cursor: string = "\u{1b}[?25h"
```

### `strike`

```sysl
const strike: string = "\u{1b}[9m"
```

### `underline`

```sysl
const underline: string = "\u{1b}[4m"
```

### `white`

```sysl
const white: string = "\u{1b}[37m"
```

### `yellow`

```sysl
const yellow: string = "\u{1b}[33m"
```
