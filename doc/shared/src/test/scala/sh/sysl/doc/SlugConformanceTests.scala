package sh.sysl.doc

import io.github.edadma.cross_platform.*
import io.github.edadma.juicer.slugifyFor
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import sh.sysl.*

/** The anchors a generated page links to, against the ids the renderer actually emits.
 *
 * **`MarkdownWriter` writes a link and juicer writes the id it lands on, and until this suite existed
 * nothing compared the two.** The generator's `slug` and juicer's `githubSlugify` are separate
 * implementations of GitHub's rule in separate repositories on separate release cycles; so are the
 * generator's `anchors` and juicer's `dedupeHeadingIds`, which give a repeated heading its `-1`. Four
 * functions, two of them subtle, and a one-character divergence in any of them sends every link on
 * every generated page to the top of the right page with no complaint from anything. Card `0286`.
 *
 * **This is the only place in the org where both sides are on one classpath.** `MarkdownWriter` lives
 * in the compiler, which carries no dependency of its own; `juicer-core` is `sysl-doc`'s. A test here
 * needs nothing new anywhere and fails the moment somebody bumps the juicer version in `build.sbt` to
 * one whose answer has moved — which is the drift that had nothing watching it.
 *
 * **The second half renders rather than calling.** juicer's numbering is `private[juicer]` with one
 * caller inside its own build pipeline, so it cannot be invoked directly at all. It can be *reached*,
 * through the front door: a minimal site — `site.toml`, one content page, one layout — built by
 * `App.build`, with the ids read back out of the HTML. That also makes the assertion the honest one,
 * since the reader's link lands on what the renderer emitted and not on what a function returned.
 *
 * **`run-gate.sh` cannot see `doc/shared/src/test/`**, so a green Native gate says nothing about this
 * file. It runs under `sbt -batch syslDocJVM/test`, as the sixteen `DocCliTests` beside it do.
 */
class SlugConformanceTests extends AnyFreeSpec with Matchers {

  /** juicer's slugger, reached the way a page reaches it: by the name the frontmatter says.
   *
   * `slugifyFor` rather than `githubSlugify` on purpose — `slugStyle: github` is a *string* in every
   * generated page's frontmatter, and the lookup that turns it into a function is as much a part of
   * the contract as the loop it selects. A juicer that renamed the style would still pass a test that
   * called the function directly.
   */
  private val rendererSlug: String => String = slugifyFor("github")

  "the slug function agrees with the renderer's" - {

    "on the shapes a generated heading actually has" in {
      val cases =
        List(
          "buf",
          "Buf",
          "parse_int",
          "starts_with",
          "Buf[T]",
          "[N]T",
          "Display for Vec2",
          "Iterator[T] for Chars",
          "sysl.text",
          "from_utf8_lossy",
          "u8",
          "Index & IndexMut",
          "a  b",
          "-leading-and-trailing-",
          "Grid Cell",
          "",
        )

      for c <- cases do withClue(s"heading '$c': ") { MarkdownWriter.slug(c) shouldBe rendererSlug(c) }
    }

    "on every character below U+0300, alone and inside a word" in {
      // A sweep rather than a list, because the hazard is a divergence nobody thought to write a case
      // for — collapsing runs of spaces, trimming a trailing hyphen, keeping a dot. Every one of those
      // is what most slug implementations do and what GitHub deliberately does not.
      for cp <- 0x20 to 0x2ff do {
        val ch = cp.toChar

        withClue(f"U+$cp%04X alone: ") { MarkdownWriter.slug(ch.toString) shouldBe rendererSlug(ch.toString) }

        val word = s"a${ch}b"

        withClue(f"U+$cp%04X in 'a${ch}b': ") { MarkdownWriter.slug(word) shouldBe rendererSlug(word) }
      }
    }
  }

  "the anchors on a generated page are the ids the renderer emits" - {

    /** A module whose headings exercise both algorithms at once.
     *
     * `buf()` and `Buf` case-fold to one slug, which is the ordinary repeat, and the two `parse_int`
     * declarations are a real overload — which is why the generator answers a list rather than a map,
     * since a map keyed by heading text holds one entry for both.
     *
     * **The backticked names are what reach the walk-forward rule, and getting them in the wrong
     * order silently does not.** Both implementations carry a comment about a document holding `buf`
     * twice *and* a literal `buf-1`, and both walk past an already-taken candidate rather than
     * trusting the counter. That branch is only entered when the FIRST candidate is taken, so the
     * page needs a heading slugging to `buf-1` **before** the pair that collides on `buf`: `` `buf 1`
     * `` is a const, which is the group a page lists first, so `Buf` has to walk from `buf-1` to
     * `buf-2`. Written the other way round every heading resolves on its first try, and deleting the
     * loop from either implementation leaves this suite green — measured, before the order was fixed.
     *
     * The struct of the same name then lands on `buf-1-1`, which is the suffix applied to a slug
     * that already carries one.
     */
    val source =
      """module probe
        |
        |/** How many a fresh buffer holds. */
        |const `buf 1`: int = 1
        |
        |/** Make an empty buffer. */
        |buf() -> int = 1
        |
        |/** Read an int from a string. */
        |parse_int(s: string) -> int = 1
        |
        |/** Read an int from bytes. */
        |parse_int(s: []const u8) -> int = 2
        |
        |/** A growable buffer. */
        |struct Buf
        |    n: usize
        |
        |/** Two words with two spaces between them. */
        |struct `a  b`
        |    n: usize
        |
        |/** A point. */
        |struct Vec2
        |    x: real
        |    y: real
        |
        |/** One cell of a grid. */
        |struct `buf 1`
        |    n: usize
        |
        |impl Display for Vec2
        |    display(self) -> string = "v"
        |""".stripMargin

    val page = {
      val unit =
        SyslParser.parse(source) match
          case Right(p) => p
          case Left(e)  => fail(e)

      val ms = ApiModel.build(List(unit))

      ms.length shouldBe 1
      MarkdownWriter.modulePage(ms.head)
    }

    /** The anchors the page links to, in the order its index lists them. */
    val linked = """\]\(#([^)]*)\)""".r.findAllMatchIn(page.text).map(_.group(1)).toList

    "the page links to the anchors this case was built to produce" in {
      // Pinned rather than merely compared, so that a change to the generator's own numbering is a
      // failure here and not a pair of matching wrong answers.
      linked shouldBe
        List("buf-1", "buf", "parse_int", "parse_int-1", "a--b", "buf-2", "buf-1-1", "vec2",
          "display-for-vec2")
    }

    "the renderer gives those headings exactly those ids" in {
      val site = s"target/slug-conformance/${page.path.stripSuffix(".md")}"

      Project.discard(site)
      Project.makeDirectories(s"$site/content")
      Project.makeDirectories(s"$site/layouts/_default")

      writeFile(s"$site/site.toml", "title = \"slug conformance\"\n")
      writeFile(s"$site/content/_index.md", "---\ntitle: home\n---\n\nprobe\n")
      writeFile(s"$site/content/${page.path}", page.text)

      // The generated page asks for `api-module`, which is a theme's layout and not juicer's. Both
      // templates are the smallest thing that renders a body, since nothing here is about the theme.
      val template = "<html><body>{{ .content }}</body></html>\n"

      writeFile(s"$site/layouts/_default/folder.html", template)
      writeFile(s"$site/layouts/_default/api-module.html", template)

      SiteRenderer.build(s"$site/content", site)

      val html = rendered(s"$site/public", page.path.stripSuffix(".md"))
      val ids  = """<h3[^>]*\bid="([^"]*)"""".r.findAllMatchIn(html).map(_.group(1)).toList

      // `<h3` and not `<h[1-6]` because a symbol heading is the page's `###` and the group headings
      // above it are `##`. Taking only the level the links point at makes this an EQUALITY rather
      // than a containment — a renderer that emitted a different id would otherwise merely be
      // filtered out of the comparison, which is the weaker claim.
      ids shouldBe linked
    }

    /** The HTML juicer wrote for one content page, wherever its URL layout put it. */
    def rendered(public: String, stem: String): String = {
      val candidates = List(s"$public/$stem/index.html", s"$public/$stem.html")

      candidates.find(isFile).map(readFile).getOrElse {
        fail(s"no rendered page for '$stem' — looked at ${candidates.mkString(", ")}")
      }
    }
  }
}
