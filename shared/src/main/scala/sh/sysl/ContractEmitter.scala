package sh.sysl

/** Everything that traps when a value turns out not to be what it was promised to be: a function's
 * `require` and `ensure` clauses (`16 §5`), a constrained subtype's `within` range and `where`
 * predicate (`16 §4`), and a struct's `invariant` (`16 §6`).
 *
 * They live together because they are one mechanism wearing four names. Each one evaluates a
 * condition and traps on false, and each one has to leave nothing behind on the checked path — a
 * condition may allocate on its way to a `bool`, and the trap is not a place that releases anything.
 * So every check opens a temporary region and closes it before the branch, which is the detail that
 * would otherwise have to be right in four places.
 *
 * What a check does *not* do is decide anything. Which clauses exist, what they mean, and whether
 * they are satisfiable are all settled by the time the tree arrives; a range here is two comparisons
 * against constants the analyzer already folded, and a predicate is an ordinary call to a function it
 * already synthesised.
 */
trait ContractEmitter extends ArcEmitter with ScalarEmitter {

  /** The postconditions of the function being emitted, checked before every return, and the SSA
   * value `result` denotes while one of them is being lowered.
   *
   * They are per-function state like every other register counter, so they are cleared with the rest
   * rather than left for the next function to inherit — `@main` declares no postconditions and must
   * not be handed the last function's.
   */
  protected var ensures: List[(TExpr, Option[String])] = Nil
  protected var resultSSA: Option[String]              = None

  /** What the function being emitted is called, what its parameters are, and the `variant` it
   * declared — the three things a self-call needs to check the measure (`17 §4`).
   */
  protected var selfName: String                 = ""
  protected var selfParams: List[(String, Type)] = Nil
  protected var selfVariant: Option[TExpr]       = None

  override protected def startFunction(): Unit = {
    super.startFunction()
    ensures = Nil
    resultSSA = None
    selfName = ""
    selfParams = Nil
    selfVariant = None
  }

  /** Whether a call to `name` is the self-call a `variant` is checked at. */
  protected def checksVariant(name: String): Boolean = selfVariant.isDefined && name == selfName

  /** The measure check at a direct recursive call (`17 §4`).
   *
   * **It reads no state and threads nothing through the call**, which is what `17 §4`'s restriction
   * to the parameters buys. The arguments about to be passed are the values the parameters are about
   * to hold, so the "next" measure is this same expression evaluated with those values in the
   * parameters' own slots: take the measure as it stands, put the arguments in, take it again, put
   * the parameters back, and compare. Nothing is retained or released across the swap — the slots end
   * holding exactly what they held — so the ownership bookkeeping is untouched.
   *
   * `staged` is aligned with `selfParams` and carries what each argument came out as, which is the
   * shape both call paths already produce: an address for a large value, a register for the rest,
   * and nothing at all for a zero-sized parameter.
   */
  protected def genVariantAtCall(staged: List[Option[(Type, Either[String, String])]]): Unit = {
    val variant = selfVariant.get

    pushTemps()
    val cur = genExpr(variant)
    popTemps()

    // Every save is laid down before any argument lands, since a measure over two parameters must
    // see both of the call's values and neither of the frame's.
    val saved =
      for case ((name, ty), Some((_, arg))) <- selfParams.zip(staged) yield
        val keep = emitAlloca(freshTemp(), ty.llvm)

        arg match
          case Left(addr) =>
            memcpy(keep, s"%$name.addr", ty)
            memcpy(s"%$name.addr", addr, ty)
          case Right(v) =>
            val old = freshTemp()

            emit(s"$old = load ${ty.llvm}, ptr %$name.addr")
            emit(s"store ${ty.llvm} $old, ptr $keep")
            emit(s"store ${ty.llvm} $v, ptr %$name.addr")
        (name, ty, keep)

    pushTemps()
    val nxt = genExpr(variant)
    popTemps()

    for (name, ty, keep) <- saved do
      if layout.indirect(ty) then memcpy(s"%$name.addr", keep, ty)
      else
        val back = freshTemp(); emit(s"$back = load ${ty.llvm}, ptr $keep")
        emit(s"store ${ty.llvm} $back, ptr %$name.addr")

    val ok = freshTemp()

    emit(s"$ok = icmp ${predicate("<", variant.ty)} ${variant.ty.llvm} $nxt, $cur")
    trapUnless(ok, "variant")
  }

  private def memcpy(dst: String, src: String, ty: Type): Unit = {
    usesMemcpy = true
    emit(s"call void @llvm.memcpy.p0.p0.i64(ptr align ${layout.align(ty)} $dst, " +
      s"ptr align ${layout.align(ty)} $src, i64 ${layout.size(ty)}, i1 false)")
  }

  /** Emits a contract clause as a trap-on-false check, discarding any temporaries the condition
   * allocated before the trap so the checked path stays leak-free.
   */
  protected def emitContract(cond: TExpr, kind: String): Unit = {
    pushTemps()
    val ok = genExpr(cond)
    popTemps()
    trapUnless(ok, kind)
  }

  /** Runs every postcondition with `result` bound to the value about to be returned. */
  protected def emitEnsures(result: Option[String]): Unit =
    if ensures.nonEmpty then
      resultSSA = result
      for (cond, _) <- ensures do emitContract(cond, "ensure")
      resultSSA = None

  /** Sets up a `variant`'s two slots at the point the loop is entered (`17 §3`), then emits the loop.
   *
   * The `armed` flag starts false, which is what lets the first iteration pass with nothing to
   * compare against. It is stored **here** rather than in the function's prologue because a loop
   * nested inside another is entered many times: a flag armed once per call would compare the second
   * entry's first measure against the first entry's last, and trap on a loop that was decreasing
   * perfectly well.
   */
  protected def genCheckedLoop(slot: String, varTy: Type, loop: TExpr): String = {
    emitAlloca(s"%$slot.prev", varTy.llvm)
    emitAlloca(s"%$slot.armed", "i1")
    emit(s"store i1 0, ptr %$slot.armed")
    genExpr(loop)
  }

  /** One iteration's `variant` check: measure, compare against the last one where there was a last
   * one, and store.
   *
   * The comparison is **strict** and it is signed or unsigned according to the measure's own type,
   * which `compareValue` reads off it — a `usize` counting down to zero is as ordinary a measure as
   * an `int` is, and comparing it as signed would be wrong at exactly the values it spends its time
   * near.
   */
  protected def genVariantCheck(v: TVariantCheck): Unit = {
    val TVariantCheck(slot, varTy, expr) = v
    val w = varTy.llvm

    pushTemps()
    val cur = genExpr(expr)
    popTemps()

    val armedV = freshTemp(); emit(s"$armedV = load i1, ptr %$slot.armed")
    val cmpL   = freshLabel("variant.cmp")
    val setL   = freshLabel("variant.set")

    emitTerm(s"br i1 $armedV, label %$cmpL, label %$setL")
    emitLabel(cmpL)
    val prev = freshTemp(); emit(s"$prev = load $w, ptr %$slot.prev")
    val ok   = freshTemp(); emit(s"$ok = icmp ${predicate("<", varTy)} $w $cur, $prev")
    trapUnless(ok, "variant")
    emitTerm(s"br label %$setL")
    emitLabel(setL)
    emit(s"store $w $cur, ptr %$slot.prev")
    emit(s"store i1 1, ptr %$slot.armed")
  }

  /** Emits the `within`-range checks for a value produced into a constrained subtype: a lower- and
   * upper-bound compare, each trapping on violation. Integer and `char` bounds compare at the base
   * width (unsigned for `char` and the unsigned integers, which `compareValue` reads off the type);
   * float bounds compare in double precision, widening a narrower value so one rendering of the
   * bound serves every float width.
   */
  private def emitRangeChecks(v: String, c: Type.Constrained): Unit =
    Type.underlying(c.base) match
      case f: Type.Floating =>
        val wide =
          if f.bits == 64 then v
          else { val r = freshTemp(); emit(s"$r = fpext ${f.llvm} $v to double"); r }
        for lo <- c.lo do trapUnless(fcmpConst("oge", wide, lo), "within")
        for hi <- c.hi do trapUnless(fcmpConst(if c.exclusiveHi then "olt" else "ole", wide, hi), "within")
      case base =>
        for lo <- c.lo do trapUnless(compareValue(">=", base, v, lo.toBigInt.toString), "within")
        for hi <- c.hi do
          trapUnless(compareValue(if c.exclusiveHi then "<" else "<=", base, v, hi.toBigInt.toString), "within")

  /** Everything a constrained subtype asks of a value: the `within` range, then the `where`
   * predicate — a synthesised `i1`-returning function over the base value, which traps exactly as
   * the range does when it answers false. Shared by the checking node and by the two forms that
   * compute and store in one step, so a value cannot reach a constrained slot by a path that tests
   * less of it than another.
   */
  protected def emitConstraintChecks(v: String, c: Type.Constrained): Unit = {
    emitRangeChecks(v, c)

    for pf <- c.predFn do
      val r = freshTemp()
      emit(s"$r = call i1 @$pf(${Type.underlying(c.base).llvm} $v)")
      trapUnless(r, "where")
  }

  private def fcmpConst(pred: String, wide: String, bound: BigDecimal): String = {
    val c = f"0x${java.lang.Double.doubleToLongBits(bound.toDouble)}%016X"
    val r = freshTemp(); emit(s"$r = fcmp $pred double $wide, $c"); r
  }

  /** Checks a struct value against its `invariant` function: read each stored field out of the
   * aggregate `v`, call `invFn`, and trap on a false result. The fields are handed over exactly as
   * an ordinary call's arguments are — the callee borrows, so no count is taken here.
   */
  protected def emitInvCheck(v: String, struct: Type.Struct, invFn: String): Unit =
    val container = Bitfields.of(struct).map { ranges =>
      val c = freshTemp(); emit(s"$c = extractvalue ${struct.llvm} $v, 0"); (ranges, c)
    }

    val args = struct.fields.zipWithIndex.collect {
      case ((_, ft), i) if !Type.zeroSized(ft) =>
        // A bitfield struct's fields are ranges of one container rather than slots of an aggregate,
        // so they are read out of it once it has been lifted out — which is the same one read
        // whether the invariant relates one field or all of them.
        val r = container match
          case Some((ranges, c)) => readBits(ranges, ranges(struct.slot(i)), c)
          case None =>
            val t = freshTemp(); emit(s"$t = extractvalue ${struct.llvm} $v, ${struct.slot(i)}"); t

        s"${ft.llvm} $r"
    }
    val ok = freshTemp(); emit(s"$ok = call i1 @$invFn(${args.mkString(", ")})")
    trapUnless(ok, "invariant")
}
