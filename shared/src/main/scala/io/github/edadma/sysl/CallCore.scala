package io.github.edadma.sysl

/** What every call has in common, whatever is being called: matching arguments against a parameter
 * list, counting them, solving a generic callee's type arguments out of them, and passing a variadic
 * tail.
 *
 * It sits at the bottom of the call layering because the three things above it — a method, an
 * operator, a constructor — are all a free call underneath. The analyzer lowers every member to a
 * function under the mangled name `Type.member`, so a method call is a free call whose first
 * argument is the receiver, and an operator is a method call whose name came from a token. That is
 * why the checking lives here once rather than three times.
 *
 * Argument checking runs **provisionally** first where a callee is generic: the arguments have to be
 * analyzed to solve the type parameters, and solving them is what says whether the arguments were
 * right. `probe` is what makes that recoverable — an attempt that fails leaves no diagnostic behind,
 * so an overload set can be tried without the failures reaching the programmer.
 */
trait CallCore extends Literals with TraitObjects {

  /** Producing the receiver a method's `self` sigil asked for — by value, by address, or by
   * reference. Supplied by `CallAnalysis`, which is mixed in last: it is the one step every route to
   * a member ends in, and the routes are what the traits between here and there are.
   */
  protected def buildReceiver(mode: RecvMode, tr: TExpr): TExpr


  /** Type-checks positional arguments against a resolved parameter list. `pre` holds arguments
   * already analyzed during type-argument inference, so they are not analyzed twice.
   */
  protected def checkArgs(
      what: String,
      params: List[(String, Type)],
      args: List[Expr],
      pre: Option[List[TExpr]],
      positional: Boolean = false,
  ): List[TExpr] = {
    // An argument analyzed against a parameter that was still being solved was analyzed without an
    // expected type, so a value headed for a `&T` parameter is boxed here instead of at its own
    // analysis, and one headed for a trait object is erased here.
    //
    // A bare literal is the one case a coercion cannot repair, because a literal does not convert —
    // it *is* the type its position gives it (`01`). Where that position is a parameter mentioning
    // what was being solved, the type was not known until the solution was, so every literal
    // argument is analyzed **here**, against the parameter it turned out to have: `rotr(x, 7)` on
    // `rotr[T](x: T, n: T)` gives the `7` the `T` that the `x` settled. What inference held is a
    // stand-in carrying the literal's default type and nothing else, which is why this is
    // unconditional rather than a repair applied where the two disagree.
    //
    // `pre` may carry a receiver the argument list does not, so the two are aligned from the right.
    val ts = pre match
      case Some(provisional) =>
        val srcs = List.fill(provisional.length - args.length)(None) ::: args.map(Some(_))

        provisional.zip(srcs.padTo(provisional.length, None)).zip(params).map {
          case ((t, src), (_, pty)) =>
            if src.exists(isLiteral) then analyzeExpr(src.get, Some(pty)) else coerce(t, pty)
        }
      case None => args.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }

    // The complaint is about one argument, so it is reported where that argument is written
    // rather than at the call as a whole.
    //
    // A `unit` parameter is legal and costs nothing — it is zero-sized, so it is dropped from the
    // signature and the argument is evaluated for its effect alone. `never` is the one that cannot
    // be a parameter, and inference is the only place it can be caught: a generic parameter accepts
    // whatever the argument's type is, and an expression that never arrives is the one thing
    // inference must not conclude a slot from. A diverging argument against a *written* parameter
    // is untouched — it is dead code, and that parameter still has a layout.
    // A callable's parameters have no names a program wrote — the call trait's are the prelude's —
    // so what a mismatch there names is the *position*, which is the only thing the caller can see.
    for ((t, (pname, pty)), i) <- ts.zip(params).zipWithIndex do
      val which = if positional then s"the ${ordinal(i + 1)} argument of $what" else s"'$pname' of '$what'"

      at(t.pos):
        if disagree(t.ty, pty) then err(s"$which is ${show(pty)}, but ${show(t.ty)} was given")
        else if pty == Type.Never then err(s"cannot pass an expression that never returns as $which")

    ts
  }

  /** The arguments of a generic call, analyzed once for the inference that follows — each against
   * its parameter's type wherever that type is already known.
   *
   * A parameter naming none of the parameters being solved has nothing to contribute to the
   * solution and nothing left to wait for, so its argument is analyzed exactly as a non-generic
   * call's is. That is what keeps `01`'s rule — a parameter's type at a call fixes an unsuffixed
   * literal — true of a generic callee: `f[T](x: T, n: usize)` has no more to work out from the `7`
   * in `f(1u32, 7)` than a plain function has.
   *
   * A literal at a parameter that *does* name one is not analyzed at all: it stands in for its own
   * default type, `solve` consults it last, and `checkArgs` analyzes it once the parameter is a
   * type. The stand-in is a node for its type and nothing else, and never reaches the output.
   * Anything else at such a parameter is analyzed bare, because the type it would be checked
   * against is the thing being solved.
   *
   * The parameter types are the declaration's, so they are resolved where it was written; the
   * arguments are the caller's, and are analyzed where *they* were written.
   */
  protected def provisionalArgs(
      decl: String,
      tparams: List[String],
      ptypes: List[TypeRef],
      args: List[Expr],
      bounds: Map[String, List[BoundRef]] = Map.empty,
  ): List[TExpr] = {
    val tps  = tparams.toSet
    val want = inDecl(decl)(ptypes.map(r => Option.unless(mentions(r, tps))(resolveType(r, Map.empty))))
    val at   = args.zip(want.padTo(args.length, None))

    // A **callable** argument is the one shape that has no type of its own to be analyzed at: a
    // closure's parameters come from the context, and a bare function name is a callable only where
    // one is asked for. So the pass runs twice — everything else first, then the callables against
    // the bound that the first pass has by then made concrete.
    val first = at.map { (a, e) =>
      if callableArg(a) then None
      else
        Some(e match
          case Some(_) => analyzeExpr(a, e)
          case None =>
            literalDefault(a) match
              case Some(ty) => standIn(ty).setPos(a.pos)
              case None     => analyzeExpr(a))
    }

    // What the first pass settles. `map[A, B](xs: []A, out: []B, f: A -> B)` gets both from the two
    // slices, and only then is `Fn(A) -> B` a thing a closure can be read against — which is why
    // this is a partial solution rather than the real one, made here and thrown away.
    val partial = scala.collection.mutable.Map.empty[String, Type]

    for case (r, Some(t)) <- ptypes.zip(first) do inDecl(decl)(unify(r, t.ty, tps, partial))

    at.zip(first).zipWithIndex.map { case (((a, _), done), i) =>
      done.getOrElse(analyzeExpr(a, inDecl(decl)(callBound(ptypes.lift(i), tps, bounds, partial.toMap))))
    }
  }

  /** Whether an argument is one whose type the *context* has to supply (`12 §5`, `§6`). */
  private def callableArg(a: Expr): Boolean = a match
    case _: Lambda => true
    case Ident(n)  => lookupOpt(n).isEmpty && funcKey(n).isDefined
    case _         => false

  /** The call trait a callable argument is being asked for, read off the bound of the parameter it
   * stands at, under whatever the other arguments have already settled.
   *
   * `None` where the bound still names a parameter nothing has determined, which leaves the closure
   * to report that its parameters have no types — the honest answer, since they have none.
   */
  private def callBound(
      ptype: Option[TypeRef],
      tps: Set[String],
      bounds: Map[String, List[BoundRef]],
      partial: Map[String, Type],
  ): Option[Type] =
    for
      tp  <- ptype.collect { case NamedType(n, Nil) if tps(n) => n }
      ref <- bounds.getOrElse(tp, Nil).find(b => Type.Fn.isCall(b.name))
      if !ref.args.exists(r => mentions(r, tps -- partial.keySet))
      b = resolveBound(ref, partial)
    yield Type.Trait(b.name, b.args)

  /** A node that carries a type and no value, for a literal inference reads before anything has
   * analyzed it. `checkArgs` replaces every one of them.
   */
  private def standIn(ty: Type): TExpr = ty match
    case _: Type.Floating => TFloatLit("0x0p+0", ty)
    case _                => TIntLit(0, ty)

  protected def callFunction(f: FuncDecl, args: List[Expr], expected: Option[Type]): TExpr = {
    // A variadic callee — foreign or sysl's own — fixes only where its declared parameters stop;
    // everything after them is the tail, checked by the rule below rather than against a parameter.
    val variadic = f.variadic

    val shown = qn(f.name)

    checkArity(s"function '$shown'", f.params.length, variadic, args.length)

    // An extern is declared in the output only if something reaches it, which is what keeps an
    // unused one — the prelude's `exit`, in a program that never panics — out of the module.
    if externDecls.contains(f.name) then externsUsed += f.name

    val (name, pre) =
      if f.tparams.isEmpty then (f.name, None)
      else
        val provisional = provisionalArgs(f.name, f.tparams, f.params.map(_.typ), args, f.bounds)
        // The parameter types being matched against are the declaration's, written in the
        // declaration's terms — so a `Pair[T]` there is that module's `Pair` whichever module the
        // call was written in.
        val targs = inDecl(f.name)(
          solve(shown, f.tparams, f.params.map(_.typ), provisional.map(_.ty), f.retType, expected,
            args.map(isLiteral)))
        checkBounds(f, targs)
        (instantiateFunc(f, targs), Some(provisional))

    val (params, rtype) = funcInsts(name)
    // A variadic's tail has no declared parameter to be checked against and is analyzed below, so
    // both lists are cut to the parameters — which is also what keeps them aligned.
    val checked  = checkArgs(shown, params, args.take(params.length), pre.map(_.take(params.length)))
    val declared = externDecls.get(f.name).fold(checked)(vaPassed(checked, _))

    funcsUsed += name
    // Only a free function can be an `extern`, so this is the one call form whose tail may be a
    // foreign one — and the only one an aggregate may cross.
    val foreign = externDecls.contains(f.name)

    TCall(name, declared ::: args.drop(params.length).map(variadicArg(_, foreign)), rtype)
  }

  /** The arguments of a foreign call, with each one headed for a C by-value `va_list` parameter
   * turned into what the target's ABI passes there (`targets.md`).
   *
   * The address is what was checked and is what all three answers are formed from, so this wraps
   * rather than replaces — and it happens here, once, because a foreign call is the only place a
   * `va_list` leaves sysl's own convention behind.
   */
  private def vaPassed(args: List[TExpr], e: ExternDecl): List[TExpr] = {
    val byValue = foreignVaByValue(e)

    if byValue.isEmpty then args
    else args.zipWithIndex.map((a, i) => if byValue(i) then TVaPass(a) else a)
  }

  /** How many arguments a callee accepts, said in the caller's own words.
   *
   * A `...` turns the count into a floor: the declared parameters are still all required, and what
   * follows them is the tail, which no declaration bounds. Every call form asks this the same way,
   * so a member and a free function report the mistake in the same shape.
   */
  protected def checkArity(shown: String, want: Int, variadic: Boolean, got: Int): Unit =
    if variadic then
      if got < want then
        err(s"$shown takes at least ${quantity(want, "argument")}, but ${supplied(got, "argument")}")
    else if got != want then
      err(s"$shown takes ${quantity(want, "argument")}, but ${supplied(got, "argument")}")

  /** One argument in a variadic extern's tail.
   *
   * There is no declared parameter to check it against, so what stands in for one is the rule C
   * itself imposes at the call: only what varargs can carry may be passed, and it is passed
   * already widened. LLVM applies no default argument promotions of its own — an `i8` or an `f32`
   * handed over as written is read back as garbage — so the widening happens here, in the tree,
   * where it is something a test can see rather than a detail of the emitter.
   *
   * What may always cross is what C can name on the other side: an integer, a float, a `char`, or a
   * raw pointer.
   *
   * An **aggregate** crosses to a *foreign* callee and not to a sysl one, and the asymmetry is not
   * an oversight. C allows a struct in a variadic tail and hands it over under exactly the
   * classification a declared parameter of the same type gets (`targets.md`), so a foreign call needs
   * no rule of its own — whoever compiled the other side applies that classification too. A **sysl**
   * variadic callee reads its own tail back with a walk (§9), and the walk is written for values that
   * fit a register; an aggregate would have to be read back some other way, which is a question about
   * the walk and not about the call.
   */
  protected def variadicArg(a: Expr, foreign: Boolean = false): TExpr = {
    val t = analyzeExpr(a)

    at(t.pos):
      t.ty match
        case i: Type.Integer if i.bits < 32   => convert(t, Type.Integer(32, i.signed))
        case f: Type.Floating if f.bits < 64  => convert(t, Type.Real)
        case _: Type.Integer | _: Type.Floating | Type.Char | _: Type.Ptr => t
        case other if CAbi.aggregate(other) && foreign => t
        case other if CAbi.aggregate(other) =>
          err(s"a ${show(other)} cannot be passed to a sysl function's '...' — a walk over the tail " +
            "reads back one register at a time and an aggregate is not one, where a foreign callee " +
            "takes it because C says which registers it arrives in")
        case other =>
          err(s"a ${show(other)} cannot be passed to '...' — a variadic argument must be an " +
            "integer, a float, a char, or a raw pointer")
  }

  /** Enforces a generic function's trait bounds against the type arguments a call resolved to.
   * For each bounded parameter, the concrete type must carry an `impl` of every trait the bound
   * names — checked here at the call, so a caller supplying a type that does not implement the
   * trait is told exactly that, rather than meeting a missing-method error deep inside the
   * monomorphized body. A user type conforms by an `impl` written for its owner key, a built-in by
   * the compiler's own rule (`14 §5`) — which is what lets `sum(3, 4)` instantiate a `[T: Add]`.
   *
   * A *type's* parameters are held to their bounds by the same rule, at the point the type is
   * applied, so the two forms of "what this declaration assumes" are one check.
   */
  protected def checkBounds(f: FuncDecl, targs: List[Type]): Unit =
    inDecl(f.name)(checkParamBounds(qn(f.name), f.tparams, f.bounds, targs))
}
