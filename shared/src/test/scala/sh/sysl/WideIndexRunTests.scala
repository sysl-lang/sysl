package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** An index **wider than an address**, which `07 § Indexing` admits and the compiler used to refuse.
 *
 * The chapter's rule is that the index may be any integer type, and its reason is the case that
 * broke: *"requiring `usize` would make `for i in 0..<10 do a[i] …` need a conversion for no
 * benefit, since the check has to happen anyway."*
 *
 * The refusal it replaced was not arbitrary — truncating an index to the address width and then
 * checking the truncation is worse than not checking at all, because `2^64 + 5` arrives as 5 and
 * passes on a six-element array. What was wrong was the conclusion. The answer is to ask whether it
 * fits **before** narrowing it, where the value is still all there: no storage holds more than
 * `usize` elements, so an index that does not fit names nothing, which makes it an ordinary
 * out-of-bounds index rather than a program to decline.
 *
 * **It stopped being exotic when a target arrived whose address is narrower than an `int`.** At 64
 * bits the only index this reaches is a `u128`, which is why refusing cost nothing for so long; on
 * CRAFT, whose address space is 64 KiB, `int` is wider than `usize` and the standard library stopped
 * compiling in twelve places. Those cannot be run here — there is no craft machine to run them on —
 * so what this file exercises is the same rule at the width the host has.
 */
class WideIndexRunTests extends AnyFreeSpec with RunSupport {

  "an index wider than an address reads the element it names" in {
    val src =
      """var a = [2, 3, 5, 7]
        |var i: u128 = 2
        |print(a[i])""".stripMargin

    run(src) shouldBe "5\n"
  }

  // The sharp one. Truncated to 64 bits this is 0, so a compiler that narrowed first and checked
  // after would print `2` — checked, and wrong, which is worse than unchecked.
  "an index past what an address can name traps rather than wrapping to a valid one" in {
    exits(
      """var a = [2, 3, 5, 7]
        |var i: u128 = 18446744073709551616
        |print(a[i])""".stripMargin
    )
  }

  "a slice bound is held to the same rule" in {
    val src =
      """var a = [2, 3, 5, 7]
        |var hi: u128 = 3
        |print(a[0..<hi].len)""".stripMargin

    run(src) shouldBe "3\n"
  }

  "a slice bound past what an address can name traps" in {
    exits(
      """var a = [2, 3, 5, 7]
        |var hi: u128 = 18446744073709551620
        |print(a[0..<hi].len)""".stripMargin
    )
  }

  // A repeat count reaches the same narrowing, so it gets the same answer rather than a rule of its
  // own: a count that does not fit would size the storage by a number nobody wrote.
  "a repeat count wider than an address builds the sequence it names" in {
    val src =
      """var n: u128 = 3
        |var xs: []int = [0; n]
        |print(xs.len)""".stripMargin

    run(src) shouldBe "3\n"
  }

  "a repeat count past what an address can name traps" in {
    exits(
      """var n: u128 = 18446744073709551616
        |var xs: []int = [0; n]
        |print(xs.len)""".stripMargin
    )
  }

  // A negative index has always failed the unsigned bounds test by arriving as a very large value.
  // It has to keep failing when it also has to be narrowed, and by the same route rather than by
  // luck: read unsigned at its own width, `-1` is above everything an address can name.
  "a negative index of a wider type still fails, and at the same test" in {
    exits(
      """var a = [2, 3, 5, 7]
        |var i: i128 = -1
        |print(a[i])""".stripMargin
    )
  }
}
