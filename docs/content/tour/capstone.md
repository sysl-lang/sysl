---
title: A program that reads its input
summary: The whole tour at once — a trait implemented, a cursor walked, and one line to swap the source.
weight: 130
---

Here is everything the tour has covered, doing one job: count the lines, words and bytes of some
text, and report the longest line.

```sysl
import sysl.io.{Reader, lines}

// A source of bytes over storage the program already has.
struct SliceReader
    src: []const u8
    at: usize

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
        if b == 32u8 then inside = false
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
    the quick brown fox
    jumps over
    the lazy dog
    """

var r = SliceReader(sample.bytes, 0usize)
var st = tally(&r)

print("lines:", st.lines, "words:", st.words, "bytes:", st.bytes)
print("longest:", st.longest)
```

```output
lines: 3 words: 9 bytes: 44
longest: the quick brown fox
```

Sixty lines, and almost every chapter of the tour is in there. Worth walking through what each part is
leaning on.

## The reader is a trait implementation

`Reader` asks for one method — hand me a buffer, tell me what you put in it — and `SliceReader`
answers it over bytes the program already has. Nothing about the rest of the program knows which kind
of source it got.

Notice the return type. `read` hands back a **view of the caller's own buffer**, not a count, and
that is the shape rather than an accident: a `[]const u8` already *is* a count and a pointer, so there
is no way to be handed one and forget to apply it to the other. An empty result means the input
ended.

`into[0..<n]` is a `[]u8` flowing into a `[]const u8` return type, which is the widening the arrays
chapter described — giving up the ability to write is a promise the callee can always make.

## The tally takes a trait object

`tally(src: *Reader)` is the dynamic side of the trait: one copy of the code, dispatching through the
table, so it works over any source at all. `&r` at the call site erases the `SliceReader` into a
`*Reader`, which is a two-word fat pointer and costs no allocation — which is why a kernel can use
this shape.

A bound would have worked too. `tally[R: Reader](src: *R)` would monomorphize, giving a direct call
per source type. The trait object is the right choice here because the point of the program is that
the source is interchangeable.

## The loop walks a cursor

`lines(src)` hands back a cursor implementing `Iterate[string]`, and `for` walks anything that does.
That means the loop reads a line at a time out of a 4 KiB chunk, rather than pulling the whole input
into memory — and it looks exactly like the `for x in xs` that walks an array.

Each `line` is a `string`, so it is guaranteed well-formed UTF-8 and `line.len` is its byte length.
`st.longest = line` costs no copy: a string is three words sharing its bytes, so keeping the longest
line means retaining the chunk it came out of and nothing more.

## The struct keeps a rule

`invariant lines >= 0` is checked at construction **and at every field write** — including
`st.lines += 1`, which is a compound assignment and owed the same re-check. If some later edit made
the count go backwards, the program would stop at the write that broke it rather than somewhere
downstream where a negative line count finally mattered.

`words_in` is the only piece doing real byte work, and it does it over `s.bytes` — the string's
storage as a read-only view. No allocation, no decoding, no copy.

## Swapping the source

The point of writing it against a trait is this diff, which is the entire change needed to read the
program's actual input:

```sysl
import sysl.io.{stdin, lines}

var src = stdin()
var st = tally(&src)

print("lines:", st.lines, "words:", st.words, "bytes:", st.bytes)
```

`stdin()` gives an `FdReader`, which implements the same `Reader` over a file descriptor. `tally` is
untouched, and so is everything under it. A freestanding target that has no file descriptors
substitutes one body — a `read` syscall — and the whole surface above it is unchanged.

That is the shape the standard library is built in, and it is worth taking away from the tour more
than any single feature: the seam is a trait with one method, the thing on either side of it is
ordinary sysl, and nothing in the middle had to be told which.

## Where to go from here

You have seen the whole language. What is left is depth:

- **The design chapters** in `docs/design/` are the specification — every rule in this tour is stated
  there with the argument for it, including the alternatives that were rejected and why.
- **The `guide/` programs** in the repository are complete, working programs at the size where the
  choices start to matter: a JSON parser, a scheduler, a SHA-2 implementation, a slab allocator, a
  ring buffer, a datetime library.
- **The standard library's own source** is the best worked example there is. `Buf`, `StrBuilder` and
  the `Reader` above are ordinary sysl over the same features this tour covered — there is no
  privileged layer underneath them.
