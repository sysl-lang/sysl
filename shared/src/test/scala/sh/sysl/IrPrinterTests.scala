package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

import ir.{Access, Attr, BinOp, Block, FnType, Func, FuncSig, Inst, Linkage, LType, Param, Printer, Val}

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

  private val i32 = LType.I(32)
  private val t1  = Val.Reg("t1")

  private def sig(name: String, ret: LType) = FuncSig(name, FnType(ret, Nil))

  "a function is its header, a brace, its blocks, and a closing brace on its own line" in {
    val add = Inst.Bin(t1, BinOp.Add, i32, Val.Int(1), Val.Int(2))
    val f   = Func(sig("main", i32),
                   List(Block("entry", List(add), Some(Inst.Ret(Some(i32), Some(t1))))))

    Printer.func(f) shouldBe
      """|define i32 @main() {
         |entry:
         |  %t1 = add i32 1, 2
         |  ret i32 %t1
         |}
         |""".stripMargin
  }

  "an instruction is indented two spaces and a label is not, which is what makes one findable" in {
    val store = Inst.Store(i32, Val.Int(0), Val.Reg("p"), Access.Plain)
    val f     = Func(sig("f", LType.Void),
                     List(Block("entry", Nil, Some(Inst.Br("body"))),
                          Block("body", List(store), Some(Inst.Ret(None, None)))))

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
    val f = Func(sig("f", LType.Void),
                 List(Block("entry", Nil, Some(Inst.Br("a"))),
                      Block("a", Nil, Some(Inst.Br("b"))),
                      Block("b", Nil, Some(Inst.Ret(None, None)))))

    Printer.func(f) shouldBe "define void @f() {\nentry:\n  br label %a\na:\n  br label %b\nb:\n  ret void\n}\n"
  }

  /** A block with no terminator is a malformed function and the back end says so. It is printed as
   * it stands rather than closed with an invented `br`: what the emitters produced is what a
   * diagnostic has to be about, and a printer that repaired this would hide the bug that made it.
   */
  "an unterminated block is printed unterminated rather than repaired" in {
    val load = Inst.Load(t1, i32, Val.Reg("p"), Access.Plain)
    val f    = Func(sig("f", LType.Void), List(Block("entry", List(load), None)))

    Printer.func(f) shouldBe "define void @f() {\nentry:\n  %t1 = load i32, ptr %p\n}\n"
  }

  "and a block with nothing in it at all is its label and its terminator" in {
    val f = Func(sig("f", LType.Void), List(Block("entry", Nil, Some(Inst.Unreachable))))

    Printer.func(f) shouldBe "define void @f() {\nentry:\n  unreachable\n}\n"
  }

  /** The signature is the half of the IR that stayed text longest, so its spellings are pinned here
   * rather than only where a program happens to produce one. Every attribute below is one the
   * conventions in `CAbi` actually emit; there are seven, which is a measurement of this compiler
   * rather than a subset of LLVM's manual.
   */
  "a signature" - {
    val point = LType.Named("%struct.Point")

    "spells each of the seven attributes the conventions ask for" in {
      Attr.SRet(point).render shouldBe "sret(%struct.Point)"
      Attr.ByVal(point).render shouldBe "byval(%struct.Point)"
      Attr.Align(8).render shouldBe "align 8"
      Attr.AlignStack(8).render shouldBe "alignstack(8)"
      Attr.NoAlias.render shouldBe "noalias"
      Attr.ZeroExt.render shouldBe "zeroext"
      Attr.SignExt.render shouldBe "signext"
    }

    // The order is the list's, because that is the order LLVM's grammar puts them in and the order
    // the conventions were measured writing them — `noalias` before `sret`, `align` after it.
    "writes a parameter as its type, then its attributes, then the name a definition gives it" in {
      Param(LType.Ptr,
            List(Attr.NoAlias, Attr.SRet(point), Attr.Align(8)),
            Some(Val.Reg("sret.out"))).render shouldBe
        "ptr noalias sret(%struct.Point) align 8 %sret.out"

      Param(LType.Ptr, List(Attr.SRet(point), Attr.Align(8))).render shouldBe
        "ptr sret(%struct.Point) align 8"

      Param(i32).render shouldBe "i32"
    }

    // A `declare` is not a `define` with the body left off: it drops the linkage, the convention,
    // the function attributes and the section, all of which are properties of the definition.
    "prints as a declare or a define from the one value, and the two differ in what they carry" in {
      val s = FuncSig("f",
                      FnType(LType.I(1),
                             List(Param(i32, name = Some(Val.Reg("n")))),
                             retAttrs = List(Attr.ZeroExt)),
                      Linkage.Internal,
                      cconv = Some("x86_intrcc"),
                      attrs = List("interrupt" -> "machine"),
                      section = Some(".text.hot"))

      s.define shouldBe
        """define internal x86_intrcc zeroext i1 @f(i32 %n) "interrupt"="machine" section ".text.hot""""
      s.declare shouldBe "declare zeroext i1 @f(i32 %n)"
    }

    "puts the ellipsis last, where a variadic call has to name the whole type" in {
      FnType(i32, List(Param(LType.Ptr)), variadic = true).render shouldBe "i32 (ptr, ...)"
      FnType(LType.Void, Nil).render shouldBe "void ()"
    }
  }
}
