package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of methods, properties, and associated functions: each receiver
 * mode passes the instance correctly, and a member lowered to a function runs like any call.
 */
class MethodRunTests extends AnyFreeSpec with RunSupport {

  "a value-receiver method reads the instance" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |    sum(self) -> int = self.x + self.y
        |var p = Point(3, 4)
        |print(p.sum())""".stripMargin

    run(src) shouldBe "7\n"
  }

  "a pointer-receiver method mutates a stack value in place" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |    shift(*self, dx: int, dy: int)
        |        self.x += dx
        |        self.y += dy
        |var p = Point(3, 4)
        |p.shift(10, 20)
        |print(p.x, p.y)""".stripMargin

    run(src) shouldBe "13 24\n"
  }

  "a reference-receiver method mutates the shared heap object" in {
    val src =
      """struct Counter
        |    n: int
        |    bump(&self)
        |        self.n += 1
        |    value(self) -> int = self.n
        |var c: &Counter = Counter(0)
        |c.bump()
        |c.bump()
        |c.bump()
        |print(c.value())""".stripMargin

    run(src) shouldBe "3\n"
  }

  "a computed property reads with no parentheses, on a value and on a reference" in {
    val src =
      """struct Counter
        |    n: int
        |    doubled -> int = self.n * 2
        |var v = Counter(21)
        |var r: &Counter = Counter(5)
        |print(v.doubled, r.doubled)""".stripMargin

    run(src) shouldBe "42 10\n"
  }

  "an associated function is called through the type name" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |    sum(self) -> int = self.x + self.y
        |    diagonal(n: int) -> Point = Point(n, n)
        |var p = Point.diagonal(6)
        |print(p.sum())""".stripMargin

    run(src) shouldBe "12\n"
  }

  "a method may call another method on self" in {
    val src =
      """struct Rect
        |    w: int
        |    h: int
        |    area(self) -> int = self.w * self.h
        |    describe(self) -> int = self.area() + 1
        |var r = Rect(3, 4)
        |print(r.describe())""".stripMargin

    run(src) shouldBe "13\n"
  }

  "a method is reached through a pointer to the receiver" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |    sum(self) -> int = self.x + self.y
        |bump(p: *Point)
        |    p.x += 100
        |var p = Point(1, 2)
        |bump(&p)
        |print(p.sum())""".stripMargin

    run(src) shouldBe "103\n"
  }

  "the built-in len property is unchanged by user members" in {
    val src =
      """var a = [10, 20, 30]
        |print(a.len)""".stripMargin

    run(src) shouldBe "3\n"
  }
}
