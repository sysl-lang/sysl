package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2 runtime behavior of the raw-pointer mode: taking an address, reading and writing
 * through it, the one level of automatic dereference on a field, and the recursive types that
 * a pointer field makes possible.
 */
class PointerRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "a pointer reads and writes the variable it points at" in {
    val src =
      """var n = 10
        |var p = &n
        |print(*p)
        |*p = 42
        |print(n)""".stripMargin

    run(src) shouldBe "10\n42\n"
  }

  "a pointer follows its target rather than copying it" in {
    val src =
      """var n = 1
        |var p = &n
        |n = 2
        |print(*p)
        |*p = 3
        |print(n)""".stripMargin

    run(src) shouldBe "2\n3\n"
  }

  "a pointer to a pointer reaches through both levels" in {
    val src =
      """var n = 1
        |var p = &n
        |var pp = &p
        |**pp = 5
        |print(n, **pp)""".stripMargin

    run(src) shouldBe "5 5\n"
  }

  "a function mutates its caller's variable through a pointer" in {
    val src =
      """bump(p: *int)
        |    *p += 1
        |var n = 41
        |bump(&n)
        |print(n)""".stripMargin

    run(src) shouldBe "42\n"
  }

  "increment and compound assignment work through a dereference" in {
    val src =
      """var n = 10
        |var p = &n
        |*p += 5
        |print(*p)
        |print((*p)++)
        |print(n)""".stripMargin

    run(src) shouldBe "15\n15\n16\n"
  }

  "a field is selected through a pointer without writing the dereference" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |var pt = Point(3, 4)
        |var p = &pt
        |print(p.x, p.y)
        |p.x = 30
        |print(pt.x)""".stripMargin

    run(src) shouldBe "3 4\n30\n"
  }

  "a pointer to a field addresses that field alone" in {
    val src =
      """struct Point
        |    x: int
        |    y: int
        |var pt = Point(3, 4)
        |var py = &pt.y
        |*py = 40
        |print(pt.x, pt.y)""".stripMargin

    run(src) shouldBe "3 40\n"
  }

  "a struct may point at its own type" in {
    val src =
      """struct Node
        |    value: int
        |    next: *Node
        |var c = Node(3, null)
        |var b = Node(2, &c)
        |var a = Node(1, &b)
        |var walk = &a
        |var sum = 0
        |while walk != null do
        |    sum += walk.value
        |    walk = walk.next
        |print(sum)""".stripMargin

    run(src) shouldBe "6\n"
  }

  "a write reaches through two links of a list" in {
    val src =
      """struct Node
        |    value: int
        |    next: *Node
        |var b = Node(2, null)
        |var a = Node(1, &b)
        |a.next.value = 20
        |print(b.value)""".stripMargin

    run(src) shouldBe "20\n"
  }

  "null compares equal to null and unequal to an address" in {
    val src =
      """var n = 1
        |var p = &n
        |var q: *int = null
        |print(p == null, q == null, p != null)""".stripMargin

    run(src) shouldBe "false true true\n"
  }

  "two pointers to the same variable compare equal" in {
    val src =
      """var n = 1
        |var m = 1
        |var p = &n
        |var q = &n
        |print(p == q, p == &m)""".stripMargin

    run(src) shouldBe "true false\n"
  }

  "a pointer parameter of a generic function takes its argument's type" in {
    val src =
      """peek[T](p: *T) -> T = *p
        |var n = 7
        |var r = 1.5
        |print(peek(&n), peek(&r))""".stripMargin

    run(src) shouldBe "7 1.5\n"
  }

  "booleans compare for equality" in {
    val src =
      """var a = true
        |var b = false
        |print(a == a, a == b, a != b)""".stripMargin

    run(src) shouldBe "true false true\n"
  }

  /** Lending a counted value to code that only wants to look at it.
   *
   * Nothing in `03` states this as a rule of its own — it falls out of two that are stated, that
   * `*p` is a place and that the address of a place is a `*T`. So `&*r` is how a `&T` reaches a
   * function written against `*T`, and the crossing into the unsafe tier stays visible at the call
   * exactly as `03` wants it to. It had no test, and `guide/shapes` is what went looking.
   */
  "a counted reference" - {
    // `14 §2` puts the pointer modes in `Eq` with **address** equality. Two references to objects
    // holding the same thing are therefore not equal, which is what makes a reference an identity:
    // a program asking "is this the very object you are holding" — a scheduler asking whether a
    // task owns the lock it is releasing — is asking about the box and never about its contents.
    "compares by address rather than by what it holds" in {
      val src =
        """struct Cell
          |    n: int
          |var a: &Cell = Cell(4)
          |var b: &Cell = Cell(4)
          |var also = a
          |print(a == a, a == b, a == also, a != b)""".stripMargin

      run(src) shouldBe "true false true true\n"
    }

    // And it stays address equality inside a generic bounded `Eq`, which is what lets one
    // `drop[T: Eq]` take an element out of a list of references. Both cells hold the same number,
    // so a comparison that had reached through the reference would answer the other way round.
    "carries that identity through a generic 'Eq' bound" in {
      val src =
        """struct Cell
          |    n: int
          |same[T: Eq](a: T, b: T) -> bool = a == b
          |var a: &Cell = Cell(4)
          |var b: &Cell = Cell(4)
          |print(same(a, a), same(a, b))""".stripMargin

      run(src) shouldBe "true false\n"
    }

    // Equality reaches further than ordering (`01`, lifted into `14 §2` intact): a reference has
    // `==` and no `<`, since one address falling below another is not a fact about the program.
    "has equality and no ordering" in {
      val src =
        """struct Cell
          |    n: int
          |var a: &Cell = Cell(1)
          |var b: &Cell = Cell(2)
          |print(a < b)""".stripMargin

      err(src) should include("'<' is not defined for &Cell")
    }

    "is lent to a raw pointer by taking the address of what it points at" in {
      val src =
        """struct Cell
          |    n: int
          |peek(p: *Cell) -> int = p.n
          |var c: &Cell = Cell(7)
          |print(peek(&*c))""".stripMargin

      run(src) shouldBe "7\n"
    }

    // The lent pointer is the box's payload, not a copy of it, so a write through it is seen by
    // everything else holding the reference.
    "lends a pointer that writes through to the shared value" in {
      val src =
        """struct Cell
          |    n: int
          |bump(p: *Cell) = p.n += 1
          |var c: &Cell = Cell(7)
          |var also = c
          |bump(&*c)
          |bump(&*c)
          |print(c.n, also.n)""".stripMargin

      run(src) shouldBe "9 9\n"
    }

    // The lend takes no count, so the reference is released on its own schedule and the loop
    // neither leaks nor frees twice.
    "is not retained by the lending, so the count is unaffected" in {
      val src =
        """struct Cell
          |    n: int
          |peek(p: *Cell) -> int = p.n
          |var total = 0
          |var i = 0
          |while i < 200000
          |    var c: &Cell = Cell(3)
          |    total += peek(&*c)
          |    i++
          |print(total)""".stripMargin

      run(src) shouldBe "600000\n"
    }

    // Not accepted directly: the two modes are different types, and the whole value of `*T` being
    // greppable is that entering it is written down.
    "is not accepted where a raw pointer is wanted without the lend being written" in {
      val src =
        """struct Cell
          |    n: int
          |peek(p: *Cell) -> int = p.n
          |var c: &Cell = Cell(7)
          |print(peek(c))""".stripMargin

      err(src) should include("'p' of 'peek' is *Cell, but &Cell was given")
    }
  }
}
