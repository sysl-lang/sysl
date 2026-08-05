package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.regex`'s parser — a POSIX ERE pattern read into a tree.
 *
 * Everything here asserts on `show`, which renders the tree as a parenthesised term rather than
 * back into pattern text. That choice is what makes the assertions discriminating: `a|bc` and
 * `(a|b)c` are both legal patterns differing only in precedence, so a round-trip to pattern text
 * would let exactly the mistakes a parser makes pass unnoticed, while `alt(lit(a), seq(lit(b),
 * lit(c)))` and `seq(group1(alt(lit(a), lit(b))), lit(c))` cannot be confused for one another.
 *
 * Each test compiles and runs one program over several patterns, because compiling to a native
 * binary is what costs — the patterns themselves are free.
 */
class RegexParseTests extends AnyFreeSpec with RunSupport {

  /** A program that prints what each pattern parses to, one line each: the tree, then how many
   * capture groups the parser counted.
   */
  private def parses(patterns: String*): String =
    run(
      """import sysl.regex.{parse_counted, show, describe}
        |
        |p(pat: string)
        |    parse_counted(pat) match
        |        Ok(t) -> print(s"${show(t.0)} [${t.1}]")
        |        Err(e) -> print(describe(e))
        |
        |main()
        |""".stripMargin + patterns.map(p => s"""    p("$p")""").mkString("\n") + "\n",
    )

  "the shape of a pattern" - {

    "a literal, and a concatenation associating to the left" in {
      parses("a", "ab", "abc") shouldBe
        """lit(a) [0]
          |seq(lit(a), lit(b)) [0]
          |seq(seq(lit(a), lit(b)), lit(c)) [0]
          |""".stripMargin
    }

    // The discriminating case is the third: alternation binds looser than concatenation, so `ab|cd`
    // is one alternation of two sequences and not a sequence containing an alternation. A parser
    // that gets the precedence backwards still parses the first two.
    "alternation binds looser than concatenation" in {
      parses("a|b", "a|b|c", "ab|cd") shouldBe
        """alt(lit(a), lit(b)) [0]
          |alt(alt(lit(a), lit(b)), lit(c)) [0]
          |alt(seq(lit(a), lit(b)), seq(lit(c), lit(d))) [0]
          |""".stripMargin
    }

    "a quantifier binds tighter than concatenation, so it takes the atom and not the sequence" in {
      parses("a*", "a+", "a?", "ab*") shouldBe
        """star(lit(a)) [0]
          |plus(lit(a)) [0]
          |quest(lit(a)) [0]
          |seq(lit(a), star(lit(b))) [0]
          |""".stripMargin
    }

    // ERE has no lazy quantifiers, so the second one applies to the first rather than modifying it.
    "quantifiers stack rather than modifying one another" in {
      parses("a*?", "a+*") shouldBe
        """quest(star(lit(a))) [0]
          |star(plus(lit(a))) [0]
          |""".stripMargin
    }

    "dot, and the two anchors" in {
      parses(".", "^a$") shouldBe
        """any [0]
          |seq(seq(start, lit(a)), end) [0]
          |""".stripMargin
    }

    "an empty branch is a node rather than an absence" in {
      parses("a|", "|a", "", "()") shouldBe
        """alt(lit(a), empty) [0]
          |alt(empty, lit(a)) [0]
          |empty [0]
          |group1(empty) [1]
          |""".stripMargin
    }
  }

  "capture groups" - {

    // Numbered by where the `(` is, which is what makes the numbering read left to right even when
    // the groups nest: the outer group of `((a)b)` is 1 because its parenthesis comes first.
    "are numbered in the order their parenthesis is read, nesting included" in {
      parses("(a)", "(a)(b)", "((a)b)", "(a(b))") shouldBe
        """group1(lit(a)) [1]
          |seq(group1(lit(a)), group2(lit(b))) [2]
          |group1(seq(group2(lit(a)), lit(b))) [2]
          |group1(seq(lit(a), group2(lit(b)))) [2]
          |""".stripMargin
    }

    "a group is an atom, so a quantifier takes the whole of it" in {
      parses("(ab)*", "(a|b)c") shouldBe
        """star(group1(seq(lit(a), lit(b)))) [1]
          |seq(group1(alt(lit(a), lit(b))), lit(c)) [1]
          |""".stripMargin
    }
  }

  "intervals expand into the nodes that already exist" - {

    "a fixed count is that many copies concatenated" in {
      parses("a{1}", "a{3}") shouldBe
        """lit(a) [0]
          |seq(seq(lit(a), lit(a)), lit(a)) [0]
          |""".stripMargin
    }

    "a bounded range is the required copies followed by that many optional ones" in {
      parses("a{2,4}", "a{0,2}") shouldBe
        """seq(seq(seq(lit(a), lit(a)), quest(lit(a))), quest(lit(a))) [0]
          |seq(quest(lit(a)), quest(lit(a))) [0]
          |""".stripMargin
    }

    "an open range is the required copies followed by a star" in {
      parses("a{3,}", "a{0,}") shouldBe
        """seq(seq(seq(lit(a), lit(a)), lit(a)), star(lit(a))) [0]
          |star(lit(a)) [0]
          |""".stripMargin
    }

    "a count of zero matches nothing at all" in {
      parses("a{0}") shouldBe "empty [0]\n"
    }

    // The group keeps one number however many copies the expansion made, which is what lets the
    // matcher report what the last repetition captured.
    "a group inside a repeated piece keeps one number" in {
      parses("(a){2}") shouldBe "seq(group1(lit(a)), group1(lit(a))) [1]\n"
    }
  }

  "bracket expressions" - {

    "single characters, a range, and a negation" in {
      parses("[abc]", "[a-z]", "[^a-z]", "[^abc]") shouldBe
        """set(a,b,c) [0]
          |set(a-z) [0]
          |notset(a-z) [0]
          |notset(a,b,c) [0]
          |""".stripMargin
    }

    // Both of POSIX's positional quirks. A `]` first is ordinary rather than closing an empty class,
    // and a `-` with nothing on one side of it is ordinary rather than opening a range.
    "a ']' first is ordinary, and so is a '-' at either end" in {
      parses("[]a]", "[^]a]", "[a-]", "[-a]") shouldBe
        """set(],a) [0]
          |notset(],a) [0]
          |set(a,-) [0]
          |set(-,a) [0]
          |""".stripMargin
    }

    "a named class is kept as itself rather than expanded into ranges" in {
      parses("[[:digit:]]", "[[:alpha:][:space:]]") shouldBe
        """set([:digit:]) [0]
          |set([:alpha:],[:space:]) [0]
          |""".stripMargin
    }

    // `show` groups the ranges before the names, so the rendering is not the source order.
    "a class mixes ranges and names" in {
      parses("[[:alpha:]0-9_]") shouldBe "set(0-9,_,[:alpha:]) [0]\n"
    }

    // The metacharacters are all ordinary inside brackets, which is the rule that makes `[.*]` two
    // literal characters rather than anything repeated.
    "the metacharacters are ordinary inside brackets" in {
      parses("[.*+?()|]") shouldBe "set(.,*,+,?,(,),|) [0]\n"
    }
  }

  // The parser steps characters rather than bytes, which is what makes a pattern holding text work
  // at all: stepping bytes would see four of them here and read a range between two of the halves.
  "a pattern holding text outside ASCII is read by character" in {
    parses("[é-ü]", "é") shouldBe
      """set(é-ü) [0]
        |lit(é) [0]
        |""".stripMargin
  }

  "escaping makes the next character ordinary" - {

    "a metacharacter escaped is a literal" in {
      parses("\\\\.", "\\\\(", "\\\\*", "\\\\[") shouldBe
        """lit(.) [0]
          |lit(() [0]
          |lit(*) [0]
          |lit([) [0]
          |""".stripMargin
    }

    // The opposite of Basic Regular Expressions, where `\(` is what opens a group. Getting this
    // backwards is the single most likely way to port a BRE engine's parser by mistake.
    "an escaped parenthesis is a literal and does not open a group" in {
      parses("\\\\(a\\\\)") shouldBe "seq(seq(lit((), lit(a)), lit())) [0]\n"
    }
  }

  "a pattern is refused, and the offset points at the trouble" - {

    "a quantifier with nothing before it" in {
      parses("*a", "+a", "?a", "{2}") shouldBe
        """a repetition has nothing before it to repeat, at 0
          |a repetition has nothing before it to repeat, at 0
          |a repetition has nothing before it to repeat, at 0
          |a repetition has nothing before it to repeat, at 0
          |""".stripMargin
    }

    "a parenthesis with no partner, in either direction" in {
      parses("(a", "a)", "((a)") shouldBe
        """a group is never closed, opened at 0
          |a ')' closes a group that was never opened, at 1
          |a group is never closed, opened at 0
          |""".stripMargin
    }

    "a bracket expression that never closes" in {
      parses("[a", "[", "[^") shouldBe
        """a bracket expression is never closed, opened at 0
          |a bracket expression is never closed, opened at 0
          |a bracket expression is never closed, opened at 0
          |""".stripMargin
    }

    "an interval that is not one" in {
      parses("a{", "a{x}", "a{2", "a{2,1}") shouldBe
        """an interval needs a count, at 1
          |an interval needs a count, at 1
          |an interval is never closed, opened at 1
          |an interval counts down rather than up, at 1
          |""".stripMargin
    }

    "a range running the wrong way" in {
      parses("[z-a]") shouldBe "a range runs from high to low, at 1\n"
    }

    // A typo in a class name is refused rather than read as the letters of the typo, which is what
    // POSIX leaves undefined and what would otherwise match and give a wrong answer in silence.
    "a class name that is not one, and one that never closes" in {
      parses("[[:foo:]]", "[[:alpha]]", "[[:alpha") shouldBe
        """there is no character class named 'foo', at 1
          |a [:name:] is never closed, opened at 1
          |a [:name:] is never closed, opened at 1
          |""".stripMargin
    }

    "a backslash at the very end" in {
      parses("a\\\\") shouldBe "a backslash ends the pattern, at 1\n"
    }
  }
}
