package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** A program built against what its `package.hocon` depends on — the whole of the package system
 * from the outside (`packages.md`).
 *
 * This is the seam that matters: the suites either side of it check the resolver's answers and the
 * hash's definition, and none of them says that a `sysl run` over a project with a `dependencies`
 * block compiles the fetched code, links it, and prints the right number. Every case here does that
 * or refuses to.
 *
 * The dependencies are `path` ones, so nothing reaches the network — and the code path from
 * resolution onwards is the same one a git dependency takes, since `§ 3` puts a fetched package in
 * a directory and everything after that is a directory.
 */
class PackageBuildTests extends PackageCacheSupport {

  /** A package on disk: a manifest, and one module in a directory of its own. */
  private def packageOf(name: String, module: String, text: String, deps: String = ""): String = {
    val root = createTempDirectory("sysl-pkg-")

    writeFile(s"$root/${PackageConfig.FileName}", manifest(name, "1.0.0", deps))
    createDirectories(s"$root/$module")
    writeFile(s"$root/$module/$module.sysl", s"module $module\n\n$text\n")
    root
  }

  /** A project with a program in it and the dependencies its manifest names. */
  private def app(program: String, deps: String): String = {
    val root = createTempDirectory("sysl-app-")

    writeFile(s"$root/${PackageConfig.FileName}",
      s"""package { name = "app", version = "0.1.0" }
         |dependencies { $deps }
         |""".stripMargin)
    writeFile(s"$root/main.sysl", program)
    root
  }

  /** The driver run against a cache of this test's own, so that what it fetches from and what the
   * machine has built before are two different things.
   */
  private def withCache[T](cache: String)(body: => T): T = Fetch.usingCache(cache)(body)

  private def run(root: String): String = {
    val out    = new java.io.ByteArrayOutputStream
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(out)(Console.withErr(notes)(io.github.edadma.sysl.execute(Config(command = "run", file = root))))

    if status != 0 then fail(s"the driver exited with $status:\n${out.toString}${notes.toString}")

    out.toString
  }

  private def refused(root: String): String = {
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(Discarded)(Console.withErr(notes)(io.github.edadma.sysl.execute(Config(command = "run", file = root))))

    if status == 0 then fail(s"expected a refusal, got a build")

    notes.toString
  }

  "a program calls a package's function" in {
    val geom = packageOf("geom-lib", "geom", "double(n: int) -> int = n * 2")

    run(app("""print(geom.double(21))""", s"""g { path = "$geom" }""")) shouldBe "42\n"
  }

  // The import line is the one the library's own documentation would show, which is `§ 9`'s whole
  // argument for making the mount optional.
  "and may import from it rather than qualifying every use" in {
    val geom = packageOf("geom-lib", "geom", "double(n: int) -> int = n * 2")

    run(app(
      """import geom.double
        |
        |print(double(21))""".stripMargin, s"""g { path = "$geom" }""")) shouldBe "42\n"
  }

  "a mount puts the package under a name of the consumer's choosing" in {
    val geom = packageOf("geom-lib", "geom", "double(n: int) -> int = n * 2")

    run(app("""print(shapes.geom.double(21))""",
      s"""g { path = "$geom", mount = "shapes" }""")) shouldBe "42\n"
  }

  // The point of the canonical prefix: the project's own `geom` and the dependency's `geom` are two
  // modules, and each program means the one it wrote.
  "a project's own module may share a name with a mounted package's" in {
    val geom = packageOf("geom-lib", "geom", "double(n: int) -> int = n * 2")
    val root = app("""print(geom.triple(14) + shapes.geom.double(0))""",
      s"""g { path = "$geom", mount = "shapes" }""")

    createDirectories(s"$root/geom")
    writeFile(s"$root/geom/geom.sysl", "module geom\n\ntriple(n: int) -> int = n * 3\n")

    run(root) shouldBe "42\n"
  }

  "a package whose own modules call each other" in {
    val root = createTempDirectory("sysl-pkg-two-")

    writeFile(s"$root/${PackageConfig.FileName}", manifest("two", "1.0.0"))
    createDirectories(s"$root/inner")
    createDirectories(s"$root/outer")
    writeFile(s"$root/inner/inner.sysl", "module inner\n\ndouble(n: int) -> int = n * 2\n")
    // Written with the package's own short name, which is what makes a package's source read the
    // same whoever depends on it.
    writeFile(s"$root/outer/outer.sysl",
      "module outer\n\nimport inner.double\n\nquadruple(n: int) -> int = double(double(n))\n")

    run(app("""print(outer.quadruple(10))""", s"""t { path = "$root" }""")) shouldBe "40\n"
  }

  // A package's table is its own, so `middle` reaches `bottom` because *its* manifest names it —
  // and would not reach it merely because the project did. That is the two-layer identity from the
  // inside: the consumer here never writes the word `bottom` at all.
  "a package that depends on a package" in {
    val cache = emptyCache()
    val at    = published(cache, "github.com/e/bottom", Version(1, 0, 0), manifest("bottom", "1.0.0"))

    createDirectories(s"$at/bottom")
    writeFile(s"$at/bottom/bottom.sysl", "module bottom\n\ndouble(n: int) -> int = n * 2\n")

    val middle = createTempDirectory("sysl-pkg-mid-")

    writeFile(s"$middle/${PackageConfig.FileName}",
      manifest("middle", "1.0.0", """b { git = "github.com/e/bottom", version = "1.0.0" }"""))
    createDirectories(s"$middle/middle")
    writeFile(s"$middle/middle/middle.sysl",
      "module middle\n\nquadruple(n: int) -> int = bottom.double(bottom.double(n))\n")

    withCache(cache)(run(app("""print(middle.quadruple(10))""", s"""m { path = "$middle" }"""))) shouldBe
      "40\n"
  }

  "and the consumer does not thereby reach what its dependency reaches" in {
    val cache = emptyCache()
    val at    = published(cache, "github.com/e/bottom", Version(1, 0, 0), manifest("bottom", "1.0.0"))

    createDirectories(s"$at/bottom")
    writeFile(s"$at/bottom/bottom.sysl", "module bottom\n\ndouble(n: int) -> int = n * 2\n")

    val middle = createTempDirectory("sysl-pkg-mid2-")

    writeFile(s"$middle/${PackageConfig.FileName}",
      manifest("middle", "1.0.0", """b { git = "github.com/e/bottom", version = "1.0.0" }"""))
    createDirectories(s"$middle/middle")
    writeFile(s"$middle/middle/middle.sysl",
      "module middle\n\nquadruple(n: int) -> int = bottom.double(bottom.double(n))\n")

    withCache(cache)(refused(app("""print(bottom.double(21))""", s"""m { path = "$middle" }"""))) should
      include("bottom")
  }

  "two packages wanting one name" - {

    "is refused rather than resolved by whichever was read first" in {
      val one = packageOf("one", "json", "tag() -> int = 1")
      val two = packageOf("two", "json", "tag() -> int = 2")

      refused(app("""print(json.tag())""",
        s"""a { path = "$one" }, b { path = "$two" }""")) should include("is the root name of two packages")
    }

    "and a mount settles it" in {
      val one = packageOf("one", "json", "tag() -> int = 40")
      val two = packageOf("two", "json", "tag() -> int = 2")

      run(app("""print(json.tag() + other.json.tag())""",
        s"""a { path = "$one" }, b { path = "$two", mount = "other" }""")) shouldBe "42\n"
    }

    // The common case rather than an exotic one, and the reason the check cannot be only about
    // dependencies disagreeing with each other.
    "including one the project already uses for a module of its own" in {
      val one  = packageOf("one", "json", "tag() -> int = 1")
      val root = app("""print(json.tag())""", s"""a { path = "$one" }""")

      createDirectories(s"$root/json")
      writeFile(s"$root/json/json.sysl", "module json\n\nmine() -> int = 2\n")

      refused(root) should include("is both a directory in this project")
    }
  }

  "a name the package does not declare is refused where it is written" in {
    val geom = packageOf("geom-lib", "geom", "double(n: int) -> int = n * 2")

    refused(app("""print(geom.triple(21))""", s"""g { path = "$geom" }""")) should include("triple")
  }

  "a project with no dependencies block is untouched by any of this" in {
    val root = createTempDirectory("sysl-plain-")

    writeFile(s"$root/${PackageConfig.FileName}", """package { name = "app", version = "0.1.0" }""")
    writeFile(s"$root/main.sysl", "print(42)")

    run(root) shouldBe "42\n"
  }

  "sysl.sum" - {

    // A path dependency is expected to change under you, so nothing records what it hashed to and
    // the file is never written for one.
    "is not written for a project whose dependencies are all paths" in {
      val geom = packageOf("geom-lib", "geom", "double(n: int) -> int = n * 2")
      val root = app("""print(geom.double(21))""", s"""g { path = "$geom" }""")

      run(root)
      isFile(s"$root/${Sums.FileName}") shouldBe false
    }

    // Written on the first build, from a package this project did not itself fetch — which is the
    // case that would otherwise leave a project with no record and so no check.
    "records what a git dependency hashed to, on the first build" in {
      val cache = emptyCache()
      val at    = published(cache, "github.com/e/geom", Version(1, 0, 0), manifest("geom-lib", "1.0.0"))

      createDirectories(s"$at/geom")
      writeFile(s"$at/geom/geom.sysl", "module geom\n\ndouble(n: int) -> int = n * 2\n")

      val hash = record(cache, "github.com/e/geom", Version(1, 0, 0))
      val root = app("""print(geom.double(21))""",
        """g { git = "github.com/e/geom", version = "1.0.0" }""")

      withCache(cache)(run(root)) shouldBe "42\n"
      readFile(s"$root/${Sums.FileName}") shouldBe s"github.com/e/geom v1.0.0 $hash\n"
    }

    "and a build whose record already agrees leaves the file alone" in {
      val cache = emptyCache()
      val at    = published(cache, "github.com/e/geom", Version(1, 0, 0), manifest("geom-lib", "1.0.0"))

      createDirectories(s"$at/geom")
      writeFile(s"$at/geom/geom.sysl", "module geom\n\ndouble(n: int) -> int = n * 2\n")

      val hash = record(cache, "github.com/e/geom", Version(1, 0, 0))
      val root = app("""print(geom.double(21))""",
        """g { git = "github.com/e/geom", version = "1.0.0" }""")

      writeFile(s"$root/${Sums.FileName}", s"github.com/e/geom v1.0.0 $hash\n")

      val before = lastModified(s"$root/${Sums.FileName}")

      withCache(cache)(run(root)) shouldBe "42\n"
      lastModified(s"$root/${Sums.FileName}") shouldBe before
    }

    // The refusal the file exists for: what is on disk is not what was promised.
    "refuses a build whose record disagrees with what is there" in {
      val cache = emptyCache()
      val at    = published(cache, "github.com/e/geom", Version(1, 0, 0), manifest("geom-lib", "1.0.0"))

      createDirectories(s"$at/geom")
      writeFile(s"$at/geom/geom.sysl", "module geom\n\ndouble(n: int) -> int = n * 2\n")
      record(cache, "github.com/e/geom", Version(1, 0, 0))

      val root = app("""print(geom.double(21))""",
        """g { git = "github.com/e/geom", version = "1.0.0" }""")

      writeFile(s"$root/${Sums.FileName}", s"github.com/e/geom v1.0.0 ${Hashing.Prefix}${"a" * 64}\n")

      withCache(cache)(refused(root)) should include("does not hash to what sysl.sum records")
    }
  }
}
