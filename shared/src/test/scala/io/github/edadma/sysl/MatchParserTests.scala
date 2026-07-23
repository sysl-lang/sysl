package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of `match` expressions: literal, `|`-alternative, range, wildcard, and `else`
 * arms, plus guards. `match` is an expression, so in statement position it is an `ExprStmt`.
 */
class MatchParserTests extends AnyFreeSpec with ParseSupport {

  private def matchExpr(src: String): MatchExpr =
    prog(src) match
      case List(ExprStmt(m: MatchExpr)) => m
      case other                        => fail(s"expected a single match statement, got $other")

  "a literal, alternative, range, and else arm" in {
    val src =
      """match n
        |    0 -> print(1)
        |    1 | 2 -> print(2)
        |    3..5 -> print(3)
        |    else -> print(4)""".stripMargin

    matchExpr(src) shouldBe MatchExpr(
      Ident("n"),
      List(
        MatchArm(List(LitPattern(i(0))), None, List(printStmt(i(1)))),
        MatchArm(List(LitPattern(i(1)), LitPattern(i(2))), None, List(printStmt(i(2)))),
        MatchArm(List(RangePattern(i(3), i(5), inclusive = true)), None, List(printStmt(i(3)))),
        MatchArm(List(WildcardPattern), None, List(printStmt(i(4)))),
      ),
    )
  }

  "a guarded wildcard arm" in {
    val src =
      """match x
        |    _ if x > 0 -> print(1)
        |    else -> print(2)""".stripMargin

    matchExpr(src) shouldBe MatchExpr(
      Ident("x"),
      List(
        MatchArm(List(WildcardPattern), Some(Compare(List(Ident("x"), i(0)), List(">"))), List(printStmt(i(1)))),
        MatchArm(List(WildcardPattern), None, List(printStmt(i(2)))),
      ),
    )
  }
}
