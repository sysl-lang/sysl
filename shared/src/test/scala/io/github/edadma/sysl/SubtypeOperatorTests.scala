package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A constrained subtype has its base's whole operator catalog, and every value the catalog makes of
 * one is held to what the type says its values are (`16 §1`, `§3`, `§4`).
 *
 * The two flavours part company over *where* the check lands, and every test here is written so
 * that the two cannot be confused. A **transparent** subtype computes at its base, so `-a` and
 * `a + b` are base values that no range applies to, and what gets checked is the store that gives
 * one the subtype again. A **derived** subtype computes at itself, so the operation is the produce
 * site and traps there. Reading the same expression two ways is what tells a correct compiler from
 * one that checks the wrong end.
 */
class SubtypeOperatorTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val Age  = "type Age = int within 0..150\n"
  private val Temp = "type Temp = int within -100..100\n"
  private val Mask = "type Mask = u8 within 0..15\n"

  private val Slot  = "type Slot = new u8 within 0..<200\n"
  private val Stamp = "type Stamp = new i64\n"

  /** `16 §1` — a transparent subtype is *the same type as* its base, so an operator on one is the
   * base's operator on base values, and its result is a base value with no range of its own.
   */
  "a transparent subtype computes at its base" - {
    "unary minus yields a base value, so a result outside the range is not a violation" in {
      run(Age + "var a: Age = 30\nprint(-a)") shouldBe "-30\n"
    }
    "and the same value stored back into the subtype is the produce site that traps" in {
      exits(Age + "var a: Age = 30\nvar b: Age = -a\nprint(b)")
    }

    "bitwise complement likewise yields the base" in {
      run(Mask + "var m: Mask = 5\nprint(~m)") shouldBe "250\n"
    }
    "and storing that back traps" in {
      exits(Mask + "var m: Mask = 5\nvar n: Mask = ~m\nprint(n)")
    }

    // A literal beside one takes the base too, for the same reason: it is about to be an operand of
    // the base's operator. Reading it as the subtype refuses `s * 100` on a `0..10` for what the
    // multiplier is, when the product is the thing that has to be in range — and here it is not, so
    // the trap has to come from the store and name that.
    "a literal beside one is a base value, not a value of the subtype" in {
      run("type Small = int within 0..10\nvar s: Small = 2\nprint(s * 100)") shouldBe "200\n"
    }
    "and a comparison against a literal outside the range is an ordinary comparison" in {
      run("type Small = int within 0..10\nvar s: Small = 2\nprint(s < 100, s > 100)") shouldBe "true false\n"
    }
    // The same reading as arithmetic's, which had it and comparison did not: one is a comparison of
    // two integers, because a transparent subtype *is* its base.
    "a comparison against a base-typed value it did not take its type from" in {
      run(Age + "var a: Age = 5\nvar n: int = 7\nprint(a < n, a == n, a >= n)") shouldBe
        "true false false\n"
    }
    "while a derived subtype compares only with itself" in {
      err(Slot + "var k: Slot = Slot(5)\nvar n: u8 = 7\nprint(k < n)") should
        include("cannot compare Slot with byte")
    }
    "while storing such a product back is what traps" in {
      exits("type Small = int within 0..10\nvar s: Small = 2\ns = s * 100\nprint(s)")
    }

    "the integer operators the base has all reach it" in {
      run(Age + "var a: Age = 12\nvar b: Age = 5\nprint(a + b, a - b, a * b, a / b, a % b)") shouldBe
        "17 7 60 2 2\n"
    }
    "including the bitwise ones and the shifts" in {
      run(Mask + "var m: Mask = 12\nvar n: Mask = 5\nprint(m & n, m | n, m ^ n, m << n, m >> n)") shouldBe
        "4 13 9 128 0\n"
    }
  }

  /** `16 §4` lists an assignment as a produce site; a compound assignment is one, and it is the same
   * one — `a += e` gives the place what `a = a + e` gives it, so the two must agree exactly.
   */
  "a compound assignment is the produce site its written-out form is" - {
    "an in-range result holds" in {
      run(Age + "var a: Age = 10\na += 5\nprint(a)") shouldBe "15\n"
    }
    "and the same statement written out gives the same answer" in {
      run(Age + "var a: Age = 10\na = a + 5\nprint(a)") shouldBe "15\n"
    }
    "a result past the top traps" in {
      exits(Age + "var a: Age = 100\na += 100\nprint(a)")
    }
    "a result below the bottom traps" in {
      exits(Age + "var a: Age = 10\na -= 100\nprint(a)")
    }

    // The discriminating case for what the right-hand side is read as. 120 is not a temperature, but
    // it is a perfectly ordinary number to add to one, and the sum is in range — so a compiler that
    // checks the addend rather than the total traps here and is wrong.
    "the value added is an ordinary base value, not one of the subtype's own" in {
      run(Temp + "var t: Temp = -50\nt += 120\nprint(t)") shouldBe "70\n"
    }
    "which is again what the written-out form does" in {
      run(Temp + "var t: Temp = -50\nt = t + 120\nprint(t)") shouldBe "70\n"
    }

    "every compound form the base has is accepted" in {
      run(Mask + "var m: Mask = 12\nm &= 5\nm |= 2\nm ^= 1\nprint(m)") shouldBe "7\n"
    }
    "and each of them checks its result" in {
      exits(Mask + "var m: Mask = 1\nm <<= 5\nprint(m)")
    }

    "a compound assignment through a pointer checks the place it points at" in {
      run(Age + "var a: Age = 10\nvar p = &a\n*p += 5\nprint(a)") shouldBe "15\n"
    }
    "and traps when the result leaves the range" in {
      exits(Age + "var a: Age = 10\nvar p = &a\n*p -= 20\nprint(a)")
    }

    "a compound write into a struct field checks it" in {
      val P = Age + "struct P\n    age: Age\n"

      run(P + "var p = P(Age(40))\np.age += 1\nprint(p.age)") shouldBe "41\n"
      exits(P + "var p = P(Age(150))\np.age += 1\nprint(p.age)")
    }

    "a compound arm of a multi-assignment checks its own result" in {
      run(Age + "var a: Age = 10\nvar b: Age = 20\na, b += 5, 5\nprint(a, b)") shouldBe "15 25\n"
    }
    "and one arm out of range stops the statement" in {
      exits(Age + "var a: Age = 10\nvar b: Age = 150\na, b += 5, 5\nprint(a, b)")
    }
  }

  "an increment steps one and is checked like any other value produced" - {
    "postfix yields the old value and stores the new one" in {
      run(Age + "var a: Age = 10\nprint(a++)\nprint(a)") shouldBe "10\n11\n"
    }
    "prefix yields the new one" in {
      run(Age + "var a: Age = 10\nprint(++a)") shouldBe "11\n"
    }
    "a step onto the last value of the range is fine" in {
      run(Age + "var a: Age = 149\na++\nprint(a)") shouldBe "150\n"
    }
    "and the step past it traps" in {
      exits(Age + "var a: Age = 150\na++\nprint(a)")
    }
    "a decrement off the bottom traps" in {
      exits(Age + "var a: Age = 0\na--\nprint(a)")
    }
    // A postfix increment's *value* is the old one, so a compiler could plausibly check that and let
    // the stored value through. What has to be checked is what lands in the place.
    "a postfix step past the end traps even though its value was in range" in {
      exits(Age + "var a: Age = 150\nprint(a++)")
    }
    "an increment through a pointer traps at the same place" in {
      exits(Age + "var a: Age = 150\nvar p = &a\n(*p)++\nprint(a)")
    }
  }

  /** `16 §3` — a derivation arrives with everything the scalar could do, *working at itself and
   * producing itself*. Producing itself is what makes each operation a produce site of `§4`.
   */
  "a derived subtype computes at itself, so the operation is the produce site" - {
    "an in-range sum is the derived type" in {
      run(Slot + "var j: Slot = Slot(100)\nvar k: Slot = Slot(50)\nprint(j + k)") shouldBe "150\n"
    }
    "a sum past the top traps at the addition, not at some later store" in {
      exits(Slot + "var j: Slot = Slot(199)\nvar k: Slot = Slot(1)\nprint(j + k)")
    }
    "a difference below the bottom traps" in {
      exits(Slot + "var j: Slot = Slot(1)\nvar k: Slot = Slot(2)\nprint(j - k)")
    }
    "unary minus on a derived type produces one and is checked" in {
      val Off = "type Off = new i32 within -10..5\n"

      run(Off + "print(-Off(5))") shouldBe "-5\n"
      exits(Off + "print(-Off(-10))")
    }
    "and so is the complement" in {
      val Nib = "type Nib = new u8 within 0..15\n"

      exits(Nib + "print(~Nib(5))")
    }

    "a compound assignment on one is checked" in {
      run(Slot + "var k: Slot = Slot(100)\nk += Slot(50)\nprint(k)") shouldBe "150\n"
      exits(Slot + "var k: Slot = Slot(199)\nk += Slot(1)\nprint(k)")
    }
    "and an increment" in {
      run(Slot + "var k: Slot = Slot(100)\nk++\nprint(k)") shouldBe "101\n"
      exits(Slot + "var k: Slot = Slot(199)\nk++\nprint(k)")
    }

    // The 100,000 iterations are what an assertion about emitted text cannot stand in for: a check
    // emitted once outside a loop, or a slot written before it is examined, both pass a single step.
    "the check holds for every step of a long walk, and stops it at the end" in {
      exits(Slot + "var k: Slot = Slot(0)\nfor i in 0..<100000\n    k++\nprint(k)")
    }
  }

  /** A derivation exists to be a distinct name as often as to be a narrowing (`16 §2`), and one that
   * narrows nothing must cost nothing — otherwise every `new i64` pays for a range it does not have.
   */
  "a derived subtype with no range asks nothing of its values" - {
    "its arithmetic runs unchecked" in {
      run(Stamp + "var a: Stamp = Stamp(3)\nvar b: Stamp = Stamp(4)\nprint(a + b, a * b, -a)") shouldBe
        "7 12 -3\n"
    }
    "and emits no check at all" in {
      ir(Stamp + "var a: Stamp = Stamp(3)\nprint(a + a)") should not include "within."
    }
    "while the one with a range emits them" in {
      ir(Slot + "var k: Slot = Slot(3)\nprint(k + k)") should include("within.")
    }
  }

  /** A `where` predicate is a call rather than a compare, so the forms that compute and store in one
   * step have to reach it too — and the function it calls has to survive the pruning pass.
   */
  "a where predicate is asked by the forms that compute and store" - {
    val Even = "type Even = int within 0..100 where value % 2 == 0\n"

    "a compound assignment landing on an even number holds" in {
      run(Even + "var e: Even = 4\ne += 2\nprint(e)") shouldBe "6\n"
    }
    "and one landing on an odd number traps" in {
      exits(Even + "var e: Even = 4\ne += 1\nprint(e)")
    }
    "an increment lands on an odd number and traps" in {
      exits(Even + "var e: Even = 4\ne++\nprint(e)")
    }
    "two increments land back on an even one" in {
      run(Even + "var e: Even = 4\ne += 1 + 1\nprint(e)") shouldBe "6\n"
    }
    // The predicate is a synthesised function, and a call to one the pruning pass dropped would not
    // link. Running the program is what proves the definition is still there.
    "the predicate the update calls is emitted beside the call" in {
      val out = ir(Even + "var e: Even = 4\ne += 2\nprint(e)")

      out should include("within.")
      out should include("where.")
    }
  }

  /** The corners of the two forms that compute and store: where the place is something other than a
   * plain local, where the subtype is something other than a two-sided integer range, and where a
   * second check has an opinion about the same write.
   */
  "the corners of a computed store" - {
    "a field of a struct carrying an invariant is checked against both" in {
      val Span = Age + "struct Span\n    lo: Age\n    hi: Age\n    invariant lo <= hi\n"

      run(Span + "var s = Span(Age(3), Age(9))\ns.lo += 2\nprint(s.lo, s.hi)") shouldBe "5 9\n"
      exits(Span + "var s = Span(Age(9), Age(9))\ns.lo += 1\nprint(s.lo, s.hi)")
    }
    // An increment is a write of one field, which is what the invariant rule is about — so it owes
    // the same re-check the compound form owes, and for the same reason.
    "and an increment of one owes the invariant the same re-check" in {
      val Span = Age + "struct Span\n    lo: Age\n    hi: Age\n    invariant lo <= hi\n"

      run(Span + "var s = Span(Age(3), Age(9))\ns.hi++\nprint(s.lo, s.hi)") shouldBe "3 10\n"
      exits(Span + "var s = Span(Age(9), Age(9))\ns.lo++\nprint(s.lo, s.hi)")
    }

    "an element of an array is a place like any other" in {
      run(Age + "var xs: [2]Age = [1, 2]\nxs[0]++\nxs[1] += 5\nprint(xs[0], xs[1])") shouldBe "2 7\n"
      exits(Age + "var xs: [2]Age = [150, 2]\nxs[0]++\nprint(xs[0])")
    }

    // A check emitted once outside the loop would pass this, and so would one that examined the slot
    // before the addition rather than after it.
    "a loop's worth of steps is checked at every step" in {
      run(Age + "var a: Age = 0\nfor i in 0..<10\n    a += 1\nprint(a)") shouldBe "10\n"
      exits(Age + "var a: Age = 0\nfor i in 0..<200\n    a += 1\nprint(a)")
    }

    "two different subtypes over one base meet at the base" in {
      run(Age + "type Small = int within 0..10\nvar a: Age = 5\nvar b: Small = 3\na += b\nprint(a)") shouldBe "8\n"
    }

    "a subtype with a predicate and no range is checked by the predicate alone" in {
      val Even = "type Even = int where value % 2 == 0\n"

      run(Even + "var e: Even = 4\ne += 2\nprint(e)") shouldBe "6\n"
      exits(Even + "var e: Even = 4\ne += 1\nprint(e)")
    }
    // Odd numbers are closed under multiplication and not under addition, so which operation was
    // written decides whether the predicate holds — which is exactly what a check on the *result*
    // means, and what a check on the operands could never express.
    "and a derived one with a predicate and no range checks its own arithmetic" in {
      val Odd = "type Odd = new int where value % 2 == 1\n"

      run(Odd + "print(Odd(3) * Odd(3))") shouldBe "9\n"
      exits(Odd + "print(Odd(3) + Odd(1))")
    }

    "a float subtype's compound assignment compares in the base's precision" in {
      val Prob = "type Prob = f64 within 0.0..<1.0\n"

      run(Prob + "var p: Prob = 0.25\np += 0.5\nprint(p)") shouldBe "0.75\n"
      exits(Prob + "var p: Prob = 0.75\np += 0.5\nprint(p)")
    }

    "an increment through a pointer to a derived subtype checks it" in {
      run(Slot + "var k: Slot = Slot(5)\nvar p = &k\n(*p)++\nprint(k)") shouldBe "6\n"
      exits(Slot + "var k: Slot = Slot(199)\nvar p = &k\n(*p)++\nprint(k)")
    }

    "a multi-assignment of derived subtypes checks each arm" in {
      run(Slot + "var j: Slot = Slot(5)\nvar k: Slot = Slot(6)\nj, k += Slot(1), Slot(2)\nprint(j, k)") shouldBe
        "6 8\n"
      exits(Slot + "var j: Slot = Slot(5)\nvar k: Slot = Slot(199)\nj, k += Slot(1), Slot(1)\nprint(j, k)")
    }
  }

  "what the base does not have, the subtype does not either" - {
    "unary minus stays refused over an unsigned base, and names the subtype" in {
      err(Mask + "var m: Mask = 5\nprint(-m)") should include("unary '-' is not defined for the unsigned type Mask")
    }
    "the complement stays refused over a float base" in {
      err("type Prob = f64 within 0.0..1.0\nvar p: Prob = 0.5\nprint(~p)") should
        include("unary '~' is not defined for Prob")
    }
    "an increment stays refused over a float base" in {
      err("type Prob = f64 within 0.0..1.0\nvar p: Prob = 0.5\np++") should
        include("'++' is not defined for Prob")
    }
    "and over a char base" in {
      err("type Letter = char within 'a'..'z'\nvar c: Letter = 'q'\nc++") should
        include("'++' is not defined for Letter")
    }
    // The two flavours do not mix without a cast (`16 §2`), and a compound form is not a way around
    // that: what it would change is the type of the place, which is the thing the message names.
    "a compound assignment may still not change the type of its place" in {
      err(Slot + "var k: Slot = Slot(1)\nk += 1") should include("needs matching types")
    }
  }

  /** The catalog being the base's is what `16 §3` calls inheriting it, and the other half of that
   * ruling is that no `impl` may replace a row of it. Both halves turn on one predicate — whether the
   * compiler provides the operator for this type — so a change that widens the catalog is a change to
   * what may be written over it, and these say which way.
   */
  "the catalog is inherited and cannot be replaced" - {
    "an operator trait the compiler provides may not be implemented for a derived subtype" in {
      err(Stamp + "impl Add for Stamp\n    add(self, other: Stamp) -> Stamp\n        self") should
        include("'Stamp' already implements 'Add' — the compiler provides it")
    }
    "and neither may Display" in {
      err(Stamp + "impl Display for Stamp\n    display(self, out: &Writer, fmt: string)\n        out.write(\"x\")") should
        include("already implements 'Display'")
    }
    "nor for a transparent one, which is its base and has the base's rows" in {
      err(Age + "impl Add for Age\n    add(self, other: Age) -> Age\n        self") should
        include("already implements 'Add'")
    }
    // A trait of the program's own is not part of the catalog, so it is ordinary — which is what
    // keeps the ruling about the *base's* operations rather than about derived types generally.
    "while a trait the program declares is ordinary" in {
      run(Stamp + "trait Doubler\n    twice(self) -> int\nimpl Doubler for Stamp\n" +
        "    twice(self) -> int\n        int(self) * 2\nprint(Stamp(4).twice())") shouldBe "8\n"
    }
  }

  /** The other side of inheriting the catalog (`14 §5`): a membership is what a **bound** is
    * satisfied by, so a subtype reaches a generic written over the base's traits. The tests above
    * pin that the *operators* reach a subtype; a bound is a different question, since it asks
    * whether the type system agrees the subtype is a member rather than whether a token lowers.
    */
  "a subtype satisfies the bounds its base satisfies" - {
    "a transparent one reaches an operator bound, computing at its base" in {
      run(Age + "twice[T: Mul](x: T) -> T = x * x\nvar a: Age = 7\nprint(int(twice(a)))") shouldBe "49\n"
    }

    "and the several bounds a generic asks for at once" in {
      val src = Age +
        """show[T: Display + Eq](a: T, b: T) -> unit
          |    print(a, a == b)
          |var x: Age = 7
          |var y: Age = 7
          |show(x, y)""".stripMargin

      run(src) shouldBe "7 true\n"
    }

    // A derived subtype is a type of its own, and the membership comes with the derivation — which
    // is the same "everything the base can do it can do" bargain the file's header describes.
    "a derived one reaches it too" in {
      run(Stamp + "twice[T: Add](x: T) -> T = x + x\nprint(i64(twice(Stamp(7i64))))") shouldBe "14\n"
    }
  }
}
