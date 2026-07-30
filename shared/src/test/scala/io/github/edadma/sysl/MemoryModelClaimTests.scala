package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What `03-memory-model.md` claims, run rather than read.
 *
 * The chapter's mechanisms already have suites of their own — `ArcRunTests` for reference
 * semantics, `PointerRunTests` for the raw tier, `RecursiveTeardownRunTests` for the iterative
 * teardown, `WeakReferenceTests` for `weak`, `SharedObjectTests` for `&sync`. What none of them
 * covers is the chapter's *connective* prose: the sentences that enumerate something, or that say
 * where a rule stops. Those are the sentences that go stale without a test failing, and two of them
 * had.
 *
 * The first was the definition of a **place**, which named three kinds and left out the element —
 * while the same chapter's pointer-difference example writes `&buf[0]`, and the diagnostic for a
 * non-place recited the same short list back at the reader. The second was the **one level** limit
 * on automatic dereference: the rule holds, but a receiver with more indirection than that fell
 * through to "cannot read field 'x' of *Point", naming what was left after the one dereference
 * rather than what the reader wrote, and reporting a missing field instead of a shorthand that
 * stops short.
 */
class MemoryModelClaimTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val point =
    """struct Point
      |    x: int
      |    y: int
      |""".stripMargin

  "a construction goes on the heap wherever a '&T' is what was asked for, and the positions are" - {
    "a declared local, with no expectation making it an ordinary value" in {
      run(s"${point}var p: &Point = Point(1, 2)\nvar q = Point(3, 4)\nprint(p.x, q.x)") shouldBe "1 3\n"
    }

    "a parameter, a return type, a struct field, and an enum variant's payload" in {
      val src =
        s"""$point
           |struct Holder
           |    p: &Point
           |enum Maybe
           |    Has(p: &Point)
           |    Nothing
           |take(p: &Point) -> int = p.x
           |make() -> &Point = Point(3, 4)
           |var h = Holder(Point(5, 6))
           |var m = Maybe.Has(Point(7, 8))
           |print(take(Point(1, 2)))
           |print(make().x)
           |print(h.p.x)
           |m match
           |    Has(q) -> print(q.y)
           |    Nothing -> print(0)
           |""".stripMargin

      run(src) shouldBe "1\n3\n5\n8\n"
    }

    "whatever produced it, and something already a '&T' passes through, leaving two names on one object" in {
      val src =
        s"""$point
           |id(p: Point) -> Point = p
           |var a: &Point = id(Point(1, 2))
           |var already: &Point = Point(9, 9)
           |var same: &Point = already
           |same.x = 4
           |print(a.x, already.x)
           |""".stripMargin

      run(src) shouldBe "1 4\n"
    }

    "a branch at a time, so a value branch and an already-'&T' branch meet at '&T'" in {
      val src =
        s"""$point
           |struct Holder
           |    origin: &Point
           |var q = Holder(Point(9, 9))
           |var c = true
           |var p: &Point = if c then Point(1, 2) else q.origin
           |var d = false
           |var r: &Point = if d then Point(1, 2) else q.origin
           |print(p.x, r.x)
           |""".stripMargin

      run(src) shouldBe "1 9\n"
    }

    "including through a loop's breaks, which is how a loop yields a value at all" in {
      val src =
        s"""$point
           |var p: &Point = for i in 0..<3 do
           |    if i == 1 then break Point(i, 0)
           |else
           |    Point(7, 7)
           |print(p.x)
           |""".stripMargin

      run(src) shouldBe "1\n"
    }

    "and a scalar is referenced without 'int' needing a constructor of its own" in {
      run("var n: &int = 0\nn = 5\nvar m: &int = 7\nprint(*n, *m + *n)") shouldBe "5 12\n"
    }
  }

  "a value copies, and a reference inside one is shared by the copy rather than duplicated" in {
    val src =
      s"""$point
         |struct Holder
         |    p: &Point
         |    n: int
         |var h = Holder(Point(1, 2), 5)
         |var copy = h
         |copy.n = 9
         |copy.p.x = 42
         |print(h.n, copy.n)
         |print(h.p.x, copy.p.x)
         |""".stripMargin

    run(src) shouldBe "5 9\n42 42\n"
  }

  "a place is a local, a dereference, an element, or a field of any of them" - {
    "an element of a fixed array has an address, which is what the chapter's own example takes" in {
      val src =
        """var buf = [10, 20, 30]
          |var hit = &buf[1]
          |var at = usize(hit - &buf[0])
          |print(at)
          |*hit = 99
          |print(buf[1])
          |""".stripMargin

      run(src) shouldBe "1\n99\n"
    }

    "so does an element of a slice, and of a view taken out of one" in {
      run("var buf: []int = [10, 20, 30]\nvar p = &buf[2]\nprint(*p)") shouldBe "30\n"
      run("var buf = [10, 20, 30]\nvar s = buf[0..<2]\nvar p = &s[1]\nprint(*p)") shouldBe "20\n"
    }

    """a slice's element is a place even when the slice is a temporary, because the element is in
      |the buffer rather than in the view — which is what makes a write through a call land""".stripMargin in {
      val src =
        """struct Grid
          |    cells: []int
          |row(g: &Grid) -> []int = g.cells
          |var g: &Grid = Grid([1, 2, 3])
          |row(g)[1] = 99
          |print(g.cells[1])
          |var p = &row(g)[2]
          |*p = 42
          |print(g.cells[2])
          |""".stripMargin

      run(src) shouldBe "99\n42\n"
    }

    "and anything computed is not one — a call's result, an arithmetic result, a fresh struct" in {
      for src <- List(
          "f() -> int = 3\nvar p = &f()",
          "var a = 1\nvar b = 2\nvar p = &(a + b)",
          s"${point}var p = &Point(1, 2)",
        )
      do err(src) should include("'&' needs a variable, a field, an element, or a dereference")
    }

    "the complaint names the element too, since leaving it out denied a form the chapter uses" in {
      err("f() -> int = 3\nvar p = &f()") should include("a variable, a field, an element, or a dereference")
    }

    "a string's own subscript is refused for immutability rather than for having no address" in {
      err("var s = \"hi\"\ns[0] = 74u8") should
        include("a string is immutable, so its bytes have no address to write through")
    }
  }

  /** `03`'s headline guarantee is that a program containing no `*T` cannot segfault, and `04` says a
   * `string` is immutable. `s.bytes` reinterprets a string's three words as a `[]u8` without copying,
   * so its elements are the string's storage — and a `[]u8` permits writes. Writing one byte of a
   * **literal** wrote into read-only memory and killed the process (exit 138) out of a program with
   * no `*T` in it; writing one byte of a **heap** string silently mutated a value the language calls
   * immutable, and can leave a `string` that is not valid UTF-8.
   *
   * The assignment is closed below. `&s.bytes[i]` is deliberately left open — it is a `*T` the
   * moment it is written, which is the tier the guarantee excludes, and it is how a string reaches
   * `printf("%.*s")`. The two `ignore`d tests carry the assertions the language owes and cannot yet
   * make: once the view is bound to a name or handed to a function, a `[]T` records nothing about
   * whose elements it views, which is exactly the read-only view type `07 § Not yet` is waiting on.
   * They are written as they should read when it exists.
   */
  "a string's bytes are the string's own storage, so writing one is writing the string" - {
    "the subscript is refused, and the message says the view is not a copy" in {
      val e = err("var s = \"hi\"\ns.bytes[0] = 74u8")

      e should include("'bytes' views the string's own storage rather than a copy of it")
      e should include("copy them into a '[]u8' first")
    }

    """taking the address of one is still allowed, because that is a '*T' and the guarantee is
      |about programs that have none — it is also how a string reaches printf's '%.*s'""".stripMargin in {
      val src =
        """extern printf(fmt: *u8, ...) -> int
          |var s = "hello"
          |printf(c"[%.*s]\n", int(s.bytes.len), &s.bytes[0])
          |""".stripMargin

      run(src) shouldBe "[hello]\n"
    }

    "reading them is untouched, since reading is what the view is for" in {
      run("var s = \"hi\"\nprint(s.bytes[0], s.bytes.len)") shouldBe "104 2\n"
    }

    "a copy of the string gives bytes that may be read the same way" in {
      run("var s = \"hi\"\nvar c = s.copy()\nprint(c.bytes[1])") shouldBe "105\n"
    }

    // TODO: un-ignore once a view can record that its elements are read-only.
    "the view refuses a write once it has been bound to a name" ignore {
      err("var s = \"hello\"\nvar b = s.bytes\nb[0] = 74u8") should include("a string is immutable")
    }

    // TODO: un-ignore once a view can record that its elements are read-only.
    "and once it has been handed to something that takes a '[]u8'" ignore {
      errOf(
        "main.sysl" ->
          """poke(b: []u8)
            |    b[0] = 74u8
            |var s = "hello"
            |poke(s.bytes)
            |""".stripMargin,
      ) should include("a string is immutable")
    }
  }

  "selection dereferences one level and only one" - {
    "so a field is reached through a '*T' and through a '&T' with nothing written" in {
      run(s"${point}var v = Point(1, 2)\nvar p = &v\nvar r: &Point = Point(3, 4)\nprint(p.x, r.y)") shouldBe "1 4\n"
    }

    "and a '**T' is told that the shorthand stops short, not that the field does not exist" in {
      val e = err(s"${point}var v = Point(1, 2)\nvar p = &v\nvar pp = &p\nprint(pp.x)")

      e should include("selection reaches through one level of indirection and **Point has more")
      e should include("'(*x).x'")
      e should not include "cannot read field"
    }

    "while the written dereference gets through" in {
      run(s"${point}var v = Point(1, 2)\nvar p = &v\nvar pp = &p\nprint((*pp).x)") shouldBe "1\n"
    }

    "and a type that genuinely has no such field keeps the plain complaint" in {
      err("var n = 5\nprint(n.x)") should include("cannot read field 'x' of int")
    }
  }

  "a match does not reach through a mode, so matching a reference to an enum writes the '*'" - {
    val shape =
      """enum Shape
        |    Circle(r: int)
        |    Square(s: int)
        |""".stripMargin

    "through a '&T'" in {
      run(s"${shape}var e: &Shape = Shape.Circle(3)\n*e match\n    Circle(r) -> print(\"circle\", r)\n" +
        "    Square(s) -> print(\"square\", s)") shouldBe "circle 3\n"
      err(s"${shape}var e: &Shape = Shape.Circle(3)\ne match\n    Circle(r) -> print(r)\n" +
        "    Square(s) -> print(s)") should include("'*x match'")
    }

    "and through a '*T'" in {
      run(s"${shape}var v = Shape.Square(4)\nvar e = &v\n*e match\n    Circle(r) -> print(\"circle\", r)\n" +
        "    Square(s) -> print(\"square\", s)") shouldBe "square 4\n"
      err(s"${shape}var v = Shape.Square(4)\nvar e = &v\ne match\n    Circle(r) -> print(r)\n" +
        "    Square(s) -> print(s)") should include("'*x match'")
    }
  }

  "a cycle is legal per cycle rather than per field" - {
    "so two types reaching each other through one pointer edge have a finite size" in {
      run("struct A\n    b: B\nstruct B\n    a: *A\nvar x: A\nprint(x.b.a == null)") shouldBe "true\n"
    }

    "and one with no indirection anywhere on it is refused, naming the type" in {
      err("struct A\n    b: B\nstruct B\n    a: A\nvar x: A\nprint(1)") should
        include("type 'A' contains itself, so it has no finite size")
    }
  }

  "null exists only for the raw tier" - {
    "so a reference refuses it, and names what an absent one is written as" in {
      err("var r: &int = null\nprint(*r)") should
        include("a &int always points at a live object — an absent one is Option[&int]")
    }

    "and pointers have equality with no ordering, since an address answers nothing else" in {
      err("var a = 1\nvar b = 2\nvar p = &a\nvar q = &b\nprint(p < q)") should
        include("'<' is not defined for *int")
    }
  }
}
