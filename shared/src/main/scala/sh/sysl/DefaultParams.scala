package sh.sysl

/** What a parameter's default has to satisfy at the **declaration** (`12 §2a`).
 *
 * A default is filled at the call, so without this pass everything wrong with one would be reported
 * at whichever call first left the argument out — a mistake in a signature, reported in somebody
 * else's file, and never reported at all for a default nothing happens to take. So each is analyzed
 * here exactly once, in the terms it was written in, whether or not any call uses it. That is the
 * same reason a `val`'s initializer and a `const`'s value are checked where they are written rather
 * than where they are read.
 *
 * The shape rules — a suffix, no variadic, no `impl` — are checked first and separately, because
 * each of them makes the parameter list unreadable rather than the default wrong, and a list that
 * cannot be read has nothing to say about the expressions in it.
 */
trait DefaultParams extends StmtAnalysis with SignatureVisibility {

  /** Every parameter list that may carry a default, checked once each. */
  protected def checkValueDefaults(): Unit = {
    for (key, f) <- funcDecls.toList do
      inScope(scopeFor(key))(check(qn(key), Some(key), f.params, f.variadic, f.tparams.nonEmpty, typed(key)))

    for ((tname, mname), m) <- memberDecls.toList do
      inDecl(tname)(check(s"${qn(tname)}.$mname", Some(tname), m.params, m.variadic,
        m.tparams.nonEmpty || typeIsGeneric(tname), _ => None))

    // An `impl` block's members are the one kind refused outright, so they are not checked for
    // anything else — the refusal is the whole of what this has to say about them.
    for (scope, impl) <- implDecls.toList; m <- impl.methods; p <- m.params if p.default.isDefined do
      inScope(scope)(recover(())(at(p.default.get.pos)(err(
        s"a member of an 'impl' block declares no default — a call through a trait object holds " +
          s"something that has forgotten which type it is, so there is no implementation to read " +
          s"one off. Declare it on trait '${impl.traitName}', where every implementation shares it"))))
  }

  /** One parameter list. `expectedAt` says what type an argument at a position would be checked
   * against, where that is cheap to know; a default is analyzed against nothing where it is not,
   * which still holds it to naming something that exists and to naming nothing local.
   */
  private def check(
      shown: String,
      owner: Option[String],
      params: List[Param],
      variadic: Boolean,
      generic: Boolean,
      expectedAt: Int => Option[Type],
  ): Unit = {
    if params.exists(_.default.isDefined) then
      // C reads a variadic call's tail relative to the last named argument (`12 §9`), so an argument
      // that might be the last declared parameter or might be the first of the tail leaves nowhere
      // for the tail to begin.
      if variadic then
        for p <- params.find(_.default.isDefined) do
          recover(())(at(p.default.get.pos)(err(
            s"$shown takes a '...', and a parameter list with a tail declares no default — where the " +
              s"tail begins would then depend on how many arguments a call chose to write")))
      else
        val first = params.indexWhere(_.default.isDefined)

        for p <- params.drop(first + 1) if p.default.isEmpty do
          recover(())(at(p.pos)(err(
            s"'${p.name}' has no default and comes after '${params(first).name}', which has one — a " +
              s"call writes its arguments in order, so nothing could leave out '${params(first).name}' " +
              s"and still supply '${p.name}'")))

    // A default already wrapped in its own scope is a **copy** of a trait's, carried onto an `impl`
    // block's method. It is checked once at the trait that wrote it, and checking it again here
    // would report one mistake once per implementing type.
    for (p, i) <- params.zipWithIndex; d <- p.default if !d.isInstanceOf[DefaultArg] do
      recover(())(at(d.pos)(sandboxed {
        resetFunction()
        // Analyzed exactly as the call that takes it will analyze it: in this declaration's terms
        // and with nothing local in scope, so a default naming a parameter is undefined here and
        // says so rather than finding a caller's variable of that name.
        val t = inDefault(owner)(analyzeExpr(d, if generic then None else expectedAt(i)))

        for want <- (if generic then None else expectedAt(i)) if disagree(t.ty, want) do
          err(s"the default for '${p.name}' is ${show(t.ty)}, and the parameter is ${show(want)}")

        for key <- owner do exposed(shown, key, p.name, t)
      }))
  }

  /** A default is the one part of a signature a call does not write, so what it names has to reach
   * as far as the declaration does (`13 §2`) — otherwise a caller who leaves the argument out has
   * had a call made on their behalf that they could neither have written nor can see.
   *
   * The names are the ones the default reaches **directly**: what a function it calls does inside
   * itself is that function's business and restricts nothing here. Passing no functions to the walk
   * is what stops it following them.
   *
   * A member is held to its **type's** reach. A member may restrict itself further than its type
   * does, so this is the weaker of the two questions — but it is the one that matches how the rest
   * of `13 §2` reads a member, and the stricter half belongs with it rather than here.
   */
  private def exposed(shown: String, key: String, param: String, t: TExpr): Unit = {
    val refs = Reachability.reachedFrom(List(t), Nil, Nil)
    val mine = reachOf(key)

    for named <- (refs.calls ++ refs.vals).toList.sorted if !covers(reachOf(named), mine) do
      err(s"the default for '$param' names '${qn(named)}', which does not reach as far as $shown " +
        s"does — a caller that leaves the argument out would be given something they could not " +
        s"have written")
  }

  /** The parameter types of a declaration whose signature is already resolved, which is every
   * non-generic function and `extern`. A generic one has none to give: its parameters are written
   * in terms nothing has fixed yet.
   */
  private def typed(key: String)(i: Int): Option[Type] =
    funcInsts.get(key).map(_._1).filter(i < _.length).map(_(i)._2)

  private def typeIsGeneric(tname: String): Boolean =
    structDecls.get(tname).exists(_.tparams.nonEmpty) ||
      enumDecls.get(tname).exists(_.tparams.nonEmpty) ||
      traitDecls.get(tname).exists(_.tparams.nonEmpty)

}
