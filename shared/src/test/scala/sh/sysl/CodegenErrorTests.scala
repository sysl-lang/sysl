package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Programs the analyzer must reject, and the diagnostic each produces. */
class CodegenErrorTests extends AnyFreeSpec with CodegenSupport {

  "an undefined name is rejected" in {
    err("print(nope)") should include("undefined name 'nope'")
  }

  "calling an undefined function is rejected" in {
    err("foo(1)") should include("undefined function 'foo'")
  }

  "mixing int and real in arithmetic is rejected" in {
    err("print(1 + 2.0)") should include("matching types")
  }
}
