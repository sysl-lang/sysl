package sh.sysl

import io.github.edadma.cross_platform.*

// The driver's half of the package system (`packages.md`): the manifest read off the root, the
// dependencies fetched and version-selected, and the `sysl.sum` checked and written back.
// Everything below resolution is `Resolve`'s and `Fetch`'s.

/** What this compilation depends on, brought onto this machine and turned into what it needs from
 * them (`packages.md § 3`, `§ 5`, `§ 9`).
 *
 * A dependency reaches a compilation the way a `--lib` source tree does — as **more modules**, and
 * as a tree whose C is compiled beside the program's (`15 §7`). What is new beside those is the
 * `Packages` table: each fetched package's files are filed under a canonical prefix taken from its
 * coordinate, and the names its import lines write are read back through the manifest that named it.
 *
 * ==A `--lib` source root's own `dependencies` are here too, and used not to be==
 *
 * A package reached as a source root is the same package it is by coordinate, so what it depends on is
 * a property of *it* rather than of the road it arrived by — the argument `§ 13` makes about the
 * allocator, and the reason `§ 8`'s header requirements are read from a root as well. Handed the same
 * directory as a path, the driver read its `.sysl` and nothing else, so the dependency was neither
 * fetched nor mentioned: what a caller got was the unresolved-name cascade that follows a package
 * whose imports point at code nobody brought, none of it naming the package or the flag.
 *
 * **One graph rather than one per root**, which is what makes selection mean anything: MVS takes its
 * maximum over every claim at once (`§ 5`), so a coordinate two roots share resolves to one copy at one
 * version rather than to two the linker would have to choose between. The roots' entries are folded
 * into the list the project's own are read from, so `demanding`, `select` and the root's import table
 * cover them with nothing added.
 *
 * **Their bindings land in the root table**, which follows from where their source lands rather than
 * being a choice: a `--lib` root's files are filed under the empty prefix, the project's own name
 * space, because that is what `--lib` means. So the names its manifest binds are names the project's
 * own files can write too — no more true of a dependency's name than it always was of the root's own
 * modules.
 *
 * A `path` dependency of a root is resolved exactly as the project's own is, by the same
 * `Fetch.ensure`. Reading it against the root it was written in would be a *second* meaning for one
 * field, and whether a relative path should be read against a project root rather than the working
 * directory is the same question on both roads.
 *
 * `sysl.sum` is written back where a package was fetched that no line covered, so the first build
 * after adding a dependency records what it got and every build after that is checked against it.
 * Writing it is not fatal if it fails: a read-only checkout should still build, and the alternative
 * is refusing to compile over a file that exists to be compared against next time.
 */
private def dependencies(cfg: Config, project: PackageConfig, roots: List[String], os: Os)
    : Either[String, PackageSources] =
  for
    fromRoots <- libDependencies(roots)
    declared   = project.dependencies ::: fromRoots
    got       <- if declared.isEmpty then Right(PackageSources.none)
                 else resolveDependencies(cfg, project.copy(dependencies = declared), roots, os)
  yield got

/** Resolving a non-empty dependency list against this machine's cache, and recording what it got.
 *
 * The source roots are handed over as well as their dependencies, and for a different purpose: their
 * modules sit in the project's own name space, so they are names a dependency may not also claim
 * (`§ 9`), and nothing in a manifest tells `Resolve` they are there.
 */
private def resolveDependencies(cfg: Config, project: PackageConfig, roots: List[String], os: Os)
    : Either[String, PackageSources] = {
  val root = projectRoot(cfg.file)

  for
    cache <- Fetch.cacheRoot
    sums  <- readSums(root)
    graph <- Resolve.graph(root, project, sums, cache, roots)
    files <- collectPackages(graph, os)
  yield
    if graph.sumsChanged then writeSums(root, graph.sums)
    files
}

/** What each `--lib` source root says it depends on (`packages.md § 2`).
 *
 * A root with no manifest depends on nothing, exactly as for the header requirements and the allocator
 * beside it, and one whose manifest will not parse stops the build rather than being skipped — the
 * same three answers `libHeaderNeeds` and `libAllocators` give, off the same read.
 */
private def libDependencies(roots: List[String]): Either[String, List[Dependency]] =
  roots.foldLeft[Either[String, List[Dependency]]](Right(Nil)) { (acc, root) =>
    for
      seen   <- acc
      config <- readPackageConfig(root)
    yield seen ::: config.dependencies
  }

/** Each fetched package's source, filed under the canonical prefix that keeps its module names
 * apart from every other package's — and each one's directory, which is a tree the C walk visits.
 */
private def collectPackages(graph: Resolve.Graph, os: Os): Either[String, PackageSources] = {
  val fetched = graph.packages.filterNot(_.isRoot)

  try
    val each = fetched.map(p => p -> Project.collect(p.root, Some(os)))

    each.find(_._2.isEmpty) match
      case Some((p, _)) => Left(s"'${p.canonical}' holds no sysl source files")
      case None =>
        val owned = each.flatMap((p, sources) => sources.map(_ -> p.canonical)).toMap

        Right(PackageSources(
          each.flatMap(_._2),
          Packages(owned, graph.packages.map(p => p.canonical -> p.imports).toMap),
          fetched.map(_.root),
          fetched.flatMap(p =>
            p.config.headers.toList.sortBy(_._1).map((name, why) => HeaderNeed(p.canonical, name, why))),
          fetched.flatMap(p =>
            p.config.pkgConfig.toList.sortBy(_._1).map((mod, why) => LibNeed(p.canonical, mod, why))),
          fetched.flatMap(p => p.config.allocator.map(p.canonical -> _)),
          fetched.map(p => p.config.carriedDefines(p.root)).foldLeft(Map.empty[String, List[String]])(_ ++ _),
        ))
  // A malformed per-OS directory is a mistake in the package rather than a package that would not
  // read (`13 §5`), and the message names the directory and lists the operating systems there are —
  // so wrapping it in "cannot read a package" would bury the only part worth having.
  catch
    case e: SelectionError => Left(e.getMessage)
    case e: Exception      => Left(s"cannot read a package: ${e.getMessage}")
}

private def readSums(root: String): Either[String, Sums] = {
  val path = s"$root/${Sums.FileName}"

  if !isFile(path) then Right(Sums.empty)
  else
    try Sums.read(readFile(path))
    catch case e: Exception => Left(s"cannot read $path: ${e.getMessage}")
}

private def writeSums(root: String, sums: Sums): Unit =
  try writeFile(s"$root/${Sums.FileName}", sums.render)
  catch
    case e: Exception =>
      Console.err.println(s"warning: cannot write ${Sums.FileName}: ${e.getMessage}")

/** The project root: the directory the driver was given, or the one holding the file it was given.
 *
 * `13 § Open a` settles that the driver is *given* a root rather than discovering one, so this never
 * searches upward — a build that walked up would depend on directories above the one named.
 */
private def projectRoot(file: String): String =
  if isDirectory(file) then file
  else
    val slash = math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'))

    if slash >= 0 then file.substring(0, slash) else "."

/** The project config, read from the root this invocation was given (`packages.md § 1`).
 *
 * **A missing file is not an error.** A single-file program has no config and wants none, so what
 * comes back is the empty one — the same shape `13 §1` gives the anonymous root module. A file that
 * is *there* and will not read is a different thing entirely and stops the build: somebody wrote it,
 * and building while ignoring it would be building something other than what they asked for.
 *
 * The file is looked for beside the sources rather than searched for upwards. `13 § Open a` settles
 * that the driver is *given* a root rather than discovering one, and a search that walked upward
 * would make a build depend on directories above the one named.
 */
private def readPackageConfig(file: String): Either[String, PackageConfig] = {
  val path = s"${projectRoot(file)}/${PackageConfig.FileName}"

  if !isFile(path) then Right(PackageConfig.empty)
  else
    try PackageConfig.read(readFile(path))
    catch case e: Exception => Left(s"cannot read $path: ${e.getMessage}")
}
