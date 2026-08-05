package sh.sysl

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

    // A file is a script *and* a set of declarations, so the two forms sit beside each other and
    // the same name appears in both roles. What tells them apart is the block, and nothing else: no
    // keyword, no forward declaration, and no rule about which comes first.
    "the same name is a call above and a declaration below, told apart by the block" in {
      prog("start()\n\nstart()\n    print(1)") shouldBe List(
        callStmt("start"),
        FuncDecl("start", Nil, Nil, None, List(printStmt(i(1)))),
      )
    }

    "a declaration below the script takes its parameters as any other does" in {
      prog("insert(db: int, name: string)\n    print(name)") shouldBe List(
        FuncDecl(
          "insert",
          Nil,
          List(Param("db", NamedType("int")), Param("name", NamedType("string"))),
          None,
          List(printStmt(Ident("name"))),
        )
      )
    }

    // An expression body ends at its line, so what follows is at the top level again — including
    // the comments, which are not part of either declaration and must not attach the second to the
    // first.
    "an expression-bodied declaration, a comment, and then a block-bodied one" in {
      prog("f(n: int) -> int = n + 1\n\n// a note\n\ng(n: int) -> int\n    n * 2") shouldBe List(
        FuncDecl("f", Nil, List(Param("n", NamedType("int"))), Some(NamedType("int")),
          List(ExprStmt(Binary("+", Ident("n"), i(1))))),
        FuncDecl("g", Nil, List(Param("n", NamedType("int"))), Some(NamedType("int")),
          List(ExprStmt(Binary("*", Ident("n"), i(2))))),
      )
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
        forStmt("i", RangeExpr(Some(i(1)), Some(i(10)), inclusive = true), List(printStmt(Ident("i"))))
      )
    }

    "a bare return and a return with a value" in {
      prog("return\nreturn 42") shouldBe List(Return(None), Return(Some(i(42))))
    }
  }
}
