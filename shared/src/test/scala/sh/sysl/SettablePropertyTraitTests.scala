package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A trait that asks for a settable property (`reference/declarations.md § A property may be
 * settable`, `02`).
 *
 * A setter is an ordinary `*self` method under a name a program cannot spell, so the three ways a
 * member is reached reach it too: a bound, a method table, and the type's own body. What each of
 * these pins is that `x.count = v` still means the setter at that receiver.
 */
class SettablePropertyTraitTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "a bound licenses writing the property" in {
    val src =
      """trait Counter
        |    count -> int
        |    set count(n)
        |
        |struct Cell
        |    v: int
        |
        |impl Counter for Cell
        |    count -> int = self.v
        |    set count(n)
        |        self.v = n * 2
        |
        |bump[C: Counter](c: *C)
        |    c.count = 21
        |
        |var cell = Cell(0)
        |bump(&cell)
        |print(cell.count)""".stripMargin

    run(src) shouldBe "42\n"
  }

  "a compound form through a bound reads and writes the property" in {
    val src =
      """trait Counter
        |    count -> int
        |    set count(n)
        |
        |struct Cell
        |    v: int
        |
        |impl Counter for Cell
        |    count -> int = self.v
        |    set count(n)
        |        self.v = n
        |
        |twice[C: Counter](c: *C)
        |    c.count += c.count
        |
        |var cell = Cell(21)
        |twice(&cell)
        |print(cell.count)""".stripMargin

    run(src) shouldBe "42\n"
  }

  "a trait object writes through its table" in {
    val src =
      """trait Counter
        |    count -> int
        |    set count(n)
        |
        |struct Cell
        |    v: int
        |
        |impl Counter for Cell
        |    count -> int = self.v
        |    set count(n)
        |        self.v = n + 1
        |
        |var c: &Counter = Cell(0)
        |c.count = 41
        |print(c.count)""".stripMargin

    run(src) shouldBe "42\n"
  }

  "a trait supplies a default setter" in {
    val src =
      """trait Counter
        |    raw -> int
        |    set raw(n)
        |
        |    count -> int = self.raw
        |    set count(n)
        |        self.raw = n * 10
        |
        |struct Cell
        |    v: int
        |
        |impl Counter for Cell
        |    raw -> int = self.v
        |    set raw(n)
        |        self.v = n
        |
        |var cell = Cell(0)
        |cell.count = 4
        |print(cell.count)""".stripMargin

    run(src) shouldBe "40\n"
  }

  "an impl need not restate the property to supply its setter" in {
    val src =
      """trait Counter
        |    count -> int = 7
        |    set count(n)
        |
        |struct Cell
        |    v: int
        |
        |impl Counter for Cell
        |    set count(n)
        |        self.v = n
        |
        |var cell = Cell(0)
        |cell.count = 1
        |print(cell.count, cell.v)""".stripMargin

    run(src) shouldBe "7 1\n"
  }

  "errors" - {
    "an implementation that omits the setter is refused, and the setter is named as one" in {
      val m = err(
        """trait Counter
          |    count -> int
          |    set count(n)
          |
          |struct Cell
          |    v: int
          |
          |impl Counter for Cell
          |    count -> int = self.v
          |print(1)""".stripMargin
      )

      m should include("count")
      m should not include "$"
    }

    "a bound with no setter refuses the write" in {
      val m = err(
        """trait Counter
          |    count -> int
          |
          |struct Cell
          |    v: int
          |
          |impl Counter for Cell
          |    count -> int = self.v
          |
          |bump[C: Counter](c: *C)
          |    c.count = 1
          |
          |var cell = Cell(0)
          |bump(&cell)""".stripMargin
      )

      m should not include "$"
      m should include("in trait 'Counter'")
    }

    "a trait object with no setter is refused, and named as the trait's" in {
      val m = err(
        """trait Counter
          |    count -> int
          |
          |struct Cell
          |    v: int
          |
          |impl Counter for Cell
          |    count -> int = self.v
          |
          |var c: &Counter = Cell(0)
          |c.count = 1""".stripMargin
      )

      m should include("in trait 'Counter'")
      m should not include "$"
    }

    "a trait asking for a setter must declare the property" in {
      err(
        """trait Counter
          |    set count(n)
          |print(1)""".stripMargin
      ) should include("declares none")
    }
  }
}
