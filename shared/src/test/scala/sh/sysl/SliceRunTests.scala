package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2: slices as programs actually behave. A slice views elements it does not own, so most
 * of what is worth checking is that the view and its buffer agree — and that the buffer
 * outlives every view of it.
 */
class SliceRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val buf = "var buf: &[8]u8 = [1, 2, 3, 4, 5, 6, 7, 8]\n"

  "the whole thing" in {
    run(buf + "var s = buf[..]\nprint(s.len, s[0], s[7])") shouldBe "8 1 8\n"
  }

  "an exclusive range stops before its high end" in {
    run(buf + "var s = buf[2..<5]\nprint(s.len, s[0], s[2])") shouldBe "3 3 5\n"
  }

  "an inclusive range takes its high end too" in {
    run(buf + "var s = buf[2..5]\nprint(s.len, s[0], s[3])") shouldBe "4 3 6\n"
  }

  "an open high end runs to the last element" in {
    run(buf + "var s = buf[6..]\nprint(s.len, s[0], s[1])") shouldBe "2 7 8\n"
  }

  "an open low end starts at the first" in {
    run(buf + "var s = buf[..<3]\nprint(s.len, s[0], s[2])") shouldBe "3 1 3\n"
  }

  "a slice of a slice is relative to the slice" in {
    run(buf + "var s = buf[2..<6]\nvar t = s[1..<3]\nprint(t.len, t[0], t[1])") shouldBe "2 4 5\n"
  }

  "an empty slice is legal, at the end and in the middle" in {
    run(buf + "print(buf[8..].len, buf[3..<3].len, buf[..<0].len)") shouldBe "0 0 0\n"
  }

  "a zero-valued slice owns nothing and names nothing" in {
    run("var s: []int\nprint(s.len)") shouldBe "0\n"
  }

  "a view writes through to the buffer" in {
    run(buf + "var s = buf[2..<5]\ns[0] = 99\nprint(buf[2], s[0])") shouldBe "99 99\n"
  }

  "a function takes a view without caring where the elements live" in {
    val src =
      buf +
        """total(s: []u8) -> int
          |    var t = 0
          |    for b in s do t += int(b)
          |    t
          |end total
          |print(total(buf[..]), total(buf[2..<5]), total(buf[8..]))
          |""".stripMargin

    run(src) shouldBe "36 12 0\n"
  }

  "a callee filling a view is seen by the caller" in {
    val src =
      """fill(s: []int, v: int)
        |    for i in 0..<s.len do s[i] = v
        |end fill
        |var buf: &[4]int = [0, 0, 0, 0]
        |fill(buf[1..<3], 7)
        |print(buf[0], buf[1], buf[2], buf[3])
        |""".stripMargin

    run(src) shouldBe "0 7 7 0\n"
  }

  "the buffer outlives every view of it, even when the reference is gone" in {
    val src =
      """view() -> []int
        |    var owned: &[3]int = [4, 5, 6]
        |    owned[..]
        |end view
        |var s = view()
        |print(s.len, s[0], s[2])
        |""".stripMargin

    run(src) shouldBe "3 4 6\n"
  }

  "a slice of references reaches the objects, which stay alive" in {
    val src =
      """struct Cell
        |    n: int
        |end Cell
        |var cells: &[3]&Cell = [Cell(1), Cell(2), Cell(3)]
        |var view = cells[1..]
        |print(view.len, view[0].n, view[1].n)
        |""".stripMargin

    run(src) shouldBe "2 2 3\n"
  }

  "slicing in a loop neither leaks nor frees twice" in {
    val src =
      """var total = 0
        |for i in 1..20000 do
        |    var b: &[4]int = [i, i, i, i]
        |    var s = b[1..<3]
        |    total += s[0]
        |print(total)
        |""".stripMargin

    run(src) shouldBe "200010000\n"
  }

  "a view of an array this frame owns reaches the same elements" in {
    val src =
      """var xs = [1, 2, 3, 4, 5]
        |var mid = xs[1..<4]
        |mid[0] = 9
        |print(mid.len, mid[0], xs[1], xs[0])
        |""".stripMargin

    run(src) shouldBe "3 9 9 1\n"
  }

  "a scratch buffer is filled by a callee and read back, with nothing allocated" in {
    val src =
      """fill(s: []u8, v: u8) -> usize
        |    for i in 0..<s.len do s[i] = v
        |    s.len
        |end fill
        |
        |total(s: []u8) -> int
        |    var t = 0
        |    for b in s do t += int(b)
        |    t
        |end total
        |
        |format(v: u8) -> int
        |    var buf: [8]u8
        |    var n = fill(buf[0..<4], v)
        |    total(buf[0..<n])
        |end format
        |print(format(3))
        |""".stripMargin

    run(src) shouldBe "12\n"
  }

  // buf[2..<7] = [3,4,5,6,7]; s[1..<4] = [4,5,6]; writing t[1] must reach the one byte four in
  // from the original buffer's start, through two layers of view arithmetic.
  "a write through a slice of a slice reaches the original buffer" in {
    val src =
      buf +
        """var s = buf[2..<7]
          |var t = s[1..<4]
          |t[1] = 99
          |print(t[0], t[1], t[2], s[2], buf[4])
          |""".stripMargin

    run(src) shouldBe "4 99 6 99 99\n"
  }

  // A view is a fat pointer that retains its heap buffer, so reassigning the only named owner does
  // not free what the view sees. The discriminating part is that the view keeps reading the OLD
  // values (1 2 3 4), not the reassigned buffer's (5 6 7 8) — a dangling view would read the new
  // buffer or garbage.
  "a view keeps its heap buffer alive after the owner is reassigned" in {
    val src =
      """var b: &[4]int = [1, 2, 3, 4]
        |var s = b[..]
        |b = [5, 6, 7, 8]
        |print(s[0], s[1], s[2], s[3])
        |""".stripMargin

    run(src) shouldBe "1 2 3 4\n"
  }

  // The re-slice carries the original buffer as its owner and retains it, so the returned sub-view
  // outlives both the heap array and the intermediate view that made it. b[1..<5] = [20,30,40,50];
  // its [1..<3] = [30,40].
  "a returned slice of a slice keeps the buffer alive past the frame" in {
    val src =
      """keep() -> []int
        |    var b: &[6]int = [10, 20, 30, 40, 50, 60]
        |    var s = b[1..<5]
        |    s[1..<3]
        |end keep
        |var t = keep()
        |print(t.len, t[0], t[1])
        |""".stripMargin

    run(src) shouldBe "2 30 40\n"
  }

  // Storing a fresh reference through a view aliases the buffer's slot: the old Cell is released
  // and the new one takes its place, seen through both the view and the owning array.
  "storing a reference through a view replaces that element in the buffer" in {
    val src =
      """struct Cell
        |    n: int
        |end Cell
        |var cells: &[3]&Cell = [Cell(10), Cell(20), Cell(30)]
        |var v = cells[0..<3]
        |v[1] = Cell(99)
        |print(cells[0].n, cells[1].n, cells[2].n, v[1].n)
        |""".stripMargin

    run(src) shouldBe "10 99 30 99\n"
  }

  // -- equality -----------------------------------------------------------------------------
  //
  // `impl[T: Eq] Eq for []T` in `library/sysl/ops.sysl`, and the array block beside it. The
  // expectations here are written in Scala and compared by another runtime, which is what a sysl
  // `@test` asserting `==` cannot do about `==`.

  "two slices with the same elements are equal" in {
    run(buf + "print(buf[0..<3] == buf[0..<3])") shouldBe "true\n"
  }

  "a differing element makes them unequal" in {
    run(buf + "print(buf[0..<3] == buf[1..<4], buf[0..<3] != buf[1..<4])") shouldBe "false true\n"
  }

  // The length test runs first, so this never reads an element that is only in one of them.
  "a differing length makes them unequal whatever the shared prefix is" in {
    run(buf + "print(buf[0..<3] == buf[0..<4], buf[0..<4] == buf[0..<3])") shouldBe "false false\n"
  }

  "two empty slices are equal, and an empty one differs from a non-empty one" in {
    run(buf + "print(buf[3..<3] == buf[5..<5], buf[3..<3] == buf[0..<1])") shouldBe "true false\n"
  }

  // A slice of a type of its own compares through that type's own `eq`, which is what the bound
  // asks for -- nothing here knows how a `Cell` decides it.
  "the element's own equality is what decides" in {
    val src =
      """struct Cell
        |    n: int
        |
        |impl Eq for Cell
        |    eq(self, rhs: Cell) -> bool = self.n % 10 == rhs.n % 10
        |var a: &[2]Cell = [Cell(1), Cell(2)]
        |var b: &[2]Cell = [Cell(11), Cell(22)]
        |var c: &[2]Cell = [Cell(11), Cell(23)]
        |print(a[..] == b[..], a[..] == c[..])
        |""".stripMargin

    run(src) shouldBe "true false\n"
  }

  // The arrays are values rather than references on purpose: a `&[3]int` compares **by address**
  // (`reference/memory.md § &T — counted references`), so a reference would be asking a different
  // question and would answer `false` to two arrays holding the same elements.
  "an array compares as the elements a slice of it would have walked" in {
    val src =
      """var a: [3]int = [1, 2, 3]
        |var b: [3]int = [1, 2, 3]
        |var c: [3]int = [1, 9, 3]
        |print(a == b, a == c, a != c)
        |""".stripMargin

    run(src) shouldBe "true false true\n"
  }

  "a reference to an array still compares by address, which the deref is the way past" in {
    val src =
      """var a: &[3]int = [1, 2, 3]
        |var b: &[3]int = [1, 2, 3]
        |print(a == b, *a == *b)
        |""".stripMargin

    run(src) shouldBe "false true\n"
  }

  // The blanket is an implementation like any other, so a program wanting its own for a narrower
  // slice is writing a second one — and pays the same `override` the printable blanket has always
  // charged. The diagnostic is what names the fix; `ImplShapeRunTests` has the working form.
  "a program's own Eq for a slice of its type is the ordinary duplicate until it says override" in {
    val e = err("""struct N
                  |    v: int
                  |impl Eq for []N
                  |    eq(self, rhs: Self) -> bool = self.len == rhs.len
                  |print(1)""".stripMargin)

    e should include("every slice already implements 'sysl.Eq'")
    e should include("write 'override impl'")
  }

  // Ordering was deliberately not supplied: nothing needed it, and a lexicographic `<` is a
  // separate claim from an element-wise `==`.
  "a slice still has no ordering" in {
    err(buf + "print(buf[0..<3] < buf[1..<4])") should include("'<' is not defined for []byte")
  }

  "a bound past the end stops the program" in {
    exits(buf + "var n = 9\nvar s = buf[0..<n]\nprint(s.len)")
  }

  "an inclusive high end must name an element that exists" in {
    exits(buf + "var n = 8\nvar s = buf[0..n]\nprint(s.len)")
  }

  "a low end past the high end stops the program" in {
    exits(buf + "var a = 5\nvar b = 2\nvar s = buf[a..<b]\nprint(s.len)")
  }

  "indexing past a view's end stops the program, even though the buffer is longer" in {
    exits(buf + "var s = buf[0..<2]\nvar i = 2\nprint(s[i])")
  }
}
