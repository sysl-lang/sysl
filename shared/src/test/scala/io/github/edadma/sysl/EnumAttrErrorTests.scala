package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for a misused enum attribute: an unknown attribute, one asked of a data enum or a
 * generic enum (neither of which has a single set of dataless values to walk), the wrong arity,
 * and an argument of the wrong kind.
 */
class EnumAttrErrorTests extends AnyFreeSpec with CodegenSupport {

  private val Color =
    """|enum Color
       |    Red
       |    Green
       |    Blue
       |""".stripMargin

  "an unknown attribute is rejected" in {
    err(Color + "print(Color::Middle(Color.Red))") should include("has no attribute 'Middle'")
  }

  "a data enum has no simple-enum attributes" in {
    val shape =
      """|enum Shape
         |    Circle(radius: int)
         |    Square(side: int)
         |""".stripMargin
    err(shape + "print(Shape::First)") should include("carries data")
  }

  "a generic enum has no single enum to read" in {
    err("print(Option::First)") should include("generic")
  }

  "the arity is checked" - {
    "First takes no arguments" in {
      err(Color + "print(Color::First(Color.Red))") should include("takes no arguments")
    }
    "Succ takes exactly one" in {
      err(Color + "print(Color::Succ())") should include("takes exactly one argument")
    }
  }

  "the argument kind is checked" - {
    "Val takes a position, not an enum value" in {
      err(Color + "print(Color::Val(Color.Red))") should include("takes a")
    }
    "Value takes a name, not a number" in {
      err(Color + "print(Color::Value(1))") should include("takes a")
    }
    "Succ takes an enum value, not a number" in {
      err(Color + "print(Color::Succ(1))") should include("takes a")
    }
  }
}
