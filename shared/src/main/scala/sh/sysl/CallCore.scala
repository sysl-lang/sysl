package sh.sysl

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
trait CallCore extends Literals with TraitObjects with ArgumentBinding {

  /** Producing the receiver a method's `self` sigil asked for — by value, by address, or by
   * reference. Supplied by `CallAnalysis`, which is mixed in last: it is the one step every route to
   * a member ends in, and the routes are what the traits between here and there are.
   */
  protected def buildReceiver(mode: RecvMode, tr: TExpr, member: String = ""): TExpr


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
            if src.exists(isLiteral) || src.isDefined && becomesSlice(t.ty, pty) then
              analyzeExpr(src.get, Some(pty))
            else coerce(t, pty)
        }
      case None => args.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }

    // A **written-out array standing where a slice is asked for**, which for a literal is a
    // conversion the *analysis* performs rather than one `coerce` repairs afterwards: `[1, 2, 3]`
    // becomes a slice because the position it was written in said so, and by the time there is a
    // `[3]int` to coerce the storage has already been made as an array. A non-generic call never
    // meets this — its parameter type is known, so the argument is analyzed against it in the first
    // place — and a generic one analyzed the argument before the solution existed, which is exactly
    // what this re-does.
    //
    // Asked of the two **types** rather than of the argument's shape, so a named array takes the
    // same road as a literal: re-analyzing `a` against `[]const int` reaches the coercion that
    // views it, which is what a non-generic call already does for `plain(a)`. Matching on the
    // syntax would have made the two forms differ here for a rule that does not distinguish them.

    // The complaint is about one argument, so it is reported where that argument is written
    // rather than at the call as a whole.
    //
    // A `unit` parameter is legal and costs nothing — it is zero-sized, so it is dropped from the
    // signature and the argument is evaluated for its effect alone. `never` is the one that cannot
    // be a parameter, and inference is the only place it can be caught: a generic parameter accepts
    // whatever the argument's type is, and an expression that never arrives is the one thing
    // inference must not conclude a slot from. A diverging argument against a *written* parameter
    // is untouched — it is dead code, and that parameter still has a layout.
    // A callable's parameters have no names a program wrote — the call trait's are the library's —
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

  /** Whether an expression is one whose type the *context* has to supply (`12 §5`, `§6`).
   *
   * The two shapes are the two ways of writing a callable that is not already a value: a literal,
   * whose parameters are typed by what asks for it, and the name of a declared function, which is
   * not a value at all until something says which call trait to build it into. Neither can be
   * analyzed first and converted afterwards, which is what separates them from every other
   * expression a converting context meets.
   */
  protected def callableArg(a: Expr): Boolean = a match
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

  /** A call to a name, which may stand for one function or for several (`12 §1a`).
   *
   * **A name declared once takes the same path it always did**, and that is worth stating as a
   * property rather than as an optimization: `overloadKeys` answers with the one key, the branch
   * below is not entered, and nothing about the analysis of the overwhelming majority of calls
   * changed when overloading arrived.
   *
   * **A candidate is chosen by trying the call against it**, rather than by a rule that compares
   * argument types to parameter types. That is the whole design here, and the reason is that the
   * comparison is not the small thing it sounds like: it would have to know about named arguments,
   * defaulted parameters, variadic tails, literal inference, generic solving, and every coercion a
   * parameter admits. All of that is `callFunction`, so asking `callFunction` is the only way to get
   * the same answer twice. A candidate the call does not fit is one that reported something, which
   * `probe` catches and drops.
   *
   * The cost is analyzing the arguments once per candidate, and it is paid only where a name is
   * overloaded at all.
   */
  protected def callOverloaded(plain: String, written: List[Expr], expected: Option[Type]): TExpr = {
    val keys = overloadKeys(plain)

    if keys.length == 1 then callFunction(funcDecls(plain), written, expected)
    else
      val candidates = keys.map(funcDecls)
      val fitting    = candidates.flatMap(f => fitSignature(f, written, expected).map(f -> _))

      narrow(fitting, written) match
        case List((one, _)) => callFunction(one, written, expected)

        // Nothing fits. Where exactly one candidate could have taken this many arguments, its own
        // complaint is the useful message — the reader wrote a call meaning that one and got a type
        // wrong — so it is made again outside the sandbox and allowed to report. Otherwise the
        // mistake is about which function was meant, and the answer is the roster.
        case Nil =>
          // An argument carrying `Type.Unknown` was already refused where it was made, and it makes
          // *every* candidate fail — so the roster below would be printed with nothing eliminated,
          // listing declarations that plainly do take the call as written. That is the worst message
          // this could give: it says overload resolution rejected a call the reader can see matches,
          // and sends them to the wrong line. The first error is the one to fix, and it is already on
          // screen above this one.
          if written.exists(a => alreadyReported(analyzeExpr(a))) then poisoned()

          val plausible = candidates.filter(f => arityFits(f.params.length, f.variadic, written.length))

          if plausible.length == 1 then callFunction(plausible.head, written, expected)
          else
            err(s"no '${qn(plain)}' takes these arguments — the declarations of that name are:\n" +
              candidates.map(f => s"    ${signatureOf(f)}").mkString("\n"))

        // Several fit, and the language does not guess. A literal is the usual cause: `0` is an
        // `int` and an `i64` and an `f64`, so a name declared at two of those is genuinely ambiguous
        // at a bare `0` and is not at `0i64`.
        case many =>
          err(s"'${qn(plain)}' is ambiguous here — ${many.length} of its declarations take these " +
            s"arguments:\n" + many.map((f, _) => s"    ${signatureOf(f)}").mkString("\n") +
            "\nAnnotate an argument, or name the type a literal is meant at")
  }

  /** The parameter types a candidate would take this call at, or `None` where it does not take it.
   *
   * It is one `probe` of `callFunction`, which is the same question `12 §1a` says to ask — but the
   * answer kept is the **signature the fit arrived at** rather than only that there was one. For a
   * generic candidate that is the signature of the *instantiation*: `callFunction` ends at
   * `funcInsts(name)` with `name` the instantiated key, so the type arguments the call solved are
   * already applied. That is what lets `narrow` ask a generic candidate the same exactness question
   * as an ordinary one, without being handed the substitution or having to read coercion nodes back
   * out of the tree.
   *
   * The instantiation the probe registered is dropped with everything else it did — `probe` is
   * `sandboxed` — so this reads the table inside the attempt and hands out types, which outlive it.
   */
  private def fitSignature(
      f: FuncDecl,
      written: List[Expr],
      expected: Option[Type],
  ): Option[List[Type]] =
    probe {
      val TCall(inst, _, _, _) = callFunction(f, written, expected): @unchecked
      funcInsts(inst)._1.map(_._2)
    }

  /** The tie-breaks applied to the candidates a call fits, in order, stopping as soon as one leaves
   * a single answer (`12 §1a`). Each candidate arrives with the signature its fit arrived at, which
   * for a generic one is the instantiation's — see `fitSignature`.
   *
   * The first two are about **exactness**, and both exist because a call that fits two declarations
   * usually fits one of them the way it was written and the other by something the language did for
   * it.
   *
   * 1. **A candidate that needed no default fitted the call as written.** `f(x: int)` and
   *    `f(x: int, y: int = 0)` both take `f(1)`, and the reader who wrote `f(1)` meant the first.
   * 2. **A candidate whose parameters are exactly the arguments' own types** beats one reached by a
   *    conversion. This is what makes a literal's natural type decide between two widths, and it is
   *    asked of the types the fit settled on rather than of the types the declaration wrote — so
   *    `g[T](x: T)` is exact at a `[]int` argument and beats `g(s: []const int)`, which is reached
   *    only by giving up the ability to write.
   * 3. **A candidate that named its parameters beats one that was solved for them**, where both are
   *    exact. `f(x: int)` beside `f[T](x: T)` at `f(0)` fits both at `int`, and a generic
   *    declaration is the one that took the call by being told what to be. Without this the second
   *    tie-break would turn that call — which resolves today — into an ambiguity, so it is a guard
   *    rather than a preference.
   *
   * What is deliberately *not* here is a rule ranking one conversion above another. Two candidates
   * each reached by a different conversion are ambiguous, and saying so is better than a ladder of
   * precedences nobody can predict from the source. Ranking a declaration against a declaration, as
   * the third does, is a different question from ranking the routes the arguments took.
   */
  private def narrow(
      fits: List[(FuncDecl, List[Type])],
      written: List[Expr],
  ): List[(FuncDecl, List[Type])] =
    if fits.length <= 1 then fits
    else
      val exactArity = fits.filter(_._1.params.length == written.length)
      val ranked     = if exactArity.length == 1 then exactArity else fits
      val natural    = written.map(a => probe(analyzeExpr(a).ty))

      if ranked.length <= 1 then ranked
      else
        val exactTypes = ranked.filter { (_, ptypes) =>
          ptypes.length == natural.length && ptypes.zip(natural).forall((p, t) => t.contains(p))
        }

        if exactTypes.length == 1 then exactTypes
        else
          val spelled = exactTypes.filter(_._1.tparams.isEmpty)

          if spelled.length == 1 then spelled else ranked

  /** Whether a callee with this many parameters could take this many arguments at all — the arity
   * question alone, with nothing said about types. Defaults make the low end, a variadic tail
   * removes the high one.
   */
  private def arityFits(params: Int, variadic: Boolean, args: Int): Boolean =
    args <= params || (variadic && args >= params)

  /** One declaration as a diagnostic lists it: the name a reader wrote and the parameters that tell
   * it from its siblings. The result is left off — it is not what distinguishes two overloads, since
   * `12 §1a` refuses a pair that differ only in it.
   */
  private def signatureOf(f: FuncDecl): String =
    s"${qn(f.name)}(${f.params.map(p => s"${p.name}: ${p.typ.show}").mkString(", ")})"

  protected def callFunction(f: FuncDecl, written: List[Expr], expected: Option[Type]): TExpr = {
    // A variadic callee — foreign or sysl's own — fixes only where its declared parameters stop;
    // everything after them is the tail, checked by the rule below rather than against a parameter.
    val variadic = f.variadic

    val shown = qn(f.name)

    // Names placed and defaults filled before anything else reads the list, so everything below —
    // the arity check, the generic solve, `checkArgs` — sees the call written out in full.
    val args = bindArgs(s"function '$shown'", Some(f.name), f.params, written, variadic)

    // A `@test` function has one caller and it is not in the program (`testing.md`). Every build but
    // `sysl test` drops it, so a call would compile and then fail at the *link*, naming a symbol
    // nothing in the source explains — which is the shape of failure a diagnostic exists to prevent.
    // Refused wherever the call is written, a test's own body included: two tests sharing work share
    // an ordinary function, which is what one is for.
    if f.test.isDefined then
      err(s"'$shown' is a '@test' function, which 'sysl test' calls and nothing else does — " +
        "every other build leaves it out, so this call would have no definition to reach. " +
        "Work two tests share belongs in an ordinary function they both call")

    // An interrupt handler has one caller and it is the processor (`15 §10`). It is entered on an
    // asynchronous event with a frame the hardware pushed, and it leaves through a
    // return-from-interrupt that restores the interrupted context — so a call written here would set
    // up an ordinary frame and then execute an instruction that unwinds something that never
    // happened. Its address is still worth taking, which is what a vector table is built from.
    if f.conv.isDefined then
      err(s"'$shown' is an interrupt handler, which the processor enters and no program calls — " +
        "it leaves through a return-from-interrupt, which would unwind a frame this call never " +
        s"pushed. Take its address for the vector table with '&$shown', and put whatever the two " +
        "share in an ordinary function they both call")

    checkArity(s"function '$shown'", f.params.length, variadic, args.length)

    // An extern is declared in the output only if something reaches it, which is what keeps an
    // unused one — the library's `exit`, in a program that never panics — out of the module.
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

    checkCrossings(f, shown, params, checked)

    val declared = externDecls.get(f.name).fold(checked)(vaPassed(checked, _))

    funcsUsed += name
    // Only a free function can be an `extern`, so this is the one call form whose tail may be a
    // foreign one — and the only one an aggregate may cross.
    val foreign = externDecls.contains(f.name)

    TCall(name, declared ::: args.drop(params.length).map(variadicArg(_, foreign)), rtype)
  }

  /** Holds each `@crossing` argument to `06`'s rule about what may reach another concurrency domain.
   *
   * **The check is made at the call and reads the *instantiated* signature**, which is what makes it
   * reach a facility the language does not own: a package's `task(…)` and the library's `spawn` are
   * one shape here, and a generic parameter has a concrete type by the time this runs, exactly as a
   * `&sync Box[T]` is asked afresh per instantiation (`Sharing`).
   *
   * **A `*T` parameter is looked *through*, and that is the whole of what the annotation buys.** A
   * raw pointer is on the crossable list because it carries no count of its own — which says nothing
   * about the object at the far end, and the object at the far end is what crossed. Everything else
   * is asked about the parameter's own type, because a parameter copies no buffer and shares
   * whatever it names.
   *
   * Reported at the **argument**, since that is the thing that could have been written differently.
   */
  protected def checkCrossings(
      f: FuncDecl,
      shown: String,
      params: List[(String, Type)],
      args: List[TExpr],
  ): Unit =
    if f.crossing.nonEmpty then
      for ((pname, pty), a) <- params.zip(args) if f.crossing.contains(pname) do
        val (subject, crossed) = pty match
          case Type.Ptr(inner) => (s"what '$pname' of '$shown' points at", inner)
          case other           => (s"'$pname' of '$shown'", other)

        at(a.pos)(Sharing.crossing(subject, crossed).foreach(err))

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
