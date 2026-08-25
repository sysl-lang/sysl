package sh.sysl

/** How much storage a type occupies, and what it must be aligned to.
 *
 * Codegen normally never has to know: it writes types by name and lets LLVM work out the offsets
 * from the target's data layout. One thing cannot be written that way — a **union**. A data enum's
 * payload is one region shared by every variant, and the width of that region has to appear in the
 * emitted text as a literal, so the compiler has to be able to say how wide the widest variant is
 * before LLVM sees the module.
 *
 * Every rule here is one **every target agrees on**: scalars are their own width and aligned to it,
 * an aggregate is laid out in declaration order with each member on its own alignment and the whole
 * rounded up to the widest member's. That is C's rule and LLVM's. Exactly one input varies, and it
 * is the one a 32-bit target changes — **how wide an address is** — so this is a value built from a
 * target rather than an object with eight bytes written into it (`targets.md`).
 *
 * That distinction is worth stating precisely, because it is easy to read as more than it is: the
 * *rules* are target-independent and the *width they are applied to* is not. Nothing here asks the
 * target anything else, and a second question would be a sign something is being decided in the
 * wrong place — the emitted module states its triple and LLVM derives the data layout from it, so
 * everything but this one number is written down once, by LLVM.
 *
 * The language's `sizeof` and `alignof` (`reference/memory.md § Reinterpreting storage`) are these
 * two functions asked from outside, so the answer a program gets and the width the compiler writes
 * into a union agree by construction rather than by being kept in step. Everything reaches them
 * through `ConstFolding.layoutBytes`, which is where the two operands with no answer — a type
 * parameter standing in for itself, and a type already complained about — are turned away before
 * they arrive.
 *
 * @param word how wide an address is, and the whole of what a target contributes here.
 */
case class Layout(word: Word) {

  given Word = word

  /** How wide an address is. */
  def pointerBytes: Int = word.bytes

  /** The two words a fat pointer costs — an erased reference, and a trait object's data and table. */
  private def fat: Int = pointerBytes * 2

  /** The bytes a value of `t` occupies, including the padding an aggregate carries at its end so
   * that an array of them keeps every element aligned. So `size` is also the stride.
   */
  def size(t: Type): Int = Type.underlying(t) match
    case Type.Bool                       => 1
    case Type.Char                       => 4
    case i: Type.Integer                 => intAlloc(i)
    case f: Type.Floating                => f.bits / 8
    case Type.Unit | Type.Never          => 0
    // A part the compiler could not work out contributes nothing rather than stopping the walk. The
    // program that produced one has an error and will not be lowered, so the number this yields is
    // never used — but a struct with one bad field is still asked its width, by a `sizeof` and by the
    // union a data enum lays out, and answering is what lets the *real* diagnostic be the one the
    // reader sees instead of a stack trace about a type they were already told about.
    case Type.Unknown                    => 0
    // Sysl's own storage for a walk, not any target's `va_list`: the widest a target needs, given
    // to all of them (`targets.md`). What is per-target is how many bytes are *copied* out of it.
    // Four words, which is what `Type.VaList.llvm` writes, so the two cannot drift.
    case Type.VaList                     => pointerBytes * 4
    case t2 @ (_: Type.Ptr | _: Type.Ref) => if Type.erased(t2) then fat else pointerBytes
    // A weak reference is an address like the other two, and a weak trait object is the same pair
    // of words an erased `&T` is — the count it takes is the box's business, not the value's.
    case Type.Weak(inner)                => if inner.isInstanceOf[Type.Trait] then fat else pointerBytes
    // One word, and the reason it is one rather than the two a `*Fn` costs: there is no table beside
    // it, because the signature is in the type rather than in the value.
    case _: Type.CFn                     => pointerBytes
    // Three: an address, a length and a capacity.
    case _: Type.View                    => pointerBytes * 3
    case Type.Array(n, elem)             => n * size(elem)
    // A vector's lanes are packed with no padding between them, exactly as an array's elements are,
    // so the two agree on size and differ only on alignment. A `<N>bool` is the one that does not
    // follow from that: LLVM packs `<N x i1>` to N *bits*, so its size is rounded up to the byte
    // rather than being N — the shape a mask has in memory is not the shape it has in a register,
    // and this is the number that matters when one is stored.
    case Type.Vector(n, elem) =>
      if Type.underlying(elem) == Type.Bool then (n + 7) / 8 else n * size(elem)
    case s: Type.Struct                  => structLayout(s)._1
    case e: Type.Enum                    => if e.simple then size(e.underlying) else enumSize(e)
    case other                           => sys.error(s"unreachable size of ${other.llvm}")

  /** Whether a value of `t` is handed about through memory rather than as a first-class LLVM value:
   * built where it is going to live, copied with `llvm.memcpy`, read a field at a time through its
   * address, and returned through an out-pointer the caller supplies.
   *
   * Only an aggregate can be one. A scalar is a register whatever its width — an `i8388607` is a
   * back-end problem of a different kind, and pretending it were a struct would not help.
   */
  def indirect(t: Type): Boolean = Type.underlying(t) match
    case _: Type.Struct | _: Type.Array => size(t) > Layout.DirectBytes
    case e: Type.Enum                   => !e.simple && size(t) > Layout.DirectBytes
    case _                              => false

  /** The address a value of `t` must start at. */
  def align(t: Type): Int = Type.underlying(t) match
    case Type.Bool                       => 1
    case Type.Char                       => 4
    case i: Type.Integer                 => intAlign(i)
    case f: Type.Floating                => f.bits / 8
    case Type.Unit | Type.Never          => 1
    case Type.Unknown                    => 1
    case Type.VaList                     => pointerBytes
    case _: Type.Ptr | _: Type.Ref       => pointerBytes
    case _: Type.Weak                    => pointerBytes
    case _: Type.CFn                     => pointerBytes
    case _: Type.View                    => pointerBytes
    case Type.Array(_, elem)             => align(elem)
    // **A vector aligns to its whole size, not to its lane**, which is the one place it parts
    // company with the array it otherwise matches: `<4>f32` wants 16 bytes where `[4]f32` wants 4.
    // That is LLVM's rule and it is the machine's — an aligned vector load is one instruction and
    // an unaligned one is a slower path or a fault, depending on the target. Rounding up to a power
    // of two is LLVM's too, so a `<3>f32` aligns to 16 rather than to 12.
    case v: Type.Vector                  => Integer.highestOneBit(math.max(size(v) * 2 - 1, 1))
    case s: Type.Struct                  => structLayout(s)._2
    case e: Type.Enum                    => if e.simple then align(e.underlying) else enumAlign(e)
    case other                           => sys.error(s"unreachable alignment of ${other.llvm}")

  /** What an integer of any width costs, which for a width that is not a whole number of bytes is
   * not `bits / 8`.
   *
   * LLVM rounds an integer's **alignment** up to that of the smallest one its data layout names — so
   * a `u12` is aligned like a `u16` and a `u96` like a `u128` — and then rounds the stride up to that
   * alignment. Both have to be computed the way LLVM computes them, because the only reason this
   * object exists is to state a union's width in the emitted text: an under-estimate here is a
   * payload region narrower than the value a variant stores into it, which no later pass can catch.
   */
  private def intAlign(i: Type.Integer): Int = {
    var a = 1
    while a < 16 && a * 8 < i.bits do a *= 2
    a
  }

  private def intAlloc(i: Type.Integer): Int = roundUp((i.bits + 7) / 8, intAlign(i))

  /** The size and alignment of members laid end to end, each on its own alignment and the whole
   * rounded up to the widest one — the layout LLVM gives the aggregate these members are emitted as.
   * A list with nothing in it is one byte wide so that an array of them still has distinct elements.
   */
  private def aggregate(members: List[Type]): (Int, Int) = {
    var offset = 0
    var widest = 1

    for m <- members do
      widest = math.max(widest, align(m))
      offset = roundUp(offset, align(m)) + size(m)

    (roundUp(offset, widest), widest)
  }

  /** A struct's size and alignment, with its layout attributes applied (`reference/types.md §
   * Structs`).
   *
   * `@packed` lays the fields end to end with no interior padding and drops the aggregate's own
   * alignment to one — what a register block, and a C struct that has to match one, need.
   * `@align(n)` then raises where the whole may *start*, and the size is rounded to a multiple of it
   * so that an array of them keeps every element on the boundary the declaration asked for.
   *
   * They are applied in that order because they are separate axes, and the other order would let the
   * packing undo an alignment written beside it. A struct may carry both: a wire header that has to
   * live in a DMA-capable buffer is exactly that shape.
   *
   * A **bitfield struct** (`Bitfields`) is one integer rather than a sequence of fields, so its size
   * is that integer's. It is the same attribute reaching a different answer: `{a: u12, b: u12}` is
   * twenty-four bits here and two allocations of a `u12` — four bytes — under the rule below.
   */
  private def structLayout(s: Type.Struct): (Int, Int) = {
    val members = s.stored.map(_._2)

    val (bytes, alignment) =
      Bitfields.of(s) match
        case Some(ranges)     => (Bitfields.bits(ranges) / 8, 1)
        case None if s.packed => (math.max(members.map(size).sum, 1), 1)
        case None             => aggregate(members)

    s.minAlign match
      case Some(n) if n > alignment => (math.max(roundUp(bytes, n), n), n)
      case _                        => (bytes, alignment)
  }

  /** Where a field starts, in bytes from the front of the struct it is written in
   * (`reference/memory.md § Reinterpreting storage`), or `None` where the struct stores no field of
   * that name.
   *
   * It walks the same members `structLayout` walks, in the same order, under the same `@packed`
   * rule — deliberately, and for the reason the class doc gives about `sizeof` and the union width:
   * the whole worth of an `offsetof` is that it is measured by the thing that did the laying out,
   * so a second walk that agreed by inspection rather than by construction would be the tautology
   * `reference/ffi.md § A library may carry C` refuses.
   *
   * `@align(n)` is not consulted, because it moves where the *struct* may start and never shifts a
   * field inside it.
   *
   * A **zero-sized** field is not stored and so has no offset. It is dropped by `stored` rather than
   * answered as the position it would have had: it occupies nothing, nothing can be read there, and
   * a number would invite a comparison against a C struct that has no such member at all.
   *
   * A **bitfield** has no byte offset either, and its caller says so in those words rather than
   * reaching here — a field starting at bit 12 is not at byte 1, and rounding it down to the byte it
   * begins in would answer a question nobody asked.
   */
  def fieldOffset(s: Type.Struct, field: String): Option[Int] = {
    var offset = 0
    var found  = Option.empty[Int]

    for (name, ty) <- s.stored if found.isEmpty do
      if !s.packed then offset = roundUp(offset, align(ty))

      if name == field then found = Some(offset) else offset += size(ty)

    found
  }

  private def roundUp(n: Int, to: Int): Int = (n + to - 1) / to * to

  /** The payload region of a data enum: the element type its slots are counted in, and how many.
   *
   * A union has to be written as an array of *something*, and what that something is decides the
   * alignment of the region — which is why it is the widest alignment any variant needs rather than
   * bytes. Counting in those units then makes the region exactly as wide as the widest variant,
   * rounded up to a whole number of them, which is where it would have had to end anyway.
   */
  def payloadArea(e: Type.Enum): (ir.LType, Int) = {
    val unit = math.max(1, payloadAlign(e))

    (ir.LType.I(unit * 8), roundUp(payloadSize(e), unit) / unit)
  }

  /** The widest a variant's payload gets. */
  def payloadSize(e: Type.Enum): Int = variants(e).map(_._1).maxOption.getOrElse(0)

  /** The strictest alignment a variant's payload needs. */
  def payloadAlign(e: Type.Enum): Int = variants(e).map(_._2).maxOption.getOrElse(1)

  private def variants(e: Type.Enum): List[(Int, Int)] =
    e.variants.filter(_.carries).map(v => aggregate(v.stored.map(_._2)))

  /** The tag is an `i32` and the payload follows it on the payload's own alignment. */
  private def enumSize(e: Type.Enum): Int = {
    val a = enumAlign(e)

    roundUp(roundUp(4, payloadAlign(e)) + payloadSize(e), a)
  }

  private def enumAlign(e: Type.Enum): Int = math.max(4, payloadAlign(e))
}

object Layout {

  /** The widest an aggregate may be and still be handed about as a first-class LLVM value.
   *
   * Below it a value is a value: LLVM carries it in registers, and the handful of `insertvalue` and
   * `extractvalue` instructions that build and read one cost the optimizer nothing. Above it the
   * back end is copying memory whatever the IR says, and a first-class value only obliges every
   * pass to reason about each byte of it — which does not stay linear. `guide/kernel` builds a
   * 20 KB struct and hands it about as a value at thirty-five call sites; that one module took
   * **408 seconds** to compile at `-O1` and **0.88 seconds** once the same program was lowered
   * through memory, with the whole of the difference inside InstCombine's walk over the allocas.
   *
   * The number is a judgement rather than a rule someone else wrote down. It is well above every
   * aggregate the language itself uses — a slice and a string are three words and a trait object
   * two — so nothing in ordinary code changes shape, and far below the sizes at which the optimizer
   * starts to struggle. It is bytes rather than words on purpose: what it is protecting against is
   * the optimizer's walk over the *storage*, which a narrower machine does not make cheaper.
   */
  val DirectBytes = 128

  /** The layout a given machine gives its types. */
  def apply(target: Target): Layout = Layout(target.word)
}
