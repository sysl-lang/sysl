package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** An accessor that reaches the member it is defining calls itself.
 *
 * There is no reading under which either half is what the author meant — the value a property
 * computes is not the property, and there is no `super` to reach past it — so the shape is refused
 * rather than left to run out of stack. What the tests here are really about is the line between
 * that and every neighbouring shape that terminates and must keep working: the setter reading its
 * own getter, a member reading a *different* property, and a method reading either.
 */
class SelfAccessorTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "refused" - {
    "a getter that reads itself" in {
      err(
        """struct Cell
          |    v: int
          |    count -> int = self.count
          |var c = Cell(1)
          |print(c.count)""".stripMargin
      ) should include("reads the property it is defining")
    }

    "a getter that reads itself from inside a block body" in {
      err(
        """struct Cell
          |    v: int
          |    count -> int
          |        val n = self.count
          |        n + 1
          |var c = Cell(1)
          |print(c.count)""".stripMargin
      ) should include("reads the property it is defining")
    }

    "a setter that writes itself" in {
      err(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.count = x
          |var c = Cell(1)
          |c.count = 2""".stripMargin
      ) should include("writes the property it is defining")
    }

    "a setter that writes itself through '*self'" in {
      err(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        (*self).count = x
          |var c = Cell(1)
          |c.count = 2""".stripMargin
      ) should include("writes the property it is defining")
    }

    "a setter that writes itself with a compound form" in {
      err(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.count += x
          |var c = Cell(1)
          |c.count = 2""".stripMargin
      ) should include("writes the property it is defining")
    }

    "a getter on a generic type that reads itself" in {
      err(
        """struct Box[T]
          |    v: T
          |    value -> T = self.value
          |var b = Box(1)
          |print(b.value)""".stripMargin
      ) should include("reads the property it is defining")
    }

    "a trait default that reads itself" in {
      err(
        """trait Counter
          |    count -> int = self.count
          |
          |struct Cell
          |    v: int
          |
          |impl Counter for Cell
          |
          |var c = Cell(1)
          |print(c.count)""".stripMargin
      ) should include("reads the property it is defining")
    }

    "neither message names the setter by the name it is filed under" in {
      err(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.count = x
          |var c = Cell(1)
          |c.count = 2""".stripMargin
      ) should not include "$"
    }
  }

  "still allowed" - {
    "a setter reads its own getter, which is a different member" in {
      val src =
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.v = x + self.count
          |var c = Cell(10)
          |c.count = 1
          |print(c.count)""".stripMargin

      run(src) shouldBe "11\n"
    }

    "a property reads a different property" in {
      val src =
        """struct Cell
          |    v: int
          |    twice -> int = self.v * 2
          |    quad -> int = self.twice * 2
          |var c = Cell(3)
          |print(c.quad)""".stripMargin

      run(src) shouldBe "12\n"
    }

    "a method reads the property beside it" in {
      val src =
        """struct Cell
          |    v: int
          |    count -> int = self.v
          |    describe(self) -> int = self.count + 1
          |var c = Cell(3)
          |print(c.describe())""".stripMargin

      run(src) shouldBe "4\n"
    }

    "a method writes the property beside it" in {
      val src =
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.v = x
          |
          |    bump(*self)
          |        self.count += 1
          |var c = Cell(3)
          |c.bump()
          |print(c.count)""".stripMargin

      run(src) shouldBe "4\n"
    }

    // The check is about the member, not about the name: `Outer.count` reading `Inner.count` is two
    // members that happen to share a spelling, and it terminates.
    "a property reads a property of the same name on another type" in {
      val src =
        """struct Inner
          |    v: int
          |    count -> int = self.v * 2
          |
          |struct Outer
          |    i: Inner
          |    count -> int = self.i.count + 1
          |var o = Outer(Inner(3))
          |print(o.count)""".stripMargin

      run(src) shouldBe "7\n"
    }
  }
}
