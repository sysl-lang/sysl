package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `where T: Display` — a declaration's bounds written after its signature rather than inside its
 * `[…]` list (`reference/generics.md § A bound may be written out of line`).
 *
 * **The claim this suite makes is an equivalence, and it is deliberately stronger than "the clause
 * works".** A `where` clause is folded into the same bound map the bracket list fills, so the two
 * spellings are not merely both accepted — they are indistinguishable to everything downstream. So
 * the cases below run each program *twice*, once written each way, and assert the two answers are
 * equal as well as correct. A test asserting only that the `where` form prints `42` would pass on an
 * implementation that quietly dropped the bounds, since an unbounded parameter compiles until
 * something needs the bound.
 *
 * The refusals are the other half: a clause naming something the declaration cannot bound has to say
 * so at the clause, which is what the reader has to change.
 */
class WhereClauseTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  /** The same program in both spellings, asserted to agree with each other and with `want`.
   *
   * `inline` is the bracket-list form and `clause` is the `where` form; they are written out rather
   * than derived from one another, because a rewrite rule shared by the test and the compiler would
   * be a check that could not fail.
   */
  private def bothWays(inline: String, clause: String, want: String): Unit = {
    val a = run(inline)
    val b = run(clause)

    a shouldBe want
    b shouldBe want
    b shouldBe a
  }

  "a clause says what the bracket list says" - {

    "one parameter, one bound" in {
      bothWays(
        inline = """show[T: Display](x: T) -> string = str(x)
                   |print(show(42))""".stripMargin,
        clause = """show[T](x: T) -> string where T: Display = str(x)
                   |print(show(42))""".stripMargin,
        want = "42\n",
      )
    }

    "two parameters, separated by a comma" in {
      bothWays(
        inline = """pair[A: Display, B: Display](a: A, b: B) -> string = s"${str(a)}/${str(b)}"
                   |print(pair(1, "two"))""".stripMargin,
        clause = """pair[A, B](a: A, b: B) -> string where A: Display, B: Display = s"${str(a)}/${str(b)}"
                   |print(pair(1, "two"))""".stripMargin,
        want = "1/two\n",
      )
    }

    "two bounds on one parameter, joined by '+'" in {
      bothWays(
        inline = """same[T: Display + Eq](a: T, b: T) -> string =
                   |    if a == b then str(a) else "no"
                   |print(same(3, 3))
                   |print(same(3, 4))""".stripMargin,
        clause = """same[T](a: T, b: T) -> string where T: Display + Eq =
                   |    if a == b then str(a) else "no"
                   |print(same(3, 3))
                   |print(same(3, 4))""".stripMargin,
        want = "3\nno\n",
      )
    }

    "a member's own parameters take one" in {
      bothWays(
        inline = """struct Box[T: Display]
                   |    v: T
                   |    tag[U: Display](self, u: U) -> string = s"${str(self.v)}:${str(u)}"
                   |print(Box(7).tag("x"))""".stripMargin,
        clause = """struct Box[T] where T: Display
                   |    v: T
                   |    tag[U](self, u: U) -> string where U: Display = s"${str(self.v)}:${str(u)}"
                   |print(Box(7).tag("x"))""".stripMargin,
        want = "7:x\n",
      )
    }

    /** The two spellings are one bound map, so a declaration may use both — which is what makes the
      * choice per *bound* rather than per declaration: the short obvious constraint stays in the
      * brackets and the long one that would crowd the signature goes below it. `reference/generics.md`
      * says so, so it owes a case.
      */
    "the bracket list and a clause may bound the SAME parameter between them" in {
      bothWays(
        inline = """same[T: Eq + Display](a: T, b: T) -> string =
                   |    if a == b then str(a) else "different"
                   |print(same(3, 3))
                   |print(same(3, 4))""".stripMargin,
        clause = """same[T: Eq](a: T, b: T) -> string where T: Display =
                   |    if a == b then str(a) else "different"
                   |print(same(3, 3))
                   |print(same(3, 4))""".stripMargin,
        want = "3\ndifferent\n",
      )
    }

    "an enum takes one" in {
      bothWays(
        inline = """enum Slot[T: Display]
                   |    Empty
                   |    Full(x: T)
                   |val s: Slot[int] = Slot.Full(4)
                   |print(s match
                   |    Slot.Full(n) -> str(n)
                   |    Slot.Empty -> "none")""".stripMargin,
        clause = """enum Slot[T] where T: Display
                   |    Empty
                   |    Full(x: T)
                   |val s: Slot[int] = Slot.Full(4)
                   |print(s match
                   |    Slot.Full(n) -> str(n)
                   |    Slot.Empty -> "none")""".stripMargin,
        want = "4\n",
      )
    }
  }

  "the bound is real, not decoration" - {

    /** The case that separates a folded clause from an ignored one. An unbounded `T` compiles right
      * up until the body needs the bound, so a program that *uses* it is the only thing that can
      * tell whether the clause was read.
      */
    "a body may use what the clause promised" in {
      run(
        """describe[T](x: T) -> string where T: Display = s"<${str(x)}>"
          |print(describe(5))""".stripMargin,
      ) shouldBe "<5>\n"
    }

    "a call that does not satisfy it is refused, exactly as the bracket form is" in {
      val src =
        """struct Bare
          |    n: int
          |show[T](x: T) -> string where T: Display = str(x)
          |print(show(Bare(1)))""".stripMargin

      err(src) should include("Display")
    }
  }

  "a clause may only bound what the declaration declares" - {

    "a name the declaration does not have" in {
      err(
        """show[T](x: T) -> string where U: Display = str(x)
          |print(show(1))""".stripMargin,
      ) should include("no type parameter 'U'")
    }

    "a declaration with no parameters at all" in {
      err(
        """plain(x: int) -> int where T: Display = x
          |print(plain(1))""".stripMargin,
      ) should include("it takes none")
    }

    /** A value parameter's `: usize` is the type its argument must have rather than a bound, and a
      * value implements no trait — so there is nothing for a clause to add to one.
      */
    "a value parameter, which has a type rather than a bound" in {
      err(
        """sum[const N: usize](xs: [N]int) -> int where N: Display = 0
          |print(sum([1, 2]))""".stripMargin,
      ) should include("stands for a value rather than a type")
    }

    "a clause with no ':' says which half is missing" in {
      err(
        """show[T](x: T) -> string where T = str(x)
          |print(show(1))""".stripMargin,
      ) should include("the ':' is what separates the two")
    }
  }

  /** `where` introduces a constrained subtype's predicate in a `type` declaration
    * (`reference/types.md`), and has since long before this form existed. The two are told apart by
    * position — one follows a type inside a `type` declaration, the other follows a signature — so
    * neither had to move and the word stays an ordinary identifier everywhere else.
    */
  "the word's other two jobs are untouched" - {

    "a constrained subtype still takes its predicate" in {
      run(
        """type Even = new int where value % 2 == 0
          |var e = Even(4)
          |print(int(e))""".stripMargin,
      ) shouldBe "4\n"
    }

    "a program may still name a function 'where'" in {
      run(
        """where(n: int) -> int = n + 1
          |print(where(1))""".stripMargin,
      ) shouldBe "2\n"
    }
  }
}
