package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `sysl.harness` **running on the board**, which is the whole reason it is in the library.
 *
 * `QemuRunTests` asks whether a program computes the right thing on a 32-bit machine, and reads the
 * answer out of single characters a program printed by hand. That is the right shape for a codegen
 * claim and the wrong shape for anything larger: a suite of twenty checks written that way is twenty
 * characters, and a run that prints `abcdyx4` says nothing about *which* of them was wrong.
 *
 * So this tier runs a real suite. The framework names each test, reports the file and line of the
 * check that failed, renders both values, and prints a tally — on a bare board, through a UART, with
 * no C library beneath it and no debug host attached.
 *
 * **What is being asserted is the framework, not the arithmetic.** Each program below fails on
 * purpose, because a suite that only ever passes cannot show that a failure is detected, located and
 * reported. What the assertions read is the report.
 */
class QemuHarnessTests extends AnyFreeSpec with QemuSupport {

  /** A whole program: the board's module, and a root file that declares its tests, points the
   * framework at the board's console, and then runs them.
   *
   * The declarations come first and every statement after, which is how a suite reads. That is all
   * it is now — it was written down here as a *requirement*, because the other arrangement produced
   * an image that would not boot on the MPS2 and only on the MPS2, and that turned out to be a
   * linker script pointing at the wrong alias rather than anything about where a statement sits.
   * `thumb.ld` has the account, and the last test below is the one that used to fail.
   */
  private def program(t: Target, decls: String, stmts: String): List[Source] =
    List(
      Source("p.sysl",
        s"""import sysl.harness.*
           |import board.*
           |
           |$decls
           |attach(console())
           |$stmts
           |""".stripMargin),
      boardModule(t))

  for t <- List(Target.riscv32Freestanding, Target.thumbFreestanding, Target.thumbv7emFreestanding) do
    s"a suite on ${t.name}" - {

      // The claim everything else here rests on: the framework links for a bare board at all. It
      // holds module storage, renders through a trait object, and takes the address of a function —
      // and none of that may reach an allocator, a C library, or the host.
      "names each test, reports its verdict, and tallies what it ran" in {
        val (status, out) = bootUnderQemu(t, program(t,
          """passes()
            |    check(true)
            |""".stripMargin,
          """            |run("passes", &passes)
            |finish()
            |""".stripMargin), 20)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("passes:PASS")
        out should include("1 tests, 0 failed")
      }

      // A failure has to say *which* check, *where*, and *what the values were*. That is the whole
      // difference between this tier and the one below it, where a wrong answer is a wrong character.
      "reports a failed equality with both values and the line it was written on" in {
        val (status, out) = bootUnderQemu(t, program(t,
          """adds()
            |    check_eq(2 + 2, 5)
            |""".stripMargin,
          """            |run("adds", &adds)
            |finish()
            |""".stripMargin), 20)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("adds:FAIL: got 4, want 5")
        out should include("1 tests, 1 failed")
      }

      // Rendering runs on the target, which is where it can go wrong in a way the host cannot show.
      // A multi-digit number is the case that matters: `Display` for the integers is sysl all the way
      // down to the sink, so these digits were computed by the code under test on a 32-bit machine —
      // `printi` would have reached `snprintf`, which is a C library this board does not have.
      "renders a value wider than one digit, on the machine that computed it" in {
        val (status, out) = bootUnderQemu(t, program(t,
          """counts()
            |    var n: usize = 0
            |    for i in 0..<1000 do n += 1
            |    check_eq(n, 999)
            |""".stripMargin,
          """            |run("counts", &counts)
            |finish()
            |""".stripMargin), 20)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("got 1000, want 999")
      }

      // A slice crosses a call boundary here, which is the shape ticket 0037 makes unlinkable under
      // `@no_alloc` — and links under the default capabilities because ARC's `free` is reachable and
      // this board has no allocator to reach. That it links at all is a claim worth pinning: the
      // framework's whole report path passes `[]const u8` to `out.write`.
      "compares two slices and says which index differs" in {
        val (status, out) = bootUnderQemu(t, program(t,
          """bytes()
            |    check_slice_eq([u8(1), u8(2), u8(3)][..], [u8(1), u8(9), u8(3)][..])
            |""".stripMargin,
          """            |run("bytes", &bytes)
            |finish()
            |""".stripMargin), 20)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("got 2, want 9 at index 1")
      }

      // A suite is more than one test, and the counters have to survive across them — module storage
      // on a board is a fixed area of the image rather than anything an allocator handed out. Three
      // verdicts in one run, which is also the largest image this suite builds.
      "runs several tests and counts passes, failures and skips apart" in {
        val (status, out) = bootUnderQemu(t, program(t,
          """good()
            |    check(true)
            |
            |bad()
            |    check(false, "on purpose")
            |""".stripMargin,
          """            |run("good", &good)
            |run("bad", &bad)
            |skip("needs_an_adc", "no part fitted")
            |finish()
            |""".stripMargin), 20)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("good:PASS")
        out should include("bad:FAIL: on purpose")
        out should include("needs_an_adc:SKIP: no part fitted")
        out should include("2 tests, 1 failed, 1 skipped")
      }

      /* The same suite with one statement written above the declarations rather than below them.
       *
       * This was `ignore`d for months as *"the MPS2 faults on this shape and only on the MPS2"* —
       * which was true and was not about the shape at all. The board was never booting through its
       * vector table, so what ran before `main` was the image's own leading bytes decoded as
       * instructions, and anything that moved them decided whether the program survived.
       * `thumb.ld` has the account. Kept, now that it passes, because the claim is worth having:
       * where a statement sits among the declarations is not something a program's meaning depends
       * on, and a tier that once said otherwise should go on saying it does not.
       */
      "reports the same failure when a statement is written above the declarations" in {
        val (status, out) = bootUnderQemu(t, program(t,
          """""".stripMargin,
          """adds()
            |    check_eq(2 + 2, 5)
            |
            |run("adds", &adds)
            |finish()
            |""".stripMargin), 20)

        withClue(s"the board said: '$out'")(status shouldBe 0)
        out should include("adds:FAIL: got 4, want 5")
      }
    }
}
