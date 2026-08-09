package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for the layout attributes: a boundary that is not one, a bound that is not constant,
 * an attribute on a declaration that has no layout, and the pointer a packed field cannot give.
 */
class LayoutAttrErrorTests extends AnyFreeSpec with CodegenSupport {

  private val packed =
    """|@packed
       |struct Head
       |    tag: u8
       |    len: u32
       |""".stripMargin

  "an alignment must be a power of two" in {
    err("@align(6)\nstruct S\n    a: int\n") should include("is not an alignment")
    err("@align(6)\nstruct S\n    a: int\n") should include("power of two")
  }

  "and must be constant" in {
    err("var n: int = 8\n@align(n)\nstruct S\n    a: int\n") should include("needs a constant")
  }

  "@align needs its parentheses" in {
    err("@align\nstruct S\n    a: int\n") should include("names the boundary in parentheses")
  }

  "the layout attributes mark a struct" - {
    "not a function" in {
      err("@packed\nf() -> int = 1\n") should include("mark a struct")
    }
    "and a function's attributes do not mark a struct" in {
      err("@tailrec\nstruct S\n    a: int\n") should include("marks a function")
    }
    "the two kinds cannot stand above one declaration" in {
      err("@packed\n@pure\nf() -> int = 1\n") should include("cannot stand above one declaration")
    }
  }

  "one written twice is refused, as any repeated attribute is" in {
    err("@packed\n@packed\nstruct S\n    a: int\n") should include("written twice")
    err("@align(4)\n@align(8)\nstruct S\n    a: int\n") should include("written twice")
  }

  /** `@align(n)` on a binding, and the four things it may not be. */
  "@align on storage" - {
    // `@packed` describes the arrangement of fields *within* an aggregate, and a binding has none —
    // so it is not merely unimplemented there, it has nothing there to mean.
    "'@packed' is not the one of the pair a binding takes" in {
      val e = err("@packed\nvar n: int = 1\nprint(n)")

      e should include("'@packed'")
      e should include("'@align(n)' is the one of the two")
    }

    "a binding that names several has no one object for a boundary to be about" in {
      err("@align(16)\nvar a, b = 1, 2\nprint(a)") should include("no one object")
      err("@align(16)\nval (a, b) = (1, 2)\nprint(a)") should include("no one object")
    }

    // The refusal `05`'s promotion rule owes. The storage moves to the heap, where the boundary is
    // the allocator's answer rather than this declaration's — so the annotation would go on standing
    // above a slot that no longer honours it.
    "an aligned array whose view outlives the frame is refused rather than quietly moved" in {
      val e = err(
        """|escaping() -> []u8
           |    @align(4096)
           |    var page: [8]u8
           |    page[0..<8]
           |
           |print(escaping().len)
           |""".stripMargin)

      e should include("4096-byte boundary")
      e should include("'@align'")
    }

    "the bound is held to the same rule a struct's is" in {
      err("@align(6)\nvar n: int = 1\nprint(n)") should include("power of two")
      err("var k: int = 8\n@align(k)\nvar n: int = 1\nprint(n)") should include("needs a constant")
    }
  }

  "a packed field has no address to give" - {
    // The rule that keeps `@packed` from being a footgun. The field sits at its declared offset, so
    // it need not be on its own boundary — and a `*u32` is entitled to assume that it is, arbitrarily
    // far from the `&` that made it.
    "taking one is refused, and says why" in {
      val e = err(packed + "var h = Head(1u8, 2u32)\nval p = &h.len\nprint(1)")
      e should include("'@packed'")
      e should include("declared offsets")
    }
  }
}
