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
    err("struct P\n    x: int\nvar p = P(1)\nprint(p.y)") should include("no field or property 'y'")
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

  "memory modes" - {
    "a cycle through a value field still has no finite size" in {
      err("struct A\n    b: B\nstruct B\n    a: A\nvar x = A(B(x))") should include("contains itself")
    }

    "a cycle is fine as soon as one edge is an indirection" in {
      ir("struct A\n    b: B\nstruct B\n    a: *A\nvar b = B(null)\nvar a = A(b)") should
        include("%struct.A = type { %struct.B }")
    }

    "'&' needs something with an address" in {
      err("print(*(&(1 + 2)))") should include("needs a variable, a field, or a dereference")
      err("f() -> int = 1\nvar p = &f()") should include("needs a variable, a field, or a dereference")
    }

    "'*' needs a pointer or a reference" in {
      err("var n = 1\nprint(*n)") should include("'*' needs a pointer or a reference, not int")
    }

    "assigning through a pointer checks the pointee type" in {
      err("var n = 1\nvar p = &n\n*p = 1.5") should include("cannot assign real")
    }

    "a pointer cannot be printed" in {
      err("var n = 1\nprint(&n)") should include("cannot print a *int value")
    }

    "null must know which pointer it is" in {
      err("var p = null") should include("takes its type from its context")
      err("var n: int = null") should include("'null' is a raw pointer")
    }

    "a pointer has equality but no ordering" in {
      err("var n = 1\nvar p = &n\nprint(p < p)") should include("'<' is not defined for *int")
    }

    "the two reference modes are distinct types" in {
      err("f(p: &int) -> &int = p\ng(p: &sync int) -> &int = f(p)") should
        include("is &int, but &sync int was given")
    }
  }

  "references" - {
    "a reference is never null, so an absent one is an Option" in {
      err("var p: &int = null") should include("Option[&int]")
    }

    "a value of an unrelated type is still a mismatch, not a box" in {
      err("struct P\n    x: int\nvar p: &P = 5") should include("declared &P but the value is int")
    }

    "a reference cannot be printed" in {
      err("struct P\n    x: int\nvar p: &P = P(1)\nprint(p)") should include("cannot print a &P value")
    }

    "references have equality but no ordering" in {
      val src = "struct P\n    x: int\nvar a: &P = P(1)\nvar b: &P = P(2)\nprint(a < b)"

      err(src) should include("'<' is not defined for &P")
    }

    "a reference is not a raw pointer" in {
      err("f(p: *int)\n    print(1)\ng(r: &int)\n    f(r)") should include("is *int, but &int was given")
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

  "arrays" - {
    "have a length that is written down, not computed" in {
      err("var n = 4\nvar a: [n]int\nprint(a[0])") should include("must be an integer literal")
    }

    "hold one element type" in {
      err("var a = [1, 2.5]") should include("needs one element type")
    }

    "cannot be empty without a context to take an element type from" in {
      err("var a = []") should include("takes its element type from its context")
    }

    "cannot hold themselves by value" in {
      err("struct Tree\n    kids: [4]Tree\nvar t: Tree\n") should include("contains itself")
    }

    "index with an integer" in {
      err("var a = [1, 2]\nprint(a[true])") should include("index must be an integer")
    }

    "are not indexable when they are not sequences" in {
      err("var n = 1\nprint(n[0])") should include("cannot index int")
    }

    "have no field but their length" in {
      err("var a = [1, 2]\nprint(a.size)") should include("cannot read field 'size'")
    }

    "are not printable, since printing is scalars only" in {
      err("var a = [1, 2]\nprint(a)") should include("cannot print a [2]int value")
    }

    "have no zero value when their elements have none" in {
      err("struct P\n    x: int\nvar a: [2]&P") should include("has no zero value")
    }

    "need one or the other — a type, or something to infer it from" in {
      err("var a") should include("needs either a type or an initial value")
    }
  }

  "slices" - {
    "cannot carry an owner whose count is atomic" in {
      err("var b: &sync [4]int = [1, 2, 3, 4]\nvar s = b[..]") should include("'&sync' array cannot be sliced")
    }

    "are written with '..' when the high end is left off" in {
      err("var b: &[4]int = [1, 2, 3, 4]\nvar s = b[1..<]") should include("written 'a[lo..]'")
    }

    "have integer bounds" in {
      err("var b: &[4]int = [1, 2, 3, 4]\nvar s = b[true..<2]") should include("bound must be an integer")
    }

    "are taken of something with elements" in {
      err("var n = 1\nvar s = n[..]") should include("cannot slice int")
    }

    "do not accept an array where a view was asked for" in {
      err("f(s: []int) -> usize = s.len\nvar a = [1, 2]\nprint(f(a))") should include("is []int, but [2]int was given")
    }
  }

  "strings" - {
    "cannot be written through, since a string is immutable" in {
      err("""var s = "ab"
            |s[0] = 65""".stripMargin) should include("a string is immutable")
    }

    "cannot have their bytes pointed at either" in {
      err("""var s = "ab"
            |var p = &s[0]""".stripMargin) should include("a string is immutable")
    }

    "say which granularity a loop wants rather than choosing one" in {
      err("""var s = "ab"
            |for c in s do print(c)""".stripMargin) should include("iterated as 's.bytes'")
    }

    "have a length and their bytes, and no other field yet" in {
      err("""print("ab".chars)""") should include("cannot read field 'chars'")
    }

    "are not a []u8 where one was asked for" in {
      err("""f(b: []u8) -> usize = b.len
            |print(f("ab"))""".stripMargin) should include("is []byte, but string was given")
    }

    "do not concatenate yet, since that would allocate" in {
      err("""print("a" + "b")""") should include("'+' is not defined for string")
    }
  }

  "methods" - {
    "a '&self' method rejects a bare stack value" in {
      err("struct C\n    n: int\n    bump(&self)\n        self.n += 1\nvar c = C(0)\nc.bump()") should
        include("'&self' needs a reference")
    }

    "a property is read without parentheses" in {
      err("struct P\n    x: int\n    twice -> int = self.x * 2\nvar p = P(1)\nprint(p.twice())") should
        include("is a property")
    }

    "a method is called with parentheses" in {
      err("struct P\n    x: int\n    twice(self) -> int = self.x * 2\nvar p = P(1)\nprint(p.twice)") should
        include("is a method")
    }

    "an unknown method is reported against its type" in {
      err("struct P\n    x: int\nvar p = P(1)\nprint(p.area())") should include("no method 'area'")
    }

    "an unknown associated function is reported against its type" in {
      err("struct P\n    x: int\n    id(self) -> int = self.x\nvar p = P.make()") should
        include("no associated function 'make'")
    }

    "a member may not share a name with a field" in {
      err("struct P\n    x: int\n    x(self) -> int = 1") should include("both a field and a member")
    }

    "a member on a generic type waits on the generics work" in {
      err("struct Box[T]\n    value: T\n    get(self) -> T = self.value") should
        include("members of a generic type are not supported yet")
    }
  }
}
