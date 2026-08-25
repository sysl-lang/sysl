package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.text.Ascii` — classifying a byte or a character over the ASCII range.
 *
 * The trait exists so that one spelling covers both types, which a language with no overloading
 * cannot get from free functions. So the tests that matter most are the ones run twice: the same
 * three words against a `u8` and against a `char`, answering the same way.
 *
 * The other thing under test is the range itself. Everything here is named for ASCII and answers
 * `false` outside it, so a non-ASCII character passing through `to_upper` unchanged is not an
 * incidental property — it is the guarantee that lets a program map one over arbitrary text.
 */
class AsciiTests extends AnyFreeSpec with RunSupport {

  /** The trait has to be in scope for its members to be reached (`reference/modules.md §
   * Visibility`), so every program here asks for it by name.
   */
  private def ascii(src: String): String = run("import sysl.text.Ascii\n\n" + src)

  "classification" - {

    "a digit is a digit and a letter is not" in {
      ascii("""print(u8('5').is_digit(), u8('a').is_digit())""") shouldBe "true false\n"
    }

    // The discriminating case, and the reason this member exists: a hand-rolled whitespace test
    // stops after space and tab, which is exactly what `examples/wc.sysl` did. All six, or the
    // member is no better than the two comparisons it replaces.
    "whitespace is all six of C's, not the two that are easy to remember" in {
      ascii("""var ws: [6]u8 = [32u8, 9u8, 10u8, 11u8, 12u8, 13u8]
              |var n = 0
              |for b in ws do if b.is_space() then n += 1
              |print(n, u8('x').is_space())""".stripMargin) shouldBe "6 false\n"
    }

    "a letter is alpha, a digit is not, and both are alnum" in {
      ascii("""print(u8('q').is_alpha(), u8('7').is_alpha(), u8('7').is_alnum())""") shouldBe
        "true false true\n"
    }

    "case is distinguished in both directions" in {
      ascii("""print(u8('Q').is_upper(), u8('Q').is_lower(), u8('q').is_lower())""") shouldBe
        "true false true\n"
    }

    // Both letter cases count, and the letter just past `f` does not — which is the boundary a
    // range written `>= 'a' && <= 'z'` gets wrong.
    "hex digits run to f in either case and stop there" in {
      ascii("""print(u8('9').is_hex_digit(), u8('F').is_hex_digit(),
              |      u8('f').is_hex_digit(), u8('g').is_hex_digit())""".stripMargin) shouldBe
        "true true true false\n"
    }

    "punctuation is the four runs between the named groups" in {
      ascii("""print(u8('!').is_punct(), u8('@').is_punct(), u8('_').is_punct(),
              |      u8('~').is_punct(), u8('a').is_punct(), u8(' ').is_punct())""".stripMargin) shouldBe
        "true true true true false false\n"
    }

    // The space is printable and the delete is not, which are the two ends people disagree about.
    "printability includes the space and excludes the delete" in {
      ascii("""print(u8(' ').is_print(), u8('~').is_print(), u8(127u32).is_print())""") shouldBe
        "true true false\n"
    }

    "controls are the C0 range and the delete, which is not below the space" in {
      ascii("""print(u8(0u32).is_control(), u8(31u32).is_control(),
              |      u8(127u32).is_control(), u8(32u32).is_control())""".stripMargin) shouldBe
        "true true true false\n"
    }

    // The discriminating character is the newline: it is whitespace and it is not blank, which is
    // the whole difference between the two questions and the one a field splitter gets wrong by
    // reaching for `is_space`.
    "blank is the two within-a-line separators, and a newline is not one" in {
      ascii("""print(u8(' ').is_blank(), u8(9u32).is_blank(),
              |      u8(10u32).is_blank(), u8(10u32).is_space(), u8('x').is_blank())""".stripMargin) shouldBe
        "true true false true false\n"
    }

    // `is_graph` is `is_print` minus the space, so the space is where the two part company.
    "graph is print without the space" in {
      ascii("""print(u8(' ').is_graph(), u8(' ').is_print(), u8('!').is_graph(),
              |      u8('~').is_graph(), u8(127u32).is_graph())""".stripMargin) shouldBe
        "false true true true false\n"
    }
  }

  "the range the trait speaks for" - {

    "a byte past 127 is not ASCII and answers false to everything" in {
      ascii("""var b = 200u8
              |print(b.is_ascii(), b.is_alpha(), b.is_print(), b.is_control())""".stripMargin) shouldBe
        "false false false false\n"
    }

    // `é` is a letter to a reader and not one to this trait, and saying so is the point: `is_alpha`
    // answering `false` means "not a letter I can see", which `is_ascii` is how a caller finds out.
    "a non-ASCII character says so rather than being guessed at" in {
      ascii("""print('é'.is_ascii(), 'é'.is_alpha())""") shouldBe "false false\n"
    }
  }

  "case conversion" - {

    "converts letters and leaves everything else alone" in {
      ascii("""print(u8('a').to_upper(), u8('Z').to_lower(), u8('5').to_upper(), u8('!').to_lower())""") shouldBe
        "65 122 53 33\n"
    }

    // The discriminating one for the arithmetic: the case bit is `0x20`, and clearing it as a mask
    // would turn `?` (`0x3F`) into `` and `_` (`0x5F`) into `?`. The guard is what stops that.
    "punctuation that shares the case bit is untouched" in {
      ascii("""print(u8('?').to_upper(), u8('_').to_lower())""") shouldBe "63 95\n"
    }

    // What makes mapping this over arbitrary text safe: a character outside the range comes back
    // as itself rather than being shifted into a different one.
    "a non-ASCII character passes through both conversions unchanged" in {
      ascii("""var c = 'é'
              |print(c.to_upper() == c, c.to_lower() == c)""".stripMargin) shouldBe "true true\n"
    }
  }

  "one spelling over both types" - {

    // The whole reason this is a trait: the same three words, the same answers, whichever type is
    // in hand. Free functions could not have this and would have needed a suffix on one of them.
    "a byte and a character answer identically" in {
      ascii("""print(u8('a').is_alpha(), 'a'.is_alpha())
              |print(u8('a').is_digit(), 'a'.is_digit())
              |print(u8('a').to_upper() == u8('A'), 'a'.to_upper() == 'A')""".stripMargin) shouldBe
        "true true\nfalse false\ntrue true\n"
    }
  }

  "digit_value" - {

    "reads a decimal digit as its value rather than its code" in {
      ascii("""print(u8('7').digit_value(10).unwrap())""") shouldBe "7\n"
    }

    "counts letters from ten, in either case" in {
      ascii("""print(u8('a').digit_value(16).unwrap(), u8('F').digit_value(16).unwrap())""") shouldBe
        "10 15\n"
    }

    "goes all the way to base 36" in {
      ascii("""print(u8('z').digit_value(36).unwrap())""") shouldBe "35\n"
    }

    // The check that makes this more than a character test: `8` is a digit, and it is not a digit
    // *in base 8*. A classifier that answered on shape alone would accept it.
    "refuses a digit that is out of range for the base" in {
      ascii("""print(u8('8').digit_value(8).is_none(), u8('7').digit_value(8).is_some())""") shouldBe
        "true true\n"
    }

    "refuses something that is not a digit at all" in {
      ascii("""print(u8('!').digit_value(16).is_none())""") shouldBe "true\n"
    }

    // The error path for the caller's mistake rather than the input's: a base nothing could be a
    // digit in answers `None` instead of trapping, so `sysl.strconv` can report it in its own words.
    "answers None for a base outside 2..36 rather than trapping" in {
      ascii("""print(u8('1').digit_value(1).is_none(), u8('1').digit_value(37).is_none())""") shouldBe
        "true true\n"
    }
  }
}
