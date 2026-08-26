package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** What `sysl run` gives the program it built — which is more than the rest of the suite watches,
 * because everywhere else a program's output is the only thing anybody asks about.
 *
 * The command ran programs through `exec`, the function the compiler uses for **tools**: `clang`,
 * `git`, `llvm-ar`, each of them run for a value the compiler goes on to inspect. `exec` closes the
 * child's input before it starts, which is exactly right for a tool that has nothing to read and
 * exactly wrong for a program the user asked to run — so every sysl program that reads was handed
 * end-of-input at once. A `wc` read nothing from the pipe it was given, and a console printed its
 * banner and exited.
 *
 * The input is copied from `Console.in`, which is why these tests can drive it: what the program
 * reads is what this suite wrote, and `Console.withIn` is dynamically scoped, so a suite running
 * beside this one is unaffected in a way a `System.setIn` would not be.
 */
class RunCommandTests extends AnyFreeSpec with Matchers {

  private def program(text: String): String = {
    val path = createTempFile("sysl-run-", ".sysl")

    writeFile(path, text)
    path
  }

  /** The driver's `run`, with the program's input supplied and its output caught.
   *
   * `--no-std-lib` because these tests are about the command rather than about which standard module
   * a compilation gets, and the tree they run in has not built one.
   */
  private def ran(text: String, input: String): (Int, String) = {
    val out = new java.io.ByteArrayOutputStream

    val status = Console.withIn(new java.io.StringReader(input))(
      Console.withOut(out)(Console.withErr(Discarded)(
        sh.sysl.execute(Config(command = "run", file = program(text), noStdLib = true)))))

    (status, out.toString)
  }

  "a program run by the driver reads what the driver was given" in {
    val (status, out) = ran(
      """import sysl.io.{stdin, lines}
        |
        |main()
        |    var input = stdin()
        |    var count = 0
        |
        |    for line in lines(&input)
        |        count += 1
        |
        |    print(count)
        |""".stripMargin,
      "one\ntwo\nthree\n")

    status shouldBe 0
    out shouldBe "3\n"
  }

  // The bytes and not merely the count: what a program reads is what was written, in order, which is
  // the whole of what a pipe promises.
  "and what it reads is what was written" in {
    val (status, out) = ran(
      """import sysl.io.{stdin, lines}
        |
        |main()
        |    var input = stdin()
        |
        |    for line in lines(&input)
        |        print(line)
        |""".stripMargin,
      "alpha\nbeta\n")

    status shouldBe 0
    out shouldBe "alpha\nbeta\n"
  }

  // A program that reads nothing is unaffected by any of it, which is the case every other suite in
  // the tree is made of — and the one that would notice a driver waiting on an input nobody sends.
  "a program that reads nothing still runs and still answers" in {
    ran("print(21 * 2)\n", "") shouldBe (0, "42\n")
  }
}
