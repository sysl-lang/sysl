package sh.sysl

/** The call forms that are neither a method on a value nor an operator: an associated function
 * reached through its type's name, struct and enum construction, and the `?` operator.
 *
 * They sit on top of the call layering — `CallCore` for what every call checks, `MethodCalls` for
 * the four ways a receiver finds its member, `OperatorCalls` for the ones spelled with a token —
 * because each of them is one of those underneath. Construction is an associated function the
 * compiler wrote; `?` is a match the compiler wrote. What is here is the part that is *not* the
 * call: which type a bare name means, how a receiver is built in the mode its `self` declared, and
 * what an enum's arity error should say when the enum has no payload to have got wrong.
 */
trait CallAnalysis extends OperatorCalls {

  /** The type a bare name stands for in call position where no *value* of that name is nearer and
   * no declaration table claims it: a type parameter of whatever is being analyzed, which is the
   * body's own `T` and the `Self` a member's is bound to, or a built-in.
   *
   * A struct, an enum and a constrained subtype are named through `typeKey` by the forms above,
   * which have their own readings to offer — a construction, a variant, a cast. What is left is the
   * two kinds a name in this position could only ever have meant as a type.
   */
  protected def typeNamed(written: String): Option[Type] =
    tsubst.get(written).orElse(scalarType(written))

  /** `T.f(…)` — an associated function reached through a **type** rather than through a value of one
   * (`02 § Reaching a trait's members without a value`).
   *
   * The two cases are the two things a name in that position can stand for. A **parameter** reaches
   * what its bounds promise, checked against the trait's signature exactly as a method call on a
   * value of the parameter is — one walk standing in for every instantiation. Anything **concrete**
   * reaches its own member table, which is the same lookup `Box.of(…)` makes, and arrives there by a
   * different route only because a built-in's name is not one of the declaration tables.
   */
  protected def callTypeAssociated(
      ty: Type,
      written: String,
      mname: String,
      args: List[Expr],
      expected: Option[Type],
  ): TExpr = ty match
    case a: Type.Abstract => callBoundAssociated(a, mname, args)
    case concrete =>
      val key = memberKey(concrete, mname)._1

      if !memberDecls.contains((key, mname)) then
        err(s"${show(concrete)} has no associated function '$mname'" +
          (if hasMember(concrete, mname) then s" — '$mname' is reached on a value of one" else ""))

      callAssociated(key, mname, args, expected, boundTraits(written))

  /** The traits a **type parameter** was bounded by, as keys, and nothing for a name that is not one.
   *
   * This is what an instantiated generic body reaches a member through: `T` has become `int` by the
   * time the call is analyzed, and the bound is the only record of which of `int`'s members under
   * that name the body was promised.
   */

  /** `Type.name(args)` — resolves and calls an associated function (a member with no receiver).
   * The positional constructor `Type(…)` is a different form and is handled elsewhere.
   *
   * On a **generic** type there is no receiver to read the type arguments off, so they are inferred
   * the way a generic free function's are: from the arguments, and from the type the context expects
   * where the arguments do not determine them.
   */
  protected def callAssociated(
      tname: String,
      mname: String,
      written: List[Expr],
      expected: Option[Type],
      via: Set[String] = Set.empty,
  ): TExpr =
    val chosen = pickAssociated(tname, mname, written, via)

    memberDecls.get((tname, chosen)) match
      case Some(m) if m.receiver.isEmpty && !m.isProperty =>
        checkMemberVisible(tname, chosen, m)
        // A member's default is written in its type's file, so the type's key is the scope it is
        // read in — and it is a top-level declaration, which a member key is not.
        val args =
          bindArgs(s"associated function '$tname.$chosen'", Some(tname), m.params, written, m.variadic)

        genericMembers.get((tname, chosen)) match
          case Some(fd) => callGenericAssociated(tname, fd, m, args, expected)
          case None =>
            val fname = s"$tname.$chosen"
            // The receiverless third of the same window `MethodCalls` and `MemberExprAnalysis`
            // guard: a member is filed before its signature is built, so one whose signature did
            // not resolve is a declaration with no lowered form. The mistake was reported at that
            // declaration, so the call is abandoned in silence rather than reaching for a
            // signature that was never recorded.
            val (params, rtype) = funcInsts.getOrElse(fname, poisoned())
            checkArity(s"associated function '$fname'", params.length, m.variadic, args.length)
            val (declared, tail) = args.splitAt(params.length)
            val ts = declared.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
            funcsUsed += fname
            TCall(fname, checkArgs(fname, params, declared, Some(ts)) ::: tail.map(variadicArg(_)), rtype)
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

    checkArity(s"associated function '$shown'", fd.params.length, m.variadic, args.length)

    val (passed, tail) = args.splitAt(fd.params.length)
    val spell       = genericSelf.get(fd.name).fold((r: TypeRef) => r)((ref, _) => spellSelf(_, ref))
    val ptypes      = fd.params.map(p => spell(p.typ))
    val provisional = provisionalArgs(fd.name, fd.tparams, ptypes, passed, m.bounds)
    val targs = inDecl(fd.name)(solve(
      shown,
      fd.tparams,
      ptypes,
      provisional.map(_.ty),
      fd.retType.map(spell),
      expected,
      passed.map(isLiteral),
      m.bounds,
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
    TCall(name, checkArgs(fd.name, params, passed, Some(provisional)) ::: tail.map(variadicArg(_)), rtype)
  }

  /** Passes the receiver in the mode the method's `self` declared, inserting the same conversion
   * a matching argument would: a value is copied, `*self` takes the instance's address, `&self`
   * needs the reference itself.
   */
  protected def buildReceiver(mode: RecvMode, tr: TExpr, member: String = ""): TExpr = mode match
    case RecvMode.ByValue =>
      val recv = tr.ty match
        case _: Type.Named => tr
        case _             => autoDeref(tr)

      // A by-value receiver is a **copy**, made by the caller, so calling one needs the layout
      // exactly as building the value does (`15 §9`). Without this an opaque type's `self` methods
      // are reachable from outside and the copy is laid out to the fields as they stood when that
      // caller was compiled — which is the silent ABI break the modifier exists to prevent, since
      // adding a field to the library would leave the call site copying the old shape. `*self` and
      // `&self` need no shape and stay reachable, which is what makes them the forms to write.
      Type.underlying(recv.ty) match
        case s: Type.Struct => checkLayoutKnown(s.base, s.name)
        case _              => ()

      recv

    case RecvMode.ByPtr =>
      tr.ty match
        case _: Type.Ptr => tr
        case _ =>
          val place = tr.ty match
            case _: Type.Ref => autoDeref(tr)
            case _           => tr
          if !isPlace(place) then
            err("'*self' needs a variable, field, or dereference to point at — this receiver has no address")
          // **A `*self` receiver is an `&` the caller did not write, and it is asked the same
          // question.** Assignment and an explicit `&` both refuse a `val`, so a method that takes
          // the address of one to write through it has to be refused as well — otherwise `val` says
          // read-only and means nothing, and a module-level `val`, which is emitted as `constant`,
          // would have a store aimed into read-only storage.
          //
          // The message names the *member*, because the write is not here: the caller wrote a call
          // and the assignment is a line inside somebody else's body.
          if readOnly(place) then
            // **The message says what to write**, because one `val` produces one of these per
            // mutating call — a five-line program that binds a table with `val` gets five, and
            // every one of them is about the same word. Naming the binding is what makes the fix
            // findable from any of the five.
            val fix = place match
              case TLoad(name, _) => s" — write 'var ${name.takeWhile(_ != '.')}' if it is meant to change"
              case _              => " — write 'var' if it is meant to change"

            err(s"'$member' takes '*self', so it writes through what it is called on, and a 'val' " +
              s"is written once$fix")
          // A `*self` method is handed somewhere to write, so it is the one call that could move
          // storage a live `ref` is standing on (`03 § ref`). It is asked here because this is where
          // the receiver becomes a place the caller can be told about.
          checkRefCall(place)
          TAddrOf(place, Type.Ptr(place.ty))

    case RecvMode.ByRef(_) =>
      tr.ty match
        case _: Type.Ref => tr
        // **What is missing is the COUNT, not an address** — and the old wording said "put the
        // object behind a '&'", which names the `&` in a *type* while the reader reaches for the
        // `&` *operator*, the one thing that cannot answer this. A `&self` method may keep the
        // receiver, so it needs a box to keep a share of; a local `var` is refused here for exactly
        // the same reason a fresh construction is, and neither is a temporary problem.
        //
        // So the message names the **binding**, which is where the box is made and where the fix
        // can be seen from any of the calls that report this.
        case _ =>
          err(s"'&self' needs a counted reference, and this receiver is a ${show(tr.ty)} on the " +
            s"stack — a '&self' method may keep hold of what it is called on, so what it wants is " +
            s"a share of a box rather than an address. Bind the value into one and call it on " +
            s"that: 'var r: &${show(tr.ty)} = …'")

  protected def constructStruct(name: String, written: List[Expr], expected: Option[Type]): TExpr = {
    val decl = structDecls(name)

    // A field is a named parameter of the constructor, so a value may be written at the name of the
    // field it is for. A field declares no default (`12 §2a`), so nothing is filled here — the
    // constructor still writes every field, and this only lets the call say which is which.
    val args = bindArgs(s"struct '${qn(name)}'", Some(name), decl.fields, written)

    if args.length != decl.fields.length then
      err(s"struct '${qn(name)}' has ${quantity(decl.fields.length, "field")}, but ${supplied(args.length, "value")}")

    // The positional constructor writes every field, so a restricted one puts it out of reach
    // (`08 § Visibility`) — a private field a caller could still set by position would restrict
    // nothing worth restricting.
    checkEveryFieldVisible(name, decl.fields.map(_.name), "the constructor",
      "build it through an associated function of its own")

    // Building one writes every field in order, which is the layout — so a type whose layout is
    // withheld cannot be built from out here whatever its fields say (`15 §9`). This is not covered
    // by resolving the name as a type: a construction never names one, it names the constructor.
    checkLayoutKnown(name, qn(name))

    val (targs, pre) =
      if decl.tparams.isEmpty then (Nil, None)
      else
        expected match
          case Some(s: Type.Struct) if s.base == name => (s.targs, None)
          case _ =>
            val provisional = provisionalArgs(name, decl.tparams, decl.fields.map(_.typ), args)
            val targs =
              inDecl(name)(
                solve(qn(name), decl.tparams, decl.fields.map(_.typ), provisional.map(_.ty), None, expected,
                  args.map(isLiteral)))
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

  /** A variant construction — `Circle(3)`, or a bare `Empty`.
   *
   * `owner` is the enum, where the site already knows it: a qualified `Shape.Circle` names it, and
   * passing it through is what makes that spelling the answer to an ambiguity rather than another
   * way of asking the same question. A bare name leaves it `None` and the enum is worked out from
   * the expected type (`variantOwnerOf`).
   */
  protected def constructVariant(key: String, written: List[Expr], expected: Option[Type],
                                 owner: Option[String] = None): TExpr = {
    // The key says which module's variant this is; the name inside the enum is what the enum's own
    // declaration and its instantiation both know it by.
    val name  = Modules.split(key)._2
    val ename = owner.orElse(variantOwnerOf(key, expected)).getOrElse(ambiguousVariant(name, key))
    val decl  = enumDecls(ename)
    val vdecl = decl.variants.find(_.name == name).get

    // A variant's payload is named the same way a struct's fields are, and takes a name at the call
    // for the same reason.
    val args = bindArgs(s"variant '$name'", Some(ename), vdecl.fields, written)

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
            val provisional = provisionalArgs(ename, decl.tparams, vdecl.fields.map(_.typ), args)
            val targs =
              inDecl(ename)(
                solve(name, decl.tparams, vdecl.fields.map(_.typ), provisional.map(_.ty), None, expected,
                  args.map(isLiteral)))
            (targs, Some(provisional))

    val en = instantiateEnum(ename, targs)
    val v  = en.variant(name).get
    TEnumNew(en, v, checkArgs(name, v.fields, args, pre))
  }

  /** A bare variant name that two enums answer to, at a site with nothing to choose by.
   *
   * The message names every candidate and shows the qualified spelling, because that is the fix —
   * and it shows it on the **first** one rather than on all of them, since a reader who can see the
   * form can apply it to whichever they meant.
   */
  private def ambiguousVariant(name: String, key: String): Nothing = {
    val owners = variantOwnerList(key)
    val which  = owners.map(o => s"'${qn(o)}'")

    err(s"'$name' is a variant of ${which.init.mkString(", ")} and ${which.last}, and nothing here " +
      s"says which — qualify it, as '${qn(owners.head)}.$name'")
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
    // Asked of `repr` rather than of the written type, because a **transparent** subtype *is* its
    // base (`16 §1`) and its values flow where the base's do. `repr` strips exactly that and leaves
    // a `new` derivation standing, which is the other half of the same rule: a derived type does not
    // mix with its base, so `Color(m)` on a `Meters` is still the written `Color(int(m))`.
    if !Type.repr(t.ty).isInstanceOf[Type.Integer] then
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

  /** The same cast reached with the enum in hand rather than with its name, which is what a
   * conversion written at a **type parameter** has once the instantiation says what the parameter
   * is. `T(n)` at a `Colour` is the `Colour(n)` a reader would have written; taking the scalar route
   * instead would refuse it for not being a scalar conversion, which is true and beside the point.
   */
  protected def enumFromIntAt(en: Type.Enum, written: String, args: List[Expr]): TExpr = {
    if !en.simple then
      err(s"'$written' is ${show(en)} here, which carries data — only a simple enum converts to " +
        "and from an integer")
    if args.length != 1 then err(s"'$written' takes exactly one integer argument")

    val t = analyzeExpr(args.head, Some(en.underlying))

    if !Type.repr(t.ty).isInstanceOf[Type.Integer] then
      err(s"'$written' converts an integer, but the value has type ${show(t.ty)}")

    TEnumFromInt(t, en)
  }

  /** `Color.try(n)` — the fallible constructor: `Some(Color)` for a declared discriminant, `None`
   * otherwise. The result is an ordinary `Option[Color]`, so nothing downstream is special-cased.
   */
  protected def enumTry(name: String, args: List[Expr]): TExpr = {
    val (en, t) = simpleEnumArg(name, args)
    val optTy   = instantiateEnum(Library.key("Option"), List(en))
    TEnumTry(t, en, optTy, optTy.variant("Some").get, optTy.variant("None").get)
  }

  /** `expr?` — unwraps an `Option`/`Result`, or returns the enclosing function early with the
   * failure re-wrapped in *its* return type. The two enums must agree, and a propagated error
   * must be the one the function returns.
   */
  protected def analyzeTry(t: TExpr): TExpr = {
    val en = t.ty match
      case e: Type.Enum if Library.tryVariants(e.base).isDefined => e
      case other => err(s"'?' needs an Option or Result value, not ${show(other)}")

    val (okName, failName) = Library.tryVariants(en.base).get

    retTy match
      case ret: Type.Enum if ret.base == en.base =>
        if en.base == Library.key("Result") && ret.targs(1) != en.targs(1) then
          err(s"'?' propagates a ${show(en.targs(1))} error, but this function returns ${show(ret.targs(1))}")
        TTry(t, en.variant(okName).get, en.variant(failName).get, ret, ret.variant(failName).get, en.targs.head)
      case other =>
        err(s"'?' may only be used in a function returning ${Modules.show(en.base)}, not ${show(other)}")
  }
}
