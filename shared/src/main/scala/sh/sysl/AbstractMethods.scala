package sh.sysl

/** Calling a member on a receiver whose type is not yet, or never will be, a concrete one.
 *
 * Split out of `MethodCalls`, which chooses between the routes; these are the two that cannot look a
 * member up under an owner key, because there is no owner to key it under.
 *
 * **A bounded type parameter** is resolved against the traits its bounds name, during the
 * definition-time pass of `14 §4`: the call is checked against the *trait's* signature, which is
 * what lets one walk stand in for every instantiation. **A trait object** is resolved against the
 * table it carries, at run time, so what is checked here is that the trait declares the member and
 * that the arguments fit what it declared.
 *
 * They are together because they fail the same way and it is worth failing identically: a member
 * nothing licenses is reported by naming the bound that would have licensed it, and the three
 * `unlicensed*` refusals below are that message for a method, an associated function and a property.
 */
trait AbstractMethods extends FuncAddress {

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
  protected def callBoundMethod(a: Type.Abstract, recv: TExpr, mname: String, args: List[Expr]): TExpr =
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
          // Checked against the trait's signature, so it is the trait's defaults and the trait's
          // parameter names that a call inside a generic body may reach for — the same ones every
          // instantiation will present, which is what makes one walk stand in for all of them.
          val bound = bindArgs(s"method '$fname'", Some(tr.name), m.params, args, m.variadic)

          checkArity(s"method '$fname'", m.params.length, m.variadic, bound.length)
          val (declared, tail) = bound.splitAt(m.params.length)

          // **A member with type parameters of its own is solved HERE, at the abstract call, and not
          // left to the instantiation.** They do not depend on the receiver — `apply[U]`'s `U` comes
          // from the argument and the context exactly as a generic free function's would — so this
          // walk can settle them, and settling them is what lets the signature be resolved at all.
          //
          // What the receiver's own type says is already in `self`: the trait's parameters, and
          // `Self` standing for the parameter this was reached through. It goes in as the seed for
          // the provisional pass and as `solve`'s `known`, so a parameter naming any of it is read
          // against the answer rather than waited on. `Self` is named among the ones being held for
          // the same reason the trait's parameters are — it has no resolution outside that map.
          val (ownSolved, provisional) =
            if m.tparams.isEmpty then (Map.empty[String, Type], None)
            else
              val ptypes = m.params.map(_.typ)
              val held   = (selfName :: traitDecls.get(tr.name).fold(List.empty[String])(_.tparams)) ::: m.tparams
              val prov   = provisionalArgs(fname, held, ptypes, declared, m.bounds, self)

              (m.tparams
                 .zip(solve(fname, m.tparams, ptypes, prov.map(_.ty), m.retType, None, Nil, m.bounds, self))
                 .toMap,
               Some(prov))

          val subst  = self ++ ownSolved
          val params = m.params.map(p => (p.name, resolveType(p.typ, subst)))

          if m.tparams.nonEmpty then checkParamBounds(fname, m.tparams, m.bounds, m.tparams.map(ownSolved), self)

          // **The provisional reading is kept where there was one**, exactly as a generic free
          // function's is: it was made against the parameter's *bound*, and re-reading an argument
          // against the parameter's solved type asks a different question. For a closure standing at
          // a bare-arrow parameter that question has no answer — the solved type is the closure's own
          // struct, which says nothing about what the closure takes, so the second reading reported
          // that its parameters had no types while the first had read them perfectly well.
          val ts = provisional.getOrElse(
            declared.zip(params).map { case (arg, (_, pty)) => analyzeExpr(arg, Some(pty)) })
          val rtype = m.retType.map(resolveReturn(_, subst)).getOrElse(Type.Unit)
          TCall(fname, recv :: (checkArgs(fname, params, declared, Some(ts)) ::: tail.map(variadicArg(_))), rtype)
        }

  /** `T.f(…)` where `T` is a type parameter and `f` is an associated function one of its bounds
   * declares — what `callBoundMethod` is to a value, asked of the type itself.
   *
   * This is the whole of what a bound can say **about the type** rather than about a value of it.
   * A parameter is not a name anything else can be written through, so without it every fact a
   * generic body needs — a width, a zero, a table of constants — has to arrive as a member carrying
   * a receiver nothing reads, which is the workaround `02` describes and this replaces.
   *
   * Checked against the trait's signature and discarded, exactly as a bound method call is: the name
   * it carries is the trait's, and which implementation runs is settled once a concrete type is
   * known. Static dispatch only — a trait declaring one has no object (`checkObjectSafe`), because
   * there is no receiver for a table slot to be selected by.
   */
  protected def callBoundAssociated(a: Type.Abstract, mname: String, args: List[Expr]): TExpr =
    boundMember(a, mname) match
      case None => unlicensedAssociated(a, mname)
      case Some((tr, self, m)) =>
        reported {
          val fname = s"${tr.name}.$mname"
          if m.isProperty then
            err(s"'$mname' is a property of '${tr.show}' — read it on a value, as 'value.$mname'")
          if m.receiver.isDefined then
            err(s"'$mname' is a method of '${tr.show}' — call it on a value of '${a.name}', not on " +
              "the type itself")
          val params = m.params.map(p => (p.name, resolveType(p.typ, self)))
          val bound  = bindArgs(s"associated function '$fname'", Some(tr.name), m.params, args, m.variadic)

          checkArity(s"associated function '$fname'", params.length, m.variadic, bound.length)
          val (declared, tail) = bound.splitAt(params.length)
          val ts    = declared.zip(params).map { case (arg, (_, pty)) => analyzeExpr(arg, Some(pty)) }
          val rtype = m.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
          TCall(fname, checkArgs(fname, params, declared, Some(ts)) ::: tail.map(variadicArg(_)), rtype)
        }

  /** The trait a bound reaches an associated function of that name through, where one does — asked
   * by a *read* that has to decide between telling the reader to add the parentheses and reporting
   * whatever else the name turned out to be.
   */
  protected def boundAssociated(a: Type.Abstract, mname: String): Option[String] =
    boundMember(a, mname).filter(_._3.recvMode.isEmpty).map(_._1.show)

  /** The diagnostic for `T.f(…)` that no bound licenses, which is the associated-function half of
   * `unlicensed`: with a trait declaring one of that name the fix is the bound, and naming it is
   * what checking at the definition is for.
   */
  private def unlicensedAssociated(a: Type.Abstract, mname: String): Nothing =
    traitDecls.toList
      .filter((k, t) => visible(k) && t.methods.exists(m => m.name == mname && m.recvMode.isEmpty))
      .map((_, t) => qn(t.name)) match
      case Nil =>
        boundErr(s"'${a.name}' is a type parameter, and no trait declares an associated function " +
          s"'$mname' that a bound could promise")
      case one :: Nil => boundErr(s"'$mname' needs '${show(a)}: $one'")
      case many =>
        boundErr(s"'$mname' needs a bound on '${a.name}' — it is declared by ${many.mkString("'", "', '", "'")}")

  /** The first member of that name one of a parameter's bounds declares, with the substitution its
   * signature is read under.
   *
   * That substitution is the whole of what a bound's *arguments* buy. `Self` is the parameter
   * itself, which is what makes `Add::add` yield a `T` and `Ord::lt` a `bool` inside a body that has
   * not met a concrete type yet (`14 §4`); the trait's own parameters are the arguments the bound
   * applied it to, so a `T: From[int]` has a `from` that takes an `int` and one bounded by
   * `From[U]` has one that takes whatever `U` turns out to be.
   */
  protected def boundMember(a: Type.Abstract, mname: String): Option[(Type.Bound, Map[String, Type], MethodDecl)] =
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
    traitDecls.toList.filter((k, t) => visible(k) && t.methods.exists(m => m.name == name && m.isProperty))
      .map((_, t) => qn(t.name)) match
      case Nil =>
        boundErr(s"'${a.name}' is a type parameter, so it has no fields to read — a field is layout, " +
          s"and no trait declares a property '$name' that a bound could promise instead")
      case one :: Nil =>
        boundErr(s"'$name' needs '${show(a)}: $one'")
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
    // one slot in one table — so this is the table's list, in the order it was laid out.
    val members = slottedMembers(Type.Bound(t.name, t.args))

    members.zipWithIndex.find(_._1._2.name == mname) match
      // **A member the trait really has, that the table could not hold**, is worth saying so about
      // rather than reporting as a name the trait does not declare — the reader wrote something
      // that exists and reached it the one way that cannot work.
      case None if traitMembers(Type.Bound(t.name, t.args)).exists(_._2.name == mname) =>
        err(s"'$mname' of '${t.name}' declares type parameters of its own, so it is not a function " +
          s"until a call names them and no slot of a ${show(recv.ty)}'s table can point at it — " +
          s"reach it through a bound, where the type is known")
      case None =>
        err(s"trait '${t.name}' declares no method '$mname' — it has " +
          members.map(_._2.name).mkString("'", "', '", "'"))
      case Some(((_, m), _)) if m.isProperty =>
        err(s"'$mname' is a property of '${t.name}' — read it as 'value.$mname', without '()'")
      case Some(((from, m), slot)) =>
        // A signature is read under the parameters of the trait that *declared* it, at the arguments
        // the object's type fixed for it — an object over `Sink[int]` takes an `int`.
        //
        // **`Self` stands for the object type**, which is the one thing it may stand for here: a
        // signature that named it anywhere but a projection was refused by object safety, and a
        // projection is what this is for — `Self::Item` on a `*Iterate[Item = string]` is the
        // `string` the object wrote down, read back by `assocType`.
        val subst: Map[String, Type] = traitDecls(from.name).tparams.zip(from.args).toMap + (selfName -> t)
        val params                   = m.params.map(p => (p.name, resolveType(p.typ, subst)))
        // A boxed callable's `call` is the same non-member the inlined one's is, and reads the same
        // way in a message — the arity-carrying trait behind it is the compiler's business.
        val callable = Type.Fn.isCall(from.name)
        val fname    = if callable then "this callable" else s"method '${from.name}.$mname'"

        // The **trait's** declaration is what a call through an object names, so the trait's
        // defaults are what fill it — which is why they are filled here, before the slot is
        // reached, and mean the same thing as they do at a call to a known type (`12 §2a`). A
        // boxed callable is the one member here with no names to give: `Fn`'s parameters are the
        // compiler's, so `bindArgs` is not asked and a name written at one is refused as it is at
        // an inlined callable.
        val bound =
          if callable then args else bindArgs(fname, Some(from.name), m.params, args)

        if bound.length != params.length then
          err(s"$fname takes ${quantity(params.length, "argument")}, " +
            s"but ${supplied(bound.length, "argument")}")

        val ts    = bound.zip(params).map { case (a, (_, pty)) => analyzeExpr(a, Some(pty)) }
        val rtype = m.retType.map(resolveReturn(_, subst)).getOrElse(Type.Unit)

        TVCall(recv, slot, checkArgs(fname, params, bound, Some(ts), callable), rtype)
  }

  /** `obj.p` on a `*Trait` or a `&Trait` — a property read through the table, which is the same
   * indirect call a method is with no arguments to pass and no parentheses to write.
   *
   * An object has no fields at all: the layout is exactly what erasure forgot. So every name read off
   * one is either a property the trait declares or a mistake, and the second half of this says which.
   */
  protected def readTraitObjectProperty(recv: TExpr, t: Type.Trait, name: String): TExpr = {
    slottedMembers(Type.Bound(t.name, t.args)).zipWithIndex.find(_._1._2.name == name) match
      case Some(((from, m), slot)) if m.isProperty =>
        // `Self` stands for the object, exactly as it does at a method call one function up: a
        // property whose type is a projection reads it back off what the object type fixed.
        val subst: Map[String, Type] = traitDecls(from.name).tparams.zip(from.args).toMap + (selfName -> t)

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
    traitDecls.toList.filter((k, t) => visible(k) && t.methods.exists(_.name == mname))
      .map((_, t) => qn(t.name)) match
      case Nil =>
        boundErr(s"no trait declares a method '$mname', so no bound on '${a.name}' could license this call")
      case one :: Nil =>
        boundErr(s"'$mname' needs '${show(a)}: $one'")
      case many =>
        boundErr(s"'$mname' needs a bound on '${a.name}' — it is declared by ${many.mkString("'", "', '", "'")}")
}
