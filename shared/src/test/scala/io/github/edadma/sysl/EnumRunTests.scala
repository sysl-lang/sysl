package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of enums: simple integer enums, data-carrying tagged unions,
 * variant construction and destructuring, guards over bindings, and nested patterns.
 */
class EnumRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** `09` says a data enum's "storage is sized for the largest variant plus a tag" — a union. What
   * is emitted is the tag followed by *every* variant's payload side by side, so an enum of four
   * variants carrying one scalar each is four scalars wide instead of one.
   *
   * The cost is real and multiplies: a fixed table of a struct holding eight of these is two and a
   * half times the storage the design describes, which is what `guide/kernel` ran out of frame on.
   * Turning it into a union means reaching a payload through memory rather than by `extractvalue`,
   * because a union is not an LLVM aggregate a value can be taken apart with — so it is a change to
   * how every data enum is represented, `Option` and `Result` included.
   */
  "a data enum is laid out as a union of its variants" ignore {
    ir("""enum Step
         |    Work(n: int)
         |    Lock(m: u8)
         |    Unlock(m: u8)
         |    Sleep(n: int)
         |var s = Work(1)
         |print(s match
         |    Work(n) -> n
         |    _ -> 0)
         |""".stripMargin) should include("%enum.Step = type { i32, i32 }")
  }

  "a simple enum matches by variant name, bare and qualified" in {
    val src =
      """enum Color
        |    Red
        |    Green
        |    Blue = 10
        |
        |name(c: Color) -> string
        |    c match
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
        |    s match
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
        |    s match
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
        |    o match
        |        Wrap(Val(v)) -> v
        |        Wrap(Nil) -> -1
        |        else -2
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
        |var answer = s match
        |    Circle(r) -> r
        |    Rect(w, h) -> w * h
        |print(answer)""".stripMargin

    run(src) shouldBe "100\n"
  }
}
