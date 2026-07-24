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

  "a positional struct pattern parses like a variant" in {
    val src =
      """match p
        |    Point(0, y) -> print(y)
        |    else -> print(0)""".stripMargin

    matchExpr(src) shouldBe MatchExpr(
      Ident("p"),
      List(
        MatchArm(List(VariantPattern("Point", List(LitPattern(i(0)), IdentPattern("y")))), None, List(printStmt(Ident("y")))),
        MatchArm(List(WildcardPattern), None, List(printStmt(i(0)))),
      ),
    )
  }

  "a named struct pattern carries field-name/sub-pattern pairs" in {
    val src =
      """match p
        |    Point{x: 0, y} -> print(y)
        |    else -> print(0)""".stripMargin

    matchExpr(src) shouldBe MatchExpr(
      Ident("p"),
      List(
        MatchArm(
          List(StructPattern("Point", List(("x", LitPattern(i(0))), ("y", IdentPattern("y"))))),
          None,
          List(printStmt(Ident("y"))),
        ),
        MatchArm(List(WildcardPattern), None, List(printStmt(i(0)))),
      ),
    )
  }

  "a named-field shorthand binds each field to its own name" in {
    val src =
      """match p
        |    Point{x, y} -> print(x)""".stripMargin

    matchExpr(src) shouldBe MatchExpr(
      Ident("p"),
      List(
        MatchArm(
          List(StructPattern("Point", List(("x", IdentPattern("x")), ("y", IdentPattern("y"))))),
          None,
          List(printStmt(Ident("x"))),
        ),
      ),
    )
  }
}
