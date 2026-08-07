package sh.sysl

import org.scalatest.freespec.AnyFreeSpec

/** `x: -> T` — a parameter passed by name (`12 § A parameter may be passed by name`).
  *
  * The feature is one thing the call site does, so the tests are about *when the argument runs*
  * rather than about what it is. Three properties carry the design and each has a test that would
  * fail if it were got backwards:
  *
  *   - the argument is not evaluated where it is written;
  *   - the body evaluates it at **each** use, which is what makes it different from a `val`;
  *   - a body that never uses it never evaluates it at all, which is the point of the feature.
  *
  * `x: () -> T` is the neighbouring case and is tested beside it: same type, different call site.
  * The two spellings have to stay distinguishable, because the shorter one is the one that changes
  * what a caller writes.
  */
class ByNameTests extends AnyFreeSpec with RunSupport with CodegenSupport {

  "what by name means" - {

    "the argument is evaluated in the callee, not at the call" in {
      run("""noisy() -> int
            |    print(1)
            |    7
            |
            |take(x: -> int) -> int
            |    print(2)
            |    x
            |
            |print(take(noisy()))
            |""".stripMargin) shouldBe "2\n1\n7\n"
    }

    // The whole of what separates this from a `val`: two uses are two evaluations.
    "it is evaluated once per use" in {
      run("""static var calls: int = 0
            |
            |tick() -> int
            |    calls += 1
            |    calls
            |
            |twice(x: -> int) -> int = x + x
            |
            |print(twice(tick()))
            |print(calls)
            |""".stripMargin) shouldBe "3\n2\n"
    }

    "a body that never uses it never evaluates it" in {
      run("""boom() -> int
            |    print(99)
            |    1
            |
            |ignore(x: -> int) -> int = 5
            |
            |print(ignore(boom()))
            |""".stripMargin) shouldBe "5\n"
    }

    // The case the feature is usually reached for: the expensive argument of a call that may not
    // want it.
    "a guard that decides whether the argument runs at all" in {
      run("""static var built: int = 0
            |
            |message() -> int
            |    built += 1
            |    42
            |
            |log(on: bool, m: -> int)
            |    if on then print(m)
            |
            |log(false, message())
            |log(true, message())
            |print(built)
            |""".stripMargin) shouldBe "42\n1\n"
    }

    "an ordinary expression, not just a call" in {
      run("""twice(x: -> int) -> int = x + x
            |
            |print(twice(3 * 7))
            |""".stripMargin) shouldBe "42\n"
    }

    "several by-name parameters, each independent" in {
      run("""pick(c: bool, a: -> int, b: -> int) -> int
            |    if c then a else b
            |
            |print(pick(true, 1, 2))
            |print(pick(false, 1, 2))
            |""".stripMargin) shouldBe "1\n2\n"
    }

    "a by-name parameter beside ordinary ones" in {
      run("""rep(n: int, x: -> int) -> int
            |    var t = 0
            |    for i in 0..<n
            |        t += x
            |    t
            |
            |print(rep(3, 5))
            |""".stripMargin) shouldBe "15\n"
    }

    "a by-name parameter of a type other than int" in {
      run("""greet(s: -> string)
            |    print(s)
            |    print(s)
            |
            |greet("hi")
            |""".stripMargin) shouldBe "hi\nhi\n"
    }

    // The read is keyed by the **uniqued** name, so a local declared over the parameter is simply a
    // different name and needs no rule of its own. Without that, this would evaluate the thunk.
    "a local shadowing it is an ordinary local" in {
      run("""static var calls: int = 0
            |
            |tick() -> int
            |    calls += 1
            |    calls
            |
            |f(x: -> int) -> int
            |    var x = 100
            |    x + x
            |
            |print(f(tick()))
            |print(calls)
            |""".stripMargin) shouldBe "200\n0\n"
    }

    "it reaches a name the caller had in scope" in {
      run("""twice(x: -> int) -> int = x + x
            |
            |var n = 21
            |print(twice(n))
            |""".stripMargin) shouldBe "42\n"
    }
  }

  "how it differs from the neighbouring spelling" - {

    // `() -> T` is the same type. What differs is that the caller writes the callable, so the
    // argument is a closure rather than an expression that became one.
    "'() -> T' still asks the caller for a callable" in {
      run("""take(f: () -> int) -> int = f() + f()
            |
            |print(take(() -> 21))
            |""".stripMargin) shouldBe "42\n"
    }

    // The by-name body writes the parameter bare, with no call. That is the other half of the
    // sugar: the caller stops writing the closure and the callee stops writing the call.
    "a by-name body names it without calling it" in {
      run("""take(x: -> int) -> int = x + x
            |
            |print(take(21))
            |""".stripMargin) shouldBe "42\n"
    }
  }

  "what it refuses" - {

    "a struct field written by name, which is storage and not a call" in {
      err("""struct Holder
            |    x: -> int
            |end Holder
            |
            |print(1)
            |""".stripMargin) should not be empty
    }
  }
}
