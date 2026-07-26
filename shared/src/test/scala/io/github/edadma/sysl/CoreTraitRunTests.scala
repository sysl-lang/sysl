package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of the core trait catalog (`14 §1`, `§2`, `§5`): `Self`, the operator
 * traits the prelude declares, and the memberships the compiler provides for the built-in types.
 *
 * The point of a membership is that it is invisible at run time — a scalar's `add` is the machine's
 * `add`, not a call — so what these check is that the *type system* now agrees a scalar satisfies a
 * bound, and that everything reached through that agreement computes what the operator computes.
 */
class CoreTraitRunTests extends AnyFreeSpec with RunSupport {

  "Self" - {
    "a trait signature written with 'Self' is the implementing type" in {
      val src =
        """trait Doubler
          |    twice(self) -> Self
          |struct Money
          |    cents: int
          |impl Doubler for Money
          |    twice(self) -> Self = Money(self.cents * 2)
          |var m = Money(21)
          |print(m.twice().cents)""".stripMargin

      run(src) shouldBe "42\n"
    }

    // The trait says `Self` and the impl says `Money`. Conformance compares resolved types, so the
    // two spellings have to be one signature — if they were not, this would fail to conform.
    "'Self' and the concrete name are interchangeable in an impl" in {
      val src =
        """trait Combine
          |    join(self, other: Self) -> Self
          |struct Money
          |    cents: int
          |impl Combine for Money
          |    join(self, other: Money) -> Money = Money(self.cents + other.cents)
          |var m = Money(30).join(Money(12))
          |print(m.cents)""".stripMargin

      run(src) shouldBe "42\n"
    }

    "'Self' works in a type's own members, signature and body alike" in {
      val src =
        """struct Money
          |    cents: int
          |    grow(self, by: Self) -> Self
          |        var sum: Self = Money(self.cents + by.cents)
          |        sum
          |var m = Money(40).grow(Money(2))
          |print(m.cents)""".stripMargin

      run(src) shouldBe "42\n"
    }
  }

  "a scalar satisfies the bound the compiler gave it" - {
    // The whole point of `§5`: no `impl Add for int` was written, yet `int` and `real` both pass a
    // `[T: Add]`. Two instantiations, so a single accidental specialization could not serve both.
    "a numeric type instantiates a '[T: Add]' generic" in {
      val src =
        """sum[T: Add](a: T, b: T) -> T = a.add(b)
          |print(sum(19, 23))
          |print(sum(1.5, 2.25))""".stripMargin

      run(src) shouldBe "42\n3.75\n"
    }

    "a string satisfies 'Add', which is concatenation" in {
      val src =
        """sum[T: Add](a: T, b: T) -> T = a.add(b)
          |print(sum("four", "two"))""".stripMargin

      run(src) shouldBe "fourtwo\n"
    }

    "'Ord' yields a bool, and the bound licenses the comparison" in {
      val src =
        """smaller[T: Ord](a: T, b: T) -> T = if a.lt(b) then a else b
          |print(smaller(7, 3))
          |print(smaller('z', 'a'))
          |print(smaller("beta", "alpha"))""".stripMargin

      run(src) shouldBe "3\na\nalpha\n"
    }

    "'Eq' reaches further than 'Ord' — a bool is equatable and not ordered" in {
      val src =
        """same[T: Eq](a: T, b: T) -> bool = a.eq(b)
          |print(same(true, true))
          |print(same(true, false))""".stripMargin

      run(src) shouldBe "true\nfalse\n"
    }

    "two bounds on one parameter are both available" in {
      val src =
        """clamp_add[T: Add + Ord](a: T, b: T, cap: T) -> T
          |    var s = a.add(b)
          |    if cap.lt(s) then cap else s
          |print(clamp_add(20, 22, 100))
          |print(clamp_add(20, 22, 30))""".stripMargin

      run(src) shouldBe "42\n30\n"
    }
  }

  "a core-trait method on a scalar is the operator" - {
    // Each arithmetic and bitwise trait, called by name on a literal. A wrong operator in the
    // table would show up here as a wrong number, and the operands are chosen so that no two
    // operators agree on them.
    "each arithmetic and bitwise method computes its own operator" in {
      val src =
        """print(20.add(3))
          |print(20.sub(3))
          |print(20.mul(3))
          |print(20.div(3))
          |print(20.rem(3))
          |print(20.bitand(3))
          |print(20.bitor(3))
          |print(20.bitxor(3))
          |print(20.shl(3))
          |print(20.shr(3))""".stripMargin

      run(src) shouldBe "23\n17\n60\n6\n2\n0\n23\n23\n160\n2\n"
    }

    "the prefix methods negate and complement" in {
      val src =
        """print(20.neg())
          |print(20.not())
          |print(2.5.neg())""".stripMargin

      run(src) shouldBe "-20\n-21\n-2.5\n"
    }

    "the comparison methods are the one each trait requires" in {
      val src =
        """print(3.eq(3), 3.eq(4))
          |print(3.lt(4), 4.lt(3), 3.lt(3))""".stripMargin

      run(src) shouldBe "true false\ntrue false false\n"
    }

    // A membership changes no codegen (`§5`): `a.add(b)` and `a + b` are the same instruction, so
    // the two must agree on a value where a call through anything else could not.
    "a method call and the operator agree" in {
      val src =
        """var a = 2147483647
          |print(a.add(1) == a + 1)
          |print(a.mul(3) == a * 3)""".stripMargin

      run(src) shouldBe "true\ntrue\n"
    }

    "a member of a struct wins over a core-trait method of the same name" in {
      val src =
        """struct Tally
          |    n: int
          |    add(self, k: int) -> int = self.n + k * 100
          |print(Tally(2).add(4))""".stripMargin

      run(src) shouldBe "402\n"
    }
  }

  "a user type carries its own impl" - {
    // `Point` is not a built-in, so its `Add` comes from the `impl` — and the same generic body
    // serves it and a scalar, which is the one dispatch rule of `§3` doing its job.
    "one generic body serves a user type and a scalar alike" in {
      val src =
        """struct Point
          |    x: int
          |    y: int
          |impl Add for Point
          |    add(self, rhs: Self) -> Self = Point(self.x + rhs.x, self.y + rhs.y)
          |sum[T: Add](a: T, b: T) -> T = a.add(b)
          |var p = sum(Point(1, 2), Point(30, 9))
          |print(p.x, p.y)
          |print(sum(40, 2))""".stripMargin

      run(src) shouldBe "31 11\n42\n"
    }

    // `Eq` is a catalog trait, but `Point` is not a built-in, so its membership comes from the
    // `impl` — the two sources of conformance meet at the same bound.
    "a user type's own 'Eq' impl satisfies the bound" in {
      val src =
        """struct Point
          |    x: int
          |    y: int
          |impl Eq for Point
          |    eq(self, rhs: Self) -> bool = self.x == rhs.x && self.y == rhs.y
          |same[T: Eq](a: T, b: T) -> bool = a.eq(b)
          |print(same(Point(1, 2), Point(1, 2)))
          |print(same(Point(1, 2), Point(1, 3)))""".stripMargin

      run(src) shouldBe "true\nfalse\n"
    }

    "a catalog method reaches its receiver through a pointer" in {
      val src =
        """var n = 40
          |var p = &n
          |print(p.add(2))""".stripMargin

      run(src) shouldBe "42\n"
    }
  }
}
