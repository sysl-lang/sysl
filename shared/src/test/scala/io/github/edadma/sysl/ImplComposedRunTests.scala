package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A trait implemented for a type that has no name of its own (`02`).
 *
 * `impl Show for []int` is as ordinary as `impl Show for Point`: the members are filed under the
 * type the same way, reached by a call, a bound, or a table the same way, and the only thing that
 * had to differ is the symbol they are emitted under — `[]int` is a fine key and an impossible
 * LLVM name.
 */
class ImplComposedRunTests extends AnyFreeSpec with RunSupport {

  "a slice" - {

    "carries an impl whose method resolves on a value of it" in {
      run(
        """trait Total
          |    total(self) -> int
          |impl Total for []int
          |    total(self) -> int
          |        var sum = 0
          |        for x in self do sum += x
          |        sum
          |var a = [1, 2, 3, 4]
          |print(a[0..].total())""".stripMargin,
      ) shouldBe "10\n"
    }

    "carries a property, read with no parentheses" in {
      run(
        """trait Ends
          |    first -> int
          |impl Ends for []int
          |    first -> int = self[0]
          |var a = [7, 8, 9]
          |print(a[0..].first)""".stripMargin,
      ) shouldBe "7\n"
    }

    // The element type is part of the type, so two slices with different elements are two
    // implementations and each member call finds its own.
    "of two element types carries two independent impls" in {
      run(
        """trait Tag
          |    tag(self) -> string
          |impl Tag for []int
          |    tag(self) -> string = "ints"
          |impl Tag for []bool
          |    tag(self) -> string = "bools"
          |var a = [1, 2]
          |var b = [true]
          |print(a[0..].tag())
          |print(b[0..].tag())""".stripMargin,
      ) shouldBe "ints\nbools\n"
    }

    "renders through Display, so print and f-strings reach it" in {
      run(
        """struct N
          |    v: int
          |impl Display for N
          |    display(self, out: *Writer, fmt: FormatSpec) = display_int(i64(self.v), out, fmt)
          |impl Display for []N
          |    display(self, out: *Writer, fmt: FormatSpec)
          |        out.write("[".bytes)
          |        for i in 0..<self.len do
          |            if i > 0usize then out.write(", ".bytes)
          |            self[i].display(out, FormatSpec(0, -1, false))
          |        out.write("]".bytes)
          |var a = [N(3), N(1), N(4)]
          |print(a[0..])
          |print(f"${a[0..]}%8s|")""".stripMargin,
      ) shouldBe "[3, 1, 4]\n[3, 1, 4]|\n"
    }

    "satisfies a bound, so a generic takes one by value" in {
      run(
        """trait Total
          |    total(self) -> int
          |impl Total for []int
          |    total(self) -> int
          |        var sum = 0
          |        for x in self do sum += x
          |        sum
          |twice[T: Total](x: T) -> int = x.total() * 2
          |var a = [5, 6]
          |print(twice(a[0..]))""".stripMargin,
      ) shouldBe "22\n"
    }

    // The object outlives the frame, so the elements the slice views have to as well — which is
    // escape analysis's ordinary rule about a slice, met here by putting the array on the heap.
    "erases to a counted trait object and dispatches through the table" in {
      run(
        """trait Total
          |    total(self) -> int
          |struct One
          |    v: int
          |impl Total for One
          |    total(self) -> int = self.v
          |impl Total for []int
          |    total(self) -> int
          |        var sum = 0
          |        for x in self do sum += x
          |        sum
          |sum_of(t: &Total) -> int = t.total()
          |var a: &[2]int = [10, 20]
          |print(sum_of(a[0..]))
          |print(sum_of(One(7)))""".stripMargin,
      ) shouldBe "30\n7\n"
    }

    "inherits a trait default like any other implementing type" in {
      run(
        """trait Total
          |    total(self) -> int
          |    doubled(self) -> int = self.total() * 2
          |impl Total for []int
          |    total(self) -> int
          |        var sum = 0
          |        for x in self do sum += x
          |        sum
          |var a = [1, 2, 3]
          |print(a[0..].doubled())""".stripMargin,
      ) shouldBe "12\n"
    }

    // A slice's elements belong to whoever made them, so a member taking one by value borrows it —
    // the same contract every other function taking a `[]T` parameter has.
    "taken by value in a loop neither leaks nor frees twice" in {
      run(
        """trait Total
          |    total(self) -> int
          |impl Total for []int
          |    total(self) -> int
          |        var sum = 0
          |        for x in self do sum += x
          |        sum
          |var a = [1, 2, 3]
          |var acc = 0
          |for i in 0..<1000 do acc += a[0..].total()
          |print(acc)""".stripMargin,
      ) shouldBe "6000\n"
    }
  }

  "a string" - {

    // `string` was already implementable; what is new is that a slice of its bytes is too, so the
    // two can carry the same trait and be told apart by it.
    "and a slice of its bytes are two distinct implementations" in {
      run(
        """trait Tag
          |    tag(self) -> string
          |impl Tag for string
          |    tag(self) -> string = "text"
          |impl Tag for []u8
          |    tag(self) -> string = "bytes"
          |var s = "hi"
          |print(s.tag())
          |print(s.bytes.tag())""".stripMargin,
      ) shouldBe "text\nbytes\n"
    }
  }

  "an array" - {

    "carries an impl, and its length is part of the type it is for" in {
      run(
        """trait Tag
          |    tag(self) -> string
          |impl Tag for [2]int
          |    tag(self) -> string = "pair"
          |impl Tag for [3]int
          |    tag(self) -> string = "triple"
          |var a: [2]int = [1, 2]
          |var b: [3]int = [1, 2, 3]
          |print(a.tag())
          |print(b.tag())""".stripMargin,
      ) shouldBe "pair\ntriple\n"
    }

    // A `self` receiver copies, which for an array copies every element — so writing through the
    // copy is invisible to the caller, exactly as it is for a struct.
    "takes its receiver by value, so a member sees a copy" in {
      run(
        """trait Bump
          |    bumped(self) -> int
          |impl Bump for [2]int
          |    bumped(self) -> int
          |        self[0] += 100
          |        self[0]
          |var a: [2]int = [1, 2]
          |print(a.bumped())
          |print(a[0])""".stripMargin,
      ) shouldBe "101\n1\n"
    }

    "of a struct element carries one too" in {
      run(
        """struct P
          |    v: int
          |trait Total
          |    total(self) -> int
          |impl Total for [2]P
          |    total(self) -> int = self[0].v + self[1].v
          |var a: [2]P = [P(3), P(4)]
          |print(a.total())""".stripMargin,
      ) shouldBe "7\n"
    }
  }

  "a nested composed type" - {

    "is one type, and one implementation" in {
      run(
        """trait Tag
          |    tag(self) -> string
          |impl Tag for [][]int
          |    tag(self) -> string = "rows"
          |impl Tag for []int
          |    tag(self) -> string = "row"
          |var a = [1, 2]
          |print(a[0..].tag())""".stripMargin,
      ) shouldBe "row\n"
    }

    // What an `impl` may not be for is a *generic* type — one whose parameters are still open. A
    // generic type already applied is a type like any other, and a slice of one is too.
    "may hold an instantiated generic, which is concrete" in {
      run(
        """struct Box[T]
          |    v: T
          |trait Total
          |    total(self) -> int
          |impl Total for []Box[int]
          |    total(self) -> int = self[0].v + self[1].v
          |var a = [Box(3), Box(4)]
          |print(a[0..].total())""".stripMargin,
      ) shouldBe "7\n"
    }
  }

  "the rest of the member surface reaches a composed type unchanged" - {

    // `Self` is bound to whatever the `impl` is for, and a slice is no different: it is one type,
    // so the spelling in the signature and the type it resolves to are the same thing.
    "Self in a signature means the type the impl is for" in {
      run(
        """trait Head
          |    head(self) -> Self
          |impl Head for []int
          |    head(self) -> Self = self[0..<1]
          |var a = [4, 5, 6]
          |var h = a[0..].head()
          |print(h.len)
          |print(h[0])""".stripMargin,
      ) shouldBe "1\n4\n"
    }

    // A `*self` receiver takes the instance's address, so it needs a place to point at — which a
    // slice held in a `var` is and a freshly-taken one is not. The receiver is a `*[]int` like any
    // other raw pointer, so reaching the slice itself is the written `*` it always is.
    "a pointer receiver writes through to the slice it was called on" in {
      run(
        """trait Retake
          |    drop_first(*self)
          |impl Retake for []int
          |    drop_first(*self)
          |        *self = (*self)[1..]
          |var a = [1, 2, 3]
          |var s = a[0..]
          |s.drop_first()
          |print(s.len)
          |print(s[0])""".stripMargin,
      ) shouldBe "2\n2\n"
    }

    "str renders one through its Display exactly as print does" in {
      run(
        """struct N
          |    v: int
          |impl Display for []N
          |    display(self, out: *Writer, fmt: FormatSpec)
          |        display_int(i64(self.len), out, fmt)
          |var a = [N(1), N(2), N(3)]
          |var s = str(a[0..])
          |print(s)""".stripMargin,
      ) shouldBe "3\n"
    }

    // Two traits over one type put their members in the one table, so the second implementation
    // sees the first's — which is the same rule an inherent member and an `impl` method share.
    "two traits may be implemented for one composed type" in {
      run(
        """trait A
          |    a(self) -> int
          |trait B
          |    b(self) -> int
          |impl A for []int
          |    a(self) -> int = self[0]
          |impl B for []int
          |    b(self) -> int = self.a() + 1
          |var xs = [9, 9]
          |print(xs[0..].b())""".stripMargin,
      ) shouldBe "10\n"
    }
  }
}
