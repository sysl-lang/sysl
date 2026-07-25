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
    "an explicit && short-circuits its right side behind a branch" in {
      val out = irMain("print(1 < 2 && 2 < 3)")

      // Still two comparisons, but the right one sits in a conditionally-entered block rather than
      // being eagerly `and`ed with the left.
      (out.split("icmp").length - 1) shouldBe 2
      out should include("sc.rhs")
      out should include("sc.end")
      out should not include "and i1"
    }

    "a chained comparison a < b < c becomes two anded icmps" in {
      val out = irMain("print(1 < 2 < 3)")

      (out.split("icmp").length - 1) shouldBe 2
      out should include("and i1")
    }
  }
}
