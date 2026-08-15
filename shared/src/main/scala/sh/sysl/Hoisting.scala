package sh.sysl

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
    storageNameHolder(key)
      .orElse(variantOwners.get(key).map(owners => s"enum '${qn(owners.head)}'"))

  /** The same question with the variants left out — what a *variant* registration asks, because a
   * second enum naming a variant the first one also names is legal and everything else is not.
   *
   * The asymmetry is the whole of `09 §3`'s namespacing rule in one line. Two variants of that name
   * are told apart by the enum they belong to, and a use site that cannot tell them apart says so at
   * the use site; a variant and a constant of one name have nothing to be told apart *by*, so the
   * clash is real and is reported where it is written.
   */
  private def storageNameHolder(key: String): Option[String] =
    if constDecls.contains(key) then Some("a constant")
    else if valDecls.contains(key) then Some("a 'val'")
    else if staticVarDecls.contains(key) then Some("a module 'var'")
    else if externVarDecls.contains(key) then Some("an 'extern' variable")
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
      if libraryOffers(s, currentModule) then libraryNames(s.name) = key
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
      if libraryOffers(e, currentModule) then libraryNames(e.name) = key
      // **A variant belongs to its enum, not to the module** (`09 §3`), so two enums here may each
      // name a variant `Failed` and the module-level name simply has two answers. What a bare use of
      // it means is settled at the use site, by the type expected there; the qualified
      // `Enum.Variant` spelling is what a site with nothing to go on writes instead.
      //
      // The key is still the module's, because that is what a bare name resolves through — the list
      // under it is what makes two answers representable at all.
      for v <- e.variants do
        val vkey = Modules.qualify(currentModule, v.name)

        for what <- storageNameHolder(vkey) do err(s"variant name '${v.name}' is already used by $what")
        // A variant is reached unqualified, so it is a name of the module in its own right — and it
        // carries its enum's visibility, since an enum nobody outside may name is not one whose
        // variants they may construct. Where the name already has an owner, the reach recorded is
        // the **widest** of theirs: a public enum's variant does not stop being reachable because a
        // private enum beside it happens to have named one the same, and the bare name outside the
        // module could only ever have meant the public one.
        if variantOwners.contains(vkey) then
          if e.vis == Visibility.Public then declAccess.remove(vkey)
          // Two restricted owners keep the first's reach. Comparing two `private[M]` scopes is a
          // question the language has no ordering for, and erring narrow costs a diagnostic where
          // erring wide would cost a leak.
        else recordAccess(vkey, e.vis)
        variantOwners(vkey) = variantOwners.getOrElse(vkey, Nil) :+ key
        if libraryOffers(e, currentModule) then libraryNames(v.name) = vkey
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
      if libraryOffers(t, currentModule) then libraryNames(t.name) = key
      for m <- t.methods do
        // A property carries its receiver without writing one, so the reading below — no receiver
        // and a body, which for a method means a default with nothing to work on — is not what a
        // property with a body is. That one is a default property, and it is allowed.
        if m.receiver.isEmpty && !m.isProperty && m.body.nonEmpty then
          at(m.pos)(err(s"'${t.name}.${m.name}' has no receiver, so a default body has no value to " +
            "work on — give it a 'self' parameter or drop the body"))
        // No implementation could supply one either, so the trait is where it is worth saying so.
        // A defaulted parameter is caught by this too, since carrying a default means having one.
        //
        // The refusal is a decision and not a gap (`02 § Details still to settle`), so the message
        // says what it is about the trait rather than what the compiler has yet to do: a member with
        // type parameters of its own could never sit in a vtable slot, because the function does not
        // exist until a call names its types — and a trait that cannot be a trait object is a
        // narrower thing than the trait somebody wrote. An inherent member may declare them (`08`),
        // which is what the message points at.
        if m.tparams.nonEmpty then
          at(m.pos)(err(s"'${t.name}.${m.name}' declares type parameters of its own, which a trait's " +
            "member may not — no table slot can hold a function that does not exist until a call " +
            "names its types; an inherent member may declare them"))
    // A constrained subtype shares the type namespace, so a name clash is caught here; the base and
    // bounds are resolved and validated lazily, the first time the name is used as a type.
    case t: TypeDecl =>
      val key = Modules.qualify(currentModule, t.name)

      if typeNameTaken(key, t.name) then err(s"type '${t.name}' is already declared")
      checkNoModuleOfThatName(key, t.name, "member")
      constrainedDecls(key) = t.copy(name = key).setPos(t.pos)
      declScope(key) = currentScope
      recordAccess(key, t.vis)
    // An assert declares no name, so there is nothing to register and nothing for it to collide
    // with — it is only collected, and settled in the same window a constant's value is. That
    // window is exactly what it needs: the condition may name a constant declared below it, just as
    // one constant may be written in terms of another.
    case a: AssertDecl => assertDecls += ((a, currentScope))

    // A constant is registered with the types rather than with the functions, because an array
    // bound and an enum discriminant may both name one and both are resolved between the two passes
    // (`13 §7`). Its *value* is not evaluated here — a constant may be written in terms of one
    // declared below it, so folding waits until something asks.
    case c: ConstDecl =>
      val key = Modules.qualify(currentModule, c.name)

      // A constant, a function, and an enum variant are all *values* a bare name can reach, so the
      // three share one namespace and each pass checks the tables filled before it.
      if constDecls.contains(key) then duplicate(key, s"constant '${c.name}' is already declared")
      else for what <- valueNameHolder(key) do duplicate(key, s"'${c.name}' is already used by $what")
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

      if valDecls.contains(key) then duplicate(key, s"'${v.name}' is already declared")
      else for what <- valueNameHolder(key) do duplicate(key, s"'${v.name}' is already used by $what")
      // `13 §2` — what is visible outside its file states its types, and a module member always
      // could be. A local `val` states nothing to anyone and infers like a `var`.
      if v.typ.isEmpty then
        err(s"a module-level 'val' states its type, so '${v.name}' needs one — 'val ${v.name}: T = …'")
      valDecls(key) = v.copy(name = key).setPos(v.pos)
      declScope(key) = currentScope
      recordAccess(key, v.vis)

    // A module `var` is registered beside the `val`s because it is the same kind of thing: storage
    // the module owns, under a name that reaches it. It arrives here by two spellings and they mean
    // one declaration (`13 §7`): `static var` at the top of the file the program starts in, which
    // `ProgramWalk` has already unwrapped, and a plain `var` at the top of any other file, where
    // there is no body for it to be a local of and `static` would say nothing.
    //
    // Only a *local* `var` never reaches this, and that is the entry file's own — which is why the
    // diagnostics below say "module storage" rather than naming either spelling. A reader told to
    // write `static` in a file that refuses the word, or told to drop it in the one file that needs
    // it, would be told something false.
    case v: VarDecl =>
      val key = Modules.qualify(currentModule, v.name)

      if staticVarDecls.contains(key) then duplicate(key, s"'${v.name}' is already declared")
      else for what <- valueNameHolder(key) do duplicate(key, s"'${v.name}' is already used by $what")
      // The same rule a `val` meets, and for the same reason (`13 §2`): a module member may be
      // visible outside its file, and what is has to say what it is. It bites harder here, because
      // module storage written with `var` may have no initializer at all for a type to be inferred
      // from.
      if v.typ.isEmpty then
        err(s"'${v.name}' is module storage, and module storage states its type (`13 §2`) — write " +
          s"'${v.name}: T'")
      staticVarDecls(key) = v.copy(name = key).setPos(v.pos)
      declScope(key) = currentScope
      recordAccess(key, v.vis)

    // An `extern` variable is registered here rather than with the functions, because what it
    // declares is a *value* a bare name reaches — the same namespace a `const` and a `val` are in,
    // and the reason a clash with either is reported at whichever was written second. Its **symbol**
    // is not qualified for the reason the `extern` function's is not: the linker knows nothing about
    // sysl's modules, so the key carries the module and the symbol is what was written.
    case e: ExternVarDecl =>
      val key = Modules.qualify(currentModule, e.name)

      if externVarDecls.contains(key) then duplicate(key, s"'${e.name}' is already declared")
      else for what <- valueNameHolder(key) do duplicate(key, s"'${e.name}' is already used by $what")
      externVarDecls(key) = e.copy(name = key, link = Some(e.symbol)).setPos(e.pos)
      declScope(key) = currentScope
      recordAccess(key, e.vis)
      if libraryOffers(e, currentModule) then libraryNames(e.name) = key
      for s <- e.link if !s.matches("[A-Za-z0-9_$.]+") do
        err(s"'$s' is not a symbol a linker can resolve")
      // The same rule the `extern` function is held to, and the same reason: this program defines
      // `main`, so a declaration claiming the linker supplies it is a duplicate symbol at the link
      // rather than anything the line it is written on could explain.
      if e.symbol == "main" then
        err("'main' is where the platform starts this program, so an 'extern' may not name that symbol")
      // The `llvm.` namespace holds the back end's operations (`Intrinsics`), which are code and not
      // storage. Left alone this would emit `@llvm.something = external global`, which is a module
      // the verifier rejects for a reason no reader of this line would connect to it.
      if Intrinsics.declared(e.symbol) then
        err(s"'${e.symbol}' is in the namespace the back end's own operations live in, and those are " +
          "code rather than storage — an 'extern' variable names a symbol the linker resolves")

    // The same rule, meeting a form that cannot satisfy it: a binding that names several things has
    // nowhere to write a type for any of them (`12 §5b`), so it can only ever be a local. Saying so
    // here is what stops one at the top of a file from quietly becoming a local of the entry point,
    // where every other `val` written there is a module member.
    case m: MultiDecl if !m.mutable =>
      at(m.pos)(err("a module-level 'val' states its type, and a binding that names several things " +
        s"has nowhere to write one — declare ${m.names.map(n => s"'$n'").mkString(" and ")} separately"))

    // A pattern binding is the same case: its parts have nowhere to carry a type either, so one at
    // the top of a file would silently become a local of the entry point.
    case d: PatternDecl if !d.mutable =>
      at(d.pos)(err("a module-level 'val' states its type, and a binding written as a pattern has " +
        "nowhere to write one — take the value apart inside a function, or declare the parts " +
        "separately"))

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
      val plain = Modules.qualify(currentModule, f.name)
      val key   = if funcDecls.contains(plain) then overloadSlot(plain) else plain

      // The other side of the `extern` rule below: overloads of an `extern` are told apart by the
      // symbol each names, and a sysl function has none to give.
      if key != plain && overloadKeys(plain).exists(externDecls.contains) then
        err(s"'${f.name}' is already declared as an 'extern', which this would overload — what tells " +
          "overloads of an 'extern' apart is the symbol each names, and a sysl function declares no " +
          "symbol")

      if constDecls.contains(key) then duplicate(key, s"'${f.name}' is already declared as a constant")
      else if valDecls.contains(key) then duplicate(key, s"'${f.name}' is already declared as a 'val'")
      else if staticVarDecls.contains(key) then
        duplicate(key, s"'${f.name}' is already declared as a module 'var'")
      else if externVarDecls.contains(key) then
        duplicate(key, s"'${f.name}' is already declared as an 'extern' variable")
      funcDecls(key) = f.copy(name = key).setPos(f.pos)
      declScope(key) = currentScope
      recordAccess(key, f.vis)
      // The **plain** key, always: what a library offers under a bare name is the name, and the name
      // is the whole overload set. An overload registered here under its own key would leave the
      // library offering whichever declaration happened to be written last.
      if libraryOffers(f, currentModule) && key == plain then libraryNames(f.name) = key
      if f.tparams.isEmpty then
        funcInsts(key) =
          (f.params.map(p => (p.name, recover(Type.Unknown)(resolveType(p.typ, Map.empty)))),
           f.retType.map(t => recover(Type.Unknown)(resolveReturn(t, Map.empty))).getOrElse(Type.Unit))
      // After every table this declaration fills, because it reports and reporting unwinds: a
      // declaration whose overload is refused is still a declaration the body pass will look up.
      //
      // **`main` is the one name overloading must not reach.** A program starts in one place, and
      // the entry point is found by asking which declarations are *called* `main` — so a second one,
      // filed under a key of its own, would be invisible to that question and the program would
      // start at whichever was written first with the other silently unreachable. It is refused
      // rather than resolved because there is nothing for a call site to choose between: nothing
      // calls `main`.
      if key != plain && Modules.bare(plain) == "main" then
        recover(())(err("'main' is where a program starts, so there is one — a second declaration " +
          "of it would overload the name, and a program has one beginning rather than a set of them"))
      else if key != plain then
        recover(())(checkOverloadDistinct(plain, key, f.params, f.retType, f.variadic))
      checkSignatureRules(f.name, f.params, f.retType, f.variadic)
      checkValueParamArithmetic(f.tvalues.keySet, f.params.map(_.typ) ::: f.retType.toList,
        f.tparams.toSet, f.tpacks)
      checkBoundNames(f.name, f.bounds)
      checkSolvedDefaults("the function", f.name, f.tdefaults)
      checkCrossingNames(f)
      // A `@test` is registered here with everything else a declaration says about itself, so that
      // the runner's list is in declaration order without anything having to sort it afterwards.
      // The checks run at the attribute, which is the part a diagnostic is about (`Tests`).
      for a <- f.test do
        at(a.pos) {
          Tests.problem(f).foreach(err)
          funcInsts.get(key).map(_._2).flatMap(Tests.resultProblem(f, _)).foreach(err)
          tests += Tests.describe(key, a)
        }

    // An `extern`'s **symbol** is not qualified, and cannot be: it names something the linker
    // already has, which knows nothing about sysl's modules. So the key the program calls it by
    // carries the module like any other name, and the symbol is pinned to what was written.
    case e: ExternDecl =>
      val plain = Modules.qualify(currentModule, e.name)
      val key   = if funcDecls.contains(plain) then overloadSlot(plain) else plain

      // **An `extern` overloads, and the C symbol is what keeps the overloads apart** (`12 §1a`).
      // Two sysl declarations sharing a name are two functions; two sharing a *symbol* are one C
      // function claimed at two signatures, and nothing downstream could tell which was meant — the
      // symbol is what is emitted, so both calls would reach the same code with different arguments.
      // That is what `ptr_cast` over a `*extern` is for, written where a reader can see it.
      val externClash =
        Option.when(key != plain) {
          if overloadKeys(plain).flatMap(externDecls.get).exists(_.symbol == e.symbol) then
            s"'${e.name}' is already declared as an 'extern' for the symbol '${e.symbol}' — two " +
              "declarations of one name are two functions, and one C function cannot be two. " +
              "Overloads of an 'extern' are told apart by the symbol each names, so give this one a " +
              "symbol of its own or take its address and 'ptr_cast' it where the other signature is " +
              "wanted"
          else if !externDecls.contains(plain) then
            s"'${e.name}' is already declared as a function, so this 'extern' would overload it — " +
              "what tells overloads of an 'extern' apart is the symbol each names, and a sysl " +
              "function declares no symbol"
          else ""
        }.filter(_.nonEmpty)

      if externVarDecls.contains(key) then
        duplicate(key, s"'${e.name}' is already declared as an 'extern' variable")
      funcDecls(key) = FuncDecl(key, Nil, e.params, e.retType, Nil, variadic = e.variadic).setPos(e.pos)
      declScope(key) = currentScope
      recordAccess(key, e.vis)
      if libraryOffers(e, currentModule) && key == plain then libraryNames(e.name) = key

      val signature =
        (e.params.map(p => (p.name, foreignParam(recover(Type.Unknown)(resolveType(p.typ, Map.empty))))),
         e.retType.map(t => recover(Type.Unknown)(resolveReturn(t, Map.empty))).getOrElse(Type.Unit))

      // **A vector may not cross to C, and this is refused rather than lowered hopefully** — the
      // same reasoning `Exports.signature` gives for an aggregate, and with more force. Each ABI
      // says which register a vector arrives in and under what alignment, the answer differs by
      // target and by which vector extensions the other side was compiled for, and `CAbi` has no
      // rule for one. Emitting the `declare` anyway produces a **corrupt call rather than a link
      // error**, which is the failure a boundary check exists to prevent.
      //
      // The shape to write instead is the one C's own SIMD-taking functions take: a pointer to the
      // lanes, which is a `*T` on both sides and has one meaning everywhere.
      //
      // **Reported after the tables are filled, not before.** `err` does not return, so refusing
      // above the two assignments would leave the name registered as a declaration and absent from
      // `funcInsts` — and every call to it would then take the compiler down with a missing key
      // instead of reporting this. That is `MethodCalls`' "registered and has no lowered form" trap
      // seen from the other side, and it is why the order here is load-bearing.
      funcInsts(key) = signature

      for (name, t) <- signature._1 if Type.repr(t).isInstanceOf[Type.Vector] do
        at(e.pos)(err(s"'$name' of the 'extern' '${e.name}' is ${Type.show(t)}, and how a vector " +
          s"reaches a C function differs by target and by what the other side was compiled for — so " +
          s"sysl will not guess at one. Pass the lanes through memory, as a '*${Type.show(
            Type.repr(t).asInstanceOf[Type.Vector].elem)}'"))

      if Type.repr(signature._2).isInstanceOf[Type.Vector] then
        at(e.pos)(err(s"the 'extern' '${e.name}' returns ${Type.show(signature._2)}, and how a " +
          s"vector comes back from a C function differs by target and by what the other side was " +
          s"compiled for — so sysl will not guess at one. Have it write the lanes through a '*${
            Type.show(Type.repr(signature._2).asInstanceOf[Type.Vector].elem)}' the caller supplies"))


      // An intrinsic's emitted name carries the width it was declared at (`Intrinsics`), so the
      // symbol is settled here — where the signature has just been resolved — rather than being
      // recomputed wherever it is wanted. Everything downstream reads `link` and needs to know
      // nothing about which kind of `extern` this was.
      val symbol =
        if !Intrinsics.declared(e.symbol) then e.symbol
        else
          if e.variadic then
            err("an intrinsic takes a fixed argument list, so it may not be declared with '...'")
          Intrinsics.resolve(e.symbol, signature._1.map(_._2), signature._2) match
            case Right(mangled) => mangled
            case Left(why)      => err(why); e.symbol

      externDecls(key) = e.copy(name = key, link = Some(symbol)).setPos(e.pos)
      // Reported after every table is filled, for the reason the same check in the branch above is:
      // the body pass looks this declaration up whatever was wrong with it.
      for why <- externClash do recover(())(err(why))
      if key != plain then recover(())(checkOverloadDistinct(plain, key, e.params, e.retType, e.variadic))
      checkSignatureRules(e.name, e.params, e.retType, e.variadic, foreign = true)
      for s <- e.link if !s.matches("[A-Za-z0-9_$.]+") do
        err(s"'$s' is not a symbol a linker can resolve")
      // `main` is where a program starts (`13 §7`), which makes it the one name that means something
      // whatever it is attached to. An `extern` may take neither half of it: the **symbol** because
      // this program defines it, so declaring it would be a second definition of one symbol and a
      // link failure rather than anything a reader of this line could act on; and the **name**
      // because a `main` that is not the one the program starts at would read as though it were.
      if e.name == "main" then
        err("'main' is where a program starts, so it is not a name an 'extern' may take")
      if e.symbol == "main" then
        err("'main' is where the platform starts this program, so an 'extern' may not name that symbol")

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
        val cond   = s.invariants.reduce((a, b) => Binary("&&", a, b))
        val ftypes = s.fields.map(p => (p.name, recover(Type.Unknown)(resolveQualified(p.typ, Map.empty))))

        // The synthetic function takes the fields by value, so its parameters are written without
        // whatever qualifier the field carried — a `volatile u32` field is a `u32` argument, exactly
        // as it is everywhere else a register is read. A clause that reads one is refused below;
        // stripping here is what keeps that the *only* thing said about it.
        val params = s.fields.map(p => p.copy(typ = unqualifiedRef(p.typ)).setPos(p.pos))

        funcDecls(ikey) = FuncDecl(ikey, Nil, params, Some(NamedType("bool")),
          List(ExprStmt(cond)), Map.empty, variadic = false).setPos(s.pos)
        declScope(ikey) = currentScope
        funcInsts(ikey) = (ftypes.map((n, t) => (n, Type.unqualified(t))), Type.Bool)

        // What the clauses may read is settled here, where the field types have just been resolved
        // and the whole aliasing rule that rests on them is still ahead (`16 §6`).
        checkInvariantReads(s, ftypes.toMap)

    case _ =>

  /** What `@crossing` may name: parameters of the function it is written above, each once
   * (`06 § Marking a domain boundary`).
   *
   * It is the whole of what can be settled at the declaration — whether an *argument* may cross is a
   * question about the argument, and is asked at each call. Checked here, with the rest of what a
   * declaration says about itself, so a misspelt parameter is reported once at the annotation rather
   * than once per call site or, worse, not at all: a name matching no parameter would otherwise mark
   * nothing and read exactly like a rule that was being enforced.
   */
  private def checkCrossingNames(f: FuncDecl): Unit = {
    val declared = f.params.map(_.name).toSet

    for (n, i) <- f.crossing.zipWithIndex do
      if !declared(n) then
        recover(())(err(s"'@crossing' names '$n', which is not a parameter of '${f.name}'" +
          (if f.params.isEmpty then " — it takes none"
           else s" — its parameters are ${f.params.map(p => s"'${p.name}'").mkString(", ")}")))
      else if f.crossing.take(i).contains(n) then
        recover(())(err(s"'@crossing' names '$n' twice, and a parameter crosses a boundary once — " +
          "the second says nothing the first does not"))
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
      // A bound on the trait's own parameter is held to naming a trait here rather than where the
      // trait was registered, and for the reason every other declaration form's bounds are: the
      // trait it names may be declared below it, or in a file the walk had not reached yet, and
      // being registered is what this pass waits for. Its own recovery region, so a bound that names
      // nothing does not carry off the requirement check below it.
      inScope(declScope(key))(recover(())(checkBoundNames(qn(key), decl.bounds)))
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
            // A requirement is read in the terms of the trait that **wrote** it, not of the one the
            // walk started at: the chain may run through any number of modules, and which trait a
            // short name reaches is what the requiring trait's own file imported.
            inScope(declScope(name))(at(s.pos) {
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
            })

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

  /** Holds every `override` to there being something for it to override (`02 § override`), once every
   * `impl` is registered and the question can be answered.
   *
   * This is the check in the other direction from the one the keyword lifts, and it is the one that
   * earns its keep later: a library drops or narrows the implementation a program was overriding, and
   * without this the override silently becomes the only one while still claiming to replace
   * something. The type's **own** key is deliberately not consulted — that is where this very block
   * is filed, so asking it would find the override overriding itself.
   */
  protected def checkOverrides(): Unit = {
    for (label, bound, ty, pos, scope) <- overrideChecks.toList do
      currentPos = pos
      recover(()) {
        val covering =
          shapeOwners(ty).view.flatMap((k, ta) => implAt(bound, k, ty, ta)).headOption
            .orElse(blanketOwners(ty).view.flatMap((k, ta) => implAt(bound, k, ty, ta)).headOption)

        if covering.isEmpty then
          inScope(scope)(err(s"'$label' says 'override', but nothing else implements " +
            s"'${showBound(bound, ty)}' for it — an override replaces an implementation that covers " +
            s"the type more generally, and there is none to replace"))
      }
    overrideChecks.clear()
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

  /** The key the **second and later** declarations of one function name are filed under (`12 §1a`),
   * recording as it goes that the name now stands for a set.
   *
   * Every table in the analyzer is keyed by a name that stands for one declaration, and overloading
   * is precisely a name that does not — so the first declaration keeps the plain key and each one
   * after it is given a numbered one. Nothing changes for a name declared once: it gets no entry
   * here, and every lookup answers exactly as it did.
   */
  private def overloadSlot(plain: String): String = {
    val existing = overloadKeys(plain)
    val key      = s"$plain.${existing.length + 1}"

    overloadSets(plain) = existing :+ key
    key
  }

  /** Refuses a declaration that some call could not be told apart from one already made (`12 §1a`).
   *
   * **The question is whether a call exists that both would take**, which is the only thing that
   * makes two declarations of a name a problem. Each declaration takes a *range* of argument counts
   * — from its parameters without defaults up to all of them, or up from there with no ceiling if it
   * is variadic — so two of them collide when their ranges overlap at some count and their first
   * that-many parameter types agree.
   *
   * That one rule covers the two cases worth naming separately:
   *
   * - **A pair differing only in the result.** `h(x: int) -> string` and `h(x: int) -> int` collide
   *   at one argument. Resolution never looks at the result (`12 §1a`), so the pair has no call that
   *   distinguishes them and every use would report an ambiguity — one mistake, made once, reported
   *   at every call site instead of at the line that has it.
   * - **A pair whose difference is behind a default.** `g(x: int)` and `g(x: int, y: int = 0)`
   *   collide at one argument, and the second's default is unreachable: no call can ever supply one
   *   argument to it, because the first takes that call. A default nothing can use is worth saying
   *   out loud rather than resolving quietly.
   *
   * **Compared as written rather than as resolved**, deliberately. Hoisting has not resolved a
   * generic parameter's type and cannot, and the written form is what a reader is looking at. Two
   * spellings of one type slip through here and are caught at the call, where they read as the
   * ambiguity they are.
   */
  private def checkOverloadDistinct(
      plain: String,
      key: String,
      params: List[Param],
      retType: Option[TypeRef],
      variadic: Boolean,
  ): Unit = {
    def low(ps: List[Param]) = ps.count(_.default.isEmpty)
    def high(ps: List[Param], v: Boolean) = if v then Int.MaxValue else ps.length

    for
      other <- overloadKeys(plain).filter(_ != key).flatMap(funcDecls.get)
      lo = low(params) max low(other.params)
      hi = high(params, variadic) min high(other.params, other.variadic)
      if lo <= hi
      n = lo min (params.length min other.params.length)
      if params.take(n).map(_.typ) == other.params.take(n).map(_.typ)
    do
      // **A pair whose parameter lists are the same is a DUPLICATE, and is told so.** It is the same
      // rule — a call fits both — but not the same mistake: somebody who declared one function twice
      // has not written an overload set that needs distinguishing, they have written the declaration
      // twice, and a message about how overloads are told apart would send them looking for a
      // difference to add. This is also the message that stood before overloading existed, which is
      // what a reader of an older program is owed.
      if params.map(_.typ) == other.params.map(_.typ) then
        // **The same parameters is a DUPLICATE, and is told so** — the same rule, and not the same
        // mistake. Somebody who declared one function twice has not written an overload set that
        // needs distinguishing; a message about how overloads are told apart would send them looking
        // for a difference to add. This is also the message that stood before overloading existed.
        //
        // Where the two **results** differ the sentence is worth finishing, because that is the pair
        // a reader wrote on purpose and expected to work.
        val because =
          if retType.map(_.show) == other.retType.map(_.show) then ""
          else " — which declaration a call means is decided by its arguments and never by what it " +
            "returns, so two that differ only in the result have no call that tells them apart"

        // Through `duplicate`, so the name is marked contested: the key goes on standing for
        // whichever declaration reached it first, and without this the *losing* file is then told
        // the name is private to its sibling — a name it declares itself, three lines up.
        duplicate(plain, s"function '${Modules.bare(plain)}' is already declared$because")
      else
        err(s"'${qn(plain)}' is already declared with parameters this one could not be told from — " +
          s"a call passing $n argument${if n == 1 then "" else "s"} would fit both, and which " +
          "declaration a call means is decided by its arguments and never by what it returns. Two " +
          "declarations of one name have to differ in a way a call site can show")
  }
}
