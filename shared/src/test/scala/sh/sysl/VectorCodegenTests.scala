package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-1: the IR a vector lowers to.
 *
 * **What this suite is for is the half `VectorRunTests` cannot see.** A program that computes the
 * right numbers computes them whether the back end used one register or scalarized into four, so the
 * run tier says nothing about whether a vector *is* a vector. These assertions are what pin that the
 * type reaches LLVM as `<N x T>` and that the operations are the single instructions they should be
 * — which is the whole claim the feature makes.
 */
class VectorCodegenTests extends AnyFreeSpec with CodegenSupport {

  "the type reaches LLVM as a vector, not an array" in {
    val out = ir("val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]\nprint(v[0])")

    out should include("<4 x float>")
    out should not include "[4 x float]"
  }

  "a literal is an insertelement chain" in {
    ir("val v: <4>i32 = [1, 2, 3, 4]\nprint(v[0])") should include(
      "insertelement <4 x i32>")
  }

  // One instruction for four additions is the whole point, so the emitted text is asserted rather
  // than left to the numbers: `fadd` at the vector type is what says the lanes were not unrolled.
  "arithmetic is one instruction at the register's width" in {
    val src =
      """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |val b: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |print((a + b)[0])
        |""".stripMargin

    ir(src) should include("fadd <4 x float>")
  }

  "the splat is a shufflevector with a zero mask" in {
    val src =
      """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |print((a * 2.0)[0])
        |""".stripMargin

    ir(src) should include("shufflevector <4 x float>")
    ir(src) should include("<4 x i32> zeroinitializer")
  }

  "a mask is a vector of i1" in {
    val src =
      """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |print((a < 2.0).any())
        |""".stripMargin

    ir(src) should include("fcmp olt <4 x float>")
    ir(src) should include("<4 x i1>")
  }

  "select is one instruction, not a branch" in {
    val src =
      """val a: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |print((a < 2.0).select(a, a)[0])
        |""".stripMargin

    ir(src) should include("select <4 x i1> ")
  }

  // The suffix is `v4f32` and not the rendered `<4 x float>` with its spaces removed, which is the
  // mistake `LType.overloadSuffix` exists to prevent — LLVM would take the wrong name at the text
  // level and fail in the verifier, a layer past where the mistake was made.
  "a reduction names the intrinsic with LLVM's overload suffix" in {
    val out = ir("val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]\nprint(v.sum())")

    out should include("@llvm.vector.reduce.fadd.v4f32")
    out should include("declare float @llvm.vector.reduce.fadd.v4f32(float, <4 x float>)")
  }

  "an integer reduction takes no accumulator" in {
    val out = ir("val v: <4>i32 = [1, 2, 3, 4]\nprint(v.sum())")

    out should include("declare i32 @llvm.vector.reduce.add.v4i32(<4 x i32>)")
  }

  // The float sum is `reassoc` because floating addition is not associative and the intrinsic is a
  // left fold without it — which would make a reduction cost exactly what a loop costs.
  "a float sum is reassociable" in {
    ir("val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]\nprint(v.sum())") should include(
      "call reassoc float @llvm.vector.reduce.fadd")
  }

  "a signed minimum picks the signed intrinsic and an unsigned one the unsigned" in {
    ir("val v: <4>i32 = [1, 2, 3, 4]\nprint(v.min())") should include("reduce.smin.v4i32")
    ir("val v: <4>u32 = [1, 2, 3, 4]\nprint(v.min())") should include("reduce.umin.v4i32")
  }

  "a mask reduces with and/or rather than a loop" in {
    val src =
      """val a: <4>i32 = [1, 2, 3, 4]
        |print((a < 2).all(), (a < 2).any())
        |""".stripMargin

    ir(src) should include("reduce.and.v4i1")
    ir(src) should include("reduce.or.v4i1")
  }

  "a lane is extractelement, with no address and no bounds test" in {
    val out = ir("val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]\nprint(v[2])")

    out should include("extractelement <4 x float>")
    out should not include "getelementptr <4 x float>"
  }

  // Two instantiations of one generic are two bodies holding different instructions, which is what
  // the mangled lane count keeps apart — without it both widths would share one body.
  "a generic kernel emits one body per width" in {
    val src =
      """scale[const W: usize](v: <W>f32, by: f32) -> <W>f32 = v * by
        |
        |val four: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |val eight: <8>f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
        |print(scale(four, 2.0)[0], scale(eight, 2.0)[0])
        |""".stripMargin

    val out = ir(src)

    out should include("fmul <4 x float>")
    out should include("fmul <8 x float>")
  }

  "a vector in a struct is laid out as one field" in {
    val src =
      """struct Body
        |    vel: <4>f32
        |
        |val b = Body([1.0, 2.0, 3.0, 4.0])
        |print(b.vel[0])
        |""".stripMargin

    ir(src) should include("<4 x float>")
  }
}
