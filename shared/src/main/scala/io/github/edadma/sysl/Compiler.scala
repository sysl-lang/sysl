package io.github.edadma.sysl

/** The pure front-to-IR pipeline: a module's source to an LLVM IR module, or the first error.
 *
 * Everything here is platform-independent and cross-compiles to JVM, JS, and Native — the
 * parts that touch the filesystem and invoke a toolchain live in the JVM CLI.
 */
object Compiler {

  /** Compiles source text to an LLVM IR module, or to the first error as a rendered diagnostic.
   * `name` is what a diagnostic calls the source — a path from the driver, a placeholder from a
   * test — and it is carried by every position the front end records.
   */
  def compileToLlvm(source: String, name: String = "<input>"): Either[String, String] =
    compile(List(Source(name, source)))

  /** Compiles the files of one module. They are analyzed together and share one scope, so the order
   * they are handed over in decides nothing but which file a diagnostic is reported against first.
   */
  def compile(sources: List[Source]): Either[String, String] = {
    val parsed = sources.map(SyslParser.parse)

    // Every file is parsed before any is rejected, so a syntax error in one does not hide the
    // syntax errors in the rest — the same reason the analyzer reports every mistake it finds.
    parsed.collect { case Left(e) => e } match
      case Nil  => analyzed(parsed.collect { case Right(p) => p })
      case errs => Left(errs.mkString("\n"))
  }

  private def analyzed(units: List[Program]): Either[String, String] =
    for
      typed   <- Analyzer.analyze(units)
      checked <- Escape.check(typed).toLeft(typed)
    yield Codegen.generate(checked)
}
