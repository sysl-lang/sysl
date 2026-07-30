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

  /** Every pass that reads a whole typed program runs before anything is dropped from it: a
   * declaration nothing can reach is still one the program declared, and checking it is what makes a
   * mistake in it a mistake at all. Pruning is therefore the last thing that happens to the tree, and
   * the only thing between the checks and the lowering.
   */
  private def analyzed(units: List[Program], target: Target): Either[String, (String, List[String])] =
    for
      typed    <- Analyzer.analyze(units)
      promoted <- Escape.check(typed)
    yield (Codegen.generate(Reachability.prune(typed), promoted, target), promoted.explanations)
}
