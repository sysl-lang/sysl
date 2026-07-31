package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The machine a program is compiled **for** (`targets.md`): the registry, how a machine names
 * itself, and what naming a target changes about the module that comes out.
 *
 * What a target actually *decides* is one thing today — how a walk reaches a foreign function — and
 * that is `VariadicForeignTests`. What is here is the model around it: that the registry says the
 * same thing twice about no machine, that a name resolves to exactly one target, and that the
 * choice reaches the emitted module rather than being recorded and forgotten.
 */
class TargetTests extends AnyFreeSpec with CodegenSupport {

  "the registry" - {
    "gives each target one name and one triple" in {
      Target.all.map(_.name).distinct.length shouldBe Target.all.length
      Target.all.map(_.triple).distinct.length shouldBe Target.all.length
    }

    "names no target it cannot answer the ABI questions for" in {
      for t <- Target.all do
        withClue(t.name) {
          t.triple should not be empty
          t.vaListBytes should be > 0
        }
    }

    // Every target that can be built for is 64-bit, which is what lets `Layout` answer without one.
    "supports every 64-bit target it lists, and no other" in {
      Target.all.filter(_.supported).map(_.pointerBits).distinct shouldBe List(64)
      Target.all.filterNot(_.supported).map(_.name) shouldBe List("x86-linux")
    }

    // Each was read off `clang -S -emit-llvm` for the triple, not out of an ABI document.
    "records the va_list ABI each triple was measured to have" in {
      Target.aarch64MacOS.vaList shouldBe VaListAbi.Loaded
      Target.x86_64Windows.vaList shouldBe VaListAbi.Loaded
      Target.riscv64Linux.vaList shouldBe VaListAbi.Loaded

      Target.x86_64Linux.vaList shouldBe VaListAbi.Address
      Target.x86_64MacOS.vaList shouldBe VaListAbi.Address
      Target.x86_64Freestanding.vaList shouldBe VaListAbi.Address

      Target.aarch64Linux.vaList shouldBe VaListAbi.Copied
      Target.aarch64Freestanding.vaList shouldBe VaListAbi.Copied
    }

    // The one storage `Type.VaList` reserves has to hold whatever `va_start` writes into it on any
    // of them, so the widest is the number that matters and it is 32 bytes.
    "reserves storage wide enough for every target's own va_list" in {
      Target.all.map(_.vaListBytes).max shouldBe Layout.size(Type.VaList)
    }

    // Bare-metal RISC-V is not built for the floating extension and every other target is, which is
    // clang's default for each of these triples and therefore has to be sysl's — the two are handed
    // the same triple and have to make the same assumption about it (`CAbi`).
    "records which targets have floating registers to pass arguments in" in {
      Target.all.filterNot(_.hardFloat).map(_.name) shouldBe List("riscv64-freestanding")
    }

    // Two machines differing only in their OS are two targets, which is the point of recording the
    // OS at all: the processor does not settle the C ABI on its own.
    "tells one processor's systems apart" in {
      val aarch64 = Target.all.filter(_.cpu == Cpu.Aarch64)

      aarch64.map(_.os) should contain allOf (Os.MacOS, Os.Linux, Os.Freestanding)
      aarch64.map(_.vaList).distinct.length shouldBe 2
    }
  }

  "naming one" - {
    "resolves a name in the registry" in {
      Target.named("x86_64-linux") shouldBe Right(Target.x86_64Linux)
    }

    "says what there is when the name is not one of them" in {
      val out = Target.named("sparc-solaris").left.getOrElse(fail("expected a complaint"))

      out should include("unknown target 'sparc-solaris'")
      out should include("aarch64-macos")
      out should include("x86_64-freestanding")
    }

    // A machine sysl knows and cannot build for is worth a message of its own: told the name is
    // unknown, a reader would look for a typo that is not there.
    "says why a target it knows cannot be built for" in {
      val out = Target.named("x86-linux").left.getOrElse(fail("expected a complaint"))

      out should include("32-bit target")
      out should include("sysl lowers 64-bit targets only")
    }

    "is case-sensitive, since the name is written down and not guessed at" in {
      Target.named("X86_64-Linux").isLeft shouldBe true
    }
  }

  /** The three platforms the compiler runs on describe one machine in three vocabularies, so the
   * mapping is shared and the asking is not. Each pair below marked *observed* was read off the
   * running compiler on this machine; the rest are the other spellings the same runtimes produce.
   */
  "a machine naming itself" - {
    "reads the three vocabularies this machine speaks" in {
      Target.hostName("aarch64", "Mac OS X") shouldBe "aarch64-macos" // observed: JVM
      Target.hostName("aarch64", "darwin") shouldBe "aarch64-macos"   // observed: Scala Native
      Target.hostName("arm64", "darwin") shouldBe "aarch64-macos"     // observed: Node 24
    }

    "reads the spellings a machine of another kind would use" in {
      Target.hostName("amd64", "Linux") shouldBe "x86_64-linux"
      Target.hostName("x64", "linux") shouldBe "x86_64-linux"
      Target.hostName("x86_64", "linux") shouldBe "x86_64-linux"
      Target.hostName("amd64", "Windows 11") shouldBe "x86_64-windows"
      Target.hostName("x64", "win32") shouldBe "x86_64-windows"
      Target.hostName("riscv64", "linux") shouldBe "riscv64-linux"
    }

    // Half an answer is no answer: a target has to be named, and half a name would be a guess.
    "answers with nothing when either half is one it does not know" in {
      Target.hostName("sparc", "Linux") shouldBe ""
      Target.hostName("aarch64", "SunOS") shouldBe ""
      Target.hostName("", "") shouldBe ""
    }

    // A freestanding target is nobody's host: a machine that reported its OS has one.
    "never answers with a target that has no operating system" in {
      val processors = List("aarch64", "arm64", "x86_64", "amd64", "x64", "riscv64")
      val systems    = List("Mac OS X", "darwin", "Linux", "linux", "Windows 11", "win32")

      for p <- processors; s <- systems do
        withClue(s"$p / $s") {
          Target.named(Target.hostName(p, s)).toOption.map(_.os) should not contain Os.Freestanding
        }
    }

    "is what settled which machine this one is" in {
      val (processor, system) = hostMachine

      Target.host shouldBe Target.named(Target.hostName(processor, system)).toOption
    }
  }

  "what naming one changes" - {
    "the module says which machine it is for" in {
      for t <- Target.all if t.supported do
        withClue(t.name)(irFor(t, "print(1)") should include(s"""target triple = "${t.triple}""""))
    }

    // A compilation that names no target is for this machine, which is what makes the whole suite
    // a suite about the machine it runs on.
    "a compilation that names none is for the default" in {
      ir("print(1)") should include(s"""target triple = "${Target.default.triple}"""")
    }

    "and the triple is the first thing in the module" in {
      ir("print(1)").linesIterator.next() should startWith("target triple =")
    }

    // The driver hands the same triple to `clang`, which is what turns "built for another machine"
    // into a failed link instead of a host binary wearing the wrong label. What the toolchain says
    // is its own business; that it refuses is the claim.
    "a cross build is refused by the toolchain rather than quietly made for this machine" in {
      assume(Toolchain.clangAvailable, "clang not available")

      val exe = "/tmp/sysl-cross-target-test"

      Toolchain.build(irFor(Target.x86_64Linux, "print(1)"), exe, Target.x86_64Linux).isLeft shouldBe true
    }

    // The other half of the same claim: with the triples agreeing, this machine's build still works
    // — the suite would say so anyway, but not in a way that names the reason.
    "and the build for this machine is not disturbed by stating it" in {
      assume(Toolchain.clangAvailable, "clang not available")

      Target.host.foreach { t =>
        Toolchain.build(irFor(t, "print(1)"), "/tmp/sysl-host-target-test", t) shouldBe Right(())
      }
    }
  }

  /** Everything whose width `Layout` has to state in the emitted text rather than leave to LLVM: a
   * padded aggregate, a nested one, an array, a data enum's union region — and a `va_list`, whose
   * storage `targets.md § What a target does not decide` says is one width for every target.
   */
  private val shapes =
    """struct Padded
      |    a: u8
      |    big: i64
      |    b: u16
      |
      |struct Nested
      |    p: Padded
      |    q: [3]u16
      |
      |enum Payload
      |    Small(c: u8)
      |    Large(x: i64, y: i64)
      |    Middling(f: f32)
      |
      |extern vprintf(fmt: *u8, ap: va_list) -> i32
      |
      |log(fmt: *u8, ...) -> i32
      |    var ap: va_list
      |    va_start(ap)
      |    var k = vprintf(fmt, &ap)
      |    va_end(ap)
      |    k
      |end log
      |
      |what(p: Payload) -> int
      |    p match
      |        Small(c) -> int(c)
      |        Large(x, y) -> int(x + y)
      |        Middling(f) -> 0
      |end what
      |
      |var n = Nested(Padded(1u8, 2i64, 3u16), [4u16; 3])
      |var s: *u8 = null
      |print(n.p.a, what(Small(1u8)), log(s, 1))""".stripMargin

  private def typeLines(t: Target): List[String] =
    irFor(t, shapes).linesIterator.filter(_.contains("= type")).toList

  /** `targets.md § What a target does not decide` rests the whole of `Layout` on one claim: every
   * target in the registry answers a layout question the same way, so the object that answers them
   * takes no `Target`. `LayoutTests` measures those answers against *this machine*, which is a
   * statement about a machine rather than about targets — the agreement itself can only be made by
   * emitting one program for more than one of them.
   */
  "what a target does not decide" - {
    "every target lays an aggregate out the same way, which is why Layout takes none" in {
      val supported = Target.all.filter(_.supported)
      val host      = typeLines(supported.head)

      host should not be empty

      for t <- supported.tail do withClue(t.name)(typeLines(t) shouldBe host)
    }

    // And the agreement is worth something only because the modules themselves disagree: these same
    // targets lower a variadic walk four different ways, so "the type lines match" is a fact about
    // layout and not about the target being ignored.
    "while the code around those types is not the same for all of them" in {
      val supported = Target.all.filter(_.supported)

      supported.map(t => irFor(t, shapes)).distinct.length should be > 1
    }

    // The width is `Layout`'s, not the target's: a module built for a machine whose own `va_list` is
    // eight bytes still reserves the widest any of them needs.
    "and a va_list is spelled one way in every module, the widest any target needs" in {
      Target.all.filter(_.supported).map(_.vaListBytes).distinct.length should be > 1
      Layout.size(Type.VaList) shouldBe 32

      for t <- Target.all if t.supported do
        withClue(t.name)(irFor(t, shapes) should include(s"alloca ${Type.VaList.llvm}"))
    }
  }

  "the machine as it described itself" - {
    "is shown in its own words, whether or not it was recognized" in {
      val (processor, system) = hostMachine

      if processor.isEmpty && system.isEmpty then Target.hostMachineShown shouldBe "not reported"
      else Target.hostMachineShown shouldBe s"$processor / $system"
    }
  }
}
