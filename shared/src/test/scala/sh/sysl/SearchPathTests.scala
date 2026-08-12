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

    // The named form is the same flag, so the directory has to reach the C compiler exactly as a
    // bare one does. A name that recorded the requirement and dropped the path would satisfy the
    // check and then fail in clang, which is the failure this whole feature exists to remove.
    "a named --include-path is both a path and an answer" in {
      val c = parsed("run", "p.sysl", "--include-path", "lwip=/sdk/lwip/include")

      c.includePaths shouldBe List("/sdk/lwip/include")
      c.namedIncludes shouldBe Map("lwip" -> "/sdk/lwip/include")
    }

    "and a bare one is only a path" in {
      val c = parsed("run", "p.sysl", "--include-path", "/opt/homebrew/include")

      c.includePaths shouldBe List("/opt/homebrew/include")
      c.namedIncludes shouldBe empty
    }
  }

  /** Which spellings of the flag are a name and which are a directory (`SearchPaths.namedInclude`).
   *
   * The two forms share a flag, so this is the whole of what keeps them apart — and it has to be
   * decidable by looking, since a path read as a name would silently stop reaching the C compiler.
   */
  "the named --include-path form" - {

    "takes a name and a directory" in {
      SearchPaths.namedInclude("lwip=/sdk/include") shouldBe Some("lwip" -> "/sdk/include")
    }

    "allows the punctuation a package name uses" in {
      SearchPaths.namedInclude("pico-sdk_2=/x") shouldBe Some("pico-sdk_2" -> "/x")
    }

    // The path is the rest of the string rather than the next segment, so a directory holding an
    // `=` still arrives whole.
    "splits at the first '=' and keeps the rest" in {
      SearchPaths.namedInclude("lwip=/sdk/a=b/include") shouldBe Some("lwip" -> "/sdk/a=b/include")
    }

    "is not a name where there is no '='" in {
      SearchPaths.namedInclude("/opt/homebrew/include") shouldBe None
    }

    // Every shape of ordinary directory, none of which may be read as a name. The first two are the
    // cases that decide the rule: something before an `=` that holds a separator is a path.
    "is not a name where what precedes the '=' could not be one" in {
      SearchPaths.namedInclude("/opt/x=y") shouldBe None
      SearchPaths.namedInclude("../x=y") shouldBe None
      SearchPaths.namedInclude("2fast=y") shouldBe None
      SearchPaths.namedInclude("=y") shouldBe None
    }

    // A name with nothing after it is a mistake either way, and taking it as a directory is the
    // reading that fails where the reader can see it: clang says the path does not exist.
    "is not a name where nothing follows the '='" in {
      SearchPaths.namedInclude("lwip=") shouldBe None
    }
  }

  /** A package saying which headers its own C needs, and the driver checking somebody supplied them
   * (`packages.md § 8`).
   *
   * **The refusal is the feature.** `--include-path` already worked, and a project that passes it
   * built before this existed and builds now — what did not exist was any way for the *package* to
   * say it needed one, so the build failed inside a C compiler that names the header and knows
   * nothing about sysl, the package, or the flag.
   */
  "a package that declares the headers it needs" - {

    /** The doubling shim again, with a `package.hocon` saying out loud what its C includes. */
    def declaring(): String = {
      val root = createTempDirectory("sysl-declared-")

      createDirectories(s"$root/m")
      writeFile(s"$root/main.sysl", "print(m.doubled())\n")
      writeFile(s"$root/package.hocon",
        """package { name = "declared" }
          |requires {
          |  headers { probe = "the probe library's headers, wherever this machine keeps them" }
          |}
          |""".stripMargin)
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

    // The whole point, in one case: the build stops naming the requirement, the reason and the flag,
    // and it stops before clang has run at all — so `probe.h` is not in the message, because nothing
    // got far enough to look for it.
    "is refused by name rather than by a header the reader has never heard of" in {
      val (status, notes) = diagnostics(Config(command = "build", file = declaring()))

      status should not be 0
      notes should include("'probe'")
      notes should include("wherever this machine keeps them")
      notes should include("--include-path probe=")
      notes should not include "probe.h"
    }

    // A bare path compiles the C perfectly well and is deliberately *not* an answer: the check is
    // about what the build says it has rather than about what it might happen to find. Reading a
    // bare path as an answer would make the declaration unenforceable.
    // Bound as `dir` rather than `include`, which would shadow the matcher of that name two lines
    // down and turn the assertion into a `charAt`.
    "is not answered by a bare --include-path" in {
      val (dir, _) = guard()

      val (status, notes) =
        diagnostics(Config(command = "build", file = declaring(), includePaths = List(dir)))

      status should not be 0
      notes should include("'probe'")
    }

    // Both fields, because a `Config` is what the parser *produced*: the name answers the
    // requirement and the directory is what reaches clang, and the pair of cases above is what
    // asserts one flag writes both.
    "and builds once the named form answers it" in {
      val (include, lib) = guard()

      ran(Config(command = "run", file = declaring(), linkPaths = List(lib),
        includePaths = List(include), namedIncludes = Map("probe" -> include))) shouldBe "84\n"
    }

    // A command that compiles no C has nothing unmet. Charging `emit-llvm` for a path it would never
    // open would turn a requirement about the C into a requirement about the package.
    "and does not hold up a command that compiles no C" in {
      val (status, _) = diagnostics(Config(command = "emit-llvm", file = declaring()))

      status shouldBe 0
    }
  }

  /** The same declaration, reached the third way: as a `--lib` **source root**.
   *
   * A package arrives at a build by three roads and only this one skipped the check. Through
   * `dependencies` its manifest comes back with the graph and is asked; as a `.syslib` it needs no
   * header at all, since the artifact carries the *measured value* of a `c const` rather than the C
   * expression that produced it. Handed the same directory, the driver read its `.sysl` files and
   * opened nothing else — so the sentence the package wrote for this moment went unread, and clang
   * answered in its place.
   */
  "a declaring package reached as a --lib source root" - {

    /** The declaring package with no `main` — a library rather than a project — and a consumer of it
     * in a directory of its own, which is what makes `--lib` the only road between them.
     */
    def libraryAndConsumer(): (String, String) = {
      val lib = createTempDirectory("sysl-declared-lib-")

      createDirectories(s"$lib/m")
      writeFile(s"$lib/package.hocon",
        """package { name = "declared" }
          |requires {
          |  headers { probe = "the probe library's headers, wherever this machine keeps them" }
          |}
          |""".stripMargin)
      writeFile(s"$lib/m/m.sysl",
        """module m
          |@link("probe")
          |
          |extern "shim_doubled" c_doubled() -> int
          |
          |doubled() -> int = c_doubled()
          |""".stripMargin)
      writeFile(s"$lib/m/shim.c",
        "#include <probe.h>\nint shim_doubled(void) { return probe_answer() * 2; }\n")

      val consumer = createTempDirectory("sysl-lib-consumer-")

      writeFile(s"$consumer/main.sysl", "print(m.doubled())\n")
      (lib, s"$consumer/main.sysl")
    }

    // The case that used to fall through: the consumer was handed clang's `'probe.h' file not
    // found`, which names neither the package that wanted it nor the flag that answers it.
    "is refused by name rather than by a header the reader has never heard of" in {
      val (lib, main)     = libraryAndConsumer()
      val (status, notes) = diagnostics(Config(command = "build", file = main, libs = List(lib)))

      status should not be 0
      notes should include("'probe'")
      notes should include("wherever this machine keeps them")
      notes should include("--include-path probe=")
      notes should not include "probe.h"
    }

    // The root is named as the reader wrote it — a path here, where it is a coordinate for a
    // dependency. Both are the thing the person reading the message typed and can go and look at.
    "naming the root the way it was given" in {
      val (lib, main) = libraryAndConsumer()
      val (_, notes)  = diagnostics(Config(command = "build", file = main, libs = List(lib)))

      notes should include(lib)
    }

    "and builds once the named form answers it" in {
      val (include, libDir) = guard()
      val (lib, main)       = libraryAndConsumer()

      ran(Config(command = "run", file = main, libs = List(lib), linkPaths = List(libDir),
        includePaths = List(include), namedIncludes = Map("probe" -> include))) shouldBe "84\n"
    }

    // A source root need not be a package at all, which is most of what `--lib` is for. One with no
    // manifest has nothing to declare and must go on building exactly as it did.
    "while a source root with no manifest is unaffected" in {
      val root = createTempDirectory("sysl-plain-lib-")

      createDirectories(s"$root/m")
      writeFile(s"$root/m/m.sysl", "module m\n\ndoubled() -> int = 21 * 4\n")

      val consumer = createTempDirectory("sysl-plain-consumer-")

      writeFile(s"$consumer/main.sysl", "print(m.doubled())\n")
      ran(Config(command = "run", file = s"$consumer/main.sysl", libs = List(root))) shouldBe "84\n"
    }
  }

  private def parsed(args: String*): Config =
    parseArgs(args).getOrElse(fail(s"these arguments did not parse: ${args.mkString(" ")}"))
}
