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

/** How strongly an atomic access is ordered against the accesses around it.
 *
 * These are C11's five, and they are named after C11 rather than after LLVM — with the one exception
 * LLVM itself made: it kept C11's *semantics* for the weakest under its own older name, `monotonic`,
 * so `Relaxed` is the single member whose text is not its own name lowercased.
 *
 * **It is an enum rather than the string it used to be because an ordering is not free-form.** LLVM
 * accepts exactly these words at exactly these instructions, and one built by interpolation is one
 * nothing can check — the failure of a misspelled ordering is a module the verifier rejects, a layer
 * further on than the mistake.
 */
enum Ordering {
  case Relaxed, Acquire, Release, AcqRel, SeqCst

  def render: String = this match
    case Relaxed => "monotonic"
    case AcqRel  => "acq_rel"
    case SeqCst  => "seq_cst"
    case other   => other.toString.toLowerCase

  /** The ordering the **failure** path of this compare-and-swap gets.
   *
   * `cmpxchg` takes two and a program writes one. A failure is a load that stored nothing, so it may
   * carry no release — LLVM refuses the instruction if it does — and the rule is C11's: drop the
   * release half and keep whatever acquire was asked for, which is the strongest the failure path is
   * allowed and so never weaker than the author expected.
   */
  def onFailure: Ordering = this match
    case Release => Relaxed
    case AcqRel  => Acquire
    case other   => other
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
  case Atomic(ordering: Ordering)
}

/** A **relaxation of IEEE arithmetic**, which is a property of one instruction rather than of a
 * type or a module — LLVM writes it between the opcode and the result type.
 *
 * There is one, because the compiler asks for one: a vector's floating sum reduces as a tree rather
 * than as a left fold, and a tree is a different answer unless the arithmetic may be reassociated.
 * Nothing else sysl emits gives up an exactness the language promised, so a second case belongs here
 * only when something does.
 */
enum FastMath {
  case Reassoc

  def render: String = this match
    case Reassoc => "reassoc"
}

/** What an `atomicrmw` does to the word it is reading and writing.
 *
 * A closed set, and a small one, because it is the set `Atomics` admits — `atomic_swap`, `add`,
 * `sub`, `and`, `or`, `xor`, and nothing else. LLVM has more; a case belongs here when the language
 * grows a name for one, not before.
 *
 * The odd spelling is `Xchg`, which is `atomic_swap` in sysl: LLVM's mnemonic and the language's
 * word for the same operation differ, and this is the one place they meet.
 */
enum RmwOp {
  case Xchg, Add, Sub, And, Or, Xor

  def render: String = toString.toLowerCase
}

/** An argument at a call: its type, the value, and whatever the convention attaches to it.
 *
 * `attrs` is where a foreign boundary's `byval`, `sret`, `signext` and `zeroext` live. They are the
 * caller's obligation rather than the callee's, so they are stated here and not in the callee's
 * declaration (`ForeignEmitter`, `CAbi`).
 */
case class Arg(ty: LType, value: Val, attrs: List[Attr] = Nil) {
  def render: String =
    val a = if attrs.isEmpty then "" else Attr.text(attrs) + " "
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

  case Bin(dest: Val, op: BinOp, ty: LType, a: Val, b: Val)
  case Neg(dest: Val, ty: LType, v: Val)
  case IntCmp(dest: Val, pred: ICmp, ty: LType, a: Val, b: Val)
  case FloatCmp(dest: Val, pred: FCmp, ty: LType, a: Val, b: Val)
  case Cast(dest: Val, op: CastOp, from: LType, v: Val, to: LType)

  case Alloca(dest: Val, ty: LType, align: Option[Int])
  /** A read, and a write.
   *
   * **`align` is the boundary the *access* promises, and it is separate from the type's own.** LLVM
   * infers the natural alignment where nothing says otherwise, which is right for a slot and wrong
   * for a run of elements read as a vector: a `[]f32` promises its elements are four-byte-aligned
   * and promises nothing about where the run begins, so the honest number there is the element's
   * rather than the register's. An over-aligned access is not a slow access — it is undefined
   * behaviour the moment the address does not meet the claim.
   *
   * An atomic access **must** carry one, which is why the field is here rather than on
   * `Access.Atomic`: LLVM writes it at the instruction for both, so one place is one place.
   */
  case Load(dest: Val, ty: LType, ptr: Val, access: Access, align: Option[Int] = None)
  case Store(ty: LType, value: Val, ptr: Val, access: Access, align: Option[Int] = None)

  /** `getelementptr` — address arithmetic in units of a type rather than of bytes. The indices are
   * typed because LLVM writes them typed, and the first one steps over whole `ty`s while the rest
   * reach inside one.
   */
  case Gep(dest: Val, ty: LType, ptr: Val, indices: List[Arg])

  case Extract(dest: Val, ty: LType, agg: Val, indices: List[Int])
  case Insert(dest: Val, ty: LType, agg: Val, valueTy: LType, value: Val, indices: List[Int])

  /** `select`, which evaluates **both** arms and keeps one — the whole difference from an `if`, and
   * why the language has a construct of its own for it (`tast.scala`'s `TSelect`).
   *
   * `condTy` is `i1` for a scalar and `<N x i1>` for the lane-wise form, where the answer is a
   * different value in every lane. It defaults because all but one caller is the scalar one.
   */
  case Select(dest: Val, cond: Val, ty: LType, a: Val, b: Val, condTy: LType = LType.I(1))

  /** `insertelement` — one lane written into a vector, which is the register file's answer to
   * `insertvalue` and the way a vector literal is built up.
   */
  case InsertElement(dest: Val, ty: LType, vec: Val, elemTy: LType, value: Val, index: Arg)

  /** `extractelement` — one lane read back out. There is no address and so no bounds test: the index
   * was held to a constant in range where it was written.
   */
  case ExtractElement(dest: Val, ty: LType, vec: Val, index: Arg)

  /** `shufflevector` — lanes taken from two vectors in whatever order the mask says.
   *
   * The compiler selects exactly one shuffle, the splat: lane zero of the first operand across an
   * all-zero mask. It is the idiom every back end recognises, and it lowers to a single broadcast
   * instruction on a machine that has one.
   */
  case Shuffle(dest: Val, ty: LType, a: Val, b: Val, mask: Arg)

  /** A block of the target's own assembly, with the operands LLVM has to satisfy around it.
   *
   * `sideeffect` is unconditional and is not a flag here for that reason: every assembly sysl can
   * express is worth keeping whether or not anything reads its result, which is the point of
   * reaching for it at all. Several outputs come back as one anonymous struct — LLVM's shape rather
   * than the language's — and it is taken apart at the emit site.
   */
  case Asm(dest: Option[Val], ret: LType, text: String, constraints: String, args: List[Arg])

  /** A call. `dest` is `None` where the result is `void` or discarded.
   *
   * `calleeType` is the callee's **whole** function type, and is present only where the callee is
   * variadic: the argument list alone does not say where the declared parameters stop and the
   * ellipsis begins, so LLVM has the call state it. It is not a second answer about the result —
   * `ret` and `retAttrs` are the same information inside it, and both are filled in either case.
   */
  case Call(dest: Option[Val], ret: LType, callee: Val, args: List[Arg],
            retAttrs: List[Attr] = Nil, calleeType: Option[FnType] = None,
            fast: List[FastMath] = Nil)

  case VaArg(dest: Val, list: Val, ty: LType)
  case AtomicRmw(dest: Val, op: RmwOp, ptr: Val, ty: LType, value: Val, ordering: Ordering)

  /** `cmpxchg`, which takes **two** orderings where a program writes one — see `Ordering.onFailure`
   * for why the second is derived rather than asked for.
   */
  case CmpXchg(dest: Val, ptr: Val, ty: LType, expected: Val, desired: Val, ordering: Ordering)
  case Fence(ordering: Ordering)

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
    case Bin(d, op, ty, a, b)      => s"${d.render} = ${op.render} ${ty.render} ${a.render}, ${b.render}"
    case Neg(d, ty, v)            => s"${d.render} = fneg ${ty.render} ${v.render}"
    case IntCmp(d, p, ty, a, b)   => s"${d.render} = icmp ${p.render} ${ty.render} ${a.render}, ${b.render}"
    case FloatCmp(d, p, ty, a, b) => s"${d.render} = fcmp ${p.render} ${ty.render} ${a.render}, ${b.render}"
    case Cast(d, op, f, v, t)     => s"${d.render} = ${op.render} ${f.render} ${v.render} to ${t.render}"

    case Alloca(d, ty, align) =>
      s"${d.render} = alloca ${ty.render}${align.map(n => s", align $n").getOrElse("")}"

    case Load(d, ty, p, acc, align) =>
      s"${d.render} = load${accessText(acc)} ${ty.render}, ptr ${p.render}${orderText(acc)}${alignText(align)}"

    case Store(ty, v, p, acc, align) =>
      s"store${accessText(acc)} ${ty.render} ${v.render}, ptr ${p.render}${orderText(acc)}${alignText(align)}"

    case Gep(d, ty, p, idx) =>
      s"${d.render} = getelementptr ${ty.render}, ptr ${p.render}, ${idx.map(_.render).mkString(", ")}"

    case Extract(d, ty, agg, idx) =>
      s"${d.render} = extractvalue ${ty.render} ${agg.render}, ${idx.mkString(", ")}"

    case Insert(d, ty, agg, vty, v, idx) =>
      s"${d.render} = insertvalue ${ty.render} ${agg.render}, ${vty.render} ${v.render}, ${idx.mkString(", ")}"

    case Select(d, c, ty, a, b, ct) =>
      s"${d.render} = select ${ct.render} ${c.render}, ${ty.render} ${a.render}, ${ty.render} ${b.render}"

    case InsertElement(d, ty, vec, ety, v, i) =>
      s"${d.render} = insertelement ${ty.render} ${vec.render}, ${ety.render} ${v.render}, ${i.render}"

    case ExtractElement(d, ty, vec, i) =>
      s"${d.render} = extractelement ${ty.render} ${vec.render}, ${i.render}"

    case Shuffle(d, ty, a, b, mask) =>
      s"${d.render} = shufflevector ${ty.render} ${a.render}, ${ty.render} ${b.render}, ${mask.render}"

    case Asm(d, ret, text, cons, args) =>
      val lhs = d.map(r => s"${r.render} = ").getOrElse("")

      s"""${lhs}call ${ret.render} asm sideeffect "$text", "$cons"(${args.map(_.render).mkString(", ")})"""

    case Call(d, ret, callee, args, retAttrs, fn, fast) =>
      val lhs  = d.map(r => s"${r.render} = ").getOrElse("")
      // A variadic callee is named by its whole type; everything else by its result alone.
      val kind = fn.map(_.render).getOrElse((retAttrs.map(_.render) :+ ret.render).mkString(" "))
      val fm   = fast.map(_.render + " ").mkString

      s"${lhs}call $fm$kind ${callee.render}(${args.map(_.render).mkString(", ")})"

    case VaArg(d, list, ty) => s"${d.render} = va_arg ptr ${list.render}, ${ty.render}"

    case AtomicRmw(d, op, p, ty, v, ord) =>
      s"${d.render} = atomicrmw ${op.render} ptr ${p.render}, ${ty.render} ${v.render} ${ord.render}"

    case CmpXchg(d, p, ty, e, n, ord) =>
      s"${d.render} = cmpxchg ptr ${p.render}, ${ty.render} ${e.render}, ${ty.render} ${n.render} " +
        s"${ord.render} ${ord.onFailure.render}"

    case Fence(ord) => s"fence ${ord.render}"

    case Br(l)             => s"br label %$l"
    case CondBr(c, t, f)   => s"br i1 ${c.render}, label %$t, label %$f"
    case Ret(None, _)      => "ret void"
    case Ret(Some(ty), v)  => s"ret ${ty.render} ${v.map(_.render).getOrElse("")}".trim
    case Unreachable       => "unreachable"

    case Switch(ty, v, default, cases) =>
      val arms = cases.map((n, l) => s"${ty.render} $n, label %$l").mkString(" ")

      s"switch ${ty.render} ${v.render}, label %$default [ $arms ]"

  private def accessText(a: Access): String = a match
    case Access.Plain     => ""
    case Access.Volatile  => " volatile"
    case _: Access.Atomic => " atomic"

  private def orderText(a: Access): String = a match
    case Access.Atomic(o) => s" ${o.render}"
    case _                => ""

  private def alignText(align: Option[Int]): String = align.map(n => s", align $n").getOrElse("")
}
