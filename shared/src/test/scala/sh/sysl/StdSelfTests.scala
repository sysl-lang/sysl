package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The standard library's **own** `@test` functions, run as part of this suite (`testing.md` Tier 3).
 *
 * `library/` is packed with tests written in sysl, and they are the right place for what is true of the
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
   * program is allowed to have none. That makes "the library's tests all passed" true of a `library/`
   * whose test files had been deleted, which is exactly the silent-green this test exists to
   * prevent. A floor is the cheap guard: it needs no maintenance as tests are added, and it fails
   * loudly if a whole file stops being collected.
   *
   * **Raise it when a batch adds a module's worth of tests**, or it stops doing its job: a floor far
   * below the real count still passes with a whole file missing, which is the one thing it is for.
   * Raised from 60 to 120 when `sysl.slices`, `sysl.encoding` and `sysl.rand` took the library from
   * 89 collected tests to 136, and to 155 when the UTF-8 encoder, `Buf.insert` and the sub-second
   * durations took it to 163, and to 165 when the duration unit properties and the arithmetic
   * nothing had been exercising took it to 169, and to 195 when the line editor and the two
   * in-memory stream types took it to 201, and to 220 when `sysl.posix.time` took it to 227.
   *
   * That raise closed a drift as much as it made room: the tree had reached 222 while the floor
   * still read 195, so a twenty-test file could have stopped being collected with nothing said. The
   * gap this guard wants is the handful the entries above all left, not the two dozen it had grown.
   * Raised again to 224 when `sysl.fs` got a `tests.sysl` and took the tree to 231, and to 234
   * when `sysl.posix.tty` got one and took it to 234, and to 236 when `sysl.fs` gained the two
   * `size` tests — those raises leave no slack on purpose, because raising to exactly the new count
   * is what proves a new file or test is collected at all rather than silently skipped. Raised to
   * **317** when `sysl.container` arrived: five modules with a `tests.sysl` each — `map`, `set`,
   * `deque`, `heap` and `list` — plus the line editor's history cap, which is the first test of a
   * bound that module always had and nothing reached.
   *
   * Raised to **330** when the zone surface arrived: `sysl.time`'s `resolve` — seven tests against a
   * synthetic zone, needing no host — and `sysl.posix.time`'s four against whatever zone the machine
   * running them is set to. Exactly the new count again, for the reason above.
   *
   * Raised to **343** when the zone database arrived: `sysl.time.tzif` decodes a TZif file and gets
   * seven, `sysl.env` four, and `sysl.posix.time` two more for reading a real zone off the host.
   * `tzif`'s carry their zone as **bytes**, so they assert the format rather than the machine — the
   * only tests in the library that would pass on a target with no filesystem at all.
   *
   * **`sysl.fs`'s are the first tests that depend on the library's own C**, so this floor now guards
   * a second thing: a build that stopped compiling or stopped linking the shim under
   * `library/sysl/fs/__<os>__` fails outright rather than quietly collecting fewer tests.
   *
   * Raised to **433** when `sysl.math.matrix` arrived with 43 of its own. That raise also closed a
   * drift of the kind this comment has recorded once before and which had grown larger than it: the
   * tree was collecting 390 while the floor still read 343, so a whole module's worth could have
   * stopped being found with nothing said. The number below is the tree's exact count again, which
   * is the only setting that proves a new file is collected rather than silently skipped.
   */
  private val floor = 433

  /** The library, compiled as a **test build of itself**.
   *
   * `building` is what says the tree in front of the compiler *is* the standard module rather than a
   * program compiled against one — the same word `build-lib --std` uses, and the same set. Without
   * it every declaration in `library/` collides with the copy the compiler supplies, which is what
   * `sysl test library` did before `--std` existed.
   */
  private def libraryTests(): List[TestRunner.Outcome] = {
    assume(Toolchain.clangAvailable, "clang not available")

    val root = StdRoot.root

    assume(root.isDefined, "the library is not reachable from the working directory")

    val target = Target.default

    val Stdlib.Resolved(std, precompiled, _) =
      Stdlib.resolve(Stdlib.Choice.FromSource, target) match {
        case Right(c)  => c
        case Left(err) => fail(s"no standard module to compile against:\n$err")
      }

    val (built, tests) =
      Compiler.compileTests(Project.collect(root.get, Some(target.os)), Nil, target, precompiled, Some(std),
                            LibraryArtifact.std) match {
        case Right(result) => result
        case Left(err)     => fail(s"the standard library did not compile as a test build:\n$err")
      }

    // **The library's own C** (`15 §7`), which it may carry exactly as any other tree may — the shim
    // under `library/sysl/fs/__<os>__` is what answers `entries`, and `sysl.fs`'s own `@test`s call
    // it. The driver does this for a real compilation off `Stdlib.Resolved`'s answer about whether an
    // artifact supplied the standard module; here the tree is being compiled from source by
    // construction, so it is unconditional.
    val native =
      NativeSources.build(NativeSources.of(List(root.get), target.os), target) match {
        case Left(err)    => fail(s"the standard library's C did not compile:\n$err")
        case Right(built) => built
      }

    val exe = createTempFile("sysl-std-test-", "")

    try
      Toolchain.build(built.ir, exe, target, Nil, links = built.links, objects = native.objects) match {
        case Left(err) => fail(s"the standard library's test build did not link:\n$err")
        case Right(_)  => TestRunner.execute(exe, tests, TestRunner.Options())
      }
    finally
      native.scratch.foreach(Project.discard)

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
