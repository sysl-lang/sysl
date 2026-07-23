package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of value structs: positional construction, field reads, in-place
 * field assignment, and passing a struct to a function.
 */
class StructRunTests extends AnyFreeSpec with RunSupport {

  "a struct is constructed and its fields read" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |var p = Point(10, 20)
        |print(p.x, p.y)""".stripMargin

    run(src) shouldBe "10 20\n"
  }

  "a field can be assigned in place" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |var p = Point(1, 2)
        |p.x = 99
        |print(p.x, p.y)""".stripMargin

    run(src) shouldBe "99 2\n"
  }

  "a struct is passed to a function by value" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |manhattan(p: Point) -> int = p.x + p.y
        |print(manhattan(Point(10, 32)))""".stripMargin

    run(src) shouldBe "42\n"
  }

  "a struct may hold mixed field types" in {
    val src =
      """struct Mix
        |    n: int
        |    r: real
        |    ok: bool
        |var m = Mix(3, 1.5, true)
        |print(m.n, m.r, m.ok)""".stripMargin

    run(src) shouldBe "3 1.5 true\n"
  }
}
