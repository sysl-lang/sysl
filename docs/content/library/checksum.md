---
title: The checksum module
summary: "`sysl.checksum` — CRC-32 (zip, PNG, Ethernet), CRC-32C (iSCSI, ext4, RocksDB) on the machine's instruction where it has one, and a stable seeded 64-bit hash, all with no allocator."
weight: 60
---

`sysl.checksum` holds checksums that catch accidental corruption, and a fast hash for tables.
**Neither is a digest**: a second input with the same CRC or the same hash can be built on purpose,
so where an attacker gains from a collision use [`sysl.crypto`](/library/crypto/).

## CRC-32

The polynomial is IEEE 802.3's, reflected (`0xedb88320`), with initial value and final xor both
`0xffffffff` — the CRC zip, gzip, PNG and Ethernet carry. Its 256-entry table is data laid down at
compile time, so the module costs no start-up work and builds for a freestanding target.

```sysl
import sysl.checksum.{crc32, new_crc32}

print(crc32("123456789".bytes))

var c = new_crc32()

c.update("1234".bytes)
c.update("56789".bytes)
print(c.finish())
print(crc32("".bytes))
```

```output
3421780262
3421780262
0
```

`0xcbf43926`, which is 3421780262, is the standard check value for these nine bytes. `Crc32` is an
ordinary struct, so copying one starts another from the same point, and `finish` does not end it:
more input may follow.

## CRC-32C

The same construction over Castagnoli's polynomial (`0x1edc6f41`, reflected `0x82f63b78`), with the
same initial value and final xor: the CRC iSCSI, ext4, Btrfs and RocksDB put on a page or a log
record. It catches every burst of up to 32 flipped bits, and at the lengths a page has it misses fewer
patterns of scattered flips than CRC-32 does. The interface is CRC-32's:

```sysl
import sysl.checksum.{crc32c, new_crc32c}

print(crc32c("123456789".bytes) == 0xe3069283)

var c = new_crc32c()

c.update("1234".bytes)
c.update("56789".bytes)
print(c.finish() == 0xe3069283, crc32c("".bytes))
```

```output
true
true 0
```

**Where the machine has the instruction, `crc32c` is the instruction**, eight bytes to a step:
AArch64's `crc32cx` or SSE 4.2's `crc32`. That is every Arm Mac, and any AArch64 or x86-64 build that
promises it — `codegen { extensions = ["crc"] }` or `["sse4.2"]` in `sysl.hocon`, or `--cpu native`
on a machine that has it. Everywhere else, a board included, it is a slice-by-8 table: 8 KiB of data
laid down at compile time, eight lookups per eight bytes. The `#if` symbol that says which is
`crc32c` ([attributes](/reference/attributes/#the-symbols-are-derived-from-the-target-and-the-set-is-closed)).
Both give the same answer, so a page checksummed on one machine is checked on any other. On an M-series
Mac, over a 1 MB buffer, the instruction runs at about 10 GB/s and the table at about 2.3 GB/s.

## `hash64` — a seeded hash for tables

`hash64(data, seed)` is a 64-bit hash for a hash table, a Bloom filter or a sketch: **rapidhash** (V3,
its default build), the successor to wyhash, at a few cycles per short key and about 35 GB/s over a
long one on an M-series Mac. `seed` picks one of 2^64 unrelated functions over the same bytes, so a Bloom filter's probes are
`hash64(key, 0)`, `hash64(key, 1)` and so on; leaving it out means `0`.

```sysl
import sysl.checksum.hash64

print(hash64("hello world".bytes) == 0x2f27cb27d5240940)
print(hash64("hello world".bytes, 1) != hash64("hello world".bytes, 2))
```

```output
true
true
```

**The value is stable.** It is the reference implementation's `rapidhash_withSeed`, bit for bit, on
every target and in every release: the bytes are read least significant first, never as the machine's
words, so a hash written into a file names the same bucket on another machine and after an upgrade.
Changing it would be a breaking change.

It is **not** `Hash`: the trait's mixers (`library/core.md § Hashing`) hash a value of a type and are
unseeded, where `hash64` hashes bytes under a seed. Nor is it a MAC — a seed an attacker does not know
keeps them from building collisions blind, but it is no key in the cryptographic sense — nor a CRC,
which catches a short burst of flipped bits every time where a hash catches it with high probability.
