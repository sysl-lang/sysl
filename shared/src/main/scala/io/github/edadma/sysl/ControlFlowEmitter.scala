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

  protected def genIf(cond: List[TCondTerm], thenBlock: TBlock, elseBlock: Option[TBlock], ty: Type): String = {
    val thenL  = freshLabel("if.then")
    val elseL  = freshLabel("if.else")
    val endL   = freshLabel("if.end")
    val target = if elseBlock.isDefined then elseL else endL
    val slot   = if Type.noValue(ty) then "" else emitAlloca(freshTemp(), ty.llvm)

    // The last term's success edge is the branch's entry, so a condition with nothing to bind emits
    // exactly the one test and the one `br` it always did.
    val paths = genCond(cond, thenL, target)

    if Type.noValue(ty) then genBlockVoid(thenBlock) else storeBlockValue(thenBlock, ty, slot)
    // The branch's value is computed before the condition's bindings are given back, so a `then`
    // that yields what it destructured has taken its own count by the time this runs.
    paths.release()
    emitTerm(s"br label %$endL")
    paths.emitCleanups()

    elseBlock.foreach { eb =>
      emitLabel(elseL)
      if Type.noValue(ty) then genBlockVoid(eb) else storeBlockValue(eb, ty, slot)
      emitTerm(s"br label %$endL")
    }

    emitLabel(endL)
    endsNowhere(ty)
    if Type.noValue(ty) then ""
    // Each branch handed its value over with a count taken, so what the merge loads is the
    // one temporary the enclosing region has to let go of.
    else { val r = freshTemp(); emit(s"$r = load ${ty.llvm}, ptr $slot"); ownTemp(r, ty) }
  }

  /** What emitting a condition left open: the owned scopes its `is` terms pushed for their
   * bindings, and the small blocks that sit on the edges leaving the chain part-way through.
   *
   * The two are separate because they run at different points. `release` closes the success path,
   * where every binding was made and the branch has finished with them. `emitCleanups` lays down the
   * blocks a failing term branches to, each giving back exactly what had been taken by the time
   * control reached it and falling into the block for the term before it — so a chain that fails at
   * its third term releases what its first two took, and a chain that fails at its first releases
   * nothing.
   */
  protected class CondPaths(scopes: Int, cleanups: List[(String, List[(String, Type)], Boolean, String)]) {
    def release(): Unit = for _ <- 1 to scopes do popOwned()

    def emitCleanups(): Unit =
      for (label, held, slots, next) <- cleanups do
        emitLabel(label)
        if slots then releaseSlots(held) else releaseValues(held)
        emitTerm(s"br label %$next")
  }

  /** Emits an `if`'s or a `while`'s condition as the chain of `&&`-joined terms it is (`09 §12`),
   * landing on `successL` — open, with every binding established — where all of them held.
   *
   * Each term branches to the next on success and to `failL` on failure, the last one branching to
   * `successL`; so a condition with no `is` in it is the one test and the one `br` it always was,
   * and the labels a reader of the IR looks for are still the ones the branch is named after.
   *
   * A term that **bound** something moves the failure target to an unbind block of its own, so
   * everything to its right leaves through the releases it owes. That is the whole reason this is
   * not a `TExpr` the short-circuit `&&` could have generated: a `&&` has no bindings to unwind, and
   * its two edges meet again at one point, where these do not.
   *
   * A pattern's test and its bindings are separated for the reason `genMatch` separates them — the
   * test is a pure read, so a chain may run several before anything is committed to, and a failure
   * retains nothing it then has to hand back.
   *
   * **Every term borrows in a region of its own**, which is the discipline the short-circuit `&&`
   * keeps and which flattening the chain would otherwise have thrown away: a later term's
   * temporaries exist only on the edges that reached it, so releasing them anywhere the whole chain
   * meets would release values the incoming path never made. A term that does not bind closes its
   * region *before* it branches, so both of its edges are already clear; a term that binds cannot,
   * having to retain out of the very value it is holding, so its failing edge gets a block that
   * closes the region for it.
   */
  protected def genCond(terms: List[TCondTerm], successL: String, failL: String): CondPaths = {
    var target   = failL
    var scopes   = 0
    var cleanups = List.empty[(String, List[(String, Type)], Boolean, String)]

    for (term, i) <- terms.zipWithIndex do
      // The block a term hands control to when it holds: the branch's own entry for the last term,
      // and otherwise a label named after what happens there — a binding is established in it, so a
      // reader of the IR can see where the pattern was committed to.
      val nextL =
        if i == terms.length - 1 then successL
        else
          term match
            case TCondIs(_, List(pat), false) if bindsAny(pat) => freshLabel("cond.bind")
            case _                                             => freshLabel("cond.and")

      pushTemps()

      term match
        case TCondTest(c) =>
          val v = genExpr(c)
          popTemps()
          emitTerm(s"br i1 $v, label %$nextL, label %$target")
          emitLabel(nextL)

        case TCondIs(subject, pats, negated) =>
          val sv   = genExpr(subject)
          val held = pats.map(patternTest(_, sv)).reduce(orI1)
          val ok   = if negated then notI1(held) else held

          // A negated test binds nothing, and neither does a list of alternatives — the analyzer
          // refused a pattern that tried in either position — so both close their region here and
          // leave the failure target where it was.
          if negated || pats.length != 1 || !bindsAny(pats.head) then
            popTemps()
            emitTerm(s"br i1 $ok, label %$nextL, label %$target")
            emitLabel(nextL)
          else
            // The bind retains out of the subject, so the subject has to still be held when it runs.
            // That puts the release after the branch on the taken edge, and in a block of its own on
            // the other.
            val borrowed = tempsHere
            val onFail =
              if borrowed.isEmpty then target
              else
                val l = freshLabel("cond.drop")
                cleanups ::= (l, borrowed, false, target)
                l

            emitTerm(s"br i1 $ok, label %$nextL, label %$onFail")
            emitLabel(nextL)
            pushOwned()
            patternBind(pats.head, sv)
            scopes += 1
            popTemps()

            // Only a binding that holds a count has anything to give back; the rest of the chain can
            // keep failing straight to where it was already going. Its region is closed by then, so
            // this block owes the bindings alone.
            val slots = ownedHere
            if slots.nonEmpty then
              val unbindL = freshLabel("cond.unbind")
              cleanups ::= (unbindL, slots, true, target)
              target = unbindL

    new CondPaths(scopes, cleanups)
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
    // The binding is established later, in `patternBind`; what decides the arm is the inner test
    // alone, since naming a value says nothing about whether it matched.
    case a: TAtPattern                     => patternTest(a.inner, value)
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

    // `n @ pat` binds the whole value and then whatever the inner pattern binds, both off the same
    // `value` — so a `&T` matched this way is retained once for the outer name and once for each
    // inner one, which is what every other pattern that names a value more than once already does.
    case TAtPattern(name, inner) =>
      patternBind(TBindPattern(name, inner.ty), value)
      patternBind(inner, value)
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
    case _: TAtPattern      => true
    case v: TVariantPattern => v.args.exists(bindsAny)
    case s: TStructPattern  => s.args.exists(bindsAny)
    case _                  => false

  /** Whether a pattern needs a run-time test — false for a wildcard, a binding, or a struct whose
   * fields all need none, so a struct pattern emits reads only for the fields it actually checks.
   */
  private def refutable(p: TPattern): Boolean = p match
    case _: TWildPattern | _: TBindPattern => false
    case a: TAtPattern                     => refutable(a.inner)
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
    // Recorded before the condition's bindings, so a `break` or a `continue` from the body unwinds
    // the round's bindings along with the body's own scope. The condition's *borrowing* is not the
    // loop's to unwind: each term closes its own region (`genCond`), so by the time the body runs
    // there is nothing of the test left outstanding.
    genLoops = GenLoop(endL, condL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$condL")
    emitLabel(condL)
    val paths = genCond(cond, bodyL, elseL)
    pushOwned()
    body.foreach(genStmt)
    popOwned()
    // A binding is per-iteration: it is released at the bottom of the body and made again by the
    // next round's test, so nothing accumulates across a loop that runs a million times.
    paths.release()
    emitTerm(s"br label %$condL")
    paths.emitCleanups()

    genLoops = genLoops.tail
    genLoopResult(slot, ty, elseL, endL, elseBlock)
  }

  /** The post-test loop. It is `genWhile`'s shape entered one label later — the entry branch goes to
   * the body rather than to the test — so the body runs before anything is asked.
   *
   * `continue` targets the **test**, which is what the form exists for: the `loop` with `if !cond
   * then break` at its foot that a program writes instead has no test for a `continue` to reach, so
   * the first one added to it jumps over the exit and never leaves.
   */
  protected def genDoWhile(d: TDoWhile): String = {
    val TDoWhile(body, cond, elseBlock, ty) = d
    val bodyL = freshLabel("dowhile.body")
    val condL = freshLabel("dowhile.cond")
    val endL  = freshLabel("dowhile.end")
    val elseL = if elseBlock.isDefined then freshLabel("dowhile.else") else endL
    val slot  = if Type.noValue(ty) then "" else emitAlloca(freshTemp(), ty.llvm)
    genLoops = GenLoop(endL, condL, slot, ty, owned.length, tempStack.length) :: genLoops

    emitTerm(s"br label %$bodyL")
    emitLabel(bodyL)
    pushOwned()
    body.foreach(genStmt)
    popOwned()
    emitTerm(s"br label %$condL")
    emitLabel(condL)
    // Re-evaluated every round, so what the test borrows is let go before the branch rather than
    // piling up in the enclosing statement's region — as the three-clause loop's test does.
    pushTemps()
    val v = genExpr(cond)
    popTemps()
    emitTerm(s"br i1 $v, label %$bodyL, label %$elseL")

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

    emitTerm(s"br label %$condL")
    emitLabel(condL)
    pushTemps()
    // `continue` goes back to the test, which is where the next element comes from: an iterating
    // loop has no step of its own, since advancing is what `next` did.
    //
    // Recorded **after** the frame the option lives in, and deliberately: the body was reached
    // through the `releaseTemps` below, so a `break` that unwound that frame as well would give the
    // option back twice.
    genLoops = GenLoop(endL, condL, slot, ty, owned.length, tempStack.length) :: genLoops
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
