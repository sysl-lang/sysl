package sh.sysl

import io.github.edadma.cross_platform.*

/** `sysl build-c` and `sysl emit-header` — the archive and the header a C project is handed
 * (`15 §12`).
 *
 * **The last test is the one that matters and the rest are its scaffolding.** Everything else in
 * this feature can be asserted by reading text sysl produced, which proves only that sysl agrees
 * with itself. What nothing else can answer is whether a **C compiler** accepts the header, whether
 * its linker resolves the symbol out of the archive, and whether the value that comes back is the
 * one the sysl function computed — so that test writes a C `main`, builds it with clang against both
 * artifacts, runs it, and reads the answer.
 *
 * It needs a clang that can link for this machine, which is the same thing every run-tier suite here
 * needs, so it is not a new dependency.
 */
class ExportCliTests extends LibraryCliSupport {

  /** A module whose whole purpose is to be called from outside, which is the shape a boundary layer
   * takes: free functions, scalars and pointers, no module storage that has to be computed.
   */
  private val boundary =
    """module mylib
      |
      |@export("mylib_add")
      |add(a: i32, b: i32) -> i32 = a + b
      |
      |@export("mylib_scale")
      |scale(a: i32, by: i32) -> i32 = a * by
      |
      |helper(n: i32) -> i32 = n + 1
      |""".stripMargin

  /** A module that reaches the standard library, which the one above never does.
   *
   * **Everything in `boundary` lowers to instructions**, so its archive stands alone whatever the
   * compilation did with the standard module — which is why a suite built entirely on it could be
   * green while every archive sysl wrote was unlinkable. One `print` is the difference: the object
   * then refers to `sysl$prints`, and whether that symbol is *in* the archive is the only question a
   * C project ever asks of this command.
   */
  private val talkative =
    """module mylib
      |
      |@export("mylib_greet")
      |greet(n: i32) =
      |    print("hello", n)
      |""".stripMargin

  private def built(text: String = boundary): (String, String) = {
    val root = rootOf("mylib", text)
    val out  = s"$root/libmylib.a"

    succeeds(Config(command = "build-c", file = root, output = Some(out)))
    (out, s"$out.h")
  }

  "build-c writes an archive and a header beside it" in {
    val (archive, header) = built()

    exists(archive) shouldBe true
    exists(header) shouldBe true
  }

  "the header declares each export under the symbol it named" in {
    val (_, header) = built()
    val text        = readFile(header)

    text should include("int32_t mylib_add(int32_t a, int32_t b);")
    text should include("int32_t mylib_scale(int32_t a, int32_t by);")
  }

  "and does not declare what was not exported" in {
    readFile(built()._2) should not include "helper"
  }

  "--header puts it somewhere else" in {
    val root = rootOf("mylib", boundary)
    val out  = s"$root/libmylib.a"
    val hdr  = s"$root/include/mylib.h"

    succeeds(Config(command = "build-c", file = root, output = Some(out), header = Some(hdr)))
    exists(hdr) shouldBe true
  }

  "emit-header prints the same declarations without building anything" in {
    val root = rootOf("mylib", boundary)
    val out  = new java.io.ByteArrayOutputStream

    val status = Console.withOut(out)(sh.sysl.execute(Config(command = "emit-header", file = root,
      noStdLib = true)))

    status shouldBe 0
    out.toString should include("int32_t mylib_add(int32_t a, int32_t b);")
  }

  // A module with no entry point still has to compile, and this is the case that would have been
  // pruned to nothing if an export were not a reachability root.
  "a module with no statements and no 'main' builds, because the exports are the roots" in {
    val (archive, _) = built()

    exists(archive) shouldBe true
  }

  "a C program links against the archive and gets the answers sysl computed" in {
    val (archive, header) = built()
    val dir               = createTempDirectory("sysl-c-caller-")
    val source            = s"$dir/main.c"
    val exe               = s"$dir/caller"

    writeFile(source,
      s"""#include <stdio.h>
         |#include "$header"
         |
         |int main(void) {
         |    printf("%d %d\\n", mylib_add(2, 3), mylib_scale(4, 5));
         |    return 0;
         |}
         |""".stripMargin)

    val build = exec(Seq("clang", source, archive, "-o", exe))

    withClue(build.stderr)(build.exitCode shouldBe 0)

    val run = exec(Seq(exe))

    withClue(run.stderr)(run.exitCode shouldBe 0)
    run.stdout.trim shouldBe "5 20"
  }

  "a C program links an export that uses the standard library, which is the case a '.syslib' cannot serve" in {
    val (archive, header) = built(talkative)
    val dir               = createTempDirectory("sysl-c-printing-")
    val source            = s"$dir/main.c"
    val exe               = s"$dir/caller"

    writeFile(source,
      s"""#include "$header"
         |
         |int main(void) {
         |    mylib_greet(7);
         |    return 0;
         |}
         |""".stripMargin)

    val build = exec(Seq("clang", source, archive, "-o", exe))

    withClue(build.stderr)(build.exitCode shouldBe 0)

    val run = exec(Seq(exe))

    withClue(run.stderr)(run.exitCode shouldBe 0)
    run.stdout.trim shouldBe "hello 7"
  }

  /** Seven is a number only the C knows and the multiplication is only the sysl's, so an archive that
   * dropped either object cannot answer 42 — and the one it drops is the one this section is about.
   */
  private val shim = "int demo_seven(void) { return 7; }\n"

  private val callingC =
    """module demo
      |
      |extern "demo_seven" c_seven() -> i32
      |
      |seven_times(n: i32) -> i32 = c_seven() * n
      |""".stripMargin

  private val exporting =
    """module mylib
      |
      |@export("mylib_answer")
      |answer() -> i32 = demo.seven_times(6)
      |""".stripMargin

  /** `15 §7`'s table gives a source root named with `--lib` the same answer as the project's own
   * tree: its C is compiled and reaches the link. For this command the link line is the archive, so
   * "reaches the link" means "is a member".
   *
   * **The failure this pins was silent in both directions.** `build-c` walked the project's tree
   * alone, so a package carrying a shim built cleanly, wrote an archive, said nothing, and handed a
   * C project a wall of undefined references from a linker that had never heard of the flag the
   * omission came from. Nothing between the two ends could see it: the sysl compiled, the archive
   * existed, and `ar t` was the only place the absence was visible.
   */
  "the C of a '--lib' source tree" - {

    "is a member of the archive, exactly as the project's own is" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val lib  = rootWithC("demo", callingC, "shim.c" -> shim)
      val root = rootOf("mylib", exporting)
      val out  = s"$root/libmylib.a"

      succeeds(Config(command = "build-c", file = root, output = Some(out), libs = List(lib)))

      Ar.members(readBytes(out)) match
        case Right(members) => members.map(_.name) should contain("demo.shim.o")
        case Left(err)      => fail(err)
    }

    "and a C program links against it and gets the answer the shim computed" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val lib    = rootWithC("demo", callingC, "shim.c" -> shim)
      val root   = rootOf("mylib", exporting)
      val out    = s"$root/libmylib.a"
      val header = s"$out.h"

      succeeds(Config(command = "build-c", file = root, output = Some(out), libs = List(lib)))

      val dir    = createTempDirectory("sysl-c-shim-")
      val source = s"$dir/main.c"
      val exe    = s"$dir/caller"

      writeFile(source,
        s"""#include <stdio.h>
           |#include "$header"
           |
           |int main(void) {
           |    printf("%d\\n", mylib_answer());
           |    return 0;
           |}
           |""".stripMargin)

      val build = exec(Seq("clang", source, out, "-o", exe))

      withClue(build.stderr)(build.exitCode shouldBe 0)

      val run = exec(Seq(exe))

      withClue(run.stderr)(run.exitCode shouldBe 0)
      run.stdout.trim shouldBe "42"
    }

    // The row of the table that already worked, kept beside the one that did not so that a change
    // moving the collection cannot fix one end while quietly dropping the other.
    "beside the project's own, which lands under a name of its own" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val lib  = rootWithC("demo", callingC, "shim.c" -> shim)
      val root = rootWithC("mylib", exporting, "extra.c" -> "int mylib_unused(void) { return 0; }\n")
      val out  = s"$root/libmylib.a"

      succeeds(Config(command = "build-c", file = root, output = Some(out), libs = List(lib)))

      Ar.members(readBytes(out)) match
        case Right(members) => members.map(_.name) should contain allOf ("demo.shim.o", "mylib.extra.o")
        case Left(err)      => fail(err)
    }
  }

  // The flag has a reading everywhere else and none here, so it is refused rather than discarded:
  // taking it silently would produce the unlinkable archive this command exists not to write.
  "naming a prebuilt standard module is refused, since a C link line cannot carry one" in {
    val root = rootOf("mylib", boundary)

    val (status, notes) = diagnostics(Config(command = "build-c", file = root,
      output = Some(s"$root/libmylib.a"), stdLib = Some(s"$root/std.syslib")))

    status should not be 0
    notes should include("--std-lib has nothing to name here")
  }
}
