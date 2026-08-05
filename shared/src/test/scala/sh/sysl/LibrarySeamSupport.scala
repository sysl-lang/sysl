package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** What the two suites about the shipped library both ask: which declarations a source text carries
 * of its own, and what a compilation that was refused said.
 *
 * `LibraryTests` pins the seam itself — whose declaration a key names, what the standard module
 * offers, and which names the compiler spells for itself. `LibraryMoveTests` holds each declaration
 * that has moved into that module to it. Both need to compile something and read the refusal, so
 * that lives here rather than in either.
 */
trait LibrarySeamSupport extends AnyFreeSpec with Matchers with RunSupport {

  /** A program's own declarations, to be told from the library's. */
  protected def parsed(src: String): List[Stmt] =
    SyslParser.parse(Source("<input>", src)) match
      case Right(p) => p.body
      case Left(e)  => fail(e)

  protected def refused(program: String): String =
    Compiler.compileToLlvm(program) match
      case Left(err) => err
      case Right(_)  => fail("the program compiled, and should not have")

  protected def refusedOf(sources: Source*): String =
    Compiler.compile(sources.toList) match
      case Left(err) => err
      case Right(_)  => fail("the program compiled, and should not have")
}
