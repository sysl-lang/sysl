package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Storage sized while running (`07 §Storage sized while running`).
 *
 * Every other array form fixes its length in the type, which is the one thing a program reading a
 * file cannot do — the size is in the header, and the header is read by the code that wants the
 * buffer. The heap was never what was missing: `&[64]u8` already put elements there. A length the
 * *program* computes was.
 *
 * A length not in the type is exactly what `[]T` is, so the rule is `03`'s for `&T` rather than a
 * new one: an array form written where a `[]T` is expected makes storage of its own and yields a
 * view of all of it. The declaration carries the choice, the slice's `owner` word carries the
 * storage, and everything about indexing, slicing, `.len`, and release is what it already was.
 *
 * What the feature is *for* is the signature it unblocks — a function that sizes its own buffers
 * and returns one, instead of taking buffers a caller could not have sized.
 */
class HeapBufferTests extends AnyFreeSpec with CodegenSupport with RunSupport {

  "what is expected decides which thing an array form is" - {
    "a repeat under a '[]T' takes a count computed while running" in {
      run(
        """f(n: usize) -> []int = [7; n]
          |var xs = f(3usize)
          |print(xs.len, xs[0], xs[2])""".stripMargin
      ) shouldBe "3 7 7\n"
    }

    "and under a '[N]T' the count is still part of the type" in {
      run(
        """var a: [3]int = [7; 3]
          |print(a.len, a[2])""".stripMargin
      ) shouldBe "3 7\n"
    }

    // The count being constant is the easy case rather than a different one: what makes storage is
    // the context, so a constant count under a `[]T` allocates exactly as a computed one does.
    "a constant count under a '[]T' allocates all the same" in {
      run(
        """var xs: []int = [7; 3]
          |print(xs.len, xs[1])""".stripMargin
      ) shouldBe "3 7\n"
    }

    "an element list under a '[]T' does too" in {
      run(
        """var xs: []int = [1, 2, 3]
          |print(xs.len, xs[0], xs[2])""".stripMargin
      ) shouldBe "3 1 3\n"
    }

    "and an empty one, whose element type its context still fixes" in {
      run(
        """var xs: []int = []
          |print(xs.len)""".stripMargin
      ) shouldBe "0\n"
    }

    // Nothing was added to the *type* side, which is the whole economy of the design: the same
    // `[]int` a parameter takes is what a buffer form produces.
    "a buffer is an ordinary '[]T' everywhere afterwards" in {
      run(
        """total(xs: []int) -> int
          |    var s = 0
          |    for x in xs do s += x
          |    s
          |var xs: []int = [1, 2, 3, 4]
          |var half = xs[1..<3]
          |print(total(xs), total(half), half.len)""".stripMargin
      ) shouldBe "10 5 2\n"
    }
  }

  "the storage leaves the frame, which is what it is for" - {
    // `guide/png`'s `decode` took three slices and two of them were its own intermediate stages,
    // because the sizes are in a header the *callee* reads. This is that signature.
    "a function sizes a buffer from something it read and returns it" in {
      run(
        """decode(src: []u8) -> []int
          |    var n = usize(src[0])
          |    var out: []int = [0; n]
          |    for i in 0..<n do out[i] = int(src[i + 1usize]) * 2
          |    out
          |end decode
          |var src: []u8 = [3, 10, 20, 30]
          |var got = decode(src)
          |print(got.len, got[0], got[2])""".stripMargin
      ) shouldBe "3 20 60\n"
    }

    "and a buffer of buffers, each sized while running" in {
      run(
        """grid(w: usize, h: usize) -> [][]int
          |    var g: [][]int = [[0; w]; h]
          |    for y in 0..<h
          |        for x in 0..<w do g[y][x] = int(y) * 10 + int(x)
          |    g
          |end grid
          |var g = grid(3usize, 2usize)
          |print(g.len, g[0].len, g[1][2])""".stripMargin
      ) shouldBe "2 3 12\n"
    }

    "a struct may hold one, so the sizing crosses a field too" in {
      run(
        """struct Frame
          |    rows: []int
          |    tag: string
          |make(n: usize) -> Frame = Frame([5; n], "hi")
          |var f = make(4usize)
          |print(f.rows.len, f.rows[3], f.tag)""".stripMargin
      ) shouldBe "4 5 hi\n"
    }
  }

  // The point of adding no type is that every claim `07` and `03` already make about a slice has
  // to come out true of one that owns its elements, with nothing said about buffers to make it so.
  "what the documents claim about a slice is true of a buffer" - {
    "the loop evaluates its sequence once, so a buffer nothing holds lives for the whole loop" in {
      run(
        """make(n: usize) -> []int
          |    var xs: []int = [0; n]
          |    for i in 0..<n do xs[i] = int(i) + 1
          |    xs
          |end make
          |var s = 0
          |for x in make(6usize) do s += x
          |print(s)""".stripMargin
      ) shouldBe "21\n"
    }

    // "Taking a slice retains the owner, dropping one releases it" (`07 §Ownership`). The view the
    // buffer arrived as is gone by the time this is read; the sub-slice is what keeps it alive.
    "a sub-slice outlives the view its storage came from" in {
      run(
        """make(n: usize) -> []int
          |    var xs: []int = [0; n]
          |    for i in 0..<n do xs[i] = int(i) + 1
          |    xs
          |end make
          |tail(n: usize) -> []int = make(n)[2..<5]
          |var t = tail(8usize)
          |print(t.len, t[0], t[2])""".stripMargin
      ) shouldBe "3 3 5\n"
    }

    // "An `if`/`match` yields its value through its branches, so the expectation reaches each of
    // them rather than the whole expression" (`03`). Each branch makes storage of its own.
    "a branch each yields a buffer, since the expectation reaches into them" in {
      run(
        """pick(c: bool, n: usize) -> []int = if c then [1; n] else [2; n]
          |print(pick(true, 2usize)[0], pick(false, 2usize)[1])""".stripMargin
      ) shouldBe "1 2\n"
    }

    "a variant's payload fixes the type as a declaration does" in {
      run(
        """maybe(n: usize) -> Option[[]int] = Some([3; n])
          |print(maybe(2usize).unwrap()[1])""".stripMargin
      ) shouldBe "3\n"
    }

    "and a buffer boxes into a '&[]T' like any other value" in {
      run(
        """var boxed: &[]int = [4; 3usize]
          |print((*boxed).len, (*boxed)[0])""".stripMargin
      ) shouldBe "3 4\n"
    }
  }

  "the elements belong to the buffer" - {
    "a buffer of references keeps them alive and lets them go" in {
      run(
        """struct Node
          |    value: int
          |refs(n: usize) -> []&Node
          |    var xs: []&Node = [Node(0); n]
          |    for i in 0..<n do xs[i] = Node(int(i) * 3)
          |    xs
          |end refs
          |var r = refs(4usize)
          |print(r.len, r[0].value, r[3].value)""".stripMargin
      ) shouldBe "4 0 9\n"
    }

    "a buffer of strings likewise" in {
      run(
        """f(n: usize) -> []string = [str(n) + "!"; n]
          |var s = f(3usize)
          |print(s.len, s[0], s[2])""".stripMargin
      ) shouldBe "3 3! 3!\n"
    }

    // The elements are reachable only from the hook that destroys them, which is why the count is
    // in the box: a slice's owner arrives with no static type to consult (`03`, `07 §Ownership`).
    "so the deallocation hook walks them" in {
      val out = defineOf(
        ir(
          """struct Node
            |    value: int
            |f(n: usize) -> []&Node = [Node(0); n]
            |print(f(2usize).len)""".stripMargin
        ),
        "arc.dropbuf.ref.Node",
      )

      out should include("load i64")
      out should include("call void @arc.release")
      out should include("call void @free(ptr %p)")
    }

    // Elements that hold nothing need no walk at all, so the hook is the plain free — a buffer of
    // bytes costs a destructor call and nothing else.
    "and elements that hold nothing get the plain free instead" in {
      val out = ir("""f(n: usize) -> []u8 = [0; n]
                     |print(f(2usize).len)""".stripMargin)

      out should include("store ptr @arc.free")
      out should not include "arc.dropbuf"
    }

    "many buffers of references come apart rather than accumulating" in {
      run(
        """struct Node
          |    value: int
          |refs(n: usize) -> []&Node = [Node(1); n]
          |var i = 0
          |while i < 100000
          |    var t = refs(8usize)
          |    i++
          |print("done")""".stripMargin
      ) shouldBe "done\n"
    }
  }

  "a repeat still evaluates its value exactly once" - {
    // The property that makes `[v; n]` a construction rather than shorthand for writing the value
    // out n times, kept as the count moves from the type into an expression.
    "however many elements it fills" in {
      run(
        """var calls = 0
          |tick(c: *int) -> int
          |    *c += 1
          |    7
          |f(c: *int, n: usize) -> []int = [tick(c); n]
          |var xs = f(&calls, 5usize)
          |print(xs.len, xs[4], calls)""".stripMargin
      ) shouldBe "5 7 1\n"
    }

    "including none at all, since the value is generated above the loop" in {
      run(
        """var calls = 0
          |tick(c: *int) -> int
          |    *c += 1
          |    7
          |f(c: *int, n: usize) -> []int = [tick(c); n]
          |var xs = f(&calls, 0usize)
          |print(xs.len, calls)""".stripMargin
      ) shouldBe "0 1\n"
    }
  }

  "a computed length is checked, since that is where arithmetic goes wrong" - {
    "zero elements is an ordinary buffer, not a special case" in {
      run(
        """f(n: usize) -> []int = [0; n]
          |var xs = f(0usize)
          |print(xs.len)""".stripMargin
      ) shouldBe "0\n"
    }

    "a negative count arrives as a very large one and traps" in {
      exits(
        """f(n: int) -> []int = [0; n]
          |var xs = f(-1)
          |print(xs.len)""".stripMargin
      )
    }

    // 2^62 elements of 8 bytes is 2^65: the byte count wraps, and without the checked multiply it
    // would wrap to something *small* — a buffer far shorter than the length its view reports.
    "a count whose byte size wraps traps rather than allocating a shorter buffer" in {
      exits(
        """f(n: usize) -> []i64 = [0; n]
          |var xs = f(4611686018427387904usize)
          |print(xs.len)""".stripMargin
      )
    }

    "an allocation that fails traps rather than yielding a null to store through" in {
      exits(
        """f(n: usize) -> []i64 = [0; n]
          |var xs = f(1152921504606846976usize)
          |print(xs.len)""".stripMargin
      )
    }

    "both checks are emitted, so neither magnitude relies on the other's" in {
      val out = ir("""f(n: usize) -> []i64 = [0; n]
                     |print(f(2usize).len)""".stripMargin)

      out should include("@llvm.umul.with.overflow.i64")
      out should include("@llvm.uadd.with.overflow.i64")
      out should include("icmp ne ptr")
      out should include("declare { i64, i1 } @llvm.umul.with.overflow.i64(i64, i64)")
    }

    "and an index into a buffer is checked like every other" in {
      exits(
        """f(n: usize) -> []int = [0; n]
          |var xs = f(3usize)
          |print(xs[3])""".stripMargin
      )
    }
  }

  // A count is an index's twin — both are a number of elements read unsigned at 64 bits — so the
  // rule about which types may be one has to be the same rule, and `03`'s is that a transparent
  // constrained subtype stands where its base does while a derived one does not.
  "a count takes the types an index takes" - {
    "a transparent 'within' subtype counts, and indexes, with no cast" in {
      run(
        """type Small = usize within 0..100
          |f(n: Small) -> []int = [7; n]
          |var xs = f(Small(4usize))
          |var i: Small = Small(1usize)
          |print(xs.len, xs[i])""".stripMargin
      ) shouldBe "4 7\n"
    }

    // A derived type is nominally distinct, so reaching its base is what a written conversion is
    // for, and neither a count nor an index is an exception to that.
    "a derived one does neither, since 'new' is nominal" in {
      err(
        """type Count = new usize
          |f(n: Count) -> []int = [7; n]""".stripMargin
      ) should include("a repeat count is a number of elements, and Count is not an integer")
    }

    "and the index refuses it in the same words" in {
      err(
        """type Ix = new usize
          |var a = [1, 2, 3]
          |var i: Ix = Ix(1usize)
          |print(a[i])""".stripMargin
      ) should include("an index must be an integer, not Ix")
    }
  }

  "what is refused" - {
    "a count that is not a number of elements" in {
      err("f(s: string) -> []int = [0; s]") should include(
        "a repeat count is a number of elements, and string is not an integer")
    }

    // The array form's own error, which now says where a computed count *does* belong rather than
    // stopping at the refusal.
    "a computed count where an array was expected, naming the form that takes one" in {
      val message = err(
        """var n = 4
          |var xs = [0; n]""".stripMargin
      )

      message should include("must be a constant")
      message should include("written where a '[]T' is expected")
    }

    "a buffer as a 'val', since a view in storage that outlives every frame is never let go of" in {
      err("val xs: []int = [1, 2, 3]") should include("plain data")
    }

    "elements of a type that has no values" in {
      err("f(n: usize) -> []unit = [(); n]") should include("an array cannot hold unit values")
    }

    // A repeat under no expectation at all is still the array form, so it still wants a constant —
    // the context is what chooses, and an absent one chooses the array.
    "a computed count with no context to make it storage" in {
      err("f(n: usize) = [0; n]") should include("must be a constant")
    }

    // The storage a buffer makes is its own, but what it is *filled with* may still be the frame's,
    // and that is the same escape it always was. Making the elements buffers of their own is now
    // one of the two ways out, which is what the diagnostic says.
    "a buffer filled with views of this frame, since the storage moved and the views did not" in {
      val message = err(
        """leak() -> [][]u8
          |    var local: [4]u8
          |    var xs: [][]u8 = [local[..]; 2]
          |    xs
          |end leak
          |print(leak().len)""".stripMargin
      )

      message should include("would outlive the array")
      message should include("declare the storage as a '[]T'")
    }

    "while the same function keeps its own storage and returns it" in {
      run(
        """keep() -> [][]u8
          |    var local: []u8 = [0; 4usize]
          |    local[1] = 9
          |    var xs: [][]u8 = [local[..]; 2]
          |    xs
          |end keep
          |var g = keep()
          |print(g.len, g[1][1])""".stripMargin
      ) shouldBe "2 9\n"
    }
  }
}
