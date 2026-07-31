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

  /** A library tree on disk, as the driver reads one: a root, with the module in a directory under
   * it whose name is the module's.
   */
  private def libraryRoot(): String = {
    val root = createTempDirectory("sysl-cli-lib-")
    val dir  = s"$root/demo"

    createDirectory(dir)
    writeFile(s"$dir/lib.sysl", library)
    root
  }

  private def program(text: String): String = {
    val path = createTempFile("sysl-cli-prog-", ".sysl")
    writeFile(path, text)
    path
  }

  /** A built artifact, and the paths that made it. */
  private def artifact(): String = {
    val out = createTempFile("sysl-cli-", LibraryArtifact.extension)

    cli(Config(command = "build-lib", file = libraryRoot(), output = Some(out))) shouldBe 0
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

  /** The standard module's own symbols an emitted module `define`s or `declare`s, which is how a
   * test tells a library that was linked from one that was compiled in.
   */
  private def libraryOwn(ir: String, form: String): Set[String] =
    ir.linesIterator.filter(_.startsWith(s"$form ")).flatMap { line =>
      val at = line.indexOf('@')

      Option.when(at >= 0)(line.drop(at + 1).takeWhile(c => c != '(' && c != ' '))
    }.filter(_.startsWith(Library.key(""))).toSet

  /** An artifact whose header promises more than the file holds. The version comes from the constant
   * rather than being written out, so that a bump to the container format leaves these testing
   * truncation rather than the version check that would otherwise fire ahead of it.
   */
  private def truncated: Array[Byte] = s"syslib ${LibraryArtifact.Version} 900\nshort".getBytes

  /** The real core's metadata wearing somebody else's fingerprint — a readable, decodable artifact
   * that is simply not the standard module this compiler carries.
   */
  private def stale: String = {
    val meta = LibraryArtifact.unpack(core, readBytes(core)) match
      case Right((m, _)) => m
      case Left(err)     => fail(err)

    "0000000000000000" + meta.drop(meta.indexOf('\n'))
  }

  private def corrupt(bytes: Array[Byte]): String = {
    val path = createTempFile("sysl-cli-bad-", LibraryArtifact.extension)
    writeBytes(path, bytes)
    path
  }

  "build-lib" - {

    "writes an artifact that carries both halves" in {
      val out = artifact()

      LibraryArtifact.unpack(out, readBytes(out)) match
        case Right((meta, obj)) =>
          meta should include("demo$double")
          obj.length should be > 0
        case Left(err) => fail(err)
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

    "refuses a library that does not check, and writes nothing" in {
      val root = createTempDirectory("sysl-cli-bad-lib-")

      createDirectory(s"$root/demo")
      writeFile(s"$root/demo/lib.sysl", "module demo\n\nf() -> int = \"no\"\n")

      val out = s"$root/out${LibraryArtifact.extension}"

      cli(Config(command = "build-lib", file = root, output = Some(out))) should not be 0
      isFile(out) shouldBe false
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

      // An object half the linker cannot read is the reachable way to fail a link, and what it
      // caught was the cleanup: `createTempFile` reserves a name and the toolchain is what writes
      // to it, so deleting the executable of a build that never produced one threw — and the stack
      // trace stood where the linker's own message should have been.
      val junk = corrupt(LibraryArtifact.pack("0\n", "not an object file".getBytes))

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

      refuses("when another sysl built it", corrupt(s"syslib ${LibraryArtifact.Version + 1} 0\n".getBytes))

      refuses("when it is truncated", corrupt(truncated))

      refuses("when its metadata will not decode",
        corrupt(LibraryArtifact.pack("0000000000000000\n0\nrubbish", Array.empty)))

      // The one a developer actually meets: build the artifact, then edit `lib/sysl`. It decodes and
      // would link perfectly — it is simply no longer the standard module in the tree — so nothing
      // but the fingerprint would catch it, and a silently wrong library is the worst of the five.
      refuses("when it was built from a different lib/sysl", corrupt(LibraryArtifact.pack(stale, Array.empty)))
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

      "while nothing there stops the compilation, and names the command that fixes it" in {
        // A fresh clone has to build the library once. Saying so is the whole difference between a
        // one-line fix and a user wondering why their program is slower than the one in the docs —
        // and, more to the point, the compiler does not get to pick a different standard module.
        val (status, notes) = diagnostics(Config(command = "emit-llvm", file = program("print(1)"),
          coreSearch = s"${createTempDirectory("sysl-cli-none-")}/core${LibraryArtifact.extension}"))

        status should not be 0
        notes should include("build-lib lib --core")
        notes should include("--no-core-lib")
      }

      "and something unreadable there stops it too, that being the shape a drifted one takes" in {
        val (status, notes) = diagnostics(Config(command = "emit-llvm", file = program("print(1)"),
          coreSearch = corrupt("not a library\n".getBytes)))

        status should not be 0
        notes should include("error")
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

    "and is what a tree with no artifact at all needs, there being no silent fallback" in {
      // The flag's reason for existing. Without it this exact configuration — a fresh clone, nothing
      // built — is an error, which is the whole point: the carried copy is reached deliberately or
      // not at all.
      val nowhere = s"${createTempDirectory("sysl-cli-bare-")}/core${LibraryArtifact.extension}"

      cli(Config(command = "emit-llvm", file = program("print(1)"), coreSearch = nowhere)) should not be 0
      cli(Config(command = "emit-llvm", file = program("print(1)"), noCoreLib = true,
        coreSearch = nowhere)) shouldBe 0
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

      LibraryArtifact.unpack(out, readBytes(out)).flatMap(r => LibraryArtifact.read(out, r._1)) match
        case Right((trees, syms, fingerprint)) =>
          // Every symbol is the standard module's own. The renderers reach the library's
          // `sysl_snprintf` and the library's `putbytes` under them, and **none of those is in
          // here** — a library defines its own declarations and nobody else's, and the core library
          // is the one place that rule is under the most pressure, since the whole of the rest of
          // the library is what it was compiled against.
          syms should not be empty
          syms.filterNot(_.startsWith(s"${Std.module}${Modules.sep}")) shouldBe empty
          trees.flatMap(_.module.map(_.show)).distinct shouldBe List(Std.module)

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
