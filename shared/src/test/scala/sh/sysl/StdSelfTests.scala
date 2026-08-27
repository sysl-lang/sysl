package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The standard library's **own** `@test` functions, run as part of this suite
 * (`reference/attributes.md § @test — a function with a caller nothing else has`).
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
   *
   * Raised to **439** when `sysl.text`'s parse family gained its `[]const u8` forms — six tests,
   * which is what a facility rather than a module costs.
   *
   * Raised to **457** when `sysl.process` arrived with 18 of its own. Like `sysl.fs`'s, they depend
   * on the library's own C — the shim under `library/sysl/process/__posix__` is what decodes how a
   * child ended — so they guard the same second thing that comment names. They also guard something
   * no other test in the tree does: that `fork` and `execvp` reach a real program and come back,
   * which cannot be asserted without starting one.
   *
   * Raised to **482** when `Chars` stopped trusting `char_width` over bytes nobody validated -- nine
   * tests for the walk, the count, `peek`, `char_indices`' offsets, and the agreement between all of
   * them and `from_utf8_lossy`, which is the only thing that says three readers of one table read it
   * the same way.
   *
   * **Nine of those sixteen were the drift again, and it is the third time this comment has recorded
   * it.** The tree was collecting 473 while the floor still read 457, so a module's worth could have
   * stopped being found with nothing said -- which is the exact failure the number exists to catch,
   * and it cannot catch it from below. **Read the count rather than adding to the last one**: set
   * the floor absurdly high for one run and the failure names it (`482 was not greater than or equal
   * to 99999`), which is one run and settles it. Adding your own tests to a number nobody measured
   * carries the drift forward, which is how it got here twice before.
   *
   * Raised to **487** when `Vector[T]` and `Matrix[T]` stopped gathering their renderings: two of
   * those are the specifier split and the agreement between a matrix and its own rows, and **three
   * were drift again** -- the tree was at 485 against a floor of 482, from `Complex[F]`'s rendering
   * tests the day before. That is the fourth time, and it was found by the measurement this comment
   * prescribes rather than by adding two to the number above it.
   *
   * **LOWERED to 442 when `sysl.math.matrix` left the library**, which is the first time this number
   * has gone down and the one direction the guard cannot check for itself: a floor that is too high
   * fails loudly and a floor left too high after a removal fails *every* run, so the temptation is to
   * drop it far enough to be safe and stop thinking. That is the drift this comment has recorded four
   * times, arrived at from the other side.
   *
   * So it is measured, not subtracted: 45 tests left with the module, `grep -rh "^@test(" library
   * --include="*.sysl" | wc -l` reads **442**, and the gap of two is the same handful the raises
   * above kept. The module is `sh.sysl.linalg` now -- a leaf nothing else in `library/` imported, and
   * the only domain in a library otherwise made of the platform and of what all code touches.
   *
   * Raised to **448**, and with no slack this time, when `sysl.slices` gained `align_up` and
   * `is_aligned` with six tests: the measurement above reads 448, and taking the number exactly is
   * what proves the six are collected rather than merely that nothing vanished. The gap the entry
   * above kept was the residue of a removal, not a target to hold.
   *
   * Raised to **457**, again with no slack, when `Buf[T]` and `[]T` became `Eq` and a `Buf` became
   * `Display`: nine tests, four for the slice and the array in the root module's own file and five
   * for the buffer. The measurement above reads 457 and the runner agrees, which is what says the
   * nine are collected rather than that nothing vanished.
   *
   * Raised to **467** when `sysl.io` gained `read_all`, `read_all_text` and `read_exact` — the
   * whole-stream reads the surface was missing — with eight tests in the module's **first** test
   * file of its own. Every one of them is really about a reader that answers *short*, which is what
   * a pipe with one write in it, a socket and a terminal all do, and is the case a hand-rolled loop
   * gets wrong; `bytes_reader_at_most` is the fixture that poses it.
   *
   * **Two of the ten were drift again, and that is the fifth time.** The measurement read 467 with
   * eight added, so the tree was at 459 against a floor of 457 — from the two the `Buf`/`Eq` entry
   * above says it took exactly. Taken exactly again here.
   *
   * Raised to **479** when `Option` and `Result` gained the transforming combinators — `map`,
   * `and_then`, `or_else`, `unwrap_or_else` on both, `filter` and `ok_or` on the option, `map_err`,
   * `ok` and `err` on the result. Twelve tests, each asserting **both** variants, because a
   * combinator that is right about the present case and wrong about the absent one is exactly the
   * shape that survives a casual test. Measured, no slack.
   */
  private val floor = 479

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

    // **The library's own C** (`reference/ffi.md § A library may carry C`), which it may carry
    // exactly as any other tree may — the shim under `library/sysl/fs/__<os>__` is what answers
    // `entries`, and `sysl.fs`'s own `@test`s call it. The driver does this for a real compilation
    // off `Stdlib.Resolved`'s answer about whether an artifact supplied the standard module; here
    // the tree is being compiled from source by construction, so it is unconditional.
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
