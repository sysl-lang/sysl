package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.regex`'s compiler — a tree becomes a flat program of nine instructions.
 *
 * These assert on the listing rather than on what the pattern goes on to match, and that is the
 * point of them: a jump target computed one off is invisible in the answers a small pattern gives —
 * `a*` against `"aaa"` matches whether the loop branches to the `split` or to the instruction after
 * it — and it is right here in the listing, where the number can be read.
 *
 * The other thing pinned is that the program counters are absolute. Every arrangement is emitted
 * into one growing array, so a construct nested inside another has its targets shifted by whatever
 * came before it, and the nesting tests are the ones that catch an arrangement that only works at
 * the start of a program.
 */
class RegexCompileTests extends AnyFreeSpec with RunSupport {

  /** The instruction count alone, for the patterns whose listing would be ten thousand lines. */
  private def size(pattern: String): String =
    run(s"""import sysl.regex.{compile_pattern, describe}
           |
           |main()
           |    compile_pattern("$pattern") match
           |        Ok(p) -> print(p.len())
           |        Err(e) -> print(describe(e))
           |""".stripMargin)

  private def compiles(pattern: String): String =
    run(s"""import sysl.regex.{compile_pattern, dump, describe}
           |
           |main()
           |    compile_pattern("$pattern") match
           |        Ok(p) -> print(dump(p))
           |        Err(e) -> print(describe(e))
           |""".stripMargin)

  // Group 0's pair of saves wraps every program, so the whole match is a capture group like any
  // other and the matcher needs no separate notion of where it began.
  "every program is group 0's saves around the pattern, ending in accept" in {
    compiles("a") shouldBe
      """0: save 0
        |1: char a
        |2: save 1
        |3: accept
        |
        |""".stripMargin
  }

  "concatenation emits its sides in order and nothing of its own" in {
    compiles("ab") shouldBe
      """0: save 0
        |1: char a
        |2: char b
        |3: save 1
        |4: accept
        |
        |""".stripMargin
  }

  "the one-instruction atoms" in {
    compiles("^.[a-z]$") shouldBe
      """0: save 0
        |1: start
        |2: any
        |3: set(a-z)
        |4: end
        |5: save 1
        |6: accept
        |
        |""".stripMargin
  }

  "the repetitions" - {

    // The `jump 1` is the whole of the loop, and it must name the split rather than the body: a
    // star that jumped to 2 would match `a+` and nothing would look different from outside.
    "star is a split around the body, and a jump back to the split" in {
      compiles("a*") shouldBe
        """0: save 0
          |1: split 2, 4
          |2: char a
          |3: jump 1
          |4: save 1
          |5: accept
          |
          |""".stripMargin
    }

    // Plus needs no forward patch at all — both its targets are known once the body is laid down —
    // which is why it is the arrangement most likely to be got wrong by one.
    "plus is the body once, then a split back to it" in {
      compiles("a+") shouldBe
        """0: save 0
          |1: char a
          |2: split 1, 3
          |3: save 1
          |4: accept
          |
          |""".stripMargin
    }

    "quest is a split over the body with no jump" in {
      compiles("a?") shouldBe
        """0: save 0
          |1: split 2, 3
          |2: char a
          |3: save 1
          |4: accept
          |
          |""".stripMargin
    }
  }

  "alternation is a split, the left branch, a jump past the right, and the right" in {
    compiles("a|b") shouldBe
      """0: save 0
        |1: split 2, 4
        |2: char a
        |3: jump 5
        |4: char b
        |5: save 1
        |6: accept
        |
        |""".stripMargin
  }

  "capture groups" - {

    "group k writes slots 2k and 2k+1" in {
      compiles("(a)(b)") shouldBe
        """0: save 0
          |1: save 2
          |2: char a
          |3: save 3
          |4: save 4
          |5: char b
          |6: save 5
          |7: save 1
          |8: accept
          |
          |""".stripMargin
    }

    "a nested group's saves sit inside its parent's" in {
      compiles("((a))") shouldBe
        """0: save 0
          |1: save 2
          |2: save 4
          |3: char a
          |4: save 5
          |5: save 3
          |6: save 1
          |7: accept
          |
          |""".stripMargin
    }
  }

  // An interval is expanded by the parser, so by the time the compiler sees it there is nothing
  // left to know about — which is the claim being checked: two copies of the body and no loop.
  "an interval is already gone by the time the compiler runs" in {
    compiles("a{2}") shouldBe
      """0: save 0
        |1: char a
        |2: char a
        |3: save 1
        |4: accept
        |
        |""".stripMargin
  }

  // The discriminating case for absolute program counters. The alternation inside the star has its
  // split at 3 rather than at 1, the star's exit is 9 rather than 4, and the group's saves fall
  // between them — every number here is displaced by the construct it sits inside.
  "a construct nested inside another has its targets shifted by what came before" in {
    compiles("(a|b)*c") shouldBe
      """0: save 0
        |1: split 2, 9
        |2: save 2
        |3: split 4, 6
        |4: char a
        |5: jump 7
        |6: char b
        |7: save 3
        |8: jump 1
        |9: char c
        |10: save 1
        |11: accept
        |
        |""".stripMargin
  }

  // An empty branch emits nothing at all, so the alternation's jump lands on the instruction after
  // itself — the case where a compiler that assumed every branch emits something goes wrong.
  "an empty branch emits no instructions" in {
    compiles("a|") shouldBe
      """0: save 0
        |1: split 2, 4
        |2: char a
        |3: jump 4
        |4: save 1
        |5: accept
        |
        |""".stripMargin
  }

  "a pattern that does not parse is refused before anything is emitted" in {
    compiles("(a") shouldBe "a group is never closed, opened at 0\n"
  }

  /** How long a program a pattern is allowed to become.
   *
   * The engine's promise is that no pattern makes *matching* blow up. Nothing about that bounds
   * **compiling**, and intervals stack multiplicatively: each `{n}` is expanded into `n` copies of
   * whatever precedes it, and the copies share one subtree — so the parse tree stays tiny while the
   * program the compiler lays out from it does not.
   *
   * The third of these is a sixteen-character pattern that asked for eight million instructions
   * before the limit existed, and the fourth is the same shape asking for a billion. Both are
   * refused at once rather than after the memory is spent, which is what the walk stopping rather
   * than merely the emit refusing is for.
   */
  "the size of the program a pattern may become" - {

    "a big but reasonable expansion is laid out" in {
      size("a{100}") shouldBe "103\n"
      size("a{100}{100}") shouldBe "10003\n"
    }

    "stacked intervals past the limit are refused rather than laid out" in {
      size("a{100}{100}{10}") shouldBe "the pattern expands past 100000 instructions\n"
      size("a{200}{200}{200}") shouldBe "the pattern expands past 100000 instructions\n"
      size("a{1000}{1000}{1000}") shouldBe "the pattern expands past 100000 instructions\n"
    }
  }
}
