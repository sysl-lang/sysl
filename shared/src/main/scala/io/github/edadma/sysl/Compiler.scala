package io.github.edadma.sysl

/** The pure front-to-IR pipeline: source text to an LLVM IR module, or the first error.
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
    for
      tree    <- SyslParser.parse(Source(name, source))
      typed   <- Analyzer.analyze(tree)
      checked <- Escape.check(typed).toLeft(typed)
    yield Codegen.generate(checked)
}
