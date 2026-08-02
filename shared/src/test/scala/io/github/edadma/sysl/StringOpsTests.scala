package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Searching, trimming, splitting and joining (`04 § Operations`).
 *
 * Two properties are under test throughout and are worth naming, because most of the individual
 * cases are checking one of them.
 *
 * **The operations are written once and reach two types.** `Search` is a trait with two required
 * members, so `s.starts_with(t)` on a `string` and on a `[]const u8` are the same code. The older
 * sysl wrote this surface twice for want of that, so the tests that run the same spelling against
 * both types are checking the design and not just the answer.
 *
 * **Byte-level search is correct over UTF-8, not merely convenient.** UTF-8 is self-synchronizing,
 * so a well-formed needle cannot match anywhere but at a character boundary — which is why an
 * offset these return is always safe to slice at. `s[a..b]` traps on a mid-codepoint bound, so a
 * test that slices at a returned offset is checking that claim rather than restating it.
 */
class StringOpsTests extends AnyFreeSpec with RunSupport {

  private val importing = "import sysl.text.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  "prefixes and suffixes" - {

    "match at either end and nowhere else" in {
      run("""print("hello".starts_with("he"), "hello".starts_with("lo"))
            |print("hello".ends_with("lo"), "hello".ends_with("he"))""".stripMargin) shouldBe
        "true false\ntrue false\n"
    }

    "the empty affix matches everything, and a whole string matches itself" in {
      run("""print("abc".starts_with(""), "abc".ends_with(""), "abc".starts_with("abc"))
            |print("".starts_with(""), "".starts_with("x"))""".stripMargin) shouldBe
        "true true true\ntrue false\n"
    }

    "an affix longer than the string is not one" in {
      run("""print("ab".starts_with("abc"), "ab".ends_with("xab"))""") shouldBe "false false\n"
    }
  }

  "finding" - {

    "reports where the first and last occurrences start" in {
      run("""print("abcabc".index_of("bc").unwrap(), "abcabc".last_index_of("bc").unwrap())""") shouldBe
        "1 4\n"
    }

    "and nothing when it is not there" in {
      run("""print("abc".index_of("z").is_none(), "abc".last_index_of("z").is_none())
            |print("ab".index_of("abc").is_none())""".stripMargin) shouldBe "true true\ntrue\n"
    }

    // The two conventions read from opposite ends, which is what makes `split` on an empty
    // separator terminate rather than loop.
    "the empty needle is at the start going forwards and at the end going back" in {
      run("""print("abc".index_of("").unwrap(), "abc".last_index_of("").unwrap())""") shouldBe "0 3\n"
    }

    "single bytes are found from either end" in {
      run("""print("a,b,c".index_of_byte(u8(',')).unwrap(), "a,b,c".last_index_of_byte(u8(',')).unwrap())
            |print("abc".index_of_byte(u8('z')).is_none())""".stripMargin) shouldBe "1 3\ntrue\n"
    }

    "containment is the same question asked for a yes or no" in {
      run("""print("hello".contains("ell"), "hello".contains("elle"))""") shouldBe "true false\n"
    }

    // Non-overlapping and left to right, which is the rule `replace_all` substitutes by — so the
    // count and the number of replacements always agree. In "aaa" there is one "aa", not two.
    "counting is non-overlapping, so it agrees with replacing" in {
      run("""print("aaa".count_of("aa"), "aaaa".count_of("aa"), "abcabc".count_of("bc"))
            |print("abc".count_of("z"), "abc".count_of(""))""".stripMargin) shouldBe "1 2 2\n0 0\n"
    }
  }

  "searching text that is not ASCII" - {

    // The self-synchronizing property, stated as a test: `é` is `C3 A9`, and the `A9` byte cannot be
    // mistaken for the start of anything, so a needle can only ever match at a boundary.
    "a needle matches only at a character boundary" in {
      run("""print("héllo".index_of("llo").unwrap(), "héllo".index_of("é").unwrap())""") shouldBe "3 1\n"
    }

    // The claim the offsets exist for. `s[a..]` traps on a mid-codepoint bound, so this printing at
    // all is the guarantee — a search that could report an interior byte would abort here.
    "so an offset it reports is always safe to slice at" in {
      run("""var s = "αβγ-δε"
            |var at = s.index_of("-").unwrap()
            |print(s[..<at], s[at + 1usize..])""".stripMargin) shouldBe "αβγ δε\n"
    }
  }

  "trimming answers with a view of what it was given" - {

    "whitespace comes off either end or both" in {
      run("""print(f"[${"  hi  ".trim_start()}%s]", f"[${"  hi  ".trim_end()}%s]", f"[${"  hi  ".trim()}%s]")""") shouldBe
        "[hi  ] [  hi] [hi]\n"
    }

    // All six of C's whitespace characters, since the trim is written on `Ascii.is_space` and a
    // hand-rolled one would stop after space and tab.
    "and it is all of the whitespace, not just the space" in {
      run("""print(f"[${" \t\n\u{b}\u{c}\rhi\r\u{c}\u{b}\n\t ".trim()}%s]")""") shouldBe "[hi]\n"
    }

    "a string that is all whitespace trims to nothing" in {
      run("""print("   ".trim().is_empty(), "".trim().is_empty(), "x".trim())""") shouldBe "true true x\n"
    }

    "a cutset trims the bytes the caller names instead" in {
      run("""print("xxhixx".trim_matches("x"), "//a//".trim_start_matches("/"), "//a//".trim_end_matches("/"))""") shouldBe
        "hi a// //a\n"
    }

    // A set, not a sequence: any of its bytes is trimmed, in any order and any number of times.
    "the cutset is a set of bytes rather than a string to match" in {
      run("""print(f"[${"ab-ba-x-abba".trim_matches("ab")}%s]")""") shouldBe "[-ba-x-]\n"
    }
  }

  "the same operations reach bytes that are not text" - {

    // The whole point of the trait: one spelling, two types, one implementation.
    "a byte view answers exactly as the string does" in {
      run("""var s = "hello, world"
            |var b = s.bytes
            |print(s.starts_with("hello"), b.starts_with("hello".bytes))
            |print(s.index_of("world").unwrap(), b.index_of("world".bytes).unwrap())
            |print(s.count_of("l"), b.count_of("l".bytes))""".stripMargin) shouldBe
        "true true\n7 7\n3 3\n"
    }

    "and trimming a byte view yields a byte view" in {
      run("""var b = "  hi  ".bytes
            |print(b.trim().len, b.len)""".stripMargin) shouldBe "2 6\n"
    }

    // `s.bytes` is already read-only, which is why every case above resolved without anything being
    // widened. A buffer a program is still filling is a *writable* `[]u8`, and that is the receiver
    // the second implementation exists for — bytes off a socket or a file, before anyone knows
    // whether they are UTF-8. `07 § Read-only views` says a `[]T` is accepted wherever a
    // `[]const T` is wanted, and a receiver is such a place; the lookup used to file the two under
    // the names a diagnostic gives them and deny the member outright.
    "a writable byte view reaches them too, since it is the same type with a bit" in {
      run("""var raw = [104u8, 105u8, 33u8]
            |var needle = [105u8, 33u8]
            |print(raw[..].contains(needle[..]), raw[..].index_of(needle[..]).unwrap())
            |print(raw[..].starts_with([104u8][..]), raw[..].is_empty())""".stripMargin) shouldBe
        "true 1\ntrue false\n"
    }

    "and a trim of one still answers, through the same widening" in {
      run("""var raw = [32u8, 104u8, 105u8, 32u8]
            |print(raw[..].trim().len, raw[..].len)""".stripMargin) shouldBe "2 4\n"
    }
  }

  "splitting" - {

    "yields one more piece than there are separators" in {
      run("""for p in split("a,b,c", ",") do print(f"[$p%s]")""") shouldBe "[a]\n[b]\n[c]\n"
    }

    // Nothing is silently dropped: adjacent separators and separators at the ends all produce the
    // empty pieces they imply, which is what makes the count predictable.
    "including the empty pieces that adjacent and edge separators imply" in {
      run("""for p in split(",a,,b,", ",") do print(f"[$p%s]")""") shouldBe
        "[]\n[a]\n[]\n[b]\n[]\n"
    }

    "a separator that is not there leaves one piece" in {
      run("""var ps = split("abc", ",")
            |print(ps.len, ps[0])""".stripMargin) shouldBe "1 abc\n"
    }

    "a multi-byte separator works like any other" in {
      run("""for p in split("a::b::c", "::") do print(f"[$p%s]")""") shouldBe "[a]\n[b]\n[c]\n"
    }

    // Splitting on nothing has no meaningful byte-level answer for text — it would cut multi-byte
    // characters apart — and the character-level reading is what `s.chars` already is.
    "an empty separator yields the whole string rather than its bytes" in {
      run("""var ps = split("abc", "")
            |print(ps.len, ps[0])""".stripMargin) shouldBe "1 abc\n"
    }

    "an empty string splits to one empty piece" in {
      run("""var ps = split("", ",")
            |print(ps.len, ps[0].is_empty())""".stripMargin) shouldBe "1 true\n"
    }
  }

  "fields is not split on a space" - {

    // The difference in one test: a run of whitespace separates two fields rather than producing
    // empty ones between them, and the edges produce nothing at all.
    "runs of whitespace separate, and the edges produce nothing" in {
      run("""for f in fields("  one   two\tthree  ") do print(f"[$f%s]")""") shouldBe
        "[one]\n[two]\n[three]\n"
    }

    "where split would have produced empties" in {
      run("""print(fields("a  b").len, split("a  b", " ").len)""") shouldBe "2 3\n"
    }

    "nothing but whitespace has no fields at all" in {
      run("""print(fields("   ").len, fields("").len)""") shouldBe "0 0\n"
    }
  }

  "joining" - {

    "is split's inverse for a separator split would have found" in {
      run("""print(join(split("a,b,c", ","), ","))
            |print(join(split("a,b,c", ","), " - "))""".stripMargin) shouldBe "a,b,c\na - b - c\n"
    }

    "one piece needs no separator, and none needs nothing" in {
      run("""print(f"[${join(["solo"], ",")}%s]", f"[${join([], ",")}%s]")""") shouldBe "[solo] []\n"
    }

    "an empty separator lays the pieces end to end" in {
      run("""print(join(["a", "b", "c"], ""))""") shouldBe "abc\n"
    }
  }

  "repeating" - {

    "lays down the text n times" in {
      run("""print(repeat("ab", 3usize), f"[${repeat("ab", 1usize)}%s]")""") shouldBe "ababab [ab]\n"
    }

    "zero times, and of nothing, is nothing" in {
      run("""print(repeat("ab", 0usize).is_empty(), repeat("", 5usize).is_empty())""") shouldBe
        "true true\n"
    }
  }

  "replacing" - {

    "substitutes every occurrence, whether the replacement is longer or shorter" in {
      run("""print(replace_all("a.b.c", ".", "->"), replace_all("a->b->c", "->", "."))""") shouldBe
        "a->b->c a.b.c\n"
    }

    "and leaves a string with no occurrence exactly as it was" in {
      run("""print(replace_all("abc", "z", "!"), replace_all("ab", "abc", "!"))""") shouldBe "abc ab\n"
    }

    // The scan continues after what was substituted, so a replacement containing the pattern does
    // not feed on itself — which is the loop an implementation restarting from the same place hangs
    // in.
    "a replacement containing the pattern does not feed on itself" in {
      run("""print(replace_all("aa", "a", "aa"))""") shouldBe "aaaa\n"
    }

    "non-overlapping, so the count of replacements matches count_of" in {
      run("""print(replace_all("aaa", "aa", "-"), "aaa".count_of("aa"))""") shouldBe "-a 1\n"
    }

    // Everywhere would insert between every pair of bytes, which for text cuts characters apart.
    "an empty pattern matches nowhere rather than everywhere" in {
      run("""print(replace_all("abc", "", "-"))""") shouldBe "abc\n"
    }
  }

  "case conversion" - {

    "converts the ASCII letters and nothing else" in {
      run("""print(to_upper("hello, world! 42"), to_lower("HELLO, WORLD! 42"))""") shouldBe
        "HELLO, WORLD! 42 hello, world! 42\n"
    }

    // The property that makes it safe to do at all: only bytes below 128 change, and those are
    // never part of a multi-byte sequence — so a character outside ASCII is re-encoded as itself.
    "a character outside ASCII passes through untouched" in {
      run("""print(to_upper("héllo"), to_lower("HÉLLO"))""") shouldBe "HéLLO hÉllo\n"
    }

    // Stronger than comparing the text: the result is a `string` like any other, so it still walks
    // as the same characters and has the same byte length it started with.
    "and what comes out is still well-formed, with its widths intact" in {
      run("""var s = "héllo-→-𝄞"
            |var u = to_upper(s)
            |var n = 0
            |for _ in u.chars do n += 1
            |print(u, n, u.len == s.len)""".stripMargin) shouldBe "HéLLO-→-𝄞 9 true\n"
    }

    "an empty string converts to an empty string" in {
      run("""print(to_upper("").is_empty(), to_lower("").is_empty())""") shouldBe "true true\n"
    }
  }
}
