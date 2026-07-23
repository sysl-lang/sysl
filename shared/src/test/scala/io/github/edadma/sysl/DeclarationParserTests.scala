package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of the top-level declaration forms — functions, structs — plus `for` and
 * `return`, and the disambiguation between a keyword-less function declaration and a call.
 */
class DeclarationParserTests extends AnyFreeSpec with ParseSupport {

  "functions" - {
    "an expression-bodied function" in {
      prog("add(a: int, b: int) -> int = a + b") shouldBe List(
        FuncDecl(
          "add",
          Nil,
          List(Param("a", NamedType("int")), Param("b", NamedType("int"))),
          Some(NamedType("int")),
          List(ExprStmt(Binary("+", Ident("a"), Ident("b")))),
        )
      )
    }

    "a block-bodied function with an implicit return" in {
      prog("f(n: int) -> int\n    n + 1") shouldBe List(
        FuncDecl(
          "f",
          Nil,
          List(Param("n", NamedType("int"))),
          Some(NamedType("int")),
          List(ExprStmt(Binary("+", Ident("n"), i(1)))),
        )
      )
    }

    "a function with no return type takes unit" in {
      prog("greet(name: string)\n    print(name)") shouldBe List(
        FuncDecl(
          "greet",
          Nil,
          List(Param("name", NamedType("string"))),
          None,
          List(printStmt(Ident("name"))),
        )
      )
    }

    "a bare call is not a declaration" in {
      prog("foo(1)") shouldBe List(ExprStmt(Call(Ident("foo"), List(i(1)))))
    }
  }

  "structs" - {
    "a struct with two fields" in {
      prog("struct Point\n    x: int\n    y: int") shouldBe List(
        StructDecl("Point", Nil, List(Param("x", NamedType("int")), Param("y", NamedType("int"))))
      )
    }
  }

  "for and return" - {
    "a for over an inclusive range" in {
      prog("for i in 1..10\n    print(i)") shouldBe List(
        For("i", RangeExpr(Some(i(1)), Some(i(10)), inclusive = true), List(printStmt(Ident("i"))))
      )
    }

    "a bare return and a return with a value" in {
      prog("return\nreturn 42") shouldBe List(Return(None), Return(Some(i(42))))
    }
  }
}
