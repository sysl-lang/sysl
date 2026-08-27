package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A slice of an **array literal** that escapes — the one storage promotion had nothing to move,
 * because there is no declaration to move (card `0314`).
 *
 * `reference/memory.md § What happens when a slice escapes` moves a declared `var buf: [64]u8` to a
 * buffer when a view of it gets out. An array literal in expression position has no declaration, so
 * it fell to the anonymous case and was refused outright — with a sentence offering two explanations
 * ("a field of a value", "an array a caller passed by value") that are neither of them true of a
 * temporary in the frame that made it.
 *
 * **The shape that meets it is a variadic through a trait object.** `xs: ...T` packs a call's
 * trailing arguments into exactly `[a, b, c][..]`, so `d.take(1, 2, 3)` on a `&Sink` produced a
 * diagnostic about a slice the reader did not write, with advice they could not act on.
 */
class PromotedTemporaryTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  private val sink =
    """trait Sink
      |    take(self, xs: []const int) -> int
      |
      |struct Adder
      |    base: int
      |
      |impl Sink for Adder
      |    take(self, xs: []const int) -> int = self.base + int(xs.len)
      |
      |""".stripMargin

  "a view of an array literal that escapes is given storage of its own" - {

    "through a trait object, which is the reduction the card was written from" in {
      run(sink +
        """var a = Adder(10)
          |val d: &Sink = a
          |var arr = [1, 2, 3]
          |
          |print(d.take(arr[..]))
          |print(d.take([4, 5, 6][..]))
          |""".stripMargin) shouldBe "13\n13\n"
    }

    "and the elements are the ones written, rather than whatever the frame left" in {
      run(
        """trait Sink
          |    total(self, xs: []const int) -> int
          |
          |struct Sum
          |    base: int
          |
          |impl Sink for Sum
          |    total(self, xs: []const int) -> int
          |        var n = self.base
          |
          |        for x in xs
          |            n += x
          |
          |        n
          |
          |var s = Sum(100)
          |val d: &Sink = s
          |
          |print(d.total([4, 5, 6][..]))
          |""".stripMargin) shouldBe "115\n"
    }

    "a repeat form is the same case, since it is the other array literal" in {
      run(sink +
        """var a = Adder(10)
          |val d: &Sink = a
          |
          |print(d.take([7; 4][..]))
          |""".stripMargin) shouldBe "14\n"
    }
  }

  /** **The case 0.0.85 made easy to meet**, and the reason the card was filed: a `...T` member
   * declared on a trait was refused through the object with a diagnostic about a slice the caller
   * never wrote.
   */
  "a variadic trait member works through a trait object" in {
    run(
      """trait Sink
        |    take(self, xs: ...int) -> int
        |
        |struct Adder
        |    base: int
        |
        |impl Sink for Adder
        |    take(self, xs: ...int) -> int
        |        var n = self.base
        |
        |        for x in xs
        |            n += x
        |
        |        n
        |
        |var a = Adder(10)
        |val d: &Sink = a
        |
        |print(d.take(1, 2, 3))
        |print(d.take())
        |""".stripMargin) shouldBe "16\n10\n"
  }

  /** A store into module storage is the other way out of a frame, and the one a `@no_alloc` module
   * can actually reach — a trait object needs an allocator of its own, so it is refused a step
   * earlier and never gets as far as the slice.
   */
  "a literal stored into module storage outlives the frame, and is given storage" in {
    run(
      """static var kept: []const int = []
        |
        |fill()
        |    kept = [4, 5, 6][..]
        |
        |fill()
        |print(kept.len, kept[0], kept[2])
        |""".stripMargin) shouldBe "3 4 6\n"
  }

  /** **The cost, stated where it can be seen.** The storage is the allocator's, so a module that
   * gave the allocator up is refused — at the view that leaves the frame, which is the line that
   * would have allocated, rather than silently getting frame storage that outlives its frame.
   */
  "a module that gave the allocator up is refused, and told what it costs" in {
    val e = err(
      """@no_alloc
        |
        |static var kept: []const int = []
        |
        |fill()
        |    kept = [4, 5, 6][..]
        |
        |fill()
        |print(kept.len)
        |""".stripMargin)

    e should include("an array literal")
    e should include("'@no_alloc'")
  }

  /** What must **not** have changed: a variadic call that does not escape is still frame-only, so
   * the ordinary case costs no allocator at all. `@no_alloc` is the instrument — it refuses exactly
   * where the allocator is reached, so a green build here is the evidence.
   */
  "a variadic call that does not escape still needs no allocator" in {
    run(
      """@no_alloc
        |
        |total(xs: ...int) -> int
        |    var n = 0
        |
        |    for x in xs
        |        n += x
        |
        |    n
        |
        |print(total(1, 2, 3))
        |""".stripMargin) shouldBe "6\n"
  }

  /** A partial slice of a literal has no declaration either, and is **not** promoted — the escape is
   * still refused. It is left out because a slice of part of a buffer is a different node from the
   * buffer, and the whole-range case is the one every reachable shape produces: a `...T` pack, and
   * the `[…][..]` a reader writes by hand.
   */
  "a partial slice of a literal is still refused, and says so" in {
    val e = err(sink +
      """var a = Adder(10)
        |val d: &Sink = a
        |
        |print(d.take([4, 5, 6][1..]))
        |""".stripMargin)

    e should include("would outlive the array")
  }
}
