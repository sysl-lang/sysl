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

      body should include("%t1 = getelementptr %arc.S, ptr %d, i32 0, i32 2")
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

      body should include("%t23 = extractvalue { ptr, ptr } %t22, 1")
      body should include("call void @arc.release(ptr %t23)")
    }
  }
}
