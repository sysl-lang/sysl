package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `Buf[T]` — a growable array (`07 §Growing one`).
 *
 * It is **ordinary sysl in the library**, not a type the compiler knows, and that is the finding
 * rather than an implementation note. Storage sized while running gave a library the one thing it
 * was missing: a `[]T` field is storage a container can make for itself and that ARC destroys on
 * its behalf, so no `Drop`, no `sizeof` over a parameter, and no pointer cast are needed.
 *
 * The other thing that had looked like a blocker was that a generic container cannot make its own
 * storage, since a repeat needs a value and no bound promises one. A `push` arrives holding one —
 * so the value being pushed seeds the new storage, and the question never comes up.
 *
 * **What a push does to the other views** was `07`'s open question, and the answer is that sysl
 * does not have to choose one: a growable array is a struct, so whether a push is seen follows from
 * whether it is held by reference or by value, which is a choice the language already makes you
 * write. Go's confusion comes from having only one answer available.
 */
class BufTests extends AnyFreeSpec with RunSupport {

  /** `Buf` is `sysl.buf`'s: a growable sequence is something a program asks for, not something the
   * language desugars onto. Written once here so that each program below is about the sequence.
   */
  private val importing = "import sysl.buf.*\n\n"

  override protected def run(src: String): String = super.run(importing + src)

  override protected def exits(src: String): Unit = super.exits(importing + src)

  override protected def panics(src: String, message: String): Unit = super.panics(importing + src, message)

  "the basic shape" - {
    "push, len, and read back" in {
      run(
        """var b: &Buf[int] = buf()
          |for i in 0..<5 do b.push(i * i)
          |print(b.len(), b.at(0), b.at(4))""".stripMargin
      ) shouldBe "5 0 16\n"
    }

    "a fresh one is empty and holds nothing" in {
      run(
        """var b: &Buf[int] = buf()
          |print(b.len(), b.cap(), b.is_empty(), b.pop().is_none())""".stripMargin
      ) shouldBe "0 0 true true\n"
    }

    "set writes an element that is there" in {
      run(
        """var b: &Buf[int] = buf()
          |b.push(1)
          |b.push(2)
          |b.set(0, 99)
          |print(b.at(0), b.at(1))""".stripMargin
      ) shouldBe "99 2\n"
    }

    "pop takes the last one back off" in {
      run(
        """var b: &Buf[int] = buf()
          |for i in 0..<3 do b.push(i)
          |print(b.pop().unwrap(), b.pop().unwrap(), b.len(), b.pop().unwrap(), b.pop().is_none())""".stripMargin
      ) shouldBe "2 1 1 0 true\n"
    }

    "clear empties it without giving the storage back" in {
      run(
        """var b: &Buf[int] = buf()
          |for i in 0..<10 do b.push(i)
          |var had = b.cap()
          |b.clear()
          |print(b.len(), b.is_empty(), b.cap() == had)""".stripMargin
      ) shouldBe "0 true true\n"
    }

    "and a view is the elements that are actually there" in {
      run(
        """var b: &Buf[int] = buf()
          |for i in 0..<5 do b.push(i)
          |var s = b.view()
          |var total = 0
          |for x in s do total += x
          |print(s.len, total, s[4])""".stripMargin
      ) shouldBe "5 10 4\n"
    }

    /* The view is the WRITABLE form, and these two facts together are why: a read site pays nothing
     * for it, because `[]T` widens to `[]const T` on its own, while a read-only view would foreclose
     * mutating a buffer in place — which is the only route a sort or a reverse could ever take, since
     * `set` reaches one element at a time. Strictly more capable, at no cost to the readers. */
    "and that view may be written, while still satisfying a reader that asks for a read-only one" in {
      run(
        """total(xs: []const int) -> int
          |    var s = 0
          |    for x in xs do s += x
          |    s
          |var b: &Buf[int] = buf()
          |b.push(3)
          |b.push(4)
          |var v = b.view()
          |v[0] = 10
          |print(total(b.view()), b.at(0))""".stripMargin
      ) shouldBe "14 10\n"
    }
  }

  // A list somebody *leaves*: taking an element out of the middle, and cutting a length down to a
  // number. `remove` is written in terms of `truncate`, which is what `07 § Not yet` said it should
  // be, and `clear` is `truncate(0)` — so all three shorten a buffer the one way.
  "shortening one" - {
    "truncate cuts the length down to what was asked for" in {
      run(
        """var b: &Buf[int] = buf()
          |for i in 0..<5 do b.push(i)
          |b.truncate(2)
          |print(b.len(), b.at(0), b.at(1))""".stripMargin
      ) shouldBe "2 0 1\n"
    }

    // Asking for a length there is not is a request to cut nothing, which is a no-op rather than a
    // panic: unlike an index, a length past the end names no element and so cannot read one.
    "a length past the end changes nothing" in {
      run(
        """var b: &Buf[int] = buf()
          |for i in 0..<3 do b.push(i)
          |b.truncate(9)
          |b.truncate(3)
          |print(b.len(), b.at(2))""".stripMargin
      ) shouldBe "3 2\n"
    }

    "truncating to nothing is what clear does" in {
      run(
        """var b: &Buf[int] = buf()
          |for i in 0..<4 do b.push(i)
          |var had = b.cap()
          |b.truncate(0)
          |print(b.len(), b.is_empty(), b.cap() == had)""".stripMargin
      ) shouldBe "0 true true\n"
    }

    "remove hands back the element it took out" in {
      run(
        """var b: &Buf[int] = buf()
          |for i in 0..<5 do b.push(i * 10)
          |print(b.remove(1), b.len(), b.at(0), b.at(1), b.at(2), b.at(3))""".stripMargin
      ) shouldBe "10 4 0 20 30 40\n"
    }

    // The first and last elements are where an off-by-one in the shift shows: removing at 0 moves
    // every survivor, and removing the last moves none.
    "at either end" in {
      run(
        """var b: &Buf[int] = buf()
          |for i in 0..<4 do b.push(i)
          |print(b.remove(0), b.at(0), b.at(2), b.len())
          |print(b.remove(2), b.at(0), b.at(1), b.len())""".stripMargin
      ) shouldBe "0 1 3 3\n3 1 2 2\n"
    }

    "the only element" in {
      run(
        """var b: &Buf[int] = buf()
          |b.push(7)
          |print(b.remove(0), b.len(), b.is_empty())""".stripMargin
      ) shouldBe "7 0 true\n"
    }

    // Every element removed one at a time, always at the front, so each removal shifts the whole
    // remainder — the order it comes out in is the whole assertion.
    "one at a time until there is nothing left" in {
      run(
        """import sysl.text.str_builder
          |
          |var b: &Buf[int] = buf()
          |for i in 0..<6 do b.push(i)
          |var out = str_builder()
          |while !b.is_empty() do out.push(str(b.remove(0)))
          |print(out.finish(), b.len())""".stripMargin
      ) shouldBe "012345 0\n"
    }

    // An element that holds something is handed to the caller *and* shifted past by its neighbours,
    // so a count taken once and a count taken twice look identical until a buffer of references is
    // churned. Removing from the front of a hundred thousand does both at every step.
    "removing an element that holds something balances its count" in {
      run(
        """struct Cell
          |    v: int
          |end Cell
          |
          |var total = 0
          |
          |for round in 0..<100000
          |    var b: &Buf[&Cell] = buf()
          |    b.push(Cell(1))
          |    b.push(Cell(2))
          |    b.push(Cell(3))
          |
          |    var gone = b.remove(0)
          |    total += gone.v + b.at(0).v + b.at(1).v
          |
          |print(total)""".stripMargin
      ) shouldBe "600000\n"
    }

    "and a truncated one is let go of too" in {
      run(
        """var total = 0
          |
          |for round in 0..<100000
          |    var b: &Buf[string] = buf()
          |    for i in 0..<4 do b.push(s"item $i")
          |    b.truncate(1)
          |    total += int(b.at(0).len)
          |
          |print(total)""".stripMargin
      ) shouldBe "600000\n"
    }

    // A copy of a `Buf` copies the two fields and not the allocation behind them, so what a copy
    // keeps is the **count** it was taken at — the shift a removal makes lands in storage they
    // share, and the copy reads the shifted elements at a length that no longer describes them.
    // That is the same shallow copy `§ how a push is seen` is about, seen from the shortening side.
    "a copy taken before a removal keeps the length, not the elements" in {
      run(
        """var p: &Buf[int] = buf()
          |for i in 0..<3 do p.push(i)
          |var c = *p
          |print(p.remove(0), p.len(), p.at(0), c.len(), c.at(0), c.at(2))""".stripMargin
      ) shouldBe "0 2 1 3 1 2\n"
    }

    "removing an index that names no element says which and how many there were" in {
      panics(
        """var b: &Buf[int] = buf()
          |b.push(1)
          |b.push(2)
          |print(b.remove(2))""".stripMargin,
        "past the 2 elements",
      )
    }

    "and so does removing from an empty one" in {
      panics(
        """var b: &Buf[int] = buf()
          |print(b.remove(0))""".stripMargin,
        "past the 0 elements",
      )
    }
  }

  "growth" - {
    "capacity doubles from nothing, and every element survives it" in {
      run(
        """var b: &Buf[int] = buf()
          |for i in 0..<100 do b.push(i)
          |var total = 0
          |for x in b.view() do total += x
          |print(b.len(), b.cap(), total, b.at(0), b.at(99))""".stripMargin
      ) shouldBe "100 128 4950 0 99\n"
    }

    // The count is the thing that grows; capacity is only ever a power of two above it, so a push
    // that fits costs no allocation at all.
    "a push that fits does not reallocate" in {
      run(
        """var b: &Buf[int] = buf()
          |b.push(1)
          |var had = b.cap()
          |for i in 0..<7 do b.push(i)
          |print(had, b.cap(), b.len())""".stripMargin
      ) shouldBe "8 8 8\n"
    }

    "growth carries elements that hold something" in {
      run(
        """var b: &Buf[string] = buf()
          |for i in 0..<40 do b.push(s"k$i")
          |print(b.len(), b.at(0), b.at(39))""".stripMargin
      ) shouldBe "40 k0 k39\n"
    }
  }

  // This is the design answer. Go has one representation and therefore one behaviour, and the
  // behaviour it picked is why two Go slices agree until one of them grows. sysl already makes the
  // caller write which of the two they meant.
  "how a push is seen follows from how the buffer is held" - {
    "two names for one buffer see each other's pushes" in {
      run(
        """var p: &Buf[int] = buf()
          |var q = p
          |p.push(7)
          |q.push(8)
          |print(p.len(), q.len(), p.at(1), q.at(0))""".stripMargin
      ) shouldBe "2 2 8 7\n"
    }

    // A struct copied out of its box is a copy, which is what copying a struct means everywhere in
    // the language — nothing about `Buf` makes it an exception, and nothing about it is hidden.
    "a copy taken out of the reference is a copy" in {
      run(
        """var p: &Buf[int] = buf()
          |p.push(1)
          |var c = *p
          |c.push(2)
          |print(p.len(), c.len())""".stripMargin
      ) shouldBe "1 2\n"
    }

    "and one passed by reference is grown by the callee" in {
      run(
        """fill(b: &Buf[int], n: int)
          |    for i in 0..<n do b.push(i)
          |var p: &Buf[int] = buf()
          |fill(p, 30)
          |print(p.len(), p.at(29))""".stripMargin
      ) shouldBe "30 29\n"
    }
  }

  // The safety property that made a reference the right answer: an append can move the storage, and
  // a view taken before it keeps the storage it was made from alive rather than dangling.
  "a view taken before a grow stays valid" - {
    "showing the elements it was made from" in {
      run(
        """var b: &Buf[int] = buf()
          |b.push(1)
          |b.push(2)
          |var early = b.view()
          |for i in 0..<50 do b.push(i)
          |print(early.len, early[0], early[1], b.len())""".stripMargin
      ) shouldBe "2 1 2 52\n"
    }

    "and a view of references keeps them alive too" in {
      run(
        """struct Node
          |    value: int
          |var b: &Buf[&Node] = buf()
          |b.push(Node(5))
          |var early = b.view()
          |for i in 0..<50 do b.push(Node(i))
          |print(early.len, early[0].value)""".stripMargin
      ) shouldBe "1 5\n"
    }
  }

  "elements that hold something are held and let go" - {
    "a buffer of references reads back what went in" in {
      run(
        """struct Node
          |    value: int
          |var b: &Buf[&Node] = buf()
          |for i in 0..<30 do b.push(Node(i * 3))
          |print(b.len(), b.at(0).value, b.at(29).value)""".stripMargin
      ) shouldBe "30 0 87\n"
    }

    "many grown buffers come apart rather than accumulating" in {
      run(
        """struct Node
          |    value: int
          |var i = 0
          |while i < 20000
          |    var t: &Buf[&Node] = buf()
          |    for k in 0..<10 do t.push(Node(k))
          |    i++
          |print("done")""".stripMargin
      ) shouldBe "done\n"
    }

    "a buffer of buffers" in {
      run(
        """var rows: &Buf[&Buf[int]] = buf()
          |for y in 0..<5
          |    var row: &Buf[int] = buf()
          |    for x in 0..<4 do row.push(y * 10 + x)
          |    rows.push(row)
          |print(rows.len(), rows.at(0).len(), rows.at(4).at(3))""".stripMargin
      ) shouldBe "5 4 43\n"
    }
  }

  /** `extend` and `buf_with_capacity` — appending a run at once, and starting with room for one.
   *
   * Both are about the cost rather than the result, so the tests are written to pin the *result*
   * exactly and to catch the ways a bulk copy can go wrong that a loop of `push` cannot: an
   * off-by-one at the join between what was there and what arrives, a growth that fits the new run
   * but forgets the old elements, and the empty run that has no first element to seed storage with.
   */
  "extending by a whole slice" - {

    "appends every element after the ones already there" in {
      run("""var b: &Buf[int] = buf()
            |b.push(1)
            |b.push(2)
            |b.extend([3, 4, 5])
            |print(b.len(), b.at(0), b.at(2), b.at(4))""".stripMargin) shouldBe "5 1 3 5\n"
    }

    // The discriminating case for the growth: the run is longer than one doubling would give, so an
    // implementation that grew once by a factor of two and then copied would write past the end.
    "grows enough for a run far longer than the current capacity" in {
      run("""var b: &Buf[int] = buf()
            |b.push(7)
            |var xs: [40]int
            |for i in 0..<40 do xs[i] = i
            |b.extend(xs[..])
            |print(b.len(), b.at(0), b.at(1), b.at(40))""".stripMargin) shouldBe "41 7 0 39\n"
    }

    // An empty run has no first element to repeat into new storage, which is the one case the
    // implementation has to leave early for rather than merely handle.
    "an empty run is a no-op rather than a trap" in {
      run("""var b: &Buf[int] = buf()
            |b.push(1)
            |var none: []int = []
            |b.extend(none)
            |print(b.len(), b.at(0))""".stripMargin) shouldBe "1 1\n"
    }

    "extending an empty buffer works the same as filling one" in {
      run("""var b: &Buf[int] = buf()
            |b.extend([9, 8])
            |print(b.len(), b.at(0), b.at(1))""".stripMargin) shouldBe "2 9 8\n"
    }

    // Repeated extends have to keep the geometric growth, or a loop of them is quadratic. What is
    // observable from sysl is that the result is right after many of them; the capacity check
    // below is what says the growth was not exact-fit.
    "many extends in a row keep every element in order" in {
      run("""var b: &Buf[int] = buf()
            |for i in 0..<20 do b.extend([i, i])
            |print(b.len(), b.at(0), b.at(1), b.at(38), b.at(39))""".stripMargin) shouldBe
        "40 0 0 19 19\n"
    }
  }

  "starting with room" - {

    "a buffer with capacity is still empty" in {
      run("""var b: &Buf[int] = buf_with_capacity(32usize, 0)
            |print(b.len(), b.is_empty(), b.cap())""".stripMargin) shouldBe "0 true 32\n"
    }

    // The point of the capacity: pushing up to it does not reallocate, so the capacity is the one
    // it was given rather than a power of two arrived at by doubling from eight.
    "filling up to the capacity does not grow it" in {
      run("""var b: &Buf[int] = buf_with_capacity(32usize, 0)
            |for i in 0..<32 do b.push(i)
            |print(b.len(), b.cap(), b.at(31))""".stripMargin) shouldBe "32 32 31\n"
    }

    "and going past it grows the way any other buffer does" in {
      run("""var b: &Buf[int] = buf_with_capacity(4usize, 0)
            |for i in 0..<5 do b.push(i)
            |print(b.len(), b.cap(), b.at(4))""".stripMargin) shouldBe "5 8 4\n"
    }

    // The fill is a parameter because a generic `T` has no zero, and none of those slots is ever
    // read — `count` is what says which elements are real, so `at` refuses them all.
    "the fill is never visible, since the count starts at zero" in {
      panics(
        """var b: &Buf[int] = buf_with_capacity(8usize, 99)
          |print(b.at(0))""".stripMargin,
        "past the 0 elements",
      )
    }
  }

  "what stops the program" - {
    // Reading past the end is a panic rather than a trap, because a `Buf` is written in sysl and
    // what sysl has to stop with is the library's own `exit` — so it can say what went wrong.
    "reading past the last element says which index and how many there were" in {
      panics(
        """var b: &Buf[int] = buf()
          |b.push(1)
          |print(b.at(3))""".stripMargin,
        "past the 1 elements",
      )
    }

    // The spare capacity is real storage holding real values, so an index inside it would read a
    // copy of whatever seeded the growth rather than failing — which is exactly why `at` checks
    // against the count and not against the slice it indexes.
    "including an index inside the spare capacity" in {
      panics(
        """var b: &Buf[int] = buf()
          |b.push(1)
          |print(b.at(5))""".stripMargin,
        "past the 1 elements",
      )
    }

    "and writing past it likewise" in {
      panics(
        """var b: &Buf[int] = buf()
          |b.push(1)
          |b.set(2, 9)""".stripMargin,
        "past the 1 elements",
      )
    }
  }
}
