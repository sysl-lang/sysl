package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for declarations and their construction: function-call arity, struct fields and
 * construction, enums and their variants, generic type-argument arity and inference, and the
 * conversions between a simple enum and an integer.
 */
class AnalyzerDeclErrorTests extends AnyFreeSpec with CodegenSupport {

  "a call with the wrong number of arguments" in {
    err("add(a: int, b: int) -> int = a + b\nprint(add(1))") should include("takes 2 arguments")
  }

  "reading an unknown struct field" in {
    err("struct P\n    x: int\nvar p = P(1)\nprint(p.y)") should include("no field or property 'y'")
  }

  "reading a field of a non-struct" in {
    err("var x = 5\nprint(x.foo)") should include("cannot read field 'foo'")
  }

  "constructing a struct with the wrong arity" in {
    err("struct P\n    x: int\n    y: int\nvar p = P(1)") should include("has 2 fields")
  }

  "enums" - {
    "a match on a data enum must cover every variant" in {
      val src = "enum Shape\n    Circle(r: int)\n    Empty\nf(s: Shape) -> int\n    s match\n        Empty -> 0"
      err(src) should include("not exhaustive")
      err(src) should include("Circle")
    }

    "constructing a variant with the wrong arity" in {
      err("enum Shape\n    Circle(r: int)\n    Empty\nvar s = Circle(1, 2)") should
        include("has 1 field, but 2 values were given")
    }

    "a nullary variant cannot take arguments" in {
      err("enum Shape\n    Circle(r: int)\n    Empty\nvar s = Empty(1)") should include("takes no arguments")
    }

    "matching an unknown variant" in {
      val src = "enum Shape\n    Circle(r: int)\n    Empty\nf(s: Shape) -> int\n    s match\n        Square(x) -> 0\n        else -> 1"
      err(src) should include("no variant 'Square'")
    }

    "a variant name may not be shared by two enums" in {
      err("enum A\n    X\n    Y\nenum B\n    X\n    Z") should include("already used by enum 'A'")
    }

    "an enum value cannot be printed" in {
      err("enum Color\n    Red\n    Green\nprint(Red)") should include("cannot print")
    }
  }

  "generics" - {
    "the wrong number of type arguments" in {
      err("struct Box[T]\n    value: T\nf(b: Box[int, real])\n    print(1)") should
        include("takes 1 type argument, but 2 type arguments were given")
    }

    "type arguments on a type that takes none" in {
      err("var x: int[real] = 1") should include("does not take type arguments")
    }

    "a type argument that nothing determines" in {
      err("var x = None") should include("cannot infer the type argument")
    }

    "an argument that does not match the instantiated parameter" in {
      err("""pair[T](a: T, b: T) -> T = a
            |var x = pair(1, "two")
            |""".stripMargin) should include("is int, but string was given")
    }

    "a type that contains itself has no finite size" in {
      err("struct Node\n    next: Node\nvar n = Node(n)") should include("contains itself")
    }
  }

  "enum ↔ integer conversion" - {
    "converting a data enum to an integer is rejected" in {
      err(
        """enum Shape
          |    Circle(radius: int)
          |    Empty
          |var s = Circle(3)
          |print(int(s))""".stripMargin
      ) should include("only a simple enum converts to an integer")
    }

    "a checked cast into a data enum is rejected" in {
      err(
        """enum Shape
          |    Circle(radius: int)
          |    Empty
          |var s = Shape(0)""".stripMargin
      ) should include("carries data")
    }

    "a checked cast on a generic enum is rejected" in {
      err(
        """enum Maybe[T]
          |    Just(value: T)
          |    Nothing
          |var m = Maybe(0)""".stripMargin
      ) should include("generic")
    }

    "a checked cast takes exactly one integer argument" in {
      err(
        """enum Color
          |    Red
          |    Green
          |var c = Color(1, 2)""".stripMargin
      ) should include("exactly one integer")
    }

    "a checked cast rejects a non-integer argument" in {
      err(
        """enum Color
          |    Red
          |    Green
          |var c = Color("red")""".stripMargin
      ) should include("converts an integer")
    }

    "the fallible constructor on a data enum is rejected" in {
      err(
        """enum Shape
          |    Circle(radius: int)
          |    Empty
          |var s = Shape.try(0)""".stripMargin
      ) should include("carries data")
    }

    "an unknown member on an enum is still reported, not swallowed by try" in {
      err(
        """enum Color
          |    Red
          |    Green
          |var c = Color.Bogus(1)""".stripMargin
      ) should include("enum 'Color' has no variant or associated function 'Bogus'")
    }

    "an underlying-type annotation on a generic enum is rejected" in {
      err(
        """enum Maybe[T]: u8
          |    Just(value: T)
          |    Nothing""".stripMargin
      ) should include("generic enum cannot pin an underlying type")
    }

    "an underlying-type annotation on a data enum is rejected" in {
      err(
        """enum Shape: u8
          |    Circle(radius: int)
          |    Empty""".stripMargin
      ) should include("only a simple enum has an underlying integer type")
    }

    "a non-integer underlying type is rejected" in {
      err(
        """enum Color: string
          |    Red
          |    Green""".stripMargin
      ) should include("underlying type must be an integer")
    }

    "a discriminant that overflows the underlying type is rejected" in {
      err(
        """enum Color: u8
          |    Red
          |    Big = 300""".stripMargin
      ) should include("does not fit")
    }

    "a negative discriminant under an unsigned underlying type is rejected" in {
      err(
        """enum Color: u8
          |    Red = -1""".stripMargin
      ) should include("does not fit")
    }

    "an auto-incremented discriminant that overflows the underlying type is rejected" in {
      err(
        """enum Small: u2
          |    A = 3
          |    B""".stripMargin
      ) should include("does not fit")
    }
  }
}
