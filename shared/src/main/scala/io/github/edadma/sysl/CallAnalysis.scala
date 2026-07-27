package io.github.edadma.sysl

/** Calls and construction: free functions, methods, associated functions, struct and enum
 * construction, and the `?` operator. A member call resolves exactly as a free call does —
 * the analyzer lowered every member to a function under the mangled name `Type.member` — so the
 * only method-specific work here is passing the receiver in the mode its `self` sigil declared.
 */
trait CallAnalysis extends Literals with TraitObjects {

  /** Type-checks positional arguments against a resolved parameter list. `pre` holds arguments
   * already analyzed during type-argument inference, so they are not analyzed twice.
   */
  protected def checkArgs(
      what: String,
      params: List[(String, Type)],
      args: List[Expr],
      pre: Option[List[TExpr]],
  ): List[TExpr] = {
    // An argument analyzed during inference was analyzed without an expected type, so a value
    // headed for a `&T` parameter is boxed here instead of at its own analysis, and one headed for
    // a trait object is erased here.
    val ts = pre match
      case Some(provisional) => provisional.zip(params).map { case (t, (_, pty)) => coerce(t, pty) }
      case None              => args.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }

    // The complaint is about one argument, so it is reported where that argument is written
    // rather than at the call as a whole.
    //
    // A `unit` parameter is legal and costs nothing — it is zero-sized, so it is dropped from the
    // signature and the argument is evaluated for its effect alone. `never` is the one that cannot
    // be a parameter, and inference is the only place it can be caught: a generic parameter accepts
    // whatever the argument's type is, and an expression that never arrives is the one thing
    // inference must not conclude a slot from. A diverging argument against a *written* parameter
    // is untouched — it is dead code, and that parameter still has a layout.
    for (t, (pname, pty)) <- ts.zip(params) do
      at(t.pos):
        if disagree(t.ty, pty) then err(s"'$pname' of '$what' is ${show(pty)}, but ${show(t.ty)} was given")
        else if pty == Type.Never then
          err(s"cannot pass an expression that never returns as '$pname' of '$what'")

    ts
  }

  protected def callFunction(f: FuncDecl, args: List[Expr], expected: Option[Type]): TExpr = {
    // A variadic callee — foreign or sysl's own — fixes only where its declared parameters stop;
    // everything after them is the tail, checked by the rule below rather than against a parameter.
    val variadic = f.variadic

    val shown = qn(f.name)

    if variadic then
      if args.length < f.params.length then
        err(s"function '$shown' takes at least ${quantity(f.params.length, "argument")}, " +
          s"but ${supplied(args.length, "argument")}")
    else if args.length != f.params.length then
      err(s"function '$shown' takes ${quantity(f.params.length, "argument")}, but ${supplied(args.length, "argument")}")

    // An extern is declared in the output only if something reaches it, which is what keeps an
    // unused one — the prelude's `exit`, in a program that never panics — out of the module.
    if externDecls.contains(f.name) then externsUsed += f.name

    val (name, pre) =
      if f.tparams.isEmpty then (f.name, None)
      else
        val provisional = args.map(analyzeExpr(_))
        // The parameter types being matched against are the declaration's, written in the
        // declaration's terms — so a `Pair[T]` there is that module's `Pair` whichever module the
        // call was written in.
        val targs = inDecl(f.name)(
          solve(shown, f.tparams, f.params.map(_.typ), provisional.map(_.ty), f.retType, expected))
        checkBounds(f, targs)
        (instantiateFunc(f, targs), Some(provisional))

    val (params, rtype) = funcInsts(name)
    val declared        = checkArgs(shown, params, args.take(params.length), pre)

    funcsUsed += name
    TCall(name, declared ::: args.drop(params.length).map(variadicArg), rtype)
  }

  /** One argument in a variadic extern's tail.
   *
   * There is no declared parameter to check it against, so what stands in for one is the rule C
   * itself imposes at the call: only what varargs can carry may be passed, and it is passed
   * already widened. LLVM applies no default argument promotions of its own — an `i8` or an `f32`
   * handed over as written is read back as garbage — so the widening happens here, in the tree,
   * where it is something a test can see rather than a detail of the emitter.
   *
   * What may cross is what C can name on the other side: an integer, a float, a `char`, or a raw
   * pointer. A `string`, a `&T`, a struct — every sysl layout C has no notion of — is refused,
   * because unlike a declared parameter (`12 §1`) there is no written type saying what the callee
   * agreed to receive.
   */
  private def variadicArg(a: Expr): TExpr = {
    val t = analyzeExpr(a)

    at(t.pos):
      t.ty match
        case i: Type.Integer if i.bits < 32   => convert(t, Type.Integer(32, i.signed))
        case f: Type.Floating if f.bits < 64  => convert(t, Type.Real)
        case _: Type.Integer | _: Type.Floating | Type.Char | _: Type.Ptr => t
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

  /** `value.method(args)` — resolves `method` as an inherent member of the receiver's type and
   * calls the function it lowered to, passing the receiver as the first argument in whatever
   * memory mode the method's `self` asked for.
   *
   * The receiver may be of *any* type: every type has an owner key its members are filed under, so
   * `5.show()` takes this path exactly as `p.show()` does.
   *
   * A method with type parameters **of its own** is the one shape the receiver does not settle, and
   * `callGenericMethod` is where the rest of the answer comes from.
   */
  protected def callMethod(recv: Expr, mname: String, args: List[Expr], expected: Option[Type]): TExpr = {
    val tr = analyzeExpr(recv)

    receiverType(tr.ty) match
      case a: Type.Abstract => callBoundMethod(a, tr, mname, args)
      case t: Type.Trait    => callTraitObject(tr, t, mname, args)
      case rty =>
        val (base, targs) = memberKey(rty, mname)

        memberDecls.get((base, mname)) match
          case Some(m) if m.receiver.isDefined && m.tparams.nonEmpty =>
            callGenericMethod(genericMembers((base, mname)), m, targs, tr, args, expected)
          case Some(m) if m.receiver.isDefined =>
            val fname           = memberFuncName(rty, mname)
            val (params, rtype) = funcInsts(fname)
            if args.length != params.length - 1 then
              err(s"method '$fname' takes ${quantity(params.length - 1, "argument")}, " +
                s"but ${supplied(args.length, "argument")}")
            val recvArg  = buildReceiver(m.receiver.get, tr)
            val restArgs = args.zip(params.tail).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
            funcsUsed += fname
            TCall(fname, checkArgs(fname, params, args, Some(recvArg :: restArgs)), rtype)
          // Neither of the two remaining kinds takes a receiver, and they are not the same mistake:
          // a property is this call with the parentheses dropped, an associated function is not
          // reached through a value at all.
          case Some(m) if m.isProperty =>
            err(s"'$mname' is a property of '$base' — read it as 'value.$mname', without '()'")
          case Some(_) => err(s"'$mname' is an associated function of '$base' — call it with '$base.$mname(…)'")
          case None =>
            builtinMethod(rty, mname, tr, args)
              .orElse(builtinDisplay(rty, mname, tr, args))
              .orElse(builtinHash(rty, mname, tr, args))
              .getOrElse(err(s"type '$base' has no method '$mname'"))
  }

  /** `value.m(…)` where `m` declares type parameters of its own, at the instantiation this call
   * resolves to.
   *
   * The two lists of parameters the lowered function carries are fixed from two different places,
   * which is the whole of what makes this its own path. The **type's** are already settled — the
   * receiver is a type, and a type has its arguments — so they are read off it and held to nothing
   * further here, having been checked where the receiver's type was made. The **member's own** are
   * solved from the arguments and from the type the context expects, exactly as a generic free
   * function's are, and held to the bounds the member wrote, reported in the member's name.
   *
   * Appending the solved arguments to the receiver's is what `synthesize` laid the two lists out in
   * that order for, so the substitution instantiating the function needs to know nothing about
   * which parameter came from where.
   */
  private def callGenericMethod(
      fd: FuncDecl,
      m: MethodDecl,
      ownerArgs: List[Type],
      recv: TExpr,
      args: List[Expr],
      expected: Option[Type],
  ): TExpr = {
    val shown = qn(fd.name)

    if args.length != fd.params.length - 1 then
      err(s"method '$shown' takes ${quantity(fd.params.length - 1, "argument")}, " +
        s"but ${supplied(args.length, "argument")}")

    val spell       = genericSelf.get(fd.name).fold((r: TypeRef) => r)((ref, _) => spellSelf(_, ref))
    val provisional = args.map(analyzeExpr(_))
    val own = inDecl(fd.name)(solve(
      shown,
      m.tparams,
      fd.params.tail.map(p => spell(p.typ)),
      provisional.map(_.ty),
      fd.retType.map(spell),
      expected,
    ))

    inDecl(fd.name)(checkParamBounds(shown, m.tparams, fd.bounds, own))

    val name            = instantiateFunc(fd, ownerArgs ::: own)
    val (params, rtype) = funcInsts(name)
    val recvArg         = buildReceiver(m.receiver.get, recv)

    TCall(name, checkArgs(shown, params, args, Some(recvArg :: provisional)), rtype)
  }

  /** `5.display(out, fmt)` — the rendering a built-in's `Display` membership provides (`14 §5`).
   *
   * It is `5.add(3)`'s sibling and exists for the same reason: a built-in has no `impl` block, so
   * there is no lowered `int.display` to call. What it has is a renderer the prelude already writes,
   * declared in the argument order `Display` does, so naming it here is the whole lowering — and it
   * is what lets a `Display` written for a struct render the struct's own fields without leaving
   * the allocation-free path the sink exists for.
   */
  private def builtinDisplay(rty: Type, mname: String, recv: TExpr, args: List[Expr]): Option[TExpr] =
    for
      m           <- Option.when(mname == "display")(traitDecls.get("Display")).flatten
      sig         <- m.methods.find(_.name == "display")
      (fname, to) <- CoreTraits.display(rty)
    yield {
      val params = sig.params.map(p => (p.name, rt(p.typ)))

      if args.length != params.length then
        err(s"method 'Display.display' takes ${quantity(params.length, "argument")}, " +
          s"but ${supplied(args.length, "argument")}")

      val self = widen(buildReceiver(RecvMode.ByValue, recv), to)

      funcsUsed += fname
      TCall(fname, self :: checkArgs("Display.display", params, args, None), Type.Unit)
    }

  /** `k.hash()` — the mixing a built-in's `Hash` membership provides (`14 §5`).
   *
   * `builtinDisplay`'s sibling, and built the same way for the same reason: a built-in has no
   * `impl` block, so the lowering is a prelude function named here. Where `Display` writes into a
   * sink this returns a number, so what the widening is for is the law rather than the signature —
   * every integer, `char`, and `bool` reaches one mixer at 64 bits, so two values that compare
   * equal across widths hash equal too.
   */
  private def builtinHash(rty: Type, mname: String, recv: TExpr, args: List[Expr]): Option[TExpr] =
    for
      _           <- Option.when(mname == "hash")(traitDecls.get("Hash")).flatten
      (fname, to) <- CoreTraits.hash(rty)
    yield {
      if args.nonEmpty then
        err(s"method 'Hash.hash' takes no arguments, but ${supplied(args.length, "argument")}")

      val self = widen(buildReceiver(RecvMode.ByValue, recv), to)

      funcsUsed += fname
      TCall(fname, List(self), Type.Integer(64, signed = false))
    }

  /** `5.add(3)`, `x.lt(y)` — a core-trait method on a type whose membership the compiler provides.
   *
   * A built-in has no `impl` block and so no lowered `int.add` to call; what it has is the operator
   * the trait method *means*, and that is what this builds. The result is the same tree the operator
   * itself would have produced, which is `14 §5`'s promise that a membership changes no codegen —
   * and it is what a monomorphized `[T: Add]` body lands on once `T` is known to be a scalar.
   */
  private def builtinMethod(rty: Type, mname: String, recv: TExpr, args: List[Expr]): Option[TExpr] =
    for
      trName <- CoreTraits.declaring(mname)
      if CoreTraits.builtin(trName, rty)
      m      <- traitDecls.get(trName).flatMap(_.methods.find(_.name == mname))
    yield {
      val params = m.params.map(p => (p.name, resolveType(p.typ, selfBinding(rty))))

      if args.length != params.length then
        err(s"method '$trName.$mname' takes ${quantity(params.length, "argument")}, " +
          s"but ${supplied(args.length, "argument")}")

      val self             = buildReceiver(RecvMode.ByValue, recv)
      val ts               = checkArgs(s"$trName.$mname", params, args, None)
      val (_, op, kind)    = CoreTraits.required(trName)

      kind match
        case CoreTraits.Kind.Arith   => TBinary(op, self, ts.head, arithType(op, self.ty, ts.head.ty))
        case CoreTraits.Kind.Compare => TCompare(List(self, ts.head), List(TCmp(op)))
        case CoreTraits.Kind.Prefix  => TUnary(op, self, self.ty)
    }

  /** `x.m(…)` where `x` is a type parameter, during the definition-time pass of `14 §4`.
   *
   * The method is looked up in the traits the parameter's bounds name, and the call checked against
   * the **trait's** signature rather than any implementation's. That is what makes one walk stand in
   * for every instantiation: an `impl` conforms to the trait exactly (`Hoisting.checkConformance`),
   * so a call the trait signature accepts is one every implementation accepts.
   *
   * The tree it builds is discarded with the rest of the pass — the name it carries is the trait's,
   * and which implementation runs is monomorphization's to decide once a concrete type is known.
   */
  private def callBoundMethod(a: Type.Abstract, recv: TExpr, mname: String, args: List[Expr]): TExpr =
    boundMember(a, mname) match
      case None => unlicensed(a, mname)
      case Some((tr, self, m)) =>
        reported {
          val fname = s"${tr.name}.$mname"
          if m.isProperty then
            err(s"'$mname' is a property of '${tr.show}' — read it as 'value.$mname', without '()'")
          if m.receiver.isEmpty then
            err(s"'$mname' is an associated function of '${tr.show}', so it has no receiver — a value " +
              "cannot be the thing it is called on")
          val params = m.params.map(p => (p.name, resolveType(p.typ, self)))
          if args.length != params.length then
            err(s"method '$fname' takes ${quantity(params.length, "argument")}, " +
              s"but ${supplied(args.length, "argument")}")
          val ts    = args.zip(params).map { case (arg, (_, pty)) => analyzeExpr(arg, Some(pty)) }
          val rtype = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
          TCall(fname, recv :: checkArgs(fname, params, args, Some(ts)), rtype)
        }

  /** The first member of that name one of a parameter's bounds declares, with the substitution its
   * signature is read under.
   *
   * That substitution is the whole of what a bound's *arguments* buy. `Self` is the parameter
   * itself, which is what makes `Add::add` yield a `T` and `Ord::lt` a `bool` inside a body that has
   * not met a concrete type yet (`14 §4`); the trait's own parameters are the arguments the bound
   * applied it to, so a `T: From[int]` has a `from` that takes an `int` and one bounded by
   * `From[U]` has one that takes whatever `U` turns out to be.
   */
  private def boundMember(a: Type.Abstract, mname: String): Option[(Type.Bound, Map[String, Type], MethodDecl)] =
    a.bounds.iterator
      .flatMap(b => traitDecls.get(b.name).map((b, _)))
      .flatMap { case (b, decl) =>
        decl.methods.find(_.name == mname).map(m => (b, selfBinding(a) ++ decl.tparams.zip(b.args), m))
      }
      .nextOption()

  /** `x.p` where `x` is a type parameter and `p` is a property one of its bounds declares — the read
   * `callBoundMethod` is to a call, checked the same way and for the same reason.
   *
   * A field would be refused here whatever the bounds said, because a field is layout and no promise
   * about behaviour reaches one (`10 §5`). A property is the opposite case: it is behaviour that
   * happens to be spelled like a field, so a trait can promise it, and a bounded body may read one
   * exactly as it may call a method.
   */
  protected def readBoundProperty(a: Type.Abstract, recv: TExpr, name: String): TExpr =
    boundMember(a, name) match
      case None => unlicensedProperty(a, name)
      case Some((tr, self, m)) =>
        reported {
          if !m.isProperty then
            err(s"'$name' is a method of '${tr.show}' — call it with '$name(…)'")
          val rtype = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
          TCall(s"${tr.name}.$name", List(recv), rtype)
        }

  /** The diagnostic for a property read that no bound licenses.
   *
   * With no trait declaring one of that name there is nothing a bound could be, and the honest
   * complaint is the older one about a field: a type parameter has no layout to read. Where a trait
   * *does* declare it, the fix is the bound, and naming it is what checking at the definition is for.
   */
  private def unlicensedProperty(a: Type.Abstract, name: String): Nothing =
    traitDecls.values.filter(_.methods.exists(m => m.name == name && m.isProperty)).map(t => qn(t.name)).toList match
      case Nil =>
        boundErr(s"'${a.name}' is a type parameter, so it has no fields to read — a field is layout, " +
          s"and no trait declares a property '$name' that a bound could promise instead")
      case one :: Nil =>
        boundErr(s"'$name' needs '${a.name}: $one'")
      case many =>
        boundErr(s"'$name' needs a bound on '${a.name}' — it is declared by ${many.mkString("'", "', '", "'")}")

  /** `obj.m(…)` on a `*Trait` or a `&Trait` — the dynamic half of `02`.
   *
   * The signature checked against is the **trait's**, because the implementation is exactly what an
   * erased value no longer says: which function runs is a word read out of the object's table at
   * run time, and what stands in for knowing it is that every `impl` conforms to the trait exactly
   * (`Hoisting.checkConformance`). `Self` is not resolved at all — object safety already refused
   * any trait that mentions it away from the receiver, so no signature reaching here contains one.
   */
  protected def callTraitObject(recv: TExpr, t: Type.Trait, mname: String, args: List[Expr]): TExpr = {
    val decl = traitDecls(t.name)
    // The trait's own parameters are what the *object's* type fixed — an object over `Sink[int]`
    // takes an `int` — so they are the whole of the substitution a signature is read under. `Self`
    // is not in it at all: object safety already refused any trait that mentions one away from its
    // receiver, so no signature reaching here contains one.
    val subst: Map[String, Type] = decl.tparams.zip(t.args).toMap

    decl.methods.zipWithIndex.find(_._1.name == mname) match
      case None =>
        err(s"trait '${t.name}' declares no method '$mname' — it has " +
          decl.methods.map(_.name).mkString("'", "', '", "'"))
      case Some((m, _)) if m.isProperty =>
        err(s"'$mname' is a property of '${t.name}' — read it as 'value.$mname', without '()'")
      case Some((m, slot)) =>
        val params = m.params.map(p => (p.name, resolveType(p.typ, subst)))
        val fname  = s"${t.name}.$mname"

        if args.length != params.length then
          err(s"method '$fname' takes ${quantity(params.length, "argument")}, " +
            s"but ${supplied(args.length, "argument")}")

        val ts    = args.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
        val rtype = m.retType.map(resolveReturn(_, subst)).getOrElse(Type.Unit)

        TVCall(recv, slot, checkArgs(fname, params, args, Some(ts)), rtype)
  }

  /** `obj.p` on a `*Trait` or a `&Trait` — a property read through the table, which is the same
   * indirect call a method is with no arguments to pass and no parentheses to write.
   *
   * An object has no fields at all: the layout is exactly what erasure forgot. So every name read off
   * one is either a property the trait declares or a mistake, and the second half of this says which.
   */
  protected def readTraitObjectProperty(recv: TExpr, t: Type.Trait, name: String): TExpr = {
    val decl                     = traitDecls(t.name)
    val subst: Map[String, Type] = decl.tparams.zip(t.args).toMap

    decl.methods.zipWithIndex.find(_._1.name == name) match
      case Some((m, slot)) if m.isProperty =>
        TVCall(recv, slot, Nil, m.retType.map(resolveReturn(_, subst)).getOrElse(Type.Unit))
      case Some(_) =>
        err(s"'$name' is a method of '${t.name}' — call it with '$name(…)'")
      case None =>
        err(s"a ${show(recv.ty)} has no fields, and trait '${t.name}' declares no '$name'")
  }

  /** The diagnostic for a method no bound licenses. It names the bound that *would* license it,
   * since the fix is to write that bound rather than to stop making the call — which is the whole
   * point of checking at the definition instead of at some caller three files away.
   */
  private def unlicensed(a: Type.Abstract, mname: String): Nothing =
    traitDecls.values.filter(_.methods.exists(_.name == mname)).map(t => qn(t.name)).toList match
      case Nil =>
        boundErr(s"no trait declares a method '$mname', so no bound on '${a.name}' could license this call")
      case one :: Nil =>
        boundErr(s"'$mname' needs '${a.name}: $one'")
      case many =>
        boundErr(s"'$mname' needs a bound on '${a.name}' — it is declared by ${many.mkString("'", "', '", "'")}")

  // --- operators as trait methods --------------------------------------------------------

  /** `a ⊕ b` where `⊕`'s operands are not something the machine has an instruction for (`14 §3`).
   *
   * A built-in keeps its instruction and this yields `None`, leaving the ordinary scalar path to
   * lower it — that is `§5`'s promise that a membership changes no codegen, and it is also what
   * keeps a float comparison on IEEE semantics instead of routing it through a negated `lt`.
   *
   * Everything else resolves to the one method the operator's trait requires: a bounded type
   * parameter to the trait's own signature (checked at the definition, then bound by
   * monomorphization), a user type to the member its `impl` produced. `None` for an operand type
   * with no membership either way, so the diagnostic stays the one the scalar path already gives.
   */
  protected def operatorCall(op: String, l: TExpr, r: TExpr): Option[TExpr] =
    CoreTraits.infix.get(op).flatMap(trName => traitOperator(trName, op, Some(r), l))

  /** `-a`, `~a` — the same, for the two prefix operators. */
  protected def prefixCall(op: String, t: TExpr): Option[TExpr] =
    CoreTraits.prefix.get(op).flatMap(trName => traitOperator(trName, op, None, t))

  private def traitOperator(trName: String, op: String, rhs: Option[TExpr], recv: TExpr): Option[TExpr] =
    dispatchFor(trName, op, recv.ty).map { d =>
      val self = selfBinding(recv.ty)
      val m    = traitDecls(trName).methods.find(_.name == CoreTraits.required(trName)._1).get

      for b <- rhs do checkOperand(op, recv.ty, b, resolveType(m.params.head.typ, self))

      val rty  = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
      val args = rhs.toList

      funcsUsed += d.name

      val call = TCall(d.name, if d.swap then args :+ recv else recv :: args, rty)

      if d.negate then TUnary("!", call, Type.Bool) else call
    }

  /** Which method an operator on `ty` dispatches to, or `None` when the machine has an instruction
   * for it — the one dispatch rule of `14 §3`, in the form the operand-sharing lowerings need.
   *
   * A built-in keeps its instruction. A bounded type parameter answers with the **trait's** own
   * method: which implementation runs is monomorphization's to decide, and a bound the parameter does
   * not carry is reported here, at the definition, which is what `14 §4` is for. A user type answers
   * with the member its `impl` produced. A type with no membership either way answers `None`, so the
   * diagnostic stays the one the scalar path already gives.
   */
  private def dispatchFor(trName: String, op: String, ty: Type): Option[TDispatch] = {
    val (swap, negate) = CoreTraits.derivation.getOrElse(op, (false, false))
    val method         = CoreTraits.required(trName)._1

    if CoreTraits.builtin(trName, ty) then None
    else
      ty match
        case a: Type.Abstract =>
          if !a.bounds.exists(_.key == trName) then boundErr(s"'$op' needs '${a.name}: $trName'")
          Some(TDispatch(s"$trName.$method", swap, negate))
        // The implementation is named by the rule a method call uses, which for a generic type is
        // the instantiation the receiver's own arguments make — `a + b` on a `Box[int]` reaches the
        // same function `a.add(b)` would.
        case _ => Option.when(satisfies(trName, ty))(TDispatch(memberFuncName(ty, method), swap, negate))
  }

  /** The other operand of a dispatched binary operator, against what the trait's signature asks for
   * — which for every operator in the catalog is `Self`, so the two sides must be the one type.
   */
  private def checkOperand(op: String, recvTy: Type, b: TExpr, want: Type): Unit =
    at(b.pos):
      if disagree(b.ty, want) then err(s"'$op' needs matching types, got ${show(recvTy)} and ${show(b.ty)}")

  /** One link of a comparison chain: the operator, plus the method performing it where the operand
   * type reaches `Eq`/`Ord` through a trait.
   *
   * What this hands codegen is a **name**, not a call tree, and that is the whole reason it exists.
   * A chain compares each middle operand against both its neighbours from a single evaluation, so a
   * dispatched comparison has to read the value codegen is already holding; a `TCall` wrapped around
   * the operand's own tree would evaluate it again.
   */
  protected def compareLink(op: String, l: TExpr, r: TExpr): TCmp = {
    val trName = CoreTraits.infix(op)

    TCmp(
      op,
      dispatchFor(trName, op, l.ty).map { d =>
        val m = traitDecls(trName).methods.find(_.name == CoreTraits.required(trName)._1).get
        checkOperand(op, l.ty, r, resolveType(m.params.head.typ, selfBinding(l.ty)))
        d
      },
    )
  }

  /** `place op= value` where `op` is a trait method — the same dispatch `place op value` makes,
   * handed over as a name so codegen applies it to the value it already loaded from the place
   * rather than reading the place a second time.
   *
   * The result is the place's own type by construction: the catalog declares every arithmetic method
   * as `(self, rhs: Self) -> Self`, and an `impl` conforms to that exactly, so a compound assignment
   * through one cannot change the type of what it updates.
   */
  protected def updateDispatch(op: String, place: TExpr, value: TExpr): Option[TDispatch] =
    for
      trName <- CoreTraits.infix.get(op)
      d      <- dispatchFor(trName, op, place.ty)
    yield {
      val m = traitDecls(trName).methods.find(_.name == CoreTraits.required(trName)._1).get
      checkOperand(op, place.ty, value, resolveType(m.params.head.typ, selfBinding(place.ty)))
      d
    }

  /** `Type.name(args)` — resolves and calls an associated function (a member with no receiver).
   * The positional constructor `Type(…)` is a different form and is handled elsewhere.
   *
   * On a **generic** type there is no receiver to read the type arguments off, so they are inferred
   * the way a generic free function's are: from the arguments, and from the type the context expects
   * where the arguments do not determine them.
   */
  protected def callAssociated(tname: String, mname: String, args: List[Expr], expected: Option[Type]): TExpr =
    memberDecls.get((tname, mname)) match
      case Some(m) if m.receiver.isEmpty && !m.isProperty =>
        genericMembers.get((tname, mname)) match
          case Some(fd) => callGenericAssociated(tname, fd, m, args, expected)
          case None =>
            val fname           = s"$tname.$mname"
            val (params, rtype) = funcInsts(fname)
            if args.length != params.length then
              err(s"associated function '$fname' takes ${quantity(params.length, "argument")}, but ${supplied(args.length, "argument")}")
            val ts = args.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
            funcsUsed += fname
            TCall(fname, checkArgs(fname, params, args, Some(ts)), rtype)
      case Some(m) if m.isProperty =>
        err(s"'$mname' is a property of '$tname' — read it on a value, as 'value.$mname'")
      case Some(_) => err(s"'$mname' is an instance method of '$tname' — call it on a value, not the type")
      case None    => err(s"type '$tname' has no associated function '$mname'")

  /** An associated function of a generic type, at the instantiation this call resolves to.
   *
   * The arity check comes first, because inference reads the arguments against the parameters
   * pairwise and a mismatched count would silently leave a parameter unsolved — reporting a failure
   * to infer where the real mistake is the number of arguments.
   *
   * `Self` is rewritten to the type applied to its own parameters before solving, so a constructor
   * written `-> Self` infers from an expected `Box[int]` exactly as one written `-> Box[T]` does.
   *
   * The two lists of parameters are held to their bounds under the name each was written in. The
   * type's are reported in the type's name — the member inherited them and is not where they are
   * written — and the member's own in the member's, which is where they are.
   */
  private def callGenericAssociated(
      owner: String,
      fd: FuncDecl,
      m: MethodDecl,
      args: List[Expr],
      expected: Option[Type],
  ): TExpr = {
    val shown = qn(fd.name)

    if args.length != fd.params.length then
      err(s"associated function '$shown' takes ${quantity(fd.params.length, "argument")}, " +
        s"but ${supplied(args.length, "argument")}")

    val spell       = genericSelf.get(fd.name).fold((r: TypeRef) => r)((ref, _) => spellSelf(_, ref))
    val provisional = args.map(analyzeExpr(_))
    val targs = inDecl(fd.name)(solve(
      shown,
      fd.tparams,
      fd.params.map(p => spell(p.typ)),
      provisional.map(_.ty),
      fd.retType.map(spell),
      expected,
    ))

    val (ownerTps, ownTps)   = fd.tparams.splitAt(fd.tparams.length - m.tparams.length)
    val (ownerArgs, ownArgs) = targs.splitAt(ownerTps.length)

    inDecl(fd.name) {
      checkParamBounds(qn(owner), ownerTps, fd.bounds, ownerArgs)
      checkParamBounds(shown, ownTps, fd.bounds, ownArgs)
    }

    val name            = instantiateFunc(fd, targs)
    val (params, rtype) = funcInsts(name)

    funcsUsed += name
    TCall(name, checkArgs(fd.name, params, args, Some(provisional)), rtype)
  }

  /** Passes the receiver in the mode the method's `self` declared, inserting the same conversion
   * a matching argument would: a value is copied, `*self` takes the instance's address, `&self`
   * needs the reference itself.
   */
  protected def buildReceiver(mode: RecvMode, tr: TExpr): TExpr = mode match
    case RecvMode.ByValue =>
      tr.ty match
        case _: Type.Named => tr
        case _             => autoDeref(tr)

    case RecvMode.ByPtr =>
      tr.ty match
        case _: Type.Ptr => tr
        case _ =>
          val place = tr.ty match
            case _: Type.Ref => autoDeref(tr)
            case _           => tr
          if !isPlace(place) then
            err("'*self' needs a variable, field, or dereference to point at — this receiver has no address")
          TAddrOf(place, Type.Ptr(place.ty))

    case RecvMode.ByRef(_) =>
      tr.ty match
        case _: Type.Ref => tr
        case _ =>
          err(s"'&self' needs a reference; a ${show(tr.ty)} on the stack has none — take '*self' instead, " +
            "or put the object behind a '&'")

  protected def constructStruct(name: String, args: List[Expr], expected: Option[Type]): TExpr = {
    val decl = structDecls(name)

    if args.length != decl.fields.length then
      err(s"struct '${qn(name)}' has ${quantity(decl.fields.length, "field")}, but ${supplied(args.length, "value")}")

    val (targs, pre) =
      if decl.tparams.isEmpty then (Nil, None)
      else
        expected match
          case Some(s: Type.Struct) if s.base == name => (s.targs, None)
          case _ =>
            val provisional = args.map(analyzeExpr(_))
            val targs =
              inDecl(name)(
                solve(qn(name), decl.tparams, decl.fields.map(_.typ), provisional.map(_.ty), None, expected))
            (targs, Some(provisional))

    val s   = instantiateStruct(name, targs)
    val nnew = TStructNew(s, checkArgs(s.name, s.fields, args, pre))

    // A struct with `invariant` clauses is checked the moment it is built: every construction site —
    // `var a: T = T(…)`, `a = T(…)` — flows through here, so one wrap covers them all. Generic
    // structs have no synthesised invariant function yet (rejected at the declaration), so they build
    // unchecked.
    if decl.invariants.nonEmpty && decl.tparams.isEmpty then TStructInvCheck(nnew, s, invKey(name))
    else nnew
  }

  protected def constructVariant(key: String, args: List[Expr], expected: Option[Type]): TExpr = {
    // The key says which module's variant this is; the name inside the enum is what the enum's own
    // declaration and its instantiation both know it by.
    val name  = Modules.split(key)._2
    val ename = variantOwner(key)
    val decl  = enumDecls(ename)
    val vdecl = decl.variants.find(_.name == name).get

    if vdecl.fields.isEmpty && args.nonEmpty then
      err(s"variant '$name' takes no arguments — write it as '$name'")
    if vdecl.fields.nonEmpty && args.isEmpty then
      err(s"variant '$name' carries data — construct it with '$name(…)'")
    if args.length != vdecl.fields.length then
      err(s"variant '$name' has ${quantity(vdecl.fields.length, "field")}, but ${supplied(args.length, "value")}")

    val (targs, pre) =
      if decl.tparams.isEmpty then (Nil, None)
      else
        expected match
          case Some(e: Type.Enum) if e.base == ename => (e.targs, None)
          case _ =>
            val provisional = args.map(analyzeExpr(_))
            val targs =
              inDecl(ename)(
                solve(name, decl.tparams, vdecl.fields.map(_.typ), provisional.map(_.ty), None, expected))
            (targs, Some(provisional))

    val en = instantiateEnum(ename, targs)
    val v  = en.variant(name).get
    TEnumNew(en, v, checkArgs(name, v.fields, args, pre))
  }

  /** A non-generic simple enum whose name appears in call position, for the two conversions from
   * an integer. The shared checks — not generic, not a data enum, exactly one integer argument —
   * live here so `Color(n)` and `Color.try(n)` reject the same shapes the same way.
   */
  private def simpleEnumArg(name: String, args: List[Expr]): (Type.Enum, TExpr) = {
    val decl  = enumDecls(name)
    val shown = qn(name)
    if decl.tparams.nonEmpty then
      err(s"'$shown' is generic, so it has no single underlying integer type to convert")
    val en = instantiateEnum(name, Nil)
    if !en.simple then
      err(s"'$shown' carries data, so only a simple enum converts to and from an integer")
    if args.length != 1 then
      err(s"'$shown' takes exactly one integer argument")
    val t = analyzeExpr(args.head, Some(en.underlying))
    if !t.ty.isInstanceOf[Type.Integer] then
      err(s"'$shown' converts an integer, but the value has type ${show(t.ty)}")
    (en, t)
  }

  /** `Color(n)` — the checked cast: an integer becomes the enum, trapping at run time on a value
   * that is not a declared discriminant.
   */
  protected def enumFromInt(name: String, args: List[Expr]): TExpr = {
    val (en, t) = simpleEnumArg(name, args)
    TEnumFromInt(t, en)
  }

  /** `Color.try(n)` — the fallible constructor: `Some(Color)` for a declared discriminant, `None`
   * otherwise. The result is an ordinary `Option[Color]`, so nothing downstream is special-cased.
   */
  protected def enumTry(name: String, args: List[Expr]): TExpr = {
    val (en, t) = simpleEnumArg(name, args)
    val optTy   = instantiateEnum("Option", List(en))
    TEnumTry(t, en, optTy, optTy.variant("Some").get, optTy.variant("None").get)
  }

  /** `expr?` — unwraps an `Option`/`Result`, or returns the enclosing function early with the
   * failure re-wrapped in *its* return type. The two enums must agree, and a propagated error
   * must be the one the function returns.
   */
  protected def analyzeTry(t: TExpr): TExpr = {
    val en = t.ty match
      case e: Type.Enum if Prelude.tryVariants(e.base).isDefined => e
      case other => err(s"'?' needs an Option or Result value, not ${show(other)}")

    val (okName, failName) = Prelude.tryVariants(en.base).get

    retTy match
      case ret: Type.Enum if ret.base == en.base =>
        if en.base == "Result" && ret.targs(1) != en.targs(1) then
          err(s"'?' propagates a ${show(en.targs(1))} error, but this function returns ${show(ret.targs(1))}")
        TTry(t, en.variant(okName).get, en.variant(failName).get, ret, ret.variant(failName).get, en.targs.head)
      case other =>
        err(s"'?' may only be used in a function returning ${en.base}, not ${show(other)}")
  }
}
