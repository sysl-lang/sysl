package io.github.edadma.sysl

import scala.collection.mutable

/** How an **aggregate** is handed to and from a C function.
 *
 * A scalar crosses the boundary as itself: an `i32` is one register on every machine sysl lowers
 * for, and nothing has to be decided. An aggregate does not. Each ABI says which registers a struct
 * arrives in, and **LLVM applies no such rule of its own** — given a struct type in a signature it
 * assigns one register per element, which is not what any of the four conventions asks for. So the
 * declaration a call names has to be the *coerced* one the ABI specifies, and the value converted
 * into and out of that shape at the call.
 *
 * The one thing this is *not* needed for is a call from sysl to sysl. Both sides are this compiler,
 * so a struct handed over as a struct is a convention like any other and the two agree by
 * construction. It is only at the seam with a foreign function — where the other side was compiled
 * by somebody else against a published document — that the published answer is the only one that
 * works.
 *
 * Every rule below was read off `clang -S -emit-llvm` for the triple, which is the method
 * `targets.md` prescribes, and it is the only method that finds the parts no document states
 * plainly: that AAPCS64 hands a small struct **back** in an integer of its exact width while taking
 * the same struct widened to a whole register; that System V splits a sixteen-byte struct into two
 * *separate* parameters where AAPCS64 passes one array of two; that RISC-V flattens a struct of one
 * float and one integer into two registers but does **not** flatten one of a pointer and a float;
 * and that both of those name a sixteen-byte aggregate `i128` once it is aligned to sixteen.
 */
object CAbi {

  /** What a call names as its result type, and how the value comes back. */
  enum Result {

    /** Nothing to coerce — the sysl type is already what the ABI asks for. */
    case Plain

    /** An aggregate returned in registers, under a different LLVM type. */
    case Coerced(llvm: String)

    /** An aggregate too big for registers: the caller supplies the storage and the callee fills it,
     * so the declared result is `void` and an out-parameter goes in front of every argument.
     */
    case Sret(llvm: String, align: Int)
  }

  /** One register's worth of an argument: the type it is named by, and whatever parameter attribute
   * the convention puts on it. The two are kept apart because only the type describes storage — an
   * attribute travels with the parameter and cannot be allocated or loaded.
   */
  case class Arg(llvm: String, attr: String = "") {
    def declared: String = if attr.isEmpty then llvm else s"$llvm $attr"
  }

  /** What a call passes for one declared parameter. */
  enum Param {

    /** Nothing to coerce. */
    case Plain

    /** An aggregate spread across registers, each its own parameter. */
    case Coerced(pieces: List[Arg])

    /** An aggregate passed by address. `byval` is System V's: the bytes go on the stack and the
     * pointer is how LLVM is told to put them there. The other three conventions pass a real
     * pointer in a register, and marking one `byval` there would be a different call.
     */
    case Indirect(llvm: String, align: Int, byval: Boolean)
  }

  /** Whether a type is one the ABI has an answer about — that is, whether its LLVM form is
   * something other than a single register's worth of value.
   *
   * A trait object and a view are here because they *are* aggregates, whatever a C header would
   * have called them: a `string` is three words, and a C function declared to take a matching
   * three-word struct is the only C function it can correctly reach. Passing it as three registers
   * because LLVM happened to do that is not a convention anything on the other side implements.
   *
   * A **zero-sized** aggregate is left alone. C passes nothing for one and LLVM assigns an empty
   * aggregate no register either, so the two already agree and there is nothing to coerce.
   */
  def aggregate(t: Type): Boolean = Type.underlying(t) match {
    case _: Type.Struct => Layout.size(t) > 0
    case _: Type.Array  => Layout.size(t) > 0
    case _: Type.View   => true
    case Type.VaList    => true
    case e: Type.Enum   => !e.simple
    case p              => Type.erased(p) || erasedWeak(p)
  }

  def result(t: Type, target: Target): Result =
    if !aggregate(t) then Result.Plain
    else
      shape(t, target) match
        case Shape.Memory                 => Result.Sret(t.llvm, Layout.align(t))
        case Shape.Registers(returned, _) => Result.Coerced(returned)

  def param(t: Type, target: Target): Param =
    if !aggregate(t) then Param.Plain
    else
      shape(t, target) match
        case Shape.Memory =>
          // Only System V wants the copy made on the caller's stack; the rest take an address.
          Param.Indirect(t.llvm, Layout.align(t), byval = target.cpu == Cpu.X86_64 && target.os != Os.Windows)
        case Shape.Registers(_, passed) => Param.Coerced(passed)

  /** The registers an aggregate occupies, named twice — because a result and an argument may spell
   * the same registers differently, which is AAPCS64's case throughout and nobody else's.
   */
  private enum Shape {
    case Memory
    case Registers(returned: String, passed: List[Arg])
  }

  /** Where a result and an argument name the same registers the same way, which is every convention
   * but AAPCS64: a result in two registers is a literal struct of them, and an argument in two is
   * two parameters.
   */
  private def alike(pieces: List[String]): Shape =
    Shape.Registers(
      if pieces.length == 1 then pieces.head else s"{ ${pieces.mkString(", ")} }",
      pieces.map(Arg(_)),
    )

  private def shape(t: Type, target: Target): Shape = {
    val size = Layout.size(t)

    target.cpu match
      case Cpu.Aarch64                           => aapcs64(t, size, target.os == Os.MacOS)
      case Cpu.X86_64 if target.os == Os.Windows => windows(size)
      case Cpu.X86_64                            => sysv(t, size)
      case Cpu.Riscv64                           => riscv(t, size, target.hardFloat)
      // A 32-bit target is refused at the registry (`Target.supported`), so nothing reaches here.
      case Cpu.X86                               => Shape.Memory
  }

  // --- AAPCS64 ---------------------------------------------------------------------------

  /** AAPCS64, which asks first whether the aggregate is a **homogeneous floating aggregate** — up to
   * four members that are all the same floating type, however deeply nested — because those go in
   * the floating registers whatever their size, and only then falls back to size.
   *
   * This is the convention whose two directions differ everywhere. A result is named by the
   * aggregate's own width, down to the bit; an argument by the whole registers it travels in, whose
   * surplus bits the convention leaves unspecified. And an argument every one of whose members is an
   * address is named in addresses, so that what a pointer carries beyond its bits survives being
   * handed over.
   */
  private def aapcs64(t: Type, size: Int, macOS: Boolean): Shape = {
    val ls        = leaves(t).map(l => Type.underlying(l._2))
    val addresses = ls.nonEmpty && ls.forall(address)

    hfa(ls) match
      case Some((elem, n)) =>
        // Away from Darwin the convention says how far to align a floating aggregate that runs out
        // of registers and lands on the stack. Darwin's variant does not ask for it.
        Shape.Registers(t.llvm, List(Arg(s"[$n x $elem]", if macOS then "" else "alignstack(8)")))
      case None if size <= 8 =>
        Shape.Registers(s"i${size * 8}", List(Arg(if addresses then "ptr" else "i64")))
      // Two registers, and what names them is the *alignment*: sixteen bytes wanting sixteen-byte
      // alignment is one `i128`, which is a register pair, where eight-byte alignment is two of them.
      case None if size <= 16 && Layout.align(t) >= 16 => alike(List("i128"))
      case None if size <= 16 =>
        Shape.Registers("[2 x i64]", List(Arg(if addresses then "[2 x ptr]" else "[2 x i64]")))
      case None => Shape.Memory
  }

  /** The floating type and count of a homogeneous floating aggregate, or `None`. Four is the limit
   * because that is how many registers the convention sets aside for one; a fifth member makes the
   * whole thing an ordinary aggregate again, however small it is.
   */
  private def hfa(ls: List[Type]): Option[(String, Int)] =
    Option.when(ls.nonEmpty && ls.length <= 4 && ls.forall(floating) && ls.distinct.length == 1)(
      (ls.head.llvm, ls.length),
    )

  // --- System V x86-64 -------------------------------------------------------------------

  /** System V, which classifies the aggregate **one eightbyte at a time**: a chunk whose every byte
   * belongs to a floating member travels in a floating register, anything else in an integer one.
   * Past two eightbytes there are no registers left for it and the whole thing goes in memory.
   */
  private def sysv(t: Type, size: Int): Shape =
    if size > 16 then Shape.Memory
    else
      val ls = leaves(t)

      alike((0 until (size + 7) / 8).toList.map { i =>
        val lo   = i * 8
        val hi   = math.min(size, lo + 8)
        val here = ls.filter((off, m) => off < hi && off + Layout.size(m) > lo)

        if here.nonEmpty && here.forall(l => floating(l._2)) then floatingChunk(here)
        else integerChunk(ls, lo, hi, size)
      })

  /** Members that all fit in floating registers are named by what fills the chunk: several of one
   * width as a vector of that many, one on its own — a `double`, or a `float` in a tail chunk with
   * nothing beside it — as itself. A chunk two *different* floating widths share has no such name,
   * and clang falls back to a whole `double`.
   */
  private def floatingChunk(here: List[(Int, Type)]): String = {
    val kinds = here.map(l => Type.underlying(l._2).llvm).distinct

    if kinds.length > 1 then "double"
    else if here.length == 1 then kinds.head
    else s"<${here.length} x ${kinds.head}>"
  }

  /** An integer chunk is named by **the member that starts it**, when that member is all the chunk
   * carries: a `u8` in the second eightbyte of `{i64, u8}` is an `i8`, not the whole register it
   * will travel in. That distinction is not decoration — it is what clang writes, and writing
   * something else would leave a diff against clang unusable as the oracle it is. When the chunk
   * carries more than the member starting it, there is no one member to name it after and it is the
   * bytes that are left: eight of them, or fewer in the tail.
   */
  private def integerChunk(ls: List[(Int, Type)], lo: Int, hi: Int, size: Int): String = {
    val starts = ls.find(_._1 == lo).map(_._2)
    val width  = starts.map(Layout.size).getOrElse(0)
    val alone  = !ls.exists((off, _) => off >= lo + width && off < math.min(lo + 8, size))

    // An address fills its chunk and is named as one, which changes nothing about the call — an
    // address travels in the integer register a number would — but it keeps what a pointer carries
    // beyond its bits, and it is what clang writes.
    if width == 8 && starts.exists(address) then "ptr"
    else if alone && (width == 1 || width == 2 || width == 4) then s"i${width * 8}"
    else s"i${(hi - lo) * 8}"
  }

  // --- RISC-V LP64D ----------------------------------------------------------------------

  /** RISC-V's hardware-float convention, which **flattens** the narrow cases: an aggregate of one or
   * two floating members travels in floating registers, and one floating member beside one integer
   * member travels in one of each. Anything else falls back to size — including a pointer beside a
   * float, which the convention does not count as the integer case.
   *
   * A bare-metal RISC-V target has no floating registers to flatten into (`Target.hardFloat`), so
   * there the size rule is the whole of it.
   */
  private def riscv(t: Type, size: Int, hardFloat: Boolean): Shape = {
    val ls   = leaves(t).map(l => Type.underlying(l._2))
    val fps  = ls.count(floating)
    val ints = ls.count(m => integral(m) && Layout.size(m) <= 8)

    if hardFloat && ls.length <= 2 && fps >= 1 && fps + ints == ls.length then
      alike(ls.map(m => if floating(m) then m.llvm else s"i${Layout.size(m) * 8}"))
    else if size <= 8 then alike(List("i64"))
    else if size <= 16 then alike(List(if Layout.align(t) >= 16 then "i128" else "[2 x i64]"))
    else Shape.Memory
  }

  // --- Windows x64 -----------------------------------------------------------------------

  /** The Microsoft convention, the simplest of the four: an aggregate whose size is exactly one
   * register's worth travels in one register as an integer of that width, and everything else — a
   * three-byte struct as much as a thirty-byte one — goes by address. There is no floating case and
   * no pointer case; a pair of floats is eight bytes and travels as an `i64`.
   */
  private def windows(size: Int): Shape =
    if size == 1 || size == 2 || size == 4 || size == 8 then alike(List(s"i${size * 8}"))
    else Shape.Memory

  // --- the members an aggregate is made of -----------------------------------------------

  /** The scalars an aggregate is made of, flattened through every level of nesting, each paired
   * with the byte it starts at. This is what the homogeneity question, the flattening question and
   * the eightbyte question are all asked of, and it walks the layout the emitted module actually has
   * rather than the one a program wrote — a zero-sized field occupies nothing and is not a member
   * here.
   */
  def leaves(t: Type): List[(Int, Type)] = {
    val acc = mutable.ListBuffer.empty[(Int, Type)]

    walk(t, 0, acc)
    acc.toList
  }

  private def walk(t: Type, at: Int, acc: mutable.ListBuffer[(Int, Type)]): Unit =
    Type.underlying(t) match {
      case s: Type.Struct =>
        var off = at

        for (_, f) <- s.stored do
          off = roundUp(off, Layout.align(f))
          walk(f, off, acc)
          off += Layout.size(f)

      case Type.Array(n, elem) =>
        val stride = Layout.size(elem)

        for i <- 0 until n do walk(elem, at + i * stride, acc)

      // A data enum is the tag and the payload region, and the region is a union — there is no
      // member to name inside it, so it counts as the integers it is emitted as. That makes an enum
      // never homogeneous and never all-floating, which is right: a union of a float and an integer
      // has to travel somewhere both of them fit.
      case e: Type.Enum if !e.simple =>
        val unit  = math.max(1, Layout.payloadAlign(e))
        val start = at + roundUp(4, unit)

        acc += ((at, Type.Integer(32, signed = true)))
        for i <- 0 until (Layout.payloadSize(e) + unit - 1) / unit do
          acc += ((start + i * unit, Type.Integer(unit * 8, signed = false)))

      // A view is the owner, the first element, and the count; a trait object is the table and the
      // value. Both are addresses and, for the view, a number — which is all the ABI needs of them.
      case _: Type.View =>
        acc += ((at, Type.Ptr(Type.Byte)))
        acc += ((at + 8, Type.Ptr(Type.Byte)))
        acc += ((at + 16, Type.Usize))

      case Type.VaList =>
        for i <- 0 until 4 do acc += ((at + i * 8, Type.Ptr(Type.Byte)))

      case p if Type.erased(p) || erasedWeak(p) =>
        acc += ((at, Type.Ptr(Type.Byte)))
        acc += ((at + 8, Type.Ptr(Type.Byte)))

      case scalar => acc += ((at, scalar))
    }

  private def erasedWeak(t: Type): Boolean = t match {
    case Type.Weak(inner) => inner.isInstanceOf[Type.Trait]
    case _                => false
  }

  private def floating(t: Type): Boolean = Type.underlying(t).isInstanceOf[Type.Floating]

  private def address(t: Type): Boolean = Type.underlying(t) match {
    case _: Type.Ptr | _: Type.Ref | _: Type.Weak => true
    case _                                        => false
  }

  private def integral(t: Type): Boolean = Type.underlying(t) match {
    case _: Type.Integer | Type.Bool | Type.Char => true
    case _                                       => false
  }

  private def roundUp(n: Int, to: Int): Int = (n + to - 1) / to * to
}
