package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Value generics (`10 §9`) — a parameter standing for a **value** rather than a type, written
 * `[const N: usize]`, which is what lets one declaration cover every array length.
 */
class ValueGenericsTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "the parameter list" - {
    "reads a value parameter beside a type one" in {
      run("""f[const N: usize, T](xs: [N]T) -> usize = N
            |var a: [3]int = [1, 2, 3]
            |print(f(a))""".stripMargin) shouldBe "3\n"
    }

    // The alternative backtracks instead of committing, so what arrives is the generic 'newline
    // expected' rather than the sentence written for this. A value parameter with no type is a
    // reader who has understood the feature and mis-spelled it, which is the case worth a word.
    "wants the type its argument must have" ignore {
      err("f[const N](xs: [N]int) = 0\nprint(1)") should include("a value parameter needs the type")
    }
  }

  "an array length" - {
    "is inferred from the argument" in {
      run("""len[const N: usize](xs: [N]int) -> usize = N
            |var a: [4]int = [1, 2, 3, 4]
            |print(len(a))""".stripMargin) shouldBe "4\n"
    }

    "is a constant inside the body, so it may be looped to" in {
      run("""total[const N: usize](xs: [N]int) -> int
            |    var t = 0
            |    for i in 0..<N do t = t + xs[i]
            |    t
            |var a: [3]int = [10, 20, 30]
            |print(total(a))""".stripMargin) shouldBe "60\n"
    }

    "makes two lengths two instantiations" in {
      run("""len[const N: usize](xs: [N]int) -> usize = N
            |var a: [2]int = [1, 2]
            |var b: [5]int = [1, 2, 3, 4, 5]
            |print(len(a))
            |print(len(b))""".stripMargin) shouldBe "2\n5\n"
    }
  }

  // A type-argument list reads types, so the `4` is not yet accepted where the argument is written
  // out rather than inferred from a value. A function never hits this, because its arguments are
  // solved from the call (§4) and there is no list to write.
  "a struct may carry one" ignore {
    run("""struct Buf[const N: usize]
          |    data: [N]byte
          |var b = Buf[4](data: [0u8, 0u8, 0u8, 0u8])
          |print(b.data.len)""".stripMargin) shouldBe "4\n"
  }

  "the motivating case" - {
    /** The whole point of the feature: one `impl[const N: usize, T: Display] Display for [N]T` in
     * the library, so a fixed array prints the way every slice already does.
     *
     * It needs the array shape to stop carrying the length — `shapeOwner` files an array under
     * `[3]`, `[4]` precisely because no parameter could stand for one — which is the coherence-side
     * half of this feature and is not built.
     */
    "one impl covers every array length" ignore {
      run("""var a: [3]int = [1, 2, 3]
            |var b: [2]int = [7, 8]
            |print(a)
            |print(b)""".stripMargin) shouldBe "[1, 2, 3]\n[7, 8]\n"
    }
  }

  "what is refused" - {
    // `[N + 1]T` needs the compiler to decide when two *expressions* denote one length — that
    // `N + 1` and `1 + N` are one type — which is type-level arithmetic and a separate feature
    // (`10 §9`). Rust keeps it unstable for the same reason long after const generics shipped.
    // Today it compiles, which is worse than refusing it: the length silently stands at whatever
    // the placeholder made it.
    "arithmetic on a length in a type" ignore {
      err("""f[const N: usize](xs: [N]int) -> [N + 1]int = xs
            |print(1)""".stripMargin) should include("arithmetic")
    }
  }
}
