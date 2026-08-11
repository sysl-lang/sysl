package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl doc`: a literate source rendered as the document it already almost is (`Doc`).
 *
 * **The load-bearing assertion here is a round trip, not a fixture.** What could go wrong with a
 * renderer that re-fences a program is that it loses a line or invents one — a block whose last line
 * ended up outside the fence, a blank line that closed one early, an illustration that got compiled
 * into the program. A golden file would catch all of that and would also break on every edit to the
 * prose it happens to quote, so what is asserted instead is the property: the code inside the fences
 * is exactly the program the compiler reads, in order.
 *
 * It is asked of the two real literate trees as well as of the small cases, because the small cases
 * are the ones somebody thought of.
 */
class DocTests extends AnyFreeSpec with Matchers {

  private def source(text: String): Source = Source(s"doc${Literate.Extension}", text)

  private def rendered(text: String): String =
    Doc.render(source(text)) match
      case Right(out) => out
      case Left(err)  => fail(err)

  /** The program a literate source holds, blank lines dropped — what the compiler is handed. */
  private def program(src: Source): List[String] =
    Literate.tangle(src) match
      case Right(out) => out.lines.filter(_.trim.nonEmpty).toList
      case Left(err)  => fail(err)

  /** The code inside the rendered document's sysl fences, blank lines dropped.
   *
   * Written out here rather than shared with the renderer on purpose: a helper that used `Doc`'s own
   * idea of where a fence begins would agree with it by construction, and agreeing by construction
   * is what a round trip is supposed to rule out.
   */
  private def fenced(document: String): List[String] = {
    val out  = collection.mutable.ArrayBuffer.empty[String]
    var open = ""

    document.linesIterator.foreach { line =>
      if open.isEmpty then {
        val ticks = line.takeWhile(_ == '`')

        if ticks.length >= 3 && line.drop(ticks.length) == Doc.Language then open = ticks
      } else if line.forall(_ == '`') && line.length >= open.length then open = ""
      else if line.trim.nonEmpty then out += line
    }

    open shouldBe ""
    out.toList
  }

  /** A driver run with both streams caught, asserted to have succeeded, and its stdout handed back.
   *
   * `sh.sysl.execute` in full, because `Suite` has an `execute` of its own and it is the one that
   * wins unqualified — the same shadowing `LibraryCliSupport` names a helper to get out of.
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
    Doc.render(src) match
      case Left(err)       => fail(s"${src.name} did not render: $err")
      case Right(document) => withClue(s"${src.name}: ")(fenced(document) shouldBe program(src))

  "a program indented for the compiler comes out fenced for a reader" in {
    // The whole of what the command does, on the smallest file that shows it. The prose is
    // untouched and the four columns are gone, because they were Markdown's way of saying "code"
    // and the fence is now saying it instead.
    rendered("The greeting\n\nA program of one statement.\n\n    print(\"hi\")\n") shouldBe
      "The greeting\n\nA program of one statement.\n\n```sysl\nprint(\"hi\")\n```\n"
  }

  "a blank line between two program lines stays inside the fence" in {
    // Consecutive indented lines are one block whether or not there is air between them
    // (`Literate`), so a function with a paragraph break in it is one function — and has to render
    // as one fence rather than as two.
    rendered("Text\n\n    a()\n\n    b()\n") shouldBe "Text\n\n```sysl\na()\n\nb()\n```\n"
  }

  "while prose between them closes the fence and opens another" in {
    // The format's headline feature — a fifty-line function explained a step at a time — and the
    // reading a Markdown renderer gives it anyway.
    rendered("    a()\n\nWhy b follows a.\n\n    b()\n") shouldBe
      "```sysl\na()\n```\n\nWhy b follows a.\n\n```sysl\nb()\n```\n"
  }

  "an illustration is passed through as it was written, and is not program text" in {
    // A fenced block is prose however it is indented, which is what lets a chapter show a wrong
    // version beside a right one. Re-fencing one as sysl would compile the wrong version.
    val out = rendered("Shown, not run:\n\n```\nnot sysl at all\n```\n\n    real()\n")

    out should include("```\nnot sysl at all\n```")
    fenced(out) shouldBe List("real()")
  }

  "and so is an example written under a bullet" in {
    val out = rendered("- a bullet\n\n      shown()\n\nBack to the margin.\n\n    run()\n")

    fenced(out) shouldBe List("run()")
    out should include("      shown()")
  }

  "a program holding a run of backticks gets a longer fence" in {
    // A string literal is entitled to hold anything, and a fence the code could close from the
    // inside would put the rest of the block into the prose.
    val out = rendered("Text\n\n    print(\"``` and ````\")\n")

    out should include("`````sysl\n")
    fenced(out) shouldBe List("print(\"``` and ````\")")
  }

  "what the compiler refuses to read, this refuses to render" in {
    // Sharing the failures with the tangler is the point rather than a saving: a document that
    // rendered happily out of a file the compiler refuses is documentation of a program that does
    // not exist.
    Doc.render(source("Text\n\n\tprint(1)\n")).left.getOrElse(fail("a tab should be refused")) should
      include("tab")

    Doc.render(source("Text\n\n```\nopened\n")).left.getOrElse(fail("an open fence should be refused")) should
      include("never closed")
  }

  "an ordinary sysl program is refused, since it has no prose to render" in {
    Doc.render(Source("prog.sysl", "main() = print(1)\n"))
      .left.getOrElse(fail("a .sysl file should be refused")) should include("all code")
  }

  "the code inside the fences is the program the compiler reads" - {

    "for a document whose prose interrupts a block" in {
      roundTrips(source("Head\n\n    a()\n\nmid\n\n    b()\n\n    c()\n\ntail\n"))
    }

    "for one that mixes illustrations, bullets and code" in {
      roundTrips(source(
        "# Title\n\nProse.\n\n```\nillustration\n```\n\n- item\n\n      under the item\n\nMargin.\n" +
          "\n    real()\n\n    more()\n\nEnd.\n"))
    }

    "for the guide programs written this way" in {
      // The real files, which are hundreds of lines each and hold every shape above at once.
      for name <- List("guide/lisp/lisp", "guide/slab/slab") do
        val path = s"$name${Literate.Extension}"

        assume(isFile(path), s"$path is not reachable from the test working directory")
        roundTrips(Source(path, readFile(path)))
    }

    "and for the library module written this way" in {
      // `sysl.regex` is five literate files, so this is also the multi-file case: every one of them
      // has to survive, and `renderAll` has to keep them apart.
      val root = StdRoot.root

      assume(root.isDefined, "the library is not reachable from the test working directory")

      val files = Project.collect(s"${root.get}/sysl/regex").filter(s => Literate.named(s.name))

      files should not be empty
      files.foreach(roundTrips)

      Doc.renderAll(files) match
        case Left(err)       => fail(err)
        case Right(document) => fenced(document) shouldBe files.flatMap(program)
    }
  }

  "the command" - {

    "renders a literate file to standard output" in {
      val path = s"guide/slab/slab${Literate.Extension}"

      assume(isFile(path), s"$path is not reachable from the test working directory")

      val out = ran(Config(command = "doc", file = path))

      out should include(s"```${Doc.Language}\n")

      // The program came out of the fences rather than out of the indent, which is the whole of what
      // the command changed. Asserted against the file's own program rather than against the absence
      // of indented lines — an illustration keeps whatever indentation it was written with.
      fenced(out) shouldBe program(Source(path, readFile(path)))
    }

    "writes it to a file when told where" in {
      val path = s"guide/slab/slab${Literate.Extension}"

      assume(isFile(path), s"$path is not reachable from the test working directory")

      val out = s"${createTempDirectory("sysl-doc-")}/slab.md"

      ran(Config(command = "doc", file = path, output = Some(out)))
      readFile(out) should include(s"```${Doc.Language}\n")
    }

    "and refuses a tree with no literate source in it, rather than writing an empty document" in {
      val dir = createTempDirectory("sysl-doc-plain-")

      writeFile(s"$dir/main.sysl", "main() = print(1)\n")
      refused(Config(command = "doc", file = dir)) should include(Literate.Extension)
    }
  }
}
