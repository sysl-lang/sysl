package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.Assertions
import org.scalatest.matchers.should.Matchers

/** Runs the programs printed on the documentation site, which are source on disk like `guide/` is —
 * except that here the file the reader sees and the file the compiler is handed are the *same
 * bytes*.
 *
 * A tutorial's examples rot silently. Nothing in an ordinary build reads them, so a page goes on
 * claiming what the language did a year ago and the first person to find out is a reader who typed
 * it in and got a diagnostic. That failure is worse than no page at all: it costs the reader their
 * confidence that anything else on the site is true either.
 *
 * So a fenced block is not a picture of a program, it *is* the program. Which one it is comes from
 * what follows it, which is also how it reads to somebody scrolling past:
 *
 *   - a `sysl` block followed by an `output` block is a whole program, and the output block is what
 *     it prints — compiled, run, and compared;
 *   - a `sysl` block followed by an `error` block is a program the compiler **refuses**, and the
 *     error block is what it says — compiled, required to fail, and the diagnostic required to
 *     contain that text;
 *   - a `sysl` block with neither under it is a fragment, quoted for its shape, and is not run.
 *
 * The refusals matter as much as the acceptances. A language is defined as much by what it will not
 * let you write, and a page that shows only working code teaches half of it — so the surface that
 * checks a refusal is here from the start rather than bolted on once somebody wants it.
 */
trait DocsSupport extends Matchers { this: Assertions =>

  /** What a `sysl` block on a page claims about itself. */
  enum Claim {
    /** A whole program, and what running it prints. */
    case Prints(output: String)

    /** A program the compiler refuses, and what its diagnostic says. */
    case Refused(message: String)

    /** A fragment, quoted to make a point about shape. Nothing is asserted about it. */
    case Fragment
  }

  /** One `sysl` block, with where it came from — a failure has to be able to name the page and the
   * position on it, because a reader of the failure has only the page to go and fix.
   */
  case class Snippet(page: String, nth: Int, source: String, claim: Claim) {
    def display: String = s"$page, sysl block $nth"
  }

  /** A fenced block as the markdown holds it: the word after the backticks, and the body. */
  private case class Fence(lang: String, body: String)

  /** Every markdown page under `docs/content`, deepest last, so a failure list reads in the order a
   * reader would meet the pages.
   */
  protected def pages: List[String] = {
    def walk(dir: String): List[String] = {
      val entries = listFiles(dir).toList.sorted

      entries.filter(f => isFile(f) && f.endsWith(".md")) :::
        entries.filter(isDirectory).flatMap(walk)
    }

    walk(Root).map(relative)
  }

  private val Root = "docs/content"

  /** A page named the way the site names it, whatever the platform handed back.
   *
   * `listFiles` answers in absolute paths, and an absolute path is the working directory of
   * whoever ran the suite — so a failure would quote a path meaningless to anyone else, and the
   * expected-page list in `DocsTests` would be a list of one machine's directory layout.
   */
  private def relative(path: String): String = {
    val at = path.replace('\\', '/').indexOf(Root)

    if at >= 0 then path.replace('\\', '/').substring(at) else path
  }

  /** The `sysl` blocks of one page, each paired with what the page claims about it. */
  protected def snippets(page: String): List[Snippet] = {
    val fences = fencesOf(readFile(page))

    // `zipWithIndex` over the sysl fences alone, not over every fence: the number a failure quotes
    // is the number a reader counts on the page, and they do not count the bash and output blocks.
    fences.zipWithIndex
      .filter { case (f, _) => f.lang == "sysl" }
      .map { case (f, i) => (f, i) }
      .zipWithIndex
      .map { case ((fence, position), nth) =>
        Snippet(page, nth + 1, fence.body, claimAfter(fences.drop(position + 1)))
      }
  }

  /** What the fences after a `sysl` block say about it: the first `output` or `error` block to
   * appear before the next `sysl` block.
   *
   * Not simply the very next fence, because a page reads *program, how to run it, what it prints* —
   * with a `bash` block in the middle — and a rule that could not see past that would push authors
   * into writing the pages in an order that suits the harness. What the rule does refuse is an
   * `output` block that belongs to a *later* program: the search stops at the next `sysl` block, so
   * a claim can never be attached to the wrong one.
   */
  private def claimAfter(rest: List[Fence]): Claim =
    rest.takeWhile(_.lang != "sysl").find(f => f.lang == "output" || f.lang == "error") match {
      case Some(Fence("output", body)) => Claim.Prints(body)
      case Some(Fence("error", body))  => Claim.Refused(body)
      case _                           => Claim.Fragment
    }

  /** The fenced blocks of a markdown document, in order.
   *
   * Only a fence at the left margin opens a block. An indented one is inside a list item or a block
   * quote, where the surrounding prose owns it — and a program indented under a bullet is being
   * quoted by the prose rather than offered as something to run.
   */
  private def fencesOf(text: String): List[Fence] = {
    val lines   = text.linesIterator.toList
    val fences  = List.newBuilder[Fence]
    var open    = false
    var lang    = ""
    val body    = StringBuilder()

    for (line <- lines)
      if line.startsWith("```") then
        if open then
          fences += Fence(lang, body.toString)
          body.clear()
          open = false
        else
          lang = line.stripPrefix("```").trim.takeWhile(!_.isWhitespace)
          open = true
      else if open then body ++= line + "\n"

    fences.result()
  }

  /** Checks one snippet, returning the complaint if it has one.
   *
   * Every snippet answers rather than throwing, so one run reports **every** page that has drifted
   * instead of the first. A tutorial breaks in groups — one language change invalidates the six
   * pages that lean on it — and a suite that stops at the first would take six runs to find them,
   * each one six minutes long.
   */
  protected def check(s: Snippet): Option[String] = s.claim match {
    case Claim.Fragment => None

    case Claim.Prints(expected) =>
      compileAndRun(s.source) match {
        case Left(err) =>
          Some(s"${s.display}: expected it to run, but it did not compile:\n$err")

        case Right((code, _)) if code != 0 =>
          Some(s"${s.display}: expected it to run, but it exited with $code")

        case Right((_, out)) if normalize(out) != normalize(expected) =>
          Some(
            s"${s.display}: what it printed is not what the page says.\n" +
              s"page:\n${indent(expected)}\nprogram:\n${indent(out)}"
          )

        case Right(_) => None
      }

    case Claim.Refused(expected) =>
      Compiler.compileToLlvm(s.source) match {
        case Right(_) =>
          Some(s"${s.display}: the page says the compiler refuses this, and it compiled")

        case Left(diagnostic) if !diagnostic.contains(expected.trim) =>
          Some(
            s"${s.display}: the compiler refused it, but not in the words the page quotes.\n" +
              s"page:\n${indent(expected)}\ncompiler:\n${indent(diagnostic)}"
          )

        case Left(_) => None
      }
  }

  /** The same compilation `RunSupport` performs — against the prebuilt standard module where the
   * toolchain can build one, which is what an ordinary `sysl run` does.
   */
  private def compileAndRun(src: String): Either[String, (Int, String)] =
    PrebuiltCore.forHost match {
      case Some((core, precompiled, archive)) =>
        Toolchain.compileAndRun(List(Source("<page>", src)), Nil, Nil, Some(core), precompiled, List(archive))
      case None =>
        Toolchain.compileAndRun(List(Source("<page>", src)), Nil, Nil, None, Set.empty, Nil)
    }

  /** Trailing whitespace is not a claim a page is making. A markdown fence keeps a final newline
   * that the author did not type and cannot see, so comparing raw text would fail every snippet for
   * a reason no reader could act on.
   */
  private def normalize(s: String): String = s.linesIterator.map(_.replaceAll("\\s+$", "")).mkString("\n").trim

  private def indent(s: String): String = s.linesIterator.map("    " + _).mkString("\n")
}
