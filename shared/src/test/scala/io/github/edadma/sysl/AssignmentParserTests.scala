package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of assignment and compound assignment: the operator set, the place forms on the left,
 * precedence and right-associativity, and — since the right side is a full expression — every
 * control-flow form that may sit there, the delimited positions that also accept one, and the bare
 * operator positions that deliberately still do not.
 */
class AssignmentParserTests extends AnyFreeSpec with ParseSupport {

  private def body(e: Expr): List[Stmt]            = List(ExprStmt(e))
  private def ifE(c: Expr, t: Expr, e: Expr): Expr = IfExpr(c, body(t), Some(body(e)))

  "the operator set" - {
    "every assignment operator parses under its own symbol" in {
      for op <- List("=", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=") do
        expr(s"a $op b") shouldBe Assign(op, Ident("a"), Ident("b"))
    }
  }

  "the left-hand side is a place" - {
    "a bare name" in {
      expr("a = b") shouldBe Assign("=", Ident("a"), Ident("b"))
    }
    "a field" in {
      expr("a.b = c") shouldBe Assign("=", Field(Ident("a"), "b"), Ident("c"))
    }
    "a nested field" in {
      expr("a.b.c = d") shouldBe Assign("=", Field(Field(Ident("a"), "b"), "c"), Ident("d"))
    }
    "an index" in {
      expr("a[i] = c") shouldBe Assign("=", Index(Ident("a"), Ident("i")), Ident("c"))
    }
    "a nested index" in {
      expr("a[i][j] = c") shouldBe
        Assign("=", Index(Index(Ident("a"), Ident("i")), Ident("j")), Ident("c"))
    }
    "a dereference" in {
      expr("*p = c") shouldBe Assign("=", Unary("*", Ident("p")), Ident("c"))
    }
    "a place under a compound operator" in {
      expr("s.n += 1") shouldBe Assign("+=", Field(Ident("s"), "n"), i(1))
      expr("a[i] *= 2") shouldBe Assign("*=", Index(Ident("a"), Ident("i")), i(2))
    }
  }

  "precedence and associativity" - {
    "assignment is right-associative" in {
      expr("a = b = c") shouldBe Assign("=", Ident("a"), Assign("=", Ident("b"), Ident("c")))
    }
    "a compound then a plain assignment nest to the right" in {
      expr("a += b = c") shouldBe Assign("+=", Ident("a"), Assign("=", Ident("b"), Ident("c")))
    }
    "assignment is looser than || on the right" in {
      expr("a = b || c") shouldBe Assign("=", Ident("a"), Binary("||", Ident("b"), Ident("c")))
    }
    "assignment is looser than || on the left" in {
      expr("a || b = c") shouldBe Assign("=", Binary("||", Ident("a"), Ident("b")), Ident("c"))
    }
  }

  "a control-flow expression may sit on the right" - {
    "an inline if" in {
      expr("x = if c then 1 else 2") shouldBe Assign("=", Ident("x"), ifE(Ident("c"), i(1), i(2)))
    }
    "an inline if under every compound operator" in {
      for op <- List("+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "<<=", ">>=") do
        expr(s"x $op if c then 1 else 2") shouldBe Assign(op, Ident("x"), ifE(Ident("c"), i(1), i(2)))
    }
    "an if with no else" in {
      expr("x = if c then 1") shouldBe Assign("=", Ident("x"), IfExpr(Ident("c"), body(i(1)), None))
    }
    "a place on the left and control flow on the right" in {
      expr("obj.field = if c then 1 else 2") shouldBe
        Assign("=", Field(Ident("obj"), "field"), ifE(Ident("c"), i(1), i(2)))
    }
    "a block match under a plain assignment" in {
      prog("x = y match\n    1 -> 10\n    else 20") shouldBe List(
        ExprStmt(
          Assign(
            "=",
            Ident("x"),
            MatchExpr(
              Ident("y"),
              List(
                MatchArm(List(LitPattern(i(1))), None, body(i(10))),
                MatchArm(List(WildcardPattern), None, body(i(20))),
              ),
            ),
          )
        )
      )
    }
    "a block match under a compound assignment" in {
      prog("total += y match\n    1 -> 10\n    else 20") shouldBe List(
        ExprStmt(
          Assign(
            "+=",
            Ident("total"),
            MatchExpr(
              Ident("y"),
              List(
                MatchArm(List(LitPattern(i(1))), None, body(i(10))),
                MatchArm(List(WildcardPattern), None, body(i(20))),
              ),
            ),
          )
        )
      )
    }
    "a while loop" in {
      expr("x = while c do 1") shouldBe Assign("=", Ident("x"), While(None, Ident("c"), body(i(1)), None))
    }
    "a for loop" in {
      expr("x = for k in xs do 1") shouldBe
        Assign("=", Ident("x"), For(None, "k", Ident("xs"), body(i(1)), None))
    }
    "a labeled loop" in {
      expr("x = 'scan while c do 1") shouldBe
        Assign("=", Ident("x"), While(Some("scan"), Ident("c"), body(i(1)), None))
    }
    "control flow threads through right-associative assignment" in {
      expr("a = b = if c then 1 else 2") shouldBe
        Assign("=", Ident("a"), Assign("=", Ident("b"), ifE(Ident("c"), i(1), i(2))))
    }
  }

  "a control-flow expression is allowed where delimiters bound it" - {
    "inside an index" in {
      expr("a[if c then 0 else 1]") shouldBe Index(Ident("a"), ifE(Ident("c"), i(0), i(1)))
    }
    "as a call argument" in {
      expr("f(if c then 1 else 2)") shouldBe Call(Ident("f"), List(ifE(Ident("c"), i(1), i(2))))
    }
    "inside parentheses as an operand" in {
      expr("(if c then 1 else 2) + 1") shouldBe Binary("+", ifE(Ident("c"), i(1), i(2)), i(1))
    }
  }

  "a control-flow expression is not a bare operator operand" - {
    "not the right operand of arithmetic" in {
      progError("var z = 1 + if c then 2 else 3")
      progError("var z = 1 * if c then 2 else 3")
    }
    "not the right operand of a comparison" in {
      progError("var z = a < if c then 1 else 2")
    }
    "not the operand of a prefix operator" in {
      progError("var z = !if c then true else false")
      progError("var z = -if c then 1 else 2")
    }
  }

  "an ordinary right-hand side is unchanged" - {
    "an arithmetic expression keeps its precedence" in {
      expr("x = a + b * c") shouldBe
        Assign("=", Ident("x"), Binary("+", Ident("a"), Binary("*", Ident("b"), Ident("c"))))
    }
    "a comparison" in {
      expr("x = a < b") shouldBe Assign("=", Ident("x"), Compare(List(Ident("a"), Ident("b")), List("<")))
    }
    "a postfix try" in {
      expr("x = a?") shouldBe Assign("=", Ident("x"), TryExpr(Ident("a")))
    }
  }
}
