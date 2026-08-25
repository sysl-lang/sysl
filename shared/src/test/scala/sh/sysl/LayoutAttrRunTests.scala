package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `@packed` and `@align(n)` — the two layout attributes a struct takes (`reference/types.md §
 * Structs`).
 *
 * They are separate axes and compose: `@packed` removes the padding *between* fields, `@align`
 * raises where the aggregate must *begin*. `sizeof` and `alignof` are how a program observes both,
 * and they are the same numbers the back end lays the aggregate out with, so a test that reads them
 * is testing the emitted layout and not a second opinion about it.
 */
class LayoutAttrRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val plain =
    """|struct Head
       |    tag: u8
       |    len: u32
       |""".stripMargin

  private val packed =
    """|@packed
       |struct Head
       |    tag: u8
       |    len: u32
       |""".stripMargin

  "a struct pads by default" - {
    "the gap in front of the wider field is real" in {
      run(plain + "print(sizeof(Head), alignof(Head))") shouldBe "8 4\n"
    }
  }

  "@packed removes the interior padding" - {
    "the fields are laid end to end" in {
      run(packed + "print(sizeof(Head))") shouldBe "5\n"
    }
    "and the aggregate needs no alignment of its own" in {
      run(packed + "print(alignof(Head))") shouldBe "1\n"
    }
    "an array of them has no gap between elements either" in {
      run(packed + "print(sizeof([4]Head))") shouldBe "20\n"
    }
    "the fields still read and write their own values" in {
      run(packed + "var h = Head(7u8, 1000u32)\nh.len += 1u32\nprint(h.tag, h.len)") shouldBe "7 1001\n"
    }
  }

  "@align raises where the aggregate begins" - {
    "alignof reports the boundary asked for" in {
      run("@align(64)\n" + plain + "print(alignof(Head))") shouldBe "64\n"
    }
    "and the size rounds up so an array keeps every element on it" in {
      run("@align(64)\n" + plain + "print(sizeof(Head))") shouldBe "64\n"
    }
    "asking for less than the fields need changes nothing" in {
      run("@align(2)\n" + plain + "print(sizeof(Head), alignof(Head))") shouldBe "8 4\n"
    }
    "the bound may be a constant rather than a literal" in {
      run("const CACHE_LINE: int = 64\n@align(CACHE_LINE)\n" + plain +
        "print(alignof(Head))") shouldBe "64\n"
    }
    "and arithmetic over one" in {
      run("const WORDS: int = 8\n@align(WORDS * 2)\n" + plain + "print(alignof(Head))") shouldBe "16\n"
    }
  }

  "the two compose, because they are different axes" - {
    // A wire header that has to live in a DMA-capable buffer is exactly this shape: no interior
    // gaps, and the whole thing beginning on a boundary the device requires.
    "no interior padding, and still aligned" in {
      run("@packed\n@align(16)\n" + plain + "print(sizeof(Head), alignof(Head))") shouldBe "16 16\n"
    }
    "the order they are written in does not matter" in {
      run("@align(16)\n@packed\n" + plain + "print(sizeof(Head), alignof(Head))") shouldBe "16 16\n"
    }
  }

  /** `@align(n)` above a binding rather than above a type — C's `alignas` rather than Rust's
   * `#[repr(align)]`.
   *
   * The capability was reachable through the type all along, so this is less a second way to align
   * something than a second place to say it: a buffer wrapped in a struct is read as
   * `region.bytes[i]` rather than `region[i]`, so the type form is not free at the use site either.
   *
   * The boundary is asserted from the **IR**, because there is nothing at run time to ask. `alignof`
   * is a question about a type and this is a fact about one object, and an address's low bits are
   * not readable from sysl — so what the back end was told is the whole of the claim.
   */
  "@align(n) marks one binding's storage" - {
    "a local's slot begins on the boundary it asked for" in {
      irMain("@align(64)\nvar buf: [8]u8\nprint(buf[0])") should include("alloca [8 x i8], align 64")
    }
    "a val's does too, the boundary being about storage rather than about writing" in {
      irMain("@align(32)\nval n: int = 7\nprint(n)") should include("alloca i32, align 32")
    }
    "one that asks for nothing is left to the back end" in {
      irMain("var n: int = 7\nprint(n)") should include("alloca i32\n")
    }
    "module storage carries it to the global it lays down" in {
      irOf("m/m.sysl" -> "module m\n\n@align(4096)\nvar table: [4]u64\n",
        "main.sysl" -> "print(m.table[0])\n") should include("align 4096")
    }
    "and so does the 'static' spelling of the same declaration" in {
      ir("@align(128)\nstatic var ticks: u64 = 0u64\nprint(ticks)") should include("align 128")
    }
    "the bound may be a constant, exactly as a struct's may" in {
      irMain("const LINE: int = 64\n@align(LINE)\nvar buf: [8]u8\nprint(buf[0])") should
        include("alloca [8 x i8], align 64")
    }
  }

  "a packed struct still works as a value" - {
    "it passes to a function and comes back" in {
      run(packed + "bump(h: Head) -> Head = Head(h.tag, h.len + 1u32)\n" +
        "print(bump(Head(1u8, 2u32)).len)") shouldBe "3\n"
    }
    // Only the *field's* address is refused. The struct's own is an ordinary pointer to an ordinary
    // aggregate, and reading a field through it goes via the offset the back end knows.
    "and the struct's own address is still an address" in {
      run(packed + "var h = Head(7u8, 2u32)\nval p = &h\nprint((*p).tag)") shouldBe "7\n"
    }
  }
}
