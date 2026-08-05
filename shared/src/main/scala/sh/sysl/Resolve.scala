package sh.sysl

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

    /** A package's hash written into the sums where no line covered it yet.
     *
     * Asked against what is held rather than against how the package arrived, so that a project
     * depending on something another project already fetched still records what it got. A line that
     * is already there and already agrees is not a change, which is what keeps a build from
     * rewriting the file every time.
     */
    def withFetch(dep: Dependency, got: Fetch.Fetched): State =
      (got.hash, dep.origin) match
        case (Some(hash), Origin.Git(coordinate, version)) if !sums.hashOf(coordinate, version).contains(hash) =>
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
        table <- acc
        dir   <- theirRoot(dep, state)
        added <- bindings(dep, dir).left.map(e => s"$owner: $e")
        _     <- collect(added.keys.toList.sorted)(local => noCollision(owner, ownerRoot, table, local, dep))
      yield table ++ added
    }

  /** What a dependency binds in the manifest that named it (`§ 9`).
   *
   * **With no mount, a package's top-level modules come in under their own names.** That is what
   * makes the mount optional in the sense the section argues for: mandatory mounting was rejected
   * because it "would make every project's import lines differ from the library's own
   * documentation", and an import line only matches the documentation if `sqlite.open` is what a
   * consumer writes — which it is not if every name is silently prefixed by something. The section's
   * own collision example says the same thing from the other side: it is a project with a `json/`
   * directory meeting "a dependency preferring `json`", and `json` there is a *directory* name.
   *
   * **A mount hangs the whole package under one segment**, which is the escape hatch for exactly the
   * case that example describes: mount it as `ejson` and its `json` is reached as `ejson.json`,
   * leaving the project's own `json` alone.
   *
   * `package.name` is deliberately not consulted. It names the *package*, which is a unit of
   * distribution, and what an import line writes is a *module*, which is a unit of code — sqlite3's
   * package is called `sqlite3` and its module is `sqlite`, and a consumer reaching for the second
   * should not have to say the first.
   */
  private def bindings(dep: Dependency, dir: String): Either[String, Map[String, String]] =
    dep.mount match
      case Some(mount) => Right(Map(mount -> dep.canonical))
      case None =>
        val modules = topLevel(dir)

        if modules.isEmpty then
          Left(s"'${dep.label}' has no modules — a package is a tree of directories, each of them a " +
            "module, and there is nothing here to import")
        else Right(modules.map(m => m -> Packages.qualify(dep.canonical, m)).toMap)

  /** Where the version of `dep` that is being built has its source, which for a git dependency is the
   * version selection settled on rather than the one this edge asked for.
   */
  private def theirRoot(dep: Dependency, state: State): Either[String, String] =
    dep.origin match
      case Origin.Local(_) =>
        state.locals.get(dep.canonical).map(_._1).toRight(s"'${dep.label}' was never read")
      case Origin.Git(coordinate, _) =>
        for
          version <- state.selected.get(coordinate).toRight(s"'$coordinate' was never selected")
          entry   <- state.manifests.get((coordinate, version)).toRight(s"'$coordinate' was never read")
        yield entry._1

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
