package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a program says when it runs out of stack, and what it recovers of its own output
 * (`library/sysl/__posix__/stackguard.c`, `Codegen.genStackGuard`).
 *
 * **Both halves were measured before this existed and both were nothing.** A program that recursed
 * without bound died with status 139 and **zero bytes** of output — not even the lines it had
 * already printed, which sit in stdio's buffer and go with the process. So a reader had a segfault
 * and no reason to suspect recursion depth rather than a wild pointer, which is a much more
 * alarming place to go looking.
 *
 * The two cases below are told apart by where the faulting address lies, which is what Rust, Go and
 * Java each do. Getting that wrong in the safe direction is a plain bad-pointer message, so the
 * second case is as much a part of the feature as the first.
 */
class StackGuardTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** The recursion has to be one LLVM cannot turn into a loop, which is the whole reason this
    * fixture looks the way it does: an accumulator recursion — `deep(n + 1) + x` — is rewritten into
    * an infinite *loop* that never touches the stack at all, and the first fixture written here did
    * exactly that and ran forever. Feeding the result into an index is what keeps the frame.
    */
  private val unbounded =
    """static var one: int = 1
      |
      |deep(n: int) -> int
      |    var pad: [256]int = [0; 256]
      |
      |    pad[0] = n
      |
      |    val r = deep(n + one)
      |
      |    pad[usize(r % 7)] + 1
      |
      |print("about to recurse")
      |print(deep(1))
      |""".stripMargin

  "a program that runs out of stack says so" - {

    "and names recursion rather than leaving a bare segfault" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val o = outcomeOf(unbounded)

      o.status should not be 0
      o.err should include("has overflowed its stack")
    }

    // The second finding, and the cheaper one to lose: every line the program had printed was in
    // stdio's buffer, which is exactly the evidence needed to find where it went wrong.
    "and what it had already printed comes out, which it did not before" in {
      assume(Toolchain.clangAvailable, "clang not available")

      outcomeOf(unbounded).out should include("about to recurse")
    }
  }

  "a fault somewhere else is not called an overflow" - {

    "which is what makes the first message worth trusting" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val o = outcomeOf("""static var addr: usize = 0x10
                          |
                          |print("about to reach")
                          |val p: *int = ptr_cast(addr)
                          |print(*p)
                          |""".stripMargin)

      o.status should not be 0
      o.err should include("faulted on an address it does not own")
      o.err should not include "overflowed"
    }

    "and its buffered output is recovered too" in {
      assume(Toolchain.clangAvailable, "clang not available")

      outcomeOf("""static var addr: usize = 0x10
                  |
                  |print("about to reach")
                  |val p: *int = ptr_cast(addr)
                  |print(*p)
                  |""".stripMargin).out should include("about to reach")
    }
  }

  "a program that does not fault is untouched" in {
    val o = outcomeOf("print(\"fine\")")

    o.status shouldBe 0
    o.out shouldBe "fine\n"
    o.err shouldBe ""
  }

  "where the call is emitted" - {

    "first thing in a hosted program's entry point" in {
      ir("print(1)") should include(s"call void @${Codegen.StackGuardSymbol}()")
    }

    /** A freestanding machine has no signals to catch, and the C is in a `__posix__` directory so it
      * is neither compiled nor linked there — emitting the call would name a symbol nothing defines.
      */
    "and not at all for a machine with no operating system" in {
      irFor(Target.all.find(_.name == "aarch64-freestanding").get, "print(1)") should
        not include Codegen.StackGuardSymbol
    }
  }
}
