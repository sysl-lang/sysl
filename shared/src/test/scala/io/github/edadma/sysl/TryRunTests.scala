package io.github.edadma.sysl

import org.scalatest.freespec.AnyFreeSpec

/** Tier-2: the prelude's `Option` and `Result`, and the postfix `?` that unwraps them or
 * returns the enclosing function early with the failure re-wrapped.
 */
class TryRunTests extends AnyFreeSpec with RunSupport {

  private val unwrap =
    """unwrap(o: Option[int], dflt: int) -> int
      |    match o
      |        Some(v) -> v
      |        None -> dflt
      |end unwrap
      |""".stripMargin

  "Option" - {
    "a Some and a None taken apart by a match" in {
      run(unwrap + """var some: Option[int] = Some(5)
                     |var none: Option[int] = None
                     |print(unwrap(some, -1), unwrap(none, -1))
                     |""".stripMargin) shouldBe "5 -1\n"
    }

    "the expected type gives a bare None its type argument" in {
      run(unwrap + """pick(yes: bool) -> Option[int]
                     |    if yes then Some(9) else None
                     |end pick
                     |print(unwrap(pick(true), 0), unwrap(pick(false), 0))
                     |""".stripMargin) shouldBe "9 0\n"
    }
  }

  "Result" - {
    "Ok takes its error type from the return type" in {
      run("""half(n: int) -> Result[int, string]
            |    if n % 2 == 0 then Ok(n / 2) else Err("odd")
            |end half
            |report(r: Result[int, string]) -> string
            |    match r
            |        Ok(v) -> "ok"
            |        Err(e) -> e
            |end report
            |print(report(half(8)), report(half(7)))
            |""".stripMargin) shouldBe "ok odd\n"
    }
  }

  "the ? operator" - {
    "propagates an Err and unwraps an Ok" in {
      run("""half(n: int) -> Result[int, string]
            |    if n % 2 == 0 then Ok(n / 2) else Err("odd")
            |end half
            |quarter(n: int) -> Result[int, string]
            |    var h = half(n)?
            |    half(h)
            |end quarter
            |report(r: Result[int, string]) -> string
            |    match r
            |        Ok(v) -> "ok"
            |        Err(e) -> e
            |end report
            |print(report(quarter(20)), report(quarter(6)), report(quarter(7)))
            |""".stripMargin) shouldBe "ok odd odd\n"
    }

    "propagates a None through an Option-returning function" in {
      run(unwrap + """tenfold(o: Option[int]) -> Option[int]
                     |    var v = o?
                     |    Some(v * 10)
                     |end tenfold
                     |print(unwrap(tenfold(Some(3)), -1), unwrap(tenfold(None), -1))
                     |""".stripMargin) shouldBe "30 -1\n"
    }

    "carries the error payload across into the caller's own Result type" in {
      run("""parse(s: string) -> Result[int, string]
            |    Err("bad input")
            |end parse
            |twice(s: string) -> Result[int, string]
            |    var n = parse(s)?
            |    Ok(n * 2)
            |end twice
            |show(r: Result[int, string]) -> string
            |    match r
            |        Ok(v) -> "ok"
            |        Err(e) -> e
            |end show
            |print(show(twice("x")))
            |""".stripMargin) shouldBe "bad input\n"
    }
  }
}
