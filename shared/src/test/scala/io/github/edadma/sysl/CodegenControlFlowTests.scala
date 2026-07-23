package io.github.edadma.sysl

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

  "if with else emits both arms" in {
    val out = ir("if true\n    print(1)\nelse\n    print(2)")

    out should include("if.then")
    out should include("if.else")
    out should include("if.end")
  }
}
