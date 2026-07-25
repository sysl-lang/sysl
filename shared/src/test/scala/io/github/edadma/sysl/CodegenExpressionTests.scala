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

    // A chain lowers to the same shape as `&&` rather than to eager comparisons ANDed together:
    // each comparison branches, and the ones after it sit in conditionally-entered blocks.
    "a chained comparison branches between its comparisons" in {
      val out = irMain("print(1 < 2 < 3)")

      (out.split("icmp").length - 1) shouldBe 2
      out should include("cmp.next")
      out should include("cmp.exit")
      out should not include "and i1"
    }

    // The exits unwind in reverse, so an operand built in a later block is released before the
    // regions that were entered ahead of it — and the short-circuit edge skips that pop entirely.
    "the exits of a chain unwind in reverse" in {
      val out = irMain("""f(s: string) -> string = s + "!"
                         |print(f("a") < f("b") < f("c"))""".stripMargin)

      val order = out.linesIterator.map(_.trim).filter(l => l.startsWith("cmp.") && l.endsWith(":")).toList

      order shouldBe List("cmp.next4:", "cmp.exit2:", "cmp.exit1:", "cmp.end3:")

      // Two operands are always built and released at the outer exit; the third only in cmp.exit2.
      val outerExit = out.linesIterator.dropWhile(_.trim != "cmp.exit1:").takeWhile(!_.trim.startsWith("cmp.end"))

      outerExit.count(_.contains("@arc.release_maybe")) shouldBe 2
    }

    // A single comparison is not a chain and has nothing to short-circuit, so it stays
    // straight-line rather than paying for a ladder it cannot use.
    "a lone comparison emits no branch at all" in {
      val out = irMain("print(1 < 2)")

      out should include("icmp")
      out should not include "cmp.next"
      out should not include "cmp.exit"
    }
  }
}
