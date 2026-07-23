package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of `enum` declarations — simple (dataless, optionally valued) and data-carrying
 * variants — and of the variant / binding patterns that destructure them in a `match`.
 */
class EnumParserTests extends AnyFreeSpec with ParseSupport {

  private def parseError(src: String): String =
    SyslParser.parse(src) match
      case Left(e)  => e
      case Right(p) => fail(s"expected a parse error, got $p")

  private def arms(src: String): List[MatchArm] =
    prog(src) match
      case List(ExprStmt(m: MatchExpr)) => m.arms
      case other                        => fail(s"expected a single match statement, got $other")

  "declarations" - {
    "a simple enum with auto and explicit values" in {
      prog("enum Color\n    Red\n    Green\n    Blue = 10\n    Yellow") shouldBe List(
        EnumDecl(
          "Color",
          List(
            EnumVariantDecl("Red", None, Nil),
            EnumVariantDecl("Green", None, Nil),
            EnumVariantDecl("Blue", Some(i(10)), Nil),
            EnumVariantDecl("Yellow", None, Nil),
          ),
        )
      )
    }

    "a data enum with payload and nullary variants" in {
      prog("enum Shape\n    Circle(radius: int)\n    Rect(w: int, h: int)\n    Empty") shouldBe List(
        EnumDecl(
          "Shape",
          List(
            EnumVariantDecl("Circle", None, List(Param("radius", NamedType("int")))),
            EnumVariantDecl("Rect", None, List(Param("w", NamedType("int")), Param("h", NamedType("int")))),
            EnumVariantDecl("Empty", None, Nil),
          ),
        )
      )
    }
  }

  "patterns" - {
    "a variant pattern binds its fields" in {
      arms("match s\n    Circle(r) -> print(r)\n    Empty -> print(0)").map(_.patterns) shouldBe List(
        List(VariantPattern("Circle", List(IdentPattern("r")))),
        List(IdentPattern("Empty")),
      )
    }

    "nested variant patterns and a bare binding" in {
      arms("match o\n    Wrap(Val(v)) -> print(v)\n    other -> print(0)").map(_.patterns) shouldBe List(
        List(VariantPattern("Wrap", List(VariantPattern("Val", List(IdentPattern("v")))))),
        List(IdentPattern("other")),
      )
    }
  }

  "end markers" - {
    "a matching end name closes a struct" in {
      prog("struct Point\n    x: int\nend Point") shouldBe List(
        StructDecl("Point", List(Param("x", NamedType("int"))))
      )
    }

    "a matching end name closes an enum and a function" in {
      val src = "enum E\n    A\n    B\nend E\nf() -> int\n    1\nend f"
      prog(src) shouldBe List(
        EnumDecl("E", List(EnumVariantDecl("A", None, Nil), EnumVariantDecl("B", None, Nil))),
        FuncDecl("f", Nil, Some(NamedType("int")), List(ExprStmt(i(1)))),
      )
    }

    "a mismatched end name is a parse error naming both" in {
      parseError("struct Point\n    x: int\nend Nope") should include("'end Nope' does not match 'Point'")
    }
  }
}
