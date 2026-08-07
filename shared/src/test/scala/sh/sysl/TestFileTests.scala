package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `@tests` — a file of a module's test scaffolding, kept by `sysl test` and dropped by every other
 * build (`testing.md`).
 *
 * The header says two things and both are pinned here, because either alone is unsound. It **drops**
 * the file, which is what keeps a helper out of a program and out of a library's artifact; and it
 * **restricts** who may name what the file declared, which is what makes dropping it safe rather
 * than a link error waiting for whoever writes the reference.
 *
 * The restriction is stated over the referring *declaration* and not over the file it sits in, and
 * the case that forces it is the one `testing.md` asks for: a `@test` written beside what it tests,
 * in an ordinary file, calling scaffolding from the test file. A rule about files would refuse that
 * and drive every test out of the module's real files.
 */
class TestFileTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  /** Every test a project declares, run, in the order the sources declared them. */
  private def ranIn(fs: (String, String, String)*): List[TestRunner.Outcome] = {
    assume(Toolchain.clangAvailable, "clang not available")

    val (built, tests) = Compiler.compileTests(project(fs*), Nil) match {
      case Right(result) => result
      case Left(e)       => fail(e)
    }

    val exe = io.github.edadma.cross_platform.createTempFile("sysl-test-", "")

    try
      Toolchain.build(built.ir, exe, links = built.links) match {
        case Left(e)  => fail(e)
        case Right(_) => TestRunner.execute(exe, tests, TestRunner.Options())
      }
    finally
      try io.github.edadma.cross_platform.deleteFile(exe)
      catch case _: Exception => ()
  }

  private def parsed(src: String): Program =
    SyslParser.parse(Source("<input>", src)) match {
      case Right(p) => p
      case Left(e)  => fail(e)
    }

  "the header attribute parses where the others do" - {

    "under a module header, with the capability clauses" in {
      parsed("module m\n@tests\n\nf() -> int = 1\n").testOnly shouldBe true
    }

    "beside one, in either order" in {
      parsed("module m\n@no_alloc\n@tests\n\nf() -> int = 1\n").testOnly shouldBe true
      parsed("module m\n@tests\n@no_alloc\n\nf() -> int = 1\n").testOnly shouldBe true
    }

    "and it says nothing about the capabilities beside it" in {
      val p = parsed("module m\n@tests\n@no_alloc\n\nf() -> int = 1\n")

      p.capabilities.map(_.name) shouldBe List("alloc")
    }

    "opening a file that declares no module, where the root module's header would go" in {
      parsed("@tests\n\nf() -> int = 1\n").testOnly shouldBe true
    }

    "and a file that does not write it is not one" in {
      parsed("module m\n\nf() -> int = 1\n").testOnly shouldBe false
    }
  }

  "written anywhere but the header, it says where it belongs" - {

    "below the statements it would otherwise seem to cover" in {
      err("f() -> int = 1\n@tests\n") should include("belongs in the file's header")
    }

    "and between two declarations, where an annotation would go" in {
      err("module m\n\nf() -> int = 1\n\n@tests\ng() -> int = 2\n") should
        include("belongs in the file's header")
    }

    // What ends the header is the first statement, not the first blank line — so a file that puts
    // air between 'module' and its attributes is written the way most of them are.
    "though a blank line between it and 'module' leaves it in the header" in {
      parsed("module m\n\n\n@tests\n\nf() -> int = 1\n").testOnly shouldBe true
    }
  }

  "what such a file declares is dropped from every build but a test build" - {

    "a helper is not defined in the program that was built beside it" in {
      val out = irIn(
        ("", "main.sysl", "import m.*\nprint(double(21))"),
        ("m", "m.sysl", "module m\n\ndouble(n: int) -> int = n * 2"),
        ("m", "tests.sysl", "module m\n@tests\n\nquadruple(n: int) -> int = double(double(n))"),
      )

      out should include("@m$double")
      out should not include "quadruple"
    }

    "module storage goes with it" in {
      val out = irIn(
        ("", "main.sysl", "import m.*\nprint(double(21))"),
        ("m", "m.sysl", "module m\n\ndouble(n: int) -> int = n * 2"),
        ("m", "tests.sysl", "module m\n@tests\n\nval fixture: int = 7"),
      )

      out should not include "fixture"
    }

    "and the program still runs" in {
      runIn(
        ("", "main.sysl", "import m.*\nprint(double(21))"),
        ("m", "m.sysl", "module m\n\ndouble(n: int) -> int = n * 2"),
        ("m", "tests.sysl", "module m\n@tests\n\nquadruple(n: int) -> int = double(double(n))"),
      ) shouldBe "42\n"
    }
  }

  "and kept by 'sysl test', which is the whole point of writing one" - {

    "a test in such a file runs, and the helper beside it is there to be called" in {
      val ran = ranIn(
        ("", "main.sysl", "import m.*\nprint(double(21))"),
        ("m", "m.sysl", "module m\n\ndouble(n: int) -> int = n * 2"),
        ("m", "tests.sysl",
         "module m\n@tests\n\nquadruple(n: int) -> int = double(double(n))\n\n" +
           "@test\ndoubling_twice_is_quadrupling() =\n    assert(quadruple(3) == 12)\n"),
      )

      ran.map(o => o.test.display -> o.passed) shouldBe List("doubling_twice_is_quadrupling" -> true)
    }

    "a '@test' in an ordinary file may name the scaffolding, since the two are dropped together" in {
      val ran = ranIn(
        ("", "main.sysl", "import m.*\nprint(double(21))"),
        ("m", "m.sysl",
         "module m\n\ndouble(n: int) -> int = n * 2\n\n" +
           "@test\nbeside_what_it_tests() =\n    assert(quadruple(5) == 20)\n"),
        ("m", "tests.sysl", "module m\n@tests\n\nquadruple(n: int) -> int = double(double(n))"),
      )

      ran.map(o => o.test.display -> o.passed) shouldBe List("beside_what_it_tests" -> true)
    }

    "and one test file may name another's" in {
      val ran = ranIn(
        ("", "main.sysl", "import m.*\nprint(double(21))"),
        ("m", "m.sysl", "module m\n\ndouble(n: int) -> int = n * 2"),
        ("m", "fixtures.sysl", "module m\n@tests\n\nquadruple(n: int) -> int = double(double(n))"),
        ("m", "tests.sysl",
         "module m\n@tests\n\n@test\nacross_two_test_files() =\n    assert(quadruple(4) == 16)\n"),
      )

      ran.map(_.passed) shouldBe List(true)
    }
  }

  "anything else that names one is told so where it wrote the name" - {

    "a function of an ordinary file" in {
      val e = errIn(
        ("", "main.sysl", "import m.*\nprint(twice_over(21))"),
        ("m", "m.sysl",
         "module m\n\ndouble(n: int) -> int = n * 2\n\ntwice_over(n: int) -> int = quadruple(n)"),
        ("m", "tests.sysl", "module m\n@tests\n\nquadruple(n: int) -> int = double(double(n))"),
      )

      e should include("'m.quadruple' is declared in a file that said '@tests'")
      e should include("only another such file, or a '@test' function, may name it")
    }

    "the program's own statements, which no build drops at all" in {
      errIn(
        ("", "main.sysl", "import m.*\nprint(quadruple(21))"),
        ("m", "m.sysl", "module m\n\ndouble(n: int) -> int = n * 2"),
        ("m", "tests.sysl", "module m\n@tests\n\nquadruple(n: int) -> int = double(double(n))"),
      ) should include("is declared in a file that said '@tests'")
    }

    "a read of storage it declared" in {
      errIn(
        ("", "main.sysl", "import m.*\nprint(fixture)"),
        ("m", "m.sysl", "module m\n\ndouble(n: int) -> int = n * 2"),
        ("m", "tests.sysl", "module m\n@tests\n\nval fixture: int = 7"),
      ) should include("is declared in a file that said '@tests'")
    }

    "and taking a helper's address, which is a use of it exactly as calling it is" in {
      errIn(
        ("", "main.sysl", "import m.*\nval f: *extern(int) -> int = &quadruple\nprint(f(21))"),
        ("m", "m.sysl", "module m\n\ndouble(n: int) -> int = n * 2"),
        ("m", "tests.sysl", "module m\n@tests\n\nquadruple(n: int) -> int = double(double(n))"),
      ) should include("is declared in a file that said '@tests'")
    }
  }

  "an 'impl' block may not sit in one" - {

    "because a test build would keep it and every other build would drop it" in {
      val e = errIn(
        ("", "main.sysl", "import m.*\nprint(1)"),
        ("m", "m.sysl", "module m\n\ntrait Scale\n    scale(self, k: int) -> int\n\nstruct P\n    n: int"),
        ("m", "tests.sysl",
         "module m\n@tests\n\nimpl Scale for P\n    scale(self, k: int) -> int = self.n * k\n"),
      )

      e should include("an 'impl' block may not sit in a file that said '@tests'")
      e should include("It belongs beside the type")
    }
  }

  "a library ships none of it" - {

    "the artifact advertises what the module declared and not what its tests did" in {
      val sources = List(
        Source("demo/lib.sysl", "module demo\n\ndouble(n: int) -> int = n * 2\n", List("demo")),
        Source("demo/tests.sysl",
               "module demo\n@tests\n\nquadruple(n: int) -> int = double(double(n))\n", List("demo")),
      )

      LibraryArtifact.build(sources) match {
        case Left(e) => fail(s"the library did not build: $e")
        case Right((ir, meta)) =>
          // Neither half carries it: the compiled half because `Tests.strip` took the definition out,
          // the tree half because the file was left out of the metadata. A consumer reads the second
          // to instantiate a generic, so a helper left there would be nameable by everything that
          // links the library — which is the whole hazard `@tests` exists to close.
          ir should not include "quadruple"

          LibraryArtifact.read("demo.syslib", meta, Target.default) match {
            case Left(e)              => fail(e)
            case Right((units, _, _)) =>
              Library.names(units.flatMap(_.body)).toList.sorted shouldBe List("double")
          }
      }
    }

    /** A library's tests are **parsed but never analyzed**, and these three pin exactly where that
      * line falls — which is not where "`build-lib` no longer checks a library's tests" would put it.
      *
      * What is given up is everything *after* the parse: name resolution, types, visibility,
      * capabilities, `@test` well-formedness, generic instantiation. It was given up because
      * analysis is not a passive reading — a test naming `Buf[int]` *creates* the whole of `Buf` at
      * `int`, and an instantiation is an ordinary library function afterwards, with nothing in it
      * recording which declaration demanded it. Analyze the tests and the artifact ships what they
      * caused, however carefully the declarations are filtered out afterwards.
      *
      * Where a library test's real errors *are* reported is `sysl test` — `--std` for the standard
      * library, which `StdSelfTests` runs as part of this suite, and plain `sysl test` for anyone
      * else's.
      */
    "an undefined name in one is no longer a build error, because it is never analyzed" in {
      val sources = List(
        Source("demo/lib.sysl", "module demo\n\ndouble(n: int) -> int = n * 2\n", List("demo")),
        Source("demo/tests.sysl", "module demo\n@tests\n\nbad() -> int = nosuchthing()\n", List("demo")),
      )

      LibraryArtifact.build(sources) match {
        case Right(_) => succeed
        case Left(e)  => fail(s"a library's own tests should not be compiled by build-lib: $e")
      }
    }

    /** **The other side of the line, and the one most likely to be got wrong when reading the rule
      * back.** `LibraryArtifact.build` parses every source and returns on the first `Left` before
      * `compileLibrary` is reached, so a `@tests` file that is not well-formed *text* still stops
      * the build — the strip never sees it, because there is no tree to strip from.
      *
      * Worth a test of its own because the natural summary of this change — "a library's tests are
      * not checked" — implies this file builds clean, and it does not.
      */
    "but a syntax error in one still is, because the strip happens after the parse" in {
      val sources = List(
        Source("demo/lib.sysl", "module demo\n\ndouble(n: int) -> int = n * 2\n", List("demo")),
        Source("demo/tests.sysl", "module demo\n@tests\n\n@test\nbad( = )\n", List("demo")),
      )

      LibraryArtifact.build(sources) match {
        case Right(_) => fail("a library whose test file does not parse built without a word")
        case Left(e)  => e should include("demo/tests.sysl")
      }
    }

    // Discriminating against the first of these, and the reason they are a set: a `build-lib` that
    // had stopped analyzing the library *altogether* would pass it for entirely the wrong reason.
    // The same undefined name, in a declaration that is not a test, is still a build error.
    "while an ordinary declaration with the same undefined name still is" in {
      val sources = List(
        Source("demo/lib.sysl", "module demo\n\nordinary() -> int = nosuchthing()\n", List("demo")),
      )

      LibraryArtifact.build(sources) match {
        case Right(_) => fail("a library with a broken ordinary declaration built without a word")
        case Left(e)  => e should include("nosuchthing")
      }
    }

    /** **The other end of the trade, and the assertion that justifies making it.**
      *
      * Everything the three above give up, a test build has to still catch, or "the net moved rather
      * than went" is a sentence with nothing behind it. The demanding case is a helper in a `@tests`
      * file that **no test calls**: nothing reaches it, so a checker that worked outwards from the
      * tests would never look at it, and it is precisely the declaration `build-lib` used to be the
      * only thing looking at.
      *
      * Both are asserted in one compilation because the analyzer reports every mistake it finds, so
      * a pass that had stopped at the first would show up as the second going missing.
      */
    "but a test build still analyzes all of it, including a helper nothing calls" in {
      val sources = List(
        Source("demo/lib.sysl", "module demo\n\ndouble(n: int) -> int = n * 2\n", List("demo")),
        Source("demo/tests.sysl",
               """module demo
                 |@tests
                 |
                 |unreached() -> int = nosuchthing()
                 |
                 |@test
                 |bad() = assert_eq(double(nowhere()), 4)
                 |""".stripMargin,
               List("demo")),
      )

      Compiler.compileTests(sources, Nil) match {
        case Right(_) => fail("a test build compiled a tree whose test file names two undefined functions")
        case Left(e) =>
          e should include("nosuchthing")
          e should include("nowhere")
      }
    }

    // The same again for a `@test` written in an ordinary file rather than a `@tests` one, since
    // `stripSource` removes the two by different rules — a file whole, and a declaration out of a
    // file that stays.
    "and a broken '@test' in an ordinary file is not a build error either" in {
      val sources = List(
        Source("demo/lib.sysl",
               "module demo\n\ndouble(n: int) -> int = n * 2\n\n@test\nbad() = nosuchthing()\n",
               List("demo")),
      )

      LibraryArtifact.build(sources) match {
        case Right((ir, _)) => ir should not include "bad"
        case Left(e)        => fail(s"a '@test' in a library should not be compiled by build-lib: $e")
      }
    }
  }

  "the flag survives the artifact codec" in {
    // It is never written to a real artifact, since the file is filtered out before the encode. It
    // travels anyway because a codec that quietly defaulted a field is the failure `Version` exists
    // to catch, and one nobody would be told about.
    val p = parsed("module m\n@tests\n\nf() -> int = 1\n")

    AstCodec.decode(AstCodec.encode(List(p)), Map("<input>" -> p.source)) match {
      case Left(e)         => fail(s"decode failed: $e")
      case Right(List(back)) => back.testOnly shouldBe true
      case Right(other)    => fail(s"expected one program, got ${other.length}")
    }
  }
}
