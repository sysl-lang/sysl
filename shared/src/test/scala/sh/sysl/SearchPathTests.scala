package sh.sysl

import io.github.edadma.cross_platform.*

/** `--link-path` and `--include-path` end to end: a library that is really on the disk, really
 * outside the toolchain's search path, and really linked against (`SearchPaths`).
 *
 * **`LinkCommandTests` asserts the command line and this asserts that the command line works**, and
 * the two are not the same claim. A `-L` in the right place proves the driver's decision; only a
 * link proves clang agreed with it. The library here is built by the suite into a temporary
 * directory, which is what makes "outside the default search path" true by construction rather than
 * by depending on what a package manager put on the machine — the reason the gap went unnoticed is
 * that nothing in the suite had ever needed a directory clang did not already know.
 *
 * **Each case is written as a pair.** The failure without the flag is half the test and the more
 * important half: a `--link-path` that reached a command line clang would have accepted anyway
 * proves nothing at all, and the two published packages both bind libraries the macOS SDK stubs, so
 * that mistake would go unnoticed here forever.
 */
class SearchPathTests extends LibraryCliSupport {

  /** A C library at a path nothing searches: a header, an archive, and no relationship to any
   * directory clang was built knowing about.
   *
   * `42` rather than `1` because a link that resolved the wrong symbol, or a shim that was compiled
   * and then not linked, has to produce a different number rather than a plausible one.
   */
  private lazy val probe: Option[(String, String)] = Option.when(Toolchain.clangAvailable) {
    val root    = createTempDirectory("sysl-probe-")
    val include = s"$root/include"
    val lib     = s"$root/lib"

    createDirectories(include)
    createDirectories(lib)
    writeFile(s"$include/probe.h", "int probe_answer(void);\n")
    writeFile(s"$lib/probe.c", "#include <probe.h>\nint probe_answer(void) { return 42; }\n")

    val obj = s"$lib/probe.o"

    Toolchain.compileC(s"$lib/probe.c", obj, Target.default, Toolchain.defaultOptimization,
      SearchPaths(include = List(include))) match
      case Left(err) => fail(s"the probe library did not compile: $err")
      case Right(_)  => ()

    Toolchain.findAr(None).flatMap(Toolchain.archive(List(obj), s"$lib/libprobe.a", _)) match
      case Left(err) => fail(s"the probe library did not archive: $err")
      case Right(_)  => ()

    (include, lib)
  }

  private def guard(): (String, String) = {
    assume(Toolchain.clangAvailable, "clang not available")
    assume(Toolchain.findAr(None).isRight, "no llvm-ar available")
    probe.get
  }

  /** A program reaching the probe library by `link` alone — no C of its own, so the only thing that
   * can fail is finding `libprobe.a`.
   */
  private val calling =
    """@link("probe")
      |
      |extern "probe_answer" c_answer() -> int
      |
      |print(c_answer())
      |""".stripMargin

  "a library outside the toolchain's search path" - {

    "cannot be linked against without being pointed at, which is the whole gap" in {
      guard()

      val (status, notes) = diagnostics(Config(command = "run", file = program(calling)))

      status should not be 0
      notes should include("probe")
    }

    "and links once --link-path names where it is" in {
      val (_, lib) = guard()

      ran(Config(command = "run", file = program(calling), linkPaths = List(lib))) shouldBe "42\n"
    }

    // A directory that holds nothing is not an error — clang searches it, finds nothing, and moves
    // on — so the one that does hold the library still has to be reached.
    "is found among several paths rather than only as the first" in {
      val (_, lib) = guard()

      ran(Config(command = "run", file = program(calling),
        linkPaths = List(createTempDirectory("sysl-empty-"), lib))) shouldBe "42\n"
    }
  }

  /** The half a `-L` cannot stand in for. A binding to a library outside the default prefix has to
   * compile its shim before there is anything to link, and the shim includes that library's header.
   */
  "a header outside the toolchain's search path" - {

    /** A project whose carried C includes the probe's header and calls into it, so the build needs
     * both settings and fails at whichever is missing.
     */
    def doubling(): String = {
      val root = createTempDirectory("sysl-shim-")

      createDirectories(s"$root/m")
      writeFile(s"$root/main.sysl", "print(m.doubled())\n")
      writeFile(s"$root/m/m.sysl",
        """module m
          |@link("probe")
          |
          |extern "shim_doubled" c_doubled() -> int
          |
          |doubled() -> int = c_doubled()
          |""".stripMargin)
      writeFile(s"$root/m/shim.c",
        "#include <probe.h>\nint shim_doubled(void) { return probe_answer() * 2; }\n")
      root
    }

    "stops the build at the include, before anything reaches a linker" in {
      guard()

      val (status, notes) = diagnostics(Config(command = "run", file = doubling()))

      status should not be 0
      notes should include("probe.h")
    }

    // The reason both flags shipped together rather than the link one alone. Given only where the
    // library is, this build now gets *further* and fails just as completely.
    "and --link-path alone does not answer it" in {
      val (_, lib) = guard()

      val (status, notes) = diagnostics(Config(command = "run", file = doubling(), linkPaths = List(lib)))

      status should not be 0
      notes should include("probe.h")
    }

    // 84 rather than 42: the shim compiled against the real header and the archive resolved the
    // function it calls, so neither half can be missing and neither can be the other.
    "and the two together build a binding that could not be built at all before" in {
      val (include, lib) = guard()

      ran(Config(command = "run", file = doubling(), linkPaths = List(lib),
        includePaths = List(include))) shouldBe "84\n"
    }
  }

  /** `--define`, which is a third step along the same path: the library is found, the header is
   * found, and the header refuses because it has not been told how the host project configures it.
   *
   * The shim here `#error`s without its macro, which is what pico-sdk's `pico/cyw43_arch.h` does and
   * is the case that found this — a real consumer where every include path was right and the build
   * still stopped inside a header.
   */
  "a header that configures itself with a macro" - {

    /** A project whose carried C refuses to compile until the host says what it is configured with —
     * the shape of every real SDK header, written small.
     */
    def configured(): String = {
      val root = createTempDirectory("sysl-configured-")

      createDirectories(s"$root/m")
      writeFile(s"$root/main.sysl", "print(m.scaled())\n")
      writeFile(s"$root/m/m.sysl",
        """module m
          |@link("probe")
          |
          |extern "shim_scaled" c_scaled() -> int
          |
          |scaled() -> int = c_scaled()
          |""".stripMargin)
      writeFile(s"$root/m/shim.c",
        "#include <probe.h>\n" +
          "#ifndef PROBE_SCALE\n#error the host project has to say what scale it builds at\n#endif\n" +
          "int shim_scaled(void) { return probe_answer() * PROBE_SCALE; }\n")
      root
    }

    // The half that matters: every path is right and the build still stops, inside the header's own
    // `#error`, which is a failure no amount of `--include-path` can answer.
    "stops the build even with every path it asked for" in {
      val (headers, lib) = guard()

      val (status, notes) = diagnostics(Config(command = "run", file = configured(),
        linkPaths = List(lib), includePaths = List(headers)))

      status should not be 0
      notes should include("what scale it builds at")
    }

    // 126 rather than 42 or 84: the macro reached the shim as a *value* and not merely as a defined
    // name, so a `-D` that arrived empty would give a different number rather than a plausible one.
    "and builds once --define supplies what the header wanted" in {
      val (include, lib) = guard()

      ran(Config(command = "run", file = configured(), linkPaths = List(lib),
        includePaths = List(include), defines = List("PROBE_SCALE=3"))) shouldBe "126\n"
    }
  }

  /** The flags as a user types them. A `Config` built by hand never finds out whether the option is
   * spelled the way the shell has to spell it, which is why `parseArgs` exists.
   */
  "as written on a command line" - {

    "all three are repeatable, and keep the order given" in {
      val c = parsed("run", "p.sysl", "--link-path", "/one", "--link-path", "/two",
        "--include-path", "/inc", "--define", "A", "--define", "B=2")

      c.linkPaths shouldBe List("/one", "/two")
      c.includePaths shouldBe List("/inc")
      c.defines shouldBe List("A", "B=2")
    }

    // `-D` is what a C build system already writes, and a consumer copying its own flags across
    // should not have to translate them.
    "and --define has clang's own short spelling" in {
      parsed("run", "p.sysl", "-D", "NDEBUG").defines shouldBe List("NDEBUG")
    }

    "and a build that names none of them carries none" in {
      val c = parsed("run", "p.sysl")

      c.linkPaths shouldBe empty
      c.includePaths shouldBe empty
      c.defines shouldBe empty
    }
  }

  private def parsed(args: String*): Config =
    parseArgs(args).getOrElse(fail(s"these arguments did not parse: ${args.mkString(" ")}"))
}
