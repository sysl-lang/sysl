package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import ir.{Block, Func, Inst, Printer}

/** Writing a function down (`ir.Printer`).
 *
 * The printer's whole obligation is to produce the text the emitters used to produce directly, and
 * every character of it is load-bearing: the codegen tier asserts on emitted IR by substring, so a
 * lost indent or a stray blank line is not a cosmetic difference — it is a suite full of failures
 * that name the wrong culprit. Those tests assert on fragments of real programs, which is what makes
 * them a regression net and also what keeps them from saying what the *shape* of a printed function
 * is. That is what this file is for.
 *
 * The blocks below are built by hand rather than emitted, deliberately. A test that lowers a program
 * and reads the result back would agree with the emitter about anything the two of them got wrong
 * together.
 */
class IrPrinterTests extends AnyFreeSpec with Matchers {

  private def raw(s: String) = Inst.Raw(s)

  "a function is its header, a brace, its blocks, and a closing brace on its own line" in {
    val f = Func("define i32 @main()",
                 List(Block("entry", List(raw("%t1 = add i32 1, 2")), Some(raw("ret i32 %t1")))))

    Printer.func(f) shouldBe
      """|define i32 @main() {
         |entry:
         |  %t1 = add i32 1, 2
         |  ret i32 %t1
         |}
         |""".stripMargin
  }

  "an instruction is indented two spaces and a label is not, which is what makes one findable" in {
    val f = Func("define void @f()",
                 List(Block("entry", Nil, Some(raw("br label %body"))),
                      Block("body", List(raw("store i32 0, ptr %p")), Some(raw("ret void")))))

    Printer.func(f) shouldBe
      """|define void @f() {
         |entry:
         |  br label %body
         |body:
         |  store i32 0, ptr %p
         |  ret void
         |}
         |""".stripMargin
  }

  // Nothing separates one block from the next: the label is the separator, and a blank line between
  // them would break every codegen assertion that matches a run of instructions across a boundary.
  "blocks run together, with the label as the only separator" in {
    val f = Func("define void @f()",
                 List(Block("entry", Nil, Some(raw("br label %a"))),
                      Block("a", Nil, Some(raw("br label %b"))),
                      Block("b", Nil, Some(raw("ret void")))))

    Printer.func(f) shouldBe "define void @f() {\nentry:\n  br label %a\na:\n  br label %b\nb:\n  ret void\n}\n"
  }

  /** A block with no terminator is a malformed function and the back end says so. It is printed as
   * it stands rather than closed with an invented `br`: what the emitters produced is what a
   * diagnostic has to be about, and a printer that repaired this would hide the bug that made it.
   */
  "an unterminated block is printed unterminated rather than repaired" in {
    val f = Func("define void @f()", List(Block("entry", List(raw("%t1 = load i32, ptr %p")), None)))

    Printer.func(f) shouldBe "define void @f() {\nentry:\n  %t1 = load i32, ptr %p\n}\n"
  }

  "and a block with nothing in it at all is its label and its terminator" in {
    val f = Func("define void @f()", List(Block("entry", Nil, Some(raw("unreachable")))))

    Printer.func(f) shouldBe "define void @f() {\nentry:\n  unreachable\n}\n"
  }
}
