package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2: vectors as programs actually behave, compiled and run.
 *
 * **The whole point of running these rather than reading the IR is that LLVM legalizes.** A `<4>f32`
 * is one register on this machine and would be four scalars on a Cortex-M, and a suite that asserted
 * on the instructions would be asserting on which of those happened. What a program is owed is the
 * arithmetic, and that is the same either way — so the numbers are the test and `VectorCodegenTests`
 * covers the shape of what is emitted.
 */
class VectorRunTests extends AnyFreeSpec with RunSupport {

  "a literal fills the lanes in order" in {
    run("val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]\nprint(v[0], v[3])") shouldBe "1 4\n"
  }

  "the lanes add" in {
    val src =
      """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |val b: <4>f32 = [10.0, 20.0, 30.0, 40.0]
        |val c = a + b
        |print(c[0], c[1], c[2], c[3])
        |""".stripMargin

    run(src) shouldBe "11 22 33 44\n"
  }

  "a scalar splats into every lane" in {
    val src =
      """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |val c = a * 2.0
        |print(c[0], c[3])
        |""".stripMargin

    run(src) shouldBe "2 8\n"
  }

  // The splat reads the same either way round, which is worth pinning because the two go through
  // different arms of `balanceLanes` and only one of them was written first.
  "a scalar splats on the left as well" in {
    val src =
      """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |val c = 10.0 - a
        |print(c[0], c[3])
        |""".stripMargin

    run(src) shouldBe "9 6\n"
  }

  "a declaration with no initializer is every lane zero" in {
    run("var v: <4>f32\nprint(v[0], v[3], v.len)") shouldBe "0 0 4\n"
  }

  "the length is the lane count and is a constant" in {
    run("val v: <8>i32 = [1, 2, 3, 4, 5, 6, 7, 8]\nprint(v.len)") shouldBe "8\n"
  }

  "integer lanes add and shift" in {
    val src =
      """val a: <4>i32 = [1, 2, 3, 4]
        |val b = (a + a) << 1
        |print(b[0], b[3])
        |""".stripMargin

    run(src) shouldBe "4 16\n"
  }

  "unary minus negates every lane" in {
    val src =
      """val a: <4>f32 = [1.0, -2.0, 3.0, -4.0]
        |val b = -a
        |print(b[0], b[1])
        |""".stripMargin

    run(src) shouldBe "-1 2\n"
  }

  // --- masks ---------------------------------------------------------------------------

  "a comparison yields a mask, and select chooses lane by lane" in {
    val src =
      """val a: <4>f32 = [1.0, 5.0, 3.0, 7.0]
        |val b: <4>f32 = [4.0, 2.0, 6.0, 0.0]
        |val lo = (a < b).select(a, b)
        |print(lo[0], lo[1], lo[2], lo[3])
        |""".stripMargin

    run(src) shouldBe "1 2 3 0\n"
  }

  // **`print` drops a trailing `.0`, so most of the expectations above read like integers** — which
  // is sysl's existing float formatting (`ScalarRunTests` pins `f32 1.5 * 2.0` printing as `3`) and
  // not anything about vectors. This one keeps a fraction all the way through, so that the suite
  // says somewhere that these really are floats and that the lanes divide rather than truncating.
  "the lanes really are floats" in {
    val src =
      """val a: <4>f32 = [1.0, 2.0, 3.0, 7.0]
        |val b = a / 2.0
        |print(b[0], b[1], b[3], b.sum())
        |""".stripMargin

    run(src) shouldBe "0.5 1 3.5 6.5\n"
  }

  "a mask reduces with any and all" in {
    val src =
      """val a: <4>f32 = [1.0, 5.0, 3.0, 7.0]
        |val b: <4>f32 = [4.0, 2.0, 6.0, 0.0]
        |print((a < b).any(), (a < b).all())
        |""".stripMargin

    run(src) shouldBe "true false\n"
  }

  "two masks combine with the bitwise operators" in {
    val src =
      """val a: <4>i32 = [1, 2, 3, 4]
        |val m = (a > 1) & (a < 4)
        |print(m.any(), m.all())
        |""".stripMargin

    run(src) shouldBe "true false\n"
  }

  "a mask compares against a splatted scalar" in {
    val src =
      """val a: <4>f32 = [1.0, 5.0, 3.0, 7.0]
        |val clamped = (a > 4.0).select(4.0, a)
        |print(clamped[0], clamped[1], clamped[3])
        |""".stripMargin

    run(src) shouldBe "1 4 4\n"
  }

  // --- reductions ----------------------------------------------------------------------

  "a float sum adds the lanes" in {
    run("val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]\nprint(v.sum())") shouldBe "10\n"
  }

  "an integer sum adds the lanes" in {
    run("val v: <4>i32 = [1, 2, 3, 4]\nprint(v.sum())") shouldBe "10\n"
  }

  "min and max pick the extreme lane" in {
    val src =
      """val v: <4>f32 = [3.0, 1.0, 4.0, 1.5]
        |print(v.min(), v.max())
        |""".stripMargin

    run(src) shouldBe "1 4\n"
  }

  "an unsigned minimum does not read its lanes as signed" in {
    val src =
      """val v: <4>u32 = [1, 2, 3, 4294967295]
        |print(v.max())
        |""".stripMargin

    run(src) shouldBe "4294967295\n"
  }

  "a dot product is a multiply and a sum" in {
    val src =
      """dot(a: <4>f32, b: <4>f32) -> f32 = (a * b).sum()
        |
        |val x: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |val y: <4>f32 = [4.0, 3.0, 2.0, 1.0]
        |print(dot(x, y))
        |""".stripMargin

    run(src) shouldBe "20\n"
  }

  // --- the payoff ----------------------------------------------------------------------

  // **This is the case the whole feature exists for.** One kernel, instantiated at two widths from
  // the arguments alone — no width written at either call, and two bodies holding different
  // instructions. Nothing else in the language lets a hot loop be written once and compiled for
  // whatever register the machine has.
  "one kernel is instantiated per width, read off the argument" in {
    val src =
      """scale[const W: usize](v: <W>f32, by: f32) -> <W>f32 = v * by
        |
        |val four: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |val eight: <8>f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
        |val a = scale(four, 2.0)
        |val b = scale(eight, 3.0)
        |print(a[3], b[7], a.len, b.len)
        |""".stripMargin

    run(src) shouldBe "8 24 4 8\n"
  }

  "a generic kernel reduces at whatever width it was given" in {
    val src =
      """total[const W: usize](v: <W>f32) -> f32 = v.sum()
        |
        |val four: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |val two: <2>f32 = [10.0, 20.0]
        |print(total(four), total(two))
        |""".stripMargin

    run(src) shouldBe "10 30\n"
  }

  // The mask's width follows the vector's through the instantiation, which is the part that would
  // break silently: a kernel whose comparison produced a fixed-width mask would compile at one
  // width and miscompile at the other.
  "a generic kernel's mask follows its width" in {
    val src =
      """clamp_low[const W: usize](v: <W>f32, lo: f32) -> <W>f32 = (v < lo).select(lo, v)
        |
        |val four: <4>f32 = [1.0, -2.0, 3.0, -4.0]
        |val two: <2>f32 = [-1.0, 5.0]
        |val a = clamp_low(four, 0.0)
        |val b = clamp_low(two, 0.0)
        |print(a[1], a[2], b[0], b[1])
        |""".stripMargin

    run(src) shouldBe "0 3 0 5\n"
  }

  // --- lanes that are not f32 ----------------------------------------------------------

  "a narrow lane keeps its width" in {
    val src =
      """val a: <8>u8 = [1, 2, 3, 4, 5, 6, 7, 8]
        |val b = a + a
        |print(b[7], b.sum())
        |""".stripMargin

    run(src) shouldBe "16 72\n"
  }

  // A `u8` sum wraps at the lane width, which is the same rule scalar arithmetic follows and is
  // worth pinning: nothing about a reduction promotes.
  "an integer sum wraps at the lane width" in {
    val src =
      """val a: <4>u8 = [200, 200, 200, 200]
        |print(a.sum())
        |""".stripMargin

    run(src) shouldBe "32\n"
  }

  "a wide vector is legal and lowers to whatever the machine has" in {
    val src =
      """val a: <16>f32 = [1.0; 16]
        |print(a.sum(), a.len)
        |""".stripMargin

    run(src) shouldBe "16 16\n"
  }

  "a vector of one lane is a degenerate case that still works" in {
    run("val v: <1>f32 = [3.5]\nprint(v[0], v.sum(), v.len)") shouldBe "3.5 3.5 1\n"
  }

  // --- vectors as values ---------------------------------------------------------------

  "a vector is copied by assignment, so two names do not share lanes" in {
    val src =
      """var a: <4>i32 = [1, 2, 3, 4]
        |var b = a
        |a = a + 1
        |print(a[0], b[0])
        |""".stripMargin

    run(src) shouldBe "2 1\n"
  }

  "a vector crosses a function boundary and comes back" in {
    val src =
      """twice(v: <4>i32) -> <4>i32 = v + v
        |
        |val a: <4>i32 = [1, 2, 3, 4]
        |val b = twice(twice(a))
        |print(b[0], b[3])
        |""".stripMargin

    run(src) shouldBe "4 16\n"
  }

  "a vector lives in a struct" in {
    val src =
      """struct Body
        |    pos: <4>f32
        |    vel: <4>f32
        |
        |var b = Body([0.0, 0.0, 0.0, 0.0], [1.0, 2.0, 3.0, 4.0])
        |b.pos = b.pos + b.vel
        |print(b.pos[1], b.pos[3])
        |""".stripMargin

    run(src) shouldBe "2 4\n"
  }

  "an array of vectors indexes to a whole register" in {
    val src =
      """var rows: [2]<4>f32
        |rows[0] = [1.0, 2.0, 3.0, 4.0]
        |rows[1] = rows[0] * 2.0
        |print(rows[1][0], rows[1][3])
        |""".stripMargin

    run(src) shouldBe "2 8\n"
  }
}
