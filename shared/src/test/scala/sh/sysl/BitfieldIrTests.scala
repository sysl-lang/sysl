package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The IR a bitfield struct lowers to (`Bitfields`).
 *
 * `BitfieldRunTests` proves the values come back, and it does so by reading the storage through a
 * `*u8`, which is the strongest evidence available inside the language. What it cannot show is the
 * *shape* of the lowering — that the container is one integer rather than an aggregate, that a read
 * is a shift and a truncation with no sign fixup, and that a write is one mask and one or. Those are
 * claims about the emitted module, so they are asserted against it.
 */
class BitfieldIrTests extends AnyFreeSpec with CodegenSupport {

  private val ctrl =
    """|@packed
       |struct Ctrl
       |    enable: u1
       |    mode: u3
       |    prescale: u4
       |""".stripMargin

  private val odd =
    """|@packed
       |struct Odd
       |    a: u3
       |    b: u16
       |    c: u5
       |val o = Odd(1, 2, 3)
       |""".stripMargin

  "the struct is one integer" - {
    "the emitted type has a single member, the container" in {
      ir(ctrl + "val c = Ctrl(1, 5, 9)\nprint(c.mode)") should include("%struct.Ctrl = type <{ i8 }>")
    }
    "and its width is the fields' total rounded up to a byte" in {
      ir(odd + "print(o.b)") should include("%struct.Odd = type <{ i24 }>")
    }
  }

  "a read is a shift and a truncation" - {
    "the shift is the field's own offset" in {
      ir(ctrl + "var c = Ctrl(1, 5, 9)\nprint(c.prescale)") should include("lshr i8")
    }
    // The signedness is in the type rather than in the extraction: an `i5` field *is* an LLVM `i5`,
    // so truncating the shifted container lands the two's-complement value already in place. A C
    // bitfield needs a sign fixup here and this does not.
    "and a signed field needs no sign fixup" in {
      val out = ir("@packed\nstruct S\n    a: u4\n    b: i5\nval s = S(3, -7)\nprint(s.b)")

      out should include("trunc i16")
      out should not include "ashr"
    }
    // Against a twenty-four-bit container, because the assertions below are about the *absence* of
    // an instruction and the module holds the standard library too — `lshr i8` is emitted by plenty
    // that has nothing to do with this struct, and `lshr i24` by nothing else at all.
    "a field starting at bit zero is not shifted at all" in {
      val out = ir(odd + "print(o.a)")

      out should include("trunc i24")
      out should not include "lshr i24"
    }
    "and one further up is shifted by exactly its own offset" in {
      ir(odd + "print(o.c)") should include regex "lshr i24 %[^,]+, 19"
    }
  }

  "a write reads the container, masks the range out, and puts the new one in" in {
    val out = ir(ctrl + "var c = Ctrl(1, 5, 9)\nc.mode = 2\nprint(c.mode)")

    // 0b11110001 — every bit but the three `mode` occupies, as an unsigned `i8`.
    out should include("and i8")
    out should include("241")
    out should include("or i8")
    out should include("shl i8")
  }

  "a field filling the container is neither masked nor or-ed" in {
    // `u12` alone is a twelve-bit container rounded to sixteen, so this is `{a: u16}` in effect —
    // the case where the mask would be a constant zero and the `or` an instruction saying nothing.
    val out = ir("@packed\nstruct Whole\n    a: u16\n    b: u3\nvar w = Whole(9, 1)\nw.a = 5\nprint(w.a)")

    out should include("%struct.Whole = type <{ i24 }>")
    out should include("or i24")
  }

  "construction ors the fields together rather than inserting each" in {
    val out = ir(odd + "print(o.b)")

    out should include("insertvalue %struct.Odd undef, i24")
    out.linesIterator.count(_.contains("insertvalue %struct.Odd")) shouldBe 1
  }
}
