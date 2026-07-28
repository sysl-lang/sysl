package io.github.edadma.sysl

import scala.collection.mutable

/** The order the passes run in, and what a function body is checked against.
 *
 * Four things live here and they are one story: the driver that walks a module's files and drains
 * the work each pass queues (`analyze`), what a *file* contributes to the module and where a
 * top-level statement goes, the module-level `val`s and the names a program reaches through a
 * module path, and the definition-time pass of `14 §4` that checks every generic body once with its
 * parameters opaque. Function bodies close it out, since running one is what the driver is draining
 * towards.
 *
 * `units` is supplied by the class: the files being analyzed together.
 */
trait ProgramWalk extends Hoisting with StmtAnalysis with SignatureVisibility with ModuleGraph with InitOrder {

  protected def units: List[Program]

  // --- program -------------------------------------------------------------------------

  def analyze(): TProgram = {
    checkLocations()
    moduleNames ++= units.map(moduleOf)

    // Every declaration is read in the terms its file set up — the module it contributes to and
    // what it imported — so each one is carried alongside those rather than flattened into one
    // list. They are what a name in its signature, its fields, and its body resolves against
    // (`13 §3`), and the imports can be read now because which module a path names is settled by
    // the headers alone.
    // The imports are gathered in the file's own terms, since what an import may reach is a
    // question about where it was written (`13 §2`) as much as about what it names.
    val files = units.map { u =>
      val base = Scope(moduleOf(u), Imports.empty, Some(u.source))

      u -> base.copy(imports = inScope(base)(gatherImports(u.body, Imports.empty)))
    }
    val body = Prelude.decls.map((Scope.root, _)) ::: files.flatMap((u, s) => u.body.map((s, _)))

    // Each declaration, each function body, and each statement is a **recovery region**: a
    // failure inside one is recorded and the region abandoned, and the walk resumes at the next.
    // That is what turns one error per compilation into one error per mistake.
    //
    // Hoisting runs at the top level, where there is no enclosing position to return to, so each
    // pass simply moves the cursor to the declaration it is registering.
    for (scope, stmt) <- body do
      currentPos = stmt.pos
      inScope(scope)(recover(())(hoistType(stmt)))

    // What each trait requires of the types implementing it is answerable now that every trait is
    // registered, and not before: `trait Ord: Eq` is ordinary whichever of the two is written first.
    checkTraitSupers()

    // And what each generic type's defaults stand in for, which is answerable for the same reason
    // and not before: a default may name a type declared below the one that defaults to it.
    checkTypeDefaults()

    // Every constant is folded now, whether or not anything reads it. Folding is lazy so that one
    // may be written in terms of another declared below it, and an unused constant would otherwise
    // never be looked at — leaving a value that does not fit its type, or is not constant at all, as
    // a mistake nobody is told about. It is the declaration that is wrong, so the declaration is
    // where it is reported, and each one is its own recovery region.
    for (key, _) <- constDecls do
      currentPos = constDecls(key).pos
      recover(())(constLiteral(key))

    // A non-generic type is instantiated eagerly, so it is emitted whether or not it is used;
    // a generic one only exists once something asks for a concrete instantiation.
    for (n, d) <- enumDecls if d.tparams.isEmpty do recover(())(instantiateEnum(n, Nil))
    for (n, d) <- structDecls if d.tparams.isEmpty do recover(())(instantiateStruct(n, Nil))

    // Every constrained subtype is resolved now, whether or not anything uses it — so an out-of-range
    // or inverted bound is a mistake reported at the declaration, exactly as a constant's is.
    for (key, d) <- constrainedDecls do
      currentPos = d.pos
      inScope(declScope(key))(recover(())(resolveConstrained(key)))

    for (scope, stmt) <- body do
      currentPos = stmt.pos
      inScope(scope)(recover(())(hoistFunc(stmt)))

    // Every declaration exists now, so what an `import` claimed a module declares can be looked up
    // — and every import from here on, a block's, is checked as it is read.
    checkImportTargets()

    // How far each declaration reaches is settled too, so a signature can be held to naming nothing
    // that reaches less far than it does (`13 §2`). It waits until here because the question is
    // about two declarations at a time, and either may be written below the other.
    checkExposedTypes()

    // A type's members lower to ordinary functions under mangled names, registered here so a
    // method call and an associated-function call resolve exactly as a free call does.
    val members = mutable.ListBuffer.empty[FuncDecl]
    for (tname, sdecl) <- structDecls do
      at(sdecl.pos)(inDecl(tname)(recover(())(hoistMembers(tname, sdecl.members, members))))
    for (tname, edecl) <- enumDecls do
      at(edecl.pos)(inDecl(tname)(recover(())(hoistMembers(tname, edecl.members, members))))
    for (scope, impl) <- implDecls.toList do
      at(impl.pos)(inScope(scope)(recover(())(hoistImpl(impl, members))))

    // Whether a type argument implements what the type asked of it is answerable only now: the
    // signatures resolved above were read before the `impl` blocks below them were registered, so
    // the question was held rather than answered against a table still being filled.
    implsHoisted = true
    for (name, tparams, bounds, targs, pos) <- boundChecks.toList do
      currentPos = pos
      recover(())(checkParamBounds(name, tparams, bounds, targs))
    boundChecks.clear()

    // And whether each `impl` of a trait that requires others supplies those too, which is the same
    // question about the same table and so waits for the same moment.
    checkImplSupers()

    // Every generic body is checked once here, against its bounds alone, before any instantiation
    // is looked at. That is what makes `sum[T](a: T, b: T) = a.plus(b)` fail on its own line
    // instead of at whichever call site first supplied a type without a `plus`.
    checkAbstractBodies()

    // Every `val` is laid down here, before the first body that might read one and in a state
    // belonging to no function — which is the point. A `val`'s initializer is a *module member's*
    // expression, so the locals of whichever function first mentioned it must not be in scope while
    // it is read, and analyzing them all in one place ahead of time is what guarantees they are not.
    // Like a constant's, an unused one is still checked: it is the declaration that is wrong.
    val tvals = mutable.ListBuffer.empty[TVal]

    resetFunction()
    tsubst = Map.empty
    for (key, decl) <- valDecls do
      currentPos = decl.pos
      tvals ++= recoverOpt(analyzeVal(key))

    val tfuncs = mutable.ListBuffer.empty[TFunc]

    // A function whose body did not analyze is left out of the program rather than stood in for:
    // there is nothing to emit, and nothing will be emitted at all while an error stands.
    //
    // The prelude's own functions are held back: they are analyzed below, and only if something
    // reaches them. That keeps a program that never prints from carrying the printing surface.
    //
    // The hoisted declarations are what is walked, rather than the source statements, because
    // hoisting is where each one was renamed to the key its module gives it — an `extern` is left
    // out by the table that says which names have no body rather than by its declaration form.
    val (fromPrelude, ours) = funcDecls.values.toList
      .filter(f => f.tparams.isEmpty && !externDecls.contains(f.name))
      .partition(Prelude.declares)

    for f <- ours do
      tfuncs ++= recoverOpt(analyzeFuncBody(f.name, f, Map.empty))

    // A member is held back on the same terms a free function is, and for the same reason: a
    // prelude *type* with members would otherwise put every one of them into every program, which
    // is the cost the rule above exists to avoid, one declaration form further in. Only the ones
    // with nothing left to instantiate qualify — a generic member is already reached through the
    // queue rather than from here.
    val (preludeMembers, ourMembers) = members.toList
      .filter(f => !defaultOrigin.get(f.name).exists(brokenDefaults))
      .partition(f => f.tparams.isEmpty && Prelude.declares(f))

    for f <- ourMembers do
      tfuncs ++= recoverOpt(analyzeFuncBody(f.name, f, Map.empty))

    val (mainScope, mainStmts) = entryPoint(files)

    resetFunction()
    tsubst = Map.empty
    retTy = Type.Int
    currentModule = mainScope.module
    currentImports = mainScope.imports
    currentFile = mainScope.file
    val tmain = mainStmts.map(recoverStmt)

    // Draining the queue may itself discover further instantiations, so it runs to a fixpoint. An
    // instantiation of a member the definition-time pass already reported is dropped rather than
    // analyzed, for the reason that pass exists: the diagnostic naming the missing bound is the one
    // worth reading, and every instantiation would add another about a consequence of it.
    def drain(): Unit =
      while pending.nonEmpty do
        val (mangled, decl, subst) = pending.dequeue()
        val reported = brokenMembers(decl.name) || defaultOrigin.get(decl.name).exists(brokenDefaults)

        if !reported then tfuncs ++= recoverOpt(analyzeFuncBody(mangled, decl, subst))

    drain()

    // A prelude function is analyzed only once something has called it, and analyzing one may call
    // another — `printi` reaches `putbytes`, `printb` reaches `prints` — so this runs to a fixpoint
    // too. Nothing reaches them in a program that never prints, and none of them is emitted.
    val available = (fromPrelude ::: preludeMembers).map(f => f.name -> f).toMap
    val analyzed  = mutable.HashSet.empty[String]
    var reached   = true

    while reached do
      reached = false
      for name <- funcsUsed.toList if available.contains(name) && !analyzed(name) do
        analyzed += name
        reached = true
        tfuncs ++= recoverOpt(analyzeFuncBody(name, available(name), Map.empty))
      drain()

    // Every reference the program makes has been resolved, so which module depends on which is
    // finally settled and the graph can be held to being acyclic (`13 §6`).
    checkModuleGraph()

    val externs = externsUsed.toList.map { name =>
      val (params, rtype) = funcInsts(name)
      val e               = externDecls(name)
      TExtern(name, e.symbol, params.map(_._2), rtype, e.variadic)
    }

    // Every function the program declares is here, whether or not anything can reach it — analysis
    // is eager, and a body nobody calls is checked and reported exactly as one that is called.
    // Dropping the unreachable ones is `Reachability.prune`, which runs after the passes that read
    // the whole program and just before it is lowered.
    //
    // An instantiation at a **type parameter** is a diagnostic type, not a laid-out one: a `Box[U]`
    // written as a trait's argument in a generic `impl` names the family the block covers, and no
    // value at run time has that type. It has no layout to emit — `Type.Abstract.llvm` says so by
    // refusing — so it is dropped here rather than reaching a backend that would have to invent one.
    // Which `val` needs which is settled by what their initializers reach, so the order they are
    // filled in can only be worked out once every body those initializers call has been analyzed —
    // the same reason the module graph is held to being acyclic here rather than earlier.
    TProgram(
      structInsts.values.filterNot(abstracted).toList,
      enumInsts.values.filterNot(e => e.simple || abstracted(e)).toList,
      vtables.values.toList,
      externs,
      orderVals(tvals.toList, tfuncs.toList, vtables.values.toList),
      tfuncs.toList,
      tmain,
    )
  }

  /** Whether a named type was instantiated at something that is not a type — a parameter standing
   * in for itself, or a type built out of one.
   */
  private def abstracted(t: Type.Named): Boolean = {
    def mentions(x: Type): Boolean = x match
      case _: Type.Abstract    => true
      case n: Type.Named       => n.targs.exists(mentions)
      case Type.Ptr(inner)     => mentions(inner)
      case Type.Ref(inner, _)  => mentions(inner)
      case Type.Array(_, elem) => mentions(elem)
      case Type.Slice(elem)    => mentions(elem)
      case _                   => false

    t.targs.exists(mentions)
  }

  // --- the files of a module -----------------------------------------------------------

  /** The module a file contributes to: what its header says, or the **anonymous root module** when
   * it declares none (`13 §1`).
   */
  private def moduleOf(u: Program): String = u.module.map(_.show).getOrElse(Modules.root)

  /** A module is a directory, and its name is that directory's path relative to the project root
   * (`13 §1`), so a file's header has to agree with where the file sits.
   *
   * The location is the driver's to know, and it hands it over on the `Source`. A file handed to
   * the compiler with no project around it carries none, and its header is then the whole of what
   * says which module it is in — which is how a single file, and the tests that compile a handful
   * of them directly, go on working with no project to be measured against.
   *
   * Checking the header against the *location* subsumes checking the files of a directory against
   * each other: they are each held to the same derived name, so a file that was edited without its
   * siblings is reported on its own line rather than as a disagreement with whichever sibling
   * happened to be read first.
   */
  private def checkLocations(): Unit =
    for u <- units; dir <- u.source.dir do
      val expected = dir.mkString(".")
      val declared = moduleOf(u)

      if declared != expected then
        val theirs = if declared.isEmpty then "declares no module" else s"declares '$declared'"
        val here   = if expected.isEmpty then "sits at the project root" else s"sits in '$expected'"

        recover(())(at(u.module.flatMap(_.pos).orElse(u.body.headOption.flatMap(_.pos))) {
          err(s"${u.source.name} $theirs, but it $here — a module is a directory, so the two must agree")
        })

  /** The statements that become the program's entry point, and the module they are read in.
   *
   * A declaration is hoisted and belongs to the module it was written in, but an executable
   * statement runs, and running happens in an order. Files have none — a module's members are one
   * unordered set (`13 §6`) — and neither do modules, which are a graph rather than a sequence. So
   * **one file of the program carries the statements it runs**, and a second that carries any is a
   * mistake rather than an ordering to be guessed at.
   */
  private def entryPoint(files: List[(Program, Scope)]): (Scope, List[Stmt]) = {
    // An `import` is not one: it binds a name for the file that wrote it and runs nothing, so a
    // file may import whatever it likes without becoming the file the program starts in.
    // Neither is a `const` or a `val`: both are declarations, hoisted, and a module that names a
    // dimension or carries a table must not thereby become the file the program starts in. That is
    // also what makes every `ValDecl` the statement walk goes on to meet a **local** — the ones
    // written at the top of a file never reach it.
    def executable(u: Program) = u.body.filter {
      case _: FuncDecl | _: StructDecl | _: EnumDecl | _: TraitDecl | _: ImplDecl | _: ExternDecl |
          _: ImportDecl | _: ConstDecl | _: ValDecl | _: TypeDecl =>
        false
      case _ => true
    }

    files.map((u, s) => (u, s, executable(u))).filter(_._3.nonEmpty) match
      case Nil                  => (Scope.root, Nil)
      case (_, s, stmts) :: Nil => (s, stmts)
      case (first, s, stmts) :: others =>
        for (u, _, rest) <- others do
          recover(())(at(rest.head.pos) {
            err(s"${first.source.name} already carries the statements this program runs, so " +
              s"${u.source.name} may hold declarations only")
          })
        (s, stmts)
  }

  // --- module-level `val`s --------------------------------------------------------------

  /** Analyzes one module-level `val` to the storage it lays down.
   *
   * The initializer is read at the declared type, so an element written `0x428a2f98` in a `[64]u32`
   * needs no suffix — the same courtesy a `const` gets from writing its type, for the same reason.
   *
   * What the initializer *is* decides only how the storage gets filled: a constant tree is written
   * into the object file, and anything else becomes code that runs once before the program's own
   * statements. What is checked here is the **type**, which is the same either way.
   */
  private def analyzeVal(key: String): TVal = inDecl(key)(at(valDecls(key).pos) {
    val decl = valDecls(key)
    val ty   = valType(key)
    val init = analyzeExpr(decl.value, Some(ty))

    if disagree(init.ty, ty) then
      err(s"cannot initialize '${qn(key)}': declared ${show(ty)} but the value is ${show(init.ty)}")
    checkPlain(ty, key)
    TVal(key, ty, init, !isStatic(init))
  })

  /** Holds a module-level `val` to **plain data** — numbers, characters, booleans, enums, and the
   * structs and arrays built from those.
   *
   * The rule is about the two promises a `val` makes. It is read-only *at every depth* (`13 §7`), and
   * a reference, a pointer, or a slice inside one is a route around that: the storage cannot be
   * written but what it points at can, one dereference away from where the mistake could still be
   * reported. And it exists for the whole run, so a counted value in one is never let go of — a leak
   * the program has no way to spell the release of. A `string` is refused by the same test, being a
   * view with an owner word, and that is the one thing this costs today.
   */
  private def checkPlain(ty: Type, key: String): Unit =
    // The whole difference between a `val` and a `const` is that a `val` has an address (`13 §7`),
    // and a value with no representation has nothing to put one on. The initializer would still run,
    // which is the only thing such a declaration could have been for — and a statement says that
    // without pretending there is storage.
    if Type.zeroSized(ty) then
      err(s"'${qn(key)}' cannot be a 'val': a ${show(ty)} value occupies nothing, so there is no " +
        "storage for the name to stand for — write the call as a statement instead")
    else if !plain(ty) then
      err(s"'${qn(key)}' cannot be a 'val': its type is ${show(ty)}, and a 'val' holds plain data " +
        "only — a reference, a pointer, a slice, or a string in storage that outlives every frame " +
        "would be neither released nor held to being read-only")

  private def plain(t: Type): Boolean = t match
    case _: Type.Ptr | _: Type.Ref | _: Type.View | _: Type.Trait => false
    case Type.Array(_, elem)                                      => plain(elem)
    case s: Type.Struct                                           => s.fields.forall(f => plain(f._2))
    case e: Type.Enum => e.variants.forall(_.fields.forall(f => plain(f._2)))
    case c: Type.Constrained => plain(c.base)
    case _                   => true

  /** Whether an initializer is a value the object file can carry as it stands: numbers, and the
   * arrays built from them. Everything else is code, which is what `13 §7`'s initialization order is
   * about — this is the test that decides which of the two a declaration is.
   */
  private def isStatic(t: TExpr): Boolean = t match
    case _: TIntLit | _: TFloatLit | _: TBoolLit => true
    case TArrayLit(elems, _)                     => elems.forall(isStatic)
    case TArrayFill(value, _)                    => isStatic(value)
    case _                                       => false

  // --- definition-checked bounds -------------------------------------------------------

  /** Checks every generic body once, at its definition, with each type parameter opaque except for
   * what its bounds promise (`14 §4`). This is the mechanism `10 §5` committed to, and what tells
   * sysl's generics apart from a C++ template: a body that assumes more than it declared is wrong
   * whether or not anything ever instantiates it, and this is where it is told so.
   *
   * Every declaration that carries type parameters is walked, and that now includes a generic
   * *type's* own members: `struct SortedList[T: Ord]` is where its bound is written, so a member may
   * assume `Ord` of `T` and nothing more, whether or not anything ever instantiates the type. A
   * generic `impl`'s members are walked for the same reason and against the block's own bounds —
   * which is what conditional conformance buys beyond deciding whether a `Box[int]` conforms.
   *
   * **A trait's default bodies are walked here too**, each as the generic function it is: one
   * parameter, `Self`, bounded by its own trait (`Hoisting.traitDefaults`). A default may assume of
   * its receiver exactly what the trait promises, which is the same rule this pass already enforces
   * — so it is checked at the trait, once, rather than once per implementing type, and a trait with
   * no implementations at all still has its defaults checked.
   *
   * **And a generic type's own fields are laid out once here**, which is the same rule applied to a
   * declaration that has no body at all: a field applying another bounded type to this one's
   * parameter is wrong at the declaration, and saying so per instantiation would blame whatever type
   * turned up for something the line never mentioned.
   */
  private def checkAbstractBodies(): Unit = {
    val generics = funcDecls.values.toList.filter(_.tparams.nonEmpty)
    val members  = abstractMembers.toList
    val defaults = traitDefaults

    if generics.nonEmpty || members.nonEmpty || defaults.nonEmpty then
      sandboxed {
        abstractPass = true

        try
          checkAbstractLayouts()

          for f <- generics do
            currentPos = f.pos
            inDecl(f.name)(recover(())(sandboxed(checkAbstractBody(f))))

          // A member reported here has been reported against the body as written, naming the bound
          // that would license what it does. Every instantiation would fail the same way and say so
          // in terms of whatever type it was made at, so those are dropped instead — one mistake,
          // one diagnostic, in the words that name the fix.
          for f <- members do
            currentPos = f.pos
            val before = diagnosticCount
            inDecl(f.name)(recover(())(sandboxed(checkAbstractBody(f))))
            if diagnosticCount > before then brokenMembers += f.name

          // A default that fails here has been reported, at the trait, against the body a
          // programmer actually wrote. The copies made for each implementing type would fail the
          // same way — against the same source line, blaming a type the line does not mention — so
          // they are dropped rather than analyzed, and one mistake stays one diagnostic.
          for f <- defaults do
            currentPos = f.pos
            val before = diagnosticCount
            inDecl(f.name)(recover(())(sandboxed(checkAbstractBody(f))))
            if diagnosticCount > before then brokenDefaults += f.name
        finally abstractPass = false
      }
  }

  /** Lays out every generic type once with its parameters standing in for themselves, so that what
   * its fields and payloads *apply* those parameters to is checked against the bounds it wrote.
   *
   * A non-generic type is laid out eagerly and needs none of this. A generic one has no layout until
   * something instantiates it, so a field holding an `Inner[T]` where `Inner` asks more of its
   * parameter than this type asks of `T` would otherwise be found at the instantiation — reported
   * against a type argument the declaration never named.
   *
   * Each layout is walked in a sandbox of its own, as each body below is. A parameter standing in
   * for itself is memoized under the name it was written with — `Box[T]` — and two declarations
   * whose parameters are both spelled `T` bound it to different things, so an instantiation kept
   * from one walk would answer the next walk's question with the first one's bounds.
   */
  private def checkAbstractLayouts(): Unit = {
    def abstracts(tparams: List[String], bounds: Map[String, List[BoundRef]]): List[Type] = {
      val subst = abstractSubst(tparams, bounds)

      tparams.map(subst)
    }

    for (n, d) <- structDecls if d.tparams.nonEmpty do
      currentPos = d.pos
      recover(())(sandboxed(instantiateStruct(n, abstracts(d.tparams, d.bounds))))

    for (n, d) <- enumDecls if d.tparams.nonEmpty do
      currentPos = d.pos
      recover(())(sandboxed(instantiateEnum(n, abstracts(d.tparams, d.bounds))))
  }

  /** One generic body, analyzed with each of its type parameters substituted by itself. */
  private def checkAbstractBody(f: FuncDecl): Unit = at(f.pos) {
    val subst: Map[String, Type] = withSelf(f.name, abstractSubst(f.tparams, f.bounds))
    val params = f.params.map(p => (p.name, recover(Type.Unknown)(resolveType(p.typ, subst))))
    val rtype  = f.retType.map(t => recover(Type.Unknown)(resolveReturn(t, subst))).getOrElse(Type.Unit)

    analyzeBodyWith(f.name, f, subst, params, rtype)
  }

  // --- function bodies -----------------------------------------------------------------

  private def analyzeFuncBody(name: String, f: FuncDecl, subst: Map[String, Type]): TFunc =
    // A body means what it meant where it was written: an `impl` in one module for a type in
    // another lowers members keyed under the type, and a trait's default is copied into every
    // implementing type, so which module a name in it resolves against travels with the body.
    inDecl(f.name)(at(f.pos)(analyzeFuncBodyAt(name, f, subst)))

  private def analyzeFuncBodyAt(name: String, f: FuncDecl, subst: Map[String, Type]): TFunc = {
    val (params, rtype) = funcInsts(name)

    analyzeBodyWith(name, f, subst, params, rtype)
  }

  /** Analyzes one body against a signature it is handed, rather than one looked up in `funcInsts`.
   * An instantiation's signature is registered there; the definition-time pass of `14 §4` resolves
   * its own, since a generic declaration has no entry until something instantiates it.
   */
  private def analyzeBodyWith(
      name: String,
      f: FuncDecl,
      subst: Map[String, Type],
      params: List[(String, Type)],
      rtype: Type,
  ): TFunc = {
    resetFunction()
    // A member's body sees `Self` alongside whatever type parameters it was instantiated with, so
    // the one substitution answers both questions and nothing downstream has to know the difference.
    tsubst = subst ++ memberSelf.getOrElse(name, Map.empty)
    retTy = rtype
    variadicFn = f.variadic
    val tparams = params.map { case (n, t) => (declare(n, t), t) }
    val (contracts, rest)         = f.body.span { case _: Require | _: Ensure => true; case _ => false }
    val (requires, ensures, olds) = analyzeContracts(rtype, contracts)

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
        err(s"function '${f.name}' should return ${show(rtype)}, but its body yields ${show(tbody.ty)}")

    TFunc(name, tparams, rtype, tbody, f.variadic, requires, ensures, olds)
  }

  /** Typechecks the leading `require`/`ensure` clauses. Both conditions must be `bool`. `result`
   * and `old(e)` are only in scope inside an `ensure` — `result` also only when the function
   * returns a value — and the `old` expressions are collected so codegen can snapshot them at
   * entry.
   */
  private def analyzeContracts(
      rtype: Type,
      clauses: List[Stmt],
  ): (List[(TExpr, Option[String])], List[(TExpr, Option[String])], List[TExpr]) = {
    val requires = mutable.ListBuffer.empty[(TExpr, Option[String])]
    val ensures  = mutable.ListBuffer.empty[(TExpr, Option[String])]
    val olds     = mutable.ListBuffer.empty[TExpr]

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
        case _ => // span guarantees only Require/Ensure reach here
    (requires.toList, ensures.toList, olds.toList)
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
