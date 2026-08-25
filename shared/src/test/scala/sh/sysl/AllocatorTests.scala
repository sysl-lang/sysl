package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The pair of C functions a program allocates through (`reference/packages.md § One heap, and the
 * package that names it`).
 *
 * A package that brings its own heap names the pair, that settles it for the whole program, and every
 * allocation the compilation emits calls it. What the choice is made *of* — one declaration, two that
 * agree, two that disagree, none at all — is `PackageConfigTests`; what is here is what the choice
 * then does: the symbols that reach the IR, and the refusal that keeps a library compiled against one
 * heap out of a program built on another.
 *
 * The IR is a string and the artifact metadata is a string, so all of this runs identically on the
 * three platforms.
 */
class AllocatorTests extends AnyFreeSpec with Matchers {

  private val freertos = Allocator("pvPortMalloc", "vPortFree")

  /** A program that allocates: a concatenation puts a string on the heap, which is the shortest thing
   * that reaches the allocator through the runtime helpers rather than through a program's own code.
   */
  private val allocates = "var s = \"a\" + \"b\"\nprint(s)\n"

  private def ir(src: String, allocator: Allocator): String =
    Compiler.compileToLlvm(src, "<input>", Target.default, allocator) match
      case Right(out) => out
      case Left(e)    => fail(e)

  /** The metadata half of an artifact built for a given pair — the object half is not needed to ask
   * what it was built for, which is the point of recording it as a name.
   */
  private def meta(allocator: Allocator): String =
    LibraryArtifact.build(List(Source("m.sysl", "module m\n\ndouble(n: int) -> int = n * 2\n")),
                          Target.default, allocator = allocator) match
      case Right((_, m)) => m
      case Left(e)       => fail(e)

  "the emitted module" - {
    "declares and calls libc's pair when nothing named another" in {
      val out = ir(allocates, Allocator.c)

      out should include("declare ptr @malloc(")
      out should include("declare void @free(ptr)")
      out should include("call ptr @malloc(")
    }

    "declares and calls the pair a package named" in {
      val out = ir(allocates, freertos)

      out should include("declare ptr @pvPortMalloc(")
      out should include("declare void @vPortFree(ptr)")
      out should include("call ptr @pvPortMalloc(")
      out should include("call void @vPortFree(")
    }

    // The whole point of the feature: a program told to allocate through one heap must not reach the
    // other anywhere. A single missed emission site is a mixed heap, and a mixed heap is not something
    // a link or a run reports — it is corruption at whatever later moment the wrong `free` is reached.
    "reaches libc's pair nowhere at all once another is named" in {
      val out = ir(allocates, freertos)

      out should not include "@malloc"
      out should not include "@free"
    }

    /** `Codegen` decides a module needs the plain drop hook by reading its own runtime helpers' text
     * for a call to the allocator. Left as a literal `@malloc` that sniff matches nothing once the
     * symbol changes, and the module loses a hook it needs — a leak with no diagnostic anywhere near
     * it, which is why the check is on the *symbol* and why this test exists.
     */
    "still installs the plain drop hook, which is found by reading for the allocator" in {
      ir(allocates, Allocator.c) should include("define private void @arc.drop.plain(")
      ir(allocates, freertos) should include("define private void @arc.drop.plain(")
    }

    // A module may not declare one symbol twice, and the allocator's declaration is emitted before any
    // `extern`'s. A binding that names `pvPortMalloc` as the allocator *and* declares it — which is
    // exactly what a FreeRTOS package does, since its sysl has to be able to call it — must not
    // produce two of them.
    "declares the allocator once when an extern names the same symbol" in {
      // Both are **called**, because an `extern` nothing reaches is pruned and never declared — which
      // would make the count below true of a module that had simply dropped them both.
      val src =
        """extern pvPortMalloc(n: usize) -> *u8
          |extern xPortGetFreeHeapSize() -> usize
          |
          |print(xPortGetFreeHeapSize())
          |val p = pvPortMalloc(8)
          |if p == null then print(1)
          |""".stripMargin + allocates

      val out = ir(src, freertos)

      // The control, and it is what makes the count below a fact about the collision rather than about
      // whether an `extern` reaches the module at all: one the allocator has no claim on is declared.
      out should include("@xPortGetFreeHeapSize(")

      out.linesIterator.count(_.startsWith("declare ptr @pvPortMalloc(")) shouldBe 1
    }
  }

  "a library artifact" - {
    "is read back by a program that allocates the same way" in {
      LibraryArtifact.read("lib.syslib", meta(freertos), Target.default, freertos).isRight shouldBe true
      LibraryArtifact.read("lib.syslib", meta(Allocator.c), Target.default, Allocator.c)
        .isRight shouldBe true
    }

    // Refused here because nothing downstream would refuse it. A mismatched *target* is caught by the
    // linker eventually, in a message about object formats; a mismatched allocator resolves cleanly,
    // links, and runs.
    "is refused by a program that allocates another way, naming both pairs" in {
      LibraryArtifact.read("lib.syslib", meta(Allocator.c), Target.default, freertos) match
        case Right(_) => fail("a libc artifact was accepted by a program allocating through FreeRTOS")
        case Left(e) =>
          e should include("lib.syslib allocates through malloc / free")
          e should include("this program allocates through pvPortMalloc / vPortFree")
          e should include("Rebuild the library")
    }

    // Both wrong is reported as the target, because that is the one the reader fixes first: an
    // artifact for another machine is not going to link whatever its allocator says.
    "reports the target when the target is wrong too" in {
      val other = Target.all.find(_ != Target.default).getOrElse(fail("one target only"))

      LibraryArtifact.read("lib.syslib", meta(Allocator.c), other, freertos) match
        case Right(_) => fail("an artifact for another target was accepted")
        case Left(e)  => e should include("was built for")
    }

    // An artifact from before the pair was recorded says nothing about which heap its object half
    // reaches, and a reader that assumed libc's would be assuming the very thing it has to check. The
    // container version is what refuses the whole file instead.
    "from a compiler that recorded no pair is refused by its version" in {
      LibraryArtifact.Version should be > 4
    }
  }

  "the standard module's cache path" - {
    // Without this the refusal above is not a diagnostic but a loop: the cross-allocator build refuses
    // the entry, rebuilds over it, and the next build the other way refuses that one and rebuilds it
    // back — which is exactly what leaving the target out of the key once did.
    "separates two allocators, so both stay warm" in {
      val libc = LibraryArtifact.stdDefault(Target.default, Allocator.c)
      val rtos = LibraryArtifact.stdDefault(Target.default, freertos)

      libc should not be rtos
    }

    "is the same path for the same pair" in {
      LibraryArtifact.stdDefault(Target.default, freertos) shouldBe
        LibraryArtifact.stdDefault(Target.default, freertos)
    }
  }
}
