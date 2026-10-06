---
title: The io module
summary: "`sysl.io` — `Reader`, the one trait input travels through; `FdReader` and `stdin()`; and `lines()`, the cursor that borrows what it reads from."
weight: 40
---

**Every declaration in `sysl.io`, with its signature:** [the generated API page](/api/sysl-io/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

`sysl.io` is the **input** half of the byte surface. It is a module rather than part of the core for a
reason that reads as an asymmetry and is not: `print` is a keyword, so what it desugars onto cannot be
behind an import a program has not written — while **nothing in the language desugars onto reading**.
A program that never takes input never has these names, and one that does said so.

```sysl
trait Reader: Fallible
    read(*self, into: []u8) -> []const u8

struct FdReader
    fd: int
    bad: bool

fd_reader(fd: int) -> FdReader
stdin() -> FdReader

find_byte(b: []const u8, c: u8) -> Option[usize]
line_text(b: []const u8) -> string
try_line_text(b: []const u8) -> Result[string, Utf8Error]

read_all(r: *Reader) -> Buf[u8]
read_all_text(r: *Reader) -> Result[string, Utf8Error]
read_exact(r: *Reader, into: []u8) -> []const u8

enum LineEnding
    Lf
    CrOrLf

struct Lines
    getline(*self) -> Option[string]
    try_getline(*self) -> Option[Result[string, Utf8Error]]

lines(r: *Reader) -> Lines
console_lines(r: *Reader) -> Lines
lines_ending(r: *Reader, ending: LineEnding) -> Lines
```

That is the whole module. Its shape is [`Writer`](/library/core/) turned around, deliberately: one
method on bytes, a latch rather than a `Result`, and `*self` on both so a source can be stateful and
still object-safe for a raw trait object — which is what lets a reader need no allocator.

## Reading the lot, and filling a slice

`read` answers whatever **one** call could fill, and a short answer is not the end of anything — a
pipe with one write in it so far, a socket, and a terminal all answer short routinely. So *"read
once"* and *"fill this"* are different requests, and three functions are the loops that turn one into
the other:

```sysl
import sysl.io.{bytes_reader_at_most, read_all, read_all_text, read_exact}

main()
    var r = bytes_reader_at_most("hello".bytes, 2)
    var into: [3]u8 = [0; 3]

    print(read_exact(&r, into).len)
    print(read_all(&r).len())

    var t = bytes_reader_at_most("hello".bytes, 1)

    read_all_text(&t) match
        Ok(s) -> print(s)
        Err(e) -> print("not text at byte", e.offset)
```

```output
3
2
hello
```

`bytes_reader_at_most` is what poses the case: it hands back at most that many bytes per call
whatever it was offered. Nothing in the block above would notice the difference against a reader that
always fills, which is exactly why the loops are worth having written once — the two mistakes in a
hand-rolled version are stopping at the first short read, and taking the length from the slice you
offered rather than from the one you were handed.

`read_all_text` has **no panicking twin**, and that is deliberate. `line_text` has one because a
program reading a file it expects to be text has nothing sensible to do with a bad line; a whole
stream is exactly what arrives off a wire, out of a serial port, or from a file somebody else wrote,
so the caller is told rather than stopped.

`read_exact` hands back a prefix of `into` exactly as `read` does, so a caller compares `got.len`
against what it asked for rather than being given a count it might forget to apply.

## The latch is required, not declared

`Reader` requires `Fallible` rather than declaring a `failed` of its own, and so does `Writer`. That
is what lets **one type be a reader and a writer at once**: a trait's members become the implementing
type's, so two traits each declaring their own `failed` could not both be implemented for one file.
An open file is both, which is why [`sysl.fs`](/library/fs/) works at all.

Leaving it out is caught at the `impl`, with the line to write:

```sysl
import sysl.io.Reader

struct Memory
    src: []const u8
    at: usize

impl Reader for Memory
    read(*self, into: []u8) -> []const u8 = into[0..<0]
```

```error
'sysl.io.Reader' requires 'sysl.Fallible', so 'Memory' has to implement that too — write 'impl sysl.Fallible for Memory'
```

`failed` **defaults to `false`**, so a source that cannot fail — an in-memory buffer, a fixed device —
writes `impl Fallible for X` with no block at all and says nothing about failure.

## `read` answers with the prefix it filled

```sysl
import sysl.io.Reader

struct Memory
    src: []const u8
    at: usize

impl Fallible for Memory

impl Reader for Memory
    read(*self, into: []u8) -> []const u8
        var n = self.src.len - self.at

        if n > into.len then n = into.len

        for i in 0..<n do into[i] = self.src[self.at + i]

        self.at += n
        into[0..<n]
    end read
end Memory

var m = Memory("alpha\nbeta".bytes, 0)
var r: *Reader = &m
var window: [4]u8

var got = r.read(window)

print(got.len, got[0], got[3])

var more = r.read(window)

print(more.len, more[0])
print(m.failed())
```

```output
4 97 104
4 97
false
```

**It hands back a slice rather than a count, and that is the one place this surface improves on
`read(2)`.** A slice already *is* a length and a pointer: `got.len` is the count and `got` is what to
look at, so there is no way to be handed the one and forget to apply it to the other — which is the
mistake `read(2)`'s signature invites every time it is called. It costs nothing, since a view is three
words either way.

The caller supplies the storage, and it has to be writable:

```sysl
import sysl.io.{Reader, stdin}

var r = stdin()
var frozen: []const u8 = "hi".bytes

var bad = r.read(frozen)
```

```error
a '[]const byte' views elements it may not write, and a '[]byte' is a licence to write them — so the one does not become the other
```

That is the [read-only view rule](/reference/types/) in the direction it does not travel. A `[]T` goes
where a `[]const T` is wanted; the reverse would be a licence nobody granted.

## Empty means the end; `failed` means it ended badly

**Reading empty says end of input and says only that.** Whether input ended *badly* is a separate
question, and the two are separate because a caller who does not care should not have to ask, and a
caller who does should not have to unwrap a `Result` at every read to find out.

That mapping is `read(2)`'s own — zero for the end, `-1` for a failure — so nothing is being conflated
to make the surface tidy.

```sysl
import sysl.io.{Reader, lines}

struct Flaky
    left: int
    bad: bool

impl Fallible for Flaky
    override failed(*self) -> bool = self.bad

impl Reader for Flaky
    read(*self, into: []u8) -> []const u8
        if self.left <= 0
            self.bad = true
            return into[0..<0]

        self.left -= 1
        into[0] = u8('x')
        into[1] = 10
        into[0..<2]
    end read
end Flaky

var f = Flaky(2, false)
var n = 0

for line in lines(&f)
    n += 1

print(n, f.failed())
```

```output
2 true
```

**The loop ended and the question is still answerable.** That is the whole reason the next section's
cursor is shaped the way it is.

## `lines` borrows its reader

```sysl
import sysl.io.{Reader, lines}

struct Memory
    src: []const u8
    at: usize

impl Fallible for Memory

impl Reader for Memory
    read(*self, into: []u8) -> []const u8
        var n = self.src.len - self.at

        if n > into.len then n = into.len
        if n > 6 then n = 6

        for i in 0..<n do into[i] = self.src[self.at + i]

        self.at += n
        into[0..<n]
    end read
end Memory

var text = "alpha\nbeta\r\ngamma"
var m = Memory(text.bytes, 0)

for line in lines(&m)
    prints("[")
    prints(line)
    prints("]")

prints("\n")
print(m.failed(), m.at)
```

```output
[alpha][beta][gamma]
false 17
```

This reader hands back at most six bytes at a time, so `"beta\r\n"` spans two reads and `"gamma"`
arrives with no newline after it at all. Both come out whole, and the `\r` is gone.

**A trailing `\r` leaves with the `\n`**, so text written on either system reads the same. That is
`bufio.Scanner`'s choice rather than C `getline`'s, and it is the right one for a language whose
`string` is bytes: a program that did not strip it would find `line == "beta"` false on half the
world's files.

`lines` takes a `*Reader`, and the `&` is not decoration:

```sysl
import sysl.io.{lines, stdin}

var r = stdin()

for line in lines(r)
    print(line)
```

```error
a *sysl.io.Reader points at a value, so it needs an address — write '&' in front of the sysl.io.FdReader to take one
```

**A `for` iterates a *copy* of its iterator.** A `Lines` that owned its reader would latch a failure
onto a copy the caller cannot reach, and `failed` would be decorative — you could ask it, and the
answer would be about a cursor that no longer exists. Borrowing leaves the reader named in the
caller's scope, which is what makes `r.failed()` answerable *after* the loop, the only moment the
question matters. The price is one line at the call site.

### Three more decisions inside the cursor

**It scans the slice `read` returned, not the one it offered.** Those are the same memory for a reader
that fills what it was given, so the distinction is free — and it is what lets a reader hand back a
view of a buffer of *its own*, never touching the offered one, and still be read correctly. Taking the
*length* from the answer and the *bytes* from the offer is how the two could have disagreed, and there
is nowhere for them to.

**A line that fits in one read is never copied.** The cursor scans its buffer in place; only a line
spanning two reads is gathered, into a `Buf[u8]` that is then reused. So the cost is proportional to
the input rather than to the input times the number of refills.

**A line is found with `memchr`**, through `find_byte` — libc's scans a word at a time where a sysl
loop could not:

```sysl
import sysl.io.find_byte

print(find_byte("a,b".bytes, u8(',')).unwrap(), find_byte("abc".bytes, u8('z')).is_none())
```

```output
1 true
```

That function is [what pointer difference is for](/reference/memory/): `memchr` answers *where* with
an address, and an index is that address minus the first. It is public because a program scanning for
a delimiter of its own wants exactly it.

**An empty read ends the cursor for good.** A caller reading again past the end gets `None` every
time rather than a second chance, which is right for the sources `read(2)` serves.

### Validation happens where the bytes arrive

`getline` yields a `string`, not bytes — so input is checked for well-formed UTF-8 at the boundary it
came in at, which is [where the language puts that check](/reference/types/). Ill-formed input stops
the program, naming the byte offset within the line.

That severity is affordable **because the layer underneath is public**. A caller who would rather
inspect than trap reads bytes through `Reader` and validates them itself with
[`from_utf8`](/library/text/), which is what having two layers is for. `line_text` is exported for the
same reason: a program doing its own framing can still get the `\r` handling and the validation
without reimplementing either.

### When stopping is the wrong severity

Reading a file you expect to be text, stopping is right — there is nothing sensible to do with the
rest, and carrying on puts the mistake a long way from its cause. Reading a serial port it is not,
and on a freestanding target it is worse than not: `exit` there is a halt with no supervisor to
notice it, so one mistyped byte hangs the board.

`try_getline` is the same walk reporting instead, and `try_line_text` the same conversion. Two
answers nest — the `Option` says whether there was a line at all, the `Result` says whether it was
text:

```sysl
import sysl.io.{lines, stdin}

var r = stdin()
var c = lines(&r)

loop
    c.try_getline() match
        None -> break
        Some(line) ->
            line match
                Ok(s) -> print("[", s, "]")
                Err(e) -> print("not text, at byte", e.offset)
```

`getline` is written in terms of it, so there is one place that decides where a line ends and one
that decides what to do about bytes that are not text.

### A terminal does not end a line the way a file does

`lines()` splits on `\n`, which is right for a file and for a pipe. **A terminal sends a bare `\r`
when Enter is pressed**, so a cursor splitting on LF never sees a line at all: it waits forever,
prints nothing, and looks hung, with nothing to grep for, because nothing has gone wrong — the line
has not ended.

**`console_lines` is the same cursor taking CR, LF and CRLF alike.** Which is right is a property of
where the bytes came from, which the library cannot know: a `\r` in the middle of a pipe's line is a
`\r`, and a `\r` from a console is a line. So the caller says, and `lines()` is unchanged.

```sysl
import sysl.io.*

struct Typed
    src: []const u8
    at: usize

impl Fallible for Typed

impl Reader for Typed
    read(*self, into: []u8) -> []const u8
        var n: usize = 0

        while n < into.len && self.at < self.src.len
            into[n] = self.src[self.at]
            self.at += 1
            n += 1

        into[0..<n]

var keys = Typed("one\rtwo\r\nthree\n".bytes, 0)

for line in console_lines(&keys)
    print("[", line, "]")
```

```output
[ one ]
[ two ]
[ three ]
```

**A bare `\r` ends the line immediately rather than waiting to see whether an `\n` follows**, because
on a bare-CR terminal it never does and waiting inside the read would hang until the next keystroke.
What that costs is that the `\n` of a `\r\n` may arrive in a *later read* than its `\r`, so the cursor
remembers it is owed one across the refill — which is the case a hand-rolled console reader gets
wrong, producing a spurious empty line between every pair.

`lines_ending(r, e)` is the two written out, for a caller holding the policy as a value.

**Echo and editing are not a line cursor's business** — one is about the *terminal* and the other
about the *line* — and they have to come from somewhere, because a terminal in raw mode shows nothing
as it is typed and a mistake cannot be corrected without them.

They come from [`sysl.term.edit`](/library/term/#reading-a-line-sysl-term-edit), which is this trait's
other consumer: it edits where nothing else will, and hands back whole lines the same way a `Lines`
does — an `Iterate` whose `Item` is `string`. **So the two are interchangeable at a call site**, as a
`*Iterate[string]`, and which one a
program wants is decided by whether anything else is already doing the editing — the kernel, on a
cooked terminal, and nothing at all over a serial cable.

## Reading from memory, and writing to it — `bytes_reader` and `bytes_writer`

Every `Reader` and every `Writer` above is a file descriptor, and for a long time that was all there
was — which made a whole class of code awkward to reach: anything taking a `*Reader` could only be
exercised by arranging for real input to arrive on a real descriptor.

```sysl
import sysl.io.{bytes_reader, bytes_writer, lines}
import sysl.text.from_utf8

main()
    var src = bytes_reader("one\ntwo\n".bytes)
    var out = bytes_writer()

    for line in lines(&src)
        out.write(line.bytes)
        out.write("|".bytes)

    print(from_utf8(out.view()).unwrap_or("?"))
```

```output
one|two|
```

A `bytes_reader` **borrows** what it reads rather than copying it, so one over a large buffer costs
three words; what that asks is that the bytes outlive the reader, which is the ordinary rule for a
slice. A `bytes_writer` grows to fit, and `view()` hands back everything written so far — a caller
wanting text calls `from_utf8` on that view and gets to decide what an ill-formed sequence means,
which is right here, because whatever wrote those bytes is the thing under suspicion.

**`bytes_reader_at_most(b, n)` hands back at most `n` bytes per read, however much room it was
offered**, and it earns its place: anything reading a stream has cases that arise *only* when a unit
of input straddles two reads — the two bytes of a `\r\n`, an escape sequence, the continuation bytes
of one character. Those are exactly the cases a hand-rolled reader gets wrong, and a reader that
always empties itself in one go can never produce them. It is also what a slow source honestly looks
like: a serial port hands over what has arrived, not what was asked for.

## `FdReader` and `stdin`

```sysl
import sysl.io.{lines, stdin}

var r = stdin()

for line in lines(&r)
    print(line)

if r.failed() then print("input ended badly")
```

`stdin()` names the descriptor a program is started with, so a caller does not have to know it is
zero, and `fd_reader(fd)` takes any other. `FdReader` is the **only** reader the library supplies —
the mirror of [`ByteSink`](/library/buf/) being the only writer that keeps what it is given.

**That symmetry is what makes the freestanding story two functions long.** The seams a target with no
C library replaces are exactly `putbytes`' body and `FdReader.read`'s. Swap those two for a `write`
and a `read` syscall and everything above them is unchanged: every renderer, every `Display`, the
whole of `lines`, and every `impl Reader` a program wrote for itself.

---

Next: [`sysl.fs`](/library/fs/) — files and paths, and the capability that gates them.
