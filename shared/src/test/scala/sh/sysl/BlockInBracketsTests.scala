package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A token that opens an indented block opens one **inside brackets too**.
 *
 * A bracket pair suspends the off-side rule until it closes, which is what lets an argument list be
 * laid out however reads best. A block opened inside such a list is the one place that is wrong: the
 * body's own margin is the only thing saying where the block ends, so the newline, indent and dedent
 * have to be emitted after all. Without them a `match` written as an argument was refused with
 * *newline expected*, pointing at its first arm — and the workaround was to bind it to a name that
 * said nothing, one line above the call.
 *
 * The two triggers are `match` and `->`, and both are here because one of them would be a rule
 * nobody could state: a language admitting a match's arms as an argument and refusing a closure's
 * body asks a reader to remember which block forms may be written where.
 */
class BlockInBracketsTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a match may be an argument" - {
    "the case the workaround was written for" in {
      val src =
        """name(n: int)
          |    print(n match
          |        0 -> "none"
          |        1 -> "one"
          |        else "many")
          |
          |name(0)
          |name(1)
          |name(7)""".stripMargin

      run(src) shouldBe "none\none\nmany\n"
    }

    // The bracket rule resumes at the block's dedent, so what follows is an ordinary argument list
    // again — which is the half that would be missing if the frame were never restored.
    "and an argument may follow it" in {
      val src =
        """pair(a: string, b: string) = print(a + "/" + b)
          |
          |f(n: int) =
          |    pair(n match
          |        0 -> "zero"
          |        else "other", "tail")
          |
          |f(0)
          |f(3)""".stripMargin

      run(src) shouldBe "zero/tail\nother/tail\n"
    }

    "a parenthesized sub-expression is the same case" in {
      val src =
        """f(n: int) =
          |    val s = "<" + (n match
          |        0 -> "zero"
          |        else "other") + ">"
          |
          |    print(s)
          |
          |f(0)""".stripMargin

      run(src) shouldBe "<zero>\n"
    }

    "and so is an array literal, which is a bracket of the other kind" in {
      val src =
        """f(n: int) -> []string
          |    [n match
          |        0 -> "a"
          |        else "b", "tail"]
          |
          |print(f(0)[0], f(0)[1])""".stripMargin

      run(src) shouldBe "a tail\n"
    }
  }

  "an arrow may open a closure's body as an argument" - {
    "a body of several statements, which had nowhere to go before" in {
      val src =
        """apply(f: int -> int, n: int) -> int = f(n)
          |
          |print(apply((x) ->
          |    val doubled = x * 2
          |
          |    doubled + 1, 5))""".stripMargin

      run(src) shouldBe "11\n"
    }

    "a closure whose body is itself a match" in {
      val src =
        """apply(f: int -> string, n: int) -> string = f(n)
          |
          |print(apply((x) -> x match
          |    0 -> "zero"
          |    else "other", 4))""".stripMargin

      run(src) shouldBe "other\n"
    }
  }

  "what the bracket rule still does" - {
    // The whole point of suspending layout inside brackets: an argument list laid out over several
    // lines has no block in it, and its margins mean nothing.
    "an argument list with no block in it is joined exactly as before" in {
      val src =
        """add(a: int, b: int, c: int) -> int = a + b + c
          |
          |print(add(1,
          |        2,
          |    3))""".stripMargin

      run(src) shouldBe "6\n"
    }

    // `:` is not a trigger, so the trailing-block form still has to be named before a call takes it.
    "a trailing block is still not written inside brackets" in {
      val src =
        """total(xs: []int) -> int = xs[0]
          |
          |print(total:
          |    1
          |    2)""".stripMargin

      err(src) should not be empty
    }

    // The one thing the rule costs, and the whole of it: a function *type* whose line is broken
    // immediately after its arrow used to be joined and is now read as opening a block. Nothing in
    // the tree is written that way — all 161 arrow-terminated lines in `library/`, `guide/` and
    // `examples/` are match arms — and breaking the line anywhere else still joins.
    "a function type may not be broken immediately after its arrow" in {
      err("""apply(f: int ->
            |    int, n: int) -> int = f(n)
            |
            |print(apply((x) -> x + 1, 5))
            |""".stripMargin) should not be empty
    }

    "and breaking it before the arrow, or at a comma, still joins" in {
      run("""apply(f: int
            |    -> int, n: int) -> int = f(n)
            |
            |print(apply((x) -> x + 1, 5))""".stripMargin) shouldBe "6\n"

      run("""apply(f: int -> int,
            |    n: int) -> int = f(n)
            |
            |print(apply((x) -> x + 1, 5))""".stripMargin) shouldBe "6\n"
    }

    "a one-line match as an argument is unchanged" in {
      val src =
        """f(n: int) = print(if n == 0 then "zero" else "other")
          |
          |f(0)""".stripMargin

      run(src) shouldBe "zero\n"
    }
  }

  // The trigger only fires inside a bracket pair, so an ordinary block is reached by the road it
  // always was and the two cannot have drifted.
  "a block outside any bracket is the one it always was" in {
    val src =
      """f(n: int) =
        |    val s = n match
        |        0 -> "zero"
        |        else "other"
        |
        |    print(s)
        |
        |f(0)""".stripMargin

    run(src) shouldBe "zero\n"
  }
}
