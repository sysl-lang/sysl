package sh.sysl.doc

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl-doc`'s command line — argument parsing, and the exit code each path answers with.
 *
 * **The exit code is the point of this suite.** `DocCli.run` is the seam held apart from `@main` for
 * exactly the reason `sysl`'s `drive` is: `@main` calls `processExit`, so a test that went through it
 * would take the test runner down with it, and the number it was going to assert is the one thing a
 * caller in a shell script actually sees. It was wrong on the generate path and nothing else would
 * have noticed — the command printed its success line and exited 1.
 */
class DocCliTests extends AnyFreeSpec with Matchers {

  /** A throwaway output directory under the build's own target tree. */
  private def out(name: String): String = {
    val dir = s"target/doc-cli-tests/$name"

    sh.sysl.Project.discard(dir)
    dir
  }

  "the argument parser" - {

    "defaults to the working directory and docs/api" in {
      val opts = DocCli.parse(Nil).toOption.get

      opts.dir shouldBe "."
      opts.out shouldBe "docs/api"
      opts.check shouldBe false
      opts.site shouldBe None
    }

    "takes the tree as a positional argument" in {
      DocCli.parse(List("library")).toOption.get.dir shouldBe "library"
    }

    "takes -o and --out alike" in {
      DocCli.parse(List("-o", "x")).toOption.get.out shouldBe "x"
      DocCli.parse(List("--out", "y")).toOption.get.out shouldBe "y"
    }

    "takes -n and --note alike, and has none by default" in {
      DocCli.parse(List("-n", "hello")).toOption.get.note shouldBe Some("hello")
      DocCli.parse(List("--note", "hello")).toOption.get.note shouldBe Some("hello")
      DocCli.parse(Nil).toOption.get.note shouldBe None
    }

    "names --note when its value is missing, as it does every other valued flag" in {
      // The missing-value list is written out by hand, so a flag added to the parser and not to
      // that list fails as "unknown option '--note'" — which reads as a flag that does not exist.
      DocCli.parse(List("--note")).left.toOption.get should include("'--note' needs a value")
    }

    "takes -w and --weight alike, and has none by default" in {
      DocCli.parse(List("-w", "45")).toOption.get.weight shouldBe Some(45)
      DocCli.parse(List("--weight", "45")).toOption.get.weight shouldBe Some(45)
      DocCli.parse(Nil).toOption.get.weight shouldBe None
    }

    "refuses a --weight that is not a number, and says what it got" in {
      // Carried as an Int rather than a string precisely so this can be refused. A frontmatter
      // `weight: nine` is not an error anywhere downstream — the site reads it as nothing and puts
      // the section wherever the unweighted default lands, which is the state this flag exists to
      // end. Failing here is the only place it can be noticed.
      val message = DocCli.parse(List("--weight", "nine")).left.toOption.get

      message should include("'--weight' needs a whole number")
      message should include("nine")
    }

    "names --weight when its value is missing, as it does every other valued flag" in {
      DocCli.parse(List("--weight")).left.toOption.get should include("'--weight' needs a value")
    }

    "reads the flags that take no value" in {
      val opts = DocCli.parse(List("--private", "--check")).toOption.get

      opts.includePrivate shouldBe true
      opts.check shouldBe true
    }

    "names the flag when its value is missing, rather than complaining about the end of input" in {
      DocCli.parse(List("--out")).left.toOption.get should include("'--out' needs a value")
    }

    "refuses an unknown option" in {
      DocCli.parse(List("--nonsense")).left.toOption.get should include("unknown option '--nonsense'")
    }

    "answers the usage text for --help" in {
      DocCli.parse(List("--help")).left.toOption.get should startWith("sysl-doc — ")
    }
  }

  "the exit code" - {

    "is 0 when --help was asked for, because asking for help is not a mistake" in {
      DocCli.run(List("--help")) shouldBe 0
    }

    "is 1 for an unknown option" in {
      DocCli.run(List("--nonsense")) shouldBe 1
    }

    "is 1 for a tree that holds no sysl" in {
      DocCli.run(List("target/doc-cli-tests/nothing-here")) shouldBe 1
    }

    "is 0 after a successful generate" in {
      // The one that was wrong: the command wrote all 27 modules, printed its success line, and
      // exited 1. A shell script reading `$?` would have called a good run a failure.
      DocCli.run(List("library", "--out", out("generate"))) shouldBe 0
    }

    "is 0 when --check finds the pages up to date" in {
      val dir = out("check-fresh")

      DocCli.run(List("library", "--out", dir)) shouldBe 0
      DocCli.run(List("library", "--out", dir, "--check")) shouldBe 0
    }

    "is 1 when --check finds nothing generated at all" in {
      DocCli.run(List("library", "--out", out("check-missing"), "--check")) shouldBe 1
    }

    "is 1 when --check finds a page that has drifted" in {
      // The whole point of the flag: a doc comment edited without regenerating fails the build.
      val dir = out("check-stale")

      DocCli.run(List("library", "--out", dir)) shouldBe 0

      io.github.edadma.cross_platform.writeFile(s"$dir/sysl-text.md", "stale\n")

      DocCli.run(List("library", "--out", dir, "--check")) shouldBe 1
    }
  }

  "reading a tree" - {

    "answers every module of the standard library" in {
      val modules = DocCli.read("library", includePrivate = false).toOption.get

      modules.map(_.name) should contain("sysl.text")
      modules.map(_.name) should contain("sysl.slices")
      modules.length should be >= 20
    }

    "refuses a directory that holds no sysl at all, naming it" in {
      DocCli.read("target/doc-cli-tests/absent", includePrivate = false)
        .left.toOption.get should include("holds no sysl source")
    }
  }
}
