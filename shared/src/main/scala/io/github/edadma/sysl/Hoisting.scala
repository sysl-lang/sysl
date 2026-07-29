package io.github.edadma.sysl

import scala.collection.mutable

/** Registering declarations, before any body is looked at.
 *
 * Everything a program declares is recorded here first — types, functions, externs — so a body may
 * name anything the file declares whatever order it appears in, and two declarations may refer to
 * each other. Nothing in this file analyzes an expression; it fills the tables that the rest of the
 * analyzer reads. A type's **members**, and the `impl` blocks that give a type someone else's, are
 * the other half of the same pass and live in `HoistMembers`.
 *
 * Two rules run through it. **A declaration that fails a check still exists**: the body analysis
 * that follows walks the source and will look the name up whatever was wrong with it, so reporting
 * a mistake must not also erase the thing the mistake is about. And **a member of a generic type
 * cannot be hoisted eagerly**, because its signature mentions parameters that have no meaning until
 * a call fixes them — it is kept generic and instantiated per call site instead.
 */
trait Hoisting extends HoistMembers {

  /** What already holds the value name `key`, named as a diagnostic would say it, or `None` where
   * nothing does.
   *
   * A constant, a `val`, and an enum variant are all *values* a bare name can reach, so the three
   * share one namespace — and every registration asks this rather than the one table it is about,
   * which is what makes the clash reported at whichever of them was written second.
   */
  protected def valueNameHolder(key: String): Option[String] =
    if constDecls.contains(key) then Some("a constant")
    else if valDecls.contains(key) then Some("a 'val'")
    else if variantOwner.contains(key) then Some(s"enum '${qn(variantOwner(key))}'")
    else None

  /** Registers one type-shaped declaration: a struct, an enum, a trait, or an `impl`.
   *
   * A declaration belongs to the module its file contributes to, so what every table holds it
   * under is the **qualified** name (`Modules.qualify`) and the declaration is renamed to it. That
   * is what makes two modules able to declare a `Point` at all, and it is done once here rather
   * than at each lookup: everything downstream reads a name that already says which module it is.
   * Diagnostics keep naming the declaration as it was *written*, since that is the line the reader
   * is looking at.
   */
  protected def hoistType(stmt: Stmt): Unit = stmt match
    case s: StructDecl =>
      val key = Modules.qualify(currentModule, s.name)

      if typeNameTaken(key, s.name) then err(s"type '${s.name}' is already declared")
      checkNoModuleOfThatName(key, s.name, "member")
      structDecls(key) = s.copy(name = key).setPos(s.pos)
      for m <- s.members do checkSolvedDefaults("the method", s"${s.name}.${m.name}", m.tdefaults)
      declScope(key) = currentScope
      recordAccess(key, s.vis)
      // A field's and a member's reach is settled here beside the type's rather than where the
      // members are lowered, because both are compared against the type's own — and against the
      // types they name, which is a pass that runs before any member is lowered.
      for f <- s.fields do at(f.pos)(recordMemberAccess(key, f.name, f.vis, s"${s.name}.${f.name}"))
      for m <- s.members do at(m.pos)(recordMemberAccess(key, m.name, m.vis, s"${s.name}.${m.name}"))
      if Prelude.declares(s) then preludeNames += key
    case e: EnumDecl =>
      val key = Modules.qualify(currentModule, e.name)

      if typeNameTaken(key, e.name) then err(s"type '${e.name}' is already declared")
      checkNoModuleOfThatName(key, e.name, "variant")
      // A generic enum has no single storage type to pin, and it is never instantiated
      // eagerly, so the annotation is rejected here at the declaration rather than on use.
      if e.underlying.isDefined && e.tparams.nonEmpty then
        err(s"a generic enum cannot pin an underlying type — '${e.name}' takes type parameters")
      enumDecls(key) = e.copy(name = key).setPos(e.pos)
      for m <- e.members do checkSolvedDefaults("the method", s"${e.name}.${m.name}", m.tdefaults)
      declScope(key) = currentScope
      recordAccess(key, e.vis)
      for m <- e.members do at(m.pos)(recordMemberAccess(key, m.name, m.vis, s"${e.name}.${m.name}"))
      if Prelude.declares(e) then preludeNames += key
      // Variant names are unique **within a module** rather than across the program: a bare
      // `Circle(5)` resolves against the module it is written in, so two modules may each name a
      // variant `Circle` without either use site becoming ambiguous.
      for v <- e.variants do
        val vkey = Modules.qualify(currentModule, v.name)

        for what <- valueNameHolder(vkey) do err(s"variant name '${v.name}' is already used by $what")
        variantOwner(vkey) = key
        // A variant is reached unqualified, so it is a name of the module in its own right — and it
        // carries its enum's visibility, since an enum nobody outside may name is not one whose
        // variants they may construct.
        recordAccess(vkey, e.vis)
        if Prelude.declares(e) then preludeNames += vkey
    case t: TraitDecl =>
      val key = Modules.qualify(currentModule, t.name)

      if typeNameTaken(key, t.name) then err(s"the name '${t.name}' is already declared")
      traitDecls(key) = t.copy(name = key).setPos(t.pos)
      declScope(key) = currentScope
      recordAccess(key, t.vis)
      // A trait's members take no modifier of their own, so each is recorded at the trait's reach —
      // which is what makes "how far does this member go" one question with one answer, whether it
      // was asked about a trait's member, a type's, or a type's field.
      for m <- t.methods do recordMemberAccess(key, m.name, Visibility.Public, s"${t.name}.${m.name}")
      if Prelude.declares(t) then preludeNames += key
      checkBoundNames(t.name, t.bounds)
      for m <- t.methods do
        // A property carries its receiver without writing one, so the reading below — no receiver
        // and a body, which for a method means a default with nothing to work on — is not what a
        // property with a body is. That one is a default property, and it is allowed.
        if m.receiver.isEmpty && !m.isProperty && m.body.nonEmpty then
          at(m.pos)(err(s"'${t.name}.${m.name}' has no receiver, so a default body has no value to " +
            "work on — give it a 'self' parameter or drop the body"))
        // No implementation could supply one either, so the trait is where it is worth saying so.
        // A defaulted parameter is caught by this too, since carrying a default means having one.
        if m.tparams.nonEmpty then
          at(m.pos)(err(s"generic methods are not supported yet — '${t.name}.${m.name}'"))
    // A constrained subtype shares the type namespace, so a name clash is caught here; the base and
    // bounds are resolved and validated lazily, the first time the name is used as a type.
    case t: TypeDecl =>
      val key = Modules.qualify(currentModule, t.name)

      if typeNameTaken(key, t.name) then err(s"type '${t.name}' is already declared")
      checkNoModuleOfThatName(key, t.name, "member")
      constrainedDecls(key) = t.copy(name = key).setPos(t.pos)
      declScope(key) = currentScope
      recordAccess(key, t.vis)
    // A constant is registered with the types rather than with the functions, because an array
    // bound and an enum discriminant may both name one and both are resolved between the two passes
    // (`13 §7`). Its *value* is not evaluated here — a constant may be written in terms of one
    // declared below it, so folding waits until something asks.
    case c: ConstDecl =>
      val key = Modules.qualify(currentModule, c.name)

      // A constant, a function, and an enum variant are all *values* a bare name can reach, so the
      // three share one namespace and each pass checks the tables filled before it.
      if constDecls.contains(key) then err(s"constant '${c.name}' is already declared")
      else for what <- valueNameHolder(key) do err(s"'${c.name}' is already used by $what")
      constDecls(key) = c.copy(name = key).setPos(c.pos)
      declScope(key) = currentScope
      recordAccess(key, c.vis)

    // A `val` is registered beside the constants and for the same reason: it is a value a bare name
    // reaches, and an array bound may not name one — but an enum discriminant and a later `val`'s
    // initializer are resolved in the same window, so the table has to be full before either is
    // read. Its type and its value wait, exactly as a constant's do: one `val` may be written in
    // terms of a `const` declared below it.
    case v: ValDecl =>
      val key = Modules.qualify(currentModule, v.name)

      if valDecls.contains(key) then err(s"'${v.name}' is already declared")
      else for what <- valueNameHolder(key) do err(s"'${v.name}' is already used by $what")
      // `13 §2` — what is visible outside its file states its types, and a module member always
      // could be. A local `val` states nothing to anyone and infers like a `var`.
      if v.typ.isEmpty then
        err(s"a module-level 'val' states its type, so '${v.name}' needs one — 'val ${v.name}: T = …'")
      valDecls(key) = v.copy(name = key).setPos(v.pos)
      declScope(key) = currentScope
      recordAccess(key, v.vis)

    // The same rule, meeting a form that cannot satisfy it: a binding that names several things has
    // nowhere to write a type for any of them (`12 §5b`), so it can only ever be a local. Saying so
    // here is what stops one at the top of a file from quietly becoming a local of the entry point,
    // where every other `val` written there is a module member.
    case m: MultiDecl if !m.mutable =>
      at(m.pos)(err("a module-level 'val' states its type, and a binding that names several things " +
        s"has nowhere to write one — declare ${m.names.map(n => s"'$n'").mkString(" and ")} separately"))

    // The type an `impl` names may be declared further down the file, so it cannot be resolved here
    // — the duplicate check goes with the resolution, in `hoistImpl`. The module it was written in
    // travels with it, since that is what the trait it names and the type it is for resolve under.
    case i: ImplDecl =>
      implDecls += ((currentScope, i))
      checkSolvedDefaults("the 'impl' block", s"${i.traitName} for ${i.forType.show}", i.tdefaults)
      for m <- i.methods do
        checkSolvedDefaults("the method", s"${i.traitName}.${m.name}", m.tdefaults)
    case _ =>

  /** Registers one function's name and signature.
   *
   * A parameter or result whose type does not resolve is recorded and taken as unknown rather
   * than sinking the whole declaration, so the function still exists with the right arity and a
   * call to it is not reported a second time as an undefined name.
   *
   * An `extern` is registered the same way, under a synthesized declaration with an empty body:
   * everything downstream of the name — the arity check, argument checking, the emitted call — is
   * then the ordinary path, and what makes it an extern is that codegen finds it in `externDecls`
   * and declares it instead of looking for a body to define.
   *
   * **Registration comes before every check but the duplicate-name one**, which is the same rule as
   * the paragraph above generalized: a declaration that fails a check still exists, because the body
   * analysis that follows walks the source and will look this signature up whatever was wrong with
   * it. Reporting the mistake must not also remove the thing it is about.
   */
  protected def hoistFunc(stmt: Stmt): Unit = stmt match
    case original: FuncDecl =>
      val f = callBounds(original.tparams, original.params).fold(original) { (tps, ps, bs) =>
        original.copy(tparams = tps, params = ps, bounds = original.bounds ++ bs).setPos(original.pos)
      }
      val key = Modules.qualify(currentModule, f.name)

      if funcDecls.contains(key) then err(s"function '${f.name}' is already declared")
      else if constDecls.contains(key) then err(s"'${f.name}' is already declared as a constant")
      else if valDecls.contains(key) then err(s"'${f.name}' is already declared as a 'val'")
      funcDecls(key) = f.copy(name = key).setPos(f.pos)
      declScope(key) = currentScope
      recordAccess(key, f.vis)
      if Prelude.declares(f) then preludeNames += key
      if f.tparams.isEmpty then
        funcInsts(key) =
          (f.params.map(p => (p.name, recover(Type.Unknown)(resolveType(p.typ, Map.empty)))),
           f.retType.map(t => recover(Type.Unknown)(resolveReturn(t, Map.empty))).getOrElse(Type.Unit))
      checkSignatureRules(f.name, f.params, f.retType, f.variadic)
      checkBoundNames(f.name, f.bounds)
      checkSolvedDefaults("the function", f.name, f.tdefaults)

    // An `extern`'s **symbol** is not qualified, and cannot be: it names something the linker
    // already has, which knows nothing about sysl's modules. So the key the program calls it by
    // carries the module like any other name, and the symbol is pinned to what was written.
    case e: ExternDecl =>
      val key = Modules.qualify(currentModule, e.name)

      if funcDecls.contains(key) then err(s"function '${e.name}' is already declared")
      funcDecls(key) = FuncDecl(key, Nil, e.params, e.retType, Nil, variadic = e.variadic).setPos(e.pos)
      externDecls(key) = e.copy(name = key, link = Some(e.symbol)).setPos(e.pos)
      declScope(key) = currentScope
      recordAccess(key, e.vis)
      if Prelude.declares(e) then preludeNames += key
      funcInsts(key) =
        (e.params.map(p => (p.name, foreignParam(recover(Type.Unknown)(resolveType(p.typ, Map.empty))))),
         e.retType.map(t => recover(Type.Unknown)(resolveReturn(t, Map.empty))).getOrElse(Type.Unit))
      checkSignatureRules(e.name, e.params, e.retType, e.variadic, foreign = true)
      for s <- e.link if !s.matches("[A-Za-z0-9_$.]+") do
        err(s"'$s' is not a symbol a linker can resolve")

    // A `where` predicate becomes an ordinary function `<Type>$pred(value: Base) -> bool`, so it is
    // analyzed and emitted through the same path every function takes; the check site calls it by
    // the key `predKey` gives it. It is registered here, in the pass that fills the function tables,
    // rather than at the check, so the predicate is type-checked whether or not the type is used.
    case t: TypeDecl if t.pred.isDefined =>
      val key  = Modules.qualify(currentModule, t.name)
      val pkey = predKey(key)

      funcDecls(pkey) = FuncDecl(pkey, Nil, List(Param("value", t.base)), Some(NamedType("bool")),
        List(ExprStmt(t.pred.get)), Map.empty, variadic = false).setPos(t.pos)
      declScope(pkey) = currentScope
      funcInsts(pkey) =
        (List(("value", recover(Type.Unknown)(resolveType(t.base, Map.empty)))), Type.Bool)

    // A struct's `invariant` clauses become one function `<Struct>$inv(field₁: T₁, …) -> bool`,
    // whose body is the clauses `and`-ed together. The fields are its *parameters* rather than one
    // `self`, so the bare field names a clause writes resolve with no rewrite of the expression, and
    // the whole thing type-checks — and reports a non-bool clause — through the ordinary body pass.
    // The check site (struct construction, a field write) calls it with the struct's field values.
    case s: StructDecl if s.invariants.nonEmpty =>
      val key  = Modules.qualify(currentModule, s.name)
      val ikey = invKey(key)

      if s.tparams.nonEmpty then
        at(s.pos)(err(s"invariants on generic structs are not supported yet — '${s.name}'"))
      else
        val cond = s.invariants.reduce((a, b) => Binary("&&", a, b))
        funcDecls(ikey) = FuncDecl(ikey, Nil, s.fields, Some(NamedType("bool")),
          List(ExprStmt(cond)), Map.empty, variadic = false).setPos(s.pos)
        declScope(ikey) = currentScope
        funcInsts(ikey) =
          (s.fields.map(p => (p.name, recover(Type.Unknown)(resolveType(p.typ, Map.empty)))), Type.Bool)

    case _ =>

  /** Checks what every trait **requires** of the types that implement it, in a pass of its own once
   * every trait is registered — because `trait Ord: Eq` is ordinary whichever of the two is written
   * first, and asking during the walk that registers them would report the one below.
   *
   * Four things are answered here, all of them about the declarations rather than about any use:
   * that each required trait is a trait, applied to as many arguments as it declares; that the
   * requirements do not come back around to the trait that made them; that one trait is not required
   * at two different argument lists; and that no two traits in the closure declare a member of one
   * name. The last two are one rule seen twice — a trait's members become the implementing type's,
   * and a type's members are one namespace — so two traits that both declare `len` cannot be
   * required together, and neither can `Into[int]` and `Into[bool]`, whose `into`s would be two
   * members of one name for one type.
   *
   * The walk resolves each requirement rather than comparing spellings, because whether two are the
   * same promise is a question about types: `Into[int]` and `Into[i32]` are one requirement written
   * two ways. Each trait's own parameters stand in for themselves, which is what lets a requirement
   * naming one resolve at the declaration at all.
   */
  protected def checkTraitSupers(): Unit =
    for (key, decl) <- traitDecls do
      currentPos = decl.pos
      inScope(declScope(key))(recover(()) {
        val declares = mutable.LinkedHashMap.empty[String, String]
        val visited  = mutable.LinkedHashMap(key -> Type.Bound(key, Nil))

        for m <- decl.methods do declares(m.name) = qn(key)

        // The path is carried so a cycle can be reported as the chain that closes it rather than as
        // the one name the walk happened to arrive at twice. A trait reached a second time by two
        // different routes — the diamond — is taken once, which is what keeps its members from
        // colliding with themselves.
        def walk(name: String, path: List[String]): Unit =
          for tr <- traitDecls.get(name); s <- tr.supers do
            at(s.pos) {
              traitKey(s.name) match
                case None => err(s"trait '${qn(name)}' requires '${s.name}', which is not a trait")
                case Some(skey) =>
                  val sdecl = traitDecls(skey)

                  checkTraitArity(s.name, sdecl.tparams, sdecl.tdefaults, s.args.map(_ => Type.Unknown))

                  // `Self` is the type implementing the requiring trait, which nothing here is yet,
                  // so it stands in for itself: enough for the arguments to resolve and for two
                  // requirements of one trait to be compared, and the same stand-in whether it was
                  // written out or arrived from the required trait's own default.
                  val bound = resolveBound(s, abstractSubst(tr.tparams, tr.bounds) ++ selfBinding(abstractSelf))

                  if path.contains(skey) then
                    err(s"trait '${qn(skey)}' requires itself, through " +
                      (path.reverse.dropWhile(_ != skey) ::: List(skey)).map(qn).mkString(" -> "))
                  else
                    visited.get(skey) match
                      // One trait at two argument lists is the coherence rule refusing in advance:
                      // a type implements a trait once, so nothing could ever satisfy both.
                      case Some(first) if first.key != bound.key =>
                        err(s"'${qn(key)}' requires both '${first.show}' and '${bound.show}', and a " +
                          "type implements one trait once — so no type could satisfy both")
                      case Some(_) =>
                      case None =>
                        visited(skey) = bound
                        for m <- sdecl.methods do
                          for other <- declares.get(m.name) do
                            err(s"'${qn(skey)}' and '$other' both declare '${m.name}', and a trait's " +
                              s"members become the implementing type's — so '${qn(key)}' cannot require both")
                          declares(m.name) = qn(skey)
                        walk(skey, skey :: path)
            }

        walk(key, List(key))
      })

  /** Refuses a default on a type parameter that is **solved** rather than written.
   *
   * A function's parameters are fixed by what the call passes, a method's by that and its receiver,
   * an `impl` block's by the type it is for — and sysl offers no call-site type arguments at all
   * (`10 §2`), so in none of the three is there an argument list a default could fill a gap in. The
   * thing that would be useful there is a fallback for an inference that found nothing, which is a
   * different feature and not this one; saying so at the declaration is what keeps the two apart.
   */
  protected def checkSolvedDefaults(noun: String, label: String, tdefaults: Map[String, TypeRef]): Unit =
    for (tp, ref) <- tdefaults.toList.sortBy(_._1) do
      at(ref.pos)(err(s"'$tp' is a type parameter of $noun '$label', whose type parameters are solved " +
        s"from what it is given rather than written where it is used — so '= ${ref.show}' has nothing " +
        "to stand in for"))

  /** Checks the defaults of the three declarations whose arguments *are* written — a trait, a struct
   * and an enum — in a pass of its own once every type is registered, since a default may name one
   * declared below it.
   *
   * What a default has to satisfy is checked at each **use** instead, by the same deferral every
   * other type argument goes through: `withDefaults` fills the list before `deferredBounds` reads
   * it, so a default that fails the parameter's own bound is caught wherever the declaration is
   * applied, and by the machinery that already knows how to wait for the `impl` blocks.
   */
  protected def checkTypeDefaults(): Unit = {
    def check(
        key: String,
        pos: Option[Pos],
        noun: String,
        tparams: List[String],
        tdefaults: Map[String, TypeRef],
        self: Map[String, Type],
    ): Unit =
      if tdefaults.nonEmpty then
        currentPos = pos
        inScope(declScope(key))(recover(())(sandboxed {
          // Arguments are written left to right, so a parameter with no default sitting behind one
          // that has could never be reached: leaving the earlier one out would leave nothing for the
          // later one to be written after. The shape is refused here rather than at every use.
          val first = tparams.indexWhere(tdefaults.contains)

          for tp <- tparams.drop(first + 1) if !tdefaults.contains(tp) do
            err(s"'$tp' has no default and comes after '${tparams(first)}', which has one — a use " +
              s"writes its arguments in order, so nothing could leave out '${tparams(first)}' and " +
              s"still supply '$tp'")

          // Resolved in the order a use fills them, each under the ones already fixed. A parameter
          // with no default stands in for itself, so a default naming one to its left is ordinary
          // and one naming a parameter to its right is caught as the forward reference it is.
          val known = mutable.LinkedHashMap.from(self)

          for (tp, i) <- tparams.zipWithIndex do
            tdefaults.get(tp) match
              case None => known(tp) = Type.Abstract(tp, Nil)
              case Some(ref) =>
                at(ref.pos) {
                  if mentions(ref, Set(tp)) then
                    err(s"the default for '$tp' names '$tp' — a parameter cannot stand in for itself")

                  for later <- tparams.drop(i + 1) if mentions(ref, Set(later)) do
                    err(s"the default for '$tp' names '$later', which is fixed after it — a default " +
                      "may name only the parameters written before it")

                  if self.isEmpty && mentionsSelf(ref) then
                    err(s"'Self' is the type implementing a trait, and $noun '${qn(key)}' is not a " +
                      s"trait — so the default for '$tp' has nothing to name")

                  known(tp) = resolveType(ref, known.toMap)
                }
        }))

    for (key, d) <- traitDecls do check(key, d.pos, "trait", d.tparams, d.tdefaults, selfBinding(abstractSelf))
    for (key, d) <- structDecls do check(key, d.pos, "struct", d.tparams, d.tdefaults, Map.empty)
    for (key, d) <- enumDecls do check(key, d.pos, "enum", d.tparams, d.tdefaults, Map.empty)
  }

  /** Holds every `impl` of a trait that requires others to supplying those too, once every `impl`
   * is registered and the question can be answered.
   *
   * A **built-in** membership counts, because that is what a required trait means to a type the
   * compiler already made a member of — `impl Word for u32` is asking `u32` for the arithmetic it
   * has always had. What that costs is recorded where it is paid: a table cannot name an
   * instruction, so `vtableFor` refuses to erase such a type.
   */
  protected def checkImplSupers(): Unit = {
    for (label, required, sup, ty, pos) <- superChecks.toList do
      currentPos = pos
      recover(()) {
        if !satisfies(sup, ty) then
          // A generic block's subject carries its own parameters, so the advice names the type the
          // block covers rather than a bare name that would send the reader to write a second
          // implementation for one instantiation — which is refused anyway (`02`).
          val write =
            if show(ty) == label then s"write 'impl ${sup.show} for $label'"
            else s"write an implementation of '${sup.show}' for ${show(ty)}"

          err(s"'$required' requires '${sup.show}', so '$label' has to implement that too — $write")
      }
    superChecks.clear()
  }


  /** Structs, enums, and the built-in scalars share one type namespace, so a name may name at
   * most one of them. `never` is in it too: it names a type, so nothing else may.
   *
   * The declared names are asked about by the **key** the module gives them, and the built-in
   * spellings by the name as **written** — a scalar is not a member of any module, so `Point` in
   * `geom` and `Point` at the root are two types while `int` is one everywhere.
   */
  /** Refuses a struct or an enum whose module and name spell a module the program also has.
   *
   * `geom.Point.dist` would then name both `geom`'s `Point.dist` and `geom.Point`'s `dist`, and a
   * dotted reference takes the **longest** prefix that names a module (`13 §3`) — so the module
   * would win and the type's member would have no spelling left at all. The keys stay distinct
   * either way (`Modules`); what collides is the path a program writes, which is exactly the thing
   * a diagnostic can fix and a silent choice cannot.
   *
   * Only a struct and an enum are asked, because they are what a dotted chain reaches *into*. A
   * trait is named in a bound or behind a sigil and never has a member selected off its name, so a
   * module sharing its name takes nothing away from it.
   */
  private def checkNoModuleOfThatName(key: String, written: String, reachable: String): Unit =
    if moduleNames(Modules.show(key)) then
      err(s"'${qn(key)}' is also a module, so '${qn(key)}.<$reachable>' would name two things — " +
        s"rename the module or '$written'")

  private def typeNameTaken(key: String, written: String): Boolean =
    structDecls.contains(key) || enumDecls.contains(key) || traitDecls.contains(key) ||
      constrainedDecls.contains(key) || scalarType(written).isDefined ||
      written == neverName || written == selfName
}
