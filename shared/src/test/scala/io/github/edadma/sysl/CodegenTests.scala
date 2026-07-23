package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Tier-1 codegen checks: assert on the emitted LLVM IR *text* without running it. The IR is
 * just a string, so these are pure and cross-platform.
 */
class CodegenTests extends AnyFreeSpec with Matchers {

  private def ir(src: String): String =
    Compiler.compileToLlvm(src) match {
      case Right(out) => out
      case Left(err)  => fail(err)
    }

  private def err(src: String): String =
    Compiler.compileToLlvm(src) match {
      case Right(out) => fail(s"expected an error, got:\n$out")
      case Left(e)    => e
    }

  "module scaffolding" - {
    "declares printf and defines main" in {
      val out = ir("print(1)")

      out should include("declare i32 @printf(ptr, ...)")
      out should include("define i32 @main() {")
      out should include("ret i32 0")
    }
  }

  "literals and print" - {
    "a string constant is interned NUL-terminated" in {
      ir("print(\"hi\")") should include("""c"hi\00"""")
    }

    "an int prints via %d" in {
      val out = ir("print(42)")

      out should include("""c"%d\0A\00"""")
      out should include("i32 42")
    }

    "a float is emitted as a hex double and printed via %g" in {
      val out = ir("print(1.5)")

      out should include("double 0x3FF8000000000000")
      out should include("%g")
    }

    "multiple args are space-separated in the format string" in {
      ir("print(1, 2)") should include("""c"%d %d\0A\00"""")
    }
  }

  "arithmetic lowering" - {
    "precedence shows in instruction order — mul before add" in {
      val out = ir("print(1 + 2 * 3)")

      out should include("mul i32 2, 3")
      out should include regex "add i32 1, %t\\d+".r
    }

    "integer division uses signed sdiv" in {
      ir("print(7 / 2)") should include("sdiv i32 7, 2")
    }

    "float arithmetic uses the fadd family" in {
      ir("print(1.5 + 2.5)") should include("fadd double")
    }
  }

  "variables" - {
    "a var lowers to alloca + store, a read to load" in {
      val out = ir("var x = 5\nprint(x)")

      out should include("%x.addr = alloca i32")
      out should include("store i32 5, ptr %x.addr")
      out should include("load i32, ptr %x.addr")
    }

    "reassignment stores again" in {
      ir("var x = 1\nx = 2") should include("store i32 2, ptr %x.addr")
    }
  }

  "control flow" - {
    "while emits cond/body/end labels and a back-edge" in {
      val out = ir("var i = 0\nwhile i < 3\n    i++")

      out should include("while.cond")
      out should include("while.body")
      out should include("while.end")
      out should include("icmp slt i32")
    }

    "if with else emits both arms" in {
      val out = ir("if true\n    print(1)\nelse\n    print(2)")

      out should include("if.then")
      out should include("if.else")
      out should include("if.end")
    }
  }

  "comparison chains lower to anded icmps" in {
    val out = ir("print(1 < 2 && 2 < 3)")

    (out.split("icmp").length - 1) shouldBe 2
    out should include("and i1")
  }

  "errors" - {
    "an undefined name is rejected" in {
      err("print(nope)") should include("undefined name 'nope'")
    }

    "calling something other than print is rejected for now" in {
      err("foo(1)") should include("only the built-in 'print'")
    }

    "mixing int and real in arithmetic is rejected" in {
      err("print(1 + 2.0)") should include("matching types")
    }
  }
}
