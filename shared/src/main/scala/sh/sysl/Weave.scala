package sh.sysl

import io.github.edadma.highlighter.Highlighter
import io.github.edadma.markdown.{EmojiConfig, MarkdownConfig, renderToHTML}

/** A literate source rendered as the document it already is — what `sysl weave` writes.
 *
 * **A `.lsysl` file is Markdown before anything touches it** (`Literate`), and this command is the
 * half of a literate system that turns it into something to look at. The other half is `tangle`,
 * which hands back the program; a build tangles too, so the pair is what sysl has been doing all
 * along with only one of the two ever spelled out.
 *
 * **The source reaches the renderer verbatim.** Nothing here re-fences a block, counts a backtick or
 * rewrites a line — the whole of the transformation is a `MarkdownConfig`, because the one thing the
 * literate format gives up is the one thing the renderer already has an option for:
 *
 * {{{
 *   indentedCodeLanguage = Some("sysl")
 * }}}
 *
 * An indent is Markdown's own spelling of a code block, which is what makes an unrendered `.lsysl`
 * readable — but an indented block carries no *language*, so a renderer sets it in a monospace font
 * and stops. That option is what puts the language back, and it puts it back at the point where the
 * highlighter is asked rather than by editing the document on the way past.
 *
 * **Math is the one thing the reader's browser does rather than this command.** The renderer writes
 * `\(…\)` and `\[…\]`, which is what KaTeX's auto-render reads, so the page carries a link to it.
 * That is a real cost and it is worth stating plainly: a woven document needs the network to set its
 * mathematics. Everything else — the prose, the code and its colouring — is markup in the file, so a
 * document read with no network loses its equations to TeX source and nothing else.
 *
 * **What this is not is an API reference.** The other thing a language's documentation command
 * usually means — a page per module, generated from declarations and the comments above them — is a
 * different product, and sysl cannot build it yet for a reason that has nothing to do with
 * rendering: there is no such thing as a documentation comment. A comment is lexical trivia here and
 * reaches no tree, so there is nothing for a generator to read.
 */
object Weave {

  /** The info string the code blocks carry, and the language the grammar answers to. */
  val Language: String = "sysl"

  /** Where KaTeX comes from.
   *
   * **Pinned to an exact version rather than tracking a range**, because a document woven today is
   * meant to still render the same way years from now, and a floating version is a promise nobody
   * made. Subresource integrity is deliberately absent: the hash would pin the *bytes* of a file
   * this command never sees, and getting it wrong fails closed with the mathematics silently unset.
   */
  private val KatexVersion: String = "0.16.11"
  private val KatexBase: String    = s"https://cdn.jsdelivr.net/npm/katex@$KatexVersion/dist"

  /** The grammar, parsed once.
   *
   * **A grammar that will not parse costs the colouring and not the document.** It is compiled in
   * and reconciled against `SyslLexical` by a test, so this cannot fail in a shipped build — but the
   * alternative to degrading is refusing to write a document over the styling of its code, and that
   * trade is not close.
   */
  private lazy val highlighter: Option[Highlighter] =
    Highlighter.fromJson(Grammar.sysl).toOption

  /** The renderer's settings.
   *
   * **The extension set is the site's**, so a page woven here and a page written by hand on `sysl.sh`
   * are read by one processor with one option set and one grammar. Two configurations would mean a
   * table or a callout that renders one way in the guide and another in the document generated from
   * it, which is a defect nobody would think to look for.
   */
  private def config: MarkdownConfig =
    MarkdownConfig.default.copy(
      autoHeadingIds       = true,
      tables               = true,
      strikethrough        = true,
      taskListItems        = true,
      extendedAutolinks    = true,
      footnotes            = true,
      smartPunctuation     = true,
      attributes           = true,
      callouts             = true,
      definitionLists      = true,
      emoji                = EmojiConfig.Unicode,
      math                 = true,
      indentedCodeLanguage = Some(Language),
      codeHighlighter      = Some { (code, lang) =>
        if lang == Language then highlighter.map(_.highlight(code)) else None
      },
    )

  /** The document a literate source holds, or the first thing wrong with the file.
   *
   * **The file is read by `Literate` before it is rendered, and the result of that reading is
   * thrown away.** It looks redundant — the renderer never sees the classification, and would render
   * the text happily without it — and it is the point: a document that wove cleanly out of a file the
   * compiler refuses would be documentation of a program that does not exist. Sharing the failures
   * is worth more than diagnostics tuned for a reader.
   */
  def render(source: Source): Either[String, String] =
    if !Literate.named(source.name) then
      Left(s"${source.name} is an ordinary sysl program — 'weave' renders the prose of a " +
        s"'${Literate.Extension}' file, and a program that is all code has none")
    else Literate.tangle(source).map(_ => page(source.name, renderToHTML(source.text, config)))

  /** The rendered body wrapped in the page that carries it.
   *
   * **The styling is in the file rather than beside it.** A woven document is a leaf artifact —
   * something to open, move or mail — rather than something a site build consumes, so one file that
   * works by itself beats a directory that has to stay together.
   */
  private def page(name: String, body: String): String =
    s"""<!doctype html>
       |<html lang="en">
       |<head>
       |<meta charset="utf-8">
       |<meta name="viewport" content="width=device-width, initial-scale=1">
       |<title>${escape(name)}</title>
       |<link rel="stylesheet" href="$KatexBase/katex.min.css">
       |<style>
       |$Style</style>
       |</head>
       |<body>
       |<main>
       |$body
       |</main>
       |<script defer src="$KatexBase/katex.min.js"></script>
       |<script defer src="$KatexBase/contrib/auto-render.min.js"
       |        onload="renderMathInElement(document.body, { delimiters: [
       |          { left: '\\\\[', right: '\\\\]', display: true },
       |          { left: '\\\\(', right: '\\\\)', display: false }
       |        ] })"></script>
       |</body>
       |</html>
       |""".stripMargin

  /** What a source's document is called when a whole tree is woven into a directory: the file's own
   * name with the document's extension in place of the source's, and its directories dropped so a
   * nested tree lands flat rather than rebuilding itself under the output.
   */
  def documentName(name: String): String = {
    val base = name.split('/').last.stripSuffix(Literate.Extension)

    s"$base.html"
  }

  /** Enough escaping for a title, which is the only place a name reaches the markup. */
  private def escape(s: String): String =
    s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  /** The document's stylesheet.
   *
   * **A colour is named once, as a custom property, and the dark palette re-answers the same
   * names.** The rules below therefore say what a thing *is* rather than what colour it is, and the
   * two palettes cannot drift into disagreeing about which parts of the page are related.
   *
   * The prose is set to a measure rather than to the window, since a line of text that runs the
   * width of a monitor is one the eye loses its place in — and the code is deliberately allowed past
   * that measure, because a program wrapped to a prose column is harder to read rather than easier.
   */
  private val Style: String =
    """:root {
      |  --bg: #fdfdfc; --fg: #1a1a18; --muted: #6b6b66; --rule: #e2e2dd;
      |  --code-bg: #f6f6f4; --accent: #4a5f8a;
      |  --hl-keyword: #8a3d6b; --hl-string: #3f6b40; --hl-comment: #86867e;
      |  --hl-number: #9a5b23; --hl-type: #3d6478; --hl-function: #4a4a8a;
      |  --hl-variable: #1a1a18; --hl-punctuation: #6b6b66;
      |}
      |
      |@media (prefers-color-scheme: dark) {
      |  :root {
      |    --bg: #16161a; --fg: #e6e6e2; --muted: #9a9a94; --rule: #2c2c33;
      |    --code-bg: #1e1e24; --accent: #9db2dd;
      |    --hl-keyword: #d69ac4; --hl-string: #9ccb9d; --hl-comment: #7e7e88;
      |    --hl-number: #e0a86a; --hl-type: #8fc0d6; --hl-function: #a8a8e0;
      |    --hl-variable: #e6e6e2; --hl-punctuation: #9a9a94;
      |  }
      |}
      |
      |* { box-sizing: border-box; }
      |
      |body {
      |  margin: 0;
      |  background: var(--bg);
      |  color: var(--fg);
      |  font-family: ui-serif, Georgia, "Times New Roman", serif;
      |  font-size: 18px;
      |  line-height: 1.65;
      |  -webkit-text-size-adjust: 100%;
      |}
      |
      |main { max-width: 34em; margin: 0 auto; padding: 4rem 1.25rem 8rem; }
      |
      |h1, h2, h3, h4 {
      |  font-family: ui-sans-serif, system-ui, -apple-system, "Helvetica Neue", sans-serif;
      |  line-height: 1.25;
      |  margin: 2.5em 0 0.6em;
      |}
      |h1 { font-size: 1.9em; margin-top: 0; letter-spacing: -0.015em; }
      |h2 { font-size: 1.4em; }
      |h3 { font-size: 1.15em; }
      |h4 { font-size: 1em; color: var(--muted); }
      |
      |p, ul, ol, dl, blockquote { margin: 0 0 1.15em; }
      |
      |a { color: var(--accent); text-decoration-thickness: 1px; text-underline-offset: 2px; }
      |
      |blockquote {
      |  margin-left: 0;
      |  padding-left: 1.1em;
      |  border-left: 2px solid var(--rule);
      |  color: var(--muted);
      |}
      |
      |hr { border: 0; border-top: 1px solid var(--rule); margin: 3em 0; }
      |
      |/* The program is the reason the document exists, so it is given the room the prose is denied
      |   -- it breaks out of the measure and scrolls rather than wrapping. */
      |pre {
      |  background: var(--code-bg);
      |  border: 1px solid var(--rule);
      |  border-radius: 5px;
      |  padding: 0.9em 1.1em;
      |  overflow-x: auto;
      |  margin: 0 0 1.15em;
      |  width: 100%;
      |}
      |@media (min-width: 60em) {
      |  pre { width: calc(100% + 6rem); margin-left: -3rem; }
      |}
      |
      |code, pre, kbd {
      |  font-family: ui-monospace, "SF Mono", Menlo, Consolas, monospace;
      |  font-size: 0.82em;
      |}
      |pre code { font-size: inherit; background: none; padding: 0; border: 0; }
      |
      |/* Inline code sits in a sentence, so it is toned down rather than boxed -- a border on every
      |   mention of an identifier turns a paragraph into a fence. */
      |code {
      |  background: var(--code-bg);
      |  padding: 0.12em 0.34em;
      |  border-radius: 3px;
      |}
      |
      |.hl-keyword     { color: var(--hl-keyword); }
      |.hl-string      { color: var(--hl-string); }
      |.hl-comment     { color: var(--hl-comment); font-style: italic; }
      |.hl-number      { color: var(--hl-number); }
      |.hl-type        { color: var(--hl-type); }
      |.hl-function    { color: var(--hl-function); }
      |.hl-variable    { color: var(--hl-variable); }
      |.hl-punctuation { color: var(--hl-punctuation); }
      |
      |table { border-collapse: collapse; width: 100%; margin: 0 0 1.15em; font-size: 0.94em; }
      |th, td { text-align: left; padding: 0.4em 0.7em; border-bottom: 1px solid var(--rule); }
      |th { font-weight: 600; }
      |
      |img { max-width: 100%; height: auto; }
      |
      |/* Display mathematics scrolls on its own rather than widening the page: an equation nobody
      |   anticipated is the one thing on a page that has no natural maximum width. */
      |.math.display { overflow-x: auto; overflow-y: hidden; padding: 0.4em 0; }
      |
      |.footnotes { font-size: 0.9em; color: var(--muted); border-top: 1px solid var(--rule);
      |             margin-top: 3em; padding-top: 1em; }
      |""".stripMargin
}
