---
title: sysl.posix.time
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.posix.time
summary: "Reading the two clocks the host keeps, which is the one thing `sysl.time` deliberately cannot do."
requires: "requires { posix }"
---

**This is a module of its own so that `sysl.time` is not.** The calendar and the arithmetic ask for
no capability and run on a bare machine; `clock_gettime(2)` is asking an operating system for
something, and a capability requirement is module-wide -- so a `now()` written next to `Instant`
would cost the whole of the date-time library its freestanding reach for one function. `sysl.rand`
and `sysl.posix.rand` are split for exactly this reason, and that file's header makes the argument
at length.

## The two clocks are different clocks, and the return types say so

`now` answers an `Instant`: a point on the timeline, counted from 1970, comparable with one taken
on another machine. It is the clock somebody can *set*, so it can move backwards, and subtracting
two readings of it to time something is a measurement that a passing `ntpd` can make negative.

`monotonic` answers a `Duration`, not an `Instant`, and that is the whole of the distinction: it is
counted from an origin nobody specifies -- boot, usually -- so a single reading means nothing and
only the difference of two does. It cannot be set and cannot go backwards, which is what makes it
the one to measure with. Giving it a type that no calendar function accepts is what stops it being
mistaken for a timestamp.

## What a freestanding target does instead

**Nothing here, and deliberately nothing shared with it.** A board's clock is a board's decision:
two boards carrying the same chip -- so the same *target* -- can count time from a different RTC,
which is precisely the case a `#if` on the target cannot express. So an embedded environment
supplies a module of its own with these two function names, in its own package, and a program picks
its clock by which one it imports.

**That used to give up a program compiling unchanged against both, and it no longer does.** The
shape this paragraph described as unbuilt -- a symbol declared in capability-free `sysl.time` that
host and board alike link an implementation of -- is built: `sysl.time.now` and
`sysl.time.monotonic` are it, `library/sysl/time/clock.sysl` carries the argument, and the two
`@export`s at the foot of this file are what answer it on a host. The names above are unchanged, as
that paragraph promised, so nothing that imported this module has moved.

**Both spellings are right and neither is deprecated.** A program that is only ever going to run on
a host imports this module and calls `now` directly, which needs no seam and no linker cooperation.
A program that wants to move imports `sysl.time` instead and lets whatever it is linked against
decide.

## Index

[`default_zoneinfo_root`](#default_zoneinfo_root) [`from_local`](#from_local) [`local`](#local) [`local_offset`](#local_offset) [`local_text`](#local_text) [`local_zone_data`](#local_zone_data) [`monotonic`](#monotonic) [`nanosleep`](#nanosleep) [`now`](#now) [`sleep`](#sleep) [`supply_monotonic_us`](#supply_monotonic_us) [`supply_wall_us`](#supply_wall_us) [`zone_data`](#zone_data) [`zone_data_in`](#zone_data_in) [`zoneinfo_root`](#zoneinfo_root)

## Constants

### `default_zoneinfo_root`

```sysl
const default_zoneinfo_root: string = "/usr/share/zoneinfo"
```

Where the operating system keeps the database when nothing says otherwise. Every system this
module compiles for uses this path -- macOS symlinks it at `/var/db/timezone/zoneinfo` and answers
here too.

## Functions

### `from_local`

```sysl
from_local(ldt: LocalDateTime) -> Resolution
```

A wall clock reading in the host's zone, resolved to the instant or instants it names -- or to
neither, where the clocks skipped it.

### `local`

```sysl
local(t: Instant) -> LocalDateTime
```

An instant as the host's own clocks were showing it.

### `local_offset`

```sysl
local_offset(t: Instant) -> Offset
```

How far the host's clocks were set from UTC at that instant.

It takes an instant rather than answering "the current offset" because those are different
questions wherever a zone moves its clocks, and the one a caller almost always wants is this one:
the offset that applied to *the timestamp being rendered*, not the one that applies now.

### `local_text`

```sysl
local_text(t: Instant) -> string
```

An instant as a timestamp in the host's zone: `2026-08-22T13:26:04-04:00`.

The offset is written out rather than an abbreviation like `EDT`, and that is RFC 3339's choice
rather than a shortcut: an abbreviation is ambiguous across zones -- `CST` is three different
offsets -- so a timestamp carrying one cannot be read back reliably, and `parse_timestamp` reads
what this writes.

### `local_zone_data`

```sysl
local_zone_data() -> Result[[]u8, IoError]
```

The bytes of the zone **this host is set to**, which is what `/etc/localtime` is: a copy of, or a
symlink to, one file of the database.

It answers what `local_offset` answers and more -- the whole table rather than one instant's
offset, so a program can ask this zone for an abbreviation, or hand it to `resolve` without a
capability crossing the call. What it cannot answer is the zone's **name**: the file does not carry
one, and where `/etc/localtime` is a symlink the name is the link's target rather than anything
inside it.

### `monotonic`

```sysl
monotonic() -> Duration
```

A monotonic counter, as a length of time from an origin this deliberately does not name.

One reading answers nothing. **Two readings subtracted answer how long something took**, and that
is the only question it can be asked:

val t0 = monotonic()
work()
print(s"took ${(monotonic() - t0).us}us")

It never goes backwards and nothing can set it, which is the difference from `now` and the reason
a measurement wants this one.

### `nanosleep`

```sysl
nanosleep(nanos: long) -> long
```

Wait for `nanos` nanoseconds, and answer how many were still owed when it stopped.

**Zero means the whole time passed.** Anything else is what a signal interrupted, and handing it
back rather than swallowing it is the difference from `sleep`: a program with a handler installed
can tell "the wait finished" from "something happened", which one call to `sleep` cannot.

    var left = nanosleep(2_000_000)

    while left > 0 && !cancelled()
        left = nanosleep(left)

**Nanoseconds, where `sleep` takes a `Duration`, and that is what this is for.** A `Duration`
counts microseconds, so it cannot name a wait shorter than one -- and `nanosleep(2)` can. Reach
for `sleep` unless you need the resolution or the remainder.

### `now`

```sysl
now() -> Instant
```

The wall clock: where the host thinks it is on the timeline.

**Two readings of this may differ by anything at all**, including a negative amount, because it is
the clock a person and an `ntpd` are both allowed to set. Use it to stamp something, and use
`monotonic` to measure something.

### `sleep`

```sysl
sleep(d: Duration)
```

Wait for `d`, however many attempts that takes.

**A signal cuts a wait short, and this one carries on.** `nanosleep(2)` returns early with `EINTR`
and the time still owed, so a wait written as one call is a wait that is quietly shorter than it
asked for whenever anything arrives -- which is exactly when a test that depends on it starts
looking flaky. This retries with the remainder until nothing is left.

**It answers nothing, because there is nothing left to say**: it returns when the time has passed.
A caller that wants to *know* it was interrupted wants `nanosleep` below.

    sleep(50.ms)

A duration of zero or less returns at once rather than yielding, which is not the same thing --
`sleep(0.s)` is not a way to offer the processor to something else.

### `supply_monotonic_us`

```sysl
supply_monotonic_us() -> long
```

Answers `sysl.time`'s monotonic seam. **Call `monotonic` instead** -- this exists to be linked
rather than called, and `supply_wall_us` beside it says why it is public.

### `supply_wall_us`

```sysl
supply_wall_us() -> long
```

Answers `sysl.time`'s wall-clock seam. **Call `now` instead** -- this exists to be linked, not to
be called, and it is public only because an `@export` may not be `private`: a private declaration
promises every caller is inside its module and an export promises the opposite, so the compiler
refuses a definition making both claims.

The name says what it is for rather than what it reads, so that it does not compete with anything
a program would reach for -- and a package answering the same seam on a board writes the same two
lines with its own chip behind them.

### `zone_data`

```sysl
zone_data(name: string) -> Result[[]u8, IoError]
```

The bytes of a named zone -- `"America/Toronto"`, `"Europe/Paris"`, `"UTC"`.

The name is a path under the database root, which is why a region-qualified one has a `/` in it and
why nothing here parses it: the database's own layout is the naming scheme.

### `zone_data_in`

```sysl
zone_data_in(root: string, name: string) -> Result[[]u8, IoError]
```

The same, from a database somewhere else -- a copy shipped with an application, a mount, or a
directory a test wrote.

Through `sysl.path.join` rather than a `+ "/" +`, which is the one case a hand-rolled join gets
wrong: a root written with a trailing separator would otherwise put two in the middle, and a name
that is already absolute would be appended to rather than replacing what is in front of it.

### `zoneinfo_root`

```sysl
zoneinfo_root() -> string
```

The database root this host is using: `TZDIR` where it is set, and the path above where it is not.

**`TZDIR` is the convention every other reader of this database follows**, which is what makes it
worth consulting rather than a nicety: it is how a distribution relocates the database, how a
container image carries its own copy, and how a test points at one it wrote. A reader that ignored
it would disagree with `date` and `zdump` on the same machine.
