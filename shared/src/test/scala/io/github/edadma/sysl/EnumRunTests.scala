package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of enums: simple integer enums, data-carrying tagged unions,
 * variant construction and destructuring, guards over bindings, and nested patterns.
 */
class EnumRunTests extends AnyFreeSpec with RunSupport {

  "a simple enum matches by variant name, bare and qualified" in {
    val src =
      """enum Color
        |    Red
        |    Green
        |    Blue = 10
        |
        |name(c: Color) -> string
        |    match c
        |        Red -> "red"
        |        Green -> "green"
        |        Blue -> "blue"
        |print(name(Red), name(Green), name(Color.Blue))""".stripMargin

    run(src) shouldBe "red green blue\n"
  }

  "a data enum destructures its variants" in {
    val src =
      """enum Shape
        |    Circle(radius: int)
        |    Rect(w: int, h: int)
        |    Empty
        |
        |area(s: Shape) -> int
        |    match s
        |        Circle(r) -> r * r * 3
        |        Rect(w, h) -> w * h
        |        Empty -> 0
        |print(area(Circle(5)), area(Rect(3, 4)), area(Empty))""".stripMargin

    run(src) shouldBe "75 12 0\n"
  }

  "a guard reads a variant binding, with a catch-all binding arm" in {
    val src =
      """enum Shape
        |    Circle(radius: int)
        |    Rect(w: int, h: int)
        |
        |describe(s: Shape) -> string
        |    match s
        |        Circle(r) if r > 10 -> "big circle"
        |        Circle(r) -> "small circle"
        |        other -> "not a circle"
        |print(describe(Circle(20)), describe(Circle(2)), describe(Rect(1, 1)))""".stripMargin

    run(src) shouldBe "big circle small circle not a circle\n"
  }

  "nested variant patterns bind through two levels" in {
    val src =
      """enum Inner
        |    Val(x: int)
        |    Nil
        |
        |enum Outer
        |    Wrap(i: Inner)
        |    Nothing
        |
        |unwrap(o: Outer) -> int
        |    match o
        |        Wrap(Val(v)) -> v
        |        Wrap(Nil) -> -1
        |        else -> -2
        |print(unwrap(Wrap(Val(42))), unwrap(Wrap(Nil)), unwrap(Nothing))""".stripMargin

    run(src) shouldBe "42 -1 -2\n"
  }

  "an enum flows through a variable and a function that returns one" in {
    val src =
      """enum Shape
        |    Circle(radius: int)
        |    Rect(w: int, h: int)
        |
        |pick(big: bool) -> Shape
        |    if big then Rect(10, 10) else Circle(1)
        |
        |var s = pick(true)
        |var answer = match s
        |    Circle(r) -> r
        |    Rect(w, h) -> w * h
        |print(answer)""".stripMargin

    run(src) shouldBe "100\n"
  }
}
