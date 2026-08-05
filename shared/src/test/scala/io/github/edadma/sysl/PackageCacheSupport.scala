package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** A package cache built on disk rather than fetched, which is what lets the fetching and resolving
 * suites run with no network and no repository.
 *
 * The seam this leans on is real rather than a test hook: `Fetch.ensure` clones only when the cache
 * has not got the package, so a cache populated by hand exercises **every path except the clone
 * itself** — the `sysl.sum` check, the recorded-hash sibling, version selection, the import tables
 * and each of the refusals. What is left uncovered is one `git clone` invocation, and a unit suite
 * that reached the network to cover it would be trading a great deal for very little.
 */
trait PackageCacheSupport extends AnyFreeSpec with Matchers {

  /** A cache of its own per test, so that nothing here can be perturbed by, or perturb, the cache
   * the machine actually builds against.
   */
  protected def emptyCache(): String = createTempDirectory("sysl-pkg-cache-")

  /** A package placed in the cache at a coordinate and version, as a fetch would have left it.
   *
   * `files` are written under the package root; `hash` is the sibling recording what the tree hashed
   * to when it was written, which is what a `sysl.sum` is checked against on a cache hit.
   */
  protected def published(cache: String, coordinate: String, version: Version,
                          config: String, files: (String, String)*): String = {
    val dir = Fetch.directory(cache, coordinate, version)

    createDirectories(dir)
    if config.nonEmpty then writeFile(s"$dir/${PackageConfig.FileName}", config)

    for (path, text) <- files do
      Project.parentOf(s"$dir/$path").foreach(createDirectories)
      writeFile(s"$dir/$path", text)

    dir
  }

  /** The usual case: a package in the cache holding one module, in a directory of its own.
   *
   * A package's top-level directories are its modules (`13 §1`) and are therefore the names it
   * offers a consumer, so a package with none offers nothing — which makes this, rather than
   * `published`, what almost every test wants.
   */
  protected def publishedModule(cache: String, coordinate: String, version: Version, module: String,
                                name: String = "", deps: String = ""): String =
    published(cache, coordinate, version,
      manifest(if name.isEmpty then module else name, version.toString, deps),
      s"$module/$module.sysl" -> s"module $module\n")

  /** The hash a fetch would have recorded beside a cached package, computed from what is there. */
  protected def record(cache: String, coordinate: String, version: Version): String = {
    val dir  = Fetch.directory(cache, coordinate, version)
    val hash = Hashing.treeHash(dir) match
      case Right(h) => h
      case Left(e)  => fail(e)

    writeFile(s"$dir.hash", s"$hash\n")
    hash
  }

  /** A project on disk: a `package.hocon` and whatever directories it has of its own. */
  protected def project(config: String, dirs: String*): String = {
    val root = createTempDirectory("sysl-pkg-project-")

    if config.nonEmpty then writeFile(s"$root/${PackageConfig.FileName}", config)
    dirs.foreach(d => createDirectories(s"$root/$d"))
    root
  }

  /** A manifest naming a package and, optionally, what it depends on. */
  protected def manifest(name: String, version: String, deps: String = ""): String =
    s"""package { name = "$name", version = "$version" }
       |${if deps.isEmpty then "" else s"dependencies { $deps }"}
       |""".stripMargin

  protected def resolve(root: String, cache: String, sums: Sums = Sums.empty): Resolve.Graph = {
    val config = PackageConfig.read(readFile(s"$root/${PackageConfig.FileName}")) match
      case Right(c) => c
      case Left(e)  => fail(e)

    Resolve.graph(root, config, sums, cache) match
      case Right(g) => g
      case Left(e)  => fail(s"expected a graph, got: $e")
  }

  protected def resolveRefused(root: String, cache: String, sums: Sums = Sums.empty): String = {
    val config = PackageConfig.read(readFile(s"$root/${PackageConfig.FileName}")) match
      case Right(c) => c
      case Left(e)  => fail(e)

    Resolve.graph(root, config, sums, cache) match
      case Left(e)  => e
      case Right(g) => fail(s"expected a refusal, got: ${g.packages.map(_.canonical)}")
  }

  /** What a resolved graph selected, as coordinate-to-version, which is the answer MVS is asked
   * for and the one thing a package's directory only says indirectly.
   */
  protected def selected(g: Resolve.Graph): Map[String, String] =
    g.packages.filterNot(_.isRoot).flatMap(p => p.version.map(v => p.canonical -> v.toString)).toMap
}
