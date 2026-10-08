---
title: sysl.fs
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.fs
summary: "What is at the end of a path: reading and writing whole files, metadata, directories, links, and the errors any of it can answer with."
requires: "requires { os }"
---

`read_text`, `write_bytes`, `write_text_atomic` and the other publishing calls, `metadata`,
`make_dir_all`, `copy_file`, `canonicalize`, the walk, and
`IoError` — which carries the platform's own number so that a caller can act on *which* failure it
was rather than only on there having been one.

**This module requires `os` and `sysl.path` requires nothing, which is why they are two.** A
capability requirement is module-wide, so one `getcwd` filed beside `join` would take path
handling away from every program with no operating system to ask. Deciding what a path *says* is
`sysl.path`'s; going and looking is this module's.

## Index

[`append`](#append) [`append_bytes`](#append_bytes) [`append_text`](#append_text) [`cache_dir`](#cache_dir) [`canonicalize`](#canonicalize) [`config_dir`](#config_dir) [`copy_dir_all`](#copy_dir_all) [`copy_file`](#copy_file) [`create`](#create) [`create_update`](#create_update) [`current_dir`](#current_dir) [`data_dir`](#data_dir) [`entries`](#entries) [`exists`](#exists) [`hard_link`](#hard_link) [`home_dir`](#home_dir) [`is_dir`](#is_dir) [`is_file`](#is_file) [`is_link`](#is_link) [`link_metadata`](#link_metadata) [`make_dir`](#make_dir) [`make_dir_all`](#make_dir_all) [`make_temp_dir`](#make_temp_dir) [`metadata`](#metadata) [`open`](#open) [`open_update`](#open_update) [`pending_name`](#pending_name) [`publish_dir`](#publish_dir) [`publish_file`](#publish_file) [`read_bytes`](#read_bytes) [`read_link`](#read_link) [`read_text`](#read_text) [`readable`](#readable) [`remove_dir`](#remove_dir) [`remove_dir_all`](#remove_dir_all) [`remove_file`](#remove_file) [`rename`](#rename) [`set_current_dir`](#set_current_dir) [`set_permissions`](#set_permissions) [`size_of`](#size_of) [`symlink`](#symlink) [`truncate`](#truncate) [`walk`](#walk) [`writable`](#writable) [`write_bytes`](#write_bytes) [`write_bytes_atomic`](#write_bytes_atomic) [`write_text`](#write_text) [`write_text_atomic`](#write_text_atomic) [`Entry`](#entry) [`File`](#file) [`FileState`](#filestate) [`IoError`](#ioerror) [`Kind`](#kind) [`Matching`](#matching) [`Meta`](#meta) [`Walk`](#walk-1) [Display for IoError](#display-for-ioerror) [Display for Kind](#display-for-kind) [Eq for IoError](#eq-for-ioerror) [Eq for Kind](#eq-for-kind) [Fallible for File](#fallible-for-file) [Iterate for Matching](#iterate-for-matching) [Iterate for Walk](#iterate-for-walk) [Reader for File](#reader-for-file) [Writer for File](#writer-for-file)

## Functions

### `append`

```sysl
append(path: string) -> Result[File, IoError]
```

### `append_bytes`

```sysl
append_bytes(path: string, bytes: []const u8) -> Result[unit, IoError]
```

Writes the bytes at the end of the file, making it if it is not there. Each call lands after
whatever the file already held, including anything another program appended in between.

### `append_text`

```sysl
append_text(path: string, text: string) -> Result[unit, IoError]
```

### `cache_dir`

```sysl
cache_dir() -> Option[string]
```

Where a program's own cache belongs -- the things it can regenerate and would rather not.

`~/Library/Caches` on macOS, `%LOCALAPPDATA%` on Windows, and `$XDG_CACHE_HOME` or `~/.cache`
elsewhere, which is the freedesktop rule.

**A program appends its own name and nothing here does it for you**: this answers where the
machine keeps caches, not where yours goes, so the call is
`cache_dir().map((d) -> join(d, "myprogram"))`. A library that guessed the leaf would be guessing
the program's identity.

### `canonicalize`

```sysl
canonicalize(path: string) -> Result[string, IoError]
```

### `config_dir`

```sysl
config_dir() -> Option[string]
```

Where a program's configuration belongs -- what a person edits, and what survives a reinstall.

`~/Library/Application Support` on macOS, `%APPDATA%` on Windows, `$XDG_CONFIG_HOME` or
`~/.config` elsewhere.

**macOS answers the same directory for this and for `data_dir`, and that is Apple's convention
rather than an omission here.** The platform draws the line between *cache* and *everything else*
and does not draw one between configuration and data; a library that invented a `~/Library/Config`
to make the four look symmetrical would be putting files where nothing else on the machine looks.

### `copy_dir_all`

```sysl
copy_dir_all(from: string, to: string) -> Result[unit, IoError]
```

A directory and everything under it copied, making the destination and everything under it.

**A directory is merged into and a file is never overwritten.** Those are the safe half of each
question: a destination that already holds a subtree gains what this brings, and a destination
that already holds a *file* of a name being copied reports `AlreadyExists` rather than quietly
replacing something. `make_dir_all` is idempotent, so the merge half is simply what happens.

**Permissions are carried**, which is where this differs from `copy_file` -- an executable copied
with that one stops being executable, and this org copies build inputs. A symbolic link is copied
*as a link*, pointing wherever it pointed, which may be nothing and may be outside the tree.

**A failure part way through leaves what it has already written.** Nothing here removes a tree on
the way out, because it cannot know whether the destination existed before it started, and
deleting one the caller owned would be far worse than the failure being reported. A caller that
needs the destination to appear whole or not at all copies to `pending_name(to)` and hands that to
`publish_dir` -- a copy built beside its destination rather than in `make_temp_dir`, since a rename
only moves a directory within one filesystem, and that rename is the only atomicity a filesystem has.

The source may be a plain file, in which case this is `copy_file` with the mode carried.

### `copy_file`

```sysl
copy_file(from: string, to: string) -> Result[unit, IoError]
```

One file copied to another path, replacing whatever was at the destination.

**It is a read-and-write loop rather than a platform fast path**, which is a decision rather than
an omission. `copyfile(3)` on macOS and `copy_file_range(2)` on Linux can hand the whole thing to
the kernel, and on a filesystem that supports reflinks they can do it without moving any data at
all -- but the two have different names, different signatures and different failure modes, and
neither is reachable without a shim per platform. What is here is correct everywhere and is what
every caller needs; the fast path is worth adding the day something measures that it is missed.

**The permission bits are not carried over.** The destination is made the way `create` makes any
file, which is what the process umask decides. A caller that wants the mode preserved reads
`metadata(from).permissions()` and writes it with `set_permissions`, which says out loud that it
is doing so -- and a copy that silently reproduced a `0o600` into a directory where that was not
intended is the kind of surprise a library should not spring.

### `create`

```sysl
create(path: string) -> Result[File, IoError]
```

### `create_update`

```sysl
create_update(path: string) -> Result[File, IoError]
```

### `current_dir`

```sysl
current_dir() -> Result[string, IoError]
```

Where the program is, which nothing in the library could ask before.

`sysl.process` takes a `dir` for a child to start in, so a program could say where somebody *else*
should be and not where it was itself. This is the other half.

**It is a global the whole process shares**, so a threaded program that reads this while another
thread is calling `set_current_dir` gets one of the two answers and no promise about which.
Building an absolute path once at start-up is the shape that avoids the question.

### `data_dir`

```sysl
data_dir() -> Option[string]
```

Where a program's own data belongs -- what it wrote and cannot regenerate.

`~/Library/Application Support` on macOS, `%APPDATA%` on Windows, `$XDG_DATA_HOME` or
`~/.local/share` elsewhere. See `config_dir` for why two of those three are the same answer.

### `entries`

```sysl
entries(path: string) -> Result[Buf[string], IoError]
```

Every name in a directory, with `.` and `..` left out.

The two dots are dropped because every caller drops them: a program listing a directory is asking
what is *in* it, and one that genuinely wants to walk upwards has a path to build rather than a
name to find. Leaving them in makes the ordinary case a filter written at every call site, and a
recursive walk that forgets the filter does not terminate.

**The names arrive in whatever order the filesystem keeps them**, which is not sorted and is not
stable between two listings of one directory. A program that wants an order applies one; there is
no order here to be relied on by accident.

**A name that is not UTF-8 makes the whole call answer `InvalidUtf8`**, carrying the offset within
that name. A POSIX name is bytes, and Linux will store any of them, so a program listing a directory
it did not make can meet one. The whole call fails rather than the name being skipped, because a
listing with a name silently missing is a wrong answer that looks like a right one -- a copy that
leaves a file behind, a clean-up that leaves it in place. A program that has to carry on through
such a directory lists it with `entry_bytes`, which answers every name as the bytes it is.

### `entry_bytes`

```sysl
entry_bytes(path: string) -> Result[Buf[[]u8], IoError]
```

Every name in a directory as the bytes the filesystem holds, with `.` and `..` left out and in the
same unpromised order as `entries`.

**This is `entries` for a directory whose names need not be text** -- the one a program did not
make, on a filesystem that stores whatever bytes it was given. It never answers `InvalidUtf8`; a
caller decodes each name itself with `sysl.text.from_utf8`, and decides what a name that does not
decode should become. The bytes are a copy, so they outlive the listing.

### `exists`

```sysl
exists(path: string) -> bool
```

Whether there is anything at all at this path.

It answers `false` for a path that exists but sits under a directory the program may not search,
which is the one way this differs from the question it looks like it is asking. That case cannot
be told from absence without a second call whose answer would already be stale, and every caller
that matters -- one about to open the file -- gets the truth from the open.

### `hard_link`

```sysl
hard_link(existing: string, fresh: string) -> Result[unit, IoError]
```

A second name for a file that already exists, in the same filesystem.

The two names are equal afterwards -- there is no original -- and the file goes when the last of
them does, which is what `Meta.links` counts. It fails across filesystems, and it fails on a
directory, both of which are the kernel's rules rather than this module's.

### `home_dir`

```sysl
home_dir() -> Option[string]
```

Where the user's home directory is, or `None` where the environment does not say.

`HOME` everywhere but Windows, which spells it `USERPROFILE`. **A variable set to nothing counts
as unset here**, which is where this differs from `sysl.env.get`: an empty string is a truthful
reading of an environment and is not a place, and every specification that mentions the case says
to treat it as absent.

### `is_dir`

```sysl
is_dir(path: string) -> bool
```

Whether the path names a directory, asked by opening it as one. `DIR *` is opaque, so this needs
nothing about the platform beyond the two symbols -- which is what makes it the one question about
a path's *kind* the module can answer honestly.

### `is_file`

```sysl
is_file(path: string) -> bool
```

Whether the path names something that is not a directory. A device or a socket answers `true`
here, which is right for what callers use it for: the question is whether opening it as a file
could work.

### `is_link`

```sysl
is_link(path: string) -> bool
```

Whether the path itself is a symbolic link. It follows nothing, so a link to a directory answers
`true` here and `is_dir` answers `true` about the same path -- the two are asking different
questions and both answers are right.

### `link_metadata`

```sysl
link_metadata(path: string) -> Result[Meta, IoError]
```

The same, about the path **itself** -- so a symbolic link is described rather than followed.

This is the only call in the module that can report a link at all. `is_dir`, `exists` and
`metadata` all follow one silently, which is the right default and was until now the only
behaviour available.

### `make_dir`

```sysl
make_dir(path: string) -> Result[unit, IoError]
```

Makes one directory. The parent has to be there already: making a chain is a loop over path
separators, and where a separator *is* is a question about paths rather than about the
filesystem, which this module does not answer.

### `make_dir_all`

```sysl
make_dir_all(path: string) -> Result[unit, IoError]
```

Every directory on the path made, from the topmost one that is missing down.

**A path that is already a directory is success, not `AlreadyExists`**, which is the difference
between this and a loop over `make_dir` and is what every caller of it wanted: the question being
asked is "make sure this is there", and a program that has to distinguish "I made it" from "it was
there" is asking a question this cannot answer honestly anyway, since somebody else may have made
it in between.

It climbs with `sysl.path.parent` and recurses, so the work is done from the top down and each
level is attempted exactly once. A component that exists and is **not** a directory reports
`NotADirectory` from the `make_dir` beneath it rather than being silently accepted.

### `make_temp_dir`

```sysl
make_temp_dir(prefix: string) -> Result[string, IoError]
```

### `metadata`

```sysl
metadata(path: string) -> Result[Meta, IoError]
```

Everything one `stat` answers about what a path names, following symbolic links.

A path that does not exist reports `NotFound`, which is the distinction a caller has to be able to
make and the reason this answers a `Result` rather than an `Option`: "there is nothing there" and
"the directory above it is not searchable" are different facts and lead to different programs.

### `open`

```sysl
open(path: string) -> Result[File, IoError]
```

The three ways a program opens a file, named for what it means rather than for the mode string it
becomes. `open` reads an existing file; `create` makes one, or empties one that was there;
`append` writes at the end of whatever is there, making the file if it is not.

### `open_update`

```sysl
open_update(path: string) -> Result[File, IoError]
```

A file opened for reading **and** writing, which the three above cannot express between them: each
of C's update modes is a different answer to "and what about what is already there", and naming
them apart is what keeps a caller from having to know that `"r+"` fails on a missing file while
`"w+"` empties one that exists.

### `pending_name`

```sysl
pending_name(target: string) -> string
```

A name beside `target` that no other call -- in this process or in any other -- will answer.

Write there, then hand both names to `publish_file` or `publish_dir`. It is the target with a
suffix, so it is in the target's directory and on the target's filesystem, and a directory listing
says which entry it was going to become.

### `publish_dir`

```sysl
publish_dir(pending: string, target: string) -> Result[unit, IoError]
```

### `publish_file`

```sysl
publish_file(pending: string, target: string) -> Result[unit, IoError]
```

Moves a finished file from `pending` onto `target`, replacing whatever was there.

**A failure leaves nothing behind**: the pending file is removed, so a full disk or a read-only
directory leaves the previous entry and no debris beside it. The error is the rename's, read before
the removal could overwrite it. The permission bits travel with the rename, so a binary linked under
the pending name is still one when it arrives.

### `read_bytes`

```sysl
read_bytes(path: string) -> Result[[]u8, IoError]
```

Every byte of the file.

It reads in chunks and grows rather than sizing the buffer from `size` first, which costs a little
copying and buys the case that matters: a file being written while this reads it, and anything
whose length is not a fact until the read ends. The loop stops on the first empty read, and asks
afterwards whether the reading *ended* badly -- the two are separate questions, which is the split
`Reader` was built around.

### `read_link`

```sysl
read_link(path: string) -> Result[string, IoError]
```

What a symbolic link points at, exactly as it was written -- which may be relative, and may name
nothing at all.

A path that is not a link reports `Other(22)`, which is `EINVAL`: "this is not the kind of thing
that has a target". Ask `is_link` first where the difference matters.

### `read_text`

```sysl
read_text(path: string) -> Result[string, IoError]
```

The file as text, or `InvalidUtf8` carrying the offset of the first byte that is not.

**Bytes that are not UTF-8 are an error, not a trap**, for the reason `reference/errors.md` gives
for every failure that comes from outside the program: a file is input, and a program reading one
it did not write cannot have ruled out the byte that breaks it. The offset is counted from the start
of the file. A caller that wants the text anyway -- replacing what does not decode, or treating the
file as Latin-1 -- reads `read_bytes` and decides for itself.

### `readable`

```sysl
readable(path: string) -> bool
```

### `remove_dir`

```sysl
remove_dir(path: string) -> Result[unit, IoError]
```

Removes a directory, which has to be empty. A directory with anything in it reports
`DirectoryNotEmpty` -- the one error code in the set whose number the two platforms disagree
about, which is why `error.sysl` reads it through a call rather than a literal.

### `remove_dir_all`

```sysl
remove_dir_all(path: string) -> Result[unit, IoError]
```

A directory and everything under it removed.

**The walk is post-order** -- a directory is emptied before it is taken -- because `rmdir` refuses
one that is not empty, which is the rule `remove_dir` states and this exists to work within rather
than around.

**A symbolic link is unlinked, not followed**, which is the property that keeps this from deleting
something outside the tree it was pointed at. `entries` says what is in a directory and `is_dir`
follows links, so the test here is `link_metadata` -- the one reading in the module that describes
the path itself.

A path that is not there at all is success. A caller tearing down after a failure should not have
to know how far the failure got.

### `remove_file`

```sysl
remove_file(path: string) -> Result[unit, IoError]
```

Removes a file, and refuses a directory rather than removing it: `unlink` is the call that draws
that line, where C's own `remove` quietly does either. Two names for two intentions is what lets
a program that meant one of them find out it had the other.

### `rename`

```sysl
rename(from: string, to: string) -> Result[unit, IoError]
```

Moves a path to another name, replacing whatever was at the destination.

It is one operation as far as anything watching is concerned when both paths are on the same
filesystem, and it fails rather than copying when they are not -- which is `rename(2)`'s contract
and worth knowing, since a program moving a file across devices has to read and write it itself.

The two copies are named rather than written into the call, because a `cstring` temporary lives
for the statement it appears in and this statement needs both of them alive at once.

### `set_current_dir`

```sysl
set_current_dir(path: string) -> Result[unit, IoError]
```

Moves the program. **It is process-wide**, which is the thing to know before reaching for it: a
library that changes the working directory changes it for every thread and for every other library
in the program, and puts it back only if it remembers to.

Prefer building an absolute path. This exists because a program that drives a build, or that is
the shell-like thing at the top, genuinely needs it.

### `set_permissions`

```sysl
set_permissions(path: string, mode: u32) -> Result[unit, IoError]
```

The permission bits set to exactly what is given. It is not a mask to add: `0o644` means the file
ends up `0o644` whatever it was, which is `chmod(2)`'s own meaning and the one a program that read
`permissions()` first is expecting.

### `size_of`

```sysl
size_of(path: string) -> Result[long, IoError]
```

How many bytes the file holds. It opens the file to ask, so it reports the same failures an open
does -- a missing file answers `NotFound` rather than zero, which is the distinction a caller
sizing a buffer needs and the one a bare number could not carry.

### `symlink`

```sysl
symlink(target: string, link: string) -> Result[unit, IoError]
```

A symbolic link created at `link`, pointing at `target`.

**`target` is not checked and does not have to exist.** A dangling link is a legal thing to make
and is sometimes the point -- a package manager writes one before what it names has been
unpacked. It is also stored **as written**: a relative target is resolved against the directory
the link is in, every time it is followed, rather than against wherever the program was when it
made the link.

### `truncate`

```sysl
truncate(path: string, length: long) -> Result[unit, IoError]
```

A file cut to a length, or extended to one with zeroes. It makes nothing: a path that is not
there reports `NotFound` rather than becoming an empty file.

Extending is `truncate(2)`'s own behaviour and is worth knowing, since the name says only half of
it: a length past the end of the file leaves a hole that reads as zeroes and that most filesystems
do not store.

### `walk`

```sysl
walk(root: string) -> Walk
```

Everything at or under a path, the path itself first.

The root is reported whatever it is, so a walk of a plain file yields that one entry -- which is
what lets `copy_dir_all` be given either and do the right thing without asking first.

### `writable`

```sysl
writable(path: string) -> bool
```

### `write_bytes`

```sysl
write_bytes(path: string, bytes: []const u8) -> Result[unit, IoError]
```

Writes the bytes, replacing whatever was there and making the file if it was not.

### `write_bytes_atomic`

```sysl
write_bytes_atomic(path: string, bytes: []const u8) -> Result[unit, IoError]
```

Writes the bytes to `path` by way of a pending name, so a reader of `path` sees the whole of the
previous file or the whole of this one and never a prefix of either.

On any failure -- the write, the close or the rename -- the pending file is removed and the first
error is the answer, so `path` is exactly what it was before the call. A directory that is not
there answers `NotFound` and leaves nothing anywhere.

### `write_text`

```sysl
write_text(path: string, text: string) -> Result[unit, IoError]
```

### `write_text_atomic`

```sysl
write_text_atomic(path: string, text: string) -> Result[unit, IoError]
```

`write_bytes_atomic` for text.

## Types

### `Entry`

```sysl
struct Entry
    path: string
    depth: usize
    meta: Meta
```

One entry a walk reported: where it is, how far down, and one reading of what it is.

**The `Meta` is a `link_metadata`**, so a symbolic link describes itself rather than whatever it
points at. That is what `is_dir` on this answers about, and it is why a walk never follows one.

The depth is from the root the walk was given, which is itself depth zero. A caller reproducing
the tree somewhere else wants `sysl.path.relative_to` rather than this, but a caller that only
needs to know how deep it has got -- a printer indenting, a search bounding itself -- would
otherwise have to count separators.

| Member | Signature | Description |
|---|---|---|
| `is_dir` | `is_dir(self) -> bool` | Whether this is a directory the walk will descend into -- so a link to one answers `false`. |
| `is_link` | `is_link(self) -> bool` | Whether this is a symbolic link, which a walk reports and does not follow. |

### `File`

```sysl
struct File
    inner: &FileState
```

A file that is open, or that was.

**Closing is the program's to do**, and `defer f.close()` is how it is written -- the language has
no destructor, so nothing runs at the end of a scope that the scope did not say. What the shared
state above buys is that saying it *twice* is harmless rather than a use-after-free, which is the
mistake a value type would otherwise make easy. Dropping a `File` without closing it leaks the
handle until the program exits, the same as in C.

| Member | Signature | Description |
|---|---|---|
| `closed` | `closed(*self) -> bool` |  |
| `flush` | `flush(*self) -> Result[unit, IoError]` | *Every member below asks this first, and none of them may be dropped.** `fclose` releases the handle, so the pointer this struct holds afterwards names storage C has freed, and reaching it is a use-after-free rather than a call that fails. |
| `close` | `close(*self) -> Result[unit, IoError]` | Releases the handle, flushing what is still buffered. |
| `tell` | `tell(*self) -> Result[long, IoError]` | Where the next read or write will happen, in bytes from the start. |
| `seek` | `seek(*self, to: long) -> Result[unit, IoError]` | Moves to an absolute offset, which also clears the end-of-file mark. |
| `size` | `size(*self) -> Result[long, IoError]` | How many bytes the file holds. |
| `at_end` | `at_end(*self) -> bool` | Whether a read has already run off the end. |
| `truncate` | `truncate(*self, length: long) -> Result[unit, IoError]` | The file cut to a length, or extended to one with zeroes -- the one thing this surface could not do, having `seek` and `size` and no way to change what is there. |

### `FileState`

```sysl
struct FileState
    handle: *u8
    bad: bool
    shut: bool
```

The handle itself, and the two things that are true of it beyond the pointer.

It is a struct of its own -- rather than the three fields sitting directly in `File` -- because of
what `File` has to survive being copied. A struct is a value here, so a `File` handed to a
function is a second `File`, and if it held the pointer directly then closing either would leave
the other holding a handle C has freed. Every copy sharing **one** `FileState` is what makes
`close` answerable at all: it is written once, and every copy sees it.

### `IoError`

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
    InvalidUtf8(offset: usize)
    Other(code: int)
```

Why a filesystem operation did not happen.

| Member | Signature | Description |
|---|---|---|
| `code` | `code(self) -> int` | The number this came from, so a program can report or compare one the set does not name. |
| `message` | `message(self) -> string` | A sentence, in the terms the operation was asked in rather than in C's. |

### `Kind`

```sysl
enum Kind
    Regular
    Directory
    Symlink
    Special(bits: u32)
```

What a filesystem entry is, beside what is in it.

The three a program branches on are named and everything else is `Special` carrying the raw type
bits, for the same reason `IoError` names ten codes and carries the rest: a table of every device
flavour is one nobody could keep current, and a program that genuinely cares about a socket is
already reading the number. `Special` is POSIX's own word for what is left -- a fifo, a socket, a
character or block device.

**`Regular` rather than `File`, and `Special` rather than `Other`, and both are about COLLISION
rather than about taste.** A variant is a name of its module, reached unqualified, so `File` here
would be a second answer for the name `sysl.fs.File` -- the open-file handle, which is what a
program means by it -- and `Other` a second answer for `IoError.Other`, which every program
matching on a filesystem error already writes bare. Neither is refused at the declaration; both
make an ordinary use of the older name ambiguous, one program away.

| Member | Signature | Description |
|---|---|---|
| `is_dir` | `is_dir(self) -> bool` | So that `k == Directory` reads as itself. |
| `name` | `name(self) -> string` | The word for what this is. |

### `Matching`

```sysl
struct Matching
    private inner: Walk
    private root: string
    private pattern: string
```

A walk with a glob over it -- what `Walk.matching` answers, and not a thing to construct.

It holds the root separately from the walk because the walk's own is private to it and the
pattern is matched against a relative path. That is one string per filtered walk rather than per
entry, since `relative_to` is what allocates here and it allocates once per entry either way.

### `Meta`

```sysl
struct Meta
    size: long
    mode: u32
    links: u64
    owner: u32
    group: u32
    inode: u64
    device: u64
    modified: Instant
    accessed: Instant
    changed: Instant
```

Everything one `stat` answers, in one value.

**One call rather than several is the point.** Asking separately whether a path is a directory,
how big it is and when it changed is three trips into the kernel and three chances for the answer
to be about three different files -- something can be replaced between them. A `Meta` is one
reading of one entry.

The times are `Instant`s, so `sysl.time` turns them into a date with nothing here knowing about
calendars. That costs the nanoseconds the platform reports: an `Instant` is microseconds, which is
the library's resolution everywhere and is finer than any filesystem here records reliably.

| Member | Signature | Description |
|---|---|---|
| `kind` | `kind(self) -> Kind` |  |
| `permissions` | `permissions(self) -> u32` | The permission bits alone -- the low twelve, which are the nine `rwx` plus setuid, setgid and the sticky bit. |
| `is_file` | `is_file(self) -> bool` |  |
| `is_dir` | `is_dir(self) -> bool` |  |
| `is_link` | `is_link(self) -> bool` | Whether the entry **itself** is a symbolic link, which only `link_metadata` can ever answer `true` to: `metadata` follows links, so what it describes is never one. |
| `same_file` | `same_file(self, other: Meta) -> bool` | Whether two readings name the same file, which is the inode and the device together and is not the path. |

### `Walk`

```sysl
struct Walk
    private stack: Buf[Frame]
    private root: string
    private started: bool
    private up: bool
    private open_next: Option[Entry]
    private pruned: bool
```

A cursor over a directory tree, reporting the root and then everything under it.

**The default order is parents first**, which is what a caller reproducing a tree needs: a
directory has to exist before anything can be written into it. `bottom_up` reverses that for the
callers that need the opposite, `remove_dir_all` being the one in this file -- `rmdir` refuses a
directory that is not empty.

**A failure is reported as an item rather than ending the walk**, so a directory that cannot be
read is one `Err` and the walk carries on with what is left. That is the axis `remove_dir_all` and
a best-effort cleanup disagree about, and neither answer is now the library's: `?` in the loop
body makes the first `Err` fatal, and ignoring it makes the walk best effort.

| Member | Signature | Description |
|---|---|---|
| `bottom_up` | `bottom_up(self) -> Walk` | The same walk, reporting a directory *after* everything under it. |
| `skip_dir` | `skip_dir(*self)` | Do not descend into the directory just reported. |
| `matching` | `matching(self, pattern: string) -> Matching` | The same walk, reporting only the entries whose path matches a glob. |

## Implementations

### Display for IoError

```sysl
impl Display for IoError
```

So that `print(e)` and `f"$e%s"` say the sentence rather than the shape of the value.

### Display for Kind

```sysl
impl Display for Kind
```

So that a kind prints as the word rather than as a number, and `Special` says what it is carrying:
a program reporting what it found in a directory has one thing to print, and the alternative is a
`match` at every such site.

### Eq for IoError

```sysl
impl Eq for IoError
```

So that a caller can say `e == NotFound` rather than `e.code() == 2`.

**The enum's own docstring is what asks for this**: it says the cases are named so that a program
acting on *why* can match rather than compare against a number, since "a comparison against a
number is a guess that compiled" -- and without an `Eq` the only comparison available was exactly
that. This module's own test wrote `assert_eq(e.code(), 2)` for want of it.

**A payload is why it has to be written out.** An enum no variant of which carries anything gets
`==` on its discriminant for free; `Other` carries a code, so this one does not.

It compares on `code()`, which makes `Other(2)` equal to `NotFound` -- deliberately, because they
are the same condition wearing two spellings, and `from_errno` never produces the first. Comparing
on the shape instead would make an error that came from a platform sysl does not have a named case
for unequal to itself after a round trip through a number.

### Eq for Kind

```sysl
impl Eq for Kind
```

### Fallible for File

```sysl
impl Fallible for File
```

Whether anything has gone wrong on this file since it was opened. It latches, so it is worth
asking once after a run of writes rather than after each -- which is the whole reason the surface
reports this way (`reference/errors.md § The third shape: a latch`).

**It is written once, here, and that is what makes the two `impl` blocks below answerable.** A
`failed` declared by `Reader` and another declared by `Writer` would be two members of one name on
one type -- allowed, but a call could not say which it meant, since `failed` takes no arguments
and a program that reads and writes a file has both traits in scope. Both requiring `Fallible`
instead leaves this the single answer each of them reaches.

### Iterate for Matching

```sysl
impl Iterate for Matching
```

### Iterate for Walk

```sysl
impl Iterate for Walk
```

### Reader for File

```sysl
impl Reader for File
```

Reading bytes out of a file is the `Reader` of `sysl.io`, whole and unmodified, which is what lets
`lines(&f)` work over a file with nothing here knowing about lines.

An empty answer means the end of the input and nothing more. Whether the input ended *badly* is
what `failed` reports, and the two are separate questions because a caller that does not care
should not have to ask -- the same split `FdReader` makes.

### Writer for File

```sysl
impl Writer for File
```

And writing them is the `Writer` of `library/core.md § What is in it`, so everything that renders
into a sink renders into a file. It latches rather than returning, which is what keeps
`f.write(b)` a statement.
