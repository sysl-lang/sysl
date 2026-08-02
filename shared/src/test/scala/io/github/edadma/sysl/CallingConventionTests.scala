package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `interrupt` — the calling convention of `15 §10`, and the one annotation whose meaning is the
 * **processor's** rather than the language's.
 *
 * Everything asserted here was read off clang first, because the three processors sysl lowers for
 * answer `__attribute__((interrupt))` three different ways and no document says so in one place:
 *
 *   - **x86-64** makes it a calling convention, `x86_intrcc`, and refuses any signature but a
 *     pointer to the frame optionally followed by an integer error code.
 *   - **RISC-V** makes it a function attribute, `"interrupt"="machine"`, on a function taking
 *     nothing at all.
 *   - **AArch64** does not have it: clang answers with "unknown attribute ignored" and compiles an
 *     ordinary function.
 *
 * So the assertions are on the **emitted IR** rather than on running anything, for the reason
 * `LinkCommandTests`' are on the command line: the machine this suite runs on is not the machine
 * that would find a mistake, and no host here can even lower for RISC-V.
 */
class CallingConventionTests extends AnyFreeSpec with Matchers with RunSupport with CodegenSupport {

  /** What a handler actually does: tell the device it has been heard. An `extern` rather than a
   * global, since a handler shares no storage with the code it interrupted that is not volatile.
   */
  private val ack = "extern \"ack\" ack()\n\n"

  /** A handler taking nothing — the shape RISC-V requires. */
  private val bare = ack +
    """interrupt timer()
      |    ack()
      |""".stripMargin

  /** A handler taking the frame — the shape x86-64 requires. */
  private val framed = ack +
    """struct Frame
      |    ip: u64
      |end Frame
      |
      |interrupt fault(f: *Frame)
      |    ack()
      |""".stripMargin

  "on x86-64 it is a calling convention, with the frame passed byval" - {

    "the define carries 'x86_intrcc'" in {
      irFor(Target.x86_64Linux, framed) should include("define x86_intrcc void @fault")
    }

    // The processor pushes the frame and hands the handler its address; LLVM models that as a
    // `byval` pointer, which is the only parameter attribute sysl emits.
    "and the frame parameter is byval over the struct it points at" in {
      irFor(Target.x86_64Linux, framed) should include("ptr byval(%struct.Frame) %f.param")
    }

    "an error code after the frame is an ordinary integer, not a second byval" in {
      val ir = irFor(Target.x86_64Linux, ack +
        """struct Frame
          |    ip: u64
          |end Frame
          |
          |interrupt fault(f: *Frame, code: u64)
          |    ack()
          |""".stripMargin)

      ir should include("ptr byval(%struct.Frame) %f.param")
      ir should include("i64 %code.param")
      ir.split("byval").length shouldBe 2
    }

    "and an ordinary function beside it is untouched" in {
      irFor(Target.x86_64Linux, framed + "\nplain(n: int) -> int = n + 1\n\nprint(plain(2))\n")
        .should(include("define i32 @plain(i32 %n.param)"))
    }

    // A handler is entered by the processor, so nothing in the program calls it — and a reachability
    // walk from what the program runs would drop it, leaving the vector table pointing at nothing.
    "a handler survives pruning although nothing calls it" in {
      irFor(Target.x86_64Linux, framed + "\nprint(1)\n") should include("define x86_intrcc void @fault")
    }

    "and so does what its own body calls" in {
      irFor(Target.x86_64Linux, framed + "\nprint(1)\n") should include("declare void @ack()")
    }
  }

  "on RISC-V it is a function attribute, and the handler takes nothing" - {

    "the define carries the attribute with the default privilege mode" in {
      irFor(Target.riscv64Linux, bare) should include("""define void @timer() "interrupt"="machine"""")
    }

    "a written mode is what is emitted" in {
      irFor(Target.riscv64Linux, ack + "interrupt(supervisor) timer()\n    ack()\n")
        .should(include(""""interrupt"="supervisor""""))
    }

    "and no calling convention is written, since RISC-V's is not one" in {
      irFor(Target.riscv64Linux, bare) should not include "intrcc"
    }
  }

  // Clang answers this with a warning and compiles an ordinary function. That is not a defensible
  // answer here: the handler then returns with `ret` where the machine needs `eret`, having saved
  // none of the registers an asynchronous entry clobbers, and nothing later in the build says a word.
  "on AArch64 it is refused rather than ignored" in {
    val e = errFor(Target.aarch64Linux, bare)

    e should include("is not something aarch64 has")
    e should include("vector table")
  }

  "the signature each processor demands is enforced" - {

    "x86-64 refuses a handler with no frame, and says what the frame is for" in {
      val e = errFor(Target.x86_64Linux, bare)

      e should include("handed the frame the processor pushed")
      e should include("interrupt timer(frame: *Frame)")
    }

    "x86-64 refuses a first parameter that is not a pointer" in {
      errFor(Target.x86_64Linux, ack + "interrupt fault(n: int)\n    ack()\n")
        .should(include("takes a pointer to the frame"))
    }

    "RISC-V refuses a handler that takes anything" in {
      errFor(Target.riscv64Linux, framed) should include("entered with no arguments")
    }

    "neither may return a value" in {
      errFor(Target.riscv64Linux, "interrupt timer() -> int = 1\n") should include("yields nothing")
    }

    "nor be generic, there being no way to say which instantiation the vector holds" in {
      errFor(Target.riscv64Linux, ack + "interrupt timer[T]()\n    ack()\n")
        .should(include("cannot be generic"))
    }
  }

  "a privilege mode is only where there are privilege modes" - {

    "an unknown RISC-V mode is refused, and the set is named" in {
      val e = errFor(Target.riscv64Linux, ack + "interrupt(hypervisor) timer()\n    ack()\n")

      e should include("is not a privilege mode")
      e should include("'machine', 'supervisor', 'user'")
    }

    "and a mode on x86-64 is refused, its convention having no level to name" in {
      errFor(Target.x86_64Linux, ack +
        """struct Frame
          |    ip: u64
          |end Frame
          |
          |interrupt(machine) fault(f: *Frame)
          |    ack()
          |""".stripMargin) should include("has none to name")
    }
  }

  "calling one is refused, since the processor is the only caller" in {
    val e = errFor(Target.riscv64Linux, bare + "\ntimer()\n")

    e should include("which the processor enters and no program calls")
    e should include("return-from-interrupt")
  }

  // `interrupt` is a soft keyword: the parenthesised form is what a call to a function somebody
  // named `interrupt` looks like, so the two have to be told apart by what follows.
  "'interrupt' is still an ordinary name" - {

    "a function may be called it, and called" in {
      run("interrupt(n: int) -> int = n + 1\nprint(interrupt(4))\n") shouldBe "5\n"
    }

    "and a variable may be" in {
      run("var interrupt = 7\nprint(interrupt * 2)\n") shouldBe "14\n"
    }
  }

  "the convention survives an artifact" in {
    val parsed = SyslParser.parse(ack + "interrupt(user) timer()\n    ack()\n", "h.sysl") match
      case Right(p)  => p
      case Left(err) => fail(err)

    val back = AstCodec.decode(AstCodec.encode(List(parsed))) match
      case Right(ps) => ps.head
      case Left(err) => fail(err)

    back.body.collectFirst { case f: FuncDecl => f.conv } shouldBe Some(Some(CallConv("interrupt", Some("user"))))
  }
}
