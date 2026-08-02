package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.regex`'s Pike VM — the program run against an input.
 *
 * Every answer here is the full slot array, printed as `start:end` per group with group 0 first,
 * and `-1:-1` for a group that did not take part. Asserting the spans rather than "did it match"
 * is what makes these discriminating: an engine that reports the right yes-or-no while capturing
 * the wrong subexpression passes a boolean test and fails these.
 *
 * The offsets are **byte** offsets into the input, on character boundaries, which is what a slice
 * takes.
 */
class RegexVmTests extends AnyFreeSpec with RunSupport {

  private def matches(cases: (String, String)*): String =
    run(
      """import sysl.regex.{compile_pattern, exec, describe}
        |
        |m(pat: string, input: string)
        |    compile_pattern(pat) match
        |        Err(e) -> print(describe(e))
        |        Ok(p) ->
        |            exec(p, input) match
        |                None -> print("no")
        |                Some(sl) ->
        |                    var out = ""
        |
        |                    for i in 0usize..<(sl.len / 2usize)
        |                        if i > 0usize then out += " "
        |                        out += s"${sl[i * 2usize]}:${sl[i * 2usize + 1usize]}"
        |
        |                    print(out)
        |
        |main()
        |""".stripMargin +
        cases.map { case (p, i) => s"""    m("$p", "$i")""" }.mkString("\n") + "\n",
    )

  "finding a match anywhere in the input" - {

    "a literal, present and absent" in {
      matches("a" -> "a", "a" -> "b", "abc" -> "xxabcyy") shouldBe
        """0:1
          |no
          |2:5
          |""".stripMargin
    }

    "the repetitions take as much as they can" in {
      matches("a*" -> "aaa", "a+" -> "baaa", "a{2,3}" -> "aaaa") shouldBe
        """0:3
          |1:4
          |0:3
          |""".stripMargin
    }

    // A star that matches nothing still matches, at the leftmost position, which is the case a
    // matcher written around "advance until something matches" gets wrong.
    "a star with nothing to match still matches, and empty" in {
      matches("a*" -> "bbb", "x*" -> "", "" -> "abc") shouldBe
        """0:0
          |0:0
          |0:0
          |""".stripMargin
    }

    "a bracket expression, negated and named" in {
      matches("[a-z]+" -> "12abc34", "[^0-9]+" -> "12ab34", "[[:digit:]]+" -> "ab123cd") shouldBe
        """2:5
          |2:4
          |2:5
          |""".stripMargin
    }
  }

  "the anchors are zero-width assertions about position" - {

    "start and end together pin the whole input" in {
      matches("^abc$" -> "abc", "^abc$" -> "xabc", "^$" -> "", "^$" -> "a") shouldBe
        """0:3
          |no
          |0:0
          |no
          |""".stripMargin
    }

    "an end anchor finds the last occurrence rather than the first" in {
      matches("a$" -> "aba", "^a" -> "aba") shouldBe
        """2:3
          |0:1
          |""".stripMargin
    }
  }

  "leftmost, and then longest" - {

    // The distinguishing test between POSIX and Perl semantics, and the reason it is worth having:
    // Perl returns `0:1` here, because it takes the first alternative that works. POSIX takes the
    // longest, and so does this.
    "the longest alternative wins, not the first one written" in {
      matches("a|ab" -> "ab", "ab|a" -> "ab", "a|ab|abc" -> "abcd") shouldBe
        """0:2
          |0:2
          |0:3
          |""".stripMargin
    }

    // Leftmost beats longest: the match at 1 is shorter than nothing later could be, but it starts
    // earlier, and no new starting position is admitted once something has matched.
    "an earlier start beats a later one" in {
      matches("x|abc" -> "zabc", "a*" -> "xaaa") shouldBe
        """1:4
          |0:0
          |""".stripMargin
    }
  }

  "capture groups" - {

    "each group reports its own span, and group 0 is the whole match" in {
      matches("(a)(b)" -> "ab", "(a+)(b+)" -> "aabbb") shouldBe
        """0:2 0:1 1:2
          |0:5 0:2 2:5
          |""".stripMargin
    }

    // A repeated group holds what the *last* repetition matched, which is what POSIX says and what
    // falls out of the group having one number however many copies the expansion made.
    "a repeated group holds the last repetition" in {
      matches("(ab)*" -> "ababab", "(a)(b)?" -> "a") shouldBe
        """0:6 4:6
          |0:1 0:1 -1:-1
          |""".stripMargin
    }

    "a group on the branch not taken reports -1" in {
      matches("(a)|(b)" -> "b") shouldBe "0:1 -1:-1 0:1\n"
    }

    "a group may match empty and say where" in {
      matches("(|a)" -> "a", "(a*)b" -> "b") shouldBe
        """0:1 0:1
          |0:1 0:0
          |""".stripMargin
    }
  }

  "characters, not bytes" - {

    // The claim the whole design rests on. `.` is one character, so it takes both bytes of `é` and
    // reports a span a slice can cut; a byte-stepping matcher answers 0:1 and hands back half of a
    // character.
    "dot takes a whole character, however many bytes it occupies" in {
      matches("." -> "é", ".." -> "éü") shouldBe
        """0:2
          |0:4
          |""".stripMargin
    }

    "a repetition over a character outside ASCII counts characters and reports bytes" in {
      matches("é+" -> "aééb") shouldBe "1:5\n"
    }
  }

  // POSIX ERE has no flag for this and `grep` never sees a newline because it works a line at a
  // time, so there is no established answer to defer to. `.` matching it is the simpler rule and
  // the one Go's `regexp` gives by default.
  "dot matches a newline, there being no flag that says otherwise" in {
    matches("a.c" -> "a\\nc") shouldBe "0:3\n"
  }

  /** The property the whole design is for: every branch is followed at once, so the work is the
   * input length times the program length and never the number of paths through the pattern.
   *
   * `(a|a)*b` against a run of `a`s with no `b` is the standard demonstration. A backtracking
   * engine tries every way of splitting the run between the two identical alternatives — 2^n of
   * them — so at 26 it is already tens of millions of steps and at 40 it will not finish. This
   * answers immediately, and the assertion is that it answers at all.
   */
  "a pattern that sends a backtracking engine exponential costs this one nothing" in {
    matches("(a|a)*b" -> ("a" * 40)) shouldBe "no\n"
  }
}
