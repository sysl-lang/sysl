---
title: sysl.fs.watch
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.fs.watch
summary: "Watching files and directories for changes: a `Watcher` holding any number of watches, whose descriptor is readable while it has something to report, and a `read` that answers what changed."
requires: "requires { os }"
---

val w = Watcher.open()?
    val site = w.add("site")?

    loop
        if w.readable(500.ms)?
            for e in w.read()?.view()
                rebuild(e.path)

**The descriptor is the kernel's** -- a kqueue on Darwin and the BSDs, an inotify instance on Linux
-- so a watcher is waited on as any descriptor is: `readable` here, `sysl.io.wait.ready` on `fd()`,
or registered with a `sysl.io.poll.Poller` among sockets and pipes, which is how a server or a
fiber runtime waits for a change without a thread of its own. **Readable is "look", not a
promise**: a `read` that follows may answer nothing, a change undone before it was read being no
change (below). `read` itself never waits.

## What is reported

| change | a watched file | a watched directory |
|---|---|---|
| `Created` | -- | an entry appeared in it |
| `Removed` | the file itself went | an entry went from it, or the directory itself went |
| `Modified` | its contents or its length changed | -- |
| `Renamed` | it moved; `to` is `None` | an entry moved, `path` to `to`; or it moved, `to` `None` |
| `Attributes` | its mode, owner, times or link count changed | its own changed |
| `Overflow` | events were lost: what is watched should be looked at again | |

**A directory watch is of its entries, not their contents.** A file written, truncated or chmod-ed
inside a watched directory is not reported, on either system: inotify would say so, but kqueue
cannot without holding a descriptor for every entry, and a directory of a few hundred files would
use up a process's descriptors. A program following a file's contents watches the file.

**Nothing is recursive.** A directory's subdirectories are entries like any other: one appearing
is `Created`, and what happens inside it is not seen until it is watched itself. inotify has no
recursive watch, and one built by adding each new subdirectory as it appears misses what is made
in it before the add, so none is offered.

**The events describe what changed, not every operation.** kqueue folds every change to one
watched object between two reads into one notification, and under it a directory's entries are a
snapshot of names and inode numbers compared with a fresh listing, so a file made and removed
between two reads is no event at all, and three writes may be one `Modified`. inotify queues each
operation, and an event the same as the one before it is folded into it. Where kqueue has folded
several kinds of change to one object into one notification, they come back in one fixed order --
`Modified`, `Attributes`, `Renamed`, `Removed` -- not the order they happened in. Either way a
program reads what is there now when it needs to be sure.

**A rename is one event where both names are watched** -- within one directory, or between two
directories this watcher watches on one filesystem -- with `path` the old name and `to` the new.
A move out of everything watched is `Removed`, and a move in is `Created`. A watched file or
directory that is itself moved is `Renamed` with `to` `None`, since neither system says where it
went, and **the watch follows it**: later events still name it by the path it was added with.

**A watch ends when what it watches is removed**, after the `Removed`; `remove` on it then answers
`NotFound`.

**`Overflow`** is inotify's queue having filled -- 16,384 events by default -- before they were
read; under kqueue, which has no queue to fill, it is a watched directory that could not be listed
again. Either way some changes were not reported, and the program looks at what it watches afresh.

A target with neither kqueue nor inotify answers every call with the error
`sysl.fs.unsupported()` stands for. It needs an operating system: a freestanding program reaching
it answers the `sysl_fs_watch_*` hooks itself (`sysl.fs.sys`).

## Index

[`Change`](#change) [`Event`](#event) [`Watch`](#watch) [`Watcher`](#watcher) [Wait for Watcher](#wait-for-watcher)

## Types

### `Change`

```sysl
enum Change
    Created
    Removed
    Modified
    Renamed
    Attributes
    Overflow
```

What happened to a watched file or directory, or to an entry of a watched directory.

### `Event`

```sysl
struct Event
    change: Change
    path: string
    to: Option[string]
    watch: Option[Watch]
```

One change: what it was, the path it concerns, the new path of a rename where it is known, and
the watch that saw it.

`path` is built from the path the watch was added with -- the path itself for a watched object, or
that path joined to an entry's name -- so a relative path added is a relative path reported.

### `Watch`

```sysl
struct Watch
    private[watch] id: int
```

One path being watched, as `add` answered it: what `remove` takes, and what each `Event` names.

### `Watcher`

```sysl
struct Watcher
    private[watch] state: &sync WatcherState
```

A set of watches and the descriptor that is readable while any of them has something to report.

**It closes itself**: every copy shares one watcher, whose descriptor is closed when the last copy
goes. `close` gives it back at a moment of the program's own; every call after it, through any
copy, answers `NotOpen`. A `read`, `add` or `remove` racing a `close` on another thread is the
program's to order, as it is in C.

| Member | Signature | Description |
|---|---|---|
| `open` | `open() -> Result[Watcher, IoError]` | A watcher watching nothing yet. |
| `add` | `add(self, path: string) -> Result[Watch, IoError]` | Start watching the file or directory at `path`, answering the watch its events will name. |
| `remove` | `remove(self, w: Watch) -> Result[unit, IoError]` | Stop a watch: no event of it is read afterwards, including one that happened before the call and was not yet read. |
| `read` | `read(self) -> Result[Buf[Event], IoError]` | Every change there is to report, in the order it was seen -- or none, at once, where there is nothing. |
| `fd` | `fd(self) -> Result[int, IoError]` | The watcher's descriptor, lent: readable while there is something to `read`, for registering with a `sysl.io.poll.Poller` or waiting on with `sysl.io.wait.ready`. |
| `close` | `close(self) -> Result[unit, IoError]` | Close the descriptor and every watch now, rather than when the last copy goes, and say whether that worked. |

## Implementations

### Wait for Watcher

```sysl
impl Wait for Watcher
```

Waiting on a watcher with a time limit: `readable` is whether a `read` would find the kernel
holding something, within `timeout`. A watcher is never written, so `writable` answers whether its
descriptor is open.
