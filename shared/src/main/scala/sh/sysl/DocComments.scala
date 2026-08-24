package sh.sysl

/** Documentation comments: `/** … */` above a declaration, and what a generator reads out of one.
 *
 * **A doc comment is not part of what a declaration means.** Nothing in the analyzer reads one, no
 * lowering sees one, and a program compiles identically with every doc comment in it deleted. What
 * they are for is the reader — a generated API reference, an editor's hover text — and the compiler
 * carries them because it is the only thing that knows which prose belongs to which declaration.
 *
 * **The form is scaladoc's**, `/** … */` with an optional `*` down the left margin, and the
 * delimiter is the whole of what distinguishes a doc comment from an ordinary one. That is
 * deliberate: an implementation note above a declaration stays an implementation note by being
 * written `//`, and nothing has to guess at intent.
 *
 * **A tag that names the signature is checked; one that does not is prose.** `@param`, `@tparam` and
 * `@return` are the three that can go stale — a parameter renamed leaves the paragraph describing it
 * pointing at nothing — so a `@param` naming something the declaration does not have is refused. The
 * rest (`@see`, `@note`, `@example`, `@since`, `@deprecated`) mean what they mean anywhere and are
 * carried through untouched.
 *
 * **An unrecognized `@name` is left alone.** sysl's `@` is the annotation sigil — `@test`,
 * `@requires`, `@export`, `@no_alloc` — and a doc comment discussing one is discussing the language
 * rather than tagging itself. So the vocabulary is closed and anything outside it is body text; what
 * that costs is that a typo (`@parm`) is silent, and `unknownTags` is what a warning would be built
 * on if that trade is ever revisited.
 */
object DocComments {

  /** One tag inside a doc comment: `@param xs the slice to sort`.
   *
   * `subject` is the first word after the tag for the tags that take one — the parameter's name —
   * and `None` for the tags that do not. `text` is everything after it, with the tag's own line and
   * any continuation lines joined.
   */
  case class Tag(name: String, subject: Option[String], text: String, line: Int)

  /** A parsed doc comment, and the declaration it was found above.
   *
   * `summary` is the first sentence of the body, which is what an index column wants; `body` is the
   * whole of the prose before the first tag, summary included. Splitting rather than trimming means
   * a page can show either without the generator having to re-derive one from the other.
   */
  case class Doc(
      summary: String,
      body: String,
      tags: List[Tag],
      startLine: Int,
      endLine: Int,
  ) {

    /** The tags that name something in the signature, which are the checkable three. */
    def params: List[Tag]  = tags.filter(_.name == "param")
    def tparams: List[Tag] = tags.filter(_.name == "tparam")
    def returns: Option[Tag] = tags.find(_.name == "return")

    /** Tags whose name is neither a doc tag nor one of sysl's own annotations — candidate typos.
     *
     * Nothing acts on this today, by decision: an unknown tag is body text and does not warn. It is
     * computed because the alternative to computing it is not having the evidence the day somebody
     * asks whether `@parm` is worth catching.
     */
    def unknownTags: List[Tag] = tags.filterNot(t => Known(t.name) || Annotations(t.name))
  }

  /** The tags that take a subject — a name from the declaration, written directly after the tag. */
  private val Subjected = Set("param", "tparam")

  /** The closed vocabulary. `throws` is deliberately absent: sysl has no exceptions, and what a
   * fallible function answers with is the `E` of its `Result[T, E]` — which `@return` describes,
   * because it is part of the return type rather than a second channel out of the function.
   */
  private val Known =
    Set("param", "tparam", "return", "see", "note", "example", "since", "deprecated", "group",
        "groupname", "inheritdoc")

  /** sysl's own annotation sigil, so that prose *about* an annotation is not read as a tag.
   *
   * This is why an unknown `@name` cannot simply warn: `@requires` in a doc comment is overwhelmingly
   * a sentence about the capability clause, and a warning there would be noise on correct prose.
   */
  private val Annotations =
    Set("test", "tests", "requires", "export", "no_alloc", "link", "crossing", "packed", "align",
        "section", "assert", "ghost", "include", "inline")

  /** Strip the delimiters and the left margin, leaving the lines the author wrote.
   *
   * The margin is scaladoc's: a `*` at the start of a line, with the one space after it removed and
   * any further indentation kept — so a fenced code block inside a doc comment keeps its own shape.
   * A line with no `*` is taken as it stands, since the margin is a convention rather than a
   * requirement and a comment written without it should not come out indented by however far the
   * declaration happened to be.
   */
  private def strip(text: String): List[String] = {
    val inner = text.stripPrefix("/**").stripSuffix("*/")

    inner.split("\n", -1).toList.map { raw =>
      val line = raw.trim

      if line.startsWith("*") then
        val rest = line.drop(1)

        if rest.startsWith(" ") then rest.drop(1) else rest
      else raw.trim
    }
  }

  /** Where a tag begins: `@name` at the start of a line, and nowhere else.
   *
   * Anchoring to the line start is what keeps an `@` inside a sentence — an email address, a
   * reference to `@test` mid-paragraph — from opening a tag. It is the same rule scaladoc and
   * rustdoc use, and it is the reason a tag's continuation lines can be ordinary prose.
   */
  private val TagStart = """^@([A-Za-z_][A-Za-z0-9_]*)\s*(.*)$""".r

  /** The first sentence of the body, for an index that has one column to spend.
   *
   * A sentence ends at `.`, `!` or `?` followed by a space or the end of the text — so `sysl.text`
   * mid-sentence does not end it, which is the case that matters in a language whose module names
   * are dotted. Where there is no such stop the whole first paragraph is the summary, since a body
   * that never ends a sentence is one whose author wrote a phrase.
   */
  private def firstSentence(body: String): String = {
    val flat = body.trim.replaceAll("\\s+", " ")

    if flat.isEmpty then ""
    else
      val stop = """[.!?](\s|$)""".r.findFirstMatchIn(flat)

      stop match
        case Some(m) => flat.substring(0, m.start + 1)
        case None    => flat
  }

  /** Parse one doc comment's text. `startLine` is the line its opening delimiter sits on.
   *
   * Continuation lines belong to the tag above them until the next tag or the end, which is what
   * lets a `@param` run to a paragraph. A blank line does **not** end a tag, for the same reason it
   * does not in scaladoc: a tag's text is prose and prose has paragraphs.
   */
  def parse(text: String, startLine: Int, endLine: Int): Doc = {
    val lines                                  = strip(text)
    val body                                   = List.newBuilder[String]
    var tags: List[(String, Option[String], List[String], Int)] = Nil
    var current: Option[(String, Option[String], List[String], Int)] = None

    def close(): Unit = current.foreach(t => tags = t :: tags)

    lines.zipWithIndex.foreach { (line, i) =>
      TagStart.findFirstMatchIn(line) match
        case Some(m) =>
          close()

          val name = m.group(1)
          val rest = m.group(2).trim

          if Subjected(name) then
            val (subject, remainder) = rest.span(!_.isWhitespace)

            current = Some((name, Some(subject).filter(_.nonEmpty), List(remainder.trim), startLine + i))
          else current = Some((name, None, List(rest), startLine + i))

        case None =>
          current match
            case Some((n, s, ls, at)) => current = Some((n, s, ls :+ line, at))
            case None                 => body += line
    }

    close()

    val prose = body.result().mkString("\n").trim

    Doc(
      summary = firstSentence(prose),
      body = prose,
      tags = tags.reverse.map((n, s, ls, at) => Tag(n, s, ls.mkString("\n").trim, at)),
      startLine = startLine,
      endLine = endLine,
    )
  }

  /** Every doc comment in a scanned source, parsed, in source order.
   *
   * The lexer reports offsets because that is what a character reader counts; a declaration is
   * positioned by line, so the two are reconciled here through `Source.placeOf` — one binary search
   * per comment over an index the source builds once.
   */
  def of(source: Source, scanned: List[(Int, Int, String)]): List[Doc] =
    scanned.map { (start, end, text) =>
      val (startLine, _) = source.placeOf(start)
      // The end offset is one past the closing delimiter, so the line it lands on is the comment's
      // last line — except where the comment ends the file with no newline, which is the same line
      // either way.
      val (endLine, _) = source.placeOf(math.max(start, end - 1))

      parse(text, startLine, endLine)
    }

  /** What a doc comment gets wrong about the signature it sits above.
   *
   * **Only the three tags that name the signature are checkable, and only in one direction.** A
   * `@param` naming a parameter the declaration does not have is wrong now and will stay wrong — it
   * is the shape a rename leaves behind, describing something that is no longer there. A parameter
   * with *no* `@param` is not an error and must never become one: documentation is optional, a
   * partial doc comment is better than none, and a rule requiring the full set is what makes people
   * write `@param n the n` to silence it.
   *
   * So: check what is written, never require what is absent. That is also what lets a library be
   * converted a file at a time, which is the property this whole feature was asked to have.
   *
   * The pair is returned rather than reported, so that the caller decides severity and this stays a
   * function of two lists. `subject` is `None` where the author wrote a bare `@param` with no name
   * after it, which is its own mistake and is named as one.
   */
  def check(doc: Doc, params: List[String], tparams: List[String]): List[(Tag, String)] = {
    def wrong(tags: List[Tag], known: List[String], what: String): List[(Tag, String)] =
      tags.flatMap { tag =>
        tag.subject match
          case None =>
            Some(tag -> s"'@${tag.name}' names no $what — write the name it documents after the tag")
          case Some(name) if !known.contains(name) =>
            val known_ = if known.isEmpty then s"it has no ${what}s" else s"it has ${known.mkString(", ")}"

            Some(tag -> s"'$name' is not a $what of this declaration — $known_")
          case _ => None
      }

    wrong(doc.params, params, "parameter") ::: wrong(doc.tparams, tparams, "type parameter")
  }

  /** The doc comment immediately above `line`, if there is one.
   *
   * **Immediately** allows annotations and blank-free whitespace in between, and nothing else. An
   * annotation between the prose and the declaration is the ordinary shape — `@test` above a
   * function, `@export` above another — so a rule that demanded adjacency would silently drop the
   * documentation of every annotated declaration. A **blank line** ends the association, which is
   * what lets a file-level comment sit at the top without being adopted by whatever is declared
   * first.
   */
  def above(source: Source, docs: List[Doc], line: Int): Option[Doc] =
    docs.reverse.find(_.endLine < line).filter { doc =>
      val between = (doc.endLine + 1 until line).map(source.line)

      between.forall { text =>
        val t = text.trim

        t.nonEmpty && (t.startsWith("@") || t.startsWith("//"))
      }
    }
}
