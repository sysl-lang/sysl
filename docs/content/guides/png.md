---
title: png
summary: The byte level — three byte orders, four length units, two checksums, and a format someone else defined.
weight: 40
---

A PNG reader: the chunk layer, the checksum, the filters, and the pixels underneath them — including
its own `inflate`.

**The axis: the byte level.** Everything here is arithmetic on bytes somebody else laid out. A PNG is
a signature, then a sequence of chunks, each one a big-endian length, a four-letter name, that many
bytes, and a CRC of the name and the bytes together. The pixels are in the chunks named `IDAT`,
concatenated and then deflated; what comes out is not the image but one **filter byte** per row
followed by a row of differences, and the image is what is left after each row is reconstructed from
the row above it and the pixel to its left.

Which is to say: three separate byte orders, four separate length units — bytes, samples, pixels,
rows — and a checksum over each of the two layers. Being wrong by one anywhere in that produces an
image, just not the right one. That is what the problem is for.

## What it exercises

**Naming what a file can be wrong about.** A reader of somebody else's format needs a lot of error
cases, and naming them individually is the difference between "this file is broken" and a report that
says where and what. That is an enum with a variant per failure, carried in a
[`Result`](/reference/errors/) — the shape the language pushes you toward, and the one a byte-level
reader wants anyway.

**`unit` as a payload.** This program and [bytecode](/guides/bytecode/) have nothing in common and
both paid for the same missing thing: a fallible step that yields no value. Two independent programs
reporting one absence is what moved it from a nuisance to a language change, and it is the clearest
example in the set of why findings are written down rather than worked around.

**The one piece of mathematics is the Paeth predictor**, and the magnitude it needs comes from
[`sysl.math`](/library/math/) rather than from a private helper this file writes. The integers are an
open family, so `Signed` is a trait the compiler supplies membership for at **every** width — which
is what stops a byte-level program from having to write its own `abs` for the width it happens to be
using.

## Worth noticing

The program carries its own fixtures rather than reading a file, so the whole thing is checkable with
no filesystem and no [`os` capability](/reference/modules/). That is not incidental to a guide
program: the set has to keep passing across compiler changes, and a test that needs a file on disk is
a test that fails for a reason unrelated to the language.

---

[Source](https://github.com/edadma/sysl/tree/dev/guide/png) ·
Next: [fft](/guides/fft/) — a type the program defined, and floating point.
