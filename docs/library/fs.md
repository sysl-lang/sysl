---
title: The fs module
summary: "`sysl.fs` — files and paths, in three tiers; `IoError` and why it is an enum; and `requires os`, the capability that decides whether the module exists at all."
weight: 50
---

**Every declaration in `sysl.fs`, with its signature:** [the generated API page](/api/sysl-fs/#index). This page is the argument — what the module is for, and how its pieces fit; that one is the list.

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
| whole file | `read_text`, `read_bytes`, `write_text`, `write_bytes`, `append_text`, `append_bytes`, `write_text_atomic`, `write_bytes_atomic` | storage the size of the file — allocates by nature |
| open file | `open`, `create`, `append`, `open_update`, `create_update`, and `File`'s members | a buffer the caller already has |
| path | `exists`, `readable`, `writable`, `is_file`, `is_dir`, `is_link`, `size_of`, `metadata`, `link_metadata`, `set_permissions`, `symlink`, `read_link`, `hard_link`, `canonicalize`, `make_dir`, `make_dir_all`, `remove_file`, `remove_dir`, `remove_dir_all`, `rename`, `pending_name`, `publish_file`, `publish_dir`, `copy_file`, `truncate`, `current_dir`, `set_current_dir`, `make_temp_dir` | one C call each, or a loop over them |

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
released down the failing paths too. That is the idiom the module is written to demonstrate: a
scope releases what the scope said it would. A [destructor](/reference/memory/) is the other way to
say it and wants the handle behind a `&T`; these functions never let one escape, so the scope is
enough.

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

### Writing where somebody else is reading

`write_text` truncates and then writes, so for as long as the write takes, a reader of the path finds
**half a file** under the name it trusts. Where the path is shared — a cache entry, a configuration
file another process reloads, a binary about to be run — write to a **pending name** beside it and
`rename` that into place. A rename within one directory is one step: a reader sees the whole of the
old file or the whole of the new one, never a mixture and never a moment with nothing there.

```sysl
import sysl.fs.{entries, make_dir, make_temp_dir, pending_name, publish_dir, read_text, remove_dir_all, write_text, write_text_atomic}
import sysl.path.join

val dir = make_temp_dir("sysl-fs-doc-publish").unwrap()
val config = join(dir, "config.toml")

write_text_atomic(config, "retries = 3\n").unwrap()
write_text_atomic(config, "retries = 5\n").unwrap()

prints(read_text(config).unwrap())
print(entries(dir).unwrap().len())

val cache = join(dir, "cache")

for who in ["first", "second"]
    val staged = pending_name(cache)

    make_dir(staged).unwrap()
    write_text(join(staged, "built-by"), who).unwrap()
    publish_dir(staged, cache).unwrap()

print(read_text(join(cache, "built-by")).unwrap())
print(entries(dir).unwrap().len())

remove_dir_all(dir).unwrap()
```

```output
retries = 5
1
first
2
```

**`write_text_atomic` and `write_bytes_atomic` are the whole pattern in one call**, and a failure
anywhere — the write, the close, the rename — removes the pending file and answers the first error, so
the directory holds exactly what it held before. That is why the listing above says `1`: the pending
names never outlive the call.

**The pending name is beside the target, not in the temporary directory.** A rename only moves a file
within one filesystem, and nothing says the destination shares one with `/tmp`. `pending_name(target)`
is the target plus a suffix carrying a token drawn once per process — sixty-four bits of kernel
entropy, not a process id — and a count taken once per call, so two processes never agree on one and
neither do two calls of one process. Use it directly for whatever is built in several steps, and hand
the result to `publish_file` or `publish_dir`.

**`publish_dir` treats losing a race as success.** Two writers of one entry are building the same
thing; a rename refuses to replace a directory that holds anything, so the first one to finish is
never disturbed, and the second removes its own copy and answers `Ok` — which is why the cache above
still says `first`. Only a rename that fails with no directory at the target afterwards is reported.
`publish_dir` is POSIX-only, since clearing away the losing copy walks a tree.

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

f.read(window)
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

f.seek(6).unwrap()

var got = f.read(window)

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

**`size` asks the file rather than measuring it.** It was a seek to the end and back — four calls to
answer one question — because the alternative was `fstat`, and `struct stat` is laid out differently
by each platform's headers: a program transcribing one holds numbers nothing checks, and being wrong
about them reads the wrong bytes rather than failing. A shim beside the module reads the header and
one field crosses, so the refusal costs nothing here any more. It never moves the position, which is
why `tell` still reports 10 afterwards — where the seek version moved it and put it back, leaving it
at the end of the file if anything failed in between.

**Where there is no shim, the seek is still what runs.** `sysl.fs` requires `os` rather than `posix`,
so it reaches a Windows target that has no per-OS directory here — and `fseek`/`ftell` are ISO C, so
that path goes on working exactly as it did.

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
print(f.seek(0).unwrap_err(), f.seek(0).unwrap_err().code())

var window: [4]u8
var got = f.read(window)

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

**Closing is the program's to do, and `defer f.close()` is how it is written.** Dropping a `File`
without closing it leaks the handle until the program exits, the same as in C.

A [destructor](/reference/memory/) is the other way to arrange it, and `File` deliberately does not
have one: a destructor runs for a value held behind a `&T`, so giving `File` one would mean a heap
box per open file and would take the choice away from a program that would rather not have one. What
a destructor is for is the resource that dies where no `defer` can be written — inside a container,
inside a struct inside a container — and a handle a function opens and closes is not that.

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

**`make_dir` makes one directory and needs its parent to be there.** It asks for `0o777`, letting the
process umask narrow it: asking for less would override the environment's decision rather than defer
to it. **`make_dir_all` is the one every caller actually wants** — it climbs with
[`sysl.path.parent`](/library/path/) and makes each level from the topmost missing one down, and a
directory that is **already there is success rather than `AlreadyExists`**, because the question being
asked is *make sure this is there*.

**`remove_dir_all` is its opposite and is a post-order walk**, because `rmdir` refuses a directory
that is not empty. A **symbolic link is unlinked rather than followed**, which is what keeps it from
deleting something outside the tree it was pointed at, and a path that is not there at all is
success — a caller tearing down after a failure should not have to know how far the failure got.

**It is the one call here that POSIX decides, and `make_dir_all` beside it is not** — which reads as
arbitrary until you ask what each needs that the other does not. `make_dir_all` climbs with
`sysl.path.parent` and asks `exists` and `make_dir`, and every target has those. `remove_dir_all` has
to know whether an entry is a symbolic link *without following it*, and the only reading that answers
that is `link_metadata`, which is POSIX because a Windows reading of a filesystem entry is a
different struct rather than this one with fields missing. Following the link instead would make the
call portable and delete things nobody asked it to, which is the property the walk exists to have.

```sysl
import sysl.fs.{make_dir_all, remove_dir_all, write_text, exists, is_dir}
import sysl.path.join

var dir = "/tmp/sysl-fs-doc-tree"

remove_dir_all(dir).unwrap()
make_dir_all(join(dir, "a/b/c")).unwrap()
write_text(join(dir, "a/b/note.txt"), "hi").unwrap()

print(is_dir(join(dir, "a/b/c")))

make_dir_all(join(dir, "a/b")).unwrap()          // already there, and that is success

remove_dir_all(dir).unwrap()

print(exists(dir))
```

```output
true
false
```

### One reading of an entry

**`metadata` is the `stat` this module was written around not having**, and it answers everything
one call knows: the size, the mode, how many names lead to the entry, its owner and group, its inode
and device, and three `Instant`s. One call rather than several is the point — asking separately
whether a path is a directory, how big it is and when it changed is three trips into the kernel and
three chances for the answer to be about three different files.

```sysl
import sysl.fs.{write_text, metadata, set_permissions, remove_file, Kind}

var path = "/tmp/sysl-fs-doc-meta.txt"

write_text(path, "0123456789").unwrap()
set_permissions(path, 0o644).unwrap()

var m = metadata(path).unwrap()

print(m.size, m.kind(), m.is_file(), m.is_dir())
print(m.permissions() == 0o644, m.links >= 1, m.modified.us > 0)

remove_file(path).unwrap()
```

```output
10 file true false
true true true
```

**`link_metadata` is the same reading about the path *itself*** — so a symbolic link is described
rather than followed, and it is the only call here that can report one. `metadata`, `is_dir` and
`exists` all follow a link silently, which is the right default and was for a long time the only
behaviour available.

`Meta.same_file` is the inode and the device together, which is what says two paths name one file.
Neither identifies a file on its own: inode numbers are reused, and are unique only within a device.

### Links, and the path the filesystem agrees on

```sysl
import sysl.fs.{write_text, symlink, read_link, is_link, hard_link, metadata,
                canonicalize, make_dir_all, remove_dir_all}
import sysl.path.{join, file_name}

var dir = "/tmp/sysl-fs-doc-links"

remove_dir_all(dir).unwrap()
make_dir_all(dir).unwrap()
write_text(join(dir, "target"), "hello").unwrap()

symlink("target", join(dir, "here")).unwrap()

print(read_link(join(dir, "here")).unwrap())
print(is_link(join(dir, "here")), is_link(join(dir, "target")))
print(metadata(join(dir, "here")).unwrap().size)

hard_link(join(dir, "target"), join(dir, "second")).unwrap()

print(metadata(join(dir, "target")).unwrap().links)
print(file_name(canonicalize(join(dir, "here")).unwrap()))

remove_dir_all(dir).unwrap()
```

```output
target
true false
5
2
Some(target)
```

**A symbolic link's target is stored as written**, which is what makes a tree of relative links
movable — and a link is allowed to point at nothing, which is sometimes the point. A **hard** link is
a second name for one file: the two are equal afterwards, there is no original, and the file goes
when the last of them does, which is what `Meta.links` counts.

**`canonicalize` follows every link and answers an absolute path.** It is
[`sysl.path.normalize`](/library/path/)'s counterpart and **not** its equivalent: where `a/b` is a
link, the two answer different files, and confusing them is the shape of every path-traversal bug
there is. Reach for this one whenever the answer decides access to something.

### Where the program is, and somewhere to put things

`current_dir` and `set_current_dir` are the working directory. Both are **process-wide**, which is
the thing to know before reaching for the second: a library that changes it changes it for every
thread and every other library in the program. Prefer building an absolute path.

**`make_temp_dir` makes a directory nobody else can take**, inside whatever `TMPDIR` names. It is
*created* rather than named, which is the whole point: inventing a path and then making it leaves a
gap in which somebody else can take the name on a shared `/tmp`. Removing it is the caller's, and
`defer remove_dir_all(d)` is the idiom.

**`copy_file`** is a read-and-write loop rather than a platform fast path, which is a decision:
`copyfile(3)` and `copy_file_range(2)` have different names, different signatures and different
failure modes, and what is here is correct everywhere. It does **not** carry the permission bits
over — a caller that wants them reads `metadata(from).permissions()` and writes it with
`set_permissions`, which says out loud that it is doing so.

**`truncate`** cuts a file to a length, or extends it to one with zeroes, and makes nothing: a path
that is not there reports `NotFound`. `File.truncate` is the same on an open file, and **does not
move the position** — which is what makes it usable in the middle of writing.

### Where a directory *belongs*

A different question from where the program is: not where it happens to be, but where the machine's
own conventions say a file of a given kind goes. Four calls, each answering `Option[string]`.

```sysl
import sysl.fs.{home_dir, cache_dir, config_dir, data_dir}

print(home_dir().is_some())
print(cache_dir().is_some(), config_dir().is_some(), data_dir().is_some())

print(cache_dir().unwrap() != home_dir().unwrap())
```

```output
true
true true true
true
```

| call | macOS | Windows | elsewhere |
|---|---|---|---|
| `home_dir` | `$HOME` | `%USERPROFILE%` | `$HOME` |
| `cache_dir` | `~/Library/Caches` | `%LOCALAPPDATA%` | `$XDG_CACHE_HOME`, or `~/.cache` |
| `config_dir` | `~/Library/Application Support` | `%APPDATA%` | `$XDG_CONFIG_HOME`, or `~/.config` |
| `data_dir` | `~/Library/Application Support` | `%APPDATA%` | `$XDG_DATA_HOME`, or `~/.local/share` |

**They are `Option` because a machine may genuinely not say.** An environment with no `HOME` set is
not a failure with an errno behind it; it is a machine that has not told the program where its user
lives. A caller that gets `None` falls back to something of its own **knowingly**, which is the whole
difference between this and reading the variable itself. A variable set to *nothing* counts as unset
here, which is where these differ from [`sysl.env`](/library/env/)'s `get`: an empty string is a
truthful reading of an environment and is not a place.

**They name a directory and do not make one.** Nothing here touches the filesystem — the answer is a
path, and whether it exists is `exists` and whether it should is `make_dir_all`. A question that
created a directory as a side effect could not be asked by a program that was about to read.

**A program appends its own name, and nothing here does it for you.** These say where the machine
keeps caches, not where *yours* goes:

```sysl
import sysl.fs.cache_dir
import sysl.path.join

val mine = cache_dir().map((d) -> join(d, "myprogram"))

print(mine.is_some())
```

```output
true
```

A library that guessed the leaf would be guessing the program's identity, which is not its to guess.

**macOS answers the same directory for `config_dir` and `data_dir`, and that is Apple's convention
rather than a gap here.** The platform draws the line between *cache* and everything else and does
not draw one between configuration and data. Inventing a `~/Library/Config` to make the four look
symmetrical would put files where nothing else on the machine looks.

### Listing a directory

```sysl
import sysl.fs.{entries, make_dir, write_text, remove_file, remove_dir}

var dir = "/tmp/sysl-fs-doc-entries"

remove_dir(dir)          // in case an earlier run of this page left it behind
make_dir(dir).unwrap()
write_text(dir + "/one.txt", "a").unwrap()
write_text(dir + "/two.txt", "b").unwrap()

var names = entries(dir).unwrap()

print(names.len())
print(names.at(0) == "one.txt" || names.at(0) == "two.txt")

remove_file(dir + "/one.txt").unwrap()
remove_file(dir + "/two.txt").unwrap()
remove_dir(dir).unwrap()

print(entries(dir).unwrap_err())
```

```output
2
true
no such file or directory
```

**`.` and `..` are left out**, because every caller drops them: a program listing a directory is
asking what is *in* it, and a recursive walk that forgets the filter does not terminate. **The order
is the filesystem's** — not sorted, and not stable between two listings of one directory. A program
that wants an order applies one.

**A name that is not UTF-8 stops the program**, exactly as `read_text` does with a file's contents
and for the same reason: answering with an error would put a case in `IoError` that no filesystem
ever reports. POSIX names are bytes, so this is reachable rather than theoretical.

**This is the one thing in the module answered by C rather than by a bare `extern`.** `readdir` hands
back a `struct dirent` whose name field sits at an offset the two platforms disagree about, which is
the transcription the rest of the module refuses — so a four-line shim returns the `char *` and
nothing in sysl learns the layout. It sits in a `__posix__` directory
([modules](/reference/modules/)), which is what keeps it off a target with no directories to list.

**One file rather than one per system**, and the shim's own reason for existing is why: the two
platforms disagree about the layout, the shim is what settles that, and so the shim itself is the
same text on both. What it needs is a POSIX system to run on, which is exactly what `__posix__` says.

### Walking a tree

`entries` lists one directory. `walk` reports a whole tree — the root first, then everything under
it — and is a [cursor](/library/core/#walking-a-type-of-your-own), so a `for` walks it:

```sysl
import sysl.fs.{make_dir_all, remove_dir_all, walk, write_text}

var dir = "/tmp/sysl-fs-doc-walk"

remove_dir_all(dir).unwrap()
make_dir_all(dir + "/a/b").unwrap()
write_text(dir + "/a/b/deep", "x").unwrap()

var files = 0
var dirs = 0

for item in walk(dir)
    val e = item.unwrap()

    if e.is_dir() then dirs += 1 else files += 1

print(dirs, files)

remove_dir_all(dir).unwrap()
```

```output
3 1
```

**Each item is a `Result`, and that is the error policy being left to you**: `?` in the body makes the
first unreadable directory fatal, and ignoring it makes the walk best effort. A directory that cannot
be read is one `Err` and the walk carries on with what is left.

**A symbolic link is reported and never followed.** `is_dir` follows a link, so a walk that classified
with it would descend into a link to a directory without ever deciding to — and a link pointing at one
of its own ancestors would make the walk unbounded. Each entry carries a
[`link_metadata`](#one-reading-of-an-entry) reading, so `e.is_link()` is what a caller asks.

**`bottom_up()` reverses the order** — a directory after everything under it, which is what
`remove_dir_all` needs since `rmdir` refuses a directory that is not empty. **`skip_dir()` prunes**
the directory just reported, and it needs the cursor by hand rather than a `for`, since a `for` walks a
copy:

```
var w = walk(root)

loop
    val e = w.next() match
        None -> break
        Some(item) -> item?

    if e.is_dir() && hidden(e.path) then w.skip_dir()
```

### Filtering a walk by a glob

`walk(root).matching(pattern)` reports only the entries whose path matches a
[glob](/library/path/#matching-a-glob), which is what a build tool, a test runner and a `--exclude`
flag all want and what every caller was otherwise writing by hand:

```sysl
import sysl.fs.{make_dir_all, remove_dir_all, walk, write_text}
import sysl.path.relative_to

var dir = "/tmp/sysl-fs-doc-matching"

remove_dir_all(dir).unwrap()
make_dir_all(dir + "/src/deep").unwrap()
make_dir_all(dir + "/.git").unwrap()
write_text(dir + "/notes.md", "x").unwrap()
write_text(dir + "/src/mid.sysl", "x").unwrap()
write_text(dir + "/src/deep/low.sysl", "x").unwrap()
write_text(dir + "/.git/config", "x").unwrap()

var found: [8]string
var n = 0

for item in walk(dir).matching("**/*.sysl")
    found[n] = relative_to(item.unwrap().path, dir).unwrap()
    n += 1

print(n)

remove_dir_all(dir).unwrap()
```

```output
2
```

**The pattern is matched against the path relative to the root**, which is what makes a pattern
portable between one caller walking `.` and another walking `/home/ed/project`. The `.git` tree is
absent because a leading `.` is hidden from every wildcard, and `notes.md` because it is not a
`.sysl` file.

**The root itself never matches.** Its relative path is `"."`, which no pattern here matches — and
that is the answer a caller wants: a walk filtered by `*.sysl` is asking for files rather than for the
directory it started in.

**It filters and does not prune.** Every directory is still descended into, because a directory whose
own name does not match may hold a file whose path does — which is the whole point of a pattern with a
separator in it. A caller that wants whole subtrees skipped wants `skip_dir`, which is a decision
about where to *go* rather than about what to report, and the two compose: drive the walk by hand,
prune what you do not want to enter, and ask `matches` yourself.

**An `Err` passes through unfiltered**, since a directory that could not be read is not an entry with
a path to test, and losing it would turn a permission failure into an empty result.

### Copying a tree

`copy_dir_all` is the walk with a leaf action, and `remove_dir_all` is the same walk asked the other
way round:

```sysl
import sysl.fs.{copy_dir_all, make_dir_all, read_text, remove_dir_all, write_text}

var dir = "/tmp/sysl-fs-doc-copy"

remove_dir_all(dir).unwrap()
make_dir_all(dir + "/from/inner").unwrap()
write_text(dir + "/from/inner/one", "hello").unwrap()

copy_dir_all(dir + "/from", dir + "/to").unwrap()

print(read_text(dir + "/to/inner/one").unwrap())

remove_dir_all(dir).unwrap()
```

```output
hello
```

**A directory is merged into and a file is never overwritten** — the safe half of each question. A
destination that already holds a subtree gains what this brings; one that already holds a *file* of a
name being copied reports `AlreadyExists` rather than quietly replacing something.

**Permissions are carried**, which is where this differs from `copy_file`: an executable copied with
that one stops being executable. A symbolic link is copied *as a link*, pointing wherever it pointed.

**A failure part way through leaves what it has already written.** Nothing removes a tree on the way
out, because it cannot know whether the destination existed before it started, and deleting one you
owned would be far worse than the failure being reported. A caller needing the destination to appear
whole or not at all copies into `make_temp_dir` and `rename`s the result into place, which is the only
way to get atomicity from a filesystem.

## What is absent, and why

**Anything `stat` would answer used to be**, and it is not any more: `metadata` is that call, and the
`struct stat` it comes back in stays in a shim under `__posix__` where the header decides the layout,
with thirteen numbers crossing rather than a transcription. That is the shape this section always
prescribed, arrived at the day something needed it.

The rule the whole module is written under is one sentence: **a question that can be answered by a
call whose signature is all there is to get right is answered here, and one that cannot is answered
by C.** No C structure is transcribed into sysl. Three headers are included, in the shims behind
`entries`, `metadata` and `size` — a question C can answer in a few lines is answered by C, and one
that would need a structure transcribed into sysl is still not answered at all. Where that leaves a
gap, an `extern` of one's own — or a `.c` of one's own — is the honest way across it, and
[foreign functions](/reference/ffi/) is where that is written up.

**Lexical path handling is absent from this module on purpose**, and is [`sysl.path`](/library/path/).
This one requires `os` and a requirement is module-wide, so one `getcwd` beside `join` would take the
whole of path handling away from every program that has no operating system to ask.

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
type 'Result' has no method 'close'
```

`?` inside a function that returns a `Result`, or `unwrap()` in a program that would rather stop, are
the two ways through. [Errors and contracts](/reference/errors/) has the rest.

---

Next: [`sysl.math`](/library/math/) — the float functions, and the integer traits.
