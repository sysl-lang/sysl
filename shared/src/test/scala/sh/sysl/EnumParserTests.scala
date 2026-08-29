package sh.sysl

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
      arms("s match\n    Circle(r) -> print(r)\n    Empty -> print(0)").map(_.patterns) shouldBe List(
        List(VariantPattern("Circle", List(IdentPattern("r")))),
        List(IdentPattern("Empty")),
      )
    }

    "nested variant patterns and a bare binding" in {
      arms("o match\n    Wrap(Val(v)) -> print(v)\n    other -> print(0)").map(_.patterns) shouldBe List(
        List(VariantPattern("Wrap", List(VariantPattern("Val", List(IdentPattern("v")))))),
        List(IdentPattern("other")),
      )
    }
  }

  /** **A payload written positionally, which is what somebody porting an enum writes first.**
    *
    * Rust, Swift, OCaml and Haskell all take one, and a data enum is exactly the construct most
    * likely to be coming from one of them. The old refusal was `')' expected` with the caret on the
    * `[`, which reads as a complaint about the *type* -- a slice that may not go there, a bracket
    * needing something -- so the next moves are `Url([]u8)` and `Url(*u8)`, and both fail the same
    * way. Card `0367`, found twice in one file writing `sysl-lang/llhttp`.
    */
  "a positional payload" - {
    "is refused by naming the form that was wanted, and the line to write" in {
      val said = parseError("enum Event\n    Url([]const u8)\n")

      said should include("a variant's payload names its fields")
      said should include("'name: []const u8'")
    }

    // The type is read back out rather than recited, so the suggestion is the reader's own type.
    "and the suggestion carries whatever type was written" in {
      parseError("enum Event\n    At(*u8)\n") should include("'name: *u8'")
      parseError("enum Event\n    Buf([4]u8)\n") should include("'name: [4]u8'")
    }

    /** **Every shape of type gets the same message, and that is why the check sits above the member
      * parser rather than inside the variant one.**
      *
      * Measured before it was moved, the answer depended on the type and three of the four were bad:
      * `[]const u8` and `[4]u8` said `')' expected` with the caret on the `[`, which reads as a
      * complaint about the type; `int` and `Buf[int]` were read as far as a member header and said
      * `':' expected`, which is fine; and `*u8` and `&Node` said **`'self' expected`**, which is
      * worse than either, because it names the one word that would not help.
      */
    "whatever the type is written as" in {
      for t <- List("[]const u8", "[4]u8", "*u8", "&Node", "int", "(int, real)", "Buf[int]") do
        withClue(s"payload '$t': ")(
          parseError(s"enum Event\n    V($t)\n") should include(s"'name: $t'")
        )
    }

    /** **The named form is untouched, and this is the assertion that says the guard cannot misfire.**
      * `typeRef` reads `r` as a name and then finds a `:`, which is neither `,` nor `)`, so the
      * positional case declines and the field list runs as it always did.
      */
    /** **A receiver is a type to a type parser, and this is what stands aside for it.** `area(self)`
      * reads as a bare name followed by `)`, and `write(*self, …)` as a pointer to one — both of
      * which are exactly the shape being refused. `namesSelf` is the whole of the exception.
      */
    "but a member's receiver is not a payload" in {
      val src = "enum Shape\n    Circle(r: real)\n\n    area(self) -> real = 1.0\n"

      prog(src).headOption.getOrElse(fail("did not parse")) shouldBe a[EnumDecl]
    }

    "while a named payload, a bare variant and an explicit value are unaffected" in {
      prog("enum Shape\n    Circle(r: real)\n    Empty\n") shouldBe List(
        EnumDecl("Shape", Nil, None, List(
          EnumVariantDecl("Circle", None, List(Param("r", NamedType("real")))),
          EnumVariantDecl("Empty", None, Nil),
        ))
      )

      prog("enum Code\n    Ok = 0\n") shouldBe List(
        EnumDecl("Code", Nil, None, List(EnumVariantDecl("Ok", Some(i(0)), Nil)))
      )
    }

    // Empty parentheses are a variant with no fields and have always been legal, so the positional
    // case has to decline on them rather than read them as a malformed payload.
    "and empty parentheses are still a variant with no payload" in {
      prog("enum Event\n    Nothing()\n") shouldBe List(
        EnumDecl("Event", Nil, None, List(EnumVariantDecl("Nothing", None, Nil)))
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
