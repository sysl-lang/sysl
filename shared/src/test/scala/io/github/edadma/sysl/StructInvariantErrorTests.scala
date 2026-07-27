package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Compile-time diagnostics for a misused `invariant`: a clause that is not a `bool`, one that
 * names something not in scope, and an invariant on a generic struct (not supported yet).
 */
class StructInvariantErrorTests extends AnyFreeSpec with CodegenSupport {

  "a non-bool invariant is rejected without leaking the synthesised function's name" - {
    "a bare integer field is not a condition" in {
      val e = err("struct Account\n    balance: int\n    invariant balance\nvar a = Account(1)")
      e should include("an 'invariant' must be a 'bool'")
      e should not include "$inv"
    }
    "an arithmetic expression is not a condition" in {
      err("struct Account\n    balance: int\n    invariant balance + 1\nvar a = Account(1)") should
        include("an 'invariant' must be a 'bool'")
    }
  }

  "an invariant that names an unknown field is rejected" in {
    err("struct Account\n    balance: int\n    invariant blance >= 0\nvar a = Account(1)") should include("blance")
  }

  "an invariant on a generic struct is not supported yet" in {
    err("struct Box[T]\n    v: T\n    invariant true\nvar b = Box(1)") should include("generic")
  }
}
