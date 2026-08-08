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
