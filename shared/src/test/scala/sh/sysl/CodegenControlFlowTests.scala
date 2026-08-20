package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Codegen of control flow: the basic blocks and branches for `if` and `while`. */
class CodegenControlFlowTests extends AnyFreeSpec with CodegenSupport with RunSupport {

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

  /** What happens to the code written after control has already left.
   *
   * Nothing refuses it — an `exit` followed by a value is legal, and saying so is what lets a
   * function end in a `match` whose arms do not all produce one. So the emitters walk it like any
   * other code and drop what they produce, and **the dropping has to reach the blocks it opens as
   * well as the instructions**. It did not: dropping stopped at the first label, and a value whose
   * lowering opens a block of its own was emitted in halves — the registers it computed dropped
   * with the closed block, the blocks that read them emitted whole. The result is not dead code
   * left lying about but a module clang refuses outright, and only clang refuses it: the analyzer
   * has no objection to make, so `sysl test` is silent and `sysl build` is where it lands.
   *
   * A slice repeat is what these are written with, because it is a small expression that lowers to
   * an allocation, a fill loop, and four labels. Anything with a branch in it does as well.
   */
  "code after a divergence" - {

    "a match arm's value is dropped with its blocks" in {
      val src = """enum Answer
                  |    Yes(n: int)
                  |    No
                  |
                  |pick(a: Answer) -> []u8
                  |    a match
                  |        Yes(n) -> [u8(n); 2]
                  |        No ->
                  |            exit(1)
                  |            [0; 0]
                  |end pick
                  |
                  |print(pick(Yes(7)).len)
                  |""".stripMargin

      definesEveryRegisterItReads(src)
      run(src) shouldBe "2\n"
    }

    // Card 0165 was found at a `match` and is not about one: an `if` used as a value has the same
    // shape and was equally broken, which is worth its own case because a fix aimed at match arms
    // would have passed the test above and left this one.
    "as is an if-expression's" in {
      val src = """pick(n: int) -> []u8
                  |    if n == 0
                  |        exit(1)
                  |        [0; 0]
                  |    else
                  |        [7; 3]
                  |end pick
                  |
                  |print(pick(2).len)
                  |""".stripMargin

      definesEveryRegisterItReads(src)
      run(src) shouldBe "3\n"
    }

    // Nor is it about arms. A plain statement after a divergence is the same dead region, reached
    // without any branching construct at all.
    "and a plain statement after one, with no arm anywhere" in {
      val src = """main() -> unit =
                  |    exit(3)
                  |    var n = 3
                  |    var xs: []u8 = [0; n]
                  |    print(xs.len)
                  |""".stripMargin

      definesEveryRegisterItReads(src)
      exitsWith(src, 3)
    }

    // The blocks are not merely harmless — they carry nothing. What the arm computed is gone, so a
    // dead region cannot allocate, cannot loop, and cannot be jumped into from the live code that
    // follows it.
    "the blocks it leaves behind hold nothing but 'unreachable'" in {
      val out = irMain("""exit(1)
                         |var n = 3
                         |var xs: []u8 = [0; n]
                         |print(xs.len)
                         |""".stripMargin)

      out should not include "@malloc"
      out should include("unreachable")
    }

    // The control, and the reason this took so long to find: each ingredient alone is fine. The
    // value has to open a block *and* follow a divergence, so a scalar after an `exit` — which is
    // what most dead code looks like — has always been emitted correctly.
    "while a value that opens no block after one was always fine" in {
      val src = """pick(n: int) -> int
                  |    if n == 0
                  |        exit(1)
                  |        0
                  |    else
                  |        7
                  |end pick
                  |
                  |print(pick(2))
                  |""".stripMargin

      definesEveryRegisterItReads(src)
      run(src) shouldBe "7\n"
    }

    "and so was the same value with nothing diverging before it" in {
      val src = """pick(n: int) -> []u8
                  |    if n == 0
                  |        [0; 0]
                  |    else
                  |        [7; 3]
                  |end pick
                  |
                  |print(pick(0).len)
                  |""".stripMargin

      definesEveryRegisterItReads(src)
      run(src) shouldBe "0\n"
    }
  }
}
