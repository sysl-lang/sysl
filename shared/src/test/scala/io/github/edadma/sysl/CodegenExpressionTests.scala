package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Codegen of expressions: arithmetic instruction selection, precedence in emission order,
 * and chained comparisons.
 */
class CodegenExpressionTests extends AnyFreeSpec with CodegenSupport {

  "arithmetic lowering" - {
    "precedence shows in instruction order — mul before add" in {
      val out = ir("print(1 + 2 * 3)")

      out should include("mul i32 2, 3")
      out should include regex "add i32 1, %t\\d+".r
    }

    "integer division uses signed sdiv" in {
      ir("print(7 / 2)") should include("sdiv i32 7, 2")
    }

    "float arithmetic uses the fadd family" in {
      ir("print(1.5 + 2.5)") should include("fadd double")
    }
  }

  "comparison chains" - {
    "an explicit && of comparisons lowers to two anded icmps" in {
      val out = ir("print(1 < 2 && 2 < 3)")

      (out.split("icmp").length - 1) shouldBe 2
      out should include("and i1")
    }

    "a chained comparison a < b < c becomes two anded icmps" in {
      val out = ir("print(1 < 2 < 3)")

      (out.split("icmp").length - 1) shouldBe 2
      out should include("and i1")
    }
  }
}
