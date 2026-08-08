package sh.sysl

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

  /** The machine every assertion in this file is about unless it says otherwise. `Layout` takes a
   * target now (`targets.md`), so a bare number here is a claim about *a* machine and has to say
   * which — the 32-bit answers are pinned in their own section at the bottom.
   */
  private val at64 = Layout(Word(64))

  /** The other one, for the section checking that a narrower address changes exactly what it should
   * and nothing else.
   */
  private val at32 = Layout(Word(32))

  private val u8  = Type.Integer(8, false)
  private val i64 = Type.Integer(64, true)

  "a scalar is its own width, aligned to it" in {
    at64.size(Type.Bool) shouldBe 1
    at64.size(u8) shouldBe 1
    at64.size(Type.Integer(32, true)) shouldBe 4
    at64.size(i64) shouldBe 8
    at64.size(Type.Floating(32)) shouldBe 4
    at64.size(Type.Char) shouldBe 4

    at64.align(Type.Bool) shouldBe 1
    at64.align(Type.Char) shouldBe 4
    at64.align(i64) shouldBe 8
  }

  /** A width that is not a whole number of bytes is the case `bits / 8` gets wrong, and it was wrong
   * here for every width already legal: LLVM rounds an integer's alignment up to that of the
   * smallest one its data layout names and then rounds the stride up to *that*, so a `u12` costs two
   * bytes aligned to two and a `u96` costs sixteen. Under-reporting either is how a union's payload
   * region ends up narrower than the value a variant stores into it, and nothing downstream can
   * notice — `Layout` is the only thing that ever says how wide the region is.
   */
  "an odd width costs what LLVM makes it cost, not its bit count over eight" in {
    at64.size(Type.Integer(1, false)) shouldBe 1
    at64.size(Type.Integer(5, false)) shouldBe 1
    at64.size(Type.Integer(12, false)) shouldBe 2
    at64.size(Type.Integer(20, false)) shouldBe 4
    at64.size(Type.Integer(96, false)) shouldBe 16
    at64.size(Type.Integer(128, false)) shouldBe 16

    at64.align(Type.Integer(5, false)) shouldBe 1
    at64.align(Type.Integer(12, false)) shouldBe 2
    at64.align(Type.Integer(20, false)) shouldBe 4
    at64.align(Type.Integer(96, false)) shouldBe 16
    at64.align(Type.Integer(128, false)) shouldBe 16
  }

  "a payload region wide enough for a variant of odd-width fields" in {
    val out = ir("""enum Packed
                   |    Four(a: u12, b: u12, c: u12, d: u12)
                   |    One(v: u8)
                   |
                   |var p = Four(1, 2, 3, 4)
                   |p match
                   |    Four(a, b, c, d) -> print(a, b, c, d)
                   |    One(v) -> print(v)""".stripMargin)

    // Four fields each aligned to two bytes occupy eight, counted in the units the region is aligned
    // to. Counting them as one byte apiece — which `bits / 8` does — gave `[4 x i8]`, half of what
    // the variant writes. Asserting the width is what catches it: running the program below passes
    // either way, because the tag and the region round up to eight bytes between them and the
    // overflow lands in padding the enum was carrying anyway.
    out should include("[4 x i16]")
  }

  "and the values come back out of one" in {
    run("""enum Packed
          |    Four(a: u12, b: u12, c: u12, d: u12)
          |    One(v: u8)
          |
          |var p = Four(4095, 1, 4095, 2)
          |p match
          |    Four(a, b, c, d) -> print(a, b, c, d)
          |    One(v) -> print(v)""".stripMargin) shouldBe "4095 1 4095 2\n"
  }

  "an address is one word, and a view of one is three" in {
    at64.size(Type.Ptr(u8)) shouldBe 8
    at64.size(Type.Ref(u8, false)) shouldBe 8
    at64.size(Type.Slice(u8)) shouldBe 24
    at64.size(Type.Str) shouldBe 24
  }

  // A trait object is a pair — the value and the table it dispatches through — so a mode pointing
  // at one is twice the width of a mode pointing at a concrete type.
  "and an address to a trait is two, since it carries its table" in {
    at64.size(Type.Ptr(Type.Trait("Show"))) shouldBe 16
    at64.align(Type.Ptr(Type.Trait("Show"))) shouldBe 8
  }

  "a zero-sized type occupies nothing" in {
    at64.size(Type.Unit) shouldBe 0
    at64.align(Type.Unit) shouldBe 1
  }

  "a struct pads each field onto its own alignment" in {
    at64.size(struct("a" -> u8, "b" -> i64)) shouldBe 16
    at64.align(struct("a" -> u8, "b" -> i64)) shouldBe 8
  }

  // The padding at the end is what makes size and stride the same number, which is what an array of
  // them relies on: without it the second element would start unaligned.
  "and carries the padding at its end that an array of it needs" in {
    at64.size(struct("a" -> i64, "b" -> u8)) shouldBe 16
    at64.size(Type.Array(3, struct("a" -> i64, "b" -> u8))) shouldBe 48
  }

  "a field that occupies nothing changes neither" in {
    at64.size(struct("a" -> i64, "n" -> Type.Unit)) shouldBe 8
    at64.align(struct("a" -> i64, "n" -> Type.Unit)) shouldBe 8
  }

  "an array is its elements end to end" in {
    at64.size(Type.Array(4, Type.Integer(32, true))) shouldBe 16
    at64.align(Type.Array(4, Type.Integer(32, true))) shouldBe 4
    at64.size(Type.Array(0, i64)) shouldBe 0
  }

  "a constrained subtype is laid out as the type it constrains" in {
    val within = Type.Constrained("TaskId", u8, true, Some(BigDecimal(0)), Some(BigDecimal(200)), true, None)

    at64.size(within) shouldBe 1
    at64.align(within) shouldBe 1
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

  /** What a 32-bit machine changes, and — the half that matters more — what it does not.
   *
   * `Layout` takes a target for exactly one reason: an address is four bytes rather than eight. Every
   * other rule is C's and LLVM's and is the same everywhere, so a change that made a scalar or an
   * aggregate's *packing* differ by machine would be a bug, and the second block below is what would
   * catch it.
   */
  "a 32-bit target" - {
    "makes an address, and everything measured in addresses, half the width" in {
      at32.size(Type.Ptr(u8)) shouldBe 4
      at32.align(Type.Ptr(u8)) shouldBe 4
      at32.size(Type.CFn(Nil, Type.Unit)) shouldBe 4

      // A slice is three words wherever it is: an owner, a first element, and a count.
      at32.size(Type.Slice(u8)) shouldBe 12
      at64.size(Type.Slice(u8)) shouldBe 24
      at32.size(Type.Str) shouldBe 12

      // The walk's storage is four words, which is what `Type.VaList.llvm` writes — the two are the
      // same claim and a drift between them would size a `va_start` region wrong.
      at32.size(Type.VaList) shouldBe 16
      at64.size(Type.VaList) shouldBe 32
    }

    "leaves every scalar, and every rule about packing, exactly where it was" in {
      for t <- List[Type](Type.Bool, u8, Type.Char, i64, Type.Integer(32, true), Type.Floating(32),
                          Type.Floating(64), Type.Integer(12, false), Type.Integer(96, true)) do
        withClue(Type.show(t)) {
          at32.size(t) shouldBe at64.size(t)
          at32.align(t) shouldBe at64.align(t)
        }

      // The interior padding rule is C's, so a struct of scalars lays out identically on both.
      val s = struct("a" -> u8, "b" -> Type.Integer(32, true), "c" -> u8)

      at32.size(s) shouldBe at64.size(s)
      at32.align(s) shouldBe at64.align(s)
    }

    "puts a pointer field on a four-byte boundary, which changes the struct around it" in {
      // The one place the width reaches an *aggregate*: `{ u8, *u8 }` is 16 bytes at 64 and 8 at 32,
      // and it is the alignment rather than the pointer's own size that does most of it.
      val p = struct("tag" -> u8, "at" -> Type.Ptr(u8))

      at64.size(p) shouldBe 16
      at64.align(p) shouldBe 8
      at32.size(p) shouldBe 8
      at32.align(p) shouldBe 4
    }
  }
}
