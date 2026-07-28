package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 edge cases for the hand-rolled UTF-8 handling: the runtime encoder at each byte-length
 * boundary, substring cuts that must land exactly on a character boundary (and trap inside one),
 * byte indexing across a multi-byte character, and byte-order comparison — the places an
 * off-by-one in the byte math would show.
 */
class StringEdgeRunTests extends AnyFreeSpec with RunSupport {

  // The encoder branches at 128 / 2048 / 65536; these codepoints sit just under and just over
  // each, so a wrong threshold or continuation byte would change the output. The printed UTF-8's
  // byte total pins each char's length, and seven codepoints coming back (not a mangled
  // replacement char) confirms every one round-tripped intact.
  "a char encodes as UTF-8 at each byte-length boundary" in {
    val out = run("print('\\u{7F}', '\\u{80}', '\\u{7FF}', '\\u{800}', '\\u{FFFF}', '\\u{10000}', '\\u{10FFFF}')")

    out.getBytes("UTF-8").length shouldBe 26   // 1+2+2+3+3+4+4 char bytes, six spaces, a newline
    out.codePointCount(0, out.length) shouldBe 14 // 7 chars + 6 spaces + newline
  }

  // "a€😀b" is 1 + 3 + 4 + 1 = 9 bytes, with character boundaries at byte offsets 0, 1, 4, 8, 9.
  "substrings cut only at character boundaries, keeping byte lengths" in {
    val src =
      """var s = "a€😀b"
        |print(s.len, s[0..<1].len, s[1..<4].len, s[4..<8].len, s[8..<9].len)
        |print(s[1..<4] == "€", s[4..<8] == "😀", s[1..<8].len)""".stripMargin

    run(src) shouldBe "9 1 3 4 1\ntrue true 7\n"
  }

  // A cut of no width, which is what a program computing both ends out of an answer it just
  // worked out will hand over sooner or later — a span between two events that turned out to be
  // the same event. It is a string, it is empty, and it is not a trap.
  "a substring of no width is the empty string" in {
    val src =
      """var s = "abcdef"
        |print(s[2..<2].len, s[2..<2] == "", s[0..<0] == "", s[6..<6] == "")""".stripMargin

    run(src) shouldBe "0 true true true\n"
  }

  "byte indexing reaches each byte of a multi-byte character" in {
    val src =
      """var s = "a€b"
        |print(s[0], s[1], s[2], s[3], s[4])""".stripMargin

    // a=97, € = e2 82 ac = 226 130 172, b=98
    run(src) shouldBe "97 226 130 172 98\n"
  }

  "a substring cut one byte inside a multi-byte character traps" in {
    exits(
      """var s = "a€😀b"
        |print(s[0..<2].len)""".stripMargin
    )
  }

  "a substring starting inside a multi-byte character traps" in {
    exits(
      """var s = "a€😀b"
        |print(s[5..<8].len)""".stripMargin
    )
  }

  // € (U+20AC) begins with byte 0xE2 = 226, above 'b' (0x62 = 98), so byte order agrees with
  // codepoint order; the comparison must give both a true and a false.
  "strings order by byte, which is codepoint order for UTF-8" in {
    run("""print("a" < "€", "€" < "b", "€" == "€")""") shouldBe "true false true\n"
  }
}
