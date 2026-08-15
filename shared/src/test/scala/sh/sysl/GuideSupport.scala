package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers

/** Runs a program out of `guide/`, which unlike every other suite here is real source on disk.
 *
 * That is the point of those programs: they are written the way a program is written, in files, at
 * a size no test string would hold, and what they are for is to find out where the language stops
 * being pleasant to write. Compiling them from the tree is also what keeps them honest — a guide
 * program that only existed inside a test could quietly stop being a program.
 *
 * They are read through `Project.collect`, so each directory is a project root exactly as `sysl run`
 * makes one.
 */
trait GuideSupport extends Matchers { this: Assertions =>

  /** Compiles and runs `guide/<name>`, returning its stdout.
   *
   * The tree is found relative to the working directory, so the suite **cancels** rather than fails
   * where it is not there — a platform whose test runner starts somewhere else has nothing to say
   * about the language.
   */
  protected def guide(name: String): String = {
    val dir = s"guide/$name"

    assume(Toolchain.clangAvailable, "clang not available")
    assume(isDirectory(dir), s"$dir is not reachable from the working directory")

    Toolchain.compileAndRun(Project.collect(dir, Some(Target.default.os))) match {
      case Right((0, out))    => out
      case Right((code, out)) => fail(s"$dir exited with $code:\n$out")
      case Left(err)          => fail(s"$dir did not compile:\n$err")
    }
  }

  /** Runs the `@test` functions of `guide/<name>` and fails naming any that did not pass
   * (`testing.md`).
   *
   * This is the half of a guide program its own run cannot assert: a refusal traps, and a trap ends
   * the run rather than reporting a failure into it. Driven through the same functions the CLI
   * drives, so what is asserted here is what `sysl test guide/<name>` does.
   */
  protected def guideTests(name: String): List[TestRunner.Outcome] = {
    val dir = s"guide/$name"

    assume(Toolchain.clangAvailable, "clang not available")
    assume(isDirectory(dir), s"$dir is not reachable from the working directory")

    val (built, tests) = Compiler.compileTests(Project.collect(dir, Some(Target.default.os)), Nil) match {
      case Right(result) => result
      case Left(err)     => fail(s"$dir did not compile:\n$err")
    }

    val exe = createTempFile("sysl-guide-test-", "")

    val ran =
      try
        Toolchain.build(built.ir, exe, links = built.links) match {
          case Left(err) => fail(s"$dir did not link:\n$err")
          case Right(_)  => TestRunner.execute(exe, tests, TestRunner.Options())
        }
      finally
        try deleteFile(exe)
        catch case _: Exception => ()

    ran.filterNot(_.passed) match {
      case Nil => ran
      case bad => fail(bad.map(o => s"${o.test.display}: ${o.detail.getOrElse("")}").mkString("\n"))
    }
  }
}
