package io.github.edadma.sysl

/** Which package each file came from, and what the names at the head of its import lines mean
 * (`packages.md § 9`).
 *
 * ==Identity is two-layered, and this is the layer that is not identity==
 *
 * A module's name is its directory path relative to the project root (`13 §1`), which makes every
 * module name **local and relative** — so a fetched `json` and a project's own `json` are the same
 * name with no domain in the path to separate them. Go does not have this problem because its import
 * path *is* the module name and is globally unique; sysl's rule is the opposite one.
 *
 * The answer is to keep names local and give each package a **canonical prefix** taken from its
 * coordinate, so a module `json` in `github.com/e/sysl-json` is really
 * `github.com.e.sysl-json.json`. That name is what `Modules.qualify` keys tables by and therefore
 * what `15 §2` mangles into every symbol, which is the property that makes two consumers naming one
 * package differently still link one copy of it. What a *file* writes stays short: the prefix is
 * added on the way in, and the leading segment of a written path is read back through this table.
 *
 * **The mount is never the identity.** If one project mounted a package as `json` and another as
 * `ejson`, a mangler keyed off the local name would emit `json$Parser.next` in one build and
 * `ejson$Parser.next` in the other — the same source producing different symbols, which makes a
 * shared artifact cache worthless and two consumers of one package unlinkable into one program.
 *
 * The empty prefix is the project being built. Its modules keep the names they were written with, so
 * a program with no dependencies goes through all of this unchanged — which is the point, and is why
 * `none` is what every existing caller gets.
 */
case class Packages(of: Map[Source, String] = Map.empty, imports: Map[String, Map[String, String]] = Map.empty) {

  /** The canonical prefix of the package a file belongs to, or the empty one for the project. */
  def prefixOf(file: Source): String = of.getOrElse(file, "")

  /** What a name at the head of an import line means, written in a file of the package `prefix`.
   *
   * Per-package, which is the whole point: a dependency's own source says `json` and means whatever
   * *it* calls `json`, while a consumer of that dependency says `json` and may mean something else.
   * Both arrive at one canonical name without ever having to agree on a spelling.
   */
  def mounted(prefix: String, name: String): Option[String] = imports.get(prefix).flatMap(_.get(name))

  /** Whether there is anything here at all — asked before any of the work below is done, since a
   * program with no dependencies is the ordinary case.
   */
  def isEmpty: Boolean = of.isEmpty && imports.isEmpty
}

object Packages {

  /** No packages: one project, its own modules, nothing fetched. */
  val none: Packages = Packages()

  /** The canonical prefix and a module's own name, joined — and the module's own name alone where
   * the package is the project being built.
   */
  def qualify(prefix: String, module: String): String =
    if prefix.isEmpty then module
    else if module.isEmpty then prefix
    else s"$prefix.$module"
}
