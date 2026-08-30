package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** A `dev_dependencies` package is resolved for this project's own `sysl test` and pruned from the
  * graph of anything that depends on it (`reference/packages.md § Dependencies a test alone needs`).
  *
  * **What has to be true is a property of the STRIPPED tree**, which is what `Tests.checkDevImports`
  * asks: a consumer compiles what survives `Tests.stripSource`, so nothing surviving it may name a
  * package a consumer will never fetch. That framing is why a `@tests` file and a `@test` function
  * need no case of their own in the implementation — they are gone before the question is put — and
  * it is why both are asserted here, since the implementation getting that wrong would look exactly
  * like it working.
  */
class DevDependencyTests extends AnyFreeSpec with Matchers {

  // The path a file writes, which is the mount rather than the coordinate: `github.com/sysl-lang/quickjs`
  // is not spellable as a module path at all, since a hyphen is not an identifier character.
  private val dev = Set("sh.sysl.quickjs")

  private def parsed(name: String, text: String): Program =
    SyslParser.checked(Source(name, text)) match
      case Right(p) => p
      case Left(e)  => fail(s"expected $name to parse, got: ${Diagnostic.report(e)}")

  private def refusal(units: List[Program]): List[Diagnostic] =
    Tests.checkDevImports(units, dev) match
      case Left(found) => found
      case Right(_)    => fail("expected the import to be refused")

  private def allowed(units: List[Program]): Unit =
    Tests.checkDevImports(units, dev) match
      case Right(_)    => ()
      case Left(found) => fail(s"expected no refusal, got: ${Diagnostic.report(found)}")

  "an ordinary module" - {

    "may not import a dev dependency" in {
      val found = refusal(List(parsed("lib.sysl",
        """module p
          |
          |import sh.sysl.quickjs
          |""".stripMargin)))

      found.length shouldBe 1
      found.head.message should include("dev_dependencies")
      found.head.message should include("sh.sysl.quickjs")
    }

    // The message has to say what to do, because both answers are reasonable and only the author
    // knows which: move the import, or promote the package.
    "is told both ways out" in {
      val message = refusal(List(parsed("lib.sysl",
        "module p\n\nimport sh.sysl.quickjs\n"))).head.message

      message should include("'@tests' file")
      message should include("'dependencies'")
    }

    "is refused for a module NAMED BELOW the package too" in {
      allowed(Nil)

      refusal(List(parsed("lib.sysl",
        "module p\n\nimport sh.sysl.quickjs.engine\n"))).length shouldBe 1
    }

    // A prefix match on the string alone would catch this, and it is a different package.
    "is not refused for a package whose name merely starts the same way" in {
      allowed(List(parsed("lib.sysl",
        "module p\n\nimport sh.sysl.quickjson\n")))
    }

    "may import anything else" in {
      allowed(List(parsed("lib.sysl", "module p\n\nimport sysl.buf.Buf\n")))
    }
  }

  "a '@tests' file" - {

    // The whole file is scaffolding, so `stripSource` drops it and there is nothing left to refuse.
    "may import a dev dependency" in {
      allowed(List(parsed("tests.sysl",
        """module p
          |@tests
          |
          |import sh.sysl.quickjs
          |""".stripMargin)))
    }

    "does not excuse an ordinary file beside it" in {
      refusal(List(
        parsed("tests.sysl", "module p\n@tests\n\nimport sh.sysl.quickjs\n"),
        parsed("lib.sysl", "module p\n\nimport sh.sysl.quickjs\n"),
      )).length shouldBe 1
    }
  }

  "with nothing declared" - {

    // The ordinary case, and the one that must cost nothing: almost no project has a dev block.
    "the check says nothing at all" in {
      val unit = parsed("lib.sysl", "module p\n\nimport sh.sysl.quickjs\n")

      Tests.checkDevImports(List(unit), Set.empty) shouldBe Right(())
    }
  }

  "every offending file is named" in {
    refusal(List(
      parsed("a.sysl", "module p\n\nimport sh.sysl.quickjs\n"),
      parsed("b.sysl", "module p\n\nimport sh.sysl.quickjs\n"),
    )).length shouldBe 2
  }
}
