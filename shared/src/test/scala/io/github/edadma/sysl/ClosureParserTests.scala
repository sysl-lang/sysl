package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of the closure surface (`12 §5`, `§5a`, `§6`): the arrow literal, the callable's type in
 * both its spellings, and what each of them may not be confused with.
 */
class ClosureParserTests extends AnyFreeSpec with ParseSupport {

  /** The one statement a closure's expression body is. */
  private def body(e: Expr): List[Stmt] = List(ExprStmt(e))

  /** A parameter with no annotation, which is the common case. */
  private def p(name: String): LambdaParam = LambdaParam(name, None)

  "the arrow literal" - {
    "one parameter drops its parentheses" in {
      expr("x -> x + 1") shouldBe Lambda(List(p("x")), body(Binary("+", Ident("x"), i(1))))
    }

    "two or more are parenthesized" in {
      expr("(x, y) -> x + y") shouldBe
        Lambda(List(p("x"), p("y")), body(Binary("+", Ident("x"), Ident("y"))))
    }

    "none is the empty pair" in {
      expr("() -> next_id()") shouldBe Lambda(Nil, body(Call(Ident("next_id"), Nil)))
    }

    "one parameter may still be written with them" in {
      expr("(x) -> x") shouldBe Lambda(List(p("x")), body(Ident("x")))
    }

    "the body extends as far right as an expression can" in {
      // The whole sum is the closure's, not the closure applied to `x` and then added to `1`.
      expr("x -> x * 2 + 1") shouldBe
        Lambda(List(p("x")), body(Binary("+", Binary("*", Ident("x"), i(2)), i(1))))
    }

    "a parameter may be annotated inside the parentheses" in {
      expr("(x: int) -> x") shouldBe
        Lambda(List(LambdaParam("x", Some(NamedType("int")))), body(Ident("x")))
    }

    "an annotation outside them is not a second spelling" in {
      // `x: int -> x` is not a closure at all; nothing in the grammar reads it, so what the caller
      // is told is that the line does not parse rather than that it parses as something else.
      an[Exception] should be thrownBy expr("x: int -> x")
    }

    "at a call site it is an ordinary argument" in {
      expr("xs.map(x -> x * 2)") shouldBe
        Call(Field(Ident("xs"), "map"), List(Lambda(List(p("x")), body(Binary("*", Ident("x"), i(2))))))
    }

    "two of them in one argument list stay apart" in {
      expr("fold(x -> x, y -> y)") shouldBe
        Call(Ident("fold"), List(Lambda(List(p("x")), body(Ident("x"))), Lambda(List(p("y")), body(Ident("y")))))
    }

    "a closure may yield a closure" in {
      expr("n -> x -> x + n") shouldBe
        Lambda(List(p("n")), body(Lambda(List(p("x")), body(Binary("+", Ident("x"), Ident("n"))))))
    }

    "an indented block is a body, and its trailing expression is the value" in {
      prog("""var f = x ->
             |    log(x)
             |    x + 1
             |""".stripMargin) shouldBe
        List(VarDecl("f", None, Some(Lambda(
          List(p("x")),
          List(ExprStmt(Call(Ident("log"), List(Ident("x")))), ExprStmt(Binary("+", Ident("x"), i(1)))),
        ))))
    }
  }

  "a block body reaches as far as the off-side rule does" - {
    "a closure bound to a name takes one" in {
      prog("""var f = x ->
             |    log(x)
             |    x
             |""".stripMargin).head shouldBe a[VarDecl]
    }

    "inside an argument list it does not, because a bracket suspends indentation" in {
      // `00 §9` — a bracket suspends the off-side rule until it closes, so the lexer emits no
      // indent inside one and there is no block for the body to be. The fix is a name to bind the
      // closure to, and the limit is recorded in `12 §5` rather than worked around here.
      progError("""xs.each(x ->
                  |    log(x)
                  |    print(x))
                  |""".stripMargin) should include("newline expected")
    }
  }

  "what the arrow does not take over" - {
    "a parenthesized list with no arrow after it is still a tuple" in {
      expr("(x, y)") shouldBe Tuple(List(Ident("x"), Ident("y")))
    }

    "a match arm's left side is a pattern, not a closure" in {
      // Both are written with `->`, and they never compete: an arm's left side is parsed by the
      // pattern grammar, so `n` here binds rather than becoming a parameter.
      prog("""x match
             |    n -> n * 2
             |""".stripMargin) shouldBe
        List(ExprStmt(MatchExpr(
          Ident("x"),
          List(MatchArm(List(IdentPattern("n")), None, body(Binary("*", Ident("n"), i(2))))),
        )))
    }

    "an arm's body may itself be a closure" in {
      prog("""x match
             |    0 -> y -> y
             |""".stripMargin) shouldBe
        List(ExprStmt(MatchExpr(
          Ident("x"),
          List(MatchArm(List(LitPattern(i(0))), None, body(Lambda(List(p("y")), body(Ident("y")))))),
        )))
    }

    "an assignment's right side is a closure and its left side is not" in {
      expr("f = x -> x") shouldBe Assign("=", Ident("f"), Lambda(List(p("x")), body(Ident("x"))))
    }
  }

  "the callable's type" - {
    /** The type written on a single parameter, which is where every spelling below is legal. */
    def paramType(src: String): TypeRef = prog(s"f(g: $src)\n    0\n") match {
      case List(FuncDecl(_, _, List(Param(_, t, _)), _, _, _, _, _, _)) => t
      case other                                                        => fail(s"unexpected $other")
    }

    "the bare arrow takes one domain" in {
      paramType("int -> int") shouldBe FnType(List(NamedType("int")), NamedType("int"), bare = true)
    }

    "several parameters are parenthesized" in {
      paramType("(int, string) -> bool") shouldBe
        FnType(List(NamedType("int"), NamedType("string")), NamedType("bool"), bare = true)
    }

    "none is the empty pair, here too" in {
      paramType("() -> int") shouldBe FnType(Nil, NamedType("int"), bare = true)
    }

    "'Fn' writes the same thing out" in {
      paramType("Fn(int) -> int") shouldBe FnType(List(NamedType("int")), NamedType("int"), bare = false)
      paramType("Fn() -> unit") shouldBe FnType(Nil, NamedType("unit"), bare = false)
    }

    "a mode sigil takes the whole callable, not just its first parameter" in {
      paramType("&Fn(int) -> int") shouldBe
        RefType(FnType(List(NamedType("int")), NamedType("int"), bare = false), sync = false)
    }

    "the arrow binds looser than a sigil on its left" in {
      // `*int -> int` is a callable *from* a pointer, which is what a reader of the line expects;
      // a pointer to a callable is `*Fn(int) -> int`, where the parentheses say where it starts.
      paramType("*int -> int") shouldBe FnType(List(PtrType(NamedType("int"))), NamedType("int"), bare = true)
      paramType("*Fn(int) -> int") shouldBe
        PtrType(FnType(List(NamedType("int")), NamedType("int"), bare = false))
    }

    "the arrow binds looser than a slice on its left" in {
      paramType("[]int -> int") shouldBe
        FnType(List(ArrayType(None, NamedType("int"))), NamedType("int"), bare = true)
    }

    "a callable may yield a callable" in {
      paramType("Fn(int) -> &Fn(int) -> int") shouldBe
        FnType(
          List(NamedType("int")),
          RefType(FnType(List(NamedType("int")), NamedType("int"), bare = false), sync = false),
          bare = false,
        )
    }

    "'Fn' stays a soft word, so a program may name a type of its own that" in {
      paramType("Fn") shouldBe NamedType("Fn")
      paramType("Fn[int]") shouldBe NamedType("Fn", List(NamedType("int")))
    }

    "a result list is still a result list beside one" in {
      prog("f(g: int -> int) -> int, int\n    0, 0\n") match {
        case List(FuncDecl(_, _, _, Some(TupleType(parts, true)), _, _, _, _, _)) =>
          parts shouldBe List(NamedType("int"), NamedType("int"))
        case other => fail(s"unexpected $other")
      }
    }
  }

  "a nested function parses exactly as a top-level one does" in {
    prog("""outer(n: int) -> int
           |    inner(k: int) -> int = k * 2
           |
           |    inner(n)
           |""".stripMargin) match {
      case List(FuncDecl("outer", _, _, _, stmts, _, _, _, _)) =>
        stmts.head shouldBe a[FuncDecl]
        stmts.head.asInstanceOf[FuncDecl].name shouldBe "inner"
      case other => fail(s"unexpected $other")
    }
  }
}
