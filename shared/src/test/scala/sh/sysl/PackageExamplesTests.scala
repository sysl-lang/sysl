package sh.sysl

import io.github.edadma.cross_platform.*

/** `examples/` — the one directory a package root gives up, so that a package can carry a program
 * (`reference/packages.md § A package may carry examples`). Card `0194 h`.
 *
 * **Everything under a project root compiles into it**, which is what made a package unable to carry
 * a demo at all: `build-lib` refused `examples/demo.sysl`, because a file with no `module` header is
 * the anonymous root module wherever it sits and a library may not have one. So every binding in the
 * org kept its example as a fenced block in a README that nothing compiles — *an example that rots*,
 * which is what the card was filed about.
 *
 * Two halves, and the second is what makes it worth having: the directory is excluded from the
 * library build and from `sysl test .`'s walk, **and** a program in it finds the package it sits
 * under without the caller naming one with `--lib`.
 */
class PackageExamplesTests extends PackageCacheSupport {

  /** A package with a module and an `examples/` directory holding whatever is asked for. */
  private def packageWithExamples(files: (String, String)*): String = {
    val root = createTempDirectory("sysl-ex-")

    writeFile(s"$root/${PackageConfig.FileName}", manifest("geom-lib", "1.0.0"))
    createDirectories(s"$root/geom")
    writeFile(s"$root/geom/geom.sysl", "module geom\n\ndouble(n: int) -> int = n * 2\n")

    for (path, body) <- files do
      Project.parentOf(s"$root/$path").foreach(createDirectories)
      writeFile(s"$root/$path", body)

    root
  }

  private def driven(command: String, file: String): (Int, String, String) = {
    val out   = new java.io.ByteArrayOutputStream
    val notes = new java.io.ByteArrayOutputStream
    val code  = Console.withOut(out)(
      Console.withErr(notes)(sh.sysl.execute(Config(command = command, file = file))))

    (code, out.toString, notes.toString)
  }

  private def ranTo(command: String, file: String): String = driven(command, file) match {
    case (0, out, _)      => out
    case (n, out, errors) => fail(s"the driver exited with $n:\n$out$errors")
  }

  "the directory is not part of the tree it sits in" - {

    /** The reduction the card was written from, verified rather than reasoned: a `.sysl` with no
     * `module` header in a package root is the anonymous root module, and a library may not have
     * one.
     */
    "a library builds with a program sitting in it" in {
      val root = packageWithExamples("examples/demo.sysl" -> "import geom.*\n\nprint(double(21))\n")

      val (code, _, errors) =
        driven("build-lib", root) match
          case (c, o, e) => (c, o, e)

      withClue(errors)(code shouldBe 0)
    }

    // The interaction the decision names: that walk refuses a file declaring no module, and it is
    // why `skitter` had to be two repositories.
    "and 'sysl test .' does not walk it either" in {
      val root = packageWithExamples(
        "examples/demo.sysl" -> "import geom.*\n\nprint(double(21))\n",
        "geom/tests.sysl"    -> "module geom\n@tests\n\n@test\ndoubling() =\n    assert(double(2) == 4)\n",
      )

      ranTo("test", root) should include("1 passed")
    }

    /** **The exclusion is at the root and nowhere else.** A package that wants the word for a module
     * still has it, which is what keeps the rule from being a name the format has taken away.
     */
    "a nested 'examples' directory is an ordinary module" in {
      val root = packageWithExamples(
        "geom/examples/examples.sysl" -> "module geom.examples\n\ntriple(n: int) -> int = n * 3\n",
      )

      // Asked of `collect` rather than of `modules`, which stops at the shallowest module on each
      // branch and so reports `geom` alone whatever is under it — a fact about what a package
      // *offers* as a top-level name, not about what compiles.
      Project.collect(root, Some(Target.default.os)).map(_.dir) should contain(Some(List("geom", "examples")))
    }

    "and the root's is in no module at all" in {
      val root = packageWithExamples("examples/demo.sysl" -> "import geom.*\n\nprint(double(21))\n")

      Project.collect(root, Some(Target.default.os)).map(_.name).exists(_.contains("examples")) shouldBe false
    }
  }

  "a program in it compiles against the package with nothing on the command line" - {

    "written as one file" in {
      val root = packageWithExamples("examples/demo.sysl" -> "import geom.*\n\nprint(double(21))\n")

      ranTo("run", s"$root/examples/demo.sysl") shouldBe "42\n"
    }

    // The other shape, and the one a demo with more than one file has to take: a directory of its
    // own inside `examples/`.
    "and as a directory of its own" in {
      val root = packageWithExamples(
        "examples/demo/main.sysl" -> "import geom.*\n\nprint(double(21))\n",
      )

      ranTo("run", s"$root/examples/demo") shouldBe "42\n"
    }

    /** **The manifest has to be there.** A directory called `examples` beside a tree that is not a
     * package is somebody's ordinary folder, and answering with its parent would compile a program
     * against a library nobody claimed.
     */
    "but only where the parent is a package" in {
      val root = createTempDirectory("sysl-plain-")

      createDirectories(s"$root/geom")
      writeFile(s"$root/geom/geom.sysl", "module geom\n\ndouble(n: int) -> int = n * 2\n")
      createDirectories(s"$root/examples")
      writeFile(s"$root/examples/demo.sysl", "import geom.*\n\nprint(double(21))\n")

      owningPackage(s"$root/examples/demo.sysl") shouldBe None

      val (code, _, _) = driven("run", s"$root/examples/demo.sysl")

      code should not be 0
    }

    "and a program that is not in one is unaffected" in {
      val root = createTempDirectory("sysl-app-")

      writeFile(s"$root/main.sysl", "print(21 * 2)\n")

      owningPackage(s"$root/main.sysl") shouldBe None
      ranTo("run", root) shouldBe "42\n"
    }
  }
}
