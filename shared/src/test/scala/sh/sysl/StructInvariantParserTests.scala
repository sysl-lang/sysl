package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A struct body may carry one or more `invariant <bool>` clauses among its fields. They collect
 * into `StructDecl.invariants` in source order, leaving the fields and members untouched.
 * `invariant` is contextual — a field may still be named `invariant`.
 */
class StructInvariantParserTests extends AnyFreeSpec with ParseSupport {

  private def structDecl(src: String): StructDecl =
    prog(src).collectFirst { case s: StructDecl => s }.getOrElse(fail("expected a struct declaration"))

  private def cmp(op: String, l: Expr, r: Expr): Expr = Compare(List(l, r), List(op))

  "a single invariant is collected and the fields are left alone" in {
    val s = structDecl("""struct Account
                         |    balance: int
                         |    invariant balance >= 0""".stripMargin)
    s.fields.map(_.name) shouldBe List("balance")
    s.invariants shouldBe List(cmp(">=", Ident("balance"), i(0)))
  }

  "several invariants collect in source order" in {
    val s = structDecl("""struct Range
                         |    lo: int
                         |    hi: int
                         |    invariant lo <= hi
                         |    invariant lo >= 0""".stripMargin)
    s.fields.map(_.name) shouldBe List("lo", "hi")
    s.invariants shouldBe List(cmp("<=", Ident("lo"), Ident("hi")), cmp(">=", Ident("lo"), i(0)))
  }

  "a struct with no invariant clause has an empty list" in {
    structDecl("""struct Point
                 |    x: int
                 |    y: int""".stripMargin).invariants shouldBe Nil
  }

  "a field may still be named `invariant`" in {
    val s = structDecl("""struct Holder
                         |    invariant: int""".stripMargin)
    s.fields.map(_.name) shouldBe List("invariant")
    s.invariants shouldBe Nil
  }

  "invariants coexist with members" in {
    val s = structDecl("""struct Counter
                         |    n: int
                         |    invariant n >= 0
                         |    bump(self) -> int = self.n + 1""".stripMargin)
    s.fields.map(_.name) shouldBe List("n")
    s.members.map(_.name) shouldBe List("bump")
    s.invariants shouldBe List(cmp(">=", Ident("n"), i(0)))
  }
}
