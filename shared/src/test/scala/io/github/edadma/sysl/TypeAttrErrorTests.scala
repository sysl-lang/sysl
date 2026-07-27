package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for a misused type attribute: an unknown attribute, one that needs a range on a
 * type that has none, the wrong arity, `::` on something that is not a type, and `Range` outside a
 * `for` loop.
 */
class TypeAttrErrorTests extends AnyFreeSpec with CodegenSupport {

  private val Age = "type Age = int within 0..150\n"

  "an unknown attribute is rejected" in {
    err(Age + "print(Age::Middle)") should include("has no attribute 'Middle'")
  }

  "an attribute needing a range on a predicate-only type is rejected" in {
    err("type Even = int where value % 2 == 0\nprint(Even::First)") should include("needs a 'within' range")
  }

  "a bounded attribute rejects the wrong arity" - {
    "First takes no arguments" in {
      err(Age + "print(Age::First(1))") should include("takes no arguments")
    }
    "Succ takes exactly one" in {
      err(Age + "print(Age::Succ())") should include("takes exactly one argument")
    }
  }

  "`::` on something that is not a type is rejected" in {
    err("var x = 3\nprint(x::First)") should include("left side must be a type name")
  }

  "Range outside a for loop is rejected" in {
    err(Age + "print(Age::Range)") should include("only meaningful as the iterable of a 'for' loop")
  }
}
