package io.github.edadma.sysl

import io.github.edadma.hocon.{ConfigBoolean, ConfigObject, ConfigString, Hocon, HoconException}

/** What a target provides, as the project says rather than as the registry knows
 * (`packages.md § 2`).
 *
 * A `triple` here declares a machine the registry does not have; omitted, the block adds
 * capabilities to a registry entry of the same name. The split is the line `targets.md` draws:
 * **capabilities are policy and the ABI is not**, so a project may say what its kernel target can
 * do and may not say how a call to it is made.
 */
case class TargetConfig(triple: Option[String], capabilities: Map[String, Boolean])

/** The project config — `package.hocon` at the project root (`packages.md § 1`).
 *
 * The file is **optional**, and a project without one is not a lesser project: the defaults are the
 * root the driver was given, the machine the compiler is running on, and a target that provides
 * everything. That is the same shape `13 §1` gives the anonymous root module — the bare case is the
 * general one with nothing filled in — and it is what keeps `sysl run hello.sysl` free of ceremony.
 *
 * Only the part of `packages.md` that has something to enforce is read. `package` and `requires`
 * are parsed and checked so that a file naming them is held to spelling them correctly, and
 * `dependencies` says what to fetch and what to call it (`§ 2`, `§ 3`).
 */
case class PackageConfig(
    name: Option[String] = None,
    version: Option[String] = None,
    defaultTarget: Option[String] = None,
    targets: Map[String, TargetConfig] = Map.empty,
    requires: Set[String] = Set.empty,
    dependencies: List[Dependency] = Nil,
) {

  /** The capabilities `target` provides.
   *
   * **A capability the file does not mention is provided**, which is the direction that cannot
   * silently take something away. The compiler's prior is that a machine can do everything — that is
   * what every target did before there was a file to say otherwise — so what a config records is
   * what a machine *cannot* do. It also composes the way the source clause does: `no alloc` narrows,
   * and so does `alloc = false`.
   */
  def provides(target: String): Set[String] =
    targets.get(target) match
      case None      => Capability.core.toSet
      case Some(cfg) => Capability.core.toSet.filter(c => cfg.capabilities.getOrElse(c, true))
}

object PackageConfig {

  /** The one name the driver looks for. */
  val FileName = "package.hocon"

  /** A project that said nothing, which is what a missing file means. */
  val empty: PackageConfig = PackageConfig()

  /** Reads the file's text, or says what is wrong with it in one line.
   *
   * It takes **text rather than a path** so that the whole of it runs on every platform with no
   * filesystem in the way (`cross-platform.md`): finding the file is the driver's, and what the file
   * means is here, where a test can ask about it directly.
   */
  def read(text: String): Either[String, PackageConfig] =
    try
      val root = Hocon.parse(text).root

      // Read off the parsed tree rather than through the dotted-path getters, which split on '.'
      // without regard for quoting — so `targets.aarch64-kernel` resolves and `targets."a.b"` does
      // not, and a target whose name carried a dot would be looked for in a nesting nobody wrote.
      // The structure is what the file means; the path API is a convenience that does not fit here.
      for
        pkg     <- block(root, "package")
        targets <- readTargets(root)
        needed  <- readCapabilityFlags(block2(root, "requires"), "requires")
        deps    <- readDependencies(root)
      yield PackageConfig(
        name = pkg.flatMap(string(_, "name")),
        version = pkg.flatMap(string(_, "version")),
        defaultTarget = block2(root, "targets").flatMap(string(_, "default")),
        targets = targets,
        requires = needed.collect { case (name, true) => name }.toSet.flatMap(Capability.closure),
        dependencies = deps,
      )
    catch
      // Every way HOCON can be wrong arrives as one of these, and the driver wants a line rather
      // than a stack trace. The message is the library's own, which already says where it was.
      case e: HoconException => Left(s"$FileName: ${e.getMessage}")

  /** A named sub-object, refusing a key that holds something else. */
  private def block(o: ConfigObject, key: String): Either[String, Option[ConfigObject]] =
    o.fields.get(key) match
      case None                    => Right(None)
      case Some(sub: ConfigObject) => Right(Some(sub))
      case Some(_)                 => Left(s"$FileName: '$key' is not a block")

  /** The same, where a non-object has already been refused or does not matter. */
  private def block2(o: ConfigObject, key: String): Option[ConfigObject] =
    o.fields.get(key).collect { case sub: ConfigObject => sub }

  /** A string field, or nothing where the key is absent or holds something else. */
  private def string(o: ConfigObject, key: String): Option[String] =
    o.fields.get(key).collect { case ConfigString(v) => v }

  /** Each named block under `targets`, less the `default` key, which names one rather than being
   * one.
   */
  private def readTargets(root: ConfigObject): Either[String, Map[String, TargetConfig]] =
    block2(root, "targets") match
      case None => Right(Map.empty)
      case Some(targets) =>
        val blocks = targets.fields.toList.filter(_._1 != "default").sortBy(_._1)

        collect(blocks) {
          case (name, sub: ConfigObject) =>
            readCapabilityFlags(block2(sub, "capabilities"), s"targets.$name.capabilities")
              .map(caps => name -> TargetConfig(string(sub, "triple"), caps))
          // `targets.default = "x"` is a string beside the blocks, and anything else that is not a
          // block is the same mistake: a name where a description of a machine was expected.
          case (name, _) => Left(s"$FileName: 'targets.$name' is not a block describing a target")
        }.map(_.toMap)

  /** A block of capability names, each true or false — a target's `capabilities` and the package's
   * own `requires` have the same shape and the same mistakes.
   *
   * A name that is not a capability is refused rather than ignored. A file that says `treads = false`
   * was written by somebody who believes they have turned something off, and the whole value of the
   * block is that they have.
   */
  private def readCapabilityFlags(section: Option[ConfigObject], where: String)
      : Either[String, Map[String, Boolean]] =
    section match
      case None => Right(Map.empty)
      case Some(caps) =>
        collect(caps.fields.toList.sortBy(_._1)) { (name, value) =>
          if !Capability.implies.contains(name) then
            Left(s"$FileName: '$name' in '$where' is not a capability — " +
              s"the set is ${Capability.core.map(n => s"'$n'").mkString(", ")}")
          else
            value match
              case ConfigBoolean(on) => Right(name -> on)
              case _                 => Left(s"$FileName: '$where.$name' must be true or false")
        }.map(_.toMap)

  /** The `dependencies` block: what to fetch, at what version, and what a consumer may rename it to
   * (`packages.md § 2–4`, `§ 9`).
   *
   * Each entry is checked here rather than at the fetch, because every mistake below is one the file
   * makes on its own — a coordinate with a scheme on the front, a major version that disagrees with
   * the path it rides in — and finding them needs neither the network nor the package. A build that
   * cannot mean anything should stop before it clones a repository to discover that.
   */
  private def readDependencies(root: ConfigObject): Either[String, List[Dependency]] =
    block2(root, "dependencies") match
      case None => Right(Nil)
      case Some(deps) =>
        for
          entries <- collect(deps.fields.toList.sortBy(_._1)) {
            case (label, sub: ConfigObject) => readDependency(label, sub)
            case (label, _) => Left(s"$FileName: 'dependencies.$label' is not a block — a dependency " +
              "is a git coordinate and a version, or a path")
          }
          _ <- unique(entries)
        yield entries

  /** One entry, held to a shape in which every field is decided by the others.
   *
   * The unknown-key refusal is the same judgement `requires` makes about a misspelled capability: a
   * file saying `versoin = "1.0.0"` was written by somebody who believes they have pinned a version,
   * and ignoring the key would resolve whatever the repository's default branch happens to be while
   * they went on believing it.
   */
  private def readDependency(label: String, sub: ConfigObject): Either[String, Dependency] = {
    val where = s"dependencies.$label"

    def unknown: Option[String] =
      sub.fields.keys.toList.sorted.find(!DependencyKeys.contains(_))

    for
      _      <- unknown.toLeft(()).left.map(k => s"$FileName: '$where.$k' is not something a " +
                  s"dependency says — the keys are ${DependencyKeys.toList.sorted.map(n => s"'$n'").mkString(", ")}")
      mount  <- readMount(sub, where)
      origin <- readOrigin(sub, where)
    yield Dependency(label, origin, mount)
  }

  private val DependencyKeys = Set("git", "version", "path", "mount")

  /** Where the source comes from — exactly one of the two, with the fields that one of them takes. */
  private def readOrigin(sub: ConfigObject, where: String): Either[String, Origin] =
    (string(sub, "git"), string(sub, "path")) match
      case (Some(_), Some(_)) =>
        Left(s"$FileName: '$where' names both a 'git' coordinate and a 'path' — a dependency is " +
          "fetched or it is beside you, and it cannot be both")

      case (None, None) =>
        Left(s"$FileName: '$where' names neither a 'git' coordinate nor a 'path'")

      case (None, Some(dir)) =>
        // A path dependency is whatever is in that directory right now, so a version beside one is
        // not a weaker promise than it looks — it is a promise nothing could keep.
        if sub.fields.contains("version") then
          Left(s"$FileName: '$where' names a 'path' and a 'version' — a path dependency is the " +
            "directory as it is now, and there is nothing to resolve a version against")
        else if dir.isEmpty then Left(s"$FileName: '$where.path' is empty")
        else Right(Origin.Local(dir))

      case (Some(coordinate), None) =>
        for
          text    <- string(sub, "version").toRight(s"$FileName: '$where' names a 'git' coordinate " +
                       "and no 'version' — a coordinate says which repository and a version says which of it")
          version <- Version.parse(text).left.map(e => s"$FileName: '$where.version': $e")
          _       <- checkCoordinate(coordinate, where)
          _       <- checkMajor(coordinate, version, where)
        yield Origin.Git(coordinate, version)

  /** What a coordinate is: a host and a path under it, as `git` would be given, and **not a URL**.
   *
   * The scheme is refused rather than accepted-and-stripped because the coordinate is *identity*
   * (`§ 9`) — two spellings of one package would mangle to two module names and link as two
   * incompatible copies, which is a link error a long way from the line that caused it.
   */
  private def checkCoordinate(coordinate: String, where: String): Either[String, Unit] =
    if coordinate.contains("://") then
      Left(s"$FileName: '$where.git' is a URL — a coordinate is written as 'github.com/you/thing', " +
        "since it is the package's identity and not the way it is fetched")
    else if coordinate.startsWith("/") || coordinate.endsWith("/") || coordinate.contains("//") then
      Left(s"$FileName: '$where.git' is not a coordinate — 'github.com/you/thing' is the shape")
    else if !coordinate.contains('/') then
      Left(s"$FileName: '$where.git' names a host and nothing under it")
    else Right(())

  /** `§ 4`'s rule that the major version rides in the path, checked both ways.
   *
   * Both halves matter and they catch opposite mistakes. A suffix that disagrees with the version is
   * a manifest asking for one package and naming another. A major of 2 or more with *no* suffix is
   * the mistake `/vN` exists to prevent: it would put two incompatible majors under one module name,
   * and `15 § 2` mangles a module name into every symbol, so the two would collide at the linker
   * rather than anywhere a diagnostic could reach.
   */
  private def checkMajor(coordinate: String, version: Version, where: String): Either[String, Unit] =
    Dependency.majorSuffix(coordinate) match
      case Some(n) if n != version.major =>
        Left(s"$FileName: '$where' asks for $version from a coordinate ending '/v$n' — a major " +
          s"version is part of the coordinate, so '/v$n' holds ${n}.x and nothing else")

      case None if version.major >= 2 =>
        Left(s"$FileName: '$where' asks for $version from a coordinate with no '/v${version.major}' " +
          "— from the second major version on, the major rides in the coordinate, because two of " +
          "them are two packages rather than two versions of one")

      case _ => Right(())

  /** The root name a consumer may put a dependency under, which has to be a name a module could
   * have: it becomes the first segment of every import line that reaches the package (`§ 9`).
   */
  private def readMount(sub: ConfigObject, where: String): Either[String, Option[String]] =
    string(sub, "mount") match
      case None => Right(None)
      case Some(name) =>
        val legal = name.nonEmpty && (name.head.isLetter || name.head == '_') &&
          name.forall(c => c.isLetterOrDigit || c == '_')

        if legal then Right(Some(name))
        else Left(s"$FileName: '$where.mount' is '$name', which is not a name a module can have — " +
          "a mount becomes the first segment of an import line")

  /** The two ways one manifest can name one thing twice.
   *
   * A repeated **coordinate** is refused because `§ 4` gives a module one version: two entries for
   * one package are two answers to a question that has one, and taking either silently would make
   * which one depend on the order a file was read in. A repeated **mount** is the collision `§ 9`
   * exists to refuse, caught here in the one case where the file alone is enough to see it.
   */
  private def unique(entries: List[Dependency]): Either[String, Unit] = {
    def repeated[A](of: Dependency => Option[A]): Option[(A, List[Dependency])] =
      entries.groupBy(of).collectFirst { case (Some(key), group) if group.length > 1 => (key, group) }

    repeated {
      case Dependency(_, Origin.Git(coordinate, _), _) => Some(coordinate)
      case _                                           => None
    } match
      case Some((coordinate, group)) =>
        return Left(s"$FileName: '$coordinate' is named by ${group.map(d => s"'${d.label}'").mkString(" and ")} " +
          "— one package is one dependency, and a module gets one version of it")
      case None => ()

    repeated(_.mount) match
      case Some((mount, group)) =>
        Left(s"$FileName: ${group.map(d => s"'${d.label}'").mkString(" and ")} are both mounted as " +
          s"'$mount' — two packages cannot share a root name")
      case None => Right(())
  }

  /** Every item, or the **first** thing wrong, by the order the caller put them in.
   *
   * One message rather than all of them: a config file is short, its mistakes are usually one
   * mistake, and the alternative is a driver that prints a list before it has read a line of sysl.
   */
  private def collect[A, B](items: List[A])(f: A => Either[String, B]): Either[String, List[B]] =
    items.foldLeft(Right(Nil): Either[String, List[B]]) { (acc, item) =>
      for
        done <- acc
        one  <- f(item)
      yield done :+ one
    }
}
