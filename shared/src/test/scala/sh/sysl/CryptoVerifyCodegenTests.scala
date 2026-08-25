package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The shape `sysl.crypto.verify` depends on, pinned against the compiler's own output.
 *
 * **What this suite is for is narrow, and saying so is half of it.** `verify` compares two tags
 * without revealing where they first differ, and the whole of that property is that it reads every
 * byte and never decides anything on their contents. That is a fact about the *shape* of the emitted
 * loop, so it is assertable — and left unasserted it is folklore that lasts exactly as long as
 * nobody rewrites the loop.
 *
 * **This checks sysl's half and not LLVM's.** The IR here is what the compiler emits before any
 * optimization, so a pass that reintroduced an early exit downstream would not fail this. That half
 * was read by hand at 0.0.79 on aarch64 — the loop is branchless and the verdict is a `cset` — and
 * re-reading it on another target is `SYSL_RELEASE=1 sysl build` plus a disassembler.
 *
 * **THE COUNTS ARE WRITTEN DOWN RATHER THAN DERIVED**, for the reason `DocsTests` writes its block
 * counts down: a number computed from the output would agree with the output whatever it said. If a
 * change to bounds checking moves them, that is a thing to re-read and confirm rather than a number
 * to update on sight.
 *
 * **And the first version of this suite could not fail** — it looked for a branch whose condition
 * was the accumulator's own register, and an early exit does not have one: the value goes to the
 * slot and comes back through a fresh `load`, so nothing matched and the assertion passed on code
 * that had exactly the defect it was written to catch. The mutation below is what found that, and
 * both assertions here were re-checked against it.
 */
class CryptoVerifyCodegenTests extends AnyFreeSpec with CodegenSupport {

  /** The comparison as `library/sysl/crypto/verify.sysl` writes it. Written out rather than imported
   * because the library's copy is linked object code by the time a program sees it, so there is no
   * body in a program's IR to assert on. What is pinned is the compiler's treatment of the pattern.
   */
  private val verify =
    """verify(a: []const u8, b: []const u8) -> bool
      |    if a.len != b.len then return false
      |
      |    var diff: u8 = 0
      |
      |    for i in 0..<a.len do diff |= a[i] ^ b[i]
      |
      |    diff == 0
      |end verify
      |
      |""".stripMargin

  private def body: String =
    defineOf(ir(verify + "main()\n    print(verify(\"a\".bytes, \"a\".bytes))\n"), "verify")

  "the accumulator is read twice: folded into once, and asked once at the end" in {
    // This is the assertion that carries the property. Every `if` on the difference — which is what
    // an early exit is — has to read the slot to test it, so a third read is exactly the defect.
    // Measured: two here, three with an early exit spliced in.
    """load i8, ptr %diff\.addr""".r.findAllMatchIn(body).size shouldBe 2

    // And what it is folded with, so that a loop which stopped accumulating would be caught too.
    body should include("xor i8")
    body should include("or i8")
  }

  "four conditional branches, and every one of them is on a length or an index" in {
    // The length check, the loop condition, and one bounds check for each of the two slices. An
    // early exit is a fifth. None of these is on a byte of either input, which is the point.
    """br i1 """.r.findAllMatchIn(body).size shouldBe 4
  }

  "the verdict is a single comparison against zero, taken after the loop" in {
    """icmp eq i8""".r.findAllMatchIn(body).size shouldBe 1
  }
}
