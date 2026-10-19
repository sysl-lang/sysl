---
title: The checksum module
summary: "`sysl.checksum` — CRC-32, the zip, PNG and Ethernet one, in one call or fed in pieces, with no allocator."
weight: 60
---

`sysl.checksum` holds checksums that catch accidental corruption. **A CRC is not a digest**: a second
input with the same CRC-32 can be built on purpose, so where an attacker gains from a collision use
[`sysl.crypto`](/library/crypto/).

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
