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

  "the ? operator on reference payloads" - {
    // `?` on a `Result[&Point, string]` unwraps the ok arm to a `&Point` that outlives the
    // wrapper enum, while the err arm returns early. Both outcomes are exercised.
    "unwraps a &T ok-payload and propagates the err arm" in {
      run("""struct Point
            |    x: int
            |    y: int
            |mk(ok: bool) -> Result[&Point, string]
            |    if ok then Ok(Point(11, 22)) else Err("no")
            |use(ok: bool) -> Result[int, string]
            |    var p = mk(ok)?
            |    Ok(p.x + p.y)
            |show(r: Result[int, string]) -> int
            |    match r
            |        Ok(v) -> v
            |        Err(e) -> -1
            |print(show(use(true)), show(use(false)))
            |""".stripMargin) shouldBe "33 -1\n"
    }

    // The failure carries a `&Fail`, which `?` moves through the early return into the caller's
    // own `Result[int, &Fail]` — the ok arm reads a code of 7, the err arm carries 404 across.
    "moves a &T error payload through the early return" in {
      run("""struct Fail
            |    code: int
            |mk(ok: bool) -> Result[int, &Fail]
            |    if ok then Ok(7) else Err(Fail(404))
            |use(ok: bool) -> Result[int, &Fail]
            |    var n = mk(ok)?
            |    Ok(n * 10)
            |show(r: Result[int, &Fail]) -> int
            |    match r
            |        Ok(v) -> v
            |        Err(e) -> e.code
            |print(show(use(true)), show(use(false)))
            |""".stripMargin) shouldBe "70 404\n"
    }

    // Chained `?` on `Option[&Node]`: the first hop unwraps the head, the second unwraps its
    // `next`, and either missing link short-circuits to None. Three inputs cover a two-hop
    // success, a one-hop-then-None, and an empty head.
    "chains through an Option[&Node] traversal" in {
      run("""struct Node
            |    value: int
            |    next: Option[&Node]
            |second(head: Option[&Node]) -> Option[int]
            |    var h = head?
            |    var nx = h.next?
            |    Some(nx.value)
            |unwrap(o: Option[int]) -> int
            |    match o
            |        Some(v) -> v
            |        None -> -1
            |var a: &Node = Node(3, None)
            |var b: &Node = Node(2, Some(a))
            |print(unwrap(second(Some(b))), unwrap(second(Some(a))), unwrap(second(None)))
            |""".stripMargin) shouldBe "3 -1 -1\n"
    }

    // The `&Point` that `?` yields flows straight into `Ok(...)`, which the enclosing
    // `Result[&Point, string]` boxes — the ? result and the &T-ok construction compose.
    "flows a ? result straight into an Ok in a &T-ok-returning function" in {
      run("""struct Point
            |    x: int
            |    y: int
            |mk(ok: bool) -> Result[&Point, string]
            |    if ok then Ok(Point(5, 6)) else Err("no")
            |relay(ok: bool) -> Result[&Point, string]
            |    Ok(mk(ok)?)
            |show(r: Result[&Point, string]) -> int
            |    match r
            |        Ok(p) -> p.x + p.y
            |        Err(e) -> -1
            |print(show(relay(true)), show(relay(false)))
            |""".stripMargin) shouldBe "11 -1\n"
    }

    // Extracting a freshly-allocated &Point through `?` many times must retain it past the
    // wrapper enum's drop and free it once — a leak grows RSS, a double-free crashes. Peak RSS
    // was separately confirmed flat. Ok arm only: p.x + p.y = 5 each, 20000 times.
    "extracting a &T ok-payload in a long loop neither leaks nor double-frees" in {
      run("""struct Point
            |    x: int
            |    y: int
            |mk() -> Result[&Point, string]
            |    Ok(Point(2, 3))
            |use() -> Result[int, string]
            |    var p = mk()?
            |    Ok(p.x + p.y)
            |var i = 0
            |var total = 0
            |while i < 20000
            |    match use()
            |        Ok(v) -> total += v
            |        Err(e) -> total += 0
            |    i++
            |print(total)
            |""".stripMargin) shouldBe "100000\n"
    }

    // The err arm allocates a fresh &Fail each time and `?` re-wraps it into the caller's return;
    // over a long loop this must free every failure exactly once. Err arm only: e.code = 7.
    "re-wrapping a &T error payload in a long loop neither leaks nor double-frees" in {
      run("""struct Fail
            |    code: int
            |mk() -> Result[int, &Fail]
            |    Err(Fail(7))
            |use() -> Result[int, &Fail]
            |    var n = mk()?
            |    Ok(n + 1)
            |var i = 0
            |var total = 0
            |while i < 20000
            |    match use()
            |        Ok(v) -> total += 0
            |        Err(e) -> total += e.code
            |    i++
            |print(total)
            |""".stripMargin) shouldBe "140000\n"
    }
  }
}
