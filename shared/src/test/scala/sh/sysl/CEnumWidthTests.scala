package sh.sysl

import io.github.edadma.cross_platform.*

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** How wide a C enumerated type is in the objects `compileC` produces (`Target.shortEnums`).
 *
 * **A `_Static_assert` is the whole apparatus, and it is why this is testable at all.** The width is
 * a decision the C front end makes and then spends: an object file records the field sizes it
 * produced and not the rule it produced them by, so reading one back says nothing about which
 * convention was asked for. Asserting the size *inside the translation unit* asks the compiler the
 * question while it still has the answer, and a wrong width is then a compile that fails.
 *
 * **Each case is a pair**, for the reason `SearchPathTests` states about its `-L`: a probe that
 * would have compiled either way proves nothing, so the width the target does *not* use is asserted
 * to be refused.
 */
class CEnumWidthTests extends AnyFreeSpec with Matchers {

  /** A translation unit that compiles only if `enum E` came out `width` bytes wide.
   *
   * One enumerator, and it is `1` — small enough that every convention *could* hold it in a byte,
   * which is what leaves the width entirely to the rule rather than to the value.
   */
  private def compiledWith(target: Target, width: Int): Either[String, Unit] = {
    val root = createTempDirectory("sysl-enum-")
    val src  = s"$root/probe.c"

    writeFile(src,
      s"""enum E { A = 1 };
         |_Static_assert(sizeof(enum E) == $width, "the enum is not this wide");
         |int use(void) { return A; }
         |""".stripMargin)

    Toolchain.compileC(src, s"$root/probe.o", target)
  }

  private def guard(target: Target): Unit =
    assume(Toolchain.findClang(target).isRight, s"no clang for ${target.name}")

  "a package's C is compiled with the enum width the target's own C compilers use" - {

    /** AAPCS says an enumerated type is the smallest containing type, and GNU's `arm-none-eabi`
     * implements it by defaulting to `-fshort-enums`. Clang on the same triple defaults the other
     * way, so a package's C linked into a pico-sdk build disagreed with everything around it — which
     * the linker reported, once per object, for two days before anybody read it.
     */
    "AAPCS asks bare-metal ARM for the smallest type that holds the values" in {
      guard(Target.thumbFreestandingSoftfp)
      compiledWith(Target.thumbFreestandingSoftfp, 1) shouldBe Right(())
    }

    "and its hard-float sibling agrees, the width being no business of the float convention" in {
      guard(Target.thumbFreestanding)
      compiledWith(Target.thumbFreestanding, 1) shouldBe Right(())
    }

    "the same source asserting four bytes is refused there, which is what makes those tests tests" in {
      guard(Target.thumbFreestandingSoftfp)
      compiledWith(Target.thumbFreestandingSoftfp, 4).isLeft shouldBe true
    }

    // Everywhere else an `int` is what the platform's own compilers produce, so passing the flag
    // unconditionally would have made sysl the odd one out on five targets to fix one.
    "everywhere else an enum is an int" in {
      guard(Target.default)
      compiledWith(Target.default, 4) shouldBe Right(())
    }

    "and asserting one byte there is refused" in {
      guard(Target.default)
      compiledWith(Target.default, 1).isLeft shouldBe true
    }
  }
}
