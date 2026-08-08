package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Inline assembly (`inline-assembly.md`).
 *
 * A target is a value, so every one of these questions is answerable on one laptop: which arm a
 * processor selects, what the constraint string comes out as, and which programs are refused for a
 * machine that is not this one. Reading the emitted text is the whole of what a cross-target test
 * can do, and it is enough — what the construct decides is what text comes out.
 */
class InlineAssemblyTests extends AnyFreeSpec with Matchers with CodegenSupport {

  private val arm   = Target.aarch64MacOS
  private val intel = Target.x86_64Linux
  private val risc  = Target.riscv64Freestanding

  /** Every arm an exhaustive statement needs, so a test about one processor does not have to spell
   * the rest to get past the coverage rule.
   *
   * **Read off `Cpu.buildable` rather than written out**, because the coverage rule is exhaustive
   * over exactly that: adding a processor to the registry adds an arm every `asm` in the language
   * owes, and a written-out list here would turn one registry entry into twenty stale fixtures. It
   * did, once — `thumb` and `riscv32` arriving broke twenty tests in this file that had nothing to
   * do with either.
   */
  private def others(kept: String*): String =
    Cpu.buildable.map(_.symbol).filterNot(kept.contains)
      .map(a => s"""        [$a] unavailable "not this test's business"""").mkString("\n")

  /** The same thing for a fixture that gives every processor the *same* instruction: the arms that
   * are not the subject of the test still have to say something, and here that something is real
   * assembly rather than a refusal.
   */
  private def rest(insn: String)(kept: String*): String =
    Cpu.buildable.map(_.symbol).filterNot(kept.contains)
      .map(a => s"""        [$a] "$insn"""").mkString("\n")

  /** One arm covering every processor the test is not about, with a reason of its own — for the
   * fixtures whose subject *is* the reason, and which would say nothing if it were boilerplate.
   */
  private def unavailable(reason: String)(kept: String*): String =
    s"""        [${Cpu.buildable.map(_.symbol).filterNot(kept.contains).mkString(", ")}]""" +
      s""" unavailable "$reason""""

  "selecting an arm" - {

    "each processor takes its own instructions and carries none of the others" in {
      val src =
        s"""halt()
           |    asm
           |        [x86_64]  "hlt"
           |        [aarch64] "wfi"
           |${rest("wfi")("x86_64", "aarch64")}
           |
           |halt()
           |""".stripMargin

      irFor(intel, src) should include("""asm sideeffect "hlt"""")
      irFor(intel, src) should not include "wfi"
      irFor(arm, src) should include("""asm sideeffect "wfi"""")
      irFor(arm, src) should not include "hlt"
    }

    "an arm may answer for several processors at once" in {
      val src =
        s"""barrier()
           |    asm
           |        [aarch64, riscv64] "nop"
           |${others("aarch64", "riscv64")}
           |
           |barrier()
           |""".stripMargin

      irFor(arm, src) should include("""asm sideeffect "nop"""")
      irFor(risc, src) should include("""asm sideeffect "nop"""")
    }

    "an arm with nothing under it emits nothing, and is not an error" in {
      val src =
        s"""barrier()
           |    asm
           |        [x86_64]
           |        [aarch64] "dmb ish"
           |        [riscv64] "fence rw, rw"
           |        [riscv32] "fence rw, rw"
           |        [thumb]   "dmb ish"
           |
           |barrier()
           |""".stripMargin

      irFor(intel, src) should not include "asm sideeffect"
      irFor(arm, src) should include("""asm sideeffect "dmb ish"""")
    }

    "several instructions are joined into one block, in the order written" in {
      val src =
        s"""twice()
           |    asm
           |        [x86_64]
           |            "nop"
           |            "cli"
           |            "sti"
           |${others("x86_64")}
           |
           |twice()
           |""".stripMargin

      irFor(intel, src) should include("""asm sideeffect "nop\0Acli\0Asti"""")
    }
  }

  "covering every processor" - {

    "a missing arm is an error on a build for a processor that has one" in {
      val src =
        """halt()
          |    asm
          |        [x86_64] "hlt"
          |
          |halt()
          |""".stripMargin

      val e = errFor(intel, src)

      e should include("no arm for")

      // Every processor a target can be built for, named — so a registry that grows is a diagnostic
      // that grows with it, and the reader is never left to work out which one was meant.
      for c <- Cpu.buildable if c != Cpu.X86_64 do withClue(c.symbol)(e should include(s"'${c.symbol}'"))

      // And named as a *list*: commas up to the last, which takes the "or". Joining them all with
      // "or" was fine while three processors meant an arm was rarely missing more than two; a bare
      // block now names five, and "'a' or 'b' or 'c' or 'd' or 'e'" is parsed rather than read.
      val listed = Cpu.buildable.filterNot(_ == Cpu.X86_64).map(c => s"'${c.symbol}'")

      e should include(s"no arm for ${listed.init.mkString(", ")} or ${listed.last}.")
    }

    "'unavailable' covers a processor, so the ones with an answer still build" in {
      val src =
        s"""port_out()
           |    asm
           |        [x86_64] "outb %al, %dx"
           |${unavailable("port I/O is x86-only; devices are reached through memory")("x86_64")}
           |
           |port_out()
           |""".stripMargin

      irFor(intel, src) should include("""asm sideeffect "outb %al, %dx"""")
    }

    "and carries its reason to the processor that has none" in {
      val src =
        s"""port_out()
           |    asm
           |        [x86_64] "outb %al, %dx"
           |${unavailable("port I/O is x86-only; devices are reached through memory")("x86_64")}
           |
           |port_out()
           |""".stripMargin

      val e = errFor(arm, src)

      e should include("no answer on aarch64")
      e should include("port I/O is x86-only; devices are reached through memory")
    }

    "a processor nobody has heard of is a mistake, not a machine" in {
      val src =
        s"""f()
           |    asm
           |        [powerpc] "nop"
           |${others()}
           |
           |f()
           |""".stripMargin

      val e = errFor(intel, src)

      e should include("'powerpc' is not a processor")
      e should include("'aarch64'")
    }

    "two arms cannot claim the same processor" in {
      val src =
        s"""f()
           |    asm
           |        [x86_64] "nop"
           |        [x86_64] "cli"
           |${others("x86_64")}
           |
           |f()
           |""".stripMargin

      errFor(intel, src) should include("already has an arm above this one")
    }

    // The coverage rule is exhaustive over `Cpu.buildable`, not over `Cpu`: `x86` is in the enum
    // because `x86-linux` is in the registry, and no arm is owed for it because no target can be
    // built for it. That was once the same sentence as "32-bit is not supported" and is not any
    // more — `riscv32` and `thumb` are 32-bit and every `asm` in the language owes them an arm.
    "a processor no target can be built for is not one an arm is owed" in {
      val src =
        s"""f()
           |    asm
           |${rest("nop")()}
           |
           |f()
           |""".stripMargin

      src should not include "[x86]"
      src should include("[riscv32]")

      irFor(intel, src) should include("asm sideeffect")
    }
  }

  "operands" - {

    "an input is loaded before the block and passed as a register operand" in {
      val src =
        s"""use(n: int)
           |    asm
           |        [x86_64]
           |            "nop {n}"
           |            in n : reg
           |${others("x86_64")}
           |
           |use(7)
           |""".stripMargin

      val out = irFor(intel, src)

      out should include("load i32, ptr %n.addr")
      out should include("""asm sideeffect "nop $0", "r,~{memory},~{cc}"(i32""")
    }

    "an output is stored back into the variable it names" in {
      val src =
        s"""read_reg() -> int
           |    var v: int = 0
           |    asm
           |        [x86_64]
           |            "mov $$7, {v}"
           |            out v : reg
           |${others("x86_64")}
           |    v
           |
           |print(read_reg())
           |""".stripMargin

      val out = irFor(intel, src)

      out should include("""= call i32 asm sideeffect "mov $$7, $0", "=r,~{memory},~{cc}"()""")
      out should include("store i32")
    }

    "a named machine register is written into the constraint as that register" in {
      val src =
        s"""out_byte(port: u16, value: u8)
           |    asm
           |        [x86_64]
           |            "outb {value}, {port}"
           |            in port : "dx"
           |            in value : "al"
           |${others("x86_64")}
           |
           |out_byte(0x3f8u16, 65u8)
           |""".stripMargin

      val out = irFor(intel, src)

      out should include("""asm sideeffect "outb $1, $0", "{dx},{al},~{memory},~{cc}"""")
    }

    "outputs are numbered before inputs, which is the order LLVM reads them in" in {
      val src =
        s"""both(n: int) -> int
           |    var v: int = 0
           |    asm
           |        [x86_64]
           |            "mov {n}, {v}"
           |            out v : reg
           |            in n : reg
           |${others("x86_64")}
           |    v
           |
           |print(both(3))
           |""".stripMargin

      irFor(intel, src) should include("""asm sideeffect "mov $1, $0", "=r,r,~{memory},~{cc}"""")
    }

    "a clobbered register joins the constraint before the two that are always there" in {
      val src =
        s"""f()
           |    asm
           |        [x86_64]
           |            "nop"
           |            clobbers "rax", "rdx"
           |${others("x86_64")}
           |
           |f()
           |""".stripMargin

      irFor(intel, src) should include(""""~{rax},~{rdx},~{memory},~{cc}"""")
    }

    "an operand naming nothing is an undefined name" in {
      val src =
        s"""f()
           |    asm
           |        [x86_64]
           |            "nop {ghost}"
           |            in ghost : reg
           |${others("x86_64")}
           |
           |f()
           |""".stripMargin

      errFor(intel, src) should include("undefined name 'ghost'")
    }

    "a placeholder no operand declares is refused before it reaches the assembler" in {
      val src =
        s"""f(n: int)
           |    asm
           |        [x86_64]
           |            "nop {m}"
           |            in n : reg
           |${others("x86_64")}
           |
           |f(1)
           |""".stripMargin

      errFor(intel, src) should include("'{m}' names an operand this assembly does not declare")
    }

    "reading and writing one variable is refused, because it is two registers" in {
      val src =
        s"""f()
           |    var n: int = 1
           |    asm
           |        [x86_64]
           |            "add {n}, {n}"
           |            in n : reg
           |            out n : reg
           |${others("x86_64")}
           |
           |f()
           |""".stripMargin

      val e = errFor(intel, src)

      e should include("both read and written here")
      e should include("two operands")
    }

    "a 'val' cannot be written by the instructions" in {
      val src =
        s"""f()
           |    val n = 1
           |    asm
           |        [x86_64]
           |            "mov $$0, {n}"
           |            out n : reg
           |${others("x86_64")}
           |
           |f()
           |""".stripMargin

      errFor(intel, src) should include("because it is a 'val'")
    }

    "a float has no register class yet, and the diagnostic says so in those terms" in {
      val src =
        s"""f()
           |    var x: f64 = 1.0
           |    asm
           |        [x86_64]
           |            "nop {x}"
           |            in x : reg
           |${others("x86_64")}
           |
           |f()
           |""".stripMargin

      val e = errFor(intel, src)

      e should include("no floating register class yet")
      e should include("'reg'")
    }
  }

  "what the compiler owns" - {

    "a dollar the program wrote survives as one, rather than being doubled in the source" in {
      val src =
        s"""f()
           |    asm
           |        [x86_64] "movq $$1, %rsi"
           |${others("x86_64")}
           |
           |f()
           |""".stripMargin

      // Doubled in the emitted template, because `$` is LLVM's operand marker — and written once in
      // the sysl source, which is the whole point of the compiler owning the escape.
      irFor(intel, src) should include("""asm sideeffect "movq $$1, %rsi"""")
    }

    "a doubled brace is a literal one, so a register list can be written" in {
      val src =
        s"""f()
           |    asm
           |        [aarch64] "push {{r0, r1}}"
           |${others("aarch64")}
           |
           |f()
           |""".stripMargin

      irFor(arm, src) should include("""asm sideeffect "push {r0, r1}"""")
    }

    "a label is renamed per expansion, so two blocks do not define one symbol twice" in {
      // Two *emitted blocks*, which is what collides — calling one function twice emits its block
      // once and would prove nothing. Each site has to end up with a label of its own.
      val src =
        s"""spin()
           |    asm
           |        [x86_64]
           |            "spot: nop"
           |            "jmp spot"
           |${others("x86_64")}
           |
           |wait()
           |    asm
           |        [x86_64]
           |            "spot: nop"
           |            "jmp spot"
           |${others("x86_64")}
           |
           |spin()
           |wait()
           |""".stripMargin

      val out = irFor(intel, src)

      // The definition and its reference move together, and the two sites do not collide.
      out should include("""spot.1: nop\0Ajmp spot.1""")
      out should include("""spot.2: nop\0Ajmp spot.2""")
      out should not include "\"spot: nop"
    }

    "a label that only looks like another is left alone" in {
      // The boundary rule, at the level of the function rather than through a compilation, because
      // what is being pinned is which occurrences count as whole ones -- and a program cannot easily
      // be made to contain every neighbouring case at once.
      //
      // This is where the renaming stopped being a regex. The natural spelling uses lookbehind and
      // lookahead, which Scala Native's RE2-derived engine rejects outright, so the compiler that
      // ships threw `PatternSyntaxException` on any assembly carrying a label while the JVM build
      // this suite normally runs on was perfectly happy. These cases hold the replacement to the
      // same semantics now that it is a scan.
      Asm.uniquifyLabels(List("spot: nop", "jmp spot"), 7) shouldBe
        List("spot.7: nop", "jmp spot.7")

      // A longer name merely starting with the label, and one merely ending with it.
      Asm.uniquifyLabels(List("spot: nop", "jmp spotless", "jmp respot"), 7) shouldBe
        List("spot.7: nop", "jmp spotless", "jmp respot")

      // The characters that continue a name are `[A-Za-z0-9_.$]`, so each of them guards an
      // occurrence -- including the dot and the dollar, which begin ordinary label names.
      Asm.uniquifyLabels(List("spot: nop", "jmp spot.other", "jmp x$spot", "jmp spot_2"), 7) shouldBe
        List("spot.7: nop", "jmp spot.other", "jmp x$spot", "jmp spot_2")

      // And a separator that is not one of them does not guard it: two references on one line both
      // move, which is the case a scan that forgot to keep going after a match would get wrong.
      Asm.uniquifyLabels(List("spot: nop", "jmp spot, spot"), 7) shouldBe
        List("spot.7: nop", "jmp spot.7, spot.7")
    }

    "two labels sharing a prefix are renamed apart" in {
      // Renaming is a fold over the defined names, so each pass runs over what the previous one
      // produced. `spot` is a prefix of `spot2`, and the rename of `spot` inserts a dot -- which is
      // a name character. Getting this wrong turns `spot2` into `spot.7 2` or renames it twice.
      Asm.uniquifyLabels(List("spot: nop", "spot2: nop", "jmp spot", "jmp spot2"), 7) shouldBe
        List("spot.7: nop", "spot2.7: nop", "jmp spot.7", "jmp spot2.7")
    }

    "an operand named twice in one template is one operand, at one number" in {
      val src =
        s"""f(n: int)
           |    asm
           |        [x86_64]
           |            "add {n}, {n}"
           |            in n : reg
           |${others("x86_64")}
           |
           |f(2)
           |""".stripMargin

      val out = irFor(intel, src)

      out should include("""asm sideeffect "add $0, $0", "r,~{memory},~{cc}"""")
      // One load, not two: the operand is passed once however often it is named.
      out.split("load i32, ptr %n.addr").length shouldBe 2
    }
  }

  "where assembly may not go" - {

    "a contract condition cannot hold assembly, because a condition is an expression" in {
      // Not a check the analyzer makes — `asm` is a statement and `require` takes an expression, so
      // there is no way to write one inside the other. Pinned because the chapter says a contract
      // never contains assembly, and this is *why* it never does.
      val src =
        s"""f(n: int) -> int
           |    require asm
           |        [x86_64] "nop"
           |${others("x86_64")}
           |    n
           |
           |print(f(1))
           |""".stripMargin

      errFor(intel, src) should not be empty
    }

    "assembly beside a contract is ordinary, and compiles" in {
      val src =
        s"""f(n: int) -> int
           |    require n > 0
           |    asm
           |        [x86_64] "nop"
           |${others("x86_64")}
           |    n
           |
           |print(f(1))
           |""".stripMargin

      irFor(intel, src) should include("asm sideeffect")
    }

    "a function declared '-> never' with an assembly body is taken at its word" in {
      val src =
        s"""stop() -> never
           |    asm
           |        [x86_64]
           |            "cli"
           |            "1: hlt"
           |            "jmp 1b"
           |${others("x86_64")}
           |
           |stop()
           |""".stripMargin

      val out = irFor(intel, src)

      out should include("""asm sideeffect "cli\0A1: hlt\0Ajmp 1b"""")
      out should include("unreachable")
    }

    "and the promise is real, so a body that plainly returns is still refused" in {
      // The other half of taking assembly at its word: `-> never` is checked where the compiler can
      // see the body, and it is only assembly it cannot see into.
      val src =
        """stop() -> never
          |    1
          |
          |stop()
          |""".stripMargin

      errFor(intel, src) should not be empty
    }

    "a name bound by 'ref' is not an operand, since it has no slot of its own" in {
      val src =
        s"""f()
           |    var n: int = 1
           |    ref r = n
           |    asm
           |        [x86_64]
           |            "nop {r}"
           |            in r : reg
           |${others("x86_64")}
           |
           |f()
           |""".stripMargin

      errFor(intel, src) should include("'r'")
    }
  }

  "the words the construct spends" - {

    "'out', 'reg' and 'clobbers' are still ordinary names elsewhere" in {
      val src =
        """f() -> int
          |    var out = 1
          |    var reg = 2
          |    var clobbers = 3
          |    var unavailable = 4
          |    var asm = 5
          |    out + reg + clobbers + unavailable + asm
          |
          |print(f())
          |""".stripMargin

      ir(src) should include("define")
    }

    "and inside an assembly block, in a position that is not theirs" in {
      val src =
        s"""f()
           |    var out: int = 1
           |    asm
           |        [x86_64]
           |            "nop {out}"
           |            in out : reg
           |${others("x86_64")}
           |
           |f()
           |""".stripMargin

      irFor(intel, src) should include("""asm sideeffect "nop $0"""")
    }
  }
}
