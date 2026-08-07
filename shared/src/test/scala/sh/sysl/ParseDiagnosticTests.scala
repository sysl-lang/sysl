package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a file that will not parse is told, and where the caret goes.
 *
 * **The rule: the refusal is reported at the furthest point the grammar reached, and it names
 * something the reader could have written there.** Both halves were broken, separately, and the
 * combination was the first thing a newcomer met on their first typo.
 *
 * The position was the damaging half. A `Success` carries the furthest failure the parse reached on
 * its way to succeeding, which is the only record of a mistake the grammar backtracked away from —
 * and every rule in the grammar is wrapped in `at`, which rebuilt the result and so emptied that
 * field. What survived to be reported came from outside the outermost rule, so the caret landed at
 * the top of the enclosing block however far down the mistake was.
 *
 * The message was the other half. Alternatives that fail at one position are ranked last-wins, so a
 * token that can begin no expression at all was reported against whatever sits at the bottom of the
 * ladder's last alternative — `'..' expected`, a range operator, for four unrelated mistakes and
 * none of them about a range. What answers for that now is `expression`, and the three combinators
 * in `SyslParserBase` that keep an absence from being spoken of as an expectation: `skipNewlines`,
 * `onNextLine`, and `maybe`.
 */
class ParseDiagnosticTests extends AnyFreeSpec with ParseSupport {

  /** The message and the `file:line:column` of a refusal, from the rendered diagnostic. */
  private def refusal(src: String): (String, String) = {
    val out   = progError(src)
    val lines = out.linesIterator.toList
    val msg   = lines.find(_.startsWith("error: ")).getOrElse(fail(out)).stripPrefix("error: ")
    val where = lines.find(_.trim.startsWith("-->")).getOrElse(fail(out)).trim.stripPrefix("--> ")

    (msg, where)
  }

  "the caret is at the mistake, however far into a block it is" - {

    // The case the rule was written from. Every line above the last one is correct, and the caret
    // used to sit on the first of them.
    "a stray token in the sixth line of a body is reported in the sixth line" in {
      refusal("main()\n    val a = 1\n\n    val b = 2\n\n    print(a b)\n") shouldBe
        ("')' expected", "<input>:6:13")
    }

    // A function's body is parsed as part of its declaration, so a body that will not parse makes
    // the *declaration* fail — and the position that used to survive was the declaration's own first
    // line, several lines above anything wrong.
    "a body that will not parse does not report against the signature above it" in {
      val src =
        """f(a: int) -> int
          |    val b =
          |        a + 1
          |
          |    b
          |end f
          |
          |print(str(f(1)))
          |""".stripMargin

      refusal(src) shouldBe ("expression expected", "<input>:2:12")
    }

    "and one inside a branch is reported in the branch" in {
      refusal("if a\n    print(1)\nelse\n    print(2 3)\nend if\n") shouldBe
        ("')' expected", "<input>:4:13")
    }
  }

  "four mistakes that gave one message now give four" - {

    // Each row of these was `'..' expected`, at the start of the enclosing block. None of them
    // involves a range, and the last is somebody reaching for a byte literal the language does not
    // have, told about an operator they did not write.
    "a second argument with no comma wants the comma's closing bracket" in {
      refusal("print(1 2)\n") shouldBe ("')' expected", "<input>:1:9")
    }

    "a second value after a binding wants the end of the statement" in {
      refusal("val x = 1 2\n") shouldBe ("newline expected", "<input>:1:11")
    }

    "an operator with nothing after it wants a value" in {
      refusal("print(1 +)\n") shouldBe ("expression expected", "<input>:1:10")
    }

    "and a byte literal that does not exist is read as the name beside it" in {
      refusal("print(b[0] == b';')\n") shouldBe ("')' expected", "<input>:1:16")
    }
  }

  "a value left off the end of a line is asked for where it should have been" - {

    "a const" in {
      refusal("const A: string =\n    \"x\"\n") shouldBe ("expression expected", "<input>:1:18")
    }

    // The same shape one construct over. These two used to disagree about the column, the `var`
    // pointing at the `=` and the `const` just past it.
    "and a var, in the same column relative to its '='" in {
      refusal("var x =\n    1 + 2\nprint(str(x))\n") shouldBe ("expression expected", "<input>:1:8")
    }
  }

  "an absence is not an expectation" - {

    // `match` is written after the value it transforms and may sit on a following line, so every
    // expression statement looks for one across the blank lines behind it. Finding none is ordinary,
    // and saying so out loud outranked the real mistake, being further into the file.
    "a stray bracket is not told a 'match' is missing" in {
      refusal("print(1)\n)\n") shouldBe ("expression expected", "<input>:2:1")
    }

    "and blank lines before it do not move the complaint onto them" in {
      refusal("print(1)\n\n)\n") shouldBe ("expression expected", "<input>:3:1")
    }

    // A module header is what a file may open with and usually does not, so its absence is not
    // something to report either. This said `'module' expected` against a line that had one problem
    // and it was not the header.
    "nor is a file that opens badly told it is missing a module header" in {
      refusal(")\nprint(1)\n")._1 should not include "'module'"
    }

    // The assignment form is `place = value`, tried at every expression and abandoned wherever there
    // is no `=`. The last of the operators it looks for was the one reported.
    "nor is a statement that runs on told about an assignment operator" in {
      refusal("val x = 1 2\n")._1 should not include "'>>='"
    }
  }

  "what the change did not do" - {

    // The rename fires only where a rule refused *without consuming anything*. A rule that got
    // somewhere and then found a specific token missing keeps its own message, which is the more
    // useful one.
    "a specific token still names itself rather than becoming 'expression'" in {
      refusal("var x = (1, 2\n")._1 shouldBe "')' expected"
    }

    "and a block that never closes still reports the block's own expectation" in {
      refusal("main()\n    print(1)\n    )\n") shouldBe ("dedent expected", "<input>:3:5")
    }

    // A lexical error is reported ahead of whatever the grammar made of the tokens around it, and
    // that path is separate from all of this.
    "a lexical error still outranks the grammar's reaction to it" in {
      refusal("print(\"oops\n")._1 shouldBe "unterminated string literal"
    }
  }
}
