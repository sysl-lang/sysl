package sh.sysl

import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.should.Matchers

/** `@assert` — a condition settled while compiling.
 *
 * `require` is a runtime precondition (`17`), so until this there was no way to fail a build on a
 * fact the compiler already knew. What wants one most is a binding: sysl lays a struct out in
 * declaration order and claims C compatibility by construction (`15 §1`), and from inside sysl that
 * claim could not be checked, because `sizeof` reports what sysl laid out rather than what the
 * header says. Paired with a `_Static_assert` in a `.c` beside it, the two pin both sides to one
 * number.
 */
class AssertTests extends AnyFreeSpec with Matchers with CodegenSupport with RunSupport {

  "a true assertion emits nothing and gets out of the way" - {
    "over literals" in {
      run(
        """@assert(1 + 1 == 2)
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    // The window matters: asserts are settled after every constant has folded, so the condition may
    // name one wherever it was written — including below.
    "naming a const declared below it" in {
      run(
        """@assert(capacity == 512)
          |const capacity: usize = 512
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    "over sizeof, which is the case it exists for" in {
      run(
        """struct FRect
          |    x: f32
          |    y: f32
          |    w: f32
          |    h: f32
          |end FRect
          |@assert(sizeof(FRect) == 16, "FRect must match SDL_FRect")
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    "over alignof" in {
      run(
        """@assert(alignof(i32) == 4)
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }

    "several, each settled on its own" in {
      run(
        """@assert(sizeof(u8) == 1)
          |@assert(sizeof(u16) == 2)
          |@assert(sizeof(u32) == 4)
          |print(1)""".stripMargin
      ) shouldBe "1\n"
    }
  }

  "a false assertion stops the compilation" - {
    // The reader's own sentence, because they know what the number means and the expression alone
    // says only that two of them differ.
    "quoting the message where there is one" in {
      err(
        """struct FRect
          |    x: f32
          |    y: f32
          |end FRect
          |@assert(sizeof(FRect) == 16, "FRect must match SDL_FRect")
          |print(1)""".stripMargin
      ) should include("assertion failed: FRect must match SDL_FRect")
    }

    "and saying so plainly where there is not" in {
      err(
        """@assert(1 == 2)
          |print(1)""".stripMargin
      ) should include("assertion failed")
    }
  }

  "what it refuses" - {
    // A call is the line `13 §Constants` draws around a constant expression, and it is the line that
    // makes this feature necessary rather than redundant: a shim accessor is a call, so a C
    // library's constants cannot be `const`s, which is why they have to be written down and checked.
    "a condition that is not a constant expression" in {
      err(
        """f() -> int = 3
          |@assert(f() == 3)
          |print(1)""".stripMargin
      ) should include("has to be a constant expression")
    }

    "a condition that is not a boolean" in {
      err(
        """@assert(1 + 1)
          |print(1)""".stripMargin
      ) should include("an assertion is something that can be true or false")
    }

    "a val, which is read while the program runs" in {
      err(
        """val limit: int = 10
          |@assert(limit == 10)
          |print(1)""".stripMargin
      ) should include("has to be a constant expression")
    }
  }

  // Two paths reach a check, and they must not overlap. In a module file an assert is a
  // declaration, collected by hoisting and settled by the walk; in the entry file it is a statement
  // (`13 §7`) and is settled where it is met. One assert must produce one message, not two.
  "in a module file" - {
    "a true one is silent" in {
      runOf(
        "m.sysl" ->
          """module m
            |const capacity: usize = 512
            |@assert(capacity == 512)""".stripMargin,
        "main.sysl" -> "print(1)",
      ) shouldBe "1\n"
    }

    "a false one is reported exactly once" in {
      val msg = errOf(
        "m.sysl" ->
          """module m
            |@assert(1 == 2, "arithmetic broke")""".stripMargin,
        "main.sysl" -> "print(1)",
      )

      msg should include("assertion failed: arithmetic broke")
      msg.sliding("assertion failed".length).count(_ == "assertion failed") shouldBe 1
    }
  }

}
