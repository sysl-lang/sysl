package sh.sysl

/** The call seam, and writing a value where it is going to live.
 *
 * What a `call` names is not simply the callee's sysl name. An `extern` may have been given a link
 * name, the program's own `main` is renamed out of the way of the entry point the platform starts,
 * a variadic callee needs its whole function type rather than just its result, and a foreign one is
 * lowered under the convention the other side was compiled against rather than sysl's own
 * (`ForeignEmitter`). Four questions, one answer each, asked once here instead of at every call
 * site.
 *
 * The into-writers sit with them because the two are one mechanism rather than two. A **large**
 * aggregate (`layout.indirect`) is built, copied and returned through memory, so `genOwnedInto` and
 * `genBorrowedInto` take the address a value is wanted at and write it there instead of handing
 * back a register the caller then stores — the difference between a struct literal that is fourteen
 * `insertvalue` instructions over multi-kilobyte SSA values and one that is fourteen stores. A call
 * is where that meets the seam from both directions: a large *argument* is staged into a slot with
 * `genOwnedInto`, and a large *result* lands in storage named in front of the argument list. They
 * call each other, so they are one trait.
 *
 * Everything smaller goes on being a value. The into-writers still accept one — a field of a large
 * struct is usually a small one, and the recursion has to bottom out somewhere — and for those they
 * emit exactly what the caller would have emitted itself.
 */
trait CallEmitter extends ControlFlowEmitter with VtableEmitter with WriterEmitter with StaticEmitter
    with ForeignEmitter with ContractEmitter with EnumAttrEmitter {
  /** The typed program being lowered, which the seam below reads to learn what a name resolves to. */
  protected val program: TProgram

  /** The arguments of a call, as an LLVM argument list.
   *
   * Every one is evaluated, in the order it was written, because the *effect* of an argument is
   * owed whatever its type — but a zero-sized one is then not passed, since the callee has no
   * parameter to receive it. That keeps the two sides of the call agreeing with `genFunction`,
   * which drops the same parameters from the signature.
   */
  protected def argList(args: List[TExpr]): List[String] = formatArgs(args.map(argValue))

  /** One argument, evaluated, in the form the callee receives it — or `None` where it is zero-sized
   * and there is nothing to hand over.
   *
   * A large one is handed over as the address of storage the caller holds — its own where the
   * argument is a place, a slot made here where it is not — which is the `Left`. The callee copies at
   * entry either way, so the copy the by-value convention promises still happens; it just happens
   * once, in memory, instead of as a multi-kilobyte value crossing the call.
   *
   * The value is kept beside its type rather than formatted straight away because a self-call needs
   * the values themselves: `17 §4`'s measure is evaluated over the arguments, and reaching them by
   * evaluating the arguments a second time would run whatever they do twice.
   */
  protected def argValue(a: TExpr): Option[(Type, Either[String, String])] =
    if layout.indirect(a.ty) then Some((a.ty, Left(address(a))))
    else
      val v = genExpr(a)

      Option.unless(Type.zeroSized(a.ty))((a.ty, Right(v)))

  protected def formatArgs(vals: List[Option[(Type, Either[String, String])]]): List[String] =
    vals.flatten.map {
      case (_, Left(addr)) => s"ptr $addr"
      case (ty, Right(v))  => s"${ty.llvm} $v"
    }

  /** A call the function makes to itself as the last thing it does, lowered as a jump back to its
   * own entry rather than a second frame (`TailCalls`).
   *
   * **The order is the whole of the correctness, and it is the order a `return` already uses.** Each
   * argument is computed while the frame is still whole — it may well read the very slots about to
   * be overwritten — and a count is taken for it there, so what it names cannot reach zero when the
   * old bindings let go. Only then does the frame give up everything it holds, and only then do the
   * new values land. Written the other way round, `go(next, list)` would free the list at the moment
   * the parameter holding it was reassigned and pass the second argument a dangling reference.
   *
   * The jump is a `br` and the block ends there, which is all any caller needs to know: this returns
   * no register because there is no value — the call does not come back, and everything the emitters
   * would have gone on to lay down is dropped by `emit`, exactly as it is after a `-> never` call.
   */
  protected def genTailSelfCall(args: List[TExpr]): String = {
    // A large argument is staged in a slot of its own rather than a register, because that is how a
    // large value moves at all here. The staging slot is what makes it safe as well: `address` on an
    // argument that is simply a parameter hands back that parameter's own slot, so writing straight
    // through would have the first argument's landing change what the second one reads.
    val staged =
      tailParams.zip(args).map { case ((_, ty), a) =>
        if Type.zeroSized(ty) then { genExpr(a); None }
        else if layout.indirect(ty) then
          val slot = emitAlloca(freshTemp(), ty.llvm)

          genOwnedInto(slot, a)
          Some(Left(slot))
        else
          val v = genExpr(a)

          retainValue(ty, v)
          Some(Right(v))
      }

    // The measure is checked while the frame is still whole, since it reads the parameters and the
    // jump below is about to overwrite them. A tail call survives this where an `ensure` does not
    // (`16 §7`): the check happens *before* the call, and a tail call's problem is that it never
    // returns.
    if checksVariant(selfName) then
      genVariantAtCall(tailParams.zip(staged).map {
        case ((_, ty), Some(Left(slot))) => Some((ty, Left(slot)))
        case ((_, ty), Some(Right(v)))   => Some((ty, Right(v)))
        case _                           => None
      })

    releaseAll()

    for case ((name, ty), Some(s)) <- tailParams.zip(staged) do
      s match
        // The count came with the bytes and stays with them: what the staging slot took is now the
        // parameter's, so nothing is retained here and nothing released.
        case Left(slot) =>
          usesMemcpy = true
          emit(s"call void @llvm.memcpy.p0.p0.i64(ptr align ${layout.align(ty)} %$name.addr, " +
            s"ptr align ${layout.align(ty)} $slot, i64 ${layout.size(ty)}, i1 false)")

        case Right(v) => emit(s"store ${ty.llvm} $v, ptr %$name.addr")

    emitTerm(s"br label %${tailTarget.get}")
    ""
  }

  /** Every callee declared with a `...`, foreign or sysl's own, mapped to the LLVM function type a
   * call to it must name: result type, declared parameter types, ellipsis.
   */
  private val variadics: Map[String, String] =
    val fromExterns = program.externs.filter(_.variadic).map(e => e.name -> foreignFnType(e.retTy, e.params))
    val fromFuncs   = program.funcs.filter(_.variadic).map { f =>
      val params = syslSret(f.retTy).toList ++ Type.stored(f.params).map(p => syslParam(p._2)) :+ "..."

      f.name -> s"${syslResult(f.retTy)} (${params.mkString(", ")})"
    }

    (fromExterns ++ fromFuncs).toMap

  /** The `extern`s a call may resolve to, so a foreign call is lowered by what the other side's
   * convention asks for rather than by what sysl's own would be (`ForeignEmitter`).
   */
  protected val foreigns: Map[String, TExtern] = program.externs.map(e => e.name -> e).toMap

  /** The symbol a called name resolves to, which differs from the name for an `extern` given a link
   * name and for the program's own `main`. Everything else is emitted under its own name.
   *
   * `main` is renamed because the emitted entry point *is* `@main`: the platform starts the program
   * there, and a sysl function of that name would be a second definition of one symbol. The reserved
   * name it takes instead holds two separators, which no key can (`Modules.qualify` writes one), so it
   * cannot collide with anything a program or a module could be called.
   *
   * **`@export` is not in here, and used to be.** It was written as the same substitution an
   * `extern`'s link name is, pointing the other way — which made the exported symbol a *rename* of
   * the definition, so a C caller reached a body lowered by sysl's convention rather than by its own
   * (`ExportThunk`). The exported name now belongs to the thunk in front of the definition, and the
   * definition keeps its mangled key, which is what a sysl caller resolves and what the thunk calls.
   */
  protected val entrySymbol = s"${Modules.sep}${Modules.sep}main"

  /** The exported symbol of each function that has one, which is the **thunk's** rather than the
   * definition's (`ExportThunk`).
   */
  private val exportSymbols: Map[String, String] =
    program.funcs.collect { case f if f.exported.isDefined => f.name -> f.exported.get }.toMap

  /** **A symbol C has claimed is not available to a sysl definition.**
   *
   * A key is normally `module$name`, which no exported symbol can be — `ExportCheck.cIdentifier`
   * holds one to letters, digits and `_`, and `Modules.sep` is none of those. A function in the
   * **root** module has no such qualification, so its key *is* the bare name, and an export under
   * its own name there claims the very symbol the definition would be emitted under. What that
   * produces is two definitions of one symbol, reported by clang as an invalid redefinition of a
   * function nobody wrote twice.
   *
   * **Renaming the definition rather than the thunk keeps the promise the right way round**: the
   * exported symbol is the one somebody outside has written down, and a mangled key is this
   * compiler's own business. Which of the two moves is the whole of the decision here.
   *
   * A function that is not itself exported is covered too, since a `@export("add")` elsewhere in the
   * program claims `add` whoever else wanted it.
   */
  private val displaced: Set[String] = exportSymbols.values.toSet

  private val symbols: Map[String, String] =
    program.externs.collect { case e if e.symbol != e.name => e.name -> e.symbol }.toMap ++
      program.funcs.collect {
        case f if displaced(f.name) => f.name -> s"${f.name}${Modules.sep}${Modules.sep}sysl"
      }.toMap ++
      program.entry.map(_.func -> entrySymbol)

  /** What a definition and every call to it name. */
  protected def symbolOf(name: String): String = symbols.getOrElse(name, name)

  /** The symbol an **address** of this function names — the one entry point that is callable under
   * the machine's C convention, since that is the only thing a `*extern` may hold (`12 §6a`).
   *
   * For an ordinary sysl function the two are the same and this is `symbolOf`: its signature is all
   * scalars, or the address would have been refused (`FuncAddress`), and a scalar crosses as itself.
   * For an exported one they are not — the definition keeps sysl's lowering and the thunk in front
   * of it is what C can call — so the address is the thunk's, which is what makes `&f` on an
   * exported function mean anything.
   */
  protected def entryOf(name: String): String = exportSymbols.getOrElse(name, symbolOf(name))

  /** What a `call` names. For an ordinary function that is the result type, which is all LLVM
   * needs; for a variadic one it is the callee's *whole* function type, because the argument list
   * alone does not say where the declared parameters stop and the ellipsis begins.
   */
  protected def calleeOf(name: String, ty: Type): String =
    val (what, callee) = calleeParts(name, ty)

    s"$what ${callee.render}"

  /** The same two things kept apart, which is how a `call` instruction carries them: what the call
   * names, and the symbol it names. `calleeOf` is this pair run together, and stays for as long as
   * anything still builds a call by interpolation.
   */
  protected def calleeParts(name: String, ty: Type): (String, ir.Val.Global) =
    val symbol = symbolOf(name)
    // A foreign result may be named by a type the sysl signature never mentions — a coerced
    // aggregate, or `void` where the value comes back through an out-parameter. A sysl result may
    // be `void` for the second of those reasons alone.
    val result = if foreigns.contains(name) then foreignResultType(ty) else syslResult(ty)

    (variadics.getOrElse(name, result), ir.Val.Global(symbol))

  /** Emits a call from sysl to sysl and hands back the register holding its result.
   *
   * `dest`, where there is one, is the storage a **large** result is to land in — the caller's own,
   * named in front of every argument, so the value is never an LLVM value at either end. A caller
   * with nowhere to put one makes a slot here and reads the value back out of it, which is correct
   * and is exactly the shape `genInto` exists to save the callers that *do* have somewhere.
   */
  protected def genSyslCall(callee: String, argVals: List[String], ty: Type, dest: Option[String]): String =
    syslSret(ty) match
      case Some(_) =>
        val slot = dest.getOrElse(emitAlloca(freshTemp(), ty.llvm))

        emit(s"call $callee(${(s"ptr sret(${ty.llvm}) align ${layout.align(ty)} $slot" :: argVals).mkString(", ")})")

        if dest.isDefined then ""
        else
          val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot")
          ownTemp(r, ty)

      case None if Type.noValue(ty) =>
        emit(s"call $callee(${argVals.mkString(", ")})")
        if ty == Type.Never then emitTerm("unreachable")
        ""

      case None =>
        val r = freshTemp(); emit(s"$r = call $callee(${argVals.mkString(", ")})")
        ownTemp(r, ty)

  // --- writing a value where it is going to live ----------------------------------------

  /** Writes `e`'s value into `dest` and leaves the destination **owning** it: a count taken for
   * every reference inside, which is what a slot that will later release them needs.
   */
  protected def genOwnedInto(dest: String, e: TExpr): Unit =
    if !layout.indirect(e.ty) then
      val v = genExpr(e)

      retainValue(e.ty, v)
      emit(s"store ${e.ty.llvm} $v, ptr $dest")
    else
      e match
        // A tail self-call needs no destination: the jump keeps the frame, so the `sret` pointer the
        // caller handed in is still the one the return that eventually happens will write through.
        // `dest` here *is* that pointer, and passing it on would be writing the result of a call that
        // is not being made.
        case c: TCall if isTailCall(c) => genTailSelfCall(c.args)

        // A result already arrives with its count taken, and the out-pointer is what says where.
        // This is the case the whole mechanism is for: `val k = kernel()` writes the callee's work
        // straight into `k`'s slot, and no `%struct.Kernel` is ever an LLVM value.
        case TCall(name, args, ty, _) if !foreigns.contains(name) =>
          val staged = args.map(argValue)

          if checksVariant(name) then genVariantAtCall(staged)
          genSyslCall(calleeOf(name, ty), formatArgs(staged), ty, Some(dest))

        case _ =>
          genBorrowedInto(dest, e)
          retainAt(e.ty, dest)

  /** Writes `e`'s value into `dest` without taking a count for anything in it — what lands there is
   * borrowed, exactly as the register `genExpr` hands back is.
   *
   * A **call** is deliberately not special-cased here. Its result arrives owned, and giving it this
   * destination would leave a count in a place with nothing registered to release it; sending it
   * through `genExpr` instead costs the whole-value load this file exists to avoid, but only where
   * a large result is nested inside a literal that is itself being built in place, which is rare
   * and is what the code did before any of this.
   */
  protected def genBorrowedInto(dest: String, e: TExpr): Unit = e match
    // A bitfield struct has one slot however many fields were written, so there is nothing to build
    // field by field: the container is assembled as a value and stored once (`Bitfields`).
    case TStructNew(struct, args) if Bitfields.of(struct).isDefined =>
      val ranges = Bitfields.of(struct).get
      val vals   = args.zipWithIndex.map((a, i) => (genExpr(a), struct.fields(i)._2))
      val c      = buildBits(ranges, vals.collect { case (v, ft) if !Type.zeroSized(ft) => v })

      emit(s"store ${containerLlvm(ranges)} $c, ptr $dest")

    case TStructNew(struct, args) =>
      for (a, i) <- args.zipWithIndex if !Type.zeroSized(struct.fields(i)._2) do
        val p = freshTemp()

        emit(s"$p = getelementptr ${struct.llvm}, ptr $dest, i32 0, i32 ${struct.slot(i)}")
        genBorrowedInto(p, a)

    case TArrayLit(elems, arrayTy) =>
      for (el, i) <- elems.zipWithIndex do
        val p = freshTemp()

        emit(s"$p = getelementptr ${arrayTy.elem.llvm}, ptr $dest, $word $i")
        genBorrowedInto(p, el)

    // The tag, and then the variant's own fields written into the region every variant shares.
    // Reaching the region by address is what the value form has to use a stack slot for anyway —
    // a union has no `insertvalue` — so this is the shorter of the two lowerings as well.
    case TEnumNew(en, variant, args) if !en.simple =>
      emit(s"store i32 ${variant.tag}, ptr $dest")

      if variant.carries then
        val base = payloadPtr(en, dest)

        for (a, i) <- args.zipWithIndex if !Type.zeroSized(variant.fields(i)._2) do
          val p = freshTemp()

          emit(s"$p = getelementptr ${en.payloadLlvm(variant)}, ptr $base, i32 0, i32 ${variant.slot(i)}")
          genBorrowedInto(p, a)

    // The value is generated once, above the loop, exactly as the value form does — every element
    // is a copy of that one evaluation. It is generated even where there are no elements, because
    // one evaluation is what the form promises and an empty array does not take that back.
    case TArrayFill(value, arrayTy) =>
      val v = genExpr(value)

      if arrayTy.length > 0 then
        fillLoop(dest, arrayTy) { at => emit(s"store ${arrayTy.elem.llvm} $v, ptr $at") }

    // A copy from one place to another is a copy of bytes. Reading the value out first would make
    // a first-class aggregate of it for the length of one instruction, which is the whole cost.
    case place if hasAddress(place) =>
      val src = address(place)

      usesMemcpy = true
      emit(s"call void @llvm.memcpy.p0.p0.i64(ptr align ${layout.align(e.ty)} $dest, " +
        s"ptr align ${layout.align(e.ty)} $src, i64 ${layout.size(e.ty)}, i1 false)")

    case _ =>
      val v = genExpr(e)

      emit(s"store ${e.ty.llvm} $v, ptr $dest")

  /** `e` built where a value of it is wanted, for a caller that asked for a register rather than
   * offering somewhere to put one. The load is the very thing the destination forms avoid, so this
   * is the fallback and not the path anything hot takes.
   */
  protected def throughSlot(e: TExpr): String = {
    val slot = emitAlloca(freshTemp(), e.ty.llvm)

    genBorrowedInto(slot, e)

    val r = freshTemp(); emit(s"$r = load ${e.ty.llvm}, ptr $slot"); r
  }

  /** Runs `each` once per element of an array laid down at `base`, with the element's address. */
  private def fillLoop(base: String, arrayTy: Type.Array)(each: String => Unit): Unit = {
    val i     = emitAlloca(freshTemp(), word)
    val condL = freshLabel("fill.test")
    val bodyL = freshLabel("fill.elem")
    val endL  = freshLabel("fill.done")

    emit(s"store $word 0, ptr $i")
    emitTerm(s"br label %$condL")
    emitLabel(condL)
    val iv   = freshTemp(); emit(s"$iv = load $word, ptr $i")
    val more = freshTemp(); emit(s"$more = icmp ult $word $iv, ${arrayTy.length}")
    emitTerm(s"br i1 $more, label %$bodyL, label %$endL")
    emitLabel(bodyL)
    val ep = freshTemp(); emit(s"$ep = getelementptr ${arrayTy.elem.llvm}, ptr $base, $word $iv")
    each(ep)
    val nxt = freshTemp(); emit(s"$nxt = add $word $iv, 1")
    emit(s"store $word $nxt, ptr $i")
    emitTerm(s"br label %$condL")
    emitLabel(endL)
  }
}
