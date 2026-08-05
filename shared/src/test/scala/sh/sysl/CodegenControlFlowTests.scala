package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Codegen of control flow: the basic blocks and branches for `if` and `while`. */
class CodegenControlFlowTests extends AnyFreeSpec with CodegenSupport {

  "while emits cond/body/end labels and a back-edge" in {
    val out = ir("var i = 0\nwhile i < 3\n    i++")

    out should include("while.cond")
    out should include("while.body")
    out should include("while.end")
    out should include("icmp slt i32")
  }

  // The whole point of the construct at this level: no condition block and no test, just a body
  // that branches to itself.
  "loop emits a body that branches back to itself, with no test" in {
    val out = ir("var i = 0\nloop\n    i++\n    if i == 3 then break")

    out should include("loop.body")
    out should include("loop.end")
    out should include("br label %loop.body")
    out should not include "loop.cond"
  }

  // Nothing leaves the loop, so nothing arrives after it and the end block says so.
  "a loop with no break ends in unreachable" in {
    val out = ir("loop\n    print(1)")

    out should include("loop.end")
    out should include("unreachable")
  }

  "if with else emits both arms" in {
    val out = ir("if true\n    print(1)\nelse\n    print(2)")

    out should include("if.then")
    out should include("if.else")
    out should include("if.end")
  }
}
