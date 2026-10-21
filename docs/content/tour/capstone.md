---
title: A program that reads its input
summary: The whole tour at once — a trait implemented, a cursor walked, and one line to swap the source.
weight: 130
---

This program uses most of what the tour has covered to do one job: count the lines, words and bytes
of some text, here a short access log, and report the longest line.

```sysl
import sysl.io.{Reader, lines}

// A source of bytes over storage the program already has.
struct SliceReader
    src: []const u8
    at: usize

// `Reader` requires `Fallible`, whose one member has a default — so this line
// is the whole of the implementation, and it says "reading this cannot fail".
impl Fallible for SliceReader

impl Reader for SliceReader
    read(*self, into: []u8) -> []const u8
        var left = self.src.len - self.at
        var n = if left < into.len then left else into.len

        for i in 0..<n do into[i] = self.src[self.at + i]

        self.at += n
        into[0..<n]

struct Stats
    lines: int
    words: int
    bytes: int
    longest: string
    invariant lines >= 0

words_in(s: string) -> int
    var n = 0
    var inside = false

    for b in s.bytes
        if b == 32 then inside = false
        elif !inside
            n += 1
            inside = true

    n

tally(src: *Reader) -> Stats
    var st = Stats(0, 0, 0, "")

    for line in lines(src)
        st.lines += 1
        st.words += words_in(line)
        st.bytes += int(line.len) + 1

        if line.len > st.longest.len then st.longest = line

    st
end tally

var sample = """
    GET /index.html 200
    GET /static/app.js 200
    POST /login 401
    """

var r = SliceReader(sample.bytes, 0)
var st = tally(&r)

print("lines:", st.lines, "words:", st.words, "bytes:", st.bytes)
print("longest:", st.longest)
```

```output
lines: 3 words: 9 bytes: 59
longest: GET /static/app.js 200
```

The program is about sixty lines and draws on most chapters of the tour. The sections below go
through what each part relies on.

## The reader is a trait implementation

`Reader` requires one method, which fills a buffer and reports what it put there, and `SliceReader`
implements it over bytes the program already holds. The rest of the program does not know which kind
of source it has.

`read` returns a **view of the caller's buffer** rather than a count. A `[]const u8` already carries
both a pointer and a length, so the caller cannot receive one and forget to apply it to the other. An
empty result means the input has ended.

`into[0..<n]` is a `[]u8` returned as a `[]const u8`, the conversion the arrays chapter described:
giving up write access is always allowed.

The bare `impl Fallible for SliceReader` line is a **required trait** in use. `Reader` requires
`Fallible`, so implementing one requires the other, and because `Fallible`'s single member has a
default, there is nothing to write in the block. Whether a stream *ended* and whether it ended
*with an error* are separate questions: an empty result answers the first, and `failed` answers the
second. A source that cannot fail does not need to say so.

## The tally takes a trait object

`tally(src: *Reader)` uses the dynamic side of traits: one copy of the code, dispatching through the
method table, so it works with any source. `&r` at the call site erases the `SliceReader` into a
`*Reader`, a two-word fat pointer that needs no allocation, so the same design works in a kernel.

A bound would also work. `tally[R: Reader](src: *R)` would be monomorphized, with a direct call for
each source type. A trait object fits better here because the program is designed to make the source
interchangeable.

## The loop iterates over a cursor

`lines(src)` returns a cursor that implements `Iterate`, and `for` iterates over anything that does.
The loop therefore reads one line at a time from a 4 KiB chunk instead of loading the whole input
into memory, and it is written exactly like a `for x in xs` over an array.

Each `line` is a `string`, so it is guaranteed to be well-formed UTF-8 and `line.len` is its length in
bytes. `st.longest = line` makes no copy: a string is three words that share their bytes, so keeping
the longest line retains only the chunk it came from.

## The struct keeps a rule

`invariant lines >= 0` is checked at construction **and at every field write**, including
`st.lines += 1`, which as a compound assignment is checked the same way. If a later change made the
count go backwards, the program would stop at the write that broke the rule, rather than later where
a negative line count caused a problem.

`words_in` is the only function that processes bytes directly, and it does so over `s.bytes`, the
string's storage as a read-only view, with no allocation, decoding or copying.

## Swapping the source

Because the program is written against a trait, reading its real input takes only this change:

```sysl
import sysl.io.{stdin, lines}

var src = stdin()
var st = tally(&src)

print("lines:", st.lines, "words:", st.words, "bytes:", st.bytes)
```

`stdin()` returns an `FdReader`, which implements the same `Reader` over a file descriptor. `tally`
and everything it calls are unchanged. A freestanding target without file descriptors supplies one
function body, a `read` system call, and everything above it stays the same.

The standard library is built the same way, and this design is the most useful thing to take from the
tour: the boundary is a trait with one method, the code on each side is ordinary sysl, and nothing
in between needs to know which implementation it is using.

## Where to go from here

You have now seen the whole language. The rest is detail:

- **The [reference](/reference/)** is the specification. It covers the same material arranged for
  lookup: each construct described once, in its own place, with the complete rules and every edge
  case shown as a program. The tour teaches in the order the features are easiest to learn; the
  reference answers specific questions, such as what may follow `for`, what `?` does to a `&T`
  payload, or which slice forms exist.
- **The [library](/library/)** documents each module listed in [modules](/tour/modules/), one page
  each, including what a `no alloc` program may use.
- **The [packages](https://github.com/sysl-lang)** show the language in use: a PNG reader, a fast
  Fourier transform, a slab allocator, and bindings to SQLite, SDL3 and OpenSSL, all ordinary sysl
  using the features this tour covered.
- **The standard library's source** is the best worked example available. `Buf`, `StrBuilder` and the
  `Reader` above are ordinary sysl built from the same features; there is no privileged layer beneath
  them.
