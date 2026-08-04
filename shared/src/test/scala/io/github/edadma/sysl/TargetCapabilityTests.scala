package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The **target provides** half of `capabilities.md § Two levels`, which the project config is what
 * finally declares (`packages.md § 2`).
 *
 * Until there was a file to say otherwise every target offered everything, so the whole of this is
 * new behaviour and none of it can be reached without a `package.hocon`. The two halves meet at one
 * rule: a module's effective set is the target's intersected with its own narrowing, so a capability
 * the machine does not have is absent no matter what any file declares.
 */
class TargetCapabilityTests extends AnyFreeSpec with Matchers {

  private val everything = Capability.core.toSet

  /** A compilation for a target providing exactly `caps`, which is what a config that turned the
   * others off would produce.
   */
  private def compile(caps: Set[String])(fs: (String, String)*): Either[String, String] =
    Compiler.compiledWith(fs.toList.map((n, t) => Source(n, t)), Nil, Target.default, Set.empty, None, caps)
      .map(_.ir)

  private def refused(caps: Set[String])(fs: (String, String)*): String =
    compile(caps)(fs*) match
      case Left(e)    => e
      case Right(out) => fail(s"expected an error, got:\n${out.take(400)}")

  private def accepted(caps: Set[String])(fs: (String, String)*): String =
    compile(caps)(fs*) match
      case Right(out) => out
      case Left(e)    => fail(s"expected a compilation, got:\n$e")

  "a target providing everything is what every build had before there was a file" - {

    "so a program that allocates still compiles" in {
      accepted(everything)("main.sysl" -> "f() -> &int = 1\n\nprint(*f())\n") should include("define")
    }
  }

  "a target with no allocator makes every module of the program allocator-free" - {

    "without a clause being written anywhere" in {
      val e = refused(everything - Capability.Alloc)("main.sysl" -> "f() -> &int = 1\n\nprint(*f())\n")

      e should include("a reference needs an allocator")
      e should include(s"'${Target.default.name}' provides no allocator")
    }

    "and the message does NOT claim a clause the file never wrote" in {
      refused(everything - Capability.Alloc)(
        "main.sysl" -> "f() -> &int = 1\n\nprint(*f())\n") shouldNot include("declared 'no alloc'")
    }

    "a module that DID write the clause still hears about its own clause" in {
      val e = refused(everything)(
        "thing/a.sysl" -> "module thing\nno alloc\n\nf() -> &int = 1\n",
        "main.sysl"    -> "print(*thing.f())\n")

      e should include("declared 'no alloc'")
      e shouldNot include("provides no allocator")
    }

    "a program using only value types is untouched" in {
      accepted(everything - Capability.Alloc)(
        "main.sysl" -> "f(p: *int) -> int = *p\n\nvar n = 4\nprint(f(&n))\n") should include("define")
    }
  }

  "the standard library is exempt from the target's half" - {

    // Its files are compiled into every program, so holding them to the target's set would report
    // the allocating half of the library as a mistake in source the program's author cannot change.
    "so a no-alloc target does not turn the library into a wall of errors" in {
      val e = refused(everything - Capability.Alloc)("main.sysl" -> "f() -> &int = 1\n\nprint(*f())\n")

      e shouldNot include("lib/sysl")
      e.linesIterator.count(_.contains("needs an allocator")) shouldBe 1
    }
  }

  "'requires' is answered against the target, which is what writing it buys" - {

    "one clean error naming the module, the capability and the machine" in {
      val e = refused(everything - Capability.Alloc)(
        "thing/a.sysl" -> "module thing\nrequires alloc\n\nf() -> int = 1\n",
        "main.sysl"    -> "print(thing.f())\n")

      e should include("'thing' requires 'alloc'")
      e should include(s"'${Target.default.name}' does not provide it")
      e should include(PackageConfig.FileName)
    }

    "and what a requirement implies is answered too — posix needs an operating system" in {
      refused(everything - Capability.Os)(
        "thing/a.sysl" -> "module thing\nrequires posix\n\nf() -> int = 1\n",
        "main.sysl"    -> "print(thing.f())\n") should include("requires 'os'")
    }

    "a requirement the target meets says nothing at all" in {
      accepted(everything)(
        "thing/a.sysl" -> "module thing\nrequires os\n\nf() -> int = 1\n",
        "main.sysl"    -> "print(thing.f())\n") should include("define")
    }

    "the root module has no name to print, and says so as a sentence" in {
      refused(everything - Capability.Alloc)(
        "main.sysl" -> "requires alloc\n\nprint(1)\n") should include("this module requires 'alloc'")
    }
  }
}
