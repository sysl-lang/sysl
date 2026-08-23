package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** Where the things a program names were declared.
 *
 * The index is *derived* from the typed tree rather than recorded during resolution, so what these
 * assert is really one claim made several times over: that a typed node's name can be traced back
 * to a declaration. Each case is a different kind of name, and each kind reaches a different table.
 */
class DefinitionIndexTests extends AnyFreeSpec with Matchers {

  /** Every reference the fixture makes **from its own file**. The library is analyzed alongside it
   * and refers to plenty of its own declarations; those are real and are not what is being asserted.
   */
  private def refs(src: String): List[Reference] = {
    val program = SyslParser.parse(src, "t.sysl") match
      case Right(p) => p
      case Left(e)  => fail(s"the fixture does not parse: $e")

    Analyzer.indexed(List(program)) match
      case Right(i) => i.references.filter(_.at.source.name == "t.sysl")
      case Left(es) => fail(s"the fixture does not analyze: ${Diagnostic.report(es)}")
  }

  /** The reference written at `line`/`col`, which is how each case names the one it is about. */
  private def from(src: String, line: Int, col: Int): Option[Reference] =
    refs(src).find(r => r.at.line == line && r.at.col == col)

  /** Where a reference points, as a line and a column — the whole of what go-to-definition needs. */
  private def target(src: String, line: Int, col: Int): Option[(String, Int, Int)] =
    from(src, line, col).map(r => (r.name, r.declaredAt.line, r.declaredAt.col))

  "a name reaches the declaration it stands for" - {
    "a local, from its use back to its binding" in {
      target("var x = 1\nprint(x)\n", 2, 7) shouldBe Some(("x", 1, 1))
    }

    "a parameter, which is a binding with no statement of its own" in {
      target("f(n: int) -> int = n\nprint(f(1))\n", 1, 20) shouldBe Some(("n", 1, 1))
    }

    "a function, from the call back to the declaration" in {
      target("add(a: int, b: int) -> int = a + b\nprint(add(1, 2))\n", 2, 7) shouldBe
        Some(("add", 1, 1))
    }

    "module storage, which is a different table from a local" in {
      val src =
        """val greeting = "hi"
          |print(greeting)
          |""".stripMargin

      target(src, 2, 7) shouldBe Some(("greeting", 1, 1))
    }
  }

  /** A generic is called through an instantiation whose name nobody wrote, and whose mangling
   * `instantiateFunc` says "cannot be read back". Following it home is the one lookup here that
   * needs something recorded rather than something already in a table.
   */
  "a call to a generic reaches the generic, not the instantiation it was compiled as" in {
    val src =
      """first[T](xs: []T) -> T = xs[0]
        |print(first([1, 2, 3]))
        |""".stripMargin

    target(src, 2, 7) shouldBe Some(("first", 1, 1))
  }

  /** Shadowing is the case a name-based index gets wrong, and the reason this one is built on the
   * unique name the analyzer bound rather than on the name the reader wrote.
   */
  "an inner binding shadows an outer one, and the reference follows the inner" in {
    val src =
      """var x = 1
        |f() -> int
        |    var x = 2
        |    return x
        |print(f(), x)
        |""".stripMargin

    target(src, 4, 12) shouldBe Some(("x", 3, 5))
    target(src, 5, 12) shouldBe Some(("x", 1, 1))
  }

  /** A declaration in another file is the case go-to-definition exists for, and the library is the
   * one every program has. Nothing here asserts *which* library file, only that the reference left
   * the program's own.
   */
  "a reference into the standard library points into the library's own file" in {
    // `gcd` is generic, so this is also the instantiation being followed home: what the call
    // becomes is a mangled name nobody wrote, and what comes back is the declaration a reader
    // would want opened, named as they would write it.
    val src = "import sysl.math.gcd\n\nprint(gcd(12, 18))\n"

    refs(src).map(r => (r.name, r.declaredAt.source.name)) shouldBe
      List(("sysl.math.gcd", "library/sysl/math/integer.sysl"))
  }

  /** What the index does *not* cover is as much a part of its contract as what it does, and both of
   * these are absences by construction rather than oversights: a literal names nothing, and a type
   * is resolved into the shape of a typed node rather than into a name one carries.
   */
  "what names nothing has no reference" - {
    "a literal" in {
      from("print(1)\n", 1, 7) shouldBe None
    }

    "and a type name, which this index does not reach" in {
      val src = "struct P\n    x: int\n\nvar p = P(1)\nprint(p.x)\n"

      // The constructor call is a reference; the `P` in neither the struct's own header nor a
      // signature is. Asserting the absence is what keeps the gap stated rather than discovered.
      refs(src).filter(_.name == "P") shouldBe Nil
    }
  }

  "the same name written twice gives two references" in {
    val src = "var x = 1\nprint(x)\nprint(x)\n"

    refs(src).filter(_.name == "x").map(r => (r.at.line, r.at.col)) shouldBe List((2, 7), (3, 7))
  }
}
