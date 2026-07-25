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
          Nil,
          None,
          List(
            EnumVariantDecl("Red", None, Nil),
            EnumVariantDecl("Green", None, Nil),
            EnumVariantDecl("Blue", Some(i(10)), Nil),
            EnumVariantDecl("Yellow", None, Nil),
          ),
        )
      )
    }

    "a simple enum with a pinned underlying integer type" in {
      prog("enum Color: u8\n    Red\n    Green\n    Blue = 10") shouldBe List(
        EnumDecl(
          "Color",
          Nil,
          Some(NamedType("u8")),
          List(
            EnumVariantDecl("Red", None, Nil),
            EnumVariantDecl("Green", None, Nil),
            EnumVariantDecl("Blue", Some(i(10)), Nil),
          ),
        )
      )
    }

    "a data enum with payload and nullary variants" in {
      prog("enum Shape\n    Circle(radius: int)\n    Rect(w: int, h: int)\n    Empty") shouldBe List(
        EnumDecl(
          "Shape",
          Nil,
          None,
          List(
            EnumVariantDecl("Circle", None, List(Param("radius", NamedType("int")))),
            EnumVariantDecl("Rect", None, List(Param("w", NamedType("int")), Param("h", NamedType("int")))),
            EnumVariantDecl("Empty", None, Nil),
          ),
        )
      )
    }
  }

  "members" - {
    // A payload variant is a name followed by `name: type` bindings in parentheses — exactly the
    // shape of a method header — so what tells the two apart is the body a member must have.
    "a payload variant is not mistaken for a method header" in {
      val src =
        """enum Shape
          |    Circle(radius: int)
          |    sides(self) -> int = 1""".stripMargin

      prog(src) shouldBe List(
        EnumDecl(
          "Shape",
          Nil,
          None,
          List(EnumVariantDecl("Circle", None, List(Param("radius", NamedType("int"))))),
          List(
            MethodDecl("sides", Some(RecvMode.ByValue), isProperty = false, Nil, Nil,
              Some(NamedType("int")), List(ExprStmt(i(1)))),
          ),
        )
      )
    }

    "variants and members intermix, and a variant still parses after a member" in {
      val src =
        """enum Color
          |    Red
          |    code(self) -> int = 1
          |    Green = 7""".stripMargin

      prog(src) shouldBe List(
        EnumDecl(
          "Color",
          Nil,
          None,
          List(EnumVariantDecl("Red", None, Nil), EnumVariantDecl("Green", Some(i(7)), Nil)),
          List(
            MethodDecl("code", Some(RecvMode.ByValue), isProperty = false, Nil, Nil,
              Some(NamedType("int")), List(ExprStmt(i(1)))),
          ),
        )
      )
    }

    // Each receiver shorthand, a property, and an associated function all parse in an enum body
    // exactly as they do in a struct's, since it is the same `member` grammar.
    "every member kind parses in an enum body" in {
      val src =
        """enum E
          |    A
          |    m(self) -> int = 1
          |    p(*self) -> int = 2
          |    r(&self) -> int = 3
          |    doubled -> int = 4
          |    make() -> int = 5""".stripMargin

      prog(src) shouldBe List(
        EnumDecl(
          "E",
          Nil,
          None,
          List(EnumVariantDecl("A", None, Nil)),
          List(
            MethodDecl("m", Some(RecvMode.ByValue), isProperty = false, Nil, Nil, Some(NamedType("int")),
              List(ExprStmt(i(1)))),
            MethodDecl("p", Some(RecvMode.ByPtr), isProperty = false, Nil, Nil, Some(NamedType("int")),
              List(ExprStmt(i(2)))),
            MethodDecl("r", Some(RecvMode.ByRef(sync = false)), isProperty = false, Nil, Nil,
              Some(NamedType("int")), List(ExprStmt(i(3)))),
            MethodDecl("doubled", None, isProperty = true, Nil, Nil, Some(NamedType("int")),
              List(ExprStmt(i(4)))),
            MethodDecl("make", None, isProperty = false, Nil, Nil, Some(NamedType("int")),
              List(ExprStmt(i(5)))),
          ),
        )
      )
    }

    // Blank lines between a variant and a member are how a real enum body is laid out, so the
    // separator has to tolerate them the way a struct body's does.
    "blank lines separate variants from members" in {
      val src =
        """enum E
          |    A
          |    B
          |
          |    m(self) -> int = 1
          |end E""".stripMargin

      prog(src) shouldBe List(
        EnumDecl(
          "E",
          Nil,
          None,
          List(EnumVariantDecl("A", None, Nil), EnumVariantDecl("B", None, Nil)),
          List(
            MethodDecl("m", Some(RecvMode.ByValue), isProperty = false, Nil, Nil, Some(NamedType("int")),
              List(ExprStmt(i(1)))),
          ),
        )
      )
    }

    "a generic enum takes members and an underlying-typed one does too" in {
      val src =
        """enum Maybe[T]
          |    Just(value: T)
          |    tag(self) -> int = 1""".stripMargin

      prog(src) shouldBe List(
        EnumDecl(
          "Maybe",
          List("T"),
          None,
          List(EnumVariantDecl("Just", None, List(Param("value", NamedType("T"))))),
          List(
            MethodDecl("tag", Some(RecvMode.ByValue), isProperty = false, Nil, Nil, Some(NamedType("int")),
              List(ExprStmt(i(1)))),
          ),
        )
      )

      prog("enum C: u8\n    Red\n    code(self) -> int = 1") shouldBe List(
        EnumDecl(
          "C",
          Nil,
          Some(NamedType("u8")),
          List(EnumVariantDecl("Red", None, Nil)),
          List(
            MethodDecl("code", Some(RecvMode.ByValue), isProperty = false, Nil, Nil, Some(NamedType("int")),
              List(ExprStmt(i(1)))),
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
        StructDecl("Point", Nil, List(Param("x", NamedType("int"))))
      )
    }

    "a matching end name closes an enum and a function" in {
      val src = "enum E\n    A\n    B\nend E\nf() -> int\n    1\nend f"
      prog(src) shouldBe List(
        EnumDecl("E", Nil, None, List(EnumVariantDecl("A", None, Nil), EnumVariantDecl("B", None, Nil))),
        FuncDecl("f", Nil, Nil, Some(NamedType("int")), List(ExprStmt(i(1)))),
      )
    }

    "a mismatched end name is a parse error naming both" in {
      parseError("struct Point\n    x: int\nend Nope") should include("'end Nope' does not match 'Point'")
    }
  }
}
