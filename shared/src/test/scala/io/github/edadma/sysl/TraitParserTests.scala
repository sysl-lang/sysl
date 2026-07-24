package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of trait declarations and `impl` blocks: a trait holds bodiless method signatures,
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
        "Point",
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

  "a trait method signature carrying a body is a parse error" in {
    val src =
      """trait Show
        |    show(self) -> string = "x"""".stripMargin

    progError(src)
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
