package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The standard library's **own** `@test` functions, run as part of this suite (`testing.md` Tier 3).
 *
 * `lib/` is packed with tests written in sysl, and they are the right place for what is true of the
 * *library* — that a `Buf` grows geometrically, that `truncate` keeps its storage. This is the one
 * test that makes them part of the gate: without it they would be a thing somebody could run rather
 * than a thing that runs, and a library test that broke would break nothing.
 *
 * **What stays in Scala is what a sysl test cannot honestly assert**, and the line is not a matter of
 * taste. A `@test` runs code the compiler under test produced and asserts with `assert_eq`, which
 * that same compiler produced — so a miscompiled `==` makes the assertion pass. Everything asserting
 * a *language* claim therefore stays where the expectation is written in another language and
 * compared by another runtime: the whole of `err*` (a program is refused, with this diagnostic),
 * the whole of `ir*` (this is the IR), and every run-tier test whose subject is a construct rather
 * than a library function.
 *
 * The gain is not only tidiness. A run-tier Scala test compiles, links and executes a program of its
 * own; these compile the library **once** and then cost a process each, which is why sixteen of them
 * run in the time one `RunSupport.run` takes to reach `clang`.
 */
class StdSelfTests extends AnyFreeSpec with Matchers {

  /** Below this, assume something has gone wrong rather than that the library got quieter.
   *
   * `TestRunner` treats a tree with no tests as a success and says so on stderr — correctly, since a
   * program is allowed to have none. That makes "the library's tests all passed" true of a `lib/`
   * whose test files had been deleted, which is exactly the silent-green this test exists to
   * prevent. A floor is the cheap guard: it needs no maintenance as tests are added, and it fails
   * loudly if a whole file stops being collected.
   *
   * **Raise it when a batch adds a module's worth of tests**, or it stops doing its job: a floor far
   * below the real count still passes with a whole file missing, which is the one thing it is for.
   * Raised from 60 to 120 when `sysl.slices`, `sysl.encoding` and `sysl.rand` took the library from
   * 89 collected tests to 136, and to 155 when the UTF-8 encoder, `Buf.insert` and the sub-second
   * durations took it to 163.
   */
  private val floor = 155

  /** The library, compiled as a **test build of itself**.
   *
   * `building` is what says the tree in front of the compiler *is* the standard module rather than a
   * program compiled against one — the same word `build-lib --std` uses, and the same set. Without
   * it every declaration in `lib/` collides with the copy the compiler supplies, which is what
   * `sysl test lib` did before `--std` existed.
   */
  private def libraryTests(): List[TestRunner.Outcome] = {
    assume(Toolchain.clangAvailable, "clang not available")

    val root = StdRoot.root

    assume(root.isDefined, "lib/ is not reachable from the working directory")

    val target = Target.default

    val Stdlib.Resolved(std, precompiled, _) =
      Stdlib.resolve(Stdlib.Choice.FromSource, target) match {
        case Right(c)  => c
        case Left(err) => fail(s"no standard module to compile against:\n$err")
      }

    val (built, tests) =
      Compiler.compileTests(Project.collect(root.get), Nil, target, precompiled, Some(std),
                            LibraryArtifact.std) match {
        case Right(result) => result
        case Left(err)     => fail(s"the standard library did not compile as a test build:\n$err")
      }

    val exe = createTempFile("sysl-std-test-", "")

    try
      Toolchain.build(built.ir, exe, target, Nil, links = built.links) match {
        case Left(err) => fail(s"the standard library's test build did not link:\n$err")
        case Right(_)  => TestRunner.execute(exe, tests, TestRunner.Options())
      }
    finally
      try deleteFile(exe)
      catch case _: Exception => ()
  }

  /** Run once. The compile is the slow half and both assertions below want the same outcomes, so
   * paying for it twice would double the cost of this file for nothing.
   */
  private lazy val outcomes: List[TestRunner.Outcome] = libraryTests()

  "the standard library's own tests" - {

    "all pass" in {
      outcomes.filterNot(_.passed) match {
        case Nil => succeed
        case bad =>
          fail(bad.map(o => s"${o.test.display}: ${o.detail.getOrElse("")}\n${o.output}").mkString("\n"))
      }
    }

    // Separate from the assertion above, because they fail for different reasons and a reader wants
    // to know which. "A library test broke" is a defect in the library; "the tests stopped being
    // found" is a defect in the collection, and it would otherwise read as everything passing.
    s"number at least $floor, so an empty run cannot read as a green one" in {
      outcomes.length should be >= floor
    }
  }
}
