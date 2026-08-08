package sh.sysl

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
      case CAbi.Result.Sret(llvm, align) => List(s"ptr sret($llvm) align $align")
      case _                             => Nil

    val rest = params.filterNot(Type.zeroSized).flatMap(p => foreignParamTypes(p))

    (foreignResultType(retTy), sret ::: rest ::: Option.when(variadic)("...").toList)
  }

  protected def foreignResultType(retTy: Type): String = CAbi.result(retTy, target) match
    case CAbi.Result.Plain       => retTy.llvm
    case CAbi.Result.Coerced(l)  => l
    case CAbi.Result.Sret(_, _)  => "void"

  private def foreignParamTypes(p: Type): List[String] = CAbi.param(p, target) match
    case CAbi.Param.Plain                       => List(p.llvm)
    case CAbi.Param.Coerced(pieces)             => pieces.map(_.declared)
    case CAbi.Param.Indirect(llvm, align, true) => List(s"ptr byval($llvm) align $align")
    case CAbi.Param.Indirect(_, _, false)       => List("ptr")

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
      case CAbi.Result.Sret(llvm, align) =>
        val slot = emitAlloca(freshTemp(), llvm)

        Some((slot, s"ptr sret($llvm) align $align $slot"))
      case _ => None

    // An argument past the declared parameters is a variadic extra, and C classifies one exactly as
    // it classifies a declared parameter — so both take the same path, each at its own type.
    val passed = args.flatMap { a =>
      val v = genExpr(a)

      if Type.zeroSized(a.ty) then Nil
      else
        CAbi.param(a.ty, target) match
          case CAbi.Param.Plain           => List(s"${a.ty.llvm} $v")
          case CAbi.Param.Coerced(pieces) => spread(v, a.ty, pieces)
          case CAbi.Param.Indirect(llvm, align, byval) =>
            val slot = emitAlloca(freshTemp(), llvm)

            emit(s"store $llvm $v, ptr $slot")
            List(if byval then s"ptr byval($llvm) align $align $slot" else s"ptr $slot")
    }

    val arguments = (returned.map(_._2).toList ::: passed).mkString(", ")

    result match
      case CAbi.Result.Sret(llvm, _) =>
        emit(s"call $callee($arguments)")

        val r = freshTemp()

        emit(s"$r = load $llvm, ptr ${returned.get._1}")
        ownTemp(r, ty)

      case CAbi.Result.Coerced(llvm) =>
        val r = freshTemp()

        emit(s"$r = call $callee($arguments)")
        // A homogeneous floating aggregate comes back under its own type, so there is nothing to
        // reinterpret and the round trip through memory would be a copy for its own sake.
        ownTemp(if llvm == ty.llvm then r else gather(r, llvm, ty), ty)

      case CAbi.Result.Plain =>
        if Type.noValue(ty) then
          emit(s"call $callee($arguments)")
          if ty == Type.Never then emitTerm("unreachable")
          ""
        else
          val r = freshTemp()

          emit(s"$r = call $callee($arguments)")
          ownTemp(r, ty)
  }

  /** A value of `t`, spread into the registers the convention hands it over in. Several registers
   * are read back out of a literal struct of them, which lays each piece out at the offset of the
   * eightbyte it stands for.
   */
  private def spread(v: String, t: Type, pieces: List[CAbi.Arg]): List[String] = {
    val holder = if pieces.length == 1 then pieces.head.llvm else s"{ ${pieces.map(_.llvm).mkString(", ")} }"
    val slot   = reinterpret(v, t.llvm, holder, layout.size(t))

    if pieces.length == 1 then
      val r = freshTemp()

      emit(s"$r = load $holder, ptr $slot")
      List(s"${pieces.head.declared} $r")
    else
      pieces.zipWithIndex.map { (p, i) =>
        val at = freshTemp()
        val r  = freshTemp()

        emit(s"$at = getelementptr $holder, ptr $slot, i32 0, i32 $i")
        emit(s"$r = load ${p.llvm}, ptr $at")
        s"${p.declared} $r"
      }
  }

  /** A value that arrived in the registers `llvm` names, read back as the `t` it stands for. */
  private def gather(v: String, llvm: String, t: Type): String = {
    val slot = reinterpret(v, llvm, t.llvm, layout.size(t))
    val r    = freshTemp()

    emit(s"$r = load ${t.llvm}, ptr $slot")
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
  private def reinterpret(v: String, from: String, to: String, bytes: Int): String = {
    val src = emitAlloca(freshTemp(), from)
    val dst = emitAlloca(freshTemp(), to)

    usesMemcpy = true
    emit(s"store $from $v, ptr $src")
    emit(s"call void @llvm.memcpy.p0.p0.i64(ptr align 1 $dst, ptr align 1 $src, i64 $bytes, i1 false)")
    dst
  }
}
