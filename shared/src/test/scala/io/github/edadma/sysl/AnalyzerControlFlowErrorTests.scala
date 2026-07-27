package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Diagnostics for control flow and pattern matching: loops and their labels, `match`
 * exhaustiveness and arm agreement, and the `?` error-propagation operator.
 */
class AnalyzerControlFlowErrorTests extends AnyFreeSpec with CodegenSupport {

  "loops" - {
    "break outside a loop is rejected" in {
      err("break 5") should include("'break' is only allowed inside a loop")
    }

    "continue outside a loop is rejected" in {
      err("continue") should include("'continue' is only allowed inside a loop")
    }

    // A value-carrying break needs an `else` to supply the value on normal completion; without
    // one the break path and the unit fall-through can't agree, and the message says why.
    "a value-carrying break with no else is rejected" in {
      err(
        """var r = for x in [1, 2, 3]
          |    if x == 2 then break x""".stripMargin
      ) should include("has no 'else' to give a value when it finishes normally")
    }

    // The break value and the else value must be the same type — int and string cannot both be
    // the loop's result.
    "a break value and else of different types are rejected" in {
      err(
        """var r = for x in [1, 2, 3]
          |    if x == 2 then break x
          |else "no"""".stripMargin
      ) should include("must have the same type")
    }

    // Two breaks that carry different types are equally irreconcilable, even before an else.
    "two breaks of different types are rejected" in {
      err(
        """var r = while true
          |    if true then break 1
          |    break "two"
          |else 0""".stripMargin
      ) should include("must have the same type")
    }

    // A labeled break/continue must name a loop that actually encloses it.
    "breaking or continuing to an unknown label is rejected" in {
      err(
        """for i in 0..<3
          |    break 'nope""".stripMargin
      ) should include("no enclosing loop is labeled 'nope")
      err(
        """for i in 0..<3
          |    continue 'gone""".stripMargin
      ) should include("no enclosing loop is labeled 'gone")
    }

    // A label is out of scope once its loop has ended, so naming it from outside is an error.
    "a label used outside its loop is rejected" in {
      err(
        """'outer for i in 0..<3
          |    print(i)
          |break 'outer""".stripMargin
      ) should include("no enclosing loop is labeled 'outer")
    }

    // Two enclosing loops may not share a label, or a labeled break would be ambiguous.
    "reusing a label already in scope is rejected" in {
      err(
        """'dup for i in 0..<3
          |    'dup for j in 0..<3
          |        break 'dup""".stripMargin
      ) should include("label 'dup is already in scope")
    }
  }

  "a value match must be exhaustive" in {
    err("var x = 1 match\n    1 -> 10\n    2 -> 20") should include("exhaustive")
  }

  "match patterns and results" - {
    // `bool` is exhaustive only when both values are covered; one value with no else is a gap.
    "a value match on a bool covering only one value is rejected" in {
      err("var x = true match\n    true -> 1") should include("must cover both 'true' and 'false'")
    }

    // A range pattern is for numbers and characters; `string` is ordered but a string range is a
    // trap, so it is refused.
    "a string range pattern is rejected" in {
      err(
        """f(s: string) -> int = s match
          |    "a".."z" -> 1
          |    else -> 0""".stripMargin
      ) should include("a range pattern needs a numeric or char value, not string")
    }

    // Two arms yielding different value types have nothing to unify to — a diagnostic, not a
    // silent collapse to unit.
    "arms that yield different value types are rejected" in {
      err(
        """var x = 3 match
          |    1 -> 10
          |    else -> "big"""".stripMargin
      ) should include("match arms have different types: int and string")
    }

    // A positional struct pattern must name every field, so a short one is a checked mistake.
    "a positional struct pattern with the wrong field count is rejected" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |f(p: Point) -> int = p match
          |    Point(x) -> x
          |    else -> 0""".stripMargin
      ) should include("struct 'Point' has 2 fields, but 1 sub-pattern was given")
    }

    // Selection reaches through a memory mode and a pattern does not (`03`), which a recursive type
    // meets the moment it is walked — so the complaint names the dereference that would fix it.
    "a variant pattern against a reference to the enum says to dereference it" in {
      val e = err(
        """enum Shape
          |    Dot
          |    Round(r: int)
          |end Shape
          |f(s: &Shape) -> int = s match
          |    Round(r) -> r
          |    else -> 0""".stripMargin
      )

      e should include("but the value is &Shape")
      e should include("'*x match'")
    }

    "and so does a struct pattern against a pointer to the struct" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |f(p: *Point) -> int = p match
          |    Point{x: n} -> n
          |    else -> 0""".stripMargin
      ) should include("'*x match'")
    }

    // The hint is about a mode over something matchable, so a value that was never going to match
    // is not told to dereference itself.
    "but a value of no such shape is not" in {
      val e = err(
        """struct Point
          |    x: int
          |end Point
          |f(n: int) -> int = n match
          |    Point(x) -> x
          |    else -> 0""".stripMargin
      )

      e should include("but the value is int")
      e should not include "*x match"
    }

    "a named struct pattern naming an unknown field is rejected" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |f(p: Point) -> int = p match
          |    Point{z} -> z
          |    else -> 0""".stripMargin
      ) should include("struct 'Point' has no field 'z'")
    }

    "a named struct pattern matching a field twice is rejected" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |f(p: Point) -> int = p match
          |    Point{x, x} -> x
          |    else -> 0""".stripMargin
      ) should include("field 'x' is matched more than once")
    }

    // A struct pattern must name the scrutinee's struct.
    "a struct pattern for a different type is rejected" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |f(p: Point) -> int = p match
          |    Other(x, y) -> x
          |    else -> 0""".stripMargin
      ) should include("'Other(…)' does not match a Point value")
    }

    // A refutable struct pattern does not cover the type, so a value match on it still needs a
    // fall-through.
    "a refutable struct pattern in a value match still needs an else" in {
      err(
        """struct Point
          |    x: int
          |    y: int
          |end Point
          |var r = Point(1, 2) match
          |    Point(0, 0) -> 1""".stripMargin
      ) should include("must be exhaustive")
    }
  }

  "the ? operator" - {
    "needs an Option or Result value" in {
      err("f(n: int) -> Option[int]\n    var x = n?\n    None") should
        include("'?' needs an Option or Result value")
    }

    "may not be used in a function returning something else" in {
      err("""g() -> Option[int] = None
            |f() -> int
            |    var x = g()?
            |    x
            |end f
            |""".stripMargin) should include("may only be used in a function returning Option")
    }

    "may not propagate an error the function does not return" in {
      err("""g() -> Result[int, string] = Ok(1)
            |f() -> Result[int, bool]
            |    var x = g()?
            |    Ok(x)
            |end f
            |""".stripMargin) should include("propagates a string error")
    }
  }
}
