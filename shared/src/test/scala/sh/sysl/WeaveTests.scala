package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl weave`: a literate source rendered as the document it already is (`Weave`).
 *
 * **The load-bearing assertion here is a round trip, not a golden file.** What could go wrong with a
 * renderer is that it loses a line of the program or invents one — a block whose last line ended up
 * outside the code, a blank line that ended one early, an illustration that got swept in. A golden
 * document would catch all of that and would also break on every edit to the prose it happens to
 * quote, so what is asserted instead is the property: **the code in the rendered document is exactly
 * the program the compiler reads, in order.**
 *
 * It is asked of the two real literate guides and of the five-file `sysl.regex` as well as of the
 * small cases, because the small cases are the ones somebody thought of.
 */
class WeaveTests extends AnyFreeSpec with Matchers {

  private def source(text: String): Source = Source(s"weave${Literate.Extension}", text)

  private def rendered(text: String): String =
    Weave.render(source(text)) match
      case Right(out) => out
      case Left(err)  => fail(err)

  /** The program a literate source holds, blank lines dropped — what the compiler is handed. */
  private def program(src: Source): List[String] =
    Literate.tangle(src) match
      case Right(out) => out.lines.filter(_.trim.nonEmpty).toList
      case Left(err)  => fail(err)

  /** The code inside the document's sysl blocks, blank lines dropped, with the highlighter's markup
   * taken back off.
   *
   * **Written out here rather than shared with `Weave` on purpose**: a helper that used the
   * renderer's own idea of where a code block begins would agree with it by construction, and
   * agreeing by construction is what a round trip exists to rule out.
   */
  private def coded(document: String): List[String] = {
    val open  = s"""<pre><code class="language-${Weave.Language}">"""
    val out   = collection.mutable.ArrayBuffer.empty[String]
    var rest  = document
    var found = 0

    while rest.contains(open) do {
      val body = rest.drop(rest.indexOf(open) + open.length)
      val end  = body.indexOf("</code></pre>")

      end should be >= 0
      found += 1
      out ++= body.take(end).linesIterator.map(strip).filter(_.trim.nonEmpty)
      rest = body.drop(end)
    }

    found should be > 0
    out.toList
  }

  /** A line of rendered code read back as the program wrote it: spans removed, entities undone.
   *
   * The entities are undone **last**, so that a program which itself contains `&lt;` is not turned
   * into one containing `<` by a step meant to undo the escaping of a different character.
   */
  private def strip(line: String): String = {
    val bare = line.replaceAll("<[^>]*>", "")

    bare.replace("&quot;", "\"").replace("&#39;", "'")
      .replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
  }

  /** A driver run with both streams caught, asserted to have succeeded, and its stdout handed back.
   *
   * `sh.sysl.execute` in full, because `Suite` has an `execute` of its own and it is the one that
   * wins unqualified.
   */
  private def ran(cfg: Config): String = {
    val out    = new java.io.ByteArrayOutputStream
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(out)(Console.withErr(notes)(sh.sysl.execute(cfg)))

    withClue(s"${out.toString}${notes.toString}")(status shouldBe 0)
    out.toString
  }

  /** The same run when it is expected to be refused: the status, and what it said about it. */
  private def refused(cfg: Config): String = {
    val notes  = new java.io.ByteArrayOutputStream
    val status = Console.withOut(new java.io.ByteArrayOutputStream)(
      Console.withErr(notes)(sh.sysl.execute(cfg)))

    withClue(notes.toString)(status should not be 0)
    notes.toString
  }

  private def roundTrips(src: Source): Unit =
    Weave.render(src) match
      case Left(err)       => fail(s"${src.name} did not render: $err")
      case Right(document) => withClue(s"${src.name}: ")(coded(document) shouldBe program(src))

  "the program is rendered as code and the prose as prose" in {
    // The whole of what the command does, on the smallest file that shows it. The four columns are
    // gone -- they were Markdown's way of saying "code" -- and the block carries the language they
    // could not, which is the one thing the format gives up.
    val out = rendered("# The greeting\n\nA program of one statement.\n\n    print(\"hi\")\n")

    out should include("<h1 id=\"the-greeting\">The greeting</h1>")
    out should include("<p>A program of one statement.</p>")
    out should include(s"""<pre><code class="language-${Weave.Language}">""")
    coded(out) shouldBe List("print(\"hi\")")
  }

  "a blank line between two program lines stays inside one block" in {
    // Consecutive indented lines are one block whether or not there is air between them
    // (`Literate`), so a function with a paragraph break in it is one function -- and has to render
    // as one block rather than as two.
    val out = rendered("Text\n\n    a()\n\n    b()\n")

    out.sliding(s"""class="language-${Weave.Language}"""".length)
      .count(_ == s"""class="language-${Weave.Language}"""") shouldBe 1
    coded(out) shouldBe List("a()", "b()")
  }

  "the source reaches the renderer verbatim, so its Markdown is its own" in {
    // Not a formatter and no opinion about anybody's Markdown: a table, an emphasis and a link are
    // the renderer's business and nothing here touches them on the way past.
    val out = rendered("A *word* and a [link](https://sysl.sh).\n\n| a | b |\n|---|---|\n| 1 | 2 |\n")

    out should include("<em>word</em>")
    out should include("""<a href="https://sysl.sh">link</a>""")
    out should include("<table>")
  }

  "mathematics reaches the reader as mathematics" in {
    // The delimiters are what KaTeX's auto-render reads. They are asserted rather than assumed
    // because they are the contract between the renderer and the script the page links: a change to
    // either that the other did not hear about leaves the equations set as TeX source.
    val out = rendered("The area is $\\pi r^2$, and:\n\n$$\nE = mc^2\n$$\n")

    out should include("""<span class="math inline">\(\pi r^2\)</span>""")
    out should include("""<div class="math display">\[E = mc^2\]</div>""")
    out should include("katex.min.js")
    out should include("renderMathInElement")
  }

  "the code is coloured by the grammar the site uses" in {
    // The spans are what the document's own stylesheet colours, which is why a reader needs no
    // JavaScript for code -- the runtime dependency is mathematics and only mathematics.
    val out = rendered("Text\n\n    // a note\n    val x = \"hi\"\n")

    out should include(s"""<span class="hl-keyword">val</span>""")
    out should include(s"""<span class="hl-string">""")
    out should include(s"""<span class="hl-comment">// a note</span>""")
  }

  "a grammar that would not parse would cost the colouring and not the document" in {
    // The grammar is compiled in and reconciled against the lexer by `GrammarTests`, so this cannot
    // happen in a shipped build. It is pinned because the alternative to degrading is refusing to
    // write a document over the styling of its code, and that trade is not close.
    Grammar.sysl should not be empty
    io.github.edadma.highlighter.Highlighter.fromJson(Grammar.sysl) shouldBe Symbol("right")
  }

  "the document carries its own styling and stands on its own" in {
    val out = rendered("# Title\n\n    print(\"hi\")\n")

    out should startWith("<!doctype html>")
    out should include("<meta charset=\"utf-8\">")
    out should include("<style>")
    // A leaf artifact: something to open, move or mail, rather than a directory that has to stay
    // together.
    out should not include "<link rel=\"stylesheet\" href=\"style.css\">"
  }

  "both palettes answer the same names" in {
    // A colour is named once and the dark palette re-answers it, so the rules say what a thing is
    // rather than what colour it is and the two cannot drift into disagreeing.
    val out = rendered("Text\n\n    print(\"hi\")\n")

    out should include("prefers-color-scheme: dark")

    for name <- List("--bg", "--fg", "--code-bg",
                     "--hl-keyword", "--hl-string", "--hl-comment", "--hl-number",
                     "--hl-type", "--hl-function", "--hl-variable", "--hl-punctuation") do
      withClue(s"$name is defined in both palettes: ")(
        out.sliding(name.length + 1).count(_ == s"$name:") shouldBe 2)
  }

  "every class the highlighter can emit is one the stylesheet colours" in {
    // The two lists are written in different repositories' worth of thinking -- the categories are
    // `highlighter`'s and the rules are this file's -- so a category added there would otherwise
    // render as unstyled text and nothing would say so.
    val out = rendered("Text\n\n    print(\"hi\")\n")

    for category <- List("keyword", "string", "comment", "number",
                         "type", "function", "variable", "punctuation") do
      withClue(s"hl-$category is styled: ")(out should include(s".hl-$category"))
  }

  "the round trip holds" - {
    "for the guide programs written this way" in {
      for name <- List("guide/lisp/lisp", "guide/slab/slab") do
        val path = s"$name${Literate.Extension}"

        assume(isFile(path), s"$path is not reachable from the test working directory")
        roundTrips(Source(path, readFile(path)))
    }

    "for the library module written this way" in {
      // `sysl.regex` is five literate files, so this is also the multi-file case: every one of them
      // is rendered and every one has to hold its own program.
      val root = StdRoot.root

      assume(root.isDefined, "the library is not reachable from the test working directory")

      val files = Project.collect(s"${root.get}/sysl/regex").filter(s => Literate.named(s.name))

      files should not be empty
      files.foreach(roundTrips)
    }
  }

  "an ordinary program has no prose to render" in {
    // Refused rather than rendered as a document with nothing in it: a `.sysl` file is all code, so
    // the request cannot be granted however it is read.
    Weave.render(Source("plain.sysl", "print(\"hi\")\n")) match
      case Right(_)  => fail("an ordinary program was woven")
      case Left(err) => err should include("ordinary sysl program")
  }

  "a file the compiler refuses is not woven" in {
    // The reading is shared with a compilation on purpose: a document that wove cleanly out of a
    // file the compiler refuses would be documentation of a program that does not exist.
    Weave.render(source("Text\n\n\tprint(\"hi\")\n")) match
      case Right(_) => fail("a file the compiler refuses was woven")
      case Left(_)  => succeed
  }

  "a document is named for the source it came from" in {
    Weave.documentName(s"slab${Literate.Extension}") shouldBe "slab.html"
    // Its directories are dropped, so a nested tree lands flat rather than rebuilding itself under
    // the output directory.
    Weave.documentName(s"guide/slab/slab${Literate.Extension}") shouldBe "slab.html"
  }

  "the driver writes what the renderer rendered" - {
    "to standard output when there is nowhere else" in {
      val path = s"guide/slab/slab${Literate.Extension}"

      assume(isFile(path), s"$path is not reachable from the test working directory")

      val out = ran(Config(command = "weave", file = path))

      out should startWith("<!doctype html>")
      coded(out) shouldBe program(Source(path, readFile(path)))
    }

    "to a file when it is asked to" in {
      val path = s"guide/slab/slab${Literate.Extension}"

      assume(isFile(path), s"$path is not reachable from the test working directory")

      val out = s"${createTempDirectory("sysl-weave-")}/slab.html"

      ran(Config(command = "weave", file = path, output = Some(out)))
      readFile(out) should include(s"""class="language-${Weave.Language}"""")
    }

    "one document per source when the path holds several" in {
      // A woven document is a leaf artifact somebody opens, so the unit is the file that was
      // written -- a tree rendered end to end would put five modules under one title.
      val root = StdRoot.root

      assume(root.isDefined, "the library is not reachable from the test working directory")

      val dir     = s"${createTempDirectory("sysl-weave-tree-")}/regex"
      val sources = Project.collect(s"${root.get}/sysl/regex").filter(s => Literate.named(s.name))

      ran(Config(command = "weave", file = s"${root.get}/sysl/regex", output = Some(dir)))

      sources.length should be > 1

      // Every source got its own document, and each one is a whole page rather than a fragment.
      for src <- sources do
        val document = s"$dir/${Weave.documentName(src.name)}"

        withClue(s"${src.name} -> $document: ")(isFile(document) shouldBe true)
        readFile(document) should startWith("<!doctype html>")
    }

    "and refuses to put several documents on one stream" in {
      // They cannot be told apart there, and concatenating them would make one file with several
      // `<html>` elements in it.
      val root = StdRoot.root

      assume(root.isDefined, "the library is not reachable from the test working directory")

      refused(Config(command = "weave", file = s"${root.get}/sysl/regex")) should
        include("give '-o' a directory")
    }

    "and refuses a tree with no literate source in it" in {
      val dir = createTempDirectory("sysl-weave-plain-")

      writeFile(s"$dir/main.sysl", "main() = print(1)\n")
      refused(Config(command = "weave", file = dir)) should include(Literate.Extension)
    }
  }
}
