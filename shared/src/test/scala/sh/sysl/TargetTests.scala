package sh.sysl

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

    // What decides whether a target can be built for is whether a C calling convention has been
    // measured for its processor -- not how wide its addresses are. Both widths are now built for,
    // and `x86-linux` is refused for the reason it was always really refused for.
    "builds for both address widths, and refuses only what it has no convention for" in {
      Target.all.filter(_.supported).map(_.pointerBits).distinct.sorted shouldBe List(32, 64)
      Target.all.filterNot(_.supported).map(_.name) shouldBe List("x86-linux")

      Target.all.filterNot(_.supported).map(_.cpu).distinct shouldBe List(Cpu.X86)
      Cpu.buildable should not contain Cpu.X86
    }

    // A processor's width is the processor's, and every target on one agrees about it -- which is
    // what lets `Layout` and `Type.llvm` take a `Word` rather than a whole `Target`.
    "reads each target's word off its processor" in {
      for t <- Target.all do
        withClue(t.name) {
          t.word.bits shouldBe t.cpu.bits
          t.pointerBytes shouldBe t.cpu.bits / 8
        }
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

    // The storage `Type.VaList` reserves has to hold whatever `va_start` writes into it, and it is
    // four *words* — so this is a claim per target rather than one number, and on a 32-bit machine
    // it is sixteen bytes against a `va_list` of four. Asserting the old single figure of 32 would
    // now be asserting it of machines that do not have it.
    "reserves storage wide enough for every target's own va_list" in {
      for t <- Target.all do
        withClue(t.name)(Layout(t).size(Type.VaList) should be >= t.vaListBytes)
    }

    // Neither bare-metal RISC-V is built for the floating extension, which is clang's default for
    // each of these triples and therefore has to be sysl's — the two are handed the same triple and
    // have to make the same assumption about it (`CAbi`). At 32 bits it is firmer than a default:
    // the RP2350's Hazard3 is RV32IMAC, with no F extension to use. The Thumb sibling is the one
    // that is here on purpose rather than by the triple's default, and the test below is about it.
    "records which targets have floating registers to pass arguments in" in {
      Target.all.filterNot(_.hardFloat).map(_.name) shouldBe
        List("riscv64-freestanding", "thumb-freestanding-softfp", "thumbv6m-freestanding",
          "riscv32-freestanding")
    }

    // The two Thumb targets are one machine under two calling conventions, and the pair exists so
    // that sysl stops dictating which one a C project uses: pico-sdk's default is softfp, GNU ld
    // refuses to link the two conventions together, and before this the only way to join such a
    // build was to make the *C* follow sysl.
    //
    // So it is written down as a disagreement rather than left to be read off two rows. A sibling
    // differing only in a suffix is exactly the shape somebody tidies away, and `AbiAgainstClang`
    // would then go on measuring whatever was left against clang and stay green about it.
    "keeps the two Thumb targets apart on the float ABI, and on nothing else" in {
      val hard = Target.thumbFreestanding
      val soft = Target.thumbFreestandingSoftfp

      hard.hardFloat shouldBe true
      soft.hardFloat shouldBe false
      hard.triple should endWith("-eabihf")
      soft.triple should endWith("-eabi")

      (hard.cpu, hard.os, hard.vaList, hard.vaListBytes) shouldBe
        (soft.cpu, soft.os, soft.vaList, soft.vaListBytes)
    }

    // The third Thumb target is a different *architecture* rather than a third convention, and that
    // is worth writing down for the reason the pair above is: all three are `Cpu.Thumb` and read as
    // near-duplicates in the registry, so the thing that separates this one lives in the triple
    // alone. Armv6-M is not a smaller set of Armv8-M's options — it is the earlier architecture,
    // with no Thumb-2, no hardware divide, no unaligned access and no `ldrex`/`strex`.
    //
    // The float ABI is where the resemblance would mislead. `thumbv6m` is soft like the `softfp`
    // sibling, and for an unrelated reason: `softfp` is a *convention* chosen over an FPU that is
    // there, and this core has no FPU at all. Reading the two rows as the same fact is what would
    // make somebody collapse them.
    "keeps the Armv6-M target apart from the Armv8-M pair on the architecture" in {
      val m0  = Target.thumbv6mFreestanding
      val m33 = Target.thumbFreestandingSoftfp

      m0.triple should startWith("thumbv6m")
      m33.triple should startWith("thumbv8m")

      // Both soft, and neither says why — which is the point of the paragraph above.
      m0.hardFloat shouldBe false
      m33.hardFloat shouldBe false

      // Everything the registry records other than the triple is the same, so the triple is the
      // whole of the difference and there is nothing else for a reader to have missed.
      (m0.cpu, m0.os, m0.vaList, m0.vaListBytes, m0.shortEnums) shouldBe
        (m33.cpu, m33.os, m33.vaList, m33.vaListBytes, m33.shortEnums)
    }

    // Whether a thread's storage is laid down before `main` is a fact about the system and not about
    // the processor, and it is the OS that records it: a hosted system starts a thread by giving it
    // storage, and a bare one has no loader, no libc, and nothing that writes the thread pointer.
    "records which targets have thread-local storage set up before main" in {
      Target.all.filterNot(_.hasThreadLocalStorage).map(_.name) shouldBe
        List(
          "aarch64-freestanding",
          "x86_64-freestanding",
          "riscv64-freestanding",
          "thumb-freestanding",
          "thumb-freestanding-softfp",
          "thumbv6m-freestanding",
          "riscv32-freestanding"
        )
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
    // unknown, a reader would look for a typo that is not there. And it says what is actually
    // missing -- a measured calling convention -- rather than the width, which is no longer the
    // reason and would send a reader looking for a limit that is not there either.
    "says why a target it knows cannot be built for" in {
      val out = Target.named("x86-linux").left.getOrElse(fail("expected a complaint"))

      out should include("sysl knows 'x86-linux' and cannot build for it")
      out should include("no C calling convention has been measured for x86")
      out should not include "64-bit"
    }

    // The refusal and the listing are read by one person minutes apart, so they say one thing. They
    // did not: `sysl targets` annotated the row "(32-bit -- not yet supported)" from a sentence of
    // its own, which went on being printed after `thumb-freestanding` and `riscv32-freestanding`
    // shipped and made the width no reason at all. Asserting the *reason* rather than either
    // wording is what holds them together -- a second copy of a sentence is what drifted.
    "gives the listing the same reason the refusal gives" in {
      val why = Target.x86Linux.unsupported.getOrElse(fail("x86-linux cannot be built for"))

      Target.named("x86-linux").left.getOrElse("") should include(why)
      why should include("no C calling convention has been measured for x86")
      why should not include "not yet supported"

      for t <- Target.all if t.supported do t.unsupported shouldBe None
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

      // The target is computed rather than named, because a written-out one stops being cross on the
      // machine it names. This said `x86_64Linux`, which is foreign from the author's Mac and is the
      // *host* on an x86-64 Linux runner -- where the build then succeeds and the claim inverts.
      //
      // **A different OS, not merely a different target.** Picking the first target that is not the
      // host chooses `x86_64-macos` on an Apple Silicon machine, and clang builds that quite happily
      // -- one toolchain, two architectures, no missing linker. What makes a link actually fail is
      // the platform: there is no Linux linker on a Mac and no Mach-O one on Linux.
      val foreign =
        List(Target.aarch64MacOS, Target.x86_64MacOS, Target.aarch64Linux, Target.x86_64Linux)
          .find(t => !Target.host.map(_.os).contains(t.os))
          .getOrElse(fail("no target has an OS foreign to this machine"))

      val exe = "/tmp/sysl-cross-target-test"

      Toolchain.build(irFor(foreign, "print(1)"), exe, foreign).isLeft shouldBe true
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

  /** `targets.md § What a target does not decide` used to rest the whole of `Layout` on one claim:
   * every target in the registry answers a layout question the same way, so the object that answers
   * them takes no `Target`. **A 32-bit target falsifies that**, and what replaced it is narrower and
   * says more: a layout question is answered by the *word*, and by nothing else a target carries.
   *
   * So `Layout` takes a `Word` rather than a `Target` — which is a stronger statement than taking
   * none, because it names what the answer depends on instead of asserting it depends on nothing.
   * `LayoutTests` measures those answers against *this machine*; the agreement between machines can
   * only be made by emitting one program for more than one of them, which is what is here.
   */
  "what a target does not decide" - {
    "targets of one width lay an aggregate out the same way, which is why Layout takes a Word" in {
      for (word, group) <- Target.all.filter(_.supported).groupBy(_.word) do
        val first = typeLines(group.head)

        first should not be empty

        for t <- group.tail do withClue(s"${t.name} against ${group.head.name} at ${word.bits}-bit")(
          typeLines(t) shouldBe first
        )
    }

    // And the OS is not one of the things it depends on: two targets on one processor differ in
    // their `va_list` walk and in nothing a layout can see. That is the claim the old single-group
    // version was really making, and it survives the split.
    "and an operating system decides no part of a layout" in {
      val aarch64 = Target.all.filter(t => t.cpu == Cpu.Aarch64 && t.supported)

      aarch64.map(_.os).distinct.length should be > 1
      aarch64.map(typeLines).distinct.length shouldBe 1
    }

    // A width's reach is narrow, which is the other half of the same point. The shapes above hold
    // no pointer -- fixed scalars, an array, a data enum's union region, and a `va_list`, which is
    // `[4 x ptr]` in every module because the *spelling* is width-free even where the bytes are not.
    // So they are laid out identically at both widths, and a machine of another width changes
    // nothing about any of them.
    "and a width reaches only what actually holds a pointer or a usize" in {
      val mine  = Set("Padded", "Nested", "Payload", "va_list")
      val ours  = (t: Target) => typeLines(t).filter(l => mine.exists(l.contains))
      val heads = Target.all.filter(_.supported).groupBy(_.word).values.map(g => ours(g.head)).toList

      heads.head should not be empty
      withClue(heads.map(_.mkString("\n")).mkString("\n---- against ----\n"))(heads.distinct.length shouldBe 1)
    }

    // And the claim stated from the other end, over the standard library's types rather than the
    // four above: **a width decides how wide a machine word is, and decides nothing else.** The two
    // modules declare exactly the same types, in the same order -- nothing appears at one width and
    // not the other -- and every body that differs holds a word.
    //
    // Written three times before it was right, and each failure was the finding:
    //
    //   - "the whole modules agree" -- no, because the module carries the library's types too, and
    //     plenty of those hold a `usize`.
    //   - "everything that moves has a view in it" -- no: `%sysl$Option.usize.Some = type { i32 }`
    //     holds a `usize` with no view anywhere near it.
    //   - "a differing body is an i64 against an i32, token for token" -- no, and this is the one
    //     worth keeping. A data enum's payload region is a **blob of words**, so
    //     `%enum.sysl$Result...File.IoError` is `[1 x i64]` at 64 bits and `[2 x i32]` at 32: the
    //     same eight bytes, spelled with a different element *and* a different count. A width can
    //     change the shape of a union region, which is not what "only the integer widths move"
    //     would have led anyone to expect.
    "and a width decides how wide a word is, and nothing else about a type" in {
      val widths = Target.all.filter(_.supported).groupBy(_.word).toList.sortBy(_._1.bits)
      val lines  = widths.map((_, g) => typeLines(g.head))

      widths.map(_._1.bits) shouldBe List(32, 64)

      val (narrow, wide) = (lines.head, lines.last)
      val name           = (s: String) => s.takeWhile(_ != '=').trim

      narrow.map(name) shouldBe wide.map(name)

      val moved = narrow.zip(wide).filter(_ != _)

      withClue("no type moved at all, so this proved nothing")(moved should not be empty)

      for (n, w) <- moved do
        withClue(s"\n$n\n$w") {
          n should include("i32")
          w should include("i64")
        }
    }

    // What a width *does* decide, stated where it is visible: a view carries its length as a
    // `usize`, so it is one word narrower on a 32-bit machine. This is the single target-dependent
    // LLVM type in the language, and the reason `Type.llvm` takes a `Word` at all.
    "while a view's length is the width, which is the one type that is" in {
      val slice = "count(xs: []const int) -> usize\n    xs.len\nvar n = count([1, 2, 3])"

      for t <- Target.all if t.supported do
        given Word = t.word

        withClue(t.name) {
          Type.Slice(Type.Integer(64, true)).llvm shouldBe s"{ ptr, ptr, i${t.word.bits} }"
          irFor(t, slice) should include(s"{ ptr, ptr, i${t.word.bits} }")
        }
    }

    // And the agreement is worth something only because the modules themselves disagree: these same
    // targets lower a variadic walk four different ways, so "the type lines match" is a fact about
    // layout and not about the target being ignored.
    "while the code around those types is not the same for all of them" in {
      val supported = Target.all.filter(_.supported)

      supported.map(t => irFor(t, shapes)).distinct.length should be > 1
    }

    // The storage is sysl's rather than the target's: a module built for a machine whose own
    // `va_list` is eight bytes still reserves four words. The *spelling* is one string on every
    // target — `[4 x ptr]` — and it is the pointer inside it that makes the bytes differ, which is
    // why this asserts the text and the section above asserts the width.
    "and a va_list is spelled one way in every module, four words wide" in {
      Target.all.filter(_.supported).map(_.vaListBytes).distinct.length should be > 1

      for t <- Target.all if t.supported do
        given Word = t.word

        withClue(t.name) {
          Layout(t).size(Type.VaList) shouldBe t.pointerBytes * 4
          irFor(t, shapes) should include(s"alloca ${Type.VaList.llvm}")
        }
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
