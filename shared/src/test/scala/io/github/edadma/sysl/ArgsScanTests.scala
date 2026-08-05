package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.args.Scan` — the shape half of a command line, driven by hand.
 *
 * Every run here goes through a real compiled program started with real arguments, because that is
 * the only place the thing under test exists: `argv` is the platform's, and a scanner asserted
 * against a hand-built `[]string` would be asserting about the harness. `runWith` passes the words
 * after the executable's own path, which is exactly what a shell does.
 *
 * The program below reduces a whole scan to one line of text, so each case is a single exact
 * comparison rather than a shape to inspect. Every reported piece is spelled distinctly — `v` for a
 * short, `--n` for a long, `[p]` for an operand, `=v` for a value — so no two readings of the same
 * command line produce the same line.
 */
class ArgsScanTests extends AnyFreeSpec with RunSupport {

  /** A program that scans its own arguments and prints what it saw. `-o`/`--output` is the one
   * option that takes a value, which is what makes the four ways of writing one observable.
   */
  private val scanner =
    """import sysl.args.*
      |
      |main(args: []string)
      |    var a = scan(args)
      |    var out = ""
      |
      |    loop
      |        a.next() match
      |            Ok(Some(Short('o'))) | Ok(Some(Long("output"))) ->
      |                a.value() match
      |                    Ok(v)  -> out += " =" + v
      |                    Err(e) -> out += " !" + e.message()
      |            Ok(Some(Short(c)))      -> out += " " + string(c)
      |            Ok(Some(Long(n)))       -> out += " --" + n
      |            Ok(Some(Positional(p))) -> out += " [" + p + "]"
      |            Ok(None) -> break
      |            Err(e) ->
      |                out += " !" + e.message()
      |                break
      |
      |    print(out)
      |""".stripMargin

  private def scanned(args: String*): String = runWith(scanner, args*)

  "a short option" - {
    "is reported on its own" in {
      scanned("-v") shouldBe " v\n"
    }

    // The bundle: one word, three options, and the order they were written in.
    "bundles with the others in its word" in {
      scanned("-abc") shouldBe " a b c\n"
    }

    // Two bundles, so a scanner that kept the tail of one across a word would show it.
    "and a second bundle starts clean" in {
      scanned("-ab", "-cd") shouldBe " a b c d\n"
    }
  }

  "the value of an option is found wherever it was written" - {
    "attached to a long option after '='" in {
      scanned("--output=x") shouldBe " =x\n"
    }

    "as the word after a long option" in {
      scanned("--output", "x") shouldBe " =x\n"
    }

    "as the rest of a short option's word" in {
      scanned("-ox") shouldBe " =x\n"
    }

    "as the word after a short option" in {
      scanned("-o", "x") shouldBe " =x\n"
    }

    // The value comes out of the bundle's tail, so the options before it are still reported.
    "as the tail of a bundle the option ends" in {
      scanned("-vox") shouldBe " v =x\n"
    }

    // getopt's rule, and the one every program that has had to name a file `-` relies on: what a
    // value takes is the next word, whatever it looks like.
    "even where the next word looks like an option" in {
      scanned("-o", "--verbose") shouldBe " =--verbose\n"
    }

    // `--output=` is a value, and it is the empty one. Distinct from `--output` with nothing after
    // it, which is the error two cases below.
    "and an empty value is a value" in {
      scanned("--output=") shouldBe " =\n"
    }
  }

  "an operand" - {
    "is anything that does not start with a dash" in {
      scanned("a", "b") shouldBe " [a] [b]\n"
    }

    "may stand between options" in {
      scanned("a", "-v", "b") shouldBe " [a] v [b]\n"
    }

    // A lone `-` names standard input by a convention older than any of this, so it is an operand
    // rather than an option whose name is empty.
    "and a lone dash is one" in {
      scanned("-", "f") shouldBe " [-] [f]\n"
    }
  }

  "'--' ends the options" - {
    "so what follows it is an operand however it starts" in {
      scanned("--", "-v", "--output=x") shouldBe " [-v] [--output=x]\n"
    }

    "while what precedes it is read as usual" in {
      scanned("-v", "--", "-v") shouldBe " v [-v]\n"
    }

    // The separator itself is consumed rather than reported, and a second one is an ordinary
    // operand — it is only the first that means anything.
    "and a second one is just an operand" in {
      scanned("--", "--") shouldBe " [--]\n"
    }
  }

  "what the scanner reports as an error" - {
    "an option whose value is not there" in {
      scanned("-o") shouldBe " !-o requires a value\n"
    }

    // Named as it was written: `--output` here, `-o` above. A message naming the other spelling
    // reads as being about an option the user is not looking at.
    "the same through the long spelling, named the way it was written" in {
      scanned("--output") shouldBe " !--output requires a value\n"
    }

    // The case a scanner without a `Result` gets wrong. `--verbose=yes` at a program whose
    // `--verbose` takes nothing: the value cannot be reported as an operand and must not be
    // dropped, so going on to the next argument is what reveals it.
    "a value attached to an option that never asked for one" in {
      scanned("--verbose=yes") shouldBe " --verbose !--verbose takes no value\n"
    }
  }

  "the zeroth argument" - {
    // `scan` skips the program's own path, so a program started with nothing scans nothing —
    // where a scanner that did not skip would report the executable as an operand every time.
    "is not an argument, so an empty command line scans to nothing" in {
      scanned() shouldBe "\n"
    }

    // And `scan_all` is the other half of that: given a vector that did not come from the platform,
    // nothing is a program name. Two options in, two options out.
    "while 'scan_all' reads every word it is given" in {
      val src =
        """import sysl.args.*
          |
          |var a = scan_all(["-x", "-y"])
          |var out = ""
          |
          |loop
          |    a.next() match
          |        Ok(Some(Short(c))) -> out += " " + string(c)
          |        Ok(None) -> break
          |        else break
          |
          |print(out)
          |""".stripMargin

      run(src) shouldBe " x y\n"
    }
  }

  "'rest' takes everything left as operands" in {
    val src =
      """import sysl.args.*
        |
        |main(args: []string)
        |    var a = scan(args)
        |
        |    a.next() match
        |        Ok(Some(Short(c))) -> print("first", c)
        |        else print("first ?")
        |
        |    var out = ""
        |
        |    for r in a.rest()
        |        out += " [" + r + "]"
        |
        |    print(out)
        |""".stripMargin

    // `-v` is read as an option; from there on `-x` is a word like any other.
    runWith(src, "-v", "a", "-x") shouldBe "first v\n [a] [-x]\n"
  }

  /** A cursor is a value, so copying one copies where it is. This is the property that makes
   * looking ahead possible without a `peek` of its own — and the one that makes `for arg in a`
   * wrong, which is why `Scan` implements no `Iterate`.
   */
  "a copy of a cursor is a second cursor over the same words" in {
    val src =
      """import sysl.args.*
        |
        |main(args: []string)
        |    var a = scan(args)
        |    var look = a
        |
        |    look.next() match
        |        Ok(Some(Short(c))) -> print("ahead", c)
        |        else print("ahead ?")
        |
        |    a.next() match
        |        Ok(Some(Short(c))) -> print("still", c)
        |        else print("still ?")
        |""".stripMargin

    runWith(src, "-a", "-b") shouldBe "ahead a\nstill a\n"
  }
}
