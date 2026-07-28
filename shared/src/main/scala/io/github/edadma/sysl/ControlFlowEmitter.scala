package io.github.edadma.sysl

/** Everything that makes a basic block: `if`, `match` and its patterns, and the three loops.
 *
 * The shape is the same in all of them. A construct that yields a value allocates a **merge slot**,
 * each path stores into it, and the merge label loads it — there is no `phi` construction, which is
 * what lets a branch be generated without knowing what the other branches did. A path that does not
 * arrive stores nothing, so a diverging arm needs no special handling: its block is already closed
 * and `emit` drops what would follow.
 *
 * A pattern is split in two on purpose. `patternTest` is a pure value read that answers whether an
 * arm matches, with no side effects and no bindings, so it can run before the arm is chosen;
 * `patternBind` establishes the bindings afterwards, once the arm is known to be taken. Splitting
 * them is what keeps a failed match from retaining anything it then has to release.
 */
trait ControlFlowEmitter extends PlaceEmitter {

  protected def genIf(cond: TExpr, thenBlock: TBlock, elseBlock: Option[TBlock], ty: Type): String = {
    val c      = genExpr(cond)
    val thenL  = freshLabel("if.then")
    val elseL  = freshLabel("if.else")
    val endL   = freshLabel("if.end")
    val target = if elseBlock.isDefined then elseL else endL

    if Type.noValue(ty) then
      emitTerm(s"br i1 $c, label %$thenL, label %$target")
      emitLabel(thenL)
      genBlockVoid(thenBlock)
      emitTerm(s"br label %$endL")
      elseBlock.foreach { eb =>
        emitLabel(elseL)
        genBlockVoid(eb)
        emitTerm(s"br label %$endL")
      }
      emitLabel(endL)
      endsNowhere(ty)
      ""
    else
      val slot = emitAlloca(freshTemp(), ty.llvm)
      emitTerm(s"br i1 $c, label %$thenL, label %$elseL")
      emitLabel(thenL)
      storeBlockValue(thenBlock, ty, slot)
      emitTerm(s"br label %$endL")
      emitLabel(elseL)
      storeBlockValue(elseBlock.get, ty, slot)
      emitTerm(s"br label %$endL")
      emitLabel(endL)
      // Each branch handed its value over with a count taken, so what the merge loads is the
      // one temporary the enclosing region has to let go of.
      val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); ownTemp(r, ty)
  }

  /** Feeds one branch's value into the merge slot. A branch that does not finish — one that aborts
   * or returns — has no value to feed and terminates its own block, so it is run for its effect
   * and nothing is stored: the merge is reached only from the branches that do arrive.
   */
  protected def storeBlockValue(b: TBlock, ty: Type, slot: String): Unit =
    if b.ty == Type.Never then genBlockVoid(b)
    else emit(s"store ${ty.llvm} ${genBlockValue(b)}, ptr $slot")

  /** Closes the merge point of an `if`, a `match`, or a loop whose every path diverges: no branch
   * arrives, so the label control would have landed on is unreachable.
   *
   * This is what keeps the invariant every consumer of a value relies on — **a `never`-typed
   * expression always leaves the block terminated** — true for the aggregate forms as well as for
   * the call that starts it. With that, a `return`, a `store`, a `break`, or an argument built from
   * one is dropped rather than emitted with nothing to say.
   */
  protected def endsNowhere(ty: Type): Unit =
    if ty == Type.Never then emitTerm("unreachable")

  protected def genMatch(scrutinee: TExpr, arms: List[TArm], ty: Type): String = {
    val sv   = genExpr(scrutinee)
    val endL = freshLabel("match.end")
    val slot = if Type.noValue(ty) then "" else emitAlloca(freshTemp(), ty.llvm)

    for arm <- arms do
      val bodyL = freshLabel("match.arm")
      val nextL = freshLabel("match.next")
      val patCond =
        arm.patterns.map(patternTest(_, sv)).reduce(orI1)

      // Bindings are established only after the pattern matches, and a guard may reference them,
      // so a guarded arm branches first on the pattern, then binds, then tests the guard.
      // Only a single (non-alternative) pattern may bind.
      def bind(): Unit = if arm.patterns.length == 1 then patternBind(arm.patterns.head, sv)

      arm.guard match
        case None =>
          emitTerm(s"br i1 $patCond, label %$bodyL, label %$nextL")
          emitLabel(bodyL)
          pushOwned()
          bind()
        case Some(g) =>
          val guardL = freshLabel("match.guard")
          emitTerm(s"br i1 $patCond, label %$guardL, label %$nextL")
          emitLabel(guardL)
          pushOwned()
          bind()
          pushTemps()
          val gv = genExpr(g)
          popTemps()
          // A guard that fails leaves an arm whose bindings were already made, so they are
          // given back before falling through to the next one.
          val unbindL = freshLabel("match.unbind")
          emitTerm(s"br i1 $gv, label %$bodyL, label %$unbindL")
          emitLabel(unbindL)
          releaseOwned()
          emitTerm(s"br label %$nextL")
          emitLabel(bodyL)

      if Type.noValue(ty) then genBlockVoid(arm.body)
      else storeBlockValue(arm.body, ty, slot)
      popOwned()
      emitTerm(s"br label %$endL")
      emitLabel(nextL)

    // Fallthrough with no matching arm: a value or enum match is exhaustive (the analyzer
    // required full coverage or a catch-all), so this point is unreachable; a plain scalar
    // statement match simply proceeds.
    if Type.noValue(ty) then emitTerm(s"br label %$endL") else emitTerm("unreachable")
    emitLabel(endL)
    endsNowhere(ty)
    if Type.noValue(ty) then ""
    else { val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); ownTemp(r, ty) }
  }

  /** The i1 result of testing a pattern against a value. Every pattern node carries the type it
   * tests at, so the value's type does not have to be passed alongside it. Pattern tests are pure
   * value reads (`extractvalue`, comparisons), so nested variant fields are extracted and
   * tested unconditionally — a failed outer tag simply ANDs a `false` through.
   */
  private def patternTest(p: TPattern, value: String): String = p match
    case _: TWildPattern | _: TBindPattern => "true"
    case TLitPattern(v)                    => compareValue("==", v.ty, value, genExpr(v))
    case TRangePattern(lo, hi, inclusive) =>
      val loOk = compareValue(">=", lo.ty, value, genExpr(lo))
      val hiOk = compareValue(if inclusive then "<=" else "<", hi.ty, value, genExpr(hi))
      andI1(loOk, hiOk)
    case TVariantPattern(en, variant, args) =>
      val tagVal =
        if en.simple then value
        else { val t = freshTemp(); emit(s"$t = extractvalue ${en.llvm} $value, 0"); t }
      val tagOk = freshTemp(); emit(s"$tagOk = icmp eq ${en.tagLlvm} $tagVal, ${variant.tag}")
      if args.isEmpty then tagOk
      else
        val payload = enumPayload(en, variant, value)
        args.zipWithIndex.foldLeft(tagOk) { case (acc, (arg, i)) =>
          andI1(acc, patternTest(arg, payloadField(en, variant, payload, i)))
        }

    // A struct has no tag, so the test is just its refutable fields' tests ANDed together; an
    // irrefutable field needs none, so nothing is emitted for the parts a named pattern omitted.
    // Any bindings such a field carries are established later, in `patternBind`.
    case TStructPattern(struct, args) =>
      args.zipWithIndex.foldLeft("true") { case (acc, (arg, i)) =>
        if !refutable(arg) then acc
        else andI1(acc, patternTest(arg, structField(struct, value, i)))
      }

  /** Establishes the bindings a pattern introduces, once its arm has been taken. Only binding
   * and (nested) variant patterns carry bindings; the rest are no-ops.
   */
  private def patternBind(p: TPattern, value: String): Unit = p match
    // A zero-sized binding is not a slot, exactly as a `var` of one is not. The name is still in
    // scope for the arm; every read of it yields nothing.
    case TBindPattern(_, bty) if Type.zeroSized(bty) => ()
    case TBindPattern(name, bty) =>
      emitAlloca(s"%$name.addr", bty.llvm)
      retainValue(bty, value)
      emit(s"store ${bty.llvm} $value, ptr %$name.addr")
      ownSlot(name, bty)
    case TVariantPattern(en, variant, args) if args.exists(bindsAny) =>
      val payload = enumPayload(en, variant, value)
      for (arg, i) <- args.zipWithIndex do patternBind(arg, payloadField(en, variant, payload, i))
    case TStructPattern(struct, args) if args.exists(bindsAny) =>
      for (arg, i) <- args.zipWithIndex if bindsAny(arg) do patternBind(arg, structField(struct, value, i))
    case _ => ()

  /** One field of a variant's payload, or nothing where the field is zero-sized — the same skipping
   * the layout does, seen from the reading side.
   */
  private def payloadField(en: Type.Enum, variant: Type.EnumVariant, payload: String, i: Int): String =
    if Type.zeroSized(variant.fields(i)._2) then ""
    else
      val fv = freshTemp()
      emit(s"$fv = extractvalue ${en.payloadLlvm(variant)} $payload, ${variant.slot(i)}")
      fv

  private def structField(struct: Type.Struct, value: String, i: Int): String =
    if Type.zeroSized(struct.fields(i)._2) then ""
    else
      val fv = freshTemp()
      emit(s"$fv = extractvalue ${struct.llvm} $value, ${struct.slot(i)}")
      fv

  private def bindsAny(p: TPattern): Boolean = p match
    case _: TBindPattern    => true
    case v: TVariantPattern => v.args.exists(bindsAny)
    case s: TStructPattern  => s.args.exists(bindsAny)
    case _                  => false

  /** Whether a pattern needs a run-time test — false for a wildcard, a binding, or a struct whose
   * fields all need none, so a struct pattern emits reads only for the fields it actually checks.
   */
  private def refutable(p: TPattern): Boolean = p match
    case _: TWildPattern | _: TBindPattern => false
    case s: TStructPattern                 => s.args.exists(refutable)
    case _                                 => true

  /** ANDs / ORs two i1 values, folding away the `"true"` immediate a trivially-true pattern
   * produces so the emitted condition stays readable.
   */
  // --- loops ---------------------------------------------------------------------------
  //
  // A loop is an expression: a `break value` stores into the loop's result slot and jumps to the
  // end, and normal completion runs the optional `else` (whose value feeds the same slot). When
  // the loop yields nothing (`unit`), there is no slot and the end is a plain merge. The `else`
  // target doubles as the end when there is no `else`, so a bare loop keeps its old shape.

  protected def genWhile(w: TWhile): String = {
    val TWhile(cond, body, elseBlock, ty) = w
    val condL = freshLabel("while.cond")
    val bodyL = freshLabel("while.body")
    val endL  = freshLabel("while.end")
    val elseL = if elseBlock.isDefined then freshLabel("while.else") else endL
    val slot  = if Type.noValue(ty) then "" else emitAlloca(freshTemp(), ty.llvm)
    genLoops = GenLoop(endL, condL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$condL")
    emitLabel(condL)
    // The condition is re-evaluated every iteration, so whatever it borrows is let go before the
    // branch rather than accumulating in the enclosing statement's region.
    pushTemps()
    val c = genExpr(cond)
    popTemps()
    emitTerm(s"br i1 $c, label %$bodyL, label %$elseL")
    emitLabel(bodyL)
    pushOwned()
    body.foreach(genStmt)
    popOwned()
    emitTerm(s"br label %$condL")

    genLoops = genLoops.tail
    genLoopResult(slot, ty, elseL, endL, elseBlock)
  }

  /** A `loop` is the same shape with the test gone: the body branches straight back to itself, and
   * the end is reached only by a `break`. `continue` targets the body's own label, since starting
   * the next iteration is all there is to do. Where nothing breaks, the loop's type is `never` and
   * the end label closes as unreachable.
   */
  protected def genLoop(l: TLoop): String = {
    val TLoop(body, ty) = l
    val bodyL = freshLabel("loop.body")
    val endL  = freshLabel("loop.end")
    val slot  = if Type.noValue(ty) then "" else emitAlloca(freshTemp(), ty.llvm)
    genLoops = GenLoop(endL, bodyL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$bodyL")
    emitLabel(bodyL)
    pushOwned()
    body.foreach(genStmt)
    popOwned()
    emitTerm(s"br label %$bodyL")

    genLoops = genLoops.tail
    genLoopResult(slot, ty, endL, endL, None)
  }

  protected def genFor(f: TFor): String = {
    val TFor(name, varTy, lo, hi, inclusive, body, elseBlock, ty) = f
    val w     = varTy.llvm
    val loV   = genExpr(lo)
    val hiV   = genExpr(hi)
    val condL = freshLabel("for.cond")
    val bodyL = freshLabel("for.body")
    val stepL = freshLabel("for.step")
    val endL  = freshLabel("for.end")
    val elseL = if elseBlock.isDefined then freshLabel("for.else") else endL
    val slot  = if Type.noValue(ty) then "" else emitAlloca(freshTemp(), ty.llvm)
    emitAlloca(s"%$name.addr", w)
    emit(s"store $w $loV, ptr %$name.addr")
    genLoops = GenLoop(endL, stepL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$condL")
    emitLabel(condL)
    val iv  = freshTemp(); emit(s"$iv = load $w, ptr %$name.addr")
    val cmp = freshTemp(); emit(s"$cmp = icmp ${predicate(if inclusive then "<=" else "<", varTy)} $w $iv, $hiV")
    emitTerm(s"br i1 $cmp, label %$bodyL, label %$elseL")
    emitLabel(bodyL)
    pushOwned()
    body.foreach(genStmt)
    popOwned()
    // `continue` lands here so the counter still advances before the next test.
    emitTerm(s"br label %$stepL")
    emitLabel(stepL)
    val cur = freshTemp(); emit(s"$cur = load $w, ptr %$name.addr")
    val nxt = freshTemp(); emit(s"$nxt = add $w $cur, 1")
    emit(s"store $w $nxt, ptr %$name.addr")
    emitTerm(s"br label %$condL")

    genLoops = genLoops.tail
    genLoopResult(slot, ty, elseL, endL, elseBlock)
  }

  /** The three-clause loop. It is `genFor`'s shape with both fixed parts opened up: the test is
   * whatever was written (absent ⇒ always taken, so the `else` target is unreachable and the loop
   * ends only through a `break`), and the step is whatever was written rather than an increment.
   *
   * `continue` targets the **step** block, which is the whole point of the form: a counted loop
   * written as a `while` skips its increment on the first `continue` somebody adds.
   */
  protected def genCFor(f: TCFor): String = {
    val TCFor(init, cond, step, body, elseBlock, ty) = f
    val condL = freshLabel("cfor.cond")
    val bodyL = freshLabel("cfor.body")
    val stepL = freshLabel("cfor.step")
    val endL  = freshLabel("cfor.end")
    val elseL = if elseBlock.isDefined then freshLabel("cfor.else") else endL
    val slot  = if Type.noValue(ty) then "" else emitAlloca(freshTemp(), ty.llvm)

    init.foreach(genStmt)
    genLoops = GenLoop(endL, stepL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$condL")
    emitLabel(condL)
    cond match
      case Some(c) =>
        // Re-evaluated every iteration, so what it borrows is let go before the branch rather than
        // accumulating in the enclosing statement's region.
        pushTemps()
        val v = genExpr(c)
        popTemps()
        emitTerm(s"br i1 $v, label %$bodyL, label %$elseL")
      case None => emitTerm(s"br label %$bodyL")

    emitLabel(bodyL)
    pushOwned()
    body.foreach(genStmt)
    popOwned()
    emitTerm(s"br label %$stepL")
    emitLabel(stepL)
    pushTemps()
    step.foreach(genStmt)
    popTemps()
    emitTerm(s"br label %$condL")

    genLoops = genLoops.tail
    genLoopResult(slot, ty, if cond.isDefined then elseL else endL, endL, elseBlock)
  }

  // The sequence is evaluated once, into the statement's own region, so a slice temporary stays
  // alive for the whole loop; the loop variable is a copy, released each iteration.
  protected def genForEach(e: TForEach): String = {
    val TForEach(name, elemTy, seq, body, elseBlock, ty) = e
    val (base, len) = seq.ty match
      case Type.Array(n, _) => (address(seq), n.toString)
      case s: Type.Slice =>
        val v = genExpr(seq)
        val p = freshTemp(); emit(s"$p = extractvalue ${s.llvm} $v, 1")
        val l = freshTemp(); emit(s"$l = extractvalue ${s.llvm} $v, 2")
        (p, l)
      case other => sys.error(s"unreachable iteration over ${other.llvm}")

    val idx   = emitAlloca(freshTemp(), "i64")
    val condL = freshLabel("each.cond")
    val bodyL = freshLabel("each.body")
    val stepL = freshLabel("each.step")
    val endL  = freshLabel("each.end")
    val elseL = if elseBlock.isDefined then freshLabel("each.else") else endL
    val slot  = if Type.noValue(ty) then "" else emitAlloca(freshTemp(), ty.llvm)
    emit(s"store i64 0, ptr $idx")
    genLoops = GenLoop(endL, stepL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$condL")
    emitLabel(condL)
    val iv   = freshTemp(); emit(s"$iv = load i64, ptr $idx")
    val more = freshTemp(); emit(s"$more = icmp ult i64 $iv, $len")
    emitTerm(s"br i1 $more, label %$bodyL, label %$elseL")
    emitLabel(bodyL)
    val ep = freshTemp(); emit(s"$ep = getelementptr ${elemTy.llvm}, ptr $base, i64 $iv")
    val ev = freshTemp(); emit(s"$ev = load ${elemTy.llvm}, ptr $ep")
    emitAlloca(s"%$name.addr", elemTy.llvm)
    retainValue(elemTy, ev)
    emit(s"store ${elemTy.llvm} $ev, ptr %$name.addr")
    pushOwned()
    ownSlot(name, elemTy)
    body.foreach(genStmt)
    popOwned()
    emitTerm(s"br label %$stepL")
    emitLabel(stepL)
    val nxt = freshTemp(); emit(s"$nxt = add i64 $iv, 1")
    emit(s"store i64 $nxt, ptr $idx")
    emitTerm(s"br label %$condL")

    genLoops = genLoops.tail
    genLoopResult(slot, ty, elseL, endL, elseBlock)
  }

  /** The iterating loop. The cursor gets a slot with a count of its own, taken before the loop is
   * recorded so that a `break` — which lets go of everything pushed *after* the record — leaves it
   * alone; the end label is where every path meets, and the release goes there.
   *
   * What `next` gives back is a temporary of its own each round, and the two edges out of the test
   * let go of it separately: the body binds the element (retaining it into its own slot) and then
   * releases the option, while the exhausted path releases it with nothing taken out.
   */
  protected def genIterate(e: TIterate): String = {
    val TIterate(cursor, cursorTy, init, next, bind, body, elseBlock, ty) = e
    val iv = genExpr(init)
    pushOwned()
    emitAlloca(s"%$cursor.addr", cursorTy.llvm)
    retainValue(cursorTy, iv)
    emit(s"store ${cursorTy.llvm} $iv, ptr %$cursor.addr")
    ownSlot(cursor, cursorTy)

    val condL = freshLabel("iter.cond")
    val bodyL = freshLabel("iter.body")
    val doneL = freshLabel("iter.done")
    val endL  = freshLabel("iter.end")
    val elseL = if elseBlock.isDefined then freshLabel("iter.else") else endL
    val slot  = if Type.noValue(ty) then "" else emitAlloca(freshTemp(), ty.llvm)
    // `continue` goes back to the test, which is where the next element comes from: an iterating
    // loop has no step of its own, since advancing is what `next` did.
    genLoops = GenLoop(endL, condL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$condL")
    emitLabel(condL)
    pushTemps()
    val opt = genExpr(next)
    val ok  = patternTest(bind, opt)
    emitTerm(s"br i1 $ok, label %$bodyL, label %$doneL")

    emitLabel(bodyL)
    pushOwned()
    patternBind(bind, opt)
    releaseTemps()
    body.foreach(genStmt)
    popOwned()
    emitTerm(s"br label %$condL")

    emitLabel(doneL)
    releaseTemps()
    dropTemps()
    emitTerm(s"br label %$elseL")

    genLoops = genLoops.tail
    val result = genLoopResult(slot, ty, elseL, endL, elseBlock)
    popOwned()
    result
  }

  /** Finishes a loop expression: run the `else` (if any) on the normal-completion path into the
   * result slot, then land at the end and hand the slot's value out as the enclosing region's to
   * release. A `unit` loop has no slot and yields nothing.
   */
  private def genLoopResult(slot: String, ty: Type, elseL: String, endL: String,
                            elseBlock: Option[TBlock]): String = {
    elseBlock.foreach { eb =>
      emitLabel(elseL)
      if Type.noValue(ty) then genBlockVoid(eb)
      else storeBlockValue(eb, ty, slot)
      emitTerm(s"br label %$endL")
    }
    emitLabel(endL)
    endsNowhere(ty)
    if Type.noValue(ty) then ""
    else { val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); ownTemp(r, ty) }
  }

  protected def genBlockVoid(b: TBlock): Unit = {
    pushTemps()
    pushOwned()
    b.stmts.foreach(genStmt)
    b.result.foreach(genExpr)
    popOwned()
    popTemps()
  }

  /** A branch's value, handed out with a count of its own. The block's locals and temporaries
   * are released before control leaves it — that is what keeps every release site dominating
   * the value it releases — so the result is retained first and becomes the caller's to let go.
   */
  protected def genBlockValue(b: TBlock): String = {
    pushTemps()
    pushOwned()
    b.stmts.foreach(genStmt)
    val v = genExpr(b.result.get)
    retainValue(b.result.get.ty, v)
    popOwned()
    popTemps()
    v
  }

}
