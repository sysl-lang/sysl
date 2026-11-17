---
title: sysl.checksum
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.checksum
summary: "Checksums that detect accidental corruption, and a fast hash for tables -- nothing an attacker cannot forge."
requires: "no alloc"
---

**A CRC is not a hash in the sense `sysl.crypto` means.** Anyone can build a second input with
the same CRC-32 on purpose, so it must never stand in for a digest where an attacker gains from a
collision. It is what zip, gzip, PNG and Ethernet put on the wire, and for those it is exactly
right. CRC-32C is the same construction over a better polynomial, and is what iSCSI, ext4, Btrfs
and RocksDB put on a page or a log record.

## Index

[`crc32_poly`](#crc32_poly) [`crc32c_poly`](#crc32c_poly) [`crc32_table`](#crc32_table) [`crc32c_tables`](#crc32c_tables) [`crc32`](#crc32) [`crc32c`](#crc32c) [`hash64`](#hash64) [`make_crc32_table`](#make_crc32_table) [`make_crc32c_tables`](#make_crc32c_tables) [`new_crc32`](#new_crc32) [`new_crc32c`](#new_crc32c) [`Crc32`](#crc32-1) [`Crc32c`](#crc32c-1)

## Constants

### `crc32_poly`

```sysl
const crc32_poly: u32 = 3988292384
```

### `crc32c_poly`

```sysl
const crc32c_poly: u32 = 2197175160
```

## Values

### `crc32_table`

```sysl
val crc32_table: [256]u32 = make_crc32_table()
```

### `crc32c_tables`

```sysl
val crc32c_tables: [8][256]u32 = make_crc32c_tables()
```

## Functions

### `crc32`

```sysl
crc32(data: []const u8) -> u32
```

The CRC-32 of one slice of bytes: initial value and final xor `0xffffffff`, polynomial
`0xedb88320` reflected. `crc32("123456789".bytes)` is `0xcbf43926`, the standard check value, and
the empty input is `0`.

### `crc32c`

```sysl
crc32c(data: []const u8) -> u32
```

The CRC-32C of one slice of bytes: Castagnoli's polynomial `0x1edc6f41` (`0x82f63b78` reflected),
initial value and final xor `0xffffffff` -- iSCSI's, ext4's and RocksDB's. `crc32c("123456789".bytes)`
is `0xe3069283`, the standard check value, and the empty input is `0`.

**It is the machine's instruction where `#if crc32c` holds** -- every Arm Mac, and an AArch64 or
x86-64 build whose `codegen.extensions` names `crc` or `sse4.2` -- and a slice-by-8 table
everywhere else, a board included. The two give the same answer, so a checksum written on one
machine is checked on any other.

### `hash64`

```sysl
hash64(data: []const u8, seed: u64 = 0) -> u64
```

A 64-bit hash of `data` for a hash table, a Bloom filter or a sketch: **rapidhash** (V3, the
default "compact" build), the successor to wyhash. `seed` selects one of 2^64 unrelated hash
functions over the same bytes -- a Bloom filter's k probes, or a table that must not be the one an
adversary precomputed collisions against.

**The output is stable**: it is the reference implementation's `rapidhash_withSeed` exactly, bit
for bit, on every target and in every future release -- the input is read as bytes, least
significant first, never as the machine's words -- so a hash written into a file today names the
same bucket tomorrow and on another machine. Changing it would be a breaking change.

**It is not a MAC and not a checksum.** A seed an attacker does not know keeps them from building
collisions blind, but it is no secret key in the cryptographic sense; and a CRC catches a burst
of flipped bits that a hash only catches with high probability.

### `make_crc32_table`

```sysl
const make_crc32_table() -> [256]u32
```

### `make_crc32c_tables`

```sysl
const make_crc32c_tables() -> [8][256]u32
```

### `new_crc32`

```sysl
new_crc32() -> Crc32
```

A checksum ready for its first `update`.

### `new_crc32c`

```sysl
new_crc32c() -> Crc32c
```

A CRC-32C ready for its first `update`.

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

### `Crc32c`

```sysl
struct Crc32c
    private state: u32
```

A CRC-32C in progress, for input that arrives in pieces -- a page written in parts, a log record
and its header. Like `Crc32` it is an ordinary struct: copying one starts another from the same
point, and `finish` answers the checksum so far without ending anything.

| Member | Signature | Description |
|---|---|---|
| `update` | `update(*self, data: []const u8)` |  |
| `finish` | `finish(self) -> u32` |  |
