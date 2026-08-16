package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** CRAFT — the first row in the registry that is **sixteen bits wide** and the first that no clang
 * can build for.
 *
 * **Both of those take it out of every sweep, which is why it has a file.** `CrossTargetBuildTests`
 * hands each target's module to clang and insists on an object; `AbiAgainstClangTests` asks clang
 * what it does with an aggregate. Neither can say anything here: the back end lives out of tree, is
 * an `llc` rather than a driver, and there is no craft clang for either to call. So this reads the
 * module — which is what the rest of the cross-target tier does anyway — and states the two facts
 * about the machine that reach the *types* the compiler writes rather than only the calls it makes.
 *
 * What is checked elsewhere and deliberately not here: that `llc -march=craft` accepts the module
 * and `craft as` assembles it. That is the CRAFT repository's own suite, against a toolchain this
 * one has no way to require, and duplicating it here would be asserting that somebody's build
 * directory exists.
 */
class CraftTargetTests extends AnyFreeSpec with Matchers {

  private val craft = Target.craftFreestanding

  private def moduleFor(src: String): String =
    Compiler.compile(List(Source("p.sysl", src)), craft) match
      case Right(ir) => ir
      case Left(why) => fail(s"did not compile for craft: $why")

  "the machine" - {

    "is sixteen bits, which is the whole of what makes it different" in {
      craft.cpu.bits shouldBe 16
      craft.pointerBits shouldBe 16
      craft.word.bits shouldBe 16
      craft.pointerBytes shouldBe 2
    }

    // The triple is the bare word rather than a three-field name, because that is what the
    // out-of-tree back end registers itself under and what its own test programs write.
    "names itself to LLVM as 'craft', with no vendor and no system" in {
      craft.triple shouldBe "craft"
      craft.cpu.backend shouldBe "craft"
    }

    // One spelling serves `#if` and an `asm` arm alike, and the coverage rule is exhaustive over
    // `Cpu.buildable` — so every assembly statement in the language now owes this arm.
    "is a processor a program can gate on and write assembly for" in {
      craft.cpu.symbol shouldBe "craft"
      Cpu.buildable should contain(Cpu.Craft)
      Conditional.symbols should contain("craft")
      Conditional.defined(craft) should contain("craft")
    }

    "has no floating-point unit and no convention about one" in {
      craft.noFpu shouldBe true
      craft.softFloat shouldBe true
      craft.hardFloat shouldBe false
      craft.fpu shouldBe None
    }

    // A trap here arrives at one vector with the reason in `cause`, so what the hardware reaches is
    // the kernel's decoder rather than a handler — there is no per-handler prologue to arrange.
    "has traps and still no interrupt form" in {
      Conventions.interruptForm(Cpu.Craft).isLeft shouldBe true
      Conventions.interruptForm(Cpu.Craft).left.getOrElse("") should include("cause")
    }
  }

  "the toolchain" - {

    "is the one target sysl will not drive a build for" in {
      craft.buildsWithClang shouldBe false
      Target.all.filterNot(_.buildsWithClang).map(_.name) shouldBe List("craft-freestanding")
    }

    // It is still a target that lowers — the refusal is about the driver, not about the compiler,
    // and a reader told otherwise would go looking for a missing back end.
    "lowers, so it is named rather than refused at the registry" in {
      craft.supported shouldBe true
      craft.unsupported shouldBe None
      Target.named("craft-freestanding") shouldBe Right(craft)
    }

    // An artifact is an archive of objects, so on a machine with no object format and no archiver
    // there is nothing one could be. The source road is the one bootstrap takes, for the same
    // reason: it is the only one that needs no toolchain at all.
    "takes the standard module as source, having nowhere to put an artifact" in {
      stdChoice(Config(), craft) shouldBe Stdlib.Choice.FromSource
      stdChoice(Config(), Target.default) should not be Stdlib.Choice.FromSource
    }

    "says what does work when it refuses" in {
      craft.noToolchain should include("emit-llvm")
      craft.noToolchain should include("llc -march=craft")
      craft.noToolchain should include("craft as")
    }
  }

  "the module it writes" - {

    "states the triple the back end is registered under" in {
      moduleFor("var n = 1\n") should include("""target triple = "craft"""")
    }

    // The one fact about a machine that reaches the *types* rather than the calls: a view's length
    // is a `usize`, so the whole of a slice and everything holding one is written differently here.
    "gives a view a sixteen-bit length" in {
      val ir = moduleFor(
        """add(xs: []const int) -> int
          |    var total = 0
          |    for x in xs
          |        total += x
          |    total
          |var got = add([1, 2, 3])
          |""".stripMargin)

      ir should include("{ ptr, ptr, i16 }")
      ir should not include "{ ptr, ptr, i64 }"
    }

    // `int` is 32 bits on every machine, because a width is the language's answer and not the
    // target's — so on this one the ordinary arithmetic of an ordinary program is wider than a
    // register, and the back end below is what expands it.
    "keeps an int at thirty-two bits, which is wider than the machine" in {
      moduleFor("var a = 6\nvar b = a * 7\n") should include("i32")
    }
  }
}
