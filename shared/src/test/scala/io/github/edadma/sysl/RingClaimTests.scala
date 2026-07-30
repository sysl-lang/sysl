package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The claims `guide/ring` makes that the program itself cannot make.
 *
 * A violated contract **traps**, so a guide program can never be the thing that breaks one — it
 * would end rather than report, and `GuideTests` would see a truncated run instead of a failure.
 * Everything the ring says about what it refuses is therefore asserted here, where a trap is an
 * observation. The shapes below are the ring's own, cut down to the two fields that matter.
 */
class RingClaimTests extends AnyFreeSpec with RunSupport {

  private val decls =
    """const capacity: usize = 8
      |type Slot = new u8 within 0..<capacity
      |type Count = new u8 within 0..capacity
      |wrap(s: Slot) -> Slot = Slot(u8((usize(s) + 1) % capacity))
      |""".stripMargin

  /** `16 §6`: a sequence of writes that ends valid but passes through invalid traps at the step that
   * broke it. `guide/ring`'s whole argument rests on this being true of a ring buffer specifically,
   * so it is measured on one rather than on the chapter's own example.
   */
  private val tracked =
    decls +
      """struct Tracked
        |    head: Slot
        |    tail: Slot
        |    count: Count
        |    invariant usize(tail) == (usize(head) + usize(count)) % capacity
        |""".stripMargin

  /** `Watermark`'s shape, with the body of `take` left open so the two orders can be run against
   * one another.
   */
  private def gauge(body: String) =
    decls +
      """struct Gauge
        |    count: Count
        |    high: Count
        |    invariant usize(count) <= usize(high)
        |    take(*self)
        |""".stripMargin + body +
      """
        |    end take
        |var g = Gauge(Count(0u8), Count(0u8))
        |g.take()
        |print(int(g.count), int(g.high))""".stripMargin

  /** The guide's `Ring`, with its two contracted operations and nothing else. */
  private val ring =
    decls +
      """struct Ring
        |    buf: [capacity]int
        |    head: Slot
        |    count: Count
        |    len(self) -> int = int(self.count)
        |    is_full(self) -> bool = usize(self.count) == capacity
        |    is_empty(self) -> bool = usize(self.count) == 0
        |    push(*self, v: int)
        |        require !self.is_full()
        |        ensure self.len() == old(self.len()) + 1
        |        self.buf[(usize(self.head) + usize(self.count)) % capacity] = v
        |        self.count = Count(u8(usize(self.count) + 1))
        |    end push
        |    pop(*self) -> int
        |        require !self.is_empty()
        |        ensure self.len() == old(self.len()) - 1
        |        var v = self.buf[usize(self.head)]
        |        self.head = wrap(self.head)
        |        self.count = Count(u8(usize(self.count) - 1))
        |        v
        |    end pop
        |    at(self, i: int) -> int
        |        require i >= 0 && i < self.len()
        |        self.buf[(usize(self.head) + usize(i)) % capacity]
        |    end at
        |var r = Ring([0; capacity], Slot(0u8), Count(0u8))
        |""".stripMargin

  "a ring that keeps its end as a field" - {
    // The naive push: move the end, then the count. Perfectly good state on either side of it.
    "traps when the two fields are written one at a time" in {
      exits(tracked + """
        |    push(*self)
        |        self.tail = wrap(self.tail)
        |        self.count = Count(u8(usize(self.count) + 1))
        |    end push
        |var t = Tracked(Slot(0u8), Slot(0u8), Count(0u8))
        |t.push()
        |print(int(t.count))""".stripMargin)
    }

    // And the other order is no better, which is what rules out reordering as the fix here and
    // makes the whole-struct form the only way through.
    "and traps on the other order too" in {
      exits(tracked + """
        |    push(*self)
        |        self.count = Count(u8(usize(self.count) + 1))
        |        self.tail = wrap(self.tail)
        |    end push
        |var t = Tracked(Slot(0u8), Slot(0u8), Count(0u8))
        |t.push()
        |print(int(t.count))""".stripMargin)
    }

    // The form the guide program actually uses. Same two fields, one write.
    "while assigning the whole struct moves both without being seen between them" in {
      run(tracked + """
        |    push(*self)
        |        *self = Tracked(self.head, wrap(self.tail), Count(u8(usize(self.count) + 1)))
        |    end push
        |var t = Tracked(Slot(0u8), Slot(0u8), Count(0u8))
        |t.push()
        |t.push()
        |print(int(t.count), int(t.tail))""".stripMargin) shouldBe "2 2\n"
    }
  }

  /** The ring the invariant argued for: with the end computed rather than stored there is no
   * clause relating two fields, so the same push is one ordinary write.
   */
  "a ring that computes its end has no invariant to break" in {
    run(decls + """
      |struct Ring
      |    head: Slot
      |    count: Count
      |    tail(self) -> Slot = Slot(u8((usize(self.head) + usize(self.count)) % capacity))
      |    push(*self)
      |        self.count = Count(u8(usize(self.count) + 1))
      |    end push
      |var r = Ring(Slot(0u8), Count(0u8))
      |r.push()
      |r.push()
      |print(int(r.count), int(r.tail()))""".stripMargin) shouldBe "2 2\n"
  }

  /** `Watermark`'s case: two fields, one invariant, and an order in which no intermediate state is
   * ever illegal. The pair is the point — the same clause traps under one order and not the other,
   * so "reorder the writes" is a real answer and not a hope.
   */
  "two fields where one order is legal and the other is not" - {
    "raising the ceiling first keeps every state legal" in {
      run(gauge(
        """        var n = Count(u8(usize(self.count) + 1))
          |        if usize(n) > usize(self.high) then self.high = n
          |        self.count = n""".stripMargin)) shouldBe "1 1\n"
    }

    "raising the floor first does not" in {
      exits(gauge(
        """        var n = Count(u8(usize(self.count) + 1))
          |        self.count = n
          |        if usize(n) > usize(self.high) then self.high = n""".stripMargin))
    }
  }

  /** The contracts the ring writes, which for the same reason no check inside it can violate. */
  "the ring's contracts refuse the calls they say they refuse" - {
    "pushing onto a full ring is refused" in {
      exits(ring + "for i in 0..<9 do r.push(int(i))\nprint(r.len())")
    }

    "and a whole capacity is not" in {
      run(ring + "for i in 0..<8 do r.push(int(i))\nprint(r.len())") shouldBe "8\n"
    }

    "taking from an empty ring is refused" in {
      exits(ring + "print(r.pop())")
    }

    "and taking from one that has something is not" in {
      run(ring + "r.push(7)\nprint(r.pop(), r.len())") shouldBe "7 0\n"
    }

    // `at`'s precondition is about the elements that are *there*, not about the buffer — which is
    // the distinction a ring buffer exists to keep, since every slot of the buffer is a real slot
    // whether or not anything has been put in it.
    "reading past the last element is refused although the slot exists" in {
      exits(ring + "r.push(1)\nr.push(2)\nprint(r.at(2))")
    }

    "and reading the last one is not" in {
      run(ring + "r.push(1)\nr.push(2)\nprint(r.at(0), r.at(1))") shouldBe "1 2\n"
    }

    // The other end of the same contract, which a bare `i < len` would let through.
    "a negative index is refused" in {
      exits(ring + "r.push(1)\nprint(r.at(-1))")
    }
  }
}
