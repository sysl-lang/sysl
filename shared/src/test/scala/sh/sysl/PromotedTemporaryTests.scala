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

  private val triple =
    """triple(n: int) -> [3]int = [n, n + 1, n + 2]
      |
      |""".stripMargin

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

  /** **Every other temporary is the same case**, which is card `0394`. A call answering a `[N]T`
   * puts it in a frame slot exactly as a literal does, so a view of it that gets out has the same
   * nowhere to go and the same answer — and until this it was refused with a sentence naming a
   * field of a value and a parameter passed by value, neither of which is what the reader wrote.
   *
   * `sha256(data)[..]` handed to something that keeps it is the shape it was found from
   * (`slate-language/slate`, `crypto.sysl`), where binding the call to a `val` first was accepted
   * and the direct form was not — two spellings of one frame slot.
   */
  "a view of any other temporary array that escapes is given storage of its own" - {

    "stored into module storage, and read after the frame that made it is gone" in {
      run(triple +
        """static var kept: []const int = []
          |
          |fill()
          |    kept = triple(7)[..]
          |
          |fill()
          |print(kept.len, kept[0], kept[2])
          |""".stripMargin) shouldBe "3 7 9\n"
    }

    // The card's own reduction: nothing of the slice is really returned, since the callee copies —
    // but a call's result is conservatively taken to view every argument, so this is an escape and
    // the temporary is what has to move.
    "handed to a callee whose result is returned, which is the reduction the card was written from" in {
      run(triple +
        """keep(xs: []const int) -> []const int = xs
          |
          |copied() -> []const int = keep(triple(7)[..])
          |
          |var s = copied()
          |print(s.len, s[0], s[2])
          |""".stripMargin) shouldBe "3 7 9\n"
    }

    "through a trait object, which is where the literal form was found" in {
      run(sink + triple +
        """var a = Adder(10)
          |val d: &Sink = a
          |
          |print(d.take(triple(7)[..]))
          |""".stripMargin) shouldBe "13\n"
    }

    // A field of a *temporary* is a temporary: there is no declaration under it either, so the
    // aggregate question a field of a named value raises is not the one being asked. That one is
    // still refused, in `EscapeErrorTests`.
    "a field of a temporary struct is the same case" in {
      run(
        """struct Frame
          |    cells: [3]int
          |
          |framed(n: int) -> Frame = Frame([n, n + 1, n + 2])
          |
          |static var kept: []const int = []
          |
          |fill()
          |    kept = framed(4).cells[..]
          |
          |fill()
          |print(kept.len, kept[0], kept[2])
          |""".stripMargin) shouldBe "3 4 6\n"
    }

    /** The buffer is a second holder of whatever the temporary counted, so it takes a count the way
     * a promoted declaration does. Nothing about the shape says whether that was done: the strings
     * read back correctly either way until something else has reused the storage, which is what the
     * churn below is for.
     */
    "and elements that carry counts are still theirs after the frame and the heap have moved on" in {
      run(
        """pair(n: int) -> [2]string
          |    var a = s"alpha${n}"
          |    var b = s"beta${n}"
          |
          |    [a, b]
          |
          |static var kept: []const string = []
          |
          |fill()
          |    kept = pair(1)[..]
          |
          |fill()
          |
          |var i = 0
          |
          |while i < 100000
          |    var junk = str(i)
          |
          |    if junk.len == 0 then exit(1)
          |
          |    i += 1
          |
          |print(kept[0], kept[1])
          |""".stripMargin) shouldBe "alpha1 beta1\n"
    }

    /** The cost, stated where it can be seen — and the message names the call, since that is what
     * the reader has on the line rather than a name they chose.
     */
    "a module that gave the allocator up is refused, and told which temporary it was" in {
      val e = err(
        """@no_alloc
          |
          |triple(n: int) -> [3]int = [n, n + 1, n + 2]
          |
          |static var kept: []const int = []
          |
          |fill()
          |    kept = triple(7)[..]
          |
          |fill()
          |print(kept.len)
          |""".stripMargin)

      e should include("'triple' answered with")
      e should include("'@no_alloc'")
    }

    /** What must **not** have changed, and the instrument that says so: a slice of a call's answer
     * that stays in the frame is the ordinary case, and it still costs no allocator at all.
     */
    "a temporary whose view does not escape still needs no allocator" in {
      run(
        """@no_alloc
          |
          |triple(n: int) -> [3]int = [n, n + 1, n + 2]
          |
          |count(xs: []const int) -> usize = xs.len
          |
          |print(count(triple(7)[..]))
          |""".stripMargin) shouldBe "3\n"
    }
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
