package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** What `16-constrained-types-and-contracts.md` claims, run rather than read.
 *
 * The chapter is the best-pinned one in the tree — `SubtypeProduceSiteTests`, `SubtypeOperatorTests`,
 * `TypeAttrRunTests`, `ContractErrorTests` and the rest cover §3, §4, §5 and §7 case by case, and the
 * sweep found nothing in any of them. What it did find is one sentence in §2, and a handful of claims
 * that no suite happened to reach.
 *
 * The sentence said a derived value reads *as* its base with no cast. It does not, in any position —
 * and the test that looks like it covers the claim, `SubtypeProduceSiteTests`' "using one as its
 * base, which needs no cast", uses a **transparent** `Age`, where §1 makes it trivially true. The
 * sentence sat in the section titled "`new` is what makes it a type", about derived ones, where it is
 * false, so the reader most likely to rely on it was the one it would mislead.
 */
class ConstrainedClaimTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val meters = "type Meters = new f64\n"
  private val age    = "type Age = int within 0..150\n"

  "a derived value needs the cast in both directions, and no position excuses it" - {

    "not a variable's initializer" in {
      err(s"${meters}var m = Meters(3.0)\nvar back: f64 = m") should
        include("cannot initialize 'back': declared real but the value is Meters")
    }

    "not an argument" in {
      err(s"${meters}takes(x: f64) -> f64 = x * 2.0\nvar m = Meters(3.0)\nprint(takes(m))") should
        include("'x' of 'takes' is real, but Meters was given")
    }

    "not a returned value" in {
      err(s"${meters}gives(m: Meters) -> f64 = m") should
        include("function 'gives' should return real, but its body yields Meters")
    }

    "and the written unwrap is what works" in {
      run(s"${meters}var m = Meters(2.5)\nvar back: f64 = f64(m)\nprint(back)") shouldBe "2.5\n"
    }

    "while a TRANSPARENT subtype is its base, so it needs no cast — which is the opposite case" in {
      run(s"${age}var a: Age = 30\nvar n: int = a\nprint(n + 1)") shouldBe "31\n"
    }
  }

  "'new', 'within', 'where' and 'invariant' are contextual, so they are ordinary names elsewhere" - {

    "each may name a function" in {
      run("where(x: int) -> int = x + 1\nwithin(x: int) -> int = x + 2\nnew(x: int) -> int = x + 3\n" +
        "invariant(x: int) -> int = x + 4\nprint(where(1), within(1), new(1), invariant(1))")
        .shouldBe("2 3 4 5\n")
    }

    "and 'where' and 'invariant' may name a field, which is where the grammar has to disambiguate" in {
      run("struct Rec\n    where: int\n    invariant: int\nvar r = Rec(1, 2)\nprint(r.where, r.invariant)")
        .shouldBe("1 2\n")
    }
  }

  "the predicate" - {

    "may read a module constant, so a range and a table's size are stated once" in {
      run("const limit: int = 100\ntype Even = int within 0..limit where value % 2 == 0\n" +
        "var e: Even = 4\nprint(e)") shouldBe "4\n"
    }

    "binds 'value' only inside itself" in {
      err("type Even = int where value % 2 == 0\nleak(x: int) -> int = value + x") should
        include("undefined name 'value'")
    }
  }

  "a contract on a mutating method is where the chapter says it is most useful" - {

    "'ensure' reads 'result' and 'old' of the receiver's own field" in {
      run("struct Counter\n    n: int\n\n    bump(*self) -> int\n        ensure result > old(self.n)\n" +
        "        self.n += 1\n        self.n\nvar c = Counter(5)\nprint(c.bump(), c.n)") shouldBe "6 6\n"
    }

    "several 'old' snapshots in one function are independent of each other" in {
      run("spread(a: int, b: int) -> int\n    ensure result == old(a) + old(b)\n    a + b\nprint(spread(2, 5))")
        .shouldBe("7\n")
    }

    "and a local actually named 'result' is an ordinary local" in {
      run("shadow(x: int) -> int\n    var result = x * 3\n    result\nprint(shadow(4))") shouldBe "12\n"
    }
  }

  /** A constrained subtype is a **module member** like any other, so both ways of reaching one from
    * another file work — the qualified path, and the import that shortens it.
    *
    * The import did not. `ImportResolution.declaresAnything` asks every table a name may be declared
    * in and did not ask `constrainedDecls`, so a type was the one declaration that could be *used*
    * through its module and not imported: `import shape.Meters` was refused with *"'shape' declares
    * no 'Meters'"* while `shape.Meters` beside it resolved. Found while measuring a `c type`, which
    * lowers to exactly this declaration and so inherited the hole.
    */
  "a type declared in another file is reached both ways" - {

    "by the qualified path" in {
      runIn(
        ("shape", "shape.sysl", "module shape\n\ntype Meters = f64 within 0.0..100.0\n"),
        ("", "main.sysl", "val d: shape.Meters = 3.5\nprint(d)\n"),
      ) shouldBe "3.5\n"
    }

    "and by an import that shortens it" in {
      runIn(
        ("shape", "shape.sysl", "module shape\n\ntype Meters = f64 within 0.0..100.0\n"),
        ("", "main.sysl", "import shape.Meters\n\nval d: Meters = 3.5\nprint(d)\n"),
      ) shouldBe "3.5\n"
    }
  }
}
