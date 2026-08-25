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
   * whole output. `reference/memory.md § Device memory` is the chapter.
   *
   * This is also the answer to how a freestanding program prints at all: **a volatile store through
   * a pointer is a language feature**, so nothing here needs `asm`, semihosting, or a debug host.
   * The 16550 at `0x10000000` is `virt`'s, and `-nographic` pipes it to the emulator's own stdout.
   */
  /** `putc` for a board, which is the whole of what a program here needs to say anything.
   *
   * The two differ in more than an address, which is why this is per-board source and not a
   * constant. `virt`'s 16550 takes a byte at offset zero and needs no setting up. The MPS2's CMSDK
   * takes a *word*, and **transmits nothing until it is enabled** — a program that writes to it
   * cold produces no output at all, and QEMU says so only under `-d guest_errors`.
   */
  private val uarts: Map[String, String] = Map(
    "virt" ->
      """struct Uart
        |    data: volatile u8
        |
        |val UART: usize = 0x10000000
        |val regs: *Uart = ptr_cast(UART)
        |
        |putc(c: u8)
        |    regs.data = c
        |""".stripMargin,

    "mps2-an505" ->
      """struct Uart
        |    data: volatile u32
        |    state: volatile u32
        |    ctrl: volatile u32
        |    intstatus: volatile u32
        |    bauddiv: volatile u32
        |
        |val UART: usize = 0x40200000
        |val regs: *Uart = ptr_cast(UART)
        |
        |putc(c: u8)
        |    regs.data = u32(c)
        |
        |regs.bauddiv = 16
        |regs.ctrl = 1
        |""".stripMargin,

    // The nRF51's transmitter is *started* rather than enabled, and its ready flag is an event that
    // has to be cleared before each byte — a second write without clearing spins on a stale ready.
    // The registers are scattered across the map rather than adjacent, so each is its own pointer
    // instead of one struct with a hundred words of padding in it.
    "microbit" ->
      """struct Reg
        |    v: volatile u32
        |
        |val UART: usize = 0x40002000
        |
        |val starttx: *Reg = ptr_cast(UART + 0x008)
        |val txdrdy: *Reg = ptr_cast(UART + 0x11c)
        |val enable: *Reg = ptr_cast(UART + 0x500)
        |val pseltxd: *Reg = ptr_cast(UART + 0x50c)
        |val txd: *Reg = ptr_cast(UART + 0x51c)
        |val baudrate: *Reg = ptr_cast(UART + 0x524)
        |
        |putc(c: u8)
        |    txdrdy.v = 0
        |    txd.v = u32(c)
        |
        |    while txdrdy.v == 0
        |        ()
        |
        |pseltxd.v = 24
        |baudrate.v = 0x01d7e000
        |enable.v = 4
        |starttx.v = 1
        |""".stripMargin,

    // The AN500's is the AN505's device moved: five CMSDK UARTs from 0x40004000, the first of them
    // wired to QEMU's stdout by `-nographic`. The AN386 shares the map, so it shares the source —
    // the two boards differ in the processor executing the image and in nothing else.
    "mps2-an500" ->
      """struct Uart
        |    data: volatile u32
        |    state: volatile u32
        |    ctrl: volatile u32
        |    intstatus: volatile u32
        |    bauddiv: volatile u32
        |
        |val UART: usize = 0x40004000
        |val regs: *Uart = ptr_cast(UART)
        |
        |putc(c: u8)
        |    regs.data = u32(c)
        |
        |regs.bauddiv = 16
        |regs.ctrl = 1
        |""".stripMargin,
  )

  /** The AN386 and the AN385 take the AN500's source rather than a copy of it, so that the three
   * cannot drift into testing different programs — which would defeat the point of running them all,
   * that being to vary the *processor* and nothing else.
   */
  private val boardUarts: Map[String, String] =
    uarts ++ List("mps2-an386", "mps2-an385").map(_ -> uarts("mps2-an500"))

  /** The prelude a program on `b` needs before it can print, plus the one helper every test here
   * wants. `digit` is separate from the board because it is written in terms of `putc`.
   */
  private def prelude(b: Board): String =
    boardUarts(b.name) + "\ndigit(n: usize)\n    putc(u8('0') + u8(n))\n"

  for b <- boards do
    s"a program on ${b.name} (${b.target.name})" - {
      val t    = b.target
      val uart = prelude(b)

      // The smallest claim this tier can make, and it is not a small one: the toolchain assembles a
      // startup, the linker places the image where the script says, the board boots it, `main` runs,
      // and what it returned reaches the emulator's exit status. Everything below rests on it, so it
      // is asserted on its own rather than assumed by the tests that follow.
      "boots, runs, and reports what it returned" in {
        val (status, out) = bootUnderQemu(b, s"$uart\nputc(u8('o'))\nputc(u8('k'))\n")

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

        val (status, out) = bootUnderQemu(b, src)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("53")
      }

      // Indices, and the bounds check around them. `widenIndex` widened an index to a fixed sixty-four
      // bits before comparing it against a length, which is exactly the shape that verifies and then
      // compares two different numbers.
      //
      // **Passing a slice to a function makes its release reachable, so this image names `free`** —
      // and links, because the board supplies one. That is the ordinary arrangement and not a way
      // round anything: a program compiled under the default capabilities may allocate, so a board
      // running it owes it an allocator. What ticket 0037 says is a narrower and still-open thing —
      // that a module declaring `@no_alloc` emits the same call — and `NoAllocEmissionTests` is
      // where that claim is pinned.
      "indexes through a bounds check the machine can express" in {
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

        val (status, out) = bootUnderQemu(b, src)

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

        val (status, out) = bootUnderQemu(b, src)

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

        val (status, out) = bootUnderQemu(b, src)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("y")
      }

      // A `Writer` of the program's own, which is what everything above a `putc` is built on: a
      // rendering writes into a sink rather than returning a string, so a board that can store a
      // byte can print anything the library can render. The integers are the case worth pinning,
      // because `Display` for them is sysl all the way down — `printi` would reach `snprintf`, which
      // this board has not got — and because rendering divides, which is where a 32-bit machine
      // differs from the host that ran the other tiers.
      //
      // **The console is a module of its own, and has to be**: an `impl` member cannot see a root
      // file's bindings, so `write` could reach neither the device pointer nor a `putc` built on it.
      "renders through a Writer of its own, on the machine that computed the digits" in {
        val src = List(
          Source("p.sysl",
            """import board.*
              |
              |val w = console()
              |
              |w.write("n=".bytes)
              |val n: usize = 1000
              |n.display(w, FormatSpec(0, -1, false))
              |""".stripMargin),
          boardModule(b))

        val (status, out) = bootUnderQemu(b, src, 20)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("n=1000")
      }

      // A string the program did not have when it started, which is a different half of the
      // compiler from a literal: a literal is three words the emitter writes down, and every one of
      // these reaches a runtime helper instead — `sysl.str.concat`, `sysl.str.from_bytes`, the
      // integer renderer, and `sysl.str.cmp`. Those helpers are IR *templates* rather than generated
      // code, so a length in one of them is a hardwired width until something makes it verify and
      // then run somewhere that has not got sixty-four bits.
      //
      // **The comparison is what makes this more than an assembly check.** Concatenation reads a
      // length, allocates the sum, and copies two runs by it; a length loaded at the wrong width
      // produces a string of the right shape and the wrong bytes, and only reading them back says
      // so. `CrossTargetBuildTests` proves the same program *verifies* for this triple and cannot
      // see any of that.
      "builds a string at run time and reads back what it built" in {
        val src = List(
          Source("p.sysl",
            """import board.*
              |
              |val w = console()
              |
              |val greeting = "he" + "llo"
              |val counted = greeting + "=" + str(greeting.len)
              |
              |w.write(counted.bytes)
              |if counted == "hello=5" then w.write(" same".bytes) else w.write(" differs".bytes)
              |""".stripMargin),
          boardModule(b))

        val (status, out) = bootUnderQemu(b, src, 20)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("hello=5 same")
      }

      // A `long` is sixty-four bits on a machine whose registers are thirty-two, so every division
      // rendering it performs is a call to a compiler-rt builtin rather than an instruction. That is
      // ordinary — it is what C does here too — but it is the one thing in the library that needs
      // something from the board beyond memory and a character out, and nothing below a link would
      // say so: the object file is perfectly good and names a symbol.
      "renders a long, whose division the board has to supply" in {
        val src = List(
          Source("p.sysl",
            """import board.*
              |
              |val w = console()
              |val n: long = 9007199254740993
              |
              |n.display(w, FormatSpec(0, -1, false))
              |""".stripMargin),
          boardModule(b))

        val (status, out) = bootUnderQemu(b, src, 20)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("9007199254740993")
      }

      // Module storage that starts at zero starts at zero. It is `.bss`, which occupies no bytes in
      // the file, so what a program finds there is whatever the RAM held unless the startup zeroes
      // it — and a `*Writer` that should be null but is not sends the first call through its method
      // table to an address nobody chose.
      "finds a module var that starts at zero already zero" in {
        val src = List(
          Source("p.sysl",
            """import board.*
              |
              |val w = console()
              |
              |if zeroed() then w.write("zero".bytes) else w.write("junk".bytes)
              |""".stripMargin),
          boardModule(b, "\nvar counter: int = 0\n\nzeroed() -> bool = counter == 0\n"))

        val (status, out) = bootUnderQemu(b, src, 20)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("zero")
      }

      // **The FPU, which is the one piece of hardware on this tier a startup has to switch on.**
      //
      // A Cortex-M denies coprocessor access out of reset, so the first VFP instruction a program
      // executes raises a UsageFault with the NOCP bit unless `CPACR` has been written — and it dies
      // *inside whatever arithmetic reached for VFP*, which is why this is worth a test of its own
      // rather than being left to whichever program happens to use a float first. That program
      // reports a fault in an unrelated computation.
      //
      // **Until this existed, nothing on this tier used a float at all**, so a startup that enables
      // the FPU and a startup that forgets to were indistinguishable — on every board here, for as
      // long as the tier has existed.
      //
      // `f32` rather than `f64` deliberately. `thumbv7em-none-eabihf` selects `fpv4-sp-d16`, whose
      // unit is single-precision only, so `double` arithmetic lowers to `__aeabi_dmul` and friends —
      // compiler-rt symbols a `-nostdlib` link does not define. `Target.thumbv7emFreestanding`'s
      // docstring records the same measurement from the other direction. So a `f64` program here
      // would fail at the linker and never reach the machine, testing nothing about the FPU.
      //
      // **The operand is a volatile read, and the first attempt at this test shows why it has to
      // be.** Written with module `var`s holding 2.5 and 4.0, the whole computation folded: the
      // AN500's `board$scaled` came out as `vmov.f32 s0, #1.1e+01 ; bx lr`, and the AN505's image
      // contained **no VFP instruction at all**. So the test passed on all three boards while
      // exercising the FPU on two of them and nothing on the third — decided by inlining, which is
      // not something a test should rest on. `SYSL_QEMU_KEEP` plus `llvm-objdump` is what showed it,
      // and is the check to repeat on any test whose subject is an instruction.
      //
      // `regs.bauddiv` is the CMSDK UART's divisor, which `console()` has just set to 16. It is a
      // `volatile` field, so the load must happen; 16 is exact, and `* 0.5 + 3` is exact in
      // binary32, so `==` is a fair comparison here rather than the usual trap.
      //
      // All three hard-float boards are CMSDK boards, so one source serves them.
      if t.hardFloat then
        "computes with the FPU, which the startup had to enable first" in {
          val src = List(
            Source("p.sysl",
              """import board.*
                |
                |val w = console()
                |
                |if scaled() == 11.0 then w.write("fpu".bytes) else w.write("bad".bytes)
                |""".stripMargin),
            boardModule(b, "\nscaled() -> f32 = f32(regs.bauddiv) * 0.5 + 3.0\n"))

          val (status, out) = bootUnderQemu(b, src, 20)

          withClue(s"the board said: '$out'")(status shouldBe 0)
          out should include("fpu")
        }

      // **The one test here that expects a status other than zero, and the tier needs exactly one.**
      //
      // Every other assertion in this file and in `QemuHarnessTests` reads `status shouldBe 0`, so
      // between them they cannot tell a working exit channel from one that reports zero whatever
      // happened — and reporting zero whatever happened is a real failure mode with a name: on
      // AArch32 the plain semihosting `SYS_EXIT` (0x18) takes its reason in `r1` and **always**
      // reports success, which is why every Thumb startup here uses `SYS_EXIT_EXTENDED` (0x20)
      // instead. A board wired to the wrong one passes all fifty-four of the others.
      //
      // Faulting on purpose is what exercises it, because a fault is the only route by which a
      // program on this tier can produce a non-zero status at all — `main` may not be declared to
      // return one. So this asserts two things at once: that the vector table is read (the handler
      // was reached) and that the channel carries a value (99 rather than 0 came out).
      //
      // 0x80000000 is chosen rather than null because **null is not a fault on every board here** —
      // the AN500 has RAM at address zero, so a store through a null pointer succeeds there and
      // computes nothing.
      //
      // **Two boards are excluded, and both exclusions are facts about the machine.** `virt` reports
      // through its `sifive_test` device rather than through a fault vector, so it has no handler
      // for this to reach. And `mps2-an505` has no unmapped address to store to at all: its
      // TrustZone master security controller claims the whole space —
      //
      //   0000000000000000-ffffffffffffffff  tz-msc-downstream
      //
      // — so the write is swallowed, the program runs to completion, and the board exits zero
      // honestly. That was measured with `info mtree -f` after this test failed there, and the
      // conclusion is about the machine rather than about the startup: `start_thumb.s` asks for
      // `SYS_EXIT_EXTENDED` exactly as the others do. The AN386 was measured the same way and has
      // no such region, which is why it is not a third exclusion.
      if b.name != "virt" && b.name != "mps2-an505" then
        "reports a fault as a status of its own rather than as success" in {
          val (status, _) = bootUnderQemu(b,
            s"""$uart
               |struct Bad
               |    v: volatile u32
               |
               |val BAD: usize = 0x80000000
               |val bad: *Bad = ptr_cast(BAD)
               |
               |bad.v = 1
               |""".stripMargin)

          status shouldBe 99
        }
    }
}
