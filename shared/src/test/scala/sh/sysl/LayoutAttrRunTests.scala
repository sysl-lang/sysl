package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `@packed` and `@align(n)` — the two layout attributes a struct takes (`15 §1`).
 *
 * They are separate axes and compose: `@packed` removes the padding *between* fields, `@align`
 * raises where the aggregate must *begin*. `sizeof` and `alignof` are how a program observes both,
 * and they are the same numbers the back end lays the aggregate out with, so a test that reads them
 * is testing the emitted layout and not a second opinion about it.
 */
class LayoutAttrRunTests extends AnyFreeSpec with RunSupport {

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
