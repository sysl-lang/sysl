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
   */
  private def cli(cfg: Config): Int = io.github.edadma.sysl.execute(cfg)

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
        libs = List(corrupt("syslib 1 900\nshort".getBytes)))) should not be 0
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

  "build-lib --core" - {

    "builds the standard module, which nothing else may declare" in {
      assume(CoreLib.root.isDefined, "lib/ not found from the test working directory")

      val out = createTempFile("sysl-cli-core-", LibraryArtifact.extension)

      cli(Config(command = "build-lib", file = CoreLib.root.get, output = Some(out), core = true)) shouldBe 0

      LibraryArtifact.unpack(out, readBytes(out)).flatMap(r => LibraryArtifact.read(out, r._1)) match
        case Right((trees, syms)) =>
          // Every symbol is the standard module's own. The renderers reach the library's
          // `sysl_snprintf` and the library's `putbytes` under them, and **none of those is in
          // here** — a library defines its own declarations and nobody else's, and the core library
          // is the one place that rule is under the most pressure, since the whole of the rest of
          // the library is what it was compiled against.
          syms should not be empty
          syms.filterNot(_.startsWith(s"${Std.module}${Modules.sep}")) shouldBe empty
          trees.flatMap(_.module.map(_.show)).distinct shouldBe List(Std.module)
        case Left(err) => fail(err)

      deleteFile(out)
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
