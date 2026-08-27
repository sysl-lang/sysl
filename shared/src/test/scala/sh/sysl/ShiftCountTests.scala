package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A shift's right operand is a **count**, not a value being combined with the left, so it may be
 * any integer type and the result is the shifted value's own
 * (`reference/expressions.md § A shift takes a count`).
 *
 * What makes this worth a file of its own is the conversion underneath it. LLVM's `shl`, `lshr` and
 * `ashr` each take two operands of one type, so the count is brought to the shifted width by
 * `ScalarEmitter.shiftAmount` — and the order that conversion happens in is load-bearing. A count
 * wider than the value is clamped at the value's width **before** it is truncated; truncating first
 * would turn 256 into 0 and answer `x` where every other over-shift answers zero. Every case below
 * that shifts by more than the width is there to pin that.
 */
class ShiftCountTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "a count narrower than the value is extended" - {
    "an unsigned one, which is the shape a length arrives in" in {
      run("""var x: u32 = 1
            |var n: u8 = 5
            |print(x << n, (x << n) >> n)""".stripMargin) shouldBe "32 1\n"
    }

    "a signed one" in {
      run("""var x: long = 1
            |var n: i16 = 40
            |print(x << n)""".stripMargin) shouldBe "1099511627776\n"
    }
  }

  "a count wider than the value is clamped before it is truncated" - {
    // 256 truncated to a `u8` is 0, so a conversion done in the wrong order would print `1` — the
    // value unshifted — rather than the zero every over-shift answers.
    "so 256 places on a byte is a full shift and not a shift by nothing" in {
      run("""var x: u8 = 1
            |var n: u32 = 256
            |print(x << n)""".stripMargin) shouldBe "0\n"
    }

    "and the same holds of the right shift" in {
      run("""var x: u8 = 0xFF
            |var n: u32 = 256
            |print(x >> n)""".stripMargin) shouldBe "0\n"
    }

    // The clamp is an unsigned comparison, so a negative count reads as an enormous one and shifts
    // all the way — which is the same answer `boundedShift` already gives a negative count of the
    // value's own width, and is what keeps the two spellings agreeing.
    "a negative count shifts all the way, exactly as one of the value's own width does" in {
      run("""var x: u8 = 0xFF
            |var wide: i32 = -1
            |var same: i8 = -1
            |print(x << wide, x << same)""".stripMargin) shouldBe "0 0\n"
    }

    // An arithmetic right shift past the width is every bit of the sign, which is the rule
    // `boundedShift` documents. The clamp must not disturb it.
    "a signed value shifted right past its width is its sign" in {
      run("""var x: i8 = -1
            |var n: u64 = 1000
            |print(x >> n)""".stripMargin) shouldBe "-1\n"
    }
  }

  "the result is the shifted value's type, never the count's" - {
    "so a byte shifted by a 64-bit count is still a byte, and wraps as one" in {
      run("""var x: u8 = 0x81
            |var n: u64 = 1
            |print(x << n)""".stripMargin) shouldBe "2\n"
    }

    "which the emitted instruction says outright" in {
      irMain("""var x: u8 = 1
               |var n: u64 = 3
               |print(x << n)""".stripMargin) should include("shl i8")
    }
  }

  "a compound shift computes what the long form computes" - {
    "with a wider count" in {
      run("""var x: u8 = 1
            |var n: u32 = 256
            |x <<= n
            |print(x)""".stripMargin) shouldBe "0\n"
    }

    "and with a narrower one" in {
      run("""var x: u32 = 1
            |var n: u8 = 5
            |x <<= n
            |x >>= n
            |print(x)""".stripMargin) shouldBe "1\n"
    }
  }

  "the count must still be an integer" - {
    "a float is refused, and the message names it as a count" in {
      err("""var x: u8 = 1
            |print(x << 1.5)""".stripMargin) should
        include("'<<' shifts by a count, and real is not an integer")
    }

    "so is a bool" in {
      err("""var x: u8 = 1
            |print(x >> true)""".stripMargin) should
        include("'>>' shifts by a count, and bool is not an integer")
    }

    "and the ordinary same-type rule is untouched for everything else" in {
      err("""var a: u8 = 1
            |var b: u16 = 2
            |print(a | b)""".stripMargin) should
        include("'|' needs matching types, got byte and ushort")
    }
  }

  /** A `within` type's left shift is the **checked** one — it traps rather than wrapping, on the
    * ranged type's own terms — and the count is brought to width *before* that check rather than
    * after, so the two spellings of one shift agree.
    */
  "a ranged receiver takes a foreign count on the same terms" - {

    "and the checked shift computes what the plain one computes" in {
      run("type Slot = new u8 within 0..<200\n\n" +
        "var s: Slot = Slot(3)\n" +
        "var narrow: u8 = 4\n" +
        "var wide: u32 = 4\n\n" +
        "print(int(s << narrow), int(s << wide))") shouldBe "48 48\n"
    }

    // The clamp happens before `checkedShl`'s own comparison against the width, so a wide count whose
    // result leaves the range still trips the range check rather than slipping past it.
    "while a shift that leaves the range still traps" in {
      exits("type Slot = new u8 within 0..<200\n\n" +
        "var s: Slot = Slot(3)\n" +
        "var wide: u32 = 7\n\n" +
        "print(int(s << wide))")
    }
  }

  /** A vector's count is lane-wise and is already the same register type, so nothing is relaxed
    * there — which is why the analyzer's exception is written against a scalar integer. A scalar
    * count still reaches a vector, by the splat every mixed vector operand takes, and that is a
    * different mechanism from this one.
    */
  "a vector shift is untouched: its lanes must still agree" in {
    err("""val a: <4>u32 = [1, 2, 3, 4]
          |val b: <4>u8 = [1, 2, 3, 4]
          |f() -> unit
          |    val c = a << b
          |""".stripMargin) should include("needs matching types")
  }
}
