package io.github.edadma.sysl

/** The pure front-to-IR pipeline: source text to an LLVM IR module, or the first error.
 *
 * Everything here is platform-independent and cross-compiles to JVM, JS, and Native — the
 * parts that touch the filesystem and invoke a toolchain live in the JVM CLI.
 */
object Compiler {

  def compileToLlvm(source: String): Either[String, String] =
    for
      tree    <- SyslParser.parse(source)
      typed   <- Analyzer.analyze(tree)
      checked <- Escape.check(typed).toLeft(typed)
    yield Codegen.generate(checked)
}
