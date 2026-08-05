package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for types: plain mismatches, the scalar widths, the memory modes (`*T` / `&T`),
 * and the aggregate types — arrays, slices, strings — along with `str` and format specifiers.
 */
class AnalyzerTypeErrorTests extends AnyFreeSpec with CodegenSupport {

  "an argument of the wrong type" in {
    err("f(x: int) -> int = x\nprint(f(1.5))") should include("is int, but real was given")
  }

  "a return value of the wrong type" in {
    err("f() -> int\n    return 1.5") should include("return type mismatch")
  }

  "an unknown type in a signature" in {
    err("f(x: Widget) -> int = 0") should include("unknown type 'Widget'")
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

    // `i128` is lowered — see `WideIntegerTests` — so what is left here is the integer width past
    // where the back end stops and the float width the chapters promise and this compiler has not
    // built. The two say different things, and neither should read like the other.
    //
    // The integer ceiling is LLVM's own `2^23 - 1`, so what is refused is a width past *that*, not
    // the 128 this once stopped at.
    "an unsupported width is named as such" in {
      err("var x: i8388608 = 1") should include("wider than the 8388607 bits")
      err("var x: f128 = 1.0") should include("'f128' is not lowered yet")
    }

    // A width so large it is not a number the compiler can hold at all, which is a different
    // complaint from one it can hold and will not lower.
    "a width beyond counting is its own complaint" in {
      err("var x: i99999999999999999999 = 1") should include("far wider than anything can hold")
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

  "memory modes" - {
    "a cycle through a value field still has no finite size" in {
      err("struct A\n    b: B\nstruct B\n    a: A\nvar x = A(B(x))") should include("contains itself")
    }

    "a cycle is fine as soon as one edge is an indirection" in {
      ir("struct A\n    b: B\nstruct B\n    a: *A\nvar b = B(null)\nvar a = A(b)") should
        include("%struct.A = type { %struct.B }")
    }

    "'&' needs something with an address" in {
      err("print(*(&(1 + 2)))") should include("needs a variable, a field, an element, or a dereference")
      err("f() -> int = 1\nvar p = &f()") should include("needs a variable, a field, an element, or a dereference")
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

    // Distinct in both directions, and the complaint says why rather than reporting two unrelated
    // type names: what a reader wants to know is that atomicity is fixed at the allocation (`06`).
    "the two reference modes are distinct types" in {
      err("f(p: &int) -> &int = p\ng(p: &sync int) -> &int = f(p)") should
        include("'&int' and '&sync int' are distinct types, and neither converts to the other")

      err("f(p: &sync int) -> int = 1\ng(p: &int) -> int = f(p)") should
        include("Allocate int as a '&sync int' where it is constructed")
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

  "arrays" - {
    // A `const` may be a bound (`13 §7`); a `var` is a value that exists at run time, and a length
    // is not.
    "have a length settled before the program runs, not while it does" in {
      err("var n = 4\nvar a: [n]int\nprint(a[0])") should include("must be a constant")
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

    "have a length, their bytes and their characters, and no other field yet" in {
      err("""print("ab".graphemes)""") should include("cannot read field 'graphemes'")
    }

    "are not a []u8 where one was asked for" in {
      err("""f(b: []u8) -> usize = b.len
            |print(f("ab"))""".stripMargin) should include("is []byte, but string was given")
    }

    // `+` is the one string operator, so the others are rejected the way they are for any type
    // that does not define them.
    "join with '+' only, not the other arithmetic operators" in {
      err("""print("a" - "b")""") should include("operator '-' is not defined for string")
      err("""print("a" * "b")""") should include("operator '*' is not defined for string")
    }

    // `+` is deliberately strict: mixing a string with a number is a type error asking for
    // interpolation, not a silent `str()` of the other operand — in either order.
    "do not coerce a number across '+'" in {
      err("""print("n=" + 5)""") should include("'+' needs matching types, got string and int")
      err("""print(5 + "n")""") should include("'+' needs matching types, got int and string")
    }

    "need a string on the right of '+=' too" in {
      err("""var s = "a"
            |s += 5""".stripMargin) should include("'+' needs matching types, got string and int")
    }
  }

  "str" - {
    // `str` renders a primitive value; a type that has no one string form has to wait for a
    // `Display` trait, so asking now is an error rather than a guess.
    "will not render a struct, an enum, or a container" in {
      err("""struct P
            |    x: int
            |end P
            |print(str(P(1)))""".stripMargin) should include("cannot make a string of a P value")
      err("""enum E
            |    A
            |    B
            |end E
            |print(str(A))""".stripMargin) should include("cannot make a string of a")
      err("""var a = [1, 2, 3]
            |print(str(a))""".stripMargin) should include("cannot make a string of a [3]int value")
    }

    "will not render a reference or a raw pointer" in {
      err("""var r: &int = 5
            |print(str(r))""".stripMargin) should include("cannot make a string of a")
      err("""var n = 5
            |var p = &n
            |print(str(p))""".stripMargin) should include("cannot make a string of a")
    }

    "will not render a unit value" in {
      err("print(str(print(1)))") should include("cannot make a string of a unit value")
    }

    "takes exactly one value" in {
      err("""print(str(1, 2))""") should include("str takes exactly one value")
      err("""print(str())""") should include("str takes exactly one value")
    }
  }

  "format specifiers" - {
    // An f-string specifier is checked against the value's type: a numeric conversion needs a
    // number, a string conversion a string.
    "an integer conversion rejects a non-integer" in {
      err("""var s = "hi"
            |print(f"${s}%d")""".stripMargin) should include(
        "format '%d' expects an integer, but the value has type string",
      )
      err("""print(f"${3.5}%x")""") should include("format '%x' expects an integer")
    }

    "a float conversion rejects a non-float" in {
      err("""print(f"${5}%f")""") should include("format '%f' expects a float, but the value has type int")
    }

    "a string conversion rejects a non-string" in {
      err("""print(f"${5}%s")""") should include("format '%s' expects a string, but the value has type int")
    }
  }
}
