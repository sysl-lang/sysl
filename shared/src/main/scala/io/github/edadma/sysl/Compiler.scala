package io.github.edadma.sysl

/** The pure front-to-IR pipeline: a program's source to an LLVM IR module, or the first error.
 *
 * Everything here is platform-independent and cross-compiles to JVM, JS, and Native — the
 * parts that touch the filesystem and invoke a toolchain live in the JVM CLI.
 *
 * A compilation is **for** a target (`targets.md`), which is a parameter and not an ambient fact:
 * the machine the compiler is running on reaches this only as the default `Target.default` picks,
 * and a caller that names one is building for it whether or not it could run the result.
 */
/** What one compilation produced: the module, whatever the driver may want to tell the user about
 * it, and what the result has to be linked against.
 *
 * `links` is here rather than inside the IR because it is not a property of the code — it is a
 * property of the *build*, and the only pass that can answer it is the one holding every unit that
 * went in, the standard module and any decoded library included. A driver that had the IR alone
 * could not work it out by reading it: an `extern` says which symbol it wants and never which
 * library has it, which is the whole reason `15 §8` exists.
 */
case class Compiled(ir: String, notes: List[String], links: List[String])

object Compiler {

  /** Compiles source text to an LLVM IR module, or to the first error as a rendered diagnostic.
   * `name` is what a diagnostic calls the source — a path from the driver, a placeholder from a
   * test — and it is carried by every position the front end records.
   */
  def compileToLlvm(source: String, name: String = "<input>", target: Target = Target.default)
      : Either[String, String] =
    compile(List(Source(name, source)), target)

  /** Compiles the files of one program, however many modules they make up. Each file says which
   * module it contributes to, the files of one module share a single scope (`13 §1`), and a module
   * reaches another's members by naming them in full (`13 §3`) — so the order the files are handed
   * over in decides nothing but which one a diagnostic is reported against first.
   */
  def compile(sources: List[Source], target: Target = Target.default): Either[String, String] =
    compiled(sources, target).map(_.ir)

  /** The same compilation, starting from trees that are **already parsed**.
   *
   * This is the seam a library artifact enters through (`AstCodec`): a decoded tree is the tree the
   * parser would have produced, so everything from here on is unchanged and no pass has to know
   * whether a declaration was read from source or from an artifact.
   */
  def compileTrees(units: List[Program], target: Target = Target.default): Either[String, String] =
    analyzed(units, target, Set.empty, Stdlib.fromSource(target)).map(_._1)

  /** Compiles a program **against a library**: the library's modules are compiled alongside it, and
   * the program reaches them by the ordinary module rules (`13 §3`) — a full path, or an `import`.
   *
   * The library arrives as trees rather than as source because that is what it will be: an
   * `AstCodec` artifact, decoded. Nothing downstream distinguishes the two, which is the property
   * worth having — a library is not a second kind of input, it is more modules.
   *
   * A library declares no statements of its own; the one file that carries a program's statements
   * (`13 §7`) is still the program's, and a library that carried any would be reported by the same
   * rule that reports two of them.
   */
  def compileWith(sources: List[Source], libraries: List[Program],
                  target: Target = Target.default): Either[String, String] =
    compiledWith(sources, libraries, target).map(_.ir)

  /** The same compilation against a library, keeping the notes the driver may want to show. This is
   * the one the CLI takes, so that a program linked against a library reports its heap promotions
   * exactly as one compiled alone does.
   */
  def compiledWith(sources: List[Source], libraries: List[Program], target: Target = Target.default,
                   precompiled: Set[String] = Set.empty, std: Option[Stdlib] = None,
                   provides: Set[String] = Capability.core.toSet)
      : Either[String, Compiled] = {
    val parsed = sources.map(SyslParser.parse(_, target))

    parsed.collect { case Left(e) => e } match
      case Nil =>
        analyzed(libraries ::: parsed.collect { case Right(p) => p }, target, precompiled,
          carried(std, target), provides)
      case errs => Left(errs.mkString("\n"))
  }

  /** The same compilation stopped at the **typed tree**, which is what `sysl prove` reads (`17 §9`).
   *
   * It stops before pruning and before lowering, and both matter. A function nothing calls is still
   * one the program declared and still one somebody may want proved; and a `@ghost` declaration is
   * dropped from the emitted module by design (`17 §8`), so a proof run reading the lowered tree
   * would have lost exactly the predicates the specification is written in.
   */
  def typedWith(sources: List[Source], libraries: List[Program], target: Target = Target.default,
                std: Option[Stdlib] = None, provides: Set[String] = Capability.core.toSet)
      : Either[String, (TProgram, Set[String])] = {
    val parsed = sources.map(SyslParser.parse(_, target))

    parsed.collect { case Left(e) => e } match
      case Nil =>
        val own = parsed.collect { case Right(p) => p }

        // **Which modules are the program's own travels back beside the tree**, because nothing in
        // the tree says. `TProgram.mainModule` names the file that carries the statements, and a
        // module of pure declarations carries none — so a proof run over a library-shaped file would
        // have found nothing to translate. The sources given are what the reader meant by "this
        // module", and they are only known here.
        Analyzer.analyze(libraries ::: own, std = carried(std, target), target = target,
                         provides = provides).map((_, own.map(moduleOf).toSet))
      case errs => Left(errs.mkString("\n"))
  }

  /** The standard module a compilation was handed, or the copy the compiler carries **for the target
   * it is building for**.
   *
   * Spelled as an option rather than as a defaulted parameter because the fallback depends on
   * `target`, and a default argument cannot read another parameter of its own list. Which is the
   * honest shape anyway: "none was given" and "this particular one was" are different facts, and
   * only the first has an answer that varies with the machine.
   */
  private def carried(std: Option[Stdlib], target: Target): Stdlib = std.getOrElse(Stdlib.fromSource(target))

  /** The same compilation, keeping the notes the driver may want to show — currently the heap
   * promotions, for `--explain-escapes` (`05`). Separate from `compile` so that the ordinary path
   * has nothing extra to ignore.
   */
  def compiled(sources: List[Source], target: Target = Target.default)
      : Either[String, Compiled] = {
    val parsed = sources.map(SyslParser.parse(_, target))

    // Every file is parsed before any is rejected, so a syntax error in one does not hide the
    // syntax errors in the rest — the same reason the analyzer reports every mistake it finds.
    parsed.collect { case Left(e) => e } match
      case Nil  => analyzed(parsed.collect { case Right(p) => p }, target, Set.empty, Stdlib.fromSource(target))
      case errs => Left(errs.mkString("\n"))
  }

  /** The same compilation as a **test build**: the IR whose entry point dispatches to one `@test`
   * function by name, and the tests it can be asked for (`testing.md`).
   *
   * This is the one compilation that keeps the tests and drops the program — `Tests.only` says why —
   * and the tests come back beside the IR because the runner needs both: the binary to execute and
   * the list to execute it for. Working the list out twice, once here and once by reading the tree
   * again, is how the two come to disagree about what a test is called.
   */
  def compileTests(sources: List[Source], libraries: List[Program], target: Target = Target.default,
                   precompiled: Set[String] = Set.empty, std: Option[Stdlib] = None)
      : Either[String, (Compiled, List[TTest])] = {
    val parsed = sources.map(SyslParser.parse(_, target))

    parsed.collect { case Left(e) => e } match
      case errs if errs.nonEmpty => Left(errs.mkString("\n"))
      case _ =>
        val units = libraries ::: parsed.collect { case Right(p) => p }
        val whole = carried(std, target)

        for
          typed    <- Analyzer.analyze(units, std = whole, target = target)
          promoted <- Escape.check(typed)
          _        <- TailCalls.check(typed)
        yield
          val kept = Tests.only(typed)

          // A test binary is linked like any other, so it needs the same libraries. It is a
          // different compilation from the one above rather than a variant of it, which is why the
          // collection is repeated here instead of shared: what the two keep differs, and only what
          // they link is the same.
          (Compiled(Codegen.generate(kept.copy(precompiled = precompiled), promoted, target),
                    promoted.explanations,
                    LinkDirectives.required(units ::: whole.units)),
           kept.tests)
  }

  /** A **library** lowered on its own: the IR for everything in it that could be compiled ahead of
   * time, and the names of those functions.
   *
   * The split needs no analysis of its own, which is the pleasant part. A generic declaration is
   * kept untyped and monomorphized on demand, so analyzing a library alone yields typed functions
   * for exactly the declarations that were already determined — a generic nothing called is simply
   * not here. Those are what an object file can hold; the generics travel as trees and are compiled
   * in whatever program fixes their type arguments.
   *
   * Nothing is pruned. A program is lowered from `main` outwards because anything it cannot reach is
   * dead, but a library has no `main` and every public declaration is a potential entry — so all of
   * them are emitted, and it is the *linker* that discards what a given program never calls.
   *
   * **A library defines its own declarations and nobody else's.** The compilation is handed the
   * shipped library too — a library that prints reaches `printi` and `putbytes` exactly as a program
   * does — and emitting *those* would put a copy of the printing surface in every artifact, so two
   * libraries that both print could not be linked into one program. They are declared here and
   * defined in the consuming program, which compiles the shipped library anyway and reaches them
   * through the very body that called for them.
   *
   * Which is which is read off the **module**, and that is exact rather than approximate: a library's
   * declarations are keyed under the module its directory names, and everything the compiler supplied
   * — including a generic of the shipped library's monomorphized for this one's sake — is keyed
   * somewhere else. It is exact only because a library may not sit in the anonymous root module,
   * which `LibraryArtifact.build` is what refuses.
   *
   * `building` names the shipped library's own modules where *this* is the compilation producing
   * them, so that the compiler does not supply the files it is being asked to compile.
   */
  def compileLibrary(units: List[Program], target: Target = Target.default, building: Set[String] = Set.empty,
                     std: Option[Stdlib] = None): Either[String, (String, Set[String])] =
    for
      analysed <- Analyzer.analyze(units, building, carried(std, target), target)
      // A library ships no tests. They are the library author's, they run against the sources rather
      // than against the artifact, and emitting them would put a function nothing can call into every
      // program that links it — with the helpers only it reaches dragged in behind.
      typed    = Tests.strip(analysed)
      promoted <- Escape.check(typed)
      _        <- TailCalls.check(typed)
    yield
      val mine            = units.map(moduleOf).toSet
      val (own, supplied) = typed.funcs.partition(f => mine(Modules.moduleOf(f.name)))
      val ir =
        Codegen.generate(
          typed.copy(entryPoint = false, precompiled = supplied.map(_.name).toSet), promoted, target)

      // A function that reads a module-level `val` is left out of the precompiled half, and this is
      // the honest boundary of what separate compilation reaches today: the storage for a `val` is
      // initialized by the entry point, and a library has none. Such a function is compiled in the
      // program instead, where the initialization it depends on actually happens. Everything else —
      // which is most of a library — is compiled once, here.
      //
      // **What is advertised is what the linker can reach**, so a file-private declaration is left
      // out however ordinary it looks here. Its symbol is emitted `internal` (`13 §2`), which is a
      // promise that every caller is in this module — and a program told the artifact holds it would
      // declare it, call it, and find nothing at the link. Left out, the program compiles a copy of
      // its own from the tree the artifact carries, which is what it does with a generic.
      val determined =
        own.filter(f => !f.internal)
          .filter(f => Reachability.reachedFrom(List(f), typed.funcs, typed.vtables).vals.isEmpty)

      (ir, determined.map(_.name).toSet)

  /** The module a file contributes to, as its header says. The directory has to agree, and the
   * analyzer is what holds it to that; before anything is analyzed the header is what there is.
   */
  private[sysl] def moduleOf(u: Program): String = u.module.map(_.show).getOrElse(Modules.root)

  /** Every pass that reads a whole typed program runs before anything is dropped from it: a
   * declaration nothing can reach is still one the program declared, and checking it is what makes a
   * mistake in it a mistake at all. Pruning is therefore the last thing that happens to the tree, and
   * the only thing between the checks and the lowering.
   */
  private def analyzed(units: List[Program], target: Target, precompiled: Set[String],
                       std: Stdlib, provides: Set[String] = Capability.core.toSet)
      : Either[String, Compiled] =
    for
      typed    <- Analyzer.analyze(units, std = std, target = target, provides = provides)
      promoted <- Escape.check(typed)
      _        <- TailCalls.check(typed)
    yield
      // Pruning still runs, and still from `main`: a library function this program never calls is
      // dropped from the tree exactly as before. What `precompiled` changes is only what happens to
      // the ones it *does* call — declared rather than defined, with the body coming from the
      // library's object file at link time.
      //
      // The tests go first, and they go **after** the analysis above rather than instead of it: a
      // `@test` that does not compile is an error in a build that would never have run it, which is
      // what makes it safe to leave one beside the code it tests (`Tests`).
      val pruned = Reachability.prune(Tests.strip(typed))

      // The std's units are asked as well as the program's, and this is what makes an artifact's
      // directives mean anything: the standard module arrives as `Stdlib` rather than in `units`, so a
      // collection that read only the latter would drop every directive the library ships with.
      Compiled(Codegen.generate(pruned.copy(precompiled = precompiled), promoted, target),
               promoted.explanations,
               LinkDirectives.required(units ::: std.units))
}
