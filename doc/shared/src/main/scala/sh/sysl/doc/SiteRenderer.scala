package sh.sysl.doc

import io.github.edadma.juicer.App
import io.github.edadma.path.Path

/** Rendering the generated Markdown as a site, with juicer.
 *
 * **It builds a site that already exists rather than scaffolding one, and that is a real constraint
 * rather than a simplification.** The `juicerapi` theme is a *bundled juicer theme* — it lives in
 * juicer's own repository beside the other eleven — and `juicer-core` is a library jar, which carries
 * no theme files. So there is nothing here that could conjure a themed site out of nothing, and
 * pretending otherwise would produce a build that fails with "no template was found for rendering
 * _index" and no explanation.
 *
 * **What that means in practice is the shape the real customer already has.** sysl.sh is a juicer
 * site with its themes in it; the generated pages are written into its content tree with `-o`, and
 * `--site` then builds the whole site the ordinary way. A package repository wanting the same thing
 * runs `juicer theme add` once and gets a site of its own.
 *
 * **And the Markdown does not need any of this.** That is the point of decision 2 on card 0256: the
 * pages are readable in the repository with no theme, no hosting and no juicer at all. Rendering is
 * a convenience for somebody who wants their own docs in a browser, which is why it is behind a flag
 * rather than being what the command does.
 */
object SiteRenderer {

  /** Build the juicer site rooted at `site`, writing into `site/public`.
   *
   * `markdown` is the directory the pages were written to. It is not passed to juicer — the site's
   * own `site.toml` decides what its content tree is — but it is checked against the site root, so
   * that generating into one place and building another fails here with a sentence rather than
   * building a site that silently lacks the pages.
   *
   * Throws on failure; juicer's `problem` prints to stderr and throws, which is its documented
   * cross-platform path out (`sys.exit` is not linkable on Scala.js). The caller turns that into an
   * exit code.
   */
  def build(markdown: String, site: String): Unit = {
    val root = Path(site)
    val out  = root / "public"

    if !within(markdown, site) then
      sys.error(
        s"the pages were written to '$markdown', which is not inside the site at '$site' — " +
          "give -o a directory under the site's content tree, or --site the site that holds them",
      )

    App.build(
      baseConfig = "standard",
      verbose = false,
      baseurl = None,
      src = root,
      dst = out,
      drafts = false,
      future = false,
    )
  }

  /** Whether `inner` names a path under `outer`.
   *
   * Compared as normalized absolute paths, since the two arrive from different flags and one is
   * routinely relative while the other is not — a textual `startsWith` on what the user typed
   * answers "no" for `-o docs/api --site docs`, which is the commonest correct invocation there is.
   */
  private def within(inner: String, outer: String): Boolean = {
    val i = Path(inner).toAbsolutePath.normalize.toString
    val o = Path(outer).toAbsolutePath.normalize.toString

    i == o || i.startsWith(if o.endsWith("/") then o else s"$o/")
  }
}
