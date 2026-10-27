---
title: sysl.io
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.io
summary: "The byte surface a program reads through, and the line cursor over it."
---

`Reader` is the one interface, `stdin()` is the one every program starts with, `lines()` and
`console_lines()` walk it a line at a time, and `bytes_reader()` / `bytes_writer()` are the same
surface over memory already in hand — which is what makes a parser testable without a file.

**A reader says nothing about where the bytes came from**, which is the whole of what the
abstraction buys: a function taking a `Reader` works over standard input, over a file, and over a
literal in a test, and nothing in it has to be written twice.

## Index

[`bytes_at`](#bytes_at) [`bytes_at_most`](#bytes_at_most) [`bytes_reader`](#bytes_reader) [`bytes_reader_at_most`](#bytes_reader_at_most) [`bytes_writer`](#bytes_writer) [`console_lines`](#console_lines) [`fd_reader`](#fd_reader) [`find_byte`](#find_byte) [`line_text`](#line_text) [`lines`](#lines) [`lines_ending`](#lines_ending) [`read_all`](#read_all) [`read_all_text`](#read_all_text) [`read_exact`](#read_exact) [`read_exact_at`](#read_exact_at) [`slice_at`](#slice_at) [`slice_at_most`](#slice_at_most) [`stdin`](#stdin) [`write_all_at`](#write_all_at) [`BytesAt`](#bytesat) [`BytesReader`](#bytesreader) [`BytesWriter`](#byteswriter) [`FdReader`](#fdreader) [`LineEnding`](#lineending) [`Lines`](#lines-1) [`MemoryError`](#memoryerror) [`SliceAt`](#sliceat) [`Positioned`](#positioned) [`ReadAt`](#readat) [`Reader`](#reader) [`WriteAt`](#writeat) [Fallible for BytesReader](#fallible-for-bytesreader) [Fallible for BytesWriter](#fallible-for-byteswriter) [Fallible for FdReader](#fallible-for-fdreader) [Iterate for Lines](#iterate-for-lines) [Positioned for BytesAt](#positioned-for-bytesat) [Positioned for SliceAt](#positioned-for-sliceat) [ReadAt for BytesAt](#readat-for-bytesat) [ReadAt for SliceAt](#readat-for-sliceat) [Reader for BytesReader](#reader-for-bytesreader) [Reader for FdReader](#reader-for-fdreader) [WriteAt for SliceAt](#writeat-for-sliceat) [Writer for BytesWriter](#writer-for-byteswriter)

## Functions

### `bytes_at`

```sysl
bytes_at(b: []const u8) -> BytesAt
```

A store over `b` that answers as much as it was offered room for.

### `bytes_at_most`

```sysl
bytes_at_most(b: []const u8, most: usize) -> BytesAt
```

A store over `b` that hands back **at most `most` bytes per read**, which is what a device reading
a sector at a time does and what a test of a caller's loop needs.

### `bytes_reader`

```sysl
bytes_reader(b: []const u8) -> BytesReader
```

A reader that hands back as much of what is left as the caller offered room for.

### `bytes_reader_at_most`

```sysl
bytes_reader_at_most(b: []const u8, most: usize) -> BytesReader
```

A reader that hands back **at most `most` bytes per read**, however much room it was offered.

**This is how a consumer's refilling is tested, and there is otherwise no way to do it.** Anything
reading a stream has cases that only arise when a unit of input straddles two reads -- the two
bytes of a CRLF, an escape sequence, the continuation bytes of one character -- and a reader that
always empties itself in one go never produces them. Those cases are exactly the ones a
hand-rolled reader gets wrong, so being unable to reach them is being unable to test the part that
breaks.

It is also what a slow source honestly looks like: a serial port hands over what has arrived, not
what was asked for.

### `bytes_writer`

```sysl
bytes_writer() -> BytesWriter
```

### `console_lines`

```sysl
console_lines(r: *Reader) -> Lines
```

A cursor over a terminal: `\r`, `\n` and `\r\n` each end a line.

It is a name of its own rather than a flag on `lines` because the reason to reach for it is *where
the bytes come from* rather than a setting to be looked up, and "I am reading from a console" is
the thing a caller actually knows.

### `fd_reader`

```sysl
fd_reader(fd: int) -> FdReader
```

### `find_byte`

```sysl
find_byte(b: []const u8, c: u8) -> Option[usize]
```

A line is found with `memchr` rather than a byte loop because libc's reads a word at a time and
sysl's could not. This is the whole of what pointer difference is for: `memchr` answers *where*
with an address, and an index is that address minus the first.

**A target with no C library walks the bytes instead, because there is nothing to call** -- the
same trade `sysl.slices.copy` makes, and for the same reason: a `memchr` reached from here would be
an undefined symbol at the link of every freestanding program that reads a line.

### `line_text`

```sysl
line_text(b: []const u8) -> Result[string, Utf8Error]
```

Bytes to text at the boundary they arrive at, which is where `04` puts the check. A trailing `\r`
goes with the `\n`, so input written on one system reads the same on the other --
`bufio.Scanner`'s choice rather than C `getline`'s.

**Ill-formed input is answered, never fatal**: the `Utf8Error` carries the offset of the first bad
byte *within the line* (the `\r` trim cannot move it, being at the end). Input off a wire, out of
a serial port, or from a file somebody else wrote carries whatever was sent, and on a freestanding
target a stop is a halt with no supervisor to notice it -- so the caller decides, and a program
that does want to stop writes its own `exit` where it can say what it was reading.

### `lines`

```sysl
lines(r: *Reader) -> Lines
```

A cursor over a file or a pipe: `\n` ends a line, and a trailing `\r` is trimmed.

### `lines_ending`

```sysl
lines_ending(r: *Reader, ending: LineEnding) -> Lines
```

### `read_all`

```sysl
read_all(r: *Reader) -> Buf[u8]
```

Every byte the reader has left, gathered into one buffer.

**The whole-stream read the surface was missing.** A `Reader` answers whatever one call could
fill, so a program that wants the lot has to loop -- and hand-rolling that loop is where the two
mistakes live: stopping at the first short read, which is not the end of anything, and taking the
length from the slice you offered rather than from the one you were handed.

It ends at the first empty read, which is what end of input means here, and says nothing about
whether input ended badly -- `failed` is still the question for that, asked once afterwards.

The chunk is the same 4096 a `Lines` uses. It is a buffer size rather than a limit: a stream
larger than it is read in as many passes as it takes.

### `read_all_text`

```sysl
read_all_text(r: *Reader) -> Result[string, Utf8Error]
```

The same, as text. Bytes that are not UTF-8 are answered as an `Err` whose offset is into the
whole stream, as `line_text`'s and `getline`'s are into the line -- nothing in this module stops
the program over input.

### `read_exact`

```sysl
read_exact(r: *Reader, into: []u8) -> []const u8
```

Reads until `into` is full or the input ends, and hands back the prefix that was filled.

`read` may answer short for reasons that have nothing to do with the end of input -- a pipe with
one write in it so far, a socket, a terminal -- so *"fill this"* and *"read once"* are different
requests, and only the second one was expressible. What comes back is a prefix of `into` exactly
as `read`'s is, so a caller checks `got.len` against what it asked for rather than being handed a
count it might forget to apply.

### `read_exact_at`

```sysl
read_exact_at[S: ReadAt](s: *S, offset: u64, into: []u8) -> Result[[]const u8, S::Error]
```

Reads at `offset` until `into` is full or the store ends, and hands back the prefix that was
filled.

It is `read_exact` at an offset: a short `read_at` is not the end of anything, an empty one is,
and what comes back is a prefix of `into` so a caller checks `got.len` against what it asked for.
A failure stops the loop and is handed back as the store reported it; the bytes read before it are
in `into` but not counted, since a caller that is told something went wrong should not have to
decide which of the bytes to believe.

### `slice_at`

```sysl
slice_at(b: []u8) -> SliceAt
```

### `slice_at_most`

```sysl
slice_at_most(b: []u8, most: usize) -> SliceAt
```

A store over `b` that reads and writes **at most `most` bytes per call**.

### `stdin`

```sysl
stdin() -> FdReader
```

### `write_all_at`

```sysl
write_all_at[S: WriteAt](s: *S, offset: u64, bytes: []const u8) -> Result[unit, S::Error]
```

Writes all of `bytes` at `offset`, across as many `write_at`s as it takes.

**A store that answers `Ok(0)` to a non-empty write has broken `WriteAt`'s contract, and this
stops the program saying so.** There is no error to hand back instead: the error type is the
store's, and nothing here can make one of those. The alternative -- answering a count the caller
then has to compare -- would put that comparison at every call site, for a case no correct store
produces.

## Types

### `BytesAt`

```sysl
struct BytesAt
    bytes: []const u8
    most: usize
```

A read-only store over bytes already in hand -- a file's contents read whole, a table in flash, a
literal in a test.

It borrows, as `BytesReader` does, so it costs three words and the bytes must outlive it.

### `BytesReader`

```sysl
struct BytesReader
    bytes: []const u8
    at: usize
    most: usize
```

A reader over bytes already in hand.

It **borrows** what it reads rather than copying it, so a reader over a large buffer costs three
words. What that asks of a caller is that the bytes outlive the reader, which is the ordinary rule
for a slice and needs no special mention beyond this one.

| Member | Signature | Description |
|---|---|---|
| `remaining` | `remaining(self) -> usize` |  |

### `BytesWriter`

```sysl
struct BytesWriter
    bytes: Buf[u8]
```

A writer that keeps what it is given.

The bytes are a `Buf[u8]`, so the sink grows to fit and `view` hands back everything written so
far. A caller wanting text calls `from_utf8` on that view, and gets to decide what an ill-formed
sequence means rather than being stopped -- which is right here, because whatever wrote those
bytes is the thing under suspicion.

| Member | Signature | Description |
|---|---|---|
| `view` | `view(self) -> []const u8` | Everything written so far. |
| `len` | `len(self) -> usize` |  |
| `is_empty` | `is_empty(self) -> bool` |  |
| `clear` | `clear(*self)` | Forget what was written, keeping the storage -- so a loop that measures one rendering after another allocates once. |

### `FdReader`

```sysl
struct FdReader
    fd: int
    bad: bool
```

A reader over a file descriptor, through `sysl.io.sys`'s `read` hook -- which a hosted target's
library answers over C's `read`, and a target with no C library answers with an `@export` of its
own, a `read` system call usually being the whole of it. Whichever answers, the surface above it is
unchanged.

### `LineEnding`

```sysl
enum LineEnding
    Lf
    CrOrLf
```

What ends a line for a given cursor.

Two policies rather than three: there is no CRLF-only setting, because a reader strict about
which of the two a sender used has a protocol rather than a line, and would want the bytes.

### `Lines`

```sysl
struct Lines
    src: *Reader
    chunk: []u8
    have: []const u8
    at: usize
    held: &Buf[u8]
    done: bool
    ending: LineEnding
    owed_lf: bool
    private fault: &LineFault
```

A cursor that yields one line at a time.

**It borrows its reader rather than owning it**, which is not a preference: a `for` loop iterates
a *copy* of the iterator, so a `Lines` holding an `FdReader` by value would latch its failure on a
copy the caller cannot reach, and `failed` would be decorative. Holding a `*Reader` leaves the
reader in the caller's hands, which is exactly what makes `r.failed()` answerable after the loop
has ended. It costs a line at the call site -- the reader has to be named -- and buys the only
question worth asking once reading has stopped.

It scans **the buffer in place** and copies nothing for a line that fits in one read; only a line
that spans two reads is gathered, into a `Buf[u8]` that is then reused, so the cost is
proportional to the input rather than to the input times the number of refills.

What it scans is **the slice `read` handed back**, not the one it offered. Those are the same
memory for a reader that fills what it was given, and the distinction costs nothing -- but it is
what lets a reader hand back a view of a buffer of *its own*, never touching the offered one, and
still be read correctly. Taking a *length* from the answer and the *bytes* from the offer is how
the two could have disagreed, and there is now nowhere for them to.

An empty read ends the cursor for good. That is right for the sources `read(2)` serves, where zero
means end of file and a failure arrives as `-1` instead -- so nothing is being conflated, and a
caller wanting to read again past the end gets `None` every time rather than a second chance.

| Member | Signature | Description |
|---|---|---|
| `error` | `error(self) -> Option[Utf8Error]` | Why `getline` stopped before the input ended: the line at the cursor was not UTF-8, and the error's `offset` is into that line, not into the stream. |
| `line_end` | `line_end(self, b: []const u8) -> Option[usize]` | Where the next line ends in `b`, which is the whole of what the policy decides. |
| `getline` | `getline(*self) -> Option[string]` | The next line, or nothing once the input has ended -- **or once a line was not text.** A line that is not UTF-8 ends the cursor for good rather than stopping the program: `getline` answers `None` there and on every call after, and `error()` says which byte of that line it was. |
| `try_getline` | `try_getline(*self) -> Option[Result[string, Utf8Error]]` | The whole of the line-finding, so that the two forms cannot come to disagree about where a line ends. |
| `held_line` | `held_line(*self) -> Result[string, Utf8Error]` |  |
| `refill` | `refill(*self)` |  |

### `MemoryError`

```sysl
enum MemoryError
    NoRoom
```

Why a store over memory refused: a write starting at or past the end of its storage, which is
fixed. Reading cannot fail at all, and past the end is an empty answer rather than this.

### `SliceAt`

```sysl
struct SliceAt
    bytes: []u8
    most: usize
```

A store over storage the caller owns, read and written in place -- a sector buffer, a RAM disk, a
flash image being built.

Its size is the slice's: a write is cut short at the end, and one starting at or past it is
`NoRoom`, since there is nowhere for it to go. Nothing is grown, which is what keeps it usable where
nothing can be allocated.

## Traits

### `Positioned`

```sysl
trait Positioned
    type Error
    extent(*self) -> Result[u64, Self::Error]
```

What every positioned store has, whichever direction it is used in: its own error, and how many
bytes it holds.

**It is a trait of its own, required by both `ReadAt` and `WriteAt`, for the reason `Fallible`
is.** An associated type is named off the type (`S::Error`), never off the trait, so a type may
implement only one trait declaring an `Error` (`reference/traits.md § One name per type`). A file
is read and written at offsets alike, and the error both directions report is one type, so it is
declared once, here, and the two traits reach it.

**The error is the implementation's, not the library's.** A file reports an `IoError`, a codec's
own store reports the codec's error, and a slice in memory reports `MemoryError` -- nothing is
squeezed through a common enum that would have to name every way every store can fail. A generic
function bounded on `ReadAt` hands `S::Error` back unchanged; one that wants its own error back
through `?` takes a `*ReadAt[Error = E]` instead, which fixes the type.

`extent` is "size" under another name because `File` already has an inherent `size` answering a
`long`, and a trait member may not share a name with an inherent one.

| Member | Signature | Description |
|---|---|---|
| `extent` | `extent(*self) -> Result[u64, Self::Error]` | How many bytes the store holds -- the offset a read reaches the end at. |

### `ReadAt`

```sysl
trait ReadAt: Positioned
    read_at(*self, offset: u64, into: []u8) -> Result[[]const u8, Self::Error]
```

Reading at an offset, without a position to move.

`read_at` fills a prefix of `into` with the bytes starting at `offset` and **hands that prefix
back**, as `Reader.read` does, so the count and the bytes cannot disagree. It may answer short
whenever it likes -- a device that reads a sector at a time, a store that hands over what one call
reaches -- and an **empty** answer to a non-empty request means `offset` is at or past the end.
Past the end is not an error: it is how a caller finds out where the end is.

`read_exact_at` is the loop a caller would otherwise write round a short answer.

| Member | Signature | Description |
|---|---|---|
| `read_at` | `read_at(*self, offset: u64, into: []u8) -> Result[[]const u8, Self::Error]` |  |

### `Reader`

```sysl
trait Reader: Fallible
    read(*self, into: []u8) -> []const u8
```

The input half of the byte surface, and the line cursor over it.

A module of its own rather than part of `sysl`, because nothing the language desugars onto reads:
a program that never takes input never needs one of these names, and one that does says so with an
`import`. The output half stays where it is for the opposite reason -- `print` is a keyword, and
what it desugars onto cannot be behind an import a program has not written.

`Reader` is here for the same reason `Writer` is: a program that reads has to name what it reads
from, and naming it once is what keeps two mechanisms -- and so two buffers, and bytes arriving in
the wrong order -- from growing. It is `Writer` turned around, down to the `Fallible` latch both
require, so a source that cannot fail says nothing about failing.

**The latch is required rather than declared**, and that is what lets one type be a reader and a
writer at once: a trait's members become the implementing type's, so two traits each declaring a
`failed` could not both be implemented for one file. Sharing `Fallible` is what makes an open
file writable at all (`sysl.fs`).

`read` hands back **the prefix of `into` that was filled** rather than a count, because a slice
already *is* a count and a pointer: the length is `got.len` and there is no way to be handed one
and forget to apply it to the other. Returning empty says end of input and nothing more -- whether
input *ended badly* is what `failed` is for, and the two questions are separate because a caller
that does not care should not have to ask.

**A TERMINAL DOES NOT END A LINE THE WAY A FILE DOES, and the failure used to be silent.** A
terminal sends a bare `\r` when Enter is pressed, so a cursor splitting on `\n` never sees a line
at all: it waits forever, prints nothing, and looks hung, with no diagnostic to grep for, because
nothing has gone wrong -- the line has not ended.

**`console_lines` is the answer, and `lines` is unchanged.** The policy is the caller's because
the right one is a property of where the bytes came from, which the library cannot know: a pipe
carrying `\r` in the middle of a line means a `\r`, and a console pressing Enter means a line. So
a file or a pipe keeps LF, and a console asks for CR, LF and CRLF alike.

**Echo and editing are not covered here, and they are the rest of what a console needs.** A
terminal in raw mode shows nothing as it is typed, and with no echo a mistake cannot be corrected.
Neither is a line cursor's business -- one is about the *terminal* and the other about the *line*
-- and both still have to come from somewhere. That somewhere is `sysl.term.edit`, which is this
trait's other consumer: it edits where nothing else will, and answers whole lines the same way a
`Lines` does -- an `Iterate` whose `Item` is `string` -- so the two are interchangeable at a call
site, as a `*Iterate[string]`. Which of them a
program wants is decided by `sysl.posix.tty.raw` on a host, and by there being no line discipline
at all on a board.

| Member | Signature | Description |
|---|---|---|
| `read` | `read(*self, into: []u8) -> []const u8` |  |

### `WriteAt`

```sysl
trait WriteAt: Positioned
    write_at(*self, offset: u64, bytes: []const u8) -> Result[usize, Self::Error]
    sync(*self) -> Result[unit, Self::Error]
```

Writing at an offset, without a position to move.

`write_at` writes a prefix of `bytes` at `offset` and answers how many it took. It may take fewer
than it was given, but **a store that can take nothing answers an error** rather than `Ok(0)`, since
a caller retrying a write that made no progress would retry for ever -- the contract
`write_all_at` relies on. An empty `bytes` takes nothing and is `Ok(0)` anywhere.

`sync` makes what was written durable -- a file's `fsync`, a flash controller's commit. Its default
says there is nothing to do, which is true of memory and of any store whose writes land at once.

| Member | Signature | Description |
|---|---|---|
| `write_at` | `write_at(*self, offset: u64, bytes: []const u8) -> Result[usize, Self::Error]` |  |
| `sync` | `sync(*self) -> Result[unit, Self::Error]` |  |

## Implementations

### Fallible for BytesReader

```sysl
impl Fallible for BytesReader
```

Nothing here can fail: the bytes are already in hand, and running out of them is the end of the
input rather than a fault. The block is still written, because a required trait is required
whether or not there is anything to say -- `Fallible.failed` has a default answer and no default
**membership**.

### Fallible for BytesWriter

```sysl
impl Fallible for BytesWriter
```

Memory does not go away, so there is nothing to latch. As above, the block is membership rather
than content.

### Fallible for FdReader

```sysl
impl Fallible for FdReader
```

### Iterate for Lines

```sysl
impl Iterate for Lines
```

### Positioned for BytesAt

```sysl
impl Positioned for BytesAt
```

### Positioned for SliceAt

```sysl
impl Positioned for SliceAt
```

### ReadAt for BytesAt

```sysl
impl ReadAt for BytesAt
```

### ReadAt for SliceAt

```sysl
impl ReadAt for SliceAt
```

### Reader for BytesReader

```sysl
impl Reader for BytesReader
```

### Reader for FdReader

```sysl
impl Reader for FdReader
```

### WriteAt for SliceAt

```sysl
impl WriteAt for SliceAt
```

### Writer for BytesWriter

```sysl
impl Writer for BytesWriter
```
