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

  /** The result type a foreign declaration and every call to it name, and the parameter list. */
  protected def foreignSignature(retTy: Type, params: List[Type], variadic: Boolean): (String, List[String]) = {
    val sret = CAbi.result(retTy, target) match
      // The out-parameter goes in front of everything, which is how the callee finds it whatever
      // else the signature holds.
      case CAbi.Result.Sret(ty, align) => List(s"ptr ${sretAttr(ty, align)}")
      case _                           => Nil

    val rest = params.filterNot(Type.zeroSized).flatMap(p => foreignParamTypes(p))

    (foreignResultType(retTy), sret ::: rest ::: Option.when(variadic)("...").toList)
  }

  protected def foreignResultType(retTy: Type): String = CAbi.result(retTy, target) match
    // A narrow scalar is named as itself and carries the convention's extension in front of it, so
    // that what the callee widened is what this side reads back (`CAbi.extension`).
    case CAbi.Result.Plain       => CAbi.returning(CAbi.extension(retTy, target), retTy.llvm)
    case CAbi.Result.Coerced(l)  => l.render
    case CAbi.Result.Sret(_, _)  => "void"

  private def foreignParamTypes(p: Type): List[String] = CAbi.param(p, target) match
    case CAbi.Param.Plain                     => List(CAbi.Arg(p.lty, CAbi.extension(p, target)).declared)
    case CAbi.Param.Coerced(pieces)           => pieces.map(_.declared)
    case CAbi.Param.Indirect(ty, align, true) => List(s"ptr ${byvalAttr(ty, align)}")
    case CAbi.Param.Indirect(_, _, false)     => List("ptr")

  /** The whole function type of a variadic foreign callee, which a call has to name because the
   * argument list alone does not say where the declared parameters stop and the ellipsis begins.
   */
  protected def foreignFnType(retTy: Type, params: List[Type]): String = {
    val (ret, ps) = foreignSignature(retTy, params, variadic = true)

    s"$ret (${ps.mkString(", ")})"
  }

  /** Lowers a call to a foreign function. `callee` is what the `call` names after its type — the
   * symbol, with the whole function type in front of it when the callee is variadic.
   */
  protected def genForeignCall(callee: String, args: List[TExpr], ty: Type): String = {
    val result = CAbi.result(ty, target)

    // The storage a big result is written into is the caller's, so it exists before the call, and it
    // is named in front of every argument — which is how the callee finds it whatever else the
    // signature holds.
    val returned = result match
      case CAbi.Result.Sret(ty, align) =>
        val slot = emitAlloca(freshTemp(), ty)

        Some((slot, ir.Arg(LType.Ptr, Val.Raw(slot), sretAttr(ty, align))))
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
            List(ir.Arg(a.ty.lty, Val.Raw(v), CAbi.extension(a.ty, target)))
          case CAbi.Param.Coerced(pieces) => spread(v, a.ty, pieces)
          case CAbi.Param.Indirect(ty, align, byval) =>
            val slot = emitAlloca(freshTemp(), ty)

            emit(Inst.Store(ty, Val.Raw(v), Val.Raw(slot), Access.Plain))
            List(ir.Arg(LType.Ptr, Val.Raw(slot), if byval then byvalAttr(ty, align) else ""))
    }

    val arguments = returned.map(_._2).toList ::: passed

    result match
      case CAbi.Result.Sret(coerced, _) =>
        emitForeign(None, callee, arguments)

        val r = freshReg()

        emit(Inst.Load(r, coerced, Val.Raw(returned.get._1), Access.Plain))
        ownTemp(r.render, ty)

      case CAbi.Result.Coerced(coerced) =>
        val r = freshReg()

        emitForeign(Some(r), callee, arguments)
        // A homogeneous floating aggregate comes back under its own type, so there is nothing to
        // reinterpret and the round trip through memory would be a copy for its own sake.
        ownTemp(if coerced == ty.lty then r.render else gather(r.render, coerced, ty), ty)

      case CAbi.Result.Plain =>
        if Type.noValue(ty) then
          emitForeign(None, callee, arguments)
          if ty == Type.Never then emitTerm(Inst.Unreachable)
          ""
        else
          val r = freshReg()

          emitForeign(Some(r), callee, arguments)
          ownTemp(r.render, ty)
  }

  /** The `sret` and `byval` attributes, which name the aggregate the storage holds as well as the
   * boundary it sits on — so both are written once here rather than at the four places that state
   * one on a declaration and at the call.
   */
  private def sretAttr(ty: LType, align: Int): String  = s"sret(${ty.render}) align $align"
  private def byvalAttr(ty: LType, align: Int): String = s"byval(${ty.render}) align $align"

  /** A call whose callee is already written down — the symbol with what it returns in front of it,
   * or a variadic callee's whole function type. Splitting the two apart is `CallEmitter.calleeParts`
   * and is a change to the foreign signature machinery rather than to this call.
   */
  private def emitForeign(dest: Option[Val], callee: String, args: List[ir.Arg]): Unit =
    emit(Inst.Call(dest, "", Val.Raw(callee), args))

  /** A value of `t`, spread into the registers the convention hands it over in. Several registers
   * are read back out of a literal struct of them, which lays each piece out at the offset of the
   * eightbyte it stands for.
   */
  private def spread(v: String, t: Type, pieces: List[CAbi.Arg]): List[ir.Arg] = {
    val holder = if pieces.length == 1 then pieces.head.ty else LType.Struct(pieces.map(_.ty))
    val slot   = reinterpret(v, t.lty, holder, layout.size(t))

    if pieces.length == 1 then
      val r = freshReg()

      emit(Inst.Load(r, holder, Val.Raw(slot), Access.Plain))
      List(ir.Arg(pieces.head.ty, r, pieces.head.attr))
    else
      pieces.zipWithIndex.map { (p, i) =>
        val at = freshReg()
        val r  = freshReg()

        emit(Inst.Gep(at, holder, Val.Raw(slot),
                      List(ir.Arg(LType.I(32), Val.Int(0)), ir.Arg(LType.I(32), Val.Int(i)))))
        emit(Inst.Load(r, p.ty, at, Access.Plain))
        ir.Arg(p.ty, r, p.attr)
      }
  }

  /** A value that arrived in the registers `llvm` names, read back as the `t` it stands for. */
  private def gather(v: String, coerced: LType, t: Type): String = {
    val slot = reinterpret(v, coerced, t.lty, layout.size(t))
    val r    = freshReg()

    emit(Inst.Load(r, t.lty, Val.Raw(slot), Access.Plain))
    r.render
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
  private def reinterpret(v: String, from: LType, to: LType, bytes: Int): String = {
    val src = emitAlloca(freshTemp(), from)
    val dst = emitAlloca(freshTemp(), to)

    emit(Inst.Store(from, Val.Raw(v), Val.Raw(src), Access.Plain))
    emitMemcpy(Val.Raw(dst), Val.Raw(src), bytes, 1)
    dst
  }
}
