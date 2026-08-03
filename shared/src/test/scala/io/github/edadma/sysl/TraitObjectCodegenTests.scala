package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-1 lowering of trait objects: the shape of a method table, the two words of the pointer,
 * and the indirect call.
 *
 * What is worth pinning here rather than leaving to the run suite is the part a correct program
 * cannot tell you about — that a slot whose receiver already *is* the data word holds the
 * implementation itself, with no adapter between, and that the counted flavour steps over the box
 * header while the raw one does not.
 */
class TraitObjectCodegenTests extends AnyFreeSpec with CodegenSupport {

  private val raw =
    """trait Shape
      |    area(self) -> int
      |    scale(*self, by: int)
      |struct Rect
      |    w: int
      |    h: int
      |impl Shape for Rect
      |    area(self) -> int = self.w * self.h
      |    scale(*self, by: int)
      |        self.w += by
      |var r = Rect(3, 4)
      |var s: *Shape = &r
      |s.scale(1)
      |print(s.area())""".stripMargin

  private val counted =
    """trait T
      |    go(&self)
      |    v(self) -> int
      |struct S
      |    n: int
      |impl T for S
      |    go(&self)
      |        self.n += 1
      |    v(self) -> int = self.n
      |var o: &T = S(41)
      |o.go()
      |print(o.v())""".stripMargin

  private val withProperty =
    """trait Sized
      |    size -> int
      |struct Box
      |    n: int
      |impl Sized for Box
      |    size -> int = self.n
      |var b = Box(7)
      |var s: *Sized = &b
      |print(s.size)""".stripMargin

  private val generic =
    """trait Total
      |    total(self) -> int
      |struct Box[T]
      |    v: T
      |    n: int
      |impl[T] Total for Box[T]
      |    total(self) -> int = self.n
      |var a: &Total = Box("x", 7)
      |var b: &Total = Box(1, 8)
      |print(a.total(), b.total())""".stripMargin

  private val slice =
    """trait Total
      |    total(self) -> int
      |impl Total for []int
      |    total(self) -> int = 0
      |var a: &[2]int = [1, 2]
      |var t: &Total = a[0..]
      |print(t.total())""".stripMargin

  private val shape =
    """trait Total
      |    total(self) -> int
      |impl[T] Total for []T
      |    total(self) -> int = 1
      |var a: &[2]int = [1, 2]
      |var b: &[3]bool = [true, false, true]
      |var s: &Total = a[0..]
      |var t: &Total = b[0..]
      |print(s.total(), t.total())""".stripMargin

  "the table" - {
    // The order is the trait's declaration order, which is what a call site indexes by, and a
    // `*self` method's receiver already is the data word — so that slot names the implementation
    // and only the by-value one needs an adapter.
    "holds one pointer per method, adapting only where the receiver differs" in {
      ir(raw) should include(
        "@vt.Shape.Rect = private constant [2 x ptr] [ptr @vt.adapt.Rect.area, ptr @Rect.scale]",
      )
    }

    "of a counted object names a '&self' method directly instead" in {
      ir(counted) should include(
        "@vt.ref.T.S = private constant [2 x ptr] [ptr @S.go, ptr @vt.adapt.ref.S.v]",
      )
    }

    "is emitted only for a type something actually erased" in {
      val out = ir(
        """trait Shape
          |    area(self) -> int
          |struct Rect
          |    w: int
          |    h: int
          |struct Sq
          |    s: int
          |impl Shape for Rect
          |    area(self) -> int = self.w * self.h
          |impl Shape for Sq
          |    area(self) -> int = self.s * self.s
          |var r = Rect(3, 4)
          |var s: *Shape = &r
          |print(s.area())""".stripMargin,
      )

      out should include("@vt.Shape.Rect")
      out should not include "@vt.Shape.Sq"
    }
  }

  "the adapter" - {
    "loads the value the data word addresses" in {
      defineOf(ir(raw), "vt.adapt.Rect.area") should include("%t1 = load %struct.Rect, ptr %d")
    }

    // The counted object's data word is the box, so the payload is one step further in — the same
    // step every `&T` takes to reach what it points at.
    "of a counted object steps over the box header first" in {
      val body = defineOf(ir(counted), "vt.adapt.ref.S.v")

      body should include("%t1 = getelementptr %arc.S, ptr %d, i32 0, i32 3")
      body should include("%t2 = load %struct.S, ptr %t1")
    }
  }

  "a property" - {
    // A property's receiver is by value and never written, so it takes a slot and an adapter exactly
    // as a by-value method does — which is the whole of what declaring one in a trait cost.
    "takes a slot like a method, with the by-value adapter one needs" in {
      ir(withProperty) should include("@vt.Sized.Box = private constant [1 x ptr] [ptr @vt.adapt.Box.size]")
      defineOf(ir(withProperty), "vt.adapt.Box.size") should include("%t1 = load %struct.Box, ptr %d")
    }

    // Reading one is the indirect call a method is, with nothing after the data word: the absent
    // parentheses are the source's, not the table's.
    "reads through the slot, passing the data word and no arguments" in {
      mainOf(ir(withProperty)) should include regex raw"call i32 %t\d+\(ptr %t\d+\)"
    }
  }

  // A type an `impl` may be for is not always a type with a *name*: `[]int` is the key its members
  // are filed under and an impossible LLVM symbol, so the lowered functions carry the type mangled
  // instead. Nothing else about the table changes, which is what these pin.
  "a type with no name of its own" - {
    "emits its members under the mangled type" in {
      ir(slice) should include("define i32 @slice.int.total(")
    }

    "names its table by the same mangling, and fills the slot from it" in {
      ir(slice) should include("@vt.ref.Total.slice.int = private constant [1 x ptr]")
      ir(slice) should include("@vt.adapt.ref.slice.int.total")
      defineOf(ir(slice), "vt.adapt.ref.slice.int.total") should include("call i32 @slice.int.total(")
    }

    "mangles an array's length into the name too" in {
      ir(
        """trait Total
          |    total(self) -> int
          |impl Total for [3]int
          |    total(self) -> int = self[0]
          |var a: [3]int = [1, 2, 3]
          |print(a.total())""".stripMargin,
      ) should include("define i32 @arr3.int.total(")
    }
  }

  /** A member of a generic type is named by its **instantiation** (`Box.total.int`), not by the
   * type mangled with the member on the end (`Box.int.total`) — so a slot filled by any other rule
   * would name a function that does not exist. The table itself keeps the mangled-type naming,
   * since that is what identifies which instantiation was erased.
   */
  "a generic type's table" - {
    "names the slot as the instantiated member, not as the type with the name appended" in {
      val out = ir(generic)

      out should include("define i32 @Box.total.string(")
      defineOf(out, "vt.adapt.ref.Box.total.string") should include("call i32 @Box.total.string(")
    }

    "gives two instantiations two tables of their own" in {
      val out = ir(generic)

      out should include("@vt.ref.Total.Box.string = private constant [1 x ptr]")
      out should include("@vt.ref.Total.Box.int = private constant [1 x ptr]")
    }
  }

  // A shape's members are emitted under the mangling of the types it covers with the arguments left
  // off, so an instantiation appends them: `slice.total` at `int` is `slice.total.int`, which the
  // written `[]int`'s own `slice.int.total` could not be mistaken for.
  "a shape's table" - {
    "names the slot as the member instantiated at the element type" in {
      val out = ir(shape)

      out should include("define i32 @slice.total.int(")
      defineOf(out, "vt.adapt.ref.slice.total.int") should include("call i32 @slice.total.int(")
    }

    "gives two element types two tables of their own" in {
      val out = ir(shape)

      out should include("@vt.ref.Total.slice.int = private constant [1 x ptr]")
      out should include("@vt.ref.Total.slice.bool = private constant [1 x ptr]")
    }
  }

  "the object" - {
    "is a pair of the table and the value" in {
      val body = mainOf(ir(raw))

      body should include("%s.addr = alloca { ptr, ptr }")
      body should include("insertvalue { ptr, ptr } undef, ptr @vt.Shape.Rect, 0")
    }

    "calls through the slot its method sits in, passing the data word first" in {
      val body = mainOf(ir(raw))

      body should include("getelementptr ptr, ptr %t11, i64 0")
      body should include("call i32 %t14(ptr %t12)")
    }

    // The count belongs to the box in the second word; the table in the first is a constant and
    // owns nothing, so releasing an object is releasing exactly what its `&T` would have.
    "counts the box in its second word, and nothing else" in {
      val body = mainOf(ir(counted))

      // The temporary is matched by shape rather than by number, since the numbering moves whenever
      // the box header does, and the release on the next line has to name that same temporary.
      //
      // Two steps rather than one pattern carrying a backreference: a backreference is what makes a
      // regex engine need to backtrack, so the linear-time engines do not offer one at all and Scala
      // Native's rejects `\1` while it is still parsing the pattern.
      val extracted = raw"%(t\d+) = extractvalue \{ ptr, ptr \} %t\d+, 1".r.unanchored

      val released = body.linesIterator.sliding(2).exists {
        case Seq(extracted(word), next) => next.trim == s"call void @arc.release(ptr %$word)"
        case _                          => false
      }

      withClue(body)(released shouldBe true)
    }
  }
}
