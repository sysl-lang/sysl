package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of the checked conversions between a simple enum and an integer
 * (`09 §2`): enum → integer is total, `Color(n)` is a checked cast that traps on an undeclared
 * discriminant, and `Color.try(n)` is the fallible constructor returning an `Option`. The `: iN`
 * underlying annotation that pins storage and governs each discriminant's range is exercised
 * alongside, since the conversions are defined in terms of it.
 */
class EnumConversionRunTests extends AnyFreeSpec with RunSupport {

  private val color =
    """enum Color: u8
      |    Red
      |    Green
      |    Blue = 10
      |    Yellow
      |""".stripMargin

  /** `16 §1` makes a **transparent** subtype *the same type as* its base, with values flowing
   * between them freely, and `§2` makes a `new` derivation a distinct type that does not. The
   * conversion asked the written type whether it was an integer, so it refused the first along with
   * the second — found by the audit that follows a merge, not by anything failing.
   */
  "the integer a checked cast takes may be a transparent subtype, which is its base" in {
    val src = color +
      """type Small = int within 0..2
        |var s: Small = 1
        |print(int(Color(s)))""".stripMargin

    run(src) shouldBe "1\n"
  }

  "and the fallible constructor takes one too, sharing the same gate" in {
    val src = color +
      """type Small = int within 0..2
        |var s: Small = 1
        |Color.try(s) match
        |    Some(c) -> print(int(c))
        |    None -> print("none")""".stripMargin

    run(src) shouldBe "1\n"
  }

  "while a 'new' derivation is a type of its own, and says so" in {
    val src = color +
      """type Tag = new int
        |var t = Tag(1)
        |print(int(Color(t)))""".stripMargin

    a[Exception] should be thrownBy run(src)
  }

  "enum to integer is total, each variant yielding its discriminant" in {
    val src = color +
      """print(int(Red), int(Green), int(Blue), int(Yellow))""".stripMargin
    run(src) shouldBe "0 1 10 11\n"
  }

  "enum to integer honours an explicit discriminant that jumps and then resumes" in {
    // Blue = 10 sets an explicit value; Yellow continues from it at 11, exactly C's rule.
    val src = color +
      """var y = Yellow
        |print(int(y))""".stripMargin
    run(src) shouldBe "11\n"
  }

  "enum converts to a wider integer than its underlying type" in {
    // The underlying type is u8, but int(c) targets i32 — the value widens, staying total.
    val src = color +
      """var b = Blue
        |var n = int(b)
        |print(n + 5)""".stripMargin
    run(src) shouldBe "15\n"
  }

  "a checked cast from a declared discriminant yields that variant" in {
    val src = color +
      """var c = Color(10)
        |print(int(c))""".stripMargin
    run(src) shouldBe "10\n"
  }

  "a checked cast round-trips every discriminant, including the boundary auto-value" in {
    val src = color +
      """var vals = [0, 1, 10, 11]
        |var i = 0
        |while i < 4
        |    print(int(Color(vals[i])))
        |    i++""".stripMargin
    run(src) shouldBe "0\n1\n10\n11\n"
  }

  "a checked cast from an undeclared value traps" in {
    // 5 falls in the gap between Green (1) and Blue (10); it is a valid u8 but not a discriminant.
    exits(color + """var c = Color(5)
                    |print(int(c))""".stripMargin)
  }

  "a checked cast from a value past the last discriminant traps" in {
    exits(color + """var c = Color(200)
                    |print(int(c))""".stripMargin)
  }

  "the fallible constructor returns Some for a declared discriminant" in {
    val src = color +
      """Color.try(11) match
        |    Some(c) -> print(int(c))
        |    None -> print(-1)""".stripMargin
    run(src) shouldBe "11\n"
  }

  "the fallible constructor returns None for an undeclared value" in {
    val src = color +
      """Color.try(7) match
        |    Some(c) -> print(int(c))
        |    None -> print(-1)""".stripMargin
    run(src) shouldBe "-1\n"
  }

  "the fallible constructor discriminates across the whole u8 range" in {
    // Every value 0..12 is tried; only the four declared discriminants round-trip, the rest miss.
    val src = color +
      """decode(n: u8) -> int
        |    Color.try(n) match
        |        Some(c) -> int(c)
        |        None -> -1
        |var i: u8 = 0
        |while i <= 12
        |    print(decode(i))
        |    i++""".stripMargin
    run(src) shouldBe "0\n1\n-1\n-1\n-1\n-1\n-1\n-1\n-1\n-1\n10\n11\n-1\n"
  }

  "an unannotated simple enum defaults to i32 and still converts both ways" in {
    val src =
      """enum Dir
        |    North
        |    East
        |    South
        |    West
        |var d = Dir(2)
        |print(int(d))
        |Dir.try(9) match
        |    Some(x) -> print(int(x))
        |    None -> print(-1)""".stripMargin
    run(src) shouldBe "2\n-1\n"
  }

  "a signed underlying type carries a negative discriminant through both conversions" in {
    val src =
      """enum Trend: i8
        |    Down = -1
        |    Flat = 0
        |    Up = 1
        |print(int(Down))
        |var t = Trend(-1)
        |print(int(t))
        |Trend.try(5) match
        |    Some(x) -> print(int(x))
        |    None -> print(99)""".stripMargin
    run(src) shouldBe "-1\n-1\n99\n"
  }
}
