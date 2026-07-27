package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** A struct's `invariant` clauses are checked whenever a value of it is built: an in-range
 * construction proceeds, an out-of-range one traps. Several clauses all have to hold; an invariant
 * may read a module constant; and the check reaches through the ordinary construction path, so a
 * struct built to be returned or passed is checked just the same.
 */
class StructInvariantRunTests extends AnyFreeSpec with RunSupport {

  private val Account =
    """|struct Account
       |    balance: int
       |    invariant balance >= 0
       |""".stripMargin

  "a construction that satisfies the invariant proceeds" in {
    run(Account + "var a = Account(10)\nprint(a.balance)") shouldBe "10\n"
  }

  "a construction that violates the invariant traps" in {
    exits(Account + "var a = Account(-1)\nprint(a.balance)")
  }

  "the boundary value is allowed" in {
    run(Account + "var a = Account(0)\nprint(a.balance)") shouldBe "0\n"
  }

  "whole-struct reassignment is re-checked" - {
    "an in-range reassignment proceeds" in {
      run(Account + "var a = Account(5)\na = Account(7)\nprint(a.balance)") shouldBe "7\n"
    }
    "an out-of-range reassignment traps" in {
      exits(Account + "var a = Account(5)\na = Account(-3)")
    }
  }

  "several invariants must all hold" - {
    val span =
      """|struct Span
         |    lo: int
         |    hi: int
         |    invariant lo <= hi
         |    invariant lo >= 0
         |""".stripMargin

    "all satisfied proceeds" in {
      run(span + "var r = Span(2, 5)\nprint(r.lo, r.hi)").shouldBe("2 5\n")
    }
    "the first clause failing traps" in {
      exits(span + "var r = Span(5, 2)")
    }
    "the second clause failing traps" in {
      exits(span + "var r = Span(-1, 5)")
    }
  }

  "an invariant may read a module constant" in {
    val src =
      """|const LIMIT: int = 100
         |struct Capped
         |    n: int
         |    invariant n <= LIMIT
         |""".stripMargin
    run(src + "var c = Capped(100)\nprint(c.n)") shouldBe "100\n"
    exits(src + "var c = Capped(101)")
  }

  "a struct built to be returned is checked" in {
    val src =
      Account +
        """|make(n: int) -> Account = Account(n)
           |print(make(4).balance)""".stripMargin
    run(src) shouldBe "4\n"
  }

  "a struct built as an argument is checked" in {
    exits(Account + "bal(a: Account) -> int = a.balance\nprint(bal(Account(-2)))")
  }

  "a float invariant traps on violation" in {
    val src =
      """|struct Prob
         |    p: f64
         |    invariant p >= 0.0 && p <= 1.0
         |""".stripMargin
    run(src + "var x = Prob(0.5)\nprint(x.p)") shouldBe "0.5\n"
    exits(src + "var x = Prob(1.5)")
  }

  "a field assignment re-checks the invariant" - {
    "an in-range write proceeds" in {
      run(Account + "var a = Account(5)\na.balance = 8\nprint(a.balance)") shouldBe "8\n"
    }
    "an out-of-range write traps" in {
      exits(Account + "var a = Account(5)\na.balance = -1")
    }
  }

  "a compound field assignment re-checks the invariant" - {
    "an in-range update proceeds" in {
      run(Account + "var a = Account(5)\na.balance += 3\nprint(a.balance)") shouldBe "8\n"
    }
    "an update that drops below the bound traps" in {
      exits(Account + "var a = Account(5)\na.balance -= 9")
    }
  }

  "a field write through a pointer is checked" in {
    exits(Account + "var a = Account(5)\nvar p = &a\n(*p).balance = -2")
  }

  "a field write into an array element is checked" - {
    "an in-range write proceeds" in {
      run(Account + "var xs = [Account(1), Account(2)]\nxs[0].balance = 5\nprint(xs[0].balance)") shouldBe "5\n"
    }
    "an out-of-range write traps" in {
      exits(Account + "var xs = [Account(1), Account(2)]\nxs[1].balance = -1")
    }
  }

  // Every field write is its own checkpoint: a sequence in which each intermediate state holds
  // proceeds, but a single write that breaks the invariant traps even if a later write would mend it.
  "each field write is checked as it happens" - {
    val span =
      """|struct Span
         |    lo: int
         |    hi: int
         |    invariant lo <= hi
         |""".stripMargin
    "a sequence whose every step holds proceeds" in {
      run(span + "var s = Span(2, 8)\ns.hi = 5\ns.lo = 4\nprint(s.lo, s.hi)") shouldBe "4 5\n"
    }
    "an intermediate write that breaks the invariant traps at that write" in {
      exits(span + "var s = Span(2, 8)\ns.lo = 10\ns.hi = 12")
    }
  }
}
