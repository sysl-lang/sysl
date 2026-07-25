package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The `extern` declaration and the `never` result type, as the parser sees them.
 *
 * An extern is a function header and nothing else, which is exactly what tells it from a function:
 * the absence of a body. `never` needs no lexical support at all — it is a predeclared type name
 * like `int`, so these tests are really about the shape of the declaration around it.
 */
class ExternParserTests extends AnyFreeSpec with ParseSupport {

  "a header with a result and one parameter" in {
    prog("extern exit(code: int) -> never") shouldBe
      List(ExternDecl("exit", List(Param("code", NamedType("int"))), Some(NamedType("never"))))
  }

  "no result at all means unit, as for a function" in {
    prog("extern abort()") shouldBe List(ExternDecl("abort", Nil, None))
  }

  "several parameters keep their order and their modes" in {
    prog("extern memcpy(dst: *u8, src: *u8, n: usize) -> *u8") shouldBe
      List(
        ExternDecl(
          "memcpy",
          List(
            Param("dst", PtrType(NamedType("u8"))),
            Param("src", PtrType(NamedType("u8"))),
            Param("n", NamedType("usize")),
          ),
          Some(PtrType(NamedType("u8"))),
        ),
      )
  }

  // The body is the whole difference between the two declaration forms, so an extern with one is
  // not an extern with something extra — it is not an extern at all.
  "a body is not allowed" in {
    progError("extern f() = 1") should not be empty
  }

  "an extern sits among other declarations" in {
    prog(
      """extern exit(code: int) -> never
        |f(n: int) -> int = n + 1
        |print(f(1))""".stripMargin,
    ) shouldBe
      List(
        ExternDecl("exit", List(Param("code", NamedType("int"))), Some(NamedType("never"))),
        FuncDecl(
          "f",
          Nil,
          List(Param("n", NamedType("int"))),
          Some(NamedType("int")),
          List(ExprStmt(Binary("+", Ident("n"), i(1)))),
        ),
        printStmt(Call(Ident("f"), List(i(1)))),
      )
  }

  "never is an ordinary result type on a function" in {
    prog("stop() -> never = exit(1)") shouldBe
      List(
        FuncDecl("stop", Nil, Nil, Some(NamedType("never")), List(ExprStmt(Call(Ident("exit"), List(i(1)))))),
      )
  }

  "never is an ordinary result type on a member" in {
    prog(
      """struct Fail
        |    code: int
        |    raise(self) -> never = exit(self.code)""".stripMargin,
    ) shouldBe
      List(
        StructDecl(
          "Fail",
          Nil,
          List(Param("code", NamedType("int"))),
          List(
            MethodDecl(
              "raise",
              Some(RecvMode.ByValue),
              isProperty = false,
              Nil,
              Nil,
              Some(NamedType("never")),
              List(ExprStmt(Call(Ident("exit"), List(Field(Ident("self"), "code"))))),
            ),
          ),
        ),
      )
  }
}
