package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2: arrays as programs actually behave, compiled and run. */
class ArrayRunTests extends AnyFreeSpec with RunSupport {

  "a literal keeps its elements in order" in {
    run("var a = [2, 3, 5, 7]\nprint(a[0], a[1], a[2], a[3])") shouldBe "2 3 5 7\n"
  }

  "a length is how many were written" in {
    run("var a = [2, 3, 5, 7]\nprint(a.len)") shouldBe "4\n"
  }

  "a declaration with no initializer is all zeros" in {
    run("var a: [4]int\nprint(a[0], a[3], a.len)") shouldBe "0 0 4\n"
  }

  // A zero-length array is a legal degenerate case: it holds nothing, so its length is 0 and any
  // index would trap, but declaring one and asking its length must still work.
  "a zero-length array has length zero" in {
    val src =
      """var empty: [0]int
        |print(empty.len)""".stripMargin

    run(src) shouldBe "0\n"
  }

  "an element is a place, so every assignment form works on it" in {
    val src =
      """var a: [3]int
        |a[0] = 5
        |a[1] += 7
        |a[2]++
        |print(a[0], a[1], a[2])
        |""".stripMargin

    run(src) shouldBe "5 7 1\n"
  }

  "an array copies, so two names do not share storage" in {
    val src =
      """var a = [1, 2, 3]
        |var b = a
        |b[0] = 99
        |print(a[0], b[0])
        |""".stripMargin

    run(src) shouldBe "1 99\n"
  }

  "a parameter is a copy too, so a callee cannot reach the caller's array" in {
    val src =
      """clobber(a: [3]int) -> int
        |    a[0] = 99
        |    a[0]
        |end clobber
        |var a = [1, 2, 3]
        |print(clobber(a), a[0])
        |""".stripMargin

    run(src) shouldBe "99 1\n"
  }

  "iterating binds each element in turn" in {
    val src =
      """var a = [2, 3, 5, 7]
        |var total = 0
        |for x in a do total += x
        |print(total)
        |""".stripMargin

    run(src) shouldBe "17\n"
  }

  "iterating by index reaches the same elements" in {
    val src =
      """var a = [2, 3, 5, 7]
        |var total = 0
        |for i in 0..<a.len do total += a[i]
        |print(total)
        |""".stripMargin

    run(src) shouldBe "17\n"
  }

  "the loop variable is a copy, so writing it does not write the array" in {
    val src =
      """var a = [1, 2, 3]
        |for x in a do x = 9
        |print(a[0], a[1], a[2])
        |""".stripMargin

    run(src) shouldBe "1 2 3\n"
  }

  "arrays nest, and the inner subscript is checked too" in {
    val src =
      """var g: [2][3]int
        |g[1][2] = 5
        |g[0][0] = 1
        |print(g[0][0], g[1][2], g.len, g[0].len)
        |""".stripMargin

    run(src) shouldBe "1 5 2 3\n"
  }

  "an array of structs holds them by value" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |end Point
        |var ps = [Point(1, 2), Point(3, 4)]
        |ps[0].x = 9
        |print(ps[0].x, ps[1].y)
        |""".stripMargin

    run(src) shouldBe "9 4\n"
  }

  "an array can hold references, and each one is counted" in {
    val src =
      """struct Cell
        |    n: int
        |end Cell
        |
        |var c: &Cell = Cell(7)
        |var cells = [c, c, c]
        |cells[1].n = 8
        |print(c.n, cells[0].n, cells.len)
        |""".stripMargin

    run(src) shouldBe "8 8 3\n"
  }

  "a big array of references does not leak or double free" in {
    val src =
      """struct Cell
        |    n: int
        |end Cell
        |
        |var total = 0
        |for i in 1..2000 do
        |    var c: &Cell = Cell(i)
        |    var held = [c, c, c, c]
        |    total += held[3].n
        |print(total)
        |""".stripMargin

    run(src) shouldBe "2001000\n"
  }

  "an out-of-range index stops the program instead of reading past the end" in {
    val src =
      """var a = [1, 2, 3]
        |var i = 3
        |print(a[i])
        |""".stripMargin

    assume(Toolchain.clangAvailable, "clang not available")
    Toolchain.compileAndRun(src) match {
      case Right((code, _)) => code should not be 0
      case Left(e)          => fail(e)
    }
  }

  "a negative index is out of range as well" in {
    val src =
      """var a = [1, 2, 3]
        |var i = -1
        |print(a[i])
        |""".stripMargin

    assume(Toolchain.clangAvailable, "clang not available")
    Toolchain.compileAndRun(src) match {
      case Right((code, _)) => code should not be 0
      case Left(e)          => fail(e)
    }
  }
}
