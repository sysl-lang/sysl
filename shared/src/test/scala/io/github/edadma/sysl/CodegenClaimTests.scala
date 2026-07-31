package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Claims `codegen.md` makes about the shape of the emitted module that nothing else measures.
 *
 * Most of that document restates the language and is pinned by the chapter's own tests. What is its
 * own are the two things it says about the *text*: the header a counted box carries, and the
 * over-approximations it lists as shortcuts. A shortcut is a claim like any other — it says the
 * compiler is still wrong in a named direction — so it needs a test that fails when the shortcut is
 * finally taken away, rather than leaving the document to drift into describing a compiler that no
 * longer exists.
 */
class CodegenClaimTests extends AnyFreeSpec with CodegenSupport {

  /** `codegen.md § Deliberate shortcuts 7`: making a slice turns the ARC runtime on even where the
   * owner is statically null, so the smallest program there is carries an allocator it never
   * reaches; and a non-generic type is instantiated eagerly wherever it is declared, so
   * `FormatSpec`'s layout is emitted whether or not anything renders.
   */
  private val printOnly = ir("print(1)")

  private val boxed = ir("struct P\n    x: int\nvar r: &P = P(1)\nprint(r.x)")

  "a counted box carries a three-word header" - {

    // The document described two words for a long time, which was true before `weak` existed. The
    // third is the weak count (`03`), and it is what makes the storage outlive the object.
    "named on its own, since the runtime walks it without knowing the payload" in {
      boxed should include("%arc.header = type { i64, ptr, i64 }")
      boxed should include("%arc.P = type { i64, ptr, i64, %struct.P }")
    }

    // Reading the payload therefore walks past three, not two — an off-by-one here would read the
    // weak count as the first field.
    "so reading the payload starts after the third word" in {
      boxed should include("getelementptr %arc.P, ptr")
    }
  }

  "the over-approximations codegen.md lists as shortcuts are still there" - {

    "a program that prints one integer still declares an allocator" in {
      printOnly should include("declare ptr @malloc(i64)")
      printOnly should include("declare void @free(ptr)")
    }

    // And the claim is that it is *unreached*, not merely present — if `main` ever called it the
    // shortcut would be something else entirely.
    "and never calls it" in {
      mainOf(printOnly) should not include "@malloc"
    }

    // Both names are read off `Library` rather than written out. `FormatSpec` and `display_pad` are
    // in the standard module, so the emitted symbols carry its prefix — and the negative is the
    // fragile one: `display_pad` spelled bare is still a substring of `sysl$display_pad`, so it
    // would keep passing while no longer testing what it names.
    "FormatSpec's layout is emitted although nothing renders through one" in {
      printOnly should include(s"%struct.${Library.key("FormatSpec")} = type { i32, i32, i1 }")
      printOnly should not include s"@${Library.key("display_pad")}("
    }

    // The counterweight, so this pair cannot pass by the module simply containing everything: the
    // things that *are* emitted on demand are still absent from the same program.
    "while what is emitted on demand is absent from that same module" in {
      printOnly should not include "@arc.retain_sync"
      printOnly should not include "@sysl.str.cmp"
    }
  }
}
