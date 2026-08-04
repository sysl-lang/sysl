---
title: The fs module
summary: "`sysl.fs` — files and paths, in three tiers; `IoError` and why it is an enum; and `requires os`, the capability that decides whether the module exists at all."
weight: 50
---

`sysl.fs` is the first module in this section that a target may simply **not have**. Its files open
with two lines rather than one:

```sysl
module sysl.fs
@requires(os)
```

Nothing here is language, and nothing here can be given a body on a target with no filesystem under
it: a freestanding image has no `fopen` to call and no `errno` to read. So the clause is not a
warning — it is a fact about which module exists, and it is checked at the **import**:

```sysl
@no_os

import sysl.fs.exists

print(exists("/tmp"))
```

```error
this reaches 'sysl.fs', which requires 'os', and this module declared 'no os' — an environment capability gates which modules exist, so a module that gave one up may not reach one that needs it
```

That is the difference between `os` and [`alloc`](/reference/modules/) worth holding on to. `alloc` is
checked on what a module *calls*, because the standard library has allocating and non-allocating
halves running through the middle of it. `os` is checked on the **module boundary**, because it
decides which modules exist at all — there is no half of `sysl.fs` that works without one.

## Three tiers, and the line between them is the allocator

| tier | names | what it costs |
|---|---|---|
| whole file | `read_text`, `read_bytes`, `write_text`, `write_bytes`, `append_text`, `append_bytes` | storage the size of the file — allocates by nature |
| open file | `open`, `create`, `append`, `open_update`, `create_update`, and `File`'s members | a buffer the caller already has |
| path | `exists`, `readable`, `writable`, `is_file`, `is_dir`, `size_of`, `make_dir`, `remove_file`, `remove_dir`, `rename` | one C call each |

**Reading a whole file *is* asking for storage the size of the file**, so the top tier could not have
been written any other way. Keeping it apart from `File` is what lets the middle tier stay honest:
opening and reading in chunks needs only a buffer the caller brought, and works in a module that must
not allocate.

## The whole-file tier

```sysl
import sysl.fs.{write_text, read_text, append_text, read_bytes, remove_file, exists}

var path = "/tmp/sysl-fs-doc-1.txt"

write_text(path, "hello\n").unwrap()

print(read_text(path).unwrap().len)

append_text(path, "again\n").unwrap()

print(read_bytes(path).unwrap().len)
prints(read_text(path).unwrap())

remove_file(path).unwrap()

print(exists(path))
```

```output
6
12
hello
again
false
```

Each of these **opens, does the one thing, and closes** — and closes through `defer`, so the handle is
released down the failing paths too. That is the idiom the module is written to demonstrate: the
language has no destructor, so a scope releases what the scope said it would.

**`read_bytes` reads in chunks and grows rather than sizing itself from the file's length first.**
That costs a little copying and buys the case that matters — a file being written while this reads it,
and anything whose length is not a fact until the read ends. The loop stops on the first empty read,
and asks *afterwards* whether the reading ended badly, which is the split
[`Reader`](/library/io/) was built around.

**Bytes that are not UTF-8 stop the program** rather than becoming an error case. That is deliberate:
answering with an error instead would put a case in `IoError` that no filesystem ever reports, and
would make every program reading its own configuration handle a failure that means its build is
broken. The severity is affordable because the layer underneath is public — a caller who would rather
inspect than trap reads `read_bytes` and validates it with
[`from_utf8`](/library/text/).

## `IoError`

```sysl
enum IoError
    NotFound
    PermissionDenied
    AlreadyExists
    NotADirectory
    IsADirectory
    DirectoryNotEmpty
    TooManyOpenFiles
    NoSpaceLeft
    Interrupted
    NotOpen
    Other(code: int)

    code(self) -> int
    message(self) -> string

impl Display for IoError
```

Every `Result` this module hands back carries one. **It is an enum rather than a bare `int` for the
reason `Result` carries its error as a type parameter at all**: a caller that wants to act on *why*
wants to match, and a match over named cases is exhaustive where a comparison against a number is a
guess that compiled.

**The cases are the ones a program branches on, and everything else arrives as `Other` carrying the
number.** A library that mapped every `errno` to a name of its own would be a table nobody could keep
current and a program could not extend — and the number is what a reader looks up anyway.

`code()` answers for every case, not only `Other`: the named ones hand back the code they were
recognised from, which keeps the question answerable without a second table. `message()` is a sentence
in the terms the operation was asked in rather than in C's, and the `Display` impl is what makes
`print(e)` say it:

```sysl
import sysl.fs.read_text

read_text("/tmp/sysl-fs-doc-missing.txt") match
    Ok(s) -> print("read", s.len)
    Err(e) -> print("refused:", e, e.code())
```

```output
refused: no such file or directory 2
```

**`errno` is read at the failure, not wherever the caller got round to asking.** Every call in the
module reports with a private `why()` that reads it on the spot, because there is nothing between the
two that could overwrite it — and `fclose` is a call like any other, which sets `errno` to whatever
*it* thought.

## `File`

```sysl
struct File
    closed(*self) -> bool
    flush(*self) -> Result[unit, IoError]
    close(*self) -> Result[unit, IoError]
    tell(*self) -> Result[long, IoError]
    seek(*self, to: long) -> Result[unit, IoError]
    size(*self) -> Result[long, IoError]
    at_end(*self) -> bool

impl Fallible for File
impl Reader for File
impl Writer for File

open(path: string) -> Result[File, IoError]          // an existing file, for reading
create(path: string) -> Result[File, IoError]        // makes one, or empties one that was there
append(path: string) -> Result[File, IoError]        // writes at the end
open_update(path: string) -> Result[File, IoError]   // read and write; fails if it is not there
create_update(path: string) -> Result[File, IoError] // read and write; empties what was there
```

**A `File` is C's buffered `FILE *`, not a file descriptor, and that is the load-bearing choice.** A
descriptor would mean `open(2)`, and `open(2)` means `O_CREAT`, `O_TRUNC` and `O_APPEND` — three
constants whose values differ between platforms and which nothing in the program could check.
`fopen` takes a **mode string**, which C standardises, so the same three intentions cross the boundary
as text that is right everywhere. The five functions above are named for what they *mean* rather than
for the mode string each becomes.

The buffering comes with it, and is the second reason: a `Writer` that reached the operating system
once per `write` would make rendering into a file cost a system call per fragment, and
[the rendering surface](/library/core/) writes in fragments.

What is given up is the descriptor itself. A program that needs one — to poll it, to hand it to a
child — should say `open(2)` for itself, which is what [`sysl.io`](/library/io/)'s `FdReader` is
already shaped for. The two live side by side on purpose: `Reader` is about bytes arriving, not about
where a program got its handle.

### It is a `Reader` and a `Writer`, whole

```sysl
import sysl.fs.{write_text, open, remove_file}
import sysl.io.lines

var path = "/tmp/sysl-fs-doc-3.txt"

write_text(path, "alpha\nbeta\ngamma\n").unwrap()

var f = open(path).unwrap()

for line in lines(&f)
    prints("[")
    prints(line)
    prints("]")

prints("\n")
print(f.failed(), f.at_end())

f.close().unwrap()
remove_file(path).unwrap()
```

```output
[alpha][beta][gamma]
false true
```

`lines(&f)` works over a file with nothing in `sysl.fs` knowing about lines, because `impl Reader for
File` is the trait unmodified. Everything that renders into a sink renders into a file for the same
reason.

**That is also why `Fallible` is a *required* trait rather than a member each of them declares.**
`failed` takes no arguments, so a `failed` from `Reader` and another from `Writer` would be two
members of one name on one type and a call could not say which it meant — on a file, which is both.
One shared latch leaves a single answer that each of them reaches.

Reaching a trait member still needs the trait in scope:

```sysl
import sysl.fs.open

var f = open("/tmp/sysl-fs-doc-3.txt").unwrap()
var window: [4]u8

f.read(window[..])
```

```error
sysl.fs.File has 'read' from sysl.io.Reader, and that trait is not in scope here — import it to reach the member
```

### Position and size

```sysl
import sysl.fs.{write_text, open, size_of, remove_file}
import sysl.io.Reader

var path = "/tmp/sysl-fs-doc-4.txt"

write_text(path, "0123456789").unwrap()

print(size_of(path).unwrap())

var f = open(path).unwrap()
var window: [4]u8

f.seek(6i64).unwrap()

var got = f.read(window[..])

print(f.tell().unwrap(), got.len, got[0])
print(f.size().unwrap())

f.close().unwrap()
remove_file(path).unwrap()
```

```output
10
10 4 54
10
```

**`size` is a seek to the end and back rather than a `stat`**, and that is the constraint the whole
module is written under: `struct stat` is laid out differently by each platform's headers, and a
program transcribing one is holding numbers nothing checks — being wrong about them reads the wrong
bytes rather than failing. A seek is two calls whose signatures are all there is to get right. It
leaves the position where it found it, which is why `tell` still reports 10 afterwards.

`size_of(path)` is the same question without an open file in hand; it opens, asks, and closes, so a
missing file answers `NotFound` rather than zero — the distinction a caller sizing a buffer needs and
the one a bare number could not carry.

### Closing, and using a file after it

```sysl
import sysl.fs.{create, remove_file}
import sysl.io.Reader

var path = "/tmp/sysl-fs-doc-5.txt"
var f = create(path).unwrap()

f.close().unwrap()

print(f.closed(), f.at_end())
print(f.close().is_ok())
print(f.seek(0i64).unwrap_err(), f.seek(0i64).unwrap_err().code())

var window: [4]u8
var got = f.read(window[..])

print(got.len, f.failed())

remove_file(path).unwrap()
```

```output
true true
true
the file is not open 9
0 true
```

Four rules are in that output.

**Closing is the program's to do, and `defer f.close()` is how it is written.** The language has no
destructor, so nothing runs at the end of a scope that the scope did not say. Dropping a `File`
without closing it leaks the handle until the program exits, the same as in C.

**`close` is idempotent, and the second call is not merely tolerated — it is the case `defer`
creates.** A function that closes on one path and defers the close for the others reaches the end
having done both.

**Every other member checks the flag first, and none of them may skip it.** `fclose` releases the
handle, so the pointer the struct holds afterwards names storage C has freed; reaching it would be a
use-after-free rather than a call that fails. What comes back instead is `NotOpen`, which is `EBADF` —
the code the operating system reports for the same mistake made one layer down.

**A read on a closed file latches rather than answering quietly empty.** An empty read means *end of
input*, and a program told that would conclude it had read the whole file when what it had done was
close it too early. So `got.len` is 0 **and** `failed()` is true, and the two together are the truth.

A failure from `close` is a real one, incidentally: the flush it performs is where a full disk finally
reports. A program that cares about its output checks the close and not only the writes.

### Copies share one handle

A `File` is a struct and therefore a value, so passing one to a function makes a second `File`. It
holds its state behind a `&FileState` rather than holding the pointer directly, and that is what makes
`close` answerable at all: **every copy shares one state**, so closing either is closing the file, and
closing it twice is harmless rather than a use-after-free. A value type holding the pointer would have
made that mistake easy.

## The path tier

```sysl
import sysl.fs.{write_text, exists, is_file, is_dir, readable, writable, rename, remove_file, size_of}

var a = "/tmp/sysl-fs-doc-6a.txt"
var b = "/tmp/sysl-fs-doc-6b.txt"

write_text(a, "x").unwrap()

print(exists(a), is_file(a), is_dir(a), readable(a), writable(a))
print(is_dir("/tmp"), is_file("/tmp"))

rename(a, b).unwrap()

print(exists(a), exists(b), size_of(b).unwrap())

remove_file(b).unwrap()

print(exists(b), remove_file(b).unwrap_err())
```

```output
true true false true true
true false
false true 1
false no such file or directory
```

**`exists` answers `false` for a path that exists under a directory the program may not search**,
which is the one way it differs from the question it looks like it is asking. That case cannot be told
from absence without a second call whose answer would already be stale — and every caller that
matters, one about to open the file, gets the truth from the open.

**`is_dir` asks by opening the path as a directory.** `DIR *` is opaque, so it needs nothing about the
platform beyond two symbols, which is what makes it the one question about a path's *kind* the module
can answer honestly. `is_file` is then "exists and is not a directory" — a device or a socket answers
true, which is right for what callers use it for: whether opening it as a file could work.

**`remove_file` refuses a directory rather than removing it.** It is `unlink`, where C's own `remove`
quietly does either; two names for two intentions is what lets a program that meant one of them find
out it had the other. `remove_dir` is `rmdir` and needs the directory empty, reporting
`DirectoryNotEmpty` — the one code in the whole set whose number the two platforms disagree about,
which is why it is read through a call rather than written as a literal.

**`rename` replaces whatever was at the destination**, is one operation as far as anything watching is
concerned when both paths are on the same filesystem, and *fails* rather than copying when they are
not. That is `rename(2)`'s contract and worth knowing, since a program moving a file across devices
has to read and write it itself.

**`make_dir` makes one directory and needs its parent to be there.** Making a chain is a loop over
path separators, and where a separator *is* is a question about paths rather than about the
filesystem — which this module does not answer. It asks for `0o777`, letting the process umask narrow
it: asking for less would override the environment's decision rather than defer to it.

## What is absent, and why

**Listing a directory.** `readdir` hands back a `struct dirent`, and finding the name in one means
knowing an offset that differs between platforms. That is the same trade the module refuses
everywhere else, so it is not made here either.

**Anything `stat` would answer** — timestamps, ownership, a mode — for the reason `size` is a seek.

The rule the whole module is written under is one sentence: **a question that can be answered by a
call whose signature is all there is to get right is answered here, and one that cannot is absent.**
No header is included and no C structure is transcribed. Where that leaves a gap, an `extern` of one's
own is the honest way across it, and [foreign functions](/reference/ffi/) is where that is written up.

## Argument types

```sysl
import sysl.fs.write_text

write_text("/tmp/sysl-fs-doc-x.txt", 42)
```

```error
'text' of 'sysl.fs.write_text' is string, but int was given
```

```sysl
import sysl.fs.write_bytes

write_bytes("/tmp/sysl-fs-doc-x.txt", "text")
```

```error
'bytes' of 'sysl.fs.write_bytes' is []const byte, but string was given
```

`write_bytes` wants bytes and `s.bytes` is how a `string` supplies them — free, since a `string`
already is a validated `[]u8`. `write_text` is the same call with that step written in.

And every one of these answers with a `Result`, which has to be opened before the file inside it is:

```sysl
import sysl.fs.open

var f = open("/tmp/sysl-fs-doc-x.txt")

f.close()
```

```error
type 'sysl$Result' has no method 'close'
```

`?` inside a function that returns a `Result`, or `unwrap()` in a program that would rather stop, are
the two ways through. [Errors and contracts](/reference/errors/) has the rest.

---

Next: [`sysl.math`](/library/math/) — the float functions, and the integer traits.
