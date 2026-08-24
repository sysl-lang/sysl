package sh.sysl.doc

import sh.sysl.*
import sh.sysl.doc.ApiModel.*

/** The model written out as Markdown — one page per module, plus an index.
 *
 * **Markdown rather than a self-contained HTML site, against what every comparable tool does.**
 * scaladoc, javadoc and rustdoc all emit a complete site with its own theme and search index. The
 * reason for not following them is the shape of this org: a `docs/` folder of generated Markdown in
 * a package repository is readable in the GitHub UI with **no tooling, no hosting and nothing
 * installed**, and Rust needs an entire hosted service — docs.rs — to solve exactly that for crates.
 * Generated Markdown is also diffable, where generated HTML cannot be reviewed in a commit; and the
 * site's own pipeline already reads Markdown, so nothing here becomes a second toolchain to keep
 * current.
 *
 * **Nothing below emits HTML or a CSS class, and that constraint is the interesting part.** The
 * `juicerapi` theme hooks structure and slugified heading ids instead, precisely so that this output
 * is not written for one renderer. A generator reaching for `<div class="…">` to give a theme
 * something to grab would produce a file that reads *worse* in the repository than in a browser,
 * which is backwards — the repository copy is the one this whole decision exists to protect.
 *
 * The four shapes the theme understands, and all this file emits:
 *
 *   - `## Functions` — an `<h2>` per kind group
 *   - ``### `push` `` — an `<h3>` per declaration
 *   - a fenced ` ```sysl ` block directly under it — the signature
 *   - a pipe table — the parameters, fields or variants
 *
 * plus `## Index` at the top, whose links become pills.
 */
object MarkdownWriter {

  /** One generated file: where it goes, relative to the output root, and what is in it. */
  case class Page(path: String, text: String)

  /** The heading a group of symbols sits under. */
  private def groupTitle(kind: Kind): String = kind match
    case Kind.Const          => "Constants"
    case Kind.Function       => "Functions"
    case Kind.Type           => "Types"
    case Kind.Trait          => "Traits"
    case Kind.Implementation => "Implementations"

  /** A module's page filename: `sysl.text` becomes `sysl-text.md`.
   *
   * The dots go because a dot in a filename reads as an extension to too many things, and because
   * the URL a site serves it at should be a path segment rather than something needing quoting.
   */
  def fileNameOf(module: String): String = s"${module.replace('.', '-')}.md"

  /** GitHub's heading-slug algorithm, which is what the generated anchors have to agree with.
   *
   * **This must stay identical to `githubSlugify` in juicer**, because the same file is read in two
   * places — as a page on the site and as a file in the repository — and a symbol index that links
   * to its own headings is dead in one of them if the two disagree. The site is configured with
   * `slugStyle = "github"` for exactly this reason; that setting and this function are two halves of
   * one contract, and `MarkdownWriterTests` pins the cases that differ from the other algorithm.
   *
   * The rule: lowercase; delete anything that is not a letter, digit, space, hyphen or underscore;
   * turn spaces into hyphens. Nothing is collapsed and nothing is trimmed.
   */
  def slug(text: String): String = {
    val sb = new StringBuilder

    for ch <- text.toLowerCase do
      if ch.isLetterOrDigit || ch == '-' || ch == '_' then sb += ch
      else if ch == ' ' then sb += '-'

    sb.toString
  }

  /** The anchors on one page, with GitHub's numeric suffix for a repeat.
   *
   * A type and the function that constructs it conventionally share a name in sysl — `buf()` and
   * `Buf`, `map()` and `Map` — and case-folding makes those one slug. GitHub renders the second as
   * `buf-1`, so that is what a link has to say. Computed once for the whole page rather than per
   * link, because the answer depends on everything before it.
   */
  private def anchors(headings: List[String]): Map[String, String] = {
    val seen  = scala.collection.mutable.HashMap.empty[String, Int]
    val built = scala.collection.mutable.LinkedHashMap.empty[String, String]

    for h <- headings do
      val base = slug(h)

      val id =
        seen.get(base) match
          case None => seen(base) = 0; base
          case Some(n) =>
            var next = n + 1
            var cand = s"$base-$next"

            while seen.contains(cand) do
              next += 1
              cand = s"$base-$next"

            seen(base) = next
            seen(cand) = 0
            cand

      built(h) = id

    built.toMap
  }

  /** The heading text for a symbol — the name, in code, so it reads as the thing you type.
   *
   * An `impl` is the exception: its "name" is already a phrase (`Display for Vec2`), so wrapping the
   * whole of it in one code span would set the word `for` in the mono face.
   */
  private def headingOf(s: Symbol): String =
    if s.kind == Kind.Implementation then s.name else s"`${s.name}`"

  /** One module's page. */
  def modulePage(m: Module, version: Option[String] = None): Page = {
    val kinds    = Kind.values.toList.filter(k => m.of(k).nonEmpty)
    val headings = kinds.flatMap(k => m.of(k).map(headingOf))
    val ids      = anchors(headings)
    val out      = new StringBuilder

    // ----- frontmatter -----
    //
    // Deliberately small. GitHub renders YAML frontmatter as a table, so a big block of generated
    // metadata is a wall of noise at the top of the file people read in the repository. Everything
    // a reader needs is in the body; this is what the THEME needs and no more.
    out ++= "---\n"
    out ++= s"title: ${m.name}\n"
    out ++= "layout: api-module\n"
    // The site default assumes a layout supplies the <h1> and an author's `#` sits beneath it. A
    // generated body's `##` groups are already meant to be <h2>, and have to land at the same level
    // here as when this file is read with no layout at all.
    out ++= "headingShift: 0\n"
    out ++= s"module: ${m.name}\n"
    if m.summary.nonEmpty then out ++= s"summary: ${yamlScalar(m.summary)}\n"
    if m.capabilities.nonEmpty then out ++= s"requires: ${yamlScalar(m.capabilities.mkString(", "))}\n"
    version.foreach(v => out ++= s"since: ${yamlScalar(v)}\n")
    out ++= "---\n\n"

    // ----- the module's own prose -----
    //
    // The summary is in the frontmatter and the theme renders it as the lead, so emitting it again
    // here would print it twice on the site. What goes in the body is whatever came AFTER the first
    // sentence — which is also why `Doc` splits the two rather than making the page re-derive one.
    m.doc.foreach { d =>
      val rest = d.body.stripPrefix(d.summary).trim

      if rest.nonEmpty then out ++= s"$rest\n\n"
    }

    // ----- the symbol index -----
    if headings.nonEmpty then
      out ++= "## Index\n\n"
      out ++= headings.map(h => s"[$h](#${ids(h)})").mkString(" ")
      out ++= "\n\n"

    // ----- the groups -----
    for k <- kinds do
      out ++= s"## ${groupTitle(k)}\n\n"

      for s <- m.of(k) do out ++= symbolSection(s, ids)

    Page(fileNameOf(m.name), out.result().stripTrailing() + "\n")
  }

  /** One symbol: heading, signature, prose, and whatever table its shape calls for. */
  private def symbolSection(s: Symbol, ids: Map[String, String]): String = {
    val out = new StringBuilder

    out ++= s"### ${headingOf(s)}\n\n"
    out ++= s"```sysl\n${s.signature}\n```\n\n"

    // A deprecation goes directly under the signature rather than after the prose — it is the thing
    // a reader has to see before deciding to use this, and a callout is how it is said because
    // GitHub renders one natively and the theme already styles it.
    s.doc.flatMap(_.tags.find(_.name == "deprecated")).foreach { t =>
      out ++= s"> [!WARNING]\n> **Deprecated.** ${oneLine(t.text)}\n\n"
    }

    s.doc.foreach { d =>
      if d.body.nonEmpty then out ++= s"${d.body}\n\n"
    }

    // A module whose author explained everything in prose — or has not written any yet — produces a
    // bare signature. Saying so is the honest answer rather than leaving a reader wondering whether
    // the page failed to render.
    if !s.documented then out ++= "> [!NOTE]\n> Undocumented.\n\n"

    s.doc.foreach { d =>
      val ps = d.params.filter(_.subject.isDefined)

      if ps.nonEmpty then
        out ++= "| Parameter | Description |\n|---|---|\n"

        for p <- ps do out ++= s"| `${p.subject.get}` | ${cell(p.text)} |\n"

        out ++= "\n"

      val tps = d.tparams.filter(_.subject.isDefined)

      if tps.nonEmpty then
        out ++= "| Type parameter | Description |\n|---|---|\n"

        for p <- tps do out ++= s"| `${p.subject.get}` | ${cell(p.text)} |\n"

        out ++= "\n"

      d.returns.foreach(r => out ++= s"**Returns** ${oneLine(r.text)}\n\n")

      // The rest of the vocabulary is prose that means what it means anywhere. Rendering each as a
      // labelled line keeps the order the author wrote them in, which `@see` in particular depends
      // on.
      for t <- d.tags if Carried(t.name) do
        out ++= s"**${t.name.capitalize}** ${oneLine(t.text)}\n\n"
    }

    // Members are the type's own methods, indented a level under it. They get no signature fence of
    // their own beyond the table, because a type with twenty methods would otherwise be twenty
    // fenced blocks and the page stops being scannable.
    if s.members.nonEmpty then
      val visible = s.members.filter(_.access == Access.Public)

      if visible.nonEmpty then
        out ++= "| Member | Signature | Description |\n|---|---|---|\n"

        for m <- visible do out ++= s"| `${m.name}` | `${inlineCode(m.signature)}` | ${cell(m.summary)} |\n"

        out ++= "\n"

    out.result()
  }

  /** The tags rendered as labelled lines under a symbol. `deprecated` is absent because it is a
   * callout above, and the three checkable ones because they are tables.
   */
  private val Carried = Set("see", "note", "example", "since")

  /** The index page listing every module. */
  def indexPage(modules: List[Module], title: String, version: Option[String] = None): Page = {
    val out = new StringBuilder

    out ++= "---\n"
    out ++= s"title: ${yamlScalar(title)}\n"
    out ++= "layout: api-index\n"
    out ++= "headingShift: 0\n"
    version.foreach(v => out ++= s"version: ${yamlScalar(v)}\n")
    out ++= "---\n\n"

    out ++= "## Modules\n\n"
    out ++= "| Module | Summary |\n|---|---|\n"

    for m <- modules do
      val link = fileNameOf(m.name).stripSuffix(".md")

      out ++= s"| [`${m.name}`]($link/) | ${cell(m.summary)} |\n"

    Page("_index.md", out.result().stripTrailing() + "\n")
  }

  /** Every page for a set of modules. */
  def pages(modules: List[Module], title: String, version: Option[String] = None): List[Page] =
    indexPage(modules, title, version) :: modules.map(modulePage(_, version))

  /** A YAML scalar that cannot be misread.
   *
   * Quoting always, rather than only when it looks necessary, because the cases that need it are the
   * ones nobody thinks of — a summary beginning with `[`, containing `: `, or reading as `yes` — and
   * a doc comment's first sentence is arbitrary prose. The escape set is YAML's for a double-quoted
   * scalar.
   */
  private def yamlScalar(s: String): String =
    "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + "\""

  /** Prose flattened to one line, for somewhere a line break would end the construct. */
  private def oneLine(s: String): String = s.trim.replaceAll("\\s+", " ")

  /** Prose as a table cell.
   *
   * A `|` inside a cell ends it, so it is escaped — which matters more here than it looks, because a
   * sysl doc comment describing a bitwise operation or a `Fn` type is exactly where one turns up.
   */
  private def cell(s: String): String = oneLine(s).replace("|", "\\|")

  /** A signature inside an inline code span in a table.
   *
   * Backticks cannot be escaped inside a code span, and a `|` still ends the cell — so both are
   * handled here rather than by `cell`, which is for prose. A multi-line signature is flattened,
   * since a table cell has no lines.
   */
  private def inlineCode(s: String): String =
    s.replaceAll("\\s+", " ").replace("|", "\\|").replace("`", "'")
}
