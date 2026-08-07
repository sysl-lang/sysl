package sh.sysl

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

  /** A package on disk: a manifest, one module in a directory of its own, and whatever else it
   * carries — which for the suite below is C, written at a path relative to the package root.
   *
   * `module` may be a path, which is how a package namespaced by reverse DNS is laid out —
   * `sh/sysl/table` declares the single module `sh.sysl.table` and leaves `sh` and `sh/sysl` holding
   * no source, and therefore declaring nothing.
   */
  private def packageOf(name: String, module: String, text: String, deps: String = "",
                        files: (String, String)*): String = {
    val root = createTempDirectory("sysl-pkg-")
    val leaf = Project.basename(module)

    writeFile(s"$root/${PackageConfig.FileName}", manifest(name, "1.0.0", deps))
    createDirectories(s"$root/$module")
    writeFile(s"$root/$module/$leaf.sysl", s"module ${module.replace('/', '.')}\n\n$text\n")

    for (path, body) <- files do
      Project.parentOf(s"$root/$path").foreach(createDirectories)
      writeFile(s"$root/$path", body)

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
    val status = Console.withOut(out)(Console.withErr(notes)(sh.sysl.execute(Config(command = "run", file = root))))

    if status != 0 then fail(s"the driver exited with $status:\n${out.toString}${notes.toString}")

    out.toString
  }

  private def refused(root: String): String = {
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(Discarded)(Console.withErr(notes)(sh.sysl.execute(Config(command = "run", file = root))))

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
        s"""a { path = "$one" }, b { path = "$two" }""")) should include("claim the same module")
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

      refused(root) should include("is both a module of this project")
    }
  }

  /** The convention `packages.md § 9` recommends, from the consuming side — and the case it was
   * recommended *for*.
   *
   * A package that namespaces itself by reverse DNS puts its source at `sh/sysl/<name>/`, so `sh`
   * and `sh/sysl` hold none and neither is a module it declares. Binding the top-level **directory**
   * made every such package claim the one name `sh`, so any two of them refused to resolve together
   * and a project could depend on at most one — with the convention that was supposed to make
   * collisions "close to never" being exactly what guaranteed one.
   *
   * Nothing in the suite could have caught it: every other package here declares a bare module at
   * its root, which is the one shape the old rule got right.
   */
  "packages namespaced under a shared prefix" - {

    "are all reachable at once, under the names their own documentation shows" in {
      val sqlite = packageOf("sqlite3", "sh/sysl/sqlite", "answer() -> int = 40")
      val table  = packageOf("table", "sh/sysl/table", "answer() -> int = 2")

      run(app("""print(sh.sysl.sqlite.answer() + sh.sysl.table.answer())""",
        s"""s { path = "$sqlite" }, t { path = "$table" }""")) shouldBe "42\n"
    }

    "and are imported without a mount, which is the whole point of the convention" in {
      val sqlite = packageOf("sqlite3", "sh/sysl/sqlite", "double(n: int) -> int = n * 2")
      val table  = packageOf("table", "sh/sysl/table", "half(n: int) -> int = n / 2")

      run(app(
        """import sh.sysl.sqlite.double
          |import sh.sysl.table.half
          |
          |print(double(half(42)))""".stripMargin,
        s"""s { path = "$sqlite" }, t { path = "$table" }""")) shouldBe "42\n"
    }

    // A binding covers the module it names and everything under it, so a package needs one entry
    // however deep its tree goes.
    "a module below a bound one comes with it" in {
      val deep = packageOf("deep", "sh/sysl/outer", "double(n: int) -> int = n * 2")

      createDirectories(s"$deep/sh/sysl/outer/inner")
      writeFile(s"$deep/sh/sysl/outer/inner/inner.sysl",
        "module sh.sysl.outer.inner\n\ntriple(n: int) -> int = n * 3\n")

      run(app("""print(sh.sysl.outer.double(15) + sh.sysl.outer.inner.triple(4))""",
        s"""d { path = "$deep" }""")) shouldBe "42\n"
    }

    /** A namespaced package's own modules reaching each other — the *other* half of `packagePath`,
     * where the path is the package's own tree rather than a dependency's binding.
     *
     * "a package whose own modules call each other" above covers the same branch with **bare** names,
     * where the head and the whole name are one thing — so the segment that travels past the head is
     * invisible there, and a package with two namespaced modules is the shape that would have caught
     * it. No package in the org has two yet, which is exactly why it is written down here.
     */
    "a namespaced package's own modules reach each other by import" in {
      val root = createTempDirectory("sysl-pkg-ns-")

      writeFile(s"$root/${PackageConfig.FileName}", manifest("ns", "1.0.0"))
      createDirectories(s"$root/sh/sysl/inner")
      createDirectories(s"$root/sh/sysl/outer")
      writeFile(s"$root/sh/sysl/inner/inner.sysl",
        "module sh.sysl.inner\n\ndouble(n: int) -> int = n * 2\n")
      writeFile(s"$root/sh/sysl/outer/outer.sysl",
        "module sh.sysl.outer\n\nimport sh.sysl.inner.double\n\n" +
          "quadruple(n: int) -> int = double(double(n))\n")

      run(app("""print(sh.sysl.outer.quadruple(10))""", s"""n { path = "$root" }""")) shouldBe "40\n"
    }

    // And by qualified reference, which takes a different route: an import goes through `inPackage`
    // and a reference through `throughModule`, and it was `throughModule` that asked with the head
    // alone.
    "and by qualified reference" in {
      val root = createTempDirectory("sysl-pkg-ns2-")

      writeFile(s"$root/${PackageConfig.FileName}", manifest("ns", "1.0.0"))
      createDirectories(s"$root/sh/sysl/inner")
      createDirectories(s"$root/sh/sysl/outer")
      writeFile(s"$root/sh/sysl/inner/inner.sysl",
        "module sh.sysl.inner\n\ndouble(n: int) -> int = n * 2\n")
      writeFile(s"$root/sh/sysl/outer/outer.sysl",
        "module sh.sysl.outer\n\ntriple(n: int) -> int = sh.sysl.inner.double(n) + n\n")

      run(app("""print(sh.sysl.outer.triple(14))""", s"""n { path = "$root" }""")) shouldBe "42\n"
    }

    // The mount is untouched by any of it: a name the consumer chose has no tree to be read off, so
    // it still hangs the whole package under one segment.
    "and a mount still hangs the whole tree under one segment" in {
      val table = packageOf("table", "sh/sysl/table", "answer() -> int = 42")

      run(app("""print(tb.sh.sysl.table.answer())""",
        s"""t { path = "$table", mount = "tb" }""")) shouldBe "42\n"
    }
  }

  "a name the package does not declare is refused where it is written" in {
    val geom = packageOf("geom-lib", "geom", "double(n: int) -> int = n * 2")

    refused(app("""print(geom.triple(21))""", s"""g { path = "$geom" }""")) should include("triple")
  }

  /** `15 §7` from the consuming side: a package carries C, and a build against it compiles that C
   * and links it.
   *
   * **This is the seam a whole release shipped without.** A package's `.sysl` compiled, its `.c` was
   * dropped with nothing said, and the build ended at the linker naming symbols the package's own C
   * defines — which made every package with a shim unusable as a dependency, and a shim is what a
   * binding to a real library *is*. `packages.md § 7` had already promised the opposite in as many
   * words, refusing build scripts on the grounds that sysl compiles a package's C declaratively.
   *
   * Nothing else in the suite could have caught it: every package above is pure sysl, so the walk
   * that never looked for C was never asked a question it could get wrong.
   */
  "a package's C" - {

    // The discriminating shape: seven is a number only the C knows, and the multiplication is only
    // the sysl's. A build that dropped the C cannot print this, and neither can one that linked the
    // C and lost the sysl.
    val shim = "int geom_seven(void) { return 7; }\n"

    val calling =
      """extern "geom_seven" c_seven() -> int
        |
        |seven_times(n: int) -> int = c_seven() * n""".stripMargin

    "is compiled and linked beside its sysl" in {
      val geom = packageOf("geom-lib", "geom", calling, "", "geom/shim.c" -> shim)

      run(app("""print(geom.seven_times(6))""", s"""g { path = "$geom" }""")) shouldBe "42\n"
    }

    // `15 §7` says *anywhere* in the tree, and the package root is the one place that declares no
    // module — so a walk gathering C only where it found sysl would skip it.
    "is found at the package root as well as beside a module" in {
      val geom = packageOf("geom-lib", "geom", calling, "", "shim.c" -> shim)

      run(app("""print(geom.seven_times(6))""", s"""g { path = "$geom" }""")) shouldBe "42\n"
    }

    // The trap the fix had to answer. Each package is staged into a directory of its own, so two
    // files at one relative path stay two objects — where one shared staging area would have the
    // second overwrite the first and the program lose whatever only the first defined.
    "does not collide with another package's at the same relative path" in {
      val one = packageOf("one", "left", """extern "one_util" c() -> int
                                           |
                                           |value() -> int = c()""".stripMargin,
        "", "left/util.c" -> "int one_util(void) { return 40; }\n")

      val two = packageOf("two", "left", """extern "two_util" c() -> int
                                           |
                                           |value() -> int = c()""".stripMargin,
        "", "left/util.c" -> "int two_util(void) { return 2; }\n")

      run(app("""print(left.value() + other.left.value())""",
        s"""a { path = "$one" }, b { path = "$two", mount = "other" }""")) shouldBe "42\n"
    }

    // Every package the resolver selected is a tree, not only the ones the project named — so a
    // binding two levels down is compiled on the same footing as one the manifest mentions. The
    // consumer here never writes the word `bottom`.
    "is compiled for a package the project never named itself" in {
      val cache = emptyCache()
      val at    = published(cache, "github.com/e/bottom", Version(1, 0, 0), manifest("bottom", "1.0.0"))

      createDirectories(s"$at/bottom")
      writeFile(s"$at/bottom/bottom.sysl",
        "module bottom\n\nextern \"bottom_seven\" c() -> int\n\nseven() -> int = c()\n")
      writeFile(s"$at/bottom/shim.c", "int bottom_seven(void) { return 7; }\n")

      val middle = createTempDirectory("sysl-pkg-mid3-")

      writeFile(s"$middle/${PackageConfig.FileName}",
        manifest("middle", "1.0.0", """b { git = "github.com/e/bottom", version = "1.0.0" }"""))
      createDirectories(s"$middle/middle")
      writeFile(s"$middle/middle/middle.sysl",
        "module middle\n\nsix_sevens() -> int = bottom.seven() * 6\n")

      withCache(cache)(run(app("""print(middle.six_sevens())""", s"""m { path = "$middle" }"""))) shouldBe
        "42\n"
    }

    // The other half of a binding: the C reaches its own library through a `@link` directive, and
    // the directive is written in the package's header where nothing but the package could know it.
    // Named after a library no machine has, so the observation is the same on every platform — the
    // name reached a command line, which is all `15 §8` claims.
    "carries its module's link directive to the command line" in {
      val geom = packageOf("geom-lib", "geom",
        "@link(\"sysl-no-such-library\")\n\nvalue() -> int = 42")

      refused(app("""print(geom.value())""", s"""g { path = "$geom" }""")) should
        include("sysl-no-such-library")
    }

    // The error path. A shim that will not compile is the package author's mistake and the consumer
    // is the one who meets it, so the message has to name the file rather than report that a link
    // went wrong somewhere.
    "that will not compile stops the build, naming the file" in {
      val geom = packageOf("geom-lib", "geom", calling, "", "geom/shim.c" -> "int geom_seven(void) { return\n")

      refused(app("""print(geom.seven_times(6))""", s"""g { path = "$geom" }""")) should
        include("shim.c")
    }
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
