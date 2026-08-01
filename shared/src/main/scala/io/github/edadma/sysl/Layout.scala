package io.github.edadma.sysl

/** How much storage a type occupies, and what it must be aligned to.
 *
 * Codegen normally never has to know: it writes types by name and lets LLVM work out the offsets
 * from the target's data layout. One thing cannot be written that way — a **union**. A data enum's
 * payload is one region shared by every variant, and the width of that region has to appear in the
 * emitted text as a literal, so the compiler has to be able to say how wide the widest variant is
 * before LLVM sees the module.
 *
 * The numbers here are the ones **every 64-bit target the compiler supports agrees on**: scalars
 * are their own width and aligned to it, an address is eight bytes, an aggregate is laid out in
 * declaration order with each member on its own alignment and the whole rounded up to the widest
 * member's. That is C's rule and LLVM's, which is why this object takes no `Target` (`targets.md`)
 * — every target in the registry answers these questions the same way, and the registry refuses a
 * 32-bit one precisely because this is where it would stop being true. The emitted module states
 * its triple and LLVM derives the data layout from that, so nothing here has to be written down
 * twice.
 *
 * The language's `sizeof` and `alignof` (`03 § Reinterpreting storage`) are these two functions asked
 * from outside, so the answer a program gets and the width the compiler writes into a union agree by
 * construction rather than by being kept in step. Everything reaches them through
 * `ConstFolding.layoutBytes`, which is where the two operands with no answer — a type parameter
 * standing in for itself, and a type already complained about — are turned away before they arrive.
 */
object Layout {

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
    case Type.VaList                     => 32
    case t2 @ (_: Type.Ptr | _: Type.Ref) => if Type.erased(t2) then 16 else 8
    // A weak reference is an address like the other two, and a weak trait object is the same pair
    // of words an erased `&T` is — the count it takes is the box's business, not the value's.
    case Type.Weak(inner)                => if inner.isInstanceOf[Type.Trait] then 16 else 8
    // One word, and the reason it is one rather than the two a `*Fn` costs: there is no table beside
    // it, because the signature is in the type rather than in the value.
    case _: Type.CFn                     => 8
    case _: Type.View                    => 24
    case Type.Array(n, elem)             => n * size(elem)
    case s: Type.Struct                  => aggregate(s.stored.map(_._2))._1
    case e: Type.Enum                    => if e.simple then size(e.underlying) else enumSize(e)
    case other                           => sys.error(s"unreachable size of ${other.llvm}")

  /** The address a value of `t` must start at. */
  def align(t: Type): Int = Type.underlying(t) match
    case Type.Bool                       => 1
    case Type.Char                       => 4
    case i: Type.Integer                 => intAlign(i)
    case f: Type.Floating                => f.bits / 8
    case Type.Unit | Type.Never          => 1
    case Type.Unknown                    => 1
    case Type.VaList                     => 8
    case _: Type.Ptr | _: Type.Ref       => 8
    case _: Type.Weak                    => 8
    case _: Type.CFn                     => 8
    case _: Type.View                    => 8
    case Type.Array(_, elem)             => align(elem)
    case s: Type.Struct                  => aggregate(s.stored.map(_._2))._2
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

  private def roundUp(n: Int, to: Int): Int = (n + to - 1) / to * to

  /** The payload region of a data enum: the element type its slots are counted in, and how many.
   *
   * A union has to be written as an array of *something*, and what that something is decides the
   * alignment of the region — which is why it is the widest alignment any variant needs rather than
   * bytes. Counting in those units then makes the region exactly as wide as the widest variant,
   * rounded up to a whole number of them, which is where it would have had to end anyway.
   */
  def payloadArea(e: Type.Enum): (String, Int) = {
    val unit = math.max(1, payloadAlign(e))

    (s"i${unit * 8}", roundUp(payloadSize(e), unit) / unit)
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
