package sh.sysl

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
    // `library/core.md § What is in it` puts the pointer modes in `Eq` with **address** equality. Two references to objects
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

    // Equality reaches further than ordering
    // (`reference/expressions.md § Equality reaches further than ordering`): a reference has
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

  /** A run of values reached through a pointer — `p[i]` and `p[0..<n]`, both C's and both unchecked.
   *
   * `03` makes `*T` the one unchecked primitive, and this is what that buys: the shape every C
   * function that fills a buffer hands back is a bare address plus a count the caller was told
   * separately, and a language that cannot say it cannot be used where C is. The length is the
   * programmer's assertion, exactly as `*p` already is.
   */
  "a run of values is reached through a raw pointer, unchecked" - {
    "the subscript reads what C's reads" in {
      run("""var a: [4]u8 = [1u8, 2u8, 3u8, 4u8]
            |var p = &a[0]
            |print(p[0usize], p[2usize], p[3usize])
            |""".stripMargin) shouldBe "1 3 4\n"
    }

    "and writes through to the storage it names" in {
      run("""var a: [4]u8 = [1u8, 2u8, 3u8, 4u8]
            |var p = &a[0]
            |p[1usize] = 99u8
            |print(a[1], p[1usize])
            |""".stripMargin) shouldBe "99 99\n"
    }

    // The stride is the pointee's size, so a pointer into a table of structs walks it. This is the
    // case that would silently read the wrong bytes if the element type were taken from anywhere
    // but the pointer.
    "a pointer to a struct strides by the struct" in {
      run("""struct C
            |    n: int
            |    m: int
            |var cs: [3]C = [C(1, 2), C(3, 4), C(5, 6)]
            |var q = &cs[0]
            |q[0usize].n = 77
            |print(q[2usize].n, q[1usize].m, cs[0].n)
            |""".stripMargin) shouldBe "5 4 77\n"
    }

    "and a pointer to a pointer indexes twice" in {
      run("""go()
            |    var a: [2]u8 = [1u8, 2u8]
            |    var b: [2]u8 = [3u8, 4u8]
            |    var t: [2]*u8 = [&a[0], &b[0]]
            |    var pp = &t[0]
            |    print(pp[1usize][0usize], pp[0usize][1usize])
            |end go
            |go()
            |""".stripMargin) shouldBe "3 2\n"
    }

    // A `val` fixes the address, not what is at it — the same reading C's `T *const p` has, and the
    // same one `*p = v` through a `val` pointer already had.
    "a 'val' pointer still writes through, since what it fixes is the address" in {
      run("""go()
            |    var a: [2]u8 = [1u8, 2u8]
            |    val vp = &a[0]
            |    vp[0usize] = 9u8
            |    print(a[0])
            |end go
            |go()
            |""".stripMargin) shouldBe "9\n"
    }

    "a view of a region is taken by writing where it ends" in {
      run("""var a: [4]u8 = [1u8, 2u8, 3u8, 4u8]
            |var p = &a[0]
            |var v = p[1..<3]
            |print(v.len, v[0usize], v[1usize])
            |""".stripMargin) shouldBe "2 2 3\n"
    }

    // The whole point of the feature, end to end: a C function's `*u8`, a length it reported
    // separately, and the bytes read back and turned into text.
    "which is what makes a buffer a C function filled readable at all" in {
      run("""extern "strlen" c_strlen(p: *u8) -> usize
            |extern "getenv" c_getenv(n: *u8) -> *u8
            |var home = c_getenv(c"HOME")
            |var n = c_strlen(home)
            |var bytes = home[0..<n]
            |var s = from_utf8_unchecked(bytes)
            |print(n == bytes.len, s.len == n, home[0usize] == 47u8)
            |""".stripMargin) shouldBe "true true true\n"
    }
  }

  /** The unchecked half, and the line it is drawn against. A pointer that *does* carry a length
   * keeps every check it had — which is what makes "unchecked" a property of the type rather than
   * of the syntax.
   */
  "and the check is what the pointer's type decides" - {
    // Discriminating: both subscripts are written identically and lower differently. A `*T` gets a
    // bare `getelementptr`; a `*[N]T` keeps the comparison and the trap.
    "a subscript on a '*T' emits no check, and one on a '*[N]T' still does" in {
      val out = mainOf(ir("""var a: [4]u8 = [1u8, 2u8, 3u8, 4u8]
                            |var p = &a[0]
                            |var q = &a
                            |print(p[2usize], q[2usize])
                            |""".stripMargin))

      // Both index by 2 into four elements, so a checked `p[2]` would emit a second comparison
      // identical to the one `q[2]` emits. Exactly one is the whole assertion.
      out.linesIterator.count(_.contains("icmp ult i64 2, 4")) shouldBe 1
    }

    "a pointer to an array views and slices with its length checked" in {
      run("""var a: [4]u8 = [1u8, 2u8, 3u8, 4u8]
            |var p = &a
            |print(p[..].len, p[1..<3].len, p[..][2])
            |""".stripMargin) shouldBe "4 2 3\n"
    }

    // There is nothing to supply the end from, so it has to be written. Left implicit it would be
    // a view of unbounded length, which is the one thing worse than an unchecked one.
    "a view of a '*T' must say where it ends" in {
      err("""var a: [4]u8 = [1u8, 2u8, 3u8, 4u8]
            |var p = &a[0]
            |print(p[..].len)
            |""".stripMargin) should include("needs its end written")
    }

    "and an open high end is the same complaint" in {
      err("""var a: [4]u8 = [1u8, 2u8, 3u8, 4u8]
            |var p = &a[0]
            |print(p[1..].len)
            |""".stripMargin) should include("needs its end written")
    }

    // `05`: a view of a `*T` region has nothing to keep alive, so its owner word is null and
    // counting it is a no-op. Unchanged by the region now being reachable through a bare pointer.
    "a view of a region owns nothing, because there is nothing to own" in {
      ir("""view(p: *u8, n: usize) -> []u8 = p[0..<n]
           |var a: [4]u8 = [1u8, 2u8, 3u8, 4u8]
           |print(view(&a[0], 2usize).len)
           |""".stripMargin) should include("insertvalue { ptr, ptr, i64 } zeroinitializer, ptr null, 0")
    }
  }

  /** `p - q`, C's `ptrdiff_t` (`03`). It is the inverse of `&p[n]`: indexing takes an address and a
    * count to an address, and the difference takes two addresses back to a count. The whole reason it
    * is here is that without it the interior-pointer half of libc — `memchr`, `strchr`, `strstr`,
    * `memmem` — is callable and useless, since each hands back a pointer *into* the caller's buffer
    * and nothing could turn one into an index.
    *
    * It counts **elements**, not bytes, which is C's rule and is what keeps the two operations
    * inverse. A test on a `*u8` cannot tell the two apart — a one-byte stride makes them the same
    * number — so every stride below is exercised at a width where it can.
    */
  "two pointers subtract, counting the elements between them" - {
    "a byte pointer gives the count, and the other way round gives its negative" in {
      run("var a: [8]u8\nvar p = &a[0]\nvar q = &a[3]\nprint(q - p, p - q)") shouldBe "3 -3\n"
    }

    "the same pointer is no distance at all" in {
      run("var a: [8]u8\nvar p = &a[2]\nprint(p - p)") shouldBe "0\n"
    }

    // The discriminating cases: a stride of 4 and a stride of 8, where counting bytes would give 12
    // and 32 instead of 3 and 4.
    "a wider pointee strides by its own size" in {
      run("var a: [8]u32\nprint(&a[3] - &a[0])") shouldBe "3\n"
    }

    "and so does a struct, by the size of the struct" in {
      run("""struct C
            |    n: int
            |    m: int
            |var cs: [8]C
            |print(&cs[5] - &cs[1])""".stripMargin) shouldBe "4\n"
    }

    "a pointer to a pointer counts pointers" in {
      run("var t: [4]*u8\nprint(&t[3] - &t[1])") shouldBe "2\n"
    }

    // The inverse property, stated as one program: taking a pointer `n` elements along and
    // subtracting the original gives `n` back, at a width where a byte count would not.
    "it is the inverse of indexing" in {
      run("""var a: [8]u32
            |var p = &a[0]
            |var n = 5usize
            |print(&p[n] - p == isize(n))""".stripMargin) shouldBe "true\n"
    }

    /** The customer, end to end: `memchr` hands back an interior pointer, the difference turns it
      * into an index, and the index cuts the run of bytes. This is the shape a line reader is built
      * out of, and it is what the operator was added for.
      */
    "which is what makes memchr usable at all" in {
      run("""extern memchr(p: *u8, c: int, n: usize) -> *u8
            |var buf: [8]u8 = [65u8, 66u8, 10u8, 67u8, 0u8, 0u8, 0u8, 0u8]
            |var hit = memchr(&buf[0], 10, 8usize)
            |if hit == null
            |    print("absent")
            |else
            |    var at = usize(hit - &buf[0])
            |    print(at, from_utf8_unchecked(buf[0..<at]))""".stripMargin) shouldBe "2 AB\n"
    }

    "and reports the absence of what it looked for, without a difference to take" in {
      run("""extern memchr(p: *u8, c: int, n: usize) -> *u8
            |var buf: [4]u8 = [65u8, 66u8, 67u8, 68u8]
            |print(memchr(&buf[0], 10, 4usize) == null)""".stripMargin) shouldBe "true\n"
    }

    "a one-byte pointee needs no divide, and a wider one does" in {
      val narrow = mainOf(ir("var a: [8]u8\nprint(&a[3] - &a[0])"))
      val wide   = mainOf(ir("var a: [8]u32\nprint(&a[3] - &a[0])"))

      narrow should include("ptrtoint")
      narrow should not include "sdiv"
      wide should include("sdiv i64")
    }

    "what it is not" - {
      // Two pointees of different types have no shared element to count, and the message is the
      // ordinary matching-types one rather than a rule of its own.
      "two pointers to different types" in {
        err("var a: [4]u8\nvar b: [4]u32\nprint(&a[0] - &b[0])") should
          include("'-' needs matching types, got *byte and *uint")
      }

      // Difference is the only pointer arithmetic here: offsetting is `&p[n]`, which already exists
      // and already strides, so a second spelling for it would be a second thing to keep in step.
      "a pointer and an integer, offsetting being '&p[n]'" in {
        err("var a: [8]u8\nprint(&a[0] - 1)") should include("'-' needs matching types")
      }

      "two pointers added, there being no address to name" in {
        err("var a: [8]u8\nprint(&a[0] + &a[1])") should include("'+' is not defined for *byte")
      }

      // A counted reference is not an address the program is free to do arithmetic on — the whole
      // difference between the two modes (`03`) — so it keeps the equality it had and nothing more.
      "a counted reference, which is not a raw pointer" in {
        err("struct P\n    x: int\nvar a: &P = P(1)\nvar b: &P = P(2)\nprint(a - b)") should
          include("'-' is not defined for &P")
      }
    }
  }
}
