package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of trait declarations and `impl` blocks: a trait holds method declarations, bodiless
 * when an implementation must supply them and carrying a body when the trait supplies a default,
 * and an `impl Trait for Type` holds ordinary method definitions in the same grammar a struct's
 * own body uses.
 */
class TraitParserTests extends AnyFreeSpec with ParseSupport {

  "a trait declares bodiless method signatures" in {
    val src =
      """trait Show
        |    show(self) -> string""".stripMargin

    prog(src) shouldBe List(
      TraitDecl(
        "Show",
        Nil,
        List(
          MethodDecl("show", Some(RecvMode.ByValue), isProperty = false, Nil, Nil, Some(NamedType("string")), Nil)
        ),
      )
    )
  }

  "a trait may declare several methods with parameters and receivers" in {
    val src =
      """trait Shape
        |    area(self) -> f64
        |    scale(*self, factor: f64)""".stripMargin

    prog(src) shouldBe List(
      TraitDecl(
        "Shape",
        Nil,
        List(
          MethodDecl("area", Some(RecvMode.ByValue), isProperty = false, Nil, Nil, Some(NamedType("f64")), Nil),
          MethodDecl(
            "scale",
            Some(RecvMode.ByPtr),
            isProperty = false,
            Nil,
            List(Param("factor", NamedType("f64"))),
            None,
            Nil,
          ),
        ),
      )
    )
  }

  "an impl block holds ordinary method definitions" in {
    val src =
      """impl Show for Point
        |    show(self) -> string = self.name""".stripMargin

    prog(src) shouldBe List(
      ImplDecl(
        "Show",
        NamedType("Point"),
        List(
          MethodDecl(
            "show",
            Some(RecvMode.ByValue),
            isProperty = false,
            Nil,
            Nil,
            Some(NamedType("string")),
            List(ExprStmt(Field(Ident("self"), "name"))),
          )
        ),
      )
    )
  }

  // The block's own type parameters go where a generic function's do — straight after the keyword
  // that opens the declaration — and carry bounds in the same spelling, which is what makes the
  // conformance conditional.
  "an impl may declare bounded type parameters of its own" in {
    val src =
      """impl[T: Show] Show for Box[T]
        |    show(self) -> string = self.v.show()""".stripMargin

    prog(src) shouldBe List(
      ImplDecl(
        "Show",
        NamedType("Box", List(NamedType("T"))),
        List(
          MethodDecl(
            "show",
            Some(RecvMode.ByValue),
            isProperty = false,
            Nil,
            Nil,
            Some(NamedType("string")),
            List(ExprStmt(Call(Field(Field(Ident("self"), "v"), "show"), Nil))),
          )
        ),
        List("T"),
        Map("T" -> List("Show")),
      )
    )
  }

  // A body after the header is what tells a default from a signature, and the two live side by
  // side in one trait — so the parse has to keep them apart on that alone.
  "a trait method carrying a body parses as a default" in {
    val src =
      """trait Show
        |    name(self) -> string
        |    show(self) -> string = self.name()""".stripMargin

    prog(src) shouldBe List(
      TraitDecl(
        "Show",
        Nil,
        List(
          MethodDecl("name", Some(RecvMode.ByValue), isProperty = false, Nil, Nil, Some(NamedType("string")), Nil),
          MethodDecl(
            "show",
            Some(RecvMode.ByValue),
            isProperty = false,
            Nil,
            Nil,
            Some(NamedType("string")),
            List(ExprStmt(Call(Field(Ident("self"), "name"), Nil))),
          ),
        ),
      )
    )
  }

  "a default may be an indented block rather than an '=' expression" in {
    val src =
      """trait Counter
        |    bump(*self)
        |        var n = 1
        |        n + 1""".stripMargin

    prog(src) shouldBe List(
      TraitDecl(
        "Counter",
        Nil,
        List(
          MethodDecl(
            "bump",
            Some(RecvMode.ByPtr),
            isProperty = false,
            Nil,
            Nil,
            None,
            List(VarDecl("n", None, Some(i(1))), ExprStmt(Binary("+", Ident("n"), i(1)))),
          )
        ),
      )
    )
  }

  // A property signature is the one member shape with neither a parameter list nor a body, so it is
  // told from a method by what does *not* follow the name.
  "a property signature in a trait parses as a member with no parameters" in {
    val src =
      """trait Sized
        |    size -> int""".stripMargin

    prog(src) shouldBe List(
      TraitDecl(
        "Sized",
        Nil,
        List(MethodDecl("size", None, isProperty = true, Nil, Nil, Some(NamedType("int")), Nil)),
      )
    )
  }

  "a generic function may bound a type parameter by a trait" in {
    prog("render[T: Show](x: T) -> string = x.show()") shouldBe List(
      FuncDecl(
        "render",
        List("T"),
        List(Param("x", NamedType("T"))),
        Some(NamedType("string")),
        List(ExprStmt(Call(Field(Ident("x"), "show"), Nil))),
        Map("T" -> List("Show")),
      )
    )
  }

  "bounds mix with unbounded parameters and join several traits with +" in {
    prog("mix[T, U: Ord + Hash](x: T, y: U) -> int = 1") shouldBe List(
      FuncDecl(
        "mix",
        List("T", "U"),
        List(Param("x", NamedType("T")), Param("y", NamedType("U"))),
        Some(NamedType("int")),
        List(ExprStmt(i(1))),
        Map("U" -> List("Ord", "Hash")),
      )
    )
  }
}
