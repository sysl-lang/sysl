---
title: sysl.term
summary: The escape sequences a terminal understands — colour, emphasis, and the screen — as constants a program with no allocator can still name.
weight: 85
---

**Every declaration in `sysl.term`, with its signature:** [the generated API page](/api/sysl-term/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.term` is forty-odd `const string`s and nothing else. Each one is an ANSI escape sequence, and
writing one into the output stream is how a terminal is told to change colour, start underlining, or
clear itself.

```sysl
import sysl.term.*

print(f"${red}${bold}error${reset}: the file was not there")
```

A sequence is written **where the text it affects is written**, because that is what it is — a mark
in the stream rather than a property of a string. There is no coloured-string type here and nothing
to wrap: `red` is text that happens to be invisible, it concatenates like any other text, and
`reset` is how you stop.

## Why constants, and what that buys

A string literal is [immortal](/reference/strings/) — it lives in the program's own image with no
owner and no reference count — so naming forty of them costs nothing at run time and nothing in
storage. That is what lets this module declare `@no_alloc`, and it is the point of the whole design:
colouring a line is exactly what a program that has given up its allocator most wants to do, and a
facility such a program could not use would be no facility at all.

```sysl
@no_alloc
@no_os

import sysl.term.*

main()
    print(f"${green}ok${reset}")
```

The module requires no capability at all, so an interrupt handler can name a colour.

## The colours

Eight, each with a bright variant and a background form. The arithmetic between them is the
specification rather than a coincidence: **a background is its foreground plus ten, and a bright
colour is its ordinary one plus sixty.**

```sysl
import sysl.term.*

// An escape is invisible, so this reads the parameter back out of one.
code(s: string) -> int
    var n = 0

    for b in s.bytes
        if b >= u8('0') && b <= u8('9') then n = n * 10 + int(b - u8('0'))

    n

main()
    print(code(red), code(bright_red), code(on_red), code(on_bright_red))
```

```output
31 91 41 101
```

| foreground | bright | background | bright background |
|---|---|---|---|
| `black` | `bright_black` | `on_black` | `on_bright_black` |
| `red` | `bright_red` | `on_red` | `on_bright_red` |
| `green` | `bright_green` | `on_green` | `on_bright_green` |
| `yellow` | `bright_yellow` | `on_yellow` | `on_bright_yellow` |
| `blue` | `bright_blue` | `on_blue` | `on_bright_blue` |
| `magenta` | `bright_magenta` | `on_magenta` | `on_bright_magenta` |
| `cyan` | `bright_cyan` | `on_cyan` | `on_bright_cyan` |
| `white` | `bright_white` | `on_white` | `on_bright_white` |

`default_color` and `on_default` put each back to whatever the terminal was using.

## Emphasis, and why `reset` is not enough

| name | what it does |
|---|---|
| `bold` | heavier, or brighter on a terminal with no bold face |
| `dim` | fainter |
| `italic` | slanted, where the terminal has it |
| `underline` | underlined |
| `blink` | blinking, where the terminal allows it |
| `reverse` | foreground and background swapped |
| `hidden` | not shown, but still selectable |
| `strike` | struck through |
| `reset` | **all of the above, and the colours, off at once** |

ANSI has no way to end one attribute and leave the others — `reset` ends everything there is. So a
program that wants its colour back without losing an emphasis writes `default_color` rather than
`reset`, and one that has ended a colour inside an underlined field has to open the underline again
afterwards.

## The screen and the cursor

| name | what it does |
|---|---|
| `clear_screen` / `clear_line` | the whole screen, the whole line — neither moves the cursor |
| `clear_below` / `clear_to_line_end` | from the cursor onwards |
| `home` | the top left corner |
| `hide_cursor` / `show_cursor` | a program that hides it owns showing it again, including on the way out |
| `save_cursor` / `restore_cursor` | one remembered position, the terminal's own — these do not nest |
| `alt_screen` / `main_screen` | the alternate screen a full-screen program draws on, and back — leaving it restores what the shell showed |
| `sync_begin` / `sync_end` | a redraw the terminal shows all at once; ignored by a terminal that does not know it |

`clear_screen` is nearly always written with `home` after it, since clearing does not move anything.

**A full-screen program draws without flicker by doing three things**: it enters `alt_screen` and
hides the cursor once, at the start; it builds each whole frame as one string and writes it in one
write, between `sync_begin` and `sync_end`; and it overwrites rather than clears — `home`, then each
line followed by `clear_to_line_end`, then `clear_below` — so no moment shows a blank screen. On the
way out it writes `show_cursor` and `main_screen`, and puts the terminal's mode back.

## Whether to write escapes at all — `sysl.posix.tty`

Naming a colour and deciding to use one are different questions, and they live in different modules.
Everything above asks for no capability at all, so an allocator-free, OS-free program can reach it.
Asking whether output is a terminal needs `isatty`, which needs `posix` — and a capability
requirement is **module-wide**, so one function here would have taken all forty constants away from
the programs this module is arranged for. The answer sits in `sysl.posix` instead, and the split
shows up in the import, which is honest about what the second one costs.

**It is `sysl.posix.tty` rather than `sysl.term.tty`, and the namespace is the point.** Everything
under `sysl.posix` requires that one capability, so a freestanding target reaches none of it — which
is now visible in the import line rather than only in the module's own header. What this needs is
`isatty(3)` and `termios`, so that is where it belongs, however much it reads as terminal handling.

| name | answers |
|---|---|
| `is_tty(fd)` | is this descriptor a terminal? |
| `color_wanted()` | does the environment want colour — `NO_COLOR` unset or empty, `TERM` not `dumb`? |
| `color_on(fd)` | both, for one descriptor |
| `color()` / `color_err()` | both, for standard output and standard error |
| `window_size(fd)` / `size()` | the terminal's rows and columns as a `WindowSize`, or `None` — for a descriptor, and for standard output |

**Ask once and keep the answer.** Each of these is a system call or an environment scan, and nothing
a running program does changes what they say.

```sysl
import sysl.term.{red, reset}
import sysl.posix.tty.color

main()
    val paint = color()
    val on    = if paint then red else ""
    val off   = if paint then reset else ""

    print(f"${on}error${off}: not found")
```

```output
error: not found
```

That output is the point rather than an accident: this page's programs run with their output
captured, so `color()` answers false and the escapes are never written — which is exactly what the
same program does in a pipeline or redirected to a file.

`is_tty` is worth having alone: a progress bar, a spinner and a prompt are all worth suppressing when
output is a pipe, and none of them is about colour. So is `color_wanted` — a `--color=always` flag
overrides the descriptor without overriding the user's `NO_COLOR`, and that is exactly this function.

**`NO_COLOR` is about the variable being there rather than about its value.** Present and non-empty
turns colour off whatever it contains, so `NO_COLOR=0` means no colour, while set-and-empty does not.
A program reading it as a boolean and looking for `"1"` has misread the convention.

**The size is the exception to asking once**, since a window is resized while the program runs. The
kernel says so with `WINCH`, and `sysl.signal` turns that into a flag: a full-screen program watches
it and asks `size()` again before the redraw that follows. A pipe has no size, and nor does a
terminal reporting zero rows by zero columns, which is what a pseudo-terminal nobody sized says.

```sysl
import sysl.posix.tty.size
import sysl.signal.{WINCH, watch}

main()
    val resized = watch(WINCH).unwrap()

    print(size())
    print(resized.take())
```

```output
None
false
```

## Taking the terminal over — `sysl.tty`

A terminal at a shell is in **cooked** mode: the kernel's line discipline echoes what is typed,
honours backspace, and hands the program a whole line at Enter. That is why `sysl.io.console_lines`
is all a hosted program usually needs — something else is doing the editing.

`raw()` puts that out of the way, so a program sees each keystroke as it is typed, and `hidden()`
keeps the lines and stops the echo, which is what a password prompt wants.

| name | does |
|---|---|
| `raw()` | cbreak mode — keystrokes arrive as typed, nothing is echoed. **Answers whether it worked** |
| `hidden()` | password mode — whole lines with the terminal's own editing, nothing echoed. **Answers whether it worked** |
| `cooked()` | puts back the mode the terminal was in before the first `raw` or `hidden`, and only what they changed |
| `mode()`, `set_mode(m)` | the terminal's `Mode` — `echo`, `canonical`, `signals`, `output`, `crlf`, `min`, `time` — read and written |
| `flush()` | (`sysl.posix.tty`) pushes out what C is holding — a prompt with no newline after it |
| `tty_writer()` | (`sysl.posix.tty`) standard output as a sink that flushes what it is given |

**`sysl.tty` asks for no C library.** Every call goes through the two hooks of `sysl.tty.sys`,
`sysl_tty_get` and `sysl_tty_set` over a `Mode`, answered over `termios` on a host and by the program
— a kernel, a board's console — with an `@export` where there is no C library; a freestanding program
that changes a terminal and answers neither is refused when it is compiled, naming both.
`sysl.posix.tty`'s `raw`, `hidden` and `cooked` are these same functions under the names that module
has always had.

**`raw()` answering `false` is not an error — it is the other situation.** With input redirected from
a file or a pipe there is no terminal to change, and an editor is the wrong facility anyway: nothing
is being typed and nothing should be echoed. So a program picks its reader from the answer, and
`prog < script.txt` goes on working.

```sysl
import sysl.io.{stdin, console_lines}
import sysl.posix.tty.{raw, cooked}

main()
    var input = stdin()

    if raw()
        print("a terminal")
        cooked()
    else
        var cursor = console_lines(&input)

        print("a pipe")
```

```output
a pipe
```

That output is the point rather than an accident, exactly as above: this page's programs run with
their input closed, so `raw()` declines and the cooked path is what runs.

### What each sets, and the one thing both give up

`raw` is `-icanon -echo -isig opost onlcr min 1 time 0`, and `hidden` is `-echo -isig` with
everything else as it was found. Both are made from the mode the first of them found, so moving from
one to the other is never a drift. Output translation is **asserted rather than assumed** in `raw` —
nothing here turns it off, so leaving it out looked safe, and a terminal that arrives without it makes
every `print` stair-step down the screen while the editor's own output looks fine.

**Signals go, in both, and that is a choice rather than a limitation.** Leaving `isig` alone would
keep Ctrl-C interrupting, which reads like a feature for a REPL — and a program interrupted with echo
off leaves a shell that shows nothing as it is typed. With `-isig` Ctrl-C arrives as **byte 3**
instead, so every way out of the program is an ordinary exit; `getpass(3)` turns signals off for the
same reason.

What that costs is worth saying plainly: a program that has stopped responding can no longer be
interrupted from its own terminal, and the escape is `kill` from another one. What it buys is that
the terminal is never left broken, and that a hosted program behaves exactly like one on a board —
which never had signals to disable.

**On a host the terminal is put back at exit even without `cooked()`.** The hosted answer is
`termios` through a shim beside `sysl.tty.hosted` — `struct termios` is caller-allocated and laid out
differently on every platform, so it stays in C and a descriptor and a packed `Mode` are all that
cross — and its first change saves the whole `struct termios` it found and registers `atexit` to put
it back. `cooked` restores **what was actually there**: exactly the settings `Mode` names, as they
were found, and nothing it never changed. **A freestanding program gets no exit hook from the
library**, there being no C library to register one with: it calls `cooked()` on its way out, and a
kernel that keeps a terminal's mode past a process keeps whatever it was left in.

```sysl
import sysl.tty

if tty.hidden()
    print("reading a password")
    tty.cooked()
else
    print("no terminal: nothing to hide")
```

```output
no terminal: nothing to hide
```

## Reading keys — `sysl.term.keys`

A terminal in raw mode sends one byte for an ordinary key and a short **escape sequence** for the
rest — `ESC [ A` for the up arrow, `ESC [ 5 ~` for Page Up, `ESC O P` for F1. A full-screen program
wants keys rather than bytes, and `KeyDecoder` is the step between: bytes go in with `push`, as they
come off any reader, and finished keys come out of `next`.

```sysl
import sysl.term.keys.{key_decoder, Key}

main()
    var keys = key_decoder()

    for b in "q\u{1b}[A\u{1b}[5~\u{1b}OP\u{1b}x\r\u{3}".bytes
        keys.push(b)

        loop
            keys.next() match
                None -> break
                Some(k) -> print(k)
```

```output
Key.Char(c: 'q')
Key.Up
Key.PageUp
Key.F(n: 1)
Key.Alt(c: 'x')
Key.Enter
Key.Ctrl(c: 'c')
```

| key | what sends it |
|---|---|
| `Char(c)` | a printable character, ASCII or not — a multi-byte one is one key, and a malformed byte is U+FFFD |
| `Ctrl(c)` | a control byte, as its letter: byte 3 is `Ctrl('c')`, because raw mode makes Ctrl-C a byte rather than a signal |
| `Alt(c)` | `ESC` and then a character, which is how a terminal sends Alt (or Meta) |
| `Enter`, `Tab`, `BackTab`, `Backspace`, `Escape` | CR or LF (a CRLF is one Enter), byte 9, `ESC [ Z`, byte 8 or 127, `ESC` alone |
| `Up`, `Down`, `Left`, `Right`, `Home`, `End` | `ESC [` or `ESC O` and a letter; Home and End also as `ESC [ 1 ~` / `4 ~` / `7 ~` / `8 ~` |
| `Insert`, `Delete`, `PageUp`, `PageDown` | `ESC [ 2 ~`, `3 ~`, `5 ~`, `6 ~` |
| `F(n)` | F1 to F4 as `ESC O P` to `S`, and all twelve numbered |

**Both spellings of each key are read**, for the reason the line editor below reads both: a
terminal picks between `ESC [` and `ESC O` by a mode the program did not set. **A sequence is read to
its final byte whatever its parameters**, so a key nothing here names — or xterm's Ctrl-Left,
`ESC [ 1 ; 5 D`, which is read as `Left` — never leaves its tail behind as typed characters.

### Escape, and `pause`

The Escape key sends the byte every sequence starts with, so a decoder that has just seen `ESC`
cannot tell which it was until the next byte arrives — or until none does. **`pause` is how a reader
says none did**: a lone `ESC` becomes `Escape`, a sequence cut short is dropped, and half a character
is U+FFFD. A hosted program gets the pause by setting `time` in its terminal `Mode` (a tenth of a
second is usual), so a read that waits that long and gets nothing returns empty; `partial()` says
whether there is anything for `pause` to finish.

```sysl
import sysl.term.keys.key_decoder

main()
    var keys = key_decoder()

    keys.push(27)
    print(keys.next(), keys.partial())
    keys.pause()
    print(keys.next(), keys.partial())
```

```output
None true
Some(Key.Escape) false
```

Without a pause the Escape arrives with the next keystroke, which is what every terminal program does
when it cannot time out. Like the rest of this module it asks for nothing of the platform — no
capability, no C, no allocation — so the same decoder reads a board's serial console.

## Reading a line — `sysl.term.edit`

The other half of what a console needs, and the reason it exists: **a terminal with no line
discipline gives a program nothing.** Over a serial cable there is none at all; at a hosted terminal
`raw()` has just removed it. Either way nothing appears as it is typed and a mistake cannot be
corrected — which is not a program that reads badly but a program that looks broken.

`editor(r, w)` is a line editor over a `*Reader` and a `*Writer`. It answers whole lines — an
`Iterate` whose `Item` is `string`, the same as `sysl.io.lines` and `console_lines`, **so the three
are interchangeable at a call site**, as a `*Iterate[string]`, and a program chooses by what is
producing its input.

```sysl
import sysl.io.{bytes_reader, bytes_writer}
import sysl.term.edit.editor

main()
    var typed = bytes_reader("one\rtwo\r".bytes)
    var echo  = bytes_writer()
    var ed    = editor(&typed, &echo)

    for line in ed
        print(line)
```

```output
one
two
```

| keys | do |
|---|---|
| `←` `→` `Home` `End` | move within the line — and `Ctrl-A` / `Ctrl-E` / `Ctrl-B` / `Ctrl-F` |
| `Backspace` `Delete` | at the cursor, not only at the end |
| `Ctrl-U` `Ctrl-K` | kill the line, or from the cursor on |
| `↑` `↓` | the last 64 lines — and `Ctrl-P` / `Ctrl-N` |
| `Ctrl-C` | abandon the line and answer an empty one |
| `Ctrl-D` | end the input, **on an empty line only** |

**The line is held as characters and measured in columns.** A cursor is an index rather than a byte
offset, so a half character can never be left behind by a backspace — one never enters the line. And
a wide character occupies two columns, so erasing a CJK character or an emoji clears both; an editor
counting characters leaves half of one on the screen.

**Both spellings of an arrow key are read.** `ESC [ D` is CSI and `ESC O D` is SS3, and a terminal
chooses between them by whether application cursor key mode is on. Reading only the first is not a
simplification — it means a left arrow inserts a stray `D` into the line.

### What it is not

There is no completion, no multi-line editing and no absolute cursor addressing. A program wanting
those wants [linenoise](https://github.com/sysl-lang/linenoise). **A line that wraps past the
terminal's width redraws wrong**, which is the honest cost of moving the cursor by writing `\b`: it
stops at column zero rather than climbing to the row above.

**It asks for nothing of the platform** — no capability, no C — which is what lets the same program
run at a terminal and over a cable. It does allocate: the line is a `Buf[char]` and the answer is a
`string`.

### The prompt, and why the editor pokes its sink

The editor prints no prompt and is not told one. Every movement it makes is relative, so it never
needs to know how far along the row the line starts, and a caller goes on printing its own prompt —
which is what makes a REPL's continuation prompt the caller's business rather than a field here.

What it does do is hand its sink a **zero-length write** before waiting for a keystroke. A hosted sink
buffers — `putbytes` goes through C's buffered `stdout`, which line-buffers a terminal — so a prompt with no
newline after it would sit in the buffer until something wrote one, which is one keystroke too late.
The poke gives a buffering sink its chance, and keeps the obligation off every caller that prints a
prompt. A board pays nothing for it: a sink with no buffer writes no bytes.

## What is deliberately not here

**Anything that takes a number.** Moving the cursor to a row and column means building
`ESC [ row ; col H`, and building a string is an allocation — the one thing the module is arranged to
avoid. The sequences above are the ones whose text is fixed; a program that wants the others writes
`f"\u{1b}[${row};${col}H"` and knows what it is spending.
