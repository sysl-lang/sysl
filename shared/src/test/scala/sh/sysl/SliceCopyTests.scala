package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** What `sysl.slices.copy` hands the move to, which is the one claim its own `@test` functions
 * cannot make.
 *
 * The library's cases in `library/sysl/slices/tests.sysl` cover every length relation and both
 * overlap directions at both declarations, and they pass whichever declaration answered — the two
 * are specified to behave identically and do. **So the thing that has to be asserted here is which
 * code was emitted**, and the only place that is visible is the IR.
 *
 * There are two claims and they are opposite, which is what makes the pair worth having:
 *
 *  - a `[]u8` copy reaches `memmove`, because the byte declaration named its parameters and
 *    `reference/declarations.md § Overloading` prefers it over the generic one solved to the same
 *    signature;
 *  - a copy at any other element type does **not**, because an element carrying a reference count
 *    needs the retain and the release an assignment does.
 *
 * The second is not a performance note. A `memmove` over `[]string` leaks what the destination held
 * and hands the source's boxes out twice, which AddressSanitizer reports as a `heap-use-after-free`
 * in the renderer — and which prints the right answer without it. That is why the generic
 * declaration is a loop and why nothing may quietly widen the byte one to cover `T`.
 *
 * **And there is a third, which the gate found rather than anybody predicting it: a freestanding
 * target must name no `memmove` at all.** A bare board is linked `-nostdlib`, so there is nothing to
 * call — and `sysl.harness` is written for exactly such a board and reaches this module, so the
 * whole QEMU tier failed to link the day the byte declaration reached libc unconditionally. The
 * declaration is the same on every target and its *body* is gated; what a caller writes does not
 * change, and what it costs does.
 */
class SliceCopyTests extends AnyFreeSpec with Matchers with CodegenSupport with RunSupport {

  // `sysl_memmove` is `private[sysl]`, so a program cannot name it and the symbol can only have got
  // there through the library's own declaration.
  "a byte copy reaches the platform's move" in {
    val out = ir("""import sysl.slices.copy
                   |
                   |var dst: [4]u8 = [0, 0, 0, 0]
                   |var src: [3]u8 = [1, 2, 3]
                   |
                   |print(copy(dst[..], src[..]))
                   |""".stripMargin)

    out should include("@memmove(")
  }

  // The same program at a wider element type. Nothing about the call changed but the type, and that
  // is the whole of what decides which declaration took it.
  "and a copy at any other element type does not, because an element may carry a count" in {
    val out = ir("""import sysl.slices.copy
                   |
                   |var dst: [4]u32 = [0, 0, 0, 0]
                   |var src: [3]u32 = [1, 2, 3]
                   |
                   |print(copy(dst[..], src[..]))
                   |""".stripMargin)

    out should not include "@memmove("
  }

  // The same call on a bare board, where there is nothing to call. `sysl.harness` reaches this
  // module and a QEMU image is linked `-nostdlib`, so a `memmove` here is an undefined symbol at
  // the link — which is where this was found, six suites deep into a gate.
  "and a freestanding target names no memmove at all, because there is nothing to link against" in {
    val src = """import sysl.slices.copy
                |
                |var dst: [4]u8 = [0, 0, 0, 0]
                |var src: [3]u8 = [1, 2, 3]
                |
                |copy(dst[..], src[..])
                |""".stripMargin

    irFor(Target.aarch64Freestanding, src) should not include "@memmove("
    irFor(Target.riscv64Freestanding, src) should not include "@memmove("
  }

  // The case the loop exists for, run rather than reasoned about: the destination's own strings have
  // to be released and the source's retained. A bitwise move is green on this program and faults
  // under `-fsanitize=address` once the source's frame is gone.
  "a copy of counted elements keeps the counts, so the source may go out of scope" in {
    run("""import sysl.slices.copy
          |
          |fill_from_temp(dst: []string)
          |    var s = ["one", "two", "three"]
          |
          |    copy(dst, s[..])
          |
          |var d = ["a", "b", "c"]
          |
          |fill_from_temp(d[..])
          |print(d[0], d[1], d[2])
          |""".stripMargin) shouldBe "one two three\n"
  }
}
