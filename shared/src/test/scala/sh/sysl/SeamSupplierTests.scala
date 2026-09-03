package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** A module supplying a symbol another module's `extern` declared, which is the fifth reachability
 * root (`Reachability.walk`, `reference/ffi.md § A module may supply another module's extern`).
 *
 * **The question only exists for a module that is not the program's own**, so every case here hands
 * the supplier over as a **library** — which is what a `--lib` source root and a fetched package both
 * arrive as. A supplier in the program's own tree is kept by the ordinary `@export` rule and would
 * prove nothing about this one.
 *
 * What the seam is for is a clock: `sysl.time` declares `sysl_wall_us` and defines it nowhere, and
 * whoever is linked defines it — the standard library on a host, a package binding an RTC chip on a
 * board. The consumer imports the *declaring* module and never names the supplier, which is the whole
 * point, since naming one is naming the chip.
 */
class SeamSupplierTests extends AnyFreeSpec with Matchers with CodegenSupport {

  /** A compilation whose `lib` files are handed over the way a `--lib` root or a fetched package is,
   * so that they are **not** the program's own and `Reachability.contributing` has something to say
   * about them.
   */
  private def withLibrary(lib: (String, String)*)(fs: (String, String)*): String =
    Compiler.compiledWith(fs.toList.map((n, t) => Source(n, t)), Nil, Target.default,
      librarySources = lib.toList.map((n, t) => Source(n, t))).map(_.ir) match
      case Right(out) => out
      case Left(e)    => fail(s"expected a compilation, got:\n$e")

  /** The module that declares the seam and calls it. A consumer reaches this and nothing else. */
  private val clock =
    """module clock
      |
      |private extern "seam_wall_us" c_wall_us() -> long
      |
      |now() -> long = c_wall_us()
      |""".stripMargin

  /** The supplier, in a module the consumer never imports. */
  private val chip =
    """module chip
      |
      |@export("seam_wall_us")
      |wall_us() -> long = 7
      |""".stripMargin

  "a supplier of a symbol the program calls" - {

    "is kept although nothing imports its module" in {
      val out = withLibrary("clock/clock.sysl" -> clock, "chip/chip.sysl" -> chip)(
        "<input>" -> "import clock.now\n\nprint(now())\n")

      out should include("define i64 @seam_wall_us")
    }

    "leaves no declaration of the symbol beside its definition" in {
      // A `declare` and a `define` of one symbol in one module is `invalid redefinition of function`
      // out of LLVM, and the seam puts both in reach by construction: the declaration is the
      // consumer's `extern` and the definition is the supplier's. `Codegen` keeps the definition.
      val out = withLibrary("clock/clock.sysl" -> clock, "chip/chip.sysl" -> chip)(
        "<input>" -> "import clock.now\n\nprint(now())\n")

      out should not include "declare i64 @seam_wall_us"
    }

    "is dropped where the program never calls the extern it answers" in {
      // The narrowness that keeps `0111` intact: a supplier is kept because something is already
      // going to ask the linker for that symbol, not because it is exported. A program that never
      // asks the time carries no clock.
      val out = withLibrary("clock/clock.sysl" -> clock, "chip/chip.sysl" -> chip)(
        "<input>" -> "print(1)\n")

      out should not include "seam_wall_us"
    }

    "is dropped where its symbol answers no extern at all" in {
      // The case `0111` was filed for, restated: a package carrying its own test application. Nothing
      // declares `extern "main"`, so no live symbol answers to it and this rule never fires.
      val stray =
        """module stray
          |
          |@export("stray_entry")
          |entry() -> long = 3
          |""".stripMargin

      val out = withLibrary("clock/clock.sysl" -> clock, "stray/stray.sysl" -> stray)(
        "<input>" -> "import clock.now\n\nprint(now())\n")

      out should not include "stray_entry"
    }

    "reaches a second seam through the supplier's own body" in {
      // The fixpoint, which is what a single pass would miss: the chip's reading is corrected by a
      // module it calls through an `extern` of its own, and nothing imports either of them.
      val chained =
        """module chip
          |
          |private extern "seam_drift_us" c_drift() -> long
          |
          |@export("seam_wall_us")
          |wall_us() -> long = 7 + c_drift()
          |""".stripMargin

      val drift =
        """module drift
          |
          |@export("seam_drift_us")
          |drift() -> long = 11
          |""".stripMargin

      val out = withLibrary("clock/clock.sysl" -> clock, "chip/chip.sysl" -> chained,
        "drift/drift.sysl" -> drift)("<input>" -> "import clock.now\n\nprint(now())\n")

      out should include("define i64 @seam_wall_us")
      out should include("define i64 @seam_drift_us")
    }

    "is reported rather than left to the linker where two modules claim one symbol" in {
      // One supplier per image. The linker would say it too, naming a symbol that appears in no sysl
      // file; saying it here names both declarations.
      val other =
        """module other
          |
          |@export("seam_wall_us")
          |wall_us() -> long = 9
          |""".stripMargin

      Compiler.compiledWith(List(Source("<input>", "import clock.now\n\nprint(now())\n")), Nil,
        Target.default,
        librarySources = List(Source("clock/clock.sysl", clock), Source("chip/chip.sysl", chip),
          Source("other/other.sysl", other))).map(_.ir) match
        case Left(e)  => e should include("seam_wall_us")
        case Right(_) => fail("expected the two suppliers to be reported")
    }
  }
}
