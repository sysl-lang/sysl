package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `package.hocon` at the seam a person actually meets it: a real file in a real directory, read by
 * the driver (`reference/packages.md`).
 *
 * `PackageConfigTests` asks what the text means and `TargetCapabilityTests` asks what a capability
 * set does to a compilation. Neither of them would notice if the driver never looked for the file,
 * looked in the wrong place, or read it and threw the answer away — which is the whole of what is
 * asserted here.
 */
class PackageConfigCliTests extends AnyFreeSpec with Matchers {

  /** The driver under a name of its own — `Suite` has an `execute` too, and it wins unqualified.
   *
   * Everything here says `--no-std-lib`: *this test is about `package.hocon`, not about which
   * standard module a compilation gets.* Without it these runs fall through to
   * `LibraryArtifact.stdDefault` — the **user's own cache** — and a suite that builds an artifact
   * there is writing outside its own temporary directories and racing every other compilation on the
   * machine for one file. `LibraryCliSupport` and `TestCliTests` each say the same thing for the
   * same reason.
   */
  private def cli(cfg: Config): Int =
    Console.withOut(Discarded)(sh.sysl.execute(cfg.copy(noStdLib = true)))

  /** A project directory holding a program and, where one is given, a config beside it. */
  private def project(program: String, config: Option[String])(check: String => Unit): Unit = {
    val dir = createTempDirectory("sysl-package-")

    try
      writeFile(s"$dir/main.sysl", program)
      for text <- config do writeFile(s"$dir/${PackageConfig.FileName}", text)
      check(dir)
    finally
      // Whatever is in there, not the two files written above: a build that got as far as linking
      // leaves its executable behind, and the directory has to be empty before it will go.
      for f <- listFiles(dir) do deleteFile(f)
      deleteFile(dir)
  }

  /** What the driver said on stderr, which is where a refusal goes. */
  private def stderrOf(body: => Int): (Int, String) = {
    val out = new java.io.ByteArrayOutputStream

    val code = Console.withErr(out)(body)

    (code, out.toString)
  }

  "a project with no config builds exactly as it always did" in {
    project("main()\n    print(1)\n", None) { dir =>
      cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))) shouldBe 0
    }
  }

  "and builds it without going looking for a standard module to link" in {
    // What a compilation that *did* look for one leaves behind is an artifact at the search path,
    // because nothing usable is ever there and the driver builds one rather than refusing
    // (`Main.foundStd`). So an empty directory left empty is the observation — and the reason it
    // matters is where the search path points when nobody names one: `LibraryArtifact.stdDefault`,
    // in the user's own cache, which this suite spent a day writing into.
    val elsewhere = createTempDirectory("sysl-package-std-")
    val unused    = s"$elsewhere/std${LibraryArtifact.extension}"

    try
      project("main()\n    print(1)\n", None) { dir =>
        cli(Config(command = "build", file = dir, output = Some(s"$dir/out"), stdSearch = Some(unused))) shouldBe 0
      }

      exists(unused) shouldBe false
    finally
      for f <- listFiles(elsewhere) do deleteFile(f)
      deleteFile(elsewhere)
  }

  /** Two hosted targets that are not the machine this suite is running on, whichever that is.
    *
    * **Written out rather than named because a fixed pair cannot be cross everywhere.** These tests
    * used `riscv64-linux` and `x86_64-linux`, which are both cross from the author's Mac and one of
    * which is the *host* on an x86-64 Linux runner — where the driver then builds happily, says
    * nothing, and the assertion that it complained fails. The claim being made is "a target other
    * than this machine's", so that is what the fixture computes.
    */
  private def elsewhere: (Target, Target) =
    List(Target.aarch64MacOS, Target.x86_64MacOS, Target.aarch64Linux, Target.x86_64Linux, Target.riscv64Linux)
      .filterNot(Target.host.contains) match
      case a :: b :: _ => (a, b)
      case _           => fail("no two targets are foreign to this machine")

  "the config names the target a build is for" in {
    val (configured, _) = elsewhere

    project(
      "main()\n    print(1)\n",
      Some(s"""targets { default = "${configured.name}" }\n"""),
    ) { dir =>
      // `run` rather than `build`, because it is the **driver** that refuses to run what it cannot
      // execute, before any of this reaches clang. That matters for what the assertion can say: a
      // failed cross *link* is worded by whatever linker the machine has -- macOS names the triple,
      // GNU ld says `unrecognised emulation mode: elf64lriscv` and names nothing -- so asserting on
      // it made the test a claim about a toolchain. The driver's own refusal names the target.
      //
      // What discriminates is *which* machine is named: with the config unread the build is for this
      // one and nothing is said at all.
      val (code, said) = stderrOf(cli(Config(command = "run", file = dir)))

      code should not be 0
      said should include(configured.name)
      said shouldNot include(Target.default.name)
    }
  }

  "and --target beats the config, so a project with a default is still cross-buildable" in {
    val (configured, overridden) = elsewhere

    project(
      "main()\n    print(1)\n",
      Some(s"""targets { default = "${configured.name}" }\n"""),
    ) { dir =>
      val (_, said) = stderrOf(cli(Config(command = "run", file = dir, target = Some(overridden.name))))

      said should include(overridden.name)
      said shouldNot include(configured.name)
    }
  }

  "a capability the config turns off reaches the compilation" in {
    project(
      "f() -> &int = 1\n\nmain()\n    print(*f())\n",
      Some(
        """targets {
          |  default = "aarch64-macos"
          |  aarch64-macos { capabilities { alloc = false } }
          |}
          |""".stripMargin),
    ) { dir =>
      val (code, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      code should not be 0
      said should include("a reference needs an allocator")
      said should include("provides no allocator")
    }
  }

  "the package's own 'requires' is answered against the machine, before a line is parsed" in {
    project(
      "main()\n    print(1)\n",
      Some(
        """requires { alloc = true }
          |targets {
          |  default = "aarch64-macos"
          |  aarch64-macos { capabilities { alloc = false } }
          |}
          |""".stripMargin),
    ) { dir =>
      val (code, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      code should not be 0
      // The config wrote `alloc`, which is the module's word and is accepted transitionally; the
      // diagnostic names the capability it was mapped to, since that is the one the reader has to
      // write from now on.
      said should include("this package requires 'heap'")
    }
  }

  /** A heap asked for on a machine with no libc to supply one (card `0329`).
    *
    * The refusal cannot come from `requires` alone: `PackageConfig.provides` defaults every
    * capability to provided, so a freestanding target says it has a heap and the `unmet` test above
    * is empty. What is asserted here is the *other* condition — that sysl is the one doing the link
    * — and each case below turns exactly one part of it off.
    */
  private val freestandingHeap =
    """requires { heap = true }
      |targets { default = "wasm32-freestanding" }
      |""".stripMargin

  "a heap on a target with no libc is refused before the linker sees it" in {
    project("main()\n    print(1)\n", Some(freestandingHeap)) { dir =>
      val (code, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      code should not be 0
      said should include("requires a heap")
      said should include("'wasm32-freestanding' is freestanding")
      // The two symbols by name, because what a reader was getting was a wall of `undefined symbol`
      // lines about exactly these and nothing connecting them to the clause they wrote.
      said should include("'malloc'")
      said should include("'free'")
      // And the way out, which is a block they can write rather than a target they cannot change.
      said should include("'allocator' block")
      // Nothing reached the toolchain: the refusal is the whole output.
      said shouldNot include("wasm-ld")
    }
  }

  "and naming a heap of your own is the way out" in {
    project(
      "main()\n    print(1)\n",
      Some(freestandingHeap + """allocator { alloc = "heap_alloc", free = "heap_free" }""" + "\n"),
    ) { dir =>
      val (_, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      // Not `shouldBe 0` — whether a wasm link succeeds on this machine is a question about the
      // toolchain, and this suite is not asking it. What is asserted is that the driver stopped
      // holding the project back.
      said shouldNot include("requires a heap")
    }
  }

  "declaring libc's own pair is not a way out, because the symbols still come from nowhere" in {
    project(
      "main()\n    print(1)\n",
      Some(freestandingHeap + """allocator { alloc = "malloc", free = "free" }""" + "\n"),
    ) { dir =>
      val (code, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      code should not be 0
      said should include("requires a heap")
    }
  }

  "a hosted target is untouched, whatever the package requires" in {
    project(
      "main()\n    print(1)\n",
      Some("requires { heap = true }\n"),
    ) { dir =>
      cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))) shouldBe 0
    }
  }

  /** The condition that keeps every board repo in the org building. `build-c` writes an archive and
    * CMake or Gradle links it, so the allocator arrives from somewhere the compiler cannot see —
    * which is why the test is `links`, not the target.
    */
  "and an archive is not refused, because sysl is not the one linking it" in {
    project("@export(\"main\")\nmain()\n    print(1)\n", Some(freestandingHeap)) { dir =>
      val (_, said) = stderrOf(cli(Config(command = "build-c", file = dir, output = Some(s"$dir/out"))))

      said shouldNot include("requires a heap")
    }
  }

  "a config that is there and will not read stops the build" in {
    project("main()\n    print(1)\n", Some("targets { default = \n")) { dir =>
      val (code, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      code should not be 0
      said should include(PackageConfig.FileName)
    }
  }

  /** The root project's own floor, at the seam. `ResolveTests` covers a **dependency**'s, which is
   * the case the field exists for; this is the one that proves the driver asks at all — every
   * command's config comes through one funnel, and nothing else here would notice if it stopped
   * looking.
   */
  "a project stating a compiler newer than the one in hand is refused" in {
    project("main()\n    print(1)\n", Some("package { name = \"app\", sysl = \"99.0.0\" }\n")) { dir =>
      val (code, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      code should not be 0
      said should include("this project cannot be built because it requires sysl 99.0.0 or newer")
      said should include("while the compiler in hand is")
    }
  }

  "and one this compiler satisfies says nothing at all" in {
    project("main()\n    print(1)\n", Some("package { name = \"app\", sysl = \"0.0.1\" }\n")) { dir =>
      cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))) shouldBe 0
    }
  }

  "a name that is not a capability is refused rather than ignored" in {
    project(
      "main()\n    print(1)\n",
      Some("targets { kernel { capabilities { treads = false } } }\n"),
    ) { dir =>
      val (code, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      code should not be 0
      said should include("'treads'")
      said should include("is not a capability")
    }
  }
}
