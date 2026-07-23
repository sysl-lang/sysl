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
    val m = matchExpr("match n\n    0 -> print(1)\n    1 | 2 -> print(2)\n    3..5 -> print(3)\n    else -> print(4)")

    m.scrutinee shouldBe Ident("n")
    m.arms.map(_.patterns) shouldBe List(
      List(LitPattern(i(0))),
      List(LitPattern(i(1)), LitPattern(i(2))),
      List(RangePattern(i(3), i(5), inclusive = true)),
      List(WildcardPattern),
    )
  }

  "a guarded wildcard arm" in {
    val m = matchExpr("match x\n    _ if x > 0 -> print(1)\n    else -> print(2)")

    m.arms.head.patterns shouldBe List(WildcardPattern)
    m.arms.head.guard shouldBe Some(Compare(List(Ident("x"), i(0)), List(">")))
  }
}
