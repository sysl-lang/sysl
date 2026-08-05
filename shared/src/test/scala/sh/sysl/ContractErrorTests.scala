package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for malformed contracts: a non-bool condition, `result` where it has no meaning,
 * and clauses that are not at the head of the body.
 */
class ContractErrorTests extends AnyFreeSpec with CodegenSupport {

  "a non-bool require condition is rejected" in {
    err(
      """f(x: int) -> int
        |    require x + 1
        |    x""".stripMargin
    ) should include("condition must be bool")
  }

  "a non-bool ensure condition is rejected" in {
    err(
      """f() -> int
        |    ensure 42
        |    42""".stripMargin
    ) should include("condition must be bool")
  }

  "result" - {
    "is rejected in a require, where it has no value yet" in {
      err(
        """f(x: int) -> int
          |    require result >= 0
          |    x""".stripMargin
      ) should include("only meaningful inside an 'ensure'")
    }

    "is rejected in an ordinary statement outside any contract" in {
      err("print(result)") should include("only meaningful inside an 'ensure'")
    }

    "is rejected in the ensure of a function that returns nothing" in {
      err(
        """f(x: int)
          |    ensure result >= 0
          |    print(x)""".stripMargin
      ) should include("only meaningful inside an 'ensure'")
    }
  }

  "old" - {
    "is rejected outside an ensure" in {
      err(
        """f(x: int) -> int
          |    require old(x) >= 0
          |    x""".stripMargin
      ) should include("undefined function 'old'")
    }

    "takes exactly one argument" in {
      err(
        """f(x: int) -> int
          |    ensure result == old(x, x)
          |    x""".stripMargin
      ) should include("'old' takes exactly one argument")
    }
  }

  "misplaced clauses" - {
    "a require after an ordinary statement is rejected" in {
      err(
        """f(x: int) -> int
          |    var y = x + 1
          |    require x >= 0
          |    y""".stripMargin
      ) should include("must come before any other statement")
    }

    "an ensure nested inside an inner block is rejected" in {
      err(
        """f(x: int) -> int
          |    if x > 0 then
          |        ensure result >= 0
          |    x""".stripMargin
      ) should include("must come before any other statement")
    }
  }
}
