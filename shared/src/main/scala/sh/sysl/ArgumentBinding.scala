package sh.sysl

/** The two things a trailing block may turn out to be, decided by the parameter it stands at.
 *
 * There is no third, and that is the design rather than a stage it has reached: a block is a
 * sequence of expressions, and either they are the elements of a collection or they are the body of
 * a closure. Swift needs a result builder here because a Swift block holds *statements*; a sysl
 * block holds expressions, and `if` is already one whose arms must agree in type.
 */
private enum BlockReading:
  case Collection, Callable

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

    blocked(params, thunked(params, bound))
  }

  /** Turns each trailing block into what the parameter it stands at asks for (`reference/
   * expressions.md § A trailing block`).
   *
   * **A block is written before anything knows what it is**, and this is the first place that can
   * be answered: the lists have been made to correspond by now, so the block and its parameter are
   * a pair. A parameter whose type is a collection reads the block as an array of its lines; one
   * whose type is a callable reads it as a closure over them. Nothing else takes one, and a
   * parameter that is neither is told so here rather than left to a complaint about a node the
   * reader never wrote.
   *
   * It runs **after** [[thunked]] and not before, which is what keeps a by-name parameter from
   * being wrapped twice: a by-name parameter's type is `Fn() -> T`, so a block at one is a closure
   * by the rule below, and a thunk around that closure would be a second one. [[thunked]] skips a
   * block for exactly that reason and leaves it to this.
   */
  private def blocked(params: List[Param], args: List[Expr]): List[Expr] =
    if !args.exists(_.isInstanceOf[BlockArg]) then args
    else
      args.zipWithIndex.map {
        case (b: BlockArg, i) => at(b.pos)(fill(params.lift(i), b))
        case (a, _)           => a
      }

  private def fill(param: Option[Param], b: BlockArg): Expr =
    param.map(p => (p, if p.byName then Some(BlockReading.Callable) else blockReading(p.typ))) match
      // A variadic's tail stands at no parameter, so there is nothing to read the block against —
      // and past the declared parameters there never will be.
      case None =>
        err("there is no parameter for this trailing block to stand at — a block fills the " +
          "parameter its position gives it, exactly as an argument written in the parentheses does")

      case Some((_, Some(BlockReading.Collection))) => ArrayLit(b.body.map(line)).setPos(b.pos)
      case Some((_, Some(BlockReading.Callable)))   => Lambda(Nil, b.body).setPos(b.pos)

      case Some((p, None)) =>
        err(s"a trailing block stands at '${p.name}', which is a '${p.typ.show}' — a block fills a " +
          "collection parameter as an array of its lines, or a callable parameter as a closure " +
          "over them, and this is neither")

  /** One line of a block that is being read as an array literal.
   *
   * **A block is a list of its lines, so each one has to be a value** — which is where the form
   * stops short of Swift's result builders, and stops short of them deliberately
   * (`reference/expressions.md § A trailing block`). A binding declares a name rather than
   * producing an element, so it has nothing to contribute to a list.
   *
   * **A loop is refused by name, and it is the one refusal here that is a choice rather than a
   * consequence.** A loop in sysl *is* an expression, so one written here would otherwise be an
   * ordinary line — and would contribute the single `unit` it evaluates to, which is never what
   * somebody writing `for` inside a list of views meant. What they meant is Swift's `buildArray`,
   * and refusing the result builder refused that with it. So the sentence says where the loop goes
   * instead, rather than leaving the reader with a true remark about `unit`.
   *
   * A loop carrying an `else` can yield something other than `unit` and is refused with the rest.
   * One rule with no sub-cases is cheaper to teach than the exception would be worth, and a value
   * built by a loop has somewhere better to be bound anyway.
   */
  private def line(s: Stmt): Expr = s match
    case ExprStmt(_: For | _: ConstFor | _: While | _: DoWhile | _: Loop | _: CFor) =>
      at(s.pos)(err("a loop inside a trailing block filling a collection would contribute one " +
        "element and not one per iteration, so it is refused rather than read that way — build " +
        "the elements into a 'Buf' before the call and pass a view of it"))

    case ExprStmt(e) => e

    case other =>
      at(other.pos)(err("every line of a trailing block filling a collection is one of its " +
        "elements, so it has to be a value — this one declares a name instead. A binding goes " +
        "outside the block, which is then given what it built"))

  /** Which of the two readings a parameter's written type asks for, or neither.
   *
   * It is asked of the type **as written**, which is what makes the answer available before
   * anything has been analyzed — and what a block needs, since it is the argument the analysis
   * would otherwise be looking at. A mode reaching the type says nothing about which reading is
   * meant, so `&Fn() -> unit` and `Fn() -> unit` answer alike.
   *
   * **The bare arrow is the one spelling that is no longer written by the time this is asked.**
   * `f: () -> int` is rewritten into a bounded type parameter before any call is analyzed
   * (`MemberLowering.callBounds`), so what arrives here is an ordinary named type — and it is the
   * most likely spelling of a callable parameter there is. `isCallBound` is what recovers it; a
   * type parameter a *program* bounded by `Fn` is not recognized, and gets the sentence below
   * naming both readings.
   */
  private def blockReading(t: TypeRef): Option[BlockReading] = t match
    case RefType(inner, _)   => blockReading(inner)
    case PtrType(inner)      => blockReading(inner)
    case WeakType(inner)     => blockReading(inner)
    case VolatileType(inner) => blockReading(inner)
    case _: ArrayType        => Some(BlockReading.Collection)
    case _: FnType           => Some(BlockReading.Callable)
    case _: CFnType          => Some(BlockReading.Callable)
    case NamedType(n, _) if MemberLowering.isCallBound(n) => Some(BlockReading.Callable)
    case _                   => None

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
          // A trailing block at a by-name parameter is already the closure the thunk would build,
          // since a by-name parameter's type is `Fn() -> T` and [[blocked]] reads a block at a
          // callable as a closure over its lines. Wrapping here would put a second one around it.
          case Some(p) if p.byName && !a.isInstanceOf[BlockArg] =>
            Lambda(Nil, List(ExprStmt(a))).setPos(a.pos)
          case _ => a
      }

  private def bind(
      shown: String,
      owner: Option[String],
      params: List[Param],
      all: List[Expr],
      variadic: Boolean,
      names: List[NamedArg],
  ): List[Expr] = {
    // **A trailing block is a written argument with no position of its own**, so it is taken out of
    // the list before any of the rules below read one. It is not positional — a name written before
    // it must not strand it, which is the whole reason for the split, since `column(spacing = 4):`
    // is exactly what a caller reaches for. And it is not named, having no name to be written by.
    // What it fills is decided below, once everything that *does* have a position has one.
    val (args, block) = all match
      case init :+ (b: BlockArg) => (init, Some(b))
      case _                     => (all, None)

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
      err(s"$shown ${arity(params)}, but ${supplied(all.length, "argument")}")

    val written = names.map(n => n.name -> n.value).toMap
    val tail    = positional.drop(params.length)

    // What the call **wrote**, before a default stands in for anything. The block is placed against
    // this rather than against the filled list, because a block is a written argument and a default
    // is not: a parameter that has a default and no argument is still one the block may fill, and
    // reading the two lists in the other order would hand the block the parameter *after* it.
    val slots = params.zipWithIndex.map { (p, i) =>
      if i < positional.length then Some(positional(i)) else written.get(p.name)
    }

    // **A trailing block fills the first parameter no written argument filled**, which is the same
    // rule as "it is the last argument" wherever the call wrote no names, and is what makes it
    // still mean something where the call did. Nothing left for it to fill is the reader having
    // written a value for every parameter and then a block as well.
    val placed = block.fold(slots) { b =>
      slots.indexWhere(_.isEmpty) match
        case -1 => at(b.pos)(err(s"there is no parameter left for this trailing block to stand " +
          s"at — $shown ${arity(params)}, and the call has already written a value for each of them"))
        case i  => slots.updated(i, Some(b))
    }

    val filled = params.zip(placed).map { (p, s) => s.orElse(p.default.map(scoped(owner, _))) }

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
