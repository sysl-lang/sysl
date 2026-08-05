package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `[v; n]` — an array of `n` copies of one value (`07`).
 *
 * The form exists for the arrays a container has to make for itself. An `enum` has no zero value, so
 * `var xs: [16]Slot` is refused and the alternative was sixteen elements written out — which is what
 * `guide/hashmap`'s empty block was. The two properties worth pinning are that the count is a
 * compile-time constant, exactly as an array bound is, and that the value is evaluated **once**: a
 * repeat is a copy of one result, not `n` requests for one.
 */
class ArrayFillTests extends AnyFreeSpec with CodegenSupport with RunSupport with ParseSupport {

  "the form parses" - {
    "as a value and a count" in {
      prog("var xs = [0; 4]") shouldBe List(VarDecl("xs", None, Some(ArrayFill(i(0), i(4)))))
    }

    // The two bracket forms separate on the token after the first expression, so neither has to be
    // written differently to stay unambiguous.
    "without disturbing the element list beside it" in {
      prog("var xs = [1, 2, 3]") shouldBe List(VarDecl("xs", None, Some(ArrayLit(List(i(1), i(2), i(3))))))
    }

    "or the empty literal" in {
      prog("var xs: [0]int = []") shouldBe
        List(VarDecl("xs", Some(ArrayType(Some(i(0)), NamedType("int", Nil))), Some(ArrayLit(Nil))))
    }

    "and the value may be any expression" in {
      prog("var xs = [f(1); 2]") shouldBe
        List(VarDecl("xs", None, Some(ArrayFill(Call(Ident("f"), List(i(1))), i(2)))))
    }
  }

  "it fills" - {
    "every element with the value" in {
      run("var xs = [7; 4]\nprint(str(xs[0] + xs[1] + xs[2] + xs[3]))") shouldBe "28\n"
    }

    "to the length the count gives" in {
      run("var xs = [0u8; 6]\nprint(str(xs.len))") shouldBe "6\n"
    }

    "taking the element type from the value" in {
      run("var xs = [3u8; 2]\nvar t: u8 = xs[0] + xs[1]\nprint(str(t))") shouldBe "6\n"
    }

    "or from the context, where the value is a bare literal" in {
      run("var xs: [3]u8 = [1; 3]\nprint(str(xs[2]))") shouldBe "1\n"
    }

    "with a count a 'const' names" in {
      run("const n: usize = 5\nvar xs = [2; n]\nprint(str(xs.len))\nprint(str(xs[4]))") shouldBe "5\n2\n"
    }

    "with a count that is a constant expression" in {
      run("const n: usize = 3\nvar xs = [1; n * 2]\nprint(str(xs.len))") shouldBe "6\n"
    }

    "a value of a type that has no zero" in {
      run(
        """enum Slot
          |    Empty
          |    Full(v: int)
          |end Slot
          |var xs: [4]Slot = [Empty; 4]
          |var n = 0
          |for x in xs
          |    x match
          |        Empty -> n += 1
          |        Full(v) -> n += v
          |print(str(n))""".stripMargin,
      ) shouldBe "4\n"
    }

    "a struct value" in {
      run(
        """struct P
          |    x: int
          |    y: int
          |end P
          |var ps = [P(2, 3); 3]
          |print(str(ps[0].x + ps[1].y + ps[2].x))""".stripMargin,
      ) shouldBe "7\n"
    }

    "an array, nested" in {
      run("var g = [[5; 3]; 2]\nprint(str(g[1][2]))") shouldBe "5\n"
    }

    "and each element is its own storage, not a shared one" in {
      run("var xs = [1; 3]\nxs[1] = 9\nprint(str(xs[0] + xs[1] + xs[2]))") shouldBe "11\n"
    }

    "including a count of zero, which is an empty array" in {
      run("var xs = [1; 0]\nprint(str(xs.len))") shouldBe "0\n"
    }
  }

  // The property that makes this a language feature rather than shorthand for writing the value out
  // n times: one evaluation, n copies. Written `[tick(); 3]`, three calls would print three lines.
  "the value is evaluated once" - {
    "however many elements it fills" in {
      run(
        """tick() -> int
          |    print("call")
          |    1
          |var xs = [tick(); 3]
          |print(str(xs[0] + xs[1] + xs[2]))""".stripMargin,
      ) shouldBe "call\n3\n"
    }

    "and never, where the array is empty" in {
      run(
        """tick() -> int
          |    print("call")
          |    1
          |var xs = [tick(); 0]
          |print(str(xs.len))""".stripMargin,
      ) shouldBe "call\n0\n"
    }
  }

  // A repeat count is where a large array gets written on purpose, so the fill is a loop rather than
  // an unrolled chain of inserts — the same reasoning the ARC walk over an array records.
  "it is built with a loop" in {
    val out = irMain("var xs = [0u8; 64]\nprint(str(xs.len))")

    out should include("fill.test")
    out should include("fill.elem")
    out should include("fill.done")
  }

  "what it refuses" - {
    "a count that is not a constant" in {
      err("f() -> usize = 3usize\nvar xs = [0; f()]") should include("must be a constant")
    }

    "a count read from a variable" in {
      err("var n: usize = 3usize\nvar xs = [0; n]") should include("must be a constant")
    }

    "a negative count" in {
      err("var xs = [0; -2]") should include("elements")
    }

    "a unit value" in {
      err("var xs = [print(1); 3]") should include("cannot hold")
    }

    "and a count that does not fold to an integer" in {
      err("var xs = [0; \"three\"]") should include("must be a constant")
    }
  }
}
