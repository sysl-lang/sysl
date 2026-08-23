package sh.sysl

import sh.sysl.api.Sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The compiler used as a **library**, handed source as a string (`Sysl`).
 *
 * The driver takes paths, which is right for a program on disk and wrong for a tool that generated
 * the source it wants compiled. These pin the surface such a tool depends on: that a string is
 * enough, that a body needs no `main` written around it, that a failure comes back as the sentence
 * the driver would have printed, and that no signature here mentions a syntax tree — which is what
 * lets the language keep changing without breaking whatever was compiled against this.
 */
class SyslLibraryTests extends AnyFreeSpec with Matchers {

  "a whole program is compiled from a string" - {
    "and run" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.run("main()\n    print(1 + 2)") shouldBe Right(Sysl.Run(0, "3\n"))
    }

    "to LLVM IR, without a toolchain being involved at all" in {
      val ir = Sysl.compile("main()\n    print(1 + 2)")

      ir.map(_.contains("define")) shouldBe Right(true)
    }

    "and its exit status is what the platform reported" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.run("main()\n    exit(3)").map(_.exitCode) shouldBe Right(3)
    }

    "and it may be started with arguments" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.run("main(args: []string)\n    print(args[1])", args = List("hello")) shouldBe
        Right(Sysl.Run(0, "hello\n"))
    }
  }

  /* A body is what a documentation page shows and what a test writes inline: the statements, with
   * whatever declarations they need, and no wrapper — because the wrapper is not the thing being
   * taught. Supplying it is structural, which is the half a caller could not have done itself.
   */
  "a body needs no 'main' written around it" - {
    "the bare statements are enough" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.runBody("print(1 + 2)") shouldBe Right(Sysl.Run(0, "3\n"))
    }

    "declarations stay where they are and only the statements move" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.runBody(
        """double(n: int) -> int = n * 2
          |static val base: int = 21
          |print(double(base))""".stripMargin,
      ) shouldBe Right(Sysl.Run(0, "42\n"))
    }

    /* The case a textual split gets wrong, and the reason `Bodies` parses first: at a file's top
     * level `f()` is a call and `f() -> int = …` is a declaration, and nothing about the characters
     * tells them apart. Both appear here, the call above the declaration.
     */
    "a call is told from a declaration of the same name, which no string operation could do" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.runBody(
        """greet()
          |greet()
          |    print("hi")""".stripMargin,
      ) shouldBe Right(Sysl.Run(0, "hi\n"))
    }

    "and where the body declares its own 'main', the statements run first" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.runBody(
        """print("first")
          |main()
          |    print("second")""".stripMargin,
      ) shouldBe Right(Sysl.Run(0, "first\nsecond\n"))
    }

    "a body with no statements at all is a program that does nothing" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.runBody("double(n: int) -> int = n * 2") shouldBe Right(Sysl.Run(0, ""))
    }

    "and neither does an empty string, which is a body with nothing in it" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.runBody("") shouldBe Right(Sysl.Run(0, ""))
    }

    // The statements land at the front of the declared `main`'s body, so they precede it whatever
    // its shape — a `main` that reads the program's arguments is the shape most likely to have
    // something written above it.
    "the statements still run first where the declared 'main' takes the arguments" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.runBody(
        """print("first")
          |main(args: []string)
          |    print(args[1])""".stripMargin,
        args = List("second"),
      ) shouldBe Right(Sysl.Run(0, "first\nsecond\n"))
    }
  }

  "a program of several files is compiled from strings" - {
    "with the directory naming the module, since there is no filesystem to read it from" in {
      assume(Sysl.canRun, "clang not available")
      Sysl.runBodyFiles(List(
        Sysl.File("t.sysl", "module tables\nval k: [2]int = [5, 6]", List("tables")),
        Sysl.File("main.sysl", "print(tables.k[1])"),
      )) shouldBe Right(Sysl.Run(0, "6\n"))
    }

    // Each file is wrapped on its own, so two files carrying statements become two `main`s rather
    // than an order somebody has to guess at. The scaladoc says at most one file may carry them;
    // this is what happens to a caller who does not.
    "and a second file carrying statements is refused, since each is wrapped on its own" in {
      Sysl.compileBodyFiles(List(
        Sysl.File("a.sysl", "print(1)"),
        Sysl.File("b.sysl", "print(2)"),
      )) match
        case Left(e)  => e should include("main")
        case Right(_) => fail("expected a diagnostic")
    }

    "a program of no files at all is the empty program" in {
      Sysl.compileBodyFiles(Nil).map(_.contains("define")) shouldBe Right(true)
    }
  }

  "a failure comes back as the sentence the driver would have printed" - {
    "for a mistake the analyzer finds" in {
      Sysl.compile("main()\n    print(x)") match
        case Left(e)  => e should include("undefined name 'x'")
        case Right(_) => fail("expected a diagnostic")
    }

    "for one the parser finds" in {
      Sysl.compileBody("static val n: int") match
        case Left(e)  => e should not be empty
        case Right(_) => fail("expected a diagnostic")
    }

    "and every file's parse errors are reported together, not just the first file's" in {
      Sysl.compileBodyFiles(List(
        Sysl.File("a.sysl", "val n: int"),
        Sysl.File("b.sysl", "val m: int"),
      )) match
        case Left(e)  => e.linesIterator.size should be > 1
        case Right(_) => fail("expected a diagnostic")
    }
  }

  /** `check` is the other kind of caller: one that wants to *do* something with a mistake rather
   * than print it. Everything above answers with the paragraph the driver would have written, and a
   * paragraph cannot be taken apart into ranges — which is what an editor needs and what these pin.
   */
  "the mistakes in a program come back as data" - {

    "and there are none at all when it compiles" in {
      Sysl.check("main()\n    print(1)") shouldBe Nil
    }

    "one per mistake, each with the range of the token that is wrong" in {
      Sysl.check("var x = 1\nprint(nope)\n", "t.sysl") shouldBe
        List(Sysl.Problem("undefined name 'nope'", Some(Sysl.Span("t.sysl", 2, 7, 2, 11))))
    }

    "in source order, so a caller may show them in the order they are read" in {
      val src = "var a = nope1\nvar b = nope2\nvar c = nope3\n"

      Sysl.check(src, "t.sysl").flatMap(_.at).map(_.line) shouldBe List(1, 2, 3)
    }

    /* The five-diagnostic limit is the *renderer's* rule: a wall of them is the first few with the
     * signal falling off behind. A caller marking up a file has the opposite need — the ones left
     * out are exactly the ones with no underline — so nothing here truncates.
     */
    "every one of them, past the five a rendered report stops at" in {
      val src = (1 to 8).map(n => s"var v$n = nope$n").mkString("\n")

      Sysl.check(src) should have length 8
      Sysl.compile(src).swap.map(_.contains("showing the first 5 of 8 errors")) shouldBe Right(true)
    }

    "a parse error among them, pointing at the token that could not be read" in {
      Sysl.check("var a = 1\nvar = 5\n", "t.sysl") shouldBe
        List(Sysl.Problem("identifier expected", Some(Sysl.Span("t.sysl", 2, 5, 2, 6))))
    }

    "and a lexical one, which the grammar never gets to react to" in {
      Sysl.check("print(\"oops\n", "t.sysl") shouldBe
        List(Sysl.Problem("unterminated string literal", Some(Sysl.Span("t.sysl", 1, 7, 1, 7))))
    }

    // Not the analyzer's: escape analysis runs on the typed tree, after it, and its refusals used
    // to be rendered where they were raised — so a caller reading them as data is reading a stage
    // that had no data to give.
    "a refusal from a later pass carries its position too" in {
      val src = "sum(bytes: []u8) -> int = 0\n\nscratch(s: [4]u8) -> []u8\n    s[..]\n"
      val List(one) = Sysl.check(src, "t.sysl"): @unchecked

      one.message should include("would outlive the array")
      one.at.map(a => (a.line, a.col)) shouldBe Some((4, 6))
    }

    /* These two complain about a whole *function*, and the typed tree carried no position for one
     * until this surface needed it — so both used to point nowhere at all.
     *
     * They land on the **attribute** rather than on the signature under it, which is where the
     * declaration begins and is also the line the reader has to change: what is refused is the
     * `@export` and the `@tailrec`, not the function they are written above.
     */
    "an export refused for its signature points at the declaration" in {
      val src =
        """var first = 1
          |
          |@export
          |f(x: []int) -> int = 1
          |
          |print(f([1]))
          |""".stripMargin
      val List(one) = Sysl.check(src, "t.sysl"): @unchecked

      one.message should include("which C has no way to spell")
      one.at.map(_.line) shouldBe Some(3)
    }

    "and so does a '@tailrec' that is not one" in {
      val src =
        """var first = 1
          |
          |@tailrec
          |f(n: int) -> int = if n == 0 then 0 else 1 + f(n - 1)
          |
          |print(f(3))
          |""".stripMargin
      val List(one) = Sysl.check(src, "t.sysl"): @unchecked

      one.message should include("'@tailrec'")
      one.at.map(_.line) shouldBe Some(3)
    }
  }

  "what the library says about itself" - {
    "the version is the one the build stamped, which is what '--version' reports" in {
      Sysl.version shouldBe BuildInfo.version
    }

    // Compiling needs nothing; linking and running need clang. A caller that runs programs asks
    // first, so a missing toolchain is reported as a missing toolchain rather than as a bad program.
    "and whether this machine can build what it emits is answerable before trying" in {
      Sysl.canRun shouldBe Toolchain.clangAvailable
    }
  }
}
