package sh.sysl

/** Where a field sits inside a bitfield struct: the bit it starts at, and how many bits it is.
 *
 * `offset` is counted from the **least significant** bit of the struct's container, which is what
 * makes the two numbers a description of an integer rather than of a byte sequence — see
 * `Bitfields` for why that distinction is the whole design.
 */
case class BitRange(name: String, ty: Type, offset: Int, width: Int)

/** A `@packed` struct in which an `iN` field occupies exactly N bits (`15 §1`).
 *
 * **Such a struct *is* one unsigned integer**, `bits` wide, and its fields are bit ranges of that
 * integer filled from the least significant bit upward in declaration order, straddling byte
 * boundaries freely. C leaves both of those implementation-defined, which is precisely why portable
 * embedded C avoids bitfields and why pinning them is the point of the feature rather than a detail
 * of it.
 *
 * **The rule is over the integer's value and never over memory bytes.** Phrased the other way —
 * "the low bits of byte 0" — it would be endianness-observable, which `00 §49` forbids. Phrased this
 * way it costs nothing to honour: the container is emitted as an `iM`, so how it reaches memory is
 * the target's ordinary byte order for an integer of that width, and nothing here asks the target
 * anything at all. A wire format's byte order is a property of the protocol rather than of the CPU,
 * so it stays with `sysl.encoding.binary`'s `get_u16_le` and the rest, where it is an operation
 * instead of a layout.
 *
 * **Only an all-integer struct is one of these**, and that is a deliberate floor rather than a
 * simplification to be lifted later. Keeping the container a single integer is what makes the
 * sentence above statable, and it keeps a pointer field out of the `inttoptr` round trip that
 * packing one into an integer would need — which would lose the provenance the back end reasons
 * about. **The composition path is nesting**: a bitfield struct is a leaf, and an outer `@packed`
 * struct holding one lays it out as an ordinary field of its size.
 *
 * A struct is only one of these when some field's width is **not** a multiple of eight. A packed
 * struct of `u8`, `u16` and `u32` already occupies exactly those bits under the byte layout, so
 * leaving it alone is not an exemption — it is the same answer reached without a container.
 */
object Bitfields {

  /** How many bits a field of this type occupies, or `None` where it does not lower to an integer
   * and so cannot be a bitfield.
   *
   * A **simple enum** counts, and costs nothing to admit: its `llvm` already *is* its underlying
   * integer, so `enum Mode: u3` reads and writes through the same three instructions an `u3` does
   * and is the spelling a mode field actually wants.
   *
   * **`bool` deliberately does not.** Its LLVM form is `i1` and its storage is a byte, so admitting
   * it would make one type mean one width inside a container and another outside it. A flag in a
   * bitfield struct is a `u1`, which is the job the open integer family exists for.
   */
  def width(t: Type): Option[Int] = Type.underlying(t) match
    case i: Type.Integer         => Some(i.bits)
    case e: Type.Enum if e.simple => Some(e.underlying.bits)
    case _                        => None

  /** The bit ranges of `s`, or `None` where `s` is not a bitfield struct — because it is not
   * `@packed`, because a field of it does not lower to an integer, or because every field is
   * already a whole number of bytes and so needs no container.
   */
  def of(s: Type.Struct): Option[List[BitRange]] =
    if !s.packed then None
    else
      val widths = s.stored.map((n, t) => (n, t, width(t)))

      if !widths.exists(_._3.exists(_ % 8 != 0)) || widths.exists(_._3.isEmpty) then None
      else
        var offset = 0

        Some(widths.map { (n, t, w) =>
          val r = BitRange(n, t, offset, w.get)
          offset += w.get
          r
        })

  /** How wide the container is, rounded up to a whole number of bytes so that the struct has a size
   * in the unit `sizeof` answers in. The padding this adds is at the **top** of the integer, which
   * is what makes it padding rather than a shift applied to every field above it.
   */
  def bits(ranges: List[BitRange]): Int = {
    val used = ranges.map(_.width).sum

    math.max(8, (used + 7) / 8 * 8)
  }

  /** The container's mask for one range, and its complement — what a write ors in and what it first
   * ands out. Written as `BigInt` because a container may be wider than a `Long` and an LLVM
   * integer constant is not bounded by one.
   */
  def mask(r: BitRange, containerBits: Int): (BigInt, BigInt) = {
    val m = ((BigInt(1) << r.width) - 1) << r.offset

    (m, ((BigInt(1) << containerBits) - 1) & ~m)
  }
}
