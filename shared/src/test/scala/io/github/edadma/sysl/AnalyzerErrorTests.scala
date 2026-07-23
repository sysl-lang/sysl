package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for the declaration-level features — functions, structs, and `match` — that
 * the analyzer must reject.
 */
class AnalyzerErrorTests extends AnyFreeSpec with CodegenSupport {

  "a call with the wrong number of arguments" in {
    err("add(a: int, b: int) -> int = a + b\nprint(add(1))") should include("takes 2 arguments")
  }

  "an argument of the wrong type" in {
    err("f(x: int) -> int = x\nprint(f(1.5))") should include("is int, but real was given")
  }

  "a return value of the wrong type" in {
    err("f() -> int\n    return 1.5") should include("return type mismatch")
  }

  "reading an unknown struct field" in {
    err("struct P\n    x: int\nvar p = P(1)\nprint(p.y)") should include("no field 'y'")
  }

  "reading a field of a non-struct" in {
    err("var x = 5\nprint(x.foo)") should include("cannot read field 'foo'")
  }

  "constructing a struct with the wrong arity" in {
    err("struct P\n    x: int\n    y: int\nvar p = P(1)") should include("has 2 fields")
  }

  "a value match must be exhaustive" in {
    err("var x = match 1\n    1 -> 10\n    2 -> 20") should include("exhaustive")
  }

  "an unknown type in a signature" in {
    err("f(x: Widget) -> int = 0") should include("unknown type 'Widget'")
  }
}
