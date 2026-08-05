package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `require`/`ensure` clauses parse to `Require`/`Ensure` statements at the head of a function
 * body, each carrying its condition expression and an optional trap message. `result` is an
 * ordinary `Ident` at this stage — only the analyzer gives it meaning inside an `ensure`.
 */
class ContractParserTests extends AnyFreeSpec with ParseSupport {

  private def fnBody(src: String): List[Stmt] =
    prog(src).collectFirst { case f: FuncDecl => f.body }.getOrElse(fail("expected a function declaration"))

  private def cmp(op: String, l: Expr, r: Expr): Expr = Compare(List(l, r), List(op))

  "require" - {
    "with a message keeps both the condition and the message" in {
      fnBody(
        """f(x: int) -> int
          |    require x >= 0, "x must be non-negative"
          |    x""".stripMargin
      ).head shouldBe Require(cmp(">=", Ident("x"), i(0)), Some("x must be non-negative"))
    }

    "without a message leaves the message empty" in {
      fnBody(
        """f(x: int) -> int
          |    require x > 0
          |    x""".stripMargin
      ).head shouldBe Require(cmp(">", Ident("x"), i(0)), None)
    }
  }

  "ensure" - {
    "carries the postcondition, with `result` as a bare identifier" in {
      fnBody(
        """f(x: int) -> int
          |    ensure result >= x
          |    x""".stripMargin
      ).head shouldBe Ensure(cmp(">=", Ident("result"), Ident("x")), None)
    }

    "with a message keeps it" in {
      fnBody(
        """f() -> int
          |    ensure result != 0, "never zero"
          |    1""".stripMargin
      ).head shouldBe Ensure(cmp("!=", Ident("result"), i(0)), Some("never zero"))
    }
  }

  // Multiple clauses of both kinds are preserved in source order ahead of the body, so codegen
  // can check them entry-first and exit-in-order.
  "several clauses stay in order ahead of the body" in {
    fnBody(
      """f(x: int, hi: int) -> int
        |    require hi >= 0
        |    ensure result >= 0
        |    ensure result <= hi
        |    x""".stripMargin
    ) shouldBe List(
      Require(cmp(">=", Ident("hi"), i(0)), None),
      Ensure(cmp(">=", Ident("result"), i(0)), None),
      Ensure(cmp("<=", Ident("result"), Ident("hi")), None),
      ExprStmt(Ident("x")),
    )
  }
}
