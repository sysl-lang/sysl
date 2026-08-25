package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What a program can ask a character cursor beyond "give me the next one" (`reference/strings.md §
 * Granularity: bytes and scalar values`).
 *
 * `for c in s.chars` walks a **copy** of the cursor, so a loop cannot ask afterwards where it got
 * to — which is why the guide's JSON reader and its bytecode lexer both index bytes by hand and
 * decode a second time. The members here are what a program driving the cursor itself needs:
 * where it is, what is next without moving to it, how many are left, and the paired walk that
 * reports an offset alongside each character.
 *
 * The offsets are the property worth pinning hardest. A reported offset is a character's **first**
 * byte, so `s[a..b]` built from two of them lands on boundaries by construction — and `s[a..b]`
 * traps on a mid-codepoint bound, so a test that slices at a reported offset is checking the claim
 * rather than describing it.
 */
class CharCursorTests extends AnyFreeSpec with RunSupport {

  private val importing = "import sysl.text.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  /** `aé→𝄞` is one character at each of the four UTF-8 widths, so byte offsets and character
   * counts disagree at every step — which is what makes it discriminating for anything reporting
   * a position.
   */
  private val mixed = "aé→𝄞"

  "a byte says whether it begins a character" - {

    "an ASCII byte and a lead byte both do" in {
      run("""print(is_char_boundary(u8('a')), is_char_boundary(0xC3u8), is_char_boundary(0xF0u8))""") shouldBe
        "true true true\n"
    }

    // The only byte pattern that is not a boundary, and the reason the test is one mask: `10xxxxxx`
    // is the continuation byte and nothing else is.
    "a continuation byte does not" in {
      run("""print(is_char_boundary(0x80u8), is_char_boundary(0xA9u8), is_char_boundary(0xBFu8))""") shouldBe
        "false false false\n"
    }

    // Walked over real text: every byte of the string answers true exactly at a character start,
    // so the count of boundaries is the count of characters.
    "so counting boundaries over a string counts its characters" in {
      run(s"""var n = 0
             |for b in "$mixed".bytes do if is_char_boundary(b) then n += 1
             |print(n, "$mixed".len)""".stripMargin) shouldBe "4 10\n"
    }
  }

  "the cursor says where it is" - {

    // The four widths in order: 1, 2, 3, 4 — so an offset that advanced by a fixed step, or by the
    // character count, disagrees at the first multi-byte character.
    "and the offset advances by each character's own width" in {
      run(s"""var c = "$mixed".chars
             |print(c.offset)
             |c.next()
             |print(c.offset)
             |c.next()
             |print(c.offset)
             |c.next()
             |print(c.offset)
             |c.next()
             |print(c.offset)""".stripMargin) shouldBe "0\n1\n3\n6\n10\n"
    }

    "a fresh cursor over an empty string is already at the end" in {
      run("""var c = "".chars
             |print(c.offset, c.next().is_none())""".stripMargin) shouldBe "0 true\n"
    }
  }

  "peeking does not move the cursor" - {

    "the peeked character is the one the next step yields" in {
      run(s"""var c = "$mixed".chars
             |print(c.peek().unwrap() == c.next().unwrap())""".stripMargin) shouldBe "true\n"
    }

    // The discriminating one: peek twice and the offset has not moved, so the second peek sees the
    // same character. A peek that advanced would show `é` here.
    "peeking twice sees the same character and leaves the offset alone" in {
      run(s"""var c = "$mixed".chars
             |var first = c.peek().unwrap()
             |var second = c.peek().unwrap()
             |print(first, second, c.offset)""".stripMargin) shouldBe "a a 0\n"
    }

    "peeking at the end says there is nothing there" in {
      run("""var c = "x".chars
             |c.next()
             |print(c.peek().is_none())""".stripMargin) shouldBe "true\n"
    }
  }

  "counting what is left" - {

    // The count is characters and the length is bytes, and this string is chosen so they differ.
    "counts characters rather than bytes" in {
      run(s"""var c = "$mixed".chars
             |print(c.count(), "$mixed".len)""".stripMargin) shouldBe "4 10\n"
    }

    // By value, so asking does not consume — a count that took `*self` would leave the cursor at
    // the end and the character after it would be missing.
    "and does not consume the cursor" in {
      run(s"""var c = "$mixed".chars
             |print(c.count())
             |print(c.next().unwrap(), c.count())""".stripMargin) shouldBe "4\na 3\n"
    }

    "counting an exhausted cursor gives zero" in {
      run("""var c = "ab".chars
             |c.next()
             |c.next()
             |print(c.count())""".stripMargin) shouldBe "0\n"
    }
  }

  "the paired walk reports an offset with each character" - {

    // The offsets are 0, 1, 3, 6 — each character's first byte, so they advance by the *previous*
    // character's width rather than by one.
    "each offset is the character's first byte" in {
      run(s"""for p in char_indices("$mixed".bytes)
             |    print(p.0, p.1)""".stripMargin) shouldBe "0 a\n1 é\n3 →\n6 𝄞\n"
    }

    "and it yields exactly as many pairs as there are characters" in {
      run(s"""var n = 0
             |for _ in char_indices("$mixed".bytes) do n += 1
             |print(n)""".stripMargin) shouldBe "4\n"
    }

    // The claim the offsets exist for: a slice taken at a reported offset lands on a boundary.
    // `s[a..b]` traps on a mid-codepoint bound, so this passing *is* the guarantee — an offset
    // scheme reporting the byte after a character would trap here rather than print.
    "so a slice taken at a reported offset lands on a boundary" in {
      run(s"""var s = "$mixed"
             |var at = 0usize
             |for p in char_indices(s.bytes)
             |    if p.1 == '→' then at = p.0
             |print(s[at..], s[..<at])""".stripMargin) shouldBe "→𝄞 aé\n"
    }

    "an empty run yields nothing" in {
      run("""var n = 0
            |for _ in char_indices("".bytes) do n += 1
            |print(n)""".stripMargin) shouldBe "0\n"
    }
  }
}
