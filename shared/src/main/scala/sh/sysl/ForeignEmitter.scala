package sh.sysl

import ir.{Access, Inst, LType, Val}

/** The seam with a foreign function: what an `extern` is declared as, and what a call to one emits.
 *
 * A call from sysl to sysl needs none of this. Both sides are this compiler, so whatever it does
 * with an aggregate is a convention like any other and the two agree by construction — a small one
 * crosses as itself, a large one through memory (`Emitter`'s `syslSret` / `syslParam`), and neither
 * has to match what any other language would have done. A foreign callee was compiled by somebody
 * else against a published document, and the document is then the only thing that can make the two
 * agree — so a declaration here names the types **that** convention asks for (`CAbi`) rather than
 * the ones the sysl signature is written in, and the call converts each value between the two.
 *
 * Every conversion goes **through memory**: the value is stored to a slot and the coerced shape is
 * read back over the same bytes. That is what clang does and it is what is available — LLVM has no
 * instruction that reinterprets one aggregate as another, and the two shapes deliberately do not
 * agree field for field. The bytes copied are always the *sysl* type's own, so a coerced form wider
 * than the value (a five-byte struct arriving in a whole register) leaves its surplus unspecified,
 * exactly as the conventions say it is.
 */
trait ForeignEmitter extends ArcEmitter {

  /** The type a foreign declaration and every call to it name.
   *
   * It is one value rather than a result and a parameter list because that is what it is: a
   * `declare` is this plus a symbol, and a variadic **call** names the whole of it, so building the
   * two out of one thing is what keeps them from disagreeing.
   */
  protected def foreignSignature(retTy: Type, params: List[Type], variadic: Boolean): ir.FnType =
    ir.FnType(foreignResult(retTy).ret,
              // The out-parameter goes in front of everything, which is how the callee finds it
              // whatever else the signature holds.
              (CAbi.result(retTy, target) match
                case CAbi.Result.Sret(ty, align) => List(ir.Param(LType.Ptr, sretAttrs(ty, align)))
                case _                           => Nil) :::
                params.filterNot(Type.zeroSized).flatMap(foreignParams),
              variadic,
              foreignResult(retTy).retAttrs)

  /** What a call to a foreign function names as its result. */
  protected def foreignResult(retTy: Type): CallForm = CAbi.result(retTy, target) match
    // A narrow scalar is named as itself and carries the convention's extension in front of it, so
    // that what the callee widened is what this side reads back (`CAbi.extension`).
    case CAbi.Result.Plain      => CallForm(retTy.lty, CAbi.extension(retTy, target))
    case CAbi.Result.Coerced(l) => CallForm(l)
    case CAbi.Result.Sret(_, _) => CallForm(LType.Void)

  private def foreignParams(p: Type): List[ir.Param] = CAbi.param(p, target) match
    case CAbi.Param.Plain                     => List(ir.Param(p.lty, CAbi.extension(p, target)))
    case CAbi.Param.Coerced(pieces)           => pieces
    case CAbi.Param.Indirect(ty, align, true) => List(ir.Param(LType.Ptr, byvalAttrs(ty, align)))
    // **A pointer the callee is handed rather than a copy it is given carries the alignment too**,
    // which is the half `byval` states as part of its own attribute. clang states it on both, and
    // sysl follows clang wherever the two can differ — card `0339`. It is a claim about the pointer
    // and not about where the bytes travel, so the placement is what it always was.
    case CAbi.Param.Indirect(_, align, false) => List(ir.Param(LType.Ptr, List(ir.Attr.Align(align))))

  /** Lowers a call to a foreign function. `what` is what the `call` names between the keyword and
   * the callee — the result type, or the callee's whole function type where it is variadic.
   */
  protected def genForeignCall(what: CallForm, callee: Val, args: List[TExpr], ty: Type): Val = {
    val result = CAbi.result(ty, target)

    // The storage a big result is written into is the caller's, so it exists before the call, and it
    // is named in front of every argument — which is how the callee finds it whatever else the
    // signature holds.
    val returned = result match
      case CAbi.Result.Sret(ty, align) =>
        val slot = emitAlloca(freshReg(), ty)

        Some((slot, ir.Arg(LType.Ptr, slot, sretAttrs(ty, align))))
      case _ => None

    // An argument past the declared parameters is a variadic extra, and C classifies one exactly as
    // it classifies a declared parameter — so both take the same path, each at its own type.
    val passed = args.flatMap { a =>
      val v = genExpr(a)

      if Type.zeroSized(a.ty) then Nil
      else
        CAbi.param(a.ty, target) match
          // The extension is stated at the call as well as on the declaration, because it is the
          // **caller's** obligation and the call is where the caller is: it is this line that makes
          // the back end widen the value before it goes into the register.
          case CAbi.Param.Plain =>
            List(ir.Arg(a.ty.lty, v, CAbi.extension(a.ty, target)))
          case CAbi.Param.Coerced(pieces) => spread(v, a.ty, pieces)
          case CAbi.Param.Indirect(ty, align, byval) =>
            val slot = emitAlloca(freshReg(), ty)

            emit(Inst.Store(ty, v, slot, Access.Plain))
            List(ir.Arg(LType.Ptr, slot,
                        if byval then byvalAttrs(ty, align) else List(ir.Attr.Align(align))))
    }

    val arguments = returned.map(_._2).toList ::: passed

    result match
      case CAbi.Result.Sret(coerced, _) =>
        emit(what.call(None, callee, arguments))

        val r = freshReg()

        emit(Inst.Load(r, coerced, returned.get._1, Access.Plain))
        ownTemp(r, ty)

      case CAbi.Result.Coerced(coerced) =>
        val r = freshReg()

        emit(what.call(Some(r), callee, arguments))
        // A homogeneous floating aggregate comes back under its own type, so there is nothing to
        // reinterpret and the round trip through memory would be a copy for its own sake.
        ownTemp(if coerced == ty.lty then r else gather(r, coerced, ty), ty)

      case CAbi.Result.Plain =>
        if Type.noValue(ty) then
          emit(what.call(None, callee, arguments))
          if ty == Type.Never then emitTerm(Inst.Unreachable)
          Val.Nothing
        else
          val r = freshReg()

          emit(what.call(Some(r), callee, arguments))
          ownTemp(r, ty)
  }

  /** `byval`, which names the aggregate the caller's copy holds as well as the boundary it sits on.
   * Its `sret` counterpart is on `Emitter`, because four places outside this file state one.
   */
  private def byvalAttrs(ty: LType, align: Int): List[ir.Attr] =
    List(ir.Attr.ByVal(ty), ir.Attr.Align(align))

  /** A value of `t`, spread into the registers the convention hands it over in. Several registers
   * are read back out of a literal struct of them, which lays each piece out at the offset of the
   * eightbyte it stands for.
   */
  private def spread(v: Val, t: Type, pieces: List[CAbi.Arg]): List[ir.Arg] = {
    val holder = if pieces.length == 1 then pieces.head.ty else LType.Struct(pieces.map(_.ty))
    val slot   = reinterpret(v, t.lty, holder, layout.size(t))

    if pieces.length == 1 then
      val r = freshReg()

      emit(Inst.Load(r, holder, slot, Access.Plain))
      List(ir.Arg(pieces.head.ty, r, pieces.head.attrs))
    else
      pieces.zipWithIndex.map { (p, i) =>
        val at = freshReg()
        val r  = freshReg()

        emit(Inst.Gep(at, holder, slot,
                      List(ir.Arg(LType.I(32), Val.Int(0)), ir.Arg(LType.I(32), Val.Int(i)))))
        emit(Inst.Load(r, p.ty, at, Access.Plain))
        ir.Arg(p.ty, r, p.attrs)
      }
  }

  /** A value that arrived in the registers `llvm` names, read back as the `t` it stands for. */
  private def gather(v: Val, coerced: LType, t: Type): Val = {
    val slot = reinterpret(v, coerced, t.lty, layout.size(t))
    val r    = freshReg()

    emit(Inst.Load(r, t.lty, slot, Access.Plain))
    r
  }

  /** Writes `v` down as `from` and hands back a slot of `to` holding the same `bytes` bytes. The
   * count is the sysl type's own size in both directions, which is the only length both shapes are
   * known to have — the coerced one is never narrower, and where it is wider the surplus is what
   * the convention leaves unspecified.
   *
   * Both operands are stated as byte-aligned, which is the one claim that is true of every pair a
   * coercion can produce: a coerced form may be an `i8`, whose slot LLVM aligns to one, and a
   * *higher* claim than the slot has would be a promise the emitted code does not keep. Nothing is
   * lost by understating it — the guarantee is a floor, and LLVM refines it from the `alloca` it can
   * see right above.
   */
  private def reinterpret(v: Val, from: LType, to: LType, bytes: Int): Val = {
    val src = emitAlloca(freshReg(), from)
    val dst = emitAlloca(freshReg(), to)

    emit(Inst.Store(from, v, src, Access.Plain))
    emitMemcpy(dst, src, bytes, 1)
    dst
  }
}
