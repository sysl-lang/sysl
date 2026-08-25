package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** An operator at the end of a line carries the expression onto the next one
 * (`reference/lexical.md § An unbracketed line continues after an operator`).
 *
 * The rule is that an operator which cannot finish an expression continues the line, so the tests
 * come in two halves: what continues, and what still ends a statement. The lexical half asserts the
 * strongest form of the claim — that a continued expression produces *exactly* the token stream the
 * same expression written on one line produces — which is discriminating in a way "it compiles"
 * would not be, since a stray `Newline` that the parser happened to tolerate would still pass that.
 *
 * Four guide programs reported the absence before this landed: `guide/bytecode` broke a condition
 * into early returns, `sysl.crypto` has a signature with nowhere to break it, and `guide/shapes`
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

  /** The other direction: a line that continues the one above it because of how it **begins**.
   *
   * A chain is the one expression people habitually break across lines, and the break they write is
   * before the dot rather than after it — so the trailing rule above cannot see it, and there is no
   * operator at the end of `text(label)` for it to see. syslUI is what asked for this: four modifier
   * links are 92 characters on one line, and a toolkit whose whole surface is chained modifiers
   * cannot require that.
   *
   * A **name** after the dot is what makes it safe. A continued line's own margin is discarded, so a
   * rule that fired on something which could also begin a statement would pull that line into the
   * block above and move where the block ends — the hazard pinned at the end of this section.
   */
  "a line beginning with a dot continues the one above it" - {
    "one link lexes as though it had been written on one line" in withLexer { l =>
      l.all("var a = \"hi\"\n    .len") shouldBe l.all("var a = \"hi\".len")
    }

    "and so does a chain of them" in withLexer { l =>
      l.all("var a = x\n    .b\n    .c\n    .d") shouldBe l.all("var a = x.b.c.d")
    }

    "the continuation line's own indentation carries no meaning" in withLexer { l =>
      l.all("var a = x\n.b") shouldBe l.all("var a = x.b")
      l.all("var a = x\n            .b") shouldBe l.all("var a = x.b")
    }

    "a comment line in the middle of a chain does not end it" in withLexer { l =>
      l.all("var a = x\n    // why\n    .b") shouldBe l.all("var a = x.b")
    }

    "nor does a blank line" in withLexer { l =>
      l.all("var a = x\n\n\n    .b") shouldBe l.all("var a = x.b")
    }

    "the two continuation rules compose" in withLexer { l =>
      l.all("var a = x\n    .b +\n    y\n    .c") shouldBe l.all("var a = x.b + y.c")
    }
  }

  "what a leading dot must not swallow" - {
    // `..` is a range and `...` a variadic tail. Neither can begin a statement either, but the
    // predicate does not have to know that — requiring a letter after the dot means it never sees
    // them, which is a much smaller thing to be right about.
    "a range operator at the start of a line is not a chain" in withLexer { l =>
      l.all("var a = 1\n    ..2") should not be l.all("var a = 1..2")
    }

    // A tuple index is a digit, not a name.
    "a tuple index at the start of a line is not a chain" in withLexer { l =>
      l.all("var a = t\n    .0") should not be l.all("var a = t.0")
    }

    // The wildcard import is the case that cost the trailing rule an hour, so it is asserted from
    // this side too.
    "a wildcard import is untouched" in withLexer { l =>
      l.all("import isa.*\nvar a = 1") should not be l.all("import isa.* var a = 1")
    }

    // A trailing dot is deliberately NOT a continuation: two ways to write one chain is a style
    // argument in every file that has one, so the language admits exactly the one.
    "a trailing dot is still an error" in {
      err("var a = \"hi\".\n    len") should include("expected")
    }
  }

  "a chain broken before the dot runs" - {
    "at the top level" in {
      run("var a = \"hello\"\n    .len\nprint(a)") shouldBe "5\n"
    }

    "with several links" in {
      run("import sysl.text.Search\n\nvar a = \"  padded  \"\n    .trim()\n    .len\nprint(a)") shouldBe "6\n"
    }

    // The block's extent is the thing a suppressed newline could damage, so the chain is put inside
    // one and the statement after it has to still be inside the block.
    "inside an indented block, which still ends where it looks like it does" in {
      val src =
        """var t = 0
          |var i = 0
          |while i < 3
          |    t = t + int(s"${i}"
          |        .len)
          |    i++
          |print(t)""".stripMargin

      run(src) shouldBe "3\n"
    }

    "inside a function body" in {
      val src =
        """digits(n: int) -> usize
          |    s"${n}"
          |        .len
          |print(digits(12345))""".stripMargin

      run(src) shouldBe "5\n"
    }

    // Inside brackets newlines were already suppressed, so the two mechanisms have to compose
    // rather than fight.
    "inside a bracketed expression" in {
      run("print(\"hi\"\n    .len, \"abc\"\n    .len)") shouldBe "2 3\n"
    }
  }

  "the hazard a leading-token rule shares with a trailing one" - {
    /** A continued line's margin is discarded, which is the whole point of continuing — so a chain
      * written at the OUTER margin is still inside the block above it, and that block ends one line
      * later than it looks like it does.
      *
      * This is why the predicate asks for a name after the dot rather than merely a dot: the shapes
      * that could begin a statement are excluded, and what remains cannot. Pinned so that a future
      * widening of the rule has to notice what it is taking on.
      */
    "a chain written at the outer margin is still inside the block above" in {
      val src =
        """var t = 0
          |if true
          |    t = t + 1
          |.to_string()
          |print(t)""".stripMargin

      err(src) should include("to_string")
    }
  }
}
