package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behaviour of `sysl.process`: the part that needs somebody outside the program to
 * be looking.
 *
 * **`library/sysl/process/tests.sysl` covers the module itself** — what a status means, what comes
 * back from a capture, that a missing program is an error rather than an exit code. What a `@test`
 * cannot see is the *order* of a program's own output against its children's, because both go to
 * the same place and the test would be reading them through whatever it had already interleaved.
 * This suite compiles a program, runs it, and reads its standard output as one stream, which is the
 * only vantage point from which the question can be asked.
 */
class ProcessRunTests extends AnyFreeSpec with RunSupport {

  /** The regression this suite exists for.
   *
   * A C library buffers standard output **fully** rather than by line whenever the destination is
   * not a terminal, which is every pipe, every file and every CI log. The child writes to the same
   * file description directly and is buffered by nothing of ours, so a parent that had not flushed
   * would have its own earlier text arrive *after* the child's — and it would look, in the log, as
   * though the program never said what it was about to do.
   *
   * `RunSupport` captures rather than allocating a terminal, so this suite is in exactly the state
   * that provokes it: without the `fflush(NULL)` before the fork, the first line here lands third.
   */
  "a program's own output is flushed before a child it starts can write" in {
    val src =
      """import sysl.process.run
        |
        |print("parent first")
        |run("echo", ["child second"])
        |print("parent last")""".stripMargin

    run(src) shouldBe "parent first\nchild second\nparent last\n"
  }

  /** The same question asked of two children, so that a fix which happened to flush once cannot
   * pass: each child's output has to land between the two things the parent said around it.
   */
  "each child lands where the parent's output says it should" in {
    val src =
      """import sysl.process.run
        |
        |print("one")
        |run("echo", ["two"])
        |print("three")
        |run("echo", ["four"])
        |print("five")""".stripMargin

    run(src) shouldBe "one\ntwo\nthree\nfour\nfive\n"
  }

  /** A capture writes to a file of the module's own rather than to this program's output, so
   * nothing of the child's appears in the stream until the parent prints it — the opposite
   * arrangement to the tests above, and the one that would break if `capture` ever stopped
   * redirecting.
   */
  "a captured child says nothing on its own account" in {
    val src =
      """import sysl.process.capture
        |
        |print("before")
        |val out = capture("echo", ["captured"]).unwrap()
        |print("after")
        |prints(out.text)""".stripMargin

    run(src) shouldBe "before\nafter\ncaptured\n"
  }
}
