package sh.sysl

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

  /** The same compilation with some files handed over as a **library**, which is what a `--lib`
   * source root and a fetched package both arrive as (`Compiler.compiledWith`). It is the only way to
   * reach a module that is not the program's own, and therefore the only way to ask any of the
   * questions below.
   */
  private def withLibrary(caps: Set[String])(lib: (String, String)*)(fs: (String, String)*)
      : Either[String, String] =
    Compiler.compiledWith(fs.toList.map((n, t) => Source(n, t)), Nil, Target.default, Set.empty, None,
      caps, librarySources = lib.toList.map((n, t) => Source(n, t))).map(_.ir)

  private def refusedWith(caps: Set[String])(lib: (String, String)*)(fs: (String, String)*): String =
    withLibrary(caps)(lib*)(fs*) match
      case Left(e)    => e
      case Right(out) => fail(s"expected an error, got:\n${out.take(400)}")

  private def acceptedWith(caps: Set[String])(lib: (String, String)*)(fs: (String, String)*): String =
    withLibrary(caps)(lib*)(fs*) match
      case Right(out) => out
      case Left(e)    => fail(s"expected a compilation, got:\n$e")

  "a target providing everything is what every build had before there was a file" - {

    "so a program that allocates still compiles" in {
      accepted(everything)("main.sysl" -> "f() -> &int = 1\n\nprint(*f())\n") should include("define")
    }
  }

  "a target with no allocator makes every module of the program allocator-free" - {

    "without a clause being written anywhere" in {
      val e = refused(everything - Capability.Heap)("main.sysl" -> "f() -> &int = 1\n\nprint(*f())\n")

      e should include("a reference needs an allocator")
      e should include(s"'${Target.default.name}' provides no allocator")
    }

    "and the message does NOT claim a clause the file never wrote" in {
      refused(everything - Capability.Heap)(
        "main.sysl" -> "f() -> &int = 1\n\nprint(*f())\n") shouldNot include("declared '@no_alloc'")
    }

    "a module that DID write the clause still hears about its own clause" in {
      val e = refused(everything)(
        "thing/a.sysl" -> "module thing\n@no_alloc\n\nf() -> &int = 1\n",
        "main.sysl"    -> "print(*thing.f())\n")

      e should include("declared '@no_alloc'")
      e shouldNot include("provides no allocator")
    }

    "a program using only value types is untouched" in {
      accepted(everything - Capability.Heap)(
        "main.sysl" -> "f(p: *int) -> int = *p\n\nvar n = 4\nprint(f(&n))\n") should include("define")
    }
  }

  "the standard library is exempt from the target's half" - {

    // Its files are compiled into every program, so holding them to the target's set would report
    // the allocating half of the library as a mistake in source the program's author cannot change.
    "so a no-alloc target does not turn the library into a wall of errors" in {
      val e = refused(everything - Capability.Heap)("main.sysl" -> "f() -> &int = 1\n\nprint(*f())\n")

      e shouldNot include("library/sysl")
      e.linesIterator.count(_.contains("needs an allocator")) shouldBe 1
    }
  }

  "'requires' is answered against the target, which is what writing it buys" - {

    "one clean error naming the module, the capability and the machine" in {
      val e = refused(everything - Capability.Heap)(
        "thing/a.sysl" -> "module thing\n@requires(heap)\n\nf() -> int = 1\n",
        "main.sysl"    -> "print(thing.f())\n")

      e should include("'thing' requires 'heap'")
      e should include(s"'${Target.default.name}' does not provide it")
      e should include(PackageConfig.FileName)
    }

    "and what a requirement implies is answered too — posix needs an operating system" in {
      refused(everything - Capability.Os)(
        "thing/a.sysl" -> "module thing\n@requires(posix)\n\nf() -> int = 1\n",
        "main.sysl"    -> "print(thing.f())\n") should include("requires 'os'")
    }

    "a requirement the target meets says nothing at all" in {
      accepted(everything)(
        "thing/a.sysl" -> "module thing\n@requires(os)\n\nf() -> int = 1\n",
        "main.sysl"    -> "print(thing.f())\n") should include("define")
    }

    "the root module has no name to print, and says so as a sentence" in {
      refused(everything - Capability.Heap)(
        "main.sysl" -> "@requires(heap)\n\nprint(1)\n") should include("this module requires 'heap'")
    }
  }

  "a library's own 'requires' is the library's, not the program's" - {

    // The standard module has always been exempt — the section above is that rule — and a library
    // handed over as source is in exactly the same position: its files are ones the program's author
    // did not write and cannot change. Holding them to the target's set makes one module of a library
    // decide whether the whole library may be used on a machine at all, however little of it a given
    // program reaches.
    val probe = "probe/probe.sysl" -> "module demo.probe\n@requires(posix)\n\nsize() -> usize = 32\n"

    "a module the program never names does not refuse the build" in {
      acceptedWith(everything - Capability.Posix)(probe)(
        "main.sysl" -> "@no_posix\n\nprint(\"never names demo.probe\")\n") should include("define")
    }

    "nor does one in a program that wrote no clause at all" in {
      acceptedWith(everything - Capability.Posix)(probe)(
        "main.sysl" -> "print(\"never names demo.probe\")\n") should include("define")
    }

    "and what the requirement implies is exempt with it — posix needs an os" in {
      acceptedWith(everything - Capability.Os - Capability.Posix)(probe)(
        "main.sysl" -> "print(1)\n") should include("define")
    }

    "the program's own module is still asked, with a library beside it" in {
      val e = refusedWith(everything - Capability.Posix)(probe)(
        "thing/a.sysl" -> "module thing\n@requires(posix)\n\nf() -> int = 1\n",
        "main.sysl"    -> "print(thing.f())\n")

      e should include("'thing' requires 'posix'")
    }

    // The narrowing half is where a reference into a gated module is answered, and it is answered at
    // the reference rather than at the library's clause — which is the diagnostic a reader can act
    // on, since the reference is the line they wrote.
    "and a module that gave posix up is still refused where it reaches the library's" in {
      val e = refusedWith(everything)(probe)(
        "main.sysl" -> "@no_posix\n\nprint(demo.probe.size())\n")

      e should include("this reaches 'demo.probe', which requires 'posix'")
    }

    // The exemption is about the *target*, which the library's author had no say in. Whether the file
    // is well-formed is a different question, and one nobody else has checked: a source root is
    // handed over as text, so a clause naming nothing was never read by the build that produced it.
    "a clause that names no capability is still refused in a library file" in {
      refusedWith(everything)("probe/probe.sysl" -> "module demo.probe\n@requires(sockets)\n\nsize() -> usize = 32\n")(
        "main.sysl" -> "print(1)\n") should include("no capability is called 'sockets'")
    }

    "and two files of one library module that disagree are still refused" in {
      refusedWith(everything)(
        "probe/a.sysl" -> "module demo.probe\n@requires(posix)\n\nsize() -> usize = 32\n",
        "probe/b.sysl" -> "module demo.probe\n\nother() -> usize = 1\n")(
        "main.sysl" -> "print(1)\n") should include("they declare different capabilities")
    }
  }

  "what the target does not provide is out of reach for the program, with no clause written" - {

    // The ceiling half of `capabilities.md § Two levels`, which is what makes the exemption above
    // safe: a library's gated module stops refusing a build it has nothing to do with, and starts
    // refusing the programs that actually reach one. Until this, the target half of the rule was
    // enforced for `heap` and for a module's own `requires` and nowhere else — so a program on a
    // machine its own config said has no operating system linked `sysl.fs` and said nothing.
    val probe = "probe/probe.sysl" -> "module demo.probe\n@requires(posix)\n\nsize() -> usize = 32\n"

    "a program reaching a library's gated module is refused at the reference" in {
      val e = refusedWith(everything - Capability.Posix)(probe)(
        "main.sysl" -> "print(demo.probe.size())\n")

      e should include("this reaches 'demo.probe', which requires 'posix'")
      e should include(s"'${Target.default.name}' does not provide it")
      e should include(PackageConfig.FileName)
    }

    "and one reaching the standard library's is refused the same way" in {
      refused(everything - Capability.Os)(
        "main.sysl" -> "print(sysl.fs.exists(\"x\"))\n") should include(
        "this reaches 'sysl.fs', which requires 'os'")
    }

    "reaching it through a module that says nothing itself is still reaching it" in {
      refusedWith(everything - Capability.Posix)(probe)(
        "relay/relay.sysl" -> "module relay\n\nn() -> usize = demo.probe.size()\n",
        "main.sysl"        -> "print(relay.n())\n") should include("which requires 'posix'")
    }

    // The clause is the better answer where there is one: it names something the reader wrote, and a
    // message about the config would send them to change the wrong file.
    "a module that gave the capability up hears about its own clause, not the machine" in {
      val e = refusedWith(everything - Capability.Posix)(probe)(
        "main.sysl" -> "@no_posix\n\nprint(demo.probe.size())\n")

      e should include("declared 'no posix'")
      e shouldNot include("does not provide it")
    }

    "a target that provides it is unaffected, which is every build with no config behind it" in {
      acceptedWith(everything)(probe)(
        "main.sysl" -> "print(demo.probe.size())\n") should include("define")
    }

    "and the library's own edges are never the program author's mistake" in {
      // `demo.probe` requires posix and `demo.relay` reaches it — an edge entirely inside the
      // library. The program touches neither, so a machine without posix has nothing to say.
      acceptedWith(everything - Capability.Posix)(
        "probe/probe.sysl" -> "module demo.probe\n@requires(posix)\n\nsize() -> usize = 32\n",
        "probe/relay.sysl" -> "module demo.relay\n\nn() -> usize = demo.probe.size()\n")(
        "main.sysl" -> "print(1)\n") should include("define")
    }
  }
}
