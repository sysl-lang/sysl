package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `sysl.os()` and `sysl.cpu()` against the registry they have to agree with.
  *
  * The two are `#if` ladders in `library/sysl/platform.sysl` — a constant the compiler already holds,
  * reaching an expression rather than a gated line. That makes them cheap and it makes them
  * **hand-written**, where `Conditional.symbols` is derived from `Os` and `Cpu` precisely so a new
  * machine extends the symbol set by existing. A ladder missing a branch is therefore the failure
  * this suite is for: the compiler grows a target, the library answers nothing for it, and the only
  * thing that notices is somebody building for that target.
  *
  * **The assertions name no variant, which is what makes them derived rather than a second copy of
  * the list.** For each member of the registry the gated body must hold exactly one line, and the
  * lines across the registry must all differ. A machine with no branch leaves an empty body; a
  * machine wired to somebody else's branch collides. Neither can pass, and neither needed this file
  * to be edited when the branch was written.
  *
  * The other half of the equation is asserted from the sysl side — `library/sysl/tests.sysl` checks
  * that `os()` answers what `#if` gates on, which is the claim a *program* depends on. This half
  * checks that every machine has an answer at all, which a program on one machine cannot see.
  */
class PlatformRegistryTests extends AnyFreeSpec with Matchers {

  /** `platform.sysl` as the walk reads it for an operating system, before any gating. It is the
    * same file for every one of them — nothing about it is per-machine until `Conditional` runs,
    * which is the whole point of the ladder.
    */
  private def platformSource: Source =
    Std.sources(Os.MacOS).find(_.name.endsWith("platform.sysl"))
      .getOrElse(fail("library/sysl/platform.sysl is not among the standard library's sources"))

  /** The body of one function, out of the source as this target sees it: the lines between the
    * declaration and its `end` marker, with the blanks a gated-out line leaves behind dropped.
    *
    * Reading the emitted text rather than compiling is deliberate — a compile answers what *this*
    * machine's target does, and the question here is about the twenty machines it cannot run.
    */
  private def body(target: Target, func: String): List[String] = {
    val gated = Conditional.gate(platformSource, target) match
      case Right(s) => s
      case Left(e)  => fail(s"platform.sysl does not gate for ${target.name}: $e")

    val lines = gated.lines.dropWhile(!_.startsWith(s"$func() -> ")).drop(1)

    lines.takeWhile(!_.startsWith(s"end $func")).map(_.trim).filter(_.nonEmpty).toList
  }

  /** One target per member of the registry, since a condition is evaluated against a whole target
    * and the ladders ask about one half of it each.
    */
  private def someTarget(p: Target => Boolean, what: String): Target =
    Target.all.find(p).getOrElse(fail(s"no registered target for $what"))

  "every operating system the compiler knows has an answer from 'os()'" - {

    for (os <- Os.values)
      s"${os}" in {
        body(someTarget(_.os == os, os.toString), "os") should have length 1
      }

    "and no two of them answer the same thing" in {
      val answers = Os.values.toList.map(os => body(someTarget(_.os == os, os.toString), "os"))

      answers.flatten.distinct should have length Os.values.length
    }
  }

  "every processor the compiler knows has an answer from 'cpu()'" - {

    for (cpu <- Cpu.values)
      s"${cpu}" in {
        body(someTarget(_.cpu == cpu, cpu.toString), "cpu") should have length 1
      }

    "and no two of them answer the same thing" in {
      val answers = Cpu.values.toList.map(cpu => body(someTarget(_.cpu == cpu, cpu.toString), "cpu"))

      answers.flatten.distinct should have length Cpu.values.length
    }
  }
}
