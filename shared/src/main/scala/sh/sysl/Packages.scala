package sh.sysl

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

  /** What a written module path means, in a file of the package `prefix`.
   *
   * Per-package, which is the whole point: a dependency's own source says `json` and means whatever
   * *it* calls `json`, while a consumer of that dependency says `json` and may mean something else.
   * Both arrive at one canonical name without ever having to agree on a spelling.
   *
   * **The whole path is offered and not its first segment**, because what a package binds is a module
   * path — `sh.sysl.table` for one namespaced by reverse DNS, whose `sh/` holds no source and is
   * therefore no module of its own (`13 §1`). A single-segment lookup could only ever have found
   * `sh`, which every such package would claim and none of them declares.
   *
   * The match is by longest key, so a name reaches the most specific package that offers it and
   * anything under that name comes with it: `sh.sysl.table.sub` is answered by `sh.sysl.table` and
   * keeps its tail. At most one key can match at all — resolution refuses two packages whose paths
   * nest — so the longest is a tie-break that never has a tie to break, and is written that way so
   * that a bug in the refusal is a wrong answer rather than an arbitrary one.
   */
  def mounted(prefix: String, written: String): Option[String] =
    imports.get(prefix).flatMap { table =>
      table.keys.filter(k => written == k || written.startsWith(s"$k."))
        .maxByOption(_.length)
        .map(k => table(k) + written.drop(k.length))
    }

  /** Whether there is anything here at all — asked before any of the work below is done, since a
   * program with no dependencies is the ordinary case.
   */
  def isEmpty: Boolean = of.isEmpty && imports.isEmpty
}

/** One package's declared need of a header it includes and does not carry (`packages.md § 8`).
 *
 * `who` is the package's coordinate rather than its module name, because that is what a consumer
 * wrote in their `dependencies` block and therefore what they can go and look at. `why` is the
 * package's own prose and is quoted rather than summarised.
 */
case class HeaderNeed(who: String, name: String, why: String)

/** What a project's `dependencies` block brings to one compilation.
 *
 * Four things and not one, because a package reaches a build by more than one road. Its `.sysl` is
 * **more modules** and joins the compilation's own; the table keeps its module names apart from
 * every other package's; its directory is a tree whose C is compiled and linked beside the
 * program's (`15 §7`, `NativeSources`); and what that C needs to *find* is something only the
 * consumer can supply, so the package states it and the driver checks it.
 *
 * The third is here because it was once missing. A package's sysl compiled and its C was dropped
 * with no warning, so a build against any package carrying a shim ended at the linker naming symbols
 * that package's own C defines — which `packages.md § 7` had already promised would not happen, on
 * the grounds that sysl compiles a package's C declaratively and needs no build script to do it.
 */
case class PackageSources(sources: List[Source], packages: Packages, roots: List[String],
                          needs: List[HeaderNeed] = Nil)

object PackageSources {

  /** A project with no dependencies, which is the ordinary case and costs nothing. */
  val none: PackageSources = PackageSources(Nil, Packages.none, Nil)
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
