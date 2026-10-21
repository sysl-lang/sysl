---
title: sysl.crypto
layout: api-module
headingShift: 0
slugStyle: github
module: sysl.crypto
summary: "Cryptographic hashing: the SHA-2 family, SHA-1 and MD5, HMAC over any of them, and PBKDF2."
requires: "no alloc"
---

A digest is bytes rather than text, a hasher streams so a caller need not hold what it is hashing,
and nothing here allocates — which is what lets a board hash a firmware image with the heap given
up.

**What is broken is marked broken where it is declared.** MD5 and SHA-1 are here because a program
reading somebody else's format has no choice about which digest that format used, and each says in
its own header what it may and may not still be trusted for. `verify` is the constant-time
comparison, and a program checking a tag should reach for it rather than `==`.

## Index

[`hkdf1`](#hkdf1) [`hkdf1_expand`](#hkdf1_expand) [`hkdf224`](#hkdf224) [`hkdf224_expand`](#hkdf224_expand) [`hkdf256`](#hkdf256) [`hkdf256_expand`](#hkdf256_expand) [`hkdf384`](#hkdf384) [`hkdf384_expand`](#hkdf384_expand) [`hkdf512`](#hkdf512) [`hkdf512_expand`](#hkdf512_expand) [`hmac1`](#hmac1) [`hmac224`](#hmac224) [`hmac256`](#hmac256) [`hmac384`](#hmac384) [`hmac512`](#hmac512) [`hmacmd5`](#hmacmd5) [`md5`](#md5) [`new_md5`](#new_md5) [`new_sha1`](#new_sha1) [`new_sha224`](#new_sha224) [`new_sha256`](#new_sha256) [`new_sha384`](#new_sha384) [`new_sha512`](#new_sha512) [`pbkdf2_hmac1`](#pbkdf2_hmac1) [`pbkdf2_hmac224`](#pbkdf2_hmac224) [`pbkdf2_hmac256`](#pbkdf2_hmac256) [`pbkdf2_hmac384`](#pbkdf2_hmac384) [`pbkdf2_hmac512`](#pbkdf2_hmac512) [`sha1`](#sha1) [`sha224`](#sha224) [`sha256`](#sha256) [`sha384`](#sha384) [`sha512`](#sha512) [`verify`](#verify) [`Fault`](#fault) [`Md5`](#md5-1) [`Sha1`](#sha1-1) [`Sha224`](#sha224-1) [`Sha256`](#sha256-1) [`Sha384`](#sha384-1) [`Sha512`](#sha512-1) [Compression for Md5C](#compression-for-md5c) [Compression for Sha1C](#compression-for-sha1c) [Compression for Sha2Narrow](#compression-for-sha2narrow) [Compression for Sha2Wide](#compression-for-sha2wide) [Word for u32](#word-for-u32) [Word for u64](#word-for-u64)

## Functions

### `hkdf1`

```sysl
hkdf1(salt: []const u8, ikm: []const u8, info: []const u8, out: []u8) -> Result[unit, Fault]
```

HKDF (RFC 5869) in one call: a secret is condensed with the salt and then spread into `out`.

**The secret must already be unguessable** -- a Diffie-Hellman result, a key from a hardware
source -- because nothing here raises the cost of guessing it. A password goes to `pbkdf2_hmac256`
instead.

**`info` is what keeps two keys derived from one secret apart**, and it is a label rather than a
secret: a protocol writes its own name and the key's purpose there, and a different `info` yields
an unrelated key. The salt may be empty, which is the standard's own default -- HMAC pads a short
key with zeroes, so an empty salt *is* the zero key the standard names.

At most 255 digests' worth of key can be asked for, which the standard's one-byte counter fixes.

**Extract on its own is `hmac256(salt, ikm)`** and needs nothing from this module: HKDF-Extract is
HMAC with the salt as the key, which is why only expansion has a name of its own below.

### `hkdf1_expand`

```sysl
hkdf1_expand(prk: []const u8, info: []const u8, out: []u8) -> Result[unit, Fault]
```

HKDF's expansion alone (RFC 5869 §2.3), over a pseudorandom key the caller already has.

**This is the half TLS 1.3 leans on**: `HKDF-Expand-Label` is this function with the label written
into `info` in the shape §7.1 gives, and `Derive-Secret` is one of those per traffic and exporter
secret in the schedule. Signal's chains are the same shape.

The key should be a digest's worth of unguessable bytes -- what `hmac256(salt, ikm)`, which is
HKDF-Extract, answers with. At most 255 digests' worth of key can be asked for.

### `hkdf224`

```sysl
hkdf224(salt: []const u8, ikm: []const u8, info: []const u8, out: []u8) -> Result[unit, Fault]
```

### `hkdf224_expand`

```sysl
hkdf224_expand(prk: []const u8, info: []const u8, out: []u8) -> Result[unit, Fault]
```

### `hkdf256`

```sysl
hkdf256(salt: []const u8, ikm: []const u8, info: []const u8, out: []u8) -> Result[unit, Fault]
```

### `hkdf256_expand`

```sysl
hkdf256_expand(prk: []const u8, info: []const u8, out: []u8) -> Result[unit, Fault]
```

### `hkdf384`

```sysl
hkdf384(salt: []const u8, ikm: []const u8, info: []const u8, out: []u8) -> Result[unit, Fault]
```

### `hkdf384_expand`

```sysl
hkdf384_expand(prk: []const u8, info: []const u8, out: []u8) -> Result[unit, Fault]
```

### `hkdf512`

```sysl
hkdf512(salt: []const u8, ikm: []const u8, info: []const u8, out: []u8) -> Result[unit, Fault]
```

### `hkdf512_expand`

```sysl
hkdf512_expand(prk: []const u8, info: []const u8, out: []u8) -> Result[unit, Fault]
```

### `hmac1`

```sysl
hmac1(key: []const u8, msg: []const u8) -> [20]u8
```

HMAC-SHA-1, answering a twenty-byte tag. A key longer than a block is replaced by its own
digest; a shorter one is padded with zeroes.

**This is a weaker warning than `sha1` carries, and deliberately so.** HMAC's security does not
rest on the hash being collision resistant, so HMAC-SHA-1 is not broken the way SHA-1 is -- which
is why RFC 6238 still specifies it as TOTP's default and why a great deal of deployed request
signing still uses it. It is here to talk to those. A protocol being written now wants `hmac256`.

### `hmac224`

```sysl
hmac224(key: []const u8, msg: []const u8) -> [28]u8
```

### `hmac256`

```sysl
hmac256(key: []const u8, msg: []const u8) -> [32]u8
```

### `hmac384`

```sysl
hmac384(key: []const u8, msg: []const u8) -> [48]u8
```

### `hmac512`

```sysl
hmac512(key: []const u8, msg: []const u8) -> [64]u8
```

### `hmacmd5`

```sysl
hmacmd5(key: []const u8, msg: []const u8) -> [16]u8
```

HMAC-MD5, answering a sixteen-byte tag. A key longer than a block is replaced by its own digest;
a shorter one is padded with zeroes.

**The same weaker warning `hmac1` carries applies here, and for the same reason.** HMAC's security
does not rest on the hash being collision resistant, so HMAC-MD5 is not broken the way `md5` is --
RFC 6151 says as much, while advising against it for anything new. It is here for what already
asks: HTTP Digest with `qop`, CRAM-MD5, and RADIUS, none of which lets a client choose. A protocol
being written now wants `hmac256`.

### `md5`

```sysl
md5(data: []const u8) -> [16]u8
```

The digest of one slice of bytes, sixteen bytes long.

**MD5 is broken, and has been since 2004.** A collision is buildable, and a chosen-prefix
collision has been since 2007 -- a pair of colliding X.509 certificates was built to show it. So
this digest says nothing about a document nobody could have substituted: it must not carry a
signature, a certificate, a content address or a deduplication key. **`sha256` is what all of
those want.**

It is in this module for the protocols that name it and give a client no say. PostgreSQL's `md5`
authentication answers a salt with `md5(md5(password + user) + salt)`; HTTP Digest, a
`Content-MD5` header and an S3 `ETag` are the same shape. Where an attacker gains nothing from a
second document with the same digest, MD5 is adequate; everywhere else it is not, and there is no
third case.

### `new_md5`

```sysl
new_md5() -> Md5
```

A hasher ready for its first `update`.

**MD5 is broken.** Reach for `new_sha256` unless a wire protocol names this one; see `md5`.

### `new_sha1`

```sysl
new_sha1() -> Sha1
```

A hasher ready for its first `update`.

**SHA-1 is broken.** Reach for `new_sha256` unless a wire protocol names this one; see `sha1`.

### `new_sha224`

```sysl
new_sha224() -> Sha224
```

### `new_sha256`

```sysl
new_sha256() -> Sha256
```

### `new_sha384`

```sysl
new_sha384() -> Sha384
```

### `new_sha512`

```sysl
new_sha512() -> Sha512
```

### `pbkdf2_hmac1`

```sysl
pbkdf2_hmac1(password: []const u8, salt: []const u8, iterations: u32, out: []u8) -> Result[unit, Fault]
```

PBKDF2 over HMAC-SHA-1, writing as much key as `out` has room for.

**It is the one an existing protocol names**: WPA2 derives its pairwise master key this way, and
so does SCRAM-SHA-1 and a great deal of deployed password storage. A scheme being written now
wants `pbkdf2_hmac256`, and something built for the purpose -- Argon2, scrypt -- before either.

The iteration count is the caller's because the protocol says it: SCRAM carries the server's count
on the wire, and a client that used its own would derive a different key and be told its password
was wrong. It must be at least one.

### `pbkdf2_hmac224`

```sysl
pbkdf2_hmac224(password: []const u8, salt: []const u8, iterations: u32, out: []u8) -> Result[unit, Fault]
```

PBKDF2 over HMAC-SHA-2, writing as much key as `out` has room for.

**`pbkdf2_hmac256` is the one to reach for**, being what SCRAM-SHA-256 names -- so PostgreSQL,
MongoDB, Kafka and XMPP -- and what WebCrypto's `deriveBits` is usually asked for.

The iteration count is the caller's, is named by the protocol, and must be at least one. What it
buys is the cost of *guessing*, and nothing else: a password that could be guessed in a thousand
tries still can be, a hundred thousand times more slowly.

### `pbkdf2_hmac256`

```sysl
pbkdf2_hmac256(password: []const u8, salt: []const u8, iterations: u32, out: []u8) -> Result[unit, Fault]
```

### `pbkdf2_hmac384`

```sysl
pbkdf2_hmac384(password: []const u8, salt: []const u8, iterations: u32, out: []u8) -> Result[unit, Fault]
```

### `pbkdf2_hmac512`

```sysl
pbkdf2_hmac512(password: []const u8, salt: []const u8, iterations: u32, out: []u8) -> Result[unit, Fault]
```

### `sha1`

```sysl
sha1(data: []const u8) -> [20]u8
```

The digest of one slice of bytes, which is the whole of what most callers want.

**SHA-1 is broken, and has been publicly broken since 2017.** A collision is buildable, and since
2019 a chosen-prefix collision is, so this digest says nothing about a document nobody could have
substituted: it must not carry a signature, a certificate, a content address or a deduplication
key. **`sha256` is what all of those want.**

It is in this module for **RFC 6455's opening handshake**, which answers a client's key with
`base64(sha1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))` and names both the algorithm and the
constant in the standard, so there is no substitute. That use makes no security claim at all --
the handshake shows the far end read the request and authenticates nobody -- which is why an
algorithm this broken still serves it. Where a second preimage would buy an attacker nothing,
SHA-1 is adequate; everywhere else it is not, and there is no third case.

### `sha224`

```sysl
sha224(data: []const u8) -> [28]u8
```

### `sha256`

```sysl
sha256(data: []const u8) -> [32]u8
```

### `sha384`

```sysl
sha384(data: []const u8) -> [48]u8
```

### `sha512`

```sysl
sha512(data: []const u8) -> [64]u8
```

### `verify`

```sysl
verify(a: []const u8, b: []const u8) -> bool
```

## Types

### `Fault`

```sysl
enum Fault
    NotStarted
    DigestTooSmall
    NoIterations
    TooMuchOutput
```

| Member | Signature | Description |
|---|---|---|
| `describe` | `describe(self) -> string` |  |

### `Md5`

```sysl
struct Md5
    private[crypto] inner: Sha[Md5C]
```

An MD5 hash in progress. **MD5 is broken** -- see `md5` for what that does and does not rule out.

| Member | Signature | Description |
|---|---|---|
| `update` | `update(*self, data: []const u8)` |  |
| `finish` | `finish(*self, digest: []u8) -> Result[unit, Fault]` |  |

### `Sha1`

```sysl
struct Sha1
    private[crypto] inner: Sha[Sha1C]
```

A SHA-1 hash in progress. **SHA-1 is broken** -- see `sha1` for what that does and does not
rule out.

| Member | Signature | Description |
|---|---|---|
| `update` | `update(*self, data: []const u8)` |  |
| `finish` | `finish(*self, digest: []u8) -> Result[unit, Fault]` |  |

### `Sha224`

```sysl
struct Sha224
    private[crypto] inner: Sha[Sha2Narrow]
```

| Member | Signature | Description |
|---|---|---|
| `update` | `update(*self, data: []const u8)` |  |
| `finish` | `finish(*self, digest: []u8) -> Result[unit, Fault]` |  |

### `Sha256`

```sysl
struct Sha256
    private[crypto] inner: Sha[Sha2Narrow]
```

| Member | Signature | Description |
|---|---|---|
| `update` | `update(*self, data: []const u8)` |  |
| `finish` | `finish(*self, digest: []u8) -> Result[unit, Fault]` |  |

### `Sha384`

```sysl
struct Sha384
    private[crypto] inner: Sha[Sha2Wide]
```

| Member | Signature | Description |
|---|---|---|
| `update` | `update(*self, data: []const u8)` |  |
| `finish` | `finish(*self, digest: []u8) -> Result[unit, Fault]` |  |

### `Sha512`

```sysl
struct Sha512
    private[crypto] inner: Sha[Sha2Wide]
```

| Member | Signature | Description |
|---|---|---|
| `update` | `update(*self, data: []const u8)` |  |
| `finish` | `finish(*self, digest: []u8) -> Result[unit, Fault]` |  |

## Implementations

### Compression for Md5C

```sysl
impl Compression for Md5C
```

### Compression for Sha1C

```sysl
impl Compression for Sha1C
```

### Compression for Sha2Narrow

```sysl
impl Compression for Sha2Narrow
```

### Compression for Sha2Wide

```sysl
impl Compression for Sha2Wide
```

### Word for u32

```sysl
impl Word for u32
```

### Word for u64

```sysl
impl Word for u64
```
