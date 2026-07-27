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
        val init = initRev.reverse.map(recoverStmt)
        val tr   = analyzeExpr(e, expected, discarded)
        TBlock(init, Some(tr), if discarded && tr.ty != Type.Never then Type.Unit else tr.ty)
      case (_: Return | _: Break | _: Continue) :: _ =>
        TBlock(stmts.map(recoverStmt), None, Type.Never)
      case _ =>
        TBlock(stmts.map(recoverStmt), None, Type.Unit)

  /** A statement sequence used only for its effects (a loop body): a fresh scope, no value. */
  protected def analyzeStmts(stmts: List[Stmt]): List[TStmt] = {
    pushScope()
    val r = stmts.map(recoverStmt)
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
  protected def recoverStmt(stmt: Stmt): TStmt = {
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
      TExprStmt(TUnitLit())
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
    case _                      =>

  private def analyzeStmt(stmt: Stmt): TStmt = at(stmt.pos)(analyzeStmtAt(stmt))

  private def analyzeStmtAt(stmt: Stmt): TStmt = stmt match
    // An import binds a name for the statements after it and emits nothing. It is read here rather
    // than gathered ahead of the block because it takes effect where it is written, as Scala's
    // does — the code above it has not imported anything.
    case i: ImportDecl =>
      importInBlock(i)
      TExprStmt(TUnitLit())

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
      TVarDecl(declare(name, declTy), declTy, ti)

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
      TVarDecl(declareReadOnly(name, declTy), declTy, ti)

    case VarDecl(name, typOpt, None) =>
      val ty = typOpt.map(rt).getOrElse(err(s"'$name' needs either a type or an initial value"))
      if !hasZero(ty) then err(s"${show(ty)} has no zero value, so '$name' needs an initial value")
      TVarDecl(declare(name, ty), ty, TZero(ty))

    // A statement's value is nobody's, so whatever branching it contains is analyzed knowing that.
    case ExprStmt(e) =>
      TExprStmt(analyzeExpr(e, None, discarded = true))

    case Return(opt) =>
      val tv = opt.map(analyzeExpr(_, Some(retTy)))
      tv match
        case Some(_) if retTy == Type.Unit    => err("cannot return a value from a function with no return type")
        case Some(t) if disagree(t.ty, retTy) => err(s"return type mismatch: expected ${show(retTy)}, got ${show(t.ty)}")
        case None if retTy != Type.Unit       => err(s"this function must return a ${show(retTy)} value")
        case _                                =>
      TReturn(tv)

    // `break value` records its type against the loop it targets — the nearest, or the one a
    // `'label` names — which unites it with that loop's other breaks and its `else` to fix the
    // loop's result type. The value is analyzed in the target loop's expected type, so a `break &T`
    // boxes the same way a `break` in a `&T` context asks.
    case Break(label, opt) =>
      val (ctx, depth) = resolveLoop("break", label)
      val tv           = opt.map(e => analyzeExpr(e, ctx.expected))
      ctx.breakTys += tv.map(_.ty).getOrElse(Type.Unit)
      TBreak(tv, depth)

    case Continue(label) =>
      val (_, depth) = resolveLoop("continue", label)
      TContinue(depth)

    // A constant is a declaration that was hoisted and folded into its uses (`13 §7`), so where the
    // walk meets one among the entry point's statements there is nothing left to run.
    case _: ConstDecl => TExprStmt(TUnitLit())

    case _: FuncDecl | _: StructDecl | _: EnumDecl | _: TraitDecl | _: ImplDecl | _: ExternDecl | _: TypeDecl =>
      err("functions, structs, enums, traits, impls, externs, and types may only be declared at the top level")

    // The leading clauses of a function body are split off before the body is analyzed, so any
    // that reach here sit after another statement or inside an inner block — both disallowed.
    case _: Require | _: Ensure =>
      err("'require'/'ensure' clauses must come before any other statement in a function body")

}
