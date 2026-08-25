package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The multi-line literal — `"""` … `"""` (`reference/lexical.md § Strings`).
  *
  * The backlog entry called this "no multi-line or raw string literal", and the shape it had to
  * serve was named at the same time: **embedded data**. That turns out to want two things a plain
  * multi-line literal does not give. It wants the block indented with the code around it and *not*
  * to carry that indentation in the value, which is what makes an off-side-rule language different
  * here from a free-form one. And for data in particular it wants the value to have **no line
  * breaks at all** — a hex-encoded file split over lines is one string, not a list of them — which
  * is what the `\`-at-end-of-line join is for.
  *
  * The indentation rule follows Java's text blocks: the strip is the least indented line that
  * carries content, together with the closing delimiter's own line. So the delimiter is the control
  * — move it left to keep more, right to keep less — with no margin character to remember. The
  * trailing-newline rule then needs no separate statement, which is the part worth checking rather
  * than assuming: it falls out of where the delimiter is.
  *
  * `Q` and `BS` are spliced because a Scala triple-quoted string can hold neither the delimiter nor
  * a trailing backslash. The two malformed cases are asked of the lexer directly, since an error
  * token is what the scanner produces and the parser's own complaint would be reported instead.
  *
  * The suite is in the two halves `9b` asks for.
  */
class TextBlockTests extends AnyFreeSpec with Matchers with RunSupport with CodegenSupport {

  private val Q  = "\"\"\""
  private val BS = "\\"

  /** The message of the error token a malformed literal produces. */
  private def bad(src: String): String = {
    val l = new SyslLexical

    l.scan(src).filterNot(t => t == l.Newline || t == l.Indent || t == l.Dedent).head.toString
  }

  "what the documents claim" - {
    // The basic promise: lines in, newlines between them, and the code's own indentation gone.
    "a block keeps its line breaks and drops the indentation it was written at" in {
      run(s"""var s = $Q
             |    one
             |    two
             |    $Q
             |print(s)""".stripMargin) shouldBe "one\ntwo\n\n"
    }

    // The delimiter is the control. Written further left, the block keeps the indentation the
    // content had relative to it — the same source lines, a different value.
    "the closing delimiter says how much indentation is incidental" in {
      run(s"""var s = $Q
             |    one
             |      two
             |$Q
             |print(s)""".stripMargin) shouldBe "    one\n      two\n\n"
    }

    // A delimiter that follows content ends the value there, so there is no trailing newline. This
    // is the half of the rule that falls out of the algorithm rather than being written into it.
    "a delimiter on a content line leaves no trailing newline" in {
      run(s"""var s = $Q
             |    one
             |    two$Q
             |print(s.len)""".stripMargin) shouldBe "7\n"
    }

    // THE customer: embedded data. Written a line at a time, read as one string with no breaks.
    "a backslash at the end of a line joins it to the next" in {
      run(s"""var s = $Q
             |    abc$BS
             |    def$BS
             |    ghi
             |    $Q
             |print(s)""".stripMargin) shouldBe "abcdefghi\n\n"
    }

    // `04` says a text block is a `string` like any other — not a new kind of value. So it joins,
    // compares, and indexes exactly as a one-line literal does.
    "a block is an ordinary string" in {
      run(s"""var s = $Q
             |    ab
             |    $Q
             |print(s == "ab${BS}n", s.len, s[0])""".stripMargin) shouldBe "true 3 97\n"
    }

    // The prefixes are orthogonal to the quote form, which is the claim being checked rather than
    // assumed: a block is a literal *form*, and a prefix says how the literal is read.
    "the interpolation prefixes compose with the block form" in {
      run(s"""var n = 7
             |var s = s$Q
             |    n is $$n
             |    $Q
             |print(s)""".stripMargin) shouldBe "n is 7\n\n"
    }

    "a raw block leaves a backslash alone" in {
      run(s"""var s = raw$Q
             |    a${BS}nb
             |    $Q
             |print(s.len)""".stripMargin) shouldBe "5\n"
    }

    // `01`'s escape table is unchanged inside a block — the line discipline is layered over the
    // one-quote scan, not a replacement for it.
    "the escape table still applies" in {
      run(s"""var s = $Q
             |    a${BS}tb${BS}u{21}
             |    $Q
             |print(s.len, s[1])""".stripMargin) shouldBe "5 9\n"
    }

    // A quote is ordinary text in a block, which is the other thing the form buys: no escaping of
    // the character the one-line form ends at.
    "a lone quote inside a block is ordinary text" in {
      run(s"""var s = $Q
             |    he said "hi"
             |    $Q
             |print(s.len)""".stripMargin) shouldBe "13\n"
    }
  }

  "what the edges do" - {
    // The opening delimiter must end its line, which is what gives the indentation rule an anchor:
    // without it the opening column would mean something, and it means nothing.
    "content on the opening line is refused" in {
      bad(s"${Q}abc\n") should include("line after")
    }

    "an unterminated block is refused" in {
      bad(s"$Q\nabc\n") should include("unterminated text block")
    }

    // Trailing blanks are invisible in a source file, so they are dropped rather than silently
    // entering the value. This is the one edit-by-accident hazard the form has.
    "trailing blanks on a line are dropped" in {
      run(s"""var s = $Q
             |    ab${"   "}
             |    cd\t
             |    $Q
             |print(s.len)""".stripMargin) shouldBe "6\n"
    }

    // …and one that is meant is written as an escape, which survives because escapes are read
    // *after* the trimming rather than before it.
    "a trailing space that is meant is written as an escape" in {
      run(s"""var s = $Q
             |    ab${BS}u{20}
             |    $Q
             |print(s.len)""".stripMargin) shouldBe "4\n"
    }

    // A blank line has no indentation to speak of, so it says nothing about the strip — otherwise
    // one stray empty line would flatten the whole block.
    "a blank line does not lower the strip" in {
      run(s"""var s = $Q
             |    one
             |
             |    two
             |    $Q
             |print(s)""".stripMargin) shouldBe "one\n\ntwo\n\n"
    }

    // The empty block: a delimiter alone on the line after the opening one. Nothing between them,
    // so nothing in the value — not a newline either, since there was no content line to end.
    "a block with no content lines is the empty string" in {
      run(s"""var s = $Q
             |    $Q
             |print(s.len)""".stripMargin) shouldBe "0\n"
    }

    // A content line is never shorter than the strip — the strip is their minimum — but a blank one
    // can be, and it gives up only the whitespace it has rather than eating the newline that ends
    // it. "one\n" + "\n" + "two\n" is nine characters.
    "a short blank line gives up only the whitespace it has" in {
      run(s"""var s = $Q
             |    one
             |${"  "}
             |    two
             |    $Q
             |print(s.len)""".stripMargin) shouldBe "9\n"
    }

    // `04` — a block is one constant, which is the whole reason it beats `a + b` for embedded data:
    // the joining happens in the lexer, so there is no allocation and no runtime work at all.
    "a joined block is one constant, not a concatenation" in {
      val out = ir(s"""var s = $Q
                      |    ab$BS
                      |    cd
                      |    $Q
                      |print(s)""".stripMargin)

      out should include("abcd")
      out should not include "concat"
    }

    // The block form does not change what a one-line literal means, and the two live beside each
    // other in one file — the check that the lexer's lookahead did not eat an ordinary string.
    "a one-line literal beside a block is unaffected" in {
      run(s"""var a = "x"
             |var b = $Q
             |    y
             |    $Q
             |var c = "z"
             |print(a, b.len, c)""".stripMargin) shouldBe "x 2 z\n"
    }

    // An empty one-line literal is two quotes, and two quotes are the front of a three-quote
    // delimiter — the one place the forms could have been confused for each other.
    "an empty string is still an empty string" in {
      run("""var s = ""
            |print(s.len)""".stripMargin) shouldBe "0\n"
    }

    // A block inside an indented block: the layout algorithm never sees the lines inside the
    // literal, so the code around it indents and dedents exactly as it would without one.
    "the code around a block indents normally" in {
      run(s"""var n = 0usize
             |for i in 0..<2
             |    var s = $Q
             |        ab
             |        $Q
             |    n += s.len
             |print(n)""".stripMargin) shouldBe "6\n"
    }

    // A `c"""` block is the C form of the same literal — the prefix says how the bytes are laid
    // down, and the quote form says how the text was written.
    "a C string may be a block" in {
      run(s"""extern "strlen" strlen(s: *u8) -> usize
             |print(strlen(c$Q
             |    ab$BS
             |    cd$Q))""".stripMargin) shouldBe "4\n"
    }

    // An `f"""` block carries its specifiers, which is the prefix that has most to lose from the
    // line discipline running over it — the `%` follows the hole and the newline follows the `%`.
    "an f-string block keeps its specifiers" in {
      run(s"""var n = 5
             |print(f$Q
             |    [$${n}%03d]
             |    $Q)""".stripMargin) shouldBe "[005]\n\n"
    }

    // A block in a file with Windows line endings means what it means in a file without them. The
    // carriage return goes with the rest of the line's trailing whitespace rather than into the
    // value — found by probing what the form promises, since nothing about it says "unless".
    "a carriage return does not reach the value" in {
      run("var s = " + Q + "\r\n    ab\r\n    " + Q + "\r\nprint(s.len)") shouldBe "3\n"
    }

    // `04` says a comment marker inside a literal is ordinary text, and a block is a literal. Both
    // markers, since a block is the first literal long enough to make either look plausible.
    "a comment marker inside a block is ordinary text" in {
      run(s"""var s = $Q
             |    a // not a comment
             |    /* nor this */
             |    $Q
             |print(s.len)""".stripMargin) shouldBe "34\n"
    }

    // `13 §7` — a `const`'s initializer is a constant expression, and a string literal is one
    // however it was written. A block folds like any other literal, with no storage and no symbol.
    "a block may initialize a const" in {
      run(s"""const S: string = $Q
             |    ab
             |    $Q
             |print(S.len)""".stripMargin) shouldBe "3\n"
    }

    // …and a `val` takes a block exactly as it takes a one-line string, since a block *is* a string
    // literal by the time the analyzer sees it (`13 §7`). Pinned so the two stay the same case: the
    // rule is about a value the object file can carry, not about how the value was written.
    "a val takes a block exactly as it takes a one-line string" in {
      run("""val S: string = "ab"
            |print(S.len)""".stripMargin) shouldBe "2\n"

      run(s"""val S: string = $Q
             |    ab
             |    $Q
             |print(S.len)""".stripMargin) shouldBe "3\n"
    }

    // `04` — a string literal is a `match` pattern, and a block is a string literal. The arm's
    // arrow follows the closing delimiter, which is the one place the layout could have gone wrong.
    "a block is a match pattern" in {
      run(s"""kind(s: string) -> int = s match
             |    $Q
             |        ab
             |        $Q -> 1
             |    else 2
             |print(kind("ab${BS}n"), kind("zz"))""".stripMargin) shouldBe "1 2\n"
    }

    // A block is an expression like any other, so it sits mid-expression and joins with `+`.
    "a block sits mid-expression" in {
      run(s"""print("x" + $Q
             |    y
             |    $Q + "z")""".stripMargin) shouldBe "xy\nz\n"
    }

    // A hole may not span the block's lines: `${` … `}` is scanned as one expression, and an
    // interpolation that runs off its line is the existing error, unchanged by the block form.
    "a hole that runs off its line is refused" in {
      bad(s"""s$Q
             |    $${1 +
             |    2}
             |    $Q
             |""".stripMargin) should include("unterminated interpolation")
    }
  }
}
