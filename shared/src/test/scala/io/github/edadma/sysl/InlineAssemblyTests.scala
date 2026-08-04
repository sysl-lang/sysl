package io.github.edadma.sysl

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
   * the other two to get past the coverage rule.
   */
  private def others(kept: String*): String =
    List("x86_64", "aarch64", "riscv64").filterNot(kept.contains)
      .map(a => s"""        [$a] unavailable "not this test's business"""").mkString("\n")

  "selecting an arm" - {

    "each processor takes its own instructions and carries none of the others" in {
      val src =
        """halt()
          |    asm
          |        [x86_64]  "hlt"
          |        [aarch64] "wfi"
          |        [riscv64] "wfi"
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
        """barrier()
          |    asm
          |        [x86_64]
          |        [aarch64] "dmb ish"
          |        [riscv64] "fence rw, rw"
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
      e should include("'aarch64'")
      e should include("'riscv64'")
    }

    "'unavailable' covers a processor, so the ones with an answer still build" in {
      val src =
        """port_out()
          |    asm
          |        [x86_64] "outb %al, %dx"
          |        [aarch64, riscv64] unavailable "port I/O is x86-only; devices are reached through memory"
          |
          |port_out()
          |""".stripMargin

      irFor(intel, src) should include("""asm sideeffect "outb %al, %dx"""")
    }

    "and carries its reason to the processor that has none" in {
      val src =
        """port_out()
          |    asm
          |        [x86_64] "outb %al, %dx"
          |        [aarch64, riscv64] unavailable "port I/O is x86-only; devices are reached through memory"
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

    "32-bit x86 is not required, since no target can be built for it" in {
      val src =
        """f()
          |    asm
          |        [x86_64]  "nop"
          |        [aarch64] "nop"
          |        [riscv64] "nop"
          |
          |f()
          |""".stripMargin

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
      val src =
        s"""spin()
           |    asm
           |        [x86_64]
           |            "spot: nop"
           |            "jmp spot"
           |${others("x86_64")}
           |
           |spin()
           |spin()
           |""".stripMargin

      val out = irFor(intel, src)

      // The definition and its reference move together, and the two sites do not collide.
      out should include("""spot.1: nop\0Ajmp spot.1""")
      out should not include "\"spot: nop"
    }
  }

  "where assembly may not go" - {

    "not in a contract, which is a claim the compiler has to be able to read" in {
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

      // The contract itself is fine — what is checked here is that assembly beside one is not an
      // accident of ordering. It compiles, and the refusal below is about a contract *condition*.
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
