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

  "enums" - {
    "a match on a data enum must cover every variant" in {
      val src = "enum Shape\n    Circle(r: int)\n    Empty\nf(s: Shape) -> int\n    match s\n        Empty -> 0"
      err(src) should include("not exhaustive")
      err(src) should include("Circle")
    }

    "constructing a variant with the wrong arity" in {
      err("enum Shape\n    Circle(r: int)\n    Empty\nvar s = Circle(1, 2)") should include("has 1 fields")
    }

    "a nullary variant cannot take arguments" in {
      err("enum Shape\n    Circle(r: int)\n    Empty\nvar s = Empty(1)") should include("takes no arguments")
    }

    "matching an unknown variant" in {
      val src = "enum Shape\n    Circle(r: int)\n    Empty\nf(s: Shape) -> int\n    match s\n        Square(x) -> 0\n        else -> 1"
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
        include("takes 1 type arguments")
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

  "scalar types" - {
    "a literal too large for the width it landed in" in {
      err("var x: byte = 300") should include("does not fit byte")
      err("print(5000000000)") should include("does not fit int")
    }

    "an integer literal does not quietly become a float" in {
      err("var x: real = 1") should include("declared real but the value is int")
    }

    "mixed widths need a conversion rather than promoting" in {
      err("var a: byte = 1\nvar b: int = 2\nprint(a + b)") should
        include("'+' needs matching types, got byte and int")
    }

    "an unsupported width is named as such" in {
      err("var x: i128 = 1") should include("wider than the 64 bits")
      err("var x: f128 = 1.0") should include("'f128' is not lowered yet")
    }

    "char has no arithmetic" in {
      err("print('a' + 'b')") should include("'+' is not defined for char")
      err("print('a' + 1)") should include("needs matching types, got char and int")
    }

    "a conversion that has no meaning" in {
      err("print(int(true))") should include("cannot convert bool to int")
      err("print(char('a'), char(1, 2))") should include("takes exactly one value")
    }

    "unary minus needs a type that has a sign" in {
      err("var b: byte = 1\nprint(-b)") should include("unsigned type byte")
    }

    "a built-in type name may not be redeclared" in {
      err("struct byte\n    x: int") should include("already declared")
    }
  }

  "the ? operator" - {
    "needs an Option or Result value" in {
      err("f(n: int) -> Option[int]\n    var x = n?\n    None") should
        include("'?' needs an Option or Result value")
    }

    "may not be used in a function returning something else" in {
      err("""g() -> Option[int] = None
            |f() -> int
            |    var x = g()?
            |    x
            |end f
            |""".stripMargin) should include("may only be used in a function returning Option")
    }

    "may not propagate an error the function does not return" in {
      err("""g() -> Result[int, string] = Ok(1)
            |f() -> Result[int, bool]
            |    var x = g()?
            |    Ok(x)
            |end f
            |""".stripMargin) should include("propagates a string error")
    }
  }
}
