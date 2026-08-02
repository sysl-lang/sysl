package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import scopt.OParser

/** `--optimize`, at the two seams that matter: what an argument list parses to, and what a program
 * built at a level actually does.
 *
 * The level exists because a build used to ask for none at all, and `-O0` is a different instruction
 * selector rather than merely a slower one — the mode a back end's own suite covers least, and the
 * one a miscompile was found living in (`Toolchain.defaultOptimization` holds the case). So what is
 * asserted here is that the default is not `0` and that naming another reaches clang.
 */
class OptimizeCliTests extends AnyFreeSpec with Matchers {

  private def parse(args: String*): Option[Config] = OParser.parse(parser, args, Config())

  /** The driver under a name of its own — `Suite` has an `execute` too, and it wins unqualified. */
  private def cli(cfg: Config): Int = io.github.edadma.sysl.execute(cfg)

  private val program =
    """import sysl.math.Bits
      |
      |spin[T: Bits](x: T, n: u32) -> T = x.rotate_left(n)
      |
      |main()
      |    var a: u8 = 0b10110000
      |    print(spin(a, 1), a.count_ones(), (0x0123456789abcdefu64).rotate_right(8))
      |""".stripMargin

  "what an argument list says" - {

    "a build that names no level asks for the default" in {
      parse("build", "prog.sysl").map(_.optimize) shouldBe Some(Toolchain.defaultOptimization)
      Toolchain.defaultOptimization should not be "0"
    }

    "and one that names a level carries it, whichever of clang's spellings it is" in {
      for level <- List("0", "2", "3", "s", "z", "fast") do
        withClue(level) { parse("build", "prog.sysl", "--optimize", level).map(_.optimize) shouldBe Some(level) }
    }

    // The flag belongs to every command that produces code, not to `build` alone — `run` links an
    // executable and `test` links one too, and a level that reached only one of them would be a
    // level that silently stopped applying when you changed command.
    "every command that emits code accepts it" in {
      for command <- List("run", "build", "build-lib", "test") do
        withClue(command) { parse(command, "prog.sysl", "--optimize", "2").map(_.optimize) shouldBe Some("2") }
    }

    // What is after `--` is the program's, so a program with an option of the same name still gets
    // its own — the split happens before this parser ever sees the arguments.
    "and the driver's own split keeps the program's arguments out of it" in {
      val (own, forwarded) = List("run", "prog.sysl", "--optimize", "2", "--", "--optimize", "9").span(_ != "--")

      OParser.parse(parser, own, Config()).map(_.optimize) shouldBe Some("2")
      forwarded.drop(1) shouldBe List("--optimize", "9")
    }
  }

  "what a level does to a program" - {

    // The answer is the program's and not the optimizer's, so every level has to agree on it. This
    // is also the only assertion here that would have caught the miscompile the default exists for.
    "every level it can be built at answers the same thing" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val path = createTempFile("sysl-optimize-", ".sysl")
      writeFile(path, program)

      try
        val answers =
          for level <- List("0", "1", "2", "s")
          yield
            val out = new java.io.ByteArrayOutputStream
            val status = Console.withOut(out)(
              cli(Config(command = "run", file = path, noCoreLib = true, optimize = level)))

            withClue(s"-O$level") { status.shouldBe(0) }
            out.toString

        answers.distinct shouldBe List("97 3 17222085231038278605\n")
      finally deleteFile(path)
    }

    // A level clang has no answer for is clang's to report, and the driver's job is to fail rather
    // than to carry on having quietly dropped it.
    "a level clang does not have stops the build" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val path = createTempFile("sysl-optimize-", ".sysl")
      writeFile(path, "main()\n    print(1)\n")

      try
        val errs = new java.io.ByteArrayOutputStream
        val status = Console.withErr(errs)(
          cli(Config(command = "run", file = path, noCoreLib = true, optimize = "nonsense")))

        status.should(not).be(0)
        errs.toString should include("clang")
      finally deleteFile(path)
    }
  }
}
