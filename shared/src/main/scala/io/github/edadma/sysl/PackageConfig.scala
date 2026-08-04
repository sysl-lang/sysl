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
 * `dependencies` is not read at all yet — there is nothing that could resolve one.
 */
case class PackageConfig(
    name: Option[String] = None,
    version: Option[String] = None,
    defaultTarget: Option[String] = None,
    targets: Map[String, TargetConfig] = Map.empty,
    requires: Set[String] = Set.empty,
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
      yield PackageConfig(
        name = pkg.flatMap(string(_, "name")),
        version = pkg.flatMap(string(_, "version")),
        defaultTarget = block2(root, "targets").flatMap(string(_, "default")),
        targets = targets,
        requires = needed.collect { case (name, true) => name }.toSet.flatMap(Capability.closure),
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
