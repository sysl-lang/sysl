package sh.sysl

import ir.{Access, Arg, Inst, LType, Val}

/** The C-callable entry an `@export` publishes (`15 §12`).
 *
 * **`@export` says the function is callable from C, and a rename is not that.** Until 0137 it was
 * one: the definition took the exported symbol and kept sysl's own parameter lowering, so a C caller
 * found the symbol, passed its arguments where its own convention says, and the body read them where
 * sysl's says. For a scalar those are the same register and the two agreed by luck; for an aggregate
 * they are not, and what arrived was whatever had been in the registers sysl looked at — a silent
 * wrong answer at the one boundary this compiler cannot check, since the other side of it was
 * compiled by somebody else.
 *
 * So the symbol is a **thunk**: a function of its own, with the signature `CAbi` says C uses, which
 * reassembles each parameter into the shape sysl's own lowering expects and calls the definition
 * normally. The definition keeps its name, its lowering and its sysl callers, and nothing about a
 * sysl-to-sysl call changes.
 *
 * **It is `ForeignEmitter` read backwards, and that is the point of writing it this way.** A call
 * *to* C classifies each argument with `CAbi.param` and spreads it into the registers the convention
 * names; this classifies each parameter the same way and gathers it back. One classifier answers
 * both directions, so the two cannot drift — which is what would have happened had the entry been
 * given a lowering of its own.
 *
 * **Why a thunk rather than reshaping the definition.** Emitting the definition itself with a C
 * signature puts a second parameter lowering inside `Codegen.genFunction`, which every sysl function
 * in the program goes through, to serve the handful that are exported. `Codegen` already keeps
 * sysl's own `declare`s away from `foreignSignature` for that reason. The extra call costs nothing
 * anybody can measure — it is a static call to a known symbol in the same module, which every
 * optimization level inlines — and it buys the one thing the reshaping does not: an address. `&f` on
 * an exported function is the thunk's, and the thunk genuinely has the convention that address is
 * being handed over as (`FuncAddress`).
 *
 * **Ownership does not arise here, and that is `ExportCheck.crosses` doing it rather than luck.**
 * That rule recurs into an aggregate's fields, so every type reaching this boundary is plain data:
 * no `&T`, no slice, no trait object, nothing with a count. The thunk therefore takes no reference
 * and releases none, and a parameter is bytes to be put where the callee will look for them.
 */
trait ExportThunk extends ForeignEmitter {

  /** The symbol a sysl definition is emitted under, given that its exported name now belongs to the
   * thunk in front of it. Every exported function keeps its own mangled key, which is what a sysl
   * caller resolves and what the thunk itself calls.
   */
  protected def genExportThunk(f: TFunc, symbol: String): String = {
    startFunction()

    val stored          = Type.stored(f.params)
    val (result, cArgs) = foreignSignature(f.retTy, stored.map(_._2), variadic = false)

    // The out-pointer is in front of every declared parameter, so the names run one behind wherever
    // there is one — which is the same offset `foreignSignature` put it at.
    val sret     = CAbi.result(f.retTy, target) match
      case CAbi.Result.Sret(_, _) => Some(Val.Reg("c.sret"))
      case _                      => None
    val incoming = names(stored.map(_._2))
    val declared = sret.toList.zip(cArgs).map((n, t) => s"$t $n") ++
      cArgs.drop(sret.size).zip(incoming.flatten).map((t, n) => s"$t $n")

    val passed = stored.zip(incoming).map((p, ns) => argument(p._2, ns))

    genThunkCall(f, symbol, passed, sret)
    finishFunction(s"define $result @${f.exported.get}(${declared.mkString(", ")})")
  }

  /** The LLVM name of each incoming parameter, grouped by the sysl parameter it belongs to — one
   * name for most, several where the convention hands an aggregate over in more than one register.
   */
  private def names(params: List[Type]): List[List[Val]] =
    params.zipWithIndex.map { (p, i) =>
      CAbi.param(p, target) match
        case CAbi.Param.Coerced(pieces) if pieces.length > 1 =>
          pieces.indices.toList.map(j => Val.Reg(s"c.$i.$j"))
        case _ => List(Val.Reg(s"c.$i"))
    }

  /** One parameter, converted from the registers C put it in to what a sysl call passes.
   *
   * The two lowerings agree about a scalar and disagree about everything else, so the conversion is
   * in two steps rather than one: get the value into storage as the sysl type, then hand the callee
   * either that storage or the value in it, whichever its own convention asks for. Going through
   * memory is what `ForeignEmitter` does in the other direction and for the same reason — LLVM has
   * no instruction that reinterprets one aggregate as another, and the two shapes deliberately do
   * not agree field for field.
   */
  private def argument(t: Type, incoming: List[Val]): Arg =
    CAbi.param(t, target) match
      // A scalar sysl also passes as itself: there is nothing to convert and no slot to make.
      case CAbi.Param.Plain if !layout.indirect(t) => Arg(t.lty, incoming.head)
      case CAbi.Param.Plain                        => Arg(LType.Ptr, slotOf(t, incoming.head))

      // Already in storage, and it is storage this function may hand on: `byval` is C's promise that
      // the copy is the callee's, and an indirect parameter without it is one the caller made for
      // this call. Sysl's own indirect lowering copies at entry, exactly as the definition's
      // prologue does for any other caller, so nothing here has to copy first.
      case CAbi.Param.Indirect(coerced, _, _) =>
        if layout.indirect(t) then Arg(LType.Ptr, incoming.head)
        else
          val v = freshReg()

          emit(Inst.Load(v, coerced, incoming.head, Access.Plain))
          Arg(t.lty, v)

      case CAbi.Param.Coerced(pieces) =>
        val holder = if pieces.length == 1 then pieces.head.ty else LType.Struct(pieces.map(_.ty))
        val from   = emitAlloca(freshReg(), holder)

        if pieces.length == 1 then
          emit(Inst.Store(holder, incoming.head, from, Access.Plain))
        else
          for (p, i) <- pieces.zipWithIndex do
            val at = freshReg()

            emit(Inst.Gep(at, holder, from,
                          List(Arg(LType.I(32), Val.Int(0)), Arg(LType.I(32), Val.Int(i)))))
            emit(Inst.Store(p.ty, incoming(i), at, Access.Plain))

        val slot = emitAlloca(freshReg(), t.lty)

        // The sysl type's own size in both directions, which is the only length both shapes are
        // known to have: a coerced form is never narrower, and where it is wider the surplus is what
        // the convention leaves unspecified. Byte alignment is claimed for the same reason
        // `ForeignEmitter.reinterpret` claims it — a guarantee is a floor, and LLVM refines it from
        // the `alloca` right above.
        emitMemcpy(slot, from, layout.size(t), 1)

        if layout.indirect(t) then Arg(LType.Ptr, slot)
        else
          val v = freshReg()

          emit(Inst.Load(v, t.lty, slot, Access.Plain))
          Arg(t.lty, v)

  /** Storage holding an incoming scalar, for the case where sysl passes that type indirectly and C
   * hands it over in a register — the two thresholds are different questions and may disagree.
   */
  private def slotOf(t: Type, value: Val): Val = {
    val slot = emitAlloca(freshReg(), t.lty)

    emit(Inst.Store(t.lty, value, slot, Access.Plain))
    slot
  }

  /** The call to the definition, and the `ret` that hands its result back the way C reads one. */
  private def genThunkCall(f: TFunc, symbol: String, passed: List[Arg], sret: Option[Val]): Unit = {
    // Where **both** sides return through storage the caller supplies, C's is handed straight to
    // sysl and the value is never a first-class LLVM value at any point — which is the whole of what
    // an out-parameter buys, and copying it into a slot of this function's own to copy it back out
    // would give that up for nothing.
    val direct   = sret.filter(_ => layout.indirect(f.retTy))
    val syslSlot =
      if !layout.indirect(f.retTy) then None
      else direct.orElse(Some(emitAlloca(freshReg(), f.retTy.lty)))

    val out = syslSlot.map(s =>
      Arg(LType.Ptr, s, s"noalias sret(${f.retTy.llvm}) align ${layout.align(f.retTy)}"))
    val args = out.toList ::: passed

    def call(dest: Option[Val]): Unit =
      emit(Inst.Call(dest, syslResult(f.retTy), Val.Global(symbol), args))

    // A `never` result diverges, so the call does not come back and there is nothing after it. The
    // `unreachable` is what says so to LLVM, exactly as a foreign call to one does.
    if f.retTy == Type.Never then
      call(None)
      emitTerm(Inst.Unreachable)
    else
      val returned = Option.when(!Type.noValue(f.retTy) && syslSlot.isEmpty) {
        val v = freshReg()

        call(Some(v))
        v
      }

      if returned.isEmpty then call(None)

      genThunkReturn(f.retTy, returned, syslSlot, sret)
  }

  /** The `ret`, in the shape `CAbi` says a C caller reads the result out of.
   *
   * A return attribute belongs on the signature rather than on the terminator — `foreignSignature`
   * has already written it — so what is named here is the bare type, which is the same rule
   * `Emitter.syslResultType` states for a sysl definition.
   */
  private def genThunkReturn(retTy: Type, returned: Option[Val], syslSlot: Option[Val],
                             sret: Option[Val]): Unit =
    CAbi.result(retTy, target) match
      case CAbi.Result.Sret(coerced, align) =>
        // Where sysl returned into this very slot there is nothing left to do. Otherwise sysl handed
        // a value back and C wants it in storage, so it is stored where C said.
        for v <- returned do
          emit(Inst.Store(coerced, v, sret.get, Access.Plain))

        for s <- syslSlot if !sret.contains(s) do
          emitMemcpy(sret.get, s, layout.size(retTy), align)

        emitTerm(Inst.Ret(None, None))

      case CAbi.Result.Coerced(coerced) =>
        val from = syslSlot.getOrElse(slotOf(retTy, returned.get))
        val slot = emitAlloca(freshReg(), coerced)
        val v    = freshReg()

        emitMemcpy(slot, from, layout.size(retTy), 1)
        emit(Inst.Load(v, coerced, slot, Access.Plain))
        emitTerm(Inst.Ret(Some(coerced), Some(v)))

      case CAbi.Result.Plain =>
        if Type.noValue(retTy) then emitTerm(Inst.Ret(None, None))
        else
          // Sysl may have returned a small aggregate through storage where C reads it from a
          // register, so the value is loaded back rather than assumed to be in hand.
          val v = returned.getOrElse {
            val r = freshReg()

            emit(Inst.Load(r, retTy.lty, syslSlot.get, Access.Plain))
            r
          }

          emitTerm(Inst.Ret(Some(retTy.lty), Some(v)))
}
