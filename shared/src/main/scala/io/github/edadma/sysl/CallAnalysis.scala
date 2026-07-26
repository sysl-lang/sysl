package io.github.edadma.sysl

/** Calls and construction: free functions, methods, associated functions, struct and enum
 * construction, and the `?` operator. A member call resolves exactly as a free call does —
 * the analyzer lowered every member to a function under the mangled name `Type.member` — so the
 * only method-specific work here is passing the receiver in the mode its `self` sigil declared.
 */
trait CallAnalysis extends Literals {

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
    // headed for a `&T` parameter is boxed here instead of at its own analysis.
    val ts = pre match
      case Some(provisional) => provisional.zip(params).map { case (t, (_, pty)) => box(t, pty) }
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
              err(s"'${f.name}' requires its type parameter '$tp' to implement '$tr', " +
                s"but ${show(concrete)} does not")

  /** The name codegen emits for a member call. A member of a concrete type was hoisted eagerly
   * under `Type.member`; a member of a generic type is instantiated here, from the receiver's own
   * type arguments, and its body queued for analysis — so both resolve to a name that `funcInsts`
   * holds.
   */
  protected def memberFuncName(base: String, mname: String, targs: List[Type]): String =
    if nominalTparams(base).isEmpty then s"$base.$mname"
    else instantiateFunc(genericMembers((base, mname)), targs)

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
      case rty =>
        val (base, targs) = memberOwner(rty)

        memberDecls.get((base, mname)) match
          case Some(m) if m.receiver.isDefined =>
            val fname           = memberFuncName(base, mname, targs)
            val (params, rtype) = funcInsts(fname)
            if args.length != params.length - 1 then
              err(s"method '$fname' takes ${quantity(params.length - 1, "argument")}, " +
                s"but ${supplied(args.length, "argument")}")
            val recvArg  = buildReceiver(m.receiver.get, tr)
            val restArgs = args.zip(params.tail).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
            TCall(fname, checkArgs(fname, params, args, Some(recvArg :: restArgs)), rtype)
          case Some(_) => err(s"'$mname' is a property of '$base' — read it as 'value.$mname', without '()'")
          case None    => builtinMethod(rty, mname, tr, args).getOrElse(err(s"type '$base' has no method '$mname'"))
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
        case CoreTraits.Kind.Compare => TCompare(List(self, ts.head), List(op))
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
          if m.receiver.isEmpty then
            err(s"'$mname' is a property of '$trName' — read it as 'value.$mname', without '()'")
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
    if CoreTraits.builtin(trName, recv.ty) then None
    else
      recv.ty match
        // At the definition, where `T` stands for itself: the bound is the whole of what licenses
        // the operator, and saying which bound is missing is the point of checking here at all.
        case a: Type.Abstract =>
          if !a.bounds.contains(trName) then boundErr(s"'$op' needs '${a.name}: $trName'")
          Some(dispatch(trName, op, s"$trName.${CoreTraits.required(trName)._1}", rhs, recv))

        case ty if satisfies(trName, ty) =>
          Some(dispatch(trName, op, s"${ownerKey(ty)}.${CoreTraits.required(trName)._1}", rhs, recv))

        case _ => None

  /** Builds the call an operator means, applying the derivation for the four comparisons the
   * catalog does not declare a method for (`§2`).
   */
  private def dispatch(trName: String, op: String, fname: String, rhs: Option[TExpr], recv: TExpr): TExpr = {
    val self = selfBinding(recv.ty)
    val m    = traitDecls(trName).methods.find(_.name == CoreTraits.required(trName)._1).get
    val rty  = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)

    for (b, p) <- rhs.zip(m.params.headOption) do
      val want = resolveType(p.typ, self)
      at(b.pos)(if disagree(b.ty, want) then err(s"'$op' needs matching types, got ${show(recv.ty)} and ${show(b.ty)}"))

    val (swap, negate) = CoreTraits.derivation.getOrElse(op, (false, false))
    val args           = rhs.toList
    val call           = TCall(fname, if swap then args :+ recv else recv :: args, rty)

    if negate then TUnary("!", call, Type.Bool) else call
  }

  /** Rejects an operator on a trait-dispatched type in a position whose lowering shares an operand.
   *
   * A chained comparison evaluates each operand **once** and compares it against both neighbours,
   * and `a += b` updates the place it read — both need the operand held between two uses, which the
   * scalar lowering does with a register and a trait call has nowhere to put. Refused with a
   * diagnostic that says how to write it, rather than silently evaluating something twice.
   */
  protected def noSharedOperand(op: String, ty: Type, write: String): Unit =
    if CoreTraits.infix.get(op).exists(tr => !CoreTraits.builtin(tr, ty) && dispatched(tr, ty)) then
      err(s"'$op' on ${show(ty)} is a trait method, which this form cannot share an operand with " +
        s"— write it as $write")

  private def dispatched(trName: String, ty: Type): Boolean = ty match
    case a: Type.Abstract => a.bounds.contains(trName)
    case _                => satisfies(trName, ty)

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
