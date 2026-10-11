---
title: "Library API"
weight: 45
layout: api-index
headingShift: 0
slugStyle: github
---

These pages are generated from the library's own doc comments: every declaration, with its signature and nothing else. The [written library section](/library/#the-library-is-a-tree-and-the-tree-is-the-point) is the other half — what each module is *for*, why its pieces are shaped as they are, and worked examples you can run. Start there to learn a module; come here when you know what you want and need its exact signature.

## Modules

| Module | Summary |
|---|---|
| [`sysl`](sysl/) | The core: what every program has without importing anything. |
| [`sysl.args`](sysl-args/) | How a program's arguments become a `[]string`, written here because every line of it is ordinary sysl. |
| [`sysl.buf`](sysl-buf/) | The growable sequence and the sink built on it. |
| [`sysl.container`](sysl-container/) | Five containers, each with a shape a slice and a `Buf` cannot give you. |
| [`sysl.container.ring`](sysl-container-ring/) | A queue of a **fixed** capacity, laid over storage the caller supplies, cheap to take from at either end and needing no allocator at all. |
| [`sysl.crypto`](sysl-crypto/) | Cryptographic hashing: the SHA-2 family, SHA-1 and MD5, HMAC over any of them, and PBKDF2. |
| [`sysl.dma`](sysl-dma/) | Memory a device can reach by address, and the handover that makes it safe to share with one. |
| [`sysl.encoding`](sysl-encoding/) | Bytes as text and text as bytes: hexadecimal, base64, UUIDs, and fixed-width integers at either byte order. |
| [`sysl.env`](sysl-env/) | Reading the environment a program was started with. |
| [`sysl.env.hosted`](sysl-env-hosted/) |  |
| [`sysl.env.sys`](sysl-env-sys/) |  |
| [`sysl.fmt`](sysl-fmt/) | What an `f"…"` hole's computed width or precision comes to — `${x}%.${n}f`, `${s}%${w}s` — and what an integer under a specifier renders as — `${n}%08x`. |
| [`sysl.fs`](sysl-fs/) | What is at the end of a path: reading and writing whole files, metadata, directories, links, and the errors any of it can answer with. |
| [`sysl.fs.errtext`](sysl-fs-errtext/) |  |
| [`sysl.fs.hosted`](sysl-fs-hosted/) |  |
| [`sysl.fs.sys`](sysl-fs-sys/) |  |
| [`sysl.harness`](sysl-harness/) | A test framework that runs on the target. |
| [`sysl.io`](sysl-io/) | The byte surface a program reads through, and the line cursor over it. |
| [`sysl.io.hosted`](sysl-io-hosted/) |  |
| [`sysl.io.sys`](sysl-io-sys/) |  |
| [`sysl.log`](sysl-log/) | Structured logging: a level, a message, a few named fields, and somewhere for them to go. |
| [`sysl.math`](sysl-math/) | Mathematics on the numeric types: the float functions, the integer ones, and the traits that let a program write both at whichever width it is already using. |
| [`sysl.math.bigint`](sysl-math-bigint/) | Integers with no width: sign and magnitude, the magnitude a run of 32-bit limbs. |
| [`sysl.math.complex`](sysl-math-complex/) | Complex numbers, at whichever floating-point width the program is already using. |
| [`sysl.math.decimal`](sysl-math-decimal/) | Exact decimal arithmetic: an integer coefficient and a scale, so `0.1 + 0.2` is `0.3`. |
| [`sysl.math.rational`](sysl-math-rational/) | Exact fractions: a numerator and a denominator, both `BigInt`s, so a third is a third. |
| [`sysl.net`](sysl-net/) | Blocking sockets -- a TCP stream, a UDP socket -- and the names a host resolves to. |
| [`sysl.net.hosted`](sysl-net-hosted/) |  |
| [`sysl.net.sys`](sysl-net-sys/) |  |
| [`sysl.path`](sysl-path/) | Path handling that is decided by the string alone. |
| [`sysl.posix.rand`](sysl-posix-rand/) | A seed taken from the host, for a program that wants a different sequence on every run. |
| [`sysl.posix.threads`](sysl-posix-threads/) | `sysl.threads` under the name it had when its threads were pthreads, kept for the programs that import it. |
| [`sysl.posix.time`](sysl-posix-time/) | Reading the two clocks the host keeps, which is the one thing `sysl.time` deliberately cannot do. |
| [`sysl.posix.tty`](sysl-posix-tty/) | Whether escapes should be written at all. |
| [`sysl.process`](sysl-process/) | Starting another program and waiting for what it does. |
| [`sysl.process.hosted`](sysl-process-hosted/) |  |
| [`sysl.process.sys`](sysl-process-sys/) |  |
| [`sysl.rand`](sysl-rand/) | Pseudo-random numbers: a named, seedable, reproducible generator, and the distributions that are easy to get wrong. |
| [`sysl.regex`](sysl-regex/) | Matching text against a pattern: compile one, then find, replace or split with it. |
| [`sysl.seq`](sysl-seq/) | What a sequence of values can be asked, in one trait, so that a slice and a `Buf` answer the same names. |
| [`sysl.signal`](sysl-signal/) | Signals as flags: a program asks to be told that one arrived, and asks again later whether it has. |
| [`sysl.signal.hosted`](sysl-signal-hosted/) |  |
| [`sysl.signal.sys`](sysl-signal-sys/) |  |
| [`sysl.slices`](sysl-slices/) | Operations over a built-in slice, and the pointer a C binding needs to hand one across. |
| [`sysl.softfloat`](sysl-softfloat/) |  |
| [`sysl.sync`](sysl-sync/) | What two threads may touch at once: `Atomic[T]`, `SpinLock`, and the five memory orderings. |
| [`sysl.sys`](sysl-sys/) | The platform seam: everything the library asks of what it is hosted on, and nothing else. |
| [`sysl.term`](sysl-term/) | What a terminal understands: the escape sequences that colour text, emphasise it, and move the cursor about. |
| [`sysl.term.edit`](sysl-term-edit/) | Reading a line from a terminal that will not do it for you. |
| [`sysl.testing`](sysl-testing/) | What a test may say about itself while it runs, beyond passing or failing. |
| [`sysl.text`](sysl-text/) | The whole text surface: what a `string` is made of, and every operation over one. |
| [`sysl.threads`](sysl-threads/) | Threads of execution, and the two things a program does with one: start it, and wait for it -- with the lock, the condition variable and the channel threads share things through. |
| [`sysl.threads.hosted`](sysl-threads-hosted/) |  |
| [`sysl.threads.sys`](sysl-threads-sys/) |  |
| [`sysl.time`](sysl-time/) | Points on the timeline, lengths of it, and the calendar that turns one into a date. |
| [`sysl.time.tzif`](sysl-time-tzif/) | Reading a zone out of the bytes of a TZif file (RFC 8536), which is what the IANA time zone database is distributed as. |
| [`sysl.tty`](sysl-tty/) | A terminal's mode: line by line or a keystroke at a time, echoing or not, and putting back what was there. |
| [`sysl.tty.hosted`](sysl-tty-hosted/) |  |
| [`sysl.tty.sys`](sysl-tty-sys/) |  |
| [`sysl.unicode`](sysl-unicode/) | What the Unicode Character Database says about a character, and the operations over text that only that database can answer. |
