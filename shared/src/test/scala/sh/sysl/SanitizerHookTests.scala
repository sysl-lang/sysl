package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The seam that lets a developer put a sanitizer under a whole sysl build — `SYSL_EXTRA_CFLAGS`,
  * spliced into every clang the build drives, and the IR marking that makes `-fsanitize=` mean
  * something.
  *
  * **The marking is the half that is easy to get silently wrong**, and it is why this suite exists:
  * LLVM instruments only functions carrying the sanitizer's attribute, which a frontend adds — so
  * IR handed over as a `.ll` links the sanitizer's runtime, reports nothing, and looks exactly like
  * a clean program. That has now happened twice. The first ASan run of the walk that provoked the
  * double release in `ControlFlowEmitter.genIterate` came back green with the bug live; and after
  * ASan was fixed, a ThreadSanitizer run over a program driving a thread pool was green for the
  * same reason one sanitizer along.
  *
  * The environment is not set while the suite runs, so the flag list is passed in rather than read.
  * A flag actually reaching clang is what the build does with the list.
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

  "reading the flags" - {

    "a sanitizer sysl marks for becomes its attribute" in {
      Toolchain.sanitizerAttrs(List("-fsanitize=address")) shouldBe List("sanitize_address")
      Toolchain.sanitizerAttrs(List("-fsanitize=thread")) shouldBe List("sanitize_thread")
      Toolchain.sanitizerAttrs(List("-fsanitize=memory")) shouldBe List("sanitize_memory")
      Toolchain.sanitizerAttrs(List("-fsanitize=hwaddress")) shouldBe List("sanitize_hwaddress")
    }

    // The ordinary way to ask for two, and a whole-flag match reads it as neither.
    "one flag may name several" in {
      Toolchain.sanitizersAsked(List("-fsanitize=address,undefined")) shouldBe
        List("address", "undefined")

      Toolchain.sanitizerAttrs(List("-fsanitize=address,undefined")) shouldBe
        List("sanitize_address")
    }

    // UBSan is emitted as checks during codegen rather than switched on by an attribute, so there
    // is nothing to mark and a run over a sysl build covers only the C a package vendors. Pinned
    // rather than left implicit, because the failure is a green run that never looked.
    "undefined asks for nothing, because LLVM has no attribute for it" in {
      Toolchain.sanitizerAttrs(List("-fsanitize=undefined")) shouldBe Nil
      Toolchain.sanitized(ir, Toolchain.sanitizerAttrs(List("-fsanitize=undefined"))) shouldBe ir
    }

    // A substring test reads a suppression as a request, and marks the IR for a sanitizer the
    // build has just turned off.
    "a suppression is not a request" in {
      Toolchain.sanitizersAsked(List("-fno-sanitize=address")) shouldBe Nil
      Toolchain.sanitizerAttrs(List("-fsanitize=address", "-fno-sanitize=thread")) shouldBe
        List("sanitize_address")
    }

    "flags that are not sanitizers say nothing" in {
      Toolchain.sanitizerAttrs(List("-g", "-O2", "-Wall")) shouldBe Nil
      Toolchain.sanitizerAttrs(Nil) shouldBe Nil
    }
  }

  "marking the IR" - {

    "with nothing asked for, the IR goes through untouched" in {
      Toolchain.sanitized(ir, Nil) shouldBe ir
    }

    "every function is marked, and the group is declared once" in {
      val marked = Toolchain.sanitized(ir, List("sanitize_address"))

      marked should include("define private void @arc.retain(ptr %p) #99 {")
      marked should include("define i32 @main() #99 {")
      "attributes #99".r.findAllIn(marked).length shouldBe 1
      marked should include("attributes #99 = { sanitize_address }")
    }

    "ThreadSanitizer marks the same functions, which is what it was missing" in {
      val marked = Toolchain.sanitized(ir, List("sanitize_thread"))

      marked should include("define private void @arc.retain(ptr %p) #99 {")
      marked should include("define i32 @main() #99 {")
      marked should include("attributes #99 = { sanitize_thread }")
    }

    // One group carries them all: LLVM takes a list, and a second numbered group would be a second
    // number for this file to own.
    "two sanitizers share the one group" in {
      val marked = Toolchain.sanitized(ir, List("sanitize_address", "sanitize_thread"))

      marked should include("attributes #99 = { sanitize_address sanitize_thread }")
      "attributes #99".r.findAllIn(marked).length shouldBe 1
    }

    // A line that merely opens a block is not a definition, and marking one would not parse — which
    // is what the two conditions on the line are for, rather than matching `{` alone.
    "and nothing that is not a definition is touched" in {
      val marked = Toolchain.sanitized(ir, List("sanitize_thread"))

      marked should include("entry:")
      marked should not include "entry: #99"
      marked should not include "}#99"
    }
  }
}
