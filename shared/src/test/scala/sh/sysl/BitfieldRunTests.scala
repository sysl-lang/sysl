package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A bitfield struct: inside `@packed`, an `iN` field occupies exactly N bits (`15 §1`).
 *
 * The struct **is** one unsigned integer, and its fields are ranges of that integer filled from the
 * least significant bit upward in declaration order, straddling byte boundaries freely. C leaves
 * both of those implementation-defined, which is why portable embedded C avoids bitfields — so the
 * tests that pin the *order* matter as much as the ones that pin the widths.
 *
 * Where a claim is about where a field physically sits, the storage is read back through a `*u8`
 * (`03 § Reinterpreting storage`) rather than through the struct: reading a field through the struct
 * would use the same offsets the write used, and agree with itself whatever they were.
 */
class BitfieldRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val ctrl =
    """|@packed
       |struct Ctrl
       |    enable: u1
       |    mode: u3
       |    prescale: u4
       |""".stripMargin

  "a field occupies exactly its declared width" - {
    "three fields totalling eight bits are one byte" in {
      run(ctrl + "print(sizeof(Ctrl), alignof(Ctrl))") shouldBe "1 1\n"
    }
    "two twelve-bit fields are three bytes rather than four" in {
      run("@packed\nstruct Pair\n    a: u12\n    b: u12\nprint(sizeof(Pair))") shouldBe "3\n"
    }
    "a total that is not a whole number of bytes rounds up" in {
      run("@packed\nstruct Odd\n    a: u3\n    b: u4\n    c: u5\nprint(sizeof(Odd))") shouldBe "2\n"
    }
    "an array of them has no gap between elements" in {
      run(ctrl + "print(sizeof([4]Ctrl))") shouldBe "4\n"
    }
  }

  "a packed struct of whole bytes is untouched" - {
    // The trigger is a field that is *not* a whole number of bytes. A struct of `u8` and `u32`
    // already occupies exactly those bits under the byte layout, so leaving it alone is the same
    // answer reached without a container rather than an exemption from the rule.
    "it lays out exactly as it did before" in {
      run("@packed\nstruct Head\n    tag: u8\n    len: u32\nprint(sizeof(Head), alignof(Head))") shouldBe "5 1\n"
    }
  }

  "the fields carry their own values" - {
    "each reads back what construction put in it" in {
      run(ctrl + "val c = Ctrl(1, 5, 9)\nprint(c.enable, c.mode, c.prescale)") shouldBe "1 5 9\n"
    }
    "a write to one leaves its neighbours alone" in {
      run(ctrl + "var c = Ctrl(1, 5, 9)\nc.mode = 2\nprint(c.enable, c.mode, c.prescale)") shouldBe "1 2 9\n"
    }
    "a compound assignment reads and writes the same range" in {
      run(ctrl + "var c = Ctrl(1, 5, 9)\nc.mode += 2\nprint(c.enable, c.mode, c.prescale)") shouldBe "1 7 9\n"
    }
    "two fields of one struct may be written by one statement" in {
      run(ctrl + "var c = Ctrl(1, 5, 9)\nc.enable, c.prescale = 0, 3\n" +
        "print(c.enable, c.mode, c.prescale)") shouldBe "0 5 3\n"
    }
    "a signed field keeps its sign" in {
      run("@packed\nstruct S\n    a: u4\n    b: i5\nval s = S(3, -7)\nprint(s.a, s.b)") shouldBe "3 -7\n"
    }
    "a field wider than a byte is still a bit range" in {
      run("@packed\nstruct W\n    tag: u3\n    len: u16\nval w = W(5, 40000)\nprint(w.tag, w.len)") shouldBe "5 40000\n"
    }
  }

  "the bits fill from the least significant upward, in declaration order" - {
    // 1 | (5 << 1) | (9 << 4) — the first field declared is the low bits, which is the half of the
    // layout C leaves to the implementation.
    "the byte holds the first field in its low bits" in {
      val src = ctrl +
        """|var arena: [4]u8 = [0u8; 4]
           |var c: *Ctrl = ptr_cast(&arena[0])
           |c.enable = 1
           |c.mode = 5
           |c.prescale = 9
           |print(arena[0])""".stripMargin

      run(src) shouldBe "155\n"
    }

    // 7 | (0xABCD << 3) | (0x15 << 19) = 0xAD5E6F, whose bytes on a little-endian machine are
    // 0x6F 0x5E 0xAD. The sixteen-bit field starts at bit three and ends in the third byte, which
    // is the straddling C's own rules do not promise.
    "and a field straddles a byte boundary rather than moving off it" in {
      val src =
        """|@packed
           |struct Straddle
           |    a: u3
           |    b: u16
           |    c: u5
           |var arena: [4]u8 = [0u8; 4]
           |var s: *Straddle = ptr_cast(&arena[0])
           |s.a = 7
           |s.b = 43981
           |s.c = 21
           |print(sizeof(Straddle), arena[0], arena[1], arena[2], arena[3])""".stripMargin

      run(src) shouldBe "3 111 94 173 0\n"
    }

    "a write through a pointer reads back through the struct" in {
      val src = ctrl +
        """|var arena: [4]u8 = [0u8; 4]
           |var c: *Ctrl = ptr_cast(&arena[0])
           |arena[0] = 155u8
           |print(c.enable, c.mode, c.prescale)""".stripMargin

      run(src) shouldBe "1 5 9\n"
    }
  }

  "a simple enum is an integer and may be a bitfield" in {
    val src =
      """|enum Mode: u3
         |    Off
         |    Slow
         |    Fast
         |@packed
         |struct Reg
         |    enable: u1
         |    mode: Mode
         |    rest: u4
         |val r = Reg(1, Mode.Fast, 6)
         |print(sizeof(Reg), r.mode == Mode.Fast, r.rest)""".stripMargin

    run(src) shouldBe "1 true 6\n"
  }

  // A `volatile` bitfield is a volatile access of the *container*, so a write is a read-modify-write
  // of the whole of it. `BitfieldIrTests` asserts that the accesses are the ones the source wrote;
  // what is asserted here is the half that matters to a driver — the read-modify-write puts the
  // neighbouring ranges back exactly as it found them.
  "a volatile bitfield keeps its neighbours through the read-modify-write" in {
    val src =
      """|@packed
         |struct Reg
         |    enable: volatile u1
         |    mode: volatile u3
         |    prescale: volatile u4
         |var arena: [4]u8 = [0u8; 4]
         |var r: *Reg = ptr_cast(&arena[0])
         |r.enable = 1
         |r.prescale = 9
         |r.mode = 5
         |print(arena[0], r.enable, r.mode, r.prescale)""".stripMargin

    run(src) shouldBe "155 1 5 9\n"
  }

  "a bitfield struct nests inside an ordinary one" - {
    // The composition path for everything a bitfield struct may not itself hold: it is a leaf, and
    // an outer struct lays it out as an ordinary field of its size.
    "the outer struct lays it out as a field of its size" in {
      val src = ctrl +
        """|@packed
           |struct Block
           |    ctrl: Ctrl
           |    count: u32
           |print(sizeof(Block))""".stripMargin

      run(src) shouldBe "5\n"
    }
    "and the field's own fields are still reachable" in {
      val src = ctrl +
        """|@packed
           |struct Block
           |    ctrl: Ctrl
           |    count: u32
           |var b = Block(Ctrl(1, 5, 9), 3u32)
           |b.ctrl.mode = 2
           |print(b.ctrl.enable, b.ctrl.mode, b.count)""".stripMargin

      run(src) shouldBe "1 2 3\n"
    }
  }

  "@align composes with it, as it does with any packed struct" in {
    run("@align(8)\n" + ctrl + "print(sizeof(Ctrl), alignof(Ctrl))") shouldBe "8 8\n"
  }

  "a struct pattern destructures the ranges" in {
    val src = ctrl +
      """|val c = Ctrl(1, 5, 9)
         |c match
         |    Ctrl(e, m, p) -> print(e, m, p)""".stripMargin

    run(src) shouldBe "1 5 9\n"
  }

  // A `unit` field occupies nothing and is not stored, so the field a program writes third is the
  // range stored second — the two indices differ, and every site that reaches a range by a written
  // index has to say which it has.
  "a zero-sized field is written and occupies no bits" in {
    val src =
      """|@packed
         |struct Odd
         |    a: u3
         |    u: unit
         |    b: u5
         |var o = Odd(5, (), 20)
         |o.b = 9
         |o match
         |    Odd(x, _, y) -> print(sizeof(Odd), x, y)""".stripMargin

    run(src) shouldBe "1 5 9\n"
  }

  "an invariant sees the fields it relates" in {
    val src =
      """|@packed
         |struct Span
         |    lo: u5
         |    hi: u5
         |    invariant lo <= hi
         |val s = Span(2, 9)
         |print(sizeof(Span), s.lo, s.hi)""".stripMargin

    run(src) shouldBe "2 2 9\n"
  }
}
