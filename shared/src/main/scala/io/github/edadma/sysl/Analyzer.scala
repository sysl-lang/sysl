package io.github.edadma.sysl

import scala.collection.mutable

/** The semantic pass: it resolves names, checks types, and turns the untyped `Program` into
 * a typed `TProgram` that codegen lowers directly. All diagnostics live here; codegen trusts
 * the tree it is handed.
 *
 * The work is split across traits mixed into this class, the way codegen is split across
 * `Emitter` and friends: `AnalyzerBase` holds the shared tables and name scopes, `TypeResolution`
 * resolves and instantiates types, `Literals` handles the scalar leaves, `Hoisting` registers
 * declarations, `StmtAnalysis` handles statements and blocks, `CallAnalysis` handles calls and
 * construction, `PatternAnalysis` handles `match`, and `SpecialForms` holds the handful of call
 * forms the compiler resolves by name. What stays here is the spine — the driver that runs the
 * passes in order, function bodies, the expression dispatch, and places — plus the recursive entry
 * points the traits call back into through `AnalyzerBase`'s hooks.
 *
 * Declarations are hoisted, so functions, structs, and enums may be used before they appear
 * and may be mutually recursive. Each function (and the synthetic `main` around the top-level
 * statements) is its own naming context: a variable that shadows an outer one is renamed to a
 * unique register name, which keeps codegen's per-function SSA names distinct without the
 * analyzer having to understand LLVM.
 *
 * **Generics are monomorphized here.** A generic declaration is kept in its untyped form and
 * instantiated on demand: each distinct set of type arguments produces its own `Type.Struct` /
 * `Type.Enum` / `TFunc` under a mangled name, and codegen never sees a type parameter. Type
 * arguments are inferred from the argument types at a call or construction, and from the
 * *expected* type when the arguments alone do not determine them — which is what lets `None`
 * and `Ok(5)` take their type from the context they appear in.
 */
class Analyzer private (units: List[Program])
    extends CallAnalysis
    with PatternAnalysis
    with Literals
    with Hoisting
    with StmtAnalysis
    with SpecialForms
    with SignatureVisibility
    with ModuleGraph {

  /** Every error the walk found, rendered and in source order. */
  def errors: List[String] = diagnostics

  // --- program -------------------------------------------------------------------------

  private def analyze(): TProgram = {
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

    TProgram(
      structInsts.values.toList,
      enumInsts.values.filterNot(_.simple).toList,
      vtables.values.toList,
      externs,
      tvals.toList,
      tfuncs.toList,
      tmain,
    )
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
   */
  private def analyzeVal(key: String): TVal = inDecl(key)(at(valDecls(key).pos) {
    val decl = valDecls(key)
    val ty   = valType(key)
    val init = analyzeExpr(decl.value, Some(ty))

    if disagree(init.ty, ty) then
      err(s"cannot initialize '${qn(key)}': declared ${show(ty)} but the value is ${show(init.ty)}")
    checkStatic(init, key)
    TVal(key, ty, init)
  })

  /** Holds a `val`'s initializer to being a value the object file can carry as it stands.
   *
   * Storage that exists before anything runs must have something to be laid down *as*, and a
   * constant tree is the only thing that qualifies: numbers, and arrays built from them. Everything
   * outside that set needs code to run before `main` and a rule for what order those run in, which
   * is the extension a computed table will ask for and not a gap in this one.
   */
  private def checkStatic(t: TExpr, key: String): Unit = t match
    case _: TIntLit | _: TFloatLit | _: TBoolLit => ()
    case TArrayLit(elems, _)                     => elems.foreach(checkStatic(_, key))
    case TArrayFill(value, _)                    => checkStatic(value, key)
    case other =>
      at(other.pos.orElse(valDecls(key).pos))(err(
        s"the value of '${qn(key)}' is not a constant: a 'val' is laid down before anything runs, " +
          "so its value must be a number, or an array of them"))

  // --- names reached through a module ---------------------------------------------------

  /** A reference written as a chain of plain names: `std.fs.read` is `["std", "fs", "read"]`.
   * `None` for anything else, since a chain interrupted by a call or a subscript is a value being
   * read from rather than a path being named.
   */
  private def chain(e: Expr): Option[List[String]] = e match
    case Ident(n)    => Some(List(n))
    case Field(r, f) => chain(r).map(_ :+ f)
    case _           => None

  /** A reference reaching into a module, rewritten with the module folded into the name it
   * qualifies — `std.fs.read` becomes the one name `std.fs`'s `read` is keyed under — or `None`
   * where the chain names no module.
   *
   * That rewrite is the whole of what qualified access needs: what is left is `read(…)`,
   * `Point(…)`, `Shape.Circle(…)` — the ordinary forms, resolved by the cases that already handle
   * them, against tables that were keyed this way to begin with.
   *
   * Two rules decide it, and both are `13 §3`'s. **A local binding shadows a module name**, so a
   * chain whose head is bound to a value is a field read and nothing else — which is why this
   * cannot be a pre-pass over the tree and has to be asked where the scopes are. And the
   * **longest** module prefix wins, so a module `a.b` is reached as one rather than as `a`'s `b`.
   *
   * A head that names no module is read as an import of one, which is what makes the `fs` of
   * `import std.fs` a prefix everywhere a written path is.
   */
  private def throughModule(e: Expr): Option[Expr] =
    for
      written <- chain(e) if written.length > 1 && lookupOpt(written.head).isEmpty
      path = if namesModule(written.head) then written
             else importedModule(written.head).fold(written)(_.split('.').toList ::: written.tail)
      k <- (path.length - 1).to(1, -1).find(n => moduleNames(path.take(n).mkString(".")))
    yield
      val module = path.take(k).mkString(".")
      val rest   = path.drop(k)

      // The key this builds is spelled the way the compiler spells its own references, so resolving
      // it says nothing about which module wrote it — but *this* is a path a file wrote, in the
      // terms of the body being read, so the dependency it makes is recorded here (`13 §6`).
      dependsOn(module)
      rest.tail.foldLeft[Expr](Ident(Modules.qualify(module, rest.head)))((acc, n) => Field(acc, n))
        .setPos(e.pos)

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

  /** `old(e)` — the value `e` had at function entry. It is analyzed in the entry scope the `ensure`
   * runs in (parameters, but no body locals, which are not in scope yet), then recorded in the
   * `old` buffer so codegen snapshots it before the body runs. The position it took is what the
   * postcondition reads back.
   */
  private def oldCall(args: List[Expr]): TExpr = {
    val e = args match
      case List(one) => one
      case _         => err(s"'old' takes exactly one argument, but got ${args.length}")

    val te = analyzeExpr(e, None)
    if te.ty == Type.Unit then err("'old' needs a value to remember, but its argument is unit")

    val buf = oldBuf.get
    val idx = buf.length
    buf += te
    TOld(idx, te.ty)
  }

  /** A comparison chain, checked link by link. A link the machine performs directly needs its
   * operands to agree and the type to have the comparison being asked of it — equality reaches
   * further than ordering (`01`); a link a trait supplies had both checked against the trait's own
   * signature when `compareLink` resolved it.
   */
  private def compareChain(ts: List[TExpr], cmps: List[TCmp]): TExpr = {
    for i <- cmps.indices if cmps(i).dispatch.isEmpty do
      val op       = cmps(i).op
      val (a, b)   = (ts(i), ts(i + 1))
      val equality = op == "==" || op == "!="
      if a.ty != b.ty then err(s"cannot compare ${show(a.ty)} with ${show(b.ty)}")
      if !(if equality then Type.isEquatable(a.ty) else Type.isOrdered(a.ty)) then
        err(s"'$op' is not defined for ${show(a.ty)}")

    TCompare(ts, cmps)
  }

  /** Registers an instantiation of a generic function and returns the name codegen will emit.
   * The signature is recorded before the body is queued, so a recursive generic function
   * resolves its own call.
   */
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

  // --- expressions ---------------------------------------------------------------------

  protected def analyzeBool(e: Expr): TExpr = {
    val t = analyzeExpr(e, Some(Type.Bool))
    if t.ty != Type.Bool then err(s"condition must be bool, got ${show(t.ty)}")
    t
  }

  /** Analyzes an expression. `expected` is the type the context wants, used where the
   * expression cannot determine its own type arguments — a bare `None`, an `Ok(v)` whose error
   * type is not mentioned by its argument, a generic call whose result alone is generic — and
   * where it decides that a value belongs on the heap.
   *
   * A context expecting `&T` asks the expression for a `T` and boxes what comes back, so
   * writing the ordinary construction is the whole spelling of an allocation. An expression
   * that is already a `&T` passes through untouched.
   */
  protected def analyzeExpr(expr: Expr, expected: Option[Type], discarded: Boolean): TExpr = {
    // A discarded expression is by definition one nothing was expected of, so there is no context
    // to push down and the conversion cases below have nothing to say — what the flag carries is
    // the *absence* of a consumer, which only the branching forms act on.
    val t = at(expr.pos)(
      if discarded then analyzeValue(expr, None, discarded = true) else analyzeExpected(expr, expected),
    ).setPos(expr.pos)

    // A value whose type could not be worked out — a name whose declaration failed, a field of a
    // type that did not resolve, a call to a function with an unusable signature — abandons this
    // statement quietly. The mistake was reported where it was made, and every consequence of it
    // reported as well would bury the one diagnostic worth reading.
    if t.ty == Type.Unknown then poisoned()

    t
  }

  private def analyzeExpected(expr: Expr, expected: Option[Type]): TExpr = expected match
    // An `if`/`match`/loop yields its value through its branches — a loop's, through its `break`s
    // and its `else` — so a context that *converts* belongs to each of those rather than to the
    // aggregate: every branch boxes or erases on its own. That is what lets a `&T` branch and a
    // plain-value branch meet at `&T`, and two branches of different concrete types meet at one
    // trait object. Converting the whole expression instead would ask each branch for something it
    // may already be past being able to supply.
    case Some(want) if converts(want) && branching(expr) => analyzeValue(expr, Some(want))

    // A trait object asks the expression for nothing in particular: what may be erased into one is
    // whatever implements the trait, and pushing the object's own type down would be asking for a
    // value of a type that has no layout. `null` is the exception — a raw address is written at
    // the type it is expected to have, rather than converted into it.
    case Some(o) if Type.erased(o) =>
      expr match
        case NullLit() => analyzeValue(expr, Some(o))
        case _         => coerce(analyzeValue(expr, None), o)

    case Some(r: Type.Ref) =>
      expr match
        case NullLit() => err(s"a ${show(r)} always points at a live object — an absent one is Option[${show(r)}]")
        case _         => coerce(analyzeValue(expr, Some(r.inner)), r)

    // A value produced into a transparent constrained subtype is analyzed at the subtype's base — so
    // a literal and arithmetic type as that base — and then checked into the subtype. A value that
    // does not agree with the base is left unwrapped for the caller to diagnose, and one that already
    // has this exact subtype is not re-checked.
    case Some(c: Type.Constrained) if !c.derived =>
      val v = analyzeValue(expr, Some(c.base))
      if disagree(v.ty, c.base) then v
      else if v.ty == c then v
      else checkInto(v, c)

    case _ => analyzeValue(expr, expected)

  /** Wraps a base-typed value in the run-time check for a constrained subtype. */
  private def checkInto(v: TExpr, c: Type.Constrained): TExpr = TConstrainedCheck(v, c).setPos(v.pos)

  /** `Name(value)` — an explicit cast into a constrained subtype. The operand is taken at the
   * subtype's base and checked; a value whose base does not agree is a mistake the message names.
   */
  private def constrainedCast(key: String, args: List[Expr]): TExpr = {
    val c = resolveConstrained(key)

    if args.length != 1 then err(s"a '${qn(key)}' conversion takes exactly one value")
    val v = analyzeExpr(args.head, Some(c.base))
    if disagree(v.ty, c.base) then err(s"cannot make ${show(c)} from ${show(v.ty)}")
    checkInto(v, c)
  }

  /** Whether a context of this type converts what it is given rather than simply requiring it. */
  private def converts(want: Type): Boolean = Type.erased(want) || want.isInstanceOf[Type.Ref]

  /** What an array form's elements should be analyzed as, given what the form itself is expected to
   * produce. A `string` is not on the list: its elements are bytes, but writing one is a validity
   * question rather than an arrangement of elements.
   */
  private def elementWanted(want: Type): Option[Type] = want match
    case Type.Array(_, e) => Some(e)
    case Type.Slice(e)    => Some(e)
    case _                => None

  /** Whether an expression yields its value through branches rather than producing one itself. */
  private def branching(expr: Expr): Boolean = expr match
    case _: IfExpr | _: MatchExpr | _: While | _: For => true
    case _                                            => false

  /** The two conversions a context may apply to a value that does not already have its type: a
   * `T` the context wanted by reference is boxed, and something concrete where a trait object was
   * wanted is erased into one. Nothing else coerces — any other mismatch is left for the caller to
   * diagnose, where the message can name the parameter or the variable it is about.
   */
  protected def coerce(t: TExpr, expected: Type): TExpr = expected match
    case _ if Type.erased(expected)     => eraseTo(t, expected)
    case r: Type.Ref if t.ty == r.inner => TBox(t, r).setPos(t.pos)
    case _                              => t

  private def analyzeValue(expr: Expr, expected: Option[Type], discarded: Boolean = false): TExpr =
    at(expr.pos)(analyzeValueAt(expr, expected, discarded)).setPos(expr.pos)

  private def analyzeValueAt(expr: Expr, expected: Option[Type], discarded: Boolean = false): TExpr = expr match
    case IntLit(v, suffix)   => intLiteral(v, suffix, expected)
    case FloatLit(t, suffix) => floatLiteral(t, suffix, expected)
    case CharLit(cp)         => TIntLit(cp, Type.Char)
    case StrLit(s)           => TStrLit(s)
    // A C callee finds the end by the terminator, so an interior NUL would hide everything written
    // after it. Refused outright rather than silently truncated — an ordinary `"a\0b"` is unaffected,
    // since carrying a length is exactly what lets it hold one.
    case CStrLit(s) =>
      if s.indexOf(0) >= 0 then
        err("a C string ends at its first NUL, so it cannot contain one — the bytes after it could never be read")
      TCStrLit(s)
    case BoolLit(b)          => TBoolLit(b)
    case UnitLit()           => TUnitLit()

    case NullLit() =>
      expected match
        case Some(p: Type.Ptr) => TNullLit(p)
        case Some(other)       => err(s"'null' is a raw pointer, and ${show(other)} was expected here")
        case None              => err("'null' takes its type from its context, and there is none here")

    // A minus and the literal it precedes are one unit for the range check, so a signed type's
    // minimum is writable even though its magnitude overflows the positive range.
    case Unary("-", IntLit(v, suffix))   => intLiteral(-v, suffix, expected)
    case Unary("-", FloatLit(t, suffix)) => floatLiteral("-" + t, suffix, expected)

    // `result` is a contextual keyword: it names the returned value inside an `ensure`, but a
    // real binding of that name (a parameter or local) still shadows it, so the lookup comes first.
    case Ident("result") if lookupOpt("result").isEmpty =>
      ensureResultTy match
        case Some(ty) => TResult(ty)
        case None     => err("'result' is only meaningful inside an 'ensure' of a value-returning function")

    case Ident(name) =>
      lookupOpt(name) match
        case Some((u, ty)) => TLoad(u, ty)
        case None =>
          variantKey(name) match
            case Some(key) => constructVariant(key, Nil, expected)
            case None =>
              // A constant is folded into its use and analyzed as the literal it stands for, at the
              // type it was declared with rather than the one the context asked for. That is what
              // makes it behave like the value it names: `const n: usize = 5` used where an `int`
              // belongs is the same mismatch a `usize` variable would be, not a silent adaptation.
              constKey(name) match
                case Some(key) => analyzeExpr(constLiteral(key), Some(constType(key)))
                // A `val` is the other half of that: nothing is folded, because it is storage. The
                // name reaches the storage itself, which is why it can be indexed and iterated.
                case None =>
                  valKey(name) match
                    case Some(key) => TGlobal(key, valType(key))
                    case None      => err(s"undefined name '${qn(name)}'")

    case Binary(op @ ("&&" | "||"), l, r) =>
      TLogical(op, analyzeBool(l), analyzeBool(r))

    case Binary(op, l, r) =>
      val List(tl, tr) = analyzeOperands(List(l, r), expected.filter(Type.isNumeric))
      operatorCall(op, tl, tr).getOrElse(TBinary(op, tl, tr, arithType(op, tl.ty, tr.ty)))

    case Unary("-", e) =>
      val t = analyzeExpr(e, expected.filter(Type.isNumeric))
      prefixCall("-", t).getOrElse(t.ty match
        case i: Type.Integer if i.signed => TUnary("-", t, i)
        case f: Type.Floating            => TUnary("-", t, f)
        case i: Type.Integer             => err(s"unary '-' is not defined for the unsigned type ${show(i)}")
        case other                       => err(s"unary '-' is not defined for ${show(other)}"))

    case Unary("!", e) =>
      TUnary("!", analyzeBool(e), Type.Bool)

    case Unary("~", e) =>
      val t = analyzeExpr(e, expected.filter(Type.isNumeric))
      prefixCall("~", t).getOrElse(t.ty match
        case i: Type.Integer => TUnary("~", t, i)
        case other           => err(s"unary '~' is not defined for ${show(other)}"))

    // Address-of yields a *raw* pointer: a place lives in a frame or inside another object, so
    // there is no refcount to take a share of. Reaching a `&T` means being handed one.
    case Unary("&", e) =>
      val place = analyzePlace(e, "'&'")
      TAddrOf(place, Type.Ptr(place.ty))

    case Unary("*", e) =>
      val t = analyzeExpr(e)
      Type.pointee(t.ty) match
        case Some(inner)                => TDeref(t, inner)
        // A trait object points somewhere, but it has forgotten what is there, so there is no type
        // to read out — its methods are the whole of what it still offers.
        case None if Type.erased(t.ty) =>
          err(s"a ${show(t.ty)} has forgotten what it points at, so there is no value to read " +
            "through it — call one of the trait's methods instead")
        case None                       => err(s"'*' needs a pointer or a reference, not ${show(t.ty)}")

    case Unary(op, _) =>
      err(s"unary '$op' is not supported yet")

    case PreIncDec(op, target)  => incDec(op, target, pre = true)
    case PostIncDec(op, target) => incDec(op, target, pre = false)

    // Each link of the chain is resolved on its own — an instruction where the operand type has
    // one, the method its `Eq`/`Ord` supplies otherwise (`14 §2`) — so a chain of user types reads
    // and behaves exactly as a chain of scalars does, sharing each middle operand between the two
    // comparisons that use it.
    case Compare(operands, ops) =>
      val ts = analyzeOperands(operands, None)

      compareChain(ts, ops.indices.map(i => compareLink(ops(i), ts(i), ts(i + 1))).toList)

    case Assign("=", target, value) =>
      val place = analyzePlace(target, "assignment")
      val tv    = analyzeExpr(value, Some(place.ty))
      // A diverging value is no value to store, so it is rejected here rather than agreeing the way
      // a `never` does where one really may stand — as the value a `return` or a branch yields.
      if tv.ty == Type.Never || disagree(tv.ty, place.ty) then
        err(s"cannot assign ${show(tv.ty)} to ${describe(target)} of type ${show(place.ty)}")
      TStore(place, tv, place.ty)

    // `p += q` on a type whose `Add` is a real implementation updates the place from the value it
    // already read, exactly as the scalar form does — the dispatch travels with the node rather
    // than becoming a call tree that would read the place twice.
    case Assign(op, target, value) =>
      val place  = analyzePlace(target, s"'$op'")
      val binSym = op.dropRight(1)
      val tv     = analyzeExpr(value, Some(place.ty))
      val d      = updateDispatch(binSym, place, tv)

      if d.isEmpty && arithType(binSym, place.ty, tv.ty) != place.ty then
        err(s"'$op' would change the type of ${describe(target)}")

      TUpdate(place, op, tv, place.ty, d)

    // The forms the compiler resolves by name rather than by looking a function up: `print` and
    // its two rendering companions, which are temporary and leave once a `Display` trait can carry
    // them, and the three ABI primitives of a variadic body, which stay. What each one means is in
    // `SpecialForms`; the dispatch is here so it reads in the order the match tries.
    case Call(Ident("print"), args)                         => printCall(args)
    case Call(Ident("str"), args)                           => strCall(args)
    case Call(Ident("format"), List(argExpr, StrLit(spec))) => formatCall(argExpr, spec)
    case Call(Ident("va_start"), args)                      => vaStart(args)
    case Call(Ident("va_end"), args)                        => vaEnd(args)
    case Call(Ident("va_arg"), args)                        => vaArg(args, expected)

    // `old(e)` is a contextual keyword read only while an `ensure` is being analyzed; the guard is
    // what lets `old` stay an ordinary name outside a postcondition.
    case Call(Ident("old"), args) if oldBuf.isDefined       => oldCall(args)

    // A conversion is written with call syntax, so a scalar type name in call position is one.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && scalarType(name).isDefined =>
      if args.length != 1 then err(s"a '$name' conversion takes exactly one value")
      convert(analyzeExpr(args.head), scalarType(name).get)

    // A constrained subtype's name in call position wraps a base value into the subtype, checking it
    // — `Age(n)`, `Meters(3.0)`. Unlike an implicit produce site, the cast is written, so it applies
    // even where the base would not flow in on its own.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && typeKey(name).exists(constrainedDecls.contains) =>
      constrainedCast(typeKey(name).get, args)

    case Call(Ident(name), args) if lookupOpt(name).isEmpty && variantKey(name).isDefined =>
      constructVariant(variantKey(name).get, args, expected)

    case Call(Ident(name), args) if lookupOpt(name).isEmpty && typeKey(name).exists(structDecls.contains) =>
      constructStruct(typeKey(name).get, args, expected)

    // A simple enum's name in call position is a checked cast from an integer — `Color(n)` traps
    // on a value that is not a declared discriminant. Told from a data enum, which has no integer
    // to reinterpret, and from a struct constructor, which line 479 already claimed.
    case Call(Ident(name), args) if lookupOpt(name).isEmpty && typeKey(name).exists(enumDecls.contains) =>
      enumFromInt(typeKey(name).get, args)

    case Call(Ident(name), args) if funcKey(name).isDefined =>
      callFunction(funcDecls(funcKey(name).get), args, expected)

    case Call(Ident(name), _) =>
      err(s"undefined function '$name'")

    // A member reached through the module it belongs to (`13 §3`): the chain is rewritten with the
    // module folded into the name it qualifies, and what is left is the ordinary form — a call, a
    // construction, an associated function — resolved exactly as one written unqualified is.
    case Call(callee, args) if throughModule(callee).isDefined =>
      analyzeValueAt(Call(throughModule(callee).get, args).setPos(expr.pos), expected)

    // Reached through the enum name: `Color.try(n)` is the fallible constructor; otherwise a
    // data-carrying variant `Shape.Circle(5)`, the qualified form of the bare `Circle(5)`, or an
    // associated function the enum declares, which resolves exactly as a struct's does.
    case Call(Field(Ident(written), mname), args)
        if lookupOpt(written).isEmpty && typeKey(written).exists(enumDecls.contains) =>
      val tname = typeKey(written).get

      if mname == "try" then enumTry(tname, args)
      else if enumDecls(tname).variants.exists(_.name == mname) then
        constructVariant(Modules.qualify(Modules.moduleOf(tname), mname), args, expected)
      else if memberDecls.contains((tname, mname)) then callAssociated(tname, mname, args, expected)
      else err(s"enum '${qn(tname)}' has no variant or associated function '$mname'")

    // `Type.name(…)` — an associated function, told from the positional constructor `Type(…)` by
    // the member selected from the type name rather than the bare name applied.
    case Call(Field(Ident(written), mname), args)
        if lookupOpt(written).isEmpty && typeKey(written).exists(structDecls.contains) =>
      callAssociated(typeKey(written).get, mname, args, expected)

    case Call(Field(recv, mname), args) =>
      callMethod(recv, mname, args, expected)

    case Call(_, _) =>
      err("the thing being called must be a name")

    case f: Field if throughModule(f).isDefined =>
      analyzeValueAt(throughModule(f).get, expected)

    case Field(Ident(written), f) if lookupOpt(written).isEmpty && typeKey(written).exists(enumDecls.contains) =>
      val n = typeKey(written).get

      if enumDecls(n).variants.exists(_.name == f) then
        constructVariant(Modules.qualify(Modules.moduleOf(n), f), Nil, expected)
      else
        memberDecls.get((n, f)) match
          case Some(m) if m.isProperty =>
            err(s"'$f' is a property of '${qn(n)}' — read it on a value, as 'value.$f'")
          case Some(m) if m.receiver.isDefined =>
            err(s"'$f' is a method of '${qn(n)}' — call it on a value, as 'value.$f(…)'")
          case Some(_) => err(s"'$f' is an associated function of '${qn(n)}' — call it with '$written.$f(…)'")
          case None    => err(s"enum '${qn(n)}' has no variant '$f'")

    // A struct name is not a value, so a member selected from it is one of the three that could
    // have been meant rather than a field read — which is what the name would otherwise be reported
    // as, in an undefined-name message naming the type instead of the member.
    case Field(Ident(written), f) if lookupOpt(written).isEmpty && typeKey(written).exists(structDecls.contains) =>
      val n = typeKey(written).get

      memberDecls.get((n, f)) match
        case Some(m) if m.isProperty =>
          err(s"'$f' is a property of '${qn(n)}' — read it on a value, as 'value.$f'")
        case Some(m) if m.receiver.isDefined =>
          err(s"'$f' is a method of '${qn(n)}' — call it on a value, as 'value.$f(…)'")
        case Some(_) => err(s"'$f' is an associated function of '${qn(n)}' — call it with '$written.$f(…)'")
        case None    => err(s"type '${qn(n)}' has no member '$f' — and '${qn(n)}' is a type, not a value")

    case Field(receiver, f) =>
      val tr = autoDeref(analyzeExpr(receiver))
      tr.ty match
        // A trait object has no fields: the layout is exactly what it forgot. What it still has is
        // whatever the trait declares, and a property is declared to be read exactly like this.
        case _ if Type.erased(tr.ty) =>
          readTraitObjectProperty(tr, Type.erasedTrait(tr.ty).get, f)

        case s: Type.Struct =>
          val idx = s.fieldIndex(f)
          if idx >= 0 then TField(tr, idx, s.fields(idx)._2)
          else readProperty(tr, s, f)

        // An enum has no fields to shadow a member, so every name read off one is a property.
        case e: Type.Enum => readProperty(tr, e, f)

        // A bound promises behaviour, and a property is behaviour spelled like a field — so this is
        // a bound's to license after all, and it is checked at the definition like every other use
        // of a parameter. What no bound reaches is a real *field*: that is layout, which is `10 §5`'s
        // rule and the complaint left when nothing declares a property of the name.
        case a: Type.Abstract => readBoundProperty(a, tr, f)
        // `len` and `bytes` are the first compiler-provided members: `len` a property on every
        // array, slice, and string, and `bytes` the reinterpretation of a string's three words
        // as a `[]u8`, dropping only the validity guarantee.
        case _: Type.Array | _: Type.View if f == "len" => TLen(tr)
        case Type.Str if f == "bytes"                   => TBytes(tr)

        // Any other type reaches its own members too, since an `impl` may be written for one and a
        // trait may ask for a property. A name none of them supplies is the older complaint, which
        // is the better one there: nothing about `x.foo` on an `int` says a property was meant.
        case other if hasMember(other, f) => readProperty(tr, other, f)
        case other                        => err(s"cannot read field '$f' of ${show(other)}")

    case ArrayLit(elems) =>
      val elemExp = expected.flatMap(elementWanted)
      val ts      = elems.map(analyzeExpr(_, elemExp))

      for t <- ts do
        if Type.noValue(t.ty) then err(s"an array cannot hold ${show(t.ty)} values")
        if t.ty != ts.head.ty then
          err(s"an array literal needs one element type, got ${show(ts.head.ty)} and ${show(t.ty)}")

      val elemTy = ts.headOption.map(_.ty).orElse(elemExp).getOrElse(
        err("an empty array literal takes its element type from its context, and there is none here"),
      )

      expected match
        case Some(Type.Slice(_)) => TBufLit(ts, Type.Slice(elemTy))
        case _                   => TArrayLit(ts, Type.Array(ts.length, elemTy))

    // `[v; n]` — the form for an array whose element type has no zero, or has one that is not the
    // wanted starting value. The value is evaluated **once** and copied into every element, which
    // is what makes `[f(); 8]` mean one call rather than eight.
    //
    // What is being asked for decides which of the two things this is (`07 §Storage sized while
    // running`). Under a `[N]T` the count is part of the type and so a compile-time constant; under
    // a `[]T` the length is not in the type at all, so the count is an ordinary expression and the
    // elements are storage of their own that the view owns.
    case ArrayFill(value, count) =>
      val elemExp = expected.flatMap(elementWanted)
      val tv      = analyzeExpr(value, elemExp)

      if Type.noValue(tv.ty) then err(s"an array cannot hold ${show(tv.ty)} values")

      expected match
        case Some(Type.Slice(_)) =>
          val tc = analyzeExpr(count)

          // A count is an index's twin, so it takes a transparent subtype for the same reason one
          // does — and refuses a derived one for the same reason too.
          if !Type.repr(tc.ty).isInstanceOf[Type.Integer] then
            err(s"a repeat count is a number of elements, and ${show(tc.ty)} is not an integer")

          TBufFill(tv, tc, Type.Slice(tv.ty))

        case _ =>
          val n = constInt(count) match
            case Some(v) if v >= 0 && v.isValidInt => v.toInt
            case Some(v)                           => err(s"an array cannot have $v elements")
            case None =>
              err("an array's repeat count must be a constant, since it is the array's bound — a " +
                "literal, or a 'const' naming one. A count computed while running makes storage " +
                "instead, which is written where a '[]T' is expected")

          TArrayFill(tv, Type.Array(n, tv.ty))

    // A range subscript takes a view. The receiver is left *undereferenced* on purpose: for a
    // heap array the reference is both where the elements are and what keeps them alive, and
    // evaluating it once is what makes those the same object.
    case Index(receiver, RangeExpr(lo, hi, inclusive)) =>
      if !inclusive && hi.isEmpty then err("an open-ended slice is written 'a[lo..]'")

      val tr = analyzeExpr(receiver)

      // A `[]T` permits writes and records nothing about where its elements came from, so a view of
      // a `val` would be a way of writing one. Refused outright rather than allowed and policed,
      // since the view outlives the expression that made it: what this wants is a read-only slice
      // type, and that is a decision about `07`'s view types rather than about `val`.
      if readOnly(tr) then
        err("a 'val' cannot be sliced: a slice permits writes and does not record whose elements it " +
          "views, so the view would be a way of writing one")

      val elem = tr.ty match
        case Type.Ref(Type.Array(_, e), false) => e
        case Type.Ref(Type.Array(_, _), true) =>
          err("a slice does not record whether its owner's count is atomic, so a '&sync' array cannot be sliced")
        case w: Type.View               => w.elem
        case Type.Array(_, e)           => e
        case Type.Ptr(Type.Array(_, e)) => e
        case other                      => err(s"cannot slice ${show(other)}")

      // Part of a string is a string, not a `[]u8` — the bytes between two character boundaries
      // are still well-formed UTF-8, which is what the check at those boundaries is for.
      val viewTy = if tr.ty == Type.Str then Type.Str else Type.Slice(elem)

      TSlice(tr, lo.map(bound), hi.map(bound), inclusive, viewTy)

    case Index(receiver, index) =>
      val tr   = autoDeref(analyzeExpr(receiver))
      val elem = Type.element(tr.ty).getOrElse(err(s"cannot index ${show(tr.ty)}"))
      val ti   = analyzeExpr(index, Some(Type.Usize))

      // A transparent constrained subtype stands where its base does, so an `Index within 0..<n`
      // indexes without a cast. A derived one does not: `new` is nominal, and reaching the base is
      // exactly what a written conversion is for.
      Type.repr(ti.ty) match
        case _: Type.Integer => TIndex(tr, ti, elem)
        case other           => err(s"an index must be an integer, not ${show(other)}")

    // An `if` whose own value is unused hands that down: each branch is a block in statement
    // position, so neither is asked what it yields and the two have nothing to disagree about.
    case IfExpr(cond, thenBody, elseOpt) =>
      val tc    = analyzeBool(cond)
      val tThen = analyzeValueBlock(thenBody, expected, discarded)
      val tElse = elseOpt.map(analyzeValueBlock(_, expected, discarded))
      // The branches meet at one type, and a branch that does not finish takes the other's. A
      // branch used only for its effect is a different thing: one `unit` branch makes the whole
      // `if` a statement, whose value is nobody's, exactly as a missing `else` does.
      val ty = tElse match
        case Some(eb) =>
          join(tThen.ty, eb.ty).getOrElse {
            if eb.ty == Type.Unit || tThen.ty == Type.Unit then Type.Unit
            else err(s"if branches have different types: ${show(tThen.ty)} and ${show(eb.ty)}")
          }
        case None => Type.Unit
      TIf(tc, tThen, tElse, ty)

    case MatchExpr(scrut, arms) =>
      val ts    = analyzeExpr(scrut)
      val tarms = arms.map(analyzeArm(ts.ty, _, expected, discarded))
      TMatch(ts, tarms, matchResultType(ts.ty, tarms))

    // A loop's `else` is a block like any other, so a loop in statement position discards it too.
    // Without that, Python's own idiom — walk, `break` on a hit, set a flag in the `else` when
    // nothing hit — would be refused for a disagreement between the flag and a bare `break`.
    case While(label, cond, body, elseOpt) =>
      val tc            = analyzeBool(cond)
      val (tbody, ctx)  = analyzeLoopBody(expected, label)(analyzeStmts(body))
      val telse         = elseOpt.map(analyzeValueBlock(_, expected, discarded))
      TWhile(tc, tbody, telse, loopResultType(ctx, telse))

    case Loop(label, body) =>
      val (tbody, ctx) = analyzeLoopBody(expected, label)(analyzeStmts(body))
      TLoop(tbody, endlessResultType(ctx))

    // The init's binding belongs to the loop and to nothing outside it, so the scope opens before
    // the condition — which reads that binding — and closes after the `else`, which may too.
    case CFor(label, init, cond, step, body, elseOpt) =>
      pushScope()
      val tinit        = init.map(recoverStmt)
      val tcond        = cond.map(analyzeBool)
      val (tbody, ctx) = analyzeLoopBody(expected, label)(body.map(recoverStmt))
      val tstep        = step.map(recoverStmt)
      val telse        = elseOpt.map(analyzeValueBlock(_, expected, discarded))
      popScope()
      // With no condition the loop cannot finish on its own, so its type is what its `break`s
      // carry, exactly as `loop`'s is — and an `else` that can never run is a mistake worth saying.
      if tcond.isEmpty && telse.isDefined then
        err("this 'for' has no condition, so it never finishes on its own and its 'else' cannot run")
      TCFor(tinit, tcond, tstep, tbody, telse,
            if tcond.isEmpty then endlessResultType(ctx) else loopResultType(ctx, telse))

    case For(label, name, iter, body, elseOpt) =>
      iter match
        case RangeExpr(Some(lo), Some(hi), inclusive) =>
          val List(tlo, thi) = analyzeOperands(List(lo, hi), None)
          if tlo.ty != thi.ty then
            err(s"a 'for' range needs matching bounds, got ${show(tlo.ty)} and ${show(thi.ty)}")
          val vty = tlo.ty match
            case i: Type.Integer => i
            case other           => err(s"a 'for' range iterates integer bounds, not ${show(other)}")
          pushScope()
          val u            = declare(name, vty)
          val (tb, ctx)    = analyzeLoopBody(expected, label)(body.map(recoverStmt))
          popScope()
          val telse        = elseOpt.map(analyzeValueBlock(_, expected, discarded))
          TFor(u, vty, tlo, thi, inclusive, tb, telse, loopResultType(ctx, telse))

        case _ =>
          val seq = autoDeref(analyzeExpr(iter))
          val elem = seq.ty match
            case Type.Array(_, e) => e
            case Type.Slice(e)    => e
            // A string has two granularities and no reason to prefer one silently, so which one
            // is wanted is written: `s.bytes` today, `s.chars` when there are characters.
            case Type.Str =>
              err("a string is iterated as 's.bytes', since a string has bytes and characters both")
            case other =>
              err(s"'for' iterates an integer range, an array, or a slice, not ${show(other)}")
          pushScope()
          val u         = declare(name, elem)
          val (tb, ctx) = analyzeLoopBody(expected, label)(body.map(recoverStmt))
          popScope()
          val telse     = elseOpt.map(analyzeValueBlock(_, expected, discarded))
          TForEach(u, elem, seq, tb, telse, loopResultType(ctx, telse))

    case TryExpr(e) =>
      analyzeTry(analyzeExpr(e))

    case _: RangeExpr =>
      err("a range is only allowed in a 'for' loop or a 'match' pattern")

    case _: Tuple => err("tuples are not supported yet")

  /** `value.name` where `name` is not a field: a computed property, which reads with no
   * parentheses and so is spelled exactly as a field is, with an implicit by-value receiver.
   *
   * The receiver may be of **any** type, because every type has an owner key its members are filed
   * under and a trait may declare a property for an `impl` to supply — so `21.twice` reads one
   * exactly as `p.twice` does, through the member the implementation was lowered to.
   *
   * The absent-member wording is the one difference between the kinds: a struct's `x` could have
   * been either a field or a property, while an enum and a built-in have no fields to have meant.
   */
  private def readProperty(tr: TExpr, ty: Type, f: String): TExpr = {
    val (base, _) = memberKey(ty, f)

    memberDecls.get((base, f)) match
      case Some(m) if m.isProperty =>
        val fname      = memberFuncName(ty, f)
        val (_, rtype) = funcInsts(fname)
        funcsUsed += fname
        TCall(fname, List(tr), rtype)
      case Some(_) => err(s"'$f' is a method of '${show(ty)}' — call it with '$f(…)'")
      case None =>
        ty match
          case _: Type.Struct => err(s"'${show(ty)}' has no field or property '$f'")
          case _              => err(s"'${show(ty)}' has no property '$f'")
  }

  /** One end of a slice range: an index like any other, so any integer will do. */
  private def bound(e: Expr): TExpr = {
    val t = analyzeExpr(e, Some(Type.Usize))

    t.ty match
      case _: Type.Integer => t
      case other           => err(s"a slice bound must be an integer, not ${show(other)}")
  }

  private def incDec(op: String, target: Expr, pre: Boolean): TExpr = {
    val place = analyzePlace(target, s"'$op'")

    place.ty match
      case i: Type.Integer => TIncDec(place, op, pre, i)
      case other           => err(s"'$op' is not defined for ${show(other)}")
  }

  // --- places --------------------------------------------------------------------------

  /** Whether a typed expression denotes a **place** — something with an address, which can be
   * assigned through and pointed at. A local, a dereference, and a field of either are places;
   * anything computed (a call result, an arithmetic result, a freshly built struct) is not.
   */
  protected def isPlace(t: TExpr): Boolean = t match
    case _: TLoad           => true
    case _: TGlobal         => true
    case _: TDeref          => true
    case TField(recv, _, _) => isPlace(recv)
    // A slice's elements live wherever its owner keeps them, so they have an address even when
    // the slice itself is a temporary. An array's elements are the array, so they do not.
    case TIndex(recv, _, _) =>
      recv.ty match
        case _: Type.Slice => true
        case Type.Str      => false
        case _             => isPlace(recv)
    case _ => false

  /** Analyzes something that must be a place — an assignment target or the operand of `&`. */
  protected def analyzePlace(target: Expr, what: String): TExpr = {
    val t = analyzeExpr(target)

    t match
      // A string is immutable, and it is worth saying so rather than reporting the absence of an
      // address: writing one byte of UTF-8 is how a string stops being UTF-8.
      case TIndex(recv, _, _) if recv.ty == Type.Str =>
        err("a string is immutable, so its bytes have no address to write through")
      case _ =>
        if !isPlace(t) then err(s"$what needs a variable, a field, or a dereference — something with an address")
        // A `val` has an address, which is the whole difference between it and a `const` — what it
        // does not have is a writable one. `&` is refused along with assignment because a `*T` is a
        // licence to write, and handing one out would make the promise unkeepable one step away
        // from where it was written.
        if readOnly(t) then err(s"a 'val' is written once, so $what has nothing to write through")

    t
  }

  /** Whether a place bottoms out in something bound by a `val` — either a module-level one or a
   * local. Reaching *into* one keeps the property: an element of a read-only array is read-only,
   * and so is a field of a read-only struct.
   */
  protected def readOnly(t: TExpr): Boolean = t match
    case _: TGlobal         => true
    case TLoad(name, _)     => readOnlyLocals(name)
    case TField(recv, _, _) => readOnly(recv)
    // Only where the elements are the receiver's own storage. A slice's are somebody else's, and
    // whose they are is exactly what a slice does not record.
    case TIndex(recv, _, _) =>
      recv.ty match
        case _: Type.View => false
        case _            => readOnly(recv)
    case _ => false

  /** One level of automatic dereference, so a field is selected through a `*T` or a `&T`
   * exactly as it is on the value itself. One level only: reaching through a `**T` is written.
   */
  protected def autoDeref(t: TExpr): TExpr =
    Type.pointee(t.ty) match
      case Some(inner) => TDeref(t, inner)
      case None        => t

  /** How a diagnostic names an assignment target. */
  private def describe(target: Expr): String = target match
    case Ident(n)      => s"'$n'"
    case Field(_, f)   => s"field '$f'"
    case Unary("*", _) => "the place it points at"
    case Index(_, _)   => "this element"
    case _             => "this place"
}

object Analyzer {

  /** Analyzes a program to a typed tree, or returns every error it found, rendered and in source
   * order.
   *
   * The walk itself never stops at the first mistake — each declaration, function body, and
   * statement is a recovery region — so what comes back on the left is the whole list. An error
   * escaping the regions entirely is still caught here, since a diagnostic that reaches the user
   * beats a stack trace.
   */
  def analyze(program: Program): Either[String, TProgram] = analyze(List(program))

  /** Analyzes the files of one module together. They share a single scope, so a declaration in one
   * is visible to all of them with no ordering and no forward declaration (`13 §6`) — which falls
   * out of hoisting, since the pass that registers every signature already runs over the whole set
   * before any body is checked.
   */
  def analyze(units: List[Program]): Either[String, TProgram] = {
    val analyzer = new Analyzer(units)

    val outcome =
      try Right(analyzer.analyze())
      catch
        case AnalyzerError(msg, pos) => Left(List(Diagnostic.render(msg, pos)))
        // A poisoned region carries no message of its own: it means an error was already
        // recorded, and those are what the caller is told about.
        case Poisoned() => Left(Nil)

    val found = analyzer.errors

    outcome match
      case Right(tree) if found.isEmpty => Right(tree)
      case Right(_)                     => Left(Diagnostic.report(found))
      case Left(escaped) =>
        val all = found ::: escaped

        // Reaching here with nothing to say would mean the analyzer gave up without recording
        // why, which is a bug in the analyzer rather than in the program it was handed.
        if all.isEmpty then Left(Diagnostic.render("the analyzer stopped without reporting why", None))
        else Left(Diagnostic.report(all))
  }
}
