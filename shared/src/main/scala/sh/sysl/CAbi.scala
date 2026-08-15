package sh.sysl

import ir.LType

import scala.collection.mutable

/** How an **aggregate** — and a **narrow scalar** — is handed to and from a C function.
 *
 * A register-width scalar crosses the boundary as itself: an `i32` is one register on every machine
 * sysl lowers for, and nothing has to be decided. An aggregate does not. Each ABI says which
 * registers a struct arrives in, and **LLVM applies no such rule of its own** — given a struct type
 * in a signature it assigns one register per element, which is not what a single one of the
 * conventions below asks for. So the declaration a call names has to be the *coerced* one the ABI
 * specifies, and the value converted into and out of that shape at the call.
 *
 * A scalar **narrower** than a register is the third case, and it was missing here until a `u8`
 * arrived at a C function as a different number (`extension` below says how). It needs no coercion —
 * an `i8` is an `i8` on both sides — but most conventions require whoever hands it over to widen it
 * to a whole register first, and LLVM does that only when the signature says to.
 *
 * The **coercion** is not needed for a call from sysl to sysl. Both sides are this compiler, so a
 * struct handed over as a struct is a convention like any other and the two agree by construction.
 * It is only at the seam with a foreign function — where the other side was compiled by somebody
 * else against a published document — that the published answer is the only one that works.
 *
 * The **extension** does not divide that way, and it is the one answer here that a sysl-to-sysl
 * signature carries too: a definition cannot know that C is not on the other end of it, and widening
 * a result is what the *callee* owes. `extension` says which side owes what.
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

  /** A layout already knows how wide an address is, so anything here with one in scope can write an
   * LLVM type without being handed the width a second time — and cannot be handed a *different* one.
   */
  private given (using l: Layout): Word = l.word

  /** What a call names as its result type, and how the value comes back. */
  enum Result {

    /** Nothing to coerce — the sysl type is already what the ABI asks for. */
    case Plain

    /** An aggregate returned in registers, under a different LLVM type. */
    case Coerced(ty: LType)

    /** An aggregate too big for registers: the caller supplies the storage and the callee fills it,
     * so the declared result is `void` and an out-parameter goes in front of every argument.
     */
    case Sret(ty: LType, align: Int)
  }

  /** One register's worth of an argument: the type it is named by, and whatever attribute the
   * convention puts on it — which is exactly a declared parameter, so it **is** `ir.Param` and had
   * been a second copy of it for as long as an attribute was a string.
   *
   * It keeps the name `Arg` here because that is what the conventions below call one, and it is an
   * alias rather than an import because `Param` already means something else in this object: what a
   * *call* passes for one declared parameter, which may be no registers or several.
   */
  type Arg = ir.Param
  val Arg: ir.Param.type = ir.Param

  /** What a call passes for one declared parameter. */
  enum Param {

    /** Nothing to coerce. */
    case Plain

    /** An aggregate spread across registers, each its own parameter. */
    case Coerced(pieces: List[Arg])

    /** An aggregate passed by address. `byval` says the bytes go on the caller's stack and the
     * pointer is how LLVM is told to put them there — System V's answer, and WebAssembly's. The rest
     * pass a real pointer in a register, and marking one `byval` there would be a different call.
     * `stackCopy` is where the two that ask for it are written down, alignment and all.
     */
    case Indirect(ty: LType, align: Int, byval: Boolean)
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
  def aggregate(t: Type)(using l: Layout): Boolean = Type.underlying(t) match {
    case _: Type.Struct => l.size(t) > 0
    case _: Type.Array  => l.size(t) > 0
    case _: Type.View   => true
    case Type.VaList    => true
    case e: Type.Enum   => !e.simple
    case p              => Type.erased(p) || erasedWeak(p)
  }

  def result(t: Type, target: Target)(using l: Layout): Result =
    if !aggregate(t) then Result.Plain
    else
      shape(t, target) match
        case Shape.Memory | Shape.Split(_)  => Result.Sret(t.lty, l.align(t))
        case Shape.Registers(returned, _)   => Result.Coerced(returned)

  def param(t: Type, target: Target)(using l: Layout): Param =
    if !aggregate(t) then Param.Plain
    else
      shape(t, target) match
        case Shape.Memory =>
          stackCopy(t, target) match
            case Some(align) => Param.Indirect(t.lty, align, byval = true)
            case None        => Param.Indirect(t.lty, l.align(t), byval = false)
        case Shape.Registers(_, passed) => Param.Coerced(passed)
        case Shape.Split(passed)        => Param.Coerced(passed)

  /** Whether an aggregate that goes in memory is a copy the **caller** makes — LLVM's `byval` — and
   * at what alignment the convention states the slot, or `None` where the callee is handed an address
   * it may not write.
   *
   * **Two conventions ask for the copy and they state its alignment by different rules**, which is
   * why this is a function rather than a flag:
   *
   *   - **System V** aligns an argument passed in memory to eight whatever the aggregate is made of,
   *     so clang says `align 8` for a `char[64]` and says more only where the type itself demands it
   *     (`align 16` for a pair of `__int128`s).
   *   - **WebAssembly** states the type's own alignment and nothing more: `align 1` for that same
   *     `char[64]`, `align 4` for a pair of `i32`, `align 8` for an `i64` beside an `i32`.
   *
   * Both measured. A `byval` alignment is a claim about the ABI rather than a hint — saying the wrong
   * one generated identical code here, because the back end applies its own minimum, and it was still
   * worth fixing: a claim that disagrees with clang's is wrong whether or not this year's back end
   * acts on it, and `AbiAgainstClangTests` asks clang every run rather than trusting that somebody
   * read it right once.
   */
  private def stackCopy(t: Type, target: Target)(using l: Layout): Option[Int] = target.cpu match
    case Cpu.X86_64 if target.os != Os.Windows => Some(math.max(l.align(t), 8))
    case Cpu.Wasm32                            => Some(l.align(t))
    // CRAFT states the type's own alignment for wasm's reason and not System V's: there is no
    // convention here declaring a floor, so the only honest number is what the type itself needs.
    case Cpu.Craft                             => Some(l.align(t))
    case _                                     => None

  /** How a scalar **narrower than a register** is widened to fill one — `signext`, `zeroext`, or
   * nothing at all.
   *
   * This is the half of the boundary that has no coercion in it. An `i8` is an `i8` to both
   * compilers and travels in the register a whole word would; what the conventions disagree about is
   * **the state of the bits above it**, and most of them settle it by making the sender widen the
   * value first. LLVM emits that widening only where the signature asks for it, so a declaration
   * without the attribute hands over a register whose top bits are whatever was in it — and a callee
   * compiled by clang, which was promised they were extended, reads a number nobody wrote.
   *
   * **Two obligations, and they fall on opposite sides.** A parameter's extension is the *caller's*,
   * so it is written at a foreign call and on the declaration it calls (`ForeignEmitter`). A result's
   * is the *callee's*, so it is written on every definition sysl emits (`Emitter.syslResult`) — a
   * definition cannot know that C is not on the other end, and a sysl caller that does not ask for
   * the extension is unharmed by getting it. That is why nothing here is written on a sysl
   * *parameter*: neither side of a sysl-to-sysl call claims it, so both are free to ignore the top
   * bits, and they agree by construction the way they do about everything else.
   *
   * Read off `clang -S -emit-llvm` for every triple in the registry — a count rather than a phrase
   * here would go stale the next time one is added, and the sweep in `AbiAgainstClangTests` is what
   * keeps the answers honest either way. The three departures from the ordinary rule are why this is
   * a measurement and not a line of reasoning:
   *
   *   - **AArch64 away from Darwin extends nothing**, not even `_Bool` — AAPCS64 leaves the top bits
   *     unspecified and makes the callee narrow what it reads. Apple's variant of the same
   *     convention is the opposite and does extend, so the two aarch64 targets in this registry
   *     disagree and both are right.
   *   - **Windows x64 extends `_Bool` and nothing else**, so `char` and `short` cross bare there.
   *   - **RISC-V 64 extends a 32-bit value too**, and `signext` whether or not it is signed: an
   *     `unsigned int` is sign-extended into a 64-bit register, which is the one place a convention
   *     asks for an extension that contradicts the type's own signedness.
   *
   * A width C cannot spell — sysl's `i5`, `u12` — takes the ordinary rule for its width. There is no
   * clang answer to measure against because there is no C declaration to write, and following the
   * sign is what every convention that extends anything does with the widths there are.
   */
  def extension(t: Type, target: Target): List[ir.Attr] = Type.underlying(t) match {
    case Type.Bool                     => widen(1, signed = false, target)
    // A `char` is a Unicode scalar in a `u32` (`Type.Char`), so it is that width's answer.
    case Type.Char                     => widen(32, signed = false, target)
    // A **simple** enum is its discriminant, whose width its `: iN` annotation chose — which is the
    // `-fshort-enums` case, and the reason this is read off the enum rather than assumed to be `int`.
    case e: Type.Enum if e.simple      => widen(e.underlying.bits, e.underlying.signed, target)
    case Type.Integer(bits, signed, _) => widen(bits, signed, target)
    case _                             => Nil
  }

  private def widen(bits: Int, signed: Boolean, target: Target): List[ir.Attr] =
    // **CRAFT widens nothing, because a widening is a promise made to a C compiler and there is no
    // C compiler here.** Every entry below says what some clang does with the bits above a narrow
    // value; this machine has none to disagree with, and its register is two bytes rather than the
    // four or eight every one of those rules is stated against — so `signext i8` would be an
    // instruction bought to keep a bargain nobody is on the other end of.
    if target.cpu == Cpu.Craft then Nil
    else if target.cpu == Cpu.Aarch64 && target.os != Os.MacOS then Nil
    else if bits == 1 then List(ir.Attr.ZeroExt)
    else if target.cpu == Cpu.X86_64 && target.os == Os.Windows then Nil
    else if bits < 32 then List(if signed then ir.Attr.SignExt else ir.Attr.ZeroExt)
    else if bits == 32 && target.cpu == Cpu.Riscv64 then List(ir.Attr.SignExt)
    else Nil

  /** The registers an aggregate occupies, named twice — because a result and an argument may spell
   * the same registers differently, which is AAPCS64's case throughout and nobody else's.
   */
  private enum Shape {
    case Memory
    case Registers(returned: LType, passed: List[Arg])

    /** AAPCS32's case, and the reason `Memory` is not enough on its own: that convention returns
     * anything wider than a register through storage while still passing the very same aggregate
     * **in whole words**, at any size at all. A sixty-four-byte struct is an `sret` result and a
     * `[16 x i32]` argument, so the two answers cannot be read off one verdict.
     */
    case Split(passed: List[Arg])
  }

  /** Where a result and an argument name the same registers the same way, which is every convention
   * but AAPCS64: a result in two registers is a literal struct of them, and an argument in two is
   * two parameters.
   */
  private def alike(pieces: List[LType]): Shape =
    Shape.Registers(
      if pieces.length == 1 then pieces.head else LType.Struct(pieces),
      pieces.map(Arg(_)),
    )

  private def shape(t: Type, target: Target)(using l: Layout): Shape = {
    val size = l.size(t)

    target.cpu match
      case Cpu.Aarch64                           => aapcs64(t, size, target.os == Os.MacOS)
      case Cpu.X86_64 if target.os == Os.Windows => windows(size)
      case Cpu.X86_64                            => sysv(t, size)
      case Cpu.Riscv64                           => riscv(t, size, target.hardFloat, xlen = 8)
      case Cpu.Riscv32                           => riscv(t, size, target.hardFloat, xlen = 4)
      case Cpu.Thumb                             => aapcs32(t, size, target.hardFloat)
      case Cpu.Wasm32                            => wasm(t, size)
      // **CRAFT has no C compiler, so there is no convention to agree with**, and that is the answer
      // rather than a measurement nobody made. Every other row here was established by compiling the
      // equivalent C and reading what clang did (`targets.md § Adding one`); this machine has no
      // libc, no craft clang and no linker, so nothing on the other side of a call is C. What is
      // left is the **back end's own** lowering, and passing an aggregate through memory is what it
      // does: a register is two bytes, so anything past a single scalar is already indirect, and
      // `CanLowerReturn` demotes a result wider than the two registers a return travels in.
      case Cpu.Craft                             => craft(t, size)
      // i386 is refused at the registry (`Target.supported`) for want of exactly this, so nothing
      // reaches here — and when its convention is measured, this is the line that gains it.
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
  private def aapcs64(t: Type, size: Int, macOS: Boolean)(using l: Layout): Shape = {
    val ls        = leaves(t).map(l2 => Type.underlying(l2._2))
    val addresses = ls.nonEmpty && ls.forall(address)

    hfa(ls) match
      case Some((elem, n)) =>
        // Away from Darwin the convention says how far to align a floating aggregate that runs out
        // of registers and lands on the stack. Darwin's variant does not ask for it.
        Shape.Registers(t.lty,
                        List(Arg(LType.Arr(n, elem),
                                 if macOS then Nil else List(ir.Attr.AlignStack(8)))))
      case None if size <= 8 =>
        Shape.Registers(LType.I(size * 8), List(Arg(if addresses then LType.Ptr else LType.I(64))))
      // Two registers, and what names them is the *alignment*: sixteen bytes wanting sixteen-byte
      // alignment is one `i128`, which is a register pair, where eight-byte alignment is two of them.
      case None if size <= 16 && l.align(t) >= 16 => alike(List(LType.I(128)))
      case None if size <= 16 =>
        Shape.Registers(LType.Arr(2, LType.I(64)),
                        List(Arg(LType.Arr(2, if addresses then LType.Ptr else LType.I(64)))))
      case None => Shape.Memory
  }

  /** The floating type and count of a homogeneous floating aggregate, or `None`. Four is the limit
   * because that is how many registers the convention sets aside for one; a fifth member makes the
   * whole thing an ordinary aggregate again, however small it is.
   */
  private def hfa(ls: List[Type])(using Word): Option[(LType, Int)] =
    Option.when(ls.nonEmpty && ls.length <= 4 && ls.forall(floating) && ls.distinct.length == 1)(
      (ls.head.lty, ls.length),
    )

  // --- AAPCS32 ---------------------------------------------------------------------------

  /** AAPCS32, the Cortex-M convention, in **both** of its float variants — and the one that breaks
   * the shape every other convention here has, because **its two directions disagree about memory
   * itself** rather than merely about how to name the same registers.
   *
   * `hardFloat` selects the variant, and it changes exactly one thing: whether a homogeneous
   * floating aggregate has floating registers to travel in. Everything below the HFA question is the
   * same for `eabihf` and `eabi`, which is what makes this one function rather than two.
   *
   * A result wider than one register goes through storage, at any size. An argument **never** does:
   * whatever its size, it is named as whole words and the back end puts the surplus on the stack
   * itself. So a sixty-four-byte struct is an `sret` result and a `[16 x i32]` argument, which is
   * `Shape.Split`, and it is why `Param.Indirect` is unreachable on this target.
   *
   * The word an argument is counted in follows the aggregate's **alignment**, not its size: eight
   * bytes of alignment makes `{ long long, int }` a `[2 x i64]` where four would have made it
   * `[4 x i32]`. And a result of four bytes or fewer is named by a whole power-of-two width — one
   * byte is `i8`, two `i16`, and three as well as four `i32`.
   *
   * All of it read off `clang -target thumbv8m.main-none-eabihf -S -emit-llvm`, and the softfp half
   * off the same command with `-none-eabi`.
   */
  private def aapcs32(t: Type, size: Int, hardFloat: Boolean)(using l: Layout): Shape = {
    val ls = leaves(t).map(l2 => Type.underlying(l2._2))

    // **The HFA rule is the hard-float variant's and belongs to it alone.** Under `softfp` there are
    // no floating registers for an aggregate to travel in, so a struct of floats is an ordinary
    // aggregate of its size and takes the rules below — which is why `struct { float; }` returns as
    // an `i32` there and as the struct itself under `eabihf`. Measured against clang for both
    // triples, and the three sizes that disagree are exactly the ones `AbiAgainstClangTests` names.
    Option.when(hardFloat)(hfa(ls)).flatten match
      // An HFA crosses as the struct type itself in both directions: one VFP register per member is
      // what the convention asks for and what LLVM does with a struct unaided, so the two agree and
      // there is nothing to coerce. That is the opposite of AAPCS64, which names the same registers
      // as an array — and the difference is clang's, not a choice available here.
      case Some(_) => Shape.Registers(t.lty, List(Arg(t.lty)))
      case None =>
        val unit  = if l.align(t) >= 8 then 8 else 4
        val words = List(Arg(LType.Arr(roundUp(size, unit) / unit, LType.I(unit * 8))))

        if size <= 4 then
          Shape.Registers(if size <= 2 then LType.I(size * 8) else LType.I(32), words)
        else Shape.Split(words)
  }

  // --- System V x86-64 -------------------------------------------------------------------

  /** System V, which classifies the aggregate **one eightbyte at a time**: a chunk whose every byte
   * belongs to a floating member travels in a floating register, anything else in an integer one.
   * Past two eightbytes there are no registers left for it and the whole thing goes in memory.
   */
  private def sysv(t: Type, size: Int)(using l: Layout): Shape =
    if size > 16 || misaligned(t, 0) then Shape.Memory
    else
      val ls = leaves(t)

      alike((0 until (size + 7) / 8).toList.map { i =>
        val lo   = i * 8
        val hi   = math.min(size, lo + 8)
        val here = ls.filter((off, m) => off < hi && off + l.size(m) > lo)

        if here.nonEmpty && here.forall(m => floating(m._2)) then floatingChunk(here)
        else integerChunk(ls, lo, hi, size)
      })

  /** Whether any member of the aggregate sits off its own alignment, which System V classifies as
   * **MEMORY whatever the size is** — the one clause of the classification that is about the layout
   * rather than about the members' kinds, and the only thing `@packed` changes about a call.
   *
   * `struct __attribute__((packed)) { unsigned char a; unsigned int b; }` is five bytes and would
   * otherwise be one eightbyte in a register; clang returns it through `sret` and takes it as
   * `byval align 8`. Nothing else here needs asking, since a member is on its alignment in every
   * layout but a packed one.
   *
   * **A bitfield struct is never one of these**, and that is clang's answer rather than a choice
   * made here: its members are ranges of a container rather than objects with addresses, so there is
   * nothing for the clause to be about. A three-byte one comes back as `i24`.
   */
  private def misaligned(t: Type, at: Int)(using l: Layout): Boolean = Type.underlying(t) match
    case s: Type.Struct if Bitfields.of(s).isDefined => false

    case s: Type.Struct =>
      var off = at
      var bad = false

      for (_, f) <- s.stored do
        if !s.packed then off = roundUp(off, l.align(f))
        if off % l.align(f) != 0 || misaligned(f, off) then bad = true
        off += l.size(f)

      bad

    // Every element of an array is the stride from the one before it, so if the first is on its
    // alignment they all are — and if it is not, none of them is.
    case Type.Array(n, elem) => n > 0 && misaligned(elem, at)
    case _                   => false

  /** Members that all fit in floating registers are named by what fills the chunk: several of one
   * width as a vector of that many, one on its own — a `double`, or a `float` in a tail chunk with
   * nothing beside it — as itself. A chunk two *different* floating widths share has no such name,
   * and clang falls back to a whole `double`.
   */
  private def floatingChunk(here: List[(Int, Type)])(using Word): LType = {
    val kinds = here.map(l => Type.underlying(l._2).lty).distinct

    if kinds.length > 1 then LType.F(64)
    else if here.length == 1 then kinds.head
    else LType.Vec(here.length, kinds.head)
  }

  /** An integer chunk is named by **the member that starts it**, when that member is all the chunk
   * carries: a `u8` in the second eightbyte of `{i64, u8}` is an `i8`, not the whole register it
   * will travel in. That distinction is not decoration — it is what clang writes, and writing
   * something else would leave a diff against clang unusable as the oracle it is. When the chunk
   * carries more than the member starting it, there is no one member to name it after and it is the
   * bytes that are left: eight of them, or fewer in the tail.
   */
  private def integerChunk(ls: List[(Int, Type)], lo: Int, hi: Int, size: Int)(using l: Layout): LType = {
    val starts = ls.find(_._1 == lo).map(_._2)

    // Clamped to what is left of the aggregate, because a member's *allocation* may be wider than
    // the storage it occupies here: a bitfield struct's three-byte container is an `i24`, whose
    // stride standing alone is four. Naming the chunk `i32` would claim a byte the struct does not
    // have, and clang writes `i24`. Nothing else is affected — outside a packed layout no member
    // reaches past the end of what holds it.
    val width  = starts.map(m => math.min(l.size(m), size - lo)).getOrElse(0)
    val alone  = !ls.exists((off, _) => off >= lo + width && off < math.min(lo + 8, size))

    // An address fills its chunk and is named as one, which changes nothing about the call — an
    // address travels in the integer register a number would — but it keeps what a pointer carries
    // beyond its bits, and it is what clang writes.
    if width == 8 && starts.exists(address) then LType.Ptr
    else if alone && (width == 1 || width == 2 || width == 4) then LType.I(width * 8)
    else LType.I((hi - lo) * 8)
  }

  // --- RISC-V, LP64D and ILP32 -----------------------------------------------------------

  /** RISC-V's convention, which is **one rule with the register width in it** rather than two: every
   * threshold below is a count of `XLEN`, so LP64D and ILP32 are the same function with `xlen` at
   * eight and at four. That is not a convenience — it is what the specification says, and writing
   * the 32-bit case out separately would be inviting the two to drift.
   *
   * The hardware-float variant **flattens** the narrow cases: an aggregate of one or two floating
   * members travels in floating registers, and one floating member beside one integer member travels
   * in one of each. Anything else falls back to size — including a pointer beside a float, which the
   * convention does not count as the integer case.
   *
   * Neither bare-metal RISC-V has floating registers to flatten into (`Target.hardFloat`), at either
   * width, so on both of them the size rule is the whole of it — the RP2350's Hazard3 is RV32IMAC
   * with no F extension at all.
   *
   * Two registers are named by the aggregate's **alignment**: a two-word span aligned to two words is
   * one double-width integer, and otherwise it is an array of two. Verified against
   * `clang -target riscv32-unknown-elf -march=rv32imac -mabi=ilp32`, where `{ double }` comes back as
   * `i64` and `{ int, int }` as `[2 x i32]` — same eight bytes, different alignment, different name.
   */
  private def riscv(t: Type, size: Int, hardFloat: Boolean, xlen: Int)(using l: Layout): Shape = {
    val ls   = leaves(t).map(l2 => Type.underlying(l2._2))
    val fps  = ls.count(floating)
    val ints = ls.count(m => integral(m) && l.size(m) <= xlen)
    val one  = LType.I(xlen * 8)

    if hardFloat && ls.length <= 2 && fps >= 1 && fps + ints == ls.length then
      alike(ls.map(m => if floating(m) then m.lty else LType.I(l.size(m) * 8)))
    else if size <= xlen then alike(List(one))
    else if size <= xlen * 2 then
      alike(List(if l.align(t) >= xlen * 2 then LType.I(xlen * 16) else LType.Arr(2, one)))
    else Shape.Memory
  }

  // --- Windows x64 -----------------------------------------------------------------------

  /** The Microsoft convention, the simplest of the four: an aggregate whose size is exactly one
   * register's worth travels in one register as an integer of that width, and everything else — a
   * three-byte struct as much as a thirty-byte one — goes by address. There is no floating case and
   * no pointer case; a pair of floats is eight bytes and travels as an `i64`.
   */
  private def windows(size: Int): Shape =
    if size == 1 || size == 2 || size == 4 || size == 8 then alike(List(LType.I(size * 8)))
    else Shape.Memory

  // --- WebAssembly ------------------------------------------------------------------------

  /** WebAssembly's convention, which is the simplest of the seven and the only one that asks nothing
   * about **size**. An aggregate that is one scalar with structs and one-element arrays wrapped
   * round it travels as that scalar; everything else goes in memory, at any size at all.
   *
   * So a pair of `i32` — eight bytes, which every other convention here puts in a register or two —
   * is `byval` on this machine, and so is a pair of floats. There is no eightbyte classification, no
   * homogeneous floating aggregate rule and no threshold to be off by one about: the whole convention
   * is one predicate and two answers, and this function is what that predicate costs.
   *
   * Measured against clang for `wasm32-unknown-unknown`, as `targets.md § Adding one` requires, and
   * `AbiAgainstClangTests` re-asks it every run.
   */
  private def wasm(t: Type, size: Int)(using l: Layout): Shape =
    onlyScalar(t, size) match
      case Some(scalar) => alike(List(scalar.lty))
      case None         => Shape.Memory

  // --- CRAFT -----------------------------------------------------------------------------

  /** CRAFT, whose rule reads like wasm's and is arrived at from the opposite direction — which is
   * why it is written twice rather than shared.
   *
   * wasm flattens nothing because the machine has no registers for an aggregate to be flattened
   * *into*. CRAFT has eight of them and they are **two bytes each**, so a rule that put an aggregate
   * in registers would be spending the whole register file on a struct of four fields. The back end
   * does not: anything past a single scalar travels through memory, and a result too wide for the
   * two registers a return uses is demoted to an out-pointer by `CanLowerReturn`.
   *
   * The one thing that *is* unwrapped is a struct of exactly one scalar with no padding round it,
   * because there is nothing there to pass indirectly.
   */
  private def craft(t: Type, size: Int)(using l: Layout): Shape =
    onlyScalar(t, size) match
      case Some(scalar) => alike(List(scalar.lty))
      case None         => Shape.Memory

  /** The one scalar an aggregate is made of, where it is made of exactly one and no padding was
   * added round it — clang's `isSingleElementStruct`, which sees through nesting in both directions:
   * a struct holding a struct holding a `double` is a `double`, and so is a one-element array of
   * either.
   *
   * **The size check is what stops an over-aligned wrapper from being unwrapped.** A struct of one
   * `i32` declared to align to eight is eight bytes with one member, and clang passes it in memory
   * rather than as an `i32` — the padding is part of what the callee is promised, so unwrapping would
   * hand over four bytes where eight were expected.
   *
   * `leaves` does the seeing-through, because it already walks the layout the emitted module has
   * rather than the shape a program wrote: a zero-sized member contributes nothing here exactly as it
   * contributes nothing to the homogeneity question.
   */
  private def onlyScalar(t: Type, size: Int)(using l: Layout): Option[Type] =
    leaves(t) match
      case (0, scalar) :: Nil if l.size(scalar) == size => Some(scalar)
      case _                                            => None

  // --- the members an aggregate is made of -----------------------------------------------

  /** The scalars an aggregate is made of, flattened through every level of nesting, each paired
   * with the byte it starts at. This is what the homogeneity question, the flattening question and
   * the eightbyte question are all asked of, and it walks the layout the emitted module actually has
   * rather than the one a program wrote — a zero-sized field occupies nothing and is not a member
   * here.
   */
  def leaves(t: Type)(using Layout): List[(Int, Type)] = {
    val acc = mutable.ListBuffer.empty[(Int, Type)]

    walk(t, 0, acc)
    acc.toList
  }

  private def walk(t: Type, at: Int, acc: mutable.ListBuffer[(Int, Type)])(using l: Layout): Unit =
    Type.underlying(t) match {
      // A bitfield struct has one member however many fields were written — the container its
      // ranges are cut from (`Bitfields`) — so that is the one leaf. Walking the written fields
      // instead would put each at a byte offset of its own and make a one-byte struct three bytes
      // of members, which is a classification of a layout nothing has.
      case s: Type.Struct if Bitfields.of(s).isDefined =>
        acc += ((at, Type.Integer(Bitfields.bits(Bitfields.of(s).get), signed = false)))

      case s: Type.Struct =>
        var off = at

        for (_, f) <- s.stored do
          off = roundUp(off, l.align(f))
          walk(f, off, acc)
          off += l.size(f)

      case Type.Array(n, elem) =>
        val stride = l.size(elem)

        for i <- 0 until n do walk(elem, at + i * stride, acc)

      // A data enum is the tag and the payload region, and the region is a union — there is no
      // member to name inside it, so it counts as the integers it is emitted as. That makes an enum
      // never homogeneous and never all-floating, which is right: a union of a float and an integer
      // has to travel somewhere both of them fit.
      case e: Type.Enum if !e.simple =>
        val unit  = math.max(1, l.payloadAlign(e))
        val start = at + roundUp(4, unit)

        acc += ((at, Type.Integer(32, signed = true)))
        for i <- 0 until (l.payloadSize(e) + unit - 1) / unit do
          acc += ((start + i * unit, Type.Integer(unit * 8, signed = false)))

      // A view is the owner, the first element, and the count; a trait object is the table and the
      // value. Both are addresses and, for the view, a number — which is all the ABI needs of them.
      // The strides are words rather than eights: on a 32-bit machine these members sit at 0, 4, 8.
      case _: Type.View =>
        val w = l.pointerBytes

        acc += ((at, Type.Ptr(Type.Byte)))
        acc += ((at + w, Type.Ptr(Type.Byte)))
        acc += ((at + w * 2, Type.usize))

      case Type.VaList =>
        for i <- 0 until 4 do acc += ((at + i * l.pointerBytes, Type.Ptr(Type.Byte)))

      case p if Type.erased(p) || erasedWeak(p) =>
        acc += ((at, Type.Ptr(Type.Byte)))
        acc += ((at + l.pointerBytes, Type.Ptr(Type.Byte)))

      case scalar => acc += ((at, scalar))
    }

  private def erasedWeak(t: Type): Boolean = t match {
    case Type.Weak(inner) => inner.isInstanceOf[Type.Trait]
    case _                => false
  }

  private def floating(t: Type): Boolean = Type.underlying(t).isInstanceOf[Type.Floating]

  private def address(t: Type): Boolean = Type.underlying(t) match {
    case _: Type.Ptr | _: Type.Ref | _: Type.Weak | _: Type.CFn => true
    case _                                                      => false
  }

  private def integral(t: Type): Boolean = Type.underlying(t) match {
    case _: Type.Integer | Type.Bool | Type.Char => true
    case _                                       => false
  }

  private def roundUp(n: Int, to: Int): Int = (n + to - 1) / to * to
}
