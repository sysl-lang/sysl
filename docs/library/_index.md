---
title: Standard Library
summary: What ships with the compiler, module by module — the core every program has, and the layers a target may not.
weight: 40
---

The [reference](/reference/) is the *language*: what the compiler reads, what a type is, what a
declaration binds. This section is the other half of what a program has — **the modules that ship
with the compiler**, and what each of them offers.

They are kept apart on purpose. Nothing in this section is a language feature: every type here is an
ordinary struct or enum, every function is ordinary sysl, and a program could have written any of it.
`Option` is a generic enum, `unwrap` is a member that calls `exit`, `print` is a library function
reached by a desugaring. The line matters because it is the language's own rule about itself —
**there are no functions built into the compiler that a program could not have written**, and the one
exception is the seam out to C.

So a page in the reference tells you what the compiler will accept. A page here tells you what
somebody already wrote for you, and where it will not be there.

## The library is a tree, and the tree is the point

`sysl` is one module with submodules under it, and each is a directory. A program reaches the core
without asking; everything below it is [imported](/reference/modules/) by name.

| module | holds | requires |
|---|---|---|
| [`sysl`](/library/core/) | the core — `Option`, `Result`, `Display`, the operator traits, `print`, `assert`, and [`exit`](/library/core/#choosing-an-exit-status) for a program that chooses its own status | — |
| [`sysl.text`](/library/text/) | the whole text surface — validation, the character cursors, `Ascii` and `Search`, splitting and joining, `StrBuilder`, the parsers, `CString` | — |
| [`sysl.unicode`](/library/unicode/) | the Unicode Character Database — case mapping that answers for `é`, [`fold`](/library/unicode/#case-mapping-is-a-character-at-a-time-and-folding-is-not) as the comparison key, the four [normal forms](/library/unicode/#normalization-and-why-comparing-needs-it), [grapheme clusters](/library/unicode/#a-grapheme-cluster-is-the-third-answer-to-how-long-text-is), and `General_Category` | — |
| [`sysl.regex`](/library/regex/) | POSIX Extended Regular Expressions — `regex`, `Regex`, `Match` | — |
| [`sysl.buf`](/library/buf/) | `Buf[T]`, the growable sequence, and `ByteSink` | — |
| [`sysl.container`](/library/container/) | five containers — [`Map`](/library/container/#the-map) and [`Set`](/library/container/#the-set) over one flat probe table, [`Deque`](/library/container/#the-queue-at-both-ends), [`Heap`](/library/container/#the-priority-queue), and an immutable [`List`](/library/container/#the-immutable-list) that shares its tail | — |
| [`sysl.container.ring`](/library/ring/) | `Ring[T]`, a queue of a **fixed** capacity over storage the caller supplies — no allocator, a full ring that answers rather than grows, and [`overwrite`](/library/ring/#overwrite-is-what-makes-it-a-ring-rather-than-a-bounded-queue) for a window over an unbounded stream | — |
| [`sysl.io`](/library/io/) | `Reader`, `stdin()`, `lines()` and `console_lines()`, and the in-memory `bytes_reader()` / `bytes_writer()` | — |
| [`sysl.path`](/library/path/) | a path by the string alone — `join`, `parent`, `file_name`, `extension`, `stem`, `normalize`, `relative_to`. It opens nothing | — |
| [`sysl.fs`](/library/fs/) | what is at the end of a path — `read_text`, `write_bytes`, `metadata`, the links, `make_dir_all`, `copy_file`, `canonicalize`, and `IoError` | `os` |
| [`sysl.math`](/library/math/) | `max`, `min`, `pi`, the float functions, the integer traits `Signed` and `Bits`, [`Magnitude`](/library/math/#magnitude-how-big-when-that-is-not-which-is-greater) and the size a type measures in, and the integer arithmetic above them — `pow`, `gcd`, `lcm`, `divmod`, `is_power_of_two`, `next_power_of_two` | — |
| [`sysl.math.complex`](/library/complex/) | `Complex[F: Float]` — the operators at two argument lists each, the transcendental set, and the branch cuts | — |
| [`sysl.math.bigint`](/library/bigint/) | `BigInt` — an integer with no width: sign and magnitude over 32-bit limbs, the five operators, `pow`, and text in [any base from 2 to 36](/library/bigint/#text-in-any-base-from-2-to-36) | — |
| [`sysl.math.decimal`](/library/decimal/) | `Decimal` — exact decimal arithmetic, so a tenth and a fifth make three tenths: a coefficient and a [scale that is part of the value](/library/decimal/#the-scale-is-part-of-the-value), and a division that [takes a rounding mode](/library/decimal/#division-takes-a-scale-and-a-rounding-mode-because-it-cannot-be-exact) | — |
| [`sysl.time`](/library/time/) | `Instant` and `Duration` — with `5.ms` and `5.hours` on any integer — the civil calendar — `LocalDate`, `LocalTime`, `LocalDateTime`, `Offset` — the fixed-offset conversions, `resolve` for a zone whose clocks move, and the ISO 8601 renderers and parsers — and [`now`/`monotonic`](/library/time/#the-portable-spelling-sysl-time-now), which read a clock the linker supplies rather than one this module knows about | — |
| [`sysl.time.tzif`](/library/time/#decoding-a-zone) | a zone decoded from the bytes of a TZif file — no copy, no allocator, and no filesystem | — |
| [`sysl.posix.time`](/library/time/#reading-a-clock-sysl-posix-time) | the two clocks the host keeps — `now` for a wall reading, `monotonic` for measuring — the zone it is set to, and the zone database by name | `posix` |
| [`sysl.env`](/library/env/) | the environment a program was started with — `get`, `get_or`, `is_set`. Reading only | `os` |
| [`sysl.process`](/library/process/) | starting another program and waiting for it — `run`, `capture`, `Status`, a timeout so a child that never ends cannot hold its caller, and no shell anywhere in it | `posix` |
| [`sysl.posix.net`](/library/net/) | blocking TCP — `resolve`, `socket`, `bind`, `listen`, `accept`, `connect`, `send`, `recv`, `shutdown`, `close`, and a timeout so a blocking call cannot wait forever | `posix` |
| [`sysl.sync`](/library/sync/) | `Atomic[T]`, `SpinLock`, and the five memory orderings | — |
| [`sysl.posix.threads`](/library/threads/) | `spawn`, `Thread.join`, `yield_now`, `Mutex[T]`, and `Channel[T]` — the bounded queue two threads hand values across | `posix` |
| [`sysl.term`](/library/term/) | the escape sequences a terminal understands — colour, emphasis, and the screen | — |
| [`sysl.posix.tty`](/library/term/#whether-to-write-escapes-at-all-sysl-posix-tty) | whether to write them at all — `is_tty`, `color_wanted`, `color`, `color_err` — and taking the terminal over: `raw`, `cooked`, `flush`, `tty_writer` | `posix` |
| [`sysl.term.edit`](/library/term/#reading-a-line-sysl-term-edit) | a line editor for a terminal with no line discipline — echo, editing, history, over a `Reader` and a `Writer` | — |
| [`sysl.slices`](/library/slices/) | what a program does *to* a `[]T` — searching, comparing, `reverse`, `copy`, two sorts that neither allocate, `binary_search`, and `as_ptr` for a C binding | — |
| [`sysl.seq`](/library/seq/) | what a program asks *of* a sequence — `map`, `filter`, `fold`, `any`, `all`, `find`, `position`, `count_where`, `each`, `flat_map`, on a slice or a `Buf` alike | — |
| [`sysl.encoding`](/library/encoding/) | hexadecimal and base64 both ways, fixed-width integers to and from bytes at either byte order, and `DecodeError` | — |
| [`sysl.crypto`](/library/crypto/) | the SHA-2 family, SHA-1 and HMAC over any of them — digests as bytes, a hasher that streams, and no allocator | — |
| [`sysl.rand`](/library/rand/) | PCG32, seeded by the caller and reproducible — `below` without modulo bias, `range`, `unit`, `shuffle` | — |
| [`sysl.posix.rand`](/library/rand/#taking-a-seed-from-the-host-sysl-posix-rand) | `seed_from_os` and `entropy_from_os`, kept apart so the generator stays freestanding | `posix` |
| [`sysl.args`](/library/args/) | command-line options — `Scan`, `Cli`, and `args_of` for a raw `argv` | — |
| [`sysl.log`](/library/log/) | structured logging — a `Record`, a `Sink` of one method, `text` and `json` renderings, and a threshold that [costs a relaxed load](/library/log/#the-threshold-is-an-atomic-and-the-sink-is-under-a-lock) on a filtered call | — |
| [`sysl.harness`](/library/harness/) | a test framework that runs **on the target** — `run`, `check`, `check_eq`, `check_slice_eq`, `skip`, and a tally | — |
| [`sysl.sys`](/library/sys/) | the platform seam — what a freestanding target replaces | — |

**The split is by capability, not by taste**, and the namespace is the column written into the path.
`sysl.fs` is `requires os`, because a filesystem is something the environment either has or does not
— and files exist on operating systems that are not POSIX, which is why it is the one gated module
that does *not* sit under `sysl.posix`. **Everything under `sysl.posix` is `requires posix` and
nothing else**: threads because pthreads is what they are, `tty` because `isatty` and `termios` are,
`rand` because entropy comes from the kernel, `time` because `clock_gettime` is a call into
it. So a module a target cannot support is not one that
fails to link — it is one a [capability clause](/reference/modules/) will not let that program import
in the first place, and now one you can spot by its name.

**`sysl.path` is the same rule one step further out**, and the clearest instance of it: not a
submodule of `sysl.fs` but a module beside it, because it requires **nothing**. It touches no
syscall — it is string manipulation whose rules happen to be per-platform — so one `getcwd` filed
next to `join` would have taken the whole of path handling away from every program that has no
operating system to ask. What that buys is concrete rather than tidy: a module loader can resolve an
import and then say *which file* it could not read, which is only writable because the resolution
never touched the disk.

It is also why `sysl.posix.tty`, `sysl.posix.rand` and `sysl.posix.time` are modules of their own
rather than functions in `sysl.term`, `sysl.rand` and `sysl.time`. **A requirement is module-wide**,
so one function asking for `posix`
beside the escape sequences would have taken all forty constants away from the allocator-free programs
that most want to colour a line; one asking for it beside the generator would have taken PCG32
away from every target that has no operating system to seed it from; and one asking for it beside
`Instant` would have taken the whole civil calendar away from a program that only wanted to add two
durations. Three instances of one shape, and
the shape is worth naming: where a module is portable except for how it gets started, the *getting
started* goes in a submodule.

That is why the atomics live apart from the threads: `sysl.sync` requires **nothing**, so a kernel
can have a spinlock without acquiring a scheduler along with it.

**`alloc` is not in that column, and the omission is the point.** No module requires it, because
allocation is refused at the **call** rather than at the import — so a program under `no alloc` still
imports `sysl.text` and still gets `from_utf8`, the cursors, and `Search`, and is refused only where
it reaches for `join` or a `StrBuilder`. A capability that gated whole modules would have cost the
allocator-free subset most of the library it can actually use.

## What belongs here, and what is a package

**The library is what a program cannot get on with the language without.** That is the whole rule, and
it is a principle rather than a list because a list goes stale the first time somebody adds a module
and answers nothing about the next one.

Read as a question to ask of a candidate: **can a program take part in the language without it?**

- **Yes, and it is a domain** — matrices, an FFT, a JSON parser, a QR encoder, a physics engine.
  Those are **packages**, fetched by coordinate. They are not lesser; they are simply not what
  *everybody* has to have, and a standard library that carried one carries an opinion about what
  everybody is writing.
- **No, because the language desugars onto it** — `Option` and `Result` for `?`, `Display` and the
  `print` family for `print`, `Iterate` for `for … in`, the operator traits for `+`, `Drop` for a
  destructor, `From` for a `?` across two error types. These are in `sysl` itself, unimported, because
  a program that had to name them would be naming part of the language.
- **No, because it is the platform** — the filesystem, the clock, the environment, threads, the
  terminal, starting another process. These are modules a program **imports**, and they are here
  rather than in packages because there is one right answer per operating system and every program
  that wants one wants the same one.
- **No, because all code touches it** — text, slices, sequences, containers, hashing, formatting,
  encoding. The line here is the vaguest of the four and the test is the same: a program that avoided
  `sysl.text` would be writing UTF-8 validation, which is not a domain, it is a tax.

**The `display_*` renderers are the boundary case, and they sharpen the rule rather than escaping
it.** No desugaring names one, so the letter of the first test puts them in a `sysl.fmt` — and the
split was tried and works. They stay in `sysl` because **a program writing `impl Display` is not
reaching for a library; it is implementing a language feature**, and the renderers are the vocabulary
that contract is written in. A program that could not write its `display` body without an import has
been asked to name part of the language.

**Two things have left for being domains**, which is the rule working rather than a reversal: matrices
and vectors went out to [`linalg`](https://github.com/sysl-lang/linalg) and the FFT to
[`fft`](https://github.com/sysl-lang/fft). Both were leaves — nothing else in the library imported
them — and both were a *subject* in a library otherwise made of the platform and of things all code
touches. `sysl.math.complex` stays, and the reason generalises: standard libraries across languages
carry the complex **type** and leave the algorithms over it to packages.

**What "complete" means here is that the four answers above are covered, not that the list is long.**
A module added because it would be useful, rather than because one of the four asks for it, is a
package that has not been written yet.

## Where some of this already is

Three pieces of the library are documented in the reference instead, because the language has
machinery that only makes sense beside them:

- **`Option`, `Result` and the `Fallible` latch** are on [errors and
  contracts](/reference/errors/), because `?` is a language form and it is what those types are for.
- **`assert` and `panic`** are on [attributes and compile time](/reference/attributes/), beside the
  `@test` protocol they exist to serve.
- **The operator traits** — which trait a `+` or a `<` reaches — are on
  [expressions](/reference/expressions/), because dispatch is a rule about the operator rather than
  about the trait.

This section links to them rather than repeating them.
