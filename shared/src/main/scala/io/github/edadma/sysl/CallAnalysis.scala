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
    for (t, (pname, pty)) <- ts.zip(params) do
      at(t.pos):
        if disagree(t.ty, pty) then err(s"'$pname' of '$what' is ${show(pty)}, but ${show(t.ty)} was given")

    ts
  }

  protected def callFunction(f: FuncDecl, args: List[Expr], expected: Option[Type]): TExpr = {
    // A variadic callee — foreign or sysl's own — fixes only where its declared parameters stop;
    // everything after them is the tail, checked by the rule below rather than against a parameter.
    val variadic = f.variadic

    if variadic then
      if args.length < f.params.length then
        err(s"function '${f.name}' takes at least ${quantity(f.params.length, "argument")}, " +
          s"but ${supplied(args.length, "argument")}")
    else if args.length != f.params.length then
      err(s"function '${f.name}' takes ${quantity(f.params.length, "argument")}, but ${supplied(args.length, "argument")}")

    // An extern is declared in the output only if something reaches it, which is what keeps an
    // unused one — the prelude's `exit`, in a program that never panics — out of the module.
    if externDecls.contains(f.name) then externsUsed += f.name

    val (name, pre) =
      if f.tparams.isEmpty then (f.name, None)
      else
        val provisional = args.map(analyzeExpr(_))
        val targs =
          solve(f.name, f.tparams, f.params.map(_.typ), provisional.map(_.ty), f.retType, expected)
        checkBounds(f, targs)
        (instantiateFunc(f, targs), Some(provisional))

    val (params, rtype) = funcInsts(name)
    val declared        = checkArgs(f.name, params, args.take(params.length), pre)

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
   */
  protected def checkBounds(f: FuncDecl, targs: List[Type]): Unit =
    if f.bounds.nonEmpty then
      val subst = f.tparams.zip(targs).toMap
      for (tp, traits) <- f.bounds; tr <- traits do
        subst(tp) match
          // A type parameter standing in for itself, during the definition-time pass. It is not a
          // type anything has an `impl` for, so what it can promise a callee is exactly what its
          // own bounds promise: a bound is satisfied by a bound.
          case a: Type.Abstract =>
            if !a.bounds.contains(tr) then
              boundErr(s"'${f.name}' requires its type parameter '$tp' to implement '$tr', " +
                s"but '${a.name}' is not bounded by it")
          case concrete =>
            if !satisfies(tr, concrete) then
              // A type an implementation covers is told what that implementation asked of it, so the
              // reader is sent one step in — to the argument that fails — rather than to a block
              // that is already written.
              val why = unmetBound(tr, concrete).fold("")(reason => s" — $reason")

              err(s"'${f.name}' requires its type parameter '$tp' to implement '$tr', " +
                s"but ${show(concrete)} does not$why")

  /** `value.method(args)` — resolves `method` as an inherent member of the receiver's type and
   * calls the function it lowered to, passing the receiver as the first argument in whatever
   * memory mode the method's `self` asked for.
   *
   * The receiver may be of *any* type: every type has an owner key its members are filed under, so
   * `5.show()` takes this path exactly as `p.show()` does.
   */
  protected def callMethod(recv: Expr, mname: String, args: List[Expr]): TExpr = {
    val tr = analyzeExpr(recv)

    receiverType(tr.ty) match
      case a: Type.Abstract => callBoundMethod(a, tr, mname, args)
      case t: Type.Trait    => callTraitObject(tr, t, mname, args)
      case rty =>
        val (base, _) = memberKey(rty, mname)

        memberDecls.get((base, mname)) match
          case Some(m) if m.receiver.isDefined =>
            val fname           = memberFuncName(rty, mname)
            val (params, rtype) = funcInsts(fname)
            if args.length != params.length - 1 then
              err(s"method '$fname' takes ${quantity(params.length - 1, "argument")}, " +
                s"but ${supplied(args.length, "argument")}")
            val recvArg  = buildReceiver(m.receiver.get, tr)
            val restArgs = args.zip(params.tail).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
            TCall(fname, checkArgs(fname, params, args, Some(recvArg :: restArgs)), rtype)
          case Some(_) => err(s"'$mname' is a property of '$base' — read it as 'value.$mname', without '()'")
          case None =>
            builtinMethod(rty, mname, tr, args)
              .orElse(builtinDisplay(rty, mname, tr, args))
              .getOrElse(err(s"type '$base' has no method '$mname'"))
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
  private def callBoundMethod(a: Type.Abstract, recv: TExpr, mname: String, args: List[Expr]): TExpr = {
    val declared =
      a.bounds.flatMap(traitDecls.get).flatMap(t => t.methods.find(_.name == mname).map((t.name, _)))

    declared.headOption match
      case None => unlicensed(a, mname)
      case Some((trName, m)) =>
        reported {
          val fname = s"$trName.$mname"
          if m.isProperty then
            err(s"'$mname' is a property of '$trName' — read it as 'value.$mname', without '()'")
          if m.receiver.isEmpty then
            err(s"'$mname' is an associated function of '$trName', so it has no receiver — a value " +
              "cannot be the thing it is called on")
          // `Self` in the trait's signature is the parameter itself here, which is what makes
          // `Add::add` yield a `T` and `Ord::lt` a `bool` inside a body that has not met a concrete
          // type yet (`14 §4`).
          val self   = selfBinding(a)
          val params = m.params.map(p => (p.name, resolveType(p.typ, self)))
          if args.length != params.length then
            err(s"method '$fname' takes ${quantity(params.length, "argument")}, " +
              s"but ${supplied(args.length, "argument")}")
          val ts    = args.zip(params).map { case (arg, (_, pty)) => analyzeExpr(arg, Some(pty)) }
          val rtype = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
          TCall(fname, recv :: checkArgs(fname, params, args, Some(ts)), rtype)
        }
  }

  /** `x.p` where `x` is a type parameter and `p` is a property one of its bounds declares — the read
   * `callBoundMethod` is to a call, checked the same way and for the same reason.
   *
   * A field would be refused here whatever the bounds said, because a field is layout and no promise
   * about behaviour reaches one (`10 §5`). A property is the opposite case: it is behaviour that
   * happens to be spelled like a field, so a trait can promise it, and a bounded body may read one
   * exactly as it may call a method.
   */
  protected def readBoundProperty(a: Type.Abstract, recv: TExpr, name: String): TExpr = {
    val declared =
      a.bounds.flatMap(traitDecls.get).flatMap(t => t.methods.find(_.name == name).map((t.name, _)))

    declared.headOption match
      case None => unlicensedProperty(a, name)
      case Some((trName, m)) =>
        reported {
          if !m.isProperty then
            err(s"'$name' is a method of '$trName' — call it with '$name(…)'")
          val self  = selfBinding(a)
          val rtype = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
          TCall(s"$trName.$name", List(recv), rtype)
        }
  }

  /** The diagnostic for a property read that no bound licenses.
   *
   * With no trait declaring one of that name there is nothing a bound could be, and the honest
   * complaint is the older one about a field: a type parameter has no layout to read. Where a trait
   * *does* declare it, the fix is the bound, and naming it is what checking at the definition is for.
   */
  private def unlicensedProperty(a: Type.Abstract, name: String): Nothing =
    traitDecls.values.filter(_.methods.exists(m => m.name == name && m.isProperty)).map(_.name).toList match
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

    decl.methods.zipWithIndex.find(_._1.name == mname) match
      case None =>
        err(s"trait '${t.name}' declares no method '$mname' — it has " +
          decl.methods.map(_.name).mkString("'", "', '", "'"))
      case Some((m, _)) if m.isProperty =>
        err(s"'$mname' is a property of '${t.name}' — read it as 'value.$mname', without '()'")
      case Some((m, slot)) =>
        val params = m.params.map(p => (p.name, rt(p.typ)))
        val fname  = s"${t.name}.$mname"

        if args.length != params.length then
          err(s"method '$fname' takes ${quantity(params.length, "argument")}, " +
            s"but ${supplied(args.length, "argument")}")

        val ts    = args.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
        val rtype = m.retType.map(resolveReturn(_, Map.empty)).getOrElse(Type.Unit)

        TVCall(recv, slot, checkArgs(fname, params, args, Some(ts)), rtype)
  }

  /** `obj.p` on a `*Trait` or a `&Trait` — a property read through the table, which is the same
   * indirect call a method is with no arguments to pass and no parentheses to write.
   *
   * An object has no fields at all: the layout is exactly what erasure forgot. So every name read off
   * one is either a property the trait declares or a mistake, and the second half of this says which.
   */
  protected def readTraitObjectProperty(recv: TExpr, t: Type.Trait, name: String): TExpr = {
    val decl = traitDecls(t.name)

    decl.methods.zipWithIndex.find(_._1.name == name) match
      case Some((m, slot)) if m.isProperty =>
        TVCall(recv, slot, Nil, m.retType.map(resolveReturn(_, Map.empty)).getOrElse(Type.Unit))
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
    traitDecls.values.filter(_.methods.exists(_.name == mname)).map(_.name).toList match
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
          if !a.bounds.contains(trName) then boundErr(s"'$op' needs '${a.name}: $trName'")
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
   * The expected type plays no part: an associated function's result is the one it declares, and
   * there is nothing to infer from the context because an associated function on a *generic* type
   * is rejected at its declaration (`Hoisting`). That parameter comes back with those.
   */
  protected def callAssociated(tname: String, mname: String, args: List[Expr]): TExpr =
    memberDecls.get((tname, mname)) match
      case Some(m) if m.receiver.isEmpty && !m.isProperty =>
        val fname           = s"$tname.$mname"
        val (params, rtype) = funcInsts(fname)
        if args.length != params.length then
          err(s"associated function '$fname' takes ${quantity(params.length, "argument")}, but ${supplied(args.length, "argument")}")
        val ts = args.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
        TCall(fname, checkArgs(fname, params, args, Some(ts)), rtype)
      case Some(m) if m.isProperty =>
        err(s"'$mname' is a property of '$tname' — read it on a value, as 'value.$mname'")
      case Some(_) => err(s"'$mname' is an instance method of '$tname' — call it on a value, not the type")
      case None    => err(s"type '$tname' has no associated function '$mname'")

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
      err(s"struct '$name' has ${quantity(decl.fields.length, "field")}, but ${supplied(args.length, "value")}")

    val (targs, pre) =
      if decl.tparams.isEmpty then (Nil, None)
      else
        expected match
          case Some(s: Type.Struct) if s.base == name => (s.targs, None)
          case _ =>
            val provisional = args.map(analyzeExpr(_))
            val targs =
              solve(name, decl.tparams, decl.fields.map(_.typ), provisional.map(_.ty), None, expected)
            (targs, Some(provisional))

    val s = instantiateStruct(name, targs)
    TStructNew(s, checkArgs(s.name, s.fields, args, pre))
  }

  protected def constructVariant(name: String, args: List[Expr], expected: Option[Type]): TExpr = {
    val ename = variantOwner(name)
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
              solve(name, decl.tparams, vdecl.fields.map(_.typ), provisional.map(_.ty), None, expected)
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
    val decl = enumDecls(name)
    if decl.tparams.nonEmpty then
      err(s"'$name' is generic, so it has no single underlying integer type to convert")
    val en = instantiateEnum(name, Nil)
    if !en.simple then
      err(s"'$name' carries data, so only a simple enum converts to and from an integer")
    if args.length != 1 then
      err(s"'$name' takes exactly one integer argument")
    val t = analyzeExpr(args.head, Some(en.underlying))
    if !t.ty.isInstanceOf[Type.Integer] then
      err(s"'$name' converts an integer, but the value has type ${show(t.ty)}")
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
