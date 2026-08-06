package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A trait implemented for every type of a **shape** (`02`).
 *
 * `impl[T] Total for []T` is the block a generic type gets, written for a type that has no name to
 * be generic over: the subject is the shape applied to the block's own parameters, the members are
 * monomorphized from each receiver's element type, and a bound on a parameter makes the whole thing
 * conditional — a slice implements the trait exactly when its element does.
 *
 * The one thing a shape needs that a generic type does not is a key of its own, since a composed
 * type is filed under the whole of itself (`[]int`, not `[]`). Dropping the arguments is what makes
 * one, and everything that looks a member up falls back to it.
 */
class ImplShapeRunTests extends AnyFreeSpec with RunSupport {

  private val total = "trait Total\n    total(self) -> usize\n"

  "a slice of any element" - {

    "carries a method, monomorphized from the receiver's element type" in {
      run(
        s"""${total}impl[T] Total for []T
           |    total(self) -> usize = self.len
           |var a = [1, 2, 3, 4]
           |var b = ["x", "y"]
           |print(a[0..].total())
           |print(b[0..].total())""".stripMargin,
      ) shouldBe "4\n2\n"
    }

    "carries a property, read with no parentheses" in {
      run(
        """trait Sized
          |    empty -> bool
          |impl[T] Sized for []T
          |    empty -> bool = self.len == 0usize
          |var a = [1, 2]
          |var b: [0]bool = []
          |print(a[0..].empty)
          |print(b[0..].empty)""".stripMargin,
      ) shouldBe "false\ntrue\n"
    }

    // Iterating yields the element type, which under a bound is the parameter standing in for
    // itself — so what the loop body may do with it is what the bound licenses, checked once.
    "iterates its elements under whatever the bound licenses" in {
      run(
        """trait Show
          |    show(self) -> string
          |impl[T: Display] Show for []T
          |    show(self) -> string
          |        var s = ""
          |        for x in self do s = s + str(x)
          |        s
          |var a = [1, 2, 3]
          |var b = ["x", "y"]
          |print(a[0..].show())
          |print(b[0..].show())""".stripMargin,
      ) shouldBe "123\nxy\n"
    }

    // The bound is what the members may assume of the element, so the body reaches the element's own
    // implementation exactly as a bounded generic function's body does.
    "reaches its element's implementation through the block's bound" in {
      run(
        """trait Show
          |    show(self) -> string
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = f"P${self.v}"
          |impl[T: Show] Show for []T
          |    show(self) -> string
          |        var s = ""
          |        for i in 0..<self.len do s = s + self[i].show()
          |        s
          |var a = [P(1), P(2)]
          |print(a[0..].show())""".stripMargin,
      ) shouldBe "P1P2\n"
    }

    // `Self` is the subject applied to whatever fixed the parameters, so it means `[]int` inside the
    // instantiation that a `[]int` receiver asked for.
    "writes 'Self' for the type it was instantiated at" in {
      run(
        s"""trait Dup
           |    dup(self) -> Self
           |impl[T] Dup for []T
           |    dup(self) -> Self = self
           |var a = [1, 2, 3]
           |print(a[0..].dup().len)""".stripMargin,
      ) shouldBe "3\n"
    }

    "inherits a trait default like any other implementing type" in {
      run(
        s"""trait Named
           |    name(self) -> string
           |    greet(self) -> string = f"hi ${"$"}{self.name()}"
           |impl[T] Named for []T
           |    name(self) -> string = "slice"
           |var a = [1, 2]
           |print(a[0..].greet())""".stripMargin,
      ) shouldBe "hi slice\n"
    }

    "satisfies a bound, so a generic takes one by value" in {
      run(
        s"""${total}impl[T] Total for []T
           |    total(self) -> usize = self.len
           |twice[S: Total](x: S) -> usize = x.total() * 2
           |var a = [5, 6, 7]
           |print(twice(a[0..]))""".stripMargin,
      ) shouldBe "6\n"
    }

    // The object outlives the frame, so the elements the slice views have to as well — which is
    // escape analysis's ordinary rule about a slice, met here by putting the array on the heap.
    "erases to a counted trait object, one table per element type" in {
      run(
        s"""${total}impl[T] Total for []T
           |    total(self) -> usize = self.len
           |sum_of(t: &Total) -> usize = t.total()
           |var a: &[2]int = [10, 20]
           |var b: &[3]bool = [true, false, true]
           |print(sum_of(a[0..]))
           |print(sum_of(b[0..]))""".stripMargin,
      ) shouldBe "2\n3\n"
    }

    // The trait is the program's own: `Display` is the library's and so is every slice, so a shape
    // block for it has no home (`02 § Coherence`) and only the library may write one. What the shape
    // still buys a program is exactly this — one block covering every slice whose element conforms.
    "a conditional shape's member reaches its element through the bound" in {
      run(
        """trait Render
          |    render(self) -> string
          |impl[T: Display] Render for []T
          |    render(self) -> string
          |        var out = "["
          |        for i in 0..<self.len do
          |            if i > 0usize then out = out + ", "
          |            out = out + str(self[i])
          |        out + "]"
          |var a = [3, 1, 4]
          |var b = ["x", "y"]
          |print(a[0..].render())
          |print(b[0..].render())""".stripMargin,
      ) shouldBe "[3, 1, 4]\n[x, y]\n"
    }

    // An operator is a trait method, so it resolves the way a call does — which is the whole reason
    // a member is named by one rule rather than by a name each site builds for itself.
    // An operator is one of the library's catalog traits, so a *shape* carrying one is the library's
    // to write (its tuple rows are exactly that). What a program may write is the composed type
    // spelled out, which its own element type gives a home to.
    "a composed type carries an operator, dispatched to the instantiated member" in {
      run(
        """struct N
          |    v: int
          |impl Eq for []N
          |    eq(self, rhs: Self) -> bool = self.len == rhs.len
          |var a = [N(1), N(2)]
          |var b = [N(3), N(4)]
          |var c = [N(5)]
          |print(a[0..] == b[0..])
          |print(a[0..] == c[0..])""".stripMargin,
      ) shouldBe "true\nfalse\n"
    }

    "reaches another member of the same shape from inside one" in {
      run(
        """trait Two
          |    one(self) -> int
          |    other(self) -> int
          |impl[T] Two for []T
          |    one(self) -> int = 1
          |    other(self) -> int = self.one() + 1
          |var v = ["x"]
          |print(v[0..].other())""".stripMargin,
      ) shouldBe "2\n"
    }

    // A slice's elements belong to whoever made them, so a member taking one by value borrows it —
    // the same contract every other function taking a `[]T` parameter has.
    "taken by value in a loop neither leaks nor frees twice" in {
      run(
        s"""${total}impl[T] Total for []T
           |    total(self) -> usize = self.len
           |var a = ["x", "y", "z"]
           |var acc = 0usize
           |for i in 0..<1000 do acc += a[0..].total()
           |print(acc)""".stripMargin,
      ) shouldBe "3000\n"
    }
  }

  "an array of any element" - {

    // A length written out is a shape of its own: `[2]T` and `[3]T` are two, each covering every
    // element type at its own length. Value generics (`10 §9`) added a second shape *below* these
    // rather than replacing them — `[N]T` covers every length, and is asked second, so a block
    // naming a length still answers first for the arrays of that length.
    "is a shape per length" in {
      run(
        """trait Tag
          |    tag(self) -> string
          |impl[T] Tag for [2]T
          |    tag(self) -> string = "pair"
          |impl[T] Tag for [3]T
          |    tag(self) -> string = "triple"
          |var a: [2]int = [1, 2]
          |var b: [3]string = ["x", "y", "z"]
          |print(a.tag())
          |print(b.tag())""".stripMargin,
      ) shouldBe "pair\ntriple\n"
    }

    "including the empty one, whose length is a shape like any other" in {
      run(
        """trait Tag
          |    tag(self) -> string
          |impl[T] Tag for [0]T
          |    tag(self) -> string = "empty"
          |var a: [0]int = []
          |print(a.tag())""".stripMargin,
      ) shouldBe "empty\n"
    }

    // A `self` receiver copies, which for an array copies every element — so writing through the
    // copy is invisible to the caller, exactly as it is for a struct.
    "takes its receiver by value, so a member sees a copy" in {
      run(
        """trait Bump
          |    bumped(self) -> int
          |impl[T] Bump for [2]T
          |    bumped(self) -> int
          |        self[0] = self[1]
          |        self[0]
          |var a: [2]int = [1, 2]
          |print(a.bumped())
          |print(a[0])""".stripMargin,
      ) shouldBe "2\n1\n"
    }
  }

  "a shape and the types it covers" - {

    // `string` is a view of bytes that are valid UTF-8, and that invariant is the whole difference
    // between it and a `[]u8` — so a block written for every slice has said nothing about it.
    //
    // The block is written for `[]const T` because that is the form that covers both: a `[]T`
    // widens into it, and `s.bytes` already is one. A block for `[]T` covers only the writable
    // half, which the two tests below pin from either side.
    "leave 'string' alone, since a string is not a slice" in {
      run(
        s"""${total}impl[T] Total for []const T
           |    total(self) -> usize = self.len
           |impl Total for string
           |    total(self) -> usize = 0usize
           |var s = "hello"
           |print(s.total())
           |print(s.bytes.total())""".stripMargin,
      ) shouldBe "0\n5\n"
    }

    // One block, both views — the reason to write the read-only form even when the writable one
    // would do, and the same advice C++ gives about `span<const T>`.
    "so a block for '[]const T' covers a writable view and a read-only one alike" in {
      run(
        s"""${total}impl[T] Total for []const T
           |    total(self) -> usize = self.len
           |val k: [4]int = [1, 2, 3, 4]
           |var a = [1, 2, 3]
           |print(k[0..].total(), a[0..].total())""".stripMargin,
      ) shouldBe "4 3\n"
    }

    // The other direction does not hold — a block for `[]T` reserves the right to write — and that
    // refusal is pinned in `ImplShapeErrorTests`, where the error helper lives.

    // A slice and an array of two are two shapes, so one trait may be implemented for both — the
    // key is the shape, and neither of these covers what the other does.
    "are one key each, so two shapes may implement one trait" in {
      run(
        """trait Tag
          |    tag(self) -> string
          |impl[T] Tag for []T
          |    tag(self) -> string = "slice"
          |impl[T] Tag for [2]T
          |    tag(self) -> string = "pair"
          |var a: [2]int = [1, 2]
          |print(a.tag())
          |print(a[0..].tag())""".stripMargin,
      ) shouldBe "pair\nslice\n"
    }

    // A type's members are one namespace whatever brought them, and the shape's are in it — but two
    // *traits* implemented for one type were always allowed, and still are.
    "share a namespace across traits, which distinct names do not disturb" in {
      run(
        s"""${total}trait Tag
           |    tag(self) -> string
           |impl[T] Total for []T
           |    total(self) -> usize = self.len
           |impl Tag for []int
           |    tag(self) -> string = "ints"
           |var a = [1, 2]
           |print(a[0..].total())
           |print(a[0..].tag())""".stripMargin,
      ) shouldBe "2\nints\n"
    }

    // Different lengths are different shapes, so a block for one covers nothing the other does and
    // both may be written.
    "coexist with a written implementation for another length" in {
      run(
        """trait Tag
          |    tag(self) -> string
          |impl[T] Tag for [2]T
          |    tag(self) -> string = "any pair"
          |impl Tag for [3]int
          |    tag(self) -> string = "three ints"
          |var a: [2]string = ["x", "y"]
          |var b: [3]int = [1, 2, 3]
          |print(a.tag())
          |print(b.tag())""".stripMargin,
      ) shouldBe "any pair\nthree ints\n"
    }

    // The control for the refusals: an element that meets the condition passes at every place the
    // condition is asked — a bound, an erasure, a rendering.
    "conform at each place the condition is asked, when the element meets it" in {
      run(
        """trait Show
          |    show(self) -> string
          |struct P
          |    v: int
          |impl Show for P
          |    show(self) -> string = str(self.v)
          |impl[T: Show] Show for []T
          |    show(self) -> string = self[0].show()
          |tell[S: Show](x: S) -> string = x.show()
          |var a: &[1]P = [P(7)]
          |var o: &Show = a[0..]
          |print(tell(a[0..]))
          |print(o.show())""".stripMargin,
      ) shouldBe "7\n7\n"
    }

    // Conditional conformance composes: a slice of slices conforms when the inner slice does, which
    // is the same question asked one step further in.
    "compose, so a slice of slices conforms when the inner one does" in {
      run(
        s"""trait Show
           |    show(self) -> string
           |impl[T: Show] Show for []T
           |    show(self) -> string
           |        var s = "("
           |        for i in 0..<self.len do s = s + self[i].show()
           |        s + ")"
           |impl Show for int
           |    show(self) -> string = str(self)
           |var inner: &[2]int = [1, 2]
           |var outer = [inner[0..], inner[1..]]
           |print(outer[0..].show())""".stripMargin,
      ) shouldBe "((12)(2))\n"
    }
  }
}
