---
title: sysl.time.tzif
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.time.tzif
summary: "Reading a zone out of the bytes of a TZif file (RFC 8536), which is what the IANA time zone database is distributed as."
---

**This module asks for no capability, and that is the whole reason it is a module of its own.** A
zone by name lives in a file, and reading a file needs an operating system -- so a decoder written
beside `sysl.fs` would take the whole of `sysl.time` down with it, exactly as a `now()` written
beside `Instant` would have. What is here is the *decoding*, which is arithmetic over bytes
somebody else supplied; `sysl.posix.time.zone_data` is what fetches them on a host.

**The split is not bookkeeping — it is what makes a zone reachable on a bare machine.** A board
with an RTC can carry one zone's bytes in flash, the zone it was deployed in, and resolve local
time with no filesystem anywhere. Weld the decoder to the file reading and a target with no
operating system gets nothing at all.

## Nothing is copied, and nothing is allocated

A `Zone` is a handful of offsets **into the caller's bytes**, and every lookup reads the packed
arrays where they lie. That is what lets a zone be used with no allocator: the storage is the file,
which the caller already has, and this adds a few words beside it.

The cost is the ordinary one for a borrowed view: **the bytes must outlive the `Zone`.** A `Zone`
whose buffer has been released reads freed memory, exactly as any slice would.

## What is decoded and what is skipped

A lookup needs three of the file's arrays -- the transition times, the type index for each, and the
types themselves -- and the designations for a name. **Leap seconds are skipped**, because POSIX
time ignores them by definition and `Instant` counts the same way, so a table of them describes a
timeline this library does not have. The standard/wall and UT/local indicators are skipped for the
same reason they exist: they matter to somebody re-interpreting a POSIX `TZ` string, and nothing
here does that.

**The version 2+ block is the one read**, where the file has one -- its transition times are 8
bytes, and a 4-byte time runs out in 2038. A version 1 file is still read, from its only block.
The footer's `TZ` string, which extrapolates past the last transition, is **not** read: a lookup
past the end answers with the last type, which is what the table says and is right until a zone
changes its rules again.

## Index

[`abbrev_at`](#abbrev_at) [`is_dst_at`](#is_dst_at) [`offset_at`](#offset_at) [`parse`](#parse) [`TzifError`](#tziferror) [`Zone`](#zone) [Display for TzifError](#display-for-tziferror)

## Functions

### `abbrev_at`

```sysl
abbrev_at(z: Zone, t: Instant) -> Result[string, TzifError]
```

The abbreviation the zone used at that instant -- `EST`, `EDT`, `+0545`.

**It is here because the file already carries it**, and it is the one thing a host's `tm_gmtoff`
route cannot answer without a second call. It is a display string and nothing else: `CST` names
three different offsets around the world, so a timestamp that has to be read back carries the
number instead, which is what `timestamp_text` writes.

### `is_dst_at`

```sysl
is_dst_at(z: Zone, t: Instant) -> Result[bool, TzifError]
```

Whether daylight saving was in force at that instant, as the file records it.

### `offset_at`

```sysl
offset_at(z: Zone, t: Instant) -> Result[Offset, TzifError]
```

How far this zone's clocks were set from UTC at that instant.

**This is the shape `sysl.time.resolve` takes**, which is the whole point of it: a zone read from a
file, a zone talking to a chip and a zone written out by hand are one thing to everything above.

### `parse`

```sysl
parse(data: []const u8) -> Result[Zone, TzifError]
```

Reads a zone out of the bytes of a TZif file.

**The `Zone` borrows `data`** -- see the note above about what that costs.

A version 2 or later file carries its table twice: once with 4-byte transition times for a reader
that predates the format's second version, and then again with 8-byte ones. The first is stepped
over rather than read, which is why the length of it has to be worked out even though nothing in
it is wanted.

## Types

### `TzifError`

```sysl
enum TzifError
    BadMagic
    BadVersion(saw: u8)
    Truncated(needed: usize)
    NoTypes
```

Why a decode refused, at the granularity a caller can act on.

The shape is `sysl.encoding.DecodeError`'s and for its reason: the variants are separated by what a
caller would **do** about each. `BadMagic` is a file that is not one of these at all -- the usual
cause being a path that named something else. `Truncated` carries the length that would have been
enough, so a caller reading in pieces knows how much more to get rather than doubling until it
fits.

### `Zone`

```sysl
struct Zone
    data: []const u8
    trans_at: usize
    types_at: usize
    ttinfo_at: usize
    desig_at: usize
    timecnt: usize
    typecnt: usize
    wide: bool
```

A zone, as a view of the bytes it was decoded from.

## Implementations

### Display for TzifError

```sysl
impl Display for TzifError
```
