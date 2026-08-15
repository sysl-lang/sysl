package sh.sysl
package ir

/** The binary arithmetic LLVM has, and all of it the compiler selects.
 *
 * The signed and unsigned members of each pair are separate opcodes rather than one opcode and a
 * flag, because that is what LLVM is: an `i32` carries no signedness, so the choice is made by the
 * instruction and by nothing else.
 */
enum BinOp {
  case Add, Sub, Mul, SDiv, UDiv, SRem, URem, Shl, AShr, LShr, And, Or, Xor
  case FAdd, FSub, FMul, FDiv

  def render: String = toString.toLowerCase
}

/** A conversion between two representations. Every one of these is a single instruction with no
 * runtime cost beyond the move, which is why the one conversion that can *fail* — `char(u)` — is a
 * check and a trap around one of these rather than a member of this list.
 */
enum CastOp {
  case Trunc, ZExt, SExt, FPTrunc, FPExt, FPToUI, FPToSI, UIToFP, SIToFP, PtrToInt, IntToPtr, BitCast

  def render: String = this match
    case FPToUI   => "fptoui"
    case FPToSI   => "fptosi"
    case UIToFP   => "uitofp"
    case SIToFP   => "sitofp"
    case PtrToInt => "ptrtoint"
    case IntToPtr => "inttoptr"
    case BitCast  => "bitcast"
    case other    => other.toString.toLowerCase
}

/** How two integers are compared. Signedness is here rather than in the type for the reason it is in
 * `BinOp`: the operands are bits and the instruction is what reads them as numbers.
 */
enum ICmp {
  case Eq, Ne, Ugt, Uge, Ult, Ule, Sgt, Sge, Slt, Sle

  def render: String = toString.toLowerCase
}

/** How two floats are compared. The `o` prefix is *ordered* — the answer is false if either operand
 * is a NaN, which is what a program means by `<` and what every comparison the compiler selects
 * uses.
 */
enum FCmp {
  case Oeq, One, Ogt, Oge, Olt, Ole, Ord, Ueq, Une, Ugt, Uge, Ult, Ule, Uno

  def render: String = toString.toLowerCase
}

/** How an access is reached: ordinarily, as a device effect, or indivisibly.
 *
 * `Volatile` is not an optimization barrier and does not order anything — it says the access is an
 * effect, so it happens exactly once and exactly where it was written (`03 § Device memory`).
 * `Atomic` carries the ordering LLVM needs spelled at the instruction, which is why an ordering is
 * refused as a runtime value at the call that asks for one.
 */
enum Access {
  case Plain
  case Volatile
  case Atomic(ordering: String, align: Int)
}

/** An argument at a call: its type, the value, and whatever the convention attaches to it.
 *
 * `attrs` is where a foreign boundary's `byval`, `sret`, `signext` and `zeroext` live. They are the
 * caller's obligation rather than the callee's, so they are stated here and not in the callee's
 * declaration (`ForeignEmitter`, `CAbi`).
 */
case class Arg(ty: LType, value: Val, attrs: String = "") {
  def render: String =
    val a = if attrs.isEmpty then "" else s"$attrs "
    val v = value.render

    if v.isEmpty then s"${ty.render} $a".trim else s"${ty.render} $a$v"
}

/** **One instruction.**
 *
 * The set is small because the compiler's own selection is: about forty opcodes, of which ten carry
 * nine tenths of the emitted lines. What a second back end has to handle is exactly this and nothing
 * wider — there is no `phi` here, and that is a fact about the lowering rather than an omission.
 * Codegen keeps every local in a stack slot and reaches it with `load` and `store`, so what a
 * consumer receives is memory form and it may promote or not as it likes.
 */
enum Inst {

  /** Text an emitter interpolated. Scaffolding, and deleted when the last emit site is converted. */
  case Raw(text: String)

  case Bin(dest: Val, op: BinOp, ty: LType, a: Val, b: Val)
  case Neg(dest: Val, ty: LType, v: Val)
  case IntCmp(dest: Val, pred: ICmp, ty: LType, a: Val, b: Val)
  case FloatCmp(dest: Val, pred: FCmp, ty: LType, a: Val, b: Val)
  case Cast(dest: Val, op: CastOp, from: LType, v: Val, to: LType)

  case Alloca(dest: Val, ty: LType, align: Option[Int])
  case Load(dest: Val, ty: LType, ptr: Val, access: Access)
  case Store(ty: LType, value: Val, ptr: Val, access: Access)

  /** `getelementptr` — address arithmetic in units of a type rather than of bytes. The indices are
   * typed because LLVM writes them typed, and the first one steps over whole `ty`s while the rest
   * reach inside one.
   */
  case Gep(dest: Val, ty: LType, ptr: Val, indices: List[Arg])

  case Extract(dest: Val, ty: LType, agg: Val, indices: List[Int])
  case Insert(dest: Val, ty: LType, agg: Val, valueTy: LType, value: Val, indices: List[Int])
  case Select(dest: Val, cond: Val, ty: LType, a: Val, b: Val)

  /** A call. `dest` is `None` where the result is `void` or discarded, and `ret` carries the return
   * type together with whatever attribute the convention puts in front of it.
   */
  case Call(dest: Option[Val], ret: String, callee: Val, args: List[Arg], sig: Option[String] = None)

  case VaArg(dest: Val, list: Val, ty: LType)
  case AtomicRmw(dest: Val, op: String, ptr: Val, ty: LType, value: Val, ordering: String)
  case CmpXchg(dest: Val, ptr: Val, ty: LType, expected: Val, desired: Val, success: String, failure: String)
  case Fence(ordering: String)

  // --- terminators -----------------------------------------------------------------------

  case Br(label: String)
  case CondBr(cond: Val, ifTrue: String, ifFalse: String)
  case Ret(ty: Option[LType], value: Option[Val])
  case Switch(ty: LType, value: Val, default: String, cases: List[(BigInt, String)])
  case Unreachable

  /** Whether this ends a block. A terminator is where control leaves, which is the one thing a
   * consumer must be able to ask without reading the opcode table.
   */
  def terminates: Boolean = this match
    case _: Br | _: CondBr | _: Ret | _: Switch | Unreachable => true
    case _                                                    => false

  /** The instruction as LLVM writes it, without the indentation a body gives it. */
  def render: String = this match
    case Raw(text) => text

    case Bin(d, op, ty, a, b)      => s"${d.render} = ${op.render} ${ty.render} ${a.render}, ${b.render}"
    case Neg(d, ty, v)            => s"${d.render} = fneg ${ty.render} ${v.render}"
    case IntCmp(d, p, ty, a, b)   => s"${d.render} = icmp ${p.render} ${ty.render} ${a.render}, ${b.render}"
    case FloatCmp(d, p, ty, a, b) => s"${d.render} = fcmp ${p.render} ${ty.render} ${a.render}, ${b.render}"
    case Cast(d, op, f, v, t)     => s"${d.render} = ${op.render} ${f.render} ${v.render} to ${t.render}"

    case Alloca(d, ty, align) =>
      s"${d.render} = alloca ${ty.render}${align.map(n => s", align $n").getOrElse("")}"

    case Load(d, ty, p, acc)  => s"${d.render} = load${accessText(acc)} ${ty.render}, ptr ${p.render}${alignText(acc)}"
    case Store(ty, v, p, acc) => s"store${accessText(acc)} ${ty.render} ${v.render}, ptr ${p.render}${alignText(acc)}"

    case Gep(d, ty, p, idx) =>
      s"${d.render} = getelementptr ${ty.render}, ptr ${p.render}, ${idx.map(_.render).mkString(", ")}"

    case Extract(d, ty, agg, idx) =>
      s"${d.render} = extractvalue ${ty.render} ${agg.render}, ${idx.mkString(", ")}"

    case Insert(d, ty, agg, vty, v, idx) =>
      s"${d.render} = insertvalue ${ty.render} ${agg.render}, ${vty.render} ${v.render}, ${idx.mkString(", ")}"

    case Select(d, c, ty, a, b) =>
      s"${d.render} = select i1 ${c.render}, ${ty.render} ${a.render}, ${ty.render} ${b.render}"

    case Call(d, ret, callee, args, sig) =>
      val lhs  = d.map(r => s"${r.render} = ").getOrElse("")
      val kind = sig.map(s => s"$s ").getOrElse(s"$ret ")

      s"${lhs}call $kind${callee.render}(${args.map(_.render).mkString(", ")})"

    case VaArg(d, list, ty) => s"${d.render} = va_arg ptr ${list.render}, ${ty.render}"

    case AtomicRmw(d, op, p, ty, v, ord) =>
      s"${d.render} = atomicrmw $op ptr ${p.render}, ${ty.render} ${v.render} $ord"

    case CmpXchg(d, p, ty, e, n, s, f) =>
      s"${d.render} = cmpxchg ptr ${p.render}, ${ty.render} ${e.render}, ${ty.render} ${n.render} $s $f"

    case Fence(ord) => s"fence $ord"

    case Br(l)             => s"br label %$l"
    case CondBr(c, t, f)   => s"br i1 ${c.render}, label %$t, label %$f"
    case Ret(None, _)      => "ret void"
    case Ret(Some(ty), v)  => s"ret ${ty.render} ${v.map(_.render).getOrElse("")}".trim
    case Unreachable       => "unreachable"

    case Switch(ty, v, default, cases) =>
      val arms = cases.map((n, l) => s"${ty.render} $n, label %$l").mkString(" ")

      s"switch ${ty.render} ${v.render}, label %$default [$arms]"

  private def accessText(a: Access): String = a match
    case Access.Plain     => ""
    case Access.Volatile  => " volatile"
    case _: Access.Atomic => " atomic"

  private def alignText(a: Access): String = a match
    case Access.Atomic(o, align) => s" $o, align $align"
    case _                       => ""
}
