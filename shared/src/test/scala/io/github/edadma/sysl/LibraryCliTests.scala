package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl build-lib` and `--lib`, driven through the driver itself.
 *
 * The compiler's own API cannot reach any of this. Which of two shapes a `--lib` path names, what a
 * corrupt artifact does, whether a temporary object survives the run — all of it lives in
 * `execute`, and a test that called `Compiler` and `Toolchain` directly would be re-implementing the
 * driver and pinning its own arrangement rather than the one a user meets.
 */
class LibraryCliTests extends AnyFreeSpec with Matchers {

  /** The driver, under a name of its own: `Suite` has an `execute` too, and it is the one that wins
   * unqualified.
   *
   * **A compilation that finds no standard module is an error**, and these tests run in a tree where
   * none has been built — so one that says nothing about the core gets `--no-core-lib`, which is what
   * it means: *this test is about `--lib`, or about the driver, and not about which standard module a
   * compilation gets.* Spelled once here rather than at twenty call sites.
   *
   * A test that mentions the core **in any way** — names one, refuses one, redirects the search path,
   * or builds one — opts out of this entirely and is run exactly as it was written. Otherwise this
   * default would quietly rewrite the premise of the very tests that exist to pin it.
   */
  private def cli(cfg: Config): Int =
    io.github.edadma.sysl.execute(if mentionsCore(cfg) then cfg else cfg.copy(noCoreLib = true))

  private def mentionsCore(cfg: Config): Boolean =
    cfg.core || cfg.noCoreLib || cfg.coreLib.isDefined || cfg.coreSearch != LibraryArtifact.coreDefault

  private val library =
    """module demo
      |
      |double(n: int) -> int = n * 2
      |
      |larger[T: Ord](a: T, b: T) -> T = if a < b then b else a
      |""".stripMargin

  /** A second library, so that "more than one `--lib`" has something to be more than one of. Its
   * module is distinct from `demo`'s, which is what makes a program calling both able to say which
   * it meant.
   */
  private val other =
    """module extra
      |
      |triple(n: int) -> int = n * 3
      |""".stripMargin

  /** A library tree on disk, as the driver reads one: a root, with the module in a directory under
   * it whose name is the module's.
   */
  private def libraryRoot(): String = rootOf("demo", library)

  private def rootOf(module: String, text: String): String = {
    val root = createTempDirectory("sysl-cli-lib-")
    val dir  = s"$root/$module"

    createDirectory(dir)
    writeFile(s"$dir/lib.sysl", text)
    root
  }

  private def program(text: String): String = {
    val path = createTempFile("sysl-cli-prog-", ".sysl")
    writeFile(path, text)
    path
  }

  /** A built artifact, and the paths that made it. */
  private def artifact(): String = artifactOf(libraryRoot())

  private def artifactOf(root: String): String = {
    val out = createTempFile("sysl-cli-", LibraryArtifact.extension)

    cli(Config(command = "build-lib", file = root, output = Some(out))) shouldBe 0
    out
  }

  /** The standard module built into an artifact, which is what `--core-lib` consumes. Built once —
   * it is the slowest thing in this file, and every test below wants the same one.
   */
  private lazy val core: String = {
    val out = createTempFile("sysl-cli-core-", LibraryArtifact.extension)

    cli(Config(command = "build-lib", file = CoreLib.root.get, output = Some(out), core = true)) shouldBe 0
    out
  }

  /** A driver run with its diagnostics captured, since for `--core-lib` the warning *is* the
   * observation: falling back and linking both exit 0, and only stderr tells them apart.
   */
  private def diagnostics(cfg: Config): (Int, String) = {
    val captured = new java.io.ByteArrayOutputStream

    val status = Console.withErr(captured)(cli(cfg))

    (status, captured.toString)
  }

  /** A driver run with its emitted module captured. `emit-llvm` prints, so this is what lets a test
   * see *which* standard module a compilation was given rather than only that it succeeded: the
   * library arriving prebuilt is a declaration where the built-in copy is a definition.
   */
  private def emitted(cfg: Config): String = {
    val captured = new java.io.ByteArrayOutputStream

    Console.withOut(captured)(cli(cfg)) shouldBe 0
    captured.toString
  }

  /** The symbols an emitted module `define`s or `declare`s. Which of the two a library's symbol
   * appears under is how a test tells a library that was **linked** from one that was compiled in,
   * and it is the only difference visible from outside.
   */
  private def symbols(ir: String, form: String): Set[String] =
    ir.linesIterator.filter(_.startsWith(s"$form ")).flatMap { line =>
      val at = line.indexOf('@')

      Option.when(at >= 0)(line.drop(at + 1).takeWhile(c => c != '(' && c != ' '))
    }.toSet

  /** The same, narrowed to the standard module's own. */
  private def libraryOwn(ir: String, form: String): Set[String] =
    symbols(ir, form).filter(_.startsWith(Library.key("")))

  /** One function's emitted body, so that two compilations can be compared on what a function does
   * rather than only on which functions there are.
   */
  private def bodyOf(ir: String, name: String): List[String] =
    ir.linesIterator
      .dropWhile(l => !(l.startsWith("define ") && l.contains(s"@$name(")))
      .takeWhile(_ != "}")
      .toList

  /** An artifact carrying given metadata and nothing the linker would want. Assembled rather than
   * built, because every one of these is a container a toolchain would refuse to produce.
   */
  private def artifactOfMeta(meta: String): Array[Byte] =
    FakeAr(LibraryArtifact.metadataMember -> LibraryArtifact.frame(meta))

  /** An artifact whose frame promises more than the member holds. The version comes from the constant
   * rather than being written out, so that a bump to the container format leaves these testing
   * truncation rather than the version check that would otherwise fire ahead of it.
   */
  private def truncated: Array[Byte] =
    FakeAr(LibraryArtifact.metadataMember ->
      LibraryArtifact.framed(s"syslib ${LibraryArtifact.Version} 900", "short"))

  /** The real core's metadata wearing somebody else's fingerprint — a readable, decodable artifact
   * that is simply not the standard module this compiler carries.
   */
  private def stale: String = {
    val meta = LibraryArtifact.metadataOf(core, readBytes(core)) match
      case Right(m)  => m
      case Left(err) => fail(err)

    "0000000000000000" + meta.drop(meta.indexOf('\n'))
  }

  private def corrupt(bytes: Array[Byte]): String = {
    val path = createTempFile("sysl-cli-bad-", LibraryArtifact.extension)
    writeBytes(path, bytes)
    path
  }

  "build-lib" - {

    "writes an artifact that carries both halves" in {
      val out   = artifact()
      val bytes = readBytes(out)

      LibraryArtifact.metadataOf(out, bytes) match
        case Right(meta) => meta should include("demo$double")
        case Left(err)   => fail(err)

      // The compiled half is not read back through any of our own code, so the check that it is
      // there has to be made against the container: a member of the name it was archived under, with
      // something in it. An artifact whose object half went missing would still decode perfectly and
      // fail at the link of every program that used it.
      Ar.members(bytes) match
        case Right(members) =>
          members.find(_.name == LibraryArtifact.codeMember).map(_.body.length).getOrElse(0) should be > 0
        case Left(why) => fail(why)
    }

    "names the artifact after the root when no output is given" in {
      // The default matters because it is what a reader gets when they follow the help text, and
      // an extension that did not match `--lib`'s test would make the two halves disagree.
      val root = libraryRoot()

      cli(Config(command = "build-lib", file = root)) shouldBe 0

      val expected = Project.basename(root) + LibraryArtifact.extension

      isFile(expected) shouldBe true
      deleteFile(expected)
    }

    "refuses a root holding no source rather than writing an empty artifact" in {
      cli(Config(command = "build-lib", file = createTempDirectory("sysl-cli-empty-"))) should not be 0
    }

    "takes the archiver it is told to use" in {
      // Worth pinning both ways round. That a named archiver is *used* is what the failing case below
      // cannot show on its own — an option that was read and then ignored would refuse a bad path
      // exactly as loudly while quietly building every library with something else.
      val ar = Toolchain.findAr(None) match
        case Right(path) => path
        case Left(why)   => cancel(why)

      val out = createTempFile("sysl-cli-named-ar-", LibraryArtifact.extension)

      cli(Config(command = "build-lib", file = libraryRoot(), output = Some(out), ar = Some(ar))) shouldBe 0

      LibraryArtifact.metadataOf(out, readBytes(out)) should matchPattern { case Right(_) => }
    }

    "refuses an archiver it cannot run rather than searching for another" in {
      // Someone who wrote down which archiver to use is owed the error. Falling back would build the
      // library with a different tool than the one asked for and say nothing about it — and the whole
      // reason to name one is a machine where the one that would be found is the wrong one.
      val out = createTempFile("sysl-cli-bad-ar-", LibraryArtifact.extension)
      val (status, notes) =
        diagnostics(Config(command = "build-lib", file = libraryRoot(), output = Some(out),
          ar = Some(s"${createTempDirectory("sysl-cli-noar-")}/llvm-ar")))

      status should not be 0
      notes should include("--ar")
    }

    "refuses a library that does not check, and writes nothing" in {
      val root = createTempDirectory("sysl-cli-bad-lib-")

      createDirectory(s"$root/demo")
      writeFile(s"$root/demo/lib.sysl", "module demo\n\nf() -> int = \"no\"\n")

      val out = s"$root/out${LibraryArtifact.extension}"

      cli(Config(command = "build-lib", file = root, output = Some(out))) should not be 0
      isFile(out) shouldBe false
    }
  }

  "a library carrying C" - {

    /* A C file beside a library's sysl, compiled with it and archived into the same artifact
     * (`15 §7`). It is what makes a binding to a real C library writable: `sizeof(regex_t)`, the
     * value of a macro like `REG_EXTENDED`, an anonymous union — each is reachable from C and from
     * nothing else, and a few lines of C turn each into an ordinary function `extern` can declare.
     *
     * The sysl side needs nothing new. That is the claim these tests are really pinning: the whole
     * feature lives in the build, and a shim is reached by the `extern` that was already there. */

    val shim = "int demo_seven(void) { return 7; }\n"

    val usingShim =
      """module demo
        |
        |extern "demo_seven" c_seven() -> int
        |
        |seven_times(n: int) -> int = c_seven() * n
        |""".stripMargin

    def rootWithC(module: String, sysl: String, cFiles: (String, String)*): String = {
      val root = createTempDirectory("sysl-cli-clib-")
      val dir  = s"$root/$module"

      createDirectory(dir)
      writeFile(s"$dir/lib.sysl", sysl)
      cFiles.foreach((name, text) => writeFile(s"$dir/$name", text))
      root
    }

    /** A driver run with the program's own output captured — `run` prints what the child wrote, so
     * this is what lets a test assert the answer C computed rather than only that the link held.
     */
    def ran(cfg: Config): String = {
      val captured = new java.io.ByteArrayOutputStream

      Console.withOut(captured)(cli(cfg)) shouldBe 0
      captured.toString
    }

    def fingerprintOf(out: String): String =
      LibraryArtifact.metadataOf(out, readBytes(out)).flatMap(LibraryArtifact.read(out, _, Target.default)) match
        case Right((_, _, fingerprint)) => fingerprint
        case Left(err)                  => fail(err)

    "is archived as a member of its own, named after where it was found" in {
      val out = artifactOf(rootWithC("demo", usingShim, "shim.c" -> shim))

      Ar.members(readBytes(out)) match
        case Right(members) =>
          // The directory is kept in the name, which is what makes it unique across the library.
          members.find(_.name == "demo.shim.o").map(_.body.length).getOrElse(0) should be > 0
        case Left(why) => fail(why)
    }

    "and a program calling through the library reaches it" in {
      assume(Toolchain.clangAvailable, "clang not available")

      // The sharp shape, and not merely a program calling C directly: `demo$seven_times` lives in
      // the library's own compiled member and leaves `demo_seven` undefined, so the linker has to
      // resolve one member of the archive from another. An artifact that carried the shim but did
      // not index it would compile, link the first member, and fail here.
      ran(Config(command = "run", file = program("print(demo.seven_times(3))"),
        libs = List(artifactOf(rootWithC("demo", usingShim, "shim.c" -> shim))))) shouldBe "21\n"
    }

    "which is what makes a caller-allocated C type bindable" in {
      assume(Toolchain.clangAvailable, "clang not available")

      // The case the feature exists for. `regex_t` has to be allocated by the caller and its size is
      // known only to the target's own headers — 32 bytes here, 64 under glibc — so a sysl program
      // has no way to spell the storage. The shim allocates it, and the sysl side never learns the
      // size at all: it holds an opaque `*u8` and the numbers stay where they are checked.
      //
      // `REG_EXTENDED` is the same problem in miniature. It is a `#define`, so it has no symbol to
      // link against and nothing but C can read it.
      val regexShim =
        """#include <regex.h>
          |#include <stdlib.h>
          |
          |void *demo_regex_new(void) { return malloc(sizeof(regex_t)); }
          |
          |int demo_regex_compile(void *re, const char *pattern) {
          |    return regcomp((regex_t *)re, pattern, REG_EXTENDED);
          |}
          |
          |int demo_regex_matches(void *re, const char *s) {
          |    return regexec((regex_t *)re, s, 0, NULL, 0) == 0;
          |}
          |
          |void demo_regex_free(void *re) {
          |    regfree((regex_t *)re);
          |    free(re);
          |}
          |""".stripMargin

      val binding =
        """module rx
          |
          |extern "demo_regex_new" regex_new() -> *u8
          |extern "demo_regex_compile" regex_compile(re: *u8, pattern: *u8) -> int
          |extern "demo_regex_matches" regex_matches(re: *u8, s: *u8) -> int
          |extern "demo_regex_free" regex_free(re: *u8)
          |""".stripMargin

      val prog =
        """var re = rx.regex_new()
          |print(rx.regex_compile(re, c"^a+b$") == 0)
          |print(rx.regex_matches(re, c"aaab") == 1)
          |print(rx.regex_matches(re, c"xyz") == 1)
          |rx.regex_free(re)
          |""".stripMargin

      // Discriminating on purpose: a match, and a non-match of the same pattern. A binding that
      // returned a constant, or one whose `regex_t` was too small to survive being written into,
      // would pass on the first line and fail on one of the others.
      ran(Config(command = "run", file = program(prog),
        libs = List(artifactOf(rootWithC("rx", binding, "regex.c" -> regexShim))))) shouldBe
        "true\ntrue\nfalse\n"
    }

    "and two modules may each hold a file of the same name" in {
      // `ar r` replaces by name, so a member name built from the basename alone would have the
      // second of these silently evict the first — and the library would ship missing whatever only
      // the first defined. Nothing else in the suite would notice.
      val root = createTempDirectory("sysl-cli-two-c-")

      for (module, symbol, value) <- List(("one", "one_util", 1), ("two", "two_util", 2)) do {
        createDirectory(s"$root/$module")
        writeFile(s"$root/$module/lib.sysl",
          s"""module $module
             |
             |extern "$symbol" util() -> int
             |""".stripMargin)
        writeFile(s"$root/$module/util.c", s"int $symbol(void) { return $value; }\n")
      }

      val out = createTempFile("sysl-cli-two-c-", LibraryArtifact.extension)

      cli(Config(command = "build-lib", file = root, output = Some(out))) shouldBe 0

      Ar.members(readBytes(out)) match
        case Right(members) => members.map(_.name) should contain allOf ("one.util.o", "two.util.o")
        case Left(why)      => fail(why)

      assume(Toolchain.clangAvailable, "clang not available")

      // And both still resolve, which is the part the member names are in aid of.
      ran(Config(command = "run", file = program("print(one.util() + two.util())"),
        libs = List(out))) shouldBe "3\n"
    }

    "while a C file that would take the code member's name is refused" in {
      // The one collision the naming scheme cannot rule out by construction, and the worst: this
      // member would evict the object the whole library is, leaving an artifact that builds, reads
      // back perfectly, and fails to link every program that uses it.
      val root = createTempDirectory("sysl-cli-clash-")

      createDirectory(s"$root/demo")
      writeFile(s"$root/demo/lib.sysl", library)
      createDirectory(s"$root/sysl")
      writeFile(s"$root/sysl/code.c", "int f(void) { return 0; }\n")

      val out = createTempFile("sysl-cli-clash-", LibraryArtifact.extension)
      val (status, notes) = diagnostics(Config(command = "build-lib", file = root, output = Some(out)))

      status should not be 0
      notes should include(LibraryArtifact.codeMember)
    }

    "a C file that does not compile stops the build and names itself" in {
      // The error path. Without the file in the message the user is handed clang's complaint about a
      // path under a temporary directory, with nothing saying which of their sources it came from.
      val root = rootWithC("demo", library, "broken.c" -> "int oops(void) { return \n")
      val out  = createTempFile("sysl-cli-bad-c-", LibraryArtifact.extension)

      deleteFile(out)

      val (status, notes) = diagnostics(Config(command = "build-lib", file = root, output = Some(out)))

      status should not be 0
      notes should include("broken.c")
      isFile(out) shouldBe false
    }

    "and the binding it makes possible really matches, at offsets nothing could have guessed" in {
      assume(Toolchain.clangAvailable, "clang not available")

      // A binding built the way a real one is: the C holds everything only a header knows — the size
      // of a `regex_t`, `REG_EXTENDED`, the layout of a `regmatch_t` — and the sysl side holds an
      // opaque pointer and four integers.
      //
      // Every case below is chosen so that only real POSIX matching gives the number. A test whose
      // matches all began at 0 would be satisfied by a binding that answered `0..len` to anything
      // that matched at all, which is the coincidence worth ruling out: here a match starts and ends
      // mid-string, a bounded repeat has to stop at its ceiling, an anchor refuses, a class is
      // negated, case matters, a group carries a quantifier, and one match is empty.
      //
      // The offsets are POSIX and portable. The message for the bad pattern is the C library's own,
      // worded differently by BSD and glibc, so only its presence is pinned.
      val regexShim =
        """#include <regex.h>
          |#include <stdlib.h>
          |
          |void *probe_rx_new(void) { return calloc(1, sizeof(regex_t)); }
          |
          |void probe_rx_free(void *re) {
          |    if (re) { regfree((regex_t *)re); free(re); }
          |}
          |
          |int probe_rx_compile(void *re, const char *pattern) {
          |    return regcomp((regex_t *)re, pattern, REG_EXTENDED);
          |}
          |
          |int probe_rx_exec(void *re, const char *s, long *so, long *eo) {
          |    regmatch_t m;
          |
          |    if (regexec((regex_t *)re, s, 1, &m, 0) != 0) return 0;
          |
          |    *so = (long)m.rm_so;
          |    *eo = (long)m.rm_eo;
          |
          |    return 1;
          |}
          |
          |void probe_rx_error(void *re, int code, char *buf, unsigned long n) {
          |    regerror(code, (regex_t *)re, buf, (size_t)n);
          |}
          |""".stripMargin

      val binding =
        """module rx
          |
          |import sysl.text.{cstring, from_cstring}
          |
          |extern "probe_rx_new" c_new() -> *u8
          |extern "probe_rx_free" c_free(re: *u8)
          |extern "probe_rx_compile" c_compile(re: *u8, pattern: *u8) -> int
          |extern "probe_rx_exec" c_exec(re: *u8, s: *u8, so: *i64, eo: *i64) -> int
          |extern "probe_rx_error" c_error(re: *u8, code: int, buf: *u8, n: u64)
          |
          |struct Match
          |    start: usize
          |    end: usize
          |
          |struct Regex
          |    handle: *u8
          |
          |    find(self, s: string) -> Option[Match]
          |        val subject = cstring(s)
          |
          |        var so: i64 = 0i64
          |        var eo: i64 = 0i64
          |
          |        if c_exec(self.handle, subject.ptr, &so, &eo) == 1 then
          |            Some(Match(usize(so), usize(eo)))
          |        else
          |            None
          |    end find
          |
          |    free(self) = c_free(self.handle)
          |end Regex
          |
          |compile(pattern: string) -> Result[Regex, string]
          |    val re   = c_new()
          |    val p    = cstring(pattern)
          |    val code = c_compile(re, p.ptr)
          |
          |    if code == 0 then
          |        Ok(Regex(re))
          |    else
          |        var buf: [256]u8
          |
          |        c_error(re, code, &buf[0], 256u64)
          |        c_free(re)
          |        Err(from_cstring(&buf[0]).unwrap_or("not a pattern"))
          |end compile
          |""".stripMargin

      val out = artifactOf(rootWithC("rx", binding, "regex.c" -> regexShim))

      val prog =
        """import rx.*
          |
          |show(pat: string, s: string) =
          |    compile(pat) match
          |        Ok(re) ->
          |            re.find(s) match
          |                Some(m) -> print(f"${m.start}..${m.end}")
          |                None -> print("none")
          |            re.free()
          |
          |        Err(why) -> print(f"error: ${why}")
          |
          |show("[0-9]+", "abc123def")
          |show("cat|dog", "hotdog stand")
          |show("a{2,3}", "aaaa")
          |show("^abc$", "xabcx")
          |show("^abc$", "abc")
          |show("[^aeiou]+", "aeixyzou")
          |show("ABC", "xxabcxx")
          |show("(ab)+", "zzababab!!")
          |show("x*", "yyy")
          |show("a{3,1}", "aaa")
          |""".stripMargin

      val lines = ran(Config(command = "run", file = program(prog), libs = List(out))).linesIterator.toList

      lines.take(9) shouldBe
        List("3..6", "3..6", "0..3", "none", "0..3", "3..6", "none", "2..8", "0..0")

      lines(9) should startWith("error: ")
      lines(9).length should be > "error: ".length
    }

    "and editing only the C changes what the artifact fingerprints as" in {
      // A library's shims are as much its source as its modules are. An artifact that did not change
      // when one of them was edited is a stale artifact nothing would notice was stale — which is
      // exactly the failure `Core.read`'s fingerprint check exists to catch for the sysl half.
      val before = fingerprintOf(artifactOf(rootWithC("demo", usingShim, "shim.c" -> shim)))
      val after =
        fingerprintOf(artifactOf(rootWithC("demo", usingShim,
          "shim.c" -> "int demo_seven(void) { return 8; }\n")))

      before should not be after
    }
  }

  "--lib pointed at an artifact" - {

    "compiles a program against it" in {
      val prog = program("print(demo.double(21))\nprint(demo.larger(3, 7))")

      cli(Config(command = "emit-llvm", file = prog, libs = List(artifact()))) shouldBe 0
    }

    "runs one, with the precompiled body linked from the artifact" in {
      assume(Toolchain.clangAvailable, "clang not available")

      cli(Config(command = "run", file = program("print(demo.double(21))"), libs = List(artifact()))) shouldBe 0
    }

    "takes a source root just as well, which is the other shape of the same flag" in {
      val prog = program("print(demo.double(21))")

      cli(Config(command = "emit-llvm", file = prog, libs = List(libraryRoot()))) shouldBe 0
    }

    "leaves no unpacked object behind" in {
      assume(Toolchain.clangAvailable, "clang not available")

      // The object half is written to a temporary file for the linker's sake. One per invocation
      // that is never removed is a leak a long-running build would feel and nothing would report.
      val probe   = createTempFile("sysl-probe-", "")
      val tempDir = probe.substring(0, math.max(probe.lastIndexOf('/'), probe.lastIndexOf('\\')))

      deleteFile(probe)

      val before = listFiles(tempDir).count(_.contains("sysl-lib-"))

      cli(Config(command = "run", file = program("print(demo.double(1))"), libs = List(artifact())))

      listFiles(tempDir).count(_.contains("sysl-lib-")) shouldBe before
    }
  }

  "several --lib at once" - {

    /* The flag is unbounded and the help text says so, which is a claim about behaviour and was the
     * one thing here nothing checked — every other test in this file passes exactly one. What makes
     * more than one worth its own section is that the driver *partitions* them, unions their symbol
     * sets, and concatenates their sources and their object files: four places where a second
     * library either arrives or silently does not. */

    "links two artifacts, both of which the program calls" in {
      val prog = program("print(demo.double(21))\nprint(extra.triple(2))")

      val ir = emitted(Config(command = "emit-llvm", file = prog,
        libs = List(artifact(), artifactOf(rootOf("extra", other)))))

      // Declared, not defined — which says both object halves were accounted for. Exiting 0 would
      // hold for a compilation that quietly compiled the second library in from source instead.
      symbols(ir, "declare") should contain allOf ("demo$double", "extra$triple")
      symbols(ir, "define") should contain noneOf ("demo$double", "extra$triple")
    }

    "and does so whichever order they are given in" in {
      val prog = program("print(demo.double(21))\nprint(extra.triple(2))")
      val two  = List(artifact(), artifactOf(rootOf("extra", other)))
      val ir   = emitted(Config(command = "emit-llvm", file = prog, libs = two.reverse))

      symbols(ir, "declare") should contain allOf ("demo$double", "extra$triple")
    }

    "and mixes an artifact with a source root, which is the other shape of the same flag" in {
      val prog = program("print(demo.double(21))\nprint(extra.triple(2))")

      val ir = emitted(Config(command = "emit-llvm", file = prog,
        libs = List(artifact(), rootOf("extra", other))))

      // The sharp one: the same compilation holds one library it links and one it compiles, so a
      // partition that dropped either half would show here and nowhere else.
      symbols(ir, "declare") should contain("demo$double")
      symbols(ir, "define") should contain("extra$triple")
      symbols(ir, "define") should not contain "demo$double"
    }

    "and runs, which is what says both object halves reached the linker" in {
      assume(Toolchain.clangAvailable, "clang not available")

      cli(Config(command = "run", file = program("print(demo.double(21) + extra.triple(2))"),
        libs = List(artifact(), artifactOf(rootOf("extra", other))))) shouldBe 0
    }
  }

  "--lib pointed at something that is not an artifact" - {

    "refuses a file that is not one of ours" in {
      cli(Config(command = "emit-llvm", file = program("print(1)"),
        libs = List(corrupt("not a library\n".getBytes)))) should not be 0
    }

    "refuses one built by a different compiler" in {
      cli(Config(command = "emit-llvm", file = program("print(1)"),
        libs = List(corrupt(s"syslib ${LibraryArtifact.Version + 1} 0\n".getBytes)))) should not be 0
    }

    "refuses a truncated one" in {
      cli(Config(command = "emit-llvm", file = program("print(1)"),
        libs = List(corrupt(truncated)))) should not be 0
    }

    "refuses one that is not there at all" in {
      cli(Config(command = "emit-llvm", file = program("print(1)"),
        libs = List(s"${createTempDirectory("sysl-cli-gone-")}/absent${LibraryArtifact.extension}")))
        .should(not be 0)
    }

    "refuses a source root holding no sysl files" in {
      cli(Config(command = "emit-llvm", file = program("print(1)"),
        libs = List(createTempDirectory("sysl-cli-empty-lib-")))) should not be 0
    }

    "reports a link that fails rather than falling over cleaning up after it" in {
      assume(Toolchain.clangAvailable, "clang not available")

      // A member the linker cannot read is the reachable way to fail a link, and what it caught was
      // the cleanup: `createTempFile` reserves a name and the toolchain is what writes to it, so
      // deleting the executable of a build that never produced one threw — and the stack trace stood
      // where the linker's own message should have been.
      //
      // The metadata is well-formed and only the compiled half is rubbish, which is what puts the
      // failure at the link rather than at the read: everything the compiler does succeeds.
      val junk = corrupt(FakeAr(LibraryArtifact.codeMember -> "not an object file".getBytes,
        LibraryArtifact.metadataMember -> LibraryArtifact.frame("0\n")))

      cli(Config(command = "run", file = program("print(1)"), libs = List(junk))) should not be 0
    }
  }

  "--core-lib" - {

    "runs a program whose share of the standard module came from the artifact" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")
      assume(Toolchain.clangAvailable, "clang not available")

      // Exiting 0 is the whole assertion, and it is a strong one. Every core symbol the artifact
      // defines is one this program only *declares* — so if the object half were not handed to the
      // linker, or held different symbols than its metadata says, this would not link at all.
      val (status, notes) = diagnostics(Config(command = "run", file = program("print(21 * 2)"),
        coreLib = Some(core)))

      status shouldBe 0
      notes should not include "warning"
    }

    "builds a library against one too, which is the other thing that gets compiled" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")
      assume(Toolchain.clangAvailable, "clang not available")

      val out = createTempFile("sysl-cli-against-core-", LibraryArtifact.extension)

      val (status, notes) =
        diagnostics(Config(command = "build-lib", file = libraryRoot(), output = Some(out), coreLib = Some(core)))

      status shouldBe 0
      notes should not include "warning"
    }

    "refuses the compilation rather than substituting another library" - {

      // The same rule `--lib` follows, and for the same reason: a library that cannot be read leaves
      // the calls into it with nothing to resolve them. That the compiler happens to carry a copy of
      // this one does not make quietly compiling against a *different* standard module than the one
      // asked for an acceptable answer — it makes it a harder mistake to notice.
      def refuses(what: String, path: String): Unit =
        what in {
          val (status, notes) = diagnostics(Config(command = "emit-llvm", file = program("print(1)"),
            coreLib = Some(path)))

          status should not be 0
          notes should include("error")
        }

      refuses("when it is not there at all",
        s"${createTempDirectory("sysl-cli-nocore-")}/absent${LibraryArtifact.extension}")

      refuses("when it is not one of ours", corrupt("not a library\n".getBytes))

      // A real archive of real objects that is simply somebody else's library — a `.a` from a C
      // project renamed. Every part of reading it works up to the part that looks for our metadata.
      refuses("when it is an archive with nothing of ours in it",
        corrupt(FakeAr("foreign.o" -> Array[Byte](7, 7))))

      refuses("when another sysl built it",
        corrupt(FakeAr(LibraryArtifact.metadataMember ->
          LibraryArtifact.framed(s"syslib ${LibraryArtifact.Version + 1} 0"))))

      refuses("when it is truncated", corrupt(truncated))

      refuses("when its metadata will not decode", corrupt(artifactOfMeta("0000000000000000\n0\nrubbish")))

      // The one a developer actually meets: build the artifact, then edit `lib/sysl`. It decodes and
      // would link perfectly — it is simply no longer the standard module in the tree — so nothing
      // but the fingerprint would catch it, and a silently wrong library is the worst of the five.
      refuses("when it was built from a different lib/sysl", corrupt(artifactOfMeta(stale)))
    }

    "is not needed by name, the artifact being looked for where build-lib --core puts it" - {

      // Every case here routes the default path through `coreSearch` to a temporary file rather than
      // using the real one. Suites run in parallel, and an artifact left at the true default would be
      // found by every other test in the run — which is the environment-dependence this feature
      // introduces, arriving first in our own suite. The real default is pinned separately, below.

      "so building it with no -o and compiling with no --core-lib is the whole workflow" in {
        assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")
        assume(Toolchain.clangAvailable, "clang not available")

        val where = s"${createTempDirectory("sysl-cli-found-")}/core${LibraryArtifact.extension}"

        cli(Config(command = "build-lib", file = CoreLib.root.get, core = true, coreSearch = where)) shouldBe 0
        isFile(where) shouldBe true

        val (status, notes) =
          diagnostics(Config(command = "run", file = program("print(21 * 2)"), coreSearch = where))

        status shouldBe 0
        notes should not include "warning"
      }

      "while nothing there is built rather than reported, a fresh clone having one answer" in {
        assume(Toolchain.clangAvailable, "clang not available")
        assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

        val where = s"${createTempDirectory("sysl-cli-none-")}/core${LibraryArtifact.extension}"

        val (status, notes) =
          diagnostics(Config(command = "emit-llvm", file = program("print(1)"), coreSearch = where))

        status shouldBe 0
        isFile(where) shouldBe true

        // Announced rather than done invisibly: a first build that pauses to do work should say what
        // the work was.
        notes should include("building the standard module")
      }

      "and something unreadable there is replaced, the artifact being derived and not authored" in {
        assume(Toolchain.clangAvailable, "clang not available")
        assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

        val where = corrupt("not a library\n".getBytes)

        diagnostics(Config(command = "emit-llvm", file = program("print(1)"), coreSearch = where))._1 shouldBe 0

        // Replaced, not merely worked around: what is at the path afterwards is a standard module
        // this compiler will read, which is the whole of what the rebuild is for.
        LibraryArtifact.metadataOf(where, readBytes(where))
          .flatMap(Core.read(where, _, Target.default)) shouldBe Symbol("right")
      }

      "and a stale one is replaced too, which is the state it is actually found in" in {
        assume(Toolchain.clangAvailable, "clang not available")
        assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

        // The one a developer meets after a merge: an artifact whose container is a format behind.
        // It is not corrupt and would decode as far as its own header — only the compiler has moved.
        val where = corrupt(s"syslib ${LibraryArtifact.Version + 1} 0\n".getBytes)

        diagnostics(Config(command = "emit-llvm", file = program("print(1)"), coreSearch = where))._1 shouldBe 0
      }

      "and the program is compiled against the rebuilt one, not against the carried copy" in {
        assume(Toolchain.clangAvailable, "clang not available")
        assume(Toolchain.findAr(None).isRight, "llvm-ar not available")

        val where = s"${createTempDirectory("sysl-cli-rebuilt-")}/core${LibraryArtifact.extension}"
        val src   = program("print(1)")

        // The discriminating half. A rebuild that produced an artifact and then went on compiling
        // against the copy the compiler carries would pass every assertion above, and the library's
        // symbols are what tell the two apart: linked, they are declarations.
        val rebuilt = emitted(Config(command = "emit-llvm", file = src, coreSearch = where))
        val carried = emitted(Config(command = "emit-llvm", file = src, noCoreLib = true, coreSearch = where))

        libraryOwn(rebuilt, "define") shouldBe empty
        libraryOwn(rebuilt, "declare") should not be empty
        libraryOwn(carried, "define") should not be empty
      }

      "but one named with --core-lib is not rebuilt, being the one that was asked for" in {
        // The rule the rebuild does *not* reach, and the reason it does not: someone who wrote down
        // which artifact to compile against is owed the truth about that one rather than a different
        // one built underneath them.
        val (status, notes) = diagnostics(Config(command = "emit-llvm", file = program("print(1)"),
          coreLib = Some(corrupt("not a library\n".getBytes))))

        status should not be 0
        notes should include("is not a sysl library")
        notes should not include "building the standard module"
      }

      "and --core-lib is the one consulted, being the one someone actually asked for" in {
        // Both are unreadable, so both refuse — what says which was read is *how* each is broken:
        // the named one is not ours at all, the one at the default path claims a later format.
        val (status, notes) = diagnostics(Config(command = "emit-llvm", file = program("print(1)"),
          coreLib = Some(corrupt("not a library\n".getBytes)),
          coreSearch = corrupt(s"syslib ${LibraryArtifact.Version + 1} 0\n".getBytes)))

        status should not be 0
        notes should include("is not a sysl library")
        notes should not include "built by a different sysl"
      }

      "and the place both ends agree on is the documented one" in {
        // The tests above route around the real default so they cannot collide; this is what says
        // the real default is what they were standing in for.
        Config().coreSearch shouldBe LibraryArtifact.coreDefault
        LibraryArtifact.coreDefault should endWith(LibraryArtifact.extension)
      }
    }
  }

  "--no-core-lib" - {

    /* The compiler keeps its own copy of the standard module, and discovery means that copy is
     * normally reached only by an artifact being absent — which is a fact about the filesystem, not
     * about the command line. These say the flag reaches it on purpose. */

    "does not read the artifact at the default path, even a broken one" in {
      // The discriminating pair: this exact artifact at this exact path *refuses* the compilation
      // without the flag (the discovery section above), so succeeding here is the artifact going
      // unread rather than a corruption that happens not to matter.
      val (status, notes) = diagnostics(Config(command = "emit-llvm", file = program("print(1)"),
        noCoreLib = true, coreSearch = corrupt("not a library\n".getBytes)))

      status shouldBe 0
      notes should not include "error"
    }

    "and compiles a tree with no artifact without making one, which the default path would" in {
      // The flag's reason for existing, and what separates it from the rebuild. Both compile in a
      // tree where nothing has been built; only one of them needs a toolchain to do it, which is why
      // this is the path the compiler's own unit tests take and the bootstrap took.
      val nowhere = s"${createTempDirectory("sysl-cli-bare-")}/core${LibraryArtifact.extension}"

      cli(Config(command = "emit-llvm", file = program("print(1)"), noCoreLib = true,
        coreSearch = nowhere)) shouldBe 0

      // Nothing was written, where the same run without the flag would have built one there.
      isFile(nowhere) shouldBe false
    }

    "and takes the built-in copy with a good artifact sitting right there" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      // Which module was used, not merely that one was: the same program at the same path, told to
      // use the artifact and told not to. Exiting 0 both ways would hold for a flag that did
      // nothing, so the assertion is on the seam the flag moves — what the artifact already holds
      // is declared when it is linked and defined when it is not.
      val src    = program("print(21 * 2)")
      val linked = emitted(Config(command = "emit-llvm", file = src, coreSearch = core))
      val carried = emitted(Config(command = "emit-llvm", file = src, noCoreLib = true, coreSearch = core))

      libraryOwn(linked, "define") shouldBe empty
      libraryOwn(linked, "declare") should not be empty
      libraryOwn(carried, "define") should not be empty
    }

    // `13 §8` gives the flag a second use beyond the bootstrap: *compiling one program both ways is
    // how the two paths are held to meaning the same thing.* The test above pins which module was
    // used, which is the seam; this one is the claim itself. What may differ is the standard module's
    // own symbols — declared when linked, defined when carried — so what must agree is the code the
    // **program** lowers to, which the way its library arrived has no business changing.
    "and one program compiled both ways lowers to the same program" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      val src = program("f(n: int) -> int = n * 2\nprint(f(21))\n")
      val linked  = emitted(Config(command = "emit-llvm", file = src, coreSearch = core))
      val carried = emitted(Config(command = "emit-llvm", file = src, noCoreLib = true, coreSearch = core))

      // The two modules do *not* hold the same symbols, and should not: the standard module's own
      // and the ARC runtime beside them are defined here only when the copy is carried, and come
      // from the artifact's object otherwise. That difference is the whole point of the flag. What
      // has to agree is the program's own code — the same source, lowered the same way.
      for name <- List("f", "main") do
        bodyOf(linked, name) should not be empty
        bodyOf(linked, name) shouldBe bodyOf(carried, name)
    }

    "and what it compiles is whole, not a program relying on the artifact anyway" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")
      assume(Toolchain.clangAvailable, "clang not available")

      // Linking is the assertion. The artifact's object half is not handed to the linker here, so
      // every core symbol this program calls has to have been emitted into it.
      val (status, notes) = diagnostics(Config(command = "run", file = program("print(21 * 2)"),
        noCoreLib = true, coreSearch = core))

      status shouldBe 0
      notes should not include "warning"
    }

    "but is refused beside --core-lib, which asks for the other one" in {
      // Two spellings a character apart, so a typo lands here. Refused rather than resolved by
      // precedence: either precedence discards half of what the command line asked for, silently.
      // The path names nothing, which says the refusal comes before the artifact is read.
      cli(Config(command = "emit-llvm", file = program("print(1)"), noCoreLib = true,
        coreLib = Some(s"${createTempDirectory("sysl-cli-both-")}/any${LibraryArtifact.extension}")))
        .should(not be 0)
    }
  }

  "build-lib --core" - {

    "builds the standard module, which nothing else may declare" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      val out = createTempFile("sysl-cli-core-", LibraryArtifact.extension)

      cli(Config(command = "build-lib", file = CoreLib.root.get, output = Some(out), core = true)) shouldBe 0

      LibraryArtifact.metadataOf(out, readBytes(out)).flatMap(LibraryArtifact.read(out, _, Target.default)) match
        case Right((trees, syms, fingerprint)) =>
          // Every symbol is one of the library's own modules'. A library defines its own
          // declarations and nobody else's, and the core library is the one place that rule is under
          // the most pressure, since the whole of the rest of the library is what it was compiled
          // against. `sysl.args` is in here as well as `sysl`, which is the point of building the
          // whole tree rather than the standard module alone.
          syms should not be empty
          syms.filterNot(s => Library.modules.contains(Modules.moduleOf(s))) shouldBe empty
          syms.map(Modules.moduleOf).size should be > 1
          trees.flatMap(_.module.map(_.show)).distinct.sorted shouldBe Library.modules

          // And it fingerprints as the library the compiler carries, though this one was walked off
          // disk and named by where it was found while the carried copy is named by where the
          // generator read it. If the fingerprint were over paths rather than contents, the guard in
          // `Core.read` would reject every artifact the documented command produces.
          fingerprint shouldBe Std.fingerprint
        case Left(err) => fail(err)

      deleteFile(out)
    }

    "refuses to build the standard module against a prebuilt copy of itself" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      // The one combination that cannot mean anything: the declarations being compiled are the ones
      // the artifact holds. Refused before the artifact is even read — ignoring it would leave a
      // command line reading as though it were used, which is the failure worth preventing.
      cli(Config(command = "build-lib", file = CoreLib.root.get, core = true,
        coreLib = Some(s"${createTempDirectory("sysl-cli-core-")}/any${LibraryArtifact.extension}")))
        .should(not be 0)
    }

    "and without it the same root is refused, because the module is the library's" in {
      // The refusal is the ordinary one every program gets, and keeping it is the point: inferring
      // the mode from the module names in the tree would turn a clear diagnostic into an artifact
      // that builds and then collides with the built-in copy at whatever link tried to use it.
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      val out = createTempFile("sysl-cli-core-", LibraryArtifact.extension)

      deleteFile(out)
      cli(Config(command = "build-lib", file = CoreLib.root.get, output = Some(out))) should not be 0
      isFile(out) shouldBe false
    }
  }
}
