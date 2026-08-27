package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl run` keeps what it built, so running the same program twice costs the second one nothing
 * (card `0309`, `RunCache`).
 *
 * **What a cache has to be right about is the MISS, not the hit.** A hit that should have been a
 * miss is a stale binary — a program that runs the code it had before the edit, silently — which is
 * worse than any amount of slowness. So most of what is below is about the key changing: every input
 * that reaches the bytes is perturbed in turn and the entry count is what says whether the key
 * noticed.
 *
 * The cache directory is this suite's own, which is what makes counting entries meaningful at all.
 */
class RunCacheTests extends AnyFreeSpec with Matchers {

  private def withCache[T](body: String => T): T = {
    val cache = createTempDirectory("sysl-runcache-")

    Fetch.usingCache(cache)(RunCache.usingCache(cache)(body(cache)))
  }

  /** The driver, run against a cache of the test's own. `Fetch.usingCache` moves the package cache
   * and `RunCache.usingCache` moves this one, so nothing here reaches the developer's.
   */
  private def ran(cfg: Config): String = {
    val out    = new java.io.ByteArrayOutputStream
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(out)(Console.withErr(notes)(sh.sysl.execute(cfg)))

    if status != 0 then fail(s"the driver exited with $status:\n${out.toString}${notes.toString}")

    out.toString
  }

  private def entries(cache: String): Int =
    if isDirectory(s"$cache/sysl/run") then listFiles(s"$cache/sysl/run").length else 0

  private def program(text: String): String = {
    val root = createTempDirectory("sysl-run-")

    writeFile(s"$root/main.sysl", text)
    root
  }

  "the same program run twice is built once" in withCache { cache =>
    {
      val root = program("""print(21 * 2)""")

      ran(Config(command = "run", file = root)) shouldBe "42\n"
      entries(cache) shouldBe 1

      ran(Config(command = "run", file = root)) shouldBe "42\n"
      entries(cache) shouldBe 1
    }
  }

  "and the arguments it is given are not part of the key, which is the point" in withCache { cache =>
    {
      val root = program("""main(args: []string)
                           |    print(args[1])
                           |""".stripMargin)

      ran(Config(command = "run", file = root, programArgs = List("a"))) shouldBe "a\n"
      ran(Config(command = "run", file = root, programArgs = List("b"))) shouldBe "b\n"

      entries(cache) shouldBe 1
    }
  }

  "an edit is a different program" in withCache { cache =>
    {
      val root = program("""print(21 * 2)""")

      ran(Config(command = "run", file = root)) shouldBe "42\n"

      writeFile(s"$root/main.sysl", """print(21 * 3)""")

      ran(Config(command = "run", file = root)) shouldBe "63\n"
      entries(cache) shouldBe 2
    }
  }

  /** **A comment is an edit.** The key is over the file's text rather than over anything the parser
   * decided, which is the conservative direction: a key that tried to see through a comment would be
   * a key that had to be right about what a comment is.
   */
  "including one the program's behaviour does not depend on" in withCache { cache =>
    {
      val root = program("""print(21 * 2)""")

      ran(Config(command = "run", file = root)) shouldBe "42\n"

      writeFile(s"$root/main.sysl", "// a note\nprint(21 * 2)")

      ran(Config(command = "run", file = root)) shouldBe "42\n"
      entries(cache) shouldBe 2
    }
  }

  "and so is a second file beside it, which the first one's text says nothing about" in withCache { cache =>
    {
      val root = program("""import m.*
                           |
                           |print(double(21))
                           |""".stripMargin)

      createDirectories(s"$root/m")
      writeFile(s"$root/m/m.sysl", "module m\n\ndouble(n: int) -> int = n * 2\n")

      ran(Config(command = "run", file = root)) shouldBe "42\n"

      writeFile(s"$root/m/m.sysl", "module m\n\ndouble(n: int) -> int = n * 3\n")

      ran(Config(command = "run", file = root)) shouldBe "63\n"
      entries(cache) shouldBe 2
    }
  }

  "the optimization level changes what is emitted, so it changes the key" in withCache { cache =>
    {
      val root = program("""print(21 * 2)""")

      ran(Config(command = "run", file = root)) shouldBe "42\n"
      ran(Config(command = "run", file = root, optimize = "2")) shouldBe "42\n"

      entries(cache) shouldBe 2
    }
  }

  /** The escape hatch, which is for working **on the compiler** — where the version in the key
   * stands still while the bytes it produces do not.
   */
  "'SYSL_NO_CACHE' builds every time and keeps nothing" in withCache { cache =>
    {
      RunCache.disabledFor {
        val root = program("""print(21 * 2)""")

        ran(Config(command = "run", file = root)) shouldBe "42\n"
        ran(Config(command = "run", file = root)) shouldBe "42\n"

        entries(cache) shouldBe 0
      }
    }
  }

  /** `sysl test` is the same shape and the case the card says the cost is felt in most often — a
   * suite that recompiles on every run. It needs **two** things from the cache, because the
   * executable does not carry what to call in it, so the sidecar is written beside it.
   */
  "a test suite run twice is built once, and the report is the same either way" in withCache { cache =>
    {
      val root = program("""@test("two doubled is four")
                           |doubling() =
                           |    assert(2 * 2 == 4)
                           |
                           |@test
                           |adding() =
                           |    assert(1 + 1 == 2)
                           |""".stripMargin)

      val first = ran(Config(command = "test", file = root))

      entries(cache) shouldBe 2

      val second = ran(Config(command = "test", file = root))

      second shouldBe first
      first should include("two doubled is four")
      first should include("2 passed")
      entries(cache) shouldBe 2
    }
  }

  /** The filter picks from the list rather than deciding what is compiled, so it is not in the key —
   * and a cached run has to apply it exactly as a fresh one does, which is why `rerun` is the tail
   * of `run` rather than a second implementation.
   */
  "and a filter is applied to a cached suite as it is to a fresh one" in withCache { cache =>
    {
      val root = program("""@test
                           |doubling() =
                           |    assert(2 * 2 == 4)
                           |
                           |@test
                           |adding() =
                           |    assert(1 + 1 == 2)
                           |""".stripMargin)

      ran(Config(command = "test", file = root)) should include("2 passed")

      val filtered = ran(Config(command = "test", file = root, filter = Some("doubling")))

      filtered should include("1 passed")
      filtered should not include "adding"
      entries(cache) shouldBe 2
    }
  }

  "a 'run' and a 'test' of one tree are two entries, since they are two builds" in withCache { cache =>
    {
      val root = program("""print(21 * 2)
                           |
                           |@test
                           |arithmetic() =
                           |    assert(1 + 1 == 2)
                           |""".stripMargin)

      ran(Config(command = "run", file = root)) shouldBe "42\n"
      ran(Config(command = "test", file = root)) should include("1 passed")

      entries(cache) shouldBe 3
    }
  }

  /** `build` writes a binary somebody named and is expected to have built it; `build-c` and
   * `build-lib` write artifacts for somebody else's toolchain. None of them is something a reader
   * would want quietly skipped, so none of them consults this.
   */
  "no other command keeps anything" in withCache { cache =>
    {
      val root = program("""print(21 * 2)""")
      val out  = createTempDirectory("sysl-out-")

      ran(Config(command = "build", file = root, output = Some(s"$out/app")))
      entries(cache) shouldBe 0
    }
  }
}
