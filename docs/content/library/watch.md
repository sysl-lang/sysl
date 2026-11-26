---
title: The watch module
summary: "`sysl.fs.watch` — watching files and directories for changes: `Watcher.open`, `add` and `remove` a path, a `read` that never waits and answers `Created`, `Removed`, `Modified`, `Renamed`, `Attributes` and `Overflow` with the path each concerns, and `fd()` — the kernel's own descriptor, readable while there is something to read, so a watcher is waited on with `readable`, `sysl.io.wait.ready` or a `Poller`. kqueue on Darwin and the BSDs, inotify on Linux."
weight: 78
---

`sysl.fs.watch` tells a program what changed in the files and directories it watches. A watcher holds
any number of watches; its descriptor is readable while it has something to report, and `read`
answers what that is:

```sysl
import sysl.fs.{make_temp_dir, remove_dir_all, rename, write_text}
import sysl.fs.watch.Watcher
import sysl.io.wait.Wait
import sysl.path.{join, relative_to}
import sysl.time.millis

val root = make_temp_dir("watch").unwrap()
val w = Watcher.open().unwrap()

_ = w.add(root).unwrap()
write_text(join(root, "draft"), "x").unwrap()
print(w.readable(millis(1000)).unwrap())

for e in w.read().unwrap().view()
    print(e.change, relative_to(e.path, root).unwrap())

rename(join(root, "draft"), join(root, "final")).unwrap()

for e in w.read().unwrap().view()
    print(e.change, relative_to(e.path, root).unwrap(), relative_to(e.to.unwrap(), root).unwrap())

remove_dir_all(root).unwrap()
```

```output
true
Created draft
Renamed draft final
```

```sysl
struct Watcher
    open() -> Result[Watcher, IoError]
    add(self, path: string) -> Result[Watch, IoError]
    remove(self, w: Watch) -> Result[unit, IoError]
    read(self) -> Result[Buf[Event], IoError]
    fd(self) -> Result[int, IoError]
    close(self) -> Result[unit, IoError]

struct Event
    change: Change
    path: string
    to: Option[string]
    watch: Option[Watch]

enum Change
    Created
    Removed
    Modified
    Renamed
    Attributes
    Overflow
```

`Watch` is what `add` answers: the watch an event names, and what `remove` takes. `Watcher` has
[`sysl.io.wait.Wait`](/library/io/#waiting-with-a-time-limit-sysl-io-wait), so `readable(timeout)` waits
on it directly.

## What is reported

| change | a watched file | a watched directory |
|---|---|---|
| `Created` | — | an entry appeared in it |
| `Removed` | the file itself went | an entry went from it, or the directory itself went |
| `Modified` | its contents or its length changed | — |
| `Renamed` | it moved; `to` is `None` | an entry moved, `path` to `to`; or it moved, `to` `None` |
| `Attributes` | its mode, owner, times or link count changed | its own changed |
| `Overflow` | changes were lost: look at what is watched again | |

`path` is built from the path the watch was added with — the path itself, or that path joined to an
entry's name. **A truncation is `Modified` on both systems**: kqueue calls it a change of attributes,
and the watcher checks the length to say otherwise.

**A directory watch is of its entries, not their contents.** A file written to or `chmod`-ed inside a
watched directory is not reported, on either system: inotify would say so, but kqueue cannot without
holding a descriptor for every entry, and a directory of a few hundred files would use up a process's
descriptors — macOS starts a process with 256. A program following a file's contents watches the file:

```sysl
import sysl.fs.{append_text, make_temp_dir, remove_dir_all, write_text}
import sysl.fs.watch.Watcher
import sysl.path.{join, relative_to}

val root = make_temp_dir("watch").unwrap()
val log = join(root, "log")

write_text(log, "").unwrap()

val w = Watcher.open().unwrap()

_ = w.add(root).unwrap()
append_text(log, "line\n").unwrap()
print(w.read().unwrap().len())

_ = w.add(log).unwrap()
append_text(log, "line\n").unwrap()

for e in w.read().unwrap().view()
    print(e.change, relative_to(e.path, root).unwrap())

remove_dir_all(root).unwrap()
```

```output
0
Modified log
```

**Nothing is recursive.** A subdirectory is an entry like any other: one appearing is `Created`, and
what happens inside it is not seen until it is watched itself. inotify has no recursive watch, and one
built by adding each new subdirectory as it appears misses whatever is made in it before the add — so
none is offered rather than one that is sometimes wrong.

## The events are what changed, not every operation

kqueue folds every change to one watched object between two reads into one notification, and says of a
directory only that its entries changed. So the watcher keeps a snapshot of each watched directory's
names and inode numbers and compares it with a fresh listing when kqueue says to look: a file made and
removed between two reads is no event at all, and three writes may be one `Modified`. inotify queues
each operation, and an event the same as the one just before it is folded into it. Where kqueue has
folded several kinds of change to one object into one notification — a file renamed and then written
before a read — they come back in one fixed order (`Modified`, `Attributes`, `Renamed`, `Removed`)
rather than the order they happened in. **Either way, read
what is there now when it has to be certain** — the events say where to look.

The same holds for the descriptor: **readable is "look", not a promise.** The kernel had something,
and the `read` that follows may still answer nothing, a change undone before it was read being no
change.

## A rename is one event where both names are watched

Within one watched directory, or between two directories this watcher watches on one filesystem, a
move is one `Renamed`, `path` the old name and `to` the new — inotify pairs the two halves by their
cookie, and the snapshots pair them by the inode that went under one name and came under the other.
A move onto an existing name is that one `Renamed`, not also a `Removed` of the name it replaced. A
move out of everything watched is `Removed`, and a move in is `Created`.

**A watched file or directory that is itself moved is `Renamed` with `to` `None`** — neither system
says where it went — and **the watch follows it**: later events still name it by the path it was added
with.

```sysl
import sysl.fs.{append_text, make_temp_dir, remove_dir_all, rename, write_text}
import sysl.fs.watch.Watcher
import sysl.path.{join, relative_to}

val root = make_temp_dir("watch").unwrap()
val f = join(root, "notes")

write_text(f, "").unwrap()

val w = Watcher.open().unwrap()

_ = w.add(f).unwrap()
rename(f, join(root, "notes.old")).unwrap()

for e in w.read().unwrap().view()
    print(e.change, relative_to(e.path, root).unwrap(), e.to.is_none())

append_text(join(root, "notes.old"), "more").unwrap()

for e in w.read().unwrap().view()
    print(e.change, relative_to(e.path, root).unwrap(), e.to.is_none())

remove_dir_all(root).unwrap()
```

```output
Renamed notes true
Modified notes true
```

## Watches begin, end and are shared

**A path already watched — under that name or another, a symbolic link being followed — answers the
watch it already has**, so adding twice costs nothing and one `remove` stops both. `remove` stops a
watch at once: nothing of it is read afterwards, not even a change made before the call and not yet
read. **A watch ends by itself when what it watches is removed**, after the `Removed`; `remove` of it
then answers `NotFound`, as it does for a watch never added.

A path that does not exist is `NotFound`. inotify refuses a watch past its limit
(`/proc/sys/fs/inotify/max_user_watches`) as a lack of space, and a watcher past 128 per user
(`max_user_instances`) as running out of descriptors.

**A watcher closes itself**: every copy shares one, and its descriptor and every watch go when the
last copy does. `close` gives them back at a moment of the program's own; every call afterwards,
through any copy, answers `NotOpen`, and a second `close` closes nothing.

## Waiting on a watcher

The descriptor is the kernel's — the kqueue, or the inotify instance — so a watcher is waited on as
any descriptor is. `readable` waits on that one; registered with a
[`Poller`](/library/poll/) it is one descriptor among a server's sockets and pipes, which is how a
loop or a [fiber](/library/fiber/) runtime waits for a change without a thread of its own. **`read`
never waits**: it answers what there is, or nothing, at once.

```sysl
import sysl.fs.{make_temp_dir, remove_dir_all, write_text}
import sysl.fs.watch.Watcher
import sysl.io.poll.{Event, Poller}
import sysl.io.wait.Readable
import sysl.path.join
import sysl.time.millis

val root = make_temp_dir("watch").unwrap()
val w = Watcher.open().unwrap()
val p = Poller.open().unwrap()
var got = [Event.none; 8]

_ = w.add(root).unwrap()
p.register(w.fd().unwrap(), [Readable], 7).unwrap()
print(p.wait(got[..], Some(millis(0))).unwrap())
write_text(join(root, "arrived"), "").unwrap()
print(p.wait(got[..], Some(millis(1000))).unwrap(), got[0].token)
print(w.read().unwrap().len())

remove_dir_all(root).unwrap()
```

```output
0
1 7
1
```

## Overflow

`Overflow` says changes were lost before they were read, and carries no path and no watch: the program
looks at what it watches afresh. Under inotify it is the kernel's queue filling
(`/proc/sys/fs/inotify/max_queued_events`, 16,384 by default) faster than the program reads. **kqueue
has no queue to fill** — its notifications fold — so there it is a watched directory that could not be
listed again (its read permission taken away, say), leaving its snapshot unknown.

## Where there is no kqueue or inotify

A target with neither answers every call with the error
[`sysl.fs.unsupported()`](/library/fs/) stands for. The module needs an operating system: a
freestanding program reaching it answers the `sysl_fs_watch_*` hooks of `sysl.fs.sys` itself, as it
does every other `sysl.fs` hook.
