package sh.sysl

import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers

/** Shared helpers for the parser test suites. The AST nodes are ordinary top-level case
 * classes, so a fresh parser per call is fine (unlike the lexer's path-dependent tokens).
 */
trait ParseSupport extends Matchers { this: Assertions =>

  /** Parses a single expression. */
  protected def expr(src: String): Expr = {
    val p = new SyslParser(Source("<expr>", src))
    val r = p.parseExpression

    assert(r.successful, r.toString)
    r.get
  }

  /** Parses a whole program to its statement list, failing the test on a parse error. */
  protected def prog(src: String): List[Stmt] =
    SyslParser.parse(src) match {
      case Right(p) => p.body
      case Left(e)  => fail(e)
    }

  /** The module a file declares, as written, or `None` where it declares no header. */
  protected def moduleOf(src: String): Option[String] =
    SyslParser.parse(src) match {
      case Right(p) => p.module.map(_.show)
      case Left(e)  => fail(e)
    }

  /** Asserts a program does *not* parse, returning the error message for further checks. */
  protected def progError(src: String): String =
    SyslParser.parse(src) match {
      case Right(p) => fail(s"expected a parse error, but it parsed as $p")
      case Left(e)  => e
    }

  /** A bare integer literal — the most common leaf in expected trees. */
  protected def i(n: Int): Expr = IntLit(BigInt(n), None)

  /** `print(args)` as a statement — the workhorse of control-flow test bodies. */
  protected def printStmt(args: Expr*): Stmt = ExprStmt(Call(Ident("print"), args.toList))

  /** A no-argument call `name()` as a statement. */
  protected def callStmt(name: String): Stmt = ExprStmt(Call(Ident(name), Nil))

  /** `if cond …` in statement position — an `IfExpr` wrapped in an `ExprStmt`. */
  protected def ifStmt(cond: Expr, thenBody: List[Stmt], elseBody: Option[List[Stmt]] = None): Stmt =
    ExprStmt(IfExpr(cond, thenBody, elseBody))

  /** `while cond …` in statement position — a `While` expression wrapped in an `ExprStmt`. */
  protected def whileStmt(
      cond: Expr,
      body: List[Stmt],
      elseBody: Option[List[Stmt]] = None,
      label: Option[String] = None,
  ): Stmt =
    ExprStmt(While(label, cond, body, elseBody))

  /** `loop …` in statement position — a `Loop` expression wrapped in an `ExprStmt`. */
  protected def loopStmt(body: List[Stmt], label: Option[String] = None): Stmt =
    ExprStmt(Loop(label, body))

  /** `for name in iter …` in statement position — a `For` expression wrapped in an `ExprStmt`. */
  protected def forStmt(
      name: String,
      iter: Expr,
      body: List[Stmt],
      elseBody: Option[List[Stmt]] = None,
      label: Option[String] = None,
  ): Stmt =
    ExprStmt(For(label, name, iter, body, elseBody))
}
