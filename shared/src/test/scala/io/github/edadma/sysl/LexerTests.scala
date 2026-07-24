package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

class LexerTests extends AnyFreeSpec with Matchers {

  /** The token case classes are path-dependent inner classes, so an expected token is
   * only equal to a scanned one when both come from the *same* lexer instance — which
   * mirrors production, where a single lexer feeds a single parse. Every test therefore
   * runs against one `l`, and its `Newline` / `Indent` / `Dedent` are `l.Newline` etc.
   */
  private def withLexer(body: SyslLexical => Any): Unit = { body(new SyslLexical); () }

  extension (l: SyslLexical) {

    /** All tokens, including the structural ones. */
    def all(src: String): List[Any] = l.scan(src)

    /** Just the lexical content — structural tokens dropped — for value assertions. */
    def bare(src: String): List[Any] =
      l.scan(src).filterNot(t => t == l.Newline || t == l.Indent || t == l.Dedent)
  }

  "integer literals" - {
    "decimal" in withLexer { l =>
      l.bare("42") shouldBe List(l.IntLit(42, None))
    }

    "hex, binary, octal share one value space" in withLexer { l =>
      l.bare("0xFF") shouldBe List(l.IntLit(255, None))
      l.bare("0b1010_0101") shouldBe List(l.IntLit(165, None))
      l.bare("0o17") shouldBe List(l.IntLit(15, None))
    }

    "hex is case-insensitive" in withLexer { l =>
      l.bare("0xff") shouldBe List(l.IntLit(255, None))
    }

    "no C leading-zero octal — 010 is decimal ten" in withLexer { l =>
      l.bare("010") shouldBe List(l.IntLit(10, None))
    }

    "digit separators are stripped" in withLexer { l =>
      l.bare("1_000_000") shouldBe List(l.IntLit(1000000, None))
      l.bare("0xFF_FF") shouldBe List(l.IntLit(0xffff, None))
    }

    "a value beyond i64 still lexes (BigInt-backed)" in withLexer { l =>
      l.bare("340282366920938463463374607431768211456") shouldBe
        List(l.IntLit(BigInt(2).pow(128), None))
    }

    "type suffixes" in withLexer { l =>
      l.bare("42u8") shouldBe List(l.IntLit(42, Some("u8")))
      l.bare("7i5") shouldBe List(l.IntLit(7, Some("i5")))
      l.bare("100u12") shouldBe List(l.IntLit(100, Some("u12")))
      l.bare("0xFFu16") shouldBe List(l.IntLit(255, Some("u16")))
      l.bare("10usize") shouldBe List(l.IntLit(10, Some("usize")))
    }

    "a friendly alias is not a valid suffix" in withLexer { l =>
      l.bare("42int").head.toString should include("invalid literal suffix")
    }

    "a trailing separator is rejected" in withLexer { l =>
      l.bare("1_000_").head.toString should include("separator")
    }

    "a doubled separator is rejected" in withLexer { l =>
      l.bare("1__0").head.toString should include("separator")
    }
  }

  "float literals" - {
    "a dot with fractional digits" in withLexer { l =>
      l.bare("1.5") shouldBe List(l.FloatLit("1.5", None))
      l.bare("3.14") shouldBe List(l.FloatLit("3.14", None))
    }

    "exponents, signed and unsigned" in withLexer { l =>
      l.bare("2.5e10") shouldBe List(l.FloatLit("2.5e10", None))
      l.bare("1e-9") shouldBe List(l.FloatLit("1e-9", None))
    }

    "float suffixes" in withLexer { l =>
      l.bare("3.0f32") shouldBe List(l.FloatLit("3.0", Some("f32")))
      l.bare("1.5f16") shouldBe List(l.FloatLit("1.5", Some("f16")))
    }

    "an integer suffix on a float is rejected" in withLexer { l =>
      l.bare("1.5u8").head.toString should include("integer suffix")
    }

    "the value keeps full width as text (f128 exceeds Double)" in withLexer { l =>
      val wide = "1.189731495357231765085759326628007e4932"

      l.bare(wide + "f128") shouldBe List(l.FloatLit(wide, Some("f128")))
    }
  }

  "the range dot does not start a float" - {
    "1..2 lexes as three tokens" in withLexer { l =>
      l.bare("1..2") shouldBe List(l.IntLit(1, None), l.Keyword(".."), l.IntLit(2, None))
    }

    "1.5 stays a float" in withLexer { l =>
      l.bare("1.5") shouldBe List(l.FloatLit("1.5", None))
    }

    "..< wins over .. by longest match" in withLexer { l =>
      l.bare("0..<10") shouldBe List(l.IntLit(0, None), l.Keyword("..<"), l.IntLit(10, None))
    }
  }

  /** Character and string literals have their own suite — `StringLiteralTests` — because
   * their edge cases (the escape table, `\u{…}`, surrogates, termination) outnumber the rest
   * of the token grammar put together.
   */
  "quoted literals lex to their decoded value" in withLexer { l =>
    l.bare("'a'") shouldBe List(l.CharLit('a'.toInt))
    l.bare("\"hello\"") shouldBe List(l.StrLit("hello"))
  }

  "interpolated strings split into literal parts and embedded source" - {
    "a hole separates the surrounding literals" in withLexer { l =>
      l.bare("""s"a${x}b"""") shouldBe List(l.StrInterp(List("a", "b"), List("x")))
    }

    "a bare $name is a hole whose source is the name" in withLexer { l =>
      l.bare("""s"hi $who"""") shouldBe List(l.StrInterp(List("hi ", ""), List("who")))
    }

    "the parts still decode escapes, but raw does not" in withLexer { l =>
      l.bare("""s"a\tb"""") shouldBe List(l.StrInterp(List("a\tb"), Nil))
      l.bare("""raw"a\tb"""") shouldBe List(l.StrInterp(List("a\\tb"), Nil))
    }

    "a hole's source keeps a nested string and brace verbatim for re-lexing" in withLexer { l =>
      l.bare("""s"${ f("}") + {1} }"""") shouldBe
        List(l.StrInterp(List("", ""), List(""" f("}") + {1} """)))
    }

    "$$ is one literal dollar, not a hole" in withLexer { l =>
      l.bare("""s"$$"""") shouldBe List(l.StrInterp(List("$"), Nil))
    }

    "s or raw not against a quote is an ordinary identifier" in withLexer { l =>
      l.bare("s + raw") shouldBe List(l.Identifier("s"), l.Keyword("+"), l.Identifier("raw"))
    }
  }

  "identifiers and reserved words" - {
    "an identifier" in withLexer { l =>
      l.bare("foo_bar1") shouldBe List(l.Identifier("foo_bar1"))
    }

    "reserved words tokenize as keywords" in withLexer { l =>
      l.bare("weak") shouldBe List(l.Keyword("weak"))
      l.bare("true") shouldBe List(l.Keyword("true"))
    }

    "a type name is an ordinary identifier, not reserved" in withLexer { l =>
      l.bare("int") shouldBe List(l.Identifier("int"))
      l.bare("usize") shouldBe List(l.Identifier("usize"))
    }
  }

  "operators tokenize by longest match" in withLexer { l =>
    l.bare("<<=") shouldBe List(l.Keyword("<<="))
    l.bare("<<") shouldBe List(l.Keyword("<<"))
    l.bare("a += b") shouldBe List(l.Identifier("a"), l.Keyword("+="), l.Identifier("b"))
  }

  "comments are skipped" in withLexer { l =>
    l.bare("1 // trailing\n2") shouldBe List(l.IntLit(1, None), l.IntLit(2, None))
    l.bare("1 /* inline */ 2") shouldBe List(l.IntLit(1, None), l.IntLit(2, None))
  }

  "the off-side rule" - {
    "an indented block yields Newline / Indent / Dedent" in withLexer { l =>
      l.all("a\n    b\nc") shouldBe List(
        l.Identifier("a"),
        l.Newline,
        l.Indent,
        l.Identifier("b"),
        l.Newline,
        l.Dedent,
        l.Newline,
        l.Identifier("c"),
        l.Newline,
      )
    }

    "brackets join lines — no indentation tokens inside" in withLexer { l =>
      l.bare("(1\n 2)") shouldBe
        List(l.Keyword("("), l.IntLit(1, None), l.IntLit(2, None), l.Keyword(")"))
    }
  }
}
