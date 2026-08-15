package sh.sysl

import io.github.edadma.cross_platform.*

/** A package that names an installed library, answered by asking this machine where it is
 * (`packages.md § 8`, `PkgConfig`).
 *
 * ==Why the happy path discovers its module rather than naming one==
 *
 * There is no library every machine has. Hard-coding `cairo` would make this suite pass here and skip
 * — or worse, fail — on a runner with a different set installed, and picking something "obviously
 * present" like zlib is the same bet with better odds. So the cases that need a real answer ask
 * `pkg-config` what it knows and use the first of those, which tests the query against whatever this
 * machine actually has.
 *
 * The one case that cannot be written that way is the end-to-end one: a program can only be compiled
 * against a library whose header it knows the name of. That case names cairo and is skipped where
 * cairo is not installed, which is the same gate `Toolchain.clangAvailable` already applies to every
 * test that links.
 */
class PkgConfigTests extends PackageCacheSupport {

  /** A module `pkg-config` on this machine knows about, when it knows about any.
   *
   * `--list-all` prints `name description`, so the first word of the first line is a module that is
   * certain to answer — which is what makes the cases below independent of what is installed.
   */
  private lazy val someModule: Option[String] =
    if !PkgConfig.available then None
    else
      val result = exec(Seq("pkg-config", "--list-all"))

      if result.exitCode != 0 then None
      else result.stdout.linesIterator.map(_.trim).find(_.nonEmpty).map(_.takeWhile(!_.isWhitespace))

  /** A project declaring `pkg_config` requirements, and a program that prints without touching the
   * library — so what these cases measure is the requirement being answered, not a binding working.
   */
  private def project(needs: String, caps: String = "os = true, heap = true",
                      program: String = "print(42)"): String = {
    val root  = createTempDirectory("sysl-pkgc-")
    val wants = if caps.isEmpty then "" else s"$caps, "

    writeFile(s"$root/${PackageConfig.FileName}",
      s"""package { name = "app", version = "0.1.0" }
         |requires { ${wants}pkg_config { $needs } }
         |""".stripMargin)
    writeFile(s"$root/main.sysl", program)
    root
  }

  /** A project that declares nothing of its own, so what a case using it measures is what the root or
   * the dependency beside it declared.
   */
  private def plainProject(): String = {
    val root = createTempDirectory("sysl-pkgc-plain-")

    writeFile(s"$root/main.sysl", "print(42)\n")
    root
  }

  /** A package on disk whose manifest declares a `pkg_config` requirement, and one module. */
  private def declaringPackage(needs: String): String = {
    val root = createTempDirectory("sysl-pkgc-dep-")

    writeFile(s"$root/${PackageConfig.FileName}",
      s"""package { name = "dep", version = "1.0.0" }
         |requires { pkg_config { $needs } }
         |""".stripMargin)
    createDirectories(s"$root/dep")
    writeFile(s"$root/dep/dep.sysl", "module dep\n\ndouble(n: int) -> int = n * 2\n")
    root
  }

  /** A project that depends on one, by path so nothing reaches the network. */
  private def dependingProject(dep: String): String = {
    val root = createTempDirectory("sysl-pkgc-app-")

    writeFile(s"$root/${PackageConfig.FileName}",
      s"""package { name = "app", version = "0.1.0" }
         |dependencies { d { path = "$dep" } }
         |""".stripMargin)
    writeFile(s"$root/main.sysl", "print(dep.double(21))\n")
    root
  }

  private def ran(cfg: Config): String = {
    val out    = new java.io.ByteArrayOutputStream
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(out)(Console.withErr(notes)(sh.sysl.execute(cfg)))

    if status != 0 then fail(s"the driver exited with $status:\n${out.toString}${notes.toString}")

    out.toString
  }

  private def refusalFrom(cfg: Config): String = {
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(Discarded)(Console.withErr(notes)(sh.sysl.execute(cfg)))

    if status == 0 then fail("expected a refusal, got a build")

    notes.toString
  }

  "a library this machine does not have" - {

    // The reason the package wrote is quoted back, because it is the only part of this that names
    // which library is meant — and the flags are named because they are the other way out.
    "is refused by name, with the package's own reason and both ways to answer it" in {
      assume(PkgConfig.available)

      val notes = refusalFrom(Config(command = "run",
        file = project("""kayro = "the Kayro graphics library, which does not exist" """)))

      notes should include("needs the 'kayro' library")
      notes should include("knows no 'kayro'")
      notes should include("the Kayro graphics library, which does not exist")
      notes should include("--include-path kayro=<dir>")
      notes should include("--link-path <dir>")
    }
  }

  "a library the command line answered" - {

    // The override that keeps a hermetic build, a hand-built prefix and a broken `.pc` from being
    // hostage to what happens to be installed. It is also what makes this addition unable to break a
    // build that works today: every one of them passes these flags already.
    "is not asked about at all, even where pkg-config has never heard of it" in {
      assume(Toolchain.clangAvailable)

      // Run rather than merely not refused: a probe that ran anyway would refuse before anything
      // could be compiled, so getting the program's own output is the assertion.
      ran(Config(command = "run",
                 file = project("""kayro = "a library nothing has" """),
                 namedIncludes = Map("kayro" -> "/nonexistent/include"))) shouldBe "42\n"
    }
  }

  "a build for another machine" - {

    /** The guard that matters most. `pkg-config` answers for the machine it runs on, so a
     * freestanding build that silently took this machine's headers would link and be wrong somewhere
     * nobody can see — which is worse than any refusal.
     */
    "is refused rather than answered with this machine's paths" in {
      val notes = refusalFrom(Config(command = "build", target = Some("thumbv7m-freestanding"),
        file = project("""sdl3 = "SDL3 — brew install sdl3" """, caps = "",
                       program = "answer() -> int = 42")))

      notes should include("needs the 'sdl3' library")
      notes should include("thumbv7m-freestanding")
      notes should include("rather than for this machine")
    }

    // The same declaration, on the same machine, for the host: what differs is only the target, so
    // this is what says the refusal above is about the target and not about the library.
    "where the same package for the host asks this machine and is answered" in {
      assume(someModule.isDefined)
      assume(Toolchain.clangAvailable)

      ran(Config(command = "run",
                 file = project(s"""${someModule.get} = "a library this machine has" """))) shouldBe "42\n"
    }
  }

  /** The declaration is worth the same whichever road the package arrived by, which is the rule
   * `packages.md § 8` already states for header requirements. These are the two roads a *package's*
   * declaration travels — the project's own is every other case in this suite.
   */
  "a package that arrived some other way" - {

    // The whole point of the requirement: the refusal names the package that wanted the library, not
    // the project that merely depends on it, so the reader knows who to go and read.
    "is asked when it came through dependencies, and the refusal names it rather than the project" in {
      val notes = refusalFrom(Config(command = "run",
        file = dependingProject(declaringPackage("""kayro = "a library nothing has" """))))

      notes should include("needs the 'kayro' library")
      notes should include("a library nothing has")
      notes should not include "this project"
    }

    // A dependency that asks for something this machine has does not stop anything, and the program
    // it came for still runs — which is what says the check above is a check and not a wall.
    "and builds when the library it named is one this machine has" in {
      assume(someModule.isDefined)
      assume(Toolchain.clangAvailable)

      ran(Config(command = "run",
        file = dependingProject(declaringPackage(s"""${someModule.get} = "one this machine has" """)))) shouldBe "42\n"
    }

    // The same package handed over as a directory rather than fetched. It used to be the road that
    // fell through for header requirements, so it is the one worth pinning here from the start.
    "is asked when it came as a --lib source root" in {
      val notes = refusalFrom(Config(command = "run", file = plainProject(),
        libs = List(declaringPackage("""kayro = "a library nothing has" """))))

      notes should include("needs the 'kayro' library")
      notes should include("a library nothing has")
    }
  }

  "the answer this machine gives" - {

    /** Measured against the tool rather than against a shape.
     *
     * The first attempt here asserted that the two halves were not both empty, which is a claim about
     * the *library* and not about this code — and it is false for the first module on this machine,
     * which turns out to be `pkg-config`'s own virtual package: it exists, answers, and has neither
     * cflags nor libs. What is actually being tested is the splitting, so the assertion is that the
     * tokens are exactly what the tool printed.
     */
    "is the tokens pkg-config printed, split the way a shell would split them" in {
      assume(someModule.isDefined)

      val mod   = someModule.get
      val flags = PkgConfig.query(mod).toOption.getOrElse(fail(s"pkg-config would not answer for '$mod'"))

      def tokens(what: String): List[String] =
        exec(Seq("pkg-config", what, mod)).stdout.split("\\s+").toList.filter(_.nonEmpty)

      flags.cflags shouldBe tokens("--cflags")
      flags.ldflags shouldBe tokens("--libs")
    }

    "is the same on a second asking, because it is memoized" in {
      assume(someModule.isDefined)

      PkgConfig.query(someModule.get) shouldBe PkgConfig.query(someModule.get)
    }

    "and a module nothing knows is a reason rather than an exception" in {
      assume(PkgConfig.available)

      PkgConfig.query("kayro-no-such-module") match
        case Left(why) => why should include("knows no")
        case Right(_)  => fail("pkg-config answered for a module that does not exist")
    }
  }

  "the probed flags reach the tools" - {

    /** The whole point, end to end and with no flags on the command line: a `c const` is measured out
     * of a header only `--cflags` can find, and the program then calls a function only `--libs` can
     * resolve. Either half missing fails this.
     *
     * cairo by name, because a program has to include a header it knows about. Skipped where cairo is
     * not installed.
     */
    "so a c const is measured and the library links, with nothing on the command line" in {
      assume(Toolchain.clangAvailable)
      assume(PkgConfig.query("cairo").isRight)

      val root = createTempDirectory("sysl-pkgc-run-")

      writeFile(s"$root/${PackageConfig.FileName}",
        s"""package { name = "app", version = "0.1.0" }
           |requires { os = true, heap = true, pkg_config { cairo = "cairo — brew install cairo" } }
           |""".stripMargin)
      writeFile(s"$root/main.sysl",
        """@link("cairo")
          |@include("cairo.h")
          |
          |c const
          |    VERSION: int = "CAIRO_VERSION"
          |
          |extern cairo_version() -> int
          |
          |print(VERSION == cairo_version())
          |""".stripMargin)

      val out    = new java.io.ByteArrayOutputStream
      val notes  = new java.io.ByteArrayOutputStream
      val status = Console.withOut(out)(Console.withErr(notes)(
        sh.sysl.execute(Config(command = "run", file = root))))

      if status != 0 then fail(s"the driver exited with $status:\n${out.toString}${notes.toString}")

      // The compile-time measurement and the run-time call agree, which is one assertion covering
      // both halves: a wrong include path fails to build and a wrong link line fails to link.
      out.toString shouldBe "true\n"
    }
  }
}
