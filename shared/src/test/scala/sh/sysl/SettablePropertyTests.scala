package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A property that can be written as well as read (`reference/declarations.md § A property may be
 * settable`).
 *
 * `p.count = v` is a **call** rather than a store, which is what every case here is really about:
 * the value reaches the setter's body, the compound forms read and write through one address, and
 * the forms that cannot be a call are refused by name rather than by the absence of an address.
 */
class SettablePropertyTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "a write reaches the setter's body" in {
    val src =
      """struct Cell
        |    v: int
        |
        |    count -> int = self.v
        |
        |    set count(x)
        |        self.v = x * 10
        |var c = Cell(0)
        |c.count = 4
        |print(c.count)""".stripMargin

    run(src) shouldBe "40\n"
  }

  "the setter sees the value and the getter is unaffected" in {
    val src =
      """struct Temp
        |    c: int
        |
        |    f -> int = self.c * 9 / 5 + 32
        |
        |    set f(v)
        |        self.c = (v - 32) * 5 / 9
        |var t = Temp(0)
        |t.f = 212
        |print(t.c, t.f)""".stripMargin

    run(src) shouldBe "100 212\n"
  }

  "a setter runs code the write could not have done itself" in {
    val src =
      """struct Counted
        |    v: int
        |    writes: int
        |
        |    value -> int = self.v
        |
        |    set value(x)
        |        self.v = x
        |        self.writes += 1
        |var c = Counted(0, 0)
        |c.value = 1
        |c.value = 2
        |c.value = 3
        |print(c.value, c.writes)""".stripMargin

    run(src) shouldBe "3 3\n"
  }

  "the compound forms read through the setter and write back" in {
    val src =
      """struct Cell
        |    v: int
        |
        |    count -> int = self.v
        |
        |    set count(x)
        |        self.v = x
        |var c = Cell(1)
        |c.count += 1
        |c.count *= 5
        |c.count -= 2
        |print(c.count)""".stripMargin

    run(src) shouldBe "8\n"
  }

  "a compound form calls the setter once, not the getter twice over" in {
    val src =
      """struct Counted
        |    v: int
        |    reads: int
        |    writes: int
        |
        |    value -> int = self.v
        |
        |    set value(x)
        |        self.v = x
        |        self.writes += 1
        |var c = Counted(5, 0, 0)
        |c.value += 1
        |print(c.value, c.writes)""".stripMargin

    run(src) shouldBe "6 1\n"
  }

  "the receiver of a compound form is evaluated once" in {
    val src =
      """struct Cell
        |    v: int
        |
        |    count -> int = self.v
        |
        |    set count(x)
        |        self.v = x
        |var calls = 0
        |which(n: int) -> int
        |    calls += 1
        |    n
        |var cells = [Cell(1), Cell(2)]
        |cells[which(0)].count += 10
        |print(cells[0].count, calls)""".stripMargin

    run(src) shouldBe "11 1\n"
  }

  "a setter is reached through a pointer receiver" in {
    val src =
      """struct Cell
        |    v: int
        |
        |    count -> int = self.v
        |
        |    set count(x)
        |        self.v = x + 1
        |var c = Cell(0)
        |val p = &c
        |p.count = 5
        |print(c.count)""".stripMargin

    run(src) shouldBe "6\n"
  }

  "a setter is reached through a reference receiver" in {
    val src =
      """struct Cell
        |    v: int
        |
        |    count -> int = self.v
        |
        |    set count(x)
        |        self.v = x + 1
        |var c: &Cell = Cell(0)
        |c.count = 5
        |print(c.count)""".stripMargin

    run(src) shouldBe "6\n"
  }

  "a setter on a generic type is instantiated per receiver" in {
    val src =
      """struct Box[T]
        |    v: T
        |
        |    value -> T = self.v
        |
        |    set value(x)
        |        self.v = x
        |var a = Box(1)
        |var b = Box("hi")
        |a.value = 7
        |b.value = "there"
        |print(a.value, b.value)""".stripMargin

    run(src) shouldBe "7 there\n"
  }

  "a setter may be written on an enum" in {
    val src =
      """enum Dial
        |    Low
        |    High
        |
        |    level -> int = self match
        |        Low -> 1
        |        High -> 2
        |
        |    set level(n)
        |        *self = if n > 1 then Dial.High else Dial.Low
        |var d = Dial.Low
        |d.level = 5
        |print(d.level)""".stripMargin

    run(src) shouldBe "2\n"
  }

  "a private setter leaves the property readable" in {
    val src =
      """struct Cell
        |    v: int
        |
        |    count -> int = self.v
        |
        |    private set count(x)
        |        self.v = x
        |
        |    bump(*self)
        |        self.count = self.count + 1
        |var c = Cell(1)
        |c.bump()
        |print(c.count)""".stripMargin

    run(src) shouldBe "2\n"
  }

  "errors" - {
    "a setter with no property of that name is refused" in {
      err(
        """struct Cell
          |    v: int
          |
          |    set count(x)
          |        self.v = x
          |print(1)""".stripMargin
      ) should include("declares none")
    }

    "a read-only property says what to write" in {
      val m = err(
        """struct Cell
          |    v: int
          |    count -> int = self.v
          |var c = Cell(1)
          |c.count = 2""".stripMargin
      )

      m should include("computes rather than naming storage")
      m should include("set count")
    }

    "a compound form on a read-only property is refused too" in {
      err(
        """struct Cell
          |    v: int
          |    count -> int = self.v
          |var c = Cell(1)
          |c.count += 2""".stripMargin
      ) should include("has nothing to write back")
    }

    "a setter refuses a value of the wrong type" in {
      err(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.v = x
          |var c = Cell(1)
          |c.count = "two"""".stripMargin
      ) should include("string")
    }

    "a second setter for one property is refused, and named as one" in {
      val m = err(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.v = x
          |
          |    set count(y)
          |        self.v = y
          |print(1)""".stripMargin
      )

      m should include("already has a setter for 'count'")
      m should not include "$"
    }

    "a setter refuses a 'val' receiver, naming the binding" in {
      err(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.v = x
          |val c = Cell(1)
          |c.count = 2""".stripMargin
      ) should include("written once")
    }

    "a property cannot be one place of a multiple assignment" in {
      err(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.v = x
          |var c = Cell(1)
          |var n = 0
          |c.count, n = 1, 2""".stripMargin
      ) should include("cannot be one place of a multiple assignment")
    }

    "the address of a property is refused" in {
      err(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.v = x
          |var c = Cell(1)
          |val p = &c.count""".stripMargin
      ) should include("address")
    }

    "a setter is not reachable under the name it is filed under" in {
      val m = err(
        """struct Cell
          |    v: int
          |
          |    count -> int = self.v
          |
          |    set count(x)
          |        self.v = x
          |var c = Cell(1)
          |print(c.count$set)""".stripMargin
      )

      m should not include "already"
    }

    "a getter that reads its own property is refused" in {
      err(
        """struct Cell
          |    v: int
          |    count -> int = self.count
          |var c = Cell(1)
          |print(c.count)""".stripMargin
      ) should include("reads the property it is defining")
    }

    "a setter that writes its own property is refused" in {
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

    "'set' is still an ordinary name" in {
      val src =
        """struct Cell
          |    set: int
          |    get -> int = self.set
          |var set = Cell(3)
          |print(set.get)""".stripMargin

      run(src) shouldBe "3\n"
    }
  }
}
