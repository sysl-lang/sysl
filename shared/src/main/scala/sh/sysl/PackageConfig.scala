package sh.sysl

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

/** The pair of C symbols a program's storage comes from and goes back to (`packages.md § 13`).
 *
 * A program has **one** of these, and `03` is why: whoever holds the last reference is what frees,
 * so two heaps in one program would mean a box whose payload cannot be given back by the code that
 * outlived it. What varies is not how many there are but which pair they are — libc's on a hosted
 * machine, `pvPortMalloc` / `vPortFree` under FreeRTOS, an arena's on a target that has one.
 *
 * The two symbols must have `malloc`'s and `free`'s signatures. **Nothing here can check that**, for
 * the same reason nothing checks an `extern` against the header it names: the declaration is a claim
 * about code the compiler does not have. It is the same trade `@link` already makes.
 */
case class Allocator(alloc: String, free: String)

object Allocator {

  /** What a program gets when no package says otherwise — and every program before this existed. */
  val c: Allocator = Allocator("malloc", "free")

  /** Whether a name is one a C symbol could have.
   *
   * Checked because the string reaches LLVM IR as `@$name`, where a space or a sigil would produce a
   * module that will not parse — and the failure would arrive as a syntax error inside generated text
   * rather than as a complaint about the line somebody wrote.
   */
  def isSymbol(name: String): Boolean =
    name.nonEmpty && (name.head.isLetter || name.head == '_') &&
      name.forall(c => c.isLetterOrDigit || c == '_')

  /** Which allocator a program built from these packages uses, or what is wrong with the answer.
   *
   * Each entry is a package's name and what it declared; the root project's name is whatever the
   * caller calls it, because a conflict has to be reported in words a reader can act on.
   *
   * **Two packages declaring the same pair is not a conflict** — it is one fact stated twice, which is
   * what happens as soon as a package and a driver built on it both know which kernel they are for.
   * Two packages declaring *different* pairs is refused, naming both, because there is no answer: the
   * program cannot have two heaps and nothing here can rank one claim above the other.
   *
   * **No declaration is not an error and never becomes one.** It is libc's pair, which is what every
   * program compiled before this feature got, so nothing that exists today changes.
   */
  def choose(declared: List[(String, Allocator)]): Either[String, Allocator] =
    declared.map(_._2).distinct match
      case Nil          => Right(c)
      case List(single) => Right(single)
      case several =>
        val who = declared
          .groupBy(_._2)
          .toList
          .sortBy(p => several.indexOf(p._1))
          .map { (a, packages) =>
            // The verb agrees, because one package claiming a pair is the ordinary case and 'freertos'
            // name pvPortMalloc reads as a mistake in the compiler rather than one in the project.
            val names = packages.map(_._1).sorted
            val verb  = if names.length == 1 then "names" else "name"

            s"'${names.mkString("' and '")}' $verb ${a.alloc} / ${a.free}"
          }

        Left("two packages name different allocators, and a program has one heap — " +
          who.mkString("; ") + ". Drop one of the declarations, or depend on only one of them")
}

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
    capabilities: Map[String, Boolean] = Map.empty,
    requires: Set[String] = Set.empty,
    headers: Map[String, String] = Map.empty,
    dependencies: List[Dependency] = Nil,
    allocator: Option[Allocator] = None,
) {

  /** The capabilities `target` provides.
   *
   * **A capability the file does not mention is provided**, which is the direction that cannot
   * silently take something away. The compiler's prior is that a machine can do everything — that is
   * what every target did before there was a file to say otherwise — so what a config records is
   * what a machine *cannot* do. It also composes the way the source clause does: `@no_alloc` narrows,
   * and so does `heap = false`.
   *
   * **The top-level block is the project's own policy and a target block layers over it**, which is
   * the order the two are written in and the only one that reads sensibly: a project says what it is
   * building — *this thing has no heap* — and then names the one machine where that is not so. Keyed
   * only by target, the statement could not be made at all for a target the registry already has,
   * since a block would then be a machine the project was redefining rather than a policy it was
   * declaring.
   *
   * Whether a heap exists is a **project engineering decision**, which is the whole reason this is
   * here rather than derived: `targets.md` deliberately carries no capabilities, because a target's
   * capabilities are exactly the part a project has an opinion about (`packages.md § 2`).
   */
  def provides(target: String): Set[String] = {
    val perTarget = targets.get(target).map(_.capabilities).getOrElse(Map.empty)

    Capability.core.toSet.filter(c => perTarget.getOrElse(c, capabilities.getOrElse(c, true)))
  }
}

object PackageConfig {

  /** The one name the driver looks for. */
  val FileName = "package.hocon"

  /** The sub-block of `requires` that names headers rather than capabilities. */
  val HeadersKey = "headers"

  /** Whether a header requirement may be called this.
   *
   * A name reaches a command line as `--include-path <name>=<dir>`, so it must be tellable from a
   * directory by looking — which is what `SearchPaths.namedInclude` does with it, and why the rule
   * lives here rather than being written twice. Refused rather than escaped: a name holding a
   * separator or an `=` would be one the flag could not carry, and the failure would land on the
   * consumer typing a command they were given rather than on the package that chose the name.
   */
  def isHeaderName(name: String): Boolean =
    name.nonEmpty && name.head.isLetter && name.forall(c => c.isLetterOrDigit || c == '_' || c == '-')

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
        _       <- checkName(pkg.flatMap(string(_, "name")))
        targets <- readTargets(root)
        project <- readCapabilityFlags(block2(root, "capabilities"), "capabilities")
        needed  <- readCapabilityFlags(capabilitiesOf(block2(root, "requires")), "requires")
        _       <- checkNotNarrowing(needed)
        headers <- readHeaders(block2(root, "requires"))
        deps    <- readDependencies(root)
        alloc   <- readAllocator(root)
      yield PackageConfig(
        name = pkg.flatMap(string(_, "name")),
        version = pkg.flatMap(string(_, "version")),
        defaultTarget = block2(root, "targets").flatMap(string(_, "default")),
        targets = targets,
        capabilities = project.toMap,
        requires = needed.collect { case (name, true) => name }.toSet.flatMap(Capability.closure),
        headers = headers,
        dependencies = deps,
        allocator = alloc,
      )
    catch
      // Every way HOCON can be wrong arrives as one of these, and the driver wants a line rather
      // than a stack trace. The message is the library's own, which already says where it was.
      case e: HoconException => Left(s"$FileName: ${e.getMessage}")

  /** Refuses `requires { … = false }`, which parsed cleanly and was then thrown away.
   *
   * `requires` is what a package **needs of its host** (`packages.md § 8`), so a `false` there says
   * nothing: a package does not need a facility *not* to exist. It was collected with
   * `collect { case (name, true) => name }` and silently dropped, which is worse than a refusal —
   * the file then reads as though the project had said something, and it is the spelling somebody
   * reaches for first when what they mean is *this project has no heap*.
   *
   * The two blocks are named in the message because they are the two directions, and somebody who
   * wrote one of them wanted the other.
   */
  private def checkNotNarrowing(needed: Map[String, Boolean]): Either[String, Unit] =
    needed.collectFirst { case (name, false) => name } match
      case None => Right(())
      case Some(name) =>
        Left(s"$FileName: 'requires { $name = false }' says nothing — 'requires' is what this package " +
          s"needs its host to have. To say the machine has no $name, write it in the project's own " +
          s"'capabilities { $name = false }'; to say a module does not use it, write " +
          s"'@no_${Capability.narrowWord(name)}' in that module's files")

  /** A package's name is what a directory project's output is called, so it reaches the filesystem
   * and has to be a single path segment.
   *
   * Refused rather than quietly ignored, and refused rather than sanitized: a file saying
   * `name = "build/tool"` was written by somebody who expected an answer, and both of the silent
   * options — falling back to the directory name, or flattening the separator away — write a
   * different executable from the one they asked for and say nothing about it. `.` and `..` are here
   * for the same reason: each is a legal segment that names a directory rather than a file, so the
   * link would fail at the far end with a message about a path rather than about this line.
   */
  private def checkName(name: Option[String]): Either[String, Unit] = name match
    case None => Right(())
    case Some(n) =>
      if n.isEmpty then Left(s"$FileName: 'package.name' is empty")
      else if n == "." || n == ".." || n.exists(c => c == '/' || c == '\\') then
        Left(s"$FileName: 'package.name' is what this project's output is called, so '$n' cannot " +
          "be one — it names a path rather than a name")
      else Right(())

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
        collect(caps.fields.toList.sortBy(_._1)) { (rawName, value) =>
          // **A module's word is accepted here and mapped**, which is a transitional allowance rather
          // than a second spelling: `alloc` names what a module does and `heap` names the facility, and
          // the config wants the facility. What makes it worth carrying is that a **tag is immutable**
          // — every package in the org is fetched at a pinned version whose `package.hocon` says
          // `requires { alloc = true }` and always will, and `Resolve.configOf` validates a fetched
          // dependency's file exactly as it validates the project's own. Refusing the old word outright
          // would stop every pinned dependency resolving on the day this shipped, and re-tagging cannot
          // fix a consumer that has not also bumped its pin.
          //
          // `heap` is the documented name and the one to write. This goes when the org has been swept.
          val name = Capability.narrowedBy.getOrElse(rawName, rawName)

          if !Capability.implies.contains(name) then
            Left(s"$FileName: '$rawName' in '$where' is not a capability — " +
              s"the set is ${Capability.core.map(n => s"'$n'").mkString(", ")}")
          else
            value match
              case ConfigBoolean(on) => Right(name -> on)
              case _                 => Left(s"$FileName: '$where.$name' must be true or false")
        }.map(_.toMap)

  /** `requires` less its `headers` sub-block, which is a requirement of a different kind and is read
   * by `readHeaders`.
   */
  private def capabilitiesOf(section: Option[ConfigObject]): Option[ConfigObject] =
    section.map(caps => ConfigObject(caps.fields - HeadersKey))

  /** The `headers` sub-block of `requires` — the C headers this package's own C includes and does not
   * carry, each under a name the consumer satisfies with `--include-path <name>=<dir>`
   * (`packages.md § 8`).
   *
   * ==A name, never a path==
   *
   * `15 §8` refuses a path here in as many words: the file is committed and describes the *package*,
   * and where a prefix lives on somebody's laptop is not a property of the package. It refuses an
   * environment variable for the same reason `packages.md § 7` refuses build scripts — a build that
   * reads the consumer's shell is one that works for whoever wrote it. So what a package may say is
   * *which* headers it needs; **where** they are stays the driver's question, exactly as it is for
   * `@link` and `--link-path`.
   *
   * ==The value is the reason, and it is what makes the refusal worth having==
   *
   * A name alone would tell a consumer that something called `lwip` is unsatisfied and leave them to
   * guess what that is and where it lives. The string is quoted back at them instead, so the build
   * that stops says what to go and find. It is prose for a person and nothing reads it as data.
   */
  private def readHeaders(section: Option[ConfigObject]): Either[String, Map[String, String]] =
    section.flatMap(s => block2(s, HeadersKey)) match
      case None => Right(Map.empty)
      case Some(headers) =>
        collect(headers.fields.toList.sortBy(_._1)) { (name, value) =>
          value match
            case ConfigString(why) if why.trim.nonEmpty => checkHeaderName(name).map(_ => name -> why)
            case ConfigString(_) =>
              Left(s"$FileName: 'requires.$HeadersKey.$name' says nothing about what it needs — the " +
                "text is quoted back at whoever has to supply the path, so an empty one helps nobody")
            case _ =>
              Left(s"$FileName: 'requires.$HeadersKey.$name' must be a string saying what these " +
                "headers are and where they come from")
        }.map(_.toMap)

  /** The keys an `allocator` block has, both required. */
  private val AllocatorKeys = Set("alloc", "free")

  /** The `allocator` block — the pair of C symbols this package's storage comes from
   * (`packages.md § 13`).
   *
   * ==Why a package and not a target==
   *
   * The allocator is a fact about the software a program is built *on*, not about the machine:
   * `thumbv7em` does not imply FreeRTOS, two RTOSes on one chip want different pairs, and a bare-metal
   * program on that same chip wants libc's. A target-level answer would need a target per RTOS, which
   * `targets.md` already declined to do for a float variant.
   *
   * ==Both keys, or neither==
   *
   * Half a pair is refused rather than filled in from libc. A file naming `alloc` and not `free` would
   * otherwise get storage from one heap and give it back to another, which is the single worst outcome
   * available here and would present as corruption a long way from this line.
   *
   * An unknown key is refused for the reason `readDependency` refuses one: somebody who wrote
   * `malloc = "pvPortMalloc"` believes they have said something, and ignoring the key would leave them
   * believing it while the program went on calling libc.
   */
  private def readAllocator(root: ConfigObject): Either[String, Option[Allocator]] =
    block(root, "allocator").flatMap {
      case None => Right(None)
      case Some(sub) =>
        def unknown: Option[String] = sub.fields.keys.toList.sorted.find(!AllocatorKeys.contains(_))

        def symbol(key: String): Either[String, String] =
          sub.fields.get(key) match
            case Some(ConfigString(v)) if Allocator.isSymbol(v) => Right(v)
            case Some(ConfigString(v)) =>
              Left(s"$FileName: 'allocator.$key' is '$v', which is not a name a C function can have")
            case Some(_) => Left(s"$FileName: 'allocator.$key' must be the name of a C function")
            case None =>
              Left(s"$FileName: 'allocator' names no '$key' — a program takes its storage from one " +
                "pair and gives it back to the same one, so both halves are said or neither is")

        for
          _ <- unknown.toLeft(()).left.map(k => s"$FileName: 'allocator.$k' is not something an " +
                 s"allocator says — the keys are 'alloc' and 'free'")
          a <- symbol("alloc")
          f <- symbol("free")
          _ <- if a == f then Left(s"$FileName: 'allocator' names '$a' for both halves") else Right(())
        yield Some(Allocator(a, f))
    }

  private def checkHeaderName(name: String): Either[String, Unit] =
    if isHeaderName(name) then Right(())
    else
      Left(s"$FileName: '$name' is not usable as a header requirement's name — it is written on a " +
        "command line as '--include-path <name>=<dir>', so it must be letters, digits, '_' and '-', " +
        "starting with a letter")

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
