package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** That a target saying it has **no floating-point unit** produces code and compiles headers for a
 * machine that has not got one (`Target.noFpu`, `Toolchain.machineFlags`).
 *
 * **The row it has to be told apart from is `thumb-freestanding-softfp`, which shares its triple.**
 * That is the whole difficulty: `thumbv8m.main-none-eabi` names the soft-float *convention* and says
 * nothing about the *presence* of the unit, so two targets differ here by a flag rather than by a
 * triple and nothing in the registry's text distinguishes them. A suite that asked only "does it
 * compile" would pass on both.
 *
 * So every case is a **pair**, for the reason `CEnumWidthTests` states about its widths: a probe that
 * would have come out the same either way proves nothing, and what makes each of these a test is that
 * the sibling row is asserted to give the other answer.
 *
 * The two halves are two different failures on a real board, and neither implies the other:
 *
 *   - **the header half** — `__ARM_FP` is what CMSIS reads to decide whether the compiler intends to
 *     use an FPU, and it refuses the build outright against a `__FPU_PRESENT 0` device. That is a
 *     failure at the `#include`, before anything is lowered, and it is why the flag has to reach
 *     `compileC` and the `c const` probe and not only the two command lines that emit instructions;
 *   - **the instruction half** — with the unit believed present, LLVM selects `vmul.f32` for an
 *     ordinary multiply. That image links, boots, and takes a usage fault on the first floating-point
 *     instruction it reaches, which is as far from the cause as a failure gets.
 */
class FpuPresenceTests extends AnyFreeSpec with Matchers {

  private def guard(t: Target): String =
    Toolchain.findClang(t).getOrElse(cancel(s"no clang here has a back end for ${t.name}"))

  /** A translation unit that compiles only if the target's C compiler believes what `expected` says
   * about the unit being there. `__ARM_FP` is the macro a header actually reads, so it is the macro
   * asked about rather than a proxy for it.
   */
  private def sees(t: Target, expected: Boolean): Either[String, Unit] = {
    val root = createTempDirectory("sysl-fpu-")
    val src  = s"$root/probe.c"
    val test = if expected then "#ifndef __ARM_FP" else "#ifdef __ARM_FP"

    writeFile(src,
      s"""$test
         |#error "the compiler does not agree about the floating-point unit"
         |#endif
         |int use(void) { return 0; }
         |""".stripMargin)

    Toolchain.compileC(src, s"$root/probe.o", t)
  }

  /** Whether the object sysl produced for `t` **calls out** for a floating-point multiply of `width`.
   *
   * A machine with no unit has no `vmul.f32` to select, so the back end emits `bl __aeabi_fmul` and
   * the symbol is undefined in the object; a machine with one emits the instruction and the symbol
   * never appears. Read out of the symbol table rather than out of the assembly because the object is
   * the artifact that goes to the link, and because an EABI libcall is a *dependency on the board's
   * runtime* — which is the thing worth pinning, the same class of requirement `__aeabi_ldivmod`
   * already is on every 32-bit row.
   *
   * **`f64` asks the same question of a different half of the unit**, and on the M33 rows the two
   * answers differ: an `fpv5-sp-d16` does single precision in hardware and double by libcall.
   */
  private def callsOutForAMultiply(t: Target, width: String = "f32"): Boolean = {
    val cc  = guard(t)
    val obj = createTempFile("sysl-fpu-", ".o")

    // `@export` rather than a plain function, and it is load-bearing: a function nothing reaches is
    // never emitted, so the object came out with no multiply in it at all and the symbol table said
    // nothing either way. A test that cannot fail is worse than no test, and this one silently could
    // not until the attribute was added.
    val src = s"""@export
                 |product(a: $width, b: $width) -> $width = a * b
                 |""".stripMargin

    val ir = Compiler.compile(List(Source("p.sysl", src)), t) match
      case Right(ir) => ir
      case Left(why) => fail(s"did not compile for ${t.name}: $why")

    withClue(s"$cc, ${t.triple}: ")(Toolchain.compileObject(ir, obj, t) shouldBe Right(()))

    val listed = exec(List("nm", obj))
    deleteFile(obj)

    // Skipped rather than failed where there is no `nm`: this asks something about the object's
    // symbol table, and a machine that cannot list one cannot be asked.
    assume(listed.exitCode == 0, "nm not available")

    val libcall = if width == "f64" then "__aeabi_dmul" else "__aeabi_fmul"

    listed.stdout.linesIterator.exists(_.contains(libcall))
  }

  "a target with no floating-point unit tells the C compiler so" - {

    // Zephyr's own MPS2 defconfigs are this case and not the exotic one: none of them sets
    // `CONFIG_FPU`, and an application's `CONFIG_FPU=y` is silently dropped where the SoC does not
    // select `CPU_HAS_FPU`. So the ordinary Cortex-M33 board is the one that needs this row.
    "the Armv8-M row for an FPU-less board defines no __ARM_FP" in {
      sees(Target.thumbFreestandingSoft, expected = false) shouldBe Right(())
    }

    // The pair. Same triple, opposite answer -- which is the whole reason the row exists, since a
    // triple that settled it would have needed no new row at all.
    "and its softfp sibling, on the same triple, defines one" in {
      sees(Target.thumbFreestandingSoftfp, expected = true) shouldBe Right(())
    }

    "the Armv7E-M row for an FPU-less board defines no __ARM_FP" in {
      sees(Target.thumbv7emFreestandingSoft, expected = false) shouldBe Right(())
    }

    "and its hard-float sibling defines one" in {
      sees(Target.thumbv7emFreestanding, expected = true) shouldBe Right(())
    }

    // Armv7-M is the half of the card that needed no flag: the architecture has no unit, so the
    // triple already says it. Asserted anyway, because "it needs no flag" is a claim about clang's
    // defaults rather than about sysl, and a clang that changed its mind would go unnoticed.
    "Armv7-M says it in the triple, having no unit in the architecture" in {
      sees(Target.thumbv7mFreestanding, expected = false) shouldBe Right(())
    }
  }

  "and emits no floating-point instruction for it" - {

    "the Armv8-M row for an FPU-less board calls the EABI routine for a multiply" in {
      callsOutForAMultiply(Target.thumbFreestandingSoft) shouldBe true
    }

    // The pair again, and the one that shows `softfp` was never enough on its own: the same triple,
    // the same soft-float *convention*, and `vmul.f32` in the object regardless.
    "and its softfp sibling uses the unit, the convention being no business of the instructions" in {
      callsOutForAMultiply(Target.thumbFreestandingSoftfp) shouldBe false
    }

    "the Armv7E-M row for an FPU-less board calls the EABI routine too" in {
      callsOutForAMultiply(Target.thumbv7emFreestandingSoft) shouldBe true
    }

    "and its hard-float sibling uses the unit" in {
      callsOutForAMultiply(Target.thumbv7emFreestanding) shouldBe false
    }
  }

  /** That the unit each row has is the **row's** answer and not the toolchain's.
   *
   * Both halves above ask what the compiler did, and until the rows named their unit the compiler was
   * answering out of clang's defaults for the triple — which are not the same defaults twice.
   * `thumbv8m.main-none-eabi` with no `-mfpu` defines `__ARM_FP 0xe` under Apple clang 21 and
   * Homebrew clang 22 and defines nothing at all under the apt.llvm.org clang 20 the Linux CI
   * installs, so the two cases the `softfp` row owns were green here and red there for two releases.
   *
   * The single-precision half is the same fact seen on the board rather than in the toolchain: a
   * Cortex-M33's unit is an `fpv5-sp-d16`, and clang's default of `fpv5-d16` lowers a `f64` multiply
   * to a `vmul.f64` the part does not implement.
   */
  "and the unit named is the one the silicon has, not the one clang assumes" - {

    "a double multiply calls out on the M33's single-precision unit" in {
      callsOutForAMultiply(Target.thumbFreestandingSoftfp, width = "f64") shouldBe true
    }

    "and on its hard-float sibling, which is the same part" in {
      callsOutForAMultiply(Target.thumbFreestanding, width = "f64") shouldBe true
    }

    // The float half of the same pair, so that a row losing its unit altogether cannot pass the two
    // cases above by calling out for everything.
    "while a float multiply on those two still uses it" in {
      callsOutForAMultiply(Target.thumbFreestandingSoftfp) shouldBe false
      callsOutForAMultiply(Target.thumbFreestanding) shouldBe false
    }
  }
}
