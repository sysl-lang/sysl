package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What `15-compilation-model.md` claims, run rather than read.
 *
 * Most of the chapter is the multi-module driver and the incremental build, neither of which is
 * built yet, so what a program can be asked about is `§1`'s layout guarantee, `§2`'s mangling, and
 * `§3`'s split between what is analyzed and what is emitted. All of it held; this file is the
 * probes rather than a finding.
 *
 * `LayoutTests` already covers sizes, alignment and padding. What it does not state is the guarantee
 * those rest on — that the compiler never *reorders* — which is the one a datasheet depends on and
 * the one a packing-minded optimizer would break.
 */
class CompilationModelClaimTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "struct layout is declaration order, and the compiler never reorders" - {

    """a field order that wastes space is emitted as written — packing the i64 first would save
      |eight bytes and is exactly what the guarantee forbids""".stripMargin in {
      ir("struct Wasteful\n    a: u8\n    big: i64\n    b: u8\nvar w = Wasteful(1u8, 2i64, 3u8)\nprint(w.b)") should
        include("%struct.Wasteful = type { i8, i64, i8 }")
    }

    "and a private field is not nameable downstream but is still there, because the ABI needs it" in {
      ir("struct Mixed\n    shown: i32\n    private hidden: i64\nvar m = Mixed(4, 5i64)\nprint(m.shown)") should
        include("%struct.Mixed = type { i32, i64 }")
    }
  }

  "an 'extern' is the exception to mangling and emits the raw C symbol" in {
    val out = ir("extern strlen(s: *u8) -> usize\nvar s = c\"hello\"\nprint(strlen(s))")

    out should include("declare i64 @strlen(ptr)")
    out should include("call i64 @strlen(")
  }

  "reachability decides what is emitted and never what is checked" - {

    "a declaration nothing can arrive at is not written out — nor the extern it would have called" in {
      ir("extern puts(s: *u8) -> i32\nprint(1)") shouldNot include("@puts")
    }

    """but its body is still analyzed, because a mistake is a mistake for what the line says and
      |not for whether the program would have run it""".stripMargin in {
      err("never_called() -> int = \"not an int\"\nprint(1)") should
        include("function 'never_called' should return int, but its body yields string")
    }
  }
}
