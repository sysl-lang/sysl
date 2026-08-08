package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Character and string literals end to end: every escape the lexer accepts, every way one
 * can be malformed, the byte encoding codegen interns, and what actually reaches stdout.
 *
 * They share one scanner (`scanChar`), so a case that holds for one holds for the other —
 * which is why both are exercised against the same table rather than in separate suites.
 */
class StringLiteralTests extends AnyFreeSpec with Matchers with CodegenSupport with RunSupport {

  /** The token case classes are path-dependent, so an expected token must come from the same
   * lexer instance that scanned it.
   */
  private def withLexer(body: SyslLexical => Any): Unit = { body(new SyslLexical); () }

  extension (l: SyslLexical) {

    def bare(src: String): List[Any] =
      l.scan(src).filterNot(t => t == l.Newline || t == l.Indent || t == l.Dedent)

    /** The message of the error token a malformed literal produces. */
    def bad(src: String): String = l.bare(src).head.toString
  }

  /** A supplementary codepoint written the way source text holds it — as a surrogate pair. */
  private def pair(cp: Int): String = {
    val v = cp - 0x10000

    new String(Array((0xd800 + (v >> 10)).toChar, (0xdc00 + (v & 0x3ff)).toChar))
  }

  "the escape table" - {
    "every escape the language defines" in withLexer { l =>
      l.bare("'\\n'") shouldBe List(l.CharLit('\n'.toInt))
      l.bare("'\\t'") shouldBe List(l.CharLit('\t'.toInt))
      l.bare("'\\r'") shouldBe List(l.CharLit('\r'.toInt))
      l.bare("'\\b'") shouldBe List(l.CharLit(8))
      l.bare("'\\f'") shouldBe List(l.CharLit(12))
      l.bare("'\\0'") shouldBe List(l.CharLit(0))
      l.bare("'\\\\'") shouldBe List(l.CharLit('\\'.toInt))
      l.bare("'\\''") shouldBe List(l.CharLit('\''.toInt))
      l.bare("'\\\"'") shouldBe List(l.CharLit('"'.toInt))
    }

    "the same table applies inside a string" in withLexer { l =>
      l.bare("\"a\\nb\\tc\\r\\\\\\\"d\"") shouldBe List(l.StrLit("a\nb\tc\r\\\"d"))
      l.bare("\"a\\bb\\fc\"") shouldBe List(l.StrLit("a" + 8.toChar + "b" + 12.toChar + "c"))
    }

    "an escape the table does not name" in withLexer { l =>
      l.bad("'\\q'") should include("unknown escape sequence '\\q'")
      l.bad("\"a\\qb\"") should include("unknown escape sequence")
    }

    // `\e` is what ANSI terminal code reaches for, and it is a GNU extension rather than standard
    // C — refused on purpose, so that the language's set is the one every other language shares.
    "the escape character has no shorthand" in withLexer { l =>
      l.bad("'\\e'") should include("unknown escape sequence '\\e'")
      l.bare("'\\u{1b}'") shouldBe List(l.CharLit(0x1b))
    }

    "a backslash with nothing after it" in withLexer { l =>
      l.bad("\"a\\") should include("incomplete escape sequence")
    }
  }

  "the braced codepoint escape" - {
    "takes one to six hex digits, in either case" in withLexer { l =>
      l.bare("'\\u{41}'") shouldBe List(l.CharLit(0x41))
      l.bare("'\\u{7}'") shouldBe List(l.CharLit(7))
      l.bare("'\\u{e9}'") shouldBe List(l.CharLit(0xe9))
      l.bare("'\\u{1F600}'") shouldBe List(l.CharLit(0x1f600))
      l.bare("'\\u{10FFFF}'") shouldBe List(l.CharLit(0x10ffff))
    }

    "needs its braces" in withLexer { l =>
      l.bad("'\\u41'") should include("expected '{' after \\u")
      l.bad("'\\u{41'") should include("unterminated \\u{...} escape")
    }

    "needs at least one digit and at most six" in withLexer { l =>
      l.bad("'\\u{}'") should include("expected hex digits")
      l.bad("'\\u{1234567}'") should include("not a Unicode scalar value")
    }

    "rejects the values that are not scalar values" in withLexer { l =>
      l.bad("'\\u{110000}'") should include("U+110000 is not a Unicode scalar value")
      l.bad("'\\u{D800}'") should include("U+D800 is not a Unicode scalar value")
      l.bad("'\\u{DFFF}'") should include("is not a Unicode scalar value")
    }
  }

  "source text outside the ASCII range" - {
    "a supplementary character is one scalar value, however it is stored" in withLexer { l =>
      l.bare("'" + pair(0x1f600) + "'") shouldBe List(l.CharLit(0x1f600))
      l.bare("\"" + pair(0x1f600) + "\"") shouldBe List(l.StrLit(pair(0x1f600)))
    }

    "a two- and three-byte character passes through unchanged" in withLexer { l =>
      l.bare("'é'") shouldBe List(l.CharLit(0xe9))
      l.bare("\"héllo ☃\"") shouldBe List(l.StrLit("héllo ☃"))
    }

    "a surrogate with no partner is not a character" in withLexer { l =>
      l.bad("'" + '\ud800'.toString + "a'") should include("unpaired surrogate")
      l.bad("\"" + '\udc00'.toString + "\"") should include("unpaired surrogate")
    }
  }

  "termination" - {
    "a character literal holds exactly one character" in withLexer { l =>
      l.bad("''") should include("empty character literal")
      l.bad("'ab'") should include("must hold exactly one character")
    }

    // A letter-started `'a` with no closing quote is now a loop label, not an unterminated
    // character, so the unterminated-character cases use a digit-started literal, which cannot be
    // a label.
    "neither literal may run past the end of its line" in withLexer { l =>
      l.bad("'1") should include("unterminated character literal")
      l.bad("'1\nb") should include("unterminated character literal")
      l.bad("\"oops") should include("unterminated string literal")
      l.bad("\"oops\nmore\"") should include("unterminated string literal")
    }

    "an empty string is a string" in withLexer { l =>
      l.bare("\"\"") shouldBe List(l.StrLit(""))
    }
  }

  "a comment marker inside a string is ordinary text" in withLexer { l =>
    l.bare("\"a // b\"") shouldBe List(l.StrLit("a // b"))
    l.bare("\"a /* b */ c\"") shouldBe List(l.StrLit("a /* b */ c"))
  }

  "the interned constant" - {
    "escapes the bytes a textual module cannot hold literally" in {
      val out = ir("""print("a\"b\\c\n")""")

      out should include("""c"a\22b\5Cc\0A\00"""")
    }

    "counts UTF-8 bytes, not characters, plus the terminator" in {
      ir("print(\"é\")") should include("""[3 x i8] c"\C3\A9\00"""")
      ir("print(\"" + pair(0x1f600) + "\")") should include("[5 x i8]")
    }
  }

  "what reaches stdout" - {
    "escapes survive to the output" in {
      run("""print("a\tb")""") shouldBe "a\tb\n"
      run("""print("quote:\"", "back:\\")""") shouldBe "quote:\" back:\\\n"
      run("""print("a\bb\fc")""") shouldBe "a" + 8.toChar + "b" + 12.toChar + "c\n"
    }

    "non-ASCII text round-trips as UTF-8" in {
      run("""print("héllo ☃ """ + pair(0x1f600) + "\")") shouldBe "héllo ☃ " + pair(0x1f600) + "\n"
    }

    "an empty string prints as nothing between its neighbours" in {
      run("""print("a", "", "b")""") shouldBe "a  b\n"
    }

    "a string built from a char is the same bytes either way" in {
      run("print('☃', \"☃\")") shouldBe "☃ ☃\n"
    }

    // A `string` carries its own length, so a NUL is an ordinary byte in it rather than the end
    // of it — which is why printing one cannot go through a `%s` of any precision.
    "a NUL inside a string is an ordinary byte" in {
      run("""print("a\0b", "end")""") shouldBe "a" + 0.toChar + "b end\n"
    }
  }
}
