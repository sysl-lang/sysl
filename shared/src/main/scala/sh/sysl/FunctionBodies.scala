package sh.sysl

import scala.collection.mutable

/** What a function body is checked against, and how one is analyzed inside another.
 *
 * Running a body is what the driver is draining towards, so this is where the drain arrives. Three
 * entries reach it and they differ only in where the signature comes from: an instantiation's is
 * registered in `funcInsts`, the definition-time pass of `14 §4` resolves its own, and a closure's
 * comes from the context that asked for a callable.
 *
 * That last one is why `analyzeNested` saves and restores the whole of the per-function state. A
 * closure's body is written in the middle of its enclosing function and has to be analyzed there —
 * that is where the names it captures are in scope — but every piece of state a body is analyzed
 * against belongs to the function being interrupted. Putting it aside and giving it back is the
 * whole of what makes a body nested inside another body possible.
 */
trait FunctionBodies extends ModuleStorage {

  protected def analyzeFuncBody(name: String, f: FuncDecl, subst: Map[String, Type]): TFunc =
    // A body means what it meant where it was written: an `impl` in one module for a type in
    // another lowers members keyed under the type, and a trait's default is copied into every
    // implementing type, so which module a name in it resolves against travels with the body.
    inDecl(f.name)(at(f.pos)(analyzeFuncBodyAt(name, f, subst)))

  private def analyzeFuncBodyAt(name: String, f: FuncDecl, subst: Map[String, Type]): TFunc = {
    val (params, rtype) = funcInsts(name)

    analyzeBodyWith(name, f, subst, params, rtype)
  }

  /** Analyzes a body **inside** the analysis of another one, and gives back what it yields as well
   * as what it lowered to.
   *
   * A closure's body is written in the middle of its enclosing function and has to be analyzed
   * there, because that is where the names it captures are still in scope and where a diagnostic
   * about it belongs. But a body is analyzed against per-function state — the scope stack, the
   * unique-name set, the declared result, the loops a `break` may name — and every one of those
   * belongs to the function being interrupted. So the state is put aside and given back, which is
   * the whole of what makes a body nested inside another body possible.
   *
   * **The result type comes from the body**, not from a signature, which is what lets a closure be
   * written with nothing to infer a result from (`12 §5`): the parameters come from the context
   * asking for a callable and the result comes from what the body does.
   */
  protected def analyzeNested(
      name: String,
      params: List[(String, Type)],
      declaredResult: Option[Type],
      body: List[Stmt],
      environment: Option[Environment] = None,
      siblings: Map[String, Nested] = Map.empty,
      variadic: Boolean = false,
  ): (TFunc, Type) = {
    val savedScopes   = scopes
    val savedUsed     = used.toSet
    val savedReadOnly = readOnlyLocals.toSet
    val savedRefs     = refPlaces.toMap
    val savedGuards   = refGuards
    val savedImports  = importStack
    val savedLoops    = loops
    val savedEnsure   = ensureResultTy
    val savedOld      = oldBuf
    val savedMulti    = multiOk
    val savedRet      = retTy
    val savedRetList  = retIsList
    val savedVariadic = variadicFn
    val savedCaptures = capturedFields
    val savedNested   = nestedFuncs
    val savedPending  = pendingNested
    val savedOuter    = outerNested
    val savedDeclares = blockDeclares

    // A closure has no name a reader wrote, so `__FUNCTION__` in one names the function it is
    // written in — which means carrying the enclosing name across the reset rather than letting the
    // body see the empty state that reset establishes. Restored below with everything else, since
    // this walk interrupts a function that is still going.
    val savedFuncName = currentFunctionName

    try
      resetFunction()
      currentFunctionName = savedFuncName
      retTy = declaredResult.getOrElse(Type.Unknown)
      retIsList = false
      // A nested function states its own signature, so a `...` on one is its own tail to walk; a
      // closure literal has no way to write one, and is handed `false` (`12 §5a`, §9).
      variadicFn = variadic

      // The receiver comes first, so the closure's own environment is the argument every call
      // already passes and the parameters after it are the ones a program wrote.
      val receiver = environment.map(e => ("self", Type.Ptr(e.struct)))
      val tparams  = (receiver.toList ::: params).map { case (n, t) => (declare(n, t), t) }

      for e <- environment do
        val self = TLoad("self", Type.Ptr(e.struct))

        capturedFields = e.names.zipWithIndex.map { (n, i) =>
          val stored = e.struct.fields(i)._2
          val field  = TField(TDeref(self, e.struct), e.struct.slot(i), stored)

          // A by-reference environment holds the address of the frame's own variable, so what the
          // name reaches is the variable itself — one dereference further, and a place, which is
          // what lets a nested function assign to it.
          val (ty, read) = stored match
            case Type.Ptr(inner) if e.byReference => (inner, TDeref(field, inner))
            case _                                => (stored, field)

          (if e.fixed(n) then declareReadOnly(n, ty) else declare(n, ty)) -> read
        }.toMap
      // A sibling is in scope throughout the group, so a body may call one written below it — which
      // is the half of `12 §5a` that makes mutual recursion work. What a body does *not* reach is a
      // nested function of the frame around it, and remembering their names is what lets that be
      // said rather than reported as a name that stands for nothing.
      nestedFuncs = siblings
      outerNested = (savedNested.keySet ++ savedOuter) -- siblings.keySet
      // The block around this body is where a name it could not capture would have been bound, so
      // its bindings are what a "declared below this" message is measured against.
      blockDeclares = savedDeclares

      // A body written like a function's is one, so its leading contract clauses are its own
      // (`16`). They are analyzed *after* it rather than before, because an `ensure` names `result`
      // and a closure's result is what its body turned out to yield — which is the one thing a
      // declared function knows in advance and this does not.
      val (contracts, rest) = body.span { case _: Require | _: Ensure => true; case _ => false }

      // Neither a closure nor a nested function takes a `variant` yet, and the two are refused
      // together because this is the one path both are analyzed on. The measure is checked at the
      // *call*, out of the arguments it supplies (`17 §4`), and neither of these is reached by a
      // call of that shape: a closure goes through `Fn`, and a nested function's calls carry its
      // captured environment as a receiver the check would have to account for. `17 § Open g`.
      body.collectFirst { case v: Variant => v }.foreach { v =>
        at(v.pos)(err("a 'variant' is a top-level function's — the measure is checked where a call " +
          "to the same body is written, and neither a closure, which is reached through 'Fn', nor " +
          "a function nested in another, whose calls carry a captured environment, is reached that " +
          "way"))
      }

      // A closure whose result the context did not fix is analyzed with nothing expected, so what
      // its body yields is what it yields — the only reading under which `x -> x * 2` has a result
      // at all.
      val tbody = declaredResult match
        case Some(Type.Unit) => analyzeValueBlock(rest, None, discarded = true)
        case want            => analyzeValueBlock(rest, want)

      val result = declaredResult.getOrElse(if tbody.result.isDefined then tbody.ty else Type.Unit)

      if declaredResult.exists(r => r != Type.Unit && tbody.result.isDefined && disagree(tbody.ty, r)) then
        err(s"this closure should yield ${show(declaredResult.get)}, but its body yields ${show(tbody.ty)}")

      val (requires, ensures, olds, _) = analyzeContracts(result, contracts)

      (TFunc(name, tparams, result, tbody, variadic, requires, ensures, olds), result)
    finally
      currentFunctionName = savedFuncName
      scopes = savedScopes
      used.clear(); used ++= savedUsed
      readOnlyLocals.clear(); readOnlyLocals ++= savedReadOnly
      refPlaces.clear(); refPlaces ++= savedRefs
      refGuards = savedGuards
      importStack = savedImports
      loops = savedLoops
      ensureResultTy = savedEnsure
      oldBuf = savedOld
      multiOk = savedMulti
      retTy = savedRet
      retIsList = savedRetList
      variadicFn = savedVariadic
      capturedFields = savedCaptures
      nestedFuncs = savedNested
      pendingNested = savedPending
      outerNested = savedOuter
      blockDeclares = savedDeclares
  }

  /** Analyzes one body against a signature it is handed, rather than one looked up in `funcInsts`.
   * An instantiation's signature is registered there; the definition-time pass of `14 §4` resolves
   * its own, since a generic declaration has no entry until something instantiates it.
   */
  protected def analyzeBodyWith(
      name: String,
      f: FuncDecl,
      subst: Map[String, Type],
      params: List[(String, Type)],
      declaredResult: Type,
  ): TFunc = {
    resetFunction()
    // Set from the *declaration* rather than from `name`, which is the instantiation's mangled key:
    // `__FUNCTION__` reports what a reader wrote, and one written function is one name however many
    // times a generic was lowered.
    currentFunctionName = Modules.bare(f.name)
    // A member's body sees `Self` alongside whatever type parameters it was instantiated with, so
    // the one substitution answers both questions and nothing downstream has to know the difference.
    tsubst = subst ++ memberSelf.getOrElse(name, Map.empty)
    // What the signature asked of those parameters, kept beside what they became: an instantiated
    // body still has to say which trait's member a name on a type parameter meant.
    tbounds = f.bounds
    // And which of the body's own names hold one of them, for the members reached through a value
    // rather than through the parameter's name. Only a parameter written as the bare type parameter
    // qualifies: a `Box[T]` is a `Box`, and what its members mean is the `Box`'s question.
    pbounds = f.params.collect { case Param(n, NamedType(w, Nil), _, _, _) if f.bounds.contains(w) => n -> w }.toMap

    // A result list is the signature's, and the body produces the tuple its parts lay out as — so
    // the body is analyzed against that tuple, with the list itself recorded beside it as the one
    // thing the tuple does not say: how the result is written, and what may stand in it.
    val rtype = declaredResult match
      case r: Type.Results => r.parts
      case other           => other

    retIsList = declaredResult.isInstanceOf[Type.Results]
    retTy = rtype
    variadicFn = f.variadic
    val tparams = params.map { case (n, t) => (declare(n, t), t) }
    // Which of those uniqued names came from a by-name parameter, so a read of one becomes the call
    // the sugar promises (`12 § A parameter may be passed by name`). Matched by written name rather
    // than by position, since what `params` holds is not always the declaration's list unchanged.
    val byNameWritten = f.params.filter(_.byName).map(_.name).toSet
    byNameLocals =
      if byNameWritten.isEmpty then Set.empty
      else params.zip(tparams).collect { case ((n, _), (u, _)) if byNameWritten(n) => u }.toSet
    val (contracts, rest) =
      f.body.span { case _: Require | _: Ensure | _: Variant => true; case _ => false }
    val (requires, ensures, olds, variant) = analyzeContracts(rtype, contracts)

    // A function owing no value is where statement position starts: its body block is the outermost
    // one written for effect, and every `if` and `match` that ends it inherits that.
    val tbody =
      if rtype == Type.Unit then analyzeValueBlock(rest, None, discarded = true)
      else analyzeValueBlock(rest, Some(rtype))

    if rtype != Type.Unit && tbody.result.isDefined && disagree(tbody.ty, rtype) then
      // A `where` predicate and a struct `invariant` lower to synthesised `-> bool` functions, so a
      // non-bool clause surfaces here. Their names are internal, so the mistake is reported as what
      // the user wrote — a condition that is not a `bool` — rather than as a return-type mismatch.
      if f.name.endsWith("$pred") then
        err(s"a 'where' predicate must be a 'bool', but this one is ${show(tbody.ty)}")
      else if f.name.endsWith("$inv") then
        err(s"an 'invariant' must be a 'bool', but this one is ${show(tbody.ty)}")
      else
        err(s"function '${f.name}' should return ${show(declaredResult)}, but its body yields " +
          s"${show(tbody.ty)}")

    // Both keys are asked because an instantiation does not carry the declaration's. `name` is what
    // this body is emitted as — `demo$amplify.int` for a generic — and `f.name` is the declaration
    // it came from, which is what `declAccess` was keyed by. They are the same key for an ordinary
    // function, and only the second answers for an instantiation; a symbol is file-private if either
    // says so, since both name the one declaration.
    TFunc(name, tparams, rtype, tbody, f.variadic, requires, ensures, olds,
      fileLocal(name) || fileLocal(f.name), f.conv, f.tailrec, variant, f.pure, f.ghost,
      frameSymbols(f.reads, "reads"), frameSymbols(f.writes, "writes"),
      // `@export` becomes a symbol here, where the declared name is still in hand. An unwritten one
      // is the function's **bare** name: the module path is what mangling adds, and suppressing the
      // mangling is the whole of what the attribute does.
      f.exported.map(_.symbol.getOrElse(Modules.bare(f.name))))
  }

  /** Typechecks the leading `require`/`ensure` clauses. Both conditions must be `bool`. `result`
   * and `old(e)` are only in scope inside an `ensure` — `result` also only when the function
   * returns a value — and the `old` expressions are collected so codegen can snapshot them at
   * entry.
   */
  private def analyzeContracts(
      rtype: Type,
      clauses: List[Stmt],
  ): (List[(TExpr, Option[String])], List[(TExpr, Option[String])], List[TExpr], Option[TExpr]) = {
    val requires = mutable.ListBuffer.empty[(TExpr, Option[String])]
    val ensures  = mutable.ListBuffer.empty[(TExpr, Option[String])]
    val olds     = mutable.ListBuffer.empty[TExpr]
    var variant  = Option.empty[TExpr]

    for c <- clauses do
      c match
        case Require(cond, msg) =>
          requires += ((analyzeBool(cond), msg))
        case Ensure(cond, msg) =>
          ensureResultTy = if rtype == Type.Unit then None else Some(rtype)
          oldBuf = Some(olds)
          val tc = analyzeBool(cond)
          oldBuf = None
          ensureResultTy = None
          ensures += ((tc, msg))
        case Variant(e) =>
          at(c.pos) {
            if variant.isDefined then
              err("a function declares one 'variant' — a measure is the thing that decreases, and " +
                "two of them say nothing about which")
            val te = analyzeExpr(e)

            te.ty match
              case _: Type.Integer =>
              case other           => err(s"a 'variant' is an integer measure, not ${show(other)}")

            // `17 §4` says the measure reads the parameters and nothing else, and **scoping is what
            // enforces it** rather than a rule of this pass: the clause is analyzed before the body,
            // in a scope holding the parameters alone, so a name from the body is undefined here and
            // says so. That is what makes the check local — the arguments at a self-call are what
            // the parameters are about to become, so the "next" measure is this expression over
            // them and nothing has to travel with the call.
            variant = Some(te)
          }
        case _ => // span guarantees only Require/Ensure/Variant reach here
    (requires.toList, ensures.toList, olds.toList, variant)
  }

  protected def instantiateFunc(f: FuncDecl, targs: List[Type]): String = {
    val name = Type.mangled(f.name, targs)

    // The signature being made real is the declaration's, so it is resolved in the declaration's
    // module however far from it the call that asked for this instantiation was written.
    if !funcInsts.contains(name) then
      inDecl(f.name) {
        val subst = withSelf(f.name, f.tparams.zip(targs).toMap)
        funcInsts(name) =
          (f.params.map(p => (p.name, resolveType(p.typ, subst))),
           f.retType.map(resolveReturn(_, subst)).getOrElse(Type.Unit))
        pending.enqueue((name, f, subst))
      }

    name
  }
}
