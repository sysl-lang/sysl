package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** An operator at the end of a line carries the expression onto the next one (`00 § Open`).
 *
 * The rule is that an operator which cannot finish an expression continues the line, so the tests
 * come in two halves: what continues, and what still ends a statement. The lexical half asserts the
 * strongest form of the claim — that a continued expression produces *exactly* the token stream the
 * same expression written on one line produces — which is discriminating in a way "it compiles"
 * would not be, since a stray `Newline` that the parser happened to tolerate would still pass that.
 *
 * Four guide programs reported the absence before this landed: `guide/bytecode` broke a condition
 * into early returns, `guide/sha2` has a signature with nowhere to break it, and `guide/shapes`
 * split a conjunction and a cross product into named halves.
 */
class LineContinuationTests extends AnyFreeSpec with Matchers with RunSupport with CodegenSupport {

  private def withLexer(body: SyslLexical => Any): Unit = { body(new SyslLexical); () }

  extension (l: SyslLexical) {
    def all(src: String): List[Any] = l.scan(src)
  }

  "a continued line lexes as one line" - {
    "an arithmetic operator" in withLexer { l =>
      l.all("var a = 1 +\n2") shouldBe l.all("var a = 1 + 2")
    }

    "the continuation line's own indentation carries no meaning" in withLexer { l =>
      l.all("var a = 1 +\n            2") shouldBe l.all("var a = 1 + 2")
      l.all("var a = 1 +\n2") shouldBe l.all("var a = 1 +\n        2")
    }

    "several operators in a row each carry the line" in withLexer { l =>
      l.all("var a = 1 +\n2 +\n3 +\n4") shouldBe l.all("var a = 1 + 2 + 3 + 4")
    }

    "a comment after the operator does not end the line" in withLexer { l =>
      l.all("var a = 1 + // still coming\n2") shouldBe l.all("var a = 1 + 2")
    }

    "a blank line after the operator does not end it either" in withLexer { l =>
      l.all("var a = 1 +\n\n\n2") shouldBe l.all("var a = 1 + 2")
    }

    "a prefix operator continues, because it cannot finish an expression" in withLexer { l =>
      l.all("var a = !\ntrue") shouldBe l.all("var a = ! true")
      l.all("var a = ~\n1") shouldBe l.all("var a = ~ 1")
    }

    "a compound assignment continues" in withLexer { l =>
      l.all("a +=\n1") shouldBe l.all("a += 1")
    }
  }

  "what still ends a statement" - {
    // The control: without a trailing operator these are two statements, and the newline between
    // them is exactly what says so.
    "a line ending in a complete expression" in withLexer { l =>
      l.all("var a = 1\nvar b = 2") should not be l.all("var a = 1 var b = 2")
    }

    // `=` and `->` already mean "an indented block starts here", and a token cannot mean that and
    // "the line goes on" at once.
    "'=' does not continue, because it opens a body" in withLexer { l =>
      l.all("f() -> int =\n    42") should not be l.all("f() -> int = 42")
    }

    "'->' does not continue, because it separates a pattern from its body" in withLexer { l =>
      l.all("x match\n    1 ->\n        2") should not be l.all("x match\n    1 -> 2")
    }

    "a postfix operator is a complete statement" in withLexer { l =>
      l.all("n++\nm++") should not be l.all("n++ m++")
    }

    "so is a '?'" in withLexer { l =>
      l.all("var a = f()?\nvar b = 2") should not be l.all("var a = f()? var b = 2")
    }

    // A range operator can be the whole thing — `s[..]` — so it is not in the set.
    "a range operator does not continue" in withLexer { l =>
      l.all("var a = 0..\n5") should not be l.all("var a = 0..5")
    }

    /** The case that only turned up when the whole suite ran, and the reason `.*` is one token.
     *
     * A wildcard import's text ends in a `*`, so while `.` and `*` lexed separately a continuation
     * there swallowed the newline and joined the import to the declaration after it — eighteen
     * import tests said so. Lexing `.*` together means a line never ends in a bare `*` that was
     * really the end of a statement, so both of these hold at once.
     */
    "a wildcard import is still a whole statement" in withLexer { l =>
      l.all("import isa.*\nvar a = 1") should not be l.all("import isa.* var a = 1")
    }
  }

  "it runs" - {
    "an arithmetic expression split over three lines" in {
      run("var a = 1 +\n    2 +\n    3\nprint(a)") shouldBe "6\n"
    }

    // The case four guide programs wanted: a conjunction too long for its line.
    "a conjunction split at '&&'" in {
      val src =
        """var w = 10
          |var x = 20
          |var y = 30
          |var big = w > 5 &&
          |          x > 5 &&
          |          y > 5
          |print(big)""".stripMargin

      run(src) shouldBe "true\n"
    }

    "a comparison split at the operator" in {
      run("var a = 3\nvar b = 4\nprint(a <\n      b)") shouldBe "true\n"
    }

    "precedence is unchanged by where the line broke" in {
      run("var a = 2 +\n3 *\n4\nprint(a)") shouldBe "14\n"
      run("var a = 2 * 3 +\n4 * 5\nprint(a)") shouldBe "26\n"
    }

    // Multiplication carries a line like every other binary operator, which is what lexing the
    // wildcard's `.*` as one token is for.
    "multiplication carries a line" in {
      run("var a = 6 *\n7\nprint(a)") shouldBe "42\n"
    }

    "and a wildcard import beside it is unaffected" in {
      val out = runIn(
        ("isa", "isa.sysl", "module isa\n\nsix() -> int = 6\n"),
        ("", "main.sysl", "import isa.*\n\nvar a = six() *\n    7\nprint(a)"),
      )

      out shouldBe "42\n"
    }

    // Inside a block the continuation has to leave the block's own extent alone, since the
    // suppressed newline is the one that would have carried an indent change.
    "a continuation inside an indented block leaves the block intact" in {
      val src =
        """var t = 0
          |var i = 0
          |while i < 3
          |    t = t +
          |        i * 10
          |    i++
          |print(t)""".stripMargin

      run(src) shouldBe "30\n"
    }

    "a continuation inside a function body" in {
      val src =
        """total(a: int, b: int, c: int) -> int
          |    a +
          |    b +
          |    c
          |print(total(1, 2, 3))""".stripMargin

      run(src) shouldBe "6\n"
    }

    // A bracketed expression continued at an operator: the two mechanisms have to compose, and the
    // bracket's own joining must not be disturbed by the operator's.
    "an operator continuation inside a bracketed one" in {
      run("print(1 +\n      2, 3 +\n      4)") shouldBe "3 7\n"
    }

    "a signature is not affected — a parameter list already continued" in {
      val src =
        """total(a: int,
          |      b: int) -> int = a + b
          |print(total(20, 22))""".stripMargin

      run(src) shouldBe "42\n"
    }
  }

  "the error path" - {
    // Nothing follows, so the operand the operator promised never arrives. The complaint is about
    // the missing operand rather than about the newline, which is the point: the line ended, the
    // expression did not.
    "an operator at the end of the file has no right-hand side" in {
      err("var a = 1 +") should include("expected")
    }

    // The hazard the rule accepts and `00` records: a continuation line that is dedented has its
    // dedent swallowed along with the newline, so the block does not close where it looks like it
    // does. Pinned so a future change to the rule has to notice it.
    "a dedented continuation line does not close the block it looks like it left" in {
      val src =
        """var t = 0
          |if true then
          |    t = 1 +
          |1
          |print(t)""".stripMargin

      run(src) shouldBe "2\n"
    }
  }
}
