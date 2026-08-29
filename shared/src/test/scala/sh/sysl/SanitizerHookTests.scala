package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The seam that lets a developer put a sanitizer under a whole sysl build — `SYSL_EXTRA_CFLAGS`,
  * spliced into every clang the build drives, and the IR marking that makes `-fsanitize=address`
  * mean something.
  *
  * **The marking is the half that is easy to get silently wrong**, and it is why this suite exists:
  * LLVM instruments only functions carrying `sanitize_address`, which a frontend adds — so IR handed
  * over as a `.ll` links the sanitizer's runtime, reports nothing, and looks exactly like a clean
  * program. That happened here: the first ASan run of the walk that provoked the double release in
  * `ControlFlowEmitter.genIterate` came back green, and the bug was live.
  *
  * The environment is not set while the suite runs, so what is asserted is the pass-through case and
  * the transformation itself; a flag actually reaching clang is what the build does with the list.
  */
class SanitizerHookTests extends AnyFreeSpec with Matchers {

  private val ir =
    """define private void @arc.retain(ptr %p) {
      |entry:
      |  ret void
      |}
      |define i32 @main() {
      |entry:
      |  ret i32 0
      |}
      |""".stripMargin

  "with nothing asked for, the IR goes through untouched" in {
    Toolchain.sanitized(ir, on = false) shouldBe ir
  }

  "every function is marked, and the group is declared once" in {
    val marked = Toolchain.sanitized(ir, on = true)

    marked should include("define private void @arc.retain(ptr %p) #99 {")
    marked should include("define i32 @main() #99 {")
    "attributes #99".r.findAllIn(marked).length shouldBe 1
  }

  // A line that merely opens a block is not a definition, and marking one would not parse — which
  // is what the two conditions on the line are for, rather than matching `{` alone.
  "and nothing that is not a definition is touched" in {
    val marked = Toolchain.sanitized(ir, on = true)

    marked should include("entry:")
    marked should not include "entry: #99"
    marked should not include "}#99"
  }
}
