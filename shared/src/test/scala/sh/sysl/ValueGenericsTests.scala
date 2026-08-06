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

  /** The whole point of the feature: one `impl[const N: usize, T: Display] Display for [N]T` in the
   * library, so a fixed array prints the way every slice already does.
   *
   * It is what the coherence half bought. An array's length used to be part of its shape *key* —
   * `[3]` and `[4]` were two shapes — because no parameter could stand for a length; now it is an
   * argument to one shape, and a block may be generic over it.
   */
  "the motivating case" - {
    "one impl covers every array length" in {
      run("""var a: [3]int = [1, 2, 3]
            |var b: [2]int = [7, 8]
            |print(a)
            |print(b)""".stripMargin) shouldBe "[1, 2, 3]\n[7, 8]\n"
    }

    "reaches an element type the library never saw" in {
      run("""struct P
            |    x: int
            |impl Display for P
            |    display(self, out: *Writer, fmt: FormatSpec) = out.write(str(self.x).bytes)
            |var a: [2]P = [P(1), P(2)]
            |print(a)""".stripMargin) shouldBe "[1, 2]\n"
    }

    // The length reaches the *symbol*, so two lengths are two emitted bodies rather than one
    // compiled at whichever arrived first — the same thing `mangleOne` pins for a function.
    "renders an array of arrays, which is two lengths at once" in {
      run("""var a: [2][3]int = [[1, 2, 3], [4, 5, 6]]
            |print(a)""".stripMargin) shouldBe "[[1, 2, 3], [4, 5, 6]]\n"
    }

    /** A block written for one length is the more specific of the two and answers first, which is
     * `shapeOwners`' ordering — the same rule that makes `[]Point` beat `[]T` (`02 § override`),
     * one level up from where it used to apply.
     *
     * The trait is the program's own because the library's would put this against the orphan rule:
     * `Display` is the library's and nothing in `[2]T` is this module's, so neither block would
     * have a home. That refusal is unchanged by any of this, and `ImplShapeErrorTests` holds it.
     */
    "a block written for one length beats the one written for every length" in {
      run("""trait Tag
            |    tag(self) -> string
            |impl[const N: usize, T] Tag for [N]T
            |    tag(self) -> string = "any"
            |impl[T] Tag for [2]T
            |    tag(self) -> string = "pair"
            |var a: [2]int = [1, 2]
            |var b: [3]int = [1, 2, 3]
            |print(a.tag())
            |print(b.tag())""".stripMargin) shouldBe "pair\nany\n"
    }
  }

  "what is refused" - {
    /** `[N + 1]T` needs the compiler to decide when two *expressions* denote one length — that
     * `N + 1` and `1 + N` are one type — which is type-level arithmetic and a separate feature
     * (`10 §9`). Rust keeps it unstable for the same reason long after const generics shipped.
     *
     * Refusing is not a limitation admitted reluctantly: left alone the length resolves to whatever
     * the placeholder made it, so the array is silently the wrong size.
     */
    "arithmetic on a length in a type" in {
      val e = err("""f[const N: usize](xs: [N]int) -> [N + 1]int = xs
                    |print(1)""".stripMargin)

      e should include("does arithmetic on 'N'")
      e should include("type-level arithmetic")
    }

    "including where the parameter is buried in the expression" in {
      err("""f[const N: usize](xs: [N]int) -> [2 * N]int = xs
            |print(1)""".stripMargin) should include("does arithmetic on 'N'")
    }

    /** The **parameter** position is why this is asked at the declaration rather than only where the
     * type gets built. Nothing unifies with `[N + 1]int`, so a call cannot solve for `N` and says
     * so — true, and about the wrong line. The call still says it, since errors are collected rather
     * than stopped at; what the declaration-time check buys is that the reader meets the cause
     * **first**, at the line that has to change.
     */
    "and in a parameter, which no call could ever be solved against" in {
      val e = err("""f[const N: usize](xs: [N + 1]int) -> usize = 0
                    |var a: [3]int = [1, 2, 3]
                    |print(f(a))""".stripMargin)

      e should include("does arithmetic on 'N'")
      e.indexOf("does arithmetic on 'N'") should be < e.indexOf("annotate the expected type")
    }

    // A member of an `impl` is the other declaration form carrying value parameters, and it is held
    // to the same rule — here in a local's type, which the resolution catches rather than the
    // declaration-time walk.
    "and in a type written inside a member's body" in {
      err("""trait Grow
            |    grow(self) -> usize
            |impl[const N: usize, T] Grow for [N]T
            |    grow(self) -> usize
            |        var b: [N * 2]int
            |        b.len
            |var a: [2]int = [1, 2]
            |print(a.grow())""".stripMargin) should include("does arithmetic on 'N'")
    }

    // And in a local of an ordinary function, which is the same catch one declaration form over.
    "and in a local's type inside a function" in {
      err("""f[const N: usize](xs: [N]int) -> usize
            |    var b: [N + 1]int
            |    b.len
            |var a: [3]int = [1, 2, 3]
            |print(f(a))""".stripMargin) should include("does arithmetic on 'N'")
    }

    /** A length measuring a **type** parameter is a different thing and stays legal: `sizeof(T)` is
     * a number the type argument fixes outright, so nothing has an equation to solve.
     */
    "while a length measuring a type parameter is untouched" in {
      run("""f[T](x: T) -> usize
            |    var buf: [sizeof(T) * 2 + 1]u8
            |    buf.len
            |print(f(1))""".stripMargin) shouldBe "9\n"
    }

    // A body may compute with `N` as freely as with any other `usize` — it is only a *type* that
    // may not carry the result.
    "and a body computes with the parameter freely" in {
      run("""f[const N: usize](xs: [N]int) -> usize = N * 2usize + 1usize
            |var a: [3]int = [1, 2, 3]
            |print(f(a))""".stripMargin) shouldBe "7\n"
    }
  }
}
