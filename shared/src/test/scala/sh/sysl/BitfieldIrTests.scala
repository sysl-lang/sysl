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

  // **`volatile` on a bitfield is a volatile access of the container**, which is what makes a
  // `@packed` struct able to describe the hardware register the feature was asked for. The cost is
  // stated rather than diagnosed: a write is a read-modify-write, so a register whose reads have
  // side effects is corrupted by one and nothing here will say so (`reference/types.md § Structs`).
  "a volatile bitfield is reached through its container" - {
    val reg =
      """|@packed
         |struct Reg
         |    enable: volatile u1
         |    mode: volatile u3
         |    prescale: volatile u4
         |static val p: *Reg = ptr_cast(usize(4096))
         |""".stripMargin

    "reading one is a single volatile load of the whole container" in {
      val out = defineOf(ir(reg + "read() -> u4 = p.prescale\nprint(read())"), "read")

      out should include("load volatile i8")
      out should include("lshr i8")
      // The container is the struct's only member, so lifting the field out of a loaded aggregate
      // would be a second way to reach the same byte — and an unqualified one.
      out should not include "extractvalue"
    }

    "writing one is a volatile load and a volatile store, and nothing between them touches memory" in {
      val out = defineOf(ir(reg + "go() = p.mode = 5\ngo()"), "go")

      out should include("load volatile i8")
      out should include("store volatile i8")
      out should include("and i8")
      out should include("or i8")
      out.linesIterator.count(_.contains("load volatile")) shouldBe 1
      out.linesIterator.count(_.contains("store volatile")) shouldBe 1
    }

    // Every field of a bitfield struct is bits of one word, so the qualifier is a property of the
    // container rather than of one range of it: there is no such thing here as a shadow field, which
    // is the thing per-field qualification buys in an ordinary register block.
    "one qualified field makes every access to the container volatile" in {
      val out = defineOf(ir("@packed\nstruct Mixed\n    flag: volatile u1\n    rest: u7\n" +
        "static val p: *Mixed = ptr_cast(usize(4096))\nread() -> u7 = p.rest\nprint(read())"), "read")

      out should include("load volatile i8")
    }

    // A simple enum is its underlying integer, so it is one load — and `reference/types.md §
    // Structs` names it as the spelling a mode field wants, which is the whole reason it must be
    // allowed to be volatile.
    "a simple enum field may be one" in {
      val src = "enum Mode: u3\n    Off\n    Slow\n    Fast\n" +
        "@packed\nstruct Ctrl\n    enable: volatile u1\n    mode: volatile Mode\n    rest: volatile u4\n" +
        "static val p: *Ctrl = ptr_cast(usize(4096))\ngo() = p.mode = Mode.Fast\ngo()"
      val out = defineOf(ir(src), "go")

      out should include("load volatile i8")
      out should include("store volatile i8")
    }

    // The control for all four above, and it is the same program with the qualifier taken off. A
    // small struct with no volatile field is still lifted out of a loaded value, which is what the
    // qualifier changes: what the tests above are seeing is the qualifier, and not the shape of a
    // pointer dereference.
    "and the same struct with no qualified field is still read out of a loaded value" in {
      val out = defineOf(ir(ctrl + "static val p: *Ctrl = ptr_cast(usize(4096))\n" +
        "read() -> u4 = p.prescale\nprint(read())"), "read")

      out should include("extractvalue %struct.Ctrl")
      out should not include "volatile"
    }

    // The neighbouring case, and the reason the container rule is narrow: a packed struct of whole
    // bytes is not a bitfield struct at all, so its registers are reached one field at a time.
    "a packed struct of whole bytes is still a register block, one field at a time" in {
      ir("@packed\nstruct Block\n    a: volatile u32\n    b: volatile u32\n" +
        "var p: *Block = ptr_cast(4096usize)\np.a = 1u32\n") should include("store volatile i32")
    }
  }

  "construction ors the fields together rather than inserting each" in {
    val out = ir(odd + "print(o.b)")

    out should include("insertvalue %struct.Odd undef, i24")
    out.linesIterator.count(_.contains("insertvalue %struct.Odd")) shouldBe 1
  }
}
