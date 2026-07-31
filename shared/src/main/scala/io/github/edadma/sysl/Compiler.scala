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
    compiled(sources, target).map(_._1)

  /** The same compilation, starting from trees that are **already parsed**.
   *
   * This is the seam a library artifact enters through (`AstCodec`): a decoded tree is the tree the
   * parser would have produced, so everything from here on is unchanged and no pass has to know
   * whether a declaration was read from source or from an artifact.
   */
  def compileTrees(units: List[Program], target: Target = Target.default): Either[String, String] =
    analyzed(units, target).map(_._1)

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
    compiledWith(sources, libraries, target).map(_._1)

  /** The same compilation against a library, keeping the notes the driver may want to show. This is
   * the one the CLI takes, so that a program linked against a library reports its heap promotions
   * exactly as one compiled alone does.
   */
  def compiledWith(sources: List[Source], libraries: List[Program], target: Target = Target.default,
                   precompiled: Set[String] = Set.empty, core: Core = Core.embedded)
      : Either[String, (String, List[String])] = {
    val parsed = sources.map(SyslParser.parse)

    parsed.collect { case Left(e) => e } match
      case Nil  => analyzed(libraries ::: parsed.collect { case Right(p) => p }, target, precompiled, core)
      case errs => Left(errs.mkString("\n"))
  }

  /** The same compilation, keeping the notes the driver may want to show — currently the heap
   * promotions, for `--explain-escapes` (`05`). Separate from `compile` so that the ordinary path
   * has nothing extra to ignore.
   */
  def compiled(sources: List[Source], target: Target = Target.default)
      : Either[String, (String, List[String])] = {
    val parsed = sources.map(SyslParser.parse)

    // Every file is parsed before any is rejected, so a syntax error in one does not hide the
    // syntax errors in the rest — the same reason the analyzer reports every mistake it finds.
    parsed.collect { case Left(e) => e } match
      case Nil  => analyzed(parsed.collect { case Right(p) => p }, target)
      case errs => Left(errs.mkString("\n"))
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
                     core: Core = Core.embedded): Either[String, (String, Set[String])] =
    for
      typed    <- Analyzer.analyze(units, building, core)
      promoted <- Escape.check(typed)
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
      val determined =
        own.filter(f => Reachability.reachedFrom(List(f), typed.funcs, typed.vtables).vals.isEmpty)

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
  private def analyzed(units: List[Program], target: Target, precompiled: Set[String] = Set.empty,
                       core: Core = Core.embedded): Either[String, (String, List[String])] =
    for
      typed    <- Analyzer.analyze(units, core = core)
      promoted <- Escape.check(typed)
    yield
      // Pruning still runs, and still from `main`: a library function this program never calls is
      // dropped from the tree exactly as before. What `precompiled` changes is only what happens to
      // the ones it *does* call — declared rather than defined, with the body coming from the
      // library's object file at link time.
      val pruned = Reachability.prune(typed)

      (Codegen.generate(pruned.copy(precompiled = precompiled), promoted, target), promoted.explanations)
}
