package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `parse_*` — reading a value back out of text (`04 § Reading a value`).
 *
 * The digits are the easy part and are barely tested here. What these are about is the edges, since
 * the edges are what a hand-written loop gets wrong and the reason the parse belongs in the library
 * at all: the most negative value, whose magnitude does not fit the width holding it; the overflow
 * that must be caught *before* the arithmetic, because integer arithmetic wraps rather than
 * trapping, so a check afterwards reads a number that already lied; the empty string; the lone
 * sign; the trailing garbage that makes `"12abc"` not a number.
 *
 * The round trips are the other half. `str` renders and these read, and a value that does not
 * survive the pair means one of the two is wrong — which is a stronger statement than either
 * direction tested alone.
 */
class ParseTests extends AnyFreeSpec with RunSupport {

  private val importing = "import sysl.text.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  "whole numbers" - {

    "read the digits, with or without a sign" in {
      run("""print(parse_int("42").unwrap(), parse_int("-42").unwrap(), parse_int("+42").unwrap())""") shouldBe
        "42 -42 42\n"
    }

    "zero, and leading zeros, are ordinary" in {
      run("""print(parse_int("0").unwrap(), parse_int("007").unwrap(), parse_int("-0").unwrap())""") shouldBe
        "0 7 0\n"
    }

    "the signed range is reached at both ends" in {
      run("""print(parse_long("9223372036854775807").unwrap())
            |print(parse_long("-9223372036854775808").unwrap())""".stripMargin) shouldBe
        "9223372036854775807\n-9223372036854775808\n"
    }

    // THE case the negative accumulation exists for. A parser that built the magnitude and negated
    // at the end cannot hold `-MIN` at any point, so it either overflows here or special-cases the
    // one string. Both of the neighbours are checked too, since an off-by-one at the limit shows up
    // as either accepting one too many or refusing the last good one.
    "including the value whose magnitude will not fit, and its neighbours" in {
      run("""print(parse_long("-9223372036854775807").is_ok())
            |print(parse_long("-9223372036854775808").is_ok())
            |print(parse_long("-9223372036854775809").is_ok())
            |print(parse_long("9223372036854775808").is_ok())""".stripMargin) shouldBe
        "true\ntrue\nfalse\nfalse\n"
    }

    // The overflow has to be caught before the multiply, since the multiply wraps. A parser
    // checking afterwards would accept this and hand back some small wrapped number.
    "a value far past the range overflows rather than wrapping" in {
      run("""parse_long("99999999999999999999999999") match
            |    Ok(v) -> print(f"wrongly accepted $v%d")
            |    Err(e) -> print(e)""".stripMargin) shouldBe "value too large for its type\n"
    }

    "an int is narrower than a long, and says so" in {
      run("""print(parse_int("2147483647").is_ok(), parse_int("2147483648").is_ok())
            |print(parse_int("-2147483648").is_ok(), parse_int("-2147483649").is_ok())
            |print(parse_long("2147483648").is_ok())""".stripMargin) shouldBe
        "true false\ntrue false\ntrue\n"
    }
  }

  "what is refused" - {

    "the empty string has no digits to read" in {
      run("""print(parse_int("").unwrap_err(), parse_int("-").unwrap_err(), parse_int("+").unwrap_err())""") shouldBe
        "no digits to read no digits to read no digits to read\n"
    }

    // Trailing garbage is refused rather than ignored, so `"12abc"` is not `12`. The offset names
    // the first byte that is not a digit, which is what a message to a user needs.
    "trailing garbage is not silently dropped, and the offset names it" in {
      run("""print(parse_int("12abc").unwrap_err(), parse_int("1 2").unwrap_err())""") shouldBe
        "not a digit at byte 2 not a digit at byte 1\n"
    }

    "a sign in the middle is not a sign" in {
      run("""print(parse_int("1-2").unwrap_err())""") shouldBe "not a digit at byte 1\n"
    }

    "and nothing that is not a number at all" in {
      run("""print(parse_int("abc").unwrap_err(), parse_int(" 1").unwrap_err())""") shouldBe
        "not a digit at byte 0 not a digit at byte 0\n"
    }
  }

  "other bases" - {

    "hex reads in either case" in {
      run("""print(parse_long_base("ff", 16).unwrap(), parse_long_base("FF", 16).unwrap())""") shouldBe
        "255 255\n"
    }

    "binary, octal and base 36" in {
      run("""print(parse_long_base("1011", 2).unwrap(), parse_long_base("777", 8).unwrap(),
            |      parse_long_base("zz", 36).unwrap())""".stripMargin) shouldBe "11 511 1295\n"
    }

    "a sign works in any base" in {
      run("""print(parse_long_base("-ff", 16).unwrap())""") shouldBe "-255\n"
    }

    // The discriminating one: `8` is a digit and is not a digit in base 8, and `2` is not one in
    // binary. A parser testing for "is a digit" without the base accepts both.
    "a digit out of range for the base is refused" in {
      run("""print(parse_long_base("18", 8).unwrap_err(), parse_long_base("12", 2).unwrap_err())""") shouldBe
        "not a digit at byte 1 not a digit at byte 1\n"
    }

    // The caller's mistake rather than the input's, which is why it names the base back.
    "a base nothing could be written in is the caller's error" in {
      run("""print(parse_long_base("1", 1).unwrap_err(), parse_long_base("1", 37).unwrap_err())""") shouldBe
        "1 is not a base between 2 and 36 37 is not a base between 2 and 36\n"
    }
  }

  "unsigned" - {

    // The case a signed parse cannot reach, and the reason unsigned parsing exists: a 64-bit mask
    // written in hex is an ordinary thing for a systems program to read back.
    "reaches the top of the range, where every signed parse overflows" in {
      run("""print(parse_ulong_base("ffffffffffffffff", 16).unwrap())
            |print(parse_long_base("ffffffffffffffff", 16).is_ok())""".stripMargin) shouldBe
        "18446744073709551615\nfalse\n"
    }

    "one past the top overflows" in {
      run("""print(parse_ulong("18446744073709551615").is_ok())
            |print(parse_ulong("18446744073709551616").is_ok())""".stripMargin) shouldBe "true\nfalse\n"
    }

    // No sign at all, not even `+`: a leading `-` on an unsigned value is a question with no good
    // answer, and refusing at the first byte is the one that cannot surprise.
    "no sign is accepted, in either direction" in {
      run("""print(parse_ulong("-1").unwrap_err(), parse_ulong("+1").unwrap_err())""") shouldBe
        "not a digit at byte 0 not a digit at byte 0\n"
    }

    "a uint is narrower than a ulong" in {
      run("""print(parse_uint("4294967295").is_ok(), parse_uint("4294967296").is_ok())""") shouldBe
        "true false\n"
    }
  }

  "bool" - {

    "exactly the two spellings 'str' produces" in {
      run("""print(parse_bool("true").unwrap(), parse_bool("false").unwrap())""") shouldBe "true false\n"
    }

    // Everything else is somebody's convention and not this library's, so a program wanting one
    // writes it where a reader can see it is a policy.
    "and nothing else, however conventional" in {
      run("""print(parse_bool("True").is_ok(), parse_bool("1").is_ok(),
            |      parse_bool("yes").is_ok(), parse_bool("").is_ok())""".stripMargin) shouldBe
        "false false false false\n"
    }
  }

  "floats" - {

    "read the ordinary spellings" in {
      run("""print(parse_real("1.5").unwrap(), parse_real("-0.25").unwrap(), parse_real("3").unwrap())""") shouldBe
        "1.5 -0.25 3\n"
    }

    "and the exponent form" in {
      run("""print(parse_real("1e3").unwrap(), parse_real("1.5e-2").unwrap())""") shouldBe "1000 0.015\n"
    }

    // C's `strtod` stops at the first byte it cannot use and reports where, so the end pointer is
    // what turns its lenient parse into this library's strict one. Without that check `"1.5x"`
    // would read as `1.5`.
    "trailing garbage is refused, which C's own parse would not do" in {
      run("""print(parse_real("1.5x").unwrap_err(), parse_real("abc").unwrap_err(), parse_real("").unwrap_err())""") shouldBe
        "not a digit at byte 3 not a digit at byte 0 no digits to read\n"
    }
  }

  "round trips against the rendering half" - {

    // The strongest statement available: `str` renders and these read, so a value surviving the
    // pair says the two agree. Either alone could be self-consistently wrong.
    "every integer edge survives being written and read back" in {
      run("""var lo: long = -9223372036854775807 - 1
            |var hi: long = 9223372036854775807
            |print(parse_long(str(lo)).unwrap() == lo, parse_long(str(hi)).unwrap() == hi)
            |print(parse_long(str(0)).unwrap() == 0, parse_long(str(-1)).unwrap() == -1)""".stripMargin) shouldBe
        "true true\ntrue true\n"
    }

    "so does a bool" in {
      run("""print(parse_bool(str(true)).unwrap(), parse_bool(str(false)).unwrap())""") shouldBe
        "true false\n"
    }

    "and a float that 'str' renders exactly" in {
      run("""print(parse_real(str(2.5)).unwrap() == 2.5, parse_real(str(-0.125)).unwrap() == -0.125)""") shouldBe
        "true true\n"
    }
  }
}
