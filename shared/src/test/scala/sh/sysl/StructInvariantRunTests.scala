package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A struct's `invariant` clauses are checked whenever a value of it is built: an in-range
 * construction proceeds, an out-of-range one traps. Several clauses all have to hold; an invariant
 * may read a module constant; and the check reaches through the ordinary construction path, so a
 * struct built to be returned or passed is checked just the same.
 */
class StructInvariantRunTests extends AnyFreeSpec with RunSupport {

  private val Account =
    """|struct Account
       |    balance: int
       |    invariant balance >= 0
       |""".stripMargin

  /** A clause that reads through a field. `Inner` carries none of its own, so it is `Outer`'s
   * invariant or nothing — which is what makes `o.a.n` the discriminating write.
   */
  private val Outer =
    """|struct Inner
       |    n: int
       |struct Outer
       |    a: Inner
       |    b: int
       |    invariant a.n <= b
       |""".stripMargin

  /** The same, with an array between the struct and the field the clause reads. */
  private val Grid =
    """|struct Cell
       |    n: int
       |struct Grid
       |    items: [2]Cell
       |    cap: int
       |    invariant items[0].n <= cap
       |""".stripMargin

  "a construction that satisfies the invariant proceeds" in {
    run(Account + "var a = Account(10)\nprint(a.balance)") shouldBe "10\n"
  }

  "a construction that violates the invariant traps" in {
    exits(Account + "var a = Account(-1)\nprint(a.balance)")
  }

  "the boundary value is allowed" in {
    run(Account + "var a = Account(0)\nprint(a.balance)") shouldBe "0\n"
  }

  "whole-struct reassignment is re-checked" - {
    "an in-range reassignment proceeds" in {
      run(Account + "var a = Account(5)\na = Account(7)\nprint(a.balance)") shouldBe "7\n"
    }
    "an out-of-range reassignment traps" in {
      exits(Account + "var a = Account(5)\na = Account(-3)")
    }
  }

  "several invariants must all hold" - {
    val span =
      """|struct Span
         |    lo: int
         |    hi: int
         |    invariant lo <= hi
         |    invariant lo >= 0
         |""".stripMargin

    "all satisfied proceeds" in {
      run(span + "var r = Span(2, 5)\nprint(r.lo, r.hi)").shouldBe("2 5\n")
    }
    "the first clause failing traps" in {
      exits(span + "var r = Span(5, 2)")
    }
    "the second clause failing traps" in {
      exits(span + "var r = Span(-1, 5)")
    }
  }

  "an invariant may read a module constant" in {
    val src =
      """|const LIMIT: int = 100
         |struct Capped
         |    n: int
         |    invariant n <= LIMIT
         |""".stripMargin
    run(src + "var c = Capped(100)\nprint(c.n)") shouldBe "100\n"
    exits(src + "var c = Capped(101)")
  }

  "a struct built to be returned is checked" in {
    val src =
      Account +
        """|make(n: int) -> Account = Account(n)
           |print(make(4).balance)""".stripMargin
    run(src) shouldBe "4\n"
  }

  "a struct built as an argument is checked" in {
    exits(Account + "bal(a: Account) -> int = a.balance\nprint(bal(Account(-2)))")
  }

  "a float invariant traps on violation" in {
    val src =
      """|struct Prob
         |    p: f64
         |    invariant p >= 0.0 && p <= 1.0
         |""".stripMargin
    run(src + "var x = Prob(0.5)\nprint(x.p)") shouldBe "0.5\n"
    exits(src + "var x = Prob(1.5)")
  }

  "a field assignment re-checks the invariant" - {
    "an in-range write proceeds" in {
      run(Account + "var a = Account(5)\na.balance = 8\nprint(a.balance)") shouldBe "8\n"
    }
    "an out-of-range write traps" in {
      exits(Account + "var a = Account(5)\na.balance = -1")
    }
  }

  "a compound field assignment re-checks the invariant" - {
    "an in-range update proceeds" in {
      run(Account + "var a = Account(5)\na.balance += 3\nprint(a.balance)") shouldBe "8\n"
    }
    "an update that drops below the bound traps" in {
      exits(Account + "var a = Account(5)\na.balance -= 9")
    }
  }

  /** An increment is a write of one field, so it owes the same re-check the compound form owes. It
   * used not to: `++` was the one write that reached a field without going through the wrapper, so a
   * `--` off the bottom of a bound left the struct holding a value its own invariant forbids.
   */
  "an increment of a field re-checks the invariant" - {
    "a step that keeps the invariant proceeds" in {
      run(Account + "var a = Account(5)\na.balance++\nprint(a.balance)") shouldBe "6\n"
    }
    "a step that breaks it traps" in {
      exits(Account + "var a = Account(0)\na.balance--")
    }
    "and so does one written prefix" in {
      exits(Account + "var a = Account(0)\n--a.balance")
    }
    "an increment through a pointer is checked too" in {
      exits(Account + "var a = Account(0)\nvar p = &a\n(*p).balance--")
    }
  }

  "a field write through a pointer is checked" in {
    exits(Account + "var a = Account(5)\nvar p = &a\n(*p).balance = -2")
  }

  // The walk survives a pointer because the pointee's type names the struct: `(*p).a.n` reaches
  // `Outer`'s clause through `p`'s static type exactly as `o.a.n` reaches it through `o`. What no
  // pointer can do is name a struct *above* its own pointee, which is why a pointer that would be
  // typed below one is refused where it is made — `StructInvariantErrorTests` has that half.
  "a nested field write through a pointer to the enclosing struct is checked" - {
    "a write that keeps the invariant proceeds" in {
      run(Outer + "var o = Outer(Inner(1), 5)\nvar p = &o\n(*p).a.n = 4\nprint(o.a.n, o.b)") shouldBe "4 5\n"
    }
    "a write that breaks it traps" in {
      exits(Outer + "var o = Outer(Inner(1), 5)\nvar p = &o\n(*p).a.n = 9")
    }
  }

  "a field write into an array element is checked" - {
    "an in-range write proceeds" in {
      run(Account + "var xs = [Account(1), Account(2)]\nxs[0].balance = 5\nprint(xs[0].balance)") shouldBe "5\n"
    }
    "an out-of-range write traps" in {
      exits(Account + "var xs = [Account(1), Account(2)]\nxs[1].balance = -1")
    }
  }

  // Every field write is its own checkpoint: a sequence in which each intermediate state holds
  // proceeds, but a single write that breaks the invariant traps even if a later write would mend it.
  "each field write is checked as it happens" - {
    val span =
      """|struct Span
         |    lo: int
         |    hi: int
         |    invariant lo <= hi
         |""".stripMargin
    "a sequence whose every step holds proceeds" in {
      run(span + "var s = Span(2, 8)\ns.hi = 5\ns.lo = 4\nprint(s.lo, s.hi)") shouldBe "4 5\n"
    }
    "an intermediate write that breaks the invariant traps at that write" in {
      exits(span + "var s = Span(2, 8)\ns.lo = 10\ns.hi = 12")
    }
  }

  // `reference/errors.md § What the type's own name offers: :: attributes`. A struct held as a
  // field of another struct is still constructed, so its invariant is checked where it is built —
  // the outer struct's construction is not a way in.
  "a struct inside a struct is checked as it is built" - {
    val nested =
      """struct Bounded
        |    n: int
        |    invariant n >= 0
        |struct Holder
        |    b: Bounded
        |""".stripMargin

    "a satisfied inner value proceeds" in {
      run(nested + "var h = Holder(Bounded(5))\nprint(h.b.n)") shouldBe "5\n"
    }
    "and a violated one traps" in {
      exits(nested + "var h = Holder(Bounded(-1))\nprint(h.b.n)")
    }
  }

  // `*self = T(...)` is the escape §6 offers a struct that cannot be updated one field at a time,
  // so it had better be checked — an escape from the ordering, not from the invariant.
  "a whole-struct assignment through self is checked" - {
    val span =
      """|struct Span
         |    lo: int
         |    hi: int
         |    invariant lo <= hi
         |    widen(*self)
         |        *self = Span(self.lo, self.hi + 1)
         |    end widen
         |    wreck(*self)
         |        *self = Span(9, 5)
         |    end wreck
         |end Span
         |""".stripMargin

    "one that holds proceeds" in {
      run(span + "var s = Span(2, 8)\ns.widen()\nprint(s.lo, s.hi)") shouldBe "2 9\n"
    }
    "one that does not traps" in {
      exits(span + "var s = Span(2, 8)\ns.wreck()")
    }
  }

  // Assigning a struct-typed field is a construction, and a construction is already a produce site —
  // so the inner value is judged on its way in, before the field it lands in is considered.
  "a struct assigned into a field is checked as the value it is" in {
    val nested =
      """|struct Bounded
         |    n: int
         |    invariant n >= 0
         |struct Holder
         |    b: Bounded
         |""".stripMargin

    run(nested + "var h = Holder(Bounded(1))\nh.b = Bounded(5)\nprint(h.b.n)") shouldBe "5\n"
    exits(nested + "var h = Holder(Bounded(1))\nh.b = Bounded(-1)")
  }

  // An invariant may read *through* a field, and then the field it depends on is not one of the
  // struct's own — so the write that breaks it is a write the struct never sees. The check is owed
  // by every struct a place is written inside, not only by the one whose field is named last.
  "an invariant that reads through a field is checked when the nested field changes" - {
    "a write that keeps it proceeds" in {
      run(Outer + "var o = Outer(Inner(1), 5)\no.a.n = 4\nprint(o.a.n, o.b)") shouldBe "4 5\n"
    }

    // The write the enclosing struct would otherwise never hear about: `a.n` is not `Outer`'s field
    // and `Inner` has no clause of its own, so before the walk went outward nothing checked this.
    "a write that breaks it traps" in {
      exits(Outer + "var o = Outer(Inner(1), 5)\no.a.n = 9")
    }

    // The two ways the enclosing struct *was* already reached, kept beside the new one so a
    // regression that narrows the walk again shows up as the pair disagreeing.
    "as are the whole-field and plain-field writes that always were" in {
      exits(Outer + "var o = Outer(Inner(1), 5)\no.a = Inner(9)")
      exits(Outer + "var o = Outer(Inner(1), 5)\no.b = 0")
    }

    // `reference/expressions.md § Several places at once` promises every write lands before any invariant is consulted, and that has to survive
    // the walk: each arm alone would be refused, and the pair is what the form is for.
    "and a multi-assignment is judged only once both writes have landed" in {
      run(Outer + "var o = Outer(Inner(1), 5)\no.a.n, o.b = 9, 9\nprint(o.a.n, o.b)") shouldBe "9 9\n"
    }

    // The other half of the walk: the innermost struct's own clause still fires through a nested
    // place, so widening the walk added a check rather than moving the one that was there.
    "while the nested struct's own clause still fires through the same place" in {
      val own =
        """|struct Bounded
           |    n: int
           |    invariant n >= 0
           |struct Box
           |    b: Bounded
           |    tag: int
           |""".stripMargin

      run(own + "var x = Box(Bounded(1), 0)\nx.b.n = 5\nprint(x.b.n)") shouldBe "5\n"
      exits(own + "var x = Box(Bounded(1), 0)\nx.b.n = -1")
    }
  }

  // The same shape with an array in the middle. An index locates a place rather than owning one, so
  // it contributes no check of its own and the walk passes through it to the struct that does.
  "an invariant that reads through an array element is checked when that element changes" - {
    "a write that keeps it proceeds" in {
      run(Grid + "var g = Grid([Cell(0), Cell(0)], 5)\ng.items[0].n = 4\nprint(g.items[0].n)") shouldBe "4\n"
    }
    "a write that breaks it traps" in {
      exits(Grid + "var g = Grid([Cell(0), Cell(0)], 5)\ng.items[0].n = 9")
    }

    // An element the clause does not read is still re-checked, since the clause is re-run whole
    // rather than tracked field by field — which costs a check and can never miss one.
    "and an element the clause does not read is permitted" in {
      run(Grid + "var g = Grid([Cell(0), Cell(0)], 5)\ng.items[1].n = 9\nprint(g.items[1].n)") shouldBe "9\n"
    }
  }

  /* A `*self` method reached through a field is the one severed place left, since there are no
   * parameter modes and so no other way to hand a callee somewhere to write without writing `&`.
   * It is allowed rather than refused, because the **call site** still knows the whole place: `o.a`
   * is the receiver, `o` is right there, and the clause is re-run the moment the call returns. So
   * the method mutates an `Inner` knowing nothing of any `Outer`, and the promise is kept at the
   * boundary instead of inside.
   */
  "a mutating method call on a field an invariant reads is re-checked at the call" - {
    val Bumping =
      """|struct Inner
         |    n: int
         |
         |    set(*self, v: int)
         |        self.n = v
         |struct Outer
         |    a: Inner
         |    b: int
         |    invariant a.n <= b
         |""".stripMargin

    "a call that leaves the invariant true proceeds" in {
      run(Bumping + "var o = Outer(Inner(1), 5)\no.a.set(4)\nprint(o.a.n, o.b)") shouldBe "4 5\n"
    }

    "the boundary value is allowed" in {
      run(Bumping + "var o = Outer(Inner(1), 5)\no.a.set(5)\nprint(o.a.n)") shouldBe "5\n"
    }

    "a call that breaks it traps" in {
      exits(Bumping + "var o = Outer(Inner(1), 5)\no.a.set(9)")
    }

    // The same call on a receiver that lies below nothing is checked by nobody, which is what "pay
    // as you promise" means here: the re-check is attached to the place, not to the method.
    "and the same method on a free value is untouched" in {
      run(Bumping + "var i = Inner(1)\ni.set(9)\nprint(i.n)") shouldBe "9\n"
    }

    // Two levels down, where the struct that owns the clause is neither the receiver nor its
    // immediate owner — the walk out of the place is what finds it, so depth costs nothing.
    "a receiver two fields below the clause is found by the same walk" in {
      val deep =
        """|struct Inner
           |    n: int
           |
           |    set(*self, v: int)
           |        self.n = v
           |struct Middle
           |    i: Inner
           |struct Top
           |    m: Middle
           |    cap: int
           |    invariant m.i.n <= cap
           |""".stripMargin

      run(deep + "var t = Top(Middle(Inner(1)), 5)\nt.m.i.set(4)\nprint(t.m.i.n)") shouldBe "4\n"
      exits(deep + "var t = Top(Middle(Inner(1)), 5)\nt.m.i.set(9)")
    }
  }

  /* What the rule leaves alone. An alias has to be refused only where its type stops carrying a
   * promise the storage has, so the address of a field no clause reads, a view that may not write,
   * and a method of a struct that lies inside nothing are all ordinary.
   */
  "an alias that carries every promise its storage has is left alone" - {
    val Spare =
      """|struct Inner
         |    n: int
         |struct Outer
         |    a: Inner
         |    b: int
         |    c: int
         |    invariant a.n <= b
         |""".stripMargin

    "a pointer to a field no clause reads" in {
      run(Spare + "bump(p: *int)\n    *p = 9\nvar o = Outer(Inner(1), 5, 0)\nbump(&o.c)\nprint(o.c)") shouldBe "9\n"
    }

    "a read-only view of an array a clause reads" in {
      val grid =
        """|struct Grid
           |    items: [2]int
           |    cap: int
           |    invariant items[0] <= cap
           |""".stripMargin

      run(grid + "var g = Grid([1, 2], 5)\nvar v: []const int = g.items[0..<2]\nprint(v[1])") shouldBe "2\n"
    }

    // A view's `len` is the struct's own three words rather than somebody else's elements, so a
    // clause may read it — the one thing on the near side of a view hop.
    "a clause reading a view's length" in {
      run("struct Buf\n    xs: []int\n    invariant xs.len > 0\nvar b = Buf([1, 2, 3])\nprint(b.xs.len)") shouldBe "3\n"
    }

    // A string is immutable, so a view of one carries no licence to write and there is no promise
    // for it to drop — which is why the refusal above is about writable views rather than views.
    "part of a string field a clause reads" in {
      val named =
        """|struct Named
           |    name: string
           |    invariant name.len >= 2
           |""".stripMargin

      run(named + "var n = Named(\"abcd\")\nprint(n.name[0..<2])") shouldBe "ab\n"
    }

    // The refinement the library forced: a view field points somewhere else, so the elements are not
    // the receiver's storage to lose and a pointer at one is not a pointer into the receiver. This is
    // the shape of `CString.ptr`, and a rule about every pointer a `*self` method returns would have
    // taken it away.
    "a '*self' method handing out a pointer past a view hop" in {
      val viewing =
        """|struct Cell
           |    bytes: []u8
           |    n: int
           |
           |    at(*self) -> *u8
           |        &self.bytes[0]
           |struct Outer
           |    a: Cell
           |    b: int
           |    invariant a.n <= b
           |""".stripMargin

      run(viewing + "var o = Outer(Cell([7, 8], 1), 5)\nvar p = o.a.at()\nprint(*p)") shouldBe "7\n"
    }

    // A struct that is nobody's field can never be a severed receiver, so its methods may hand out
    // pointers into it however they like — even in a program where some *other* struct has a clause.
    "a '*self' method of a struct that lies inside nothing" in {
      val free =
        """|struct Guarded
           |    m: int
           |    invariant m >= 0
           |struct Free
           |    n: int
           |
           |    at(*self) -> *int
           |        &self.n
           |""".stripMargin

      run(free + "var g = Guarded(1)\nvar f = Free(1)\nvar p = f.at()\n*p = 9\nprint(f.n, g.m)") shouldBe "9 1\n"
    }
  }
}
