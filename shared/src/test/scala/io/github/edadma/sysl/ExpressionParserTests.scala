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
}
