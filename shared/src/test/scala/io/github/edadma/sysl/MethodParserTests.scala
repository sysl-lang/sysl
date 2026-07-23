package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of a type's inherent members: instance methods with each receiver shorthand,
 * computed properties, and associated functions, intermixed with fields in a struct body.
 */
class MethodParserTests extends AnyFreeSpec with ParseSupport {

  "a value-receiver method" in {
    val src =
      """struct Point
        |    x: int
        |    sum(self) -> int = self.x""".stripMargin

    prog(src) shouldBe List(
      StructDecl(
        "Point",
        Nil,
        List(Param("x", NamedType("int"))),
        List(
          MethodDecl(
            "sum",
            Some(RecvMode.ByValue),
            isProperty = false,
            Nil,
            Nil,
            Some(NamedType("int")),
            List(ExprStmt(Field(Ident("self"), "x"))),
          )
        ),
      )
    )
  }

  "a pointer-receiver method with parameters" in {
    val src =
      """struct P
        |    x: int
        |    shift(*self, dx: int)
        |        self.x += dx""".stripMargin

    prog(src) shouldBe List(
      StructDecl(
        "P",
        Nil,
        List(Param("x", NamedType("int"))),
        List(
          MethodDecl(
            "shift",
            Some(RecvMode.ByPtr),
            isProperty = false,
            Nil,
            List(Param("dx", NamedType("int"))),
            None,
            List(ExprStmt(Assign("+=", Field(Ident("self"), "x"), Ident("dx")))),
          )
        ),
      )
    )
  }

  "a reference receiver, plain and sync" in {
    val src =
      """struct C
        |    n: int
        |    a(&self) = self.n
        |    b(&sync self) = self.n""".stripMargin

    prog(src) shouldBe List(
      StructDecl(
        "C",
        Nil,
        List(Param("n", NamedType("int"))),
        List(
          MethodDecl("a", Some(RecvMode.ByRef(sync = false)), isProperty = false, Nil, Nil, None,
            List(ExprStmt(Field(Ident("self"), "n")))),
          MethodDecl("b", Some(RecvMode.ByRef(sync = true)), isProperty = false, Nil, Nil, None,
            List(ExprStmt(Field(Ident("self"), "n")))),
        ),
      )
    )
  }

  "a computed property" in {
    val src =
      """struct P
        |    x: int
        |    doubled -> int = self.x * 2""".stripMargin

    prog(src) shouldBe List(
      StructDecl(
        "P",
        Nil,
        List(Param("x", NamedType("int"))),
        List(
          MethodDecl(
            "doubled",
            None,
            isProperty = true,
            Nil,
            Nil,
            Some(NamedType("int")),
            List(ExprStmt(Binary("*", Field(Ident("self"), "x"), i(2)))),
          )
        ),
      )
    )
  }

  "an associated function has no receiver" in {
    val src =
      """struct P
        |    x: int
        |    origin() -> P = P(0)""".stripMargin

    prog(src) shouldBe List(
      StructDecl(
        "P",
        Nil,
        List(Param("x", NamedType("int"))),
        List(
          MethodDecl(
            "origin",
            None,
            isProperty = false,
            Nil,
            Nil,
            Some(NamedType("P")),
            List(ExprStmt(Call(Ident("P"), List(i(0))))),
          )
        ),
      )
    )
  }

  "fields and members intermix, and a field still parses after a member" in {
    val src =
      """struct P
        |    x: int
        |    sum(self) -> int = self.x
        |    y: int""".stripMargin

    prog(src) shouldBe List(
      StructDecl(
        "P",
        Nil,
        List(Param("x", NamedType("int")), Param("y", NamedType("int"))),
        List(
          MethodDecl("sum", Some(RecvMode.ByValue), isProperty = false, Nil, Nil, Some(NamedType("int")),
            List(ExprStmt(Field(Ident("self"), "x")))),
        ),
      )
    )
  }
}
