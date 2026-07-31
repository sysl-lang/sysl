package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `#test` as the grammar and the analyzer see it (`testing.md`).
 *
 * The attribute is the language's first, so half of what is pinned here is about the *mechanism*
 * rather than about tests: that `#` opens one, that the word after it is checked rather than
 * swallowed, that the attribute and its declaration are one statement across the newline between
 * them. The other half is what a `#test` function may be, which is everything needed for the runner
 * to call it with nothing and read the answer off whether it returned.
 */
class TestAttributeTests extends AnyFreeSpec with CodegenSupport with TestFrameworkSupport {

  private def parsed(src: String): List[Stmt] =
    SyslParser.parse(Source("<input>", src)) match {
      case Right(p) => p.body
      case Left(e)  => fail(e)
    }

  private def attrOf(src: String): TestAttr =
    parsed(src).collectFirst { case f: FuncDecl if f.test.isDefined => f.test.get } match {
      case Some(a) => a
      case None    => fail(s"no '#test' function was parsed from:\n$src")
    }

  "the attribute parses in each form it offers" - {
    "a bare '#test' says only that the function is one" in {
      attrOf("""#test
               |t() = 0
               |""".stripMargin) shouldBe TestAttr(None, false, None)
    }

    "a string is the name a report shows" in {
      attrOf("""#test("a sentence about what holds")
               |t() = 0
               |""".stripMargin) shouldBe TestAttr(Some("a sentence about what holds"), false, None)
    }

    "'should_trap' inverts the verdict" in {
      attrOf("""#test(should_trap)
               |t() = 0
               |""".stripMargin) shouldBe TestAttr(None, true, None)
    }

    "'should_trap' may name what the run must have printed" in {
      attrOf("""#test(should_trap: "past the end")
               |t() = 0
               |""".stripMargin) shouldBe TestAttr(None, true, Some("past the end"))
    }

    // The two are independent — a test may want a sentence for its name *and* be about a check that
    // fires — so they compose rather than being alternatives, which is the one combination a grammar
    // written as a flat choice would have left out.
    "a name and an expectation may be written together" in {
      attrOf("""#test("an index past the end is refused", should_trap: "past the end")
               |t() = 0
               |""".stripMargin) shouldBe
        TestAttr(Some("an index past the end is refused"), true, Some("past the end"))
    }

    "a test may be private, which is what tests of a module's own internals need" in {
      val decls = parsed("""#test
                           |private t() = 0
                           |""".stripMargin)

      decls.collectFirst { case f: FuncDecl => (f.vis, f.test.isDefined) } shouldBe Some((Visibility.File, true))
    }
  }

  "the attribute is one statement with the declaration below it" - {
    // The separator between statements is a newline, so without the rule joining them the attribute
    // is a statement of its own — and the failure is about whatever the *next* line is, which says
    // nothing about the line that was wrong.
    "an ordinary declaration after a test is not swept into it" in {
      val decls = parsed("""#test
                           |t() = 0
                           |
                           |plain() = 1
                           |""".stripMargin)

      decls.collect { case f: FuncDecl => (f.name, f.test.isDefined) } shouldBe
        List(("t", true), ("plain", false))
    }

    "two tests in a row each keep their own attribute" in {
      val decls = parsed("""#test("first")
                           |a() = 0
                           |#test("second")
                           |b() = 0
                           |""".stripMargin)

      decls.collect { case f: FuncDecl => f.test.flatMap(_.display) } shouldBe
        List(Some("first"), Some("second"))
    }

    "blank lines between the attribute and the function do not separate them" in {
      attrOf("""#test("still attached")
               |
               |
               |t() = 0
               |""".stripMargin).display shouldBe Some("still attached")
    }
  }

  "an attribute the language does not have is refused by name" - {
    // The point of naming it: a mechanism with one member should say which member it has, rather
    // than reporting the grammar's own confusion about a token it could not place.
    "an unknown word after '#' says what the one attribute is" in {
      err("""#packed
            |t() = 0
            |""".stripMargin) should include("'#test' is the only one")
    }

    "a declaration that is not a function cannot be a test" in {
      err("""#test
            |struct Point
            |    x: int
            |""".stripMargin) should include("only a function")
    }

    "an extern has no body to run" in {
      err("""#test
            |extern side_effect()
            |""".stripMargin) should include("only a function")
    }
  }

  "what a '#test' function may be is what the runner can call" - {
    "a parameter has nowhere to come from" in {
      err("""#test
            |t(n: int) =
            |    print(n)
            |""".stripMargin) should include("takes no parameters")
    }

    // A variadic needs a named parameter in front of the tail (`12 §9`), so it is refused by the
    // rule above and needs no case of its own — and a tail with *no* named parameter never reaches
    // this rule at all, being refused where every other signature like it is.
    "a variadic tail is refused for the parameter it has to have" in {
      err("""#test
            |t(n: int, ...) =
            |    print(n)
            |""".stripMargin) should include("takes no parameters")
    }

    "a tail with nothing in front of it is refused where any other signature would be" in {
      err("""#test
            |t(...) = 0
            |""".stripMargin) should include("at least one named parameter")
    }

    "a generic has no compiled form until a caller fixes its arguments" in {
      err("""#test
            |t[T]() = 0
            |""".stripMargin) should include("no type parameters")
    }

    "a result is a value nothing is going to read" in {
      err("""#test
            |t() -> int = 3
            |""".stripMargin) should include("returns nothing")
    }

    // `-> unit` and no result at all are the same signature written two ways (`12 §1`), so a test
    // that says it out loud is as good as one that does not — and a rule checking the *syntax*
    // rather than the resolved type would have refused this one.
    "an explicit '-> unit' is the same as writing none" in {
      discovered("""#test
                   |t() -> unit = 0
                   |""".stripMargin) shouldBe List("t")
    }
  }

  "a test is reported under the name it was given" - {
    "with no string, that is the function's own bare name" in {
      discovered("""#test
                   |adds_two() = 0
                   |""".stripMargin) shouldBe List("adds_two")
    }

    "a module does not decorate it — the report groups by file already" in {
      Compiler.compileTests(List(Source("m.sysl", """module m
                                                    |
                                                    |#test
                                                    |adds_two() = 0
                                                    |""".stripMargin, List("m"))), Nil) match {
        case Right((_, tests)) =>
          tests.map(_.display) shouldBe List("adds_two")
          tests.map(_.func) shouldBe List("m$adds_two")
        case Left(e) => fail(e)
      }
    }

    "the position a report points at is the attribute's, not the function's" in {
      Compiler.compileTests(List(Source("m.sysl", """double(n: int) -> int = n * 2
                                                    |
                                                    |#test
                                                    |t() = 0
                                                    |""".stripMargin)), Nil) match {
        case Right((_, tests)) => tests.map(t => (t.file, t.line)) shouldBe List(("m.sysl", 3))
        case Left(e)           => fail(e)
      }
    }

    "tests are listed in the order the source declared them" in {
      discovered("""#test
                   |third() = 0
                   |
                   |#test
                   |first() = 0
                   |
                   |#test
                   |second() = 0
                   |""".stripMargin) shouldBe List("third", "first", "second")
    }
  }

  "a test is analyzed whether or not the build would run it" - {
    // The property that lets a test sit beside what it tests: dropping them is an *emission*
    // decision, taken after every check has run. A test that stopped being checked the moment it
    // stopped being emitted would rot in place, and nobody would find out until they ran it.
    "a broken test is an error in an ordinary build" in {
      err("""#test
            |t() =
            |    print(undefined_name)
            |""".stripMargin) should include("undefined name 'undefined_name'")
    }

    "a broken test is an error in a test build too" in {
      Compiler.compileTests(List(Source("<input>", """#test
                                                     |t() =
                                                     |    print(undefined_name)
                                                     |""".stripMargin)), Nil) match {
        case Left(e)  => e should include("undefined name 'undefined_name'")
        case Right(_) => fail("expected the broken test to be reported")
      }
    }
  }
}
