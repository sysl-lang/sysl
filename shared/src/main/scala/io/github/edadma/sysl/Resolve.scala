package io.github.edadma.sysl

import io.github.edadma.cross_platform.*

/** One package of a resolved graph: where its source is, what it says about itself, and what the
 * names in *its* import lines mean.
 *
 * `imports` is per-package and that is the whole of `packages.md § 9`'s two-layer identity. A
 * package's own source says `json.Parser` and means whatever *it* calls `json`; a consumer of that
 * package says `json.Parser` and may mean something else entirely. Both resolve through their own
 * table to one canonical name, so the two never have to agree on a spelling and always agree on
 * which code they linked.
 */
case class ResolvedPackage(
    canonical: String,
    root: String,
    config: PackageConfig,
    imports: Map[String, String],
    version: Option[Version] = None,
) {

  /** Whether this is the project being built rather than something it depends on. The root project
   * has no coordinate, and its modules keep the names they were written with.
   */
  def isRoot: Boolean = canonical.isEmpty
}

/** The dependency graph, resolved (`packages.md § 5`).
 *
 * Minimal Version Selection: the version chosen for a package is **the highest minimum anybody
 * asked for**, not the newest that exists. It is a walk taking a maximum rather than a search, which
 * is what makes it a few hundred lines instead of a solver, and it gives three properties that fall
 * out of the arithmetic rather than out of cleverness — adding a dependency cannot silently upgrade
 * an unrelated one, the answer is a pure function of the manifests reachable from this one, and
 * upgrading is an edit to a file rather than a command that walks everything forward.
 *
 * ==Selecting and materializing are two passes, and they have to be==
 *
 * A floor can rise *after* the package it belongs to has been reached: a dependency named 1.2.0 and
 * something further down the graph named 1.4.0, so what was read first is not what gets built. One
 * pass that resolved names as it walked would have built the import table of a version it then
 * stopped using. So the first pass reads manifests and does nothing but raise floors, and the second
 * pass runs over the answer, by which time every version is final.
 */
object Resolve {

  /** Everything the build needs from the package system: the packages in a stable order, and the
   * `sysl.sum` that should be on disk afterwards.
   */
  case class Graph(packages: List[ResolvedPackage], sums: Sums, sumsChanged: Boolean)

  /** What the selecting pass carries: the version floors, the manifests read so far, the packages
   * that are directories rather than coordinates, and the sums as they stand.
   */
  private case class State(
      selected: Map[String, Version] = Map.empty,
      manifests: Map[(String, Version), (String, PackageConfig)] = Map.empty,
      locals: Map[String, (String, PackageConfig)] = Map.empty,
      sums: Sums = Sums.empty,
      changed: Boolean = false,
  ) {

    /** Every requirement raises a floor and nothing ever lowers one, which is the whole of MVS. */
    def demanding(deps: List[Dependency]): State =
      deps.foldLeft(this) {
        case (s, Dependency(_, Origin.Git(coordinate, version), _)) =>
          if s.selected.get(coordinate).forall(_ < version) then
            s.copy(selected = s.selected + (coordinate -> version))
          else s
        case (s, _) => s
      }

    def withFetch(dep: Dependency, got: Fetch.Fetched): State =
      (got.hash, dep.origin) match
        case (Some(hash), Origin.Git(coordinate, version)) =>
          copy(sums = sums.recording(coordinate, version, hash), changed = true)
        case _ => this
  }

  /** Resolves the graph rooted at `root`, fetching whatever this machine has not got.
   *
   * `cache` is passed in rather than found here so that a test can resolve a whole graph without
   * writing into the machine's own package cache — the same reason `PackageConfig.read` takes text
   * rather than a path.
   */
  def graph(root: String, config: PackageConfig, sums: Sums, cache: String): Either[String, Graph] =
    for
      withLocals <- readLocals(config.dependencies, State(sums = sums), cache)
      settled    <- select(withLocals.demanding(config.dependencies), cache)
      rootTable  <- tableOf(PackageConfig.FileName, root, config.dependencies, settled)
      packages   <- materialize(settled)
      tables     <- collect(packages)(p => tableOf(owner(p), p.root, p.config.dependencies, settled)
                      .map(t => p.copy(imports = t)))
    yield Graph(ResolvedPackage("", root, config, rootTable) :: tables, settled.sums, settled.changed)

  private def owner(p: ResolvedPackage): String =
    p.version.map(v => s"${p.canonical} ${v.tag}").getOrElse(p.canonical)

  /** The path dependencies of the **root** manifest, which are not part of version selection.
   *
   * A directory beside the consumer has no version to select and no coordinate to be identified by,
   * so it takes no part in MVS and is simply read. A *dependency* may not have one: a relative path
   * in a fetched package would be relative to a checkout that only exists on the machine that made
   * it, which is a build that works for its author and nobody else.
   */
  private def readLocals(deps: List[Dependency], state: State, cache: String): Either[String, State] =
    deps.foldLeft(Right(state): Either[String, State]) { (acc, dep) =>
      dep.origin match
        case Origin.Git(_, _) => acc
        case Origin.Local(_) =>
          for
            before <- acc
            got    <- Fetch.ensure(dep, before.sums, cache)
            theirs <- configOf(got.root)
            _      <- Either.cond(theirs.dependencies.forall(isGit), (),
                        s"'${dep.label}' is a path dependency that itself has a path dependency — a " +
                          "relative path only means something in the project it was written in")
          yield before.copy(locals = before.locals + (dep.canonical -> (got.root, theirs)))
                      .demanding(theirs.dependencies)
    }

  private def isGit(dep: Dependency): Boolean = dep.origin match
    case Origin.Git(_, _) => true
    case Origin.Local(_)  => false

  /** The selecting pass: read the manifest of every floor, raise whatever it demands, repeat.
   *
   * It terminates because a floor only rises and a coordinate has finitely many versions, so each
   * step either reads a pair nothing has read or finds none left and stops.
   */
  private def select(state: State, cache: String): Either[String, State] =
    state.selected.toList.sortBy(_._1).find(pair => !state.manifests.contains(pair)) match
      case None => Right(state)
      case Some(pair @ (coordinate, version)) =>
        val dep = Dependency(coordinate, Origin.Git(coordinate, version))

        for
          got    <- Fetch.ensure(dep, state.sums, cache)
          theirs <- configOf(got.root)
          _      <- Either.cond(theirs.dependencies.forall(isGit), (),
                      s"$coordinate ${version.tag} has a path dependency, which only means something " +
                        "in the project it was written in")
          next    = state.withFetch(dep, got)
                      .copy(manifests = state.manifests + (pair -> (got.root, theirs)))
                      .demanding(theirs.dependencies)
          settled <- select(next, cache)
        yield settled

  /** Every package the build will read, at the version selection settled on.
   *
   * The manifests map holds every version that was *looked at*, which is more than what is used —
   * a floor that rose left the lower one behind. Reading the answer off `selected` rather than off
   * the manifests is what drops those.
   */
  private def materialize(state: State): Either[String, List[ResolvedPackage]] = {
    val fromGit = state.selected.toList.sortBy(_._1).map { (coordinate, version) =>
      val (dir, config) = state.manifests((coordinate, version))

      ResolvedPackage(coordinate.replace('/', '.'), dir, config, Map.empty, Some(version))
    }

    val fromPath = state.locals.toList.sortBy(_._1).map { (canonical, entry) =>
      ResolvedPackage(canonical, entry._1, entry._2, Map.empty)
    }

    Right(fromGit ::: fromPath)
  }

  /** One package's import table: what each name it writes at the head of an import line means.
   *
   * Built after selection, so a dependency that named 1.2.0 while something else named 1.4.0 gets
   * the manifest of the version that is actually being built — which is where its *name* comes from
   * when no mount overrides it, and is exactly the thing one pass would have got wrong.
   */
  private def tableOf(owner: String, ownerRoot: String, deps: List[Dependency], state: State)
      : Either[String, Map[String, String]] =
    deps.foldLeft(Right(Map.empty[String, String]): Either[String, Map[String, String]]) { (acc, dep) =>
      for
        table  <- acc
        theirs <- theirConfig(dep, state)
        local  <- rootName(dep, theirs).left.map(e => s"$owner: $e")
        _      <- noCollision(owner, ownerRoot, table, local, dep)
      yield table + (local -> dep.canonical)
    }

  /** The manifest of the version of `dep` that is being built, which for a git dependency is the one
   * selection settled on rather than the one this edge asked for.
   */
  private def theirConfig(dep: Dependency, state: State): Either[String, PackageConfig] =
    dep.origin match
      case Origin.Local(_) =>
        state.locals.get(dep.canonical).map(_._2).toRight(s"'${dep.label}' was never read")
      case Origin.Git(coordinate, _) =>
        for
          version <- state.selected.get(coordinate).toRight(s"'$coordinate' was never selected")
          entry   <- state.manifests.get((coordinate, version)).toRight(s"'$coordinate' was never read")
        yield entry._2

  /** The two ways one root name can be claimed twice, both refused rather than resolved by order.
   *
   * This is `§ 9`'s load-bearing rule: a collision is an error and never a silent winner. The JVM
   * classpath is the counter-example the chapter names — the same package in two jars is decided by
   * jar order, quietly, and what results is a program somebody has to bisect to understand.
   */
  private def noCollision(owner: String, ownerRoot: String, table: Map[String, String], local: String,
                          dep: Dependency): Either[String, Unit] =
    table.get(local) match
      case Some(other) if other != dep.canonical =>
        Left(s"$owner: '$local' is the root name of two packages — $other and ${dep.canonical}. " +
          s"Give one of them a 'mount' in ${PackageConfig.FileName} to say which name it takes here")

      case _ =>
        // The consumer's own modules are in the same name space, and a project with a `json`
        // directory at its root taking a dependency that prefers `json` is the common case rather
        // than an exotic one. Refused in the same words, because it is the same collision.
        Either.cond(!topLevel(ownerRoot).contains(local), (),
          s"$owner: '$local' is both a directory in this project and the root name of " +
            s"${dep.canonical} — give the dependency a 'mount' to say what it is called here")

  /** The root name a dependency takes in the manifest that named it (`§ 9`).
   *
   * The package's own `package.name` is the preferred one, and a `mount` overrides it. That order is
   * what makes the mount **optional**: a consumer's import lines match the library's own
   * documentation until two libraries want one word, and only then does anyone write a rename.
   *
   * A package that states no name and is given no mount is refused rather than named after its
   * directory. `§ Open a` leans this way, and the reason is that a directory name is not a decision
   * anybody made — it is where a checkout happened to land, and a silent default drawn from it is
   * pleasant exactly once.
   */
  private def rootName(dep: Dependency, config: PackageConfig): Either[String, String] =
    dep.mount.orElse(config.name).toRight(
      s"'${dep.label}' says no name in its own ${PackageConfig.FileName} and is given no 'mount', " +
        "so there is nothing to call it here — add a 'mount' saying what its modules are named under")

  /** A package's manifest, where it has one. A package with no file is a package that said nothing,
   * exactly as `§ 1` has it for a project.
   */
  private def configOf(root: String): Either[String, PackageConfig] = {
    val path = s"$root/${PackageConfig.FileName}"

    if !isFile(path) then Right(PackageConfig.empty)
    else
      try PackageConfig.read(readFile(path)).left.map(e => s"$root: $e")
      catch case e: Exception => Left(s"cannot read $path: ${e.getMessage}")
  }

  /** The directory names directly under a project root, which are exactly the modules it declares at
   * top level (`13 §1`) and therefore the names a dependency must not silently take.
   */
  private def topLevel(root: String): Set[String] =
    try listFiles(root).filter(isDirectory).map(Project.basename).filterNot(_.startsWith(".")).toSet
    catch case _: Exception => Set.empty

  /** Every item, or the first thing wrong — the same shape the config parser uses, and for the same
   * reason: these are not independent failures, and a list of them describes one broken graph
   * several times over.
   */
  private def collect[A, B](items: List[A])(f: A => Either[String, B]): Either[String, List[B]] =
    items.foldLeft(Right(Nil): Either[String, List[B]]) { (acc, item) =>
      for
        done <- acc
        one  <- f(item)
      yield done :+ one
    }
}
