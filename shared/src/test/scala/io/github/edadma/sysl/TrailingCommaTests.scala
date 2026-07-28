package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A comma-separated list may end in a comma (`00 §9`).
 *
 * Every such list in sysl is bracketed, and a bracket suspends the off-side rule, so a list has
 * always been free to span lines. The trailing comma is what makes that layout worth using: with
 * one element per line the last line stops being special, so an element can be added, removed or
 * reordered without touching its neighbour.
 *
 * The rule is that the comma is optional *after* an element and never instead of one, so the error
 * cases below are as much the specification as the accepting ones.
 */
class TrailingCommaTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a list may end in a comma" - {
    "an array literal" in {
      run("var a = [1, 2, 3,]\nprint(a[0], a[2])") shouldBe "1 3\n"
    }

    "an array literal written one element to a line" in {
      val src =
        """var a = [
          |    10,
          |    20,
          |    30,
          |]
          |print(a[0] + a[1] + a[2])""".stripMargin

      run(src) shouldBe "60\n"
    }

    "a call's arguments" in {
      run("print(1, 2,)") shouldBe "1 2\n"
    }

    "a parameter list" in {
      run("f(a: int, b: int,) -> int = a + b\nprint(f(20, 22,))") shouldBe "42\n"
    }

    "a method's parameters, with and without a receiver" in {
      val src =
        """struct P
          |    x: int
          |    y: int
          |    sum(self, k: int,) -> int = self.x + self.y + k
          |    of(a: int, b: int,) -> P = P(a, b)
          |var p = P.of(1, 2,)
          |print(p.sum(39,))""".stripMargin

      run(src) shouldBe "42\n"
    }

    "a type-argument list" in {
      run("var b: &Buf[int,] = buf()\nb.push(7)\nprint(b.at(0))") shouldBe "7\n"
    }

    "a type-parameter list" in {
      run("first[T, U,](a: T, b: U) -> T = a\nprint(first(1, \"x\"))") shouldBe "1\n"
    }

    "an enum variant's payload, and a pattern that takes it apart" in {
      val src =
        """enum Shape
          |    Rect(w: int, h: int,)
          |    Dot
          |var s = Rect(6, 7,)
          |var a = s match
          |    Rect(w, h,) -> w * h
          |    Dot -> 0
          |print(a)""".stripMargin

      run(src) shouldBe "42\n"
    }

    "an import selector list" in {
      val out = runIn(
        ("isa", "isa.sysl", "module isa\n\nwidth() -> int = 6\nheight() -> int = 7\n"),
        ("", "main.sysl", "import isa.{width, height,}\n\nprint(width() * height())"),
      )

      out shouldBe "42\n"
    }

    // The variadic marker still reads the comma before it as the separator it is, so the two
    // features do not collide.
    "a variadic parameter list is unaffected" in {
      run("f(n: int, ...) -> int = n\nprint(f(42, 1, 2))") shouldBe "42\n"
    }
  }

  "the comma follows an element, it does not replace one" - {
    "an empty list with a comma in it" in {
      err("var a = [,]") should include("expected")
    }

    "an empty argument list with a comma in it" in {
      err("print(,)") should include("expected")
    }

    "two commas in a row" in {
      err("var a = [1,, 2]") should include("expected")
    }

    "a leading comma" in {
      err("var a = [, 1]") should include("expected")
    }

    // An empty list is still an empty list — the change adds nothing there.
    "an empty list is unchanged" in {
      run("var a: [0]int = []\nprint(a.len)") shouldBe "0\n"
    }
  }
}
