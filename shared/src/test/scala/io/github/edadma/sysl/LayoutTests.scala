package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** The size and alignment model (`Layout`).
 *
 * It exists for one customer — the width of a data enum's payload region has to be a literal in the
 * emitted text — but it is a claim about *every* type, and a claim LLVM will not check: an
 * under-sized region does not fail to compile, it aliases. So the arithmetic is pinned here
 * directly, and the enum tests below it check that what the emitter writes down agrees.
 */
class LayoutTests extends AnyFreeSpec with Matchers with CodegenSupport with RunSupport {

  private def struct(fields: (String, Type)*): Type.Struct = {
    val s = new Type.Struct("S", Nil)

    s.fields = fields.toList
    s
  }

  private val u8  = Type.Integer(8, false)
  private val i64 = Type.Integer(64, true)

  "a scalar is its own width, aligned to it" in {
    Layout.size(Type.Bool) shouldBe 1
    Layout.size(u8) shouldBe 1
    Layout.size(Type.Integer(32, true)) shouldBe 4
    Layout.size(i64) shouldBe 8
    Layout.size(Type.Floating(32)) shouldBe 4
    Layout.size(Type.Char) shouldBe 4

    Layout.align(Type.Bool) shouldBe 1
    Layout.align(Type.Char) shouldBe 4
    Layout.align(i64) shouldBe 8
  }

  "an address is one word, and a view of one is three" in {
    Layout.size(Type.Ptr(u8)) shouldBe 8
    Layout.size(Type.Ref(u8, false)) shouldBe 8
    Layout.size(Type.Slice(u8)) shouldBe 24
    Layout.size(Type.Str) shouldBe 24
  }

  // A trait object is a pair — the value and the table it dispatches through — so a mode pointing
  // at one is twice the width of a mode pointing at a concrete type.
  "and an address to a trait is two, since it carries its table" in {
    Layout.size(Type.Ptr(Type.Trait("Show"))) shouldBe 16
    Layout.align(Type.Ptr(Type.Trait("Show"))) shouldBe 8
  }

  "a zero-sized type occupies nothing" in {
    Layout.size(Type.Unit) shouldBe 0
    Layout.align(Type.Unit) shouldBe 1
  }

  "a struct pads each field onto its own alignment" in {
    Layout.size(struct("a" -> u8, "b" -> i64)) shouldBe 16
    Layout.align(struct("a" -> u8, "b" -> i64)) shouldBe 8
  }

  // The padding at the end is what makes size and stride the same number, which is what an array of
  // them relies on: without it the second element would start unaligned.
  "and carries the padding at its end that an array of it needs" in {
    Layout.size(struct("a" -> i64, "b" -> u8)) shouldBe 16
    Layout.size(Type.Array(3, struct("a" -> i64, "b" -> u8))) shouldBe 48
  }

  "a field that occupies nothing changes neither" in {
    Layout.size(struct("a" -> i64, "n" -> Type.Unit)) shouldBe 8
    Layout.align(struct("a" -> i64, "n" -> Type.Unit)) shouldBe 8
  }

  "an array is its elements end to end" in {
    Layout.size(Type.Array(4, Type.Integer(32, true))) shouldBe 16
    Layout.align(Type.Array(4, Type.Integer(32, true))) shouldBe 4
    Layout.size(Type.Array(0, i64)) shouldBe 0
  }

  "a constrained subtype is laid out as the type it constrains" in {
    val within = Type.Constrained("TaskId", u8, true, Some(BigDecimal(0)), Some(BigDecimal(200)), true, None)

    Layout.size(within) shouldBe 1
    Layout.align(within) shouldBe 1
  }

  "what the emitter writes down agrees with the model" - {
    // A simple enum is its underlying integer and has no region at all, so nothing here applies to
    // one — the type it lowers to is `iN`, not an aggregate.
    "a simple enum is not an aggregate" in {
      val out = ir("""enum Color
                     |    Red
                     |    Green
                     |which(c: Color) -> int
                     |    c match
                     |        Red -> 0
                     |        Green -> 1
                     |print(which(Red))
                     |""".stripMargin)

      out should not include "%enum.Color = type"
    }

    "one variant, one region the size of it" in {
      val out = ir("""enum Box
                     |    Full(n: int)
                     |    Empty
                     |held(b: Box) -> int
                     |    b match
                     |        Full(n) -> n
                     |        Empty -> 0
                     |print(held(Full(1)))
                     |""".stripMargin)

      out should include("%enum.Box = type { i32, [1 x i32] }")
    }

    "a variant carrying a struct is as wide as the struct" in {
      val out = ir("""struct P
                     |    x: i64
                     |    y: u8
                     |enum E
                     |    One(p: P)
                     |    Two(n: int)
                     |which(e: E) -> int
                     |    e match
                     |        One(p) -> int(p.y)
                     |        Two(n) -> n
                     |print(which(One(P(1i64, 2u8))))
                     |""".stripMargin)

      out should include("%struct.P = type { i64, i8 }")
      out should include("%enum.E = type { i32, [2 x i64] }")
    }

    // A variant written with fields that all occupy nothing still carries a payload aggregate, and
    // that aggregate is empty — so the region it asks for is empty too.
    "a payload of nothing asks for no region" in {
      val out = ir("""enum E
                     |    Nothing(n: unit)
                     |    Something
                     |which(e: E) -> int
                     |    e match
                     |        Nothing(n) -> 1
                     |        Something -> 2
                     |print(which(Something))
                     |""".stripMargin)

      out should include("%E.Nothing = type {  }")
      out should include("%enum.E = type { i32, [0 x i8] }")
    }

    // The shape a conversion with more than one answer takes: variants of one, two and three
    // wrapped words. The region is three words because of the widest of them, and the two narrower
    // variants are stored in the same three — which is the claim, since laying them out side by
    // side would give six.
    "the widest variant sizes the region and the others share it" in {
      val out = ir("""struct Moment
                     |    us: i64
                     |struct Span
                     |    us: i64
                     |enum Answer
                     |    Once(at: Moment)
                     |    Skipped(gap: Span, at: Moment, shifted: Moment)
                     |    Twice(earlier: Moment, later: Moment)
                     |which(a: Answer) -> i64
                     |    a match
                     |        Once(at) -> at.us
                     |        Skipped(gap, at, shifted) -> gap.us + at.us + shifted.us
                     |        Twice(earlier, later) -> earlier.us + later.us
                     |print(which(Skipped(Span(1i64), Moment(2i64), Moment(4i64))))
                     |print(which(Twice(Moment(8i64), Moment(16i64))))
                     |print(which(Once(Moment(32i64))))
                     |""".stripMargin)

      out should include("%enum.Answer = type { i32, [3 x i64] }")
    }

    "and every one of them reads back what it wrote" in {
      run("""struct Moment
            |    us: i64
            |struct Span
            |    us: i64
            |enum Answer
            |    Once(at: Moment)
            |    Skipped(gap: Span, at: Moment, shifted: Moment)
            |    Twice(earlier: Moment, later: Moment)
            |which(a: Answer) -> i64
            |    a match
            |        Once(at) -> at.us
            |        Skipped(gap, at, shifted) -> gap.us + at.us + shifted.us
            |        Twice(earlier, later) -> earlier.us + later.us
            |var t: [3]Answer = [Once(Moment(32i64)); 3]
            |t[1usize] = Skipped(Span(1i64), Moment(2i64), Moment(4i64))
            |t[2usize] = Twice(Moment(8i64), Moment(16i64))
            |for i in 0..<3
            |    print(which(t[usize(i)]))
            |""".stripMargin) shouldBe "32\n7\n24\n"
    }
  }
}
