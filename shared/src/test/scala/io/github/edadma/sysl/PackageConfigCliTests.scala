package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `package.hocon` at the seam a person actually meets it: a real file in a real directory, read by
 * the driver (`packages.md § 1`).
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
    Console.withOut(Discarded)(io.github.edadma.sysl.execute(cfg.copy(noStdLib = true)))

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
        cli(Config(command = "build", file = dir, output = Some(s"$dir/out"), stdSearch = unused)) shouldBe 0
      }

      exists(unused) shouldBe false
    finally
      for f <- listFiles(elsewhere) do deleteFile(f)
      deleteFile(elsewhere)
  }

  "the config names the target a build is for" in {
    project(
      "main()\n    print(1)\n",
      Some("targets { default = \"riscv64-linux\" }\n"),
    ) { dir =>
      // A cross build gets no further than clang here — this machine's has no RISC-V target — and
      // that is enough to see the answer. What is asserted is that the driver took the target from
      // the **file**: with the config unread the build is for this machine, it succeeds, and nothing
      // is said at all, so it is *which* machine is named that discriminates. The triple rather than
      // the name because that is what got as far as the toolchain, and both are read off the target
      // table rather than written out, so this says "the configured one, not this one" rather than
      // repeating a spelling.
      val riscv = Target.named("riscv64-linux").getOrElse(fail("riscv64-linux is not a target"))

      val (code, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      code should not be 0
      said should include(riscv.triple)
      said shouldNot include(Target.default.triple)
    }
  }

  "and --target beats the config, so a project with a default is still cross-buildable" in {
    project(
      "main()\n    print(1)\n",
      Some("targets { default = \"riscv64-linux\" }\n"),
    ) { dir =>
      val (_, said) = stderrOf(cli(Config(command = "run", file = dir, target = Some("x86_64-linux"))))

      said should include("x86_64-linux")
      said shouldNot include("riscv64-linux")
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
      said should include("this package requires 'alloc'")
    }
  }

  "a config that is there and will not read stops the build" in {
    project("main()\n    print(1)\n", Some("targets { default = \n")) { dir =>
      val (code, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      code should not be 0
      said should include(PackageConfig.FileName)
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
