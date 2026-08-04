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

  /** The driver under a name of its own — `Suite` has an `execute` too, and it wins unqualified. */
  private def cli(cfg: Config): Int = Console.withOut(Discarded)(io.github.edadma.sysl.execute(cfg))

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

  "the config names the target a build is for" in {
    project(
      "main()\n    print(1)\n",
      Some("targets { default = \"riscv64-linux\" }\n"),
    ) { dir =>
      // A cross build gets no further than the standard module here — this machine's clang has no
      // RISC-V target — and that is enough to see the answer. What is asserted is that the driver
      // took the target from the **file**: with the config unread the build is for this machine, it
      // succeeds, and nothing is said at all, so naming the configured machine is what discriminates.
      val (code, said) = stderrOf(cli(Config(command = "build", file = dir, output = Some(s"$dir/out"))))

      code should not be 0
      said should include("riscv64-linux")
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
