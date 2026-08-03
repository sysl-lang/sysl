package io.github.edadma.sysl

/** Control flow **as an expression** (`00 §10`), plus the three forms that carry several values at
 * once.
 *
 * Every one of these yields a value, which is the whole reason they are analyzed here rather than in
 * `StmtAnalysis`: an `if` in statement position and an `if` on the right of a `var` are the same
 * form asked a different question, and the difference is carried by `expected` and `discarded`
 * rather than by two grammars. A loop yields what its `break` carried and `unit` when it finishes on
 * its own, so a loop is a value too.
 *
 * Split out of `ExprAnalysis`, whose expression dispatch calls in here.
 */
trait ControlFlowExprAnalysis extends ExprSupport {

  /** A branch, a loop, or a form that yields more than one value. The parameter names them all, so
   * that the match below is exhaustive over exactly what the dispatch sends here.
   */
  protected def controlExpr(
      expr: IfExpr | MatchExpr | While | DoWhile | Loop | CFor | For | TryExpr | RangeExpr |
        ResultList | Lambda | Tuple,
      expected: Option[Type],
      discarded: Boolean,
  ): TExpr = expr match
    // An `if` whose own value is unused hands that down: each branch is a block in statement
    // position, so neither is asked what it yields and the two have nothing to disagree about.
    case IfExpr(cond, thenBody, elseOpt) =>
      // The condition's own scope wraps the condition and the *then* branch and nothing else, which
      // is the whole of what an `is` binding's reach has to be said about (`09 §12`). The `else` is
      // analyzed outside it, and so is an `elif` — the parser nests one into the else branch, so it
      // is already on the other side of this `popScope` and cannot read a name the test bound.
      pushScope()
      val tc    = analyzeCond(cond)
      val tThen = analyzeValueBlock(thenBody, expected, discarded)
      popScope()
      val tElse = elseOpt.map(analyzeValueBlock(_, expected, discarded))
      // The branches meet at one type, and a branch that does not finish takes the other's. A
      // branch used only for its effect is a different thing: one `unit` branch makes the whole
      // `if` a statement, whose value is nobody's, exactly as a missing `else` does.
      val ty = tElse match
        case Some(eb) =>
          join(tThen.ty, eb.ty).getOrElse {
            if eb.ty == Type.Unit || tThen.ty == Type.Unit then Type.Unit
            else err(s"if branches have different types: ${show(tThen.ty)} and ${show(eb.ty)}")
          }
        case None => Type.Unit
      TIf(tc, tThen, tElse, ty)

    case MatchExpr(scrut, arms) =>
      val ts    = analyzeExpr(scrut)
      val tarms = arms.map(analyzeArm(ts.ty, _, expected, discarded))
      TMatch(ts, tarms, matchResultType(ts.ty, tarms))

    // A loop's `else` is a block like any other, so a loop in statement position discards it too.
    // Without that, Python's own idiom — walk, `break` on a hit, set a flag in the `else` when
    // nothing hit — would be refused for a disagreement between the flag and a bare `break`.
    case While(label, cond, body, elseOpt) =>
      // As with `if`: the condition and the body share one scope, so a binding the test made is the
      // body's to read and is remade each round. The `else` runs when the test finally fails, which
      // is the one path on which there is nothing bound, so it sits outside.
      pushScope()
      val tc            = analyzeCond(cond)
      val (tbody, ctx)  = analyzeLoopBody(expected, label)(analyzeStmts(body))
      popScope()
      val telse         = elseOpt.map(analyzeValueBlock(_, expected, discarded))
      TWhile(tc, tbody, telse, loopResultType(ctx, telse))

    // The body is analyzed first and in its own scope, which has closed by the time the test is
    // reached — so the foot's condition reads what the enclosing block holds and nothing the body
    // declared. That is C's rule, and it is also the only one the form can have: a `var` made in the
    // body is remade every round, and a test that read one would be reading the last round's.
    //
    // The condition is `analyzeBool` rather than `analyzeCond` for the reason the three-clause
    // `for`'s is: an `is` binding is live through the branch that the test guards, and a test at the
    // foot guards nothing — the body it belongs to has already run.
    case DoWhile(label, body, cond, elseOpt) =>
      val (tbody, ctx) = analyzeLoopBody(expected, label)(analyzeStmts(body))
      val tcond        = analyzeBool(cond)
      val telse        = elseOpt.map(analyzeValueBlock(_, expected, discarded))
      TDoWhile(tbody, tcond, telse, loopResultType(ctx, telse))

    case Loop(label, body) =>
      val (tbody, ctx) = analyzeLoopBody(expected, label)(analyzeStmts(body))
      TLoop(tbody, endlessResultType(ctx))

    // The init's binding belongs to the loop and to nothing outside it, so the scope opens before
    // the condition — which reads that binding — and closes after the `else`, which may too.
    case CFor(label, init, cond, step, body, elseOpt) =>
      pushScope()
      val tinit        = init.toList.flatMap(recoverStmt)
      val tcond        = cond.map(analyzeBool)
      val (tbody, ctx) = analyzeLoopBody(expected, label)(inBlock(body)(body.flatMap(recoverStmt)))
      val tstep        = step.toList.flatMap(recoverStmt)
      val telse        = elseOpt.map(analyzeValueBlock(_, expected, discarded))
      popScope()
      // With no condition the loop cannot finish on its own, so its type is what its `break`s
      // carry, exactly as `loop`'s is — and an `else` that can never run is a mistake worth saying.
      if tcond.isEmpty && telse.isDefined then
        err("this 'for' has no condition, so it never finishes on its own and its 'else' cannot run")
      TCFor(tinit, tcond, tstep, tbody, telse,
            if tcond.isEmpty then endlessResultType(ctx) else loopResultType(ctx, telse))

    case For(label, name, iter, body, elseOpt) =>
      iter match
        // `for i in T::Range` iterates a constrained integer subtype's range, `First` through `Last`
        // inclusive — the one place `::Range` is meaningful.
        case TypeAttr(Ident(tn), "Range") if lookupOpt(tn).isEmpty && typeKey(tn).exists(constrainedDecls.contains) =>
          val c = resolveConstrained(typeKey(tn).get)
          val i = c.base match
            case i: Type.Integer => i
            case other           => err(s"'${qn(typeKey(tn).get)}::Range' iterates an integer subtype, not ${show(other)}")
          val (lo, hi) = (c.lo, c.hi) match
            case (Some(l), Some(h)) => (l, h)
            case _                  => err(s"'${qn(typeKey(tn).get)}::Range' needs a 'within' range")
          val last      = if c.exclusiveHi then hi - 1 else hi
          pushScope()
          // The loop variable is a `T`, since what the range walks are the values of the subtype —
          // the bounds and the comparison stay at the base, which is what `T` is laid out as.
          val u         = declare(name, c)
          val (tb, ctx) = analyzeLoopBody(expected, label)(inBlock(body)(body.flatMap(recoverStmt)))
          popScope()
          val telse     = elseOpt.map(analyzeValueBlock(_, expected, discarded))
          TFor(u, i, TIntLit(lo.toBigInt, i), TIntLit(last.toBigInt, i), inclusive = true, tb, telse,
               loopResultType(ctx, telse))

        case RangeExpr(Some(lo), Some(hi), inclusive) =>
          val List(tlo, thi) = analyzeOperands(List(lo, hi), None)
          if tlo.ty != thi.ty then
            err(s"a 'for' range needs matching bounds, got ${show(tlo.ty)} and ${show(thi.ty)}")
          val vty = tlo.ty match
            case i: Type.Integer => i
            case other           => err(s"a 'for' range iterates integer bounds, not ${show(other)}")
          pushScope()
          val u            = declare(name, vty)
          val (tb, ctx)    = analyzeLoopBody(expected, label)(inBlock(body)(body.flatMap(recoverStmt)))
          popScope()
          val telse        = elseOpt.map(analyzeValueBlock(_, expected, discarded))
          TFor(u, vty, tlo, thi, inclusive, tb, telse, loopResultType(ctx, telse))

        case _ =>
          val seq = autoDeref(analyzeExpr(iter))
          seq.ty match
            case Type.Array(_, elem) => forEach(label, name, seq, elem, body, elseOpt, expected, discarded)
            case Type.Slice(elem, _) => forEach(label, name, seq, elem, body, elseOpt, expected, discarded)
            // A string has two granularities and no reason to prefer one silently, so which one
            // is wanted is written: `s.bytes` for the bytes, `s.chars` for the characters they
            // encode. Neither is the default, because a program that means one rarely means both.
            case Type.Str =>
              err("a string is iterated as 's.bytes' or 's.chars', " +
                "since a string has bytes and characters both")
            case ty if iterateElem(ty).isDefined =>
              iterating(label, name, seq, iterateElem(ty).get, body, elseOpt, expected, discarded)
            case other =>
              err(s"'for' iterates an integer range, an array, a slice, or a type that implements " +
                s"'${qn(Library.key("Iterate"))}', and ${show(other)} is none of those")

    case TryExpr(e) =>
      analyzeTry(analyzeExpr(e))

    case _: RangeExpr =>
      err("a range is only allowed in a 'for' loop or a 'match' pattern")

    // `a, b` where a function's result list is what is being produced. It builds the aggregate the
    // caller takes apart — the same one a tuple builds, since a result list is a tuple's layout
    // without a tuple's type.
    case ResultList(values) =>
      if !retIsList then
        err("several values separated by commas are a function's result list, and this function " +
          "declares one result — write the values it wants, or declare a result list")

      val want = retTy.asInstanceOf[Type.Tuple]

      if values.length != want.targs.length then
        err(s"this function yields ${quantity(want.targs.length, "result")}, but " +
          s"${supplied(values.length, "value")}")

      val ts = values.zip(want.targs).map((v, w) => analyzeExpr(v, Some(w)))

      for ((t, w) <- ts.zip(want.targs)) do
        if disagree(t.ty, w) then
          at(t.pos)(err(s"this result is declared ${show(w)}, but the value is ${show(t.ty)}"))

      TStructNew(want, ts)

    case l: Lambda => analyzeLambda(l, expected)

    // `(a, b)` — a tuple, built exactly as a struct is: the parts are the fields, in the order they
    // were written. What each part is *wanted* at comes from the tuple being asked for, which is
    // what lets `var p: (i8, i8) = (1, 2)` narrow its literals the way a struct's fields do.
    case Tuple(elems) =>
      // A function declaring a result list yields several things and not one tuple (`12 §5b`), so
      // the parentheses are refused where they would build the carrier the form says never exists.
      if wantsResults(expected) then
        err(s"this function yields ${quantity(elems.length, "result")} rather than a tuple — " +
          s"write the values without the parentheses")

      val wanted = expected.map(Type.underlying) match
        case Some(t: Type.Tuple) if t.targs.length == elems.length => t.targs.map(Some(_))
        case _                                                     => elems.map(_ => None)

      val ts = elems.zip(wanted).map((e, w) => analyzeExpr(e, w))

      // A `unit` part is let through for the reason a `unit` field is (`00 §12`): the layout skips
      // it. `never` is refused for the reason it is refused everywhere but a result — a part that
      // is never produced is a part nothing can give the tuple.
      for t <- ts do
        if t.ty == Type.Never then
          at(t.pos)(err("a tuple part has to be a value, and this expression never produces one"))

      TStructNew(tupleType(ts.map(_.ty)), ts)

  /** An `if`'s or a `while`'s condition, as the `&&`-joined chain of terms it is (`09 §12`).
   *
   * The chain is flattened here rather than left as nested `Binary("&&", …)` because a term may
   * **bind**, and a binding's reach is "from its own `is` rightward" — which is a statement about
   * the flat sequence and not about a tree. Left-nesting would put `a is P && g` under the same node
   * whether `g` reads what `P` bound or not.
   *
   * Terms are analyzed left to right, in the scope the caller opened, so each `is` declares into the
   * scope the terms after it look names up in. Nothing else about a condition changes: a chain with
   * no `is` in it is the same expressions in the same order, split at the `&&` the emitter was going
   * to short-circuit at anyway.
   */
  protected def analyzeCond(e: Expr): List[TCondTerm] = conjuncts(e).map(condTerm)

  private def conjuncts(e: Expr): List[Expr] = e match
    case Binary("&&", l, r) => conjuncts(l) ::: conjuncts(r)
    case other              => List(other)

  private def condTerm(e: Expr): TCondTerm = e match
    case p: IsPattern => at(p.pos)(condIs(p))
    // Everything else is an ordinary boolean, and an `is` buried inside one is refused by
    // `analyzeExpr` — which is where the message lives, since it has to name every position rather
    // than the one this function happens to be looking at.
    case other => TCondTest(analyzeBool(other))

  private def condIs(p: IsPattern): TCondIs = {
    val subject = analyzeExpr(p.subject)

    if subject.ty == Type.Never then
      err("this expression never produces a value, so there is nothing here for a pattern to match")

    val tpats = p.patterns.map(analyzePattern(_, subject.ty))

    // A pattern that matches every value of the type asks a question with one answer. Refused rather
    // than folded away, because the two forms it takes are both a mistake worth naming: `x is n` is a
    // binding wearing a test's clothes, and a struct pattern with no refutable field is a
    // destructuring that belongs in the branch it was guarding. Among alternatives it is one of them
    // being irrefutable that decides it, since that one already answers for the rest.
    if !tpats.forall(refutable) then
      err(if p.negated then
            s"this pattern matches every ${show(subject.ty)}, so 'is not' is never true here"
          else
            s"this pattern matches every ${show(subject.ty)}, so the test is always true — " +
              s"take the value apart with 'match', or bind it with 'var'")

    // Alternatives share one answer, so the branch cannot know which of them matched — the same rule
    // an arm's alternatives are held to (`09 §6`), said in the words this position needs.
    if tpats.length > 1 && tpats.exists(binds) then
      err("alternative patterns joined by '|' cannot bind a name — the branch cannot know which of " +
        "them matched. Write '_' for the parts you are not naming")

    // `x is not Some(n)` would name `n` on the one path where nothing matched it. The binder is what
    // is refused, not the negation: `x is not Some(_)` is the early-exit guard the form is for.
    if p.negated && tpats.exists(binds) then
      err("a pattern under 'is not' cannot bind a name — the branch it guards is the one where it " +
        "did not match, so there would be nothing for the name to hold. Write '_' for the parts you " +
        "are not naming")

    TCondIs(subject, tpats, p.negated)
  }

  /** `for name in seq` over storage that is already there: each element is copied out by index, and
   * the sequence is evaluated once.
   */
  protected def forEach(label: Option[String], name: String, seq: TExpr, elem: Type, body: List[Stmt],
                      elseOpt: Option[List[Stmt]], expected: Option[Type], discarded: Boolean): TExpr = {
    pushScope()
    val u         = declare(name, elem)
    val (tb, ctx) = analyzeLoopBody(expected, label)(inBlock(body)(body.flatMap(recoverStmt)))
    popScope()
    val telse     = elseOpt.map(analyzeValueBlock(_, expected, discarded))
    TForEach(u, elem, seq, tb, telse, loopResultType(ctx, telse))
  }

  /** `for name in cursor` over a sequence that has to be produced a value at a time (`14 §7`).
   *
   * The cursor is the loop's own: the expression is evaluated once into a slot nothing outside the
   * loop can name, and `next` takes that slot's address, so a `Chars` or any other iterator advances
   * in place while whatever was written stays a value like every other. That the slot is a *copy* is
   * the ordinary value semantics — draining `for c in it` leaves an `it` the program declared
   * untouched, exactly as passing it to a function would.
   */
  protected def iterating(label: Option[String], name: String, seq: TExpr, elem: Type, body: List[Stmt],
                        elseOpt: Option[List[Stmt]], expected: Option[Type], discarded: Boolean): TExpr = {
    val cursor = freshName("iter")
    val step   = callMethodOn(TLoad(cursor, seq.ty), "next", Nil, None)
    val opt = step.ty match
      case e: Type.Enum if e.base == Library.key("Option") && e.targs == List(elem) => e
      case other =>
        err(s"'${qn(Library.key("Iterate"))}' asks its 'next' for an " +
          s"${show(Type.Enum(Library.key("Option"), List(elem)))}, " +
          s"and this one gives back ${show(other)}")

    pushScope()
    val u         = declare(name, elem)
    val (tb, ctx) = analyzeLoopBody(expected, label)(inBlock(body)(body.flatMap(recoverStmt)))
    popScope()
    val telse     = elseOpt.map(analyzeValueBlock(_, expected, discarded))
    val bind      = TVariantPattern(opt, opt.variant("Some").get, List(TBindPattern(u, elem)))
    TIterate(cursor, seq.ty, seq, step, bind, tb, telse, loopResultType(ctx, telse))
  }

  /** What a type's `Iterate` implementation yields, or `None` where it has none.
   *
   * A type may implement one parameterized trait at more than one argument list (`02`), and for
   * every other trait the call's arguments are what say which — but `next` takes none, so a second
   * `Iterate` leaves the loop nothing to decide with. That is reported here rather than left to the
   * call, because the sentence a program needs names the loop.
   */
  protected def iterateElem(ty: Type): Option[Type] = {
    val (key, targs) = memberOwner(ty)
    val iterate      = Library.key("Iterate")

    // A **trait object** has no implementations filed for it and needs none: the table it carries
    // holds `Iterate`'s member, which is the one thing the loop calls. Reaching it here is the same
    // rule that lets an object satisfy a bound (`10 §5`) — a `for` asks what may be called on the
    // value, and the answer comes from the same table.
    //
    // The several-implementations case below cannot arise for one, which is why this is a lookup
    // rather than a choice: a trait-object type names one trait at one argument list, so the element
    // type is whatever the object was erased to. It is read out of the requirement closure so that a
    // trait *requiring* `Iterate` is walked too, exactly as its members are.
    def erased =
      for
        tr   <- Type.erasedTrait(ty)
        b    <- traitClosure(tr.bound, selfBinding(ty)).find(_.name == iterate)
        elem <- b.args.headOption
      yield elem

    erased.orElse(
      implsOf(iterate, key).map(suppliedBound(_, iterate, ty, targs).args) match
        case Nil               => None
        case List(elem) :: Nil => Some(elem)
        case several =>
          err(s"${show(ty)} implements '${qn(iterate)}' " +
            s"${conjoin(several.map(a => s"'${Type.Bound(iterate, a).show}'"))}, and a 'for' has " +
            "nothing to say which of them it means — call 'next' yourself, with the element type written"),
    )
  }
}
