package io.github.edadma.sysl

import io.github.edadma.sysl.api.Sysl

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
          |val base: int = 21
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
      Sysl.compileBody("val n: int") match
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
