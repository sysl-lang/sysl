package sh.sysl

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

    // `01` gives the three bitwise operators three levels of their own, in C's order. Only the
    // tightest of them is pinned above, against comparison; these are the two boundaries between
    // them, which a single level for all three would read the same way as left association.
    "the three bitwise operators keep three levels, and-tightest" in {
      expr("a | b ^ c") shouldBe Binary("|", Ident("a"), Binary("^", Ident("b"), Ident("c")))
      expr("a ^ b & c") shouldBe Binary("^", Ident("a"), Binary("&", Ident("b"), Ident("c")))
      expr("a | b ^ c & d") shouldBe
        Binary("|", Ident("a"), Binary("^", Ident("b"), Binary("&", Ident("c"), Ident("d"))))
    }

    "and binds tighter than or" in {
      expr("a || b && c") shouldBe Binary("||", Ident("a"), Binary("&&", Ident("b"), Ident("c")))
    }

    "prefix negation applies to the whole postfix chain, so `-a.b` is `-(a.b)`" in {
      expr("-a.b") shouldBe Unary("-", Field(Ident("a"), "b"))
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

    "a range binds tighter than a comparison, which is the level above it" in {
      expr("x == a..b") shouldBe
        Compare(List(Ident("x"), RangeExpr(Some(Ident("a")), Some(Ident("b")), inclusive = true)), List("=="))
    }

    "and does not associate, so a chain of two has no reading" in {
      progError("var r = a..b..c") should not be empty
    }

    // Rust spells an inclusive range `a..=b`, so a reader arriving from it writes one here once.
    // Left to the ordinary grammar it reads as the open-ended `a..` followed by a token nothing
    // wants, and the refusal is then about the range that was *parsed* rather than the one that was
    // written — in a `for` header, a sentence saying a range is only allowed in a `for` loop, to
    // somebody looking at one. All three of these are positions where a range is legal.
    "'..=' is refused by name, wherever a range may be written" in {
      val where = List(
        "main()\n    val a = 1\n    for i in a..=3\n        print(i)\n",
        "main()\n    val xs = [1, 2, 3]\n    print(xs[0..=1])\n",
        "main()\n    val a = 1\n    val b = a..=3\n",
      )

      for src <- where do
        progError(src) should include(
          "'..=' is not a range — inclusive is 'a..b' and exclusive is 'a..<b'",
        )
    }

    // The token that caused it is what the caret has to be under. Pointing past the `=` would put it
    // on the bound, which is the one part of the line that is right.
    "and it points at the '..', not past the '='" in {
      progError("main()\n    val a = 1\n    for i in a..=3\n        print(i)\n") should
        include(":3:15")
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

    /** **`raw` reads nothing at all, which is why it is not in this block's subject.**
      *
      * It used to be an interpolator following Scala's — backslashes left alone, holes still read —
      * and the combination that left unwritable is the only one a literal carrying another
      * language's source can use: a plain string reads no `${…}` and *does* decode escapes, and
      * Scala's `raw` does the opposite. So it lexes as one `StrLit` rather than a concatenation,
      * which is what says no interpolation scan ran over it.
      */
    "raw keeps both a backslash and a hole as ordinary characters" in {
      expr("""raw"a\n$x"""") shouldBe StrLit("a\\n$x")
      expr("""raw"${x}"""") shouldBe StrLit("${x}")
    }

    "a malformed hole is a fatal error naming the interpolation" in {
      val p = new SyslParser(Source("<expr>", """s"${1 +}""""))
      p.parseExpression match
        case p.Success(_, _)  => fail("expected a parse error")
        case ns: p.NoSuccess  => ns.msg should include("in interpolation")
    }

    "an f-string hole with a specifier renders through format, not str" in {
      expr("""f"${n}%04d"""") shouldBe Call(Ident("format"), List(Ident("n"), StrLit("%04d")))
    }

    "an f-string hole with no specifier still renders through str" in {
      expr("""f"$n"""") shouldBe str(Ident("n"))
    }

    "an f-string mixes formatted and plain holes around its literals" in {
      expr("""f"x=${a}%d y=$b"""") shouldBe
        cat(
          StrLit("x="),
          Call(Ident("format"), List(Ident("a"), StrLit("%d"))),
          StrLit(" y="),
          str(Ident("b")),
        )
    }
  }
}
