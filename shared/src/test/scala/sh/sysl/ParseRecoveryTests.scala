package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a file with more than one mistake in it is told, and what is left of it afterwards.
 *
 * **The rule: a file the grammar refuses is parsed a second time, recovering — and a file it accepts
 * is not.** The second half is the one worth pinning hardest. `suite` is
 * `newline ~> indent ~> statements <~ dedent`, so the recovering `statements` stands under every
 * block in the language, including the blocks tried speculatively as one alternative among several.
 * A recovering block *succeeds* where it used to fail, and that failure is what hands the position
 * to the next alternative — so recovery on the first pass would quietly re-decide what a working
 * program parses as. Confined to a second pass it cannot, and the cases below are what says so.
 *
 * The tree the recovering pass yields has holes in it: a line it could not read is dropped rather
 * than replaced. That is why nothing but `recovered` hands it out, and why `checked` goes on
 * refusing a file that needed it.
 */
class ParseRecoveryTests extends AnyFreeSpec with ParseSupport {

  /** Every diagnostic a file yields, as `line:column — message`, in the order a reader meets them. */
  private def problems(src: String): List[String] =
    SyslParser.recovered(Source("t.sysl", src))._2.map { d =>
      d.pos.map(p => s"${p.line}:${p.col}").getOrElse("?") + " — " + d.message
    }

  /** The statements the recovering pass managed to keep, or `None` where it kept no tree at all. */
  private def kept(src: String): Option[List[Stmt]] =
    SyslParser.recovered(Source("t.sysl", src))._1.map(_.body)

  /** Just the lines the diagnostics sit on — what a caller marking a file actually places. */
  private def lines(src: String): List[Int] =
    SyslParser.recovered(Source("t.sysl", src))._2.flatMap(_.pos.map(_.line))

  "a file reports every line it could not read, not only the first" - {

    "two broken statements yield two diagnostics" in {
      lines("print(1 2)\nprint(3)\nprint(4 5)\n") shouldBe List(1, 3)
    }

    "three broken statements yield three" in {
      lines("print(1 2)\nprint(3 4)\nprint(5 6)\n") shouldBe List(1, 2, 3)
    }

    "a good file yields none, and its tree is the whole of it" in {
      val src = "print(1)\nprint(2)\n"

      problems(src) shouldBe Nil
      kept(src) shouldBe Some(prog(src))
    }
  }

  "the statements around a broken one survive it" - {

    // The point of the whole exercise: an editor asked about line 3 has something to answer with,
    // though line 1 is unreadable.
    "a broken first line does not cost the two below it" in {
      kept("print(1 2)\nval a = 5\nprint(a)\n").map(_.length) shouldBe Some(2)
    }

    "a broken line is dropped rather than stood in for" in {
      kept("val a = 1\nprint(2 3)\nval b = 4\n") shouldBe
        Some(List(ValDecl("a", None, i(1)), ValDecl("b", None, i(4))))
    }
  }

  "a skip stays inside the block it began in" - {

    // The depth count earning its keep: without it the skip runs to the next newline wherever that
    // is, and the statement after the block is read as though it were part of it.
    "a broken statement in a body does not take the rest of the file" in {
      val src = "main()\n    print(1 2)\n    print(3)\n\nprint(4)\n"

      lines(src) shouldBe List(2)
      kept(src).map(_.length) shouldBe Some(2)
    }

    // One unreadable header is worth one diagnostic, not one per line of the body underneath it.
    "a broken header takes the block it opened, and reports itself once" in {
      val src = "if\n    print(1)\n    print(2)\n\nprint(3)\n"

      lines(src) shouldBe List(1)
      kept(src) shouldBe Some(List(printStmt(i(3))))
    }

    // An unclosed bracket is the case where nothing survives, and it is the lexer's doing rather
    // than the skip's: a line break inside brackets is not a line break, so there is no next line
    // to skip to and the whole file is one statement that will not parse.
    "an unclosed bracket swallows the file, and says so once" in {
      val src = "struct Point(\n    x: int\n    y: int\n\nprint(1)\n"

      lines(src) shouldBe List(1)
      kept(src) shouldBe Some(Nil)
    }
  }

  "what recovery must not do" - {

    // A file that parses never reaches the recovering parser, so there is nothing for recovery to
    // change about it. This is that claim as a test rather than as a paragraph.
    "a file with a block written as one of several alternatives parses as it always did" in {
      val src = "main()\n    if true then print(1) else print(2)\n"

      problems(src) shouldBe Nil
      kept(src) shouldBe Some(prog(src))
    }

    "a recovered file is still refused by the entry point a build uses" in {
      SyslParser.checked(Source("t.sysl", "print(1 2)\nprint(3)\n")).isLeft shouldBe true
    }

    "the diagnostics a build is given are the recovered ones, all of them" in {
      SyslParser.checked(Source("t.sysl", "print(1 2)\nprint(3)\nprint(4 5)\n")) match
        case Left(ds) => ds.flatMap(_.pos.map(_.line)) shouldBe List(1, 3)
        case Right(p) => fail(s"expected a refusal, got $p")
    }

    // The token stream is damaged from the quote onward, so skipping lines through it would be
    // reporting the grammar's confusion about tokens the lexer has already explained.
    "a lexical error is reported once, and recovery is not attempted through it" in {
      problems("print(\"oops\nprint(1)\n").length shouldBe 1
    }
  }

  /** The regression the existing suite caught, as a rule of its own. The ordinary grammar reports the
   * furthest point it reached and names something the reader could have written there; the
   * recovering loop knows only that a line would not parse. Letting the second pass answer for the
   * *first* mistake swapped a tuned message for a vague one in eleven cases `ParseDiagnosticTests`
   * already pins.
   */
  "the first mistake keeps the grammar's own message, not recovery's" - {

    "the leading diagnostic is the one the parser always gave" in {
      problems("print(1 2)\nprint(3 4)\n").head shouldBe "1:9 — ')' expected"
    }

    "and what recovery adds sits strictly below it" in {
      problems("print(1 2)\nprint(3 4)\n").length shouldBe 2
      lines("print(1 2)\nprint(3 4)\n") shouldBe List(1, 2)
    }
  }

  "a reader is told a thing once" - {

    "the same complaint reached by two roads is reported once" in {
      val src = "main()\n    print(1 2)\n"

      problems(src).length shouldBe 1
    }

    "diagnostics come back in source order" in {
      lines("print(1 2)\nprint(3)\nprint(4 5)\nprint(6)\nprint(7 8)\n") shouldBe List(1, 3, 5)
    }
  }

  "two statements on one line are still two statements too many" in {
    lines("print(1) print(2)\nprint(3)\n") shouldBe List(1)
  }
}
