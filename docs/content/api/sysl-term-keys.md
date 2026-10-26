---
title: sysl.term.keys
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.term.keys
summary: "Keystrokes out of the bytes a terminal sends: the decoder a full-screen program reads its keyboard through."
requires: "no alloc"
---

A terminal in raw mode sends a byte for an ordinary key and a short **escape sequence** for the
others -- `ESC [ A` for the up arrow, `ESC [ 5 ~` for Page Up -- and a program that compares bytes
itself ends up with a left arrow inserting a stray `D`, Page Up read as three keys, and Escape that
never arrives. `KeyDecoder` takes the bytes one at a time, as they come off any `Reader`, and hands
back a `Key` whenever one is complete.

    var keys = key_decoder()

    for b in got
        keys.push(b)

        loop
            keys.next() match
                None -> break
                Some(k) -> act(k)

**It asks for nothing of the platform** -- no capability, no C, no allocation -- so the same
decoder reads a hosted terminal and a serial console on a board.

## Escape, alone

The Escape key sends the byte every sequence starts with, so a decoder that has just seen `ESC`
cannot know which it was until the next byte arrives -- or until none does. **That is what `pause`
is for**: a reader that waited and was given nothing (a terminal in raw mode with `time` set, a
`poll` that timed out) calls it, and a lone `ESC` is answered as `Escape`. Without a pause, `Escape`
arrives with the next keystroke, which is what every terminal program does when it cannot time out.

## Index

[`key_decoder`](#key_decoder) [`Key`](#key) [`KeyDecoder`](#keydecoder)

## Functions

### `key_decoder`

```sysl
key_decoder() -> KeyDecoder
```

A decoder between keys, with nothing held.

## Types

### `Key`

```sysl
enum Key
    Char(c: char)
    Ctrl(c: char)
    Alt(c: char)
    Enter
    Tab
    BackTab
    Backspace
    Delete
    Insert
    Escape
    Up
    Down
    Left
    Right
    Home
    End
    PageUp
    PageDown
    F(n: int)
```

One keystroke.

### `KeyDecoder`

```sysl
struct KeyDecoder
    private state: KeyState
    private param: int
    private params: int
    private cp: uint
    private more: int
    private owed_lf: bool
    private first: Option[Key]
    private second: Option[Key]
```

The decoder: bytes in with `push`, keys out with `next`. Holds at most two finished keys, which is
as many as one byte can finish -- `ESC` then a byte that starts no sequence is an `Escape` and then
that byte's own key.

| Member | Signature | Description |
|---|---|---|
| `push` | `push(*self, b: u8)` | One byte from the terminal. |
| `pause` | `pause(*self)` | The input went quiet: whatever is half read is answered as what it is so far. |
| `next` | `next(*self) -> Option[Key]` | The next finished key, oldest first. |
| `partial` | `partial(self) -> bool` | Whether a key has been started and not finished, which is when a reader should wait a little before calling `pause`. |
