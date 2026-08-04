package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** `sysl build-lib` — producing an artifact, driven through the driver itself.
 *
 * The compiler's own API cannot reach any of this. What a built container holds, what a library
 * carrying C source does to the object half, whether a temporary object survives the run — all of it
 * lives in `execute`, and a test that called `Compiler` and `Toolchain` directly would be
 * re-implementing the driver and pinning its own arrangement rather than the one a user meets.
 *
 * Which artifact a *compilation* is then given is the other half, and it is `LibraryCliTests`.
 */
class LibraryBuildCliTests extends LibraryCliSupport {

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

      succeeds(Config(command = "build-lib", file = root))

      val expected = Project.basename(root) + LibraryArtifact.extension

      isFile(expected) shouldBe true
      deleteFile(expected)
    }

    "refuses a root holding no source rather than writing an empty artifact" in {
      refused(Config(command = "build-lib", file = createTempDirectory("sysl-cli-empty-")))
    }

    "takes the archiver it is told to use" in {
      // Worth pinning both ways round. That a named archiver is *used* is what the failing case below
      // cannot show on its own — an option that was read and then ignored would refuse a bad path
      // exactly as loudly while quietly building every library with something else.
      val ar = Toolchain.findAr(None) match
        case Right(path) => path
        case Left(why)   => cancel(why)

      val out = createTempFile("sysl-cli-named-ar-", LibraryArtifact.extension)

      succeeds(Config(command = "build-lib", file = libraryRoot(), output = Some(out), ar = Some(ar)))

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

      refused(Config(command = "build-lib", file = root, output = Some(out)))
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

    "is found at the root of the tree as well as beside a module" in {
      // `15 §7` says *anywhere* in the tree, and the root is the one place that is not a module's
      // directory — nothing there declares a module, so a walk that gathered C only where it had
      // found sysl would skip it. The member takes the bare name, there being no directory to carry.
      assume(Toolchain.clangAvailable, "clang not available")

      val root = rootWithC("demo", """module demo
                                     |
                                     |extern "demo_root" c_root() -> int
                                     |
                                     |from_root() -> int = c_root()
                                     |""".stripMargin)

      writeFile(s"$root/shared.c", "int demo_root(void) { return 13; }\n")

      val out = artifactOf(root)

      Ar.members(readBytes(out)) match
        case Right(members) => members.map(_.name) should contain("shared.o")
        case Left(why)      => fail(why)

      ran(Config(command = "run", file = program("print(demo.from_root())"), libs = List(out))) shouldBe "13\n"
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

      succeeds(Config(command = "build-lib", file = root, output = Some(out)))

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
      // exactly the failure `Stdlib.read`'s fingerprint check exists to catch for the sysl half.
      val before = fingerprintOf(artifactOf(rootWithC("demo", usingShim, "shim.c" -> shim)))
      val after =
        fingerprintOf(artifactOf(rootWithC("demo", usingShim,
          "shim.c" -> "int demo_seven(void) { return 8; }\n")))

      before should not be after
    }
  }
  "build-lib --std" - {

    "builds the standard module, which nothing else may declare" in {
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")

      val out = createTempFile("sysl-cli-std-", LibraryArtifact.extension)

      succeeds(Config(command = "build-lib", file = StdRoot.root.get, output = Some(out), std = true))

      LibraryArtifact.metadataOf(out, readBytes(out)).flatMap(LibraryArtifact.read(out, _, Target.default)) match
        case Right((trees, syms, fingerprint)) =>
          // Every symbol is one of the library's own modules'. A library defines its own
          // declarations and nobody else's, and the standard module library is the one place that rule is under
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
          // `Stdlib.read` would reject every artifact the documented command produces.
          fingerprint shouldBe Std.fingerprint
        case Left(err) => fail(err)

      deleteFile(out)
    }

    "refuses to build the standard module against a prebuilt copy of itself" in {
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")

      // The one combination that cannot mean anything: the declarations being compiled are the ones
      // the artifact holds. Refused before the artifact is even read — ignoring it would leave a
      // command line reading as though it were used, which is the failure worth preventing.
      refused(Config(command = "build-lib", file = StdRoot.root.get, std = true,
        stdLib = Some(s"${createTempDirectory("sysl-cli-std-")}/any${LibraryArtifact.extension}")))
    }

    "and without it the same root is refused, because the module is the library's" in {
      // The refusal is the ordinary one every program gets, and keeping it is the point: inferring
      // the mode from the module names in the tree would turn a clear diagnostic into an artifact
      // that builds and then collides with the built-in copy at whatever link tried to use it.
      assume(StdRoot.root.isDefined, "lib/ not found from the test working directory")

      val out = createTempFile("sysl-cli-std-", LibraryArtifact.extension)

      deleteFile(out)
      refused(Config(command = "build-lib", file = StdRoot.root.get, output = Some(out)))
      isFile(out) shouldBe false
    }
  }
}
