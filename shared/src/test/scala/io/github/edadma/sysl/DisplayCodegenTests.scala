package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-1 lowering of rendering (`14 §6`): the two sinks, and which path a value takes to text.
 *
 * What is worth pinning here rather than leaving to the run suite is the part a working program
 * cannot tell you about — that a scalar still renders with no sink at all, that the sink a `print`
 * builds costs nothing but two words, and that a program which never renders a user type carries
 * neither writer.
 */
class DisplayCodegenTests extends AnyFreeSpec with CodegenSupport {

  private val point =
    """struct Point
      |    x: int
      |    y: int
      |impl Display for Point
      |    display(self, out: *Writer, fmt: FormatSpec)
      |        self.x.display(out, fmt)
      |""".stripMargin

  "the scalar path is untouched" - {
    // `14 §8 b`: the two renderings are identical, so the one that does not build a sink is the one
    // to emit — and a program that only prints numbers should not carry a method table at all.
    "a printed scalar calls its renderer directly" in {
      val out = irMain("print(5)")

      out should include regex """call void @printi\(i64 %t\d+\)"""
      out should not include "insertvalue { ptr, ptr }"
    }

    "a program that prints only scalars carries no writer" in {
      val out = ir("print(5, \"a\", true)")

      out should not include "@sysl.vt.out"
      out should not include "@sysl.w.buf.write"
    }

    "str of a scalar still renders without a buffer" in {
      val out = irMain("print(str(5))")

      out should include("@sysl.str.int")
      out should not include "@sysl.w.buf.finish"
    }
  }

  "the sink a print builds" - {
    // Two words and no allocation: the table is a constant and the data word is null, because a
    // writer over standard output has nothing to point at.
    "is a constant table beside a null" in {
      val out = irMain(point + "print(Point(1, 2))")

      out should include("insertvalue { ptr, ptr } undef, ptr @sysl.vt.out, 0")
      out should include regex """insertvalue \{ ptr, ptr \} %t\d+, ptr null, 1"""
    }

    "hands the value, the sink, and the specifier to the type's own display" in {
      irMain(point + "print(Point(1, 2))") should include regex
        """call void @Point\.display\(%struct\.Point %t\d+, \{ ptr, ptr \} %t\d+, %struct\.FormatSpec %t\d+\)"""
    }

    "reaches standard output through the prelude's own byte sink" in {
      defineOf(ir(point + "print(Point(1, 2))"), "sysl.w.out.write") should
        include("call void @putbytes({ ptr, ptr, i64 } %b)")
    }
  }

  "the sink a str builds" - {
    // A stack slot, zeroed on arrival rather than once: an alloca is hoisted to the entry block, so
    // a render inside a loop meets the same slot every time round and has to start it empty.
    "is a stack slot the render zeroes each time" in {
      val out = irMain(point + "print(str(Point(1, 2)))")

      out should include("alloca { ptr, i64, i64 }")
      out should include regex """store \{ ptr, i64, i64 \} zeroinitializer, ptr %t\d+"""
    }

    "turns what landed there into a string the statement owns" in {
      val out = irMain(point + "print(str(Point(1, 2)))")

      out should include regex """call \{ ptr, ptr, i64 \} @sysl\.w\.buf\.finish\(ptr %t\d+\)"""
      out should include("call void @arc.release_maybe")
    }
  }

  "the writers' tables" - {
    // The compiler lays these out by hand, so the order is the contract: slot 0 is `write` and slot
    // 1 is `failed`, which is what a call through the object indexes by.
    "hold write first and failed second" in {
      val out = ir(point + "print(Point(1, 2))\nprint(str(Point(3, 4)))")

      out should include("@sysl.vt.out = private constant [2 x ptr] " +
        "[ptr @sysl.w.out.write, ptr @sysl.w.out.failed]")
      out should include("@sysl.vt.buf = private constant [2 x ptr] " +
        "[ptr @sysl.w.buf.write, ptr @sysl.w.buf.failed]")
    }

    "are emitted once however many values render" in {
      val out = ir(point + "print(Point(1, 2))\nprint(Point(3, 4))\nprint(Point(5, 6))")

      out.linesIterator.count(_.startsWith("@sysl.vt.out =")) shouldBe 1
    }

    "a program that only prints a user type carries no buffer" in {
      val out = ir(point + "print(Point(1, 2))")

      out should include("@sysl.vt.out")
      out should not include "@sysl.vt.buf"
    }
  }

  "a writer a program wrote" - {
    // An ordinary erasure, so `write`'s `*self` lands in the slot with no adapter between — the
    // data word of a raw object already *is* the receiver it declared.
    "puts its own implementation straight into the table" in {
      ir("""struct C
            |    n: usize
            |impl Writer for C
            |    write(*self, bytes: []const u8)
            |        self.n += bytes.len
            |    failed(*self) -> bool = false
            |var c: C
            |var w: *Writer = &c
            |display_int(1, w, FormatSpec(0, -1, false))""".stripMargin) should include(
        "@vt.Writer.C = private constant [2 x ptr] [ptr @C.write, ptr @C.failed]")
    }
  }
}
