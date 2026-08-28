package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The syntax-highlighting grammar, checked against the lexer it is supposed to describe.
 *
 * `grammars/sysl.tmLanguage.json` is a second, hand-written statement of which words are keywords,
 * and nothing makes the two agree. A word added to `SyslLexical.reserved` is a keyword the compiler
 * honours and every renderer sets as an ordinary identifier — which looks like a line that simply has
 * little to highlight rather than like a fault, so it survives being looked at. `ref` did exactly
 * that: reserved, taught in the tour, and unstyled on the published page.
 *
 * **This test lives here because `SyslLexical` does.** The grammar was in `sysl.sh` until `weave`
 * needed it, and the arrangement had the test able to see the grammar but not the lexer it is a claim
 * about — it worked only because the site's suite happened to depend on the compiler. The site now
 * fetches the grammar the same way its CI already fetches the library.
 *
 * **What is checked is the compiled-in constant rather than the file**, so what the assertions are
 * about is the grammar that actually ships inside `weave` — a file read off disk could be right while
 * a stale generated constant shipped beside it.
 *
 * Both directions are checked against the grammar's **`keyword` section only**. The predeclared
 * scalars are styled from a section of their own and are deliberately *not* reserved words — that is
 * what lets the open `iN` / `uN` families need no lexical support — so a check spanning the whole
 * file would have to allow them and would stop meaning anything.
 */
class GrammarTests extends AnyFreeSpec with Matchers {

  /** One entry of the grammar's repository, as text. */
  private def section(name: String): String = {
    val text  = Grammar.sysl
    val start = text.indexOf(s"\"$name\": {")

    withClue(s"the grammar has no '$name' section\n") { start should be >= 0 }

    val end = text.indexOf("\n    },", start)

    end should be > start

    text.substring(start, end)
  }

  /** Every regex that section styles with, concatenated, with the regex escapes blanked out.
    *
    * Blanking matters: the patterns arrive still escaped as they appear in the JSON, so `self` is
    * written `\\bself\\b` and is preceded by the *letter* `b`. Any word-boundary reasoning done
    * against the raw text is reasoning about backslashes, and quietly finds nothing.
    *
    * `comment` and `name` fields are left out, because the comment beside a keyword group nearly
    * always names the very words in it — which is exactly how this kind of test passes by accident.
    */
  private def styledIn(sectionText: String): Set[String] = {
    val field = """"(?:match|begin|end)"\s*:\s*"((?:[^"\\]|\\.)*)"""".r
    val text = field
      .findAllMatchIn(sectionText)
      .map(_.group(1))
      .mkString(" ")
      .replaceAll("""\\\\.""", " ")

    """[a-z_]{2,}""".r.findAllMatchIn(text).map(_.matched).toSet
  }

  /** The `match` patterns of a section, compiled — which is the other question this file can ask of
    * the grammar and did not until 2026-08-28: whether a pattern **matches** what it claims to,
    * rather than which words appear inside one.
    *
    * The JSON holds each one doubly escaped, so `\\b` in the file is `\b` in the pattern, and one
    * `replace` is the whole of the decoding. Compiled with `java.util.regex`, which is what juicer
    * uses for a TextMate grammar — so `\p{L}` means here what it means on the published page.
    */
  private def patterns(name: String): List[scala.util.matching.Regex] = {
    val field = """"match"\s*:\s*"((?:[^"\\]|\\.)*)"""".r

    field.findAllMatchIn(section(name)).map(m => m.group(1).replace("\\\\", "\\").r).toList
  }

  /** Whether this runtime's regex engine understands a lookaround, which Scala Native's does not.
    *
    * The grammar's boundaries are written out as lookarounds rather than as `\b`, because
    * `java.util.regex` reads `\b` over ASCII word characters — so `\bÁrbol` has no boundary to
    * match at and a widened character class would style nothing. That is right for the engine the
    * site renders with and unrepresentable in RE2.
    */
  private lazy val lookaroundCompiles: Boolean =
    try { "(?!x)a".r.pattern; true }
    catch { case _: Exception => false }

  private lazy val asKeyword: Set[String] = styledIn(section("keyword"))

  /** `true`, `false` and `null` are reserved words that the grammar styles as constants rather than
    * as keywords, which is the right call — they are values, and colouring them like `if` would say
    * something false about them. So a reserved word is satisfied by either section.
    */
  private lazy val asConstant: Set[String] = styledIn(section("constant"))

  /** Words the grammar styles as keywords without the lexer reserving them. They are keywords only
    * where the grammar expects one and ordinary identifiers everywhere else, so `SyslLexical` never
    * sees them. Adding to this set is a decision about the language, not a way to fix a red test.
    *
    * **`opaque` and `derives` are here because the parser reads them with `softWord`**, in
    * `DeclParser` — so the decision was taken when they were written, and the grammar catching up
    * with it (`71436553`) is what left this list behind. That commit brought `grammars/` into line
    * with the site's copy and turned this half of the reconciliation red on dev; the words are soft
    * keywords in fact, so the list is what was wrong.
    *
    * **`become` joined them in 0.0.86 and the same thing happened again**, which is what makes this
    * a pattern rather than one stale line. `StmtParser.becomeStmt` reads the word with `softWord`,
    * so it is soft by construction — a variable and a function may both still be called `become`.
    * The grammar was taught to colour it and this list was not, and the two are in different files
    * *and different repositories*: `sysl.sh` carries a copy of the grammar with a `soft` set of its
    * own, so a word can be right in three places and wrong in the fourth. Adding a soft keyword
    * means editing the grammar here, this set, and the site's `GrammarTests` — and CI diffs the two
    * grammar files against each other, so the copy is the half that announces itself.
    */
  private val soft =
    Set("is", "not", "invariant", "new", "within", "where", "with", "opaque", "derives", "become")

  "the highlighting grammar" - {

    "is the one the compiler carries" in {
      // The constant is generated from the file by the build, so an edit to the grammar that never
      // reached a rebuild would otherwise be invisible to everything below.
      Grammar.sysl should include("source.sysl")
      Grammar.sysl should include("\"keyword\": {")
    }

    "styles every word the lexer reserves" in {
      val styled  = asKeyword ++ asConstant
      val missing = new SyslLexical().reserved.toList.sorted.filterNot(styled)

      withClue(s"reserved by SyslLexical but unstyled by the grammar: ${missing.mkString(", ")}\n") {
        missing shouldBe empty
      }
    }

    // The disagreement half. Without it the test above would pass on a grammar whose keyword section
    // had been replaced by one alternation listing every lowercase word imaginable — which would
    // style correctly and prove nothing — and it is also what notices a word going the other way,
    // when the lexer stops reserving something the grammar goes on colouring.
    "and styles nothing else, beyond the soft keywords" in {
      val unknown = (asKeyword -- new SyslLexical().reserved -- soft).toList.sorted

      withClue(s"styled as a keyword by the grammar but neither reserved nor soft: " +
        s"${unknown.mkString(", ")}\n") {
        unknown shouldBe empty
      }
    }

    // What `weave` actually does with it. The reconciliation above is about the word list; this is
    // about the file being a grammar at all, which no amount of agreement with the lexer implies.
    "and parses as one" in {
      io.github.edadma.highlighter.Highlighter.fromJson(Grammar.sysl) match
        case Left(err) => fail(s"the grammar does not parse: $err")
        case Right(highlighter) =>
          highlighter.highlight("val x = 1") should include("""<span class="hl-keyword">val</span>""")
    }
    // **THE PATTERNS BELOW CANNOT BE COMPILED ON EVERY PLATFORM THIS SUITE RUNS ON, AND THAT IS A
    // FACT ABOUT THE RUNTIME RATHER THAN ABOUT THE GRAMMAR.** Scala Native's `Regex` is RE2, which
    // has no lookaround at all — `(?![\p{L}\p{Nd}_])` is refused as an *"Unknown inline modifier"*,
    // which reads as a malformed pattern and is a missing feature.
    //
    // The claim is about `java.util.regex` specifically: that is what juicer compiles a TextMate
    // grammar with, so it is the engine the published page is rendered by and the only one this is
    // about. Asked as a **capability** rather than as a platform, so the message says what is
    // missing rather than which build it is.
    // **The reconciliation above is about WORDS, and the grammar also carries identifier PATTERNS
    // that nothing checked.** A pattern is an ASCII character class in a language whose identifiers
    // are Unicode's letters (`reference/lexical.md § Identifiers`), and what that produces is a page
    // where `struct Círculo` renders as an unstyled word — a thing that looks like a line with
    // little to highlight rather than like a fault, which is the same failure the reserved-word half
    // exists for, one construct over.
    //
    // Asserted against `java.util.regex`, which is what juicer compiles these with.
    "matches a declaration, a call and a type whose name is not ASCII" in {
      // Cancelled rather than skipped, and named: RE2 has no lookaround, and the two cases above
      // are unaffected and still run here.
      if !lookaroundCompiles then
        cancel("this runtime's regex engine is RE2 (Scala Native), which has no lookaround — the " +
          "grammar is rendered by java.util.regex, so the JVM run is where this claim is checked")

      // **What is asserted is the WHOLE name and not that something matched**, which is the
      // difference between a real check and one that cannot fail here. An ASCII class matches
      // `struct C` and stops at the accent, so a `findFirstIn` answers `Some` on a pattern that
      // styles one letter of the name — checked by reverting each class and watching this go red.
      def spans(section: String, sample: String, name: String): Boolean =
        patterns(section).exists(_.findFirstMatchIn(sample).exists(_.matched.endsWith(name)))

      spans("declaration", "struct Círculo", "Círculo") shouldBe true
      spans("declaration", "end Círculo", "Círculo") shouldBe true
      spans("declaration", "área(ancho: real)", "área") shouldBe true
      spans("call", "área(3.0)", "área") shouldBe true
      spans("type", "Círculo", "Círculo") shouldBe true
    }

    // The other direction, so the classes above cannot be widened into matching anything at all: a
    // digit still does not begin a name, in any script.
    "and still refuses a name beginning with a digit" in {
      // Cancelled rather than skipped, and named: RE2 has no lookaround, and the two cases above
      // are unaffected and still run here.
      if !lookaroundCompiles then
        cancel("this runtime's regex engine is RE2 (Scala Native), which has no lookaround — the " +
          "grammar is rendered by java.util.regex, so the JVM run is where this claim is checked")

      val started =
        patterns("declaration").exists(_.findFirstMatchIn("struct 3café").exists(_.matched.contains("3")))

      started shouldBe false
    }
  }
}
