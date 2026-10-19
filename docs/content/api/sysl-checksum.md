---
title: sysl.checksum
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.checksum
summary: "Checksums that detect accidental corruption and nothing else."
requires: "no alloc"
---

**A CRC is not a hash in the sense `sysl.crypto` means.** Anyone can build a second input with
the same CRC-32 on purpose, so it must never stand in for a digest where an attacker gains from a
collision. It is what zip, gzip, PNG and Ethernet put on the wire, and for those it is exactly
right.

## Index

[`crc32_poly`](#crc32_poly) [`crc32_table`](#crc32_table) [`crc32`](#crc32) [`make_crc32_table`](#make_crc32_table) [`new_crc32`](#new_crc32) [`Crc32`](#crc32-1)

## Constants

### `crc32_poly`

```sysl
const crc32_poly: u32 = 3988292384
```

## Values

### `crc32_table`

```sysl
val crc32_table: [256]u32 = make_crc32_table()
```

## Functions

### `crc32`

```sysl
crc32(data: []const u8) -> u32
```

The CRC-32 of one slice of bytes: initial value and final xor `0xffffffff`, polynomial
`0xedb88320` reflected. `crc32("123456789".bytes)` is `0xcbf43926`, the standard check value, and
the empty input is `0`.

### `make_crc32_table`

```sysl
const make_crc32_table() -> [256]u32
```

### `new_crc32`

```sysl
new_crc32() -> Crc32
```

A checksum ready for its first `update`.

## Types

### `Crc32`

```sysl
struct Crc32
    private state: u32
```

A CRC-32 in progress, for input that arrives in pieces.

It is an ordinary struct, so copying one starts another from the same point. `update` may be
called as often as you like and `finish` answers the checksum so far without ending anything.

| Member | Signature | Description |
|---|---|---|
| `update` | `update(*self, data: []const u8)` |  |
| `finish` | `finish(self) -> u32` |  |
