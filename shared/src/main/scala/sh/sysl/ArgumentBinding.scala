package sh.sysl

/** Turning what a call wrote into the positional argument list a declaration takes (`12 §2a`).
 *
 * Two things a call may leave to the declaration — an argument's **value**, where the parameter
 * declares a default, and an argument's **position**, where the call names the parameter instead —
 * and both are resolved here, once, before anything else looks at the arguments. What every call
 * form downstream receives is therefore the list it would have received had the call been written
 * out in full: same length, same order, one argument per parameter. Arity checking, generic
 * solving, `checkArgs`, ARC placement and the emitter are all unchanged by this feature, which is
 * the point of doing it in one place.
 *
 * The fast path matters as much as the slow one. A call that wrote no name to a declaration that
 * declares no default is returned **untouched**, so the diagnostics for an ordinary miscounted call
 * are still the ones `checkArity` gives, phrased as they always were.
 */
trait ArgumentBinding extends TraitLookup {

  /** Binds `args` to `params`, filling defaults and placing named arguments.
   *
   * `owner` is the key of the declaration the parameters were written in — the scope a default is
   * analyzed under, since a default names what its own file names wherever it is called from.
   *
   * `params` is the parameters the arguments correspond to, so a member's receiver is not among
   * them: the caller drops it before asking, exactly as it drops it before counting.
   */
  protected def bindArgs(
      shown: String,
      owner: Option[String],
      params: List[Param],
      args: List[Expr],
      variadic: Boolean = false,
  ): List[Expr] = {
    val names = args.collect { case n: NamedArg => n }

    val bound =
      if names.isEmpty && !params.exists(_.default.isDefined) then args
      else bind(shown, owner, params, args, variadic, names)

    thunked(params, bound)
  }

  /** Wraps each argument standing at a by-name parameter in the closure the parameter's type asks
   * for (`12 § A parameter may be passed by name`).
   *
   * **This is the whole of the feature, and it is deliberately a desugar over the untyped tree.**
   * `x: -> T` is typed `Fn() -> T`, so an argument that arrives as a closure is a thing the analyzer
   * already knows how to bind, monomorphize and inline; turning the expression into that closure
   * here means nothing downstream learns a new shape. What the reader gets is the call site: the
   * argument is not evaluated where it is written, and the body evaluates it at each use.
   *
   * It runs after binding rather than before, so a by-name parameter reached by name or filled from
   * a default is thunked exactly as a positional one is — the argument lists have been made to
   * correspond by then, which is the only point at which a parameter and its argument are known to
   * be a pair.
   *
   * A defaulted by-name parameter is thunked here too, so the default is an expression the callee
   * evaluates at each use rather than one the caller evaluated before the call.
   */
  private def thunked(params: List[Param], args: List[Expr]): List[Expr] =
    if !params.exists(_.byName) then args
    else
      args.zipWithIndex.map { (a, i) =>
        // A variadic call has arguments past the last declared parameter, and those stand at no
        // parameter at all — `lift` is what says so rather than an index check written out.
        params.lift(i) match
          case Some(p) if p.byName => Lambda(Nil, List(ExprStmt(a))).setPos(a.pos)
          case _                   => a
      }

  private def bind(
      shown: String,
      owner: Option[String],
      params: List[Param],
      args: List[Expr],
      variadic: Boolean,
      names: List[NamedArg],
  ): List[Expr] = {
    // A named argument gives up its position, so nothing after one has a position left to mean
    // anything. Reported against the offending positional argument rather than the name that
    // preceded it, since the one to move is the one being pointed at.
    // A call reaches here with no name at all whenever the callee declares a default, so the
    // "everything from here on is named" boundary sits past the end in that case rather than at -1.
    val firstNamed = args.indexWhere(_.isInstanceOf[NamedArg]) match
      case -1 => args.length
      case i  => i

    for stray <- args.drop(firstNamed).find(!_.isInstanceOf[NamedArg]) do
      at(stray.pos)(err(s"this argument comes after one written by name, so there is no position " +
        s"left for it to stand at — give it its parameter's name too"))

    val positional = args.take(firstNamed)
    val byName     = params.drop(positional.length).map(_.name).toSet

    // Every name has to be a parameter's, and one a positional argument already filled is as much a
    // second value for that parameter as writing the name twice would be.
    for n <- names do
      if !params.exists(_.name == n.name) then
        at(n.pos)(err(s"$shown declares no parameter named '${n.name}'" +
          (if params.isEmpty then "" else s" — it takes ${conjoin(params.map(p => s"'${p.name}'"))}")))
      else if !byName(n.name) then
        at(n.pos)(err(s"'${n.name}' was already given by position, so this is a second value for " +
          s"one parameter"))

    for dup <- names.groupBy(_.name).values.find(_.length > 1) do
      at(dup.last.pos)(err(s"'${dup.head.name}' is given twice"))

    // Past the declared parameters a variadic's tail begins, and nothing there has a parameter to be
    // matched against — which is why a variadic list may declare no default, and so why the tail is
    // whatever positional arguments are left over rather than something this has to reason about.
    if positional.length > params.length && !variadic then
      err(s"$shown ${arity(params)}, but ${supplied(args.length, "argument")}")

    val written = names.map(n => n.name -> n.value).toMap
    val tail    = positional.drop(params.length)

    val filled = params.zipWithIndex.map { (p, i) =>
      if i < positional.length then Some(positional(i))
      else written.get(p.name).orElse(p.default.map(scoped(owner, _)))
    }

    // Named all at once: a call that left out three parameters has one mistake, not three, and the
    // fix is to look at the signature — which the message puts in front of them.
    val missing = params.zip(filled).collect { case (p, None) => s"'${p.name}'" }

    if missing.nonEmpty then
      err(s"$shown was given no value for ${conjoin(missing)}, and ${
          if missing.length == 1 then "that parameter declares" else "those parameters declare"
        } no default")

    filled.flatten ::: tail
  }

  /** A default wrapped in the scope it is to be read in, and positioned at the call it is being
   * filled at.
   *
   * One that already carries a scope keeps it and is not wrapped again: it is a **trait's** default,
   * copied onto an implementing type's method, and the trait is where it was written however far
   * from it the implementation sits. Wrapping twice would also make the same written default appear
   * to be filled inside itself, which is precisely what the cycle guard exists to refuse.
   *
   * **The wrapper's own position is the call's, and the expression inside it keeps the
   * declaration's.** Those are the two places a default belongs to (`12 §2a`) and both are wanted:
   * the inner one is where a complaint about the default goes, since that is where it was written,
   * and the outer one is what `__FILE__` and `__LINE__` report, since a default stands where the
   * argument would have. Nothing needed to tell them apart until a built-in could ask.
   *
   * A trait's copy is re-wrapped by `copy()` rather than assigned to, because `setPos` keeps the
   * first position a node is given and that node is shared by every call to the method — the copy is
   * what lets two call sites in different files each report their own.
   */
  private def scoped(owner: Option[String], d: Expr): Expr = d match
    case already: DefaultArg => already.copy().setPos(currentPos.orElse(already.pos))
    case _                   => DefaultArg(owner, d).setPos(currentPos.orElse(d.pos))

  /** How many arguments a declaration takes, said in a phrase that accounts for its defaults — the
   * value-level twin of `arityPhrase`, and worded the same way for the same reason.
   */
  private def arity(params: List[Param]): String = {
    val least = params.count(_.default.isEmpty)

    if least == params.length then s"takes ${quantity(params.length, "argument")}"
    else s"takes between $least and ${params.length} arguments"
  }

  /** How many arguments a call is obliged to write: the parameters, less the ones a default stands
   * in for. `checkArity` is asked for this rather than for the parameter count wherever a
   * declaration may carry defaults, so the floor it enforces is the real one.
   */
  protected def leastValueArgs(params: List[Param]): Int = params.count(_.default.isEmpty)

}
