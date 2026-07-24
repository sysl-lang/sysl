package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Parsing of expressions: precedence, chained comparison, ranges, unary/postfix, primaries. */
class ExpressionParserTests extends AnyFreeSpec with ParseSupport {

  "expression precedence" - {
    "multiplication binds tighter than addition" in {
      expr("1 + 2 * 3") shouldBe Binary("+", i(1), Binary("*", i(2), i(3)))
    }

    "shift binds like multiplication, tighter than addition" in {
      expr("1 + 2 << 3") shouldBe Binary("+", i(1), Binary("<<", i(2), i(3)))
      expr("a << 8 + b") shouldBe
        Binary("+", Binary("<<", Ident("a"), i(8)), Ident("b"))
    }

    "bitwise-and binds tighter than comparison (the fix to C)" in {
      expr("x & mask == 0") shouldBe
        Compare(List(Binary("&", Ident("x"), Ident("mask")), i(0)), List("=="))
    }

    "addition is left-associative" in {
      expr("1 - 2 - 3") shouldBe Binary("-", Binary("-", i(1), i(2)), i(3))
    }

    "assignment is right-associative and loosest" in {
      expr("a = b = c") shouldBe
        Assign("=", Ident("a"), Assign("=", Ident("b"), Ident("c")))
    }
  }

  "comparison chains" - {
    "a single comparison" in {
      expr("a < b") shouldBe Compare(List(Ident("a"), Ident("b")), List("<"))
    }

    "a three-way chain keeps all operands and operators" in {
      expr("a < b <= c") shouldBe
        Compare(List(Ident("a"), Ident("b"), Ident("c")), List("<", "<="))
    }
  }

  "ranges" - {
    "an inclusive range below arithmetic" in {
      expr("0..<n + 1") shouldBe
        RangeExpr(Some(i(0)), Some(Binary("+", Ident("n"), i(1))), inclusive = false)
    }

    "an open-ended range" in {
      expr("a..") shouldBe RangeExpr(Some(Ident("a")), None, inclusive = true)
    }
  }

  "unary and postfix" - {
    "postfix binds tighter than prefix — *p++ is *(p++)" in {
      expr("*p++") shouldBe Unary("*", PostIncDec("++", Ident("p")))
    }

    "member access chains" in {
      expr("a.b.c") shouldBe Field(Field(Ident("a"), "b"), "c")
    }

    "a call with arguments" in {
      expr("f(a, b)") shouldBe Call(Ident("f"), List(Ident("a"), Ident("b")))
    }

    "index and try" in {
      expr("xs[0]?") shouldBe TryExpr(Index(Ident("xs"), i(0)))
    }
  }

  "primaries" - {
    "unit and grouping and tuples" in {
      expr("()") shouldBe UnitLit()
      expr("(1 + 2) * 3") shouldBe Binary("*", Binary("+", i(1), i(2)), i(3))
      expr("(1, 2, 3)") shouldBe Tuple(List(i(1), i(2), i(3)))
    }

    "literals carry their lexed values" in {
      expr("42u8") shouldBe IntLit(42, Some("u8"))
      expr("3.14") shouldBe FloatLit("3.14", None)
      expr("true") shouldBe BoolLit(true)
      expr("\"hi\"") shouldBe StrLit("hi")
    }
  }

  "string interpolation" - {
    def str(e: Expr): Expr = Call(Ident("str"), List(e))
    def cat(es: Expr*): Expr = es.reduceLeft((l, r) => Binary("+", l, r))

    "with no holes is just the literal" in {
      expr("""s"plain"""") shouldBe StrLit("plain")
      expr("""s""""") shouldBe StrLit("")
    }

    "a lone `$name` renders that name — no surrounding literals" in {
      expr("""s"$name"""") shouldBe str(Ident("name"))
    }

    "surrounding text becomes the literal segments around each render" in {
      expr("""s"a${x}b"""") shouldBe cat(StrLit("a"), str(Ident("x")), StrLit("b"))
    }

    "adjacent holes render each with no literal between them" in {
      expr("""s"$x$y"""") shouldBe cat(str(Ident("x")), str(Ident("y")))
    }

    "a `${ … }` hole parses as a full expression" in {
      expr("""s"= ${a + b * 2}"""") shouldBe
        cat(StrLit("= "), str(Binary("+", Ident("a"), Binary("*", Ident("b"), i(2)))))
    }

    "a hole may itself contain a string, brace, and further interpolation" in {
      expr("""s"${ f("x") + g }"""") shouldBe
        str(Binary("+", Call(Ident("f"), List(StrLit("x"))), Ident("g")))
    }

    "`$$` is a literal dollar, not a hole" in {
      expr("""s"$$5"""") shouldBe StrLit("$5")
    }

    "an empty hole-adjacent segment is dropped as the identity under `+`" in {
      expr("""s"${x}${y}"""") shouldBe cat(str(Ident("x")), str(Ident("y")))
    }

    "raw keeps a backslash as an ordinary character but still interpolates" in {
      expr("""raw"a\n$x"""") shouldBe cat(StrLit("a\\n"), str(Ident("x")))
    }

    "a malformed hole is a fatal error naming the interpolation" in {
      val p = new SyslParser
      p.parseExpression("""s"${1 +}"""") match
        case p.Success(_, _)  => fail("expected a parse error")
        case ns: p.NoSuccess  => ns.msg should include("in interpolation")
    }
  }
}
