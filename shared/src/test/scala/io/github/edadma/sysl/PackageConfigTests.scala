package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The project config of `packages.md § 1–2`: what `package.hocon` says, and what a file that says
 * it wrongly is told.
 *
 * Reading is a pure function of the file's text, so every case here runs identically on all three
 * platforms — which is the property `cross-platform.md` asks of anything below the driver, and the
 * reason the parse is separated from finding the file at all.
 */
class PackageConfigTests extends AnyFreeSpec with Matchers {

  private def read(text: String): PackageConfig =
    PackageConfig.read(text) match
      case Right(c) => c
      case Left(e)  => fail(s"expected a config, got: $e")

  private def refused(text: String): String =
    PackageConfig.read(text) match
      case Left(e)  => e
      case Right(c) => fail(s"expected a refusal, got: $c")

  "a project may have no file at all, and every capability is then provided" in {
    PackageConfig.empty.provides("aarch64-macos") shouldBe Set("alloc", "os", "posix", "threads")
  }

  "an empty file is a project that said nothing" in {
    read("") shouldBe PackageConfig.empty
  }

  "identity" - {

    "a name and a version" in {
      val c = read(
        """package {
          |  name    = "geom"
          |  version = "1.4.2"
          |}
          |""".stripMargin)

      c.name shouldBe Some("geom")
      c.version shouldBe Some("1.4.2")
    }

    "and neither is required" in {
      read("targets { default = \"x86_64-linux\" }").name shouldBe None
    }
  }

  "targets" - {

    "'default' names one rather than being one" in {
      val c = read(
        """targets {
          |  default = "x86_64-linux"
          |}
          |""".stripMargin)

      c.defaultTarget shouldBe Some("x86_64-linux")
      c.targets shouldBe empty
    }

    "a block may declare a machine the registry does not have" in {
      val c = read(
        """targets {
          |  aarch64-kernel {
          |    triple = "aarch64-none-elf"
          |  }
          |}
          |""".stripMargin)

      c.targets("aarch64-kernel").triple shouldBe Some("aarch64-none-elf")
    }

    "a capability set is read, and what it does not mention stays provided" in {
      val c = read(
        """targets {
          |  aarch64-kernel {
          |    triple = "aarch64-none-elf"
          |    capabilities { os = false, posix = false }
          |  }
          |}
          |""".stripMargin)

      c.provides("aarch64-kernel") shouldBe Set("alloc", "threads")
    }

    "a target the file says nothing about provides everything" in {
      val c = read(
        """targets {
          |  aarch64-kernel { capabilities { alloc = false } }
          |}
          |""".stripMargin)

      c.provides("x86_64-linux") shouldBe Set("alloc", "os", "posix", "threads")
      c.provides("aarch64-kernel") shouldBe Set("os", "posix", "threads")
    }

    "a capability that is not one is refused, rather than quietly doing nothing" in {
      val e = refused(
        """targets {
          |  kernel { capabilities { treads = false } }
          |}
          |""".stripMargin)

      e should include("'treads'")
      e should include("is not a capability")
      e should include("'alloc', 'os', 'posix', 'threads'")
    }

    "a capability that is not true or false" in {
      refused(
        """targets {
          |  kernel { capabilities { alloc = "no" } }
          |}
          |""".stripMargin) should include("must be true or false")
    }

    "a target that is a name where a block was expected" in {
      refused(
        """targets {
          |  kernel = "aarch64-none-elf"
          |}
          |""".stripMargin) should include("is not a block describing a target")
    }
  }

  "a package's own requires block" - {

    "names what it needs of whatever builds it" in {
      read("requires { os = true }").requires shouldBe Set("os")
    }

    "and folds in what that implies — posix needs an operating system under it" in {
      read("requires { posix = true }").requires shouldBe Set("os", "posix")
    }

    "a false entry asks for nothing rather than forbidding it" in {
      read("requires { os = false }").requires shouldBe empty
    }

    "a name that is not a capability" in {
      refused("requires { sockets = true }") should include("is not a capability")
    }
  }

  "a file that is not HOCON at all is one line rather than a stack trace" in {
    val e = refused("package { name = ")

    e should startWith("package.hocon:")
    e shouldNot include("Exception")
  }
}
