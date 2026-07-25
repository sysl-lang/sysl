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

  // `...` is the C ellipsis, and the parser's whole job with it is to tell the tail apart from a
  // parameter — everything else about a variadic declaration is an ordinary header.
  "variadic" - {
    "a trailing ellipsis is recorded, and the named parameters keep their types" in {
      prog("extern printf(fmt: *u8, ...) -> int") shouldBe
        List(
          ExternDecl(
            "printf",
            List(Param("fmt", PtrType(NamedType("u8")))),
            Some(NamedType("int")),
            variadic = true,
          ),
        )
    }

    "several named parameters may precede it" in {
      prog("extern snprintf(buf: *u8, n: usize, fmt: *u8, ...) -> int") shouldBe
        List(
          ExternDecl(
            "snprintf",
            List(
              Param("buf", PtrType(NamedType("u8"))),
              Param("n", NamedType("usize")),
              Param("fmt", PtrType(NamedType("u8"))),
            ),
            Some(NamedType("int")),
            variadic = true,
          ),
        )
    }

    "the same declaration without one is not variadic" in {
      prog("extern printf(fmt: *u8) -> int") shouldBe
        List(ExternDecl("printf", List(Param("fmt", PtrType(NamedType("u8")))), Some(NamedType("int"))))
    }

    // `...` and `..` share a prefix, so the lexer has to prefer the longer one — and go on
    // preferring the shorter one where that is what was written.
    "the ellipsis does not swallow a range, nor a range the ellipsis" in {
      prog("extern f(n: int, ...)") shouldBe
        List(ExternDecl("f", List(Param("n", NamedType("int"))), None, variadic = true))
      prog("for i in 1..3\n    print(i)") shouldBe
        List(forStmt("i", RangeExpr(Some(i(1)), Some(i(3)), inclusive = true), List(printStmt(Ident("i")))))
    }

    // Rejected by the analyzer rather than the grammar, so that the message can say what is
    // missing instead of pointing at a token the parser did not expect.
    "an ellipsis with nothing before it parses, to be diagnosed later" in {
      prog("extern f(...) -> int") shouldBe List(ExternDecl("f", Nil, Some(NamedType("int")), variadic = true))
    }

    "a result is optional here as everywhere" in {
      prog("extern log(fmt: *u8, ...)") shouldBe
        List(ExternDecl("log", List(Param("fmt", PtrType(NamedType("u8")))), None, variadic = true))
    }

    // The ellipsis is not the foreign seam's alone — a sysl function takes it too (`12 §9`), from
    // the same production, so the two cannot drift apart.
    "a sysl function takes the same ellipsis" in {
      prog("f(n: int, ...) -> int = n") shouldBe
        List(
          FuncDecl("f", Nil, List(Param("n", NamedType("int"))), Some(NamedType("int")),
                   List(ExprStmt(Ident("n"))), Map.empty, variadic = true),
        )
    }
  }

  // A string before the name is the symbol the linker resolves, and the name after it is what the
  // program calls it by. A declaration otherwise begins with an identifier, so this needs no
  // keyword — and the two spellings are the same production apart from that string.
  "a link name" - {
    "is the symbol, and the identifier after it is the sysl name" in {
      prog("""extern "snprintf" fmt(buf: *u8, ...) -> int""") shouldBe
        List(
          ExternDecl(
            "fmt",
            List(Param("buf", PtrType(NamedType("u8")))),
            Some(NamedType("int")),
            variadic = true,
            link = Some("snprintf"),
          ),
        )
    }

    "is absent by default, and then the name is the symbol" in {
      prog("extern abort()") shouldBe List(ExternDecl("abort", Nil, None, link = None))
      ExternDecl("abort", Nil, None).symbol shouldBe "abort"
      ExternDecl("fmt", Nil, None, link = Some("snprintf")).symbol shouldBe "snprintf"
    }

    "may repeat the name it is already known by" in {
      prog("""extern "abort" abort()""") shouldBe List(ExternDecl("abort", Nil, None, link = Some("abort")))
    }

    // Only an `extern` reaches outside the program, so only an `extern` takes one.
    "is not something a sysl function may carry" in {
      progError("""extern "abort" abort() = 1""") should not be empty
    }
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
