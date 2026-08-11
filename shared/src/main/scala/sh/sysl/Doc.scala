package sh.sysl

/** A literate source rendered as the document it already almost is — what `sysl doc` writes.
 *
 * **A `.lsysl` file is Markdown before anything touches it** (`Literate`), so the temptation is to
 * say that rendering one is the identity and that the command is unnecessary. It is not, and the
 * reason is the one thing the format gives up to keep the compiler simple: **the program is marked
 * by an indent and nothing else.** A four-column indent is Markdown's own spelling of a code block,
 * which is why the format works unrendered — but an indented block carries no *language*, so every
 * renderer that reads one sets it in a monospace font and stops there. There is nothing for a
 * highlighter to dispatch on and nothing for a tool that wanted to find the code to match.
 *
 * So what this does is put the language back:
 *
 * ```
 *     print("Hello, sysl!")          becomes        ```sysl
 *                                                   print("Hello, sysl!")
 *                                                   ```
 * ```
 *
 * Everything else in the file is passed through the way it was written. Prose is prose, an
 * illustration stays an illustration, and a heading keeps its level — this is not a formatter and it
 * has no opinion about anybody's Markdown.
 *
 * **Two things follow from re-fencing rather than re-writing, and both are the point.** A block that
 * was indented for a compiler is now tagged for a highlighter, which is what a reader of
 * `guide/lisp` gets out of it; and the tag is the same one the site's grammar answers to, so the
 * code in a rendered document and the code on a hand-written page are highlighted by one grammar
 * rather than two.
 *
 * **What this is not is an API reference.** The other thing a language's `doc` command usually
 * means — a page per module, generated from declarations and the comments above them — is a
 * different product, and sysl cannot build it yet for a reason that is nothing to do with
 * rendering: there is no such thing as a documentation comment. A comment is lexical trivia here and
 * reaches no tree, so there is nothing for a generator to read. Whether to make one a language
 * construct is a decision about the language rather than about this file.
 */
object Doc {

  /** The info string the fences carry. The site's grammar answers to this word, and so does every
   * highlighter that has been taught sysl at all, so a document rendered here and a page written by
   * hand are read by the same one.
   */
  val Language: String = "sysl"

  /** What separates one file's document from the next when a whole directory is rendered: a
   * thematic break, which every Markdown renderer draws as a rule.
   *
   * **`***` rather than `---`, and it is not a matter of taste.** A `---` on the line after text is
   * a setext heading underline in CommonMark, so joining two documents with one would silently
   * promote whatever the previous file ended on into a heading. `***` has no second reading.
   */
  private val Break: String = "***"

  /** The document a literate source holds, or the first thing wrong with the file.
   *
   * The failures are `Literate`'s own — a tab in an indent, a fence that never closes — because
   * they are failures to *read* the file, and a file the compiler cannot read is one this cannot
   * render either. That they are shared is worth more than the diagnostics being tuned for a reader:
   * a document that rendered happily out of a file the compiler refuses would be documentation of a
   * program that does not exist.
   */
  def render(source: Source): Either[String, String] =
    if !Literate.named(source.name) then
      Left(s"${source.name} is an ordinary sysl program — 'doc' renders the prose of a " +
        s"'${Literate.Extension}' file, and a program that is all code has none")
    else Literate.classify(source).map(lay)

  /** Several sources as one document, in the order they were given, separated by a rule. */
  def renderAll(sources: Seq[Source]): Either[String, String] = {
    val each = sources.map(render)

    each.collectFirst { case Left(err) => err } match
      case Some(err) => Left(err)
      case None      => Right(each.collect { case Right(text) => text }.mkString(s"\n$Break\n\n"))
  }

  /** The lines laid out as Markdown: prose as it was written, and each run of program text inside a
   * fence of its own.
   *
   * **A blank line between two program lines is held rather than emitted**, which is the only
   * subtlety here and it comes straight from the format: consecutive indented lines are one block
   * whether or not there is air between them, so a function with a blank line in the middle is one
   * function and has to render as one fence. A held blank is let out unfenced only once something
   * that is really prose arrives, or at the end of the file.
   */
  private def lay(lines: IndexedSeq[Literate.Line]): String = {
    val out   = collection.mutable.ArrayBuffer.empty[String]
    val block = collection.mutable.ArrayBuffer.empty[String]
    val held  = collection.mutable.ArrayBuffer.empty[String]

    def close(): Unit = {
      if block.nonEmpty then {
        val fence = "`" * width(block)

        out += fence + Language
        out ++= block
        out += fence
        block.clear()
      }

      out ++= held
      held.clear()
    }

    lines.foreach {
      case Literate.Line.Code(text) =>
        block ++= held
        held.clear()
        block += text

      // Air inside a block, which is not yet known to be air *after* one.
      case Literate.Line.Prose(text) if block.nonEmpty && text.isBlank => held += text

      case Literate.Line.Prose(text) =>
        close()
        out += text
    }

    close()
    out.mkString("\n")
  }

  /** How many backticks the fence needs: three, or one more than the longest run the code itself
   * holds. A program is entitled to a string literal full of backticks, and a fence it could close
   * from the inside would put the rest of the block into the prose.
   */
  private def width(block: collection.Seq[String]): Int = {
    val longest = block.foldLeft(0) { (most, line) =>
      val runs = line.foldLeft((0, 0)) {
        case ((run, most), '`') => (run + 1, math.max(most, run + 1))
        case ((_, most), _)     => (0, most)
      }

      math.max(most, runs._2)
    }

    math.max(3, longest + 1)
  }
}
