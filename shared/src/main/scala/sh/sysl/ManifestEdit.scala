package sh.sysl

/** Putting a dependency into a `package.hocon` **by rewriting the smallest run of bytes that has to
 * change**, which is the whole design of `sysl add`.
 *
 * A manifest is data, so the obvious rewrite — read it, add a member, print it back — is available
 * and is wrong. Printing back a parsed value emits the value and nothing else, and the manifests in
 * this org are about three fifths comment: `sdl3-demo`'s opens with twelve lines explaining why the
 * four SDL packages are four, and `syslui-demo`'s explains what transitive imports let it stop
 * saying. All of that goes the first time a tool touches the file, and a tool that eats the
 * reasoning out of a file is a tool people stop running.
 *
 * **The file's own style is followed rather than imposed.** A new entry takes the indent its
 * siblings have and lines its coordinate up with theirs where they are lined up; nothing already in
 * the file moves. That last point is a rule rather than an omission — reflowing the siblings to fit
 * a longer name would turn a one-line addition into a diff over the whole block, which is the same
 * objection as the reprint, one size down.
 *
 * Nothing here writes a file or asks a network anything: it is text in and text out, so what it
 * decides can be asserted without either.
 */
object ManifestEdit {

  /** A manifest with one dependency added, or a sentence saying why it could not be.
   *
   * The result is not written anywhere by this — `sysl add` re-reads it through `PackageConfig`
   * before it goes to disk, so a rewrite that produced something the compiler cannot read fails
   * with the manifest untouched.
   */
  def addDependency(text: String, label: String, coordinate: String,
                    version: String): Either[String, String] =
    blockOf(text, "dependencies") match
      case Some((open, close)) =>
        val body = text.substring(open, close)

        if entryNames(body).contains(label) then
          Left(s"'$label' is already a dependency of this project — change its version in " +
            s"${PackageConfig.FileName}, or remove the entry and add it again")
        else Right(insert(text, open, close, label, coordinate, version))

      case None =>
        // A project with no dependencies at all: the block goes at the end, where a reader looking
        // for what a project takes will find it, and where it cannot land inside something else.
        val entry = s"""  $label { git = "$coordinate", version = "$version" }"""
        val sep   = if text.endsWith("\n\n") then "" else if text.endsWith("\n") then "\n" else "\n\n"

        Right(s"$text${sep}dependencies {\n$entry\n}\n")

  /** The half-open span of a top-level block's body — from just after its `{` to just before the
   * `}` that closes it.
   *
   * The scan skips strings and both comment forms, because a `}` inside either closes nothing and a
   * counter that did not know the difference would stop in the middle of a sentence. A manifest is
   * small enough that walking it a character at a time costs nothing worth measuring.
   */
  private[sysl] def blockOf(text: String, name: String): Option[(Int, Int)] = {
    val head = headerOf(text, name)

    head.flatMap { at =>
      val open = text.indexOf('{', at)

      if open < 0 then None else matching(text, open + 1).map((open + 1, _))
    }
  }

  /** Where a top-level `name {` header starts, ignoring one written inside a comment or a string.
   *
   * **Top-level means at brace depth zero**, which is what keeps `dependencies` from being found
   * inside some other block that happened to contain the word.
   */
  private def headerOf(text: String, name: String): Option[Int] = {
    var i     = 0
    var depth = 0

    while i < text.length do
      i = skipPast(text, i) match
        case Some(next) => next
        case None =>
          text.charAt(i) match
            case '{' => depth += 1; i + 1
            case '}' => depth -= 1; i + 1
            case _ if depth == 0 && starts(text, i, name) => return Some(i)
            case _ => i + 1

    None
  }

  /** Whether `name` sits at `i` as a whole word with only spaces between it and a `{`. */
  private def starts(text: String, i: Int, name: String): Boolean = {
    val before = i == 0 || !isWordChar(text.charAt(i - 1))
    val end    = i + name.length

    before && text.startsWith(name, i) && {
      var j = end

      while j < text.length && (text.charAt(j) == ' ' || text.charAt(j) == '\t') do j += 1

      j < text.length && text.charAt(j) == '{'
    }
  }

  private def isWordChar(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '-' || c == '.'

  /** The index of the `}` closing a block whose body starts at `from`. */
  private def matching(text: String, from: Int): Option[Int] = {
    var i     = from
    var depth = 0

    while i < text.length do
      i = skipPast(text, i) match
        case Some(next) => next
        case None =>
          text.charAt(i) match
            case '{' => depth += 1; i + 1
            case '}' if depth == 0 => return Some(i)
            case '}' => depth -= 1; i + 1
            case _ => i + 1

    None
  }

  /** Past a string or a comment starting at `i`, or `None` where `i` starts neither.
   *
   * HOCON writes `//` and `#` line comments and `/* */` block ones, and sysl's manifests use the
   * first. All three are skipped, since a rewrite that understood only the form this org happens to
   * write would fail on a file somebody else wrote.
   */
  private def skipPast(text: String, i: Int): Option[Int] = {
    def rest(from: Int, of: String): Int =
      val at = text.indexOf(of, from)

      if at < 0 then text.length else at + of.length

    text.charAt(i) match
      case '"' =>
        var j = i + 1

        while j < text.length && text.charAt(j) != '"' do
          j += (if text.charAt(j) == '\\' then 2 else 1)

        Some(math.min(j + 1, text.length))

      case '#' => Some(rest(i, "\n"))
      case '/' if text.startsWith("//", i) => Some(rest(i, "\n"))
      case '/' if text.startsWith("/*", i) => Some(rest(i, "*/"))
      case _ => None
  }

  /** The labels a block's body already declares, read off the text rather than off a parse.
   *
   * It is the text because this runs *before* the rewrite, on a file the caller may not have parsed
   * — and because what matters here is whether the name is written, not what it resolves to.
   */
  private[sysl] def entryNames(body: String): Set[String] =
    entryLines(body).flatMap(_.trim.split("[ \t{=]", 2).headOption).filter(_.nonEmpty).toSet

  /** The lines of a block's body that declare something, which is every line that is not blank and
   * not wholly a comment.
   */
  private def entryLines(body: String): List[String] =
    body.linesIterator.map(_.stripTrailing).filter { l =>
      val t = l.trim

      t.nonEmpty && !t.startsWith("//") && !t.startsWith("#")
    }.toList

  /** The new line put in, with the block's own indent and — where its entries are lined up — its own
   * column for the coordinate.
   */
  private def insert(text: String, open: Int, close: Int, label: String, coordinate: String,
                     version: String): String = {
    val body    = text.substring(open, close)
    val lines   = entryLines(body)
    val indent  = lines.headOption.map(l => l.takeWhile(c => c == ' ' || c == '\t')).getOrElse("  ")

    // The column the siblings put their `{` at, taken only when they all agree about it: a block
    // that is already ragged gets one space, since there is no column to line up with.
    val braces  = lines.map(_.indexOf('{')).filter(_ > 0).distinct
    val at      = braces match
      case one :: Nil if one > indent.length + label.length => one
      case _ => indent.length + label.length + 1

    val pad   = " " * math.max(1, at - indent.length - label.length)
    val entry = s"""$indent$label$pad{ git = "$coordinate", version = "$version" }"""

    // Before the closing brace, on a line of its own, and after whatever indent that brace sits on
    // — a one-line `dependencies { … }` therefore becomes three, which is the only case where
    // anything already in the file moves, and it moves by gaining line breaks rather than by being
    // reflowed.
    val trailing = body.reverse.takeWhile(c => c == ' ' || c == '\t').reverse
    val kept     = body.dropRight(trailing.length)
    val lead     = if kept.isEmpty || kept.endsWith("\n") then "" else "\n"

    s"${text.substring(0, open)}$kept$lead$entry\n$trailing${text.substring(close)}"
  }
}
