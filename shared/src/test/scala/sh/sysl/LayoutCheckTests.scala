package sh.sysl

import io.github.edadma.cross_platform.*

/** The checked C struct layout, end to end — `@assert` on the sysl side and `_Static_assert` on the
 * C side, pinned to the same number.
 *
 * This is what `@assert` was built for, and it is the pair that matters rather than either half.
 * `15 §7` refuses transcribing a C struct into sysl on the grounds that nothing verifies the
 * result — *"sysl's own `sizeof` would report what sysl laid out, not what C did, so even that
 * comparison is a tautology."* It stops being a tautology when both sides name the same literal: the
 * `.c` pins what the header says, the `@assert` pins what sysl laid out, and a build survives only
 * while the two agree.
 *
 * A project rather than a string, because the C has to be a **file in the tree** for `15 §7` to
 * compile it — which is also the half that carries `--target=`, so the check reads the headers of
 * the machine being built for.
 */
class LayoutCheckTests extends LibraryCliSupport {

  /** What a run that must not compile said on the way out. Both halves fail the *build* rather than
   * the program, so the message is on stderr and there is no output to read.
   */
  private def refusedWith(cfg: Config): String = {
    val (status, notes) = diagnostics(cfg)

    withClue(notes)(status should not be 0)
    notes
  }

  private def projectOf(files: (String, String)*): String = {
    val root = createTempDirectory("sysl-layout-")

    for (path, body) <- files do
      Project.parentOf(s"$root/$path").foreach(createDirectories)
      writeFile(s"$root/$path", body)

    root
  }

  private val pairSysl =
    """struct Pair
      |    a: i32
      |    b: i32
      |end Pair
      |
      |@assert(sizeof(Pair) == 8, "Pair must match struct pair")
      |@assert(offsetof(Pair, b) == 4, "struct pair.b moved")
      |
      |extern "pair_size" c_pair_size() -> usize
      |
      |print(c_pair_size() == sizeof(Pair))
      |""".stripMargin

  private val pairC =
    """#include <stddef.h>
      |struct pair { int a; int b; };
      |_Static_assert(sizeof(struct pair) == 8, "struct pair size moved");
      |_Static_assert(offsetof(struct pair, b) == 4, "struct pair.b moved");
      |size_t pair_size(void) { return sizeof(struct pair); }
      |""".stripMargin

  /* A binding **is** a library, so a layout check has to survive `build-lib` — which means the
   * artifact carries the assertion rather than dropping it.
   *
   * Carried rather than dropped on purpose: `sizeof` is per target, so a layout a library verified
   * when it was built is not a layout verified for whatever the consumer is building for. The
   * consumer re-settles it.
   */
  "an assertion survives a library artifact" - {
    "a true one, built and consumed" in {
      val root = rootOf("shape",
        """module shape
          |
          |struct Pair
          |    a: i32
          |    b: i32
          |end Pair
          |
          |@assert(sizeof(Pair) == 8, "Pair must match struct pair")
          |
          |width() -> usize = sizeof(Pair)
          |""".stripMargin)

      ran(Config(command = "run", file = program("print(shape.width())\n"),
                 libs = List(artifactOf(root)))) shouldBe "8\n"
    }
  }

  "both halves agree" in {
    assume(Toolchain.clangAvailable, "clang not available")

    val root = projectOf("main.sysl" -> pairSysl, "pair.c" -> pairC)

    ran(Config(command = "run", file = root)) shouldBe "true\n"
  }

  // The sysl half catching a drift: the struct gained a field and the assertion did not move, so the
  // build stops here rather than at whatever reads past the end of C's storage.
  "the sysl half stops a struct that no longer matches" in {
    assume(Toolchain.clangAvailable, "clang not available")

    val root = projectOf(
      "main.sysl" ->
        """struct Pair
          |    a: i32
          |    b: i32
          |    c: i32
          |end Pair
          |
          |@assert(sizeof(Pair) == 8, "Pair must match struct pair")
          |
          |print(1)
          |""".stripMargin,
      "pair.c" -> pairC,
    )

    refusedWith(Config(command = "run", file = root)) should include("Pair must match struct pair")
  }

  // The failure `sizeof` structurally cannot see. The mirror has the same two fields in the other
  // order, so it is the same eight bytes with the same alignment and every size assertion on both
  // sides passes — while every read of `a` returns `b`. `offsetof` is the only thing in the pair that
  // objects, which is the whole argument for it existing.
  "the sysl half stops two same-width fields transposed, which sizeof cannot see" in {
    assume(Toolchain.clangAvailable, "clang not available")

    val root = projectOf(
      "main.sysl" ->
        """struct Pair
          |    b: i32
          |    a: i32
          |end Pair
          |
          |@assert(sizeof(Pair) == 8, "Pair must match struct pair")
          |@assert(offsetof(Pair, b) == 4, "struct pair.b moved")
          |
          |print(1)
          |""".stripMargin,
      "pair.c" -> pairC,
    )

    val notes = refusedWith(Config(command = "run", file = root))

    notes should include("struct pair.b moved")
    notes should not include "Pair must match struct pair"
  }

  // And the C half catching the other direction: the header moved, so `_Static_assert` fails while
  // the sysl is still internally consistent. The two are not redundant -- neither finds this alone.
  "the C half stops a header that no longer matches" in {
    assume(Toolchain.clangAvailable, "clang not available")

    val root = projectOf(
      "main.sysl" -> pairSysl,
      "pair.c" ->
        """#include <stddef.h>
          |struct pair { int a; int b; int c; };
          |_Static_assert(sizeof(struct pair) == 8, "struct pair size moved");
          |size_t pair_size(void) { return sizeof(struct pair); }
          |""".stripMargin,
    )

    refusedWith(Config(command = "run", file = root)) should include("struct pair size moved")
  }
}
