package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Codegen of the module scaffolding and the `print` builtin: the fixed preamble, string
 * interning, and per-type format specifiers.
 */
class CodegenModuleTests extends AnyFreeSpec with CodegenSupport {

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
}
