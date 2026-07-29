package io.github.edadma.sysl

/** Statements, and the blocks and loop contexts they live in.
 *
 * A statement is analyzed as its own **recovery region**: a mistake inside one is recorded, the
 * region abandoned, and the walk resumes at the next statement — which is what turns one error per
 * compilation into one error per mistake. Winding the scope and loop stacks back is part of that,
 * so an abandoned block cannot leave its bindings visible to the code after it.
 *
 * The other thread here is that a block is an **expression**: it has a type, taken from its
 * trailing expression, or `never` where it ends in a jump and control does not arrive at all.
 */
trait StmtAnalysis extends TypeResolution {

  /** A block whose trailing expression (if any) is its value — a function body or an if/match
   * branch. Statements share one lexical scope with the result expression.
   */
  protected def analyzeValueBlock(stmts: List[Stmt], expected: Option[Type], discarded: Boolean = false): TBlock = {
    pushScope()
    val tb = analyzeBlockBody(stmts, expected, discarded)
    popScope()
    tb
  }

  /** The body of a value block, using whatever scope the caller has established — a match arm
   * runs this after declaring its pattern bindings, so they are visible to the body.
   *
   * A block that ends in a **jump** — `return`, `break`, `continue` — has no trailing expression to
   * be the value of, and it does not fall out the bottom either, so its type is `never` rather than
   * `unit`. That is what lets `if c then 1 else return 0` be an `int`: the jump is still not an
   * expression (`12 §3`), but the block around it is one, and its type says control does not arrive.
   *
   * **A block in statement position has no value, whatever its last expression yields** (`00 §2`).
   * There is no statement terminator to write "and throw this away" with, so a trailing call, an
   * assignment, or an `i++` would otherwise make a block that was plainly written for its effect
   * claim a value nobody asked for — and two such blocks under one `if` or `match` would then be
   * made to agree on it. A block that does not *arrive* keeps `never`: that is reachability rather
   * than a value, and the code around it is still entitled to know.
   */
  protected def analyzeBlockBody(stmts: List[Stmt], expected: Option[Type], discarded: Boolean = false): TBlock =
    stmts.reverse match
      case ExprStmt(e) :: initRev =>
        val init = initRev.reverse.flatMap(recoverStmt)
        // A block whose value *is* the enclosing function's result is the third place a result list
        // may stand (`12 §5b`), which is what lets a branch or a nested block forward one.
        val tr =
          if wantsResults(expected) then analyzeMulti(e, expected)
          else analyzeExpr(e, expected, discarded)
        TBlock(init, Some(tr), if discarded && tr.ty != Type.Never then Type.Unit else tr.ty)
      case (_: Return | _: Break | _: Continue) :: _ =>
        TBlock(stmts.flatMap(recoverStmt), None, Type.Never)
      case _ =>
        TBlock(stmts.flatMap(recoverStmt), None, Type.Unit)

  /** A statement sequence used only for its effects (a loop body): a fresh scope, no value. */
  protected def analyzeStmts(stmts: List[Stmt]): List[TStmt] = {
    pushScope()
    val r = stmts.flatMap(recoverStmt)
    popScope()
    r
  }

  /** Analyzes a loop body with a fresh loop context on the stack, so `break`/`continue` inside it
   * resolve to this loop and each `break` value is collected for the loop's result type. Returns
   * the typed body together with the context the breaks were recorded in.
   */
  protected def analyzeLoopBody(expected: Option[Type], label: Option[String])(
      body: => List[TStmt],
  ): (List[TStmt], LoopCtx) = {
    label.foreach { l =>
      if loops.exists(_.label.contains(l)) then err(s"label '$l is already in scope")
    }
    val ctx = new LoopCtx(expected, label)
    loops = ctx :: loops
    val tb = body
    loops = loops.tail
    (tb, ctx)
  }

  /** Resolves a `break`/`continue` to the loop it targets and that loop's distance out from the
   * innermost. An absent label is the nearest loop; a `'name` is the nearest loop carrying it.
   */
  private def resolveLoop(keyword: String, label: Option[String]): (LoopCtx, Int) = label match
    case None =>
      loops match
        case ctx :: _ => (ctx, 0)
        case Nil      => err(s"'$keyword' is only allowed inside a loop")
    case Some(l) =>
      loops.indexWhere(_.label.contains(l)) match
        case -1 => err(s"no enclosing loop is labeled '$l")
        case i  => (loops(i), i)

  /** The result type of a loop: every `break` value and the `else` value must meet at one type.
   * With no `else`, normal completion yields `unit`, so a value-carrying `break` has nothing to
   * meet on that path — a clear error rather than a silent mismatch.
   */
  protected def loopResultType(ctx: LoopCtx, elseBlock: Option[TBlock]): Type = {
    val elseTy = elseBlock.map(_.ty).getOrElse(Type.Unit)
    val tys    = (ctx.breakTys.toList :+ elseTy).distinct
    val joined = tys.foldLeft(Option(tys.head))((acc, t) => acc.flatMap(join(_, t)))

    if joined.isDefined then joined.get
    else if elseBlock.isEmpty then
      val v = ctx.breakTys.find(t => !Type.noValue(t)).get
      err(s"this loop breaks with a ${show(v)} but has no 'else' to give a value when it finishes normally — add an 'else'")
    else
      err(s"a loop's break values and its 'else' must have the same type, but got ${tys.map(show).mkString(" and ")}")
  }

  /** The result type of a `loop`, which has no `else` and so nothing on the normal-completion path
   * for its `break` values to meet: they decide it between them.
   *
   * A `loop` nothing breaks out of is `never`. That is the one thing `while true` could not say —
   * a condition the analyzer does not evaluate leaves it looking like a loop that might finish —
   * and it is what lets one stand as the last thing a function returning a value does.
   */
  protected def endlessResultType(ctx: LoopCtx): Type = {
    val tys = ctx.breakTys.toList.distinct

    if tys.isEmpty then Type.Never
    else
      tys.foldLeft(Option(tys.head))((acc, t) => acc.flatMap(join(_, t))).getOrElse {
        err(s"a loop's break values must have the same type, but got ${tys.map(show).mkString(" and ")}")
      }
  }

  /** Analyzes one statement as its own recovery region, so a mistake costs the statement it is
   * in and nothing after it.
   *
   * The statement it stands in for is a no-op: nothing is emitted from a program that has an
   * error, so the substitute only has to be well-formed enough for the walk to continue.
   */
  protected def recoverStmt(stmt: Stmt): List[TStmt] = {
    // A statement abandoned part-way may have opened a scope or entered a loop that it never got
    // to close, so both are wound back to where the statement started. Without this an inner
    // block's bindings would stay visible after it, and the analyzer would be *more* permissive
    // after an error than before one.
    val depth = scopes.length
    val outer = loops

    recover {
      while scopes.length > depth do popScope()
      loops = outer
      bindFailed(stmt)
      List(TExprStmt(TUnitLit()))
    }(analyzeStmt(stmt))
  }

  /** Keeps what the rest of the block will need from a statement that failed.
   *
   * A `var` binds its name even when its initializer did not analyze — at `Type.Unknown`, which
   * poisons rather than reports — because otherwise one bad initializer turns every later use of
   * the name into an "undefined name" of its own, and the real mistake is lost among them.
   */
  private def bindFailed(stmt: Stmt): Unit = stmt match
    case VarDecl(name, _, _)    => declare(name, Type.Unknown)
    case ValDecl(name, _, _, _) => declareReadOnly(name, Type.Unknown)
    case MultiDecl(names, mutable, _) =>
      for n <- names do if mutable then declare(n, Type.Unknown) else declareReadOnly(n, Type.Unknown)
    case _                      =>

  private def analyzeStmt(stmt: Stmt): List[TStmt] = at(stmt.pos)(analyzeStmtAt(stmt))

  /** `a, b = b, a` and `a, b += 1, 2` (`00 §2`).
   *
   * Every place is analyzed before any value, which is what fixes each value's expected type: a
   * literal on the right takes the width of the place it is heading for, exactly as it does after a
   * single `=`. Each arm is then checked by the rule its own operator already has — the plain form
   * wants a value the place can hold, the compound one wants an operator that does not change the
   * place's type — so the form adds no rule of its own beyond the two sides being the same length.
   * The ordering it promises is a run-time matter and belongs to codegen.
   */
  /** The values a comma form on the left is given, where the right side is **one** thing carrying
   * several (`00 §13`) — the third of the three feeds one comma syntax has.
   *
   * The one thing is evaluated once, into a name no program can write, and each part is read back
   * out of it. That is what keeps `a, b = f()` a single call, and it costs the form nothing else:
   * the hidden binding is an ordinary local, so it is counted, released and walked exactly as a
   * written one is.
   *
   * `None` where the right side is a list rather than one carrier, which is every other case.
   */
  private def spread(places: Int, values: List[Expr], subject: String): Option[(TStmt, List[TExpr])] =
    values match
      case List(one) if places > 1 =>
        val tv = at(one.pos)(analyzeMulti(one))

        Type.underlying(tv.ty) match
          case t: Type.Tuple if t.targs.length == places =>
            val name = declare(s"${Modules.sep}parts", tv.ty)
            val read = t.fields.indices.toList.map(i => TField(TLoad(name, tv.ty), i, t.fields(i)._2))

            Some((TVarDecl(name, tv.ty, tv), read))

          // A result list and a tuple are both taken apart here, and each is complained about in
          // its own terms: a call that yields several things has *results*, and the type nobody
          // wrote is not worth naming back at the reader.
          case t: Type.Tuple if yieldsResults(tv) =>
            at(one.pos)(err(s"$subject, and this yields ${quantity(t.targs.length, "result")}"))

          case t: Type.Tuple =>
            at(one.pos)(err(s"$subject, and a ${show(tv.ty)} has ${quantity(t.targs.length, "part")} " +
              "to give them"))

          case other =>
            at(one.pos)(err(s"$subject, and one ${show(other)} is not something to take apart — " +
              "only a tuple is"))
      case _ => None

  /** Whether a value came from a call declaring a **result list** rather than one type. */
  private def yieldsResults(t: TExpr): Boolean = t match
    case c: TCall  => c.results
    case c: TVCall => c.results
    case _         => false

  private def multiAssign(m: MultiAssign): List[TStmt] = {
    val taken =
      spread(m.targets.length, m.values, s"this assignment has ${m.targets.length} places on the left")

    if taken.isEmpty && m.targets.length != m.values.length then
      err(s"this assignment has ${m.targets.length} places on the left and ${m.values.length} " +
        s"${if m.values.length == 1 then "value" else "values"} on the right")

    // `b[i] = v` on a container is a call to `index_set` rather than a store (`14`), so it has no
    // address for the phase that locates the places to find and no reading of it separates what it
    // reads from what it writes. Saying that is worth more than the "needs something with an
    // address" the ordinary path would reach.
    for t <- m.targets do
      t match
        case Index(receiver, _) if indexes("IndexSet", receiver) =>
          at(t.pos)(err("an element set through 'Index' is a call rather than a store, so it cannot be " +
            "one place of a multiple assignment — write that one on its own"))
        case _ =>

    val what   = if m.op == "=" then "assignment" else s"'${m.op}'"
    val places = m.targets.map(analyzePlace(_, what))

    // A part read out of a carrier is already analyzed, so what is left for the arm is the check
    // the written form performs after analyzing its own value. The two are kept together here so
    // that neither feed can end up with a rule the other does not have.
    def value(target: Expr, place: TExpr, part: Option[TExpr], written: Expr): TWrite =
      if m.op == "=" then
        val tv = part.getOrElse(analyzeExpr(written, Some(place.ty)))

        if tv.ty == Type.Never || disagree(tv.ty, place.ty) then
          err(s"cannot assign ${show(tv.ty)} to ${describe(target)} of type ${show(place.ty)}")
        TWrite(place, m.op, tv, None, invCheckFor(place))
      else
        val binSym = m.op.dropRight(1)
        val tv     = part.getOrElse(analyzeExpr(written, updateExpected(binSym, place.ty)))
        val d      = updateDispatch(binSym, place, tv)

        if d.isEmpty && arithType(binSym, place.ty, tv.ty) != place.ty then
          err(s"'${m.op}' would change the type of ${describe(target)}")
        TWrite(place, m.op, tv, d, invCheckFor(place))

    // Each arm's position is the value it was written with, or — where one carrier supplied them
    // all — that carrier, since there is no separate expression to point at.
    val written = taken.fold(m.values)(_ => List.fill(m.targets.length)(m.values.head))
    val parts   = taken.fold(m.values.map(_ => None))(_._2.map(Some(_)))

    val writes =
      for (((target, place), part), w) <- m.targets.zip(places).zip(parts).zip(written)
      yield at(w.pos)(value(target, place, part, w))

    taken.map(_._1).toList :+ TMultiAssign(writes)
  }

  /** `val a, b = …` / `var a, b = …` (`00 §2`).
   *
   * The names are declared only once every value has been analyzed, so a value may still name
   * whatever the enclosing scope calls one of them — the binding does not shadow itself half way
   * through its own right-hand side.
   */
  private def multiDecl(m: MultiDecl): List[TStmt] = {
    for (n, i) <- m.names.zipWithIndex do
      if m.names.indexOf(n) != i then err(s"'$n' is named twice in one binding")

    val taken = spread(m.names.length, m.values, s"this binding names ${m.names.length} things")

    if taken.isEmpty && m.names.length != m.values.length then
      err(s"this binding names ${m.names.length} things and has ${m.values.length} " +
        s"${if m.values.length == 1 then "value" else "values"} to give them")

    val tvs = taken.fold {
      for v <- m.values
      yield
        val tv = at(v.pos)(analyzeExpr(v, None))
        if tv.ty == Type.Never then at(v.pos)(err("cannot bind a name to an expression that never returns"))
        tv
    }(_._2)

    taken.map(_._1).toList ::: (
      for (name, tv) <- m.names.zip(tvs)
      yield TVarDecl(if m.mutable then declare(name, tv.ty) else declareReadOnly(name, tv.ty), tv.ty, tv)
    )
  }

  /** Most statements are one statement. The two comma forms are the exception, and the only reason
   * this hands back a list: a binding that names several things is several declarations.
   */
  private def analyzeStmtAt(stmt: Stmt): List[TStmt] = stmt match
    case m: MultiAssign => multiAssign(m)
    case m: MultiDecl   => multiDecl(m)

    // An import binds a name for the statements after it and emits nothing. It is read here rather
    // than gathered ahead of the block because it takes effect where it is written, as Scala's
    // does — the code above it has not imported anything.
    case i: ImportDecl =>
      importInBlock(i)
      List(TExprStmt(TUnitLit()))

    case VarDecl(name, typOpt, Some(init)) =>
      val declared = typOpt.map(rt)
      val ti       = analyzeExpr(init, declared)
      // A binding needs a value to hold, and an initializer that does not finish never produces
      // one — the code after it is unreachable, so the declaration is a mistake rather than a
      // clever way to spell divergence.
      if ti.ty == Type.Never then err(s"cannot bind '$name' to an expression that never returns")
      val declTy = declared.getOrElse(ti.ty)
      if declared.isDefined && disagree(ti.ty, declTy) then
        err(s"cannot initialize '$name': declared ${show(declTy)} but the value is ${show(ti.ty)}")
      List(TVarDecl(declare(name, declTy), declTy, ti))

    // A local `val` is a `var` that may not be assigned to again — same frame, same lifetime, same
    // code. Only the binding differs, so the two share everything below this line, and the read-only
    // half of the rule is enforced where an assignment target is checked rather than here.
    case ValDecl(name, typOpt, init, _) =>
      val declared = typOpt.map(rt)
      val ti       = analyzeExpr(init, declared)
      if ti.ty == Type.Never then err(s"cannot bind '$name' to an expression that never returns")
      val declTy = declared.getOrElse(ti.ty)
      if declared.isDefined && disagree(ti.ty, declTy) then
        err(s"cannot initialize '$name': declared ${show(declTy)} but the value is ${show(ti.ty)}")
      List(TVarDecl(declareReadOnly(name, declTy), declTy, ti))

    case VarDecl(name, typOpt, None) =>
      val ty = typOpt.map(rt).getOrElse(err(s"'$name' needs either a type or an initial value"))
      if !hasZero(ty) then err(s"${show(ty)} has no zero value, so '$name' needs an initial value")
      List(TVarDecl(declare(name, ty), ty, TZero(ty)))

    // A statement's value is nobody's, so whatever branching it contains is analyzed knowing that.
    case ExprStmt(e) =>
      List(TExprStmt(analyzeExpr(e, None, discarded = true)))

    case Return(opt) =>
      val tv = opt.map(e => if retIsList then analyzeMulti(e, Some(retTy)) else analyzeExpr(e, Some(retTy)))
      tv match
        case Some(_) if retTy == Type.Unit    => err("cannot return a value from a function with no return type")
        case Some(t) if disagree(t.ty, retTy) => err(s"return type mismatch: expected ${show(retTy)}, got ${show(t.ty)}")
        case None if retTy != Type.Unit       => err(s"this function must return a ${show(retTy)} value")
        case _                                =>
      List(TReturn(tv))

    // `break value` records its type against the loop it targets — the nearest, or the one a
    // `'label` names — which unites it with that loop's other breaks and its `else` to fix the
    // loop's result type. The value is analyzed in the target loop's expected type, so a `break &T`
    // boxes the same way a `break` in a `&T` context asks.
    case Break(label, opt) =>
      val (ctx, depth) = resolveLoop("break", label)
      val tv           = opt.map(e => analyzeExpr(e, ctx.expected))
      ctx.breakTys += tv.map(_.ty).getOrElse(Type.Unit)
      List(TBreak(tv, depth))

    case Continue(label) =>
      val (_, depth) = resolveLoop("continue", label)
      List(TContinue(depth))

    // A constant is a declaration that was hoisted and folded into its uses (`13 §7`), so where the
    // walk meets one among the entry point's statements there is nothing left to run.
    case _: ConstDecl => List(TExprStmt(TUnitLit()))

    case _: FuncDecl | _: StructDecl | _: EnumDecl | _: TraitDecl | _: ImplDecl | _: ExternDecl | _: TypeDecl =>
      err("functions, structs, enums, traits, impls, externs, and types may only be declared at the top level")

    // The leading clauses of a function body are split off before the body is analyzed, so any
    // that reach here sit after another statement or inside an inner block — both disallowed.
    case _: Require | _: Ensure =>
      err("'require'/'ensure' clauses must come before any other statement in a function body")

}
