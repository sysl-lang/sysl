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
}
