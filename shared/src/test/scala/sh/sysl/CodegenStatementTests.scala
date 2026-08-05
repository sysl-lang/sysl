package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Codegen of statements: variable storage and assignment. */
class CodegenStatementTests extends AnyFreeSpec with CodegenSupport {

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

    "a compound assignment loads, operates, and stores back" in {
      val out = ir("var x = 10\nx += 5")

      out should include("load i32, ptr %x.addr")
      out should include("add i32")
      out should include("store i32")
    }
  }
}
