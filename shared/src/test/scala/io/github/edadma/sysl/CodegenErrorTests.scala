package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Programs the compiler must reject, and the diagnostic each produces. As these checks move
 * into a dedicated analyzer, this suite migrates with them.
 */
class CodegenErrorTests extends AnyFreeSpec with CodegenSupport {

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
