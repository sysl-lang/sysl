package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-1: the instruction selection the scalar table drives — width, signedness, and the
 * conversions — asserted on the emitted IR text.
 */
class ScalarCodegenTests extends AnyFreeSpec with CodegenSupport {

  "a declared width becomes the LLVM integer width" in {
    ir("var n: u12 = 7\nprint(n + 1)") should include("add i12")
  }

  "signedness picks between the division pairs" in {
    ir("var u: uint = 9\nprint(u / 2)") should include("udiv i32")
    ir("var s: int = 9\nprint(s / 2)") should include("sdiv i32")
  }

  "signedness picks between the right shifts" in {
    ir("var u: byte = 9\nprint(u >> 1)") should include("lshr i8")
    ir("var s: i8 = 9\nprint(s >> 1)") should include("ashr i8")
  }

  "an unsigned comparison uses the unsigned predicate" in {
    ir("var u: uint = 9\nprint(u < 10)") should include("icmp ult i32")
  }

  "conversions" - {
    "a narrower target truncates" in {
      ir("var n: int = 9\nprint(byte(n))") should include("trunc i32")
    }

    "a wider target extends by the source's signedness" in {
      ir("var n: i8 = 9\nprint(i32(n))") should include("sext i8")
      ir("var n: byte = 9\nprint(u32(n))") should include("zext i8")
    }

    "between integer and float" in {
      ir("var n: int = 9\nprint(real(n))") should include("sitofp i32")
      ir("var n: uint = 9\nprint(real(n))") should include("uitofp i32")
    }

    // Float-to-integer saturates through the LLVM intrinsic (defined on every target), rather
    // than a bare fptosi/fptoui whose out-of-range result is poison. Each is declared once and
    // called; the target's signedness picks fptosi.sat vs fptoui.sat.
    "float to integer uses the saturating intrinsic, not a bare cast" in {
      val signed = ir("print(int(1.5))")
      signed should include("declare i32 @llvm.fptosi.sat.i32.f64(double)")
      signed should include("call i32 @llvm.fptosi.sat.i32.f64(double")
      signed should not include "fptosi double"

      val unsigned = ir("print(u8(1.5))")
      unsigned should include("declare i8 @llvm.fptoui.sat.i8.f64(double)")
      unsigned should include("call i8 @llvm.fptoui.sat.i8.f64(double")
    }

    "a checked char conversion traps on a value that is not a scalar value" in {
      val out = ir("var n: int = 65\nprint(char(n))")

      out should include("declare void @llvm.trap()")
      out should include("call void @llvm.trap()")
    }
  }

  "a narrower float constant is the double rounded down to it" in {
    ir("var f: f32 = 1.5\nprint(f)") should include("fptrunc double 0x3FF8000000000000 to float")
  }

  "printing widens to what varargs promote to" in {
    ir("var b: byte = 9\nprint(b)") should include("%u")
    ir("var n: long = 9\nprint(n)") should include("%lld")
    ir("var u: ulong = 9\nprint(u)") should include("%llu")
  }

  "printing a char encodes it as UTF-8" in {
    ir("print('A')") should include("@sysl.utf8")
  }

  "stack slots are hoisted into the entry block" in {
    val out = ir("for i in 1..3\n    var x = i * 2\n    print(x)")

    out should include("entry:\n  %i.addr = alloca i32\n  %x.addr = alloca i32")
  }
}
