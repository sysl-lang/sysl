package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2: what a `string` does as a program runs. A string is an immutable validated `[]u8`,
 * so what is worth checking is the part a slice does not already cover — that a length is in
 * bytes, that comparison is by bytes, that a substring shares rather than copies, and that the
 * UTF-8 guarantee survives every way of taking one apart.
 */
class StringRunTests extends AnyFreeSpec with RunSupport {

  "length and indexing" - {
    "a length counts bytes, not characters" in {
      run("""print("hello".len, "héllo".len, "".len)""") shouldBe "5 6 0\n"
    }

    "an index reads one byte" in {
      run("""var s = "AB"
            |print(s[0], s[1])""".stripMargin) shouldBe "65 66\n"
    }

    "the bytes of a non-ASCII character are the ones UTF-8 gives it" in {
      run("""var s = "é"
            |print(s.len, s[0], s[1])""".stripMargin) shouldBe "2 195 169\n"
    }

    "an index past the end stops the program" in {
      exits("""var s = "ab"
              |var i = 2
              |print(s[i])""".stripMargin)
    }
  }

  "substrings" - {
    "every range form takes the bytes it names" in {
      val src =
        """var s = "abcdef"
          |print(s[..], s[0..<3], s[0..3], s[3..], s[..<2])
          |""".stripMargin

      run(src) shouldBe "abcdef abc abcd def ab\n"
    }

    "a substring of a substring is relative to the substring" in {
      run("""var s = "abcdef"
            |var t = s[1..<5]
            |print(t, t[1..<3])""".stripMargin) shouldBe "bcde cd\n"
    }

    "an empty substring is legal, at the end and in the middle" in {
      run("""var s = "abc"
            |print(s[3..].len, s[1..<1].len)""".stripMargin) shouldBe "0 0\n"
    }

    "a substring shares its parent's bytes rather than copying them" in {
      val src =
        """tail(s: string) -> string
          |    s[1..]
          |end tail
          |var s = "hello"
          |print(tail(s), tail(tail(s)))
          |""".stripMargin

      run(src) shouldBe "ello llo\n"
    }

    "a bound past the end stops the program" in {
      exits("""var s = "abc"
              |var n = 4
              |print(s[0..<n].len)""".stripMargin)
    }

    "a cut inside a character stops the program" in {
      exits("""var s = "é"
              |var n = 1
              |print(s[0..<n].len)""".stripMargin)
    }

    "a cut at the start of a character is fine" in {
      run("""var s = "aéb"
            |print(s[0..<1], s[1..<3], s[3..])""".stripMargin) shouldBe "a é b\n"
    }
  }

  "comparison" - {
    "equality is by bytes" in {
      run("""print("abc" == "abc", "abc" == "abd", "abc" != "ab")""") shouldBe "true false true\n"
    }

    "a shorter string that is a prefix comes first" in {
      run("""print("ab" < "abc", "abc" < "ab", "" < "a")""") shouldBe "true false true\n"
    }

    "ordering is by byte, which for UTF-8 is by codepoint" in {
      run("""print("a" < "b", "z" < "é", "é" < "☃")""") shouldBe "true true true\n"
    }

    "a comparison chains like any other" in {
      run("""print("a" < "b" < "c")""") shouldBe "true\n"
    }

    "two strings that share a buffer still compare by content" in {
      run("""var s = "abab"
            |print(s[0..<2] == s[2..], s[0..<2] == s[1..<3])""".stripMargin) shouldBe "true false\n"
    }

    "a literal is matched like any other value" in {
      val src =
        """kind(s: string) -> int
          |    match s
          |        "yes" -> 1
          |        "no" -> 0
          |        else -> -1
          |end kind
          |print(kind("yes"), kind("no"), kind("maybe"))
          |""".stripMargin

      run(src) shouldBe "1 0 -1\n"
    }
  }

  "bytes" - {
    "a string's bytes are a slice of the same length" in {
      run("""var s = "héllo"
            |print(s.bytes.len, s.bytes[1])""".stripMargin) shouldBe "6 195\n"
    }

    "iterating the bytes reaches each one in turn" in {
      val src =
        """var s = "abc"
          |var total = 0
          |for b in s.bytes do total += int(b)
          |print(total)
          |""".stripMargin

      run(src) shouldBe "294\n"
    }

    "a byte view can be handed to anything that takes a slice" in {
      val src =
        """total(bytes: []u8) -> int
          |    var t = 0
          |    for b in bytes do t += int(b)
          |    t
          |end total
          |print(total("abc".bytes), total("abc".bytes[1..]))
          |""".stripMargin

      run(src) shouldBe "294 197\n"
    }
  }

  "content" - {
    "a NUL is an ordinary byte, in the middle and at the end" in {
      run("""var s = "a\0b"
            |print(s.len, s[1], s)""".stripMargin) shouldBe "3 0 a" + 0.toChar + "b\n"
    }

    "a zero-valued string is the empty string" in {
      run("""var s: string
            |print(s.len, s == "", s)""".stripMargin) shouldBe "0 true \n"
    }

    "a string survives being carried through a struct and an enum" in {
      val src =
        """struct Named
          |    name: string
          |end Named
          |enum Answer
          |    Word(w: string)
          |    Nothing
          |end Answer
          |say(a: Answer) -> string
          |    match a
          |        Word(w) -> w
          |        Nothing -> "-"
          |end say
          |var n = Named("ada")
          |print(n.name, say(Word(n.name)), say(Nothing))
          |""".stripMargin

      run(src) shouldBe "ada ada -\n"
    }

    "a string on the heap outlives the reference that put it there" in {
      val src =
        """struct Label
          |    text: string
          |end Label
          |name() -> string
          |    var l: &Label = Label("boxed")
          |    l.text
          |end name
          |print(name())
          |""".stripMargin

      run(src) shouldBe "boxed\n"
    }

    "taking substrings in a loop neither leaks nor frees twice" in {
      val src =
        """var s = "abcdefgh"
          |var total = 0
          |for i in 1..20000 do
          |    var t = s[1..<7]
          |    total += int(t[0])
          |print(total)
          |""".stripMargin

      run(src) shouldBe "1960000\n"
    }
  }
}
