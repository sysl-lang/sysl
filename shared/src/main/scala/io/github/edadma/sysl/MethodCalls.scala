package io.github.edadma.sysl

/** Calling a method, which is four different resolutions wearing one syntax.
 *
 * `x.m(a)` reaches its definition by a different route depending on what `x` is: a concrete type
 * looks the member up under its mangled name, a bounded type parameter finds it in one of its
 * bounds, a trait object reads it out of the table it carries, and a builtin — `str`, `hash`,
 * a weak reference's `get` — has no declaration at all and is recognised here. An overload set adds
 * a fifth step in front of the other four, since which member is meant depends on the arguments.
 *
 * They are one file because the *choice between them* is the interesting part and it is made in one
 * place. Once made, each route ends in the same free call `CallCore` already knows how to check —
 * the receiver passed in the mode its `self` sigil declared, and nothing else method-specific.
 */
trait MethodCalls extends FuncAddress {

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
  protected def callMethod(recv: Expr, mname: String, args: List[Expr], expected: Option[Type]): TExpr =
    callMethodOn(analyzeExpr(recv), mname, args, expected)

  /** The same, for a receiver already analyzed — what a form that had to look at the receiver's type
   * to know it was a method call uses, so the receiver is analyzed once and the analysis that
   * decided is the one that runs.
   */
  protected def callMethodOn(tr: TExpr, mname: String, args: List[Expr], expected: Option[Type]): TExpr = {
    receiverType(tr.ty) match
      case a: Type.Abstract => callBoundMethod(a, tr, mname, args)
      case t: Type.Trait    => callTraitObject(tr, t, mname, args)
      case w: Type.Weak     => weakGet(w, tr, mname, args)

      // `s.copy()` is the operation that stops a substring holding its parent buffer alive (`04`):
      // the bytes are copied into a string that owns them, so what the result keeps is its own.
      // Parentheses because it allocates and walks the bytes — `08 § Property or method` puts the
      // O(1) projections on the other side of that line, and this is the case the line was drawn for.
      case Type.Str if mname == "copy" =>
        if args.nonEmpty then err("'copy' takes no arguments — it copies the string it is read off")
        TFromBytes(TBytes(tr))

      case rty =>
        val (base, _) = memberKey(rty, mname)
        val chosen    = pickOverload(rty, base, mname, args)
        val targs     = memberKey(rty, chosen)._2

        memberDecls.get((base, chosen)) match
          case Some(m) if m.receiver.isDefined && m.tparams.nonEmpty =>
            checkMemberVisible(base, chosen, m)
            callGenericMethod(genericMembers((base, chosen)), m, targs, tr, args, expected)
          case Some(m) if m.receiver.isDefined =>
            checkMemberVisible(base, chosen, m)
            val fname           = memberFuncName(rty, chosen)
            val (params, rtype) = funcInsts(fname)
            // A closure's `call` is not a method a program wrote, so a complaint about one names
            // the callable and the argument's position rather than the member behind it (`12 §6`).
            val callable = mname == "call" && callableOf(rty).isDefined
            val shown    = if callable then "this callable" else s"method '$fname'"
            checkArity(shown, params.length - 1, m.variadic, args.length)
            // A member's tail begins where its declared parameters stop, and the receiver holds one
            // of the slots — so the cut is one past what a free function's would be.
            val (declared, tail) = args.splitAt(params.length - 1)
            val recvArg  = buildReceiver(m.receiver.get, tr)
            val restArgs = declared.zip(params.tail).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
            funcsUsed += fname
            recheckAfter(recvArg,
              TCall(fname, checkArgs(if callable then shown else fname, params, declared,
                                     Some(recvArg :: restArgs), callable) ::: tail.map(variadicArg(_)), rtype))
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
              .orElse(callableField(rty, mname, tr, args, expected))
              .getOrElse {
                // A call reaches through one level of indirection and only one, exactly as selection
                // does (`08 § Calling a method`). A receiver still carrying a mode after that has
                // more than the shorthand walks, and the complaint below would name what is *left*
                // — a type the reader never wrote — and report a missing method, when the method is
                // there and what stopped short is the reach.
                rty match
                  case _: Type.Ptr | _: Type.Ref =>
                    err(s"a method call reaches through one level of indirection and ${show(tr.ty)} " +
                      s"has more, so the rest is written: '(*x).$mname(…)' calls '$mname' on the " +
                      s"${show(rty)} it leaves")
                  // A member the compiler provides, written with parentheses it does not take. A
                  // property a program *declares* has said so since properties existed; a provided
                  // one fell through to the line below and denied the member outright — the one
                  // answer that is not true of it, since `len` is exactly what a slice has. Anything
                  // reaching here failed to resolve as a call, so the provided members that really
                  // are called — `copy`, and a weak reference's `get` — are already gone above.
                  case _ if builtinMember(rty, mname) =>
                    err(s"'$mname' is a property the compiler provides for '$base' — read it as " +
                      s"'value.$mname', without '()'")
                  case _ => err(s"type '$base' has no method '$mname'")
              }
  }

  /** Which of a type's members a written name means, where more than one implementation of one trait
   * gave the type a member of that name.
   *
   * A name that reaches one member — everything a program writes until a type implements a trait
   * twice — comes straight back, so the ordinary call is the lookup it always was. Where there are
   * several, the arguments decide: each candidate's parameters are compared against the types the
   * arguments already have, and exactly one candidate accepting them is the answer.
   *
   * This is **not** overloading, and the difference is that the answer is determined rather than
   * chosen. A trait's argument list is what tells its implementations apart, and a call carries the
   * argument list in the values it passes — so a call that does not determine one is a call whose
   * arguments name no implementation, which is reported rather than resolved by a preference rule.
   * The arguments are analyzed here with nothing expected of them, and a literal has no type of its
   * own to be matched by, so `c.mul(2)` where the candidates take a `Complex` and a `real` is one of
   * the calls that determines nothing (`08 § One name, one member`).
   */
  protected def pickOverload(owner: String, mname: String, args: List[Expr], subject: String)(
      params: String => Option[List[Type]],
  ): String =
    memberAlts.get((owner, mname)) match
      case None => mname
      case Some(cands) =>
        val supplied = args.map(probeType)
        val from = s"'$mname' comes from ${quantity(cands.length, "implementation")} of one trait on $subject"

        // A candidate that takes a `...` is answered for by its declared parameters alone: the tail
        // stands at none, so what it may be told apart by stops where they do (`12 §9`).
        val fits = cands.filter { c =>
          params(c).exists { ps =>
            val counted = if variadicMember(owner, c) then supplied.length >= ps.length else supplied.length == ps.length

            counted && ps.zip(supplied).forall((p, s) => s.contains(p))
          }
        }

        fits match
          case one :: Nil => one
          case Nil =>
            err(s"$from, and none of them takes (${supplied.map(_.fold("?")(show)).mkString(", ")}) — " +
              "write the argument at the type of the implementation that was meant")
          case _ => err(s"$from, and the arguments do not say which was meant")

  /** Whether one of a type's members takes a `...`, asked of the member table rather than of the
   * lowered signature — a tail is not something a parameter list records.
   */
  private def variadicMember(owner: String, mname: String): Boolean =
    memberDecls.get((owner, mname)).exists(_.variadic)

  /** `value.m(…)`, where the receiver's type names each candidate's instantiation. */
  protected def pickOverload(rty: Type, base: String, mname: String, args: List[Expr]): String =
    pickOverload(base, mname, args, show(rty))(c => probe(funcInsts(memberFuncName(rty, c))._1.tail.map(_._2)))

  /** `Type.f(…)` — an associated function, which has no receiver to drop off the front. */
  protected def pickAssociated(tname: String, mname: String, args: List[Expr]): String =
    pickOverload(tname, mname, args, qn(tname))(c => funcInsts.get(s"$tname.$c").map(_._1.map(_._2)))

  /** The type an argument already has, with nothing expected of it and nothing said about whatever
   * goes wrong — the ordinary analysis that follows reports that, in the place it belongs.
   */
  private def probeType(e: Expr): Option[Type] = probe(analyzeExpr(e).ty)

  /** A question asked of the tables that is allowed to have no answer, with everything it registers
   * on the way dropped and everything it complains about left for the walk that follows.
   */
  protected def probe[T](body: => T): Option[T] =
    sandboxed {
      try Some(body)
      catch
        case AnalyzerError(_, _) => None
        case Poisoned()          => None
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

    checkArity(s"method '$shown'", fd.params.length - 1, m.variadic, args.length)

    // The tail stands at no parameter, so it is kept out of the inference as well as out of the
    // check: what solves the member's own type parameters is the arguments that have one to solve.
    val (passed, tail) = args.splitAt(fd.params.length - 1)
    val spell       = genericSelf.get(fd.name).fold((r: TypeRef) => r)((ref, _) => spellSelf(_, ref))
    val ptypes      = fd.params.tail.map(p => spell(p.typ))
    val provisional = provisionalArgs(fd.name, fd.tparams, ptypes, passed, m.bounds)
    val own = inDecl(fd.name)(solve(
      shown,
      m.tparams,
      ptypes,
      provisional.map(_.ty),
      fd.retType.map(spell),
      expected,
      passed.map(isLiteral),
    ))

    inDecl(fd.name)(checkParamBounds(shown, m.tparams, fd.bounds, own))

    val name            = instantiateFunc(fd, ownerArgs ::: own)
    val (params, rtype) = funcInsts(name)
    val recvArg         = buildReceiver(m.receiver.get, recv)

    recheckAfter(recvArg,
      TCall(name, checkArgs(shown, params, passed, Some(recvArg :: provisional)) ::: tail.map(variadicArg(_)), rtype))
  }

  /** `5.display(out, fmt)` — the rendering a built-in's `Display` membership provides (`14 §5`).
   *
   * It is `5.add(3)`'s sibling and exists for the same reason: a built-in has no `impl` block, so
   * there is no lowered `int.display` to call. What it has is a renderer the library already writes,
   * declared in the argument order `Display` does, so naming it here is the whole lowering — and it
   * is what lets a `Display` written for a struct render the struct's own fields without leaving
   * the allocation-free path the sink exists for.
   */
  private def builtinDisplay(rty: Type, mname: String, recv: TExpr, args: List[Expr]): Option[TExpr] =
    for
      m           <- Option.when(mname == "display")(traitDecls.get(Library.key("Display"))).flatten
      sig         <- m.methods.find(_.name == "display")
      (fname, to) <- CoreTraits.display(rty)
    yield {
      // Read in the **trait's** terms, not the caller's: `Display.display` names the specifier
      // struct, and a program that declares a type of that name means its own by the word
      // everywhere except here.
      val params = inDecl(m.name)(sig.params.map(p => (p.name, rt(p.typ))))
      val key    = Library.key(fname)

      if args.length != params.length then
        err(s"method 'Display.display' takes ${quantity(params.length, "argument")}, " +
          s"but ${supplied(args.length, "argument")}")

      val self = rendered(buildReceiver(RecvMode.ByValue, recv), to)

      funcsUsed += key
      TCall(key, self :: checkArgs("Display.display", params, args, None), Type.Unit)
    }

  /** `k.hash()` — the mixing a built-in's `Hash` membership provides (`14 §5`).
   *
   * `builtinDisplay`'s sibling, and built the same way for the same reason: a built-in has no
   * `impl` block, so the lowering is a library function named here. Where `Display` writes into a
   * sink this returns a number, so what the widening is for is the law rather than the signature —
   * every integer, `char`, and `bool` reaches one mixer at 64 bits, so two values that compare
   * equal across widths hash equal too.
   */
  private def builtinHash(rty: Type, mname: String, recv: TExpr, args: List[Expr]): Option[TExpr] =
    for
      _           <- Option.when(mname == "hash")(traitDecls.get(Library.key("Hash"))).flatten
      (fname, to) <- CoreTraits.hash(rty)
    yield {
      val key = Library.key(fname)

      if args.nonEmpty then
        err(s"method 'Hash.hash' takes no arguments, but ${supplied(args.length, "argument")}")

      val self = widen(buildReceiver(RecvMode.ByValue, recv), to)

      funcsUsed += key
      TCall(key, List(self), Type.Integer(64, signed = false))
    }

  /** `w.get()` — the one thing a `weak T` can be asked (`03`).
   *
   * It yields an ordinary `Option[&T]`, so everything downstream of it — matching, `unwrap`, `?` —
   * is the surface `Option` already has, and nothing about a weak reference reaches further into
   * the language than this call.
   */
  private def weakGet(w: Type.Weak, recv: TExpr, mname: String, args: List[Expr]): TExpr = {
    if mname != "get" then
      err(s"a ${show(w)} has no method '$mname' — a weak reference may be gone, so 'get()' is the " +
        s"only thing to ask one, and what it hands back is what has methods")
    if args.nonEmpty then err(s"'get' takes no arguments")

    val optTy = instantiateEnum(Library.key("Option"), List(w.strong))
    TUpgrade(recv, optTy, optTy.variant("Some").get, optTy.variant("None").get)
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
      decl   <- traitDecls.get(Library.key(trName))
      m      <- decl.methods.find(_.name == mname)
    yield {
      // A built-in's membership is homogeneous (`14 §5`), so the trait's own parameter is the
      // receiver's own type: `5.add(3)` is the `Add[int]` an `int` has, and it has no other.
      //
      // The signature is the **trait's**, so it is resolved in the trait's terms however far from it
      // the call was written — the same rule an instantiated function's signature follows. Reading
      // it here would let a program that declares a type the trait's signature names mean its own by
      // the word, and `Display.display`'s specifier is exactly such a name.
      val params = inDecl(decl.name) {
        m.params.map(p => (p.name, resolveType(p.typ, selfBinding(rty) ++ decl.tparams.map(_ -> rty))))
      }

      if args.length != params.length then
        err(s"method '$trName.$mname' takes ${quantity(params.length, "argument")}, " +
          s"but ${supplied(args.length, "argument")}")

      val self             = buildReceiver(RecvMode.ByValue, recv)
      val ts               = checkArgs(s"$trName.$mname", params, args, None)
      val (_, op, kind)    = CoreTraits.required(trName)

      kind match
        case CoreTraits.Kind.Arith   => produced(TBinary(op, self, ts.head, arithType(op, self.ty, ts.head.ty)))
        case CoreTraits.Kind.Compare => TCompare(List(self, ts.head), List(TCmp(op)))
        case CoreTraits.Kind.Prefix  => produced(TUnary(op, self, unaryType(self.ty)))
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
          checkArity(s"method '$fname'", params.length, m.variadic, args.length)
          val (declared, tail) = args.splitAt(params.length)
          val ts    = declared.zip(params).map { case (arg, (_, pty)) => analyzeExpr(arg, Some(pty)) }
          val rtype = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
          TCall(fname, recv :: (checkArgs(fname, params, declared, Some(ts)) ::: tail.map(variadicArg(_))), rtype)
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
      .flatMap(traitClosure(_, selfBinding(a)))
      .flatMap(b => traitDecls.get(b.name).map((b, _)))
      .flatMap { case (b, decl) =>
        // Padded rather than zipped, because a bound whose own resolution failed is recorded with
        // whatever survived of its arguments — and reading a member against a short list would
        // report the trait's signature as unresolvable, which is a second complaint about the one
        // mistake already being reported.
        decl.methods
          .find(_.name == mname)
          .map(m => (b, selfBinding(a) ++ decl.tparams.zipAll(b.args, "", Type.Unknown).filter(_._1.nonEmpty), m))
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
    // A trait offers the members of the traits it requires as well as its own, and both kinds are
    // one slot in one table — so this is the whole list, in the order the table was laid out.
    val members = traitMembers(Type.Bound(t.name, t.args))

    members.zipWithIndex.find(_._1._2.name == mname) match
      case None =>
        err(s"trait '${t.name}' declares no method '$mname' — it has " +
          members.map(_._2.name).mkString("'", "', '", "'"))
      case Some(((_, m), _)) if m.isProperty =>
        err(s"'$mname' is a property of '${t.name}' — read it as 'value.$mname', without '()'")
      case Some(((from, m), slot)) =>
        // A signature is read under the parameters of the trait that *declared* it, at the arguments
        // the object's type fixed for it — an object over `Sink[int]` takes an `int`. `Self` is not
        // in the substitution at all: object safety already refused any trait that mentions one away
        // from its receiver, so no signature reaching here contains one.
        val subst: Map[String, Type] = traitDecls(from.name).tparams.zip(from.args).toMap
        val params                   = m.params.map(p => (p.name, resolveType(p.typ, subst)))
        // A boxed callable's `call` is the same non-member the inlined one's is, and reads the same
        // way in a message — the arity-carrying trait behind it is the compiler's business.
        val callable = Type.Fn.isCall(from.name)
        val fname    = if callable then "this callable" else s"method '${from.name}.$mname'"

        if args.length != params.length then
          err(s"$fname takes ${quantity(params.length, "argument")}, " +
            s"but ${supplied(args.length, "argument")}")

        val ts    = args.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
        val rtype = m.retType.map(resolveReturn(_, subst)).getOrElse(Type.Unit)

        TVCall(recv, slot, checkArgs(fname, params, args, Some(ts), callable), rtype)
  }

  /** `obj.p` on a `*Trait` or a `&Trait` — a property read through the table, which is the same
   * indirect call a method is with no arguments to pass and no parentheses to write.
   *
   * An object has no fields at all: the layout is exactly what erasure forgot. So every name read off
   * one is either a property the trait declares or a mistake, and the second half of this says which.
   */
  protected def readTraitObjectProperty(recv: TExpr, t: Type.Trait, name: String): TExpr = {
    traitMembers(Type.Bound(t.name, t.args)).zipWithIndex.find(_._1._2.name == name) match
      case Some(((from, m), slot)) if m.isProperty =>
        val subst: Map[String, Type] = traitDecls(from.name).tparams.zip(from.args).toMap

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
}
