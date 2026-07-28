package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of enums: simple integer enums, data-carrying tagged unions,
 * variant construction and destructuring, guards over bindings, and nested patterns.
 */
class EnumRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** `09 §3` — "storage is sized for the largest variant plus a tag". Four variants carrying one
   * scalar each are one scalar wide, not four, and the width is the *widest* of them: what the
   * region is counted in is the strictest alignment any variant needs, which is what keeps a
   * payload holding an `i64` from landing four bytes past where it may start.
   */
  private val step =
    """enum Step
      |    Work(n: int)
      |    Lock(m: u8)
      |    Unlock(m: u8)
      |    Sleep(n: int)
      |code(s: Step) -> int
      |    s match
      |        Work(n) -> n
      |        Lock(m) -> 100 + int(m)
      |        Unlock(m) -> 200 + int(m)
      |        Sleep(n) -> 300 + n
      |""".stripMargin

  "the storage of a data enum" - {
    "is the tag and one region every variant shares" in {
      ir(step + "print(code(Work(1)))") should include("%enum.Step = type { i32, [1 x i32] }")
    }

    "counted in whatever unit the strictest variant must be aligned to" in {
      val out = ir("""enum Wide
                     |    Pair(x: i64, y: i64)
                     |    Small(c: u8)
                     |    Empty
                     |what(w: Wide) -> int
                     |    w match
                     |        Pair(x, y) -> int(x + y)
                     |        Small(c) -> int(c)
                     |        Empty -> -1
                     |print(what(Pair(1i64, 2i64)))
                     |""".stripMargin)

      out should include("%enum.Wide = type { i32, [2 x i64] }")
    }

    "and is wide enough for a variant whose payload is itself an enum" in {
      ir(step + "var o: Option[Step] = Some(Lock(1u8))\nprint(o.is_some())") should
        include("%enum.Option.Step = type { i32, [2 x i32] }")
    }

    // The union is what makes this the interesting case: four variants writing four different
    // types into one region, read back through a table that would alias if the region were sized
    // for anything but the widest of them.
    "so a table of them holds each variant's own payload" in {
      val src = step +
        """var t: [4]Step = [Sleep(0); 4]
          |t[0usize] = Work(11)
          |t[1usize] = Lock(3u8)
          |t[2usize] = Unlock(4u8)
          |t[3usize] = Sleep(5)
          |for i in 0..<4
          |    print(code(t[usize(i)]))
          |""".stripMargin

      run(src) shouldBe "11\n103\n204\n305\n"
    }

    // Sharing the region means a pattern reads whatever the variant that *wrote* it left there, so
    // a literal payload pattern can match on bytes that were never written at its type. The tag
    // test beside it is the whole of what keeps that from firing, and this is what checks it does.
    "a literal payload pattern never fires for another variant" in {
      val src =
        """enum Step
          |    Work(n: int)
          |    Lock(m: u8)
          |    Sleep(n: int)
          |code(s: Step) -> int
          |    s match
          |        Work(0) -> 1
          |        Work(n) -> 2
          |        Lock(m) -> 3
          |        Sleep(n) -> 4
          |print(code(Lock(0u8)), code(Sleep(0)), code(Work(0)), code(Work(9)))
          |""".stripMargin

      run(src) shouldBe "3 4 1 2\n"
    }

    // Two enums nested one inside the other read two regions on the way to one field, at two
    // different types. They can only ever be *different* types — an enum holding itself by value
    // would be infinitely sized and is refused — so the two reads never collide.
    "a nested pattern reaches through both regions" in {
      val src =
        """enum Step
          |    Work(n: int)
          |    Lock(m: u8)
          |what(o: Option[Step]) -> int
          |    o match
          |        Some(Work(n)) -> n
          |        Some(Lock(m)) -> 100 + int(m)
          |        None -> -1
          |print(what(Some(Work(7))), what(Some(Lock(3u8))), what(None))
          |""".stripMargin

      run(src) shouldBe "7 103 -1\n"
    }

    // A variant written with a payload that occupies nothing asks for a region of nothing, which is
    // still a store and a load — of zero bytes.
    "a payload that occupies nothing still round-trips" in {
      val src =
        """enum E
          |    Nothing(n: unit)
          |    Something
          |which(e: E) -> int
          |    e match
          |        Nothing(n) -> 1
          |        Something -> 2
          |print(which(Nothing(())), which(Something))
          |""".stripMargin

      run(src) shouldBe "1 2\n"
    }

    // `09 §3` — "it moves by copy like any value". The region is written through a stack slot the
    // whole function shares, so this is also what checks that two enum values of one type are not
    // quietly the same storage.
    "and it still moves by copy" in {
      val src = step +
        """var a = Work(1)
          |var b = a
          |a = Sleep(2)
          |print(code(a), code(b))
          |""".stripMargin

      run(src) shouldBe "302 1\n"
    }

    // A nullary variant never touches the region, so overwriting a payload-carrying value with one
    // has to leave the tag saying so — reading the old payload back would be the bug this catches.
    "and a nullary variant leaves nothing of the one before it" in {
      val src =
        """enum Wide
          |    Pair(x: i64, y: i64)
          |    Small(c: u8)
          |    Empty
          |what(w: Wide) -> int
          |    w match
          |        Pair(x, y) -> int(x + y)
          |        Small(c) -> int(c)
          |        Empty -> -1
          |var w = Pair(1i64, 2i64)
          |print(what(w))
          |w = Small(9u8)
          |print(what(w))
          |w = Empty
          |print(what(w))
          |""".stripMargin

      run(src) shouldBe "3\n9\n-1\n"
    }
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
