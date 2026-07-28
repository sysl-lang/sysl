package io.github.edadma.sysl

import scala.collection.mutable

/** Registering declarations, before any body is looked at.
 *
 * Everything a program declares is recorded here first — types, functions, externs, members, and
 * the `impl` blocks that add members to a type — so a body may name anything the file declares
 * whatever order it appears in, and two declarations may refer to each other. Nothing in this file
 * analyzes an expression; it fills the tables that the rest of the analyzer reads.
 *
 * Two rules run through it. **A declaration that fails a check still exists**: the body analysis
 * that follows walks the source and will look the name up whatever was wrong with it, so reporting
 * a mistake must not also erase the thing the mistake is about. And **a member of a generic type
 * cannot be hoisted eagerly**, because its signature mentions parameters that have no meaning until
 * a call fixes them — it is kept generic and instantiated per call site instead.
 */
trait Hoisting extends TypeResolution {

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
    case f: FuncDecl =>
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
        (e.params.map(p => (p.name, recover(Type.Unknown)(resolveType(p.typ, Map.empty)))),
         e.retType.map(t => recover(Type.Unknown)(resolveReturn(t, Map.empty))).getOrElse(Type.Unit))
      checkSignatureRules(e.name, e.params, e.retType, e.variadic)
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

  /** Records how far a declaration is visible, for the modifier it was written with (`13 §2`).
   *
   * A public declaration records nothing: the unmarked default is the common case, and a table that
   * held an entry for it would be a table with an entry per declaration in every program. What is
   * stored is the answer rather than the modifier — the file, and the module a `private[M]` resolved
   * to — because the question is asked at every use and the modifier alone cannot answer it.
   *
   * A `private[M]` naming no enclosing module is reported and then left public, so the mistake is
   * one diagnostic at the declaration rather than one at every use of it.
   */
  protected def recordAccess(key: String, vis: Visibility): Unit = vis match
    case Visibility.Public    => ()
    case Visibility.File      => declAccess(key) = Access(currentFile, None)
    case Visibility.Scoped(m) => recover(())(declAccess(key) = Access(currentFile, Some(enclosing(m))))

  /** Which module a `private[M]` names: the **innermost** enclosing one whose last segment is `M`,
   * counting the declaring module itself (`13 §2`).
   *
   * Reading outward from the declaration is what disambiguates a repeated segment — `private[geom]`
   * inside `geom.mesh.geom.tri` is the nearer `geom` — and taking the answer from where the
   * declaration sits is what keeps moving a subtree elsewhere from changing what its own
   * annotations mean. There is deliberately no way to name an unrelated module, so a visibility
   * scope is always a contiguous subtree containing the declaration.
   */
  private def enclosing(m: String): String = {
    val parts = if currentModule.isEmpty then Nil else currentModule.split('.').toList

    parts.lastIndexOf(m) match
      case -1 if parts.isEmpty =>
        err(s"this file is at the project root, whose module has no name, so there is no '$m' " +
          "to widen to — 'private' on its own is this file")
      case -1 =>
        err(s"'$m' is not '$currentModule' or one of its ancestors, and 'private[M]' widens to a " +
          "module the declaration is already inside")
      case i => parts.take(i + 1).mkString(".")
  }

  /** Checks that every bound a declaration writes names a trait and applies it to as many arguments
   * as it declares, whichever declaration form wrote it — a function, a struct, an enum, a trait. A
   * bound is a trait and nothing else (`10 §5`), so a name that is a struct, a scalar, or nothing at
   * all is reported here rather than silently promising something no type could ever be held to.
   *
   * It runs in a pass after every type is registered, so a bound may name a trait declared further
   * down the file. What the *arguments* are is left to `resolveBound`, which is reached wherever the
   * substitution that gives them meaning exists; the arity is answerable here and worth saying at
   * the declaration rather than at whatever first applied it.
   */
  protected def checkBoundNames(name: String, bounds: Map[String, List[BoundRef]]): Unit =
    for (tp, traits) <- bounds; tr <- traits do
      // The whole check points at the bound rather than at the declaration carrying it, because
      // that is the text that is wrong. It also means a bound naming something the file may not
      // reach is reported at one place — this and the definition-time walk both resolve the name,
      // and two identical complaints about one bound are one mistake reported twice.
      at(tr.pos) {
        traitKey(tr.name).map(traitDecls) match
          case None       => err(s"the bound on '$tp' in '$name' names '${tr.name}', which is not a trait")
          case Some(decl) =>
            checkTraitArity(tr.name, decl.tparams, decl.tdefaults, tr.args.map(_ => Type.Unknown))
      }

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

  /** The rules a declared signature must satisfy whichever declaration form it came from, checked
   * after the name is registered so a failure reports the mistake without also erasing the
   * declaration it is about.
   */
  private def checkSignatureRules(
      name: String,
      params: List[Param],
      ret: Option[TypeRef],
      variadic: Boolean,
  ): Unit = {
    // C reads a variadic call's arguments relative to the last named parameter, so there has to be
    // one; `f(...)` is not a callable declaration in any C either.
    if variadic && params.isEmpty then err(s"'$name' needs at least one named parameter before '...'")
    checkNoVaList(name, params, ret)
  }

  /** A `va_list` may be a local, which is all a function needs to walk its own tail — but not a
   * parameter or a result, because handing one to another function is C's `vprintf` shape and its
   * calling convention is not implemented (`12 §Open g`). Refused with a diagnostic rather than
   * lowered to something that would pass the wrong thing.
   */
  private def checkNoVaList(name: String, params: List[Param], ret: Option[TypeRef]): Unit = {
    def isVaList(t: TypeRef) = t match
      case NamedType(n, Nil) => scalarType(n).contains(Type.VaList)
      case _                 => false

    for p <- params if isVaList(p.typ) do
      at(p.pos)(err(s"a va_list cannot be a parameter yet — '$name' would have to pass its tail on, " +
        "which is not supported"))
    for r <- ret if isVaList(r) do
      at(r.pos)(err(s"a va_list cannot be returned — it walks '$name''s own tail, which is gone once it returns"))
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

  /** Records a type's members and lowers each to a function declaration under the mangled name
   * `Type.member`, whose signature is registered so calls resolve like ordinary ones.
   *
   * A member of a concrete type is hoisted eagerly, so an uncalled member is still type-checked at
   * its definition. A member of a *generic* type cannot be: its signature mentions the type's
   * parameters, which have no meaning until a call fixes them, so it is stored generic in
   * `genericMembers` and instantiated on demand at each call site. A method reads those arguments
   * off its receiver; an **associated function** has no receiver, so its call infers them from what
   * it is passed and from the type the context expects, exactly as a call to a generic free function
   * does. Members that introduce their own type parameters wait on later work and are rejected with
   * a clear diagnostic rather than silently mishandled.
   *
   * A generic type's members are handed to the definition-time pass of `14 §4` all the same. What
   * they may assume of the type's parameters is what the type asks of them — nothing, where it asks
   * nothing — and that is a rule a body can be held to before anything instantiates it, exactly as
   * a bounded generic function's body is.
   */
  protected def hoistMembers(tname: String, members: List[MethodDecl], out: mutable.ListBuffer[FuncDecl]): Unit = {
    val (tparams, taken, noun) = nominal(tname).get
    val bounds                 = nominalBounds(tname)

    checkBoundNames(tname, bounds)

    // A member of a concrete type may write `Self` for the type it is a member of, exactly as an
    // `impl`'s method may. A member of a *generic* one has its `Self` bound one step later, at each
    // instantiation, since `Box[T]` is not a type until `T` is one — `genericSelf` is where the
    // reference waits for that.
    val self = if tparams.nonEmpty then Map.empty else concrete(tname).fold(Map.empty[String, Type])(selfBinding)

    val lowered = hoistMemberList(
      MemberHome(
        tname,
        qn(tname),
        tname,
        None,
        NamedType(tname, tparams.map(NamedType(_, Nil))),
        tparams,
        bounds,
        taken,
        noun,
        self,
      ),
      members,
      out,
    )

    abstractMembers ++= lowered.filter(_.tparams.nonEmpty)
  }

  /** Everything hoisting a list of members needs to know about the type they belong to, whichever
   * declaration form brought them — a struct or enum body, or an `impl` block.
   *
   *   - `key` is what the members are filed under. For a block matching a **shape** it is the shape
   *     itself (`[]`), which is what a lookup falls back to when the type it is asked about has no
   *     members of its own.
   *   - `label` is what a diagnostic calls the type, which is the key everywhere the key is
   *     something a programmer would recognise — and the subject as written where it is not, since
   *     `[]` names nothing and `[]T` names what the block covers.
   *   - `symbol` is the same type mangled, which is what the lowered functions are *named*. The two
   *     differ only for a type that has no name of its own: `[]int` is a fine key and an impossible
   *     LLVM symbol, so its members are emitted under `slice.int`.
   *   - `head` is the shape these members share a namespace with, where they have one — the shape
   *     itself for a block matching one, and its own shape for a composed type written out in full.
   *     A type's members are one namespace whatever brought them, and this is what carries that rule
   *     across the two ways a composed type can come by one.
   *   - `selfRef` is the receiver's type as written, so a `self` parameter needs no reconstructing.
   *   - `tparams` are the parameters a member's signature may mention — a generic type's own, or a
   *     generic `impl`'s, **in the order the implementing type applies them**, so that instantiating
   *     a member from a receiver's type arguments substitutes them positionally. `bounds` is what
   *     was asked of them where they were declared, and it is what the members may assume.
   *   - `taken` are the names already spoken for inside the body (a struct's fields, an enum's
   *     variants), and `noun` what a diagnostic calls one of those.
   *   - `self` is what `Self` means inside these members, empty where the answer waits for an
   *     instantiation (a generic type's members) or means nothing at all.
   *   - `outer` is what the **trait's** own type parameters mean, for the members an `impl` of a
   *     generic trait brings. They are fixed by the block rather than by anything a call does, so
   *     unlike `tparams` they are answers rather than questions, and a member's signature and body
   *     read them exactly as they read `Self`.
   */
  private case class MemberHome(
      key: String,
      label: String,
      symbol: String,
      head: Option[String],
      selfRef: TypeRef,
      tparams: List[String],
      bounds: Map[String, List[BoundRef]],
      taken: Set[String],
      noun: String,
      self: Map[String, Type],
      outer: Map[String, Type] = Map.empty,
  ) {

    /** Everything a member's signature resolves against that a call does not supply: the trait's
     * arguments, and `Self` where it is already known.
     */
    def fixed: Map[String, Type] = outer ++ self

    /** Whether this block is the one matching the shape, rather than one of the types it covers.
     * A composed type written out in full is never spelled as its own shape, so the keys settle it.
     */
    def shaped: Boolean = head.contains(key)
  }

  /** The instantiation of a non-generic struct or enum, which was made before any member was
   * hoisted. Read from the table rather than resolved again, so a type whose own declaration was
   * already reported does not report it a second time on the way to its members — the members are
   * still hoisted, with `Self` simply unbound.
   */
  private def concrete(name: String): Option[Type] =
    structInsts.get(name).orElse(enumInsts.get(name))

  private def nominal(name: String): Option[(List[String], Set[String], String)] =
    structDecls
      .get(name)
      .map(s => (s.tparams, s.fields.map(_.name).toSet, "field"))
      .orElse(enumDecls.get(name).map(e => (e.tparams, e.variants.map(_.name).toSet, "variant")))

  /** Lowers a list of members to functions, shared by a type's own body and an `impl` block. Each
   * member is registered under (type, name) and synthesized to a `Type.member` function; a member
   * of a concrete type is type-checked eagerly, while a member of a generic type waits for a
   * concrete instantiation.
   *
   * The declarations come back so that a caller with something further to do with them — a generic
   * `impl`, whose members are checked at their definition — needs no second walk to find them.
   */
  private def hoistMemberList(
      home: MemberHome,
      members: List[MethodDecl],
      out: mutable.ListBuffer[FuncDecl],
  ): List[FuncDecl] = {
    val lowered = mutable.ListBuffer.empty[FuncDecl]

    for m <- members do
      currentPos = m.pos.orElse(currentPos)

      // A member's parameters and its type's are two lists that end up in one signature, so a name
      // used by both would leave the lowered function with two parameters of that name and nothing
      // to say which one a `T` in the body meant.
      for tp <- m.tparams if home.tparams.contains(tp) do
        err(s"'${home.label}' already declares a type parameter '$tp', so member '${m.name}' " +
          "cannot declare one of that name")

      checkBoundNames(s"${home.label}.${m.name}", m.bounds)

      // An associated function is reached by naming its type — `Box.of(…)` — and only a struct or
      // an enum has a name to be reached through. A block for a built-in or a composed type would
      // register one that nothing could ever call, which is worth saying at the declaration rather
      // than leaving as a member the program cannot use.
      if m.receiver.isEmpty && !m.isProperty && nominal(home.key).isEmpty then
        err(s"'${m.name}' has no receiver, and '${home.label}' is not a name a call could reach it " +
          "through — give it a 'self' parameter")

      if memberDecls.contains((home.key, m.name)) then
        err(s"type '${home.label}' already has a member named '${m.name}'")
      if home.taken.contains(m.name) then
        err(s"type '${home.label}' has both a ${home.noun} and a member named '${m.name}'")

      // A composed type's members and its shape's are one namespace, so that a name reaches one
      // member however the type came by it. Both directions are asked, since a file may write the
      // shape before the types it covers or after them.
      for h <- home.head do
        if home.shaped then
          for written <- composedMembers.get((h, m.name)) do
            err(s"'$written' already has a member named '${m.name}', and this 'impl' would give " +
              s"${everyShape(h)} one — a call on a '$written' could mean either")
        else
          if memberDecls.contains((h, m.name)) then
            err(s"${everyShape(h)} already has a member named '${m.name}', so '${home.label}' " +
              "cannot declare one of its own")
          composedMembers((h, m.name)) = home.label

      for ty <- home.self.get(selfName) do
        // A built-in's catalog methods are the compiler's (`14 §5`), and member lookup would find a
        // member of the same name first — so an `impl` of some *other* trait may not quietly take
        // `5.add` over from the `Add` the type is already a member of.
        for tr <- CoreTraits.declaring(m.name) if CoreTraits.builtin(tr, ty) do
          err(s"'${m.name}' is how '$tr' is implemented for ${show(ty)}, and the compiler provides " +
            s"that — a member of this name would hide it")

        // `len` and `bytes` are the same situation one step further out: a member of a built-in that
        // the compiler supplies, reached ahead of the member table rather than through it.
        if builtinMember(ty, m.name) then
          err(s"'${m.name}' is a member the compiler provides for ${show(ty)} — a member of this " +
            "name would hide it")

      memberDecls((home.key, m.name)) = m
      val fd = synthesize(home, m)

      // A member is filed under the type it belongs to, which an `impl` in another module may have
      // named — and an inherited default's body is the trait's source, wherever the trait is. Both
      // resolve their names where they were written, so that is what is recorded here.
      declScope(fd.name) = defaultHome(fd.name).getOrElse(currentScope)

      lowered += fd

      // A signature mentioning a type parameter — the type's own, or the member's — has no meaning
      // until something fixes it, so the member is kept as written and made real per call.
      if fd.tparams.nonEmpty then
        genericMembers((home.key, m.name)) = fd
        // On a generic type `Self` is the type applied to its own parameters, which is not a type
        // yet. The reference is what waits, and every substitution that fixes the parameters fixes
        // it too; on a concrete type it is already the answer and resolves to the same thing.
        genericSelf(fd.name) = (home.selfRef, currentScope)
        if home.outer.nonEmpty then genericOuter(fd.name) = home.outer
      else
        out += fd
        if home.fixed.nonEmpty then memberSelf(fd.name) = home.fixed
        funcInsts(fd.name) =
          (fd.params.map(p => (p.name, resolveType(p.typ, home.fixed))),
           fd.retType.map(resolveReturn(_, home.fixed)).getOrElse(Type.Unit))

    lowered.toList
  }

  /** Checks an `impl` conforms to its trait and lowers its methods to inherent members of the
   * implementing type. Conformance is nominal and exact: the type must supply every trait method
   * with a matching signature and no method the trait does not declare. The methods are then
   * hoisted exactly as a type's own members are, so a call on a value resolves through the ordinary
   * member path with no dispatch machinery of its own.
   */
  protected def hoistImpl(block: ImplDecl, out: mutable.ListBuffer[FuncDecl]): Unit = {
    // The trait is named in the module the block was written in, and everything filed below is
    // filed under the key that names — so the block carries the resolved name from here on and
    // nothing downstream has to resolve it a second time.
    val tr   = traitKey(block.traitName).map(traitDecls).getOrElse(err(s"unknown trait '${block.traitName}'"))
    val impl = block.copy(traitName = tr.name).setPos(block.pos)

    val (ty, target) = implTarget(impl)
    val bound        = implBound(impl, tr)
    val home         = target.copy(outer = tr.tparams.zip(bound.args).toMap)

    // A built-in's memberships come from the compiler (`14 §5`), so an `impl` for one is not adding
    // a capability but competing with the one that is already there — and the operator would keep
    // lowering to its native instruction whatever this block said.
    if bound.args.isEmpty && CoreTraits.builtin(impl.traitName, ty) then
      err(s"'${home.label}' already implements '${qn(impl.traitName)}' — the compiler provides it")

    // Keyed by the type rather than by the spelling, so `impl Show for int` and `impl Show for i32`
    // are the one implementation they are, and so are `[]int` and `[]i32`. A generic type has one
    // key for all of its instantiations, which is what makes an implementation cover the type as a
    // whole and two of them for one generic type a collision rather than a choice.
    //
    // The trait's arguments are deliberately **not** part of the key. A trait's members become the
    // type's, and a type's members are one namespace, so `impl From[int] for C` and
    // `impl From[real] for C` would give a `C` two members called `from` with nothing to say which
    // one `c.from(x)` meant.
    for supplied <- implFor.get((impl.traitName, home.key)) do
      err(s"'${home.label}' already implements '${qn(supplied)}'" + secondImplementation(tr))

    // A shape and a type of that shape written out in full would both implement the trait for that
    // type, and sysl has no rule that picks between two implementations — the more specific one does
    // not win, because nothing here is more specific than anything else. So the second one written is
    // refused, in whichever order the file put them.
    for h <- home.head do
      if home.shaped then
        for one <- writtenShapes.get((impl.traitName, h)) do
          err(s"'$one' already implements '${qn(impl.traitName)}', and this 'impl' would implement " +
            s"it for ${everyShape(h)} — including that one")
      else
        if traitImpls.contains((impl.traitName, h)) then
          err(s"${everyShape(h)} already implements '${qn(impl.traitName)}', so '${home.label}' has an " +
            "implementation and cannot be given a second one")
        writtenShapes((impl.traitName, h)) = home.label

    traitImpls((impl.traitName, home.key)) = impl
    implFor((impl.traitName, home.key)) = bound.key

    // What the trait **requires** is asked of the implementing type here, at the block that makes
    // the promise, rather than at each bound that relies on it. Two reasons, and the second decides
    // it: the diagnostic belongs on the declaration that cannot keep its word, and a `&Sub` object's
    // table needs a slot for every required trait's method — so if the requirement were only checked
    // where the trait is *used*, a table could be asked for that has nothing to point at. The
    // question is held until every `impl` is registered, since the one that supplies a required
    // trait may be written below the one that needs it.
    // A **generic** block covers a type it never instantiates, so `ty` is unknown for it and the
    // subject of the question is the type applied to the block's own parameters — each standing in
    // for itself and carrying what the block asked of it. That is what makes
    // `impl[T: Named] Greet for Box[T]` answerable against `impl[T: Named] Named for Box[T]`: the
    // condition on one side is met by the condition on the other.
    val subject =
      if impl.tparams.isEmpty then ty
      else sandboxed(resolveType(impl.forType, abstractSubst(impl.tparams, impl.bounds)))

    for s <- tr.supers do
      superChecks += ((home.label, qn(tr.name),
        resolveBound(s, tr.tparams.zip(bound.args).toMap ++ selfBinding(subject)), subject, impl.pos))

    if home.tparams.nonEmpty && home.bounds.nonEmpty then
      implBounds((impl.traitName, home.key)) = (home.tparams, home.bounds)

    // A default the block left out is hoisted for this type exactly as a written method is, from the
    // body the trait supplied — so everything downstream (a call, a vtable slot, the escape summary)
    // finds an ordinary `Type.method` and needs to know nothing about where it came from. What is
    // recorded is where each copy came *from*, which is only ever read to keep one bad default from
    // being reported once per implementing type.
    //
    // Conformance is checked with the block's parameters standing in for themselves, which resolves
    // a signature mentioning one — and `Self`, which for a generic `impl` is the type applied to
    // them. Those instantiations are diagnostic only, so the walk is sandboxed the way `14 §4`'s is.
    val inherited = sandboxed(checkConformance(tr, impl, home, signatures(home)))

    for m <- inherited do defaultOrigin(s"${home.symbol}.${m.name}") = s"${impl.traitName}.${m.name}"

    val lowered = hoistMemberList(home, impl.methods ::: inherited, out)

    // A generic block's members are checkable before anything instantiates them, against the bounds
    // the block wrote on its own parameters — the same walk a generic type's members take. An
    // inherited default is left out: it was checked at the trait, against what the trait promises,
    // which is the whole of what its body may assume wherever it is copied to.
    abstractMembers ++= lowered.filter(_.tparams.nonEmpty).filterNot(f => defaultOrigin.contains(f.name))
  }

  /** Where a copied default's body was written, for a member that is one.
   *
   * `defaultOrigin` records the trait method a copy came from, as the trait's key and the method's
   * name — so the trait itself is everything left of the last dot, a member name having none and a
   * key's module separator not being one.
   */
  private def defaultHome(member: String): Option[Scope] =
    defaultOrigin.get(member).map(origin => scopeFor(origin.take(origin.lastIndexOf('.'))))

  /** Why a *generic* trait is no more implementable twice than any other, said only where the
   * arguments might have made a reader think otherwise.
   */
  private def secondImplementation(tr: TraitDecl): String =
    if tr.tparams.isEmpty then ""
    else
      s" — a trait's members become the type's, so a second '${qn(tr.name)}' would give '${tr.methods.head.name}' " +
        "two meanings and a call no way to say which"

  /** Which promise an `impl` block supplies: the trait, at the arguments this block writes for it.
   *
   * The arguments must be types the block fixes outright, not its own parameters. An
   * `impl[T] From[T] for Wrapper` would be an implementation for every `From` at once, and deciding
   * which of several such blocks a `From[int]` reaches is the matching problem sysl does not have —
   * one implementation per (trait-at-arguments, type) is what every table here is keyed by, and a
   * parameter in the key is a key that matches many things.
   */
  private def implBound(impl: ImplDecl, tr: TraitDecl): Type.Bound = {
    checkTraitArity(qn(impl.traitName), tr.tparams, tr.tdefaults, impl.traitArgs.map(_ => Type.Unknown))

    // The block's parameters resolve to themselves so that naming one here is caught as the thing it
    // is, rather than reported as an unknown type — which would send the reader looking for a
    // declaration rather than at the argument they meant to fix.
    val declared = abstractSubst(impl.tparams, impl.bounds)
    val written  = impl.traitArgs.map(resolveType(_, declared))

    // What the block leaves out the trait supplies, and `Self` in one of those defaults is the type
    // this block implements the trait for — which is what makes `impl Mul for Point` the
    // `impl Mul[Point] for Point` it reads as.
    val args =
      if written.length == tr.tparams.length then written
      else
        withDefaults(impl.traitName, tr.tparams, tr.tdefaults, written,
          selfBinding(sandboxed(resolveType(impl.forType, declared))))

    // Asked of what the block **wrote**, not of what the defaults filled in. A conditional block's
    // subject is the type applied to its own parameters, so a default of `Self` names them by
    // construction — `impl[T: Named] Pairable for Box[T]` supplies `Pairable[Box[T]]`, which is one
    // implementation and not a family of them, and only an argument the author left open is.
    for a <- written; abs <- mentionedParam(a) do
      err(s"'${abs.name}' is a type parameter of this 'impl', so it leaves which '${qn(impl.traitName)}' " +
        "this block implements open — write the type the trait is applied to")

    for tp <- impl.tparams if tr.tparams.contains(tp) do
      err(s"trait '${qn(impl.traitName)}' already declares a type parameter '$tp', so this 'impl' " +
        "cannot declare one of that name")

    deferredBounds(impl.traitName, tr.tparams, tr.bounds, args)

    Type.Bound(impl.traitName, args)
  }

  /** The first type parameter a type is built out of, if any — what tells a written type from one
   * that still depends on something a call would fix.
   */
  private def mentionedParam(t: Type): Option[Type.Abstract] = t match
    case a: Type.Abstract    => Some(a)
    case n: Type.Named       => n.targs.flatMap(mentionedParam).headOption
    case Type.Ptr(inner)     => mentionedParam(inner)
    case Type.Ref(inner, _)  => mentionedParam(inner)
    case Type.Array(_, elem) => mentionedParam(elem)
    case Type.Slice(elem)    => mentionedParam(elem)
    case _                   => None

  /** What a signature written inside these members resolves under.
   *
   * A concrete `impl` binds only `Self`, to the one type it is for. A generic one binds its own
   * parameters to themselves — the opaque stand-in of `14 §4` — and `Self` to the type applied to
   * them, so `-> Self` and `-> Box[T]` are the one signature conformance compares, exactly as
   * `-> Self` and `-> Point` are on a concrete implementation.
   *
   * The trait's own parameters are bound either way, since the block fixed them: a method written in
   * the trait's `T` and one written in the type that `T` is are the same signature.
   */
  private def signatures(home: MemberHome): Map[String, Type] =
    home.outer ++ {
      if home.tparams.isEmpty then home.self
      else
        val abstracts = abstractSubst(home.tparams, home.bounds)

        abstracts + (selfName -> resolveType(home.selfRef, abstracts))
    }

  /** The type an `impl` is for, and where its members belong.
   *
   * A trait may be implemented for **any** type it makes sense to name: built-ins included — a
   * language whose `Show` cannot cover `int` has a `Show` no library can use — the composed types
   * too, so `impl Display for []int` says how a slice of ints renders, a **generic** struct or enum
   * as a whole, which is what an `impl` with type parameters of its own is for, and a composed
   * **shape**, which is that same block written for a type that has no name to be generic over. A
   * struct or an enum brings its own fields or variants for a member to collide with; nothing else
   * has any.
   *
   * Two shapes are refused outright, each because an `impl` for it would be about nothing. A
   * **memory mode** is a way of holding a type rather than a type, and a member call already sees
   * through one. A **trait object** has forgotten which type it holds, which is the one thing an
   * `impl` is written about.
   */
  private def implTarget(impl: ImplDecl): (Type, MemberHome) = {
    val ref = impl.forType

    ref match
      // A name declaring a struct or an enum is the one shape that may be generic, so it is settled
      // here rather than by resolving a reference whose arguments are the block's own parameters.
      // Its key is the name it was declared under, which stands even where the declaration itself
      // failed to resolve and there is no instantiation to read one off.
      case NamedType(written, argRefs) if typeKey(written).exists(k => nominal(k).isDefined) =>
        val key                    = typeKey(written).get
        val (tparams, taken, noun) = nominal(key).get

        if tparams.isEmpty then
          if impl.tparams.nonEmpty then
            err(s"'$written' takes no type arguments, so an 'impl' for it has nothing to be generic over")
          if argRefs.nonEmpty then err(s"type '$written' does not take type arguments")

          val ty = concrete(key).getOrElse(Type.Unknown)

          (ty, MemberHome(key, qn(key), key, None, ref, Nil, Map.empty, taken, noun, selfBinding(ty)))
        else
          val order = implArgs(impl, written, tparams)

          // A generic type has no one instantiation to be, and nothing that reaches this needs one:
          // an implementation covers every `Box` at once, and each member is made real per receiver.
          (Type.Unknown,
           MemberHome(key, qn(key), key, None, ref, order, impl.bounds, taken, noun, Map.empty))

      case NamedType(n, Nil) if n == selfName =>
        err("'Self' is the type an 'impl' is for, so it cannot also be the type it names")
      case NamedType(n, Nil) if n == neverName =>
        err("'never' has no values, so nothing can be implemented for it")
      // Both valueless types are named here rather than left to `resolveType`, which refuses them
      // for standing anywhere but a result — true, and beside the point when what the block asks
      // is whether the type can behave.
      case NamedType(n, Nil) if n == unitName =>
        err("'unit' has one value and no behaviour — a trait for it would say nothing")

      case _ =>
        // The block's parameters stand in for themselves while the subject is resolved, so a shape
        // written with one comes back as the shape it is — `[]T` as a slice of something — rather
        // than through a complaint about a name that means nothing outside this block.
        val abstracts = abstractSubst(impl.tparams, impl.bounds)

        // Resolved rather than taken as written, so the key is the type's one canonical name and
        // two spellings of one type are one implementation.
        val ty = resolveType(ref, abstracts)

        ty match
          case t if Type.erased(t) =>
            err(s"'${show(t)}' has forgotten which type it holds, and an 'impl' says how one " +
              "particular type behaves — so it is written for that type, not for an object over it")
          case Type.Ptr(inner)       => err(modeIsNotAType("*", inner))
          case Type.Ref(inner, sync) => err(modeIsNotAType(if sync then "&sync " else "&", inner))
          case Type.VaList => err("a va_list is an ABI primitive, not something to implement a trait for")
          // The subject is the block's own parameter, which stands for any type at all — so the block
          // would be saying how every type behaves, which is what a trait's own defaults are for.
          case a: Type.Abstract =>
            err(s"'${a.name}' is a type parameter of this 'impl', so it stands for every type at " +
              "once — an 'impl' says how one kind of type behaves")
          case _ =>

        val head = shapeOwner(ty).map(_._1)

        if impl.tparams.isEmpty then
          (ty,
           MemberHome(ownerKey(ty), show(ty), Type.mangle(ty), head, ref, Nil, Map.empty, Set.empty, "field",
             selfBinding(ty)))
        else
          val shape = head.getOrElse(notGeneric(ref))
          val order = shapeArgs(impl, ty, shape)

          // Like a generic type's, the members are made real per receiver — so there is no one
          // instantiation to be, and the shape rather than any type of it is what they are filed
          // under. The symbol drops the arguments the same way: `slice.show` instantiated at `int`
          // is `slice.show.int`, which the written `[]int`'s `slice.int.show` cannot collide with.
          (Type.Unknown,
           MemberHome(shape, ref.show, shapeSymbol(ty), head, ref, order, impl.bounds, Set.empty, "field",
             Map.empty))
  }

  /** What a diagnostic calls every type of a shape at once. */
  private def everyShape(head: String): String =
    if head == "[]" then "every slice" else s"every array of ${head.drop(1).dropRight(1)}"

  /** The symbol a shape's members are emitted under, which is the mangling of the types it covers
   * with the arguments left off — so an instantiation appends them and arrives back where the type
   * written out in full would have started.
   */
  private def shapeSymbol(t: Type): String = t match
    case _: Type.Slice => "slice"
    case Type.Array(n, _) => s"arr$n"
    case other            => Type.mangle(other)

  /** Why a block declaring type parameters has nothing to apply them to: the subject is a type that
   * takes no arguments and has no shape either, so there is nothing for the parameters to fix.
   *
   * A composed type does have a shape, and matching it is what `shapeArgs` checks instead. What is
   * left here is a name — and by the time one reaches this it has already resolved, so a type that
   * does not exist or a trait was reported as itself rather than through a complaint about the
   * parameters, which would send the reader looking at the one part of the line written correctly.
   */
  private def notGeneric(ref: TypeRef): Nothing = ref match
    case NamedType(n, _) =>
      err(s"'$n' takes no type arguments, so an 'impl' for it has nothing to be generic over")
    case _ =>
      err(s"'${ref.show}' has no shape for an 'impl' to match — a slice and an array do, and a " +
        "generic struct or enum has a name of its own to be generic over")

  /** The block's type parameters **in the order the implementing type applies them**, which is what
   * lets a member be instantiated from a receiver's own type arguments by position.
   *
   * An `impl` covers a generic type as a whole, so its subject must be that type applied to the
   * block's parameters and nothing else: each argument one parameter, each parameter used once, all
   * of them spoken for. Anything narrower — `Box[int]`, `Pair[T, int]` — is an implementation for
   * *some* instantiations, which is a second implementation for a key that holds one.
   */
  private def implArgs(impl: ImplDecl, written: String, tparams: List[String]): List[String] = {
    val declared = impl.tparams.toSet
    val args     = impl.forType match
      case NamedType(_, as) => as
      case _                => Nil
    val spelled = s"impl[${tparams.mkString(", ")}] ${impl.traitName} for $written[${tparams.mkString(", ")}]"

    if impl.tparams.isEmpty then
      err(s"'$written' is generic, so an 'impl' for it covers every instantiation at once — " +
        s"write '$spelled'")
    if args.length != tparams.length then
      err(s"'$written' takes ${quantity(tparams.length, "type argument")}, but " +
        s"${supplied(args.length, "type argument")}")

    val names = args.map {
      case NamedType(n, Nil) if declared(n) => n
      case other =>
        err(s"'${other.show}' fixes an argument of '$written' to one type, and an 'impl' covers " +
          s"the whole of a generic type — write one of the block's own parameters here")
    }

    if names.distinct.length != names.length then
      err(s"each argument of '$written' takes a type parameter of its own, and this 'impl' " +
        s"names '${names.diff(names.distinct).head}' twice")
    if declared != names.toSet then
      err(s"'${(declared -- names).mkString("', '")}' is declared by this 'impl' but does not " +
        s"appear in '${impl.forType.show}', so nothing would ever fix it")

    names
  }

  /** The same, for a **shape**: the block's parameters in the order the shape applies them, which
   * for a slice or an array is the one element type.
   *
   * The rule is `implArgs`' rule, and it is the same rule because the reason is the same. A shape is
   * one key, so a block that fixed part of what it matched would be implementing the trait for
   * *some* of the types the shape covers — a second implementation for a key that holds one.
   */
  private def shapeArgs(impl: ImplDecl, ty: Type, shape: String): List[String] = {
    val declared = impl.tparams.toSet
    val names    = shapeOwner(ty).get._2.map {
      case Type.Abstract(n, _) if declared(n) => n
      case other =>
        err(s"'${show(other)}' fixes the element type, and an 'impl' with type parameters covers " +
          s"${everyShape(shape)} — write one of the block's own parameters here")
    }

    if declared != names.toSet then
      err(s"'${(declared -- names).mkString("', '")}' is declared by this 'impl' but does not " +
        s"appear in '${impl.forType.show}', so nothing would ever fix it")

    names
  }

  private def modeIsNotAType(sigil: String, inner: Type): String =
    s"'$sigil${show(inner)}' is a way of holding a ${show(inner)} rather than a type of its own — " +
      s"write the 'impl' for ${show(inner)}, which a member call reaches through one '${sigil.trim}' to find"

  /** Verifies that `impl` supplies the members `tr` declares, each with an identical resolved
   * signature, and yields the **defaults it inherits** — the members it did not write and does not
   * have to, because the trait supplied a body for them.
   *
   * A member the trait declares without a default and the block leaves out, a member the trait does
   * not declare at all, or a mismatched kind, receiver, parameter, or result is reported against the
   * trait it fails to satisfy.
   */
  private def checkConformance(
      tr: TraitDecl,
      impl: ImplDecl,
      home: MemberHome,
      sig: Map[String, Type],
  ): List[MethodDecl] = {
    val declared  = tr.methods.map(_.name).toSet
    val inherited = mutable.ListBuffer.empty[MethodDecl]

    for tm <- tr.methods do
      impl.methods.find(_.name == tm.name) match
        case Some(im) => checkSignature(home.label, qn(impl.traitName), tm, im, sig, scopeFor(tr.name))
        case None if tm.body.nonEmpty => inherited += tm
        case None =>
          err(s"'${home.label}' does not implement '${qn(impl.traitName)}': ${kind(tm)} '${tm.name}' is missing")

    for im <- impl.methods do
      if !declared.contains(im.name) then
        err(s"trait '${qn(impl.traitName)}' declares no ${kind(im)} '${im.name}', so this 'impl' cannot define it")

    inherited.toList
  }

  /** What a diagnostic calls one member of a trait. The three kinds are told apart by shape rather
   * than by a keyword, so a message that names the wrong one sends the reader looking for the wrong
   * mistake.
   */
  private def kind(m: MethodDecl): String =
    if m.isProperty then "property" else if m.receiver.isEmpty then "associated function" else "method"

  /** Compares one implementing method against the trait's signature: same receiver mode, same
   * parameter types in order, and the same result.
   *
   * Both sides are resolved with `Self` bound to the implementing type, which is what makes a
   * signature written with `Self` and one written with the concrete name the same signature
   * (`14 §1`) — the comparison is between *resolved* types, so it does not matter which spelling
   * either side chose.
   */
  private def checkSignature(
      forType: String,
      traitName: String,
      tm: MethodDecl,
      im: MethodDecl,
      self: Map[String, Type],
      traitScope: Scope,
  ): Unit = {
    // Which *kind* of member it is comes first, because two of the three kinds have no receiver
    // between them: a property and an associated function both answer `None`, so the receiver
    // comparison below would let one stand for the other without ever noticing.
    if tm.isProperty != im.isProperty then
      if tm.isProperty then
        err(s"'${im.name}' is a property of trait '$traitName', so an implementation writes it as " +
          s"'${im.name} -> …' with no parameter list")
      else
        err(s"'${im.name}' is a method of trait '$traitName', so an implementation writes it with a " +
          "parameter list — a property has none")
    if tm.receiver != im.receiver then
      err(s"method '${im.name}' of 'impl $traitName for $forType' takes a different receiver than the trait declares")
    // A member of an `impl` is the trait's member supplied, so its shape is the trait's — including
    // how many types of its own it is generic over. The trait declares none today, which makes this
    // the diagnostic for writing a generic method in an `impl`.
    if tm.tparams.length != im.tparams.length then
      err(s"${kind(im)} '${im.name}' of 'impl $traitName for $forType' declares " +
        s"${quantity(im.tparams.length, "type parameter")}, but trait '$traitName' declares " +
        s"${tm.tparams.length}")
    if tm.params.length != im.params.length then
      err(s"method '${im.name}' of 'impl $traitName for $forType' takes ${im.params.length} " +
        s"parameters, but the trait declares ${tm.params.length}")

    // The two signatures were written in two files — the trait's and the block's — so each side is
    // resolved where it was written, under that file's module and its imports. A `Point` in the
    // trait's `-> Point` is the trait module's `Point`, whether or not the implementing module has
    // one of its own.
    for (tp, ip) <- tm.params.zip(im.params) do
      val want = inScope(traitScope)(resolveType(tp.typ, self))
      val got  = resolveType(ip.typ, self)
      if want != got then
        err(s"parameter '${ip.name}' of method '${im.name}' is ${show(got)}, but trait '$traitName' declares ${show(want)}")

    val want = inScope(traitScope)(tm.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit))
    val got  = im.retType.map(resolveReturn(_, self)).getOrElse(Type.Unit)
    if want != got then
      err(s"method '${im.name}' returns ${show(got)}, but trait '$traitName' declares ${show(want)}")
  }

  /** Builds the function a member lowers to: the receiver becomes an ordinary first parameter
   * named `self`, carrying the memory mode its sigil asked for. A property's receiver is an
   * implicit by-value read; an associated function has no receiver. On a generic type the self
   * type is the type applied to its own parameters (`Box[T]`, not `Box`), and the lowered function
   * inherits those parameters, so instantiating it at a concrete `Box[int]` substitutes `T` in the
   * receiver, the result, and the body alike.
   *
   * A member's **own** parameters follow the type's, in that order and never interleaved, because
   * the two are fixed from different places and the order is what lets them be: a call reads the
   * type's off the receiver and appends the ones it solved, so the same positional substitution
   * serves a member that adds parameters and one that adds none.
   */
  private def synthesize(home: MemberHome, m: MethodDecl): FuncDecl = {
    val name = s"${home.symbol}.${m.name}"

    FuncDecl(
      name,
      home.tparams ::: m.tparams,
      receiverParam(m, selfRefFor(name, home)).toList ::: m.params,
      m.retType,
      m.body,
      home.bounds ++ m.bounds,
    ).setPos(m.pos)
  }

  /** The receiver's type as a member's lowered signature carries it.
   *
   * A member written here names it as the block did. An **inherited default** is read in the trait's
   * terms (`defaultHome`), because that is where its body and the rest of its signature came from —
   * and the subject is the one thing in it that was not written there, so a copy naming it as the
   * block spelled it would have that spelling looked for in the trait's module. The copy therefore
   * names it `Self`, which is what the trait itself calls the implementing type, and the binding for
   * `Self` is made where the subject really was written.
   */
  private def selfRefFor(name: String, home: MemberHome): TypeRef =
    if defaultOrigin.contains(name) then NamedType(selfName) else home.selfRef

  /** The `self` parameter a member's receiver becomes, at the self type it is a member of. A
   * property's receiver is an implicit by-value read; an associated function has none.
   */
  private def receiverParam(m: MethodDecl, selfRef: TypeRef): Option[Param] = m.recvMode.map {
    case RecvMode.ByValue     => Param("self", selfRef)
    case RecvMode.ByPtr       => Param("self", PtrType(selfRef))
    case RecvMode.ByRef(sync) => Param("self", RefType(selfRef, sync))
  }

  /** Every trait default, as the generic function each one is: one type parameter, `Self`, bounded
   * by the trait that declared it.
   *
   * That is what a default body *means* — it may assume of its receiver exactly what the trait
   * promises, and nothing else — so writing it down this way is what lets the definition-time pass
   * of `14 §4` check it once, at the trait, with the machinery a bounded generic already uses. The
   * declarations exist only for that walk; the body a program runs is the copy `hoistImpl` makes for
   * each implementing type.
   *
   * A **property** with a body is a default like any other, and needs nothing said about it here: its
   * declaration form already carries a body, so the only question was whether the trait was allowed
   * to write one, and the receiver it never spelled becomes a `self` parameter the same way.
   */
  protected def traitDefaults: List[FuncDecl] =
    for
      tr <- traitDecls.values.toList
      m  <- tr.methods if m.body.nonEmpty
    yield FuncDecl(
      s"${tr.name}.${m.name}",
      selfName :: tr.tparams,
      receiverParam(m, NamedType(selfName, Nil)).toList ::: m.params,
      m.retType,
      m.body,
      // A generic trait's default is generic over the trait's parameters too — they are as unknown
      // inside the body as `Self` is — and what `Self` promises is the trait *applied* to them,
      // which is the one promise every implementation of it makes.
      bounds = tr.bounds +
        (selfName -> List(BoundRef(tr.name, tr.tparams.map(NamedType(_, Nil))))),
    ).setPos(m.pos)
}
