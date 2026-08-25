package sh.sysl.doc

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers
import sh.sysl.*

/** The API-reference generator: signatures rendered back out of the AST, the module model built from
 * parsed units, and the Markdown a page is written as.
 *
 * **Every assertion here is on parsed source rather than on hand-built trees.** A generator's whole
 * job is to be right about what somebody actually wrote, so a test that constructed a `FuncDecl`
 * directly would be checking this file's idea of the AST against itself. Parsing is also what proves
 * the claim the model rests on — that nothing here needs the analyzer.
 */
class DocGeneratorTests extends AnyFreeSpec with Matchers {

  private def parse(src: String): Program =
    SyslParser.parse(src) match
      case Right(p) => p
      case Left(e)  => fail(e)

  private def modules(src: String, includePrivate: Boolean = false): List[ApiModel.Module] =
    ApiModel.build(List(parse(src)), includePrivate)

  private def only(src: String): ApiModel.Module = {
    val ms = modules(src)

    ms.length shouldBe 1
    ms.head
  }

  /** The Markdown of the one module in `src`. */
  private def page(src: String): String = MarkdownWriter.modulePage(only(src)).text

  /** The rendered signature of the one declaration in `src`. */
  private def sig(src: String): String = {
    val syms = only(s"module m\n\n$src").symbols

    syms.length shouldBe 1
    syms.head.signature
  }

  "a signature is rendered back out of the AST" - {

    "a function keeps its parameters, its result and its order" in {
      sig("add(a: int, b: int) -> int = a + b") shouldBe "add(a: int, b: int) -> int"
    }

    "a procedure has no arrow, because sysl does not write one" in {
      // Rendering `-> ()` would document a spelling the language does not have.
      sig("greet(name: string)\n    print(name)") shouldBe "greet(name: string)"
    }

    "a slice, a read-only slice and a fixed array are three different types" in {
      sig("f(a: []u8, b: []const u8, c: [4]u8)\n    print(1)") shouldBe
        "f(a: []u8, b: []const u8, c: [4]u8)"
    }

    "a pointer, a reference and a sync reference each keep their sigil" in {
      sig("f(a: *u8, b: &int, c: &sync int)\n    print(1)") shouldBe "f(a: *u8, b: &int, c: &sync int)"
    }

    "a generic function renders its type parameters and their bounds" in {
      sig("show[T: Display](x: T) -> string = \"\"") shouldBe "show[T: Display](x: T) -> string"
    }

    "two bounds on one parameter are joined with +" in {
      sig("f[T: Display + Eq](x: T)\n    print(1)") shouldBe "f[T: Display + Eq](x: T)"
    }

    "a value parameter states its type where a type parameter states its bounds" in {
      sig("f[const N: usize](xs: [N]u8)\n    print(1)") shouldBe "f[const N: usize](xs: [N]u8)"
    }

    "an applied generic type keeps its arguments" in {
      sig("f(x: Option[int]) -> Result[int, string] = Ok(x.value)") shouldBe
        "f(x: Option[int]) -> Result[int, string]"
    }

    "a bare arrow stays a bare arrow, because that is the form a parameter may use" in {
      // Normalizing it to `Fn(int) -> int` would document a spelling a caller cannot write here.
      sig("apply(f: int -> int, x: int) -> int = f(x)") shouldBe "apply(f: int -> int, x: int) -> int"
    }

    "a written Fn stays a written Fn" in {
      sig("apply(f: Fn(int) -> int, x: int) -> int = f(x)") shouldBe
        "apply(f: Fn(int) -> int, x: int) -> int"
    }

    "a tuple renders its parts" in {
      sig("f(p: (int, string)) -> (usize, bool) = (0, true)") shouldBe "f(p: (int, string)) -> (usize, bool)"
    }

    "a default is shown, so a caller knows what may be left out" in {
      sig("f(n: usize = 0)\n    print(n)") shouldBe "f(n: usize = 0)"
    }

    "a struct renders its head and its fields" in {
      sig("struct Point\n    x: int\n    y: int") shouldBe "struct Point\n    x: int\n    y: int"
    }

    "a struct with no fields keeps its end marker, which is required and not optional" in {
      // The bare head is not sysl: the compiler refuses it, because `end` is the only thing
      // distinguishing a deliberately empty body from one whose author forgot to indent it.
      sig("struct Stdout\nend Stdout") shouldBe "struct Stdout\nend Stdout"
    }

    "an enum renders its variants with their named payloads" in {
      // There is no positional `Some(T)` in sysl — a payload's fields parse with the same rule a
      // struct's do — so a renderer that invented one would document a refused spelling.
      sig("enum Option[T]\n    Some(value: T)\n    None") shouldBe
        "enum Option[T]\n    Some(value: T)\n    None"
    }

    "a simple enum keeps a constant it pinned" in {
      sig("enum Colour\n    Red = 1\n    Green = 2") shouldBe "enum Colour\n    Red = 1\n    Green = 2"
    }

    "a trait renders its associated types before its members" in {
      sig("trait Iterate\n    type Item\n    next(&self) -> Option[Self::Item]") shouldBe
        "trait Iterate\n    type Item\n    next(&self) -> Option[Self::Item]"
    }

    "a const renders its type and its value" in {
      sig("const capacity: usize = 512") shouldBe "const capacity: usize = 512"
    }

    "an impl renders its head and NOT its methods" in {
      // The members' signatures are the trait's, already written where the trait is; repeating them
      // under every implementation is how a reference page becomes unreadable.
      sig("impl Display for Point\n    show(self) -> string = \"p\"") shouldBe "impl Display for Point"
    }

    "a receiver is rendered in the mode it was declared in" in {
      val m = only("module m\n\nstruct S\n    v: int\n    get(&self) -> int = self.v")

      m.of(ApiModel.Kind.Type).head.members.head.signature shouldBe "get(&self) -> int"
    }

    "a property is written without parentheses, because that is how it is called" in {
      val m = only("module m\n\nstruct S\n    v: int\n    len -> int = self.v")

      m.of(ApiModel.Kind.Type).head.members.head.signature shouldBe "len -> int"
    }
  }

  "the model groups a module's declarations" - {

    val src =
      """module sysl.demo
        |
        |const limit: usize = 8
        |
        |first(xs: []int) -> int = xs[0]
        |
        |struct Point
        |    x: int
        |
        |trait Shape
        |    area(self) -> int
        |""".stripMargin

    "a module is the unit, named as it was declared" in {
      only(src).name shouldBe "sysl.demo"
    }

    "each declaration lands in its own group" in {
      val m = only(src)

      m.of(ApiModel.Kind.Const).map(_.name) shouldBe List("limit")
      m.of(ApiModel.Kind.Function).map(_.name) shouldBe List("first")
      m.of(ApiModel.Kind.Type).map(_.name) shouldBe List("Point")
      m.of(ApiModel.Kind.Trait).map(_.name) shouldBe List("Shape")
    }

    "a private declaration is absent, because a reader can only call what is exported" in {
      val m = only("module m\n\nprivate helper(n: int) -> int = n\n\npublic(n: int) -> int = n")

      m.symbols.map(_.name) shouldBe List("public")
    }

    "…and is present when asked for, since a maintainer reads their own package" in {
      val ms = modules("module m\n\nprivate helper(n: int) -> int = n", includePrivate = true)

      ms.head.symbols.map(_.name) shouldBe List("helper")
    }

    "a module's files are merged, and symbols sort by name rather than by file order" in {
      val a  = parse("module m\n\nzeta() -> int = 1")
      val b  = parse("module m\n\nalpha() -> int = 2")
      val ms = ApiModel.build(List(a, b))

      ms.length shouldBe 1
      ms.head.symbols.map(_.name) shouldBe List("alpha", "zeta")
    }

    "a file with no module header contributes nothing, because it is a program and not an API" in {
      ApiModel.build(List(parse("main() = print(1)"))) shouldBe empty
    }

    "a @tests file contributes nothing, because its functions are not API" in {
      // Every build but `sysl test` strips those files before analysis, so nothing in one can be
      // called by anybody's program. Found by generating `sysl.text`: 74 functions came out, 30 of
      // them its own tests, with no way for a reader to tell which of the two kinds they may use.
      val lib   = parse("module sysl.text\n\nsplit(s: string) -> int = 1")
      val tests = parse("module sysl.text\n@tests\n\n@test(\"splits\")\nsplit_cuts()\n    print(1)")
      val ms    = ApiModel.build(List(lib, tests))

      ms.length shouldBe 1
      ms.head.symbols.map(_.name) shouldBe List("split")
    }

    "a capability clause is carried, because it is the headline for an embedded reader" in {
      only("module m\n@requires(alloc)\n\nf()\n    print(1)").capabilities shouldBe
        List("requires { alloc }")
    }
  }

  "prose is attached to the declaration below it" - {

    "a doc comment reaches the function under it" in {
      val m = only("module m\n\n/** Answers the first element. */\nfirst(xs: []int) -> int = xs[0]")

      m.symbols.head.summary shouldBe "Answers the first element."
    }

    "an annotation between the prose and the declaration does not break the association" in {
      val m = only("module m\n\n/** Answers one. */\n@export(\"one\")\none() -> int = 1")

      m.symbols.head.summary shouldBe "Answers one."
    }

    "a blank line ends the association, which is what lets a file open with prose" in {
      val m = only("module m\n\n/** About this module. */\n\nf() -> int = 1")

      m.symbols.head.documented shouldBe false
      m.summary shouldBe "About this module."
    }

    "a member's prose reaches the member and not its type" in {
      val src =
        """module m
          |
          |/** A point. */
          |struct Point
          |    x: int
          |
          |    /** Answers x. */
          |    getx(self) -> int = self.x
          |""".stripMargin

      val t = only(src).of(ApiModel.Kind.Type).head

      t.summary shouldBe "A point."
      t.members.head.summary shouldBe "Answers x."
    }

    "an undocumented declaration says so rather than pretending" in {
      only("module m\n\nf() -> int = 1").symbols.head.documented shouldBe false
    }
  }

  "the Markdown a page is written as" - {

    "carries the frontmatter the theme reads" in {
      val text = page("module sysl.text\n\nf() -> int = 1")

      text should include("layout: api-module")
      text should include("module: sysl.text")
      // A generated body's `##` is already meant to be an <h2>; the site default assumes otherwise.
      text should include("headingShift: 0")
      // And the page names its own slug algorithm rather than relying on the site's. A site that
      // already carries hand-written prose cannot switch the site key without rewriting the anchors
      // its existing headings were published with, so the generated page has to say it for itself.
      text should include("slugStyle: github")
    }

    "opens with an index whose links resolve to its own headings" in {
      val text = page("module m\n\nstarts_with(s: string) -> bool = true")

      text should include("## Index")
      text should include("[`starts_with`](#starts_with)")
      text should include("### `starts_with`")
    }

    "gives an underscore-bearing name GitHub's anchor and not juicer's default" in {
      // juicer's default slug would answer `starts-with`. The two renderings of this file — the site
      // and the repository — have to agree, which is what `slugStyle = "github"` on the site buys.
      MarkdownWriter.slug("starts_with") shouldBe "starts_with"
      MarkdownWriter.slug("Buf[T]") shouldBe "buft"
      MarkdownWriter.slug("Reading These Pages") shouldBe "reading-these-pages"
    }

    "gives two overloads of one name different anchors" in {
      // sysl has overloading, so `parse_int` really is two declarations with one heading. The anchor
      // table was keyed by heading TEXT at first, which holds one entry for both — `sysl.text` came
      // out with `parse_bool` listed twice, both links pointing at the second of the pair.
      val text = page("module m\n\nf(s: string) -> int = 1\n\nf(s: []const u8) -> int = 2")

      text should include("[`f`](#f)")
      text should include("[`f`](#f-1)")
    }

    "gives a type and its constructor different anchors, as GitHub does" in {
      // `buf()` and `Buf` case-fold to one slug. Without the suffix the second is unreachable and
      // the index's second link scrolls to the first.
      val text = page("module m\n\nbuf() -> Buf = Buf()\n\nstruct Buf\n    n: int")

      text should include("[`buf`](#buf)")
      text should include("[`Buf`](#buf-1)")
    }

    "puts the signature in a fenced sysl block directly under the heading" in {
      val text = page("module m\n\nf(n: int) -> int = n")

      text should include("### `f`\n\n```sysl\nf(n: int) -> int\n```")
    }

    "renders @param as a table" in {
      val text = page("module m\n\n/** Adds.\n *\n * @param a the left side\n */\nadd(a: int) -> int = a")

      text should include("| Parameter | Description |")
      text should include("| `a` | the left side |")
    }

    "renders @return as a labelled line" in {
      val text = page("module m\n\n/** Adds.\n *\n * @return the sum\n */\nadd(a: int) -> int = a")

      text should include("**Returns** the sum")
    }

    "says a deprecation in a callout, above the prose rather than after it" in {
      val src = "module m\n\n/** Old.\n *\n * @deprecated use `g` instead\n */\nf() -> int = 1"
      val text = page(src)

      text should include("> [!WARNING]")
      text should include("**Deprecated.** use `g` instead")
      text.indexOf("[!WARNING]") should be < text.indexOf("Old.")
    }

    "leaves an undocumented symbol bare, because the empty space already says it" in {
      // Generating the real library put this callout on 277 of 631 symbols, most of them correctly
      // undocumented — a run of constants under one shared paragraph does not want a sentence each.
      val text = page("module m\n\nf() -> int = 1")

      text should include("### `f`")
      text should not include "Undocumented"
    }

    "escapes a pipe in prose, which would otherwise end a table cell" in {
      // A doc comment describing a bitwise operation is exactly where one turns up.
      val text = page("module m\n\n/** Or.\n *\n * @param f the a | b flags\n */\nf(f: int) -> int = f")

      text should include("the a \\| b flags")
    }

    "quotes a summary in frontmatter always, since prose is arbitrary" in {
      // A summary beginning with `[`, containing `: `, or reading as `yes` all need it, and those
      // are the cases nobody thinks of.
      val text = page("module m\n\n/** Note: this one has a colon. */\n\nf() -> int = 1")

      text should include("summary: \"Note: this one has a colon.\"")
    }

    "lists a type's members as a table under it" in {
      val text = page("module m\n\nstruct S\n    v: int\n\n    get(self) -> int = self.v")

      text should include("| Member | Signature | Description |")
      text should include("| `get` |")
    }

    "gives a module page a filename with no dots in it" in {
      MarkdownWriter.fileNameOf("sysl.text") shouldBe "sysl-text.md"
    }

    "writes an index page linking every module" in {
      val a    = parse("module sysl.a\n\nf() -> int = 1")
      val b    = parse("module sysl.b\n\ng() -> int = 2")
      val page = MarkdownWriter.indexPage(ApiModel.build(List(a, b)), "Standard library")

      page.path shouldBe "_index.md"
      page.text should include("layout: api-index")
      page.text should include("[`sysl.a`](sysl-a/)")
      page.text should include("[`sysl.b`](sysl-b/)")
    }

    "puts the site's note under the index title and above the table" in {
      // Generated reference and hand-written prose are two halves of one set. The module pages link
      // outward; without this the index has no way back, and the two sections do not know about
      // each other. The text is the site's because a generator cannot know where the prose lives.
      val a    = parse("module sysl.a\n\nf() -> int = 1")
      val note = "The [written pages](/library/#modules) are the argument; this is the list."
      val page = MarkdownWriter.indexPage(ApiModel.build(List(a)), "Standard library", note = Some(note))

      page.text should include(note)
      page.text.indexOf(note) should be < page.text.indexOf("## Modules")
    }

    "leaves the index exactly as it was when no note is given" in {
      // The note is optional and its absence must cost nothing — a package repo generating for
      // GitHub alone has no second section to point at.
      val a       = parse("module sysl.a\n\nf() -> int = 1")
      val modules = ApiModel.build(List(a))

      MarkdownWriter.indexPage(modules, "T", note = None).text shouldBe
        MarkdownWriter.indexPage(modules, "T").text
    }

    "gives the index the same per-page settings as a module page" in {
      // The index links to its own `## Modules` heading and sits in the same generated section, so
      // it needs both keys for the same reasons. Asserted separately because it is written by a
      // different method — the two frontmatter blocks have drifted apart before.
      val a    = parse("module sysl.a\n\nf() -> int = 1")
      val page = MarkdownWriter.indexPage(ApiModel.build(List(a)), "Standard library")

      page.text should include("headingShift: 0")
      page.text should include("slugStyle: github")
    }

    "emits no HTML and no CSS class, which is what keeps the file readable in a repository" in {
      // The theme hooks structure and heading ids instead. A generator reaching for a wrapper div
      // would produce a file that reads worse on GitHub than in a browser.
      val text = page("module m\n\n/** Does a thing. */\nf(n: int) -> int = n")

      text should not include "<div"
      text should not include "class="
      text should not include "{#"
    }
  }
}
