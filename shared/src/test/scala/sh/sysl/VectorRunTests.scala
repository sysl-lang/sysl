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

  // `v += x` is `v = v + x`, and the two spellings reach the same instruction — so the splat has to
  // happen on this path too. It did not until a probe asked; the compound form refused the scalar
  // while the binary form beside it took one.
  "a compound assignment splats its scalar" in {
    val src =
      """f() -> unit
        |    var v: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |    v += 1.0
        |    v *= 2.0
        |    print(v[0], v[3])
        |
        |f()
        |""".stripMargin

    run(src) shouldBe "4 10\n"
  }

  "a compound assignment takes another vector as well" in {
    val src =
      """f() -> unit
        |    var v: <4>i32 = [1, 2, 3, 4]
        |    val w: <4>i32 = [10, 20, 30, 40]
        |    v += w
        |    print(v[0], v[3])
        |
        |f()
        |""".stripMargin

    run(src) shouldBe "11 44\n"
  }

  // A lane may be a transparent subtype, which lays out and computes as its base — so `<4>Small`
  // is four integers and its arithmetic is the base's, exactly as a scalar `Small`'s is.
  "a constrained lane computes at its base" in {
    val src =
      """type Small = int within 0..100
        |
        |f() -> unit
        |    val v: <4>Small = [1, 2, 3, 4]
        |    val w = v + v
        |    print(w[0], w[3])
        |
        |f()
        |""".stripMargin

    run(src) shouldBe "2 8\n"
  }

  "a vector is a type argument like any other" in {
    val src =
      """id[T](x: T) -> T = x
        |
        |val v: <4>f32 = [1.5, 2.0, 3.0, 4.0]
        |print(id(v)[0])
        |""".stripMargin

    run(src) shouldBe "1.5\n"
  }

  "a vector rides in an enum variant" in {
    val src =
      """enum Shape
        |    Points(v: <4>f32)
        |    Empty
        |
        |val s = Shape.Points([1.5, 2.0, 3.0, 4.0])
        |s match
        |    Shape.Points(v) -> print(v[0], v[3])
        |    Shape.Empty -> print("none")
        |""".stripMargin

    run(src) shouldBe "1.5 4\n"
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

  // -- load and store ---------------------------------------------------------------------------
  //
  // The pair that gives a vector an address to come from and go to. Everything above computes with
  // lanes that were written as literals; a kernel over real data gets them from a slice, and until
  // these existed it had nowhere to put its answers.

  "a run of an array loads into a vector" in {
    val src =
      """var xs: [6]f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0]
        |val v: <4>f32 = xs.load(1)
        |print(v[0], v[3])
        |""".stripMargin

    run(src) shouldBe "2 5\n"
  }

  "a vector stores back into a run of an array" in {
    val src =
      """var xs: [5]f32 = [0.0, 0.0, 0.0, 0.0, 9.0]
        |val v: <4>f32 = [1.0, 2.0, 3.0, 4.0]
        |xs.store(0, v * 10.0)
        |print(xs[0], xs[3], xs[4])
        |""".stripMargin

    run(src) shouldBe "10 40 9\n"
  }

  // A slice's run is measured from the slice, so the elements written are the ones the *slice*
  // names — which is what makes a kernel taking a `[]f32` parameter mean anything.
  "a slice loads and stores the elements it views" in {
    val src =
      """var xs: [8]f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
        |val s = xs[2..<6]
        |val v: <4>f32 = s.load(0)
        |s.store(0, v + 100.0)
        |print(xs[1], xs[2], xs[5], xs[6])
        |""".stripMargin

    run(src) shouldBe "2 103 106 7\n"
  }

  "an integer run loads and stores" in {
    val src =
      """var xs = [1, 2, 3, 4]
        |val v: <4>int = xs.load(0)
        |xs.store(0, v * v)
        |print(xs[0], xs[1], xs[2], xs[3])
        |""".stripMargin

    run(src) shouldBe "1 4 9 16\n"
  }

  "a reference to an array is a receiver like the array" in {
    val src =
      """var b: &[8]f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
        |val v: <4>f32 = b.load(2)
        |b.store(0, v)
        |print(b[0], b[3], b[4])
        |""".stripMargin

    run(src) shouldBe "3 6 5\n"
  }

  /** **A declared `load` or `store` beats the builtin, and the full suite is what said so.**
    *
    * `sysl.sync.Atomic.load` was in the library before vectors were, reached through a `*self` — so
    * a builtin claiming these two words unconditionally did not merely shadow something hypothetical,
    * it refused a retry loop already written in `SyncTests`. Two ordinary words are not spellings
    * only the compiler could have meant, and the precedence a reader assumes is that their own
    * declaration is theirs.
    */
  "a member declared for a slice wins over the builtin" in {
    val src =
      """trait Lanes
        |    load(self, i: usize) -> f32
        |
        |impl Lanes for []const f32
        |    load(self, i: usize) -> f32 = self[i] * 100.0
        |
        |var xs: [4]f32 = [1.0, 2.0, 3.0, 4.0]
        |print(xs[..].load(2))
        |""".stripMargin

    run(src) shouldBe "300\n"
  }

  "an atomic's own load and store are reached through a pointer" in {
    val src =
      """import sysl.sync.Atomic
        |
        |bump(a: *Atomic[int])
        |    a.store(a.load() + 1)
        |
        |var counter = Atomic(7)
        |bump(&counter)
        |print(counter.load())
        |""".stripMargin

    run(src) shouldBe "8\n"
  }

  "a mask survives a round trip through memory" in {
    val src =
      """var xs: [4]f32 = [1.0, 5.0, 2.0, 8.0]
        |val v: <4>f32 = xs.load(0)
        |xs.store(0, (v > 3.0).select(0.0, v))
        |print(xs[0], xs[1], xs[2], xs[3])
        |""".stripMargin

    run(src) shouldBe "1 0 2 0\n"
  }

  // -- the run is checked as a run --------------------------------------------------------------

  "a run exactly reaching the end is in bounds" in {
    val src =
      """var xs: [5]f32 = [1.0, 2.0, 3.0, 4.0, 5.0]
        |val v: <4>f32 = xs.load(1)
        |print(v[0], v[3])
        |""".stripMargin

    run(src) shouldBe "2 5\n"
  }

  // The whole difference between this and a subscript: index 3 exists, and the run starting at 3
  // does not. A check made at the first element only would let this through.
  "a run whose tail is past the end traps" in {
    exits(
      """var xs: [5]f32 = [1.0, 2.0, 3.0, 4.0, 5.0]
        |val v: <4>f32 = xs.load(3)
        |print(v[0])""".stripMargin
    )
  }

  "a store whose tail is past the end traps" in {
    exits(
      """var xs: [5]f32 = [1.0, 2.0, 3.0, 4.0, 5.0]
        |val v: <4>f32 = [0.0, 0.0, 0.0, 0.0]
        |xs.store(2, v)""".stripMargin
    )
  }

  // **The overflow case, and it is why `runAddr` subtracts rather than adds.** `i + 4` on a `usize`
  // at the top of the range wraps to 3, so a check written the obvious way would pass and the load
  // would read four floats from wherever the wrapped address lands.
  "an index at the top of usize does not wrap past the check" in {
    exits(
      """var xs: [5]f32 = [1.0, 2.0, 3.0, 4.0, 5.0]
        |val i: usize = 18446744073709551615
        |val v: <4>f32 = xs.load(i)
        |print(v[0])""".stripMargin
    )
  }

  // The other half of the same arithmetic: `len - lanes` on a slice shorter than the vector wraps
  // to an enormous number, which every index is below. The length test in front of it is what
  // catches this one, and nothing else would.
  "a slice shorter than the vector traps rather than wrapping" in {
    exits(
      """var xs: [2]f32 = [1.0, 2.0]
        |val v: <4>f32 = xs[..].load(0)
        |print(v[0])""".stripMargin
    )
  }

  // -- one kernel, every width --------------------------------------------------------------------

  /** **This is what card 0155 was filed for, and what `guide/simd` could not write.**
    *
    * The width is a parameter, so neither the load nor the store can name its lanes — a lane index
    * has to be a constant, and there is no constant to write. Both are written once here and the
    * body is instantiated at 4 and at 8, which is the claim the vector type was added to make and
    * which held only for the arithmetic until these two existed.
    *
    * `by` is a vector rather than an `f32` because that is where `W` enters: a written type
    * argument at a call is still refused (`10 § Open a`), so a kernel whose parameters are all
    * slices has no way to be told its width. It is not a workaround — a SIMD kernel's constants are
    * vectors anyway — but it is the reason the signature reads as it does.
    */
  "one kernel loads, computes and stores at more than one width" in {
    val src =
      """scale[const W: usize](xs: []const f32, out: []f32, by: <W>f32)
        |    var i: usize = 0
        |
        |    while i + W <= xs.len
        |        val v: <W>f32 = xs.load(i)
        |        out.store(i, v * by)
        |        i += W
        |
        |    while i < xs.len
        |        out[i] = xs[i] * by[0]
        |        i += 1
        |end scale
        |
        |var src: [10]f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]
        |var four: [10]f32
        |var eight: [10]f32
        |val by4: <4>f32 = 3.0
        |val by8: <8>f32 = 3.0
        |
        |scale(src[..], four[..], by4)
        |scale(src[..], eight[..], by8)
        |
        |for i in 0..<10
        |    if four[i] != eight[i] then print(s"differ at $i")
        |
        |print(four[0], four[9], eight[0], eight[9])
        |""".stripMargin

    run(src) shouldBe "3 30 3 30\n"
  }

  // The tail is the caller's, written as a scalar loop, and the two halves have to agree at the
  // boundary. Ten elements at a width of four is two full runs and two left over, which is the
  // case a kernel gets wrong.
  "the scalar tail covers what the runs do not" in {
    val src =
      """scale[const W: usize](xs: []const f32, out: []f32, by: <W>f32)
        |    var i: usize = 0
        |
        |    while i + W <= xs.len
        |        val v: <W>f32 = xs.load(i)
        |        out.store(i, v * by)
        |        i += W
        |
        |    while i < xs.len
        |        out[i] = xs[i] * by[0]
        |        i += 1
        |end scale
        |
        |var src: [10]f32 = [1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0]
        |var out: [10]f32
        |val by: <4>f32 = 7.0
        |
        |scale(src[..], out[..], by)
        |print(out[7], out[8], out[9])
        |""".stripMargin

    run(src) shouldBe "7 7 7\n"
  }

  // **A load's width comes from what receives it, and an operand position does not receive it.**
  // `xs.load(i) * by` is refused even though `by` fixes the width, because an operator does not
  // decide its two sides in either order — the deferral `analyzeOperands` makes for a bare literal
  // is a narrow, listed one and a method call is not on the list. A parameter *is* a receiving
  // position, which is this case.
  "a load takes its width from a parameter it is passed to" in {
    val src =
      """first(v: <4>f32) -> f32 = v[0]
        |
        |var xs: [4]f32 = [2.5, 0.0, 0.0, 0.0]
        |print(first(xs.load(0)))
        |""".stripMargin

    run(src) shouldBe "2.5\n"
  }

  "a load takes its width from the function's declared result" in {
    val src =
      """grab[const W: usize](xs: []const f32) -> <W>f32 = xs.load(0)
        |
        |var xs: [8]f32 = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]
        |val v: <4>f32 = grab(xs[..])
        |print(v[0], v[3])
        |""".stripMargin

    run(src) shouldBe "1 4\n"
  }
}
