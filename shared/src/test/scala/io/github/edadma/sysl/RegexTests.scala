package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.regex`'s public surface — `regex`, `Regex` and `Match`.
 *
 * The engine's own behaviour is pinned by `RegexParseTests`, `RegexCompileTests` and
 * `RegexVmTests`. What is under test here is the layer a program actually calls: that spans come
 * back as text a slice can cut, that a group which took no part is absent rather than empty, and
 * that walking a text finds each match once and terminates when the pattern matches nothing.
 */
class RegexTests extends AnyFreeSpec with RunSupport {

  private def uses(src: String): String = run("import sysl.regex.regex\n\n" + src)

  "a match reports text as well as spans" - {

    "the whole match and each group" in {
      uses("""var re = regex("([a-z]+)@([a-z.]+)").unwrap()
              |
              |re.find("write to ed@example.com today") match
              |    Some(m) -> print(m.text(), m.group(1).unwrap(), m.group(2).unwrap(), m.start(), m.end())
              |    None -> print("none")""".stripMargin) shouldBe
        "ed@example.com ed example.com 9 23\n"
    }

    // The two cases that a span alone cannot tell apart, and the reason `group` answers an
    // `Option`: a group that took no part in the match is absent, and one that matched the empty
    // string is present and empty.
    "a group that did not take part is absent, and one that matched empty is not" in {
      uses("""var re = regex("(a)|(b)").unwrap()
              |var m = re.find("b").unwrap()
              |var e = regex("(a*)b").unwrap().find("b").unwrap()
              |
              |print(m.group(1).is_some(), m.group(2).unwrap(), e.group(1).is_some(), s"[${e.group(1).unwrap()}]")""".stripMargin) shouldBe
        "false b true []\n"
    }

    "asking for a group the pattern does not have is absent rather than a trap" in {
      uses("""var m = regex("(a)").unwrap().find("a").unwrap()
              |
              |print(m.group_count(), m.group(1).is_some(), m.group(2).is_some())""".stripMargin) shouldBe
        "2 true false\n"
    }

    // A span is a byte offset on a character boundary, which is what makes it directly a slice.
    // A matcher stepping bytes would report 0..1 for the first character here and the slice would
    // be refused.
    "a span over text outside ASCII cuts cleanly" in {
      uses("""var m = regex("..").unwrap().find("héllo").unwrap()
              |
              |print(m.text(), m.end())""".stripMargin) shouldBe "hé 3\n"
    }
  }

  "walking a text" - {

    "every match, left to right and not overlapping" in {
      uses("""var re = regex("[a-z]+").unwrap()
              |var all = re.find_all("ab cd ef")
              |var out = ""
              |
              |for i in 0usize..<all.len() do out += s"${all.at(i).text()}@${all.at(i).start()} "
              |print(out)""".stripMargin) shouldBe "ab@0 cd@3 ef@6 \n"
    }

    // The case a naive loop hangs on. An empty match at a position would be found there again for
    // ever, so the walk resumes one character on — which is why there are three and not two.
    "a pattern that matches the empty string still terminates" in {
      uses("""var all = regex("a*").unwrap().find_all("bb")
              |var out = ""
              |
              |for i in 0usize..<all.len() do out += s"${all.at(i).start()} "
              |print(all.len(), out)""".stripMargin) shouldBe "3 0 1 2 \n"
    }

    // The discriminating test for `find_at` taking a *starting position* rather than a shortened
    // input. Searching the remainder after the first match would give `^` a fresh beginning to
    // match against and answer 2 here.
    "an anchor keeps speaking about the whole text, not about where the search resumed" in {
      uses("""print(regex("^ab").unwrap().find_all("abab").len(),
              |      regex("b$").unwrap().find_all("bb").len())""".stripMargin) shouldBe "1 1\n"
    }
  }

  "replacing" - {

    "every match replaced, with the text between them kept" in {
      uses("""print(regex("[a-z]+").unwrap().replace_all("ab cd ef", "X"))""") shouldBe "X X X\n"
    }

    "the replacement may name the groups the match found" in {
      uses("""var re = regex("([a-z]+)=([0-9]+)").unwrap()
              |
              |print(re.replace_all("x=1, yy=22", "\\2:\\1"))""".stripMargin) shouldBe "1:x, 22:yy\n"
    }

    "a doubled backslash is one, and a group that took no part contributes nothing" in {
      uses("""print(regex("a").unwrap().replace_all("a", "p\\\\q"),
              |      regex("(x)|(a)").unwrap().replace_all("a", "[\\1]"))""".stripMargin) shouldBe
        "p\\q []\n"
    }

    "a pattern that matches nothing leaves the text alone" in {
      uses("""print(regex("z+").unwrap().replace_all("abc", "X"))""") shouldBe "abc\n"
    }
  }

  "splitting" - {

    // Four pieces from three separators, the empty one between the doubled comma included — a
    // splitter that drops empty pieces silently loses a field.
    "the pieces between the matches, empty ones included" in {
      uses("""var parts = regex(",").unwrap().split("a,b,,c")
              |var out = ""
              |
              |for i in 0usize..<parts.len() do out += s"[${parts.at(i)}]"
              |print(parts.len(), out)""".stripMargin) shouldBe "4 [a][b][][c]\n"
    }

    "a text with no separator in it is one piece" in {
      uses("""var parts = regex(",").unwrap().split("abc")
              |
              |print(parts.len(), parts.at(0usize))""".stripMargin) shouldBe "1 abc\n"
    }
  }

  "asking only whether it matched" in {
    uses("""var re = regex("[a-z]+").unwrap()
            |
            |print(re.is_match("123"), re.is_match("1a3"), re.group_count())""".stripMargin) shouldBe
      "false true 0\n"
  }

  /** The inputs and patterns that are nothing much, which is where a surface breaks.
   *
   * Each of these is a place `find_all` and the two written on top of it could reasonably answer
   * something silly — a replacement that loses the text between empty matches, a split that drops
   * the ends, a search resumed past the end of the input that walks off it.
   */
  "the degenerate cases" - {

    // Three empty matches over "bb", so the replacement lands before each character and after the
    // last, and the characters between them are kept. Losing them is the obvious way to get this
    // wrong, since there is no text "between" two matches that are both empty.
    "replacing and splitting on a pattern that matches empty" in {
      uses("""var re = regex("a*").unwrap()
              |var p = re.split("bb")
              |var out = ""
              |
              |for i in 0usize..<p.len() do out += s"[${p.at(i)}]"
              |print(s"[${re.replace_all("bb", "X")}]", p.len(), out)""".stripMargin) shouldBe
        "[XbXbX] 4 [][b][b][]\n"
    }

    "an empty input" in {
      uses("""print(regex("a").unwrap().find_all("").len(),
              |      regex("a*").unwrap().find_all("").len(),
              |      s"[${regex("x").unwrap().replace_all("", "Y")}]",
              |      regex(",").unwrap().split("").len())""".stripMargin) shouldBe "0 1 [] 1\n"
    }

    "a search resumed at the very end of the input" in {
      uses("""print(regex("a").unwrap().find_at("abc", 3usize).is_some(),
              |      regex("a*").unwrap().find_at("abc", 3usize).unwrap().start())""".stripMargin) shouldBe
        "false 3\n"
    }

    // Ten groups is twenty-two slots, which is the arithmetic `slots()` does and the one place an
    // off-by-one would put the last group's end past the end of the array.
    "a pattern with ten groups sizes its slots right" in {
      uses("""var re = regex("(a)(b)(c)(d)(e)(f)(g)(h)(i)(j)").unwrap()
              |var m = re.find("abcdefghij").unwrap()
              |
              |print(re.group_count(), m.group_count(), m.group(10usize).unwrap())""".stripMargin) shouldBe
        "10 11 j\n"
    }

    "a text that is entirely separator splits into two empty pieces" in {
      uses("""var q = regex("a+").unwrap().split("aaa")
              |var out = ""
              |
              |for i in 0usize..<q.len() do out += s"[${q.at(i)}]"
              |print(q.len(), out)""".stripMargin) shouldBe "2 [][]\n"
    }
  }

  // Compiling is where a pattern is refused, so a caller handles the error once rather than at
  // every match.
  "a pattern that does not parse is refused at compile time" in {
    uses("""import sysl.regex.describe
            |
            |regex("(a") match
            |    Ok(_) -> print("compiled")
            |    Err(e) -> print(describe(e))""".stripMargin) shouldBe
      "a group is never closed, opened at 0\n"
  }
}
