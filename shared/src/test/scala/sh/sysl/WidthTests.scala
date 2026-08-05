package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.text.char_columns` and `sysl.text.columns` — how much room text takes on a terminal.
 *
 * This is the third measurement a string has, and the two that came before it are the reason it is
 * needed: `s.len` counts bytes and a `Chars` walk counts scalar values, and a program laying out a
 * column needs neither. `14 §2` records the finding that provoked it — a `FormatSpec`'s width
 * counts bytes, so a field padded to it is short by one column per non-ASCII character and two
 * cells of one column come out wrong by different amounts.
 *
 * **The tests are about the tables, not about the rule.** The rule is four comparisons and would be
 * right or wrong at a glance; what can quietly be wrong is 499 ranges lifted out of the Unicode
 * Character Database and a binary search over them. So the cases below are chosen where a mistake
 * has somewhere to hide: the exact first and last code point of a range with the ones either side,
 * the blocks whose *unassigned* code points default to wide, and the code points that are in both
 * source tables and must come out of only one.
 *
 * Every value is written as a codepoint number rather than as the character it stands for. Most of
 * the interesting ones — a combining mark, a zero-width space, an unassigned code point in plane 2
 * — either do not print or print as something a reader cannot tell from its neighbour, and a test
 * whose expectation is an invisible byte in the source file is one nobody can safely edit.
 */
class WidthTests extends AnyFreeSpec with RunSupport {

  private def width(src: String): String = run("import sysl.text.{char_columns, columns}\n\n" + src)

  /** How wide each of some code points is, as one line of numbers. */
  private def columnsOf(codepoints: Int*): String =
    width(codepoints.map(cp => f"char_columns(char(0x$cp%04xu32))").mkString("print(", ", ", ")"))

  "one character at a time" - {

    "ASCII is one column, and so is the rest of Latin-1 that prints" in {
      width("""print(char_columns('a'), char_columns('~'), char_columns(char(0x00e9u32)))""") shouldBe
        "1 1 1\n"
    }

    // The property the whole file exists for. U+65E5 and U+672C are the two characters of 日本 —
    // two scalar values, six bytes, and four columns, which is the number neither of the other two
    // measurements gives.
    "an East Asian ideograph is two" in {
      width("""print(char_columns(char(0x65e5u32)), char_columns(char(0x672cu32)))""") shouldBe "2 2\n"
    }

    "a combining mark takes no column of its own" in {
      width("""print(char_columns(char(0x0301u32)), char_columns(char(0x0483u32)))""") shouldBe "0 0\n"
    }

    "and neither does a format character" in {
      width("""print(char_columns(char(0x200bu32)), char_columns(char(0x200eu32)))""") shouldBe "0 0\n"
    }

    // The one code point deliberately taken back out of the zero table: U+00AD is `Cf` by category
    // and every terminal draws it anyway. Asserted beside a `Cf` that is genuinely invisible, so
    // this cannot pass by the whole category having been forgotten.
    "except the soft hyphen, which terminals draw" in {
      width("""print(char_columns(char(0x00adu32)), char_columns(char(0x200bu32)))""") shouldBe "1 0\n"
    }

    "a control character has no column, because a terminal acts on it rather than drawing it" in {
      width("""print(char_columns('\n'), char_columns('\t'), char_columns(char(0u32)))""") shouldBe
        "0 0 0\n"
    }
  }

  /** A binary search that is off by one is off at exactly these code points and nowhere else, so
   * each pair below is the last outside a range and the first inside it, or the reverse.
   */
  "the edges of the ranges" - {

    "the first wide code point, and the one before it" in {
      columnsOf(0x10ff, 0x1100) shouldBe "1 2\n"
    }

    "the last of that range, and the one after" in {
      columnsOf(0x115f, 0x1160) shouldBe "2 1\n"
    }

    // The fullwidth forms end at U+FF60 and the *halfwidth* ones begin at U+FF61 — adjacent code
    // points in one block with opposite answers, which a range written a character wide either way
    // gets wrong.
    "fullwidth forms stop where the halfwidth ones start" in {
      columnsOf(0xff00, 0xff01, 0xff60, 0xff61) shouldBe "1 2 2 1\n"
    }

    "and the currency forms at the end of the block" in {
      columnsOf(0xffe0, 0xffe6, 0xffe7) shouldBe "2 2 1\n"
    }
  }

  /** `EastAsianWidth.txt` lists no line for these; its own header says the *unassigned* code points
   * of these blocks default to `W`. A table built only from the lines in the file measures them as
   * one column while a terminal draws them as two.
   */
  "the blocks whose unassigned code points default to wide" - {

    "the whole of plane 2, to its last code point" in {
      columnsOf(0x20000, 0x2fffd, 0x2fffe) shouldBe "2 2 1\n"
    }

    "and plane 3" in {
      columnsOf(0x30000, 0x3fffd, 0x40000) shouldBe "2 2 1\n"
    }
  }

  /** U+302A..U+302D are nonspacing marks *and* East Asian wide. They are in both source files, and
   * a table that took the width property without removing the marks would call them two columns —
   * so the range that would otherwise have started at U+302A starts at U+302E instead.
   */
  "a code point in both source tables is a mark, not a wide character" in {
    columnsOf(0x3029, 0x302a, 0x302d, 0x302e) shouldBe "2 0 0 2\n"
  }

  "a run of text" - {

    "is the sum of what its characters take" in {
      width("""print(columns("hello".bytes), columns("".bytes))""") shouldBe "5 0\n"
    }

    // The three measurements of one string, side by side, which is the whole argument for this
    // file: `naïveté` is nine bytes, seven characters and seven columns, so a field padded to its
    // byte length overshoots by two.
    "differs from the byte count wherever the text is not ASCII" in {
      width("""val s = "na" + str(char(0x00efu32)) + "vet" + str(char(0x00e9u32))
              |print(s.bytes.len, columns(s.bytes))""".stripMargin) shouldBe "9 7\n"
    }

    // And differs from the *character* count too, which is the measurement a program reaches for
    // second and is still wrong: two characters, four columns.
    "and from the character count wherever it is East Asian" in {
      width("""val s = str(char(0x65e5u32)) + str(char(0x672cu32))
              |print(s.bytes.len, s.chars.count(), columns(s.bytes))""".stripMargin) shouldBe "6 2 4\n"
    }

    // A decomposed vowel is the case that makes counting characters wrong in *Latin* text, which
    // is the script a program is most likely to have assumed safe: the same word precomposed and
    // decomposed is four columns either way and a different number of characters.
    "a decomposed vowel adds a character and no column" in {
      width("""val nfc = "caf" + str(char(0x00e9u32))
              |val nfd = "cafe" + str(char(0x0301u32))
              |print(nfc.chars.count(), columns(nfc.bytes), nfd.chars.count(), columns(nfd.bytes))""".stripMargin) shouldBe
        "4 4 5 4\n"
    }

    "and mixed scripts add up the way the borders fall" in {
      width("""val s = "a" + str(char(0x65e5u32)) + "b" + str(char(0x0301u32))
              |print(columns(s.bytes))""".stripMargin) shouldBe "4\n"
    }
  }
}
