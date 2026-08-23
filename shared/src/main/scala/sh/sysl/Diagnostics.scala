package sh.sysl

/** Source locations and the rendering of a diagnostic against them.
 *
 * The lexer has always known where every token is; what was missing was a way to carry that
 * knowledge as far as the pass that finds the mistake. A `Pos` is that carrier, and because it
 * holds the `Source` it came from, a diagnostic renders itself — no pass has to thread the
 * source text alongside the message just to quote a line.
 */

/** A named source text: the unit a `Pos` points into. Sources are compared by identity, so the
 * library and the user's file stay distinct even though a program is made of both, and a
 * diagnostic against a library declaration quotes the library's own file rather than the wrong one.
 *
 * `dir` is where the file sits, as the directory segments between the project root and it — so a
 * file at `std/fs/read.sysl` carries `["std", "fs"]` and one at the root carries `Nil`. A module
 * is a directory (`13 §1`), which makes this the module the file's header has to agree with, and
 * it is the driver that knows it: `None` says the file was handed over with no project around it,
 * and the header is then the whole of what says which module the file is in.
 *
 * `columnOffset` is how many columns were taken off the front of every line to make this text, which
 * is zero for a file the compiler was handed as it was written and four for the program inside a
 * literate one (`Literate`). It is added back wherever a position is *reported*, so that a location
 * names the column of the file the reader has open rather than of the text the lexer saw.
 */
final class Source(val name: String, val text: String, val dir: Option[List[String]] = None,
                   val columnOffset: Int = 0) {

  /** The text split into lines, kept for the one line a diagnostic quotes. Splitting with a
   * negative limit keeps a trailing empty line, so line numbers stay 1:1 with the file.
   */
  lazy val lines: Vector[String] = text.split("\n", -1).toVector

  /** Line `n`, 1-based, or the empty string when there is no such line — a position past the
   * end (an unexpected EOF) still renders, it just has nothing to quote.
   */
  def line(n: Int): String =
    if n >= 1 && n <= lines.length then lines(n - 1).stripSuffix("\r") else ""

  /** The offset into `text` at which each line begins, so that an offset can be turned into a line
   * and a column without walking the text.
   *
   * The lexer knows where a token *ends* as an offset and nothing else — it is what the character
   * reader beneath it counts — while everything that reports a position speaks in lines and
   * columns. One of the two has to be converted, and this is the cheap direction.
   */
  lazy val lineStarts: Vector[Int] =
    0 +: text.indices.view.filter(text.charAt(_) == '\n').map(_ + 1).toVector

  /** The line and the column, both 1-based, of an offset into `text`.
   *
   * An offset past the end answers the place just past the last character, which is where a token
   * that runs to the end of input ends.
   */
  def placeOf(offset: Int): (Int, Int) = {
    val at = math.max(0, math.min(offset, text.length))
    var lo = 0
    var hi = lineStarts.length - 1

    while lo < hi do
      val mid = (lo + hi + 1) / 2

      if lineStarts(mid) <= at then lo = mid else hi = mid - 1

    (lo + 1, at - lineStarts(lo) + 1)
  }

  override def toString: String = name
}

object Source {
  def apply(name: String, text: String): Source = new Source(name, text)

  /** A file the driver read out of a project, carrying the directory it was found in. */
  def apply(name: String, text: String, dir: List[String]): Source = new Source(name, text, Some(dir))
}

/** A span of a source file: where something starts, and where it ends.
 *
 * `line` and `col` are 1-based, as the lexer counts them, and `endLine`/`endCol` name the place
 * **just past** the last character — so the width of a span on one line is `endCol - col`, and a
 * span whose end equals its start has no extent at all. The exclusive end is what an editor
 * publishes and what makes the arithmetic come out without a `+ 1` at every use.
 *
 * **What a span covers is the TOKEN a diagnostic points at, not the whole construct.**
 * `SyslParserBase.at` stamps a node with the position of the first token the rule consumed, so a
 * node reports where its first token runs from and to. That is what a reader wants underlined and
 * what resolves a cursor to an identifier; a span covering a whole expression is a different thing
 * and nothing here claims to give one.
 *
 * A position built from a line and a column alone — a literate file's margin, a conditional
 * directive, a parse that ran out of input — has no token to measure and carries no extent.
 */
final case class Pos(source: Source, line: Int, col: Int, endLine: Int, endCol: Int) {

  /** Where this is, as a compiler conventionally spells it: `file.sysl:12:7`.
   *
   * The column is the one in the **file**, which is not the one the lexer counted when the text it
   * lexed had its left margin removed (`Source.columnOffset`). An editor told to go to a location
   * goes to the file, so the file's column is the one worth giving.
   */
  def location: String = s"${source.name}:$line:${col + source.columnOffset}"

  /** The message, the location, and the offending line with the token underlined:
   *
   * {{{
   * error: 'b' of 'add' is int, but string was given
   *  --> hello.sysl:7:14
   *   |
   * 7 | print(add(x, "two"))
   *   |              ^^^^^
   * }}}
   *
   * The underline's indent is built from the line's own leading characters with everything but a
   * tab replaced by a space, so it lands under the right column whichever mix of tabs and spaces
   * the line was written with.
   *
   * A span that runs past the end of its first line — a text block, an unterminated literal — is
   * underlined to the end of that line and no further: the quote shows one line, so an underline
   * longer than it would be pointing at nothing. A span with no extent gets a single caret, which
   * is what every diagnostic looked like before spans existed.
   */
  def render(msg: String): String = {
    val number = line.toString
    val gutter = " " * number.length
    val text   = source.line(line)
    val column = math.max(1, math.min(col, text.length + 1))
    val indent = text.take(column - 1).map(c => if c == '\t' then '\t' else ' ')
    val past   = if endLine == line then math.min(endCol, text.length + 1) else text.length + 1
    val width  = math.max(1, past - column)

    List(
      s"error: $msg",
      s"$gutter--> $location",
      s"$gutter |",
      s"$number | $text",
      s"$gutter | $indent${"^" * width}",
    ).mkString("\n")
  }
}

object Pos {

  /** A place rather than a span, for a caller that knows a line and a column and has no token to
   * measure. Its end is its start, so it renders with one caret.
   */
  def apply(source: Source, line: Int, col: Int): Pos = Pos(source, line, col, line, col)
}

/** Something that came from a place in a source file.
 *
 * The position is a **mutable field rather than a constructor parameter** on purpose: every AST
 * node is a case class, and a position in the constructor would put it into `equals`, which
 * would mean no two trees built from different files could ever compare equal and every
 * structural test would have to spell out positions it does not care about. Keeping it out of
 * the case-class signature leaves `Binary("+", a, b) == Binary("+", a, b)` true, exactly as the
 * parser tests assume.
 *
 * `setPos` **keeps the first position it is given**. Parsing builds bottom-up, so the innermost
 * rule to claim a node is the most specific one that could: a `.field` tail sets the position of
 * the dot, and the enclosing expression rule, which would have set the start of the whole
 * expression, then leaves it alone.
 */
trait Positioned {
  private var current: Option[Pos] = None

  def pos: Option[Pos] = current

  def setPos(p: Pos): this.type = {
    if current.isEmpty then current = Some(p)
    this
  }

  def setPos(p: Option[Pos]): this.type = {
    if current.isEmpty then current = p
    this
  }
}

object Diagnostic {

  /** How many errors one compilation reports.
   *
   * Five is already more than anyone fixes before compiling again, and the further down a broken
   * file a diagnostic is, the likelier it is to be a consequence of one further up rather than a
   * mistake of its own. A wall of them is not more information; it is the first five with the
   * signal-to-noise falling off behind them.
   */
  val limit: Int = 5

  /** A message rendered against a position when there is one, and on its own when there is not —
   * a synthesized node (the library's, a desugaring's) may have no place to point at, and that
   * is a reason to say less rather than to say nothing.
   */
  def render(msg: String, pos: Option[Pos]): String =
    pos.map(_.render(msg)).getOrElse(s"error: $msg")

  /** A note rather than a complaint: the location first, in the one-line form a build log wants,
   * since these are printed by the handful and not read one at a time.
   */
  def explain(msg: String, pos: Option[Pos]): String =
    pos.map(p => s"${p.location}: $msg").getOrElse(msg)

  /** Assembles the diagnostics of one compilation: at most `limit` of them, separated by a blank
   * line, with a closing count when there were more. The ones kept are the *first* — the list
   * arrives in source order, and an error early in a file is the one worth reading.
   */
  def report(all: List[String]): String =
    if all.length <= limit then all.mkString("\n\n")
    else (all.take(limit) :+ s"showing the first $limit of ${all.length} errors").mkString("\n\n")
}
