package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Programs that **ran** on a 32-bit machine, rather than programs that compiled for one.
 *
 * This is the tier the whole 32-bit change hangs on. Everything below it reads text: `CAbiTests`
 * pins a table against itself, `AbiAgainstClangTests` pins it against clang, and
 * `CrossTargetBuildTests` proves the module verifies and assembles. **A miscompile is text too** —
 * a length loaded at the wrong width, an index widened to a size the machine does not have, an
 * aggregate coerced into the wrong register — and every one of those produces a module that
 * verifies, assembles, and computes the wrong answer.
 *
 * The board is QEMU's `virt`, which is the RISC-V half of the RP2350's pair in every way this tier
 * cares about: RV32 with no floating extension, a 16550 UART, and RAM where the linker script says.
 * `QemuSupport` has the mechanism and `resources/qemu/README.md` has the reasoning.
 *
 * **Both channels are used, for what each is good for.** A program says what it computed through
 * the UART, and the startup reports what `main` returned through the board's exit device. Reading
 * only the status would say a run failed without saying which value was wrong, which is no use for
 * the one class of bug this tier can see and no other can.
 */
class QemuRunTests extends AnyFreeSpec with QemuSupport {

  /** A UART is a device, so its register is `volatile` — a store to it is an effect and not a value
   * computation, and an optimizer entitled to drop a store nobody reads would drop the program's
   * whole output. `03 § Device memory` is the chapter.
   *
   * This is also the answer to how a freestanding program prints at all: **a volatile store through
   * a pointer is a language feature**, so nothing here needs `asm`, semihosting, or a debug host.
   * The 16550 at `0x10000000` is `virt`'s, and `-nographic` pipes it to the emulator's own stdout.
   */
  private val uart =
    """struct Uart
      |    data: volatile u8
      |
      |val UART: usize = 0x10000000
      |val regs: *Uart = ptr_cast(UART)
      |
      |putc(c: u8)
      |    regs.data = c
      |
      |digit(n: usize)
      |    putc(u8('0') + u8(n))
      |""".stripMargin

  private val rv32 = Target.riscv32Freestanding

  s"a program on ${rv32.name}" - {

    // The smallest claim this tier can make, and it is not a small one: the toolchain assembles a
    // startup, the linker places the image where the script says, the board boots it, `main` runs,
    // and what it returned reaches the emulator's exit status. Everything below rests on it, so it
    // is asserted on its own rather than assumed by the tests that follow.
    "boots, runs, and reports what it returned" in {
      val (status, out) = bootUnderQemu(rv32, s"$uart\nputc(u8('o'))\nputc(u8('k'))\n")

      withClue(s"the board said: '$out'")(status shouldBe 0)
      out should include("ok")
    }

    // A `usize` is the width this whole change is about, and here it is four bytes where the host's
    // is eight. A length computed at the wrong width is the bug class `CrossTargetBuildTests` cannot
    // see: an `i64` on a machine with 32-bit registers verifies, assembles, and loads rubbish.
    "computes a length and a slice's length at the machine's width" in {
      val src =
        s"""$uart
           |
           |var xs = [u8(10), u8(20), u8(30), u8(40), u8(50)]
           |val s = xs[1..<4]
           |
           |digit(xs.len)
           |digit(s.len)
           |""".stripMargin

      val (status, out) = bootUnderQemu(rv32, src)

      withClue(s"the board said: '$out'")(status shouldBe 0)
      out should include("53")
    }

    /* Indices, and the bounds check around them. `widenIndex` widened an index to a fixed sixty-four
     * bits before comparing it against a length, which is exactly the shape that verifies and then
     * compares two different numbers.
     *
     * **This cannot run yet, and the reason has nothing to do with widths.** Passing a slice to a
     * function makes its release reachable, ARC's release path calls `@free` by name, and a bare
     * board has no allocator to link against — so the image does not link. `NoAllocEmissionTests`
     * has the diagnosis and the chapter text it contradicts; the assertions here are what this test
     * should say once the free path is indirect.
     *
     * The neighbouring tests take slices too and link, which is not a contradiction: `-O2` deletes
     * an internal function nothing reaches, so a slice whose release is never *called* costs the
     * link nothing.
     */
    "indexes through a bounds check the machine can express" ignore {
      val src =
        s"""$uart
           |
           |pick(xs: []const u8, i: usize) -> u8
           |    xs[i]
           |
           |var letters = [u8('a'), u8('b'), u8('c'), u8('d')]
           |
           |for i in 0..<letters.len
           |    putc(pick(letters[..], i))
           |""".stripMargin

      val (status, out) = bootUnderQemu(rv32, src)

      withClue(s"the board said: '$out'")(status shouldBe 0)
      out should include("abcd")
    }

    // An aggregate crossing a call boundary is where a wrong ABI table shows up as a wrong answer
    // rather than as a wrong string. RV32 passes two words as `[2 x i32]` and eight aligned bytes as
    // an `i64`; a table with those the other way round puts the second field where the callee looks
    // for the first, and nothing below this tier would notice.
    "hands an aggregate to a function and gets the same bytes back" in {
      val src =
        s"""$uart
           |
           |struct Pair
           |    a: u8
           |    b: u8
           |
           |swap(p: Pair) -> Pair
           |    Pair(p.b, p.a)
           |
           |val q = swap(Pair(u8('x'), u8('y')))
           |
           |putc(q.a)
           |putc(q.b)
           |""".stripMargin

      val (status, out) = bootUnderQemu(rv32, src)

      withClue(s"the board said: '$out'")(status shouldBe 0)
      out should include("yx")
    }

    // A `usize` really is thirty-two bits here and not merely spelled as one, which is the claim a
    // host machine cannot make about itself. Wrapping is the cheapest way to ask: the largest
    // `usize` on this board is 2^32 - 1, and on the machine running this suite it is not.
    "wraps a usize at the width the machine actually has" in {
      val src =
        s"""$uart
           |
           |var top: usize = 0
           |top -= 1
           |
           |if top == 4294967295 then putc(u8('y')) else putc(u8('n'))
           |""".stripMargin

      val (status, out) = bootUnderQemu(rv32, src)

      withClue(s"the board said: '$out'")(status shouldBe 0)
      out should include("y")
    }
  }
}
