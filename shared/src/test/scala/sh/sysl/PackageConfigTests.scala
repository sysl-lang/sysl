package sh.sysl

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

    /** The name is what a directory project's output is called, so it reaches the filesystem. Each
     * of these is refused rather than sanitized: a file that names its output was written by
     * somebody expecting an answer, and both silent repairs — falling back to the directory name, or
     * flattening the separator — write a different executable and say nothing.
     */
    "a name that is a path rather than a name is refused" in {
      refused("package { name = \"build/tool\" }") should include("names a path rather than a name")
      refused("package { name = \"..\\\\tool\" }") should include("names a path rather than a name")
    }

    // Both are legal path segments and neither names a file, so the link would fail at the far end
    // with a message about a directory instead of about this line.
    "and so are the two segments that name a directory" in {
      refused("package { name = \".\" }") should include("names a path rather than a name")
      refused("package { name = \"..\" }") should include("names a path rather than a name")
    }

    "an empty name is refused as its own mistake" in {
      refused("package { name = \"\" }") should include("is empty")
    }

    // A name with spaces or dots in it is awkward rather than wrong, and the shell can quote it.
    // Refusing it would be this check deciding what a project may call itself, which is not its job.
    "while an unusual but usable name is left alone" in {
      read("package { name = \"my tool\" }").name shouldBe Some("my tool")
      read("package { name = \"tool.v2\" }").name shouldBe Some("tool.v2")
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

  "the dependencies block" - {

    "a coordinate and a version" in {
      val deps = read(
        """dependencies {
          |  json { git = "github.com/edadma/sysl-json", version = "1.4.0" }
          |}""".stripMargin).dependencies

      deps shouldBe List(Dependency("json", Origin.Git("github.com/edadma/sysl-json", Version(1, 4, 0))))
    }

    "a path, for a package being written beside its consumer" in {
      val deps = read("""dependencies { local { path = "../experiment" } }""").dependencies

      deps shouldBe List(Dependency("local", Origin.Local("../experiment")))
    }

    "a mount, which is what a consumer writes when two packages want one name" in {
      val deps = read(
        """dependencies {
          |  regex { git = "github.com/edadma/sysl-regex/v2", version = "2.0.4", mount = "re" }
          |}""".stripMargin).dependencies

      deps.head.mount shouldBe Some("re")
    }

    "several, in the order a reader finds them rather than the order they were written" in {
      val deps = read(
        """dependencies {
          |  zeta { path = "../z" }
          |  alpha { path = "../a" }
          |}""".stripMargin).dependencies

      deps.map(_.label) shouldBe List("alpha", "zeta")
    }

    "what a coordinate has to be" - {

      "a URL is refused rather than stripped, because the coordinate is the identity" in {
        refused("""dependencies { j { git = "https://github.com/e/j", version = "1.0.0" } }""") should
          include("is a URL")
      }

      "a host with nothing under it" in {
        refused("""dependencies { j { git = "github.com", version = "1.0.0" } }""") should
          include("names a host and nothing under it")
      }

      "a trailing slash" in {
        refused("""dependencies { j { git = "github.com/e/j/", version = "1.0.0" } }""") should
          include("is not a coordinate")
      }
    }

    "the major version rides in the coordinate" - {

      "0.x and 1.x ride in the bare path" in {
        read("""dependencies { j { git = "github.com/e/j", version = "1.9.0" } }""")
          .dependencies.head.origin shouldBe Origin.Git("github.com/e/j", Version(1, 9, 0))
      }

      "2.x needs the suffix, or two majors would share one module name" in {
        refused("""dependencies { j { git = "github.com/e/j", version = "2.0.0" } }""") should
          include("no '/v2'")
      }

      "with the suffix it is accepted, and the suffix stays part of the coordinate" in {
        val dep = read("""dependencies { j { git = "github.com/e/j/v2", version = "2.0.0" } }""")
          .dependencies.head

        dep.origin shouldBe Origin.Git("github.com/e/j/v2", Version(2, 0, 0))
        dep.canonical shouldBe "github.com.e.j.v2"
      }

      "a suffix that disagrees with the version it is asked for" in {
        refused("""dependencies { j { git = "github.com/e/j/v3", version = "2.0.0" } }""") should
          include("holds 3.x and nothing else")
      }
    }

    "a version is three numbers" - {

      "two is not enough" in {
        refused("""dependencies { j { git = "github.com/e/j", version = "1.4" } }""") should
          include("is not a version")
      }

      "and a leading zero is a typo rather than a number" in {
        refused("""dependencies { j { git = "github.com/e/j", version = "01.4.2" } }""") should
          include("is not a version")
      }
    }

    "the two halves of an entry that decide each other" - {

      "both a coordinate and a path" in {
        refused("""dependencies { j { git = "github.com/e/j", version = "1.0.0", path = "../j" } }""") should
          include("cannot be both")
      }

      "neither" in {
        refused("""dependencies { j { mount = "x" } }""") should include("names neither")
      }

      "a coordinate with no version" in {
        refused("""dependencies { j { git = "github.com/e/j" } }""") should include("and no 'version'")
      }

      // Nothing could keep the promise: the directory is whatever it is on disk right now.
      "a path with a version" in {
        refused("""dependencies { j { path = "../j", version = "1.0.0" } }""") should
          include("nothing to resolve a version against")
      }
    }

    // The same judgement `requires` makes about a misspelled capability, and for a sharper reason:
    // ignoring the key would resolve the default branch while the author believed they had pinned
    // a version.
    "a misspelled key is refused rather than ignored" in {
      refused("""dependencies { j { git = "github.com/e/j", versoin = "1.0.0" } }""") should
        include("is not something a dependency says")
    }

    "one thing named twice" - {

      "the same coordinate under two labels" in {
        refused(
          """dependencies {
            |  a { git = "github.com/e/j", version = "1.0.0" }
            |  b { git = "github.com/e/j", version = "1.2.0" }
            |}""".stripMargin) should include("one package is one dependency")
      }

      "two packages mounted under one root name" in {
        refused(
          """dependencies {
            |  a { path = "../a", mount = "json" }
            |  b { path = "../b", mount = "json" }
            |}""".stripMargin) should include("cannot share a root name")
      }
    }

    "a mount has to be a name a module could have" in {
      refused("""dependencies { j { path = "../j", mount = "sysl.json" } }""") should
        include("not a name a module can have")
    }

    "an entry that is not a block at all" in {
      refused("""dependencies { j = "github.com/e/j" }""") should include("is not a block")
    }
  }

  "how a coordinate becomes something git can clone" - {

    // The suffix is identity and not location: there is no branch or directory called v2 in the
    // repository, so it comes off before the URL is built.
    "the major suffix comes off" in {
      Dependency.cloneUrl("github.com/e/j/v2") shouldBe "https://github.com/e/j.git"
    }

    "and a coordinate without one is left alone" in {
      Dependency.cloneUrl("github.com/e/j") shouldBe "https://github.com/e/j.git"
    }

    "v0 and v1 are not suffixes — the first two majors ride in the bare path" in {
      Dependency.majorSuffix("github.com/e/j/v1") shouldBe None
      Dependency.majorSuffix("github.com/e/j/v2") shouldBe Some(2)
      Dependency.majorSuffix("github.com/e/j") shouldBe None
    }

    // A repository whose own name ends in something v-shaped is not a major suffix.
    "a path segment that merely starts with a v" in {
      Dependency.majorSuffix("github.com/e/vector") shouldBe None
      Dependency.cloneUrl("github.com/e/vector") shouldBe "https://github.com/e/vector.git"
    }

    "a version knows the tag it is published under" in {
      Version(2, 1, 0).tag shouldBe "v2.1.0"
    }
  }

  "a file that is not HOCON at all is one line rather than a stack trace" in {
    val e = refused("package { name = ")

    e should startWith("package.hocon:")
    e shouldNot include("Exception")
  }
}
