package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** The raw tier at run time (`03 § Reinterpreting storage`): what a type's storage costs, and
 * reading an address as a pointer to something else.
 *
 * The three operations exist for one customer between them — an allocator carves bytes, hands back a
 * typed pointer, and has to know how wide the thing it is pointing at is — so the suite ends with
 * that program rather than only with the pieces.
 */
class RawStorageRunTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "'sizeof' answers what the compiler already measures" - {
    "scalars are their own width" in {
      run("print(sizeof(u8), sizeof(int), sizeof(i64), sizeof(real), sizeof(bool), sizeof(char))") shouldBe
        "1 4 8 8 1 4\n"
    }
    "an address is eight bytes whatever it points at" in {
      run("struct Node\n    v: int\nprint(sizeof(*u8), sizeof(*Node), sizeof(&Node))") shouldBe "8 8 8\n"
    }
    "an array is its elements end to end, and is also the stride" in {
      run("print(sizeof([16]u8), sizeof([16]int), sizeof([0]int))") shouldBe "16 64 0\n"
    }
    "a slice and a string are three words, as this chapter says they are" in {
      run("print(sizeof([]int), sizeof(string))") shouldBe "24 24\n"
    }
    "a struct is laid out in declaration order, padding included" in {
      val src =
        """struct Node
          |    value: int
          |    next: *Node
          |struct Packed
          |    a: u8
          |    b: u8
          |print(sizeof(Node), sizeof(Packed))""".stripMargin

      run(src) shouldBe "16 2\n"
    }
    "the order is the programmer's, so two of the same fields can cost differently" in {
      val src =
        """struct Loose
          |    a: u8
          |    b: i64
          |    c: u8
          |struct Tight
          |    b: i64
          |    a: u8
          |    c: u8
          |print(sizeof(Loose), sizeof(Tight))""".stripMargin

      run(src) shouldBe "24 16\n"
    }
    "a tuple lays out as the struct it is" in {
      run("print(sizeof((int, real)), sizeof((u8, u8)))") shouldBe "16 2\n"
    }
  }

  "'alignof' answers the other half" in {
    val src =
      """struct Node
        |    value: int
        |    next: *Node
        |print(alignof(u8), alignof(int), alignof(real), alignof(*u8), alignof(Node), alignof([16]u8))""".stripMargin

    run(src) shouldBe "1 4 8 8 8 1\n"
  }

  "both are compile-time constants, so they stand where a constant is required" - {
    "in a 'const'" in {
      run("struct Node\n    v: int\n    n: *Node\nconst BLOCK: usize = sizeof(Node)\nprint(BLOCK)") shouldBe "16\n"
    }
    "in an array bound, arithmetic included" in {
      val src =
        """struct Node
          |    v: int
          |    n: *Node
          |var slab: [sizeof(Node) * 4]u8 = [0u8; sizeof(Node) * 4]
          |print(slab.len)""".stripMargin

      run(src) shouldBe "64\n"
    }
    "in a 'require'" in {
      run("check(n: int)\n    require sizeof(int) == 4\n    print(n)\ncheck(7)") shouldBe "7\n"
    }
  }

  /** The measurement a generic container needs. A type parameter has no answer while the body is
   * walked with its parameters standing in for themselves; it has one at every instantiation, which
   * is where the body is compiled.
   */
  "a type parameter may be asked, and answers per instantiation" in {
    val src =
      """width[T](x: T) -> usize
        |    sizeof(T)
        |struct Node
        |    v: int
        |    n: *Node
        |var node = Node(1, null)
        |print(width(3u8), width(3.0), width(&node), width(node))""".stripMargin

    run(src) shouldBe "1 8 8 16\n"
  }

  "an address is read as a number, and the number back as an address" - {
    "the round trip is the identity" in {
      val src =
        """var arena: [64]u8 = [0u8; 64]
          |var p: *u8 = &arena[8]
          |var n = usize(p)
          |var q: *u8 = ptr_cast(n)
          |print(q == p)""".stripMargin

      run(src) shouldBe "true\n"
    }
    "the number is an address, so a difference of addresses counts bytes" in {
      val src =
        """var arena: [64]u8 = [0u8; 64]
          |print(usize(&arena[8]) - usize(&arena[0]))""".stripMargin

      run(src) shouldBe "8\n"
    }
    "'isize' takes one too" in {
      run("var arena: [8]u8 = [0u8; 8]\nprint(isize(&arena[0]) > 0)") shouldBe "true\n"
    }
  }

  "a pointer read as another pointee reaches the same storage" - {
    "a write through the reinterpreted pointer lands in the bytes it came from" in {
      val src =
        """struct Pair
          |    a: u8
          |    b: u8
          |var arena: [8]u8 = [0u8; 8]
          |var pair: *Pair = ptr_cast(&arena[0])
          |pair.a = 3u8
          |pair.b = 9u8
          |print(arena[0], arena[1], arena[2])""".stripMargin

      run(src) shouldBe "3 9 0\n"
    }
    "and the reinterpretation is reversible" in {
      val src =
        """struct Pair
          |    a: u8
          |    b: u8
          |var arena: [8]u8 = [0u8; 8]
          |var pair: *Pair = ptr_cast(&arena[0])
          |var back: *u8 = ptr_cast(pair)
          |back[1] = 5u8
          |print(pair.b)""".stripMargin

      run(src) shouldBe "5\n"
    }
    "a result position supplies the target as a binding does" in {
      val src =
        """struct Node
          |    v: int
          |    n: *Node
          |head(p: *u8) -> *Node
          |    ptr_cast(p)
          |var arena: [32]u8 = [0u8; 32]
          |var node = head(&arena[0])
          |node.v = 11
          |node.n = null
          |print(node.v, node.n == null)""".stripMargin

      run(src) shouldBe "11 true\n"
    }
    "so does a field's declared type" in {
      val src =
        """struct Node
          |    v: int
          |    n: *Node
          |struct Slab
          |    first: *Node
          |var arena: [32]u8 = [0u8; 32]
          |var s = Slab(ptr_cast(&arena[0]))
          |s.first.v = 4
          |print(s.first.v)""".stripMargin

      run(src) shouldBe "4\n"
    }
  }

  /** The pointer moves the raw tier already had, kept here because the allocator below rests on them
   * and nothing else states them as one claim: a write through a bare pointer at a literal index, an
   * element address at a computed one, and a bare pointer advanced with no array in scope.
   */
  "the arithmetic an allocator walks with" - {
    "a write through a raw pointer at a literal index" in {
      run("var arena: [64]u8 = [0u8; 64]\nvar p: *u8 = &arena[0]\np[16] = 9u8\nprint(arena[16])") shouldBe "9\n"
    }
    "an element address at a computed index" in {
      val src =
        """var arena: [64]u8 = [0u8; 64]
          |var i = 32
          |var q: *u8 = &arena[i]
          |*q = 5u8
          |print(arena[32])""".stripMargin

      run(src) shouldBe "5\n"
    }
    "a bare pointer advanced inside a function that never sees the array" in {
      val src =
        """at(p: *u8, i: int) -> *u8
          |    &p[i]
          |var arena: [64]u8 = [0u8; 64]
          |var q = at(&arena[0], 40)
          |*q = 3u8
          |print(arena[40])""".stripMargin

      run(src) shouldBe "3\n"
    }
  }

  /** The program the section exists for: a slab carved out of a fixed array, generic in what it
   * holds, with the free list threaded through the free blocks' own storage. Every piece above is
   * load-bearing here — `sizeof(T)` sizes the block, `&p[i]` finds it, and `ptr_cast` is what hands
   * back something typed at each end.
   */
  "a generic slab allocator, which needs all three" in {
    val src =
      """struct Node
        |    value: int
        |    next: *Node
        |
        |carve[T](p: *u8, i: usize) -> *T
        |    ptr_cast(&p[i * sizeof(T)])
        |
        |var arena: [128]u8 = [0u8; 128]
        |var base: *u8 = &arena[0]
        |
        |// Thread a free list through the blocks themselves, back to front.
        |var free: *Node = null
        |var i = 0
        |while i < 4 do
        |    var block: *Node = carve(base, usize(3 - i))
        |    block.value = 3 - i
        |    block.next = free
        |    free = block
        |    i += 1
        |
        |// Walking it visits the blocks in the order they were pushed.
        |var walk = free
        |while walk != null do
        |    print(walk.value, usize(walk) - usize(base))
        |    walk = walk.next""".stripMargin

    run(src) shouldBe "0 0\n1 16\n2 32\n3 48\n"
  }
}
