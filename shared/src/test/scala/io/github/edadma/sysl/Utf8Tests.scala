package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `from_utf8` and the primitive under it (`04 § Validity`).
 *
 * Two guide programs paid for the absence of this in different currencies — `guide/json` in
 * workarounds, reaching text for a `\uXXXX` only through `str(char(n))`, and `guide/shapes` in
 * design, since the allocation-free way to render is into a `*Writer` and the bytes could not come
 * back out as a `string`.
 *
 * **The tests are the Unicode well-formedness table, row by row** (Table 3-7), because that is what
 * the validator is. A decoder that computes a codepoint and then range-checks it passes the obvious
 * cases and fails here: the rows are what make an overlong encoding, a surrogate, and a value past
 * `10FFFF` illegal at the *second byte* rather than after the sequence is assembled, so every
 * boundary below is a byte pair the naive implementation accepts.
 *
 * The other property under test is the one distinction `Utf8Error` carries beyond the offset `04`
 * asks for: whether the input merely **ended** in the middle of a sequence, which more bytes would
 * fix, or holds one no continuation could rescue. `E0 80` and `E0 A0` are both two bytes of a
 * three-byte sequence and only the second is worth waiting on.
 *
 * Expected text is built from codepoint numbers rather than written as characters. Most of the
 * interesting ones — U+0000, U+007F, U+FFFF, U+10FFFF — do not print, and a test whose expectation
 * is an invisible byte in the source file is one nobody can read or safely edit.
 */
class Utf8Tests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private def literal(hex: String): String =
    if hex.isEmpty then "[]" else hex.split(" ").map(b => s"0x${b}u8").mkString("[", ", ", "]")

  /** What `from_utf8` makes of a run of bytes: the text it decoded to, or the failure as `!offset`
   * for a sequence that is wrong and `~offset` for one that is merely unfinished.
   */
  private def decode(hex: String): String =
    run(
      s"""var b: []u8 = ${literal(hex)}
         |var r = from_utf8(b)
         |if r.is_ok() then
         |    print("ok:" + r.unwrap())
         |else
         |    var e = r.unwrap_err()
         |    print((if e.truncated then "~" else "!") + str(e.offset))""".stripMargin,
    ).stripSuffix("\n")

  /** The success form, so an expectation reads as the codepoints it is about. */
  private def ok(cps: Int*): String = "ok:" + cps.map(c => new String(Character.toChars(c))).mkString

  "well-formed bytes become a string" - {
    "ascii" in {
      decode("68 65 6C 6C 6F") shouldBe "ok:hello"
    }

    "two, three, and four byte sequences" in {
      decode("C3 A9") shouldBe ok(0xe9)
      decode("E2 82 AC") shouldBe ok(0x20ac)
      decode("F0 9D 84 9E") shouldBe ok(0x1d11e)
    }

    "nothing at all is a string too" in {
      decode("") shouldBe "ok:"
    }

    // A `string` carries its length, so a NUL is an ordinary byte in it — the one place this
    // differs from every C route to the same bytes.
    "an interior NUL is a byte like any other" in {
      run(
        """var b: []u8 = [0x61u8, 0x00u8, 0x62u8]
          |print(from_utf8(b).unwrap().len)""".stripMargin,
      ) shouldBe "3\n"
    }

    "the widths mixed together" in {
      decode("41 C3 A9 E2 82 AC F0 9D 84 9E 5A") shouldBe ok(0x41, 0xe9, 0x20ac, 0x1d11e, 0x5a)
    }

    // The first and last codepoint each width encodes. A validator built the other way — decode,
    // then check the number — gets all eight of these right and still fails the pairs below.
    "the first and last codepoint of every width" in {
      decode("00") shouldBe ok(0x0)
      decode("7F") shouldBe ok(0x7f)
      decode("C2 80") shouldBe ok(0x80)
      decode("DF BF") shouldBe ok(0x7ff)
      decode("E0 A0 80") shouldBe ok(0x800)
      decode("EF BF BF") shouldBe ok(0xffff)
      decode("F0 90 80 80") shouldBe ok(0x10000)
      decode("F4 8F BF BF") shouldBe ok(0x10ffff)
    }

    "both sides of the surrogate hole" in {
      decode("ED 9F BF") shouldBe ok(0xd7ff)
      decode("EE 80 80") shouldBe ok(0xe000)
    }
  }

  "a byte that can never appear anywhere" - {
    // `C0` and `C1` could only ever begin an overlong two-byte form, so they are illegal as bytes
    // rather than as sequences — nothing that follows could make them well-formed.
    "C0 and C1 are not lead bytes" in {
      decode("C0 80") shouldBe "!0"
      decode("C1 BF") shouldBe "!0"
    }

    "F5 through FF are past the last codepoint" in {
      decode("F5 80 80 80") shouldBe "!0"
      decode("F8 80") shouldBe "!0"
      decode("FE") shouldBe "!0"
      decode("FF") shouldBe "!0"
    }

    "a continuation byte with no lead" in {
      decode("80") shouldBe "!0"
      decode("BF") shouldBe "!0"
      decode("41 80") shouldBe "!1"
    }
  }

  /** The four rows where the *second* byte is what makes the sequence legal, and the reason the
   * validator is a table. Each pair here is one byte on either side of a boundary, so a
   * continuation check of plain `80..BF` accepts exactly the wrong half of every one.
   */
  "the second byte decides, and the boundaries are exact" - {
    "E0 rules out the overlong three-byte forms" in {
      decode("E0 9F BF") shouldBe "!0"
      decode("E0 A0 80") shouldBe ok(0x800)
    }

    "ED rules out the surrogates" in {
      decode("ED 9F BF") shouldBe ok(0xd7ff)
      decode("ED A0 80") shouldBe "!0"
      decode("ED BF BF") shouldBe "!0"
    }

    "F0 rules out the overlong four-byte forms" in {
      decode("F0 8F BF BF") shouldBe "!0"
      decode("F0 90 80 80") shouldBe ok(0x10000)
    }

    "F4 rules out everything past 10FFFF" in {
      decode("F4 8F BF BF") shouldBe ok(0x10ffff)
      decode("F4 90 80 80") shouldBe "!0"
    }

    "and the ordinary rows still take the full range" in {
      decode("E1 80 80") shouldBe ok(0x1000)
      decode("EC BF BF") shouldBe ok(0xcfff)
      decode("F1 80 80 80") shouldBe ok(0x40000)
      decode("F3 BF BF BF") shouldBe ok(0xfffff)
    }
  }

  "a continuation that is not one" - {
    "in each position of each width" in {
      decode("C3 28") shouldBe "!0"
      decode("E2 28 AC") shouldBe "!0"
      decode("E2 82 28") shouldBe "!0"
      decode("F0 9D 84 28") shouldBe "!0"
    }

    // A second lead byte where a continuation was expected is the common real-world case — two
    // sequences concatenated with a byte lost between them.
    "a lead byte where a continuation belongs" in {
      decode("E2 C3 A9") shouldBe "!0"
      decode("F0 9D C3 A9") shouldBe "!0"
    }
  }

  "the input that merely ran out" - {
    "one byte short, at each width" in {
      decode("C3") shouldBe "~0"
      decode("E2 82") shouldBe "~0"
      decode("F0 9D 84") shouldBe "~0"
    }

    "a lead byte and nothing else" in {
      decode("E2") shouldBe "~0"
      decode("F0") shouldBe "~0"
    }

    "after text that was fine" in {
      decode("68 69 E2 82") shouldBe "~2"
    }
  }

  /** The distinction the second field exists for. Both inputs below are two bytes of a three-byte
   * sequence, and a caller reading from a stream should wait for more on one and give up on the
   * other — which is why "unfinished" cannot just be "invalid at the last byte".
   */
  "unfinished is told from unfixable" - {
    "E0 80 is wrong; E0 A0 is unfinished" in {
      decode("E0 80") shouldBe "!0"
      decode("E0 A0") shouldBe "~0"
    }

    "F4 90 is wrong; F4 8F is unfinished" in {
      decode("F4 90") shouldBe "!0"
      decode("F4 8F") shouldBe "~0"
    }

    "ED A0 is wrong; ED 9F is unfinished" in {
      decode("ED A0") shouldBe "!0"
      decode("ED 9F") shouldBe "~0"
    }
  }

  "the offset names the byte the bad sequence starts at" - {
    "counted in bytes, not in characters" in {
      decode("C3 A9 E2 82 AC FF") shouldBe "!5"
    }

    "after ascii" in {
      decode("61 62 63 C3 28") shouldBe "!3"
    }

    // The first fault is the one reported, so a later one cannot be what the offset is pointing at.
    "the first fault, not the last" in {
      decode("41 FF 42 FE") shouldBe "!1"
    }
  }

  /** `04`'s claim is that a `string` **is** a validated `[]u8` and nothing else, so one that arrived
   * this way has to be indistinguishable from a literal with the same bytes — not merely printable.
   * These are the operations the document lists beside it.
   */
  "a decoded string is a string like any other" - {
    "it compares, orders, and concatenates against a literal" in {
      run(
        """var b: []u8 = [0xE2u8, 0x82u8, 0xACu8, 0x41u8]
          |var s = from_utf8(b).unwrap()
          |print(s.len, s == "€A", s < "€B", s + "!")""".stripMargin,
      ) shouldBe "4 true true €A!\n"
    }

    // The invariant stated as a round trip: because every `string` is well-formed, feeding one's own
    // bytes back through the validator can never fail, whatever it was built from.
    "its own bytes revalidate, and to the same string" in {
      run(
        """var b: []u8 = [0xE2u8, 0x82u8, 0xACu8, 0x41u8]
          |var s = from_utf8(b).unwrap()
          |var again = from_utf8(s.bytes)
          |print(again.is_ok(), again.unwrap() == s)""".stripMargin,
      ) shouldBe "true true\n"
    }

    "a substring on a character boundary is taken" in {
      run(
        """var b: []u8 = [0xE2u8, 0x82u8, 0xACu8, 0x41u8]
          |print(from_utf8(b).unwrap()[3..<4])""".stripMargin,
      ) shouldBe "A\n"
    }

    // And the other half: a substring that would cut a sequence in half traps, exactly as it does
    // for a literal. Validation at the door is only worth having if nothing downstream can undo it.
    "and one that cuts a sequence in half traps" in {
      exits(
        """var b: []u8 = [0xE2u8, 0x82u8, 0xACu8, 0x41u8]
          |print(from_utf8(b).unwrap()[0..<2])""".stripMargin,
      )
    }
  }

  "the unchecked primitive" - {
    "bytes straight through" in {
      run(
        """var b: []u8 = [0x68u8, 0x69u8]
          |print(from_utf8_unchecked(b))""".stripMargin,
      ) shouldBe "hi\n"
    }

    // It copies rather than viewing, and this is why: a `[]u8` can be written through afterwards,
    // and a `string` whose bytes still change is not one that was ever validated.
    "the string it makes does not follow later writes to the bytes" in {
      run(
        """var b: []u8 = [0x68u8, 0x69u8]
          |var s = from_utf8_unchecked(b)
          |b[0] = 0x4Au8
          |print(s, from_utf8_unchecked(b))""".stripMargin,
      ) shouldBe "hi Ji\n"
    }

    // Because the copy is owned rather than a view, it may leave the frame the bytes were in —
    // which the same expression over `a[..]` alone could not.
    "and so it may outlive the array it read" in {
      run(
        """name() -> string
          |    var a: [3]u8 = [0x61u8, 0x62u8, 0x63u8]
          |    from_utf8_unchecked(a[..])
          |print(name())""".stripMargin,
      ) shouldBe "abc\n"
    }

    // The guarantee is the analyzer's, so setting it aside really does set it aside: nothing checks
    // these bytes, and the point of the long name is that the line saying so is greppable.
    "nothing is checked, which is the whole of what it is for" in {
      run(
        """var b: []u8 = [0xFFu8, 0xFEu8]
          |print(from_utf8_unchecked(b).len)""".stripMargin,
      ) shouldBe "2\n"
    }
  }

  "the error path" - {
    "an array is not a slice, and the fix is named" in {
      val e = err(
        """var a: [3]u8 = [0x61u8, 0x62u8, 0x63u8]
          |print(from_utf8_unchecked(a))""".stripMargin,
      )

      e should include("takes a []u8")
      e should include("a[..]")
    }

    "a string is already a string" in {
      err("""print(from_utf8_unchecked("hi"))""") should include("already a string")
    }

    "some other type entirely" in {
      err("print(from_utf8_unchecked(5))") should include("but the value has type int")
    }

    "one value, no more and no fewer" in {
      err("print(from_utf8_unchecked())") should include("exactly one value")
      err(
        """var b: []u8 = [0x61u8]
          |print(from_utf8_unchecked(b, b))""".stripMargin,
      ) should include("exactly one value")
    }

    "the safe form wants bytes too" in {
      err("""print(from_utf8("hi").is_ok())""") should include("string")
    }
  }

  "what it costs a program that never decodes" - {
    // The reachability rule the prelude relies on: neither the validator nor anything it reaches is
    // emitted for a program that does not call it.
    // The name is read off `Library.key` rather than written out, because the validator is in the
    // standard module and `@from_utf8` is no longer the symbol: spelled literally, this negative
    // would go on passing while asserting nothing. `@sysl.str.from_bytes` below is a *codegen*
    // helper — dots, not the module separator — so it is unaffected by where the library declares
    // things and stays spelled as it is emitted.
    "the validator is not emitted" in {
      val out = Compiler.compileToLlvm("print(1)")

      out.map(_.contains(s"@${Library.key("from_utf8")}(")) shouldBe Right(false)
      out.map(_.contains("@sysl.str.from_bytes")) shouldBe Right(false)
    }

    // Layout is the documented exception — a non-generic prelude type is instantiated where it is
    // declared — so the cost is one type line, and it is worth pinning that it stays one.
    "its error type costs exactly one line of layout" in {
      val out = Compiler.compileToLlvm("print(1)")

      out.map(_.linesIterator.count(_.startsWith(s"%struct.${Library.key("Utf8Error")}"))) shouldBe Right(1)
    }
  }
}
