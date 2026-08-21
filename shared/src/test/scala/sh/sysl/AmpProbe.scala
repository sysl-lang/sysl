package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Scratch probes for card 0204. Deleted before the branch lands — what they establish becomes
  * `AmpConstructionTests`.
  */
class AmpProbe extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val shapes =
    """trait Shape
      |    area(&self) -> int
      |end Shape
      |
      |struct Rect
      |    w: int
      |end Rect
      |
      |impl Shape for Rect
      |    area(&self) -> int = self.w
      |
      |""".stripMargin

  private val plain =
    """trait Shape
      |    area(self) -> int
      |end Shape
      |
      |struct Rect
      |    w: int
      |end Rect
      |
      |impl Shape for Rect
      |    area(self) -> int = self.w
      |
      |""".stripMargin

  private def show(label: String, src: String): Unit =
    label in {
      Compiler.compileToLlvm(src) match
        case Right(_) => info(s"COMPILES")
        case Left(e)  => info(s"REFUSED: ${e.linesIterator.take(3).mkString(" | ")}")
      succeed
    }

  "probes" - {
    show("P1  &r into a *Shape", plain + "var r = Rect(2)\nvar s: *Shape = &r\n\nprint(s.area())\n")
    show("P2  &Rect(2) into a *Shape", plain + "var s: *Shape = &Rect(2)\n\nprint(s.area())\n")
    show("P3  Rect(2) into a *Shape, no sigil", plain + "var s: *Shape = Rect(2)\n\nprint(s.area())\n")
    show("P4  &Rect(2) into a &Shape", plain + "var s: &Shape = &Rect(2)\n\nprint(s.area())\n")
    show("P5  Rect(2) into a &Shape, no sigil", plain + "var s: &Shape = Rect(2)\n\nprint(s.area())\n")
    show("P6  &self on a fresh construction", shapes + "print(Rect(3).area())\n")
    show("P7  &r where the method takes &self", shapes + "var r = Rect(3)\n\nprint(r.area())\n")
    show("P8  returning the address of a local", plain +
      "leak() -> *Rect\n    var r = Rect(2)\n    &r\n\nprint(leak().w)\n")
    show("P9  &(a plain call result)", plain +
      "make() -> Rect = Rect(2)\n\nvar s: *Shape = &make()\n\nprint(s.area())\n")
    show("P10 & on an int literal", "var p: *int = &1\n\nprint(*p)\n")
    show("P11 &self on a local bound to a '&Rect'", shapes + "var r: &Rect = Rect(3)\n\nprint(r.area())\n")
    show("P12 &self on a local bound to a '&Shape'", shapes + "var r: &Shape = Rect(3)\n\nprint(r.area())\n")
    show("P13 &self through a function returning &Shape", shapes +
      "boxed() -> &Shape = Rect(3)\n\nprint(boxed().area())\n")
    show("P14 &Rect(2) where a plain *Rect is wanted", plain + "var s: *Rect = &Rect(2)\n\nprint(s.w)\n")
    show("P15 & on a local already holding a &Rect", plain +
      "var r: &Rect = Rect(2)\nvar s = &r\n\nprint((*s).w)\n")
    show("P16 &r escaping the block it was bound in", plain +
      "var s: *Rect = null\n\nif true\n    var r = Rect(2)\n    s = &r\n\nprint(s.w)\n")
  }

  "what P1 actually prints" in {
    info(run(plain + "var r = Rect(2)\nvar s: *Shape = &r\n\nprint(s.area())\n"))
    succeed
  }
}
