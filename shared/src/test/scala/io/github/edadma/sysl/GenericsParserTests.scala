package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of the generic surface: type-parameter lists on declarations, and type arguments
 * applied to a named type.
 */
class GenericsParserTests extends AnyFreeSpec with ParseSupport {

  "type parameters" - {
    "a generic function" in {
      prog("id[T](x: T) -> T = x") shouldBe List(
        FuncDecl(
          "id",
          List("T"),
          List(Param("x", NamedType("T"))),
          Some(NamedType("T")),
          List(ExprStmt(Ident("x"))),
        )
      )
    }

    "a generic struct with two parameters" in {
      prog("struct Pair[A, B]\n    first: A\n    second: B\nend Pair") shouldBe List(
        StructDecl("Pair", List("A", "B"), List(Param("first", NamedType("A")), Param("second", NamedType("B"))))
      )
    }

    "a generic enum" in {
      prog("enum Maybe[T]\n    Just(value: T)\n    Nothing") shouldBe List(
        EnumDecl(
          "Maybe",
          List("T"),
          List(EnumVariantDecl("Just", None, List(Param("value", NamedType("T")))), EnumVariantDecl("Nothing", None, Nil)),
        )
      )
    }
  }

  "type arguments" - {
    "a type reference applied to one argument" in {
      prog("f(b: Box[int])\n    print(1)") shouldBe List(
        FuncDecl(
          "f",
          Nil,
          List(Param("b", NamedType("Box", List(NamedType("int"))))),
          None,
          List(printStmt(i(1))),
        )
      )
    }

    "nested type arguments" in {
      prog("var x: Result[Box[int], string] = y") shouldBe List(
        VarDecl(
          "x",
          Some(NamedType("Result", List(NamedType("Box", List(NamedType("int"))), NamedType("string")))),
          Ident("y"),
        )
      )
    }

    "indexing is still an expression, not a type application" in {
      prog("a[0]") shouldBe List(ExprStmt(Index(Ident("a"), i(0))))
    }
  }
}
