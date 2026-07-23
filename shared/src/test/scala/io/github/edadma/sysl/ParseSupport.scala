package io.github.edadma.sysl

import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers

/** Shared helpers for the parser test suites. The AST nodes are ordinary top-level case
 * classes, so a fresh parser per call is fine (unlike the lexer's path-dependent tokens).
 */
trait ParseSupport extends Matchers { this: Assertions =>

  /** Parses a single expression. */
  protected def expr(src: String): Expr = {
    val p = new SyslParser
    val r = p.parseExpression(src)

    assert(r.successful, r.toString)
    r.get
  }

  /** Parses a whole program to its statement list, failing the test on a parse error. */
  protected def prog(src: String): List[Stmt] =
    SyslParser.parse(src) match {
      case Right(p) => p.body
      case Left(e)  => fail(e)
    }

  /** A bare integer literal — the most common leaf in expected trees. */
  protected def i(n: Int): Expr = IntLit(BigInt(n), None)

  /** `print(args)` as a statement — the workhorse of control-flow test bodies. */
  protected def printStmt(args: Expr*): Stmt = ExprStmt(Call(Ident("print"), args.toList))

  /** A no-argument call `name()` as a statement. */
  protected def callStmt(name: String): Stmt = ExprStmt(Call(Ident(name), Nil))
}
