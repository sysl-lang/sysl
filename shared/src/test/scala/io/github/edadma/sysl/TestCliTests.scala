package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl test` driven through the driver itself — the seam a user meets.
 *
 * What `TestRunnerTests` cannot reach is here: the exit status the command leaves, what it prints
 * when there is nothing to run, and that a test build refuses the same things every other build
 * refuses. All of it lives in `execute` and in `TestRunner.run`, and a test that assembled the steps
 * itself would be pinning its own arrangement rather than the one the command performs.
 */
class TestCliTests extends AnyFreeSpec with Matchers {

  /** The driver, under a name of its own — `Suite` has an `execute` too, and it wins unqualified.
   *
   * These run in a tree where no standard module has been built, so everything here says
   * `--no-core-lib`: *this test is about the test command, not about which standard module a
   * compilation gets.*
   */
  private def cli(cfg: Config): Int = Console.withOut(Discarded)(driver(cfg))

  /** The same run with stdout left where it was, for `ran` below. Everything else throws it away —
   * the report `sysl test` prints is this suite's subject, not something to read off the console.
   */
  private def driver(cfg: Config): Int =
    io.github.edadma.sysl.execute(cfg.copy(noCoreLib = true))

  private def program(text: String): String = {
    val path = createTempFile("sysl-test-cli-", ".sysl")
    writeFile(path, text)
    path
  }

  /** A run with both streams captured. The report goes to stdout and the driver's own complaints to
   * stderr, and a test about "what does it say when there is nothing to run" is about the second.
   */
  private def ran(cfg: Config): (Int, String, String) = {
    val out = new java.io.ByteArrayOutputStream
    val errs = new java.io.ByteArrayOutputStream

    val status = Console.withOut(out)(Console.withErr(errs)(driver(cfg)))

    (status, out.toString, errs.toString)
  }

  private val passing =
    """@test
      |arithmetic_holds() =
      |    assert(1 + 1 == 2, "two")
      |
      |@test("a trap is what a broken promise leaves")
      |a_broken_promise() =
      |    assert(false, "down")
      |""".stripMargin

  "the status is what a caller reads" - {
    "a run where everything passes exits 0" in {
      assume(Toolchain.clangAvailable, "clang not available")

      cli(Config(command = "test", file = program("""@test
                                                    |t() =
                                                    |    assert(true, "up")
                                                    |""".stripMargin))) shouldBe 0
    }

    "a run with a failure exits 1" in {
      assume(Toolchain.clangAvailable, "clang not available")

      cli(Config(command = "test", file = program(passing))) shouldBe 1
    }

    // A test is ordinary code and takes a visibility like anything else, and a file-private one is
    // emitted with `internal` linkage (`13 §2`). The runner reaches it from the same module, so that
    // costs it nothing — but it is worth pinning, because a runner that resolved a test by symbol
    // from outside would fail on exactly this and on nothing else.
    "a file-private test is still one the runner reaches" in {
      assume(Toolchain.clangAvailable, "clang not available")

      cli(Config(command = "test", file = program("""@test
                                                    |private t() =
                                                    |    assert(true, "up")
                                                    |""".stripMargin))) shouldBe 0
    }

    // A tree with no tests is not a failure — a program is allowed to have none — but it is worth
    // saying, because the alternative is a silent exit 0 that looks exactly like a run that passed.
    "a tree with no tests exits 0 and says so" in {
      val (status, _, errs) = ran(Config(command = "test", file = program("""print("nothing to test")""")))

      status shouldBe 0
      errs should include("no '@test' functions")
    }

    "a filter that matches nothing says how many there were" in {
      val (status, _, errs) =
        ran(Config(command = "test", file = program(passing), filter = Some("no such test")))

      status shouldBe 0
      errs should include("2 to choose from")
    }
  }

  "the report is what a reader gets" - {
    "it names each test and counts the outcome" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val (_, out, _) = ran(Config(command = "test", file = program(passing)))

      out should include("arithmetic_holds")
      out should include("a trap is what a broken promise leaves")
      out should include("1 passed, 1 failed")
    }

    "a filter narrows both the run and the count" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val (status, out, _) =
        ran(Config(command = "test", file = program(passing), filter = Some("arithmetic")))

      status shouldBe 0
      out should include("running 1 test of 2")
      out should not include "broken promise"
    }
  }

  "a test build is a build, and is refused for the same reasons" - {
    "a program that does not compile is reported rather than run" in {
      val (status, _, errs) =
        ran(Config(command = "test", file = program("""@test
                                                      |t() =
                                                      |    print(undefined_name)
                                                      |""".stripMargin)))

      status shouldBe 1
      errs should include("undefined name 'undefined_name'")
    }

    // `test` runs what it builds, exactly as `run` does, so a cross target has nothing to run the
    // result with. Refused before the compile rather than after it.
    "a cross target is refused, since there would be nothing here to run it" in {
      val other = Target.all.find(t => !Target.host.contains(t) && t.supported)

      other match {
        case None => cancel("no cross target to test against")
        case Some(t) =>
          val (status, _, errs) =
            ran(Config(command = "test", file = program(passing), target = Some(t.name)))

          status shouldBe 1
          errs should include("not this machine")
      }
    }
  }
}
