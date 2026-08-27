package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `?` widening a callee's error into the caller's through `From`
 * (`reference/errors.md § A '?' converts through 'From'`).
 *
 * **What it is for is the shape a library's error types get designed around.** With exact-match `?`
 * and nothing else, every layer of a program writes the conversion into its own signatures — either
 * a `.map_err(…)` at each call that crosses between two error types, or one error type flattened
 * across layers that have nothing to do with each other. The conversion is looked up rather than
 * written, so the two layers are joined once, in the block that says how.
 *
 * The trait is `sysl.From`, and it is ordinary: a program may implement and call it for its own
 * conversions. `?` is the only thing in the language that reads it.
 */
class TryConversionTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  private val layers =
    """enum Io
      |    NotFound
      |    Denied
      |
      |enum Fault
      |    Disk(cause: Io)
      |    Parse(what: string)
      |
      |impl From[Io] for Fault
      |    from(value: Io) -> Fault = Fault.Disk(value)
      |
      |open(ok: bool) -> Result[int, Io] = if ok then Ok(7) else Err(Io.NotFound)
      |
      |read(ok: bool) -> Result[int, Fault]
      |    val n = open(ok)?
      |
      |    Ok(n * 2)
      |
      |describe(r: Result[int, Fault]) -> string = r match
      |    Ok(n) -> "ok " + str(n)
      |    Err(Fault.Disk(_)) -> "disk"
      |    Err(Fault.Parse(s)) -> "parse " + s
      |
      |""".stripMargin

  "a '?' across two error types converts through 'From'" - {

    "the success path is untouched" in {
      run(layers + "print(describe(read(true)))") shouldBe "ok 14\n"
    }

    "and the failure arrives wrapped in the caller's own type" in {
      run(layers + "print(describe(read(false)))") shouldBe "disk\n"
    }
  }

  "what it does not change" - {

    "a '?' whose error types already agree makes no call at all" in {
      irMain("""f(ok: bool) -> Result[int, string] = if ok then Ok(1) else Err("no")
               |
               |g() -> Result[int, string]
               |    val n = f(true)?
               |
               |    Ok(n)
               |
               |print(g().unwrap_or(0))""".stripMargin) should not include "from"
    }

    // `?` on an `Option` has no error to convert: `None` carries nothing, so there is nothing for a
    // conversion to be about and the two enums still have to be the same one.
    "an Option is unaffected, having no error to convert" in {
      run("""f(ok: bool) -> Option[int] = if ok then Some(3) else None
            |
            |g(ok: bool) -> Option[int]
            |    val n = f(ok)?
            |
            |    Some(n * 2)
            |
            |print(g(true), g(false))""".stripMargin) shouldBe "Some(6) None\n"
    }

    "and the two enums must still agree — an Option does not become a Result" in {
      err("""f() -> Option[int] = Some(1)
            |
            |g() -> Result[int, string]
            |    val n = f()?
            |
            |    Ok(n)
            |
            |print(1)""".stripMargin) should include("may only be used in a function returning")
    }
  }

  "where there is no conversion" - {

    // The message names the block to write rather than only the mismatch, which is the whole
    // difference between a diagnostic that reports a fact and one that answers it.
    "the refusal says what would join the two layers" in {
      err("""f() -> Result[int, string] = Ok(1)
            |
            |g() -> Result[int, bool]
            |    val n = f()?
            |
            |    Ok(n)
            |
            |print(1)""".stripMargin) should
        include("a '?' converts through 'sysl.From', so 'impl From[string] for bool' is what joins")
    }

    // An inherent `from` of the right signature is a function that happens to be spelled the same.
    // Reading it would make `?` convert through something nobody declared a conversion.
    "an inherent 'from' of the right shape is not a conversion" in {
      err("""struct Wrap
            |    n: int
            |
            |    from(value: int) -> Wrap = Wrap(value)
            |
            |f() -> Result[int, int] = Ok(1)
            |
            |g() -> Result[int, Wrap]
            |    val n = f()?
            |
            |    Ok(n)
            |
            |print(1)""".stripMargin) should include("converts through")
    }
  }

  "a type may accept conversions from several sources" in {
    run("""enum Fault
          |    FromInt(n: int)
          |    FromText(s: string)
          |
          |impl From[int] for Fault
          |    from(value: int) -> Fault = Fault.FromInt(value)
          |
          |impl From[string] for Fault
          |    from(value: string) -> Fault = Fault.FromText(value)
          |
          |numeric() -> Result[int, int] = Err(4)
          |textual() -> Result[int, string] = Err("bad")
          |
          |both(which: bool) -> Result[int, Fault]
          |    if which then
          |        val a = numeric()?
          |
          |        Ok(a)
          |    else
          |        val b = textual()?
          |
          |        Ok(b)
          |
          |show(r: Result[int, Fault]) -> string = r match
          |    Ok(n) -> str(n)
          |    Err(Fault.FromInt(n)) -> "int " + str(n)
          |    Err(Fault.FromText(s)) -> "text " + s
          |
          |print(show(both(true)))
          |print(show(both(false)))""".stripMargin) shouldBe "int 4\ntext bad\n"
  }

  /** The conversion's result arrives **owned** — it is a call — so the early return carries the
    * count it already took and takes no second one.
    *
    * **A destructor is the oracle, and the assertion was falsified before it was believed**: with a
    * retain added back beside the conversion this reads `0` rather than `10`, because every note
    * then leaks. Counting the drops is what tells a correct count from one that merely runs.
    */
  "a converted payload's count is exactly right, which a destructor is what can say" in {
    run("""static var dropped: int = 0
          |
          |struct Note
          |    text: string
          |
          |impl Drop for Note
          |    drop(self)
          |        dropped += 1
          |
          |enum Low
          |    Bad(note: &Note)
          |
          |enum High
          |    Wrapped(note: &Note)
          |
          |impl From[Low] for High
          |    from(value: Low) -> High = value match
          |        Low.Bad(n) -> High.Wrapped(n)
          |
          |deep() -> Result[int, Low] = Err(Low.Bad(Note("boom")))
          |
          |shallow() -> Result[int, High]
          |    val n = deep()?
          |
          |    Ok(n)
          |
          |run() -> unit
          |    for i in 0..<10
          |        shallow() match
          |            Ok(_) -> print("ok")
          |            Err(High.Wrapped(n)) -> print(n.text)
          |
          |run()
          |print(dropped)""".stripMargin) shouldBe ("boom\n" * 10) + "10\n"
  }
}
