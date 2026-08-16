package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** **What the driver does before what** — the order of its steps, asserted through the order of what
 * it says.
 *
 * Ordering inside `execute` is invisible to every other suite: each step is right, each is tested
 * where it lives, and a compilation that performs them in the wrong sequence still reaches the same
 * answer whenever every step succeeds. It stops being invisible exactly when one of them **fails**,
 * because then the reader gets the first failure rather than the relevant one.
 *
 * **The library carrying C is what made that reachable** (`13 §5`, card 0135). A shim under a
 * `__<os>__` directory is selected by the *target's* system and compiled by the host's clang, so a
 * cross-system build asks this machine to compile another system's C against headers it has not got.
 * That is a real limitation and not the bug: cross-compiling C needs a sysroot, and none of these
 * commands could have produced a working binary anyway. The bug is that the complaint arrived
 * *first*, in place of the one the reader needed.
 *
 * **CI is where it showed, and it showed as two unrelated-looking failures.** On Linux the first
 * cross target in the registry is `aarch64-macos`, so a `sysl test` refusal and a capability
 * diagnostic both came back as `library/sysl/fs/__macos__/dirent.c did not compile`. The mirror
 * holds here — targeting Linux from macOS fails on `__linux__/dirent.c` and `'dirent.h' file not
 * found` — which is what lets these run on either machine.
 */
class DriverOrderTests extends AnyFreeSpec with Matchers {

  private def cli(cfg: Config): Int =
    Console.withOut(Discarded)(sh.sysl.execute(cfg.copy(noStdLib = true)))

  /** What the driver said on stderr, which is where a refusal goes. */
  private def stderrOf(body: => Int): (Int, String) = {
    val out  = new java.io.ByteArrayOutputStream
    val code = Console.withErr(out)(body)

    (code, out.toString)
  }

  private def program(source: String)(check: String => Unit): Unit = {
    val dir = createTempDirectory("sysl-order-")

    try
      writeFile(s"$dir/main.sysl", source)
      check(dir)
    finally
      for f <- listFiles(dir) do deleteFile(f)
      deleteFile(dir)
  }

  /** A target whose **operating system** is not this one's, which is what makes the library's C
   * uncompilable here and so what makes these tests able to see the order at all. A target that is
   * merely a different processor is no use: its C is this system's and compiles perfectly.
   */
  private val crossSystem: Option[Target] =
    Target.host.flatMap(h => Target.all.find(t => t.supported && t.buildsWithClang && t.os != h.os))

  "a build reports what is wrong with the program" - {

    "rather than what is wrong with a shim it was never going to reach" in {
      crossSystem match
        case None => cancel("no cross-system target to build for")
        case Some(t) =>
          program("main()\n    print(undefined_name)\n") { dir =>
            val (code, said) =
              stderrOf(cli(Config(command = "build", file = dir, target = Some(t.name),
                output = Some(s"$dir/out"))))

            code should not be 0
            said should include("undefined name 'undefined_name'")
            said should not include "did not compile"
          }
    }
  }

  "a test refuses a cross target before anything is compiled for it" - {

    // `TestRunner` keeps a check of its own, since it is reachable without this driver — but it is
    // *handed* the objects a tree's C compiled to, so its check can only ever run after the C. The
    // refusal has to be made by whoever decides to compile.
    "and says so in the words of the refusal" in {
      crossSystem match
        case None => cancel("no cross-system target to test against")
        case Some(t) =>
          program("@test\nworks()\n    assert_eq(1, 1)\n") { dir =>
            val (code, said) =
              stderrOf(cli(Config(command = "test", file = dir, target = Some(t.name))))

            code should not be 0
            said should include("not this machine")
            said should not include "did not compile"
          }
    }
  }
}
