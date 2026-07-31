package io.github.edadma.sysl

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

  override protected def startFunction(): Unit = {
    super.startFunction()
    ensures = Nil
    resultSSA = None
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
    val args = struct.fields.zipWithIndex.collect {
      case ((_, ft), i) if !Type.zeroSized(ft) =>
        val r = freshTemp(); emit(s"$r = extractvalue ${struct.llvm} $v, ${struct.slot(i)}")
        s"${ft.llvm} $r"
    }
    val ok = freshTemp(); emit(s"$ok = call i1 @$invFn(${args.mkString(", ")})")
    trapUnless(ok, "invariant")
}
