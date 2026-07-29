package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The attributes a simple enum exposes: its endpoints (`First`/`Last`), the two ordinal maps
 * (`Pos` a value to its 0-based position, `Val` a position back to a value, trapping out of
 * range), the neighbouring values (`Succ`/`Pred`, trapping at the ends), and the two name maps
 * (`Image` a value to its name, `Value` a name back to a value, trapping on no such name).
 *
 * A simple enum carries no `==`, and does not print on its own, so an enum-valued result is
 * observed by turning it back into a position with `Pos` or a name with `Image` — which is how
 * these attributes are meant to be used in the first place. Tags may be non-contiguous, so the
 * suite pins that down with an enum whose discriminants are spelled out.
 */
class EnumAttrRunTests extends AnyFreeSpec with RunSupport {

  private val Color =
    """|enum Color
       |    Red
       |    Green
       |    Blue
       |""".stripMargin

  // Discriminants that are neither zero-based nor adjacent: position and value must be looked up,
  // not computed, so `Pos`/`Val`/`Succ`/`Pred` cannot pass by treating a tag as its own index.
  private val Gap =
    """|enum Gap
       |    A = 5
       |    B = 10
       |    C = 20
       |""".stripMargin

  "First and Last name the endpoints" - {
    "First is the first variant" in {
      run(Color + "print(Color::Pos(Color::First))") shouldBe "0\n"
    }
    "Last is the last variant" in {
      run(Color + "print(Color::Image(Color::Last))") shouldBe "Blue\n"
    }
  }

  "Pos reports a value's 0-based position" - {
    "a contiguous enum counts from zero" in {
      run(Color + "print(Color::Pos(Color.Green))") shouldBe "1\n"
    }
    "a gapped enum still counts positions, not discriminants" in {
      run(Gap + "print(Gap::Pos(Gap.B), Gap::Pos(Gap.C))") shouldBe "1 2\n"
    }
  }

  "Val reads the value at a position" - {
    "a contiguous enum" in {
      run(Color + "print(Color::Image(Color::Val(2)))") shouldBe "Blue\n"
    }
    "a gapped enum returns the discriminant, not the position" in {
      run(Gap + "print(Gap::Pos(Gap::Val(2)))") shouldBe "2\n"
    }
    "a position past the last variant traps" in {
      exits(Color + "print(Color::Pos(Color::Val(3)))")
    }
    "a negative position traps" in {
      exits(Color + "print(Color::Pos(Color::Val(-1)))")
    }
  }

  "Succ and Pred step to the neighbouring value" - {
    "Succ moves one along" in {
      run(Color + "print(Color::Pos(Color::Succ(Color.Red)))") shouldBe "1\n"
    }
    "Pred moves one back" in {
      run(Color + "print(Color::Pos(Color::Pred(Color.Blue)))") shouldBe "1\n"
    }
    "Succ steps across a gap" in {
      run(Gap + "print(Gap::Image(Gap::Succ(Gap.A)))") shouldBe "B\n"
    }
    "Succ traps at the last variant" in {
      exits(Color + "print(Color::Pos(Color::Succ(Color.Blue)))")
    }
    "Pred traps at the first variant" in {
      exits(Color + "print(Color::Pos(Color::Pred(Color.Red)))")
    }
  }

  "Image gives a value's name" - {
    "the first variant" in {
      run(Color + "print(Color::Image(Color.Red))") shouldBe "Red\n"
    }
    "a middle variant" in {
      run(Color + "print(Color::Image(Color.Green))") shouldBe "Green\n"
    }
  }

  "Value gives the value a name stands for" - {
    "a known name resolves to its position" in {
      run(Color + "print(Color::Pos(Color::Value(\"Blue\")))") shouldBe "2\n"
    }
    "a known name in a gapped enum resolves to its discriminant" in {
      run(Gap + "print(Gap::Pos(Gap::Value(\"C\")))") shouldBe "2\n"
    }
    "an unknown name traps" in {
      exits(Color + "print(Color::Pos(Color::Value(\"Purple\")))")
    }
  }

  "a single-variant enum has endpoints that meet" in {
    run("enum One\n    Only\nprint(One::Pos(One::First), One::Pos(One::Last))") shouldBe "0 0\n"
  }

  // `09 §2` says `::` is what keeps these out of the member namespace, and a variant named after an
  // attribute is the case that would decide it if they shared one. `Last::First` reads across both
  // spellings at once: the attribute on the left of `::`, the variant on the right of `.`.
  "a variant named after an attribute does not shadow it" in {
    run("enum Edge\n    First\n    Last\nprint(Edge::Pos(Edge::First), Edge::Pos(Edge.Last))") shouldBe "0 1\n"
  }
}
